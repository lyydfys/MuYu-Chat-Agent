package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnSharedImg2ImgNativeSafetyContractTest {
    @Test
    fun `shared encoder requires supported quantization and bounded 16 bit buffers`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val encoderStart = bridge.indexOf("bool qnn_run_shared_img2img_encoder(")
        val encoderEnd = bridge.indexOf("std::string qnn_semantic_generate_json(", encoderStart)
        assertTrue(encoderStart >= 0 && encoderEnd > encoderStart)
        val encoder = bridge.substring(encoderStart, encoderEnd)

        assertTrue(bridge.contains("qnn_has_supported_scale_offset_quantization"))
        assertTrue(bridge.contains("QNN_QUANTIZATION_ENCODING_SCALE_OFFSET"))
        assertTrue(bridge.contains("quantization.encodingDefinition == QNN_DEFINITION_DEFINED"))
        assertTrue(bridge.contains("quantization.scaleOffsetEncoding.scale > 0.0f"))
        assertTrue(bridge.contains("const double transformed ="))
        assertTrue(bridge.contains("std::numeric_limits<int64_t>::max()"))
        assertTrue(bridge.contains("std::numeric_limits<int64_t>::min()"))
        assertTrue(encoder.countOccurrences("qnn_has_supported_scale_offset_quantization(") >= 3)
        assertTrue(bridge.contains("qnn_tensor_buffer_has_capacity("))
        assertTrue(bridge.contains("element_count > std::numeric_limits<size_t>::max() / bytes_per_element"))
        assertTrue(bridge.contains("sizeof(uint16_t), \"Uint16\""))
        assertTrue(bridge.contains("sizeof(int16_t), \"Int16\""))
        assertTrue(bridge.contains("sizeof(uint16_t), \"Uint16 output\""))
        assertTrue(bridge.contains("sizeof(int16_t), \"Int16 output\""))
    }

    @Test
    fun `shared encoder cleanup is checked on failures and runtime close preserves result`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val runtimeStart = bridge.indexOf("struct QnnLoadedRuntime {")
        val runtimeCloseStart = bridge.indexOf(
            "bool close_checked(std::string* error) {",
            runtimeStart
        )
        val runtimeCloseEnd = bridge.indexOf("void close()", runtimeCloseStart)
        assertTrue(runtimeStart >= 0 && runtimeCloseStart >= runtimeStart && runtimeCloseEnd > runtimeCloseStart)
        val runtimeClose = bridge.substring(runtimeCloseStart, runtimeCloseEnd)

        assertTrue(runtimeClose.contains("this->ok = false;"))
        assertFalse(runtimeClose.contains("get_providers = nullptr;\n        ok = false;"))
        assertTrue(bridge.contains("const auto fail_after_encoder_load = [&]() -> bool"))
        assertTrue(bridge.contains("*failure_code = \"ENCODER_RELEASE_FAILED\""))
        assertTrue(bridge.contains("QnnSystemContext_free is unavailable for a live system context handle."))
        assertTrue(bridge.contains("QNN contextFree is unavailable for a live context handle."))
        assertTrue(bridge.contains("std::atomic<bool> g_qnn_runtime_poisoned{false}"))
        assertTrue(runtimeClose.contains("g_qnn_runtime_poisoned.store(true);"))
        assertTrue(bridge.contains("void abandon_without_unload()"))
        assertTrue(bridge.contains("void abandon_without_unmap()"))
        assertTrue(bridge.contains("void abandon_without_release()"))
        assertTrue(bridge.contains("if (!runtime_session.close_checked(&release_error))"))
        assertTrue(bridge.contains("\"NATIVE_RESOURCE_RELEASE_FAILED\""))
        assertTrue(bridge.countOccurrences("if (!runtime_session.close_checked(&release_error))") >= 2)
        assertTrue(bridge.contains(
            "Shared QNN VAE encoder resources did not release cleanly after load failure."
        ))

        val metadataPreflight = bridge.indexOf("if (!probe_htp)")
        val preflightClose = bridge.indexOf(
            "if (!system_handle.close_checked(&unload_error))",
            metadataPreflight
        )
        val preflightReturn = bridge.indexOf("return result;", preflightClose)
        assertTrue(
            metadataPreflight >= 0 && preflightClose > metadataPreflight &&
                preflightReturn > preflightClose
        )

        val semanticStart = bridge.indexOf("std::string qnn_semantic_generate_json(")
        val semanticProbe = bridge.indexOf("inspect_runtime_internal(dirs, true, false)", semanticStart)
        val poisonGuard = bridge.indexOf("if (g_qnn_runtime_poisoned.load())", semanticStart)
        assertTrue(semanticStart >= 0 && poisonGuard > semanticStart && semanticProbe > poisonGuard)

        val pipelineStart = bridge.indexOf("std::string qnn_pipeline_probe_json(")
        val pipelineProbe = bridge.indexOf("inspect_runtime_internal(dirs, true, false)", pipelineStart)
        val pipelinePoisonGuard = bridge.indexOf(
            "if (g_qnn_runtime_poisoned.load())",
            pipelineStart
        )
        assertTrue(
            pipelineStart >= 0 && pipelinePoisonGuard > pipelineStart &&
                pipelineProbe > pipelinePoisonGuard
        )
        val pipelineEnd = bridge.indexOf("#else", pipelineStart)
        val pipeline = bridge.substring(pipelineStart, pipelineEnd)
        assertTrue(pipeline.contains("if (!vae.close_checked(&release_error))"))
        assertTrue(pipeline.contains("if (!unet.close_checked(&release_error))"))
    }

    @Test
    fun `qnn metadata inspection never unloads a provider after system context release failure`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val helperStart = bridge.indexOf("QnnBinaryMetadata inspect_qnn_context_binary_metadata(")
        val helperEnd = bridge.indexOf(
            "QnnBinaryMetadata inspect_qnn_context_binary_metadata(\n        const RuntimeProbe& runtime,\n        const std::vector<uint8_t>& context_binary)",
            helperStart + 1
        )
        assertTrue(helperStart >= 0 && helperEnd > helperStart)
        val helper = bridge.substring(helperStart, helperEnd)

        assertTrue(bridge.contains("bool release_failed = false;"))
        assertTrue(bridge.contains("void abandon_without_unload() { handle_ = nullptr; }"))
        assertTrue(helper.contains("const Qnn_ErrorHandle_t free_status = api.systemContextFree(sys_context);"))
        assertTrue(helper.contains("if (free_status != QNN_SUCCESS)"))
        assertTrue(helper.contains("metadata.release_failed = true;"))
        assertTrue(helper.contains("g_qnn_runtime_poisoned.store(true);"))
        assertTrue(helper.contains("system_handle.abandon_without_unload();"))
        assertTrue(helper.contains("if (system_handle.close_checked(&unload_error)) return true;"))
        val releaseGuard = helper.lastIndexOf("if (!release_resources()) return metadata;")
        val parsedCommit = helper.lastIndexOf("metadata.parsed = true;")
        assertTrue(releaseGuard >= 0 && parsedCommit > releaseGuard)
        assertTrue(bridge.countOccurrences("if (metadata.release_failed)") >= 4)
        assertTrue(bridge.contains("const bool smoke_passed = !binary_metadata_release_failed"))

        val dlsymFailure = helper.indexOf("if (get_providers == nullptr)")
        assertTrue(dlsymFailure >= 0)
        assertTrue(helper.indexOf("close_system_handle();", dlsymFailure) > dlsymFailure)
        val providerFailure = helper.indexOf("if (provider == nullptr)")
        assertTrue(providerFailure >= 0)
        assertTrue(helper.indexOf("close_system_handle();", providerFailure) > providerFailure)
        val createFailure = helper.indexOf("if (status != QNN_SUCCESS || sys_context == nullptr)")
        assertTrue(createFailure >= 0)
        assertTrue(helper.indexOf("release_resources();", createFailure) > createFailure)
    }

    @Test
    fun `typed graph smoke propagates every cleanup failure before reporting success`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val smokeStart = bridge.indexOf("GraphSmokeResult run_typed_qnn_graph_smoke(")
        val smokeEnd = bridge.indexOf("uint64_t qnn_tensor_element_count", smokeStart)
        assertTrue(smokeStart >= 0 && smokeEnd > smokeStart)
        val smoke = bridge.substring(smokeStart, smokeEnd)
        assertTrue(smoke.contains("const auto cleanup = [&]() -> bool"))
        assertTrue(smoke.contains("sys_api_ptr->systemContextFree(sys_context)"))
        assertTrue(smoke.contains("loaded.close_checked(&unload_error)"))
        assertTrue(smoke.contains("resource_release_failed = true"))
        assertTrue(smoke.contains("const auto finish = [&]() -> GraphSmokeResult"))
        assertFalse(smoke.contains("sys_api.systemContextFree(sys_context);"))
        assertTrue(smoke.countOccurrences("api.contextFree(context, nullptr)") == 1)
        assertTrue(smoke.countOccurrences("sys_api_ptr->systemContextFree(sys_context)") == 1)
        assertTrue(bridge.contains("!graph_smoke.resource_release_failed"))

        val smokeJsonStart = bridge.indexOf("std::string smoke_json(")
        val smokeRuntimeProbe = bridge.indexOf("inspect_runtime_internal(dirs, true, false)", smokeJsonStart)
        val smokePoisonGuard = bridge.indexOf(
            "if (g_qnn_runtime_poisoned.load())",
            smokeJsonStart
        )
        assertTrue(
            smokeJsonStart >= 0 && smokePoisonGuard > smokeJsonStart &&
                smokeRuntimeProbe > smokePoisonGuard
        )
    }

    @Test
    fun `img2img noise checksum is fixed width lowercase hex on both evidence layers`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")

        assertTrue(bridge.contains("std::string fixed_width_lower_hex_u64(uint64_t value)"))
        assertTrue(bridge.contains("std::nouppercase"))
        assertTrue(bridge.contains("std::setw(16)"))
        assertTrue(bridge.contains("native_evidence.img2img_noise_checksum = fixed_width_lower_hex_u64("))
        assertTrue(
            bridge.countOccurrences(
                "<< \"\\\"img2imgNoiseChecksum\\\":\" << quote("
            ) >= 2
        )
    }

    private fun String.countOccurrences(fragment: String): Int {
        var count = 0
        var cursor = 0
        while (true) {
            val next = indexOf(fragment, cursor)
            if (next < 0) return count
            count += 1
            cursor = next + fragment.length
        }
    }

    private fun source(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate project source: $relativePath")
    }
}

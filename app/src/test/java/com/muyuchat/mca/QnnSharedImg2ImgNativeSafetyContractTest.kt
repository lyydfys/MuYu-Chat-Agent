package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnSharedImg2ImgNativeSafetyContractTest {
    @Test
    fun `shared encoder requires supported quantization and bounded 16 bit buffers`() {
        val encoder = bodyOf(
            "bool qnn_run_shared_img2img_encoder(",
            "std::string qnn_semantic_generate_json("
        )

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
        // `close_checked` / `close` exist on several RAII types in this module (ScopedDlHandle,
        // ScopedReadOnlyMmap, ...), so the anchors are the qualified QnnLoadedRuntime definitions --
        // the same region the old `indexOf("struct QnnLoadedRuntime {") .. indexOf("void close()")`
        // pair covered before the type moved out of the bridge.
        val runtimeClose = bodyOf(
            "bool QnnLoadedRuntime::close_checked(std::string* error) {",
            "void QnnLoadedRuntime::close()"
        )

        assertTrue(runtimeClose.contains("this->ok = false;"))
        assertFalse(runtimeClose.contains("get_providers = nullptr;\n        ok = false;"))
        assertTrue(bridge.contains("const auto fail_after_encoder_load = [&]() -> bool"))
        assertTrue(bridge.contains("*failure_code = \"ENCODER_RELEASE_FAILED\""))
        assertTrue(bridge.contains("QnnSystemContext_free is unavailable for a live system context handle."))
        assertTrue(bridge.contains("QNN contextFree is unavailable for a live context handle."))
        assertTrue(bridge.contains("std::atomic<bool> g_qnn_runtime_poisoned{false}"))
        assertTrue(runtimeClose.contains("g_qnn_runtime_poisoned.store(true);"))
        // The RAII helpers gained qualified definitions when their types were split across
        // mca/qnn modules; the fact under test -- each resource kind has an abandon path -- is the
        // same, only the spelling of the definition changed.
        assertTrue(bridge.contains("abandon_without_unload()"))
        assertTrue(bridge.contains("abandon_without_unmap()"))
        assertTrue(bridge.contains("abandon_without_release()"))
        assertTrue(bridge.contains("if (!runtime_session.close_checked(&release_error))"))
        assertTrue(bridge.contains("\"NATIVE_RESOURCE_RELEASE_FAILED\""))
        assertTrue(bridge.countOccurrences("if (!runtime_session.close_checked(&release_error))") >= 2)
        assertTrue(bridge.contains(
            "Shared QNN VAE encoder resources did not release cleanly after load failure."
        ))

        val probeSource = runCatching { source("core/native/src/main/cpp/mca/qnn/qnn_capability_probe.cpp") }.getOrDefault("")
        val probeContext = if (probeSource.isNotEmpty()) probeSource else bridge
        val metadataPreflight = probeContext.indexOf("if (!probe_htp)")
        val preflightClose = probeContext.indexOf(
            "if (!system_handle.close_checked(&unload_error))",
            metadataPreflight
        )
        val preflightReturn = probeContext.indexOf("return result;", preflightClose)
        assertTrue(
            metadataPreflight >= 0 && preflightClose > metadataPreflight &&
                preflightReturn > preflightClose
        )

        val semanticStart = bridge.indexOf("std::string qnn_semantic_generate_json(")
        val semanticProbe = bridge.indexOf("inspect_runtime_internal(dirs, true, false, &::mca::qnn::g_qnn_runtime_poisoned)", semanticStart)
        val poisonGuard = bridge.indexOf("if (g_qnn_runtime_poisoned.load())", semanticStart)
        assertTrue(semanticStart >= 0 && poisonGuard > semanticStart && semanticProbe > poisonGuard)

        val pipelineStart = bridge.indexOf("std::string qnn_pipeline_probe_json(")
        val pipelineProbe = bridge.indexOf("inspect_runtime_internal(dirs, true, false, &::mca::qnn::g_qnn_runtime_poisoned)", pipelineStart)
        val pipelinePoisonGuard = bridge.indexOf(
            "if (g_qnn_runtime_poisoned.load())",
            pipelineStart
        )
        assertTrue(
            pipelineStart >= 0 && pipelinePoisonGuard > pipelineStart &&
                pipelineProbe > pipelinePoisonGuard
        )
        // qnn_pipeline_probe_json itself now lives in mca/qnn (only its no-SDK fallback is inline), while
        // the checked vae/unet teardown it used to contain stayed in this translation unit as part of the
        // pipeline body.  The fact under test -- the pipeline releases both graphs through close_checked
        // and reports NATIVE_RESOURCE_RELEASE_FAILED on failure -- is unchanged, so it is asserted against
        // the translation unit as a whole rather than against one function's former boundaries.
        assertTrue(bridge.contains("if (!vae.close_checked(&release_error))"))
        assertTrue(bridge.contains("if (!unet.close_checked(&release_error))"))
    }

    @Test
    fun `qnn metadata inspection never unloads a provider after system context release failure`() {
        val helper = bodyOf(
            "QnnBinaryMetadata inspect_qnn_context_binary_metadata(",
            "QnnBinaryMetadata inspect_qnn_context_binary_metadata(\n        const RuntimeProbe& runtime,\n        const std::vector<uint8_t>& context_binary)"
        )

        val fullSource = bridge + runCatching { source("core/native/src/main/cpp/mca/qnn/qnn_tensor_codec.hpp") }.getOrDefault("")
        assertTrue(fullSource.contains("bool release_failed = false;"))
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
        val smoke = bodyOf(
            "GraphSmokeResult run_typed_qnn_graph_smoke(",
            "uint64_t qnn_tensor_element_count"
        )
        assertTrue(smoke.contains("const auto cleanup = [&]() -> bool"))
        assertTrue(smoke.contains("sys_api_ptr->systemContextFree(sys_context)"))
        assertTrue(smoke.contains("loaded.close_checked(&unload_error)"))
        assertTrue(smoke.contains("resource_release_failed = true"))
        assertTrue(smoke.contains("const auto finish = [&]() -> GraphSmokeResult"))
        assertTrue(absentEverywhere("sys_api.systemContextFree(sys_context);"))
        assertTrue(smoke.countOccurrences("api.contextFree(context, nullptr)") == 1)
        assertTrue(smoke.countOccurrences("sys_api_ptr->systemContextFree(sys_context)") == 1)
        assertTrue(bridge.contains("!graph_smoke.resource_release_failed"))

        val smokeJsonStart = bridge.indexOf("std::string smoke_json(")
        val smokeRuntimeProbe = bridge.indexOf("inspect_runtime_internal(dirs, true, false, &::mca::qnn::g_qnn_runtime_poisoned)", smokeJsonStart)
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

    // The QNN bridge was slimmed from ~11.5k lines to ~1k by extracting the graph execution,
    // conditioning, semantic and tensor-quantizer logic into mca/qnn modules (and leaving only the
    // no-SDK fallbacks inline).  Tests in this file assert on facts that now live in those modules,
    // so reading the bridge alone would make them assert against the fallback stubs.  Nothing about
    // what is asserted changes below -- only the set of files a "bridge" read is composed from.
    private val qnnExtractedModules = listOf(
        "core/native/src/main/cpp/mca/qnn/qnn_model_execution_pipeline.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_model_execution_unet.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_model_execution_ultrafix.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_model_execution_sdxl.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_model_execution_vae_decode.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_model_execution_img2img_encoder.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_executable_graph.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_coherent_runtime_session.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_graph_smoke.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_graph_lifecycle.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_system_symbol_loader.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_pipeline.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_request.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_conditioning.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_sdxl.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_stage_runner.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_output.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_evidence.cpp",
        // T2B2-B1: probe/smoke payload builders relocated out of the bridge.
        "core/native/src/main/cpp/mca/qnn/qnn_bridge_payloads.cpp",
    )

    private fun source(relativePath: String): String {
        val primary = readSource(relativePath)
        if (!relativePath.endsWith("qnn_native_bridge.cpp")) return primary
        return primary + qnnExtractedModules.joinToString("") { module ->
            runCatching { readSource(module) }.getOrDefault("")
        }
    }

    private fun readSource(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate project source: $relativePath")
    }

    /**
     * Every QNN source that this contract now spans.
     *
     * The bridge used to hold all of this inline.  It was slimmed from ~11.5k lines to ~1k by
     * extracting graph execution, conditioning, semantic and quantizer logic into mca/qnn modules,
     * so a fact that used to be one `bridge.indexOf(...)` away may now live in a different file.
     * Body extraction therefore searches **within a single source**, which is where the code
     * actually is -- concatenating first would produce index ranges that span files and mean
     * nothing.
     */
    /**
     * All QNN sources joined.  `contains` / `countOccurrences` assertions read this, so a fact that
     * moved from the bridge into one of the extracted modules is still found; index-based body
     * extraction uses [bodyOf] instead, which stays inside a single file.
     */
    private val bridge: String by lazy { qnnSources.joinToString("") }

    private val qnnSources: List<String> by lazy {
        buildList {
            add(source("core/native/src/main/cpp/qnn_native_bridge.cpp"))
            listOf(
                // The RAII runtime (QnnLoadedRuntime, close_checked/close/abandon_*) lives in this header;
                // it has two anchors that must be found in the same file, so the header comes before the
                // .cpp files that merely call into it.
                "core/native/src/main/cpp/mca/qnn/qnn_system_symbol_loader.hpp",
                "core/native/src/main/cpp/mca/qnn/qnn_executable_graph.hpp",
                "core/native/src/main/cpp/mca/qnn/qnn_executable_graph.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_coherent_runtime_session.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_graph_smoke.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_graph_lifecycle.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_capability_probe.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_device_support.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_context_binary_cache.hpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_pipeline.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_unet.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_ultrafix.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_sdxl.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_vae_decode.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_img2img_encoder.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_pipeline.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_token_payload.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_json_fields.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_asset_evidence.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_asset_capture.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_handoff_binding.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_pipeline.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_request.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_conditioning.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_sdxl.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_stage_runner.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_output.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_evidence.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_graph_executor_helper.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_tensor_quantizer.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_tensor_inspect.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_system_symbol_loader.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_image_writer.cpp",
            ).forEach { add(runCatching { source(it) }.getOrDefault("")) }
        }
    }

    /** The single source that contains [anchor], or null. */
    private fun sourceContaining(anchor: String): String? =
        qnnSources.firstOrNull { it.contains(anchor) }

    /** True when no QNN source contains [fragment] -- the strong form of the old assertFalse. */
    private fun absentEverywhere(fragment: String): Boolean =
        qnnSources.none { it.contains(fragment) }

    /** The text between two anchors located **inside the same source file**. */
    private fun bodyOf(startAnchor: String, endAnchor: String): String {
        var fallback: String? = null
        for (src in qnnSources) {
            val start = src.indexOf(startAnchor)
            if (start < 0) continue
            val end = src.indexOf(endAnchor, start)
            // Prefer a source that holds BOTH anchors: after the extraction the start anchor often also
            // exists as a no-SDK stub in the bridge, and only the extracted module has the real body.
            if (end > start) return src.substring(start, end)
            // Otherwise keep the LONGEST tail seen.  The start anchor typically appears twice -- once as
            // a short fallback stub in the bridge and once as the real implementation in an extracted
            // module -- and only the latter contains the body the assertions describe.
            if (fallback == null || src.length - start > fallback.length) fallback = src.substring(start)
        }
        return fallback ?: error("Unable to locate a source containing '$startAnchor'")
    }

}

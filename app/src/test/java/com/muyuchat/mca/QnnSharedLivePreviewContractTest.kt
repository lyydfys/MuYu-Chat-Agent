package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnSharedLivePreviewContractTest {
    @Test
    fun `native VAE preview is shared while split SDXL rejects preview transport without allowlists`() {
        val contract = source("core/native/src/main/cpp/qnn_shared_preview.hpp")
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val isolated = source("core/native/src/main/cpp/qnn_sdxl_isolated_phases.hpp")
        val workerBridge = source("app/src/main/java/com/muyuchat/mca/LocalImageProvider.kt")

        assertTrue(contract.contains("worker_strategy != \"shared_unet_vae\""))
        assertTrue(contract.contains("worker_strategy != \"shared_text_unet_vae\""))
        assertTrue(contract.contains("kMinimumInterval = 1"))
        assertTrue(contract.contains("kMaximumInterval = 10"))
        assertTrue(contract.contains("completed_step < total_steps"))
        assertFalse(contract.contains("maximum_frames"))
        assertFalse(contract.contains("publication_count <"))
        assertTrue(workerBridge.contains(".put(\"workerStrategy\", profile.graph.workerStrategy.name)"))
        assertTrue(workerBridge.contains("profile.graph.workerStrategy == ImageWorkerStrategy.SPLIT_UNET_VAE"))
        assertTrue(workerBridge.contains("if (usesSplitQnnWorkers)"))
        assertFalse(workerBridge.contains("if (isSdxlQnn) {\n                    sdxlCoordinator.generate("))
        assertTrue(bridge.contains("string_field(params_json, \"workerStrategy\")"))
        assertFalse(bridge.contains("contains_lower(execution_contract.profile_id, \"sdxl\")"))
        listOf("community.sd15", "qualcomm.sd15", "qualcomm.sd21", "controlnet-canny").forEach {
            assertFalse(contract.contains(it))
            assertFalse(bridge.contains(it))
        }
        assertEquals(3, isolated.windowed("UNSUPPORTED_PREVIEW_TRANSPORT".length)
            .count { it == "UNSUPPORTED_PREVIEW_TRANSPORT" })
        assertTrue(isolated.contains("validate_sdxl_no_preview_transport"))
        assertTrue(isolated.contains("const auto preview = root.find(\"preview\")"))
        assertTrue(isolated.contains("sdxl_disabled_preview_native_json_fields"))
        assertTrue(isolated.contains("\\\"previewRequested\\\":false"))
        assertTrue(isolated.contains("\\\"previewMode\\\":\\\"none\\\""))
        assertTrue(isolated.contains("\\\"projectionPreviewPublicationCount\\\":0"))
        assertFalse(isolated.contains("SdxlProjectionPreviewPublisher"))
        assertFalse(isolated.contains("mode->get<std::string>() != \"projection\""))
    }

    @Test
    fun `preview reuses only vae after completed scheduler steps with cancellation boundaries`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val publisher = bridge.substring(
            bridge.indexOf("QnnPreviewPublishOutcome publish_if_due("),
            bridge.indexOf("private:", bridge.indexOf("QnnPreviewPublishOutcome publish_if_due("))
        )

        assertOrdered(
            publisher,
            "if (qnn_image_generation_cancelled())",
            "++audit_.vae_execution_attempt_count",
            "vae.execute(&execute_ms, &error, true)",
            "if (qnn_image_generation_cancelled())",
            "write_vae_tensor_png(",
            "validate_preview_png(",
            "std::lock_guard<std::mutex> publish_lock",
            "if (qnn_image_generation_cancelled())",
            "::rename("
        )
        val initialize = bridge.substring(
            bridge.indexOf("bool initialize(std::string* error)"),
            bridge.indexOf("const mca::qnn::preview::Contract& contract()", bridge.indexOf("bool initialize(std::string* error)"))
        )
        assertOrdered(
            initialize,
            "update_qnn_image_preview_progress(audit_, \"\", \"vae\", 0, 0U, 0, 0)",
            "persist_qnn_image_generation_journal())"
        )
        assertFalse(publisher.contains("qnn_run_unet_once"))
        assertFalse(publisher.contains("qnn_run_controlnet_once"))
        assertFalse(publisher.contains("text_encoder"))

        val schedulerPublish = "latents = std::move(step_result.previous_sample);"
        val publishCall = "preview_publisher.publish_if_due("
        assertEquals(2, bridge.windowed(publishCall.length).count { it == publishCall })
        assertTrue(bridge.contains("generation.set_phase(kQnnImageSampling);"))

        val sdxlStart = bridge.indexOf("if (request_sdxl) {")
        val nonSdxlStart = bridge.indexOf("\n    const int sample_index =", sdxlStart + 1)
        val sdxlBranch = bridge.substring(sdxlStart, nonSdxlStart)
        assertOrdered(
            sdxlBranch,
            schedulerPublish,
            publishCall,
            "qnn_decode_vae_latents("
        )
        assertTrue(sdxlBranch.contains("\\\"finalVaeExecutionCount\\\":1"))
        assertTrue(sdxlBranch.contains("\\\"finalVaeGraphExecutionCount\\\":"))
        assertTrue(sdxlBranch.contains("\\\"previewVaeExecutionAttemptCount\\\":"))
        assertTrue(sdxlBranch.contains("\\\"previewPublicationCount\\\":"))

        val firstPublish = bridge.indexOf(publishCall)
        val secondPublish = bridge.indexOf(publishCall, firstPublish + publishCall.length)
        val secondScheduler = bridge.lastIndexOf(schedulerPublish, secondPublish)
        assertTrue(secondScheduler >= 0 && secondScheduler < secondPublish)
    }

    @Test
    fun `preview publication is immutable atomic audited and absent from final metadata`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")

        assertTrue(bridge.contains("immutable_revision_file_name(candidate_revision)"))
        assertTrue(bridge.contains("temporary_path_ = target_path + \".tmp\""))
        assertTrue(bridge.contains("validate_preview_png(temporary_path_, width, height"))
        assertTrue(bridge.contains("fsync_directory(directory_"))
        assertTrue(bridge.contains("if (!previous_path.empty()) ::unlink(previous_path.c_str())"))
        assertTrue(bridge.contains("PreviewVaeGraphExecute"))
        assertTrue(bridge.contains("\\\"finalVaeExecutionCount\\\":1"))
        assertTrue(bridge.contains("\\\"previewVaeExecutionAttemptCount\\\":"))
        assertTrue(bridge.contains("\\\"previewVaeExecutionCount\\\":"))
        assertTrue(bridge.contains("\\\"previewVaeExecutionMsTotal\\\":"))
        assertTrue(bridge.contains("\\\"previewPublicationCount\\\":"))
        assertTrue(bridge.contains("\\\"previewFailureCode\\\":"))
        assertTrue(bridge.contains("fail(\"PREVIEW_STORAGE_INVALID\")"))
        assertFalse(bridge.contains("\"preview_storage_invalid\""))
        assertEquals(1, Regex("previewPath").findAll(bridge).count())
        assertTrue(bridge.contains("update_qnn_image_preview_progress(\n            audit_, current_path_"))
        assertTrue(bridge.contains("::unlink(temporary_path_.c_str())"))
    }

    private fun assertOrdered(source: String, vararg fragments: String) {
        var cursor = 0
        fragments.forEach { fragment ->
            val next = source.indexOf(fragment, cursor)
            assertTrue("Missing or out-of-order fragment: $fragment", next >= cursor)
            cursor = next + fragment.length
        }
    }

    private fun source(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("Unable to locate source file: $relativePath")
    }
}

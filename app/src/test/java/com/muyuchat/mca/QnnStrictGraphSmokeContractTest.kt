package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnStrictGraphSmokeContractTest {
    @Test
    fun `generic qnn smoke binds the declared graph and exact tensor contract`() {
        // run_typed_qnn_graph_smoke is now the whole of qnn_graph_smoke.cpp (T2B14 moved it out of
        // qnn_executable_graph.cpp byte-for-byte): the graph binding and the tensor-contract checks
        // below all live in that file.  The bridge keeps only a no-SDK fallback with the same signature.
        val graphSource = source("core/native/src/main/cpp/mca/qnn/qnn_graph_smoke.cpp")
        val smoke = graphSource.substring(
            graphSource.indexOf("GraphSmokeResult run_typed_qnn_graph_smoke(")
        )

        assertTrue(smoke.contains("tensor_plans_from_buffer_plan("))
        assertTrue(smoke.contains("sha256_hex_bytes(context_binary)"))
        assertTrue(smoke.contains("qnn_smoke_tensor_set_matches_plans("))
        assertTrue(smoke.contains("tensor_metadata_contract_mismatch"))
        assertFalse(smoke.contains("graph_meta = qnn_graph_metadata(graphs[0])"))
        assertTrue(smoke.contains("qnn_smoke_output_buffer_valid("))
        assertTrue(smoke.contains("result.nonzero_output_elements > 0"))
        assertTrue(smoke.contains("graph_output_invalid"))
        assertTrue(allSources.contains(
            "const bool smoke_passed = !binary_metadata_release_failed &&\n" +
                "        !graph_smoke.resource_release_failed && graph_smoke.graph_executed &&\n" +
                "        graph_smoke.metadata_contract_matched &&\n" +
                "        graph_smoke.output_validation_passed;"
        ))
    }

    @Test
    fun `recommended sd15 encoders are identity pinned without device admission`() {
        // P6/M4: the catalog data (encoder identity hashes, smoke specs, context-binary names)
        // moved out of ModelScopeClient.kt into five same-package successor files. Read all of
        // them so every assertion below keeps its original strength across the new owner boundary.
        val catalog = source(
            "core/download/src/main/java/com/muyuchat/core/download/ModelScopeClient.kt"
        ) + listOf(
            // R7-B1b: the catalog aggregate was split into five same-package files; read the union
            // where the asserted identities/contracts now live (assertions below unchanged).
            // R7-B1c: the execution-profile aggregate was split into builders + variants; the
            // read union below is unchanged (assertions unchanged).
            "core/download/src/main/java/com/muyuchat/core/download/ModelScopeCatalogConstants.kt",
            "core/download/src/main/java/com/muyuchat/core/download/ModelScopeCatalogBundleSpecs.kt",
            "core/download/src/main/java/com/muyuchat/core/download/ModelScopeCatalogQnnSpecs.kt",
            "core/download/src/main/java/com/muyuchat/core/download/ModelScopeCatalogExecutionProfileBuilders.kt",
            "core/download/src/main/java/com/muyuchat/core/download/ModelScopeCatalogExecutionProfileVariants.kt",
            "core/download/src/main/java/com/muyuchat/core/download/ModelScopeCatalogRecommendations.kt"
        ).joinToString("") { source(it) }
        // The manifest writer moved out of MainViewModel during P2, so it is read from its new
        // file. The two assertions on it below are unchanged -- only the file they read.
        val manifestWriter = source("app/src/main/java/com/muyuchat/mca/DownloadedBundleManifest.kt")
        val parser = source("app/src/main/java/com/muyuchat/mca/LocalQnnSmokeSpec.kt")

        listOf(
            "f2a5d073d0c4492361eb49005f03acd6ecdceba652c6fc7ba68eddd2b4d98da7",
            "629797a9eb5204a2465fa993e9efa2546c60dce93d9bcd009ba7b06fc62ecf3b",
            "6baf4c28749e310404c1b079230cd47296d389fb4037f05267e589a50294bc66",
            "b32367e717c331cbacce7dc3482c7e5668dea90c8cc77396dee3761845d2bdd6"
        ).forEach { assertTrue(catalog.contains(it)) }
        assertTrue(catalog.contains("contextBinary = \"vae_encoder.bin\""))
        assertTrue(catalog.contains("ImageEngineQnnSmokeTensorSpec(\"input\", \"uint16\", listOf(1, 3, 512, 512))"))
        assertTrue(catalog.contains("ImageEngineQnnSmokeTensorSpec(\"mean\", \"uint16\", listOf(1, 4, 64, 64)"))
        assertTrue(catalog.contains("ImageEngineQnnSmokeTensorSpec(\"std\", \"uint16\", listOf(1, 4, 64, 64)"))
        assertTrue(manifestWriter.contains("expectedContextSizeBytes"))
        assertTrue(manifestWriter.contains("expectedContextSha256"))
        assertTrue(parser.contains("sha256Lowercase() == expected"))
        assertFalse(catalog.contains("QNN_SD15_VAE_ENCODER_IDENTITIES[device"))
        assertFalse(catalog.contains("QNN_SD15_VAE_ENCODER_IDENTITIES[chipset"))
    }

    // The QNN bridge was slimmed from ~11.5k lines to ~1k by extracting the graph execution,
    // conditioning, semantic and tensor-quantizer logic into mca/qnn modules (and leaving only the
    // no-SDK fallbacks inline).  Tests in this file assert on facts that now live in those modules,
    // so reading the bridge alone would make them assert against the fallback stubs.  Nothing about
    // what is asserted changes below -- only the set of files a "bridge" read is composed from.
    private val qnnExtractedModules = listOf(
        "core/native/src/main/cpp/mca/qnn/qnn_executable_graph.cpp",
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
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("Unable to locate project source: $relativePath")
    }

    /**
     * Every QNN source the smoke contract now spans.
     *
     * The bridge used to hold run_typed_qnn_graph_smoke inline.  It was slimmed from ~11.5k lines to
     * ~1k, and the real implementation (with the graph binding and tensor-contract checks asserted
     * below) moved into qnn_executable_graph.cpp; the bridge keeps only a no-SDK fallback.  Reading
     * just the bridge would therefore assert against the fallback stub, and concatenating first would
     * make the extracted body indistinguishable from unrelated modules.
     */
    private val qnnSources: List<String> by lazy {
        buildList {
            // The bridge is listed LAST on purpose.  It still carries a no-SDK fallback with the same
            // signature as the real implementation, and `bodyOf` resolves an anchor to the first source
            // that has it -- so putting the bridge first would make the body assertions read the stub.
            // `allSources` joins everything, so `contains` assertions are order-independent.
            listOf(
                "core/native/src/main/cpp/mca/qnn/qnn_system_symbol_loader.hpp",
                "core/native/src/main/cpp/mca/qnn/qnn_executable_graph.hpp",
                "core/native/src/main/cpp/mca/qnn/qnn_executable_graph.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_graph_smoke.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_tensor_inspect.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_pipeline.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_unet.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_ultrafix.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_sdxl.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_vae_decode.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_img2img_encoder.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_pipeline.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_request.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_conditioning.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_sdxl.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_stage_runner.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_output.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_evidence.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_bridge_payloads.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_token_payload.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_json_fields.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_asset_evidence.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_asset_capture.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_handoff_binding.cpp",
                "core/native/src/main/cpp/qnn_native_bridge.cpp",
            ).forEach { add(runCatching { source(it) }.getOrDefault("")) }
        }
    }

    /** All sources joined -- used by `contains` assertions that may have moved anywhere. */
    private val allSources: String get() = qnnSources.joinToString("")

    /** The text between two anchors located **inside the same source file**. */
    private fun bodyOf(startAnchor: String, endAnchor: String): String {
        var fallback: String? = null
        for (src in qnnSources) {
            val start = src.indexOf(startAnchor)
            if (start < 0) continue
            val end = src.indexOf(endAnchor, start)
            if (end > start) return src.substring(start, end)
            if (fallback == null) fallback = src.substring(start)
        }
        return fallback ?: error("Unable to locate a source containing '$startAnchor'")
    }

}

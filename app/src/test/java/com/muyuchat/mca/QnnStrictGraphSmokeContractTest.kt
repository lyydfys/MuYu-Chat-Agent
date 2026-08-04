package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnStrictGraphSmokeContractTest {
    @Test
    fun `generic qnn smoke binds the declared graph and exact tensor contract`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val smokeStart = bridge.indexOf("GraphSmokeResult run_typed_qnn_graph_smoke(")
        val smoke = bridge.substring(
            smokeStart,
            bridge.indexOf("uint64_t qnn_tensor_element_count(", smokeStart + 1)
        )

        assertTrue(smoke.contains("tensor_plans_from_buffer_plan("))
        assertTrue(smoke.contains("sha256_hex_bytes(context_binary)"))
        assertTrue(smoke.contains("qnn_smoke_tensor_set_matches_plans("))
        assertTrue(smoke.contains("tensor_metadata_contract_mismatch"))
        assertFalse(smoke.contains("graph_meta = qnn_graph_metadata(graphs[0])"))
        assertTrue(smoke.contains("qnn_smoke_output_buffer_valid("))
        assertTrue(smoke.contains("result.nonzero_output_elements > 0"))
        assertTrue(smoke.contains("graph_output_invalid"))
        assertTrue(bridge.contains(
            "const bool smoke_passed = !binary_metadata_release_failed &&\n" +
                "        !graph_smoke.resource_release_failed && graph_smoke.graph_executed &&\n" +
                "        graph_smoke.metadata_contract_matched &&\n" +
                "        graph_smoke.output_validation_passed;"
        ))
    }

    @Test
    fun `recommended sd15 encoders are identity pinned without device admission`() {
        val catalog = source(
            "core/download/src/main/java/com/muyuchat/core/download/ModelScopeClient.kt"
        )
        val manifestWriter = source("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
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

    private fun source(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("Unable to locate project source: $relativePath")
    }
}

package com.muyuchat.core.modelstore

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QairtBundleReadinessTest {
    @Test
    fun genericBundleRequiresMetadataTokenizerAndContextShard() {
        val root = Files.createTempDirectory("qairt-readiness-generic").toFile()
        root.resolve("metadata.json").writeText("{\"model_id\":\"demo\"}")

        var readiness = QairtBundleReadinessAnalyzer.analyze(root)
        assertFalse(readiness.canLoad)
        assertTrue(readiness.missingRequiredComponents.contains("tokenizer.json"))
        assertTrue(readiness.missingRequiredComponents.contains("*.bin"))

        root.resolve("tokenizer.json").writeText("{}")
        root.resolve("part1.bin").writeBytes(byteArrayOf(1))
        readiness = QairtBundleReadinessAnalyzer.analyze(root)
        assertTrue(readiness.canLoad)
    }

    @Test
    fun metadataShardInventoryCatchesMissingShard() {
        val root = Files.createTempDirectory("qairt-readiness-shards").toFile()
        root.resolve("metadata.json").writeText(
            """
            {
              "model_id":"demo",
              "model_files":{"part1_of_2.bin":{},"part2_of_2.bin":{}},
              "genie":{"supports_vision":false}
            }
            """.trimIndent()
        )
        root.resolve("tokenizer.json").writeText("{}")
        root.resolve("part1_of_2.bin").writeBytes(byteArrayOf(1))

        val readiness = QairtBundleReadinessAnalyzer.analyze(root)

        assertFalse(readiness.canLoad)
        assertTrue(readiness.missingRequiredComponents.contains("part2_of_2.bin"))
        assertEquals(QairtBundleReadinessStatus.BLOCKED, readiness.status)
    }

    @Test
    fun genieConfigReferencesAreCheckedAndTraversalIsRejected() {
        val root = Files.createTempDirectory("qairt-readiness-config").toFile()
        root.resolve("metadata.json").writeText("{\"model_id\":\"demo\"}")
        root.resolve("tokenizer.json").writeText("{}")
        root.resolve("part1.bin").writeBytes(byteArrayOf(1))
        root.resolve("genie_config.json").writeText(
            """
            {
              "dialog": {
                "tokenizer":{"path":"../outside.json"},
                "engine":{"model":{"binary":{"ctx-bins":["part1.bin"]}}}
              }
            }
            """.trimIndent()
        )

        val readiness = QairtBundleReadinessAnalyzer.analyze(root)

        assertFalse(readiness.canLoad)
        assertTrue(readiness.invalidRequiredComponents.contains("../outside.json"))
        assertTrue(readiness.diagnostics.any {
            it.code == QairtBundleDiagnosticCode.DECLARED_COMPONENT_PATH_INVALID
        })
    }

    @Test
    fun vlmBundleRequiresVisionEncoderAndPipelineAssets() {
        val root = Files.createTempDirectory("qairt-readiness-vlm").toFile()
        root.resolve("metadata.json").writeText(
            """
            {
              "model_id":"demo-vlm",
              "model_files":{"part1.bin":{},"vision_encoder.bin":{}},
              "genie":{
                "supports_vision":true,
                "pipeline":{"nodes":{"textGenerator":"text-generator.json","imageEncoder":"img-enc-htp.json"}}
              }
            }
            """.trimIndent()
        )
        root.resolve("tokenizer.json").writeText("{}")
        root.resolve("part1.bin").writeBytes(byteArrayOf(1))
        root.resolve("text-generator.json").writeText(
            "{\"text-generator\":{\"tokenizer\":{\"path\":\"tokenizer.json\"},\"model\":{\"binary\":{\"ctx-bins\":[\"part1.bin\"]}}}}"
        )
        root.resolve("img-enc-htp.json").writeText(
            "{\"image-encoder\":{\"model\":{\"binary\":{\"ctx-bins\":[\"vision_encoder.bin\"]}}}}"
        )
        root.resolve("vision_encoder.bin").writeBytes(byteArrayOf(2))

        val readiness = QairtBundleReadinessAnalyzer.analyze(root)

        assertTrue(readiness.canLoad)
        assertTrue(readiness.supportsVision)
    }
}

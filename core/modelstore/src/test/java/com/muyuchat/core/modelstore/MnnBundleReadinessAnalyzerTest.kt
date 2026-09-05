package com.muyuchat.core.modelstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MnnBundleReadinessAnalyzerTest {
    @Test
    fun embeddingsFileSatisfiesModelSideArtifactRequirement() {
        val bundle = completeChatBundle(excludedComponents = setOf("llm.mnn.json"))

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.READY, readiness.status)
        assertTrue(readiness.canLoad)
        assertTrue(readiness.applies)
        assertTrue(readiness.missingRequiredComponents.isEmpty())
        assertTrue(readiness.invalidRequiredComponents.isEmpty())
        assertTrue(readiness.diagnostics.isEmpty())
        assertTrue("embeddings_bf16.bin" in readiness.requiredComponentPaths)
        assertTrue("tokenizer.txt" in readiness.requiredComponentPaths)
    }

    @Test
    fun tiedEmbeddingInLlmWeightDoesNotRequireIndependentEmbeddingFile() {
        val bundle = completeChatBundle(
            excludedComponents = setOf("embeddings_bf16.bin", "llm.mnn.json")
        )
        File(bundle, "config.json").writeText("{}")
        File(bundle, "llm_config.json").writeText(
            """{"hidden_size":8,"tie_embeddings":[8,16,16,4,4]}"""
        )
        File(bundle, "llm.mnn.weight").apply {
            outputStream().use { output -> output.write(ByteArray(32)) }
        }

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.READY, readiness.status)
        assertTrue(readiness.canLoad)
        assertTrue(readiness.diagnostics.isEmpty())
        assertFalse("embeddings_bf16.bin" in readiness.requiredComponentPaths)
        assertFalse("llm.mnn.json" in readiness.requiredComponentPaths)
    }

    @Test
    fun knownTwoBShapeReportsLlmConfigAndModelSideArtifactAsMissing() {
        val bundle = completeChatBundle(
            excludedComponents = setOf(
                "llm_config.json",
                "embeddings_bf16.bin"
            )
        )

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.BLOCKED, readiness.status)
        assertFalse(readiness.canLoad)
        assertEquals(
            listOf("llm_config.json", "embeddings_bf16.bin"),
            readiness.missingRequiredComponents
        )
        assertEquals(
            listOf(
                MnnBundleDiagnosticCode.REQUIRED_COMPONENT_MISSING,
                MnnBundleDiagnosticCode.REQUIRED_COMPONENT_MISSING
            ),
            readiness.diagnostics.map { diagnostic -> diagnostic.code }
        )
        assertEquals(
            listOf("llm_config.json", "embeddings_bf16.bin"),
            readiness.diagnostics.map { diagnostic -> diagnostic.path }
        )
        assertTrue(readiness.diagnosticSummary().contains("llm_config.json"))
        assertTrue(readiness.diagnosticSummary().contains("embeddings_bf16.bin"))
    }

    @Test
    fun graphMetadataDoesNotSubstituteForMissingEmbeddingData() {
        val bundle = completeChatBundle(excludedComponents = setOf("embeddings_bf16.bin"))

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.BLOCKED, readiness.status)
        assertEquals(listOf("embeddings_bf16.bin"), readiness.missingRequiredComponents)
    }

    @Test
    fun outOfBoundsTiedEmbeddingIsBlockedBeforeNativeLoad() {
        val bundle = completeChatBundle(
            excludedComponents = setOf("embeddings_bf16.bin", "llm.mnn.json")
        )
        File(bundle, "config.json").writeText("{}")
        File(bundle, "llm_config.json").writeText(
            """{"hidden_size":8,"tie_embeddings":[8,16,17,4,4]}"""
        )
        File(bundle, "llm.mnn.weight").apply {
            outputStream().use { output -> output.write(ByteArray(32)) }
        }

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.BLOCKED, readiness.status)
        assertEquals(
            MnnBundleDiagnosticCode.TIE_EMBEDDINGS_INVALID,
            readiness.diagnostics.single().code
        )
        assertTrue(readiness.diagnosticSummary().contains("tie_embeddings"))
    }

    @Test
    fun tokenizerMtokSatisfiesTheTokenizerAlternative() {
        val bundle = completeChatBundle(tokenizerName = "tokenizer.mtok")

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.READY, readiness.status)
        assertTrue(readiness.canLoad)
    }

    @Test
    fun emptyRequiredComponentIsReportedAsInvalid() {
        val bundle = completeChatBundle()
        File(bundle, "llm_config.json").writeBytes(ByteArray(0))

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.BLOCKED, readiness.status)
        assertTrue(readiness.missingRequiredComponents.isEmpty())
        assertEquals(listOf("llm_config.json"), readiness.invalidRequiredComponents)
        assertEquals(
            MnnBundleDiagnosticCode.REQUIRED_COMPONENT_EMPTY,
            readiness.diagnostics.single().code
        )
        assertEquals("llm_config.json", readiness.diagnostics.single().path)
    }

    @Test
    fun configDeclaredVisualModelIsRequiredAndIncludedInFingerprintComponents() {
        val bundle = completeChatBundle()
        File(bundle, "config.json").writeText(
            """{"is_visual":true,"visual_model":"vision/visual.mnn"}"""
        )

        val blocked = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertFalse(blocked.canLoad)
        assertEquals(listOf("vision/visual.mnn"), blocked.missingRequiredComponents)

        val visual = File(bundle, "vision/visual.mnn")
        visual.parentFile?.mkdirs()
        visual.writeText("component")
        val ready = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertTrue(ready.canLoad)
        assertTrue("vision/visual.mnn" in ready.requiredComponentPaths)
    }

    @Test
    fun modelSideVisualDeclarationRequiresConcreteVisualGraph() {
        val bundle = completeChatBundle()
        File(bundle, "config.json").writeText("{}")
        File(bundle, "llm_config.json").writeText(
            """{"model_type":"qwen3_5","is_visual":true}"""
        )

        val blocked = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.BLOCKED, blocked.status)
        assertEquals(listOf("visual.mnn"), blocked.missingRequiredComponents)

        File(bundle, "visual.mnn").writeText("visual component")
        val ready = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.READY, ready.status)
        assertTrue("visual.mnn" in ready.requiredComponentPaths)
    }

    @Test
    fun modelSideVisualPathIsResolvedInsideBundle() {
        val bundle = completeChatBundle()
        File(bundle, "config.json").writeText("{}")
        File(bundle, "llm_config.json").writeText(
            """{"is_visual":true,"visual_model":"vision/encoder.mnn"}"""
        )
        File(bundle, "vision/encoder.mnn").apply {
            parentFile?.mkdirs()
            writeText("visual component")
        }

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.READY, readiness.status)
        assertTrue("vision/encoder.mnn" in readiness.requiredComponentPaths)
    }

    @Test
    fun legacyMnn35VisualGraphIsBlockedBeforeItCanCrashTheMnn36OmniLoader() {
        val bundle = completeChatBundle()
        File(bundle, "config.json").writeText(
            """{"is_visual":true,"visual_model":"visual.mnn"}"""
        )
        File(bundle, "visual.mnn").writeText("visual component")
        File(bundle, "llm.mnn.json").writeText(
            """{"extraInfo":{"version":"3.5.0"}}"""
        )

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.BLOCKED, readiness.status)
        assertFalse(readiness.canLoad)
        assertEquals(
            MnnBundleDiagnosticCode.LEGACY_VISUAL_GRAPH_RUNTIME_INCOMPATIBLE,
            readiness.diagnostics.single().code
        )
        assertTrue(readiness.diagnosticSummary().contains("MNN 3.6"))
    }

    @Test
    fun legacyMnn35GraphRemainsEligibleWhenVisualComponentsAreRemovedForTextOnlyIsolation() {
        val bundle = completeChatBundle()
        File(bundle, "config.json").writeText("{}")
        File(bundle, "llm.mnn.json").writeText(
            """{"extraInfo":{"version":"3.5.0"}}"""
        )

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.READY, readiness.status)
        assertTrue(readiness.canLoad)
    }

    @Test
    fun legacyMnn35VisualGraphWithConfiguredLlmModelNameIsBlocked() {
        val bundle = completeChatBundle()
        File(bundle, "config.json").writeText(
            """{"is_visual":true,"visual_model":"visual.mnn","llm_model":"models/chat.mnn"}"""
        )
        File(bundle, "visual.mnn").writeText("visual component")
        File(bundle, "models/chat.mnn").apply {
            parentFile?.mkdirs()
            writeText("chat component")
        }
        File(bundle, "models/chat.mnn.json").writeText(
            """{"extraInfo":{"version":"3.5.0"}}"""
        )

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.BLOCKED, readiness.status)
        assertEquals(
            MnnBundleDiagnosticCode.LEGACY_VISUAL_GRAPH_RUNTIME_INCOMPATIBLE,
            readiness.diagnostics.single().code
        )
        assertEquals("models/chat.mnn.json", readiness.diagnostics.single().path)
    }

    @Test
    fun legacyMnn35VersionRecordedOnlyBesideVisualGraphIsBlocked() {
        val bundle = completeChatBundle()
        File(bundle, "config.json").writeText(
            """{"is_visual":true,"visual_model":"vision/encoder.mnn"}"""
        )
        File(bundle, "vision/encoder.mnn").apply {
            parentFile?.mkdirs()
            writeText("visual component")
        }
        File(bundle, "vision/encoder.mnn.json").writeText(
            """{"extraInfo":{"version":"3.5.0"}}"""
        )

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.BLOCKED, readiness.status)
        assertEquals(
            MnnBundleDiagnosticCode.LEGACY_VISUAL_GRAPH_RUNTIME_INCOMPATIBLE,
            readiness.diagnostics.single().code
        )
        assertEquals("vision/encoder.mnn.json", readiness.diagnostics.single().path)
    }

    @Test
    fun configDrivenNamesReplaceDefaultContractAndIncludeNestedModalitySidecars() {
        val bundle = Files.createTempDirectory("mnn-config-driven-bundle").toFile()
        File(bundle, "config.json").writeText(
            """
            {
              "llm_config":"meta/llm.json",
              "llm_model":"models/chat.mnn",
              "llm_weight":"models/chat.mnn.weight",
              "embedding_file":"models/embeddings.bin",
              "tokenizer_file":"assets/tokenizer.mtok",
              "is_visual":true,
              "visual_model":"vision/encoder.mnn",
              "visual_weight":"vision/encoder.mnn.weight",
              "mllm": {
                "ple_model":"processor/ple.mnn",
                "ple_weight":"processor/ple.mnn.weight"
              }
            }
            """.trimIndent()
        )
        listOf(
            "meta/llm.json",
            "models/chat.mnn",
            "models/chat.mnn.weight",
            "models/embeddings.bin",
            "assets/tokenizer.mtok",
            "vision/encoder.mnn",
            "vision/encoder.mnn.weight",
            "processor/ple.mnn",
            "processor/ple.mnn.weight"
        ).forEach { path ->
            File(bundle, path).apply {
                parentFile?.mkdirs()
                writeText("component")
            }
        }

        val ready = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertEquals(MnnBundleReadinessStatus.READY, ready.status)
        assertTrue("meta/llm.json" in ready.requiredComponentPaths)
        assertTrue("vision/encoder.mnn.weight" in ready.requiredComponentPaths)
        assertTrue("processor/ple.mnn" in ready.requiredComponentPaths)
        assertFalse("llm.mnn" in ready.requiredComponentPaths)

        File(bundle, "processor/ple.mnn.weight").delete()
        val blocked = MnnBundleReadinessAnalyzer.analyze(bundle)
        assertFalse(blocked.canLoad)
        assertEquals(listOf("processor/ple.mnn.weight"), blocked.missingRequiredComponents)
    }

    @Test
    fun llmConfigDeclaredPleEmbeddingSidecarIsRequired() {
        val bundle = completeChatBundle()
        File(bundle, "config.json").writeText("{}")
        File(bundle, "llm_config.json").writeText(
            """{"model_type":"gemma4","ple_embed_file":"ple_embeddings_int4.bin"}"""
        )

        val blocked = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertFalse(blocked.canLoad)
        assertEquals(listOf("ple_embeddings_int4.bin"), blocked.missingRequiredComponents)

        File(bundle, "ple_embeddings_int4.bin").writeText("ple sidecar")
        val ready = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertTrue(ready.canLoad)
        assertTrue("ple_embeddings_int4.bin" in ready.requiredComponentPaths)
    }

    @Test
    fun unsafePleEmbeddingPathInLlmConfigBlocksRegistration() {
        val bundle = completeChatBundle()
        File(bundle, "config.json").writeText("{}")
        File(bundle, "llm_config.json").writeText(
            """{"model_type":"gemma4","ple_embed_file":"../outside.bin"}"""
        )

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertFalse(readiness.canLoad)
        assertEquals(listOf("llm_config.json: ple_embed_file"), readiness.invalidRequiredComponents)
        assertEquals(
            MnnBundleDiagnosticCode.CONFIG_DECLARED_COMPONENT_PATH_INVALID,
            readiness.diagnostics.single().code
        )
        assertEquals("llm_config.json", readiness.diagnostics.single().path)
    }

    @Test
    fun manifestDeclaredExtensionUsesSafeRelativePathAndIsRequired() {
        val bundle = completeChatBundle()

        val blocked = MnnBundleReadinessAnalyzer.analyze(
            bundle,
            additionalRequiredComponents = listOf("vision/visual.mnn.weight")
        )

        assertFalse(blocked.canLoad)
        assertEquals(listOf("vision/visual.mnn.weight"), blocked.missingRequiredComponents)

        val sidecar = File(bundle, "vision/visual.mnn.weight")
        sidecar.parentFile?.mkdirs()
        sidecar.writeText("component")
        assertTrue(
            MnnBundleReadinessAnalyzer.analyze(
                bundle,
                additionalRequiredComponents = listOf("vision/visual.mnn.weight")
            ).canLoad
        )
    }

    @Test
    fun unsafeConfigDeclaredVisualPathBlocksInsteadOfEscapingBundleRoot() {
        val bundle = completeChatBundle()
        File(bundle, "config.json").writeText(
            """{"is_visual":true,"visual_model":"../outside.mnn"}"""
        )

        val readiness = MnnBundleReadinessAnalyzer.analyze(bundle)

        assertFalse(readiness.canLoad)
        assertEquals(listOf("config.json: visual_model"), readiness.invalidRequiredComponents)
        assertEquals(
            MnnBundleDiagnosticCode.CONFIG_DECLARED_COMPONENT_PATH_INVALID,
            readiness.diagnostics.single().code
        )
    }

    @Test
    fun visionAndImageScopesDoNotApplyTheChatRootFileContract() {
        val missingBundle = File(Files.createTempDirectory("mnn-non-chat").toFile(), "missing")

        listOf(MnnBundleLoadScope.VISION_ENGINE, MnnBundleLoadScope.IMAGE_ENGINE).forEach { scope ->
            val readiness = MnnBundleReadinessAnalyzer.analyze(missingBundle, scope)

            assertEquals(MnnBundleReadinessStatus.NOT_APPLICABLE, readiness.status)
            assertTrue(readiness.canLoad)
            assertFalse(readiness.applies)
            assertTrue(readiness.missingRequiredComponents.isEmpty())
            assertEquals(
                MnnBundleDiagnosticCode.CHAT_LLM_CONTRACT_NOT_APPLICABLE,
                readiness.diagnostics.single().code
            )
        }
    }

    private fun completeChatBundle(
        tokenizerName: String = "tokenizer.txt",
        excludedComponents: Set<String> = emptySet()
    ): File {
        val bundle = Files.createTempDirectory("mnn-chat-bundle").toFile()
        val components = listOf(
            "config.json",
            "llm_config.json",
            "llm.mnn",
            "llm.mnn.json",
            "llm.mnn.weight",
            "embeddings_bf16.bin",
            tokenizerName
        )
        components
            .filterNot { component -> component in excludedComponents }
            .forEach { component -> File(bundle, component).writeText("component") }
        return bundle
    }
}

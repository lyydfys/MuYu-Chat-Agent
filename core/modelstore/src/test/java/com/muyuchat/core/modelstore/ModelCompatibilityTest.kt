package com.muyuchat.core.modelstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class ModelCompatibilityTest {
    @Test
    fun readyMnnVisualComponentAcceptsImagesOnEveryCompatibleDevice() {
        val unvalidatedMnn = ModelManifest(
            id = "mnn-vision",
            displayName = "MNN visual",
            path = "/models/mnn",
            runtime = ChatModelRuntime.MNN,
            source = ModelSource.LOCAL,
            fileName = "config.json",
            sizeBytes = 1L,
            sha256 = "sha",
            visionValidated = false
        )
        val validatedMnn = unvalidatedMnn.copy(visionValidated = true)
        val gguf = unvalidatedMnn.copy(runtime = ChatModelRuntime.LLAMA_CPP)

        assertTrue(unvalidatedMnn.acceptsImageInput(nativeVisionReady = true))
        assertTrue(validatedMnn.acceptsImageInput(nativeVisionReady = true))
        assertTrue(gguf.acceptsImageInput(nativeVisionReady = true))
        assertFalse(validatedMnn.acceptsImageInput(nativeVisionReady = false))
    }

    @Test
    fun qwenMainModelPassesPreflight() {
        val file = ggufFile("Qwen3.5-4B-Q4_K_M.gguf", architecture = "qwen3", fileType = 15)
        val metadata = GgufMetadataReader.read(file)
        val result = ModelCompatibility.check(file, metadata)

        assertTrue(result.canLoad)
        assertEquals("qwen3", metadata.architecture)
        assertEquals("Q4_K_M", metadata.quant)
    }

    @Test
    fun atomicImportStagingFilePreservesGgufIdentityForPreflight() {
        val finalFile = ggufFile("Qwen3.5-4B-Q4_K_M.gguf", architecture = "qwen3", fileType = 15)
        val staging = atomicImportStagingFile(finalFile, transactionId = "test-transaction")
        staging.parentFile?.mkdirs()
        finalFile.copyTo(staging)

        assertEquals(finalFile.name, staging.name)
        assertTrue(staging.name.endsWith(".gguf", ignoreCase = true))
        assertTrue(ModelCompatibility.check(staging).canLoad)
    }

    @Test
    fun filenameHintsDoNotPreemptNativeGgufAdmission() {
        val directory = Files.createTempDirectory("gguf-import-guards-").toFile()
        val mmproj = File(directory, "mmproj-Qwen3.5.gguf")
        val mtp = File(directory, "mtp-Qwen3.5.gguf")
        val split = File(directory, "Qwen3.5-00001-of-00002.gguf")

        listOf(mmproj, mtp, split).forEach { finalFile ->
            val staging = atomicImportStagingFile(finalFile, transactionId = finalFile.nameWithoutExtension)
            staging.parentFile?.mkdirs()
            staging.writeBytes(fakeGguf(architecture = "qwen3", fileType = 15))
            assertEquals(finalFile.name, staging.name)
            assertTrue(ModelCompatibility.check(staging).canLoad)
        }
    }

    @Test
    fun veryLowBitGgufWarnsAboutAnswerQuality() {
        val file = ggufFile("Gemma-4-26B-IQ2_XXS.gguf", architecture = "gemma", fileType = 19)

        val result = ModelCompatibility.check(file)

        assertTrue(result.canLoad)
        assertTrue(result.warnings.any { it.contains("极低比特") && it.contains("指令遵循") })
    }

    @Test
    fun unknownChatArchitectureDefersFinalDecisionToCurrentLlamaRuntime() {
        val file = ggufFile("DeepSeek-V3-Q4_K_M.gguf", architecture = "deepseek2", fileType = 15)

        val result = ModelCompatibility.check(file)

        assertTrue(result.canLoad)
        assertTrue(result.warnings.any { it.contains("architecture=deepseek2") })
    }

    @Test
    fun supportedCausalArchitecturesAreNotRejectedByANameBasedDenylist() {
        listOf("pangu-embedded", "talkie", "paddleocr", "deepseek2-ocr").forEach { architecture ->
            val file = ggufFile(
                "$architecture-Q4_K_M.gguf",
                architecture = architecture,
                fileType = 15,
                causalAttention = true,
                poolingType = 0
            )

            assertTrue("architecture=$architecture", ModelCompatibility.check(file).canLoad)
        }
    }

    @Test
    fun poolingMetadataBlocksEmbeddingAndRerankerVariantsUsingChatArchitectureNames() {
        listOf(
            Triple("qwen2", 1, "embedding"),
            Triple("qwen3", 3, "embedding-last"),
            Triple("qwen3", 4, "reranker")
        ).forEach { (architecture, poolingType, label) ->
            val file = ggufFile(
                "$architecture-$label-Q4_K_M.gguf",
                architecture = architecture,
                fileType = 15,
                causalAttention = true,
                poolingType = poolingType
            )

            val metadata = GgufMetadataReader.read(file)
            val result = ModelCompatibility.check(file, metadata)

            assertEquals(poolingType, metadata.poolingType)
            assertFalse(result.canLoad)
            assertTrue(result.message.contains("pooling_type=$poolingType"))
        }
    }

    @Test
    fun nonCausalMetadataBlocksAnOtherwiseKnownChatArchitecture() {
        val file = ggufFile(
            "Qwen3-NonCausal-Q4_K_M.gguf",
            architecture = "qwen3",
            fileType = 15,
            causalAttention = false
        )

        val metadata = GgufMetadataReader.read(file)
        val result = ModelCompatibility.check(file, metadata)

        assertEquals(false, metadata.causalAttention)
        assertFalse(result.canLoad)
        assertTrue(result.message.contains("attention.causal=false"))
    }

    @Test
    fun causalChatMetadataWithNoPoolingRemainsLoadable() {
        val file = ggufFile(
            "Qwen3-Chat-Q4_K_M.gguf",
            architecture = "qwen3",
            fileType = 15,
            causalAttention = true,
            poolingType = 0
        )

        val metadata = GgufMetadataReader.read(file)

        assertEquals(true, metadata.causalAttention)
        assertEquals(0, metadata.poolingType)
        assertTrue(ModelCompatibility.check(file, metadata).canLoad)
    }

    @Test
    fun embeddingArchitectureIsNotRegisteredAsAChatModel() {
        val file = ggufFile("Nomic-Embed-Q4_K_M.gguf", architecture = "nomic-bert", fileType = 15)

        val result = ModelCompatibility.check(file)

        assertFalse(result.canLoad)
        assertTrue(result.message.contains("architecture=nomic-bert"))
    }

    @Test
    fun nonCausalDiffusionArchitectureIsNotRegisteredAsAutoregressiveChat() {
        val file = ggufFile("LLaDA-Q4_K_M.gguf", architecture = "llada", fileType = 15)

        val result = ModelCompatibility.check(file)

        assertFalse(result.canLoad)
        assertTrue(result.message.contains("architecture=llada"))
    }

    @Test
    fun projectorLikeFilenameIsEvaluatedFromMetadata() {
        val file = ggufFile("mmproj-Qwen3.5-4B-BF16.gguf", architecture = "clip", fileType = 1)
        val result = ModelCompatibility.check(file)

        assertFalse(result.canLoad)
        assertTrue(result.message.contains("architecture=clip"))
    }

    @Test
    fun splitPartIsAllowedToReachNativeLoader() {
        val file = ggufFile("Qwen3.6-27B-BF16-00001-of-00002.gguf", architecture = "qwen3", fileType = 32)

        val result = ModelCompatibility.check(file)

        assertTrue(result.canLoad)
        assertTrue(result.warnings.any { it.contains("sharded GGUF") })
    }

    @Test
    fun mnnBundleRequiresLlmConfigAndNonEmptyCoreFiles() {
        val dir = Files.createTempDirectory("mnn-bundle").toFile()
        listOf(
            "config.json",
            "llm_config.json",
            "llm.mnn",
            "llm.mnn.weight",
            "embeddings_bf16.bin",
            "tokenizer.txt"
        )
            .forEach { name -> File(dir, name).writeText("x") }

        assertTrue(isCompleteMnnBundleDirectory(dir))

        File(dir, "llm_config.json").delete()
        assertFalse(isCompleteMnnBundleDirectory(dir))

        File(dir, "llm_config.json").writeText("x")
        File(dir, "llm.mnn.weight").writeBytes(ByteArray(0))
        assertFalse(isCompleteMnnBundleDirectory(dir))

        File(dir, "llm.mnn.weight").writeText("x")
        File(dir, "embeddings_bf16.bin").delete()
        assertFalse(isCompleteMnnBundleDirectory(dir))
    }

    @Test
    fun qairtBundleAcceptsUnpackedGenieXArtifacts() {
        val dir = Files.createTempDirectory("qairt-bundle").toFile()
        assertFalse(isCompleteQairtBundleDirectory(dir))

        File(dir, "genie_config.json").writeText("{}")
        assertFalse(isCompleteQairtBundleDirectory(dir))

        File(dir, "metadata.json").writeText("{\"model_id\":\"test\",\"genie\":{\"supports_vision\":false}}")
        File(dir, "tokenizer.json").writeText("{}")
        File(dir, "qnn_context.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(isCompleteQairtBundleDirectory(dir))

        File(dir, "metadata.json").delete()
        assertFalse(isCompleteQairtBundleDirectory(dir))
    }

    @Test
    fun qairtBundleResolvesAnUnambiguousNestedRoot() {
        val outer = Files.createTempDirectory("qairt-wrapper").toFile()
        val root = File(outer, "Qwen3-geniex").apply { mkdirs() }
        writeQairtBundle(root)

        assertEquals(root.canonicalFile, findQairtBundleRoot(outer))
        assertFalse(isCompleteQairtBundleDirectory(outer))
    }

    @Test
    fun qairtBundleDoesNotFlattenAmbiguousRoots() {
        val outer = Files.createTempDirectory("qairt-ambiguous").toFile()
        writeQairtBundle(File(outer, "part-a").apply { mkdirs() })
        writeQairtBundle(File(outer, "part-b").apply { mkdirs() })

        assertNull(findQairtBundleRoot(outer))
        assertFalse(isCompleteQairtBundleDirectory(outer))
    }

    @Test
    fun qairtManifestMigratesFromLegacyOuterDirectory() {
        val outer = Files.createTempDirectory("qairt-manifest").toFile()
        val root = File(outer, "bundle").apply { mkdirs() }
        writeQairtBundle(root)
        val legacy = ModelManifest(
            id = "legacy-qairt",
            displayName = "Legacy QAIRT",
            path = outer.absolutePath,
            runtime = ChatModelRuntime.GENIEX_QAIRT,
            source = ModelSource.LOCAL,
            fileName = outer.name,
            sizeBytes = 0L,
            sha256 = ""
        )

        val migrated = normalizeQairtManifestRoot(ModelManifest.fromJson(legacy.toJson()))

        assertEquals(root.canonicalPath, migrated.path)
        assertEquals(root.name, migrated.fileName)
    }

    @Test
    fun installedQairtRecoveryFindsCompleteOrphanAndSkipsRegisteredRepo() {
        val sdkRoot = Files.createTempDirectory("installed-qairt").toFile()
        val owner = File(sdkRoot, "qualcomm").apply { mkdirs() }
        val vl = File(owner, "Qwen3-VL-4B-Instruct").apply { mkdirs() }
        val text = File(owner, "Qwen3-4B-Instruct-2507").apply { mkdirs() }
        val incomplete = File(owner, "incomplete").apply { mkdirs() }
        writeQairtBundle(vl)
        writeQairtBundle(text)
        File(incomplete, "metadata.json").writeText("{}")
        val registered = ModelManifest(
            id = "registered-text",
            displayName = "Qwen3 4B QAIRT",
            path = Files.createTempDirectory("managed-qairt").toFile().absolutePath,
            runtime = ChatModelRuntime.GENIEX_QAIRT,
            source = ModelSource.HUGGING_FACE,
            repoId = "qualcomm/Qwen3-4B-Instruct-2507",
            fileName = "Qwen3-4B-Instruct-2507",
            sizeBytes = 1L,
            sha256 = "sha"
        )

        val recovered = findRecoverableInstalledQairtBundles(sdkRoot, listOf(registered))

        assertEquals(listOf("qualcomm/Qwen3-VL-4B-Instruct"), recovered.map { it.repoId })
        assertEquals(vl.canonicalPath, recovered.single().bundleDir.canonicalPath)
    }

    @Test
    fun installedQairtRecoverySkipsBundleAlreadyRegisteredByCanonicalPath() {
        val sdkRoot = Files.createTempDirectory("installed-qairt-path").toFile()
        val bundle = File(File(sdkRoot, "qualcomm").apply { mkdirs() }, "Qwen3-VL-4B-Instruct")
            .apply { mkdirs() }
        writeQairtBundle(bundle)
        val registered = ModelManifest(
            id = "registered-vl",
            displayName = "Qwen3-VL-4B-Instruct",
            path = bundle.absolutePath,
            runtime = ChatModelRuntime.GENIEX_QAIRT,
            source = ModelSource.LOCAL,
            fileName = bundle.name,
            sizeBytes = 1L,
            sha256 = "sha"
        )

        assertTrue(findRecoverableInstalledQairtBundles(sdkRoot, listOf(registered)).isEmpty())
    }

    private fun writeQairtBundle(dir: File) {
        File(dir, "genie_config.json").writeText("{}")
        File(dir, "metadata.json").writeText("{\"model_id\":\"test\",\"genie\":{\"supports_vision\":false}}")
        File(dir, "tokenizer.json").writeText("{}")
        File(dir, "qnn_context.bin").writeBytes(byteArrayOf(1))
    }

    private fun ggufFile(
        name: String,
        architecture: String,
        fileType: Int,
        causalAttention: Boolean? = null,
        poolingType: Int? = null
    ): File {
        val directory = Files.createTempDirectory("gguf-fixture-").toFile().apply { deleteOnExit() }
        return File(directory, name).apply {
            writeBytes(fakeGguf(architecture, fileType, causalAttention, poolingType))
            deleteOnExit()
        }
    }

    private fun fakeGguf(
        architecture: String,
        fileType: Int,
        causalAttention: Boolean? = null,
        poolingType: Int? = null
    ): ByteArray =
        ByteArrayOutputStream().apply {
            write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            writeU32(3)
            writeU64(0)
            writeU64(2L + (if (causalAttention != null) 1L else 0L) + (if (poolingType != null) 1L else 0L))
            writeString("general.architecture")
            writeU32(8)
            writeString(architecture)
            writeString("general.file_type")
            writeU32(4)
            writeU32(fileType)
            causalAttention?.let { causal ->
                writeString("$architecture.attention.causal")
                writeU32(7)
                write(if (causal) 1 else 0)
            }
            poolingType?.let { pooling ->
                writeString("$architecture.pooling_type")
                writeU32(4)
                writeU32(pooling)
            }
        }.toByteArray()

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeU64(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        repeat(4) { shift -> write((value shr (shift * 8)) and 0xff) }
    }

    private fun ByteArrayOutputStream.writeU64(value: Long) {
        repeat(8) { shift -> write(((value shr (shift * 8)) and 0xff).toInt()) }
    }
}

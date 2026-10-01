package com.muyuchat.core.modelstore

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmFormatTest {
    @Test
    fun validContainerPassesBoundedPreflight() {
        val file = liteRtLmFile("model.litertlm")

        val result = validateLiteRtLmLoadPreflight(file, file.length())

        assertTrue(result.canLoad)
        assertTrue(result.details.contains("runtime=litert_lm"))
    }

    @Test
    fun gpuArtisanTextDecoderContainerPassesBoundedPreflight() {
        val file = Files.createTempDirectory("litertlm-artisan-format").toFile()
            .resolve("gpu-artisan.litertlm")
            .apply { writeBytes(validLiteRtLmBytes("tf_lite_artisan_text_decoder")) }

        val result = validateLiteRtLmLoadPreflight(file, file.length())

        assertTrue(result.canLoad)
        assertTrue(isLiteRtLmFile(file))
    }

    @Test
    fun visualSectionIsDetectedFromBoundedMetadata() {
        val file = Files.createTempDirectory("litertlm-vision-format").toFile()
            .resolve("vision.litertlm")
            .apply { writeBytes(validLiteRtLmBytes("tf_lite_vision_encoder")) }

        assertEquals(setOf("tf_lite_vision_encoder"), liteRtLmModelTypes(file))
        assertTrue(isLiteRtLmVisionModel(file))
    }

    @Test
    fun textDecoderWithoutVisualSectionIsNotMarkedAsVisionModel() {
        val file = liteRtLmFile("text-only.litertlm")

        assertTrue(liteRtLmModelTypes(file).contains("tf_lite_prefill_decode"))
        assertFalse(isLiteRtLmVisionModel(file))
    }

    @Test
    fun wrongMagicAndSizeAreRejected() {
        val file = liteRtLmFile("model.litertlm")
        val wrongSize = validateLiteRtLmLoadPreflight(file, file.length() + 1L)
        assertFalse(wrongSize.canLoad)
        assertTrue(wrongSize.title.contains("大小"))

        File(file.parentFile, "wrong.litertlm").apply {
            writeBytes(ByteArray(32) { 'x'.code.toByte() })
        }.also { wrongMagic ->
            val result = validateLiteRtLmLoadPreflight(wrongMagic, wrongMagic.length())
            assertFalse(result.canLoad)
            assertTrue(result.title.contains("文件头"))
        }
    }

    @Test
    fun structurallyValidNonChatContainerIsRejectedBeforeNativeLoad() {
        val file = Files.createTempDirectory("litertlm-format").toFile()
            .resolve("embedder.litertlm")
            .apply { writeBytes(validLiteRtLmBytes("tf_lite_embedder")) }

        val result = validateLiteRtLmLoadPreflight(file, file.length())

        assertFalse(result.canLoad)
        assertTrue(result.details.contains("TF_LITE_PREFILL_DECODE"))
        assertFalse(isLiteRtLmFile(file))
    }

    @Test
    fun truncatedContainerIsNotListedAsARecoverableChatModel() {
        val root = Files.createTempDirectory("litertlm-truncated-recovery").toFile()
        val truncated = File(root, "truncated.litertlm").apply {
            writeBytes(validLiteRtLmBytes())
        }
        RandomAccessFile(truncated, "rw").use { handle -> handle.setLength(handle.length() - 1L) }

        assertFalse(isLiteRtLmFile(truncated))
        assertTrue(findRecoverableManagedLiteRtLmFiles(root, emptyList()).isEmpty())
    }

    @Test
    fun manifestRoundTripPreservesLiteRtLmRuntime() {
        val manifest = ModelManifest(
            id = "litertlm-model",
            displayName = "Gemma LiteRT-LM",
            path = "/models/gemma.litertlm",
            runtime = ChatModelRuntime.LITERT_LM,
            source = ModelSource.HUGGING_FACE,
            repoId = "litert-community/Gemma3-1B-IT",
            revision = "main",
            fileName = "gemma.litertlm",
            sizeBytes = 64L,
            sha256 = "a".repeat(64)
        )

        val restored = ModelManifest.fromJson(manifest.toJson())

        assertEquals(ChatModelRuntime.LITERT_LM, restored.runtime)
        assertEquals("litert_lm", restored.runtime.storageValue)
    }

    @Test
    fun managedRecoveryFindsNestedLiteRtLmAndSkipsRepresentedPath() {
        val root = Files.createTempDirectory("litertlm-recovery").toFile()
        val nested = File(File(root, "gemma-bundle").apply { mkdirs() }, "gemma.litertlm")
            .apply {
                writeBytes(validLiteRtLmBytes())
            }
        val existing = ModelManifest(
            id = "existing",
            displayName = "existing",
            path = nested.absolutePath,
            runtime = ChatModelRuntime.LITERT_LM,
            source = ModelSource.LOCAL,
            fileName = nested.name,
            sizeBytes = nested.length(),
            sha256 = "0".repeat(64)
        )

        assertTrue(findRecoverableManagedLiteRtLmFiles(root, emptyList()).single().file == nested)
        assertTrue(findRecoverableManagedLiteRtLmFiles(root, listOf(existing)).isEmpty())
    }

    @Test
    fun sameDisplayNamePreservesCompleteCpuAndGpuFileSuffixes() = withModelRepository { repository, root ->
        val cpuBytes = validLiteRtLmBytes("tf_lite_prefill_decode")
        val gpuBytes = validLiteRtLmBytes("tf_lite_artisan_text_decoder")
        val cpuName = "gemma-4-E4B-it-cpu.litertlm"
        val gpuName = "gemma-4-E4B-it-gpu.litertlm"
        val cpu = importLiteRtLm(repository, cpuName, cpuBytes, "cpu")
        val gpu = importLiteRtLm(repository, gpuName, gpuBytes, "gpu")

        assertEquals("Gemma-4-E4B-Abliterated", cpu.displayName)
        assertEquals(cpu.displayName, gpu.displayName)
        assertEquals(cpuName, cpu.fileName)
        assertEquals(gpuName, gpu.fileName)
        assertFalse(cpu.id == gpu.id)
        assertFalse(cpu.path == gpu.path)
        assertFalse(cpu.sha256 == gpu.sha256)
        assertArrayEquals(cpuBytes, File(cpu.path).readBytes())
        assertArrayEquals(gpuBytes, File(gpu.path).readBytes())
        assertEquals(setOf(cpu.id, gpu.id), repository.listModels().map { it.id }.toSet())
        val reopened = ModelStoreRepository(TestFilesContext(root))
        assertEquals(setOf(cpuName, gpuName), reopened.listModels().map { it.fileName }.toSet())
    }

    @Test
    fun providerReportedSameFileNameDoesNotOverwriteDifferentContent() = withModelRepository { repository, _ ->
        val cpuBytes = validLiteRtLmBytes("tf_lite_prefill_decode")
        val gpuBytes = validLiteRtLmBytes("tf_lite_artisan_text_decoder")
        val cpu = importLiteRtLm(repository, "gemma-e4b.litertlm", cpuBytes, "provider-a")
        val gpu = importLiteRtLm(repository, "gemma-e4b.litertlm", gpuBytes, "provider-b")

        assertEquals("gemma-e4b.litertlm", cpu.fileName)
        assertEquals("gemma-e4b-1.litertlm", gpu.fileName)
        assertFalse(cpu.id == gpu.id)
        assertFalse(cpu.path == gpu.path)
        assertArrayEquals(cpuBytes, File(cpu.path).readBytes())
        assertArrayEquals(gpuBytes, File(gpu.path).readBytes())
        assertEquals(2, repository.listModels().size)
    }

    @Test
    fun taskProviderAliasWithRealLiteRtLmHeaderRemainsDistinct() = withModelRepository { repository, _ ->
        // This is a LiteRT-LM container with a provider alias, not a MediaPipe ZIP task.
        val bytes = validLiteRtLmBytes()
        val task = importLiteRtLm(repository, "gemma-e4b-cpu.task", bytes, "task-alias")
        val liteRtLm = importLiteRtLm(repository, "gemma-e4b-cpu.litertlm", bytes, "litertlm")

        assertEquals("gemma-e4b-cpu.task.litertlm", task.fileName)
        assertEquals("gemma-e4b-cpu.litertlm", liteRtLm.fileName)
        assertFalse(task.path == liteRtLm.path)
        assertFalse(task.id == liteRtLm.id)
        assertArrayEquals(bytes, File(task.path).readBytes())
        assertArrayEquals(bytes, File(liteRtLm.path).readBytes())
        assertEquals(2, repository.listModels().size)
    }

    @Test
    fun repeatedRegistrationReturnsPersistedCatalogOwner() = withModelRepository { repository, _ ->
        val bytes = validLiteRtLmBytes()
        val imported = importLiteRtLm(repository, "gemma-e4b-cpu.litertlm", bytes, "owner")
        val registered = repository.registerDownloadedLiteRtLmModel(
            file = File(imported.path), repoId = "test/gemma-e4b", revision = "main"
        )
        val registeredAgain = repository.registerDownloadedLiteRtLmModel(File(imported.path))

        assertEquals(imported.id, registered.id)
        assertEquals(imported.id, registeredAgain.id)
        assertEquals(imported.id, repository.listModels().single().id)
        assertEquals(imported.sha256, registeredAgain.sha256)
        assertTrue(registered.aliases.isNotEmpty())
        registeredAgain.aliases.forEach { alias ->
            assertEquals(imported.id, repository.getModel(alias)?.id)
        }
        assertArrayEquals(bytes, File(imported.path).readBytes())
    }

    @Test
    fun deletingRegistrationAliasRemovesOnlyItsOwnerAndKeepsOtherSuffix() = withModelRepository { repository, _ ->
        val cpu = importLiteRtLm(repository, "gemma-e4b-cpu.litertlm", validLiteRtLmBytes(), "delete-cpu")
        val gpuBytes = validLiteRtLmBytes("tf_lite_artisan_text_decoder")
        val gpu = importLiteRtLm(repository, "gemma-e4b-gpu.litertlm", gpuBytes, "keep-gpu")
        val registered = repository.registerDownloadedLiteRtLmModel(File(cpu.path))
        val alias = registered.aliases.single()

        assertTrue(repository.deleteModel(alias))
        assertFalse(File(cpu.path).exists())
        assertEquals(null, repository.getModel(cpu.id))
        assertEquals(null, repository.getModel(alias))
        assertEquals(gpu.id, repository.listModels().single().id)
        assertArrayEquals(gpuBytes, File(gpu.path).readBytes())
        assertFalse(repository.deleteModel(alias))
    }

    @Test
    fun resumedLegacyReceiptReturnsAndStoresPersistedOwner() = withModelRepository { repository, root ->
        val bytes = validLiteRtLmBytes()
        val imported = importLiteRtLm(repository, "gemma-e4b-cpu.litertlm", bytes, "receipt")
        val workspace = File(root, "external-files").listFiles().orEmpty().single {
            it.isDirectory && it.name.startsWith(".mca-import-resume-")
        }
        val stateFile = File(workspace, "state.json")
        val legacyId = "legacy-receipt-id"
        val state = org.json.JSONObject(stateFile.readText())
            .put("completedModel", imported.copy(id = legacyId).toJson())
        stateFile.writeText(state.toString())

        val resumed = importLiteRtLm(repository, "gemma-e4b-cpu.litertlm", bytes, "receipt")

        assertEquals(imported.id, resumed.id)
        assertEquals(imported.id, repository.listModels().single().id)
        assertEquals(imported.id, repository.getModel(legacyId)?.id)
        assertEquals(imported.id, org.json.JSONObject(stateFile.readText())
            .getJSONObject("completedModel").getString("id"))
        assertArrayEquals(bytes, File(imported.path).readBytes())
    }

    private fun importLiteRtLm(
        repository: ModelStoreRepository, fileName: String, bytes: ByteArray, taskId: String
    ): ModelManifest = repository.importPreparedSources(
        sources = listOf(ModelImportSource(
            identity = "source:$taskId", path = fileName, size = bytes.size.toLong(),
            open = { bytes.inputStream() }
        )),
        identity = "test-import:$taskId",
        kind = classifyModelImport(fileName, bytes),
        displayNameOverride = "Gemma-4-E4B-Abliterated",
        options = ModelImportOptions(resumeKey = taskId),
        onProgress = {}, checkCancelled = {}
    )

    private fun withModelRepository(block: (ModelStoreRepository, File) -> Unit) {
        val root = Files.createTempDirectory("litertlm-import-identity").toFile()
        try {
            block(ModelStoreRepository(TestFilesContext(root)), root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class TestFilesContext(private val root: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getExternalFilesDir(type: String?): File = File(root, "external-files").apply { mkdirs() }
    }

    private fun liteRtLmFile(name: String): File = Files.createTempDirectory("litertlm-format").toFile()
        .resolve(name)
        .apply { writeBytes(validLiteRtLmBytes()) }

    /** Tiny real FlatBuffers metadata graph with one chat-model payload section. */
    private fun validLiteRtLmBytes(
        modelType: String = "tf_lite_prefill_decode"
    ): ByteArray {
        val metadataBase = 32
        val valueString = 176
        val valueBytes = modelType.toByteArray(Charsets.UTF_8)
        val keyBytes = "model_type".toByteArray(Charsets.UTF_8)
        val keyString = align4(valueString + 4 + valueBytes.size + 1)
        val metadataEnd = align4(keyString + 4 + keyBytes.size + 1)
        val headerEnd = metadataBase + metadataEnd
        val payloadBegin = align16(headerEnd)
        val payloadEnd = payloadBegin + 32
        val file = ByteArray(payloadEnd)
        LITERT_LM_MAGIC.toByteArray(Charsets.US_ASCII).copyInto(file)
        writeLittleEndianUInt32(file, 8, 1)
        writeLittleEndianUInt32(file, 12, 6)
        writeLittleEndianUInt64(file, 24, headerEnd.toLong())

        // FlatBuffers metadata starts at file offset 32. Root table @16,
        // SectionMetadata @40, vector @52, SectionObject @80.
        writeLittleEndianUInt32(file, metadataBase, 16)
        writeLittleEndianUInt16(file, metadataBase + 8, 8)
        writeLittleEndianUInt16(file, metadataBase + 10, 12)
        writeLittleEndianUInt16(file, metadataBase + 12, 0)
        writeLittleEndianUInt16(file, metadataBase + 14, 4)

        // Root table at local offset 16 and its section_metadata pointer.
        writeLittleEndianUInt32(file, metadataBase + 16, 8)
        writeLittleEndianUInt32(file, metadataBase + 20, 20)

        // SectionMetadata vtable at 32 and table at 40.
        writeLittleEndianUInt16(file, metadataBase + 32, 6)
        writeLittleEndianUInt16(file, metadataBase + 34, 8)
        writeLittleEndianUInt16(file, metadataBase + 36, 4)
        writeLittleEndianUInt32(file, metadataBase + 40, 8)
        writeLittleEndianUInt32(file, metadataBase + 44, 8)
        writeLittleEndianUInt32(file, metadataBase + 52, 1)
        writeLittleEndianUInt32(file, metadataBase + 56, 24)

        // SectionObject vtable at 64 and table at 80.
        writeLittleEndianUInt16(file, metadataBase + 64, 12)
        writeLittleEndianUInt16(file, metadataBase + 66, 28)
        writeLittleEndianUInt16(file, metadataBase + 68, 4)
        writeLittleEndianUInt16(file, metadataBase + 70, 8)
        writeLittleEndianUInt16(file, metadataBase + 72, 16)
        writeLittleEndianUInt16(file, metadataBase + 74, 24)
        writeLittleEndianUInt32(file, metadataBase + 80, 16)
        writeLittleEndianUInt32(file, metadataBase + 84, 28)
        writeLittleEndianUInt64(file, metadataBase + 88, payloadBegin.toLong())
        writeLittleEndianUInt64(file, metadataBase + 96, payloadEnd.toLong())
        file[metadataBase + 104] = 3

        // Section items vector contains one KeyValuePair at local offset 136.
        writeLittleEndianUInt32(file, metadataBase + 112, 1)
        writeLittleEndianUInt32(file, metadataBase + 116, 20)

        // KeyValuePair vtable: value table, union type, and key string.
        writeLittleEndianUInt16(file, metadataBase + 120, 10)
        writeLittleEndianUInt16(file, metadataBase + 122, 16)
        writeLittleEndianUInt16(file, metadataBase + 124, 12)
        writeLittleEndianUInt16(file, metadataBase + 126, 11)
        writeLittleEndianUInt16(file, metadataBase + 128, 4)
        writeLittleEndianUInt32(file, metadataBase + 136, 16)
        writeLittleEndianUInt32(file, metadataBase + 140, 20)
        file[metadataBase + 147] = 9
        writeLittleEndianUInt32(file, metadataBase + 148, keyString - 148)

        // StringValue table points to the model-type string at local offset 176.
        writeLittleEndianUInt16(file, metadataBase + 152, 6)
        writeLittleEndianUInt16(file, metadataBase + 154, 8)
        writeLittleEndianUInt16(file, metadataBase + 156, 4)
        writeLittleEndianUInt32(file, metadataBase + 160, 8)
        writeLittleEndianUInt32(file, metadataBase + 164, valueString - 164)
        writeFlatBufferString(file, metadataBase + valueString, valueBytes)
        writeFlatBufferString(file, metadataBase + keyString, keyBytes)
        return file
    }

    private fun writeFlatBufferString(target: ByteArray, offset: Int, value: ByteArray) {
        writeLittleEndianUInt32(target, offset, value.size)
        value.copyInto(target, offset + 4)
    }

    private fun align4(value: Int): Int = (value + 3) and -4

    private fun align16(value: Int): Int = (value + 15) and -16

    private fun writeLittleEndianUInt32(target: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> target[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun writeLittleEndianUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeLittleEndianUInt64(target: ByteArray, offset: Int, value: Long) {
        repeat(8) { index -> target[offset + index] = (value ushr (index * 8)).toByte() }
    }
}

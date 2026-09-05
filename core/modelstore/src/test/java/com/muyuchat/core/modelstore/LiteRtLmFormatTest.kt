package com.muyuchat.core.modelstore

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
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

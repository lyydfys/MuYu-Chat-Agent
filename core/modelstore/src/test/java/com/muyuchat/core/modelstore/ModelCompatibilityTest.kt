package com.muyuchat.core.modelstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ModelCompatibilityTest {
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
    fun mmprojIsBlockedBeforeNativeLoad() {
        val file = ggufFile("mmproj-Qwen3.5-4B-BF16.gguf", architecture = "clip", fileType = 1)
        val result = ModelCompatibility.check(file)

        assertFalse(result.canLoad)
        assertTrue(result.message.contains("视觉投影辅助文件"))
    }

    @Test
    fun splitPartIsBlockedBeforeNativeLoad() {
        val file = ggufFile("Qwen3.6-27B-BF16-00001-of-00002.gguf", architecture = "qwen3", fileType = 32)
        val result = ModelCompatibility.check(file)

        assertFalse(result.canLoad)
        assertTrue(result.message.contains("分片"))
    }

    private fun ggufFile(name: String, architecture: String, fileType: Int): File {
        val file = File.createTempFile(name.removeSuffix(".gguf"), ".gguf")
        file.writeBytes(fakeGguf(architecture, fileType))
        file.deleteOnExit()
        return File(file.parentFile, name).also {
            file.renameTo(it)
            it.deleteOnExit()
        }
    }

    private fun fakeGguf(architecture: String, fileType: Int): ByteArray =
        ByteArrayOutputStream().apply {
            write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            writeU32(3)
            writeU64(0)
            writeU64(2)
            writeString("general.architecture")
            writeU32(8)
            writeString(architecture)
            writeString("general.file_type")
            writeU32(4)
            writeU32(fileType)
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

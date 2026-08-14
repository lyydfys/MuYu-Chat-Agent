package com.muyuchat.core.modelstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files

class GgufLoadPreflightTest {
    @Test
    fun mainModelPreflightIgnoresTensorTailButRejectsSizeAndFormatChanges() {
        val root = Files.createTempDirectory("gguf-load-preflight").toFile()
        val model = writeGguf(File(root, "Qwen3.5-4B-Q4_0.gguf"), architecture = "qwen3")
        expandSparse(model)
        val expectedSize = model.length()

        assertTrue(validateGgufLoadPreflight(model, expectedSize).canLoad)
        RandomAccessFile(model, "rw").use { file ->
            file.seek(expectedSize - 1L)
            file.write(0x5a)
        }
        assertTrue(validateGgufLoadPreflight(model, expectedSize).canLoad)
        assertFalse(validateGgufLoadPreflight(model, expectedSize + 1L).canLoad)

        val invalid = File(root, "invalid.gguf").apply { writeBytes(ByteArray(64) { 1 }) }
        assertFalse(validateGgufLoadPreflight(invalid, invalid.length()).canLoad)
    }

    @Test
    fun projectorPreflightIgnoresTensorTailButRejectsWrongSizeAndType() {
        val root = Files.createTempDirectory("projector-load-preflight").toFile()
        val projector = writeGguf(File(root, "mmproj-model-f16.gguf"), architecture = "clip")
        expandSparse(projector)
        val expectedSize = projector.length()

        assertTrue(validateGgufProjectorLoadPreflight(projector, expectedSize).canLoad)
        RandomAccessFile(projector, "rw").use { file ->
            file.seek(expectedSize - 1L)
            file.write(0x7f)
        }
        assertTrue(validateGgufProjectorLoadPreflight(projector, expectedSize).canLoad)
        assertFalse(validateGgufProjectorLoadPreflight(projector, expectedSize - 1L).canLoad)

        val textModel = writeGguf(File(root, "plain-model.gguf"), architecture = "qwen3")
        assertFalse(validateGgufProjectorLoadPreflight(textModel, textModel.length()).canLoad)
    }

    @Test
    fun metadataReaderCannotConsumePastItsLoadTimeBudget() {
        val declaredLargeKey = ByteArrayOutputStream().apply {
            write("GGUF".toByteArray(Charsets.US_ASCII))
            writeU32(3)
            writeU64(0)
            writeU64(1)
            writeU64(4_096)
            write(ByteArray(4_096) { 'x'.code.toByte() })
        }.toByteArray()
        val counting = CountingInputStream(ByteArrayInputStream(declaredLargeKey))

        val metadata = readBoundedGgufMetadata(
            input = counting,
            fileName = "Qwen3.5-4B-Q4_0.gguf",
            byteLimit = 64L
        )

        assertTrue(metadata.isGguf)
        assertTrue("read ${counting.bytesRead} bytes", counting.bytesRead <= 64L)
    }

    @Test
    fun repositoryLoadPathContainsNoFullContentHash() {
        val source = sourceFile("core/modelstore/src/main/java/com/muyuchat/core/modelstore/ModelStoreRepository.kt")
        val load = functionBody(source, "fun validateForLoad(id: String)")
        val verify = functionBody(source, "fun verify(id: String)")

        assertTrue(load.contains("validateGgufLoadPreflight(file, model.sizeBytes)"))
        assertTrue(load.contains("validateGgufProjectorLoadPreflight("))
        assertFalse(load.contains("sha256("))
        assertTrue(verify.contains("sha256(file)"))
        assertTrue(verify.contains("sha256(candidate)"))
    }

    private fun expandSparse(file: File) {
        RandomAccessFile(file, "rw").use { sparse ->
            sparse.setLength(16L * 1024L * 1024L)
        }
    }

    private fun writeGguf(file: File, architecture: String): File = file.apply {
        parentFile?.mkdirs()
        writeBytes(ByteArrayOutputStream().apply {
            write("GGUF".toByteArray(Charsets.US_ASCII))
            writeU32(3)
            writeU64(0)
            writeU64(2)
            writeString("general.architecture")
            writeU32(8)
            writeString(architecture)
            writeString("general.file_type")
            writeU32(4)
            writeU32(2)
        }.toByteArray())
    }

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

    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int = delegate.read().also { value ->
            if (value >= 0) bytesRead += 1L
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length).also { read ->
                if (read > 0) bytesRead += read.toLong()
            }

        override fun skip(byteCount: Long): Long = delegate.skip(byteCount).also { skipped ->
            if (skipped > 0L) bytesRead += skipped
        }
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function: $signature")
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate source file: $relativePath")
    }
}

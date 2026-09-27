package com.muyuchat.mca

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenImageOutputValidationTest {
    @Test
    fun `qwen output accepts strict rgb and rgba pngs`() = withRoot { root ->
        val rgb = File(root, "rgb.png")
        val rgbBytes = png(width = 3, height = 2, colorType = 2)
        rgb.writeBytes(rgbBytes)
        val verifiedRgb = verifyAndReadQwenImageOutput(
            nativeResult = evidence(rgb, width = 3, height = 2),
            expectedOutputFile = rgb,
            expectedWidth = 3,
            expectedHeight = 2
        )
        assertArrayEquals(rgbBytes, verifiedRgb.bytes)
        assertEquals(rgbBytes.size.toLong(), verifiedRgb.outputBytes)

        val rgba = File(root, "rgba.png")
        val rgbaBytes = png(width = 3, height = 2, colorType = 6)
        rgba.writeBytes(rgbaBytes)
        val verifiedRgba = verifyAndReadQwenImageOutput(
            nativeResult = evidence(rgba, width = 3, height = 2),
            expectedOutputFile = rgba,
            expectedWidth = 3,
            expectedHeight = 2
        )
        assertArrayEquals(rgbaBytes, verifiedRgba.bytes)
        assertEquals(rgbaBytes.size.toLong(), verifiedRgba.outputBytes)
    }

    @Test
    fun `qwen output keeps qnn rgb contract separate and rejects unsupported color type`() =
        withRoot { root ->
            val rgba = File(root, "rgba.png")
            rgba.writeBytes(png(width = 2, height = 2, colorType = 6))
            assertRejected {
                verifyAndReadQnnImageOutput(
                    nativeResult = evidence(rgba, width = 2, height = 2),
                    expectedOutputFile = rgba,
                    expectedWidth = 2,
                    expectedHeight = 2
                )
            }

            val indexed = File(root, "indexed.png")
            indexed.writeBytes(png(width = 2, height = 2, colorType = 3))
            assertRejected {
                verifyAndReadQwenImageOutput(
                    nativeResult = evidence(indexed, width = 2, height = 2),
                    expectedOutputFile = indexed,
                    expectedWidth = 2,
                    expectedHeight = 2
                )
            }
        }

    @Test
    fun `qwen output rejects truncated and crc-corrupt pngs`() = withRoot { root ->
        val valid = png(width = 2, height = 2, colorType = 6)
        val output = File(root, "output.png")

        output.writeBytes(valid.copyOf(valid.size - 1))
        assertRejected {
            verifyAndReadQwenImageOutput(
                nativeResult = evidence(output, width = 2, height = 2),
                expectedOutputFile = output,
                expectedWidth = 2,
                expectedHeight = 2
            )
        }

        val corrupt = valid.copyOf().apply {
            // The first IDAT data byte is after the PNG signature and IHDR chunk.
            this[41] = (this[41].toInt() xor 0x01).toByte()
        }
        output.writeBytes(corrupt)
        assertRejected {
            verifyAndReadQwenImageOutput(
                nativeResult = evidence(output, width = 2, height = 2),
                expectedOutputFile = output,
                expectedWidth = 2,
                expectedHeight = 2
            )
        }
    }

    @Test
    fun `qwen output rejects dimensions and decoded scanline mismatches`() = withRoot { root ->
        val output = File(root, "output.png")
        output.writeBytes(png(width = 2, height = 2, colorType = 6))
        assertRejected {
            verifyAndReadQwenImageOutput(
                nativeResult = evidence(output, width = 2, height = 2),
                expectedOutputFile = output,
                expectedWidth = 3,
                expectedHeight = 2
            )
        }

        val ihdr = ihdr(width = 2, height = 2, colorType = 6)
        val shortRows = ByteArray((1 + 2 * 4) * 1)
        val malformed = pngBytes(
            listOf(
                ihdr,
                pngChunk("IDAT", compressed(shortRows)),
                pngChunk("IEND")
            )
        )
        output.writeBytes(malformed)
        assertRejected {
            verifyAndReadQwenImageOutput(
                nativeResult = evidence(output, width = 2, height = 2),
                expectedOutputFile = output,
                expectedWidth = 2,
                expectedHeight = 2
            )
        }
    }

    private fun evidence(output: File, width: Int, height: Int): JSONObject =
        JSONObject()
            .put("outputPath", output.absolutePath)
            .put("outputBytes", output.length())
            .put("outputSha256", output.sha256())
            .put("mimeType", "image/png")
            .put("width", width)
            .put("height", height)

    private fun png(width: Int, height: Int, colorType: Int): ByteArray = pngBytes(
        listOf(
            ihdr(width, height, colorType),
            pngChunk("IDAT", compressed(ByteArray((1 + width * if (colorType == 6) 4 else 3) * height))),
            pngChunk("IEND")
        )
    )

    private fun ihdr(width: Int, height: Int, colorType: Int): ByteArray =
        pngChunk(
            "IHDR",
            ByteArray(13).apply {
                putU32(0, width.toLong())
                putU32(4, height.toLong())
                this[8] = 8
                this[9] = colorType.toByte()
            }
        )

    private fun compressed(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { result ->
        DeflaterOutputStream(result).use { it.write(bytes) }
        result.toByteArray()
    }

    private fun pngBytes(chunks: List<ByteArray>): ByteArray = ByteArrayOutputStream().use { out ->
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        chunks.forEach(out::write)
        out.toByteArray()
    }

    private fun pngChunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
        require(type.length == 4)
        val chunk = ByteArray(data.size + 12)
        chunk.putU32(0, data.size.toLong())
        type.toByteArray(Charsets.US_ASCII).copyInto(chunk, 4)
        data.copyInto(chunk, 8)
        val crc = CRC32().apply { update(chunk, 4, data.size + 4) }
        chunk.putU32(chunk.size - 4, crc.value)
        return chunk
    }

    private fun ByteArray.putU32(offset: Int, value: Long) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256").run {
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) update(buffer, 0, count)
            }
        }
        digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun assertRejected(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private inline fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("qwen-output-validation").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

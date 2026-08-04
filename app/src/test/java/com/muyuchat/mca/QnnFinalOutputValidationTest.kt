package com.muyuchat.mca

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnFinalOutputValidationTest {
    @Test
    fun `final output binds exact request path byte proof and png dimensions`() = withRoot { root ->
        val output = File(root, "request.png")
        val bytes = rgbPng(width = 512, height = 512)
        output.writeBytes(bytes)

        val verified = verifyAndReadQnnImageOutput(
            nativeResult = outputEvidence(output, width = 512, height = 512),
            expectedOutputFile = output,
            expectedWidth = 512,
            expectedHeight = 512
        )

        assertArrayEquals(bytes, verified.bytes)
        assertEquals("image/png", verified.mimeType)
        assertEquals(bytes.size.toLong(), verified.outputBytes)
    }

    @Test
    fun `final output rejects checksum evidence that differs from exact copied bytes`() =
        withRoot { root ->
            val output = File(root, "request.png").apply {
                writeBytes(rgbPng(width = 8, height = 8))
            }

            assertRejected {
                verifyAndReadQnnImageOutput(
                    nativeResult = outputEvidence(output, width = 8, height = 8)
                        .put("outputSha256", "0".repeat(64)),
                    expectedOutputFile = output,
                    expectedWidth = 8,
                    expectedHeight = 8
                )
            }
            assertRejected {
                verifyAndReadQnnImageOutput(
                    nativeResult = outputEvidence(output, width = 8, height = 8)
                        .put("outputSha256", output.sha256().uppercase()),
                    expectedOutputFile = output,
                    expectedWidth = 8,
                    expectedHeight = 8
                )
            }
        }

    @Test
    fun `final output rejects a sibling path even when it contains a plausible png`() = withRoot { root ->
        val expected = File(root, "request.png").apply {
            writeBytes(rgbPng(width = 512, height = 512))
        }
        val sibling = File(root, "other-request.png").apply {
            writeBytes(rgbPng(width = 512, height = 512))
        }

        assertRejected {
            verifyAndReadQnnImageOutput(
                nativeResult = outputEvidence(sibling, width = 512, height = 512),
                expectedOutputFile = expected,
                expectedWidth = 512,
                expectedHeight = 512
            )
        }
    }

    @Test
    fun `final output rejects mismatched byte proof header and dimensions before publication`() =
        withRoot { root ->
            val output = File(root, "request.png").apply {
                writeBytes(rgbPng(width = 512, height = 512))
            }

            assertRejected {
                verifyAndReadQnnImageOutput(
                    nativeResult = outputEvidence(output, width = 512, height = 512)
                        .put("outputBytes", output.length() + 1L),
                    expectedOutputFile = output,
                    expectedWidth = 512,
                    expectedHeight = 512
                )
            }

            output.writeBytes(rgbPng(width = 256, height = 512))
            assertRejected {
                verifyAndReadQnnImageOutput(
                    nativeResult = outputEvidence(output, width = 512, height = 512),
                    expectedOutputFile = output,
                    expectedWidth = 512,
                    expectedHeight = 512
                )
            }

            output.writeBytes(ByteArray(29))
            assertRejected {
                verifyAndReadQnnImageOutput(
                    nativeResult = outputEvidence(output, width = 512, height = 512),
                    expectedOutputFile = output,
                    expectedWidth = 512,
                    expectedHeight = 512
                )
            }
        }

    @Test
    fun `final output rejects header-only crc corruption missing iend and trailing bytes`() =
        withRoot { root ->
            val output = File(root, "request.png")
            val valid = rgbPng(width = 512, height = 512)

            rejectOutput(output, valid.copyOfRange(0, 33), width = 512, height = 512)

            val corruptIdat = valid.copyOf().apply {
                this[41] = (this[41].toInt() xor 0x01).toByte()
            }
            rejectOutput(output, corruptIdat, width = 512, height = 512)
            rejectOutput(output, valid.copyOf(valid.size - 12), width = 512, height = 512)
            rejectOutput(output, valid + byteArrayOf(0), width = 512, height = 512)
        }

    @Test
    fun `final output rejects crc-valid undecodable or malformed rgb scanlines`() =
        withRoot { root ->
            val output = File(root, "request.png")
            val ihdr = ihdrChunk(width = 2, height = 2)
            val iend = pngChunk("IEND")

            rejectOutput(
                output,
                pngBytes(listOf(ihdr, pngChunk("IDAT", byteArrayOf(0x78, 0x9c.toByte())), iend)),
                width = 2,
                height = 2
            )
            rejectOutput(
                output,
                pngBytes(
                    listOf(
                        ihdr,
                        pngChunk("IDAT", compressedRgbScanlines(width = 1, height = 2)),
                        iend
                    )
                ),
                width = 2,
                height = 2
            )
            val completeStream = compressedRgbScanlines(width = 2, height = 2)
            rejectOutput(
                output,
                pngBytes(
                    listOf(
                        ihdr,
                        pngChunk("IDAT", completeStream + byteArrayOf(0)),
                        iend
                    )
                ),
                width = 2,
                height = 2
            )
            rejectOutput(
                output,
                pngBytes(
                    listOf(
                        ihdr,
                        pngChunk("IDAT", completeStream),
                        pngChunk("IDAT", completeStream),
                        iend
                    )
                ),
                width = 2,
                height = 2
            )
            val invalidFilterScanlines = ByteArray((1 + 2 * 3) * 2).apply { this[0] = 5 }
            rejectOutput(
                output,
                pngBytes(
                    listOf(
                        ihdr,
                        pngChunk("IDAT", compressedBytes(invalidFilterScanlines)),
                        iend
                    )
                ),
                width = 2,
                height = 2
            )
        }

    @Test
    fun `final output rejects rendering-affecting ancillary chunks but permits inert text`() =
        withRoot { root ->
            val output = File(root, "request.png")
            val ihdr = ihdrChunk(width = 2, height = 2)
            val idat = pngChunk("IDAT", compressedRgbScanlines(width = 2, height = 2))
            val iend = pngChunk("IEND")

            listOf("tRNS", "gAMA", "cHRM", "iCCP", "sRGB", "sBIT", "eXIf").forEach { type ->
                rejectOutput(
                    output,
                    pngBytes(listOf(ihdr, pngChunk(type, byteArrayOf(1)), idat, iend)),
                    width = 2,
                    height = 2
                )
            }

            val accepted = pngBytes(
                listOf(
                    ihdr,
                    pngChunk("tEXt", "audit=test".toByteArray(Charsets.ISO_8859_1)),
                    idat,
                    iend
                )
            )
            output.writeBytes(accepted)
            val verified = verifyAndReadQnnImageOutput(
                nativeResult = outputEvidence(output, width = 2, height = 2),
                expectedOutputFile = output,
                expectedWidth = 2,
                expectedHeight = 2
            )
            assertArrayEquals(accepted, verified.bytes)
        }

    @Test
    fun `final output accepts consecutive idat chunks forming one complete zlib stream`() =
        withRoot { root ->
            val output = File(root, "request.png")
            val compressed = compressedRgbScanlines(width = 8, height = 8)
            val split = compressed.size / 2
            val bytes = pngBytes(
                listOf(
                    ihdrChunk(width = 8, height = 8),
                    pngChunk("IDAT", compressed.copyOfRange(0, split)),
                    pngChunk("IDAT", compressed.copyOfRange(split, compressed.size)),
                    pngChunk("IEND")
                )
            )
            output.writeBytes(bytes)

            val verified = verifyAndReadQnnImageOutput(
                nativeResult = outputEvidence(output, width = 8, height = 8),
                expectedOutputFile = output,
                expectedWidth = 8,
                expectedHeight = 8
            )

            assertArrayEquals(bytes, verified.bytes)
        }

    @Test
    fun `final output rejects duplicate ihdr missing or split idat and unknown critical chunks`() =
        withRoot { root ->
            val output = File(root, "request.png")
            val ihdr = ihdrChunk(width = 512, height = 512)
            val compressed = compressedRgbScanlines(width = 512, height = 512)
            val idat = pngChunk("IDAT", compressed)
            val iend = pngChunk("IEND")

            rejectOutput(
                output,
                pngBytes(listOf(ihdr, ihdr, idat, iend)),
                width = 512,
                height = 512
            )
            rejectOutput(
                output,
                pngBytes(listOf(ihdr, pngChunk("tEXt", byteArrayOf(1)), iend)),
                width = 512,
                height = 512
            )
            val split = compressed.size / 2
            rejectOutput(
                output,
                pngBytes(
                    listOf(
                        ihdr,
                        pngChunk("IDAT", compressed.copyOfRange(0, split)),
                        pngChunk("tEXt", byteArrayOf(1)),
                        pngChunk("IDAT", compressed.copyOfRange(split, compressed.size)),
                        iend
                    )
                ),
                width = 512,
                height = 512
            )
            rejectOutput(
                output,
                pngBytes(listOf(ihdr, pngChunk("ABCD", byteArrayOf(1)), idat, iend)),
                width = 512,
                height = 512
            )
        }

    @Test
    fun `final output rejects oversized chunk lengths before offset narrowing`() = withRoot { root ->
        val output = File(root, "request.png")
        val malformed = ByteArray(57).apply {
            pngSignature().copyInto(this)
            ihdrChunk(width = 512, height = 512).copyInto(this, destinationOffset = 8)
            putU32(offset = 33, value = 0xffff_ffffL)
            "IDAT".toByteArray(Charsets.US_ASCII).copyInto(this, destinationOffset = 37)
        }

        rejectOutput(output, malformed, width = 512, height = 512)
    }

    @Test
    fun `final output rejects a physical file above the 64 MiB hard limit before allocation`() =
        withRoot { root ->
            val output = File(root, "request.png")
            RandomAccessFile(output, "rw").use { file ->
                file.setLength(64L * 1_024L * 1_024L + 1L)
            }

            assertRejected {
                verifyAndReadQnnImageOutput(
                    nativeResult = outputEvidence(output, width = 512, height = 512),
                    expectedOutputFile = output,
                    expectedWidth = 512,
                    expectedHeight = 512
                )
            }
        }

    @Test
    fun `final output copy polls cancellation at bounded byte intervals`() = withRoot { root ->
        val output = File(root, "request.png")
        val bytes = pngBytes(
            listOf(
                ihdrChunk(width = 512, height = 512),
                pngChunk("tEXt", ByteArray(128 * 1_024) { index -> index.toByte() }),
                pngChunk("IDAT", compressedRgbScanlines(width = 512, height = 512)),
                pngChunk("IEND")
            )
        )
        output.writeBytes(bytes)
        var cancellationChecks = 0

        val failure = runCatching {
            verifyAndReadQnnImageOutput(
                nativeResult = outputEvidence(output, width = 512, height = 512),
                expectedOutputFile = output,
                expectedWidth = 512,
                expectedHeight = 512,
                checkCancelled = {
                    cancellationChecks += 1
                    if (cancellationChecks == 3) throw CancellationException("test cancellation")
                }
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is CancellationException)
        assertEquals(3, cancellationChecks)
    }

    @Test
    fun `final output idat inflation polls cancellation before downstream publication`() =
        withRoot { root ->
            val output = File(root, "request.png")
            output.writeBytes(rgbPng(width = 512, height = 512))
            var cancellationChecks = 0

            val failure = runCatching {
                verifyAndReadQnnImageOutput(
                    nativeResult = outputEvidence(output, width = 512, height = 512),
                    expectedOutputFile = output,
                    expectedWidth = 512,
                    expectedHeight = 512,
                    checkCancelled = {
                        cancellationChecks += 1
                        if (cancellationChecks == 16) {
                            throw CancellationException("test IDAT cancellation")
                        }
                    }
                )
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure is CancellationException)
            assertEquals(16, cancellationChecks)
        }

    @Test
    fun `shared text topology resolves its declared custom context instead of exact legacy name`() =
        withRoot { root ->
            val declared = File(root, "graphs/custom_clip_context.bin").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val artifact = ImageGraphArtifactContract(relativePath = "graphs/custom_clip_context.bin")

            assertEquals(
                "graphs/custom_clip_context.bin",
                qnnDeclaredTextEncoderContextPath(root, artifact)
            )
            assertEquals(null, qnnNativeTextEncoderContextPath(root))
            assertEquals(declared.canonicalFile, File(root, requireNotNull(
                qnnDeclaredTextEncoderContextPath(root, artifact)
            )).canonicalFile)

            val provider = projectSource("app/src/main/java/com/muyuchat/mca/LocalImageProvider.kt")
            val strategy = provider.indexOf("ImageWorkerStrategy.SHARED_TEXT_UNET_VAE ->")
            val declaredResolver = provider.indexOf(
                "qnnDeclaredTextEncoderContextPath(bundleRoot, profile.graph.textEncoder)",
                startIndex = strategy.coerceAtLeast(0)
            )
            assertTrue(strategy >= 0 && declaredResolver in strategy until strategy + 300)
        }

    @Test
    fun `qnn final result rechecks cancellation around completed progress before transfer`() {
        val provider = projectSource("app/src/main/java/com/muyuchat/mca/LocalImageProvider.kt")
        val result = provider.indexOf("val result = LocalImageResult(")
        val preProgressGate = provider.indexOf("coroutineContext.ensureActive()", result)
        val preProgressFlag = provider.indexOf("if (cancellationRequested.get())", preProgressGate)
        val completedProgress = provider.indexOf(
            "QNN NPU image generation completed",
            preProgressFlag
        )
        val postProgressGate = provider.indexOf(
            "coroutineContext.ensureActive()",
            completedProgress
        )
        val postProgressFlag = provider.indexOf("if (cancellationRequested.get())", postProgressGate)
        val transfer = provider.indexOf("splitOutputTransferred = true", postProgressFlag)

        assertTrue(
            result >= 0 && preProgressGate > result && preProgressFlag > preProgressGate &&
                completedProgress > preProgressFlag && postProgressGate > completedProgress &&
                postProgressFlag > postProgressGate && transfer > postProgressFlag
        )
    }

    @Test
    fun `declared shared text context rejects traversal outside bundle`() = withRoot { root ->
        File(requireNotNull(root.parentFile), "escaped-text-encoder.bin").apply {
            writeBytes(byteArrayOf(1))
            try {
                assertRejected {
                    qnnDeclaredTextEncoderContextPath(
                        root,
                        ImageGraphArtifactContract(relativePath = "../$name")
                    )
                }
            } finally {
                delete()
            }
        }
    }

    private fun outputEvidence(output: File, width: Int, height: Int): JSONObject =
        JSONObject()
            .put("outputPath", output.absolutePath)
            .put("outputBytes", output.length())
            .put("outputSha256", output.sha256())
            .put("mimeType", "image/png")
            .put("width", width)
            .put("height", height)

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun rejectOutput(output: File, bytes: ByteArray, width: Int, height: Int) {
        output.writeBytes(bytes)
        assertRejected {
            verifyAndReadQnnImageOutput(
                nativeResult = outputEvidence(output, width, height),
                expectedOutputFile = output,
                expectedWidth = width,
                expectedHeight = height
            )
        }
    }

    private fun rgbPng(width: Int, height: Int): ByteArray = pngBytes(
        listOf(
            ihdrChunk(width, height),
            pngChunk("IDAT", compressedRgbScanlines(width, height)),
            pngChunk("IEND")
        )
    )

    private fun ihdrChunk(width: Int, height: Int): ByteArray =
        pngChunk(
            "IHDR",
            ByteArray(13).apply {
                putU32(offset = 0, value = width.toLong())
                putU32(offset = 4, value = height.toLong())
                this[8] = 8
                this[9] = 2
            }
        )

    private fun compressedRgbScanlines(width: Int, height: Int): ByteArray {
        val row = ByteArray(Math.addExact(Math.multiplyExact(width, 3), 1))
        return compressedBytes(ByteArray(Math.multiplyExact(row.size, height)))
    }

    private fun compressedBytes(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { compressed ->
        DeflaterOutputStream(compressed).use { output -> output.write(bytes) }
        compressed.toByteArray()
    }

    private fun pngBytes(chunks: List<ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.write(pngSignature())
            chunks.forEach { chunk -> output.write(chunk) }
            output.toByteArray()
        }

    private fun pngChunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
        require(type.length == 4)
        val chunk = ByteArray(Math.addExact(data.size, 12)).apply {
            putU32(offset = 0, value = data.size.toLong())
            type.toByteArray(Charsets.US_ASCII).copyInto(this, destinationOffset = 4)
            data.copyInto(this, destinationOffset = 8)
        }
        val crc = CRC32().apply { update(chunk, 4, Math.addExact(4, data.size)) }
        chunk.putU32(offset = chunk.size - 4, value = crc.value)
        return chunk
    }

    private fun pngSignature(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    private fun ByteArray.putU32(offset: Int, value: Long) {
        require(value in 0L..0xffff_ffffL)
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private fun assertRejected(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private inline fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("qnn-final-output").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun projectSource(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate project source: $relativePath")
    }
}

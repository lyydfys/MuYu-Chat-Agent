package com.muyuchat.core.engine

import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVisionInputPreparerTest {
    @Test
    fun inlineDataUrlIsWrittenAsNativeReadableImageFile() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val imageBytes = pngHeader(width = 320, height = 240)
        val dataUrl = "data:image/png;base64,${Base64.getEncoder().encodeToString(imageBytes)}"
        val diagnostics = mutableListOf<Pair<String, JSONObject>>()
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "Describe this image",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = "api-image.png",
                            mimeType = "image/png",
                            dataBase64 = dataUrl
                        )
                    )
                )
            )
        )

        val prepared = LocalVisionInputPreparer.prepare(
            request = request,
            cacheDir = cacheDir,
            nowMillis = { 1234L },
            idSuffix = { "fixed" },
            diagnosticSink = { stage, details -> diagnostics += stage to details }
        )
        val attachment = prepared.messages.single().imageAttachments.single()
        val outputFile = File(attachment.uriString)

        assertTrue(outputFile.exists())
        assertTrue(outputFile.absolutePath.endsWith("engine_vision_inputs${File.separator}vision-1234-fixed.png"))
        assertArrayEquals(imageBytes, outputFile.readBytes())
        assertEquals("", attachment.dataBase64)
        assertEquals(imageBytes.size.toLong(), attachment.sizeBytes)

        val (stage, details) = diagnostics.single()
        assertEquals("local_vision_input_prepared", stage)
        assertEquals("prepared", details.getString("status"))
        assertEquals("inline", details.getString("sourceType"))
        assertEquals("image/png", details.getString("declaredFormat"))
        assertEquals("png", details.getString("detectedFormat"))
        assertEquals(320, details.getInt("originalWidth"))
        assertEquals(240, details.getInt("originalHeight"))
        assertTrue(details.getBoolean("dimensionsDetected"))
        assertEquals(imageBytes.size.toLong(), details.getLong("inputBytes"))
        assertEquals(imageBytes.size.toLong(), details.getLong("nativeReadableBytes"))
        assertEquals("vision-1234-fixed.png", details.getString("nativeReadablePath"))
        assertEquals("file_name", details.getString("nativeReadablePathKind"))
        assertEquals("passthrough", details.getString("preprocessing"))
        assertEquals("dd78ca23d9b2ad113b190e3fdc1c3fcfed3b9114943cdcb5cdbdef39c3c191a7", details.getString("inputSha256"))
        assertEquals("complete", details.getString("inspectionStatus"))
        assertFalse(details.toString().contains(dataUrl))
    }

    @Test
    fun existingLocalImagePathIsPreserved() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val localImage = File(cacheDir, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "Describe this image",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = "photo.jpg",
                            uriString = localImage.absolutePath,
                            mimeType = "image/jpeg"
                        )
                    )
                )
            )
        )

        val diagnostics = mutableListOf<Pair<String, JSONObject>>()
        val prepared = LocalVisionInputPreparer.prepare(
            request,
            cacheDir,
            diagnosticSink = { stage, details -> diagnostics += stage to details }
        )
        val attachment = prepared.messages.single().imageAttachments.single()

        assertEquals(localImage.absolutePath, attachment.uriString)
        assertEquals(3L, attachment.sizeBytes)
        assertFalse(File(cacheDir, "engine_vision_inputs").exists())
        val details = diagnostics.single().second
        assertEquals("file", details.getString("sourceType"))
        assertEquals("unknown", details.getString("detectedFormat"))
        assertEquals("photo.jpg", details.getString("nativeReadablePath"))
        assertEquals(3L, details.getLong("inputBytes"))
        assertFalse(details.toString().contains(localImage.absolutePath))
    }

    @Test
    fun fileUrlIsNormalizedToNativeReadableAbsolutePath() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val localImage = File(cacheDir, "photo.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "Describe this image",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = "photo.png",
                            uriString = localImage.toURI().toString(),
                            mimeType = "image/png"
                        )
                    )
                )
            )
        )

        val prepared = LocalVisionInputPreparer.prepare(request, cacheDir)
        val attachment = prepared.messages.single().imageAttachments.single()

        assertEquals(localImage.absolutePath, attachment.uriString)
        assertEquals(3L, attachment.sizeBytes)
    }

    @Test
    fun remoteImageUrlIsDownloadedAsNativeReadableImageFile() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val imageBytes = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "Describe this image",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = "remote.webp",
                            uriString = "https://example.test/image.webp",
                            mimeType = "image/jpeg"
                        )
                    )
                )
            )
        )
        val diagnostics = mutableListOf<Pair<String, JSONObject>>()

        val prepared = LocalVisionInputPreparer.prepare(
            request = request,
            cacheDir = cacheDir,
            nowMillis = { 5678L },
            idSuffix = { "remote" },
            remoteImageFetcher = { url ->
                assertEquals("https://example.test/image.webp", url)
                LocalVisionInputPreparer.RemoteImage(imageBytes, "image/webp")
            },
            diagnosticSink = { stage, details -> diagnostics += stage to details }
        )
        val attachment = prepared.messages.single().imageAttachments.single()
        val outputFile = File(attachment.uriString)

        assertTrue(outputFile.exists())
        assertTrue(outputFile.absolutePath.endsWith("engine_vision_inputs${File.separator}vision-5678-remote.webp"))
        assertArrayEquals(imageBytes, outputFile.readBytes())
        assertEquals("image/webp", attachment.mimeType)
        assertEquals("", attachment.dataBase64)
        assertEquals(imageBytes.size.toLong(), attachment.sizeBytes)
        val details = diagnostics.single().second
        assertEquals("http", details.getString("sourceType"))
        assertEquals("image/webp", details.getString("declaredFormat"))
        assertEquals("image/jpeg", details.getString("requestedFormat"))
        assertEquals("unknown", details.getString("detectedFormat"))
        assertEquals("vision-5678-remote.webp", details.getString("nativeReadablePath"))
        assertFalse(details.toString().contains("https://example.test"))
    }

    @Test
    fun missingLocalImagePathFailsBeforeNativeCall() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val missing = File(cacheDir, "missing.jpg")
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "Describe this image",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = "missing.jpg",
                            uriString = missing.absolutePath,
                            mimeType = "image/jpeg"
                        )
                    )
                )
            )
        )

        val diagnostics = mutableListOf<Pair<String, JSONObject>>()
        val error = assertThrows(IllegalArgumentException::class.java) {
            LocalVisionInputPreparer.prepare(
                request,
                cacheDir,
                diagnosticSink = { stage, details -> diagnostics += stage to details }
            )
        }

        assertTrue(error.message.orEmpty().contains("Image file is not readable"))
        val (stage, details) = diagnostics.single()
        assertEquals("local_vision_input_prepare_failed", stage)
        assertEquals("failed", details.getString("status"))
        assertEquals("file", details.getString("sourceType"))
        assertEquals("not_started", details.getString("preprocessing"))
        assertEquals("IllegalArgumentException", details.getString("errorType"))
        assertFalse(details.toString().contains(missing.absolutePath))
        assertEquals("unavailable", details.getString("inputSha256"))
        assertEquals("unknown", details.getString("detectedFormat"))
        assertEquals("unavailable", details.getString("nativeReadablePath"))
        assertEquals("not_available", details.getString("inspectionStatus"))
    }

    @Test
    fun requestWithoutImagesIsUnchangedAndEmitsNoDiagnostics() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val request = ChatRequest(messages = listOf(ChatMessage(Role.USER, "Hello")))
        val diagnostics = mutableListOf<Pair<String, JSONObject>>()

        val prepared = LocalVisionInputPreparer.prepare(
            request,
            cacheDir,
            diagnosticSink = { stage, details -> diagnostics += stage to details }
        )

        assertEquals(request, prepared)
        assertTrue(diagnostics.isEmpty())
        assertFalse(File(cacheDir, "engine_vision_inputs").exists())
    }

    @Test
    fun diagnosticSinkFailureDoesNotChangePreparedRequestSemantics() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val localImage = File(cacheDir, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "Describe this image",
                    imageAttachments = listOf(ChatImageAttachment(uriString = localImage.absolutePath))
                )
            )
        )

        val prepared = LocalVisionInputPreparer.prepare(
            request,
            cacheDir,
            diagnosticSink = { _, _ -> error("diagnostic collector failed") }
        )

        assertEquals(localImage.absolutePath, prepared.messages.single().imageAttachments.single().uriString)
    }

    private fun pngHeader(width: Int, height: Int): ByteArray = ByteArray(24).apply {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        )
        signature.copyInto(this)
        writeIntBigEndian(16, width)
        writeIntBigEndian(20, height)
    }

    private fun ByteArray.writeIntBigEndian(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}

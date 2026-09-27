package com.muyuchat.core.engine

import java.io.File
import java.io.RandomAccessFile
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
        val localImage = File(cacheDir, "photo.png").apply { writeBytes(pngHeader(320, 240)) }
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "Describe this image",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = "photo.png",
                            uriString = localImage.absolutePath,
                            mimeType = "image/png"
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
        assertEquals(localImage.length(), attachment.sizeBytes)
        assertFalse(File(cacheDir, "engine_vision_inputs").exists())
        val details = diagnostics.single().second
        assertEquals("file", details.getString("sourceType"))
        assertEquals("png", details.getString("detectedFormat"))
        assertEquals("photo.png", details.getString("nativeReadablePath"))
        assertEquals(localImage.length(), details.getLong("inputBytes"))
        assertFalse(details.toString().contains(localImage.absolutePath))
    }

    @Test
    fun jpegHeaderDiagnosticsPreserveNonSquareWidthAndHeight() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val localImage = File(cacheDir, "landscape.jpg").apply {
            writeBytes(byteArrayOf(
                0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(),
                0x00, 0x11, 0x08, 0x00, 0x3c, 0x00, 0x64, 0x03,
                0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
                0xff.toByte(), 0xd9.toByte()
            ))
        }
        val diagnostics = mutableListOf<JSONObject>()

        LocalVisionInputPreparer.prepare(
            localImageRequest(localImage),
            cacheDir,
            diagnosticSink = { _, details -> diagnostics += details }
        )

        assertEquals(100, diagnostics.single().getInt("originalWidth"))
        assertEquals(60, diagnostics.single().getInt("originalHeight"))
    }

    @Test
    fun fileUrlIsNormalizedToNativeReadableAbsolutePath() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val localImage = File(cacheDir, "photo.png").apply { writeBytes(pngHeader(320, 240)) }
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
        assertEquals(localImage.length(), attachment.sizeBytes)
    }

    @Test
    fun remoteImageUrlIsDownloadedAsNativeReadableImageFile() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val imageBytes = pngHeader(320, 240)
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "Describe this image",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = "remote.png",
                            uriString = "https://example.test/image.png",
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
                assertEquals("https://example.test/image.png", url)
                LocalVisionInputPreparer.RemoteImage(imageBytes, "image/png")
            },
            diagnosticSink = { stage, details -> diagnostics += stage to details }
        )
        val attachment = prepared.messages.single().imageAttachments.single()
        val outputFile = File(attachment.uriString)

        assertTrue(outputFile.exists())
        assertTrue(outputFile.absolutePath.endsWith("engine_vision_inputs${File.separator}vision-5678-remote.png"))
        assertArrayEquals(imageBytes, outputFile.readBytes())
        assertEquals("image/png", attachment.mimeType)
        assertEquals("", attachment.dataBase64)
        assertEquals(imageBytes.size.toLong(), attachment.sizeBytes)
        val details = diagnostics.single().second
        assertEquals("http", details.getString("sourceType"))
        assertEquals("image/png", details.getString("declaredFormat"))
        assertEquals("image/jpeg", details.getString("requestedFormat"))
        assertEquals("png", details.getString("detectedFormat"))
        assertEquals("vision-5678-remote.png", details.getString("nativeReadablePath"))
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
    fun existingImageAboveTwentyMiBIsRejectedBeforeNativeDecode() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val oversizedImage = File(cacheDir, "oversized.jpg")
        RandomAccessFile(oversizedImage, "rw").use { file ->
            file.setLength(MAX_LOCAL_VISION_IMAGE_BYTES + 1L)
        }
        val request = localImageRequest(oversizedImage)
        val diagnostics = mutableListOf<Pair<String, JSONObject>>()

        val error = assertThrows(LocalVisionInputException::class.java) {
            LocalVisionInputPreparer.prepare(
                request,
                cacheDir,
                diagnosticSink = { stage, details -> diagnostics += stage to details }
            )
        }

        assertEquals(LocalVisionInputFailureCode.IMAGE_TOO_LARGE, error.failureCode)
        val (stage, details) = diagnostics.single()
        assertEquals("local_vision_input_prepare_failed", stage)
        assertEquals(LocalVisionInputFailureCode.IMAGE_TOO_LARGE.wireCode, details.getString("failureCode"))
        assertEquals("LocalVisionInputException", details.getString("errorType"))
    }

    @Test
    fun imageWithUnsafeDecodedGeometryIsRejectedUsingHeaderOnlyProbe() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val oversizedImage = File(cacheDir, "oversized.png").apply {
            writeBytes(pngHeader(width = 6000, height = 6000))
        }
        val request = localImageRequest(oversizedImage)

        val error = assertThrows(LocalVisionInputException::class.java) {
            LocalVisionInputPreparer.prepare(request, cacheDir)
        }

        assertEquals(LocalVisionInputFailureCode.IMAGE_RESOLUTION_TOO_LARGE, error.failureCode)
        assertTrue(error.message.orEmpty().contains("6000×6000"))
    }

    @Test
    fun maximumSupportedVisionGeometryIsAllowed() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val image = File(cacheDir, "supported.png").apply {
            writeBytes(pngHeader(width = 4000, height = 4000))
        }

        val prepared = LocalVisionInputPreparer.prepare(localImageRequest(image), cacheDir)

        assertEquals(image.absolutePath, prepared.messages.single().imageAttachments.single().uriString)
    }

    @Test
    fun declaredMimeTypeMustMatchDetectedImageFormat() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val png = File(cacheDir, "declared-jpeg.png").apply {
            writeBytes(pngHeader(width = 320, height = 240))
        }
        val request = localImageRequest(png).copy(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "Describe this image",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = png.name,
                            uriString = png.absolutePath,
                            mimeType = "image/jpeg"
                        )
                    )
                )
            )
        )

        val error = assertThrows(LocalVisionInputException::class.java) {
            LocalVisionInputPreparer.prepare(request, cacheDir)
        }

        assertEquals(LocalVisionInputFailureCode.IMAGE_FORMAT_MISMATCH, error.failureCode)
        assertTrue(error.message.orEmpty().contains("does not match"))
    }

    @Test
    fun unknownImageSignatureIsRejectedBeforeNativeDecode() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val image = File(cacheDir, "unknown.png").apply { writeBytes(ByteArray(64) { 0x42 }) }

        val error = assertThrows(LocalVisionInputException::class.java) {
            LocalVisionInputPreparer.prepare(localImageRequest(image), cacheDir)
        }

        assertEquals(LocalVisionInputFailureCode.IMAGE_FORMAT_UNSUPPORTED, error.failureCode)
    }

    @Test
    fun rejectedInlineImageLeavesNoTemporaryFile() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val request = ChatRequest(messages = listOf(ChatMessage(
            role = Role.USER,
            content = "Describe this image",
            imageAttachments = listOf(ChatImageAttachment(
                name = "broken.png",
                mimeType = "image/png",
                dataBase64 = Base64.getEncoder().encodeToString(ByteArray(64) { 0x42 })
            ))
        )))

        val error = assertThrows(LocalVisionInputException::class.java) {
            LocalVisionInputPreparer.prepare(request, cacheDir, nowMillis = { 1234L }, idSuffix = { "broken" })
        }

        assertEquals(LocalVisionInputFailureCode.IMAGE_FORMAT_UNSUPPORTED, error.failureCode)
        assertFalse(File(cacheDir, "engine_vision_inputs/vision-1234-broken.png").exists())
    }

    @Test
    fun preparedImageNeverOverwritesAnExistingCacheFile() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val inputDir = File(cacheDir, "engine_vision_inputs").apply { mkdirs() }
        val existing = File(inputDir, "vision-1234-fixed.png").apply { writeBytes(pngHeader(321, 240)) }
        val existingBytes = existing.readBytes()
        val request = ChatRequest(messages = listOf(ChatMessage(
            role = Role.USER,
            content = "Describe",
            imageAttachments = listOf(ChatImageAttachment(
                mimeType = "image/png",
                dataBase64 = Base64.getEncoder().encodeToString(pngHeader(320, 240))
            ))
        )))

        assertThrows(IllegalStateException::class.java) {
            LocalVisionInputPreparer.prepare(
                request, cacheDir, nowMillis = { 1234L }, idSuffix = { "fixed" }
            )
        }
        assertArrayEquals(existingBytes, existing.readBytes())
    }

    @Test
    fun aggregatePixelBudgetRejectsRequestAndCleansPreparedFiles() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val attachments = (0..2).map { index ->
            ChatImageAttachment(
                name = "image-$index.png",
                mimeType = "image/png",
                dataBase64 = Base64.getEncoder().encodeToString(pngHeader(4000 + index, 4000))
            )
        }
        val request = ChatRequest(messages = listOf(ChatMessage(
            role = Role.USER,
            content = "Compare these images",
            imageAttachments = attachments
        )))
        var nextId = 0

        val error = assertThrows(LocalVisionInputException::class.java) {
            LocalVisionInputPreparer.prepare(
                request,
                cacheDir,
                nowMillis = { 1234L },
                idSuffix = { "${nextId++}" }
            )
        }

        assertEquals(LocalVisionInputFailureCode.IMAGE_RESOLUTION_TOO_LARGE, error.failureCode)
        assertTrue(File(cacheDir, "engine_vision_inputs").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun imageCountLimitIsCheckedBeforeMaterializingInlineData() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val attachments = (0..8).map { index ->
            ChatImageAttachment(
                name = "image-$index.png",
                mimeType = "image/png",
                dataBase64 = Base64.getEncoder().encodeToString(pngHeader(320 + index, 240))
            )
        }
        val request = ChatRequest(messages = listOf(ChatMessage(
            role = Role.USER,
            content = "Compare",
            imageAttachments = attachments
        )))

        val error = assertThrows(LocalVisionInputException::class.java) {
            LocalVisionInputPreparer.prepare(request, cacheDir)
        }

        assertEquals(LocalVisionInputFailureCode.TOO_MANY_IMAGES, error.failureCode)
        assertFalse(File(cacheDir, "engine_vision_inputs").exists())
    }

    @Test
    fun releaseDeletesOnlyFilesCreatedForPreparedRequest() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val existing = File(cacheDir, "existing.png").apply { writeBytes(pngHeader(320, 240)) }
        val original = ChatRequest(messages = listOf(ChatMessage(
            role = Role.USER,
            content = "Compare",
            imageAttachments = listOf(
                ChatImageAttachment(uriString = existing.absolutePath, mimeType = "image/png"),
                ChatImageAttachment(
                    mimeType = "image/png",
                    dataBase64 = Base64.getEncoder().encodeToString(pngHeader(321, 240))
                )
            )
        )))
        val prepared = LocalVisionInputPreparer.prepare(original, cacheDir)
        val generated = File(prepared.messages.single().imageAttachments.last().uriString)
        assertTrue(generated.isFile)

        LocalVisionInputPreparer.releasePreparedInputs(original, prepared, cacheDir)

        assertFalse(generated.exists())
        assertTrue(existing.isFile)
    }

    @Test
    fun releasePreservesOriginalFileUrlInsidePreparationDirectory() {
        val cacheDir = Files.createTempDirectory("mca-vision-test").toFile()
        val inputDir = File(cacheDir, "engine_vision_inputs").apply { mkdirs() }
        val originalFile = File(inputDir, "vision-existing.png").apply { writeBytes(pngHeader(320, 240)) }
        val original = ChatRequest(messages = listOf(ChatMessage(
            role = Role.USER,
            content = "Describe",
            imageAttachments = listOf(ChatImageAttachment(
                uriString = originalFile.toURI().toString(),
                mimeType = "image/png"
            ))
        )))

        val prepared = LocalVisionInputPreparer.prepare(original, cacheDir)
        LocalVisionInputPreparer.releasePreparedInputs(original, prepared, cacheDir)

        assertTrue(originalFile.isFile)
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
        val localImage = File(cacheDir, "photo.png").apply { writeBytes(pngHeader(320, 240)) }
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "Describe this image",
                    imageAttachments = listOf(ChatImageAttachment(uriString = localImage.absolutePath, mimeType = "image/png"))
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

    private fun localImageRequest(file: File): ChatRequest = ChatRequest(
        messages = listOf(
            ChatMessage(
                role = Role.USER,
                content = "Describe this image",
                imageAttachments = listOf(
                    ChatImageAttachment(
                        name = file.name,
                        uriString = file.absolutePath,
                        mimeType = "image/${file.extension}"
                    )
                )
            )
        )
    )

    private fun ByteArray.writeIntBigEndian(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}

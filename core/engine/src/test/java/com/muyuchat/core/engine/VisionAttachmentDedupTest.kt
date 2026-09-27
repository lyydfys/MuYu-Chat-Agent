package com.muyuchat.core.engine

import com.geniex.sdk.bean.ModelType
import com.geniex.sdk.bean.VlmContent
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionAttachmentDedupTest {
    @Test
    fun chatJsonEmitsOnePartForDuplicateInlinePayloads() {
        val payload = "AAECAwQ="
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "describe",
                    imageAttachments = listOf(
                        ChatImageAttachment(name = "one.png", mimeType = "image/png", dataBase64 = payload),
                        ChatImageAttachment(name = "renamed.png", mimeType = "image/png", dataBase64 = payload)
                    )
                )
            )
        )

        val messages = JSONArray(request.messagesJson(multimodal = true))
        val content = (0 until messages.length())
            .asSequence()
            .map { messages.getJSONObject(it) }
            .first { it.opt("content") is JSONArray }
            .getJSONArray("content")

        assertEquals(2, content.length()) // one text part + one image part
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
    }

    @Test
    fun chatJsonEmitsOnePartForEquivalentFileAndInlinePayloads() {
        val source = Files.createTempFile("mca-chat-json-file-inline", ".png").toFile()
        val bytes = byteArrayOf(6, 7, 8, 9)
        source.writeBytes(bytes)
        val payload = Base64.getEncoder().encodeToString(bytes)
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "describe",
                    imageAttachments = listOf(
                        ChatImageAttachment(uriString = source.absolutePath),
                        ChatImageAttachment(dataBase64 = payload)
                    )
                )
            )
        )

        val messages = JSONArray(request.messagesJson(multimodal = true))
        val content = (0 until messages.length())
            .asSequence()
            .map { messages.getJSONObject(it) }
            .first { it.opt("content") is JSONArray }
            .getJSONArray("content")

        assertEquals(2, content.length()) // one text part + one image part
    }

    @Test
    fun preparerDoesNotWriteDuplicateNativeFilesForSameInlineImage() {
        val cacheDir = Files.createTempDirectory("mca-vision-dedup").toFile()
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "describe",
                    imageAttachments = listOf(
                        ChatImageAttachment(mimeType = "image/png", dataBase64 = "AAECAwQ="),
                        ChatImageAttachment(mimeType = "image/png", dataBase64 = "AAECAwQ=")
                    )
                )
            )
        )

        val prepared = LocalVisionInputPreparer.prepare(
            request = request,
            cacheDir = cacheDir,
            nowMillis = { 1L },
            idSuffix = { "dedup" }
        )

        assertEquals(1, prepared.messages.single().imageAttachments.size)
        assertEquals(1, cacheDir.resolve("engine_vision_inputs").listFiles().orEmpty().size)
    }

    @Test
    fun preparerAcceptsCaseInsensitiveDataUrlBase64Marker() {
        val cacheDir = Files.createTempDirectory("mca-vision-uppercase-data-url").toFile()
        val bytes = byteArrayOf(0, 1, 2, 3, 4)
        val payload = Base64.getEncoder().encodeToString(bytes)
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "describe",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            mimeType = "image/png",
                            dataBase64 = "DATA:image/png;BASE64,$payload"
                        )
                    )
                )
            )
        )

        val prepared = LocalVisionInputPreparer.prepare(
            request = request,
            cacheDir = cacheDir,
            nowMillis = { 3L },
            idSuffix = { "uppercase" }
        )

        val nativeFile = File(prepared.messages.single().imageAttachments.single().uriString)
        assertTrue(nativeFile.isFile)
        assertEquals(bytes.toList(), nativeFile.readBytes().toList())
    }

    @Test
    fun preparerDeduplicatesDifferentRepresentationsAfterMaterializingBytes() {
        val cacheDir = Files.createTempDirectory("mca-vision-materialized-dedup").toFile()
        val source = cacheDir.resolve("source.bin")
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 5)
        source.writeBytes(bytes)
        val inline = Base64.getEncoder().encodeToString(bytes)
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "describe",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = "file.bin",
                            mimeType = "application/octet-stream",
                            uriString = source.absolutePath
                        ),
                        ChatImageAttachment(
                            name = "inline.bin",
                            mimeType = "application/octet-stream",
                            dataBase64 = inline
                        )
                    )
                )
            )
        )

        val prepared = LocalVisionInputPreparer.prepare(
            request = request,
            cacheDir = cacheDir,
            nowMillis = { 2L },
            idSuffix = { "different" }
        )

        assertEquals(1, prepared.messages.single().imageAttachments.size)
    }

    @Test
    fun vlmParserDeduplicatesRepeatedCurrentTurnImagePaths() {
        val messages = genieXVlmMessagesFromJson(
            """
            [{"role":"user","content":[
              {"type":"text","text":"describe"},
              {"type":"image_url","image_url":{"url":"file:///tmp/a.jpg"}},
              {"type":"image_url","image_url":{"url":"file:///tmp/a.jpg"}}
            ]}]
            """.trimIndent()
        )

        assertEquals(listOf("/tmp/a.jpg"), genieXCurrentTurnImagePaths(messages).toList())
        assertEquals(2, messages.single().contents.size) // text + one image
    }

    @Test
    fun vlmParserDeduplicatesEquivalentFileAndDataUrlImages() {
        val payload = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val messages = genieXVlmMessagesFromJson(
            """
            [{"role":"user","content":[
              {"type":"image_url","image_url":{"url":"data:image/png;base64,$payload"}},
              {"type":"image_url","image_url":{"url":"data:image/png;base64,$payload"}}
            ]}]
            """.trimIndent()
        )
        assertEquals(1, messages.single().contents.size)
    }

    @Test
    fun vlmContentDeduplicatesFileUriAndAbsolutePathRepresentations() {
        val image = Files.createTempFile("mca-vlm-image", ".png").toFile()
        val contents = listOf(
            VlmContent("image", image.absolutePath),
            VlmContent("image", image.toURI().toString())
        ).deduplicateVisionContents()

        assertEquals(1, contents.size)
    }

    @Test
    fun requestAttachmentDedupCanonicalizesFileUriAndAbsolutePath() {
        val image = Files.createTempFile("mca-chat-image", ".png").toFile()
        val attachments = listOf(
            ChatImageAttachment(uriString = image.absolutePath),
            ChatImageAttachment(uriString = image.toURI().toString())
        ).deduplicateVisionAttachments()

        assertEquals(1, attachments.size)
    }

    @Test
    fun requestAttachmentDedupUsesContentHashAcrossImportedCopies() {
        val first = Files.createTempFile("mca-chat-image-a", ".png").toFile()
        val second = Files.createTempFile("mca-chat-image-b", ".png").toFile()
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        first.writeBytes(bytes)
        second.writeBytes(bytes)

        val attachments = listOf(
            ChatImageAttachment(uriString = first.absolutePath),
            ChatImageAttachment(uriString = second.absolutePath)
        ).deduplicateVisionAttachments()

        assertEquals(1, attachments.size)
        assertEquals(first.absolutePath, attachments.single().uriString)
    }

    @Test
    fun requestAttachmentDedupUsesOneByteIdentityForFileAndInlineRepresentations() {
        val image = Files.createTempFile("mca-chat-image-file-inline", ".png").toFile()
        val bytes = byteArrayOf(4, 3, 2, 1, 0)
        image.writeBytes(bytes)
        val inline = Base64.getEncoder().encodeToString(bytes)

        val attachments = listOf(
            ChatImageAttachment(uriString = image.absolutePath),
            ChatImageAttachment(dataBase64 = inline)
        ).deduplicateVisionAttachments()

        assertEquals(1, attachments.size)
        assertEquals(image.absolutePath, attachments.single().uriString)
    }

    @Test
    fun displayContentDedupCollapsesLegacyCopiedImages() {
        val first = Files.createTempFile("mca-chat-display-image-a", ".png").toFile()
        val second = Files.createTempFile("mca-chat-display-image-b", ".png").toFile()
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        first.writeBytes(bytes)
        second.writeBytes(bytes)

        val attachments = listOf(
            ChatImageAttachment(name = "first", uriString = first.absolutePath),
            ChatImageAttachment(name = "duplicate", uriString = second.absolutePath)
        ).deduplicateVisionAttachments()

        assertEquals(listOf("first"), attachments.map { it.name })
    }

    @Test
    fun inlineDataUrlDedupUsesDecodedBytesAcrossMimeWrapping() {
        val payload = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val attachments = listOf(
            ChatImageAttachment(dataBase64 = payload),
            ChatImageAttachment(dataBase64 = "data:image/png;base64,${payload.chunked(2).joinToString("\n")}")
        ).deduplicateVisionAttachments()

        assertEquals(1, attachments.size)
    }

    @Test
    fun remoteUrlDedupPreservesCaseSensitivePathAndQueryButNormalizesHostAndFragment() {
        val contents = listOf(
            VlmContent("image", "https://EXAMPLE.test/Photo.png?token=A#preview"),
            VlmContent("image", "https://example.TEST/Photo.png?token=A#other"),
            VlmContent("image", "https://example.test/photo.png?token=a")
        ).deduplicateVisionContents()

        assertEquals(2, contents.size)
        assertEquals("https://EXAMPLE.test/Photo.png?token=A#preview", contents.first().text)
    }

    @Test
    fun percentEncodedDataUrlDoesNotTreatPlusAsSpace() {
        val equivalent = listOf(
            VlmContent("image", "data:image/png,AB+C"),
            VlmContent("image", "data:image/png,%41%42%2BC")
        ).deduplicateVisionContents()
        val distinct = listOf(
            VlmContent("image", "data:image/png,AB+C"),
            VlmContent("image", "data:image/png,AB%20C")
        ).deduplicateVisionContents()

        assertEquals(1, equivalent.size)
        assertEquals(2, distinct.size)
    }

    @Test
    fun visionReadinessRequiresExplicitNativeVisionCapability() {
        val projector = Files.createTempFile("mca-mmproj", ".gguf").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        assertFalse(
            genieXVisionReady(
                runtime = LocalChatRuntime.GENIEX_LLAMA_CPP,
                loaded = true,
                modelType = ModelType.VLM,
                mmprojPath = null,
                supportsVision = null
            )
        )
        assertFalse(
            genieXVisionReady(
                runtime = LocalChatRuntime.GENIEX_LLAMA_CPP,
                loaded = true,
                modelType = ModelType.VLM,
                mmprojPath = projector.absolutePath,
                supportsVision = null
            )
        )
        assertTrue(
            genieXVisionReady(
                runtime = LocalChatRuntime.GENIEX_LLAMA_CPP,
                loaded = true,
                modelType = ModelType.VLM,
                mmprojPath = projector.absolutePath,
                supportsVision = true
            )
        )
        assertFalse(
            genieXVisionReady(
                runtime = LocalChatRuntime.GENIEX_LLAMA_CPP,
                loaded = true,
                modelType = ModelType.VLM,
                mmprojPath = projector.resolveSibling("missing-mmproj.gguf").absolutePath,
                supportsVision = null
            )
        )
        assertFalse(
            genieXVisionReady(
                runtime = LocalChatRuntime.GENIEX_LLAMA_CPP,
                loaded = true,
                modelType = ModelType.VLM,
                mmprojPath = projector.absolutePath,
                supportsVision = false
            )
        )
        assertFalse(
            genieXVisionReady(
                runtime = LocalChatRuntime.GENIEX_QAIRT,
                loaded = true,
                modelType = ModelType.VLM,
                mmprojPath = null,
                supportsVision = null
            )
        )
        assertTrue(
            genieXVisionReady(
                runtime = LocalChatRuntime.GENIEX_QAIRT,
                loaded = true,
                modelType = ModelType.VLM,
                mmprojPath = null,
                supportsVision = true
            )
        )
        assertFalse(
            genieXVisionReady(
                runtime = LocalChatRuntime.GENIEX_QAIRT,
                loaded = true,
                modelType = ModelType.VLM,
                mmprojPath = null,
                supportsVision = false
            )
        )
    }

    @Test
    fun visionFailureReasonDistinguishesTextOnlyLiteRtAndMissingProjector() {
        assertEquals(
            "text_only_runtime",
            genieXVisionFailureReason(
                runtime = LocalChatRuntime.LITERT_LM,
                loaded = true,
                modelType = ModelType.LLM,
                mmprojPath = null,
                supportsVision = false
            )
        )
        assertEquals(
            "mmproj_file_missing",
            genieXVisionFailureReason(
                runtime = LocalChatRuntime.GENIEX_LLAMA_CPP,
                loaded = true,
                modelType = ModelType.VLM,
                mmprojPath = Files.createTempDirectory("mca-missing-mmproj")
                    .resolve("missing.gguf")
                    .toAbsolutePath()
                    .toString(),
                supportsVision = true
            )
        )
        assertEquals(
            "native_capability_unavailable",
            genieXVisionFailureReason(
                runtime = LocalChatRuntime.GENIEX_QAIRT,
                loaded = true,
                modelType = ModelType.VLM,
                mmprojPath = null,
                supportsVision = null
            )
        )
    }

    @Test
    fun displayDedupCanonicalizesFileUriWithoutOpeningTheFile() {
        val missingPath = "/mca-display-dedup/missing image.png"
        val attachments = listOf(
            ChatImageAttachment(name = "first", uriString = missingPath),
            ChatImageAttachment(name = "duplicate", uriString = "file:///mca-display-dedup/missing%20image.png"),
            ChatImageAttachment(name = "different", uriString = "/mca-display-dedup/another.png")
        ).deduplicateVisionAttachmentsForDisplay()

        assertEquals(listOf("first", "different"), attachments.map { it.name })
    }

    @Test
    fun displayDedupPreservesDistinctContentUris() {
        val attachments = listOf(
            ChatImageAttachment(uriString = "content://provider/images/1"),
            ChatImageAttachment(uriString = "content://provider/images/2"),
            ChatImageAttachment(uriString = "content://provider/images/1")
        ).deduplicateVisionAttachmentsForDisplay()

        assertEquals(
            listOf("content://provider/images/1", "content://provider/images/2"),
            attachments.map { it.uriString }
        )
    }
}

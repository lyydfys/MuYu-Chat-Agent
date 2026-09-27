package com.muyuchat.feature.chat

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatGeneratedImageUiTest {
    @Test
    fun `request current job takes precedence over older retries`() {
        val older = job(id = "old", assetId = "old-image")
        val current = job(id = "current", assetId = "new-image")
        val request = request(currentJobId = "current")

        assertEquals(current, chatGeneratedImageJob(request, listOf(older, current), "session"))
        assertEquals(listOf("new-image"), chatGeneratedImageAssetIds(request, current))
    }

    @Test
    fun `missing current job does not display a previous attempt result`() {
        val oldAttempt = job(id = "old", assetId = "old-image")
        val request = request(
            currentJobId = "missing-current",
            imageAssetIds = listOf("old-image")
        )

        val currentJob = chatGeneratedImageJob(request, listOf(oldAttempt), "session")

        assertNull(currentJob)
        assertEquals(emptyList<String>(), chatGeneratedImageAssetIds(request, currentJob))
    }

    @Test
    fun `queued retry does not show the prior attempt asset`() {
        val current = job(id = "current", assetId = "new-image")
        val request = request(
            currentJobId = "current",
            imageAssetIds = listOf("old-image")
        )

        assertEquals(listOf("new-image"), chatGeneratedImageAssetIds(request, current))
    }

    @Test
    fun `completed batch resolves every asset in request order`() {
        val request = request(
            currentJobId = "current",
            status = ChatGeneratedImageStatus.DONE,
            imageAssetIds = listOf("image-3", "image-1", "image-2")
        )
        val available = listOf(
            image("image-1"),
            image("image-2"),
            image("image-3"),
            image("unrelated")
        )

        assertEquals(
            listOf("image-3", "image-1", "image-2"),
            chatGeneratedImageItems(request, job(id = "current"), available)
                .map(ImageAssetUiItem::id)
        )
    }

    @Test
    fun `running batch uses all job assets and removes duplicate or unavailable ids`() {
        val request = request(currentJobId = "current", status = ChatGeneratedImageStatus.GENERATING)
        val current = job(id = "current", imageAssetIds = listOf("a", "b", "a", "missing"))
        val available = listOf(image("a"), image("b"))

        assertEquals(
            listOf("a", "b"),
            chatGeneratedImageItems(request, current, available).map(ImageAssetUiItem::id)
        )
    }

    @Test
    fun `legacy request without job id associates newest matching chat job`() {
        val latest = job(id = "latest", assetId = "latest-image")
        val older = job(id = "older", assetId = "older-image")

        assertEquals(latest, chatGeneratedImageJob(request(), listOf(latest, older), "session"))
        assertNull(chatGeneratedImageJob(request(), listOf(latest), "another-session"))
    }

    @Test
    fun `status distinguishes approval running interruption and result`() {
        assertEquals(
            ChatGeneratedImageUiState.AWAITING_APPROVAL,
            chatGeneratedImageUiState(
                request(status = ChatGeneratedImageStatus.AWAITING_APPROVAL),
                job = null,
                imageCount = 0
            )
        )
        assertEquals(
            ChatGeneratedImageUiState.RUNNING,
            chatGeneratedImageUiState(
                request(status = ChatGeneratedImageStatus.GENERATING),
                job = job(id = "active", terminal = false),
                imageCount = 0
            )
        )
        assertEquals(
            ChatGeneratedImageUiState.INTERRUPTED,
            chatGeneratedImageUiState(
                request(status = ChatGeneratedImageStatus.GENERATING),
                job = null,
                imageCount = 0
            )
        )
        assertEquals(
            ChatGeneratedImageUiState.RESULT,
            chatGeneratedImageUiState(
                request(status = ChatGeneratedImageStatus.DONE),
                job = null,
                imageCount = 1
            )
        )
    }

    @Test
    fun `approval label shows the exact saved model and backend`() {
        assertEquals(
            "MeinaMix XL · 本地",
            chatGeneratedImageModelLabel(
                request().copy(modelName = "MeinaMix XL", backendId = "LOCAL")
            )
        )
        assertEquals(
            "Cloud Image · 云端",
            chatGeneratedImageModelLabel(
                request().copy(modelName = "Cloud Image", backendId = "CLOUD")
            )
        )
    }

    private fun request(
        currentJobId: String? = null,
        status: ChatGeneratedImageStatus = ChatGeneratedImageStatus.QUEUED,
        imageAssetIds: List<String> = emptyList()
    ) = ChatGeneratedImageRequest(
        id = "request",
        prompt = "A blue ceramic bowl",
        status = status,
        currentJobId = currentJobId,
        imageAssetIds = imageAssetIds
    )

    private fun job(
        id: String,
        assetId: String? = null,
        imageAssetIds: List<String> = assetId?.let(::listOf).orEmpty(),
        terminal: Boolean = true
    ) = ImageGenerationUiJob(
        id = id,
        prompt = "A blue ceramic bowl",
        statusLabel = if (terminal) "完成" else "生成中",
        chatSessionId = "session",
        chatMessageId = "request",
        imageAssetId = assetId,
        imageAssetIds = imageAssetIds,
        terminal = terminal
    )

    private fun image(id: String) = ImageAssetUiItem(
        id = id,
        name = "$id.png",
        uriString = "file:///tmp/$id.png",
        source = "generated",
        prompt = "A blue ceramic bowl",
        createdAtText = "now",
        sizeText = "1 KB",
        width = 512,
        height = 512
    )
}

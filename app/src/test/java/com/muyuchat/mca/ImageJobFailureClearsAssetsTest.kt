package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败/取消的图片任务不得保留资产引用。
 *
 * 失败批次的部分产物会被清理，如果 job 仍带着 `imageAssetId(s)`，详情/重试/历史就会继续指向
 * `image_assets/` 下已不存在的文件（`open failed: ENOENT`）。成功任务则必须照常携带产物引用。
 */
class ImageJobFailureClearsAssetsTest {

    @Test
    fun `failed job drops asset ids and transient preview`() {
        val failed = activeJob().withCommittedImageJobUpdate(
            status = ImageGenerationStatusRecord.FAILED,
            message = "图片生成失败",
        )
        assertEquals(ImageGenerationStatusRecord.FAILED, failed.status)
        assertNull(failed.imageAssetId)
        assertTrue(failed.imageAssetIds.isEmpty())
        assertNull(failed.previewUriString)
    }

    @Test
    fun `cancelled job also drops asset ids`() {
        val cancelled = activeJob().withCommittedImageJobUpdate(
            status = ImageGenerationStatusRecord.CANCELLED,
            message = "已取消图片生成",
        )
        assertNull(cancelled.imageAssetId)
        assertTrue(cancelled.imageAssetIds.isEmpty())
    }

    @Test
    fun `successful job keeps the produced asset ids`() {
        val done = activeJob().withCommittedImageJobUpdate(
            status = ImageGenerationStatusRecord.DONE,
            message = "已生成 2 张图片。",
            imageAssetIds = listOf("asset-a", "asset-b"),
        )
        assertEquals(listOf("asset-a", "asset-b"), done.imageAssetIds)
        assertEquals("asset-a", done.imageAssetId)
    }

    @Test
    fun `late progress callback cannot revive a failed job or its assets`() {
        val failed = activeJob().withCommittedImageJobUpdate(
            status = ImageGenerationStatusRecord.FAILED,
            message = "失败",
        )
        val late = failed.withCommittedImageJobUpdate(
            status = ImageGenerationStatusRecord.GENERATING,
            message = "生成中",
        )
        assertEquals(ImageGenerationStatusRecord.FAILED, late.status)
        assertNull(late.imageAssetId)
        assertTrue(late.imageAssetIds.isEmpty())
    }

    private fun activeJob(): ImageGenerationJobRecord = ImageGenerationJobRecord(
        id = "job",
        prompt = "一只猫",
        status = ImageGenerationStatusRecord.GENERATING,
        backend = ImageBackend.LOCAL,
        imageAssetId = "asset-a",
        imageAssetIds = listOf("asset-a", "asset-b"),
        message = "生成中",
    )
}

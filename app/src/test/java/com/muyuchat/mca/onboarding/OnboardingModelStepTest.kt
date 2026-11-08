package com.muyuchat.mca.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the first-run model step's projection.
 *
 * The page is only allowed to promise what the owner can actually do, so every test here is about a
 * boundary the user can hit on a real device: no recommendation resolved, a model this build refuses,
 * a download with and without a known total, and a failure that must explain itself.
 *
 * The single rule these pin: a percentage is never invented. It exists only when the owner has
 * reported both counters, and it is always `downloaded / total`.
 */
class OnboardingModelStepTest {

    private fun step(
        modelLabel: String? = "Qwen3 4B",
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        running: Boolean = false,
        failed: Boolean = false,
        failureReason: String? = null,
        blockReason: String? = null
    ) = onboardingModelStep(
        modelLabel = modelLabel,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        running = running,
        failed = failed,
        failureReason = failureReason,
        blockReason = blockReason
    )

    @Test
    fun `没有推荐模型时降级为不提供下载`() {
        val result = step(modelLabel = null, totalBytes = 1_000L, running = true)

        assertFalse("没有推荐模型就不能提供下载", result.available)
        assertFalse("不可下载时不能显示正在下载", result.downloading)
        assertNull("不可下载时没有进度可言", result.progressPercent)
        assertNull("这不是失败，只是没得下", result.failure)
        assertEquals(OnboardingModelStep.Unavailable, result)
    }

    @Test
    fun `构建拒绝的模型降级并说明原因与下一步`() {
        val result = step(blockReason = "该模型暂不提供下载。")

        assertFalse("被拒绝的模型不能点亮下载", result.available)
        val failure = requireNotNull(result.failure) { "被拒绝时必须给出原因" }
        assertTrue("原因要原样带上 owner 的说法：$failure", failure.contains("该模型暂不提供下载。"))
        assertTrue("必须告诉用户下一步能做什么：$failure", failure.contains("模型管理"))
    }

    @Test
    fun `下载中按 owner 的字节数算进度`() {
        val result = step(downloadedBytes = 25L, totalBytes = 200L, running = true)

        assertTrue(result.available)
        assertTrue(result.downloading)
        assertEquals("25/200 必须是 12%，不是估计值", 12, result.progressPercent)
        assertNull("下载中不该同时报错", result.failure)
    }

    @Test
    fun `没有总量的下载不编造百分比`() {
        val result = step(downloadedBytes = 4_096L, totalBytes = 0L, running = true)

        assertTrue(result.downloading)
        assertNull("总量未知时页面只能显示「正在下载…」", result.progressPercent)
    }

    @Test
    fun `进度被钳进 0 到 100`() {
        assertEquals(100, step(downloadedBytes = 300L, totalBytes = 200L, running = true).progressPercent)
        assertEquals(0, step(downloadedBytes = -5L, totalBytes = 200L, running = true).progressPercent)
    }

    @Test
    fun `失败没有具体原因时也给可执行的下一步`() {
        val result = step(running = false, failed = true, failureReason = "   ")

        assertTrue("失败后仍可重试，所以下载能力本身还在", result.available)
        assertFalse(result.downloading)
        val failure = requireNotNull(result.failure) { "失败必须显示原因" }
        assertTrue("空原因要换成可行动的说明：$failure", failure.contains("重试"))
        assertTrue("也要给出退路：$failure", failure.contains("模型管理"))
    }

    @Test
    fun `失败原因原样透传`() {
        val result = step(failed = true, failureReason = "存储空间不足：需要 1.2 GB，剩余 0.4 GB。")

        assertEquals("存储空间不足：需要 1.2 GB，剩余 0.4 GB。", result.failure)
    }

    @Test
    fun `未开始下载时没有进度也没有错误`() {
        val result = step()

        assertTrue(result.available)
        assertFalse(result.downloading)
        assertNull(result.progressPercent)
        assertNull(result.failure)
        assertEquals("Qwen3 4B", result.modelLabel)
    }

    @Test
    fun `下载结束后进度不再显示`() {
        val result = step(downloadedBytes = 200L, totalBytes = 200L, running = false)

        assertFalse("DONE 之后不该还在「正在下载」", result.downloading)
        assertNull("结束后页面不该停在 100% 的进度条上", result.progressPercent)
    }
}

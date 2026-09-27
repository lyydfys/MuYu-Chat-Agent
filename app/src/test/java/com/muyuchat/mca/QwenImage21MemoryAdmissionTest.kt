package com.muyuchat.mca

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenImage21MemoryAdmissionTest {
    @Test
    fun `unknown native memory remains an advisory allow`() {
        val result = QwenImage21MemoryAdmissionPolicy.evaluate(-1, 512, 512)
        assertTrue(result.allowed)
        assertTrue(result.message == null)
    }

    @Test
    fun `512 canvas warns but does not block before native allocation`() {
        val result = QwenImage21MemoryAdmissionPolicy.evaluate(5_651, 512, 512)
        assertTrue(result.allowed)
        assertTrue(result.message.orEmpty().contains("释放聊天模型"))
        assertTrue(result.message.orEmpty().contains("512×512"))
    }

    @Test
    fun `tiny canvas needs less memory than standard canvas`() {
        val standard = QwenImage21MemoryAdmissionPolicy.evaluate(-1, 512, 512).requiredMemoryMb
        val tiny = QwenImage21MemoryAdmissionPolicy.evaluate(-1, 320, 320).requiredMemoryMb
        assertTrue(tiny < standard)
    }

    @Test
    fun `large dimensions saturate the estimate instead of wrapping`() {
        val result = QwenImage21MemoryAdmissionPolicy.evaluate(0, Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(result.allowed)
        assertTrue(result.requiredMemoryMb > 5_000)
        assertTrue(result.message != null)
    }
}

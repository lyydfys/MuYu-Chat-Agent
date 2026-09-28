package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenImage21MemoryAdmissionTest {
    @Test
    fun `unknown native memory remains an advisory allow`() {
        val result = QwenImage21MemoryAdmissionPolicy.evaluate(-1, 512, 512)
        assertTrue(result.allowed)
        assertNull(result.message)
    }

    @Test
    fun `512 canvas warns but does not block before native allocation`() {
        val result = QwenImage21MemoryAdmissionPolicy.evaluate(5_651, 512, 512)
        assertTrue(result.allowed)
        assertTrue(result.message.orEmpty().contains("释放聊天模型"))
        assertTrue(result.message.orEmpty().contains("512×512"))
    }

    @Test
    fun `3966 MB available on a 16 GB phone keeps the requested 512 canvas`() {
        val result = QwenImage21MemoryAdmissionPolicy.evaluate(3_966, 512, 512)
        assertTrue(result.allowed)
        assertEquals(6_041, result.requiredMemoryMb)
        assertEquals(512, result.width)
        assertEquals(512, result.height)
        assertTrue(result.message.orEmpty().contains("3966 MB"))
        assertTrue(result.message.orEmpty().contains("并非手机总内存"))
        assertTrue(result.message.orEmpty().contains("按所选尺寸尝试真实生成"))
    }

    @Test
    fun `zero reported availability remains advisory`() {
        val result = QwenImage21MemoryAdmissionPolicy.evaluate(0, 512, 512)
        assertTrue(result.allowed)
        assertTrue(result.message != null)
    }

    @Test
    fun `availability at the peak estimate does not warn`() {
        val required = QwenImage21MemoryAdmissionPolicy.evaluate(-1, 512, 512).requiredMemoryMb
        val result = QwenImage21MemoryAdmissionPolicy.evaluate(required, 512, 512)
        assertTrue(result.allowed)
        assertNull(result.message)
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
        assertEquals(Int.MAX_VALUE, result.requiredMemoryMb)
        assertTrue(result.message != null)
    }
}

package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生图输入引用的准入前可用性判定。
 *
 * 目标：缺失/不可读的输入图在入队前被拦下并给出可操作原因（重新选图），而不是让任务失败后再以
 * 同一条失效引用重试，形成“必须新开聊天”的死循环。纯函数接收可读性探针，测试不需要 Context。
 */
class GenerationImageInputAvailabilityTest {

    @Test
    fun `missing unreadable inputs are reported per role and blank roles are ignored`() {
        val missing = missingGenerationImageInputRoles(
            references = listOf(
                GenerationInputReference(GenerationInputAssetRole.INPUT, "file:///images/gone.png"),
                GenerationInputReference(GenerationInputAssetRole.MASK, null),
                GenerationInputReference(GenerationInputAssetRole.CONTROL, "content://provider/control"),
                GenerationInputReference(GenerationInputAssetRole.INPUT, "   "),
            ),
            isReadable = { reference -> reference == "content://provider/control" },
        )
        assertEquals(listOf(GenerationInputAssetRole.INPUT), missing)
    }

    @Test
    fun `all readable inputs pass`() {
        val missing = missingGenerationImageInputRoles(
            references = listOf(
                GenerationInputReference(GenerationInputAssetRole.INPUT, "file:///images/input.png"),
                GenerationInputReference(GenerationInputAssetRole.CONTROL, "content://provider/control"),
            ),
            isReadable = { true },
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `message names every stale role and the next action`() {
        assertNull(generationImageInputMissingMessage(emptyList()))
        val message = generationImageInputMissingMessage(
            listOf(GenerationInputAssetRole.INPUT, GenerationInputAssetRole.MASK),
        )!!
        assertTrue(message.contains("输入图片"))
        assertTrue(message.contains("蒙版"))
        assertTrue(message.contains("重新选择"))
    }
}

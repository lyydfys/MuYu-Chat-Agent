package com.muyuchat.mca

import com.muyuchat.core.tuning.TuningSearchDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R1/S4: 调试模式枚举与调优深度映射的行为保护网（纯 JVM 执行）。 */
class AgentDebugModeSupportTest {

    @Test
    fun `debug mode keeps four labelled entries in declaration order`() {
        assertEquals(
            listOf("快速调试", "标准调试", "深度调试", "省电调试"),
            AgentDebugMode.entries.map { it.label }
        )
    }

    @Test
    fun `search depth maps every debug mode to its tuning depth`() {
        assertEquals(TuningSearchDepth.QUICK, AgentDebugMode.Quick.searchDepth())
        assertEquals(TuningSearchDepth.STANDARD, AgentDebugMode.Standard.searchDepth())
        assertEquals(TuningSearchDepth.DEEP, AgentDebugMode.Deep.searchDepth())
        assertEquals(TuningSearchDepth.POWER_SAVE, AgentDebugMode.PowerSave.searchDepth())
        assertTrue(AgentDebugMode.Standard.searchDepth() != TuningSearchDepth.QUICK)
    }
}

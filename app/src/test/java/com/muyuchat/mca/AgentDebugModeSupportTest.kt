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

    @Test
    fun `tuning eta multiplies previous load per mode and floors at fifteen seconds`() {
        assertEquals(45L, tuningEtaSecondsFromPreviousLoad(30_000L, AgentDebugMode.Quick))
        assertEquals(60L, tuningEtaSecondsFromPreviousLoad(30_000L, AgentDebugMode.Standard))
        assertEquals(75L, tuningEtaSecondsFromPreviousLoad(30_000L, AgentDebugMode.Deep))
        assertEquals(54L, tuningEtaSecondsFromPreviousLoad(30_000L, AgentDebugMode.PowerSave))
        assertEquals(15L, tuningEtaSecondsFromPreviousLoad(1_000L, AgentDebugMode.Quick))
    }

    @Test
    fun `tuning eta falls back to thirty second default when history is missing or nonpositive`() {
        assertEquals(45L, tuningEtaSecondsFromPreviousLoad(null, AgentDebugMode.Quick))
        assertEquals(45L, tuningEtaSecondsFromPreviousLoad(0L, AgentDebugMode.Quick))
        assertEquals(45L, tuningEtaSecondsFromPreviousLoad(-5L, AgentDebugMode.Quick))
    }

    @Test
    fun `local api debug mode parses aliases case dirt and dashes`() {
        assertEquals(AgentDebugMode.Quick, agentDebugModeFromLocalApiValue("quick"))
        assertEquals(AgentDebugMode.Standard, agentDebugModeFromLocalApiValue("  Standard "))
        assertEquals(AgentDebugMode.Deep, agentDebugModeFromLocalApiValue("DEEP"))
        assertEquals(AgentDebugMode.Deep, agentDebugModeFromLocalApiValue("full"))
        assertEquals(AgentDebugMode.PowerSave, agentDebugModeFromLocalApiValue("power"))
        assertEquals(AgentDebugMode.PowerSave, agentDebugModeFromLocalApiValue("power_save"))
        assertEquals(AgentDebugMode.PowerSave, agentDebugModeFromLocalApiValue("powersave"))
        assertEquals(AgentDebugMode.PowerSave, agentDebugModeFromLocalApiValue("power-save"))
        assertEquals(null, agentDebugModeFromLocalApiValue("turbo"))
        assertEquals(null, agentDebugModeFromLocalApiValue(""))
    }
}

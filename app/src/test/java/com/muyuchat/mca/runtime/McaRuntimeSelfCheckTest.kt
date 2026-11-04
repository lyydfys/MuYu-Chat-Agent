package com.muyuchat.mca.runtime

import com.muyuchat.core.dsh.contract.runtime.RuntimeSelfCheck
import com.muyuchat.core.dsh.contract.runtime.RuntimeSelfCheckProbe
import com.muyuchat.core.dsh.contract.runtime.RuntimeSelfCheckRepair
import com.muyuchat.core.dsh.contract.runtime.RuntimeSelfCheckRow
import com.muyuchat.core.dsh.contract.runtime.RuntimeSelfCheckStatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "运行状态自检" rows: five components, honest status, and repair buttons only where a real
 * action exists. The copy is also checked for leaked filesystem paths — this block is shown in the
 * console, not in diagnostics.
 */
class McaRuntimeSelfCheckTest {

    @Test
    fun `a fully healthy runtime shows five ready rows and no repair buttons`() {
        val rows = RuntimeSelfCheck.rows(healthyProbe())

        assertEquals(5, rows.size)
        assertTrue(rows.all { it.status == RuntimeSelfCheckStatus.READY })
        assertTrue(RuntimeSelfCheck.repairableRows(rows).isEmpty())
        assertEquals(
            listOf("运行时进程", "Web 端口", "模型网关", "终端 PTY", "DSH 配置目录"),
            rows.map { it.title }
        )
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
    }

    @Test
    fun `a stopped runtime offers a restart, which really exists`() {
        val processRow = row(RuntimeSelfCheck.KEY_PROCESS, healthyProbe().copy(processRunning = false))

        assertEquals(RuntimeSelfCheckStatus.FAILED, processRow.status)
        assertEquals(RuntimeSelfCheckRepair.RESTART_RUNTIME, processRow.repair)
        assertEquals("重启运行时", processRow.repair?.buttonLabel)
        assertTrue(processRow.detail.contains("未运行"))
    }

    @Test
    fun `an occupied web port offers the fallback, and only then`() {
        val occupied = row(RuntimeSelfCheck.KEY_WEB_PORT, healthyProbe().copy(webPortAvailable = false))

        assertEquals(RuntimeSelfCheckStatus.FAILED, occupied.status)
        assertEquals(RuntimeSelfCheckRepair.USE_FALLBACK_PORT, occupied.repair)
        assertEquals("使用回退端口", occupied.repair?.buttonLabel)
        assertTrue("the detail must name the port", occupied.detail.contains("14000"))

        val free = row(RuntimeSelfCheck.KEY_WEB_PORT, healthyProbe())
        assertNull("a free port has nothing to repair", free.repair)
    }

    @Test
    fun `gateway terminal and config rows never claim a repair this build cannot perform`() {
        val probe = healthyProbe().copy(
            gatewayHealthy = false,
            terminalPtyReady = false,
            configDirectoryReady = false
        )

        val rows = RuntimeSelfCheck.rows(probe).filter { it.key in setOf(
            RuntimeSelfCheck.KEY_GATEWAY,
            RuntimeSelfCheck.KEY_TERMINAL,
            RuntimeSelfCheck.KEY_CONFIG_DIR
        ) }

        assertEquals(3, rows.size)
        assertTrue(rows.all { it.status == RuntimeSelfCheckStatus.FAILED })
        assertTrue("no fake repair buttons", rows.all { it.repair == null })
        assertEquals(
            2,
            RuntimeSelfCheck.repairableRows(
                RuntimeSelfCheck.rows(probe.copy(processRunning = false, webPortAvailable = false))
            ).size
        )
    }

    @Test
    fun `unprobed components are unknown instead of failed`() {
        val rows = RuntimeSelfCheck.rows(RuntimeSelfCheckProbe())

        assertTrue(rows.all { it.status == RuntimeSelfCheckStatus.UNKNOWN })
        assertTrue(RuntimeSelfCheck.repairableRows(rows).isEmpty())
        assertTrue(rows.all { it.statusLabel == "未检测" })
    }

    @Test
    fun `a port that was never probed is unknown, not failed`() {
        val row = row(RuntimeSelfCheck.KEY_WEB_PORT, RuntimeSelfCheckProbe(webPort = 14000))

        assertEquals(RuntimeSelfCheckStatus.UNKNOWN, row.status)
        assertNull(row.repair)
        assertTrue(row.detail.contains("尚未探测"))
    }

    @Test
    fun `row copy never leaks a filesystem path or an internal identifier`() {
        val probes = listOf(
            RuntimeSelfCheckProbe(),
            healthyProbe(),
            healthyProbe().copy(
                processRunning = false,
                webPortAvailable = false,
                gatewayHealthy = false,
                terminalPtyReady = false,
                configDirectoryReady = false
            )
        )

        probes.forEach { probe ->
            RuntimeSelfCheck.rows(probe).forEach { row ->
                val text = "${row.title} ${row.detail} ${row.statusLabel}"
                listOf("/data/", "filesDir", "/storage", "content://", "exception", "java.").forEach { forbidden ->
                    assertFalse("row '${row.key}' leaked '$forbidden': $text", text.contains(forbidden, ignoreCase = true))
                }
                assertNotNull(row.key)
                assertTrue(row.key.isNotBlank())
                assertTrue(row.detail.isNotBlank())
            }
        }
    }

    @Test
    fun `status labels stay stable for the console`() {
        assertEquals("正常", row(RuntimeSelfCheck.KEY_PROCESS, healthyProbe()).statusLabel)
        assertEquals("异常", row(RuntimeSelfCheck.KEY_PROCESS, healthyProbe().copy(processRunning = false)).statusLabel)
        assertEquals("未检测", row(RuntimeSelfCheck.KEY_PROCESS, RuntimeSelfCheckProbe()).statusLabel)
    }

    private fun healthyProbe() = RuntimeSelfCheckProbe(
        processRunning = true,
        webPort = 14_000,
        webPortAvailable = true,
        gatewayHealthy = true,
        terminalPtyReady = true,
        configDirectoryReady = true
    )

    private fun row(key: String, probe: RuntimeSelfCheckProbe): RuntimeSelfCheckRow =
        RuntimeSelfCheck.rows(probe).first { it.key == key }
}

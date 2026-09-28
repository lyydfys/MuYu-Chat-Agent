package com.muyuchat.feature.chat

import com.muyuchat.core.engine.RuntimeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDeviceStatusTest {
    @Test
    fun exposesFourStableRowsWhenRuntimeHasNoEvidence() {
        val rows = ChatUiState().deviceStatusItems()

        assertEquals(listOf("cpu", "gpu", "npu", "memory"), rows.map { it.key })
        assertEquals("在线", rows.first { it.key == "cpu" }.value)
        assertEquals("待检测", rows.first { it.key == "gpu" }.value)
        assertEquals("未使用", rows.first { it.key == "npu" }.value)
        assertEquals("待检测", rows.first { it.key == "memory" }.value)
        assertFalse(rows.first { it.key == "gpu" }.active)
    }

    @Test
    fun verifiedGpuAndQnnBackendArePresentedAsActive() {
        val stats = RuntimeStats(
            loaded = true,
            backend = "qnn_htp",
            gpuOffloadSupported = true,
            gpuOffloadActive = true,
            gpuOffloadAllocationObserved = true,
            gpuOffloadExecutionObserved = true,
            gpuOffloadLayers = 18,
            gpuOffloadLayersKnown = true,
            totalMemKb = 8L * 1024 * 1024,
            availMemKb = 2L * 1024 * 1024
        )

        val rows = ChatUiState(stats = stats).deviceStatusItems()

        assertEquals("运行中", rows.first { it.key == "gpu" }.value)
        assertTrue(rows.first { it.key == "gpu" }.detail.contains("18 层"))
        assertEquals("运行中", rows.first { it.key == "npu" }.value)
        assertEquals("6.0 GB / 8.0 GB", rows.first { it.key == "memory" }.value)
        assertTrue(rows.first { it.key == "memory" }.detail.contains("2.0 GB"))
    }

    @Test
    fun cloudSelectionDoesNotClaimLocalAccelerators() {
        val rows = ChatUiState(
            selectedModelIsCloud = true,
            stats = RuntimeStats(
                loaded = true,
                backend = "gpu",
                gpuOffloadSupported = true,
                totalMemKb = 1024L * 1024,
                availMemKb = 512L * 1024
            )
        ).deviceStatusItems()

        assertEquals("未使用", rows.first { it.key == "gpu" }.value)
        assertEquals("未使用", rows.first { it.key == "npu" }.value)
    }
}

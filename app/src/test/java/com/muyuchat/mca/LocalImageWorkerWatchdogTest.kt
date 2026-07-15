package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageWorkerWatchdogTest {
    @Test
    fun `hard watchdog applies only to qnn sdxl`() {
        val policy = localImageWorkerWatchdogPolicy(
            runtime = LocalImageRuntime.QNN_HTP,
            family = LocalImageModelFamily.SDXL
        )

        assertEquals(SDXL_QNN_WORKER_TIMEOUT_MS, policy?.timeoutMs)
        assertEquals("qnn_sdxl_worker_timeout", LOCAL_IMAGE_WORKER_WATCHDOG_TIMEOUT_CODE)
        assertNull(
            localImageWorkerWatchdogPolicy(
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SD15
            )
        )
        assertNull(
            localImageWorkerWatchdogPolicy(
                runtime = LocalImageRuntime.MNN_DIFFUSION,
                family = LocalImageModelFamily.SDXL
            )
        )
    }

    @Test
    fun `timeout message preserves last phase and accumulated native stages`() {
        val message = localImageWorkerWatchdogMessage(
            timeoutMs = SDXL_QNN_WORKER_TIMEOUT_MS,
            phase = "graph_execute",
            stageTrace = listOf(
                "context_lock",
                "context_binary_mmap",
                "context_create",
                "graph_execute"
            )
        )

        assertTrue(message.contains("360s"))
        assertTrue(message.contains("phase=graph_execute"))
        assertTrue(message.contains("context_binary_mmap -> context_create -> graph_execute"))
    }

    @Test
    fun `lease waiting does not consume the qnn execution deadline`() {
        assertEquals(false, localImageWorkerWatchdogStartsAtPhase("worker_started"))
        assertEquals(false, localImageWorkerWatchdogStartsAtPhase("waiting_for_native_lease"))
        assertEquals(true, localImageWorkerWatchdogStartsAtPhase("conditioning"))
        assertEquals(true, localImageWorkerWatchdogStartsAtPhase("context_create"))
        assertEquals(true, localImageWorkerWatchdogStartsAtPhase("graph_execute"))
    }

    @Test
    fun `later coarse progress cannot erase a persisted native trace`() {
        val detailed = listOf(
            "unet_graph_retrieve_after",
            "vae_context_release_before",
            "runtime_unload_before"
        )
        assertEquals(detailed, accumulateNativeStageTrace(detailed, emptyList()))
        assertEquals(
            detailed + "runtime_unload_after",
            accumulateNativeStageTrace(detailed, detailed + "runtime_unload_after")
        )
    }
}

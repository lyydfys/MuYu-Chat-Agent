package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageWorkerWatchdogTest {
    @Test
    fun `split ultrafix encoder timeout scales with physical tile count and stays bounded`() {
        val singleTile = sdxlEncoderPhaseTimeoutMs(1)
        val nineTiles = sdxlEncoderPhaseTimeoutMs(9)

        assertEquals(sdxlEncoderPhaseTimeoutMs(), singleTile)
        assertTrue(nineTiles > singleTile)
        assertEquals(sdxlEncoderPhaseTimeoutMs(64), sdxlEncoderPhaseTimeoutMs(Int.MAX_VALUE))
    }

    @Test
    fun `hard watchdog applies only to qnn sdxl`() {
        val policy = localImageWorkerWatchdogPolicy(
            runtime = LocalImageRuntime.QNN_HTP,
            family = LocalImageModelFamily.SDXL,
            steps = 30,
            useCfg = true
        )

        assertEquals(sdxlWorkerTimeoutMs(30, true), policy?.timeoutMs)
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
    fun `explicit mnn opencl receives a disposable worker deadline`() {
        val policy = localImageWorkerWatchdogPolicy(
            runtime = LocalImageRuntime.MNN_DIFFUSION,
            family = LocalImageModelFamily.SD15,
            steps = 4,
            backendMode = "gpu"
        )

        assertEquals(mnnOpenClWorkerTimeoutMs(4), policy?.timeoutMs)
        assertEquals(MNN_OPENCL_WORKER_WATCHDOG_TIMEOUT_CODE, policy?.timeoutCode)
        assertNull(
            localImageWorkerWatchdogPolicy(
                runtime = LocalImageRuntime.MNN_DIFFUSION,
                family = LocalImageModelFamily.SD15,
                backendMode = "cpu"
            )
        )
    }

    @Test
    fun `timeout message preserves last phase and accumulated native stages`() {
        val timeoutMs = sdxlWorkerTimeoutMs(30, true)
        val message = localImageWorkerWatchdogMessage(
            timeoutMs = timeoutMs,
            phase = "graph_execute",
            stageTrace = listOf(
                "context_lock",
                "context_binary_mmap",
                "context_create",
                "graph_execute"
            )
        )

        assertTrue(message.contains("${timeoutMs / 1_000L}s"))
        assertTrue(message.contains("phase=graph_execute"))
        assertTrue(message.contains("context_binary_mmap -> context_create -> graph_execute"))
        assertTrue(message.contains("termination was requested"))
    }

    @Test
    fun `watchdog budget scales with steps and cfg branches`() {
        val oneStep = sdxlWorkerTimeoutMs(1, useCfg = false)
        val thirtyStepsSingleBranch = sdxlWorkerTimeoutMs(30, useCfg = false)
        val thirtyStepsCfg = sdxlWorkerTimeoutMs(30, useCfg = true)

        assertTrue(thirtyStepsSingleBranch > oneStep)
        assertTrue(thirtyStepsCfg > thirtyStepsSingleBranch)
        assertTrue(sdxlUnetPhaseTimeoutMs(60) < thirtyStepsCfg)
        assertTrue(sdxlVaePhaseTimeoutMs(SDXL_DEFAULT_VAE_EXECUTION_COUNT) < thirtyStepsCfg)
    }

    @Test
    fun `lease waiting does not consume the qnn execution deadline`() {
        assertEquals(false, localImageWorkerWatchdogStartsAtPhase("worker_started"))
        assertEquals(false, localImageWorkerWatchdogStartsAtPhase("waiting_for_native_lease"))
        assertEquals(true, localImageWorkerWatchdogStartsAtPhase("conditioning"))
        assertEquals(true, localImageWorkerWatchdogStartsAtPhase("context_create"))
        assertEquals(true, localImageWorkerWatchdogStartsAtPhase("graph_execute"))
        assertEquals(true, localImageWorkerWatchdogStartsAtPhase("generating"))
        assertEquals(true, localImageWorkerWatchdogStartsAtPhase("saving"))
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

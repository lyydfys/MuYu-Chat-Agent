package com.muyuchat.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLoadContractTest {
    @Test
    fun parsesProcCountersAndComputesBoundedCpuDeltas() {
        val first = SystemLoadParser.withProcessTicks(
            SystemLoadParser.parseCpuStat("cpu 100 0 100 700 100 0 0 0\n")!!,
            10
        )
        val second = SystemLoadParser.withProcessTicks(
            SystemLoadParser.parseCpuStat("cpu 120 0 120 740 120 0 0 0\n")!!,
            20
        )

        val load = SystemLoadParser.delta(first, second)

        assertEquals(40, load!!.systemPercent)
        assertEquals(10, load.processPercent)
        assertTrue(load.systemPercent in 0..100)
        assertTrue(load.processPercent in 0..100)
    }

    @Test
    fun processStatParserHandlesSpacesInsideCommandName() {
        // The final two values below are utime and stime (fields 14 and 15).
        val stat = "123 (mca worker with spaces) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14"
        assertEquals(23L, SystemLoadParser.parseProcessTicks(stat))
    }

    @Test
    fun busyCounterRequiresAnExplicitTotal() {
        assertEquals(25, SystemLoadParser.parseBusyTotal("25 100", null))
        assertEquals(25, SystemLoadParser.parseBusyTotal("25", "100"))
        assertNull(SystemLoadParser.parseBusyTotal("25", null))
        assertNull(SystemLoadParser.parseBusyTotal("125 100", null))
    }

    @Test
    fun nativeExecutionEvidenceNeverBecomesAUsagePercentage() {
        val npu = NativeAcceleratorEvidence.fromNativeStats(
            "{\"npuExecutionProven\":true,\"computeUnit\":\"htp\"}",
            NativeAcceleratorEvidence.Accelerator.NPU
        )
        val gpu = NativeAcceleratorEvidence.fromNativeStats(
            "{\"gpuOffloadActive\":true}",
            NativeAcceleratorEvidence.Accelerator.GPU
        )

        assertNull(npu.utilizationPercent)
        assertTrue(npu.executionObserved)
        assertNull(gpu.utilizationPercent)
        assertTrue(gpu.executionObserved)
        assertEquals(AcceleratorLoad.Status.UNAVAILABLE, npu.status)
        assertEquals(AcceleratorLoad.Status.UNAVAILABLE, gpu.status)

        val alias = NativeAcceleratorEvidence.fromNativeStats(
            "{\"geniexComputeUnit\":\"htp\"}",
            NativeAcceleratorEvidence.Accelerator.NPU
        )
        assertTrue(alias.executionObserved)
    }

    @Test
    fun samplerDoesNotReuseStaleCpuSampleWhenCountersGoBackwards() {
        val source = FakeSource(
            cpu = listOf(
                "cpu 100 0 100 700 100 0 0 0",
                "cpu 90 0 90 700 100 0 0 0"
            ),
            process = listOf("123 (mca) S 1 2 3 4 5 6 7 8 9 10 10 0", "123 (mca) S 1 2 3 4 5 6 7 8 9 10 8 0")
        )
        val sampler = SystemLoadSampler(source)
        sampler.sample()
        val second = sampler.sample()
        assertNull(second.cpu.systemPercent)
        assertNull(second.cpu.processPercent)
    }

    @Test
    fun processCpuFallsBackToPublicAndroidStyleCpuTimeWhenProcfsIsBlocked() {
        val source = object : SystemLoadDataSource {
            private val processCpuSamples = listOf(100L, 400L).iterator()
            override fun readCpuStat(): String? = null
            override fun readProcessStat(): String? = null
            override fun readProcessCpuTimeMillis(): Long? = processCpuSamples.next()
            override fun readMemory() = MemoryLoad(100, 200, 42)
            override fun readGpuLoad() = AcceleratorLoad.unavailable("test")
            override fun readNpuLoad() = AcceleratorLoad.unavailable("test")
        }
        val elapsedSamples = listOf(1_000L, 2_000L).iterator()
        val sampler = SystemLoadSampler(
            source = source,
            elapsedRealtimeMillis = elapsedSamples::next,
            processorCount = 4
        )

        assertNull(sampler.sample().cpu.processPercent)
        val second = sampler.sample()

        // 300 ms of process CPU over 1 second on a four-core device = 7.5% of total capacity.
        assertEquals(7, second.cpu.processPercent)
        assertNull(second.cpu.systemPercent)
    }

    @Test
    fun systemCpuRemainsReadableWhenOnlyProcessProcfsEntryIsBlocked() {
        val source = object : SystemLoadDataSource {
            private val systemSamples = listOf(
                "cpu 100 0 100 700 100 0 0 0",
                "cpu 120 0 120 740 120 0 0 0"
            ).iterator()
            override fun readCpuStat(): String? = systemSamples.next()
            override fun readProcessStat(): String? = null
            override fun readMemory() = MemoryLoad(100, 200, 42)
            override fun readGpuLoad() = AcceleratorLoad.unavailable("test")
            override fun readNpuLoad() = AcceleratorLoad.unavailable("test")
        }
        val sampler = SystemLoadSampler(source)
        sampler.sample()

        val second = sampler.sample()

        assertEquals(40, second.cpu.systemPercent)
        assertNull(second.cpu.processPercent)
    }

    @Test
    fun missingAcceleratorCountersAreExplicitlyUnavailable() {
        val result = NativeAcceleratorEvidence.fromNativeStats(null, NativeAcceleratorEvidence.Accelerator.NPU)
        assertEquals(AcceleratorLoad.Status.UNAVAILABLE, result.status)
        assertFalse(result.executionObserved)
        assertNull(result.utilizationPercent)
    }

    private class FakeSource(
        private val cpu: List<String>,
        private val process: List<String>
    ) : SystemLoadDataSource {
        private var cpuIndex = 0
        private var processIndex = 0
        override fun readCpuStat(): String = cpu.getOrElse(cpuIndex++) { cpu.last() }
        override fun readProcessStat(): String = process.getOrElse(processIndex++) { process.last() }
        override fun readMemory() = MemoryLoad(100, 200, 42)
        override fun readGpuLoad() = AcceleratorLoad.unavailable("test")
        override fun readNpuLoad() = AcceleratorLoad.unavailable("test")
    }
}

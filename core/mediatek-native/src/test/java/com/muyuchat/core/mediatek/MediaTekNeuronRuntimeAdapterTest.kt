package com.muyuchat.core.mediatek

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekNeuronRuntimeAdapterTest {
    private class FakeBridge : MediaTekNeuronNativeBridge {
        var probeText = """{"status":"BLOCKED","libraryPath":null,"version":null,"devices":[],"message":"no runtime"}"""
        var restoreText = """{"status":"OPEN","libraryPath":"libneuronusdk.so","version":"9.0.0","inputCount":2,"outputCount":1,"inputPaddedBytes":[4,8],"outputPaddedBytes":[16],"inputDims":[[1,2],[1,4]],"outputDims":[[1,4]],"restoreElapsedMs":12,"message":""}"""
        var outputs = arrayOf(ByteArray(16) { it.toByte() })
        var restoreCalls = 0
        var runCalls = 0
        var closeCalls = 0

        override fun nativeProbe(): String = probeText

        override fun nativeRestore(
            dlaPath: String,
            inputCount: Int,
            outputCount: Int,
            inputRanks: IntArray,
        ): String {
            restoreCalls++
            return restoreText
        }

        override fun nativeRun(inputs: Array<ByteArray>): Array<ByteArray> {
            runCalls++
            return outputs
        }

        override fun nativeClose() {
            closeCalls++
        }

        override fun nativeLastError(): String = ""
    }

    @Test
    fun probeParsesBlockedRuntimeWithoutOpeningAnything() {
        val bridge = FakeBridge()
        val adapter = MediaTekNeuronRuntimeAdapter(bridge)

        val info = adapter.probe().getOrThrow()

        assertEquals("BLOCKED", info.status)
        assertNull(info.libraryPath)
        assertTrue(info.devices.isEmpty())
        assertEquals(0, bridge.restoreCalls)
    }

    @Test
    fun openRejectsMissingDlaBeforeCallingNative() {
        val bridge = FakeBridge()
        val adapter = MediaTekNeuronRuntimeAdapter(bridge)

        val failure = adapter.open(File("missing.dla"), 2, 1, intArrayOf(2, 2)).exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, bridge.restoreCalls)
    }

    @Test
    fun openParsesRuntimePaddedSizesAndMarksOpen() {
        val bridge = FakeBridge()
        val adapter = MediaTekNeuronRuntimeAdapter(bridge)
        val file = tempDla()

        val info = adapter.open(file, 2, 1, intArrayOf(2, 2)).getOrThrow()

        assertEquals("OPEN", info.status)
        assertEquals(listOf(4L, 8L), info.inputPaddedBytes)
        assertEquals(listOf(16L), info.outputPaddedBytes)
        assertEquals(1, info.outputCount)
        assertEquals(MediaTekNeuronRuntimeAdapter.State.OPEN, adapter.state)
        assertEquals(info, adapter.networkInfo)
        assertEquals(1, bridge.restoreCalls)
    }

    @Test
    fun runValidatesInputSizesBeforeCallingNative() {
        val bridge = FakeBridge()
        val adapter = MediaTekNeuronRuntimeAdapter(bridge)
        adapter.open(tempDla(), 2, 1, intArrayOf(2, 2)).getOrThrow()

        val failure = adapter.run(listOf(ByteArray(3), ByteArray(8))).exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, bridge.runCalls)
    }

    @Test
    fun runTrimsThePaddedOutputToTheLogicalSize() {
        val bridge = FakeBridge()
        val adapter = MediaTekNeuronRuntimeAdapter(bridge)
        adapter.open(tempDla(), 2, 1, intArrayOf(2, 2)).getOrThrow()

        val outputs = adapter.run(listOf(ByteArray(4), ByteArray(8)), logicalOutputBytes = 8).getOrThrow()

        assertEquals(1, outputs.size)
        assertEquals(8, outputs.single().size)
        assertEquals(7.toByte(), outputs.single()[7])
        assertEquals(1, bridge.runCalls)
    }

    @Test
    fun failedRestoreKeepsTheAdapterClosed() {
        val bridge = FakeBridge().apply {
            restoreText = """{"status":"FAILED","stage":"restore","code":3,"message":"restore failed"}"""
        }
        val adapter = MediaTekNeuronRuntimeAdapter(bridge)

        val failure = adapter.open(tempDla(), 2, 1, intArrayOf(2, 2)).exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(MediaTekNeuronRuntimeAdapter.State.IDLE, adapter.state)
        assertNull(adapter.networkInfo)
        assertEquals(1, bridge.closeCalls)
    }

    @Test
    fun runBeforeOpenFailsWithoutCallingNative() {
        val bridge = FakeBridge()
        val adapter = MediaTekNeuronRuntimeAdapter(bridge)

        val failure = adapter.run(listOf(ByteArray(4))).exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, bridge.runCalls)
    }

    @Test
    fun closeIsIdempotent() {
        val bridge = FakeBridge()
        val adapter = MediaTekNeuronRuntimeAdapter(bridge)
        adapter.open(tempDla(), 2, 1, intArrayOf(2, 2)).getOrThrow()

        adapter.close()
        adapter.close()

        assertEquals(1, bridge.closeCalls)
        assertEquals(MediaTekNeuronRuntimeAdapter.State.CLOSED, adapter.state)
        assertNull(adapter.networkInfo)
    }

    private fun tempDla(): File = Files.createTempFile("mca-neuron", ".dla").toFile().apply {
        deleteOnExit()
        writeBytes(byteArrayOf(1, 2, 3))
    }
}

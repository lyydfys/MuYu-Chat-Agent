package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic fake of the MNN chat native leaf. Every field mirrors observable native behaviour
 * so the port's lease/drain/release bookkeeping can be asserted without a device.
 */
private class FakeMnnLeaf : MnnChatNativeLeaf {
    @Volatile var loaded = false
    @Volatile var generationActive = false
    @Volatile var beginRc = 0
    @Volatile var loadFailure: Int? = null

    /** Simulates the native unload that returns early because a producer refused to join. */
    @Volatile var unloadLeavesOwnerAlive = false

    @Volatile var statsOverride: String? = null

    var loadCalls = 0
    var unloadCalls = 0
    var shutdownCalls = 0
    var stopCalls = 0
    var lastLoadPath: String? = null
    var lastParams: String? = null
    val chunks = ArrayDeque<String>()

    override fun loadModel(configPath: String, paramsJson: String): Int {
        loadCalls++
        lastLoadPath = configPath
        lastParams = paramsJson
        val failure = loadFailure
        if (failure != null) {
            loaded = false
            return failure
        }
        loaded = true
        return 0
    }

    override fun unloadModel() {
        unloadCalls++
        if (unloadLeavesOwnerAlive) return
        loaded = false
        generationActive = false
    }

    override fun beginCompletion(messagesJson: String, paramsJson: String): Int {
        generationActive = beginRc == 0
        return beginRc
    }

    override fun generateNextChunk(): String? =
        if (chunks.isEmpty()) null else chunks.removeFirst()

    override fun requestStop() {
        stopCalls++
        generationActive = false
    }

    override fun requestStopIfActive(): Boolean {
        if (!generationActive) return false
        stopCalls++
        generationActive = false
        return true
    }

    override fun isGenerationRunning(): Boolean = generationActive

    override fun getRuntimeStatsJson(): String =
        statsOverride ?: """{"loaded":$loaded,"generationActive":$generationActive}"""

    override fun shutdown() {
        shutdownCalls++
        loaded = false
        generationActive = false
    }
}

class MnnChatRuntimePortTest {

    private fun newEngine(leaf: FakeMnnLeaf, path: String = "/models/mnn-chat"): MnnChatEnginePort =
        MnnChatEnginePort(engineId = "engine-test-1", modelPath = path, leaf = leaf)

    @Test
    fun loadReportsRawReturnCodeAndReadinessOnlyAfterSuccess() {
        val leaf = FakeMnnLeaf()
        val engine = newEngine(leaf)

        assertFalse("a fresh engine is not ready", engine.isReady)
        assertEquals(MnnChatEnginePort.NOT_LOADED_RC, engine.lastLoadReturnCode)

        assertEquals(0, engine.load("""{"temperature":0}"""))
        assertTrue(engine.isReady)
        assertEquals("/models/mnn-chat", leaf.lastLoadPath)
        assertEquals("""{"temperature":0}""", leaf.lastParams)

        leaf.loadFailure = 7
        val failing = newEngine(leaf, "/models/other")
        assertEquals(7, failing.load("{}"))
        assertEquals(7, failing.lastLoadReturnCode)
        assertFalse("a failed load must never report ready", failing.isReady)
    }

    @Test
    fun failedLoadLeavesNoUsableSessionAndNoHalfInitializedHandle() {
        val leaf = FakeMnnLeaf()
        leaf.loadFailure = -3
        val engine = newEngine(leaf)
        assertEquals(-3, engine.load("{}"))
        assertFalse(engine.isReady)
        assertThrows(IllegalStateException::class.java) {
            engine.createSession("mnn-chat-default")
        }
    }

    @Test
    fun duplicateSessionIdIsAConflictAndNeverReplacesTheLiveSession() {
        val leaf = FakeMnnLeaf()
        val engine = newEngine(leaf)
        engine.load("{}")

        val first = engine.createSession("s-1")
        assertThrows(IllegalStateException::class.java) {
            engine.createSession("s-1")
        }
        // The live session is untouched by the rejected duplicate.
        assertFalse(first.isLeaseActive)
        val handle = (first as MnnChatSessionPort).beginRequest("[]", "{}")
        assertNotNull(handle)
        assertTrue(first.isLeaseActive)
    }

    @Test
    fun oneExecutionLeasePerSessionAndIdempotentRelease() {
        val leaf = FakeMnnLeaf()
        val engine = newEngine(leaf)
        engine.load("{}")
        val session = engine.createSession("s-1") as MnnChatSessionPort

        val handle = session.beginRequest("[]", "{}")
        assertNotNull(handle)
        assertTrue(session.isLeaseActive)

        // A foreign caller cannot take a second lease while the attempt is live.
        assertNull(session.tryAcquireExecutionLease(RequestIdentity.create("foreign")))

        handle!!.close()
        assertFalse(session.isLeaseActive)
        handle.close()
        assertTrue(handle.isReleased)

        val next = session.tryAcquireExecutionLease(RequestIdentity.create("foreign-2"))
        assertNotNull("a released lease is reusable", next)
        next!!.close()
    }

    @Test
    fun cancelIsCooperativeAndDrainAckComesFromTheNativeStats() {
        val leaf = FakeMnnLeaf()
        val engine = newEngine(leaf)
        engine.load("{}")
        val session = engine.createSession("s-1") as MnnChatSessionPort
        leaf.chunks.addLast("hello")
        val handle = session.beginRequest("[]", "{}")!!

        assertEquals("hello", handle.nextChunk())
        assertTrue("still generating until the native ack says otherwise", handle.isNativeRunning())

        assertEquals(true, handle.requestStopIfActive())
        assertEquals(1, leaf.stopCalls)
        assertFalse("drain ack: the native owner stopped", handle.isNativeRunning())
        assertTrue(handle.beginRc == 0 && handle.isAccepted)
    }

    @Test
    fun drainProbeFailsClosedWhenStatsCannotBeParsed() {
        val leaf = FakeMnnLeaf()
        val engine = newEngine(leaf)
        engine.load("{}")
        val session = engine.createSession("s-1") as MnnChatSessionPort
        val handle = session.beginRequest("[]", "{}")!!
        leaf.statsOverride = "not-json"
        assertTrue("an unreadable ack must not be reported as a stopped owner", handle.isNativeRunning())
    }

    @Test
    fun unloadVerifiesReleaseAndKeepsLeasesUntilDrainIsConfirmed() {
        val leaf = FakeMnnLeaf()
        leaf.unloadLeavesOwnerAlive = true
        val engine = newEngine(leaf)
        engine.load("{}")
        val session = engine.createSession("s-1") as MnnChatSessionPort
        session.beginRequest("[]", "{}")

        engine.unload()
        assertEquals(1, leaf.unloadCalls)
        assertFalse("native unload returned early; release is not confirmed", engine.isNativeReleaseConfirmed)
        assertNotNull(engine.nativeReleaseMessage)
        assertTrue("the in-flight lease must not be force-released", session.isLeaseActive)

        // The stalled owner finishes draining; the release can now be verified.
        leaf.loaded = false
        leaf.generationActive = false
        assertTrue(engine.retryRelease())
        assertTrue(engine.isNativeReleaseConfirmed)
        assertNull(engine.nativeReleaseMessage)
        assertFalse(session.isLeaseActive)
    }

    @Test
    fun unloadCallsTheNativeOwnerOncePerLoadCycle() {
        val leaf = FakeMnnLeaf()
        val engine = newEngine(leaf)
        engine.load("{}")
        engine.unload()
        engine.unload()
        assertEquals(1, leaf.unloadCalls)

        engine.load("{}")
        engine.unload()
        assertEquals(2, leaf.unloadCalls)
    }

    @Test
    fun supersedeNeverCallsTheNativeUnloadOfTheNewModel() {
        val leaf = FakeMnnLeaf()
        val old = newEngine(leaf)
        old.load("{}")

        old.supersede()
        old.close()
        assertEquals("a superseded engine must not unload the newer native model", 0, leaf.unloadCalls)
        assertFalse(old.isReady)
    }

    @Test
    fun nativeShutdownRetiresPortStateWithoutExtraNativeCalls() {
        val leaf = FakeMnnLeaf()
        val engine = newEngine(leaf)
        engine.load("{}")
        val session = engine.createSession("s-1") as MnnChatSessionPort
        session.beginRequest("[]", "{}")

        leaf.shutdown()
        engine.markNativeShutdown()
        assertEquals(0, leaf.unloadCalls)
        assertFalse(session.isLeaseActive)
        assertTrue(engine.isNativeReleaseConfirmed)
    }

    @Test
    fun attemptIdentitiesAreMonotonicAndCarryTheRuntimeEpoch() {
        val leaf = FakeMnnLeaf()
        val engine = MnnChatEnginePort(
            engineId = "engine-test-2",
            modelPath = "/models/mnn-chat",
            leaf = leaf,
            runtimeEpoch = RuntimeEpoch(4L)
        )
        val first = engine.newRequestIdentity("s-1")
        val second = engine.newRequestIdentity("s-1")
        assertNotEquals(first.requestId, second.requestId)
        assertTrue(second.attemptId > first.attemptId)
        assertEquals(4L, first.runtimeEpoch)
    }

    @Test
    fun backendOwnerFactoryProducesARealEngineAndDefaultKeepsTheSkeleton() {
        val leaf = FakeMnnLeaf()
        val owner = BackendRuntimeOwner(
            backendKind = "MNN_CPU",
            epoch = RuntimeEpoch(1L),
            engineFactory = { engineId, modelPath ->
                MnnChatEnginePort(engineId = engineId, modelPath = modelPath, leaf = leaf)
            }
        )
        val engine = owner.createEngine("/models/mnn-chat") as MnnChatEnginePort
        assertEquals(0, engine.load("{}"))
        assertTrue(engine.isReady)
        owner.shutdown()
        assertEquals(1, leaf.unloadCalls)

        val skeletonOwner = BackendRuntimeOwner(backendKind = "MNN_CPU", epoch = RuntimeEpoch(1L))
        assertFalse(
            "without a wired loader the audited skeleton behaviour stays explicit",
            skeletonOwner.createEngine("/models/mnn-chat").isReady
        )
    }

    @Test
    fun releaseEngineStopsTrackingWithoutASecondNativeUnload() {
        val leaf = FakeMnnLeaf()
        val owner = BackendRuntimeOwner(
            backendKind = "MNN_CPU",
            epoch = RuntimeEpoch(1L),
            engineFactory = { engineId, modelPath ->
                MnnChatEnginePort(engineId = engineId, modelPath = modelPath, leaf = leaf)
            }
        )
        val engine = owner.createEngine("/models/mnn-chat") as MnnChatEnginePort
        engine.load("{}")
        engine.supersede()

        assertTrue(owner.releaseEngine(engine.engineId))
        assertFalse("an unknown/released id is refused", owner.releaseEngine(engine.engineId))
        assertEquals("a superseded engine never unloads the newer native model", 0, leaf.unloadCalls)

        // The released engine is no longer owned, so shutdown must not touch it again.
        val second = owner.createEngine("/models/mnn-chat-2") as MnnChatEnginePort
        second.load("{}")
        owner.shutdown()
        assertEquals(1, leaf.unloadCalls)
    }
}
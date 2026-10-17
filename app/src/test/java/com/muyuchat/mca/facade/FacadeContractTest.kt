package com.muyuchat.mca.facade

import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.contract.RuntimeEpoch
import com.muyuchat.core.engine.runtime.BackendPort
import com.muyuchat.core.engine.runtime.EnginePort
import com.muyuchat.core.engine.runtime.HardwareProbeAdvisory
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FacadeContractTest {

    @Test
    fun testRuntimeFacadeLifecycleAndEpochMigration() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val facade = RuntimeFacade(supervisor)

        assertEquals(RuntimeEpoch(1L), facade.currentEpoch)
        assertTrue(facade.isHealthy)

        val health = facade.evaluateHealth()
        assertEquals(RuntimeEpoch(1L), health.epoch)
        assertTrue(health.isHealthy)

        val newEpoch = facade.advanceEpoch()
        assertEquals(RuntimeEpoch(2L), newEpoch)
        assertEquals(RuntimeEpoch(2L), facade.currentEpoch)

        facade.shutdown()
        assertFalse(facade.isHealthy)
    }

    @Test
    fun testChatExecutionFacadeCommandEquivalence() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val chatFacade = ChatExecutionFacade(supervisor)

        val cmd = ChatCommand(
            prompt = "Hello MCA enterprise refactor",
            sessionId = "session-1",
            requestId = "req-chat-001"
        )

        val events = mutableListOf<String>()
        val identity = chatFacade.dispatchChat(cmd) { ev ->
            events.add(ev.eventType.name)
        }

        assertNotNull(identity)
        assertEquals("req-chat-001", identity.requestId)
        assertEquals(1L, identity.runtimeEpoch)
        assertTrue(chatFacade.isChatActive("req-chat-001"))

        // Cancellation
        val cancelled = chatFacade.cancelChat("req-chat-001", "User stop")
        assertTrue(cancelled)

        supervisor.shutdownProcess()
    }

    @Test
    fun testImageGenerationFacadeCommandEquivalence() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val imageFacade = ImageGenerationFacade(supervisor)

        val cmd = ImageCommand(
            prompt = "A high-tech control room in neon glow",
            targetOutputPath = "/tmp/test.png",
            requestId = "req-img-001"
        )

        val identity = imageFacade.dispatchImage(cmd) { /* no-op */ }

        assertNotNull(identity)
        assertEquals("req-img-001", identity.requestId)
        assertEquals(1L, identity.runtimeEpoch)
        assertTrue(imageFacade.isImageActive("req-img-001"))

        val cancelled = imageFacade.cancelImage("req-img-001")
        assertTrue(cancelled)

        supervisor.shutdownProcess()
    }

    @Test
    fun testModelLifecycleFacadeRefusesToFakeALoad() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        // Recording loader: reports the truth (nothing was loaded) and records whether the
        // facade released the engine before failing. A fake backend that reported success here
        // would re-introduce exactly the fake-ready defect this test exists to prevent.
        val engine = RecordingNotReadyEngine("engine-1", "/models/never-loaded.bin")
        val backend = RecordingBackend(backendKind = "GenericCpu", epoch = supervisor.currentEpoch, engine = engine)
        supervisor.registerBackend("GenericCpu", backend)

        val modelFacade = ModelLifecycleFacade(supervisor)

        val tempModel = File.createTempFile("mca_test_model", ".bin")
        try {
            val failure = runCatching { modelFacade.loadModel("GenericCpu", tempModel.absolutePath) }
                .exceptionOrNull()

            assertTrue(
                "an unwired skeleton must fail the load explicitly instead of reporting a fake ready engine",
                failure is UnsupportedOperationException
            )
            assertTrue("the failure must name the backend", failure!!.message!!.contains("GenericCpu"))

            // Resource contract: the engine that could not become ready is closed and is NOT
            // registered as a loaded model.
            assertTrue("the unusable engine must be released", engine.closeCount == 1)
            assertFalse(modelFacade.isModelLoaded(engine.engineId))
            assertFalse(modelFacade.isModelLoaded("never-created"))
            // Nothing was leaked into the facade, so unloading everything is a no-op that must not throw.
            modelFacade.unloadAll()
        } finally {
            tempModel.delete()
            supervisor.shutdownProcess()
        }
    }

    private class RecordingNotReadyEngine(
        override val engineId: String,
        override val modelPath: String
    ) : EnginePort {
        var closeCount = 0
        override val isReady: Boolean = false
        override fun createSession(sessionId: String) = throw UnsupportedOperationException("not ready")
        override fun unload() = Unit
        override fun close() { closeCount++ }
    }

    private class RecordingBackend(
        override val backendKind: String,
        override val epoch: RuntimeEpoch,
        private val engine: EnginePort
    ) : BackendPort {
        override val hardwareAdvisory = HardwareProbeAdvisory()
        override val isInitialized = true
        override fun createEngine(modelPath: String) = engine
        override fun shutdown() = Unit
        override fun close() = Unit
    }
}

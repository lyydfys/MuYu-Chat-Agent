package com.muyuchat.mca.facade

import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.contract.RuntimeEpoch
import com.muyuchat.core.engine.runtime.BackendRuntimeOwner
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
    fun testModelLifecycleFacadeManagement() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val backend = BackendRuntimeOwner(
            backendKind = "GenericCpu",
            epoch = supervisor.currentEpoch
        )
        supervisor.registerBackend("GenericCpu", backend)

        val modelFacade = ModelLifecycleFacade(supervisor)

        // Create a temporary mock model file
        val tempModel = File.createTempFile("mca_test_model", ".bin")
        try {
            val engine = modelFacade.loadModel("GenericCpu", tempModel.absolutePath)
            assertNotNull(engine)
            assertTrue(engine.isReady)
            assertTrue(modelFacade.isModelLoaded(engine.engineId))

            val unloaded = modelFacade.unloadModel(engine.engineId)
            assertTrue(unloaded)
            assertFalse(modelFacade.isModelLoaded(engine.engineId))
        } finally {
            tempModel.delete()
            supervisor.shutdownProcess()
        }
    }
}

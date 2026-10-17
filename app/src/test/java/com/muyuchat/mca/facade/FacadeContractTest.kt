package com.muyuchat.mca.facade

import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FacadeContractTest {

    @Test
    fun testChatExecutionFacadeCommandEquivalence() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val chatFacade = ChatExecutionFacade.admissionOnly(supervisor)

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

        val cancelled = chatFacade.cancelChat("req-chat-001", "User stop")
        assertTrue(cancelled)

        supervisor.shutdownProcess()
    }

    @Test
    fun testProductionExecutionOwnerPathIsUnique() {
        val sources = productionKotlinSources()
        val container = sources.getValue(MCA_APP_CONTAINER)
        val imageParts = sources.getValue(MCA_APP_CONTAINER_IMAGE_PARTS)
        val mainViewModel = sources.getValue(MAIN_VIEW_MODEL)
        val mainRuntimeAssembly = sources.getValue(MAIN_RUNTIME_ASSEMBLY)

        assertTrue(
            "the production chat facade must receive the shared supervisor",
            container.contains("supervisor = sharedRuntimeSupervisor")
        )
        assertTrue(
            "the production chat facade must receive the real engine execution body",
            container.contains("executionBody = com.muyuchat.mca.chatExecutionBodyFor(engine)")
        )
        assertTrue(
            "the production image gate must be the real supervisor-backed owner",
            container.contains("ImageExecutionOwner(sharedRuntimeSupervisor)")
        )
        assertTrue(
            "the production worker client must use the process image gate",
            container.contains("LocalImageWorkerClient(application, imageExecutionGate)")
        )
        assertTrue(
            "the image-generation owner must have one container construction",
            imageParts.contains("): ImageGenerationOwner = ImageGenerationOwner(")
        )
        assertTrue(
            "the ViewModel must initialize the owner through the composition runtime assembly",
            mainViewModel.contains("runtimeAssembly.initializeImageGenerationOwner(")
        )
        assertTrue(
            "the runtime assembly must use the container-created image-generation owner",
            mainRuntimeAssembly.contains("container.createImageGenerationOwner(")
        )

        val realOwners = mapOf(
            "ChatExecutionFacade" to (CHAT_EXECUTION_FACADE to MCA_APP_CONTAINER),
            "ImageExecutionOwner" to (IMAGE_EXECUTION_GATE to MCA_APP_CONTAINER),
            "ImageGenerationOwner" to (IMAGE_GENERATION_OWNER to MCA_APP_CONTAINER_IMAGE_PARTS)
        )
        for ((type, locations) in realOwners) {
            val (declarationFile, expectedConstructionFile) = locations
            assertEquals(
                "$type must have exactly one production construction site",
                mapOf(expectedConstructionFile to 1),
                constructionSites(sources, type, declarationFile)
            )
        }

        val compositionSources = sources.filterKeys { it.startsWith("$PACKAGE_ROOT/composition/") }
        assertEquals(
            "the composition root must create exactly one shared runtime supervisor",
            mapOf(MCA_APP_CONTAINER to 1),
            constructionSites(compositionSources, "RuntimeSupervisor")
        )

        val removedOwners = listOf(
            "ImageGenerationFacade",
            "ModelLifecycleFacade",
            "RuntimeFacade",
            "LocalApiFacadeAdapter"
        )
        val allSource = sources.values.joinToString("\n")
        for (removed in removedOwners) {
            assertFalse(
                "$removed must not remain as a production declaration",
                Regex("""\b(?:class|interface|object)\s+${Regex.escape(removed)}\b""")
                    .containsMatchIn(allSource)
            )
            assertTrue(
                "$removed must not have a production construction site",
                constructionSites(sources, removed).isEmpty()
            )
        }
    }

    private fun constructionSites(
        sources: Map<String, String>,
        type: String,
        declarationFile: String? = null
    ): Map<String, Int> {
        val constructor = Regex("""\b${Regex.escape(type)}\s*\(""")
        return sources
            .filterKeys { it != declarationFile }
            .mapValues { (_, source) -> constructor.findAll(source).count() }
            .filterValues { it > 0 }
    }

    private fun productionKotlinSources(): Map<String, String> {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val sourceRoot = File(directory, "app/src/main/java")
            if (sourceRoot.isDirectory) {
                return sourceRoot.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .associate { file ->
                        file.relativeTo(sourceRoot).invariantSeparatorsPath to file.readText(Charsets.UTF_8)
                    }
            }
            directory = directory.parentFile
                ?: error("app/src/main/java not found from ${System.getProperty("user.dir")}")
        }
        error("app/src/main/java not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PACKAGE_ROOT = "com/muyuchat/mca"
        const val CHAT_EXECUTION_FACADE = "$PACKAGE_ROOT/facade/ChatExecutionFacade.kt"
        const val IMAGE_EXECUTION_GATE = "$PACKAGE_ROOT/facade/ImageExecutionGate.kt"
        const val IMAGE_GENERATION_OWNER = "$PACKAGE_ROOT/ImageGenerationOwner.kt"
        const val MCA_APP_CONTAINER = "$PACKAGE_ROOT/composition/McaAppContainer.kt"
        const val MCA_APP_CONTAINER_IMAGE_PARTS = "$PACKAGE_ROOT/composition/McaAppContainerImageParts.kt"
        const val MAIN_VIEW_MODEL = "$PACKAGE_ROOT/MainViewModel.kt"
        const val MAIN_RUNTIME_ASSEMBLY = "$PACKAGE_ROOT/composition/MainRuntimeAssembly.kt"
    }
}

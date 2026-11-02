package com.muyuchat.mca.agent.composition

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level evidence for the production memory wiring.
 *
 * These are static contract checks, not runtime proof. They pin the three things the JVM behavior
 * tests cannot instantiate (Room task creation, the app data assembly, the app runtime assembly):
 * one shared memory store/service, a selection freeze inside the task-creation transaction, and the
 * production frozen-command/catalog seams. Runtime execution is reported separately as NOT_RUN
 * when no build/test runner is available.
 */
class AgentMemoryProductionWiringSourceContractTest {
    @Test
    fun `task creation freezes memory through the service before reporting created`() {
        val repository = source("app/src/main/java/com/muyuchat/mca/agent/data/AgentTaskRepository.kt")
        assertTrue(repository.contains("memorySelectionService.freezeForTask"))
        assertTrue(repository.contains("AgentMemorySelectionOutcome.Rejected"))
        assertTrue(repository.contains("memorySpaceIds = frozenSelection?.spaceIds?.toSet().orEmpty()"))

        val freezeIndex = repository.lastIndexOf("freezeMemorySelection(taskId, requestedMemorySpaceIds)")
        val createdIndex = repository.indexOf("AgentTaskCreationOutcome.Created(task = task, idempotentReplay = false)")
        assertTrue("the execute branch must freeze selection", freezeIndex >= 0)
        assertTrue("the task must be reported created after the freeze", createdIndex > freezeIndex)
    }

    @Test
    fun `one memory persistence store and selection service are constructed by the data assembly`() {
        val assembly = source("app/src/main/java/com/muyuchat/mca/agent/composition/AgentDataAssembly.kt")
        assertEquals(1, countOccurrences(assembly, "RoomAgentMemoryPersistence("))
        assertEquals(1, countOccurrences(assembly, "AgentMemoryStore("))
        assertEquals(1, countOccurrences(assembly, "AgentMemorySelectionService("))
        assertTrue(assembly.contains("memorySelectionService = memorySelectionService"))
    }

    @Test
    fun `the runtime assembly injects the memory normalizer and catalog provider`() {
        val assembly = source("app/src/main/java/com/muyuchat/mca/composition/AgentHostAssembly.kt")
        assertTrue(assembly.contains("AgentFrozenCommandFactory("))
        assertTrue(assembly.contains("normalizer = AgentToolNormalizerRoutes.production()"))
        val routes = source("app/src/main/java/com/muyuchat/mca/agent/tools/AgentToolNormalizerRoutes.kt")
        assertTrue(routes.contains("AgentDispatchToolArgumentNormalizer("))
        assertTrue(routes.contains("AgentMemoryToolArgumentNormalizer()"))
        assertTrue(assembly.contains("AgentMemoryToolCatalogProvider("))
        assertTrue(assembly.contains("store = data.memoryStore"))
        assertTrue(assembly.contains("selections = data.memorySelectionService"))
        assertTrue(assembly.contains("internal val agentMemoryPersistence: RoomAgentMemoryPersistence"))
        assertTrue(assembly.contains("internal val agentMemoryStore: AgentMemoryStore"))
        assertTrue(assembly.contains("internal val agentMemorySelectionService: AgentMemorySelectionService"))
    }

    @Test
    fun `the memory package stays local-only and has no network or android dependency`() {
        val files = memorySources()
        assertTrue(files.size >= 8)
        files.forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            assertFalse("${file.name} must not open a network client", text.contains("java.net"))
            assertFalse("${file.name} must not use OkHttp", text.contains("okhttp"))
            assertFalse("${file.name} must not use Android types", text.contains("android."))
        }
        val toolSet = files.first { it.name == "AgentMemoryToolSet.kt" }.readText(Charsets.UTF_8)
        assertTrue(toolSet.contains("if (networkPolicy != AgentNetworkPolicy.LOCAL_ONLY) return emptyList()"))
    }

    private fun memorySources(): List<File> {
        val directory = File(projectRoot(), "app/src/main/java/com/muyuchat/mca/agent/memory")
        require(directory.isDirectory) { "missing memory source directory: ${directory.absolutePath}" }
        return directory.listFiles()
            ?.filter { it.isFile && it.extension == "kt" }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    private fun source(relativePath: String): String =
        File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun countOccurrences(text: String, token: String): Int {
        var count = 0
        var index = text.indexOf(token)
        while (index >= 0) {
            count++
            index = text.indexOf(token, index + token.length)
        }
        return count
    }

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            if (File(root, "app/src/main/java/com/muyuchat/mca").isDirectory) return root
            directory = root.parentFile
        }
        error("Repository root not found from ${System.getProperty("user.dir")}")
    }
    @Test
    fun `the permission hook preserves a typed frozen scope`() {
        val hook = source("app/src/main/java/com/muyuchat/mca/agent/composition/AgentToolPermissionHook.kt")
        assertTrue(
            "typedPreconditionJson must be copied into AgentToolScope; dropping it rejects every " +
                "device/memory tool with INTERNAL_UNEXPECTED before approval",
            hook.contains("typedPreconditionJson = frozen.scope.typedPreconditionJson")
        )
    }

}

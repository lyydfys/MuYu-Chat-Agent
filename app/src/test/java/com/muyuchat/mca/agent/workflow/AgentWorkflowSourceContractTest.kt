package com.muyuchat.mca.agent.workflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level evidence for the workflow slice.
 *
 * These are static contract checks, not runtime proof: they verify that the new package cannot
 * hide a second executor/loop, an Android scheduler/broadcast surface, a network client or an
 * executable payload in a task recipe. Runtime/JVM behavior is covered by the other workflow tests.
 */
class AgentWorkflowSourceContractTest {

    private val packageDirectory = "app/src/main/java/com/muyuchat/mca/agent/workflow"

    @Test
    fun workflowPackageHasNoSecondExecutorOrLoop() {
        val forbidden = listOf(
            "AgentToolExecutor",
            "executeTool",
            "AgentPromptedLoopCoordinator",
            "AgentRunOwner",
            "AgentToolCatalog",
            "AgentModelPort",
            "ExecuteNode",
            "TriggerNode",
            "ScriptEngine",
            "QuickJS",
            "DexClassLoader",
            "System.loadLibrary",
            "ProcessBuilder",
            "Runtime.getRuntime"
        )
        workflowSources().forEach { file ->
            val text = codeOnly(file.readText(Charsets.UTF_8))
            // The MODEL node must use the injected contract port. It may not own an adapter, loop,
            // engine or executor; the dedicated test below asserts that narrower rule. The port type
            // is allowed only in the files that own the injection (the run loop) and the MODEL node.
            val portOwners = setOf("WorkflowExecutionService.kt", "WorkflowExternalNodeAdapters.kt")
            val forbiddenForFile = if (file.name in portOwners) {
                forbidden - "AgentModelPort"
            } else {
                forbidden
            }
            forbiddenForFile.forEach { token ->
                assertFalse("${file.name} must not contain $token", text.contains(token))
            }
        }
    }

    @Test
    fun modelNodeUsesOnlyTheInjectedModelPort() {
        // The MODEL node moved to the adapters file that owns node-type work; the rule is unchanged:
        // it may read the injected port and nothing else, and the run loop must inject that port.
        val code = codeOnly(sourceFile("WorkflowExternalNodeAdapters.kt"))
        assertTrue("the MODEL node owner must take the narrow model contract", code.contains("AgentModelPort"))
        assertTrue("MODEL nodes must read the injected port", code.contains("val port = modelPort"))
        assertTrue("MODEL nodes must call the injected port", code.contains("port.infer("))
        val service = codeOnly(sourceFile("WorkflowExecutionService.kt"))
        assertTrue(
            "the run loop must inject the port into the adapters",
            service.contains("WorkflowExternalNodeAdapters(modelPort, agentFrameExecutor, agentTaskRunner, codec)")
        )
        listOf(
            "AgentPromptedLoopCoordinator",
            "AgentCloudModelAdapter",
            "McaAgentLocalModelAdapter",
            "AgentToolExecutor",
            "McaInferenceService",
            "EngineLifecycleLease",
            "AgentRunOwner"
        ).forEach { token ->
            assertFalse("the MODEL node owner must not own $token", code.contains(token))
            assertFalse("the workflow service must not own $token", service.contains(token))
        }
    }

    @Test
    fun workflowPackageHasNoAndroidSchedulingOrBroadcastSurface() {
        val forbidden = listOf(
            "android.",
            "WorkManager",
            "WorkerFactory",
            "BroadcastReceiver",
            "AlarmManager",
            "JobScheduler",
            "BOOT_COMPLETED",
            "PendingIntent",
            "startForegroundService",
            "java.net",
            "okhttp",
            "java.net.http"
        )
        workflowSources().forEach { file ->
            val text = codeOnly(file.readText(Charsets.UTF_8))
            forbidden.forEach { token ->
                assertFalse("${file.name} must not contain $token", text.contains(token))
            }
        }
    }

    @Test
    fun onlyTheTaskGatewayDelegatesToTheExistingOwnerCommands() {
        val sources = workflowSources().associateBy { it.name }
        val launcher = sources.getValue("AgentWorkflowLauncher.kt").readText(Charsets.UTF_8)
        val gateway = launcher.substringAfter("class AgentWorkspaceCommandTaskGateway")
        assertEquals(1, countOccurrences(gateway, "commands.createTask"))
        assertEquals(1, countOccurrences(gateway, "commands.startRun"))

        sources.filterKeys { it != "AgentWorkflowLauncher.kt" }.forEach { (name, file) ->
            val text = file.readText(Charsets.UTF_8)
            assertFalse("$name must not call createTask directly", text.contains("commands.createTask"))
            assertFalse("$name must not call startRun directly", text.contains("commands.startRun"))
            assertFalse("$name must not construct a run", text.contains("AgentRunSpec("))
        }
    }

    @Test
    fun launcherUsesOnlyTheGatewayAndHasNoFileOrNetworkImports() {
        val launcher = sourceFile("AgentWorkflowLauncher.kt")
        val code = codeOnly(launcher)
        assertTrue(launcher.contains("AgentWorkflowTaskGateway"))
        assertTrue(launcher.contains("AgentWorkflowLaunchOutcome"))
        assertFalse(code.contains("java.io"))
        assertFalse(code.contains("java.nio.file"))
        assertFalse(code.contains("AgentToolExecutor"))
        assertFalse(code.contains("executeTool"))
        assertFalse(code.contains("WorkManager"))
    }

    @Test
    fun workflowDomainDoesNotImportFeatureUiOrPresentationAdapters() {
        workflowSources().forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            assertFalse(
                "${file.name} must stay below the feature/presentation boundary",
                text.contains("import com.muyuchat.feature.")
            )
            assertFalse(
                "${file.name} must not depend on the app presentation adapter",
                text.contains("import com.muyuchat.mca.presentation.")
            )
        }
    }

    @Test
    fun parserAndPreflightArePlatformFreeAndHaveNoIo() {
        listOf("AgentWorkflowRecipeParser.kt", "AgentWorkflowPreflight.kt", "AgentWorkflowRecipe.kt")
            .forEach { name ->
                val text = sourceFile(name)
                assertFalse("$name must not import java.io", text.contains("java.io"))
                assertFalse("$name must not import android", text.contains("android."))
                assertFalse("$name must not import java.net", text.contains("java.net"))
            }
    }

    @Test
    fun parserRejectsUnknownAndExecutableFields() {
        val parser = sourceFile("AgentWorkflowRecipeParser.kt")
        assertTrue(parser.contains("requireOnlyKeys"))
        assertTrue(parser.contains("unsupported field"))
        listOf("\"script\"", "\"main\"", "\"exec\"", "\"wasm\"", "\"dex\"").forEach { field ->
            assertFalse("parser must not accept an executable field: $field", parser.contains(field))
        }
    }

    @Test
    fun storeDocumentsStrictParseAtomicWriteAndFailClosed() {
        val store = sourceFile("FileAgentWorkflowRecipeStore.kt")
        assertTrue(store.contains("ATOMIC_MOVE"))
        assertTrue(store.contains("MAX_STORE_BYTES"))
        assertTrue(store.contains("STORE_CORRUPT"))
        assertTrue(store.contains("requireStoreKeys"))
        assertTrue(store.contains("output.fd.sync()"))
    }

    @Test
    fun adminServiceOnlyLaunchesThroughTheLauncher() {
        val service = File(
            projectRoot(),
            "app/src/main/java/com/muyuchat/mca/presentation/AgentWorkflowAdminService.kt"
        ).readText(Charsets.UTF_8)
        assertTrue(service.contains("launcher.launch("))
        assertFalse(service.contains("commands.createTask"))
        assertFalse(service.contains("commands.startRun"))
        assertFalse(service.contains("AgentToolExecutor"))
        assertFalse(service.contains("WorkManager"))
    }

    @Test
    fun recipeFormatCarriesTheRequiredVersionedPolicyFields() {
        val recipe = sourceFile("AgentWorkflowRecipe.kt")
        listOf(
            "val id: String",
            "val version: Long",
            "val title: String",
            "val goalTemplate: String",
            "val inputs: List<AgentWorkflowInputSource>",
            "val modelPolicy: AgentWorkflowModelPolicy",
            "val approvalPolicy: AgentWorkflowApprovalPolicy",
            "val enabledToolKeys: List<String>",
            "val steps: List<AgentWorkflowStep>"
        ).forEach { field ->
            assertTrue("recipe must carry $field", recipe.contains(field))
        }
    }

    private fun codeOnly(text: String): String {
        val withoutBlocks = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        return withoutBlocks.lines().joinToString("\n") { line -> line.substringBefore("//") }
    }

    private fun countOccurrences(text: String, token: String): Int {
        var count = 0
        var index = text.indexOf(token)
        while (index >= 0) {
            count++
            index = text.indexOf(token, index + token.length)
        }
        return count
    }

    private fun sourceFile(name: String): String =
        File(projectRoot(), "$packageDirectory/$name").readText(Charsets.UTF_8)

    private fun workflowSources(): List<File> {
        val directory = File(projectRoot(), packageDirectory)
        require(directory.isDirectory) { "missing workflow source directory: ${directory.absolutePath}" }
        return directory.listFiles()
            ?.filter { it.isFile && it.extension == "kt" }
            ?.sortedBy { it.name }
            .orEmpty()
            .also { require(it.size >= 7) { "workflow package is unexpectedly small" } }
    }

    private fun projectRoot(): File {
        var directory: File? =
            File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            if (File(root, "app/src/main/java/com/muyuchat/mca").isDirectory) return root
            directory = root.parentFile
        }
        error("Repository root not found from ${System.getProperty("user.dir")}")
    }
}

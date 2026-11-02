package com.muyuchat.mca.agent.platform

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0008 ownership guard.
 *
 * Static source evidence, not runtime lifecycle proof: the VM must not construct/close the shared
 * Agent assembly, the Application must own the one process root, and the root is the only place
 * that settles the Agent host and the container-owned worker pools. Device lifecycle evidence
 * remains a separate NOT_RUN acceptance item.
 */
class AgentProcessRuntimeRootSourceContractTest {

    private val mainViewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
    private val application = sourceFile("app/src/main/java/com/muyuchat/mca/McaApplication.kt")
    private val owner = sourceFile("app/src/main/java/com/muyuchat/mca/composition/McaProcessRuntimeOwner.kt")
    private val root = sourceFile("app/src/main/java/com/muyuchat/mca/composition/McaProcessRuntimeRoot.kt")
    private val startup = sourceFile("app/src/main/java/com/muyuchat/mca/AppStartupViewModel.kt")

    @Test
    fun theViewModelNeverConstructsASecondRuntimeAssembly() {
        val code = codeOnly(mainViewModel.readText(Charsets.UTF_8))

        assertFalse("MainViewModel must not construct MainRuntimeAssembly", code.contains("MainRuntimeAssembly("))
        assertTrue(
            "the VM must consume the process root's assembly",
            code.contains("startupContext.runtimeRoot.assembly")
        )
    }

    @Test
    fun theViewModelDoesNotSettleTheProcessOwnedAgentHost() {
        val code = codeOnly(mainViewModel.readText(Charsets.UTF_8))

        assertFalse("onCleared must not close the process-owned Agent host", code.contains("agentHost.close()"))
        assertTrue(
            "the Agent root settles on the last Activity finish, not page teardown",
            codeOnly(application.readText(Charsets.UTF_8)).contains("closeProcessRuntimeRoot()")
        )
    }

    @Test
    fun theApplicationOwnsOneRootAndClosesItOnlyOnExplicitExit() {
        val code = codeOnly(application.readText(Charsets.UTF_8))

        assertTrue(code.contains("McaProcessRuntimeOwner"))
        assertTrue(code.contains("closeProcessRuntimeRoot()"))
        assertTrue(code.contains("ProcessActivityTracker"))
        assertTrue(code.contains("shouldCloseOnDestroyed("))
        assertTrue(code.contains("onTerminate()"))
        assertFalse("the Application must not reach into the container type", code.contains("McaAppContainer"))
    }

    @Test
    fun theCompositionOwnerHoldsTheOnlyContainerAndRoot() {
        val code = codeOnly(owner.readText(Charsets.UTF_8))

        listOf(
            "McaAppContainer(",
            "McaProcessRuntimeRoot(",
            "fun container()",
            "fun root(installationScopeId: String)",
            "root?.takeIf { !it.isClosed }",
            "fun close()"
        ).forEach { token ->
            assertTrue("McaProcessRuntimeOwner must contain $token", code.contains(token))
        }
    }

    @Test
    fun theRootIsTheSingleSettlementPoint() {
        val code = codeOnly(root.readText(Charsets.UTF_8))

        listOf(
            "assembly.close()",
            "container.localApiRuntimeOwner.release()",
            "LocalApiForegroundService.stop(application)",
            "container.localImageWorkerClient.close()",
            "container.isolatedLocalChatRunners.close()",
            "scope.cancel()"
        ).forEach { token ->
            assertTrue("McaProcessRuntimeRoot must contain $token", code.contains(token))
        }
    }

    @Test
    fun theAssemblySettlesEveryProcessOwnedHost() {
        val assembly = codeOnly(
            sourceFile("app/src/main/java/com/muyuchat/mca/composition/MainRuntimeAssembly.kt")
                .readText(Charsets.UTF_8)
        )

        assertTrue(
            "the assembly must expose one settlement entry point",
            assembly.contains("fun close()")
        )
        assertTrue(
            "DSH must be settled by the process root, including its app-local servers",
            assembly.contains("dshHost.closeAndStopBlocking()")
        )
        assertTrue(
            "the Agent host must be settled by the assembly",
            assembly.contains("agentHost.close()")
        )
        val dshHost = codeOnly(
            sourceFile("app/src/main/java/com/muyuchat/mca/composition/DshHostAssembly.kt")
                .readText(Charsets.UTF_8)
        )
        assertTrue(
            "a created capability bridge must be stopped",
            dshHost.contains("capabilityAssembly.stop()")
        )
        assertTrue("a created gateway must be stopped", dshHost.contains("gatewayAssembly.stop()"))
        assertFalse(
            "callers must settle the assembly, not one host inside it",
            codeOnly(root.readText(Charsets.UTF_8)).contains("agentHost.close()")
        )
    }

    @Test
    fun startupObtainsTheSharedContainerAndRootInsteadOfCreatingThem() {
        val code = codeOnly(startup.readText(Charsets.UTF_8))

        assertTrue(code.contains("app.processRuntimeOwner.container()"))
        assertTrue(code.contains("app.processRuntimeOwner.root("))
        assertFalse("startup must not construct a standalone container", code.contains("McaAppContainer(app)"))
    }

    private fun sourceFile(relativePath: String): File =
        File(projectRoot(), relativePath)

    private fun projectRoot(): File {
        var directory: File? =
            File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val current = directory ?: return@repeat
            if (File(current, "app/src/main/java/com/muyuchat/mca").isDirectory) return current
            directory = current.parentFile
        }
        error("Repository root not found from ${System.getProperty("user.dir")}")
    }

    private fun codeOnly(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//.*"), "")
}

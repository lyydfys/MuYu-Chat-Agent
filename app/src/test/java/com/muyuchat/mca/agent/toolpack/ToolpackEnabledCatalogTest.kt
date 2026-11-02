package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Production catalog data source (`enabledToolPackageExecutors` / `augmentedWithPackageTools`).
 *
 * JVM only: real store + real Rhino engine through a recording host handler; no device/UI.
 */
class ToolpackEnabledCatalogTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val handler = RecordingHostHandler()

    private fun service(root: File): ToolPackageLifecycleService = ToolPackageLifecycleService(
        store = ToolPackageInstallStore(root),
        runtime = ToolPackageScriptRuntime(RhinoToolPackageScriptEngine(), handler)
    )

    private fun manifest(packageVersion: String = "1.0.0"): String = ToolpackFixtures.manifestJson(
        packageVersion = packageVersion,
        hostApis = emptyList(),
        tools = listOf(ToolpackFixtures.toolJson())
    )

    private fun scripts() = ToolpackFixtures.scripts(
        ToolpackFixtures.TOOL_ENTRY to "function main(input) { return { path: input.path }; }"
    )

    private fun installAndEnable(
        service: ToolPackageLifecycleService,
        root: File
    ): String = runBlocking {
        val installed = service.install(manifest().toByteArray(), scripts())
        assertTrue(installed is ToolPackageLifecycleOutcome.Applied)
        val record = ToolPackageInstallStore(root).scan().records.single()
        assertTrue(service.enable(record.packageId) is ToolPackageLifecycleOutcome.Applied)
        record.packageId
    }

    @Test
    fun `only enabled and verified packages contribute executors`() = runBlocking {
        val root = temporary.newFolder("enabled-only")
        val service = service(root)
        val installed = service.install(manifest().toByteArray(), scripts())
        val record = (installed as ToolPackageLifecycleOutcome.Applied).status!!

        assertTrue(service.enabledExecutors().isEmpty())

        service.enable(record.packageId)
        val executors = service.enabledExecutors()
        assertEquals(1, executors.size)
        assertEquals("pkg.research_basics.read_brief", executors.single().descriptor.toolKey)

        service.disable(record.packageId)
        assertTrue(service.enabledExecutors().isEmpty())
    }

    @Test
    fun `revoked package contributes no executor even if the state was hand-edited`() = runBlocking {
        val root = temporary.newFolder("revoked")
        val service = service(root)
        val packageId = installAndEnable(service, root)

        service.revoke(packageId)
        assertTrue(service.enabledExecutors().isEmpty())
        assertEquals(0, enabledToolPackageCatalog(ToolPackageInstallStore(root)).executors.size)
    }

    @Test
    fun `augmented catalog sees later package changes without rebuilding the base`() = runBlocking {
        val root = temporary.newFolder("augment")
        val service = service(root)
        var packageExecutors: List<AgentToolExecutor> = emptyList()
        val base = SyntheticCatalog(listOf(fakeExecutor("workspace.list")))
        val augmented = base.augmentedWithPackageTools { packageExecutors }

        assertEquals(listOf("workspace.list"), augmented.snapshot().map { it.toolKey })
        assertNull(augmented.find("pkg.research_basics.read_brief"))

        val installed = (service.install(manifest().toByteArray(), scripts()) as ToolPackageLifecycleOutcome.Applied)
        service.enable(installed.status!!.packageId)
        packageExecutors = service.enabledExecutors()

        assertEquals(
            listOf("pkg.research_basics.read_brief", "workspace.list"),
            augmented.snapshot().map { it.toolKey }
        )
        assertNotNull(augmented.find("pkg.research_basics.read_brief"))
        assertNotNull(augmented.find("workspace.list"))

        packageExecutors = emptyList()
        assertEquals(listOf("workspace.list"), augmented.snapshot().map { it.toolKey })
    }

    @Test
    fun `built-in keys always win over a package descriptor with the same key`() {
        val base = SyntheticCatalog(listOf(fakeExecutor("workspace.list")))
        val shadow = fakeExecutor("workspace.list")
        val augmented = base.augmentedWithPackageTools { listOf(shadow) }

        val snapshot = augmented.snapshot()
        assertEquals(listOf("workspace.list"), snapshot.map { it.toolKey })
        assertTrue(augmented.find("workspace.list") !== shadow)
    }

    @Test
    fun `default runtime denies every host call`() = runBlocking {
        val denial = denyToolPackageHostHandler().handle(
            ToolPackageHostOperationRequest(
                api = ToolPackageHostApis.WORKSPACE_READ_TEXT_API,
                argumentsJson = """{"path":"notes.txt"}""",
                instanceId = "tpi-test",
                packageId = "research-basics",
                packageContentHash = "0".repeat(64),
                scope = ToolpackFixtures.scope(),
                resource = ToolPackageHostResource.Workspace(
                    workspaceId = "workspace-1",
                    relativePath = "notes.txt",
                    access = ToolPackageHostResourceAccess.READ,
                    capability = ToolPackageHostApis.WORKSPACE_READ_TEXT
                )
            )
        )

        assertTrue(denial is ToolPackageHostOperationResult.Denied)
        assertEquals(
            ToolPackageErrorCode.HOST_CALL_DENIED,
            (denial as ToolPackageHostOperationResult.Denied).error.code
        )
    }

    private fun fakeExecutor(toolKey: String): AgentToolExecutor = object : AgentToolExecutor {
        override val descriptor: AgentToolDescriptor = AgentToolDescriptor(
            toolKey = toolKey,
            version = "1",
            displayName = toolKey,
            description = "Synthetic executor for catalog tests.",
            schemaJson = EMPTY_SCHEMA,
            schemaHash = "0".repeat(64),
            sideEffect = com.muyuchat.core.agent.contract.AgentToolSideEffect.READ_ONLY,
            dataClass = com.muyuchat.core.agent.contract.AgentDataClass.PRIVATE
        )

        override suspend fun execute(
            command: com.muyuchat.core.agent.contract.FrozenToolCommand,
            context: com.muyuchat.core.agent.contract.AgentToolContext
        ): com.muyuchat.core.agent.contract.AgentToolResult =
            com.muyuchat.core.agent.contract.AgentToolResult(
                status = com.muyuchat.core.agent.contract.AgentToolResultStatus.FAILED,
                summary = "Synthetic executor is never executed by catalog tests."
            )
    }

    private companion object {
        const val EMPTY_SCHEMA = """{"additionalProperties":false,"properties":{},"type":"object"}"""
    }

    private class SyntheticCatalog(
        private val executors: List<AgentToolExecutor>
    ) : AgentToolCatalog {
        override fun find(toolKey: String): AgentToolExecutor? =
            executors.firstOrNull { it.descriptor.toolKey == toolKey }

        override fun snapshot(): List<AgentToolDescriptor> =
            executors.map { it.descriptor }.sortedBy { it.toolKey }
    }
}

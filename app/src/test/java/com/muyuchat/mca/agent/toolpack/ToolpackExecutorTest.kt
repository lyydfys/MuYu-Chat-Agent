package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolpackExecutorTest {

    private val readEngine = FakeScriptEngine { request ->
        request.host.call(ToolPackageHostApis.WORKSPACE_READ_TEXT, """{"path":"notes.txt"}""")
        ToolPackageEngineResult.Returned("""{"content":"hello from workspace"}""")
    }

    private val readHandler = RecordingHostHandler(
        ToolPackageHostOperationResult.Returned("""{"content":"hello from workspace"}""")
    )

    @Test
    fun `projects one read-only package tool into the frozen catalog surface`() {
        val bundle = ToolpackFixtures.bundle()
        val catalog = ToolPackageToolCatalog.project(
            bundle,
            ToolPackageScriptRuntime(readEngine, readHandler)
        )

        val descriptor = catalog.snapshot().single()
        assertEquals("pkg.research_basics.read_brief", descriptor.toolKey)
        assertEquals(bundle.versionIdentity, descriptor.version)
        assertEquals(AgentToolSideEffect.READ_ONLY, descriptor.sideEffect)
        assertEquals(AgentDataClass.PRIVATE, descriptor.dataClass)
        assertEquals(10_000L, descriptor.timeoutMillis)
        assertEquals(
            com.muyuchat.mca.agent.tools.AgentToolJson.sha256Hex(bundle.manifest.tools.single().inputSchemaJson),
            descriptor.schemaHash
        )
        assertNotNull(catalog.find(descriptor.toolKey))
        assertEquals(null, catalog.find("pkg.research_basics.unknown"))
    }

    @Test
    fun `executes a read-only package tool and returns its typed result`() = runTest {
        val bundle = ToolpackFixtures.bundle()
        val catalog = ToolPackageToolCatalog.project(
            bundle,
            ToolPackageScriptRuntime(readEngine, readHandler)
        )
        val executor = requireNotNull(catalog.find("pkg.research_basics.read_brief"))

        val result = executor.execute(
            ToolpackFixtures.command(executor.descriptor.toolKey, executor.descriptor.version, executor.descriptor.schemaHash),
            ToolpackFixtures.context(workspaceReadGranted = true)
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertFalse(result.possibleSideEffects)
        val document = JSONObject(requireNotNull(result.dataJson))
        assertTrue(document.getBoolean("ok"))
        assertEquals("hello from workspace", document.getJSONObject("data").getString("content"))
        assertEquals(1, readHandler.requests.size)
    }

    @Test
    fun `workspace-capable packages require the task workspace read grant`() = runTest {
        val bundle = ToolpackFixtures.bundle()
        val catalog = ToolPackageToolCatalog.project(
            bundle,
            ToolPackageScriptRuntime(readEngine, readHandler)
        )
        val executor = requireNotNull(catalog.find("pkg.research_basics.read_brief"))

        val result = executor.execute(
            ToolpackFixtures.command(executor.descriptor.toolKey, executor.descriptor.version, executor.descriptor.schemaHash),
            ToolpackFixtures.context(workspaceReadGranted = false)
        )

        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals("PERMISSION_DENIED", result.errorCode)
        assertEquals(0, readEngine.invocations)
        assertTrue(readHandler.requests.isEmpty())
    }

    @Test
    fun `a memory-only package executes without a workspace grant`() = runTest {
        val engine = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.MEMORY_SEARCH, """{"query":"brief"}""")
            ToolPackageEngineResult.Returned("""{"matches":[]}""")
        }
        val handler = RecordingHostHandler(ToolPackageHostOperationResult.Returned("""{"matches":[]}"""))
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(
                hostApis = listOf(ToolPackageHostApis.MEMORY_SEARCH),
                tools = listOf(
                    ToolpackFixtures.toolJson(
                        name = "search_memory",
                        inputSchema = ToolpackFixtures.ZERO_ARGUMENT_SCHEMA
                    )
                )
            )
        )
        val catalog = ToolPackageToolCatalog.project(bundle, ToolPackageScriptRuntime(engine, handler))
        val executor = requireNotNull(catalog.find("pkg.research_basics.search_memory"))

        val result = executor.execute(
            ToolpackFixtures.command(
                executor.descriptor.toolKey,
                executor.descriptor.version,
                executor.descriptor.schemaHash,
                argumentsJson = "{}"
            ),
            ToolpackFixtures.context(workspaceReadGranted = false)
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertEquals(1, handler.requests.size)
        assertEquals(ToolPackageHostApis.MEMORY_SEARCH, handler.requests.single().api.method)
    }

    @Test
    fun `a write-capable package is refused by the projection`() {
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(hostApis = listOf(ToolPackageHostApis.ARTIFACT_WRITE_TEXT))
        )
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.WRITE_CAPABILITY_UNSUPPORTED) {
            ToolPackageToolCatalog.project(bundle, ToolPackageScriptRuntime(readEngine, readHandler))
        }
    }

    @Test
    fun `a command with a stale version or schema is not executed`() = runTest {
        val bundle = ToolpackFixtures.bundle()
        val catalog = ToolPackageToolCatalog.project(
            bundle,
            ToolPackageScriptRuntime(readEngine, readHandler)
        )
        val executor = requireNotNull(catalog.find("pkg.research_basics.read_brief"))
        val command = ToolpackFixtures.command(
            executor.descriptor.toolKey,
            executor.descriptor.version,
            "stale-schema-hash"
        )

        val result = executor.execute(command, ToolpackFixtures.context())

        assertEquals(AgentToolResultStatus.NOT_EXECUTED, result.status)
        assertEquals("TOOL_INPUT_INVALID", result.errorCode)
        assertEquals(0, readEngine.invocations)
    }

    @Test
    fun `a package id that cannot form a descriptor key is refused`() {
        val bundle = ToolpackFixtures.bundle(ToolpackFixtures.manifestJson(packageId = "1pkg"))
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.TOOL_KEY_UNSUPPORTED) {
            ToolPackageToolCatalog.project(bundle, ToolPackageScriptRuntime(readEngine, readHandler))
        }
    }

    @Test
    fun `two tools project to a deterministic sorted catalog`() {
        val tools = listOf(
            ToolpackFixtures.toolJson(name = "read_brief", entry = "tools/read_brief.js"),
            ToolpackFixtures.toolJson(name = "read_other", entry = "tools/read_other.js")
        )
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(tools = tools),
            ToolpackFixtures.scripts("tools/read_other.js" to "// other\n")
        )

        val catalog = ToolPackageToolCatalog.project(
            bundle,
            ToolPackageScriptRuntime(readEngine, readHandler)
        )

        assertEquals(
            listOf("pkg.research_basics.read_brief", "pkg.research_basics.read_other"),
            catalog.snapshot().map { it.toolKey }
        )
    }

    @Test
    fun `an engine failure is mapped to a typed tool failure`() = runTest {
        val engine = FakeScriptEngine { ToolPackageEngineResult.Failed("the script failed safely") }
        val bundle = ToolpackFixtures.bundle()
        val catalog = ToolPackageToolCatalog.project(
            bundle,
            ToolPackageScriptRuntime(engine, readHandler)
        )
        val executor = requireNotNull(catalog.find("pkg.research_basics.read_brief"))

        val result = executor.execute(
            ToolpackFixtures.command(executor.descriptor.toolKey, executor.descriptor.version, executor.descriptor.schemaHash),
            ToolpackFixtures.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_FAILED", result.errorCode)
        assertEquals("the script failed safely", result.summary)
    }
}

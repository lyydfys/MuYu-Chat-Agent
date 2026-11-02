package com.muyuchat.mca.agent.toolpack

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentListResult
import com.muyuchat.core.agent.contract.AgentReadResult
import com.muyuchat.core.agent.contract.AgentSearchResult
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import com.muyuchat.core.agent.contract.AgentWriteResult
import com.muyuchat.core.agent.contract.FrozenToolCommand
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device proof for the executable-tool-package chain: install -> enable -> catalog executor ->
 * real Rhino execution -> typed result.
 */
@RunWith(AndroidJUnit4::class)
class ToolpackDeviceExecutionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installEnableAndExecuteRealRhinoScriptOnDevice() = runBlocking {
        val root = File(context.filesDir, "toolpack-device-test-${System.nanoTime()}")
        val store = ToolPackageInstallStore(root)
        val service = ToolPackageLifecycleService(
            store = store,
            runtime = defaultToolPackageRuntime()
        )
        val manifest = JSONObject()
            .put("schemaVersion", ToolPackageLimits.FORMAT_VERSION)
            .put("packageId", "devicetest-echo")
            .put("packageVersion", "1.0.0")
            .put("hostApiVersion", ToolPackageLimits.HOST_API_VERSION)
            .put("displayName", "Device echo")
            .put("description", "Device-only pure script proof.")
            .put("entry", "index.js")
            .put("hostApis", JSONArray())
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("name", "echo_path")
                        .put("displayName", "Echo path")
                        .put("description", "Returns the supplied path.")
                        .put("entry", "tools/echo_path.js")
                        .put(
                            "inputSchema",
                            JSONObject()
                                .put("type", "object")
                                .put("properties", JSONObject().put("path", JSONObject().put("type", "string")))
                                .put("required", JSONArray().put("path"))
                                .put("additionalProperties", false)
                                .toString()
                        )
                        .put("timeoutMillis", 5_000)
                )
            )
            .toString()
        val scripts = mapOf(
            "index.js" to "// registration is not executed in this slice\n",
            "tools/echo_path.js" to "function main(input) { return { echoed: input.path }; }"
        )

        val installed = service.install(manifest.toByteArray(Charsets.UTF_8), scripts)
        assertTrue("install must succeed: $installed", installed is ToolPackageLifecycleOutcome.Applied)
        val packageId = (installed as ToolPackageLifecycleOutcome.Applied).status!!.packageId
        assertTrue(service.enable(packageId) is ToolPackageLifecycleOutcome.Applied)

        val executor = service.enabledExecutors().single()
        val command = FrozenToolCommand(
            invocationId = "inv-device-1",
            runId = "run-device-1",
            stepId = "step-device-1",
            callOrdinal = 0,
            toolKey = executor.descriptor.toolKey,
            toolVersion = executor.descriptor.version,
            schemaHash = executor.descriptor.schemaHash,
            normalizedArgumentsJson = """{"path":"notes.txt"}""",
            argumentsHash = "args-device",
            resourceFingerprint = "resource-device",
            runEpoch = 1L,
            policyVersion = 1L,
            expiresAtMillis = Long.MAX_VALUE
        )
        val toolContext = AgentToolContext(
            taskId = "task-device-1",
            workspaceId = "workspace-device-1",
            runId = "run-device-1",
            stepIndex = 0,
            runEpoch = 1L,
            policyVersion = 1L,
            workspaceReadGranted = false
        )
        val result = executor.execute(command, toolContext)
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertTrue("output must come from the real script: ${result.dataJson}", result.dataJson.orEmpty().contains("notes.txt"))

        service.uninstall(packageId)
        assertTrue(service.enabledExecutors().isEmpty())
    }

    @Test
    fun commonJsRequireRunsOnDevice() = runBlocking {
        val root = File(context.filesDir, "toolpack-device-cjs-${System.nanoTime()}")
        val service = ToolPackageLifecycleService(
            store = ToolPackageInstallStore(root),
            runtime = defaultToolPackageRuntime()
        )
        val manifest = JSONObject()
            .put("schemaVersion", ToolPackageLimits.FORMAT_VERSION)
            .put("packageId", "devicetest-cjs")
            .put("packageVersion", "1.0.0")
            .put("hostApiVersion", ToolPackageLimits.HOST_API_VERSION)
            .put("displayName", "Device CommonJS")
            .put("description", "Device-only CommonJS proof.")
            .put("entry", "index.js")
            .put("hostApis", JSONArray())
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("name", "double")
                        .put("displayName", "Double")
                        .put("description", "Doubles through a required module.")
                        .put("entry", "tools/double.js")
                        .put(
                            "inputSchema",
                            JSONObject()
                                .put("type", "object")
                                .put("properties", JSONObject().put("value", JSONObject().put("type", "number")))
                                .put("required", JSONArray().put("value"))
                                .put("additionalProperties", false)
                                .toString()
                        )
                        .put("timeoutMillis", 5_000)
                )
            )
            .toString()
        val scripts = mapOf(
            "index.js" to "// registration is not executed in this slice\n",
            "tools/double.js" to
                "var math = require('../lib/math.js');\n" +
                "function main(input) { return { value: math.double(input.value) }; }",
            "lib/math.js" to "exports.double = function (x) { return x * 2; };"
        )
        val installed = service.install(manifest.toByteArray(Charsets.UTF_8), scripts)
        assertTrue("install must succeed: $installed", installed is ToolPackageLifecycleOutcome.Applied)
        val packageId = (installed as ToolPackageLifecycleOutcome.Applied).status!!.packageId
        assertTrue(service.enable(packageId) is ToolPackageLifecycleOutcome.Applied)
        val executor = service.enabledExecutors().single()
        val command = FrozenToolCommand(
            invocationId = "inv-device-cjs",
            runId = "run-device-cjs",
            stepId = "step-device-cjs",
            callOrdinal = 0,
            toolKey = executor.descriptor.toolKey,
            toolVersion = executor.descriptor.version,
            schemaHash = executor.descriptor.schemaHash,
            normalizedArgumentsJson = """{"value":21}""",
            argumentsHash = "args-device-cjs",
            resourceFingerprint = "resource-device-cjs",
            runEpoch = 1L,
            policyVersion = 1L,
            expiresAtMillis = Long.MAX_VALUE
        )
        val toolContext = AgentToolContext(
            taskId = "task-device-cjs",
            workspaceId = "workspace-device-cjs",
            runId = "run-device-cjs",
            stepIndex = 0,
            runEpoch = 1L,
            policyVersion = 1L,
            workspaceReadGranted = false
        )
        val result = executor.execute(command, toolContext)
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertTrue(
            "required module result must reach the script: ${result.dataJson}",
            result.dataJson.orEmpty().contains("42")
        )
        service.uninstall(packageId)
        Unit
    }

    @Test
    fun hostCallReachesTheProductionWorkspaceHandlerOnDevice() = runBlocking {
        val root = File(context.filesDir, "toolpack-device-host-${System.nanoTime()}")
        val port = FakeWorkspacePort()
        val store = ToolPackageInstallStore(root)
        val service = ToolPackageLifecycleService(
            store = store,
            runtime = ToolPackageScriptRuntime(
                RhinoToolPackageScriptEngine(),
                WorkspaceToolPackageHostHandler(port)
            )
        )
        val manifest = JSONObject()
            .put("schemaVersion", ToolPackageLimits.FORMAT_VERSION)
            .put("packageId", "devicetest-host")
            .put("packageVersion", "1.0.0")
            .put("hostApiVersion", ToolPackageLimits.HOST_API_VERSION)
            .put("displayName", "Device host echo")
            .put("description", "Device-only host API proof.")
            .put("entry", "index.js")
            .put("hostApis", JSONArray().put(ToolPackageHostApis.WORKSPACE_READ_TEXT))
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("name", "read_path")
                        .put("displayName", "Read path")
                        .put("description", "Reads through the workspace host API.")
                        .put("entry", "tools/read_path.js")
                        .put(
                            "inputSchema",
                            JSONObject()
                                .put("type", "object")
                                .put("properties", JSONObject().put("path", JSONObject().put("type", "string")))
                                .put("required", JSONArray().put("path"))
                                .put("additionalProperties", false)
                                .toString()
                        )
                        .put("timeoutMillis", 5_000)
                )
            )
            .toString()
        val scripts = mapOf(
            "index.js" to "// registration is not executed in this slice\n",
            "tools/read_path.js" to
                "function main(input) { var reply = host.call(\"workspace.read_text\", " +
                "JSON.stringify({ path: input.path })); return JSON.parse(reply); }"
        )

        val installed = service.install(manifest.toByteArray(Charsets.UTF_8), scripts)
        assertTrue("install must succeed: $installed", installed is ToolPackageLifecycleOutcome.Applied)
        val packageId = (installed as ToolPackageLifecycleOutcome.Applied).status!!.packageId
        assertTrue(service.enable(packageId) is ToolPackageLifecycleOutcome.Applied)
        val executor = service.enabledExecutors().single()
        val command = FrozenToolCommand(
            invocationId = "inv-device-host-1",
            runId = "run-device-host-1",
            stepId = "step-device-host-1",
            callOrdinal = 0,
            toolKey = executor.descriptor.toolKey,
            toolVersion = executor.descriptor.version,
            schemaHash = executor.descriptor.schemaHash,
            normalizedArgumentsJson = """{"path":"inputs/notes.txt"}""",
            argumentsHash = "args-device-host",
            resourceFingerprint = "resource-device-host",
            runEpoch = 1L,
            policyVersion = 1L,
            expiresAtMillis = Long.MAX_VALUE
        )
        val toolContext = AgentToolContext(
            taskId = "task-device-host-1",
            workspaceId = "workspace-device-host-1",
            runId = "run-device-host-1",
            stepIndex = 0,
            runEpoch = 1L,
            policyVersion = 1L,
            workspaceReadGranted = true
        )
        val result = executor.execute(command, toolContext)
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertTrue(
            "host result must reach the script: ${result.dataJson}",
            result.dataJson.orEmpty().contains("hello from device")
        )

        service.uninstall(packageId)
        Unit
    }

    private class FakeWorkspacePort : AgentWorkspaceFilePort {
        override suspend fun list(
            workspaceId: String,
            taskId: String,
            path: String,
            limit: Int,
            cursor: String?
        ): AgentFileResult<AgentListResult> = AgentFileResult.Ok(AgentListResult(emptyList()))

        override suspend fun readText(
            workspaceId: String,
            taskId: String,
            path: String,
            offsetBytes: Long,
            limitBytes: Int
        ): AgentFileResult<AgentReadResult> = AgentFileResult.Ok(
            AgentReadResult(
                content = "hello from device",
                offsetBytes = 0,
                nextOffsetBytes = 17,
                eof = true,
                totalBytes = 17,
                sha256 = "b".repeat(64)
            )
        )

        override suspend fun searchText(
            workspaceId: String,
            taskId: String,
            query: String,
            path: String,
            maxMatches: Int
        ): AgentFileResult<AgentSearchResult> =
            AgentFileResult.Ok(AgentSearchResult(emptyList(), false, 0))

        override suspend fun writeArtifact(
            workspaceId: String,
            taskId: String,
            invocationId: String,
            logicalPath: String,
            content: String,
            expectedOldHash: String?,
            policyVersion: Long
        ): AgentFileResult<AgentWriteResult> =
            AgentFileResult.Failure("NOT_WIRED", "writes are not wired")
    }
    @Test
    fun runawayScriptIsAbortedOnDevice() = runBlocking {
        val root = File(context.filesDir, "toolpack-device-spin-${System.nanoTime()}")
        val service = ToolPackageLifecycleService(
            store = ToolPackageInstallStore(root),
            runtime = defaultToolPackageRuntime()
        )
        val manifest = JSONObject()
            .put("schemaVersion", ToolPackageLimits.FORMAT_VERSION)
            .put("packageId", "devicetest-spin")
            .put("packageVersion", "1.0.0")
            .put("hostApiVersion", ToolPackageLimits.HOST_API_VERSION)
            .put("displayName", "Device runaway")
            .put("description", "Device-only wall-clock abort proof.")
            .put("entry", "index.js")
            .put("hostApis", JSONArray())
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("name", "spin")
                        .put("displayName", "Spin")
                        .put("description", "Never returns.")
                        .put("entry", "tools/spin.js")
                        .put(
                            "inputSchema",
                            JSONObject()
                                .put("type", "object")
                                .put("properties", JSONObject())
                                .put("required", JSONArray())
                                .put("additionalProperties", false)
                                .toString()
                        )
                        .put("timeoutMillis", 800)
                )
            )
            .toString()
        val scripts = mapOf(
            "index.js" to "// registration is not executed in this slice\n",
            "tools/spin.js" to "function main(input) { while (true) { } }"
        )
        val installed = service.install(manifest.toByteArray(Charsets.UTF_8), scripts)
        assertTrue("install must succeed: $installed", installed is ToolPackageLifecycleOutcome.Applied)
        val packageId = (installed as ToolPackageLifecycleOutcome.Applied).status!!.packageId
        assertTrue(service.enable(packageId) is ToolPackageLifecycleOutcome.Applied)
        val executor = service.enabledExecutors().single()
        val command = FrozenToolCommand(
            invocationId = "inv-device-spin",
            runId = "run-device-spin",
            stepId = "step-device-spin",
            callOrdinal = 0,
            toolKey = executor.descriptor.toolKey,
            toolVersion = executor.descriptor.version,
            schemaHash = executor.descriptor.schemaHash,
            normalizedArgumentsJson = "{}",
            argumentsHash = "args-device-spin",
            resourceFingerprint = "resource-device-spin",
            runEpoch = 1L,
            policyVersion = 1L,
            expiresAtMillis = Long.MAX_VALUE
        )
        val started = System.nanoTime()
        val result = executor.execute(
            command,
            AgentToolContext(
                taskId = "task-device-spin",
                workspaceId = "workspace-device-spin",
                runId = "run-device-spin",
                stepIndex = 0,
                runEpoch = 1L,
                policyVersion = 1L,
                workspaceReadGranted = false
            )
        )
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("runaway script must not succeed", result.status != AgentToolResultStatus.SUCCEEDED)
        assertTrue("abort took ${elapsedMillis}ms", elapsedMillis < 5_000)
        service.uninstall(packageId)
        Unit
    }

    @Test
    fun updateAndRollbackKeepTheActiveContentOnDevice() = runBlocking {
        val root = File(context.filesDir, "toolpack-device-rollback-${System.nanoTime()}")
        val service = ToolPackageLifecycleService(
            store = ToolPackageInstallStore(root),
            runtime = defaultToolPackageRuntime()
        )
        fun manifest(version: String) = JSONObject()
            .put("schemaVersion", ToolPackageLimits.FORMAT_VERSION)
            .put("packageId", "devicetest-rollback")
            .put("packageVersion", version)
            .put("hostApiVersion", ToolPackageLimits.HOST_API_VERSION)
            .put("displayName", "Device rollback")
            .put("description", "Device-only update/rollback proof.")
            .put("entry", "index.js")
            .put("hostApis", JSONArray())
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("name", "version")
                        .put("displayName", "Version")
                        .put("description", "Returns its script version.")
                        .put("entry", "tools/version.js")
                        .put(
                            "inputSchema",
                            JSONObject()
                                .put("type", "object")
                                .put("properties", JSONObject())
                                .put("required", JSONArray())
                                .put("additionalProperties", false)
                                .toString()
                        )
                        .put("timeoutMillis", 5_000)
                )
            )
            .toString()
        fun scripts(marker: String) = mapOf(
            "index.js" to "// registration is not executed in this slice\n",
            "tools/version.js" to "function main(input) { return { version: '$marker' }; }"
        )
        suspend fun executeVersion(): String {
            val executor = service.enabledExecutors().single()
            val descriptor = executor.descriptor
            val result = executor.execute(
                FrozenToolCommand(
                    invocationId = "inv-rollback-" + System.nanoTime(),
                    runId = "run-rollback",
                    stepId = "step-rollback",
                    callOrdinal = 0,
                    toolKey = descriptor.toolKey,
                    toolVersion = descriptor.version,
                    schemaHash = descriptor.schemaHash,
                    normalizedArgumentsJson = "{}",
                    argumentsHash = "args-rollback",
                    resourceFingerprint = "scope-rollback",
                    runEpoch = 1L,
                    policyVersion = 1L,
                    expiresAtMillis = Long.MAX_VALUE
                ),
                AgentToolContext(
                    taskId = "task-rollback",
                    workspaceId = "workspace-rollback",
                    runId = "run-rollback",
                    stepIndex = 0,
                    runEpoch = 1L,
                    policyVersion = 1L,
                    workspaceReadGranted = false
                )
            )
            return result.dataJson.orEmpty()
        }

        val installed = service.install(manifest("1.0.0").toByteArray(Charsets.UTF_8), scripts("v1"))
        assertTrue("install must succeed: $installed", installed is ToolPackageLifecycleOutcome.Applied)
        val packageId = (installed as ToolPackageLifecycleOutcome.Applied).status!!.packageId
        assertTrue(service.enable(packageId) is ToolPackageLifecycleOutcome.Applied)
        assertTrue(executeVersion().contains("v1"))

        val updated = service.update(manifest("2.0.0").toByteArray(Charsets.UTF_8), scripts("v2"))
        assertTrue("update must succeed: $updated", updated is ToolPackageLifecycleOutcome.Applied)
        assertTrue(executeVersion().contains("v2"))

        val rolledBack = service.rollback(packageId)
        assertTrue("rollback must succeed: $rolledBack", rolledBack is ToolPackageLifecycleOutcome.Applied)
        assertTrue(executeVersion().contains("v1"))
        service.uninstall(packageId)
        Unit
    }

}

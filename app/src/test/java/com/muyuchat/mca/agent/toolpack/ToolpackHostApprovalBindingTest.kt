package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentListResult
import com.muyuchat.core.agent.contract.AgentReadResult
import com.muyuchat.core.agent.contract.AgentSearchResult
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import com.muyuchat.core.agent.contract.AgentWriteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PKG-07 / N11: a nested `host.call` is a per-call decision, not an inherited right.
 *
 * These tests drive the real [ToolPackageScriptRuntime] and [ToolPackageHostBridge] with a fake
 * engine so each step (capability re-check, resource resolution, approval, dispatch, replay) is
 * observable. The workspace surface itself is covered by `WorkspaceToolPackageHostHandlerTest`; a
 * real Rhino run of the same contract is covered by `ToolpackRhinoEngineTest`.
 */
class ToolpackHostApprovalBindingTest {

    private val writeArgs = """{"path":"artifacts/out.txt","content":"x","expectedOldHash":null}"""

    private fun writeBundle(): ExecutableToolPackage = ToolpackFixtures.bundle(
        ToolpackFixtures.manifestJson(hostApis = listOf(ToolPackageHostApis.ARTIFACT_WRITE_TEXT))
    )

    private fun readBundle(): ExecutableToolPackage = ToolpackFixtures.bundle()

    private fun execute(
        engine: ToolPackageScriptEngine,
        handler: ToolPackageHostOperationHandler,
        bundle: ExecutableToolPackage,
        approval: ToolPackageHostApprovalPort = ToolPackageHostApprovalPort.DENY_ALL
    ): ToolPackageExecutionResult = runBlocking {
        ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle, argumentsJson = "{}", approval = approval))
            .await()
    }

    @Test
    fun `a repeated ordinal with the same arguments approves and dispatches once`() {
        val handler = RecordingHostHandler(
            ToolPackageHostOperationResult.Returned("""{"artifactId":"art-1"}""")
        )
        val approval = RecordingApproval.allowing()
        val replies = mutableListOf<String>()
        val engine = FakeScriptEngine { request ->
            replies += request.host.call(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, writeArgs, 1)
            replies += request.host.call(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, writeArgs, 1)
            ToolPackageEngineResult.Returned("{}")
        }
        val bundle = writeBundle()

        val result = execute(engine, handler, bundle, approval)

        assertTrue(result is ToolPackageExecutionResult.Succeeded)
        assertEquals(1, approval.requests.size)
        assertEquals(1, handler.requests.size)
        assertEquals(replies[0], replies[1])
        val binding = approval.requests.single()
        assertEquals(1, binding.stableCallOrdinal)
        assertEquals(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, binding.method)
        assertEquals(bundle.contentHash, binding.packageContentHash)
        assertEquals("research-basics", binding.packageId)
        assertEquals("run-1", binding.runId)
        assertEquals("inv-1", binding.parentInvocationId)
        assertEquals(ToolPackageHostAccess.WRITE, binding.access)
        assertEquals(64, binding.argumentsHash.length)
        val resource = binding.resource as ToolPackageHostResource.Workspace
        assertEquals("workspace-1", resource.workspaceId)
        assertEquals(ToolPackageHostResourceAccess.WRITE, resource.access)
        assertEquals(binding.revocation, ToolPackageHostRevocation(runEpoch = 3L, policyVersion = 2L))
    }

    @Test
    fun `the same ordinal with different arguments is refused`() {
        val handler = RecordingHostHandler(ToolPackageHostOperationResult.Returned("""{"ok":true}"""))
        val approval = RecordingApproval.allowing()
        val engine = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, writeArgs, 7)
            request.host.call(
                ToolPackageHostApis.ARTIFACT_WRITE_TEXT,
                """{"path":"artifacts/other.txt","content":"x","expectedOldHash":null}""",
                7
            )
            ToolPackageEngineResult.Returned("{}")
        }

        val result = execute(engine, handler, writeBundle(), approval)

        assertEquals(
            ToolPackageErrorCode.HOST_CALL_REPLAY_MISMATCH,
            (result as ToolPackageExecutionResult.Denied).error.code
        )
        assertEquals(1, handler.requests.size)
        assertEquals(1, approval.requests.size)
    }

    @Test
    fun `a capability the package did not declare is refused before approval`() {
        val handler = RecordingHostHandler()
        val approval = RecordingApproval.allowing()
        val engine = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, writeArgs, 1)
            ToolPackageEngineResult.Returned("{}")
        }
        // The bundle declares only the workspace read capability.
        val bundle = readBundle()

        val result = execute(engine, handler, bundle, approval)

        assertEquals(
            ToolPackageErrorCode.HOST_API_NOT_DECLARED,
            (result as ToolPackageExecutionResult.Denied).error.code
        )
        assertTrue(handler.requests.isEmpty())
        assertTrue(approval.requests.isEmpty())
    }

    @Test
    fun `an approval for a revoked epoch is refused`() {
        val handler = RecordingHostHandler()
        val approval = RecordingApproval(decision = { binding ->
            ToolPackageHostApprovalDecision.Refused(
                ToolPackageHostApprovalRefusal.REVOKED,
                "The run epoch was revoked before this write."
            )
        })
        val engine = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, writeArgs, 1)
            ToolPackageEngineResult.Returned("{}")
        }

        val result = execute(engine, handler, writeBundle(), approval)

        assertEquals(
            ToolPackageErrorCode.HOST_API_APPROVAL_REVOKED,
            (result as ToolPackageExecutionResult.Denied).error.code
        )
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun `an approval issued for another binding is refused`() {
        val handler = RecordingHostHandler()
        val approval = RecordingApproval(decision = { binding ->
            ToolPackageHostApprovalDecision.Approved(
                approvalId = "approval-foreign",
                bindingId = "a-different-host-call",
                revocation = binding.revocation
            )
        })
        val engine = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, writeArgs, 1)
            ToolPackageEngineResult.Returned("{}")
        }

        val result = execute(engine, handler, writeBundle(), approval)

        assertEquals(
            ToolPackageErrorCode.HOST_API_APPROVAL_REVOKED,
            (result as ToolPackageExecutionResult.Denied).error.code
        )
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun `a write without any approval source performs zero handler calls`() {
        val handler = RecordingHostHandler()
        val engine = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, writeArgs, 1)
            ToolPackageEngineResult.Returned("{}")
        }

        val result = execute(engine, handler, writeBundle())

        assertEquals(
            ToolPackageErrorCode.HOST_API_WRITE_NOT_APPROVED,
            (result as ToolPackageExecutionResult.Denied).error.code
        )
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun `one approval id cannot authorise a second call`() {
        val handler = RecordingHostHandler(ToolPackageHostOperationResult.Returned("""{"ok":true}"""))
        val approval = RecordingApproval(decision = { binding ->
            ToolPackageHostApprovalDecision.Approved(
                approvalId = "one-single-approval",
                bindingId = binding.bindingId,
                revocation = binding.revocation
            )
        })
        val engine = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, writeArgs, 1)
            request.host.call(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, writeArgs, 2)
            ToolPackageEngineResult.Returned("{}")
        }

        val result = execute(engine, handler, writeBundle(), approval)

        assertEquals(
            ToolPackageErrorCode.HOST_API_APPROVAL_REVOKED,
            (result as ToolPackageExecutionResult.Denied).error.code
        )
        assertEquals(1, handler.requests.size)
        assertEquals(2, approval.requests.size)
        assertNotEquals(approval.requests[0].bindingId, approval.requests[1].bindingId)
    }

    @Test
    fun `cancelling between the approval and the handler leaves zero side effects`() = runBlocking {
        val handler = RecordingHostHandler(ToolPackageHostOperationResult.Returned("""{"ok":true}"""))
        var execution: ToolPackageExecution? = null
        val approval = RecordingApproval(decision = { binding ->
            // The write is approved, then the execution is cancelled before the handler is entered.
            execution?.cancel()
            RecordingApproval.allow(binding)
        })
        val engine = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, writeArgs, 5)
            ToolPackageEngineResult.Returned("{}")
        }
        val bundle = writeBundle()
        execution = ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle, argumentsJson = "{}", approval = approval))

        val result = execution.await()

        assertEquals(ToolPackageExecutionResult.Cancelled, result)
        assertTrue(handler.requests.isEmpty())
        assertEquals(1, approval.requests.size)
    }

    @Test
    fun `a declared workspace read resolves to its canonical path`() {
        val port = RecordingWorkspacePort()
        val handler = WorkspaceToolPackageHostHandler(port)
        val engine = FakeScriptEngine { request ->
            request.host.call(
                ToolPackageHostApis.WORKSPACE_READ_TEXT,
                """{"path":"inputs/notes.txt"}""",
                1
            )
            ToolPackageEngineResult.Returned("""{"ok":true}""")
        }

        val result = execute(engine, handler, readBundle())

        assertTrue(result is ToolPackageExecutionResult.Succeeded)
        assertEquals(listOf("inputs/notes.txt"), port.readPaths)
        assertEquals("workspace-1", port.lastWorkspaceId)
        assertEquals("task-1", port.lastTaskId)
    }

    @Test
    fun `an undeclared workspace resource is refused before the port`() {
        val port = RecordingWorkspacePort()
        val handler = WorkspaceToolPackageHostHandler(port)
        val engine = FakeScriptEngine { request ->
            request.host.call(
                ToolPackageHostApis.WORKSPACE_READ_TEXT,
                """{"path":"../secrets/notes.txt"}""",
                1
            )
            ToolPackageEngineResult.Returned("{}")
        }

        val result = execute(engine, handler, readBundle())

        assertEquals(
            ToolPackageErrorCode.HOST_CALL_RESOURCE_UNDECLARED,
            (result as ToolPackageExecutionResult.Denied).error.code
        )
        assertTrue(port.readPaths.isEmpty())
    }

    @Test
    fun `an unknown host method is refused before any resource or approval`() {
        val handler = RecordingHostHandler()
        val approval = RecordingApproval.allowing()
        val engine = FakeScriptEngine { request ->
            request.host.call("network.fetch", """{"url":"https://example.invalid"}""", 1)
            ToolPackageEngineResult.Returned("{}")
        }

        val result = execute(engine, handler, readBundle(), approval)

        assertEquals(
            ToolPackageErrorCode.HOST_API_UNKNOWN,
            (result as ToolPackageExecutionResult.Denied).error.code
        )
        assertTrue(handler.requests.isEmpty())
        assertTrue(approval.requests.isEmpty())
    }

    /** Minimal workspace port: records the canonical path the handler actually asked for. */
    private class RecordingWorkspacePort : AgentWorkspaceFilePort {
        val readPaths = mutableListOf<String>()
        var lastWorkspaceId: String? = null
        var lastTaskId: String? = null

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
        ): AgentFileResult<AgentReadResult> {
            readPaths += path
            lastWorkspaceId = workspaceId
            lastTaskId = taskId
            return AgentFileResult.Ok(
                AgentReadResult(
                    content = "hello",
                    offsetBytes = 0,
                    nextOffsetBytes = 5,
                    eof = true,
                    totalBytes = 5,
                    sha256 = "a".repeat(64)
                )
            )
        }

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
}

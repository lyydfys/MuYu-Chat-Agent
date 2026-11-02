package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentFileEntry
import com.muyuchat.core.agent.contract.AgentFileKind
import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentListResult
import com.muyuchat.core.agent.contract.AgentReadResult
import com.muyuchat.core.agent.contract.AgentSearchMatch
import com.muyuchat.core.agent.contract.AgentSearchResult
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import com.muyuchat.core.agent.contract.AgentWriteResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolPackageHostHandlerTest {

    private class FakePort(
        var readResult: AgentFileResult<AgentReadResult> = AgentFileResult.Ok(
            AgentReadResult(
                content = "hello",
                offsetBytes = 0,
                nextOffsetBytes = 5,
                eof = true,
                totalBytes = 5,
                sha256 = "a".repeat(64)
            )
        )
    ) : AgentWorkspaceFilePort {
        var lastWorkspace: String? = null
        var lastTask: String? = null
        var lastLimit: Int? = null

        override suspend fun list(
            workspaceId: String,
            taskId: String,
            path: String,
            limit: Int,
            cursor: String?
        ): AgentFileResult<AgentListResult> = AgentFileResult.Ok(
            AgentListResult(
                entries = listOf(AgentFileEntry("notes.txt", AgentFileKind.FILE, 5))
            )
        )

        override suspend fun readText(
            workspaceId: String,
            taskId: String,
            path: String,
            offsetBytes: Long,
            limitBytes: Int
        ): AgentFileResult<AgentReadResult> {
            lastWorkspace = workspaceId
            lastTask = taskId
            lastLimit = limitBytes
            return readResult
        }

        override suspend fun searchText(
            workspaceId: String,
            taskId: String,
            query: String,
            path: String,
            maxMatches: Int
        ): AgentFileResult<AgentSearchResult> = AgentFileResult.Ok(
            AgentSearchResult(
                matches = listOf(AgentSearchMatch("notes.txt", 1, "hello")),
                truncated = false,
                scannedBytes = 5
            )
        )

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

    private fun request(
        method: String,
        arguments: String = """{"path":"notes.txt"}""",
        relativePath: String? = "notes.txt",
        access: ToolPackageHostResourceAccess = ToolPackageHostResourceAccess.READ
    ) = ToolPackageHostOperationRequest(
        api = ToolPackageHostApis.require(method),
        argumentsJson = arguments,
        instanceId = "instance-1",
        packageId = "pkg-1",
        packageContentHash = "hash-1",
        scope = ToolPackageExecutionScope(
            invocationId = "inv-1",
            runId = "run-1",
            runEpoch = 1L,
            taskId = "task-1",
            workspaceId = "workspace-1",
            policyVersion = 1L
        ),
        resource = ToolPackageHostResource.Workspace(
            workspaceId = "workspace-1",
            relativePath = relativePath,
            access = access,
            capability = method
        )
    )

    private fun resourceRequest(
        method: String,
        arguments: String = """{"path":"notes.txt"}"""
    ) = ToolPackageHostResourceRequest(
        api = ToolPackageHostApis.require(method),
        argumentsJson = arguments,
        instanceId = "instance-1",
        packageId = "pkg-1",
        packageContentHash = "hash-1",
        scope = ToolPackageExecutionScope(
            invocationId = "inv-1",
            runId = "run-1",
            runEpoch = 1L,
            taskId = "task-1",
            workspaceId = "workspace-1",
            policyVersion = 1L
        )
    )

    @Test
    fun `read is scoped to the execution workspace and task`() = runBlocking {
        val port = FakePort()
        val result = WorkspaceToolPackageHostHandler(port).handle(
            request(ToolPackageHostApis.WORKSPACE_READ_TEXT)
        )
        assertTrue(result is ToolPackageHostOperationResult.Returned)
        val json = JSONObject((result as ToolPackageHostOperationResult.Returned).json)
        assertEquals("hello", json.getString("text"))
        assertEquals("workspace-1", port.lastWorkspace)
        assertEquals("task-1", port.lastTask)
    }

    @Test
    fun `script supplied limit is clamped instead of trusted`() = runBlocking {
        val port = FakePort()
        WorkspaceToolPackageHostHandler(port).handle(
            request(
                ToolPackageHostApis.WORKSPACE_READ_TEXT,
                """{"path":"notes.txt","limit":999999999}"""
            )
        )
        assertEquals(256 * 1024, port.lastLimit)
    }

    @Test
    fun `a blank path is denied before reaching the port`() = runBlocking {
        val port = FakePort()
        val result = WorkspaceToolPackageHostHandler(port).handle(
            request(
                ToolPackageHostApis.WORKSPACE_READ_TEXT,
                """{"path":" "}""",
                relativePath = null
            )
        )
        assertTrue(result is ToolPackageHostOperationResult.Denied)
        assertEquals(null, port.lastWorkspace)
    }

    @Test
    fun `a declared workspace path resolves to a canonical resource`() = runBlocking {
        val resolution = WorkspaceToolPackageHostHandler(FakePort())
            .resolveResource(resourceRequest(ToolPackageHostApis.WORKSPACE_READ_TEXT, """{"path":"inputs/notes.txt"}"""))

        val resource = (resolution as ToolPackageHostResourceResolution.Resolved).resource
            as ToolPackageHostResource.Workspace
        assertEquals("workspace-1", resource.workspaceId)
        assertEquals("inputs/notes.txt", resource.relativePath)
        assertEquals(ToolPackageHostResourceAccess.READ, resource.access)
        assertEquals(ToolPackageHostApis.WORKSPACE_READ_TEXT, resource.capability)
    }

    @Test
    fun `a path outside the task surface is refused as undeclared`() = runBlocking {
        val handler = WorkspaceToolPackageHostHandler(FakePort())
        for (path in listOf("secrets/notes.txt", "inputs/../secrets.txt", "/etc/passwd", "inputs\\notes.txt")) {
            val resolution = handler.resolveResource(
                resourceRequest(ToolPackageHostApis.WORKSPACE_READ_TEXT, JSONObject().put("path", path).toString())
            )
            assertEquals(
                path,
                ToolPackageErrorCode.HOST_CALL_RESOURCE_UNDECLARED,
                (resolution as ToolPackageHostResourceResolution.Refused).error.code
            )
        }
    }

    @Test
    fun `a missing read path is refused as unresolved`() = runBlocking {
        val resolution = WorkspaceToolPackageHostHandler(FakePort())
            .resolveResource(resourceRequest(ToolPackageHostApis.WORKSPACE_READ_TEXT, """{"path":" "}"""))

        assertEquals(
            ToolPackageErrorCode.HOST_CALL_RESOURCE_UNRESOLVED,
            (resolution as ToolPackageHostResourceResolution.Refused).error.code
        )
    }

    @Test
    fun `a blank list path resolves to the task root`() = runBlocking {
        val resolution = WorkspaceToolPackageHostHandler(FakePort())
            .resolveResource(resourceRequest(ToolPackageHostApis.WORKSPACE_LIST, "{}"))

        val resource = (resolution as ToolPackageHostResourceResolution.Resolved).resource
            as ToolPackageHostResource.Workspace
        assertEquals(null, resource.relativePath)
    }

    @Test
    fun `an artifact write outside artifacts is refused before any store`() = runBlocking {
        val resolution = WorkspaceToolPackageHostHandler(FakePort()).resolveResource(
            resourceRequest(
                ToolPackageHostApis.ARTIFACT_WRITE_TEXT,
                """{"path":"inputs/out.txt","content":"x","expectedOldHash":null}"""
            )
        )

        assertEquals(
            ToolPackageErrorCode.HOST_CALL_RESOURCE_UNDECLARED,
            (resolution as ToolPackageHostResourceResolution.Refused).error.code
        )
    }

    @Test
    fun `memory reads are refused as an undeclared resource`() = runBlocking {
        val resolution = WorkspaceToolPackageHostHandler(FakePort())
            .resolveResource(resourceRequest(ToolPackageHostApis.MEMORY_SEARCH, """{"query":"x"}"""))

        assertEquals(
            ToolPackageErrorCode.HOST_CALL_RESOURCE_UNDECLARED,
            (resolution as ToolPackageHostResourceResolution.Refused).error.code
        )
    }

    @Test
    fun `workspace failures stay typed denials`() = runBlocking {
        val port = FakePort(readResult = AgentFileResult.Failure("WORKSPACE_PATH_DENIED", "path denied"))
        val result = WorkspaceToolPackageHostHandler(port).handle(
            request(ToolPackageHostApis.WORKSPACE_READ_TEXT)
        )
        assertTrue(result is ToolPackageHostOperationResult.Denied)
    }

    @Test
    fun `memory search is denied until its store is wired`() = runBlocking {
        val result = WorkspaceToolPackageHostHandler(FakePort()).handle(
            request(ToolPackageHostApis.MEMORY_SEARCH, """{"query":"x"}""")
        )
        assertTrue(result is ToolPackageHostOperationResult.Denied)
    }
}

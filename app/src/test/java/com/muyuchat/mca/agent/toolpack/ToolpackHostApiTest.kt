package com.muyuchat.mca.agent.toolpack

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolpackHostApiTest {

    @Test
    fun `host registry exposes the read-only slice and classifies writes`() {
        assertEquals(
            setOf(
                ToolPackageHostApis.WORKSPACE_LIST,
                ToolPackageHostApis.WORKSPACE_READ_TEXT,
                ToolPackageHostApis.WORKSPACE_SEARCH_TEXT,
                ToolPackageHostApis.MEMORY_SEARCH
            ),
            ToolPackageHostApis.READ_ONLY_METHODS
        )
        assertEquals(setOf(ToolPackageHostApis.ARTIFACT_WRITE_TEXT), ToolPackageHostApis.WRITE_METHODS)
        assertNull(ToolPackageHostApis.find("network.fetch"))
        assertNull(ToolPackageHostApis.find("shell.exec"))
    }

    @Test
    fun `declared read-only host call reaches the handler with canonical arguments`() = runTest {
        val handler = RecordingHostHandler(ToolPackageHostOperationResult.Returned("""{"content":"hello"}"""))
        val engine = FakeScriptEngine { request ->
            val content = request.host.call(
                ToolPackageHostApis.WORKSPACE_READ_TEXT,
                """{"path":"notes.txt","limit":64}"""
            )
            assertEquals("""{"content":"hello"}""", content)
            ToolPackageEngineResult.Returned("""{"answer":1}""")
        }
        val bundle = ToolpackFixtures.bundle()

        val result = ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()

        assertTrue(result is ToolPackageExecutionResult.Succeeded)
        assertEquals("""{"answer":1}""", (result as ToolPackageExecutionResult.Succeeded).resultJson)
        assertEquals(1, handler.requests.size)
        val request = handler.requests.single()
        assertEquals(ToolPackageHostApis.WORKSPACE_READ_TEXT, request.api.method)
        assertEquals("""{"limit":64,"path":"notes.txt"}""", request.argumentsJson)
        assertEquals("research-basics", request.packageId)
        assertEquals(bundle.contentHash, request.packageContentHash)
        assertEquals("run-1", request.scope.runId)
    }

    @Test
    fun `a host API the manifest did not declare is denied before the handler`() = runTest {
        val handler = RecordingHostHandler()
        val engine = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.WORKSPACE_READ_TEXT, """{"path":"notes.txt"}""")
            ToolPackageEngineResult.Returned("{}")
        }
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(hostApis = listOf(ToolPackageHostApis.MEMORY_SEARCH))
        )

        val result = ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()

        val denied = result as ToolPackageExecutionResult.Denied
        assertEquals(ToolPackageErrorCode.HOST_API_NOT_DECLARED, denied.error.code)
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun `an unregistered host API is denied even when the manifest names it`() = runTest {
        val handler = RecordingHostHandler()
        val engine = FakeScriptEngine { request ->
            request.host.call("network.fetch", """{"url":"https://example.invalid"}""")
            ToolPackageEngineResult.Returned("{}")
        }
        val bundle = ToolpackFixtures.bundle()

        val result = ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()

        val denied = result as ToolPackageExecutionResult.Denied
        assertEquals(ToolPackageErrorCode.HOST_API_UNKNOWN, denied.error.code)
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun `a write host call without per-call approval is denied`() = runTest {
        val handler = RecordingHostHandler()
        val approval = RecordingApproval.denying()
        val engine = FakeScriptEngine { request ->
            request.host.call(
                ToolPackageHostApis.ARTIFACT_WRITE_TEXT,
                """{"path":"out.txt","content":"x","expectedOldHash":null}"""
            )
            ToolPackageEngineResult.Returned("{}")
        }
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(hostApis = listOf(ToolPackageHostApis.ARTIFACT_WRITE_TEXT))
        )

        val result = ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle, approval = approval))
            .await()

        val denied = result as ToolPackageExecutionResult.Denied
        assertEquals(ToolPackageErrorCode.HOST_API_WRITE_NOT_APPROVED, denied.error.code)
        assertTrue(handler.requests.isEmpty())
        assertEquals(1, approval.requests.size)
        assertEquals(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, approval.requests.single().method)
    }

    @Test
    fun `an approved write host call reaches the handler exactly once`() = runTest {
        val handler = RecordingHostHandler(ToolPackageHostOperationResult.Returned("""{"artifactId":"art-1"}"""))
        val approval = RecordingApproval.allowing()
        val engine = FakeScriptEngine { request ->
            request.host.call(
                ToolPackageHostApis.ARTIFACT_WRITE_TEXT,
                """{"path":"out.txt","content":"x","expectedOldHash":null}"""
            )
            ToolPackageEngineResult.Returned("""{"written":true}""")
        }
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(hostApis = listOf(ToolPackageHostApis.ARTIFACT_WRITE_TEXT))
        )

        val result = ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle, approval = approval))
            .await()

        assertTrue(result is ToolPackageExecutionResult.Succeeded)
        assertEquals(1, approval.requests.size)
        assertEquals(1, handler.requests.size)
    }

    @Test
    fun `host arguments are checked against the operation surface before dispatch`() = runTest {
        val handler = RecordingHostHandler()
        val extra = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.WORKSPACE_READ_TEXT, """{"path":"x","evil":true}""")
            ToolPackageEngineResult.Returned("{}")
        }
        val missing = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.WORKSPACE_READ_TEXT, """{"limit":10}""")
            ToolPackageEngineResult.Returned("{}")
        }
        val bundle = ToolpackFixtures.bundle()
        for (engine in listOf(extra, missing)) {
            val result = ToolPackageScriptRuntime(engine, handler)
                .open(bundle)
                .start(this, ToolpackFixtures.request(bundle))
                .await()
            val failed = result as ToolPackageExecutionResult.Failed
            assertEquals(ToolPackageErrorCode.HOST_CALL_ARGUMENTS_INVALID, failed.error.code)
        }
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun `host call and output budgets stop the script`() = runTest {
        val callsHandler = RecordingHostHandler()
        val budgetEngine = FakeScriptEngine { request ->
            repeat(2) {
                request.host.call(ToolPackageHostApis.WORKSPACE_READ_TEXT, """{"path":"notes.txt"}""")
            }
            ToolPackageEngineResult.Returned("{}")
        }
        val bundle = ToolpackFixtures.bundle()
        val limited = ToolPackageScriptRuntime(
            budgetEngine,
            callsHandler,
            ToolPackageRuntimeLimits(maxHostCalls = 1)
        ).open(bundle).start(this, ToolpackFixtures.request(bundle)).await()
        assertEquals(
            ToolPackageErrorCode.HOST_CALL_LIMIT,
            (limited as ToolPackageExecutionResult.Failed).error.code
        )
        assertEquals(1, callsHandler.requests.size)

        val bigHandler = RecordingHostHandler(
            ToolPackageHostOperationResult.Returned("""{"content":"${"a".repeat(256)}"}""")
        )
        val bigEngine = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.WORKSPACE_READ_TEXT, """{"path":"notes.txt"}""")
            ToolPackageEngineResult.Returned("{}")
        }
        val outputLimited = ToolPackageScriptRuntime(
            bigEngine,
            bigHandler,
            ToolPackageRuntimeLimits(maxHostResultBytes = 32, maxHostTotalResultBytes = 64)
        ).open(bundle).start(this, ToolpackFixtures.request(bundle)).await()
        assertEquals(
            ToolPackageErrorCode.HOST_CALL_OUTPUT_LIMIT,
            (outputLimited as ToolPackageExecutionResult.Failed).error.code
        )
    }

    @Test
    fun `a handler denial and a malformed handler result stay typed`() = runTest {
        val denial = RecordingHostHandler(
            ToolPackageHostOperationResult.Denied(
                ToolPackageError(ToolPackageErrorCode.HOST_CALL_DENIED, "The workspace read was denied.")
            )
        )
        val engine = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.WORKSPACE_READ_TEXT, """{"path":"notes.txt"}""")
            ToolPackageEngineResult.Returned("{}")
        }
        val bundle = ToolpackFixtures.bundle()
        val denied = ToolPackageScriptRuntime(engine, denial)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()
        assertEquals(
            ToolPackageErrorCode.HOST_CALL_DENIED,
            (denied as ToolPackageExecutionResult.Denied).error.code
        )

        val malformed = RecordingHostHandler(ToolPackageHostOperationResult.Returned("""["not-an-object"]"""))
        val malformedResult = ToolPackageScriptRuntime(engine, malformed)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()
        assertEquals(
            ToolPackageErrorCode.HOST_CALL_RESULT_INVALID,
            (malformedResult as ToolPackageExecutionResult.Failed).error.code
        )
    }

    @Test
    fun `a handler exception is reported without leaking its message`() = runTest {
        val handler = object : ToolPackageHostOperationHandler {
            override suspend fun resolveResource(
                request: ToolPackageHostResourceRequest
            ): ToolPackageHostResourceResolution = ToolPackageHostResourceResolution.Resolved(
                ToolPackageHostResource.Workspace(
                    workspaceId = request.scope.workspaceId,
                    relativePath = "notes.txt",
                    access = ToolPackageHostResourceAccess.READ,
                    capability = request.api.method
                )
            )

            override suspend fun handle(request: ToolPackageHostOperationRequest): ToolPackageHostOperationResult {
                throw IllegalStateException("secret path /data/private")
            }
        }
        val engine = FakeScriptEngine { request ->
            request.host.call(ToolPackageHostApis.WORKSPACE_READ_TEXT, """{"path":"notes.txt"}""")
            ToolPackageEngineResult.Returned("{}")
        }
        val bundle = ToolpackFixtures.bundle()
        val result = ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()

        val failed = result as ToolPackageExecutionResult.Failed
        assertEquals(ToolPackageErrorCode.HOST_CALL_FAILED, failed.error.code)
        assertTrue(!failed.error.safeMessage.contains("secret"))
        assertTrue(!failed.error.safeMessage.contains("/data/private"))
    }
}

package com.muyuchat.mca.agent.toolpack

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolpackRuntimeTest {

    private val handler = RecordingHostHandler()

    @Test
    fun `a pure computation needs no host capability`() = runTest {
        val engine = FakeScriptEngine { ToolPackageEngineResult.Returned("""{"value":42}""") }
        val bundle = ToolpackFixtures.bundle(ToolpackFixtures.manifestJson(hostApis = emptyList()))

        val result = ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()

        assertEquals("""{"value":42}""", (result as ToolPackageExecutionResult.Succeeded).resultJson)
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun `the per-tool timeout is enforced`() = runTest {
        val engine = FakeScriptEngine {
            delay(10_000)
            ToolPackageEngineResult.Returned("{}")
        }
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(tools = listOf(ToolpackFixtures.toolJson(timeoutMillis = 50)))
        )

        val result = ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()

        assertEquals(
            ToolPackageErrorCode.EXECUTION_TIMEOUT,
            (result as ToolPackageExecutionResult.Failed).error.code
        )
    }

    @Test
    fun `the script output budget is enforced before parsing`() = runTest {
        val engine = FakeScriptEngine {
            ToolPackageEngineResult.Returned("""{"blob":"${"a".repeat(256)}"}""")
        }
        val bundle = ToolpackFixtures.bundle()

        val result = ToolPackageScriptRuntime(
            engine,
            handler,
            ToolPackageRuntimeLimits(maxOutputBytes = 32)
        ).open(bundle).start(this, ToolpackFixtures.request(bundle)).await()

        assertEquals(
            ToolPackageErrorCode.EXECUTION_OUTPUT_LIMIT,
            (result as ToolPackageExecutionResult.Failed).error.code
        )
    }

    @Test
    fun `a non-object script result is rejected`() = runTest {
        val engine = FakeScriptEngine { ToolPackageEngineResult.Returned("""["not","an","object"]""") }
        val bundle = ToolpackFixtures.bundle()

        val result = ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()

        assertEquals(
            ToolPackageErrorCode.EXECUTION_RESULT_INVALID,
            (result as ToolPackageExecutionResult.Failed).error.code
        )
    }

    @Test
    fun `invalid arguments are rejected before the engine starts`() = runTest {
        val engine = FakeScriptEngine { ToolPackageEngineResult.Returned("{}") }
        val bundle = ToolpackFixtures.bundle()

        for (arguments in listOf("""["array"]""", "not-json", "")) {
            val result = ToolPackageScriptRuntime(engine, handler)
                .open(bundle)
                .start(this, ToolpackFixtures.request(bundle, argumentsJson = arguments))
                .await()
            assertEquals(
                ToolPackageErrorCode.EXECUTION_ARGUMENTS_INVALID,
                (result as ToolPackageExecutionResult.Failed).error.code
            )
        }
        assertEquals(0, engine.invocations)
    }

    @Test
    fun `a tool outside the package snapshot is rejected`() = runTest {
        val engine = FakeScriptEngine { ToolPackageEngineResult.Returned("{}") }
        val bundle = ToolpackFixtures.bundle()
        val foreign = ExecutableToolPackageTool(
            name = "other_tool",
            displayName = "Other",
            description = "Not part of the bundle.",
            entry = "other.js",
            inputSchemaJson = ToolpackFixtures.ZERO_ARGUMENT_SCHEMA,
            timeoutMillis = 1_000L
        )
        val request = ToolPackageExecutionRequest(
            scope = ToolpackFixtures.scope(),
            tool = foreign,
            argumentsJson = "{}"
        )

        val result = ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, request)
            .await()

        assertEquals(
            ToolPackageErrorCode.EXECUTION_TOOL_UNKNOWN,
            (result as ToolPackageExecutionResult.Failed).error.code
        )
        assertEquals(0, engine.invocations)
    }

    @Test
    fun `engine failures are typed and do not leak exception text`() = runTest {
        val reported = FakeScriptEngine { ToolPackageEngineResult.Failed("the script rejected the input") }
        val thrown = FakeScriptEngine { throw IllegalStateException("secret internal path") }
        val bundle = ToolpackFixtures.bundle()

        val reportedResult = ToolPackageScriptRuntime(reported, handler)
            .open(bundle).start(this, ToolpackFixtures.request(bundle)).await()
        val reportedFailure = reportedResult as ToolPackageExecutionResult.Failed
        assertEquals(ToolPackageErrorCode.ENGINE_FAILED, reportedFailure.error.code)
        assertEquals("the script rejected the input", reportedFailure.error.safeMessage)

        val thrownResult = ToolPackageScriptRuntime(thrown, handler)
            .open(bundle).start(this, ToolpackFixtures.request(bundle)).await()
        val thrownFailure = thrownResult as ToolPackageExecutionResult.Failed
        assertEquals(ToolPackageErrorCode.ENGINE_FAILED, thrownFailure.error.code)
        assertFalse(thrownFailure.error.safeMessage.contains("secret"))
    }

    @Test
    fun `one instance rejects concurrent execution and recovers after completion`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeScriptEngine {
            gate.await()
            ToolPackageEngineResult.Returned("""{"done":true}""")
        }
        val bundle = ToolpackFixtures.bundle()
        val instance = ToolPackageScriptRuntime(engine, handler).open(bundle)

        val first = instance.start(this, ToolpackFixtures.request(bundle))
        val second = instance.start(this, ToolpackFixtures.request(bundle))
        val busy = second.await() as ToolPackageExecutionResult.Failed
        assertEquals(ToolPackageErrorCode.INSTANCE_BUSY, busy.error.code)

        gate.complete(Unit)
        assertTrue(first.await() is ToolPackageExecutionResult.Succeeded)

        val third = instance.start(this, ToolpackFixtures.request(bundle))
        assertTrue(third.await() is ToolPackageExecutionResult.Succeeded)
    }

    @Test
    fun `cancellation stops a running script without reporting success`() = runTest {
        val engine = FakeScriptEngine { request ->
            repeat(1_000) {
                request.control.ensureActive()
                delay(1)
            }
            ToolPackageEngineResult.Returned("""{"finished":true}""")
        }
        val bundle = ToolpackFixtures.bundle()
        val instance = ToolPackageScriptRuntime(engine, handler).open(bundle)
        val execution = instance.start(this, ToolpackFixtures.request(bundle))

        delay(5)
        execution.cancel()
        assertEquals(ToolPackageExecutionResult.Cancelled, execution.await())

        advanceUntilIdle()
        val afterCancel = instance.start(this, ToolpackFixtures.request(bundle))
        advanceUntilIdle()
        assertTrue(afterCancel.await() is ToolPackageExecutionResult.Succeeded)
    }

    @Test
    fun `cancellation rejects host calls that arrive afterwards`() = runTest {
        val calls = mutableListOf<Int>()
        val engine = FakeScriptEngine { request ->
            repeat(1_000) { index ->
                request.control.ensureActive()
                request.host.call(ToolPackageHostApis.WORKSPACE_READ_TEXT, """{"path":"notes.txt"}""")
                calls += index
                delay(1)
            }
            ToolPackageEngineResult.Returned("{}")
        }
        val bundle = ToolpackFixtures.bundle()
        val execution = ToolPackageScriptRuntime(engine, handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))

        delay(4)
        execution.cancel()
        assertEquals(ToolPackageExecutionResult.Cancelled, execution.await())
        val callsAtCancel = calls.size
        advanceUntilIdle()

        assertEquals(callsAtCancel, calls.size)
        assertTrue(handler.requests.size <= callsAtCancel)
    }

    @Test
    fun `owner cancellation keeps propagating instead of becoming a result`() = runTest {
        val engine = FakeScriptEngine {
            delay(10_000)
            ToolPackageEngineResult.Returned("{}")
        }
        val bundle = ToolpackFixtures.bundle()
        val instance = ToolPackageScriptRuntime(engine, handler).open(bundle)
        var outcome: ToolPackageExecutionResult? = null

        val job = launch {
            outcome = instance.start(this, ToolpackFixtures.request(bundle)).await()
        }
        delay(5)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(null, outcome)
    }

    @Test
    fun `runtime limits cannot be raised above the frozen package budget`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolPackageRuntimeLimits(maxHostCalls = ToolPackageLimits.MAX_HOST_CALLS + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolPackageRuntimeLimits(maxOutputBytes = ToolPackageLimits.MAX_OUTPUT_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolPackageRuntimeLimits(maxToolTimeoutMillis = ToolPackageLimits.MAX_TOOL_TIMEOUT_MILLIS + 1)
        }
    }
}

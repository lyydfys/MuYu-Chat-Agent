package com.muyuchat.mca.agent.toolpack

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real Rhino execution tests (no fake engine). These are JVM tests: they prove the host-side
 * sandbox and script contract, not Android `isolatedProcess`/UID/FD isolation (NOT_RUN).
 */
class ToolpackRhinoEngineTest {

    private val handler = RecordingHostHandler(
        ToolPackageHostOperationResult.Returned("""{"content":"hello from rhino"}""")
    )

    private fun engine(): RhinoToolPackageScriptEngine = RhinoToolPackageScriptEngine()

    private fun bundleWith(script: String, timeoutMillis: Long = 10_000L): ExecutableToolPackage =
        ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(tools = listOf(ToolpackFixtures.toolJson(timeoutMillis = timeoutMillis))),
            ToolpackFixtures.scripts(ToolpackFixtures.TOOL_ENTRY to script)
        )

    @Test
    fun `executes a real script and reaches the declared host API`() = runBlocking {
        val script = """
            function main(input) {
              var reply = host.call("workspace.read_text", JSON.stringify({ path: input.path }));
              var parsed = JSON.parse(reply);
              return { content: parsed.content, path: input.path, sum: 1 + 1 };
            }
        """.trimIndent()
        val bundle = bundleWith(script)

        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()

        val succeeded = result as ToolPackageExecutionResult.Succeeded
        assertEquals(
            """{"content":"hello from rhino","path":"notes.txt","sum":2}""",
            succeeded.resultJson
        )
        assertEquals(1, handler.requests.size)
        assertEquals(ToolPackageHostApis.WORKSPACE_READ_TEXT, handler.requests.single().api.method)
    }

    @Test
    fun `denies every Java class and host file or loader global`() = runBlocking {
        val script = """
            function main(input) {
              var blocked = [];
              function probe(name, fn) {
                try { fn(); } catch (e) { blocked.push(name); }
              }
              probe("java", function () { java.lang.System.exit(0); });
              probe("packages", function () { Packages.java.lang.System.exit(0); });
              probe("android", function () { Packages.android.app.Activity; });
              probe("file", function () { new java.io.File("/"); });
              probe("getClass", function () { getClass(); });
              probe("load", function () { load("other.js"); });
              return {
                blocked: blocked,
                javaType: typeof java,
                packagesType: typeof Packages,
                loadType: typeof load,
                readFileType: typeof readFile,
                getClassType: typeof getClass,
                hostType: typeof host,
                printType: typeof print
              };
            }
        """.trimIndent()
        val bundle = bundleWith(script)

        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await() as ToolPackageExecutionResult.Succeeded

        val json = org.json.JSONObject(result.resultJson)
        assertEquals(6, json.getJSONArray("blocked").length())
        assertEquals("undefined", json.getString("javaType"))
        assertEquals("undefined", json.getString("packagesType"))
        assertEquals("undefined", json.getString("loadType"))
        assertEquals("undefined", json.getString("readFileType"))
        assertEquals("undefined", json.getString("getClassType"))
        assertEquals("object", json.getString("hostType"))
        assertEquals("function", json.getString("printType"))
    }

    @Test
    fun `an unbounded script is stopped by the wall clock`() = runBlocking {
        val bundle = bundleWith("function main(input) { while (true) { } }", timeoutMillis = 200)
        val started = System.nanoTime()

        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()

        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
        assertEquals(
            ToolPackageErrorCode.EXECUTION_TIMEOUT,
            (result as ToolPackageExecutionResult.Failed).error.code
        )
        assertTrue("script should be interrupted promptly, took ${elapsedMillis}ms", elapsedMillis < 5_000L)
    }

    @Test
    fun `a repeated stable ordinal through the real engine is one logical call`() = runBlocking {
        val script = """
            function main(input) {
              var first = host.call("workspace.read_text", JSON.stringify({ path: input.path }), 9);
              var second = host.call("workspace.read_text", JSON.stringify({ path: input.path }), 9);
              return { same: first === second };
            }
        """.trimIndent()
        val bundle = bundleWith(script)

        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()

        val succeeded = result as ToolPackageExecutionResult.Succeeded
        assertTrue("replay must return the same rendered result: ${succeeded.resultJson}", succeeded.resultJson.contains("\"same\":true"))
        assertEquals(1, handler.requests.size)
    }

    @Test
    fun `a non-integer stable ordinal is refused through the real engine`() = runBlocking {
        val script = """
            function main(input) {
              return JSON.parse(host.call("workspace.read_text", "{\"path\":\"notes.txt\"}", "first"));
            }
        """.trimIndent()
        val bundle = bundleWith(script)

        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await() as ToolPackageExecutionResult.Failed

        assertEquals(ToolPackageErrorCode.HOST_CALL_ORDINAL_INVALID, result.error.code)
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun `a print flood is stopped by the output budget`() = runBlocking {
        val script = """
            function main(input) {
              for (var i = 0; i < 10000; i++) { print("0123456789"); }
              return { ok: true };
            }
        """.trimIndent()
        val bundle = bundleWith(script)

        val result = ToolPackageScriptRuntime(
            engine(),
            handler,
            ToolPackageRuntimeLimits(maxOutputBytes = 64)
        ).open(bundle).start(this, ToolpackFixtures.request(bundle)).await()

        assertEquals(
            ToolPackageErrorCode.EXECUTION_OUTPUT_LIMIT,
            (result as ToolPackageExecutionResult.Failed).error.code
        )
    }

    @Test
    fun `a host API the package did not declare is denied through the real engine`() = runBlocking {
        val script = """
            function main(input) {
              return JSON.parse(host.call("workspace.read_text", "{\"path\":\"notes.txt\"}"));
            }
        """.trimIndent()
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(hostApis = listOf(ToolPackageHostApis.MEMORY_SEARCH)),
            ToolpackFixtures.scripts(ToolpackFixtures.TOOL_ENTRY to script)
        )

        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await() as ToolPackageExecutionResult.Denied

        assertEquals(ToolPackageErrorCode.HOST_API_NOT_DECLARED, result.error.code)
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun `a write host call still requires an approval with the real engine`() = runBlocking {
        val script = """
            function main(input) {
              return JSON.parse(host.call("artifact.write_text",
                "{\"path\":\"out.txt\",\"content\":\"x\",\"expectedOldHash\":null}"));
            }
        """.trimIndent()
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(hostApis = listOf(ToolPackageHostApis.ARTIFACT_WRITE_TEXT)),
            ToolpackFixtures.scripts(ToolpackFixtures.TOOL_ENTRY to script)
        )

        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await() as ToolPackageExecutionResult.Denied

        assertEquals(ToolPackageErrorCode.HOST_API_WRITE_NOT_APPROVED, result.error.code)
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun `cancellation stops a real loop and reports cancelled`() = runBlocking {
        val bundle = bundleWith("function main(input) { while (true) { } }", timeoutMillis = 200)
        val execution = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
        val canceller = launch {
            delay(100)
            execution.cancel()
        }
        val result = execution.await()
        canceller.join()

        assertEquals(ToolPackageExecutionResult.Cancelled, result)
    }

    @Test
    fun `script errors never leak source or messages`() = runBlocking {
        val script = """
            function main(input) {
              throw new Error("secret /data/private token");
            }
        """.trimIndent()
        val bundle = bundleWith(script)

        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await() as ToolPackageExecutionResult.Failed

        assertEquals(ToolPackageErrorCode.ENGINE_FAILED, result.error.code)
        assertFalse(result.error.safeMessage.contains("secret"))
        assertFalse(result.error.safeMessage.contains("/data/private"))
    }

    @Test
    fun `a script without main is rejected safely`() = runBlocking {
        val bundle = bundleWith("var notATool = 1;")

        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await() as ToolPackageExecutionResult.Failed

        assertEquals(ToolPackageErrorCode.ENGINE_FAILED, result.error.code)
        assertTrue(result.error.safeMessage.contains("main"))
    }

    @Test
    fun `script arguments are parsed and non-object results stay typed failures`() = runBlocking {
        val echo = bundleWith("function main(input) { return { path: input.path }; }")
        val echoed = ToolPackageScriptRuntime(engine(), handler)
            .open(echo)
            .start(this, ToolpackFixtures.request(echo))
            .await() as ToolPackageExecutionResult.Succeeded
        assertEquals("""{"path":"notes.txt"}""", echoed.resultJson)

        val primitive = bundleWith("function main(input) { return 42; }")
        val rejected = ToolPackageScriptRuntime(engine(), handler)
            .open(primitive)
            .start(this, ToolpackFixtures.request(primitive))
            .await()
        assertEquals(
            ToolPackageErrorCode.EXECUTION_RESULT_INVALID,
            (rejected as ToolPackageExecutionResult.Failed).error.code
        )
    }
    @Test
    fun `relative require shares module exports`() = runBlocking {
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(),
            mapOf(
                ToolpackFixtures.MAIN_ENTRY to "// registration is not executed\n",
                ToolpackFixtures.TOOL_ENTRY to
                    "var math = require('../lib/math.js');\n" +
                    "function main(input) { return { value: math.double(2) }; }",
                "lib/math.js" to "exports.double = function (x) { return x * 2; };"
            )
        )
        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()
        assertTrue("unexpected result: $result", result is ToolPackageExecutionResult.Succeeded)
        val succeeded = result as ToolPackageExecutionResult.Succeeded
        assertEquals("""{"value":4}""", succeeded.resultJson)
    }

    @Test
    fun `module exports reassignment is honoured`() = runBlocking {
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(),
            mapOf(
                ToolpackFixtures.MAIN_ENTRY to "// registration is not executed\n",
                ToolpackFixtures.TOOL_ENTRY to
                    "var calc = require('../lib/calc.js');\n" +
                    "function main(input) { return { value: calc.triple(3) }; }",
                "lib/calc.js" to "module.exports = { triple: function (x) { return x * 3; } };"
            )
        )
        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()
        assertTrue("unexpected result: $result", result is ToolPackageExecutionResult.Succeeded)
        assertEquals("""{"value":9}""", (result as ToolPackageExecutionResult.Succeeded).resultJson)
    }

    @Test
    fun `cyclic require fails closed`() = runBlocking {
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(),
            mapOf(
                ToolpackFixtures.MAIN_ENTRY to "// registration is not executed\n",
                ToolpackFixtures.TOOL_ENTRY to "require('./lib/a.js'); function main(input) { return {}; }",
                "lib/a.js" to "require('./b.js'); exports.a = 1;",
                "lib/b.js" to "require('./a.js'); exports.b = 1;"
            )
        )
        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()
        assertTrue("unexpected result: $result", result is ToolPackageExecutionResult.Failed)
        assertEquals(ToolPackageErrorCode.ENGINE_FAILED, (result as ToolPackageExecutionResult.Failed).error.code)
    }

    @Test
    fun `require rejects paths outside the package`() = runBlocking {
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(),
            mapOf(
                ToolpackFixtures.MAIN_ENTRY to "// registration is not executed\n",
                ToolpackFixtures.TOOL_ENTRY to
                    "require('../../etc/passwd'); function main(input) { return {}; }"
            )
        )
        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()
        assertTrue("unexpected result: $result", result is ToolPackageExecutionResult.Failed)
        assertEquals(ToolPackageErrorCode.ENGINE_FAILED, (result as ToolPackageExecutionResult.Failed).error.code)
    }

    @Test
    fun `module metadata is visible while the module evaluates`() = runBlocking {
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(),
            mapOf(
                ToolpackFixtures.MAIN_ENTRY to "// registration is not executed\n",
                ToolpackFixtures.TOOL_ENTRY to
                    "var meta = require('./meta.js');\n" +
                    "function main(input) { return meta; }",
                "tools/meta.js" to
                    "module.exports = { id: module.id, filename: module.filename, loaded: module.loaded };"
            )
        )
        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()
        val json = org.json.JSONObject((result as ToolPackageExecutionResult.Succeeded).resultJson)
        assertEquals("tools/meta.js", json.getString("id"))
        assertEquals("tools/meta.js", json.getString("filename"))
        assertFalse(json.getBoolean("loaded"))
    }

    @Test
    fun `a module exception does not leak its message`() = runBlocking {
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(),
            mapOf(
                ToolpackFixtures.MAIN_ENTRY to "// registration is not executed\n",
                ToolpackFixtures.TOOL_ENTRY to
                    "require('./boom.js'); function main(input) { return {}; }",
                "tools/boom.js" to "throw new Error('secret-token-should-not-leak');"
            )
        )
        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()
        val failed = result as ToolPackageExecutionResult.Failed
        assertEquals("TOOL_FAILED", failed.error.code.agentCode)
        assertFalse(failed.error.safeMessage.contains("secret-token"))
    }

    @Test
    fun `a returned promise is awaited`() = runBlocking {
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(),
            mapOf(
                ToolpackFixtures.MAIN_ENTRY to "// registration is not executed\n",
                ToolpackFixtures.TOOL_ENTRY to
                    "function main(input) { return Promise.resolve({ value: 7 }); }"
            )
        )
        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()
        assertTrue("unexpected result: $result", result is ToolPackageExecutionResult.Succeeded)
        assertEquals("""{"value":7}""", (result as ToolPackageExecutionResult.Succeeded).resultJson)
    }

    @Test
    fun `a rejected promise fails without leaking its reason`() = runBlocking {
        val bundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(),
            mapOf(
                ToolpackFixtures.MAIN_ENTRY to "// registration is not executed\n",
                ToolpackFixtures.TOOL_ENTRY to
                    "function main(input) { return Promise.reject(new Error('secret-reason')); }"
            )
        )
        val result = ToolPackageScriptRuntime(engine(), handler)
            .open(bundle)
            .start(this, ToolpackFixtures.request(bundle))
            .await()
        val failed = result as ToolPackageExecutionResult.Failed
        assertEquals("TOOL_FAILED", failed.error.code.agentCode)
        assertFalse(failed.error.safeMessage.contains("secret-reason"))
    }


}

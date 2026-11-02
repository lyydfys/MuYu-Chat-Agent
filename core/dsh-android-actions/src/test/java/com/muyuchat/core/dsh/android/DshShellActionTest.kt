package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentDecision
import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DshShellActionTest {

    private val allowedKeys = setOf("ok", "exit_code", "stdout", "stderr", "error")

    private fun config(
        minimum: Long = 10L,
        maximum: Long = 100L,
        default: Long = 10L,
        stdoutLimit: Int = DshAndroidActionLimits.MAX_SHELL_STDOUT_CHARS,
        stderrLimit: Int = DshAndroidActionLimits.MAX_SHELL_STDERR_CHARS
    ) = DshShellActionConfig(
        minimumTimeoutMillis = minimum,
        maximumTimeoutMillis = maximum,
        defaultTimeoutMillis = default,
        maxConcurrentCommands = 1,
        stdoutLimit = stdoutLimit,
        stderrLimit = stderrLimit
    )

    private fun request(
        command: String = "id",
        timeoutMillis: Int? = null,
        token: String? = null,
        query: Map<String, String> = emptyMap(),
        method: DshCapabilityHttpMethod = DshCapabilityHttpMethod.POST
    ): DshCapabilityRouteRequest {
        val body = JSONObject().put("command", command)
        if (timeoutMillis != null) body.put("timeout_ms", timeoutMillis)
        if (token != null) body.put("token", token)
        return routeRequest(DshShellAction.ROUTE, method, query, body.toString())
    }

    private suspend fun handle(
        action: DshShellAction,
        request: DshCapabilityRouteRequest
    ): JSONObject = responseJson(action.handle(request))

    @Test
    fun allows_execution_only_after_an_allow_decision() = runTest {
        val runner = FakeShellRunner(
            result = DshShellRunResult(exitCode = 0, stdout = "uid=0(root)", stderr = "")
        )
        val consent = FakeConsent(decision = DshCapabilityConsentDecision.ALLOW)
        val channels = FakeChannels(root = true, shizuku = false)
        val response = handle(DshShellAction(channels, consent, runner, config()), request())

        assertTrue(response.getBoolean("ok"))
        assertEquals(0, response.getInt("exit_code"))
        assertEquals("uid=0(root)", response.getString("stdout"))
        assertOnlyKeys(response, allowedKeys)
        assertEquals(1, runner.invocations.size)
        assertEquals("id", runner.invocations.single().command)
        assertEquals(DshPrivilegedChannel.ROOT, runner.invocations.single().channel)
        assertEquals(1, consent.prompts.size)
        assertEquals(DshCapability.PRIVILEGED_SHELL, consent.prompts.single().capability)
    }

    @Test
    fun ignores_the_token_the_official_client_copies_into_the_body() = runTest {
        val runner = FakeShellRunner()
        val response = handle(
            DshShellAction(FakeChannels(), FakeConsent(), runner, config()),
            request(token = "dshc_0123456789abcdefghijklmnopqrstuvwxyzABCDE")
        )

        assertTrue(response.getBoolean("ok"))
        assertFalse(response.toString().contains("dshc_"))
        assertEquals("id", runner.invocations.single().command)
    }

    @Test
    fun denies_never_run_the_command() = runTest {
        val runner = FakeShellRunner()
        val consent = FakeConsent(decision = DshCapabilityConsentDecision.DENY)
        val response = handle(
            DshShellAction(FakeChannels(), consent, runner, config()),
            request(command = "rm -rf /data/local/tmp/x")
        )

        assertFalse(response.getBoolean("ok"))
        assertEquals(-1, response.getInt("exit_code"))
        assertEquals("CONSENT_DENIED", errorCodeOf(response))
        assertTrue(runner.invocations.isEmpty())
        assertOnlyKeys(response, allowedKeys)
    }

    @Test
    fun a_consent_timeout_never_runs_the_command() = runTest {
        val runner = FakeShellRunner()
        val consent = FakeConsent(decision = DshCapabilityConsentDecision.TIMEOUT)
        val response = handle(DshShellAction(FakeChannels(), consent, runner, config()), request())

        assertEquals("CONSENT_TIMEOUT", errorCodeOf(response))
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun a_broken_consent_port_fails_closed() = runTest {
        val runner = FakeShellRunner()
        val consent = FakeConsent(error = IllegalStateException("consent surface unavailable"))
        val response = handle(DshShellAction(FakeChannels(), consent, runner, config()), request())

        assertEquals("CONSENT_DENIED", errorCodeOf(response))
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun refuses_when_no_privileged_channel_exists() = runTest {
        val runner = FakeShellRunner()
        val consent = FakeConsent()
        val response = handle(
            DshShellAction(FakeChannels(root = false, shizuku = false), consent, runner, config()),
            request()
        )

        assertEquals("CAPABILITY_UNAVAILABLE", errorCodeOf(response))
        assertTrue(runner.invocations.isEmpty())
        assertTrue(consent.prompts.isEmpty())
    }

    @Test
    fun falls_back_to_shizuku_when_root_is_missing() = runTest {
        val runner = FakeShellRunner()
        val response = handle(
            DshShellAction(FakeChannels(root = false, shizuku = true), FakeConsent(), runner, config()),
            request()
        )

        assertTrue(response.getBoolean("ok"))
        assertEquals(DshPrivilegedChannel.SHIZUKU, runner.invocations.single().channel)
    }

    @Test
    fun a_deadline_kills_the_process_and_reports_a_timeout() = runTest {
        val runner = FakeShellRunner(behavior = FakeShellRunner.Behavior.HANG)
        val response = handle(
            DshShellAction(FakeChannels(), FakeConsent(), runner, config(10L, 100L, 10L)),
            request(timeoutMillis = 10)
        )

        assertEquals("TIMEOUT", errorCodeOf(response))
        assertEquals(-1, response.getInt("exit_code"))
        assertEquals(1, runner.killCount)
    }

    @Test
    fun cancellation_kills_the_process_and_propagates() = runTest {
        val runner = FakeShellRunner(behavior = FakeShellRunner.Behavior.HANG)
        val action = DshShellAction(FakeChannels(), FakeConsent(), runner, config(10L, 120_000L, 60_000L))
        val job = launch { action.handle(request(timeoutMillis = 60_000)) }
        runCurrent()
        assertEquals(1, runner.invocations.size)

        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(1, runner.killCount)
    }

    @Test
    fun a_second_concurrent_command_is_busy() = runTest {
        val runner = FakeShellRunner(behavior = FakeShellRunner.Behavior.HANG)
        val action = DshShellAction(FakeChannels(), FakeConsent(), runner, config(10L, 120_000L, 60_000L))
        val first = launch { action.handle(request(timeoutMillis = 60_000)) }
        runCurrent()

        val second = handle(action, request(timeoutMillis = 60_000))

        assertEquals("BUSY", errorCodeOf(second))
        assertEquals(1, runner.invocations.size)
        first.cancel()
        runCurrent()
    }

    @Test
    fun caps_oversized_stdout_and_states_the_truncation() = runTest {
        val runner = FakeShellRunner(
            result = DshShellRunResult(
                exitCode = 0,
                stdout = "x".repeat(9_000),
                stderr = "",
                stdoutTruncated = true
            )
        )
        val response = handle(
            DshShellAction(
                FakeChannels(),
                FakeConsent(),
                runner,
                config(stdoutLimit = 8_000)
            ),
            request()
        )

        val stdout = response.getString("stdout")
        assertTrue(stdout.startsWith("x".repeat(8_000)))
        assertTrue(stdout.contains("[stdout truncated at 8000 chars]"))
        assertOnlyKeys(response, allowedKeys)
    }

    @Test
    fun reports_a_non_zero_exit_code_as_a_failure() = runTest {
        val runner = FakeShellRunner(
            result = DshShellRunResult(exitCode = 2, stdout = "", stderr = "denied")
        )
        val response = handle(DshShellAction(FakeChannels(), FakeConsent(), runner, config()), request())

        assertFalse(response.getBoolean("ok"))
        assertEquals(2, response.getInt("exit_code"))
        assertEquals("denied", response.getString("stderr"))
        assertTrue(response.getString("error").isNotBlank())
    }

    @Test
    fun rejects_invalid_commands_without_asking_for_consent() = runTest {
        val runner = FakeShellRunner()
        val consent = FakeConsent()
        val action = DshShellAction(FakeChannels(), consent, runner, config())

        assertEquals("INVALID_REQUEST", errorCodeOf(handle(action, request(command = "   "))))
        assertEquals("INVALID_REQUEST", errorCodeOf(handle(action, request(command = "id\u0007"))))
        assertEquals(
            "TOO_LARGE",
            errorCodeOf(handle(action, request(command = "a".repeat(8_193))))
        )
        assertTrue(consent.prompts.isEmpty())
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun clamps_the_requested_timeout_to_the_wire_ceiling() = runTest {
        val runner = FakeShellRunner()
        handle(
            DshShellAction(FakeChannels(), FakeConsent(), runner, DshShellActionConfig()),
            request(timeoutMillis = 999_999)
        )
        assertEquals(
            DshAndroidActionLimits.MAX_SHELL_TIMEOUT_MILLIS,
            runner.invocations.single().timeoutMillis
        )
    }

    @Test
    fun refuses_get_and_a_query_on_post() = runTest {
        val action = DshShellAction(FakeChannels(), FakeConsent(), FakeShellRunner(), config())
        assertEquals(
            "METHOD_NOT_ALLOWED",
            errorCodeOf(handle(action, routeRequest(DshShellAction.ROUTE, DshCapabilityHttpMethod.GET)))
        )
        assertEquals(
            "UNEXPECTED_QUERY",
            errorCodeOf(handle(action, request(query = mapOf("command" to "id"))))
        )
    }
}

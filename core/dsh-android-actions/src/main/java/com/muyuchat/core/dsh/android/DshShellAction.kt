package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentDecision
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentPort
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentPrompt
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Limits and knobs of the `/shell` route. Tests shrink the timeouts; production uses the defaults. */
data class DshShellActionConfig(
    val minimumTimeoutMillis: Long = DshAndroidActionLimits.MIN_SHELL_TIMEOUT_MILLIS,
    val maximumTimeoutMillis: Long = DshAndroidActionLimits.MAX_SHELL_TIMEOUT_MILLIS,
    val defaultTimeoutMillis: Long = DshAndroidActionLimits.DEFAULT_SHELL_TIMEOUT_MILLIS,
    val maxConcurrentCommands: Int = DshAndroidActionLimits.MAX_CONCURRENT_SHELL_COMMANDS,
    val stdoutLimit: Int = DshAndroidActionLimits.MAX_SHELL_STDOUT_CHARS,
    val stderrLimit: Int = DshAndroidActionLimits.MAX_SHELL_STDERR_CHARS
) {
    init {
        require(minimumTimeoutMillis >= 1L) { "minimum shell timeout must be positive" }
        require(maximumTimeoutMillis <= DshAndroidActionLimits.MAX_SHELL_TIMEOUT_MILLIS) {
            "maximum shell timeout exceeds the wire ceiling"
        }
        require(minimumTimeoutMillis <= defaultTimeoutMillis && defaultTimeoutMillis <= maximumTimeoutMillis) {
            "default shell timeout must sit inside the configured range"
        }
        require(maxConcurrentCommands >= 1) { "at least one privileged command slot is required" }
        require(stdoutLimit in 1..DshAndroidActionLimits.MAX_SHELL_STDOUT_CHARS)
        require(stderrLimit in 1..DshAndroidActionLimits.MAX_SHELL_STDERR_CHARS)
    }
}

/**
 * `POST /shell` — `dsh-tool-shizuku`'s `shizuku_shell` (and the app-process Shizuku transport of
 * `dsh-tool-android`).
 *
 * Wire in: `{"command":"...","timeout_ms":30000,"token":"dshc_..."}`. The pinned client sends the
 * token only in the body for this route, so the server core's route-scoped body-token fallback
 * authenticates it before dispatch; this handler never reads, echoes or forwards the credential.
 * Wire out (exactly these keys):
 * `{"ok":true,"exit_code":0,"stdout":"..","stderr":".."}`; failures add `error` with the typed
 * `CODE: message` prefix and `exit_code:-1`.
 *
 * Invariants enforced here, all unit-tested without a device:
 *  - no channel (root or Shizuku) ⇒ CAPABILITY_UNAVAILABLE and the runner is never called;
 *  - consent must return [DshCapabilityConsentDecision.ALLOW]; DENY/TIMEOUT never run anything;
 *  - a second concurrent command is BUSY instead of queueing unbounded privileged work;
 *  - the deadline terminates the process (the handler always calls the handle before returning);
 *  - cancellation terminates the process and is rethrown, never swallowed;
 *  - stdout/stderr are capped at the wire limits and truncation is stated, not hidden.
 */
class DshShellAction(
    private val channels: DshPrivilegedChannelProvider,
    private val consent: DshCapabilityConsentPort,
    private val runner: DshPrivilegedShellRunner,
    private val config: DshShellActionConfig = DshShellActionConfig()
) : DshAndroidRoute() {

    override val path: String = ROUTE

    private val slot = Semaphore(config.maxConcurrentCommands)

    /** Projection input: a privileged tool may only be registered when a real channel exists. */
    fun state(): DshCapabilityState = when {
        DshShellPolicy.selectChannel(channels) != null -> DshCapabilityState.GRANTED
        else -> DshCapabilityState.DENIED
    }

    override suspend fun executeTyped(
        request: DshCapabilityRouteRequest
    ): DshCapabilityRouteResponse {
        DshAndroidEnvelope.requirePost(request, ROUTE)
        val body = DshAndroidEnvelope.body(request)

        val command = DshShellPolicy.sanitizeCommand(
            DshAndroidWire.requireString(
                body,
                "command",
                DshAndroidActionLimits.MAX_SHELL_COMMAND_CHARS
            )
        )
        val timeoutMillis = resolveTimeout(DshAndroidWire.optionalInt(body, "timeout_ms"))

        val channel = DshShellPolicy.selectChannel(channels)
            ?: return failure(
                DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE,
                "未授予 root 或 Shizuku 特权通道，无法执行特权命令"
            )

        when (requestConsentDecision(command)) {
            DshCapabilityConsentDecision.ALLOW -> Unit
            DshCapabilityConsentDecision.DENY -> return failure(
                DshAndroidActionErrorCode.CONSENT_DENIED,
                "用户拒绝了本次特权命令，未执行任何内容"
            )
            DshCapabilityConsentDecision.TIMEOUT -> return failure(
                DshAndroidActionErrorCode.CONSENT_TIMEOUT,
                "等待用户确认超时，未执行任何内容"
            )
        }

        if (!slot.tryAcquire()) {
            return failure(DshAndroidActionErrorCode.BUSY, "已有特权命令在执行，请稍后重试")
        }
        try {
            val invocation = DshShellInvocation(
                command = command,
                timeoutMillis = timeoutMillis,
                channel = channel,
                stdoutLimit = config.stdoutLimit,
                stderrLimit = config.stderrLimit
            )
            return execute(invocation)
        } finally {
            slot.release()
        }
    }

    private suspend fun requestConsentDecision(command: String): DshCapabilityConsentDecision {
        val prompt = DshCapabilityConsentPrompt(
            title = "执行特权命令？",
            detail = DshShellPolicy.consentDetail(command),
            capability = DshCapability.PRIVILEGED_SHELL
        )
        return try {
            consent.requestConsent(prompt)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // A consent surface that cannot prompt fails closed: nothing is executed.
            DshCapabilityConsentDecision.DENY
        }
    }

    private suspend fun execute(invocation: DshShellInvocation): DshCapabilityRouteResponse {
        val handleRef = AtomicReference<DshShellHandle?>(null)
        val result = try {
            withTimeoutOrNull(invocation.timeoutMillis) {
                runner.run(invocation) { handle -> handleRef.set(handle) }
            }
        } catch (cancel: CancellationException) {
            handleRef.get()?.kill()
            throw cancel
        }

        if (result == null) {
            // withTimeoutOrNull cancelled the runner; terminate the process before answering.
            handleRef.get()?.kill()
            return failure(
                DshAndroidActionErrorCode.TIMEOUT,
                "命令超时（${invocation.timeoutMillis}ms）被强制终止"
            )
        }

        val reportedExit = if (result.failure == null) result.exitCode else -1
        val body = JSONObject()
            .put("ok", result.ok)
            .put("exit_code", reportedExit)
            .put(
                "stdout",
                withTruncationNote(result.stdout, result.stdoutTruncated, invocation.stdoutLimit, "stdout")
            )
            .put(
                "stderr",
                withTruncationNote(result.stderr, result.stderrTruncated, invocation.stderrLimit, "stderr")
            )
        result.failure?.let { body.put("error", it.wireMessage) }
        if (result.failure == null && !result.ok) {
            body.put("error", "命令退出码 " + reportedExit)
        }
        return DshAndroidWire.response(body)
    }

    private fun withTruncationNote(
        text: String,
        truncated: Boolean,
        limit: Int,
        field: String
    ): String = if (truncated) "$text\n[$field truncated at $limit chars]" else text

    private fun resolveTimeout(rawTimeoutMillis: Int?): Long =
        DshShellPolicy.clampTimeoutMillis(
            rawTimeoutMillis = rawTimeoutMillis,
            minimum = config.minimumTimeoutMillis,
            maximum = config.maximumTimeoutMillis,
            default = config.defaultTimeoutMillis
        )

    private fun failure(
        code: DshAndroidActionErrorCode,
        message: String
    ): DshCapabilityRouteResponse =
        DshAndroidWire.response(failureBody(DshAndroidActionFailure.of(code, message)))

    override fun failureBody(failure: DshAndroidActionFailure): JSONObject = JSONObject()
        .put("ok", false)
        .put("exit_code", -1)
        .put("stdout", "")
        .put("stderr", "")
        .put("error", failure.wireMessage)

    companion object {
        const val ROUTE = "/shell"
    }
}

package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentDecision
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentPort
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentPrompt
import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

// ── request / response helpers ────────────────────────────────────────────────────────────────

internal fun routeRequest(
    path: String,
    method: DshCapabilityHttpMethod = DshCapabilityHttpMethod.POST,
    query: Map<String, String> = emptyMap(),
    body: String? = null,
    token: String? = null
): DshCapabilityRouteRequest = DshCapabilityRouteRequest(
    path = path,
    method = method,
    query = query,
    bodyJson = body,
    localToken = token,
    generation = 1L,
    receivedAtMillis = 1_000L
)

internal fun jsonBody(vararg pairs: Pair<String, Any?>): String {
    val json = JSONObject()
    pairs.forEach { (key, value) -> if (value != null) json.put(key, value) }
    return json.toString()
}

internal fun responseJson(response: DshCapabilityRouteResponse): JSONObject {
    check(response is DshCapabilityRouteResponse.Json) { "expected a JSON route response, got $response" }
    return JSONObject(response.bodyJson)
}

/** The stable machine code of a route refusal: the `error` text before the first colon. */
internal fun errorCodeOf(json: JSONObject): String =
    json.optString("error").substringBefore(':').trim()

/**
 * Every route body must stay inside the pinned official tool output schema
 * (`additionalProperties: false`); an extra key makes DSH reject the result as illegal.
 */
internal fun assertOnlyKeys(json: JSONObject, allowed: Set<String>) {
    val keys = mutableSetOf<String>()
    val iterator = json.keys()
    while (iterator.hasNext()) keys += iterator.next()
    val extra = keys - allowed
    assertEquals("unexpected response keys $extra in $json", emptySet<String>(), extra)
}

/** JUnit 4.13 `assertThrows` for inline reified types across the module's own exception classes. */
internal inline fun <reified T : Throwable> assertThrowsType(block: () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw error
    }
    throw AssertionError("expected ${T::class.java.simpleName} to be thrown")
}

// ── fake ports ────────────────────────────────────────────────────────────────────────────────

internal class FakeNotificationPort(
    var state: DshCapabilityState = DshCapabilityState.GRANTED
) : DshNotificationPort {

    val posts = mutableListOf<Triple<String, String, Int>>()
    val cancelled = mutableListOf<Int>()
    var cancelAllResult: Int = 0
    var failure: DshAndroidActionFailure? = null

    override fun state(): DshCapabilityState = state

    override suspend fun post(
        title: String,
        text: String,
        notificationId: Int
    ): DshNotificationPostOutcome {
        posts += Triple(title, text, notificationId)
        failure?.let { return DshNotificationPostOutcome.Failed(it) }
        return DshNotificationPostOutcome.Posted(notificationId)
    }

    override suspend fun cancel(notificationId: Int): Boolean {
        cancelled += notificationId
        return true
    }

    override suspend fun cancelAll(): Int = cancelAllResult
}

internal class FakeOverlayPort(
    var granted: Boolean = true,
    var state: DshCapabilityState = DshCapabilityState.GRANTED,
    var visible: Boolean = false,
    var running: Boolean = false,
    var engineUp: Boolean = false,
    var visibleAfterShow: Boolean = true
) : DshOverlayPort {

    val calls = mutableListOf<String>()

    override fun state(): DshCapabilityState = state

    override suspend fun snapshot(): DshOverlaySnapshot = snapshot(null)

    override suspend fun show(): DshOverlaySnapshot {
        calls += "show"
        visible = visibleAfterShow && granted
        return snapshot(null)
    }

    override suspend fun hide(): DshOverlaySnapshot {
        calls += "hide"
        visible = false
        return snapshot(null)
    }

    private fun snapshot(message: String?): DshOverlaySnapshot = DshOverlaySnapshot(
        running = running,
        engineUp = engineUp,
        granted = granted,
        visible = visible && granted,
        message = message
    )
}

internal class FakeUsagePort(
    var accessState: DshCapabilityState = DshCapabilityState.GRANTED,
    var apps: List<DshUsageApp> = emptyList(),
    var failure: DshAndroidActionFailure? = null
) : DshUsageStatsPort {

    val queries = mutableListOf<Int>()

    override fun access(): DshCapabilityState = accessState

    override suspend fun query(days: Int): DshUsageQueryOutcome {
        queries += days
        failure?.let { return DshUsageQueryOutcome.Unavailable(it) }
        return DshUsageQueryOutcome.Loaded(apps)
    }
}

internal class FakeClipboardPort(
    var state: DshCapabilityState = DshCapabilityState.GRANTED,
    var readOutcome: DshClipboardReadOutcome = DshClipboardReadOutcome.Empty,
    var writeFailure: DshAndroidActionFailure? = null
) : DshClipboardPort {

    val writes = mutableListOf<Pair<String, Boolean>>()

    override fun state(): DshCapabilityState = state

    override suspend fun read(): DshClipboardReadOutcome = readOutcome

    override suspend fun write(text: String, sensitive: Boolean): DshAndroidActionFailure? {
        writes += text to sensitive
        return writeFailure
    }
}

internal class FakeSettingsPort(
    var accessState: DshCapabilityState = DshCapabilityState.GRANTED,
    var outcome: DshSettingWriteOutcome = DshSettingWriteOutcome("old", "new"),
    var rejection: DshAndroidActionFailure? = null
) : DshAppSettingsPort {

    val accessQueries = mutableListOf<String>()
    val writes = mutableListOf<Pair<String, String>>()

    override fun writeAccess(key: String): DshCapabilityState {
        accessQueries += key
        return accessState
    }

    override suspend fun read(key: String): String? = null

    override suspend fun write(key: String, value: String): DshSettingWriteResult {
        writes += key to value
        rejection?.let { return DshSettingWriteResult.Rejected(it) }
        return DshSettingWriteResult.Written(outcome)
    }
}

internal class FakeChannels(
    var root: Boolean = true,
    var shizuku: Boolean = false
) : DshPrivilegedChannelProvider {

    override fun rootAvailable(): Boolean = root

    override fun shizukuAvailable(): Boolean = shizuku
}

internal class FakeConsent(
    var decision: DshCapabilityConsentDecision = DshCapabilityConsentDecision.ALLOW,
    var error: Exception? = null
) : DshCapabilityConsentPort {

    val prompts = mutableListOf<DshCapabilityConsentPrompt>()

    override suspend fun requestConsent(
        prompt: DshCapabilityConsentPrompt
    ): DshCapabilityConsentDecision {
        prompts += prompt
        error?.let { throw it }
        return decision
    }
}

internal class FakeShellRunner(
    var result: DshShellRunResult = DshShellRunResult(exitCode = 0, stdout = "", stderr = ""),
    var behavior: Behavior = Behavior.RETURN
) : DshPrivilegedShellRunner {

    enum class Behavior { RETURN, HANG }

    val invocations = mutableListOf<DshShellInvocation>()
    var killCount: Int = 0

    override suspend fun run(
        invocation: DshShellInvocation,
        onStarted: (DshShellHandle) -> Unit
    ): DshShellRunResult {
        invocations += invocation
        onStarted(DshShellHandle { killCount += 1 })
        return when (behavior) {
            Behavior.RETURN -> result
            Behavior.HANG -> awaitCancellation()
        }
    }
}

// ── fake processes for the root runner ────────────────────────────────────────────────────────

internal class RecordingProcessStarter(
    private val process: Process,
    private val startFailure: IOException? = null
) : DshShellProcessStarter {

    var argv: List<String>? = null
    var environment: Map<String, String>? = null

    override fun start(argv: List<String>, environment: Map<String, String>): Process {
        this.argv = argv
        this.environment = environment
        startFailure?.let { throw it }
        return process
    }
}

/** A process whose streams are already complete, so the runner can be exercised without an OS. */
internal class FakeProcess(
    stdoutText: String = "",
    stderrText: String = "",
    private val exitCode: Int = 0
) : Process() {

    @Volatile
    var destroyed: Boolean = false
        private set

    private val stdoutStream: InputStream = ByteArrayInputStream(stdoutText.toByteArray())
    private val stderrStream: InputStream = ByteArrayInputStream(stderrText.toByteArray())

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = stdoutStream

    override fun getErrorStream(): InputStream = stderrStream

    override fun waitFor(): Int = exitCode

    override fun exitValue(): Int = exitCode

    override fun destroy() {
        destroyed = true
    }

    override fun destroyForcibly(): Process {
        destroyed = true
        return this
    }

    override fun isAlive(): Boolean = !destroyed
}

/** A process whose stdout never ends until it is killed: the timeout/kill path of the runner. */
internal class BlockingProcess : Process() {

    private val source = PipedOutputStream()
    private val sink = PipedInputStream(source)

    @Volatile
    var destroyed: Boolean = false
        private set

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = sink

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = if (destroyed) -1 else 0

    override fun exitValue(): Int = if (destroyed) -1 else 0

    override fun destroy() {
        destroyed = true
        runCatching { sink.close() }
        runCatching { source.close() }
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }

    override fun isAlive(): Boolean = !destroyed
}

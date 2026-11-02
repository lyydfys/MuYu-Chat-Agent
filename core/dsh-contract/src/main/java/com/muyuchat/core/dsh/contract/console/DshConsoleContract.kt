package com.muyuchat.core.dsh.contract.console

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmissionSnapshot

/**
 * Read-only projection contract of the DSH-only host console (completion-v2 D20).
 *
 * The console is a projection, never a second writer. Every field below is a snapshot of state
 * owned elsewhere: the runtime owner owns phase/epoch/stop facts, the capability owners own each
 * family, the maintenance owner owns the admission snapshot, and the diagnostics collector owns
 * the redacted lines. Buttons do not mutate those objects; they submit a [DshConsoleIntent] to an
 * injected [DshConsolePort], and only that owner may act.
 *
 * Nothing here carries a capability token, launch URL, cookie, account credential, prompt text or
 * raw device path. Diagnostic lines are supplied already redacted by their owner; this contract
 * only bounds them.
 */
data class DshConsoleRuntimeProjection(
    val phase: DshRuntimePhase,
    /** Runtime generation (host epoch). Zero means no generation has been created yet. */
    val generation: Long,
    /** Loopback origin of the live generation, no path/query/token; null when not ready. */
    val origin: String?,
    /** The runtime owner's own stop fact: a user/system stop is fenced and must not auto-restart. */
    val stopRequested: Boolean,
    /** The WebView first visible frame was observed for this generation. */
    val firstFrameRendered: Boolean,
    /** Stable failure code name when the host failed; never a message, path or stack. */
    val failureCode: String?,
    /** Model/tool binding is ready for new operations in this generation. */
    val modelBound: Boolean
) {
    init {
        require(generation >= 0L) { "console generation must be non-negative" }
        require(origin == null || isLoopbackOrigin(origin)) {
            "console origin must be a bare loopback origin"
        }
        require(failureCode == null || isPromptFreeToken(failureCode, DshConsoleLimits.MAX_CODE_CHARS)) {
            "console failure code must be a short stable token"
        }
    }
}

/** One capability family as the console shows it. */
data class DshConsoleCapabilityProjection(
    val capability: DshCapability,
    val state: DshCapabilityState,
    /** True only when DSH actually registered the matching tool for this generation. */
    val toolRegistered: Boolean,
    /** Owner-provided prompt-free explanation; null when the state is self-explanatory. */
    val detail: String? = null
) {
    init {
        require(detail == null || isPromptFreeLine(detail, DshConsoleLimits.MAX_DETAIL_CHARS)) {
            "capability detail must be a bounded prompt-free line"
        }
    }
}

/** Maintenance admission + journal projection owned by the data/maintenance lane (D22a). */
data class DshConsoleMaintenanceProjection(
    val admission: DshMaintenanceAdmissionSnapshot,
    /** Last journal transition as a short stable token (for example `BACKUP_STAGING`). */
    val journalHeadline: String? = null,
    /** Number of journal entries retained by the maintenance owner; no contents are copied here. */
    val journalEntries: Int = 0
) {
    init {
        require(journalHeadline == null || isPromptFreeToken(journalHeadline, DshConsoleLimits.MAX_CODE_CHARS)) {
            "maintenance journal headline must be a short stable token"
        }
        require(journalEntries >= 0) { "journal entry count must be non-negative" }
    }
}

/**
 * Bounded, already-redacted diagnostics summary. The feature never reads runtime logs or files
 * itself; it only renders what the diagnostics owner supplies.
 */
data class DshConsoleDiagnosticsProjection(
    val webViewGeneration: Long = 0L,
    val rendererRestarts: Int = 0,
    val lastErrorCode: String? = null,
    val redactedLines: List<String> = emptyList()
) {
    init {
        require(webViewGeneration >= 0L) { "WebView generation must be non-negative" }
        require(rendererRestarts >= 0) { "renderer restart count must be non-negative" }
        require(lastErrorCode == null || isPromptFreeToken(lastErrorCode, DshConsoleLimits.MAX_CODE_CHARS)) {
            "diagnostics error code must be a short stable token"
        }
        require(redactedLines.size <= DshConsoleLimits.MAX_DIAGNOSTIC_LINES) {
            "diagnostics must stay within ${DshConsoleLimits.MAX_DIAGNOSTIC_LINES} lines"
        }
        require(redactedLines.all { isPromptFreeLine(it, DshConsoleLimits.MAX_DIAGNOSTIC_LINE_CHARS) }) {
            "diagnostic lines must be bounded prompt-free single lines"
        }
    }
}

/**
 * Official DSH surfaces the console may link to. The console does not re-implement sessions,
 * plugins, settings, approvals or account pages; it asks the owner to navigate the official UI.
 */
enum class DshConsoleOfficialSurface {
    SETTINGS,
    ACCOUNT,
    PLUGINS,
    SESSIONS,
    BROWSER,
    VIRTUAL_SCREEN,
    MAINTENANCE,
    DIAGNOSTICS
}

/** Immutable projection of the whole console at one point in time. */
data class DshConsoleSnapshot(
    /** Monotonic projection revision owned by the port implementation. */
    val revision: Long,
    val capturedAtMillis: Long,
    val runtime: DshConsoleRuntimeProjection,
    val capabilities: List<DshConsoleCapabilityProjection> = emptyList(),
    /**
     * Typed consent state of the app-owned screen capture that the virtual-screen family needs.
     * It is projected by the app composition owner (never a cached platform boolean): the system
     * dialog itself is launched by the host Activity, which needs visible user presence.
     */
    val screenCapture: DshScreenCaptureConsentState = DshScreenCaptureConsentState(),
    /**
     * Host-side account attempt projection (bounded, credential-free). It never claims a login:
     * see [DshConsoleAccountProjection]. Default = no source injected into this composition.
     */
    val account: DshConsoleAccountProjection = DshConsoleAccountProjection(),
    val maintenance: DshConsoleMaintenanceProjection = DshConsoleMaintenanceProjection(
        DshMaintenanceAdmissionSnapshot(
            barrierActive = false,
            mutationLeaseCount = 0,
            readLeaseCount = 0
        )
    ),
    val diagnostics: DshConsoleDiagnosticsProjection = DshConsoleDiagnosticsProjection(),
    /**
     * Shell-confinement fact of this generation. Default is "not probed"; it must never default to
     * enforced. The console renders high-trust execution when [DshConsoleShellSandboxProjection.enforced]
     * is false, so a kernel without Landlock cannot be presented as workspace isolation.
     */
    val shellSandbox: DshConsoleShellSandboxProjection = DshConsoleShellSandboxProjection(),
    val officialSurfaces: Set<DshConsoleOfficialSurface> = emptySet()
) {
    init {
        require(revision >= 0L) { "console revision must be non-negative" }
        require(capturedAtMillis >= 0L) { "console capture time must be non-negative" }
        require(capabilities.distinctBy { it.capability }.size == capabilities.size) {
            "console capabilities must not repeat a family"
        }
    }
}

/**
 * Typed console intents. The console never performs the effect itself; each intent is submitted to
 * [DshConsolePort.execute], which is implemented by the owner that actually holds the resource.
 */
sealed interface DshConsoleIntent {
    /** Re-project only; never writes runtime, model, session or plugin state. */
    data object Refresh : DshConsoleIntent

    /** Navigate the official DSH UI to an existing surface instead of re-implementing it. */
    data class OpenOfficialSurface(val surface: DshConsoleOfficialSurface) : DshConsoleIntent

    /** Ask the runtime owner to stop the current generation (owner still publishes stop facts). */
    data object StopRuntime : DshConsoleIntent

    /** Ask the runtime owner for a new generation after a failed/stopped host. */
    data object RestartRuntime : DshConsoleIntent

    /**
     * Ask the app consent owner to run the system MediaProjection consent flow for the virtual
     * screen. The owner may only mark the request; the visible host Activity launches the dialog
     * and hands its result back, so this intent never impersonates user presence.
     */
    data object RequestScreenCapture : DshConsoleIntent

    /** Ask the maintenance owner for the exclusive write barrier (D22a); it may refuse. */
    data object AcquireMaintenanceBarrier : DshConsoleIntent

    /** Release only a barrier previously granted to this console session. */
    data object ReleaseMaintenanceBarrier : DshConsoleIntent

    /** Ask the diagnostics owner to export its redacted bundle through its own SAF flow. */
    data object ExportDiagnostics : DshConsoleIntent
}

/** Why an intent was refused before reaching its owner. */
enum class DshConsoleRejection {
    HOST_NOT_READY,
    HOST_BUSY,
    STOP_REQUESTED,
    MAINTENANCE_ACTIVE,
    MAINTENANCE_NOT_HELD,
    SURFACE_UNAVAILABLE,
    NOT_AVAILABLE,

    /** The virtual-screen family is not registered, so a capture grant could not be used. */
    SCREEN_CAPTURE_UNAVAILABLE,

    /** A real projection is already granted for this app process. */
    SCREEN_CAPTURE_ALREADY_GRANTED,

    /** A consent request is already waiting for the system dialog or the user. */
    SCREEN_CAPTURE_PENDING,
    USER_PRESENCE_REQUIRED,
    UNKNOWN_INTENT
}

/**
 * Result of an intent. [Applied] and [Pending] mean the owner accepted it; [Rejected] and [Failed]
 * mean nothing changed. A console must never report an effect that the owner did not confirm.
 */
sealed interface DshConsoleIntentResult {
    data class Applied(val message: String? = null) : DshConsoleIntentResult {
        init {
            require(message == null || isPromptFreeLine(message, DshConsoleLimits.MAX_DETAIL_CHARS))
        }
    }

    data class Pending(val message: String? = null) : DshConsoleIntentResult {
        init {
            require(message == null || isPromptFreeLine(message, DshConsoleLimits.MAX_DETAIL_CHARS))
        }
    }

    data class Rejected(
        val reason: DshConsoleRejection,
        val message: String? = null
    ) : DshConsoleIntentResult {
        init {
            require(message == null || isPromptFreeLine(message, DshConsoleLimits.MAX_DETAIL_CHARS))
        }
    }

    data class Failed(
        val code: String,
        val retryable: Boolean,
        val message: String? = null
    ) : DshConsoleIntentResult {
        init {
            require(isPromptFreeToken(code, DshConsoleLimits.MAX_CODE_CHARS)) { "failure code must be a stable token" }
            require(message == null || isPromptFreeLine(message, DshConsoleLimits.MAX_DETAIL_CHARS))
        }
    }
}

/**
 * The only way the console talks to the host. Implementations live in the app composition root and
 * delegate to the existing owners (runtime/service stop policy, capability owners, maintenance
 * owner, diagnostics). The console feature never writes those states directly.
 */
interface DshConsolePort {
    /** Current read-only projection. Must not mutate any owner state. */
    suspend fun snapshot(): DshConsoleSnapshot

    /** Submits one typed intent to the owning implementation. */
    suspend fun execute(intent: DshConsoleIntent): DshConsoleIntentResult
}

/** Bounds shared by the contract and the console presentation. */
object DshConsoleLimits {
    const val MAX_DETAIL_CHARS = 200
    const val MAX_CODE_CHARS = 48
    const val MAX_DIAGNOSTIC_LINES = 8
    const val MAX_DIAGNOSTIC_LINE_CHARS = 200
}

private fun isLoopbackOrigin(value: String): Boolean {
    val match = LOOPBACK_ORIGIN.matchEntire(value) ?: return false
    val port = match.groupValues[1].toIntOrNull() ?: return false
    return port in 1..65_535
}

internal fun isPromptFreeToken(value: String, maxChars: Int): Boolean =
    value.isNotEmpty() && value.length <= maxChars && value.none(::isControlCharacter)

internal fun isPromptFreeLine(value: String, maxChars: Int): Boolean =
    value.isNotEmpty() && value.length <= maxChars && value.none(::isControlCharacter)

private fun isControlCharacter(character: Char): Boolean =
    character.code < 0x20 || character.code in 0x7F..0x9F

private val LOOPBACK_ORIGIN = Regex("""http://127\.0\.0\.1:(\d{1,5})""")

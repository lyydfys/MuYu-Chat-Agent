package com.muyuchat.core.dsh.android

/**
 * Privileged shell contract used by `POST /shell` (`dsh-tool-shizuku`'s `shizuku_shell` and the
 * privileged half of `dsh-tool-android`).
 *
 * The command is deliberately one arbitrary shell command line: that is the official tool's
 * declared purpose, and MCA reports the broad access to the user through a consent prompt instead
 * of pretending a token filter is a sandbox. What MCA does enforce is: no execution without an
 * explicit `ALLOW` from the consent port, one command at a time, a bounded deadline, a bounded
 * output and a process group kill on timeout/cancel.
 *
 * This lane does not bind Shizuku itself. It exposes [DshPrivilegedChannelProvider] and a runner
 * port; the app composition (or the Shizuku bridge lane) supplies the real transport and this lane
 * degrades honestly when no channel is granted.
 */

/** The two privileged transports of the pinned reference, in its own priority order. */
enum class DshPrivilegedChannel(val displayName: String) {
    /** `su`/root: `spawn("su", ["-c", command])`. */
    ROOT("root(su)"),

    /** Shizuku: the App-process Shizuku API (preferred) or the `rish` fallback. */
    SHIZUKU("shizuku")
}

/**
 * Reports which privileged channels the device/app currently has.
 *
 * Implementations come from the app composition: the root probe is a real `su` check, and the
 * Shizuku half is owned by the Shizuku bridge, never guessed from a stale flag. The projection maps
 * these booleans onto capability states so DSH only registers a privileged tool when one is real.
 */
interface DshPrivilegedChannelProvider {
    fun rootAvailable(): Boolean

    fun shizukuAvailable(): Boolean

    /** The reference prefers root and falls back to Shizuku, then `rish`. */
    fun preferredChannel(): DshPrivilegedChannel? = when {
        rootAvailable() -> DshPrivilegedChannel.ROOT
        shizukuAvailable() -> DshPrivilegedChannel.SHIZUKU
        else -> null
    }
}

/** One fully validated privileged invocation; construction is the last place limits are applied. */
data class DshShellInvocation(
    val command: String,
    val timeoutMillis: Long,
    val channel: DshPrivilegedChannel,
    val stdoutLimit: Int = DshAndroidActionLimits.MAX_SHELL_STDOUT_CHARS,
    val stderrLimit: Int = DshAndroidActionLimits.MAX_SHELL_STDERR_CHARS
) {
    init {
        require(command.isNotBlank()) { "privileged shell command must not be blank" }
        require(command.length <= DshAndroidActionLimits.MAX_SHELL_COMMAND_CHARS) {
            "privileged shell command exceeds the wire limit"
        }
        require(timeoutMillis in 1L..DshAndroidActionLimits.MAX_SHELL_TIMEOUT_MILLIS) {
            "privileged shell timeout is out of the wire range"
        }
        require(stdoutLimit in 1..DshAndroidActionLimits.MAX_SHELL_STDOUT_CHARS) {
            "stdout limit is out of range"
        }
        require(stderrLimit in 1..DshAndroidActionLimits.MAX_SHELL_STDERR_CHARS) {
            "stderr limit is out of range"
        }
    }
}

/**
 * Handle to one started privileged process. [kill] is idempotent and must return quickly: the
 * handler calls it on timeout/cancel before it writes the terminal body.
 */
fun interface DshShellHandle {
    fun kill()
}

/** Terminal outcome of one privileged execution. [failure] is set only for infrastructure failures. */
data class DshShellRunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val failure: DshAndroidActionFailure? = null
) {
    val ok: Boolean
        get() = failure == null && exitCode == 0
}

/**
 * Executes exactly one [DshShellInvocation].
 *
 * The runner reports the process handle through [onStarted] before it drains output, so the
 * handler can terminate a hung command. It must return promptly after the process dies or its
 * streams close; it must not retry, interpret or re-quote the command.
 */
interface DshPrivilegedShellRunner {
    suspend fun run(
        invocation: DshShellInvocation,
        onStarted: (DshShellHandle) -> Unit
    ): DshShellRunResult
}

/**
 * Pure policy of the privileged shell route: command validation, timeout clamping, channel
 * selection, child environment filtering and the capped output buffer. No Android type, no I/O.
 */
object DshShellPolicy {

    /** Variables forced out of the child environment by the pinned reference (`sanitizeEnv`). */
    private val DROPPED_ENVIRONMENT_KEYS = setOf("LD_LIBRARY_PATH", "LD_PRELOAD", "LD_DEBUG")

    /**
     * Environment key fragments that identify a credential. The app process must never hand the
     * capability token (or any other secret) to a privileged child, and the child's environment is
     * a place a careless log or `env` dump would surface it.
     */
    private val SECRET_ENVIRONMENT_MARKERS = listOf(
        "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "PRIVATE_KEY", "API_KEY", "AUTH_SOCK"
    )

    /** Characters allowed in a privileged command: tab/newline/CR plus everything printable. */
    fun sanitizeCommand(raw: String?): String {
        val trimmed = raw?.replace("\r\n", "\n")?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            throw badRequest(DshAndroidActionErrorCode.INVALID_REQUEST, "command 不能为空")
        }
        if (trimmed.length > DshAndroidActionLimits.MAX_SHELL_COMMAND_CHARS) {
            throw badRequest(
                DshAndroidActionErrorCode.TOO_LARGE,
                "command 超过 ${DshAndroidActionLimits.MAX_SHELL_COMMAND_CHARS} 字符上限"
            )
        }
        trimmed.forEach { character ->
            val allowed = character == '\n' || character == '\t' || !isForbiddenFailureCharacter(character)
            if (!allowed) {
                throw badRequest(
                    DshAndroidActionErrorCode.INVALID_REQUEST,
                    "command 含不允许的控制字符"
                )
            }
        }
        return trimmed
    }

    /** Mirrors both official packages: `max(1000, min(timeout_ms || 30000, 120000))`. */
    fun clampTimeoutMillis(
        rawTimeoutMillis: Int?,
        minimum: Long = DshAndroidActionLimits.MIN_SHELL_TIMEOUT_MILLIS,
        maximum: Long = DshAndroidActionLimits.MAX_SHELL_TIMEOUT_MILLIS,
        default: Long = DshAndroidActionLimits.DEFAULT_SHELL_TIMEOUT_MILLIS
    ): Long {
        if (rawTimeoutMillis == null || rawTimeoutMillis <= 0) return default
        return rawTimeoutMillis.toLong().coerceIn(minimum, maximum)
    }

    fun selectChannel(provider: DshPrivilegedChannelProvider): DshPrivilegedChannel? =
        provider.preferredChannel()

    /**
     * Builds the child environment: everything the app has, minus the linker variables the system
     * `app_process` needs to keep, minus every value that looks like a credential.
     */
    fun childEnvironment(base: Map<String, String>): Map<String, String> = buildMap {
        base.forEach { (key, value) ->
            if (key in DROPPED_ENVIRONMENT_KEYS) return@forEach
            val upper = key.uppercase()
            if (SECRET_ENVIRONMENT_MARKERS.any(upper::contains)) return@forEach
            put(key, value)
        }
    }

    /**
     * Prompt detail: one prompt-free line of at most 300 characters. The full command is what runs,
     * but the consent card must stay readable; the truncation is explicit, never silent.
     */
    fun consentDetail(command: String): String {
        val singleLine = buildString {
            var pendingSpace = false
            command.forEach { character ->
                if (character == '\n' || character == '\t' || isForbiddenFailureCharacter(character)) {
                    pendingSpace = isNotEmpty()
                } else {
                    if (pendingSpace) append(' ')
                    pendingSpace = false
                    append(character)
                }
            }
        }
        val limit = DshAndroidActionLimits.MAX_STATUS_DETAIL_CHARS
        val prefix = "特权命令："
        if (prefix.length + singleLine.length <= limit) return prefix + singleLine
        val room = (limit - prefix.length - 1).coerceAtLeast(1)
        var head = singleLine.take(room)
        if (head.isNotEmpty() && head.last().isHighSurrogate()) head = head.dropLast(1)
        return prefix + head + "…"
    }
}

/**
 * Captures at most [limit] characters and keeps draining the stream afterwards, so a process that
 * writes megabytes cannot block on a full pipe while the captured output stays bounded.
 */
class DshShellOutputBuffer(private val limit: Int) {
    init {
        require(limit > 0) { "output buffer limit must be positive" }
    }

    private val builder = StringBuilder(minOf(limit, 4_096))
    private var droppedChars: Long = 0

    val capturedChars: Int
        get() = builder.length

    val truncated: Boolean
        get() = droppedChars > 0

    val dropped: Long
        get() = droppedChars

    fun append(chunk: CharSequence) {
        val room = limit - builder.length
        if (room <= 0) {
            droppedChars += chunk.length
            return
        }
        if (chunk.length <= room) {
            builder.append(chunk)
            return
        }
        builder.append(chunk, 0, room)
        droppedChars += (chunk.length - room).toLong()
    }

    fun text(): String = builder.toString()
}

package com.muyuchat.core.dsh.vscreen

/**
 * Lifecycle of the one virtual display MCA owns.
 *
 * `CREATED` is the initial state before any request. A create moves `CLOSED`/`FAILED`/`CREATED`
 * to `STARTING`; a successful start reaches `RUNNING`; close goes through `CLOSING` to `CLOSED`.
 * A failed start lands in `FAILED`. No other transition exists, and no state is skipped.
 */
enum class DshVscreenState { CREATED, STARTING, RUNNING, CLOSING, CLOSED, FAILED }

/** Identifies one successful create. A late close with an older lease is rejected, not applied. */
data class DshVscreenLease(val id: Long, val displayId: Int) {
    init {
        require(id >= 1L) { "lease id must be positive" }
        require(displayId >= 0) { "lease display id must be non-negative" }
    }
}

/** One captured frame of the virtual display. [bytes] are already encoded (PNG or JPEG). */
data class DshVscreenFrame(
    val format: DshVscreenFrameFormat,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtMillis: Long
) {
    val sizeBytes: Int get() = bytes.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DshVscreenFrame) return false
        return format == other.format &&
            bytes.contentEquals(other.bytes) &&
            width == other.width &&
            height == other.height &&
            capturedAtMillis == other.capturedAtMillis
    }

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + capturedAtMillis.hashCode()
        return result
    }

    override fun toString(): String =
        "DshVscreenFrame(format=$format, width=$width, height=$height, bytes=${bytes.size}, capturedAtMillis=$capturedAtMillis)"
}

/** A frame persisted where the Node tool package can read it (`readFile(r.path)`). */
data class DshVscreenPersistedFrame(
    val path: String,
    val imageWidth: Int,
    val imageHeight: Int
) {
    init {
        require(path.isNotBlank() && path.length <= DshVscreenLimits.MAX_PATH_CHARS) {
            "persisted frame path is invalid"
        }
        require(imageWidth > 0 && imageHeight > 0) { "persisted frame dimensions must be positive" }
    }
}

/**
 * Frame source port. The Android adapter implements it with ImageReader; unit tests implement it
 * with an in-memory source. [release] must be idempotent and must release the display, ImageReader,
 * buffers and file descriptors it owns.
 */
interface DshVscreenDisplayHandle {
    val displayId: Int
    val geometry: DshVscreenGeometry

    /** Latest frame at call time, or null when none is available. Must not block past the watchdog. */
    suspend fun latestFrame(timeoutMillis: Long): DshVscreenFrame?

    /** Idempotent teardown. Called exactly once by the owner on close, and also on a lost race. */
    suspend fun release()
}

/** Creates the virtual display. A failed start must throw, so the owner records FAILED. */
fun interface DshVscreenDisplayPort {
    suspend fun start(geometry: DshVscreenGeometry): DshVscreenDisplayHandle
}

/** Typed start failure raised by the Android display adapter. */
class DshVscreenDisplayStartException(
    val code: DshVscreenErrorCode,
    message: String
) : Exception(message) {
    init {
        require(message.isNotBlank() && message.length <= DshVscreenLimits.MAX_MESSAGE_CHARS) {
            "display start failure message must be bounded"
        }
    }
}

/** Persists one captured frame and reports where it landed. */
fun interface DshVscreenFrameSink {
    suspend fun persist(frame: DshVscreenFrame, displayId: Int): DshVscreenPersistedFrame
}

/**
 * Optional preview surface.
 *
 * The production owner is wired to [DshVscreenPreviewRelay] (bounded in-process frame plus an
 * optional overlay window). [None] remains for callers that only need the pull path, and a preview
 * failure must never fail capture.
 */
interface DshVscreenPreviewPort {
    fun onFrame(displayId: Int, frame: DshVscreenFrame, deliveredAtMillis: Long) = Unit

    fun onDisplayStopped(displayId: Int, reason: String) = Unit

    companion object {
        val None: DshVscreenPreviewPort = object : DshVscreenPreviewPort {}
    }
}

/** Typed result of a privileged command. Output is bounded by the adapter before construction. */
data class DshVscreenCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    /** Health of the transport after this execution; `UNKNOWN` when nothing was proven. */
    val channelState: DshPrivilegedChannelState = DshPrivilegedChannelState.UNKNOWN
) {
    init {
        require(stdout.length <= DshVscreenLimits.MAX_COMMAND_STDOUT_CHARS) { "stdout exceeds the command budget" }
        require(stderr.length <= DshVscreenLimits.MAX_COMMAND_STDERR_CHARS) { "stderr exceeds the command budget" }
    }

    /** True when the transport failed, so the caller must re-probe instead of retrying blindly. */
    val channelLost: Boolean
        get() = channelState == DshPrivilegedChannelState.LOST ||
            channelState == DshPrivilegedChannelState.SERVER_NOT_RUNNING ||
            channelState == DshPrivilegedChannelState.PERMISSION_DENIED ||
            channelState == DshPrivilegedChannelState.DEX_UNUSABLE ||
            channelState == DshPrivilegedChannelState.NOT_INSTALLED
}

/**
 * What the privileged adapter needs to build a transport-specific launch line.
 *
 * [privilegedDexPath] is the jar/dex the reference executes with `app_process`; the path is data,
 * not a secret. [shizukuAppId] is the application id the Shizuku loader binds to.
 */
data class DshVscreenPrivilegedTargets(
    val privilegedDexPath: String?,
    val shizukuAppId: String?
) {
    init {
        require(privilegedDexPath == null || (privilegedDexPath.startsWith("/") && privilegedDexPath.length <= 240)) {
            "privileged dex path must be an absolute bounded path"
        }
        require(shizukuAppId == null || (shizukuAppId.length <= DshVscreenLimits.MAX_PACKAGE_CHARS && shizukuAppId.isNotBlank())) {
            "shizuku application id must be bounded"
        }
    }
}

/**
 * One privileged invocation.
 *
 * The command line ([argv]) must never contain a secret. A token-bearing configuration is placed
 * in [stdin] (or, when a transport forces it, in a separately passed environment map); [redacted]
 * therefore prints argv and environment *keys* only, never stdin content or environment values.
 */
data class DshVscreenPrivilegedCommand(
    val transport: DshVscreenTransport,
    val argv: List<String>,
    val stdin: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = DshVscreenLimits.DEFAULT_COMMAND_TIMEOUT_MILLIS
) {
    init {
        require(transport != DshVscreenTransport.NONE) { "a privileged command needs a real transport" }
        require(argv.isNotEmpty()) { "a privileged command needs an argv" }
        require(argv.all { it.isNotEmpty() && it.length <= DshVscreenLimits.MAX_COMMAND_ARG_CHARS }) {
            "privileged argv entries must be non-empty and bounded"
        }
        require(argv.none { argument -> argument.any { it.code < 0x20 || it.code in 0x7F..0x9F } }) {
            "privileged argv must not carry control characters"
        }
        require(stdin == null || stdin.length <= DshVscreenLimits.MAX_STDIN_BYTES) {
            "privileged stdin payload exceeds the budget"
        }
        require(environment.keys.all { it.matches(ENVIRONMENT_KEY) }) {
            "privileged environment keys must be identifier-shaped"
        }
        require(environment.values.none { value -> value.any { it.code < 0x20 || it.code in 0x7F..0x9F } }) {
            "privileged environment values must be single-line"
        }
        require(
            timeoutMillis in DshVscreenLimits.MIN_COMMAND_TIMEOUT_MILLIS..DshVscreenLimits.MAX_COMMAND_TIMEOUT_MILLIS
        ) { "privileged command timeout is out of range" }
    }

    /** True when [secret] would be visible in `ps`/the shell line or in the process environment. */
    fun carriesSecretOnCommandLine(secret: String): Boolean =
        secret.isNotEmpty() && (argv.any { it.contains(secret) } || environment.values.any { it.contains(secret) })

    val redacted: String
        get() = "DshVscreenPrivilegedCommand(transport=$transport, argv=$argv, " +
            "stdinChars=${stdin?.length ?: 0}, environmentKeys=${environment.keys.sorted()})"

    override fun toString(): String = redacted

    private companion object {
        val ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    }
}

/** Probes both privileged transports and executes their commands. Implemented by the Android adapter. */
interface DshVscreenPrivilegedPort {
    suspend fun probe(): DshVscreenProbeReport

    suspend fun execute(command: DshVscreenPrivilegedCommand): DshVscreenCommandResult

    fun targets(): DshVscreenPrivilegedTargets
}

/** Result DTOs returned by the control surface. */
data class DshVscreenCreateInfo(
    val displayId: Int,
    val geometry: DshVscreenGeometry,
    val reused: Boolean,
    val lease: DshVscreenLease,
    val hint: String?
) {
    init {
        require(displayId >= 0) { "display id must be non-negative" }
        require(!reused) { "MCA never silently reuses a display; a second create is rejected" }
        require(hint == null || hint.length <= DshVscreenLimits.MAX_MESSAGE_CHARS) { "create hint must be bounded" }
    }
}

data class DshVscreenStatusInfo(
    val state: DshVscreenState,
    val displayId: Int,
    val geometry: DshVscreenGeometry?,
    val running: Boolean,
    val hasFrame: Boolean,
    val lease: DshVscreenLease?,
    val transport: DshVscreenTransportSelection?
)

data class DshVscreenLaunchInfo(
    val packageName: String,
    val component: String?,
    val displayId: Int,
    val hint: String?
)

data class DshVscreenFrameInfo(
    val path: String,
    val displayId: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int
) {
    val scaleX: Double get() = if (imageWidth > 0) screenWidth.toDouble() / imageWidth else 1.0
    val scaleY: Double get() = if (imageHeight > 0) screenHeight.toDouble() / imageHeight else 1.0
}

/**
 * The eight-action surface the wire layer talks to. [VirtualScreenOwner] is the production
 * implementation; unit tests use a fake to assert the JSON envelope independently of lifecycle.
 */
interface DshVscreenControl {
    suspend fun create(request: DshVscreenCreateRequest): DshVscreenResult<DshVscreenCreateInfo>
    suspend fun status(): DshVscreenResult<DshVscreenStatusInfo>
    suspend fun launch(request: DshVscreenLaunchRequest): DshVscreenResult<DshVscreenLaunchInfo>
    suspend fun see(request: DshVscreenSeeRequest): DshVscreenResult<DshVscreenFrameInfo>
    suspend fun tap(request: DshVscreenTapRequest): DshVscreenResult<Unit>
    suspend fun swipe(request: DshVscreenSwipeRequest): DshVscreenResult<Unit>
    suspend fun key(request: DshVscreenKeyRequest): DshVscreenResult<Unit>
    suspend fun close(): DshVscreenResult<Unit>
}

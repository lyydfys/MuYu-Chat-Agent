package com.muyuchat.core.dsh.vscreen

/** Privileged transports MCA can probe. [NONE] means neither shell channel is proven. */
enum class DshVscreenTransport { ROOT, SHIZUKU, NONE }

/**
 * How strongly one capability is established.
 *
 * `PROVEN` is reserved for a probe/execution that actually succeeded on the current device and
 * process. A shell can prove itself with a real `id -u`; the display path is only `PROVEN` after a
 * real `MediaProjection` virtual display was created, never from a flag.
 */
enum class DshVscreenProof { PROVEN, UNPROVEN, UNSUPPORTED }

/**
 * Probe result of one privileged *shell* transport.
 *
 * The shell only carries input injection and app launching. It cannot create a virtual display, so
 * the display half is a separate [DshVscreenCaptureEvidence] rather than a field here — a boolean
 * shell probe must never be read as "the display path works".
 */
data class DshVscreenTransportEvidence(
    val transport: DshVscreenTransport,
    val shellProven: Boolean,
    val shellDetail: String
) {
    init {
        require(transport != DshVscreenTransport.NONE) { "evidence must name a transport" }
        require(shellDetail.isNotBlank() && shellDetail.length <= DshVscreenLimits.MAX_PRIVILEGED_DETAIL_CHARS) {
            "shell evidence detail must be bounded"
        }
        require(shellDetail.none { it.code < 0x20 || it.code in 0x7F..0x9F }) {
            "transport evidence must be single-line"
        }
    }
}

/** How a virtual display is actually created. There is exactly one supported source. */
enum class DshVscreenDisplaySource {
    /** `MediaProjection` consent + `MediaProjection.createVirtualDisplay` with an ImageReader. */
    PROJECTION,

    /** No display source exists in this build/process. */
    NONE
}

/**
 * Evidence for the *display* half of the capability.
 *
 * A shell (root/Shizuku) cannot create a display: `DisplayManager.createVirtualDisplay` is gated by
 * the signature `CAPTURE_VIDEO_OUTPUT` permission or a MediaProjection token, and `cmd display` has
 * no add-display subcommand. The supported source is therefore an app-owned screen-capture consent
 * whose frames come back through an `ImageReader` on the display's surface.
 */
data class DshVscreenCaptureEvidence(
    val source: DshVscreenDisplaySource,
    val proof: DshVscreenProof,
    val detail: String
) {
    init {
        require(detail.isNotBlank() && detail.length <= DshVscreenLimits.MAX_PRIVILEGED_DETAIL_CHARS) {
            "capture evidence detail must be bounded"
        }
        require(detail.none { it.code < 0x20 || it.code in 0x7F..0x9F }) {
            "capture evidence detail must be single-line"
        }
        require((source == DshVscreenDisplaySource.NONE) == (proof == DshVscreenProof.UNSUPPORTED)) {
            "a capture source is either attemptable or unsupported"
        }
    }

    companion object {
        fun unsupported(detail: String): DshVscreenCaptureEvidence = DshVscreenCaptureEvidence(
            source = DshVscreenDisplaySource.NONE,
            proof = DshVscreenProof.UNSUPPORTED,
            detail = detail.take(DshVscreenLimits.MAX_PRIVILEGED_DETAIL_CHARS)
        )
    }
}

/** Both privileged-shell probe results plus the display-source evidence. */
data class DshVscreenProbeReport(
    val root: DshVscreenTransportEvidence,
    val shizuku: DshVscreenTransportEvidence,
    val capture: DshVscreenCaptureEvidence
) {
    init {
        require(root.transport == DshVscreenTransport.ROOT) { "root evidence must describe ROOT" }
        require(shizuku.transport == DshVscreenTransport.SHIZUKU) { "shizuku evidence must describe SHIZUKU" }
    }

    companion object {
        /** A report that claims nothing; used when probing itself failed. */
        fun unavailable(detail: String = "no privileged transport probe has completed"): DshVscreenProbeReport {
            val bounded = detail.take(DshVscreenLimits.MAX_PRIVILEGED_DETAIL_CHARS)
            return DshVscreenProbeReport(
                root = DshVscreenTransportEvidence(
                    transport = DshVscreenTransport.ROOT,
                    shellProven = false,
                    shellDetail = bounded
                ),
                shizuku = DshVscreenTransportEvidence(
                    transport = DshVscreenTransport.SHIZUKU,
                    shellProven = false,
                    shellDetail = bounded
                ),
                capture = DshVscreenCaptureEvidence.unsupported(bounded)
            )
        }
    }
}

/**
 * Typed transport decision.
 *
 * [shell] drives input injection and launch (`input -d`, `am start --display`); [display] drives
 * virtual-display creation. They are deliberately independent: creation needs the user's capture
 * consent, while launch/input need a privileged shell, and either half can be missing.
 */
data class DshVscreenTransportSelection(
    val shell: DshVscreenTransport,
    val display: DshVscreenDisplaySource,
    val displayProof: DshVscreenProof,
    val fullyVerified: Boolean,
    val warning: String?
) {
    init {
        require(warning == null || warning.length <= DshVscreenLimits.MAX_MESSAGE_CHARS)
        require(warning == null || warning.none { it.code < 0x20 || it.code in 0x7F..0x9F })
        require(!fullyVerified || displayProof == DshVscreenProof.PROVEN) {
            "a fully verified selection must rest on a proven display capability"
        }
        require((display == DshVscreenDisplaySource.NONE) == (displayProof == DshVscreenProof.UNSUPPORTED)) {
            "the display source and its proof must agree"
        }
    }

    val privileged: Boolean get() = shell != DshVscreenTransport.NONE
    val canCreateDisplay: Boolean get() = display != DshVscreenDisplaySource.NONE
    val displayCreationVerified: Boolean get() = display != DshVscreenDisplaySource.NONE && displayProof == DshVscreenProof.PROVEN

    companion object {
        val unavailable: DshVscreenTransportSelection = DshVscreenTransportSelection(
            shell = DshVscreenTransport.NONE,
            display = DshVscreenDisplaySource.NONE,
            displayProof = DshVscreenProof.UNSUPPORTED,
            fullyVerified = false,
            warning = "No screen-capture path and no privileged shell (root/Shizuku) are available; " +
                "virtual-screen control is unavailable."
        )
    }
}

object DshVscreenTransportSelector {
    /**
     * Selection is conservative and never equates an environment boolean with a working display:
     *
     * - the *shell* transport prefers root because `su -c` is the direct channel, then Shizuku;
     * - the *display* source is the app-owned screen-capture consent, which a shell cannot replace;
     * - `fullyVerified` is true only when the display path itself reported `PROVEN` (a real create
     *   after consent), never from a flag.
     */
    fun select(report: DshVscreenProbeReport): DshVscreenTransportSelection {
        val shell = when {
            report.root.shellProven -> DshVscreenTransport.ROOT
            report.shizuku.shellProven -> DshVscreenTransport.SHIZUKU
            else -> DshVscreenTransport.NONE
        }
        val capture = report.capture
        val display = capture.source
        val displayProof = if (display == DshVscreenDisplaySource.NONE) {
            DshVscreenProof.UNSUPPORTED
        } else {
            capture.proof
        }
        val warning = when {
            display == DshVscreenDisplaySource.NONE && shell == DshVscreenTransport.NONE ->
                "No screen-capture path and no privileged shell (root/Shizuku) are available; " +
                    "virtual-screen control is unavailable."

            display == DshVscreenDisplaySource.NONE ->
                "A privileged shell exists, but no screen-capture path can create a virtual display."

            displayProof == DshVscreenProof.PROVEN && shell == DshVscreenTransport.NONE ->
                "The display path is verified, but no privileged shell (root/Shizuku) is available: " +
                    "launch, tap, swipe and key will fail."

            displayProof == DshVscreenProof.PROVEN -> null

            shell == DshVscreenTransport.NONE ->
                "Screen capture is not granted yet and no privileged shell is available; the display " +
                    "path stays unverified and launch/input will fail."

            else ->
                "Screen capture is not granted yet; the display path stays unverified until the user " +
                    "grants the in-app capture consent."
        }
        return DshVscreenTransportSelection(
            shell = shell,
            display = display,
            displayProof = displayProof,
            fullyVerified = display != DshVscreenDisplaySource.NONE &&
                displayProof == DshVscreenProof.PROVEN,
            warning = warning
        )
    }
}

/** Configuration passed to the privileged display core on stdin; the token is never in argv. */
data class DshVscreenBridgeLaunchConfig(
    val bridgePort: Int,
    val localToken: String,
    val geometry: DshVscreenGeometry
) {
    init {
        require(bridgePort in DshVscreenLimits.MIN_BRIDGE_PORT..DshVscreenLimits.MAX_BRIDGE_PORT) {
            "bridge port is out of range"
        }
        require(localToken.length in DshVscreenLimits.MIN_TOKEN_CHARS..DshVscreenLimits.MAX_TOKEN_CHARS) {
            "local token length is out of range"
        }
        require(localToken.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) {
            "local token must be URL-safe"
        }
    }
}

/**
 * Builds the exact privileged command lines MCA executes.
 *
 * Secret handling is a construction invariant, not a convention: bridge configuration is written
 * to [DshVscreenPrivilegedCommand.stdin] and never to argv or the environment; the factory asserts
 * this before returning a command.
 */
object DshVscreenCommandFactory {
    private val environmentKey = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")

    /**
     * Environment key fragments that identify a credential. A privileged child must never inherit
     * the App-local capability token, an API key or a private-key path.
     */
    private val CREDENTIAL_MARKERS = listOf(
        "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "PRIVATE_KEY", "API_KEY"
    )

    /**
     * Drops the loader variables that break `app_process` (the pinned package strips the same set),
     * rejects identifier-invalid keys and strips credential-looking values.
     */
    fun sanitizeEnvironment(source: Map<String, String>): Map<String, String> =
        source.filterKeys { key ->
            val upper = key.uppercase()
            !key.startsWith("LD_") &&
                environmentKey.matches(key) &&
                CREDENTIAL_MARKERS.none { marker -> upper.contains(marker) }
        }.filterValues { value -> value.none { it.code < 0x20 || it.code in 0x7F..0x9F } }

    fun inputTap(
        transport: DshVscreenTransport,
        targets: DshVscreenPrivilegedTargets,
        displayId: Int,
        x: Int,
        y: Int
    ): DshVscreenPrivilegedCommand {
        requireDisplay(displayId, x, y)
        return launch(
            transport = transport,
            targets = targets,
            shellCommand = "input -d $displayId tap $x $y",
            timeoutMillis = 8_000L
        )
    }

    fun inputSwipe(
        transport: DshVscreenTransport,
        targets: DshVscreenPrivilegedTargets,
        displayId: Int,
        request: DshVscreenSwipeRequest
    ): DshVscreenPrivilegedCommand {
        requireDisplay(displayId, request.x1, request.y1, request.x2, request.y2)
        return launch(
            transport = transport,
            targets = targets,
            shellCommand = "input -d $displayId swipe ${request.x1} ${request.y1} " +
                "${request.x2} ${request.y2} ${request.durationMillis}",
            timeoutMillis = 12_000L
        )
    }

    fun inputKey(
        transport: DshVscreenTransport,
        targets: DshVscreenPrivilegedTargets,
        displayId: Int,
        key: DshVscreenKey
    ): DshVscreenPrivilegedCommand {
        require(displayId >= 0) { "display id must be non-negative" }
        require(key.keycode in DshVscreenKey.acceptedKeycodes) { "unsupported keycode" }
        return launch(
            transport = transport,
            targets = targets,
            shellCommand = "input -d $displayId keyevent ${key.keycode}",
            timeoutMillis = 8_000L
        )
    }

    fun startApp(
        transport: DshVscreenTransport,
        targets: DshVscreenPrivilegedTargets,
        displayId: Int,
        packageName: String
    ): DshVscreenPrivilegedCommand {
        require(displayId >= 0) { "display id must be non-negative" }
        require(packageName.isNotBlank()) { "package name is required" }
        require(packageName.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == ' ' }) {
            "package name contains unsupported characters"
        }
        return launch(
            transport = transport,
            targets = targets,
            shellCommand = "am start --display $displayId -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -p '$packageName'",
            timeoutMillis = 15_000L
        )
    }

    /**
     * Builds the line for a future shell-side display core.
     *
     * The shipped MCA path does **not** use this: a shell uid cannot create a virtual display
     * (`createVirtualDisplay` needs `CAPTURE_VIDEO_OUTPUT` or a projection token), so creation goes
     * through `MediaProjection` in the app process. This typed builder is retained as the audited
     * secret boundary for a helper jar that is not shipped, and it asserts that a token-bearing
     * configuration never reaches argv or the environment.
     */
    fun startDisplayBridge(
        transport: DshVscreenTransport,
        targets: DshVscreenPrivilegedTargets,
        config: DshVscreenBridgeLaunchConfig
    ): DshVscreenPrivilegedCommand {
        require(transport != DshVscreenTransport.NONE) { "a bridge launch needs a transport" }
        val dex = requireNotNull(targets.privilegedDexPath) {
            "the privileged display core needs a dex/jar path"
        }
        val stdin = buildString {
            append("{\"bridgePort\":").append(config.bridgePort)
            append(",\"localToken\":\"").append(config.localToken).append('"')
            append(",\"width\":").append(config.geometry.width)
            append(",\"height\":").append(config.geometry.height)
            append(",\"dpi\":").append(config.geometry.dpi)
            append('}')
        }
        val command = launch(
            transport = transport,
            targets = targets,
            shellCommand = "app_process -Djava.class.path=$dex /system/bin --nice-name=dsh-vscreen-core " +
                "com.muyuchat.core.dsh.vscreen.bridge.DshVscreenCore --config-stdin",
            timeoutMillis = DshVscreenLimits.DEFAULT_COMMAND_TIMEOUT_MILLIS,
            stdin = stdin
        )
        require(!command.carriesSecretOnCommandLine(config.localToken)) {
            "bridge token leaked onto the privileged command line"
        }
        return command
    }

    private fun launch(
        transport: DshVscreenTransport,
        targets: DshVscreenPrivilegedTargets,
        shellCommand: String,
        timeoutMillis: Long,
        stdin: String? = null
    ): DshVscreenPrivilegedCommand {
        require(transport != DshVscreenTransport.NONE) { "a privileged command needs a transport" }
        require(shellCommand.none { it.code < 0x20 || it.code in 0x7F..0x9F }) {
            "privileged shell command must be a single line"
        }
        val argv: List<String>
        val environment: Map<String, String>
        when (transport) {
            DshVscreenTransport.ROOT -> {
                argv = listOf("su", "-c", shellCommand)
                environment = emptyMap()
            }

            DshVscreenTransport.SHIZUKU -> {
                val dex = requireNotNull(targets.privilegedDexPath) {
                    "Shizuku transport needs SHIZUKU_DEX"
                }
                val appId = requireNotNull(targets.shizukuAppId) {
                    "Shizuku transport needs SHIZUKU_APP_ID"
                }
                // One audited argv shape for every Shizuku invocation (probe, shell route, vscreen).
                val invocation = DshShizukuRishCommand.build(
                    dexPath = dex,
                    applicationId = appId,
                    shellCommand = shellCommand
                )
                argv = invocation.argv
                environment = invocation.environment
            }

            DshVscreenTransport.NONE -> error("unreachable")
        }
        return DshVscreenPrivilegedCommand(
            transport = transport,
            argv = argv,
            stdin = stdin,
            environment = environment,
            timeoutMillis = timeoutMillis
        )
    }

    private fun requireDisplay(displayId: Int, vararg coordinates: Int) {
        require(displayId >= 0) { "display id must be non-negative" }
        require(coordinates.all { it in 0..DshVscreenLimits.MAX_COORDINATE }) {
            "command coordinates are out of range"
        }
    }
}

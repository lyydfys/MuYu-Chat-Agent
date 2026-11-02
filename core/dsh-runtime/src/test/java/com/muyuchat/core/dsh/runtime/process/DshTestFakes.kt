package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseOutcome
import com.muyuchat.core.dsh.contract.runtime.DshTerminalExit
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.contract.runtime.DshTerminalTransport
import com.muyuchat.core.dsh.contract.runtime.DshTerminalUnavailableReason
import com.muyuchat.core.dsh.runtime.terminal.DshPtyAvailability
import com.muyuchat.core.dsh.runtime.terminal.DshPtyBridge
import com.muyuchat.core.dsh.runtime.terminal.DshTerminalFactory
import com.muyuchat.core.dsh.runtime.terminal.DshTerminalOpenResult
import com.muyuchat.core.dsh.runtime.terminal.DshTerminalPort
import java.io.File
import java.util.ArrayDeque

/** Builds a closure model with every packaged artifact present unless a test removes one. */
internal fun dshTestModel(
    nativeLibraries: Set<String> = setOf(
        DshCommandClosure.Native.NODE,
        DshCommandClosure.Native.RIPGREP,
        DshCommandClosure.Native.CURL,
        DshCommandClosure.Native.GIT,
        DshCommandClosure.Native.GIT_REMOTE_HTTP,
        DshCommandClosure.Native.GIT_REMOTE_HTTPS,
        DshCommandClosure.Native.PYTHON,
        DshCommandClosure.DISPATCHER_LIBRARY,
        DshCommandClosure.PTY_LIBRARY
    ),
    payloadFiles: Set<String> = setOf(
        DshCommandClosure.Payload.NPM_CLI,
        DshCommandClosure.Payload.NPX_CLI,
        DshCommandClosure.Payload.PNPM_CLI,
        DshCommandClosure.Payload.BASH,
        DshCommandClosure.Payload.GIT,
        DshCommandClosure.Payload.RG,
        DshCommandClosure.Payload.CURL
    ),
    payloadDirectories: Set<String> = setOf(DshCommandClosure.Payload.PYTHON_STDLIB),
    payloadRoot: String = "/data/user/0/com.muyuchat.mca/files/dsh/payload",
    nativeRoot: String = "/data/app/~~x/com.muyuchat.mca-y/lib/arm64"
): DshCommandClosureModel = DshCommandClosureModel(
    payloadDir = payloadRoot,
    nativeLibraryDir = nativeRoot,
    payloadFiles = payloadFiles,
    payloadDirectories = payloadDirectories,
    nativeLibraries = nativeLibraries
)

/** Scripted terminal port; the pump reads pre-programmed chunks, then reports a bounded timeout. */
internal class ScriptedTerminalPort(
    override val transport: DshTerminalTransport = DshTerminalTransport.REAL_PTY,
    initialOutput: List<ByteArray> = emptyList(),
    private val exitOnClose: DshTerminalExit = DshTerminalExit(code = 0)
) : DshTerminalPort {

    private val chunks = ArrayDeque<ByteArray>().apply { addAll(initialOutput) }

    override val isAtty: Boolean = transport == DshTerminalTransport.REAL_PTY

    val writes = mutableListOf<ByteArray>()
    val resizeRequests = mutableListOf<DshTerminalSize>()
    var closeCalls = 0
    var abandonCalls = 0
    var readCalls = 0
    var settledOnClose = true

    @Volatile
    var exit: DshTerminalExit? = null

    @Volatile
    var released = false

    override fun write(bytes: ByteArray): Int {
        if (released) return -1
        writes.add(bytes.copyOf())
        return bytes.size
    }

    override fun read(buffer: ByteArray, timeoutMillis: Long): Int {
        readCalls++
        if (released) return 0
        val chunk = if (chunks.isEmpty()) return -1 else chunks.removeFirst()
        val copied = minOf(chunk.size, buffer.size)
        System.arraycopy(chunk, 0, buffer, 0, copied)
        if (copied < chunk.size) {
            chunks.addFirst(chunk.copyOfRange(copied, chunk.size))
        }
        return copied
    }

    override fun resize(size: DshTerminalSize): Boolean {
        if (transport != DshTerminalTransport.REAL_PTY || released) return false
        resizeRequests.add(size)
        return true
    }

    override fun size(): DshTerminalSize? =
        if (transport == DshTerminalTransport.REAL_PTY) DshTerminalSize(80, 24) else null

    override fun pollExit(timeoutMillis: Long): DshTerminalExit? = exit

    override fun close(graceMillis: Long, killMillis: Long): DshTerminalCloseOutcome {
        closeCalls++
        released = true
        exit = exit ?: exitOnClose
        return DshTerminalCloseOutcome(
            settled = settledOnClose,
            escalated = !settledOnClose,
            detail = if (settledOnClose) "SIGTERM" else "child not reaped within the kill budget"
        )
    }

    override fun abandon() {
        abandonCalls++
        released = true
    }

    override fun close() {
        close(1_500L, 2_500L)
    }

    fun push(bytes: ByteArray) {
        chunks.addLast(bytes)
    }
}

/** PTY bridge that either returns scripted ports or a typed load failure. */
internal class FakePtyBridge(
    private val port: ScriptedTerminalPort? = null,
    private val portProvider: (() -> ScriptedTerminalPort?)? = null
) : DshPtyBridge {
    override val nativeLibrary: String = "libdshpty.so"
    var openCalls = 0

    private val available: Boolean get() = port != null || portProvider != null

    override fun availability(): DshPtyAvailability = if (available) {
        DshPtyAvailability.Loaded("libdshpty.so", "/dev/pts/3")
    } else {
        DshPtyAvailability.Unavailable(
            DshTerminalUnavailableReason.NATIVE_LOAD_FAILED,
            "dlopen failed: library \"libdshpty.so\" not found"
        )
    }

    override fun open(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: String,
        size: DshTerminalSize
    ): DshTerminalOpenResult {
        openCalls++
        val target = portProvider?.invoke() ?: port ?: return DshTerminalOpenResult.Failed(
            DshTerminalUnavailableReason.OPEN_FAILED,
            "fake pty open unavailable"
        )
        return DshTerminalOpenResult.Opened(target)
    }
}

internal fun fakeFactory(
    port: ScriptedTerminalPort? = null,
    allowPipeFallback: Boolean = false,
    pipePort: ScriptedTerminalPort? = null,
    portProvider: (() -> ScriptedTerminalPort?)? = null
): DshTerminalFactory = DshTerminalFactory(
    ptyBridge = FakePtyBridge(port, portProvider),
    allowPipeFallback = allowPipeFallback,
    pipeOpener = {
        val target = pipePort
        if (target != null) DshTerminalOpenResult.Opened(target)
        else DshTerminalOpenResult.Failed(DshTerminalUnavailableReason.OPEN_FAILED, "no pipe fallback in test")
    }
)

internal fun tempDirectory(prefix: String): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
    check(dir.mkdirs() || dir.isDirectory) { "cannot create test directory $dir" }
    return dir
}

/** Materialises a plausible payload tree so the probe scans real filesystem facts. */
internal fun materializePayload(root: File) {
    fun file(relative: String, content: String = "x") {
        val target = File(root, relative)
        target.parentFile?.mkdirs()
        target.writeText(content)
    }

    fun dir(relative: String) {
        File(root, relative).mkdirs()
    }
    file(DshCommandClosure.Payload.NPM_CLI)
    file(DshCommandClosure.Payload.NPX_CLI)
    file(DshCommandClosure.Payload.PNPM_CLI)
    file(DshCommandClosure.Payload.BASH)
    file(DshCommandClosure.Payload.GIT)
    file(DshCommandClosure.Payload.RG)
    file(DshCommandClosure.Payload.CURL)
    dir(DshCommandClosure.Payload.PYTHON_STDLIB)
    dir("runtime/bin")
    dir("bin")
    File(root, "runtime/lib").mkdirs()
    File(root, "home").mkdirs()
    File(root, "workspace").mkdirs()
    File(root, "tmp").mkdirs()
    File(root, "native").mkdirs()
    for (library in listOf(
        DshCommandClosure.Native.NODE,
        DshCommandClosure.Native.RIPGREP,
        DshCommandClosure.Native.CURL,
        DshCommandClosure.Native.GIT,
        DshCommandClosure.Native.GIT_REMOTE_HTTP,
        DshCommandClosure.Native.GIT_REMOTE_HTTPS,
        DshCommandClosure.Native.PYTHON,
        DshCommandClosure.DISPATCHER_LIBRARY,
        DshCommandClosure.PTY_LIBRARY
    )) {
        File(root, "native/$library").writeText("elf")
    }
}

/** Answers every probe with plausible version output; tests can override one argv. */
internal fun successfulProbeRunner(
    overrideProbe: (List<String>) -> DshCommandResult? = { null }
): DshProcessRunner = DshProcessRunner { argv, _, _, _ ->
    overrideProbe(argv) ?: when {
        argv.any { it.endsWith(DshCommandClosure.Payload.NPM_CLI) } -> DshCommandResult(0, "10.9.2\n", "", false)
        argv.any { it.endsWith(DshCommandClosure.Payload.NPX_CLI) } -> DshCommandResult(0, "10.9.2\n", "", false)
        argv.any { it.endsWith(DshCommandClosure.Payload.PNPM_CLI) } -> DshCommandResult(0, "10.12.1\n", "", false)
        argv.contains("-m") -> DshCommandResult(0, "pip 24.3.1 from /payload/python/lib/pip (python 3.14)\n", "", false)
        argv.any { it.endsWith(DshCommandClosure.Native.PYTHON) } &&
            argv.lastOrNull() == "--version" -> DshCommandResult(0, "Python 3.14.0\n", "", false)
        argv.any { it.endsWith(DshCommandClosure.Native.NODE) } -> DshCommandResult(0, "v26.1.0\n", "", false)
        argv.any { it.endsWith(DshCommandClosure.Native.RIPGREP) } -> DshCommandResult(0, "ripgrep 14.1.1\n", "", false)
        argv.any { it.endsWith(DshCommandClosure.Native.CURL) } -> DshCommandResult(0, "curl 8.11.1\n", "", false)
        argv.any { it.endsWith(DshCommandClosure.Native.GIT) } -> DshCommandResult(0, "git version 2.47.1\n", "", false)
        argv.contains(DshToolchainVerifier.MARKER_COMMAND) -> DshCommandResult(0, DshToolchainVerifier.MARKER, "", false)
        else -> DshCommandResult(0, "unknown\n", "", false)
    }
}

package com.muyuchat.core.dsh.runtime.terminal

import com.muyuchat.core.dsh.contract.runtime.DshTerminalCapability
import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseOutcome
import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseReason
import com.muyuchat.core.dsh.contract.runtime.DshTerminalEvent
import com.muyuchat.core.dsh.contract.runtime.DshTerminalExit
import com.muyuchat.core.dsh.contract.runtime.DshTerminalFailureCode
import com.muyuchat.core.dsh.contract.runtime.DshTerminalOpenRequest
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.contract.runtime.DshTerminalTransport
import com.muyuchat.core.dsh.contract.runtime.DshTerminalUnavailableReason
import com.muyuchat.core.dsh.runtime.process.DshCommandClosure
import com.muyuchat.core.dsh.runtime.process.DshCommandClosureModel
import com.muyuchat.core.dsh.runtime.process.DshCommandLaunch
import com.muyuchat.core.dsh.runtime.process.DshCommandRole
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D12 acceptance at the JVM level: PTY session semantics, resize/SIGWINCH coalescing, cancel and
 * close idempotency, honest NON_PTY fallback, and the fail-closed native bridge.
 *
 * The real PTY itself cannot be exercised without a device (the native library is arm64 and needs
 * /dev/ptmx); that is recorded as NOT_RUN. Everything that can be proven on the JVM is proven here.
 */
class DshTerminalSessionTest {

    private class FakePort(
        override val transport: DshTerminalTransport = DshTerminalTransport.REAL_PTY,
        override val isAtty: Boolean = true,
        private val initialSize: DshTerminalSize? = DshTerminalSize.DEFAULT,
        var resizeAccepted: Boolean = true,
        var exitToReport: DshTerminalExit? = null,
        var readFailure: Throwable? = null,
        var writes: Int = 1,
        var closeOutcome: DshTerminalCloseOutcome = DshTerminalCloseOutcome(true, false, "SIGTERM")
    ) : DshTerminalPort {
        val resizeRequests = mutableListOf<DshTerminalSize>()
        val written = mutableListOf<String>()
        var closeCalls = 0
        var abandonCalls = 0
        private var geometry = initialSize
        private val inbox = ArrayDeque<ByteArray>()

        fun enqueue(bytes: String) {
            inbox.addLast(bytes.toByteArray())
        }

        override fun write(bytes: ByteArray): Int {
            written += String(bytes)
            return writes
        }

        override fun read(buffer: ByteArray, timeoutMillis: Long): Int {
            readFailure?.let { throw it }
            val head = inbox.firstOrNull() ?: return 0
            val copied = minOf(head.size, buffer.size)
            System.arraycopy(head, 0, buffer, 0, copied)
            if (copied == head.size) inbox.removeFirst() else inbox.addFirst(head.copyOfRange(copied, head.size))
            return copied
        }

        override fun resize(size: DshTerminalSize): Boolean {
            resizeRequests += size
            if (resizeAccepted) geometry = size
            return resizeAccepted
        }

        override fun size(): DshTerminalSize? = geometry

        override fun pollExit(timeoutMillis: Long): DshTerminalExit? = exitToReport

        override fun close(graceMillis: Long, killMillis: Long): DshTerminalCloseOutcome {
            closeCalls++
            return closeOutcome
        }

        override fun abandon() {
            abandonCalls++
        }

        override fun close() = Unit
    }

    private fun session(
        port: FakePort,
        now: () -> Long = { 0L },
        windowMillis: Long = DshTerminalSession.DEFAULT_RESIZE_WINDOW_MILLIS
    ) = DshTerminalSession(port, clock = now, resizeWindowMillis = windowMillis)

    @Test
    fun realPtyIsOnlyClaimedForATty() {
        assertTrue(session(FakePort()).isRealPty)
        assertFalse(session(FakePort(isAtty = false)).isRealPty)
        assertFalse(session(FakePort(transport = DshTerminalTransport.PIPE_FALLBACK)).isRealPty)
    }

    @Test
    fun resizeBeforeAttachIsCoalescedAndAppliedOnAttach() {
        val port = FakePort(initialSize = DshTerminalSize(80, 24))
        val terminal = session(port)
        val result = terminal.resize(DshTerminalSize(120, 40))
        assertTrue("first resize must be queued: $result", result is DshTerminalResizeResult.Coalesced)
        assertTrue(port.resizeRequests.isEmpty())
        val events = terminal.attach()
        assertEquals(1, events.size)
        assertTrue(events.single() is DshTerminalEvent.Resized)
        assertEquals(DshTerminalSize(120, 40), port.resizeRequests.single())
    }

    @Test
    fun identicalResizeIsIgnoredAndRapidResizesUseOnlyTheLatest() {
        val port = FakePort()
        var now = 1_000L
        val terminal = session(port, now = { now })
        terminal.attach()
        assertTrue(terminal.resize(DshTerminalSize.DEFAULT) is DshTerminalResizeResult.Ignored)
        assertTrue(terminal.resize(DshTerminalSize(100, 30)) is DshTerminalResizeResult.Applied)
        val coalesced = terminal.resize(DshTerminalSize(110, 34))
        assertTrue(coalesced is DshTerminalResizeResult.Coalesced)
        val flush = terminal.flushResize()
        assertEquals(DshTerminalEvent.Resized(DshTerminalSize(110, 34)), flush)
        now += 1_000L
        assertTrue(terminal.resize(DshTerminalSize(130, 50)) is DshTerminalResizeResult.Applied)
        assertEquals(
            listOf(DshTerminalSize(100, 30), DshTerminalSize(110, 34), DshTerminalSize(130, 50)),
            port.resizeRequests
        )
    }

    @Test
    fun unsupportedTransportResizeIsReportedNotHidden() {
        val port = FakePort(transport = DshTerminalTransport.PIPE_FALLBACK, resizeAccepted = false, initialSize = null)
        val terminal = session(port)
        terminal.attach()
        val result = terminal.resize(DshTerminalSize(100, 30))
        assertTrue("pipe fallback cannot resize: $result", result is DshTerminalResizeResult.Unsupported)
        assertNull(terminal.flushResize())
    }

    @Test
    fun resizeAfterCloseIsRejected() {
        val port = FakePort()
        val terminal = session(port)
        terminal.close()
        val result = terminal.resize(DshTerminalSize(100, 30))
        assertTrue(result is DshTerminalResizeResult.Rejected)
        assertEquals(
            DshTerminalFailureCode.ALREADY_CLOSED,
            (result as DshTerminalResizeResult.Rejected).code
        )
    }

    @Test
    fun outputIsDeliveredAndEofProducesTheExitFact() {
        val port = FakePort(exitToReport = DshTerminalExit(code = 0))
        val terminal = session(port)
        port.enqueue("hello")
        val buffer = ByteArray(64)
        val output = terminal.pump(buffer, 10)
        assertEquals(1, output.size)
        val bytes = (output.single() as DshTerminalEvent.Output).bytes
        assertEquals("hello", String(bytes))
        assertFalse(terminal.observedEndOfStream)
        val exitEvents = terminal.pump(buffer, 10)
        assertEquals(listOf(DshTerminalEvent.Exited(DshTerminalExit(0))), exitEvents)
        assertEquals(DshTerminalSession.Phase.EXITED, terminal.phase)
    }

    @Test
    fun eofWithoutObservableExitStaysOpenAndReportsEndOfStream() {
        val port = FakePort(exitToReport = null)
        val terminal = session(port)
        val events = terminal.pump(ByteArray(16), 10)
        assertTrue(events.isEmpty())
        assertTrue(terminal.observedEndOfStream)
        assertEquals(DshTerminalSession.Phase.OPEN, terminal.phase)
        assertNull(terminal.pollExit(10))
    }

    @Test
    fun readFailureMarksTheSessionFailedAndIsNotRetried() {
        val port = FakePort(readFailure = IOException("EIO"))
        val terminal = session(port)
        val events = terminal.pump(ByteArray(16), 10)
        val failed = events.single() as DshTerminalEvent.Failed
        assertEquals(DshTerminalFailureCode.READ_FAILED, failed.code)
        assertEquals(DshTerminalSession.Phase.FAILED, terminal.phase)
        val again = terminal.pump(ByteArray(16), 10)
        assertEquals(
            DshTerminalFailureCode.ALREADY_CLOSED,
            (again.single() as DshTerminalEvent.Failed).code
        )
    }

    @Test
    fun writeAfterCloseIsRejectedAndPeerClosedIsTyped() {
        val port = FakePort(writes = -1)
        val terminal = session(port)
        assertEquals(DshTerminalWriteResult.PeerClosed, terminal.write("ctrl-c".toByteArray()))
        terminal.close()
        val rejected = terminal.write("x".toByteArray())
        assertTrue(rejected is DshTerminalWriteResult.Rejected)
        assertEquals(DshTerminalFailureCode.ALREADY_CLOSED, (rejected as DshTerminalWriteResult.Rejected).code)
    }

    @Test
    fun closeIsIdempotentAndSignalsOnce() {
        val port = FakePort()
        val terminal = session(port)
        val first = terminal.close(DshTerminalCloseReason.SERVICE_STOPPED) as DshTerminalEvent.Closed
        assertTrue(first.outcome.settled)
        assertEquals(1, port.closeCalls)
        val second = terminal.close(DshTerminalCloseReason.CLIENT_CLOSED) as DshTerminalEvent.Closed
        assertEquals(first.outcome, second.outcome)
        assertEquals("a closed session must not be signalled twice", 1, port.closeCalls)
        assertEquals(DshTerminalSession.Phase.CLOSED, terminal.phase)
    }

    @Test
    fun unsettledStopIsReportedAsNotClosed() {
        val port = FakePort(
            closeOutcome = DshTerminalCloseOutcome(settled = false, escalated = true, detail = "kill budget exhausted")
        )
        val terminal = session(port)
        val event = terminal.close() as DshTerminalEvent.Closed
        assertFalse(event.outcome.settled)
        assertTrue(event.outcome.escalated)
        assertEquals(DshTerminalFailureCode.EXIT_UNOBSERVABLE, terminal.lastFailure)
    }

    @Test
    fun abandonReleasesWithoutClaimingAStop() {
        val port = FakePort()
        val terminal = session(port)
        terminal.abandon()
        assertEquals(1, port.abandonCalls)
        assertEquals(0, port.closeCalls)
        assertEquals(DshTerminalSession.Phase.CLOSED, terminal.phase)
        val event = terminal.close() as DshTerminalEvent.Closed
        assertFalse("abandon must not be reported as a settled stop", event.outcome.settled)
    }
}

/** Factory/fallback selection and the fail-closed native bridge. */
class DshTerminalFactoryTest {

    private val payloadRoot = "/data/user/0/com.muyuchat.mca/files/dsh/payload"
    private val nativeRoot = "/data/app/~~x/com.muyuchat.mca-y/lib/arm64"

    private fun model(
        nativeLibraries: Set<String> = setOf(
            DshCommandClosure.Native.NODE,
            DshCommandClosure.DISPATCHER_LIBRARY,
            DshCommandClosure.Native.PYTHON
        ),
        payloadFiles: Set<String> = setOf(
            DshCommandClosure.Payload.BASH,
            DshCommandClosure.Payload.NPM_CLI
        ),
        payloadDirectories: Set<String> = setOf(DshCommandClosure.Payload.PYTHON_STDLIB)
    ) = DshCommandClosureModel(
        payloadDir = payloadRoot,
        nativeLibraryDir = nativeRoot,
        payloadFiles = payloadFiles,
        payloadDirectories = payloadDirectories,
        nativeLibraries = nativeLibraries
    )

    private class FakeBridge(
        private val state: DshPtyAvailability,
        private val openResult: DshTerminalOpenResult? = null
    ) : DshPtyBridge {
        var openCalls = 0
        override val nativeLibrary = "libdshpty.so"

        override fun availability(): DshPtyAvailability = state

        override fun open(
            command: List<String>,
            environment: Map<String, String>,
            workingDirectory: String,
            size: DshTerminalSize
        ): DshTerminalOpenResult {
            openCalls++
            return openResult ?: DshTerminalOpenResult.Failed(
                DshTerminalUnavailableReason.OPEN_FAILED,
                "fake open failure"
            )
        }
    }

    private val request = DshTerminalOpenRequest(
        command = listOf("/system/bin/sh", "-i"),
        workingDirectory = "/data/user/0/com.muyuchat.mca/files/dsh/workspace"
    )

    @Test
    fun capabilityReportsPipeFallbackWithTheExactReason() {
        val factory = DshTerminalFactory(
            ptyBridge = FakeBridge(
                DshPtyAvailability.Unavailable(
                    DshTerminalUnavailableReason.NATIVE_LOAD_FAILED,
                    "dlopen failed: library \"libdshpty.so\" not found"
                )
            )
        )
        val capability = factory.capability()
        assertTrue(capability is DshTerminalCapability.PipeFallback)
        assertEquals(DshTerminalTransport.PIPE_FALLBACK, capability.transport)
        val fallback = capability as DshTerminalCapability.PipeFallback
        assertEquals(DshTerminalUnavailableReason.NATIVE_LOAD_FAILED, fallback.reason)
        assertTrue(fallback.detail.contains("libdshpty.so"))
    }

    @Test
    fun capabilityRefusesFallbackWhenTheCallerForbidsIt() {
        val factory = DshTerminalFactory(
            ptyBridge = FakeBridge(
                DshPtyAvailability.Unavailable(DshTerminalUnavailableReason.NATIVE_LIBRARY_MISSING, "not packaged")
            ),
            allowPipeFallback = false
        )
        assertTrue(factory.capability() is DshTerminalCapability.Unavailable)
        assertTrue(factory.open(request) is DshTerminalOpenResult.Failed)
    }

    @Test
    fun openUsesThePipeFallbackAndLabelsIt() {
        val pipePort = DshTerminalFactoryTestPort()
        val factory = DshTerminalFactory(
            ptyBridge = FakeBridge(
                DshPtyAvailability.Unavailable(DshTerminalUnavailableReason.NATIVE_LOAD_FAILED, "missing")
            ),
            pipeOpener = { DshTerminalOpenResult.Opened(pipePort) }
        )
        val opened = factory.open(request)
        assertTrue(opened is DshTerminalOpenResult.Opened)
        assertEquals(DshTerminalTransport.PIPE_FALLBACK, (opened as DshTerminalOpenResult.Opened).port.transport)
    }

    @Test
    fun bothTransportsFailingProducesOneTypedFailureWithBothReasons() {
        val factory = DshTerminalFactory(
            ptyBridge = FakeBridge(
                DshPtyAvailability.Unavailable(DshTerminalUnavailableReason.NATIVE_LIBRARY_MISSING, "lib absent")
            ),
            pipeOpener = {
                DshTerminalOpenResult.Failed(DshTerminalUnavailableReason.OPEN_FAILED, "spawn denied")
            }
        )
        val failed = factory.open(request) as DshTerminalOpenResult.Failed
        assertTrue(failed.detail.contains("lib absent"))
        assertTrue(failed.detail.contains("spawn denied"))
    }

    @Test
    fun nativePtyIsPreferredWhenItOpens() {
        val nativePort = DshTerminalFactoryTestPort(DshTerminalTransport.REAL_PTY)
        val factory = DshTerminalFactory(
            ptyBridge = FakeBridge(
                DshPtyAvailability.Loaded("libdshpty.so", "/dev/pts/7"),
                DshTerminalOpenResult.Opened(nativePort)
            )
        )
        val opened = factory.open(request) as DshTerminalOpenResult.Opened
        assertEquals(DshTerminalTransport.REAL_PTY, opened.port.transport)
    }

    @Test
    fun shellChoicePrefersBashButNeverClaimsGnuBash() {
        val factory = DshTerminalFactory(ptyBridge = FakeBridge(DshPtyAvailability.Unavailable(
            DshTerminalUnavailableReason.NATIVE_LIBRARY_MISSING, "no pty"
        )))
        val choice = factory.chooseShell(
            model = model(),
            workingDirectory = "/data/user/0/com.muyuchat.mca/files/dsh/workspace",
            environment = mapOf("HOME" to "/data/user/0/com.muyuchat.mca/files/dsh/home"),
            arguments = listOf("--noprofile", "--norc", "-i")
        )
        assertTrue(choice is DshTerminalShellChoice.Ready)
        val ready = choice as DshTerminalShellChoice.Ready
        assertEquals(DshCommandRole.BASH, ready.role)
        assertFalse(ready.gnuBash)
        assertTrue(ready.launch is DshCommandLaunch.DispatcherLink)
        assertEquals(
            listOf("$payloadRoot/bin/bash", "--noprofile", "--norc", "-i"),
            ready.request.command
        )
    }

    @Test
    fun shellChoiceFallsBackToPlatformShWithoutADispatcher() {
        val factory = DshTerminalFactory(ptyBridge = FakeBridge(DshPtyAvailability.Unavailable(
            DshTerminalUnavailableReason.NATIVE_LIBRARY_MISSING, "no pty"
        )))
        val choice = factory.chooseShell(
            model = model(nativeLibraries = setOf(DshCommandClosure.Native.NODE)),
            workingDirectory = "/tmp",
            environment = emptyMap(),
            arguments = listOf("-i")
        ) as DshTerminalShellChoice.Ready
        assertTrue(choice.launch is DshCommandLaunch.PlatformShell)
        assertEquals(listOf(DshCommandClosure.PLATFORM_SHELL, "-i"), choice.request.command)
        assertFalse(choice.gnuBash)
    }

    @Test
    fun shellChoiceIsTypedUnavailableWhenNothingCanStart() {
        val factory = DshTerminalFactory(ptyBridge = FakeBridge(DshPtyAvailability.Unavailable(
            DshTerminalUnavailableReason.NATIVE_LIBRARY_MISSING, "no pty"
        )))
        val empty = DshCommandClosureModel(
            payloadDir = payloadRoot,
            nativeLibraryDir = nativeRoot,
            payloadFiles = emptySet(),
            nativeLibraries = emptySet(),
            platformShells = emptySet()
        )
        val choice = factory.chooseShell(empty, "/tmp", emptyMap())
        assertTrue(choice is DshTerminalShellChoice.Unavailable)
        assertTrue((choice as DshTerminalShellChoice.Unavailable).detail.contains("shell"))
    }

    @Test
    fun openShellOnAnEmptyDeviceNeverStartsAnything() {
        val bridge = FakeBridge(
            DshPtyAvailability.Unavailable(DshTerminalUnavailableReason.NATIVE_LIBRARY_MISSING, "no pty")
        )
        val factory = DshTerminalFactory(ptyBridge = bridge)
        val empty = DshCommandClosureModel(
            payloadDir = payloadRoot,
            nativeLibraryDir = nativeRoot,
            payloadFiles = emptySet(),
            nativeLibraries = emptySet(),
            platformShells = emptySet()
        )
        val opened = factory.openShell(empty, "/tmp", emptyMap())
        assertTrue(opened is DshTerminalSessionOpen.Unavailable)
        assertEquals(0, bridge.openCalls)
    }

    @Test
    fun openShellReturnsAnOwnedSessionOnTheFallbackTransport() {
        val pipePort = DshTerminalFactoryTestPort()
        val factory = DshTerminalFactory(
            ptyBridge = FakeBridge(
                DshPtyAvailability.Unavailable(DshTerminalUnavailableReason.NATIVE_LIBRARY_MISSING, "no pty")
            ),
            pipeOpener = { DshTerminalOpenResult.Opened(pipePort) }
        )
        val opened = factory.openShell(
            model = model(),
            workingDirectory = "/data/user/0/com.muyuchat.mca/files/dsh/workspace",
            environment = mapOf("HOME" to "/data/user/0/com.muyuchat.mca/files/dsh/home")
        )
        assertTrue("expected an owned session, was $opened", opened is DshTerminalSessionOpen.Opened)
        val session = (opened as DshTerminalSessionOpen.Opened).session
        assertEquals(DshTerminalTransport.PIPE_FALLBACK, session.transport)
        assertFalse(session.isRealPty)
        assertEquals(DshCommandRole.BASH, opened.choice.role)
        assertFalse(opened.choice.gnuBash)
    }

    @Test
    fun nativeBridgeFailsClosedOnTheJvmInsteadOfThrowing() {
        val availability = DshPtyNative.availability()
        assertTrue(
            "the JVM has no arm64 libdshpty.so; the bridge must report a typed failure, was $availability",
            availability is DshPtyAvailability.Unavailable
        )
        val unavailable = availability as DshPtyAvailability.Unavailable
        assertEquals(DshTerminalUnavailableReason.NATIVE_LOAD_FAILED, unavailable.reason)
        assertTrue(unavailable.detail.isNotBlank())
        val failed = DshNativePtyBridge().open(
            command = listOf("/system/bin/sh"),
            environment = emptyMap(),
            workingDirectory = "/tmp",
            size = DshTerminalSize.DEFAULT
        )
        assertTrue(failed is DshTerminalOpenResult.Failed)
        assertEquals(DshTerminalUnavailableReason.NATIVE_LOAD_FAILED, (failed as DshTerminalOpenResult.Failed).reason)
    }
}

/** Minimal port for factory tests; never opened on a real transport. */
private class DshTerminalFactoryTestPort(
    override val transport: DshTerminalTransport = DshTerminalTransport.PIPE_FALLBACK
) : DshTerminalPort {
    override val isAtty: Boolean = transport == DshTerminalTransport.REAL_PTY
    override fun write(bytes: ByteArray): Int = bytes.size
    override fun read(buffer: ByteArray, timeoutMillis: Long): Int = -1
    override fun resize(size: DshTerminalSize): Boolean = transport == DshTerminalTransport.REAL_PTY
    override fun size(): DshTerminalSize? = null
    override fun pollExit(timeoutMillis: Long): DshTerminalExit? = null
    override fun close(graceMillis: Long, killMillis: Long): DshTerminalCloseOutcome =
        DshTerminalCloseOutcome(true, false, "never started")

    override fun abandon() = Unit
    override fun close() = Unit
}

/**
 * The explicit NON_PTY fallback is exercised against a real child process on this host (the JVM's
 * own java launcher), so the pipe plumbing, backpressure drain and exit observation are real.
 * This is not a terminal-equivalence test: resize and isatty must stay false.
 */
class DshPipeTerminalTest {

    private fun javaBinary(): String {
        val suffix = if (File.separatorChar == '\\') ".exe" else ""
        return File(System.getProperty("java.home") ?: "", "bin/java$suffix").absolutePath
    }

    @Test
    fun pipeFallbackRunsARealProcessAndReportsNonTty() {
        val request = DshTerminalOpenRequest(
            command = listOf(javaBinary(), "-version"),
            workingDirectory = Files.createTempDirectory("dsh-pipe").toFile().absolutePath
        )
        val opened = DshPipeTerminalPort.open(request)
        assertTrue("pipe open failed: $opened", opened is DshTerminalOpenResult.Opened)
        val port = (opened as DshTerminalOpenResult.Opened).port
        assertEquals(DshTerminalTransport.PIPE_FALLBACK, port.transport)
        assertFalse(port.isAtty)
        assertFalse("a pipe cannot resize", port.resize(DshTerminalSize(120, 40)))
        assertNull(port.size())

        val terminal = DshTerminalSession(port)
        val buffer = ByteArray(4096)
        val collected = StringBuilder()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val events = terminal.pump(buffer, 500)
            var eof = false
            for (event in events) {
                when (event) {
                    is DshTerminalEvent.Output -> collected.append(String(event.bytes))
                    is DshTerminalEvent.Exited -> eof = true
                    else -> Unit
                }
            }
            if (eof || terminal.observedEndOfStream) break
        }
        val exit = terminal.pollExit(20_000)
        assertTrue("the java launcher should have exited", exit != null)
        assertEquals(0, exit!!.code)
        assertTrue("output must have been captured: '$collected'", collected.isNotEmpty())
        val closed = terminal.close() as DshTerminalEvent.Closed
        assertTrue("close must settle: ${closed.outcome.detail}", closed.outcome.settled)
    }

    @Test
    fun missingExecutableIsATypedOpenFailure() {
        val request = DshTerminalOpenRequest(
            command = listOf("/definitely/not/a/binary"),
            workingDirectory = Files.createTempDirectory("dsh-pipe-fail").toFile().absolutePath
        )
        val opened = DshPipeTerminalPort.open(request)
        assertTrue("expected a typed failure, was $opened", opened is DshTerminalOpenResult.Failed)
        assertEquals(
            DshTerminalUnavailableReason.OPEN_FAILED,
            (opened as DshTerminalOpenResult.Failed).reason
        )
    }

    @Test
    fun writeToAFinishedChildReportsPeerClosedInsteadOfThrowing() {
        val request = DshTerminalOpenRequest(
            command = listOf(javaBinary(), "-version"),
            workingDirectory = Files.createTempDirectory("dsh-pipe-eof").toFile().absolutePath
        )
        val port = (DshPipeTerminalPort.open(request) as DshTerminalOpenResult.Opened).port
        val terminal = DshTerminalSession(port)
        assertTrue(terminal.pollExit(20_000) != null)
        // The child is gone: the write must be a typed result, never an exception.
        val result = terminal.write("q".toByteArray())
        assertTrue(result is DshTerminalWriteResult.PeerClosed || result is DshTerminalWriteResult.Written)
        assertTrue((terminal.close() as DshTerminalEvent.Closed).outcome.settled)
    }
}




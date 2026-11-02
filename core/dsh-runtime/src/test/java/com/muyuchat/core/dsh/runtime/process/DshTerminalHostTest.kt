package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseReason
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.contract.runtime.DshTerminalTransport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshTerminalHostTest {

    private val workingDirectory = File(System.getProperty("java.io.tmpdir")).absolutePath
    private val model = dshTestModel()

    private fun host(
        factory: com.muyuchat.core.dsh.runtime.terminal.DshTerminalFactory,
        initialOutputMillis: Long = 120L,
        maxSessions: Int = 8,
        retainedBytes: Int = DshTerminalHost.DEFAULT_RETAINED_BYTES
    ): DshTerminalHost = DshTerminalHost(
        factory = factory,
        modelProvider = { model },
        environmentProvider = { mapOf("PATH" to "/payload/bin:/system/bin", "TERM" to "xterm-256color") },
        workingDirectoryProvider = { workingDirectory },
        maxSessions = maxSessions,
        maxSessionsPerOwner = 4,
        retainedOutputBytes = retainedBytes,
        initialOutputMillis = initialOutputMillis,
        autoPump = false
    )

    private fun openOrFail(host: DshTerminalHost, owner: String = "dsh"): DshTerminalOpenOutcome.Opened {
        val outcome = host.open(owner)
        assertTrue("expected an opened session, was $outcome", outcome is DshTerminalOpenOutcome.Opened)
        return outcome as DshTerminalOpenOutcome.Opened
    }

    @Test
    fun openCapturesStartupOutputAndReportsTheRealTransport() {
        val port = ScriptedTerminalPort(initialOutput = listOf("welcome\r\n".toByteArray()))
        val host = host(fakeFactory(port))
        val opened = openOrFail(host)

        assertEquals(DshTerminalTransport.REAL_PTY, opened.snapshot.transport)
        assertTrue(opened.snapshot.realPty)
        assertEquals(DshTerminalStatus.RUNNING, opened.snapshot.status)
        assertEquals("welcome\r\n", opened.motd)
        assertEquals("dsh", opened.snapshot.ownerKey)
        assertNotNull(opened.snapshot.size)
        assertEquals(DshTerminalHost.SHELL_TYPE, opened.snapshot.type)
    }

    @Test
    fun interactiveInputSubmitAndCtrlCGoThroughTheTerminal() {
        val port = ScriptedTerminalPort()
        val host = host(fakeFactory(port))
        val session = openOrFail(host)

        val written = host.write("dsh", session.snapshot.sessionId, "echo hi", submit = true)
        assertTrue(written is DshTerminalWriteOutcome.Written)
        assertEquals("echo hi\r", port.writes.single().toString(Charsets.UTF_8))

        val ctrlC = host.control("dsh", session.snapshot.sessionId, DshTerminalControl.CTRL_C)
        assertTrue(ctrlC is DshTerminalWriteOutcome.Written)
        assertEquals(2, port.writes.size)
        assertEquals(0x03.toByte(), port.writes.last().single())

        val noSubmit = host.write("dsh", session.snapshot.sessionId, "\u001b[A", submit = false)
        assertTrue(noSubmit is DshTerminalWriteOutcome.Written)
        assertEquals("\u001b[A", port.writes.last().toString(Charsets.UTF_8))
    }

    @Test
    fun resizeIsAppliedAndReadBackForARealPty() {
        val port = ScriptedTerminalPort()
        val host = host(fakeFactory(port))
        val session = openOrFail(host)

        val outcome = host.resize("dsh", session.snapshot.sessionId, DshTerminalSize(120, 40))
        assertTrue("expected applied resize, was $outcome", outcome is DshTerminalResizeOutcome.Applied)
        assertEquals(listOf(DshTerminalSize(120, 40)), port.resizeRequests)
        assertEquals(120, host.list("dsh").single().size?.columns)
    }

    @Test
    fun thePipeFallbackIsReportedAndRejectsResizeAndCtrlC() {
        val pipePort = ScriptedTerminalPort(DshTerminalTransport.PIPE_FALLBACK)
        val host = host(fakeFactory(null, allowPipeFallback = true, pipePort = pipePort))
        val opened = openOrFail(host)

        assertEquals(DshTerminalTransport.PIPE_FALLBACK, opened.snapshot.transport)
        assertFalse(opened.snapshot.realPty)

        val resize = host.resize("dsh", opened.snapshot.sessionId, DshTerminalSize(100, 30))
        assertTrue("pipe resize must be unsupported, was $resize", resize is DshTerminalResizeOutcome.Unsupported)

        val ctrlC = host.control("dsh", opened.snapshot.sessionId, DshTerminalControl.CTRL_C)
        assertTrue(ctrlC is DshTerminalWriteOutcome.Refused)
        assertEquals(
            DshTerminalRefusal.PTY_UNAVAILABLE,
            (ctrlC as DshTerminalWriteOutcome.Refused).reason
        )
        assertTrue(pipePort.writes.isEmpty())
    }

    @Test
    fun aCallerThatRequiresARealPtyNeverReceivesThePipeFallback() {
        val pipePort = ScriptedTerminalPort(DshTerminalTransport.PIPE_FALLBACK)
        val host = host(fakeFactory(null, allowPipeFallback = true, pipePort = pipePort))

        val outcome = host.open("dsh", requireRealPty = true)
        assertTrue(outcome is DshTerminalOpenOutcome.Refused)
        val refusal = outcome as DshTerminalOpenOutcome.Refused
        assertEquals(DshTerminalRefusal.PTY_UNAVAILABLE, refusal.reason)
        assertTrue(refusal.detail.contains("libdshpty.so"))
        assertEquals("the discarded fallback must be stopped", 1, pipePort.closeCalls)
        assertTrue(host.list().isEmpty())
    }

    @Test
    fun reconnectReadsFromACursorWithoutRespawningTheChild() {
        val port = ScriptedTerminalPort(initialOutput = listOf("hello".toByteArray()))
        val host = host(fakeFactory(port))
        val session = openOrFail(host)
        val id = session.snapshot.sessionId

        port.push(" world".toByteArray())
        assertTrue(host.pumpForTest("dsh", id))

        val first = host.read("dsh", id, offset = 0L, maxBytes = 5)
        assertTrue(first is DshTerminalReadOutcome.Ok)
        assertEquals("hello", (first as DshTerminalReadOutcome.Ok).page.text)
        assertEquals(5L, first.page.nextOffset)

        val rest = host.read("dsh", id, offset = 5L, maxBytes = 100)
        assertEquals(" world", (rest as DshTerminalReadOutcome.Ok).page.text)
        assertEquals(11L, rest.page.nextOffset)

        val attached = host.attach("dsh", id, size = DshTerminalSize(90, 30))
        assertTrue(attached is DshTerminalReadOutcome.Ok)
        assertEquals("hello world", (attached as DshTerminalReadOutcome.Ok).page.text)
        assertEquals("a reconnect must keep the same single session", 1, host.list("dsh").size)
    }

    @Test
    fun theRetentionWindowIsBoundedAndReportsTruncation() {
        val first = ByteArray(3_000) { 'a'.code.toByte() }
        val second = ByteArray(3_000) { 'b'.code.toByte() }
        val port = ScriptedTerminalPort(initialOutput = listOf(first, second))
        val host = host(fakeFactory(port), initialOutputMillis = 200L, retainedBytes = 4 * 1024)
        val session = openOrFail(host)
        val id = session.snapshot.sessionId

        val page = host.read("dsh", id, offset = 0L, maxBytes = 8 * 1024) as DshTerminalReadOutcome.Ok
        assertTrue(page.page.truncated)
        assertTrue(page.page.text.length <= 4 * 1024)
        assertTrue(page.page.text.startsWith("a"))
        assertTrue(page.page.text.endsWith("b"))
        assertTrue(page.page.nextOffset > 4 * 1024)
    }

    @Test
    fun anExitedChildReportsItsExitAndRejectsFurtherInput() {
        val port = ScriptedTerminalPort(initialOutput = listOf("bye".toByteArray()))
        port.exit = com.muyuchat.core.dsh.contract.runtime.DshTerminalExit(code = 3)
        val host = host(fakeFactory(port))
        val session = openOrFail(host)
        val id = session.snapshot.sessionId

        assertEquals(DshTerminalStatus.EXITED, host.list("dsh").single().status)
        assertEquals(3, host.list("dsh").single().exit?.code)

        val write = host.write("dsh", id, "ignored")
        assertTrue(write is DshTerminalWriteOutcome.Refused)
        assertEquals(DshTerminalRefusal.SESSION_EXITED, (write as DshTerminalWriteOutcome.Refused).reason)

        val read = host.read("dsh", id) as DshTerminalReadOutcome.Ok
        assertEquals(DshTerminalStatus.EXITED, read.page.status)
        assertEquals(3, read.page.exit?.code)
    }

    @Test
    fun sessionsAreOwnerIsolatedAndCapped() {
        val host = host(fakeFactory(portProvider = { ScriptedTerminalPort() }), maxSessions = 1)
        val opened = openOrFail(host, owner = "agent-a")

        assertTrue(host.read("agent-b", opened.snapshot.sessionId) is DshTerminalReadOutcome.Refused)
        assertNull(host.close("agent-b", opened.snapshot.sessionId))
        assertEquals(1, host.list("agent-a").size)
        assertTrue(host.list("agent-b").isEmpty())

        val second = host.open("agent-a")
        assertTrue(second is DshTerminalOpenOutcome.Refused)
        assertEquals(DshTerminalRefusal.LIMIT_REACHED, (second as DshTerminalOpenOutcome.Refused).reason)
    }

    @Test
    fun closeAllSettlesEveryBackgroundSessionExactlyOnce() {
        val ports = mutableListOf<ScriptedTerminalPort>()
        val host = host(fakeFactory(portProvider = { ScriptedTerminalPort().also { ports.add(it) } }))
        openOrFail(host, owner = "agent-a")
        openOrFail(host, owner = "agent-b")

        val shutdown = host.closeAll(DshTerminalCloseReason.SERVICE_STOPPED)
        assertTrue(shutdown.settled)
        assertTrue(shutdown.survivors.isEmpty())
        assertEquals(2, ports.size)
        assertTrue(ports.all { it.closeCalls == 1 })
        assertTrue(host.list().isEmpty())

        val reopen = host.open("agent-a")
        assertEquals(
            DshTerminalRefusal.HOST_CLOSED,
            (reopen as DshTerminalOpenOutcome.Refused).reason
        )
    }

    @Test
    fun aFailedStopIsNeverReportedAsSettled() {
        val port = ScriptedTerminalPort().apply { settledOnClose = false }
        val host = host(fakeFactory(port))
        openOrFail(host)

        val shutdown = host.closeAll(DshTerminalCloseReason.SERVICE_STOPPED)
        assertFalse(shutdown.settled)
        assertEquals(1, shutdown.survivors.size)
        assertEquals("child not reaped within the kill budget", shutdown.sessions.single().outcome.detail)
    }

    @Test
    fun relativeWorkingDirectoriesAreRejected() {
        val host = host(fakeFactory(ScriptedTerminalPort()))
        val outcome = host.open("dsh", cwd = "relative/path")
        assertTrue(outcome is DshTerminalOpenOutcome.Refused)
        assertEquals(
            DshTerminalRefusal.INVALID_WORKING_DIRECTORY,
            (outcome as DshTerminalOpenOutcome.Refused).reason
        )
    }

    @Test
    fun unknownTypesAndNamesAreRejectedWithoutStartingAChild() {
        val port = ScriptedTerminalPort()
        val host = host(fakeFactory(port))

        val type = host.open("dsh", type = "gdb")
        assertEquals(
            DshTerminalRefusal.UNSUPPORTED_TYPE,
            (type as DshTerminalOpenOutcome.Refused).reason
        )
        val name = host.open("dsh", name = "bad\u0001name")
        assertEquals(
            DshTerminalRefusal.INVALID_NAME,
            (name as DshTerminalOpenOutcome.Refused).reason
        )
        assertEquals(0, port.readCalls)
    }
}

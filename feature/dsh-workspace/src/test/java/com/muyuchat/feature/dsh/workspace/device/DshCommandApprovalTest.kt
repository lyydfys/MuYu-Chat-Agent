package com.muyuchat.feature.dsh.workspace.device

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dangerous-command approval: masking, the single-pending queue and the suspend gate.
 *
 * The window itself is Compose and cannot be asserted here, so every rule is pinned on the pure parts:
 * what text reaches the card, when a request expires, that a decision is consumed exactly once, and that
 * every "not an explicit allow" path (disabled, no window, timeout, cancellation) ends as DENY.
 */
class DshCommandApprovalTest {

    /** The gate is process-global, so every test must leave it in the strict default state. */
    @After
    fun resetGlobalApproval() {
        DshCommandApproval.resetForTesting()
    }

    // ---------------------------------------------------------------- masking

    @Test
    fun maskKeepsTheCommandNameWhenThereAreNoArguments() {
        assertEquals("id", DshCommandApprovalMasking.mask("id"))
        assertEquals("id", DshCommandApprovalMasking.mask("  id  "))
    }

    @Test
    fun maskKeepsTheFirstTwoArguments() {
        val masked = DshCommandApprovalMasking.mask("dd if=/dev/block/sda of=/tmp/x")

        assertEquals("dd if=/dev/block/sda of=/tmp/x", masked)
    }

    @Test
    fun maskTruncatesALongTokenToTwentyFourCharacters() {
        val longArgument = "a".repeat(40)

        val masked = DshCommandApprovalMasking.mask("getprop $longArgument")

        assertEquals("getprop ${"a".repeat(24)}…", masked)
        assertTrue(masked.endsWith("…"))
    }

    @Test
    fun maskKeepsAQuotedArgumentTogether() {
        val masked = DshCommandApprovalMasking.mask("bash -c \"rm -rf /data\"")

        assertEquals("bash -c \"rm -rf /data\"", masked)
    }

    @Test
    fun maskNeverContainsMoreThanThreeTokens() {
        val masked = DshCommandApprovalMasking.mask("pm list packages -3 -e -f")
        val tokens = masked.removeSuffix(" …").split(' ')

        assertEquals(3, tokens.size)
        assertTrue(masked.endsWith(" …"))
    }

    @Test
    fun maskOfABlankCommandIsEmpty() {
        assertEquals("", DshCommandApprovalMasking.mask(""))
        assertEquals("", DshCommandApprovalMasking.mask("   \t "))
    }

    // ---------------------------------------------------------------- queue

    @Test
    fun registerReplacesThePendingRequestAndReportsTheReplacedId() {
        val queue = DshCommandApprovalQueue()

        queue.register(request(id = 1))
        val replacedId = queue.register(request(id = 2)).previousId

        assertEquals(1L, replacedId)
        assertEquals(2L, queue.current()?.id)
    }

    @Test
    fun theReplacedRequestIsAnsweredDenyImmediately() = runTest {
        val queue = DshCommandApprovalQueue()
        val first = queue.register(request(id = 1))

        queue.register(request(id = 2))

        assertEquals(DshCommandApprovalDecision.DENY, first.deferred.await())
    }

    @Test
    fun allowOnceIsConsumedExactlyOnce() {
        var now = 1_000L
        val queue = DshCommandApprovalQueue(clock = { now })
        queue.register(request(id = 7, atMillis = now))

        assertTrue(queue.decide(7, DshCommandApprovalDecision.ALLOW_ONCE))
        assertFalse(queue.decide(7, DshCommandApprovalDecision.ALLOW_ONCE))
        assertNull("the queue is empty after an answer", queue.current())
    }

    @Test
    fun aRepeatedDecisionNeverChangesTheFirstOne() = runTest {
        var now = 1_000L
        val queue = DshCommandApprovalQueue(clock = { now })
        val registration = queue.register(request(id = 3, atMillis = now))

        assertTrue(queue.decide(3, DshCommandApprovalDecision.DENY))
        assertFalse(queue.decide(3, DshCommandApprovalDecision.ALLOW_ONCE))
        assertEquals(DshCommandApprovalDecision.DENY, registration.deferred.await())
    }

    @Test
    fun decidingAnUnknownIdReturnsFalse() {
        val queue = DshCommandApprovalQueue()
        queue.register(request(id = 5))

        assertFalse(queue.decide(99, DshCommandApprovalDecision.ALLOW_ONCE))
        assertEquals(5L, queue.current()?.id)
    }

    @Test
    fun aStaleRequestExpiresToDeny() = runTest {
        var now = 1_000L
        val queue = DshCommandApprovalQueue(timeoutMillis = 30_000L, clock = { now })
        val registration = queue.register(request(id = 4, atMillis = now))

        now += 30_001L
        val expiredId = queue.expireIfDue()

        assertEquals(4L, expiredId)
        assertEquals(DshCommandApprovalDecision.DENY, registration.deferred.await())
        assertNull(queue.current())
    }

    @Test
    fun aDecisionAfterTheDeadlineIsDeniedAndRefused() = runTest {
        var now = 10_000L
        val queue = DshCommandApprovalQueue(timeoutMillis = 30_000L, clock = { now })
        val registration = queue.register(request(id = 8, atMillis = now))

        now += 30_001L
        val accepted = queue.decide(8, DshCommandApprovalDecision.ALLOW_ONCE)

        assertFalse("a late tap must not authorise anything", accepted)
        assertEquals(DshCommandApprovalDecision.DENY, registration.deferred.await())
    }

    @Test
    fun cancelAnswersDenyAndClearsTheQueue() = runTest {
        val queue = DshCommandApprovalQueue()
        val registration = queue.register(request(id = 9))

        assertTrue(queue.cancel(9))
        assertEquals(DshCommandApprovalDecision.DENY, registration.deferred.await())
        assertNull(queue.current())
        assertFalse(queue.cancel(9))
    }

    // ---------------------------------------------------------------- gate

    private class FakePresenter(private val show: Boolean = true) : DshCommandApprovalPresenter {
        val shown = mutableListOf<DshCommandApprovalRequest>()
        val dismissed = mutableListOf<Long>()

        override fun present(request: DshCommandApprovalRequest): Boolean {
            if (!show) return false
            shown += request
            return true
        }

        override fun dismiss(id: Long) {
            dismissed += id
        }
    }

    private class Harness(
        val presenter: FakePresenter?,
        var enabled: Boolean = true,
        timeoutMillis: Long = 30_000L
    ) {
        var now = 1_000L
        var nextId = 0L
        val queue = DshCommandApprovalQueue(timeoutMillis = timeoutMillis, clock = { now })
        val gate = DshCommandApprovalGate(
            queue = queue,
            presenter = { presenter },
            enabled = { enabled },
            clock = { now },
            nextRequestId = { ++nextId }
        )
    }

    @Test
    fun aDisabledGateDeniesWithoutShowingAnything() = runTest {
        val presenter = FakePresenter()
        val harness = Harness(presenter, enabled = false)

        val decision = harness.gate.await(
            DshCommandApprovalKind.ADB,
            "dd x",
            DshDevicePolicyRejection.DENIED_COMMAND.code
        )

        assertEquals(DshCommandApprovalDecision.DENY, decision)
        assertTrue("the switch is off: no card may be shown", presenter.shown.isEmpty())
        assertNull(harness.queue.current())
    }

    @Test
    fun withoutAPresenterTheGateDenies() = runTest {
        val harness = Harness(presenter = null)

        val decision = harness.gate.await(
            DshCommandApprovalKind.ROOT,
            "rm x",
            DshDevicePolicyRejection.DENIED_COMMAND.code
        )

        assertEquals(DshCommandApprovalDecision.DENY, decision)
        assertNull(harness.queue.current())
    }

    @Test
    fun aWindowThatCannotShowTheCardMeansDeny() = runTest {
        // "未开启外显": the presenter exists but has no window to draw on.
        val presenter = FakePresenter(show = false)
        val harness = Harness(presenter)

        val decision = harness.gate.await(
            DshCommandApprovalKind.ADB,
            "dd x",
            DshDevicePolicyRejection.DENIED_COMMAND.code
        )

        assertEquals(DshCommandApprovalDecision.DENY, decision)
        assertTrue(presenter.shown.isEmpty())
        assertNull("no ghost request may stay waitable", harness.queue.current())
    }

    @Test
    fun allowOnceFromTheWindowIsReturned() = runTest {
        val presenter = FakePresenter()
        val harness = Harness(presenter)
        var decision: DshCommandApprovalDecision? = null

        val job = launch {
            decision = harness.gate.await(
                DshCommandApprovalKind.ADB,
                "dd if=/dev/block/sda of=/tmp/x …",
                DshDevicePolicyRejection.DENIED_COMMAND.code
            )
        }
        runCurrent()
        val shown = presenter.shown.single()
        harness.queue.decide(shown.id, DshCommandApprovalDecision.ALLOW_ONCE)
        job.join()

        assertEquals(DshCommandApprovalDecision.ALLOW_ONCE, decision)
        assertEquals(listOf(shown.id), presenter.dismissed)
        assertEquals(1L, shown.id)
        assertEquals("dd if=/dev/block/sda of=/tmp/x …", shown.commandMasked)
        assertEquals("denied_command", shown.reasonCode)
    }

    @Test
    fun denyFromTheWindowIsReturned() = runTest {
        val presenter = FakePresenter()
        val harness = Harness(presenter)
        var decision: DshCommandApprovalDecision? = null

        val job = launch {
            decision = harness.gate.await(
                DshCommandApprovalKind.ROOT,
                "reboot now",
                DshDevicePolicyRejection.DENIED_COMMAND.code
            )
        }
        runCurrent()
        harness.queue.decide(presenter.shown.single().id, DshCommandApprovalDecision.DENY)
        job.join()

        assertEquals(DshCommandApprovalDecision.DENY, decision)
        assertEquals(1, presenter.dismissed.size)
    }

    @Test
    fun theThirtySecondTimeoutDeniesAndDismisses() = runTest {
        val presenter = FakePresenter()
        val harness = Harness(presenter)
        var decision: DshCommandApprovalDecision? = null

        val job = launch {
            decision = harness.gate.await(
                DshCommandApprovalKind.ADB,
                "dd x",
                DshDevicePolicyRejection.DENIED_COMMAND.code
            )
        }
        runCurrent()
        assertEquals(1, presenter.shown.size)

        advanceTimeBy(30_001L)
        job.join()

        assertEquals(DshCommandApprovalDecision.DENY, decision)
        assertEquals(listOf(presenter.shown.single().id), presenter.dismissed)
        assertNull(harness.queue.current())
    }

    @Test
    fun cancellationCleansUpTheCardAndRethrows() = runTest {
        val presenter = FakePresenter()
        val harness = Harness(presenter)

        val job = launch {
            harness.gate.await(
                DshCommandApprovalKind.ADB,
                "dd x",
                DshDevicePolicyRejection.DENIED_COMMAND.code
            )
        }
        runCurrent()
        job.cancel()
        job.join()

        assertTrue("a cancelled wait must never be reported as an allow", job.isCancelled)
        assertNull(harness.queue.current())
        assertEquals(listOf(presenter.shown.single().id), presenter.dismissed)
    }

    // ---------------------------------------------------------------- policy seams

    @Test
    fun onlySemanticRefusalsAreApprovable() {
        assertTrue(DshDevicePolicyRejection.DENIED_COMMAND.isApprovalCandidate())
        assertTrue(DshDevicePolicyRejection.DENIED_ARGUMENT.isApprovalCandidate())
        assertTrue(DshDevicePolicyRejection.NOT_WHITELISTED.isApprovalCandidate())
        assertFalse(DshDevicePolicyRejection.SHELL_COMPOSITION.isApprovalCandidate())
        assertFalse(DshDevicePolicyRejection.EMPTY_COMMAND.isApprovalCandidate())
        assertFalse(DshDevicePolicyRejection.COMMAND_TOO_LONG.isApprovalCandidate())
        assertFalse(DshDevicePolicyRejection.EMPTY_SEGMENT.isApprovalCandidate())
    }

    @Test
    fun anApprovedCommandIsReRenderedWithQuoteEscaping() {
        val segments = requireNotNull(dshStructuralSegments("bash -c \"rm -rf /data\""))
        val rendered = DshDeviceCommandPolicy.renderSafe(segments)

        assertEquals("bash -c 'rm -rf /data'", rendered)
    }

    @Test
    fun aShellCompositionCannotBeApproved() {
        assertNull(dshStructuralSegments("rm \$(whoami)"))
        assertNull(dshStructuralSegments("dd if=/dev/zero > /dev/block/sda"))
    }

    @Test
    fun aStructuralPathCannotBeApproved() {
        assertNull(dshStructuralSegments("/system/bin/rm -rf /data"))
    }

    // ---------------------------------------------------------------- channel integration

    private class RecordingTransport(
        private var current: DshAdbTransportState = DshAdbTransportState.CONNECTED
    ) : AdbTransport {
        val commands = mutableListOf<String>()

        override fun state(): DshAdbTransportState = current

        override fun lastFailure(): DshDeviceCommandFailure? = null

        override suspend fun connect(endpoint: DshAdbEndpoint): Boolean {
            current = DshAdbTransportState.CONNECTED
            return true
        }

        override suspend fun shell(command: String): DshAdbShellResult {
            commands += command
            return DshAdbShellResult(exitCode = 0, stdout = "ok", stderr = "")
        }

        override fun close() {
            current = DshAdbTransportState.NOT_CONFIGURED
        }
    }

    private fun installGlobalApproval(presenter: DshCommandApprovalPresenter?, enabled: Boolean) {
        DshCommandApproval.resetForTesting()
        if (presenter != null) DshCommandApproval.installPresenter(presenter)
        DshCommandApproval.setEnabledProvider { enabled }
    }

    @Test
    fun anApprovedCommandReachesTheTransportOnceWithTheRenderedString() = runTest {
        val presenter = FakePresenter()
        installGlobalApproval(presenter, enabled = true)
        try {
            val transport = RecordingTransport()
            val channel = DshAdbChannel(transport)
            var result: DshDeviceCommandResult? = null

            val job = launch { result = channel.exec("bash -c \"rm -rf /data\"") }
            runCurrent()
            assertEquals("bash -c \"rm -rf /data\"", presenter.shown.single().commandMasked)
            DshCommandApproval.decide(
                presenter.shown.single().id,
                DshCommandApprovalDecision.ALLOW_ONCE
            )
            job.join()

            assertEquals("the exact re-rendered, quote-safe string runs", listOf("bash -c 'rm -rf /data'"), transport.commands)
            assertEquals(0, result?.exitCode)
        } finally {
            DshCommandApproval.resetForTesting()
        }
    }

    @Test
    fun aDeniedCommandNeverReachesTheTransport() = runTest {
        val presenter = FakePresenter()
        installGlobalApproval(presenter, enabled = true)
        try {
            val transport = RecordingTransport()
            val channel = DshAdbChannel(transport)
            var result: DshDeviceCommandResult? = null

            val job = launch { result = channel.exec("rm -rf /data") }
            runCurrent()
            DshCommandApproval.decide(
                presenter.shown.single().id,
                DshCommandApprovalDecision.DENY
            )
            job.join()

            assertTrue(transport.commands.isEmpty())
            assertEquals("policy_blocked", result?.reasonCode)
            assertTrue(result?.stderr.orEmpty().startsWith("[POLICY_BLOCKED]"))
        } finally {
            DshCommandApproval.resetForTesting()
        }
    }

    @Test
    fun withApprovalSwitchedOffTheRefusalIsImmediateAndNothingIsShown() = runTest {
        val presenter = FakePresenter()
        installGlobalApproval(presenter, enabled = false)
        try {
            val transport = RecordingTransport()
            val channel = DshAdbChannel(transport)

            val result = channel.exec("rm -rf /data")

            assertEquals("policy_blocked", result.reasonCode)
            assertTrue(transport.commands.isEmpty())
            assertTrue("switched off means no card at all", presenter.shown.isEmpty())
        } finally {
            DshCommandApproval.resetForTesting()
        }
    }

    private fun request(id: Long, atMillis: Long = 0L) = DshCommandApprovalRequest(
        id = id,
        kind = DshCommandApprovalKind.ADB,
        commandMasked = "dd x",
        reasonCode = DshDevicePolicyRejection.DENIED_COMMAND.code,
        atMillis = atMillis
    )
}

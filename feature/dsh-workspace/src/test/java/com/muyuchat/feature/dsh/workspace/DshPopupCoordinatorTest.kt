package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.console.DshPopupKind
import com.muyuchat.core.dsh.contract.console.DshPopupLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshPopupCoordinatorTest {

    private class FakeClock(var nowMillis: Long = 1_000L) {
        operator fun invoke(): Long = nowMillis
    }

    private fun coordinator(clock: FakeClock = FakeClock()): DshPopupCoordinator =
        DshPopupCoordinator(clock = clock::invoke)

    @Test
    fun beginCreatesASingleUseTicket() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)

        val created = coordinator.begin(
            "https://example.test/callback?code=1",
            DshPopupKind.ACCOUNT_AUTH
        ) as DshPopupBegin.Created

        assertEquals(1L, created.request.ticketId)
        assertEquals(DshPopupKind.ACCOUNT_AUTH, created.request.kind)
        assertEquals(1_000L, created.request.createdAtMillis)
        assertEquals(
            1_000L + DshPopupLimits.MAX_LIFETIME_MILLIS,
            created.request.deadlineAtMillis
        )
        assertEquals(1, coordinator.pendingCount())
    }

    @Test
    fun aTicketIsHandedOffExactlyOnce() {
        val coordinator = coordinator()
        val ticket = created(coordinator.begin("https://example.test/a", DshPopupKind.NORMAL_EXTERNAL))

        val first = coordinator.claim(ticket.request.ticketId)
        val second = coordinator.claim(ticket.request.ticketId)

        assertTrue(first is DshPopupClaim.HandedOff)
        assertEquals(DshPopupClaim.Replayed, second)
        assertEquals(0, coordinator.pendingCount())
    }

    @Test
    fun claimAfterTheDeadlineReportsExpiryWithoutHandoff() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)
        val ticket = created(coordinator.begin("https://example.test/a", DshPopupKind.ACCOUNT_AUTH))
        clock.nowMillis = ticket.request.deadlineAtMillis

        val claim = coordinator.claim(ticket.request.ticketId)

        assertTrue(claim is DshPopupClaim.Expired)
        assertEquals(ticket.request.ticketId, (claim as DshPopupClaim.Expired).request.ticketId)
    }

    @Test
    fun expireReturnsEveryPendingExpiredTicket() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)
        val first = created(coordinator.begin("https://example.test/a", DshPopupKind.NORMAL_EXTERNAL))
        val second = created(coordinator.begin("https://example.test/b", DshPopupKind.NORMAL_EXTERNAL))
        clock.nowMillis = second.request.deadlineAtMillis

        val expired = coordinator.expire()

        assertEquals(listOf(first.request.ticketId, second.request.ticketId), expired.map { it.ticketId })
        assertEquals(0, coordinator.pendingCount())
    }

    @Test
    fun cancellingAPendingTicketPreventsHandoff() {
        val coordinator = coordinator()
        val ticket = created(coordinator.begin("https://example.test/a", DshPopupKind.ACCOUNT_AUTH))

        assertTrue(coordinator.cancel(ticket.request.ticketId))
        val claim = coordinator.claim(ticket.request.ticketId)

        assertTrue(claim is DshPopupClaim.Cancelled)
        assertEquals(0, coordinator.pendingCount())
    }

    @Test
    fun cancellingAnAlreadyClaimedTicketIsHarmless() {
        val coordinator = coordinator()
        val ticket = created(coordinator.begin("https://example.test/a", DshPopupKind.NORMAL_EXTERNAL))
        coordinator.claim(ticket.request.ticketId)

        assertTrue(!coordinator.cancel(ticket.request.ticketId))
        assertEquals(DshPopupClaim.Replayed, coordinator.claim(ticket.request.ticketId))
    }

    @Test
    fun unknownTicketClaimIsTyped() {
        assertEquals(DshPopupClaim.Unknown, coordinator().claim(42L))
    }

    @Test
    fun pendingCapacityIsBounded() {
        val coordinator = DshPopupCoordinator(maxPendingTickets = 1, clock = FakeClock()::invoke)
        created(coordinator.begin("https://example.test/a", DshPopupKind.NORMAL_EXTERNAL))

        val rejected = coordinator.begin("https://example.test/b", DshPopupKind.NORMAL_EXTERNAL)

        assertEquals(
            DshPopupBegin.Rejected(DshPopupBeginRejection.CAPACITY_EXCEEDED),
            rejected
        )
    }

    @Test
    fun invalidUrlIsRejectedBeforeAllocatingATicket() {
        val coordinator = coordinator()

        val rejected = coordinator.begin("javascript:alert(1)", DshPopupKind.NORMAL_EXTERNAL)

        assertEquals(
            DshPopupBegin.Rejected(DshPopupBeginRejection.INVALID_URL),
            rejected
        )
        assertEquals(0, coordinator.pendingCount())
    }

    @Test
    fun deadlineInThePastIsRejected() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)

        val rejected = coordinator.begin(
            "https://example.test/a",
            DshPopupKind.ACCOUNT_AUTH,
            requestedDeadlineMillis = 999L
        )

        assertEquals(
            DshPopupBegin.Rejected(DshPopupBeginRejection.INVALID_DEADLINE),
            rejected
        )
    }

    @Test
    fun requestedDeadlineIsClampedToTheContractMaximum() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)

        val created = created(
            coordinator.begin(
                "https://example.test/a",
                DshPopupKind.ACCOUNT_AUTH,
                requestedDeadlineMillis = 10_000_000L
            )
        )

        assertEquals(
            1_000L + DshPopupLimits.MAX_LIFETIME_MILLIS,
            created.request.deadlineAtMillis
        )
    }

    @Test
    fun aRequestedDeadlineInsideTheBoundIsHonoured() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)

        val created = created(
            coordinator.begin(
                "https://example.test/a",
                DshPopupKind.ACCOUNT_AUTH,
                requestedDeadlineMillis = 6_000L
            )
        )

        assertEquals(6_000L, created.request.deadlineAtMillis)
    }

    @Test
    fun aHandedOffWindowCanBeReleasedExactlyOnce() {
        val coordinator = coordinator()
        val ticket = created(coordinator.begin("https://example.test/auth", DshPopupKind.ACCOUNT_AUTH))
        assertTrue(coordinator.claim(ticket.request.ticketId) is DshPopupClaim.HandedOff)

        assertEquals(
            DshPopupWindowRelease.RECORDED,
            coordinator.releaseHandedOffWindow(ticket.request.ticketId)
        )
        assertEquals(
            "a repeated page-close is not a second fact",
            DshPopupWindowRelease.ALREADY_RELEASED,
            coordinator.releaseHandedOffWindow(ticket.request.ticketId)
        )
        assertEquals(
            "a released window already served its one handoff",
            DshPopupClaim.Replayed,
            coordinator.claim(ticket.request.ticketId)
        )
        assertEquals(0, coordinator.pendingCount())
    }

    @Test
    fun aReleaseWithoutAHandoffIsRefused() {
        val coordinator = coordinator()
        val pending = created(coordinator.begin("https://example.test/auth", DshPopupKind.ACCOUNT_AUTH))
        assertEquals(
            DshPopupWindowRelease.NOT_HANDED_OFF,
            coordinator.releaseHandedOffWindow(pending.request.ticketId)
        )
        // The refused release did not consume the pending ticket.
        assertTrue(coordinator.claim(pending.request.ticketId) is DshPopupClaim.HandedOff)

        val cancelled = created(
            coordinator.begin("https://example.test/b", DshPopupKind.ACCOUNT_AUTH)
        )
        assertTrue(coordinator.cancel(cancelled.request.ticketId))
        assertEquals(
            DshPopupWindowRelease.NOT_HANDED_OFF,
            coordinator.releaseHandedOffWindow(cancelled.request.ticketId)
        )

        assertEquals(
            DshPopupWindowRelease.NOT_HANDED_OFF,
            coordinator.releaseHandedOffWindow(42L)
        )
    }

    @Test
    fun anExpiredTicketNeverAcceptsARelease() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)
        val ticket = created(coordinator.begin("https://example.test/auth", DshPopupKind.ACCOUNT_AUTH))
        clock.nowMillis = ticket.request.deadlineAtMillis
        assertTrue(coordinator.claim(ticket.request.ticketId) is DshPopupClaim.Expired)

        assertEquals(
            DshPopupWindowRelease.NOT_HANDED_OFF,
            coordinator.releaseHandedOffWindow(ticket.request.ticketId)
        )
    }

    private fun created(begin: DshPopupBegin): DshPopupBegin.Created {
        assertTrue("expected a created ticket, got $begin", begin is DshPopupBegin.Created)
        return begin as DshPopupBegin.Created
    }
}

package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.console.DshPopupKind
import com.muyuchat.core.dsh.contract.console.DshPopupLimits
import com.muyuchat.core.dsh.contract.console.DshPopupRequest

/** Why a popup could not become a pending ticket. */
enum class DshPopupBeginRejection {
    INVALID_URL,
    INVALID_DEADLINE,
    CAPACITY_EXCEEDED
}

/** Result of opening one popup ticket. */
sealed interface DshPopupBegin {
    data class Created(val request: DshPopupRequest) : DshPopupBegin
    data class Rejected(val reason: DshPopupBeginRejection) : DshPopupBegin
}

/** Single-use claim result of a popup ticket. */
sealed interface DshPopupClaim {
    data class HandedOff(val request: DshPopupRequest) : DshPopupClaim
    data class Cancelled(val request: DshPopupRequest) : DshPopupClaim
    data class Expired(val request: DshPopupRequest) : DshPopupClaim
    data object Unknown : DshPopupClaim
    data object Replayed : DshPopupClaim
}

/** What the ledger recorded when a handed-off popup window was released by the page. */
enum class DshPopupWindowRelease {
    /** The window of a handed-off ticket was released; the handoff is final. */
    RECORDED,

    /** Nothing was handed off for this ticket (unknown, pending, cancelled or expired). */
    NOT_HANDED_OFF,

    /** The same release was already recorded; a repeated page-close is not a new fact. */
    ALREADY_RELEASED
}

/**
 * Bounded, single-use popup ticket ledger for the WebView `onCreateWindow` seam (D04/D25).
 *
 * The WebView layer owns transport, not browser policy. A popup becomes a typed
 * [DshPopupRequest] here and is claimed exactly once before it is handed to the injected
 * browser port. Terminal states are explicit:
 *  - `claim` after a handoff returns [DshPopupClaim.Replayed] (the same URL is never opened twice);
 *  - `cancel` (page closed the window / host left composition) makes a later claim
 *    [DshPopupClaim.Cancelled];
 *  - the deadline makes a later claim [DshPopupClaim.Expired] even if `expire()` was never called;
 *  - `releaseHandedOffWindow` records the page-close of an already handed-off window exactly once,
 *    which is the edge the account lane turns into its terminal state.
 *
 * Time is injected so timeout behaviour is unit-testable without sleeping. The coordinator holds
 * at most [DshPopupLimits.MAX_PENDING_TICKETS] pending tickets and a bounded terminal history, so a
 * page cannot accumulate hidden popups or grow this table without limit.
 */
class DshPopupCoordinator(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPendingTickets: Int = DshPopupLimits.MAX_PENDING_TICKETS,
    private val maxLifetimeMillis: Long = DshPopupLimits.MAX_LIFETIME_MILLIS
) {
    init {
        require(maxPendingTickets >= 1) { "popup ticket budget must be positive" }
        require(maxLifetimeMillis in 1L..DshPopupLimits.MAX_LIFETIME_MILLIS) {
            "popup ticket lifetime is out of range"
        }
    }

    private enum class State { PENDING, HANDED_OFF, RELEASED, CANCELLED, EXPIRED }

    private class Ticket(val request: DshPopupRequest, var state: State)

    private val lock = Any()
    private val tickets = LinkedHashMap<Long, Ticket>()
    private var nextTicketId = 1L

    /** Creates one pending ticket. [requestedDeadlineMillis] may come from the official attempt. */
    fun begin(
        url: String?,
        kind: DshPopupKind,
        requestedDeadlineMillis: Long? = null
    ): DshPopupBegin = synchronized(lock) {
        val now = clock()
        pruneTerminalHistory()
        if (tickets.values.count { it.state == State.PENDING } >= maxPendingTickets) {
            return@synchronized DshPopupBegin.Rejected(DshPopupBeginRejection.CAPACITY_EXCEEDED)
        }
        val deadline = resolveDeadline(now, requestedDeadlineMillis)
            ?: return@synchronized DshPopupBegin.Rejected(DshPopupBeginRejection.INVALID_DEADLINE)
        val candidate = url?.trim().orEmpty()
        if (candidate.isEmpty()) {
            return@synchronized DshPopupBegin.Rejected(DshPopupBeginRejection.INVALID_URL)
        }
        val request = runCatching {
            DshPopupRequest(
                ticketId = nextTicketId,
                url = candidate,
                kind = kind,
                createdAtMillis = now,
                deadlineAtMillis = deadline
            )
        }.getOrNull() ?: return@synchronized DshPopupBegin.Rejected(DshPopupBeginRejection.INVALID_URL)
        nextTicketId += 1
        tickets[request.ticketId] = Ticket(request, State.PENDING)
        DshPopupBegin.Created(request)
    }

    /** Claims a ticket exactly once. Called immediately before handing the URL to the browser port. */
    fun claim(ticketId: Long): DshPopupClaim = synchronized(lock) {
        val ticket = tickets[ticketId] ?: return@synchronized DshPopupClaim.Unknown
        return@synchronized when (ticket.state) {
            State.PENDING ->
                if (ticket.request.isExpiredAt(clock())) {
                    ticket.state = State.EXPIRED
                    DshPopupClaim.Expired(ticket.request)
                } else {
                    ticket.state = State.HANDED_OFF
                    DshPopupClaim.HandedOff(ticket.request)
                }

            State.HANDED_OFF -> DshPopupClaim.Replayed
            // A released window already served its one handoff; it is never re-opened.
            State.RELEASED -> DshPopupClaim.Replayed
            State.CANCELLED -> DshPopupClaim.Cancelled(ticket.request)
            State.EXPIRED -> DshPopupClaim.Expired(ticket.request)
        }
    }

    /**
     * Records that the page (or the account UI acting through it) released the window of a
     * handed-off ticket.
     *
     * The ledger only acknowledges a release for a ticket that was actually handed off and records
     * it once, so a container can use [DshPopupWindowRelease.RECORDED] as the single edge that
     * turns a page-close into one downstream fact (for the account popup: the official attempt
     * ended without a host-observed success). A release for any other ticket state is not a fact
     * about a handoff and is reported as such instead of being silently accepted.
     */
    fun releaseHandedOffWindow(ticketId: Long): DshPopupWindowRelease = synchronized(lock) {
        val ticket = tickets[ticketId] ?: return@synchronized DshPopupWindowRelease.NOT_HANDED_OFF
        return@synchronized when (ticket.state) {
            State.HANDED_OFF -> {
                ticket.state = State.RELEASED
                DshPopupWindowRelease.RECORDED
            }

            State.RELEASED -> DshPopupWindowRelease.ALREADY_RELEASED
            State.PENDING,
            State.CANCELLED,
            State.EXPIRED -> DshPopupWindowRelease.NOT_HANDED_OFF
        }
    }

    /** Cancels a still-pending ticket (page closed the popup or the host left composition). */
    fun cancel(ticketId: Long): Boolean = synchronized(lock) {
        val ticket = tickets[ticketId] ?: return@synchronized false
        if (ticket.state == State.PENDING) {
            ticket.state = State.CANCELLED
            true
        } else {
            false
        }
    }

    /** Expires every pending ticket whose deadline elapsed. Returns them for notification. */
    fun expire(nowMillis: Long = clock()): List<DshPopupRequest> = synchronized(lock) {
        val expired = mutableListOf<DshPopupRequest>()
        tickets.values.forEach { ticket ->
            if (ticket.state == State.PENDING && ticket.request.isExpiredAt(nowMillis)) {
                ticket.state = State.EXPIRED
                expired += ticket.request
            }
        }
        expired
    }

    fun pendingCount(): Int = synchronized(lock) {
        tickets.values.count { it.state == State.PENDING }
    }

    private fun resolveDeadline(nowMillis: Long, requestedDeadlineMillis: Long?): Long? {
        val requested = requestedDeadlineMillis ?: (nowMillis + maxLifetimeMillis)
        if (requested <= nowMillis) return null
        return requested.coerceAtMost(nowMillis + maxLifetimeMillis)
    }

    private fun pruneTerminalHistory() {
        if (tickets.size <= TERMINAL_HISTORY_LIMIT) return
        val iterator = tickets.entries.iterator()
        while (tickets.size > TERMINAL_HISTORY_LIMIT && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.state != State.PENDING) {
                iterator.remove()
            }
        }
    }

    private companion object {
        const val TERMINAL_HISTORY_LIMIT = 32
    }
}

package com.muyuchat.mca.dsh.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests of the overlay policy. No Android class is loaded: the reducer is pure, so these cover
 * the states a device would otherwise be needed to reach (permission refusal, service death,
 * window attach/detach) without any Android stub assertion.
 */
class DshOverlayStateReducerTest {

    private fun reduce(
        initial: DshOverlayState,
        vararg events: DshOverlayEvent
    ): DshOverlayState = events.fold(initial) { state, event ->
        DshOverlayStateReducer.reduce(state, event)
    }

    private fun granted(): DshOverlayState =
        reduce(DshOverlayState(), DshOverlayEvent.PermissionObserved(DshOverlayPermissionState.GRANTED))

    private fun attached(): DshOverlayState = reduce(
        granted(),
        DshOverlayEvent.ShowRequested,
        DshOverlayEvent.WindowAttached
    )

    @Test
    fun permission_denied_refuses_show_and_keeps_hide_a_no_op() {
        val denied = reduce(
            DshOverlayState(),
            DshOverlayEvent.PermissionObserved(DshOverlayPermissionState.DENIED)
        )
        val refused = reduce(denied, DshOverlayEvent.ShowRequested)

        assertFalse(refused.visible)
        assertFalse(refused.running)
        assertEquals(DshOverlayServiceState.STOPPED, refused.service)
        assertEquals(DshOverlayFailureCode.PERMISSION_DENIED, refused.failure?.code)

        val hidden = reduce(refused, DshOverlayEvent.HideRequested)
        assertFalse(hidden.visible)
        assertFalse(hidden.panelOpen)
    }

    @Test
    fun losing_the_permission_detaches_an_attached_window() {
        val revoked = reduce(
            reduce(attached(), DshOverlayEvent.MarkTapped),
            DshOverlayEvent.PermissionObserved(DshOverlayPermissionState.DENIED)
        )

        assertFalse(revoked.visible)
        assertFalse(revoked.panelOpen)
        assertEquals(DshOverlayServiceState.STOPPED, revoked.service)
        assertEquals(DshOverlayFailureCode.PERMISSION_DENIED, revoked.failure?.code)
    }

    @Test
    fun show_attach_hide_show_is_idempotent_and_leaks_no_window() {
        val showing = reduce(granted(), DshOverlayEvent.ShowRequested)
        assertEquals(DshOverlayServiceState.STARTING, showing.service)
        assertFalse(showing.visible)

        val visible = reduce(showing, DshOverlayEvent.WindowAttached)
        assertTrue(visible.visible)
        assertTrue(visible.running)
        assertEquals(visible, reduce(visible, DshOverlayEvent.ShowRequested))

        val hidden = reduce(visible, DshOverlayEvent.HideRequested)
        assertFalse(hidden.visible)
        assertFalse(hidden.panelOpen)
        assertEquals(DshOverlayServiceState.STOPPED, hidden.service)
        assertFalse(hidden.running)

        val reshown = reduce(hidden, DshOverlayEvent.ShowRequested)
        assertEquals(DshOverlayServiceState.STARTING, reshown.service)
        val reattached = reduce(reshown, DshOverlayEvent.WindowAttached)
        assertTrue(reattached.visible)
        assertFalse(reattached.panelOpen)
    }

    @Test
    fun service_death_clears_window_and_panel() {
        val dead = reduce(
            reduce(attached(), DshOverlayEvent.MarkTapped),
            DshOverlayEvent.ServiceDestroyed
        )

        assertFalse(dead.visible)
        assertFalse(dead.panelOpen)
        assertFalse(dead.running)
        assertEquals(DshOverlayServiceState.STOPPED, dead.service)
    }

    @Test
    fun provider_status_changes_update_text_only() {
        val before = attached()
        val next = DshOverlayStatus.of(
            runtimePhase = "READY",
            generation = 7L,
            engineOnline = true,
            capabilitySummary = "NOTIFICATION GRANTED · PRIVILEGED_SHELL DENIED"
        )
        val after = reduce(before, DshOverlayEvent.StatusUpdated(next))

        assertEquals(next, after.status)
        assertTrue(after.engineOnline)
        assertTrue(after.visible)
        assertEquals("READY · gen 7", after.status.headline)
    }

    @Test
    fun the_mark_tap_only_toggles_while_a_window_is_attached() {
        val detached = granted()
        assertEquals(detached, reduce(detached, DshOverlayEvent.MarkTapped))

        val opened = reduce(attached(), DshOverlayEvent.MarkTapped)
        assertTrue(opened.panelOpen)
        val closed = reduce(opened, DshOverlayEvent.MarkTapped)
        assertFalse(closed.panelOpen)
    }

    @Test
    fun a_service_start_failure_is_typed_and_retryable() {
        val failure = DshOverlayFailure.of(
            DshOverlayFailureCode.SERVICE_START_FAILED,
            "无法启动悬浮窗服务"
        )
        val failed = reduce(granted(), DshOverlayEvent.StartFailed(failure))

        assertEquals(DshOverlayServiceState.FAILED, failed.service)
        assertEquals(failure, failed.failure)
        assertFalse(failed.visible)

        val retried = reduce(failed, DshOverlayEvent.ShowRequested)
        assertEquals(DshOverlayServiceState.STARTING, retried.service)
        assertNull(retried.failure)
    }

    @Test
    fun status_and_failure_text_is_sanitized_and_bounded() {
        val status = DshOverlayStatus.of(
            runtimePhase = "READY\ninjected",
            generation = 3L,
            engineOnline = true,
            capabilitySummary = "x".repeat(500)
        )
        assertEquals("READY injected", status.runtimePhase)
        assertTrue(status.capabilitySummary.length <= DshOverlayStatus.MAX_SUMMARY_CHARS)

        val unavailable = DshOverlayStatus.unavailable()
        assertFalse(unavailable.engineOnline)
        assertEquals("UNKNOWN · gen 0", unavailable.headline)

        val failure = DshOverlayFailure.of(
            DshOverlayFailureCode.INTERNAL,
            "line1\nline2\t\u0007"
        )
        assertEquals("line1 line2", failure.message)
        assertNotNull(DshOverlayFailure.of(DshOverlayFailureCode.INTERNAL, "  ").message)
    }
}

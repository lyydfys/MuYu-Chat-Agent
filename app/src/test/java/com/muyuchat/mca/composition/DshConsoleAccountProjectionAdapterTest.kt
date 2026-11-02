package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptBounds
import com.muyuchat.core.dsh.contract.auth.DshBrowserHandoffResult
import com.muyuchat.core.dsh.contract.auth.DshUnauthorizedScope
import com.muyuchat.core.dsh.contract.console.DshConsoleAccountReason
import com.muyuchat.core.dsh.contract.console.DshConsoleAccountState
import com.muyuchat.core.dsh.contract.console.DshExternalBrowserPort
import com.muyuchat.core.dsh.contract.console.DshPopupHandoffResult
import com.muyuchat.core.dsh.contract.console.DshPopupKind
import com.muyuchat.core.dsh.contract.console.DshPopupRequest
import com.muyuchat.core.dsh.runtime.auth.DshAccountAttemptJournal
import com.muyuchat.core.dsh.runtime.auth.DshAccountAttemptMarker
import com.muyuchat.core.dsh.runtime.auth.DshAccountLoginOwner
import com.muyuchat.core.dsh.runtime.auth.DshAccountUnauthorizedOutcome
import com.muyuchat.core.dsh.runtime.auth.DshExternalBrowserLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM projection tests of the D20 account row on top of the real account lane.
 *
 * The account owner is pure JVM (its ports are injected), so these tests drive the real attempt
 * correlation and only inspect the bounded console projection. They pin the honest edges: no
 * attempt, waiting for the browser/callback, timeout, cancel, a launch-cookie 401 (which must not
 * sign the account out or claim a login), a lost runtime generation, and the absence of every URL
 * or credential in the projection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshConsoleAccountProjectionAdapterTest {

    private var now = 1_000_000L
    private var origin: String? = TRUSTED_ORIGIN
    private val bounds = DshAuthAttemptBounds()

    private class FakeJournal(
        var marker: DshAccountAttemptMarker? = null
    ) : DshAccountAttemptJournal {
        override fun load(): DshAccountAttemptMarker? = marker
        override fun save(marker: DshAccountAttemptMarker) {
            this.marker = marker
        }

        override fun clear() {
            marker = null
        }
    }

    private class FakeLauncher : DshExternalBrowserLauncher {
        override fun open(url: String): DshBrowserHandoffResult = DshBrowserHandoffResult.Opened
    }

    private class FakeExternalPort : DshExternalBrowserPort {
        override suspend fun openPopup(request: DshPopupRequest): DshPopupHandoffResult =
            DshPopupHandoffResult.HandedOff
    }

    private fun owner(
        scope: CoroutineScope,
        journal: DshAccountAttemptJournal = FakeJournal()
    ): DshAccountLoginOwner = DshAccountLoginOwner(
        scope = scope,
        callbackOrigin = { origin },
        trustedWebOrigin = { origin },
        launcher = FakeLauncher(),
        externalFallback = FakeExternalPort(),
        journal = journal,
        bounds = bounds,
        clock = { now }
    )

    private fun popupRequest(
        url: String = AUTHORIZE_URL,
        createdAtMillis: Long = now,
        deadlineAtMillis: Long = now + 60_000L
    ): DshPopupRequest = DshPopupRequest(
        ticketId = 1L,
        url = url,
        kind = DshPopupKind.ACCOUNT_AUTH,
        createdAtMillis = createdAtMillis,
        deadlineAtMillis = deadlineAtMillis
    )

    @Test
    fun aMissingAccountSourceIsProjectedAsTypedUnavailable() {
        val adapter = DshConsoleAccountProjectionAdapter(attemptView = { null })

        val projection = adapter.project()

        assertEquals(DshConsoleAccountState.UNKNOWN, projection.state)
        assertEquals(DshConsoleAccountReason.SOURCE_UNAVAILABLE, projection.reason)
        assertEquals(0L, projection.attemptAgeMillis)
    }

    @Test
    fun noAttemptProjectsNotStarted() = runTest {
        val owner = owner(backgroundScope)
        val adapter = DshConsoleAccountProjectionAdapter({ owner.attemptView() }, { now })

        val projection = adapter.project()

        assertEquals(DshConsoleAccountState.NOT_STARTED, projection.state)
        assertEquals(DshConsoleAccountReason.NONE, projection.reason)
        assertEquals(0L, projection.attemptAgeMillis)
    }

    @Test
    fun aWaitingAttemptProjectsBrowserThenCallbackWithAnObservedAge() = runTest {
        val owner = owner(backgroundScope)
        val adapter = DshConsoleAccountProjectionAdapter({ owner.attemptView() }, { now })

        owner.onPopupPreopened(TRUSTED_ORIGIN)
        val waitingBrowser = adapter.project()
        assertEquals(DshConsoleAccountState.WAITING_BROWSER, waitingBrowser.state)
        assertEquals(DshConsoleAccountReason.AUTHORIZE_URL_PENDING, waitingBrowser.reason)
        assertEquals(0L, waitingBrowser.attemptAgeMillis)

        now += 3_000L
        assertEquals(3_000L, adapter.project().attemptAgeMillis)

        assertEquals(
            DshPopupHandoffResult.HandedOff,
            owner.openPopup(popupRequest())
        )
        val waitingCallback = adapter.project()
        assertEquals(DshConsoleAccountState.WAITING_CALLBACK, waitingCallback.state)
        assertEquals(DshConsoleAccountReason.CALLBACK_PENDING, waitingCallback.reason)
        assertEquals(3_000L, waitingCallback.attemptAgeMillis)

        now += 2_000L
        assertEquals(5_000L, adapter.project().attemptAgeMillis)
    }

    @Test
    fun theBoundedDeadlineProjectsTimeoutWithoutClaimingAnything() = runTest {
        val owner = owner(backgroundScope)
        val adapter = DshConsoleAccountProjectionAdapter({ owner.attemptView() }, { now })
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        assertEquals(0L, adapter.project().attemptAgeMillis)

        now += 1_000L
        adapter.project()

        // Expire the attempt through its public popup path (its own ticket deadline elapsed).
        now += bounds.defaultAttemptMillis
        assertEquals(
            DshPopupHandoffResult.Expired,
            owner.openPopup(
                popupRequest(
                    createdAtMillis = now - 2_000L,
                    deadlineAtMillis = now - 1_000L
                )
            )
        )

        val timedOut = adapter.project()
        assertEquals(DshConsoleAccountState.TIMED_OUT, timedOut.state)
        assertEquals(DshConsoleAccountReason.ATTEMPT_TIMEOUT, timedOut.reason)
        assertEquals("the last observed live age is kept", 1_000L, timedOut.attemptAgeMillis)
    }

    @Test
    fun cancellingThePreopenedWindowProjectsCancelled() = runTest {
        val owner = owner(backgroundScope)
        val adapter = DshConsoleAccountProjectionAdapter({ owner.attemptView() }, { now })
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        // The attempt start is not exposed, so age is observed from the first projection.
        assertEquals(0L, adapter.project().attemptAgeMillis)
        now += 1_500L
        assertEquals(1_500L, adapter.project().attemptAgeMillis)

        owner.onPopupAbandoned()

        val cancelled = adapter.project()
        assertEquals(DshConsoleAccountState.CANCELLED, cancelled.state)
        assertEquals(DshConsoleAccountReason.ATTEMPT_CANCELLED, cancelled.reason)
        assertEquals(1_500L, cancelled.attemptAgeMillis)
    }

    @Test
    fun theOfficialCancelSignalDropsWaitingCallbackAndProjectsCancelled() = runTest {
        val owner = owner(backgroundScope)
        val adapter = DshConsoleAccountProjectionAdapter({ owner.attemptView() }, { now })
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest())
        val waiting = adapter.project()
        assertEquals(DshConsoleAccountState.WAITING_CALLBACK, waiting.state)
        assertEquals(DshConsoleAccountReason.CALLBACK_PENDING, waiting.reason)

        now += 2_000L
        // The age is an observation of a live attempt; keep one live projection so the terminal
        // row can still report how long the app saw it.
        assertEquals(2_000L, adapter.project().attemptAgeMillis)
        // The official "等待登录" dialog 取消 closes the pre-opened window; the container reports it.
        owner.onOfficialPopupReleased()

        val cancelled = adapter.project()
        assertEquals(
            "the console may not keep claiming a callback that can never arrive",
            DshConsoleAccountState.CANCELLED,
            cancelled.state
        )
        assertFalse(cancelled.state == DshConsoleAccountState.WAITING_CALLBACK)
        assertEquals(DshConsoleAccountReason.ATTEMPT_CANCELLED, cancelled.reason)
        assertEquals("the observed age of the live attempt is kept", 2_000L, cancelled.attemptAgeMillis)
    }

    @Test
    fun aWebSession401NeverClaimsALoginOrCancelsTheAttempt() = runTest {
        val owner = owner(backgroundScope)
        val adapter = DshConsoleAccountProjectionAdapter({ owner.attemptView() }, { now })
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest())
        assertEquals(DshConsoleAccountState.WAITING_CALLBACK, adapter.project().state)

        owner.onWebSessionUnauthorized()
        assertEquals(
            "the launch cookie is not the account credential",
            DshAccountUnauthorizedOutcome.NOT_ACCOUNT_SCOPE,
            owner.onUnauthorized(DshUnauthorizedScope.WEB_COOKIE, 401)
        )
        assertEquals(
            "MCA holds no credential record, so only the official store may invalidate it",
            DshAccountUnauthorizedOutcome.OFFICIAL_STORE_ONLY,
            owner.onUnauthorized(DshUnauthorizedScope.INFERENCE, 401)
        )

        val afterUnauthorized = adapter.project()
        assertEquals(DshConsoleAccountState.WAITING_CALLBACK, afterUnauthorized.state)
        assertEquals(DshConsoleAccountReason.CALLBACK_PENDING, afterUnauthorized.reason)
        assertFalse(afterUnauthorized.state == DshConsoleAccountState.CANCELLED)
    }

    @Test
    fun aLostRuntimeGenerationEndsTheAttemptWithoutSuccess() = runTest {
        val owner = owner(backgroundScope)
        val adapter = DshConsoleAccountProjectionAdapter({ owner.attemptView() }, { now })
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest())
        assertEquals(DshConsoleAccountState.WAITING_CALLBACK, adapter.project().state)

        // The DSH host stopped: the account lane's deadline ticker observes the lost generation.
        origin = null
        testScheduler.advanceTimeBy(2_000L)
        testScheduler.runCurrent()

        val lost = adapter.project()
        assertEquals(DshConsoleAccountState.FAILED, lost.state)
        assertEquals(DshConsoleAccountReason.RUNTIME_GENERATION_LOST, lost.reason)
    }

    @Test
    fun aNewRuntimeGenerationNeverInheritsTheOldAttempt() = runTest {
        val owner = owner(backgroundScope)
        val adapter = DshConsoleAccountProjectionAdapter({ owner.attemptView() }, { now })
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest())
        assertEquals(DshConsoleAccountState.WAITING_CALLBACK, adapter.project().state)

        origin = "http://127.0.0.1:31234"

        val nextGeneration = adapter.project()
        assertEquals(
            "the old attempt belonged to the old host and is not projected as still waiting",
            DshConsoleAccountState.NOT_STARTED,
            nextGeneration.state
        )
        assertEquals(0L, nextGeneration.attemptAgeMillis)
    }

    @Test
    fun aRestoredUnknownOutcomeIsProjectedAsUnknownWithoutObservedAge() = runTest {
        val journal = FakeJournal(
            DshAccountAttemptMarker(
                attemptId = "attempt-from-killed-process",
                active = true,
                updatedAtEpochMillis = now
            )
        )
        val owner = owner(backgroundScope, journal = journal)
        val adapter = DshConsoleAccountProjectionAdapter({ owner.attemptView() }, { now })

        val projection = adapter.project()

        assertEquals(DshConsoleAccountState.UNKNOWN, projection.state)
        assertEquals(DshConsoleAccountReason.RUNTIME_GENERATION_LOST, projection.reason)
        assertEquals("a restored attempt was never observed live", 0L, projection.attemptAgeMillis)
        assertTrue("the restore marker is still the durable fact", journal.marker?.active == true)
    }

    @Test
    fun theProjectionNeverCarriesTheAuthorizeUrlQueryOrACredential() = runTest {
        val owner = owner(backgroundScope)
        val adapter = DshConsoleAccountProjectionAdapter({ owner.attemptView() }, { now })
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest())

        val projection = adapter.project()
        val text = projection.toString()
        assertFalse(text.contains("http", ignoreCase = true))
        assertFalse(text.contains("?"))
        // `state=` is also this data class's own field label; assert on URL-query shapes instead.
        assertFalse(text.contains("?state="))
        assertFalse(text.contains("&state="))
        assertFalse(text.contains("code_challenge"))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("cookie", ignoreCase = true))
        assertEquals(
            listOf("state", "reason"),
            projection.toString()
                .removePrefix("DshConsoleAccountProjection(")
                .removeSuffix(")")
                .split(", ")
                .map { it.substringBefore('=') }
        )
    }

    private companion object {
        const val TRUSTED_ORIGIN = "http://127.0.0.1:25140"
        const val AUTHORIZE_URL =
            "https://platform.deepseek.com/dsh/authorize?state=s&code_challenge=c" +
                "&code_challenge_method=S256&redirect_uri=http%3A%2F%2F127.0.0.1%3A25140%2Foauth" +
                "%2Fcallback&login_source=web"
    }
}

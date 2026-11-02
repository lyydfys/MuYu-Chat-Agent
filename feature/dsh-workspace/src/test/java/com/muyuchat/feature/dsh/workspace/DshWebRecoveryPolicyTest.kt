package com.muyuchat.feature.dsh.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class DshWebRecoveryPolicyTest {

    @Test
    fun healthyPageAfterBackgroundingIsNotReloaded() {
        assertEquals(
            DshWebRecoveryAction.NONE,
            DshWebRecoveryPolicy.onForeground(
                backgroundMillis = 120_000L,
                loadingInterrupted = false,
                hasWebPage = true
            )
        )
    }

    @Test
    fun interruptedLoadIsReloadedAfterALongBackground() {
        assertEquals(
            DshWebRecoveryAction.RELOAD_CURRENT_PAGE,
            DshWebRecoveryPolicy.onForeground(
                backgroundMillis = DshWebRecoveryPolicy.STALE_BACKGROUND_MILLIS,
                loadingInterrupted = true,
                hasWebPage = true
            )
        )
    }

    @Test
    fun aShortBackgroundNeverReloadsEvenWhenALoadWasInterrupted() {
        assertEquals(
            DshWebRecoveryAction.NONE,
            DshWebRecoveryPolicy.onForeground(
                backgroundMillis = 5_000L,
                loadingInterrupted = true,
                hasWebPage = true
            )
        )
    }

    @Test
    fun noPageMeansNothingToRecover() {
        assertEquals(
            DshWebRecoveryAction.NONE,
            DshWebRecoveryPolicy.onForeground(
                backgroundMillis = 120_000L,
                loadingInterrupted = true,
                hasWebPage = false
            )
        )
    }

    @Test
    fun negativeBackgroundDurationIsTreatedAsNoRecovery() {
        assertEquals(
            DshWebRecoveryAction.NONE,
            DshWebRecoveryPolicy.onForeground(
                backgroundMillis = -1L,
                loadingInterrupted = true,
                hasWebPage = true
            )
        )
    }

    @Test
    fun rendererDeathRecreatesThePageInsteadOfRestartingTheRuntime() {
        assertEquals(
            DshWebRecoveryAction.RELOAD_CURRENT_PAGE,
            DshWebRecoveryPolicy.onRendererGone()
        )
    }

    @Test
    fun aPendingTokenEntryIsNeverReplayedWhenTheHostIsRecreated() {
        assertEquals(
            DshWebRecoveryAction.RELOAD_CURRENT_PAGE,
            DshWebRecoveryPolicy.onHostRecreated(authenticatedEntryPending = true)
        )
        assertEquals(
            DshWebRecoveryAction.NONE,
            DshWebRecoveryPolicy.onHostRecreated(authenticatedEntryPending = false)
        )
    }
}

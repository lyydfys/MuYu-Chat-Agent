@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.agent

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

internal fun shortName(value: String?): String? =
    value?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.let { if (it.length > 32) it.take(29) + "..." else it }

internal val AgentTuningJobState.isActive: Boolean
    get() = when (this) {
        AgentTuningJobState.QUEUED,
        AgentTuningJobState.RUNNING,
        AgentTuningJobState.PAUSED,
        AgentTuningJobState.CANCELING,
        AgentTuningJobState.VALIDATING,
        AgentTuningJobState.RECOVERING -> true

        AgentTuningJobState.IDLE,
        AgentTuningJobState.SUCCEEDED,
        AgentTuningJobState.FAILED -> false
    }

/**
 * A staged candidate deliberately uses PAUSED while it waits for explicit user
 * approval. After process recreation the same persisted pending transaction is
 * surfaced as VALIDATING even though no validation coroutine is running. Busy
 * remains the concurrency guard; these idle states must therefore keep the
 * explicit "apply and reload" action available.
 */
internal val AgentTuningJobState.canApplyPendingWhenIdle: Boolean
    get() = when (this) {
        AgentTuningJobState.IDLE,
        AgentTuningJobState.PAUSED,
        AgentTuningJobState.VALIDATING,
        AgentTuningJobState.SUCCEEDED,
        AgentTuningJobState.FAILED -> true

        AgentTuningJobState.QUEUED,
        AgentTuningJobState.RUNNING,
        AgentTuningJobState.CANCELING,
        AgentTuningJobState.RECOVERING -> false
    }

internal val AgentTuningJobState.canStart: Boolean
    get() = this == AgentTuningJobState.IDLE ||
        this == AgentTuningJobState.SUCCEEDED ||
        this == AgentTuningJobState.FAILED

private val AgentTuningJobState.canPause: Boolean
    get() = this == AgentTuningJobState.RUNNING || this == AgentTuningJobState.VALIDATING

internal fun AgentTuningJobState.canPauseSearch(hasReadyPendingProfile: Boolean): Boolean =
    canPause && !hasReadyPendingProfile

private val AgentTuningJobState.canResume: Boolean
    get() = this == AgentTuningJobState.PAUSED

internal fun AgentTuningJobState.canResumeSearch(hasReadyPendingProfile: Boolean): Boolean =
    canResume && !hasReadyPendingProfile

private val AgentTuningJobState.canCancel: Boolean
    get() = when (this) {
        AgentTuningJobState.QUEUED,
        AgentTuningJobState.RUNNING,
        AgentTuningJobState.PAUSED,
        AgentTuningJobState.VALIDATING,
        AgentTuningJobState.RECOVERING -> true

        AgentTuningJobState.IDLE,
        AgentTuningJobState.CANCELING,
        AgentTuningJobState.SUCCEEDED,
        AgentTuningJobState.FAILED -> false
    }

internal fun AgentTuningJobState.canCancelSearch(hasReadyPendingProfile: Boolean): Boolean =
    canCancel && !hasReadyPendingProfile

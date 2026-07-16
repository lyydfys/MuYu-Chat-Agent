package com.muyuchat.feature.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingProfileApplyPolicyTest {
    @Test
    fun stagedAndRestoredPendingProfilesRemainApplicable() {
        assertTrue(AgentTuningJobState.PAUSED.canApplyPendingWhenIdle)
        assertTrue(AgentTuningJobState.VALIDATING.canApplyPendingWhenIdle)
    }

    @Test
    fun activelyMutatingJobsCannotApplyAnotherPendingProfile() {
        listOf(
            AgentTuningJobState.QUEUED,
            AgentTuningJobState.RUNNING,
            AgentTuningJobState.CANCELING,
            AgentTuningJobState.RECOVERING
        ).forEach { state ->
            assertFalse("$state must keep apply disabled", state.canApplyPendingWhenIdle)
        }
    }

    @Test
    fun readyPendingProfileUsesApplyOrDiscardInsteadOfResumingADeadSearch() {
        assertFalse(AgentTuningJobState.VALIDATING.canPauseSearch(hasReadyPendingProfile = true))
        assertFalse(AgentTuningJobState.PAUSED.canResumeSearch(hasReadyPendingProfile = true))
        assertFalse(AgentTuningJobState.PAUSED.canCancelSearch(hasReadyPendingProfile = true))
        assertTrue(AgentTuningJobState.VALIDATING.canPauseSearch(hasReadyPendingProfile = false))
        assertTrue(AgentTuningJobState.PAUSED.canResumeSearch(hasReadyPendingProfile = false))
        assertTrue(AgentTuningJobState.PAUSED.canCancelSearch(hasReadyPendingProfile = false))
    }
}

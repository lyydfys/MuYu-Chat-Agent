package com.muyuchat.mca

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the local chat send path that dispatched nothing.
 *
 * `MainViewModel.startGeneration` publishes the run job before starting it, so at the moment the
 * generation body reaches the "wait for the foreground recovery probe" step, the published job is
 * the running job itself. The upstream rule is "await the recovery job, never yourself"; the
 * refactor (`318a7b17`) inverted the operands and made every LOCAL send join its own job, which
 * suspends forever: no worker dispatch, no terminal, no timeout, and the foreground lease stays
 * held until somebody cancels the run.
 */
class ChatGenerationRecoveryWaitTest {

    @Test
    fun `nothing is awaited when no foreground recovery job is running`() {
        assertNull(recoveryJobToAwait(null, null))
        val self = Job()
        try {
            assertNull(recoveryJobToAwait(null, self))
        } finally {
            self.cancel()
        }
    }

    @Test
    fun `the caller never awaits itself`() {
        val self = Job()
        try {
            assertNull("a job that joins itself suspends forever", recoveryJobToAwait(self, self))
        } finally {
            self.cancel()
        }
    }

    @Test
    fun `a distinct foreground recovery job is awaited`() {
        val self = Job()
        val recovery = Job()
        try {
            assertEquals(recovery, recoveryJobToAwait(recovery, self))
            assertEquals(recovery, recoveryJobToAwait(recovery, null))
        } finally {
            self.cancel()
            recovery.cancel()
        }
    }

    @Test
    fun `a local send does not await its own published job when no recovery is running`() = runBlocking {
        // Mirrors MainViewModel.startGeneration: publish the run, then start it. The wait must
        // resolve to "nothing" and the body must complete; joining the published job here would
        // suspend the run forever (the defect this test pins).
        val chatRuns = ChatRunOwnership()
        val completed = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val run = scope.launch(start = CoroutineStart.LAZY) {
                val selfJob = requireNotNull(coroutineContext[Job]) { "a run body must own a job" }
                chatRuns.publishJob(selfJob)
                recoveryJobToAwait(null, selfJob)?.join()
                completed.complete(Unit)
            }
            run.start()
            withTimeout(SEND_COMPLETION_TIMEOUT_MS) { completed.await() }
            assertTrue("the local send must not await its own job", completed.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val SEND_COMPLETION_TIMEOUT_MS = 5_000L
    }
}

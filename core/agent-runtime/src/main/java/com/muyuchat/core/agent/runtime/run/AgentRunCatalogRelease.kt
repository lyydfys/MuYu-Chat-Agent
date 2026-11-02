package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Exactly-once release for a run-scoped tool catalog. Runs in [NonCancellable] so a cancelled
 * execution still closes its per-run clients; the call happens after every model turn and tool
 * execution of that execution has ended.
 *
 * A non-cancellation release failure is best-effort and cannot replace the computed
 * [AgentLoopResult]. A release CancellationException propagates as cancellation.
 */
internal suspend fun releaseRunCatalog(
    provider: AgentRunToolCatalogProvider,
    run: AgentRun
) {
    withContext(NonCancellable) {
        try {
            provider.release(run)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Release is best-effort; the run outcome is authoritative.
        }
    }
}

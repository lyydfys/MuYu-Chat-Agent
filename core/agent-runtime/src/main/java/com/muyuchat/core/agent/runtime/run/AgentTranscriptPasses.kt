package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.runtime.AgentTranscriptEntry

/**
 * The context passes applied to the transcript **view** one model call sees.
 *
 * Order matters and is not interchangeable:
 *  1. [AgentToolResultDeduper.dedupe] first — it replaces repeat observations with a short pointer, so
 *     the compactor then has far less to cut and the first (authoritative) copy of a result is the one
 *     that survives in full;
 *  2. [compactTranscript] second — squeezes whatever single observations are still large.
 *
 * This is deliberately a *view*: the authoritative `entries` list the loop accumulates is never
 * mutated, so the transcript reported on pause/completion still holds every observation verbatim and
 * a resumed run can re-render it. Running the passes on the live list would bake an irreversible
 * lossy cut into the run's own record.
 *
 * Both passes are idempotent and both preserve entry count and ordering, so a caller may apply this
 * on every step without the transcript drifting.
 */
internal fun List<AgentTranscriptEntry>.applyContextPasses(): List<AgentTranscriptEntry> {
    if (isEmpty()) return this
    return compactTranscript(AgentToolResultDeduper.dedupe(this))
}

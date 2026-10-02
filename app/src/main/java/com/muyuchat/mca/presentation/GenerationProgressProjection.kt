package com.muyuchat.mca.presentation

import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.mca.MainUiState

// The generation-progress quartet of MainUiState, set as one unit.
//
// Why this exists: MainUiState.generationPhase, generationTokenProgress,
// generationPersistProgress and generationStats describe one thing -- where the current
// generation is and how far it has got -- but they were written by hand at seventeen copy()
// sites in MainViewModel. Every one of those sites listed the fields individually, and twelve of
// them deliberately omit at least one field so that Kotlin's copy() keeps the previous value.
// That is a correctness hazard the compiler cannot see: naming three of the four fields produces
// a state where, say, the phase has been reset but the token progress has not, and nothing warns.
//
// This helper makes the four move together when a caller does want to set all four. It is
// deliberately a plain four-parameter function rather than a pair of named resets: the call sites
// it replaces pass explicit values for all four, including the streaming site that enters DECODE
// with the new stats. Passing the values through unchanged is what makes the replacement an
// equivalence rather than a behaviour change.
//
// It does NOT replace the twelve sites that omit a field. Those keep copy()'s "leave it alone"
// semantics on purpose, and forcing them through this helper would silently write null over a
// value that was meant to survive. Moving any of them is a separate decision, per site.
//
// Purely a state constructor: no I/O, no engine, no Room, no API. Safe to unit test by
// construction, which is how the five replacements were checked.

/**
 * Returns [this] with the whole generation-progress quartet replaced by the given values.
 *
 * Every one of the four is set, so callers must supply all four -- that is the point. If a caller
 * wants to leave one alone, it should keep using `copy(...)` directly and say so.
 */
internal fun MainUiState.withGenerationProgress(
    phase: GenerationPhase?,
    tokenProgress: TokenProgress?,
    persistProgress: PersistProgress?,
    stats: RuntimeStats?
): MainUiState = copy(
    generationPhase = phase,
    generationTokenProgress = tokenProgress,
    generationPersistProgress = persistProgress,
    generationStats = stats
)

/**
 * The idle/cleared form of the quartet: nothing in flight, nothing carried over.
 *
 * This is the shape the start, restart, completion and termination sites all used, spelled out by
 * hand four times. Naming it makes "a generation just ended or has not begun" a single readable
 * fact rather than four assignments that have to agree.
 */
internal fun MainUiState.withGenerationProgressCleared(): MainUiState =
    withGenerationProgress(
        phase = null,
        tokenProgress = null,
        persistProgress = null,
        stats = null
    )

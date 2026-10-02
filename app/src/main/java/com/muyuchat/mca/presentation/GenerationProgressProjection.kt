package com.muyuchat.mca.presentation

import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.mca.MainUiState

// The generation-progress quartet of MainUiState, set as one unit.
//
// Why this exists: MainUiState.generationPhase, generationTokenProgress,
// generationPersistProgress and generationStats are written by hand at seventeen copy() sites in
// MainViewModel, and twelve of those sites deliberately omit at least one field so that Kotlin's
// copy() keeps the previous value. Naming three of the four is easy to do by accident and the
// compiler cannot see it, so a caller that does want to set all four should say so once.
//
// An earlier version of this comment claimed the four "describe one thing". That was too strong,
// and the sites that omit a field are the counter-example: they divide into in-flight state --
// phase, tokenProgress, persistProgress -- and the last result, stats. publishReconciledChatSessions
// is the clearest case: when the active session is corrected underneath an in-flight generation it
// clears the in-flight three and keeps stats, because the user should still see what the previous
// generation produced. So the omissions are a structured split, not nine unrelated oversights, and
// this helper must not be applied where that split is what the code means.
//
// This helper makes the four move together when a caller does want to set all four. It is a plain
// four-parameter function rather than a pair of named resets: the call sites it replaces pass
// explicit values for all four, including the streaming site that enters DECODE with the new stats.
// Passing the values through unchanged is what makes the replacements equivalences.
//
// It does NOT replace the twelve sites that omit a field. Those keep copy()'s "leave it alone"
// semantics on purpose, and forcing them through this helper would silently write null over a
// value that was meant to survive. Moving any of them is a separate decision, per site.
//
// Purely a state constructor: no I/O, no engine, no Room, no API. Safe to unit test by
// construction, which is how the five replacements were checked.

/**
 * Returns [this] with all four generation-progress fields replaced by the given values.
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

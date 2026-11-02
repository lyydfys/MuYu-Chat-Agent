package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import java.util.Locale

/**
 * Pure projection of the run-cost row above the composer.
 *
 * Sources inspected:
 * - [AgentRun.spentBudget]: model attempts, tool invocations, generated tokens and model wall time
 *   are the authoritative counters charged by the runtime. One model attempt is one agent loop
 *   round in V1; `runOrdinal` counts resumed runs, so it is not reused as a round count.
 * - [AgentRun.state]: only a real RUNNING state drives the animated progress ring.
 * - [AgentRun.modelBinding.contextWindowTokens]: only the denominator of a context percentage.
 *   AgentRunSnapshot exposes no prompt-token numerator, so no percentage is claimed.
 * - AgentEvent timeline entries: inspected, but the event contract carries no token/cache usage;
 *   event summaries are display text and are never parsed into numbers.
 *
 * Cache hit is omitted for the same reason: no cache field is projected into AgentRunSnapshot.
 * [AgentRunStatsUiState.contextPercent] and [AgentRunStatsUiState.cacheHitPercent] therefore stay
 * null until a real usage projection exists; the UI omits those segments entirely.
 */
internal fun projectRunStats(run: AgentRun): AgentRunStatsUiState {
    val spent = run.spentBudget
    val generatedTokens = spent.generatedTokens
    val modelWallTimeMillis = spent.modelWallTimeMillis
    val tokensPerSecond = if (generatedTokens > 0 && modelWallTimeMillis > 0L) {
        generatedTokens.toDouble() * 1_000.0 / modelWallTimeMillis.toDouble()
    } else {
        null
    }
    return AgentRunStatsUiState(
        rounds = spent.modelAttempts,
        steps = spent.modelAttempts + spent.toolInvocations,
        tokensPerSecond = tokensPerSecond,
        totalTokens = generatedTokens,
        contextPercent = null,
        cacheHitPercent = null,
        running = run.state == AgentRunState.RUNNING
    )
}

/** Rounds and steps are joint counters; a known side is still rendered when the other is absent. */
internal fun formatRunRoundsAndSteps(rounds: Int?, steps: Int?): String? {
    val parts = buildList {
        rounds?.let { add("$it 轮") }
        steps?.let { add("$it 步") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/** Throughput and total tokens share the database segment; either side may be absent. */
internal fun formatRunTokens(tokensPerSecond: Double?, totalTokens: Int?): String? {
    val parts = buildList {
        tokensPerSecond?.let { add(formatTokensPerSecond(it)) }
        totalTokens?.let { add(formatTokenCount(it)) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 23.0 -> "23 tok/s", 23.44 -> "23.4 tok/s"; the value is always the real budget ratio. */
internal fun formatTokensPerSecond(tokensPerSecond: Double): String {
    val value = tokensPerSecond.coerceAtLeast(0.0)
    val text = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
    return "$text tok/s"
}

/** 14_600 -> "14.6K tok"; thousands/millions only change the unit, never the direction. */
internal fun formatTokenCount(tokens: Int): String {
    val value = tokens.coerceAtLeast(0)
    val compact = when {
        value < 1_000 -> value.toString()
        value < 1_000_000 ->
            String.format(Locale.ROOT, "%.1f", value / 1_000.0).removeSuffix(".0") + "K"

        else ->
            String.format(Locale.ROOT, "%.1f", value / 1_000_000.0).removeSuffix(".0") + "M"
    }
    return "$compact tok"
}

/** Kept separate from cache text so a future usage projection can fill only what it really has. */
internal fun formatRunContextPercent(contextPercent: Int?): String? =
    contextPercent?.let { "$it%" }

internal fun formatRunCacheHitPercent(cacheHitPercent: Int?): String? =
    cacheHitPercent?.let { "缓存命中 $it%" }

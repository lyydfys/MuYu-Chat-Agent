@file:Suppress("unused")

package com.muyuchat.core.engine.contract

import org.json.JSONArray

/**
 * Public facade for API adapters that only depend on :core:inference-contract.
 *
 * Type aliases preserve Kotlin source compatibility with the historical
 * com.muyuchat.core.engine names without creating a second DTO authority.
 */
typealias ChatRequest = com.muyuchat.core.engine.ChatRequest
typealias ChatMessage = com.muyuchat.core.engine.ChatMessage
typealias ChatImageAttachment = com.muyuchat.core.engine.ChatImageAttachment
typealias GenerationParams = com.muyuchat.core.engine.GenerationParams
typealias GenerateEvent = com.muyuchat.core.engine.GenerateEvent
typealias GenerateEventPhase = com.muyuchat.core.engine.GenerateEvent.Phase
typealias GenerateEventPersist = com.muyuchat.core.engine.GenerateEvent.Persist
typealias GenerateEventChunk = com.muyuchat.core.engine.GenerateEvent.Chunk
typealias GenerateEventDone = com.muyuchat.core.engine.GenerateEvent.Done
typealias GenerateEventError = com.muyuchat.core.engine.GenerateEvent.Error
typealias LocalChatExecutionContext = com.muyuchat.core.engine.LocalChatExecutionContext
typealias Role = com.muyuchat.core.engine.Role
typealias ReasoningMode = com.muyuchat.core.engine.ReasoningMode
typealias RuntimeStats = com.muyuchat.core.engine.RuntimeStats
typealias ContextWindowBudget = com.muyuchat.core.engine.ContextWindowBudget

const val SAFE_ASCII_PROMPT_PUNCTUATION: String =
    com.muyuchat.core.engine.SAFE_ASCII_PROMPT_PUNCTUATION

fun compareUtf8Unsigned(left: String, right: String): Int =
    com.muyuchat.core.engine.compareUtf8Unsigned(left, right)

fun isNativeChineseHanCodePoint(codePoint: Int): Boolean =
    com.muyuchat.core.engine.isNativeChineseHanCodePoint(codePoint)

fun isSafeAsciiDiffusionPromptCodePoint(codePoint: Int): Boolean =
    com.muyuchat.core.engine.isSafeAsciiDiffusionPromptCodePoint(codePoint)

fun localContextWindowBudget(requestedContextLength: Int): ContextWindowBudget =
    com.muyuchat.core.engine.localContextWindowBudget(requestedContextLength)

fun JSONArray.toStringList(): List<String> =
    com.muyuchat.core.engine.collectJsonStringList(this)
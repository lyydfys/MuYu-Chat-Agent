package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import com.muyuchat.core.engine.PrefixCacheKey
import java.util.UUID

/**
 * T2B22-T3（b 工位）拆片：normalizeAssistantRecords /
 * restoreLegacyImportedCharacterCardPrompt /
 * sanitizeAssistantParamsJsonPreservingLegacyExecution / LEGACY_EXECUTION_INT_FIELDS 自
 * AssistantStore.kt 逐字节平移；零改写（全部已为顶层 internal/private）。
 */

/**
 * T2B22-T3（b 工位）拆片：legacy 规范化组自 AssistantStore.kt 逐字节平移；零改写。
 */

internal fun normalizeAssistantRecords(
    records: List<AssistantRecord>,
    fallback: AssistantRecord
): List<AssistantRecord> {
    val withDefault = if (records.any { it.id == AssistantRecord.DEFAULT_ID }) {
        records
    } else {
        listOf(fallback) + records
    }
    val defaults = assistantGenerationParamsFromJson(
        fallback.paramsJson,
        GenerationParams(),
        fallback.systemPrompt
    )
    return withDefault
        .distinctBy { it.id }
        .map { assistant ->
            val restored = restoreLegacyImportedCharacterCardPrompt(assistant, defaults)
            restored.copy(
                paramsJson = sanitizeAssistantParamsJsonPreservingLegacyExecution(
                    restored.paramsJson,
                    defaults,
                    restored.systemPrompt
                )
            )
        }
        .ifEmpty { listOf(fallback) }
}

/**
 * A narrow lazy migration for old external character cards that were persisted with the exact
 * MCA boilerplate instead of the persona prompt derived from the retained source card.
 *
 * The raw card is kept byte-for-byte. Only an exact boilerplate prompt is eligible, and explicit
 * MCA assistant-card exports are excluded because their boilerplate is intentional. An explicit
 * USER_AUTHORED record always stays untouched, even when its prompt equals the default exactly.
 * Records without metadata remain LEGACY_UNKNOWN; this migration never guesses earlier edits.
 * A prompt with any authored suffix, or a record without a retained source card, stays untouched.
 */
internal fun restoreLegacyImportedCharacterCardPrompt(
    assistant: AssistantRecord,
    defaults: GenerationParams
): AssistantRecord {
    if (assistant.systemPromptProvenance == AssistantPromptProvenance.USER_AUTHORED ||
        assistant.id == AssistantRecord.DEFAULT_ID || !isMcaDefaultAssistantPrompt(assistant.systemPrompt)) {
        return assistant
    }
    val rawCard = assistant.characterCardJson?.takeIf(String::isNotBlank) ?: return assistant
    val card = (CharacterCardCodec.parseJson(rawCard) as? CharacterCardParseResult.Success)?.card
        ?: return assistant
    if (!isMcaDefaultCharacterCardPrompt(card)) return assistant
    val restored = card.toAssistantRecord(AssistantRecord.default(defaults.systemPrompt, defaults))
    val restoredPrompt = restored.systemPrompt
        .take(AssistantRecord.MAX_SYSTEM_PROMPT_CHARS)
        .takeIf(String::isNotBlank)
        ?: return assistant
    if (isMcaDefaultAssistantPrompt(restoredPrompt)) return assistant
    return assistant.copy(
        systemPrompt = restoredPrompt,
        paramsJson = sanitizeAssistantParamsJsonPreservingLegacyExecution(
            assistant.paramsJson,
            defaults,
            restoredPrompt
        )
    )
}

/**
 * Migrates an old assistant card without losing the two execution values that historically lived
 * in its params JSON. They are retained only for lossless export/import compatibility; all
 * runtime application paths use [assistantGenerationParamsFromJson], which deliberately copies
 * generation fields onto the caller's model execution defaults and therefore ignores these keys.
 */
internal fun sanitizeAssistantParamsJsonPreservingLegacyExecution(
    rawJson: String,
    defaults: GenerationParams,
    systemPrompt: String
): String {
    val sanitized = runCatching {
        JSONObject(sanitizeAssistantParamsJson(rawJson, defaults, systemPrompt))
    }.getOrElse {
        return sanitizeAssistantParamsJson(rawJson, defaults, systemPrompt)
    }
    val raw = runCatching { JSONObject(rawJson) }.getOrNull() ?: return sanitized.toString()
    if (raw.has(ASSISTANT_PROMPT_PROVENANCE_KEY)) {
        sanitized.put(
            ASSISTANT_PROMPT_PROVENANCE_KEY,
            assistantPromptProvenanceFromParamsJson(rawJson).name
        )
    }
    LEGACY_EXECUTION_INT_FIELDS.forEach { (canonical, aliases) ->
        val value = aliases.firstNotNullOfOrNull { key ->
            if (raw.has(key) && !raw.isNull(key)) {
                raw.optInt(key, Int.MIN_VALUE).takeIf { it > 0 }
            } else {
                null
            }
        }
        value?.let { sanitized.put(canonical, it) }
    }
    return sanitized.toString()
}

private val LEGACY_EXECUTION_INT_FIELDS: List<Pair<String, List<String>>> = listOf(
    "n_ctx" to listOf("n_ctx", "nCtx"),
    "n_threads" to listOf("n_threads", "nThreads")
)

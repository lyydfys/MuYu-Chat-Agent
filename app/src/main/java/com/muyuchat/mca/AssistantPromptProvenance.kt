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
 * T2B22-T3（b 工位）拆片：AssistantPromptProvenance 枚举 / 常量 / 三个顶层扩展自
 * AssistantStore.kt 逐字节平移；assistantPromptProvenanceFromParamsJson 由 private 提升为
 * internal（壳内 systemPromptProvenance getter 跨文件引用，widen 登记）。
 */

/**
 * T2B22-T3（b 工位）拆片：prompt provenance 组自 AssistantStore.kt 逐字节平移（仅登记 visibility）。
 */
/** Origin of the persisted prompt; missing historical metadata must never imply an authored edit. */
enum class AssistantPromptProvenance {
    IMPORTED_DERIVED,
    USER_AUTHORED,
    LEGACY_UNKNOWN;

    companion object {
        internal fun fromWireValue(value: String?): AssistantPromptProvenance =
            values().firstOrNull { it.name == value?.trim() } ?: LEGACY_UNKNOWN
    }
}

internal const val ASSISTANT_PROMPT_PROVENANCE_KEY = "assistant_prompt_provenance"

/** Inert assistant metadata stored in the existing params JSON so Room needs no schema migration. */
internal fun String.withAssistantPromptProvenance(provenance: AssistantPromptProvenance): String =
    JSONObject(this).put(ASSISTANT_PROMPT_PROVENANCE_KEY, provenance.name).toString()

internal fun assistantPromptProvenanceFromParamsJson(rawJson: String): AssistantPromptProvenance =
    runCatching {
        AssistantPromptProvenance.fromWireValue(JSONObject(rawJson).optString(ASSISTANT_PROMPT_PROVENANCE_KEY))
    }.getOrDefault(AssistantPromptProvenance.LEGACY_UNKNOWN)

internal fun AssistantRecord.withSystemPromptProvenance(provenance: AssistantPromptProvenance): AssistantRecord =
    copy(paramsJson = paramsJson.withAssistantPromptProvenance(provenance))

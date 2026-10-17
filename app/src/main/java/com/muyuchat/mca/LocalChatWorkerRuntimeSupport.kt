package com.muyuchat.mca

import com.muyuchat.core.engine.LocalChatRunner
import org.json.JSONObject

internal fun LocalChatWorkerService.liteRtLmNpuRequested(paramsJson: String): Boolean {
        val root = runCatching { JSONObject(paramsJson.ifBlank { "{}" }) }.getOrNull() ?: return false
        val advanced = root.opt("advanced_json")?.let { raw ->
            when (raw) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            }
        }
        fun value(vararg names: String): Any? = names.asSequence()
            .mapNotNull { name ->
                root.opt(name).takeIf { it != JSONObject.NULL }
                    ?: advanced?.opt(name)?.takeIf { it != JSONObject.NULL }
            }
            .firstOrNull()
        val raw = value("backend", "backend_type", "backendType")
        return when (raw?.toString()?.trim()?.lowercase()?.replace('-', '_')) {
            "npu", "qnn", "qualcomm" -> true
            else -> false
        }
    }

internal data class RuntimeStatsReadSnapshot(
    val epoch: Long,
    val runner: LocalChatRunner?,
    val fallback: String
)

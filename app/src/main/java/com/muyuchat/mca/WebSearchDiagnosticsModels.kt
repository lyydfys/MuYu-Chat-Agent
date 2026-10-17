package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatSourceReference
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class WebSearchCitationAudit(
    val sourceCount: Int,
    val citedIndices: List<Int> = emptyList(),
    val invalidIndices: List<Int> = emptyList(),
    val appendedFallbackCitation: Boolean = false,
    val repaired: Boolean = false
) {
    val closedLoopChecks: List<String>
        get() = buildList {
            if (sourceCount <= 0) {
                add("引用审计：无来源卡片，跳过")
                return@buildList
            }
            val validCount = citedIndices.count { it in 1..sourceCount }
            add("引用审计：${validCount} 个有效引用 / ${sourceCount} 个来源")
            if (invalidIndices.isNotEmpty()) {
                add("引用审计：已移除越界引用 ${invalidIndices.sorted().joinToString(prefix = "[", postfix = "]")}")
            }
            if (appendedFallbackCitation) {
                add("引用审计：已补充参考来源 [1]")
            }
        }

    val warnings: List<String>
        get() = buildList {
            if (invalidIndices.isNotEmpty()) {
                add("回答引用了不存在的来源：${invalidIndices.sorted().joinToString(", ") { "[$it]" }}")
            }
            if (appendedFallbackCitation) {
                add("回答缺少来源编号，已补充参考来源 [1]")
            }
        }

    val statusMessage: String
        get() = when {
            sourceCount <= 0 -> "联网引用审计：无来源"
            repaired -> "联网引用审计：已修正"
            else -> "联网引用审计：通过"
        }
}

data class WebSearchAnswerGuardResult(
    val message: ChatMessage,
    val citationAudit: WebSearchCitationAudit? = null
)

data class WebSearchDiagnosticRecord(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val providerLabel: String,
    val triggerModeLabel: String,
    val query: String,
    val searchedQueries: List<String> = emptyList(),
    val directUrls: List<String> = emptyList(),
    val sourceCount: Int = 0,
    val elapsedMs: Long = 0L,
    val success: Boolean,
    val message: String,
    val topSources: List<ChatSourceReference> = emptyList(),
    val healthScore: Int = 0,
    val healthLabel: String = "",
    val healthReasons: List<String> = emptyList(),
    val qualityScore: Int = 0,
    val qualityLabel: String = "",
    val qualityReasons: List<String> = emptyList(),
    val sourceTrustSummary: List<String> = emptyList(),
    val researchConfidenceScore: Int = 0,
    val researchConfidenceLabel: String = "",
    val researchEvidenceGroups: List<String> = emptyList(),
    val researchConflictWarnings: List<String> = emptyList(),
    val researchSynthesisGuidance: List<String> = emptyList(),
    val triggerReasons: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val cacheStatus: String = "",
    val closedLoopChecks: List<String> = emptyList()
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("createdAt", createdAt)
            .put("providerLabel", providerLabel)
            .put("triggerModeLabel", triggerModeLabel)
            .put("query", query)
            .put("searchedQueries", searchedQueries.toJsonArray())
            .put("directUrls", directUrls.toJsonArray())
            .put("sourceCount", sourceCount)
            .put("elapsedMs", elapsedMs)
            .put("success", success)
            .put("message", message)
            .put("healthScore", healthScore)
            .put("healthLabel", healthLabel)
            .put("healthReasons", healthReasons.toJsonArray())
            .put("qualityScore", qualityScore)
            .put("qualityLabel", qualityLabel)
            .put("qualityReasons", qualityReasons.toJsonArray())
            .put("sourceTrustSummary", sourceTrustSummary.toJsonArray())
            .put("researchConfidenceScore", researchConfidenceScore)
            .put("researchConfidenceLabel", researchConfidenceLabel)
            .put("researchEvidenceGroups", researchEvidenceGroups.toJsonArray())
            .put("researchConflictWarnings", researchConflictWarnings.toJsonArray())
            .put("researchSynthesisGuidance", researchSynthesisGuidance.toJsonArray())
            .put("triggerReasons", triggerReasons.toJsonArray())
            .put("warnings", warnings.toJsonArray())
            .put("cacheStatus", cacheStatus)
            .put("closedLoopChecks", closedLoopChecks.toJsonArray())
            .put(
                "topSources",
                JSONArray().also { array ->
                    topSources.take(3).forEach { source ->
                        array.put(
                            JSONObject()
                                .put("title", source.title)
                                .put("url", source.url)
                                .put("snippet", source.snippet)
                                .put("provider", source.provider)
                                .put("hostLabel", source.hostLabel)
                                .put("trustLabel", source.trustLabel)
                                .put("trustReason", source.trustReason)
                        )
                    }
                }
            )

    companion object {
        fun fromJson(json: JSONObject): WebSearchDiagnosticRecord {
            val health = json.toWebSearchDiagnosticHealthReport()
            return WebSearchDiagnosticRecord(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                providerLabel = json.optString("providerLabel"),
                triggerModeLabel = json.optString("triggerModeLabel"),
                query = json.optString("query"),
                searchedQueries = json.optJSONArray("searchedQueries")?.toStringList().orEmpty(),
                directUrls = json.optJSONArray("directUrls")?.toStringList().orEmpty(),
                sourceCount = json.optInt("sourceCount", 0),
                elapsedMs = json.optLong("elapsedMs", 0L),
                success = json.optBoolean("success", false),
                message = json.optString("message"),
                topSources = json.optJSONArray("topSources").toWebSearchSourceReferences(),
                healthScore = health.score,
                healthLabel = health.label,
                healthReasons = health.reasons,
                qualityScore = json.optInt("qualityScore", 0),
                qualityLabel = json.optString("qualityLabel"),
                qualityReasons = json.optJSONArray("qualityReasons")?.toStringList().orEmpty(),
                sourceTrustSummary = json.optJSONArray("sourceTrustSummary")?.toStringList().orEmpty(),
                researchConfidenceScore = json.optInt("researchConfidenceScore", 0),
                researchConfidenceLabel = json.optString("researchConfidenceLabel"),
                researchEvidenceGroups = json.optJSONArray("researchEvidenceGroups")?.toStringList().orEmpty(),
                researchConflictWarnings = json.optJSONArray("researchConflictWarnings")?.toStringList().orEmpty(),
                researchSynthesisGuidance = json.optJSONArray("researchSynthesisGuidance")?.toStringList().orEmpty(),
                triggerReasons = json.optJSONArray("triggerReasons")?.toStringList().orEmpty(),
                warnings = json.optJSONArray("warnings")?.toStringList().orEmpty(),
                cacheStatus = json.optString("cacheStatus"),
                closedLoopChecks = json.optJSONArray("closedLoopChecks")?.toStringList().orEmpty()
            )
        }
    }
}

data class WebSearchTurnOutcome(
    val plan: WebSearchPlan,
    val requested: Boolean,
    val searched: Boolean,
    val success: Boolean,
    val promptContext: String = "",
    val sourceReferences: List<ChatSourceReference> = emptyList(),
    val diagnostic: WebSearchDiagnosticRecord? = null,
    val webSearchStatusMessage: String? = null,
    val statusMessage: String? = null
)

data class WebSearchPreflightReport(
    val ok: Boolean,
    val checks: List<String>,
    val message: String
)

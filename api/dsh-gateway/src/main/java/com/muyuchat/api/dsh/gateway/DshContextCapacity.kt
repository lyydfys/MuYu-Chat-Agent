package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshGatewayModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * ECO-5: why an advertised model row cannot take part in official compaction.
 *
 * The official `dsh-compaction-basic` backend refuses to compact a model whose adapter model row
 * declares no context window ("compaction-basic: no context capacity for …; configure
 * contextWindow on that adapter model"). MCA cannot invent a window, so the gateway reports the
 * gap instead of emitting a silently unusable row.
 */
enum class DshContextCapacityGap {
    /** The row carries no positive `contextWindow`, so compaction fails closed for it. */
    NO_CONTEXT_WINDOW
}

/**
 * Typed compaction-capacity fact of one advertised `/v1/models` row.
 *
 * It is a **non-breaking sidecar**: the official model-discovery parser reads only `id`, `name`,
 * `context_window` and `max_output_tokens`, so adding this object to an entry (and the warning list
 * to the listing root) keeps the official wire contract intact while making the unusable row
 * visible to MCA diagnostics.
 */
data class DshContextCapacity(
    val modelId: String,
    val contextWindow: Int?,
    val maxOutputTokens: Int?
) {
    /** True only when the row declares a positive window, which is what compaction needs. */
    val compactionReady: Boolean get() = (contextWindow ?: 0) > 0

    /** The typed gap when compaction cannot run, otherwise null. */
    val gap: DshContextCapacityGap?
        get() = if (compactionReady) null else DshContextCapacityGap.NO_CONTEXT_WINDOW

    /**
     * Value-free warning line (model id only, never a base URL/key). Null when the row declares a
     * window, so a healthy catalog produces no warning noise.
     */
    val warning: String?
        get() = gap?.let {
            "model ${modelId}: no contextWindow; official compaction fails closed for this row"
        }
}

/**
 * Pure probe over the catalog the gateway advertises.
 *
 * It never changes a model row: the gateway's job is to be honest about what the backend declares,
 * so a missing window stays missing and is reported. The app-side seeder applies the same rule to
 * the overlay row it writes.
 */
object DshContextCapacityProbe {

    fun of(model: DshGatewayModel): DshContextCapacity = DshContextCapacity(
        modelId = model.id,
        contextWindow = model.contextWindow?.takeIf { it > 0 },
        maxOutputTokens = model.maxOutputTokens?.takeIf { it > 0 }
    )

    /** Every catalog row, in catalog order (diagnostics), never filtered. */
    fun capacities(models: List<DshGatewayModel>): List<DshContextCapacity> = models.map(::of)

    /** Only the rows that cannot be compacted. */
    fun gaps(models: List<DshGatewayModel>): List<DshContextCapacity> =
        capacities(models).filter { it.gap != null }

    /** Value-free warning lines for the rows that cannot be compacted. */
    fun warnings(models: List<DshGatewayModel>): List<String> = gaps(models).mapNotNull { it.warning }

    /** One bounded log-safe line for a host logger; null when every row is compaction-capable. */
    fun summary(models: List<DshGatewayModel>): String? {
        val gaps = gaps(models)
        if (gaps.isEmpty()) return null
        val named = gaps.take(MAX_SUMMARY_IDS).joinToString(",") { it.modelId }
        val more = if (gaps.size > MAX_SUMMARY_IDS) " (+${gaps.size - MAX_SUMMARY_IDS} more)" else ""
        return "${gaps.size} model row(s) without contextWindow: $named$more; " +
            "official compaction fails closed for them"
    }

    /** Per-entry sidecar for `/v1/models`; official fields are never touched. */
    fun toJson(capacity: DshContextCapacity): JSONObject = JSONObject()
        .put("context_window", capacity.contextWindow ?: JSONObject.NULL)
        .put("max_output_tokens", capacity.maxOutputTokens ?: JSONObject.NULL)
        .put("compaction_ready", capacity.compactionReady)
        .put("gap", capacity.gap?.name?.lowercase() ?: JSONObject.NULL)

    /** Root-level warning array for `/v1/models`; empty when the catalog is fully usable. */
    fun warningsJson(models: List<DshGatewayModel>): JSONArray = JSONArray(warnings(models))

    private const val MAX_SUMMARY_IDS = 4
}

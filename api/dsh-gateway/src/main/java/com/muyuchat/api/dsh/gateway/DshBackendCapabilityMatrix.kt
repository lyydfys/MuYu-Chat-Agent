package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshGatewayModel
import org.json.JSONArray
import org.json.JSONObject

/** Honest support level for one capability. Never claim a capability the backend cannot serve. */
enum class DshCapabilitySupport {
    SUPPORTED,
    UNSUPPORTED,

    /** The provider did not declare it; the gateway must not claim it either way. */
    NOT_DECLARED
}

/** Tool-choice modes as the MCA wire can actually honour per backend. */
enum class DshToolChoiceKind {
    NONE,
    AUTO,
    REQUIRED,

    /**
     * The pinned gateway validates a named `tool_choice`, then maps it to [REQUIRED] because the
     * contract carries no named mode. It is therefore *not* advertised as natively supported.
     */
    NAMED
}

/**
 * One backend/model/transport capability row.
 *
 * The row is derived from the served model metadata, so `/v1/models` and routing see exactly the
 * same honesty: prompt-compatibility tool calls are labelled, native tool calling must come with no
 * prompt label, and an undeclared vision/reasoning capability stays [DshCapabilitySupport.NOT_DECLARED]
 * instead of being assumed.
 */
data class DshBackendCapabilityRow(
    val modelId: String,
    val backend: DshModelBackend,
    val nativeToolCalling: Boolean,
    val promptedToolCalling: Boolean,
    val toolProtocolLabel: String?,
    val toolChoice: Set<DshToolChoiceKind>,
    val vision: DshCapabilitySupport,
    val reasoning: DshCapabilitySupport,
    val parallelToolCalls: Boolean,
    val contextWindow: Int?,
    val maxOutputTokens: Int?
)

object DshBackendCapabilityMatrix {

    /**
     * @param vision declared vision support from the app provider source; null means "not declared"
     *   and is intentionally reported as [DshCapabilitySupport.NOT_DECLARED].
     */
    fun describe(
        model: DshGatewayModel,
        vision: Boolean? = null
    ): DshBackendCapabilityRow {
        val backend = DshModelBackend.classify(model.id)
        val native = model.supportsTools && model.nativeToolCalling
        val prompted = model.supportsTools && !model.nativeToolCalling
        val choices = when {
            !model.supportsTools -> emptySet()
            else -> linkedSetOf(
                DshToolChoiceKind.NONE,
                DshToolChoiceKind.AUTO,
                DshToolChoiceKind.REQUIRED
            )
        }
        return DshBackendCapabilityRow(
            modelId = model.id,
            backend = backend,
            nativeToolCalling = native,
            promptedToolCalling = prompted,
            toolProtocolLabel = model.toolProtocolLabel?.takeIf { it.isNotBlank() },
            toolChoice = choices,
            vision = when (vision) {
                true -> DshCapabilitySupport.SUPPORTED
                false -> DshCapabilitySupport.UNSUPPORTED
                null -> DshCapabilitySupport.NOT_DECLARED
            },
            reasoning = DshCapabilitySupport.NOT_DECLARED,
            // The pinned wire contract fixes parallel=false for every backend.
            parallelToolCalls = false,
            contextWindow = model.contextWindow?.takeIf { it > 0 },
            maxOutputTokens = model.maxOutputTokens?.takeIf { it > 0 }
        )
    }

    /** Honesty violations of one row; an empty list is the only acceptable production state. */
    fun violations(row: DshBackendCapabilityRow): List<String> = buildList {
        if (row.nativeToolCalling && row.toolProtocolLabel != null) {
            add("a native tool-calling model must not carry a prompt-compat label")
        }
        if (row.promptedToolCalling && row.toolProtocolLabel == null) {
            add("a prompted tool-calling model must carry its compatibility label")
        }
        if (!row.nativeToolCalling && !row.promptedToolCalling && row.toolProtocolLabel != null) {
            add("a model without tools must not carry a tool protocol label")
        }
        if (row.parallelToolCalls) {
            add("parallel tool calls are not part of the pinned wire contract")
        }
    }

    /** Serialized as an extra, non-breaking field on each `/v1/models` entry. */
    fun toJson(row: DshBackendCapabilityRow): JSONObject = JSONObject()
        .put("backend", row.backend.name.lowercase())
        .put("native_tools", row.nativeToolCalling)
        .put("prompted_tools", row.promptedToolCalling)
        .put("tool_choice", JSONArray(row.toolChoice.map { it.name.lowercase() }.sorted()))
        .put("vision", row.vision.name.lowercase())
        .put("reasoning", row.reasoning.name.lowercase())
        .put("parallel_tool_calls", row.parallelToolCalls)
        .put("context_window", row.contextWindow ?: JSONObject.NULL)
        .put("max_output_tokens", row.maxOutputTokens ?: JSONObject.NULL)

    /**
     * Validates every row of a catalog and reports violations with their model id. The gateway uses
     * this before advertising the catalog so a dishonest label is visible diagnostics, not silent.
     */
    fun violationsOf(models: List<DshGatewayModel>): List<String> = models.flatMap { model ->
        violations(describe(model)).map { "$it (model ${model.id})" }
    }
}

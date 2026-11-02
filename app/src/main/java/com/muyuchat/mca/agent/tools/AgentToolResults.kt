package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the single structured result document used by every built-in tool (architecture 9.2).
 *
 * The document carries the invocation identity, `ok`/`status`, summary, typed `data`, artifact
 * references, truncation flags, error code and side-effect truth. `ok` is true only for
 * [AgentToolResultStatus.SUCCEEDED]; every other status is a truthful failure/non-execution.
 *
 * Size rules (architecture 6.6): a result larger than 16 KiB is marked `truncated` and points at
 * the persisted full result through `fullResultRef`; a result that would exceed the 64 KiB
 * persistence budget is shrunk by the tool-specific [shrink] callback before it is returned. When
 * the full document itself cannot be retained, `fullResultRef` stays null and
 * `fullResultRetained=false` says so instead of claiming a complete observation.
 *
 * This object is also the mapping seam for a broker-generated DENIED/CANCELLED/UNKNOWN result; the
 * tool layer never fabricates those statuses through a normal execution.
 */
object AgentToolResults {
    const val TOOL_RESULT_SCHEMA_VERSION: Int = AgentContract.SCHEMA_VERSION
    const val MODEL_RESULT_MAX_BYTES: Int = AgentContract.MAX_TOOL_RESULT_TO_MODEL_BYTES
    const val PERSISTED_RESULT_MAX_BYTES: Int = AgentContract.MAX_TOOL_RESULT_PERSIST_BYTES
    const val FULL_RESULT_REF_PREFIX: String = "tool-result:"

    private val ERROR_CODE = Regex("^[A-Z][A-Z0-9_]{1,63}$")

    fun success(
        command: FrozenToolCommand,
        descriptor: AgentToolDescriptor,
        summary: String,
        data: JSONObject,
        artifactIds: List<String> = emptyList(),
        truncated: Boolean = false,
        shrink: ((JSONObject) -> Boolean)? = null
    ): AgentToolResult = build(
        command = command,
        descriptor = descriptor,
        status = AgentToolResultStatus.SUCCEEDED,
        summary = summary,
        data = data,
        artifactIds = artifactIds,
        errorCode = null,
        possibleSideEffects = false,
        initiallyTruncated = truncated,
        shrink = shrink
    )

    fun failure(
        command: FrozenToolCommand,
        descriptor: AgentToolDescriptor,
        errorCode: String,
        summary: String,
        possibleSideEffects: Boolean = false
    ): AgentToolResult = build(
        command = command,
        descriptor = descriptor,
        status = AgentToolResultStatus.FAILED,
        summary = summary,
        data = JSONObject(),
        artifactIds = emptyList(),
        errorCode = stableErrorCode(errorCode),
        possibleSideEffects = possibleSideEffects,
        initiallyTruncated = false,
        shrink = null
    )

    fun invalidArguments(
        command: FrozenToolCommand,
        descriptor: AgentToolDescriptor,
        message: String
    ): AgentToolResult = failure(command, descriptor, AgentErrorCodes.TOOL_INPUT_INVALID, message)

    fun portFailure(
        command: FrozenToolCommand,
        descriptor: AgentToolDescriptor,
        failure: AgentFileResult.Failure
    ): AgentToolResult = failure(
        command = command,
        descriptor = descriptor,
        errorCode = toolErrorCode(failure.errorCode),
        summary = failure.safeMessage.ifBlank { "Tool execution failed." },
        possibleSideEffects = failure.possibleSideEffects
    )

    fun denied(
        command: FrozenToolCommand,
        descriptor: AgentToolDescriptor,
        errorCode: String,
        summary: String
    ): AgentToolResult = build(
        command, descriptor, AgentToolResultStatus.DENIED, summary, JSONObject(), emptyList(),
        stableErrorCode(errorCode), possibleSideEffects = false, initiallyTruncated = false, shrink = null
    )

    fun notExecuted(
        command: FrozenToolCommand,
        descriptor: AgentToolDescriptor,
        errorCode: String,
        summary: String
    ): AgentToolResult = build(
        command, descriptor, AgentToolResultStatus.NOT_EXECUTED, summary, JSONObject(), emptyList(),
        stableErrorCode(errorCode), possibleSideEffects = false, initiallyTruncated = false, shrink = null
    )

    fun cancelled(
        command: FrozenToolCommand,
        descriptor: AgentToolDescriptor,
        summary: String = "The tool invocation was cancelled before it completed."
    ): AgentToolResult = build(
        command, descriptor, AgentToolResultStatus.CANCELLED, summary, JSONObject(), emptyList(),
        errorCode = null, possibleSideEffects = false, initiallyTruncated = false, shrink = null
    )

    fun unknown(
        command: FrozenToolCommand,
        descriptor: AgentToolDescriptor,
        summary: String = "The tool outcome is unknown; do not assume it ran."
    ): AgentToolResult = build(
        command, descriptor, AgentToolResultStatus.UNKNOWN, summary, JSONObject(), emptyList(),
        AgentErrorCodes.TOOL_RESULT_UNKNOWN, possibleSideEffects = true, initiallyTruncated = false, shrink = null
    )

    private fun build(
        command: FrozenToolCommand,
        descriptor: AgentToolDescriptor,
        status: AgentToolResultStatus,
        summary: String,
        data: JSONObject,
        artifactIds: List<String>,
        errorCode: String?,
        possibleSideEffects: Boolean,
        initiallyTruncated: Boolean,
        shrink: ((JSONObject) -> Boolean)?
    ): AgentToolResult {
        require(summary.isNotBlank()) { "tool result summary must not be blank" }
        val uniqueArtifacts = artifactIds.distinct()
        require(uniqueArtifacts.size == artifactIds.size) { "tool result artifact ids must not repeat" }
        require(uniqueArtifacts.size <= AgentContract.MAX_FINAL_ARTIFACTS) {
            "tool result references too many artifacts"
        }

        val envelope = JSONObject()
            .put("schemaVersion", TOOL_RESULT_SCHEMA_VERSION)
            .put("invocationId", command.invocationId)
            .put("toolKey", descriptor.toolKey)
            .put("toolVersion", descriptor.version)
            .put("ok", status == AgentToolResultStatus.SUCCEEDED)
            .put("status", status.name)
            .put("summary", summary)
            .put("data", data)
            .put(
                "artifactRefs",
                JSONArray().also { array -> uniqueArtifacts.forEach { array.put(it) } }
            )
            .put("truncated", initiallyTruncated)
            .put("errorCode", errorCode ?: JSONObject.NULL)
            .put("possibleSideEffects", possibleSideEffects)
            .put("fullResultRef", JSONObject.NULL)

        var truncated = initiallyTruncated
        var fullResultRef: String? = null
        var text = AgentToolJson.canonical(envelope)

        if (AgentToolJson.utf8Size(text) > PERSISTED_RESULT_MAX_BYTES) {
            truncated = true
            envelope.put("truncated", true)
            envelope.put("truncationReason", "PERSISTED_CAP")
            envelope.put("fullResultRetained", false)
            var iterations = 0
            while (shrink != null && iterations < 64 &&
                AgentToolJson.utf8Size(text) > PERSISTED_RESULT_MAX_BYTES
            ) {
                if (!shrink.invoke(data)) break
                envelope.put("data", data)
                text = AgentToolJson.canonical(envelope)
                iterations++
            }
            if (AgentToolJson.utf8Size(text) > PERSISTED_RESULT_MAX_BYTES) {
                envelope.put(
                    "data",
                    JSONObject().put(
                        "note",
                        "Tool result exceeded the 64 KiB persistence budget; reduce the request scope."
                    )
                )
                text = AgentToolJson.canonical(envelope)
            }
            require(AgentToolJson.utf8Size(text) <= PERSISTED_RESULT_MAX_BYTES) {
                "tool result still exceeds the persistence budget after shrinking"
            }
        } else if (AgentToolJson.utf8Size(text) > MODEL_RESULT_MAX_BYTES) {
            truncated = true
            fullResultRef = "$FULL_RESULT_REF_PREFIX${command.invocationId}"
            envelope.put("truncated", true)
            envelope.put("truncationReason", "MODEL_CAP")
            envelope.put("fullResultRetained", true)
            envelope.put("fullResultRef", fullResultRef)
            text = AgentToolJson.canonical(envelope)
            require(AgentToolJson.utf8Size(text) <= PERSISTED_RESULT_MAX_BYTES) {
                "truncated tool result exceeds the persistence budget"
            }
        }

        return AgentToolResult(
            status = status,
            summary = summary,
            dataJson = text,
            artifactIds = uniqueArtifacts,
            truncated = truncated,
            errorCode = errorCode,
            possibleSideEffects = possibleSideEffects,
            fullResultRef = fullResultRef
        )
    }

    private fun stableErrorCode(raw: String): String =
        if (ERROR_CODE.matches(raw)) raw else AgentErrorCodes.TOOL_FAILED

    /**
     * The workspace store reports a stale create/overwrite precondition as
     * STORAGE_PRECONDITION_CHANGED; the model-facing tool vocabulary (architecture 8.4 / 9.1)
     * names it PRECONDITION_CHANGED. Keep the translation here so both layers stay stable.
     */
    internal fun toolErrorCode(raw: String): String = when (raw) {
        "STORAGE_PRECONDITION_CHANGED" -> "PRECONDITION_CHANGED"
        else -> stableErrorCode(raw)
    }
}

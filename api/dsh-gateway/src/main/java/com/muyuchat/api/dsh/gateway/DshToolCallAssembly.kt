package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshModelEvent
/** A normalized delta that survived backend assembly and is ready for the wire. */
internal sealed interface DshEmittedDelta {
    data class Text(val text: String) : DshEmittedDelta
    data class Reasoning(val text: String) : DshEmittedDelta
    data class ToolCall(
        val index: Int,
        val toolCallId: String?,
        val name: String?,
        val argumentsFragment: String?
    ) : DshEmittedDelta
}

/** Normalized terminal state of one call. Exactly one of these ends every gateway call. */
internal sealed interface DshStreamOutcome {
    data class Completed(
        val finishReason: DshFinishReason,
        val promptTokens: Int?,
        val completionTokens: Int?,
        val toolCalls: List<DshAssembledToolCall>
    ) : DshStreamOutcome

    data class Failed(
        val code: String,
        val message: String,
        val retryable: Boolean
    ) : DshStreamOutcome

    data class Cancelled(val code: String) : DshStreamOutcome
}

internal class DshToolCallProtocolException(
    val code: String,
    message: String
) : IllegalArgumentException(message)

internal class DshResponseTooLargeException(message: String) : IllegalArgumentException(message)

/**
 * Assembles normalized tool-call deltas into complete OpenAI tool calls.
 *
 * The first fragment for an index is not emitted until its function name is known, so the client
 * always receives a usable `id`/`name` on the first tool delta. Argument fragments after that are
 * forwarded verbatim with the stable index.
 *
 * C06 invariants enforced here:
 * - an index keeps one function name and one id for the whole call; any later non-null mutation is
 *   a protocol failure (`invalid_tool_call`), never a silent overwrite;
 * - the same id may not be used by two indexes;
 * - arguments must arrive as explicit fragments forming one complete strict JSON object within
 *   [maxArgumentChars]; the gateway never synthesizes `{}` for a tool call whose arguments were
 *   not fully observed;
 * - a terminal is reported only through [terminate], and any producer-declared reason is preserved
 *   (no inference from token counts or content length).
 */
internal class DshToolCallAssembler(
    private val maxCalls: Int,
    private val callId: String,
    private val maxArgumentChars: Int
) {
    private class Slot(val index: Int) {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
        var flushed = false
    }

    private val slots = LinkedHashMap<Int, Slot>()
    private val ordered = ArrayList<Slot>()
    private val usedIds = HashMap<String, Int>()

    fun onDelta(event: DshModelEvent.ToolCallDelta): List<DshEmittedDelta.ToolCall> {
        if (event.index < 0) {
            throw DshToolCallProtocolException(
                "invalid_tool_call",
                "The model backend emitted a negative tool call index."
            )
        }
        val slot = slots.getOrPut(event.index) {
            if (slots.size >= maxCalls) {
                throw DshToolCallProtocolException(
                    "invalid_tool_call",
                    "The model backend emitted more than $maxCalls tool calls."
                )
            }
            Slot(event.index).also { ordered += it }
        }
        event.id?.takeIf { it.isNotBlank() }?.let { presented -> adoptId(slot, presented) }
        event.name?.takeIf { it.isNotBlank() }?.let { presented ->
            when {
                slot.name == null -> slot.name = presented
                slot.name != presented -> throw DshToolCallProtocolException(
                    "invalid_tool_call",
                    "The model backend changed the function name for tool call index ${event.index}."
                )
            }
        }
        val fragment = event.argumentsFragment
        if (!slot.flushed && slot.name != null) {
            slot.flushed = true
            fragment?.let { appendArguments(slot, it) }
            val id = slot.id ?: mintId(event.index).also { minted ->
                if (usedIds.putIfAbsent(minted, event.index) != null) {
                    throw DshToolCallProtocolException(
                        "invalid_tool_call",
                        "The model backend produced a duplicate tool call id."
                    )
                }
                slot.id = minted
            }
            return listOf(
                DshEmittedDelta.ToolCall(
                    index = event.index,
                    toolCallId = id,
                    name = slot.name,
                    argumentsFragment = slot.arguments.toString().ifEmpty { null }
                )
            )
        }
        if (!slot.flushed) {
            fragment?.let { appendArguments(slot, it) }
            return emptyList()
        }
        if (fragment.isNullOrEmpty()) return emptyList()
        appendArguments(slot, fragment)
        return listOf(
            DshEmittedDelta.ToolCall(
                index = event.index,
                toolCallId = null,
                name = null,
                argumentsFragment = fragment
            )
        )
    }

    /**
     * Map one producer terminal to the gateway outcome. The finish reason is copied exactly; only
     * structural tool-call violations (missing name, no observed argument fragments, incomplete
     * JSON, duplicate ids) on a *successful* terminal turn a `Completed` into an error outcome.
     * A producer-declared cancellation or error stays that exact terminal cause even when a held
     * tool envelope is incomplete: neither outcome carries executable tool calls.
     */
    fun terminate(event: DshModelEvent.Completed): DshStreamOutcome {
        when (event.finishReason) {
            DshFinishReason.CANCELLED -> return DshStreamOutcome.Cancelled("backend_cancelled")
            DshFinishReason.ERROR -> return DshStreamOutcome.Failed(
                code = "backend_error_terminal",
                message = "The model backend reported an error terminal state.",
                retryable = false
            )
            DshFinishReason.STOP,
            DshFinishReason.TOOL_CALLS,
            DshFinishReason.LENGTH -> Unit
        }
        ordered.firstOrNull { it.name.isNullOrBlank() }?.let { slot ->
            return DshStreamOutcome.Failed(
                code = "invalid_tool_call",
                message = "The model backend emitted tool call index ${slot.index} without a function name.",
                retryable = false
            )
        }
        ordered.firstOrNull { it.arguments.isEmpty() }?.let { slot ->
            return DshStreamOutcome.Failed(
                code = "invalid_tool_call",
                message = "The model backend emitted tool call index ${slot.index} without any " +
                    "argument fragments; executable arguments cannot be proven complete.",
                retryable = false
            )
        }
        if (event.finishReason == DshFinishReason.TOOL_CALLS && ordered.isEmpty()) {
            return DshStreamOutcome.Failed(
                code = "invalid_tool_calls",
                message = "The model backend reported finish_reason=tool_calls without any tool call.",
                retryable = false
            )
        }
        val calls = ordered.map { slot ->
            val arguments = slot.arguments.toString()
            if (!DshGatewayJson.isStrictJsonObject(arguments)) {
                return DshStreamOutcome.Failed(
                    code = "invalid_tool_call",
                    message = "The tool arguments for index ${slot.index} are not complete JSON.",
                    retryable = false
                )
            }
            DshAssembledToolCall(
                index = slot.index,
                id = slot.id ?: mintId(slot.index),
                name = slot.name.orEmpty(),
                argumentsJson = arguments
            )
        }
        return DshStreamOutcome.Completed(
            finishReason = event.finishReason,
            promptTokens = event.promptTokens,
            completionTokens = event.completionTokens,
            toolCalls = calls
        )
    }

    private fun adoptId(slot: Slot, presented: String) {
        val current = slot.id
        if (current == null) {
            val ownerIndex = usedIds[presented]
            if (ownerIndex != null && ownerIndex != slot.index) {
                throw DshToolCallProtocolException(
                    "invalid_tool_call",
                    "The model backend reused tool call id '$presented' for indexes " +
                        "$ownerIndex and ${slot.index}."
                )
            }
            usedIds[presented] = slot.index
            slot.id = presented
            return
        }
        if (current != presented) {
            throw DshToolCallProtocolException(
                "invalid_tool_call",
                "The model backend changed the tool call id for index ${slot.index}."
            )
        }
    }

    private fun appendArguments(slot: Slot, fragment: String) {
        if (fragment.isEmpty()) return
        val nextLength = slot.arguments.length + fragment.length
        if (nextLength > maxArgumentChars) {
            throw DshToolCallProtocolException(
                "tool_arguments_too_large",
                "The tool arguments for index ${slot.index} exceed $maxArgumentChars characters."
            )
        }
        slot.arguments.append(fragment)
    }

    private fun mintId(index: Int): String = "call_${callId.replace(':', '_')}_$index"
}

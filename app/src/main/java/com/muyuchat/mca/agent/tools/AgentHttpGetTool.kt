package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.FrozenToolCommand
import org.json.JSONObject

/**
 * `http.get`: one bounded HTTPS GET with an explicit run egress authorization.
 *
 * Order of checks (all before any socket is opened):
 * 1. frozen tool/schema identity ([runBuiltInTool]);
 * 2. strict argument shape and destination policy ([AgentHttpDestinationRules]);
 * 3. host-owned run authorization ([AgentHttpGetGate]) for the canonical origin;
 * 4. bounded transport call with its own deadline.
 *
 * The tool never follows redirects, never re-sends a request automatically and never reports a
 * truncated body as complete. A denied gate is a DENIED result with a typed policy code; transport
 * failures are FAILED results. Exceptions never contain the URL's query string.
 */
class AgentHttpGetTool(
    private val client: AgentHttpTextClient,
    private val gate: AgentHttpGetGate = AgentHttpGetGate.DENY_ALL
) : AgentToolExecutor {
    override val descriptor: AgentToolDescriptor = AgentExtendedToolDescriptors.HTTP_GET

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = false) {
            val fields = AgentToolArguments.parse(command)
            AgentToolArguments.rejectUnknown(fields, setOf("url", "maxBytes", "timeoutMillis"))
            val rawUrl = AgentToolArguments.requiredText(fields, "url", AgentHttpDestinationRules.MAX_URL_CHARS)
            val destination = when (val checked = AgentHttpDestinationRules.check(rawUrl)) {
                is AgentHttpUrlResult.Valid -> checked
                is AgentHttpUrlResult.Invalid -> {
                    return@runBuiltInTool AgentToolResults.denied(
                        command,
                        descriptor,
                        checked.code,
                        checked.safeMessage
                    )
                }
            }
            val maxBytes = AgentToolArguments
                .optionalInteger(fields, "maxBytes", DEFAULT_MAX_BYTES.toLong(), 1L, MAX_MAX_BYTES.toLong())
                .toInt()
            val timeoutMillis = AgentToolArguments
                .optionalInteger(fields, "timeoutMillis", DEFAULT_TIMEOUT_MILLIS, MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS)

            val decision = gate.authorize(
                AgentHttpGetAuthorization(
                    runId = context.runId,
                    origin = destination.origin,
                    url = destination.url
                )
            )
            if (decision is AgentHttpGetGateDecision.Denied) {
                return@runBuiltInTool AgentToolResults.denied(
                    command,
                    descriptor,
                    decision.code.ifBlank { "POLICY_EGRESS_DENIED" },
                    decision.safeMessage.ifBlank { "This run is not authorized for the requested origin." }
                )
            }

            when (
                val outcome = client.get(
                    AgentHttpGetRequest(
                        url = destination.url,
                        origin = destination.origin,
                        maxBytes = maxBytes,
                        timeoutMillis = timeoutMillis
                    )
                )
            ) {
                is AgentHttpGetOutcome.Failure -> AgentToolResults.failure(
                    command,
                    descriptor,
                    outcome.code,
                    outcome.safeMessage,
                    possibleSideEffects = outcome.possibleSideEffects
                )
                is AgentHttpGetOutcome.Response -> {
                    var text = outcome.text
                    val data = JSONObject()
                        .put("url", destination.url)
                        .put("origin", destination.origin)
                        .put("statusCode", outcome.statusCode)
                        .put("contentType", outcome.contentType ?: JSONObject.NULL)
                        .put("bodyBytes", outcome.bodyBytes)
                        .put("truncated", outcome.truncated)
                        .put("textAvailable", text != null)
                        .put("text", text ?: JSONObject.NULL)
                    val shrink: (JSONObject) -> Boolean = { target ->
                        val current = text
                        if (current == null || current.length <= 1) {
                            false
                        } else {
                            text = current.substring(0, current.length / 2)
                            target.put("text", text)
                            target.put("truncated", true)
                            true
                        }
                    }
                    AgentToolResults.success(
                        command = command,
                        descriptor = descriptor,
                        summary = buildString {
                            append("Fetched ${outcome.bodyBytes} bytes from ")
                            append(destination.origin)
                            append(" (HTTP ${outcome.statusCode})")
                            if (outcome.truncated) append("; the body was truncated at the byte budget.")
                            if (text == null) append("; the payload is not UTF-8 text.")
                        },
                        data = data,
                        truncated = outcome.truncated,
                        shrink = shrink
                    )
                }
            }
        }

    private companion object {
        const val DEFAULT_MAX_BYTES = 32 * 1024
        const val MAX_MAX_BYTES = 128 * 1024
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val MIN_TIMEOUT_MILLIS = 1_000L
        const val MAX_TIMEOUT_MILLIS = 15_000L
    }
}

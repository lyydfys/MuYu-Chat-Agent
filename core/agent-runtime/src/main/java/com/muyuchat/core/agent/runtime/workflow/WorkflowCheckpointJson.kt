package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec

/**
 * Canonical JSON for [WorkflowRunCheckpoint] (N19).
 *
 * The runtime owns this encoding so durable stores never reconstruct node facts from ad-hoc JSON.
 * Decoding is strict: unknown/missing keys, duplicate node facts, invalid states or a revision below
 * zero all return null (the caller treats it as a corrupt checkpoint, never as an empty one).
 */
object WorkflowCheckpointJson {
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()

    fun encode(checkpoint: WorkflowRunCheckpoint): String = codec.write(
        AgentJsonValue.Obj(
            listOf(
                "definitionId" to AgentJsonValue.Str(checkpoint.definitionId),
                "definitionVersion" to AgentJsonValue.Num(checkpoint.definitionVersion.toString()),
                "revision" to AgentJsonValue.Num(checkpoint.revision.toString()),
                "outcomes" to AgentJsonValue.Arr(checkpoint.outcomes.map(::outcomeJson)),
                "nodeOutputs" to mapJson(checkpoint.nodeOutputs),
                "outputs" to mapJson(checkpoint.outputs),
                "activeEdges" to AgentJsonValue.Arr(
                    checkpoint.activeEdges.sorted().map(AgentJsonValue::Str)
                ),
                "skipped" to AgentJsonValue.Arr(checkpoint.skipped.sorted().map(AgentJsonValue::Str)),
                "compensating" to AgentJsonValue.Bool(checkpoint.compensating),
                "compensated" to AgentJsonValue.Arr(
                    checkpoint.compensated.sorted().map(AgentJsonValue::Str)
                )
            ) + listOfNotNull(
                checkpoint.failureCode?.let { "failureCode" to AgentJsonValue.Str(it) },
                checkpoint.failureMessage?.let { "failureMessage" to AgentJsonValue.Str(it) }
            )
        )
    )

    fun decode(text: String): WorkflowRunCheckpoint? {
        val parsed = codec.parse(text) as? AgentJsonParseResult.Parsed ?: return null
        val root = parsed.value as? AgentJsonValue.Obj ?: return null
        val fields = root.fields.toMap()
        val definitionId = (fields["definitionId"] as? AgentJsonValue.Str)?.value ?: return null
        val version = (fields["definitionVersion"] as? AgentJsonValue.Num)?.literal?.toLongOrNull()
            ?: return null
        val revision = (fields["revision"] as? AgentJsonValue.Num)?.literal?.toLongOrNull() ?: return null
        val outcomes = (fields["outcomes"] as? AgentJsonValue.Arr)?.items?.map { item ->
            decodeOutcome(item) ?: return null
        } ?: return null
        val nodeOutputs = decodeMap(fields["nodeOutputs"]) ?: return null
        val outputs = decodeMap(fields["outputs"]) ?: return null
        val activeEdges = (fields["activeEdges"] as? AgentJsonValue.Arr)?.items?.map { item ->
            (item as? AgentJsonValue.Str)?.value ?: return null
        }?.toSet() ?: return null
        val skipped = (fields["skipped"] as? AgentJsonValue.Arr)?.items?.map { item ->
            (item as? AgentJsonValue.Str)?.value ?: return null
        }?.toSet() ?: return null
        val compensating = (fields["compensating"] as? AgentJsonValue.Bool)?.value ?: false
        val compensated = (fields["compensated"] as? AgentJsonValue.Arr)?.items?.map { item ->
            (item as? AgentJsonValue.Str)?.value ?: return null
        }?.toSet() ?: emptySet()
        val failureCode = (fields["failureCode"] as? AgentJsonValue.Str)?.value
        val failureMessage = (fields["failureMessage"] as? AgentJsonValue.Str)?.value
        return runCatching {
            WorkflowRunCheckpoint(
                definitionId = definitionId,
                definitionVersion = version,
                revision = revision,
                outcomes = outcomes,
                nodeOutputs = nodeOutputs,
                outputs = outputs,
                activeEdges = activeEdges,
                skipped = skipped,
                compensating = compensating,
                compensated = compensated,
                failureCode = failureCode,
                failureMessage = failureMessage
            )
        }.getOrNull()
    }

    private fun outcomeJson(outcome: WorkflowNodeOutcome): AgentJsonValue = AgentJsonValue.Obj(
        listOfNotNull(
            "nodeId" to AgentJsonValue.Str(outcome.nodeId),
            "state" to AgentJsonValue.Str(outcome.state.name),
            outcome.output?.let { "output" to it },
            outcome.errorCode?.let { "errorCode" to AgentJsonValue.Str(it) },
            outcome.safeMessage?.let { "safeMessage" to AgentJsonValue.Str(it) }
        )
    )

    private fun decodeOutcome(value: AgentJsonValue): WorkflowNodeOutcome? {
        val fields = (value as? AgentJsonValue.Obj)?.fields?.toMap() ?: return null
        val nodeId = (fields["nodeId"] as? AgentJsonValue.Str)?.value ?: return null
        val state = (fields["state"] as? AgentJsonValue.Str)?.value
            ?.let { name -> WorkflowNodeState.entries.firstOrNull { it.name == name } }
            ?: return null
        return WorkflowNodeOutcome(
            nodeId = nodeId,
            state = state,
            output = fields["output"],
            errorCode = (fields["errorCode"] as? AgentJsonValue.Str)?.value,
            safeMessage = (fields["safeMessage"] as? AgentJsonValue.Str)?.value
        )
    }

    private fun mapJson(values: Map<String, AgentJsonValue>): AgentJsonValue =
        AgentJsonValue.Obj(values.entries.sortedBy { it.key }.map { it.key to it.value })

    private fun decodeMap(value: AgentJsonValue?): Map<String, AgentJsonValue>? {
        val obj = value as? AgentJsonValue.Obj ?: return null
        return obj.fields.toMap()
    }
}

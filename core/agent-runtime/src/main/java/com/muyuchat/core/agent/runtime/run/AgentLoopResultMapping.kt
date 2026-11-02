package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.AgentArgumentValidation
import com.muyuchat.core.agent.runtime.AgentTurnDecodeResult
import com.muyuchat.core.agent.runtime.AgentTurnDecoder
import com.muyuchat.core.agent.runtime.AgentControlMessage
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentSystemPromptBuilder
import com.muyuchat.core.agent.runtime.AgentToolSchemaValidator
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.AgentTranscriptSerializer
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.context.AgentContextAssembler
import com.muyuchat.core.agent.runtime.context.AgentContextAssemblyRequest
import com.muyuchat.core.agent.runtime.context.AgentContextAssemblyResult
import com.muyuchat.core.agent.runtime.journal.AgentJournalRejectedException
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.AgentTurnExecution
import com.muyuchat.core.agent.runtime.protocol.toExecutionMessage
import com.muyuchat.core.agent.runtime.protocol.AgentProtocolErrorCodes
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import kotlinx.coroutines.CancellationException

internal fun AgentPromptedLoopCoordinator.toObservation(result: AgentToolResult): AgentJsonValue {
    val fields = ArrayList<Pair<String, AgentJsonValue>>()
    fields += "schemaVersion" to AgentJsonValue.Num("1")
    fields += "status" to AgentJsonValue.Str(result.status.name)
    fields += "ok" to AgentJsonValue.Bool(result.status == AgentToolResultStatus.SUCCEEDED)
    fields += "summary" to AgentJsonValue.Str(result.summary)
    fields += "artifactIds" to AgentJsonValue.Arr(
        result.artifactIds.map { AgentJsonValue.Str(it) }
    )
    fields += "truncated" to AgentJsonValue.Bool(result.truncated)
    fields += "possibleSideEffects" to AgentJsonValue.Bool(result.possibleSideEffects)
    result.dataJson?.let { data ->
        fields += when (val parsed = codec.parse(data)) {
            is AgentJsonParseResult.Parsed -> "data" to parsed.value
            is AgentJsonParseResult.Failed -> "dataText" to AgentJsonValue.Str(data.take(16 * 1024))
        }
    }
    result.errorCode?.let { fields += "errorCode" to AgentJsonValue.Str(it) }
    result.fullResultRef?.let { fields += "fullResultRef" to AgentJsonValue.Str(it) }
    return AgentJsonValue.Obj(fields)
}

internal fun AgentPromptedLoopCoordinator.toolObservation(
    status: AgentToolResultStatus,
    summary: String,
    errorCode: String? = null,
    possibleSideEffects: Boolean = false
): AgentJsonValue {
    val fields = ArrayList<Pair<String, AgentJsonValue>>()
    fields += "schemaVersion" to AgentJsonValue.Num("1")
    fields += "status" to AgentJsonValue.Str(status.name)
    fields += "ok" to AgentJsonValue.Bool(status == AgentToolResultStatus.SUCCEEDED)
    fields += "summary" to AgentJsonValue.Str(summary)
    fields += "artifactIds" to AgentJsonValue.Arr(emptyList())
    fields += "truncated" to AgentJsonValue.Bool(false)
    fields += "possibleSideEffects" to AgentJsonValue.Bool(possibleSideEffects)
    errorCode?.let { fields += "errorCode" to AgentJsonValue.Str(it) }
    return AgentJsonValue.Obj(fields)
}

internal suspend fun AgentPromptedLoopCoordinator.appendEvent(
    run: AgentRun,
    type: String,
    summary: String?,
    causationId: String? = null
): AgentRun = eventWriter.append(run, type, summary, causationId)

internal fun AgentPromptedLoopCoordinator.pause(
    run: AgentRun,
    entries: List<AgentTranscriptEntry>,
    stepIndex: Int,
    repairs: Int,
    spent: com.muyuchat.core.agent.contract.AgentSpentBudget,
    exceeded: AgentBudgetDecision.Exceeded
): AgentLoopResult.Paused = AgentLoopResult.Paused(
    run = run.copy(spentBudget = spent),
    transcript = transcript(entries, stepIndex, repairs),
    reason = exceeded.pauseReason,
    error = exceeded.error
)

internal fun AgentPromptedLoopCoordinator.transcript(
    entries: List<AgentTranscriptEntry>,
    stepIndex: Int,
    repairs: Int
): AgentLoopTranscript = AgentLoopTranscript(entries.toList(), stepIndex, repairs)

internal fun AgentPromptedLoopCoordinator.outputTokensOf(result: AgentModelResult): Int = when (result) {
    is AgentModelResult.Success -> result.usage?.outputTokens
        ?: ((result.visibleText.toByteArray(Charsets.UTF_8).size + 2) / 3 + 1)
    else -> 0
}

internal fun AgentPromptedLoopCoordinator.terminalLabel(result: AgentModelResult): String = when (result) {
    is AgentModelResult.Success -> "SUCCEEDED"
    is AgentModelResult.Failure -> "FAILED"
    is AgentModelResult.Cancelled -> "CANCELLED"
}

internal fun AgentPromptedLoopCoordinator.budgetSummaryJson(budget: AgentBudget): String = codec.write(
    AgentJsonValues.canonical(
        AgentJsonValues.obj(
            "modelAttempts" to AgentJsonValue.Num(budget.modelAttempts.toString()),
            "toolInvocations" to AgentJsonValue.Num(budget.toolInvocations.toString()),
            "callsPerModelResponse" to AgentJsonValue.Num(
                budget.callsPerModelResponse.toString()
            ),
            "protocolRepairs" to AgentJsonValue.Num(budget.protocolRepairs.toString()),
            "modelWallTimeMillis" to AgentJsonValue.Num(budget.modelWallTimeMillis.toString()),
            "runActiveTimeMillis" to AgentJsonValue.Num(budget.runActiveTimeMillis.toString()),
            "toolResultToModelBytes" to AgentJsonValue.Num(
                budget.toolResultToModelBytes.toString()
            ),
            "textOutputTokens" to AgentJsonValue.Num(budget.textOutputTokens.toString()),
            "totalGeneratedTokens" to AgentJsonValue.Num(
                budget.totalGeneratedTokens.toString()
            )
        )
    )
)

internal fun AgentPromptedLoopCoordinator.protocolError(message: String): AgentError = AgentError(
    code = AgentErrorCodes.MODEL_PROTOCOL_ERROR,
    category = AgentErrorCategory.MODEL,
    retryability = AgentRetryability.AFTER_USER_ACTION,
    safeMessage = message.take(2 * 1024),
    correlationId = AgentPromptedLoopCoordinator.CORRELATION_ID
)

/**
 * Per-run catalog resolution failed before any decoder/model/tool work: return a typed Tool
 * failure with no partial transcript instead of crashing or continuing with an empty catalog.
 */
internal fun AgentPromptedLoopCoordinator.catalogUnavailableResult(run: AgentRun, failure: Throwable): AgentLoopResult.Failed =
    AgentLoopResult.Failed(
        run = run,
        transcript = AgentLoopTranscript(emptyList(), 0, 0),
        error = AgentError(
            code = AgentRunToolCatalogErrorCodes.TOOL_CATALOG_UNAVAILABLE,
            category = AgentErrorCategory.TOOL,
            retryability = AgentRetryability.AFTER_USER_ACTION,
            safeMessage = "The tool catalog for this run could not be resolved.",
            correlationId = AgentPromptedLoopCoordinator.CORRELATION_ID,
            causeRef = failure.javaClass.simpleName
        )
    )

/**
 * Reads the catalog hash recorded by the first execution of this run.
 *
 * A missing event means either a fresh run or a run created before this record existed; both
 * may append one now. A present-but-unreadable record must fail closed instead of silently
 * re-deriving a different identity.
 */
internal suspend fun AgentPromptedLoopCoordinator.readRecordedCatalogHash(runId: String): CatalogIdentity = try {
    val page = journal.readEvents(runId, 0L, AgentPromptedLoopCoordinator.CATALOG_EVENT_SCAN_LIMIT)
    val recorded = page.events.firstOrNull { it.type == AgentEventTypes.TOOL_CATALOG_RESOLVED }
    if (recorded == null) {
        CatalogIdentity.Absent
    } else {
        val summary = recorded.summary
        val parsed = summary?.let(codec::parse)
        val fields = (parsed as? AgentJsonParseResult.Parsed)?.value as? AgentJsonValue.Obj
        val hash = fields?.fields?.firstOrNull { it.first == "catalogHash" }?.second
        if (hash is AgentJsonValue.Str && hash.value.matches(Regex("^[0-9a-f]{64}$"))) {
            val tools = fields.fields.firstOrNull { it.first == "tools" }?.second
            val identities = if (tools == null) {
                emptyList()
            } else {
                val items = (tools as? AgentJsonValue.Arr)?.items
                    ?: return CatalogIdentity.Unreadable("the recorded catalog tools are malformed")
                items.map { item ->
                    val tool = item as? AgentJsonValue.Obj
                        ?: return CatalogIdentity.Unreadable("a recorded tool identity is malformed")
                    val map = tool.fields.toMap()
                    val key = (map["toolKey"] as? AgentJsonValue.Str)?.value
                    val version = (map["version"] as? AgentJsonValue.Str)?.value
                    val schemaHash = (map["schemaHash"] as? AgentJsonValue.Str)?.value
                    if (key.isNullOrBlank() || version.isNullOrBlank() || schemaHash.isNullOrBlank()) {
                        return CatalogIdentity.Unreadable("a recorded tool identity is incomplete")
                    }
                    RecordedToolIdentity(key, version, schemaHash)
                }.sortedBy { it.toolKey }
            }
            CatalogIdentity.Recorded(hash.value, identities)
        } else {
            CatalogIdentity.Unreadable("the recorded catalog hash is missing or malformed")
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    CatalogIdentity.Unreadable("the catalog record could not be read: " + failure.javaClass.simpleName)
}

internal fun AgentPromptedLoopCoordinator.catalogEventSummary(catalogHash: String, catalog: AgentToolCatalog): String =
    codec.write(
        AgentJsonValues.canonical(
            AgentJsonValues.obj(
                "catalogHash" to AgentJsonValues.str(catalogHash),
                "toolKeys" to AgentJsonValues.arr(
                    catalog.snapshot()
                        .map { it.toolKey }
                        .distinct()
                        .sorted()
                        .map { AgentJsonValues.str(it) }
                ),
                // N04: exact identities let a later execution rebuild the recorded surface
                // instead of only detecting drift.
                "tools" to AgentJsonValues.arr(
                    catalog.snapshot()
                        .sortedBy { it.toolKey }
                        .map { descriptor ->
                            AgentJsonValues.obj(
                                "toolKey" to AgentJsonValues.str(descriptor.toolKey),
                                "version" to AgentJsonValues.str(descriptor.version),
                                "schemaHash" to AgentJsonValues.str(descriptor.schemaHash)
                            )
                        }
                )
            )
        )
    )

/**
 * The run already executed with a different tool surface. Continuing would silently change
 * which tools/approvals the model can reach, so the execution fails with a typed Tool error and
 * no partial transcript. The user must start a new run with the current catalog.
 */
internal fun AgentPromptedLoopCoordinator.catalogDriftResult(run: AgentRun, recorded: CatalogIdentity, currentHash: String): AgentLoopResult.Failed {
    val recordedHash = when (recorded) {
        is CatalogIdentity.Recorded -> recorded.hash
        CatalogIdentity.Absent -> "none"
        is CatalogIdentity.Unreadable -> "unreadable"
    }
    val reason = (recorded as? CatalogIdentity.Unreadable)?.reason
        ?: "the tool catalog changed after the run started"
    return AgentLoopResult.Failed(
        run = run,
        transcript = AgentLoopTranscript(emptyList(), 0, 0),
        error = AgentError(
            code = AgentRunToolCatalogErrorCodes.TOOL_CATALOG_DRIFT,
            category = AgentErrorCategory.TOOL,
            retryability = AgentRetryability.AFTER_USER_ACTION,
            safeMessage = "The tool catalog for this run is no longer the one it started with; " +
                "start a new run to use the current tools.",
            correlationId = AgentPromptedLoopCoordinator.CORRELATION_ID,
            causeRef = "recorded=" + recordedHash.take(12) + ",current=" + currentHash.take(12) +
                (reason.takeIf { recorded is CatalogIdentity.Unreadable }?.let { ";$it" } ?: "")
        )
    )
}

internal sealed interface CatalogIdentity {
    data class Recorded(
        val hash: String,
        val tools: List<RecordedToolIdentity> = emptyList()
    ) : CatalogIdentity

    data object Absent : CatalogIdentity

    data class Unreadable(val reason: String) : CatalogIdentity
}

internal fun AgentPromptedLoopCoordinator.internalError(message: String): AgentError = AgentError(
    code = AgentErrorCodes.INTERNAL_INVARIANT,
    category = AgentErrorCategory.INTERNAL,
    retryability = AgentRetryability.NEVER,
    safeMessage = message.take(2 * 1024),
    correlationId = AgentPromptedLoopCoordinator.CORRELATION_ID
)

internal fun AgentBudgetDecision.exceededOrNull(): AgentBudgetDecision.Exceeded? =
    this as? AgentBudgetDecision.Exceeded

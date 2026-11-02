package com.muyuchat.core.engine

import kotlinx.coroutines.delay

/**
 * Real native generation activity for one runner.
 *
 * [IDLE] is the only value that may be treated as "the native owner is quiet". [UNKNOWN] means the
 * runtime cannot prove anything; it must never be converted into success. Values come from the
 * runner's own native call return points and local flags, never from "our request map is empty".
 */
enum class NativeGenerationActivity {
    ACTIVE,
    IDLE,
    UNKNOWN
}

/**
 * Optional narrow capability implemented by a [LocalChatRunner] that can prove its native
 * generation state without entering a blocking JNI/Binder query.
 *
 * Implementations must:
 *  * return from process-local counters/flags or one non-blocking native atomic read;
 *  * never wait for a decode/native step, never take a native lock and never poll;
 *  * return [NativeGenerationActivity.UNKNOWN] when that evidence is unavailable.
 */
fun interface NativeQuiescenceProbe {
    fun nativeGenerationActivity(): NativeGenerationActivity
}

internal fun LocalChatRunner.nativeGenerationActivityOrUnknown(): NativeGenerationActivity =
    // A probe failure is missing evidence, never idle evidence.
    runCatching {
        (this as? NativeQuiescenceProbe)?.nativeGenerationActivity()
            ?: NativeGenerationActivity.UNKNOWN
    }.getOrElse { NativeGenerationActivity.UNKNOWN }

/**
 * Exact identity of one engine request execution. [requestEpoch] is the engine-owned generation
 * epoch assigned when the request activated its stop target; null means the request never reached
 * native dispatch. [loadIncarnation] changes on every native lifecycle transition (load/unload),
 * so a proof taken before a reload can never be applied to the replacement worker.
 */
data class EngineExecutionIdentity(
    val requestId: String,
    val requestEpoch: Long?,
    val loadIncarnation: Long,
    val runtime: String
)

/**
 * Typed answer to "is the exact engine request proven quiescent?".
 *
 * A lease may be released only for [Released] (real native idle evidence for the exact request) or
 * [NotDispatched] (the stream returned before any native begin call). [StoppingUnknown],
 * [TimedOut] and [Unknown] keep the resource fence: none of them is a safe release.
 */
sealed interface EngineStopSettlement {
    /** The stream returned before dispatching native work; direct release is safe. */
    data class NotDispatched(
        val identity: EngineExecutionIdentity
    ) : EngineStopSettlement

    /** The exact request returned and the runtime proved its native generation is idle. */
    data class Released(
        val identity: EngineExecutionIdentity,
        val evidence: String
    ) : EngineStopSettlement

    /** Quiescence is not proven (missing/unsupported native evidence); keep STOPPING. */
    data class StoppingUnknown(
        val identity: EngineExecutionIdentity,
        val detail: String,
        val activity: NativeGenerationActivity
    ) : EngineStopSettlement

    /** The bounded proof wait expired; keep STOPPING and retain the lease. */
    data class TimedOut(
        val identity: EngineExecutionIdentity,
        val waitedMillis: Long,
        val detail: String,
        val activity: NativeGenerationActivity
    ) : EngineStopSettlement

    /** No record, a superseded request, or a stale epoch/incarnation. Never a proof. */
    data class Unknown(
        val requestId: String,
        val reason: String
    ) : EngineStopSettlement
}

/** Immutable copy of one request execution record, read under the record monitor. */
internal data class RequestExecutionSnapshot(
    val requestId: String,
    val tokenEpoch: Long?,
    val runtimeId: String,
    val loadIncarnation: Long,
    val dispatched: Boolean,
    val streamTerminal: Boolean,
    val superseded: Boolean,
    val reason: String?,
    val lastActivity: NativeGenerationActivity,
    val lastDetail: String
)

/**
 * Engine-owned execution record for exactly one request id.
 *
 * It deliberately survives the request-scoped stop token: clearing the token is a UI/lifecycle
 * fact, never native quiescence. The record keeps the engine-owned facts (dispatch, stream return)
 * and the last observed native activity until the bounded record table evicts it or a lifecycle
 * transition/replacement stream marks it superseded.
 */
internal class RequestExecutionRecord(
    val requestId: String,
    private val runtimeId: String,
    private val loadIncarnation: Long,
    internal val runner: LocalChatRunner?
) {
    private val lock = Any()
    private var tokenEpoch: Long? = null
    private var dispatched = false
    private var streamTerminal = false
    private var superseded = false
    private var supersededReason: String? = null
    private var lastActivity: NativeGenerationActivity = NativeGenerationActivity.UNKNOWN
    private var lastDetail: String = "no native activity evidence yet"

    fun attachToken(epoch: Long) {
        synchronized(lock) {
            tokenEpoch = if (tokenEpoch == null) epoch else maxOf(tokenEpoch ?: epoch, epoch)
        }
    }

    fun markDispatchStarted(epoch: Long) {
        synchronized(lock) {
            if (tokenEpoch == null || tokenEpoch == epoch) dispatched = true
        }
    }

    fun markStreamTerminal(detail: String) {
        synchronized(lock) {
            streamTerminal = true
            lastDetail = detail
        }
    }

    fun markSuperseded(reason: String) {
        synchronized(lock) {
            superseded = true
            supersededReason = reason
        }
    }

    fun observe(activity: NativeGenerationActivity, detail: String) {
        synchronized(lock) {
            lastActivity = activity
            lastDetail = detail
        }
    }

    fun snapshot(): RequestExecutionSnapshot = synchronized(lock) {
        RequestExecutionSnapshot(
            requestId = requestId,
            tokenEpoch = tokenEpoch,
            runtimeId = runtimeId,
            loadIncarnation = loadIncarnation,
            dispatched = dispatched,
            streamTerminal = streamTerminal,
            superseded = superseded,
            reason = supersededReason,
            lastActivity = lastActivity,
            lastDetail = lastDetail
        )
    }
}

/** Internal classification of one inspection: either a final settlement or "keep waiting". */
internal sealed interface StopSettlementInspection {
    data class Pending(
        val identity: EngineExecutionIdentity,
        val detail: String,
        val activity: NativeGenerationActivity
    ) : StopSettlementInspection

    data class Final(val settlement: EngineStopSettlement) : StopSettlementInspection
}

/** Lowest-cost cadence for re-reading engine-owned state and the non-blocking native probe. */
internal const val STOP_SETTLEMENT_POLL_INTERVAL_MS = 25L

/** Bounded number of retained request proofs; older proofs become [EngineStopSettlement.Unknown]. */
internal const val MAX_TRACKED_REQUEST_EXECUTIONS = 8

internal fun McaInferenceService.beginRequestExecutionRecord(requestId: String): RequestExecutionRecord =
    synchronized(generationStopGate) {
        val record = newRequestExecutionRecordLocked(requestId)
        val replaced = requestExecutionRecords.put(requestId, record)
        replaced?.markSuperseded("a later execution reused the same request id")
        evictOldRequestRecordsLocked(keep = record)
        record
    }

/** Called only while [McaInferenceService.generationStopGate] is held. */
internal fun McaInferenceService.newRequestExecutionRecordLocked(requestId: String): RequestExecutionRecord {
    val runtime = activeRuntime
    return RequestExecutionRecord(
        requestId = requestId,
        runtimeId = runtime.backendId,
        loadIncarnation = loadIncarnation,
        runner = runners[runtime]
    )
}

/** Called only while [McaInferenceService.generationStopGate] is held. */
internal fun McaInferenceService.supersedeOtherRecordsLocked(
    keep: RequestExecutionRecord,
    reason: String
) {
    requestExecutionRecords.values.forEach { candidate ->
        if (candidate !== keep) candidate.markSuperseded(reason)
    }
}

private fun McaInferenceService.evictOldRequestRecordsLocked(keep: RequestExecutionRecord) {
    if (requestExecutionRecords.size <= MAX_TRACKED_REQUEST_EXECUTIONS) return
    // Never evict a live (non-terminal, non-superseded) proof: it may be the lease holder's only
    // evidence. Terminal and superseded records are the safe victims; a burst of blocked collectors
    // may temporarily exceed the soft cap instead of destroying an active proof.
    val terminal = requestExecutionRecords.entries
        .filter { it.value !== keep && it.value.snapshot().streamTerminal }
    val superseded = requestExecutionRecords.entries
        .filter { it.value !== keep && it.value.snapshot().superseded }
    val victim = terminal.firstOrNull() ?: superseded.firstOrNull() ?: return
    requestExecutionRecords.remove(victim.key)
    victim.value.markSuperseded("execution proof evicted by the bounded engine record table")
}

internal fun McaInferenceService.finishRequestExecutionRecord(
    record: RequestExecutionRecord,
    detail: String
) {
    record.markStreamTerminal(detail)
}

/** Called only while [McaInferenceService.generationStopGate] is held. */
internal fun McaInferenceService.attachGenerationStopTokenLocked(
    requestId: String,
    token: GenerationStopToken
) {
    val record = requestExecutionRecords[requestId] ?: newRequestExecutionRecordLocked(requestId)
        .also { created ->
            requestExecutionRecords[requestId] = created
            evictOldRequestRecordsLocked(keep = created)
        }
    record.attachToken(token.epoch)
    // Activating the single native stop target is the point where this request becomes the owner
    // of the native generation; every older execution proof is now stale.
    supersedeOtherRecordsLocked(record, "a later engine execution started")
}

internal fun McaInferenceService.markRequestNativeDispatchStarted(token: GenerationStopToken) {
    synchronized(generationStopGate) {
        requestExecutionRecords[token.requestId]?.markDispatchStarted(token.epoch)
    }
}

/** Called only while [McaInferenceService.generationStopGate] is held. */
internal fun McaInferenceService.markLifecycleTransitionLocked(reason: String) {
    loadIncarnation += 1L
    requestExecutionRecords.values.forEach { it.markSuperseded(reason) }
}

internal fun McaInferenceService.stopSettlementSnapshotImpl(
    requestId: String,
    expectedEpoch: Long?
): EngineStopSettlement = when (val inspection = inspectStopSettlement(requestId, expectedEpoch)) {
    is StopSettlementInspection.Pending -> EngineStopSettlement.StoppingUnknown(
        identity = inspection.identity,
        detail = inspection.detail,
        activity = inspection.activity
    )
    is StopSettlementInspection.Final -> inspection.settlement
}

/**
 * Bounded wait for the exact request to become quiescent.
 *
 * The wait itself reads only engine-owned flags and the runner's non-blocking probe. It never takes
 * the engine lifecycle mutex, never dispatches a stop, never polls a blocking JNI/Binder query and
 * never converts a missing token into success.
 */
internal suspend fun McaInferenceService.awaitStopSettlementImpl(
    requestId: String,
    expectedEpoch: Long?,
    timeoutMillis: Long
): EngineStopSettlement {
    require(timeoutMillis > 0L) { "stop settlement timeout must be positive" }
    val started = System.nanoTime()
    var lastPending: StopSettlementInspection.Pending? = null
    while (true) {
        when (val inspection = inspectStopSettlement(requestId, expectedEpoch)) {
            is StopSettlementInspection.Pending -> lastPending = inspection
            is StopSettlementInspection.Final -> return inspection.settlement
        }
        val waitedMillis = (System.nanoTime() - started) / 1_000_000L
        if (waitedMillis >= timeoutMillis) {
            val pending = lastPending ?: return EngineStopSettlement.Unknown(
                requestId = requestId,
                reason = "stop settlement produced no inspection"
            )
            return EngineStopSettlement.TimedOut(
                identity = pending.identity,
                waitedMillis = waitedMillis,
                detail = pending.detail,
                activity = pending.activity
            )
        }
        delay(minOf(STOP_SETTLEMENT_POLL_INTERVAL_MS, timeoutMillis - waitedMillis))
    }
}

private fun McaInferenceService.inspectStopSettlement(
    requestId: String,
    expectedEpoch: Long?
): StopSettlementInspection {
    val record = synchronized(generationStopGate) { requestExecutionRecords[requestId] }
        ?: return finalSettlement(
            EngineStopSettlement.Unknown(
                requestId = requestId,
                reason = "no engine execution record exists for this request"
            )
        )
    val snapshot = record.snapshot()
    val identity = EngineExecutionIdentity(
        requestId = snapshot.requestId,
        requestEpoch = snapshot.tokenEpoch,
        loadIncarnation = snapshot.loadIncarnation,
        runtime = snapshot.runtimeId
    )
    if (snapshot.superseded) {
        return finalSettlement(
            EngineStopSettlement.Unknown(
                requestId = requestId,
                reason = "execution proof is stale: " + (snapshot.reason ?: "superseded")
            )
        )
    }
    val currentIncarnation = synchronized(generationStopGate) { loadIncarnation }
    if (snapshot.loadIncarnation != currentIncarnation) {
        return finalSettlement(
            EngineStopSettlement.Unknown(
                requestId = requestId,
                reason = "execution proof belongs to a previous native incarnation"
            )
        )
    }
    if (expectedEpoch != null && snapshot.tokenEpoch != null && snapshot.tokenEpoch != expectedEpoch) {
        return finalSettlement(
            EngineStopSettlement.Unknown(
                requestId = requestId,
                reason = "execution proof epoch ${snapshot.tokenEpoch} does not match $expectedEpoch"
            )
        )
    }
    if (!snapshot.streamTerminal) {
        record.observe(
            NativeGenerationActivity.ACTIVE,
            "engine stream has not returned yet"
        )
        return StopSettlementInspection.Pending(
            identity = identity,
            detail = "engine stream has not returned yet",
            activity = NativeGenerationActivity.ACTIVE
        )
    }
    if (!snapshot.dispatched) {
        record.observe(
            NativeGenerationActivity.IDLE,
            "stream returned before native dispatch"
        )
        return finalSettlement(EngineStopSettlement.NotDispatched(identity))
    }
    val activity = record.runner?.nativeGenerationActivityOrUnknown()
        ?: NativeGenerationActivity.UNKNOWN
    return when (activity) {
        NativeGenerationActivity.IDLE -> {
            record.observe(
                NativeGenerationActivity.IDLE,
                "native generation idle after the exact request returned"
            )
            finalSettlement(
                EngineStopSettlement.Released(
                    identity = identity,
                    evidence = "native idle (runtime=${snapshot.runtimeId}, " +
                        "requestEpoch=${snapshot.tokenEpoch}, loadIncarnation=${snapshot.loadIncarnation})"
                )
            )
        }

        NativeGenerationActivity.ACTIVE -> {
            record.observe(
                NativeGenerationActivity.ACTIVE,
                "native generation is still active"
            )
            StopSettlementInspection.Pending(
                identity = identity,
                detail = "native generation is still active",
                activity = NativeGenerationActivity.ACTIVE
            )
        }

        NativeGenerationActivity.UNKNOWN -> {
            record.observe(
                NativeGenerationActivity.UNKNOWN,
                "runtime provided no non-blocking native activity evidence"
            )
            finalSettlement(
                EngineStopSettlement.StoppingUnknown(
                    identity = identity,
                    detail = "runtime provided no non-blocking native activity evidence",
                    activity = NativeGenerationActivity.UNKNOWN
                )
            )
        }
    }
}

private fun finalSettlement(settlement: EngineStopSettlement): StopSettlementInspection =
    StopSettlementInspection.Final(settlement)

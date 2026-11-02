package com.muyuchat.core.mediatek

/**
 * Typed record of what a run asked for, what it actually got, and which output
 * the caller adopted. Counts are derived from these records; no business rule
 * may be inferred from backend name strings or transport labels.
 */
enum class MediaTekGraphTransport {
    LITERT_JIT,
    LITERT_AOT,
    NPUG_NATIVE,
    NEURON_COMPILED_NETWORK,
}

enum class MediaTekComputeTarget {
    CPU,
    NPU,
}

enum class MediaTekSelectionReason {
    DEFAULT,
    MODEL_RANGE_PIN,
    INPUT_RANGE_PIN,
    MEMORY_BUDGET,
    NPU_COMPILE_FAILED,
    NPU_EXECUTION_FAILED,
    NUMERIC_MISMATCH,
    ;

    /** Reasons that describe a planned CPU placement rather than a repair after NPU failure. */
    val isPlanningReason: Boolean
        get() = this in setOf(DEFAULT, MODEL_RANGE_PIN, INPUT_RANGE_PIN, MEMORY_BUDGET)

    val isNpuFailureReason: Boolean
        get() = this in setOf(NPU_COMPILE_FAILED, NPU_EXECUTION_FAILED, NUMERIC_MISMATCH)
}

enum class MediaTekExecutionScope {
    /** One graph of the chain. */
    GRAPH,

    /** One compiled partition inside a graph. */
    PARTITION,

    /** Every graph the run needs, attributed end to end. */
    FULL_CHAIN,
}

enum class MediaTekEvidenceSource {
    RUNTIME_COUNTERS,
    DELEGATE_REPORT,
}

/** Only the runtime may confirm a backend; a request alone stays unverified. */
sealed interface MediaTekBackendEvidence {
    data object Unverified : MediaTekBackendEvidence

    data class Confirmed(
        val provider: String,
        val executionScope: MediaTekExecutionScope,
        val npuPartitions: Int?,
        val cpuFallbackOps: Int?,
        val source: MediaTekEvidenceSource,
    ) : MediaTekBackendEvidence
}

data class MediaTekGraphSelection(
    val graphId: String,
    val stage: MediaTekGraphStage,
    val transport: MediaTekGraphTransport,
    val target: MediaTekComputeTarget,
    val reason: MediaTekSelectionReason,
)

sealed interface MediaTekAttemptOutcome {
    data class Succeeded(val outputSha256: String?) : MediaTekAttemptOutcome

    data class Failed(
        val stage: MediaTekGraphFailureStage,
        val message: String,
    ) : MediaTekAttemptOutcome

    data object Cancelled : MediaTekAttemptOutcome
}

data class MediaTekGraphAttempt(
    val graphId: String,
    val stage: MediaTekGraphStage,
    val selection: MediaTekGraphSelection,
    val outcome: MediaTekAttemptOutcome,
    val evidence: MediaTekBackendEvidence,
    val elapsedMs: Long,
) {
    val succeeded: Boolean get() = outcome is MediaTekAttemptOutcome.Succeeded
}

/** The output the run continued with. Adoption is a separate decision from an attempt. */
data class MediaTekGraphAdoption(
    val graphId: String,
    val stage: MediaTekGraphStage,
    val attemptIndex: Int,
    val requestedTarget: MediaTekComputeTarget,
    val adoptedTarget: MediaTekComputeTarget,
    val reason: MediaTekSelectionReason,
)

enum class MediaTekNpuAttribution {
    CONFIRMED,
    UNVERIFIED,
}

enum class MediaTekQualityStatus {
    /** No reference was available; this is not a pass. */
    NOT_COMPARED,
    MATCH,
    DIVERGED,

    /** The comparison itself is unusable (non-finite values or metrics). */
    INVALID,
}

data class MediaTekQualityReport(
    val status: MediaTekQualityStatus,
    val referenceIdentity: String?,
    val relativeL1: Double?,
    val maximumAbsolute: Double?,
    val detail: String,
) {
    val verified: Boolean get() = status == MediaTekQualityStatus.MATCH

    companion object {
        fun notCompared(detail: String): MediaTekQualityReport = MediaTekQualityReport(
            status = MediaTekQualityStatus.NOT_COMPARED,
            referenceIdentity = null,
            relativeL1 = null,
            maximumAbsolute = null,
            detail = detail,
        )

        fun invalid(detail: String): MediaTekQualityReport = MediaTekQualityReport(
            status = MediaTekQualityStatus.INVALID,
            referenceIdentity = null,
            relativeL1 = null,
            maximumAbsolute = null,
            detail = detail,
        )

        fun compare(
            referenceIdentity: String,
            relativeL1: Double,
            maximumAbsolute: Double,
            relativeL1Tolerance: Double,
            maximumAbsoluteTolerance: Double,
        ): MediaTekQualityReport {
            require(referenceIdentity.isNotBlank()) { "reference identity must not be blank" }
            require(relativeL1Tolerance.isFinite() && relativeL1Tolerance >= 0.0) {
                "relative L1 tolerance must be finite and nonnegative"
            }
            require(maximumAbsoluteTolerance.isFinite() && maximumAbsoluteTolerance >= 0.0) {
                "absolute tolerance must be finite and nonnegative"
            }
            if (!relativeL1.isFinite() || !maximumAbsolute.isFinite()) {
                return invalid("comparison metrics are not finite (relativeL1=$relativeL1 maxAbs=$maximumAbsolute)")
            }
            val matched = relativeL1 <= relativeL1Tolerance && maximumAbsolute <= maximumAbsoluteTolerance
            return MediaTekQualityReport(
                status = if (matched) MediaTekQualityStatus.MATCH else MediaTekQualityStatus.DIVERGED,
                referenceIdentity = referenceIdentity,
                relativeL1 = relativeL1,
                maximumAbsolute = maximumAbsolute,
                detail = if (matched) "within tolerance" else "outside tolerance",
            )
        }
    }
}

data class MediaTekBackendTally(
    val plannedCpu: Int,
    val rangeCpu: Int,
    val npuSucceeded: Int,
    val npuFailedThenCpu: Int,
    val supersededFailures: Int,
    val cancelledAttempts: Int,
    val incompleteGraphs: List<String>,
) {
    val adoptedTotal: Int get() = plannedCpu + rangeCpu + npuSucceeded + npuFailedThenCpu
}

data class MediaTekRunSummary(
    val tally: MediaTekBackendTally,
    val quality: MediaTekQualityReport,
    val npuAttribution: MediaTekNpuAttribution,
    val stagesExecuted: Set<MediaTekGraphStage>,
    val outputValid: Boolean,
    val attempts: List<MediaTekGraphAttempt>,
    val adoptions: List<MediaTekGraphAdoption>,
) {
    val executionCompleted: Boolean
        get() = adoptions.isNotEmpty() && tally.incompleteGraphs.isEmpty()

    /**
     * Structural acceptance only. Quality remains a separate fact: a run may be
     * accepted while [qualityVerified] stays false because no baseline existed.
     */
    val accepted: Boolean
        get() = executionCompleted && outputValid &&
            quality.status != MediaTekQualityStatus.DIVERGED &&
            quality.status != MediaTekQualityStatus.INVALID

    val qualityVerified: Boolean get() = quality.verified
}

/**
 * Records per-graph attempts and the adoption decision for each graph.
 *
 * Invariants:
 * - one attempt has exactly one target; an adapter never switches backend internally;
 * - an adoption must reference a successful attempt;
 * - a CPU adoption labelled as an NPU-failure repair requires a recorded failed NPU
 *   attempt for that graph, so planned CPU work cannot be reported as fallback and a
 *   fallback cannot be invented.
 */
class MediaTekGraphScheduler {
    private val lock = Any()
    private val attemptLog = mutableListOf<MediaTekGraphAttempt>()
    private val adoptionLog = mutableListOf<MediaTekGraphAdoption>()
    private val stageLog = linkedSetOf<MediaTekGraphStage>()

    fun plan(
        graphId: String,
        stage: MediaTekGraphStage,
        transport: MediaTekGraphTransport,
        target: MediaTekComputeTarget = MediaTekComputeTarget.NPU,
        reason: MediaTekSelectionReason = MediaTekSelectionReason.DEFAULT,
    ): MediaTekGraphSelection {
        require(graphId.isNotBlank()) { "graphId must not be blank" }
        if (target == MediaTekComputeTarget.CPU && reason.isNpuFailureReason) {
            val failureRecorded = synchronized(lock) {
                attemptLog.any {
                    it.graphId == graphId &&
                        it.selection.target == MediaTekComputeTarget.NPU &&
                        it.outcome is MediaTekAttemptOutcome.Failed
                }
            }
            require(failureRecorded) {
                "CPU retry '$graphId' cannot claim ${reason.name} before that NPU failure is recorded"
            }
        }
        return MediaTekGraphSelection(graphId, stage, transport, target, reason)
    }

    fun record(
        selection: MediaTekGraphSelection,
        outcome: MediaTekAttemptOutcome,
        evidence: MediaTekBackendEvidence,
        elapsedMs: Long,
    ): MediaTekGraphAttempt {
        require(elapsedMs >= 0L) { "elapsedMs must not be negative" }
        if (selection.target == MediaTekComputeTarget.CPU && evidence is MediaTekBackendEvidence.Confirmed) {
            throw IllegalArgumentException(
                "CPU attempt '${selection.graphId}' cannot carry NPU confirmation evidence",
            )
        }
        val attempt = MediaTekGraphAttempt(
            graphId = selection.graphId,
            stage = selection.stage,
            selection = selection,
            outcome = outcome,
            evidence = evidence,
            elapsedMs = elapsedMs,
        )
        synchronized(lock) {
            attemptLog += attempt
            stageLog += selection.stage
        }
        return attempt
    }

    fun adopt(
        selection: MediaTekGraphSelection,
        adoptedTarget: MediaTekComputeTarget = selection.target,
        reason: MediaTekSelectionReason = selection.reason,
    ): MediaTekGraphAdoption {
        synchronized(lock) {
            val index = attemptLog.indexOfLast { it.selection == selection }
            require(index >= 0) { "no attempt was recorded for '${selection.graphId}'" }
            val attempt = attemptLog[index]
            require(attempt.succeeded) {
                "cannot adopt ${attempt.outcome::class.simpleName} attempt for '${selection.graphId}'"
            }
            if (adoptedTarget == MediaTekComputeTarget.CPU && reason.isNpuFailureReason) {
                val failure = attemptLog.take(index).any {
                    it.graphId == selection.graphId &&
                        it.selection.target == MediaTekComputeTarget.NPU &&
                        it.outcome is MediaTekAttemptOutcome.Failed
                }
                require(failure) {
                    "CPU adoption of '${selection.graphId}' labelled ${reason.name} has no recorded NPU failure"
                }
            }
            val adoption = MediaTekGraphAdoption(
                graphId = selection.graphId,
                stage = selection.stage,
                attemptIndex = index,
                requestedTarget = selection.target,
                adoptedTarget = adoptedTarget,
                reason = reason,
            )
            adoptionLog += adoption
            return adoption
        }
    }

    fun attempts(): List<MediaTekGraphAttempt> = synchronized(lock) { attemptLog.toList() }

    fun adoptions(): List<MediaTekGraphAdoption> = synchronized(lock) { adoptionLog.toList() }

    fun tally(): MediaTekBackendTally = synchronized(lock) { tallyLocked() }

    fun summary(
        quality: MediaTekQualityReport,
        outputValid: Boolean,
        stagesExecuted: Set<MediaTekGraphStage> = synchronized(lock) { stageLog.toSet() },
    ): MediaTekRunSummary = synchronized(lock) {
        val tally = tallyLocked()
        return MediaTekRunSummary(
            tally = tally,
            quality = quality,
            npuAttribution = attributionLocked(stagesExecuted),
            stagesExecuted = stagesExecuted,
            outputValid = outputValid,
            attempts = attemptLog.toList(),
            adoptions = adoptionLog.toList(),
        )
    }

    private fun tallyLocked(): MediaTekBackendTally {
        var plannedCpu = 0
        var rangeCpu = 0
        var npuSucceeded = 0
        var npuFailedThenCpu = 0
        val adoptedIndexes = adoptionLog.mapTo(mutableSetOf()) { it.attemptIndex }
        adoptionLog.forEach { adoption ->
            when {
                adoption.adoptedTarget == MediaTekComputeTarget.NPU -> npuSucceeded++
                adoption.reason == MediaTekSelectionReason.INPUT_RANGE_PIN -> rangeCpu++
                adoption.reason.isNpuFailureReason -> npuFailedThenCpu++
                else -> plannedCpu++
            }
        }
        val supersededFailures = attemptLog.indices.count { index ->
            index !in adoptedIndexes && attemptLog[index].outcome is MediaTekAttemptOutcome.Failed
        }
        val cancelledAttempts = attemptLog.count { it.outcome is MediaTekAttemptOutcome.Cancelled }
        val adoptedGraphs = adoptionLog.mapTo(mutableSetOf()) { it.graphId }
        val incompleteGraphs = attemptLog.map { it.graphId }.distinct().filterNot(adoptedGraphs::contains)
        return MediaTekBackendTally(
            plannedCpu = plannedCpu,
            rangeCpu = rangeCpu,
            npuSucceeded = npuSucceeded,
            npuFailedThenCpu = npuFailedThenCpu,
            supersededFailures = supersededFailures,
            cancelledAttempts = cancelledAttempts,
            incompleteGraphs = incompleteGraphs,
        )
    }

    private fun attributionLocked(stagesExecuted: Set<MediaTekGraphStage>): MediaTekNpuAttribution {
        val npuAdoptions = adoptionLog.filter { it.adoptedTarget == MediaTekComputeTarget.NPU }
        if (npuAdoptions.isEmpty()) return MediaTekNpuAttribution.UNVERIFIED
        if (!stagesExecuted.containsAll(MediaTekGraphStage.entries)) return MediaTekNpuAttribution.UNVERIFIED
        val allConfirmed = npuAdoptions.all { adoption ->
            attemptLog[adoption.attemptIndex].evidence is MediaTekBackendEvidence.Confirmed
        }
        return if (allConfirmed) MediaTekNpuAttribution.CONFIRMED else MediaTekNpuAttribution.UNVERIFIED
    }
}

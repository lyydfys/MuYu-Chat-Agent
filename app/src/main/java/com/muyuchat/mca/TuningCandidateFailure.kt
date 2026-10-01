package com.muyuchat.mca

import com.muyuchat.core.tuning.CandidateHardGate
import com.muyuchat.core.tuning.CandidateScore

/**
 * Classifies why an adaptive-tuning candidate was rejected.
 *
 * The result travels into the persisted measurement metrics and into what the user is told,
 * so the precedence below is the contract: a worker that hit a native fatal signal is
 * reported as such rather than as a generic rejection, and a candidate that never reached
 * the gates is not blamed on signatures. Reordering these branches would silently
 * reclassify real failures without failing any build.
 *
 * The ordering is deliberately specific-to-general:
 *
 *  1. an eligible candidate has no failure code
 *  2. process-level failures: native fatal signal, crash, timeout
 *  3. a runtime error reported by the run itself
 *  4. correctness gates, then safety, then memory, then signature, then the catch-all
 *
 * Extracted verbatim from `MainViewModel.candidateMeasurement` during P2; no branch was
 * reordered and no code text changed.
 */
internal fun tuningCandidateFailureCode(
    gate: CandidateHardGate,
    score: CandidateScore,
    lastError: String?
): String? = when {
    score.eligible -> null
    gate.nativeFatalSignalCount > 0 -> "NATIVE_FATAL_SIGNAL"
    gate.crashCount > 0 -> "WORKER_CRASH"
    gate.anrCount > 0 -> "WORKER_TIMEOUT"
    lastError != null -> "RUNTIME_ERROR"
    !gate.correctnessPassed || !gate.templateValid || !gate.outputVisible -> "CORRECTNESS_GATE_FAILED"
    !gate.safetyPassed -> "SAFETY_GATE_FAILED"
    gate.lowMemoryTriggered -> "LOW_MEMORY"
    !gate.signaturesMatch -> "SIGNATURE_MISMATCH"
    else -> "CANDIDATE_REJECTED"
}

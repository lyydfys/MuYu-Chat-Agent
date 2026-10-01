package com.muyuchat.mca

import com.muyuchat.core.tuning.CandidateHardGate
import com.muyuchat.core.tuning.CandidateScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins how a rejected adaptive-tuning candidate is classified, and proves the P2 extraction
 * changed nothing.
 *
 * The code reaches persisted metrics and user-facing messages, so the precedence order is a
 * contract, not an implementation detail. It had no test: reordering the branches, or
 * dropping one, would have reclassified real failures silently.
 *
 * The sweep evaluates the extracted function and a verbatim copy of the original `when`
 * over every combination of gate booleans, process-failure counters and eligibility.
 */
class TuningCandidateFailureTest {

    @Test
    fun `an eligible candidate has no failure code`() {
        assertNull(tuningCandidateFailureCode(passingGate(), score(eligible = true), lastError = null))
        assertNull(
            "eligibility wins even when gate flags look wrong",
            tuningCandidateFailureCode(passingGate(crashCount = 3), score(eligible = true), lastError = "boom")
        )
    }

    @Test
    fun `process level failures outrank everything else`() {
        // All three counters set at once: the native signal is reported, not the crash.
        assertEquals(
            "NATIVE_FATAL_SIGNAL",
            tuningCandidateFailureCode(
                passingGate(crashCount = 1, anrCount = 1, nativeFatalSignalCount = 1),
                score(eligible = false),
                lastError = "boom"
            )
        )
        assertEquals(
            "WORKER_CRASH",
            tuningCandidateFailureCode(
                passingGate(crashCount = 1, anrCount = 1),
                score(eligible = false),
                lastError = "boom"
            )
        )
        assertEquals(
            "WORKER_TIMEOUT",
            tuningCandidateFailureCode(passingGate(anrCount = 1), score(eligible = false), lastError = "boom")
        )
    }

    @Test
    fun `a reported runtime error outranks the gate failures`() {
        assertEquals(
            "RUNTIME_ERROR",
            tuningCandidateFailureCode(
                passingGate(correctnessPassed = false, safetyPassed = false),
                score(eligible = false),
                lastError = "native returned 1"
            )
        )
    }

    @Test
    fun `correctness gates cover the three correctness flags together`() {
        listOf(
            passingGate(correctnessPassed = false),
            passingGate(templateValid = false),
            passingGate(outputVisible = false)
        ).forEach { gate ->
            assertEquals(
                "CORRECTNESS_GATE_FAILED",
                tuningCandidateFailureCode(gate, score(eligible = false), lastError = null)
            )
        }
    }

    @Test
    fun `the remaining branches keep their order`() {
        assertEquals(
            "SAFETY_GATE_FAILED",
            tuningCandidateFailureCode(passingGate(safetyPassed = false), score(eligible = false), null)
        )
        assertEquals(
            "LOW_MEMORY",
            tuningCandidateFailureCode(passingGate(lowMemoryTriggered = true), score(eligible = false), null)
        )
        assertEquals(
            "SIGNATURE_MISMATCH",
            tuningCandidateFailureCode(passingGate(signaturesMatch = false), score(eligible = false), null)
        )
    }

    @Test
    fun `a gate that fails only through its derived property falls through to the catch-all`() {
        // Every explicit flag is healthy, so no specific branch matches. CandidateHardGate.passed
        // is derived from these flags, so this combination cannot actually occur -- the branch
        // exists to keep the mapping total.
        assertEquals(
            "CANDIDATE_REJECTED",
            tuningCandidateFailureCode(passingGate(), score(eligible = false), lastError = null)
        )
    }

    @Test
    fun `the extraction matches the original mapping on every combination`() {
        val flags = listOf(false, true)
        val counters = listOf(0, 1)
        var compared = 0

        flags.forEach { correctnessPassed ->
            flags.forEach { lowMemoryTriggered ->
                flags.forEach { outputVisible ->
                    flags.forEach { templateValid ->
                        flags.forEach { safetyPassed ->
                            flags.forEach { signaturesMatch ->
                                counters.forEach { crashCount ->
                                    counters.forEach { anrCount ->
                                        counters.forEach { nativeFatalSignalCount ->
                                            flags.forEach { eligible ->
                                                val gate = CandidateHardGate(
                                                    correctnessPassed = correctnessPassed,
                                                    crashCount = crashCount,
                                                    anrCount = anrCount,
                                                    nativeFatalSignalCount = nativeFatalSignalCount,
                                                    lowMemoryTriggered = lowMemoryTriggered,
                                                    outputVisible = outputVisible,
                                                    templateValid = templateValid,
                                                    safetyPassed = safetyPassed,
                                                    signaturesMatch = signaturesMatch
                                                )
                                                val candidateScore = score(eligible)
                                                listOf(null, "boom").forEach { lastError ->
                                                    assertEquals(
                                                        "classification diverged for " +
                                                            "gate=$gate eligible=$eligible lastError=$lastError",
                                                        originalFailureCode(gate, candidateScore, lastError),
                                                        tuningCandidateFailureCode(gate, candidateScore, lastError)
                                                    )
                                                    compared++
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        assertEquals("the sweep must cover the whole space", 2048, compared)
    }

    @Test
    fun `only the documented codes are ever produced`() {
        val allowed = setOf(
            "NATIVE_FATAL_SIGNAL", "WORKER_CRASH", "WORKER_TIMEOUT", "RUNTIME_ERROR",
            "CORRECTNESS_GATE_FAILED", "SAFETY_GATE_FAILED", "LOW_MEMORY",
            "SIGNATURE_MISMATCH", "CANDIDATE_REJECTED"
        )
        val produced = mutableSetOf<String>()

        listOf(false, true).forEach { correctness ->
            listOf(false, true).forEach { safety ->
                listOf(false, true).forEach { lowMemory ->
                    listOf(false, true).forEach { signature ->
                        listOf(0, 1).forEach { crash ->
                            listOf(0, 1).forEach { anr ->
                                listOf(0, 1).forEach { native ->
                                    val gate = CandidateHardGate(
                                        correctnessPassed = correctness,
                                        crashCount = crash,
                                        anrCount = anr,
                                        nativeFatalSignalCount = native,
                                        lowMemoryTriggered = lowMemory,
                                        outputVisible = true,
                                        templateValid = true,
                                        safetyPassed = safety,
                                        signaturesMatch = signature
                                    )
                                    listOf(null, "boom").forEach { lastError ->
                                        tuningCandidateFailureCode(gate, score(eligible = false), lastError)
                                            ?.let { produced += it }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        assertEquals(emptySet<String>(), produced - allowed)
        assertEquals(
            "the sweep must reach every documented code",
            allowed,
            produced
        )
    }

    private fun passingGate(
        correctnessPassed: Boolean = true,
        crashCount: Int = 0,
        anrCount: Int = 0,
        nativeFatalSignalCount: Int = 0,
        lowMemoryTriggered: Boolean = false,
        outputVisible: Boolean = true,
        templateValid: Boolean = true,
        safetyPassed: Boolean = true,
        signaturesMatch: Boolean = true
    ) = CandidateHardGate(
        correctnessPassed = correctnessPassed,
        crashCount = crashCount,
        anrCount = anrCount,
        nativeFatalSignalCount = nativeFatalSignalCount,
        lowMemoryTriggered = lowMemoryTriggered,
        outputVisible = outputVisible,
        templateValid = templateValid,
        safetyPassed = safetyPassed,
        signaturesMatch = signaturesMatch
    )

    private fun score(eligible: Boolean) = CandidateScore(
        eligible = eligible,
        value = if (eligible) 1.0 else null,
        medianDecodeTps = 0.0,
        medianTtftMs = 0.0,
        decodeVariance = 0.0
    )

    /** Verbatim copy of the `when` that lived in `MainViewModel.candidateMeasurement`. */
    private fun originalFailureCode(
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
}

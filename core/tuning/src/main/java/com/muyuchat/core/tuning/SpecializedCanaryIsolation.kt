package com.muyuchat.core.tuning

enum class CandidateExecutionEnvironment {
    CALLER_PROCESS,
    ISOLATED_PROCESS
}

/** Enforces the planner's process boundary before the native load is attempted. */
object CandidateIsolationPolicy {
    fun assess(
        plan: TuningCandidateCanaryPlan,
        environment: CandidateExecutionEnvironment
    ): SpecializedCanaryAssessment {
        val violations = buildList {
            when (plan.processBoundary) {
                CandidateProcessBoundary.REJECT_IDENTITY_MISMATCH -> add(
                    SpecializedCanaryViolation(
                        "identity_mismatch",
                        "Candidate and committed profile belong to different runtime identities"
                    )
                )
                CandidateProcessBoundary.ISOLATED_PROCESS_REQUIRED -> {
                    if (environment != CandidateExecutionEnvironment.ISOLATED_PROCESS) {
                        add(
                            SpecializedCanaryViolation(
                                "isolated_process_required",
                                "Load-bound candidates must execute in a disposable isolated process"
                            )
                        )
                    }
                }
                CandidateProcessBoundary.CALLER_PROCESS_ALLOWED -> Unit
            }
        }
        return SpecializedCanaryAssessment(violations)
    }
}

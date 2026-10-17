package com.muyuchat.core.tuning

data class LongContextNeedleCanarySpec(
    val requestedContextTokens: Int,
    val minimumPromptTokens: Int,
    val marker: String,
    val expectedOutput: String,
    val maximumOutputTokens: Int = 16
) {
    init {
        require(requestedContextTokens > 0)
        require(minimumPromptTokens > 0)
        require(maximumOutputTokens > 0)
        require(minimumPromptTokens + maximumOutputTokens < requestedContextTokens) {
            "Long-context canary must leave native context headroom"
        }
        require(marker.isNotBlank())
        require(expectedOutput.isNotBlank())
    }

    /** The runner fills both placeholders to a tokenizer-measured target size. */
    fun prompt(fillerBefore: String, fillerAfter: String): String = buildString {
        require("NEEDLE=" !in fillerBefore && "NEEDLE=" !in fillerAfter) {
            "Filler must not contain another needle"
        }
        require(marker !in fillerBefore && marker !in fillerAfter) {
            "Filler must not contain the canary marker"
        }
        append("Inside the filler there is exactly one line beginning with NEEDLE=. ")
        append("At the end, return that complete line exactly and nothing else.\nBEGIN_FILLER\n")
        append(fillerBefore)
        append("\nNEEDLE=")
        append(marker)
        append("\n")
        append(fillerAfter)
        append("\nEND_FILLER\nQuestion: What was NEEDLE?")
    }
}

data class LongContextNeedleEvidence(
    val requestId: String,
    val generationSequenceBefore: Long,
    val generationSequenceAfter: Long,
    val effectiveContextTokens: Int,
    val promptTokens: Int,
    val needleTokenIndex: Int,
    val completionTokens: Int,
    val contextShifts: Int,
    val output: String,
    val nativeError: String? = null
)

object LongContextNeedleCanaryPolicy {
    private const val CONTEXT_HEADROOM_TOKENS = 64
    private const val OUTPUT_TOKENS = 16

    fun specFor(requestedContextTokens: Int): LongContextNeedleCanarySpec {
        require(requestedContextTokens > CONTEXT_HEADROOM_TOKENS + OUTPUT_TOKENS)
        val maximumPrompt = requestedContextTokens - CONTEXT_HEADROOM_TOKENS - OUTPUT_TOKENS
        val depthTarget = (requestedContextTokens * 3L / 4L).toInt()
        val beyondLegacyWindow = if (requestedContextTokens > 4096) 4097 else 1
        val minimumPrompt = minOf(maximumPrompt, maxOf(depthTarget, beyondLegacyWindow))
        val marker = "MCA_CTX_NEEDLE_${requestedContextTokens}_17"
        return LongContextNeedleCanarySpec(
            requestedContextTokens = requestedContextTokens,
            minimumPromptTokens = minimumPrompt,
            marker = marker,
            expectedOutput = "NEEDLE=$marker",
            maximumOutputTokens = OUTPUT_TOKENS
        )
    }

    fun assess(
        spec: LongContextNeedleCanarySpec,
        evidence: LongContextNeedleEvidence
    ): SpecializedCanaryAssessment {
        val violations = buildList {
            if (evidence.requestId.isBlank()) {
                add(SpecializedCanaryViolation("request_id_missing", "Long-context canary needs a request id"))
            }
            if (evidence.generationSequenceAfter <= evidence.generationSequenceBefore) {
                add(
                    SpecializedCanaryViolation(
                        "generation_not_observed",
                        "Native generation sequence did not advance for the long-context canary"
                    )
                )
            }
            if (!evidence.nativeError.isNullOrBlank()) {
                add(SpecializedCanaryViolation("native_error", evidence.nativeError))
            }
            if (evidence.effectiveContextTokens < spec.requestedContextTokens) {
                add(
                    SpecializedCanaryViolation(
                        "effective_context_too_small",
                        "Native context ${evidence.effectiveContextTokens} is below requested ${spec.requestedContextTokens}"
                    )
                )
            }
            if (evidence.promptTokens < spec.minimumPromptTokens) {
                add(
                    SpecializedCanaryViolation(
                        "prompt_not_long_enough",
                        "Prompt used ${evidence.promptTokens} tokens; ${spec.minimumPromptTokens} are required"
                    )
                )
            }
            val minimumNeedleIndex = evidence.promptTokens / 4
            val maximumNeedleIndex = evidence.promptTokens * 3 / 4
            if (evidence.needleTokenIndex !in minimumNeedleIndex..maximumNeedleIndex) {
                add(
                    SpecializedCanaryViolation(
                        "needle_not_deep",
                        "Needle must be tokenizer-measured inside the middle half of the long prompt"
                    )
                )
            }
            if (evidence.promptTokens + evidence.completionTokens >= evidence.effectiveContextTokens) {
                add(
                    SpecializedCanaryViolation(
                        "context_headroom_exhausted",
                        "Canary exhausted the effective context instead of testing stable recall"
                    )
                )
            }
            if (evidence.completionTokens <= 0) {
                add(SpecializedCanaryViolation("empty_completion", "Long-context canary produced no tokens"))
            }
            if (evidence.contextShifts != 0) {
                add(
                    SpecializedCanaryViolation(
                        "unexpected_context_shift",
                        "Needle recall must pass without silently shifting away the tested prompt"
                    )
                )
            }
            if (!matches(spec, evidence.output)) {
                add(
                    SpecializedCanaryViolation(
                        "needle_recall_failed",
                        "Output did not exactly recall the long-context marker"
                    )
                )
            }
        }
        return SpecializedCanaryAssessment(violations)
    }

    fun matches(spec: LongContextNeedleCanarySpec, output: String): Boolean {
        if (output.isEmpty() || '\uFFFD' in output) return false
        val normalized = output
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .removeSuffix("\n")
        return normalized == spec.expectedOutput
    }
}

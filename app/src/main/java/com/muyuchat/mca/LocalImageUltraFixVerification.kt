package com.muyuchat.mca

import java.security.MessageDigest
import org.json.JSONObject

/**
 * Production verification of the UltraFix execution evidence published by stable-diffusion.cpp and
 * by the QNN split-SDXL phases. Not a test fixture: both entry points are called from the provider's
 * native publication path, and a failure here is what stops an unbound or conflicting tile plan,
 * quality schedule, noise-injection record, or output commit from being published as an image.
 *
 * Moved out of `LocalImageProvider.kt` verbatim: `verifyStableDiffusionUltraFixEvidence`,
 * `verifyQnnUltraFixEvidence`, and the two evidence-version constants they own. Every check, every
 * message, and the order of the checks is unchanged. These functions only read the worker result JSON
 * and the files native published; they hold no state and never touch `LocalImageProvider`.
 *
 * The output PNG byte bounds they enforce live in `LocalImageProviderOutputEvidence.kt`, which is the
 * single owner of the runtime output byte caps.
 */
internal const val STABLE_DIFFUSION_ULTRAFIX_EVIDENCE_VERSION = 5L
internal const val QNN_ULTRAFIX_EVIDENCE_VERSION = 2L

/**
 * Treats UltraFix as a distinct native algorithm, not as an img2img label. The bridge already
 * rejects incomplete native evidence; this second boundary binds that evidence to the immutable
 * Android request and to the exact PNG bytes that leave the isolated worker.
 */
internal fun verifyStableDiffusionUltraFixEvidence(
    result: JSONObject,
    request: LocalImageUltraFixOptions?,
    inputImage: LocalImagePreparedInput?,
    requestedUseCfg: Boolean,
    outputs: List<LocalImageOutput>
) {
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: error("stable-diffusion.cpp result is missing nativeEffective evidence.")
    val ultraFixOnlyFields = listOf(
        "ultraFix",
        "strengthMechanism",
        "outputSha256",
        "outputSizeBytes",
        "outputAtomicCommit"
    )
    if (request == null) {
        ultraFixOnlyFields.forEach { field ->
            require(!result.has(field) && !nativeEffective.has(field)) {
                "stable-diffusion.cpp reported UltraFix-only evidence for a regular request."
            }
        }
        return
    }

    val source = requireNotNull(inputImage) { "UltraFix evidence requires its prepared source." }
    require(outputs.size == 1) { "UltraFix must publish exactly one committed output." }
    val context = parseAndBindSdUltraFixEvidence(result, nativeEffective, request, source)
    verifySdUltraFixStagesAndCommit(result, nativeEffective, request, requestedUseCfg, outputs, context.outer, context.outerVaeTiling)
}

/** Independent QNN UltraFix v2 verifier for both shared and isolated phase topologies. */
internal fun verifyQnnUltraFixEvidence(
    result: JSONObject,
    request: LocalImageUltraFixOptions?,
    inputImage: LocalImagePreparedInput?,
    requestedUseCfg: Boolean,
    outputBytes: ByteArray,
    outputMimeType: String
) {
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: error("QNN result is missing nativeEffective evidence.")
    if (request == null) {
        require(!result.has("ultraFix") && !nativeEffective.has("ultraFix") &&
            !result.has("strengthMechanism") && !nativeEffective.has("strengthMechanism")
        ) { "QNN reported UltraFix-only evidence for a regular request." }
        return
    }
    verifyQnnUltraFixEvidenceBody(result, nativeEffective, request, inputImage, requestedUseCfg, outputBytes, outputMimeType)
}

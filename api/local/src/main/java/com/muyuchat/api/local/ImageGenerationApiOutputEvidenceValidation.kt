package com.muyuchat.api.local

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
internal object ImageGenerationApiOutputEvidenceValidation {

    private val ULTRAFIX_STAGE_EVIDENCE_FIELDS = setOf(
        "invocationCount", "successCount", "tileInvocationCount", "tileSuccessCount", "stepCount"
    )

    private val ULTRAFIX_EXECUTION_EVIDENCE_FIELDS = setOf(
        "version",
        "generationCompleted",
        "cancelled",
        "previewPublished",
        "sourceWidth",
        "sourceHeight",
        "targetWidth",
        "targetHeight",
        "sourceFit",
        "sourceResizedWidth",
        "sourceResizedHeight",
        "sourceCropLeft",
        "sourceCropTop",
        "tileSize",
        "overlap",
        "inversionSteps",
        "refinementSteps",
        "denoiseStepCount",
        "sampleMethod",
        "nativeScheduler",
        "vaeEncode",
        "ddimInversion",
        "tiledUnetRefinement",
        "tiledVaeDecode",
        "physicalDiffusionModelComputeCount",
        "qualityStepEvaluationCount",
        "noiseInjectionStepCount",
        "noiseInjectionSeedFingerprint",
        "noiseInjectionChecksum",
        "structureGuidanceStepCount",
        "structureGuidanceChecksum",
        "trajectoryNoiseChecksum"
    )

    private val RESPONSE_OUTPUT_EVIDENCE_FIELDS = setOf(
        "index", "mimeType", "sizeBytes", "sha256"
    )

    private val RESPONSE_IMAGE_DATA_FIELDS = setOf(
        "index", "b64_json", "mime_type", "width", "height", "seed"
    )

    private const val MAX_IMAGE_OUTPUT_PNG_BYTES = 256L * 1024L * 1024L

    private const val ULTRAFIX_BASE_EXECUTION_EVIDENCE_VERSION = 5L

    private const val ULTRAFIX_EXTENDED_EXECUTION_EVIDENCE_VERSION = 2L

    private const val QNN_ULTRAFIX_EXECUTION_EVIDENCE_VERSION = 2L

    private const val MIN_ULTRAFIX_OUTPUT_PNG_BYTES = 57L

    private const val MAX_ULTRAFIX_OUTPUT_PNG_BYTES = 256L * 1024L * 1024L

    private val UINT64_HEX_PATTERN = Regex("[a-f0-9]{16}")

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )

    internal fun validateQwenImage21ImageData(
        data: JSONArray,
        execution: JSONObject,
        nativeEffective: JSONObject,
        expectedCount: Int
    ) {
        if (expectedCount != 1) {
            reject("invalid_image_provider_response", "Qwen-Image-2.1 schema v1 must return one image.")
        }
        validateImageData(
            data = data,
            execution = execution,
            nativeEffective = nativeEffective,
            expectedCount = 1,
            requireSeedEvidence = true
        )
        val item = data.optJSONObject(0)
            ?: reject("invalid_image_provider_response", "Qwen image data item must be an object.")
        val responseOutput = execution.optJSONArray("responseOutputEvidence")
            ?.optJSONObject(0)
            ?: reject("invalid_image_provider_response", "Qwen output byte evidence is missing.")
        val sha256 = item.imageGenerationApiRequiredNonBlankString("b64_json").let { encoded ->
            val bytes = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                reject("invalid_image_provider_response", "Qwen image data Base64 is malformed.")
            }
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        val sizeBytes = responseOutput.imageGenerationApiRequiredPositiveLong("sizeBytes")
        if (execution.imageGenerationApiRequiredNonBlankString("outputSha256") != sha256 ||
            nativeEffective.imageGenerationApiRequiredNonBlankString("outputSha256") != sha256 ||
            execution.imageGenerationApiRequiredPositiveLong("outputBytes") != sizeBytes ||
            nativeEffective.imageGenerationApiRequiredPositiveLong("outputBytes") != sizeBytes
        ) {
            reject(
                "invalid_image_execution_evidence",
                "Qwen output hash/size evidence does not match the returned PNG bytes."
            )
        }
    }

    internal fun validateRequestedUltraFixEvidence(
        request: ImageGenerationApiRequest,
        execution: JSONObject,
        data: JSONArray
    ) {
        val requested = request.ultraFix ?: return
        when (execution.imageGenerationApiRequiredNonBlankString("runtime")) {
            "QNN_HTP" -> {
                validateQnnUltraFixEvidence(request, execution, data)
                return
            }
            "STABLE_DIFFUSION_CPP" -> Unit
            else -> reject(
                "invalid_ultrafix_execution_evidence",
                "UltraFix is only supported by the stable v5 or QNN v2 execution schemas."
            )
        }
        val nativeEffective = execution.getJSONObject("nativeEffective")

        fun invalid(message: String): Nothing =
            reject("invalid_ultrafix_execution_evidence", message)

        if (request.taskMode != ImageGenerationApiTaskMode.IMG2IMG ||
            request.imageCount != 1 || data.length() != 1
        ) {
            invalid("UltraFix execution must return exactly one img2img output.")
        }

        fun JSONObject.requireExactKeys(expected: Set<String>, layer: String) {
            if (keys().asSequence().toSet() != expected) {
                invalid("$layer fields do not match the strict UltraFix schema.")
            }
        }

        fun JSONObject.exactLong(field: String, layer: String): Long {
            if (!has(field) || isNull(field)) invalid("$layer is missing $field.")
            val raw = get(field)
            if (raw !is Byte && raw !is Short && raw !is Int && raw !is Long) {
                invalid("$layer $field must be an exact integer.")
            }
            return (raw as Number).toLong()
        }

        fun JSONObject.exactBoolean(field: String, layer: String): Boolean {
            if (!has(field) || isNull(field) || get(field) !is Boolean) {
                invalid("$layer $field must be boolean.")
            }
            return getBoolean(field)
        }

        fun JSONObject.exactString(field: String, layer: String): String {
            if (!has(field) || isNull(field) || get(field) !is String) {
                invalid("$layer $field must be a string.")
            }
            return getString(field)
        }

        fun JSONObject.exactDouble(field: String, layer: String): Double {
            if (!has(field) || isNull(field) || get(field) !is Number) {
                invalid("$layer $field must be numeric.")
            }
            val value = (get(field) as Number).toDouble()
            if (!value.isFinite()) invalid("$layer $field must be finite.")
            return value
        }

        data class StageEvidence(
            val invocationCount: Long,
            val successCount: Long,
            val tileInvocationCount: Long,
            val tileSuccessCount: Long,
            val stepCount: Long
        )

        data class ExtendedEvidence(
            val tileCount: Long,
            val tilePlanSha256: String,
            val encoderGraphExecutionCount: Long,
            val inversionPositiveGraphExecutionCount: Long,
            val refinementPositiveGraphExecutionCount: Long,
            val refinementNegativeGraphExecutionCount: Long,
            val decoderGraphExecutionCount: Long,
            val outputSha256: String,
            val outputBytes: Long,
            val outputAtomicCommit: Boolean
        )

        data class UltraFixEvidence(
            val version: Long,
            val generationCompleted: Boolean,
            val cancelled: Boolean,
            val previewPublished: Boolean,
            val sourceWidth: Long,
            val sourceHeight: Long,
            val targetWidth: Long,
            val targetHeight: Long,
            val sourceFit: String,
            val sourceResizedWidth: Long,
            val sourceResizedHeight: Long,
            val sourceCropLeft: Long,
            val sourceCropTop: Long,
            val tileSize: Long,
            val overlap: Double,
            val inversionSteps: Long,
            val refinementSteps: Long,
            val denoiseStepCount: Long,
            val sampleMethod: String,
            val nativeScheduler: String,
            val vaeEncode: StageEvidence,
            val ddimInversion: StageEvidence,
            val tiledUnetRefinement: StageEvidence,
            val tiledVaeDecode: StageEvidence,
            val physicalDiffusionModelComputeCount: Long,
            val qualityStepEvaluationCount: Long,
            val noiseInjectionStepCount: Long,
            val noiseInjectionSeedFingerprint: String,
            val noiseInjectionChecksum: String,
            val structureGuidanceStepCount: Long,
            val structureGuidanceChecksum: String,
            val trajectoryNoiseChecksum: String,
            val extended: ExtendedEvidence?
        )

        fun parseStage(parent: JSONObject, field: String, layer: String): StageEvidence {
            val stage = parent.optJSONObject(field)
                ?: invalid("$layer is missing $field.")
            stage.requireExactKeys(ULTRAFIX_STAGE_EVIDENCE_FIELDS, "$layer.$field")
            return StageEvidence(
                invocationCount = stage.exactLong("invocationCount", "$layer.$field"),
                successCount = stage.exactLong("successCount", "$layer.$field"),
                tileInvocationCount = stage.exactLong("tileInvocationCount", "$layer.$field"),
                tileSuccessCount = stage.exactLong("tileSuccessCount", "$layer.$field"),
                stepCount = stage.exactLong("stepCount", "$layer.$field")
            )
        }

        fun parseEvidence(parent: JSONObject, layer: String): UltraFixEvidence {
            val evidence = parent.optJSONObject("ultraFix")
                ?: invalid("$layer UltraFix execution evidence is missing.")
            val evidenceKeys = evidence.keys().asSequence().toSet()
            if (evidenceKeys != ULTRAFIX_EXECUTION_EVIDENCE_FIELDS) {
                invalid("$layer.ultraFix fields do not match the strict stable v5 schema.")
            }
            val extended: ExtendedEvidence? = null
            return UltraFixEvidence(
                version = evidence.exactLong("version", "$layer.ultraFix"),
                generationCompleted = evidence.exactBoolean("generationCompleted", "$layer.ultraFix"),
                cancelled = evidence.exactBoolean("cancelled", "$layer.ultraFix"),
                previewPublished = evidence.exactBoolean("previewPublished", "$layer.ultraFix"),
                sourceWidth = evidence.exactLong("sourceWidth", "$layer.ultraFix"),
                sourceHeight = evidence.exactLong("sourceHeight", "$layer.ultraFix"),
                targetWidth = evidence.exactLong("targetWidth", "$layer.ultraFix"),
                targetHeight = evidence.exactLong("targetHeight", "$layer.ultraFix"),
                sourceFit = evidence.exactString("sourceFit", "$layer.ultraFix"),
                sourceResizedWidth = evidence.exactLong("sourceResizedWidth", "$layer.ultraFix"),
                sourceResizedHeight = evidence.exactLong("sourceResizedHeight", "$layer.ultraFix"),
                sourceCropLeft = evidence.exactLong("sourceCropLeft", "$layer.ultraFix"),
                sourceCropTop = evidence.exactLong("sourceCropTop", "$layer.ultraFix"),
                tileSize = evidence.exactLong("tileSize", "$layer.ultraFix"),
                overlap = evidence.exactDouble("overlap", "$layer.ultraFix"),
                inversionSteps = evidence.exactLong("inversionSteps", "$layer.ultraFix"),
                refinementSteps = evidence.exactLong("refinementSteps", "$layer.ultraFix"),
                denoiseStepCount = evidence.exactLong("denoiseStepCount", "$layer.ultraFix"),
                sampleMethod = evidence.exactString("sampleMethod", "$layer.ultraFix"),
                nativeScheduler = evidence.exactString("nativeScheduler", "$layer.ultraFix"),
                vaeEncode = parseStage(evidence, "vaeEncode", "$layer.ultraFix"),
                ddimInversion = parseStage(evidence, "ddimInversion", "$layer.ultraFix"),
                tiledUnetRefinement = parseStage(evidence, "tiledUnetRefinement", "$layer.ultraFix"),
                tiledVaeDecode = parseStage(evidence, "tiledVaeDecode", "$layer.ultraFix"),
                physicalDiffusionModelComputeCount = evidence.exactLong(
                    "physicalDiffusionModelComputeCount",
                    "$layer.ultraFix"
                ),
                qualityStepEvaluationCount = evidence.exactLong(
                    "qualityStepEvaluationCount",
                    "$layer.ultraFix"
                ),
                noiseInjectionStepCount = evidence.exactLong(
                    "noiseInjectionStepCount",
                    "$layer.ultraFix"
                ),
                noiseInjectionSeedFingerprint = evidence.exactString(
                    "noiseInjectionSeedFingerprint",
                    "$layer.ultraFix"
                ),
                noiseInjectionChecksum = evidence.exactString(
                    "noiseInjectionChecksum",
                    "$layer.ultraFix"
                ),
                structureGuidanceStepCount = evidence.exactLong(
                    "structureGuidanceStepCount",
                    "$layer.ultraFix"
                ),
                structureGuidanceChecksum = evidence.exactString(
                    "structureGuidanceChecksum",
                    "$layer.ultraFix"
                ),
                trajectoryNoiseChecksum = evidence.exactString(
                    "trajectoryNoiseChecksum",
                    "$layer.ultraFix"
                ),
                extended = extended
            )
        }

        val outer = parseEvidence(execution, "outer")
        val native = parseEvidence(nativeEffective, "nativeEffective")
        if (outer != native) {
            invalid("Outer and nativeEffective UltraFix evidence do not match.")
        }
        val outerStrengthMechanism = execution.exactString("strengthMechanism", "outer")
        val nativeStrengthMechanism = nativeEffective.exactString(
            "strengthMechanism",
            "nativeEffective"
        )
        if (outerStrengthMechanism != "ddim_inversion" ||
            nativeStrengthMechanism != outerStrengthMechanism
        ) {
            invalid("UltraFix execution did not prove the DDIM inversion strength mechanism.")
        }

        val expectedEvidenceVersion = if (outer.extended == null) {
            ULTRAFIX_BASE_EXECUTION_EVIDENCE_VERSION
        } else {
            ULTRAFIX_EXTENDED_EXECUTION_EVIDENCE_VERSION
        }
        if (outer.version != expectedEvidenceVersion ||
            !outer.generationCompleted || outer.cancelled || outer.previewPublished ||
            outer.sourceWidth <= 0L || outer.sourceHeight <= 0L ||
            outer.targetWidth != requested.targetWidth.toLong() ||
            outer.targetHeight != requested.targetHeight.toLong() ||
            outer.sourceFit != "cover_center" ||
            outer.sourceResizedWidth < outer.targetWidth ||
            outer.sourceResizedHeight < outer.targetHeight ||
            outer.sourceCropLeft != (outer.sourceResizedWidth - outer.targetWidth) / 2L ||
            outer.sourceCropTop != (outer.sourceResizedHeight - outer.targetHeight) / 2L ||
            outer.tileSize != requested.tileSize.toLong() ||
            !imageGenerationApiNumbersMatch(outer.overlap, requested.overlap) ||
            outer.inversionSteps != requested.inversionSteps.toLong() ||
            outer.refinementSteps != requested.refinementSteps.toLong() ||
            outer.denoiseStepCount != requested.inversionSteps.toLong() ||
            outer.sampleMethod.isBlank() || outer.nativeScheduler.isBlank() ||
            !imageGenerationApiNumbersMatch(execution.exactDouble("strength", "outer"), requested.strength) ||
            !imageGenerationApiNumbersMatch(
                nativeEffective.exactDouble("strength", "nativeEffective"),
                requested.strength
            )
        ) {
            invalid("UltraFix evidence does not match the immutable API request.")
        }

        fun requireStage(
            name: String,
            stage: StageEvidence,
            invocations: Long,
            steps: Long
        ) {
            if (stage.invocationCount != invocations || stage.successCount != invocations ||
                stage.stepCount != steps || stage.tileInvocationCount <= 0L ||
                stage.tileSuccessCount != stage.tileInvocationCount
            ) {
                invalid("$name UltraFix stage evidence is incomplete.")
            }
        }

        val inversionSteps = requested.inversionSteps.toLong()
        requireStage("VAE encode", outer.vaeEncode, 1L, 1L)
        requireStage("DDIM inversion", outer.ddimInversion, inversionSteps, inversionSteps)
        requireStage(
            "tiled UNet refinement",
            outer.tiledUnetRefinement,
            inversionSteps,
            inversionSteps
        )
        requireStage("tiled VAE decode", outer.tiledVaeDecode, 1L, 1L)
        if (outer.ddimInversion.tileInvocationCount % inversionSteps != 0L ||
            outer.tiledUnetRefinement.tileInvocationCount % inversionSteps != 0L
        ) {
            invalid("UltraFix tile evidence is not divisible by the completed timestep count.")
        }
        if (outer.ddimInversion.tileInvocationCount / inversionSteps !=
            outer.tiledUnetRefinement.tileInvocationCount / inversionSteps
        ) {
            invalid("UltraFix DDIM inversion and refinement tile plans do not match.")
        }

        val (expectedPositive, expectedNegative, expectedPhysical) = try {
            val extended = outer.extended
            if (extended == null) {
                val positive = Math.addExact(
                    outer.ddimInversion.tileInvocationCount,
                    outer.tiledUnetRefinement.tileInvocationCount
                )
                val negative = if (nativeEffective.imageGenerationApiRequiredBoolean("useCfg")) {
                    outer.tiledUnetRefinement.tileInvocationCount
                } else {
                    0L
                }
                Triple(positive, negative, Math.addExact(positive, negative))
            } else {
                if (!ImageGenerationApiContract.SHA256_PATTERN.matches(extended.tilePlanSha256) ||
                    extended.tileCount <= 0L ||
                    extended.encoderGraphExecutionCount != outer.vaeEncode.tileInvocationCount ||
                    extended.encoderGraphExecutionCount != outer.vaeEncode.tileSuccessCount ||
                    extended.inversionPositiveGraphExecutionCount !=
                        outer.ddimInversion.tileInvocationCount ||
                    Math.addExact(
                        extended.refinementPositiveGraphExecutionCount,
                        extended.refinementNegativeGraphExecutionCount
                    ) != outer.tiledUnetRefinement.tileInvocationCount ||
                    extended.decoderGraphExecutionCount !=
                        outer.tiledVaeDecode.tileInvocationCount ||
                    extended.decoderGraphExecutionCount !=
                        outer.tiledVaeDecode.tileSuccessCount ||
                    extended.encoderGraphExecutionCount != extended.tileCount ||
                    extended.inversionPositiveGraphExecutionCount !=
                        Math.multiplyExact(extended.tileCount, inversionSteps) ||
                    extended.refinementPositiveGraphExecutionCount !=
                        Math.multiplyExact(extended.tileCount, inversionSteps) ||
                    extended.decoderGraphExecutionCount != extended.tileCount ||
                    (nativeEffective.imageGenerationApiRequiredBoolean("useCfg") &&
                        extended.refinementNegativeGraphExecutionCount !=
                        Math.multiplyExact(extended.tileCount, inversionSteps)) ||
                    (!nativeEffective.imageGenerationApiRequiredBoolean("useCfg") &&
                        extended.refinementNegativeGraphExecutionCount != 0L)
                ) {
                    invalid("Extended UltraFix tile-plan and graph counters are inconsistent.")
                }
                val positive = Math.addExact(
                    extended.inversionPositiveGraphExecutionCount,
                    extended.refinementPositiveGraphExecutionCount
                )
                val negative = extended.refinementNegativeGraphExecutionCount
                Triple(positive, negative, Math.addExact(positive, negative))
            }
        } catch (_: ArithmeticException) {
            invalid("UltraFix physical execution evidence overflowed.")
        }
        val nativeCounterEvidenceMatches = outer.extended != null ||
            (nativeEffective.exactLong(
                "positiveDiffusionModelComputeCount",
                "nativeEffective"
            ) == expectedPositive &&
                nativeEffective.exactLong(
                    "negativeDiffusionModelComputeCount",
                    "nativeEffective"
                ) == expectedNegative &&
                nativeEffective.exactLong(
                    "auxiliaryDiffusionModelComputeCount",
                    "nativeEffective"
                ) == 0L &&
                nativeEffective.exactLong("samplingPassCount", "nativeEffective") == 1L &&
                nativeEffective.exactLong(
                    "totalUnetExecutionCount",
                    "nativeEffective"
                ) == expectedPhysical)
        if (outer.physicalDiffusionModelComputeCount != expectedPhysical ||
            execution.exactLong("actualDiffusionModelComputeCount", "outer") != expectedPhysical ||
            execution.exactLong("actualPositiveDiffusionModelComputeCount", "outer") != expectedPositive ||
            execution.exactLong("actualNegativeDiffusionModelComputeCount", "outer") != expectedNegative ||
            execution.exactLong("actualAuxiliaryDiffusionModelComputeCount", "outer") != 0L ||
            execution.exactLong("actualSamplingStepCount", "outer") != inversionSteps ||
            execution.exactLong("actualSamplingPassCount", "outer") != 1L ||
            execution.exactLong("totalUnetExecutionCount", "outer") != expectedPhysical ||
            !nativeCounterEvidenceMatches
        ) {
            invalid("UltraFix physical execution evidence is incomplete or misclassified.")
        }

        val qualityStepCount = Math.max(inversionSteps - 1L, 0L)
        val seed = execution.exactLong("seed", "outer")
        val zeroQualityChecksum = "0000000000000000"
        val noiseEvidenceConsistent =
            (outer.noiseInjectionStepCount == 0L) ==
                (outer.noiseInjectionChecksum == zeroQualityChecksum)
        val structureEvidenceConsistent =
            (outer.structureGuidanceStepCount == 0L) ==
                (outer.structureGuidanceChecksum == zeroQualityChecksum)
        val qualityActionCount = runCatching {
            Math.addExact(outer.noiseInjectionStepCount, outer.structureGuidanceStepCount)
        }.getOrDefault(-1L)
        if (seed < 0L || nativeEffective.exactLong("seed", "nativeEffective") != seed ||
            outer.qualityStepEvaluationCount != qualityStepCount ||
            outer.noiseInjectionStepCount !in 0L..qualityStepCount ||
            outer.structureGuidanceStepCount !in 0L..qualityStepCount ||
            outer.noiseInjectionSeedFingerprint !=
                ultrafixNoiseSeedFingerprint(seed, inversionSteps) ||
            !ImageGenerationApiContract.SHA256_PATTERN.matches(outer.noiseInjectionSeedFingerprint) ||
            !UINT64_HEX_PATTERN.matches(outer.noiseInjectionChecksum) ||
            !UINT64_HEX_PATTERN.matches(outer.structureGuidanceChecksum) ||
            !UINT64_HEX_PATTERN.matches(outer.trajectoryNoiseChecksum) ||
            if (qualityStepCount == 0L) {
                outer.noiseInjectionStepCount != 0L ||
                    outer.structureGuidanceStepCount != 0L ||
                    outer.noiseInjectionChecksum != zeroQualityChecksum ||
                    outer.structureGuidanceChecksum != zeroQualityChecksum ||
                    outer.trajectoryNoiseChecksum != zeroQualityChecksum
            } else {
                !noiseEvidenceConsistent || !structureEvidenceConsistent ||
                    qualityActionCount < qualityStepCount ||
                    outer.trajectoryNoiseChecksum == zeroQualityChecksum
            }
        ) {
            invalid("UltraFix quality, seed, or checksum evidence is invalid.")
        }

        data class CommitEvidence(val sha256: String, val sizeBytes: Long, val atomic: Boolean)

        fun parseCommit(parent: JSONObject, layer: String): CommitEvidence {
            val sha256 = parent.exactString("outputSha256", layer)
            if (!ImageGenerationApiContract.SHA256_PATTERN.matches(sha256)) invalid("$layer output SHA-256 is malformed.")
            return CommitEvidence(
                sha256 = sha256,
                sizeBytes = parent.exactLong("outputSizeBytes", layer),
                atomic = parent.exactBoolean("outputAtomicCommit", layer)
            )
        }

        val outerCommit = parseCommit(execution, "outer")
        val nativeCommit = parseCommit(nativeEffective, "nativeEffective")
        if (outerCommit != nativeCommit || !outerCommit.atomic ||
            outerCommit.sizeBytes !in MIN_ULTRAFIX_OUTPUT_PNG_BYTES..MAX_ULTRAFIX_OUTPUT_PNG_BYTES
        ) {
            invalid("UltraFix output commit evidence is incomplete or conflicts across layers.")
        }
        outer.extended?.let { extended ->
            if (!ImageGenerationApiContract.SHA256_PATTERN.matches(extended.outputSha256) ||
                extended.outputSha256 != outerCommit.sha256 ||
                extended.outputBytes != outerCommit.sizeBytes ||
                !extended.outputAtomicCommit
            ) {
                invalid("Extended UltraFix output evidence conflicts with the committed output.")
            }
        }

        val item = data.optJSONObject(0)
            ?: invalid("UltraFix output data must contain one object.")
        val encoded = item.exactString("b64_json", "data[0]")
        val maxEncodedLength = ((MAX_ULTRAFIX_OUTPUT_PNG_BYTES + 2L) / 3L) * 4L
        if (encoded.isEmpty() || encoded.length.toLong() > maxEncodedLength ||
            encoded.length % 4 != 0
        ) {
            invalid("UltraFix output Base64 is malformed or exceeds the response limit.")
        }
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            invalid("UltraFix output Base64 is malformed.")
        }
        if (Base64.getEncoder().encodeToString(decoded) != encoded) {
            invalid("UltraFix output Base64 is not canonical.")
        }
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(decoded)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        if (decoded.size.toLong() != outerCommit.sizeBytes ||
            actualSha256 != outerCommit.sha256 ||
            item.exactString("mime_type", "data[0]") != "image/png" ||
            item.imageGenerationApiRequiredPositiveLong("width") != requested.targetWidth.toLong() ||
            item.imageGenerationApiRequiredPositiveLong("height") != requested.targetHeight.toLong() ||
            decoded.size < PNG_SIGNATURE.size ||
            !decoded.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
        ) {
            invalid("UltraFix output evidence does not bind the exact returned PNG bytes.")
        }
    }

    internal fun validateQnnUltraFixEvidence(
        request: ImageGenerationApiRequest,
        execution: JSONObject,
        data: JSONArray
    ) {
        val requested = request.ultraFix ?: return
        val nativeEffective = execution.getJSONObject("nativeEffective")
        fun invalid(message: String): Nothing =
            reject("invalid_ultrafix_execution_evidence", message)
        if (request.taskMode != ImageGenerationApiTaskMode.IMG2IMG ||
            request.imageCount != 1 || data.length() != 1
        ) invalid("QNN UltraFix execution must return exactly one img2img output.")

        fun JSONObject.requireExactKeys(expected: Set<String>, layer: String) {
            if (keys().asSequence().toSet() != expected) {
                invalid("$layer fields do not match the strict QNN UltraFix v2 schema.")
            }
        }
        fun JSONObject.exactLong(field: String, layer: String): Long {
            if (!has(field) || isNull(field)) invalid("$layer is missing $field.")
            val value = get(field)
            if (value !is Byte && value !is Short && value !is Int && value !is Long) {
                invalid("$layer $field must be an exact integer.")
            }
            return (value as Number).toLong()
        }
        fun JSONObject.exactBoolean(field: String, layer: String): Boolean {
            if (!has(field) || isNull(field) || get(field) !is Boolean) {
                invalid("$layer $field must be boolean.")
            }
            return getBoolean(field)
        }
        fun JSONObject.exactString(field: String, layer: String): String {
            if (!has(field) || isNull(field) || get(field) !is String) {
                invalid("$layer $field must be a string.")
            }
            return getString(field)
        }
        fun JSONObject.exactDouble(field: String, layer: String): Double {
            if (!has(field) || isNull(field) || get(field) !is Number) {
                invalid("$layer $field must be numeric.")
            }
            return (get(field) as Number).toDouble().also {
                if (!it.isFinite()) invalid("$layer $field must be finite.")
            }
        }
        data class Stage(
            val invocations: Long,
            val successes: Long,
            val tileInvocations: Long,
            val tileSuccesses: Long,
            val steps: Long
        )
        data class Evidence(
            val version: Long,
            val generationCompleted: Boolean,
            val cancelled: Boolean,
            val previewPublished: Boolean,
            val sourceWidth: Long,
            val sourceHeight: Long,
            val targetWidth: Long,
            val targetHeight: Long,
            val sourceFit: String,
            val sourceResizedWidth: Long,
            val sourceResizedHeight: Long,
            val sourceCropLeft: Long,
            val sourceCropTop: Long,
            val tileSize: Long,
            val overlap: Double,
            val tileCount: Long,
            val tilePlanSha256: String,
            val inversionSteps: Long,
            val refinementSteps: Long,
            val denoiseStepCount: Long,
            val sampleMethod: String,
            val nativeScheduler: String,
            val vaeEncode: Stage,
            val ddimInversion: Stage,
            val tiledUnetRefinement: Stage,
            val tiledVaeDecode: Stage,
            val encoderGraphExecutionCount: Long,
            val inversionPositiveGraphExecutionCount: Long,
            val refinementPositiveGraphExecutionCount: Long,
            val refinementNegativeGraphExecutionCount: Long,
            val decoderGraphExecutionCount: Long,
            val physicalDiffusionModelComputeCount: Long,
            val qualityStepEvaluationCount: Long,
            val noiseInjectionStepCount: Long,
            val noiseInjectionSeedFingerprint: String,
            val noiseInjectionChecksum: String,
            val structureGuidanceStepCount: Long,
            val structureGuidanceChecksum: String,
            val trajectoryNoiseChecksum: String,
            val outputSha256: String,
            val outputBytes: Long,
            val outputAtomicCommit: Boolean
        )
        val stageFields = setOf(
            "invocationCount", "successCount", "tileInvocationCount", "tileSuccessCount", "stepCount"
        )
        val evidenceFields = setOf(
            "version", "generationCompleted", "cancelled", "previewPublished",
            "sourceWidth", "sourceHeight", "targetWidth", "targetHeight", "sourceFit",
            "sourceResizedWidth", "sourceResizedHeight", "sourceCropLeft", "sourceCropTop",
            "tileSize", "overlap", "tileCount", "tilePlanSha256", "inversionSteps",
            "refinementSteps", "denoiseStepCount", "sampleMethod", "nativeScheduler",
            "vaeEncode", "ddimInversion", "tiledUnetRefinement", "tiledVaeDecode",
            "encoderGraphExecutionCount", "inversionPositiveGraphExecutionCount",
            "refinementPositiveGraphExecutionCount", "refinementNegativeGraphExecutionCount",
            "decoderGraphExecutionCount", "physicalDiffusionModelComputeCount",
            "qualityStepEvaluationCount", "noiseInjectionStepCount",
            "noiseInjectionSeedFingerprint", "noiseInjectionChecksum",
            "structureGuidanceStepCount", "structureGuidanceChecksum", "trajectoryNoiseChecksum",
            "outputSha256", "outputBytes", "outputAtomicCommit"
        )
        fun parseStage(parent: JSONObject, field: String, layer: String): Stage {
            val stage = parent.optJSONObject(field) ?: invalid("$layer is missing $field.")
            stage.requireExactKeys(stageFields, "$layer.$field")
            return Stage(
                invocations = stage.exactLong("invocationCount", "$layer.$field"),
                successes = stage.exactLong("successCount", "$layer.$field"),
                tileInvocations = stage.exactLong("tileInvocationCount", "$layer.$field"),
                tileSuccesses = stage.exactLong("tileSuccessCount", "$layer.$field"),
                steps = stage.exactLong("stepCount", "$layer.$field")
            )
        }
        fun parse(parent: JSONObject, layer: String): Evidence {
            val evidence = parent.optJSONObject("ultraFix")
                ?: invalid("$layer QNN UltraFix evidence is missing.")
            evidence.requireExactKeys(evidenceFields, "$layer.ultraFix")
            return Evidence(
                version = evidence.exactLong("version", "$layer.ultraFix"),
                generationCompleted = evidence.exactBoolean("generationCompleted", "$layer.ultraFix"),
                cancelled = evidence.exactBoolean("cancelled", "$layer.ultraFix"),
                previewPublished = evidence.exactBoolean("previewPublished", "$layer.ultraFix"),
                sourceWidth = evidence.exactLong("sourceWidth", "$layer.ultraFix"),
                sourceHeight = evidence.exactLong("sourceHeight", "$layer.ultraFix"),
                targetWidth = evidence.exactLong("targetWidth", "$layer.ultraFix"),
                targetHeight = evidence.exactLong("targetHeight", "$layer.ultraFix"),
                sourceFit = evidence.exactString("sourceFit", "$layer.ultraFix"),
                sourceResizedWidth = evidence.exactLong("sourceResizedWidth", "$layer.ultraFix"),
                sourceResizedHeight = evidence.exactLong("sourceResizedHeight", "$layer.ultraFix"),
                sourceCropLeft = evidence.exactLong("sourceCropLeft", "$layer.ultraFix"),
                sourceCropTop = evidence.exactLong("sourceCropTop", "$layer.ultraFix"),
                tileSize = evidence.exactLong("tileSize", "$layer.ultraFix"),
                overlap = evidence.exactDouble("overlap", "$layer.ultraFix"),
                tileCount = evidence.exactLong("tileCount", "$layer.ultraFix"),
                tilePlanSha256 = evidence.exactString("tilePlanSha256", "$layer.ultraFix"),
                inversionSteps = evidence.exactLong("inversionSteps", "$layer.ultraFix"),
                refinementSteps = evidence.exactLong("refinementSteps", "$layer.ultraFix"),
                denoiseStepCount = evidence.exactLong("denoiseStepCount", "$layer.ultraFix"),
                sampleMethod = evidence.exactString("sampleMethod", "$layer.ultraFix"),
                nativeScheduler = evidence.exactString("nativeScheduler", "$layer.ultraFix"),
                vaeEncode = parseStage(evidence, "vaeEncode", "$layer.ultraFix"),
                ddimInversion = parseStage(evidence, "ddimInversion", "$layer.ultraFix"),
                tiledUnetRefinement = parseStage(evidence, "tiledUnetRefinement", "$layer.ultraFix"),
                tiledVaeDecode = parseStage(evidence, "tiledVaeDecode", "$layer.ultraFix"),
                encoderGraphExecutionCount = evidence.exactLong(
                    "encoderGraphExecutionCount", "$layer.ultraFix"
                ),
                inversionPositiveGraphExecutionCount = evidence.exactLong(
                    "inversionPositiveGraphExecutionCount", "$layer.ultraFix"
                ),
                refinementPositiveGraphExecutionCount = evidence.exactLong(
                    "refinementPositiveGraphExecutionCount", "$layer.ultraFix"
                ),
                refinementNegativeGraphExecutionCount = evidence.exactLong(
                    "refinementNegativeGraphExecutionCount", "$layer.ultraFix"
                ),
                decoderGraphExecutionCount = evidence.exactLong(
                    "decoderGraphExecutionCount", "$layer.ultraFix"
                ),
                physicalDiffusionModelComputeCount = evidence.exactLong(
                    "physicalDiffusionModelComputeCount", "$layer.ultraFix"
                ),
                qualityStepEvaluationCount = evidence.exactLong(
                    "qualityStepEvaluationCount", "$layer.ultraFix"
                ),
                noiseInjectionStepCount = evidence.exactLong(
                    "noiseInjectionStepCount", "$layer.ultraFix"
                ),
                noiseInjectionSeedFingerprint = evidence.exactString(
                    "noiseInjectionSeedFingerprint", "$layer.ultraFix"
                ),
                noiseInjectionChecksum = evidence.exactString(
                    "noiseInjectionChecksum", "$layer.ultraFix"
                ),
                structureGuidanceStepCount = evidence.exactLong(
                    "structureGuidanceStepCount", "$layer.ultraFix"
                ),
                structureGuidanceChecksum = evidence.exactString(
                    "structureGuidanceChecksum", "$layer.ultraFix"
                ),
                trajectoryNoiseChecksum = evidence.exactString(
                    "trajectoryNoiseChecksum", "$layer.ultraFix"
                ),
                outputSha256 = evidence.exactString("outputSha256", "$layer.ultraFix"),
                outputBytes = evidence.exactLong("outputBytes", "$layer.ultraFix"),
                outputAtomicCommit = evidence.exactBoolean("outputAtomicCommit", "$layer.ultraFix")
            )
        }
        val outer = parse(execution, "outer")
        val inner = parse(nativeEffective, "nativeEffective")
        if (outer != inner) invalid("Outer and nativeEffective QNN UltraFix evidence do not match.")
        if (execution.exactString("strengthMechanism", "outer") != "ddim_inversion" ||
            nativeEffective.exactString("strengthMechanism", "nativeEffective") != "ddim_inversion"
        ) invalid("QNN UltraFix execution did not prove the DDIM inversion strength mechanism.")
        if (outer.version != QNN_ULTRAFIX_EXECUTION_EVIDENCE_VERSION ||
            !outer.generationCompleted || outer.cancelled || outer.previewPublished
        ) invalid("QNN UltraFix evidence is incomplete or has an unsupported version.")

        fun overlapBits(value: Double): String =
            value.toRawBits().toULong().toString(radix = 16).padStart(16, '0')
        fun qnnPlan(): Pair<Long, String> {
            val scale = 8
            val alignment = 8
            if (requested.targetWidth < requested.tileSize || requested.targetHeight < requested.tileSize ||
                requested.targetWidth % (scale * alignment) != 0 ||
                requested.targetHeight % (scale * alignment) != 0 ||
                requested.tileSize % (scale * alignment) != 0
            ) invalid("QNN UltraFix dimensions do not align to the fixed graph tile plan.")
            fun axis(extentPixels: Int): List<Int> {
                val extent = extentPixels / scale
                val tile = requested.tileSize / scale
                if (extent <= 0 || tile <= 0 || tile > extent) invalid("QNN UltraFix tile plan is empty.")
                if (extent == tile) return listOf(0)
                val rawStride = kotlin.math.floor(tile.toDouble() * (1.0 - requested.overlap)).toInt()
                val stride = maxOf(alignment, rawStride / alignment * alignment)
                val finalStart = extent - tile
                val positions = ArrayList<Int>()
                var cursor = 0
                while (true) {
                    positions += cursor
                    if (cursor == finalStart) return positions
                    val next = minOf(cursor + stride, finalStart) / alignment * alignment
                    if (next <= cursor) invalid("QNN UltraFix tile stride did not advance.")
                    cursor = next
                }
            }
            val xs = axis(requested.targetWidth)
            val ys = axis(requested.targetHeight)
            val tile = requested.tileSize / scale
            val descriptor = buildString {
                append("ultrafix-tile-plan-v2|target=")
                    .append(requested.targetWidth).append('x').append(requested.targetHeight)
                    .append("|tile=").append(requested.tileSize)
                    .append("|scale=").append(scale)
                    .append("|alignment=").append(alignment)
                    .append("|overlapBits=").append(overlapBits(requested.overlap))
                    .append("|tiles=").append(xs.size.toLong() * ys.size.toLong())
                ys.forEachIndexed { yi, y ->
                    xs.forEachIndexed { xi, x ->
                        val left = if (xi == 0) 0 else xs[xi - 1] + tile - x
                        val right = if (xi + 1 == xs.size) 0 else x + tile - xs[xi + 1]
                        val top = if (yi == 0) 0 else ys[yi - 1] + tile - y
                        val bottom = if (yi + 1 == ys.size) 0 else y + tile - ys[yi + 1]
                        if (left !in 0 until tile || right !in 0 until tile ||
                            top !in 0 until tile || bottom !in 0 until tile
                        ) invalid("QNN UltraFix tile overlap is invalid.")
                        append('|').append(x * scale).append(',').append(y * scale).append(',')
                            .append(x).append(',').append(y).append(',')
                            .append(left * scale).append(',').append(right * scale).append(',')
                            .append(top * scale).append(',').append(bottom * scale).append(',')
                            .append(left).append(',').append(right).append(',')
                            .append(top).append(',').append(bottom)
                    }
                }
            }
            return xs.size.toLong() * ys.size.toLong() to sha256Utf8(descriptor)
        }
        val (expectedTileCount, expectedTilePlanSha256) = qnnPlan()
        val expectedTargetWidth = requested.targetWidth.toLong()
        val expectedTargetHeight = requested.targetHeight.toLong()
        if (outer.sourceWidth <= 0L || outer.sourceHeight <= 0L ||
            outer.sourceWidth > expectedTargetWidth || outer.sourceHeight > expectedTargetHeight
        ) invalid("QNN UltraFix source geometry is invalid.")
        val fitByWidth = Math.multiplyExact(expectedTargetWidth, outer.sourceHeight) >=
            Math.multiplyExact(expectedTargetHeight, outer.sourceWidth)
        val resizedWidth = if (fitByWidth) expectedTargetWidth else
            Math.multiplyExact(outer.sourceWidth, expectedTargetHeight) / outer.sourceHeight
        val resizedHeight = if (fitByWidth) Math.multiplyExact(outer.sourceHeight, expectedTargetWidth) /
            outer.sourceWidth else expectedTargetHeight
        if (outer.targetWidth != expectedTargetWidth || outer.targetHeight != expectedTargetHeight ||
            outer.sourceFit != "cover_center" || outer.sourceResizedWidth != resizedWidth ||
            outer.sourceResizedHeight != resizedHeight ||
            outer.sourceCropLeft != (resizedWidth - expectedTargetWidth) / 2L ||
            outer.sourceCropTop != (resizedHeight - expectedTargetHeight) / 2L ||
            outer.tileSize != requested.tileSize.toLong() ||
            !imageGenerationApiNumbersMatch(outer.overlap, requested.overlap) || outer.tileCount != expectedTileCount ||
            outer.tilePlanSha256 != expectedTilePlanSha256 ||
            !ImageGenerationApiContract.SHA256_PATTERN.matches(outer.tilePlanSha256) ||
            outer.inversionSteps != requested.inversionSteps.toLong() ||
            outer.refinementSteps != requested.refinementSteps.toLong() ||
            outer.denoiseStepCount != requested.inversionSteps.toLong()
        ) invalid("QNN UltraFix evidence does not match the immutable request or tile plan.")

        fun stage(name: String, value: Stage, invocations: Long, tiles: Long, steps: Long) {
            if (value.invocations != invocations || value.successes != invocations ||
                value.tileInvocations != tiles || value.tileSuccesses != tiles || value.steps != steps
            ) invalid("$name QNN UltraFix stage evidence is incomplete.")
        }
        val tail = requested.inversionSteps.toLong()
        val inversionTiles = Math.multiplyExact(expectedTileCount, tail)
        val refinementPositive = inversionTiles
        val refinementNegative = if (nativeEffective.imageGenerationApiRequiredBoolean("useCfg")) inversionTiles else 0L
        val refinementTiles = Math.addExact(refinementPositive, refinementNegative)
        val physical = Math.addExact(inversionTiles, refinementTiles)
        stage("VAE encode", outer.vaeEncode, 1L, expectedTileCount, 1L)
        stage("DDIM inversion", outer.ddimInversion, tail, inversionTiles, tail)
        stage("tiled UNet refinement", outer.tiledUnetRefinement, tail, refinementTiles, tail)
        stage("tiled VAE decode", outer.tiledVaeDecode, 1L, expectedTileCount, 1L)
        if (outer.encoderGraphExecutionCount != expectedTileCount ||
            outer.inversionPositiveGraphExecutionCount != inversionTiles ||
            outer.refinementPositiveGraphExecutionCount != refinementPositive ||
            outer.refinementNegativeGraphExecutionCount != refinementNegative ||
            outer.decoderGraphExecutionCount != expectedTileCount ||
            outer.physicalDiffusionModelComputeCount != physical ||
            execution.imageGenerationApiRequiredBoolean("useCfg") != nativeEffective.imageGenerationApiRequiredBoolean("useCfg") ||
            execution.imageGenerationApiRequiredBoolean("useCfg") != (refinementNegative != 0L) ||
            nativeEffective.exactLong("positiveDiffusionModelComputeCount", "nativeEffective") !=
                inversionTiles + refinementPositive ||
            nativeEffective.exactLong("negativeDiffusionModelComputeCount", "nativeEffective") != refinementNegative ||
            nativeEffective.exactLong("auxiliaryDiffusionModelComputeCount", "nativeEffective") != 0L ||
            nativeEffective.exactLong("samplingPassCount", "nativeEffective") != 1L ||
            nativeEffective.exactLong("totalUnetExecutionCount", "nativeEffective") != physical
        ) invalid("QNN UltraFix logical and physical graph counts are inconsistent.")
        if (execution.has("actualDiffusionModelComputeCount") &&
            (execution.exactLong("actualDiffusionModelComputeCount", "outer") != physical ||
                execution.exactLong("actualPositiveDiffusionModelComputeCount", "outer") !=
                    inversionTiles + refinementPositive ||
                execution.exactLong("actualNegativeDiffusionModelComputeCount", "outer") != refinementNegative ||
                execution.exactLong("actualAuxiliaryDiffusionModelComputeCount", "outer") != 0L)
        ) invalid("QNN UltraFix outer physical counters are inconsistent.")

        val seed = execution.exactLong("seed", "outer")
        val quality = Math.max(tail - 1L, 0L)
        val zeroQualityChecksum = "0000000000000000"
        val noiseEvidenceConsistent =
            (outer.noiseInjectionStepCount == 0L) ==
                (outer.noiseInjectionChecksum == zeroQualityChecksum)
        val structureEvidenceConsistent =
            (outer.structureGuidanceStepCount == 0L) ==
                (outer.structureGuidanceChecksum == zeroQualityChecksum)
        val qualityActionCount = runCatching {
            Math.addExact(outer.noiseInjectionStepCount, outer.structureGuidanceStepCount)
        }.getOrDefault(-1L)
        if (seed !in 0L..0xffff_ffffL || nativeEffective.exactLong("seed", "nativeEffective") != seed ||
            outer.qualityStepEvaluationCount != quality || outer.noiseInjectionStepCount !in 0L..quality ||
            outer.structureGuidanceStepCount !in 0L..quality ||
            outer.noiseInjectionSeedFingerprint != ultrafixNoiseSeedFingerprint(seed, tail) ||
            !ImageGenerationApiContract.SHA256_PATTERN.matches(outer.noiseInjectionSeedFingerprint) ||
            !UINT64_HEX_PATTERN.matches(outer.noiseInjectionChecksum) ||
            !UINT64_HEX_PATTERN.matches(outer.structureGuidanceChecksum) ||
            !UINT64_HEX_PATTERN.matches(outer.trajectoryNoiseChecksum) ||
            if (quality == 0L) {
                outer.noiseInjectionStepCount != 0L || outer.structureGuidanceStepCount != 0L ||
                    outer.noiseInjectionChecksum != zeroQualityChecksum ||
                    outer.structureGuidanceChecksum != zeroQualityChecksum ||
                    outer.trajectoryNoiseChecksum != zeroQualityChecksum
            } else {
                !noiseEvidenceConsistent || !structureEvidenceConsistent ||
                    qualityActionCount < quality ||
                    outer.trajectoryNoiseChecksum == zeroQualityChecksum
            }
        ) invalid("QNN UltraFix quality, seed, or checksum evidence is invalid.")
        listOf(execution, nativeEffective).forEach { layer ->
            if (!layer.has("img2imgAddNoiseApplied") || layer.get("img2imgAddNoiseApplied") != false ||
                layer.exactString("img2imgNoiseChecksum", "QNN UltraFix") != "0000000000000000"
            ) invalid("QNN UltraFix must prove addNoise=false.")
        }

        data class Commit(val sha256: String, val bytes: Long, val atomic: Boolean)
        fun commit(parent: JSONObject, layer: String): Commit {
            val sha = parent.exactString("outputSha256", layer)
            if (!ImageGenerationApiContract.SHA256_PATTERN.matches(sha)) invalid("$layer output SHA-256 is malformed.")
            return Commit(
                sha256 = sha,
                bytes = parent.exactLong("outputSizeBytes", layer),
                atomic = parent.exactBoolean("outputAtomicCommit", layer)
            )
        }
        val outerCommit = commit(execution, "outer")
        val innerCommit = commit(nativeEffective, "nativeEffective")
        val nestedCommit = Commit(outer.outputSha256, outer.outputBytes, outer.outputAtomicCommit)
        if (outerCommit != innerCommit || outerCommit != nestedCommit || !outerCommit.atomic ||
            outerCommit.bytes !in MIN_ULTRAFIX_OUTPUT_PNG_BYTES..MAX_ULTRAFIX_OUTPUT_PNG_BYTES
        ) invalid("QNN UltraFix output commit evidence is incomplete or conflicts across layers.")
        val item = data.optJSONObject(0) ?: invalid("QNN UltraFix output data must contain one object.")
        val encoded = item.exactString("b64_json", "data[0]")
        val maxEncodedLength = ((MAX_ULTRAFIX_OUTPUT_PNG_BYTES + 2L) / 3L) * 4L
        if (encoded.isEmpty() || encoded.length.toLong() > maxEncodedLength ||
            encoded.length % 4 != 0
        ) invalid("QNN UltraFix output Base64 is malformed or exceeds the response limit.")
        val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
            invalid("QNN UltraFix output Base64 is malformed.")
        }
        if (Base64.getEncoder().encodeToString(decoded) != encoded ||
            decoded.size.toLong() != outerCommit.bytes ||
            item.exactString("mime_type", "data[0]") != "image/png" ||
            item.exactLong("width", "data[0]") != requested.targetWidth.toLong() ||
            item.exactLong("height", "data[0]") != requested.targetHeight.toLong()
        ) {
            invalid("QNN UltraFix output payload is not canonical image/png evidence.")
        }
        val actualSha = MessageDigest.getInstance("SHA-256").digest(decoded)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        if (actualSha != outerCommit.sha256 || decoded.size < PNG_SIGNATURE.size ||
            !decoded.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) ||
            item.exactString("mime_type", "data[0]") != "image/png"
        ) invalid("QNN UltraFix output evidence does not bind the exact returned PNG bytes.")
    }

    internal fun validateImageData(
        data: JSONArray,
        execution: JSONObject,
        nativeEffective: JSONObject,
        expectedCount: Int,
        requireSeedEvidence: Boolean
    ) {
        if (data.length() != expectedCount) {
            reject(
                "invalid_image_provider_response",
                "Image provider returned ${data.length()} images; expected $expectedCount."
            )
        }
        val outputEvidence = execution.optJSONArray("responseOutputEvidence")
            ?: reject(
                "invalid_image_provider_response",
                "Image provider response is missing responseOutputEvidence."
            )
        if (outputEvidence.length() != data.length()) {
            reject(
                "invalid_image_provider_response",
                "Image response output evidence count does not match image data."
            )
        }
        val maxEncodedLength = ((MAX_IMAGE_OUTPUT_PNG_BYTES + 2L) / 3L) * 4L
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index)
                ?: reject("invalid_image_provider_response", "Image data item must be an object.")
            item.imageGenerationApiRejectUnknownFields(RESPONSE_IMAGE_DATA_FIELDS, "data[$index]")
            val evidence = outputEvidence.optJSONObject(index)
                ?: reject(
                    "invalid_image_provider_response",
                    "Image response output evidence item must be an object."
                )
            evidence.imageGenerationApiRejectUnknownFields(RESPONSE_OUTPUT_EVIDENCE_FIELDS, "responseOutputEvidence[$index]")
            if (evidence.imageGenerationApiRequiredInteger("index", minimum = 0L) != index.toLong()) {
                reject("invalid_image_provider_response", "Image response output evidence indices must be contiguous.")
            }
            val encoded = item.imageGenerationApiRequiredNonBlankString("b64_json")
            if (encoded.length.toLong() > maxEncodedLength || encoded.length % 4 != 0) {
                reject("invalid_image_provider_response", "Image data Base64 is malformed or exceeds the response limit.")
            }
            val decoded = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                reject("invalid_image_provider_response", "Image data Base64 is malformed.")
            }
            if (Base64.getEncoder().encodeToString(decoded) != encoded ||
                decoded.size < PNG_SIGNATURE.size ||
                !decoded.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
            ) {
                reject("invalid_image_provider_response", "Image data is not canonical PNG Base64.")
            }
            val mimeType = item.imageGenerationApiRequiredNonBlankString("mime_type")
            val evidenceMimeType = evidence.imageGenerationApiRequiredNonBlankString("mimeType")
            if (mimeType != "image/png" || evidenceMimeType != mimeType) {
                reject("invalid_image_provider_response", "Image data MIME type must be bound to image/png evidence.")
            }
            val evidenceSize = evidence.imageGenerationApiRequiredPositiveLong("sizeBytes")
            if (evidenceSize > MAX_IMAGE_OUTPUT_PNG_BYTES || evidenceSize != decoded.size.toLong()) {
                reject("invalid_image_provider_response", "Image data byte length does not match output evidence.")
            }
            val expectedSha256 = evidence.imageGenerationApiRequiredNonBlankString("sha256")
            val actualSha256 = MessageDigest.getInstance("SHA-256")
                .digest(decoded)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            if (!ImageGenerationApiContract.SHA256_PATTERN.matches(expectedSha256) || actualSha256 != expectedSha256) {
                reject("invalid_image_provider_response", "Image data SHA-256 does not match output evidence.")
            }
            val width = item.imageGenerationApiRequiredPositiveLong("width")
            val height = item.imageGenerationApiRequiredPositiveLong("height")
            if (width != nativeEffective.imageGenerationApiRequiredPositiveLong("width") ||
                height != nativeEffective.imageGenerationApiRequiredPositiveLong("height")
            ) {
                reject(
                    "invalid_image_provider_response",
                    "Image data dimensions do not match nativeEffective evidence."
                )
            }
            if (item.imageGenerationApiRequiredInteger("index", minimum = 0L) != index.toLong()) {
                reject("invalid_image_provider_response", "Image data indices must be contiguous.")
            }
            if (requireSeedEvidence) {
                val nativeSeed = nativeEffective.imageGenerationApiRequiredInteger("seed", minimum = 0L)
                val expectedSeed = runCatching { Math.addExact(nativeSeed, index.toLong()) }
                    .getOrElse {
                        reject(
                            "invalid_image_provider_response",
                            "Native image output seed sequence overflowed."
                        )
                    }
                if (item.imageGenerationApiRequiredInteger("seed", minimum = 0L) != expectedSeed) {
                    reject(
                        "invalid_image_provider_response",
                        "Image data seed does not match native execution evidence."
                    )
                }
            }
        }
    }

}

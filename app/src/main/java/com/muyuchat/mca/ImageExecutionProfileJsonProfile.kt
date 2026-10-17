package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal fun ImageExecutionProfileJson.parseProfile(json: JSONObject): ImageExecutionProfile {
    val profile = try {
        ImageExecutionProfile(
            schemaVersion = json.requiredStrictInt("schemaVersion"),
            profileId = json.requiredStrictString("profileId"),
            profileRevision = json.requiredStrictInt("profileRevision"),
            modelFingerprint = json.requiredStrictString("modelFingerprint"),
            runtime = enumValue(json.requiredStrictString("runtime"), "runtime"),
            family = enumValue(json.requiredStrictString("family"), "family"),
            variant = enumValue(json.requiredStrictString("variant"), "variant"),
            task = enumValue(json.requiredStrictString("task"), "task"),
            provenance = parseProvenance(json.strictObject("provenance")),
            textEncoderLanguage = json.optionalObject("textEncoderLanguage")
                ?.let(::parseTextEncoderLanguage),
            tokenizer = parseTokenizer(json.strictObject("tokenizer")),
            conditioning = parseConditioning(json.strictObject("conditioning")),
            scheduler = parseScheduler(json.strictObject("scheduler")),
            latent = parseLatent(json.strictObject("latent")),
            vae = parseVae(json.strictObject("vae")),
            graph = parseGraph(json.strictObject("graph")),
            defaults = parseDefaults(json.strictObject("defaults")),
            capabilities = parseCapabilities(json.strictObject("capabilities"))
        )
    } catch (error: ImageExecutionProfileJsonException) {
        throw error
    } catch (error: JSONException) {
        throw ImageExecutionProfileJsonException(
            code = "PROFILE_JSON_INVALID",
            field = "executionProfile",
            message = error.message ?: "Invalid execution profile JSON.",
            cause = error
        )
    }
    val legacyIncompleteNativeMultilingualDeclaration = profile.textEncoderLanguage
        ?.takeIf { contract ->
            contract.capability == ImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL &&
                (contract.evidence == null || contract.evidence.promptToEncoderAssets.isEmpty())
        }
    val sequenceAxisIssues = buildList {
        if (profile.conditioning.textEncoderInputShape.getOrNull(1) != profile.tokenizer.maxLength) {
            add(
                ImageProfileValidationIssue(
                    code = "CONDITIONING_CONTRACT_INVALID",
                    field = "conditioning.textEncoderInputShape",
                    message = "Conditioning input sequence axis must match tokenizer maxLength."
                )
            )
        }
        profile.conditioning.textEncoderOutputShapes.forEachIndexed { index, shape ->
            if (shape.getOrNull(1) != profile.tokenizer.maxLength) {
                add(
                    ImageProfileValidationIssue(
                        code = "CONDITIONING_CONTRACT_INVALID",
                        field = "conditioning.textEncoderOutputShapes[$index]",
                        message = "Conditioning output sequence axis must match tokenizer maxLength."
                    )
                )
            }
        }
    }
    val profileIssues = ImageExecutionProfileValidator.validate(profile).issues
        .filterNot { issue ->
            // The role-aware closure did not exist in older installed profiles. It was never
            // enough to admit Chinese directly, but it must not make an otherwise valid
            // package unusable for English prompts. Keep malformed fields and asset pins
            // fatal; only remove the expected missing-closure declaration errors.
            legacyIncompleteNativeMultilingualDeclaration != null &&
                (issue.code == "TEXT_ENCODER_LANGUAGE_EVIDENCE_MISSING" ||
                    (issue.code == "TEXT_ENCODER_LANGUAGE_TOPOLOGY_UNSUPPORTED" &&
                        issue.field ==
                            "textEncoderLanguage.evidence.promptToEncoderAssets"))
        }
    val validation = ImageProfileValidationReport(
        profileIssues + sequenceAxisIssues
    )
    if (!validation.valid) {
        val first = validation.issues.first()
        throw ImageExecutionProfileJsonException(
            code = "PROFILE_VALIDATION_FAILED",
            field = first.field,
            message = validation.issues.joinToString(" ") { issue -> "${issue.code}:${issue.message}" }
        )
    }
    return if (legacyIncompleteNativeMultilingualDeclaration != null) {
        profile.copy(textEncoderLanguage = null)
    } else {
        profile
    }
}

private fun parseProvenance(json: JSONObject): ImageProfileProvenance = ImageProfileProvenance(
    primarySource = enumValue(json.requiredStrictString("primarySource"), "provenance.primarySource"),
    sources = json.requiredArray("sources").strictEnumList("provenance.sources"),
    recommendationId = json.optionalStrictString("recommendationId"),
    recommendationRevision = json.optionalStrictString("recommendationRevision"),
    notes = json.optionalArray("notes")?.strictStringList("provenance.notes").orEmpty()
)

/**
 * This field was added after installed execution-profile JSON existed. Its absence is not an
 * error: callers conservatively treat it as English-dominant instead of inferring semantics
 * from a family, package sidecar, tokenizer transport, or device.
 */
private fun parseTextEncoderLanguage(json: JSONObject): ImageTextEncoderLanguageContract {
    val supportedLanguages = json.requiredArray("supportedLanguages")
        .strictEnumList<ImageTextEncoderLanguage>("textEncoderLanguage.supportedLanguages")
    if (supportedLanguages.toSet().size != supportedLanguages.size) {
        throw formatError(
            "textEncoderLanguage.supportedLanguages",
            "Text encoder language support must not contain duplicates."
        )
    }
    val evidence = json.optionalObject("evidence")?.let { evidenceJson ->
        ImageTextEncoderLanguageEvidence(
            evidenceId = evidenceJson.requiredStrictString("evidenceId"),
            evidenceSha256 = evidenceJson.requiredStrictString("evidenceSha256"),
            textEncoderAsset = evidenceJson.strictObject("textEncoderAsset").let { assetJson ->
                ImageProfileAsset(
                    relativePath = assetJson.requiredStrictString("relativePath"),
                    fingerprint = assetJson.requiredStrictString("fingerprint"),
                    sizeBytes = assetJson.requiredStrictLong("sizeBytes")
                ).also { asset ->
                    requireSafePath(
                        asset.relativePath,
                        "textEncoderLanguage.evidence.textEncoderAsset.relativePath"
                    )
                }
            },
            auxiliaryAssets = evidenceJson.optionalArray("auxiliaryAssets")
                ?.strictObjectList("textEncoderLanguage.evidence.auxiliaryAssets")
                ?.mapIndexed { index, assetJson ->
                    ImageProfileAsset(
                        relativePath = assetJson.requiredStrictString("relativePath"),
                        fingerprint = assetJson.requiredStrictString("fingerprint"),
                        sizeBytes = assetJson.requiredStrictLong("sizeBytes")
                    ).also { asset ->
                        requireSafePath(
                            asset.relativePath,
                            "textEncoderLanguage.evidence.auxiliaryAssets[$index].relativePath"
                        )
                    }
                }
                .orEmpty(),
            promptToEncoderAssets = evidenceJson.optionalArray("promptToEncoderAssets")
                ?.strictObjectList("textEncoderLanguage.evidence.promptToEncoderAssets")
                ?.mapIndexed { index, entryJson ->
                    val asset = entryJson.strictObject("asset").let { assetJson ->
                        ImageProfileAsset(
                            relativePath = assetJson.requiredStrictString("relativePath"),
                            fingerprint = assetJson.requiredStrictString("fingerprint"),
                            sizeBytes = assetJson.requiredStrictLong("sizeBytes")
                        )
                    }
                    requireSafePath(
                        asset.relativePath,
                        "textEncoderLanguage.evidence.promptToEncoderAssets[$index].asset.relativePath"
                    )
                    ImagePromptToEncoderAsset(
                        role = enumValue(
                            entryJson.requiredStrictString("role"),
                            "textEncoderLanguage.evidence.promptToEncoderAssets[$index].role"
                        ),
                        asset = asset
                    )
                }
                .orEmpty(),
            semanticProof = evidenceJson.optionalObject("semanticProof")
                ?.let(::parseTextEncoderLanguageSemanticProof)
        )
    }
    return ImageTextEncoderLanguageContract(
        capability = enumValue(
            json.requiredStrictString("capability"),
            "textEncoderLanguage.capability"
        ),
        supportedLanguages = supportedLanguages.toCollection(linkedSetOf()),
        evidence = evidence
    )
}

/**
 * A malformed proof is not a malformed base profile. Its verifier will reject it and the
 * resolver will conservatively remove only the direct-Chinese declaration, preserving normal
 * English import/load/generation behavior.
 */
private fun parseTextEncoderLanguageSemanticProof(
    json: JSONObject
): ImageTextEncoderLanguageSemanticProof = ImageTextEncoderLanguageSemanticProof(
    proofVersion = json.optStrictIntOrZero("proofVersion"),
    signerKeyId = json.opt("signerKeyId") as? String ?: "",
    signerCertificateSha256 = json.opt("signerCertificateSha256") as? String ?: "",
    signatureAlgorithm = json.opt("signatureAlgorithm") as? String ?: "",
    payloadSha256 = json.opt("payloadSha256") as? String ?: "",
    signatureBase64 = json.opt("signatureBase64") as? String ?: ""
)

private fun parseTokenizer(json: JSONObject): ImageTokenizerContract = ImageTokenizerContract(
    backend = enumValue(json.requiredStrictString("backend"), "tokenizer.backend"),
    assets = json.optionalArray("assets")?.strictObjectList("tokenizer.assets")?.mapIndexed { index, asset ->
        ImageProfileAsset(
            relativePath = asset.requiredStrictString("relativePath"),
            fingerprint = asset.requiredStrictString("fingerprint"),
            sizeBytes = asset.optionalStrictLong("sizeBytes")
        ).also { requireSafePath(it.relativePath, "tokenizer.assets[$index].relativePath") }
    }.orEmpty(),
    bosId = json.optionalStrictInt("bosId"),
    eosId = json.optionalStrictInt("eosId"),
    padId = json.optionalStrictInt("padId"),
    maxLength = json.requiredStrictInt("maxLength"),
    unicodeNormalization = enumValue(json.requiredStrictString("unicodeNormalization"), "tokenizer.unicodeNormalization"),
    lowercase = json.requiredStrictBoolean("lowercase"),
    preTokenizer = json.requiredStrictString("preTokenizer"),
    postProcessor = json.requiredStrictString("postProcessor"),
    clip1PadRule = enumValue(json.requiredStrictString("clip1PadRule"), "tokenizer.clip1PadRule"),
    clip2PadRule = json.optionalStrictString("clip2PadRule")?.let { enumValue(it, "tokenizer.clip2PadRule") },
    supportsPromptWeighting = json.requiredStrictBoolean("supportsPromptWeighting"),
    supportsTextualInversion = json.requiredStrictBoolean("supportsTextualInversion"),
    separateNegativePrompt = json.requiredStrictBoolean("separateNegativePrompt")
)

private fun parseConditioning(json: JSONObject): ImageConditioningContract = ImageConditioningContract(
    diskDataType = enumValue(json.requiredStrictString("diskDataType"), "conditioning.diskDataType"),
    exactByteSize = json.optionalStrictLong("exactByteSize"),
    elementCount = json.optionalStrictLong("elementCount"),
    tokenTableShape = json.optionalArray("tokenTableShape")?.strictPositiveIntList("conditioning.tokenTableShape").orEmpty(),
    positionTableShape = json.optionalArray("positionTableShape")?.strictPositiveIntList("conditioning.positionTableShape").orEmpty(),
    textEncoderInputShape = json.optionalArray("textEncoderInputShape")?.strictPositiveIntList("conditioning.textEncoderInputShape").orEmpty(),
    textEncoderOutputShapes = json.optionalArray("textEncoderOutputShapes")
        ?.strictArrayList("conditioning.textEncoderOutputShapes")
        ?.mapIndexed { index, shape -> shape.strictPositiveIntList("conditioning.textEncoderOutputShapes[$index]") }
        .orEmpty(),
    conversionStrategy = enumValue(json.requiredStrictString("conversionStrategy"), "conditioning.conversionStrategy"),
    dualEncoder = json.requiredStrictBoolean("dualEncoder"),
    pooledOutput = json.requiredStrictBoolean("pooledOutput"),
    concatenationOrder = json.optionalArray("concatenationOrder")?.strictStringList("conditioning.concatenationOrder").orEmpty()
)

private fun parseScheduler(json: JSONObject): ImageSchedulerContract = ImageSchedulerContract(
    algorithm = enumValue(json.requiredStrictString("algorithm"), "scheduler.algorithm"),
    predictionType = enumValue(json.requiredStrictString("predictionType"), "scheduler.predictionType"),
    numTrainTimesteps = json.requiredStrictInt("numTrainTimesteps"),
    noiseSchedule = enumValue(json.requiredStrictString("noiseSchedule"), "scheduler.noiseSchedule"),
    betaStart = json.optionalStrictDouble("betaStart"),
    betaEnd = json.optionalStrictDouble("betaEnd"),
    timestepSpacing = enumValue(json.requiredStrictString("timestepSpacing"), "scheduler.timestepSpacing"),
    stepsOffset = json.requiredStrictInt("stepsOffset"),
    setAlphaToOne = json.requiredStrictBoolean("setAlphaToOne"),
    skipPrkSteps = json.requiredStrictBoolean("skipPrkSteps"),
    finalSigmaType = enumValue(json.requiredStrictString("finalSigmaType"), "scheduler.finalSigmaType"),
    clipSample = json.requiredStrictBoolean("clipSample"),
    clipSampleRange = json.requiredStrictDouble("clipSampleRange"),
    thresholding = json.requiredStrictBoolean("thresholding"),
    eta = json.requiredStrictDouble("eta"),
    lowerOrderFinal = json.requiredStrictBoolean("lowerOrderFinal"),
    initNoiseSigma = json.requiredStrictDouble("initNoiseSigma"),
    scaleModelInput = json.requiredStrictBoolean("scaleModelInput"),
    order = json.requiredStrictInt("order"),
    defaultSteps = json.requiredStrictInt("defaultSteps"),
    minSteps = json.requiredStrictInt("minSteps"),
    maxSteps = json.requiredStrictInt("maxSteps"),
    rng = enumValue(json.requiredStrictString("rng"), "scheduler.rng"),
    seedBits = json.requiredStrictInt("seedBits")
)

private fun parseLatent(json: JSONObject): ImageLatentContract = ImageLatentContract(
    channels = json.requiredStrictInt("channels"),
    downsampleFactor = json.requiredStrictInt("downsampleFactor"),
    schedulerLayout = enumValue(json.requiredStrictString("schedulerLayout"), "latent.schedulerLayout"),
    graphLayout = enumValue(json.requiredStrictString("graphLayout"), "latent.graphLayout"),
    initialShape = json.requiredArray("initialShape").strictPositiveIntList("latent.initialShape"),
    dataType = enumValue(json.requiredStrictString("dataType"), "latent.dataType")
)

private fun parseVae(json: JSONObject): ImageVaeContract = ImageVaeContract(
    scalingLocation = enumValue(json.requiredStrictString("scalingLocation"), "vae.scalingLocation"),
    scalingFactor = json.requiredStrictDouble("scalingFactor"),
    inputShape = json.requiredArray("inputShape").strictPositiveIntList("vae.inputShape"),
    outputShape = json.requiredArray("outputShape").strictPositiveIntList("vae.outputShape"),
    inputLayout = enumValue(json.requiredStrictString("inputLayout"), "vae.inputLayout"),
    outputLayout = enumValue(json.requiredStrictString("outputLayout"), "vae.outputLayout"),
    outputRange = enumValue(json.requiredStrictString("outputRange"), "vae.outputRange"),
    channelOrder = enumValue(json.requiredStrictString("channelOrder"), "vae.channelOrder")
)

private fun parseGraph(json: JSONObject): ImageGraphContract = ImageGraphContract(
    textEncoder = json.optionalObject("textEncoder")?.let { parseGraphArtifact(it, "graph.textEncoder") },
    unet = json.optionalObject("unet")?.let { parseGraphArtifact(it, "graph.unet") },
    vae = json.optionalObject("vae")?.let { parseGraphArtifact(it, "graph.vae") },
    vaeEncoder = json.optionalObject("vaeEncoder")?.let { parseGraphArtifact(it, "graph.vaeEncoder") },
    controlNet = json.optionalObject("controlNet")?.let { parseGraphArtifact(it, "graph.controlNet") },
    schedulerSidecar = json.optionalStrictString("schedulerSidecar")?.also { requireSafePath(it, "graph.schedulerSidecar") },
    tokenizerSidecar = json.optionalStrictString("tokenizerSidecar")?.also { requireSafePath(it, "graph.tokenizerSidecar") },
    configSidecars = json.optionalArray("configSidecars")?.strictStringList("graph.configSidecars")?.onEachIndexed { index, path ->
        requireSafePath(path, "graph.configSidecars[$index]")
    }.orEmpty(),
    qnnSdk = json.optionalStrictString("qnnSdk"),
    htpArch = json.optionalStrictInt("htpArch"),
    contextMetadataFingerprint = json.optionalStrictString("contextMetadataFingerprint"),
    workerStrategy = enumValue(json.requiredStrictString("workerStrategy"), "graph.workerStrategy")
)

private fun parseGraphArtifact(json: JSONObject, field: String): ImageGraphArtifactContract {
    val path = json.requiredStrictString("relativePath")
    requireSafePath(path, "$field.relativePath")
    return ImageGraphArtifactContract(
        relativePath = path,
        graphName = json.requiredStrictString("graphName"),
        inputs = json.optionalArray("inputs")?.strictObjectList("$field.inputs")?.mapIndexed { index, tensor ->
            parseTensor(tensor, "$field.inputs[$index]")
        }.orEmpty(),
        outputs = json.optionalArray("outputs")?.strictObjectList("$field.outputs")?.mapIndexed { index, tensor ->
            parseTensor(tensor, "$field.outputs[$index]")
        }.orEmpty()
    )
}

private fun parseTensor(json: JSONObject, field: String): ImageTensorContract = ImageTensorContract(
    role = json.requiredStrictString("role"),
    name = json.requiredStrictString("name"),
    shape = json.requiredArray("shape").strictPositiveIntList("$field.shape"),
    dataType = enumValue(json.requiredStrictString("dataType"), "$field.dataType"),
    scale = json.optionalStrictDouble("scale"),
    zeroPoint = json.optionalStrictInt("zeroPoint")
)

private fun parseDefaults(json: JSONObject): ImageGenerationDefaults = ImageGenerationDefaults(
    width = json.requiredStrictInt("width"),
    height = json.requiredStrictInt("height"),
    steps = json.requiredStrictInt("steps"),
    cfgScale = json.requiredStrictDouble("cfgScale"),
    seed = json.requiredStrictLong("seed"),
    useCfg = json.requiredStrictBoolean("useCfg"),
    defaultPrompt = json.optionalStrictString("defaultPrompt", preserveBlank = true),
    defaultNegativePrompt = json.optionalStrictString("defaultNegativePrompt", preserveBlank = true)
)

private fun parseCapabilities(json: JSONObject): ImageGenerationCapabilities {
    val minWidth = json.requiredStrictInt("minWidth")
    val maxWidth = json.requiredStrictInt("maxWidth")
    val minHeight = json.requiredStrictInt("minHeight")
    val maxHeight = json.requiredStrictInt("maxHeight")
    val widthMultiple = json.requiredStrictInt("widthMultiple")
    val heightMultiple = json.requiredStrictInt("heightMultiple")
    val supportsUltraFix = json.optionalStrictBoolean("supportsUltraFix") ?: false
    return ImageGenerationCapabilities(
        supportedSchedulers = json.requiredArray("supportedSchedulers")
            .strictEnumList<ImageSchedulerAlgorithm>("capabilities.supportedSchedulers")
            .toSet(),
        minWidth = minWidth,
        maxWidth = maxWidth,
        minHeight = minHeight,
        maxHeight = maxHeight,
        widthMultiple = widthMultiple,
        heightMultiple = heightMultiple,
        supportsNegativePrompt = json.requiredStrictBoolean("supportsNegativePrompt"),
        supportsPromptWeighting = json.requiredStrictBoolean("supportsPromptWeighting"),
        supportsTextualInversion = json.requiredStrictBoolean("supportsTextualInversion"),
        requiresControlImage = json.requiredStrictBoolean("requiresControlImage"),
        requiresInputImage = json.requiredStrictBoolean("requiresInputImage"),
        supportsMask = json.requiredStrictBoolean("supportsMask"),
        // Added after the original profile schema shipped. Old installed manifests remain
        // readable and conservatively expose no advanced control until a built-in or
        // downloaded profile layer supplies an explicit capability.
        supportsClipSkip = json.optionalStrictBoolean("supportsClipSkip") ?: false,
        supportsVaeTiling = json.optionalStrictBoolean("supportsVaeTiling") ?: false,
        supportsUltraFix = supportsUltraFix,
        ultraFixMinWidth = json.optionalStrictInt("ultraFixMinWidth")
            ?: if (supportsUltraFix) minWidth else 0,
        ultraFixMaxWidth = json.optionalStrictInt("ultraFixMaxWidth")
            ?: if (supportsUltraFix) maxWidth else 0,
        ultraFixMinHeight = json.optionalStrictInt("ultraFixMinHeight")
            ?: if (supportsUltraFix) minHeight else 0,
        ultraFixMaxHeight = json.optionalStrictInt("ultraFixMaxHeight")
            ?: if (supportsUltraFix) maxHeight else 0,
        ultraFixWidthMultiple = json.optionalStrictInt("ultraFixWidthMultiple")
            ?: if (supportsUltraFix) widthMultiple else 0,
        ultraFixHeightMultiple = json.optionalStrictInt("ultraFixHeightMultiple")
            ?: if (supportsUltraFix) heightMultiple else 0,
        ultraFixRequiredTileSize = json.optionalStrictInt("ultraFixRequiredTileSize") ?: 0,
        supportsLivePreview = json.optionalStrictBoolean("supportsLivePreview") ?: false,
        supportsLora = json.optionalStrictBoolean("supportsLora") ?: false,
        maxBatchCount = json.optionalStrictInt("maxBatchCount") ?: 1
    )
}

private fun JSONObject.requiredArray(name: String): JSONArray =
    optionalArray(name) ?: throw formatError(name, "$name is required and must be an array.")

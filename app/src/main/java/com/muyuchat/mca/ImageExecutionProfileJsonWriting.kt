package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject

/** Canonical writer used when a catalog profile is materialized into an installed package. */
internal fun ImageExecutionProfileJson.toJson(profile: ImageExecutionProfile): JSONObject = JSONObject()
    .put("schemaVersion", profile.schemaVersion)
    .put("profileId", profile.profileId)
    .put("profileRevision", profile.profileRevision)
    .put("modelFingerprint", profile.modelFingerprint.lowercase())
    .put("runtime", profile.runtime.name)
    .put("family", profile.family.name)
    .put("variant", profile.variant.name)
    .put("task", profile.task.name)
    .put(
        "provenance",
        JSONObject()
            .put("primarySource", profile.provenance.primarySource.name)
            .put("sources", JSONArray(profile.provenance.sources.map { source -> source.name }))
            .apply {
                profile.provenance.recommendationId?.let { put("recommendationId", it) }
                profile.provenance.recommendationRevision?.let { put("recommendationRevision", it) }
                put("notes", JSONArray(profile.provenance.notes))
            }
    )
    .apply {
        profile.textEncoderLanguage?.let { contract ->
            put("textEncoderLanguage", textEncoderLanguageToJson(contract))
        }
    }
    .put(
        "tokenizer",
        JSONObject()
            .put("backend", profile.tokenizer.backend.name)
            .put(
                "assets",
                JSONArray(profile.tokenizer.assets.map { asset ->
                    JSONObject()
                        .put("relativePath", asset.relativePath)
                        .put("fingerprint", asset.fingerprint.lowercase())
                        .apply { asset.sizeBytes?.let { put("sizeBytes", it) } }
                })
            )
            .apply {
                profile.tokenizer.bosId?.let { put("bosId", it) }
                profile.tokenizer.eosId?.let { put("eosId", it) }
                profile.tokenizer.padId?.let { put("padId", it) }
            }
            .put("maxLength", profile.tokenizer.maxLength)
            .put("unicodeNormalization", profile.tokenizer.unicodeNormalization.name)
            .put("lowercase", profile.tokenizer.lowercase)
            .put("preTokenizer", profile.tokenizer.preTokenizer)
            .put("postProcessor", profile.tokenizer.postProcessor)
            .put("clip1PadRule", profile.tokenizer.clip1PadRule.name)
            .apply { profile.tokenizer.clip2PadRule?.let { put("clip2PadRule", it.name) } }
            .put("supportsPromptWeighting", profile.tokenizer.supportsPromptWeighting)
            .put("supportsTextualInversion", profile.tokenizer.supportsTextualInversion)
            .put("separateNegativePrompt", profile.tokenizer.separateNegativePrompt)
    )
    .put(
        "conditioning",
        JSONObject()
            .put("diskDataType", profile.conditioning.diskDataType.name)
            .apply {
                profile.conditioning.exactByteSize?.let { put("exactByteSize", it) }
                profile.conditioning.elementCount?.let { put("elementCount", it) }
            }
            .put("tokenTableShape", JSONArray(profile.conditioning.tokenTableShape))
            .put("positionTableShape", JSONArray(profile.conditioning.positionTableShape))
            .put("textEncoderInputShape", JSONArray(profile.conditioning.textEncoderInputShape))
            .put(
                "textEncoderOutputShapes",
                JSONArray(profile.conditioning.textEncoderOutputShapes.map { shape -> JSONArray(shape) })
            )
            .put("conversionStrategy", profile.conditioning.conversionStrategy.name)
            .put("dualEncoder", profile.conditioning.dualEncoder)
            .put("pooledOutput", profile.conditioning.pooledOutput)
            .put("concatenationOrder", JSONArray(profile.conditioning.concatenationOrder))
    )
    .put(
        "scheduler",
        JSONObject()
            .put("algorithm", profile.scheduler.algorithm.name)
            .put("predictionType", profile.scheduler.predictionType.name)
            .put("numTrainTimesteps", profile.scheduler.numTrainTimesteps)
            .put("noiseSchedule", profile.scheduler.noiseSchedule.name)
            .apply {
                profile.scheduler.betaStart?.let { put("betaStart", it) }
                profile.scheduler.betaEnd?.let { put("betaEnd", it) }
            }
            .put("timestepSpacing", profile.scheduler.timestepSpacing.name)
            .put("stepsOffset", profile.scheduler.stepsOffset)
            .put("setAlphaToOne", profile.scheduler.setAlphaToOne)
            .put("skipPrkSteps", profile.scheduler.skipPrkSteps)
            .put("finalSigmaType", profile.scheduler.finalSigmaType.name)
            .put("clipSample", profile.scheduler.clipSample)
            .put("clipSampleRange", profile.scheduler.clipSampleRange)
            .put("thresholding", profile.scheduler.thresholding)
            .put("eta", profile.scheduler.eta)
            .put("lowerOrderFinal", profile.scheduler.lowerOrderFinal)
            .put("initNoiseSigma", profile.scheduler.initNoiseSigma)
            .put("scaleModelInput", profile.scheduler.scaleModelInput)
            .put("order", profile.scheduler.order)
            .put("defaultSteps", profile.scheduler.defaultSteps)
            .put("minSteps", profile.scheduler.minSteps)
            .put("maxSteps", profile.scheduler.maxSteps)
            .put("rng", profile.scheduler.rng.name)
            .put("seedBits", profile.scheduler.seedBits)
    )
    .put(
        "latent",
        JSONObject()
            .put("channels", profile.latent.channels)
            .put("downsampleFactor", profile.latent.downsampleFactor)
            .put("schedulerLayout", profile.latent.schedulerLayout.name)
            .put("graphLayout", profile.latent.graphLayout.name)
            .put("initialShape", JSONArray(profile.latent.initialShape))
            .put("dataType", profile.latent.dataType.name)
    )
    .put(
        "vae",
        JSONObject()
            .put("scalingLocation", profile.vae.scalingLocation.name)
            .put("scalingFactor", profile.vae.scalingFactor)
            .put("inputShape", JSONArray(profile.vae.inputShape))
            .put("outputShape", JSONArray(profile.vae.outputShape))
            .put("inputLayout", profile.vae.inputLayout.name)
            .put("outputLayout", profile.vae.outputLayout.name)
            .put("outputRange", profile.vae.outputRange.name)
            .put("channelOrder", profile.vae.channelOrder.name)
    )
    .put("graph", graphToJson(profile.graph))
    .put(
        "defaults",
        JSONObject()
            .put("width", profile.defaults.width)
            .put("height", profile.defaults.height)
            .put("steps", profile.defaults.steps)
            .put("cfgScale", profile.defaults.cfgScale)
            .put("seed", profile.defaults.seed)
            .put("useCfg", profile.defaults.useCfg)
            .apply {
                profile.defaults.defaultPrompt?.let { put("defaultPrompt", it) }
                profile.defaults.defaultNegativePrompt?.let { put("defaultNegativePrompt", it) }
            }
    )
    .put(
        "capabilities",
        JSONObject()
            .put(
                "supportedSchedulers",
                JSONArray(profile.capabilities.supportedSchedulers.map { scheduler -> scheduler.name })
            )
            .put("minWidth", profile.capabilities.minWidth)
            .put("maxWidth", profile.capabilities.maxWidth)
            .put("minHeight", profile.capabilities.minHeight)
            .put("maxHeight", profile.capabilities.maxHeight)
            .put("widthMultiple", profile.capabilities.widthMultiple)
            .put("heightMultiple", profile.capabilities.heightMultiple)
            .put("supportsNegativePrompt", profile.capabilities.supportsNegativePrompt)
            .put("supportsPromptWeighting", profile.capabilities.supportsPromptWeighting)
            .put("supportsTextualInversion", profile.capabilities.supportsTextualInversion)
            .put("requiresControlImage", profile.capabilities.requiresControlImage)
            .put("requiresInputImage", profile.capabilities.requiresInputImage)
            .put("supportsMask", profile.capabilities.supportsMask)
            .put("supportsClipSkip", profile.capabilities.supportsClipSkip)
            .put("supportsVaeTiling", profile.capabilities.supportsVaeTiling)
            .put("supportsUltraFix", profile.capabilities.supportsUltraFix)
            .put("ultraFixMinWidth", profile.capabilities.ultraFixMinWidth)
            .put("ultraFixMaxWidth", profile.capabilities.ultraFixMaxWidth)
            .put("ultraFixMinHeight", profile.capabilities.ultraFixMinHeight)
            .put("ultraFixMaxHeight", profile.capabilities.ultraFixMaxHeight)
            .put("ultraFixWidthMultiple", profile.capabilities.ultraFixWidthMultiple)
            .put("ultraFixHeightMultiple", profile.capabilities.ultraFixHeightMultiple)
            .put("ultraFixRequiredTileSize", profile.capabilities.ultraFixRequiredTileSize)
            .put("supportsLivePreview", profile.capabilities.supportsLivePreview)
            .put("supportsLora", profile.capabilities.supportsLora)
            .put("maxBatchCount", profile.capabilities.maxBatchCount)
    )

private fun textEncoderLanguageToJson(
    contract: ImageTextEncoderLanguageContract
): JSONObject = JSONObject()
    .put("capability", contract.capability.name)
    .put(
        "supportedLanguages",
        JSONArray(contract.supportedLanguages.sortedBy { it.ordinal }.map { language -> language.name })
    )
    .apply {
        contract.evidence?.let { evidence ->
            put(
                "evidence",
                JSONObject()
                    .put("evidenceId", evidence.evidenceId)
                    .put("evidenceSha256", evidence.evidenceSha256.lowercase())
                    .put(
                        "textEncoderAsset",
                        JSONObject()
                            .put("relativePath", evidence.textEncoderAsset.relativePath)
                            .put("fingerprint", evidence.textEncoderAsset.fingerprint.lowercase())
                            .put("sizeBytes", evidence.textEncoderAsset.sizeBytes)
                    )
                    .apply {
                        if (evidence.auxiliaryAssets.isNotEmpty()) {
                            put(
                                "auxiliaryAssets",
                                JSONArray(evidence.auxiliaryAssets.map { asset ->
                                    JSONObject()
                                        .put("relativePath", asset.relativePath)
                                        .put("fingerprint", asset.fingerprint.lowercase())
                                        .put("sizeBytes", asset.sizeBytes)
                                })
                            )
                        }
                        if (evidence.promptToEncoderAssets.isNotEmpty()) {
                            put(
                                "promptToEncoderAssets",
                                JSONArray(
                                    evidence.promptToEncoderAssets
                                        .sortedBy { entry -> entry.role.ordinal }
                                        .map { entry ->
                                            JSONObject()
                                                .put("role", entry.role.name)
                                                .put(
                                                    "asset",
                                                    JSONObject()
                                                        .put(
                                                            "relativePath",
                                                            entry.asset.relativePath
                                                        )
                                                        .put(
                                                            "fingerprint",
                                                            entry.asset.fingerprint.lowercase()
                                                        )
                                                        .put("sizeBytes", entry.asset.sizeBytes)
                                                )
                                        }
                                )
                            )
                        }
                        evidence.semanticProof?.let { proof ->
                            put(
                                "semanticProof",
                                JSONObject()
                                    .put("proofVersion", proof.proofVersion)
                                    .put("signerKeyId", proof.signerKeyId)
                                    .put(
                                        "signerCertificateSha256",
                                        proof.signerCertificateSha256.lowercase()
                                    )
                                    .put("signatureAlgorithm", proof.signatureAlgorithm)
                                    .put("payloadSha256", proof.payloadSha256.lowercase())
                                    .put("signatureBase64", proof.signatureBase64)
                            )
                        }
                    }
            )
        }
    }

private fun graphToJson(graph: ImageGraphContract): JSONObject = JSONObject().apply {
    graph.textEncoder?.let { put("textEncoder", graphArtifactToJson(it)) }
    graph.unet?.let { put("unet", graphArtifactToJson(it)) }
    graph.vae?.let { put("vae", graphArtifactToJson(it)) }
    graph.vaeEncoder?.let { put("vaeEncoder", graphArtifactToJson(it)) }
    graph.controlNet?.let { put("controlNet", graphArtifactToJson(it)) }
    graph.schedulerSidecar?.let { put("schedulerSidecar", it) }
    graph.tokenizerSidecar?.let { put("tokenizerSidecar", it) }
    put("configSidecars", JSONArray(graph.configSidecars))
    graph.qnnSdk?.let { put("qnnSdk", it) }
    graph.htpArch?.let { put("htpArch", it) }
    graph.contextMetadataFingerprint?.let { put("contextMetadataFingerprint", it) }
    put("workerStrategy", graph.workerStrategy.name)
}

private fun graphArtifactToJson(artifact: ImageGraphArtifactContract): JSONObject = JSONObject()
    .put("relativePath", artifact.relativePath)
    .put("graphName", artifact.graphName)
    .put("inputs", JSONArray(artifact.inputs.map(::tensorToJson)))
    .put("outputs", JSONArray(artifact.outputs.map(::tensorToJson)))

private fun tensorToJson(tensor: ImageTensorContract): JSONObject = JSONObject()
    .put("role", tensor.role)
    .put("name", tensor.name)
    .put("shape", JSONArray(tensor.shape))
    .put("dataType", tensor.dataType.name)
    .apply {
        tensor.scale?.let { put("scale", it) }
        tensor.zeroPoint?.let { put("zeroPoint", it) }
    }

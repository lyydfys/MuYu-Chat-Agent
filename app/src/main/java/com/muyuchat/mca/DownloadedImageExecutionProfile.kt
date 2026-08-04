package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineBundleRuntime
import com.muyuchat.core.download.ImageEngineBundleComponentRole
import com.muyuchat.core.download.ImageEngineBundleSpec
import java.io.File

/**
 * Converts the catalog-owned contract into the exact app/runtime contract stored beside downloaded
 * model bytes. The catalog is authoritative for new installs; built-in resolver templates remain an
 * old-package fallback only.
 */
internal fun materializeDownloadedImageExecutionProfile(
    bundle: ImageEngineBundleSpec,
    modelFingerprint: String
): ImageExecutionProfile? {
    val source = bundle.executionProfile ?: return null
    fun artifact(path: String?): ImageGraphArtifactContract? = path
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::ImageGraphArtifactContract)
    val promptExecutionAssets = bundle.requiredComponents
        .asSequence()
        .filter { component ->
            component.role in setOf(
                ImageEngineBundleComponentRole.TEXT_ENCODER,
                ImageEngineBundleComponentRole.TOKENIZER,
                ImageEngineBundleComponentRole.CONDITIONING
            )
        }
        .mapNotNull { component ->
            val sha256 = component.sha256?.trim()?.lowercase()
            val sizeBytes = component.expectedSizeBytes
            if (sha256 == null || sizeBytes == null) return@mapNotNull null
            ImageProfileAsset(
                relativePath = component.relativePath.replace('\\', '/'),
                fingerprint = sha256,
                sizeBytes = sizeBytes
            )
        }
        .sortedWith { left, right ->
            compareUtf8Unsigned(left.relativePath, right.relativePath)
        }
        .toList()

    val textEncoderLanguage = source.textEncoderLanguage?.let { declared ->
        val evidence = declared.evidence?.let { declaredEvidence ->
            fun resolvePinnedAsset(path: String, sha256: String, label: String): ImageProfileAsset {
                val expectedPath = path.replace('\\', '/')
                return promptExecutionAssets.singleOrNull { asset ->
                    asset.relativePath.replace('\\', '/').equals(expectedPath, ignoreCase = true) &&
                        asset.fingerprint.equals(sha256, ignoreCase = true)
                } ?: error(
                    "Text encoder language evidence $label must bind an exact downloaded text-encoder asset."
                )
            }
            val pinnedAsset = resolvePinnedAsset(
                declaredEvidence.textEncoderAssetPath,
                declaredEvidence.textEncoderAssetSha256,
                "primary graph"
            )
            ImageTextEncoderLanguageEvidence(
                evidenceId = declaredEvidence.evidenceId,
                evidenceSha256 = declaredEvidence.evidenceSha256,
                textEncoderAsset = pinnedAsset,
                auxiliaryAssets = declaredEvidence.auxiliaryAssets.mapIndexed { index, auxiliary ->
                    resolvePinnedAsset(auxiliary.relativePath, auxiliary.sha256, "auxiliary asset $index")
                },
                promptToEncoderAssets = declaredEvidence.promptToEncoderAssets.mapIndexed {
                        index,
                        declaredAsset ->
                    ImagePromptToEncoderAsset(
                        role = ImagePromptToEncoderAssetRole.valueOf(declaredAsset.role.name),
                        asset = resolvePinnedAsset(
                            declaredAsset.relativePath,
                            declaredAsset.sha256,
                            "prompt-to-encoder asset $index (${declaredAsset.role.name})"
                        )
                    )
                },
                semanticProof = declaredEvidence.semanticProof?.let { proof ->
                    ImageTextEncoderLanguageSemanticProof(
                        proofVersion = proof.proofVersion,
                        signerKeyId = proof.signerKeyId,
                        signerCertificateSha256 = proof.signerCertificateSha256,
                        signatureAlgorithm = proof.signatureAlgorithm,
                        payloadSha256 = proof.payloadSha256,
                        signatureBase64 = proof.signatureBase64
                    )
                }
            )
        }
        ImageTextEncoderLanguageContract(
            capability = ImageTextEncoderLanguageCapability.valueOf(declared.capability.name),
            supportedLanguages = declared.supportedLanguages
                .mapTo(linkedSetOf()) { language ->
                    ImageTextEncoderLanguage.valueOf(language.name)
                },
            evidence = evidence
        )
    }

    val defaults = source.defaults
    val profile = ImageExecutionProfile(
        profileId = source.profileId,
        profileRevision = source.profileRevision,
        modelFingerprint = modelFingerprint.trim().lowercase(),
        runtime = when (bundle.runtime) {
            ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP -> LocalImageRuntime.STABLE_DIFFUSION_CPP
            ImageEngineBundleRuntime.MNN_DIFFUSION -> LocalImageRuntime.MNN_DIFFUSION
            ImageEngineBundleRuntime.QNN_HTP -> LocalImageRuntime.QNN_HTP
        },
        family = LocalImageModelFamily.from(source.family.name),
        variant = ImageModelVariant.valueOf(source.variant.name),
        task = ImageTask.valueOf(source.task.name),
        provenance = ImageProfileProvenance(
            primarySource = ImageProfileSource.MANIFEST,
            sources = listOf(ImageProfileSource.MANIFEST),
            recommendationId = bundle.recommendationId
        ),
        tokenizer = ImageTokenizerContract(
            backend = ImageTokenizerBackend.valueOf(source.tokenizer.backend.name),
            assets = promptExecutionAssets,
            bosId = source.tokenizer.bosId,
            eosId = source.tokenizer.eosId,
            padId = source.tokenizer.padId,
            maxLength = source.tokenizer.maxLength,
            clip1PadRule = ImageClipPadRule.valueOf(source.tokenizer.clip1PadRule.name),
            clip2PadRule = source.tokenizer.clip2PadRule?.let { ImageClipPadRule.valueOf(it.name) },
            supportsPromptWeighting = source.tokenizer.supportsPromptWeighting,
            supportsTextualInversion = source.tokenizer.supportsTextualInversion,
            separateNegativePrompt = source.tokenizer.separateNegativePrompt
        ),
        conditioning = ImageConditioningContract(
            diskDataType = ImageEmbeddingDiskDataType.valueOf(source.conditioning.diskDataType.name),
            textEncoderInputShape = source.conditioning.textEncoderInputShape,
            textEncoderOutputShapes = source.conditioning.textEncoderOutputShapes,
            conversionStrategy = ImageEmbeddingConversionStrategy.valueOf(
                source.conditioning.conversionStrategy.name
            ),
            dualEncoder = source.conditioning.dualEncoder,
            pooledOutput = source.conditioning.pooledOutput,
            concatenationOrder = source.conditioning.concatenationOrder
        ),
        scheduler = ImageSchedulerContract(
            algorithm = ImageSchedulerAlgorithm.valueOf(source.scheduler.algorithm.name),
            predictionType = ImagePredictionType.valueOf(source.scheduler.predictionType.name),
            numTrainTimesteps = source.scheduler.numTrainTimesteps,
            noiseSchedule = ImageNoiseSchedule.valueOf(source.scheduler.noiseSchedule.name),
            betaStart = source.scheduler.betaStart,
            betaEnd = source.scheduler.betaEnd,
            timestepSpacing = ImageTimestepSpacing.valueOf(source.scheduler.timestepSpacing.name),
            stepsOffset = source.scheduler.stepsOffset,
            setAlphaToOne = source.scheduler.setAlphaToOne,
            skipPrkSteps = source.scheduler.skipPrkSteps,
            finalSigmaType = ImageFinalSigmaType.valueOf(source.scheduler.finalSigmaType.name),
            clipSample = source.scheduler.clipSample,
            clipSampleRange = source.scheduler.clipSampleRange,
            thresholding = source.scheduler.thresholding,
            eta = source.scheduler.eta,
            lowerOrderFinal = source.scheduler.lowerOrderFinal,
            initNoiseSigma = source.scheduler.initNoiseSigma,
            scaleModelInput = source.scheduler.scaleModelInput,
            order = source.scheduler.order,
            defaultSteps = source.scheduler.defaultSteps,
            minSteps = source.scheduler.minSteps,
            maxSteps = source.scheduler.maxSteps,
            rng = ImageRngContract.MT19937,
            seedBits = source.scheduler.seedBits
        ),
        latent = ImageLatentContract(
            channels = 4,
            downsampleFactor = 8,
            schedulerLayout = ImageTensorLayout.NCHW,
            graphLayout = ImageTensorLayout.NCHW,
            initialShape = listOf(1, 4, defaults.height / 8, defaults.width / 8),
            dataType = ImageEmbeddingDiskDataType.FP32
        ),
        vae = ImageVaeContract(
            scalingLocation = ImageVaeScalingLocation.valueOf(source.vae.scalingLocation.name),
            scalingFactor = source.vae.scalingFactor,
            inputShape = source.vae.inputShape,
            outputShape = source.vae.outputShape,
            inputLayout = ImageTensorLayout.valueOf(source.vae.inputLayout.name),
            outputLayout = ImageTensorLayout.valueOf(source.vae.outputLayout.name),
            outputRange = ImagePixelRange.valueOf(source.vae.outputRange.name),
            channelOrder = ImageChannelOrder.valueOf(source.vae.channelOrder.name)
        ),
        graph = ImageGraphContract(
            textEncoder = artifact(source.graph.textEncoder),
            unet = artifact(source.graph.unet),
            vae = artifact(source.graph.vae),
            vaeEncoder = artifact(source.graph.vaeEncoder),
            controlNet = artifact(source.graph.controlNet),
            schedulerSidecar = source.graph.schedulerSidecar,
            tokenizerSidecar = source.graph.tokenizerSidecar,
            configSidecars = source.graph.configSidecars,
            qnnSdk = source.graph.qnnSdk,
            htpArch = source.graph.htpArch,
            workerStrategy = ImageWorkerStrategy.valueOf(source.graph.workerStrategy.name)
        ),
        defaults = ImageGenerationDefaults(
            width = defaults.width,
            height = defaults.height,
            steps = defaults.steps,
            cfgScale = defaults.cfgScale,
            seed = defaults.seed,
            useCfg = defaults.useCfg,
            defaultPrompt = defaults.defaultPrompt,
            defaultNegativePrompt = defaults.defaultNegativePrompt
        ),
        capabilities = ImageGenerationCapabilities(
            supportedSchedulers = source.capabilities.supportedSchedulers
                .mapTo(linkedSetOf()) { ImageSchedulerAlgorithm.valueOf(it.name) },
            minWidth = source.capabilities.minWidth,
            maxWidth = source.capabilities.maxWidth,
            minHeight = source.capabilities.minHeight,
            maxHeight = source.capabilities.maxHeight,
            widthMultiple = source.capabilities.widthMultiple,
            heightMultiple = source.capabilities.heightMultiple,
            supportsNegativePrompt = source.capabilities.supportsNegativePrompt,
            supportsPromptWeighting = source.capabilities.supportsPromptWeighting,
            supportsTextualInversion = source.capabilities.supportsTextualInversion,
            requiresControlImage = source.capabilities.requiresControlImage,
            requiresInputImage = source.capabilities.requiresInputImage,
            supportsMask = source.capabilities.supportsMask,
            supportsClipSkip = source.capabilities.supportsClipSkip,
            supportsVaeTiling = source.capabilities.supportsVaeTiling,
            supportsUltraFix = source.capabilities.supportsUltraFix,
            ultraFixMinWidth = source.capabilities.ultraFixMinWidth,
            ultraFixMaxWidth = source.capabilities.ultraFixMaxWidth,
            ultraFixMinHeight = source.capabilities.ultraFixMinHeight,
            ultraFixMaxHeight = source.capabilities.ultraFixMaxHeight,
            ultraFixWidthMultiple = source.capabilities.ultraFixWidthMultiple,
            ultraFixHeightMultiple = source.capabilities.ultraFixHeightMultiple,
            ultraFixRequiredTileSize = source.capabilities.ultraFixRequiredTileSize,
            supportsLivePreview = source.capabilities.supportsLivePreview,
            supportsLora = source.capabilities.supportsLora,
            maxBatchCount = source.capabilities.maxBatchCount
        ),
        textEncoderLanguage = textEncoderLanguage
    )
    val validation = ImageExecutionProfileValidator.validate(profile, modelFingerprint)
    require(validation.valid) {
        validation.issues.joinToString(" ") { issue ->
            "${issue.code}:${issue.field}:${issue.message}"
        }
    }
    return profile
}

/**
 * Persists the exact files that native prompt conditioning will consume. Publisher pins are
 * reused without a second host-side hash; files discovered only after archive expansion are
 * hashed once here. Native execution independently verifies every pin before and after use.
 */
internal fun ImageExecutionProfile.withDownloadedTextualInversionConsumerPins(
    bundleRoot: File,
    primaryModel: File
): ImageExecutionProfile {
    if (!capabilities.supportsTextualInversion || !tokenizer.supportsTextualInversion) return this
    if (runtime in setOf(LocalImageRuntime.QNN_HTP, LocalImageRuntime.MNN_DIFFUSION)) {
        require(hasHostWritableClipTextualInversionTopology()) {
            "Downloaded profile advertises textual inversion without a host-writable CLIP topology."
        }
    }

    val root = bundleRoot.canonicalFile
    val primary = primaryModel.canonicalFile
    require(root.isDirectory && primary.isFile && primary.path.startsWith(root.path + File.separator)) {
        "Downloaded textual-inversion consumer root or primary model is invalid."
    }
    val declaredByLabel = tokenizer.assets.associateBy { asset ->
        asset.relativePath.replace('\\', '/').trim()
    }
    val pins = resolveTextualInversionConsumerAssetFiles(
        profile = this,
        primaryModel = primary,
        root = root
    )
        .map(File::getCanonicalFile)
        .distinctBy(File::getPath)
        .map { file ->
            val label = file.relativeTo(root).invariantSeparatorsPath
            val installedPin = if (file == primary) {
                ImageProfileAsset(label, modelFingerprint.lowercase(), file.length())
            } else {
                declaredByLabel[label]?.takeIf { asset -> asset.sizeBytes != null }
            }
            val descriptor = captureImageExecutionAssetDescriptor(
                bundleRoot = root,
                source = file,
                installedPin = installedPin
            )
            ImageProfileAsset(
                relativePath = descriptor.label,
                fingerprint = descriptor.sha256,
                sizeBytes = descriptor.sizeBytes
            )
        }
        .sortedWith { left, right ->
            compareUtf8Unsigned(left.relativePath, right.relativePath)
        }

    // The complete language-evidence closure is part of prompt semantics, not textual inversion
    // itself. Keep every graph/sidecar in the request-scoped asset snapshot even when no inversion
    // consumer needs it.
    val pinsWithLanguageEvidence = textEncoderLanguage?.evidence?.consumedAssets()
        ?.let { languageAssets ->
            languageAssets.forEach { languageAsset ->
                val matching = pins.firstOrNull { candidate ->
                    candidate.relativePath.replace('\\', '/').equals(
                        languageAsset.relativePath.replace('\\', '/'),
                        ignoreCase = true
                    )
                }
                require(
                    matching == null ||
                        (matching.fingerprint.equals(languageAsset.fingerprint, ignoreCase = true) &&
                            matching.sizeBytes == languageAsset.sizeBytes)
                ) {
                    "Textual-inversion capture changed a text-encoder language evidence asset."
                }
            }
            (pins + languageAssets)
                .distinctBy { asset -> asset.relativePath.replace('\\', '/').lowercase() }
                .sortedWith { left, right -> compareUtf8Unsigned(left.relativePath, right.relativePath) }
        }
        ?: pins

    val pinnedProfile = copy(tokenizer = tokenizer.copy(assets = pinsWithLanguageEvidence))
    val validation = ImageExecutionProfileValidator.validate(pinnedProfile, modelFingerprint)
    require(validation.valid) {
        validation.issues.joinToString(" ") { issue ->
            "${issue.code}:${issue.field}:${issue.message}"
        }
    }
    return pinnedProfile
}

package com.muyuchat.mca

/**
 * The layer stack `ImageExecutionProfileResolver.resolve` applies on top of a base
 * profile -- sidecar, package behavior, capability discovery, user overrides -- together
 * with the scheduler reconciliation and the resolved execution contract derived from the
 * final profile.
 *
 * Moved verbatim out of the resolver object so the merge order, the fallback paths and the
 * rejection conditions of each layer can be read in one place. Nothing here inspects the
 * device, a chipset, a model name or a validation list: the only consumer of the device
 * hint is a provenance note when no device-local profile is known, and that note is
 * advisory -- real native load and execution stay authoritative.
 */

private const val IMAGE_EXECUTION_PROFILE_DEFAULT_BETA_START = 0.00085

private const val IMAGE_EXECUTION_PROFILE_DEFAULT_BETA_END = 0.012

internal fun imageExecutionProfileApplySidecar(
    base: ImageExecutionProfile,
    sidecar: ImageProfileSidecar,
    sources: MutableMap<String, ImageProfileSource>
): ImageExecutionProfile {
    fun mark(field: String, present: Boolean) {
        if (present) sources[field] = ImageProfileSource.SIDECAR
    }
    mark("profileId", sidecar.profileId != null)
    mark("profileRevision", sidecar.profileRevision != null)
    mark("family", sidecar.family != null)
    mark("variant", sidecar.variant != null)
    mark("task", sidecar.task != null)
    mark("tokenizer", sidecar.tokenizer != null)
    mark("conditioning", sidecar.conditioning != null)
    mark("scheduler", sidecar.scheduler != null)
    mark("latent", sidecar.latent != null)
    mark("vae", sidecar.vae != null)
    mark("graph", sidecar.graph != null)
    mark("defaults", sidecar.defaults != null)
    mark("capabilities", sidecar.capabilities != null)
    val replaced = base.copy(
        profileId = sidecar.profileId ?: base.profileId,
        profileRevision = sidecar.profileRevision ?: base.profileRevision,
        family = sidecar.family ?: base.family,
        variant = sidecar.variant ?: base.variant,
        task = sidecar.task ?: base.task,
        tokenizer = sidecar.tokenizer ?: base.tokenizer,
        conditioning = sidecar.conditioning ?: base.conditioning,
        scheduler = sidecar.scheduler ?: base.scheduler,
        latent = sidecar.latent ?: base.latent,
        vae = sidecar.vae ?: base.vae,
        graph = sidecar.graph ?: base.graph,
        defaults = sidecar.defaults ?: base.defaults,
        capabilities = sidecar.capabilities ?: base.capabilities
    )
    return sidecar.behavior?.let { behavior ->
        imageExecutionProfileApplyPackageBehavior(replaced, behavior, ImageProfileSource.SIDECAR, sources)
    } ?: replaced
}

internal fun imageExecutionProfileApplyPackageBehavior(
    base: ImageExecutionProfile,
    behavior: ImagePackageBehaviorConfig,
    source: ImageProfileSource,
    sources: MutableMap<String, ImageProfileSource>
): ImageExecutionProfile {
    fun mark(field: String, present: Boolean) {
        if (present) sources[field] = source
    }

    mark("family", behavior.family != null)
    mark("variant", behavior.variant != null)
    mark("scheduler.algorithm", behavior.scheduler != null)
    mark("scheduler.defaultSteps", behavior.steps != null)
    mark("defaults.defaultPrompt", behavior.defaultPrompt != null)
    mark("defaults.defaultNegativePrompt", behavior.defaultNegativePrompt != null)
    mark("defaults.steps", behavior.steps != null)
    mark("defaults.cfgScale", behavior.cfgScale != null)
    mark("defaults.width", behavior.width != null)
    mark("defaults.height", behavior.height != null)
    mark("defaults.useCfg", behavior.useCfg != null || behavior.cfgScale != null)

    val width = behavior.width ?: base.defaults.width
    val height = behavior.height ?: base.defaults.height
    val dimensionsChanged = width != base.defaults.width || height != base.defaults.height
    if (dimensionsChanged) {
        sources["latent.initialShape"] = source
        sources["vae.inputShape"] = source
        sources["vae.outputShape"] = source
    }
    val cfgScale = behavior.cfgScale ?: base.defaults.cfgScale
    val useCfg = behavior.useCfg
        ?: behavior.cfgScale?.let { kotlin.math.abs(it - 1.0) > 1.0e-12 }
        ?: base.defaults.useCfg
    val scheduler = base.scheduler.imageExecutionProfileWithResolvedSchedulerAlgorithm(
        algorithm = behavior.scheduler ?: base.scheduler.algorithm,
        defaultSteps = behavior.steps ?: base.scheduler.defaultSteps
    )
    val resizedContracts = imageExecutionProfileResizedContracts(base, width, height)
    val resolvedCapabilities = if (dimensionsChanged &&
        base.capabilities.minWidth == base.capabilities.maxWidth &&
        base.capabilities.minHeight == base.capabilities.maxHeight
    ) {
        // An explicit package behavior is an intentional dimension override. Re-open the
        // ordinary SDCPP range so later user overrides remain compatible with that package.
        base.capabilities.copy(
            minWidth = 256,
            maxWidth = 1_536,
            minHeight = 256,
            maxHeight = 1_536
        )
    } else {
        base.capabilities
    }
    return base.copy(
        family = behavior.family ?: base.family,
        variant = behavior.variant ?: base.variant,
        scheduler = scheduler,
        latent = if (dimensionsChanged) resizedContracts.first else base.latent,
        vae = if (dimensionsChanged) resizedContracts.second else base.vae,
        capabilities = resolvedCapabilities,
        defaults = base.defaults.copy(
            width = width,
            height = height,
            steps = behavior.steps ?: base.defaults.steps,
            cfgScale = cfgScale,
            useCfg = useCfg,
            defaultPrompt = behavior.defaultPrompt ?: base.defaults.defaultPrompt,
            defaultNegativePrompt = behavior.defaultNegativePrompt
                ?: base.defaults.defaultNegativePrompt
        )
    )
}

private fun imageExecutionProfileResizedContracts(
    base: ImageExecutionProfile,
    width: Int,
    height: Int
): Pair<ImageLatentContract, ImageVaeContract> {
    val latentWidth = width / base.latent.downsampleFactor
    val latentHeight = height / base.latent.downsampleFactor
    return base.latent.copy(
        initialShape = imageExecutionProfileResizedTensorShape(
            layout = base.latent.schedulerLayout,
            current = base.latent.initialShape,
            channels = base.latent.channels,
            width = latentWidth,
            height = latentHeight
        )
    ) to base.vae.copy(
        inputShape = imageExecutionProfileResizedTensorShape(
            layout = base.vae.inputLayout,
            current = base.vae.inputShape,
            channels = base.latent.channels,
            width = latentWidth,
            height = latentHeight
        ),
        outputShape = imageExecutionProfileResizedTensorShape(
            layout = base.vae.outputLayout,
            current = base.vae.outputShape,
            channels = 3,
            width = width,
            height = height
        )
    )
}

private fun imageExecutionProfileResizedTensorShape(
    layout: ImageTensorLayout,
    current: List<Int>,
    channels: Int,
    width: Int,
    height: Int
): List<Int> = when (layout) {
    ImageTensorLayout.NCHW,
    ImageTensorLayout.BCHW -> listOf(1, channels, height, width)
    ImageTensorLayout.NHWC -> listOf(1, height, width, channels)
    else -> current
}

internal fun imageExecutionProfileApplyCapabilityDiscovery(
    base: ImageExecutionProfile,
    discovery: ImageCapabilityDiscovery
): ImageExecutionProfile {
    val tokenizer = discovery.tokenizer ?: base.tokenizer
    val discoveredCapabilities = discovery.capabilities ?: base.capabilities
    val capabilities = discoveredCapabilities.copy(
        supportsPromptWeighting = tokenizer.supportsPromptWeighting &&
            discoveredCapabilities.supportsPromptWeighting &&
            base.runtime in setOf(
                LocalImageRuntime.QNN_HTP,
                LocalImageRuntime.MNN_DIFFUSION,
                LocalImageRuntime.STABLE_DIFFUSION_CPP
            )
    )
    return base.copy(
        family = discovery.family ?: base.family,
        variant = discovery.variant ?: base.variant,
        task = discovery.task ?: base.task,
        tokenizer = tokenizer,
        conditioning = discovery.conditioning ?: base.conditioning,
        scheduler = discovery.scheduler ?: base.scheduler,
        latent = discovery.latent ?: base.latent,
        vae = discovery.vae ?: base.vae,
        graph = discovery.graph ?: base.graph,
        defaults = discovery.defaults ?: base.defaults,
        capabilities = capabilities
    )
}

internal fun imageExecutionProfileApplyUserOverrides(
    base: ImageExecutionProfile,
    overrides: ImageGenerationOverrides,
    sources: MutableMap<String, ImageProfileSource>
): ImageExecutionProfile {
    val schedulerChanged = overrides.scheduler != null || overrides.predictionType != null
    // Capability derivation depends on the scheduler that will actually run. In
    // particular, QNN UltraFix has a narrower sampler set than the regular graph. Resolve
    // the scheduler first so a user-selected PNDM request does not retain a stale UltraFix
    // claim from the profile's original sampler.
    val resolvedScheduler = base.scheduler.imageExecutionProfileWithResolvedSchedulerAlgorithm(
        algorithm = overrides.scheduler ?: base.scheduler.algorithm,
        predictionType = overrides.predictionType ?: base.scheduler.predictionType,
        defaultSteps = overrides.steps ?: base.scheduler.defaultSteps,
        allowLowStepSmoke = overrides.allowLowStepSmoke &&
            base.runtime in setOf(
                LocalImageRuntime.QNN_HTP,
                LocalImageRuntime.MNN_DIFFUSION
            )
    )
    val schedulerAdjustedBase = base.copy(scheduler = resolvedScheduler)
        .withTopologyDerivedQnnUltraFixCapability()
    val width = overrides.width ?: base.defaults.width
    val height = overrides.height ?: base.defaults.height
    val dimensionsChanged = width != base.defaults.width || height != base.defaults.height
    if (schedulerChanged) sources["scheduler"] = ImageProfileSource.USER_OVERRIDE
    if (overrides.steps != null) sources["defaults.steps"] = ImageProfileSource.USER_OVERRIDE
    if (overrides.cfgScale != null) sources["defaults.cfgScale"] = ImageProfileSource.USER_OVERRIDE
    if (overrides.useCfg != null) sources["defaults.useCfg"] = ImageProfileSource.USER_OVERRIDE
    if (overrides.width != null) sources["defaults.width"] = ImageProfileSource.USER_OVERRIDE
    if (overrides.height != null) sources["defaults.height"] = ImageProfileSource.USER_OVERRIDE
    if (overrides.seed != null) sources["defaults.seed"] = ImageProfileSource.USER_OVERRIDE
    if (overrides.negativePromptSpecified) sources["defaults.defaultNegativePrompt"] = ImageProfileSource.USER_OVERRIDE
    val resizeNativeTensorContracts = dimensionsChanged &&
        !(overrides.useUltraFixDimensionContract && base.runtime == LocalImageRuntime.QNN_HTP)
    if (resizeNativeTensorContracts) {
        sources["latent.initialShape"] = ImageProfileSource.USER_OVERRIDE
        sources["vae.inputShape"] = ImageProfileSource.USER_OVERRIDE
        sources["vae.outputShape"] = ImageProfileSource.USER_OVERRIDE
    }
    val effectiveCapabilities = if (overrides.useUltraFixDimensionContract) {
        if (!schedulerAdjustedBase.capabilities.supportsUltraFix) {
            throw ImageProfileResolutionException(
                ImageProfileValidationReport(
                    listOf(
                        ImageProfileValidationIssue(
                            code = "ULTRAFIX_EXECUTION_UNSUPPORTED",
                            field = "capabilities.supportsUltraFix",
                            message = "The resolved graph and sampler cannot execute UltraFix dimensions."
                        )
                    )
                )
            )
        }
        schedulerAdjustedBase.capabilities.copy(
            minWidth = schedulerAdjustedBase.capabilities.ultraFixMinWidth,
            maxWidth = schedulerAdjustedBase.capabilities.ultraFixMaxWidth,
            minHeight = schedulerAdjustedBase.capabilities.ultraFixMinHeight,
            maxHeight = schedulerAdjustedBase.capabilities.ultraFixMaxHeight,
            widthMultiple = schedulerAdjustedBase.capabilities.ultraFixWidthMultiple,
            heightMultiple = schedulerAdjustedBase.capabilities.ultraFixHeightMultiple
        )
    } else {
        schedulerAdjustedBase.capabilities
    }
    val resizedContracts = if (resizeNativeTensorContracts) {
        imageExecutionProfileResizedContracts(schedulerAdjustedBase, width, height)
    } else {
        schedulerAdjustedBase.latent to schedulerAdjustedBase.vae
    }
    return schedulerAdjustedBase.copy(
        latent = resizedContracts.first,
        vae = resizedContracts.second,
        capabilities = effectiveCapabilities,
        defaults = base.defaults.copy(
            width = width,
            height = height,
            steps = overrides.steps ?: base.defaults.steps,
            cfgScale = overrides.cfgScale ?: base.defaults.cfgScale,
            seed = overrides.seed ?: base.defaults.seed,
            useCfg = overrides.useCfg ?: base.defaults.useCfg,
            defaultNegativePrompt = if (overrides.negativePromptSpecified) {
                overrides.negativePrompt
            } else {
                base.defaults.defaultNegativePrompt
            }
        )
    )
}

/**
 * Selects an algorithm without carrying algorithm-specific state from the previous one.
 *
 * Profiles and package behavior can intentionally retain model-specific schedule values
 * such as timestep spacing, beta values, offsets, and alpha handling. The native scheduler
 * still requires the fields that define the selected algorithm to agree as one contract:
 * Euler scales model input, DPM++ is second order, and only PNDM consumes PRK warm-up state.
 */

private fun ImageSchedulerContract.imageExecutionProfileWithResolvedSchedulerAlgorithm(
    algorithm: ImageSchedulerAlgorithm,
    predictionType: ImagePredictionType = this.predictionType,
    defaultSteps: Int = this.defaultSteps,
    allowLowStepSmoke: Boolean = false
): ImageSchedulerContract {
    // Semantic smoke uses a deliberately tiny step count to exercise the complete native
    // graph/PNG path quickly. Keep the model's quality floor for every normal product call;
    // the internal marker is only set by the debug semantic smoke harness.
    val resolvedMinSteps = if (allowLowStepSmoke) {
        minOf(minSteps, defaultSteps)
    } else {
        minSteps
    }
    // applyUserOverrides is also the normal no-op merge path. Retain a complete scheduler
    // sidecar unchanged unless the algorithm itself changes; some native profiles declare
    // otherwise-unused compatibility values such as skipPrkSteps deliberately.
    if (algorithm == this.algorithm) {
        return copy(
            predictionType = predictionType,
            defaultSteps = defaultSteps,
            minSteps = resolvedMinSteps
        )
    }
    val isFlowMatch = algorithm == ImageSchedulerAlgorithm.FLOW_MATCH
    val wasFlowMatch = this.algorithm == ImageSchedulerAlgorithm.FLOW_MATCH
    val nonFlowPrediction = predictionType.takeUnless { it == ImagePredictionType.FLOW }
        ?: ImagePredictionType.EPSILON
    val noiseSchedule = when {
        isFlowMatch -> ImageNoiseSchedule.SIGMA
        this.noiseSchedule == ImageNoiseSchedule.SIGMA -> ImageNoiseSchedule.SCALED_LINEAR
        else -> this.noiseSchedule
    }
    val betaStart = when {
        isFlowMatch -> null
        wasFlowMatch && this.betaStart == null -> IMAGE_EXECUTION_PROFILE_DEFAULT_BETA_START
        else -> this.betaStart
    }
    val betaEnd = when {
        isFlowMatch -> null
        wasFlowMatch && this.betaEnd == null -> IMAGE_EXECUTION_PROFILE_DEFAULT_BETA_END
        else -> this.betaEnd
    }
    return copy(
        algorithm = algorithm,
        predictionType = if (isFlowMatch) ImagePredictionType.FLOW else nonFlowPrediction,
        noiseSchedule = noiseSchedule,
        betaStart = betaStart,
        betaEnd = betaEnd,
        skipPrkSteps = if (algorithm == ImageSchedulerAlgorithm.PNDM_PLMS) {
            skipPrkSteps
        } else {
            false
        },
        lowerOrderFinal = if (algorithm == ImageSchedulerAlgorithm.DPMPP_2M) {
            lowerOrderFinal
        } else {
            false
        },
        scaleModelInput = algorithm == ImageSchedulerAlgorithm.EULER ||
            algorithm == ImageSchedulerAlgorithm.EULER_A,
        order = if (algorithm == ImageSchedulerAlgorithm.DPMPP_2M) 2 else 1,
        defaultSteps = defaultSteps,
        minSteps = resolvedMinSteps
    )
}

internal fun imageExecutionProfileResolvedExecution(profile: ImageExecutionProfile): ImageResolvedExecution {
    val timetableCount = profile.scheduler.expectedTimestepCount(profile.defaults.steps)
    val branches = if (profile.defaults.useCfg) 2 else 1
    val tokenCount = when {
        profile.family == LocalImageModelFamily.SANA &&
            profile.tokenizer.backend == ImageTokenizerBackend.MNN_MTOK -> {
            // Sana reports the sequence axis consumed by each conditioning
            // branch. Positive and negative branches are separate executions,
            // so adding both 256-token sequences would misstate the native
            // contract as 512 tokens.
            profile.conditioning.textEncoderInputShape.getOrNull(1)
                ?.takeIf { it > 0 }
                ?: profile.tokenizer.maxLength
        }
        profile.runtime == LocalImageRuntime.MNN_DIFFUSION -> {
            // MNN SD executes one tokenizer sequence per active conditioning branch.
            // Declaring a separate negative prompt describes capability, not an always-on
            // branch when CFG has been disabled for this request.
            profile.tokenizer.maxLength * branches
        }
        profile.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP -> {
            // stable-diffusion.cpp only prepares the negative conditioner
            // when CFG actually executes. This is the resolved minimum
            // branch capacity; native reports the prompt-dependent token
            // count separately after real conditioning runs.
            profile.tokenizer.maxLength * branches
        }
        profile.runtime == LocalImageRuntime.QNN_HTP &&
            profile.graph.workerStrategy == ImageWorkerStrategy.SPLIT_UNET_VAE -> {
            // Isolated SDXL QNN phases consume exactly the branches that
            // the split UNet will execute. Shared QNN topologies retain
            // their existing fixed dual-branch payload below.
            profile.tokenizer.maxLength * branches
        }
        profile.runtime == LocalImageRuntime.QNN_HTP -> {
            // QNN packages consume the fixed negative/positive CLIP
            // payload contract even when a distilled UNet executes only
            // the positive branch.
            profile.tokenizer.maxLength * 2
        }
        else -> profile.tokenizer.maxLength *
            if (profile.tokenizer.separateNegativePrompt) 2 else 1
    }
    val graphName = profile.graph.unet?.graphName
        ?: profile.graph.controlNet?.graphName
        ?: "runtime-native"
    return ImageResolvedExecution(
        profileId = profile.profileId,
        profileRevision = profile.profileRevision,
        modelFingerprint = profile.modelFingerprint,
        runtime = profile.runtime,
        scheduler = profile.scheduler.algorithm,
        predictionType = profile.scheduler.predictionType,
        steps = profile.defaults.steps,
        timetableCount = timetableCount,
        unetExecutionCount = timetableCount * branches,
        cfgScale = profile.defaults.cfgScale,
        useCfg = profile.defaults.useCfg,
        unconditionalBranch = profile.defaults.useCfg,
        tokenizerBackend = profile.tokenizer.backend,
        tokenCount = tokenCount,
        promptWeightingSupported =
            profile.tokenizer.supportsPromptWeighting &&
                profile.capabilities.supportsPromptWeighting,
        embeddingDiskDataType = profile.conditioning.diskDataType,
        vaeScalingLocation = profile.vae.scalingLocation,
        vaeScalingFactor = profile.vae.scalingFactor,
        width = profile.defaults.width,
        height = profile.defaults.height,
        seed = profile.defaults.seed,
        graphName = graphName,
        fallback = false
    )
}

internal fun imageExecutionProfileMarkAllFields(
    target: MutableMap<String, ImageProfileSource>,
    source: ImageProfileSource
) {
    listOf(
        "profileId",
        "profileRevision",
        "runtime",
        "family",
        "variant",
        "task",
        "tokenizer",
        "conditioning",
        "scheduler",
        "latent",
        "vae",
        "graph",
        "defaults",
        "capabilities"
    ).forEach { field -> target[field] = source }
}

internal fun imageExecutionProfileHasUserOverrides(value: ImageGenerationOverrides): Boolean =
    value.expectedProfileId != null ||
        value.expectedProfileRevision != null ||
        value.scheduler != null ||
        value.predictionType != null ||
        value.steps != null ||
        value.cfgScale != null ||
        value.useCfg != null ||
        value.width != null ||
        value.height != null ||
        value.useUltraFixDimensionContract ||
        value.seed != null ||
        value.negativePromptSpecified

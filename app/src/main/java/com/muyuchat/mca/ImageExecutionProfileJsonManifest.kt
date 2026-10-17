package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val DEFAULT_SCHEDULER_SIDECAR = "scheduler/scheduler_config.json"
private const val DEFAULT_TOKENIZER_SIDECAR = "tokenizer/tokenizer_config.json"
private const val DEFAULT_BEHAVIOR_SIDECAR = "config.json"
/** Parses partial generation behavior without requiring a full execution profile. */
internal fun ImageExecutionProfileJson.parseManifestBehavior(manifest: JSONObject): ImagePackageBehaviorConfig? {
    var behavior = parseBehaviorFields(manifest, "manifest")
    listOf(
        "modelConfig",
        "model_config",
        "generation",
        "generationConfig",
        "generation_config",
        "generationDefaults",
        "generation_defaults",
        "defaults"
    ).forEach { field ->
        manifest.optionalObject(field)?.let { nested ->
            behavior = mergeBehavior(behavior, parseBehaviorFields(nested, "manifest.$field"))
        }
    }
    return behavior
}

internal fun ImageExecutionProfileJson.parseSidecars(
    bundleRoot: File,
    schedulerRelativePath: String = DEFAULT_SCHEDULER_SIDECAR,
    tokenizerRelativePath: String? = DEFAULT_TOKENIZER_SIDECAR,
    behaviorRelativePaths: List<String> = listOf(DEFAULT_BEHAVIOR_SIDECAR)
): ImageProfileSidecar? {
    val schedulerFile = safeBundleFile(bundleRoot, schedulerRelativePath, "schedulerSidecar")
    val scheduler = schedulerFile.takeIf(File::isFile)?.let { file ->
        parseSchedulerConfig(readJsonFile(file, schedulerRelativePath))
    }
    val tokenizer = tokenizerRelativePath?.let { relativePath ->
        safeBundleFile(bundleRoot, relativePath, "tokenizerSidecar")
            .takeIf(File::isFile)
            ?.let { file -> parseTokenizerConfig(readJsonFile(file, relativePath)) }
    }
    var behavior: ImagePackageBehaviorConfig? = null
    behaviorRelativePaths.distinct().forEachIndexed { index, relativePath ->
        val file = safeBundleFile(bundleRoot, relativePath, "behaviorSidecars[$index]")
        if (file.isFile) {
            behavior = mergeBehavior(
                behavior,
                parsePackageBehaviorConfig(readJsonFile(file, relativePath), relativePath)
            )
        }
    }
    if (scheduler == null && tokenizer == null && behavior == null) return null
    return ImageProfileSidecar(
        scheduler = scheduler,
        tokenizer = tokenizer,
        behavior = behavior
    )
}

internal fun ImageExecutionProfileJson.parsePackageBehaviorConfig(
    json: JSONObject,
    fieldPrefix: String = "config"
): ImagePackageBehaviorConfig? {
    var behavior = parseBehaviorFields(json, fieldPrefix)
    listOf(
        "generation",
        "generationConfig",
        "generation_config",
        "generationDefaults",
        "generation_defaults",
        "defaults"
    ).forEach { field ->
        json.optionalObject(field)?.let { nested ->
            behavior = mergeBehavior(behavior, parseBehaviorFields(nested, "$fieldPrefix.$field"))
        }
    }
    return behavior
}

internal fun ImageExecutionProfileJson.parseSchedulerConfig(json: JSONObject): ImageSchedulerContract {
    val className = json.optionalStrictString("_class_name")
    val declaredAlgorithm = json.optionalStrictString("algorithm")
        ?: json.optionalStrictString("scheduler")
        ?: className
        ?: throw formatError("scheduler.algorithm", "Scheduler algorithm is required.")
    val pndmPublisherConfigUsesEulerTarget =
        className?.equals("PNDMScheduler", ignoreCase = true) == true &&
            json.optionalStrictString("prediction_type") == null &&
            json.optionalStrictString("timestep_spacing") == null
    val algorithm = if (pndmPublisherConfigUsesEulerTarget) {
        ImageSchedulerAlgorithm.EULER
    } else {
        schedulerAlgorithm(
            declared = declaredAlgorithm,
            algorithmType = json.optionalStrictString("algorithm_type"),
            solverOrder = json.optionalStrictInt("solver_order")
        )
    }
    val flowScheduler = algorithm == ImageSchedulerAlgorithm.FLOW_MATCH
    val predictionType = json.optionalStrictString("prediction_type")?.let { value ->
        predictionType(value, "scheduler.prediction_type")
    } ?: when {
        pndmPublisherConfigUsesEulerTarget -> ImagePredictionType.EPSILON
        flowScheduler -> ImagePredictionType.FLOW
        else -> throw formatError("prediction_type", "prediction_type is required for this scheduler.")
    }
    val declaredNoiseSchedule = json.optionalStrictString("beta_schedule")?.let(::noiseSchedule)
    val noiseSchedule = when {
        flowScheduler && declaredNoiseSchedule == null -> ImageNoiseSchedule.SIGMA
        flowScheduler && declaredNoiseSchedule != ImageNoiseSchedule.SIGMA ->
            throw formatError("scheduler.beta_schedule", "Flow schedulers require a sigma noise schedule.")
        declaredNoiseSchedule != null -> declaredNoiseSchedule
        else -> throw formatError("scheduler.beta_schedule", "beta_schedule is required for this scheduler.")
    }
    val betaStart = json.optionalStrictDouble("beta_start")
    val betaEnd = json.optionalStrictDouble("beta_end")
    if (flowScheduler && (betaStart != null || betaEnd != null)) {
        throw formatError("scheduler.beta_schedule", "Flow schedulers must not declare diffusion beta endpoints.")
    }
    val timestepSpacing = json.optionalStrictString("timestep_spacing")?.let(::timestepSpacing)
        ?: when {
            pndmPublisherConfigUsesEulerTarget -> ImageTimestepSpacing.LINSPACE
            flowScheduler -> ImageTimestepSpacing.LEADING
            algorithm == ImageSchedulerAlgorithm.DDIM -> ImageTimestepSpacing.LEADING
            else -> throw formatError("timestep_spacing", "timestep_spacing is required for this scheduler.")
        }
    val defaultSteps = json.optionalStrictInt("default_steps") ?: 20
    val minSteps = json.optionalStrictInt("min_steps") ?: 1
    val maxSteps = json.optionalStrictInt("max_steps") ?: 100
    return ImageSchedulerContract(
        algorithm = algorithm,
        predictionType = predictionType,
        numTrainTimesteps = json.optionalStrictInt("num_train_timesteps") ?: 1_000,
        noiseSchedule = noiseSchedule,
        betaStart = betaStart,
        betaEnd = betaEnd,
        timestepSpacing = timestepSpacing,
        stepsOffset = json.optionalStrictInt("steps_offset")
            ?: if (flowScheduler) 0 else json.requiredStrictInt("steps_offset"),
        setAlphaToOne = json.optionalStrictBoolean("set_alpha_to_one")
            ?: if (flowScheduler) false else json.requiredStrictBoolean("set_alpha_to_one"),
        skipPrkSteps = json.optionalStrictBoolean("skip_prk_steps")
            ?: if (flowScheduler) false else json.requiredStrictBoolean("skip_prk_steps"),
        finalSigmaType = json.optionalStrictString("final_sigma_type")?.let { value ->
            enumValue<ImageFinalSigmaType>(value, "scheduler.final_sigma_type")
        } ?: ImageFinalSigmaType.ZERO,
        clipSample = json.optionalStrictBoolean("clip_sample") ?: false,
        clipSampleRange = json.optionalStrictDouble("clip_sample_range") ?: 1.0,
        thresholding = json.optionalStrictBoolean("thresholding") ?: false,
        eta = json.optionalStrictDouble("eta") ?: 0.0,
        lowerOrderFinal = json.optionalStrictBoolean("lower_order_final") ?: true,
        initNoiseSigma = json.optionalStrictDouble("init_noise_sigma") ?: 1.0,
        scaleModelInput = json.optionalStrictBoolean("scale_model_input")
            ?: (algorithm == ImageSchedulerAlgorithm.EULER || algorithm == ImageSchedulerAlgorithm.EULER_A),
        order = json.optionalStrictInt("solver_order")
            ?: if (algorithm == ImageSchedulerAlgorithm.DPMPP_2M) 2 else 1,
        defaultSteps = defaultSteps,
        minSteps = minSteps,
        maxSteps = maxSteps,
        rng = ImageRngContract.MT19937,
        seedBits = json.optionalStrictInt("seed_bits") ?: 32
    ).also { scheduler ->
        if (
            scheduler.defaultSteps !in scheduler.minSteps..scheduler.maxSteps ||
            scheduler.minSteps <= 0 || scheduler.maxSteps < scheduler.minSteps
        ) {
            throw formatError("scheduler.steps", "Scheduler step bounds are invalid.")
        }
    }
}

internal fun ImageExecutionProfileJson.parseTokenizerConfig(json: JSONObject): ImageTokenizerContract {
    val backend = json.optionalStrictString("backend")?.let { value ->
        enumValue<ImageTokenizerBackend>(value, "tokenizer.backend")
    } ?: ImageTokenizerBackend.TOKENIZERS_CPP
    val maxLength = json.requiredStrictInt("model_max_length")
    if (maxLength <= 0) throw formatError("tokenizer.model_max_length", "Tokenizer max length must be positive.")
    return ImageTokenizerContract(
        backend = backend,
        bosId = clipTokenId(json, "bos_token_id", "bos_token", 49_406),
        eosId = clipTokenId(json, "eos_token_id", "eos_token", 49_407),
        padId = clipTokenId(json, "pad_token_id", "pad_token", 49_407),
        maxLength = maxLength,
        unicodeNormalization = json.optionalStrictString("unicode_normalization")?.let { value ->
            enumValue<ImageUnicodeNormalization>(value, "tokenizer.unicode_normalization")
        } ?: ImageUnicodeNormalization.NFC,
        lowercase = json.optionalStrictBoolean("lowercase") ?: true,
        preTokenizer = json.optionalStrictString("pre_tokenizer") ?: "model_declared",
        postProcessor = json.optionalStrictString("post_processor") ?: "model_declared",
        clip1PadRule = json.optionalStrictString("clip1_pad_rule")?.let { value ->
            enumValue<ImageClipPadRule>(value, "tokenizer.clip1_pad_rule")
        } ?: ImageClipPadRule.MODEL_DECLARED,
        clip2PadRule = json.optionalStrictString("clip2_pad_rule")?.let { value ->
            enumValue<ImageClipPadRule>(value, "tokenizer.clip2_pad_rule")
        },
        supportsPromptWeighting = json.optionalStrictBoolean("supports_prompt_weighting") ?: false,
        supportsTextualInversion = json.optionalStrictBoolean("supports_textual_inversion") ?: false,
        separateNegativePrompt = json.optionalStrictBoolean("separate_negative_prompt") ?: true
    )
}

private fun mergeBehavior(
    lowerPriority: ImagePackageBehaviorConfig?,
    higherPriority: ImagePackageBehaviorConfig?
): ImagePackageBehaviorConfig? = when {
    lowerPriority == null -> higherPriority
    higherPriority == null -> lowerPriority
    else -> lowerPriority.overlay(higherPriority)
}


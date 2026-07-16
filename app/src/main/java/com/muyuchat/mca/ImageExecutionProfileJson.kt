package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class ImageExecutionProfileJsonException(
    val code: String,
    val field: String,
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException("$code:$field:$message", cause)

/**
 * Strict JSON boundary for versioned image execution profiles and standard
 * package sidecars. Missing sidecars are advisory; malformed present files are
 * concrete package errors and are never converted into a device restriction.
 */
internal object ImageExecutionProfileJson {
    private const val MANIFEST_PROFILE_FIELD = "executionProfile"
    private const val DEFAULT_SCHEDULER_SIDECAR = "scheduler/scheduler_config.json"
    private const val DEFAULT_TOKENIZER_SIDECAR = "tokenizer/tokenizer_config.json"

    fun parseManifest(manifest: JSONObject): ImageExecutionProfile? {
        if (!manifest.has(MANIFEST_PROFILE_FIELD) || manifest.isNull(MANIFEST_PROFILE_FIELD)) return null
        val profileJson = manifest.strictObject(MANIFEST_PROFILE_FIELD)
        return parseProfile(profileJson)
    }

    fun parseManifestFile(manifestFile: File): ImageExecutionProfile? {
        if (!manifestFile.isFile) return null
        return parseManifest(readJsonFile(manifestFile, "manifest"))
    }

    fun parseSidecars(
        bundleRoot: File,
        schedulerRelativePath: String = DEFAULT_SCHEDULER_SIDECAR,
        tokenizerRelativePath: String = DEFAULT_TOKENIZER_SIDECAR
    ): ImageProfileSidecar? {
        val schedulerFile = safeBundleFile(bundleRoot, schedulerRelativePath, "schedulerSidecar")
        val tokenizerFile = safeBundleFile(bundleRoot, tokenizerRelativePath, "tokenizerSidecar")
        val scheduler = schedulerFile.takeIf(File::isFile)?.let { file ->
            parseSchedulerConfig(readJsonFile(file, schedulerRelativePath))
        }
        val tokenizer = tokenizerFile.takeIf(File::isFile)?.let { file ->
            parseTokenizerConfig(readJsonFile(file, tokenizerRelativePath))
        }
        if (scheduler == null && tokenizer == null) return null
        return ImageProfileSidecar(
            scheduler = scheduler,
            tokenizer = tokenizer
        )
    }

    fun parseSchedulerConfig(json: JSONObject): ImageSchedulerContract {
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
        val predictionType = json.optionalStrictString("prediction_type")?.let { value ->
            predictionType(value, "scheduler.prediction_type")
        } ?: if (pndmPublisherConfigUsesEulerTarget) {
            ImagePredictionType.EPSILON
        } else {
            throw formatError("prediction_type", "prediction_type is required for this scheduler.")
        }
        val noiseSchedule = noiseSchedule(json.requiredStrictString("beta_schedule"))
        val timestepSpacing = json.optionalStrictString("timestep_spacing")?.let(::timestepSpacing)
            ?: when {
                pndmPublisherConfigUsesEulerTarget -> ImageTimestepSpacing.LINSPACE
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
            betaStart = json.optionalStrictDouble("beta_start"),
            betaEnd = json.optionalStrictDouble("beta_end"),
            timestepSpacing = timestepSpacing,
            stepsOffset = json.requiredStrictInt("steps_offset"),
            setAlphaToOne = json.requiredStrictBoolean("set_alpha_to_one"),
            skipPrkSteps = json.requiredStrictBoolean("skip_prk_steps"),
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

    fun parseTokenizerConfig(json: JSONObject): ImageTokenizerContract {
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

    fun parseProfile(json: JSONObject): ImageExecutionProfile {
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
        val validation = ImageExecutionProfileValidator.validate(profile)
        if (!validation.valid) {
            val first = validation.issues.first()
            throw ImageExecutionProfileJsonException(
                code = "PROFILE_VALIDATION_FAILED",
                field = first.field,
                message = validation.issues.joinToString(" ") { issue -> "${issue.code}:${issue.message}" }
            )
        }
        return profile
    }

    private fun parseProvenance(json: JSONObject): ImageProfileProvenance = ImageProfileProvenance(
        primarySource = enumValue(json.requiredStrictString("primarySource"), "provenance.primarySource"),
        sources = json.requiredArray("sources").strictEnumList("provenance.sources"),
        recommendationId = json.optionalStrictString("recommendationId"),
        recommendationRevision = json.optionalStrictString("recommendationRevision"),
        notes = json.optionalArray("notes")?.strictStringList("provenance.notes").orEmpty()
    )

    private fun parseTokenizer(json: JSONObject): ImageTokenizerContract = ImageTokenizerContract(
        backend = enumValue(json.requiredStrictString("backend"), "tokenizer.backend"),
        assets = json.optionalArray("assets")?.strictObjectList("tokenizer.assets")?.mapIndexed { index, asset ->
            ImageProfileAsset(
                relativePath = asset.requiredStrictString("relativePath"),
                fingerprint = asset.requiredStrictString("fingerprint")
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

    private fun parseCapabilities(json: JSONObject): ImageGenerationCapabilities = ImageGenerationCapabilities(
        supportedSchedulers = json.requiredArray("supportedSchedulers")
            .strictEnumList<ImageSchedulerAlgorithm>("capabilities.supportedSchedulers")
            .toSet(),
        minWidth = json.requiredStrictInt("minWidth"),
        maxWidth = json.requiredStrictInt("maxWidth"),
        minHeight = json.requiredStrictInt("minHeight"),
        maxHeight = json.requiredStrictInt("maxHeight"),
        widthMultiple = json.requiredStrictInt("widthMultiple"),
        heightMultiple = json.requiredStrictInt("heightMultiple"),
        supportsNegativePrompt = json.requiredStrictBoolean("supportsNegativePrompt"),
        supportsPromptWeighting = json.requiredStrictBoolean("supportsPromptWeighting"),
        supportsTextualInversion = json.requiredStrictBoolean("supportsTextualInversion"),
        requiresControlImage = json.requiredStrictBoolean("requiresControlImage"),
        requiresInputImage = json.requiredStrictBoolean("requiresInputImage"),
        supportsMask = json.requiredStrictBoolean("supportsMask")
    )

    private fun clipTokenId(json: JSONObject, idField: String, tokenField: String, fixedId: Int): Int {
        json.optionalStrictInt(idField)?.let { return it }
        if (!json.has(tokenField) || json.isNull(tokenField)) {
            throw formatError("tokenizer.$idField", "$idField or $tokenField must declare the CLIP special token.")
        }
        return when (val token = json.get(tokenField)) {
            is String -> {
                if (token.isBlank()) throw formatError("tokenizer.$tokenField", "$tokenField must not be blank.")
                fixedId
            }
            is JSONObject -> token.optionalStrictInt("id") ?: fixedId
            else -> throw formatError("tokenizer.$tokenField", "$tokenField must be a string or token object.")
        }
    }

    private fun schedulerAlgorithm(
        declared: String,
        algorithmType: String?,
        solverOrder: Int?
    ): ImageSchedulerAlgorithm {
        val normalized = declared.trim().lowercase().replace(Regex("[^a-z0-9+]+"), "")
        val normalizedType = algorithmType.orEmpty().trim().lowercase().replace(Regex("[^a-z0-9+]+"), "")
        return when {
            "eulerancestral" in normalized || normalized == "eulera" -> ImageSchedulerAlgorithm.EULER_A
            "euler" in normalized -> ImageSchedulerAlgorithm.EULER
            "ddim" in normalized -> ImageSchedulerAlgorithm.DDIM
            "pndm" in normalized || "plms" in normalized -> ImageSchedulerAlgorithm.PNDM_PLMS
            "dpm++2m" in normalized || "dpmpp2m" in normalized -> ImageSchedulerAlgorithm.DPMPP_2M
            "dpmsolvermultistep" in normalized && "dpmsolver++" in normalizedType && solverOrder == 2 -> {
                ImageSchedulerAlgorithm.DPMPP_2M
            }
            else -> throw formatError("scheduler.algorithm", "Unsupported scheduler algorithm: $declared")
        }
    }

    private fun predictionType(value: String, field: String): ImagePredictionType = when (value.trim().lowercase()) {
        "epsilon" -> ImagePredictionType.EPSILON
        "v_prediction", "v-prediction" -> ImagePredictionType.V_PREDICTION
        "sample" -> ImagePredictionType.SAMPLE
        "flow", "flow_prediction" -> ImagePredictionType.FLOW
        else -> throw formatError(field, "Unsupported prediction type: $value")
    }

    private fun noiseSchedule(value: String): ImageNoiseSchedule = when (value.trim().lowercase()) {
        "linear" -> ImageNoiseSchedule.LINEAR
        "scaled_linear" -> ImageNoiseSchedule.SCALED_LINEAR
        "sigma", "karras" -> ImageNoiseSchedule.SIGMA
        "model_declared", "squaredcos_cap_v2" -> ImageNoiseSchedule.MODEL_DECLARED
        else -> throw formatError("scheduler.beta_schedule", "Unsupported beta schedule: $value")
    }

    private fun timestepSpacing(value: String): ImageTimestepSpacing = when (value.trim().lowercase()) {
        "leading" -> ImageTimestepSpacing.LEADING
        "trailing" -> ImageTimestepSpacing.TRAILING
        "linspace" -> ImageTimestepSpacing.LINSPACE
        else -> throw formatError("scheduler.timestep_spacing", "Unsupported timestep spacing: $value")
    }

    private fun readJsonFile(file: File, field: String): JSONObject = try {
        JSONObject(file.readText(Charsets.UTF_8))
    } catch (error: Exception) {
        throw ImageExecutionProfileJsonException(
            code = "PROFILE_JSON_INVALID",
            field = field,
            message = error.message ?: "Invalid JSON file.",
            cause = error
        )
    }

    private fun safeBundleFile(root: File, relativePath: String, field: String): File {
        requireSafePath(relativePath, field)
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { error ->
            throw ImageExecutionProfileJsonException("PROFILE_PATH_INVALID", field, "Bundle root is invalid.", error)
        }
        val candidate = runCatching { File(canonicalRoot, relativePath).canonicalFile }.getOrElse { error ->
            throw ImageExecutionProfileJsonException("PROFILE_PATH_INVALID", field, "Sidecar path is invalid.", error)
        }
        if (candidate.path != canonicalRoot.path && !candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            throw formatError(field, "Sidecar path escapes the bundle root.", "PROFILE_PATH_INVALID")
        }
        return candidate
    }

    private fun requireSafePath(path: String, field: String) {
        val normalized = path.trim().replace('\\', '/')
        val safe = normalized.isNotBlank() &&
            !normalized.startsWith('/') &&
            !normalized.startsWith("./") &&
            !Regex("^[A-Za-z]:").containsMatchIn(normalized) &&
            normalized.split('/').all { segment -> segment.isNotBlank() && segment != "." && segment != ".." }
        if (!safe) throw formatError(field, "Path must be a safe bundle-relative path.", "PROFILE_PATH_INVALID")
    }

    private fun formatError(field: String, message: String, code: String = "PROFILE_FORMAT_INVALID") =
        ImageExecutionProfileJsonException(code, field, message)

    private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
        enumValues<T>().firstOrNull { candidate -> candidate.name.equals(value.trim(), ignoreCase = true) }
            ?: throw formatError(field, "Unsupported ${T::class.java.simpleName}: $value")

    private inline fun <reified T : Enum<T>> JSONArray.strictEnumList(field: String): List<T> =
        strictStringList(field).mapIndexed { index, value -> enumValue(value, "$field[$index]") }

    private fun JSONObject.requiredStrictString(name: String): String =
        optionalStrictString(name) ?: throw formatError(name, "$name is required and must be a non-blank string.")

    private fun JSONObject.optionalStrictString(name: String, preserveBlank: Boolean = false): String? {
        if (!has(name) || isNull(name)) return null
        val value = get(name)
        if (value !is String) throw formatError(name, "$name must be a string.")
        val normalized = value.trim()
        if (!preserveBlank && normalized.isBlank()) throw formatError(name, "$name must not be blank.")
        return normalized
    }

    private fun JSONObject.requiredStrictInt(name: String): Int =
        optionalStrictInt(name) ?: throw formatError(name, "$name is required and must be an integer.")

    private fun JSONObject.optionalStrictInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        val value = get(name)
        if (value !is Number) throw formatError(name, "$name must be an integer.")
        val number = value.toDouble()
        if (!number.isFinite() || number % 1.0 != 0.0 || number !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            throw formatError(name, "$name must be a finite 32-bit integer.")
        }
        return number.toInt()
    }

    private fun JSONObject.requiredStrictLong(name: String): Long =
        optionalStrictLong(name) ?: throw formatError(name, "$name is required and must be an integer.")

    private fun JSONObject.optionalStrictLong(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        val value = get(name)
        if (value !is Number) throw formatError(name, "$name must be an integer.")
        val number = value.toDouble()
        if (!number.isFinite() || number % 1.0 != 0.0 || number !in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
            throw formatError(name, "$name must be a finite integer.")
        }
        return value.toLong()
    }

    private fun JSONObject.requiredStrictDouble(name: String): Double =
        optionalStrictDouble(name) ?: throw formatError(name, "$name is required and must be numeric.")

    private fun JSONObject.optionalStrictDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        val value = get(name)
        if (value !is Number) throw formatError(name, "$name must be numeric.")
        return value.toDouble().also { number ->
            if (!number.isFinite()) throw formatError(name, "$name must be finite.")
        }
    }

    private fun JSONObject.requiredStrictBoolean(name: String): Boolean =
        optionalStrictBoolean(name) ?: throw formatError(name, "$name is required and must be boolean.")

    private fun JSONObject.optionalStrictBoolean(name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        val value = get(name)
        if (value !is Boolean) throw formatError(name, "$name must be boolean.")
        return value
    }

    private fun JSONObject.strictObject(name: String): JSONObject =
        optionalObject(name) ?: throw formatError(name, "$name is required and must be an object.")

    private fun JSONObject.optionalObject(name: String): JSONObject? {
        if (!has(name) || isNull(name)) return null
        return get(name) as? JSONObject ?: throw formatError(name, "$name must be an object.")
    }

    private fun JSONObject.requiredArray(name: String): JSONArray =
        optionalArray(name) ?: throw formatError(name, "$name is required and must be an array.")

    private fun JSONObject.optionalArray(name: String): JSONArray? {
        if (!has(name) || isNull(name)) return null
        return get(name) as? JSONArray ?: throw formatError(name, "$name must be an array.")
    }

    private fun JSONArray.strictStringList(field: String): List<String> = List(length()) { index ->
        val value = get(index)
        if (value !is String || value.isBlank()) throw formatError("$field[$index]", "Array item must be a non-blank string.")
        value.trim()
    }

    private fun JSONArray.strictPositiveIntList(field: String): List<Int> = List(length()) { index ->
        val value = get(index)
        val number = value as? Number ?: throw formatError("$field[$index]", "Shape item must be an integer.")
        val integer = number.toInt()
        if (number.toDouble() != integer.toDouble() || integer <= 0) {
            throw formatError("$field[$index]", "Shape item must be a positive integer.")
        }
        integer
    }

    private fun JSONArray.strictObjectList(field: String): List<JSONObject> = List(length()) { index ->
        get(index) as? JSONObject ?: throw formatError("$field[$index]", "Array item must be an object.")
    }

    private fun JSONArray.strictArrayList(field: String): List<JSONArray> = List(length()) { index ->
        get(index) as? JSONArray ?: throw formatError("$field[$index]", "Array item must be an array.")
    }
}

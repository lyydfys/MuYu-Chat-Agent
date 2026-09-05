package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject

internal object LocalImageWorkerProtocol {
    private const val VERSION = 5
    private const val INTERNAL_ALLOW_LOW_STEP_SMOKE = "allowLowStepSmoke"

    data class GenerateRequest(
        val requestId: String,
        val model: LocalImageModelRecord,
        val prompt: String,
        val options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        val batchLineage: ImageGenerationBatchLineage? = null
    )

    data class UpscaleRequest(
        val requestId: String,
        val input: LocalImagePreparedInput,
        val upscaler: LocalImagePreparedUpscaler,
        val targetScale: Int,
        val tileSize: Int,
        val threads: Int
    )

    data class ProgressEnvelope(
        val requestId: String,
        val workerPid: Int,
        val progress: LocalImageProgress
    )

    data class ResultEnvelope(
        val requestId: String,
        val workerPid: Int,
        val outputPath: String,
        val mimeType: String,
        val executionMetadataJson: String = "",
        val outputs: List<OutputEnvelope> = listOf(
            OutputEnvelope(index = 0, outputPath = outputPath, mimeType = mimeType, seed = null)
        )
    )

    data class OutputEnvelope(
        val index: Int,
        val outputPath: String,
        val mimeType: String,
        val seed: Long?
    )

    data class ErrorEnvelope(
        val requestId: String,
        val workerPid: Int,
        val code: String,
        val message: String
    )

    fun beginRequest(runtime: LocalImageRuntime): String =
        JSONObject()
            .put("version", VERSION)
            .put("runtime", runtime.name)
            .toString()

    fun parseBeginRuntime(raw: String): LocalImageRuntime =
        JSONObject(raw).also { it.requireCurrentVersion() }
            .let { LocalImageRuntime.from(it.requireString("runtime")) }

    fun cancelRequest(requestId: String?): String =
        JSONObject()
            .put("version", VERSION)
            .put("requestId", requestId.orEmpty())
            .toString()

    fun parseCancelRequestId(raw: String): String? =
        JSONObject(raw).also { it.requireCurrentVersion() }
            .optString("requestId").takeIf { it.isNotBlank() }

    fun generateRequest(
        requestId: String,
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        batchLineage: ImageGenerationBatchLineage? = null
    ): String {
        val optionJson = options.toJson().apply {
            // sampler is the product/API term. sampleMethod remains in the payload for backward
            // compatibility with workers that predate the shared execution profile.
            options.sampleMethod?.let { put("sampler", it) }
        }
        return JSONObject()
            .put("version", VERSION)
            .put("requestId", requestId)
            .put("model", model.toJson())
            .put("prompt", prompt)
            .put("options", optionJson)
            .apply {
                if (options.allowLowStepSmoke) put(INTERNAL_ALLOW_LOW_STEP_SMOKE, true)
                batchLineage?.let { put("batchLineage", it.toJson()) }
            }
            .toString()
    }

    fun parseGenerateRequest(raw: String): GenerateRequest {
        val json = JSONObject(raw)
        json.requireCurrentVersion()
        val optionJson = json.optJSONObject("options")
        val parsedOptions = LocalImageGenerationOptions.fromJson(optionJson)
        val allowLowStepSmoke = json.optionalStrictBoolean(INTERNAL_ALLOW_LOW_STEP_SMOKE) ?: false
        val sampler = optionJson?.explicitString("sampler", preserveEmpty = true)
        if (sampler != null && parsedOptions.sampleMethod != null) {
            require(sampler == parsedOptions.sampleMethod) {
                "sampler and sampleMethod must resolve to the same value."
            }
        }
        val workerOptions = (if (sampler != null) {
            parsedOptions.copy(sampleMethod = sampler)
        } else {
            parsedOptions
        }).copy(allowLowStepSmoke = allowLowStepSmoke)
        return GenerateRequest(
            requestId = json.requireString("requestId"),
            model = LocalImageModelRecord.fromJson(
                json.optJSONObject("model") ?: kotlin.error("Missing local image model.")
            ),
            prompt = json.requireString("prompt"),
            options = workerOptions,
            batchLineage = json.optJSONObject("batchLineage")
                ?.let(ImageGenerationBatchLineage::fromJson)
        )
    }

    fun upscaleRequest(
        requestId: String,
        input: LocalImagePreparedInput,
        upscaler: LocalImagePreparedUpscaler,
        targetScale: Int,
        tileSize: Int,
        threads: Int
    ): String {
        require(targetScale in UPSCALE_TARGET_SCALES) { "Unsupported upscale target scale." }
        require(tileSize in 32..1_024 && tileSize % 8 == 0) { "Invalid upscale tile size." }
        require(threads in 1..64) { "Invalid upscale thread count." }
        return JSONObject()
            .put("version", VERSION)
            .put("requestId", requestId)
            .put("input", input.toJson())
            .put("upscaler", upscaler.toJson())
            .put("targetScale", targetScale)
            .put("tileSize", tileSize)
            .put("threads", threads)
            .toString()
    }

    fun parseUpscaleRequest(raw: String): UpscaleRequest {
        val json = JSONObject(raw).also { it.requireCurrentVersion() }
        val targetScale = json.optInt("targetScale", -1)
        val tileSize = json.optInt("tileSize", -1)
        val threads = json.optInt("threads", -1)
        require(targetScale in UPSCALE_TARGET_SCALES) { "Unsupported upscale target scale." }
        require(tileSize in 32..1_024 && tileSize % 8 == 0) { "Invalid upscale tile size." }
        require(threads in 1..64) { "Invalid upscale thread count." }
        return UpscaleRequest(
            requestId = json.requireString("requestId"),
            input = LocalImagePreparedInput.fromJson(
                json.optJSONObject("input") ?: error("Missing upscale input image.")
            ),
            upscaler = LocalImagePreparedUpscaler.fromJson(
                json.optJSONObject("upscaler") ?: error("Missing upscale model.")
            ),
            targetScale = targetScale,
            tileSize = tileSize,
            threads = threads
        )
    }

    fun progress(
        requestId: String,
        workerPid: Int,
        progress: LocalImageProgress
    ): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", requestId)
        .put("workerPid", workerPid)
        .put(
            "progress",
            JSONObject()
                .put("phase", progress.phase)
                .put("message", progress.message)
                .put("step", progress.step)
                .put("steps", progress.steps)
                .put("elapsedMs", progress.elapsedMs)
                .put("secondsPerStep", progress.secondsPerStep)
                .put("threads", progress.threads)
                .put("width", progress.width)
                .put("height", progress.height)
                .put("cancelRequested", progress.cancelRequested)
                .put("requestOptionsJson", progress.requestOptionsJson)
                .put("componentSelectionJson", progress.componentSelectionJson)
                .put("previewPath", progress.previewPath)
                .put("previewMimeType", progress.previewMimeType)
                .put("previewMode", progress.previewMode)
                .put("previewStep", progress.previewStep)
                .put("previewRevision", progress.previewRevision)
                .put("previewWidth", progress.previewWidth)
                .put("previewHeight", progress.previewHeight)
                .put("previewFrameCount", progress.previewFrameCount)
                .put("previewNoisy", progress.previewNoisy)
                .put("previewVaeExecutionAttemptCount", progress.previewVaeExecutionAttemptCount)
                .put("previewVaeExecutionCount", progress.previewVaeExecutionCount)
                .put("previewVaeExecutionMsTotal", progress.previewVaeExecutionMsTotal)
                .put("previewPublicationCount", progress.previewPublicationCount)
                .put("previewLastStep", progress.previewLastStep)
                .put("previewLastRevision", progress.previewLastRevision)
                .put("previewFailureCode", progress.previewFailureCode)
                .put("stageTrace", JSONArray(progress.stageTrace))
        )
        .toString()

    fun parseProgress(raw: String): ProgressEnvelope {
        val json = JSONObject(raw)
        json.requireReadableVersion()
        val progress = json.optJSONObject("progress") ?: error("Missing progress payload.")
        return ProgressEnvelope(
            requestId = json.requireString("requestId"),
            workerPid = json.optInt("workerPid", -1),
            progress = LocalImageProgress(
                phase = progress.optString("phase"),
                message = progress.optString("message"),
                step = progress.optInt("step"),
                steps = progress.optInt("steps"),
                elapsedMs = progress.optLong("elapsedMs"),
                secondsPerStep = progress.optDouble("secondsPerStep"),
                threads = progress.optInt("threads"),
                width = progress.optInt("width"),
                height = progress.optInt("height"),
                cancelRequested = progress.optBoolean("cancelRequested"),
                requestOptionsJson = progress.optString("requestOptionsJson"),
                componentSelectionJson = progress.optString("componentSelectionJson"),
                previewPath = progress.optString("previewPath"),
                previewMimeType = progress.optString("previewMimeType"),
                previewMode = progress.optString("previewMode"),
                previewStep = progress.optInt("previewStep"),
                previewRevision = progress.optLong("previewRevision"),
                previewWidth = progress.optInt("previewWidth"),
                previewHeight = progress.optInt("previewHeight"),
                previewFrameCount = progress.optInt("previewFrameCount"),
                previewNoisy = progress.optBoolean("previewNoisy"),
                previewVaeExecutionAttemptCount =
                    progress.optInt("previewVaeExecutionAttemptCount"),
                previewVaeExecutionCount = progress.optInt("previewVaeExecutionCount"),
                previewVaeExecutionMsTotal = progress.optLong("previewVaeExecutionMsTotal"),
                previewPublicationCount = progress.optInt("previewPublicationCount"),
                previewLastStep = progress.optInt("previewLastStep"),
                previewLastRevision = progress.optLong("previewLastRevision"),
                previewFailureCode = progress.optString("previewFailureCode"),
                stageTrace = progress.optJSONArray("stageTrace")?.let { trace ->
                    buildList {
                        for (index in 0 until trace.length()) {
                            trace.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }.orEmpty()
            )
        )
    }

    fun result(
        requestId: String,
        workerPid: Int,
        outputPath: String,
        mimeType: String,
        executionMetadataJson: String = ""
    ): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", requestId)
        .put("workerPid", workerPid)
        .put("outputPath", outputPath)
        .put("mimeType", mimeType)
        .put("executionMetadataJson", executionMetadataJson)
        .put(
            "outputs",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("outputPath", outputPath)
                    .put("mimeType", mimeType)
            )
        )
        .toString()

    fun result(
        requestId: String,
        workerPid: Int,
        outputs: List<OutputEnvelope>,
        executionMetadataJson: String = ""
    ): String {
        require(outputs.isNotEmpty()) { "Worker result outputs must not be empty." }
        require(outputs.map(OutputEnvelope::index) == outputs.indices.toList()) {
            "Worker result output indices must be contiguous and start at zero."
        }
        val first = outputs.first()
        return JSONObject()
            .put("version", VERSION)
            .put("requestId", requestId)
            .put("workerPid", workerPid)
            .put("outputPath", first.outputPath)
            .put("mimeType", first.mimeType)
            .put("executionMetadataJson", executionMetadataJson)
            .put(
                "outputs",
                JSONArray().apply {
                    outputs.forEach { output ->
                        put(
                            JSONObject()
                                .put("index", output.index)
                                .put("outputPath", output.outputPath)
                                .put("mimeType", output.mimeType)
                                .apply { output.seed?.let { put("seed", it) } }
                        )
                    }
                }
            )
            .toString()
    }

    fun parseResult(raw: String): ResultEnvelope {
        val json = JSONObject(raw)
        json.requireReadableVersion()
        val legacyPath = json.requireString("outputPath")
        val legacyMime = json.optString("mimeType", "image/png").ifBlank { "image/png" }
        val outputs = json.optJSONArray("outputs")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: error("Worker output item must be an object.")
                    val itemIndex = item.optInt("index", -1)
                    require(itemIndex == index) { "Worker output indices must be contiguous and start at zero." }
                    add(
                        OutputEnvelope(
                            index = itemIndex,
                            outputPath = item.requireString("outputPath"),
                            mimeType = item.optString("mimeType", "image/png").ifBlank { "image/png" },
                            seed = if (item.has("seed") && !item.isNull("seed")) item.getLong("seed") else null
                        )
                    )
                }
            }.also { require(it.isNotEmpty()) { "Worker result outputs must not be empty." } }
        } ?: listOf(OutputEnvelope(0, legacyPath, legacyMime, null))
        require(outputs.first().outputPath == legacyPath && outputs.first().mimeType == legacyMime) {
            "Worker legacy result fields must match output index zero."
        }
        return ResultEnvelope(
            requestId = json.requireString("requestId"),
            workerPid = json.optInt("workerPid", -1),
            outputPath = legacyPath,
            mimeType = legacyMime,
            executionMetadataJson = json.optString("executionMetadataJson"),
            outputs = outputs
        )
    }

    fun error(
        requestId: String,
        workerPid: Int,
        code: String,
        message: String
    ): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", requestId)
        .put("workerPid", workerPid)
        .put("code", code)
        .put("message", message)
        .toString()

    fun parseError(raw: String): ErrorEnvelope {
        val json = JSONObject(raw)
        json.requireReadableVersion()
        return ErrorEnvelope(
            requestId = json.optString("requestId"),
            workerPid = json.optInt("workerPid", -1),
            code = json.optString("code", "worker_error"),
            message = json.optString("message").ifBlank { "Local image worker failed." }
        )
    }

    private fun JSONObject.requireString(name: String): String =
        optString(name).takeIf { it.isNotBlank() } ?: kotlin.error("Missing $name.")

    private fun JSONObject.requireCurrentVersion() {
        require(optInt("version", -1) in 4..VERSION) { "Unsupported local image worker protocol version." }
    }

    private fun JSONObject.requireReadableVersion() {
        require(optInt("version", 1) in 1..VERSION) { "Unsupported local image worker protocol version." }
    }

    private fun JSONObject.explicitString(name: String, preserveEmpty: Boolean): String? {
        if (!has(name)) return null
        require(!isNull(name)) { "$name must be a string when specified." }
        val raw = get(name)
        require(raw is String) { "$name must be a string when specified." }
        val value = raw.trim()
        return value.takeIf { preserveEmpty || it.isNotEmpty() }
    }

    private fun JSONObject.optionalStrictBoolean(name: String): Boolean? {
        if (!has(name)) return null
        require(!isNull(name)) { "$name must be a boolean when specified." }
        return (get(name) as? Boolean)
            ?: throw IllegalArgumentException("$name must be a boolean when specified.")
    }

    private val UPSCALE_TARGET_SCALES = setOf(2, 3, 4)
}

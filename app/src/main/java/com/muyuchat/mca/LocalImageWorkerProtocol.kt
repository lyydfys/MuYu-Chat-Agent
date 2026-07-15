package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject

internal object LocalImageWorkerProtocol {
    private const val VERSION = 1

    data class GenerateRequest(
        val requestId: String,
        val model: LocalImageModelRecord,
        val prompt: String,
        val options: LocalImageGenerationOptions = LocalImageGenerationOptions()
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
        val executionMetadataJson: String = ""
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
        LocalImageRuntime.from(JSONObject(raw).requireString("runtime"))

    fun cancelRequest(requestId: String?): String =
        JSONObject()
            .put("version", VERSION)
            .put("requestId", requestId.orEmpty())
            .toString()

    fun parseCancelRequestId(raw: String): String? =
        JSONObject(raw).optString("requestId").takeIf { it.isNotBlank() }

    fun generateRequest(
        requestId: String,
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions()
    ): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", requestId)
        .put("model", model.toJson())
        .put("prompt", prompt)
        .put("options", options.toJson())
        .toString()

    fun parseGenerateRequest(raw: String): GenerateRequest {
        val json = JSONObject(raw)
        return GenerateRequest(
            requestId = json.requireString("requestId"),
            model = LocalImageModelRecord.fromJson(
                json.optJSONObject("model") ?: kotlin.error("Missing local image model.")
            ),
            prompt = json.requireString("prompt"),
            options = LocalImageGenerationOptions.fromJson(json.optJSONObject("options"))
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
                .put("stageTrace", JSONArray(progress.stageTrace))
        )
        .toString()

    fun parseProgress(raw: String): ProgressEnvelope {
        val json = JSONObject(raw)
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
        .toString()

    fun parseResult(raw: String): ResultEnvelope {
        val json = JSONObject(raw)
        return ResultEnvelope(
            requestId = json.requireString("requestId"),
            workerPid = json.optInt("workerPid", -1),
            outputPath = json.requireString("outputPath"),
            mimeType = json.optString("mimeType", "image/png").ifBlank { "image/png" },
            executionMetadataJson = json.optString("executionMetadataJson")
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
        return ErrorEnvelope(
            requestId = json.optString("requestId"),
            workerPid = json.optInt("workerPid", -1),
            code = json.optString("code", "worker_error"),
            message = json.optString("message").ifBlank { "Local image worker failed." }
        )
    }

    private fun JSONObject.requireString(name: String): String =
        optString(name).takeIf { it.isNotBlank() } ?: kotlin.error("Missing $name.")
}

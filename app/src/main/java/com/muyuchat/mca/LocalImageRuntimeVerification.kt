package com.muyuchat.mca

import android.graphics.BitmapFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
import org.json.JSONObject


/**
 * The two image-runtime verification probes: "does this QNN bundle actually load and run?" and
 * "does this MNN diffusion package actually load and run?".
 *
 * Both took **no port at all**. Their only collaboration with the ViewModel was the worker client,
 * and passing that as a parameter is both smaller and clearer than a one-member interface -- the
 * probes are pure functions of `(client, model)`, they hold no state, and they launch nothing.
 *
 * Neither probe decides admission: the callers treat a negative result as **advisory telemetry**, and
 * the structure of the returned values is preserved exactly, including the `QnnRuntimeVerificationResult`
 * whose `execution` field the caller records separately from its `verification` field.
 */

internal data class QnnRuntimeVerificationResult(
        val verification: Pair<Boolean, String>,
        val execution: QnnImageExecutionEvidence? = null
    )

internal suspend fun verifyQnnImageRuntime(
    workerClient: LocalImageWorkerClient,
    model: LocalImageModelRecord): QnnRuntimeVerificationResult =
        runCatching {
            val bundleRoot = model.bundleRoot
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf(File::isDirectory)
                ?: File(model.path).parentFile?.takeIf(File::isDirectory)
                ?: error("QNN 图像引擎缺少完整组件目录。")
            val seedOptions = LocalImageGenerationOptions(seed = 1234)
            val baseResolution = resolveLocalImageExecutionProfile(model, seedOptions, bundleRoot)
            val options = seedOptions.copy(
                steps = baseResolution.profile.scheduler.minSteps,
                width = baseResolution.layers.resolved.width,
                height = baseResolution.layers.resolved.height,
                cfgScale = baseResolution.layers.resolved.cfgScale,
                useCfg = baseResolution.layers.resolved.useCfg,
                sampleMethod = imageSchedulerProductName(baseResolution.layers.resolved.scheduler)
            )
            val verificationResolution = resolveLocalImageExecutionProfile(model, options, bundleRoot)
            val requestId = "verify-qnn-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            workerClient.begin(model.runtime)
            val result = workerClient.generate(
                model = model,
                prompt = "a small white ceramic cup on a wooden desk, morning light, clean background, photo realistic",
                options = options,
                requestId = requestId
            )
            val execution = JSONObject(result.executionMetadataJson)
            ImageExecutionProfileNativeContract.parseAndValidate(verificationResolution, execution)
            require(execution.optBoolean("npuActive", false)) {
                "QNN verification did not prove that the NPU was active."
            }
            require(execution.optBoolean("qnnGraphExecution", false)) {
                "QNN verification did not prove graph execution."
            }
            require(execution.optBoolean("nativeExecution", false)) {
                "QNN verification did not prove native execution."
            }
            require(!execution.optBoolean("fallback", true)) {
                "QNN verification unexpectedly used a fallback path."
            }
            require(execution.optLong("nativeGenerationSequence", 0L) > 0L) {
                "QNN verification is missing its native generation sequence."
            }
            require(result.bytes.isNotEmpty()) { "QNN verification produced an empty image." }
            val resolved = verificationResolution.layers.resolved
            val evidence = QnnImageExecutionEvidence.fromNativeImageExecution(
                level = QnnImageEvidenceLevel.PRODUCT_IMAGE_SMOKE,
                entryPoint = QnnImageEvidenceEntryPoint.MAIN_ACTIVITY,
                requestId = requestId,
                attemptId = requestId,
                expected = QnnImageRunParameters(resolved.width, resolved.height, resolved.steps, resolved.seed),
                executionMetadataJson = result.executionMetadataJson,
                output = result.bytes
            )
            QnnRuntimeVerificationResult(true to buildString {
                append("QNN 产品生图校验通过：${resolved.width}x${resolved.height}，${resolved.steps} step")
                execution.optLong("elapsedMs").takeIf { it > 0L }?.let { append("，耗时 ${it}ms") }
                execution.optLong("unetExecuteMsAvg").takeIf { it > 0L }?.let { append("，UNet ${it}ms/step") }
                append("，NPU/native/fallback 证据完整。")
            }, evidence)
        }.getOrElse { error ->
            QnnRuntimeVerificationResult(false to (error.message ?: "QNN 图像引擎校验异常。"))
        }

internal suspend fun verifyMnnDiffusionImageRuntime(
    workerClient: LocalImageWorkerClient,
    model: LocalImageModelRecord): Pair<Boolean, String> =
        runCatching {
            val bundleRoot = model.bundleRoot
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf(File::isDirectory)
                ?: File(model.path).parentFile?.takeIf(File::isDirectory)
                ?: error("MNN-Diffusion 缺少完整组件目录。")
            val seedOptions = LocalImageGenerationOptions(seed = 42)
            val baseResolution = resolveLocalImageExecutionProfile(model, seedOptions, bundleRoot)
            require(baseResolution.profile.task == ImageTask.TEXT_TO_IMAGE) {
                "该 MNN 图像编辑模型需要源图片，不能用纯文本生成校验代替。"
            }
            val resolved = baseResolution.layers.resolved
            val options = seedOptions.copy(
                width = resolved.width,
                height = resolved.height,
                steps = resolved.steps,
                threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                cfgScale = resolved.cfgScale,
                sampleMethod = imageSchedulerProductName(resolved.scheduler),
                backendMode = "opencl",
                memoryMode = 0,
                runner = "direct",
                useCfg = resolved.useCfg
            )
            val verificationResolution = resolveLocalImageExecutionProfile(model, options, bundleRoot)
            val requestId = "verify-mnn-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            workerClient.begin(model.runtime)
            val result = workerClient.generate(
                model = model,
                prompt = "A single red cube on a clean white background, studio lighting, no text.",
                options = options,
                requestId = requestId
            )
            val execution = JSONObject(result.executionMetadataJson)
            ImageExecutionProfileNativeContract.parseAndValidate(verificationResolution, execution)
            require(execution.optString("runner").equals("direct", ignoreCase = true)) {
                "MNN verification did not use the direct runner."
            }
            require(mnnDiffusionBackendMatches("opencl", execution.optString("backendMode"))) {
                "MNN verification did not execute on OpenCL."
            }
            require(!execution.optBoolean("fallback", true)) {
                "MNN verification unexpectedly used a fallback path."
            }
            require(result.bytes.size >= 8 && result.bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            )) { "MNN-Diffusion product output is not a complete PNG." }
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)) {
                "MNN-Diffusion product PNG cannot be decoded."
            }
            val quality = try {
                require(bitmap.width == resolved.width && bitmap.height == resolved.height) {
                    "MNN-Diffusion decoded ${bitmap.width}x${bitmap.height}, " +
                        "expected ${resolved.width}x${resolved.height}."
                }
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                evaluateLocalImageSmokePixels(bitmap.width, bitmap.height, pixels)
            } finally {
                bitmap.recycle()
            }
            require(quality.passed) { "MNN-Diffusion PNG quality smoke failed: ${quality.message}." }
            true to "MNN-Diffusion 产品 worker ${resolved.width}x${resolved.height}、" +
                "${resolved.steps}-step direct/OpenCL 校验通过，执行合同与像素质量一致。"
        }.getOrElse { error ->
            false to (error.message ?: "MNN-Diffusion 校验异常。")
        }

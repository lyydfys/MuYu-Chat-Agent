package com.muyuchat.mca

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.muyuchat.api.local.imagePromptExecutionSha256
import com.scsonic.qwenimage21.QwenImage21
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * T2B22-T4（b 工位）拆片：ActiveOperation / LoadRequest / GenerateRequest /
 * QwenNativeException 四个原嵌套私有类型提升为顶层 internal；自
 * QwenImage21WorkerService.kt 逐字节平移，仅登记声明行改写（去缩进+可见性）。
 */

internal data class ActiveOperation(
        val requestId: String,
        val kind: String,
        val callback: IQwenImage21WorkerCallback,
        val terminalSent: AtomicBoolean = AtomicBoolean(false),
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        var deathRecipient: IBinder.DeathRecipient? = null
    ) {
        @Volatile var outputPath: String? = null
    }

internal data class LoadRequest(
        val requestId: String,
        val bundleRoot: String,
        val bundleFingerprint: String?,
        val useGpu: Boolean,
        val backend: QwenImage21Backend,
        val qnnRuntimePath: String?,
        val threads: Int
    ) {
        companion object {
            fun parse(json: String): LoadRequest {
                val value = JSONObject(json)
                val requestId = value.optString("requestId").trim().ifBlank { UUID.randomUUID().toString() }
                val root = value.optString("bundleRoot").trim()
                require(root.isNotBlank()) { "Model bundle path is required." }
                val threads = value.optInt("threads", 4).coerceIn(1, 16)
                val useGpu = value.optBoolean("useGpu", true)
                val backend = QwenImage21Backend.fromWire(value.optString("backend"), useGpu)
                return LoadRequest(
                    requestId, root, value.optString("bundleFingerprint").takeIf(String::isNotBlank),
                    backend.useGpu,
                    backend,
                    value.optString("qnnRuntimePath").takeIf(String::isNotBlank),
                    threads
                )
            }
        }
    }

internal data class GenerateRequest(
        val requestId: String,
        val bundleRoot: String,
        val bundleFingerprint: String?,
        val prompt: String,
        val negativePrompt: String,
        val inputImagePath: String?,
        val outputPath: String,
        val width: Int,
        val height: Int,
        val steps: Int,
        val seed: Int,
        val threads: Int,
        val useGpu: Boolean,
        val backend: QwenImage21Backend,
        val qnnRuntimePath: String?
    ) {
        companion object {
            fun parse(json: String): GenerateRequest {
                val value = JSONObject(json)
                val id = value.optString("requestId").trim()
                require(id.isNotBlank()) { "Generation request id is required." }
                val prompt = value.optString("prompt").trim()
                require(prompt.isNotBlank()) { "Image prompt cannot be empty." }
                val negative = value.optString("negativePrompt").trim()
                require(negative.isEmpty()) {
                    "Qwen-Image-2.1 MNN does not support a separate negative prompt. Clear the negative prompt and retry."
                }
                val root = value.optString("bundleRoot").trim()
                require(root.isNotBlank()) { "Model bundle path is required." }
                val output = value.optString("outputPath").trim()
                require(output.isNotBlank()) { "Output image path is required." }
                val width = value.optInt("width", 0)
                val height = value.optInt("height", 0)
                require(QwenImage21SizeContract.isSupported(width, height)) {
                    QwenImage21SizeContract.unsupportedSizeMessage(width, height)
                }
                val steps = value.optInt("steps", 20)
                require(steps in 2..40) { "Qwen generation steps must be between 2 and 40." }
                val threads = value.optInt("threads", 4).coerceIn(1, 16)
                val useGpu = value.optBoolean("useGpu", true)
                val backend = QwenImage21Backend.fromWire(value.optString("backend"), useGpu)
                return GenerateRequest(
                    requestId = id,
                    bundleRoot = root,
                    bundleFingerprint = value.optString("bundleFingerprint").takeIf(String::isNotBlank),
                    prompt = prompt,
                    negativePrompt = negative,
                    inputImagePath = value.optString("inputImagePath").takeIf(String::isNotBlank),
                    outputPath = output,
                    width = width,
                    height = height,
                    steps = steps,
                    seed = value.optInt("seed", 42),
                    threads = threads,
                    useGpu = backend.useGpu,
                    backend = backend,
                    qnnRuntimePath = value.optString("qnnRuntimePath").takeIf(String::isNotBlank)
                )
            }
        }
    }

internal class QwenNativeException(val code: String, message: String) : IllegalStateException(message)

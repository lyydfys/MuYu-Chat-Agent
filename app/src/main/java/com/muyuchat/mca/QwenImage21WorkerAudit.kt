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
import com.muyuchat.mca.QwenImage21WorkerService.Companion.MAX_ERROR_CHARS

/**
 * T2B22-T4（b 工位）拆片：executionAudit / bundleFingerprint / isPng / checkNotCancelled /
 * safeMessage / requestIdFrom 以 QwenImage21WorkerService 扩展承载；自
 * QwenImage21WorkerService.kt 逐字节平移，仅登记声明行改写。
 */

internal fun QwenImage21WorkerService.executionAudit(
        request: GenerateRequest,
        elapsedMs: Long,
        outputBytes: Long,
        nativeAudit: JSONObject
    ): JSONObject {
        // The Qwen runtime performs prompt conditioning inside the isolated worker. Publish the
        // same request-bound digest used by the image provider so the result cannot be detached
        // from the text that was actually submitted to native execution.
        val promptSha256 = imagePromptExecutionSha256(request.prompt, "")
        val nativeEffective = JSONObject()
            .put("runtime", "MNN_DIFFUSION")
            .put("nativeExecution", nativeAudit.optBoolean("nativeExecution", false))
            .put("fallback", nativeAudit.optBoolean("fallback", true))
            .put("nativeGenerationSequence", nativeAudit.optInt("nativeGenerationSequence", 0))
            .put("backendResolved", nativeAudit.optBoolean("backendResolved", false))
            .put("nativeRunCompleted", nativeAudit.optBoolean("nativeRunCompleted", false))
            .put("backendExecutionConfirmedByNative", nativeAudit.optBoolean("backendExecutionConfirmedByNative", false))
            .put("backendExecutionProof", nativeAudit.optString("backendExecutionProof"))
            .put("backendExecutionScope", nativeAudit.optString("backendExecutionScope"))
            .put("requestedBackend", nativeAudit.optString("requestedBackend"))
            .put("effectiveBackend", nativeAudit.optString("effectiveBackend"))
            .put("nativeGenerationCount", nativeAudit.optInt("nativeGenerationCount", 0))
            .put("nativePromptExecutionSha256", promptSha256)
            .put("nativePromptBindingStage", "conditioning_consumed")
        return JSONObject()
            .put("runtime", "MNN_DIFFUSION")
            .put("nativeExecution", nativeAudit.optBoolean("nativeExecution", false))
            .put("fallback", nativeAudit.optBoolean("fallback", true))
            .put("nativeGenerationSequence", nativeAudit.optInt("nativeGenerationSequence", 0))
            .put("backendResolved", nativeAudit.optBoolean("backendResolved", false))
            .put("backendConfigured", nativeAudit.optString("effectiveBackend").ifBlank { "MNN_UNKNOWN" })
            .put("backendExecutionConfirmedByNative", nativeAudit.optBoolean("backendExecutionConfirmedByNative", false))
            .put("backendExecutionProof", nativeAudit.optString("backendExecutionProof"))
            .put("backendExecutionScope", nativeAudit.optString("backendExecutionScope"))
            .put("nativeRunCompleted", nativeAudit.optBoolean("nativeRunCompleted", false))
            .put("requestedBackend", nativeAudit.optString("requestedBackend"))
            .put("effectiveBackend", nativeAudit.optString("effectiveBackend"))
            .put("nativeGenerationCount", nativeAudit.optInt("nativeGenerationCount", 0))
            .put("textEncoderOnCpu", true)
            .put("vaeOnCpu", true)
            .put("steps", request.steps)
            .put("width", request.width)
            .put("height", request.height)
            .put("seed", request.seed)
            .put("threads", request.threads)
            .put("loadMs", modelLoadMs)
            .put("generationElapsedMs", elapsedMs)
            .put("outputBytes", outputBytes)
            .put("pngValidated", true)
            .put("nativePromptExecutionSha256", promptSha256)
            .put("nativePromptBindingStage", "conditioning_consumed")
            .put("nativeEffective", nativeEffective)
    }

internal fun QwenImage21WorkerService.bundleFingerprint(root: File, supplied: String?): String {
        return qwenImage21BundleFingerprint(root, supplied)
    }

internal fun QwenImage21WorkerService.isPng(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            val actual = ByteArray(expected.size)
            var offset = 0
            while (offset < actual.size) {
                val read = input.read(actual, offset, actual.size - offset)
                if (read < 0) return@use false
                offset += read
            }
            expected.contentEquals(actual)
        }
    }.getOrDefault(false)

internal fun QwenImage21WorkerService.checkNotCancelled(operation: ActiveOperation) {
        if (operation.cancelled.get()) throw java.util.concurrent.CancellationException("Operation cancelled.")
    }

internal fun QwenImage21WorkerService.safeMessage(error: Throwable): String =
        (error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName)
            .filterNot(Char::isISOControl)
            .take(MAX_ERROR_CHARS)

internal fun QwenImage21WorkerService.requestIdFrom(json: String): String = runCatching {
        JSONObject(json).optString("requestId").takeIf(String::isNotBlank)
    }.getOrNull() ?: UUID.randomUUID().toString()

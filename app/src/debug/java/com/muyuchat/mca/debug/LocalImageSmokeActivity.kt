package com.muyuchat.mca.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Base64
import android.util.Log
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.nativebridge.NativeQnnBridge
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import com.muyuchat.mca.NativeQnnSmokeResult
import com.muyuchat.mca.LocalImageModelFamily
import com.muyuchat.mca.LocalImageModelRecord
import com.muyuchat.mca.LocalImageRuntime
import com.muyuchat.mca.LocalImageVerificationStatus
import com.muyuchat.mca.LocalImageExecutionGate
import com.muyuchat.mca.LocalImageGenerationOptions
import com.muyuchat.mca.LocalImageProvider
import com.muyuchat.mca.LocalImageWorkerClient
import com.muyuchat.mca.ImagePixelRange
import com.muyuchat.mca.QnnExecutionDiagnostics
import com.muyuchat.mca.QnnSmokeSpec
import com.muyuchat.mca.QwenImage21Backend
import com.muyuchat.mca.QwenImage21WorkerClient
import com.muyuchat.mca.hasCurrentQnnVerificationStamp
import com.muyuchat.mca.qnnContextSocCompatibilityMessage
import com.muyuchat.mca.qnnGraphSmokeSpecForHarness
import com.muyuchat.mca.qnnImageVerificationStampFor
import com.muyuchat.mca.qnnRuntimeDirectoriesFor
import com.muyuchat.mca.contextBinaryFileIn
import com.muyuchat.mca.localImageBundleManifestFromRoot
import com.muyuchat.mca.strictJsonForPersistence
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class LocalImageSmokeActivity : Activity() {
    private val tag = "MCA-SD-SMOKE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread { runSmoke() }.start()
    }

    private fun qnnRuntimeDirsJson(bundleRoot: String): String {
        // `am --es` strips JSON's embedded quote characters on some Android
        // shell paths. Tests therefore pass an optional Base64 transport; the
        // legacy raw extra remains available for direct instrumentation calls.
        val encoded = intent.getStringExtra("runtimeDirsJsonBase64").orEmpty()
        if (encoded.isNotBlank()) {
            val decoded = runCatching {
                String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrElse { failure ->
                error("runtimeDirsJsonBase64 is invalid: ${failure.message ?: failure::class.java.simpleName}")
            }
            JSONArray(decoded)
            return decoded
        }
        val raw = intent.getStringExtra("runtimeDirsJson").orEmpty()
        if (raw.isNotBlank()) {
            JSONArray(raw)
            return raw
        }
        return JSONArray(qnnRuntimeDirectoriesFor(this, File(bundleRoot))).toString()
    }

    private fun runSmoke() {
        val startedAt = System.currentTimeMillis()
        val mainPid = Process.myPid()
        val observedWorkerPid = AtomicInteger(-1)
        val mainLeaseHeld = AtomicBoolean(false)
        val workerWaitedForNativeLease = AtomicBoolean(false)
        val outDir = File(getExternalFilesDir("image_bench"), "runs").apply { mkdirs() }
        val runId = intent.getStringExtra("runId").orEmpty().ifBlank { "run-${startedAt}" }
        val logFile = File(outDir, "$runId.json")
        val events = JSONArray()
        val logLock = Any()
        fun write(event: JSONObject) = synchronized(logLock) {
            val snapshot = JSONObject(event.toString())
                .put("runId", runId)
                .put("elapsedMs", System.currentTimeMillis() - startedAt)
                .put("mainPid", mainPid)
                .put("workerPid", observedWorkerPid.get())
                .put("mainProcessAlive", Process.myPid() == mainPid)
                .put("mainLeaseHeld", mainLeaseHeld.get())
                .put("workerWaitedForNativeLease", workerWaitedForNativeLease.get())
                .put(
                    "workerIsolated",
                    observedWorkerPid.get() > 0 && observedWorkerPid.get() != mainPid
                )
                .put("processMemory", processMemoryJson())
            events.put(snapshot)
            val document = JSONObject(snapshot.toString()).put("events", events)
            atomicReplaceUtf8(logFile, strictJsonForPersistence(document, indentSpaces = 2))
            Log.i(tag, snapshot.toString())
        }

        try {
            val runtime = intent.getStringExtra("runtime").orEmpty().ifBlank { "sdcpp" }.lowercase()
            val isMnnDiffusion = runtime in setOf("mnn", "mnn_diffusion", "mnn-diffusion")
            val isQnnHtp = runtime in setOf("qnn", "qnn_htp", "qnn-htp", "qairt", "htp")
            val isStableDiffusionCpp = !isMnnDiffusion && !isQnnHtp
            val bundleRoot = intent.getStringExtra("bundleRoot").orEmpty()
            val modelPath = intent.getStringExtra("modelPath").orEmpty().ifBlank {
                if (isMnnDiffusion && bundleRoot.isNotBlank()) {
                    File(bundleRoot, "unet.mnn").absolutePath
                } else {
                    ""
                }
            }
            require(modelPath.isNotBlank() || bundleRoot.isNotBlank()) {
                "modelPath or bundleRoot is required"
            }
            val prompt = intent.getStringExtra("prompt")
                ?: "a tiny ceramic robot sitting on a wooden desk, soft morning light, clean background, no text"
            // QNN semantic execution validates this field as a JSON string even
            // when the caller intentionally uses no negative prompt.
            val negativePrompt = intent.getStringExtra("negativePrompt").orEmpty()
            val width = intent.getIntExtra("width", 256)
            val height = intent.getIntExtra("height", 256)
            val steps = intent.getIntExtra("steps", 2)
            val threads = intent.getIntExtra("threads", 4)
            val seed = intent.getIntExtra("seed", 42)
            val cfgScale = intent.numberExtra("cfgScale", 7.0)
            require(isStableDiffusionCpp || !intent.hasExtra("distilledGuidance")) {
                "Runtime $runtime has no distilled-guidance graph input."
            }
            require(isStableDiffusionCpp || !intent.hasExtra("flowShift")) {
                "Runtime $runtime has no writable native flow-shift control."
            }
            val distilledGuidance = if (isStableDiffusionCpp) {
                intent.numberExtra("distilledGuidance", 3.5)
            } else {
                null
            }
            val flowShift = if (isStableDiffusionCpp) {
                intent.numberExtra("flowShift", -1.0)
            } else {
                null
            }
            val requestedLoraPath = intent.getStringExtra("loraPath").orEmpty().trim()
            val loraId = intent.getStringExtra("loraId").orEmpty().trim()
            val loraSha256 = intent.getStringExtra("loraSha256").orEmpty().trim().lowercase()
            val loraSizeBytes = intent.getLongExtra("loraSizeBytes", 0L)
            val loraMultiplier = intent.numberExtra("loraMultiplier", 1.0)
            require(requestedLoraPath.isEmpty() || isStableDiffusionCpp) {
                "LoRA adapters are supported only by stable-diffusion.cpp smoke."
            }
            if (requestedLoraPath.isNotEmpty()) {
                require(loraId.isNotEmpty() && loraSha256.matches(Regex("[0-9a-f]{64}")) && loraSizeBytes > 0L) {
                    "LoRA smoke requires id, SHA-256, and a positive size."
                }
                require(File(requestedLoraPath).isFile && File(requestedLoraPath).length() == loraSizeBytes) {
                    "LoRA smoke file is missing or has an unexpected size."
                }
            }
            // Keep native LoRA reopening inside this app's private storage. The
            // harness may provide an external absolute path, but the bridge's
            // worker/process boundary must not depend on broad storage access.
            val loraPath = if (requestedLoraPath.isEmpty()) {
                ""
            } else {
                stageLoraFile(
                    sourcePath = requestedLoraPath,
                    loraSha256 = loraSha256,
                    loraSizeBytes = loraSizeBytes
                )
            }
            val sampleMethod = intent.getStringExtra("sampleMethod").orEmpty().ifBlank { "euler" }
            val family = intent.getStringExtra("family").orEmpty().ifBlank { "SD15" }
            val backendMode = intent.getStringExtra("backendMode").orEmpty().ifBlank { "cpu" }
            val memoryMode = intent.getIntExtra("memoryMode", 0)
            val runner = intent.getStringExtra("runner").orEmpty()
            val directUnetSmoke = intent.getBooleanExtra("directUnetSmoke", false)
            val preflightOnly = intent.getBooleanExtra("preflightOnly", false)
            val qnnPipelineProbe = intent.getBooleanExtra("pipelineProbe", false)
            val qnnSemanticGenerate = intent.getBooleanExtra("semanticGenerate", false)
            val workerProductPath = intent.getBooleanExtra("workerProductPath", false)
            val workerStartPauseMs = intent.getLongExtra("workerStartPauseMs", 0L).coerceIn(0L, 300_000L)
            val workerMainLeaseHoldMs = intent.getLongExtra("workerMainLeaseHoldMs", 0L).coerceIn(0L, 300_000L)
            require(
                !workerProductPath ||
                    isStableDiffusionCpp ||
                    (isMnnDiffusion && !preflightOnly) ||
                    (isQnnHtp && qnnSemanticGenerate)
            ) {
                "workerProductPath requires stable-diffusion.cpp, MNN generate mode, or QNN semantic mode."
            }
            require(workerMainLeaseHoldMs == 0L || workerProductPath) {
                "workerMainLeaseHoldMs requires workerProductPath."
            }
            require(workerStartPauseMs == 0L || workerMainLeaseHoldMs == 0L) {
                "workerStartPauseMs and workerMainLeaseHoldMs must be tested in separate runs."
            }
            val executionMode = when {
                workerProductPath && isQnnHtp -> "qnn_worker_product"
                workerProductPath && isMnnDiffusion -> "mnn_worker_product"
                workerProductPath && isStableDiffusionCpp -> "sdcpp_worker_product"
                isQnnHtp && qnnSemanticGenerate -> "qnn_semantic_graph"
                isQnnHtp && qnnPipelineProbe -> "qnn_pipeline_probe"
                isQnnHtp -> "qnn_graph_smoke"
                isMnnDiffusion -> "mnn_diffusion"
                else -> "stable-diffusion.cpp"
            }
            val outputFile = File(outDir, if (isMnnDiffusion) "$runId.png" else "$runId.png")
            val requestJson = JSONObject()
                .put("prompt", prompt)
                .put("negativePrompt", negativePrompt)
                .put("family", family)
                .put("width", width)
                .put("height", height)
                .put("steps", steps)
                .put("threads", threads)
                .put("seed", seed)
                .put("randomSeed", seed)
                .put("cfgScale", cfgScale)
                .put("sampleMethod", sampleMethod)
                .put("backendMode", backendMode)
                .put("memoryMode", memoryMode)
            // Qwen Image 2.1 uses flow matching without a distilled-guidance graph input.
            // The harness keeps a legacy SD-Turbo default for other families, but must not
            // mark that optional field as specified for Qwen native contract validation.
            if (!family.equals("QWEN_IMAGE_21", ignoreCase = true)) {
                distilledGuidance?.let { requestJson.put("distilledGuidance", it) }
            }
            flowShift?.let { requestJson.put("flowShift", it) }
            requestJson.put("loraCount", if (loraPath.isEmpty()) 0 else 1)
                .put("loraRootPath", if (loraPath.isEmpty()) "" else File(loraPath).parentFile?.absolutePath.orEmpty())
                .put("loras", JSONArray().apply {
                    if (loraPath.isNotEmpty()) {
                        put(JSONObject()
                            .put("id", loraId)
                            .put("name", File(loraPath).name)
                            .put("path", loraPath)
                            .put("sha256", loraSha256)
                            .put("sizeBytes", loraSizeBytes)
                            .put("multiplier", loraMultiplier))
                    }
                })
            if (isQnnHtp) {
                requestJson.put(
                    "pixelRange",
                    intent.getStringExtra("pixelRange")
                        .orEmpty()
                        .ifBlank { ImagePixelRange.NEGATIVE_ONE_TO_ONE.name }
                )
            }
            if (family.equals("SDXL", ignoreCase = true)) {
                requestJson.put("conditioningFormat", "sdxl_qnn_conditioning")
                    .put("vaeLatentScale", 1.0 / 0.13025)
            }
            if (runner.isNotBlank()) {
                requestJson.put("runner", runner)
            }
            val deferNativeInspectionToWorker = workerProductPath && (isMnnDiffusion || isStableDiffusionCpp)
            val mnnInspection = if (
                isMnnDiffusion &&
                !deferNativeInspectionToWorker &&
                NativeMnnDiffusionBridge.isAvailable
            ) {
                runCatching {
                    JSONObject(NativeMnnDiffusionBridge().inspectBundle(bundleRoot.ifBlank { File(modelPath).parent.orEmpty() }))
                }.getOrElse { error ->
                    JSONObject().put("error", error.stackTraceToString())
                }
            } else {
                JSONObject()
            }
            write(
                JSONObject()
                    .put("status", "starting")
                    .put(
                        "runtime",
                        when {
                            isMnnDiffusion -> "mnn_diffusion"
                            isQnnHtp -> "qnn_htp"
                            else -> "stable-diffusion.cpp"
                        }
                    )
                    .put("modelPath", modelPath)
                    .put("bundleRoot", bundleRoot)
                    .put("prompt", prompt)
                    .put("width", width)
                    .put("height", height)
                    .put("steps", steps)
                    .put("threads", threads)
                    .put("seed", seed)
                    .put("cfgScale", cfgScale)
                    .put("sampleMethod", sampleMethod)
                    .put("backendMode", backendMode)
                    .put("memoryMode", memoryMode)
                    .put("runner", runner)
                    .put("directUnetSmoke", directUnetSmoke)
                    .put("preflightOnly", preflightOnly)
                    .put("qnnPipelineProbe", qnnPipelineProbe)
                    .put("qnnSemanticGenerate", qnnSemanticGenerate)
                    .put("workerProductPath", workerProductPath)
                    .put("workerStartPauseMs", workerStartPauseMs)
                    .put("workerMainLeaseHoldMs", workerMainLeaseHoldMs)
                    .put("executionMode", executionMode)
                    .put("fallback", false)
                    .put("qnnGraphExecutionRequired", isQnnHtp)
                    .put("family", family)
                    .put("deviceSnapshot", deviceSnapshotJson())
                    .put(
                        "nativeAvailable",
                        if (deferNativeInspectionToWorker) JSONObject.NULL else NativeStableDiffusionBridge.isAvailable
                    )
                    .put(
                        "nativeLoadError",
                        if (deferNativeInspectionToWorker) "deferred_to_worker" else NativeStableDiffusionBridge.loadError?.message.orEmpty()
                    )
                    .put(
                        "mnnNativeAvailable",
                        if (deferNativeInspectionToWorker) JSONObject.NULL else NativeMnnDiffusionBridge.isAvailable
                    )
                    .put(
                        "mnnRunnerReady",
                        if (deferNativeInspectionToWorker) JSONObject.NULL else NativeMnnDiffusionBridge.runnerReady
                    )
                    .put(
                        "mnnNativeLoadError",
                        if (deferNativeInspectionToWorker) "deferred_to_worker" else NativeMnnDiffusionBridge.loadError?.message.orEmpty()
                    )
                    .put("mnnInspection", mnnInspection)
            )

            if (workerProductPath) {
                val resolvedFamily = LocalImageModelFamily.from(family.uppercase())
                val resolvedBundleRoot = bundleRoot.ifBlank { File(modelPath).parent.orEmpty() }
                val (verificationEvidence, verificationStamp) = if (isQnnHtp) {
                    runBlocking {
                        LocalImageExecutionGate.withLease(applicationContext) {
                            val evidence = verifyQnnWorkerPrerequisite(
                                bundleRoot = resolvedBundleRoot,
                                requestJson = requestJson,
                                outputFile = outputFile,
                                write = ::write
                            )
                            evidence to qnnImageVerificationStampFor(
                                this@LocalImageSmokeActivity,
                                File(resolvedBundleRoot)
                            )
                        }
                    }
                } else {
                    JSONObject().put(
                        "kind",
                        if (isMnnDiffusion) "mnn_debug_record" else "sdcpp_debug_record"
                    ) to ""
                }
                val workerModel = LocalImageModelRecord(
                    id = "debug-worker-${UUID.randomUUID()}",
                    displayName = File(resolvedBundleRoot).name.ifBlank { "Debug local image worker" },
                    path = modelPath,
                    fileName = File(modelPath).name,
                    sizeBytes = File(modelPath).takeIf { it.isFile }?.length() ?: 0L,
                    sha256 = "",
                    runtime = when {
                        isQnnHtp -> LocalImageRuntime.QNN_HTP
                        isMnnDiffusion -> LocalImageRuntime.MNN_DIFFUSION
                        else -> LocalImageRuntime.STABLE_DIFFUSION_CPP
                    },
                    family = resolvedFamily,
                    imageSize = "${width}x$height",
                    source = "debug-worker-product-path",
                    bundleRoot = resolvedBundleRoot,
                    componentCount = File(resolvedBundleRoot).walkTopDown().count { it.isFile }.coerceAtLeast(1),
                    verificationStatus = LocalImageVerificationStatus.PASSED,
                    verificationMessage = when {
                        isQnnHtp -> "Debug worker smoke passed real main-process QNN graph and semantic verification."
                        isMnnDiffusion -> "Debug worker smoke explicitly opted into the product MNN generation path."
                        else -> "Debug worker smoke explicitly opted into the product stable-diffusion.cpp generation path."
                    },
                    verifiedAt = System.currentTimeMillis(),
                    qnnVerificationStamp = verificationStamp
                )
                if (isQnnHtp) {
                    require(workerModel.hasCurrentQnnVerificationStamp(this, File(resolvedBundleRoot))) {
                        "QNN verification stamp did not match immediately after real main-process verification."
                    }
                }
                write(
                    JSONObject()
                        .put("status", "worker_model_ready")
                        .put(
                            "runtime",
                            when {
                                isQnnHtp -> "qnn_htp"
                                isMnnDiffusion -> "mnn_diffusion"
                                else -> "stable-diffusion.cpp"
                            }
                        )
                        .put("workerProductPath", true)
                        .put("model", workerModel.toJson())
                        .put("verification", verificationEvidence)
                )
                runWorkerProductGeneration(
                    model = workerModel,
                    prompt = prompt,
                    generationOptions = LocalImageGenerationOptions.fromJson(requestJson)
                        .copy(allowLowStepSmoke = true),
                    outputFile = outputFile,
                    workerStartPauseMs = workerStartPauseMs,
                    workerMainLeaseHoldMs = workerMainLeaseHoldMs,
                    observedWorkerPid = observedWorkerPid,
                    mainLeaseHeld = mainLeaseHeld,
                    workerWaitedForNativeLease = workerWaitedForNativeLease,
                    verificationEvidence = verificationEvidence,
                    write = ::write
                )
                return
            }

            // Direct Qwen QNN worker smoke is intentionally debug-only. It exercises the
            // same isolated service used by the product while bypassing the older profile
            // resolver, so a real QNN bundle can be validated before product admission is
            // widened. The normal OpenCL/CPU branches above remain unchanged.
            if (intent.getBooleanExtra("qwenWorkerDirect", false)) {
                require(isQnnHtp) { "qwenWorkerDirect requires runtime=qnn" }
                runQwenWorkerDirect(
                    bundleRoot = bundleRoot.ifBlank { File(modelPath).parent.orEmpty() },
                    requestJson = requestJson,
                    outputFile = outputFile,
                    write = ::write
                )
                return
            }

            if ((directUnetSmoke || preflightOnly) && isMnnDiffusion) {
                write(
                    JSONObject()
                        .put("status", "unet_preflight_starting")
                        .put("runtime", "mnn_diffusion")
                        .put("backendMode", backendMode)
                        .put("bundleRoot", bundleRoot.ifBlank { File(modelPath).parent.orEmpty() })
                )
                val unetSmoke = runCatching {
                    JSONObject(
                        NativeMnnDiffusionBridge().runUnetSmoke(
                            bundleRoot.ifBlank { File(modelPath).parent.orEmpty() },
                            backendMode
                        )
                    )
                }.getOrElse { error ->
                    JSONObject().put("ok", false).put("error", error.stackTraceToString())
                }
                mnnInspection.put("unetDirectSmoke", unetSmoke)
                write(
                    JSONObject()
                        .put(
                            "status",
                            if (unetSmoke.optBoolean("ok", false)) {
                                "unet_preflight_completed"
                            } else {
                                "unet_preflight_failed"
                            }
                        )
                        .put("runtime", "mnn_diffusion")
                        .put("preflightOnly", preflightOnly)
                        .put("mnnInspection", mnnInspection)
                        .put("unetPreflight", unetSmoke)
                )
                if (preflightOnly) {
                    write(
                        JSONObject()
                            .put("status", if (unetSmoke.optBoolean("ok", false)) "completed" else "failed")
                            .put("runtime", "mnn_diffusion")
                            .put("preflightOnly", true)
                            .put("unetPreflight", unetSmoke)
                    )
                    return
                }
            }

            if (isMnnDiffusion) {
                runProductionMnnGenerate(
                    bundleRoot.ifBlank { File(modelPath).parent.orEmpty() },
                    requestJson,
                    outputFile,
                    ::write
                )
                return
            }
            if (isQnnHtp) {
                if (qnnSemanticGenerate) {
                    runQnnSemanticGenerate(
                        bundleRoot.ifBlank { File(modelPath).parent.orEmpty() },
                        requestJson,
                        outputFile,
                        ::write
                    )
                } else if (qnnPipelineProbe) {
                    runQnnPipelineProbe(
                        bundleRoot.ifBlank { File(modelPath).parent.orEmpty() },
                        requestJson,
                        outputFile,
                        ::write
                    )
                } else {
                    runQnnImageSmoke(bundleRoot.ifBlank { File(modelPath).parent.orEmpty() }, ::write)
                }
                return
            }

            require(NativeStableDiffusionBridge.isAvailable) {
                "mca_sd_native is unavailable: ${NativeStableDiffusionBridge.loadError?.message.orEmpty()}"
            }

            val bridge = NativeStableDiffusionBridge()
            val progressThread = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    runCatching {
                        val progress = JSONObject(bridge.getProgress())
                        write(JSONObject().put("status", "progress").put("progress", progress))
                    }
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            progressThread.start()
            val result = try {
                bridge.generate(
                    modelPath,
                    bundleRoot,
                    requestJson.toString(),
                    outputFile.absolutePath
                )
            } finally {
                progressThread.interrupt()
                runCatching { bridge.shutdown() }
            }
            val resultJson = JSONObject(result)
            write(
                JSONObject()
                    .put("status", if (resultJson.optBoolean("ok")) "completed" else "failed")
                    .put("request", requestJson)
                    .put("result", resultJson)
                    .put("outputPath", outputFile.absolutePath)
                    .put("outputBytes", outputFile.takeIf { it.exists() }?.length() ?: 0L)
            )
        } catch (error: Throwable) {
            write(JSONObject().put("status", "failed").put("error", error.stackTraceToString()))
        } finally {
            runOnUiThread { finish() }
        }
    }

    private fun verifyQnnWorkerPrerequisite(
        bundleRoot: String,
        requestJson: JSONObject,
        outputFile: File,
        write: (JSONObject) -> Unit
    ): JSONObject {
        require(bundleRoot.isNotBlank()) { "bundleRoot is required for QNN worker verification" }
        write(
            JSONObject()
                .put("status", "worker_verification_starting")
                .put("runtime", "qnn_htp")
                .put("verificationProcess", "main")
        )
        val smoke = runQnnImageSmoke(bundleRoot, write, terminalStatus = false)
        require(smoke.optBoolean("npuExecutionProven", false)) {
            smoke.optString("message").ifBlank { "Main-process QNN graph smoke did not prove NPU execution." }
        }

        val verificationOutput = File(
            outputFile.parentFile ?: getExternalFilesDir("image_bench") ?: filesDir,
            "${outputFile.nameWithoutExtension}.worker-verification.png"
        )
        val verificationRequest = JSONObject(requestJson.toString())
            .put(
                "steps",
                if (requestJson.optString("family").equals("SDXL", ignoreCase = true)) 1 else 4
            )
            .put("seed", 1234)
            .put("randomSeed", 1234)
        val semantic = try {
            runQnnSemanticGenerate(
                bundleRoot = bundleRoot,
                requestJson = verificationRequest,
                outputFile = verificationOutput,
                write = write,
                terminalStatus = false
            )
        } finally {
            runCatching { verificationOutput.delete() }
            runCatching {
                File(
                    verificationOutput.parentFile,
                    "${verificationOutput.nameWithoutExtension}.sd15-embeddings.f32"
                ).delete()
            }
            runCatching {
                File(
                    verificationOutput.parentFile,
                    "${verificationOutput.nameWithoutExtension}.sdxl-conditioning.f32"
                ).delete()
            }
        }
        require(
            semantic.optBoolean("ok", false) &&
                semantic.optBoolean("npuActive", false) &&
                semantic.optBoolean("semanticReady", false)
        ) {
            semantic.optString("message").ifBlank {
                semantic.optString("error").ifBlank {
                    "Main-process QNN semantic verification did not prove a complete NPU path."
                }
            }
        }
        return JSONObject()
            .put("kind", "main_process_qnn_graph_and_semantic")
            .put("smoke", smoke)
            .put("semantic", semantic)
    }

    private fun runWorkerProductGeneration(
        model: LocalImageModelRecord,
        prompt: String,
        generationOptions: LocalImageGenerationOptions,
        outputFile: File,
        workerStartPauseMs: Long,
        workerMainLeaseHoldMs: Long,
        observedWorkerPid: AtomicInteger,
        mainLeaseHeld: AtomicBoolean,
        workerWaitedForNativeLease: AtomicBoolean,
        verificationEvidence: JSONObject,
        write: (JSONObject) -> Unit
    ) {
        val client = LocalImageWorkerClient(applicationContext)
        val pauseConsumed = AtomicBoolean(false)
        val reportedWorkerSteps = AtomicInteger(-1)
        val mainLeaseAcquired = CountDownLatch(if (workerMainLeaseHoldMs > 0L) 1 else 0)
        val workerLeaseWaitObserved = CountDownLatch(if (workerMainLeaseHoldMs > 0L) 1 else 0)
        val mainLeaseFailure = AtomicReference<Throwable?>(null)
        val mainLeaseThread = workerMainLeaseHoldMs.takeIf { it > 0L }?.let { holdMs ->
            Thread {
                try {
                    runBlocking {
                        LocalImageExecutionGate.withLease(applicationContext) {
                            val acquiredAt = System.currentTimeMillis()
                            mainLeaseHeld.set(true)
                            write(
                                JSONObject()
                                    .put("status", "main_native_lease_held")
                                    .put("requestedHoldMs", holdMs)
                            )
                            mainLeaseAcquired.countDown()
                            workerLeaseWaitObserved.await(60, TimeUnit.SECONDS)
                            val remainingMs = holdMs - (System.currentTimeMillis() - acquiredAt)
                            if (remainingMs > 0L) Thread.sleep(remainingMs)
                            write(
                                JSONObject()
                                    .put("status", "main_native_lease_releasing")
                                    .put("requestedHoldMs", holdMs)
                                    .put("workerWaitObserved", workerWaitedForNativeLease.get())
                            )
                        }
                    }
                } catch (error: Throwable) {
                    mainLeaseFailure.set(error)
                } finally {
                    mainLeaseAcquired.countDown()
                }
            }.also { thread ->
                thread.name = "mca-debug-main-image-lease"
                thread.start()
            }
        }
        try {
            if (workerMainLeaseHoldMs > 0L) {
                require(mainLeaseAcquired.await(30, TimeUnit.SECONDS)) {
                    "Timed out waiting for the main process to acquire the native image lease."
                }
                mainLeaseFailure.get()?.let { throw it }
                require(mainLeaseHeld.get()) { "Main process did not confirm the native image lease." }
            }
            client.begin(model.runtime)
            write(
                JSONObject()
                    .put("status", "worker_binding")
                    .put("runtime", model.runtime.name.lowercase())
                    .put("workerProductPath", true)
                    .put("outputPath", outputFile.absolutePath)
            )
            val result = runBlocking {
                client.generate(model, prompt, options = generationOptions) { progress ->
                    client.lastWorkerPid.takeIf { it > 0 }?.let(observedWorkerPid::set)
                    if (progress.steps > 0) reportedWorkerSteps.set(progress.steps)
                    if (progress.phase == "waiting_for_native_lease") {
                        workerWaitedForNativeLease.set(true)
                        workerLeaseWaitObserved.countDown()
                    }
                    write(
                        JSONObject()
                            .put("status", "worker_progress")
                            .put("runtime", model.runtime.name.lowercase())
                            .put("phase", progress.phase)
                            .put("message", progress.message)
                            .put("step", progress.step)
                            .put("steps", progress.steps)
                            .put("progressElapsedMs", progress.elapsedMs)
                            .put(
                                "requestOptions",
                                progress.requestOptionsJson.takeIf { it.isNotBlank() }
                                    ?.let(::JSONObject)
                                    ?: JSONObject.NULL
                            )
                            .put(
                                "componentSelection",
                                progress.componentSelectionJson.takeIf { it.isNotBlank() }
                                    ?.let(::JSONObject)
                                    ?: JSONObject.NULL
                            )
                    )
                    if (progress.phase == "worker_started" &&
                        workerStartPauseMs > 0L &&
                        pauseConsumed.compareAndSet(false, true)) {
                        write(
                            JSONObject()
                                .put("status", "worker_kill_window")
                                .put("runtime", model.runtime.name.lowercase())
                                .put("pauseMs", workerStartPauseMs)
                                .put("adbProcessName", "$packageName:local_image")
                                .put("mainProcessAlive", true)
                        )
                        try {
                            Thread.sleep(workerStartPauseMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
            }
            client.lastWorkerPid.takeIf { it > 0 }?.let(observedWorkerPid::set)
            mainLeaseFailure.get()?.let { throw it }
            val workerPid = observedWorkerPid.get()
            require(workerPid > 0) { "Worker product path did not report its process id." }
            require(workerPid != Process.myPid()) {
                "Worker product path unexpectedly ran in the main process ($workerPid)."
            }
            if (workerMainLeaseHoldMs > 0L) {
                require(mainLeaseHeld.get()) { "Main-process native lease evidence is missing." }
                require(workerWaitedForNativeLease.get()) {
                    "Worker did not report waiting_for_native_lease while the main lease was held."
                }
            }
            generationOptions.steps?.let { requestedSteps ->
                require(reportedWorkerSteps.get() == requestedSteps) {
                    "Worker 报告了 ${reportedWorkerSteps.get()} 步，但请求的是 $requestedSteps 步。"
                }
            }
            outputFile.writeBytes(result.bytes)
            val isQnn = model.runtime == LocalImageRuntime.QNN_HTP
            val runtimeLabel = when (model.runtime) {
                LocalImageRuntime.QNN_HTP -> "qnn_htp"
                LocalImageRuntime.MNN_DIFFUSION -> "mnn_diffusion"
                LocalImageRuntime.STABLE_DIFFUSION_CPP -> "stable-diffusion.cpp"
                else -> model.runtime.name.lowercase()
            }
            val resultJson = (if (isQnn) {
                qnnWorkerSmokeResult(
                    executionMetadataJson = result.executionMetadataJson,
                    outputBytes = result.bytes,
                    requestedSteps = generationOptions.steps,
                    mainProcessVerification = verificationEvidence
                )
            } else JSONObject())
                .put("ok", true)
                .put("fallback", false)
                .put("mimeType", result.mimeType)
                .put("workerProductPath", true)
                .put("workerPid", workerPid)
            if (model.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
                resultJson
                    .put("backend", "stable-diffusion.cpp")
                    .put("runtimeBackend", "cpu")
                    .put("backendMode", generationOptions.backendMode ?: "cpu")
                    .put("width", generationOptions.width ?: model.imageSize.substringBefore('x').toIntOrNull() ?: 512)
                    .put("height", generationOptions.height ?: model.imageSize.substringAfter('x').toIntOrNull() ?: 512)
                    .put("steps", generationOptions.steps ?: 1)
                    .put("seed", generationOptions.seed ?: JSONObject.NULL)
                    .put("sampleMethod", generationOptions.sampleMethod ?: "euler")
                    // LocalImageProvider rejects the result unless native JNI
                    // confirmed terminal context release first.
                    .put("contextReleased", true)
                result.executionMetadataJson.takeIf { it.isNotBlank() }?.let { metadata ->
                    val executionMetadata = JSONObject(metadata)
                    resultJson.put(
                        "componentSelection",
                        executionMetadata.optJSONObject("componentSelection") ?: JSONObject.NULL
                    )
                }
            }
            write(
                JSONObject()
                    .put("status", "completed")
                    .put("runtime", runtimeLabel)
                    .put("executionMode", "worker_product")
                    .put("workerProductPath", true)
                    .put("qnnGraphExecution", isQnn && resultJson.optBoolean("qnnGraphExecution", false))
                    .put("fallback", false)
                    .put("result", resultJson)
                    .put("nativeStats", resultJson.optJSONObject("nativeEffective") ?: JSONObject.NULL)
                    .put("outputPath", outputFile.absolutePath)
                    .put("outputBytes", outputFile.length())
            )
        } catch (error: Throwable) {
            client.lastWorkerPid.takeIf { it > 0 }?.let(observedWorkerPid::set)
            write(
                JSONObject()
                    .put("status", "worker_failed")
                    .put("runtime", model.runtime.name.lowercase())
                    .put("workerProductPath", true)
                    .put("mainProcessAlive", Process.myPid() > 0)
                    .put("error", error.stackTraceToString())
            )
            throw error
        } finally {
            client.close()
            mainLeaseThread?.interrupt()
            runCatching { mainLeaseThread?.join(5_000L) }
        }
    }

    private fun runQnnImageSmoke(
        bundleRoot: String,
        write: (JSONObject) -> Unit,
        terminalStatus: Boolean = true
    ): JSONObject {
        require(bundleRoot.isNotBlank()) { "bundleRoot is required for QNN smoke" }
        require(NativeQnnBridge.isAvailable) {
            "mca_qnn_native is unavailable: ${NativeQnnBridge.loadError?.message.orEmpty()}"
        }
        require(NativeQnnBridge.runnerReady) {
            NativeQnnBridge().getRuntimeStatsJson()
        }

        val bridge = NativeQnnBridge()
        val runtimeDirsJson = qnnRuntimeDirsJson(bundleRoot)
        val manifestFile = File(bundleRoot, "manifest.json")
        val smokeSpecJson = intent.getStringExtra("smokeSpecJson").orEmpty().ifBlank {
            require(manifestFile.isFile) { "QNN bundle manifest.json is required: ${manifestFile.absolutePath}" }
            qnnGraphSmokeSpecForHarness(File(bundleRoot)).toJson().toString()
        }

        val runtimeInspection = runCatching {
            JSONObject(bridge.inspectRuntime(runtimeDirsJson))
        }.getOrElse { error ->
            JSONObject().put("error", error.stackTraceToString())
        }
        val bundleInspection = runCatching {
            JSONObject(bridge.inspectBundle(bundleRoot))
        }.getOrElse { error ->
            JSONObject().put("error", error.stackTraceToString())
        }
        write(
            JSONObject()
                .put("status", "qnn_starting")
                .put("runtime", "qnn_htp")
                .put("bundleRoot", bundleRoot)
                .put("runtimeDirs", JSONArray(runtimeDirsJson))
                .put("smokeSpec", JSONObject(smokeSpecJson))
                .put("runtimeInspection", runtimeInspection)
                .put("bundleInspection", bundleInspection)
        )

        val result = JSONObject(bridge.runImageSmoke(bundleRoot, runtimeDirsJson, smokeSpecJson))
        val smokeResult = NativeQnnSmokeResult.fromJson(result.toString())
        val diagnostics = QnnExecutionDiagnostics.from(smokeResult)
        val compatibilityMessage = runCatching {
            qnnContextSocCompatibilityMessage(
                device = DeviceProfileReader(applicationContext).read(),
                binaryMetadata = diagnostics.binaryMetadata
            )
        }.getOrNull()
        compatibilityMessage?.let { message ->
            result.put("compatibilityMessage", message)
        }
        val graphExecuted = diagnostics.graphExecuted
        // Device/profile metadata ranks packages, but a real native graph pass
        // is the capability proof. Unknown or mismatched metadata is advisory.
        val productReady = result.optBoolean("ok") && smokeResult.provesNpuExecution
        result.put("qnnGraphExecution", graphExecuted)
        result.put("fallback", false)
        result.put("executionMode", if (graphExecuted) "qnn_graph" else "none")
        result.put("npuExecutionProven", productReady)
        result.put("compatibilityAdvisory", compatibilityMessage != null)
        result.put("compatibilityBlocked", false)
        result.put("runtimeDirs", JSONArray(runtimeDirsJson))
        result.put("runtimeInspection", runtimeInspection)
        result.put("qnnDiagnostics", diagnostics.toJson())
        write(
            JSONObject()
                .put(
                    "status",
                    if (terminalStatus) {
                        if (productReady) "completed" else "failed"
                    } else {
                        if (productReady) "worker_verification_smoke_completed" else "worker_verification_smoke_failed"
                    }
                )
                .put("runtime", "qnn_htp")
                .put("runtimeDirs", JSONArray(runtimeDirsJson))
                .put("runtimeInspection", runtimeInspection)
                .put("qnnGraphExecution", graphExecuted)
                .put("fallback", false)
                .put("result", result)
        )
        return result
    }

    private fun runQwenWorkerDirect(
        bundleRoot: String,
        requestJson: JSONObject,
        outputFile: File,
        write: (JSONObject) -> Unit
    ) {
        val root = File(bundleRoot).canonicalFile
        require(root.isDirectory) { "Qwen bundle directory is missing: ${root.absolutePath}" }
        val runtimePath = intent.getStringExtra("qnnRuntimePath").orEmpty().trim()
        require(runtimePath.isNotBlank()) { "qnnRuntimePath is required for direct Qwen QNN smoke" }
        val fingerprint = root.walkTopDown().filter(File::isFile).sumOf { it.length() }.toString()
        val client = QwenImage21WorkerClient(applicationContext)
        val prompt = requestJson.optString("prompt")
        val width = requestJson.optInt("width", 320)
        val height = requestJson.optInt("height", 320)
        val steps = requestJson.optInt("steps", 2)
        val seed = requestJson.optInt("seed", 1729)
        val threads = requestJson.optInt("threads", 2)
        try {
            write(
                JSONObject()
                    .put("status", "qwen_worker_qnn_loading")
                    .put("runtime", "qnn_htp")
                    .put("bundleRoot", root.absolutePath)
                    .put("runtimePath", runtimePath)
                    .put("request", requestJson)
            )
            val loaded = runBlocking {
                client.load(
                    bundleRoot = root.absolutePath,
                    useGpu = true,
                    backend = QwenImage21Backend.QNN,
                    qnnRuntimePath = runtimePath,
                    threads = threads,
                    bundleFingerprint = fingerprint,
                    requestId = UUID.randomUUID().toString(),
                    onProgress = { progress ->
                        write(
                            JSONObject()
                                .put("status", "progress")
                                .put("phase", "model_loading")
                                .put("progress", progress.progressPercent ?: 0)
                        )
                    }
                )
            }
            write(
                JSONObject()
                    .put("status", "qwen_worker_qnn_loaded")
                    .put("loaded", loaded.rawJson)
            )
            require(loaded.loaded && loaded.backendConfigured == "MNN_QNN") {
                "Qwen worker did not confirm MNN_QNN: ${loaded.rawJson}"
            }
            val generated = runBlocking {
                client.generate(
                    requestId = UUID.randomUUID().toString(),
                    bundleRoot = root.absolutePath,
                    prompt = prompt,
                    inputImagePath = null,
                    outputPath = outputFile.absolutePath,
                    width = width,
                    height = height,
                    steps = steps,
                    seed = seed,
                    threads = threads,
                    useGpu = true,
                    backend = QwenImage21Backend.QNN,
                    qnnRuntimePath = runtimePath,
                    bundleFingerprint = fingerprint,
                    onProgress = { progress ->
                        write(
                            JSONObject()
                                .put("status", "progress")
                                .put("phase", "denoising")
                                .put("progress", progress)
                        )
                    }
                )
            }
            require(outputFile.isFile && outputFile.length() > 1024L) {
                "Qwen QNN worker returned an empty PNG: ${outputFile.absolutePath}"
            }
            write(
                JSONObject()
                    .put("status", "completed")
                    .put("runtime", "qnn_htp")
                    .put("qnnGraphExecution", generated.executionAudit.optBoolean("backendExecutionConfirmedByNative", false))
                    .put("fallback", generated.executionAudit.optBoolean("fallback", false))
                    .put("elapsedMs", generated.elapsedMs)
                    .put("steps", generated.steps)
                    .put("seed", generated.seed)
                    .put("executionAudit", generated.executionAudit)
                    .put("outputPath", outputFile.absolutePath)
                    .put("outputBytes", outputFile.length())
            )
        } finally {
            runCatching { runBlocking { client.unload(UUID.randomUUID().toString()) } }
            runCatching { runBlocking { client.disconnect() } }
        }
    }

    private fun runQnnPipelineProbe(
        bundleRoot: String,
        requestJson: JSONObject,
        outputFile: File,
        write: (JSONObject) -> Unit
    ) {
        require(bundleRoot.isNotBlank()) { "bundleRoot is required for QNN pipeline probe" }
        require(NativeQnnBridge.isAvailable) {
            "mca_qnn_native is unavailable: ${NativeQnnBridge.loadError?.message.orEmpty()}"
        }
        require(NativeQnnBridge.runnerReady) {
            NativeQnnBridge().getRuntimeStatsJson()
        }
        val bridge = NativeQnnBridge()
        val runtimeDirsJson = qnnRuntimeDirsJson(bundleRoot)
        write(
            JSONObject()
                .put("status", "qnn_pipeline_probe_starting")
                .put("runtime", "qnn_htp")
                .put("bundleRoot", bundleRoot)
                .put("request", requestJson)
                .put("outputPath", outputFile.absolutePath)
        )
        val result = JSONObject(
            bridge.runImagePipelineProbe(
                bundleRoot,
                runtimeDirsJson,
                requestJson.toString(),
                outputFile.absolutePath
            )
        )
        val graphExecuted = result.optBoolean("graphExecute", false) || result.optBoolean("npuActive", false)
        result.put("qnnGraphExecution", graphExecuted)
        result.put("fallback", false)
        result.put("executionMode", if (graphExecuted) "qnn_graph" else "none")
        write(
            JSONObject()
                .put("status", if (result.optBoolean("ok")) "completed" else "failed")
                .put("runtime", "qnn_htp")
                .put("qnnGraphExecution", graphExecuted)
                .put("fallback", false)
                .put("request", requestJson)
                .put("result", result)
                .put("outputPath", outputFile.absolutePath)
                .put("outputBytes", outputFile.takeIf { it.exists() }?.length() ?: 0L)
        )
    }

    private fun runQnnSemanticGenerate(
        bundleRoot: String,
        requestJson: JSONObject,
        outputFile: File,
        write: (JSONObject) -> Unit,
        terminalStatus: Boolean = true
    ): JSONObject {
        require(bundleRoot.isNotBlank()) { "bundleRoot is required for QNN semantic generation" }
        val model = debugImageModel(
            runtime = LocalImageRuntime.QNN_HTP,
            bundleRoot = bundleRoot,
            requestedModelPath = intent.getStringExtra("modelPath").orEmpty(),
            family = requestJson.optString("family", "SD15"),
            width = requestJson.optInt("width", 512),
            height = requestJson.optInt("height", 512)
        )
        val provider = LocalImageProvider(applicationContext)
        val options = LocalImageGenerationOptions.fromJson(requestJson)
            .copy(allowLowStepSmoke = true)
        val runtimeDirsJson = JSONArray(
            qnnRuntimeDirectoriesFor(this, File(bundleRoot))
        ).toString()
        val runtimeInspection = runCatching {
            JSONObject(NativeQnnBridge().inspectRuntime(runtimeDirsJson))
        }.getOrElse { error ->
            JSONObject().put("error", error.stackTraceToString())
        }
        write(
            JSONObject()
                .put("status", "qnn_semantic_product_starting")
                .put("runtime", "qnn_htp")
                .put("bundleRoot", bundleRoot)
                .put("runtimeDirs", JSONArray(runtimeDirsJson))
                .put("runtimeInspection", runtimeInspection)
                .put("request", requestJson)
                .put("model", model.toJson())
                .put("outputPath", outputFile.absolutePath)
        )
        val generated = runBlocking {
            provider.begin(LocalImageRuntime.QNN_HTP)
            provider.generate(model, options = options, prompt = requestJson.optString("prompt")) { progress ->
                write(
                    JSONObject()
                        .put("status", "progress")
                        .put("runtime", "qnn_htp")
                        .put("phase", progress.phase)
                        .put("step", progress.step)
                        .put("steps", progress.steps)
                        .put("message", progress.message)
                        .put("progressElapsedMs", progress.elapsedMs)
                )
            }
        }
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(generated.bytes)
        val result = JSONObject(generated.executionMetadataJson)
        // LocalImageProvider has already validated the native execution
        // contract. Its sanitized metadata intentionally exposes the stable
        // graph/native flags, while semanticReady is a debug projection that
        // older providers did not preserve. Derive it from the validated
        // flags instead of treating an omitted optional field as failure.
        val graphExecuted = result.optBoolean("npuActive", false) &&
            result.optBoolean("qnnGraphExecution", false) &&
            result.optBoolean("nativeExecution", false)
        // LocalImageProvider has already completed the strict execution and PNG
        // contracts. The debug protocol exposes the native result under
        // `result`, so mirror the terminal booleans expected by DeviceSmoke.
        result.put("ok", generated.bytes.isNotEmpty() && graphExecuted)
        result.put("semanticReady", graphExecuted)
        result.put("qnnGraphExecution", graphExecuted)
        result.put("fallback", result.optBoolean("fallback", false))
        result.put("executionMode", if (graphExecuted) "qnn_graph" else "none")
        result.put("runtimeDirs", JSONArray(runtimeDirsJson))
        result.put("runtimeInspection", runtimeInspection)
        val selectedHtpArch = result.optInt("selectedHtpArch", 0)
        val transportHtpArch = result.optInt("transportHtpArch", 0)
        result.put("selectedHtpArch", selectedHtpArch)
        result.put("transportHtpArch", transportHtpArch)
        val passed = result.optBoolean("ok", false) && graphExecuted
        write(
            JSONObject()
                .put(
                    "status",
                    if (terminalStatus) {
                        if (passed) "completed" else "failed"
                    } else {
                        if (passed) "worker_verification_semantic_completed" else "worker_verification_semantic_failed"
                    }
                )
                .put("runtime", "qnn_htp")
                .put("runtimeDirs", JSONArray(runtimeDirsJson))
                .put("runtimeInspection", runtimeInspection)
                .put("selectedHtpArch", selectedHtpArch)
                .put("transportHtpArch", transportHtpArch)
                .put("qnnGraphExecution", graphExecuted)
                .put("fallback", result.optBoolean("fallback", false))
                .put("request", requestJson)
                .put("result", result)
                .put("outputPath", outputFile.absolutePath)
                .put("outputBytes", generated.bytes.size)
        )
        return result
    }

    private fun runProductionMnnGenerate(
        bundleRoot: String,
        requestJson: JSONObject,
        outputFile: File,
        write: (JSONObject) -> Unit
    ) {
        val conditioningOrder = if (requestJson.optBoolean("useCfg", true)) {
            "negative_then_positive"
        } else {
            "positive_only"
        }
        val model = debugImageModel(
            runtime = LocalImageRuntime.MNN_DIFFUSION,
            bundleRoot = bundleRoot,
            requestedModelPath = intent.getStringExtra("modelPath").orEmpty(),
            family = requestJson.optString("family", "SD15"),
            width = requestJson.optInt("width", 512),
            height = requestJson.optInt("height", 512)
        )
        val provider = LocalImageProvider(applicationContext)
        val options = LocalImageGenerationOptions.fromJson(requestJson)
            .copy(allowLowStepSmoke = true)
        val generated = runBlocking {
            provider.begin(LocalImageRuntime.MNN_DIFFUSION)
            provider.generate(model, options = options, prompt = requestJson.optString("prompt")) { progress ->
                write(
                    JSONObject()
                        .put("status", "progress")
                        .put("runtime", "mnn_diffusion")
                        .put("phase", progress.phase)
                        .put("step", progress.step)
                        .put("steps", progress.steps)
                        .put("message", progress.message)
                        .put("progressElapsedMs", progress.elapsedMs)
                )
            }
        }
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(generated.bytes)
        val result = JSONObject(generated.executionMetadataJson)
        result.put("ok", generated.bytes.isNotEmpty())
        write(
            JSONObject()
                .put("status", "completed")
                .put("runtime", "mnn_diffusion")
                .put("request", requestJson)
                .put("conditioningOrder", conditioningOrder)
                .put("result", result)
                .put("outputPath", outputFile.absolutePath)
                .put("outputBytes", generated.bytes.size)
        )
    }

    private fun debugImageModel(
        runtime: LocalImageRuntime,
        bundleRoot: String,
        requestedModelPath: String,
        family: String,
        width: Int,
        height: Int
    ): LocalImageModelRecord {
        val root = File(bundleRoot).canonicalFile
        val requested = requestedModelPath.trim().takeIf { it.isNotEmpty() }?.let(::File)
        val manifestFile = File(root, "manifest.json").canonicalFile
        val manifest = localImageBundleManifestFromRoot(root)
        val declaredPrimary = manifest?.primaryFile
            ?.takeIf { it.isFile && it.length() > 0L }
        val smokePrimary = manifest
            ?.qnnSmokeSpecs
            ?.asSequence()
            ?.mapNotNull { spec -> spec.contextBinaryFileIn(root) }
            ?.firstOrNull()
        val primary = sequenceOf(
            // The harness historically defaulted modelPath to manifest.json.
            // Treat that value as metadata and resolve the executable artifact
            // from the installed bundle instead of using JSON as the model.
            requested?.takeUnless { it.canonicalFile == manifestFile },
            declaredPrimary,
            smokePrimary,
            File(root, if (runtime == LocalImageRuntime.MNN_DIFFUSION) "unet.mnn" else "unet.bin")
        ).filterNotNull().map { candidate ->
            runCatching { candidate.canonicalFile }.getOrDefault(candidate.absoluteFile)
        }.firstOrNull { it.isFile && it.length() > 0L }
            ?: error("No usable image model artifact exists under ${root.absolutePath}.")
        return LocalImageModelRecord(
            id = "debug-${runtime.name.lowercase()}-${root.name}",
            displayName = root.name.ifBlank { "Debug local image model" },
            path = primary.absolutePath,
            fileName = primary.name,
            sizeBytes = primary.length(),
            sha256 = "",
            runtime = runtime,
            family = LocalImageModelFamily.from(family.uppercase()),
            imageSize = "${width}x$height",
            source = "debug-product-path",
            bundleRoot = root.absolutePath,
            // Preserve the installed catalog identity in the debug record so
            // a minimal manifest can still select the complete built-in
            // profile when the primary GGUF path is not the catalog filename.
            recommendationId = manifest?.recommendationId ?: manifest?.id,
            componentCount = root.walkTopDown().count { it.isFile }.coerceAtLeast(1),
            verificationStatus = LocalImageVerificationStatus.UNKNOWN,
            verificationMessage = "",
            verifiedAt = 0L
        )
    }

    private fun runMnnDiffusionSmoke(
        bundleRoot: String,
        requestJson: JSONObject,
        outputFile: File,
        write: (JSONObject) -> Unit
    ) {
        require(NativeMnnDiffusionBridge.isAvailable) {
            "mca_mnn_native is unavailable: ${NativeMnnDiffusionBridge.loadError?.message.orEmpty()}"
        }
        require(NativeMnnDiffusionBridge.runnerReady) {
            NativeMnnDiffusionBridge().getRuntimeStatsJson()
        }
        val bridge = NativeMnnDiffusionBridge()
        val progressThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    val progress = JSONObject(bridge.getProgress())
                    write(JSONObject().put("status", "progress").put("runtime", "mnn_diffusion").put("progress", progress))
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }
        progressThread.start()
        val result = try {
            bridge.generate(
                bundleRoot,
                requestJson.toString(),
                outputFile.absolutePath
            )
        } finally {
            progressThread.interrupt()
        }
        val resultJson = JSONObject(result)
        write(
            JSONObject()
                .put("status", if (resultJson.optBoolean("ok")) "completed" else "failed")
                .put("runtime", "mnn_diffusion")
                .put("request", requestJson)
                .put("result", resultJson)
                .put("stats", JSONObject(bridge.getRuntimeStatsJson()))
                .put("outputPath", outputFile.absolutePath)
                .put("outputBytes", outputFile.takeIf { it.exists() }?.length() ?: 0L)
        )
    }

    private fun deviceSnapshotJson(): JSONObject = runCatching {
        DeviceProfileReader(applicationContext).read().toJson()
    }.getOrElse { error ->
        JSONObject().put("error", error.stackTraceToString())
    }

    private fun processMemoryJson(): JSONObject {
        val runtime = Runtime.getRuntime()
        val nativeHeapAllocated = android.os.Debug.getNativeHeapAllocatedSize()
        val nativeHeapSize = android.os.Debug.getNativeHeapSize()
        val memoryInfo = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(memoryInfo)
        return JSONObject()
            .put("javaMaxBytes", runtime.maxMemory())
            .put("javaTotalBytes", runtime.totalMemory())
            .put("javaFreeBytes", runtime.freeMemory())
            .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeHeapAllocatedBytes", nativeHeapAllocated)
            .put("nativeHeapSizeBytes", nativeHeapSize)
            .put("totalPssKb", memoryInfo.totalPss)
            .put("dalvikPssKb", memoryInfo.dalvikPss)
            .put("nativePssKb", memoryInfo.nativePss)
            .put("otherPssKb", memoryInfo.otherPss)
    }

    private fun stageLoraFile(
        sourcePath: String,
        loraSha256: String,
        loraSizeBytes: Long
    ): String {
        val source = File(sourcePath).canonicalFile
        require(source.isFile && source.length() == loraSizeBytes) {
            "LoRA smoke source changed while staging: ${source.absolutePath}"
        }
        val destinationRoot = File(filesDir, "image_loras").apply { mkdirs() }
        val safeName = source.name
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "adapter.safetensors" }
        val destination = File(destinationRoot, "${loraSha256.take(16)}-$safeName")
        if (destination.isFile && destination.length() == loraSizeBytes) {
            return destination.absolutePath
        }
        val temporary = File(destinationRoot, ".${destination.name}.${Process.myPid()}.part")
        temporary.delete()
        source.inputStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        require(temporary.length() == loraSizeBytes) {
            temporary.delete()
            "Staged LoRA size does not match metadata."
        }
        require(temporary.renameTo(destination) ||
            (destination.isFile && destination.length() == loraSizeBytes)) {
            temporary.delete()
            "Unable to publish staged LoRA file."
        }
        temporary.delete()
        return destination.absolutePath
    }

    private fun Intent.numberExtra(name: String, default: Double): Double {
        val value = extras?.get(name) ?: return default
        val parsed = when (value) {
            is Double -> value
            is Float -> value.toDouble()
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: default
            else -> default
        }
        return parsed.takeIf(Double::isFinite) ?: default
    }
}

package com.muyuchat.mca

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Debug-only entry point for a real Qwen-Image-2.1 QNN run.
 *
 * This intentionally drives the same isolated worker used by the product UI,
 * so a successful result is evidence for the production Binder/native path.
 */
class QwenImage21QnnSmokeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Qwen QNN smoke running..." })
        scope.launch {
            val tag = "QwenQnnSmoke"
            val bundleContainer = File(
                getExternalFilesDir(null),
                "image_models/bundle-qwen_image_21_mnn_opencl_bundle"
            )
            // The device fixture is installed as a managed download container;
            // its audited MNN bundle is the nested mnn-bundle-* directory.
            val bundle = File(bundleContainer, "mnn-bundle-f9d98aa3").takeIf { it.isDirectory }
                ?: bundleContainer
            val requestedBackend = getIntent().getStringExtra("backend").orEmpty()
                .trim().lowercase().let { if (it == "opencl") QwenImage21Backend.OPENCL else QwenImage21Backend.QNN }
            val qnnRuntimeMode = getIntent().getStringExtra("qnnRuntimeMode")
                ?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: "product"
            val width = getIntent().getIntExtra("width", 320)
            val height = getIntent().getIntExtra("height", 320)
            val steps = getIntent().getIntExtra("steps", 2)
            val seed = getIntent().getIntExtra("seed", 1729)
            val output = File(
                getExternalFilesDir(null),
                "qwen-${requestedBackend.name.lowercase()}-smoke-${width}x${height}-${steps}step-seed$seed.png"
            )
            try {
                require(bundle.isDirectory) { "Qwen model bundle missing: ${bundle.absolutePath}" }
                require(QwenImage21SizeContract.isSupported(width, height)) {
                    QwenImage21SizeContract.unsupportedSizeMessage(width, height)
                }
                require(steps in 1..100) { "Smoke steps must be between 1 and 100." }
                require(qnnRuntimeMode in setOf("product", "litert-staged", "platform", "external")) {
                    "QNN runtime mode must be product, litert-staged, platform, or external."
                }
                val runtimeStage = if (requestedBackend == QwenImage21Backend.QNN &&
                    qnnRuntimeMode == "litert-staged"
                ) {
                    LiteRtQualcommRuntimeStager.stage(applicationContext)
                } else null
                val runtime = if (requestedBackend == QwenImage21Backend.QNN) {
                    when (qnnRuntimeMode) {
                        "litert-staged" -> runtimeStage?.directory ?: File(applicationInfo.nativeLibraryDir)
                        "platform" -> File("/vendor/lib64")
                        "external" -> {
                            val provided = getIntent().getStringExtra("qnnRuntimePath")
                                ?.trim()
                                ?.takeIf(String::isNotBlank)
                                ?.let(::File)
                            val staged = File(codeCacheDir, "qnn-runtime-2.45-v73")
                            val externalStage = getExternalFilesDir(null)
                                ?.let { File(it, "qnn-runtime-2.45-v73-stage") }
                            val sourceRuntime = provided?.takeIf(File::isDirectory) ?: externalStage
                            if (!staged.isDirectory && sourceRuntime?.isDirectory == true) {
                                check(staged.mkdirs() || staged.isDirectory) {
                                    "Cannot create private QNN runtime cache: ${staged.absolutePath}"
                                }
                                sourceRuntime.listFiles().orEmpty()
                                    .filter { it.isFile && it.name.startsWith("libQnn") }
                                    .forEach { source ->
                                        val target = source.copyTo(File(staged, source.name), overwrite = true)
                                        target.setReadable(true, true)
                                        target.setExecutable(true, true)
                                    }
                            }
                            val selected = listOfNotNull(staged, provided?.takeIf { candidate ->
                                candidate.canonicalPath.startsWith(filesDir.canonicalPath + File.separator) ||
                                    candidate.canonicalPath.startsWith(codeCacheDir.canonicalPath + File.separator)
                            })
                                .firstOrNull(File::isDirectory)
                                ?: requireNotNull(provided ?: staged) {
                                    "QNN runtime directory is unavailable in external mode"
                                }
                            Log.i(
                                tag,
                                "QWEN_QNN_RUNTIME_PATH_RESOLUTION provided=${provided?.absolutePath.orEmpty()} " +
                                    "selected=${selected.absolutePath} exists=${selected.exists()} " +
                                    "directory=${selected.isDirectory} readable=${selected.canRead()} " +
                                    "entries=${selected.listFiles()?.size ?: -1} " +
                                    "externalStage=${externalStage?.absolutePath.orEmpty()}"
                            )
                            selected
                        }
                        else -> File(applicationInfo.nativeLibraryDir)
                    }
                } else null
                if (runtime != null) {
                    val htpVariant = LiteRtQualcommRuntimeStager.variantForSocModel(android.os.Build.SOC_MODEL)
                    val transportFiles = when (htpVariant) {
                        "v73" -> listOf("libQnnHtpV73Stub.so", "libQnnHtpV73Skel.so")
                        "v75" -> listOf("libQnnHtpV75Stub.so", "libQnnHtpV75Skel.so")
                        "v79" -> listOf("libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so")
                        "v81" -> listOf("libQnnHtpV81Stub.so", "libQnnHtpV81Skel.so")
                        else -> emptyList()
                    }
                    val required = listOf("libQnnSystem.so", "libQnnHtp.so") + when (qnnRuntimeMode) {
                        "litert-staged" -> transportFiles
                        "external" -> listOf("libQnnHtpPrepare.so") + transportFiles
                        "platform" -> emptyList()
                        else -> listOf("libQnnHtpPrepare.so")
                    }
                    val presentTransportFiles = transportFiles.filter { File(runtime, it).isFile }
                    require(required.all { File(runtime, it).isFile }) {
                        "QAIRT runtime directory is incomplete for ${android.os.Build.SOC_MODEL}: " +
                            "${runtime.listFiles()?.map { it.name }}"
                    }
                    Log.i(
                        tag,
                        "QWEN_QNN_RUNTIME mode=$qnnRuntimeMode " +
                            "sdk=${when (qnnRuntimeMode) {
                                "litert-staged" -> "2.47.0.260601114230"
                                "platform" -> "oem-platform"
                                "external" -> "caller-provided"
                                else -> "app-native"
                            }} " +
                            "variant=${runtimeStage?.variant ?: htpVariant ?: "unknown"} " +
                            "fingerprint=${runtimeStage?.fingerprint ?: "$qnnRuntimeMode-directory"} " +
                            "directory=${runtime.absolutePath} " +
                            "required=${required.joinToString(",")} " +
                            "transportPresent=${presentTransportFiles.joinToString(",")}"
                    )
                }
                val fingerprint = bundle.walkTopDown().filter(File::isFile).sumOf { it.length() }.toString()
                val client = QwenImage21WorkerClient(this@QwenImage21QnnSmokeActivity)
                try {
                    val loaded = client.load(
                        bundleRoot = bundle.absolutePath,
                        useGpu = true,
                        backend = requestedBackend,
                        qnnRuntimePath = runtime?.absolutePath,
                        threads = 2,
                        bundleFingerprint = fingerprint,
                        requestId = UUID.randomUUID().toString()
                    )
                    Log.i(tag, "QWEN_QNN_LOAD ${loaded.rawJson}")
                    require(loaded.loaded && loaded.backendConfigured == requestedBackend.nativeAuditName) {
                        "Qwen load did not resolve ${requestedBackend.nativeAuditName}: ${loaded.rawJson}"
                    }
                    val result = client.generate(
                        requestId = UUID.randomUUID().toString(),
                        bundleRoot = bundle.absolutePath,
                        prompt = "a red apple on a white table, studio photo",
                        inputImagePath = null,
                        outputPath = output.absolutePath,
                        width = width,
                        height = height,
                        steps = steps,
                        seed = seed,
                        threads = 2,
                        useGpu = true,
                        backend = requestedBackend,
                        qnnRuntimePath = runtime?.absolutePath,
                        bundleFingerprint = fingerprint,
                        onProgress = { progress -> Log.i(tag, "QWEN_QNN_PROGRESS $progress") }
                    )
                    Log.i(
                        tag,
                        "QWEN_QNN_SMOKE backend=${requestedBackend.nativeAuditName} width=${width} height=${height} " +
                            "steps=$steps seed=$seed output=${result.outputPath} bytes=${result.outputBytes} " +
                            "elapsedMs=${result.elapsedMs} audit=${result.executionAudit}"
                    )
                    require(result.executionAudit.optBoolean("nativeRunCompleted", false)) {
                        "QNN generation did not complete natively: ${result.executionAudit}"
                    }
                    require(result.executionAudit.optBoolean("backendExecutionConfirmedByNative", false)) {
                        "QNN backend not confirmed by native audit: ${result.executionAudit}"
                    }
                    require(output.isFile && output.length() > 1024L) { "Output PNG missing or empty" }
                } finally {
                    runCatching { client.unload() }
                }
            } catch (error: Throwable) {
                Log.e(tag, "QWEN_QNN_SMOKE_FAILED ${error.message}", error)
            }
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

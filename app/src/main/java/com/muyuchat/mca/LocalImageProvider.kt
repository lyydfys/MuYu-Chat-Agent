package com.muyuchat.mca

import android.content.Context
import android.util.Log
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.nativebridge.NativeQnnBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal const val STABLE_DIFFUSION_OUTPUT_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
internal const val MNN_DIFFUSION_OUTPUT_MAX_AGE_MS = 24L * 60L * 60L * 1_000L

internal data class VerifiedQnnImageOutput(
    val bytes: ByteArray,
    val mimeType: String,
    val outputBytes: Long
)


/** Preserves a concrete native product error instead of collapsing it into generation_failed. */
internal fun throwLocalImageNativeFailure(
    result: JSONObject,
    fallbackMessage: String
): Nothing {
    val message = sequenceOf(
        result.optString("error"),
        result.optString("message"),
        fallbackMessage
    ).map { it.trim() }.firstOrNull(String::isNotEmpty).orEmpty()
    val code = result.optString("errorCode").trim()
    if (code.isNotEmpty()) {
        throw LocalImageProductContractException(code.lowercase(), message)
    }
    error(message)
}

/** Logs a native failure at the Android boundary without coupling pure error mapping to Log. */
private fun logLocalImageNativeFailure(result: JSONObject, fallbackMessage: String) {
    val message = sequenceOf(
        result.optString("error"),
        result.optString("message"),
        fallbackMessage
    ).map { it.trim() }.firstOrNull(String::isNotEmpty).orEmpty()
    val code = result.optString("errorCode").trim()
    Log.e(
        "MCA-LocalImage",
        "native_failure code=${code.ifBlank { "unknown" }} " +
            "backend=${result.optString("backend").take(64)} " +
            "stage=${result.optString("executionStage").take(64)} " +
            "message=${message.take(512)}"
    )
}

class LocalImageProvider(context: Context) {
    private val appContext = context.applicationContext
    private val textualInversionStore by lazy { TextualInversionStore(appContext) }
    private val bridge by lazy { NativeStableDiffusionBridge() }
    private val mnnDiffusionBridge by lazy { NativeMnnDiffusionBridge() }
    private val qnnBridge by lazy { NativeQnnBridge() }
    private val qwenImage21Worker by lazy { QwenImage21WorkerClient(appContext) }
    private val qwenImage21RuntimeResident = AtomicBoolean(false)
    private val activeQwenImage21RequestId = AtomicReference<String?>(null)
    private val sdxlCoordinator by lazy { SdxlTwoPhaseCoordinator(appContext) }
    private val cancellationRequested = AtomicBoolean(false)
    private val activeSplitSdxlTerminal =
        AtomicReference<SplitSdxlRequestTerminalArbiter?>(null)

    @Volatile
    private var activeRuntime: LocalImageRuntime? = null

    private val localImageGenerationPort: LocalImageGenerationPort =
        object : LocalImageGenerationPort {
            override val appContext get() = this@LocalImageProvider.appContext
            override val textualInversionStore get() = this@LocalImageProvider.textualInversionStore
            override val bridge get() = this@LocalImageProvider.bridge
            override val mnnDiffusionBridge get() = this@LocalImageProvider.mnnDiffusionBridge
            override val qnnBridge get() = this@LocalImageProvider.qnnBridge
            override val qwenImage21Worker get() = this@LocalImageProvider.qwenImage21Worker
            override val qwenImage21RuntimeResident get() = this@LocalImageProvider.qwenImage21RuntimeResident
            override val sdxlCoordinator get() = this@LocalImageProvider.sdxlCoordinator
            override val cancellationRequested get() = this@LocalImageProvider.cancellationRequested
            override val activeSplitSdxlTerminal get() = this@LocalImageProvider.activeSplitSdxlTerminal
            override var activeRuntime: LocalImageRuntime?
                get() = this@LocalImageProvider.activeRuntime
                set(value) {
                    this@LocalImageProvider.activeRuntime = value
                }

            override fun begin(runtime: LocalImageRuntime) {
                this@LocalImageProvider.begin(runtime)
            }

            override fun logLocalImageNativeFailure(result: JSONObject, fallbackMessage: String) {
                com.muyuchat.mca.logLocalImageNativeFailure(result, fallbackMessage)
            }

            override suspend fun generateQwenImage21(
                model: LocalImageModelRecord,
                prompt: String,
                options: LocalImageGenerationOptions,
                onProgress: (LocalImageProgress) -> Unit
            ): LocalImageResult =
                runQwenImage21Generation(localImageQwenImage21Port, model, prompt, options, onProgress)

            override fun generateMediaTekAnima(
                model: LocalImageModelRecord,
                prompt: String,
                options: LocalImageGenerationOptions,
                isCancellationRequested: () -> Boolean,
                onProgress: (LocalImageProgress) -> Unit
            ): LocalImageResult = executeMediaTekAnimaGeneration(
                context = appContext,
                model = model,
                prompt = prompt,
                options = options,
                isCancellationRequested = isCancellationRequested,
                onProgress = onProgress
            )
        }

    private val localImageUpscalePort: LocalImageUpscalePort =
        object : LocalImageUpscalePort {
            override val appContext get() = this@LocalImageProvider.appContext
            override val bridge get() = this@LocalImageProvider.bridge
            override val cancellationRequested get() = this@LocalImageProvider.cancellationRequested
            override var activeRuntime: LocalImageRuntime?
                get() = this@LocalImageProvider.activeRuntime
                set(value) {
                    this@LocalImageProvider.activeRuntime = value
                }

            override fun logLocalImageNativeFailure(result: JSONObject, fallbackMessage: String) {
                com.muyuchat.mca.logLocalImageNativeFailure(result, fallbackMessage)
            }

            override fun begin(runtime: LocalImageRuntime) {
                this@LocalImageProvider.begin(runtime)
            }
        }

    private val localImageCancellationPort: LocalImageCancellationPort =
        object : LocalImageCancellationPort {
            override val activeRuntime get() = this@LocalImageProvider.activeRuntime
            override val activeSplitSdxlTerminal get() = this@LocalImageProvider.activeSplitSdxlTerminal
            override val cancellationRequested get() = this@LocalImageProvider.cancellationRequested
            override val sdxlCoordinator get() = this@LocalImageProvider.sdxlCoordinator
            override val qnnBridge get() = this@LocalImageProvider.qnnBridge
            override val mnnDiffusionBridge get() = this@LocalImageProvider.mnnDiffusionBridge
            override val qwenImage21Worker get() = this@LocalImageProvider.qwenImage21Worker
            override val qwenImage21RuntimeResident get() = this@LocalImageProvider.qwenImage21RuntimeResident
            override val activeQwenImage21RequestId get() = this@LocalImageProvider.activeQwenImage21RequestId
            override val bridge get() = this@LocalImageProvider.bridge
        }

    private val localImageQwenImage21Port: LocalImageQwenImage21Port =
        object : LocalImageQwenImage21Port {
            override val appContext get() = this@LocalImageProvider.appContext
            override val qwenImage21Worker get() = this@LocalImageProvider.qwenImage21Worker
            override val qwenImage21RuntimeResident get() = this@LocalImageProvider.qwenImage21RuntimeResident
            override val activeQwenImage21RequestId get() = this@LocalImageProvider.activeQwenImage21RequestId
            override val cancellationRequested get() = this@LocalImageProvider.cancellationRequested
        }

    fun begin(runtime: LocalImageRuntime) {
        cancellationRequested.set(false)
        if (runtime in setOf(LocalImageRuntime.QNN_HTP, LocalImageRuntime.MNN_DIFFUSION) &&
            NativeMnnDiffusionBridge.isAvailable
        ) {
            runCatching { mnnDiffusionBridge.resetImageCancellation() }
        }
        if (runtime == LocalImageRuntime.QNN_HTP) {
            sdxlCoordinator.begin()
        }
        activeRuntime = runtime
    }

    fun cancel(): Boolean = runLocalImageCancellation(localImageCancellationPort)

    fun nativeConfig(): JSONObject? =
        if (NativeStableDiffusionBridge.isAvailable) {
            runCatching { JSONObject(bridge.getNativeConfig()) }.getOrNull()
        } else {
            null
        }

    suspend fun upscale(
        input: LocalImagePreparedInput,
        upscaler: LocalImagePreparedUpscaler,
        targetScale: Int,
        tileSize: Int = 128,
        threads: Int = defaultLocalImageThreads(),
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult =
        runLocalImageUpscale(localImageUpscalePort, input, upscaler, targetScale, tileSize, threads, onProgress)

    suspend fun generate(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult =
        runLocalImageGeneration(localImageGenerationPort, model, prompt, options, onProgress)

}


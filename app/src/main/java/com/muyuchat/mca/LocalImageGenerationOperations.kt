package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.engine.SystemRuntimeMonotonicClock
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.nativebridge.NativeQnnBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class LocalImageTextualInversionLeaseCell {
    var value: TextualInversionSelectionLease? = null

    operator fun getValue(
        thisRef: Any?,
        property: kotlin.reflect.KProperty<*>
    ): TextualInversionSelectionLease? = value

    operator fun setValue(
        thisRef: Any?,
        property: kotlin.reflect.KProperty<*>,
        newValue: TextualInversionSelectionLease?
    ) {
        value = newValue
    }
}

/**
 * The local-image generation owner (P5/L8). LocalImageProvider.generate's body moved here verbatim
 * as runLocalImageGeneration(port, ...): the provider keeps begin/cancel/generateQwenImage21/upscale/
 * nativeConfig and supplies state through LocalImageGenerationPort implemented by a private port
 * object. The SplitSdxl request-terminal arbiter pair moved with the body (private -> internal,
 * recorded: the port exposes the same AtomicReference instance, so terminal semantics are unchanged).
 * activeRuntime stays a live volatile read through the port on purpose (begin() updates it mid-call).
 */

internal suspend fun runLocalImageGeneration(
    port: LocalImageGenerationPort,
    model: LocalImageModelRecord,
    prompt: String,
    options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
    onProgress: (LocalImageProgress) -> Unit = {}
): LocalImageResult {
    val appContext = port.appContext
    // The provider owns lazy runtime resources. Keep accesses inside the selected IO
    // branch, matching the original provider rather than loading every backend at entry.
    val textualInversionStore by lazy(LazyThreadSafetyMode.NONE) { port.textualInversionStore }
    val bridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { port.bridge }
    val mnnDiffusionBridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { port.mnnDiffusionBridge }
    val qnnBridge by lazy(LazyThreadSafetyMode.NONE) { port.qnnBridge }
    val qwenImage21Worker by lazy(LazyThreadSafetyMode.NONE) { port.qwenImage21Worker }
    val qwenImage21RuntimeResident = port.qwenImage21RuntimeResident
    val sdxlCoordinator by lazy(LazyThreadSafetyMode.NONE) { port.sdxlCoordinator }
    val cancellationRequested = port.cancellationRequested
    val activeSplitSdxlTerminal = port.activeSplitSdxlTerminal
    return withContext(Dispatchers.IO) {
        val options = options.withCanonicalUltraFixControls()
        var textualInversionLease: TextualInversionSelectionLease? = null
        try {
            model.localImageStructuralReadinessMessage()?.let { message -> error(message) }
            require(prompt.isNotBlank()) { "请输入图片描述。" }
            require(!cancellationRequested.get()) { "本地生图已停止" }
            options.validateProductInputContract()
            validateLocalImageRuntimeProductOptions(model.runtime, options)
            if (port.activeRuntime != model.runtime) port.begin(model.runtime)
            if (model.isQwenImage21MnnModel()) {
                return@withContext port.generateQwenImage21(
                    model = model,
                    prompt = prompt,
                    options = options,
                    onProgress = onProgress
                )
            }
            if (qwenImage21RuntimeResident.get()) {
                // The Qwen model is close to 10 GB. Release its isolated MNN context before
                // another image backend is allowed to allocate its own working set.
                try {
                    qwenImage21Worker.unload()
                } finally {
                    qwenImage21RuntimeResident.set(false)
                }
            }
            if (model.runtime == LocalImageRuntime.QNN_HTP) {
            val qnnLeaseCell = LocalImageTextualInversionLeaseCell()
            return@withContext try {
                val qnnExecution = runQnnHtpPreparation(
                    port = port,
                    model = model,
                    prompt = prompt,
                    options = options,
                    appContext = appContext,
                    onProgress = onProgress,
                    textualInversionStoreRef = { textualInversionStore },
                    qnnBridgeRef = { qnnBridge },
                    mnnDiffusionBridgeRef = { mnnDiffusionBridge },
                    sdxlCoordinatorRef = { sdxlCoordinator },
                    cancellationRequested = cancellationRequested,
                    activeSplitSdxlTerminal = activeSplitSdxlTerminal,
                    textualInversionLeaseCell = qnnLeaseCell
                )
                qnnExecution.runQnnHtpExecution()
            } finally {
                textualInversionLease = qnnLeaseCell.value
            }
        }
        require(model.runtime != LocalImageRuntime.QNN_HTP) {
            "QNN/QAIRT 本地生图 runner 尚未打包。该引擎包需要骁龙 NPU runtime、完整 QNN context/bin 组件，并通过 1-step smoke test 后才能生成。"
        }
        if (model.runtime == LocalImageRuntime.MNN_DIFFUSION) {
            val mnnLeaseCell = LocalImageTextualInversionLeaseCell()
            return@withContext try {
                runMnnDiffusionGeneration(
                    port = port,
                    model = model,
                    prompt = prompt,
                    options = options,
                    appContext = appContext,
                    onProgress = onProgress,
                    mnnDiffusionBridgeRef = { mnnDiffusionBridge },
                    textualInversionStoreRef = { textualInversionStore },
                    cancellationRequested = cancellationRequested,
                    textualInversionLeaseCell = mnnLeaseCell
                )
            } finally {
                textualInversionLease = mnnLeaseCell.value
            }
        }
        require(model.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
            "当前仅支持 stable-diffusion.cpp 或 MNN-Diffusion 本地图像引擎。"
        }
        val sdcppLeaseCell = LocalImageTextualInversionLeaseCell()
        return@withContext try {
            runStableDiffusionCppGeneration(
                port = port,
                model = model,
                prompt = prompt,
                options = options,
                appContext = appContext,
                onProgress = onProgress,
                bridgeRef = { bridge },
                textualInversionStoreRef = { textualInversionStore },
                cancellationRequested = cancellationRequested,
                textualInversionLeaseCell = sdcppLeaseCell
            )
        } finally {
            textualInversionLease = sdcppLeaseCell.value
        }
        } finally {
            textualInversionLease?.close()
            textualInversionLease = null
            if (port.activeRuntime == model.runtime) {
                port.activeRuntime = null
                cancellationRequested.set(false)
            }
        }
    }
}

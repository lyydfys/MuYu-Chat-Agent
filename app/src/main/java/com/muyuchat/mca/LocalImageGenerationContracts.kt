package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.nativebridge.NativeQnnBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Local image generation contracts (P7/S32), moved verbatim from
 * LocalImageGenerationOperations.kt: the generation port surface and the split-SDXL request
 * terminal-state arbiter. The execution body stays in its own file.
 */

internal interface LocalImageGenerationPort {
    val appContext: Context
    val textualInversionStore: TextualInversionStore
    val bridge: NativeStableDiffusionBridge
    val mnnDiffusionBridge: NativeMnnDiffusionBridge
    val qnnBridge: NativeQnnBridge
    val qwenImage21Worker: QwenImage21WorkerClient
    val qwenImage21RuntimeResident: AtomicBoolean
    val sdxlCoordinator: SdxlTwoPhaseCoordinator
    val cancellationRequested: AtomicBoolean
    val activeSplitSdxlTerminal: AtomicReference<SplitSdxlRequestTerminalArbiter?>
    var activeRuntime: LocalImageRuntime?

    fun begin(runtime: LocalImageRuntime)

    fun logLocalImageNativeFailure(result: JSONObject, fallbackMessage: String)

    suspend fun generateQwenImage21(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        onProgress: (LocalImageProgress) -> Unit
    ): LocalImageResult

    fun generateMediaTekAnima(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        isCancellationRequested: () -> Boolean,
        onProgress: (LocalImageProgress) -> Unit
    ): LocalImageResult
}

internal enum class SplitSdxlRequestTerminalState {
    PENDING,
    CANCELLED,
    TRANSFERRED
}

internal class SplitSdxlRequestTerminalArbiter {
    private val state = AtomicReference(SplitSdxlRequestTerminalState.PENDING)

    fun cancel(): Boolean = state.compareAndSet(
        SplitSdxlRequestTerminalState.PENDING,
        SplitSdxlRequestTerminalState.CANCELLED
    )

    fun transfer(): Boolean = state.compareAndSet(
        SplitSdxlRequestTerminalState.PENDING,
        SplitSdxlRequestTerminalState.TRANSFERRED
    )

    fun current(): SplitSdxlRequestTerminalState = state.get()
}

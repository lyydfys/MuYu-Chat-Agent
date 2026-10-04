package com.muyuchat.mca

import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.nativebridge.NativeQnnBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The local-image runtime cancellation owner (P5/L10). LocalImageProvider.cancel's body moved here
 * verbatim as runLocalImageCancellation(port): the provider keeps begin/nativeConfig/generate/upscale
 * and supplies its live state through LocalImageCancellationPort, whose getters read the same
 * AtomicBoolean/AtomicReference instances (and the same @Volatile activeRuntime) the provider owns.
 * The split-request terminal latch still linearizes QNN cancellation exactly once per branch.
 */

internal interface LocalImageCancellationPort {
    val activeRuntime: LocalImageRuntime?
    val activeSplitSdxlTerminal: AtomicReference<SplitSdxlRequestTerminalArbiter?>
    val cancellationRequested: AtomicBoolean
    val sdxlCoordinator: SdxlTwoPhaseCoordinator
    val qnnBridge: NativeQnnBridge
    val mnnDiffusionBridge: NativeMnnDiffusionBridge
    val qwenImage21Worker: QwenImage21WorkerClient
    val qwenImage21RuntimeResident: AtomicBoolean
    val activeQwenImage21RequestId: AtomicReference<String?>
    val bridge: NativeStableDiffusionBridge
}

internal fun runLocalImageCancellation(port: LocalImageCancellationPort): Boolean {
    val activeRuntime = port.activeRuntime
    val activeSplitSdxlTerminal = port.activeSplitSdxlTerminal
    val cancellationRequested = port.cancellationRequested
    val sdxlCoordinator = port.sdxlCoordinator
    val qnnBridge = port.qnnBridge
    val mnnDiffusionBridge = port.mnnDiffusionBridge
    val qwenImage21Worker = port.qwenImage21Worker
    val qwenImage21RuntimeResident = port.qwenImage21RuntimeResident
    val activeQwenImage21RequestId = port.activeQwenImage21RequestId
    val bridge = port.bridge
    return when (activeRuntime ?: return false) {
        LocalImageRuntime.QNN_HTP -> {
            // The split request state is the linearization point against final output transfer.
            activeSplitSdxlTerminal.get()?.cancel()
            cancellationRequested.set(true)
            activeSplitSdxlTerminal.get()?.cancel()
            runCatching { sdxlCoordinator.cancel() }
            if (NativeQnnBridge.isAvailable) {
                runCatching { qnnBridge.cancelImageGeneration() }
            }
            if (NativeMnnDiffusionBridge.isAvailable) {
                runCatching { mnnDiffusionBridge.cancel() }
            }
            true
        }
        LocalImageRuntime.MNN_DIFFUSION -> {
            cancellationRequested.set(true)
            activeQwenImage21RequestId.get()?.let { requestId ->
                runCatching { qwenImage21Worker.cancelImmediately(requestId) }
                qwenImage21RuntimeResident.set(false)
                return true
            }
            if (NativeMnnDiffusionBridge.isAvailable) {
                runCatching { mnnDiffusionBridge.cancel() }
            }
            true
        }
        LocalImageRuntime.STABLE_DIFFUSION_CPP -> {
            cancellationRequested.set(true)
            if (NativeStableDiffusionBridge.isAvailable) {
                runCatching { bridge.cancel() }
            }
            true
        }
        else -> false
    }
}

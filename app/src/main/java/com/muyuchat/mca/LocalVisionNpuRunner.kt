package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.download.ImageEngineMinDeviceTier
import com.muyuchat.core.download.VisionModelAccelerator
import com.muyuchat.core.download.VisionModelBundleRuntime
import java.io.File
import org.json.JSONObject

internal enum class LocalVisionNpuState {
    NOT_QNN_BUNDLE,
    DEVICE_UNSUPPORTED,
    QNN_RUNTIME_MISSING,
    QNN_TRANSPORT_BLOCKED,
    BUNDLE_MISSING,
    BUNDLE_INCOMPLETE,
    RUNNER_NOT_PACKAGED,
    SMOKE_METADATA_INVALID,
    SMOKE_REQUIRED,
    SMOKE_FAILED,
    NPU_ACTIVE
}

internal data class LocalVisionNpuReport(
    val state: LocalVisionNpuState,
    val backend: String,
    val message: String,
    val npuActive: Boolean = false,
    val smokePassed: Boolean = false,
    val smokeElapsedMs: Long = 0L,
    val graphExecute: Boolean = false,
    val qnnDiagnostics: QnnExecutionDiagnostics = QnnExecutionDiagnostics.Empty
) {
    fun toJson(): JSONObject = JSONObject()
        .put("state", state.name.lowercase())
        .put("backend", backend)
        .put("message", message)
        .put("npuActive", npuActive)
        .put("smokePassed", smokePassed)
        .put("smokeElapsedMs", smokeElapsedMs)
        .put("graphExecute", graphExecute)
        .put("executionStage", qnnDiagnostics.executionStage)
        .put("qnnDiagnostics", qnnDiagnostics.toJson())
}

internal interface LocalVisionNpuRunner {
    val runnerReady: Boolean
    val backendLabel: String
    fun health(device: DeviceProfile, bundleRoot: File?): LocalVisionNpuReport
    fun runSmoke(device: DeviceProfile, bundleRoot: File?): LocalVisionNpuReport
}

internal class LiteRtQnnVisionRunner(
    runnerReady: Boolean? = null,
    private val forcedSmokePassed: Boolean? = null,
    private val forcedSmokeElapsedMs: Long = 0L,
    private val smokeBridge: LocalVisionNpuSmokeBridge = NativeLocalVisionNpuSmokeBridge
) : LocalVisionNpuRunner {
    override val runnerReady: Boolean = runnerReady ?: smokeBridge.runnerReady
    override val backendLabel: String = "骁龙 NPU"

    override fun health(device: DeviceProfile, bundleRoot: File?): LocalVisionNpuReport {
        val manifest = bundleRoot?.let(::localVisionBundleManifestFromRoot)
        return readiness(device, bundleRoot, manifest, smokeRequested = false)
    }

    override fun runSmoke(device: DeviceProfile, bundleRoot: File?): LocalVisionNpuReport {
        val manifest = bundleRoot?.let(::localVisionBundleManifestFromRoot)
        return readiness(device, bundleRoot, manifest, smokeRequested = true)
    }

    private fun readiness(
        device: DeviceProfile,
        bundleRoot: File?,
        manifest: LocalVisionBundleManifest?,
        smokeRequested: Boolean
    ): LocalVisionNpuReport {
        if (bundleRoot == null || manifest == null) {
            return LocalVisionNpuReport(
                state = LocalVisionNpuState.BUNDLE_MISSING,
                backend = backendLabel,
                message = "Local vision NPU requires a complete MCA vision engine bundle manifest."
            )
        }
        if (manifest.accelerator != VisionModelAccelerator.QNN_HTP ||
            manifest.runtime != VisionModelBundleRuntime.LITERT_QNN
        ) {
            return LocalVisionNpuReport(
                state = LocalVisionNpuState.NOT_QNN_BUNDLE,
                backend = manifest.runtime?.label ?: backendLabel,
                message = "This vision bundle is not a LiteRT / QNN NPU bundle."
            )
        }
        if (!manifest.minDeviceTier.supportedBy(device)) {
            return LocalVisionNpuReport(
                state = LocalVisionNpuState.DEVICE_UNSUPPORTED,
                backend = backendLabel,
                message = "当前设备未达到该视觉 NPU 包要求的最低骁龙档位。"
            )
        }
        if (manifest.requiresQnnRuntime && device.accelerationProfile.qnnRuntime.transportDependencyBlocked) {
            return LocalVisionNpuReport(
                state = LocalVisionNpuState.QNN_TRANSPORT_BLOCKED,
                backend = backendLabel,
                message = "The Snapdragon NPU runtime loads, but its device transport is blocked: ${device.accelerationProfile.qnnRuntime.cdspRpcMessage}"
            )
        }
        if (manifest.requiresQnnRuntime && !device.accelerationProfile.qnnRuntime.usableForSmoke) {
            return LocalVisionNpuReport(
                state = LocalVisionNpuState.QNN_RUNTIME_MISSING,
                backend = backendLabel,
                message = "Device supports Snapdragon NPU acceleration, but the complete runtime is not load-verified."
            )
        }
        val missing = manifest.missingRequiredComponents
        if (missing.isNotEmpty()) {
            return LocalVisionNpuReport(
                state = LocalVisionNpuState.BUNDLE_INCOMPLETE,
                backend = backendLabel,
                message = "Vision NPU bundle is incomplete: ${missing.joinToString(", ")}"
            )
        }
        if (!runnerReady) {
            return LocalVisionNpuReport(
                state = LocalVisionNpuState.RUNNER_NOT_PACKAGED,
                backend = backendLabel,
                message = "LiteRT/QNN vision bridge is not packaged yet; NPU vision remains inactive."
            )
        }
        val smokeValidation = manifest.qnnSmokeSpec.validation
        if (!smokeValidation.readyForNativeSmoke) {
            return LocalVisionNpuReport(
                state = LocalVisionNpuState.SMOKE_METADATA_INVALID,
                backend = backendLabel,
                message = "Vision NPU smoke metadata is invalid: ${smokeValidation.blockingReasons.joinToString(" ")}"
            )
        }
        if (manifest.qnnSmokeSpec.contextBinaryFileIn(bundleRoot) == null) {
            return LocalVisionNpuReport(
                state = LocalVisionNpuState.SMOKE_METADATA_INVALID,
                backend = backendLabel,
                message = "Vision NPU smoke contextBinary is missing from bundle: ${manifest.qnnSmokeSpec.contextBinary}"
            )
        }
        if (!smokeRequested) {
            return LocalVisionNpuReport(
                state = LocalVisionNpuState.SMOKE_REQUIRED,
                backend = backendLabel,
                message = "Runtime and bundle are ready, but a real image smoke test is still required."
            )
        }

        forcedSmokePassed?.let { passed ->
            return LocalVisionNpuReport(
                state = if (passed) LocalVisionNpuState.NPU_ACTIVE else LocalVisionNpuState.SMOKE_FAILED,
                backend = backendLabel,
                message = if (passed) {
                    "LiteRT/QNN image smoke test passed; NPU vision can be enabled."
                } else {
                    "Real image smoke test did not pass; keep NPU vision inactive."
                },
                npuActive = passed,
                smokePassed = passed,
                smokeElapsedMs = forcedSmokeElapsedMs,
                graphExecute = passed
            )
        }

        val smoke = smokeBridge.runVisionSmoke(
            bundleRoot = bundleRoot,
            runtimeDirs = qnnRuntimeDirectoriesFor(device.accelerationProfile.qnnRuntime),
            smokeSpec = manifest.qnnSmokeSpec
        )
        val diagnostics = QnnExecutionDiagnostics.from(smoke)
        val compatibilityMessage = qnnContextSocCompatibilityMessage(
            device = device,
            binaryMetadata = diagnostics.binaryMetadata
        )
        val passed = smoke.provesNpuExecution && compatibilityMessage == null
        return LocalVisionNpuReport(
            state = if (passed) LocalVisionNpuState.NPU_ACTIVE else LocalVisionNpuState.SMOKE_FAILED,
            backend = smoke.backend.ifBlank { backendLabel },
            message = compatibilityMessage ?: smoke.message,
            npuActive = passed,
            smokePassed = passed,
            smokeElapsedMs = smoke.elapsedMs,
            graphExecute = smoke.graphExecute,
            qnnDiagnostics = diagnostics
        )
    }
}

private fun ImageEngineMinDeviceTier?.supportedBy(device: DeviceProfile): Boolean {
    val tier = this ?: ImageEngineMinDeviceTier.ANY
    val acceleration = device.accelerationProfile
    return when (tier) {
        ImageEngineMinDeviceTier.ANY -> true
        ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN1 -> acceleration.stableDiffusion15NpuCandidate
        ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN2 -> acceleration.stableDiffusion15NpuCandidate
        ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN3 -> acceleration.sdxlNpuCandidate
        ImageEngineMinDeviceTier.SNAPDRAGON_8_ELITE -> acceleration.sdxlNpuCandidate &&
            acceleration.chipsetCode in setOf("SM8750", "SM8750P", "SM8850", "SM8850P")
    }
}

package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.deviceprofile.DeviceProfile
import java.io.File
import org.json.JSONObject

internal enum class LocalImageQnnState {
    NOT_QNN_BUNDLE,
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

internal data class LocalImageQnnReport(
    val state: LocalImageQnnState,
    val backend: String,
    val message: String,
    val npuActive: Boolean = false,
    val smokePassed: Boolean = false,
    val smokeElapsedMs: Long = 0L,
    val smokeWidth: Int = 0,
    val smokeHeight: Int = 0,
    val smokeSteps: Int = 0,
    val graphExecute: Boolean = false,
    val fallback: Boolean = false,
    val qnnDiagnostics: QnnExecutionDiagnostics = QnnExecutionDiagnostics.Empty
) {
    fun toJson(): JSONObject = JSONObject()
        .put("state", state.name.lowercase())
        .put("backend", backend)
        .put("message", message)
        .put("npuActive", npuActive)
        .put("smokePassed", smokePassed)
        .put("smokeElapsedMs", smokeElapsedMs)
        .put("smokeWidth", smokeWidth)
        .put("smokeHeight", smokeHeight)
        .put("smokeSteps", smokeSteps)
        .put("graphExecute", graphExecute)
        .put("qnnGraphExecution", graphExecute)
        .put("fallback", fallback)
        .put("executionMode", if (graphExecute) "qnn_graph" else "none")
        .put("executionStage", qnnDiagnostics.executionStage)
        .put("qnnDiagnostics", qnnDiagnostics.toJson())
}

internal interface LocalImageQnnRunner {
    val runnerReady: Boolean
    val backendLabel: String
    fun health(device: DeviceProfile, bundleRoot: File?): LocalImageQnnReport
    fun runSmoke(device: DeviceProfile, bundleRoot: File?): LocalImageQnnReport
}

internal class QnnHtpImageRunner(
    runnerReady: Boolean? = null,
    private val forcedSmokePassed: Boolean? = null,
    private val forcedSmokeElapsedMs: Long = 0L,
    private val smokeBridge: LocalImageQnnSmokeBridge = NativeLocalImageQnnSmokeBridge,
    private val context: Context? = null
) : LocalImageQnnRunner {
    override val runnerReady: Boolean = runnerReady ?: smokeBridge.runnerReady
    override val backendLabel: String = "骁龙 NPU"

    override fun health(device: DeviceProfile, bundleRoot: File?): LocalImageQnnReport {
        val manifest = bundleRoot?.let(::localImageBundleManifestFromRoot)
        return readiness(device, bundleRoot, manifest, smokeRequested = false)
    }

    override fun runSmoke(device: DeviceProfile, bundleRoot: File?): LocalImageQnnReport {
        val manifest = bundleRoot?.let(::localImageBundleManifestFromRoot)
        return readiness(device, bundleRoot, manifest, smokeRequested = true)
    }

    private fun readiness(
        device: DeviceProfile,
        bundleRoot: File?,
        manifest: LocalImageBundleManifest?,
        smokeRequested: Boolean
    ): LocalImageQnnReport {
        if (bundleRoot == null || manifest == null) {
            return LocalImageQnnReport(
                state = LocalImageQnnState.BUNDLE_MISSING,
                backend = backendLabel,
                message = "QNN image generation requires a complete MCA image engine bundle manifest."
            )
        }
        if (manifest.runtime != LocalImageRuntime.QNN_HTP) {
            return LocalImageQnnReport(
                state = LocalImageQnnState.NOT_QNN_BUNDLE,
                backend = manifest.runtime?.label ?: backendLabel,
                message = "This image engine is not a Snapdragon NPU bundle."
            )
        }
        // minDeviceTier is a package recommendation, not an admission gate.
        // Unknown/future devices retain the run path and the real native load
        // plus graph smoke below determines whether this package works.
        qnnRequiredBundleRuntimeReadinessMessage(bundleRoot, manifest.requiredRuntimeProfile)?.let { message ->
            return manifest.report(
                state = LocalImageQnnState.QNN_RUNTIME_MISSING,
                backend = backendLabel,
                message = message
            )
        }
        val runtimeResolution = context?.let { appContext ->
            qnnRuntimeDirectoryResolutionFor(appContext, bundleRoot)
        }
        if (runtimeResolution?.stagingError != null) {
            return manifest.report(
                state = LocalImageQnnState.QNN_RUNTIME_MISSING,
                backend = backendLabel,
                message = runtimeResolution.stagingError
            )
        }
        val bundleRuntimeReady = runtimeResolution?.stagedRuntime != null
        if (
            manifest.requiresQnnRuntime &&
            !bundleRuntimeReady &&
            device.accelerationProfile.qnnRuntime.transportDependencyBlocked
        ) {
            return manifest.report(
                state = LocalImageQnnState.QNN_TRANSPORT_BLOCKED,
                backend = backendLabel,
                message = "The Snapdragon NPU runtime loads, but its device transport is blocked: ${device.accelerationProfile.qnnRuntime.cdspRpcMessage}"
            )
        }
        if (
            manifest.requiresQnnRuntime &&
            !bundleRuntimeReady &&
            (!device.accelerationProfile.qnnRuntime.ready ||
                !device.accelerationProfile.qnnRuntime.htpStubLibraryPresent)
        ) {
            return manifest.report(
                state = LocalImageQnnState.QNN_RUNTIME_MISSING,
                backend = backendLabel,
                message = "The complete QNN System, HTP, Skel, and Stub runtime libraries are unavailable."
            )
        }
        val declaredSmokeSpecs = manifest.qnnSmokeSpecs.ifEmpty {
            listOfNotNull(manifest.qnnSmokeSpec.takeIf(QnnSmokeSpec::hasStaticGraphMetadata))
        }
        val usesSemanticGraphDiscovery = declaredSmokeSpecs.none(QnnSmokeSpec::hasStaticGraphMetadata)
        val missing = if (usesSemanticGraphDiscovery) {
            qnnSemanticGraphBundleMissingComponents(
                root = bundleRoot,
                requiresControlNet = manifest.task == "CONTROL_IMAGE"
            )
        } else {
            qnnMissingComponents(bundleRoot, manifest)
        }
        if (missing.isNotEmpty()) {
            return manifest.report(
                state = LocalImageQnnState.BUNDLE_INCOMPLETE,
                backend = backendLabel,
                message = if (usesSemanticGraphDiscovery) {
                    "QNN semantic image bundle is incomplete: ${missing.joinToString(", ")} must exist and be non-empty."
                } else {
                    "QNN image bundle is incomplete: ${missing.joinToString(", ")}."
                }
            )
        }
        if (!runnerReady) {
            return manifest.report(
                state = LocalImageQnnState.RUNNER_NOT_PACKAGED,
                backend = backendLabel,
                message = "QNN image bridge is not packaged yet; NPU image generation remains inactive."
            )
        }
        if (usesSemanticGraphDiscovery) {
            return manifest.report(
                state = LocalImageQnnState.SMOKE_REQUIRED,
                backend = backendLabel,
                message = if (smokeRequested) {
                    "This QNN semantic bundle has no static graph smoke schema; the real text encoder, UNet, and VAE generation must provide native graph proof."
                } else {
                    "Runtime and semantic bundle are ready; the next generation will run the real QNN text encoder, UNet, and VAE graphs."
                }
            )
        }
        val smokeSpecs = declaredSmokeSpecs
        val invalidSmoke = smokeSpecs.firstOrNull { !it.validation.readyForNativeSmoke }
        if (invalidSmoke != null) {
            val smokeValidation = invalidSmoke.validation
            return manifest.report(
                state = LocalImageQnnState.SMOKE_METADATA_INVALID,
                backend = backendLabel,
                message = "QNN image smoke metadata is invalid: ${smokeValidation.blockingReasons.joinToString(" ")}"
            )
        }
        val missingContext = smokeSpecs.firstOrNull { it.contextBinaryFileIn(bundleRoot) == null }
        if (missingContext != null) {
            return manifest.report(
                state = LocalImageQnnState.SMOKE_METADATA_INVALID,
                backend = backendLabel,
                message = "QNN image smoke contextBinary is missing from bundle: ${missingContext.contextBinary}"
            )
        }
        if (!smokeRequested) {
            return manifest.report(
                state = LocalImageQnnState.SMOKE_REQUIRED,
                backend = backendLabel,
                message = "Runtime and bundle are ready, but a real 1-step QNN image smoke test is still required."
            )
        }

        forcedSmokePassed?.let { passed ->
            return manifest.report(
                state = if (passed) LocalImageQnnState.NPU_ACTIVE else LocalImageQnnState.SMOKE_FAILED,
                backend = backendLabel,
                message = if (passed) {
                    "QNN image smoke test passed; this engine can be selected for NPU image generation."
                } else {
                    "Real 1-step QNN image smoke test did not pass; keep this engine inactive."
                },
                npuActive = passed,
                smokePassed = passed,
                smokeElapsedMs = forcedSmokeElapsedMs,
                graphExecute = passed
            )
        }

        var elapsedMs = 0L
        var lastDiagnostics = QnnExecutionDiagnostics.Empty
        var lastBackend = backendLabel
        var graphExecuted = false
        var failureMessage: String? = null
        for (spec in smokeSpecs) {
            val smoke = smokeBridge.runImageSmoke(
                bundleRoot = bundleRoot,
                runtimeDirs = runtimeResolution?.directories
                    ?: qnnImageSmokeRuntimeDirectories(
                        bundleRoot = bundleRoot,
                        runtimeStatus = device.accelerationProfile.qnnRuntime
                    ),
                smokeSpec = spec
            )
            elapsedMs += smoke.elapsedMs
            lastBackend = smoke.backend.ifBlank { backendLabel }
            val diagnostics = QnnExecutionDiagnostics.from(smoke)
            lastDiagnostics = diagnostics
            graphExecuted = graphExecuted || smoke.graphExecute
            val compatibilityMessage = qnnContextSocCompatibilityMessage(
                device = device,
                binaryMetadata = diagnostics.binaryMetadata,
                allowKnownForwardCompatibility = true
            )
            if (!smoke.provesNpuExecution) {
                failureMessage = smoke.message.ifBlank {
                    compatibilityMessage ?: "QNN graph execution did not complete."
                }
                break
            }
        }
        val passed = failureMessage == null
        return manifest.report(
            state = if (passed) LocalImageQnnState.NPU_ACTIVE else LocalImageQnnState.SMOKE_FAILED,
            backend = lastBackend,
            message = failureMessage
                ?: "QNN 生图 smoke 通过；已在骁龙 NPU 执行 ${smokeSpecs.size} 个图。",
            npuActive = passed,
            smokePassed = passed,
            smokeElapsedMs = elapsedMs,
            graphExecute = graphExecuted,
            qnnDiagnostics = lastDiagnostics
        )
    }
}


private fun LocalImageBundleManifest.report(
    state: LocalImageQnnState,
    backend: String,
    message: String,
    npuActive: Boolean = false,
    smokePassed: Boolean = false,
    smokeElapsedMs: Long = 0L,
    graphExecute: Boolean = false,
    qnnDiagnostics: QnnExecutionDiagnostics = QnnExecutionDiagnostics.Empty
): LocalImageQnnReport =
    LocalImageQnnReport(
        state = state,
        backend = backend,
        message = message,
        npuActive = npuActive,
        smokePassed = smokePassed,
        smokeElapsedMs = smokeElapsedMs,
        smokeWidth = smokeWidth,
        smokeHeight = smokeHeight,
        smokeSteps = smokeSteps,
        graphExecute = graphExecute,
        qnnDiagnostics = qnnDiagnostics
    )

private fun qnnMissingComponents(root: File, manifest: LocalImageBundleManifest): List<String> {
    val files = root.walkTopDown().filter { it.isFile }.toList()
    val names = files.map { it.invariantSeparatorsPath.lowercase() }
    fun hasAny(vararg tokens: String): Boolean = names.any { name -> tokens.any { it in name } }
    return buildList {
        if (!hasAny("qnn", "context", "unet", "diffusion", "transformer")) {
            add("QNN diffusion/context")
        }
        if (!hasAny("vae", "decoder", "ae")) add("VAE/AE decoder")
        if (!hasAny("text_encoder", "clip", "t5", "tokenizer", "qwen", "llm")) add("text encoder/tokenizer")
    }
}

/**
 * Static graph-smoke metadata is optional for official semantic QNN bundles.
 * Partially declared graph metadata remains explicit and must pass the strict
 * path and tensor validation; it must never be bypassed by dynamic discovery.
 */
private fun QnnSmokeSpec.hasStaticGraphMetadata(): Boolean =
    graphName.isNotBlank() ||
        contextBinary.isNotBlank() ||
        inputs.isNotEmpty() ||
        outputs.isNotEmpty()

internal fun qnnSemanticGraphBundleMissingComponents(
    root: File,
    requiresControlNet: Boolean = false
): List<String> =
    buildList {
        if (root.nonEmptyQnnContextPath("text_encoder.bin") == null) add("text_encoder.bin")
        if (root.nonEmptyQnnContextPath("unet.bin") == null) add("unet.bin")
        if (root.nonEmptyQnnContextPath("vae.bin", "vae_decoder.bin") == null) {
            add("vae.bin (or vae_decoder.bin)")
        }
        if (requiresControlNet && root.nonEmptyQnnContextPath("controlnet.bin") == null) {
            add("controlnet.bin")
        }
    }

internal fun hasCompleteQnnSemanticGraphBundle(
    root: File,
    requiresControlNet: Boolean = false
): Boolean = qnnSemanticGraphBundleMissingComponents(root, requiresControlNet).isEmpty()

internal fun File.nonEmptyQnnContextPath(vararg names: String): String? =
    qnnFirstContextPath(this, *names)?.takeIf { relativePath ->
        val rootCanonical = runCatching { canonicalFile }.getOrNull() ?: return@takeIf false
        val candidate = runCatching { File(rootCanonical, relativePath).canonicalFile }.getOrNull()
            ?: return@takeIf false
        candidate.isFile &&
            candidate.length() > 0L &&
            candidate.path.startsWith(rootCanonical.path + File.separator)
    }

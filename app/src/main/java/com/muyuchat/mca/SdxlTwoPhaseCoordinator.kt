package com.muyuchat.mca

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal class SdxlTwoPhaseCoordinator(
    context: Context
) {
    internal val appContext = context.applicationContext
    internal val coordinationRoot = File(
        appContext.noBackupFilesDir,
        SDXL_TWO_PHASE_COORDINATION_DIRECTORY
    ).apply { mkdirs() }
    internal val cancelled = AtomicBoolean(false)
    internal val stateLock = Any()
    internal var activeClient: SdxlPhaseClient? = null

    /** Resets cancellation only at the provider's request-admission boundary. */
    fun begin() {
        synchronized(stateLock) {
            check(activeClient == null) { "Cannot begin a new SDXL request while a phase is active." }
            cancelled.set(false)
        }
    }

    fun cancel(): Boolean {
        cancelled.set(true)
        synchronized(stateLock) { activeClient }?.cancelAndTerminate()
        return true
    }


    suspend fun generate(
        lease: SdxlTwoPhaseRequestLease,
        requestId: String,
        bundleRoot: File,
        runtimeDirsJson: String,
        params: JSONObject,
        embeddingsFile: File,
        latentFile: File,
        metadataFile: File,
        preparedInput: SdxlPreparedInputTensor?,
        preparedInpaint: QnnPreparedInpaintInput? = null,
        inputTensorFile: File,
        encoderLatentFile: File,
        encoderMetadataFile: File,
        outputFile: File,
        encoderJournal: File,
        unetJournal: File,
        vaeJournal: File,
        onProgress: (LocalImageProgress) -> Unit
    ): String {
        requireSplitSdxlPreviewDisabled(params, "generation admission")
        val contract = SdxlImageExecutionContract.fromParams(params.toString())
        val ultraFix = contract.ultraFixRequestOrNull()
        val expectedUltraFixTileCount = ultraFix?.let(::expectedSdxlUltraFixTileCount)
        fun phaseParamsJson(): String =
            JSONObject(params.toString()).apply {
                requireSplitSdxlPreviewDisabled(this, "phase admission")
                // Keep the wire defensively clean even if a future mutable params source regresses.
                remove("preview")
            }.toString()
        val taskMode = LocalImageTaskMode.fromWireName(
            contract.paramsObject().getString("taskMode")
        )
        require(
            when (taskMode) {
                LocalImageTaskMode.TEXT_TO_IMAGE -> preparedInput == null && preparedInpaint == null
                LocalImageTaskMode.IMG2IMG -> preparedInput != null && preparedInpaint == null
                LocalImageTaskMode.INPAINT -> preparedInput == null && preparedInpaint != null
                else -> false
            }
        ) { "SDXL encoder admission does not match the resolved product task mode." }
        val encoderInput = preparedInpaint?.source ?: preparedInput
        ultraFix?.let { request ->
            val prepared = requireNotNull(preparedInput) {
                "Split-SDXL UltraFix requires one target-sized prepared RGB tensor."
            }
            require(taskMode == LocalImageTaskMode.IMG2IMG && preparedInpaint == null &&
                prepared.tensorWidth == request.targetWidth &&
                prepared.tensorHeight == request.targetHeight
            ) { "Split-SDXL UltraFix prepared input contract is invalid." }
        }
        preparedInpaint?.let { prepared ->
            require(prepared.topology == QnnInpaintMaskTopology.LATENT_BLEND_4 &&
                prepared.targetWidth == contract.width && prepared.targetHeight == contract.height
            ) { "Split-SDXL inpaint accepts only an exact latent_blend_4 topology." }
        }
        val expectedVaeEncoderContextSha256 = if (encoderInput != null) {
            params.getString("vaeEncoderContextSha256").lowercase().also { sha256 ->
                require(sha256.matches(Regex("^[0-9a-f]{64}$"))) {
                    "Resolved VAE encoder context SHA-256 is missing or malformed."
                }
            }
        } else {
            ""
        }
        encoderInput?.let { prepared ->
            require(File(prepared.tensorPath).canonicalFile == inputTensorFile.canonicalFile &&
                inputTensorFile.isFile && inputTensorFile.length() == prepared.tensorBytes &&
                sdxlArtifactSha256(inputTensorFile) == prepared.tensorSha256
            ) { "SDXL input tensor changed before encoder admission." }
        }
        lease.requireGenerationIdentity(
            contract,
            embeddingsFile,
            latentFile,
            metadataFile,
            inputTensorFile,
            encoderLatentFile,
            encoderMetadataFile,
            outputFile,
            encoderJournal,
            unetJournal,
            vaeJournal,
            *listOfNotNull(
                preparedInpaint?.maskTensorPath?.let(::File),
                preparedInpaint?.fullMaskTensorPath?.let(::File),
            ).toTypedArray()
        )
        require(lease.requestId == requestId) { "Split-SDXL request lease id mismatch." }
        var executionJournal = lease.entry
        fun updateExecutionJournal(
            phase: ImageExecutionPhase = executionJournal.phase,
            step: Int = executionJournal.step,
            nativeStageMask: Long = executionJournal.nativeStageMask,
            nativeGenerationSequence: Long? = executionJournal.nativeGenerationSequence
        ) {
            if (executionJournal.phase.terminal) return
            executionJournal = lease.update(
                executionJournal.copy(
                    phase = phase,
                    step = step.coerceAtLeast(executionJournal.step).coerceAtMost(contract.steps),
                    nativeStageMask = nativeStageMask or executionJournal.nativeStageMask,
                    nativeGenerationSequence = nativeGenerationSequence
                        ?: executionJournal.nativeGenerationSequence,
                    updatedAtMs = System.currentTimeMillis()
                        .coerceAtLeast(executionJournal.updatedAtMs + 1L)
                )
            )
        }
        var mergedStages = emptyList<String>()
        fun report(envelope: SdxlImagePhaseProgress) {
            require(envelope.projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE) {
                "Split-SDXL phase progress cannot carry live-preview evidence."
            }
            envelope.progress.requireSdxlPreviewDisabled("Split-SDXL phase progress")
            mergedStages = SdxlTwoPhaseJournal.merge(mergedStages, envelope)
            updateExecutionJournal(
                phase = when (envelope.phase) {
                    SdxlImagePhase.ENCODER -> ImageExecutionPhase.PREPARING
                    SdxlImagePhase.UNET -> ImageExecutionPhase.SAMPLING
                    SdxlImagePhase.VAE -> ImageExecutionPhase.DECODING
                },
                step = if (envelope.phase == SdxlImagePhase.ENCODER) {
                    0
                } else {
                    envelope.progress.step
                }
            )
            onProgress(
                envelope.progress.copy(
                    phase = "sdxl_${envelope.phase.wireName}:${envelope.progress.phase}",
                    message = "${envelope.phase.wireName.uppercase()} ${envelope.runtimeProfile} " +
                        "pid=${envelope.workerPid}: ${envelope.progress.message}",
                    stageTrace = mergedStages
                )
            )
        }
        QnnImageStageJournal.cleanupSdxlProjectionPreview(unetJournal)
        cleanupHandoff(
            latentFile,
            metadataFile,
            encoderLatentFile,
            encoderMetadataFile,
            outputFile,
            encoderJournal,
            unetJournal,
            vaeJournal
        )
        try {
            val phaseRuntimeDirs = stageBothRuntimeProfiles(
                bundleRoot = bundleRoot,
                packagedRuntimeDirsJson = runtimeDirsJson
            )
            check(!cancelled.get()) { "SDXL generation was cancelled." }
            var encoder: SdxlPhaseCompletion? = null
            var encoderNative: JSONObject? = null
            var encoderMetadata: SdxlEncoderLatentMetadata? = null
            var encoderTransportHtpArch: Int? = null
            var encoderRuntimeProfile: SdxlQnnRuntimeProfile? = null
            if (encoderInput != null) {
                val encoderAttempt = runPhaseWithRuntimeCandidates(
                    phase = SdxlImagePhase.ENCODER,
                    candidates = phaseRuntimeDirs.encoderCandidates,
                    request = SdxlImagePhaseRequest(
                        requestId = requestId,
                        phase = SdxlImagePhase.ENCODER,
                        runtimeProfile = phaseRuntimeDirs.encoderCandidates.first().runtimeProfile,
                        sourceArtifactProducerHtpArch = 0,
                        profileId = contract.expected.profileId,
                        profileRevision = contract.expected.profileRevision,
                        modelFingerprint = contract.expected.modelFingerprint,
                        steps = contract.steps,
                        width = contract.width,
                        height = contract.height,
                        bundleRoot = bundleRoot.canonicalPath,
                        paramsJson = phaseParamsJson(),
                        embeddingsPath = "",
                        latentPath = encoderLatentFile.canonicalPath,
                        metadataPath = encoderMetadataFile.canonicalPath,
                        outputPath = "",
                        journalPath = encoderJournal.canonicalPath,
                        conditioningArtifactSha256 = contract.conditioningArtifactSha256,
                        expectedVaeEncoderContextSha256 = expectedVaeEncoderContextSha256,
                        inputTensorPath = inputTensorFile.canonicalPath
                    ),
                    timeoutMs = sdxlEncoderPhaseTimeoutMs(
                        encoderExecutionCount = expectedUltraFixTileCount ?: 1
                    ),
                    onProgress = ::report
                )
                val completedEncoder = encoderAttempt.completion
                encoderRuntimeProfile = encoderAttempt.candidate.runtimeProfile
                encoder = completedEncoder
                check(completedEncoder.processDeathConfirmed) {
                    "VAE encoder process did not exit before UNet admission."
                }
                val completedEncoderNative = JSONObject(completedEncoder.result.nativeResultJson)
                encoderNative = completedEncoderNative
                val completedEncoderTransport = validateSdxlNativeTransport(
                    phase = SdxlImagePhase.ENCODER,
                    expectedRuntimeProfile = encoderAttempt.candidate.runtimeProfile,
                    nativeResult = completedEncoderNative
                )
                encoderTransportHtpArch = completedEncoderTransport
                validateSdxlEncoderNativeEvidence(contract, completedEncoderNative)
                val committedEncoderMetadata = SdxlEncoderLatentArtifact.validate(
                    requestId = requestId,
                    latentFile = encoderLatentFile,
                    metadataFile = encoderMetadataFile,
                    expectedProducerArch = completedEncoderTransport,
                    contract = contract
                )
                encoderMetadata = committedEncoderMetadata
                require(committedEncoderMetadata.encoderNativeGenerationSequence ==
                    completedEncoder.result.nativeGenerationSequence
                ) { "Encoder result sequence does not match committed latent metadata." }
                mergedStages = SdxlTwoPhaseJournal.appendBoundary(
                    mergedStages,
                    SdxlImagePhase.ENCODER,
                    completedEncoder.result.workerPid,
                    completedEncoder.result.runtimeProfile,
                    "process_exit_confirmed"
                )
                params.put("encoderLatentSha256", committedEncoderMetadata.sha256)
                    .put("encoderNativeGenerationSequence", completedEncoder.result.nativeGenerationSequence)
                    .put("encoderNativeStageMask", completedEncoder.result.nativeStageMask)
                    .put(
                        "encoderNativeDetailStageMaskHex",
                        completedEncoder.result.nativeDetailStageMask.toFixedUInt64Hex()
                    )
                ultraFix?.let {
                    val tilePlanSha256 = completedEncoderNative
                        .getString("ultraFixTilePlanSha256").lowercase()
                    val tileCount = completedEncoderNative.getInt("ultraFixTileCount")
                    require(tilePlanSha256.matches(Regex("^[a-f0-9]{64}$")) &&
                        tileCount == expectedUltraFixTileCount
                    ) {
                        "Split-SDXL UltraFix encoder did not publish a valid tile-plan handoff."
                    }
                    listOf(
                        "ultraFixEncoderGraphExecutionCount",
                        "ultraFixEncoderTileSuccessCount",
                        "ultraFixSourceWidth",
                        "ultraFixSourceHeight",
                        "ultraFixSourceResizedWidth",
                        "ultraFixSourceResizedHeight",
                        "ultraFixSourceCropLeft",
                        "ultraFixSourceCropTop"
                    ).forEach { field -> params.put(field, completedEncoderNative.get(field)) }
                    listOf(
                        "ultraFixEncoderInputProofSha256",
                        "ultraFixEncoderMeanProofSha256",
                        "ultraFixEncoderStdProofSha256"
                    ).forEach { field ->
                        val digest = completedEncoderNative.getString(field).lowercase()
                        require(digest.matches(Regex("^[a-f0-9]{64}$"))) {
                            "Split-SDXL UltraFix encoder $field is malformed."
                        }
                        params.put(field, digest)
                    }
                    params.put("ultraFixTilePlanSha256", tilePlanSha256)
                        .put("ultraFixTileCount", tileCount)
                }
                updateExecutionJournal(
                    phase = ImageExecutionPhase.SAMPLING,
                    step = 0,
                    nativeStageMask = completedEncoder.result.nativeStageMask
                )
                check(!cancelled.get()) { "SDXL generation was cancelled." }
            }
            val unetTimeoutExecutionCount = ultraFix?.let { request ->
                val tileCount = params.getInt("ultraFixTileCount")
                Math.multiplyExact(
                    Math.multiplyExact(tileCount, request.inversionSteps),
                    if (contract.expected.useCfg) 3 else 2
                )
            } ?: contract.expectedUnetExecutionCount
            val unetAttempt = runPhaseWithRuntimeCandidates(
                phase = SdxlImagePhase.UNET,
                candidates = phaseRuntimeDirs.unetCandidates,
                request = SdxlImagePhaseRequest(
                    requestId = requestId,
                    phase = SdxlImagePhase.UNET,
                    runtimeProfile = phaseRuntimeDirs.unetCandidates.first().runtimeProfile,
                    sourceArtifactProducerHtpArch = encoderTransportHtpArch ?: 0,
                    profileId = contract.expected.profileId,
                    profileRevision = contract.expected.profileRevision,
                    modelFingerprint = contract.expected.modelFingerprint,
                    steps = contract.steps,
                    width = contract.width,
                    height = contract.height,
                    bundleRoot = bundleRoot.canonicalPath,
                    paramsJson = phaseParamsJson(),
                    embeddingsPath = embeddingsFile.canonicalPath,
                    latentPath = latentFile.canonicalPath,
                    metadataPath = metadataFile.canonicalPath,
                    outputPath = "",
                    journalPath = unetJournal.canonicalPath,
                    conditioningArtifactSha256 = contract.conditioningArtifactSha256,
                    maskTensorPath = preparedInpaint?.maskTensorPath.orEmpty(),
                    sourceLatentPath = if (encoderInput == null) "" else encoderLatentFile.canonicalPath,
                    sourceMetadataPath = if (encoderInput == null) "" else encoderMetadataFile.canonicalPath
                ),
                timeoutMs = sdxlUnetPhaseTimeoutMs(unetTimeoutExecutionCount),
                onProgress = ::report
            )
            val unet = unetAttempt.completion
            check(unet.processDeathConfirmed) { "UNet phase process did not exit before VAE admission." }
            val unetNative = JSONObject(unet.result.nativeResultJson)
            require(unet.result.projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE) {
                "Split-SDXL UNet result cannot carry live-preview evidence."
            }
            val unetTransportHtpArch = validateSdxlNativeTransport(
                phase = SdxlImagePhase.UNET,
                expectedRuntimeProfile = unetAttempt.candidate.runtimeProfile,
                nativeResult = unetNative
            )
            val metadata = SdxlLatentArtifact.validate(
                requestId = requestId,
                latentFile = latentFile,
                metadataFile = metadataFile,
                expectedProducerArch = unetTransportHtpArch,
                contract = contract
            )
            ultraFix?.let {
                require(unetNative.getString("ultraFixTilePlanSha256").lowercase() ==
                    params.getString("ultraFixTilePlanSha256").lowercase() &&
                    unetNative.getInt("ultraFixTileCount") == params.getInt("ultraFixTileCount")
                ) { "Split-SDXL UltraFix UNet changed the encoder tile-plan identity." }
                listOf(
                    "ultraFixInversionStepCount",
                    "ultraFixInversionGraphExecutionCount",
                    "ultraFixInversionTileSuccessCount",
                    "ultraFixRefinementStepCount",
                    "ultraFixRefinementPositiveGraphExecutionCount",
                    "ultraFixRefinementNegativeGraphExecutionCount",
                    "ultraFixRefinementTileSuccessCount",
                    "ultraFixPhysicalUnetGraphExecutionCount",
                    "ultraFixQualityStepEvaluationCount",
                    "ultraFixNoiseInjectionStepCount",
                    "ultraFixStructureGuidanceStepCount"
                ).forEach { field -> params.put(field, unetNative.get(field)) }
                listOf(
                    "ultraFixNoiseInjectionSeedFingerprint",
                    "ultraFixNoiseInjectionChecksum",
                    "ultraFixStructureGuidanceChecksum",
                    "ultraFixTrajectoryNoiseChecksum"
                ).forEach { field ->
                    val value = unetNative.getString(field).lowercase()
                    require(
                        (field.endsWith("SeedFingerprint") && value.matches(Regex("^[a-f0-9]{64}$"))) ||
                            (!field.endsWith("SeedFingerprint") && value.matches(Regex("^[a-f0-9]{16}$")))
                    ) { "Split-SDXL UltraFix $field is malformed." }
                    params.put(field, value)
                }
                params.put("ultraFixSampleMethod", unetNative.getString("ultraFixSampleMethod"))
                    .put("ultraFixNativeScheduler", unetNative.getString("ultraFixNativeScheduler"))
            }
            require(metadata.unetNativeGenerationSequence == unet.result.nativeGenerationSequence) {
                "UNet result sequence does not match committed latent metadata."
            }
            require(metadata.unetNativeStageMask == unet.result.nativeStageMask) {
                "UNet stage mask does not match committed latent metadata."
            }
            mergedStages = SdxlTwoPhaseJournal.appendBoundary(
                mergedStages,
                SdxlImagePhase.UNET,
                unet.result.workerPid,
                unet.result.runtimeProfile,
                "process_exit_confirmed"
            )
            updateExecutionJournal(
                phase = ImageExecutionPhase.DECODING,
                step = contract.steps,
                nativeStageMask = unet.result.nativeStageMask,
                nativeGenerationSequence = unet.result.nativeGenerationSequence
            )
            check(!cancelled.get()) { "SDXL generation was cancelled." }
            val vaeAttempt = runPhaseWithRuntimeCandidates(
                phase = SdxlImagePhase.VAE,
                candidates = phaseRuntimeDirs.vaeCandidates,
                request = SdxlImagePhaseRequest(
                    requestId = requestId,
                    phase = SdxlImagePhase.VAE,
                    runtimeProfile = phaseRuntimeDirs.vaeCandidates.first().runtimeProfile,
                    sourceArtifactProducerHtpArch = unetTransportHtpArch,
                    profileId = contract.expected.profileId,
                    profileRevision = contract.expected.profileRevision,
                    modelFingerprint = contract.expected.modelFingerprint,
                    steps = contract.steps,
                    width = contract.width,
                    height = contract.height,
                    bundleRoot = bundleRoot.canonicalPath,
                    paramsJson = phaseParamsJson(),
                    embeddingsPath = "",
                    latentPath = latentFile.canonicalPath,
                    metadataPath = metadataFile.canonicalPath,
                    outputPath = outputFile.canonicalPath,
                    journalPath = vaeJournal.canonicalPath,
                    conditioningArtifactSha256 = contract.conditioningArtifactSha256,
                    inputTensorPath = preparedInpaint?.source?.tensorPath.orEmpty(),
                    fullMaskTensorPath = preparedInpaint?.fullMaskTensorPath.orEmpty()
                ),
                timeoutMs = sdxlVaePhaseTimeoutMs(
                    vaeExecutionCount = ultraFix?.let {
                        params.getInt("ultraFixTileCount")
                    } ?: SDXL_DEFAULT_VAE_EXECUTION_COUNT
                ),
                onProgress = ::report
            )
            val vae = vaeAttempt.completion
            check(vae.processDeathConfirmed) { "VAE phase process did not exit after PNG publication." }
            val vaeNative = JSONObject(vae.result.nativeResultJson)
            val vaeTransportHtpArch = validateSdxlNativeTransport(
                phase = SdxlImagePhase.VAE,
                expectedRuntimeProfile = vaeAttempt.candidate.runtimeProfile,
                nativeResult = vaeNative
            )
            validateSdxlVaeNativeEvidence(contract, vaeNative)
            check(outputFile.isFile && outputFile.length() > 0L) { "VAE phase output is missing." }
            mergedStages = SdxlTwoPhaseJournal.appendBoundary(
                mergedStages,
                SdxlImagePhase.VAE,
                vae.result.workerPid,
                vae.result.runtimeProfile,
                "process_exit_confirmed"
            )
            updateExecutionJournal(
                phase = ImageExecutionPhase.PUBLISHING,
                step = contract.steps,
                nativeStageMask = unet.result.nativeStageMask or vae.result.nativeStageMask
            )
            onProgress(
                LocalImageProgress(
                    phase = "sdxl_two_phase_finalizing",
                    message = buildString {
                        append("SDXL isolated phases finished; validating merged output and evidence: ")
                        encoderTransportHtpArch?.let { append("encoder=V$it, ") }
                        append("UNet=V$unetTransportHtpArch, VAE=V$vaeTransportHtpArch.")
                    },
                    step = contract.steps,
                    steps = contract.steps,
                    elapsedMs = 0L,
                    secondsPerStep = 0.0,
                    threads = 0,
                    width = contract.width,
                    height = contract.height,
                    cancelRequested = false,
                    stageTrace = mergedStages
                )
            )
            val finalResult = mergeSdxlPhaseNativeResults(
                contract = contract,
                encoderResult = encoder?.result,
                encoderNative = encoderNative,
                encoderMetadata = encoderMetadata,
                unetResult = unet.result,
                unetNative = unetNative,
                vaeResult = vae.result,
                vaeNative = vaeNative,
                metadata = metadata,
                encoderRuntimeProfile = encoderRuntimeProfile,
                unetRuntimeProfile = unetAttempt.candidate.runtimeProfile,
                vaeRuntimeProfile = vaeAttempt.candidate.runtimeProfile,
                unetTransportHtpArch = unetTransportHtpArch,
                vaeTransportHtpArch = vaeTransportHtpArch,
                outputFile = outputFile,
                stageTrace = mergedStages,
                preparedInpaint = preparedInpaint
            )
            // PUBLISHING remains non-terminal until LocalImageProvider has copied the PNG and
            // removed every request-scoped artifact. The durable lease must span that ownership
            // handoff so a provider crash after this return can still recover the output.
            return finalResult.toString()
        } finally {
            // The provider-owned lease performs all artifact cleanup only after it acquires the
            // request lock exclusively. Coroutine cancellation must not delete paths while an
            // isolated phase process can still be inside a synchronous native call.
        }
    }


}

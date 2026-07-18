package com.muyuchat.mca

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal class SdxlTwoPhaseCoordinator(
    context: Context
) {
    private val appContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)
    private val stateLock = Any()
    private var activeClient: SdxlPhaseClient? = null

    fun cancel(): Boolean {
        cancelled.set(true)
        synchronized(stateLock) { activeClient }?.cancelAndTerminate()
        return true
    }

    suspend fun generate(
        requestId: String,
        bundleRoot: File,
        runtimeDirsJson: String,
        params: JSONObject,
        embeddingsFile: File,
        latentFile: File,
        metadataFile: File,
        outputFile: File,
        unetJournal: File,
        vaeJournal: File,
        onProgress: (LocalImageProgress) -> Unit
    ): String {
        cancelled.set(false)
        val contract = SdxlImageExecutionContract.fromParams(params.toString())
        val executionJournalStore = ImageExecutionJournalStore(
            File(metadataFile.parentFile, "request-journal")
        )
        val createdAtMs = System.currentTimeMillis().coerceAtLeast(1L)
        var executionJournal = executionJournalStore.create(
            ImageExecutionJournalEntry(
                requestId = requestId,
                modelFingerprint = contract.expected.modelFingerprint,
                profileFingerprint = "${contract.expected.profileId}:${contract.expected.profileRevision}:${contract.expected.modelFingerprint}",
                requestedSummaryJson = contract.expectedJson().toString(),
                resolvedSummaryJson = contract.expectedJson().toString(),
                phase = ImageExecutionPhase.PREPARING,
                steps = contract.steps,
                workerPid = Process.myPid(),
                createdAtMs = createdAtMs,
                latentTempPath = latentFile.canonicalPath,
                outputTempPath = outputFile.canonicalPath
            )
        )
        fun updateExecutionJournal(
            phase: ImageExecutionPhase = executionJournal.phase,
            step: Int = executionJournal.step,
            nativeStageMask: Long = executionJournal.nativeStageMask,
            nativeGenerationSequence: Long? = executionJournal.nativeGenerationSequence
        ) {
            if (executionJournal.phase.terminal) return
            executionJournal = executionJournalStore.update(
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
        updateExecutionJournal(phase = ImageExecutionPhase.SAMPLING)
        var mergedStages = emptyList<String>()
        fun report(envelope: SdxlImagePhaseProgress) {
            mergedStages = SdxlTwoPhaseJournal.merge(mergedStages, envelope)
            updateExecutionJournal(
                phase = if (envelope.phase == SdxlImagePhase.UNET) {
                    ImageExecutionPhase.SAMPLING
                } else {
                    ImageExecutionPhase.DECODING
                },
                step = envelope.progress.step
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
        cleanupHandoff(latentFile, metadataFile, outputFile, unetJournal, vaeJournal)
        try {
            val phaseRuntimeDirs = stageBothRuntimeProfiles(
                bundleRoot = bundleRoot,
                packagedRuntimeDirsJson = runtimeDirsJson
            )
            check(!cancelled.get()) { "SDXL generation was cancelled." }
            val unet = runPhase(
                phase = SdxlImagePhase.UNET,
                request = SdxlImagePhaseRequest(
                    requestId = requestId,
                    phase = SdxlImagePhase.UNET,
                    expectedHtpArch = SDXL_AUTO_TRANSPORT_HTP_ARCH,
                    profileId = contract.expected.profileId,
                    profileRevision = contract.expected.profileRevision,
                    modelFingerprint = contract.expected.modelFingerprint,
                    steps = contract.steps,
                    width = contract.width,
                    height = contract.height,
                    bundleRoot = bundleRoot.canonicalPath,
                    runtimeDirsJson = phaseRuntimeDirs.unetDirsJson,
                    paramsJson = params.toString(),
                    embeddingsPath = embeddingsFile.canonicalPath,
                    latentPath = latentFile.canonicalPath,
                    metadataPath = metadataFile.canonicalPath,
                    outputPath = "",
                    journalPath = unetJournal.canonicalPath,
                    conditioningArtifactSha256 = contract.conditioningArtifactSha256
                ),
                timeoutMs = sdxlUnetPhaseTimeoutMs(contract.expectedUnetExecutionCount),
                onProgress = ::report
            )
            check(unet.processDeathConfirmed) { "UNet phase process did not exit before VAE admission." }
            val unetNative = JSONObject(unet.result.nativeResultJson)
            val transportHtpArch = validateSdxlNativeTransport(
                phase = SdxlImagePhase.UNET,
                expectedHtpArch = SDXL_AUTO_TRANSPORT_HTP_ARCH,
                nativeResult = unetNative
            )
            val metadata = SdxlLatentArtifact.validate(
                requestId = requestId,
                latentFile = latentFile,
                metadataFile = metadataFile,
                expectedProducerArch = transportHtpArch,
                contract = contract
            )
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
            val vae = runPhase(
                phase = SdxlImagePhase.VAE,
                request = SdxlImagePhaseRequest(
                    requestId = requestId,
                    phase = SdxlImagePhase.VAE,
                    expectedHtpArch = transportHtpArch,
                    profileId = contract.expected.profileId,
                    profileRevision = contract.expected.profileRevision,
                    modelFingerprint = contract.expected.modelFingerprint,
                    steps = contract.steps,
                    width = contract.width,
                    height = contract.height,
                    bundleRoot = bundleRoot.canonicalPath,
                    runtimeDirsJson = phaseRuntimeDirs.vaeDirsJson,
                    paramsJson = params.toString(),
                    embeddingsPath = "",
                    latentPath = latentFile.canonicalPath,
                    metadataPath = metadataFile.canonicalPath,
                    outputPath = outputFile.canonicalPath,
                    journalPath = vaeJournal.canonicalPath,
                    conditioningArtifactSha256 = contract.conditioningArtifactSha256
                ),
                timeoutMs = sdxlVaePhaseTimeoutMs(
                    vaeExecutionCount = SDXL_DEFAULT_VAE_EXECUTION_COUNT
                ),
                onProgress = ::report
            )
            check(vae.processDeathConfirmed) { "VAE phase process did not exit after PNG publication." }
            val vaeNative = JSONObject(vae.result.nativeResultJson)
            val vaeTransportHtpArch = validateSdxlNativeTransport(
                phase = SdxlImagePhase.VAE,
                expectedHtpArch = transportHtpArch,
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
                    phase = "sdxl_two_phase_completed",
                    message = "SDXL UNet and VAE completed on HTP V$transportHtpArch in separate exited processes.",
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
                unetResult = unet.result,
                unetNative = unetNative,
                vaeResult = vae.result,
                vaeNative = vaeNative,
                metadata = metadata,
                transportHtpArch = transportHtpArch,
                vaeTransportHtpArch = vaeTransportHtpArch,
                outputFile = outputFile,
                stageTrace = mergedStages
            )
            executionJournal = executionJournalStore.markTerminal(
                requestId = requestId,
                phase = ImageExecutionPhase.COMPLETED
            )
            return finalResult.toString()
        } catch (error: Throwable) {
            if (!executionJournal.phase.terminal) {
                if (cancelled.get()) {
                    runCatching {
                        executionJournal = executionJournalStore.finishCancelled(
                            requestId = requestId,
                            cleanupRoots = listOf(requireNotNull(metadataFile.parentFile))
                        ).entry
                    }
                } else {
                    runCatching {
                        executionJournal = executionJournalStore.markTerminal(
                            requestId = requestId,
                            phase = ImageExecutionPhase.FAILED,
                            errorCode = "SDXL_TWO_PHASE_FAILED",
                            errorMessage = error.message.orEmpty()
                        )
                    }
                }
            }
            throw error
        } finally {
            runCatching { latentFile.delete() }
            runCatching { File(latentFile.path + ".part").delete() }
            runCatching { metadataFile.delete() }
            runCatching { File(metadataFile.path + ".part").delete() }
            runCatching { unetJournal.delete() }
            runCatching { File(unetJournal.path + ".tmp").delete() }
            runCatching { vaeJournal.delete() }
            runCatching { File(vaeJournal.path + ".tmp").delete() }
        }
    }

    private suspend fun runPhase(
        phase: SdxlImagePhase,
        request: SdxlImagePhaseRequest,
        timeoutMs: Long,
        onProgress: (SdxlImagePhaseProgress) -> Unit
    ): SdxlPhaseCompletion {
        val client = SdxlPhaseClient(appContext, phase, onProgress)
        synchronized(stateLock) { activeClient = client }
        return try {
            check(!cancelled.get()) { "SDXL generation was cancelled." }
            client.execute(request, timeoutMs)
        } finally {
            synchronized(stateLock) { if (activeClient === client) activeClient = null }
            client.close()
        }
    }

    private fun cleanupHandoff(vararg files: File) {
        files.forEach { file ->
            runCatching { file.delete() }
            runCatching { File(file.path + ".part").delete() }
            file.parentFile?.mkdirs()
        }
    }

    private fun stageBothRuntimeProfiles(
        bundleRoot: File,
        packagedRuntimeDirsJson: String
    ): SdxlPhaseRuntimeDirectories {
        val bundleContextProfile = qnnImageBundleRuntimeProfileForArchOrNull(
            bundleRoot,
            SDXL_ARCHIVE_CONTEXT_HTP_ARCH
        )
        val isolatedRuntimeDirs = if (bundleContextProfile == null) {
            // Public SDXL archives contain graph contexts and prompt encoders,
            // but no host/Skel/Stub libraries. Use the APK's coherent runtime
            // selected for this device. Chipset detection only orders the
            // packaged transport; the real context load and graph execute in
            // each disposable worker remain the compatibility decision.
            isolatedSdxlPackagedRuntimeDirs(packagedRuntimeDirsJson)
        } else {
            val stager = QnnImageRuntimeStager(
                File(appContext.codeCacheDir, "qnn-image-runtime-sdxl-phases")
            )
            val device = DeviceProfileReader(appContext).read()
            val transportArch = DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(
                device.accelerationProfile.chipsetCode
            ) ?: device.accelerationProfile.qnnRuntime.htpArchVersion.takeIf { it > 0 }
            val selectedTransport = transportArch
                ?.let { arch -> qnnImageBundleRuntimeProfileForArchOrNull(bundleRoot, arch) }
                ?: bundleContextProfile
            val result = if (selectedTransport.htpArchVersion == bundleContextProfile.htpArchVersion) {
                stager.stage(bundleContextProfile)
            } else {
                stager.stage(
                    QnnImageRuntimeStagePlan(
                        contextProfile = bundleContextProfile,
                        transportProfile = selectedTransport
                    )
                )
            }
            require(!result.failed && result.runtime != null) {
                result.error ?: "Unable to stage the SDXL QNN runtime for real execution."
            }
            orderedSdxlRuntimeDirs(
                requireNotNull(result.runtime).directory.canonicalPath,
                emptyList()
            )
        }
        // Each disposable process receives only its complete content-addressed
        // runtime directory. Native code adds platform ADSP paths for DSP
        // discovery, but cannot mix a second host profile into either process.
        return SdxlPhaseRuntimeDirectories(
            unetDirsJson = isolatedRuntimeDirs,
            vaeDirsJson = isolatedRuntimeDirs
        )
    }
}

internal fun mergeSdxlPhaseNativeResults(
    contract: SdxlImageExecutionContract,
    unetResult: SdxlImagePhaseResult,
    unetNative: JSONObject,
    vaeResult: SdxlImagePhaseResult,
    vaeNative: JSONObject,
    metadata: SdxlLatentMetadata,
    transportHtpArch: Int,
    vaeTransportHtpArch: Int,
    outputFile: File,
    stageTrace: List<String>
): JSONObject {
    require(unetResult.phase == SdxlImagePhase.UNET) { "Expected UNet phase result." }
    require(vaeResult.phase == SdxlImagePhase.VAE) { "Expected VAE phase result." }
    require(unetResult.requestId == vaeResult.requestId) { "SDXL phase request identity mismatch." }
    require(
        unetResult.conditioningArtifactSha256 == contract.conditioningArtifactSha256 &&
            vaeResult.conditioningArtifactSha256 == contract.conditioningArtifactSha256
    ) { "SDXL phase conditioning artifact identity mismatch." }
    require(unetResult.workerPid > 0 && vaeResult.workerPid > 0) {
        "SDXL phase worker PID proof is missing."
    }
    require(unetNative.optBoolean("ok") && vaeNative.optBoolean("ok")) {
        "SDXL phase native success proof is missing."
    }
    require(transportHtpArch > 0 && vaeTransportHtpArch == transportHtpArch) {
        "SDXL phase transport mismatch."
    }
    require(unetNative.getInt("htpArchVersion") == transportHtpArch) { "UNet transport proof mismatch." }
    require(vaeNative.getInt("htpArchVersion") == vaeTransportHtpArch) { "VAE transport proof mismatch." }
    validateSdxlUnetNativeEvidence(contract, unetNative)
    val unetEffective = contract.requireNativeEffective(unetNative, SdxlImagePhase.UNET)
    val metadataEffective = contract.requireNativeEffective(
        JSONObject().put("nativeEffective", JSONObject(metadata.nativeEffectiveJson)),
        SdxlImagePhase.UNET
    )
    require(metadataEffective == unetEffective) {
        "Committed latent native execution evidence differs from the UNet result."
    }
    validateSdxlVaeNativeEvidence(contract, vaeNative)
    require(metadata.unetNativeGenerationSequence == unetResult.nativeGenerationSequence) {
        "Committed latent sequence mismatch."
    }
    require(File(unetResult.artifactPath).canonicalFile == File(metadata.latentPath).canonicalFile) {
        "UNet artifact path does not match committed latent metadata."
    }
    require(outputFile.isFile && outputFile.length() > 0L) { "SDXL output is missing." }
    require(File(vaeResult.artifactPath).canonicalFile == outputFile.canonicalFile) {
        "VAE protocol artifact path mismatch."
    }

    val unetProof = SdxlNativePhaseProof.fromNativeResult(unetNative, SdxlImagePhase.UNET)
    val vaeProof = SdxlNativePhaseProof.fromNativeResult(vaeNative, SdxlImagePhase.VAE)
    require(unetProof.nativeGenerationSequence == unetResult.nativeGenerationSequence) {
        "UNet protocol sequence mismatch."
    }
    require(unetProof.nativeStageMask == unetResult.nativeStageMask &&
        unetProof.nativeDetailStageMask == unetResult.nativeDetailStageMask
    ) { "UNet protocol stage proof mismatch." }
    require(vaeProof.nativeGenerationSequence == vaeResult.nativeGenerationSequence) {
        "VAE protocol sequence mismatch."
    }
    require(vaeProof.nativeStageMask == vaeResult.nativeStageMask &&
        vaeProof.nativeDetailStageMask == vaeResult.nativeDetailStageMask
    ) { "VAE protocol stage proof mismatch." }
    val nativeOutput = File(vaeNative.getString("outputPath")).canonicalFile
    require(nativeOutput == outputFile.canonicalFile) { "VAE output path proof mismatch." }
    require(vaeNative.getLong("outputBytes") == outputFile.length()) { "VAE output byte proof mismatch." }
    val outputSha256 = vaeNative.getString("outputSha256").lowercase()
    require(outputSha256 == sdxlArtifactSha256(outputFile)) { "VAE output SHA-256 proof mismatch." }
    val mimeType = vaeNative.getString("mimeType")
    require(mimeType == "image/png") { "VAE output MIME proof mismatch." }

    val unetNativeEffectiveJson = unetNative.optJSONObject("nativeEffective")
        ?: error("UNet native result is missing nativeEffective evidence.")
    val actualConditioningArtifactSha256 = unetNativeEffectiveJson
        .getString("conditioningArtifactSha256")
        .lowercase()
    require(actualConditioningArtifactSha256 == metadata.conditioningArtifactSha256) {
        "Committed latent conditioning evidence differs from the UNet result."
    }
    val metadataNativeEffectiveJson = JSONObject(metadata.nativeEffectiveJson)
    require(
        metadataNativeEffectiveJson.getString("conditioningArtifactSha256").lowercase() ==
            actualConditioningArtifactSha256
    ) { "Committed latent nativeEffective conditioning evidence was changed." }

    val sdxlPhaseProof = JSONObject()
        .put("conditioningArtifactSha256", actualConditioningArtifactSha256)
        .put("unetContextLoadCount", unetNative.getInt("unetContextLoadCount"))
        .put("unetSamplingLoopCount", unetNative.getInt("unetSamplingLoopCount"))
        .put("unetSamplingStepCount", unetNative.getInt("unetSamplingStepCount"))
        .put("unetGraphExecutionCount", unetNative.getInt("unetGraphExecutionCount"))
        .put(
            "unetContextReusedAcrossSteps",
            unetNative.getBoolean("unetContextReusedAcrossSteps")
        )
        .put("unetGraphName", unetNative.getString("unetGraphName"))
        .put("vaeContextLoadCount", vaeNative.getInt("vaeContextLoadCount"))
        .put("vaeExecutionCount", vaeNative.getInt("vaeExecutionCount"))
        .put("vaeTileCount", vaeNative.getInt("vaeTileCount"))
        .put("vaeTiled", vaeNative.getBoolean("vaeTiled"))
        .put("vaeGraphName", vaeNative.getString("vaeGraphName"))
        .put("vaeSourceLatentShape", vaeNative.getJSONArray("vaeSourceLatentShape"))
        .put("vaeInputLatentShape", vaeNative.getJSONArray("vaeInputLatentShape"))
        .put("vaeOutputTileShape", vaeNative.getJSONArray("vaeOutputTileShape"))
        .put("vaeFinalOutputShape", vaeNative.getJSONArray("vaeFinalOutputShape"))
        .put("vaeDecodeSpatialScale", vaeNative.getInt("vaeDecodeSpatialScale"))
        .put("vaeScalingLocation", vaeNative.getString("vaeScalingLocation"))
        .put("vaeScalingFactor", vaeNative.getDouble("vaeScalingFactor"))
        .put("effectiveVaeHostScale", vaeNative.getDouble("effectiveVaeHostScale"))
        .put("pixelRange", vaeNative.getString("pixelRange"))
        .put("pixelRangeConversion", vaeNative.getString("pixelRangeConversion"))
        .put("pixelRangeValueCount", vaeNative.getLong("pixelRangeValueCount"))
        .put("pixelRangeClampedValueCount", vaeNative.getLong("pixelRangeClampedValueCount"))
        .put("pixelRangeObservedMin", vaeNative.getDouble("pixelRangeObservedMin"))
        .put("pixelRangeObservedMax", vaeNative.getDouble("pixelRangeObservedMax"))
        .put("outputSha256", outputSha256)
    val nativeEffectiveJson = JSONObject(unetNativeEffectiveJson.toString())
        .put("vaeScalingLocation", vaeNative.getString("vaeScalingLocation"))
        .put("vaeScalingFactor", vaeNative.getDouble("vaeScalingFactor"))
        .put("pixelRange", vaeNative.getString("pixelRange"))
        .put("conditioningArtifactSha256", actualConditioningArtifactSha256)
        .put("sdxlPhaseProof", sdxlPhaseProof)
    val finalResult = JSONObject(nativeEffectiveJson.toString())
    finalResult.put("nativeEffective", nativeEffectiveJson)
        .put("ok", true)
        .put("backend", "qnn_htp")
        .put("npuActive", true)
        .put("qnnGraphExecution", true)
        .put("nativeExecution", true)
        .put("executionStage", "sdxl_two_phase_passed")
        .put("runtimeSessionMode", "isolated_unet_then_vae_same_transport")
        .put("archiveContextHtpArch", SDXL_ARCHIVE_CONTEXT_HTP_ARCH)
        .put("transportHtpArch", transportHtpArch)
        .put("unetWorkerPid", unetResult.workerPid)
        .put("unetRuntimeProfile", unetResult.runtimeProfile)
        .put("unetGraph", unetNative.getString("unetGraphName"))
        .put("unetProcessDeathConfirmed", true)
        .put("unetNativeGenerationSequence", unetProof.nativeGenerationSequence)
        .put("unetNativeStageMask", unetProof.nativeStageMask)
        .put("unetNativeDetailStageMask", unetProof.nativeDetailStageMask)
        .put("vaeWorkerPid", vaeResult.workerPid)
        .put("vaeRuntimeProfile", vaeResult.runtimeProfile)
        .put("vaeGraph", vaeNative.getString("vaeGraphName"))
        .put("vaeTransportHtpArch", vaeTransportHtpArch)
        .put("vaeProcessDeathConfirmed", true)
        .put("vaeNativeGenerationSequence", vaeProof.nativeGenerationSequence)
        .put("vaeNativeStageMask", vaeProof.nativeStageMask)
        .put("vaeNativeDetailStageMask", vaeProof.nativeDetailStageMask)
        .put("nativeGenerationSequence", unetProof.nativeGenerationSequence)
        .put("nativeStageMask", unetProof.nativeStageMask or vaeProof.nativeStageMask)
        .put(
            "nativeDetailStageMask",
            unetProof.nativeDetailStageMask or vaeProof.nativeDetailStageMask
        )
        .put("vaeScalingLocation", vaeNative.getString("vaeScalingLocation"))
        .put("vaeScalingFactor", vaeNative.getDouble("vaeScalingFactor"))
        .put("pixelRange", vaeNative.getString("pixelRange"))
        .put("effectiveVaeHostScale", vaeNative.getDouble("effectiveVaeHostScale"))
        .put("vaeExecutionCount", vaeNative.getInt("vaeExecutionCount"))
        .put("outputPath", outputFile.canonicalPath)
        .put("mimeType", mimeType)
        .put("outputBytes", outputFile.length())
        .put("outputSha256", outputSha256)
        .put("latentSha256", metadata.sha256)
        .put("stageTrace", JSONArray(stageTrace))

    listOf(
        "timesteps",
        "sigmas",
        "initNoiseSigma",
        "scaleModelInput",
        "unetContextLoadCount",
        "unetSamplingLoopCount",
        "unetSamplingStepCount",
        "unetGraphExecutionCount",
        "unetContextReusedAcrossSteps",
        "unetContextLoadMs",
        "unetExecuteMsTotal"
    ).forEach { field ->
        if (unetNative.has(field) && !unetNative.isNull(field)) {
            finalResult.put(field, unetNative.get(field))
        }
    }
    listOf(
        "vaeContextLoadMs",
        "vaeExecuteMs",
        "vaeTileCount",
        "vaeTiled",
        "vaeContextLoadCount",
        "vaeSourceLatentShape",
        "vaeInputLatentShape",
        "vaeOutputTileShape",
        "vaeFinalOutputShape",
        "vaeDecodeSpatialScale",
        "pixelChecksum",
        "pixelRangeConversion",
        "pixelRangeValueCount",
        "pixelRangeClampedValueCount",
        "pixelRangeObservedMin",
        "pixelRangeObservedMax"
    ).forEach { field ->
        if (vaeNative.has(field) && !vaeNative.isNull(field)) {
            finalResult.put(field, vaeNative.get(field))
        }
    }
    (vaeNative.optJSONObject("runtimeEvidence")
        ?: vaeNative.optJSONObject("runtime"))?.let { finalResult.put("runtimeEvidence", it) }
    validateSdxlFlatNativeEffective(finalResult)
    return finalResult
}

internal data class SdxlPhaseRuntimeDirectories(
    val unetDirsJson: String,
    val vaeDirsJson: String
)

@Suppress("UNUSED_PARAMETER")
internal fun orderedSdxlRuntimeDirs(primary: String, fallback: List<String>): String =
    JSONArray(listOf(File(primary).canonicalPath)).toString()

/**
 * Selects one complete APK/app-private QNN runtime for an isolated SDXL phase.
 * The directory may contain several physical-device transports; native context
 * metadata selects the compatible one and a real graph execute decides support.
 */
internal fun isolatedSdxlPackagedRuntimeDirs(runtimeDirsJson: String): String {
    val raw = JSONArray(runtimeDirsJson)
    val directories = buildList {
        for (index in 0 until raw.length()) {
            raw.optString(index)
                .takeIf(String::isNotBlank)
                ?.let(::File)
                ?.let { runCatching { it.canonicalFile }.getOrNull() }
                ?.takeIf(File::isDirectory)
                ?.let(::add)
        }
    }.distinctBy(File::getPath)
    val coherent = directories.firstOrNull(File::hasCoherentSdxlQnnRuntime)
        ?: error("The APK does not contain a complete QNN runtime for SDXL graph execution.")
    return orderedSdxlRuntimeDirs(coherent.path, emptyList())
}

private fun File.hasCoherentSdxlQnnRuntime(): Boolean {
    if (!File(this, "libQnnSystem.so").isFile || !File(this, "libQnnHtp.so").isFile) return false
    return listFiles().orEmpty().any { skel ->
        val arch = SDXL_HTP_SKEL.matchEntire(skel.name)?.groupValues?.getOrNull(1) ?: return@any false
        File(this, "libQnnHtpV${arch}Stub.so").isFile
    }
}

private val SDXL_HTP_SKEL = Regex("^libQnnHtpV(\\d+)Skel\\.so$")

internal data class SdxlPhaseCompletion(
    val result: SdxlImagePhaseResult,
    val processDeathConfirmed: Boolean
)

internal object SdxlTwoPhaseJournal {
    fun merge(
        previous: List<String>,
        envelope: SdxlImagePhaseProgress
    ): List<String> {
        var merged = previous
        val prefix = "${envelope.phase.wireName}[pid=${envelope.workerPid},profile=${envelope.runtimeProfile}]"
        if (merged.none { it == "$prefix:worker_started" }) {
            merged = merged + "$prefix:worker_started"
        }
        envelope.progress.stageTrace.forEach { stage ->
            val tagged = "$prefix:$stage"
            if (tagged !in merged) merged = merged + tagged
        }
        return merged
    }

    fun appendBoundary(
        previous: List<String>,
        phase: SdxlImagePhase,
        pid: Int,
        profile: String,
        boundary: String
    ): List<String> {
        val tagged = "${phase.wireName}[pid=$pid,profile=$profile]:$boundary"
        return if (tagged in previous) previous else previous + tagged
    }
}

private class SdxlPhaseClient(
    private val context: Context,
    private val phase: SdxlImagePhase,
    private val onProgress: (SdxlImagePhaseProgress) -> Unit
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val serviceReady = CompletableDeferred<ISdxlImagePhaseWorker>()
    private val result = CompletableDeferred<SdxlImagePhaseResult>()
    private val processDeath = CompletableDeferred<Unit>()
    private var service: ISdxlImagePhaseWorker? = null
    private var binder: IBinder? = null

    @Volatile
    private var workerPid: Int = -1
    private var bound = false
    private var bindingRequested = false
    private var closed = false

    @Volatile
    private var requestId: String = ""

    private val deathRecipient = IBinder.DeathRecipient {
        processDeath.complete(Unit)
        if (!result.isCompleted) {
            result.completeExceptionally(
                LocalImageWorkerDisconnectedException("SDXL ${phase.wireName} phase process died before result publication.")
            )
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, connectedBinder: IBinder) {
            val connectedService = ISdxlImagePhaseWorker.Stub.asInterface(connectedBinder)
            val linkError = runCatching { connectedBinder.linkToDeath(deathRecipient, 0) }
                .exceptionOrNull()
            val accepted = synchronized(lifecycleLock) {
                if (closed || (!bound && !bindingRequested) || linkError != null) {
                    false
                } else {
                    binder = connectedBinder
                    service = connectedService
                    true
                }
            }
            if (!accepted) {
                runCatching { connectedBinder.unlinkToDeath(deathRecipient, 0) }
                if (linkError != null) serviceReady.completeExceptionally(linkError)
                runCatching { context.unbindService(this) }
                return
            }
            if (!serviceReady.complete(connectedService)) {
                runCatching { connectedBinder.unlinkToDeath(deathRecipient, 0) }
                releaseBindingForExit()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            failConnection("SDXL ${phase.wireName} phase service disconnected.")
        }

        override fun onBindingDied(name: ComponentName) {
            failConnection("SDXL ${phase.wireName} phase binding died.")
        }

        override fun onNullBinding(name: ComponentName) {
            failConnection("SDXL ${phase.wireName} phase returned a null binding.")
        }
    }

    private val callback = object : ISdxlImagePhaseWorkerCallback.Stub() {
        override fun onProgress(payloadJson: String) {
            if (isClosed()) return
            val envelope = runCatching { SdxlImagePhaseProtocol.parseProgress(payloadJson) }
                .getOrElse {
                    result.completeExceptionally(it)
                    return
                }
            if (envelope.requestId != requestId || envelope.phase != phase) {
                result.completeExceptionally(IllegalStateException("SDXL phase progress identity mismatch."))
                return
            }
            if (envelope.workerPid > 0) workerPid = envelope.workerPid
            onProgress(envelope)
        }

        override fun onComplete(payloadJson: String) {
            if (isClosed()) return
            val envelope = runCatching { SdxlImagePhaseProtocol.parseResult(payloadJson) }
                .getOrElse {
                    result.completeExceptionally(it)
                    return
                }
            if (envelope.requestId != requestId || envelope.phase != phase) {
                result.completeExceptionally(IllegalStateException("SDXL phase result identity mismatch."))
                return
            }
            if (envelope.workerPid > 0) workerPid = envelope.workerPid
            result.complete(envelope)
        }

        override fun onError(payloadJson: String) {
            if (isClosed()) return
            val envelope = runCatching { SdxlImagePhaseProtocol.parseError(payloadJson) }
                .getOrElse {
                    result.completeExceptionally(it)
                    return
                }
            if (envelope.requestId != requestId || envelope.phase != phase) {
                result.completeExceptionally(IllegalStateException("SDXL phase error identity mismatch."))
                return
            }
            if (envelope.workerPid > 0) workerPid = envelope.workerPid
            result.completeExceptionally(LocalImageWorkerRemoteException(envelope.code, envelope.message))
        }
    }

    suspend fun execute(request: SdxlImagePhaseRequest, timeoutMs: Long): SdxlPhaseCompletion {
        requestId = request.requestId
        val serviceClass = when (phase) {
            SdxlImagePhase.UNET -> SdxlUnetWorkerService::class.java
            SdxlImagePhase.VAE -> SdxlVaeWorkerService::class.java
        }
        synchronized(lifecycleLock) {
            check(!closed) { "SDXL ${phase.wireName} phase client is closed." }
            check(!bindingRequested && !bound) { "SDXL ${phase.wireName} phase bind was already requested." }
            bindingRequested = true
        }
        val didBind = runCatching {
            context.bindService(Intent(context, serviceClass), connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            synchronized(lifecycleLock) { bindingRequested = false }
            throw error
        }
        val closedDuringBind = synchronized(lifecycleLock) {
            bindingRequested = false
            if (!closed) bound = didBind
            closed
        }
        if (closedDuringBind && didBind) {
            runCatching { context.unbindService(connection) }
        }
        check(!closedDuringBind && didBind) { "Unable to bind SDXL ${phase.wireName} phase worker." }
        try {
            return withTimeout(timeoutMs) {
                val remote = serviceReady.await()
                check(remote.execute(SdxlImagePhaseProtocol.request(request), callback)) {
                    "SDXL ${phase.wireName} phase rejected the request."
                }
                val completed = result.await()
                require(completed.workerPid > 0) { "SDXL phase did not report a worker PID." }
                // The child deliberately exits instead of unloading QNN.  The
                // binding is released first so Android does not restart the
                // disposable service, then the parent confirms /proc death.
                releaseBindingForExit()
                withTimeout(SDXL_PHASE_EXIT_CONFIRM_TIMEOUT_MS) {
                    while (File("/proc/${completed.workerPid}").exists()) delay(25L)
                }
                processDeath.complete(Unit)
                SdxlPhaseCompletion(completed, true)
            }
        } catch (timeout: TimeoutCancellationException) {
            cancelAndTerminate()
            throw LocalImageWorkerRemoteException(
                code = "qnn_sdxl_${phase.wireName}_worker_timeout",
                message = "SDXL ${phase.wireName} phase exceeded ${timeoutMs / 1000L}s and its isolated worker was terminated."
            )
        }
    }

    fun cancelAndTerminate() {
        val (currentService, currentPid) = synchronized(lifecycleLock) { service to workerPid }
        runCatching { currentService?.cancel(requestId) }
        if (currentPid > 0 && currentPid != Process.myPid()) {
            runCatching { Process.killProcess(currentPid) }
        }
    }

    private fun releaseBindingForExit() {
        val shouldUnbind = synchronized(lifecycleLock) {
            if (!bound) false else {
                bound = false
                true
            }
        }
        if (shouldUnbind) runCatching { context.unbindService(connection) }
    }

    private fun failConnection(message: String) {
        val failure = LocalImageWorkerDisconnectedException(message)
        processDeath.complete(Unit)
        if (isClosed()) return
        serviceReady.completeExceptionally(failure)
        result.completeExceptionally(failure)
    }

    private fun isClosed(): Boolean = synchronized(lifecycleLock) { closed }

    private fun closeLifecycle(): Pair<IBinder?, Boolean> = synchronized(lifecycleLock) {
        if (closed) return@synchronized null to false
        closed = true
        val currentBinder = binder
        binder = null
        service = null
        val shouldUnbind = bound
        bound = false
        currentBinder to shouldUnbind
    }

    override fun close() {
        val (currentBinder, shouldUnbind) = closeLifecycle()
        currentBinder?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
        if (shouldUnbind) runCatching { context.unbindService(connection) }
    }
}

private const val SDXL_PHASE_EXIT_CONFIRM_TIMEOUT_MS = 5L * 1_000L

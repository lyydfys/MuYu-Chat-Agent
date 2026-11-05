package com.muyuchat.mca

import android.os.Process
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

internal fun LocalImageWorkerService.prepareExecutionJournal(
        request: LocalImageWorkerProtocol.GenerateRequest,
        effectiveOptions: LocalImageGenerationOptions,
        inputDirectory: File
    ) {
        replaceTerminalExecutionJournal(request.requestId)
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        if (request.model.runtime == LocalImageRuntime.MEDIATEK_NEURON) {
            // The Neuron pipeline has no SD execution profile. Its journal provenance is the
            // bundle's pinned v3/v4 component manifest and the declared generation profile.
            val contract = request.model.mediaTekImageProductContractOrNull()
                ?: error("MediaTek image bundle manifest is unavailable to the worker journal.")
            val profile = contract.profileFor(
                effectiveOptions.width,
                effectiveOptions.height,
                effectiveOptions.steps
            ) ?: contract.defaultProfile
            val steps = effectiveOptions.steps ?: profile.defaultSteps
            executionJournal.create(
                ImageExecutionJournalEntry(
                    requestId = request.requestId,
                    modelId = request.model.id,
                    modelName = request.model.displayName,
                    recommendationId = contract.recommendationId.orEmpty(),
                    recommendationRevision = contract.recommendationRevision.orEmpty(),
                    modelFingerprint = request.model.sha256
                        .trim()
                        .lowercase()
                        .takeIf(JOURNAL_SHA256::matches)
                        ?: contract.manifestSha256,
                    profileFingerprint = contract.manifestSha256,
                    requestedSummaryJson = effectiveOptions.toJson()
                        .apply {
                            remove("inputImage")
                            remove("maskImage")
                            remove("controlImage")
                            put("productInput", effectiveOptions.inputAuditJson())
                            request.batchLineage?.let { put("batchLineage", it.toJson()) }
                        }
                        .put("runtime", request.model.runtime.name)
                        .put("family", request.model.family.name)
                        .toString(),
                    resolvedSummaryJson = JSONObject()
                        .put("runtime", LocalImageRuntime.MEDIATEK_NEURON.name)
                        .put("backend", LocalImageRuntime.MEDIATEK_NEURON.name)
                        .put("profileSchema", contract.schema)
                        .put("width", effectiveOptions.width ?: profile.width)
                        .put("height", effectiveOptions.height ?: profile.height)
                        .put("steps", steps)
                        .put("sampler", MEDIA_TEK_FIXED_SAMPLER)
                        .put(
                            "cfgScale",
                            effectiveOptions.cfgScale ?: profile.defaultCfgScale.toDouble()
                        )
                        .put("productInput", effectiveOptions.inputAuditJson())
                        .toString(),
                    phase = ImageExecutionPhase.PREPARING,
                    step = 0,
                    steps = steps,
                    workerPid = Process.myPid(),
                    createdAtMs = now,
                    inputTempPaths = listOfNotNull(
                        effectiveOptions.inputImage?.path,
                        effectiveOptions.maskImage?.path,
                        effectiveOptions.controlImage?.path,
                        inputDirectory.canonicalPath
                    )
                )
            )
            return
        }
        val resolution = resolveLocalImageExecutionProfile(
            model = request.model,
            options = effectiveOptions,
            bundleRoot = request.model.workerBundleRoot()
        )
        executionJournal.create(
            ImageExecutionJournalEntry(
                requestId = request.requestId,
                modelId = request.model.id,
                modelName = request.model.displayName,
                recommendationId = resolution.profile.provenance.recommendationId.orEmpty(),
                recommendationRevision = resolution.profile.provenance.recommendationRevision.orEmpty(),
                modelFingerprint = resolution.profile.modelFingerprint,
                profileFingerprint = resolution.profile.bindingFingerprint,
                requestedSummaryJson = effectiveOptions.toJson()
                    .apply {
                        remove("inputImage")
                        remove("maskImage")
                        remove("controlImage")
                        put("productInput", effectiveOptions.inputAuditJson())
                        request.batchLineage?.let { put("batchLineage", it.toJson()) }
                    }
                    .put("runtime", request.model.runtime.name)
                    .put("family", request.model.family.name)
                    .toString(),
                resolvedSummaryJson = ImageExecutionProfileNativeContract
                    .toNativeParamsJson(resolution)
                    .put("productInput", effectiveOptions.inputAuditJson())
                    .toString(),
                phase = ImageExecutionPhase.PREPARING,
                step = 0,
                steps = resolution.layers.resolved.steps,
                workerPid = Process.myPid(),
                createdAtMs = now,
                inputTempPaths = listOfNotNull(
                    effectiveOptions.inputImage?.path,
                    effectiveOptions.maskImage?.path,
                    effectiveOptions.controlImage?.path,
                    inputDirectory.canonicalPath
                )
            )
        )
    }

private fun LocalImageWorkerService.replaceTerminalExecutionJournal(requestId: String) {
    executionJournal.read(requestId)?.let { existing ->
        require(existing.phase.terminal) {
            "A non-terminal image request already uses id $requestId."
        }
        check(executionJournal.deleteTerminal(requestId)) {
            "Unable to replace terminal image journal $requestId."
        }
    }
}

internal fun LocalImageWorkerService.prepareUpscaleExecutionJournal(
        request: LocalImageWorkerProtocol.UpscaleRequest,
        inputs: LocalImageWorkerUpscaleInputs
    ) {
        replaceTerminalExecutionJournal(request.requestId)
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        val requested = JSONObject()
            .put("operation", "ESRGAN_UPSCALE")
            .put("targetScale", request.targetScale)
            .put("tileSize", request.tileSize)
            .put("threads", request.threads)
            .put("input", inputs.input.toJson(includePath = false))
            .put("upscaler", inputs.upscaler.toJson(includePath = false))
        executionJournal.create(
            ImageExecutionJournalEntry(
                requestId = request.requestId,
                modelId = inputs.upscaler.id,
                modelName = inputs.upscaler.name,
                modelFingerprint = inputs.upscaler.sha256,
                profileFingerprint = "stable-diffusion-cpp-esrgan-upscale-v1",
                requestedSummaryJson = requested.toString(),
                resolvedSummaryJson = JSONObject(requested.toString())
                    .put("runtime", LocalImageRuntime.STABLE_DIFFUSION_CPP.name)
                    .put("backendMode", "cpu")
                    .toString(),
                phase = ImageExecutionPhase.PREPARING,
                step = 0,
                steps = 1,
                workerPid = Process.myPid(),
                createdAtMs = now,
                inputTempPaths = listOf(inputs.input.path, inputs.directory.canonicalPath)
            )
        )
    }

internal fun LocalImageWorkerService.updateJournalFromProgress(requestId: String, progress: LocalImageProgress) {
        val targetPhase = progress.phase.toJournalPhase() ?: return
        runCatching {
            advanceJournalTo(
                requestId = requestId,
                targetPhase = targetPhase,
                observedStep = progress.step
            )
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("persist_progress", error)
        }
    }

internal fun LocalImageWorkerService.advanceJournalTo(
        requestId: String,
        targetPhase: ImageExecutionPhase,
        observedStep: Int? = null
    ) {
        var current = executionJournal.read(requestId) ?: return
        if (current.phase.terminal) return
        val targetRank = targetPhase.executionRank()
        while (current.phase.executionRank() < targetRank) {
            val nextPhase = when (current.phase) {
                ImageExecutionPhase.PREPARING -> if (targetPhase == ImageExecutionPhase.CONDITIONING) {
                    ImageExecutionPhase.CONDITIONING
                } else {
                    ImageExecutionPhase.SAMPLING
                }
                ImageExecutionPhase.CONDITIONING -> ImageExecutionPhase.SAMPLING
                ImageExecutionPhase.SAMPLING -> ImageExecutionPhase.DECODING
                ImageExecutionPhase.DECODING -> ImageExecutionPhase.PUBLISHING
                else -> break
            }
            current = executionJournal.update(
                current.copy(
                    phase = nextPhase,
                    step = current.nextObservedStep(observedStep),
                    updatedAtMs = System.currentTimeMillis().coerceAtLeast(current.updatedAtMs + 1L)
                )
            )
        }
        if (current.phase == targetPhase || current.phase.executionRank() > targetRank) {
            val nextStep = current.nextObservedStep(observedStep)
            if (nextStep != current.step) {
                executionJournal.update(
                    current.copy(
                        step = nextStep,
                        updatedAtMs = System.currentTimeMillis().coerceAtLeast(current.updatedAtMs + 1L)
                    )
                )
            }
        }
    }

internal fun LocalImageWorkerService.updateJournalFromNativeResult(requestId: String, raw: String) {
        if (raw.isBlank()) return
        runCatching {
            val metadata = JSONObject(raw)
            val current = executionJournal.read(requestId) ?: return@runCatching
            if (current.phase.terminal) return@runCatching
            val sequence = if (metadata.has("nativeGenerationSequence") &&
                !metadata.isNull("nativeGenerationSequence")
            ) {
                metadata.getLong("nativeGenerationSequence")
            } else {
                current.nativeGenerationSequence
            }
            val stageMask = metadata.optLong("nativeStageMask", current.nativeStageMask)
            executionJournal.update(
                current.copy(
                    nativeStageMask = current.nativeStageMask or stageMask,
                    nativeGenerationSequence = sequence,
                    updatedAtMs = System.currentTimeMillis().coerceAtLeast(current.updatedAtMs + 1L)
                )
            )
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("persist_native_evidence", error)
        }
    }

internal fun LocalImageWorkerService.updateJournalOutputPath(requestId: String, output: File) {
        val current = executionJournal.read(requestId)
            ?: error("Image execution journal disappeared before result publication.")
        check(!current.phase.terminal) {
            "Image execution journal became terminal before result publication."
        }
        executionJournal.update(
            current.copy(
                outputTempPath = output.canonicalPath,
                outputTempPaths = (current.outputTempPaths + output.canonicalPath).distinct(),
                updatedAtMs = System.currentTimeMillis().coerceAtLeast(current.updatedAtMs + 1L)
            )
        )
    }

internal fun LocalImageWorkerService.updateJournalOutputArtifact(
        requestId: String,
        output: File,
        index: Int,
        mimeType: String,
        seed: Long?
    ) {
        val canonical = output.canonicalFile
        require(canonical.isFile && canonical.length() > 0L) {
            "Published image output is missing before provenance hashing."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        canonical.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val artifact = ImageExecutionOutputArtifact(
            index = index,
            mimeType = mimeType,
            bytes = canonical.length(),
            sha256 = digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            },
            seed = seed
        )
        val current = executionJournal.read(requestId)
            ?: error("Image execution journal disappeared before output provenance publication.")
        check(!current.phase.terminal) {
            "Image execution journal became terminal before output provenance publication."
        }
        require(index == current.outputArtifacts.size) {
            "Published image output provenance arrived out of order."
        }
        executionJournal.update(
            current.copy(
                outputArtifacts = current.outputArtifacts + artifact,
                updatedAtMs = System.currentTimeMillis().coerceAtLeast(current.updatedAtMs + 1L)
            )
        )
    }

internal fun LocalImageWorkerService.requestJournalCancellation(requestId: String) {
        runCatching {
            val current = executionJournal.read(requestId) ?: return@runCatching
            if (!current.phase.terminal && !current.cancellationRequested) {
                executionJournal.requestCancellation(requestId)
            }
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("persist_cancellation", error)
        }
    }

internal fun LocalImageWorkerService.finishJournalCancelled(requestId: String, message: String) {
        runCatching {
            val current = executionJournal.read(requestId) ?: return@runCatching
            if (current.phase.terminal) return@runCatching
            executionJournal.finishCancelled(
                requestId = requestId,
                cleanupRoots = listOf(cacheDir),
                message = message
            )
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("finish_cancelled_journal", error)
        }
    }

internal fun LocalImageWorkerService.markJournalTerminal(
        requestId: String,
        phase: ImageExecutionPhase,
        errorCode: String = "",
        errorMessage: String = ""
    ) {
        runCatching {
            var current = executionJournal.read(requestId) ?: return@runCatching
            if (current.phase.terminal) return@runCatching
            if (phase == ImageExecutionPhase.COMPLETED && current.steps > 0 && current.step < current.steps) {
                current = executionJournal.update(
                    current.copy(
                        step = current.steps,
                        updatedAtMs = System.currentTimeMillis().coerceAtLeast(current.updatedAtMs + 1L)
                    )
                )
            }
            executionJournal.markTerminal(requestId, phase, errorCode, errorMessage)
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("finish_terminal_journal", error)
        }
    }

    private fun LocalImageModelRecord.workerBundleRoot(): File? =
        bundleRoot?.let(::File)?.takeIf(File::isDirectory)
            ?: File(path).takeIf(File::isDirectory)
            ?: File(path).parentFile?.takeIf(File::isDirectory)

    private fun String.toJournalPhase(): ImageExecutionPhase? {
        val normalized = trim().lowercase()
        return when {
            normalized.isBlank() -> null
            "condition" in normalized || "text_encoder" in normalized || "tokeniz" in normalized ->
                ImageExecutionPhase.CONDITIONING
            "sampl" in normalized || "unet" in normalized || "denois" in normalized ||
                "upscal" in normalized ->
                ImageExecutionPhase.SAMPLING
            "decod" in normalized || "vae" in normalized -> ImageExecutionPhase.DECODING
            "publish" in normalized || "writing" in normalized || normalized == "completed" ->
                ImageExecutionPhase.PUBLISHING
            else -> ImageExecutionPhase.PREPARING
        }
    }

    private fun ImageExecutionPhase.executionRank(): Int = when (this) {
        ImageExecutionPhase.PREPARING -> 0
        ImageExecutionPhase.CONDITIONING -> 1
        ImageExecutionPhase.SAMPLING -> 2
        ImageExecutionPhase.DECODING -> 3
        ImageExecutionPhase.PUBLISHING -> 4
        ImageExecutionPhase.COMPLETED,
        ImageExecutionPhase.FAILED,
        ImageExecutionPhase.CANCELLED,
        ImageExecutionPhase.INTERRUPTED -> 5
    }

    private fun ImageExecutionJournalEntry.nextObservedStep(observedStep: Int?): Int {
        if (observedStep == null) return step
        val upper = if (steps > 0) steps else observedStep.coerceAtLeast(0)
        return maxOf(step, observedStep.coerceIn(0, upper))
    }

private val JOURNAL_SHA256 = Regex("^[0-9a-f]{64}$")

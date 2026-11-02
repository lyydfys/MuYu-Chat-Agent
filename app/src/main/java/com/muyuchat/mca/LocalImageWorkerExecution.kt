package com.muyuchat.mca

import android.os.Process
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal suspend fun LocalImageWorkerService.runGenerateJob(
    scope: CoroutineScope,
    active: LocalImageWorkerService.ActiveGeneration,
    request: LocalImageWorkerProtocol.GenerateRequest,
    effectiveOptions: LocalImageGenerationOptions,
    callback: ILocalImageWorkerCallback
) {
    val coroutineContext = scope.coroutineContext
                val transferredOutputs = mutableListOf<File>()
                var workerInputs: LocalImageWorkerInputs? = null
                try {
                    workerInputs = workerInputStore.materialize(request.requestId, effectiveOptions)
                    val workerOptions = workerInputs.options
                    prepareExecutionJournal(request, workerOptions, workerInputs.directory)
                    val startedDelivered = sendProgress(
                        callback,
                        request.requestId,
                        LocalImageProgress(
                            phase = "worker_started",
                            message = "Local image worker started.",
                            step = 0,
                            steps = 0,
                            elapsedMs = 0L,
                            secondsPerStep = 0.0,
                            threads = 0,
                            width = 0,
                            height = 0,
                            cancelRequested = false,
                            requestOptionsJson = workerOptions.inputAuditJson().toString()
                        )
                    )
                    if (!startedDelivered) {
                        cancelForDeadClient(active)
                    }
                    var waitingReported = false
                    val result = NativeMediaExecutionGate.withLease(
                        context = applicationContext,
                        isCancelled = { active.cancelRequested },
                        onWaiting = {
                            if (!waitingReported) {
                                waitingReported = true
                                val delivered = sendProgress(
                                    callback,
                                    request.requestId,
                                    LocalImageProgress(
                                        phase = "waiting_for_native_lease",
                                        message = "Waiting for another native image task to finish.",
                                        step = 0,
                                        steps = 0,
                                        elapsedMs = 0L,
                                        secondsPerStep = 0.0,
                                        threads = 0,
                                        width = 0,
                                        height = 0,
                                        cancelRequested = false
                                    )
                                )
                                if (!delivered) cancelForDeadClient(active)
                            }
                        }
                    ) {
                        provider.generate(
                            model = request.model,
                            prompt = request.prompt,
                            options = workerOptions,
                            onProgress = { progress ->
                                updateJournalFromProgress(request.requestId, progress)
                                val delivered = sendProgress(
                                    callback,
                                    request.requestId,
                                    active.withAccumulatedStages(progress)
                                )
                                if (!delivered) cancelForDeadClient(active)
                            }
                        )
                    }
                    coroutineContext.ensureActive()
                    advanceJournalTo(request.requestId, ImageExecutionPhase.PUBLISHING)
                    updateJournalFromNativeResult(request.requestId, result.executionMetadataJson)
                    val publishedOutputs = result.outputs.map { generated ->
                        val target = prepareResultTarget(
                            requestId = request.requestId,
                            mimeType = generated.mimeType,
                            index = generated.index
                        )
                        updateJournalOutputPath(request.requestId, target.partial)
                        val output = writeResultFile(target, generated.bytes)
                        transferredOutputs += output
                        updateJournalOutputPath(request.requestId, output)
                        updateJournalOutputArtifact(
                            requestId = request.requestId,
                            output = output,
                            index = generated.index,
                            mimeType = generated.mimeType,
                            seed = generated.seed
                        )
                        generated to output
                    }
                    val outputBytes = transferredOutputs.sumOf(File::length)
                    if (!active.tryBeginResultPublication()) {
                        throw CancellationException("Image generation was cancelled before result publication.")
                    }
                    val delivered = sendComplete(
                        callback = callback,
                        requestId = request.requestId,
                        outputs = publishedOutputs.map { (generated, output) ->
                            LocalImageWorkerProtocol.OutputEnvelope(
                                index = generated.index,
                                outputPath = output.canonicalPath,
                                mimeType = generated.mimeType,
                                seed = generated.seed
                            )
                        },
                        executionMetadataJson = result.executionMetadataJson
                    )
                    try {
                        if (delivered) {
                            markJournalTerminal(request.requestId, ImageExecutionPhase.COMPLETED)
                            Log.i(
                                "MCA-LocalImage",
                                localImageWorkerCompletionLogSummary(
                                    requestId = request.requestId,
                                    workerPid = Process.myPid(),
                                    operation = "IMAGE_GENERATION",
                                    runtime = request.model.runtime,
                                    outputCount = transferredOutputs.size,
                                    outputBytes = outputBytes,
                                    executionMetadataJson = result.executionMetadataJson
                                )
                            )
                            transferredOutputs.clear()
                        } else {
                            transferredOutputs.deleteWorkerOutputs()
                            markJournalTerminal(
                                request.requestId,
                                ImageExecutionPhase.FAILED,
                                errorCode = "RESULT_DELIVERY_FAILED",
                                errorMessage = "The generated image could not be delivered to the client."
                            )
                        }
                    } finally {
                        active.markResultPublicationTerminalAttemptFinished()
                    }
                } catch (error: CancellationException) {
                    val message = error.message ?: "The image client or worker was cancelled."
                    finishJournalCancelled(request.requestId, message)
                    publishCancellationTerminal(active, message)
                    // Client death and service teardown have no live callback to notify.
                } catch (error: Throwable) {
                    val errorCode = localImageWorkerErrorCode(error)
                    val message = error.message ?: "Local image generation failed."
                    if (active.tryBeginErrorPublication()) {
                        transferredOutputs.deleteWorkerOutputs()
                        markJournalTerminal(
                            request.requestId,
                            ImageExecutionPhase.FAILED,
                            errorCode = errorCode.uppercase(),
                            errorMessage = message
                        )
                        sendError(
                            callback = callback,
                            requestId = request.requestId,
                            code = errorCode,
                            message = message
                        )
                    } else if (active.cancelRequested) {
                        finishJournalCancelled(request.requestId, message)
                        publishCancellationTerminal(active, message)
                    }
                } finally {
                    transferredOutputs.forEach { it.delete() }
                    workerInputStore.cleanup(workerInputs?.directory)
                    finish(active)
                }
}

internal suspend fun LocalImageWorkerService.runUpscaleJob(
    scope: CoroutineScope,
    active: LocalImageWorkerService.ActiveGeneration,
    request: LocalImageWorkerProtocol.UpscaleRequest,
    callback: ILocalImageWorkerCallback
) {
    val coroutineContext = scope.coroutineContext
            var transferredOutput: File? = null
            var workerInputs: LocalImageWorkerUpscaleInputs? = null
            try {
                val preparingDelivered = sendProgress(
                    callback,
                    request.requestId,
                    LocalImageProgress(
                        phase = "worker_preparing",
                        message = "Verifying the local ESRGAN inputs and model lease.",
                        step = 0,
                        steps = 1,
                        elapsedMs = 0L,
                        secondsPerStep = 0.0,
                        threads = request.threads,
                        width = request.input.width,
                        height = request.input.height,
                        cancelRequested = false
                    )
                )
                if (!preparingDelivered) cancelForDeadClient(active)
                coroutineContext.ensureActive()
                val preparedInputs = workerInputStore.materializeUpscale(
                    requestId = request.requestId,
                    input = request.input,
                    upscaler = request.upscaler,
                    isCancelled = {
                        active.cancelRequested || coroutineContext[Job]?.isActive != true
                    }
                )
                workerInputs = preparedInputs
                coroutineContext.ensureActive()
                prepareUpscaleExecutionJournal(request, preparedInputs)
                val startedDelivered = sendProgress(
                    callback,
                    request.requestId,
                    LocalImageProgress(
                        phase = "worker_started",
                        message = "Local ESRGAN worker started.",
                        step = 0,
                        steps = 1,
                        elapsedMs = 0L,
                        secondsPerStep = 0.0,
                        threads = request.threads,
                        width = preparedInputs.input.width,
                        height = preparedInputs.input.height,
                        cancelRequested = false,
                        requestOptionsJson = JSONObject()
                            .put("operation", "ESRGAN_UPSCALE")
                            .put("targetScale", request.targetScale)
                            .put("tileSize", request.tileSize)
                            .put("threads", request.threads)
                            .put("input", preparedInputs.input.toJson(includePath = false))
                            .put("upscaler", preparedInputs.upscaler.toJson(includePath = false))
                            .toString()
                    )
                )
                if (!startedDelivered) cancelForDeadClient(active)
                var waitingReported = false
                val result = NativeMediaExecutionGate.withLease(
                    context = applicationContext,
                    isCancelled = { active.cancelRequested },
                    onWaiting = {
                        if (!waitingReported) {
                            waitingReported = true
                            val delivered = sendProgress(
                                callback,
                                request.requestId,
                                LocalImageProgress(
                                    phase = "waiting_for_native_lease",
                                    message = "Waiting for another native image task to finish.",
                                    step = 0,
                                    steps = 1,
                                    elapsedMs = 0L,
                                    secondsPerStep = 0.0,
                                    threads = request.threads,
                                    width = preparedInputs.input.width,
                                    height = preparedInputs.input.height,
                                    cancelRequested = false
                                )
                            )
                            if (!delivered) cancelForDeadClient(active)
                        }
                    }
                ) {
                    provider.upscale(
                        input = preparedInputs.input,
                        upscaler = preparedInputs.upscaler,
                        targetScale = request.targetScale,
                        tileSize = request.tileSize,
                        threads = request.threads,
                        onProgress = { progress ->
                            updateJournalFromProgress(request.requestId, progress)
                            val delivered = sendProgress(
                                callback,
                                request.requestId,
                                active.withAccumulatedStages(progress)
                            )
                            if (!delivered) cancelForDeadClient(active)
                        }
                    )
                }
                preparedInputs.close()
                coroutineContext.ensureActive()
                advanceJournalTo(request.requestId, ImageExecutionPhase.PUBLISHING, observedStep = 1)
                updateJournalFromNativeResult(request.requestId, result.executionMetadataJson)
                val output = result.outputs.single()
                val target = prepareResultTarget(
                    requestId = request.requestId,
                    mimeType = output.mimeType,
                    index = 0
                )
                updateJournalOutputPath(request.requestId, target.partial)
                transferredOutput = writeResultFile(target, output.bytes)
                updateJournalOutputPath(request.requestId, requireNotNull(transferredOutput))
                updateJournalOutputArtifact(
                    requestId = request.requestId,
                    output = requireNotNull(transferredOutput),
                    index = 0,
                    mimeType = output.mimeType,
                    seed = output.seed
                )
                if (!active.tryBeginResultPublication()) {
                    throw CancellationException("Image upscale was cancelled before result publication.")
                }
                val delivered = sendComplete(
                    callback = callback,
                    requestId = request.requestId,
                    outputs = listOf(
                        LocalImageWorkerProtocol.OutputEnvelope(
                            index = 0,
                            outputPath = requireNotNull(transferredOutput).canonicalPath,
                            mimeType = output.mimeType,
                            seed = null
                        )
                    ),
                    executionMetadataJson = result.executionMetadataJson
                )
                try {
                    if (delivered) {
                        markJournalTerminal(request.requestId, ImageExecutionPhase.COMPLETED)
                        Log.i(
                            "MCA-LocalImage",
                            localImageWorkerCompletionLogSummary(
                                requestId = request.requestId,
                                workerPid = Process.myPid(),
                                operation = "ESRGAN_UPSCALE",
                                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                                outputCount = 1,
                                outputBytes = transferredOutput.length(),
                                executionMetadataJson = result.executionMetadataJson
                            )
                        )
                        transferredOutput = null
                    } else {
                        transferredOutput.delete()
                        transferredOutput = null
                        markJournalTerminal(
                            request.requestId,
                            ImageExecutionPhase.FAILED,
                            errorCode = "RESULT_DELIVERY_FAILED",
                            errorMessage = "The upscaled image could not be delivered to the client."
                        )
                    }
                } finally {
                    active.markResultPublicationTerminalAttemptFinished()
                }
            } catch (error: CancellationException) {
                val message = error.message ?: "The image upscale was cancelled."
                finishJournalCancelled(request.requestId, message)
                publishCancellationTerminal(active, message)
            } catch (error: Throwable) {
                val errorCode = localImageWorkerErrorCode(error)
                val message = error.message ?: "Local image upscale failed."
                if (active.tryBeginErrorPublication()) {
                    transferredOutput?.delete()
                    transferredOutput = null
                    markJournalTerminal(
                        request.requestId,
                        ImageExecutionPhase.FAILED,
                        errorCode = errorCode.uppercase(),
                        errorMessage = message
                    )
                    sendError(callback, request.requestId, errorCode, message)
                } else if (active.cancelRequested) {
                    finishJournalCancelled(request.requestId, message)
                    publishCancellationTerminal(active, message)
                }
            } finally {
                transferredOutput?.delete()
                workerInputs?.close()
                workerInputStore.cleanup(workerInputs?.directory)
                finish(active)
            }
}

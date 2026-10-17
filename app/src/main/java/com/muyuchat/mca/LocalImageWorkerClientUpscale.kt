package com.muyuchat.mca

import android.os.RemoteException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import com.muyuchat.mca.LocalImageWorkerClient.ActiveRequest

internal suspend fun LocalImageWorkerClient.upscale(
        inputImageReference: String,
        upscaler: LocalImagePreparedUpscaler,
        targetScale: Int,
        tileSize: Int = 128,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 5),
        requestId: String = UUID.randomUUID().toString(),
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult {
        return executionGate.run(requestId, "upscale") {
        require(inputImageReference.isNotBlank()) { "Upscale input image reference must not be blank." }
        val request = ActiveRequest(
            requestId = requestId,
            runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            requestedSteps = 1,
            requestedUseCfg = false,
            requestedBackendMode = null,
            onProgress = onProgress
        )
        synchronized(stateLock) {
            check(!closed) { "Local image worker client is closed." }
            check(activeRequest == null) { "Another local image operation is already running." }
            activeRequest = request
        }

        var inputDispatch: LocalImageInputDispatch? = null
        try {
            inputDispatch = inputDispatcher.prepare(
                requestId = requestId,
                draft = LocalImageInputDraft(
                    taskMode = LocalImageTaskMode.IMG2IMG,
                    inputImageReference = inputImageReference,
                    strength = 1.0
                ),
                baseOptions = LocalImageGenerationOptions()
            )
            val preparedInput = requireNotNull(inputDispatch.options.inputImage) {
                "Upscale input dispatcher did not publish a prepared image."
            }
            val runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP
            val prepared = currentPreparation(runtime) ?: run {
                begin(runtime)
                requireNotNull(currentPreparation(runtime))
            }
            prepared.ready.await()
            request.bindingSession = prepared.bindingSession
            if (prepared.cancelRequested) throw LocalImageWorkerCancelledException()
            if (request.completion.isCompleted) return@run request.completion.await().consumeResult()

            val endpoint = awaitService { session -> request.bindingSession = session }
            val service = endpoint.service
            if (request.completion.isCompleted) return@run request.completion.await().consumeResult()
            if (!request.handshake.tryBeginRemoteStart()) throw LocalImageWorkerCancelledException()
            val accepted = try {
                service.upscale(
                    LocalImageWorkerProtocol.upscaleRequest(
                        requestId = request.requestId,
                        input = preparedInput,
                        upscaler = upscaler,
                        targetScale = targetScale,
                        tileSize = tileSize,
                        threads = threads
                    ),
                    callbackFor(request)
                )
            } catch (error: RemoteException) {
                request.handshake.markFinished()
                handleConnectionLoss(
                    endpoint.session.connection,
                    "Local image worker upscale call failed: ${error.message.orEmpty()}"
                )
                throw LocalImageWorkerDisconnectedException("Local image worker upscale call failed.", error)
            }
            val cancelAfterRegistration = request.handshake.completeRemoteStart(accepted)
            if (!accepted) {
                request.completion.completeExceptionally(
                    LocalImageWorkerException("Local image worker rejected the upscale request.")
                )
            } else if (cancelAfterRegistration) {
                cancelRemote(request)
            } else {
                startUpscaleWatchdog(request)
            }
            return@run request.completion.await().consumeResult()
        } catch (cancelled: CancellationException) {
            request.completion.cancel(cancelled)
            if (request.handshake.requestCancel() == LocalImageStartHandshake.CancelAction.CANCEL_REMOTE) {
                cancelRemote(request)
            }
            throw cancelled
        } finally {
            request.handshake.markFinished()
            awaitWatchdogTerminationDispatch(request)
            if (!request.watchdogTimedOut) request.watchdogJob?.cancel()
            request.deliveredOutputPaths.forEach(::deleteResultIfSafe)
            releaseBindingAfterRequest(request, LocalImageRuntime.STABLE_DIFFUSION_CPP)
            inputDispatch?.directory?.deleteRecursively()
        }
        }
    }

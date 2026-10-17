package com.muyuchat.mca

import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal fun LocalImageWorkerService.sendProgress(
        callback: ILocalImageWorkerCallback,
        requestId: String,
        progress: LocalImageProgress
    ): Boolean = runCatching {
        callback.onProgress(
            LocalImageWorkerProtocol.progress(
                requestId = requestId,
                workerPid = Process.myPid(),
                progress = progress
            )
        )
    }.isSuccess

internal fun LocalImageWorkerService.sendComplete(
        callback: ILocalImageWorkerCallback,
        requestId: String,
        outputs: List<LocalImageWorkerProtocol.OutputEnvelope>,
        executionMetadataJson: String
    ): Boolean = runCatching {
        callback.onComplete(
            LocalImageWorkerProtocol.result(
                requestId = requestId,
                workerPid = Process.myPid(),
                outputs = outputs,
                executionMetadataJson = executionMetadataJson
            )
        )
    }.isSuccess

internal fun LocalImageWorkerService.sendError(
        callback: ILocalImageWorkerCallback,
        requestId: String,
        code: String,
        message: String
    ): Boolean = runCatching {
        callback.onError(
            LocalImageWorkerProtocol.error(
                requestId = requestId,
                workerPid = Process.myPid(),
                code = code,
                message = message
            )
        )
    }.isSuccess

internal fun LocalImageWorkerService.publishCancellationTerminal(
        active: LocalImageWorkerService.ActiveGeneration,
        message: String
    ): Boolean {
        if (!active.tryBeginCancellationTerminalPublication()) return false
        return sendError(
            callback = active.callback,
            requestId = active.requestId,
            code = LOCAL_IMAGE_GENERATION_CANCELLED_CODE,
            message = message.ifBlank { "Local image generation was cancelled." }
        )
    }

internal fun LocalImageWorkerService.prepareResultTarget(
        requestId: String,
        mimeType: String,
        index: Int = 0
    ): LocalImageWorkerService.ResultFileTarget {
        val outputDir = resultDirectory().apply { mkdirs() }
        cleanupStaleResults()
        val safeId = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val extension = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val baseName = "${safeId.ifBlank { "image" }}-${UUID.randomUUID()}-${index.toString().padStart(3, '0')}"
        return LocalImageWorkerService.ResultFileTarget(
            partial = File(outputDir, "$baseName.$extension.part"),
            output = File(outputDir, "$baseName.$extension")
        )
    }

internal fun LocalImageWorkerService.writeResultFile(target: LocalImageWorkerService.ResultFileTarget, bytes: ByteArray): File {
        return try {
            FileOutputStream(target.partial).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            durableMoveWithinParent(
                source = target.partial,
                target = target.output,
                move = { source, output ->
                    check(source.renameTo(output)) {
                        "Unable to publish local image worker output."
                    }
                }
            )
            target.output
        } catch (error: Throwable) {
            runCatching { target.partial.delete() }
            runCatching { target.output.delete() }
            throw error
        }
    }

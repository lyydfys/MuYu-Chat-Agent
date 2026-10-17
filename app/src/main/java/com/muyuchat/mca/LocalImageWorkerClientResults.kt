package com.muyuchat.mca

import android.os.DeadObjectException
import android.os.RemoteException
import java.io.File
import com.muyuchat.mca.LocalImageWorkerClient.BoundWorker

internal fun <T> LocalImageWorkerClient.runRemoteCall(
        endpoint: LocalImageWorkerClient.BoundWorker,
        block: () -> T
    ): Result<T> = runCatching(block).onFailure { error ->
        if (error is RemoteException || error is DeadObjectException) {
            handleConnectionLoss(
                endpoint.session.connection,
                "Local image worker IPC failed."
            )
        }
    }

    internal fun LocalImageWorkerClient.validatedResultFile(path: String): File {
        val root = resultDirectory().canonicalFile
        val candidate = File(path).canonicalFile
        require(candidate.path.startsWith(root.path + File.separator)) {
            "Local image worker returned a path outside its result directory."
        }
        return candidate
    }

    internal fun LocalImageWorkerClient.deleteResultIfSafe(path: String) {
        runCatching { validatedResultFile(path).delete() }
    }

    internal fun LocalImageWorkerClient.resultDirectory(): File =
        File(appContext.cacheDir, LocalImageWorkerService.RESULT_DIRECTORY)

    internal fun LocalImageWorkerClient.unlinkRemoteDeathRecipientLocked() {
        val binder = remoteBinder
        val recipient = remoteDeathRecipient
        if (binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        remoteDeathRecipient = null
    }

    internal fun LocalImageWorkerClient.remoteFailure(error: Throwable): Throwable =
        when (error) {
            is LocalImageWorkerException -> error
            is RemoteException -> LocalImageWorkerDisconnectedException(
                "Local image worker IPC failed: ${error.message.orEmpty()}",
                error
            )
            else -> error
        }

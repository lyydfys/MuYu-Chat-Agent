package com.muyuchat.core.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repoId = inputData.getString(KEY_REPO_ID) ?: return Result.failure()
        val revision = inputData.getString(KEY_REVISION) ?: "master"
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val sha256 = inputData.getString(KEY_SHA256)
        val finalPath = inputData.getString(KEY_FINAL_PATH) ?: return Result.failure()
        val tempPath = inputData.getString(KEY_TEMP_PATH) ?: "$finalPath.part"
        val remote = RemoteModelFile(
            repoId = repoId,
            revision = revision,
            path = path,
            name = path.substringAfterLast('/'),
            sha256 = sha256,
            downloadUrl = url
        )

        return runCatching {
            ResumableDownloader().download(
                remote = remote,
                tempFile = File(tempPath),
                finalFile = File(finalPath)
            )
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        const val KEY_REPO_ID = "repo_id"
        const val KEY_REVISION = "revision"
        const val KEY_PATH = "path"
        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_FINAL_PATH = "final_path"
        const val KEY_TEMP_PATH = "temp_path"
    }
}

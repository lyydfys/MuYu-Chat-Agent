package com.muyuchat.mca.presentation

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import com.muyuchat.feature.agent.workspace.AgentDeleteOutcome
import com.muyuchat.feature.agent.workspace.AgentDocumentRef
import com.muyuchat.feature.agent.workspace.AgentExportOutcome
import com.muyuchat.feature.agent.workspace.AgentImportOutcome
import com.muyuchat.feature.agent.workspace.AgentPackageActions
import com.muyuchat.feature.agent.workspace.AgentPackageOperationException
import com.muyuchat.mca.agent.composition.AgentRuntimeAssembly
import com.muyuchat.mca.agent.data.export.AgentPackageResult
import com.muyuchat.mca.agent.data.quota.AgentTaskDeletionResult
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * K14 host adapter: the only place that converts feature document refs to SAF streams and calls the
 * Agent data services. It lives in the app presentation layer because Rule 12 keeps app/agent free of
 * feature-module types; the page never sees a raw URI, file, stream or provider error.
 *
 * Export first stages the package in the data layer, then copies the verified zip into the caller's
 * document; a failed copy deletes that document so no truncated package survives.
 */
class AgentPackageService(
    context: Context,
    private val runtime: AgentRuntimeAssembly
) : AgentPackageActions {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun export(
        taskId: String,
        destination: AgentDocumentRef
    ): Result<AgentExportOutcome> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(destination.value)
        try {
            val descriptor = resolver.openFileDescriptor(uri, "wt")
                ?: return@withContext failure("PACKAGE_DESTINATION_UNAVAILABLE", "无法写入所选位置。")
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                when (val result = runtime.exportTaskPackage(taskId, output)) {
                    is AgentPackageResult.Success -> Unit
                    is AgentPackageResult.Failure -> {
                        discardDocument(uri)
                        return@withContext failure(result.code.name, result.safeMessage)
                    }
                }
            }
            Result.success(AgentExportOutcome.Saved)
        } catch (failure: Exception) {
            discardDocument(uri)
            failure("PACKAGE_EXPORT_FAILED", "任务包导出失败，请重试。")
        }
    }

    override suspend fun exportToDownloads(taskId: String): Result<AgentExportOutcome> =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@withContext failure(
                    "PACKAGE_DOWNLOAD_EXPORT_UNAVAILABLE",
                    "当前系统版本不支持免选择器导出。"
                )
            }
            val displayName = "agent-$taskId.mca-task.zip"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/MCA"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = runCatching {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }.getOrNull() ?: return@withContext failure(
                "PACKAGE_DOWNLOAD_EXPORT_FAILED",
                "无法在下载目录创建任务包。"
            )
            try {
                val output = resolver.openOutputStream(uri, "w")
                    ?: run {
                        discardDocument(uri)
                        return@withContext failure(
                            "PACKAGE_DOWNLOAD_EXPORT_FAILED",
                            "无法写入下载目录。"
                        )
                    }
                output.use { stream ->
                    when (val result = runtime.exportTaskPackage(taskId, stream)) {
                        is AgentPackageResult.Success -> Unit
                        is AgentPackageResult.Failure -> {
                            discardDocument(uri)
                            return@withContext failure(result.code.name, result.safeMessage)
                        }
                    }
                }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
                Result.success(AgentExportOutcome.Saved)
            } catch (failure: Exception) {
                discardDocument(uri)
                failure("PACKAGE_DOWNLOAD_EXPORT_FAILED", "任务包导出到下载目录失败，请重试。")
            }
        }

    override suspend fun importPackage(source: AgentDocumentRef): Result<AgentImportOutcome> =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(source.value)
            try {
                val input = resolver.openInputStream(uri)
                    ?: return@withContext failure("PACKAGE_SOURCE_UNAVAILABLE", "无法读取所选文件。")
                input.use { stream ->
                    when (val result = runtime.importTaskPackage(stream)) {
                        is AgentPackageResult.Success -> Result.success(
                            AgentImportOutcome(
                                importedTaskId = result.value.taskId,
                                displayTitle = result.value.displayTitle
                            )
                        )
                        is AgentPackageResult.Failure -> failure(result.code.name, result.safeMessage)
                    }
                }
            } catch (failure: Exception) {
                failure("PACKAGE_IMPORT_FAILED", "任务包导入失败，请确认文件完整后重试。")
            }
        }

    override suspend fun deleteTask(taskId: String): Result<AgentDeleteOutcome> =
        withContext(Dispatchers.IO) {
            when (val result = runtime.deleteTaskPackage(taskId)) {
                is AgentTaskDeletionResult.Deleted ->
                    Result.success(AgentDeleteOutcome(result.taskId))
                is AgentTaskDeletionResult.CancellationRequested -> failure(
                    "PACKAGE_DELETE_CANCELING",
                    "正在停止任务，请稍后重试删除。"
                )
                is AgentTaskDeletionResult.AwaitingStop -> failure(
                    "PACKAGE_DELETE_AWAITING_STOP",
                    "任务正在停止，确认停止后可重试删除。"
                )
                is AgentTaskDeletionResult.Rejected -> failure(result.code.name, result.safeMessage)
            }
        }

    private fun discardDocument(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }

    private fun <T> failure(code: String, message: String): Result<T> = Result.failure(
        AgentPackageOperationException(code = code, safeMessage = message)
    )

    /** Maintenance hooks for startup/idle callers; never on the UI thread. */
    suspend fun retryPendingCleanup() = withContext(Dispatchers.IO) {
        runtime.retryPackageCleanup()
    }

    suspend fun collectOrphanStaging() = withContext(Dispatchers.IO) {
        runtime.collectPackageStaging()
    }
}

package com.muyuchat.feature.dsh.workspace.web

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import com.muyuchat.feature.dsh.workspace.DshDownloadInspection
import com.muyuchat.feature.dsh.workspace.DshDownloadPolicy
import com.muyuchat.feature.dsh.workspace.DshOrigin

/** Result of starting one HTTP download. Messages contain only the local file name. */
internal sealed interface DshDownloadOutcome {
    val message: String

    data class Started(override val message: String) : DshDownloadOutcome
    data class Failed(override val message: String) : DshDownloadOutcome
}

/**
 * Starts same/external http(s) page downloads through the platform DownloadManager.
 *
 * Auth note: the DSH download endpoints live on the same loopback origin and rely on the
 * first-party HttpOnly cookie. `CookieManager.getCookie()` is the only public bridge from a
 * WebView session to DownloadManager; when the platform omits HttpOnly cookies the request may be
 * rejected. That is recorded as a device NOT_RUN in README.md; the secure fallback is a host-side
 * export/SAF path, not a JavaScript bridge. No token or cookie value is ever logged.
 */
internal class DshDownloader(context: Context) {

    private val appContext = context.applicationContext

    fun enqueue(
        download: DshDownloadInspection.Http,
        origin: DshOrigin
    ): DshDownloadOutcome {
        val manager = appContext.getSystemService(DownloadManager::class.java)
            ?: return DshDownloadOutcome.Failed("系统下载服务不可用，无法下载。")
        val publicDownloads = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val firstAttempt = runCatching {
            manager.enqueue(buildRequest(download, origin, publicDestination = publicDownloads))
        }
        if (firstAttempt.isSuccess) {
            return DshDownloadOutcome.Started(downloadMessage(download, publicDownloads))
        }
        if (publicDownloads) {
            val fallback = runCatching {
                manager.enqueue(buildRequest(download, origin, publicDestination = false))
            }
            if (fallback.isSuccess) {
                return DshDownloadOutcome.Started(downloadMessage(download, publicDestination = false))
            }
        }
        return DshDownloadOutcome.Failed("下载未能开始，请稍后重试。")
    }

    private fun buildRequest(
        download: DshDownloadInspection.Http,
        origin: DshOrigin,
        publicDestination: Boolean
    ): DownloadManager.Request {
        val request = DownloadManager.Request(Uri.parse(download.url)).apply {
            setTitle(download.suggestedFileName)
            setDescription("DeepSeek Harness 下载")
            download.mimeType
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 128 }
                ?.let { setMimeType(it) }
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        // First-party DSH cookies only; never forward them to another origin.
        if (origin.matches(download.url)) {
            val cookies = runCatching { CookieManager.getInstance().getCookie(origin.value) }
                .getOrNull()
                ?.let(DshDownloadPolicy::safeHeaderValue)
            if (cookies != null) request.addRequestHeader("Cookie", cookies)
        }
        download.userAgent?.let { request.addRequestHeader("User-Agent", it) }
        if (publicDestination) {
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                download.suggestedFileName
            )
        } else {
            request.setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                download.suggestedFileName
            )
        }
        return request
    }

    private fun downloadMessage(
        download: DshDownloadInspection.Http,
        publicDestination: Boolean
    ): String = if (publicDestination) {
        "已开始下载：${download.suggestedFileName}"
    } else {
        "已保存到应用专属下载目录：${download.suggestedFileName}"
    }
}

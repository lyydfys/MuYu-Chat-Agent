package com.muyuchat.mca

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * R3-S31：离线提示词翻译族体自 MainViewModel 迁出（体逐字；状态/下载器/安装器参数化）。
 */

internal fun offlineTranslationBundleReadyStatus(bundle: VerifiedOfflinePromptTranslationBundle): String =
    if (bundle.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2) {
        "Hy-MT2 已安装；可预览英文译文，英文模型自动翻译"
    } else {
        "翻译包已安装，但当前 runtime 尚未接入该模型；可下载 Hy-MT2。"
    }

internal suspend fun cacheVerifiedOfflineTranslationBundleFor(
    admission: RuntimeAdmissionState,
    bundle: VerifiedOfflinePromptTranslationBundle
) {
    admission.offlineTranslationBundleMutex.withLock {
        admission.offlinePromptTranslationBundle = bundle
    }
}

internal suspend fun verifiedOfflineTranslationBundleFor(
    store: UiStateStore,
    admission: RuntimeAdmissionState,
    downloader: OfflinePromptTranslationBundleDownloader
): VerifiedOfflinePromptTranslationBundle? = withContext(Dispatchers.IO) {
    admission.offlineTranslationBundleMutex.withLock {
        val cached = admission.offlinePromptTranslationBundle
        if (cached?.matchesVerifiedFiles() == true) return@withLock cached
        admission.offlinePromptTranslationBundle = null
        val verification = try {
            downloader.verifyInstalledOrRecoverCompleteDownload()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OfflinePromptTranslationBundleVerification.Rejected(
                OfflinePromptTranslationBundleRejectionCode.ROOT_UNAVAILABLE,
                error.message ?: "离线翻译包无法校验或启用"
            )
        }
        val bundle = (verification as? OfflinePromptTranslationBundleVerification.Verified)?.bundle
        admission.offlinePromptTranslationBundle = bundle
        store.update { state ->
            val wasInstalled = cached != null || state.offlineTranslationInstalled
            state.copy(
                offlineTranslationInstalled = bundle?.identity?.translatorFamily == OfflineTranslatorFamily.HY_MT2,
                offlineTranslationStatus = when {
                    bundle != null -> offlineTranslationBundleReadyStatus(bundle)
                    wasInstalled -> {
                        val reason = (verification as? OfflinePromptTranslationBundleVerification.Rejected)?.message
                            ?: "模型文件或清单已丢失"
                        "Hy-MT2 安装已失效：$reason。请重新导入或下载。"
                    }
                    else -> state.offlineTranslationStatus
                }
            )
        }
        bundle
    }
}

internal fun importOfflinePromptTranslationBundleFor(
    store: UiStateStore,
    admission: RuntimeAdmissionState,
    scope: CoroutineScope,
    installer: OfflinePromptTranslationBundleInstaller,
    uri: Uri
) {
    if (admission.offlineTranslationInstallJob?.isActive == true ||
        admission.offlineTranslationDownloadJob?.isActive == true
    ) return
    admission.offlineTranslationInstallJob = scope.launch {
        store.update { it.copy(offlineTranslationInstalling = true, offlineTranslationStatus = "正在导入并校验 Hy-MT2") }
        try {
            val bundle = installer.install(uri)
            cacheVerifiedOfflineTranslationBundleFor(admission, bundle)
            store.update { it.copy(
                offlineTranslationInstalled = bundle.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2,
                offlineTranslationStatus = offlineTranslationBundleReadyStatus(bundle),
                statusMessage = "离线翻译包已导入。"
            ) }
        } catch (cancelled: CancellationException) {
            store.update { it.copy(offlineTranslationStatus = "导入已取消") }
            throw cancelled
        } catch (error: Exception) {
            store.update { it.copy(offlineTranslationStatus = error.message ?: "离线翻译包导入失败") }
        } finally {
            store.update { it.copy(offlineTranslationInstalling = false) }
        }
    }
}

internal fun cancelOfflinePromptTranslationInstallFor(admission: RuntimeAdmissionState) {
    admission.offlineTranslationInstallJob?.cancel()
}

internal fun downloadOfflinePromptTranslationBundleFor(
    store: UiStateStore,
    admission: RuntimeAdmissionState,
    scope: CoroutineScope,
    downloader: OfflinePromptTranslationBundleDownloader
) {
    if (admission.offlineTranslationDownloadJob?.isActive == true ||
        admission.offlineTranslationInstallJob?.isActive == true
    ) return
    admission.offlineTranslationDownloadJob = scope.launch {
        store.update { it.copy(
            offlineTranslationDownloading = true,
            offlineTranslationDownloadBytes = 0L,
            offlineTranslationDownloadTotalBytes = 0L,
            offlineTranslationDownloadProgress = null,
            offlineTranslationStatus = "正在准备下载 Hy-MT2"
        ) }
        try {
            val bundle = downloader.download { progress ->
                store.update { state -> state.copy(
                    offlineTranslationDownloadBytes = progress.downloadedBytes,
                    offlineTranslationDownloadTotalBytes = progress.totalBytes,
                    offlineTranslationDownloadProgress =
                        (progress.downloadedBytes.toDouble() / progress.totalBytes.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f),
                    offlineTranslationStatus = progress.detail?.takeIf { it != "LICENSE" && it != "LICENSE.txt" && it != HyMt2PromptTranslationContract.MODEL_FILE }
                        ?: "正在下载并校验 Hy-MT2：${progress.downloadedBytes / 1_000_000} / ${progress.totalBytes / 1_000_000} MB"
                ) }
            }
            cacheVerifiedOfflineTranslationBundleFor(admission, bundle)
            store.update { it.copy(
                offlineTranslationInstalled = bundle.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2,
                offlineTranslationStatus = offlineTranslationBundleReadyStatus(bundle),
                statusMessage = "Hy-MT2 已下载并安装。"
            ) }
        } catch (cancelled: CancellationException) {
            store.update { it.copy(offlineTranslationStatus = "下载已暂停，可继续下载") }
            throw cancelled
        } catch (error: Exception) {
            store.update { it.copy(
                offlineTranslationStatus = "Hy-MT2 下载失败：${error.message ?: "请重试"}"
            ) }
        } finally {
            store.update { it.copy(offlineTranslationDownloading = false) }
        }
    }
}

internal fun cancelOfflinePromptTranslationDownloadFor(admission: RuntimeAdmissionState) {
    admission.offlineTranslationDownloadJob?.cancel()
}

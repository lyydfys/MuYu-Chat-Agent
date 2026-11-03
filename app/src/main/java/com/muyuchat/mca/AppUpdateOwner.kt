package com.muyuchat.mca

import android.content.Intent
import android.net.Uri
import android.os.Build
import com.muyuchat.core.download.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

import android.app.Application
import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Moved here with their only consumer: every one of these was referenced solely by the update
// flow, so keeping one copy in the owner file avoids two definitions drifting apart.
internal const val APP_UPDATE_PREFERENCES = "mca_app_updates_v1"
internal const val APP_UPDATE_AUTO_CHECK_KEY = "auto_check_enabled"
internal const val APP_UPDATE_LAST_CHECKED_KEY = "last_checked_at"
internal const val APP_UPDATE_LAST_FAILURE_KEY = "last_failure_at"
internal const val APP_UPDATE_ETAG_KEY = "etag"
internal const val APP_UPDATE_RELEASE_JSON_KEY = "release_json"
internal const val APP_UPDATE_VERIFIED_ASSET_KEY = "verified_asset"
internal const val APP_UPDATE_VERIFIED_SHA256_KEY = "verified_sha256"
internal const val APP_UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
internal const val APP_UPDATE_FAILURE_BACKOFF_MS = 15L * 60L * 1000L
internal const val APP_UPDATE_DIRECTORY = "updates"

/**
 * The owner seam for the in-app update flow.
 *
 * It is deliberately tiny: the update machinery -- the preferences, the GitHub client, the downloader,
 * the three jobs, the discovered candidate and the downloaded file -- is **entirely family-private**,
 * so the owner needs almost nothing from the app. Measurements before this slice showed every one of
 * the nine fields had exactly one reference outside the family, and that reference was its own
 * declaration. Only the state publish and the application context cross this boundary.
 */
internal interface AppUpdatePort {

    val context: Application

    fun uiState(): MainUiState

    fun updateUiState(transform: (AppUpdateFields) -> AppUpdateFields)
}

/**
 * Owns the in-app update flow: check, download, install and open-release.
 *
 * Three jobs live here instead of on the ViewModel. They are the lifecycle of this flow: a check can be
 * superseded by another check, and the install path deliberately waits on the download job. Keeping them
 * private to this class means no other ViewModel code can cancel or join them, which is what the
 * previous arrangement allowed.
 *
 * The user-visible strings are reproduced verbatim; the update flow is one of the few places where a
 * wrong word changes what the user believes about an installation.
 */
internal data class AppUpdateResolution(
        val state: AppUpdateState,
        val candidate: AppUpdateCandidate?
    )

internal class AppUpdateOwner(
    private val port: AppUpdatePort,
    /** The scope the update jobs run in. It is a constructor field, not a port member: a scope
     * is the caller's resource, and the three jobs belong to this owner. */
    private val scope: CoroutineScope
) {

    val appUpdatePreferences = port.context.getSharedPreferences(
        APP_UPDATE_PREFERENCES,
        Context.MODE_PRIVATE
    )
    val appUpdateClient = GitHubReleaseClient()
    val appUpdateDownloader = AppUpdateDownloader()
    val currentAppVersion = runCatching { readCurrentAppVersion(port.context) }
        .getOrElse { CurrentAppVersion("0.0.0", 0L) }
    @Volatile
    var appUpdateCandidate: AppUpdateCandidate? = null
    @Volatile
    var downloadedAppUpdateFile: File? = null
    var appUpdateCheckJob: Job? = null
    var appUpdateDownloadJob: Job? = null
    var appUpdateInstallJob: Job? = null

    fun loadInitialAppUpdateState(): AppUpdateState {
        val base = AppUpdateState(
            currentVersionName = currentAppVersion.versionName,
            currentVersionCode = currentAppVersion.versionCode,
            autoCheckEnabled = appUpdatePreferences.getBoolean(APP_UPDATE_AUTO_CHECK_KEY, true),
            lastCheckedAtMillis = appUpdatePreferences.getLong(APP_UPDATE_LAST_CHECKED_KEY, 0L)
        )
        val raw = appUpdatePreferences.getString(APP_UPDATE_RELEASE_JSON_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: return base
        return runCatching {
            val resolution = resolveAppUpdate(parseGitHubReleaseJson(raw), base.lastCheckedAtMillis)
            appUpdateCandidate = resolution.candidate
            val candidate = resolution.candidate
            val verifiedKey = appUpdatePreferences.getString(APP_UPDATE_VERIFIED_ASSET_KEY, null)
            val expectedKey = candidate?.let { "${it.release.tagName}|${it.apkAsset?.name.orEmpty()}" }
            if (candidate?.expectedSha256 == null && expectedKey != null && verifiedKey == expectedKey) {
                resolution.state.copy(
                    apkSha256 = appUpdatePreferences.getString(APP_UPDATE_VERIFIED_SHA256_KEY, null)
                )
            } else {
                resolution.state
            }
        }.getOrElse { error ->
            base.copy(status = AppUpdateStatus.ERROR, message = "读取缓存的更新信息失败：${error.message.orEmpty()}")
        }
    }

    fun resolveAppUpdate(release: GitHubRelease, checkedAt: Long): AppUpdateResolution {
        val version = parseReleaseVersionOrNull(release.tagName)
        val candidate = buildAppUpdateCandidate(
            release = release,
            currentVersionName = currentAppVersion.versionName,
            supportedAbis = Build.SUPPORTED_ABIS.toList()
        )
        val newer = version != null && version > (parseReleaseVersionOrNull(currentAppVersion.versionName) ?: ReleaseVersion(0, 0, 0))
        val status = when {
            release.draft || release.prerelease || version?.preRelease?.isNotEmpty() == true -> AppUpdateStatus.UP_TO_DATE
            version == null -> AppUpdateStatus.ERROR
            newer -> AppUpdateStatus.AVAILABLE
            else -> AppUpdateStatus.UP_TO_DATE
        }
        val releaseUrl = release.htmlUrl
            .takeIf(::isTrustedGitHubDownloadUrl)
            ?: GITHUB_RELEASE_PAGE_URL
        val apk = candidate?.apkAsset
        val canDownload = candidate?.let { value ->
            value.installable &&
                (value.expectedSha256 != null || value.checksumAsset != null)
        } == true
        val message = when {
            status == AppUpdateStatus.ERROR -> "GitHub Release 版本号无法识别。"
            status == AppUpdateStatus.AVAILABLE && apk == null -> "当前设备暂无兼容的 APK 资产，可打开 Release 页面手动查看。"
            status == AppUpdateStatus.AVAILABLE && !canDownload -> "GitHub Release 没有提供可验证的 APK SHA-256。"
            release.draft || release.prerelease || version?.preRelease?.isNotEmpty() == true -> "当前最新发布不是正式稳定版。"
            else -> null
        }
        return AppUpdateResolution(
            state = AppUpdateState(
                currentVersionName = currentAppVersion.versionName,
                currentVersionCode = currentAppVersion.versionCode,
                autoCheckEnabled = appUpdatePreferences.getBoolean(APP_UPDATE_AUTO_CHECK_KEY, true),
                status = status,
                latestVersionName = version?.toString() ?: release.tagName,
                latestTitle = release.name,
                releaseNotes = release.body,
                releaseUrl = releaseUrl,
                apkSizeBytes = apk?.sizeBytes ?: 0L,
                apkSha256 = candidate?.expectedSha256,
                canDownload = canDownload,
                lastCheckedAtMillis = checkedAt,
                message = message
            ),
            candidate = candidate
        )
    }

    fun maybeCheckForAppUpdate() {
        val state = port.uiState().appUpdate
        if (!state.autoCheckEnabled) return
        val now = System.currentTimeMillis()
        if (state.lastCheckedAtMillis > 0L && now - state.lastCheckedAtMillis < APP_UPDATE_CHECK_INTERVAL_MS) return
        val lastFailure = appUpdatePreferences.getLong(APP_UPDATE_LAST_FAILURE_KEY, 0L)
        if (lastFailure > 0L && now - lastFailure < APP_UPDATE_FAILURE_BACKOFF_MS) return
        checkForAppUpdate(force = false)
    }

    fun checkForAppUpdate() {
        checkForAppUpdate(force = true)
    }

    fun checkForAppUpdate(force: Boolean) {
        if (!force && !port.uiState().appUpdate.autoCheckEnabled) return
        if (appUpdateDownloadJob?.isActive == true || appUpdateInstallJob?.isActive == true) return
        if (appUpdateCheckJob?.isActive == true) return
        val job = scope.launch(Dispatchers.IO) {
            port.updateUiState { state ->
                state.copy(appUpdate = state.appUpdate.copy(status = AppUpdateStatus.CHECKING, message = null))
            }
            try {
                val now = System.currentTimeMillis()
                val etag = appUpdatePreferences.getString(APP_UPDATE_ETAG_KEY, null)
                val resolution = when (val result = appUpdateClient.fetchLatest(etag)) {
                    is GitHubReleaseFetchResult.Fetched -> {
                        appUpdatePreferences.edit()
                            .putString(APP_UPDATE_RELEASE_JSON_KEY, result.rawJson)
                            .putString(APP_UPDATE_ETAG_KEY, result.etag)
                            .putLong(APP_UPDATE_LAST_CHECKED_KEY, now)
                            .remove(APP_UPDATE_LAST_FAILURE_KEY)
                            .apply()
                        resolveAppUpdate(result.release, now)
                    }
                    is GitHubReleaseFetchResult.NotModified -> {
                        val cached = appUpdatePreferences.getString(APP_UPDATE_RELEASE_JSON_KEY, null)
                            ?: error("GitHub 返回 304，但本机没有缓存的 Release。")
                        appUpdatePreferences.edit()
                            .putLong(APP_UPDATE_LAST_CHECKED_KEY, now)
                            .putString(APP_UPDATE_ETAG_KEY, result.etag ?: etag)
                            .remove(APP_UPDATE_LAST_FAILURE_KEY)
                            .apply()
                        resolveAppUpdate(parseGitHubReleaseJson(cached), now)
                    }
                }
                appUpdateCandidate = resolution.candidate
                port.updateUiState { state -> state.copy(appUpdate = resolution.state) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failureAt = System.currentTimeMillis()
                appUpdateCandidate = null
                appUpdatePreferences.edit().putLong(APP_UPDATE_LAST_FAILURE_KEY, failureAt).apply()
                port.updateUiState { state ->
                    state.copy(
                        appUpdate = state.appUpdate.copy(
                            status = AppUpdateStatus.ERROR,
                            canDownload = false,
                            message = error.message ?: "更新检查失败。"
                        )
                    )
                }
            }
        }
        appUpdateCheckJob = job
        job.invokeOnCompletion {
            if (appUpdateCheckJob === job) appUpdateCheckJob = null
        }
    }

    fun setAppUpdateAutoCheckEnabled(enabled: Boolean) {
        appUpdatePreferences.edit().putBoolean(APP_UPDATE_AUTO_CHECK_KEY, enabled).apply()
        port.updateUiState { state -> state.copy(appUpdate = state.appUpdate.copy(autoCheckEnabled = enabled)) }
        if (enabled) maybeCheckForAppUpdate()
    }

    fun downloadAppUpdate() {
        if (appUpdateCheckJob?.isActive == true) {
            port.updateUiState { state ->
                state.copy(appUpdate = state.appUpdate.copy(message = "正在检查更新，请稍候。"))
            }
            return
        }
        if (appUpdateInstallJob?.isActive == true) return
        val candidate = appUpdateCandidate
        val asset = candidate?.apkAsset
        if (candidate == null || asset == null) {
            port.updateUiState { state -> state.copy(appUpdate = state.appUpdate.copy(status = AppUpdateStatus.ERROR, message = "没有可下载的兼容 APK，请先检查更新。")) }
            return
        }
        if (appUpdateDownloadJob?.isActive == true) return
        val job = scope.launch(Dispatchers.IO) {
            port.updateUiState { state ->
                state.copy(appUpdate = state.appUpdate.copy(status = AppUpdateStatus.DOWNLOADING, message = null, downloadedBytes = 0L, downloadTotalBytes = asset.sizeBytes, downloadSpeedBytesPerSecond = 0L, downloadRemainingSeconds = null))
            }
            try {
                val expectedSha = candidate.expectedSha256
                    ?: candidate.checksumAsset?.let { checksum ->
                        parseSha256Sidecar(appUpdateClient.fetchText(checksum.downloadUrl), asset.name)
                    }
                    ?: error("GitHub Release 没有提供可验证的 APK SHA-256。")
                val app = port.context
                val file = appUpdateDownloader.download(
                    candidate = candidate,
                    expectedSha256 = expectedSha,
                    updatesDirectory = File(app.filesDir, APP_UPDATE_DIRECTORY)
                ) { snapshot ->
                    port.updateUiState { state ->
                        state.copy(appUpdate = state.appUpdate.copy(
                            status = if (snapshot.status == DownloadStatus.DONE) AppUpdateStatus.READY_TO_INSTALL else AppUpdateStatus.DOWNLOADING,
                            downloadedBytes = snapshot.downloadedBytes,
                            downloadTotalBytes = snapshot.expectedLength.takeIf { it > 0L } ?: asset.sizeBytes,
                            downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                            downloadRemainingSeconds = snapshot.remainingSeconds,
                            message = snapshot.errorMessage
                        ))
                    }
                }
                val validationError = ApkUpdateInstaller.validateArchive(
                    context = app,
                    apkFile = file,
                    current = currentAppVersion,
                    expectedReleaseVersion = candidate.version
                )
                require(validationError == null) { validationError ?: "更新 APK 无效。" }
                downloadedAppUpdateFile = file
                port.updateUiState { state ->
                    state.copy(appUpdate = state.appUpdate.copy(
                        status = AppUpdateStatus.READY_TO_INSTALL,
                        downloadedBytes = file.length(),
                        downloadTotalBytes = file.length(),
                        downloadSpeedBytesPerSecond = 0L,
                        downloadRemainingSeconds = null,
                        apkSha256 = expectedSha,
                        message = "下载完成，已通过 SHA-256、包名和版本号校验。"
                    ))
                }
                val verifiedKey = "${candidate.release.tagName}|${asset.name}"
                appUpdatePreferences.edit()
                    .putString(APP_UPDATE_VERIFIED_SHA256_KEY, expectedSha)
                    .putString(APP_UPDATE_VERIFIED_ASSET_KEY, verifiedKey)
                    .apply()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                port.updateUiState { state -> state.copy(appUpdate = state.appUpdate.copy(status = AppUpdateStatus.ERROR, message = error.message ?: "更新下载失败。", downloadSpeedBytesPerSecond = 0L, downloadRemainingSeconds = null)) }
            }
        }
        appUpdateDownloadJob = job
        job.invokeOnCompletion {
            if (appUpdateDownloadJob === job) appUpdateDownloadJob = null
        }
    }

    fun installAppUpdate() {
        if (appUpdateDownloadJob?.isActive == true || appUpdateInstallJob?.isActive == true) return
        val app = port.context
        val candidate = appUpdateCandidate
        val asset = candidate?.apkAsset
        val file = downloadedAppUpdateFile
            ?: asset?.let { File(File(app.filesDir, APP_UPDATE_DIRECTORY), it.name) }
        if (file == null || !file.isFile) {
            port.updateUiState { state -> state.copy(appUpdate = state.appUpdate.copy(status = AppUpdateStatus.ERROR, message = "请先下载更新 APK。")) }
            return
        }
        if (candidate == null || asset == null) {
            port.updateUiState { state -> state.copy(appUpdate = state.appUpdate.copy(status = AppUpdateStatus.ERROR, message = "更新信息已过期，请重新检查更新。")) }
            return
        }
        val expectedSha = normalizeSha256OrNull(
            candidate.expectedSha256
                ?: port.uiState().appUpdate.apkSha256
                ?: appUpdatePreferences.getString(APP_UPDATE_VERIFIED_SHA256_KEY, null)
        )
        val verifiedAssetKey = appUpdatePreferences.getString(APP_UPDATE_VERIFIED_ASSET_KEY, null)
        val expectedAssetKey = "${candidate.release.tagName}|${asset.name}"
        if (expectedSha == null || verifiedAssetKey != expectedAssetKey) {
            port.updateUiState { state -> state.copy(appUpdate = state.appUpdate.copy(status = AppUpdateStatus.ERROR, message = "更新 APK 的完整性凭据已过期，请重新下载。")) }
            return
        }
        val job = scope.launch(Dispatchers.IO) {
            port.updateUiState { state ->
                state.copy(appUpdate = state.appUpdate.copy(
                    status = AppUpdateStatus.VERIFYING,
                    message = "正在校验更新 APK 完整性。"
                ))
            }
            try {
                val validationError = ApkUpdateInstaller.validateArchive(
                    context = app,
                    apkFile = file,
                    current = currentAppVersion,
                    expectedReleaseVersion = candidate.version
                )
                require(validationError == null) { validationError ?: "更新 APK 无效。" }
                require(sha256File(file).equals(expectedSha, ignoreCase = true)) {
                    "更新 APK SHA-256 校验失败，请重新下载。"
                }
                withContext(Dispatchers.Main.immediate) {
                    if (!ApkUpdateInstaller.canInstallUnknownSources(app)) {
                        app.startActivity(ApkUpdateInstaller.unknownSourcesSettingsIntent(app))
                        port.updateUiState { state ->
                            state.copy(appUpdate = state.appUpdate.copy(
                                status = AppUpdateStatus.READY_TO_INSTALL,
                                message = "请允许 MCA 安装未知来源应用，然后再次点击安装。"
                            ))
                        }
                    } else {
                        app.startActivity(ApkUpdateInstaller.installIntent(app, file))
                        port.updateUiState { state ->
                            state.copy(appUpdate = state.appUpdate.copy(
                                status = AppUpdateStatus.READY_TO_INSTALL,
                                message = "已打开系统安装器。"
                            ))
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                port.updateUiState { state ->
                    state.copy(appUpdate = state.appUpdate.copy(
                        status = AppUpdateStatus.ERROR,
                        message = error.message ?: "无法安装更新 APK。"
                    ))
                }
            }
        }
        appUpdateInstallJob = job
        job.invokeOnCompletion {
            if (appUpdateInstallJob === job) appUpdateInstallJob = null
        }
    }

    fun openAppUpdateRelease() {
        val raw = port.uiState().appUpdate.releaseUrl
        val url = raw.takeIf(::isTrustedGitHubDownloadUrl) ?: GITHUB_RELEASE_PAGE_URL
        runCatching {
            port.context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { error ->
            port.updateUiState { state -> state.copy(appUpdate = state.appUpdate.copy(status = AppUpdateStatus.ERROR, message = error.message ?: "无法打开 GitHub Release 页面。")) }
        }
    }
}

package com.muyuchat.mca

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.ResumableDownloader
import com.muyuchat.core.download.DownloadTaskSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal const val GITHUB_RELEASE_API_URL =
    "https://api.github.com/repos/lyydfys/MuYu-Chat-Agent/releases/latest"
internal const val GITHUB_RELEASE_PAGE_URL =
    "https://github.com/lyydfys/MuYu-Chat-Agent/releases/latest"
internal const val GITHUB_RELEASE_REPOSITORY = "lyydfys/MuYu-Chat-Agent"

internal data class CurrentAppVersion(
    val versionName: String,
    val versionCode: Long
)

internal data class GitHubReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val digest: String?
)

internal data class GitHubRelease(
    val tagName: String,
    val name: String,
    val htmlUrl: String,
    val body: String,
    val publishedAt: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GitHubReleaseAsset>
)

internal data class AppUpdateCandidate(
    val release: GitHubRelease,
    val version: ReleaseVersion,
    val apkAsset: GitHubReleaseAsset?,
    val checksumAsset: GitHubReleaseAsset?,
    val expectedSha256: String?
) {
    val installable: Boolean
        get() = apkAsset != null
}

enum class AppUpdateStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    READY_TO_INSTALL,
    ERROR
}

data class AppUpdateState(
    val currentVersionName: String = "",
    val currentVersionCode: Long = 0L,
    val autoCheckEnabled: Boolean = true,
    val status: AppUpdateStatus = AppUpdateStatus.IDLE,
    val latestVersionName: String? = null,
    val latestTitle: String? = null,
    val releaseNotes: String = "",
    val releaseUrl: String = GITHUB_RELEASE_PAGE_URL,
    val apkSizeBytes: Long = 0L,
    val apkSha256: String? = null,
    val canDownload: Boolean = false,
    val downloadedBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val lastCheckedAtMillis: Long = 0L,
    val message: String? = null
)

internal data class ReleaseVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val preRelease: List<String> = emptyList()
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)
            .takeIf { it != 0 }
            ?.let { return it }
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        val length = maxOf(preRelease.size, other.preRelease.size)
        for (index in 0 until length) {
            val left = preRelease.getOrNull(index) ?: return -1
            val right = other.preRelease.getOrNull(index) ?: return 1
            if (left == right) continue
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            return when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
        }
        return 0
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (preRelease.isNotEmpty()) append('-').append(preRelease.joinToString("."))
    }
}

internal fun parseReleaseVersionOrNull(raw: String?): ReleaseVersion? {
    val value = raw?.trim().orEmpty()
    val match = Regex(
        "^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$"
    ).matchEntire(value) ?: return null
    val preRelease = match.groupValues[4]
        .takeIf { it.isNotBlank() }
        ?.split('.')
        .orEmpty()
    if (preRelease.any { it.isBlank() }) return null
    return ReleaseVersion(
        major = match.groupValues[1].toLongOrNull() ?: return null,
        minor = match.groupValues[2].toLongOrNull() ?: return null,
        patch = match.groupValues[3].toLongOrNull() ?: return null,
        preRelease = preRelease
    )
}

internal fun archiveVersionMatchesRelease(
    archiveVersionName: String?,
    expectedReleaseVersion: ReleaseVersion
): Boolean = parseReleaseVersionOrNull(archiveVersionName) == expectedReleaseVersion

internal fun normalizeSha256OrNull(raw: String?): String? {
    val value = raw?.trim()
        ?.removePrefix("sha256:")
        ?.removePrefix("SHA256:")
        ?.trim()
        .orEmpty()
    return value.takeIf { it.matches(Regex("^[0-9a-fA-F]{64}$")) }?.lowercase(Locale.US)
}

internal fun parseGitHubReleaseJson(raw: String): GitHubRelease {
    val root = JSONObject(raw)
    val tagName = root.optString("tag_name").trim()
    require(tagName.isNotBlank()) { "GitHub Release 缺少 tag_name。" }
    val assetsJson = root.optJSONArray("assets") ?: JSONArray()
    val assets = buildList {
        for (index in 0 until assetsJson.length()) {
            val asset = assetsJson.optJSONObject(index) ?: continue
            val name = asset.optString("name").trim()
            val url = asset.optString("browser_download_url").trim()
            if (name.isBlank() || url.isBlank()) continue
            add(
                GitHubReleaseAsset(
                    name = name,
                    downloadUrl = url,
                    sizeBytes = asset.optLong("size", 0L).coerceAtLeast(0L),
                    digest = normalizeSha256OrNull(asset.optString("digest").takeIf { it.isNotBlank() })
                )
            )
        }
    }
    return GitHubRelease(
        tagName = tagName,
        name = root.optString("name", tagName).trim().ifBlank { tagName },
        htmlUrl = root.optString("html_url", GITHUB_RELEASE_PAGE_URL).trim(),
        body = root.optString("body", ""),
        publishedAt = root.optString("published_at", ""),
        draft = root.optBoolean("draft", false),
        prerelease = root.optBoolean("prerelease", false),
        assets = assets
    )
}

internal fun isTrustedGitHubDownloadUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (!uri.userInfo.isNullOrBlank() || (uri.port != -1 && uri.port != 443)) return false
    val host = uri.host?.lowercase(Locale.US) ?: return false
    return host == "github.com" ||
        host == "objects.githubusercontent.com" ||
        host == "release-assets.githubusercontent.com" ||
        host == "github-releases.githubusercontent.com"
}

private fun isTrustedGitHubReleaseNetworkUrl(raw: String): Boolean {
    if (isTrustedGitHubDownloadUrl(raw)) return true
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("api.github.com", ignoreCase = true)
}

private val SAFE_ASSET_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,180}$")
private val EXPLICIT_ABI_MARKER = Regex(
    "(?:^|[-_.])(arm64-v8a|arm64|aarch64|armeabi-v7a|armeabi|armv7|armv7a|" +
        "x86_64|x86|amd64|x64|riscv64|mips64|mips|loongarch64)(?:[-_.]|$)"
)

internal fun selectReleaseApkAsset(
    assets: List<GitHubReleaseAsset>,
    supportedAbis: List<String>
): GitHubReleaseAsset? {
    val apkAssets = assets.filter { asset ->
        asset.name.endsWith(".apk", ignoreCase = true) &&
            SAFE_ASSET_NAME.matches(asset.name) &&
            isTrustedGitHubDownloadUrl(asset.downloadUrl)
    }
    if (apkAssets.isEmpty()) return null
    for (abi in supportedAbis.map { it.lowercase(Locale.US) }.filter { it.isNotBlank() }) {
        val matches = apkAssets.filter { asset -> asset.name.lowercase(Locale.US).matchesAbi(abi) }
        if (matches.size == 1) return matches.single()
        if (matches.size > 1) {
            val exact = matches.filter { it.name.lowercase(Locale.US).endsWith("-$abi.apk") }
            if (exact.size == 1) return exact.single()
            return null
        }
    }
    // Never silently use a package built for another ABI. A package without an
    // explicit ABI marker is treated as a universal/generic build and is safe
    // to use only when it is the sole remaining candidate.
    val generic = apkAssets.filterNot { asset ->
        EXPLICIT_ABI_MARKER.containsMatchIn(asset.name.lowercase(Locale.US))
    }
    return generic.singleOrNull()
}

private fun String.matchesAbi(abi: String): Boolean =
    Regex("(?:^|[-_.])${Regex.escape(abi)}(?:[-_.]|\\.apk$)").containsMatchIn(this)

internal fun selectReleaseChecksumAsset(
    assets: List<GitHubReleaseAsset>,
    apkAsset: GitHubReleaseAsset?
): GitHubReleaseAsset? {
    val apk = apkAsset ?: return null
    val candidates = assets.filter { asset ->
        asset.name.endsWith(".sha256", ignoreCase = true) &&
            SAFE_ASSET_NAME.matches(asset.name) &&
            isTrustedGitHubDownloadUrl(asset.downloadUrl)
    }
    val stem = apk.name.removeSuffix(".apk")
    return candidates.firstOrNull { it.name.startsWith(stem, ignoreCase = true) }
        ?: candidates.singleOrNull()
}

internal fun buildAppUpdateCandidate(
    release: GitHubRelease,
    currentVersionName: String,
    supportedAbis: List<String>
): AppUpdateCandidate? {
    if (release.draft || release.prerelease) return null
    val version = parseReleaseVersionOrNull(release.tagName) ?: return null
    if (version.preRelease.isNotEmpty()) return null
    val current = parseReleaseVersionOrNull(currentVersionName) ?: return null
    if (version <= current) return null
    val apk = selectReleaseApkAsset(release.assets, supportedAbis)
    val checksum = selectReleaseChecksumAsset(release.assets, apk)
    return AppUpdateCandidate(
        release = release,
        version = version,
        apkAsset = apk,
        checksumAsset = checksum,
        expectedSha256 = normalizeSha256OrNull(apk?.digest)
    )
}

internal fun parseSha256Sidecar(content: String, expectedFileName: String): String? {
    val entries = content.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val match = Regex("^([0-9a-fA-F]{64})\\s+[*]?(.+?)\\s*$").matchEntire(line)
                ?: Regex("^([0-9a-fA-F]{64})\\s*$").matchEntire(line)
            match?.let {
                val fileName = it.groupValues.getOrNull(2).orEmpty().trim().trimStart('*')
                    .substringAfterLast('/')
                    .takeIf(String::isNotBlank)
                normalizeSha256OrNull(it.groupValues[1]) to fileName
            }
        }
        .toList()
    if (entries.isEmpty()) return null
    val matching = entries.filter { (_, fileName) ->
        fileName == null || fileName.equals(expectedFileName, ignoreCase = true)
    }
    if (matching.size != 1) return null
    return matching.single().first
}

internal sealed interface GitHubReleaseFetchResult {
    data class Fetched(val release: GitHubRelease, val rawJson: String, val etag: String?) : GitHubReleaseFetchResult
    data class NotModified(val etag: String?) : GitHubReleaseFetchResult
}

internal class GitHubReleaseClient(
    private val client: OkHttpClient = defaultGitHubHttpClient(),
    private val endpoint: String = GITHUB_RELEASE_API_URL
) {
    fun fetchLatest(etag: String? = null): GitHubReleaseFetchResult {
        require(endpoint == GITHUB_RELEASE_API_URL) { "GitHub Release endpoint is fixed to the official repository." }
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MCA-App-Update")
            .apply { etag?.takeIf(String::isNotBlank)?.let { header("If-None-Match", it) } }
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 304) return GitHubReleaseFetchResult.NotModified(response.header("ETag") ?: etag)
            require(response.isSuccessful) { "GitHub 更新检查失败：HTTP ${response.code}" }
            val body = readUtf8Body(
                requireNotNull(response.body) { "GitHub 更新响应为空。" },
                MAX_RELEASE_JSON_BYTES,
                "GitHub 更新响应过大。"
            )
            return GitHubReleaseFetchResult.Fetched(
                release = parseGitHubReleaseJson(body),
                rawJson = body,
                etag = response.header("ETag")
            )
        }
    }

    fun fetchText(url: String): String {
        require(isTrustedGitHubDownloadUrl(url)) { "更新资产地址不是受信任的 GitHub HTTPS 地址。" }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/plain,application/octet-stream;q=0.8,*/*;q=0.1")
            .header("User-Agent", "MCA-App-Update")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "读取更新校验文件失败：HTTP ${response.code}" }
            return readUtf8Body(
                requireNotNull(response.body) { "更新校验文件为空。" },
                MAX_CHECKSUM_BYTES,
                "更新校验文件过大。"
            )
        }
    }

    companion object {
        private const val MAX_CHECKSUM_BYTES = 64L * 1024L
        private const val MAX_RELEASE_JSON_BYTES = 1024L * 1024L
    }
}

internal class AppUpdateDownloader(
    private val downloader: ResumableDownloader = ResumableDownloader(defaultGitHubAssetHttpClient())
) {
    suspend fun download(
        candidate: AppUpdateCandidate,
        expectedSha256: String,
        updatesDirectory: File,
        onProgress: (DownloadTaskSnapshot) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val asset = requireNotNull(candidate.apkAsset) { "当前设备没有可用的 APK 资产。" }
        require(SAFE_ASSET_NAME.matches(asset.name)) { "更新 APK 文件名不安全。" }
        require(isTrustedGitHubDownloadUrl(asset.downloadUrl)) { "更新 APK 地址不是受信任的 GitHub 地址。" }
        val sha256 = requireNotNull(normalizeSha256OrNull(expectedSha256)) { "更新 APK 缺少有效 SHA-256。" }
        require(updatesDirectory.isDirectory || updatesDirectory.mkdirs()) { "无法创建更新目录。" }
        val finalFile = File(updatesDirectory, asset.name).canonicalFile
        require(finalFile.parentFile == updatesDirectory.canonicalFile) { "更新 APK 路径越界。" }
        if (finalFile.isFile && finalFile.length() == asset.sizeBytes && sha256File(finalFile) == sha256) {
            return@withContext finalFile
        }
        val tempFile = File(updatesDirectory, ".${asset.name}.part").canonicalFile
        val remote = RemoteModelFile(
            repoId = GITHUB_RELEASE_REPOSITORY,
            revision = candidate.release.tagName,
            path = asset.name,
            name = asset.name,
            sizeBytes = asset.sizeBytes.takeIf { it > 0L },
            sha256 = sha256,
            downloadUrl = asset.downloadUrl,
            provider = ModelRepositoryProvider.HUGGING_FACE
        )
        downloader.download(remote, tempFile, finalFile, onProgress)
        require(finalFile.isFile && sha256File(finalFile) == sha256) { "更新 APK 校验失败。" }
        finalFile
    }
}

internal object ApkUpdateInstaller {
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    fun installIntent(context: Context, apkFile: File): Intent {
        require(apkFile.isFile) { "更新 APK 不存在。" }
        val uri = FileProvider.getUriForFile(
            context.applicationContext,
            context.applicationContext.packageName + ".fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.applicationContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun canInstallUnknownSources(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun validateArchive(
        context: Context,
        apkFile: File,
        current: CurrentAppVersion,
        expectedReleaseVersion: ReleaseVersion
    ): String? {
        if (!apkFile.isFile || apkFile.length() <= 0L) return "更新 APK 文件不存在或为空。"
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
                )
            }
        }.getOrNull() ?: return "Android 无法读取更新 APK。"
        if (packageInfo.packageName != context.packageName) return "更新 APK 包名与 MCA 不一致。"
        if (!archiveVersionMatchesRelease(packageInfo.versionName, expectedReleaseVersion)) {
            return "更新 APK 版本名与 GitHub Release 不一致。"
        }
        @Suppress("DEPRECATION")
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        if (archiveVersionCode <= current.versionCode) return "更新 APK 版本号没有高于当前版本。"
        return null
    }
}

internal fun readCurrentAppVersion(context: Context): CurrentAppVersion {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        packageInfo.versionCode.toLong()
    }
    return CurrentAppVersion(
        versionName = packageInfo.versionName.orEmpty().ifBlank { "0.0.0" },
        versionCode = versionCode
    )
}

internal fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun defaultGitHubHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(true)
    .followSslRedirects(true)
    .retryOnConnectionFailure(true)
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .addNetworkInterceptor { chain ->
        require(isTrustedGitHubReleaseNetworkUrl(chain.request().url.toString())) {
            "GitHub 更新请求重定向到了不受信任的地址。"
        }
        chain.proceed(chain.request())
    }
    .build()

private fun defaultGitHubAssetHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(true)
    .followSslRedirects(true)
    .retryOnConnectionFailure(true)
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    // APK downloads can legitimately outlast several minutes on mobile networks.
    // ResumableDownloader handles retries and cancellation; an overall call timeout
    // would abort an otherwise healthy long-running transfer.
    .callTimeout(0, TimeUnit.SECONDS)
    .addNetworkInterceptor { chain ->
        require(isTrustedGitHubDownloadUrl(chain.request().url.toString())) {
            "更新 APK 重定向到了不受信任的地址。"
        }
        chain.proceed(chain.request())
    }
    .build()

private fun readUtf8Body(body: ResponseBody, maxBytes: Long, tooLargeMessage: String): String {
    val declaredLength = body.contentLength()
    require(declaredLength < 0L || declaredLength <= maxBytes) { tooLargeMessage }
    val bytes = ByteArrayOutputStream()
    body.byteStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size().toLong() + count <= maxBytes) { tooLargeMessage }
            bytes.write(buffer, 0, count)
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}

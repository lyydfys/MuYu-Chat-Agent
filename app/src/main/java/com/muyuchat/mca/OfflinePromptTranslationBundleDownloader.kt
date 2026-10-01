package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.ResumableDownloader
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class OfflineTranslationDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val detail: String? = null
)

/** Downloads the pinned official ModelScope mirror and activates only a verified package. */
internal class OfflinePromptTranslationBundleDownloader(
    context: Context,
    private val downloader: ResumableDownloader = ResumableDownloader()
) {
    private val appContext = context.applicationContext
    private val installGate = OfflinePromptTranslationPackageActivation.gate

    /**
     * A completed download may survive process death just before promotion. Recover it offline
     * after full package verification; partial files never become an installed/runtime bundle.
     */
    suspend fun verifyInstalledOrRecoverCompleteDownload(): OfflinePromptTranslationBundleVerification =
        installGate.withLock {
            withContext(Dispatchers.IO) {
                val activeRoot = File(appContext.filesDir, ACTIVE_DIRECTORY)
                val active = OfflinePromptTranslationBundleVerifier.verify(activeRoot)
                if (active is OfflinePromptTranslationBundleVerification.Verified &&
                    active.bundle.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2
                ) return@withContext active
                val stageRoot = File(appContext.filesDir, STAGING_DIRECTORY)
                val manifest = File(stageRoot, OfflinePromptTranslationContract.MANIFEST_RELATIVE_PATH)
                if (!manifest.isFile) return@withContext active
                val staged = OfflinePromptTranslationBundleVerifier.verify(stageRoot)
                if (staged !is OfflinePromptTranslationBundleVerification.Verified ||
                    staged.bundle.identity.translatorFamily != OfflineTranslatorFamily.HY_MT2
                ) return@withContext active
                currentCoroutineContext().ensureActive()
                OfflinePromptTranslationBundleVerification.Verified(
                    activateOfflinePromptTranslationPackage(
                        stageRoot, activeRoot, OfflinePromptTranslationBundleVerifier::requireVerified
                    )
                )
            }
        }

    suspend fun download(onProgress: (OfflineTranslationDownloadProgress) -> Unit = {}):
        VerifiedOfflinePromptTranslationBundle = installGate.withLock {
        withContext(Dispatchers.IO) {
            val filesDir = appContext.filesDir
            val activeRoot = File(filesDir, ACTIVE_DIRECTORY)
            (OfflinePromptTranslationBundleVerifier.verify(activeRoot) as?
                OfflinePromptTranslationBundleVerification.Verified)?.takeIf { it.bundle.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2 }
                ?.let { return@withContext it.bundle }

            val stageRoot = File(filesDir, STAGING_DIRECTORY)
            val translation = File(stageRoot, OfflinePromptTranslationContract.TRANSLATION_DIRECTORY)
            check(translation.isDirectory || translation.mkdirs()) {
                "无法创建 Hy-MT2 下载暂存目录。"
            }
            val partials = File(filesDir, PARTIALS_DIRECTORY)
            check(partials.isDirectory || partials.mkdirs()) { "无法创建 Hy-MT2 续传目录。" }

            val artifacts = listOf(
                pinnedFile(
                    MIRROR_REPO_ID,
                    MIRROR_REVISION,
                    HyMt2PromptTranslationContract.MODEL_FILE,
                    HyMt2PromptTranslationContract.MODEL_BYTES,
                    HyMt2PromptTranslationContract.MODEL_SHA256,
                    "$MIRROR_RESOLVE_URL/${HyMt2PromptTranslationContract.MODEL_FILE}"
                ),
                pinnedFile(
                    MIRROR_REPO_ID,
                    MIRROR_REVISION,
                    "LICENSE.txt",
                    MODEL_LICENSE_BYTES,
                    HyMt2PromptTranslationContract.MODEL_NOTICE_SHA256,
                    "$MIRROR_RESOLVE_URL/LICENSE.txt"
                )
            )
            val total = artifacts.sumOf { requireNotNull(it.sizeBytes) }
            var completed = 0L
            suspend fun fetch(remote: RemoteModelFile, destination: File) {
                val expected = requireNotNull(remote.sizeBytes)
                val temporary = File(partials, "${destination.name}.part")
                if (!matchesPinnedFile(destination, expected, requireNotNull(remote.sha256))) {
                    if (destination.exists()) check(destination.delete()) {
                        "无法移除损坏的 Hy-MT2 暂存文件。"
                    }
                    downloader.download(remote, temporary, destination) { snapshot ->
                        onProgress(OfflineTranslationDownloadProgress(
                            downloadedBytes = (completed + snapshot.downloadedBytes).coerceAtMost(total),
                            totalBytes = total,
                            detail = snapshot.errorMessage ?: if (snapshot.status == DownloadStatus.DONE) null else remote.name
                        ))
                    }
                }
                completed += expected
                onProgress(OfflineTranslationDownloadProgress(completed, total))
            }

            fetch(artifacts[0], File(translation, HyMt2PromptTranslationContract.MODEL_FILE))
            val modelNotice = File(translation, HyMt2PromptTranslationContract.MODEL_NOTICE)
            fetch(artifacts[1], modelNotice)

            val runtimeNotice = File(translation, HyMt2PromptTranslationContract.RUNTIME_NOTICE)
            if (!matchesPinnedFile(runtimeNotice, RUNTIME_NOTICE_BYTES,
                    OfflinePromptTranslationContract.RUNTIME_NOTICE_ARTIFACT_SHA256)) {
                val upstreamLicense = appContext.assets.open(RUNTIME_LICENSE_ASSET_PATH).use { it.readBytes() }
                val converted = runtimeNoticeFromOfficialLicense(upstreamLicense)
                writeSynced(runtimeNotice, converted)
            }
            val manifest = File(translation, OfflinePromptTranslationContract.MANIFEST_FILE_NAME)
            writeSynced(manifest, hyMt2PinnedManifest().toString().toByteArray(Charsets.UTF_8))

            onProgress(OfflineTranslationDownloadProgress(total, total, "正在校验 Hy-MT2 完整包"))
            OfflinePromptTranslationBundleVerifier.requireVerified(stageRoot)
            currentCoroutineContext().ensureActive()

            activateOfflinePromptTranslationPackage(
                stageRoot, activeRoot, OfflinePromptTranslationBundleVerifier::requireVerified
            )
        }
    }

    private suspend fun matchesPinnedFile(file: File, bytes: Long, sha256: String): Boolean {
        if (!file.isFile || file.length() != bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hexLowercase() == sha256
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    companion object {
        private const val ACTIVE_DIRECTORY = "offline-prompt-translation"
        private const val STAGING_DIRECTORY = "offline-prompt-translation.download"
        private const val PARTIALS_DIRECTORY = "offline-prompt-translation.partials"
        private const val MIRROR_REPO_ID = "Tencent-Hunyuan/Hy-MT2-1.8B-GGUF"
        private const val MIRROR_REVISION = "e97e6af0ec0950385644d7c0a8e5c899071f722b"
        private const val MIRROR_RESOLVE_URL =
            "https://modelscope.cn/models/$MIRROR_REPO_ID/resolve/$MIRROR_REVISION"
        private const val RUNTIME_LICENSE_ASSET_PATH = "offline_translation/llama.cpp-LICENSE.txt"
        private const val MODEL_LICENSE_BYTES = 11_639L
        private const val RUNTIME_NOTICE_BYTES = 1_099L

        private fun pinnedFile(
            repoId: String, revision: String, name: String, sizeBytes: Long,
            sha256: String, url: String
        ) = RemoteModelFile(
            repoId = repoId, revision = revision, path = name, name = name,
            sizeBytes = sizeBytes, sha256 = sha256, downloadUrl = url,
            provider = ModelRepositoryProvider.MODELSCOPE
        )
    }
}

/** Git's Windows checkout turns the pinned llama.cpp LF LICENSE into this CRLF notice. */
internal fun runtimeNoticeFromOfficialLicense(source: ByteArray): ByteArray {
    require(source.size == 1_078 &&
        MessageDigest.getInstance("SHA-256").digest(source).hexLowercase() ==
        "94f29bbed6a22c35b992c5c6ebf0e7c92f13b836b90f36f461c9cf2f0f1d010d") {
        "llama.cpp 官方 LICENSE 与固定 revision 不匹配。"
    }
    require(source.none { it == '\r'.code.toByte() }) { "llama.cpp LICENSE 的换行格式不符合预期。" }
    val converted = String(source, Charsets.UTF_8).replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
    require(converted.size == 1_099 &&
        MessageDigest.getInstance("SHA-256").digest(converted).hexLowercase() ==
        OfflinePromptTranslationContract.RUNTIME_NOTICE_ARTIFACT_SHA256) {
        "llama.cpp NOTICE 与离线翻译包固定校验值不匹配。"
    }
    return converted
}

internal fun hyMt2PinnedManifest(): JSONObject = JSONObject()
    .put("kind", OfflinePromptTranslationContract.MANIFEST_KIND)
    .put("contractVersion", 2)
    .put("translatorFamily", OfflineTranslatorFamily.HY_MT2.name)
    .put("runtimeKind", OfflineTranslationRuntimeKind.LLAMA_CPP.name)
    .put("model", JSONObject()
        .put("sourceId", HyMt2PromptTranslationContract.SOURCE_ID)
        .put("sourceRevision", HyMt2PromptTranslationContract.SOURCE_REVISION)
        .put("license", "Apache-2.0")
        .put("fileName", HyMt2PromptTranslationContract.MODEL_FILE)
        .put("sha256", HyMt2PromptTranslationContract.MODEL_SHA256)
        .put("sizeBytes", HyMt2PromptTranslationContract.MODEL_BYTES)
        .put("architecture", HyMt2PromptTranslationContract.ARCHITECTURE)
        .put("quantization", "Q4_K_M"))
    .put("runtime", JSONObject()
        .put("sourceId", HyMt2PromptTranslationContract.RUNTIME_SOURCE_ID)
        .put("sourceRevision", HyMt2PromptTranslationContract.RUNTIME_REVISION)
        .put("license", "MIT")
        .put("nativeLibraryFileName", HyMt2PromptTranslationContract.NATIVE_LIBRARY))

private fun ByteArray.hexLowercase(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

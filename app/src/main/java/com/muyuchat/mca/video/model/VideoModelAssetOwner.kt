package com.muyuchat.mca.video.model

import com.muyuchat.core.download.DownloadTaskSnapshot
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.ResumableDownloader
import com.muyuchat.core.download.huggingFacePreferredTransferUrl
import com.muyuchat.core.video.contract.VideoModelAssetComponentState
import com.muyuchat.core.video.contract.VideoModelAssetPhase
import com.muyuchat.core.video.contract.VideoModelAssetPort
import com.muyuchat.core.video.contract.VideoModelAssetRole
import com.muyuchat.core.video.contract.VideoModelAssetState
import com.muyuchat.mca.managedRemoteIdentity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Download seam kept injectable so JVM tests can prove role mapping and no re-download. */
fun interface VideoModelAssetDownloader {
    suspend fun download(
        remote: RemoteModelFile,
        tempFile: File,
        finalFile: File,
        onProgress: (DownloadTaskSnapshot) -> Unit,
    ): DownloadTaskSnapshot
}

/**
 * Process-level acquisition owner of the Wan2.2 TI2V-5B model components.
 *
 * The manifest stays the single source of names, sizes, roles and SHA-256 pins. The owner only
 * places bytes at those paths: it reuses the existing [ResumableDownloader] (HTTP range resume +
 * `.part` preservation + final SHA verification), skips components whose local bytes already
 * match the pinned SHA-256, and never invents an alternate filename. Leaving the video page does
 * not cancel this job because the owner lives in the runtime-root scope.
 */
class VideoModelAssetOwner(
    private val root: File,
    private val scope: CoroutineScope,
    private val manifest: VideoModelManifest = Wan22Ti2v5bProfiles.Q4_0_Q4_K_M,
    private val downloader: VideoModelAssetDownloader = VideoModelAssetDownloader { remote, temp, final, onProgress ->
        ResumableDownloader().download(remote, temp, final, onProgress)
    },
    private val availableSpaceBytes: () -> Long? = { usableSpaceOf(root.parentFile ?: root) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VideoModelAssetPort {

    private val totalBytes = manifest.components.sumOf { it.sizeBytes }
    private val state = MutableStateFlow(initialState())
    private var job: Job? = null
    private var cachedFingerprint: String? = null
    private var cachedReady: VideoModelAssetState? = null

    override fun observe(): Flow<VideoModelAssetState> = state.asStateFlow()

    @Synchronized
    override fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch(ioDispatcher) {
            try {
                state.value = inspect(useCache = true)
            } catch (cancelled: CancellationException) {
                state.value = cancelledState()
                throw cancelled
            } catch (failure: Throwable) {
                state.value = failedState(failure)
            } finally {
                synchronized(this@VideoModelAssetOwner) { job = null }
            }
        }
    }

    @Synchronized
    override fun download() {
        if (job?.isActive == true) return
        job = scope.launch(ioDispatcher) {
            try {
                downloadInternal()
            } catch (cancelled: CancellationException) {
                state.value = cancelledState()
                throw cancelled
            } catch (failure: Throwable) {
                state.value = failedState(failure)
            } finally {
                synchronized(this@VideoModelAssetOwner) { job = null }
            }
        }
    }

    @Synchronized
    override fun cancel() {
        job?.takeIf { it.isActive }?.cancel()
    }

    private suspend fun downloadInternal() {
        val initial = inspect(useCache = true)
        if (initial.ready) {
            state.value = initial
            return
        }
        val verifiedPaths = initial.components.filter { it.verified }.mapTo(mutableSetOf()) { it.relativePath }
        val needed = manifest.components.filter { it.relativePath !in verifiedPaths }
        if (needed.isEmpty()) {
            state.value = initial
            return
        }
        // A component that already has a `.part` does not need its full size again: only the
        // missing tail plus the fixed reserve must fit. Counting the whole component would
        // reject a resume that actually fits (e.g. 6 GB component with 5.9 GB already on disk).
        val requiredBytes = needed.sumOf { component ->
            val resumed = existingPartialBytes(component)
            (component.sizeBytes - resumed).coerceAtLeast(0L)
        }
        availableSpaceBytes()?.let { available ->
            if (available < requiredBytes + MIN_FREE_MARGIN_BYTES) {
                state.value = failedState(
                    IllegalStateException(
                        "存储空间不足：还差约 ${formatBytes(requiredBytes + MIN_FREE_MARGIN_BYTES - available)}。",
                    ),
                    code = "DISK_FULL",
                    phase = VideoModelAssetPhase.FAILED,
                )
                return
            }
        }

        val verifiedBytes = manifest.components
            .filter { it.relativePath in verifiedPaths }
            .sumOf { it.sizeBytes }
        var completedBytes = verifiedBytes
        state.value = VideoModelAssetState(
            phase = VideoModelAssetPhase.DOWNLOADING,
            components = componentStates(initial.components, verifiedPaths),
            downloadedBytes = completedBytes,
            totalBytes = totalBytes,
            message = "正在下载 ${needed.size} 个缺失组件；已完成的组件不会重复下载。",
        )
        for (component in needed) {
            currentCoroutineContext().ensureActive()
            val remote = remoteFor(component)
            val finalFile = File(root, component.relativePath)
            finalFile.parentFile?.mkdirs()
            val tempFile = partialFileFor(component, remote)
            state.update {
                it.copy(
                    phase = VideoModelAssetPhase.DOWNLOADING,
                    message = "正在下载 ${component.relativePath}…",
                )
            }
            downloader.download(remote, tempFile, finalFile) { snapshot ->
                val downloaded = (completedBytes + snapshot.downloadedBytes).coerceIn(0L, totalBytes)
                state.update {
                    it.copy(
                        downloadedBytes = downloaded,
                        message = "正在下载 ${component.relativePath}…",
                    )
                }
            }
            currentCoroutineContext().ensureActive()
            if (!verifyFile(finalFile, component)) {
                state.value = failedState(
                    IllegalStateException("${component.relativePath} 下载后 SHA-256 或大小与 pin 不一致。"),
                    code = "MODEL_CHECKSUM_MISMATCH",
                    phase = VideoModelAssetPhase.FAILED,
                )
                return
            }
            completedBytes += component.sizeBytes
            state.update {
                it.copy(
                    downloadedBytes = completedBytes.coerceAtMost(totalBytes),
                    components = it.components.map { componentState ->
                        if (componentState.relativePath == component.relativePath) {
                            componentState.copy(present = true, verified = true, detail = "SHA-256 已确认")
                        } else {
                            componentState
                        }
                    },
                )
            }
        }
        state.update {
            it.copy(
                phase = VideoModelAssetPhase.VERIFYING,
                downloadedBytes = totalBytes,
                message = "下载完成，正在对全部组件重新计算 SHA-256…",
            )
        }
        val verified = inspect(useCache = false)
        state.value = verified
        if (!verified.ready) {
            throw IllegalStateException(verified.message ?: "组件校验未通过")
        }
    }

    private fun inspect(useCache: Boolean): VideoModelAssetState {
        val fingerprint = fingerprint()
        if (useCache && fingerprint == cachedFingerprint) {
            cachedReady?.let { return it }
        }
        state.value = VideoModelAssetState(
            phase = VideoModelAssetPhase.CHECKING,
            components = initialState().components,
            downloadedBytes = 0L,
            totalBytes = totalBytes,
            message = "正在检查本地组件的大小和 SHA-256…",
        )
        // No disk-space argument here: readiness is about bytes on disk; download checks the
        // remaining components against free space before touching the network.
        val outcome = VideoModelAssetVerifier.verify(manifest, root)
        val next = when (outcome) {
            is VideoModelAssetVerification.Ready -> readyState(outcome.components)
            is VideoModelAssetVerification.Problems -> {
                val states = outcome.components.map(::componentState)
                val errorCode = when {
                    states.any { it.detail?.contains("SHA-256 不匹配") == true } -> "MODEL_CHECKSUM_MISMATCH"
                    states.any { !it.present } -> "MODEL_MISSING"
                    states.any { !it.verified } -> "MODEL_CHECKSUM_MISMATCH"
                    else -> "MODEL_MISSING"
                }
                VideoModelAssetState(
                    phase = VideoModelAssetPhase.MISSING,
                    components = states,
                    downloadedBytes = states.filter { it.present }.sumOf { sizeOf(it.relativePath) },
                    totalBytes = totalBytes,
                    message = if (outcome.isIncomplete) {
                        "本地组件不完整或校验未通过；可下载缺失/损坏的组件。"
                    } else {
                        "组件存在但尚未完成 SHA-256 校验。"
                    },
                    errorCode = errorCode,
                )
            }
        }
        if (next.ready) {
            cachedFingerprint = fingerprint
            cachedReady = next
        }
        return next
    }

    private fun readyState(outcomes: List<ComponentVerification>): VideoModelAssetState {
        val byPath = outcomes.associateBy { it.file.relativePath }
        return VideoModelAssetState(
            phase = VideoModelAssetPhase.READY,
            components = manifest.components.map { component ->
                if (component.required) {
                    componentState(component).copy(present = true, verified = true, detail = "SHA-256 已确认")
                } else {
                    val outcome = byPath[component.relativePath] ?: return@map componentState(component)
                    componentState(outcome).copy(
                        detail = when (outcome) {
                            is ComponentVerification.Ok -> "SHA-256 已确认（可选）"
                            is ComponentVerification.SizeOnlyOk -> "仅有大小 pin（可选）"
                            is ComponentVerification.Missing -> "可选组件，未下载"
                            is ComponentVerification.SizeMismatch ->
                                "大小 ${outcome.actualBytes} / ${component.sizeBytes}（可选）"
                            is ComponentVerification.ChecksumMismatch -> "SHA-256 不匹配（可选）"
                        },
                    )
                }
            },
            downloadedBytes = totalBytes,
            totalBytes = totalBytes,
            message = "Wan2.2 TI2V-5B 模型组件已通过大小与 SHA-256 校验。",
        )
    }

    private fun initialState(): VideoModelAssetState = VideoModelAssetState(
        phase = VideoModelAssetPhase.UNKNOWN,
        components = manifest.components.map { componentState(it) },
        totalBytes = totalBytes,
        message = "尚未检查本地模型组件。",
    )

    private fun componentStates(
        previous: List<VideoModelAssetComponentState>,
        verifiedPaths: Set<String>,
    ): List<VideoModelAssetComponentState> = manifest.components.map { component ->
        val old = previous.firstOrNull { it.relativePath == component.relativePath }
        componentState(component).copy(
            present = old?.present ?: File(root, component.relativePath).isFile,
            verified = component.relativePath in verifiedPaths,
            detail = old?.detail,
        )
    }

    private fun componentState(component: VideoModelComponentFile): VideoModelAssetComponentState {
        val file = File(root, component.relativePath)
        return VideoModelAssetComponentState(
            role = when (component.role) {
                VideoModelComponentRole.DIT -> VideoModelAssetRole.DIT
                VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL -> VideoModelAssetRole.TEXT_ENCODER
                VideoModelComponentRole.VAE_WAN22_5B -> VideoModelAssetRole.VAE
                VideoModelComponentRole.LORA_TURBO -> VideoModelAssetRole.LORA
                VideoModelComponentRole.TAE_HEAD -> VideoModelAssetRole.TAE
            },
            label = labelOf(component),
            relativePath = component.relativePath,
            expectedBytes = component.sizeBytes,
            present = file.isFile,
            verified = false,
            detail = if (file.isFile) {
                if (file.length() == component.sizeBytes) "大小符合，等待 SHA-256" else "大小不符"
            } else {
                "缺失"
            },
        )
    }

    private fun componentState(outcome: ComponentVerification): VideoModelAssetComponentState {
        val component = when (outcome) {
            is ComponentVerification.Ok -> outcome.file
            is ComponentVerification.SizeOnlyOk -> outcome.file
            is ComponentVerification.Missing -> outcome.file
            is ComponentVerification.SizeMismatch -> outcome.file
            is ComponentVerification.ChecksumMismatch -> outcome.file
        }
        return componentState(component).copy(
            present = outcome !is ComponentVerification.Missing,
            verified = outcome is ComponentVerification.Ok,
            detail = when (outcome) {
                is ComponentVerification.Ok -> "SHA-256 已确认"
                is ComponentVerification.SizeOnlyOk -> "仅有大小 pin，SHA-256 未校验"
                is ComponentVerification.Missing -> "缺失"
                is ComponentVerification.SizeMismatch -> "大小 ${outcome.actualBytes} / ${component.sizeBytes}"
                is ComponentVerification.ChecksumMismatch -> "SHA-256 不匹配"
            },
        )
    }

    private fun labelOf(component: VideoModelComponentFile): String = when (component.role) {
        VideoModelComponentRole.DIT -> "DiT · ${component.quantization}"
        VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL -> "文本编码器 · ${component.quantization}"
        VideoModelComponentRole.VAE_WAN22_5B -> "VAE · ${component.quantization}"
        VideoModelComponentRole.LORA_TURBO -> "Turbo LoRA · ${component.quantization}"
        VideoModelComponentRole.TAE_HEAD -> "TAE 解码器 · ${component.quantization}（可选）"
    }

    /**
     * The `.part` a resumable download for [component] would keep appending to. The identity is the
     * same one [managedRemoteIdentity] uses for the download task, so the pre-check and the actual
     * download can never disagree about which partial file is being resumed.
     */
    private fun partialFileFor(component: VideoModelComponentFile, remote: RemoteModelFile = remoteFor(component)): File {
        val finalFile = File(root, component.relativePath)
        return File(finalFile.parentFile, ".${managedRemoteIdentity(remote)}.part")
    }

    /** Bytes already on disk for [component] that the downloader would resume instead of re-fetching. */
    private fun existingPartialBytes(component: VideoModelComponentFile): Long {
        val partial = partialFileFor(component)
        if (!partial.isFile) return 0L
        return partial.length().coerceIn(0L, component.sizeBytes)
    }

    private fun verifyFile(file: File, component: VideoModelComponentFile): Boolean {
        if (!file.isFile || file.length() != component.sizeBytes) return false
        val pinned = component.sha256 ?: return false
        return VideoModelAssetVerifier.sha256Of(file).equals(pinned, ignoreCase = true)
    }

    private fun fingerprint(): String = manifest.components.joinToString("|") { component ->
        val file = File(root, component.relativePath)
        "${component.relativePath}:${file.length()}:${file.lastModified()}"
    }

    private fun sizeOf(relativePath: String): Long =
        manifest.components.firstOrNull { it.relativePath == relativePath }?.sizeBytes ?: 0L

    private fun cancelledState(): VideoModelAssetState = state.value.copy(
        phase = VideoModelAssetPhase.CANCELLED,
        message = "已暂停下载；已完成组件与 .part 已保留，再次点击会从断点继续。",
        errorCode = "CANCELLED",
    )

    private fun failedState(
        failure: Throwable,
        code: String = "DOWNLOAD_FAILED",
        phase: VideoModelAssetPhase = VideoModelAssetPhase.FAILED,
    ): VideoModelAssetState = state.value.copy(
        phase = phase,
        message = failure.message ?: failure.javaClass.simpleName,
        errorCode = code,
    )

    /**
     * Maps a pinned source URL onto the generic downloader descriptor. Supported forms:
     * `huggingface.co/<repo>/resolve/<revision>/<path>`,
     * `modelscope.cn/models/<repo>/resolve/<revision>/<path>` and
     * `raw.githubusercontent.com/<repo>/<revision>/<path>`. No filename is guessed.
     *
     * `repoId`, `revision` and `path` always come from the canonical `sourceUrl`, and that URL is
     * what the docs and [VideoModelComponentFile.provenance] record. Only the Hugging Face path
     * goes through the app-wide preferred-transfer host; ModelScope and GitHub URLs are fetched
     * as pinned. The unchanged size + SHA-256 pins still decide whether the bytes are accepted.
     */
    internal fun remoteFor(component: VideoModelComponentFile): RemoteModelFile {
        val source = component.sourceUrl
        val parsed = when {
            source.startsWith("https://huggingface.co/") -> {
                val rest = source.substringAfter("huggingface.co/")
                ParsedVideoSource(
                    provider = ModelRepositoryProvider.HUGGING_FACE,
                    downloadUrl = huggingFacePreferredTransferUrl(source),
                    repoId = rest.substringBefore("/resolve/"),
                    revision = rest.substringAfter("/resolve/", "").substringBefore('/'),
                    path = rest.substringAfter("/resolve/", "").substringAfter('/', ""),
                )
            }

            source.startsWith("https://modelscope.cn/models/") -> {
                val rest = source.substringAfter("/models/")
                ParsedVideoSource(
                    provider = ModelRepositoryProvider.MODELSCOPE,
                    downloadUrl = source,
                    repoId = rest.substringBefore("/resolve/"),
                    revision = rest.substringAfter("/resolve/", "").substringBefore('/'),
                    path = rest.substringAfter("/resolve/", "").substringAfter('/', ""),
                )
            }

            else -> error("Unsupported model source URL: $source")
        }
        require(
            parsed.repoId.isNotBlank() && parsed.revision.isNotBlank() && parsed.path.isNotBlank(),
        ) { "Cannot split model source URL: $source" }
        return RemoteModelFile(
            repoId = parsed.repoId,
            revision = parsed.revision,
            path = parsed.path,
            name = parsed.path.substringAfterLast('/'),
            sizeBytes = component.sizeBytes,
            sha256 = component.sha256,
            license = component.license,
            downloadUrl = parsed.downloadUrl,
            provider = parsed.provider,
            relativePath = component.relativePath,
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
        else -> "%.0f MB".format(bytes / 1048576.0)
    }

    private companion object {
        const val MIN_FREE_MARGIN_BYTES = 256L * 1024L * 1024L
    }
}

/** Parsed `<host>/<repo>/resolve|raw/<revision>/<path>` source forms. */
private data class ParsedVideoSource(
    val provider: ModelRepositoryProvider,
    val downloadUrl: String,
    val repoId: String,
    val revision: String,
    val path: String,
)

/** `usableSpace` is 0 for a path that does not exist yet; walk up to the real filesystem root. */
private fun usableSpaceOf(path: File): Long? {
    var current: File? = path
    while (current != null && !current.exists()) current = current.parentFile
    return (current ?: path).usableSpace.takeIf { it > 0L }
}

package com.muyuchat.feature.dsh.workspace.container

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * The six-step install pipeline of the bundled container runtime.
 *
 * The steps are a fixed sequence and each one has a single responsibility, because every failure
 * has a different user action: a hash mismatch is a broken build, a missing `bin/sh` is a broken
 * archive, and a failed probe is a runtime/SELinux problem. Collapsing them into "install failed"
 * would tell the user nothing.
 *
 * ```
 * 1 校验离线资产      manifest schema + rootfs SHA-256 compared against the packaged bytes
 * 2 准备安装目录      fresh staging directory; stale staging directories of crashed runs removed
 * 3 解包 Ubuntu       tar.gz → staging, with real byte progress
 * 4 校验 rootfs 结构   bin/sh (or a symlink to it) must exist inside the staged tree
 * 5 原子提交          staging → rootfs; the previous tree is kept as rootfs.previous for rollback
 * 6 容器自检探针      the allowlisted `echo ok` argv must exit 0 and print ok
 * ```
 *
 * The installer never starts a process other than the fixed probe from
 * [DshContainerCommand.probeArgv]: no user string reaches a command line here.
 */
object DshContainerInstaller {
    /** Staging/marker names; all of them live under [DshContainerLayout.containerDir]. */
    const val STAGING_DIR_NAME: String = ".staging"
    const val PREVIOUS_ROOTFS_NAME: String = "rootfs.previous"
    const val INSTALL_STATE_NAME: String = ".install-state.json"

    /** The probe is bounded: a container that hangs must not hang the UI. */
    const val PROBE_TIMEOUT_MILLIS: Long = 20_000

    /** One step of the pipeline, in order. [ordinal] starts at 1 so the console can print 1/6. */
    enum class Step(val position: Int, val title: String) {
        VERIFY_ASSETS(1, "校验离线资产"),
        PREPARE_WORKSPACE(2, "准备安装目录"),
        EXTRACT_ROOTFS(3, "解包 Ubuntu rootfs"),
        VERIFY_ROOTFS(4, "校验 rootfs 结构"),
        COMMIT_ROOTFS(5, "原子提交并保留回滚点"),
        PROBE(6, "运行容器自检探针")
    }

    /** Streaming progress; [fraction] is null while the step has no measurable denominator. */
    sealed interface Event {
        data class Started(val step: Step, val attempt: Int) : Event
        data class Progress(val step: Step, val fraction: Float?, val detail: String) : Event
        data class Succeeded(val step: Step, val detail: String) : Event
        data class Failed(val step: Step, val reason: String, val detail: String) : Event
    }

    /** Terminal outcome. Every branch is a distinct user-visible reason. */
    sealed interface Result {
        data class Installed(
            val manifest: DshContainerAssetManifest,
            val stats: DshContainerTarGz.Stats,
            val probeOutput: String,
            val reused: Boolean
        ) : Result

        /** The build does not carry the container assets (staging script was not run). */
        data class AssetsMissing(val detail: String) : Result

        /** The assets exist but do not verify; nothing was extracted. */
        data class Integrity(val detail: String) : Result

        /** A step failed after verification started; [detail] names the step and the reason. */
        data class Failed(val step: Step, val reason: String, val detail: String) : Result
    }

    /**
     * Runs the pipeline.
     *
     * [layout] supplies the directory convention; [onEvent] receives one event per state change so
     * the UI never has to poll. [probe] is the host's bounded process runner, injected so this unit
     * is testable without Android.
     */
    fun install(
        assets: DshContainerAssetSource,
        layout: DshContainerLayout,
        probe: (List<String>, Long) -> DshContainerRun,
        onEvent: (Event) -> Unit = {}
    ): Result {
        val containerDir = File(layout.containerDir)
        val rootfsDir = File(layout.rootfsDir)
        val stagingDir = File(containerDir, STAGING_DIR_NAME)
        val previousDir = File(containerDir, PREVIOUS_ROOTFS_NAME)

        // Step 1: verify the packaged bytes before writing anything.
        onEvent(Event.Started(Step.VERIFY_ASSETS, 1))
        val manifest = readManifest(assets) ?: return finishAssetsMissing(onEvent)
        val archiveBytes = measureArchive(assets, manifest)
            ?: return Result.AssetsMissing("离线资产缺少 ${manifest.rootfsFile}").also {
                onEvent(Event.Failed(Step.VERIFY_ASSETS, "缺少 rootfs 归档", it.toString()))
            }
        if (!manifest.matchesArchive(archiveBytes.bytes, archiveBytes.sha256)) {
            val detail = "rootfs 归档与清单不一致：清单=${manifest.rootfsSha256}/${manifest.rootfsBytes} " +
                "实际=${archiveBytes.sha256}/${archiveBytes.bytes}"
            onEvent(Event.Failed(Step.VERIFY_ASSETS, "资产校验失败", detail))
            return Result.Integrity(detail)
        }
        val prootPath = layout.executableProotCandidates.firstOrNull { File(it).isFile }
            ?: return Result.AssetsMissing(
                "APK 里没有 ${DshContainerLayout.PACKAGED_PROOT_LIBRARY_NAME}；容器资产未随包"
            ).also {
                onEvent(
                    Event.Failed(
                        Step.VERIFY_ASSETS,
                        "缺少 proot",
                        "打包脚本没有把 proot 放进 nativeLibraryDir，容器无法启动"
                    )
                )
            }
        onEvent(
            Event.Succeeded(
                Step.VERIFY_ASSETS,
                "${manifest.distribution} · ${manifest.rootfsBytes} 字节 · SHA-256 匹配"
            )
        )

        // A previous successful install of the same archive is reused instead of unpacked again.
        val state = InstallState.read(File(containerDir, INSTALL_STATE_NAME))
        if (state != null &&
            state.archiveSha256.equals(manifest.rootfsSha256, ignoreCase = true) &&
            File(rootfsDir, DshContainerLayout.ROOTFS_SHELL_RELATIVE).exists()
        ) {
            onEvent(Event.Progress(Step.PREPARE_WORKSPACE, null, "已安装同一份 rootfs，跳过解包"))
            onEvent(Event.Succeeded(Step.PREPARE_WORKSPACE, "复用已有 rootfs"))
            onEvent(Event.Succeeded(Step.EXTRACT_ROOTFS, "复用已有 rootfs"))
            onEvent(Event.Succeeded(Step.VERIFY_ROOTFS, "复用已有 rootfs"))
            onEvent(Event.Succeeded(Step.COMMIT_ROOTFS, "复用已有 rootfs"))
            return probeAndFinish(manifest, DshContainerTarGz.Stats(0, 0, 0, true), layout, probe, onEvent, reused = true)
        }

        // Step 2: a fresh staging directory; leftovers of a crashed run are removed first.
        onEvent(Event.Started(Step.PREPARE_WORKSPACE, 1))
        if (!containerDir.isDirectory && !containerDir.mkdirs()) {
            return failStep(Step.PREPARE_WORKSPACE, "无法创建容器目录", containerDir.absolutePath, onEvent)
        }
        if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
            return failStep(Step.PREPARE_WORKSPACE, "无法清理上次的临时目录", stagingDir.absolutePath, onEvent)
        }
        if (!stagingDir.mkdirs()) {
            return failStep(Step.PREPARE_WORKSPACE, "无法创建临时解包目录", stagingDir.absolutePath, onEvent)
        }
        onEvent(Event.Succeeded(Step.PREPARE_WORKSPACE, stagingDir.absolutePath))

        // Step 3: unpack. The stream is reopened on purpose: step 1 consumed it for hashing.
        onEvent(Event.Started(Step.EXTRACT_ROOTFS, 1))
        val stats = extractTo(assets, stagingDir) { fraction ->
            onEvent(
                Event.Progress(
                    Step.EXTRACT_ROOTFS,
                    fraction,
                    "已解包 ${(fraction * manifest.rootfsBytes).toLong() / (1024 * 1024)} / " +
                        "${manifest.rootfsBytes / (1024 * 1024)} MB"
                )
            )
        } ?: return failStep(
            Step.EXTRACT_ROOTFS,
            "解包失败",
            "rootfs 归档无法解包（tar.gz 损坏或格式不受支持）",
            onEvent
        )
        onEvent(Event.Succeeded(Step.EXTRACT_ROOTFS, "${stats.entries} 个条目 / ${stats.bytes} 字节"))

        // Step 4: the staged tree must prove itself before it replaces anything.
        onEvent(Event.Started(Step.VERIFY_ROOTFS, 1))
        val stagedShell = File(stagingDir, DshContainerLayout.ROOTFS_SHELL_RELATIVE)
        if (!stagedShell.exists()) {
            return failStep(
                Step.VERIFY_ROOTFS,
                "rootfs 缺少 bin/sh",
                "解包后的 ${stagedShell.absolutePath} 不存在；归档不是完整 rootfs",
                onEvent
            )
        }
        onEvent(Event.Succeeded(Step.VERIFY_ROOTFS, "bin/sh 存在"))

        // Step 5: swap. The old tree is kept: a broken new rootfs must be recoverable without a
        // re-download, and deleting the only copy would make the failure permanent.
        onEvent(Event.Started(Step.COMMIT_ROOTFS, 1))
        if (previousDir.exists() && !previousDir.deleteRecursively()) {
            return failStep(Step.COMMIT_ROOTFS, "无法清理旧的回滚点", previousDir.absolutePath, onEvent)
        }
        if (rootfsDir.exists() && !rootfsDir.renameTo(previousDir)) {
            return failStep(Step.COMMIT_ROOTFS, "无法移走旧 rootfs", rootfsDir.absolutePath, onEvent)
        }
        if (!stagingDir.renameTo(rootfsDir)) {
            // Put the old tree back so the container lane keeps working with the previous rootfs.
            previousDir.renameTo(rootfsDir)
            return failStep(Step.COMMIT_ROOTFS, "无法提交新 rootfs", stagingDir.absolutePath, onEvent)
        }
        InstallState.write(
            File(containerDir, INSTALL_STATE_NAME),
            InstallState(
                schema = DshContainerManifest.SUPPORTED_SCHEMA,
                archiveSha256 = manifest.rootfsSha256,
                distribution = manifest.distribution,
                installedAtEpochMillis = System.currentTimeMillis()
            )
        )
        onEvent(Event.Succeeded(Step.COMMIT_ROOTFS, "已提交；旧 rootfs 保留为 $PREVIOUS_ROOTFS_NAME"))

        return probeAndFinish(manifest, stats, layout, probe, onEvent, reused = false)
    }

    /** Step 6 and the shared tail of the reuse path. */
    private fun probeAndFinish(
        manifest: DshContainerAssetManifest,
        stats: DshContainerTarGz.Stats,
        layout: DshContainerLayout,
        probe: (List<String>, Long) -> DshContainerRun,
        onEvent: (Event) -> Unit,
        reused: Boolean
    ): Result {
        onEvent(Event.Started(Step.PROBE, 1))
        val facts = dshContainerFactsFor(layout)
        val proot = facts.prootPath
        if (proot == null || !facts.prootExecutable) {
            val detail = "proot 不在 nativeLibraryDir 或缺失，无法执行探针"
            onEvent(Event.Failed(Step.PROBE, "proot 不可执行", detail))
            return Result.Failed(Step.PROBE, "proot 不可执行", detail)
        }
        val spec = DshContainerSpec(rootfsPath = layout.rootfsDir, prootPath = proot)
        val argv = when (val built = DshContainerCommand.probeArgv(spec)) {
            is DshContainerCommandResult.Ready -> built.argv
            is DshContainerCommandResult.Rejected -> {
                onEvent(Event.Failed(Step.PROBE, "探针 argv 被拒绝", built.detail))
                return Result.Failed(Step.PROBE, "探针 argv 被拒绝", built.detail)
            }
        }
        val run = probe(argv, PROBE_TIMEOUT_MILLIS)
        // DshContainerRun deliberately has no `succeeded` flag of its own: success is exactly
        // "exited 0, did not time out, and the runner reported no failure".
        val succeeded = run.exitCode == 0 && !run.timedOut && run.failure == null
        if (!succeeded) {
            val detail = run.failure
                ?: if (run.timedOut) "探针超时" else "探针退出码 ${run.exitCode}，输出「${run.output.take(200)}」"
            onEvent(Event.Failed(Step.PROBE, "容器自检未通过", detail))
            return Result.Failed(Step.PROBE, "容器自检未通过", detail)
        }
        onEvent(Event.Succeeded(Step.PROBE, "输出「${run.output.trim().take(120)}」"))
        return Result.Installed(
            manifest = manifest,
            stats = stats,
            probeOutput = run.output.trim(),
            reused = reused
        )
    }

    private data class ArchiveFingerprint(val bytes: Long, val sha256: String)

    /** Streams the archive once to fingerprint it; nothing is written during this step. */
    private fun measureArchive(
        assets: DshContainerAssetSource,
        manifest: DshContainerAssetManifest
    ): ArchiveFingerprint? {
        val stream: InputStream = assets.openRootfsArchive() ?: return null
        return stream.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                total += read
            }
            ArchiveFingerprint(total, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    private fun readManifest(assets: DshContainerAssetSource): DshContainerAssetManifest? {
        val stream = assets.openManifest() ?: return null
        val text = stream.use { input ->
            val bytes = input.readNBytes(DshContainerManifest.MAX_MANIFEST_BYTES + 1)
            check(bytes.size <= DshContainerManifest.MAX_MANIFEST_BYTES) {
                "容器资产清单超过 ${DshContainerManifest.MAX_MANIFEST_BYTES} 字节"
            }
            String(bytes, Charsets.UTF_8)
        }
        return DshContainerManifest.parse(text).getOrThrow()
    }

    private fun extractTo(
        assets: DshContainerAssetSource,
        destination: File,
        onFraction: (Float) -> Unit
    ): DshContainerTarGz.Stats? {
        val stream = assets.openRootfsArchive() ?: return null
        var lastReported = 0f
        val result = stream.use { input ->
            DshContainerTarGz.extract(input, destination) { bytes ->
                // The uncompressed total is unknown before the archive ends; the manifest's
                // compressed size is the best available denominator, so the bar is capped at 0.99
                // until the extractor returns and the pipeline can report completion.
                val fraction = (bytes / 300f / 1024f / 1024f).coerceIn(0f, 0.99f)
                if (fraction - lastReported >= 0.02f) {
                    lastReported = fraction
                    onFraction(fraction)
                }
            }
        }
        return result.getOrElse { null }
    }

    private fun finishAssetsMissing(onEvent: (Event) -> Unit): Result {
        val detail = "APK 没有打包容器资产：先运行 scripts/dsh/stage-container.ps1 再构建"
        onEvent(Event.Failed(Step.VERIFY_ASSETS, "缺少容器资产", detail))
        return Result.AssetsMissing(detail)
    }

    private fun failStep(
        step: Step,
        reason: String,
        detail: String,
        onEvent: (Event) -> Unit
    ): Result {
        onEvent(Event.Failed(step, reason, detail))
        return Result.Failed(step, reason, detail)
    }

    /** Marker written after a successful commit; a missing marker means "verify again". */
    data class InstallState(
        val schema: String,
        val archiveSha256: String,
        val distribution: String,
        val installedAtEpochMillis: Long
    ) {
        companion object {
            fun read(file: File): InstallState? = runCatching {
                if (!file.isFile) return null
                val json = org.json.JSONObject(file.readText())
                InstallState(
                    schema = json.optString("schema"),
                    archiveSha256 = json.optString("archiveSha256"),
                    distribution = json.optString("distribution"),
                    installedAtEpochMillis = json.optLong("installedAtEpochMillis")
                ).takeIf { it.schema == DshContainerManifest.SUPPORTED_SCHEMA && it.archiveSha256.isNotBlank() }
            }.getOrNull()

            fun write(file: File, state: InstallState) {
                val json = org.json.JSONObject()
                    .put("schema", state.schema)
                    .put("archiveSha256", state.archiveSha256)
                    .put("distribution", state.distribution)
                    .put("installedAtEpochMillis", state.installedAtEpochMillis)
                val temp = File(file.parentFile, file.name + "." + UUID.randomUUID().toString().take(8) + ".tmp")
                temp.writeText(json.toString())
                if (!temp.renameTo(file)) {
                    file.delete()
                    temp.renameTo(file)
                }
            }
        }
    }
}

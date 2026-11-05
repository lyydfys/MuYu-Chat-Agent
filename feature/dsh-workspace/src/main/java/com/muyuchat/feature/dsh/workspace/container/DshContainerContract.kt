package com.muyuchat.feature.dsh.workspace.container

import java.io.File

/**
 * Container runtime contract of the DSH workspace (proot + user-supplied rootfs).
 *
 * Why this file is pure Kotlin: the container lane's only decision is "can we build the exact
 * argv `proot … -- /bin/sh -c <fixed command>` from the paths we found?", and that decision has to
 * be provable on the JVM — no device, no proot binary, no rootfs. Everything here is a value or a
 * pure function; the Android-facing [DshContainerHost] only gathers filesystem facts and executes
 * the single allowlisted probe.
 *
 * What this version deliberately does NOT do:
 *  - it does not ship or download a `proot` binary or a rootfs (third-party binaries / hundreds of
 *    megabytes; see `docs/architecture/DSH_CONTAINER.md`);
 *  - it does not accept a user-supplied command: the only executable argv is the constant probe;
 *  - it does not treat a SAF `content://` tree as a rootfs path: proot's `-r` needs a real
 *    filesystem path, so a granted tree has to be imported first (reported as a missing rootfs
 *    with the import instruction, never as "supported").
 */

/**
 * The directory convention the user fills in, resolved once per process.
 *
 * Paths are string joins on purpose (like `DshCommandClosure`): the JVM tests assert the exact
 * argv a device would receive and never depend on the host OS separator.
 */
data class DshContainerLayout(
    /** App-private container directory, e.g. `/data/user/0/<pkg>/files/dsh/container`. */
    val containerDir: String,
    /** Rootfs directory: `<containerDir>/rootfs`; it must contain `bin/sh`. */
    val rootfsDir: String,
    /**
     * proot candidates that Android may exec(): APK `nativeLibraryDir` (extracted native lib).
     * Only this family is reported as executable; see [nonExecutableProotCandidates].
     */
    val executableProotCandidates: List<String>,
    /**
     * proot candidates in app-writable storage. They are data only: at targetSdk 36 SELinux
     * (`exec_no_trans`) refuses to exec() an ELF from app data, so finding one here is
     * `NOT_PERMITTED`, not `SUPPORTED`.
     */
    val nonExecutableProotCandidates: List<String>,
    /** Read-only bind of the app's own files (the container can see payload/config outputs). */
    val appFilesMount: String,
    /**
     * App-private directory the installer fills with proot's SONAME dependencies.
     *
     * APK packaging only accepts `*.so`, but proot's ELF records `libtalloc.so.2` as DT_NEEDED, so
     * the installer copies the packaged `libtalloc.so` here under the SONAME and the runner puts
     * this directory on `LD_LIBRARY_PATH`.
     */
    val libraryDir: String = ""
) {
    companion object {
        const val CONTAINER_DIR_NAME: String = "dsh/container"
        const val ROOTFS_DIR_NAME: String = "rootfs"
        const val LIBRARY_DIR_NAME: String = "lib"

        /** Packaged file name of proot's talloc dependency (packaging requires a `.so` suffix). */
        const val PACKAGED_TALLOC_LIBRARY_NAME: String = "libtalloc.so"

        /** The name the loader is actually asked for by proot's DT_NEEDED entry. */
        const val TALLOC_SONAME: String = "libtalloc.so.2"
        const val PROOT_FILE_NAME: String = "proot"
        const val PACKAGED_PROOT_LIBRARY_NAME: String = "libproot.so"
        /** Rootfs-relative path proot must find; `bin/sh` is the smallest honest rootfs proof. */
        const val ROOTFS_SHELL_RELATIVE: String = "bin/sh"

        /** Layout for the real app: [filesDir] = `Context.filesDir`, [nativeLibraryDir] = APK libs. */
        fun forApp(filesDir: String, nativeLibraryDir: String): DshContainerLayout {
            val containerDir = join(filesDir, CONTAINER_DIR_NAME)
            return DshContainerLayout(
                containerDir = containerDir,
                rootfsDir = join(containerDir, ROOTFS_DIR_NAME),
                executableProotCandidates = listOf(join(nativeLibraryDir, PACKAGED_PROOT_LIBRARY_NAME)),
                nonExecutableProotCandidates = listOf(join(containerDir, PROOT_FILE_NAME)),
                appFilesMount = filesDir,
                libraryDir = join(containerDir, LIBRARY_DIR_NAME)
            )
        }

        private fun join(base: String, name: String): String = base.trimEnd('/') + "/" + name
    }
}

/** One `-b` bind: [source] on the host, [target] inside the container (same path when null). */
data class DshContainerMount(
    val source: String,
    val target: String? = null
) {
    /** `proot -b` argument body: `source` or `source:target`. */
    val bindSpec: String get() = if (target == null) source else "$source:$target"
}

/**
 * What the container should run. Only the fixed [DshContainerCommand.PROBE_ENTRY_COMMAND] is
 * accepted by [DshContainerCommandPolicy]; the field exists so the argv translation is testable
 * and so a future allowlisted entry point has a place to live.
 */
data class DshContainerSpec(
    val rootfsPath: String,
    val prootPath: String,
    val entryCommand: String = DshContainerCommand.PROBE_ENTRY_COMMAND,
    /** Extra binds; `/dev`, `/proc` and `/sys` are always added first by [DshContainerCommand]. */
    val mounts: List<DshContainerMount> = emptyList()
)

/** Why an argv could not be built. Codes are engineering tokens; the detail is the human reason. */
enum class DshContainerRejectionReason(val code: String) {
    ROOTFS_NOT_ABSOLUTE("rootfs_not_absolute"),
    ROOTFS_TRAVERSAL("rootfs_traversal"),
    ROOTFS_NOT_A_REAL_PATH("rootfs_not_a_real_path"),
    PROOT_NOT_ABSOLUTE("proot_not_absolute"),
    PROOT_TRAVERSAL("proot_traversal"),
    ENTRY_COMMAND_BLANK("entry_command_blank"),
    ENTRY_COMMAND_NOT_CONTROL_FREE("entry_command_not_control_free"),
    MOUNT_NOT_ABSOLUTE("mount_not_absolute"),
    MOUNT_TRAVERSAL("mount_traversal"),
    MOUNT_TARGET_NOT_ABSOLUTE("mount_target_not_absolute")
}

/** Result of translating a spec into `proot` argv. There is no "half valid" state. */
sealed interface DshContainerCommandResult {
    data class Ready(val argv: List<String>) : DshContainerCommandResult
    data class Rejected(val reason: DshContainerRejectionReason, val detail: String) :
        DshContainerCommandResult
}

/**
 * Pure `proot` argv translation.
 *
 * Fixed shape (asserted by `DshContainerContractTest`):
 * `proot -r <rootfs> -0 -w /root [-b <bind>]… -- /bin/sh -c <entryCommand>`
 *
 * `-0` is proot's faked root inside its user namespace: the app uid stays the real uid, nothing
 * here elevates privileges. Every argument is its own argv element, so a path containing spaces,
 * quotes or CJK text is never re-parsed by a shell.
 */
object DshContainerCommand {
    /** The one executable entry command of this version. Constant: no user input reaches it. */
    const val PROBE_ENTRY_COMMAND: String = "echo ok"

    const val CONTAINER_WORKING_DIRECTORY: String = "/root"
    const val CONTAINER_SHELL: String = "/bin/sh"
    const val FAKE_ROOT_FLAG: String = "-0"

    /** The three kernel filesystems every shell needs; always bound first. */
    val BASE_MOUNTS: List<DshContainerMount> = listOf(
        DshContainerMount("/dev"),
        DshContainerMount("/proc"),
        DshContainerMount("/sys")
    )

    /**
     * Mount merge rule: base mounts first (their original position), then the spec's own binds,
     * de-duplicated by source where the **last** value wins — an explicit `/dev:/dev` bind
     * therefore overrides the default bind spec while `/dev` stays bound before `/proc`/`/sys`.
     */
    fun mountsFor(extra: List<DshContainerMount>): List<DshContainerMount> {
        val merged = LinkedHashMap<String, DshContainerMount>()
        (BASE_MOUNTS + extra).forEach { mount -> merged[mount.source] = mount }
        return merged.values.toList()
    }

    fun argv(spec: DshContainerSpec): DshContainerCommandResult {
        // A URI is rejected by its own reason before the absolute-path check: "content://…" is not
        // just relative, it is not a filesystem path at all, and the user needs that difference.
        if (!isPlainPath(spec.rootfsPath) || spec.rootfsPath.contains("://")) {
            return DshContainerCommandResult.Rejected(
                DshContainerRejectionReason.ROOTFS_NOT_A_REAL_PATH,
                "rootfs 必须是真实文件系统路径；SAF 的 content:// URI 不能作为 proot -r 的根"
            )
        }
        validateAbsolute(
            spec.rootfsPath,
            DshContainerRejectionReason.ROOTFS_NOT_ABSOLUTE,
            DshContainerRejectionReason.ROOTFS_TRAVERSAL,
            "rootfs"
        )?.let { return it }
        validateAbsolute(
            spec.prootPath,
            DshContainerRejectionReason.PROOT_NOT_ABSOLUTE,
            DshContainerRejectionReason.PROOT_TRAVERSAL,
            "proot"
        )?.let { return it }
        if (spec.entryCommand.isBlank()) {
            return DshContainerCommandResult.Rejected(
                DshContainerRejectionReason.ENTRY_COMMAND_BLANK,
                "容器入口命令为空"
            )
        }
        if (spec.entryCommand.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            return DshContainerCommandResult.Rejected(
                DshContainerRejectionReason.ENTRY_COMMAND_NOT_CONTROL_FREE,
                "容器入口命令不能包含 NUL 或换行"
            )
        }
        val mounts = mountsFor(spec.mounts)
        for (mount in mounts) {
            validateAbsolute(
                mount.source,
                DshContainerRejectionReason.MOUNT_NOT_ABSOLUTE,
                DshContainerRejectionReason.MOUNT_TRAVERSAL,
                "挂载源 ${mount.source}"
            )?.let { return it }
            val target = mount.target ?: continue
            validateAbsolute(
                target,
                DshContainerRejectionReason.MOUNT_TARGET_NOT_ABSOLUTE,
                DshContainerRejectionReason.MOUNT_TRAVERSAL,
                "挂载目标 $target"
            )?.let { return it }
        }
        val argv = buildList {
            add(spec.prootPath)
            add("-r")
            add(spec.rootfsPath)
            add(FAKE_ROOT_FLAG)
            add("-w")
            add(CONTAINER_WORKING_DIRECTORY)
            mounts.forEach { mount ->
                add("-b")
                add(mount.bindSpec)
            }
            add("--")
            add(CONTAINER_SHELL)
            add("-c")
            add(spec.entryCommand)
        }
        return DshContainerCommandResult.Ready(argv)
    }

    /** The argv of the only executable command in this version. */
    fun probeArgv(spec: DshContainerSpec): DshContainerCommandResult =
        argv(spec.copy(entryCommand = PROBE_ENTRY_COMMAND))

    private fun validateAbsolute(
        path: String,
        notAbsolute: DshContainerRejectionReason,
        traversal: DshContainerRejectionReason,
        label: String
    ): DshContainerCommandResult.Rejected? {
        if (!path.startsWith("/")) {
            return DshContainerCommandResult.Rejected(notAbsolute, "$label 必须是绝对路径，实际为「$path」")
        }
        if (path.split('/').any { it == ".." }) {
            return DshContainerCommandResult.Rejected(traversal, "$label 不能包含 .. 段：$path")
        }
        return null
    }

    /** Real path only: no URI scheme, no NUL/newline, bounded length. */
    private fun isPlainPath(path: String): Boolean =
        path.length in 1..4096 &&
            !path.contains("://") &&
            path.none { it == '\u0000' || it == '\n' || it == '\r' }
}

/**
 * The container lane's command gate.
 *
 * The device diagnostic whitelist (`DshDeviceCommandPolicy`) refuses `sh` and every shell wrapper
 * by design, so the container probe cannot borrow it; instead this lane allowlists exactly one
 * argv — the constant probe rendered from the same spec. A spec whose entry command differs
 * produces argv that this gate rejects, so no user-supplied string can ever reach the runner.
 */
object DshContainerCommandPolicy {
    fun allowsProbe(argv: List<String>, spec: DshContainerSpec): Boolean =
        when (val expected = DshContainerCommand.probeArgv(spec)) {
            is DshContainerCommandResult.Ready -> expected.argv == argv
            is DshContainerCommandResult.Rejected -> false
        }
}

/** The four probe states the console renders; each carries a human reason, never just a code. */
enum class DshContainerProbeState {
    /** proot is executable *and* a rootfs with bin/sh exists. The probe may run. */
    SUPPORTED,

    /** No proot at all: the binary is a third-party asset this APK does not ship. */
    MISSING_PROOT,

    /** proot exists but cannot be exec()ed from where it was placed. */
    NOT_PERMITTED,

    /** proot is usable but no rootfs with bin/sh was found (SAF alone is not a path). */
    MISSING_ROOTFS
}

/** One probe verdict: state + short reason + actionable detail for the console. */
data class DshContainerProbe(
    val state: DshContainerProbeState,
    val reason: String,
    val detail: String
)

/** Filesystem facts gathered by the host; all of them are cheap reads, no execution. */
data class DshContainerFacts(
    /** First existing proot candidate (executable family first), or null when none exists. */
    val prootPath: String? = null,
    /** True only when [prootPath] came from the APK `nativeLibraryDir` candidate family. */
    val prootExecutable: Boolean = false,
    /** Rootfs directory when one was found (container dir only; a SAF tree is not a path). */
    val rootfsPath: String? = null,
    val rootfsHasShell: Boolean = false,
    /** Persisted SAF tree grants, reported as an import hint only; they never enable the state. */
    val safTreeUris: List<String> = emptyList(),
    /** Termux installed on the device: informational only, never a dependency and never a gate. */
    val termuxInstalled: Boolean = false
)

/**
 * Pure state mapping. Order is "first missing dependency wins": proot before rootfs, because a
 * rootfs without proot cannot run either and the user's next action is to provide proot.
 *
 * [DshContainerFacts.termuxInstalled] and [DshContainerFacts.safTreeUris] only change the *detail
 * text*; the state is identical with and without them (asserted by the contract test, per the
 * project rule that device/app matching must never gate a feature).
 */
fun dshContainerProbeFor(facts: DshContainerFacts): DshContainerProbe {
    val safHint = if (facts.safTreeUris.isEmpty()) {
        ""
    } else {
        "检测到 ${facts.safTreeUris.size} 个持久 SAF 目录授权；content:// 不是真实路径，需先导入到应用私有目录。"
    }
    val termuxHint = if (facts.termuxInstalled) {
        "设备上已安装 Termux（仅作为参考，本应用不依赖它、也不调用它）。"
    } else {
        ""
    }
    return when {
        facts.prootPath == null -> DshContainerProbe(
            state = DshContainerProbeState.MISSING_PROOT,
            reason = "缺少 proot 可执行文件",
            detail = "本次构建的 APK 里没有 nativeLibraryDir/libproot.so：容器运行时随发布附带、" +
                "不进 Git，由 scripts/dsh/stage-container.ps1 在构建前摆放" +
                "（Android 10+ 只允许执行 nativeLibraryDir 里的 ELF）。" +
                "rootfs 布局：files/dsh/container/rootfs。$safHint$termuxHint"
        )

        !facts.prootExecutable -> DshContainerProbe(
            state = DshContainerProbeState.NOT_PERMITTED,
            reason = "proot 存在但不能执行",
            detail = "找到 ${facts.prootPath}，但它不在 nativeLibraryDir；Android 10+ 的 SELinux " +
                "exec_no_trans 策略会拒绝执行应用可写目录里的 ELF。请把 proot 打包成 " +
                "nativeLibraryDir/libproot.so，或放到系统可执行目录（需要 root）。$termuxHint"
        )

        facts.rootfsPath == null || !facts.rootfsHasShell -> {
            val rootfsPath = facts.rootfsPath
            DshContainerProbe(
                state = DshContainerProbeState.MISSING_ROOTFS,
                reason = if (rootfsPath == null) "缺少 rootfs" else "rootfs 里没有可用的 bin/sh",
                detail = if (rootfsPath == null) {
                    "在控制台点「安装内置 Ubuntu」解包随包的 rootfs，或手工把含 bin/sh 的 rootfs " +
                        "解压到 files/dsh/container/rootfs（目录布局：rootfs/bin/sh）。$safHint$termuxHint"
                } else {
                    "$rootfsPath/bin/sh 不存在或不可执行，proot 会立即失败；请确认 rootfs " +
                        "解压完整（Ubuntu base rootfs 自带 /bin/sh）。$termuxHint"
                }
            )
        }

        else -> DshContainerProbe(
            state = DshContainerProbeState.SUPPORTED,
            reason = "proot 与 rootfs 已就绪",
            detail = "proot=${facts.prootPath}，rootfs=${facts.rootfsPath}；可以运行固定探针命令" +
                "「${DshContainerCommand.PROBE_ENTRY_COMMAND}」证明容器能启动（不接受任何用户输入）。"
        )
    }
}

/**
 * Gathers [DshContainerFacts] from the real filesystem.
 *
 * `prootExecutable` is a *location* fact, not a `File.canExecute()` read: on Android the
 * permission bits of an app-data file can say "executable" while execve() is refused by SELinux,
 * so only the `nativeLibraryDir` candidate family is reported as executable. Whether it really
 * runs is proven by the probe, never assumed here.
 */
fun dshContainerFactsFor(
    layout: DshContainerLayout,
    safTreeUris: List<String> = emptyList(),
    termuxInstalled: Boolean = false
): DshContainerFacts {
    val executable = layout.executableProotCandidates.firstOrNull(::fileExists)
    val nonExecutable = layout.nonExecutableProotCandidates.firstOrNull(::fileExists)
    val proot = executable ?: nonExecutable
    val rootfs = layout.rootfsDir.takeIf(::directoryExists)
    val shell = rootfs != null &&
        fileExists(rootfs.trimEnd('/') + "/" + DshContainerLayout.ROOTFS_SHELL_RELATIVE)
    return DshContainerFacts(
        prootPath = proot,
        prootExecutable = executable != null,
        rootfsPath = rootfs,
        rootfsHasShell = shell,
        safTreeUris = safTreeUris,
        termuxInstalled = termuxInstalled
    )
}

private fun fileExists(path: String): Boolean =
    runCatching { File(path).isFile }.getOrDefault(false)

private fun directoryExists(path: String): Boolean =
    runCatching { File(path).isDirectory }.getOrDefault(false)

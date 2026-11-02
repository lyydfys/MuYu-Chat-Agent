package com.muyuchat.core.dsh.runtime.process

import java.io.File

/**
 * The real DSH command closure on targetSdk 36, as data.
 *
 * Facts this model encodes (from the pinned payload lock and the R01 exec probe recorded in
 * DshAndroidRuntimeAssembly):
 *  - the payload ships real ELF executables for node, ripgrep, curl, git, git-remote-http(s) and
 *    the Python launcher; they are packaged as APK native libraries and are executable from
 *    `nativeLibraryDir`;
 *  - `bin/{npm,npx,pnpm,pip,bash,python}` are `/system/bin/sh` wrapper *scripts* in the writable
 *    app home. At targetSdk 36 the app cannot exec its own writable files, so those names are
 *    NOT startable by name unless an executable dispatcher is packaged (libdshcmd.so) and the
 *    payload name is a symlink to it;
 *  - `bin/bash` is not GNU bash under any arrangement: the wrapper only forwards to
 *    `/system/bin/sh` after removing bash-only long options. [DshCommandClosure] therefore reports
 *    `gnuBash = false` and never upgrades that to a "bash works" claim.
 *
 * The model is pure data so the whole matrix can be unit tested without Android; [DshCommandProbe]
 * is the thin `File`-based scanner that fills it in production.
 */
enum class DshCommandRole {
    NODE,
    NPM,
    NPX,
    PNPM,
    PIP,
    PYTHON,
    RIPGREP,
    CURL,
    GIT,
    GIT_REMOTE_HTTP,
    GIT_REMOTE_HTTPS,
    SHELL,
    BASH;

    /** The name the official kernel would use with `execvp(name, ...)`. */
    val pathName: String
        get() = when (this) {
            NODE -> "node"
            NPM -> "npm"
            NPX -> "npx"
            PNPM -> "pnpm"
            PIP -> "pip"
            PYTHON -> "python3"
            RIPGREP -> "rg"
            CURL -> "curl"
            GIT -> "git"
            GIT_REMOTE_HTTP -> "git-remote-http"
            GIT_REMOTE_HTTPS -> "git-remote-https"
            SHELL -> "sh"
            BASH -> "bash"
        }
}

/** Why a command cannot be started. Every value is a concrete missing condition. */
enum class DshCommandBlockReason {
    /** The APK does not package the required native library for this ABI. */
    MISSING_NATIVE_LIBRARY,
    /** The verified payload does not contain the script/module entry point. */
    MISSING_PAYLOAD_ENTRY,
    /** Only a writable-home script exists; executing it is denied at targetSdk 36. */
    SCRIPT_NOT_EXECUTABLE,
    /** The name needs the packaged exec dispatcher (npm/npx/pnpm/pip/bash); it is absent. */
    NEEDS_NATIVE_DISPATCHER,
    /** No usable platform shell. */
    NO_PLATFORM_SHELL
}

/** How MCA can start a role with an exact argv (never a shell string). */
sealed interface DshCommandLaunch {
    /** Direct exec of an executable file (a native-library ELF or a symlink to one). */
    data class NativeExecutable(val executable: String, val argv0: String) : DshCommandLaunch

    /**
     * Exec through the system dynamic linker. Used only when a real `--version` probe showed that
     * direct exec of the nativeLibraryDir ELF is denied on this device; it is not a default.
     */
    data class LinkerExecutable(val linker: String, val executable: String) : DshCommandLaunch

    /** Exec the bundling Node interpreter with a JavaScript entry point. */
    data class NodeScript(
        val node: String,
        val script: String,
        val scriptPrefixArgs: List<String> = emptyList(),
        /** Empty for a direct exec; `[linker]` when the exec strategy needed LINKER64. */
        val execPrefix: List<String> = emptyList()
    ) : DshCommandLaunch

    /** Exec the bundled Python launcher with a module (`-m`). */
    data class PythonModule(val python: String, val module: String) : DshCommandLaunch

    /** Exec the platform shell (optionally a script file) 鈥?an explicit sh compatibility layer. */
    data class PlatformShell(
        val shell: String,
        val script: String? = null,
        val stripBashOnlyOptions: Boolean = false
    ) : DshCommandLaunch

    /** Exec the packaged dispatcher through its payload-name symlink (argv[0] selects the role). */
    data class DispatcherLink(val linkPath: String, val name: String) : DshCommandLaunch

    data class Blocked(val reason: DshCommandBlockReason, val detail: String) : DshCommandLaunch
}

/** What the kernel sees on PATH for one name, and why. */
enum class DshPathStatus {
    /** PATH name is an executable symlink to a nativeLibraryDir ELF. */
    EXECUTABLE_LINK,
    /** PATH name is a symlink to the packaged exec dispatcher. */
    DISPATCHER_LINK,
    /** The payload script exists but cannot be exec()d from the writable home. */
    SCRIPT_NOT_EXECUTABLE,
    /** Nothing on PATH resolves this name. */
    MISSING
}

/** One resolved command: launch recipe + PATH fact + honest detail. */
data class DshCommandAvailability(
    val role: DshCommandRole,
    val launch: DshCommandLaunch,
    val pathStatus: DshPathStatus,
    val pathTarget: String?,
    val detail: String
) {
    val executable: Boolean get() = launch !is DshCommandLaunch.Blocked

    /** One bounded, log-safe diagnostic line. */
    fun report(): String = buildString {
        append(role.name).append('=')
        when (val recipe = launch) {
            is DshCommandLaunch.NativeExecutable -> append("DIRECT")
            is DshCommandLaunch.LinkerExecutable -> append("LINKER64")
            is DshCommandLaunch.NodeScript -> append("NODE_SCRIPT")
            is DshCommandLaunch.PythonModule -> append("PYTHON_MODULE")
            is DshCommandLaunch.PlatformShell -> append("PLATFORM_SHELL")
            is DshCommandLaunch.DispatcherLink -> append("DISPATCHER")
            is DshCommandLaunch.Blocked -> append("BLOCKED(").append(recipe.reason).append(')')
        }
        append(" path=").append(pathStatus)
        if (detail.isNotBlank()) append(" (").append(detail).append(')')
    }
}

/** Filesystem facts the closure needs. Filled by [DshCommandProbe]; can be built in tests. */
data class DshCommandClosureModel(
    val payloadDir: String,
    val nativeLibraryDir: String,
    /** Payload-relative paths of regular files that really exist. */
    val payloadFiles: Set<String>,
    /** Payload-relative paths of directories that really exist (e.g. the Python stdlib root). */
    val payloadDirectories: Set<String> = emptySet(),
    /** File names present in `nativeLibraryDir`. */
    val nativeLibraries: Set<String>,
    /** Absolute platform shells that exist. */
    val platformShells: Set<String> = setOf(DshCommandClosure.PLATFORM_SHELL),
    /** True when the Node exec strategy needed `/system/bin/linker64` instead of direct exec. */
    val nodeNeedsLinker: Boolean = false
) {
    fun payloadFile(relative: String): Boolean = relative in payloadFiles
    fun payloadDirectory(relative: String): Boolean = relative in payloadDirectories
    fun nativeLibrary(name: String): Boolean = name in nativeLibraries
}

/**
 * Pure resolver of the closure.
 *
 * It never assumes a name works: each role is either startable with an exact argv or a typed
 * [DshCommandLaunch.Blocked] with the missing condition, and PATH truth is reported separately.
 */
object DshCommandClosure {
    const val DISPATCHER_LIBRARY = "libdshcmd.so"
    const val PTY_LIBRARY = "libdshpty.so"
    const val PLATFORM_SHELL = "/system/bin/sh"
    const val PLATFORM_LINKER = "/system/bin/linker64"

    object Payload {
        const val NPM_CLI = "npm/bin/npm-cli.js"
        const val NPX_CLI = "npm/bin/npx-cli.js"
        const val PNPM_CLI = "pnpm/bin/pnpm.cjs"
        const val PYTHON_LAUNCHER = "python/bin/python3.14"
        const val PYTHON_STDLIB = "python/lib/python3.14"
        const val GIT = "bin/git"
        const val RG = "runtime/bin/rg"
        const val CURL = "runtime/bin/curl"
        const val BASH = "bin/bash"
    }

    object Native {
        const val NODE = "libdshnode.so"
        const val RIPGREP = "libdshrg.so"
        const val CURL = "libdshcurl.so"
        const val GIT = "libdshgit.so"
        const val GIT_REMOTE_HTTP = "libdshgitremotehttp.so"
        const val GIT_REMOTE_HTTPS = "libdshgitremotehttps.so"
        const val PYTHON = "libdshpython.so"
    }

    /** PNPM needs copy imports: app-private/FUSE storage rejects hard links. */
    const val PNPM_IMPORT_ARG = "--config.package-import-method=copy"

    fun resolve(model: DshCommandClosureModel): Map<DshCommandRole, DshCommandAvailability> {
        // Android paths always use '/'. String joins (not File) keep the resolver platform-neutral
        // so the JVM tests assert the exact argv a device would receive.
        val dispatcher = join(model.nativeLibraryDir, DISPATCHER_LIBRARY)
        val binDir = join(model.payloadDir, "bin")
        val hasDispatcher = model.nativeLibrary(DISPATCHER_LIBRARY)
        val node = join(model.nativeLibraryDir, Native.NODE)
        val python = join(model.nativeLibraryDir, Native.PYTHON)
        val shell = model.platformShells.firstOrNull { it.isNotBlank() }

        fun blocked(
            role: DshCommandRole,
            reason: DshCommandBlockReason,
            detail: String,
            pathStatus: DshPathStatus = DshPathStatus.MISSING,
            pathTarget: String? = null
        ) = role to DshCommandAvailability(
            role = role,
            launch = DshCommandLaunch.Blocked(reason, detail),
            pathStatus = pathStatus,
            pathTarget = pathTarget,
            detail = detail
        )

        fun nativeExec(
            role: DshCommandRole,
            library: String,
            payloadRelative: String,
            argv0: String = role.pathName
        ): Pair<DshCommandRole, DshCommandAvailability> {
            val target = join(model.nativeLibraryDir, library)
            return if (model.nativeLibrary(library)) {
                role to DshCommandAvailability(
                    role = role,
                    launch = if (model.nodeNeedsLinker) {
                        DshCommandLaunch.LinkerExecutable(PLATFORM_LINKER, target)
                    } else {
                        DshCommandLaunch.NativeExecutable(target, argv0)
                    },
                    pathStatus = DshPathStatus.EXECUTABLE_LINK,
                    pathTarget = target,
                    detail = if (model.nodeNeedsLinker) {
                        "packaged $library; direct exec was denied, uses $PLATFORM_LINKER"
                    } else {
                        "packaged $library"
                    }
                )
            } else {
                blocked(
                    role,
                    if (model.payloadFile(payloadRelative)) {
                        DshCommandBlockReason.MISSING_NATIVE_LIBRARY
                    } else {
                        DshCommandBlockReason.MISSING_PAYLOAD_ENTRY
                    },
                    "no $library in nativeLibraryDir; payload entry $payloadRelative " +
                        if (model.payloadFile(payloadRelative)) "exists but cannot be exec()d" else "is absent",
                    if (model.payloadFile(payloadRelative)) {
                        DshPathStatus.SCRIPT_NOT_EXECUTABLE
                    } else {
                        DshPathStatus.MISSING
                    }
                )
            }
        }

        fun nodeModule(
            role: DshCommandRole,
            payloadRelative: String,
            name: String,
            prefixArgs: List<String> = emptyList()
        ): Pair<DshCommandRole, DshCommandAvailability> {
            val script = join(model.payloadDir, payloadRelative)
            val hasNode = model.nativeLibrary(Native.NODE)
            val launch = when {
                !hasNode -> DshCommandLaunch.Blocked(
                    DshCommandBlockReason.MISSING_NATIVE_LIBRARY,
                    "the bundled Node executable (${Native.NODE}) is not packaged"
                )
                !model.payloadFile(payloadRelative) -> DshCommandLaunch.Blocked(
                    DshCommandBlockReason.MISSING_PAYLOAD_ENTRY,
                    "the verified payload has no $payloadRelative"
                )
                else -> DshCommandLaunch.NodeScript(
                    node = node,
                    script = script,
                    scriptPrefixArgs = prefixArgs,
                    execPrefix = if (model.nodeNeedsLinker) listOf(PLATFORM_LINKER) else emptyList()
                )
            }
            val path = when {
                hasDispatcher -> DshPathStatus.DISPATCHER_LINK
                model.payloadFile(payloadRelative) -> DshPathStatus.SCRIPT_NOT_EXECUTABLE
                else -> DshPathStatus.MISSING
            }
            val detail = when {
                path == DshPathStatus.DISPATCHER_LINK -> "PATH via $DISPATCHER_LIBRARY"
                path == DshPathStatus.SCRIPT_NOT_EXECUTABLE ->
                    "PATH name '$name' is a writable-home script; exec is denied at targetSdk 36"
                else -> "not on PATH"
            }
            return role to DshCommandAvailability(
                role = role,
                launch = launch,
                pathStatus = path,
                pathTarget = when (path) {
                    DshPathStatus.DISPATCHER_LINK -> dispatcher
                    else -> null
                },
                detail = detail
            )
        }

        val resolved = linkedMapOf<DshCommandRole, DshCommandAvailability>()
        resolved += nativeExec(DshCommandRole.NODE, Native.NODE, "runtime/bin/node")
        resolved += nodeModule(DshCommandRole.NPM, Payload.NPM_CLI, "npm")
        resolved += nodeModule(DshCommandRole.NPX, Payload.NPX_CLI, "npx")
        resolved += nodeModule(DshCommandRole.PNPM, Payload.PNPM_CLI, "pnpm", listOf(PNPM_IMPORT_ARG))
        resolved += nativeExec(DshCommandRole.RIPGREP, Native.RIPGREP, Payload.RG)
        resolved += nativeExec(DshCommandRole.CURL, Native.CURL, Payload.CURL)
        resolved += nativeExec(DshCommandRole.GIT, Native.GIT, Payload.GIT)
        resolved += nativeExec(
            DshCommandRole.GIT_REMOTE_HTTP,
            Native.GIT_REMOTE_HTTP,
            "git/libexec/git-core/git-remote-http"
        )
        resolved += nativeExec(
            DshCommandRole.GIT_REMOTE_HTTPS,
            Native.GIT_REMOTE_HTTPS,
            "git/libexec/git-core/git-remote-https"
        )

        val hasStdlib = model.payloadDirectory(Payload.PYTHON_STDLIB) ||
            model.payloadFile(Payload.PYTHON_STDLIB)
        resolved += if (model.nativeLibrary(Native.PYTHON)) {
            DshCommandRole.PYTHON to DshCommandAvailability(
                role = DshCommandRole.PYTHON,
                launch = DshCommandLaunch.NativeExecutable(python, "python3"),
                pathStatus = DshPathStatus.EXECUTABLE_LINK,
                pathTarget = python,
                detail = if (hasStdlib) "packaged ${Native.PYTHON}" else "packaged ${Native.PYTHON}; stdlib unverified"
            )
        } else {
            blocked(
                DshCommandRole.PYTHON,
                DshCommandBlockReason.MISSING_NATIVE_LIBRARY,
                "no ${Native.PYTHON} in nativeLibraryDir"
            )
        }
        resolved += if (model.nativeLibrary(Native.PYTHON) && hasStdlib) {
            DshCommandRole.PIP to DshCommandAvailability(
                role = DshCommandRole.PIP,
                launch = DshCommandLaunch.PythonModule(python, "pip"),
                pathStatus = if (hasDispatcher) DshPathStatus.DISPATCHER_LINK else DshPathStatus.SCRIPT_NOT_EXECUTABLE,
                pathTarget = if (hasDispatcher) dispatcher else null,
                detail = if (hasDispatcher) {
                    "PATH via $DISPATCHER_LIBRARY; direct form is '<python> -m pip'"
                } else {
                    "startable as '<python> -m pip'; the payload 'pip' shell wrapper is not exec()able"
                }
            )
        } else {
            blocked(
                DshCommandRole.PIP,
                if (!model.nativeLibrary(Native.PYTHON)) {
                    DshCommandBlockReason.MISSING_NATIVE_LIBRARY
                } else {
                    DshCommandBlockReason.MISSING_PAYLOAD_ENTRY
                },
                "pip needs the bundled Python launcher and its standard library"
            )
        }

        resolved += if (shell != null) {
            DshCommandRole.SHELL to DshCommandAvailability(
                role = DshCommandRole.SHELL,
                launch = DshCommandLaunch.PlatformShell(shell),
                pathStatus = DshPathStatus.EXECUTABLE_LINK,
                pathTarget = shell,
                detail = "platform shell (mksh/toybox); not GNU bash"
            )
        } else {
            blocked(
                DshCommandRole.SHELL,
                DshCommandBlockReason.NO_PLATFORM_SHELL,
                "no platform shell is present at $PLATFORM_SHELL"
            )
        }

        // bash: the packaged dispatcher reproduces the wrapper's flag stripping, but only when the
        // payload name it is reached by really exists; the dispatcher alone is not a launch target.
        // Without that name the honest fallback is the platform shell with the same stripping done
        // here, and the launch report keeps gnuBash=false.
        val bashLink = join(binDir, "bash")
        val bashLinkPresent = hasDispatcher && model.payloadFile(Payload.BASH)
        val bashLaunch = when {
            bashLinkPresent -> DshCommandLaunch.DispatcherLink(bashLink, "bash")
            shell != null -> DshCommandLaunch.PlatformShell(shell, stripBashOnlyOptions = true)
            else -> DshCommandLaunch.Blocked(
                DshCommandBlockReason.NO_PLATFORM_SHELL,
                "no platform shell and no $DISPATCHER_LIBRARY; bash cannot be provided"
            )
        }
        resolved += DshCommandRole.BASH to DshCommandAvailability(
            role = DshCommandRole.BASH,
            launch = bashLaunch,
            pathStatus = when {
                bashLinkPresent -> DshPathStatus.DISPATCHER_LINK
                shell != null -> DshPathStatus.EXECUTABLE_LINK
                else -> DshPathStatus.MISSING
            },
            pathTarget = when {
                bashLinkPresent -> dispatcher
                else -> shell
            },
            detail = when {
                bashLinkPresent ->
                    "sh compatibility layer via $DISPATCHER_LIBRARY (bash-only options stripped); gnuBash=false"
                shell != null && hasDispatcher ->
                    "payload bin/bash launcher is absent; the bash role falls back to the platform shell " +
                        "with bash-only options stripped (gnuBash=false)"
                shell != null ->
                    "sh compat only; payload bin/bash is a writable-home script (gnuBash=false)"
                else -> "no launcher for the bash role"
            }
        )
        return resolved
    }

    private fun join(base: String, name: String): String = base.trimEnd('/') + "/" + name

    /** Exact argv for a launch. Arguments are forwarded verbatim, never joined into a shell line. */
    fun argv(launch: DshCommandLaunch, arguments: List<String> = emptyList()): List<String> {
        require(arguments.none { it.contains('\u0000') }) { "dsh: argv must not contain NUL" }
        return when (launch) {
            is DshCommandLaunch.NativeExecutable -> listOf(launch.executable) + arguments
            is DshCommandLaunch.LinkerExecutable ->
                listOf(launch.linker, launch.executable) + arguments
            is DshCommandLaunch.NodeScript ->
                launch.execPrefix + listOf(launch.node, launch.script) +
                    launch.scriptPrefixArgs + arguments
            is DshCommandLaunch.PythonModule -> listOf(launch.python, "-m", launch.module) + arguments
            is DshCommandLaunch.PlatformShell -> buildList {
                add(launch.shell)
                if (launch.script != null) add(launch.script)
                addAll(if (launch.stripBashOnlyOptions) stripBashOnlyOptions(arguments) else arguments)
            }
            is DshCommandLaunch.DispatcherLink -> listOf(launch.linkPath) + arguments
            is DshCommandLaunch.Blocked ->
                throw IllegalStateException("dsh: cannot build argv for a blocked command: ${launch.reason}")
        }
    }

    /** The bash-only leading options the payload wrapper removes before delegating to sh. */
    fun stripBashOnlyOptions(arguments: List<String>): List<String> {
        val bashOnly = listOf(
            "--noprofile", "--norc", "--login", "--posix", "--restricted", "-l"
        )
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            val strip = argument in bashOnly ||
                argument.startsWith("--noprofile=") ||
                argument.startsWith("--rcfile=")
            if (!strip) break
            index++
        }
        return arguments.drop(index)
    }
}

/**
 * Thin production scanner: turns the real app-private payload tree and nativeLibraryDir into a
 * [DshCommandClosureModel]. It performs no execution; commands are proven on a device, not here.
 */
object DshCommandProbe {
    fun scan(
        payloadDir: File,
        nativeLibraryDir: File,
        payloadEntries: Collection<String> = CANDIDATE_ENTRIES,
        platformShells: Collection<String> = listOf(DshCommandClosure.PLATFORM_SHELL),
        nodeNeedsLinker: Boolean = false
    ): DshCommandClosureModel = DshCommandClosureModel(
        payloadDir = payloadDir.absolutePath,
        nativeLibraryDir = nativeLibraryDir.absolutePath,
        payloadFiles = payloadEntries
            .filter { relative -> runCatching { File(payloadDir, relative).isFile }.getOrDefault(false) }
            .toSet(),
        payloadDirectories = payloadEntries
            .filter { relative -> runCatching { File(payloadDir, relative).isDirectory }.getOrDefault(false) }
            .toSet(),
        nativeLibraries = nativeLibraryDir.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.toSet()
            .orEmpty(),
        platformShells = platformShells
            .filter { relative -> runCatching { File(relative).isFile }.getOrDefault(false) }
            .toSet(),
        nodeNeedsLinker = nodeNeedsLinker
    )

    /** The payload paths the closure cares about; scanned as a bounded set, never a full walk. */
    val CANDIDATE_ENTRIES: List<String> = listOf(
        "npm/bin/npm-cli.js",
        "npm/bin/npx-cli.js",
        "pnpm/bin/pnpm.cjs",
        "python/bin/python3.14",
        "python/lib/python3.14",
        "bin/git",
        "bin/bash",
        "bin/npm",
        "bin/pnpm",
        "runtime/bin/node",
        "runtime/bin/rg",
        "runtime/bin/curl",
        "git/libexec/git-core/git-remote-http",
        "git/libexec/git-core/git-remote-https"
    )
}


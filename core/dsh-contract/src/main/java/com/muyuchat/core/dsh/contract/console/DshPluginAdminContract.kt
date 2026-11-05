package com.muyuchat.core.dsh.contract.console

/**
 * In-app plugin administration seam.
 *
 * The official DSH plugin manager owns `profiles/web/package.json` and `pnpm-workspace.yaml` after
 * the first seed. MCA therefore never edits those files by hand: an install/remove is performed by
 * the packaged `pnpm` CLI running *inside the DSH home*, exactly as the official manager would, so
 * the manifest, the workspace file and `node_modules` stay consistent with each other.
 *
 * The console feature only talks to [DshPluginAdminPort]. Everything privileged — resolving the
 * CLI from the payload, choosing the working directory, bounding the child process and sanitising
 * its output — stays with the implementation in `:core:dsh-runtime`.
 */

/**
 * The package-name shape the official manager accepts.
 *
 * Bounded and deliberately narrow: a name reaches a child process argv, so anything that could be
 * read as an option, a path, a shell metacharacter or a URL is refused before it gets there.
 */
object DshPluginName {

    /** npm's own limit for the whole `[@scope/]name` string. */
    const val MAX_LENGTH: Int = 214

    /** `name` or `@scope/name`, lowercase, no leading dot/dash, no whitespace, no separators. */
    private val PATTERN = Regex("^(?:@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*$")

    /**
     * Optional version/dist-tag suffix (`1.2.3`, `^1`, `latest`, `>=1 <2`); never a URL or a path.
     *
     * A space is allowed because the argv is handed to the child process directly (no shell), so a
     * compound semver range is a single argument, not two commands.
     */
    private val VERSION = Regex("^[A-Za-z0-9.^~<>=*+ -]{1,64}$")

    /** Null when [name] may be passed to the CLI; otherwise the human reason it may not. */
    fun rejectReason(name: String): String? = when {
        name.isBlank() -> "插件名不能为空"
        name.length > MAX_LENGTH -> "插件名超过 $MAX_LENGTH 个字符"
        name.startsWith("-") -> "插件名不能以 - 开头（会被当成命令行选项）"
        name.startsWith(".") || name.startsWith("/") -> "插件名不能以 . 或 / 开头"
        !PATTERN.matches(name) -> "插件名只能是 name 或 @scope/name（小写字母、数字、. _ ~ -）"
        else -> null
    }

    fun isAcceptable(name: String): Boolean = rejectReason(name) == null

    /** Null when [version] is acceptable; also null when it is empty (means "latest"). */
    fun versionRejectReason(version: String?): String? = when {
        version.isNullOrBlank() -> null
        version.length > 64 -> "版本号超过 64 个字符"
        version.startsWith("-") -> "版本号不能以 - 开头"
        !VERSION.matches(version) -> "版本号只能包含字母、数字与 . ^ ~ < > = * + -"
        else -> null
    }
}

/** What the console asked the owner to do. */
enum class DshPluginOperation { INSTALL, REMOVE }

/** Terminal outcome of one administration request. Every branch is user-visible. */
sealed interface DshPluginAdminResult {
    /** The CLI finished successfully; the new state is visible on the next inventory read. */
    data class Succeeded(val operation: DshPluginOperation, val plugin: String) : DshPluginAdminResult

    /** Refused before anything ran (bad name, no runtime, feature off). */
    data class Rejected(val reason: String) : DshPluginAdminResult

    /**
     * The CLI ran and failed. [reasonCode] is a stable token; [detail] is a bounded, secret-free
     * excerpt of the CLI output so the user can act on it.
     */
    data class Failed(
        val operation: DshPluginOperation,
        val plugin: String,
        val reasonCode: String,
        val detail: String = ""
    ) : DshPluginAdminResult
}

/**
 * Owner side of the seam.
 *
 * `install`/`remove` are the only mutating calls, and both are expected to be idempotent from the
 * caller's point of view: a second identical call after success reports [DshPluginAdminResult.Failed]
 * with `already_present` / `not_installed` rather than pretending it changed state.
 */
interface DshPluginAdminPort {

    /** True only when the packaged CLI can actually run here (payload present, home prepared). */
    suspend fun isAvailable(): Boolean

    /**
     * Installs [name] (optionally [version]) into the official profile.
     *
     * The DSH runtime must not be mid-turn: the implementation refuses while a generation is live
     * rather than mutating `node_modules` under a running loader.
     */
    suspend fun install(name: String, version: String? = null): DshPluginAdminResult

    suspend fun remove(name: String): DshPluginAdminResult
}

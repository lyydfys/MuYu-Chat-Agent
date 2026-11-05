package com.muyuchat.core.dsh.runtime.plugin

import com.muyuchat.core.dsh.contract.console.DshPluginAdminPort
import com.muyuchat.core.dsh.contract.console.DshPluginAdminResult
import com.muyuchat.core.dsh.contract.console.DshPluginName
import com.muyuchat.core.dsh.contract.console.DshPluginOperation
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.process.DefaultDshProcessRunner
import com.muyuchat.core.dsh.runtime.process.DshCommandClosure
import com.muyuchat.core.dsh.runtime.process.DshCommandLaunch
import com.muyuchat.core.dsh.runtime.process.DshCommandProbe
import com.muyuchat.core.dsh.runtime.process.DshCommandRole
import com.muyuchat.core.dsh.runtime.process.DshNodeCommand
import com.muyuchat.core.dsh.runtime.process.DshProcessRunner
import java.io.File

/**
 * In-app plugin administration, performed by the **packaged** pnpm CLI inside the DSH home.
 *
 * Why it drives pnpm instead of editing `package.json`:
 *  - the official plugin manager owns `profiles/web/package.json` and `pnpm-workspace.yaml` after the
 *    first seed; hand-editing them would desynchronise the workspace and `node_modules`;
 *  - pnpm is shipped with the DSH payload (`pnpm/bin/pnpm.cjs`) and runs as `node <cjs>`, so it
 *    needs no exec() of a writable ELF (the targetSdk 36 EACCES condition);
 *  - the closure resolver already knows how to launch it on this device, including the linker
 *    fallback, so this class never invents an argv shape of its own.
 *
 * Guards, in order: name/version shape → runtime quiescent → profile present → CLI resolvable.
 * Every refusal is a [DshPluginAdminResult.Rejected] *before* a child process exists.
 */
class DshPluginAdmin(
    private val config: DshRuntimeConfig,
    private val nativeLibraryDir: File,
    /** True while a DSH generation is live; mutating `node_modules` under a running loader is refused. */
    private val runtimeIsLive: () -> Boolean,
    private val nodeNeedsLinker: Boolean = false,
    private val runner: DshProcessRunner = DefaultDshProcessRunner(),
    private val timeoutMillis: Long = 180_000L
) : DshPluginAdminPort {

    override suspend fun isAvailable(): Boolean =
        runCatching { resolvePnpm() != null && profileDir().isDirectory }.getOrDefault(false)

    override suspend fun install(name: String, version: String?): DshPluginAdminResult {
        DshPluginName.rejectReason(name)?.let { return DshPluginAdminResult.Rejected(it) }
        DshPluginName.versionRejectReason(version)?.let { return DshPluginAdminResult.Rejected(it) }
        val spec = if (version.isNullOrBlank()) name else "$name@$version"
        return mutate(DshPluginOperation.INSTALL, name, listOf("add", "-w", spec))
    }

    override suspend fun remove(name: String): DshPluginAdminResult {
        DshPluginName.rejectReason(name)?.let { return DshPluginAdminResult.Rejected(it) }
        return mutate(DshPluginOperation.REMOVE, name, listOf("remove", "-w", name))
    }

    private fun mutate(
        operation: DshPluginOperation,
        plugin: String,
        arguments: List<String>
    ): DshPluginAdminResult {
        if (runtimeIsLive()) {
            // pnpm rewrites node_modules and the lockfile; doing that under a running loader is how
            // a session ends up importing a half-written tree.
            return DshPluginAdminResult.Rejected("DSH 运行时正在运行，请先在控制台停止运行时再改插件。")
        }
        val profile = profileDir()
        if (!profile.isDirectory) {
            return DshPluginAdminResult.Rejected("DSH 配置尚未初始化（缺少 profiles/web），先启动一次运行时。")
        }
        val pnpm = resolvePnpm()
            ?: return DshPluginAdminResult.Rejected("本机解析不到 pnpm CLI（payload 未安装完整）。")

        val argv = runCatching { DshCommandClosure.argv(pnpm, arguments) }.getOrElse { error ->
            return DshPluginAdminResult.Failed(
                operation = operation,
                plugin = plugin,
                reasonCode = "argv_unavailable",
                detail = error.message.orEmpty().take(MAX_DETAIL)
            )
        }
        val result = runCatching {
            runner.run(argv, environment(), profile, timeoutMillis)
        }.getOrElse { error ->
            return DshPluginAdminResult.Failed(
                operation = operation,
                plugin = plugin,
                reasonCode = "spawn_failed",
                detail = (error.javaClass.simpleName + ": " + error.message.orEmpty()).take(MAX_DETAIL)
            )
        }
        if (result.timedOut) {
            return DshPluginAdminResult.Failed(operation, plugin, "timeout")
        }
        if (result.exitCode == 0) {
            return DshPluginAdminResult.Succeeded(operation, plugin)
        }
        return DshPluginAdminResult.Failed(
            operation = operation,
            plugin = plugin,
            reasonCode = classify(result.stderr.ifBlank { result.stdout }),
            detail = sanitize(result.stderr.ifBlank { result.stdout })
        )
    }

    /** The `pnpm` command closure of this install, or null when it cannot be resolved. */
    private fun resolvePnpm(): DshCommandLaunch? {
        val model = DshCommandProbe.scan(
            payloadDir = config.payloadDir,
            nativeLibraryDir = nativeLibraryDir,
            nodeNeedsLinker = nodeNeedsLinker
        )
        val availability = DshCommandClosure.resolve(model)[DshCommandRole.PNPM] ?: return null
        if (!availability.executable) return null
        return availability.launch.takeUnless { it is DshCommandLaunch.Blocked }
    }

    private fun profileDir(): File = File(config.homeDir, PROFILE_RELATIVE)

    /** Same child environment the official loader sees, so pnpm resolves the same store/registry. */
    private fun environment(): Map<String, String> = DshNodeCommand.environment(
        homeDir = config.homeDir,
        configDir = config.configDir,
        workspaceDir = config.workspaceDir,
        tmpDir = config.tmpDir,
        payloadDir = config.payloadDir,
        runtimeLibraryDir = File(config.payloadDir, config.runtimeLibraryRelativePath),
        payloadBinDir = File(config.payloadDir, config.payloadBinRelativePath),
        // Not a runtime generation: 0 keeps the ownership token out of an administrative child.
        generation = 0L
    )

    /**
     * Stable token for the console. pnpm's own error codes are already stable, so the mapper only
     * groups them; it never echoes the raw line (that becomes [DshPluginAdminResult.Failed.detail]).
     */
    private fun classify(output: String): String = when {
        output.contains("ERR_PNPM_NO_MATCHING_VERSION", ignoreCase = true) -> "no_matching_version"
        output.contains("ERR_PNPM_SPEC_NOT_SUPPORTED", ignoreCase = true) -> "unsupported_spec"
        output.contains("ERR_PNPM_FETCH_404", ignoreCase = true) || output.contains("E404") -> "not_found"
        output.contains("ERR_PNPM_ADDING_TO_ROOT", ignoreCase = true) -> "workspace_root_refused"
        output.contains("ERR_PNPM_NOT_FOUND", ignoreCase = true) -> "not_installed"
        output.contains("ENOTFOUND", ignoreCase = true) ||
            output.contains("ECONNREFUSED", ignoreCase = true) -> "network_unavailable"
        output.contains("EACCES", ignoreCase = true) -> "permission_denied"
        else -> "cli_failed"
    }

    /**
     * Bounded, secret-free excerpt: absolute paths under the app data root are collapsed to `<data>`
     * so a failure cannot leak the install layout, and only the last few lines are kept.
     */
    private fun sanitize(output: String): String {
        val dataRoot = runCatching { config.dataRoot.absolutePath }.getOrNull()
        val collapsed = if (dataRoot.isNullOrBlank()) {
            output
        } else {
            output.replace(dataRoot, "<data>")
        }
        return collapsed.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(MAX_DETAIL_LINES)
            .joinToString(" | ")
            .take(MAX_DETAIL)
    }

    companion object {
        /** Where the official plugin manager keeps the profile this class mutates. */
        const val PROFILE_RELATIVE: String = "profiles/web"

        const val MAX_DETAIL: Int = 400
        const val MAX_DETAIL_LINES: Int = 4
    }
}

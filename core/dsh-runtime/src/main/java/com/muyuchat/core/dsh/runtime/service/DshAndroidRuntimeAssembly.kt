package com.muyuchat.core.dsh.runtime.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.capability.DshCapabilityBridgeEndpoint
import com.muyuchat.core.dsh.runtime.DshRuntimeOwner
import com.muyuchat.core.dsh.runtime.config.DshGatewayConfigSeeder
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.config.DshSeedOutcome
import com.muyuchat.core.dsh.runtime.log.DshLogRedactor
import com.muyuchat.core.dsh.runtime.payload.DshPayloadInstaller
import com.muyuchat.core.dsh.runtime.payload.DshPayloadProvisioner
import com.muyuchat.core.dsh.runtime.payload.DshPayloadSource
import com.muyuchat.core.dsh.runtime.process.AndroidDshBundledSkillSource
import com.muyuchat.core.dsh.runtime.process.AndroidDshNodeHost
import com.muyuchat.core.dsh.runtime.process.DefaultDshProcessRunner
import com.muyuchat.core.dsh.runtime.process.DshBundledSkillInstallResult
import com.muyuchat.core.dsh.runtime.process.DshBundledSkillInstaller
import com.muyuchat.core.dsh.runtime.process.DshBundledSkillRoot
import com.muyuchat.core.dsh.runtime.process.DshNodeExecStrategy
import com.muyuchat.core.dsh.runtime.process.DshNodeExecutable
import com.muyuchat.core.dsh.runtime.process.DshNodeHost
import com.muyuchat.core.dsh.runtime.process.DshProcessTree
import com.muyuchat.core.dsh.runtime.readiness.DshReadinessGate
import com.muyuchat.core.dsh.runtime.readiness.LoopbackHttpProbe
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * In-memory holder for the MCA model gateway endpoint of the current host generation.
 *
 * The endpoint arrives from the app-side [DshRuntimeServiceClient] over Binder (never an Intent,
 * preference or log). It is only valid for the generation the client asked to start; the owner
 * re-reads it on every start/restart, so a new generation gets a new bearer token.
 */
internal class DshGatewayEndpointHolder {
    @Volatile
    private var endpoint: DshGatewayEndpoint? = null

    fun set(value: DshGatewayEndpoint?) {
        endpoint = value
    }

    fun get(): DshGatewayEndpoint? = endpoint
}

/**
 * In-memory holder for the App-local capability bridge of the current host generation (D15).
 *
 * The endpoint arrives from the app-side [DshRuntimeServiceClient] over Binder together with the
 * gateway coordinates of the same generation. It carries a per-generation token that is never
 * written to disk, logged or put in an Intent; the owner only turns it into child environment
 * variables for the official mobile tool packages.
 */
internal class DshCapabilityEndpointHolder {
    @Volatile
    private var endpoint: DshCapabilityBridgeEndpoint? = null

    fun set(value: DshCapabilityBridgeEndpoint?) {
        endpoint = value
    }

    fun get(): DshCapabilityBridgeEndpoint? = endpoint
}

/**
 * In-memory identity of the app-process composition that supplied the current generation's seams.
 *
 * A main-process rebuild may keep the same loopback gateway and the same model catalog while
 * replacing the inference owner behind it. The Node host applies its configuration at process
 * start, so the composition reports a new value here and the runtime owner reloads the host even
 * though the endpoint looks identical. It is a token-free label, never a secret and never file
 * content; the empty value means "the composition did not supply one".
 */
internal class DshHostBuildHolder {
    @Volatile
    private var revision: String = ""

    fun set(value: String?) {
        revision = value?.trim().orEmpty()
    }

    fun get(): String = revision
}

/** APK-asset source for the pinned, hash-verified payload archive and its manifest. */
internal class AssetDshPayloadSource(
    private val context: Context,
    private val config: DshRuntimeConfig
) : DshPayloadSource {
    override val description: String = "APK assets ${config.manifestAsset} + ${config.payloadAsset}"

    override fun openManifest(): InputStream? =
        runCatching { context.assets.open(config.manifestAsset) }.getOrNull()

    override fun openArchive(): InputStream? =
        runCatching { context.assets.open(config.payloadAsset) }.getOrNull()
}

/**
 * Android implementation of the runtime owner's platform seams. All of these are the real
 * production implementations proven by the R01 probe; nothing here fakes execution or readiness.
 */
internal class DshAndroidRuntimeAssembly(
    private val context: Context,
    private val config: DshRuntimeConfig,
    private val endpointHolder: DshGatewayEndpointHolder,
    private val capabilityHolder: DshCapabilityEndpointHolder,
    private val hostBuildHolder: DshHostBuildHolder
) : DshRuntimeOwner.Deps {

    private val seeder = DshGatewayConfigSeeder(config)
    private val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)

    /**
     * Materialises the APK's bundled skill content into `$DSH_HOME/bundled-skills` before Node is
     * started. This is additive content for the official filesystem skill provider, not a second
     * config surface: the root is MCA-owned inside the managed home and is never a payload path.
     */
    private val bundledSkills =
        DshBundledSkillInstaller(AndroidDshBundledSkillSource(context))

    override val payload: DshPayloadProvisioner =
        DshPayloadInstaller(config, AssetDshPayloadSource(context, config))

    override val execStrategy: DshNodeExecStrategy =
        DshNodeExecStrategy(DefaultDshProcessRunner())

    override val readinessGate: DshReadinessGate = DshReadinessGate(
        probe = LoopbackHttpProbe(),
        stabilityDelayMillis = config.stabilityDelayMillis,
        readinessTimeoutMillis = config.readinessTimeoutMillis
    )

    override val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()

    /**
     * The staged payload ships only the arm64-v8a runtime. This is a packaging fact, not a device
     * admission rule: a real `node --version` run on the device remains the authoritative check,
     * and a device without arm64 gets ABI_UNSUPPORTED with that concrete reason.
     */
    override val packagedAbis: List<String> = listOf("arm64-v8a")

    override suspend fun gatewayEndpoint(): DshGatewayEndpoint? = endpointHolder.get()

    /** D15: bridge coordinates of the same generation, or null when no bridge was supplied. */
    override suspend fun capabilityEndpoint(): DshCapabilityBridgeEndpoint? = capabilityHolder.get()

    /**
     * App-composition identity of the same generation. It forces a host reload after a
     * main-process rebuild that leaves the visible endpoint unchanged.
     */
    override fun hostBuildRevision(): String = hostBuildHolder.get()

    /**
     * Seeds the isolated home. The seeder returns a typed refusal for lost/corrupt homes; this
     * seam keeps the frozen owner contract (`Boolean`: no Node launch) and records the exact
     * typed code in the engineering log, never a token or file content.
     */
    override suspend fun seedDshHome(endpoint: DshGatewayEndpoint): Boolean =
        when (val outcome = seeder.seed(endpoint)) {
            DshSeedOutcome.Seeded -> {
                installBundledSkills()
                true
            }
            is DshSeedOutcome.Failed -> {
                log("dsh: DSH home seed refused code=${outcome.code.name} detail=${outcome.detail}")
                false
            }
        }

    /**
     * D02 account-only boot: the official account provider and frontend open even when MCA has no
     * local/cloud model bound. The overlay is generated without an invented MCA provider and the
     * host runs without MCA_DSH_GATEWAY_TOKEN.
     */
    override suspend fun seedDshHomeAccountOnly(): Boolean =
        when (val outcome = seeder.seedAccountOnly()) {
            DshSeedOutcome.Seeded -> {
                installBundledSkills()
                true
            }
            is DshSeedOutcome.Failed -> {
                log("dsh: account-only seed refused code=${outcome.code.name} detail=${outcome.detail}")
                false
            }
        }

    /**
     * Copies the APK's bundled skills into the managed home after a successful seed.
     *
     * An absent or failed bundle is never a boot refusal: skills are additive model content, and
     * `DSH_BUNDLED_SKILL_DIR` pointing at an empty/absent directory simply yields no bundled
     * entries (exactly like a payload that ships none). The typed result is logged without any
     * skill body or user content.
     */
    private fun installBundledSkills() {
        when (val installed = bundledSkills.ensureInstalled(config.homeDir)) {
            is DshBundledSkillInstallResult.Installed ->
                log(
                    "dsh: bundled skills installed root=${DshBundledSkillRoot.DIRECTORY_NAME} " +
                        "written=${installed.written} unchanged=${installed.unchanged}"
                )
            is DshBundledSkillInstallResult.Missing ->
                log("dsh: bundled skills absent: ${installed.detail}")
            is DshBundledSkillInstallResult.Failed ->
                log("dsh: bundled skills install failed: ${installed.detail}")
        }
    }

    override fun nativeLibraryDir(): File = nativeLibraryDir

    override fun newNodeHost(): DshNodeHost =
        AndroidDshNodeHost(ringCapacity = config.logRingLines, maxLineChars = config.maxLogLineChars)

    override fun isProcessAlive(pid: Int): Boolean =
        pid > 0 && File("/proc/$pid").exists()

    /**
     * Delivers SIGTERM/SIGKILL to a *journaled* trial PID during startup recovery (D24).
     *
     * `android.os.Process.sendSignal` enforces the same-UID rule, and the runtime owner verifies
     * `/proc/<pid>/cmdline` attribution against this installation's payload before it ever calls
     * this seam. A refused delivery returns false so the trial stays UNKNOWN instead of being
     * reported as released.
     */
    override fun signalProcess(pid: Int, signal: Int): Boolean =
        pid > 0 && signal > 0 &&
            runCatching { android.os.Process.sendSignal(pid, signal) }.isSuccess

    /**
     * Same-UID processes still referencing the verified payload tree or the native Node host.
     * The needle list is exactly this app's absolute payload/native paths, so unrelated apps
     * can never match; the scan is bounded and skips this process.
     */
    override fun findOrphanedHostProcesses(): List<Int>? = DshProcessTree.matchingSameUid(
        needles = listOf(
            config.payloadDir.absolutePath,
            File(context.applicationInfo.nativeLibraryDir, config.nodeLibraryName).absolutePath
        )
    )

    /**
     * Replaces the payload's app-home tool shims with symlinks to the PackageManager-extracted
     * copies in nativeLibraryDir. The R01 probe proved both direct exec of a nativeLibraryDir ELF
     * and exec of an app-home symlink pointing at it; it also proved that exec of a real app-home
     * ELF/script is denied at targetSdk 36. Only tools that are actually packaged as native
     * libraries are relinked; anything else keeps its original (non-executable) file.
     */
    override fun prepareRuntimeLinks(executable: DshNodeExecutable, payloadRoot: File) {
        prepareLinkFarm(payloadRoot)
        // The installed payload keeps its own farm (toolchain diagnostics probe that tree). A
        // reinstall changes nativeLibraryDir, so it must be refreshed even when a closure serves.
        val installed = config.payloadDir
        if (runCatching { payloadRoot.canonicalFile }.getOrNull() !=
            runCatching { installed.canonicalFile }.getOrNull()
        ) {
            prepareLinkFarm(installed)
        }
        log("dsh: runtime links prepared for ${executable.strategy}")
    }

    private fun prepareLinkFarm(payloadRoot: File) {
        val binDir = File(payloadRoot, "bin")
        if (!binDir.isDirectory) return
        for (mapping in TOOL_LINKS) {
            val native = File(nativeLibraryDir, mapping.libraryName)
            if (!native.isFile) continue
            val link = File(payloadRoot, mapping.payloadRelativePath)
            if (link.parentFile?.isDirectory != true) continue
            runCatching {
                Files.deleteIfExists(link.toPath())
                Files.createSymbolicLink(link.toPath(), native.toPath())
            }.onFailure {
                log("dsh: tool link ${mapping.payloadRelativePath} not created (${it.javaClass.simpleName})")
            }
        }
        // Fallback only: when the command dispatcher (libdshcmd.so, D11) is not packaged, the
        // payload bash shim is still a shell script in the writable home and cannot be exec'd at
        // targetSdk 36. Point it at the platform shell so bash-local keeps working in that case.
        // With the dispatcher present, TOOL_LINKS already owns bin/bash and this block is skipped.
        if (!File(nativeLibraryDir, "libdshcmd.so").isFile) {
            runCatching {
                val bash = File(binDir, "bash")
                if (bash.exists() || Files.isSymbolicLink(bash.toPath())) {
                    Files.deleteIfExists(bash.toPath())
                    Files.createSymbolicLink(bash.toPath(), File("/system/bin/sh").toPath())
                }
            }.onFailure {
                log("dsh: bash shim link not created (${it.javaClass.simpleName})")
            }
        }
        // node itself must be first in the payload bin directory, matching the reference layout.
        runCatching {
            // Created unconditionally: an archived payload tree may not carry bin/node at all,
            // while a fresh install must still end with the current nativeLibraryDir target.
            val nodeLink = File(binDir, "node")
            Files.deleteIfExists(nodeLink.toPath())
            Files.createSymbolicLink(
                nodeLink.toPath(),
                File(nativeLibraryDir, config.nodeLibraryName).toPath()
            )
        }.onFailure {
            log("dsh: node link not created (${it.javaClass.simpleName})")
        }
    }

    override fun log(line: String) {
        Log.i(TAG, DshLogRedactor.redact(line, config.maxLogLineChars))
    }

    private data class ToolLink(val payloadRelativePath: String, val libraryName: String)

    private companion object {
        const val TAG = "McaDsh"

        /**
         * Names emitted by scripts/dsh/stage.ps1. Keep this list and the staging script in sync;
         * the R01 probe proved that the symlink -> nativeLibraryDir exec path works.
         */
        val TOOL_LINKS = listOf(
            ToolLink("bin/node", "libdshnode.so"),
            // The archive ships a real (0644, non-executable) copy at runtime/bin/node; MCP stdio
            // servers spawn that path directly, so it needs the same nativeLibraryDir link.
            ToolLink("runtime/bin/node", "libdshnode.so"),
            ToolLink("runtime/bin/rg", "libdshrg.so"),
            ToolLink("runtime/bin/curl", "libdshcurl.so"),
            ToolLink("bin/git", "libdshgit.so"),
            ToolLink("git/libexec/git-core/git-remote-http", "libdshgitremotehttp.so"),
            ToolLink("git/libexec/git-core/git-remote-https", "libdshgitremotehttps.so"),
            // Command dispatcher (D11): the payload's shell wrappers cannot be exec'd at
            // targetSdk 36. The dispatcher maps argv[0] to the real node/JS or python entry.
            // bash is an sh compatibility layer with bash-only options stripped, not GNU bash.
            ToolLink("bin/npm", "libdshcmd.so"),
            ToolLink("bin/npx", "libdshcmd.so"),
            ToolLink("bin/pnpm", "libdshcmd.so"),
            ToolLink("bin/pnpx", "libdshcmd.so"),
            ToolLink("bin/pip", "libdshcmd.so"),
            ToolLink("bin/pip3", "libdshcmd.so"),
            ToolLink("bin/bash", "libdshcmd.so"),
            // python: both the payload/python/bin launcher names and the PATH-first bin/ wrappers
            // resolve to the one packaged launcher executable. npm/npx/pnpm/pip remain shell/JS
            // wrappers in the writable home and are documented R05 FAILs.
            ToolLink("python/bin/python3.14", "libdshpython.so"),
            ToolLink("python/bin/python3", "libdshpython.so"),
            ToolLink("bin/python", "libdshpython.so"),
            ToolLink("bin/python3", "libdshpython.so"),
            ToolLink("bin/python3.14", "libdshpython.so")
        )
    }
}

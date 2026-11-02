package com.muyuchat.core.dsh.runtime.config

import android.util.Log
import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.console.DshShellSandboxStatus
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Typed reason the MCA-owned DSH home was not seeded.
 *
 * Every value is a fail-closed condition for the current host generation: the runtime owner
 * maps a [DshSeedOutcome.Failed] to `GATEWAY_UNAVAILABLE` and never launches Node, so a lost or
 * corrupt home can never be silently replaced by a fresh empty home.
 */
enum class DshSeedFailureCode {
    /** `dataRoot`/the home path is not a usable configuration. */
    CONFIG_INVALID,
    /** The home path exists but is a regular file, not a directory. */
    HOME_NOT_A_DIRECTORY,
    /** The home directory could not be listed (permissions/IO). */
    HOME_UNREADABLE,
    /** The home is missing/empty or missing a required subdirectory/template file: data-loss evidence. */
    HOME_INCOMPLETE,
    /** The template marker exists but cannot be parsed or has an unsupported shape. */
    MARKER_CORRUPT,
    /** The marker was written by a newer template version than this runtime knows. */
    TEMPLATE_VERSION_UNSUPPORTED,
    /** The verified payload does not carry the official profile template files. */
    TEMPLATE_MISSING,
    /** The MCA model catalog could not be read, so no truthful provider overlay can be written. */
    CATALOG_UNAVAILABLE,
    /** The generated overlay would not be honest (fake endpoint, disabled official provider...). */
    OVERLAY_UNHONEST,
    /** A directory/file creation, copy, fsync or rename failed. */
    IO_FAILURE
}

/** Result of one seed attempt. */
sealed interface DshSeedOutcome {
    /** The home is complete for the current template version and the overlay was written. */
    data object Seeded : DshSeedOutcome

    /** Fail-closed refusal. [detail] is path/state evidence and never contains a token. */
    data class Failed(val code: DshSeedFailureCode, val detail: String) : DshSeedOutcome
}

/** ECO-5: why a seeded adapter-model row cannot participate in official compaction. */
internal enum class DshSeedContextWindowGapReason {
    /** The MCA gateway advertised no positive `context_window`/`context_length` for the model. */
    NO_CONTEXT_WINDOW
}

/**
 * ECO-5: one seeded model row that will be written without `contextWindow`.
 *
 * Official `dsh-compaction-basic` fails closed for such a row ("no context capacity"). The seeder
 * cannot invent a window, so it keeps the official row shape (no fabricated `contextWindow`) and
 * reports this typed gap instead of publishing the row as if compaction were available.
 */
internal data class DshSeedContextWindowGap(
    val modelId: String,
    val reason: DshSeedContextWindowGapReason = DshSeedContextWindowGapReason.NO_CONTEXT_WINDOW
)

/**
 * Seeds the app-owned DSH home for one host generation and writes the MCA-owned config overlay.
 *
 * Ownership split (documented in the V1 proposal and ADR 0003):
 * - MCA seeds the official profile template from the verified payload on first run;
 * - MCA owns the provider/permission/platform-patch overlay (`<DSH_HOME>/cordis.patch.yml`) and rewrites it per
 *   host generation because the loopback baseURL changes with the ephemeral port;
 * - MCA never fabricates a model context window: a catalog row without one is seeded with its
 *   official shape (no `contextWindow`) and reported as a typed ECO-5 gap, because the official
 *   compaction backend fails closed for such a row;
 * - MCA owns `<DSH_HOME>/mcp-servers.json` (see [DshMcpServerConfigLoader]): when that bounded,
 *   typed file exists, each accepted server becomes exactly one official
 *   `@deepseek-ai/dsh-mcp-client` row in the overlay. The file is never created by MCA, so an
 *   absent file reproduces the zero-MCP-row overlay byte for byte;
 * - the official plugin manager owns `profiles/web/package.json` and
 *   `profiles/web/pnpm-workspace.yaml` after the first seed, so they are never overwritten;
 * - DSH owns sessions, compression, approvals and all official user state after the seed.
 *
 * Data-loss guard (DSHA G2):
 * - a truly first-run home (absent or empty) may be seeded;
 * - an existing non-empty home without a marker is adopted only when it already has every
 *   required directory/file; otherwise the seed refuses with [DshSeedFailureCode.HOME_INCOMPLETE]
 *   instead of recreating a plausible empty home over lost user data;
 * - a complete home whose required subdirectories disappeared is refused, never repaired;
 * - a successful seed also records `<dataRoot>/.mca-home-seeded.json`. That record is outside the
 *   home, so a home deleted/replaced on its own is still recognised as loss instead of first run;
 * - an unreadable marker is refused instead of being treated as first run.
 *
 * Atomicity:
 * - every template file and the marker are written to a sibling `.<name>.mca-tmp` file, fsynced
 *   and renamed over the target (`ATOMIC_MOVE` with a replacement fallback);
 * - a `seeding` marker is written before the first template byte of a FIRST-RUN seed, so a process
 *   death between two files is resumed on the next generation rather than misdiagnosed as a lost
 *   home;
 * - a version migration keeps the previous marker until it completes and is idempotent, so an
 *   interrupted migration is retried without ever reclassifying plugin-manager files as
 *   first-run template files;
 * - a half-written target can therefore never survive a crash (the old file remains until the
 *   rename, and the new file appears complete).
 *
 * Versioning: [PROFILE_TEMPLATE_VERSION] is recorded in
 * `<DSH_HOME>/.mca-profile-template.json`. An older or unmarked structurally complete home is
 * migrated by version: the MCA-managed `cordis.yml` is refreshed atomically while plugin-manager
 * and user-owned files are preserved.
 */
internal class DshGatewayConfigSeeder(
    private val config: DshRuntimeConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Functional probe of the packaged Landlock runner. Production uses the real executable;
     * tests inject a typed result so both compositions (kernel and advisory) are covered on the
     * JVM. The probe never decides silently: its result is logged and published in [lastReport].
     */
    private val sandboxProbe: (File?, File) -> DshSandboxProbeResult = { runner, workingDir ->
        DshSandboxProbe.probe(runner, workingDir)
    }
) {

    private data class Model(
        val id: String,
        val displayName: String,
        val contextWindow: Int?,
        val maxOutputTokens: Int?
    )

    /**
     * Seeds the home for one host generation.
     *
     * @return [DshSeedOutcome.Seeded] only after the template and overlay are durable;
     *   [DshSeedOutcome.Failed] for every refusal, including every data-loss-shaped condition.
     */
    /** Typed, token-free facts of the last successful seed; null before the first seed. */
    var lastReport: DshSeedReport? = null
        private set

    /**
     * ECO-5 facts of the last successful seed: the model rows written without a `contextWindow`.
     *
     * Empty is the only state in which official compaction can work for every bound model. The
     * list is value-free (model ids only) and is also logged as one typed warning per row, and
     * mirrored as a comment next to the row in the seeded overlay.
     */
    var lastContextWindowGaps: List<DshSeedContextWindowGap> = emptyList()
        private set

    /**
     * Seeds the home for one host generation with a bound MCA gateway endpoint.
     *
     * Kept explicitly non-null so the runtime owner's frozen `seedDshHome(endpoint)` seam keeps
     * working unchanged.
     */
    fun seed(endpoint: DshGatewayEndpoint): DshSeedOutcome = seedInternal(endpoint)

    /**
     * Seeds the home for an account-only generation (D02).
     *
     * The official DeepSeek account provider and the official frontend must be usable even when no
     * MCA gateway is bound. This path writes no `llm-pi-ai` MCA provider row at all: inventing a
     * loopback baseURL for a gateway that does not exist would be a dishonest configuration. The
     * platform patch rows, the MCA workspace and any user-owned overlay rows are still composed and
     * preserved, and the official account provider is never named (so never disabled) by MCA.
     *
     * Wiring: the app composition selects this entry when the MCA provider is disabled or its
     * gateway binding is intentionally absent for the generation.
     */
    fun seedAccountOnly(): DshSeedOutcome = seedInternal(null)

    private fun seedInternal(endpoint: DshGatewayEndpoint?): DshSeedOutcome {
        lastReport = null
        lastContextWindowGaps = emptyList()
        try {
            config.validated()
        } catch (t: IllegalArgumentException) {
            return refuse(
                DshSeedFailureCode.CONFIG_INVALID,
                t.message ?: t.javaClass.simpleName
            )
        }

        val decision = DshHomeAdoption(
            homeDir = config.homeDir,
            dataRoot = config.dataRoot,
            workspaceDir = config.workspaceDir,
            templateVersion = PROFILE_TEMPLATE_VERSION,
            legacyTemplateVersion = LEGACY_TEMPLATE_VERSION,
            markerVersion = MARKER_VERSION
        ).classify()
        if (decision is DshHomeAdoption.Decision.Refused) return refuse(decision.code, decision.detail)

        val templateRoot = File(config.payloadDir, config.dshHomeTemplateRelativePath)
        val templateProfile = File(templateRoot, PROFILE_DIR_RELATIVE)
        val needsTemplateCopy = decision !is DshHomeAdoption.Decision.Current
        if (needsTemplateCopy) {
            if (!templateProfile.isDirectory) {
                return refuse(
                    DshSeedFailureCode.TEMPLATE_MISSING,
                    "the official profile template is missing from the verified payload: ${templateProfile.absolutePath}"
                )
            }
            val missing = PROFILE_TEMPLATE_FILES.filterNot { File(templateProfile, it).isFile }
            if (missing.isNotEmpty()) {
                return refuse(
                    DshSeedFailureCode.TEMPLATE_MISSING,
                    "the official profile template is missing ${missing.joinToString(",")}"
                )
            }
        }

        val models = if (endpoint == null) {
            emptyList()
        } else {
            fetchModels(endpoint)
                ?: return refuse(
                    DshSeedFailureCode.CATALOG_UNAVAILABLE,
                    "the MCA model catalog could not be read for this DSH generation"
                )
        }
        val provider: DshConfigProviderState = when {
            endpoint == null -> DshConfigProviderState.McaNotBound
            models.isEmpty() -> DshConfigProviderState.McaCatalogEmpty(endpoint.baseUrl)
            else -> DshConfigProviderState.McaBound(endpoint.baseUrl, models.size)
        }
        // ECO-5: an advertised row without a context window cannot be compacted by the official
        // backend. Report the concrete rows now; the typed list is published with the seed.
        val contextWindowGaps = contextWindowGapsOf(models)
        logContextWindowGaps(contextWindowGaps)
        // MCP is an optional add-on: an absent file yields zero rows, and a refused file or entry is
        // reported as a typed, value-free diagnostic instead of failing the host generation.
        val mcp = DshMcpServerConfigLoader.load(File(config.homeDir, DshMcpServerConfigLoader.FILE_NAME))
        logMcpDiagnostics(mcp.diagnostics)
        val sandbox = resolveSandboxEnforcement()
        val overlay = DshOverlayComposer.compose(
            config = config,
            baseUrl = endpoint?.baseUrl,
            models = models.map {
                DshOverlayModel(it.id, it.displayName, it.contextWindow, it.maxOutputTokens)
            },
            sandbox = sandbox
        )
        return applySeed(
            decision, templateRoot, templateProfile, overlay, provider, mcp, contextWindowGaps, sandbox
        )
    }

    // ------------------------------------------------------------------ seed / migration

    private fun applySeed(
        decision: DshHomeAdoption.Decision,
        templateRoot: File,
        templateProfile: File,
        overlay: String,
        provider: DshConfigProviderState,
        mcp: DshMcpServerCatalog,
        contextWindowGaps: List<DshSeedContextWindowGap>,
        sandbox: DshSandboxEnforcement
    ): DshSeedOutcome {
        cleanupStaleTemps()
        val home = config.homeDir
        val profileDir = File(home, PROFILE_DIR_RELATIVE)
        val freshOrResume = decision is DshHomeAdoption.Decision.Fresh || decision is DshHomeAdoption.Decision.Resume
        val upgrade = decision is DshHomeAdoption.Decision.Upgrade
        val writesTemplates = freshOrResume || upgrade

        if (writesTemplates) {
            if (!ensureDirectory(home)) {
                return refuse(DshSeedFailureCode.IO_FAILURE, "cannot create the DSH home directory")
            }
            if (freshOrResume) {
                // Persist the in-progress state BEFORE the first template byte of a first-run
                // seed. A crash between two atomic copies then resumes next generation instead
                // of looking like a lost home. A migration deliberately does NOT write this
                // marker: it must keep its previous marker (or none) so an interrupted
                // migration is retried without making plugin-owned files look first-run.
                if (!writeMarker(SeedPhase.SEEDING)) {
                    return refuse(DshSeedFailureCode.IO_FAILURE, "cannot write the seeding marker")
                }
            }
            for (dir in listOf(config.configDir, profileDir)) {
                if (!ensureDirectory(dir)) {
                    return refuse(DshSeedFailureCode.IO_FAILURE, "cannot create ${dir.absolutePath}")
                }
            }
            for (name in PROFILE_TEMPLATE_FILES) {
                val target = File(profileDir, name)
                // package.json / pnpm-workspace.yaml are plugin-manager-owned after the first
                // seed: a migration restores them only when missing, never overwrites them.
                val isManaged = name == MANAGED_PROFILE_FILE
                if (freshOrResume || isManaged || !target.isFile) {
                    if (!copyAtomically(File(templateProfile, name), target)) {
                        return refuse(
                            DshSeedFailureCode.IO_FAILURE,
                            "cannot copy the profile template $name"
                        )
                    }
                }
            }
            if (decision is DshHomeAdoption.Decision.Upgrade) {
                Log.i(
                    TAG,
                    "DSH home template migrated ${decision.fromTemplateVersion} -> " +
                        "$PROFILE_TEMPLATE_VERSION (plugin-manager files preserved)"
                )
            }
        }
        for (dir in listOf(config.workspaceDir, config.tmpDir)) {
            if (!ensureDirectory(dir)) {
                return refuse(DshSeedFailureCode.IO_FAILURE, "cannot create ${dir.absolutePath}")
            }
        }

        val settingsSource = File(templateRoot, SETTINGS_NAME)
        val settingsTarget = File(home, SETTINGS_NAME)
        if (writesTemplates && !settingsTarget.isFile && settingsSource.isFile) {
            if (!copyAtomically(settingsSource, settingsTarget)) {
                return refuse(DshSeedFailureCode.IO_FAILURE, "cannot copy settings.yaml")
            }
        }

        val overlayTarget = File(home, OVERLAY_NAME)
        // Refuse to publish a dishonest overlay before touching the previous one: a fake MCA
        // endpoint, a disabled official provider or a dropped platform row is worse than a typed
        // boot failure.
        //
        // Only the MCA-owned platform/provider rows are inspected. The `dsh-mcp-client` rows are
        // appended below as typed, double-quoted scalars, so a value declared by the user can
        // neither trip a platform honesty check nor fake one.
        val facts = DshComposedConfigInspector.inspect(overlay)
        val violations = DshComposedConfigInspector.violations(
            facts,
            requireMcaProvider = provider !is DshConfigProviderState.McaNotBound,
            expectKernelSandbox = sandbox is DshSandboxEnforcement.Kernel
        )
        if (violations.isNotEmpty()) {
            return refuse(
                DshSeedFailureCode.OVERLAY_UNHONEST,
                violations.joinToString("; ")
            )
        }
        val previousOverlay = if (overlayTarget.isFile) {
            runCatching { overlayTarget.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }
        // One official `@deepseek-ai/dsh-mcp-client` row per accepted server in the MCA-owned
        // declaration file. An empty server list renders nothing, which is what keeps an absent
        // `mcp-servers.json` byte-identical to a build that never composed MCP at all.
        val generated = overlay + DshMcpOverlayRows.render(mcp.servers)
        // MCA also owns the `dsh-mcp-client` rows it composed for this generation, plus the rows a
        // previous generation marked as its own (so a server removed from `mcp-servers.json` loses
        // its row). Every other row — including a hand-written `dsh-mcp-client` row — is preserved.
        val managedIds = DshManagedOverlay.MANAGED_ROW_IDS +
            mcp.servers.map { it.rowId } +
            DshMcpOverlayRows.ownedRowIds(previousOverlay)
        val mergePlan = DshManagedOverlayMerge.plan(previousOverlay, generated, managedIds)
        var backedUp = false
        if (mergePlan.backupPrevious) {
            val backup = File(home, OVERLAY_BACKUP_NAME)
            backedUp = copyAtomically(overlayTarget, backup)
            if (!backedUp) {
                return refuse(
                    DshSeedFailureCode.IO_FAILURE,
                    "cannot preserve the unreadable previous $OVERLAY_NAME before replacing it"
                )
            }
            Log.w(TAG, "DSH overlay was not a patch sequence; kept a backup and rewrote the managed rows")
        }
        if (!writeTextAtomically(overlayTarget, mergePlan.text)) {
            return refuse(DshSeedFailureCode.IO_FAILURE, "cannot write ${OVERLAY_NAME}")
        }
        lastReport = DshSeedReport(
            provider = provider,
            preservedUserRows = mergePlan.preservedUserRows,
            managedConflicts = mergePlan.managedConflicts,
            previousOverlayBackedUp = backedUp,
            overlaySha256 = sha256Hex(mergePlan.text),
            mcpServerRows = mcp.servers.map { it.rowId },
            mcpDiagnostics = mcp.diagnostics,
            sandboxEnforcement = sandbox
        )
        lastContextWindowGaps = contextWindowGaps
        if (!writeMarker(SeedPhase.COMPLETE)) {
            return refuse(DshSeedFailureCode.IO_FAILURE, "cannot write the complete template marker")
        }
        // Secondary evidence outside the home. It is best-effort: the home marker above is the
        // primary record, and failing to write this one must not block an otherwise valid boot.
        if (!writeHomeStateMarker()) {
            Log.w(TAG, "DSH seeding could not record the data-root home-state marker")
        }
        publishSandboxStatus(sandbox)
        return DshSeedOutcome.Seeded
    }

    /**
     * Publishes the one shell-confinement fact the console reads. The overlay is authoritative for
     * Node composition; this file is the app-side, user-visible projection. A write failure is
     * logged and leaves the previous/unknown fact, never a fabricated "enforced" value.
     */
    private fun publishSandboxStatus(sandbox: DshSandboxEnforcement) {
        val status = when (sandbox) {
            is DshSandboxEnforcement.Kernel -> DshShellSandboxStatus(
                enforced = true,
                landlockAbi = sandbox.landlockAbi,
                partial = sandbox.partial,
                advisoryReason = null,
                updatedAtMillis = clock()
            )

            is DshSandboxEnforcement.Advisory -> DshShellSandboxStatus(
                enforced = false,
                landlockAbi = null,
                partial = false,
                advisoryReason = sandbox.reason.name,
                updatedAtMillis = clock()
            )
        }
        if (!DshShellSandboxStatusStore.write(config.dataRoot, status)) {
            Log.w(TAG, "DSH shell sandbox status could not be recorded; the console will show unknown")
        }
    }

    private fun ensureDirectory(dir: File): Boolean =
        dir.isDirectory || dir.mkdirs() || dir.isDirectory

    /**
     * Removes only our own `.<name>.mca-tmp` leftovers. They are never authoritative: a crash
     * before the rename leaves the previous complete target in place, and the next seed rewrites
     * the temp from scratch. This keeps a repeated interrupted seed from accumulating debris.
     */
    private fun cleanupStaleTemps() {
        val dirs = listOf(
            config.dataRoot,
            config.homeDir,
            File(config.homeDir, PROFILE_DIR_RELATIVE)
        )
        for (dir in dirs) {
            val candidates = dir.listFiles() ?: continue
            for (candidate in candidates) {
                if (candidate.isFile && candidate.name.endsWith(TEMP_SUFFIX)) {
                    runCatching { candidate.delete() }
                }
            }
        }
    }

    // ------------------------------------------------------------------ atomic writes

    /**
     * Writes [source] into a sibling temp file, fsyncs it and renames it over [target]. The
     * target is never truncated in place, so a crash leaves either the old complete file or the
     * new complete file.
     */
    private fun copyAtomically(source: File, target: File): Boolean {
        val parent = target.parentFile ?: return false
        if (!ensureDirectory(parent)) return false
        val temp = File(parent, ".${target.name}$TEMP_SUFFIX")
        return runCatching {
            FileInputStream(source).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            moveAtomically(temp, target)
            target.isFile
        }.getOrElse {
            runCatching { temp.delete() }
            Log.w(TAG, "DSH seeding failed: copy ${source.name} failed (${it.javaClass.simpleName})")
            false
        }
    }

    private fun writeTextAtomically(target: File, content: String): Boolean {
        val parent = target.parentFile ?: return false
        if (!ensureDirectory(parent)) return false
        val temp = File(parent, ".${target.name}$TEMP_SUFFIX")
        return runCatching {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            moveAtomically(temp, target)
            target.isFile
        }.getOrElse {
            runCatching { temp.delete() }
            Log.w(TAG, "DSH seeding failed: cannot write ${target.name} (${it.javaClass.simpleName})")
            false
        }
    }

    private fun moveAtomically(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (unsupported: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (unsupported: UnsupportedOperationException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeMarker(phase: SeedPhase): Boolean =
        writeTextAtomically(
            File(config.homeDir, TEMPLATE_MARKER_NAME),
            markerContent(phase)
        )

    /**
     * Sibling record directly below `dataRoot`; it survives deletion of the home and makes a
     * missing home detectable as loss instead of first run.
     */
    private fun writeHomeStateMarker(): Boolean =
        writeTextAtomically(homeStateMarkerFile(), markerContent(SeedPhase.COMPLETE))

    private fun homeStateMarkerFile(): File = File(config.dataRoot, HOME_STATE_MARKER_NAME)

    private fun markerContent(phase: SeedPhase): String {
        val marker = JSONObject()
            .put("markerVersion", MARKER_VERSION)
            .put("templateVersion", PROFILE_TEMPLATE_VERSION)
            .put("phase", phase.value)
            .put("updatedAtEpochMillis", clock())
            .put("source", "core/dsh-runtime DshGatewayConfigSeeder")
        // Optional evidence for the three-way overlay merge: which managed bytes were last
        // published for this home. Older markers without it stay readable (optInt defaults).
        lastReport?.let { report ->
            marker.put("overlaySha256", report.overlaySha256)
            marker.put("providerState", providerStateValue(report.provider))
        }
        return marker.toString(2) + "\n"
    }

    private fun providerStateValue(state: DshConfigProviderState): String = when (state) {
        is DshConfigProviderState.McaBound -> "mca-bound"
        is DshConfigProviderState.McaCatalogEmpty -> "mca-catalog-empty"
        DshConfigProviderState.McaNotBound -> "mca-not-bound"
    }

    /** Stable digest of the published overlay, used only for the merge record (never a secret). */
    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private fun refuse(code: DshSeedFailureCode, detail: String): DshSeedOutcome.Failed {
        Log.w(TAG, "DSH seeding refused ${code.name}: $detail")
        return DshSeedOutcome.Failed(code, detail)
    }

    // ------------------------------------------------------------------ catalog

    private fun fetchModels(endpoint: DshGatewayEndpoint): List<Model>? {
        val url = endpoint.baseUrl.trimEnd('/') + "/models"
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        if (uri.host != "127.0.0.1" && uri.host != "::1" && uri.host != "localhost") return null
        if (uri.port !in 1..65535) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = uri.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CATALOG_TIMEOUT_MILLIS
            connection.readTimeout = CATALOG_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${endpoint.bearerToken}")
            if (connection.responseCode != 200) {
                Log.w(TAG, "DSH gateway catalog HTTP ${connection.responseCode}")
                return null
            }
            val body = connection.inputStream.use { stream ->
                val bytes = stream.readNBytes(MAX_CATALOG_BYTES + 1)
                if (bytes.size > MAX_CATALOG_BYTES) return null
                bytes.toString(Charsets.UTF_8)
            }
            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: return null
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isEmpty()) continue
                    val name = item.optString("name").ifBlank {
                        item.optString("display_name").ifBlank {
                            item.optString("mca_display_name").ifBlank { id }
                        }
                    }
                    val context = item.optInt("context_window", 0)
                        .takeIf { it > 0 }
                        ?: item.optInt("context_length", 0).takeIf { it > 0 }
                    val maxOutput = item.optInt("max_output_tokens", 0).takeIf { it > 0 }
                        ?: item.optInt("max_tokens", 0).takeIf { it > 0 }
                    add(
                        Model(
                            id = id,
                            displayName = name,
                            contextWindow = context,
                            maxOutputTokens = maxOutput
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DSH gateway catalog read failed (${t.javaClass.simpleName})")
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ------------------------------------------------------------------ sandbox

    /**
     * Probes the Android Landlock runner and turns the result into the one composition fact.
     *
     * Failure is never silent: the seeder logs the exact advisory reason and publishes it in
     * [lastReport], so the console can say "high-trust execution, not workspace-confined" instead of
     * repeating the workspace-write claim the official preset would otherwise imply.
     */
    private fun resolveSandboxEnforcement(): DshSandboxEnforcement {
        val runner = config.sandboxRunnerFile
        if (runner != null && !config.dataRoot.isDirectory) {
            // The probe's functional denial check needs a writable cwd; dataRoot is app-private
            // and is also created by the launcher before the home seed.
            runCatching { config.dataRoot.mkdirs() }
        }
        val result = sandboxProbe(runner, config.dataRoot)
        val enforcement = when (result) {
            is DshSandboxProbeResult.Enforced -> {
                if (runner == null) {
                    DshSandboxEnforcement.Advisory(DshSandboxEnforcement.Reason.RUNNER_MISSING)
                } else {
                    DshSandboxEnforcement.Kernel(result.landlockAbi, result.partial)
                }
            }

            is DshSandboxProbeResult.Unavailable -> DshSandboxEnforcement.Advisory(result.reason)
        }
        when (enforcement) {
            is DshSandboxEnforcement.Kernel -> Log.i(
                TAG,
                "DSH shell sandbox: kernel-enforced workspace-write " +
                    "(landlock abi=${enforcement.landlockAbi}" +
                    (if (enforcement.partial) ", partial (truncate not mediated)" else "") + ")"
            )

            is DshSandboxEnforcement.Advisory -> Log.w(
                TAG,
                "DSH shell sandbox: ADVISORY workspace-write (${enforcement.reason.name}); " +
                    "model shell commands are NOT confined to the session workspace"
            )
        }
        return enforcement
    }


    /**
     * Reports declaration refusals as code + location + schema field only. Values read from the
     * file (command, arguments, URL, env/header values) are never logged, so a secret cannot leak
     * through a diagnostic.
     */
    private fun logMcpDiagnostics(diagnostics: List<DshMcpConfigDiagnostic>) {
        for (diagnostic in diagnostics) {
            Log.w(
                TAG,
                "DSH MCP declaration refused ${diagnostic.code.name} at " +
                    "${diagnostic.location} field=${diagnostic.field ?: "-"}"
            )
        }
    }

    /**
     * ECO-5: model rows that will be seeded without a `contextWindow`.
     *
     * The official compaction backend needs a positive window on the adapter model row; MCA cannot
     * invent one, so those rows are reported instead of being published as compaction-capable.
     */
    private fun contextWindowGapsOf(models: List<Model>): List<DshSeedContextWindowGap> =
        models.mapNotNull { model ->
            if ((model.contextWindow ?: 0) > 0) {
                null
            } else {
                DshSeedContextWindowGap(model.id, DshSeedContextWindowGapReason.NO_CONTEXT_WINDOW)
            }
        }

    /** One typed, value-free warning per row that cannot be compacted (model id only). */
    private fun logContextWindowGaps(gaps: List<DshSeedContextWindowGap>) {
        for (gap in gaps) {
            Log.w(
                TAG,
                "DSH model row without contextWindow: model=${gap.modelId} reason=${gap.reason.name}; " +
                    "official compaction fails closed for this row " +
                    "(dsh-compaction-basic: no context capacity)"
            )
        }
    }

    internal companion object {
        const val TAG = "McaDshSeeder"
        const val OVERLAY_NAME = "cordis.patch.yml"
        const val SETTINGS_NAME = "settings.yaml"
        const val PROFILE_DIR_RELATIVE = "profiles/web"
        const val TEMPLATE_MARKER_NAME = ".mca-profile-template.json"
        const val HOME_STATE_MARKER_NAME = ".mca-home-seeded.json"
        const val TEMP_SUFFIX = ".mca-tmp"

        /** Kept next to the overlay when a previous file was not a readable patch sequence. */
        const val OVERLAY_BACKUP_NAME = "cordis.patch.yml.unreadable-backup"

        private const val HEX = "0123456789abcdef"

        /** Bump when the seeded template/overlay contract changes and a migration is added. */
        const val PROFILE_TEMPLATE_VERSION = 1
        const val MARKER_VERSION = 1
        /** Homes seeded before the marker existed are migrated from version 0. */
        const val LEGACY_TEMPLATE_VERSION = 0

        /**
         * Profile composition is MCA-managed and is refreshed by a version migration. The other
         * two files are plugin-manager-owned after the first seed and are only restored when
         * missing, so installed plugins survive a payload/template upgrade.
         */
        const val MANAGED_PROFILE_FILE = "cordis.yml"

        /** Required for a home to be considered structurally complete. */
        val REQUIRED_HOME_DIRS = listOf(PROFILE_DIR_RELATIVE, "config")
        val PROFILE_TEMPLATE_FILES = listOf("cordis.yml", "package.json", "pnpm-workspace.yaml")

        const val CATALOG_TIMEOUT_MILLIS = 5_000
        const val MAX_CATALOG_BYTES = 1_048_576
    }
}

package com.muyuchat.core.dsh.runtime.config

import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DSHA G2: the seeder must never turn a lost/corrupt DSH home into a silent fresh home.
 *
 * Covered here (plain JVM, no Android runtime):
 * - a first-run home (absent or empty) seeds and leaves an atomic template marker;
 * - a second seed is idempotent and preserves plugin-manager/user-owned files;
 * - every seed writes the Android platform patch rows (`bash-sandbox`/`pwsh-sandbox`/
 *   `session-log-download`/`session-telemetry-otel` disabled, `bash-local` inserted with the
 *   MCA workspace cwd) while the reference danger default and tool plugins stay absent;
 * - an existing home missing a required subdirectory/template file fails closed with a typed code
 *   and is left untouched;
 * - a home deleted after a successful seed fails closed because a data-root record survives it;
 * - an unreadable marker and a marker from a newer template version fail closed;
 * - an older/unmarked complete home is migrated without overwriting plugin state;
 * - an interrupted seed (marker phase=seeding) resumes instead of being reported as a lost home;
 * - no `*.mca-tmp` file survives a successful seed.
 */
class DshGatewayConfigSeederTest {

    // ------------------------------------------------------------------ first run

    @Test
    fun firstRunEmptyHomeSeedsAtomicallyAndWritesTheTemplateMarker() {
        val root = tempRoot("dsh-seeder-first")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            // A truly first-run home may already exist as an empty directory.
            assertTrue(config.homeDir.mkdirs())

            val catalog = FakeCatalog(EMPTY_CATALOG)
            try {
                val outcome = DshGatewayConfigSeeder(config).seed(catalog.endpoint)
                assertTrue("first-run home must seed, was $outcome", outcome is DshSeedOutcome.Seeded)
                assertEquals("Bearer test-bearer-token", catalog.authorization())
            } finally {
                catalog.close()
            }

            val profile = File(config.homeDir, "profiles/web")
            assertEquals(CORDIS_TEMPLATE, File(profile, "cordis.yml").readText())
            assertEquals(PACKAGE_TEMPLATE, File(profile, "package.json").readText())
            assertEquals(WORKSPACE_TEMPLATE, File(profile, "pnpm-workspace.yaml").readText())
            assertEquals(DEFAULT_SETTINGS, File(config.homeDir, "settings.yaml").readText())

            val overlay = File(config.homeDir, "cordis.patch.yml").readText()
            assertPlatformPatchRows(overlay, config)
            assertTrue("empty catalog must be a YAML array, not null", overlay.contains("models: []"))
            assertTrue(overlay.contains("defaultPreset: workspace-write"))
            assertTrue(overlay.contains("approval: ask"))
            assertTrue(overlay.contains("apiKeyEnv: MCA_DSH_GATEWAY_TOKEN"))
            assertTrue(
                "a local prefill must outlast the official 300 s idle guard",
                overlay.contains("streamIdleTimeoutMs: 900000")
            )
            assertTrue(overlay.contains("baseURL: \"${catalog.endpoint.baseUrl}\""))
            val quotedWorkspace = quoteForOverlay(config.workspaceDir.absolutePath)
            assertTrue(
                "the official workspace controller must resolve first use inside the MCA workspace",
                overlay.contains("documentsDirectory: $quotedWorkspace")
            )
            assertFalse("the reference danger default must never be copied", overlay.contains("approval: never"))
            for ((id, name) in TOOL_PACKAGES) {
                assertTrue(
                    "the official mobile tool package $id must be inserted",
                    overlay.contains("    - id: $id\n      name: '$name'")
                )
            }

            val marker = markerJson(config)
            assertEquals(DshGatewayConfigSeeder.MARKER_VERSION, marker.getInt("markerVersion"))
            assertEquals(DshGatewayConfigSeeder.PROFILE_TEMPLATE_VERSION, marker.getInt("templateVersion"))
            assertEquals("complete", marker.getString("phase"))
            assertTrue(
                "a prior-seed record outside the home is required to detect a later home loss",
                File(config.dataRoot, DshGatewayConfigSeeder.HOME_STATE_MARKER_NAME).isFile
            )
            assertNoAtomicTemps(config.dataRoot)
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ compaction capacity (ECO-5)

    @Test
    fun aSeededModelRowWithoutContextWindowIsReportedAsATypedCompactionGap() {
        val root = tempRoot("dsh-seeder-context-gap")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            val catalog = FakeCatalog(TWO_MODEL_CATALOG)
            val seeder = DshGatewayConfigSeeder(config)
            try {
                val outcome = seeder.seed(catalog.endpoint)
                assertTrue("the catalog must seed, was $outcome", outcome is DshSeedOutcome.Seeded)
            } finally {
                catalog.close()
            }

            val overlay = File(config.homeDir, "cordis.patch.yml").readText()
            // The declared window is written exactly as advertised.
            assertTrue(overlay.contains("          - id: \"mca-local:with-window\""))
            assertTrue(overlay.contains("            contextWindow: 8192"))
            assertTrue(
                "the advertised output budget must reach the official model row",
                overlay.contains("            maxTokens: 2048")
            )
            // The row without a window keeps the official shape: no fabricated contextWindow.
            assertTrue(overlay.contains("          - id: \"mca-cloud:no-window\""))
            val noWindowBlockStart = overlay.indexOf("mca-cloud:no-window")
            val noWindowBlock = overlay.substring(noWindowBlockStart)
            assertFalse(
                "a missing window must never be fabricated into the official row",
                noWindowBlock.lineSequence().take(4).any { it.trim().startsWith("contextWindow:") }
            )
            assertTrue(
                "the seeded file must make the compaction gap visible",
                overlay.contains(
                    "# mca: contextWindow unavailable; official compaction will refuse this model"
                )
            )
            // The typed, machine-readable form of the same fact (ECO-5).
            assertEquals(
                listOf(DshSeedContextWindowGap("mca-cloud:no-window")),
                seeder.lastContextWindowGaps
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aFullyDeclaredCatalogSeedsNoContextWindowGap() {
        val root = tempRoot("dsh-seeder-context-ok")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            val catalog = FakeCatalog(
                """{"object":"list","data":[
                    {"id":"mca-local:with-window","name":"With Window","context_window":4096}
                ]}"""
            )
            val seeder = DshGatewayConfigSeeder(config)
            try {
                val outcome = seeder.seed(catalog.endpoint)
                assertTrue("the catalog must seed, was $outcome", outcome is DshSeedOutcome.Seeded)
            } finally {
                catalog.close()
            }

            assertTrue(seeder.lastContextWindowGaps.isEmpty())
            val overlay = File(config.homeDir, "cordis.patch.yml").readText()
            assertTrue(overlay.contains("            contextWindow: 4096"))
            assertFalse(overlay.contains("# mca: contextWindow unavailable"))
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ idempotency / ownership

    @Test
    fun secondSeedIsIdempotentAndPreservesPluginManagerAndUserFiles() {
        val root = tempRoot("dsh-seeder-repeat")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            seedOnce(config)

            // Simulate the official plugin manager editing its files and a user editing settings.
            val profile = File(config.homeDir, "profiles/web")
            File(profile, "package.json").writeText(
                """{"name":"dsh-profile-web","private":true,"dependencies":{"some-plugin":"1.2.3"}}"""
            )
            File(profile, "pnpm-workspace.yaml").writeText("packages:\n  - .\n  - plugins/some-plugin\n")
            File(config.homeDir, "settings.yaml").writeText("ui-conversation:\n  busyEnter: queue\n")

            val outcome = seedOnce(config)
            assertTrue("steady-state reseed must succeed, was $outcome", outcome is DshSeedOutcome.Seeded)

            assertTrue(File(profile, "package.json").readText().contains("some-plugin"))
            assertTrue(File(profile, "pnpm-workspace.yaml").readText().contains("plugins/some-plugin"))
            assertEquals("ui-conversation:\n  busyEnter: queue\n", File(config.homeDir, "settings.yaml").readText())
            assertEquals(CORDIS_TEMPLATE, File(profile, "cordis.yml").readText())
            assertEquals("complete", markerJson(config).getString("phase"))
            assertNoAtomicTemps(config.dataRoot)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun currentHomeReseedRewritesTheOverlayWithPlatformPatchRows() {
        val root = tempRoot("dsh-seeder-platform-patch-reseed")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            seedOnce(config)

            // Simulate an older generation's overlay on an already-current home. The platform
            // rows must not depend on a template-version migration: every seed rewrites the
            // whole MCA-owned overlay, including for HomeDecision.Current.
            val overlayFile = File(config.homeDir, DshGatewayConfigSeeder.OVERLAY_NAME)
            overlayFile.writeText("# stale overlay without platform patch rows\n")
            assertFalse(overlayFile.readText().contains("bash-sandbox"))

            val outcome = seedOnce(config)
            assertTrue("a Current home must reseed its overlay, was $outcome", outcome is DshSeedOutcome.Seeded)

            val overlay = overlayFile.readText()
            assertPlatformPatchRows(overlay, config)
            assertFalse("the stale overlay must be replaced", overlay.contains("stale overlay"))
            assertTrue(
                "the provider overlay must still be regenerated",
                overlay.contains("apiKeyEnv: MCA_DSH_GATEWAY_TOKEN")
            )
            assertEquals("complete", markerJson(config).getString("phase"))
            assertNoAtomicTemps(config.dataRoot)
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ fail-closed data-loss guard

    @Test
    fun existingHomeWithoutProfileDirectoryFailsClosedAndIsNotRecreated() {
        val root = tempRoot("dsh-seeder-lost-profile")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            // Evidence of an existing DSH installation whose profile tree disappeared.
            File(config.homeDir, "sessions").mkdirs()
            File(config.homeDir, "sessions/keep.jsonl").writeText("{\"id\":\"keep\"}\n")
            File(config.homeDir, "credentials.json").writeText("{\"token\":\"user-owned\"}\n")

            val outcome = DshGatewayConfigSeeder(config).seed(unreachableEndpoint())
            assertEquals(DshSeedFailureCode.HOME_INCOMPLETE, (outcome as DshSeedOutcome.Failed).code)
            assertTrue(outcome.detail.contains("profiles/web"))

            assertFalse("a lost profile dir must not be recreated", File(config.homeDir, "profiles/web").exists())
            assertFalse(markerFile(config).exists())
            assertFalse(File(config.homeDir, "cordis.patch.yml").exists())
            assertTrue(File(config.homeDir, "sessions/keep.jsonl").isFile)
            assertTrue(File(config.homeDir, "credentials.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingHomeWithoutRequiredConfigDirectoryOrProfileFileFailsClosed() {
        val root = tempRoot("dsh-seeder-incomplete")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            writeCompleteUnmarkedHome(config)
            // config/ is required for MCA's config surface.
            assertTrue(config.configDir.deleteRecursively())

            val missingDir = DshGatewayConfigSeeder(config).seed(unreachableEndpoint())
            assertEquals(DshSeedFailureCode.HOME_INCOMPLETE, (missingDir as DshSeedOutcome.Failed).code)
            assertTrue(missingDir.detail.contains("config"))
            assertFalse(config.configDir.exists())

            // A complete home with one required profile template removed is also refused.
            writeCompleteUnmarkedHome(config)
            assertTrue(File(config.homeDir, "profiles/web/cordis.yml").delete())
            val missingFile = DshGatewayConfigSeeder(config).seed(unreachableEndpoint())
            assertEquals(DshSeedFailureCode.HOME_INCOMPLETE, (missingFile as DshSeedOutcome.Failed).code)
            assertTrue(missingFile.detail.contains("cordis.yml"))
            assertFalse(
                "a missing template file must not be silently recreated",
                File(config.homeDir, "profiles/web/cordis.yml").exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun homeDeletedAfterASuccessfulSeedFailsClosedEvenWithoutWorkspaceData() {
        val root = tempRoot("dsh-seeder-lost-home-record")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            seedOnce(config)
            // Both the home and the workspace are gone (a partial restore that kept `dataRoot`
            // but lost the user data); only the data-root seed record proves this is not first run.
            assertTrue(config.homeDir.deleteRecursively())
            config.workspaceDir.deleteRecursively()

            val outcome = DshGatewayConfigSeeder(config).seed(unreachableEndpoint())
            assertEquals(DshSeedFailureCode.HOME_INCOMPLETE, (outcome as DshSeedOutcome.Failed).code)
            assertTrue(outcome.detail.contains("prior successful seed"))
            assertFalse("a previously seeded home must not be recreated", config.homeDir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingHomeWithWorkspaceDataFailsClosedInsteadOfSeedingAReplacement() {
        val root = tempRoot("dsh-seeder-workspace-evidence")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            // The home is gone, but tool/user data from the previous generation survives.
            assertTrue(config.workspaceDir.mkdirs())
            File(config.workspaceDir, "project/notes.md").apply {
                parentFile.mkdirs()
                writeText("user work\n")
            }

            val outcome = DshGatewayConfigSeeder(config).seed(unreachableEndpoint())
            assertEquals(DshSeedFailureCode.HOME_INCOMPLETE, (outcome as DshSeedOutcome.Failed).code)
            assertTrue(outcome.detail.contains("workspace"))
            assertFalse("the lost home must not be replaced", config.homeDir.exists())
            assertTrue(File(config.workspaceDir, "project/notes.md").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun homePathThatIsARegularFileFailsClosed() {
        val root = tempRoot("dsh-seeder-home-file")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            assertTrue(config.homeDir.parentFile.isDirectory || config.homeDir.parentFile.mkdirs())
            config.homeDir.writeText("not a home\n")

            val outcome = DshGatewayConfigSeeder(config).seed(unreachableEndpoint())
            assertEquals(DshSeedFailureCode.HOME_NOT_A_DIRECTORY, (outcome as DshSeedOutcome.Failed).code)
            assertEquals("not a home\n", config.homeDir.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptMarkerFailsClosedInsteadOfBeingTreatedAsFirstRun() {
        val root = tempRoot("dsh-seeder-corrupt-marker")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            writeCompleteUnmarkedHome(config)
            markerFile(config).writeText("{ this is not json")
            val before = markerFile(config).readText()

            val outcome = DshGatewayConfigSeeder(config).seed(unreachableEndpoint())
            assertEquals(DshSeedFailureCode.MARKER_CORRUPT, (outcome as DshSeedOutcome.Failed).code)
            assertEquals("the corrupt marker must not be overwritten", before, markerFile(config).readText())
            assertFalse(File(config.homeDir, "cordis.patch.yml").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun markerFromANewerTemplateVersionFailsClosed() {
        val root = tempRoot("dsh-seeder-newer")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            writeCompleteUnmarkedHome(config)
            writeMarker(
                config,
                templateVersion = DshGatewayConfigSeeder.PROFILE_TEMPLATE_VERSION + 1,
                phase = "complete"
            )
            val oldCordis = File(config.homeDir, "profiles/web/cordis.yml").readText()

            val outcome = DshGatewayConfigSeeder(config).seed(unreachableEndpoint())
            assertEquals(
                DshSeedFailureCode.TEMPLATE_VERSION_UNSUPPORTED,
                (outcome as DshSeedOutcome.Failed).code
            )
            assertEquals(oldCordis, File(config.homeDir, "profiles/web/cordis.yml").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingPayloadTemplateFailsClosedWithoutCreatingAHome() {
        val root = tempRoot("dsh-seeder-no-template")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            val outcome = DshGatewayConfigSeeder(config).seed(unreachableEndpoint())
            assertEquals(DshSeedFailureCode.TEMPLATE_MISSING, (outcome as DshSeedOutcome.Failed).code)
            assertFalse(config.homeDir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ migration / resume

    @Test
    fun unmarkedLegacyHomeMigratesCordisAndPreservesInstalledPluginState() {
        val root = tempRoot("dsh-seeder-migrate")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            writeCompleteUnmarkedHome(config)
            val profile = File(config.homeDir, "profiles/web")
            // A crash leftover from the pre-atomic implementation must not win over the target.
            File(profile, ".cordis.yml.mca-tmp").writeText("stale garbage")

            val outcome = seedOnce(config)
            assertTrue("legacy complete home must migrate, was $outcome", outcome is DshSeedOutcome.Seeded)

            assertEquals(
                "the MCA-managed profile composition is refreshed by the version migration",
                CORDIS_TEMPLATE,
                File(profile, "cordis.yml").readText()
            )
            assertTrue(
                "plugin-manager dependencies must survive a template migration",
                File(profile, "package.json").readText().contains("some-plugin")
            )
            assertTrue(
                "plugin workspace entries must survive a template migration",
                File(profile, "pnpm-workspace.yaml").readText().contains("plugins/some-plugin")
            )
            assertEquals(
                "user settings must never be overwritten by a migration",
                USER_SETTINGS,
                File(config.homeDir, "settings.yaml").readText()
            )
            val marker = markerJson(config)
            assertEquals(DshGatewayConfigSeeder.PROFILE_TEMPLATE_VERSION, marker.getInt("templateVersion"))
            assertEquals("complete", marker.getString("phase"))
            assertNoAtomicTemps(config.dataRoot)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedFirstMarkerWriteLeavesOnlyATempAndStillSeeds() {
        val root = tempRoot("dsh-seeder-first-marker-crash")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            assertTrue(config.homeDir.mkdirs())
            // Crash window: the first `seeding` marker write created its temp but never renamed it.
            val temp = File(
                config.homeDir,
                ".${DshGatewayConfigSeeder.TEMPLATE_MARKER_NAME}${DshGatewayConfigSeeder.TEMP_SUFFIX}"
            )
            temp.writeText("half written marker", Charsets.UTF_8)

            val outcome = seedOnce(config)
            assertTrue(
                "a home containing only our atomic temp must still count as first run, was $outcome",
                outcome is DshSeedOutcome.Seeded
            )
            assertFalse("the stale marker temp must be cleaned up", temp.exists())
            assertEquals(CORDIS_TEMPLATE, File(config.homeDir, "profiles/web/cordis.yml").readText())
            assertNoAtomicTemps(config.dataRoot)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedSeedResumesFromTheTypedMarkerInsteadOfFailingClosed() {
        val root = tempRoot("dsh-seeder-resume")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            // Simulate a crash after the seeding marker and one atomic template copy: the home is
            // structurally incomplete, but it is OUR transaction, not lost user data.
            writeCompleteUnmarkedHome(config)
            assertTrue(File(config.homeDir, "profiles/web/cordis.yml").delete())
            assertTrue(File(config.homeDir, "profiles/web/pnpm-workspace.yaml").delete())
            writeMarker(config, DshGatewayConfigSeeder.PROFILE_TEMPLATE_VERSION, phase = "seeding")

            val outcome = seedOnce(config)
            assertTrue("an interrupted seed must resume, was $outcome", outcome is DshSeedOutcome.Seeded)

            assertEquals(CORDIS_TEMPLATE, File(config.homeDir, "profiles/web/cordis.yml").readText())
            assertEquals(WORKSPACE_TEMPLATE, File(config.homeDir, "profiles/web/pnpm-workspace.yaml").readText())
            assertEquals(PACKAGE_TEMPLATE, File(config.homeDir, "profiles/web/package.json").readText())
            assertEquals("complete", markerJson(config).getString("phase"))
            assertNoAtomicTemps(config.dataRoot)
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ catalog

    @Test
    fun unreadableCatalogFailsClosedBeforeTouchingTheHome() {
        val root = tempRoot("dsh-seeder-catalog")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)

            val outcome = DshGatewayConfigSeeder(config).seed(unreachableEndpoint())
            assertEquals(DshSeedFailureCode.CATALOG_UNAVAILABLE, (outcome as DshSeedOutcome.Failed).code)
            assertFalse("no home may be created when the catalog cannot be read", config.homeDir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun tempRoot(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    private fun seedOnce(config: DshRuntimeConfig): DshSeedOutcome {
        val catalog = FakeCatalog(EMPTY_CATALOG)
        return try {
            DshGatewayConfigSeeder(config).seed(catalog.endpoint)
        } finally {
            catalog.close()
        }
    }

    private fun unreachableEndpoint(): DshGatewayEndpoint =
        DshGatewayEndpoint("http://127.0.0.1:1/v1", "test-bearer-token", "rev-unreachable")

    private fun seedPayloadTemplate(config: DshRuntimeConfig) {
        val root = File(config.payloadDir, "dshhome")
        val profile = File(root, "profiles/web")
        assertTrue(profile.mkdirs() || profile.isDirectory)
        File(profile, "cordis.yml").writeText(CORDIS_TEMPLATE)
        File(profile, "package.json").writeText(PACKAGE_TEMPLATE)
        File(profile, "pnpm-workspace.yaml").writeText(WORKSPACE_TEMPLATE)
        File(root, "settings.yaml").writeText(DEFAULT_SETTINGS)
    }

    /** An existing home written by the pre-marker MCA seeder, including plugin/user edits. */
    private fun writeCompleteUnmarkedHome(config: DshRuntimeConfig) {
        val profile = File(config.homeDir, "profiles/web")
        assertTrue(profile.mkdirs() || profile.isDirectory)
        config.configDir.mkdirs()
        File(profile, "cordis.yml").writeText("bundles:\n  - legacy-bundle\n")
        File(profile, "package.json").writeText(
            """{"name":"dsh-profile-web","dependencies":{"some-plugin":"1.2.3"}}"""
        )
        File(profile, "pnpm-workspace.yaml").writeText("packages:\n  - .\n  - plugins/some-plugin\n")
        File(config.homeDir, "settings.yaml").writeText(USER_SETTINGS)
    }

    private fun markerFile(config: DshRuntimeConfig): File =
        File(config.homeDir, DshGatewayConfigSeeder.TEMPLATE_MARKER_NAME)

    private fun markerJson(config: DshRuntimeConfig): JSONObject =
        JSONObject(markerFile(config).readText(Charsets.UTF_8))

    private fun writeMarker(config: DshRuntimeConfig, templateVersion: Int, phase: String) {
        val marker = JSONObject()
            .put("markerVersion", DshGatewayConfigSeeder.MARKER_VERSION)
            .put("templateVersion", templateVersion)
            .put("phase", phase)
            .put("updatedAtEpochMillis", 0L)
        markerFile(config).writeText(marker.toString(), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ shell sandbox composition

    @Test
    fun aUsableLandlockRunnerKeepsTheOfficialSandboxExecutorAndNeverClaimsAdvisoryIsolation() {
        val root = tempRoot("dsh-seeder-kernel-sandbox")
        try {
            val runner = File(root, "libdshsandbox.so").apply { writeText("probe-stub") }
            val config = DshRuntimeConfig(dataRoot = root, sandboxRunnerFile = runner).validated()
            seedPayloadTemplate(config)
            assertTrue(config.homeDir.mkdirs())
            val catalog = FakeCatalog(EMPTY_CATALOG)
            val seeder = DshGatewayConfigSeeder(
                config,
                sandboxProbe = { _, _ -> DshSandboxProbeResult.Enforced(landlockAbi = 6, partial = false) }
            )
            try {
                val outcome = seeder.seed(catalog.endpoint)
                assertTrue("kernel composition must seed, was $outcome", outcome is DshSeedOutcome.Seeded)
            } finally {
                catalog.close()
            }

            val overlay = File(config.homeDir, "cordis.patch.yml").readText()
            assertTrue(overlay.contains("# mca-sandbox: kernel (landlock abi=6)"))
            assertTrue(
                "the official bash-sandbox provider must stay enabled",
                overlay.contains("- id: bash-sandbox\n  config:\n    timeoutMs: 60000")
            )
            assertFalse(
                "advisory bash-local must not replace the official executor",
                overlay.contains("id: bash-local")
            )
            assertTrue(
                "the official sandbox provider must receive the absolute runner path",
                overlay.contains("runnerCommand:\n      - ${yamlScalar(runner.absolutePath)}")
            )
            assertTrue(overlay.contains("runnerFailureSignatures:\n      - \"mca-dsh-sandbox: \""))
            assertTrue(overlay.contains("# mca:managed-insert"))
            for ((id, name) in TOOL_PACKAGES) {
                assertTrue("$id must stay inserted", overlay.contains("    - id: $id\n      name: '$name'"))
            }
            assertEquals(
                DshSandboxEnforcement.Kernel(landlockAbi = 6, partial = false),
                seeder.lastReport?.sandboxEnforcement
            )
            val status = DshShellSandboxStatusStore.read(root)
            assertTrue("the console status must be enforced", status?.enforced == true)
            assertEquals(6, status?.landlockAbi)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anEnforcedProbeWithoutARunnerFileDegradesToAdvisoryInsteadOfAdvertisingConfinement() {
        val root = tempRoot("dsh-seeder-kernel-without-file")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            assertTrue(config.homeDir.mkdirs())
            val catalog = FakeCatalog(EMPTY_CATALOG)
            val seeder = DshGatewayConfigSeeder(
                config,
                sandboxProbe = { _, _ -> DshSandboxProbeResult.Enforced(landlockAbi = 6, partial = false) }
            )
            try {
                val outcome = seeder.seed(catalog.endpoint)
                assertTrue("advisory fallback must seed, was $outcome", outcome is DshSeedOutcome.Seeded)
            } finally {
                catalog.close()
            }

            val overlay = File(config.homeDir, "cordis.patch.yml").readText()
            assertTrue(overlay.contains("# mca-sandbox: advisory (RUNNER_MISSING)"))
            assertTrue(overlay.contains("- id: bash-sandbox\n  disabled: true"))
            assertTrue(overlay.contains("    - id: bash-local"))
            assertFalse(overlay.contains("runnerCommand:"))
            assertEquals(
                DshSandboxEnforcement.Advisory(DshSandboxEnforcement.Reason.RUNNER_MISSING),
                seeder.lastReport?.sandboxEnforcement
            )
            val status = DshShellSandboxStatusStore.read(root)
            assertFalse("advisory must never be recorded as enforced", status?.enforced == true)
            assertEquals("RUNNER_MISSING", status?.advisoryReason)
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ catalog applied revision

    @Test
    fun aChangedCatalogIsRewrittenIntoTheNextOverlayInsteadOfLeavingStaleModelRows() {
        val root = tempRoot("dsh-seeder-catalog-revision")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            assertTrue(config.homeDir.mkdirs())

            val first = FakeCatalog(TWO_MODEL_CATALOG)
            try {
                val outcome = DshGatewayConfigSeeder(config).seed(first.endpoint)
                assertTrue("first catalog seed must succeed, was $outcome", outcome is DshSeedOutcome.Seeded)
            } finally {
                first.close()
            }
            val firstOverlay = File(config.homeDir, "cordis.patch.yml").readText()
            assertTrue(firstOverlay.contains("mca-local:with-window"))
            assertTrue(firstOverlay.contains("mca-cloud:no-window"))

            val second = FakeCatalog(
                """{"object":"list","data":[{"id":"mca-cloud:replacement","name":"Replacement","context_window":16384}]}"""
            )
            val secondSeeder = DshGatewayConfigSeeder(config)
            try {
                val outcome = secondSeeder.seed(second.endpoint)
                assertTrue("second catalog seed must succeed, was $outcome", outcome is DshSeedOutcome.Seeded)
            } finally {
                second.close()
            }
            val secondOverlay = File(config.homeDir, "cordis.patch.yml").readText()
            assertTrue(
                "the new catalog row must be applied to the overlay",
                secondOverlay.contains("mca-cloud:replacement")
            )
            assertFalse(
                "a removed model must not survive as a stale managed row",
                secondOverlay.contains("mca-local:with-window")
            )
            assertTrue(secondOverlay.contains("baseURL: \"${second.endpoint.baseUrl}\""))
            assertEquals(
                DshConfigProviderState.McaBound(second.endpoint.baseUrl, 1),
                secondSeeder.lastReport?.provider
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * C02c: the MCA overlay must carry the Android platform patch rows on every generation,
     * while never inheriting the reference template's dangerous default or its tool plugins.
     */
    private fun assertPlatformPatchRows(overlay: String, config: DshRuntimeConfig) {
        for (id in listOf(
            "bash-sandbox",
            "pwsh-sandbox",
            "session-log-download",
            "session-telemetry-otel"
        )) {
            assertTrue(
                "the platform patch must disable $id in the MCA overlay",
                overlay.contains("- id: $id\n  disabled: true")
            )
        }
        val quotedWorkspace = quoteForOverlay(config.workspaceDir.absolutePath)
        assertTrue(
            "bash-local must be inserted as the Android ctx.shell provider",
            overlay.contains(
                "- insert:\n" +
                    "    # mca:managed-insert\n" +
                    "    - id: bash-local\n" +
                    "      name: '@deepseek-ai/dsh-bash-local'\n" +
                    "      config:\n" +
                    "        cwd: $quotedWorkspace\n" +
                    "        timeoutMs: 60000"
            )
        )
        assertFalse(
            "cwd must be a literal MCA workspace path, not a deferred JS expression",
            overlay.contains("!!js")
        )
        assertTrue(
            "the MCA permission preset must stay workspace-write",
            overlay.contains("defaultPreset: workspace-write")
        )
        assertFalse(
            "the reference danger default must never be copied",
            overlay.contains("defaultPreset: danger-full-access")
        )
        assertFalse("approval: never must never be copied", overlay.contains("approval: never"))
        for (id in listOf("permission", "workspace-controller", "llm-pi-ai")) {
            assertTrue("the MCA-owned $id row must remain in the overlay", overlay.contains("- id: $id"))
        }
        assertFalse(
            "the official sandbox service must stay enabled: ptc-runtime injects it",
            overlay.contains("- id: sandbox\n  disabled: true")
        )
        for ((id, name) in TOOL_PACKAGES) {
            assertTrue(
                "the official mobile tool package $id must be inserted",
                overlay.contains("    - id: $id\n      name: '$name'")
            )
        }
    }

    private fun quoteForOverlay(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun assertNoAtomicTemps(root: File) {
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            assertFalse("stale atomic temp left behind: ${file.absolutePath}", file.name.contains(".mca-tmp"))
        }
    }

    /**
     * One-shot authenticated `/v1/models` listener. The seeder reads the catalog exactly once per
     * seed; tests that expect an earlier refusal never contact it.
     */
    private class FakeCatalog(private val body: String) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val authorization = AtomicReference<String?>(null)
        private val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("Authorization:", ignoreCase = true)) {
                            authorization.set(line.substringAfter(':').trim())
                        }
                        if (line.isEmpty()) break
                    }
                    val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                        "Connection: close\r\n\r\n$body"
                    socket.getOutputStream().apply {
                        write(response.toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
            }
        }

        val endpoint = DshGatewayEndpoint(
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            bearerToken = "test-bearer-token",
            catalogRevision = "rev-empty"
        )

        fun authorization(): String? = authorization.get()

        override fun close() {
            runCatching { server.close() }
            worker.join(2_000)
        }
    }

    private companion object {
        const val EMPTY_CATALOG = """{"object":"list","data":[]}"""
        const val TWO_MODEL_CATALOG = """{"object":"list","data":[
            {"id":"mca-local:with-window","name":"With Window","context_window":8192,
             "max_output_tokens":2048},
            {"id":"mca-cloud:no-window","name":"No Window"}
        ]}"""
        const val CORDIS_TEMPLATE = "bundles:\n  - @deepseek-ai/dsh-base\n"
        const val PACKAGE_TEMPLATE =
            """{"name":"dsh-profile-web","private":true,"dependencies":{}}"""
        const val WORKSPACE_TEMPLATE = "packages:\n  - .\n\nnodeLinker: hoisted\n"
        const val DEFAULT_SETTINGS = "ui-conversation:\n  busyEnter: steer\n"
        const val USER_SETTINGS = "ui-conversation:\n  busyEnter: queue\n"
        val TOOL_PACKAGES = listOf(
            "tool-shizuku" to "@deepseek-ai/dsh-tool-shizuku",
            "tool-android" to "@deepseek-ai/dsh-tool-android",
            "tool-accessibility" to "@deepseek-ai/dsh-tool-accessibility",
            "tool-vscreen" to "@deepseek-ai/dsh-tool-vscreen",
            "tool-browser" to "@deepseek-ai/dsh-tool-browser"
        )
    }
}


package com.muyuchat.core.dsh.runtime.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D13 plugin manager: why the model-facing plugin tools stay disabled while the host row and the
 * Web Plugins page are composed.
 *
 * The payload composition decides this, not MCA:
 *  - `dsh-base/cordis.patch.yml:20-22` inserts the **host** row `plugin-manager`
 *    (`@deepseek-ai/dsh-plugin-manager`) with `disabled: !!js "!ctx.get('profileContext')"`. A
 *    profile boot provides that service (`profile-boot` does `hostCtx.provide("profileContext",
 *    profileContext)`), so the host service the Plugins page talks to is composed enabled;
 *  - `dsh-base:16-18` inserts the model-facing `tool-plugin-manager`
 *    (`@deepseek-ai/dsh-plugin-manager/tools`) with `disabled: true`;
 *  - `dsh-web-app:477-478` disables that row again on the host plane because the agent plane moves
 *    into presets, and `presets/standard.patch.yml:123-128` (the default preset) inserts both
 *    `tool-plugin-manager` and `tool-plugin-market` (`dsh-plugin-market/tools`) explicitly
 *    `disabled: true`. The `ptc`/`cordis` presets instead gate them on `profileContext`, so the
 *    model-facing plugin tools are a per-preset decision, disabled in the shipped default;
 *  - the user-facing surface is composed separately: `ui-plugin-manager` (`dsh-web-app:315-319`,
 *    install/enable/disable/remove over the `pluginManager` Remote) and the read-only
 *    `ui-settings-plugin-inventory` (`:321-324`).
 *
 * So plugin install/remove is deliberately a *user* action over the official Remote, never a model
 * tool call. MCA must not flip that decision: a managed overlay row would either duplicate the
 * host row (same id in a higher layer silently replaces its config) or re-enable network/pnpm
 * mutations the payload disables on purpose. Whether the profile-backed host actually stays
 * enabled and the Plugins page round-trips an install is a device fact (round-34 saw the read-only
 * inventory page only; the install lifecycle remains NOT_RUN).
 *
 * No managed-overlay change is required. This fixture pins that decision from the overlay side:
 * MCA composes zero plugin-manager/tool-plugin-* rows, never treats them as MCA-owned (a user row
 * targeting them survives verbatim), and repeated reseeds stay byte-identical.
 *
 * Scope: JVM overlay composition only. It cannot start the official loader, so it proves the
 * composed overlay, not a running Plugins page or an actual install.
 */
class DshPluginManagerOverlayTest {

    @Test
    fun theManagedOverlayAddsNoPluginManagerRowAndNeverDisablesThePayloadDecision() {
        val root = tempRoot("dsh-plugin-overlay")
        try {
            val config = payloadTemplateConfig(root)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val overlay = overlayFile(config).readText()

            assertEquals(
                "MCA must not compose a second plugin-manager host row",
                0,
                countRows(overlay, "plugin-manager")
            )
            assertEquals(
                "the model-facing plugin tools stay disabled by the payload, not by MCA",
                0,
                countRows(overlay, "tool-plugin-manager")
            )
            assertEquals(0, countRows(overlay, "tool-plugin-market"))
            assertFalse(
                "the payload decides plugin rows; MCA's managed set must not claim them",
                DshManagedOverlay.isManagedBlock("plugin-manager")
            )
            assertFalse(DshManagedOverlay.isManagedBlock("tool-plugin-manager"))
            assertFalse(DshManagedOverlay.isManagedBlock("tool-plugin-market"))

            // MCA's own rows are still composed exactly once per generation.
            for (rowId in listOf(
                "bash-sandbox",
                "pwsh-sandbox",
                "session-log-download",
                "session-telemetry-otel",
                "permission",
                "workspace-controller"
            )) {
                assertEquals("$rowId must be composed exactly once", 1, countRows(overlay, rowId))
            }
            assertEquals(
                "the MCA insert block must be composed once",
                1,
                overlay.lines().count { it == "- insert:" }
            )
            assertTrue(overlay.contains("id: bash-local"))
            assertTrue(
                "a steady-state reseed must stay byte-identical",
                seeder.seedAccountOnly() is DshSeedOutcome.Seeded &&
                    overlay == overlayFile(config).readText()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aUserRowTargetingThePluginManagerSurvivesReseedsExactlyOnce() {
        val root = tempRoot("dsh-plugin-user-row")
        try {
            val config = payloadTemplateConfig(root)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val overlayTarget = overlayFile(config)
            // A user patch targeting the payload's host row: a higher layer replaces only that
            // row's config. MCA must preserve it verbatim instead of regenerating it.
            overlayTarget.appendText(USER_PLUGIN_ROW)

            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val firstReseed = overlayTarget.readText()
            assertEquals("the user row must survive once", 1, countRows(firstReseed, "plugin-manager"))
            assertEquals(
                listOf("plugin-manager"),
                seeder.lastReport!!.preservedUserRows.filter { it == "plugin-manager" }
            )
            assertEquals(
                "MCA still composes its own insert once",
                1,
                firstReseed.lines().count { it == "- insert:" }
            )
            assertTrue(
                "the user row's bytes must be preserved verbatim",
                firstReseed.contains("- id: plugin-manager\n  disabled: false\n")
            )

            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            assertEquals(
                "a second reseed must not duplicate or grow the user row",
                firstReseed,
                overlayTarget.readText()
            )
            assertEquals(1, countRows(overlayTarget.readText(), "plugin-manager"))
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun countRows(overlay: String, rowId: String): Int =
        overlay.lines().count { it.trim() == "- id: $rowId" }

    private fun overlayFile(config: DshRuntimeConfig): File =
        File(config.homeDir, DshGatewayConfigSeeder.OVERLAY_NAME)

    private fun payloadTemplateConfig(root: File): DshRuntimeConfig {
        val config = DshRuntimeConfig(dataRoot = root).validated()
        val templateRoot = File(config.payloadDir, "dshhome")
        val templateProfile = File(templateRoot, "profiles/web")
        assertTrue(templateProfile.mkdirs() || templateProfile.isDirectory)
        File(templateProfile, "cordis.yml").writeText("[]\n")
        File(templateProfile, "package.json").writeText(PACKAGE_TEMPLATE)
        File(templateProfile, "pnpm-workspace.yaml").writeText(WORKSPACE_TEMPLATE)
        File(templateRoot, "settings.yaml").writeText(SETTINGS_TEMPLATE)
        return config
    }

    private fun tempRoot(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    private companion object {
        const val PACKAGE_TEMPLATE = """{
  "name": "dsh-profile-web",
  "private": true,
  "dependencies": {},
  "dsh": {
    "profile": {
      "bundles": [
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app"
      ]
    }
  }
}
"""

        const val WORKSPACE_TEMPLATE = "packages:\n  - .\n\nnodeLinker: hoisted\n"

        const val SETTINGS_TEMPLATE = "ui-onboarding:\n  welcomeNoticeVersion: 2026-08-13.1\n"

        /**
         * A user-owned patch row addressed at the payload's host row. It carries no MCA marker and
         * is not in [DshManagedOverlay.MANAGED_ROW_IDS], so the merge must preserve it.
         */
        val USER_PLUGIN_ROW = "- id: plugin-manager\n  disabled: false\n"
    }
}

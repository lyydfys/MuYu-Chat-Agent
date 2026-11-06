package com.muyuchat.core.dsh.runtime.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Official-ecosystem rows owned by someone else must survive the MCA seed.
 *
 * `$DSH_HOME/cordis.patch.yml` is the home-level user patch layer the official loader applies over
 * every profile (`@deepseek-ai/dsh` profile-boot: `homePatchPath()`), and it is also the only
 * configuration surface MCA can reach on Android: the DSH home lives in app-private storage and the
 * Web UI has no MCP/plugin settings page. An MCP server or an installed plugin is therefore enabled
 * exactly by a patch row another owner wrote into that file — one instance of
 * `@deepseek-ai/dsh-mcp-client` per server, keyed by `serverName`.
 *
 * The seeder rewrites its own rows on every Android host generation. These tests pin the two facts a
 * user-visible regression would violate:
 *  - an official `dsh-mcp-client` row is preserved byte-for-byte and reported as a preserved row;
 *  - a steady-state reseed is idempotent: no duplicated row, no growth, no rewrite.
 *
 * They also pin the plugin-install manifest (`profiles/web/package.json`, owned by the official
 * plugin manager after the first seed) against a steady-state reseed.
 *
 * Scope note: this is a JVM fixture of the Kotlin merge/seeder contract. It does not start the
 * official Node kernel, so it is evidence that the *configuration path* is honoured, not that an
 * MCP server or plugin actually loads.
 */
class DshEcosystemOverlayRowTest {

    @Test
    fun anOfficialMcpClientRowSurvivesRepeatedGenerationsWithoutDuplication() {
        val root = tempRoot("dsh-ecosystem-mcp-row")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            // Inline payload-template fixture (the shared helper was not visible to this test's
            // first call site under the Kotlin compiler used by the gate).
            val templateRoot = File(config.payloadDir, "dshhome")
            val templateProfile = File(templateRoot, "profiles/web")
            assertTrue(templateProfile.mkdirs() || templateProfile.isDirectory)
            File(templateProfile, "cordis.yml").writeText(CORDIS_TEMPLATE)
            File(templateProfile, "package.json").writeText(PACKAGE_TEMPLATE)
            File(templateProfile, "pnpm-workspace.yaml").writeText(WORKSPACE_TEMPLATE)
            File(templateRoot, "settings.yaml").writeText(SETTINGS_TEMPLATE)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(
                "the first account-only seed must succeed",
                seeder.seedAccountOnly() is DshSeedOutcome.Seeded
            )

            val overlayFile = File(config.homeDir, DshGatewayConfigSeeder.OVERLAY_NAME)
            assertTrue("the seeder owns overlay creation", overlayFile.isFile)
            // One row written by another owner, in exactly the shape an MCP server takes in a DSH
            // patch sequence: the home patch layer is the only configuration surface on Android.
            overlayFile.appendText(MCP_CLIENT_ROW)

            assertTrue(
                "the reseed with a foreign row must still succeed",
                seeder.seedAccountOnly() is DshSeedOutcome.Seeded
            )
            val firstMerge = overlayFile.readText()
            assertEquals(
                "the official MCP row is not MCA-managed, so it must be reported as preserved",
                listOf("mcp-local-github"),
                seeder.lastReport!!.preservedUserRows
            )
            assertTrue(
                "the row must survive byte-for-byte, including its nested config block",
                firstMerge.contains(MCP_CLIENT_ROW)
            )
            assertEquals(
                "the preserved row must appear exactly once",
                1,
                firstMerge.lines().count { it.trim() == "- id: mcp-local-github" }
            )
            assertTrue(
                "MCA still owns and regenerates the Android platform rows in the same file",
                firstMerge.contains("- id: bash-sandbox\n  disabled: true")
            )
            assertTrue(
                "the MCA shell provider insert must not be duplicated or lost",
                firstMerge.contains("- insert:\n    # mca:managed-insert\n    - id: bash-local")
            )
            assertEquals(1, firstMerge.lines().count { it.trim() == "- id: bash-local" })
            assertEquals(
                "all five official mobile tool rows stay inserted",
                5,
                firstMerge.lines().count { it.trimStart().startsWith("- id: tool-") }
            )

            assertTrue(
                "a steady-state reseed must succeed",
                seeder.seedAccountOnly() is DshSeedOutcome.Seeded
            )
            assertEquals(
                "a steady-state reseed must reproduce the same file, not grow it",
                firstMerge,
                overlayFile.readText()
            )
            assertEquals(listOf("mcp-local-github"), seeder.lastReport!!.preservedUserRows)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anInstalledPluginManifestInTheProfileSurvivesASteadyStateReseed() {
        val root = tempRoot("dsh-ecosystem-plugin-manifest")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            // Inline payload-template fixture (the shared helper was not visible to this test's
            // first call site under the Kotlin compiler used by the gate).
            val templateRoot = File(config.payloadDir, "dshhome")
            val templateProfile = File(templateRoot, "profiles/web")
            assertTrue(templateProfile.mkdirs() || templateProfile.isDirectory)
            File(templateProfile, "cordis.yml").writeText(CORDIS_TEMPLATE)
            File(templateProfile, "package.json").writeText(PACKAGE_TEMPLATE)
            File(templateProfile, "pnpm-workspace.yaml").writeText(WORKSPACE_TEMPLATE)
            File(templateRoot, "settings.yaml").writeText(SETTINGS_TEMPLATE)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)

            // What `dsh plugin add` leaves behind: a dependency in the profile manifest and a
            // workspace entry, both owned by the official plugin manager after the first seed.
            val profile = File(config.homeDir, DshGatewayConfigSeeder.PROFILE_DIR_RELATIVE)
            val manifestFile = File(profile, "package.json")
            val workspaceFile = File(profile, "pnpm-workspace.yaml")
            manifestFile.writeText(INSTALLED_MANIFEST)
            workspaceFile.writeText(INSTALLED_WORKSPACE)

            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)

            assertEquals(
                "an installed plugin dependency must survive a steady-state reseed",
                INSTALLED_MANIFEST,
                manifestFile.readText()
            )
            assertEquals(
                "an installed plugin workspace entry must survive a steady-state reseed",
                INSTALLED_WORKSPACE,
                workspaceFile.readText()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun tempRoot(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    private companion object {
        const val CORDIS_TEMPLATE = "[]\n"

        const val PACKAGE_TEMPLATE = """{
  "name": "dsh-profile-web",
  "private": true,
  "dependencies": {},
  "dsh": {
    "profile": {
      "bundles": [
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app"
      ],
      "patchReload": "live"
    }
  }
}
"""

        const val WORKSPACE_TEMPLATE = "packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n"

        const val SETTINGS_TEMPLATE = "ui-onboarding:\n  welcomeNoticeVersion: 2026-08-13.1\n"

        /**
         * One official MCP server as DSH documents it: a `dsh-mcp-client` instance whose config is
         * discriminated on `transport`, requires `serverName` (public tools are
         * `mcp__<serverName>__<rawName>`), and carries the stdio command.
         */
        const val MCP_CLIENT_ROW = """- id: mcp-local-github
  name: '@deepseek-ai/dsh-mcp-client'
  config:
    transport: stdio
    serverName: github
    command: node
    args:
      - /data/user/0/com.muyuchat.mca/files/dsh/workspace/mcp/github.js
    env:
      GITHUB_TOKEN: from-env
    toolCallTimeoutMs: 60000
    failOnStartupError: false
"""

        const val INSTALLED_MANIFEST = """{
  "name": "dsh-profile-web",
  "private": true,
  "dependencies": {
    "@deepseek-ai/dsh-mcp-client": "0.2.2-rc.1"
  },
  "dsh": {
    "profile": {
      "bundles": [
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app"
      ],
      "patchReload": "live"
    }
  }
}
"""

        const val INSTALLED_WORKSPACE = "packages:\n  - .\n  - plugins/mcp-local\n\nnodeLinker: hoisted\n"
    }
}

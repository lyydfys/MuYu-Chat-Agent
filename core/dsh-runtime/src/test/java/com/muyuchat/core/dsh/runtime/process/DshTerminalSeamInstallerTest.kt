package com.muyuchat.core.dsh.runtime.process

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam installer is the only writer of its own overlay block. These tests pin the two things
 * that could silently break a boot: touching a home that is not seeded yet (data-loss guard) and
 * rewriting or dropping rows owned by the seeder or the user.
 */
class DshTerminalSeamInstallerTest {

    private val home = tempDirectory("dsh-terminal-seam-home")

    @After
    fun cleanUp() {
        home.walkBottomUp().forEach { it.delete() }
    }

    @Test
    fun itInstallsThePluginAndOneOwnedOverlayBlockWithoutTouchingOtherRows() {
        val overlayBefore = seededOverlay()
        val installer = DshTerminalSeamInstaller(home)

        val state = installer.install(SEAM_SOURCE)

        assertTrue("expected Active, was $state", state is DshTerminalSeamState.Active)
        assertEquals(SEAM_SOURCE, installer.pluginFile().readText())
        assertEquals(
            "profiles/web/node_modules/@mca/dsh-terminal-bridge/index.mjs",
            (state as DshTerminalSeamState.Active).pluginRelativePath
        )
        assertEquals("@mca/dsh-terminal-bridge", state.moduleSpecifier)

        val overlayAfter = File(home, "cordis.patch.yml").readText()
        // Every seeder-owned byte survives: the previous rows are a byte-prefix of the new file.
        assertTrue(overlayAfter.startsWith(overlayBefore.trimEnd()))
        assertEquals(1, occurrences(overlayAfter, "id: ${DshTerminalSeamInstaller.BLOCK_ID}"))
        assertTrue(overlayAfter.contains("- id: bash-local"))
        assertTrue(overlayAfter.contains("- id: tool-android"))
    }

    @Test
    fun aSecondInstallIsIdempotent() {
        seededOverlay()
        val installer = DshTerminalSeamInstaller(home)

        installer.install(SEAM_SOURCE)
        val first = File(home, "cordis.patch.yml").readText()
        val second = installer.install(SEAM_SOURCE)

        assertTrue(second is DshTerminalSeamState.Active)
        assertEquals(first, File(home, "cordis.patch.yml").readText())
        assertEquals(1, occurrences(first, "id: ${DshTerminalSeamInstaller.BLOCK_ID}"))
        // One seeder-owned insert block plus exactly one block of ours.
        assertEquals(2, occurrences(first, "- insert:"))
    }

    @Test
    fun aUserInsertBlockIsPreservedVerbatim() {
        val userBlock = """
            - insert:
                - id: user-own-plugin
                  name: 'some-user-package'
        """.trimIndent()
        val overlay = File(home, "cordis.patch.yml")
        seededOverlay()
        overlay.writeText(overlay.readText() + "\n" + userBlock + "\n")

        DshTerminalSeamInstaller(home).install(SEAM_SOURCE)

        val text = overlay.readText()
        assertTrue(text.contains(userBlock))
        assertEquals(1, occurrences(text, "id: user-own-plugin"))
        assertEquals(1, occurrences(text, "id: ${DshTerminalSeamInstaller.BLOCK_ID}"))
    }

    @Test
    fun aSharedInsertBlockThatAlreadyNamesTheSeamIsNeverRewritten() {
        seedProfileOnly()
        val shared = """
            - id: bash-sandbox
              disabled: true
            - insert:
                - id: bash-local
                  name: '@deepseek-ai/dsh-bash-local'
                - id: mca-terminal-bridge
                  name: '@mca/dsh-terminal-bridge'
                - id: tool-android
                  name: '@deepseek-ai/dsh-tool-android'
        """.trimIndent() + "\n"
        val overlay = File(home, "cordis.patch.yml")
        overlay.writeText(shared)
        val installer = DshTerminalSeamInstaller(home)

        val state = installer.install(SEAM_SOURCE)

        assertTrue("expected Active, was $state", state is DshTerminalSeamState.Active)
        assertEquals(
            DshTerminalSeamState.RowOwner.SHARED_BLOCK,
            (state as DshTerminalSeamState.Active).rowOwner
        )
        // The shared block is owned by the seeder: not one byte may change, and no second insert
        // row may be added (the plugin would then be applied twice).
        assertEquals(shared, overlay.readText())
        assertTrue(installer.pluginFile().isFile)
    }

    @Test
    fun aFirstRunHomeIsRefusedAndNothingIsCreated() {
        // No home directory at all: exactly the first-run state. Writing here would make the
        // seeder classify the home as non-empty without a template marker (data loss).
        val cold = File(home, "not-seeded-yet")

        val state = DshTerminalSeamInstaller(cold).install(SEAM_SOURCE)

        assertTrue(state is DshTerminalSeamState.Pending)
        assertEquals(
            DshTerminalSeamState.SkipCode.HOME_NOT_SEEDED,
            (state as DshTerminalSeamState.Pending).code
        )
        assertFalse(cold.exists())
    }

    @Test
    fun aHomeWithoutTheSeededProfileIsRefused() {
        File(home, "seams").mkdirs()

        val state = DshTerminalSeamInstaller(home).install(SEAM_SOURCE)

        assertTrue(state is DshTerminalSeamState.Pending)
        assertEquals(
            DshTerminalSeamState.SkipCode.PROFILE_MISSING,
            (state as DshTerminalSeamState.Pending).code
        )
        assertFalse(File(home, "cordis.patch.yml").exists())
    }

    @Test
    fun aSeededHomeWithoutTheOverlayIsPendingAndWritesNoOverlay() {
        seedProfileOnly()

        val state = DshTerminalSeamInstaller(home).install(SEAM_SOURCE)

        assertTrue(state is DshTerminalSeamState.Pending)
        assertEquals(
            DshTerminalSeamState.SkipCode.OVERLAY_MISSING,
            (state as DshTerminalSeamState.Pending).code
        )
        // The seeder owns overlay creation: a patch file with only our row would boot without the
        // platform rows and the bash-local shell provider.
        assertFalse(File(home, "cordis.patch.yml").exists())
        assertFalse(DshTerminalSeamInstaller(home).pluginFile().exists())
    }

    @Test
    fun aWrongSourceIsRejectedBeforeAnyWrite() {
        seededOverlay()
        val installer = DshTerminalSeamInstaller(home)

        for (source in listOf("", "   ", "// no seam here", "spawnTerminal but no env")) {
            val state = installer.install(source)
            assertTrue("expected failure for '$source'", state is DshTerminalSeamState.Failed)
            assertEquals(
                DshTerminalSeamState.FailureCode.SOURCE_REJECTED,
                (state as DshTerminalSeamState.Failed).code
            )
        }
        assertFalse(installer.pluginFile().exists())
    }

    @Test
    fun inspectIsReadOnlyAndReportsTheRealArtifacts() {
        seededOverlay()
        val installer = DshTerminalSeamInstaller(home)

        val before = installer.inspect(SEAM_SOURCE)
        assertTrue(before is DshTerminalSeamState.Pending)
        assertEquals(
            DshTerminalSeamState.SkipCode.PLUGIN_MISSING,
            (before as DshTerminalSeamState.Pending).code
        )
        assertFalse(installer.pluginFile().exists())

        installer.install(SEAM_SOURCE)
        assertTrue(installer.inspect(SEAM_SOURCE) is DshTerminalSeamState.Active)

        // A plugin whose bytes differ from this build's asset is stale, not active.
        installer.pluginFile().writeText(SEAM_SOURCE + "\n// edited on disk\n")
        val stale = installer.inspect(SEAM_SOURCE)
        assertEquals(
            DshTerminalSeamState.SkipCode.SOURCE_STALE,
            (stale as DshTerminalSeamState.Pending).code
        )
    }

    @Test
    fun theOverlayBlockNamesTheInstalledPackage() {
        seededOverlay()

        DshTerminalSeamInstaller(home).install(SEAM_SOURCE)

        val text = File(home, "cordis.patch.yml").readText()
        assertTrue(
            "overlay must name the plugin by its package specifier: $text",
            text.contains("      name: '@mca/dsh-terminal-bridge'")
        )
        // No absolute app-private path is written into the overlay for this row.
        assertFalse(text.contains(home.absolutePath))
    }

    @Test
    fun itWritesThePackageManifestThatMakesTheBareNameResolvable() {
        seededOverlay()
        val installer = DshTerminalSeamInstaller(home)

        installer.install(SEAM_SOURCE)

        assertTrue(installer.packageJsonFile().isFile)
        val manifest = installer.packageJsonFile().readText()
        assertTrue(manifest.contains("\"name\":\"@mca/dsh-terminal-bridge\""))
        assertTrue(manifest.contains("\"main\":\"index.mjs\""))

        // Without the manifest the bare name cannot resolve, so the seam is not "active".
        assertTrue(installer.packageJsonFile().delete())
        val state = installer.inspect(SEAM_SOURCE)
        assertTrue("expected Pending, was $state", state is DshTerminalSeamState.Pending)
        assertEquals(
            DshTerminalSeamState.SkipCode.PLUGIN_MISSING,
            (state as DshTerminalSeamState.Pending).code
        )
    }

    /** A realistic seeded overlay: MCA platform rows plus the mobile tool inserts. */
    private fun seededOverlay(): String {
        seedProfileOnly()
        val overlay = File(home, "cordis.patch.yml")
        overlay.writeText(
            """
            # MCA-owned DSH overlay. Regenerated for each Android DSH host generation.
            - id: bash-sandbox
              disabled: true
            - id: permission
              config:
                presets:
                  workspace-write:
                    sandbox: workspace-write
                    approval: ask
                defaultPreset: workspace-write
            - insert:
                - id: bash-local
                  name: '@deepseek-ai/dsh-bash-local'
                  config:
                    cwd: "/data/user/0/com.muyuchat.mca/files/dsh/workspace"
                    timeoutMs: 60000
                - id: tool-android
                  name: '@deepseek-ai/dsh-tool-android'
            """.trimIndent() + "\n"
        )
        return overlay.readText()
    }

    private fun seedProfileOnly() {
        val profile = File(home, "profiles/web")
        profile.mkdirs()
        File(profile, "cordis.yml").writeText("[]\n")
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private companion object {
        /** Shaped like the real asset: the installer only checks for the two load-bearing markers. */
        const val SEAM_SOURCE = """
            export const inject = ['subprocess']
            export function apply(ctx) {
              return ctx.subprocess.spawnTerminal
            }
            // reads APP_NOTIFY_PORT
        """
    }
}

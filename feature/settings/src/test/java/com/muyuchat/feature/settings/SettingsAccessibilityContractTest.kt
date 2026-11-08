package com.muyuchat.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards today's accessibility pass: actions that already show a text label must not repeat their
 * name through the leading icon, and the settings header uses the shared icon button. Glyph
 * assertions use the shared semantic catalog so callers cannot select a different icon family.
 */
class SettingsAccessibilityContractTest {

    @Test
    fun `labelled actions keep their icons decorative`() {
        val logs = source("feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsLogsPrivacyScreens.kt")
        assertTrue(logs.contains("Icon(McaIcons.Refresh, contentDescription = null)"))
        assertTrue(logs.contains("Icon(McaIcons.Description, contentDescription = null)"))
        assertTrue(logs.contains("Icon(McaIcons.ContentCopy, contentDescription = null)"))
        assertTrue(logs.contains("Icon(McaIcons.FileDownload, contentDescription = null)"))

        val web = source("feature/settings/src/main/java/com/muyuchat/feature/settings/WebSearchDiagnostics.kt")
        assertTrue(web.contains("Icon(McaIcons.ContentCopy, contentDescription = null)"))

        val api = source("feature/settings/src/main/java/com/muyuchat/feature/settings/LocalApiToolScreen.kt")
        assertFalse(api.contains("contentDescription = \"复制 Key\""))
        assertFalse(api.contains("contentDescription = \"复制本机地址\""))
        assertFalse(api.contains("contentDescription = \"复制局域网地址\""))
    }

    @Test
    fun `settings header back button uses the shared icon button`() {
        val screen = source("feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsScreens.kt")
        assertTrue(screen.contains("McaIconButton("))
        assertTrue(screen.contains("contentDescription = \"返回聊天\""))
    }

    @Test
    fun `standalone switches expose their visible action name`() {
        val namedSources = mapOf(
            "LocalApiToolScreen.kt" to listOf("本机调用", "开放端口"),
            "SettingsSearchScreen.kt" to listOf("启用联网检索", "抓取网页正文"),
            "SettingsLogsPrivacyScreens.kt" to listOf("持久化前缀缓存"),
            "SettingsRuntimeScreen.kt" to listOf("应用更新"),
        )
        namedSources.forEach { (file, names) ->
            val code = source("feature/settings/src/main/java/com/muyuchat/feature/settings/$file")
            names.forEach { name ->
                assertTrue("$file must name the $name switch", code.contains("contentDescription = \"$name\""))
            }
        }
        val diagnostics = source("feature/settings/src/main/java/com/muyuchat/feature/settings/WebSearchDiagnostics.kt")
        assertTrue(diagnostics.contains("contentDescription = title"))
    }

    @Test
    fun `cleanup actions delegate busy feedback to the stable shared button`() {
        val logs = source("feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsLogsPrivacyScreens.kt")
        listOf("busy", "chatHistoryBusy", "imageLibraryBusy", "fileLibraryBusy").forEach { busy ->
            assertTrue(
                "the $busy action must preserve its previous primary spinner color",
                Regex("""loading = $busy,\s*loadingIndicatorColor = MaterialTheme\.colorScheme\.primary,""")
                    .containsMatchIn(logs)
            )
        }
        assertFalse("inline spinners must not grow cleanup button content", logs.contains("CircularProgressIndicator("))
        // The clear button used to be gated on `!busy` alone. The owner serializes cleanups into a
        // single-flight slot, so the gate is now `interactive` = not busy AND no other cleanup
        // holds the slot; that is stricter, not looser.
        assertTrue(logs.contains("enabled = interactive,"))
        assertTrue(logs.contains("enabled = entryCount > 0 && interactive,"))
        assertTrue(logs.contains("val interactive = !busy && !blockedByCleanup"))
    }

    @Test
    fun `section selection has one tactile owner and does not tick for a reselected tab`() {
        // The chip strip became a dashboard grid: the grid ticks once per opened section and the
        // back gesture stays silent, so no path can fire two tactile events for one navigation.
        val screen = source("feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsScreens.kt")
        val hub = screen.substringAfter("private fun SettingsHubContent(")
            .substringBefore("private fun SettingsHubTopBar(")
        // The dashboard has two entry points that are each a navigation choice, so each owns exactly
        // one tick: opening a section from the grid, and opening settings search. The invariant being
        // pinned is "one tick per navigation action", not "one tick in the function" -- the search
        // entry is a user choice like any card, and leaving it silent would make it the only
        // tappable dashboard row with no tactile feedback.
        assertEquals(2, Regex("haptics\\.tick\\(\\)").findAll(hub).count())
        val searchEntry = hub.substringAfter("item(key = \"search\")")
            .substringBefore("item(key = \"sections-title\")")
        assertEquals(
            "the search entry is a navigation choice and must tick exactly once",
            1,
            Regex("haptics\\.tick\\(\\)").findAll(searchEntry).count()
        )
        val sectionGrid = hub.substringAfter("SettingsSectionGrid(")
        assertEquals(
            "the section grid must tick exactly once per opened section",
            1,
            Regex("haptics\\.tick\\(\\)").findAll(sectionGrid).count()
        )
        assertFalse(hub.contains("if (section != item)"))
        // Returning to the dashboard is navigation, not a selection change.
        val back = screen.substringAfter("val navigateBackToHub = {").substringBefore("}\n")
        assertFalse(back.contains("haptics.tick()"))
    }

    private fun source(relativePath: String): String =
        File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("project root not found")
    }
}

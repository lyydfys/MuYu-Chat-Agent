package com.muyuchat.feature.settings

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
        assertTrue(logs.contains("enabled = !busy,"))
    }

    @Test
    fun `section selection has one tactile owner and does not tick for a reselected tab`() {
        val screen = source("feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsScreens.kt")
        val chips = screen.substringAfter("SettingsSection.entries.forEach").substringBefore("AnimatedContent(")
        assertTrue(chips.contains("if (section != item) haptics.tick()"))
        assertTrue(chips.contains("hapticsEnabled = false"))
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

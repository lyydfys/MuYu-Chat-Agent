package com.muyuchat.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards today's accessibility pass: actions that already show a text label must not repeat their
 * name through the leading icon, and the settings header uses the shared icon button.
 */
class SettingsAccessibilityContractTest {

    @Test
    fun `labelled actions keep their icons decorative`() {
        val logs = source("feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsLogsPrivacyScreens.kt")
        assertTrue(logs.contains("Icon(Icons.Rounded.Refresh, contentDescription = null)"))
        assertTrue(logs.contains("Icon(Icons.Rounded.Description, contentDescription = null)"))
        assertTrue(logs.contains("Icon(Icons.Rounded.ContentCopy, contentDescription = null)"))
        assertTrue(logs.contains("Icon(Icons.Rounded.FileDownload, contentDescription = null)"))

        val web = source("feature/settings/src/main/java/com/muyuchat/feature/settings/WebSearchDiagnostics.kt")
        assertTrue(web.contains("Icon(Icons.Rounded.ContentCopy, contentDescription = null)"))

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

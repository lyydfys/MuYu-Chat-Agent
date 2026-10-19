package com.muyuchat.core.designsystem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The system-bar write touches window layout params, so it must be tied to the theme rather than
 * executed on every recomposition of the host.
 */
class McaThemeSystemBarContractTest {

    @Test
    fun `system bar colours are applied through a theme-keyed effect`() {
        val theme = source("core/designsystem/src/main/java/com/muyuchat/core/designsystem/McaTheme.kt")
        assertTrue(theme.contains("DisposableEffect(darkTheme)"))
        assertTrue(theme.contains("applySystemBarColors("))
        assertFalse("a per-recomposition write would run during streaming", theme.contains("SideEffect {"))
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

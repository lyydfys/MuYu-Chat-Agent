package com.muyuchat.feature.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAsyncStateContractTest {
    @Test
    fun cleanupBusyStateIsExplicitAndAggregate() {
        val idle = SettingsCleanupBusyUiState()
        assertFalse(idle.any)
        assertFalse(idle.chatHistory)
        assertFalse(idle.imageLibrary)
        assertFalse(idle.fileLibrary)
        assertFalse(idle.persistentPrefixCache)

        val active = SettingsCleanupBusyUiState(imageLibrary = true)
        assertTrue(active.any)
        assertTrue(active.imageLibrary)
        assertFalse(active.chatHistory)
        assertFalse(WebSearchSettingsUiState().operationBusy)
    }

    @Test
    fun settingsSurfaceConsumesOwnerBusyStateWithoutCoroutineHandles() {
        val sources = listOf(
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsScreens.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsLogsPrivacyScreens.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsSearchScreen.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/WebSearchDiagnostics.kt"
        ).joinToString("\n") { sourceFile(it) }

        assertFalse(sources.contains("import com.muyuchat.mca"))
        assertFalse(sources.contains("import kotlinx.coroutines.Job"))
        assertFalse(sources.contains("Deferred<"))
        assertFalse(sources.contains("setTimeout"))
        assertFalse(sources.contains("delay("))

        val search = sourceFile(
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsSearchScreen.kt"
        )
        assertTrue(search.contains("busy = state.operationBusy"))
        assertTrue(search.countOccurrences("enabled = !state.operationBusy") >= 6)

        val privacy = sourceFile(
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsLogsPrivacyScreens.kt"
        )
        assertTrue(privacy.contains("state.cleanupBusy.persistentPrefixCache"))
        assertTrue(privacy.contains("state.cleanupBusy.chatHistory"))
        assertTrue(privacy.contains("enabled = entryCount > 0 && interactive"))
        assertTrue(privacy.contains("enabled = chatSessionCount > 0 && !anyBusy"))
    }

    @Test
    fun everyPrivacyCleanupControlFollowsTheOwnersSingleFlightSlot() {
        val privacy = sourceFile(
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsLogsPrivacyScreens.kt"
        )

        // The owner runs one cleanup at a time and drops a concurrent request without any message,
        // so no control may stay armed while another cleanup holds the slot.
        assertTrue(privacy.contains("anyCleanupBusy = state.cleanupBusy.any"))
        assertTrue(
            privacy.contains(
                "blockedByCleanup = state.cleanupBusy.any && !state.cleanupBusy.persistentPrefixCache"
            )
        )
        assertTrue(privacy.contains("onCheckedChange = if (interactive) onEnabledChanged else null"))
        assertTrue(privacy.contains("enabled = entryCount > 0 && interactive"))
        assertTrue(privacy.contains("val anyBusy = anyCleanupBusy ||"))
        // An armed confirmation must not survive the wait, or one tap afterwards wipes the data.
        assertTrue(privacy.contains("LaunchedEffect(state.cleanupBusy.any)"))
        assertFalse(privacy.contains("if (busy) null else onEnabledChanged"))
    }

    private fun String.countOccurrences(value: String): Int =
        Regex(Regex.escape(value)).findAll(this).count()

    private fun sourceFile(relativePath: String): String =
        File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }
}

package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPrefixCacheSettingsContractTest {
    @Test
    fun settingsPersistToggleClearDiskStateAndInvalidateLiveConversation() {
        val source = sourceFile("MainViewModel.kt")
        // The preference key and its read/write moved to GenerationPreferenceStore.kt; the ViewModel
        // keeps forwarding members with the same names. Both files are read so the contract below
        // still covers the same facts, and the assertions are unchanged.
        val preferences = sourceFile("GenerationPreferenceStore.kt")
        val coordinator = sourceFile("CacheManagementCoordinator.kt")
        val both = source + preferences + coordinator
        val toggle = functionBody(coordinator, "setPersistentPrefixCacheEnabled")
        val clear = functionBody(coordinator, "schedulePersistentPrefixCacheClear")

        assertTrue(both.contains("PERSISTENT_PREFIX_CACHE_ENABLED_KEY"))
        assertTrue(source.contains("loadPersistentPrefixCacheEnabled"))
        assertTrue(both.contains("persistPersistentPrefixCacheEnabled"))
        assertTrue(toggle.contains("engine.setPersistentPrefixCacheEnabled(enabled)"))
        assertTrue(toggle.contains("markLocalConversationContextInvalid()"))
        assertTrue(clear.contains("engine.clearPersistentPrefixCache()"))
        // The run handle is owned by ChatRunOwnership now; the contract -- clearing waits for the
        // active generation job -- is unchanged.
        assertTrue(clear.contains("chatRuns.activePublishedJob()?.join()"))
    }

    @Test
    fun chatRequestOnlyOptsIntoDiskPrefixWhenTheUserSettingIsEnabled() {
        // P3 step 1 moved the startGeneration execution body to ChatExecutionOperations; the
        // assertions are unchanged -- they are read where they now live.
        val source = sourceFile("ChatExecutionOperations.kt")
        val startGeneration = functionBody(source, "ChatGenerationExecutionPort.runChatGeneration")

        assertTrue(startGeneration.contains("initialState.persistentPrefixCacheEnabled"))
        assertTrue(startGeneration.contains("persistentPrefixSystemPrompt = persistentLlamaPrefix"))
    }

    @Test
    fun settingsScreenReceivesUsageAndClearCallbacks() {
        // The callback wiring is still in MainActivity. The state field this checks moved to the
        // presentation package during P2, so it is read from there. Both assertions are unchanged
        // from before the move -- only the file each one reads.
        val source = sourceFile("MainActivity.kt")
        val mapperSource = sourceFile("presentation/SettingsUiStateMapping.kt")

        assertTrue(mapperSource.contains("persistentPrefixCacheEntryCount = persistentPrefixCacheEntryCount"))
        assertTrue(source.contains("onPersistentPrefixCacheEnabledChanged = viewModel::setPersistentPrefixCacheEnabled"))
        assertTrue(source.contains("onClearPersistentPrefixCache = viewModel::clearPersistentPrefixCache"))
    }

    private fun functionBody(source: String, name: String): String {
        val declaration = source.indexOf("fun $name")
        require(declaration >= 0) { "Missing function $name" }
        val openingBrace = source.indexOf('{', declaration)
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openingBrace + 1, index)
                }
            }
        }
        error("Unterminated function $name")
    }

    private fun sourceFile(name: String): String = sequenceOf(
        File("src/main/java/com/muyuchat/mca/$name"),
        File("app/src/main/java/com/muyuchat/mca/$name")
    ).firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
        ?: error("Unable to locate $name")
}

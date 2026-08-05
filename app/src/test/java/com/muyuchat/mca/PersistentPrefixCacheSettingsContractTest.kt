package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPrefixCacheSettingsContractTest {
    @Test
    fun settingsPersistToggleClearDiskStateAndInvalidateLiveConversation() {
        val source = sourceFile("MainViewModel.kt")
        val toggle = functionBody(source, "setPersistentPrefixCacheEnabled")
        val clear = functionBody(source, "schedulePersistentPrefixCacheClear")

        assertTrue(source.contains("PERSISTENT_PREFIX_CACHE_ENABLED_KEY"))
        assertTrue(source.contains("loadPersistentPrefixCacheEnabled"))
        assertTrue(source.contains("persistPersistentPrefixCacheEnabled"))
        assertTrue(toggle.contains("engine.setPersistentPrefixCacheEnabled(enabled)"))
        assertTrue(toggle.contains("markLocalConversationContextInvalid()"))
        assertTrue(clear.contains("engine.clearPersistentPrefixCache()"))
        assertTrue(clear.contains("generationJob?.takeIf { it.isActive }?.join()"))
    }

    @Test
    fun chatRequestOnlyOptsIntoDiskPrefixWhenTheUserSettingIsEnabled() {
        val source = sourceFile("MainViewModel.kt")
        val startGeneration = functionBody(source, "startGeneration")

        assertTrue(startGeneration.contains("initialState.persistentPrefixCacheEnabled"))
        assertTrue(startGeneration.contains("persistentPrefixSystemPrompt = persistentLlamaPrefix"))
    }

    @Test
    fun settingsScreenReceivesUsageAndClearCallbacks() {
        val source = sourceFile("MainActivity.kt")

        assertTrue(source.contains("persistentPrefixCacheEntryCount = persistentPrefixCacheEntryCount"))
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

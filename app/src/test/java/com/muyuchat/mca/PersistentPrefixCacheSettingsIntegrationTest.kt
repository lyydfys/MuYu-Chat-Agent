package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPrefixCacheSettingsIntegrationTest {
    @Test
    fun settingsHubReceivesPersistentPrefixCacheStateAndActions() {
        val source = sourceFile("MainActivity.kt")
        val settingsHub = callArguments(source, "SettingsHubScreen(")
        val mapper = callArguments(source, "SettingsUiState(")

        assertTrue(settingsHub.contains(
            "onPersistentPrefixCacheEnabledChanged = viewModel::setPersistentPrefixCacheEnabled"
        ))
        assertTrue(settingsHub.contains(
            "onClearPersistentPrefixCache = viewModel::clearPersistentPrefixCache"
        ))
        assertTrue(mapper.contains("persistentPrefixCacheEnabled = persistentPrefixCacheEnabled"))
        assertTrue(mapper.contains("persistentPrefixCacheEntryCount = persistentPrefixCacheEntryCount"))
        assertTrue(mapper.contains("persistentPrefixCacheBytes = persistentPrefixCacheBytes"))
    }

    private fun callArguments(source: String, marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "Missing call: $marker" }
        val openingParenthesis = source.indexOf('(', start)
        var depth = 0
        for (index in openingParenthesis until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(openingParenthesis + 1, index)
                }
            }
        }
        error("Unterminated call: $marker")
    }

    private fun sourceFile(name: String): String = sequenceOf(
        File("src/main/java/com/muyuchat/mca/$name"),
        File("app/src/main/java/com/muyuchat/mca/$name")
    ).firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
        ?: error("Unable to locate $name")
}

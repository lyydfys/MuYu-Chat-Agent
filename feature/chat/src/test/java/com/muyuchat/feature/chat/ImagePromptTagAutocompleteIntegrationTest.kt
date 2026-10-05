package com.muyuchat.feature.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptTagAutocompleteIntegrationTest {
    @Test
    fun `image workspace keeps textual inversion autocomplete without the dictionary manager`() {
        // The image workspace moved out of ChatScreen.kt into ImageWorkspaceScreens.kt. The subject
        // of this test is that workspace, so it reads both files rather than one: the assertions
        // below are unchanged, and reading both keeps them meaningful wherever each declaration
        // now lives.
        // P9/S6: 提示栏与 token 测量簇移入 ImagePromptBarScreens.kt（多源拼接随迁）。
        val source = sourceFile(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt"
        ) + sourceFile(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ImageWorkspaceScreens.kt"
        ) + sourceFile(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ImagePromptBarScreens.kt"
        )

        assertTrue(source.countOccurrences("ImagePromptTagAutocompleteProvider(") == 1)
        // P9/S6: ImageNegativePromptTagField moved to ImagePromptBarScreens.kt and had to widen from
        // private to internal there (it is the cross-file entry used by the workspace). The assertion
        // tracks the declaration, so it tracks the new modifier; the subject is unchanged.
        assertTrue(source.contains("internal fun ImageNegativePromptTagField("))
        assertTrue(source.countOccurrences("ImagePromptTagAssistPanel(") == 0)
        assertTrue(source.countOccurrences("TextFieldValue(") >= 4)
        assertTrue(source.countOccurrences("标签联想与词典设置") == 0)
        assertTrue(source.contains("onFocusChanged { focused = it.isFocused }"))
        assertFalse(source.contains("BasicTextField(\n                        value = prompt,"))
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            File(root, relativePath).takeIf(File::isFile)?.let { file ->
                return file.readText(Charsets.UTF_8)
            }
            directory = root.parentFile
        }
        error("Unable to locate $relativePath")
    }

    private fun String.countOccurrences(value: String): Int =
        windowed(value.length, step = 1, partialWindows = false).count { it == value }
}

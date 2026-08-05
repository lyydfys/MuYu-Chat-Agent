package com.muyuchat.feature.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptTagAutocompleteIntegrationTest {
    @Test
    fun `image workspace shares one autocomplete provider across positive and negative prompts`() {
        val source = sourceFile(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt"
        )

        assertTrue(source.countOccurrences("ImagePromptTagAutocompleteProvider(") == 1)
        assertTrue(source.contains("private fun ImageNegativePromptTagField("))
        assertTrue(source.countOccurrences("ImagePromptTagAssistPanel(") >= 2)
        assertTrue(source.countOccurrences("TextFieldValue(") >= 4)
        assertTrue(source.countOccurrences("标签联想与词典设置") >= 2)
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

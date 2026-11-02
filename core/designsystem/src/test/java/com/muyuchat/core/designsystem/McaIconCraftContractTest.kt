package com.muyuchat.core.designsystem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McaIconCraftContractTest {
    @Test
    fun `disabled plates neutralise selected defaults but preserve explicit caller colours`() {
        val source = designSystemSource("McaIconPlate.kt").replace(Regex("\\s+"), "")
        assertTrue(source.contains("containerColor?:if(selected&&enabled)McaTonal.accentFill(accent)elseMcaTonal.fill(enabled)"))
        assertTrue(source.contains("borderColor?:if(selected&&enabled)McaHairline.selected(accent)elseMcaHairline.default(enabled)"))
    }

    @Test
    fun `directional product actions mirror centrally and unselected pin keeps its outline`() {
        val source = designSystemSource("McaIcons.kt")
        mapOf(
            "Back" to "ArrowBack",
            "Forward" to "KeyboardArrowRight",
            "Previous" to "KeyboardArrowLeft",
            "Send" to "Send",
            "Undo" to "Undo",
            "Redo" to "Redo",
        ).forEach { (semantic, vector) ->
            assertTrue("$semantic must mirror in RTL", source.contains("$semantic: ImageVector = Icons.AutoMirrored.Rounded.$vector"))
        }
        assertTrue(source.contains("PinUnselected: ImageVector = Icons.Outlined.PushPin"))
        assertTrue(source.contains("PushPin: ImageVector = Icons.Rounded.PushPin"))
    }

    @Test
    fun `optical tokens preserve existing exceptional glyph and badge dimensions`() {
        val source = designSystemSource("McaTokens.kt")
        mapOf(
            "Inline" to 17,
            "Compact" to 19,
            "Menu" to 21,
            "Action" to 22,
            "Navigation" to 27,
            "DrawerBadge" to 29,
            "MenuBadge" to 30,
        ).forEach { (token, dp) ->
            assertTrue("$token must keep its frozen $dp dp value", source.contains("$token: Dp = $dp.dp"))
        }
    }

    @Test
    fun `destructive icon callers let the shared button own disabled tint`() {
        val memory = productSource("feature/chat/src/main/java/com/muyuchat/feature/chat/AssistantMemoryManagerDialog.kt")
        val adapters = productSource("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageInputAdapterPanels.kt")
        assertFalse(Regex("Icon\\(McaIcons.Delete,[^\\n]*tint\\s*=").containsMatchIn(memory))
        assertFalse(Regex("Icon\\(\\s*McaIcons.Delete,[^)]*\\btint\\s*=").containsMatchIn(adapters))
        assertTrue(memory.contains("enabled = !isSaving, glyphSize = McaIconSize.L, tint = MaterialTheme.colorScheme.error"))
        assertTrue(adapters.contains("enabled = !adapter.inUse && !loraImporting"))
        assertTrue(adapters.contains("enabled = !artifact.inUse && !textualInversionImporting"))
    }

    @Test
    fun `header new conversation glyph agrees with its existing medium slot`() {
        val header = productSource("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatStatusBarHeader.kt")
        val action = header.substringAfter("contentDescription = \"新建聊天\"")
            .substringBefore("contentDescription = \"打开应用菜单\"")
        assertTrue(action.contains("glyphSize = McaIconSize.M"))
        assertTrue(action.contains("modifier = Modifier.size(McaIconSize.M)"))
        assertFalse(action.contains("Modifier.size(21.dp)"))
    }

    @Test
    fun `production Kotlin package declarations precede every import including file opt in headers`() {
        val annotation = "@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)\n\n"
        val packageLine = "package com.muyuchat.feature.chat\n\n"
        val importLine = "import com.muyuchat.core.designsystem.McaIcons\n"
        // Reproduce the failed bulk insertion: the first blank belongs to @file, not package.
        assertFalse(packagePrecedesImports(annotation + importLine + packageLine))
        assertTrue(packagePrecedesImports(annotation + packageLine + importLine))
        assertTrue(packagePrecedesImports("\uFEFF" + annotation + packageLine + importLine))

        val repository = mcaRepositoryRoot()
        val invalidHeaders = listOf("app", "core", "feature").flatMap { module ->
            File(repository, module).walkTopDown()
                .onEnter { it.name !in setOf("build", ".gradle", ".git", "third_party", "vendor") }
                .filter { file ->
                    file.isFile && file.extension == "kt" &&
                        Regex("/src/main/(java|kotlin)/").containsMatchIn(file.invariantSeparatorsPath)
                }
                .filterNot { packagePrecedesImports(it.readText()) }
                .map { it.relativeTo(repository).invariantSeparatorsPath }
                .toList()
        }
        assertTrue("imports must follow the package declaration: $invalidHeaders", invalidHeaders.isEmpty())
    }

    private fun packagePrecedesImports(source: String): Boolean {
        // Valid BOM and file annotations are preserved; comment/example directives are not code.
        val code = source.removePrefix("\uFEFF").replace(
            Regex("(?s)/\\*.*?\\*/|//[^\\r\\n]*|\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\""),
        ) { match -> match.value.map { if (it == '\n') '\n' else ' ' }.joinToString("") }
        val packageStatement = Regex("(?m)^[\\t ]*package[\\t ]+[\\w.]+").find(code) ?: return false
        return Regex("(?m)^[\\t ]*import[\\t ]+").findAll(code)
            .all { it.range.first > packageStatement.range.first }
    }

    private fun productSource(path: String): String = File(mcaRepositoryRoot(), path).readText()
}

package com.muyuchat.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties
import java.nio.file.Files

class McaVisualDebtRatchetContractTest {
    private val repositoryRoot = mcaRepositoryRoot()

    @Test
    fun `legacy icon families do not exceed the captured workspace baseline`() {
        val baseline = baseline("legacy_icon_family_references")
        val current = productionSources(listOf("app", "core", "feature")).sumOf { source ->
            legacyIconReferences(source.readText())
        }

        assertTrue("legacy icon references grew from $baseline to $current", current <= baseline)
    }

    @Test
    fun `product icons are chosen only by the canonical semantic map`() {
        val baseline = baseline("direct_material_icon_access")
        val canonicalMap = File(repositoryRoot, "core/designsystem/src/main/java/com/muyuchat/core/designsystem/McaIcons.kt")
        val violations = productionSources(listOf("app", "core", "feature"))
            .filter { it != canonicalMap }
            .mapNotNull { source ->
                directIconAccess(source.readText()).takeIf { it > 0 }?.let { source to it }
            }
        val current = violations.sumOf { it.second }

        assertTrue(
            "direct Material icon access grew from $baseline to $current: " +
                violations.joinToString { it.first.relativeTo(repositoryRoot).invariantSeparatorsPath },
            current <= baseline,
        )
    }

    @Test
    fun `UI toast calls do not exceed the captured workspace baseline`() {
        val baseline = baseline("ui_toast_calls")
        val current = productionSources(listOf("app", "feature")).sumOf { source ->
            toastCalls(source.readText())
        }

        assertTrue("Toast calls grew from $baseline to $current", current <= baseline)
    }

    @Test
    fun `source scan includes java and kotlin trees while ignoring build output`() {
        val root = Files.createTempDirectory("mca-visual-ratchet").toFile()
        try {
            listOf(
                "feature/example/src/main/java/JavaTree.kt",
                "feature/example/src/main/kotlin/KotlinTree.kt",
                "feature/example/src/test/kotlin/TestOnly.kt",
                "feature/example/build/generated/src/main/kotlin/Generated.kt",
            ).forEach { path ->
                File(root, path).apply { requireNotNull(parentFile).mkdirs(); writeText("package fixture") }
            }
            assertEquals(
                setOf("JavaTree.kt", "KotlinTree.kt"),
                productionSources(listOf("feature"), root).map { it.name }.toSet(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `icon aliases and direct family imports cannot bypass the ratchet`() {
        val aliased = "import androidx.compose.material.icons.Icons as ProductIcons\n" +
            "val glyph = ProductIcons.AutoMirrored.Filled.Send"
        assertEquals(1, legacyIconReferences(aliased))
        assertTrue(directIconAccess(aliased) > 0)
        assertTrue(directIconAccess("import androidx.compose.material.icons.rounded.Close as CloseGlyph") > 0)
        assertTrue(directIconAccess("import androidx.compose.material.icons.rounded.*") > 0)
        assertTrue(directIconAccess("val glyph = androidx.compose.material.icons.Icons.Rounded.Close") > 0)
    }

    @Test
    fun `comments and example strings do not consume the migration baseline`() {
        val documentation = "// Icons.Default.Close\n" +
            "/* nested /* Icons.Filled.Add */ import androidx.compose.material.icons.Icons */\n" +
            "val example = \"Icons.Filled.Close; Toast.makeText(context, text, 0)\"\n" +
            "val raw = \"\"\"import androidx.compose.material.icons.rounded.Close\"\"\""
        assertEquals(0, legacyIconReferences(documentation))
        assertEquals(0, directIconAccess(documentation))
        assertEquals(0, toastCalls(documentation))
    }

    @Test
    fun `Toast aliases retain the same zero UI feedback debt baseline`() {
        assertEquals(1, toastCalls("import android.widget.Toast as PlatformToast\nPlatformToast.makeText(context, text, 0)"))
        assertEquals(1, toastCalls("import android.widget.Toast.makeText as showToast\nshowToast(context, text, 0)"))
    }

    private fun baseline(key: String): Int {
        val properties = Properties()
        javaClass.getResourceAsStream("/mca-visual-debt-baseline.properties").use { input ->
            requireNotNull(input) { "visual debt baseline resource is missing" }
            properties.load(input)
        }
        return requireNotNull(properties.getProperty(key)) { "visual debt baseline '$key' is missing" }.toInt()
    }

    private fun productionSources(moduleRoots: List<String>, repository: File = repositoryRoot): List<File> = moduleRoots
        .map { File(repository, it) }
        .filter(File::isDirectory)
        .flatMap { root ->
            root.walkTopDown()
                .onEnter { directory -> directory.name !in setOf("build", ".gradle", ".git", "third_party", "vendor") }
                .filter { file ->
                    file.isFile && file.extension == "kt" &&
                        Regex("/src/main/(java|kotlin)/").containsMatchIn(file.invariantSeparatorsPath)
                }
                .toList()
        }

    private fun legacyIconReferences(source: String): Int {
        val code = codeOnly(source)
        val names = importedNames(code, "androidx.compose.material.icons.Icons", "Icons")
        val references = names.sumOf { name ->
            Regex("\\b${Regex.escape(name)}\\s*\\.\\s*(?:AutoMirrored\\s*\\.\\s*)?(?:Default|Filled)\\s*\\.")
                .findAll(code).count()
        }
        val imports = Regex("(?m)^\\s*import\\s+androidx\\.compose\\.material\\.icons\\.(?:automirrored\\.)?filled\\.")
            .findAll(code).count()
        return references + imports
    }

    private fun directIconAccess(source: String): Int {
        val code = codeOnly(source)
        // Imports count too: aliases and wildcard imports must not reopen family selection in UI.
        return Regex("(?m)^\\s*import\\s+androidx\\.compose\\.material\\.icons(?:\\.|\\s|$)").findAll(code).count() +
            Regex("\\bIcons\\s*\\.\\s*(?:AutoMirrored\\s*\\.\\s*)?(?:Default|Filled|Rounded|Outlined|Sharp|TwoTone)\\s*\\.")
                .findAll(code).count()
    }

    private fun toastCalls(source: String): Int {
        val code = codeOnly(source)
        val receivers = importedNames(code, "android.widget.Toast", "Toast")
        val calls = receivers.sumOf { name ->
            Regex("\\b${Regex.escape(name)}\\s*\\.\\s*makeText\\s*\\(").findAll(code).count()
        }
        return calls + importedNames(code, "android.widget.Toast.makeText", "makeText")
            .filter { Regex("(?m)^\\s*import\\s+android\\.widget\\.Toast\\.makeText(?:\\s|$)").containsMatchIn(code) }
            .sumOf { name -> Regex("(?<![\\w.])${Regex.escape(name)}\\s*\\(").findAll(code).count() }
    }

    private fun importedNames(code: String, qualified: String, default: String): Set<String> = buildSet {
        add(default)
        Regex("(?m)^\\s*import\\s+${Regex.escape(qualified)}\\s+as\\s+(\\w+)")
            .findAll(code).forEach { add(it.groupValues[1]) }
    }

    /** Masks non-code without joining lines; nested comments and Kotlin raw strings are supported. */
    private fun codeOnly(source: String): String {
        val output = StringBuilder(source.length)
        var index = 0
        var blockDepth = 0
        var lineComment = false
        var rawString = false
        var quote: Char? = null
        var escaped = false
        fun masked(count: Int = 1) {
            repeat(count) { output.append(if (source[index++] == '\n') '\n' else ' ') }
        }
        while (index < source.length) {
            val char = source[index]
            when {
                lineComment -> { if (char == '\n') lineComment = false; masked() }
                blockDepth > 0 -> when {
                    source.startsWith("/*", index) -> { blockDepth++; masked(2) }
                    source.startsWith("*/", index) -> { blockDepth--; masked(2) }
                    else -> masked()
                }
                rawString -> if (source.startsWith("\"\"\"", index)) {
                    rawString = false; masked(3)
                } else masked()
                quote != null -> {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == quote -> quote = null
                    }
                    masked()
                }
                source.startsWith("//", index) -> { lineComment = true; masked(2) }
                source.startsWith("/*", index) -> { blockDepth = 1; masked(2) }
                source.startsWith("\"\"\"", index) -> { rawString = true; masked(3) }
                char == '\"' || char == '\'' -> { quote = char; masked() }
                else -> { output.append(char); index++ }
            }
        }
        return output.toString()
    }
}

package com.muyuchat.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the user-visible settings surface so no label or help text can disappear silently.
 *
 * `SettingsScreens.kt` is 2,961 lines and carries 429 distinct Chinese strings: section
 * titles, switch labels, help paragraphs, error text and button captions. It is the
 * largest user-visible surface in the app, and unlike the Local API it had no contract
 * test at all -- the only settings-named tests in the repository cover address
 * normalisation, one prefix-cache UI state and search diagnostics.
 *
 * The plan forbids hiding an entry or removing a setting to make a refactor easier, and
 * P3-P6 move code across module boundaries. A dropped label in a 2,961-line file is easy
 * to miss in review, so it is pinned here instead. This mirrors the route golden in
 * api:local: the check is a text contract, and losing a line means losing UI.
 *
 * Every string matters, including help text, because the help text is what tells a user
 * how a setting behaves; losing it changes the product as much as losing the switch.
 *
 * On the first run the fixture is written and the test fails on purpose so the generated
 * surface is reviewed before it is committed.
 */
class SettingsSurfaceGoldenTest {

    @Test
    fun `every user-visible settings string is still present`() {
        val actual = chineseLiterals()
        val golden = goldenFile()

        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(goldenText(actual))
            throw AssertionError(
                "Settings surface golden was missing and has been generated at $GOLDEN_PATH. " +
                    "Review it, commit it, then re-run."
            )
        }

        val frozen = golden.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val lost = frozen - actual.toSet()
        val added = actual - frozen.toSet()

        assertEquals(
            "settings strings disappeared from the UI; if a setting was intentionally " +
                "removed, that is a product change and needs its own task: $lost",
            emptyList<String>(),
            lost
        )
        assertEquals(
            "new settings strings appeared; add them to $GOLDEN_PATH once reviewed: $added",
            emptyList<String>(),
            added
        )
    }

    @Test
    fun `the settings surface is substantial enough for the golden to mean something`() {
        // A guard against the extraction silently breaking: if the regex stopped matching,
        // both sets would be empty and the comparison above would pass vacuously.
        val actual = chineseLiterals()
        assertTrue(
            "expected the settings screen to carry hundreds of visible strings, found ${actual.size}",
            actual.size >= 300
        )
        assertTrue(
            "the settings source must still exist at its expected path",
            sourceFile().isFile
        )
    }

    /** Quoted string literals containing at least one CJK character, sorted and de-duplicated. */
    private fun chineseLiterals(): List<String> =
        CHINESE_LITERAL.findAll(sourceFile().readText())
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()

    private fun goldenText(values: List<String>): String = buildString {
        appendLine("# User-visible Chinese strings in SettingsScreens.kt, sorted and de-duplicated.")
        appendLine("# Generated from the source; reviewed before committing. See SettingsSurfaceGoldenTest.")
        appendLine("# A line disappearing means a settings label or help text was removed from the UI.")
        values.forEach { appendLine(it) }
    }

    private fun goldenFile(): File = File(projectRoot(), GOLDEN_PATH)

    private fun sourceFile(): File = File(projectRoot(), SOURCE_PATH)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SOURCE_PATH =
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsScreens.kt"
        const val GOLDEN_PATH = "feature/settings/src/test/resources/settings-surface.golden.txt"

        /** A double-quoted literal that is not escaped and contains a CJK character. */
        val CHINESE_LITERAL = Regex("\"([^\"\\\\]*[\\u4e00-\\u9fff][^\"\\\\]*)\"")
    }
}

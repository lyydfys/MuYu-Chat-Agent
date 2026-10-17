package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the set of SharedPreferences files the app reads and writes.
 *
 * Losing a preference file means losing user data on upgrade: download state, generation
 * parameters, imported LoRAs and upscalers, app-update state and the image-tool
 * authorization all live in preferences. P3-P6 move code across module boundaries, so a
 * dropped or renamed `getSharedPreferences` call is exactly the kind of change that would
 * pass a compile and a behavioural test while silently orphaning a user's data.
 *
 * Why this checks file names and not keys. An earlier attempt to extract keys as well
 * attributed them by variable name and scope, and it was wrong: it reported
 * mca_generation_params as carrying mca_api's five keys, and mca_image_generation_ui_parameters
 * as carrying x_fraction and ball_size_dp, which belong to chat_device_status_overlay.
 * Getting names right needs no attribution at all -- just the set of literals and
 * same-file constants passed to getSharedPreferences -- so the reliable half is pinned
 * here and the unreliable half is deliberately left out.
 *
 * Two forms have to be matched, and both were found the hard way:
 *   - the name as a string literal, or as a constant defined in the same file
 *   - the call split across lines, as in `getSharedPreferences(\n    "name",\n    mode\n)`
 * A literal-only, line-by-line scan finds neither, which is how an earlier inventory
 * reported 12 preference files when there are 22.
 */
class SharedPreferencesSurfaceGoldenTest {

    @Test
    fun `every shared preferences file is still referenced`() {
        val actual = preferenceFileNames()
        val golden = goldenFile()

        if (!golden.exists()) {
            golden.parentFile!!.mkdirs()
            golden.writeText(goldenText(actual))
            throw AssertionError(
                "Preference golden was missing and has been generated at $GOLDEN_PATH. " +
                    "Review it, commit it, then re-run."
            )
        }

        val frozen = golden.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val lost = frozen - actual.toSet()
        val added = actual - frozen.toSet()

        assertEquals(
            "a preference file is no longer referenced; user data in it would be orphaned: $lost",
            emptyList<String>(),
            lost
        )
        assertEquals(
            "a new preference file appeared; add it to $GOLDEN_PATH once reviewed: $added",
            emptyList<String>(),
            added
        )
    }

    @Test
    fun `the scan still resolves constants and does not silently find nothing`() {
        val actual = preferenceFileNames()

        // Guards against the extraction breaking: an empty or tiny set would make the
        // comparison above pass vacuously.
        assertTrue("expected at least 20 preference files, found ${actual.size}", actual.size >= 20)
        assertTrue(
            "every name must be resolved; an unresolved constant means the extractor needs a fix",
            actual.none { it.startsWith(UNRESOLVED_PREFIX) }
        )
        // Names reached through a constant are the ones a literal-only scan misses, so pin
        // that at least one of them is present rather than trusting the regex.
        assertTrue(
            "names passed as constants must still be resolved",
            actual.containsAll(listOf("image_loras_v1", "mca_generation_params"))
        )
    }

    /** Sorted, de-duplicated preference file names across every main source set. */
    private fun preferenceFileNames(): List<String> {
        val names = sortedSetOf<String>()
        sourceFiles().forEach { file ->
            val text = file.readText()
            if (!text.contains("getSharedPreferences")) return@forEach
            val constants = CONSTANT.findAll(text)
                .associate { it.groupValues[1] to it.groupValues[2] }
            CALL.findAll(text).forEach { match ->
                val literal = match.groupValues[1]
                val identifier = match.groupValues[2]
                names += when {
                    literal.isNotEmpty() -> literal
                    constants.containsKey(identifier) -> constants.getValue(identifier)
                    // P9/S30: the ChatScreen split moved a getSharedPreferences call next to
                    // its sibling file while the constant stayed behind. Resolve a name that
                    // is unambiguous across sibling files of the same directory; a name with
                    // zero or several distinct values stays unresolved on purpose.
                    else -> unambiguousDirectoryConstant(file, identifier) ?: "$UNRESOLVED_PREFIX$identifier"
                }
            }
        }
        return names.toList()
    }

    /** The single distinct `const val` value for [name] across sibling .kt files, or null. */
    private fun unambiguousDirectoryConstant(file: File, name: String): String? =
        (file.parentFile?.listFiles()?.asSequence()?.toList() ?: emptyList())
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { candidate ->
                CONSTANT.findAll(candidate.readText())
                    .firstOrNull { it.groupValues[1] == name }
                    ?.groupValues?.get(2)
            }
            .toSet()
            .singleOrNull()

    private fun sourceFiles(): List<File> = SOURCE_ROOTS.flatMap { root ->
        File(projectRoot(), root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    private fun goldenText(values: List<String>): String = buildString {
        appendLine("# SharedPreferences file names referenced from main sources, sorted.")
        appendLine("# Generated from the source; reviewed before committing.")
        appendLine("# A line disappearing means user data stored under that file would be orphaned.")
        values.forEach { appendLine(it) }
    }

    private fun goldenFile(): File = File(projectRoot(), GOLDEN_PATH)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val GOLDEN_PATH = "app/src/test/resources/shared-preferences-files.golden.txt"
        const val UNRESOLVED_PREFIX = "«unresolved:"

        val SOURCE_ROOTS = listOf("app/src/main", "core", "feature", "api")

        /** `const val NAME = "value"`, used to resolve names passed as constants. */
        val CONSTANT = Regex("const\\s+val\\s+([A-Za-z_]\\w*)\\s*(?::\\s*String\\s*)?=\\s*\"([^\"]*)\"")

        /**
         * `getSharedPreferences(<literal or constant>,`. `\s` matches newlines in Kotlin's
         * regex by default, so a call split across lines is matched too.
         */
        val CALL = Regex("getSharedPreferences\\(\\s*(?:\"([^\"]+)\"|([A-Za-z_]\\w*))\\s*,")
    }
}

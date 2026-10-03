package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the single-writer rule for the shell's transient status line.
 *
 * `statusMessage` is App Shell state — global, short-lived, and previously assigned from 406 places
 * across `MainViewModel`. Those assignments were two genuinely different operations:
 *
 * 1. setting *only* the status, which is what `showStatus(...)` exists for; and
 * 2. updating the status **together with other fields in one `copy(...)`**, such as
 *    `copy(busy = false, statusMessage = "…")`.
 *
 * Only (1) was funnelled. Splitting (2) would turn one atomic state change into two, so it keeps its
 * atomic form deliberately — it is a different operation that happens to carry a status, not a
 * second writer of the same one.
 *
 * This test exists so the funnelled form cannot silently regress. It reads the source rather than
 * instantiating the ViewModel, because `MainViewModel` needs a real `Application` and 82
 * collaborators; the property being pinned is a property of the *code shape*, which is exactly what
 * a source contract test is for.
 */
class StatusMessageWriterContractTest {

    private val viewModelSource: String by lazy {
        sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
    }

    /** The shape that must no longer appear: a one-line status-only update. */
    private val singleFieldUpdate =
        Regex("""_uiState\.update\s*\{\s*(?:it|state)\.copy\(statusMessage\s*=""")

    /**
     * The funnel's own body.
     *
     * It is, by construction, a one-line status-only update — that is the whole point of the helper.
     * The scan below has to exempt exactly this line, otherwise the rule would forbid the very
     * implementation that satisfies it. Only this one line is exempt: any other one-line
     * status-only update is a bypass.
     */
    private val funnelBody = "_uiState.update { it.copy(statusMessage = message) }"

    /**
     * Scans [source] for one-line status-only updates that bypass the funnel.
     *
     * Split out so the positive control below can prove the scan actually detects one, instead of
     * the zero-result assertion passing because the pattern never matches anything at all.
     */
    private fun countSingleFieldStatusUpdates(source: String): Int =
        source.lineSequence().count { line ->
            line.trim() != funnelBody &&
                singleFieldUpdate.containsMatchIn(line) &&
                line.count { it == '{' } == line.count { it == '}' } &&
                line.count { it == '(' } == line.count { it == ')' }
        }

    @Test
    fun `no status-only update bypasses the funnel`() {
        assertEquals(
            "A one-line `_uiState.update { it.copy(statusMessage = …) }` must go through showStatus(...) instead.",
            0,
            countSingleFieldStatusUpdates(viewModelSource)
        )
    }

    @Test
    fun `the funnel is actually used`() {
        // The funnel is still *defined* once, on the ViewModel (see the test below). What moved is the
        // call sites: when a family is extracted, its `showStatus(...)` becomes `port.showStatus(...)`
        // in the extracted file. Counting both spellings across the package's own sources keeps the
        // same contract -- "the migrated sites really do go through the funnel" -- instead of pinning
        // a count that every extraction would otherwise have to lower.
        val sources = packageSources()
        val direct = Regex("""(?<![\w.])showStatus\(""").findAll(sources).count()
        val throughPort = Regex("""\.showStatus\(""").findAll(sources).count()
        val calls = direct + throughPort
        assertTrue("showStatus(...) should be called by the migrated sites, found $calls", calls >= 100)
    }

    @Test
    fun `the funnel is defined exactly once and writes the field it owns`() {
        val declarations = Regex("""private fun showStatus\(message: String\?\)""").findAll(viewModelSource).count()
        assertEquals("showStatus must have a single definition", 1, declarations)
        assertTrue(
            "the funnel must be the plain status-only update it replaced",
            viewModelSource.contains("_uiState.update { it.copy(statusMessage = message) }")
        )
    }

    @Test
    fun `atomic multi-field updates are deliberately left alone and still exist`() {
        // If this ever reaches zero, either every such site was funnelled (which would have split
        // atomic updates) or the field was renamed. Both need a human decision, not a silent pass.
        val atomic = Regex("""copy\([^)\n]*,\s*statusMessage\s*=""").findAll(viewModelSource).count()
        assertTrue(
            "atomic copies that carry a status alongside other fields should still be present, found $atomic",
            atomic > 0
        )
    }

    // ── Positive control ────────────────────────────────────────────────────────────────

    @Test
    fun `the scan detects a violation when one is present`() {
        // Without this, `assertEquals(0, …)` above could pass because the scanner is broken.
        val violating = """
            fun example() {
                _uiState.update { it.copy(statusMessage = "regression") }
            }
        """.trimIndent()
        assertEquals("the scanner must see the violating line", 1, countSingleFieldStatusUpdates(violating))

        val compliant = """
            fun example() {
                showStatus("funnelled")
            }
        """.trimIndent()
        assertEquals("the scanner must not flag the funnelled form", 0, countSingleFieldStatusUpdates(compliant))

        // A multi-line atomic copy must not be counted as a single-field update either.
        val atomic = """
            fun example() {
                _uiState.update {
                    it.copy(
                        busy = false,
                        statusMessage = "done"
                    )
                }
            }
        """.trimIndent()
        assertEquals("a multi-line atomic copy must not be flagged", 0, countSingleFieldStatusUpdates(atomic))
    }

    /**
     * Every `.kt` file in the app package, concatenated.
     *
     * Resolved the same way [sourceFile] resolves a single path, then listing that directory, so the
     * helper keeps working from whatever working directory the test runner picks.
     */
    private fun packageSources(): String {
        val relative = "app/src/main/java/com/muyuchat/mca/MainViewModel.kt"
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidate = File(root, relative)
            if (candidate.isFile) {
                val packageDir = candidate.parentFile
                return packageDir.listFiles { file -> file.extension == "kt" }
                    ?.sortedBy { it.name }
                    ?.joinToString("\n") { it.readText(Charsets.UTF_8) }
                    ?: error("Unable to list $packageDir")
            }
            directory = root.parentFile
        }
        error("Unable to locate $relative")
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            File(root, relativePath).takeIf(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("Unable to locate $relativePath")
    }
}

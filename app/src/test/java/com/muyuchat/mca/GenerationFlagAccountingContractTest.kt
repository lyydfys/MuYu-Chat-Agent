package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the accounting for `MainUiState.isGenerating`.
 *
 * ## Correction: this test was first written on a wrong premise
 *
 * Its first version said the flag "mirrors state that `UiGenerationOwnership` owns authoritatively"
 * and that there was **no** named transition funnel. Both halves were misleading, and the second
 * was simply wrong:
 *
 * - `app/src/main/java/com/muyuchat/mca/presentation/GenerationStateTransitions.kt` already holds
 *   the generation-lifecycle transitions as **pure functions**, extracted verbatim from
 *   `MainViewModel` during P2: `afterGenerationStarted`, `afterGenerationTerminated`,
 *   `afterGenerationCompleted`, `afterBackgroundGenerationStopped`,
 *   `afterClearChatGenerationStopped`, `afterNativeRuntimeReleased` and
 *   `restoreAfterConversationMutationFailure`. Its header states the point — "having them in one
 *   file makes it possible to read that sequence without opening a 22000-line ViewModel" — and
 *   documents why the lock is cleared in more than one place: the user-facing generation lock must
 *   be released *before* completion bookkeeping that can cross a Binder or persistence boundary.
 *
 * - The first version also scanned **only** `MainViewModel.kt` while asserting repo-wide totals, so
 *   it would have reported a clean result while six further writes sat in
 *   `GenerationStateTransitions.kt`. A test that pins an incomplete accounting is worse than no
 *   test, because it claims coverage it does not have.
 *
 * The two files scanned below are the ones that write `MainUiState.isGenerating`. The other
 * `isGenerating = …` occurrences in the repository are not this field: they are Compose parameters
 * (for example `AnimatedMcaAssistantMark(isGenerating = true, …)` in `ChatChromeScreens.kt`) or
 * pass-throughs inside `copy(...)` of some other state type.
 *
 * ## The picture this version pins
 *
 * Writes fall into two groups:
 *
 * 1. **through a named transition or the run-id guard** — the `afterGeneration*` functions in
 *    `GenerationStateTransitions.kt`, or `updateGenerationUi { … }` / `settleGenerationUi(…)` in
 *    `MainViewModel`, both of which check `generationRunSequence.get() == generationRunId` first.
 *    This is the designed path, and it is what keeps a superseded generation from clearing a newer
 *    one's flag.
 * 2. **raw assignments** — the handful that write the field directly. These are the ones worth
 *    auditing, because they bypass both the named transitions and the run-id guard.
 *
 * ## What this test does and does not claim
 *
 * It does not claim a defect. It refuses to let the accounting drift silently: the counts below are
 * the measured baseline, so adding or moving a write site fails here and forces a human to decide
 * which group the new site belongs to.
 *
 * Deriving the flag from `UiGenerationOwnership`, or funnelling every raw write through a named
 * transition, is a change to a concurrency guard and needs real-device verification of the
 * start/stop/cancel/error paths. It is deliberately not attempted here.
 */
class GenerationFlagAccountingContractTest {

    private val mainViewModel: String by lazy {
        sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
    }
    private val transitions: String by lazy {
        sourceFile("app/src/main/java/com/muyuchat/mca/presentation/GenerationStateTransitions.kt")
    }

    /**
     * Measured baseline across the two files that own the flag's transitions.
     *
     * A change here is a prompt to re-derive the grouping, not a number to update silently.
     */
    private val expectedTrueInViewModel = 3
    private val expectedFalseInViewModel = 11
    private val expectedTrueInTransitions = 1
    private val expectedFalseInTransitions = 5

    private val assignment = Regex("""(?<![\w.])isGenerating\s*=\s*(true|false)\b""")

    /** Every `isGenerating = true|false` in [source], as (line index, value). */
    private fun assignments(source: String): List<Pair<Int, String>> =
        source.lineSequence().withIndex().mapNotNull { (index, line) ->
            assignment.find(line)?.let { index to it.groupValues[1] }
        }.toList()

    /**
     * Counts resets inside a `finally`, walking back from each reset but stopping at the enclosing
     * function's boundary.
     *
     * The walk stops at a **function boundary** as well as at `try`. Without that stop it crosses
     * into the previous function and credits a neighbour's `finally` to this reset — which is
     * exactly what the positive control below caught when this scanner was first written.
     */
    private fun exceptionSafeResets(source: String): Int {
        val lines = source.lines()
        // Leading whitespace is optional: the real file indents members, while the synthetic
        // samples in the positive controls are written flush left after trimIndent().
        val functionBoundary = Regex("""^\s*(?:private |internal |public |protected |override |open |operator )*(?:suspend )?fun\s""")
        val typeBoundary = Regex("""^\s*(?:private |internal |public )*(?:object|class|interface|companion object)\s""")
        return assignments(source).count { (index, value) ->
            if (value != "false") return@count false
            var insideFinally = false
            for (cursor in index - 1 downTo maxOf(0, index - 60)) {
                val line = lines[cursor]
                if (Regex("""\bfinally\b""").containsMatchIn(line)) {
                    insideFinally = true
                    break
                }
                if (Regex("""\btry\b\s*\{""").containsMatchIn(line)) break
                if (functionBoundary.containsMatchIn(line) || typeBoundary.containsMatchIn(line)) break
            }
            insideFinally
        }
    }

    // ── The designed path exists ────────────────────────────────────────────────────────

    @Test
    fun `the named generation transitions still exist and still set the flag`() {
        // If these disappear, the accounting below describes a different design and this test must
        // be re-derived rather than adjusted.
        listOf(
            "fun MainUiState.afterGenerationStarted(",
            "fun MainUiState.afterGenerationTerminated(",
            "fun MainUiState.afterBackgroundGenerationStopped(",
            "fun MainUiState.afterClearChatGenerationStopped(",
            "fun MainUiState.afterNativeRuntimeReleased("
        ).forEach { signature ->
            assertTrue("GenerationStateTransitions should still define $signature", transitions.contains(signature))
        }
        assertTrue("afterGenerationStarted should set the flag", transitions.contains("isGenerating = true"))
        assertTrue("afterGenerationTerminated should clear the flag", transitions.contains("isGenerating = false"))
    }

    @Test
    fun `the run id guard is still what keeps a stale generation from clearing a newer flag`() {
        // The guard is the same rule expressed through the ownership seam: matches() is the strict
        // "an id was given and the epoch equals it" form the old raw comparison spelled out.
        val guard = Regex("""fun generationStillOwnsUi\(\): Boolean\s*=\s*chatRuns\.matches\(generationRunId\)""")
        assertTrue(
            "the ownership guard is the reason a superseded generation cannot clear a newer flag",
            guard.containsMatchIn(mainViewModel)
        )
        assertTrue(
            "the guarded update wrapper should still exist",
            mainViewModel.contains("fun updateGenerationUi(transform:")
        )
    }

    // ── The accounting ──────────────────────────────────────────────────────────────────

    @Test
    fun `the entry points that start a generation are the recorded ones`() {
        val trues = assignments(mainViewModel).filter { it.second == "true" }
        assertEquals(
            "A new raw `isGenerating = true` in MainViewModel needs a matching, guarded reset.",
            expectedTrueInViewModel,
            trues.size
        )
    }

    @Test
    fun `the raw writes in each file are the recorded ones`() {
        val falseInViewModel = assignments(mainViewModel).count { it.second == "false" }
        val trueInTransitions = assignments(transitions).count { it.second == "true" }
        val falseInTransitions = assignments(transitions).count { it.second == "false" }
        assertEquals("MainViewModel resets changed", expectedFalseInViewModel, falseInViewModel)
        assertEquals("GenerationStateTransitions starts changed", expectedTrueInTransitions, trueInTransitions)
        assertEquals("GenerationStateTransitions resets changed", expectedFalseInTransitions, falseInTransitions)
    }

    @Test
    fun `the start sites still live in the recorded functions`() {
        val lines = mainViewModel.lines()
        fun ownerOf(index: Int): String {
            for (cursor in index downTo 0) {
                Regex("""^    (?:private |internal |public )?(?:suspend )?fun (\w+)\(""")
                    .find(lines[cursor])?.let { return it.groupValues[1] }
            }
            return "?"
        }
        val owners = assignments(mainViewModel).filter { it.second == "true" }.map { ownerOf(it.first) }.toSet()
        assertEquals(
            "Start sites moved; this test's KDoc lists the entry points and must move with them.",
            setOf("sendPreparedMessageAsync", "continueAssistantImageToolTurn", "regenerateLastResponse"),
            owners
        )
    }

    @Test
    fun `no reset in either file is reached through a finally`() {
        // Recorded so that a change in either direction is noticed: reaching a reset through a
        // `finally` would be an improvement worth recording, losing one would be a regression.
        assertEquals("MainViewModel", 0, exceptionSafeResets(mainViewModel))
        assertEquals("GenerationStateTransitions", 0, exceptionSafeResets(transitions))
    }

    // ── Positive controls ───────────────────────────────────────────────────────────────

    @Test
    fun `the scanner detects start and reset sites in a synthetic sample`() {
        val sample = """
            fun a() {
                copy(isGenerating = true)
            }
            fun b() {
                try {
                    work()
                } finally {
                    copy(isGenerating = false)
                }
            }
            fun c() {
                copy(isGenerating = false)
            }
        """.trimIndent()
        val found = assignments(sample)
        assertEquals("two resets and one start", 3, found.size)
        assertEquals(1, found.count { it.second == "true" })
        assertEquals(2, found.count { it.second == "false" })
        assertEquals("only the reset inside finally counts as exception safe", 1, exceptionSafeResets(sample))
    }

    @Test
    fun `the scanner does not count reads or unrelated identifiers`() {
        val sample = """
            fun a(state: MainUiState) {
                if (state.isGenerating) return
                val mirrored = state.isGenerating
                val other = state.busy
            }
        """.trimIndent()
        assertEquals("reads are not assignments", 0, assignments(sample).size)
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

package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the accounting for `MainUiState.isGenerating`, which currently mirrors state that
 * `UiGenerationOwnership` already owns authoritatively.
 *
 * ## What the measurement showed
 *
 * `isGenerating` is set to `true` from **3** entry points (`sendPreparedMessageAsync`,
 * `continueAssistantImageToolTurn`, `regenerateLastResponse`) and reset to `false` from **11**
 * places spread over 7 functions — six of them inside `startGeneration` alone, and two of them
 * (`ensureLocalConversationContextInvalidated`, `persistConversationMutation`) in functions that
 * have nothing to do with a generation ending.
 *
 * **None of the 11 resets sits in a `finally`.** That number was first measured as 1; the positive
 * control below caught that the scanner was walking back past a function boundary and crediting a
 * neighbour's `finally` to the wrong reset. Corrected, the count is zero: every reset is reached
 * only if control flows to it normally.
 *
 * Meanwhile `UiGenerationOwnership` — a synchronised component with a monotonic run id, an owner
 * identity and an explicit phase (`reserveStart`/`activate`/`markPhase`/`finish`/`cancelCurrent`)
 * — tracks the same fact authoritatively, and has its own unit test.
 *
 * ## Why that matters
 *
 * `isGenerating` is not just a label. It is a **concurrency guard** read at roughly twenty sites
 * (`sendMessage`, `deleteMessage`, `importWorldBook`, `importAssistantCard`, `saveAssistantProfile`
 * and others all return early when it is set), it drives `AgentEngineLifecycle.GENERATING`, it
 * decides the Local API busy code, and `LocalChatReadiness` renders it as "生成中".
 *
 * So if the flag ever sticks at `true` — one entry point whose reset is not reached because an
 * exception, cancellation or early return skipped it — those guards keep failing and the app stops
 * accepting writes until the process restarts.
 *
 * ## What this test does and does not claim
 *
 * It does **not** claim the flag is currently stuck, and it does not assert a bug. What it does is
 * refuse to let the accounting change silently: the counts below are the recorded baseline, so
 * adding or moving a write site fails here and forces a human to re-derive the pairing — which is
 * exactly the review that a manual mirror of an authoritative component needs.
 *
 * The real fix (deriving the flag from `UiGenerationOwnership`, or funnelling every reset through
 * one exception-safe call) is a change to a concurrency guard and needs real-device verification of
 * generation start/stop/cancel/error paths. It is deliberately not attempted here.
 */
class GenerationFlagAccountingContractTest {

    private val viewModelSource: String by lazy {
        sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
    }

    /** Recorded baseline. A change here is a prompt to re-check the pairing, not a failure to fix silently. */
    private val expectedTrueSites = 3
    private val expectedFalseSites = 11

    /** Measured after the scanner was corrected: no reset is reached through a `finally`. */
    private val expectedExceptionSafeResets = 0

    private val assignment = Regex("""(?<![\w.])isGenerating\s*=\s*(true|false)\b""")

    /** Every `isGenerating = true|false` in [source], as (line index, value). */
    private fun assignments(source: String): List<Pair<Int, String>> =
        source.lineSequence().withIndex().mapNotNull { (index, line) ->
            assignment.find(line)?.let { index to it.groupValues[1] }
        }.toList()

    /**
     * Counts resets that sit inside a `finally` block, by walking back from each reset to the
     * nearest enclosing `try`/`finally`.
     *
     * The walk stops at a **function boundary** as well as at `try`. Without that stop it crosses
     * into the previous function and credits a neighbour's `finally` to this reset — which is
     * exactly what the positive control below caught when this scanner was first written.
     *
     * Split out so the positive control can prove it detects one.
     */
    private fun exceptionSafeResets(source: String): Int {
        val lines = source.lines()
        // Leading whitespace is optional: the real file indents members by four spaces, but the
        // synthetic samples in the positive controls are written flush left after trimIndent().
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
                // A `try` in this function ends the search; a boundary means the `finally` we might
                // find would belong to somebody else.
                if (Regex("""\btry\b\s*\{""").containsMatchIn(line)) break
                if (functionBoundary.containsMatchIn(line) || typeBoundary.containsMatchIn(line)) break
            }
            insideFinally
        }
    }

    @Test
    fun `the entry points that start a generation are the recorded ones`() {
        val trues = assignments(viewModelSource).filter { it.second == "true" }
        assertEquals(
            "A new `isGenerating = true` needs a matching, exception-safe reset before it lands.",
            expectedTrueSites,
            trues.size
        )
    }

    @Test
    fun `the reset sites are the recorded ones`() {
        val falses = assignments(viewModelSource).filter { it.second == "false" }
        assertEquals(
            "The number of manual resets changed; re-derive the true/false pairing in this test's KDoc.",
            expectedFalseSites,
            falses.size
        )
    }

    @Test
    fun `only the recorded number of resets is exception safe`() {
        assertEquals(
            "If more resets became exception-safe, update the baseline and the KDoc — that is progress.",
            expectedExceptionSafeResets,
            exceptionSafeResets(viewModelSource)
        )
    }

    @Test
    fun `every start site lives in the functions recorded in the KDoc`() {
        // Naming the owners keeps the finding concrete: a start added somewhere else is a new
        // lifecycle path, not a variation of an existing one.
        val lines = viewModelSource.lines()
        fun ownerOf(index: Int): String {
            for (cursor in index downTo 0) {
                Regex("""^    (?:private |internal |public )?(?:suspend )?fun (\w+)\(""")
                    .find(lines[cursor])?.let { return it.groupValues[1] }
            }
            return "?"
        }
        val owners = assignments(viewModelSource)
            .filter { it.second == "true" }
            .map { ownerOf(it.first) }
            .toSet()
        assertEquals(
            "Start sites moved; the KDoc's list of entry points must be updated with them.",
            setOf("sendPreparedMessageAsync", "continueAssistantImageToolTurn", "regenerateLastResponse"),
            owners
        )
    }

    @Test
    fun `the authoritative owner still exists alongside the mirrored flag`() {
        // The finding only holds while UiGenerationOwnership is the authoritative component and
        // isGenerating is the mirror. If either side disappears, this test should be revisited.
        val ownership = sourceFile("app/src/main/java/com/muyuchat/mca/UiGenerationOwnership.kt")
        listOf("fun reserveStart(", "fun activate(", "fun finish(", "fun cancelCurrent(").forEach { member ->
            assertTrue("UiGenerationOwnership should still own the lifecycle: missing $member", ownership.contains(member))
        }
        assertTrue(
            "the mirrored flag should still exist in MainUiState for this accounting to matter",
            sourceFile("app/src/main/java/com/muyuchat/mca/MainUiState.kt").contains("val isGenerating: Boolean = false")
        )
    }

    // ── Positive controls ───────────────────────────────────────────────────────────────

    @Test
    fun `the scanner detects start and reset sites in a synthetic sample`() {
        val sample = """
            fun a() {
                _uiState.update { it.copy(isGenerating = true) }
            }
            fun b() {
                try {
                    work()
                } finally {
                    _uiState.update { it.copy(isGenerating = false) }
                }
            }
            fun c() {
                _uiState.update { it.copy(isGenerating = false) }
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

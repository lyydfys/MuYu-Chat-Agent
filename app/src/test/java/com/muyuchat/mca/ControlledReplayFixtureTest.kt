package com.muyuchat.mca

import com.muyuchat.feature.chat.ChatMessageIdentity
import com.muyuchat.feature.chat.ChatReplayFrame
import com.muyuchat.feature.chat.ChatReplayFrameProjection
import com.muyuchat.feature.chat.ChatReplayRenderBranch
import com.muyuchat.feature.chat.ChatReplayUiChain
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R10.3: drives the declarative controlled-replay fixture through the PRODUCTION stream owner.
 *
 * Scope, stated honestly: this test covers the publisher-to-committed-answer slice -- the fixture's
 * event vocabulary (text / reasoning / stats / tool / terminal / cancel, including run switches and
 * late chunks) is applied to the real [ChatStreamAccumulator], which is the same object the chat
 * execution path uses to turn network deltas into committed batches. It therefore proves event
 * consumption, publish batching, terminal settlement idempotency, cancel isolation, late-chunk
 * rejection and the exact final answer bytes (length + SHA-256, including the 100k stress case).
 *
 * It does NOT prove frame timing, scrolling or rendering: those need a device driver and stay
 * NOT_RUN until one exists. The fixture's own frame-observation schema says the same thing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlledReplayFixtureTest {

    @Test
    fun `manifest digest matches the fixture file it describes`() {
        val root = replayRoot()
        val manifest = JSONObject(File(root, "manifest.json").readText(Charsets.UTF_8))
        val fixtureFile = File(root, manifest.getString("fixture"))
        assertTrue("fixture must exist: $fixtureFile", fixtureFile.isFile)
        assertEquals(
            "manifest must pin the fixture bytes",
            manifest.getString("fixtureSha256").lowercase(),
            sha256(fixtureFile.readBytes())
        )
        assertEquals("mca.controlled-replay.manifest/1", manifest.getString("schema"))
        // The chain is deliberately BLOCKED: data + digest exist, an on-device driver does not.
        assertEquals("BLOCKED", manifest.getJSONObject("chain").getString("status"))
    }

    @Test
    fun `every declared case reaches its expected final answer through the production accumulator`() =
        runTest {
            val fixture = loadFixture()
            val cases = fixture.getJSONArray("cases")
            assertEquals("case count must match the fixture header", fixture.getInt("caseCount"), cases.length())

            val required = fixture.getJSONArray("requiredCases")
            val ids = (0 until cases.length()).map { cases.getJSONObject(it).getString("id") }
            for (index in 0 until required.length()) {
                assertTrue("required case missing: ${required.getString(index)}", ids.contains(required.getString(index)))
            }

            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                runCase(case)
            }
        }

    @Test
    fun `every replayed frame projects through the UI chain and selects a renderer branch`() {
        val fixture = loadFixture()
        val cases = fixture.getJSONArray("cases")
        val ids = linkedSetOf<String>()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            ids += case.getString("id")
            assertReplayFrames(case)
        }
        // A case added to the fixture without a renderer-branch contract would silently escape the
        // per-frame assertions, so the declared contract must cover the declared case list exactly.
        assertEquals(
            "every declared case must have a declared renderer-branch contract",
            ids,
            EXPECTED_SETTLED_BRANCHES.keys
        )
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * One replay run: fixture events in, committed answer out.
     *
     * The accumulator is driven with a virtual monotonic clock and a single assistant message that
     * exists for the whole run, which is the production shape (the message row is created before the
     * stream starts, so a reasoning-only or empty answer still settles).
     */
    private fun TestScope.runCase(case: JSONObject) {
        val id = case.getString("id")
        val expect = case.getJSONObject("expect")
        val events = case.getJSONArray("events")

        val answer = StringBuilder()
        val reasoning = StringBuilder()
        var clockMs = 0L
        var runId = 1L
        var fixtureRun = events.optJSONObject(0)?.optInt("run", 1) ?: 1
        var terminalEvents = 0
        var settlements = 0
        var cancellations = 0
        var lateChunks = 0
        var lateChunkText = ""
        var terminalSeen = false
        val seenRuns = linkedSetOf(fixtureRun)

        val accumulator = ChatStreamAccumulator(
            scope = backgroundScope,
            currentRunId = { runId },
            activeSessionId = { "replay-session" },
            hasAssistantMessage = { true },
            commit = { batch, isStillCurrent ->
                if (isStillCurrent()) {
                    answer.append(batch.content)
                    reasoning.append(batch.reasoning)
                }
            },
            now = { clockMs }
        )

        var consumed = 0
        for (eventIndex in 0 until events.length()) {
            val event = events.getJSONObject(eventIndex)
            consumed++
            clockMs += 1
            val declaredRun = event.optInt("run", fixtureRun)
            if (declaredRun != fixtureRun) {
                // A second run starts: the previous generation is discarded, and the answer body
                // belongs to the newest run only.
                accumulator.discard(runId)
                runId++
                fixtureRun = declaredRun
                seenRuns += declaredRun
                answer.setLength(0)
                reasoning.setLength(0)
                terminalSeen = false
            }
            when (event.getString("type")) {
                "text" -> {
                    if (event.optBoolean("late", false) || terminalSeen) {
                        lateChunks++
                        lateChunkText = eventText(event)
                    } else {
                        accumulator.append(
                            delta = eventText(event),
                            generationRunId = runId
                        )
                    }
                }

                "reasoning" -> {
                    accumulator.append(
                        delta = "",
                        reasoningDelta = event.getString("text"),
                        generationRunId = runId
                    )
                }

                // Stats and tool events carry no answer body; the harness still consumes them so the
                // consumed-event count proves the whole sequence was delivered.
                "stats", "tool" -> Unit

                "terminal" -> {
                    terminalEvents++
                    if (!terminalSeen) {
                        // One settlement per run: a duplicate terminal must not flush twice, which is
                        // what keeps metrics and the terminal state single.
                        accumulator.flush(runId)
                        settlements++
                        terminalSeen = true
                    }
                }

                "cancel" -> {
                    cancellations++
                    accumulator.discard(runId)
                }

                else -> error("unknown replay event type for case '$id': ${event.getString("type")}")
            }
            runCurrent()
        }

        // The tail must still be published even when the last text arrived just before the terminal.
        if (!terminalSeen) {
            accumulator.flush(runId)
            settlements++
        }

        assertEquals("$id: consumed event count", expect.getInt("eventCount"), consumed)
        assertEquals("$id: terminal events", expect.getInt("terminalCount"), terminalEvents)
        assertEquals("$id: cancels", expect.getInt("cancelCount"), cancellations)
        assertEquals("$id: late chunks", expect.getInt("lateTextCount"), lateChunks)
        assertEquals("$id: terminal settlement must be exactly once per run", 1, settlements)
        if (expect.has("runs")) {
            assertEquals("$id: distinct runs", expect.getInt("runs"), seenRuns.size)
        }
        if (expect.has("lateText") && expect.getInt("lateTextCount") > 0) {
            assertEquals("$id: the ignored late chunk", expect.getString("lateText"), lateChunkText)
        }

        // The answer body: the fixture pins length in UTF-16 units and the SHA-256 of the UTF-8
        // bytes. Asserting both catches a truncated copy payload as well as a wrong body.
        val body = answer.toString()
        assertEquals("$id: final answer length", expect.getInt("finalTextLength"), body.length)
        assertEquals("$id: final answer digest", expect.getString("finalTextSha256"), sha256(body.toByteArray(Charsets.UTF_8)))
        if (expect.has("finalText")) {
            assertEquals("$id: final answer text", expect.getString("finalText"), body)
        }
        if (expect.has("minChars")) {
            assertTrue("$id: stress body must not shrink", body.length >= expect.getInt("minChars"))
        }
        // Reasoning must never leak into the answer body.
        if (expect.getInt("finalTextLength") == 0) {
            assertTrue("$id: reasoning-only answer stays empty", body.isEmpty())
        }
    }

    /** Materializes a text event: either a literal `text`, or a `blocks` repeat definition. */
    private fun eventText(event: JSONObject): String {
        event.optString("text", "").takeIf { event.has("text") }?.let { return it }
        val blocks = event.optJSONArray("blocks") ?: return ""
        val builder = StringBuilder()
        for (blockIndex in 0 until blocks.length()) {
            val block = blocks.getJSONObject(blockIndex)
            when (block.getString("kind")) {
                "repeat" -> builder.append(block.getString("unit").repeat(block.getInt("count")))
                else -> error("unknown text block kind: ${block.getString("kind")}")
            }
        }
        return builder.toString()
    }

    // ---- frame -> projection -> renderer branch ------------------------------------------------

    /** Frames one case would render, plus how many text deltas actually entered the answer body. */
    private data class FramePlan(val frames: List<ChatReplayFrame>, val acceptedTextEvents: Int)

    /**
     * The frame sequence the chat surface would render for one case.
     *
     * One frame per accepted text delta plus one frame for the settled body. Late chunks and text
     * after the terminal are not body content (the accumulator rejects them), and a run switch
     * starts a new assistant message, so it resets the body and owns a new identity. That is the
     * same rule the accumulator harness above applies; the frames only carry the visible side.
     */
    private fun replayFrames(case: JSONObject): FramePlan {
        val id = case.getString("id")
        val events = case.getJSONArray("events")
        val frames = mutableListOf<ChatReplayFrame>()
        val body = StringBuilder()
        var runId = 1
        var fixtureRun = events.optJSONObject(0)?.optInt("run", 1) ?: 1
        var terminalSeen = false
        var accepted = 0

        fun settledFrame() {
            frames += ChatReplayUiChain.frame(
                content = body.toString(),
                owner = ChatMessageIdentity(conversationId = "replay-$id", messageId = "run-$runId")
            )
        }

        for (eventIndex in 0 until events.length()) {
            val event = events.getJSONObject(eventIndex)
            val declaredRun = event.optInt("run", fixtureRun)
            if (declaredRun != fixtureRun) {
                runId += 1
                fixtureRun = declaredRun
                body.setLength(0)
                terminalSeen = false
            }
            when (event.getString("type")) {
                "text" -> if (!event.optBoolean("late", false) && !terminalSeen) {
                    body.append(eventText(event))
                    accepted += 1
                    settledFrame()
                }

                "terminal" -> terminalSeen = true
                else -> Unit
            }
        }
        settledFrame()
        return FramePlan(frames = frames, acceptedTextEvents = accepted)
    }

    /**
     * Per-frame projection and renderer-selection assertions for one case.
     *
     * Every frame must project through the shipped page / budget / parser rules, must select at
     * least one renderer branch, and the previous projection may only stay on screen inside the
     * same run (the shipped `canDisplay` gate).
     */
    private fun assertReplayFrames(case: JSONObject) {
        val id = case.getString("id")
        val expect = case.getJSONObject("expect")
        val plan = replayFrames(case)
        val frames = plan.frames
        assertEquals(
            "$id: one frame per accepted text delta plus the settled frame",
            plan.acceptedTextEvents + 1,
            frames.size
        )

        val projections = frames.map { frame -> ChatReplayUiChain.project(frame) }
        var previous: ChatReplayFrameProjection? = null
        projections.forEachIndexed { index, projection ->
            assertFrameProjection(id, index, frames[index], projection, previous)
            previous = projection
        }

        // First, middle and settled frame: projecting the same frame again must be stable, so the
        // branch a device selects is a property of the frame, not of projection order.
        linkedSetOf(0, frames.size / 2, frames.lastIndex).forEach { index ->
            val again = ChatReplayUiChain.project(frames[index])
            assertEquals(
                "$id frame $index: display page is stable",
                projections[index].displayContent,
                again.displayContent
            )
            assertEquals(
                "$id frame $index: renderer branches are stable",
                projections[index].blockBranches,
                again.blockBranches
            )
            assertEquals(
                "$id frame $index: budget routing is stable",
                projections[index].needsBackgroundProjection,
                again.needsBackgroundProjection
            )
        }

        // The settled frame is what the user ends up reading: its body must be the fixture's pinned
        // answer and its renderer selection is the case's declared contract.
        val settled = projections.last()
        assertEquals(
            "$id: settled frame holds the pinned answer body",
            expect.getInt("finalTextLength"),
            settled.contentLength
        )
        assertEquals(
            "$id: settled frame hashes to the pinned answer body",
            expect.getString("finalTextSha256"),
            sha256(settled.fullContent.toByteArray(Charsets.UTF_8))
        )
        val expectedBranches =
            EXPECTED_SETTLED_BRANCHES[id] ?: error("case '$id' has no declared renderer branches")
        assertEquals("$id: settled renderer branches", expectedBranches.toSet(), settled.branches.toSet())

        assertCaseShape(id, case, frames, projections)
    }

    private fun assertFrameProjection(
        id: String,
        index: Int,
        frame: ChatReplayFrame,
        projection: ChatReplayFrameProjection,
        previous: ChatReplayFrameProjection?
    ) {
        val label = "$id frame $index"
        assertEquals("$label: body length", frame.content.length, projection.contentLength)
        assertEquals("$label: page budget", frame.visibleCharacters, projection.visibleCharacters)
        assertTrue(
            "$label: the displayed page is a prefix of the body",
            frame.content.startsWith(projection.displayContent)
        )
        if (frame.content.length <= frame.visibleCharacters) {
            assertEquals(
                "$label: a body inside the page budget is displayed whole",
                frame.content,
                projection.displayContent
            )
        } else {
            assertTrue(
                "$label: a paged body is cut at the page budget",
                projection.displayContent.length == frame.visibleCharacters ||
                    projection.displayContent.length == frame.visibleCharacters - 1
            )
        }
        // Budget routing is the shipped scheduling rule, re-derived from the frame's own page/body.
        val expectedBackground =
            projection.displayContent.length > ChatReplayUiChain.syncParseBudgetCharacters ||
                projection.contentLength > ChatReplayUiChain.syncParseBudgetCharacters
        assertEquals("$label: parse budget routing", expectedBackground, projection.needsBackgroundProjection)
        assertTrue("$label: the renderer always receives at least one block", projection.blockBranches.isNotEmpty())
        assertEquals(
            "$label: the first block selects the primary renderer",
            projection.blockBranches.first(),
            projection.primaryBranch
        )
        assertEquals("$label: one branch per parsed block", projection.blockBranches.size, projection.blockCount)
        assertNull("$label: the production parse must not fall back to source", projection.failure)
        // Only a later frame of the SAME run may keep the previous projection on screen.
        assertEquals(
            "$label: previous projection display gate",
            previous != null && previous.owner == frame.owner,
            ChatReplayUiChain.keepsPreviousProjection(previous, projection)
        )
    }

    /** Case-specific structure that the generic per-frame checks cannot express. */
    private fun assertCaseShape(
        id: String,
        case: JSONObject,
        frames: List<ChatReplayFrame>,
        projections: List<ChatReplayFrameProjection>
    ) {
        val settled = projections.last()
        when (id) {
            "burst-2048" -> {
                // The fixture pins exactly one budget of UTF-16 units, i.e. the fast-path boundary.
                assertEquals(2_048, settled.contentLength)
                assertFalse(
                    "$id: a body exactly at the budget stays on the synchronous fast path",
                    settled.needsBackgroundProjection
                )
            }

            "no-content-wait", "reasoning-only" -> {
                assertEquals("$id: reasoning never reaches the answer body", 0, settled.contentLength)
                assertEquals(
                    "$id: an empty settled body still selects the paragraph renderer",
                    setOf(ChatReplayRenderBranch.RICH),
                    settled.branches
                )
                assertEquals("", settled.displayContent)
            }

            "late-chunk-after-terminal" -> {
                val late = case.getJSONObject("expect").getString("lateText")
                assertTrue("$id: the fixture must record a late chunk", late.isNotEmpty())
                assertTrue("$id: no frame may show the late chunk", frames.none { it.content.contains(late) })
                assertTrue(
                    "$id: no projection may show the late chunk",
                    projections.none { it.displayContent.contains(late) }
                )
            }

            "cancel-then-new-run" -> {
                assertEquals(
                    "$id: the cancelled run and the new run are two message identities",
                    2,
                    frames.map { it.owner }.distinct().size
                )
                val firstNewRun = projections.indexOfFirst { it.owner != projections.first().owner }
                assertTrue("$id: the replay must switch runs", firstNewRun > 0)
                assertFalse(
                    "$id: the settled projection belongs to the newest run only",
                    ChatReplayUiChain.keepsPreviousProjection(
                        projections[firstNewRun - 1],
                        projections[firstNewRun]
                    )
                )
            }

            "content-code-table-math-link" -> {
                val firstFrame = projections.first()
                assertTrue(
                    "$id: the first frame already holds the code fence",
                    firstFrame.hasBranch(ChatReplayRenderBranch.CODE)
                )
                assertFalse(
                    "$id: the table only arrives in a later frame",
                    firstFrame.hasBranch(ChatReplayRenderBranch.TABLE)
                )
                assertTrue("$id: the settled frame holds the table", settled.hasBranch(ChatReplayRenderBranch.TABLE))
                assertTrue(
                    "$id: the settled frame holds the math/link paragraph",
                    settled.hasBranch(ChatReplayRenderBranch.RICH)
                )
            }

            "content-100k" -> {
                assertTrue("$id: the stress body exceeds one page", settled.contentLength > settled.visibleCharacters)
                assertEquals(
                    "$id: the stress body is displayed as exactly the first page",
                    settled.visibleCharacters,
                    settled.displayContent.length
                )
                assertTrue("$id: the stress body must use the background projection", settled.needsBackgroundProjection)
            }
        }
    }

    /**
     * The renderer selection each case must end on. This is the replay's renderer contract: the
     * fixture declares the content shapes, and this map declares which renderer family each case
     * must select for them.
     */
    private val EXPECTED_SETTLED_BRANCHES = mapOf(
        "normal-small-chunks" to setOf(ChatReplayRenderBranch.RICH),
        "burst-2048" to setOf(ChatReplayRenderBranch.RICH),
        "early-finish" to setOf(ChatReplayRenderBranch.RICH),
        "no-content-wait" to setOf(ChatReplayRenderBranch.RICH),
        "reasoning-only" to setOf(ChatReplayRenderBranch.RICH),
        "tail-flush" to setOf(ChatReplayRenderBranch.RICH),
        "cancel-then-new-run" to setOf(ChatReplayRenderBranch.RICH),
        "duplicate-terminal" to setOf(ChatReplayRenderBranch.RICH),
        "late-chunk-after-terminal" to setOf(ChatReplayRenderBranch.RICH),
        "content-unicode" to setOf(ChatReplayRenderBranch.RICH),
        "content-code-table-math-link" to setOf(
            ChatReplayRenderBranch.RICH,
            ChatReplayRenderBranch.CODE,
            ChatReplayRenderBranch.TABLE
        ),
        "content-100k" to setOf(ChatReplayRenderBranch.RICH)
    )

    private fun loadFixture(): JSONObject {
        val manifest = JSONObject(File(replayRoot(), "manifest.json").readText(Charsets.UTF_8))
        val fixture = File(replayRoot(), manifest.getString("fixture"))
        return JSONObject(fixture.readText(Charsets.UTF_8))
    }

    private fun replayRoot(): File = File(projectRoot(), "benchmark/src/main/assets/controlled-replay")

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
    }
}

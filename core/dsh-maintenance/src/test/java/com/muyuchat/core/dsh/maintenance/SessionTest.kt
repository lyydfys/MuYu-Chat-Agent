package com.muyuchat.core.dsh.maintenance

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionTest {

    private val roots = ArrayList<File>()

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("dsh-session")
        roots.add(root)
        return root
    }

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private class Fixture(val root: File) {
        val home = File(root, "home")
        val layout = DshHomeLayout(home)
        val codec = TestSupport.FramedSessionCodec()
        val goodSha = "a".repeat(64)
        val missingSha = "b".repeat(64)
        val goodBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

        init {
            TestSupport.writeBytes(
                File(home, "attachments/v1/objects/${goodSha.substring(0, 2)}/$goodSha"),
                goodBytes
            )
        }

        private fun textPart(text: String) = JSONObject().put("type", "text").put("text", text)

        private fun attachmentPart(sha: String, name: String, bytes: Long) = JSONObject()
            .put("type", "image")
            .put(
                "attachment",
                JSONObject()
                    .put("attachmentId", "sha256:$sha")
                    .put("mediaType", "image/png")
                    .put("name", name)
                    .put("bytes", bytes)
            )

        private fun line(seq: Int, type: String, content: JSONArray) = JSONObject()
            .put("type", type)
            .put("seq", seq)
            .put("data", JSONObject().put("message", JSONObject().put("content", content)))
            .toString()

        fun sessionLines(): List<String> = listOf(
            line(1, "user/message", JSONArray().put(textPart("hello"))),
            line(
                2, "tool/result",
                JSONArray()
                    .put(attachmentPart(goodSha, "ok.png", 8))
                    .put(textPart("first-tail"))
            ),
            line(
                3, "tool/result",
                JSONArray()
                    .put(attachmentPart(missingSha, "bad.png", 10))
                    .put(textPart("second-tail"))
            )
        )

        fun writeSession(
            slug: String = "main",
            dirName: String = "session-abc",
            fileName: String = "session.v4.jsonl.zstd"
        ): DshSessionRef {
            val lines = sessionLines()
            val bytes = codec.encodeFrames(
                listOf(
                    lines[0] + "\n" + lines[1] + "\n",
                    lines[2] + "\n"
                )
            )
            val directory = File(home, "sessions/$slug/$dirName")
            TestSupport.writeBytes(File(directory, fileName), bytes)
            return DshSessionInventory(layout).find(dirName.removePrefix("session-"))!!
        }

        fun refFor(slug: String, dirName: String, fileName: String): DshSessionRef {
            val lines = sessionLines()
            val bytes = codec.encodeFrames(listOf(lines[0] + "\n" + lines[1] + "\n", lines[2] + "\n"))
            val directory = File(home, "sessions/$slug/$dirName")
            TestSupport.writeBytes(File(directory, fileName), bytes)
            return DshSessionInventory(layout).list().first { it.dirName == dirName }
        }

        fun engine(
            probe: DshMaintenanceRecoveryProbe = TestSupport.healthyProbe(),
            crash: DshMaintenanceCrashHook = DshMaintenanceCrashHook.NONE
        ) = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), probe, crashHook = crash)
    }

    @Test
    fun inventoryIsVersionAgnostic() {
        val fixture = Fixture(newRoot())
        fixture.writeSession("main", "session-abc", "session.v4.jsonl.zstd")
        fixture.writeSession("main", "session-legacy", "session.jsonl.zstd")
        fixture.writeSession("work", "session-future", "session.v5.jsonl.zstd")
        TestSupport.write(File(fixture.home, "sessions/main/notes.txt"), "not a session")
        val sessions = DshSessionInventory(fixture.layout).list()
        assertEquals(3, sessions.size)
        assertEquals(
            setOf(DshSessionFormat.CURRENT, DshSessionFormat.LEGACY, DshSessionFormat.OTHER_VERSIONED),
            sessions.map { it.format }.toSet()
        )
        assertEquals("abc", DshSessionInventory(fixture.layout).find("abc")?.sessionId)
    }

    @Test
    fun repairsOnlyTheProvenBadAttachmentAndPreservesEverythingElse() {
        val fixture = Fixture(newRoot())
        val session = fixture.writeSession()
        val beforeBytes = session.file.readBytes()
        val beforeContent = fixture.codec.decode(beforeBytes)
        val engine = fixture.engine()
        val repairer = DshSessionRepairer(engine, fixture.layout, fixture.codec, DshAttachmentObjectVerifier(fixture.layout.attachmentsRoot))
        val plan = repairer.plan(session)
        assertTrue(plan.refusal == null)
        assertEquals(2, plan.findings.size)
        assertEquals(1, plan.actions.size)
        assertEquals("sha256:${fixture.missingSha}", plan.actions.single().attachmentId)
        val applied = repairer.apply(session, plan, TestSupport.requester(), TestSupport.stopped())
        assertTrue(applied is DshSessionRepairResult.Applied)
        val afterContent = fixture.codec.decode(session.file.readBytes())
        assertEquals(beforeContent.frames.size, afterContent.frames.size)
        assertEquals(3, plan.lineCount)
        // The frame that contained only a healthy reference kept its exact bytes.
        assertArrayEquals(beforeContent.frames[0].raw, afterContent.frames[0].raw)
        val goodLine = JSONObject(afterContent.frames[0].text.split('\n')[1])
        val goodAttachment = goodLine.getJSONObject("data").getJSONObject("message")
            .getJSONArray("content").getJSONObject(0).getJSONObject("attachment")
        assertEquals("sha256:${fixture.goodSha}", goodAttachment.getString("attachmentId"))
        val repairedLine = JSONObject(afterContent.frames[1].text.split('\n')[0])
        val parts = repairedLine.getJSONObject("data").getJSONObject("message").getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertTrue(parts.getJSONObject(0).getString("text").contains("attachment unavailable"))
        assertEquals("second-tail", parts.getJSONObject(1).getString("text"))
        assertFalse(afterContent.frames[1].text.contains("sha256:${fixture.missingSha}"))
    }

    @Test
    fun refusesUnknownVersionsCorruptFramesAndRunningSessions() {
        val fixture = Fixture(newRoot())
        val repairer = DshSessionRepairer(
            fixture.engine(),
            fixture.layout,
            fixture.codec,
            DshAttachmentObjectVerifier(fixture.layout.attachmentsRoot)
        )
        val future = fixture.refFor("main", "session-future", "session.v5.jsonl.zstd")
        assertEquals(
            DshMaintenanceError.UNKNOWN_FORMAT,
            repairer.plan(future).refusal?.code
        )
        val corrupt = fixture.refFor("main", "session-corrupt", "session.v4.jsonl.zstd")
        val corruptBytes = fixture.codec.encodeFrames(listOf(fixture.sessionLines()[0] + "\n")) +
            fixture.codec.corruptFrame()
        TestSupport.writeBytes(corrupt.file, corruptBytes)
        val corruptRef = DshSessionInventory(fixture.layout).list().first { it.dirName == "session-corrupt" }
        assertEquals(
            DshMaintenanceError.UNKNOWN_FORMAT,
            repairer.plan(corruptRef).refusal?.code
        )
        val normal = fixture.writeSession()
        assertEquals(
            DshMaintenanceError.SESSION_RUNNING,
            repairer.plan(normal, running = true).refusal?.code
        )
    }

    @Test
    fun healthFailureOnRepairRestoresTheOriginalSession() {
        val fixture = Fixture(newRoot())
        val session = fixture.writeSession()
        val before = session.file.readBytes()
        val repairer = DshSessionRepairer(
            fixture.engine(probe = TestSupport.unhealthyProbe()),
            fixture.layout,
            fixture.codec,
            DshAttachmentObjectVerifier(fixture.layout.attachmentsRoot)
        )
        val plan = repairer.plan(session)
        val result = repairer.apply(session, plan, TestSupport.requester(), TestSupport.stopped())
        assertTrue(result is DshSessionRepairResult.RolledBack)
        assertArrayEquals(before, session.file.readBytes())
    }

    @Test
    fun trashRestoreAndPurgeKeepSessionIdentityWithVisibleConflicts() {
        val fixture = Fixture(newRoot())
        val recycler = DshSessionRecycler(fixture.engine(), fixture.layout)
        val session = fixture.writeSession()
        val trashed = recycler.trash(session, running = false, TestSupport.requester(), TestSupport.stopped())
        assertTrue(trashed is DshSessionRecycleResult.Trashed)
        assertFalse(session.directory.exists())
        val entry = (trashed as DshSessionRecycleResult.Trashed).entry
        assertEquals("main", entry.slug)
        assertEquals("session-abc", entry.dirName)
        assertTrue(File(entry.directory, ".origin").isFile)
        val listed = recycler.listTrash()
        assertEquals(1, listed.size)
        val restored = recycler.restore(listed.single(), TestSupport.requester("restore"), TestSupport.stopped())
        assertTrue(restored is DshSessionRecycleResult.Restored)
        assertTrue(File(fixture.home, "sessions/main/session-abc/session.v4.jsonl.zstd").isFile)
        assertFalse(File(fixture.home, "sessions/main/session-abc/.origin").exists())
        // Repeating the restore is idempotent instead of destructive.
        assertTrue(
            recycler.restore(listed.single(), TestSupport.requester("again"), TestSupport.stopped())
                is DshSessionRecycleResult.AlreadyRestored
        )
        // A same-name destination is a visible conflict, never an overwrite.
        val second = recycler.trash(
            DshSessionInventory(fixture.layout).find("abc")!!,
            running = false,
            TestSupport.requester("op-3"),
            TestSupport.stopped()
        ) as DshSessionRecycleResult.Trashed
        TestSupport.write(File(fixture.home, "sessions/main/session-abc/session.v4.jsonl.zstd"), "conflicting")
        val conflict = recycler.restore(second.entry, TestSupport.requester("op-4"), TestSupport.stopped())
        assertEquals(
            DshMaintenanceError.RECYCLE_CONFLICT,
            (conflict as DshSessionRecycleResult.Rejected).failure.code
        )
        assertEquals("conflicting", File(fixture.home, "sessions/main/session-abc/session.v4.jsonl.zstd").readText())
        assertTrue(second.entry.directory.isDirectory)
        TestSupport.deleteTree(File(fixture.home, "sessions/main/session-abc"))
        assertTrue(
            recycler.restore(second.entry, TestSupport.requester("op-5"), TestSupport.stopped())
                is DshSessionRecycleResult.Restored
        )
        // Running sessions are refused, and permanent deletion needs an explicit confirmation.
        assertEquals(
            DshMaintenanceError.SESSION_RUNNING,
            (recycler.trash(session, true, TestSupport.requester(), TestSupport.stopped())
                as DshSessionRecycleResult.Rejected).failure.code
        )
        val untouched = File(fixture.home, "sessions/main/session-def/session.v4.jsonl.zstd")
        TestSupport.write(untouched, "untouched")
        val purgeEntry = (
            recycler.trash(
                DshSessionInventory(fixture.layout).find("abc")!!,
                running = false,
                TestSupport.requester("op-6"),
                TestSupport.stopped()
            ) as DshSessionRecycleResult.Trashed
            ).entry
        assertEquals(
            DshMaintenanceError.PURGE_CONFIRMATION_REQUIRED,
            (recycler.purge(purgeEntry, false, TestSupport.requester(), TestSupport.stopped())
                as DshSessionRecycleResult.Rejected).failure.code
        )
        assertTrue(
            recycler.purge(purgeEntry, true, TestSupport.requester("purge"), TestSupport.stopped())
                is DshSessionRecycleResult.Purged
        )
        assertTrue(recycler.listTrash().isEmpty())
        // The live sessions tree was never touched by a purge: an unrelated session stays.
        assertTrue(untouched.isFile)
    }

    @Test
    fun crashDuringTrashIsRolledBackToTheSessionsTree() {
        val fixture = Fixture(newRoot())
        val session = fixture.writeSession()
        val crashing = fixture.engine(crash = TestSupport.CrashAt(DshMaintenanceBoundary.STEP_APPLIED, 0))
        val recycler = DshSessionRecycler(crashing, fixture.layout)
        assertThrows(DshSimulatedCrash::class.java) {
            recycler.trash(session, running = false, TestSupport.requester(), TestSupport.stopped())
        }
        assertFalse(session.directory.exists())
        assertTrue(File(fixture.home, "sessions-deleted").listFiles()!!.isNotEmpty())
        val recovering = fixture.engine()
        recovering.recoverOnStart()
        assertTrue(File(fixture.home, "sessions/main/session-abc/session.v4.jsonl.zstd").isFile)
        assertEquals(0, File(fixture.home, "sessions-deleted").listFiles()!!.size)
    }
}

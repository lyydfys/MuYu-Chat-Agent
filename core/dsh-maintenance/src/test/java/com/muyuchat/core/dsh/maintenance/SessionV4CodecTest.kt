package com.muyuchat.core.dsh.maintenance

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Frame-container and repair tests for the official `session.v4.jsonl.zstd` format.
 *
 * The structural scanner is pure JVM and always exercised. The codec protocol is exercised with a
 * fake helper runner; the same flows are also run against the real bundled helper when a Node with
 * `node:zlib` zstd (>= 24) is available to the test JVM. Tests that need that Node are explicitly
 * assumed/skipped there, never silently reported as passing codec behaviour.
 */
class SessionV4CodecTest {

    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("dsh-session-codec")
        roots.add(root)
        return root
    }

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/$name")!!.use { it.readBytes() }

    private fun frameRanges(bytes: ByteArray): List<DshZstdFrame> = DshZstdFrameScanner.scan(bytes).frames

    // ------------------------------------------------------------------ scanner (pure JVM)

    @Test
    fun structuralScanFindsEveryIndependentFrameAndChecksum() {
        val bytes = fixture("session-v4-fixture.jsonl.zstd")
        val scan = DshZstdFrameScanner.scan(bytes)
        assertEquals(4, scan.frames.size)
        assertNull(scan.tornStart)
        assertEquals(0L, scan.frames.first().start)
        assertEquals(bytes.size.toLong(), scan.frames.last().end)
        assertTrue(scan.frames.all { it.checksummed })
        // Complete frames are contiguous with no gaps and no overlap.
        scan.frames.zipWithNext().forEach { (left, right) -> assertEquals(left.end, right.start) }
    }

    @Test
    fun structuralScanWalksMultipleBlocksInsideOneFrame() {
        val bytes = fixture("session-v4-multiblock.zstd")
        val scan = DshZstdFrameScanner.scan(bytes)
        assertEquals(2, scan.frames.size)
        assertNull(scan.tornStart)
        assertEquals(bytes.size.toLong(), scan.frames.last().end)
        // The second frame is larger than one 128 KiB Zstandard block, proving the scan did not
        // stop after the first block header.
        assertTrue(scan.frames.last().end - scan.frames.last().start > 60L)
    }

    @Test
    fun tornFinalFrameIsReportedInsteadOfBeingTreatedAsCorruption() {
        val bytes = fixture("session-v4-torn.jsonl.zstd")
        val scan = DshZstdFrameScanner.scan(bytes)
        assertEquals(3, scan.frames.size)
        val torn = scan.tornStart!!
        assertTrue(torn > 0L)
        assertTrue(torn < bytes.size)
        assertEquals(scan.frames.last().end, torn)
    }

    @Test
    fun invalidMagicAndReservedBitsAreRefused() {
        val bytes = fixture("session-v4-fixture.jsonl.zstd")
        val frames = frameRanges(bytes)
        val corruptMagic = bytes.copyOf()
        corruptMagic[frames[1].start.toInt()] = 0
        assertThrows(DshSessionDecodeException::class.java) {
            DshZstdFrameScanner.scan(corruptMagic)
        }
        val reserved = bytes.copyOf()
        reserved[(frames[0].start + 4).toInt()] =
            (reserved[(frames[0].start + 4).toInt()].toInt() or 0x10).toByte()
        assertThrows(DshSessionDecodeException::class.java) {
            DshZstdFrameScanner.scan(reserved)
        }
    }

    @Test
    fun singleFrameValidationRejectsConcatenations() {
        val bytes = fixture("session-v4-fixture.jsonl.zstd")
        val frames = frameRanges(bytes)
        val first = bytes.copyOfRange(frames[0].start.toInt(), frames[0].end.toInt())
        assertTrue(DshZstdFrameScanner.isSingleChecksummedFrame(first))
        assertFalse(DshZstdFrameScanner.isSingleChecksummedFrame(bytes))
        assertFalse(DshZstdFrameScanner.isSingleChecksummedFrame(ByteArray(0)))
    }

    // ------------------------------------------------------------------ codec protocol (fake helper)

    @Test
    fun decodeKeepsRawFrameBytesAndReportsTornTailMetadata() {
        val bytes = fixture("session-v4-fixture.jsonl.zstd")
        val scan = DshZstdFrameScanner.scan(bytes)
        val texts = listOf(
            "{\"type\":\"session\",\"version\":4,\"id\":\"fixture\"}\n",
            "{\"type\":\"turn/start\",\"seq\":1}\n",
            "{\"type\":\"turn/end\",\"seq\":2}\n",
            "{\"type\":\"turn/start\",\"seq\":3}\n"
        )
        val codec = DshNodeSessionCodec(
            runner = decodeFakeRunner(bytes, scan, texts),
            helper = File(newRoot(), "helper.mjs").apply { writeText("// fake helper") },
            workDirectory = File(newRoot(), "work")
        )
        val content = codec.decode(bytes)
        assertEquals("session.v4.jsonl.zstd", content.formatId)
        assertEquals(4, content.frames.size)
        assertTrue(content.frames.all { it.decodable })
        assertArrayEquals(
            bytes.copyOfRange(scan.frames[2].start.toInt(), scan.frames[2].end.toInt()),
            content.frames[2].raw
        )
        assertNull(content.tornTailStart)
    }

    @Test
    fun codecRejectsAFrameDisagreementWithTheStructuralScan() {
        val bytes = fixture("session-v4-fixture.jsonl.zstd")
        val scan = DshZstdFrameScanner.scan(bytes)
        val runner = DshNodeHelperRunner { _, args, _, _ ->
            val response = JSONObject()
                .put("ok", true)
                .put("formatId", "session.v4.jsonl.zstd")
                .put("header", JSONObject().put("type", "session").put("version", 4))
                .put(
                    "frames",
                    JSONArray().put(
                        JSONObject()
                            .put("index", 0)
                            .put("start", 0)
                            .put("end", 1)
                            .put("text", "x")
                    )
                )
            File(args[2]).writeText(response.toString())
            DshNodeHelperResult(0)
        }
        val codec = DshNodeSessionCodec(runner, stubHelper(newRoot()), File(newRoot(), "w"))
        assertThrows(DshSessionDecodeException::class.java) { codec.decode(bytes) }
    }

    @Test
    fun encodeFrameRefusesAnUnchecksummedOrConcatenatedResult() {
        val root = newRoot()
        val validFrame = run {
            val bytes = fixture("session-v4-fixture.jsonl.zstd")
            val frames = frameRanges(bytes)
            bytes.copyOfRange(frames[1].start.toInt(), frames[1].end.toInt())
        }
        val runner = DshNodeHelperRunner { _, args, _, _ ->
            val request = JSONObject(File(args[1]).readText())
            // Deliberately return two concatenated frames to prove the writer validates its input.
            File(request.getString("output")).writeBytes(validFrame + validFrame)
            File(args[2]).writeText(JSONObject().put("ok", true).toString())
            DshNodeHelperResult(0)
        }
        val codec = DshNodeSessionCodec(runner, stubHelper(newRoot()), File(root, "w"))
        assertThrows(DshSessionDecodeException::class.java) { codec.encodeFrame("frame\n") }
    }

    // ------------------------------------------------------------------ real helper (Node >= 24)

    @Test
    fun realHelperDecodesCountsAndReEncodesTheOfficialFormat() {
        val node = nodeOrNull()
        assumeTrue("a local Node >= 24 with node:zlib zstd is required for this check", node != null)
        val root = newRoot()
        val helper = File(root, "session-v4-codec.mjs")
        helper.writeText(helperSource(), Charsets.UTF_8)
        val codec = DshNodeSessionCodec(
            runner = processRunner(node!!),
            helper = helper,
            workDirectory = File(root, "work")
        )
        val bytes = fixture("session-v4-fixture.jsonl.zstd")
        val sessionFile = File(root, "session.v4.jsonl.zstd").apply { writeBytes(bytes) }

        val proof = codec.inspectHeader(sessionFile)
        assertTrue(proof.detail, proof.verified)
        assertEquals(4, proof.version)

        val stats = codec.stats(listOf(sessionFile)).single()
        assertEquals(12, stats?.eventCount)
        assertEquals(2, stats?.userMessages)
        assertEquals(2, stats?.assistantMessages)
        assertEquals(4, stats?.messageCount)
        assertFalse(stats?.tornTail ?: true)

        val content = codec.decode(bytes)
        assertEquals(4, content.frames.size)
        assertTrue(content.frames.all { it.decodable })
        assertTrue(content.frames[0].text.contains("\"type\":\"session\""))

        val headerText = content.frames[0].text
        val encoded = codec.encodeFrame(headerText)
        assertTrue(DshZstdFrameScanner.isSingleChecksummedFrame(encoded))
        assertEquals(headerText, processDecode(node, helper, encoded))
    }

    @Test
    fun repairDegradesOnlyTheMissingAttachmentAndPreservesEveryOtherFrame() {
        val node = nodeOrNull()
        assumeTrue("a local Node >= 24 with node:zlib zstd is required for this check", node != null)
        val fixture = realRepairFixture(node!!)
        val plan = fixture.repairer.plan(fixture.session)
        assertNull(plan.refusal)
        assertEquals(4, plan.frameCount)
        assertEquals(13, plan.lineCount)
        assertFalse(plan.tornTailTruncated)
        assertEquals(1, plan.actions.size)
        assertEquals("sha256:" + "b".repeat(64), plan.actions.single().attachmentId)
        val before = fixture.codec.decode(fixture.session.file.readBytes())

        val applied = fixture.repairer.apply(fixture.session, plan, TestSupport.requester(), TestSupport.stopped())
        assertTrue(applied is DshSessionRepairResult.Applied)
        val after = fixture.codec.decode(fixture.session.file.readBytes())
        assertEquals(before.frames.size, after.frames.size)
        // The frames with only healthy/retry content are untouched byte for byte.
        assertArrayEquals(before.frames[1].raw, after.frames[1].raw)
        assertArrayEquals(before.frames[2].raw, after.frames[2].raw)
        assertFalse(after.frames[3].text.contains("sha256:" + "b".repeat(64)))
        assertTrue(after.frames[3].text.contains("attachment unavailable"))
    }

    @Test
    fun repairTruncatesATornTailEvenWithoutAttachmentDamage() {
        val node = nodeOrNull()
        assumeTrue("a local Node >= 24 with node:zlib zstd is required for this check", node != null)
        val fixture = realRepairFixture(node!!, fixtureName = "session-v4-torn.jsonl.zstd")
        val plan = fixture.repairer.plan(fixture.session)
        assertNull(plan.refusal)
        assertEquals(3, plan.frameCount)
        assertTrue(plan.tornTailTruncated)
        assertTrue(plan.tornTailBytes > 0L)
        assertTrue(plan.needsRepair)

        val applied = fixture.repairer.apply(fixture.session, plan, TestSupport.requester(), TestSupport.stopped())
        assertTrue(applied is DshSessionRepairResult.Applied)
        val repaired = fixture.codec.decode(fixture.session.file.readBytes())
        assertEquals(3, repaired.frames.size)
        assertNull(repaired.tornTailStart)
    }

    // ------------------------------------------------------------------ helpers

    private class RealRepairFixture(
        val root: File,
        val codec: DshNodeSessionCodec,
        val repairer: DshSessionRepairer,
        val session: DshSessionRef
    )

    private fun realRepairFixture(node: String, fixtureName: String = "session-v4-fixture.jsonl.zstd"): RealRepairFixture {
        val root = newRoot()
        val helper = File(root, "session-v4-codec.mjs").apply { writeText(helperSource(), Charsets.UTF_8) }
        val codec = DshNodeSessionCodec(
            runner = processRunner(node),
            helper = helper,
            workDirectory = File(root, "work")
        )
        val home = File(root, "home")
        val goodSha = "a".repeat(64)
        TestSupport.writeBytes(
            File(home, "attachments/v1/objects/${goodSha.substring(0, 2)}/$goodSha"),
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        )
        val sessionDir = File(home, "sessions/main/session-codec")
        TestSupport.writeBytes(File(sessionDir, "session.v4.jsonl.zstd"), fixture(fixtureName))
        val layout = DshHomeLayout(home)
        val session = DshSessionInventory(layout).find("codec")!!
        val engine = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), TestSupport.healthyProbe())
        val repairer = DshSessionRepairer(engine, layout, codec, DshAttachmentObjectVerifier(layout.attachmentsRoot))
        return RealRepairFixture(root, codec, repairer, session)
    }

    private fun decodeFakeRunner(
        bytes: ByteArray,
        scan: DshZstdScan,
        texts: List<String>
    ): DshNodeHelperRunner = DshNodeHelperRunner { _, args, _, _ ->
        val request = JSONObject(File(args[1]).readText())
        assertEquals(File(request.getString("input")).readBytes().size, bytes.size)
        val frames = JSONArray()
        scan.frames.forEachIndexed { index, frame ->
            frames.put(
                JSONObject()
                    .put("index", index)
                    .put("start", frame.start)
                    .put("end", frame.end)
                    .put("text", texts.getOrElse(index) { "" })
            )
        }
        val response = JSONObject()
            .put("ok", true)
            .put("formatId", "session.v4.jsonl.zstd")
            .put("header", JSONObject().put("type", "session").put("version", 4))
            .put("frames", frames)
        File(args[2]).writeText(response.toString())
        DshNodeHelperResult(0)
    }

    private fun processRunner(node: String): DshNodeHelperRunner = DshNodeHelperRunner { helper, args, workDir, _ ->
        val process = ProcessBuilder(listOf(node, helper.absolutePath) + args)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        DshNodeHelperResult(if (finished) process.exitValue() else -1)
    }

    private fun processDecode(node: String, helper: File, frame: ByteArray): String {
        val work = Files.createTempDirectory("dsh-codec-decode").toFile()
        try {
            val input = File(work, "frame.zst").apply { writeBytes(frame) }
            val request = File(work, "request.json").apply {
                writeText(JSONObject().put("input", input.absolutePath).toString())
            }
            val response = File(work, "response.json")
            val process = ProcessBuilder(node, helper.absolutePath, "decode", request.absolutePath, response.absolutePath)
                .redirectErrorStream(true)
                .start()
            process.inputStream.readBytes()
            process.waitFor()
            val json = JSONObject(response.readText())
            return json.getJSONArray("frames").getJSONObject(0).getString("text")
        } finally {
            TestSupport.deleteTree(work)
        }
    }

    private fun nodeOrNull(): String? = runCatching {
        val process = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        val major = output.removePrefix("v").substringBefore('.').toIntOrNull() ?: return null
        if (major >= 24) "node" else null
    }.getOrNull()

    private fun helperSource(): String =
        File("src/main/assets/dsh-maintenance/session-v4-codec.mjs").readText(Charsets.UTF_8)

    private fun stubHelper(root: File): File =
        File(root, "helper.mjs").apply { writeText("// fake helper", Charsets.UTF_8) }
}

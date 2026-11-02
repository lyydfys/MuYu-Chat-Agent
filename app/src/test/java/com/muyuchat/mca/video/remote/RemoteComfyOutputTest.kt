package com.muyuchat.mca.video.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RemoteComfyOutputTest {

    @Test
    fun selectsMp4OutputFromVideosArray() {
        val record = JSONObject(
            """{"status":{"status_str":"success","completed":true},"outputs":{"9":{"videos":[{"filename":"clip.mp4","subfolder":"videos","type":"output","url":"https://evil.example/clip.mp4"}]}}}""",
        )
        val output = RemoteComfyOutputSelector.select(record)
        assertEquals(RemoteComfyOutputRef("clip.mp4", "videos", "9"), output)
        assertFalse(output.toString().contains("evil"))
    }

    @Test
    fun ignoresNonOutputNonMp4AndTraversalFilenames() {
        assertNull(select("""{"outputs":{"1":{"gifs":[{"filename":"clip.mp4","subfolder":"","type":"temp"}]}}}"""))
        assertNull(select("""{"outputs":{"1":{"images":[{"filename":"clip.webp","subfolder":"","type":"output"}]}}}"""))
        assertNull(select("""{"outputs":{"1":{"videos":[{"filename":"../clip.mp4","subfolder":"","type":"output"}]}}}"""))
        assertNull(select("""{"outputs":{"1":{"videos":[{"filename":"a/b.mp4","subfolder":"","type":"output"}]}}}"""))
        assertNull(select("""{"outputs":{"1":{"videos":[{"filename":"a\\b.mp4","subfolder":"","type":"output"}]}}}"""))
        assertNull(select("""{"outputs":{"1":{"videos":[{"filename":"..mp4","subfolder":"","type":"output"}]}}}"""))
        assertNull(select("""{"outputs":{"1":{"videos":[{"filename":"clip.mp4","subfolder":"../secret","type":"output"}]}}}"""))
    }

    @Test
    fun supportsGifsAndImagesArraysAndPicksVideosFirst() {
        val gif = select(
            """{"outputs":{"1":{"gifs":[{"filename":"g.mp4","subfolder":"","type":"output"}]}}}""",
        )
        assertEquals("g.mp4", gif?.filename)
        val image = select(
            """{"outputs":{"1":{"images":[{"filename":"i.mp4","subfolder":"","type":"output"}]}}}""",
        )
        assertEquals("i.mp4", image?.filename)
        val mixed = select(
            """{"outputs":{"1":{"gifs":[{"filename":"g.mp4","subfolder":"","type":"output"}]},"2":{"videos":[{"filename":"v.mp4","subfolder":"","type":"output"}]}}}""",
        )
        assertEquals("v.mp4", mixed?.filename)
    }

    @Test
    fun multipleOutputsAreDeterministicByNodeId() {
        val record = JSONObject(
            """{"outputs":{"2":{"videos":[{"filename":"two.mp4","subfolder":"","type":"output"}]},"1":{"videos":[{"filename":"one.mp4","subfolder":"","type":"output"}]}}}""",
        )
        assertEquals("one.mp4", RemoteComfyOutputSelector.select(record)?.filename)
    }

    @Test
    fun noOutputsReturnsNull() {
        assertNull(select("""{"status":{"status_str":"success"},"outputs":{}}"""))
        assertNull(select("""{"status":{"status_str":"success"}}"""))
    }

    @Test
    fun queueStatesAreDistinguished() {
        val queue = JSONObject(
            """{"queue_running":[[1,"p1"]],"queue_pending":[[2,"p2"]]}""",
        )
        assertEquals(RemoteComfyQueueState.RUNNING, RemoteComfyQueue.state(queue, "p1"))
        assertEquals(RemoteComfyQueueState.PENDING, RemoteComfyQueue.state(queue, "p2"))
        assertEquals(RemoteComfyQueueState.ABSENT, RemoteComfyQueue.state(queue, "p3"))

        val malformed = JSONObject("""{"queue_running":["bad-entry"],"queue_pending":[]}""")
        assertEquals(RemoteComfyQueueState.UNKNOWN, RemoteComfyQueue.state(malformed, "p1"))
        assertEquals(
            RemoteComfyQueueState.UNKNOWN,
            RemoteComfyQueue.state(JSONObject("{}"), "p1"),
        )
    }

    @Test
    fun historyErrorStatusWinsAndSummaryOmitsPromptAndKey() {
        val record = JSONObject(
            """{"status":{"status_str":"error","completed":true,"messages":[["execution_error",{"node_id":"5","exception_type":"RuntimeError","exception_message":"prompt=secret apiKey=sk-xyz"}]]},"outputs":{}}""",
        )
        assertEquals(RemoteComfyHistoryStatus.ERROR, RemoteComfyHistory.status(record))
        val summary = RemoteComfyHistory.errorSummary(record)
        assertTrue(summary.contains("RuntimeError"))
        assertTrue(summary.contains("5"))
        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("sk-xyz"))
        assertFalse(summary.contains("apiKey"))
    }

    @Test
    fun historySuccessAndUnknownAreSeparated() {
        assertEquals(
            RemoteComfyHistoryStatus.SUCCESS,
            RemoteComfyHistory.status(
                JSONObject("""{"status":{"status_str":"success","completed":true}}"""),
            ),
        )
        assertEquals(
            RemoteComfyHistoryStatus.SUCCESS,
            RemoteComfyHistory.status(
                JSONObject("""{"outputs":{"1":{"videos":[]}}}"""),
            ),
        )
        assertEquals(
            RemoteComfyHistoryStatus.UNKNOWN,
            RemoteComfyHistory.status(JSONObject("""{"outputs":{}}""")),
        )
    }

    @Test
    fun mp4SignatureNeedsFtypInsideFirstTwelveBytes() {
        val valid = File.createTempFile("mca-remote-mp4-", ".bin").apply {
            deleteOnExit()
            writeBytes(byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray(Charsets.US_ASCII))
        }
        assertTrue(RemoteComfyMp4.hasFtyp(RemoteComfyMp4.readPrefix(valid)))

        val png = File.createTempFile("mca-remote-png-", ".bin").apply {
            deleteOnExit()
            writeBytes(
                byteArrayOf(
                    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                    0, 0, 0, 0,
                ),
            )
        }
        assertFalse(RemoteComfyMp4.hasFtyp(RemoteComfyMp4.readPrefix(png)))

        val short = File.createTempFile("mca-remote-short-", ".bin").apply {
            deleteOnExit()
            writeBytes("ftyp".toByteArray(Charsets.US_ASCII))
        }
        assertFalse(RemoteComfyMp4.hasFtyp(RemoteComfyMp4.readPrefix(short)))
    }

    private fun select(json: String): RemoteComfyOutputRef? =
        RemoteComfyOutputSelector.select(JSONObject(json))
}

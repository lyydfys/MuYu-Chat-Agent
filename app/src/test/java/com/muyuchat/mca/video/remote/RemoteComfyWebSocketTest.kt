package com.muyuchat.mca.video.remote

import com.muyuchat.core.video.contract.VideoRunStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteComfyWebSocketTest {

    private val nodes = mapOf(
        "1" to "CLIPTextEncode",
        "2" to "KSampler",
        "3" to "VAEDecode",
        "4" to "SaveVideo",
    )

    @Test
    fun mapsKnownNodeClassesToStages() {
        assertEquals(VideoRunStage.TEXT_ENCODE, RemoteComfyNodeStages.stageFor("CLIPTextEncode"))
        assertEquals(VideoRunStage.SAMPLE, RemoteComfyNodeStages.stageFor("KSampler"))
        assertEquals(VideoRunStage.SAMPLE, RemoteComfyNodeStages.stageFor("KSamplerAdvanced"))
        assertEquals(VideoRunStage.SAMPLE, RemoteComfyNodeStages.stageFor("WanVideoSampler"))
        assertEquals(VideoRunStage.DECODE, RemoteComfyNodeStages.stageFor("VAEDecode"))
        assertEquals(VideoRunStage.DECODE, RemoteComfyNodeStages.stageFor("VAEDecodeTiled"))
        assertEquals(VideoRunStage.ENCODE, RemoteComfyNodeStages.stageFor("CreateVideo"))
        assertEquals(VideoRunStage.ENCODE, RemoteComfyNodeStages.stageFor("SaveVideo"))
        assertNull(RemoteComfyNodeStages.stageFor("LoadCheckpoint"))
        assertFalse(RemoteComfyNodeStages.isSampler("KSamplerSelect"))
    }

    @Test
    fun executingMappedNodeEmitsItsStage() {
        val projection = RemoteComfyWsProjection(nodes)
        val result = projection.apply(
            parse("""{"type":"executing","data":{"node":"1","prompt_id":"p1"}}"""),
        )
        val stage = result as RemoteComfyWsProjectionResult.Stage
        assertEquals(VideoRunStage.TEXT_ENCODE, stage.signal.stage)
        assertEquals("1", projection.currentNodeId)
    }

    @Test
    fun samplingProgressRequiresCurrentSamplerNode() {
        val projection = RemoteComfyWsProjection(nodes)
        projection.apply(parse("""{"type":"executing","data":{"node":"2","prompt_id":"p1"}}"""))
        val progress = projection.apply(
            parse("""{"type":"progress","data":{"value":3,"max":20,"node":"2","prompt_id":"p1"}}"""),
        )
        val stage = progress as RemoteComfyWsProjectionResult.Stage
        assertEquals(VideoRunStage.SAMPLE, stage.signal.stage)
        assertEquals(3L, stage.signal.step ?: -1L)
        assertEquals(20L, stage.signal.stepTotal ?: -1L)
    }

    @Test
    fun progressDoesNotMoveACompletedSamplerBackIntoStage() {
        val projection = RemoteComfyWsProjection(nodes)
        projection.apply(parse("""{"type":"executing","data":{"node":"2","prompt_id":"p1"}}"""))
        projection.apply(parse("""{"type":"executing","data":{"node":"3","prompt_id":"p1"}}"""))
        val stale = projection.apply(
            parse("""{"type":"progress","data":{"value":4,"max":20,"node":"2","prompt_id":"p1"}}"""),
        )
        assertSame(RemoteComfyWsProjectionResult.Ignored, stale)
    }

    @Test
    fun progressOnNonSamplerIsIgnored() {
        val projection = RemoteComfyWsProjection(nodes)
        projection.apply(parse("""{"type":"executing","data":{"node":"3","prompt_id":"p1"}}"""))
        val progress = projection.apply(
            parse("""{"type":"progress","data":{"value":4,"max":20,"node":"3","prompt_id":"p1"}}"""),
        )
        assertSame(RemoteComfyWsProjectionResult.Ignored, progress)
    }

    @Test
    fun nullExecutingNodeAndExecutionSuccessComplete() {
        val projection = RemoteComfyWsProjection(nodes)
        assertSame(
            RemoteComfyWsProjectionResult.Completed,
            projection.apply(parse("""{"type":"executing","data":{"node":null,"prompt_id":"p1"}}""")),
        )
        assertSame(
            RemoteComfyWsProjectionResult.Completed,
            projection.apply(parse("""{"type":"execution_success","data":{"prompt_id":"p1"}}""")),
        )
        assertSame(
            RemoteComfyWsProjectionResult.Ignored,
            projection.apply(
                parse("""{"type":"progress","data":{"value":4,"max":20,"node":"2","prompt_id":"p1"}}"""),
            ),
        )
    }

    @Test
    fun onlyOwnPromptEventsAreAccepted() {
        val own = parse("""{"type":"executing","data":{"node":"2","prompt_id":"p1"}}""")
        val foreign = parse("""{"type":"executing","data":{"node":"2","prompt_id":"p2"}}""")
        val noPrompt = parse("""{"type":"progress","data":{"value":1,"max":2,"node":"2"}}""")
        assertTrue(own.matchesPrompt("p1"))
        assertFalse(foreign.matchesPrompt("p1"))
        assertTrue(noPrompt.matchesPrompt("p1"))
    }

    @Test
    fun executionErrorSummaryContainsTypeButNeverPromptOrKey() {
        val projection = RemoteComfyWsProjection(nodes)
        val result = projection.apply(
            parse(
                """{"type":"execution_error","data":{"prompt_id":"p1","node_id":"2","exception_type":"RuntimeError","exception_message":"prompt=secret apiKey=sk-xyz"}}""",
            ),
        )
        val failed = result as RemoteComfyWsProjectionResult.Failed
        assertTrue(failed.summary.contains("RuntimeError"))
        assertFalse(failed.summary.contains("secret"))
        assertFalse(failed.summary.contains("sk-xyz"))
    }

    @Test
    fun malformedOrUnknownMessagesAreIgnored() {
        assertNull(RemoteComfyWsParser.parse("not-json"))
        assertNull(RemoteComfyWsParser.parse("""{"data":{}}"""))
        val other = RemoteComfyWsParser.parse("""{"type":"status","data":{}}""")
        assertTrue(other is RemoteComfyWsEvent.Other)
    }

    private fun parse(json: String): RemoteComfyWsEvent =
        RemoteComfyWsParser.parse(json) ?: throw AssertionError("expected a parsed event")
}

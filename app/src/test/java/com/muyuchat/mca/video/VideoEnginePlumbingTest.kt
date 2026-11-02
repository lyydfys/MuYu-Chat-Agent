package com.muyuchat.mca.video

import com.muyuchat.api.local.VideoGenerationApiRequest
import com.muyuchat.core.video.contract.VideoLocalEngine
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoResolvedParams
import com.muyuchat.core.video.contract.VideoRunProjection
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.core.video.contract.VideoRunState
import com.muyuchat.core.video.contract.VideoRunId
import com.muyuchat.core.video.contract.VideoAttemptId
import com.muyuchat.core.video.contract.VideoWorkflowId
import com.muyuchat.core.video.contract.VideoRevisionId
import com.muyuchat.core.video.contract.VideoResourceState
import com.muyuchat.feature.video.workspace.VideoWorkflowTemplates
import com.muyuchat.mca.presentation.localApiVideoResultJson
import com.muyuchat.mca.video.data.VideoMediaRecord
import com.muyuchat.mca.video.data.VideoSnapshotCodec
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles
import com.muyuchat.mca.video.worker.videoNativeRequestPayload
import com.muyuchat.mca.video.workflow.VideoWorkflowCompiler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine plumbing from the submit command to the native request body.
 *
 * Two invariants matter more than the feature itself: an unsupported engine must be a typed
 * rejection (never a silent fallback), and the DEFAULT engine must produce exactly the request
 * bytes the app produced before the engine field existed — the field is absent, not empty.
 */
class VideoEnginePlumbingTest {

    private val manifest = Wan22Ti2v5bProfiles.Q4_0_Q8_0

    private fun params(engine: String = VideoLocalEngine.DEFAULT) = VideoResolvedParams(
        width = 256,
        height = 256,
        frames = 5,
        fps = 24,
        steps = 1,
        sampler = "euler",
        guidance = VideoNodeValue.DecimalValue(60, 1),
        flowShift = VideoNodeValue.DecimalValue(30, 1),
        seed = 1234,
        promptSha256 = "a".repeat(64),
        negativePromptSha256 = null,
        imageRef = null,
        engine = engine,
    )

    private fun payload(engine: String = VideoLocalEngine.DEFAULT): JSONObject = videoNativeRequestPayload(
        runDir = "/data/runs/run-1",
        stagingRoot = "/data/video/runs",
        modelRoot = "/data/video_models/wan2.2-ti2v-5b",
        modelFingerprint = "fingerprint",
        ditPath = "/data/video_models/wan2.2-ti2v-5b/dit.gguf",
        encoderPath = "/data/video_models/wan2.2-ti2v-5b/umt5.gguf",
        vaePath = "/data/video_models/wan2.2-ti2v-5b/vae.safetensors",
        prompt = "a red apple",
        negativePrompt = "",
        imagePath = "",
        params = params(engine),
    )

    @Test
    fun `the default engine keeps the exact pre-engine request body`() {
        // Literal copy of the payload builder as it existed before the engine field. Any change in
        // keys, values or insertion order fails this test.
        val expected = JSONObject().apply {
            put("runDir", "/data/runs/run-1")
            put("stagingRoot", "/data/video/runs")
            put("modelRoot", "/data/video_models/wan2.2-ti2v-5b")
            put("modelFingerprint", "fingerprint")
            put("ditPath", "/data/video_models/wan2.2-ti2v-5b/dit.gguf")
            put("encoderPath", "/data/video_models/wan2.2-ti2v-5b/umt5.gguf")
            put("vaePath", "/data/video_models/wan2.2-ti2v-5b/vae.safetensors")
            put("prompt", "a red apple")
            put("negativePrompt", "")
            put("imagePath", "")
            put("width", 256L)
            put("height", 256L)
            put("frames", 5L)
            put("fps", 24L)
            put("steps", 1L)
            put("seed", 1234L)
            put("guidance", 6.0)
            put("flowShift", 3.0)
            put("sampler", "euler")
            put("threads", 4)
        }
        val actual = payload()
        assertFalse("the built-in engine must not add an engine field", actual.has("engine"))
        assertEquals(expected.toString(), actual.toString())
        assertEquals(
            "the serialized bytes must be identical to the pre-engine body",
            expected.toString().toByteArray(Charsets.UTF_8).toList(),
            actual.toString().toByteArray(Charsets.UTF_8).toList(),
        )
    }

    @Test
    fun `an explicit external engine adds engine and backend only`() {
        val withEngine = payload(VideoLocalEngine.SDCPP_EXTERNAL)
        assertEquals("sdcpp_external", withEngine.getString("engine"))
        assertEquals("htp", withEngine.getString("backend"))
        assertEquals("tae", withEngine.getString("vaeProfile"))
        assertEquals("disk", withEngine.getString("paramsBackend"))
        val stripped = JSONObject(withEngine.toString())
        stripped.remove("engine")
        stripped.remove("backend")
        stripped.remove("vaeProfile")
        stripped.remove("paramsBackend")
        assertEquals(
            "external engine settings must be the only difference from the default body",
            payload().toString(),
            stripped.toString(),
        )
    }

    @Test
    fun `the compiler freezes the chosen engine and rejects unknown values`() {
        val document = VideoWorkflowTemplates.textToVideoTemplate(prompt = "a cat")
        val compiled = VideoWorkflowCompiler.compile(
            document,
            manifest,
            VideoWorkflowCompiler.SeedPolicy.Fixed(7L),
            engine = VideoLocalEngine.SDCPP_EXTERNAL,
        ) as VideoWorkflowCompiler.Result.Compiled
        assertEquals(VideoLocalEngine.SDCPP_EXTERNAL, compiled.snapshot.resolvedParams.engine)
        assertEquals(7L, compiled.snapshot.resolvedParams.seed)

        val defaultCompiled = VideoWorkflowCompiler.compile(
            document,
            manifest,
            VideoWorkflowCompiler.SeedPolicy.Fixed(7L),
        ) as VideoWorkflowCompiler.Result.Compiled
        assertEquals(VideoLocalEngine.DEFAULT, defaultCompiled.snapshot.resolvedParams.engine)

        val rejected = VideoWorkflowCompiler.compile(
            document,
            manifest,
            VideoWorkflowCompiler.SeedPolicy.Fixed(7L),
            engine = "bogus_engine",
        )
        assertTrue(rejected is VideoWorkflowCompiler.Result.Rejected)
        assertEquals(
            com.muyuchat.core.video.contract.VideoErrorCode.INPUT_INVALID,
            (rejected as VideoWorkflowCompiler.Result.Rejected).firstCode(),
        )

        val badBackend = VideoWorkflowCompiler.compile(
            document,
            manifest,
            VideoWorkflowCompiler.SeedPolicy.Fixed(7L),
            engine = VideoLocalEngine.SDCPP_EXTERNAL,
            externalBackend = "gpu",
        )
        assertTrue(badBackend is VideoWorkflowCompiler.Result.Rejected)
        assertEquals(
            com.muyuchat.core.video.contract.VideoErrorCode.INPUT_INVALID,
            (badBackend as VideoWorkflowCompiler.Result.Rejected).firstCode(),
        )

        val cpuBackend = VideoWorkflowCompiler.compile(
            document,
            manifest,
            VideoWorkflowCompiler.SeedPolicy.Fixed(7L),
            engine = VideoLocalEngine.SDCPP_EXTERNAL,
            externalBackend = com.muyuchat.core.video.contract.VideoLocalBackend.CPU,
        ) as VideoWorkflowCompiler.Result.Compiled
        assertEquals("cpu", cpuBackend.snapshot.resolvedParams.externalBackend)
    }

    @Test
    fun `the snapshot keeps the engine and legacy rows still decode`() {
        val document = VideoWorkflowTemplates.textToVideoTemplate(prompt = "a cat")
        val snapshot = (
            VideoWorkflowCompiler.compile(
                document,
                manifest,
                VideoWorkflowCompiler.SeedPolicy.Fixed(3L),
                engine = VideoLocalEngine.SDCPP_EXTERNAL,
            ) as VideoWorkflowCompiler.Result.Compiled
            ).snapshot
        val encoded = VideoSnapshotCodec.encode(snapshot)
        val decoded = VideoSnapshotCodec.decode(encoded)
        assertEquals(VideoLocalEngine.SDCPP_EXTERNAL, decoded.resolvedParams.engine)
        assertEquals("htp", decoded.resolvedParams.externalBackend)

        // A snapshot persisted before either field existed must decode to the built-in engine and
        // the production backend, which is exactly the behaviour those runs had.
        val legacy = JSONObject(encoded)
        legacy.getJSONObject("params").remove("engine")
        legacy.getJSONObject("params").remove("externalBackend")
        val legacyDecoded = VideoSnapshotCodec.decode(legacy.toString())
        assertEquals(VideoLocalEngine.DEFAULT, legacyDecoded.resolvedParams.engine)
        assertEquals("htp", legacyDecoded.resolvedParams.externalBackend)
        assertNull(legacyDecoded.resolvedParams.imageRef)
    }

    @Test
    fun `the response echoes the actual backend and the fallback marker`() {
        val request = VideoGenerationApiRequest(
            rawBody = "{}",
            prompt = "a red apple",
            width = 256,
            height = 256,
            frames = 5,
            fps = 24,
            steps = 1,
            seed = 1234,
            engine = VideoLocalEngine.SDCPP_EXTERNAL,
            backend = "htp",
            ditPath = null,
            vaePath = null,
            tePath = null,
        )
        val projection = VideoRunProjection(
            runId = VideoRunId("v-run"),
            attemptId = VideoAttemptId("v-attempt"),
            workflowId = VideoWorkflowId("wf-run"),
            revisionId = VideoRevisionId("rev-run"),
            state = VideoRunState.COMPLETED,
            stage = VideoRunStage.FINALIZE,
            resourceState = VideoResourceState.RELEASED,
            createdAtMs = 1_000L,
            updatedAtMs = 2_000L,
            terminalAtMs = 2_000L,
        )
        val media = VideoMediaRecord(
            mediaId = "media-1",
            runId = "v-run",
            mimeType = "video/mp4",
            relativePath = "media-1.mp4",
            sizeBytes = 10_861L,
            sha256 = "a".repeat(64),
            width = 256L,
            height = 256L,
            durationUs = 208_300L,
            fps = 24L,
            frameCount = 5L,
            codec = "h264",
            hasAudio = false,
            createdAt = 2_000L,
            deletedAt = null,
        )
        val fallbackJson = JSONObject(
            localApiVideoResultJson(
                requestId = "req-1",
                request = request,
                projection = projection,
                media = media,
                elapsedMs = 1_000L,
                externalBackend = "cpu",
                fallback = "htp->cpu",
                fallbackReason = "htp_unavailable: fastrpc_denied",
            ),
        )
        assertEquals("cpu", fallbackJson.getString("external_backend"))
        assertEquals("cpu", fallbackJson.getJSONObject("execution").getString("external_backend"))
        assertEquals("htp->cpu", fallbackJson.getString("external_backend_fallback"))
        assertEquals(
            "htp_unavailable: fastrpc_denied",
            fallbackJson.getString("external_backend_reason"),
        )
        assertEquals(
            "htp_unavailable: fastrpc_denied",
            fallbackJson.getJSONObject("execution").getString("external_backend_reason"),
        )
        assertEquals(
            "htp->cpu",
            fallbackJson.getJSONObject("execution").getString("external_backend_fallback"),
        )
        assertEquals(5, fallbackJson.getJSONArray("data").getJSONObject(0).getInt("frame_count"))

        // No fallback: the requested backend is echoed and the marker is absent.
        val plainJson = JSONObject(
            localApiVideoResultJson(
                requestId = "req-2",
                request = request,
                projection = projection,
                media = media,
                elapsedMs = 1_000L,
                externalBackend = "htp",
            ),
        )
        assertEquals("htp", plainJson.getJSONObject("execution").getString("external_backend"))
        assertFalse(plainJson.has("external_backend_fallback"))
    }
}

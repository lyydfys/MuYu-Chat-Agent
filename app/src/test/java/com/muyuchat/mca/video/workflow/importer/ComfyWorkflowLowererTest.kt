package com.muyuchat.mca.video.workflow.importer

import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoImportPreview
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoWorkflowCanon
import com.muyuchat.core.video.contract.VideoWorkflowSource
import com.muyuchat.core.video.contract.validateVideoWorkflow
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles
import com.muyuchat.mca.video.workflow.VideoWorkflowCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyWorkflowLowererTest {

    private val manifest = Wan22Ti2v5bProfiles.Q4_0_Q8_0

    @Test
    fun `text-to-video lowers to the frozen template shape`() {
        val ready = ready(ComfyApiFixtures.t2v())
        assertTrue(ready.runnable)
        assertTrue(ready.diagnostics.none { it.severity.name == "ERROR" })
        val document = ready.document
        assertEquals(VideoWorkflowSource.COMFY_API, document.source)
        assertEquals("comfy-api/2026-10-25", document.metadata["comfy.mappingVersion"])
        assertEquals(
            listOf("n_input", "n_model", "n_cond", "n_sampler", "n_decode", "n_encode", "n_save"),
            document.nodes.map { it.nodeId },
        )
        assertEquals(8, document.edges.size)
        assertTrue(validateVideoWorkflow(document).isEmpty())

        val cond = document.node("n_cond")!!
        assertEquals(VideoNodeTypeId.WAN_CONDITIONING.typeId, cond.typeId)
        assertEquals(480L, (cond.params["width"] as VideoNodeValue.LongValue).value)
        assertEquals(832L, (cond.params["height"] as VideoNodeValue.LongValue).value)
        assertEquals(33L, (cond.params["frames"] as VideoNodeValue.LongValue).value)
        assertEquals(7L, (cond.params["seed"] as VideoNodeValue.LongValue).value)

        val sampler = document.node("n_sampler")!!
        assertEquals(20L, (sampler.params["steps"] as VideoNodeValue.LongValue).value)
        assertEquals("euler", (sampler.params["sampler"] as VideoNodeValue.TextValue).text)
        assertEquals(VideoNodeValue.DecimalValue(6000L, 3), sampler.params["guidance"])
        assertEquals(VideoNodeValue.DecimalValue(3000L, 3), sampler.params["flow_shift"])
        assertEquals(16L, (document.node("n_encode")!!.params["fps"] as VideoNodeValue.LongValue).value)
    }

    @Test
    fun `lowered T2V compiles with the production compiler`() {
        val ready = ready(ComfyApiFixtures.t2v())
        val result = VideoWorkflowCompiler.compile(
            ready.document,
            manifest,
            VideoWorkflowCompiler.SeedPolicy.Fixed(7L),
        )
        assertTrue("expected Compiled, got $result", result is VideoWorkflowCompiler.Result.Compiled)
        val snapshot = (result as VideoWorkflowCompiler.Result.Compiled).snapshot
        assertEquals(480L, snapshot.resolvedParams.width)
        assertEquals(832L, snapshot.resolvedParams.height)
        assertEquals(33L, snapshot.resolvedParams.frames)
        assertEquals(16L, snapshot.resolvedParams.fps)
        assertEquals(20L, snapshot.resolvedParams.steps)
        assertEquals("euler", snapshot.resolvedParams.sampler)
        assertEquals(VideoNodeValue.DecimalValue(6000L, 3), snapshot.resolvedParams.guidance)
        assertEquals(VideoNodeValue.DecimalValue(3000L, 3), snapshot.resolvedParams.flowShift)
        assertEquals(7L, snapshot.resolvedParams.seed)
        assertEquals(ready.document.canonicalSha256, snapshot.canonicalSha256)
    }

    @Test
    fun `image-to-video lowers but stays non-runnable`() {
        val ready = ready(ComfyApiFixtures.i2v())
        assertFalse(ready.runnable)
        assertTrue(validateVideoWorkflow(ready.document).isEmpty())
        val image = ready.document.node("n_image")
        assertNotNull(image)
        val ref = image!!.params["image"] as VideoNodeValue.ImageAssetRefValue
        assertEquals(ComfyWorkflowLowerer.UNRESOLVED_IMAGE_REF_PREFIX + "start.png", ref.assetId)
        assertTrue(ready.diagnostics.any { it.message.contains("仅记录来源名") })
        assertTrue(ready.diagnostics.any { it.nodeId == "12" })
        assertEquals("start.png", ready.document.metadata["comfy.imageSource"])
        assertEquals(9, ready.document.edges.size)
    }

    @Test
    fun `wan image latent output zero fallback stays non-runnable`() {
        val ready = ready(ComfyApiFixtures.i2v(latentOutputIndex = 0))
        assertFalse(ready.runnable)
        assertTrue(ready.diagnostics.any { it.message.contains("输出 0") })
        assertTrue(validateVideoWorkflow(ready.document).isEmpty())
    }

    @Test
    fun `exact thousandth conversion rounds half up`() {
        val ready = ready(ComfyApiFixtures.t2v(cfg = "6.0005", shift = "3.0005"))
        val sampler = ready.document.node("n_sampler")!!
        assertEquals(VideoNodeValue.DecimalValue(6001L, 3), sampler.params["guidance"])
        assertEquals(VideoNodeValue.DecimalValue(3001L, 3), sampler.params["flow_shift"])
    }

    @Test
    fun `wrong scheduler degrades runnable`() {
        val ready = ready(ComfyApiFixtures.t2v(scheduler = "simple"))
        assertFalse(ready.runnable)
        assertTrue(ready.diagnostics.any { it.message.contains("scheduler='simple'") })
        assertTrue(ready.diagnostics.any { it.path?.contains("/inputs/scheduler") == true })
        assertTrue(validateVideoWorkflow(ready.document).isEmpty())
    }

    @Test
    fun `wrong sampler and denoise degrade runnable`() {
        val wrongSampler = ready(ComfyApiFixtures.t2v(samplerName = "dpmpp_2m"))
        assertFalse(wrongSampler.runnable)
        assertEquals("dpmpp_2m", (wrongSampler.document.node("n_sampler")!!.params["sampler"] as VideoNodeValue.TextValue).text)
        assertTrue(wrongSampler.diagnostics.any { it.message.contains("仅 euler") })

        val wrongDenoise = ready(ComfyApiFixtures.t2v(denoise = "0.5"))
        assertFalse(wrongDenoise.runnable)
        assertTrue(wrongDenoise.diagnostics.any { it.message.contains("仅 1.0") })
    }

    @Test
    fun `non-aligned frame count is rejected`() {
        val result = rejected(ComfyApiFixtures.t2v(length = 32))
        assertEquals(VideoErrorCode.WORKFLOW_INVALID, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("4k+1") })
    }

    @Test
    fun `non-multiple-of-32 geometry is rejected`() {
        val result = rejected(ComfyApiFixtures.t2v(width = 500))
        assertEquals(VideoErrorCode.WORKFLOW_INVALID, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("32 的倍数") })
    }

    @Test
    fun `model filename mismatches keep the document but disable runnable`() {
        val cases = listOf(
            Triple("wan2.2_ti2v_5B_fp16.safetensors", ComfyApiFixtures.CLIP_Q8_0, ComfyApiFixtures.VAE),
            Triple(ComfyApiFixtures.UNET_Q4_0, "umt5-xxl-fp8.safetensors", ComfyApiFixtures.VAE),
            Triple(ComfyApiFixtures.UNET_Q4_0, ComfyApiFixtures.CLIP_Q8_0, "wan2.1_vae.safetensors"),
        )
        for ((unet, clip, vae) in cases) {
            val ready = ready(
                ComfyApiFixtures.t2v(unetName = unet, clipName = clip, vaeName = vae),
            )
            assertFalse("$unet/$clip/$vae must not be runnable", ready.runnable)
            assertTrue(ready.diagnostics.any { it.message.contains("固定 profile") })
            assertTrue(validateVideoWorkflow(ready.document).isEmpty())
            assertEquals("unbound", ready.document.metadata["comfy.modelBinding"])
        }
    }

    @Test
    fun `Q4_K_M loader names bind to their own profile`() {
        val ready = ready(
            ComfyApiFixtures.t2v(
                unetName = ComfyApiFixtures.UNET_Q4_K_M,
                clipName = ComfyApiFixtures.CLIP_Q4_K_M,
            ),
        )
        assertTrue(ready.runnable)
        assertTrue(ready.document.metadata["comfy.modelBinding"]!!.startsWith("profile:"))
        assertTrue(ready.document.metadata["comfy.modelBinding"]!!.contains("q4_k_m"))
    }

    @Test
    fun `save format and codec outside the matrix degrade runnable`() {
        val format = ready(ComfyApiFixtures.t2v(saveFormat = "webm"))
        assertFalse(format.runnable)
        assertTrue(format.diagnostics.any { it.message.contains("format='webm'") })

        val codec = ready(ComfyApiFixtures.t2v(saveCodec = "hevc"))
        assertFalse(codec.runnable)
        assertTrue(codec.diagnostics.any { it.message.contains("codec='hevc'") })
    }

    @Test
    fun `non-wan clip type degrades runnable`() {
        val ready = ready(ComfyApiFixtures.t2v(clipType = "sdxl"))
        assertFalse(ready.runnable)
        assertTrue(ready.diagnostics.any { it.message.contains("type='sdxl'") })
    }

    @Test
    fun `batch size other than one degrades runnable`() {
        val ready = ready(ComfyApiFixtures.t2v(batchSize = 2))
        assertFalse(ready.runnable)
        assertTrue(ready.diagnostics.any { it.message.contains("batch_size=2") })
    }

    @Test
    fun `blank prompt is saved but not runnable`() {
        val ready = ready(ComfyApiFixtures.t2v(prompt = ""))
        assertFalse(ready.runnable)
        assertTrue(ready.diagnostics.any { it.message.contains("提示词为空") })
        assertTrue(validateVideoWorkflow(ready.document).isEmpty())
        val compiled = VideoWorkflowCompiler.compile(
            ready.document,
            manifest,
            VideoWorkflowCompiler.SeedPolicy.Fixed(7L),
        )
        assertTrue(compiled is VideoWorkflowCompiler.Result.Rejected)
    }

    @Test
    fun `unused supported nodes are rejected instead of dropped`() {
        val json = ComfyApiFixtures.withExtraNode(
            ComfyApiFixtures.t2v(),
            "12",
            """{"class_type":"LoadImage","inputs":{"image":"extra.png"}}""",
        )
        val result = rejected(json)
        assertEquals(VideoErrorCode.WORKFLOW_INVALID, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("不会静默丢弃") })
    }

    @Test
    fun `missing ModelSamplingSD3 is rejected`() {
        val json = ComfyApiFixtures.t2v().replace("\"model\":[\"7\",0]", "\"model\":[\"1\",0]")
        val result = rejected(json)
        assertEquals(VideoErrorCode.WORKFLOW_INVALID, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("ModelSamplingSD3") })
    }

    @Test
    fun `identifiers and canonical digest are stable and source-bound`() {
        val a = ready(ComfyApiFixtures.t2v()).document
        val b = ready(ComfyApiFixtures.t2v()).document
        assertEquals(a.workflowId, b.workflowId)
        assertEquals(a.revisionId, b.revisionId)
        assertEquals(a.canonicalSha256, b.canonicalSha256)
        assertEquals(VideoWorkflowCanon.canonicalExecutableSha256(a), a.canonicalSha256)
        assertTrue(a.workflowId.value.startsWith("comfy-"))
        assertTrue(a.revisionId.value.startsWith("comfy-rev-"))
        assertTrue(a.workflowId.value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' })

        val changed = ready(ComfyApiFixtures.t2v(prompt = "a dog")).document
        assertNotEquals(a.canonicalSha256, changed.canonicalSha256)
        assertNotEquals(a.revisionId, changed.revisionId)
    }

    @Test
    fun `file name and raw source ref do not enter the executable digest`() {
        val json = ComfyApiFixtures.t2v()
        val a = (ComfyWorkflowLowerer.lower(json, "first.json", "doc://first") as VideoImportPreview.Ready).document
        val b = (ComfyWorkflowLowerer.lower(json, "second.json", "doc://second") as VideoImportPreview.Ready).document
        assertEquals(a.workflowId, b.workflowId)
        assertEquals(a.canonicalSha256, b.canonicalSha256)
        assertEquals("first.json", a.metadata["comfy.sourceName"])
        assertEquals("doc://first", a.rawSourceRef)
        assertEquals("second.json", b.metadata["comfy.sourceName"])
        assertEquals("doc://second", b.rawSourceRef)
        assertEquals(ComfyCompatibilityMatrix.MAPPING_VERSION, a.metadata["comfy.mappingVersion"])
    }

    @Test
    fun `metadata does not leak prompt text`() {
        val ready = ready(ComfyApiFixtures.t2v(prompt = "secret prompt", negative = "secret negative"))
        assertFalse(ready.document.metadata.values.any { it.contains("secret prompt") })
        assertFalse(ready.document.metadata.values.any { it.contains("secret negative") })
        assertTrue(ready.document.metadata["comfy.classTypes"]!!.contains("KSampler=1"))
    }

    @Test
    fun `lower convenience returns parser diagnostics for rejected input`() {
        val preview = ComfyWorkflowLowerer.lower("""{"nodes":[],"links":[]}""", "ui.json")
        assertTrue(preview is VideoImportPreview.Rejected)
        assertEquals(VideoErrorCode.INPUT_INVALID, (preview as VideoImportPreview.Rejected).code)
        assertTrue(preview.diagnostics.any { it.message.contains("请导出 API format") })
    }

    private fun ready(json: String): VideoImportPreview.Ready {
        val preview = ComfyWorkflowLowerer.lower(json, "fixture.json")
        assertTrue("expected Ready, got $preview", preview is VideoImportPreview.Ready)
        return preview as VideoImportPreview.Ready
    }

    private fun rejected(json: String): VideoImportPreview.Rejected {
        val preview = ComfyWorkflowLowerer.lower(json, "fixture.json")
        assertTrue("expected Rejected, got $preview", preview is VideoImportPreview.Rejected)
        return preview as VideoImportPreview.Rejected
    }
}

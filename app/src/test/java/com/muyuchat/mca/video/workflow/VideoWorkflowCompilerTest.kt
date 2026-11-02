package com.muyuchat.mca.video.workflow

import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoInputBinding
import com.muyuchat.core.video.contract.VideoPortKind
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles
import com.muyuchat.feature.video.workspace.VideoWorkflowTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compiler is the production path from ANY MCA-native draft (template or hand-edited) to the
 * frozen run snapshot (proposal §10.4). These tests pin: baseline parameter lowering, seed
 * freeze policy, image binding, model-manifest agreement, rejection of unmappable drafts and
 * snapshot determinism.
 */
class VideoWorkflowCompilerTest {

    private val manifest = Wan22Ti2v5bProfiles.Q4_0_Q8_0

    private fun compile(
        doc: com.muyuchat.core.video.contract.VideoWorkflowDocument,
        seed: Long = 42L,
        refs: Map<String, VideoInputBinding> = emptyMap(),
    ) = VideoWorkflowCompiler.compile(doc, manifest, VideoWorkflowCompiler.SeedPolicy.Fixed(seed), refs)

    @Test
    fun `text-to-video template compiles with the product recipe parameters`() {
        val result = compile(VideoWorkflowTemplates.textToVideoTemplate(prompt = "a cat"))
        assertTrue("expected Compiled, got $result", result is VideoWorkflowCompiler.Result.Compiled)
        val snapshot = (result as VideoWorkflowCompiler.Result.Compiled).snapshot
        assertEquals(480L, snapshot.resolvedParams.width)
        assertEquals(832L, snapshot.resolvedParams.height)
        assertEquals(5L, snapshot.resolvedParams.frames)
        assertEquals(24L, snapshot.resolvedParams.fps)
        assertEquals(4L, snapshot.resolvedParams.steps)
        assertEquals("euler", snapshot.resolvedParams.sampler)
        assertEquals(42L, snapshot.resolvedParams.seed)
        assertEquals(64, snapshot.resolvedParams.promptSha256.length)
        assertNull(snapshot.resolvedParams.imageRef)
        assertNull(snapshot.resolvedParams.negativePromptSha256)
        assertNotNull(snapshot.canonicalSha256)
        assertEquals(manifest.modelId, "wan2.2-ti2v-5b")
    }

    @Test
    fun `negative prompt and random seed freeze into the snapshot`() {
        val result = VideoWorkflowCompiler.compile(
            VideoWorkflowTemplates.textToVideoTemplate(prompt = "p", negativePrompt = "blurry"),
            manifest,
            VideoWorkflowCompiler.SeedPolicy.Randomized(drawnSeed = 1234567890123L),
        )
        val snapshot = (result as VideoWorkflowCompiler.Result.Compiled).snapshot
        assertEquals(1234567890123L, snapshot.resolvedParams.seed)
        assertNotNull(snapshot.resolvedParams.negativePromptSha256)
    }

    @Test
    fun `image-to-video requires a resolved image and freezes its binding`() {
        val noImage = compile(VideoWorkflowTemplates.imageToVideoTemplate(prompt = "p"))
        assertTrue(noImage is VideoWorkflowCompiler.Result.Rejected)

        val literal = compile(VideoWorkflowTemplates.imageToVideoTemplate(prompt = "p", imageAssetId = "img-7"))
        val snapshot = (literal as VideoWorkflowCompiler.Result.Compiled).snapshot
        assertEquals("img-7", snapshot.resolvedParams.imageRef?.reference)

        val ownerResolved = compile(
            VideoWorkflowTemplates.imageToVideoTemplate(prompt = "p"),
            refs = mapOf(
                "n_image" to VideoInputBinding("n_image", VideoPortKind.IMAGE_REF, "content-resolved-1", sha256 = "ab"),
            ),
        )
        val snapshot2 = (ownerResolved as VideoWorkflowCompiler.Result.Compiled).snapshot
        assertEquals("content-resolved-1", snapshot2.resolvedParams.imageRef?.reference)
        assertEquals("ab", snapshot2.resolvedParams.imageRef?.sha256)
    }

    @Test
    fun `model manifest mismatch is rejected with the role code`() {
        val doc = VideoWorkflowTemplates.textToVideoTemplate(prompt = "p").let { base ->
            base.copy(
                nodes = base.nodes.map {
                    if (it.nodeId == "n_model") {
                        it.copy(params = it.params + ("manifest" to com.muyuchat.core.video.contract.VideoNodeValue.ModelRefValue("some.other.model")))
                    } else {
                        it
                    }
                },
            )
        }
        val result = compile(doc)
        assertTrue(result is VideoWorkflowCompiler.Result.Rejected)
        assertEquals(VideoErrorCode.MODEL_ROLE_MISMATCH, (result as VideoWorkflowCompiler.Result.Rejected).firstCode())
    }

    @Test
    fun `structurally invalid drafts are rejected before lowering`() {
        val doc = VideoWorkflowTemplates.textToVideoTemplate(prompt = "p").let { base ->
            base.copy(
                nodes = base.nodes.map {
                    if (it.nodeId == "n_cond") {
                        it.copy(params = it.params + ("frames" to com.muyuchat.core.video.contract.VideoNodeValue.LongValue(6)))
                    } else {
                        it
                    }
                },
            )
        }
        val result = compile(doc)
        assertTrue(result is VideoWorkflowCompiler.Result.Rejected)
        assertTrue((result as VideoWorkflowCompiler.Result.Rejected).issues.any { it.detail.contains("Wan-aligned") })
    }

    @Test
    fun `empty prompt is rejected`() {
        val result = compile(VideoWorkflowTemplates.textToVideoTemplate(prompt = ""))
        assertTrue(result is VideoWorkflowCompiler.Result.Rejected)
    }

    @Test
    fun `compilation is deterministic and sensitive to executable content`() {
        val docA = VideoWorkflowTemplates.textToVideoTemplate(prompt = "a cat", seed = 7)
        val docB = docA.copy(
            nodes = docA.nodes.map {
                if (it.nodeId == "n_input") it.copy(ui = it.ui.copy(label = "随便标", positionX = 999f)) else it
            },
        )
        val snapA = compile(docA, seed = 7).let { it as VideoWorkflowCompiler.Result.Compiled }.snapshot
        val snapB = compile(docB, seed = 7).let { it as VideoWorkflowCompiler.Result.Compiled }.snapshot
        assertEquals("UI-only edits must not change the executable digest", snapA.canonicalSha256, snapB.canonicalSha256)

        val docC = VideoWorkflowTemplates.textToVideoTemplate(prompt = "a dog", seed = 7)
        val snapC = compile(docC, seed = 7).let { it as VideoWorkflowCompiler.Result.Compiled }.snapshot
        assertNotEquals(snapA.canonicalSha256, snapC.canonicalSha256)
        assertNotEquals(snapA.resolvedParams.promptSha256, snapC.resolvedParams.promptSha256)
        assertEquals(snapA.modelFingerprint, snapC.modelFingerprint)
    }

    @Test
    fun `model fingerprint is stable and covers the pins`() {
        val a = VideoWorkflowCompiler.modelFingerprintOf(manifest)
        val b = VideoWorkflowCompiler.modelFingerprintOf(Wan22Ti2v5bProfiles.Q4_0_Q8_0)
        assertEquals(a, b)
        assertNotEquals(a, VideoWorkflowCompiler.modelFingerprintOf(Wan22Ti2v5bProfiles.Q4_K_M_Q4_K_M))
        assertEquals(64, a.length)
    }
}

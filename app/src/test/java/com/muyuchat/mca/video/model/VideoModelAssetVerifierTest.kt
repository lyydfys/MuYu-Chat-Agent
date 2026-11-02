package com.muyuchat.mca.video.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Asset verification matrix of [VideoModelAssetVerifier] (V1-01 / P0-A): complete, missing,
 * truncated (interrupted), foreign same-name file, checksum pass, size-only pin and the
 * disk-space precondition. Fixtures use a small synthetic manifest with the same role shape as
 * the real profile; the real pinned facts are asserted separately below.
 */
class VideoModelAssetVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tinyManifest(): VideoModelManifest {
        fun comp(role: VideoModelComponentRole, path: String, sha: String?) = VideoModelComponentFile(
            role = role,
            relativePath = path,
            format = "gguf",
            quantization = "TEST",
            sizeBytes = 100L,
            sha256 = sha,
            sourceUrl = "https://example.invalid/$path",
            license = "test",
            provenance = "test fixture",
        )
        return VideoModelManifest(
            modelId = "wan2.2-ti2v-5b",
            family = "WAN2_2",
            variant = "TI2V_5B",
            revision = "test",
            components = listOf(
                comp(VideoModelComponentRole.DIT, "dit/a.gguf", sha = "matching"),
                comp(VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL, "text_encoder/b.gguf", sha = "matching"),
                comp(VideoModelComponentRole.VAE_WAN22_5B, "vae/c.safetensors", sha = "matching"),
            ),
            referenceSamplerRecipe = Wan22Ti2v5bProfiles.REFERENCE_RECIPE,
        )
    }

    private fun write(root: File, path: String, bytes: Int): File {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes) { it.toByte() })
        return f
    }

    @Test
    fun `complete bundle with matching checksums is ready`() {
        val root = tmp.newFolder()
        val manifest = tinyManifest()
        val paths = listOf("dit/a.gguf", "text_encoder/b.gguf", "vae/c.safetensors")
        paths.forEach { write(root, it, 100) }
        val outcome = VideoModelAssetVerifier.verify(
            manifest, root,
            hashFile = { _ -> "matching" },
        )
        assertTrue("expected Ready, got $outcome", outcome is VideoModelAssetVerification.Ready)
    }

    @Test
    fun `an absent optional component does not block readiness`() {
        val root = tmp.newFolder()
        val base = tinyManifest()
        val manifest = base.copy(
            components = base.components + VideoModelComponentFile(
                role = VideoModelComponentRole.TAE_HEAD,
                relativePath = "vae/tae.safetensors",
                format = "safetensors",
                quantization = "TEST",
                sizeBytes = 10L,
                sha256 = "matching",
                sourceUrl = "https://example.invalid/tae",
                license = "test",
                provenance = "test fixture",
                required = false,
            ),
        )
        listOf("dit/a.gguf", "text_encoder/b.gguf", "vae/c.safetensors").forEach { write(root, it, 100) }
        val outcome = VideoModelAssetVerifier.verify(manifest, root, hashFile = { _ -> "matching" })
        assertTrue("expected Ready, got $outcome", outcome is VideoModelAssetVerification.Ready)
        val ready = outcome as VideoModelAssetVerification.Ready
        assertTrue(
            "the optional head must still be reported as missing",
            ready.components.any {
                it is ComponentVerification.Missing && it.file.role == VideoModelComponentRole.TAE_HEAD
            },
        )
    }

    @Test
    fun `missing component is reported as missing`() {
        val root = tmp.newFolder()
        write(root, "dit/a.gguf", 100)
        // encoder + vae absent
        val outcome = VideoModelAssetVerifier.verify(tinyManifest(), root, hashFile = { _ -> "x" })
        assertTrue(outcome is VideoModelAssetVerification.Problems)
        val problems = outcome as VideoModelAssetVerification.Problems
        val missing = problems.components.filterIsInstance<ComponentVerification.Missing>()
        assertEquals(2, missing.size)
        assertTrue(problems.isIncomplete)
    }

    @Test
    fun `truncated file reads as interrupted download, not foreign file`() {
        val root = tmp.newFolder()
        write(root, "dit/a.gguf", 40) // interrupted mid-download
        write(root, "text_encoder/b.gguf", 100)
        write(root, "vae/c.safetensors", 100)
        val outcome = VideoModelAssetVerifier.verify(tinyManifest(), root, hashFile = { _ -> "matching" })
        val problems = outcome as VideoModelAssetVerification.Problems
        val truncated = problems.components.filterIsInstance<ComponentVerification.SizeMismatch>()
        assertEquals(1, truncated.size)
        assertEquals(40L, truncated.first().actualBytes)
    }

    @Test
    fun `same-name foreign file fails checksum`() {
        val root = tmp.newFolder()
        listOf("dit/a.gguf", "text_encoder/b.gguf", "vae/c.safetensors").forEach { write(root, it, 100) }
        val outcome = VideoModelAssetVerifier.verify(tinyManifest(), root, hashFile = { file ->
            if (file.name == "a.gguf") "totally-different" else "matching"
        })
        val problems = outcome as VideoModelAssetVerification.Problems
        val mismatched = problems.components.filterIsInstance<ComponentVerification.ChecksumMismatch>()
        assertEquals(1, mismatched.size)
        assertEquals(VideoModelComponentRole.DIT, mismatched.first().file.role)
    }

    @Test
    fun `size-only pin is usable but never reported as checksum-verified`() {
        val root = tmp.newFolder()
        val manifest = tinyManifest().let { base ->
            base.copy(
                components = base.components.map {
                    if (it.role == VideoModelComponentRole.DIT) it.copy(sha256 = null) else it
                },
            )
        }
        listOf("dit/a.gguf", "text_encoder/b.gguf", "vae/c.safetensors").forEach { write(root, it, 100) }
        val outcome = VideoModelAssetVerifier.verify(manifest, root, hashFile = { _ -> "matching" })
        // Not Ready (checksum unverified) and not failed: an honest middle state.
        assertTrue(outcome is VideoModelAssetVerification.Problems)
        val problems = outcome as VideoModelAssetVerification.Problems
        assertFalse("size-only components must not count as incomplete", problems.isIncomplete)
        assertTrue(problems.components.any { it is ComponentVerification.SizeOnlyOk })
    }

    @Test
    fun `insufficient space is reported before any download starts`() {
        val root = tmp.newFolder()
        listOf("dit/a.gguf", "text_encoder/b.gguf", "vae/c.safetensors").forEach { write(root, it, 100) }
        val outcome = VideoModelAssetVerifier.verify(
            tinyManifest(), root,
            availableSpaceBytes = { 250L }, // needs 300
            hashFile = { _ -> "matching" },
        )
        val problems = outcome as VideoModelAssetVerification.Problems
        assertEquals("shortfall must be reported", 51L, problems.insufficientSpaceBytes)
    }

    @Test
    fun `real pinned profiles carry complete role chains and sizes`() {
        for (manifest in Wan22Ti2v5bProfiles.ALL) {
            assertEquals("wan2.2-ti2v-5b", manifest.modelId)
            assertEquals(setOf("text_to_video", "image_to_video"), manifest.supportedModes)
            assertEquals(32L, manifest.dimensionMultiple)
            assertTrue("no measured presets may exist before P0-C", manifest.measuredPresets.isEmpty())
            val dit = manifest.componentFor(VideoModelComponentRole.DIT)
            val encoder = manifest.componentFor(VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL)
            val vae = manifest.componentFor(VideoModelComponentRole.VAE_WAN22_5B)
            assertTrue("DiT pin must be multi-GB class", dit.sizeBytes > 2_000_000_000L)
            assertTrue("encoder pin must be multi-GB class", encoder.sizeBytes > 3_000_000_000L)
            assertEquals(1_409_400_960L, vae.sizeBytes)
            assertTrue("VAE pin must carry a sha256", vae.sha256 != null && vae.sha256.length == 64)
        }
        // The primary profile must be fully sha-pinned. These are the Hugging Face LFS oids
        // (sha256 of the file bytes), not the Xet CAS ids recorded by mistake in the first pin
        // set; this assertion is the regression guard for that defect.
        val primary = Wan22Ti2v5bProfiles.Q4_0_Q8_0
        assertTrue(primary.components.all { it.sha256 != null && it.sha256.length == 64 })
        assertEquals(
            "fcf40dd62cb5e15556eef5f975700ab0b071aacd1939607689276e88f81ba463",
            primary.componentFor(VideoModelComponentRole.DIT).sha256,
        )
        assertEquals(
            "2521d4de0bf9e1cc6549866463ceae85e4ec3239bc6063f7488810be39033bbc",
            primary.componentFor(VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL).sha256,
        )
        assertEquals(
            "e40321bd36b9709991dae2530eb4ac303dd168276980d3e9bc4b6e2b75fed156",
            primary.componentFor(VideoModelComponentRole.VAE_WAN22_5B).sha256,
        )
        assertTrue(Wan22Ti2v5bProfiles.Q4_K_M_Q4_K_M.components.all { it.sha256 != null && it.sha256.length == 64 })
        val lora = primary.componentFor(VideoModelComponentRole.LORA_TURBO)
        val tae = primary.componentFor(VideoModelComponentRole.TAE_HEAD)
        assertEquals(332_348_584L, lora.sizeBytes)
        assertEquals("0ace5244e3d1256f884662c261b017249796cf5b95f05d5ed93cc02a478967b8", lora.sha256)
        assertTrue("the Turbo LoRA is required for the product recipe", lora.required)
        assertEquals(22_848_048L, tae.sizeBytes)
        assertEquals("b84609b2a133d48434bd9636bfcb44bf05168dc436e2d3cecf26256faa1f5325", tae.sha256)
        assertFalse("the TAE head only backs the optional decode path", tae.required)
        assertTrue(lora.sourceUrl.contains("https://modelscope.cn/models/lyydfys/mca-wan22-video-assets/resolve/"))
        assertTrue(tae.sourceUrl.contains("https://modelscope.cn/models/lyydfys/mca-wan22-video-assets/resolve/"))
        // Reference recipe is the upstream BASELINE, never a 4-step Turbo schedule.
        assertEquals("euler", Wan22Ti2v5bProfiles.REFERENCE_RECIPE.sampler)
        assertEquals(33L, Wan22Ti2v5bProfiles.REFERENCE_RECIPE.referenceFrames)
        assertTrue(Wan22Ti2v5bProfiles.REFERENCE_RECIPE.referenceFrames % 4L == 1L)
    }
}

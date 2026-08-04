package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnInpaintContractTest {
    @Test
    fun `stale inpaint cleanup is exact direct and older than 24 hours`() {
        val cacheRoot = Files.createTempDirectory("qnn-inpaint-artifacts").toFile()
        try {
            val outputRoot = File(cacheRoot, QNN_SHARED_ARTIFACT_DIRECTORY).apply { mkdirs() }
            val staleToken = "qnn-htp-1721430000000-123e4567-e89b-12d3-a456-426614174000"
            val freshToken = "qnn-htp-1721430000001-123e4567-e89b-12d3-a456-426614174001"
            val nowMs = 4L * QNN_SHARED_ARTIFACT_MAX_AGE_MS
            val staleMs = nowMs - QNN_SHARED_ARTIFACT_MAX_AGE_MS - 1L
            val freshMs = nowMs - QNN_SHARED_ARTIFACT_MAX_AGE_MS + 1L
            val stale = listOf(
                ".inpaint-mask-latent.f32",
                ".inpaint-mask-latent.f32.part",
                ".inpaint-masked-rgb-nchw.f32",
                ".inpaint-masked-rgb-nchw.f32.part",
            ).map { suffix ->
                File(outputRoot, staleToken + suffix).apply {
                    writeBytes(byteArrayOf(1))
                    setLastModified(staleMs)
                }
            }
            val fresh = File(outputRoot, "$freshToken.inpaint-mask-latent.f32").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(freshMs)
            }
            val wrong = File(outputRoot, "$staleToken.inpaint-mask-latent.f32.backup").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(staleMs)
            }

            assertEquals(stale.size, QnnInpaintInputArtifact.cleanupStaleArtifacts(cacheRoot, nowMs))
            stale.forEach { assertFalse(it.exists()) }
            assertTrue(fresh.exists())
            assertTrue(wrong.exists())
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `topology accepts nchw latent blend and explicit mask conditioned graphs`() {
        val concatenated = QnnInpaintTopology.inspect(
            listOf(tensor("sample", 1, 9, 64, 64), tensor("timestep", 1), tensor("text", 1, 77, 768)),
        )
        assertTrue(concatenated.supported)
        assertEquals(QnnInpaintMaskTopology.CONCATENATED_LATENT_9, concatenated.topology)
        assertEquals(QnnInpaintTensorLayout.NCHW, concatenated.layout)

        val separate = QnnInpaintTopology.inspect(
            listOf(tensor("sample", 1, 4, 64, 64), tensor("inpaint_mask", 1, 1, 64, 64)),
        )
        assertTrue(separate.supported)
        assertEquals(QnnInpaintMaskTopology.SEPARATE_MASK_INPUT, separate.topology)

        val latentBlend = QnnInpaintTopology.inspect(
            listOf(tensor("sample", 1, 4, 64, 64)),
        )
        assertTrue(latentBlend.supported)
        assertEquals(QnnInpaintMaskTopology.LATENT_BLEND_4, latentBlend.topology)
        assertFalse(latentBlend.requiresNativeMaskBinding)
        assertFalse(latentBlend.requiresMaskedImageLatent)

        assertFalse(
            QnnInpaintTopology.inspect(
                listOf(tensor("sample", 1, 64, 64, 9)),
            ).supported,
        )
    }

    @Test
    fun `multiple semantic masks remain ambiguous regardless of later inputs`() {
        val result = QnnInpaintTopology.inspect(
            listOf(
                tensor("sample", 1, 4, 64, 64),
                tensor("mask", 1, 1, 64, 64),
                tensor("inpaint_mask", 1, 1, 64, 64),
                tensor("conditioning_mask", 1, 1, 64, 64),
            ),
        )
        assertFalse(result.supported)
        assertTrue(result.reason.contains("multiple", ignoreCase = true))
    }

    @Test
    fun `mask conversion preserves alpha weighted grayscale and area coverage at latent scale`() {
        assertEquals(1.0f, qnnInpaintGrayscaleMaskValue(0xffffffff.toInt()))
        assertEquals(0.0f, qnnInpaintGrayscaleMaskValue(0x00ffffff))
        assertEquals(127.0f / 255.0f, qnnInpaintGrayscaleMaskValue(0xff7f7f7f.toInt()))
        assertEquals(128.0f / 255.0f, qnnInpaintGrayscaleMaskValue(0xff808080.toInt()))
        assertEquals(128.0f / 255.0f, qnnInpaintGrayscaleMaskValue(0x80ffffff.toInt()))

        val mask = FloatArray(16 * 8)
        for (y in 0 until 8) {
            for (x in 0 until 4) mask[y * 16 + x] = 1.0f
            for (x in 8 until 16) mask[y * 16 + x] = 0.25f
        }
        assertEquals(
            listOf(0.5f, 0.25f),
            downsampleQnnInpaintMaskArea(mask, width = 16, height = 8).toList(),
        )
        assertEquals(6, qnnInpaintLaplacianLevelCount(512, 512))
    }

    @Test
    fun `prepared inpaint params declare rgb input latent mask and no fabricated latent`() {
        val source = QnnPreparedInputTensor(
            sourcePath = "/cache/source.png",
            sourceSha256 = "a".repeat(64),
            sourceBytes = 100,
            sourceWidth = 512,
            sourceHeight = 512,
            exifOrientation = 1,
            orientedWidth = 512,
            orientedHeight = 512,
            tensorPath = "/cache/source.f32",
            tensorSha256 = "b".repeat(64),
            tensorBytes = 1L * 3 * 512 * 512 * Float.SIZE_BYTES,
            tensorWidth = 512,
            tensorHeight = 512,
        )
        val prepared = QnnPreparedInpaintInput(
            source = source,
            topology = QnnInpaintMaskTopology.CONCATENATED_LATENT_9,
            maskSourcePath = "/cache/mask.png",
            maskSourceSha256 = "c".repeat(64),
            maskSourceBytes = 50,
            maskSourceWidth = 512,
            maskSourceHeight = 512,
            maskExifOrientation = 1,
            maskOrientedWidth = 512,
            maskOrientedHeight = 512,
            maskTensorPath = "/cache/mask.f32",
            maskTensorSha256 = "d".repeat(64),
            maskTensorBytes = 1L * 1 * 64 * 64 * Float.SIZE_BYTES,
            fullMaskTensorPath = "/cache/mask-full.f32",
            fullMaskTensorSha256 = "f".repeat(64),
            fullMaskTensorBytes = 1L * 1 * 512 * 512 * Float.SIZE_BYTES,
            maskedInputTensorPath = "/cache/masked-rgb.f32",
            maskedInputTensorSha256 = "e".repeat(64),
            maskedInputTensorBytes = 1L * 3 * 512 * 512 * Float.SIZE_BYTES,
            targetWidth = 512,
            targetHeight = 512,
            repaintPixelCount = 100,
            latentRepaintPixelCount = 4,
        )
        val params = prepared.putNativeParams(JSONObject())

        assertEquals(QNN_INPAINT_ARTIFACT_VERSION, params.getInt("inpaintArtifactVersion"))
        assertEquals(listOf(1, 1, 64, 64), params.intList("maskImageTensorShape"))
        assertEquals(listOf(1, 1, 512, 512), params.intList("maskImageFullTensorShape"))
        assertEquals(listOf(1, 3, 512, 512), params.intList("maskedInputImageTensorShape"))
        assertEquals("NCHW", params.getString("maskImageTensorLayout"))
        assertEquals("NCHW", params.getString("maskedInputImageTensorLayout"))
        assertEquals(QNN_INPAINT_MASK_CONVENTION, params.getString("inpaintMaskConvention"))
        assertFalse(params.has("maskedInputLatentPath"))
        assertFalse(params.has("maskedInputLatentSha256"))
    }

    @Test
    fun `four channel latent blend params require no fabricated masked image latent`() {
        val prepared = QnnPreparedInpaintInput(
            source = QnnPreparedInputTensor(
                sourcePath = "/cache/source.png",
                sourceSha256 = "a".repeat(64),
                sourceBytes = 100,
                sourceWidth = 512,
                sourceHeight = 512,
                exifOrientation = 1,
                orientedWidth = 512,
                orientedHeight = 512,
                tensorPath = "/cache/source.f32",
                tensorSha256 = "b".repeat(64),
                tensorBytes = 1L * 3 * 512 * 512 * Float.SIZE_BYTES,
                tensorWidth = 512,
                tensorHeight = 512,
            ),
            topology = QnnInpaintMaskTopology.LATENT_BLEND_4,
            maskSourcePath = "/cache/mask.png",
            maskSourceSha256 = "c".repeat(64),
            maskSourceBytes = 50,
            maskSourceWidth = 512,
            maskSourceHeight = 512,
            maskExifOrientation = 1,
            maskOrientedWidth = 512,
            maskOrientedHeight = 512,
            maskTensorPath = "/cache/mask.f32",
            maskTensorSha256 = "d".repeat(64),
            maskTensorBytes = 1L * 64 * 64 * Float.SIZE_BYTES,
            fullMaskTensorPath = "/cache/mask-full.f32",
            fullMaskTensorSha256 = "f".repeat(64),
            fullMaskTensorBytes = 1L * 512 * 512 * Float.SIZE_BYTES,
            maskedInputTensorPath = null,
            maskedInputTensorSha256 = null,
            maskedInputTensorBytes = 0,
            targetWidth = 512,
            targetHeight = 512,
            repaintPixelCount = 100,
            latentRepaintPixelCount = 4,
        )

        val params = prepared.putNativeParams(JSONObject())
        assertEquals("latent_blend_4", params.getString("inpaintRequestedTopology"))
        assertFalse(params.has("maskedInputImageTensorPath"))
        assertFalse(params.has("maskedInputImageTensorSha256"))
        assertFalse(params.has("maskedInputImageTensorShape"))
    }

    @Test
    fun `inpaint schedule uses the img2img float32 tail contract`() {
        val schedule = resolveQnnInpaintSchedule(steps = 30, fullTimetableCount = 30, strength = 0.6)
        assertEquals(11, schedule.beginIndex)
        assertEquals(19, schedule.effectiveSteps)
    }

    private fun tensor(name: String, vararg shape: Int): QnnSmokeTensorSpec = QnnSmokeTensorSpec(
        name = name,
        role = "input",
        dataType = "uint16",
        shape = shape.toList(),
    )

    private fun JSONObject.intList(name: String): List<Int> = getJSONArray(name).let { array ->
        List(array.length()) { index -> array.getInt(index) }
    }
}

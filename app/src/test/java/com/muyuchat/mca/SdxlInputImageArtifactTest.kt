package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SdxlInputImageArtifactTest {
    @Test
    fun `stale shared qnn cleanup is canonical direct exact and older than 24 hours`() {
        val cacheRoot = Files.createTempDirectory("shared-qnn-artifacts").toFile()
        try {
            val outputRoot = File(cacheRoot, QNN_SHARED_ARTIFACT_DIRECTORY).apply { mkdirs() }
            val staleToken = "qnn-htp-1721430000000-123e4567-e89b-12d3-a456-426614174000"
            val freshToken = "qnn-htp-1721430000001-123e4567-e89b-12d3-a456-426614174001"
            val boundaryToken = "qnn-htp-1721430000002-123e4567-e89b-12d3-a456-426614174002"
            val directoryToken = "qnn-htp-1721430000003-123e4567-e89b-12d3-a456-426614174003"
            val nowMs = 4L * QNN_SHARED_ARTIFACT_MAX_AGE_MS
            val staleMs = nowMs - QNN_SHARED_ARTIFACT_MAX_AGE_MS - 1L
            val freshMs = nowMs - QNN_SHARED_ARTIFACT_MAX_AGE_MS + 1L
            val boundaryMs = nowMs - QNN_SHARED_ARTIFACT_MAX_AGE_MS
            val exactSuffixes = listOf(
                ".png",
                ".png.part",
                ".sdxl-conditioning.f32",
                ".sdxl-conditioning.f32.part",
                ".qnn-clip-conditioning.bin",
                ".qnn-clip-conditioning.bin.part",
                ".qnn-clip-token-ids.i32",
                ".qnn-clip-token-ids.i32.part",
                ".sd15-embeddings.f32",
                ".sd15-embeddings.f32.part",
                ".latent.f32",
                ".latent.f32.part",
                ".latent.json",
                ".latent.json.part",
                ".input-rgb-nchw.f32",
                ".input-rgb-nchw.f32.part",
                ".encoder-latent.f32",
                ".encoder-latent.f32.part",
                ".encoder-latent.json",
                ".encoder-latent.json.part",
                ".qnn-stage.json",
                ".qnn-stage.json.tmp",
                ".encoder-stage.json",
                ".encoder-stage.json.tmp",
                ".unet-stage.json",
                ".unet-stage.json.tmp",
                ".vae-stage.json",
                ".vae-stage.json.tmp"
            )
            val staleFiles = exactSuffixes.map { suffix ->
                File(outputRoot, staleToken + suffix).writeArtifact(staleMs)
            }
            val fresh = File(outputRoot, "$freshToken.input-rgb-nchw.f32").writeArtifact(freshMs)
            val boundary = File(outputRoot, "$boundaryToken.png.part").writeArtifact(boundaryMs)
            val wrongPrefix = File(
                outputRoot,
                "mnn-diffusion-1721430000000-123e4567-e89b-12d3-a456-426614174000.png"
            ).writeArtifact(staleMs)
            val wrongSuffix = File(outputRoot, "$staleToken.input-rgb-nchw.f32.backup")
                .writeArtifact(staleMs)
            val malformedUuid = File(
                outputRoot,
                "qnn-htp-1721430000000-not-a-uuid.input-rgb-nchw.f32"
            ).writeArtifact(staleMs)
            val matchingDirectory = File(outputRoot, "$directoryToken.encoder-latent.json").apply {
                mkdirs()
                setLastModified(staleMs)
            }
            val nested = File(outputRoot, "nested").apply { mkdirs() }
            val nestedArtifact = File(nested, "$staleToken.png").writeArtifact(staleMs)
            val siblingRoot = Files.createTempDirectory(
                requireNotNull(cacheRoot.parentFile).toPath(),
                "shared-qnn-artifacts-sibling"
            ).toFile()
            val siblingArtifact = File(siblingRoot, "$staleToken.input-rgb-nchw.f32")
                .writeArtifact(staleMs)

            try {
                assertEquals(
                    exactSuffixes.size,
                    QnnInputImageArtifact.cleanupStaleSharedArtifacts(cacheRoot, nowMs)
                )
                staleFiles.forEach { file -> assertFalse(file.exists()) }
                listOf(
                    fresh,
                    boundary,
                    wrongPrefix,
                    wrongSuffix,
                    malformedUuid,
                    matchingDirectory,
                    nestedArtifact,
                    siblingArtifact
                ).forEach { file -> assertTrue(file.exists()) }
            } finally {
                siblingRoot.deleteRecursively()
            }
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `worker initialization wires bounded shared qnn stale cleanup`() {
        val source = locateProjectFile(
            "app/src/main/java/com/muyuchat/mca/LocalImageWorkerService.kt",
            "src/main/java/com/muyuchat/mca/LocalImageWorkerService.kt"
        ).readText(Charsets.UTF_8)
        val onCreateStart = source.indexOf("override fun onCreate()")
        val onBindStart = source.indexOf("override fun onBind", startIndex = onCreateStart)
        val onCreate = source.substring(onCreateStart, onBindStart)

        assertTrue(
            onCreate.contains("QnnInputImageArtifact.cleanupStaleSharedArtifacts(cacheDir)")
        )
        assertTrue(onCreate.contains("cleanup_stale_shared_qnn_artifacts"))
    }

    @Test
    fun `qnn artifact accepts bounded topology aligned canvases`() {
        listOf(
            512 to 512,
            512 to 1024,
            1024 to 512,
            1024 to 1024,
            1536 to 1024,
            2048 to 512,
            2048 to 2048
        ).forEach { (width, height) ->
            QnnInputImageArtifact.requireSupportedDimensions(width, height)
        }

        listOf(
            0 to 0,
            511 to 511,
            513 to 512,
            512 to 513,
            2_112 to 512,
            512 to 2_112
        ).forEach { (width, height) ->
            assertTrue(
                runCatching {
                    QnnInputImageArtifact.requireSupportedDimensions(width, height)
                }.isFailure
            )
        }
    }

    @Test
    fun `legacy sdxl artifact wrapper remains fixed at 1024 square`() {
        SdxlInputImageArtifact.requireSupportedDimensions(1024, 1024)

        listOf(512 to 512, 512 to 1024, 1024 to 512).forEach { (width, height) ->
            assertTrue(
                runCatching {
                    SdxlInputImageArtifact.requireSupportedDimensions(width, height)
                }.isFailure
            )
        }
    }

    @Test
    fun `generic tensor metadata preserves legacy native json contract at both sizes`() {
        assertEquals(QNN_INPUT_TENSOR_PREPROCESS, SDXL_INPUT_TENSOR_PREPROCESS)
        assertEquals(QNN_INPUT_TENSOR_DTYPE, SDXL_INPUT_TENSOR_DTYPE)
        assertEquals(QNN_INPUT_TENSOR_LAYOUT, SDXL_INPUT_TENSOR_LAYOUT)
        assertEquals(QNN_INPUT_TENSOR_RANGE, SDXL_INPUT_TENSOR_RANGE)

        listOf(512, 1024).forEach { size ->
            val expectedTensorBytes = 1L * 3L * size * size * Float.SIZE_BYTES
            val generic: QnnPreparedInputTensor = SdxlPreparedInputTensor(
                sourcePath = "/cache/source-$size.png",
                sourceSha256 = "a".repeat(64),
                sourceBytes = 1234L,
                sourceWidth = 1200,
                sourceHeight = 900,
                exifOrientation = 1,
                orientedWidth = 1200,
                orientedHeight = 900,
                tensorPath = "/cache/input-$size.f32",
                tensorSha256 = "b".repeat(64),
                tensorBytes = expectedTensorBytes,
                tensorWidth = size,
                tensorHeight = size
            )
            val legacy: SdxlPreparedInputTensor = generic
            val params = legacy.putNativeParams(JSONObject())

            assertEquals(listOf(1, 3, size, size), generic.tensorShape)
            assertEquals(generic, legacy)
            assertEquals(generic.sourcePath, params.getString("inputImagePath"))
            assertEquals(generic.sourceSha256, params.getString("inputImageSha256"))
            assertEquals(generic.sourceBytes, params.getLong("inputImageSizeBytes"))
            assertEquals(generic.sourceWidth, params.getInt("inputImageSourceWidth"))
            assertEquals(generic.sourceHeight, params.getInt("inputImageSourceHeight"))
            assertEquals(generic.exifOrientation, params.getInt("inputImageExifOrientation"))
            assertEquals(generic.orientedWidth, params.getInt("inputImageOrientedWidth"))
            assertEquals(generic.orientedHeight, params.getInt("inputImageOrientedHeight"))
            assertEquals(generic.tensorPath, params.getString("inputImageTensorPath"))
            assertEquals(generic.tensorSha256, params.getString("inputImageTensorSha256"))
            assertEquals(expectedTensorBytes, params.getLong("inputImageTensorBytes"))
            assertEquals(
                listOf(1, 3, size, size),
                params.getJSONArray("inputImageTensorShape").let { shape ->
                    List(shape.length()) { index -> shape.getInt(index) }
                }
            )
            assertEquals("float32-le", params.getString("inputImageTensorDtype"))
            assertEquals("NCHW", params.getString("inputImageTensorLayout"))
            assertEquals("NEGATIVE_ONE_TO_ONE", params.getString("inputImageTensorRange"))
            assertEquals(
                "exif_orient_center_crop_bilinear_rgb_nchw_negative_one_to_one_v1",
                params.getString("inputImagePreprocess")
            )
        }
    }

    @Test
    fun `qnn img2img tail schedule matches Local Dream float32 semantics`() {
        data class Case(
            val steps: Int,
            val strength: Double,
            val beginIndex: Int,
            val effectiveSteps: Int
        )
        listOf(
            Case(1, 0.0, 0, 1),
            Case(1, 1.0, 0, 1),
            Case(4, 0.0, 3, 1),
            Case(30, 0.05, 28, 2),
            Case(30, 1.0, 0, 30),
            Case(20, 0.5, 10, 10),
            Case(4, 0.75, 1, 3),
            Case(30, 0.65, 10, 20),
            Case(28, 0.65, 9, 19),
            Case(20, 0.6, 7, 13),
            Case(30, 0.6, 11, 19)
        ).forEach { expected ->
            val actual = resolveQnnImg2ImgSchedule(
                steps = expected.steps,
                fullTimetableCount = expected.steps,
                strength = expected.strength
            )
            assertEquals(expected.beginIndex, actual.beginIndex)
            assertEquals(expected.effectiveSteps, actual.effectiveSteps)
            assertEquals(expected.steps, actual.beginIndex + actual.effectiveSteps)
        }
    }

    @Test
    fun `qnn img2img tail schedule rejects invalid strength and malformed timetable`() {
        listOf(
            -0.01,
            1.01,
            Double.NaN,
            Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY
        ).forEach { strength ->
            assertTrue(
                runCatching {
                    resolveQnnImg2ImgSchedule(
                        steps = 20,
                        fullTimetableCount = 20,
                        strength = strength
                    )
                }.isFailure
            )
        }

        listOf(
            0 to 0,
            -1 to -1,
            20 to 0,
            20 to 19,
            20 to 21
        ).forEach { (steps, fullTimetableCount) ->
            assertTrue(
                runCatching {
                    resolveQnnImg2ImgSchedule(
                        steps = steps,
                        fullTimetableCount = fullTimetableCount,
                        strength = 0.5
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `legacy sdxl schedule wrapper is exactly equivalent to generic qnn schedule`() {
        listOf(1, 4, 20, 28, 30).forEach { steps ->
            listOf(0.0, Double.MIN_VALUE, 0.05, 0.5, 0.6, 0.65, 1.0).forEach { strength ->
                val generic = resolveQnnImg2ImgSchedule(steps, steps, strength)
                val legacy: SdxlImg2ImgSchedule = resolveSdxlImg2ImgSchedule(
                    steps = steps,
                    fullTimetableCount = steps,
                    strength = strength
                )
                val legacyConstructed = SdxlImg2ImgSchedule(
                    strength = legacy.strength,
                    effectiveSteps = legacy.effectiveSteps,
                    beginIndex = legacy.beginIndex,
                    fullTimetableCount = legacy.fullTimetableCount
                )

                assertEquals(generic, legacy)
                assertEquals(legacy, legacyConstructed)
            }
        }
    }

    @Test
    fun `img2img phase progress maps physical tail steps onto full timetable`() {
        assertEquals(
            11,
            sdxlPhaseProgressStep(
                phase = SdxlImagePhase.UNET,
                taskMode = LocalImageTaskMode.IMG2IMG,
                fullSteps = 30,
                beginIndex = 11,
                effectiveSteps = 19,
                physicalStep = 0
            )
        )
        assertEquals(
            30,
            sdxlPhaseProgressStep(
                phase = SdxlImagePhase.UNET,
                taskMode = LocalImageTaskMode.IMG2IMG,
                fullSteps = 30,
                beginIndex = 11,
                effectiveSteps = 19,
                physicalStep = 19
            )
        )
        assertEquals(
            3,
            sdxlPhaseProgressStep(
                phase = SdxlImagePhase.UNET,
                taskMode = LocalImageTaskMode.IMG2IMG,
                fullSteps = 4,
                beginIndex = 3,
                effectiveSteps = 1,
                physicalStep = 0
            )
        )
        assertEquals(
            20,
            sdxlPhaseProgressStep(
                phase = SdxlImagePhase.VAE,
                taskMode = LocalImageTaskMode.IMG2IMG,
                fullSteps = 20,
                beginIndex = 0,
                effectiveSteps = 20,
                physicalStep = 0
            )
        )
    }

    private fun File.writeArtifact(lastModifiedMs: Long): File = apply {
        parentFile?.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
        assertTrue("Unable to set test artifact timestamp for $path", setLastModified(lastModifiedMs))
    }

    private fun locateProjectFile(vararg relativePaths: String): File {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            relativePaths.forEach { relativePath ->
                File(root, relativePath).takeIf(File::isFile)?.let { return it }
            }
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate ${relativePaths.first()}.")
    }
}

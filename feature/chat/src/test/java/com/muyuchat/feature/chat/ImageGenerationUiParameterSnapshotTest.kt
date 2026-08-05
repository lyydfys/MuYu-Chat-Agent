package com.muyuchat.feature.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationUiParameterSnapshotTest {
    @Test
    fun `ultrafix UI derives the native Float denoising tail at adjacent boundaries`() {
        assertEquals(4, imageGenerationUltraFixDenoisingTailStepCount(10, 0.4))
        assertEquals(
            4,
            imageGenerationUltraFixDenoisingTailStepCount(10, 0.4000000000000001)
        )
    }

    @Test
    fun `img2img snapshot removes pndm while text to image preserves it`() {
        val model = ChatModelChoice(
            id = "shared-qnn",
            displayName = "Shared QNN",
            imageDefaultSampler = "pndm",
            imageSupportedSamplers = listOf("dpmpp_2m", "euler", "pndm"),
            imageImg2ImgSupportedSamplers = listOf("dpmpp_2m", "euler")
        )
        val base = ImageGenerationUiParameterSnapshot(
            taskModeName = ImageGenerationUiTaskMode.IMG2IMG.name,
            strengthText = "0.6",
            controlStrengthText = "1.0",
            negativePrompt = "",
            disableModelNegativePrompt = false,
            clipSkipText = "",
            vaeTilingEnabled = false,
            batchCount = 1,
            widthText = "512",
            heightText = "512",
            stepsText = "20",
            cfgScaleText = "7",
            seedText = "",
            sampler = "pndm"
        )

        assertEquals(
            "dpmpp_2m",
            base.normalizedForImageModel(model).sampler
        )
        assertEquals(
            "pndm",
            base.copy(taskModeName = ImageGenerationUiTaskMode.TEXT_TO_IMAGE.name)
                .normalizedForImageModel(model)
                .sampler
        )
    }

    @Test
    fun `same model profile refresh clamps removed sampler to supported default`() {
        assertEquals(
            "dpmpp_2m",
            normalizedImageSamplerForCapabilities(
                current = "lcm",
                supported = listOf("dpmpp_2m", "euler"),
                defaultSampler = "dpmpp_2m"
            )
        )
        assertEquals(
            "euler",
            normalizedImageSamplerForCapabilities(
                current = "lcm",
                supported = listOf("euler"),
                defaultSampler = "removed_default"
            )
        )
    }

    @Test
    fun `step text normalization follows refreshed profile bounds`() {
        assertEquals("28", normalizedImageGenerationStepsText("not-a-number", 28, 10, 50))
        assertEquals("10", normalizedImageGenerationStepsText("0", 28, 10, 50))
        assertEquals("36", normalizedImageGenerationStepsText("36", 28, 10, 50))
        assertEquals("50", normalizedImageGenerationStepsText("999999999999", 28, 10, 50))
    }

    @Test
    fun `versions one through three keep the legacy live preview behavior`() {
        for (version in 1..3) {
            val json = JSONObject()
                .put("version", version)
                .put("taskModeName", ImageGenerationUiTaskMode.TEXT_TO_IMAGE.name)
                .put("strengthText", "0.75")
                .put("controlStrengthText", "1.0")
                .put("negativePrompt", "")
                .put("disableModelNegativePrompt", false)
                .put("clipSkipText", "")
                .put("vaeTilingEnabled", false)
                .put("batchCount", 1)
                .put("widthText", "512")
                .put("heightText", "512")
                .put("stepsText", "20")
                .put("cfgScaleText", "7")
                .put("seedText", "")
                .put("sampler", "euler")
            if (version >= 3) json.put("loras", JSONArray())

            val snapshot = requireNotNull(
                ImageGenerationUiParameterSnapshot.fromJsonOrNull(json.toString())
            )

            assertNull(snapshot.inputImageUri)
            assertNull(snapshot.maskImageUri)
            assertNull(snapshot.controlImageUri)
            assertEquals(emptyList<ImageGenerationUiLoraDraft>(), snapshot.loras)
            assertTrue(snapshot.livePreviewEnabled)
            assertEquals(1, snapshot.livePreviewInterval)
            assertNull(snapshot.livePreviewMode)
            assertFalse(snapshot.livePreviewIntervalExplicit)
            assertEquals(version, snapshot.sourceVersion)
        }
    }

    @Test
    fun `current version round trip preserves advanced image controls`() {
        val expected = ImageGenerationUiParameterSnapshot(
            taskModeName = ImageGenerationUiTaskMode.INPAINT.name,
            strengthText = "0.65",
            controlStrengthText = "1.0",
            negativePrompt = "",
            disableModelNegativePrompt = true,
            clipSkipText = "2",
            vaeTilingEnabled = true,
            batchCount = 2,
            widthText = "768",
            heightText = "512",
            stepsText = "24",
            cfgScaleText = "6.5",
            seedText = "42",
            sampler = "dpmpp_2m",
            loras = listOf(
                ImageGenerationUiLoraDraft(
                    id = "11111111-1111-4111-8111-111111111111",
                    multiplierText = "0.75"
                )
            ),
            inputImageUri = "content://documents/input",
            maskImageUri = "content://documents/mask",
            controlImageUri = "content://documents/control",
            livePreviewMode = ImageGenerationUiPreviewMode.VAE,
            livePreviewEnabled = false,
            livePreviewInterval = 7,
            livePreviewIntervalExplicit = true,
            textualInversionIds = listOf("11111111-1111-4111-8111-111111111111"),
            ultraFixEnabled = true,
            ultraFixStrengthText = "0.4",
            ultraFixInversionStepsText = "4",
            ultraFixRefinementStepsText = "10",
            ultraFixTileSizeText = "512",
            ultraFixTileSizeExplicit = true,
            ultraFixOverlapText = "0.25"
        )

        val actual = ImageGenerationUiParameterSnapshot.fromJsonOrNull(
            expected.toJson().toString()
        )

        assertEquals(expected, actual)
        assertEquals(9, expected.toJson().getInt("version"))
        assertEquals("vae", expected.toJson().getString("livePreviewMode"))
        assertTrue(expected.toJson().getBoolean("ultraFixTileSizeExplicit"))
    }

    @Test
    fun `legacy implicit stable SDXL tile migrates to 1024 with executable dimensions`() {
        val migrated = ultraFixSnapshot(
            tileSize = "512",
            explicit = false,
            sourceVersion = 6,
        ).normalizedForImageModel(ultraFixModel(defaultSize = 1024))

        assertEquals("1024", migrated.ultraFixTileSizeText)
        assertFalse(migrated.ultraFixTileSizeExplicit)
        assertEquals("1024", migrated.widthText)
        assertEquals("1024", migrated.heightText)
    }

    @Test
    fun `current explicit stable SDXL tile 512 is preserved`() {
        val normalized = ultraFixSnapshot(
            tileSize = "512",
            explicit = true,
            sourceVersion = 7,
        ).normalizedForImageModel(ultraFixModel(defaultSize = 1024))

        assertEquals("512", normalized.ultraFixTileSizeText)
        assertTrue(normalized.ultraFixTileSizeExplicit)
        assertEquals("1024", normalized.widthText)
        assertEquals("1024", normalized.heightText)
        assertEquals("512", normalized.ultraFixTargetWidthText)
        assertEquals("512", normalized.ultraFixTargetHeightText)
    }

    @Test
    fun `fixed QNN tile overrides persisted selection and never becomes explicit`() {
        val normalized = ultraFixSnapshot(
            tileSize = "1024",
            explicit = true,
            sourceVersion = 7,
        ).normalizedForImageModel(
            ultraFixModel(defaultSize = 1024, requiredTileSize = 512)
        )

        assertEquals("512", normalized.ultraFixTileSizeText)
        assertFalse(normalized.ultraFixTileSizeExplicit)
    }

    @Test
    fun `version six non-default tile is inferred as an explicit user selection`() {
        val normalized = ultraFixSnapshot(
            tileSize = "768",
            explicit = false,
            sourceVersion = 6,
        ).normalizedForImageModel(ultraFixModel(defaultSize = 1024))

        assertEquals("768", normalized.ultraFixTileSizeText)
        assertTrue(normalized.ultraFixTileSizeExplicit)
        assertEquals("1024", normalized.widthText)
        assertEquals("1024", normalized.heightText)
        assertEquals("768", normalized.ultraFixTargetWidthText)
        assertEquals("768", normalized.ultraFixTargetHeightText)
    }

    @Test
    fun `implicit tile follows capabilities across stable and QNN model switches`() {
        val stable = ultraFixModel(defaultSize = 1024)
        val qnn = ultraFixModel(defaultSize = 1024, requiredTileSize = 512)

        val firstStable = normalizedImageGenerationUltraFixTileSelection(
            model = stable,
            rawValue = "512",
            explicit = false,
            sourceVersion = 6,
        )
        val fixedQnn = normalizedImageGenerationUltraFixTileSelection(
            model = qnn,
            rawValue = firstStable.tileSize.toString(),
            explicit = firstStable.explicit,
            sourceVersion = 7,
        )
        val secondStable = normalizedImageGenerationUltraFixTileSelection(
            model = stable,
            rawValue = fixedQnn.tileSize.toString(),
            explicit = fixedQnn.explicit,
            sourceVersion = 7,
        )

        assertEquals(ImageGenerationUiUltraFixTileSelection(1024, false), firstStable)
        assertEquals(ImageGenerationUiUltraFixTileSelection(512, false), fixedQnn)
        assertEquals(ImageGenerationUiUltraFixTileSelection(1024, false), secondStable)
    }

    @Test
    fun `flexible default respects native scale and rejects invalid explicit tiles`() {
        val stableSd1 = ultraFixModel(defaultSize = 512)
        val unalignedDefault = ultraFixModel(defaultSize = 1000)

        assertEquals(512, stableSd1.resolvedImageUltraFixDefaultTileSize())
        assertEquals(960, unalignedDefault.resolvedImageUltraFixDefaultTileSize())
        assertEquals(
            ImageGenerationUiUltraFixTileSelection(1024, false),
            normalizedImageGenerationUltraFixTileSelection(
                model = ultraFixModel(defaultSize = 1024),
                rawValue = "770",
                explicit = true,
                sourceVersion = 7,
            )
        )
        assertEquals(
            ImageGenerationUiUltraFixTileSelection(1024, false),
            normalizedImageGenerationUltraFixTileSelection(
                model = ultraFixModel(defaultSize = 1024),
                rawValue = "4096",
                explicit = true,
                sourceVersion = 7,
            )
        )
    }

    @Test
    fun `version four vae default one migrates to model default while projection history is retained`() {
        val legacyDefault = ImageGenerationUiParameterSnapshot(
            taskModeName = ImageGenerationUiTaskMode.TEXT_TO_IMAGE.name,
            strengthText = "0.75",
            controlStrengthText = "1.0",
            negativePrompt = "",
            disableModelNegativePrompt = false,
            clipSkipText = "",
            vaeTilingEnabled = false,
            batchCount = 1,
            widthText = "512",
            heightText = "512",
            stepsText = "20",
            cfgScaleText = "7",
            seedText = "",
            sampler = "euler",
            livePreviewEnabled = true,
            livePreviewInterval = 1
        ).toJson().apply {
            put("version", 4)
            remove("livePreviewMode")
            remove("livePreviewIntervalExplicit")
        }
        val legacySnapshot = requireNotNull(
            ImageGenerationUiParameterSnapshot.fromJsonOrNull(legacyDefault.toString())
        )
        val vaeModel = ChatModelChoice(
            id = "qnn-vae",
            displayName = "QNN VAE",
            imagePreviewMode = ImageGenerationUiPreviewMode.VAE,
            imageDefaultPreviewInterval = 4,
            supportsImageLivePreview = true
        )

        val migratedVae = legacySnapshot.normalizedForImageModel(vaeModel)

        assertEquals(ImageGenerationUiPreviewMode.VAE, migratedVae.livePreviewMode)
        assertEquals(4, migratedVae.livePreviewInterval)
        assertFalse(migratedVae.livePreviewIntervalExplicit)
        assertEquals(
            5,
            legacySnapshot.normalizedForImageModel(
                vaeModel.copy(imageDefaultPreviewInterval = 5)
            ).livePreviewInterval
        )
        val migratedLegacyVaeSelection = legacySnapshot.copy(
            livePreviewInterval = 7,
            sourceVersion = 4
        ).normalizedForImageModel(vaeModel)
        assertEquals(7, migratedLegacyVaeSelection.livePreviewInterval)
        assertTrue(migratedLegacyVaeSelection.livePreviewIntervalExplicit)

        val projectionHistory = legacySnapshot.copy(
            livePreviewInterval = 7,
            sourceVersion = 4
        ).normalizedForImageModel(
            ChatModelChoice(
                id = "stable-projection",
                displayName = "Stable projection",
                imagePreviewMode = ImageGenerationUiPreviewMode.PROJECTION,
                imageDefaultPreviewInterval = 1,
                supportsImageLivePreview = true
            )
        )
        assertEquals(ImageGenerationUiPreviewMode.PROJECTION, projectionHistory.livePreviewMode)
        assertEquals(7, projectionHistory.livePreviewInterval)
        assertTrue(projectionHistory.livePreviewIntervalExplicit)
        assertEquals(
            1,
            legacySnapshot.normalizedForImageModel(
                ChatModelChoice(
                    id = "stable-projection-default",
                    displayName = "Stable projection default",
                    imagePreviewMode = ImageGenerationUiPreviewMode.PROJECTION,
                    imageDefaultPreviewInterval = 5,
                    supportsImageLivePreview = true
                )
            ).livePreviewInterval
        )
    }

    @Test
    fun `explicit interval one survives vae normalization and current restore`() {
        val model = ChatModelChoice(
            id = "qnn-vae-default-five",
            displayName = "QNN VAE default five",
            imagePreviewMode = ImageGenerationUiPreviewMode.VAE,
            imageDefaultPreviewInterval = 5,
            supportsImageLivePreview = true
        )
        val explicitOne = ImageGenerationUiParameterSnapshot(
            taskModeName = ImageGenerationUiTaskMode.TEXT_TO_IMAGE.name,
            strengthText = "0.75",
            controlStrengthText = "1.0",
            negativePrompt = "",
            disableModelNegativePrompt = false,
            clipSkipText = "",
            vaeTilingEnabled = false,
            batchCount = 1,
            widthText = "512",
            heightText = "512",
            stepsText = "20",
            cfgScaleText = "7",
            seedText = "",
            sampler = "euler",
            livePreviewMode = ImageGenerationUiPreviewMode.VAE,
            livePreviewInterval = 1,
            livePreviewIntervalExplicit = true
        )

        val restored = requireNotNull(
            ImageGenerationUiParameterSnapshot.fromJsonOrNull(explicitOne.toJson().toString())
        ).normalizedForImageModel(model)

        assertEquals(1, restored.livePreviewInterval)
        assertTrue(restored.livePreviewIntervalExplicit)
        assertEquals(ImageGenerationUiPreviewMode.VAE, restored.livePreviewMode)
    }

    @Test
    fun `preview request carries stable mode wire name together with interval`() {
        val vaeModel = ChatModelChoice(
            id = "qnn-shared",
            displayName = "QNN shared",
            imagePreviewMode = ImageGenerationUiPreviewMode.VAE,
            imageDefaultPreviewInterval = 4,
            supportsImageLivePreview = true
        )

        val request = requireNotNull(
            imageGenerationUiPreviewRequestOrNull(vaeModel, enabled = true, interval = 6)
        )
        val options = ImageGenerationUiOptions(
            previewMode = request.mode,
            previewInterval = request.interval
        )

        assertEquals(ImageGenerationUiPreviewMode.VAE, options.previewMode)
        assertEquals("vae", options.previewMode?.wireName)
        assertEquals(6, options.previewInterval)
        assertEquals(
            ImageGenerationUiPreviewMode.PROJECTION,
            ImageGenerationUiPreviewMode.fromWireNameOrNull("projection")
        )
        assertNull(ImageGenerationUiPreviewMode.fromWireNameOrNull("unknown"))
        assertNull(
            imageGenerationUiPreviewRequestOrNull(vaeModel, enabled = false, interval = 1)
        )
        assertTrue(vaeModel.supportsImageLivePreview)
        assertTrue(
            runCatching {
                ChatModelChoice(
                    id = "missing-mode",
                    displayName = "Missing mode",
                    supportsImageLivePreview = true
                )
            }.isFailure
        )
        assertTrue(
            shouldShowImageGenerationVaePreviewCostWarning(
                mode = ImageGenerationUiPreviewMode.VAE,
                enabled = true,
                interval = 1
            )
        )
        assertFalse(
            shouldShowImageGenerationVaePreviewCostWarning(
                mode = ImageGenerationUiPreviewMode.PROJECTION,
                enabled = true,
                interval = 1
            )
        )
        assertFalse(
            shouldShowImageGenerationVaePreviewCostWarning(
                mode = ImageGenerationUiPreviewMode.VAE,
                enabled = false,
                interval = 1
            )
        )
        assertFalse(
            shouldShowImageGenerationVaePreviewCostWarning(
                mode = ImageGenerationUiPreviewMode.VAE,
                enabled = true,
                interval = 2
            )
        )
    }

    @Test
    fun `grant references span every current model and role while orphan snapshots are pruned`() {
        val shared = "content://documents/shared"
        val modelA = ImageGenerationUiParameterSnapshot(
            taskModeName = ImageGenerationUiTaskMode.INPAINT.name,
            strengthText = "0.7",
            controlStrengthText = "1.0",
            negativePrompt = "",
            disableModelNegativePrompt = false,
            clipSkipText = "",
            vaeTilingEnabled = false,
            batchCount = 1,
            widthText = "512",
            heightText = "512",
            stepsText = "20",
            cfgScaleText = "7",
            seedText = "",
            sampler = "euler",
            inputImageUri = shared,
            maskImageUri = "content://documents/mask"
        )
        val modelB = modelA.copy(
            taskModeName = ImageGenerationUiTaskMode.CONTROL.name,
            inputImageUri = null,
            maskImageUri = null,
            controlImageUri = shared
        )
        val stale = modelA.copy(inputImageUri = "content://documents/orphan")
        val preferences = mapOf<String, Any>(
            "model:local-a" to modelA.toJson().toString(),
            "model:cloud-b" to modelB.toJson().toString(),
            "model:deleted" to stale.toJson().toString(),
            "owned:persistable_image_uris" to setOf(shared)
        )

        val references = generationImageSnapshotReferences(
            preferences,
            setOf("local-a", "cloud-b")
        )

        assertEquals(setOf(shared, "content://documents/mask"), references)
        assertEquals(
            setOf(shared),
            generationImageSnapshotReferences(preferences, setOf("cloud-b"))
        )
        assertEquals(
            setOf("model:deleted"),
            obsoleteGenerationImageSnapshotKeys(
                preferences,
                setOf("local-a", "cloud-b")
            )
        )
        assertFalse("owned:persistable_image_uris" in obsoleteGenerationImageSnapshotKeys(
            preferences,
            setOf("local-a", "cloud-b")
        ))
        assertEquals(
            setOf(shared, "content://documents/history-mask"),
            normalizedGenerationImageHistoryReferences(
                listOf(
                    shared,
                    " content://documents/history-mask ",
                    "file:///data/user/0/private.png",
                    "https://example.invalid/image.png"
                )
            )
        )
        assertEquals(
            setOf(
                shared,
                "content://documents/history-mask",
                "content://documents/transient",
                "content://documents/pending"
            ),
            combinedGenerationImageGrantReferences(
                snapshotReferencedUris = setOf(shared),
                historyReferencedUris = setOf("content://documents/history-mask"),
                transientReferencedUris = setOf("content://documents/transient"),
                pendingUris = setOf("content://documents/pending")
            )
        )
    }

    @Test
    fun `grant reconciliation releases only unreferenced owned grants and defers during work`() {
        val shared = "content://documents/shared"
        val orphan = "content://documents/orphan"
        val invalid = "content://documents/invalid"
        val external = "content://documents/model-import"
        val normal = planGenerationImageGrantReconciliation(
            ownedUris = setOf(shared, orphan, invalid),
            persistedReadUris = setOf(shared, orphan, external),
            referencedUris = setOf(shared),
            deferRelease = false
        )

        assertEquals(setOf(shared), normal.retainedOwnedUris)
        assertEquals(setOf(orphan), normal.releaseOwnedUris)
        assertEquals(setOf(invalid), normal.forgetOwnedUris)
        assertFalse(external in normal.releaseOwnedUris)

        val deferred = planGenerationImageGrantReconciliation(
            ownedUris = setOf(shared, orphan, invalid),
            persistedReadUris = setOf(shared, orphan, external),
            referencedUris = emptySet(),
            deferRelease = true
        )
        assertEquals(setOf(shared, orphan), deferred.retainedOwnedUris)
        assertTrue(deferred.releaseOwnedUris.isEmpty())
        assertEquals(setOf(invalid), deferred.forgetOwnedUris)
    }

    @Test
    fun `pending grant epochs close take to snapshot races without retaining abandoned uris`() {
        val selected = "content://documents/selected"
        val otherInFlight = "content://documents/other"

        val staleReconciliation = pendingGenerationImageGrantUrisAfterReconciliation(
            pendingUriEpochs = mapOf(selected to 2L, otherInFlight to 3L),
            eligibleForPruneUriEpochs = mapOf(selected to 1L),
            snapshotReferencedUris = emptySet(),
            transientReferencedUris = emptySet()
        )
        assertEquals(
            mapOf(selected to 2L, otherInFlight to 3L),
            staleReconciliation
        )

        val afterSnapshotCommit = pendingGenerationImageGrantUrisAfterReconciliation(
            pendingUriEpochs = staleReconciliation,
            eligibleForPruneUriEpochs = staleReconciliation,
            snapshotReferencedUris = setOf(selected),
            transientReferencedUris = setOf(selected, otherInFlight),
            committedReferencedUris = setOf(selected)
        )
        assertEquals(mapOf(otherInFlight to 3L), afterSnapshotCommit)

        assertTrue(
            pendingGenerationImageGrantUrisAfterReconciliation(
                pendingUriEpochs = mapOf(selected to 4L),
                eligibleForPruneUriEpochs = mapOf(selected to 4L),
                snapshotReferencedUris = setOf(selected),
                transientReferencedUris = emptySet()
            ).isEmpty()
        )

        val afterFastClear = pendingGenerationImageGrantUrisAfterReconciliation(
            pendingUriEpochs = afterSnapshotCommit,
            eligibleForPruneUriEpochs = afterSnapshotCommit,
            snapshotReferencedUris = emptySet(),
            transientReferencedUris = emptySet()
        )
        assertTrue(afterFastClear.isEmpty())
    }

    @Test
    fun `upscale target choices are exact and dimensions fail closed on overflow`() {
        assertEquals(listOf(2, 3, 4), IMAGE_UPSCALE_TARGET_SCALES)
        assertEquals(1536 to 1152, upscaleOutputDimensionsOrNull(512, 384, 3))
        assertEquals(4_000 to 4_000, upscaleOutputDimensionsOrNull(2_000, 2_000, 2))
        assertEquals(4_000 to 4_000, upscaleOutputDimensionsOrNull(2_000, 2_000, 2))
        assertNull(upscaleOutputDimensionsOrNull(512, 512, 5))
        assertNull(upscaleOutputDimensionsOrNull(2_000, 2_001, 2))
        assertNull(upscaleOutputDimensionsOrNull(2_048, 2_048, 4))
        assertNull(upscaleOutputDimensionsOrNull(Int.MAX_VALUE, 512, 4))
    }

    @Test
    fun `persisted dimensions and steps normalize to current model bounds`() {
        val snapshot = ImageGenerationUiParameterSnapshot(
            taskModeName = ImageGenerationUiTaskMode.TEXT_TO_IMAGE.name,
            strengthText = "0.75",
            controlStrengthText = "1.0",
            negativePrompt = "",
            disableModelNegativePrompt = false,
            clipSkipText = "",
            vaeTilingEnabled = false,
            batchCount = 1,
            widthText = "1001",
            heightText = "513",
            stepsText = "80",
            cfgScaleText = "7",
            seedText = "",
            sampler = "euler"
        )
        val model = ChatModelChoice(
            id = "local-fixed-height",
            displayName = "Local fixed height",
            imageDefaultWidth = 640,
            imageDefaultHeight = 768,
            imageDefaultSteps = 28,
            imageMinSteps = 10,
            imageMaxSteps = 50,
            imageMinWidth = 512,
            imageMaxWidth = 1024,
            imageMinHeight = 768,
            imageMaxHeight = 768,
            imageWidthMultiple = 64,
            imageHeightMultiple = 64
        )

        val normalized = snapshot.normalizedForImageModel(model)

        assertEquals("1024", normalized.widthText)
        assertEquals("768", normalized.heightText)
        assertEquals("50", normalized.stepsText)
        assertEquals(
            "640",
            snapshot.copy(widthText = "not-a-size")
                .normalizedForImageModel(model)
                .widthText
        )
        assertEquals(
            "512",
            snapshot.copy(widthText = "1")
                .normalizedForImageModel(model)
                .widthText
        )
        assertEquals(
            "28",
            snapshot.copy(stepsText = "not-a-step")
                .normalizedForImageModel(model)
                .stepsText
        )
        assertEquals(
            "10",
            snapshot.copy(stepsText = "1")
                .normalizedForImageModel(model)
                .stepsText
        )
        assertEquals(
            snapshot,
            snapshot.normalizedForImageModel(model.copy(cloud = true))
        )
    }

    private fun ultraFixModel(
        defaultSize: Int,
        requiredTileSize: Int = 0,
    ): ChatModelChoice = ChatModelChoice(
        id = "ultrafix-$defaultSize-$requiredTileSize",
        displayName = "UltraFix test model",
        supportedImageTaskModes = setOf(
            ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
            ImageGenerationUiTaskMode.IMG2IMG,
        ),
        supportsImageVaeTiling = true,
        supportsImageUltraFix = true,
        imageDefaultWidth = defaultSize,
        imageDefaultHeight = defaultSize,
        imageMinWidth = 512,
        imageMaxWidth = 2048,
        imageMinHeight = 512,
        imageMaxHeight = 2048,
        imageWidthMultiple = 64,
        imageHeightMultiple = 64,
        imageUltraFixMinWidth = 512,
        imageUltraFixMaxWidth = 2048,
        imageUltraFixMinHeight = 512,
        imageUltraFixMaxHeight = 2048,
        imageUltraFixWidthMultiple = 64,
        imageUltraFixHeightMultiple = 64,
        imageUltraFixRequiredTileSize = requiredTileSize,
    )

    private fun ultraFixSnapshot(
        tileSize: String,
        explicit: Boolean,
        sourceVersion: Int,
    ): ImageGenerationUiParameterSnapshot = ImageGenerationUiParameterSnapshot(
        taskModeName = ImageGenerationUiTaskMode.IMG2IMG.name,
        strengthText = "0.6",
        controlStrengthText = "1.0",
        negativePrompt = "",
        disableModelNegativePrompt = false,
        clipSkipText = "",
        vaeTilingEnabled = true,
        batchCount = 1,
        widthText = "512",
        heightText = "512",
        stepsText = "20",
        cfgScaleText = "7",
        seedText = "",
        sampler = "euler",
        ultraFixEnabled = true,
        ultraFixTileSizeText = tileSize,
        ultraFixTileSizeExplicit = explicit,
        sourceVersion = sourceVersion,
    )
}

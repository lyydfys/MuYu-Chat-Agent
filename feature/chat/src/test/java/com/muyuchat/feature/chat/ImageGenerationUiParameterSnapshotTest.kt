package com.muyuchat.feature.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationUiParameterSnapshotTest {
    @Test
    fun `version one remains readable without persisted image inputs`() {
        val raw = JSONObject()
            .put("version", 1)
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
            .toString()

        val snapshot = requireNotNull(ImageGenerationUiParameterSnapshot.fromJsonOrNull(raw))

        assertNull(snapshot.inputImageUri)
        assertNull(snapshot.maskImageUri)
        assertNull(snapshot.controlImageUri)
        assertEquals(emptyList<ImageGenerationUiLoraDraft>(), snapshot.loras)
    }

    @Test
    fun `version three round trip preserves image inputs and LoRA drafts`() {
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
            controlImageUri = "content://documents/control"
        )

        val actual = ImageGenerationUiParameterSnapshot.fromJsonOrNull(
            expected.toJson().toString()
        )

        assertEquals(expected, actual)
        assertEquals(3, expected.toJson().getInt("version"))
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
    fun `persisted dimensions normalize to current model bounds steps and fixed axes`() {
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
            stepsText = "20",
            cfgScaleText = "7",
            seedText = "",
            sampler = "euler"
        )
        val model = ChatModelChoice(
            id = "local-fixed-height",
            displayName = "Local fixed height",
            imageDefaultWidth = 640,
            imageDefaultHeight = 768,
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
            snapshot,
            snapshot.normalizedForImageModel(model.copy(cloud = true))
        )
    }
}

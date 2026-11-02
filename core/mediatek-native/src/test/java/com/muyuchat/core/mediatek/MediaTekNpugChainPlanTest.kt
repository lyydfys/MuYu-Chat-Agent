package com.muyuchat.core.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekNpugChainPlanTest {
    private val tokens = 1024
    private val contextScale = 4.609773532138206e-05

    @Test
    fun fourChunkReferenceChainBindsBlocksSegmentsRopeAndScaleHandoff() {
        val manifest = manifest()
        val routes = (0 until 4).associate { index ->
            val id = "chunk$index"
            id to route(id, chunkInputs(index, index == 3), chunkOutput(index))
        }

        val plan = MediaTekNpugChainPlanner.plan(manifest, "reference-1024-32x32", 6, routes)

        assertEquals(listOf(0, 7, 14, 21), plan.chunks.map { it.blockStart })
        assertEquals(listOf(68, 68, 68, 70), plan.chunks.map { it.inputCount })
        assertEquals(listOf(false, false, false, true), plan.chunks.map { it.finalChunk })
        assertEquals(listOf(2.0634209795389324e-4, 2.7253208827460185e-5,
            3.173520672135055e-4, 3.3345913137309253e-4), plan.chunks.map { it.inputScale })
        assertEquals(2.083965518977493e-4, plan.chunks.last().outputScale, 0.0)
        assertEquals(7, plan.sigmas.size)
        assertEquals(1.0, plan.sigmas.first(), 0.0)
        assertEquals(0.0, plan.sigmas.last(), 1e-15)
        assertEquals(1.9019890169147402e-4, plan.vaeInputScale, 0.0)

        val slots = plan.chunks.first().slots
        assertEquals(68, slots.size)
        assertEquals(MediaTekNpugSlotSpec.PatchTokens, slots[0])
        assertEquals(MediaTekNpugSlotSpec.FixedContext, slots[1])
        assertEquals(MediaTekNpugSlotSpec.ZeroContext, slots[2])
        assertEquals(MediaTekNpugSlotSpec.Modulation(0, 0), slots[3])
        assertEquals(MediaTekNpugSlotSpec.Modulation(6, 8), slots[65])
        assertEquals(MediaTekNpugSlotSpec.RopeHalf(0), slots[66])
        assertEquals(MediaTekNpugSlotSpec.RopeHalf(1), slots[67])

        val lastSlots = plan.chunks.last().slots
        assertEquals(MediaTekNpugSlotSpec.HiddenState, lastSlots[0])
        assertEquals(MediaTekNpugSlotSpec.Modulation(27, 8), lastSlots[65])
        assertEquals(MediaTekNpugSlotSpec.FinalModulation(0), lastSlots[66])
        assertEquals(MediaTekNpugSlotSpec.FinalModulation(1), lastSlots[67])
        assertEquals(MediaTekNpugSlotSpec.RopeHalf(0), lastSlots[68])
        assertEquals(MediaTekNpugSlotSpec.RopeHalf(1), lastSlots[69])

        val step3 = plan.chunks.last().slotsForStep(3)
        assertEquals(MediaTekNpugSlot.Modulation(3, 21, 0), step3[3])
        assertEquals(MediaTekNpugSlot.FinalModulation(3, 1), step3[67])
    }

    @Test
    fun patchTokensKeepTheSeventeenthZeroChannelInTheReferenceLayout() {
        val manifest = manifest()
        val routes = (0 until 4).associate { index ->
            "chunk$index" to route("chunk$index", chunkInputs(index, index == 3), chunkOutput(index))
        }
        val plan = MediaTekNpugChainPlanner.plan(manifest, "reference-1024-32x32", 6, routes)

        val latent = FloatArray(16 * 64 * 64) { index -> index.toFloat() }
        val tokens = plan.patchTokens(latent)

        assertEquals(1024 * 68, tokens.size)
        assertEquals(0f, tokens[64], 0f)
        assertEquals(0f, tokens[65], 0f)
        assertEquals(0f, tokens[66], 0f)
        assertEquals(0f, tokens[67], 0f)
        assertEquals(latent[0], tokens[0], 0f)
        assertEquals(latent[1], tokens[1], 0f)
        assertEquals(latent[64], tokens[2], 0f)
        assertEquals(latent[65], tokens[3], 0f)
    }

    @Test
    fun vaeInputAppliesVaeDenormalizationThenRequantizes() {
        val manifest = manifest()
        val routes = (0 until 4).associate { index ->
            "chunk$index" to route("chunk$index", chunkInputs(index, index == 3), chunkOutput(index))
        }
        val plan = MediaTekNpugChainPlanner.plan(manifest, "reference-1024-32x32", 6, routes)

        val latent = FloatArray(16 * 64 * 64)
        latent[0] = 3.804e-4f
        latent[1 * 64 * 64] = -3.804e-4f
        val bytes = plan.vaeInput(latent)

        assertEquals(65536 * 2, bytes.size)
        assertEquals(-3975, int16(bytes, 0))
        assertEquals(-3730, int16(bytes, 1))
        assertEquals(-1536, int16(bytes, 15))
    }

    @Test
    fun eulerUpdateUsesSigmaDifferenceAndRejectsNonFiniteResults() {
        val updated = MediaTekNpugEuler.update(
            floatArrayOf(1f, -1f),
            floatArrayOf(2f, 4f),
            sigma = 0.6,
            sigmaNext = 0.3,
        )
        assertEquals(0.4f, updated[0], 1e-6f)
        assertEquals(-2.2f, updated[1], 1e-6f)

        try {
            MediaTekNpugEuler.update(floatArrayOf(0f), floatArrayOf(Float.NaN), 1.0, 0.0)
            throw AssertionError("expected a non-finite flow to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("non-finite"))
        }
    }

    @Test
    fun rejectsChainsThatDoNotMatchTheRouteShapeContract() {
        val manifest = manifest()
        val broken = (0 until 4).associate { index ->
            "chunk$index" to route(
                "chunk$index",
                chunkInputs(index, index == 3, featureOverride = if (index == 1) 64 else null),
                chunkOutput(index),
            )
        }
        assertRejected("first input must be") {
            MediaTekNpugChainPlanner.plan(manifest, "reference-1024-32x32", 6, broken)
        }

        val wrongScale = (0 until 4).associate { index ->
            "chunk$index" to route(
                "chunk$index",
                chunkInputs(index, index == 3, inputScaleOverride = if (index == 2) 1e-3 else null),
                chunkOutput(index),
            )
        }
        assertRejected("input scale") {
            MediaTekNpugChainPlanner.plan(manifest, "reference-1024-32x32", 6, wrongScale)
        }

        val goodRoutes = (0 until 4).associate { index ->
            "chunk$index" to route("chunk$index", chunkInputs(index, index == 3), chunkOutput(index))
        }
        assertRejected("not supported") {
            MediaTekNpugChainPlanner.plan(manifest, "reference-1024-32x32", 4, goodRoutes)
        }
        assertRejected("no parsed route") {
            MediaTekNpugChainPlanner.plan(manifest, "reference-1024-32x32", 6, goodRoutes - "chunk0")
        }
    }

    @Test
    fun rejectsChunksThatDisagreeOnSharedContextZeroOrRopeShapes() {
        val manifest = manifest()
        val badContext = (0 until 4).associate { index ->
            "chunk$index" to route(
                "chunk$index",
                chunkInputs(index, index == 3, contextDims = if (index == 2) listOf(1, 128, 1024) else null),
                chunkOutput(index),
            )
        }
        assertRejected("context input shape") {
            MediaTekNpugChainPlanner.plan(manifest, "reference-1024-32x32", 6, badContext)
        }

        val badZero = (0 until 4).associate { index ->
            "chunk$index" to route(
                "chunk$index",
                chunkInputs(index, index == 3, zeroDims = if (index == 1) listOf(1, 1, 128) else null),
                chunkOutput(index),
            )
        }
        assertRejected("zero-context input shape") {
            MediaTekNpugChainPlanner.plan(manifest, "reference-1024-32x32", 6, badZero)
        }

        val badRope = (0 until 4).associate { index ->
            "chunk$index" to route(
                "chunk$index",
                chunkInputs(index, index == 3, ropeDims = if (index == 3) listOf(1024, 64) else null),
                chunkOutput(index),
            )
        }
        assertRejected("RoPE half shape") {
            MediaTekNpugChainPlanner.plan(manifest, "reference-1024-32x32", 6, badRope)
        }
    }

    @Test
    fun rejectsChunkCountsThatCannotSplitTheTwentyEightBlocks() {
        val manifest = manifest()
        val three = manifest.copy(
            variants = manifest.variants.take(3).mapIndexed { index, variant ->
                variant.copy(
                    id = "chunk$index",
                    compiledNetwork = variant.compiledNetwork?.copy(graphId = "chunk$index"),
                    route = variant.route.copy(graphId = "chunk$index"),
                )
            },
        )
        assertRejected("cannot split 28 blocks") {
            MediaTekNpugChainPlanner.plan(
                three,
                "reference-1024-32x32",
                6,
                (0 until 3).associate { index ->
                    "chunk$index" to route("chunk$index", chunkInputs(index, index == 2), chunkOutput(index))
                },
            )
        }
    }

    private fun manifest(): MediaTekAotManifest {
        val base = MediaTekAotManifestJson.parse(resource("/npug/reference-aot-manifest.json"))
        val template = base.variants.single()
        return base.copy(
            variants = (0 until 4).map { index ->
                template.copy(
                    id = "chunk$index",
                    compiledNetwork = template.compiledNetwork?.copy(graphId = "chunk$index"),
                    route = template.route.copy(graphId = "chunk$index"),
                )
            },
        )
    }

    private fun chunkInputs(
        index: Int,
        last: Boolean,
        featureOverride: Int? = null,
        inputScaleOverride: Double? = null,
        contextDims: List<Int>? = null,
        zeroDims: List<Int>? = null,
        ropeDims: List<Int>? = null,
    ): List<MediaTekNpugRouteTensor> {
        val features = featureOverride ?: if (index == 0) 68 else 2048
        val ownScale = inputScaleOverride ?: CHUNK_INPUT_SCALES[index]
        val inputs = mutableListOf(
            tensor(0, listOf(1, tokens, features), ownScale),
            tensor(1, contextDims ?: listOf(1, 256, 1024), contextScale),
            tensor(2, zeroDims ?: listOf(1, 1, 256), 4.1208789852698047e-11),
        )
        repeat(7 * 9) { inputs += tensor(inputs.size, listOf(1, 1, 2048), 1e-4) }
        if (last) repeat(2) { inputs += tensor(inputs.size, listOf(1, 1, 2048), 1e-4) }
        val ropeShape = ropeDims ?: listOf(1024, 128)
        inputs += tensor(inputs.size, ropeShape, 4.120879020774737e-05)
        inputs += tensor(inputs.size, ropeShape, 4.120864105061628e-05)
        return inputs
    }

    private fun chunkOutput(index: Int): List<MediaTekNpugRouteTensor> {
        val last = index == 3
        return if (last) {
            listOf(
                tensor(
                    routeIndex = 0,
                    dims = listOf(1024, 64),
                    scale = 2.083965518977493e-4,
                    tensorId = 900,
                ),
            )
        } else {
            listOf(
                tensor(
                    routeIndex = 0,
                    dims = listOf(1, 32, 32, 2048),
                    scale = CHUNK_OUTPUT_SCALES[index],
                    tensorId = 900,
                ),
            )
        }
    }

    private fun route(
        graph: String,
        inputs: List<MediaTekNpugRouteTensor>,
        outputs: List<MediaTekNpugRouteTensor>,
    ) = MediaTekNpugRoute(
        graph = graph,
        stub = MediaTekNpugRouteRef("aot/stub/$graph.npug", 1, "a".repeat(64)),
        dla = MediaTekNpugRouteRef("aot/mt6993/np9/$graph.dla", 1, "b".repeat(64)),
        tensorCount = 1,
        operationCount = 1,
        inputs = inputs,
        outputs = outputs,
        inputShapeGroups = emptyList(),
    )

    private fun tensor(routeIndex: Int, dims: List<Int>, scale: Double, tensorId: Int = routeIndex) =
        MediaTekNpugRouteTensor(
            routeIndex = routeIndex,
            tensorId = tensorId,
            rank = dims.size,
            dims = dims,
            quantTag = 1,
            scale = scale,
            zeroPoint = 0,
            dataOffset = 0,
            dataSize = 0,
        )

    private fun int16(bytes: ByteArray, index: Int): Int =
        (bytes[index * 2].toInt() and 0xFF) or (bytes[index * 2 + 1].toInt() shl 8)

    private fun resource(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: throw AssertionError("missing test resource $path")

    private fun assertRejected(expected: String, action: () -> Unit) {
        try {
            action()
        } catch (error: IllegalArgumentException) {
            assertTrue("expected '${error.message}' to contain '$expected'", error.message.orEmpty().contains(expected))
            return
        }
        throw AssertionError("expected an IllegalArgumentException containing '$expected'")
    }

    private companion object {
        val CHUNK_INPUT_SCALES = listOf(
            2.0634209795389324e-4,
            2.7253208827460185e-5,
            3.173520672135055e-4,
            3.3345913137309253e-4,
        )
        val CHUNK_OUTPUT_SCALES = listOf(
            2.7253208827460185e-5,
            3.173520672135055e-4,
            3.3345913137309253e-4,
        )
    }
}

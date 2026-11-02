package com.muyuchat.core.mediatek

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekNpugExecutorTest {
    @Test
    fun executesFourChunksAndVaeWithRouteSlotsAndClosesEverySession() {
        val fixture = Fixture()
        val fake = FakeSession()
        val chunks = mutableListOf<MediaTekNpugChunkRun>()
        val progress = mutableListOf<Int>()

        val result = MediaTekNpugExecutor { fake }.execute(
            fixture.request(
                onChunk = { chunks += it },
                onProgress = { completed, _ -> progress += completed },
            ),
        )

        assertEquals(4, result.chunkRuns.size)
        assertEquals(listOf("chunk0", "chunk1", "chunk2", "chunk3"), result.chunkRuns.map { it.variantId })
        assertEquals(listOf(1), progress)
        assertEquals(512, result.width)
        assertEquals(512, result.height)
        assertEquals(1, result.steps)
        assertTrue(result.pngBytes.size > 8)
        assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), result.pngBytes.copyOf(8))
        assertEquals(5, fake.openCount)
        assertEquals(5, fake.closeCount)

        val first = fake.calls.first()
        val plan = fixture.plan
        val route = fixture.routes.getValue("chunk0")
        assertEquals(68, first.inputs.size)
        assertArrayEquals(
            MediaTekNpugVaeCodec.quantizeInt16LittleEndian(plan.patchTokens(fixture.latent), plan.chunks[0].inputScale, 0),
            first.inputs[0],
        )
        assertArrayEquals(fixture.context, first.inputs[1])
        assertArrayEquals(ByteArray(512), first.inputs[2])
        assertArrayEquals(fixture.modulation.segmentBytes(0, 0, 0), first.inputs[3])
        assertArrayEquals(fixture.rope[0], first.inputs[66])
        assertArrayEquals(fixture.rope[1], first.inputs[67])
        assertEquals(route.inputs.size, first.inputs.size)
        assertTrue(first.inputs.all { it.isNotEmpty() })
        assertEquals(4, chunks.size)
    }

    @Test
    fun cancellationClosesTheOpenSessionAndReturnsNoImage() {
        val fixture = Fixture()
        val fake = FakeSession()
        var cancelled = false

        try {
            MediaTekNpugExecutor { fake }.execute(
                fixture.request(
                    isCancelled = { cancelled || fake.openCount >= 1 },
                ),
            )
            throw AssertionError("expected a cancellation")
        } catch (expected: MediaTekNpugCancelledException) {
            assertTrue(expected.message.orEmpty().contains("cancelled"))
        }

        cancelled = true
        assertEquals(fake.openCount, fake.closeCount)
        assertEquals(1, fake.openCount)
    }

    @Test
    fun rejectsAConstantChunkOutputInsteadOfPassingItToTheNextChunk() {
        val fixture = Fixture()
        try {
            MediaTekNpugExecutor { ConstantSession() }.execute(fixture.request())
            throw AssertionError("expected a constant hidden state to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("constant output"))
        }
    }

    @Test
    fun rejectsAConstantVaeImageInsteadOfPublishingIt() {
        val fixture = Fixture()
        try {
            MediaTekNpugExecutor { ZeroVaeSession() }.execute(fixture.request())
            throw AssertionError("expected a constant image to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("constant image"))
        }
    }

    @Test
    fun rejectsALatentThatDoesNotMatchTheProfile() {
        val fixture = Fixture()
        try {
            MediaTekNpugExecutor { FakeSession() }.execute(
                fixture.request(latent = FloatArray(3)),
            )
            throw AssertionError("expected a latent shape rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("latent is 3 floats"))
        }
    }

    @Test
    fun usesAnExternallyEncodedContextWhenProvided() {
        val fixture = Fixture()
        val fake = FakeSession()
        val context = FloatArray(256 * 1024) { index -> ((index % 89) - 44) / 1000f }

        val result = MediaTekNpugExecutor { fake }.execute(fixture.request(contextValues = context))

        assertTrue(result.pngBytes.size > 8)
        assertArrayEquals(
            MediaTekNpugVaeCodec.quantizeInt16LittleEndian(
                context,
                fixture.routes.getValue("chunk0").inputs[1].scale,
                0,
            ),
            fake.calls.first().inputs[1],
        )
    }

    @Test
    fun rejectsAnExternallyEncodedContextWithTheWrongShape() {
        val fixture = Fixture()
        try {
            MediaTekNpugExecutor { FakeSession() }.execute(
                fixture.request(contextValues = FloatArray(7)),
            )
            throw AssertionError("expected a context shape rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("ACTX context has 7 values"))
        }
    }

    private class ConstantSession : MediaTekNpugGraphSession {
        override fun open(
            file: File,
            inputBytes: LongArray,
            outputCount: Int,
            inputRanks: IntArray,
        ): MediaTekNpugGraphSession.Network = MediaTekNpugGraphSession.Network(inputBytes.copyOf())

        override fun run(inputs: List<ByteArray>, outputBytes: Int): List<ByteArray> =
            listOf(ByteArray(outputBytes) { 7 })

        override fun close() = Unit
    }

    private class ZeroVaeSession : MediaTekNpugGraphSession {
        override fun open(
            file: File,
            inputBytes: LongArray,
            outputCount: Int,
            inputRanks: IntArray,
        ): MediaTekNpugGraphSession.Network = MediaTekNpugGraphSession.Network(inputBytes.copyOf())

        override fun run(inputs: List<ByteArray>, outputBytes: Int): List<ByteArray> =
            if (outputBytes == 512 * 512 * 3 * 2) {
                listOf(ByteArray(outputBytes))
            } else {
                listOf(ByteArray(outputBytes) { index -> ((index % 97) - 48).toByte() })
            }

        override fun close() = Unit
    }

    private class FakeSession : MediaTekNpugGraphSession {
        data class Call(val file: File, val inputs: List<ByteArray>)

        val calls = ArrayList<Call>()
        var openCount = 0
        var closeCount = 0

        override fun open(
            file: File,
            inputBytes: LongArray,
            outputCount: Int,
            inputRanks: IntArray,
        ): MediaTekNpugGraphSession.Network {
            openCount++
            require(inputRanks.size == inputBytes.size)
            return MediaTekNpugGraphSession.Network(inputBytes.copyOf())
        }

        override fun run(inputs: List<ByteArray>, outputBytes: Int): List<ByteArray> {
            calls += Call(File("session-${openCount}"), inputs)
            val output = ByteArray(outputBytes)
            for (index in output.indices) {
                output[index] = ((index % 97) - 48).toByte()
            }
            return listOf(output)
        }

        override fun close() {
            closeCount++
        }
    }

    private class Fixture {
        val root: File = kotlin.io.path.createTempDirectory("mca-npug-executor").toFile()
        val base = MediaTekAotManifestJson.parse(resource("/npug/reference-aot-manifest.json"))
        val profile = base.profiles.single().copy(
            supportedSteps = setOf(1),
            defaultSteps = 1,
            modulations = listOf(
                base.profiles.single().modulations.single().copy(
                    stepCount = 1,
                    asset = MediaTekBundleAssetContract("assets/npug/mods.bin", "a".repeat(64), 1, "application/octet-stream"),
                ),
            ),
        )
        val manifest = base.copy(
            variants = (0 until 4).map { index ->
                base.variants.single().copy(
                    id = "chunk$index",
                    compiledNetwork = base.variants.single().compiledNetwork?.copy(graphId = "chunk$index"),
                    route = base.variants.single().route.copy(graphId = "chunk$index"),
                )
            },
            profiles = listOf(profile),
        )
        val routes = (0 until 4).associate { index -> "chunk$index" to route(index) }
        val latent = FloatArray(16 * 64 * 64) { index -> ((index % 17) - 8) / 8f }
        val contextRaw = actxValues()
        val context = MediaTekNpugVaeCodec.quantizeInt16LittleEndian(contextRaw, 4.609773532138206e-05, 0)
        val rope: List<ByteArray>
        val modulation: MediaTekNpugModulationFile

        init {
            for (index in 0 until 4) {
                File(root, "chunk$index.dla").writeBytes(ByteArray(8) { index.toByte() })
            }
            File(root, File(profile.vae.asset.file).name).writeBytes(ByteArray(8) { 9 })
            File(root, File(requireNotNull(manifest.conditioning.contextAsset).file).name).writeBytes(actx())
            val ropeBytes = ByteArray(1024 * 128 * 4)
            for (index in ropeBytes.indices) ropeBytes[index] = (index % 31).toByte()
            File(root, File(profile.ropeAsset.file).name).writeBytes(ropeBytes)
            rope = listOf(ropeBytes.copyOfRange(0, ropeBytes.size / 2), ropeBytes.copyOfRange(ropeBytes.size / 2, ropeBytes.size))
            val amod = amod(steps = 1)
            File(root, "mods.bin").writeBytes(amod)
            modulation = MediaTekNpugModulationFile.parse(amod)
        }

        val plan: MediaTekNpugChainPlan = MediaTekNpugChainPlanner.plan(manifest, profile.id, 1, routes)

        fun request(
            latent: FloatArray = this.latent,
            contextValues: FloatArray? = null,
            isCancelled: () -> Boolean = { false },
            onChunk: (MediaTekNpugChunkRun) -> Unit = {},
            onProgress: (Int, Int) -> Unit = { _, _ -> },
        ) = MediaTekNpugExecutionRequest(
            plan = plan,
            manifest = manifest,
            routes = routes,
            resolveAsset = { asset -> File(root, File(asset.file).name) },
            contextValues = contextValues,
            latent = latent,
            isCancelled = isCancelled,
            onProgress = onProgress,
            onChunk = onChunk,
        )

        private fun route(index: Int): MediaTekNpugRoute {
            val last = index == 3
            val inputs = mutableListOf(
                tensor(0, listOf(1, 1024, if (index == 0) 68 else 2048), CHUNK_INPUT_SCALES[index]),
                tensor(1, listOf(1, 256, 1024), 4.609773532138206e-05),
                tensor(2, listOf(1, 1, 256), 4.1208789852698047e-11),
            )
            repeat(7 * 9) { inputs += tensor(inputs.size, listOf(1, 1, 2048), 1e-4) }
            if (last) repeat(2) { inputs += tensor(inputs.size, listOf(1, 1, 2048), 1e-4) }
            inputs += tensor(inputs.size, listOf(1024, 128), 4.120879020774737e-05)
            inputs += tensor(inputs.size, listOf(1024, 128), 4.120864105061628e-05)
            val output = if (last) {
                tensor(0, listOf(1024, 64), 2.083965518977493e-4, 900)
            } else {
                tensor(0, listOf(1, 32, 32, 2048), CHUNK_OUTPUT_SCALES[index], 900)
            }
            return MediaTekNpugRoute(
                graph = "chunk$index",
                stub = MediaTekNpugRouteRef("aot/stub/chunk$index.npug", 1, "a".repeat(64)),
                dla = MediaTekNpugRouteRef("aot/chunk$index.dla", 1, "b".repeat(64)),
                tensorCount = 1,
                operationCount = 1,
                inputs = inputs,
                outputs = listOf(output),
                inputShapeGroups = emptyList(),
            )
        }

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

        private fun actxValues(): FloatArray {
            val values = FloatArray(256 * 1024) { index -> ((index % 13) - 6) / 13f }
            return values
        }

        private fun actx(): ByteArray {
            val buffer = ByteBuffer.allocate(16 + 256 * 1024 * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("ACTX".toByteArray())
            buffer.putInt(1)
            buffer.putInt(256)
            buffer.putInt(1024)
            contextRaw.forEach { buffer.putFloat(it) }
            return buffer.array()
        }

        private fun amod(steps: Int): ByteArray {
            val blocks = 28
            val segments = blocks * 9 + 2
            val bytes = ByteArray(20 + steps * 2048 * segments * 2)
            bytes[0] = 'A'.code.toByte()
            bytes[1] = 'M'.code.toByte()
            bytes[2] = 'O'.code.toByte()
            bytes[3] = 'D'.code.toByte()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(4, 1)
                putInt(8, steps)
                putInt(12, 2048)
                putInt(16, blocks)
            }
            for (index in 0 until steps * 2048 * segments) {
                val value = (index % 101) - 50
                val offset = 20 + index * 2
                bytes[offset] = (value and 0xFF).toByte()
                bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            }
            return bytes
        }

        companion object {
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

            fun resource(path: String): String =
                MediaTekNpugExecutorTest::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
                    ?: throw AssertionError("missing test resource $path")
        }
    }
}

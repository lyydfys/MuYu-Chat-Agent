package com.muyuchat.core.mediatek

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Host dry run of the real four-chunk kit built by tools/mediatek/build_chain_kit.py.
 * A fake runtime produces non-constant, input-dependent buffers; everything before the
 * NPU call (manifest, routes, ACTX, RoPE, AMOD, slot assembly, Euler update, VAE
 * handoff) is the real code. This proves the sampling loop propagates state and slices
 * AMOD per step, which slot-size checks alone cannot.
 */
class MediaTekNpugKitDryRunTest {
    @Test
    fun realKitRunsSixStepsAndPropagatesStateAcrossChunksAndSteps() {
        val kit = Kit.require()
        val first = Kit.execution(kit, FakeSession())
        val runs = first.result.chunkRuns
        val calls = first.session.calls

        assertEquals(kit.plan.steps * kit.plan.chunks.size, runs.size)
        assertEquals(kit.plan.steps * kit.plan.chunks.size + 1, first.session.openCount)
        assertEquals(first.session.openCount, first.session.closeCount)
        assertEquals(kit.plan.steps, runs.map { it.step }.distinct().size)
        assertEquals(4, runs.map { it.variantId }.distinct().size)
        assertEquals(512, first.result.width)
        assertEquals(512, first.result.height)
        assertTrue(first.result.pngBytes.size > 1024)
        assertArrayEquals(
            byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10),
            first.result.pngBytes.copyOf(8),
        )
        assertTrue(runs.all { it.bytes > 0 })
        assertTrue(runs.all { it.sha256.length == 64 })
        assertEquals(runs.size + 1, calls.size)
        assertEquals(16 * 64 * 64, first.result.finalLatent.size)
        assertTrue(first.result.finalLatent.all(Float::isFinite))
        assertFalse(
            "the final latent must move away from the initial noise",
            first.result.finalLatent.contentEquals(kit.latent),
        )

        val chunksPerStep = kit.plan.chunks.size
        val step0Chunk0 = calls[0].inputs
        val step1Chunk0 = calls[chunksPerStep].inputs
        assertEquals(1024 * 68 * 2, step0Chunk0[0].size)
        assertEquals(512, step0Chunk0[2].size)
        assertEquals(1024 * 128 * 2, step0Chunk0[66].size)
        assertFalse(
            "patch tokens must change after the Euler update",
            step0Chunk0[0].contentEquals(step1Chunk0[0]),
        )
        assertFalse(
            "AMOD block segments must advance with the step",
            step0Chunk0[3].contentEquals(step1Chunk0[3]),
        )
        assertFalse(
            "hidden state must advance through the chain",
            calls[1].inputs[0].contentEquals(calls[chunksPerStep + 1].inputs[0]),
        )

        val again = Kit.execution(kit, FakeSession())
        assertArrayEquals(
            "the same latent and fake runtime must produce the same PNG",
            first.result.pngBytes,
            again.result.pngBytes,
        )
        assertEquals(
            "the same latent must produce the same chunk digests",
            runs.map { it.sha256 },
            again.result.chunkRuns.map { it.sha256 },
        )
        assertArrayEquals(
            "the same latent must produce the same final latent",
            first.result.finalLatent,
            again.result.finalLatent,
            0f,
        )

        val changedLatent = kit.latent.copyOf().also { it[0] += 0.5f }
        val changed = Kit.execution(kit, FakeSession(), changedLatent)
        assertFalse(
            "a different latent must change the final image",
            first.result.pngBytes.contentEquals(changed.result.pngBytes),
        )
        assertFalse(
            "a different latent must change the final latent",
            first.result.finalLatent.contentEquals(changed.result.finalLatent),
        )
    }

    @Test(timeout = 900_000)
    fun dynamicTextKitEncodesThePromptAndFeedsTheChain() {
        val kit = Kit.requireDynamic()
        val weightsFile = File(kit.assets, "text.bin")
        val tokenizerFile = File(kit.assets, "tok.bin")
        assumeTrue("reference text.bin is missing", weightsFile.isFile)
        assumeTrue("reference text.bin is incomplete", weightsFile.length() == 1_461_438_080L)
        assumeTrue("reference tok.bin is missing", tokenizerFile.isFile)
        val lk = requireNotNull(kit.textLk) { "dynamic kit has no textLk" }

        val encoding = MediaTekAotTextWeights.open(weightsFile).use { weights ->
            MediaTekAotTextEncoder(weights, MediaTekAotTokenizer.parse(tokenizerFile)).encode("a cat", lk)
        }
        assertEquals(256 * 1024, encoding.context.size)

        val execution = Kit.execution(kit, FakeSession(), contextValues = encoding.context)
        assertEquals(kit.plan.steps * kit.plan.chunks.size, execution.result.chunkRuns.size)
        assertArrayEquals(
            "the encoded ACTX must be quantized with the route's context scale",
            MediaTekNpugVaeCodec.quantizeInt16LittleEndian(
                encoding.context,
                kit.routes.getValue("chunk0").inputs[1].scale,
                0,
            ),
            execution.session.calls.first().inputs[1],
        )
        assertArrayEquals(
            byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10),
            execution.result.pngBytes.copyOf(8),
        )
    }

    private class Kit(
        val dir: File,
        val assets: File,
        val manifest: MediaTekAotManifest,
        val routes: Map<String, MediaTekNpugRoute>,
        val plan: MediaTekNpugChainPlan,
        val latent: FloatArray,
        val textLk: Int? = null,
    ) {
        data class Execution(
            val result: MediaTekNpugExecutionResult,
            val session: FakeSession,
        )

        companion object {
            fun require(): Kit {
                val dir = File("../../build/chain-kit")
                val assets = File("E:/mtk-npu-reference-assets")
                assumeTrue("chain kit is not present; run tools/mediatek/build_chain_kit.py", dir.isDirectory)
                assumeTrue("reference assets are not present", assets.isDirectory)
                val config = Json.parseToJsonElement(File(dir, "chain-config.json").readText()).jsonObject
                fun text(name: String): String = config.getValue(name).jsonPrimitive.content
                val manifest = MediaTekAotManifestJson.parse(File(dir, text("manifest")).readText())
                val routes = LinkedHashMap<String, MediaTekNpugRoute>()
                for (entry in config.getValue("chunks") as JsonArray) {
                    val fields = entry.jsonObject
                    routes[fields.getValue("variantId").jsonPrimitive.content] =
                        MediaTekNpugRouteJson.parse(
                            File(dir, fields.getValue("routeFile").jsonPrimitive.content).readText(),
                        )
                }
                val plan = MediaTekNpugChainPlanner.plan(
                    manifest,
                    text("profileId"),
                    config.getValue("plannedSteps").jsonPrimitive.int,
                    routes,
                )
                val latent = ByteBuffer.wrap(File(dir, text("latentFile")).readBytes())
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                    .let { buffer -> FloatArray(buffer.remaining()).also { buffer.get(it) } }
                return Kit(dir, assets, manifest, routes, plan, latent)
            }

            fun requireDynamic(): Kit {
                val dir = File("../../build/chain-kit-dynamic")
                val assets = File("E:/mtk-npu-reference-assets")
                assumeTrue("dynamic kit is not present; run tools/mediatek/build_chain_kit.py --dynamic-text", dir.isDirectory)
                assumeTrue("reference assets are not present", assets.isDirectory)
                val config = Json.parseToJsonElement(File(dir, "chain-config.json").readText()).jsonObject
                fun text(name: String): String = config.getValue(name).jsonPrimitive.content
                val manifest = MediaTekAotManifestJson.parse(File(dir, text("manifest")).readText())
                val routes = LinkedHashMap<String, MediaTekNpugRoute>()
                for (entry in config.getValue("chunks") as JsonArray) {
                    val fields = entry.jsonObject
                    routes[fields.getValue("variantId").jsonPrimitive.content] =
                        MediaTekNpugRouteJson.parse(
                            File(dir, fields.getValue("routeFile").jsonPrimitive.content).readText(),
                        )
                }
                val plan = MediaTekNpugChainPlanner.plan(
                    manifest,
                    text("profileId"),
                    config.getValue("plannedSteps").jsonPrimitive.int,
                    routes,
                )
                val latent = ByteBuffer.wrap(File(dir, text("latentFile")).readBytes())
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                    .let { buffer -> FloatArray(buffer.remaining()).also { buffer.get(it) } }
                return Kit(dir, assets, manifest, routes, plan, latent, textLk = config.getValue("textLk").jsonPrimitive.int)
            }

            fun execution(
                kit: Kit,
                session: FakeSession,
                latent: FloatArray = kit.latent,
                contextValues: FloatArray? = null,
            ): Execution {
                val result = MediaTekNpugExecutor { session }.execute(
                    MediaTekNpugExecutionRequest(
                        plan = kit.plan,
                        manifest = kit.manifest,
                        routes = kit.routes,
                        resolveAsset = { asset ->
                            val kitFile = File(kit.dir, File(asset.file).name)
                            if (kitFile.isFile) kitFile else File(kit.assets, File(asset.file).name)
                        },
                        contextValues = contextValues,
                        latent = latent,
                        isCancelled = { false },
                    ),
                )
                return Execution(result, session)
            }
        }
    }

    private class FakeSession : MediaTekNpugGraphSession {
        data class Call(val openIndex: Int, val inputs: List<ByteArray>)

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
            calls += Call(openCount, inputs)
            var hash = 2166136261u
            for (input in inputs) {
                for (index in input.indices step 4099) {
                    hash = (hash xor (input[index].toInt() and 0xFF).toUInt()) * 16777619u
                }
            }
            hash = hash xor (openCount.toUInt() * 2654435761u)
            val output = ByteArray(outputBytes)
            var state = hash.toInt()
            for (index in output.indices step 2) {
                state = state * 1103515245 + 12345
                output[index] = (state ushr 16).toByte()
                output[index + 1] = (state ushr 24).toByte()
            }
            return listOf(output)
        }

        override fun close() {
            closeCount++
        }
    }
}

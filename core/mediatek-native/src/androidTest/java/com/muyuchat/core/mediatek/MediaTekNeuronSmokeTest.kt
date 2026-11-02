package com.muyuchat.core.mediatek

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke for one ditc1c Neuron compiled-network chunk.
 *
 * The kit (input-pack.bin, hidden-int16.bin, smoke-config.json and the DLA)
 * is pushed by tools/mediatek/run_neuron_smoke.py. The test never fabricates a
 * MediaTek result: it writes a report and skips restore on non-MediaTek SoCs.
 */
@RunWith(AndroidJUnit4::class)
class MediaTekNeuronSmokeTest {
    @Test
    fun probeOnly() {
        val dir = smokeDir()
        val report = baseReport(dir)
        val adapter = MediaTekNeuronRuntimeAdapter()
        try {
            val runtime = adapter.probe().getOrThrow()
            report.put(
                "runtime",
                JSONObject()
                    .put("status", runtime.status)
                    .put("libraryPath", runtime.libraryPath)
                    .put("version", runtime.version)
                    .put("devices", JSONArray(runtime.devices))
                    .put("message", runtime.message),
            )
            report.put("status", if (runtime.status == "READY") "COMPLETED" else "BLOCKED")
            writeReport(dir, report)
            if (isMediaTek()) {
                assertEquals("Neuron runtime must be READY on a MediaTek device", "READY", runtime.status)
            } else {
                assumeTrue("MediaTek device required for a meaningful probe", false)
            }
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("error", error.toString())
            writeReport(dir, report)
            throw error
        } finally {
            adapter.close()
        }
    }

    @Test
    fun restoreBindRunClose() {
        assumeTrue("MediaTek device required for restore/run", isMediaTek())
        val dir = smokeDir()
        val report = baseReport(dir)
        val adapter = MediaTekNeuronRuntimeAdapter()
        try {
            val config = JSONObject(File(dir, "smoke-config.json").readText())
            val runtime = adapter.probe().getOrThrow()
            report.put(
                "runtime",
                JSONObject()
                    .put("status", runtime.status)
                    .put("libraryPath", runtime.libraryPath)
                    .put("version", runtime.version)
                    .put("devices", JSONArray(runtime.devices))
                    .put("message", runtime.message),
            )
            assertEquals("Neuron runtime must be READY", "READY", runtime.status)

            val inputCount = config.getInt("inputCount")
            val outputCount = config.getInt("outputCount")
            val outputBytes = config.getInt("outputBytes")
            val ranks = config.getJSONArray("inputRanks").toIntArray()
            val inputBytes = config.getJSONArray("inputBytes").toLongArray()
            val hidden = File(dir, config.getString("hiddenFile")).readBytes()
            val pack = File(dir, config.getString("packFile")).readBytes()
            require(hidden.size.toLong() == inputBytes[0]) {
                "hidden is ${hidden.size} bytes; kit declares ${inputBytes[0]}"
            }
            require(pack.size.toLong() == config.getLong("packBytes")) {
                "pack is ${pack.size} bytes; kit declares ${config.getLong("packBytes")}"
            }
            val inputs = ArrayList<ByteArray>(inputCount)
            inputs.add(hidden)
            var offset = 0
            for (index in 1 until inputCount) {
                val size = inputBytes[index].toInt()
                require(offset + size <= pack.size) { "pack is shorter than input $index" }
                inputs.add(pack.copyOfRange(offset, offset + size))
                offset += size
            }
            require(offset == pack.size) { "pack has ${pack.size - offset} unassigned bytes" }

            val dla = File(dir, config.getString("dlaFile"))
            require(dla.isFile && dla.canRead() && dla.length() == config.getLong("dlaBytes")) {
                "DLA is missing or has the wrong size: ${dla.absolutePath}"
            }
            val network = adapter.open(dla, inputCount, outputCount, ranks).getOrThrow()
            report.put(
                "network",
                JSONObject()
                    .put("libraryPath", network.libraryPath)
                    .put("version", network.version)
                    .put("inputCount", network.inputCount)
                    .put("outputCount", network.outputCount)
                    .put("inputPaddedBytes", JSONArray(network.inputPaddedBytes))
                    .put("outputPaddedBytes", JSONArray(network.outputPaddedBytes))
                    .put("restoreElapsedMs", network.restoreElapsedMs),
            )
            val padded = inputs.mapIndexed { index, bytes ->
                val size = network.inputPaddedBytes[index].toInt()
                require(size >= bytes.size) {
                    "runtime padding for input $index is $size, smaller than ${bytes.size}"
                }
                bytes.copyOf(size)
            }
            val first = adapter.run(padded, outputBytes).getOrThrow()
            val second = adapter.run(padded, outputBytes).getOrThrow()
            require(first.size == outputCount) { "expected $outputCount outputs, got ${first.size}" }
            require(first[0].size == outputBytes) {
                "output 0 is ${first[0].size} bytes; expected $outputBytes"
            }
            val firstStats = stats(first[0])
            val secondStats = stats(second[0])
            report.put("outputs", JSONArray().put(firstStats).put(secondStats))
            report.put("deterministic", first[0].contentEquals(second[0]))
            report.put("inputPackSHA256", config.getString("packSHA256"))
            report.put("inputHiddenSHA256", config.getString("hiddenSHA256"))
            report.put("status", "COMPLETED")
            writeReport(dir, report)
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("error", error.toString())
            writeReport(dir, report)
            throw error
        } finally {
            adapter.close()
        }
    }


    @Test
    fun vaeOnly() {
        assumeTrue("MediaTek device required for VAE restore/run", isMediaTek())
        val dir = smokeDir()
        val report = baseReport(dir)
        val adapter = MediaTekNeuronRuntimeAdapter()
        try {
            val runtime = adapter.probe().getOrThrow()
            report.put(
                "runtime",
                JSONObject()
                    .put("status", runtime.status)
                    .put("libraryPath", runtime.libraryPath)
                    .put("version", runtime.version)
                    .put("devices", JSONArray(runtime.devices))
                    .put("message", runtime.message),
            )
            assertEquals("Neuron runtime must be READY", "READY", runtime.status)
            val dla = File(dir, "vaedec_64.dla")
            require(dla.isFile && dla.canRead() && dla.length() == 26_432_870L) {
                "VAE DLA is missing or has the wrong size: ${dla.absolutePath}"
            }
            val network = adapter.open(dla, 1, 1, intArrayOf(4)).getOrThrow()
            report.put(
                "network",
                JSONObject()
                    .put("libraryPath", network.libraryPath)
                    .put("version", network.version)
                    .put("inputCount", network.inputCount)
                    .put("outputCount", network.outputCount)
                    .put("inputPaddedBytes", JSONArray(network.inputPaddedBytes))
                    .put("outputPaddedBytes", JSONArray(network.outputPaddedBytes))
                    .put("restoreElapsedMs", network.restoreElapsedMs),
            )
            val logicalInputBytes = 64 * 64 * 16 * 2
            val paddedInput = ByteArray(network.inputPaddedBytes[0].toInt())
            require(paddedInput.size >= logicalInputBytes) {
                "VAE input padding ${paddedInput.size} is smaller than $logicalInputBytes"
            }
            val outputBytes = 512 * 512 * 3 * 2
            val first = adapter.run(listOf(paddedInput), outputBytes).getOrThrow()
            val second = adapter.run(listOf(paddedInput), outputBytes).getOrThrow()
            require(first.size == 1) { "expected one VAE output, got ${first.size}" }
            require(first[0].size == outputBytes) {
                "VAE output is ${first[0].size} bytes; expected $outputBytes"
            }
            val rgb = MediaTekNpugVaeCodec.dequantizeInt16LittleEndian(
                first[0],
                scale = 4.065645771333948e-5,
                zeroPoint = 0,
            )
            val ncdhw = MediaTekNpugVaeCodec.nhwcToNcdhwDepthOne(rgb, 512, 512, 3)
            report.put(
                "vae",
                JSONObject()
                    .put("inputBytes", logicalInputBytes)
                    .put("paddedInputBytes", paddedInput.size)
                    .put("outputBytes", outputBytes)
                    .put("outputSHA256", sha256(first[0]))
                    .put("deterministic", first[0].contentEquals(second[0]))
                    .put("rgb", floatStats(ncdhw)),
            )
            report.put("status", "COMPLETED")
            writeReport(dir, report)
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("error", error.toString())
            writeReport(dir, report)
            throw error
        } finally {
            adapter.close()
        }
    }
    /**
     * Four-chunk chain plus VAE through the production [MediaTekNpugExecutor].
     * The kit must be built by tools/mediatek/build_chain_kit.py and pushed with
     * every DLA and the AMOD file. This runs the same slot assembly, health
     * gates, Euler update and VAE handoff as the image worker.
     */
    @Test
    fun fullChain() {
        assumeTrue("MediaTek device required for the chain", isMediaTek())
        val dir = smokeDir()
        val report = baseReport(dir).put("mode", "full-chain")
        try {
            val config = JSONObject(File(dir, "chain-config.json").readText())
            val manifest = MediaTekAotManifestJson.parse(File(dir, config.getString("manifest")).readText())
            val chunkConfigs = config.getJSONArray("chunks")
            val routes = HashMap<String, MediaTekNpugRoute>()
            for (index in 0 until chunkConfigs.length()) {
                val entry = chunkConfigs.getJSONObject(index)
                routes[entry.getString("variantId")] =
                    MediaTekNpugRouteJson.parse(File(dir, entry.getString("routeFile")).readText())
            }
            val plan = MediaTekNpugChainPlanner.plan(
                manifest,
                config.getString("profileId"),
                config.getInt("plannedSteps"),
                routes,
            )
            val latent = readFloats(File(dir, config.getString("latentFile")))
            require(latent.size == plan.latentChannels * plan.height * plan.width) {
                "latent is ${latent.size} floats; expected ${plan.latentChannels}x${plan.height}x${plan.width}"
            }
            val textContext = if (manifest.conditioning.mode == MediaTekAotConditioningMode.DYNAMIC_TEXT) {                val weightsFile = File(dir, config.getString("textWeightsFile"))
                val tokenizerFile = File(dir, config.getString("textTokenizerFile"))
                require(weightsFile.isFile) { "text weights are missing: ${weightsFile.absolutePath}" }
                require(tokenizerFile.isFile) { "text tokenizer is missing: ${tokenizerFile.absolutePath}" }
                val textPrompt = config.optString("textPrompt", "a cat")
                val textLk = config.optInt("textLk", routes.values.first().inputs[1].dims[1])
                val encoding = MediaTekAotTextWeights.open(weightsFile).use { store ->
                    MediaTekAotTextEncoder(store, MediaTekAotTokenizer.parse(tokenizerFile))
                        .encode(textPrompt, textLk)
                }
                report.put(
                    "textEncoding",
                    JSONObject()
                        .put("prompt", textPrompt)
                        .put("lk", encoding.lk)
                        .put("qwenTokens", JSONArray(encoding.qwenTokens.toList()))
                        .put("t5Tokens", JSONArray(encoding.t5Tokens.toList()))
                        .put("contextSHA256", encoding.contextSha256)
                        .put("elapsedMs", encoding.elapsedMs)
                        .put("minimum", encoding.minimum.toDouble())
                        .put("maximum", encoding.maximum.toDouble())
                        .put("mean", encoding.mean.toDouble())
                        .put("std", encoding.standardDeviation.toDouble()),
                )
                val actx = ByteBuffer.allocate(16 + encoding.context.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                actx.put("ACTX".toByteArray(Charsets.US_ASCII))
                actx.putInt(1)
                actx.putInt(encoding.lk)
                actx.putInt(1024)
                encoding.context.forEach { actx.putFloat(it) }
                val actxBytes = actx.array()
                File(dir, "dynamic-actx.bin").writeBytes(actxBytes)
                report.getJSONObject("textEncoding")
                    .put("actxFile", "dynamic-actx.bin")
                    .put("actxSHA256", sha256(actxBytes))
                encoding.context
            } else {
                null
            }
            report.put("conditioningMode", manifest.conditioning.mode.name)
            report.put("modelIdentity", manifest.modelIdentity.modelSha256)
            report.put("manifestSHA256", sha256(File(dir, config.getString("manifest")).readBytes()))
            report.put(
                "plan",
                JSONObject()
                    .put("steps", plan.steps)
                    .put("sigmas", JSONArray(plan.sigmas))
                    .put("blocks", JSONArray(plan.chunks.map { it.blockStart }))
                    .put("inputScales", JSONArray(plan.chunks.map { it.inputScale }))
                    .put("latentSHA256", config.getString("latentSHA256")),
            )
            val probe = MediaTekNeuronRuntimeAdapter()
            try {
                val runtime = probe.probe().getOrThrow()
                assertEquals("Neuron runtime must be READY", "READY", runtime.status)
                report.put(
                    "runtime",
                    JSONObject()
                        .put("status", runtime.status)
                        .put("libraryPath", runtime.libraryPath)
                        .put("version", runtime.version)
                        .put("devices", JSONArray(runtime.devices))
                        .put("message", runtime.message),
                )
            } finally {
                probe.close()
            }
            val chunkRuns = JSONArray()
            val progress = JSONArray()
            val result = MediaTekNpugExecutor().execute(
                MediaTekNpugExecutionRequest(
                    plan = plan,
                    manifest = manifest,
                    routes = routes,
                    resolveAsset = { asset -> kitAsset(dir, asset) },
                    contextValues = textContext,
                    latent = latent,
                    isCancelled = { false },
                    onProgress = { completed, total ->
                        progress.put(JSONObject().put("completed", completed).put("total", total))
                    },
                    onChunk = { run ->
                        chunkRuns.put(
                            JSONObject()
                                .put("step", run.step)
                                .put("variantId", run.variantId)
                                .put("bytes", run.bytes)
                                .put("sha256", run.sha256)
                                .put("min", run.minimum)
                                .put("max", run.maximum)
                                .put("mean", run.mean)
                                .put("std", run.standardDeviation),
                        )
                    },
                ),
            )
            report.put("chunks", chunkRuns)
            report.put("progress", progress)
            report.put(
                "output",
                JSONObject()
                    .put("width", result.width)
                    .put("height", result.height)
                    .put("steps", result.steps)
                    .put("pngBytes", result.pngBytes.size)
                    .put("pngSHA256", sha256(result.pngBytes))
                    .put("runtimeMessage", result.runtimeMessage),
            )
            val finalLatentBytes = ByteBuffer.allocate(result.finalLatent.size * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { result.finalLatent.forEach { putFloat(it) } }
                .array()
            File(dir, "final-latent.f32").writeBytes(finalLatentBytes)
            val latentMean = result.finalLatent.average()
            val latentStd = kotlin.math.sqrt(
                result.finalLatent.sumOf { value -> (value - latentMean) * (value - latentMean) } /
                    result.finalLatent.size,
            )
            report.put(
                "baseline",
                JSONObject()
                    .put("schema", "mca.neuron-chain-baseline/1")
                    .put("modelIdentity", manifest.modelIdentity.modelSha256)
                    .put("manifestSHA256", report.getString("manifestSHA256"))
                    .put("profileId", config.getString("profileId"))
                    .put("plannedSteps", config.getInt("plannedSteps"))
                    .put("runSteps", config.getInt("runSteps"))
                    .put("latentSHA256", config.getString("latentSHA256"))
                    .put("conditioningMode", manifest.conditioning.mode.name)
                    .put(
                        "textContextSHA256",
                        report.optJSONObject("textEncoding")?.optString("contextSHA256")?.takeIf { it.isNotBlank() }
                            ?: JSONObject.NULL,
                    )
                    .put("chunkSHA256", JSONArray(result.chunkRuns.map { it.sha256 }))
                    .put("outputSHA256", sha256(result.pngBytes))
                    .put("finalLatentFile", "final-latent.f32")
                    .put("finalLatentElements", result.finalLatent.size)
                    .put("finalLatentSHA256", sha256(finalLatentBytes))
                    .put("finalLatentMin", result.finalLatent.min().toDouble())
                    .put("finalLatentMax", result.finalLatent.max().toDouble())
                    .put("finalLatentMean", latentMean)
                    .put("finalLatentStd", latentStd)
                    .put("qualityStatus", "NOT_VERIFIED_NO_REFERENCE_BASELINE")
                    .put("deviceVerification", "NOT_RUN"),
            )
            report.put("status", "COMPLETED")
            writeReport(dir, report)
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("error", error.toString())
            writeReport(dir, report)
            throw error
        }
    }

    private fun kitAsset(dir: File, asset: MediaTekBundleAssetContract): File {
        val file = File(dir, File(asset.file).name)
        require(file.isFile) { "kit is missing ${asset.file}" }
        require(file.length() == asset.sizeBytes) {
            "${asset.file} is ${file.length()} B; the manifest pins ${asset.sizeBytes} B"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == asset.sha256.lowercase()) {
            "${asset.file} SHA-256 $actual does not match the pinned ${asset.sha256}"
        }
        return file
    }


    private fun readFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size % 4 == 0) { "${file.name} is not a float32 array" }
        val floats = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
    }

    private fun smokeDir(): File {
        val arguments = InstrumentationRegistry.getArguments()
        val explicit = arguments.getString("mcaNeuronSmokeDir")
        if (!explicit.isNullOrBlank()) return File(explicit)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null) ?: context.filesDir, "mca-neuron-smoke")
    }

    private fun baseReport(dir: File): JSONObject = JSONObject()
        .put("schema", "mca.neuron-smoke-report/1")
        .put("utcMillis", System.currentTimeMillis())
        .put("smokeDir", dir.absolutePath)
        .put("manufacturer", android.os.Build.MANUFACTURER)
        .put("model", android.os.Build.MODEL)
        .put("socManufacturer", if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER else "")
        .put("socModel", if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "")
        .put("android", android.os.Build.VERSION.RELEASE)
        .put("sdk", android.os.Build.VERSION.SDK_INT)
        .put("mediatek", isMediaTek())

    private fun isMediaTek(): Boolean {
        val socManufacturer = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER else ""
        val socModel = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else ""
        return android.os.Build.MANUFACTURER.contains("MediaTek", ignoreCase = true) ||
            socManufacturer.contains("MediaTek", ignoreCase = true) ||
            socModel.contains("Dimensity", ignoreCase = true) ||
            socModel.startsWith("MT", ignoreCase = true)
    }

    private fun stats(bytes: ByteArray): JSONObject {
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var minimum = Short.MAX_VALUE.toInt()
        var maximum = Short.MIN_VALUE.toInt()
        var sum = 0.0
        var sumSquares = 0.0
        val count = shorts.remaining()
        while (shorts.hasRemaining()) {
            val value = shorts.get().toInt()
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            sum += value
            sumSquares += value.toDouble() * value
        }
        val mean = if (count > 0) sum / count else 0.0
        val variance = if (count > 0) max(0.0, sumSquares / count - mean * mean) else 0.0
        return JSONObject()
            .put("bytes", bytes.size)
            .put("sha256", sha256(bytes))
            .put("count", count)
            .put("min", minimum)
            .put("max", maximum)
            .put("mean", mean)
            .put("std", sqrt(variance))
    }


    private fun floatStats(values: FloatArray): JSONObject {
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var sumSquares = 0.0
        values.forEach { value ->
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            sum += value
            sumSquares += value.toDouble() * value
        }
        val count = values.size
        val mean = if (count > 0) sum / count else 0.0
        val variance = if (count > 0) max(0.0, sumSquares / count - mean * mean) else 0.0
        return JSONObject()
            .put("count", count)
            .put("min", minimum)
            .put("max", maximum)
            .put("mean", mean)
            .put("std", sqrt(variance))
    }
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private fun writeReport(dir: File, report: JSONObject) {
        dir.mkdirs()
        File(dir, "report.json").writeText(report.toString(2), Charsets.UTF_8)
    }

    private fun JSONArray.toIntArray(): IntArray = IntArray(length()) { index -> getInt(index) }

    private fun JSONArray.toLongArray(): LongArray = LongArray(length()) { index -> getLong(index) }
}

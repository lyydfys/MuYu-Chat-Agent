package com.muyuchat.core.mediatek

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

data class MediaTekAnimaRequest(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfgScale: Float? = null,
    val seed: Long,
)

data class MediaTekAnimaTokenSequence(
    val qwenTokenIds: IntArray,
    val qwenAttentionMask: IntArray,
    val t5TokenIds: IntArray,
    val t5TokenWeights: FloatArray,
    val t5AttentionMask: IntArray,
)

/** Implemented by the model-format tokenizer paired with this Anima text encoder. */
fun interface MediaTekAnimaTokenizer {
    fun tokenize(
        bundleRoot: File,
        textEncoder: MediaTekAnimaTextEncoderContract,
        prompt: String,
        qwenMaxTokens: Int,
        t5MaxTokens: Int,
    ): MediaTekAnimaTokenSequence
}

data class MediaTekAnimaProgress(val stage: String, val completed: Int, val total: Int)

data class MediaTekAnimaPipelineResult(
    val pngBytes: ByteArray,
    val evidence: MediaTekExecutionEvidence,
    val graphRuns: List<MediaTekGraphRunEvidence>,
    val seed: Long,
    val steps: Int,
    val summary: MediaTekRunSummary,
)

/**
 * Executes the complete schema-v3 Anima graph topology through LiteRT's MediaTek NPU provider.
 * Tokenization is an explicit dependency because silently substituting a different tokenizer
 * changes the model semantics. The graph runner itself never falls back to CPU.
 */
class MediaTekAnimaPipeline(
    context: Context,
    private val tokenizer: MediaTekAnimaTokenizer,
) {
    private val appContext = context.applicationContext

    fun generate(
        bundleRoot: File,
        manifest: MediaTekAnimaManifest,
        request: MediaTekAnimaRequest,
        isCancellationRequested: () -> Boolean = { false },
        onProgress: (MediaTekAnimaProgress) -> Unit = {},
        onDiagnostic: ((MediaTekAnimaDiagnosticSample) -> Unit)? = null,
    ): MediaTekAnimaPipelineResult {
        val errors = manifest.validate()
        require(errors.isEmpty()) { "Invalid MediaTek Anima bundle: ${errors.joinToString("; ")}" }
        require(request.prompt.isNotBlank()) { "Anima prompt must not be blank" }
        require(request.seed >= 0L) { "Anima seed must be nonnegative" }
        val profile = manifest.sampling.generationProfiles.firstOrNull { candidate ->
            (request.width == null || request.width == candidate.widthPixels) &&
                (request.height == null || request.height == candidate.heightPixels) &&
                (request.steps == null || request.steps in candidate.sigmaSchedules)
        } ?: error("Requested Anima resolution or step count is not present in the bundle")
        val steps = request.steps ?: profile.defaultSteps
        val sigmas = requireNotNull(profile.sigmaSchedules[steps]) { "Anima step count $steps is unsupported" }
        val cfgScale = request.cfgScale ?: profile.defaultCfgScale
        require(cfgScale.isFinite() && cfgScale in profile.minimumCfgScale..profile.maximumCfgScale) {
            "Anima CFG scale is outside the supported range"
        }
        val root = bundleRoot.canonicalFile
        require(root.isDirectory && root.canRead()) { "MediaTek Anima bundle directory is unavailable" }
        verifyAssetsForRequest(root, manifest, profile, steps)

        fun checkCancelled() {
            if (isCancellationRequested()) {
                throw MediaTekGraphExecutionException(MediaTekGraphFailureStage.EXECUTE, "generation was cancelled")
            }
        }
        checkCancelled()
        val textContract = manifest.textEncoder
        val positiveTokens = tokenizer.tokenize(
            root, textContract, request.prompt,
            textContract.qwenTokenizer.sequenceLength, textContract.t5Tokenizer.sequenceLength,
        )
        validateTokens(textContract, positiveTokens)
        checkCancelled()
        val negativeTokens = tokenizer.tokenize(
            root, textContract, request.negativePrompt,
            textContract.qwenTokenizer.sequenceLength, textContract.t5Tokenizer.sequenceLength,
        )
        validateTokens(textContract, negativeTokens)

        val graphRuntime = MediaTekNeuronGraphRunner(appContext).openRuntime()
        val sessions = linkedMapOf<String, MediaTekNeuronGraphSession>()
        val graphRuns = mutableListOf<MediaTekGraphRunEvidence>()
        val scheduler = MediaTekGraphScheduler()
        fun runGraph(graph: MediaTekGraphContract, inputs: Map<String, MediaTekTensorData>): List<MediaTekTensorData> {
            checkCancelled()
            val orderedInputs = graph.inputTensors.map { metadata ->
                inputs[metadata.name] ?: throw MediaTekGraphExecutionException(
                    MediaTekGraphFailureStage.INPUTS,
                    "no value was provided for required input '${metadata.name}' in ${graph.file}",
                )
            }
            if (inputs.keys != graph.inputTensors.mapTo(linkedSetOf()) { it.name }) {
                throw MediaTekGraphExecutionException(
                    MediaTekGraphFailureStage.INPUTS,
                    "input values do not match the manifest for ${graph.file}",
                )
            }
            val selection = scheduler.plan(
                graphId = graph.file,
                stage = graph.stage,
                transport = MediaTekGraphTransport.LITERT_JIT,
            )
            val attemptStartedNanos = System.nanoTime()
            fun elapsedMs() = ((System.nanoTime() - attemptStartedNanos) / 1_000_000L).coerceAtLeast(0L)
            val session = try {
                sessions.getOrPut(graph.file) { graphRuntime.openGraph(root, graph) }
            } catch (error: MediaTekGraphExecutionException) {
                scheduler.record(
                    selection,
                    MediaTekAttemptOutcome.Failed(error.stage, error.message.orEmpty()),
                    MediaTekBackendEvidence.Unverified,
                    elapsedMs(),
                )
                throw error
            }
            val result = try {
                session.run(orderedInputs)
            } catch (error: MediaTekGraphExecutionException) {
                scheduler.record(
                    selection,
                    MediaTekAttemptOutcome.Failed(error.stage, error.message.orEmpty()),
                    MediaTekBackendEvidence.Unverified,
                    elapsedMs(),
                )
                throw MediaTekGraphExecutionException(
                    error.stage,
                    "${graph.stage} graph '${graph.file}': ${error.message}",
                    error,
                    error.outputDiagnostic,
                )
            }
            scheduler.record(
                selection,
                MediaTekAttemptOutcome.Succeeded(result.evidence.outputSha256),
                result.evidence.toBackendEvidence(),
                elapsedMs(),
            )
            scheduler.adopt(selection)
            graphRuns += result.evidence
            return result.outputs
        }
        try {
            checkCancelled()
            val conditioning = loadConditioningAssets(root, textContract)
            val positiveHidden = encodePrompt(textContract, positiveTokens, conditioning, ::runGraph)
            val positiveContext = adaptContext(
                manifest.sampling.contextAdapter, positiveHidden, positiveTokens, ::runGraph, profile,
            )
            onProgress(MediaTekAnimaProgress("text_conditioning", 1, 2))
            checkCancelled()
            val negativeHidden = encodePrompt(textContract, negativeTokens, conditioning, ::runGraph)
            val negativeContext = adaptContext(
                manifest.sampling.contextAdapter, negativeHidden, negativeTokens, ::runGraph, profile,
            )
            onProgress(MediaTekAnimaProgress("text_conditioning", 2, 2))

            val sampling = manifest.sampling
            val latentShape = listOf(1, sampling.latentChannels, profile.heightPixels / sampling.latentDownsample,
                profile.widthPixels / sampling.latentDownsample)
            var latent = initializeLatent(root, profile, request.seed, latentShape)
            val blockModulationAsset = requireNotNull(profile.blockModulationsByStepCount[steps])
            val finalModulationAsset = requireNotNull(profile.finalModulationsByStepCount[steps])
            val blockModulationFile = verifiedAssetFile(root, blockModulationAsset.asset)
            val finalModulationFile = verifiedAssetFile(root, finalModulationAsset.asset)

            for (step in 0 until steps) {
                checkCancelled()
                val positiveFlow = predictFlow(
                    profile, steps, blockModulationFile, finalModulationFile, step, latent, latentShape,
                    positiveContext, sampling, MediaTekAnimaCfgBranch.POSITIVE, ::runGraph, ::checkCancelled,
                    onDiagnostic,
                )
                val negativeFlow = predictFlow(
                    profile, steps, blockModulationFile, finalModulationFile, step, latent, latentShape,
                    negativeContext, sampling, MediaTekAnimaCfgBranch.NEGATIVE, ::runGraph, ::checkCancelled,
                    onDiagnostic,
                )
                val guided = MediaTekAnimaTensorOps.classifierFreeGuidance(positiveFlow, negativeFlow, cfgScale)
                val updated = MediaTekAnimaTensorOps.eulerUpdate(latent, guided, sigmas[step], sigmas[step + 1])
                val latentTensor = MediaTekTensorData.Float32("latent", latentShape, updated)
                checkGate(profile, MediaTekAnimaNumericStage.UPDATED_LATENT,
                    MediaTekTensorData.Float32("previous_latent", latentShape, latent), latentTensor)
                latent = updated
                onProgress(MediaTekAnimaProgress("denoise", step + 1, steps))
            }

            checkCancelled()
            val vaeInputs = mapOf(
                sampling.vaeLatentInput to MediaTekTensorData.Float32(
                    sampling.vaeLatentInput,
                    listOf(1, sampling.latentChannels, 1, latentShape[2], latentShape[3]),
                    latent,
                ),
            )
            val vaeOutputs = runGraph(sampling.vae, vaeInputs)
            val imageTensor = vaeOutputs.firstOrNull { it.name == sampling.vaeImageOutput } as? MediaTekTensorData.Float32
                ?: error("VAE graph did not return float32 image output '${sampling.vaeImageOutput}'")
            checkGate(
                profile,
                MediaTekAnimaNumericStage.VAE_OUTPUT,
                MediaTekTensorData.Float32("updated_latent", latentShape, latent),
                imageTensor,
            )
            val png = MediaTekPngEncoder.encodeNcdhwRgb(
                values = imageTensor.values,
                width = profile.widthPixels,
                height = profile.heightPixels,
            )
            val outputSha = png.sha256Hex()
            val summary = scheduler.summary(
                quality = MediaTekQualityReport.notCompared(
                    "no reference tensor baseline is bundled; quality comparison stays unverified",
                ),
                outputValid = imageTensor.values.isNotEmpty() && imageTensor.values.all { it.isFinite() },
            )
            val evidence = MediaTekExecutionEvidence(
                state = MediaTekRuntimeState.UNVERIFIED,
                requestedRuntime = MediaTekAnimaManifest.RUNTIME,
                actualDelegate = null,
                actualRuntimeVersion = null,
                npuPartitions = null,
                cpuFallbackOps = null,
                stages = MediaTekGraphStage.entries.toSet(),
                semanticGeneration = true,
                outputMime = "image/png",
                outputSha256 = outputSha,
                message = "Complete Anima pipeline returned a PNG; LiteRT does not expose NPU partition evidence.",
            )
            onProgress(MediaTekAnimaProgress("complete", 1, 1))
            return MediaTekAnimaPipelineResult(png, evidence, graphRuns.toList(), request.seed, steps, summary)
        } finally {
            sessions.values.toList().asReversed().forEach { session ->
                try {
                    session.close()
                } catch (_: Exception) {
                    // Continue closing the remaining sessions.
                }
            }
            try {
                graphRuntime.close()
            } catch (_: Exception) {
                // Preserve the generation result or original execution failure.
            }
        }
    }

    private fun encodePrompt(
        text: MediaTekAnimaTextEncoderContract,
        sequence: MediaTekAnimaTokenSequence,
        conditioning: Map<Pair<String, String>, MediaTekTensorData>,
        runGraph: (MediaTekGraphContract, Map<String, MediaTekTensorData>) -> List<MediaTekTensorData>,
    ): MediaTekTensorData.Float32 {
        val graph = text.graphs.single()
        val inputs = linkedMapOf<String, MediaTekTensorData>(
            text.tokenIdsInput to MediaTekTensorData.Int32(
                text.tokenIdsInput, listOf(1, sequence.qwenTokenIds.size), sequence.qwenTokenIds,
            ),
            text.attentionMaskInput to MediaTekTensorData.Int32(
                text.attentionMaskInput, listOf(1, sequence.qwenAttentionMask.size), sequence.qwenAttentionMask,
            ),
        )
        inputs.putAll(conditioning.filterKeys { it.first == graph.file }.mapKeys { it.key.second })
        val outputs = runGraph(graph, inputs)
        return outputs.firstOrNull { it.name == text.embeddingsOutput } as? MediaTekTensorData.Float32
            ?: error("Text encoder did not return float32 embeddings '${text.embeddingsOutput}'")
    }

    private fun adaptContext(
        adapter: MediaTekAnimaContextAdapterContract,
        hidden: MediaTekTensorData.Float32,
        tokens: MediaTekAnimaTokenSequence,
        runGraph: (MediaTekGraphContract, Map<String, MediaTekTensorData>) -> List<MediaTekTensorData>,
        profile: MediaTekAnimaGenerationProfile,
    ): MediaTekTensorData.Float32 {
        val inputs = mapOf(
            adapter.hiddenInput to hidden.renamed(adapter.hiddenInput),
            adapter.qwenAttentionMaskInput to MediaTekTensorData.Int32(
                adapter.qwenAttentionMaskInput,
                listOf(1, tokens.qwenAttentionMask.size),
                tokens.qwenAttentionMask,
            ),
            adapter.t5TokenIdsInput to MediaTekTensorData.Int32(
                adapter.t5TokenIdsInput,
                listOf(1, tokens.t5TokenIds.size),
                tokens.t5TokenIds,
            ),
            adapter.t5AttentionMaskInput to MediaTekTensorData.Int32(
                adapter.t5AttentionMaskInput,
                listOf(1, tokens.t5AttentionMask.size),
                tokens.t5AttentionMask,
            ),
            adapter.t5WeightsInput to MediaTekTensorData.Float32(
                adapter.t5WeightsInput,
                listOf(1, tokens.t5TokenWeights.size),
                tokens.t5TokenWeights,
            ),
        )
        val adapted = runGraph(adapter.graph, inputs)
            .firstOrNull { it.name == adapter.adaptedContextOutput } as? MediaTekTensorData.Float32
            ?: error("Anima context adapter did not return '${adapter.adaptedContextOutput}'")
        checkGate(profile, MediaTekAnimaNumericStage.TEXT_CONTEXT_ADAPTER, hidden, adapted)
        return adapted
    }

    private fun predictFlow(
        profile: MediaTekAnimaGenerationProfile,
        totalSteps: Int,
        blockModulationFile: File,
        finalModulationFile: File,
        step: Int,
        latent: FloatArray,
        latentShape: List<Int>,
        context: MediaTekTensorData.Float32,
        sampling: MediaTekAnimaSamplingContract,
        cfgBranch: MediaTekAnimaCfgBranch,
        runGraph: (MediaTekGraphContract, Map<String, MediaTekTensorData>) -> List<MediaTekTensorData>,
        checkCancelled: () -> Unit,
        onDiagnostic: ((MediaTekAnimaDiagnosticSample) -> Unit)?,
    ): FloatArray {
        val paddedChannelsPerPatch = MediaTekAnimaSamplingContract.PATCH_FEATURES
        val patchTokens = MediaTekAnimaTensorOps.patchifyLatent(
            latent, sampling.latentChannels, latentShape[2], latentShape[3], sampling.patchSize,
            paddedChannelsPerPatch,
        )
        val tokenShape = listOf(1, patchTokens.size / paddedChannelsPerPatch, paddedChannelsPerPatch)
        val patchInput = MediaTekTensorData.Float32(sampling.prelude.patchTokensInput, tokenShape, patchTokens)
        checkGate(profile, MediaTekAnimaNumericStage.PATCHIFY, MediaTekTensorData.Float32("latent", latentShape, latent), patchInput)
        val preludeOutputs = try {
            runGraph(sampling.prelude.graph, mapOf(sampling.prelude.patchTokensInput to patchInput))
        } catch (error: MediaTekGraphExecutionException) {
            reportFailedOutputDiagnostic(
                onDiagnostic = onDiagnostic,
                stage = MediaTekAnimaDiagnosticStage.PRELUDE,
                stepIndex = step,
                cfgBranch = cfgBranch,
                blockIndex = null,
                inputTensorName = sampling.prelude.patchTokensInput,
                input = patchInput,
                error = error,
            )
            throw MediaTekGraphExecutionException(
                error.stage,
                "Anima prelude graph '${sampling.prelude.graph.file}': ${error.message}",
                error,
                error.outputDiagnostic,
            )
        }
        var hidden = preludeOutputs.firstOrNull { it.name == sampling.prelude.hiddenOutput } as? MediaTekTensorData.Float32
            ?: error("Anima prelude did not return hidden state '${sampling.prelude.hiddenOutput}'")
        onDiagnostic?.invoke(
            createMediaTekAnimaDiagnosticSample(
                stage = MediaTekAnimaDiagnosticStage.PRELUDE,
                stepIndex = step,
                cfgBranch = cfgBranch,
                blockIndex = null,
                graphFile = sampling.prelude.graph.file,
                input = patchInput,
                output = hidden,
                inputTensorName = sampling.prelude.patchTokensInput,
                outputTensorName = sampling.prelude.hiddenOutput,
            ),
        )
        checkGate(profile, MediaTekAnimaNumericStage.PRELUDE, patchInput, hidden)

        sampling.blocks.forEach { block ->
            checkCancelled()
            val hiddenInput = hidden.renamed(block.hiddenInput)
            val modulationInputs = block.modulationInputs.mapIndexed { part, name ->
                val shape = profile.blockModulationsByStepCount.getValue(totalSteps).shape
                val flat = loadFloat16Slice(blockModulationFile, shape, step, block.index, part)
                name to MediaTekTensorData.Float32(name, listOf(1, flat.size), flat)
            }.toMap()
            val inputs = linkedMapOf<String, MediaTekTensorData>(
                block.hiddenInput to hiddenInput,
                block.contextInput to context.renamed(block.contextInput),
            ).apply { putAll(modulationInputs) }
            val outputs = try {
                runGraph(block.graph, inputs)
            } catch (error: MediaTekGraphExecutionException) {
                reportFailedOutputDiagnostic(
                    onDiagnostic = onDiagnostic,
                    stage = MediaTekAnimaDiagnosticStage.DIT_BLOCK,
                    stepIndex = step,
                    cfgBranch = cfgBranch,
                    blockIndex = block.index,
                    inputTensorName = block.hiddenInput,
                    input = hidden,
                    error = error,
                )
                throw MediaTekGraphExecutionException(
                    error.stage,
                    "Anima DiT block ${block.index} graph '${block.graph.file}': ${error.message}",
                    error,
                    error.outputDiagnostic,
                )
            }
            val output = outputs.firstOrNull { it.name == block.hiddenOutput } as? MediaTekTensorData.Float32
                ?: error("Anima block ${block.index} did not return hidden state '${block.hiddenOutput}'")
            onDiagnostic?.invoke(
                createMediaTekAnimaDiagnosticSample(
                    stage = MediaTekAnimaDiagnosticStage.DIT_BLOCK,
                    stepIndex = step,
                    cfgBranch = cfgBranch,
                    blockIndex = block.index,
                    graphFile = block.graph.file,
                    input = hidden,
                    output = output,
                    inputTensorName = block.hiddenInput,
                    outputTensorName = block.hiddenOutput,
                ),
            )
            try {
                checkGate(profile, MediaTekAnimaNumericStage.DIT_BLOCK, hidden, output)
            } catch (error: MediaTekGraphExecutionException) {
                throw MediaTekGraphExecutionException(
                    error.stage,
                    "Anima DiT block ${block.index} (${block.graph.file}): ${error.message}",
                    error,
                )
            }
            hidden = output
        }

        val finalTensor = loadFinalModulation(finalModulationFile, profile, totalSteps, step)
        val finalHiddenInput = hidden
        val finalOutputs = try {
            runGraph(
                sampling.final.graph,
                mapOf(
                    sampling.final.hiddenInput to hidden.renamed(sampling.final.hiddenInput),
                    sampling.final.finalModulationInput to MediaTekTensorData.Float32(
                        sampling.final.finalModulationInput, listOf(1, finalTensor.size), finalTensor,
                    ),
                ),
            )
        } catch (error: MediaTekGraphExecutionException) {
            reportFailedOutputDiagnostic(
                onDiagnostic = onDiagnostic,
                stage = MediaTekAnimaDiagnosticStage.FINAL,
                stepIndex = step,
                cfgBranch = cfgBranch,
                blockIndex = null,
                inputTensorName = sampling.final.hiddenInput,
                input = hidden,
                error = error,
            )
            throw MediaTekGraphExecutionException(
                error.stage,
                "Anima final graph '${sampling.final.graph.file}': ${error.message}",
                error,
                error.outputDiagnostic,
            )
        }
        val prediction = finalOutputs.firstOrNull { it.name == sampling.final.flowPredictionOutput } as? MediaTekTensorData.Float32
            ?: error("Anima final graph did not return flow prediction '${sampling.final.flowPredictionOutput}'")
        onDiagnostic?.invoke(
            createMediaTekAnimaDiagnosticSample(
                stage = MediaTekAnimaDiagnosticStage.FINAL,
                stepIndex = step,
                cfgBranch = cfgBranch,
                blockIndex = null,
                graphFile = sampling.final.graph.file,
                input = finalHiddenInput,
                output = prediction,
                inputTensorName = sampling.final.hiddenInput,
                outputTensorName = sampling.final.flowPredictionOutput,
            ),
        )
        checkGate(profile, MediaTekAnimaNumericStage.FLOW_PREDICTION, finalHiddenInput, prediction)
        return MediaTekAnimaTensorOps.unpatchifyPrediction(
            prediction.values,
            sampling.latentChannels,
            latentShape[2],
            latentShape[3],
            sampling.patchSize,
        )
    }

    private fun loadFinalModulation(file: File, profile: MediaTekAnimaGenerationProfile, steps: Int, step: Int): FloatArray {
        val tensor = profile.finalModulationsByStepCount[steps]
            ?: error("No final modulation asset for requested $steps steps")
        val width = tensor.shape.drop(1).fold(1, Math::multiplyExact)
        val bytes = readSlice(file, (step * width * 2).toLong(), width * 2)
        return MediaTekAnimaTensorOps.decodeFloat16LittleEndian(bytes, width)
    }

    private fun loadFloat16Slice(file: File, shape: List<Int>, step: Int, block: Int, part: Int): FloatArray {
        require(shape.size == 5 && step in 0 until shape[0] && block in 0 until shape[1] && part in 0 until shape[2])
        val vectorSize = shape[3] * shape[4]
        val offsetElements = (((step * shape[1] + block) * shape[2] + part) * vectorSize)
        return MediaTekAnimaTensorOps.decodeFloat16LittleEndian(
            readSlice(file, offsetElements.toLong() * 2L, vectorSize * 2), vectorSize,
        )
    }

    private fun initializeLatent(
        root: File,
        profile: MediaTekAnimaGenerationProfile,
        seed: Long,
        shape: List<Int>,
    ): FloatArray = when (profile.latentInitialization.distribution) {
        MediaTekAnimaLatentDistribution.STANDARD_NORMAL -> {
            require(!profile.latentInitialization.seedRequired || seed >= 0L)
            MediaTekAnimaTensorOps.standardNormalLikeTorchMt19937(seed, shape.fold(1, Math::multiplyExact))
        }
        MediaTekAnimaLatentDistribution.VERIFIED_ASSET -> {
            val asset = requireNotNull(profile.latentInitialization.verifiedLatentAsset)
            require(asset.shape == shape) { "Verified latent asset shape does not match the selected resolution" }
            loadFloatAsset(root, asset)
        }
    }

    private fun loadConditioningAssets(
        root: File,
        text: MediaTekAnimaTextEncoderContract,
    ): Map<Pair<String, String>, MediaTekTensorData> = text.conditioningAssets.associate { binding ->
        val graph = text.graphs.single { it.file.replace('\\', '/') == binding.consumerGraphFile.replace('\\', '/') }
        val input = requireNotNull(graph.inputTensor(binding.consumerInput))
        val data = loadAssetTensor(root, binding.asset, input)
        (binding.consumerGraphFile to binding.consumerInput) to data
    }

    private fun loadFloatAsset(root: File, asset: MediaTekShapedAssetContract): FloatArray {
        val file = verifiedAssetFile(root, asset.asset)
        val count = tensorElementCount(asset.shape)
        val bytesPerElement = when (asset.dtype.lowercase()) {
            "float16" -> 2
            "float32" -> Float.SIZE_BYTES
            else -> error("Latent initialization asset must use float16 or float32")
        }
        requireExpectedAssetBytes(file, count, bytesPerElement)
        return when (asset.dtype.lowercase()) {
            "float16" -> MediaTekAnimaTensorOps.decodeFloat16LittleEndian(file.readBytes(), count)
            "float32" -> readFloat32(file.readBytes(), count)
            else -> error("Latent initialization asset must use float16 or float32")
        }
    }

    private fun loadAssetTensor(root: File, asset: MediaTekBundleAssetContract, metadata: MediaTekTensorContract): MediaTekTensorData {
        val file = verifiedAssetFile(root, asset)
        val declaredFormat = asset.mediaType.lowercase()
        val count = tensorElementCount(metadata.shape)
        val bytesPerElement = when {
            declaredFormat.contains("float16") || metadata.dtype.lowercase() in setOf("float16", "f16") -> 2
            declaredFormat.contains("float32") || metadata.dtype.lowercase() in setOf("float32", "f32", "float") -> Float.SIZE_BYTES
            declaredFormat.contains("int32") || metadata.dtype.lowercase() in setOf("int32", "i32") -> Int.SIZE_BYTES
            declaredFormat.contains("int8") || metadata.dtype.lowercase() in setOf("int8", "i8") -> 1
            else -> error("Unsupported conditioning asset media type '${asset.mediaType}' for '${metadata.name}'")
        }
        requireExpectedAssetBytes(file, count, bytesPerElement)
        val bytes = file.readBytes()
        return when {
            declaredFormat.contains("float16") || metadata.dtype.lowercase() in setOf("float16", "f16") -> MediaTekTensorData.Float32(
                metadata.name, metadata.shape, MediaTekAnimaTensorOps.decodeFloat16LittleEndian(bytes, count),
            )
            declaredFormat.contains("float32") || metadata.dtype.lowercase() in setOf("float32", "f32", "float") ->
                MediaTekTensorData.Float32(metadata.name, metadata.shape, readFloat32(bytes, count))
            declaredFormat.contains("int32") || metadata.dtype.lowercase() in setOf("int32", "i32") ->
                MediaTekTensorData.Int32(metadata.name, metadata.shape, readInt32(bytes, count))
            declaredFormat.contains("int8") || metadata.dtype.lowercase() in setOf("int8", "i8") -> {
                require(bytes.size == count)
                MediaTekTensorData.Int8(metadata.name, metadata.shape, bytes)
            }
            else -> error("Unsupported conditioning asset media type '${asset.mediaType}' for '${metadata.name}'")
        }
    }

    private fun verifyAssetsForRequest(
        root: File,
        manifest: MediaTekAnimaManifest,
        profile: MediaTekAnimaGenerationProfile,
        steps: Int,
    ) {
        manifest.textEncoder.assets().forEach { verifiedAssetFile(root, it) }
        profile.latentInitialization.verifiedLatentAsset?.let { verifiedAssetFile(root, it.asset) }
        verifiedAssetFile(root, profile.blockModulationsByStepCount.getValue(steps).asset)
        verifiedAssetFile(root, profile.finalModulationsByStepCount.getValue(steps).asset)
    }

    private fun verifiedAssetFile(root: File, asset: MediaTekBundleAssetContract): File {
        val normalizedPath = asset.file.replace('\\', '/')
        require(isSafeRelativeBundlePath(normalizedPath, "assets")) { "Unsafe MediaTek asset path '${asset.file}'" }
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, normalizedPath).canonicalFile
        require(file.toPath().startsWith(canonicalRoot.toPath()) && file.isFile && file.canRead()) {
            "MediaTek asset is missing: ${asset.file}"
        }
        require(file.length() == asset.sizeBytes) { "MediaTek asset size mismatch: ${asset.file}" }
        require(file.sha256Hex() == asset.sha256.lowercase()) { "MediaTek asset SHA-256 mismatch: ${asset.file}" }
        return file
    }

    private fun verifiedAssetFile(root: File, asset: MediaTekShapedAssetContract): File = verifiedAssetFile(root, asset.asset)

    private fun readSlice(file: File, offset: Long, size: Int): ByteArray {
        require(offset >= 0L && size >= 0 && offset + size <= file.length()) { "MediaTek tensor asset slice is out of bounds" }
        return java.io.RandomAccessFile(file, "r").use { source ->
            source.seek(offset)
            ByteArray(size).also(source::readFully)
        }
    }

    private fun readFloat32(bytes: ByteArray, count: Int): FloatArray {
        require(bytes.size.toLong() == count.toLong() * Float.SIZE_BYTES)
        val values = FloatArray(count)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values)
        return values
            .also { values -> require(values.all(Float::isFinite)) { "Float32 asset contains NaN or infinity" } }
    }

    private fun readInt32(bytes: ByteArray, count: Int): IntArray {
        require(bytes.size.toLong() == count.toLong() * Int.SIZE_BYTES)
        val values = IntArray(count)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(values)
        return values
    }

    private fun tensorElementCount(shape: List<Int>): Int {
        val count = shape.fold(1L) { product, dimension -> Math.multiplyExact(product, dimension.toLong()) }
        require(shape.isNotEmpty() && shape.all { it > 0 } && count <= MediaTekTensorData.MAX_TENSOR_ELEMENTS) {
            "MediaTek asset tensor shape is invalid or exceeds the element limit"
        }
        return count.toInt()
    }

    private fun requireExpectedAssetBytes(file: File, elementCount: Int, bytesPerElement: Int) {
        val expectedBytes = Math.multiplyExact(elementCount.toLong(), bytesPerElement.toLong())
        require(expectedBytes <= MAX_ASSET_TENSOR_BYTES && file.length() == expectedBytes) {
            "MediaTek tensor asset has an unexpected or oversized byte length"
        }
    }

    private fun validateTokens(contract: MediaTekAnimaTextEncoderContract, sequence: MediaTekAnimaTokenSequence) {
        val qwenLength = contract.qwenTokenizer.sequenceLength
        val t5Length = contract.t5Tokenizer.sequenceLength
        require(sequence.qwenTokenIds.size == qwenLength && sequence.qwenAttentionMask.size == qwenLength) {
            "Qwen tokenizer must return exactly $qwenLength token IDs and attention-mask values"
        }
        require(sequence.t5TokenIds.size == t5Length && sequence.t5AttentionMask.size == t5Length &&
            sequence.t5TokenWeights.size == t5Length
        ) { "T5 tokenizer must return exactly $t5Length token IDs, weights, and mask values" }
        require(sequence.qwenAttentionMask.all { it == 0 || it == 1 } &&
            sequence.t5AttentionMask.all { it == 0 || it == 1 }
        ) { "Tokenizer attention masks must contain only zero or one" }
        require(sequence.t5TokenWeights.all(Float::isFinite)) { "T5 tokenizer returned a non-finite prompt weight" }
        require(sequence.t5TokenWeights.indices.all { index ->
            sequence.t5AttentionMask[index] == 1 || sequence.t5TokenWeights[index] == contract.t5Tokenizer.paddingWeight
        }) { "T5 padding weights do not match the manifest" }
    }

    private fun checkGate(
        profile: MediaTekAnimaGenerationProfile,
        stage: MediaTekAnimaNumericStage,
        input: MediaTekTensorData.Float32?,
        output: MediaTekTensorData.Float32,
    ) {
        val gate = requireNotNull(profile.numericGates[stage]) { "No numeric gate declared for $stage" }
        val errors = MediaTekAnimaTensorOps.validateNumericGate(input, output, gate, stage)
        if (errors.isNotEmpty()) {
            throw MediaTekGraphExecutionException(MediaTekGraphFailureStage.OUTPUTS, errors.joinToString("; "))
        }
    }

    private companion object {
        const val MAX_ASSET_TENSOR_BYTES = 128L * 1024L * 1024L
    }
}

private fun MediaTekGraphRunEvidence.toBackendEvidence(): MediaTekBackendEvidence =
    if (reportsNpuPartitions) {
        MediaTekBackendEvidence.Confirmed(
            provider = "litert-mediatek",
            executionScope = MediaTekExecutionScope.GRAPH,
            npuPartitions = npuPartitions,
            cpuFallbackOps = cpuFallbackOps,
            source = MediaTekEvidenceSource.RUNTIME_COUNTERS,
        )
    } else {
        MediaTekBackendEvidence.Unverified
    }

private fun MediaTekTensorData.Float32.renamed(name: String) =
    MediaTekTensorData.Float32(name, shape, values)

internal object MediaTekPngEncoder {
    fun encodeNcdhwRgb(values: FloatArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0 && values.size.toLong() == 3L * width * height)
        val raw = ByteArray(height * (width * 3 + 1))
        var out = 0
        for (y in 0 until height) {
            raw[out++] = 0
            for (x in 0 until width) {
                for (channel in 0..2) {
                    val value = values[channel * width * height + y * width + x]
                    require(value.isFinite()) { "VAE image output contains a non-finite pixel" }
                    raw[out++] = (((value.coerceIn(-1f, 1f) + 1f) * 127.5f).toInt()).toByte()
                }
            }
        }
        val compressed = ByteArrayOutputStream().also { buffer ->
            DeflaterOutputStream(buffer).use { it.write(raw) }
        }.toByteArray()
        val png = ByteArrayOutputStream()
        png.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        writeChunk(png, "IHDR", header)
        writeChunk(png, "IDAT", compressed)
        writeChunk(png, "IEND", ByteArray(0))
        return png.toByteArray()
    }

    private fun writeChunk(output: ByteArrayOutputStream, name: String, data: ByteArray) {
        val type = name.toByteArray(Charsets.US_ASCII)
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array())
        output.write(type)
        output.write(data)
        val crc = CRC32().apply { update(type); update(data) }.value.toInt()
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc).array())
    }
}

private fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}

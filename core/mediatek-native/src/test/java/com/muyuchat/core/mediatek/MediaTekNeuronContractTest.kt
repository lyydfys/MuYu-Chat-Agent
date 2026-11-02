package com.muyuchat.core.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaTekNeuronContractTest {
    @Test
    fun missingSdkIsExplicitlyBlocked() {
        val probe = MediaTekSdkResolver.probe(null)
        assertEquals(MediaTekRuntimeState.BLOCKED, probe.state)
        assertTrue(probe.message.contains("blocked"))
    }

    @Test
    fun arbitraryDirectoryIsBlocked() {
        val root = Files.createTempDirectory("mca-neuron-empty").toFile()
        try {
            assertEquals(MediaTekRuntimeState.BLOCKED, MediaTekSdkResolver.probe(root).state)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun graphBoundaryRejectsLiteRtUnsupportedFloat16IoBeforeLoading() {
        val tensor = MediaTekTensorContract("input", "float16", "NC", listOf(1, 4))

        assertTrue(tensor.validate("graph input").any { it.contains("unsupported dtype") })
    }

    @Test
    fun sdkMarkerOnlyProvisionsPreflightAndNeverProvesExecution() {
        val root = Files.createTempDirectory("mca-neuron-sdk").toFile()
        try {
            File(root, "neuron-sdk.properties").writeText("version=fixture")
            assertEquals(MediaTekRuntimeState.READY, MediaTekSdkResolver.probe(root).state)
        } finally {
            root.deleteRecursively()
        }
        assertFalse(MediaTekExecutionEvidence.blocked("delegate not run").provesNpuExecution)
    }

    @Test
    fun unavailablePartitionAndFallbackCountersRemainUnknownAndCannotProveNpu() {
        val evidence = MediaTekExecutionEvidence(
            state = MediaTekRuntimeState.UNVERIFIED,
            requestedRuntime = MediaTekAnimaManifest.RUNTIME,
            actualDelegate = "LiteRT_NPU_REQUESTED",
            actualRuntimeVersion = null,
            npuPartitions = null,
            cpuFallbackOps = null,
            stages = MediaTekGraphStage.entries.toSet(),
            semanticGeneration = true,
            outputMime = "image/png",
            outputSha256 = "0".repeat(64),
            message = "LiteRT does not expose partition evidence.",
        )

        assertFalse(evidence.provesNpuExecution)
    }

    @Test
    fun referenceAnimaTopologySeparatesFlowPredictionFromSamplerLatentAndVaeInput() {
        val manifest = validManifest()

        assertTrue(manifest.validate().toString(), manifest.validate().isEmpty())
        assertEquals(MediaTekAnimaManifest.SCHEMA, manifest.schema)
        assertEquals(28, manifest.sampling.blocks.size)
        assertEquals(listOf(1.0, 0.75, 0.5, 0.25, 0.0), manifest.sampling.generationProfiles.first().sigmas(4))
        assertEquals(
            listOf("context_adapter", "prelude") + (0..27).map { "block-$it" } + listOf("final", "vae"),
            manifest.sampling.graphs().map { File(it.file).nameWithoutExtension },
        )
        assertEquals("t5_ids", manifest.sampling.contextAdapter.t5TokenIdsInput)
        assertEquals("t5_weights", manifest.sampling.contextAdapter.t5WeightsInput)
        assertEquals("adapted_context", manifest.sampling.contextAdapter.adaptedContextOutput)
        assertEquals(listOf(1, 1024, 64), requireNotNull(manifest.sampling.final.graph.outputTensor("flow")).shape)
        assertEquals(listOf(1, 16, 1, 64, 64), requireNotNull(manifest.sampling.vae.inputTensor("latent")).shape)
    }

    @Test
    fun eachBlockRunsOneConditioningBranchAndTextEncoderSupportsDynamicPositiveAndNegativePrompts() {
        val manifest = validManifest()
        val block = manifest.sampling.blocks.first()

        assertEquals(setOf(MediaTekConditioningBranch.POSITIVE, MediaTekConditioningBranch.NEGATIVE), manifest.textEncoder.conditioningBranches)
        assertEquals("context", block.contextInput)
        assertEquals(listOf("mod0", "mod1", "mod2"), block.modulationInputs)
        assertEquals(1, block.graph.inputs.count { it == block.contextInput })
    }

    @Test
    fun textEncoderMustAcceptPromptTokensAndEmitExpectedContextShape() {
        val manifest = validManifest()
        val encoder = manifest.textEncoder.copy(
            graphs = listOf(graph(
                stage = MediaTekGraphStage.TEXT_ENCODER,
                file = "graphs/text_encoder.tflite",
                inputs = listOf(tensor("attention_mask", "int32", "NT", 1, 512)),
                outputs = listOf(tensor("embeddings", "float32", "NTC", 1, 512, 768)),
            )),
        )
        val errors = manifest.copy(textEncoder = encoder).validate()

        assertTrue(errors.any { it.contains("tokenIdsInput") })
        assertTrue(errors.any { it.contains("text encoder must emit float32 NTC [1,512,1024]") })
    }

    @Test
    fun unsupportedMultiGraphTextEncoderIsRejectedByTheManifestContract() {
        val manifest = validManifest()
        val graph = manifest.textEncoder.graphs.single()
        val invalid = manifest.copy(
            textEncoder = manifest.textEncoder.copy(
                graphs = listOf(graph, graph.copy(file = "graphs/text_encoder_part_2.tflite")),
            ),
        )

        assertTrue(
            invalid.validate().any { it.contains("exactly one graph; multi-graph chaining is unsupported") },
        )
    }

    @Test
    fun fixedConditioningOnlyBundleCannotPretendToSupportDynamicPrompts() {
        val manifest = validManifest().copy(
            textEncoder = validManifest().textEncoder.copy(conditioningBranches = setOf(MediaTekConditioningBranch.POSITIVE)),
        )
        assertTrue(manifest.validate().any { it.contains("dynamic positive and negative") })
    }

    @Test
    fun tokenizerAssetPathMustBeSafeAndSourcesAreMutuallyExclusive() {
        val manifest = validManifest()
        val text = manifest.textEncoder.copy(
            qwenTokenizer = manifest.textEncoder.qwenTokenizer.copy(
                source = MediaTekTokenizerSource.BUNDLE_ASSET,
                asset = asset("assets/../tokenizer.json"),
                providerId = "external.provider",
            ),
        )
        val errors = manifest.copy(textEncoder = text).validate()

        assertTrue(errors.any { it.contains("cannot set providerId") })
        assertTrue(errors.any { it.contains("relative assets/") })
    }

    @Test
    fun qwenAndT5TokenizersHaveIndependentSourcesAndStaticLengths() {
        val manifest = validManifest()
        val qwenAsset = asset("assets/qwen-merges.txt")
        val t5Asset = asset("assets/t5-tokenizer.json")
        val text = manifest.textEncoder.copy(
            qwenTokenizer = tokenizer(
                sequenceLength = 256,
                padTokenId = 151643,
                weighting = MediaTekAnimaTokenWeighting.UNIFORM_ONE,
            ).copy(source = MediaTekTokenizerSource.BUNDLE_ASSET, asset = qwenAsset, providerId = null),
            t5Tokenizer = tokenizer(
                sequenceLength = 384,
                padTokenId = 0,
                weighting = MediaTekAnimaTokenWeighting.PROMPT_ATTENTION,
            ).copy(source = MediaTekTokenizerSource.BUNDLE_ASSET, asset = t5Asset, providerId = null),
        )
        val adapter = manifest.sampling.contextAdapter.copy(
            graph = contextAdapterGraph(qwenSequenceLength = 256, t5SequenceLength = 384),
        )
        val encoder = manifest.textEncoder.graphs.single().copy(
            inputTensors = listOf(
                tensor("token_ids", "int32", "NT", 1, 256),
                tensor("attention_mask", "int32", "NT", 1, 256),
            ),
            outputTensors = listOf(tensor("embeddings", "float32", "NTC", 1, 256, 1024)),
        )
        val updated = manifest.copy(
            textEncoder = text.copy(graphs = listOf(encoder)),
            sampling = manifest.sampling.copy(contextAdapter = adapter),
        )

        assertTrue(updated.validate().toString(), updated.validate().isEmpty())
        assertEquals(listOf("assets/qwen-merges.txt", "assets/t5-tokenizer.json"), updated.textEncoder.assets().map { it.file })
    }

    @Test
    fun tokenizerLengthsAndAdapterMaskWeightBindingsMustMatchStaticGraphShapes() {
        val manifest = validManifest()
        val adapter = manifest.sampling.contextAdapter.copy(
            t5AttentionMaskInput = "missing_t5_mask",
            t5WeightsInput = "t5_ids",
        )
        val errors = manifest.copy(sampling = manifest.sampling.copy(contextAdapter = adapter)).validate()

        assertTrue(errors.any { it.contains("does not declare T5 attention-mask input") })
        assertTrue(errors.any { it.contains("must be float32 NT [1,512]") })

        val mismatchedTokenizer = manifest.textEncoder.copy(
            t5Tokenizer = manifest.textEncoder.t5Tokenizer.copy(sequenceLength = 511),
        )
        val lengthErrors = manifest.copy(textEncoder = mismatchedTokenizer).validate()
        assertTrue(lengthErrors.any { it.contains("T5 token IDs must be int32 NT [1,511]") })
        assertTrue(lengthErrors.any { it.contains("T5 attention mask must be int32 NT [1,511]") })
        assertTrue(lengthErrors.any { it.contains("T5 token weights must be float32 NT [1,511]") })
    }

    @Test
    fun DiTBlocksMustConsumeTheContextAdapterOutputTensor() {
        val manifest = validManifest()
        val blocks = manifest.sampling.blocks.toMutableList().also { blockList ->
            val first = blockList.first()
            val graph = first.graph
            val context = graph.inputTensor("context")!!
            blockList[0] = first.copy(
                graph = graph.copy(inputTensors = graph.inputTensors.map {
                    if (it.name == context.name) it.copy(shape = listOf(1, 511, 1024)) else it
                }),
            )
        }
        val errors = manifest.copy(sampling = manifest.sampling.copy(blocks = blocks)).validate()

        assertTrue(errors.any { it.contains("context input is incompatible with adapted Anima text conditioning") })
    }

    @Test
    fun tokenizerAssetsMustNotAliasOneAnotherOrGraphFiles() {
        val manifest = validManifest()
        val qwenAsset = asset("assets/tokenizer.json")
        val text = manifest.textEncoder.copy(
            qwenTokenizer = manifest.textEncoder.qwenTokenizer.copy(
                source = MediaTekTokenizerSource.BUNDLE_ASSET,
                asset = qwenAsset,
                providerId = null,
            ),
            t5Tokenizer = manifest.textEncoder.t5Tokenizer.copy(
                source = MediaTekTokenizerSource.BUNDLE_ASSET,
                asset = qwenAsset.copy(),
                providerId = null,
            ),
        )

        assertTrue(manifest.copy(textEncoder = text).validate().any { it.contains("graph and asset paths must be unique") })
        val graphCollision = text.copy(t5Tokenizer = text.t5Tokenizer.copy(asset = asset("graphs/text_encoder.tflite")))
        assertTrue(manifest.copy(textEncoder = graphCollision).validate().any { it.contains("graph and asset paths must be unique") })
    }

    @Test
    fun verifiedLatentAndEveryProfileModulationAssetAreCheckedForGraphPathCollisions() {
        val manifest = validManifest()
        val profile = manifest.sampling.generationProfiles.first()
        val graphPathAsset = asset("graphs/context_adapter.tflite")
        val latentCollision = profile.copy(
            latentInitialization = MediaTekAnimaLatentInitializationContract(
                distribution = MediaTekAnimaLatentDistribution.VERIFIED_ASSET,
                seedRequired = false,
                verifiedLatentAsset = shapedAsset(graphPathAsset.file, "float32", listOf(1, 16, 64, 64)),
            ),
        )
        val blockModulationCollision = profile.copy(
            blockModulationsByStepCount = profile.blockModulationsByStepCount.mapValues { (_, shaped) ->
                shaped.copy(asset = graphPathAsset)
            },
        )
        val finalModulationCollision = profile.copy(
            finalModulationsByStepCount = profile.finalModulationsByStepCount.mapValues { (_, shaped) ->
                shaped.copy(asset = graphPathAsset)
            },
        )
        listOf(latentCollision, blockModulationCollision, finalModulationCollision).forEach { invalidProfile ->
            val candidate = manifest.copy(
                sampling = manifest.sampling.copy(
                    generationProfiles = listOf(invalidProfile) + manifest.sampling.generationProfiles.drop(1),
                ),
            )
            assertTrue(candidate.validate().any { it.contains("graph and asset paths must be unique") })
        }
    }

    @Test
    fun malformedBlockCountOrderAndHiddenHandoffAreRejected() {
        val manifest = validManifest()
        val blocks = manifest.sampling.blocks.toMutableList().also { values ->
            values.removeAt(values.lastIndex)
            values[3] = values[3].copy(index = 2)
            values[4] = values[4].copy(
                graph = blockGraph("block-4", hiddenShape = listOf(1, 1024, 2048), outputShape = listOf(1, 1024, 2048))
                    .copy(outputTensors = listOf(tensor("hidden_out", "float32", "NTC", 1, 1024, 1024))),
            )
        }
        val errors = manifest.copy(sampling = manifest.sampling.copy(blocks = blocks)).validate()

        assertTrue(errors.any { it.contains("exactly 28") })
        assertTrue(errors.any { it.contains("indexes must be unique and ordered") })
        assertTrue(errors.any { it.contains("hidden output shape") })
    }

    @Test
    fun finalPredictionCannotBeMistakenForVaeUpdatedLatent() {
        val manifest = validManifest()
        val badVae = manifest.sampling.vae.copy(
            inputTensors = listOf(tensor("latent", "float32", "NTC", 1, 1024, 64)),
        )
        val errors = manifest.copy(sampling = manifest.sampling.copy(vae = badVae)).validate()

        assertTrue(errors.any { it.contains("VAE input must match") })
    }

    @Test
    fun sigmaSchedulesMustMatchStepCountAndPerStepModulationAssets() {
        val manifest = validManifest()
        val profile = manifest.sampling.generationProfiles.first()
        val invalid = profile.copy(
            sigmaSchedules = mapOf(4 to listOf(1.0, 0.5, 0.0)),
            blockModulationsByStepCount = emptyMap(),
        )
        val errors = manifest.copy(
            sampling = manifest.sampling.copy(generationProfiles = listOf(invalid)),
        ).validate()

        assertTrue(errors.any { it.contains("stepCount + 1") })
        assertTrue(errors.any { it.contains("block modulation step counts must match") })
    }

    @Test
    fun numericDriftGateRequiresEveryStageAndRejectsNonFiniteAllowance() {
        val profile = validManifest().sampling.generationProfiles.first()
        val gates = profile.numericGates.toMutableMap().also {
            it.remove(MediaTekAnimaNumericStage.TEXT_CONTEXT_ADAPTER)
            it[MediaTekAnimaNumericStage.DIT_BLOCK] = gate().copy(maximumNonFiniteElements = 1)
        }
        val errors = validManifest().copy(
            sampling = validManifest().sampling.copy(
                generationProfiles = listOf(profile.copy(numericGates = gates)),
            ),
        ).validate()

        assertTrue(errors.any { it.contains("missing numeric gates") })
        assertTrue(errors.any { it.contains("TEXT_CONTEXT_ADAPTER") })
        assertTrue(errors.any { it.contains("maximumNonFiniteElements must be zero") })
    }

    @Test
    fun numericGatesCannotDisableRuntimeSafetyCeilings() {
        val looseGate = gate().copy(
            maximumAbs = 10_000.0,
            minimumOutputToInputStdRatio = 0.01,
            maximumOutputToInputStdRatio = 100.0,
        )
        val contractErrors = looseGate.validate(MediaTekAnimaNumericStage.DIT_BLOCK)
        val runtimeErrors = MediaTekAnimaTensorOps.validateNumericGate(
            input = MediaTekTensorData.Float32("in", listOf(1, 4), floatArrayOf(-1f, 1f, -1f, 1f)),
            output = MediaTekTensorData.Float32("out", listOf(1, 4), floatArrayOf(-5_000f, 5_000f, -5_000f, 5_000f)),
            gate = looseGate,
            stage = MediaTekAnimaNumericStage.DIT_BLOCK,
        )

        assertTrue(contractErrors.any { it.contains("runtime safety ceiling") })
        assertTrue(contractErrors.any { it.contains("minimum drift ratio") })
        assertTrue(contractErrors.any { it.contains("maximum drift ratio") })
        assertTrue(runtimeErrors.any { it.contains("exceeds 1000") })
        assertTrue(runtimeErrors.any { it.contains("outside [0.3,3.0]") })
    }

    @Test
    fun graphAndAssetPathsAndTensorMetadataRejectTraversalAndInvalidShapes() {
        val malformedGraph = graph(
            stage = MediaTekGraphStage.DIFFUSION,
            file = "graphs/../outside.tflite",
            inputs = listOf(tensor("input", "float16", "NC", 0)),
            outputs = listOf(tensor("output", "float16", "NC", 1)),
        ).copy(sha256 = "bad", sizeBytes = 0)
        val errors = malformedGraph.validate()
        val assetErrors = asset("assets/../tokenizer.json").validate("tokenizer")

        assertTrue(errors.any { it.contains("relative graphs/") })
        assertTrue(errors.any { it.contains("sha256") })
        assertTrue(errors.any { it.contains("sizeBytes") })
        assertTrue(errors.any { it.contains("shape") })
        assertTrue(assetErrors.any { it.contains("relative assets/") })
    }

    @Test
    fun runtimeRequirementsAndGraphPrecisionMustAgree() {
        val manifest = validManifest().copy(
            runtimeRequirements = MediaTekRuntimeRequirements(
                minimumNeuronVersion = "9.x",
                minimumLiteRtVersion = "2.3.0",
                compilerPluginId = "",
                dispatchPluginId = "mediatek.dispatch",
                supportedPrecisions = setOf(MediaTekPrecision.FLOAT32),
            ),
        )
        val errors = manifest.validate()

        assertTrue(errors.any { it.contains("semantic major.minor.patch") })
        assertTrue(errors.any { it.contains("compilerPluginId") })
        assertTrue(errors.any { it.contains("precision FLOAT16") })
    }

    @Test
    fun sdkMarkerAndValidManifestStillDoNotClaimNpuExecution() {
        val sdk = MediaTekSdkProbe(MediaTekRuntimeState.READY, "fixture", "fixture", emptyList(), "ok")
        val result = MediaTekNeuronPreflight.check(validManifest(), sdk)

        assertEquals(MediaTekRuntimeState.UNVERIFIED, result.state)
        assertFalse(result.evidence.provesNpuExecution)
    }

    internal fun validManifest(): MediaTekAnimaManifest {
        val encoder = graph(
            stage = MediaTekGraphStage.TEXT_ENCODER,
            file = "graphs/text_encoder.tflite",
            inputs = listOf(
                tensor("token_ids", "int32", "NT", 1, 512),
                tensor("attention_mask", "int32", "NT", 1, 512),
            ),
            outputs = listOf(tensor("embeddings", "float32", "NTC", 1, 512, 1024)),
        )
        val text = MediaTekAnimaTextEncoderContract(
            graphs = listOf(encoder),
            qwenTokenizer = tokenizer(
                sequenceLength = 512,
                padTokenId = 151643,
                weighting = MediaTekAnimaTokenWeighting.UNIFORM_ONE,
            ).copy(providerId = "mca.qwen3-tokenizer"),
            t5Tokenizer = tokenizer(
                sequenceLength = 512,
                padTokenId = 0,
                weighting = MediaTekAnimaTokenWeighting.PROMPT_ATTENTION,
            ).copy(providerId = "mca.anima-t5-tokenizer"),
            tokenIdsInput = "token_ids",
            attentionMaskInput = "attention_mask",
            embeddingsOutput = "embeddings",
        )
        val blocks = (0 until 28).map { index ->
            MediaTekAnimaBlockContract(
                index = index,
                graph = blockGraph("block-$index"),
                hiddenInput = "hidden_in",
                contextInput = "context",
                modulationInputs = listOf("mod0", "mod1", "mod2"),
                hiddenOutput = "hidden_out",
            )
        }
        val fourStepProfile = profile(4, sigma = listOf(1.0, 0.75, 0.5, 0.25, 0.0))
        val sixStepProfile = profile(6, sigma = listOf(1.0, 0.84, 0.67, 0.5, 0.33, 0.16, 0.0))
        val vae = graph(
            stage = MediaTekGraphStage.VAE,
            file = "graphs/vae.tflite",
            inputs = listOf(tensor("latent", "float32", "NCDHW", 1, 16, 1, 64, 64)),
            outputs = listOf(tensor("pixels", "float32", "NCDHW", 1, 3, 1, 512, 512)),
        )
        return MediaTekAnimaManifest(
            schema = MediaTekAnimaManifest.SCHEMA,
            family = MediaTekAnimaManifest.ANIMA_FAMILY,
            runtime = MediaTekAnimaManifest.RUNTIME,
            accelerator = MediaTekAnimaManifest.ACCELERATOR,
            compileMode = MediaTekCompileMode.ONLINE,
            sdkName = "neuron",
            sdkVersion = "9.0.11",
            runtimeRequirements = MediaTekRuntimeRequirements(
                minimumNeuronVersion = "9.0.0",
                minimumLiteRtVersion = "2.3.0",
                compilerPluginId = "mediatek.compiler",
                dispatchPluginId = "mediatek.dispatch",
                supportedPrecisions = setOf(MediaTekPrecision.FLOAT16, MediaTekPrecision.FLOAT32),
            ),
            textEncoder = text,
            sampling = MediaTekAnimaSamplingContract(
                scheduler = MediaTekAnimaScheduler.FLOW_MATCHING_EULER,
                cfgGuidanceFormula = MediaTekAnimaSamplingContract.CFG_FORMULA,
                eulerUpdateFormula = MediaTekAnimaSamplingContract.EULER_FORMULA,
                latentChannels = 16,
                latentDownsample = 8,
                patchSize = 2,
                patchifyPacking = MediaTekAnimaTensorPacking.LATENT_NCHW_TO_PATCH_TOKENS_C_SLOWEST,
                predictionPacking = MediaTekAnimaTensorPacking.PATCH_PREDICTION_C_FASTEST,
                contextAdapter = MediaTekAnimaContextAdapterContract(
                    graph = contextAdapterGraph(),
                    hiddenInput = "qwen_hidden",
                    qwenAttentionMaskInput = "qwen_mask",
                    t5TokenIdsInput = "t5_ids",
                    t5AttentionMaskInput = "t5_mask",
                    t5WeightsInput = "t5_weights",
                    adaptedContextOutput = "adapted_context",
                ),
                prelude = MediaTekAnimaPreludeContract(
                    graph = graph(
                        stage = MediaTekGraphStage.DIFFUSION,
                        file = "graphs/prelude.tflite",
                        inputs = listOf(tensor("patch_tokens", "float32", "NTC", 1, 1024, 68)),
                        outputs = listOf(tensor("hidden_out", "float32", "NTC", 1, 1024, 2048)),
                    ),
                    patchTokensInput = "patch_tokens",
                    hiddenOutput = "hidden_out",
                ),
                blocks = blocks,
                final = MediaTekAnimaFinalContract(
                    graph = graph(
                        stage = MediaTekGraphStage.DIFFUSION,
                        file = "graphs/final.tflite",
                        inputs = listOf(
                            tensor("hidden_in", "float32", "NTC", 1, 1024, 2048),
                            tensor("final_mod", "float32", "NC", 1, 4096),
                        ),
                        outputs = listOf(tensor("flow", "float32", "NTC", 1, 1024, 64)),
                    ),
                    hiddenInput = "hidden_in",
                    finalModulationInput = "final_mod",
                    flowPredictionOutput = "flow",
                ),
                vae = vae,
                vaeLatentInput = "latent",
                vaeExpansionAxis = 2,
                vaeImageOutput = "pixels",
                generationProfiles = listOf(fourStepProfile, sixStepProfile),
            ),
        )
    }

    private fun profile(steps: Int, sigma: List<Double>) = MediaTekAnimaGenerationProfile(
        id = "512-${steps}-step",
        minimumSteps = steps,
        maximumSteps = steps,
        defaultSteps = steps,
        sigmaSchedules = mapOf(steps to sigma),
        blockModulationsByStepCount = mapOf(
            steps to shapedAsset("assets/mods-$steps.f16", "float16", listOf(steps, 28, 3, 1, 6144)),
        ),
        finalModulationsByStepCount = mapOf(
            steps to shapedAsset("assets/mods-final-$steps.f16", "float16", listOf(steps, 1, 4096)),
        ),
        defaultCfgScale = 6f,
        minimumCfgScale = 1f,
        maximumCfgScale = 12f,
        widthPixels = 512,
        heightPixels = 512,
        latentInitialization = MediaTekAnimaLatentInitializationContract(
            distribution = MediaTekAnimaLatentDistribution.STANDARD_NORMAL,
            seedRequired = true,
            verifiedLatentAsset = null,
        ),
        numericGates = MediaTekAnimaNumericStage.entries.associateWith { gate() },
    )

    private fun gate() = MediaTekNumericDriftGate(
        minimumStd = 0.001,
        maximumStd = 10.0,
        maximumAbs = 1000.0,
        minimumOutputToInputStdRatio = 0.3,
        maximumOutputToInputStdRatio = 3.0,
    )

    private fun blockGraph(
        name: String,
        hiddenShape: List<Int> = listOf(1, 1024, 2048),
        outputShape: List<Int> = listOf(1, 1024, 2048),
    ) = graph(
        stage = MediaTekGraphStage.DIFFUSION,
        file = "graphs/$name.tflite",
        inputs = listOf(
            tensor("hidden_in", "float32", "NTC", *hiddenShape.toIntArray()),
            tensor("context", "float32", "NTC", 1, 512, 1024),
            tensor("mod0", "float32", "NC", 1, 6144),
            tensor("mod1", "float32", "NC", 1, 6144),
            tensor("mod2", "float32", "NC", 1, 6144),
        ),
        outputs = listOf(tensor("hidden_out", "float32", "NTC", *outputShape.toIntArray())),
    )

    private fun graph(
        stage: MediaTekGraphStage,
        file: String,
        inputs: List<MediaTekTensorContract>,
        outputs: List<MediaTekTensorContract>,
    ) = MediaTekGraphContract(
        stage = stage,
        file = file,
        sha256 = "a".repeat(64),
        sizeBytes = 1024,
        dtype = "float32",
        layout = "NTC",
        shape = listOf(1),
        inputs = inputs.map { it.name },
        outputs = outputs.map { it.name },
        delegate = "neuron",
        precision = MediaTekPrecision.FLOAT16,
        inputTensors = inputs,
        outputTensors = outputs,
    )

    private fun tokenizer(
        sequenceLength: Int,
        padTokenId: Int,
        weighting: MediaTekAnimaTokenWeighting,
    ) = MediaTekAnimaTokenizerContract(
        source = MediaTekTokenizerSource.RUNTIME_PROVIDER,
        asset = null,
        providerId = "mca.tokenizer",
        sequenceLength = sequenceLength,
        padTokenId = padTokenId,
        paddingPolicy = MediaTekAnimaTokenizerPaddingPolicy.RIGHT_PAD,
        paddingWeight = 0f,
        weighting = weighting,
        truncationPolicy = MediaTekAnimaTokenTruncationPolicy.TRUNCATE_TAIL,
    )

    private fun contextAdapterGraph(
        qwenSequenceLength: Int = 512,
        t5SequenceLength: Int = 512,
    ) = graph(
        stage = MediaTekGraphStage.DIFFUSION,
        file = "graphs/context_adapter.tflite",
        inputs = listOf(
            tensor("qwen_hidden", "float32", "NTC", 1, qwenSequenceLength, 1024),
            tensor("qwen_mask", "int32", "NT", 1, qwenSequenceLength),
            tensor("t5_ids", "int32", "NT", 1, t5SequenceLength),
            tensor("t5_mask", "int32", "NT", 1, t5SequenceLength),
            tensor("t5_weights", "float32", "NT", 1, t5SequenceLength),
        ),
        outputs = listOf(tensor("adapted_context", "float32", "NTC", 1, 512, 1024)),
    )

    private fun tensor(name: String, dtype: String, layout: String, vararg shape: Int) =
        MediaTekTensorContract(name, dtype, layout, shape.toList())

    private fun shapedAsset(file: String, dtype: String, shape: List<Int>) =
        MediaTekShapedAssetContract(asset(file), dtype, shape)

    private fun asset(file: String) = MediaTekBundleAssetContract(
        file = file,
        sha256 = "b".repeat(64),
        sizeBytes = 4096,
        mediaType = "application/octet-stream",
    )
}

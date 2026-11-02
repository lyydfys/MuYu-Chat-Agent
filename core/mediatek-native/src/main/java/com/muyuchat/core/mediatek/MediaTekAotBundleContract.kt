package com.muyuchat.core.mediatek

/**
 * Component contract for the NPUG/AOT transport (schema `mca.mediatek-image/4`).
 *
 * This schema describes Neuron compiled-network restores and NPUG graphs only.
 * It deliberately cannot describe a TFLite flatbuffer, so a v4 package never
 * reaches the LiteRT JIT executor or the GGUF/CPU path. The v3 JIT contract in
 * [MediaTekAnimaManifest] keeps its own meaning and remains parseable.
 */
enum class MediaTekNpugContainerMode {
    /** Topology plus metadata only; constants live outside the file and require a matching compiled network. */
    STUB_TOPOLOGY,

    /** Complete graph including constant weight data; may be compiled by the native Neuron API. */
    FULL_GRAPH,
}

data class MediaTekNpugTopologyContract(
    val asset: MediaTekBundleAssetContract,
    val containerMagic: String,
    val mode: MediaTekNpugContainerMode,
    val tensorCount: Int,
    val operationCount: Int,
    val weightDataBytes: Long,
) {
    fun validate(label: String): List<String> = buildList {
        addAll(asset.validate(label))
        if (!asset.file.endsWith(".npug", ignoreCase = true)) {
            add("$label: topology must be a .npug container")
        }
        if (containerMagic != CONTAINER_MAGIC) add("$label: containerMagic must be $CONTAINER_MAGIC")
        if (tensorCount <= 0) add("$label: tensorCount must be positive")
        if (operationCount <= 0) add("$label: operationCount must be positive")
        if (weightDataBytes < 0L) add("$label: weightDataBytes must not be negative")
        when (mode) {
            MediaTekNpugContainerMode.STUB_TOPOLOGY -> {
                if (weightDataBytes != 0L) add("$label: STUB_TOPOLOGY must not declare weight data")
            }
            MediaTekNpugContainerMode.FULL_GRAPH -> {
                if (weightDataBytes <= 0L) add("$label: FULL_GRAPH requires its constant weight data")
            }
        }
    }

    companion object {
        const val CONTAINER_MAGIC = "NPUG"
    }
}

data class MediaTekCompiledNetworkContract(
    val asset: MediaTekBundleAssetContract,
    val runtimeMajor: Int,
    val graphId: String,
) {
    fun validate(label: String): List<String> = buildList {
        addAll(asset.validate(label))
        if (!asset.file.endsWith(".dla", ignoreCase = true)) {
            add("$label: compiled network must be a .dla artifact")
        }
        if (runtimeMajor <= 0) add("$label: runtimeMajor must be positive")
        if (graphId.isBlank()) add("$label: graphId is empty")
    }
}

/** Pins the per-input/per-output NPUG route file; tensor details are parsed by [MediaTekNpugRouteJson]. */
data class MediaTekNpugRouteContract(
    val asset: MediaTekBundleAssetContract,
    val graphId: String,
    val inputCount: Int,
    val outputCount: Int,
    val inputEncoding: MediaTekNpugInputEncodingContract,
) {
    fun validate(label: String): List<String> = buildList {
        addAll(asset.validate(label))
        if (!asset.file.endsWith(".json", ignoreCase = true)) add("$label: route must be a .json file")
        if (graphId.isBlank()) add("$label: graphId is empty")
        if (inputCount <= 0) add("$label: inputCount must be positive")
        if (outputCount <= 0) add("$label: outputCount must be positive")
        addAll(inputEncoding.validate(label, inputCount))
    }
}

enum class MediaTekNpugTensorEncoding {
    FLOAT32,
    IEEE_HALF,

    /** Two's-complement int16 obtained with tensor scale/zeroPoint, not an IEEE half. */
    SCALED_SIGNED_INT16,
}

enum class MediaTekNpugRounding {
    HALF_AWAY_FROM_ZERO,
    HALF_TO_EVEN,
}

/** How float inputs are written into each NPUG input tensor. */
data class MediaTekNpugInputEncodingContract(
    val defaultEncoding: MediaTekNpugTensorEncoding,
    val overrides: Map<Int, MediaTekNpugTensorEncoding>,
    val rounding: MediaTekNpugRounding,
    val saturates: Boolean,
) {
    fun validate(label: String, inputCount: Int): List<String> = buildList {
        val invalid = overrides.keys.filter { it !in 0 until inputCount }
        if (invalid.isNotEmpty()) add("$label: encoding overrides reference missing route indexes ${invalid.sorted()}")
        val usesInt16 = defaultEncoding == MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16 ||
            overrides.values.any { it == MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16 }
        if (usesInt16 && !saturates) add("$label: SCALED_SIGNED_INT16 inputs must declare saturation")
    }
}


/** One executable arrangement of the same model. Selecting a variant never switches transport at runtime. */
data class MediaTekAotVariant(
    val id: String,
    val transport: MediaTekGraphTransport,
    val topology: MediaTekNpugTopologyContract,
    val compiledNetwork: MediaTekCompiledNetworkContract?,
    val route: MediaTekNpugRouteContract,
    val jitFallbackAllowed: Boolean = false,
) {
    fun validate(): List<String> = buildList {
        if (id.isBlank()) add("variant id is empty")
        if (transport != MediaTekGraphTransport.NEURON_COMPILED_NETWORK &&
            transport != MediaTekGraphTransport.NPUG_NATIVE
        ) {
            add("variant $id: transport ${transport.name} is not an NPUG/AOT transport")
        }
        addAll(topology.validate("variant $id topology"))
        compiledNetwork?.let { addAll(it.validate("variant $id compiled network")) }
        addAll(route.validate("variant $id route"))
        if (jitFallbackAllowed) {
            add("variant $id: NPUG/AOT variants must not allow a JIT fallback")
        }
        when (transport) {
            MediaTekGraphTransport.NEURON_COMPILED_NETWORK -> {
                if (topology.mode != MediaTekNpugContainerMode.STUB_TOPOLOGY) {
                    add("variant $id: NEURON_COMPILED_NETWORK requires STUB_TOPOLOGY")
                }
                if (compiledNetwork != null && compiledNetwork.graphId != route.graphId) {
                    add("variant $id: compiled network and route must describe the same graph")
                }
            }
            MediaTekGraphTransport.NPUG_NATIVE -> {
                if (topology.mode != MediaTekNpugContainerMode.FULL_GRAPH) {
                    add("variant $id: NPUG_NATIVE requires FULL_GRAPH")
                }
            }
            else -> Unit
        }
    }

    /**
     * Structural completeness problems that keep a syntactically valid variant
     * from running. A stub without its compiled network is the reference case:
     * it parses for inspection but must not be sent to JIT or filled with zeros.
     */
    fun executionBlockers(): List<String> = buildList {
        if (transport == MediaTekGraphTransport.NEURON_COMPILED_NETWORK && compiledNetwork == null) {
            add("variant $id: STUB_TOPOLOGY requires a matching compiled network (.dla); JIT fallback is not permitted")
        }
    }

    val executable: Boolean get() = validate().isEmpty() && executionBlockers().isEmpty()
}

enum class MediaTekAotConditioningMode {
    /** Prompts are tokenized and encoded inside the bundle. */
    DYNAMIC_TEXT,

    /** A precomputed ACTX context is the only conditioning; user prompts are not applied. */
    FIXED_CONTEXT,
}

data class MediaTekAotConditioningContract(
    val mode: MediaTekAotConditioningMode,
    val promptEffective: Boolean,
    val contextVersion: Int?,
    val contextAsset: MediaTekBundleAssetContract?,
    val contextDtype: String?,
    val contextShape: List<Int>,
    val textAssets: List<MediaTekBundleAssetContract>,
) {
    fun validate(): List<String> = buildList {
        when (mode) {
            MediaTekAotConditioningMode.DYNAMIC_TEXT -> {
                if (!promptEffective) add("DYNAMIC_TEXT must declare promptEffective=true")
                if (textAssets.isEmpty()) add("DYNAMIC_TEXT requires at least one text asset")
                if (contextAsset != null || contextVersion != null || contextDtype != null || contextShape.isNotEmpty()) {
                    add("DYNAMIC_TEXT must not declare a fixed context")
                }
                textAssets.forEachIndexed { index, asset -> addAll(asset.validate("text asset $index")) }
            }
            MediaTekAotConditioningMode.FIXED_CONTEXT -> {
                if (promptEffective) add("FIXED_CONTEXT must declare promptEffective=false")
                if (contextAsset == null) add("FIXED_CONTEXT requires an ACTX context asset")
                if (contextVersion != ACTX_VERSION) add("ACTX context version must be $ACTX_VERSION")
                if (!contextDtype.equals("float32", ignoreCase = true)) add("ACTX context dtype must be float32")
                if (contextShape != ACTX_SHAPE) add("ACTX v1 context shape must be $ACTX_SHAPE")
                if (textAssets.isNotEmpty()) add("FIXED_CONTEXT must not declare text assets")
                contextAsset?.let { addAll(it.validate("ACTX context")) }
            }
        }
        if (textAssets.map { it.file.replace('\\', '/') }.size != textAssets.map { it.file.replace('\\', '/') }.toSet().size) {
            add("duplicate text asset file")
        }
    }

    /** Fixed-context bundles cannot honour a prompt; callers must reject before execution. */
    fun promptRejections(prompt: String): List<String> =
        if (mode == MediaTekAotConditioningMode.FIXED_CONTEXT && prompt.isNotBlank()) {
            listOf("bundle uses a fixed ACTX context; prompts are not applied")
        } else {
            emptyList()
        }

    fun assets(): List<MediaTekBundleAssetContract> = textAssets + listOfNotNull(contextAsset)

    companion object {
        const val ACTX_VERSION = 1
        val ACTX_SHAPE = listOf(256, 1024)
    }
}

enum class MediaTekAotCfgMode {
    /** The native call receives no CFG input; only the declared default is meaningful. */
    NONE,

    /** The native call consumes CFG explicitly. */
    NATIVE,
}

data class MediaTekAotParameterContract(
    val supportsNegativePrompt: Boolean,
    val cfgMode: MediaTekAotCfgMode,
) {
    fun validate(): List<String> = buildList {
        if (supportsNegativePrompt && cfgMode != MediaTekAotCfgMode.NATIVE) {
            add("negative prompts require cfgMode NATIVE")
        }
    }
}

data class MediaTekAotModulationContract(
    val asset: MediaTekBundleAssetContract,
    val stepCount: Int,
    val blockCount: Int,
    val blockSegments: Int,
    val finalSegments: Int,
) {
    fun validate(label: String): List<String> = buildList {
        addAll(asset.validate(label))
        if (stepCount !in 1..64) add("$label: stepCount must be within 1..64")
        if (blockCount != MediaTekAotProfile.ANIMA_BLOCK_COUNT) {
            add("$label: blockCount must be ${MediaTekAotProfile.ANIMA_BLOCK_COUNT}")
        }
        if (blockSegments != MediaTekAotProfile.ANIMA_BLOCK_SEGMENTS) {
            add("$label: blockSegments must be ${MediaTekAotProfile.ANIMA_BLOCK_SEGMENTS}")
        }
        if (finalSegments != MediaTekAotProfile.ANIMA_FINAL_SEGMENTS) {
            add("$label: finalSegments must be ${MediaTekAotProfile.ANIMA_FINAL_SEGMENTS}")
        }
    }
}

enum class MediaTekAotVaeTransport {
    NEURON_COMPILED_NETWORK,
    NPUG_FULL_GRAPH,
}

data class MediaTekAotVaeTensorContract(
    val dims: List<Int>,
    val dtype: MediaTekNpugTensorEncoding,
    val layout: String,
    val scale: Double,
    val zeroPoint: Int,
) {
    fun validate(label: String): List<String> = buildList {
        if (dims.isEmpty() || dims.any { it <= 0 }) add("$label: dims must be positive")
        if (dtype != MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16) {
            add("$label: VAE tensors must be SCALED_SIGNED_INT16")
        }
        if (!layout.equals("NHWC", ignoreCase = true)) add("$label: VAE tensors must be NHWC")
        if (!scale.isFinite() || scale <= 0.0) add("$label: scale must be finite and positive")
        if (zeroPoint !in Short.MIN_VALUE..Short.MAX_VALUE) add("$label: zeroPoint is outside int16")
    }
}

/** The VAE runs outside the DiT chunk chain; its layout is NHWC, unlike the v3 NCDHW path. */
data class MediaTekAotVaeContract(
    val transport: MediaTekAotVaeTransport,
    val asset: MediaTekBundleAssetContract,
    val input: MediaTekAotVaeTensorContract,
    val output: MediaTekAotVaeTensorContract,
) {
    fun validate(label: String): List<String> = buildList {
        addAll(asset.validate(label))
        when (transport) {
            MediaTekAotVaeTransport.NEURON_COMPILED_NETWORK -> {
                if (!asset.file.endsWith(".dla", ignoreCase = true)) add("$label: compiled VAE must be a .dla")
            }
            MediaTekAotVaeTransport.NPUG_FULL_GRAPH -> {
                if (!asset.file.endsWith(".npug", ignoreCase = true)) add("$label: full-graph VAE must be a .npug")
            }
        }
        addAll(input.validate("$label input"))
        addAll(output.validate("$label output"))
    }
}

data class MediaTekAotProfile(
    val id: String,
    val widthPixels: Int,
    val heightPixels: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val tokenCount: Int,
    val supportedSteps: Set<Int>,
    val defaultSteps: Int,
    val defaultCfgScale: Float,
    val minimumCfgScale: Float,
    val maximumCfgScale: Float,
    val sigmaSchedule: MediaTekNpugSigmaSchedule,
    val modulations: List<MediaTekAotModulationContract>,
    val ropeAsset: MediaTekBundleAssetContract,
    val vae: MediaTekAotVaeContract,
) {
    fun validate(): List<String> = buildList {
        if (id.isBlank()) add("profile id is empty")
        if (gridWidth <= 0 || gridHeight <= 0) add("profile $id: grid must be positive")
        if (widthPixels != gridWidth * PIXELS_PER_GRID || heightPixels != gridHeight * PIXELS_PER_GRID) {
            add("profile $id: pixels must be $PIXELS_PER_GRID x grid")
        }
        if (widthPixels !in 256..1024 || heightPixels !in 256..1024) add("profile $id: resolution must be 256..1024 pixels")
        if (tokenCount != gridWidth * gridHeight) add("profile $id: tokenCount must equal gridWidth x gridHeight")
        if (supportedSteps.isEmpty()) add("profile $id: supportedSteps is empty")
        if (defaultSteps !in supportedSteps) add("profile $id: defaultSteps is not supported")
        if (modulations.map { it.stepCount }.toSet() != supportedSteps) {
            add("profile $id: modulation step counts must match supportedSteps")
        }
        if (modulations.map { it.stepCount }.size != modulations.size) add("profile $id: duplicate modulation step count")
        modulations.forEach { addAll(it.validate("profile $id modulation ${it.stepCount}")) }
        if (!minimumCfgScale.isFinite() || !maximumCfgScale.isFinite() ||
            minimumCfgScale < 0f || maximumCfgScale < minimumCfgScale
        ) {
            add("profile $id: CFG bounds must be finite, nonnegative, and ordered")
        }
        if (!defaultCfgScale.isFinite() || defaultCfgScale !in minimumCfgScale..maximumCfgScale) {
            add("profile $id: defaultCfgScale is outside the supported range")
        }
        addAll(ropeAsset.validate("profile $id RoPE"))
        if (!ropeAsset.file.endsWith(".bin", ignoreCase = true)) add("profile $id: RoPE asset must be a .bin file")
        addAll(vae.validate("profile $id VAE"))
        val expectedVaeInput = listOf(1, gridHeight * 2, gridWidth * 2, 16)
        if (vae.input.dims != expectedVaeInput) {
            add("profile $id: VAE input must be [1,${gridHeight * 2},${gridWidth * 2},16]")
        }
        val expectedVaeOutput = listOf(1, heightPixels, widthPixels, 3)
        if (vae.output.dims != expectedVaeOutput) {
            add("profile $id: VAE output must be [1,$heightPixels,$widthPixels,3]")
        }
    }

    fun supports(steps: Int): Boolean = steps in supportedSteps

    companion object {
        const val PIXELS_PER_GRID = 16
        const val ANIMA_BLOCK_COUNT = 28
        const val ANIMA_BLOCK_SEGMENTS = 9
        const val ANIMA_FINAL_SEGMENTS = 2
    }
}

data class MediaTekAotModelIdentity(
    val architecture: String,
    val modelSha256: String,
) {
    fun validate(): List<String> = buildList {
        if (architecture != MediaTekAotManifest.ANIMA_FAMILY) {
            add("modelIdentity architecture must be ${MediaTekAotManifest.ANIMA_FAMILY}")
        }
        if (!modelSha256.matches(SHA256_PATTERN)) add("modelIdentity modelSha256 is invalid")
    }

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}

data class MediaTekAotManifest(
    val schema: String,
    val family: String,
    val runtime: String,
    val modelIdentity: MediaTekAotModelIdentity,
    val variants: List<MediaTekAotVariant>,
    val conditioning: MediaTekAotConditioningContract,
    val parameters: MediaTekAotParameterContract,
    val profiles: List<MediaTekAotProfile>,
) {
    fun validate(): List<String> = buildList {
        if (schema != SCHEMA) add("schema must be $SCHEMA")
        if (family != ANIMA_FAMILY) add("family must be $ANIMA_FAMILY")
        if (runtime != RUNTIME) add("runtime must be $RUNTIME")
        addAll(modelIdentity.validate())
        if (variants.isEmpty()) add("manifest has no execution variants")
        if (variants.map { it.id }.size != variants.map { it.id }.toSet().size) add("duplicate execution variant id")
        variants.forEach { addAll(it.validate()) }
        addAll(conditioning.validate())
        addAll(parameters.validate())
        if (profiles.isEmpty()) add("manifest has no generation profiles")
        if (profiles.map { it.id }.size != profiles.map { it.id }.toSet().size) add("duplicate generation profile id")
        profiles.forEach { addAll(it.validate()) }
        val byFile = assets().groupBy { it.file.replace('\\', '/') }
        byFile.forEach { (file, group) ->
            if (group.map { it.sizeBytes to it.sha256.lowercase() }.toSet().size != 1) {
                add("asset '$file' is pinned with conflicting size/SHA-256 values")
            }
        }
    }

    fun assets(): List<MediaTekBundleAssetContract> = buildList {
        variants.forEach { variant ->
            add(variant.topology.asset)
            variant.compiledNetwork?.let { add(it.asset) }
            add(variant.route.asset)
        }
        addAll(conditioning.assets())
        profiles.forEach { profile ->
            profile.modulations.forEach { add(it.asset) }
            add(profile.ropeAsset)
            add(profile.vae.asset)
        }
    }

    fun profile(id: String): MediaTekAotProfile? = profiles.firstOrNull { it.id == id }

    fun executionBlockers(): List<String> =
        variants.flatMap { variant -> variant.executionBlockers().map { "${variant.id}: $it" } }

    /**
     * After a compiled-network restore fails, only a fully materialized NPUG
     * graph may be attempted. Stub topology has no CPU/TFLite equivalent, so an
     * empty result is a hard stop rather than a JIT fallback.
     */
    fun recoveryVariantsAfterCompiledNetworkFailure(): List<MediaTekAotVariant> =
        variants.filter { variant ->
            variant.transport == MediaTekGraphTransport.NPUG_NATIVE &&
                variant.topology.mode == MediaTekNpugContainerMode.FULL_GRAPH &&
                variant.validate().isEmpty()
        }

    /** Parameter capability is negotiated before execution; unsupported fields never reach native. */
    fun requestRejections(
        profileId: String,
        prompt: String,
        negativePrompt: String,
        cfgScale: Float?,
        steps: Int?,
    ): List<String> = buildList {
        val selected = profile(profileId)
        if (selected == null) {
            add("unsupported profile '$profileId'")
            return@buildList
        }
        conditioning.promptRejections(prompt).forEach { add("unsupported prompt: $it") }
        if (negativePrompt.isNotBlank() && !parameters.supportsNegativePrompt) {
            add("unsupported negativePrompt: this bundle's native call does not consume a negative branch")
        }
        if (steps != null && !selected.supports(steps)) add("unsupported steps: $steps")
        if (cfgScale != null) {
            if (!cfgScale.isFinite() || cfgScale !in selected.minimumCfgScale..selected.maximumCfgScale) {
                add("unsupported cfgScale: $cfgScale")
            } else if (parameters.cfgMode == MediaTekAotCfgMode.NONE && cfgScale != selected.defaultCfgScale) {
                add("unsupported cfgScale: this bundle only honours the default ${selected.defaultCfgScale}")
            }
        }
    }

    companion object {
        const val SCHEMA = "mca.mediatek-image/4"
        const val ANIMA_FAMILY = "ANIMA"
        const val RUNTIME = "MEDIATEK_NEURON"
    }
}

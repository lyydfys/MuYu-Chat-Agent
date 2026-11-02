package com.muyuchat.core.mediatek

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Strict JSON boundary for the versioned Anima bundle contract. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object MediaTekAnimaManifestJson {
    private val json = Json {
        allowSpecialFloatingPointValues = false
        allowTrailingComma = false
        coerceInputValues = false
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parse(json: String): MediaTekAnimaManifest {
        val root = try {
            this.json.parseToJsonElement(json)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("manifest: malformed JSON", error)
        }
        val manifest = root.parseManifest("manifest")
        val errors = manifest.validate()
        require(errors.isEmpty()) { "manifest: contract validation failed: ${errors.joinToString("; ")}" }
        return manifest
    }

    fun encode(manifest: MediaTekAnimaManifest): String {
        val errors = manifest.validate()
        require(errors.isEmpty()) { "manifest: contract validation failed: ${errors.joinToString("; ")}" }
        return json.encodeToString(JsonElement.serializer(), manifest.toJsonElement())
    }

    private fun JsonElement.parseManifest(path: String) = objectAt(path, "schema", "family", "runtime", "accelerator", "compileMode", "sdkName", "sdkVersion", "runtimeRequirements", "textEncoder", "sampling", "outputMime") { value ->
        MediaTekAnimaManifest(
            schema = value.string("schema", path),
            family = value.string("family", path),
            runtime = value.string("runtime", path),
            accelerator = value.string("accelerator", path),
            compileMode = value.enum("compileMode", path),
            sdkName = value.string("sdkName", path),
            sdkVersion = value.string("sdkVersion", path),
            runtimeRequirements = value.required("runtimeRequirements", path).parseRuntimeRequirements("$path.runtimeRequirements"),
            textEncoder = value.required("textEncoder", path).parseTextEncoder("$path.textEncoder"),
            sampling = value.required("sampling", path).parseSampling("$path.sampling"),
            outputMime = value.string("outputMime", path),
        )
    }

    private fun JsonElement.parseRuntimeRequirements(path: String) = objectAt(path, "minimumNeuronVersion", "minimumLiteRtVersion", "compilerPluginId", "dispatchPluginId", "supportedPrecisions") { value ->
        MediaTekRuntimeRequirements(
            minimumNeuronVersion = value.string("minimumNeuronVersion", path),
            minimumLiteRtVersion = value.string("minimumLiteRtVersion", path),
            compilerPluginId = value.string("compilerPluginId", path),
            dispatchPluginId = value.string("dispatchPluginId", path),
            supportedPrecisions = value.required("supportedPrecisions", path).enumSet("$path.supportedPrecisions"),
        )
    }

    private fun JsonElement.parseTextEncoder(path: String) = objectAt(path, "graphs", "qwenTokenizer", "t5Tokenizer", "tokenIdsInput", "attentionMaskInput", "embeddingsOutput", "conditioningBranches", "conditioningAssets") { value ->
        MediaTekAnimaTextEncoderContract(
            graphs = value.required("graphs", path).list("$path.graphs") { element, itemPath -> element.parseGraph(itemPath) },
            qwenTokenizer = value.required("qwenTokenizer", path).parseTokenizer("$path.qwenTokenizer"),
            t5Tokenizer = value.required("t5Tokenizer", path).parseTokenizer("$path.t5Tokenizer"),
            tokenIdsInput = value.string("tokenIdsInput", path),
            attentionMaskInput = value.string("attentionMaskInput", path),
            embeddingsOutput = value.string("embeddingsOutput", path),
            conditioningBranches = value.required("conditioningBranches", path).enumSet("$path.conditioningBranches"),
            conditioningAssets = value.required("conditioningAssets", path).list("$path.conditioningAssets") { element, itemPath -> element.parseConditioningAsset(itemPath) },
        )
    }

    private fun JsonElement.parseTokenizer(path: String) = objectAt(path, "source", "asset", "providerId", "sequenceLength", "padTokenId", "paddingPolicy", "paddingWeight", "weighting", "truncationPolicy") { value ->
        MediaTekAnimaTokenizerContract(
            source = value.enum("source", path),
            asset = value.nullable("asset", path) { element, itemPath -> element.parseAsset(itemPath) },
            providerId = value.nullableString("providerId", path),
            sequenceLength = value.int("sequenceLength", path),
            padTokenId = value.int("padTokenId", path),
            paddingPolicy = value.enum("paddingPolicy", path),
            paddingWeight = value.float("paddingWeight", path),
            weighting = value.enum("weighting", path),
            truncationPolicy = value.enum("truncationPolicy", path),
        )
    }

    private fun JsonElement.parseConditioningAsset(path: String) = objectAt(path, "asset", "consumerGraphFile", "consumerInput") { value ->
        MediaTekConditioningAssetContract(
            asset = value.required("asset", path).parseAsset("$path.asset"),
            consumerGraphFile = value.string("consumerGraphFile", path),
            consumerInput = value.string("consumerInput", path),
        )
    }

    private fun JsonElement.parseAsset(path: String) = objectAt(path, "file", "sha256", "sizeBytes", "mediaType") { value ->
        MediaTekBundleAssetContract(
            file = value.string("file", path),
            sha256 = value.string("sha256", path),
            sizeBytes = value.long("sizeBytes", path),
            mediaType = value.string("mediaType", path),
        )
    }

    private fun JsonElement.parseGraph(path: String) = objectAt(path, "stage", "file", "sha256", "sizeBytes", "dtype", "layout", "shape", "inputs", "outputs", "delegate", "precision", "inputTensors", "outputTensors") { value ->
        MediaTekGraphContract(
            stage = value.enum("stage", path),
            file = value.string("file", path),
            sha256 = value.string("sha256", path),
            sizeBytes = value.long("sizeBytes", path),
            dtype = value.string("dtype", path),
            layout = value.string("layout", path),
            shape = value.required("shape", path).intList("$path.shape"),
            inputs = value.required("inputs", path).stringList("$path.inputs"),
            outputs = value.required("outputs", path).stringList("$path.outputs"),
            delegate = value.string("delegate", path),
            precision = value.enum("precision", path),
            inputTensors = value.required("inputTensors", path).list("$path.inputTensors") { element, itemPath -> element.parseTensor(itemPath) },
            outputTensors = value.required("outputTensors", path).list("$path.outputTensors") { element, itemPath -> element.parseTensor(itemPath) },
        )
    }

    private fun JsonElement.parseTensor(path: String) = objectAt(path, "name", "dtype", "layout", "shape") { value ->
        MediaTekTensorContract(
            name = value.string("name", path),
            dtype = value.string("dtype", path),
            layout = value.string("layout", path),
            shape = value.required("shape", path).intList("$path.shape"),
        )
    }

    private fun JsonElement.parseSampling(path: String) = objectAt(path, "scheduler", "cfgGuidanceFormula", "eulerUpdateFormula", "latentChannels", "latentDownsample", "patchSize", "patchifyPacking", "predictionPacking", "contextAdapter", "prelude", "blocks", "final", "vae", "vaeLatentInput", "vaeExpansionAxis", "vaeImageOutput", "generationProfiles") { value ->
        MediaTekAnimaSamplingContract(
            scheduler = value.enum("scheduler", path),
            cfgGuidanceFormula = value.string("cfgGuidanceFormula", path),
            eulerUpdateFormula = value.string("eulerUpdateFormula", path),
            latentChannels = value.int("latentChannels", path),
            latentDownsample = value.int("latentDownsample", path),
            patchSize = value.int("patchSize", path),
            patchifyPacking = value.enum("patchifyPacking", path),
            predictionPacking = value.enum("predictionPacking", path),
            contextAdapter = value.required("contextAdapter", path).parseContextAdapter("$path.contextAdapter"),
            prelude = value.required("prelude", path).parsePrelude("$path.prelude"),
            blocks = value.required("blocks", path).list("$path.blocks") { element, itemPath -> element.parseBlock(itemPath) },
            final = value.required("final", path).parseFinal("$path.final"),
            vae = value.required("vae", path).parseGraph("$path.vae"),
            vaeLatentInput = value.string("vaeLatentInput", path),
            vaeExpansionAxis = value.int("vaeExpansionAxis", path),
            vaeImageOutput = value.string("vaeImageOutput", path),
            generationProfiles = value.required("generationProfiles", path).list("$path.generationProfiles") { element, itemPath -> element.parseProfile(itemPath) },
        )
    }

    private fun JsonElement.parseContextAdapter(path: String) = objectAt(path, "graph", "hiddenInput", "qwenAttentionMaskInput", "t5TokenIdsInput", "t5AttentionMaskInput", "t5WeightsInput", "adaptedContextOutput", "outputSequenceLength", "paddingPolicy", "truncationPolicy") { value ->
        MediaTekAnimaContextAdapterContract(
            graph = value.required("graph", path).parseGraph("$path.graph"),
            hiddenInput = value.string("hiddenInput", path),
            qwenAttentionMaskInput = value.string("qwenAttentionMaskInput", path),
            t5TokenIdsInput = value.string("t5TokenIdsInput", path),
            t5AttentionMaskInput = value.string("t5AttentionMaskInput", path),
            t5WeightsInput = value.string("t5WeightsInput", path),
            adaptedContextOutput = value.string("adaptedContextOutput", path),
            outputSequenceLength = value.int("outputSequenceLength", path),
            paddingPolicy = value.enum("paddingPolicy", path),
            truncationPolicy = value.enum("truncationPolicy", path),
        )
    }

    private fun JsonElement.parsePrelude(path: String) = objectAt(path, "graph", "patchTokensInput", "hiddenOutput") { value ->
        MediaTekAnimaPreludeContract(
            graph = value.required("graph", path).parseGraph("$path.graph"),
            patchTokensInput = value.string("patchTokensInput", path),
            hiddenOutput = value.string("hiddenOutput", path),
        )
    }

    private fun JsonElement.parseBlock(path: String) = objectAt(path, "index", "graph", "hiddenInput", "contextInput", "modulationInputs", "hiddenOutput") { value ->
        MediaTekAnimaBlockContract(
            index = value.int("index", path),
            graph = value.required("graph", path).parseGraph("$path.graph"),
            hiddenInput = value.string("hiddenInput", path),
            contextInput = value.string("contextInput", path),
            modulationInputs = value.required("modulationInputs", path).stringList("$path.modulationInputs"),
            hiddenOutput = value.string("hiddenOutput", path),
        )
    }

    private fun JsonElement.parseFinal(path: String) = objectAt(path, "graph", "hiddenInput", "finalModulationInput", "flowPredictionOutput") { value ->
        MediaTekAnimaFinalContract(
            graph = value.required("graph", path).parseGraph("$path.graph"),
            hiddenInput = value.string("hiddenInput", path),
            finalModulationInput = value.string("finalModulationInput", path),
            flowPredictionOutput = value.string("flowPredictionOutput", path),
        )
    }

    private fun JsonElement.parseProfile(path: String) = objectAt(path, "id", "minimumSteps", "maximumSteps", "defaultSteps", "sigmaSchedules", "blockModulationsByStepCount", "finalModulationsByStepCount", "defaultCfgScale", "minimumCfgScale", "maximumCfgScale", "widthPixels", "heightPixels", "latentInitialization", "numericGates") { value ->
        MediaTekAnimaGenerationProfile(
            id = value.string("id", path),
            minimumSteps = value.int("minimumSteps", path),
            maximumSteps = value.int("maximumSteps", path),
            defaultSteps = value.int("defaultSteps", path),
            sigmaSchedules = value.required("sigmaSchedules", path).intKeyedMap("$path.sigmaSchedules") { element, itemPath -> element.doubleList(itemPath) },
            blockModulationsByStepCount = value.required("blockModulationsByStepCount", path).intKeyedMap("$path.blockModulationsByStepCount") { element, itemPath -> element.parseShapedAsset(itemPath) },
            finalModulationsByStepCount = value.required("finalModulationsByStepCount", path).intKeyedMap("$path.finalModulationsByStepCount") { element, itemPath -> element.parseShapedAsset(itemPath) },
            defaultCfgScale = value.float("defaultCfgScale", path),
            minimumCfgScale = value.float("minimumCfgScale", path),
            maximumCfgScale = value.float("maximumCfgScale", path),
            widthPixels = value.int("widthPixels", path),
            heightPixels = value.int("heightPixels", path),
            latentInitialization = value.required("latentInitialization", path).parseLatentInitialization("$path.latentInitialization"),
            numericGates = value.required("numericGates", path).enumKeyedMap("$path.numericGates") { element, itemPath -> element.parseNumericGate(itemPath) },
        )
    }

    private fun JsonElement.parseLatentInitialization(path: String) = objectAt(path, "distribution", "seedRequired", "verifiedLatentAsset") { value ->
        MediaTekAnimaLatentInitializationContract(
            distribution = value.enum("distribution", path),
            seedRequired = value.boolean("seedRequired", path),
            verifiedLatentAsset = value.nullable("verifiedLatentAsset", path) { element, itemPath -> element.parseShapedAsset(itemPath) },
        )
    }

    private fun JsonElement.parseShapedAsset(path: String) = objectAt(path, "asset", "dtype", "shape") { value ->
        MediaTekShapedAssetContract(
            asset = value.required("asset", path).parseAsset("$path.asset"),
            dtype = value.string("dtype", path),
            shape = value.required("shape", path).intList("$path.shape"),
        )
    }

    private fun JsonElement.parseNumericGate(path: String) = objectAt(path, "minimumStd", "maximumStd", "maximumAbs", "minimumOutputToInputStdRatio", "maximumOutputToInputStdRatio", "maximumNonFiniteElements") { value ->
        MediaTekNumericDriftGate(
            minimumStd = value.double("minimumStd", path),
            maximumStd = value.double("maximumStd", path),
            maximumAbs = value.double("maximumAbs", path),
            minimumOutputToInputStdRatio = value.nullable("minimumOutputToInputStdRatio", path) { element, itemPath -> element.doubleAt(itemPath) },
            maximumOutputToInputStdRatio = value.nullable("maximumOutputToInputStdRatio", path) { element, itemPath -> element.doubleAt(itemPath) },
            maximumNonFiniteElements = value.long("maximumNonFiniteElements", path),
        )
    }

    private inline fun <T> JsonElement.objectAt(path: String, vararg fields: String, block: (JsonObject) -> T): T {
        val value = this as? JsonObject ?: fail(path, "must be an object")
        value.requireFields(path, fields.toSet())
        return block(value)
    }

    private fun JsonObject.requireFields(path: String, expected: Set<String>) {
        val unknown = keys - expected
        if (unknown.isNotEmpty()) fail(path, "unknown fields ${unknown.sorted().joinToString()}")
        val missing = expected - keys
        if (missing.isNotEmpty()) fail(path, "missing fields ${missing.sorted().joinToString()}")
    }

    private fun JsonObject.required(name: String, path: String): JsonElement =
        this[name] ?: fail("$path.$name", "field is required")

    private fun JsonObject.string(name: String, path: String) = required(name, path).stringAt("$path.$name")
    private fun JsonObject.int(name: String, path: String) = required(name, path).intAt("$path.$name")
    private fun JsonObject.long(name: String, path: String) = required(name, path).longAt("$path.$name")
    private fun JsonObject.float(name: String, path: String) = required(name, path).floatAt("$path.$name")
    private fun JsonObject.double(name: String, path: String) = required(name, path).doubleAt("$path.$name")
    private fun JsonObject.boolean(name: String, path: String) = required(name, path).booleanAt("$path.$name")

    private fun JsonObject.nullableString(name: String, path: String): String? = when (val value = required(name, path)) {
        JsonNull -> null
        else -> value.stringAt("$path.$name")
    }

    private inline fun <T> JsonObject.nullable(name: String, path: String, parse: (JsonElement, String) -> T): T? =
        when (val value = required(name, path)) {
            JsonNull -> null
            else -> parse(value, "$path.$name")
        }

    private inline fun <reified E : Enum<E>> JsonObject.enum(name: String, path: String): E =
        required(name, path).enumAt("$path.$name")

    private inline fun <reified E : Enum<E>> JsonElement.enumAt(path: String): E {
        val name = stringAt(path)
        return enumValues<E>().firstOrNull { it.name == name } ?: fail(path, "unknown ${E::class.simpleName} value '$name'")
    }

    private inline fun <reified E : Enum<E>> JsonElement.enumSet(path: String): Set<E> {
        val values = list(path) { element, itemPath -> element.enumAt<E>(itemPath) }
        if (values.size != values.toSet().size) fail(path, "contains duplicate enum values")
        return values.toSet()
    }

    private inline fun <T> JsonElement.list(path: String, parse: (JsonElement, String) -> T): List<T> {
        val values = this as? JsonArray ?: fail(path, "must be an array")
        return values.mapIndexed { index, element -> parse(element, "$path[$index]") }
    }

    private fun JsonElement.stringList(path: String) = list(path) { element, itemPath -> element.stringAt(itemPath) }
    private fun JsonElement.intList(path: String) = list(path) { element, itemPath -> element.intAt(itemPath) }
    private fun JsonElement.doubleList(path: String) = list(path) { element, itemPath -> element.doubleAt(itemPath) }

    private inline fun <T> JsonElement.intKeyedMap(path: String, parse: (JsonElement, String) -> T): Map<Int, T> {
        val values = this as? JsonObject ?: fail(path, "must be an object keyed by integer step counts")
        val result = linkedMapOf<Int, T>()
        values.forEach { (key, element) ->
            val intKey = key.toIntOrNull() ?: fail("$path.$key", "key must be an integer")
            if (intKey.toString() != key) fail("$path.$key", "key must use canonical integer syntax")
            result[intKey] = parse(element, "$path.$key")
        }
        return result
    }

    private inline fun <reified E : Enum<E>, T> JsonElement.enumKeyedMap(path: String, parse: (JsonElement, String) -> T): Map<E, T> {
        val values = this as? JsonObject ?: fail(path, "must be an object keyed by enum names")
        val result = linkedMapOf<E, T>()
        values.forEach { (key, element) ->
            val enumKey = enumValues<E>().firstOrNull { it.name == key } ?: fail("$path.$key", "unknown ${E::class.simpleName} key '$key'")
            result[enumKey] = parse(element, "$path.$key")
        }
        return result
    }

    private fun JsonElement.stringAt(path: String): String {
        val value = this as? JsonPrimitive ?: fail(path, "must be a string")
        if (!value.isString) fail(path, "must be a string")
        return value.content
    }

    private fun JsonElement.booleanAt(path: String): Boolean {
        val value = this as? JsonPrimitive ?: fail(path, "must be a boolean")
        if (value.isString || value.booleanOrNull == null) fail(path, "must be a boolean")
        return value.content.toBoolean()
    }

    private fun JsonElement.intAt(path: String): Int {
        val value = this as? JsonPrimitive ?: fail(path, "must be an integer")
        if (value.isString) fail(path, "must be an integer")
        return value.intOrNull ?: fail(path, "must be an in-range integer")
    }

    private fun JsonElement.longAt(path: String): Long {
        val value = this as? JsonPrimitive ?: fail(path, "must be an integer")
        if (value.isString) fail(path, "must be an integer")
        return value.longOrNull ?: fail(path, "must be an in-range integer")
    }

    private fun JsonElement.floatAt(path: String): Float {
        val value = this as? JsonPrimitive ?: fail(path, "must be a number")
        if (value.isString) fail(path, "must be a number")
        val number = value.floatOrNull ?: fail(path, "must be a finite number")
        if (!number.isFinite()) fail(path, "must be a finite number")
        return number
    }

    private fun JsonElement.doubleAt(path: String): Double {
        val value = this as? JsonPrimitive ?: fail(path, "must be a number")
        if (value.isString) fail(path, "must be a number")
        val number = value.doubleOrNull ?: fail(path, "must be a finite number")
        if (!number.isFinite()) fail(path, "must be a finite number")
        return number
    }

    private fun fail(path: String, message: String): Nothing =
        throw IllegalArgumentException("$path: $message")

    private fun MediaTekAnimaManifest.toJsonElement(): JsonElement = jsonObject(
        "schema" to stringJson(schema),
        "family" to stringJson(family),
        "runtime" to stringJson(runtime),
        "accelerator" to stringJson(accelerator),
        "compileMode" to enumJson(compileMode),
        "sdkName" to stringJson(sdkName),
        "sdkVersion" to stringJson(sdkVersion),
        "runtimeRequirements" to runtimeRequirements.toJsonElement(),
        "textEncoder" to textEncoder.toJsonElement(),
        "sampling" to sampling.toJsonElement(),
        "outputMime" to stringJson(outputMime),
    )

    private fun MediaTekRuntimeRequirements.toJsonElement(): JsonElement = jsonObject(
        "minimumNeuronVersion" to stringJson(minimumNeuronVersion),
        "minimumLiteRtVersion" to stringJson(minimumLiteRtVersion),
        "compilerPluginId" to stringJson(compilerPluginId),
        "dispatchPluginId" to stringJson(dispatchPluginId),
        "supportedPrecisions" to enumSetJson(supportedPrecisions),
    )

    private fun MediaTekAnimaTextEncoderContract.toJsonElement(): JsonElement = jsonObject(
        "graphs" to arrayJson(graphs.map { it.toJsonElement() }),
        "qwenTokenizer" to qwenTokenizer.toJsonElement(),
        "t5Tokenizer" to t5Tokenizer.toJsonElement(),
        "tokenIdsInput" to stringJson(tokenIdsInput),
        "attentionMaskInput" to stringJson(attentionMaskInput),
        "embeddingsOutput" to stringJson(embeddingsOutput),
        "conditioningBranches" to enumSetJson(conditioningBranches),
        "conditioningAssets" to arrayJson(conditioningAssets.map { it.toJsonElement() }),
    )

    private fun MediaTekAnimaTokenizerContract.toJsonElement(): JsonElement = jsonObject(
        "source" to enumJson(source),
        "asset" to nullableJson(asset) { it.toJsonElement() },
        "providerId" to nullableJson(providerId, ::stringJson),
        "sequenceLength" to intJson(sequenceLength),
        "padTokenId" to intJson(padTokenId),
        "paddingPolicy" to enumJson(paddingPolicy),
        "paddingWeight" to floatJson(paddingWeight),
        "weighting" to enumJson(weighting),
        "truncationPolicy" to enumJson(truncationPolicy),
    )

    private fun MediaTekConditioningAssetContract.toJsonElement(): JsonElement = jsonObject(
        "asset" to asset.toJsonElement(),
        "consumerGraphFile" to stringJson(consumerGraphFile),
        "consumerInput" to stringJson(consumerInput),
    )

    private fun MediaTekBundleAssetContract.toJsonElement(): JsonElement = jsonObject(
        "file" to stringJson(file),
        "sha256" to stringJson(sha256),
        "sizeBytes" to longJson(sizeBytes),
        "mediaType" to stringJson(mediaType),
    )

    private fun MediaTekGraphContract.toJsonElement(): JsonElement = jsonObject(
        "stage" to enumJson(stage),
        "file" to stringJson(file),
        "sha256" to stringJson(sha256),
        "sizeBytes" to longJson(sizeBytes),
        "dtype" to stringJson(dtype),
        "layout" to stringJson(layout),
        "shape" to intArrayJson(shape),
        "inputs" to stringArrayJson(inputs),
        "outputs" to stringArrayJson(outputs),
        "delegate" to stringJson(delegate),
        "precision" to enumJson(precision),
        "inputTensors" to arrayJson(inputTensors.map { it.toJsonElement() }),
        "outputTensors" to arrayJson(outputTensors.map { it.toJsonElement() }),
    )

    private fun MediaTekTensorContract.toJsonElement(): JsonElement = jsonObject(
        "name" to stringJson(name),
        "dtype" to stringJson(dtype),
        "layout" to stringJson(layout),
        "shape" to intArrayJson(shape),
    )

    private fun MediaTekAnimaSamplingContract.toJsonElement(): JsonElement = jsonObject(
        "scheduler" to enumJson(scheduler),
        "cfgGuidanceFormula" to stringJson(cfgGuidanceFormula),
        "eulerUpdateFormula" to stringJson(eulerUpdateFormula),
        "latentChannels" to intJson(latentChannels),
        "latentDownsample" to intJson(latentDownsample),
        "patchSize" to intJson(patchSize),
        "patchifyPacking" to enumJson(patchifyPacking),
        "predictionPacking" to enumJson(predictionPacking),
        "contextAdapter" to contextAdapter.toJsonElement(),
        "prelude" to prelude.toJsonElement(),
        "blocks" to arrayJson(blocks.map { it.toJsonElement() }),
        "final" to final.toJsonElement(),
        "vae" to vae.toJsonElement(),
        "vaeLatentInput" to stringJson(vaeLatentInput),
        "vaeExpansionAxis" to intJson(vaeExpansionAxis),
        "vaeImageOutput" to stringJson(vaeImageOutput),
        "generationProfiles" to arrayJson(generationProfiles.map { it.toJsonElement() }),
    )

    private fun MediaTekAnimaContextAdapterContract.toJsonElement(): JsonElement = jsonObject(
        "graph" to graph.toJsonElement(),
        "hiddenInput" to stringJson(hiddenInput),
        "qwenAttentionMaskInput" to stringJson(qwenAttentionMaskInput),
        "t5TokenIdsInput" to stringJson(t5TokenIdsInput),
        "t5AttentionMaskInput" to stringJson(t5AttentionMaskInput),
        "t5WeightsInput" to stringJson(t5WeightsInput),
        "adaptedContextOutput" to stringJson(adaptedContextOutput),
        "outputSequenceLength" to intJson(outputSequenceLength),
        "paddingPolicy" to enumJson(paddingPolicy),
        "truncationPolicy" to enumJson(truncationPolicy),
    )

    private fun MediaTekAnimaPreludeContract.toJsonElement(): JsonElement = jsonObject(
        "graph" to graph.toJsonElement(),
        "patchTokensInput" to stringJson(patchTokensInput),
        "hiddenOutput" to stringJson(hiddenOutput),
    )

    private fun MediaTekAnimaBlockContract.toJsonElement(): JsonElement = jsonObject(
        "index" to intJson(index),
        "graph" to graph.toJsonElement(),
        "hiddenInput" to stringJson(hiddenInput),
        "contextInput" to stringJson(contextInput),
        "modulationInputs" to stringArrayJson(modulationInputs),
        "hiddenOutput" to stringJson(hiddenOutput),
    )

    private fun MediaTekAnimaFinalContract.toJsonElement(): JsonElement = jsonObject(
        "graph" to graph.toJsonElement(),
        "hiddenInput" to stringJson(hiddenInput),
        "finalModulationInput" to stringJson(finalModulationInput),
        "flowPredictionOutput" to stringJson(flowPredictionOutput),
    )

    private fun MediaTekAnimaGenerationProfile.toJsonElement(): JsonElement = jsonObject(
        "id" to stringJson(id),
        "minimumSteps" to intJson(minimumSteps),
        "maximumSteps" to intJson(maximumSteps),
        "defaultSteps" to intJson(defaultSteps),
        "sigmaSchedules" to intMapJson(sigmaSchedules) { doubleArrayJson(it) },
        "blockModulationsByStepCount" to intMapJson(blockModulationsByStepCount) { it.toJsonElement() },
        "finalModulationsByStepCount" to intMapJson(finalModulationsByStepCount) { it.toJsonElement() },
        "defaultCfgScale" to floatJson(defaultCfgScale),
        "minimumCfgScale" to floatJson(minimumCfgScale),
        "maximumCfgScale" to floatJson(maximumCfgScale),
        "widthPixels" to intJson(widthPixels),
        "heightPixels" to intJson(heightPixels),
        "latentInitialization" to latentInitialization.toJsonElement(),
        "numericGates" to enumMapJson(numericGates) { it.toJsonElement() },
    )

    private fun MediaTekAnimaLatentInitializationContract.toJsonElement(): JsonElement = jsonObject(
        "distribution" to enumJson(distribution),
        "seedRequired" to booleanJson(seedRequired),
        "verifiedLatentAsset" to nullableJson(verifiedLatentAsset) { it.toJsonElement() },
    )

    private fun MediaTekShapedAssetContract.toJsonElement(): JsonElement = jsonObject(
        "asset" to asset.toJsonElement(),
        "dtype" to stringJson(dtype),
        "shape" to intArrayJson(shape),
    )

    private fun MediaTekNumericDriftGate.toJsonElement(): JsonElement = jsonObject(
        "minimumStd" to doubleJson(minimumStd),
        "maximumStd" to doubleJson(maximumStd),
        "maximumAbs" to doubleJson(maximumAbs),
        "minimumOutputToInputStdRatio" to nullableJson(minimumOutputToInputStdRatio, ::doubleJson),
        "maximumOutputToInputStdRatio" to nullableJson(maximumOutputToInputStdRatio, ::doubleJson),
        "maximumNonFiniteElements" to longJson(maximumNonFiniteElements),
    )

    private fun jsonObject(vararg fields: Pair<String, JsonElement>): JsonObject =
        buildJsonObject { fields.forEach { (name, value) -> put(name, value) } }

    private fun arrayJson(elements: Iterable<JsonElement>): JsonArray =
        buildJsonArray { elements.forEach(::add) }

    private fun stringJson(value: String) = JsonPrimitive(value)
    private fun booleanJson(value: Boolean) = JsonPrimitive(value)
    private fun intJson(value: Int) = JsonPrimitive(value)
    private fun longJson(value: Long) = JsonPrimitive(value)
    private fun floatJson(value: Float) = JsonPrimitive(value)
    private fun doubleJson(value: Double) = JsonPrimitive(value)
    private fun enumJson(value: Enum<*>) = stringJson(value.name)

    private fun stringArrayJson(values: Iterable<String>) = arrayJson(values.map(::stringJson))
    private fun intArrayJson(values: Iterable<Int>) = arrayJson(values.map(::intJson))
    private fun doubleArrayJson(values: Iterable<Double>) = arrayJson(values.map(::doubleJson))

    private inline fun <reified E : Enum<E>> enumSetJson(values: Set<E>): JsonArray =
        arrayJson(enumValues<E>().filter { it in values }.map(::enumJson))

    private inline fun <V> intMapJson(values: Map<Int, V>, encode: (V) -> JsonElement): JsonObject =
        buildJsonObject { values.toSortedMap().forEach { (key, value) -> put(key.toString(), encode(value)) } }

    private inline fun <reified E : Enum<E>, V> enumMapJson(values: Map<E, V>, encode: (V) -> JsonElement): JsonObject =
        buildJsonObject {
            enumValues<E>().forEach { key -> values[key]?.let { put(key.name, encode(it)) } }
        }

    private inline fun <T> nullableJson(value: T?, encode: (T) -> JsonElement): JsonElement =
        value?.let(encode) ?: JsonNull
}

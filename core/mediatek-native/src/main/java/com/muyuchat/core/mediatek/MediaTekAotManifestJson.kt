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

/** Strict JSON boundary for the `mca.mediatek-image/4` NPUG/AOT component contract. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object MediaTekAotManifestJson {
    private val json = Json {
        allowSpecialFloatingPointValues = false
        allowTrailingComma = false
        coerceInputValues = false
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parse(json: String): MediaTekAotManifest {
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

    fun encode(manifest: MediaTekAotManifest): String {
        val errors = manifest.validate()
        require(errors.isEmpty()) { "manifest: contract validation failed: ${errors.joinToString("; ")}" }
        return json.encodeToString(JsonElement.serializer(), manifest.toJsonElement())
    }

    private fun JsonElement.parseManifest(path: String) = objectAt(
        path, "schema", "family", "runtime", "modelIdentity", "executionVariants", "conditioning", "parameters", "profiles",
    ) { value ->
        MediaTekAotManifest(
            schema = value.string("schema", path),
            family = value.string("family", path),
            runtime = value.string("runtime", path),
            modelIdentity = value.required("modelIdentity", path).parseModelIdentity("$path.modelIdentity"),
            variants = value.required("executionVariants", path).list("$path.executionVariants") { element, itemPath ->
                element.parseVariant(itemPath)
            },
            conditioning = value.required("conditioning", path).parseConditioning("$path.conditioning"),
            parameters = value.required("parameters", path).parseParameters("$path.parameters"),
            profiles = value.required("profiles", path).list("$path.profiles") { element, itemPath ->
                element.parseProfile(itemPath)
            },
        )
    }

    private fun JsonElement.parseModelIdentity(path: String) = objectAt(path, "architecture", "modelSha256") { value ->
        MediaTekAotModelIdentity(
            architecture = value.string("architecture", path),
            modelSha256 = value.string("modelSha256", path),
        )
    }

    private fun JsonElement.parseVariant(path: String) = objectAt(
        path, "id", "transport", "topology", "compiledNetwork", "route", "jitFallbackAllowed",
    ) { value ->
        MediaTekAotVariant(
            id = value.string("id", path),
            transport = value.enum("transport", path),
            topology = value.required("topology", path).parseTopology("$path.topology"),
            compiledNetwork = value.nullable("compiledNetwork", path) { element, itemPath ->
                element.parseCompiledNetwork(itemPath)
            },
            route = value.required("route", path).parseRoute("$path.route"),
            jitFallbackAllowed = value.boolean("jitFallbackAllowed", path),
        )
    }

    private fun JsonElement.parseTopology(path: String) = objectAt(
        path, "asset", "containerMagic", "mode", "tensorCount", "operationCount", "weightDataBytes",
    ) { value ->
        MediaTekNpugTopologyContract(
            asset = value.required("asset", path).parseAsset("$path.asset"),
            containerMagic = value.string("containerMagic", path),
            mode = value.enum("mode", path),
            tensorCount = value.int("tensorCount", path),
            operationCount = value.int("operationCount", path),
            weightDataBytes = value.long("weightDataBytes", path),
        )
    }

    private fun JsonElement.parseCompiledNetwork(path: String) = objectAt(
        path, "asset", "runtimeMajor", "graphId",
    ) { value ->
        MediaTekCompiledNetworkContract(
            asset = value.required("asset", path).parseAsset("$path.asset"),
            runtimeMajor = value.int("runtimeMajor", path),
            graphId = value.string("graphId", path),
        )
    }

    private fun JsonElement.parseRoute(path: String) = objectAt(
        path, "asset", "graphId", "inputCount", "outputCount", "inputEncoding",
    ) { value ->
        MediaTekNpugRouteContract(
            asset = value.required("asset", path).parseAsset("$path.asset"),
            graphId = value.string("graphId", path),
            inputCount = value.int("inputCount", path),
            outputCount = value.int("outputCount", path),
            inputEncoding = value.required("inputEncoding", path).parseInputEncoding("$path.inputEncoding"),
        )
    }

    private fun JsonElement.parseInputEncoding(path: String) = objectAt(
        path, "defaultEncoding", "overrides", "rounding", "saturates",
    ) { value ->
        MediaTekNpugInputEncodingContract(
            defaultEncoding = value.enum("defaultEncoding", path),
            overrides = value.required("overrides", path).intKeyedMap("$path.overrides") { element, itemPath ->
                element.enumAt(itemPath)
            },
            rounding = value.enum("rounding", path),
            saturates = value.boolean("saturates", path),
        )
    }

    private fun JsonElement.parseConditioning(path: String) = objectAt(
        path, "mode", "promptEffective", "contextVersion", "contextAsset", "contextDtype", "contextShape", "textAssets",
    ) { value ->
        MediaTekAotConditioningContract(
            mode = value.enum("mode", path),
            promptEffective = value.boolean("promptEffective", path),
            contextVersion = value.nullableInt("contextVersion", path),
            contextAsset = value.nullable("contextAsset", path) { element, itemPath -> element.parseAsset(itemPath) },
            contextDtype = value.nullableString("contextDtype", path),
            contextShape = value.required("contextShape", path).intList("$path.contextShape"),
            textAssets = value.required("textAssets", path).list("$path.textAssets") { element, itemPath ->
                element.parseAsset(itemPath)
            },
        )
    }

    private fun JsonElement.parseParameters(path: String) = objectAt(path, "supportsNegativePrompt", "cfgMode") { value ->
        MediaTekAotParameterContract(
            supportsNegativePrompt = value.boolean("supportsNegativePrompt", path),
            cfgMode = value.enum("cfgMode", path),
        )
    }

    private fun JsonElement.parseProfile(path: String) = objectAt(
        path, "id", "widthPixels", "heightPixels", "gridWidth", "gridHeight", "tokenCount",
        "supportedSteps", "defaultSteps", "defaultCfgScale", "minimumCfgScale", "maximumCfgScale",
        "sigmaSchedule", "modulations", "ropeAsset", "vae",
    ) { value ->
        MediaTekAotProfile(
            id = value.string("id", path),
            widthPixels = value.int("widthPixels", path),
            heightPixels = value.int("heightPixels", path),
            gridWidth = value.int("gridWidth", path),
            gridHeight = value.int("gridHeight", path),
            tokenCount = value.int("tokenCount", path),
            supportedSteps = value.required("supportedSteps", path).intSet("$path.supportedSteps"),
            defaultSteps = value.int("defaultSteps", path),
            defaultCfgScale = value.float("defaultCfgScale", path),
            minimumCfgScale = value.float("minimumCfgScale", path),
            maximumCfgScale = value.float("maximumCfgScale", path),
            sigmaSchedule = value.enum("sigmaSchedule", path),
            modulations = value.required("modulations", path).list("$path.modulations") { element, itemPath ->
                element.parseModulation(itemPath)
            },
            ropeAsset = value.required("ropeAsset", path).parseAsset("$path.ropeAsset"),
            vae = value.required("vae", path).parseVae("$path.vae"),
        )
    }

    private fun JsonElement.parseVae(path: String) = objectAt(path, "transport", "asset", "input", "output") { value ->
        MediaTekAotVaeContract(
            transport = value.enum("transport", path),
            asset = value.required("asset", path).parseAsset("$path.asset"),
            input = value.required("input", path).parseVaeTensor("$path.input"),
            output = value.required("output", path).parseVaeTensor("$path.output"),
        )
    }

    private fun JsonElement.parseVaeTensor(path: String) = objectAt(path, "dims", "dtype", "layout", "scale", "zeroPoint") { value ->
        MediaTekAotVaeTensorContract(
            dims = value.required("dims", path).intList("$path.dims"),
            dtype = value.enum("dtype", path),
            layout = value.string("layout", path),
            scale = value.double("scale", path),
            zeroPoint = value.int("zeroPoint", path),
        )
    }

    private fun JsonElement.parseModulation(path: String) = objectAt(
        path, "asset", "stepCount", "blockCount", "blockSegments", "finalSegments",
    ) { value ->
        MediaTekAotModulationContract(
            asset = value.required("asset", path).parseAsset("$path.asset"),
            stepCount = value.int("stepCount", path),
            blockCount = value.int("blockCount", path),
            blockSegments = value.int("blockSegments", path),
            finalSegments = value.int("finalSegments", path),
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

    private fun JsonObject.nullableInt(name: String, path: String): Int? = when (val value = required(name, path)) {
        JsonNull -> null
        else -> value.intAt("$path.$name")
    }

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

    private inline fun <T> JsonElement.list(path: String, parse: (JsonElement, String) -> T): List<T> {
        val values = this as? JsonArray ?: fail(path, "must be an array")
        return values.mapIndexed { index, element -> parse(element, "$path[$index]") }
    }

    private fun JsonElement.intList(path: String) = list(path) { element, itemPath -> element.intAt(itemPath) }

    private fun JsonElement.intSet(path: String): Set<Int> {
        val values = intList(path)
        if (values.size != values.toSet().size) fail(path, "contains duplicate values")
        return values.toSet()
    }

    private inline fun <T> JsonElement.intKeyedMap(path: String, parse: (JsonElement, String) -> T): Map<Int, T> {
        val values = this as? JsonObject ?: fail(path, "must be an object keyed by integer indexes")
        val result = linkedMapOf<Int, T>()
        values.forEach { (key, element) ->
            val intKey = key.toIntOrNull() ?: fail("$path.$key", "key must be an integer")
            if (intKey.toString() != key) fail("$path.$key", "key must use canonical integer syntax")
            result[intKey] = parse(element, "$path.$key")
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

    private fun MediaTekAotManifest.toJsonElement(): JsonElement = jsonObject(
        "schema" to stringJson(schema),
        "family" to stringJson(family),
        "runtime" to stringJson(runtime),
        "modelIdentity" to modelIdentity.toJsonElement(),
        "executionVariants" to arrayJson(variants.map { it.toJsonElement() }),
        "conditioning" to conditioning.toJsonElement(),
        "parameters" to parameters.toJsonElement(),
        "profiles" to arrayJson(profiles.map { it.toJsonElement() }),
    )

    private fun MediaTekAotModelIdentity.toJsonElement(): JsonElement = jsonObject(
        "architecture" to stringJson(architecture),
        "modelSha256" to stringJson(modelSha256),
    )

    private fun MediaTekAotVariant.toJsonElement(): JsonElement = jsonObject(
        "id" to stringJson(id),
        "transport" to enumJson(transport),
        "topology" to topology.toJsonElement(),
        "compiledNetwork" to nullableJson(compiledNetwork) { it.toJsonElement() },
        "route" to route.toJsonElement(),
        "jitFallbackAllowed" to booleanJson(jitFallbackAllowed),
    )

    private fun MediaTekNpugTopologyContract.toJsonElement(): JsonElement = jsonObject(
        "asset" to asset.toJsonElement(),
        "containerMagic" to stringJson(containerMagic),
        "mode" to enumJson(mode),
        "tensorCount" to intJson(tensorCount),
        "operationCount" to intJson(operationCount),
        "weightDataBytes" to longJson(weightDataBytes),
    )

    private fun MediaTekCompiledNetworkContract.toJsonElement(): JsonElement = jsonObject(
        "asset" to asset.toJsonElement(),
        "runtimeMajor" to intJson(runtimeMajor),
        "graphId" to stringJson(graphId),
    )

    private fun MediaTekNpugRouteContract.toJsonElement(): JsonElement = jsonObject(
        "asset" to asset.toJsonElement(),
        "graphId" to stringJson(graphId),
        "inputCount" to intJson(inputCount),
        "outputCount" to intJson(outputCount),
        "inputEncoding" to inputEncoding.toJsonElement(),
    )

    private fun MediaTekNpugInputEncodingContract.toJsonElement(): JsonElement = jsonObject(
        "defaultEncoding" to enumJson(defaultEncoding),
        "overrides" to intMapJson(overrides) { enumJson(it) },
        "rounding" to enumJson(rounding),
        "saturates" to booleanJson(saturates),
    )

    private fun MediaTekAotConditioningContract.toJsonElement(): JsonElement = jsonObject(
        "mode" to enumJson(mode),
        "promptEffective" to booleanJson(promptEffective),
        "contextVersion" to nullableJson(contextVersion, ::intJson),
        "contextAsset" to nullableJson(contextAsset) { it.toJsonElement() },
        "contextDtype" to nullableJson(contextDtype, ::stringJson),
        "contextShape" to intArrayJson(contextShape),
        "textAssets" to arrayJson(textAssets.map { it.toJsonElement() }),
    )

    private fun MediaTekAotParameterContract.toJsonElement(): JsonElement = jsonObject(
        "supportsNegativePrompt" to booleanJson(supportsNegativePrompt),
        "cfgMode" to enumJson(cfgMode),
    )

    private fun MediaTekAotProfile.toJsonElement(): JsonElement = jsonObject(
        "id" to stringJson(id),
        "widthPixels" to intJson(widthPixels),
        "heightPixels" to intJson(heightPixels),
        "gridWidth" to intJson(gridWidth),
        "gridHeight" to intJson(gridHeight),
        "tokenCount" to intJson(tokenCount),
        "supportedSteps" to intArrayJson(supportedSteps),
        "defaultSteps" to intJson(defaultSteps),
        "defaultCfgScale" to floatJson(defaultCfgScale),
        "minimumCfgScale" to floatJson(minimumCfgScale),
        "maximumCfgScale" to floatJson(maximumCfgScale),
        "sigmaSchedule" to enumJson(sigmaSchedule),
        "modulations" to arrayJson(modulations.map { it.toJsonElement() }),
        "ropeAsset" to ropeAsset.toJsonElement(),
        "vae" to vae.toJsonElement(),
    )

    private fun MediaTekAotVaeContract.toJsonElement(): JsonElement = jsonObject(
        "transport" to enumJson(transport),
        "asset" to asset.toJsonElement(),
        "input" to input.toJsonElement(),
        "output" to output.toJsonElement(),
    )

    private fun MediaTekAotVaeTensorContract.toJsonElement(): JsonElement = jsonObject(
        "dims" to intArrayJson(dims),
        "dtype" to enumJson(dtype),
        "layout" to stringJson(layout),
        "scale" to doubleJson(scale),
        "zeroPoint" to intJson(zeroPoint),
    )

    private fun MediaTekAotModulationContract.toJsonElement(): JsonElement = jsonObject(
        "asset" to asset.toJsonElement(),
        "stepCount" to intJson(stepCount),
        "blockCount" to intJson(blockCount),
        "blockSegments" to intJson(blockSegments),
        "finalSegments" to intJson(finalSegments),
    )

    private fun MediaTekBundleAssetContract.toJsonElement(): JsonElement = jsonObject(
        "file" to stringJson(file),
        "sha256" to stringJson(sha256),
        "sizeBytes" to longJson(sizeBytes),
        "mediaType" to stringJson(mediaType),
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

    private fun intArrayJson(values: Iterable<Int>) = arrayJson(values.map(::intJson))

    private inline fun <T> nullableJson(value: T?, encode: (T) -> JsonElement): JsonElement =
        value?.let(encode) ?: JsonNull

    private inline fun <V> intMapJson(values: Map<Int, V>, encode: (V) -> JsonElement): JsonObject =
        buildJsonObject { values.toSortedMap().forEach { (key, value) -> put(key.toString(), encode(value)) } }
}

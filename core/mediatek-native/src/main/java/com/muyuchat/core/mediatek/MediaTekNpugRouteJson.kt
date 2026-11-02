package com.muyuchat.core.mediatek

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One NPUG graph input/output entry. `scale`/`zeroPoint` are the tensor's
 * symmetric quantization parameters; they are not optional metadata.
 */
data class MediaTekNpugRouteTensor(
    val routeIndex: Int,
    val tensorId: Int,
    val rank: Int,
    val dims: List<Int>,
    val quantTag: Int,
    val scale: Double,
    val zeroPoint: Int,
    val dataOffset: Long,
    val dataSize: Long,
)

data class MediaTekNpugRouteRef(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

data class MediaTekNpugRouteInputGroup(
    val shapeKey: String,
    val count: Int,
    val routeIndexes: List<Int>,
    val quantTags: List<Int>,
    val distinctScales: List<Double>,
    val note: String?,
)

data class MediaTekNpugRoute(
    val graph: String,
    val stub: MediaTekNpugRouteRef,
    val dla: MediaTekNpugRouteRef?,
    val tensorCount: Int,
    val operationCount: Int,
    val inputs: List<MediaTekNpugRouteTensor>,
    val outputs: List<MediaTekNpugRouteTensor>,
    val inputShapeGroups: List<MediaTekNpugRouteInputGroup>,
) {
    fun validate(): List<String> = buildList {
        if (graph.isBlank()) add("route graph is empty")
        if (tensorCount <= 0) add("route tensorCount must be positive")
        if (operationCount <= 0) add("route operationCount must be positive")
        addAll(stub.validate("route stub"))
        dla?.let { addAll(it.validate("route compiled network")) }
        if (inputs.isEmpty()) add("route has no inputs")
        if (outputs.isEmpty()) add("route has no outputs")
        addAll(inputs.validateTensors("input"))
        addAll(outputs.validateTensors("output"))
        val ids = (inputs + outputs).map { it.tensorId }
        if (ids.size != ids.toSet().size) add("route tensor ids must be unique across inputs and outputs")
        addAll(validateShapeGroups())
    }

    private fun MediaTekNpugRouteTensor.shapeKey(): String =
        dims.joinToString(separator = ",", prefix = "[", postfix = "]")

    private fun validateShapeGroups(): List<String> = buildList {
        val declaredKeys = inputShapeGroups.map { it.shapeKey }
        if (declaredKeys.size != declaredKeys.toSet().size) add("route inputShapeGroups declare a duplicate shapeKey")
        val observed = inputs.groupingBy { it.shapeKey() }.eachCount()
        val declared = inputShapeGroups.associate { it.shapeKey to it.count }
        if (declared != observed) add("route inputShapeGroups do not match the listed inputs")
        val covered = inputShapeGroups.flatMap { it.routeIndexes }
        if (covered.sorted() != inputs.indices.toList()) add("route inputShapeGroups must cover every input exactly once")
        inputShapeGroups.forEach { group ->
            val label = "route inputShapeGroup ${group.shapeKey}"
            if (group.count <= 0) add("$label: count must be positive")
            if (group.routeIndexes.size != group.count) add("$label: count must match routeIndexes")
            if (group.quantTags.isEmpty()) add("$label: quantTags is empty")
            val referenced = group.routeIndexes.mapNotNull { inputs.getOrNull(it) }
            if (referenced.size != group.routeIndexes.size) add("$label: references missing inputs")
            if (referenced.any { it.shapeKey() != group.shapeKey }) add("$label: references an input with another shape")
            if (group.quantTags.toSet() != referenced.map { it.quantTag }.toSet()) add("$label: quantTags do not match inputs")
            val groupScales = referenced.map { it.scale }.toSet()
            if (group.distinctScales.toSet() != groupScales) add("$label: distinctScales do not match inputs")
            if (group.distinctScales.any { !it.isFinite() || it <= 0.0 }) add("$label: distinctScales must be finite and positive")
        }
    }

    /** Every declared manifest pin must match the route file byte-for-byte identity. */
    fun verifyAgainst(
        contract: MediaTekNpugRouteContract,
        topology: MediaTekNpugTopologyContract,
        compiledNetwork: MediaTekCompiledNetworkContract?,
    ): List<String> = buildList {
        if (graph != contract.graphId) add("route graph '$graph' != manifest graph '${contract.graphId}'")
        if (inputs.size != contract.inputCount) add("route has ${inputs.size} inputs; manifest declares ${contract.inputCount}")
        if (outputs.size != contract.outputCount) add("route has ${outputs.size} outputs; manifest declares ${contract.outputCount}")
        if (tensorCount != topology.tensorCount) add("route tensorCount $tensorCount != topology ${topology.tensorCount}")
        if (operationCount != topology.operationCount) add("route operationCount $operationCount != topology ${topology.operationCount}")
        if (!stub.matches(topology.asset)) add("route stub identity does not match the manifest topology asset")
        if (compiledNetwork == null) {
            if (dla != null) add("route references a compiled network that the manifest does not install")
        } else {
            if (dla == null) add("route does not reference the manifest compiled network")
            else if (!dla.matches(compiledNetwork.asset)) add("route compiled-network identity does not match the manifest asset")
        }
        val int16Indexes = buildSet {
            contract.inputEncoding.overrides.forEach { (index, encoding) ->
                if (encoding == MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16) add(index)
            }
            if (contract.inputEncoding.defaultEncoding == MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16) {
                addAll(inputs.indices)
            }
        }
        int16Indexes.forEach { index ->
            val tensor = inputs.getOrNull(index) ?: return@forEach
            if (tensor.zeroPoint !in Short.MIN_VALUE..Short.MAX_VALUE) add("input $index: zeroPoint is outside int16")
        }
    }

    private fun List<MediaTekNpugRouteTensor>.validateTensors(kind: String): List<String> {
        val tensors = this
        return buildList {
            val indexes = tensors.map { it.routeIndex }
            if (indexes != tensors.indices.toList()) add("route $kind indexes must be contiguous from zero")
            tensors.forEach { tensor ->
                val label = "route $kind ${tensor.routeIndex}"
                if (tensor.tensorId < 0) add("$label: tensorId must be nonnegative")
                if (tensor.rank !in 1..8) add("$label: rank must be within 1..8")
                if (tensor.dims.size != tensor.rank) add("$label: dims count ${tensor.dims.size} != rank ${tensor.rank}")
                if (tensor.dims.any { it <= 0 }) add("$label: dims must be positive")
                if (tensor.quantTag !in 0..1) add("$label: quantTag must be 0 or 1")
                if (!tensor.scale.isFinite() || tensor.scale <= 0.0) add("$label: scale must be finite and positive")
                if (tensor.dataOffset < 0L || tensor.dataSize < 0L) add("$label: data range must not be negative")
            }
        }
    }

    private fun MediaTekNpugRouteRef.validate(label: String): List<String> = buildList {
        if (path.isBlank()) add("$label path is empty")
        if (bytes <= 0L) add("$label bytes must be positive")
        if (!sha256.matches(SHA256_PATTERN)) add("$label sha256 is invalid")
    }

    private fun MediaTekNpugRouteRef.matches(asset: MediaTekBundleAssetContract): Boolean =
        bytes == asset.sizeBytes &&
            sha256.equals(asset.sha256, ignoreCase = true) &&
            path.replace('\\', '/').substringAfterLast('/') == asset.file.replace('\\', '/').substringAfterLast('/')

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}

/** Strict parser for the `model-npug-route` JSON generated by the S0 route exporter. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object MediaTekNpugRouteJson {
    private val json = Json {
        allowSpecialFloatingPointValues = false
        allowTrailingComma = false
        coerceInputValues = false
        isLenient = false
    }

    fun parse(text: String): MediaTekNpugRoute {
        val root = try {
            json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("route: malformed JSON", error)
        }
        val route = root.strictObject(
            "route",
            setOf(
                "graph", "stub", "dla", "schemaFile", "schemaSHA256", "tensorCount", "opCount",
                "inputCount", "outputCount", "inputs", "outputs", "inputShapeGroups", "checks", "checksAllPass",
            ),
        ) { value ->
            val inputs = value.required("inputs", "route").list("route.inputs") { element, itemPath ->
                element.parseTensor(itemPath)
            }
            val outputs = value.required("outputs", "route").list("route.outputs") { element, itemPath ->
                element.parseTensor(itemPath)
            }
            val declaredInputs = value.int("inputCount", "route")
            val declaredOutputs = value.int("outputCount", "route")
            if (inputs.size != declaredInputs) {
                fail("route.inputCount", "declares $declaredInputs but lists ${inputs.size}")
            }
            if (outputs.size != declaredOutputs) {
                fail("route.outputCount", "declares $declaredOutputs but lists ${outputs.size}")
            }
            val groups = value.required("inputShapeGroups", "route").list("route.inputShapeGroups") { element, itemPath ->
                element.parseShapeGroup(itemPath)
            }
            val checks = value.required("checks", "route").booleanMap("route.checks")
            if (checks.isEmpty() || checks.values.any { !it }) {
                fail("route.checks", "must be a nonempty set of passing checks")
            }
            if (!value.boolean("checksAllPass", "route")) fail("route.checksAllPass", "must be true")
            if (!value.string("schemaSHA256", "route").matches(SHA256_PATTERN)) {
                fail("route.schemaSHA256", "must be a SHA-256")
            }
            MediaTekNpugRoute(
                graph = value.string("graph", "route"),
                stub = value.required("stub", "route").parseRouteRef("route.stub"),
                dla = value.nullable("dla", "route") { element, itemPath -> element.parseRouteRef(itemPath) },
                tensorCount = value.int("tensorCount", "route"),
                operationCount = value.int("opCount", "route"),
                inputs = inputs,
                outputs = outputs,
                inputShapeGroups = groups,
            )
        }
        val errors = route.validate()
        require(errors.isEmpty()) { "route: validation failed: ${errors.joinToString("; ")}" }
        return route
    }

    private fun JsonElement.parseRouteRef(path: String) = strictObject(path, setOf("path", "bytes", "sha256")) { value ->
        MediaTekNpugRouteRef(
            path = value.string("path", path),
            bytes = value.long("bytes", path),
            sha256 = value.string("sha256", path),
        )
    }

    private fun JsonElement.parseShapeGroup(path: String) = strictObject(
        path,
        setOf("shapeKey", "count", "routeIndexes", "quantTags", "distinctScales", "note"),
    ) { value ->
        MediaTekNpugRouteInputGroup(
            shapeKey = value.string("shapeKey", path),
            count = value.int("count", path),
            routeIndexes = value.required("routeIndexes", path).intList("$path.routeIndexes"),
            quantTags = value.required("quantTags", path).intList("$path.quantTags"),
            distinctScales = value.required("distinctScales", path).doubleList("$path.distinctScales"),
            note = value.nullableString("note", path),
        )
    }

    private fun JsonElement.parseTensor(path: String) = strictObject(
        path,
        setOf(
            "routeIndex", "tensorId", "rank", "dims", "shapeKey", "field0", "field1",
            "quantTag", "quant", "dataOffset", "dataSize", "bytesIfFP16", "bytesIfFP32",
        ),
    ) { value ->
        val dims = value.required("dims", path).intList("$path.dims")
        val rank = value.int("rank", path)
        if (dims.size != rank) fail("$path.dims", "count ${dims.size} != rank $rank")
        val shapeKey = dims.joinToString(separator = ",", prefix = "[", postfix = "]")
        if (value.string("shapeKey", path) != shapeKey) fail("$path.shapeKey", "does not match dims")
        val elements = dims.fold(1L) { product, dim -> product * dim }
        if (value.long("bytesIfFP16", path) != elements * 2L) fail("$path.bytesIfFP16", "does not match dims")
        if (value.long("bytesIfFP32", path) != elements * 4L) fail("$path.bytesIfFP32", "does not match dims")
        val quant = value.required("quant", path).strictObject("$path.quant", setOf("scale", "zeroPoint")) { quantValue ->
            quantValue.double("scale", "$path.quant") to quantValue.int("zeroPoint", "$path.quant")
        }
        MediaTekNpugRouteTensor(
            routeIndex = value.int("routeIndex", path),
            tensorId = value.int("tensorId", path),
            rank = rank,
            dims = dims,
            quantTag = value.int("quantTag", path),
            scale = quant.first,
            zeroPoint = quant.second,
            dataOffset = value.long("dataOffset", path),
            dataSize = value.long("dataSize", path),
        )
    }

    private inline fun <T> JsonElement.strictObject(path: String, expected: Set<String>, block: (JsonObject) -> T): T {
        val value = this as? JsonObject ?: fail(path, "must be an object")
        val unknown = value.keys - expected
        if (unknown.isNotEmpty()) fail(path, "unknown fields ${unknown.sorted().joinToString()}")
        val missing = expected - value.keys
        if (missing.isNotEmpty()) fail(path, "missing fields ${missing.sorted().joinToString()}")
        return block(value)
    }

    private fun JsonObject.required(name: String, path: String): JsonElement =
        this[name] ?: fail("$path.$name", "field is required")

    private fun JsonObject.string(name: String, path: String) = required(name, path).stringAt("$path.$name")
    private fun JsonObject.int(name: String, path: String) = required(name, path).intAt("$path.$name")
    private fun JsonObject.long(name: String, path: String) = required(name, path).longAt("$path.$name")
    private fun JsonObject.double(name: String, path: String) = required(name, path).doubleAt("$path.$name")
    private fun JsonObject.boolean(name: String, path: String) = required(name, path).booleanAt("$path.$name")

    private inline fun <T> JsonObject.nullable(name: String, path: String, parse: (JsonElement, String) -> T): T? =
        when (val value = required(name, path)) {
            JsonNull -> null
            else -> parse(value, "$path.$name")
        }

    private fun JsonObject.nullableString(name: String, path: String): String? =
        when (val value = required(name, path)) {
            JsonNull -> null
            else -> value.stringAt("$path.$name")
        }

    private inline fun <T> JsonElement.list(path: String, parse: (JsonElement, String) -> T): List<T> {
        val values = this as? JsonArray ?: fail(path, "must be an array")
        return values.mapIndexed { index, element -> parse(element, "$path[$index]") }
    }

    private fun JsonElement.intList(path: String) = list(path) { element, itemPath -> element.intAt(itemPath) }
    private fun JsonElement.doubleList(path: String) = list(path) { element, itemPath -> element.doubleAt(itemPath) }

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

    private fun JsonElement.booleanMap(path: String): Map<String, Boolean> {
        val values = this as? JsonObject ?: fail(path, "must be an object of booleans")
        return values.mapValues { (key, element) -> element.booleanAt("$path.$key") }
    }

    private fun MediaTekNpugRouteTensor.shapeKey(): String =
        dims.joinToString(separator = ",", prefix = "[", postfix = "]")

    private fun JsonElement.stringAt(path: String): String {
        val value = this as? JsonPrimitive ?: fail(path, "must be a string")
        if (!value.isString) fail(path, "must be a string")
        return value.content
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

    private fun JsonElement.doubleAt(path: String): Double {
        val value = this as? JsonPrimitive ?: fail(path, "must be a number")
        if (value.isString) fail(path, "must be a number")
        val number = value.doubleOrNull ?: fail(path, "must be a finite number")
        if (!number.isFinite()) fail(path, "must be a finite number")
        return number
    }

    private fun JsonElement.booleanAt(path: String): Boolean {
        val value = this as? JsonPrimitive ?: fail(path, "must be a boolean")
        if (value.isString || value.booleanOrNull == null) fail(path, "must be a boolean")
        return value.content.toBoolean()
    }

    private fun fail(path: String, message: String): Nothing =
        throw IllegalArgumentException("$path: $message")

    private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
}

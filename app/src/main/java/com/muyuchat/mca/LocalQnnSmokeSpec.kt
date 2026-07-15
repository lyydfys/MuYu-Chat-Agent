package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class QnnSmokeTensorSpec(
    val name: String,
    val role: String,
    val dataType: String,
    val shape: List<Int>,
    val fill: String = "zero",
    val scale: Float? = null,
    val zeroPoint: Int? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("role", role)
        .put("dataType", dataType)
        .put("shape", JSONArray(shape))
        .put("fill", fill)
        .also { json ->
            scale?.let { json.put("scale", it) }
            zeroPoint?.let { json.put("zeroPoint", it) }
        }
}

internal data class QnnTensorBufferPlan(
    val name: String,
    val role: String,
    val dataType: String,
    val shape: List<Int>,
    val elementCount: Long,
    val bytesPerElement: Int,
    val byteSize: Long,
    val supported: Boolean,
    val reason: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("role", role)
        .put("dataType", dataType)
        .put("shape", JSONArray(shape))
        .put("elementCount", elementCount)
        .put("bytesPerElement", bytesPerElement)
        .put("byteSize", byteSize)
        .put("supported", supported)
        .put("reason", reason)
}

internal data class QnnSmokeBufferPlan(
    val inputs: List<QnnTensorBufferPlan>,
    val outputs: List<QnnTensorBufferPlan>
) {
    val ready: Boolean
        get() = inputs.isNotEmpty() &&
            outputs.isNotEmpty() &&
            inputs.all { it.supported } &&
            outputs.all { it.supported }

    val totalInputBytes: Long
        get() = inputs.sumOf { it.byteSize }

    val totalOutputBytes: Long
        get() = outputs.sumOf { it.byteSize }

    val totalBytes: Long
        get() = totalInputBytes + totalOutputBytes

    fun toJson(): JSONObject = JSONObject()
        .put("ready", ready)
        .put("totalInputBytes", totalInputBytes)
        .put("totalOutputBytes", totalOutputBytes)
        .put("totalBytes", totalBytes)
        .put("inputs", JSONArray(inputs.map { it.toJson() }))
        .put("outputs", JSONArray(outputs.map { it.toJson() }))
}

internal data class QnnSmokeValidationReport(
    val readyForNativeSmoke: Boolean,
    val blockingReasons: List<String>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("readyForNativeSmoke", readyForNativeSmoke)
        .put("blockingReasons", JSONArray(blockingReasons))
}

internal data class QnnSmokeSpec(
    val graphName: String = "",
    val contextBinary: String = "",
    val timeoutSeconds: Int = 60,
    val inputs: List<QnnSmokeTensorSpec> = emptyList(),
    val outputs: List<QnnSmokeTensorSpec> = emptyList()
) {
    val bufferPlan: QnnSmokeBufferPlan
        get() = QnnSmokeBufferPlan(
            inputs = inputs.map { it.toBufferPlan() },
            outputs = outputs.map { it.toBufferPlan() }
        )

    val validation: QnnSmokeValidationReport
        get() {
            val plan = bufferPlan
            val reasons = buildList {
                if (graphName.isBlank()) add("QNN smoke graphName is required.")
                if (contextBinary.isBlank()) {
                    add("QNN smoke contextBinary is required.")
                } else if (!contextBinary.isSafeBundleRelativePath()) {
                    add("QNN smoke contextBinary must be a safe relative bundle path.")
                }
                if (inputs.isEmpty()) add("QNN smoke requires at least one input tensor.")
                if (outputs.isEmpty()) add("QNN smoke requires at least one output tensor.")
                addAll(inputs.missingRequiredTensorReasons("input"))
                addAll(outputs.missingRequiredTensorReasons("output"))
                addAll(inputs.duplicateTensorNameReasons("input"))
                addAll(outputs.duplicateTensorNameReasons("output"))
                addAll(plan.inputs.filter { !it.supported }.map { "Input tensor ${it.name.ifBlank { "<unnamed>" }} is not bindable: ${it.reason}" })
                addAll(plan.outputs.filter { !it.supported }.map { "Output tensor ${it.name.ifBlank { "<unnamed>" }} is not bindable: ${it.reason}" })
                if (plan.ready && plan.totalBytes > MAX_NATIVE_SMOKE_BUFFER_BYTES) {
                    add("QNN smoke tensor buffers exceed the native smoke limit of $MAX_NATIVE_SMOKE_BUFFER_BYTES bytes.")
                }
            }
            return QnnSmokeValidationReport(
                readyForNativeSmoke = reasons.isEmpty(),
                blockingReasons = reasons
            )
        }

    val completeForGraphSmoke: Boolean
        get() = validation.readyForNativeSmoke

    fun toJson(): JSONObject = JSONObject()
        .put("graphName", graphName)
        .put("contextBinary", contextBinary)
        .put("timeoutSeconds", timeoutSeconds)
        .put("inputs", JSONArray(inputs.map { it.toJson() }))
        .put("outputs", JSONArray(outputs.map { it.toJson() }))
        .put("bufferPlan", bufferPlan.toJson())
        .put("tensorBufferPlanReady", bufferPlan.ready)
        .put("inputBufferBytes", bufferPlan.totalInputBytes)
        .put("outputBufferBytes", bufferPlan.totalOutputBytes)
        .put("totalBufferBytes", bufferPlan.totalBytes)
        .put("validation", validation.toJson())
        .put("completeForGraphSmoke", completeForGraphSmoke)

    companion object {
        val Empty: QnnSmokeSpec = QnnSmokeSpec()

        fun fromSmokeJson(smoke: JSONObject?): QnnSmokeSpec {
            if (smoke == null) return Empty
            return QnnSmokeSpec(
                graphName = smoke.optString("graphName")
                    .ifBlank { smoke.optString("graph").ifBlank { smoke.optString("name") } },
                contextBinary = smoke.optString("contextBinary")
                    .ifBlank { smoke.optString("context").ifBlank { smoke.optString("contextPath") } },
                timeoutSeconds = smoke.optInt("timeoutSeconds", 60).coerceAtLeast(1),
                inputs = smoke.optJSONArray("inputs").toTensorSpecs(defaultRole = "input"),
                outputs = smoke.optJSONArray("outputs").toTensorSpecs(defaultRole = "output")
            )
        }
    }
}

internal fun QnnSmokeSpec.contextBinaryFileIn(bundleRoot: File): File? {
    if (!validation.readyForNativeSmoke) return null
    val normalized = contextBinary.replace('\\', '/').trim()
    val rootCanonical = runCatching { bundleRoot.canonicalFile }.getOrNull() ?: return null
    val candidate = runCatching { File(rootCanonical, normalized).canonicalFile }.getOrNull() ?: return null
    return candidate.takeIf {
        it.isFile &&
            it.length() > 0L &&
            (it.path == rootCanonical.path || it.path.startsWith(rootCanonical.path + File.separator))
    }
}

/**
 * Chooses the first executable graph spec for the one-graph debug harness.
 *
 * Image manifests may keep their legacy [smoke] object for a complete semantic
 * run while putting graph bindings under [smokes]. The harness must therefore
 * use the parsed smoke suite instead of requiring graph fields on legacy
 * metadata.
 */
internal fun qnnGraphSmokeSpecForHarness(bundleRoot: File): QnnSmokeSpec {
    val manifest = localImageBundleManifestFromRoot(bundleRoot)
        ?: error("QNN bundle manifest could not be parsed: ${File(bundleRoot, "manifest.json").absolutePath}")
    val specs = manifest.qnnSmokeSpecs.ifEmpty { listOf(manifest.qnnSmokeSpec) }
    val invalid = specs.firstOrNull { !it.validation.readyForNativeSmoke }
    require(specs.isNotEmpty() && invalid == null) {
        val details = invalid?.validation?.blockingReasons?.joinToString(" ").orEmpty()
        "QNN manifest requires at least one complete graph smoke spec under smokes[], smokeSpecs, smoke, or smokeSpec.${if (details.isBlank()) "" else " $details"}"
    }
    return specs.first()
}

private const val MAX_NATIVE_SMOKE_BUFFER_BYTES: Long = 512L * 1024L * 1024L

private fun QnnSmokeTensorSpec.toBufferPlan(): QnnTensorBufferPlan {
    val bytes = bytesPerElement(dataType)
    val elements = shape.fold(1L) { acc, value ->
        if (value <= 0 || acc > Long.MAX_VALUE / value) return unsupportedBufferPlan("Invalid or overflowing shape.")
        acc * value
    }
    if (bytes <= 0) return unsupportedBufferPlan("Unsupported QNN smoke tensor data type.")
    val byteSize = if (elements > Long.MAX_VALUE / bytes) {
        return unsupportedBufferPlan("Tensor byte size overflows Long.")
    } else {
        elements * bytes
    }
    return QnnTensorBufferPlan(
        name = name,
        role = role,
        dataType = normalizedDataType(dataType),
        shape = shape,
        elementCount = elements,
        bytesPerElement = bytes,
        byteSize = byteSize,
        supported = true
    )
}

private fun QnnSmokeTensorSpec.unsupportedBufferPlan(reason: String): QnnTensorBufferPlan =
    QnnTensorBufferPlan(
        name = name,
        role = role,
        dataType = normalizedDataType(dataType),
        shape = shape,
        elementCount = 0L,
        bytesPerElement = 0,
        byteSize = 0L,
        supported = false,
        reason = reason
    )

private fun bytesPerElement(dataType: String): Int =
    when (normalizedDataType(dataType)) {
        "bool", "int8", "uint8" -> 1
        "float16", "fp16", "int16", "uint16" -> 2
        "float32", "fp32", "int32", "uint32" -> 4
        "float64", "fp64", "int64", "uint64" -> 8
        else -> 0
    }

private fun normalizedDataType(dataType: String): String =
    dataType.trim().lowercase()

private fun JSONArray?.toTensorSpecs(defaultRole: String): List<QnnSmokeTensorSpec> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val name = item.optString("name")
                .ifBlank { item.optString("tensorName") }
            if (name.isBlank()) continue
            add(
                QnnSmokeTensorSpec(
                    name = name,
                    role = item.optString("role").ifBlank { defaultRole },
                    dataType = item.optString("dataType")
                        .ifBlank { item.optString("dtype").ifBlank { item.optString("type") } },
                    shape = item.optJSONArray("shape").toIntList(),
                    fill = item.optString("fill").ifBlank { "zero" },
                    scale = item.optDoubleOrNull("scale")?.toFloat(),
                    zeroPoint = item.optIntOrNull("zeroPoint")
                )
            )
        }
    }
}

private fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = optInt(index, 0)
            if (value > 0) add(value)
        }
    }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name)) optDouble(name) else null

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name)) optInt(name) else null

private fun List<QnnSmokeTensorSpec>.missingRequiredTensorReasons(role: String): List<String> =
    flatMapIndexed { index, tensor ->
        buildList {
            val label = "$role tensor #${index + 1}"
            if (tensor.name.isBlank()) add("$label requires a name.")
            if (tensor.dataType.isBlank()) add("$label requires a dataType.")
            if (tensor.shape.isEmpty()) add("$label requires a positive shape.")
        }
    }

private fun List<QnnSmokeTensorSpec>.duplicateTensorNameReasons(role: String): List<String> =
    map { it.name.trim() }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .map { "Duplicate $role tensor name: $it." }

private fun String.isSafeBundleRelativePath(): Boolean {
    val normalized = trim().replace('\\', '/')
    if (normalized.isBlank()) return false
    if (normalized.startsWith("/")) return false
    if (normalized.startsWith("./")) return false
    if (Regex("""^[A-Za-z]:""").containsMatchIn(normalized)) return false
    return normalized
        .split('/')
        .all { segment -> segment.isNotBlank() && segment != "." && segment != ".." }
}

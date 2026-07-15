package com.muyuchat.mca

import com.muyuchat.core.nativebridge.NativeQnnBridge
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class QnnTensorBufferDiagnostics(
    val name: String = "",
    val role: String = "",
    val dataType: String = "",
    val shape: List<Int> = emptyList(),
    val elementCount: Long = 0L,
    val bytesPerElement: Int = 0,
    val byteSize: Long = 0L,
    val supported: Boolean = false,
    val reason: String = ""
) {
    val bindable: Boolean
        get() = supported && name.isNotBlank() && byteSize > 0

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("role", role)
        .put("dataType", dataType)
        .put("shape", JSONArray(shape))
        .put("elementCount", elementCount)
        .put("bytesPerElement", bytesPerElement)
        .put("byteSize", byteSize)
        .put("supported", supported)
        .put("bindable", bindable)
        .put("reason", reason)

    companion object {
        fun fromJson(json: JSONObject?): QnnTensorBufferDiagnostics {
            if (json == null) return QnnTensorBufferDiagnostics()
            return QnnTensorBufferDiagnostics(
                name = json.optString("name"),
                role = json.optString("role"),
                dataType = json.optString("dataType"),
                shape = json.optJSONArray("shape").toIntList(),
                elementCount = json.optLong("elementCount", 0L),
                bytesPerElement = json.optInt("bytesPerElement", 0),
                byteSize = json.optLong("byteSize", 0L),
                supported = json.optBoolean("supported", false),
                reason = json.optString("reason")
            )
        }
    }
}

internal data class QnnBinaryMetadataDiagnostics(
    val attempted: Boolean = false,
    val parsed: Boolean = false,
    val version: Int = 0,
    val backendId: Int = 0,
    val buildId: String = "",
    val coreApiVersion: String = "",
    val backendApiVersion: String = "",
    val socVersion: String = "",
    val socModel: Int = 0,
    val contextBlobSize: Long = 0L,
    val graphCount: Int = 0,
    val graphNames: List<String> = emptyList(),
    val message: String = ""
) {
    val targetSocKnown: Boolean
        get() = parsed && socModel > 0

    fun toJson(): JSONObject = JSONObject()
        .put("attempted", attempted)
        .put("parsed", parsed)
        .put("version", version)
        .put("backendId", backendId)
        .put("buildId", buildId)
        .put("coreApiVersion", coreApiVersion)
        .put("backendApiVersion", backendApiVersion)
        .put("socVersion", socVersion)
        .put("socModel", socModel)
        .put("contextBlobSize", contextBlobSize)
        .put("graphCount", graphCount)
        .put("graphNames", JSONArray(graphNames))
        .put("message", message)

    companion object {
        val Empty: QnnBinaryMetadataDiagnostics = QnnBinaryMetadataDiagnostics()

        fun fromJson(json: JSONObject?): QnnBinaryMetadataDiagnostics {
            if (json == null) return Empty
            return QnnBinaryMetadataDiagnostics(
                attempted = json.optBoolean("attempted", false),
                parsed = json.optBoolean("parsed", false),
                version = json.optInt("version", 0),
                backendId = json.optInt("backendId", 0),
                buildId = json.optString("buildId"),
                coreApiVersion = json.optString("coreApiVersion"),
                backendApiVersion = json.optString("backendApiVersion"),
                socVersion = json.optString("socVersion"),
                socModel = json.optInt("socModel", 0),
                contextBlobSize = json.optLong("contextBlobSize", 0L),
                graphCount = json.optInt("graphCount", 0),
                graphNames = json.optJSONArray("graphNames").toStringList(),
                message = json.optString("message")
            )
        }
    }
}

internal data class QnnExecutionDiagnostics(
    val executionStage: String = "",
    val graphMetadataReady: Boolean = false,
    val qnnInterfacePresent: Boolean = false,
    val sdkHeadersPresent: Boolean = false,
    val typedGraphBindingsCompiled: Boolean = false,
    val cdspRpcLibraryPresent: Boolean = false,
    val cdspRpcLibraryLoadable: Boolean = false,
    val cdspRpcMessage: String = "",
    val smokeInputCount: Int = 0,
    val smokeOutputCount: Int = 0,
    val tensorBufferPlanReady: Boolean = false,
    val smokeValidationReady: Boolean = false,
    val smokeValidationBlockingReasons: List<String> = emptyList(),
    val inputBufferBytes: Long = 0L,
    val outputBufferBytes: Long = 0L,
    val contextBinaryBytes: Long = 0L,
    val runtimeLoaded: Boolean = false,
    val qnnInterfaceFound: Boolean = false,
    val bundleManifestFound: Boolean = false,
    val bundleGraphArtifactFound: Boolean = false,
    val bundleContextBinaryFound: Boolean = false,
    val bundleContextBinaryNonEmpty: Boolean = false,
    val smokeMetadataComplete: Boolean = false,
    val sdkHeadersCompiled: Boolean = false,
    val backendCreated: Boolean = false,
    val contextLoaded: Boolean = false,
    val graphResolved: Boolean = false,
    val tensorsBound: Boolean = false,
    val graphExecuted: Boolean = false,
    val binaryMetadata: QnnBinaryMetadataDiagnostics = QnnBinaryMetadataDiagnostics.Empty,
    val inputTensors: List<QnnTensorBufferDiagnostics> = emptyList(),
    val outputTensors: List<QnnTensorBufferDiagnostics> = emptyList()
) {
    val allTensorsBindable: Boolean
        get() = inputTensors.isNotEmpty() &&
            outputTensors.isNotEmpty() &&
            inputTensors.all { it.bindable } &&
            outputTensors.all { it.bindable }

    fun toJson(): JSONObject = JSONObject()
        .put("executionStage", executionStage)
        .put("graphMetadataReady", graphMetadataReady)
        .put("qnnInterfacePresent", qnnInterfacePresent)
        .put("sdkHeadersPresent", sdkHeadersPresent)
        .put("typedGraphBindingsCompiled", typedGraphBindingsCompiled)
        .put("cdspRpcLibraryPresent", cdspRpcLibraryPresent)
        .put("cdspRpcLibraryLoadable", cdspRpcLibraryLoadable)
        .put("cdspRpcMessage", cdspRpcMessage)
        .put("smokeInputCount", smokeInputCount)
        .put("smokeOutputCount", smokeOutputCount)
        .put("tensorBufferPlanReady", tensorBufferPlanReady)
        .put("smokeValidationReady", smokeValidationReady)
        .put("smokeValidationBlockingReasons", JSONArray(smokeValidationBlockingReasons))
        .put("inputBufferBytes", inputBufferBytes)
        .put("outputBufferBytes", outputBufferBytes)
        .put("contextBinaryBytes", contextBinaryBytes)
        .put("runtimeLoaded", runtimeLoaded)
        .put("qnnInterfaceFound", qnnInterfaceFound)
        .put("bundleManifestFound", bundleManifestFound)
        .put("bundleGraphArtifactFound", bundleGraphArtifactFound)
        .put("bundleContextBinaryFound", bundleContextBinaryFound)
        .put("bundleContextBinaryNonEmpty", bundleContextBinaryNonEmpty)
        .put("smokeMetadataComplete", smokeMetadataComplete)
        .put("sdkHeadersCompiled", sdkHeadersCompiled)
        .put("backendCreated", backendCreated)
        .put("contextLoaded", contextLoaded)
        .put("graphResolved", graphResolved)
        .put("tensorsBound", tensorsBound)
        .put("graphExecuted", graphExecuted)
        .put("allTensorsBindable", allTensorsBindable)
        .put("binaryMetadata", binaryMetadata.toJson())
        .put("inputTensors", JSONArray(inputTensors.map { it.toJson() }))
        .put("outputTensors", JSONArray(outputTensors.map { it.toJson() }))

    companion object {
        val Empty: QnnExecutionDiagnostics = QnnExecutionDiagnostics()

        fun from(smoke: NativeQnnSmokeResult): QnnExecutionDiagnostics =
            QnnExecutionDiagnostics(
                executionStage = smoke.executionStage,
                graphMetadataReady = smoke.graphMetadataReady,
                qnnInterfacePresent = smoke.qnnInterfacePresent,
                sdkHeadersPresent = smoke.sdkHeadersPresent,
                typedGraphBindingsCompiled = smoke.typedGraphBindingsCompiled,
                cdspRpcLibraryPresent = smoke.cdspRpcLibraryPresent,
                cdspRpcLibraryLoadable = smoke.cdspRpcLibraryLoadable,
                cdspRpcMessage = smoke.cdspRpcMessage,
                smokeInputCount = smoke.smokeInputCount,
                smokeOutputCount = smoke.smokeOutputCount,
                tensorBufferPlanReady = smoke.tensorBufferPlanReady,
                smokeValidationReady = smoke.smokeValidationReady,
                smokeValidationBlockingReasons = smoke.smokeValidationBlockingReasons,
                inputBufferBytes = smoke.inputBufferBytes,
                outputBufferBytes = smoke.outputBufferBytes,
                contextBinaryBytes = smoke.contextBinaryBytes,
                runtimeLoaded = smoke.runtimeLoaded,
                qnnInterfaceFound = smoke.qnnInterfaceFound,
                bundleManifestFound = smoke.bundleManifestFound,
                bundleGraphArtifactFound = smoke.bundleGraphArtifactFound,
                bundleContextBinaryFound = smoke.bundleContextBinaryFound,
                bundleContextBinaryNonEmpty = smoke.bundleContextBinaryNonEmpty,
                smokeMetadataComplete = smoke.smokeMetadataComplete,
                sdkHeadersCompiled = smoke.sdkHeadersCompiled,
                backendCreated = smoke.backendCreated,
                contextLoaded = smoke.contextLoaded,
                graphResolved = smoke.graphResolved,
                tensorsBound = smoke.tensorsBound,
                graphExecuted = smoke.graphExecute,
                binaryMetadata = smoke.binaryMetadata,
                inputTensors = smoke.inputTensors,
                outputTensors = smoke.outputTensors
            )
    }
}

internal data class NativeQnnSmokeResult(
    val backend: String = "qnn_htp",
    val message: String,
    val runnerReady: Boolean = false,
    val graphRunnerReady: Boolean = false,
    val graphExecute: Boolean = false,
    val npuActive: Boolean = false,
    val smokePassed: Boolean = false,
    val elapsedMs: Long = 0L,
    val graphMetadataReady: Boolean = false,
    val qnnInterfacePresent: Boolean = false,
    val sdkRootConfigured: Boolean = false,
    val sdkHeadersPresent: Boolean = false,
    val typedGraphBindingsCompiled: Boolean = false,
    val cdspRpcLibraryPresent: Boolean = false,
    val cdspRpcLibraryLoadable: Boolean = false,
    val cdspRpcMessage: String = "",
    val smokeInputCount: Int = 0,
    val smokeOutputCount: Int = 0,
    val tensorBufferPlanReady: Boolean = false,
    val smokeValidationReady: Boolean = false,
    val smokeValidationBlockingReasons: List<String> = emptyList(),
    val inputBufferBytes: Long = 0L,
    val outputBufferBytes: Long = 0L,
    val contextBinaryBytes: Long = 0L,
    val executionStage: String = "",
    val runtimeLoaded: Boolean = false,
    val qnnInterfaceFound: Boolean = false,
    val bundleManifestFound: Boolean = false,
    val bundleGraphArtifactFound: Boolean = false,
    val bundleContextBinaryFound: Boolean = false,
    val bundleContextBinaryNonEmpty: Boolean = false,
    val smokeMetadataComplete: Boolean = false,
    val sdkHeadersCompiled: Boolean = false,
    val backendCreated: Boolean = false,
    val contextLoaded: Boolean = false,
    val graphResolved: Boolean = false,
    val tensorsBound: Boolean = false,
    val binaryMetadata: QnnBinaryMetadataDiagnostics = QnnBinaryMetadataDiagnostics.Empty,
    val inputTensors: List<QnnTensorBufferDiagnostics> = emptyList(),
    val outputTensors: List<QnnTensorBufferDiagnostics> = emptyList()
) {
    val provesNpuExecution: Boolean
        get() = graphRunnerReady && graphExecute && npuActive && smokePassed

    companion object {
        fun fromJson(raw: String): NativeQnnSmokeResult {
            val json = JSONObject(raw)
            return NativeQnnSmokeResult(
                backend = json.optString("backend").ifBlank { "qnn_htp" },
                message = json.optString("message").ifBlank { "QNN smoke returned no message." },
                runnerReady = json.optBoolean("runnerReady", false),
                graphRunnerReady = json.optBoolean("graphRunnerReady", false),
                graphExecute = json.optBoolean("graphExecute", false),
                npuActive = json.optBoolean("npuActive", false),
                smokePassed = json.optBoolean("smokePassed", false),
                elapsedMs = json.optLong("elapsedMs", 0L),
                graphMetadataReady = json.optBoolean("graphMetadataReady", false),
                qnnInterfacePresent = json.optJSONObject("runtime")
                    ?.optBoolean("qnnInterfacePresent", false) ?: false,
                sdkRootConfigured = json.optJSONObject("compile")
                    ?.optBoolean("sdkRootConfigured", false)
                    ?: json.optJSONObject("runtime")
                        ?.optJSONObject("compile")
                        ?.optBoolean("sdkRootConfigured", false)
                    ?: false,
                sdkHeadersPresent = json.optJSONObject("compile")
                    ?.optBoolean("sdkHeadersPresent", false)
                    ?: json.optJSONObject("runtime")
                        ?.optJSONObject("compile")
                        ?.optBoolean("sdkHeadersPresent", false)
                    ?: false,
                typedGraphBindingsCompiled = json.optJSONObject("compile")
                    ?.optBoolean("typedGraphBindingsCompiled", false)
                    ?: json.optJSONObject("runtime")
                        ?.optJSONObject("compile")
                        ?.optBoolean("typedGraphBindingsCompiled", false)
                    ?: false,
                cdspRpcLibraryPresent = json.optJSONObject("runtime")
                    ?.optBoolean("cdspRpcLibraryPresent", false) ?: false,
                cdspRpcLibraryLoadable = json.optJSONObject("runtime")
                    ?.optBoolean("cdspRpcLibraryLoadable", false) ?: false,
                cdspRpcMessage = json.optJSONObject("runtime")
                    ?.optString("cdspRpcMessage").orEmpty(),
                smokeInputCount = json.optJSONObject("smokeSpec")?.optInt("inputCount", 0) ?: 0,
                smokeOutputCount = json.optJSONObject("smokeSpec")?.optInt("outputCount", 0) ?: 0,
                tensorBufferPlanReady = json.optJSONObject("smokeSpec")
                    ?.optBoolean("tensorBufferPlanReady", false) ?: false,
                smokeValidationReady = json.optJSONObject("smokeSpec")
                    ?.optBoolean("validationReady", false) ?: false,
                smokeValidationBlockingReasons = json.optJSONObject("smokeSpec")
                    ?.optJSONObject("validation")
                    ?.optJSONArray("blockingReasons")
                    .toStringList(),
                inputBufferBytes = json.optJSONObject("smokeSpec")
                    ?.optLong("inputBufferBytes", 0L) ?: 0L,
                outputBufferBytes = json.optJSONObject("smokeSpec")
                    ?.optLong("outputBufferBytes", 0L) ?: 0L,
                contextBinaryBytes = json.optJSONObject("smokeSpec")
                    ?.optLong("contextBinaryBytes", 0L) ?: 0L,
                executionStage = json.optString("executionStage"),
                runtimeLoaded = json.optJSONObject("stages")?.optBoolean("runtimeLoaded", false)
                    ?: false,
                qnnInterfaceFound = json.optJSONObject("stages")?.optBoolean("qnnInterfaceFound", false)
                    ?: false,
                bundleManifestFound = json.optJSONObject("stages")?.optBoolean("bundleManifestFound", false)
                    ?: false,
                bundleGraphArtifactFound = json.optJSONObject("stages")?.optBoolean("bundleGraphArtifactFound", false)
                    ?: false,
                bundleContextBinaryFound = json.optJSONObject("stages")?.optBoolean("bundleContextBinaryFound", false)
                    ?: false,
                bundleContextBinaryNonEmpty = json.optJSONObject("stages")?.optBoolean("bundleContextBinaryNonEmpty", false)
                    ?: false,
                smokeMetadataComplete = json.optJSONObject("stages")?.optBoolean("smokeMetadataComplete", false)
                    ?: false,
                sdkHeadersCompiled = json.optJSONObject("stages")?.optBoolean("sdkHeadersCompiled", false)
                    ?: false,
                backendCreated = json.optJSONObject("stages")?.optBoolean("backendCreated", false)
                    ?: false,
                contextLoaded = json.optJSONObject("stages")?.optBoolean("contextLoaded", false)
                    ?: false,
                graphResolved = json.optJSONObject("stages")?.optBoolean("graphResolved", false)
                    ?: false,
                tensorsBound = json.optJSONObject("stages")?.optBoolean("tensorsBound", false)
                    ?: false,
                binaryMetadata = QnnBinaryMetadataDiagnostics.fromJson(
                    json.optJSONObject("binaryMetadata")
                ),
                inputTensors = json.optJSONObject("smokeSpec")
                    ?.optJSONObject("bufferPlan")
                    ?.optJSONArray("inputs")
                    .toTensorBufferDiagnostics(),
                outputTensors = json.optJSONObject("smokeSpec")
                    ?.optJSONObject("bufferPlan")
                    ?.optJSONArray("outputs")
                    .toTensorBufferDiagnostics()
            )
        }

        fun unavailable(message: String): NativeQnnSmokeResult =
            NativeQnnSmokeResult(message = message)
    }
}

private fun JSONArray?.toTensorBufferDiagnostics(): List<QnnTensorBufferDiagnostics> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            add(QnnTensorBufferDiagnostics.fromJson(optJSONObject(index)))
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

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) add(value)
        }
    }
}

internal interface LocalImageQnnSmokeBridge {
    val runnerReady: Boolean
    fun runImageSmoke(
        bundleRoot: File,
        runtimeDirs: List<String>,
        smokeSpec: QnnSmokeSpec
    ): NativeQnnSmokeResult
}

internal interface LocalVisionNpuSmokeBridge {
    val runnerReady: Boolean
    fun runVisionSmoke(
        bundleRoot: File,
        runtimeDirs: List<String>,
        smokeSpec: QnnSmokeSpec
    ): NativeQnnSmokeResult
}

internal object NativeLocalImageQnnSmokeBridge : LocalImageQnnSmokeBridge {
    override val runnerReady: Boolean
        get() = NativeQnnBridge.runnerReady

    override fun runImageSmoke(
        bundleRoot: File,
        runtimeDirs: List<String>,
        smokeSpec: QnnSmokeSpec
    ): NativeQnnSmokeResult =
        runCatching {
            NativeQnnSmokeResult.fromJson(
                NativeQnnBridge().runImageSmoke(
                    bundleRoot.absolutePath,
                    JSONArray(runtimeDirs).toString(),
                    smokeSpec.toJson().toString()
                )
            )
        }.getOrElse { error ->
            NativeQnnSmokeResult.unavailable(
                "QNN image bridge failed: ${error.message ?: error::class.java.simpleName}"
            )
        }
}

internal object NativeLocalVisionNpuSmokeBridge : LocalVisionNpuSmokeBridge {
    override val runnerReady: Boolean
        get() = NativeQnnBridge.runnerReady

    override fun runVisionSmoke(
        bundleRoot: File,
        runtimeDirs: List<String>,
        smokeSpec: QnnSmokeSpec
    ): NativeQnnSmokeResult =
        runCatching {
            NativeQnnSmokeResult.fromJson(
                NativeQnnBridge().runVisionSmoke(
                    bundleRoot.absolutePath,
                    JSONArray(runtimeDirs).toString(),
                    smokeSpec.toJson().toString()
                )
            )
        }.getOrElse { error ->
            NativeQnnSmokeResult.unavailable(
                "QNN vision bridge failed: ${error.message ?: error::class.java.simpleName}"
            )
        }
}

package com.muyuchat.core.engine

import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import com.geniex.sdk.bean.RuntimeIdValue
import com.muyuchat.core.modelstore.QairtBundleReadinessAnalyzer
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

internal fun GenieXChatRunner.prepareDirectGgufModel(input: File): PreparedGenieXModel {
        val searchRoot = input.takeIf(File::isDirectory) ?: input.parentFile
        val gguf = when {
            input.isFile &&
                input.extension.equals("gguf", ignoreCase = true) &&
                !input.isVisionProjectorFile() -> input
            input.isDirectory -> input.walkTopDown()
                .filter { candidate ->
                    candidate.isFile &&
                        candidate.extension.equals("gguf", ignoreCase = true) &&
                        !candidate.isVisionProjectorFile()
                }
                .maxByOrNull { it.length() }
                ?: error("No text GGUF model found under ${input.absolutePath}.")
            input.isFile && input.extension.equals("gguf", ignoreCase = true) -> searchRoot
                ?.walkTopDown()
                ?.maxDepth(2)
                ?.filter { candidate ->
                    candidate.isFile &&
                        candidate.extension.equals("gguf", ignoreCase = true) &&
                        !candidate.isVisionProjectorFile()
                }
                ?.maxByOrNull { it.length() }
                ?: error("No text GGUF model found beside ${input.absolutePath}.")
            else -> error("GenieX llama.cpp requires a GGUF file or directory: ${input.absolutePath}")
        }
        require(gguf.length() > 0L) { "GGUF model is empty: ${gguf.absolutePath}" }
        val explicitMmproj = loadParams.visionProjectorPath?.let { rawPath ->
            val rawFile = File(rawPath)
            val candidate = if (rawFile.isAbsolute) rawFile else File(searchRoot, rawPath)
            require(candidate.isFile && candidate.length() > 0L && candidate.canRead()) {
                "Vision projector does not exist or is empty: ${candidate.absolutePath}"
            }
            candidate
        }
        val discoveredMmproj = searchRoot
            ?.takeIf(File::isDirectory)
            ?.walkTopDown()
            ?.maxDepth(3)
            ?.filter { candidate ->
                candidate.isFile &&
                    candidate.extension.equals("gguf", ignoreCase = true) &&
                    candidate.isVisionProjectorFile()
            }
            ?.toList()
            ?.let { candidates -> chooseGenieXVisionProjector(gguf, candidates) }
        val mmproj = explicitMmproj ?: discoveredMmproj
        val modelType = detectGenieXModelType(input, mmproj?.absolutePath)
        return PreparedGenieXModel(
            modelName = gguf.nameWithoutExtension,
            modelPath = gguf.absolutePath,
            tokenizerPath = null,
            runtimeId = RuntimeIdValue.LLAMA_CPP.value.orEmpty(),
            modelType = modelType,
            mmprojPath = mmproj?.absolutePath
        )
    }

internal fun File.isVisionProjectorFile(): Boolean =
        name.contains("mmproj", ignoreCase = true) ||
            name.contains("projector", ignoreCase = true)

internal fun GenieXChatRunner.resolveLlamaCppComputeUnit(raw: String?): String = when (raw?.trim()?.lowercase()) {
        ComputeUnitValue.CPU.value -> ComputeUnitValue.CPU.value.orEmpty()
        ComputeUnitValue.GPU.value -> ComputeUnitValue.GPU.value.orEmpty()
        ComputeUnitValue.NPU.value -> ComputeUnitValue.NPU.value.orEmpty()
        ComputeUnitValue.HYBRID.value -> ComputeUnitValue.HYBRID.value.orEmpty()
        else -> defaultComputeUnit
    }

internal fun GenieXChatRunner.backendDevicesFor(computeUnit: String, modelType: ModelType): String {
        if (isQairtRuntime) {
            return if (modelType == ModelType.VLM) "QAIRT HTP / VLM" else defaultBackendDevices
        }
        return when (computeUnit) {
        ComputeUnitValue.CPU.value -> "CPU / GenieX llama.cpp"
        ComputeUnitValue.GPU.value -> "Adreno OpenCL / GenieX llama.cpp"
        ComputeUnitValue.NPU.value -> "骁龙 HTP0 / GenieX llama.cpp"
        ComputeUnitValue.HYBRID.value -> "骁龙 HTP + CPU / GenieX llama.cpp"
        else -> defaultBackendDevices
        }
    }

internal fun GenieXChatRunner.prepareManagedQairtModel(bundleDir: File): PreparedGenieXModel {
        val resolvedBundleDir = resolveQairtBundleRootForLoad(bundleDir)
            ?: error("GenieX QAIRT model root is ambiguous or incomplete: ${bundleDir.absolutePath}")
        val sourceReadiness = QairtBundleReadinessAnalyzer.analyze(resolvedBundleDir)
        lastQairtBundleReadiness = sourceReadiness
        if (!sourceReadiness.canLoad) {
            LocalChatRunnerDebug.emit(
                "qairt_bundle_preflight_failed",
                JSONObject()
                    .put("bundleDir", resolvedBundleDir.absolutePath)
                    .put("diagnostic", sourceReadiness.diagnosticSummary())
                    .put("missing", sourceReadiness.missingRequiredComponents.joinToString(","))
                    .put("invalid", sourceReadiness.invalidRequiredComponents.joinToString(","))
            )
            error("GenieX QAIRT model package is incomplete: ${sourceReadiness.diagnosticSummary()}")
        }
        LocalChatRunnerDebug.emit(
            "qairt_bundle_preflight_ok",
            JSONObject()
                .put("bundleDir", resolvedBundleDir.absolutePath)
                .put("modelId", sourceReadiness.modelId)
                .put("supportsVision", sourceReadiness.supportsVision)
                .put("rootBinCount", sourceReadiness.rootBinPaths.size)
        )
        val candidates = qairtModelNameCandidates(resolvedBundleDir)
        val importName = candidates.firstOrNull() ?: resolvedBundleDir.name
        val desiredModelType = detectGenieXModelType(resolvedBundleDir, loadParams.visionProjectorPath)
        return runBlocking {
            val manager = ModelManagerWrapper
            for (candidate in candidates) {
                val existing = runCatching { manager.getPaths(candidate) }.getOrNull() ?: continue
                val existingModel = existing.model_path
                    .takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?.takeIf { it.isFile && it.length() > 0L }
                    ?: continue
                val existingReadiness = QairtBundleReadinessAnalyzer.analyze(
                    requireNotNull(existingModel.parentFile) {
                        "GenieX QAIRT cache model has no parent directory."
                    }
                )
                if (!existingReadiness.canLoad) {
                    val removeResult = runCatching { manager.remove(candidate) }.getOrNull()
                    LocalChatRunnerDebug.emit(
                        "qairt_existing_registration_invalidated",
                        JSONObject()
                            .put("candidate", candidate)
                            .put("modelPath", existingModel.absolutePath)
                            .put("removeResult", removeResult)
                            .put("diagnostic", existingReadiness.diagnosticSummary())
                    )
                    continue
                }
                val registeredName = existing.model_name.takeIf { it.isNotBlank() } ?: candidate
                LocalChatRunnerDebug.emit(
                    if (existing.model_type == desiredModelType) {
                        "qairt_existing_registration_reused"
                    } else {
                        "qairt_existing_registration_type_overridden"
                    },
                    JSONObject()
                        .put("candidate", candidate)
                        .put("registeredName", registeredName)
                        .put("registeredModelType", existing.model_type.name.lowercase())
                        .put("effectiveModelType", desiredModelType.name.lowercase())
                        .put("modelPath", existingModel.absolutePath)
                )
                return@runBlocking PreparedGenieXModel(
                    modelName = registeredName,
                    modelPath = existingModel.absolutePath,
                    tokenizerPath = existing.tokenizer_path?.takeIf { it.isNotBlank() },
                    runtimeId = existing.runtime_id.orEmpty().ifBlank { RuntimeIdValue.QAIRT.value.orEmpty() },
                    modelType = desiredModelType,
                    mmprojPath = existing.mmproj_path?.takeIf { it.isNotBlank() }
                )
            }
            LocalChatRunnerDebug.emit(
                "qairt_localfs_pull_start",
                JSONObject()
                    .put("importName", importName)
                    .put("bundleDir", resolvedBundleDir.absolutePath)
                    .put("modelType", desiredModelType.name.lowercase())
            )
            manager.pullFlow(
                ModelPullInput(
                    model_name = importName,
                    precision = "w4a16",
                    hub = HubSource.LOCALFS,
                    local_path = resolvedBundleDir.absolutePath,
                    hf_token = null,
                    chipset = null,
                    display_name = importName,
                    model_type = desiredModelType
                )
            ).collect { event ->
                when (event) {
                    is ModelManagerWrapper.PullEvent.Error -> {
                        LocalChatRunnerDebug.emit(
                            "qairt_localfs_pull_error",
                            JSONObject()
                                .put("code", event.code)
                                .put("message", event.message)
                        )
                        error("GenieX LOCALFS import failed (${event.code}): ${event.message}")
                    }
                    else -> Unit
                }
            }
            LocalChatRunnerDebug.emit("qairt_localfs_pull_done")

            var lastError: Throwable? = null
            for (candidate in candidates) {
                LocalChatRunnerDebug.emit(
                    "qairt_get_paths_start",
                    JSONObject().put("candidate", candidate)
                )
                val paths = runCatching { manager.getPaths(candidate) }
                    .onFailure {
                        lastError = it
                        LocalChatRunnerDebug.emit(
                            "qairt_get_paths_failed",
                            JSONObject()
                                .put("candidate", candidate)
                                .put("error", it.message.orEmpty())
                        )
                    }
                    .getOrNull()
                val cachedModel = paths?.model_path
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?.takeIf { it.isFile && it.length() > 0L }
                if (paths != null && cachedModel != null) {
                    val cachedReadiness = QairtBundleReadinessAnalyzer.analyze(
                        requireNotNull(cachedModel.parentFile) {
                            "GenieX QAIRT cache model has no parent directory."
                        }
                    )
                    if (!cachedReadiness.canLoad) {
                        val removeResult = runCatching { manager.remove(candidate) }.getOrNull()
                        LocalChatRunnerDebug.emit(
                            "qairt_localfs_cache_incomplete",
                            JSONObject()
                                .put("candidate", candidate)
                                .put("modelPath", cachedModel.absolutePath)
                                .put("removeResult", removeResult)
                                .put("diagnostic", cachedReadiness.diagnosticSummary())
                        )
                        error(
                            "GenieX LOCALFS import produced an incomplete QAIRT cache: " +
                                cachedReadiness.diagnosticSummary()
                        )
                    }
                    LocalChatRunnerDebug.emit(
                        "qairt_get_paths_ok",
                        JSONObject()
                            .put("candidate", candidate)
                            .put("modelName", paths.model_name.orEmpty())
                            .put("modelPath", cachedModel.absolutePath)
                            .put("tokenizerPath", paths.tokenizer_path.orEmpty())
                            .put("runtimeId", paths.runtime_id.orEmpty())
                            .put("modelType", paths.model_type.name.lowercase())
                            .put("mmprojPath", paths.mmproj_path.orEmpty())
                    )
                    if (paths.model_type != desiredModelType) {
                        LocalChatRunnerDebug.emit(
                            "qairt_imported_registration_type_overridden",
                            JSONObject()
                                .put("candidate", candidate)
                                .put("registeredModelType", paths.model_type.name.lowercase())
                                .put("effectiveModelType", desiredModelType.name.lowercase())
                        )
                    }
                    return@runBlocking PreparedGenieXModel(
                        modelName = paths.model_name.orEmpty().ifBlank { candidate },
                        modelPath = cachedModel.absolutePath,
                        tokenizerPath = paths.tokenizer_path?.takeIf { it.isNotBlank() },
                        runtimeId = paths.runtime_id.orEmpty().ifBlank { RuntimeIdValue.QAIRT.value.orEmpty() },
                        modelType = desiredModelType,
                        mmprojPath = paths.mmproj_path?.takeIf { it.isNotBlank() }
                    )
                }
            }
            error(
                "GenieX LOCALFS import did not expose model paths. Tried ${candidates.joinToString(", ")}" +
                    (lastError?.message?.let { ": $it" } ?: "")
            )
        }
    }

internal fun GenieXChatRunner.qairtModelNameCandidates(bundleDir: File): List<String> = buildList {
        val metadata = File(bundleDir, "metadata.json")
        val root = runCatching { JSONObject(metadata.readText()) }.getOrNull()
        root?.optString("model_name")?.takeIf { it.isNotBlank() }?.let(::add)
        root?.optString("model_id")?.takeIf { it.isNotBlank() }?.let(::add)
        add(bundleDir.name)
        bundleDir.name.substringBefore("-geniex").takeIf { it.isNotBlank() }?.let(::add)
    }.distinct()

package com.muyuchat.mca

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.muyuchat.core.engine.GenerationParams
import org.json.JSONObject
import com.muyuchat.feature.modelhub.GenerationPresetUiItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** JSON key of the imported prompt bundle; kept out of the sampler parser's key set. */
internal const val PROMPT_APPENDIX_KEY: String = "prompt_appendix"

internal fun generationPresetSummary(paramsJson: String): String {
    val params = GenerationParams.fromJson(paramsJson, GenerationParams())
    return "T ${params.temperature} · top_p ${params.topP} · top_k ${params.topK} · 输出 ${params.nPredict}"
}

/** Shared projection for the model-hub presets tab and the standalone preset page. */
internal fun generationPresetUiItems(
    records: List<GenerationPresetRecord>
): List<GenerationPresetUiItem> = records.map { preset ->
    GenerationPresetUiItem(
        id = preset.id,
        name = preset.name,
        summary = generationPresetSummary(preset.paramsJson),
        boundLocalModelIds = preset.boundLocalModelIds,
        boundCloudModelIds = preset.boundCloudModelIds,
        updatedAt = preset.updatedAt
    )
}

internal fun importGenerationPresetFor(
    scope: CoroutineScope,
    application: Application,
    uiStateWriter: UiStateWriter<GenerationPresetFields>,
    presetStore: GenerationPresetStore,
    uriString: String
) = importGenerationPresetsFor(
    scope = scope,
    application = application,
    uiStateWriter = uiStateWriter,
    presetStore = presetStore,
    uriStrings = listOf(uriString)
)

/**
 * Real file name of a picked document, or null when the provider exposes none.
 *
 * `Uri.lastPathSegment` cannot stand in for this: SAF providers put their opaque document id there
 * (`document:1000156859`), which is exactly the text that used to reach the preset list. Injected as
 * a `(Uri) -> String?` lambda so the naming order stays pure and unit-testable.
 */
private fun openableDisplayNameReader(application: Application): (Uri) -> String? = { uri ->
    runCatching {
        application.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()
}

/**
 * Batch import: one picker result, one store write, one status line.
 *
 * Importing presets one at a time meant one picker round-trip and one status message per file.
 * Each file still parses independently, so one unreadable file no longer aborts the rest; the
 * caller sees "已导入 N 个，M 个失败" instead of a single error.
 *
 * Both dialects are accepted: a Tavern chat-completion preset goes through
 * [SillyTavernPresetAdapter] (clamped values, recorded ignored/unsupported keys) and an MCA preset
 * file is parsed with the same parameter contract the runtime uses. The file name is the preset
 * name in both cases (the existing `GenerationPresetNaming` rules); a name declared inside the JSON
 * is reported by the adapter but never overrides the file name.
 */
internal fun importGenerationPresetsFor(
    scope: CoroutineScope,
    application: Application,
    uiStateWriter: UiStateWriter<GenerationPresetFields>,
    presetStore: GenerationPresetStore,
    uriStrings: List<String>
) {
    val targets = uriStrings.filter { it.isNotBlank() }.distinct()
    if (targets.isEmpty()) return
    val readDisplayName = openableDisplayNameReader(application)
    scope.launch(Dispatchers.IO) {
        val imported = mutableListOf<GenerationPresetRecord>()
        val failures = mutableListOf<String>()
        var report = GenerationPresetImportReport()
        for (uriString in targets) {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            // Resolved once per file: it names both the imported record and any failure line.
            val displayName = uri?.let(readDisplayName)
            val fallbackName = GenerationPresetNaming.fallbackName(displayName, uri?.lastPathSegment)
            runCatching {
                val target = uri ?: error("无法识别所选文件。")
                val raw = requireNotNull(application.contentResolver.openInputStream(target)) {
                    "无法读取所选预设文件。"
                }.use { readGenerationPresetJson(it) }
                val name = GenerationPresetNaming.recordName(fallbackName)
                when (val parsed = SillyTavernPresetAdapter.adapt(raw)) {
                    is GenerationPresetParseResult.Rejected -> error(parsed.reason)
                    is GenerationPresetParseResult.Tavern -> {
                        val mapping = parsed.mapping
                        ImportedGenerationPreset(
                            name = name,
                            // The prompt bundle travels with the preset so a Tavern "jailbreak
                            // preset" keeps its text instead of being reduced to bare samplers.
                            // The runtime parameter parser ignores the extra key; the preset
                            // manager reads it to offer "apply to assistant".
                            paramsJson = JSONObject(mapping.params.toAssistantGenerationJson())
                                .apply {
                                    mapping.promptAppendix?.let { put(PROMPT_APPENDIX_KEY, it) }
                                }
                                .toString(),
                            appliedFields = mapping.mappedKeys.size,
                            clampedFields = mapping.clamped.size,
                            ignoredFields = mapping.ignoredKeys,
                            unsupportedFields = mapping.unsupported.map(TavernPresetUnsupported::key),
                            promptChars = mapping.promptChars
                        )
                    }
                    is GenerationPresetParseResult.Mca -> {
                        val mapping = parsed.mapping
                        ImportedGenerationPreset(
                            name = name,
                            paramsJson = mapping.params.toAssistantGenerationJson(),
                            appliedFields = mapping.mappedKeys.size,
                            clampedFields = 0,
                            ignoredFields = mapping.ignoredKeys,
                            unsupportedFields = mapping.unsupported.map(TavernPresetUnsupported::key)
                        )
                    }
                }
            }.onSuccess { preset ->
                imported += GenerationPresetRecord(name = preset.name, paramsJson = preset.paramsJson)
                report = report.plus(preset)
            }.onFailure { error ->
                // Same normalization as the success path: no provider ids in user-visible text.
                failures += "$fallbackName（${error.message ?: "请确认文件格式"}）"
            }
        }
        // One read-modify-write for the whole batch; the store lock only guards single calls.
        if (imported.isNotEmpty()) {
            presetStore.save(presetStore.load() + imported)
        }
        val records = presetStore.load()
        val message = generationPresetImportMessage(imported, report, failures)
        uiStateWriter.update {
            it.copy(
                generationPresets = records,
                statusMessage = message
            )
        }
    }
}

/**
 * Status line of one batch import.
 *
 * Pure and kept next to the import loop so the legacy batch wording and the new per-field counts
 * ("应用 N 项 / 忽略 N 项 / 不支持 N 项") are testable without Android: one unreadable file must
 * not hide what the readable ones imported, and a value that was clamped must be visible.
 */
internal fun generationPresetImportMessage(
    imported: List<GenerationPresetRecord>,
    report: GenerationPresetImportReport,
    failures: List<String>
): String {
    val detail = report.detailSuffix()
    return when {
        imported.isEmpty() -> "模型预设导入失败：${failures.joinToString("；")}"
        failures.isEmpty() && imported.size == 1 -> "已导入模型预设：${imported.first().name}$detail"
        failures.isEmpty() -> "已导入 ${imported.size} 个模型预设$detail"
        else -> "已导入 ${imported.size} 个模型预设$detail，${failures.size} 个失败：" +
            failures.joinToString("；")
    }
}

internal fun deleteGenerationPresetFor(
    uiStateWriter: UiStateWriter<GenerationPresetFields>,
    presetStore: GenerationPresetStore,
    presetId: String
) {
    val remaining = presetStore.load().filterNot { it.id == presetId }
    presetStore.save(remaining)
    uiStateWriter.update {
        it.copy(
            generationPresets = remaining,
            statusMessage = "已删除预设；已应用到模型的参数不会自动回退。"
        )
    }
}

internal fun saveGenerationPresetBindingsFor(
    application: Application,
    uiStateWriter: UiStateWriter<GenerationPresetFields>,
    presetStore: GenerationPresetStore,
    modelGenerationProfileStore: ModelGenerationProfileStore,
    presetId: String,
    name: String,
    boundLocalModelIds: Set<String>,
    boundCloudModelIds: Set<String>
) {
    val presets = presetStore.load()
    val current = presets.firstOrNull { it.id == presetId } ?: return
    val updated = current.copy(
        name = name.trim().take(64).ifBlank { current.name },
        boundLocalModelIds = boundLocalModelIds,
        boundCloudModelIds = boundCloudModelIds,
        updatedAt = System.currentTimeMillis()
    )
    val records = presets.map { if (it.id == presetId) updated else it }
    presetStore.save(records)

    val presetJson = runCatching { JSONObject(current.paramsJson) }.getOrNull()
    if (presetJson != null) {
        boundLocalModelIds.forEach { modelId ->
            val key = ModelGenerationProfileKey.local(modelId)
            val base = modelGenerationProfileStore.loadOrCreate(key, GenerationParams())
            modelGenerationProfileStore.save(key, modelGenerationParamsFromJson(presetJson, base))
        }
        boundCloudModelIds.forEach { modelId ->
            val key = ModelGenerationProfileKey.cloud(modelId)
            val base = modelGenerationProfileStore.loadOrCreate(key, GenerationParams())
            modelGenerationProfileStore.save(key, modelGenerationParamsFromJson(presetJson, base))
        }
    }

    val state = uiStateWriter.current
    val currentBackend = state.selectedChatBackend
    val currentModelId = state.currentChatModelId()
    val currentBound = currentModelId != null && when (currentBackend) {
        ChatBackend.LOCAL -> currentModelId in boundLocalModelIds
        ChatBackend.CLOUD -> currentModelId in boundCloudModelIds
    }
    if (currentBound && presetJson != null && currentModelId != null) {
        val key = GenerationPreferences.modelGenerationProfileKey(currentBackend, currentModelId)
        val modelParams = key?.let { modelGenerationProfileStore.loadOrCreate(it, state.params) } ?: state.params
        val effectiveParams = state.selectedAssistant()?.toGenerationParams(modelParams) ?: modelParams
        GenerationPreferences.persistGenerationParams(application, effectiveParams)
        uiStateWriter.update {
            it.copy(
                generationPresets = records,
                params = effectiveParams,
                statusMessage = "已保存预设绑定并应用到选中模型；角色卡覆盖仍然优先。"
            )
        }
    } else {
        uiStateWriter.update {
            it.copy(
                generationPresets = records,
                statusMessage = "已保存预设绑定并应用到选中模型；角色卡覆盖仍然优先。"
            )
        }
    }
}

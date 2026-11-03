package com.muyuchat.mca

import android.app.Application
import android.net.Uri
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.feature.chat.SillyTavernPresetCodec
import com.muyuchat.feature.chat.readSillyTavernPresetJson
import com.muyuchat.feature.modelhub.GenerationPresetUiItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

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
) {
    scope.launch(Dispatchers.IO) {
        val result = runCatching {
            val uri = Uri.parse(uriString)
            val json = requireNotNull(application.contentResolver.openInputStream(uri)) {
                "无法读取所选预设文件。"
            }.use { readSillyTavernPresetJson(it) }
            SillyTavernPresetCodec.parse(
                rawJson = json,
                fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "模型预设"
            )
        }
        result.onSuccess { preset ->
            val defaults = GenerationParams()
            val params = defaults.copy(
                nPredict = preset.maxTokens?.coerceIn(16, 65_536) ?: defaults.nPredict,
                temperature = preset.temperature ?: defaults.temperature,
                topK = preset.topK ?: defaults.topK,
                topP = preset.topP ?: defaults.topP,
                minP = preset.minP ?: defaults.minP,
                repeatPenalty = preset.repeatPenalty ?: defaults.repeatPenalty,
                presencePenalty = preset.presencePenalty ?: defaults.presencePenalty,
                frequencyPenalty = preset.frequencyPenalty ?: defaults.frequencyPenalty,
                stopWords = preset.stopWords ?: defaults.stopWords
            )
            val record = GenerationPresetRecord(
                name = preset.name.take(64),
                paramsJson = params.toAssistantGenerationJson()
            )
            val records = presetStore.load() + record
            presetStore.save(records)
            val promptNote = if (preset.promptAppendix != null) "；提示词请在角色卡中导入" else ""
            uiStateWriter.update {
                it.copy(
                    generationPresets = records,
                    statusMessage = "已导入模型预设：${record.name}$promptNote"
                )
            }
        }.onFailure { error ->
            uiStateWriter.update {
                it.copy(statusMessage = "模型预设导入失败：${error.message ?: "请确认文件格式。"}")
            }
        }
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

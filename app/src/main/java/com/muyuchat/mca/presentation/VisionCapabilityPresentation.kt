package com.muyuchat.mca.presentation

// Vision capability wording for the chat screen, moved verbatim from MainActivity during P2.
//
// This is where the app says whether a local model can accept an image. Note what it says
// about devices: "所有兼容 ARM64 机型默认开放图片输入". Local vision is opened for every
// compatible device and the answer depends on the loaded model, not on which chip the device
// has. The chipset never appears in these branches, which is the point.


import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.mca.ChatBackend
import com.muyuchat.mca.CloudModelKind
import com.muyuchat.mca.MainUiState
import com.muyuchat.core.engine.liteRtVisionInputAvailable

internal data class VisionCapabilityUi(
    val label: String,
    val detail: String,
    val ready: Boolean
)

internal fun liteRtVisionInputReadyForUi(nativeStats: org.json.JSONObject?): Boolean =
    nativeStats?.let { liteRtVisionInputAvailable(it.toString()) } == true

internal fun liteRtVisionVerifiedReadyForUi(nativeStats: org.json.JSONObject?): Boolean =
    liteRtVisionInputReadyForUi(nativeStats) &&
        nativeStats?.optBoolean("visionReady", false) == true

internal fun MainUiState.chatVisionCapability(): VisionCapabilityUi {
    if (selectedChatBackend == ChatBackend.CLOUD) {
        val cloudModel = cloudModels.firstOrNull {
            it.id == selectedCloudChatModelId && it.kind == CloudModelKind.CHAT && it.configured
        }
        return if (cloudModel != null) {
            if (cloudModel.supportsVision) {
                VisionCapabilityUi(
                    label = "云端多模态",
                    detail = "图片将发送给当前云端模型，识图能力由服务商和模型决定。",
                    ready = true
                )
            } else {
                VisionCapabilityUi(
                    label = "云端识图未启用",
                    detail = "请编辑云端推理引擎，开启支持图片输入。",
                    ready = false
                )
            }
        } else {
            VisionCapabilityUi(
                label = "未加载云端模型",
                detail = "请在模型管理加载支持图片输入的云端推理引擎。",
                ready = false
            )
        }
    }

    val loadedModel = models.firstOrNull { it.id == loadedModelId }
    val nativeStats = runCatching { org.json.JSONObject(nativeStatsJson) }.getOrNull()
    val nativeVisionReady = nativeStats?.optBoolean("visionReady", false) == true
    // Use the same admission predicate as the actual send path. Transport
    // readiness alone only proves that the SDK can serialize ImageFile; a
    // LiteRT package whose metadata explicitly says it has no visual encoder
    // must not appear ready in the chat UI.
    val liteRtImageTransportReady = liteRtVisionInputReadyForUi(nativeStats)
    val visionReadyForUi = if (loadedModel?.runtime == ChatModelRuntime.LITERT_LM) {
        liteRtVisionVerifiedReadyForUi(nativeStats)
    } else {
        nativeVisionReady
    }
    val liteRtKnownTextOnly = nativeStats?.optBoolean("visionModelKnownTextOnly", false) == true
    return when {
        loadedModel == null -> VisionCapabilityUi(
            label = "未加载本地模型",
            detail = "请加载 MNN 多模态包，或加载多模态 GGUF 并绑定匹配 mmproj。",
            ready = false
        )
        loadedModel.runtime == ChatModelRuntime.LITERT_LM && liteRtKnownTextOnly -> VisionCapabilityUi(
            label = "当前模型不支持识图",
            detail = "这是纯文本 LiteRT-LM 包，不含图像视觉权重。请更换视觉版模型，或改用完整 MNN/GGUF 多模态模型。",
            ready = false
        )
        loadedModel.acceptsImageInput(visionReadyForUi) -> VisionCapabilityUi(
            label = "本地识图已就绪",
            detail = if (loadedModel.runtime == ChatModelRuntime.MNN) {
                "MNN 视觉组件已加载；所有兼容 ARM64 机型默认开放图片输入。"
            } else {
                "当前本地模型已启用视觉模块。"
            },
            ready = true
        )
        loadedModel.runtime == ChatModelRuntime.LITERT_LM && liteRtImageTransportReady -> VisionCapabilityUi(
            label = "LiteRT 图像传输可用",
            detail = "图片输入已接通；当前模型尚未通过真实图片验证，首次请求会检查模型视觉能力。",
            ready = true
        )
        loadedModel.runtime == ChatModelRuntime.LITERT_LM -> VisionCapabilityUi(
            label = "LiteRT 图像通道未就绪",
            detail = "请重新加载模型后重试图片输入；若仍失败，请切换到支持视觉的 GGUF/MNN/QNN。",
            ready = false
        )
        loadedModel.runtime == ChatModelRuntime.MNN -> VisionCapabilityUi(
            label = "MNN 视觉组件未就绪",
            detail = "请加载包含可读 visual.mnn 的完整多模态包；就绪后即可发送图片。",
            ready = false
        )
        !loadedModel.visionProjectorPath.isNullOrBlank() -> VisionCapabilityUi(
            label = "视觉投影器待启用",
            detail = "已绑定 mmproj，请重新加载模型后再发图。",
            ready = false
        )
        else -> VisionCapabilityUi(
            label = "本地识图未启用",
            detail = "纯文本模型不能识图，请使用 MNN 多模态包或绑定匹配 mmproj。",
            ready = false
        )
    }
}

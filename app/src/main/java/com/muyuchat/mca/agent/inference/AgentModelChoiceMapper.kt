package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudModelKind
import com.muyuchat.mca.CloudModelRecord

/** Read-only projection of one currently loaded local model. */
internal data class AgentLoadedLocalModel(
    val modelId: String,
    val runtimeBackendId: String,
    val identityHash: String,
    val displayName: String,
    val contextWindowTokens: Int? = null
)

/** Read-only catalog source; implementations must never write chat/model selection. */
internal interface AgentModelCatalogSnapshot {
    fun loadedLocalModel(): AgentLoadedLocalModel?
    fun cloudModels(): List<CloudModelRecord>
}

internal data class AgentModelChoice(
    val binding: AgentModelBinding,
    val displayName: String,
    val isLocal: Boolean,
    val note: String? = null
)

/**
 * Declared native tool capability of one API format this build actually implements.
 *
 * This is a wire-capability table keyed only by [CloudApiFormat]; it is deliberately *not* a
 * device/chipset/model-name check, and a missing entry never hides a model - it only keeps the
 * prompted compatibility path.
 */
internal data class AgentNativeToolCapability(
    val supported: Boolean,
    val evidenceVersion: String,
    val note: String,
    /**
     * True when this build verified the whole round against an auditable controlled provider
     * server (declared tools, terminal `function_call` reconstruction, paired
     * `function_call_output`). Such a format may negotiate NATIVE without a per-model record;
     * a format without it still needs the real-provider evidence store.
     */
    val controlledVerification: Boolean = false
)

/**
 * Provider capability table for the strict native tool wire (N05d).
 *
 * `supported = true` requires the whole executable round: tool declarations on the wire, a complete
 * terminal `function_call` reconstruction, and the paired `function_call_output` on the next turn.
 * The current evidence for Responses is the controlled wire transport test in this module; a real
 * provider run is NOT_RUN, which the capability note and the binding evidence version record.
 */
internal object AgentNativeToolCapabilities {
    private const val RESPONSES_EVIDENCE_VERSION = "n05e-2026-10-29"
    private const val PROMPTED_CHAT_EVIDENCE_VERSION = "prompted-chat-2026-10-28"

    /**
     * Wire implementation gate, keyed only by API format.
     *
     * Responses is implemented end to end and verified against an auditable controlled provider
     * server: the request declares the frozen catalog, a streamed `function_call` is reconstructed
     * only from `response.completed`, its `call_id` is preserved through the transcript, and the
     * next turn sends the paired `function_call_output`. A real provider round is still NOT_RUN, so
     * a model negotiates NATIVE only when the user enabled `supportsTools` for it; failures stay
     * typed and closed (no silent downgrade to the prompted envelope).
     */
    private const val NATIVE_WIRE_ENABLED = true

    fun forFormat(format: CloudApiFormat): AgentNativeToolCapability = when (format) {
        CloudApiFormat.OPENAI_RESPONSES -> AgentNativeToolCapability(
            supported = NATIVE_WIRE_ENABLED,
            evidenceVersion = RESPONSES_EVIDENCE_VERSION,
            note = "Responses 原生工具已接线：tools 声明、terminal function_call 重建、" +
                "call_id 配对与 function_call_output 回注均由受控 Responses 服务验证；" +
                "真实 provider 回合未验证（运行失败会以类型化协议错误终止，不静默降级）。",
            controlledVerification = true
        )
        CloudApiFormat.OPENAI_COMPATIBLE -> AgentNativeToolCapability(
            supported = false,
            evidenceVersion = PROMPTED_CHAT_EVIDENCE_VERSION,
            note = "chat/completions 线缆只承载 prompted 信封：不声明 provider 原生工具，" +
                "工具目录留在提示词中，结果按 prompted 协议回注。"
        )
        else -> AgentNativeToolCapability(
            supported = false,
            evidenceVersion = RESPONSES_EVIDENCE_VERSION,
            note = "该 API 格式尚无原生工具的严格终态策略。"
        )
    }
}

/**
 * Maps the currently loaded local model plus configured cloud models into Agent model choices.
 *
 * Rules (architecture 6.1, N05a/N05d capability-truth rule):
 *  * loaded local text model → PROMPTED_V1 (the local prompted envelope stays authoritative);
 *  * cloud format with a declared native capability *and* `supportsTools` enabled by the user →
 *    NATIVE, because the production wire then declares tools, rebuilds provider calls and sends the
 *    paired results on the next turn;
 *  * the same format without `supportsTools` → PROMPTED_V1 (configuration does not opt in, so the
 *    run must not speak a protocol the user did not enable);
 *  * a configured `OPENAI_COMPATIBLE` chat record → PROMPTED_V1 over the `chat/completions` wire
 *    (typed adapter: the prompted envelope is the only protocol that wire carries);
 *  * every other format → NONE until its strict terminal policy exists.
 *
 * This is a pure projection: it never mutates chat selection, cloud config or the active backend,
 * and it never consults device, chipset or model-name heuristics.
 */
internal object AgentModelChoiceMapper {
    fun choices(catalog: AgentModelCatalogSnapshot): List<AgentModelChoice> = buildList {
        catalog.loadedLocalModel()?.let { local ->
            if (local.modelId.isNotBlank() && local.identityHash.isNotBlank()) {
                add(
                    AgentModelChoice(
                        binding = AgentModelBinding(
                            providerId = LOCAL_PROVIDER_ID,
                            modelId = local.modelId,
                            runtime = local.runtimeBackendId,
                            assetFingerprint = local.identityHash,
                            protocolMode = AgentProtocolMode.PROMPTED_V1,
                            modelDisplayName = local.displayName.ifBlank { local.modelId },
                            contextWindowTokens = local.contextWindowTokens
                        ),
                        displayName = local.displayName.ifBlank { local.modelId },
                        isLocal = true,
                        note = "本地文本模型默认使用 PROMPTED_V1 协议。"
                    )
                )
            }
        }
        catalog.cloudModels()
            .filter { it.kind == CloudModelKind.CHAT && it.configured }
            .forEach { model ->
                val capability = AgentNativeToolCapabilities.forFormat(model.apiFormat)
                val protocol = when {
                    // Responses keeps its native/prompted split; a compatible record always runs the
                    // prompted envelope over chat/completions (no provider-side tool round).
                    model.apiFormat == CloudApiFormat.OPENAI_RESPONSES ->
                        if (capability.supported && model.supportsTools) {
                            AgentProtocolMode.NATIVE
                        } else {
                            AgentProtocolMode.PROMPTED_V1
                        }
                    model.apiFormat == CloudApiFormat.OPENAI_COMPATIBLE -> AgentProtocolMode.PROMPTED_V1
                    else -> AgentProtocolMode.NONE
                }
                add(
                    AgentModelChoice(
                        binding = AgentModelBinding(
                            providerId = "cloud:${model.apiFormat.name.lowercase()}",
                            modelId = model.modelName,
                            runtime = CLOUD_RUNTIME_ID,
                            assetFingerprint = cloudAssetFingerprint(model),
                            protocolMode = protocol,
                            modelDisplayName = model.displayName.ifBlank { model.modelName },
                            samplingJson = "{}",
                            capabilityEvidenceVersion = capability.evidenceVersion
                        ),
                        displayName = model.displayName.ifBlank { model.modelName },
                        isLocal = false,
                        note = when {
                            model.apiFormat == CloudApiFormat.OPENAI_COMPATIBLE -> capability.note
                            protocol == AgentProtocolMode.NATIVE -> capability.note
                            protocol == AgentProtocolMode.NONE -> capability.note
                            model.supportsTools && !capability.supported ->
                                "该 API 格式尚未实现原生工具线缆；按 prompted 兼容路径执行。"
                            else ->
                                "该模型未开启 supportsTools；按 prompted 兼容路径执行，不声明原生工具能力。"
                        }
                    )
                )
            }
    }

    private fun cloudAssetFingerprint(model: CloudModelRecord): String =
        "cloud:${model.id}:${model.updatedAt}:${model.modelName}"

    private const val LOCAL_PROVIDER_ID = "mca-local"
    private const val CLOUD_RUNTIME_ID = "cloud"
}

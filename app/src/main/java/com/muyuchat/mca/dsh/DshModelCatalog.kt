package com.muyuchat.mca.dsh

import com.muyuchat.core.dsh.contract.DshGatewayModel
import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudModelKind
import com.muyuchat.mca.CloudModelRecord
import com.muyuchat.mca.CloudModelWindow

/**
 * Honest model catalog exposed through the gateway's `/v1/models`.
 *
 * Only models that can be served right now are listed:
 *  - `mca-local:<modelId>` for the single currently loaded local model (never a not-loaded one);
 *  - `mca-cloud:<recordId>` for fully configured cloud CHAT models in the existing
 *    [com.muyuchat.mca.CloudApiStore] (never an image model, never an incomplete configuration).
 *
 * `nativeToolCalling` is true only for the OpenAI Responses cloud path when the user enabled tool
 * support there. Everything else is `false` with [DshPromptedToolProtocol.PROTOCOL_LABEL], so DSH
 * and the user can see that tool calls are prompt-compatibility, not a native template. Ids are
 * stable and namespaced; no base URL, API key or other secret is ever included.
 *
 * ## Lifecycle / refresh limitation (verified against the frozen official DSH source)
 *
 * This catalog is a live read: nothing is cached, and the gateway's `/v1/models` therefore reflects
 * a model loaded or unloaded after the gateway started. The official `llm-pi-ai` provider,
 * however, materializes `Provider.getModels()` from the resolved settings document
 * (`provider.ts`: `getModels: () => spec.models`; catalog-owned dynamic refresh is deliberately
 * dropped), and its model-discovery module documents that discovery is "neither ... a catalog
 * refresh ... Nothing here is stored; cordis.patch.yml remains the only thing that decides what a
 * route serves". The MCA host seeds that overlay once per DSH host generation
 * (`DshGatewayConfigSeeder` fetches `/models` and writes the `mca:` route model list, including
 * `models: []`). Consequence: loading a model after DSH reached READY makes it visible on the live
 * gateway endpoint but NOT selectable inside the running DSH provider until the overlay is
 * reseeded / the host generation is restarted. The only official no-restart path is the
 * user-initiated "fetch available models" action on a provider draft, which adopts candidates into
 * settings and does not hot-refresh the running provider. The adapter cannot push a catalog
 * refresh into DSH, and it must not restart or kill a busy host to fake one.
 */
internal object DshModelCatalog {
    const val LOCAL_ID_PREFIX = "mca-local:"
    const val CLOUD_ID_PREFIX = "mca-cloud:"

    private const val MAX_DISPLAY_NAME_CHARS = 120

    fun localModelId(modelId: String): String = LOCAL_ID_PREFIX + modelId

    fun cloudModelId(recordId: String): String = CLOUD_ID_PREFIX + recordId

    fun build(
        local: DshLocalModel?,
        cloudModels: List<CloudModelRecord>,
        installed: List<DshLocalModel> = emptyList()
    ): List<DshGatewayModel> {
        val models = ArrayList<DshGatewayModel>(cloudModels.size + installed.size + 1)
        local?.takeIf { it.modelId.isNotBlank() }?.let { model ->
            models += DshGatewayModel(
                id = localModelId(model.modelId),
                displayName = displayNameOf(model.displayName.ifBlank { model.modelId }),
                contextWindow = model.contextWindow?.takeIf { it > 0 },
                maxOutputTokens = model.maxOutputTokens?.takeIf { it > 0 },
                supportsTools = true,
                nativeToolCalling = false,
                toolProtocolLabel = DshPromptedToolProtocol.PROTOCOL_LABEL
            )
        }
        // Downloaded-but-not-loaded local models are selectable in DSH too: the official provider
        // freezes this list per host generation, so it must contain every installed model up front.
        // These entries carry no context window (the live profile is unknown); the gateway sidecar
        // still reports the real context of whatever ends up loaded.
        val activeId = local?.modelId
        installed.asSequence()
            .filter { it.modelId.isNotBlank() && it.modelId != activeId }
            .distinctBy { it.modelId }
            .forEach { model ->
                models += DshGatewayModel(
                    id = localModelId(model.modelId),
                    displayName = displayNameOf(model.displayName.ifBlank { model.modelId }),
                    contextWindow = model.contextWindow?.takeIf { it > 0 },
                    maxOutputTokens = model.maxOutputTokens?.takeIf { it > 0 },
                    supportsTools = true,
                    nativeToolCalling = false,
                    toolProtocolLabel = DshPromptedToolProtocol.PROTOCOL_LABEL
                )
            }
        val seen = HashSet<String>()
        val cloud = cloudModels
            .asSequence()
            .filter { it.kind == CloudModelKind.CHAT && it.configured && it.id.isNotBlank() }
            .filter { seen.add(it.id) }
            .map { record ->
                val native = supportsNativeCloudTools(record)
                // The advertised window is what lets DSH compact on its own and stops it from asking
                // for an output budget the window cannot hold. Unknown gateways get the 128 k fallback;
                // `CloudModelWindow` is the single place to add per-model overrides.
                val window = CloudModelWindow.windowTokens(record.modelName)
                DshGatewayModel(
                    id = cloudModelId(record.id),
                    displayName = displayNameOf(record),
                    contextWindow = window,
                    maxOutputTokens = CloudModelWindow.outputTokens(window),
                    supportsTools = true,
                    nativeToolCalling = native,
                    toolProtocolLabel = if (native) null else DshPromptedToolProtocol.PROTOCOL_LABEL
                )
            }
            .sortedWith(
                compareBy<DshGatewayModel> { it.displayName.lowercase() }.thenBy { it.id }
            )
            .toList()
        models += cloud
        return models
    }

    /** Native tools exist only on the existing OpenAI Responses transport with user-enabled tools. */
    fun supportsNativeCloudTools(record: CloudModelRecord): Boolean =
        record.kind == CloudModelKind.CHAT &&
            record.apiFormat == CloudApiFormat.OPENAI_RESPONSES &&
            record.supportsTools

    /** Stable revision over ids and advertised capabilities; changes when the catalog changes. */
    fun revision(models: List<DshGatewayModel>): String =
        sha256Hex(
            models.joinToString("\n") { model ->
                listOf(
                    model.id,
                    model.displayName,
                    model.contextWindow?.toString().orEmpty(),
                    model.maxOutputTokens?.toString().orEmpty(),
                    model.supportsTools.toString(),
                    model.nativeToolCalling.toString(),
                    model.toolProtocolLabel.orEmpty()
                ).joinToString("|")
            }
        ).take(16)

    private fun displayNameOf(record: CloudModelRecord): String {
        val configured = record.displayName.trim()
        if (configured.isNotEmpty()) return displayNameOf(configured)
        val provider = record.providerName.trim()
        val model = record.modelName.trim()
        val combined = listOf(provider, model).filter { it.isNotEmpty() }.joinToString(" ")
        return displayNameOf(combined.ifBlank { record.apiFormat.label })
    }

    private fun displayNameOf(raw: String): String {
        val cleaned = raw.filter { !it.isISOControl() }.trim().ifEmpty { "MCA model" }
        return if (cleaned.length <= MAX_DISPLAY_NAME_CHARS) cleaned else cleaned.take(MAX_DISPLAY_NAME_CHARS - 1) + "…"
    }
}

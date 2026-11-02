package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.CloudApiStore
import com.muyuchat.mca.CloudModelKind

/**
 * Request-scoped cloud configuration for one frozen [AgentModelBinding] (architecture 5.2).
 *
 * Unlike the legacy [AgentCloudConfigSource] ("whatever the chat page currently selected"), this
 * resolver is evaluated per Agent request from the binding itself, so a run created against record
 * A can never silently execute against the store's current selection B.
 */
internal fun interface AgentCloudRequestConfigResolver {
    /**
     * Exact configuration for [binding], or null when the frozen binding cannot be honoured.
     *
     * Null is authoritative: the caller must fail `MODEL_NOT_LOADED` and must not fall back to a
     * different model's base URL or credential. A binding that carries no record id at all may
     * still opt into the legacy provider-level configuration inside the implementation.
     */
    fun resolve(binding: AgentModelBinding): CloudApiConfig?
}

/**
 * Selects the exact configured chat record encoded in `providerId == "cloud:<recordId>"` (P05).
 *
 * The config is built with [com.muyuchat.mca.CloudModelRecord.toChatConfig] from
 * [CloudApiStore.loadModels], so base URL, wire model name, capability flags and the API key all
 * belong to that one record. The current store configuration is consulted only when the binding
 * carries no record id (legacy provider-level selection); a present-but-unknown, non-chat or
 * unconfigured record returns null instead of borrowing another model's configuration.
 *
 * Credentials stay inside the transport request: the resolved config is never copied into an
 * Agent binding, journal record or UI projection.
 */
internal class CloudStoreModelConfigResolver(
    private val store: CloudApiStore,
    private val legacySource: AgentCloudConfigSource = CloudApiStoreConfigSource(store)
) : AgentCloudRequestConfigResolver {

    override fun resolve(binding: AgentModelBinding): CloudApiConfig? {
        val recordId = cloudModelRecordId(binding.providerId) ?: return legacySource.load()
        val record = store.loadModels().firstOrNull { it.id == recordId } ?: return null
        if (record.kind != CloudModelKind.CHAT || !record.configured) return null
        return record.toChatConfig()
    }
}

/**
 * Extracts `<recordId>` from the catalog's `cloud:<recordId>` provider id.
 *
 * Returns null when the provider id does not pin a record (`cloud:`, legacy `cloud`, or any local
 * provider id); only that case is allowed to fall back to the provider-level store config.
 */
internal fun cloudModelRecordId(providerId: String): String? {
    if (!providerId.startsWith(AGENT_CLOUD_PROVIDER_PREFIX)) return null
    return providerId.removePrefix(AGENT_CLOUD_PROVIDER_PREFIX).takeIf { it.isNotBlank() }
}

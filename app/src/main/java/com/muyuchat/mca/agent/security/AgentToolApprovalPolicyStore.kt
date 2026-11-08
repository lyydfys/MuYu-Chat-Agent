package com.muyuchat.mca.agent.security

import android.content.Context

/**
 * Persisted global default + per-tool exceptions for tool approval.
 *
 * The document is the versioned JSON of [ToolApprovalPolicyCodec]; a missing or corrupt value
 * decodes to "everything asks", so a broken preference can never silently auto-allow a tool.
 * The store caches the decoded policy keyed by the raw string, so the hot path (one call per tool
 * invocation) never re-parses an unchanged document.
 *
 * There is deliberately no UI in this change: the settings surface that writes these values is a
 * separate task, and until it exists every install keeps the ASK default.
 */
class AgentToolApprovalPolicyStore(context: Context) : AgentToolApprovalPolicySource {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private var cachedRaw: String? = null
    private var cachedPolicy: ToolApprovalPolicy = ToolApprovalPolicy.DEFAULT

    override fun current(): ToolApprovalPolicy {
        val raw = runCatching { preferences.getString(KEY_POLICY, null) }.getOrNull()
        if (raw == cachedRaw) return cachedPolicy
        val decoded = ToolApprovalPolicyCodec.decode(raw)
        cachedRaw = raw
        cachedPolicy = decoded
        return decoded
    }

    /** Replaces the whole policy. The next [current] call observes it. */
    fun setPolicy(policy: ToolApprovalPolicy) {
        val encoded = ToolApprovalPolicyCodec.encode(policy)
        preferences.edit().putString(KEY_POLICY, encoded).apply()
        cachedRaw = encoded
        cachedPolicy = policy
    }

    fun setDefaultMode(mode: ToolApprovalMode): ToolApprovalPolicy {
        val updated = current().withDefaultMode(mode)
        setPolicy(updated)
        return updated
    }

    /** [mode] null removes the exception and restores the global default for that tool. */
    fun setOverride(toolKey: String, mode: ToolApprovalMode?): ToolApprovalPolicy? {
        if (!ToolApprovalPolicy.isAcceptableToolKey(toolKey)) return null
        if (mode != null && !current().hasOverride(toolKey) &&
            current().perToolOverrides.size >= ToolApprovalPolicy.MAX_TOOL_OVERRIDES
        ) {
            return null
        }
        val updated = current().withOverride(toolKey, mode)
        setPolicy(updated)
        return updated
    }

    companion object {
        /**
         * Shared with the settings surface (a feature module cannot depend on the app module, so
         * the name and key are public constants that both sides must spell identically).
         */
        const val PREFERENCES_NAME: String = "mca_agent_tool_approval"
        const val KEY_POLICY: String = "policy"
    }
}

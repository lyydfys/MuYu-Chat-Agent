package com.muyuchat.mca

import com.muyuchat.feature.settings.AgentApprovalPresetUi
import com.muyuchat.mca.agent.security.AgentToolApprovalPolicyStore
import com.muyuchat.mca.agent.security.ToolApprovalMode
import com.muyuchat.mca.agent.security.ToolApprovalPolicy

/**
 * Bridge between the settings card's presentation tier and the persisted approval policy.
 *
 * The settings module must not depend on `:app` (architecture Rule 14), so it carries only the tier
 * ids; the real [ToolApprovalPolicy] objects live app-side. This file is the one place that maps
 * between them, which keeps "what the card shows" and "what the broker enforces" provably in sync.
 *
 * Only exact preset matches are reported as a tier. A stored policy that was edited per tool (or
 * written by an older version) is not one of the three presets, and pretending otherwise would
 * silently rewrite the user's exceptions on the next card interaction; those documents show the
 * cautious tier as the safe reading while the write path only happens when the user picks a chip.
 */
internal object AgentApprovalPolicyPresets {

    /** All three presets, in the card's display order. */
    val all: List<Pair<AgentApprovalPresetUi, ToolApprovalPolicy>> = listOf(
        AgentApprovalPresetUi.CAUTIOUS to ToolApprovalPolicy.PRESET_CAUTIOUS,
        AgentApprovalPresetUi.ASK_MONEY_ONLY to ToolApprovalPolicy.PRESET_ASK_MONEY_ONLY,
        AgentApprovalPresetUi.AUTONOMOUS to ToolApprovalPolicy.PRESET_AUTONOMOUS
    )

    /** The policy a freshly picked tier writes. */
    fun policyFor(preset: AgentApprovalPresetUi): ToolApprovalPolicy =
        all.first { (ui, _) -> ui == preset }.second

    /**
     * The tier a stored policy corresponds to, or [AgentApprovalPresetUi.DEFAULT] when it is not an
     * exact preset (per-tool edits, legacy documents).
     */
    fun presetFor(policy: ToolApprovalPolicy): AgentApprovalPresetUi =
        all.firstOrNull { (_, candidate) -> candidate == policy }?.first
            ?: AgentApprovalPresetUi.DEFAULT

    /** True when [policy] is exactly one of the three presets, i.e. the card may show it as chosen. */
    fun isPreset(policy: ToolApprovalPolicy): Boolean = all.any { (_, candidate) -> candidate == policy }

    /** Convenience for the card's `selected` argument. */
    fun selectedFor(store: AgentToolApprovalPolicyStore): AgentApprovalPresetUi =
        presetFor(store.current())

    /** Applies a picked tier to the store; returns the policy that was persisted. */
    fun apply(store: AgentToolApprovalPolicyStore, preset: AgentApprovalPresetUi): ToolApprovalPolicy {
        val policy = policyFor(preset)
        store.setPolicy(policy)
        return policy
    }

    /**
     * Default mode a tier resolves to, exposed for the settings summary line.
     *
     * A `FORBID` default is not offered by any preset, so the label set stays to the three the card
     * shows; this exists so a future per-tool editor can reuse the wording.
     */
    fun modeLabel(mode: ToolApprovalMode): String = when (mode) {
        ToolApprovalMode.ALLOW -> "直接执行"
        ToolApprovalMode.ASK -> "每次确认"
        ToolApprovalMode.FORBID -> "禁止执行"
    }
}

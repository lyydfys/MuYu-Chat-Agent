package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentToolSideEffect
import org.json.JSONObject

/**
 * How a tool call is decided before any approval prompt is created.
 *
 * The three values mirror the per-tool policy of a desktop terminal agent: [ALLOW] executes
 * without asking, [ASK] keeps the existing one-shot approval prompt, and [FORBID] refuses the
 * call outright. The policy is a *user* decision, so it is read from the persisted settings on
 * every decision instead of being frozen into a run.
 */
enum class ToolApprovalMode {
    ALLOW,
    ASK,
    FORBID
}

/**
 * Global default plus per-tool exceptions.
 *
 * Evaluation order is fixed and load-bearing:
 *  1. an exact [perToolOverrides] entry wins over everything, including read-only shortcuts;
 *  2. a side-effect class listed in [askOnSideEffects] asks even when the default allows, which is
 *     how the "confirm only what costs money" preset covers tools it has never heard of;
 *  3. otherwise, under [ToolApprovalMode.ASK], a side-effect-free tool (`READ_ONLY`) executes
 *     without a prompt, because there is nothing to approve;
 *  4. otherwise the [defaultMode] applies.
 *
 * Unknown tool keys therefore inherit [defaultMode] (or an [askOnSideEffects] class); they are never
 * implicitly allowed. The object is immutable and cheap to copy, so a settings write can atomically
 * replace it.
 */
data class ToolApprovalPolicy(
    val defaultMode: ToolApprovalMode = ToolApprovalMode.ASK,
    val perToolOverrides: Map<String, ToolApprovalMode> = emptyMap(),
    /**
     * Side-effect classes that always ask, whatever [defaultMode] says. Empty by default, so every
     * pre-existing policy keeps its exact behaviour; [PRESET_ASK_MONEY_ONLY] is the only preset
     * that fills it.
     */
    val askOnSideEffects: Set<AgentToolSideEffect> = emptySet()
) {
    init {
        require(perToolOverrides.size <= MAX_TOOL_OVERRIDES) {
            "at most $MAX_TOOL_OVERRIDES per-tool overrides are supported"
        }
        perToolOverrides.forEach { (toolKey, _) ->
            require(isAcceptableToolKey(toolKey)) { "invalid tool key in approval policy" }
        }
    }

    /** Mode for [toolKey] before side-effect folding; exposed for the settings list. */
    fun modeFor(toolKey: String): ToolApprovalMode = perToolOverrides[toolKey] ?: defaultMode

    /**
     * Resolved mode for one call.
     *
     * An override always wins. A side-effect class in [askOnSideEffects] asks unless an override
     * said otherwise. Only [ToolApprovalMode.ASK] may be relaxed by the read-only shortcut, so a
     * `FORBID` default still forbids read-only tools.
     */
    fun decide(toolKey: String, sideEffect: AgentToolSideEffect): ToolApprovalMode {
        perToolOverrides[toolKey]?.let { return it }
        if (sideEffect in askOnSideEffects) return ToolApprovalMode.ASK
        if (defaultMode == ToolApprovalMode.ASK && sideEffect == AgentToolSideEffect.READ_ONLY) {
            return ToolApprovalMode.ALLOW
        }
        return defaultMode
    }

    /** True when [toolKey] carries an explicit exception (used by the settings summary). */
    fun hasOverride(toolKey: String): Boolean = perToolOverrides.containsKey(toolKey)

    fun withDefaultMode(mode: ToolApprovalMode): ToolApprovalPolicy = copy(defaultMode = mode)

    fun withOverride(toolKey: String, mode: ToolApprovalMode?): ToolApprovalPolicy {
        val updated = LinkedHashMap(perToolOverrides)
        if (mode == null) updated.remove(toolKey) else updated[toolKey] = mode
        return copy(perToolOverrides = updated)
    }

    companion object {
        const val MAX_TOOL_OVERRIDES: Int = 128
        const val MAX_TOOL_KEY_CHARS: Int = 200

        /** Default for a fresh install: every tool asks, matching the pre-policy behavior. */
        val DEFAULT: ToolApprovalPolicy = ToolApprovalPolicy()

        /**
         * Tools that can spend money or act outside the app even though their declared side-effect
         * class alone does not say so. Kept as explicit keys because the check must be predictable
         * and testable, not a substring guess over a model-authored name.
         */
        val MONEY_CONFIRM_TOOL_KEYS: Set<String> = setOf(
            "device.screen",
            "http.get",
            "terminal.exec"
        )

        /**
         * "只对花钱的动作确认" (Mika's ASK default): everything runs, but any external effect, any
         * network call and the three spend-capable tools above still prompt once.
         */
        val PRESET_ASK_MONEY_ONLY: ToolApprovalPolicy = ToolApprovalPolicy(
            defaultMode = ToolApprovalMode.ALLOW,
            perToolOverrides = MONEY_CONFIRM_TOOL_KEYS.associateWith { ToolApprovalMode.ASK },
            askOnSideEffects = setOf(
                AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
                AgentToolSideEffect.NETWORK
            )
        )

        /** Fully autonomous: even a payment-like action runs without a prompt. */
        val PRESET_AUTONOMOUS: ToolApprovalPolicy = ToolApprovalPolicy(
            defaultMode = ToolApprovalMode.ALLOW,
            perToolOverrides = emptyMap()
        )

        /** Cautious: every call asks, read-only calls are explicitly allowed through. */
        val PRESET_CAUTIOUS: ToolApprovalPolicy = ToolApprovalPolicy(
            defaultMode = ToolApprovalMode.ASK,
            perToolOverrides = emptyMap(),
            askOnSideEffects = emptySet()
        )

        /** Selectable presets with the user-facing one-line explanation, in display order. */
        val PRESETS: List<ToolApprovalPreset> = listOf(
            ToolApprovalPreset(
                id = "ask_money_only",
                policy = PRESET_ASK_MONEY_ONLY,
                description = "默认直接执行；只有可能产生费用或触达外部的动作（截图、联网、命令）才确认。"
            ),
            ToolApprovalPreset(
                id = "cautious",
                policy = PRESET_CAUTIOUS,
                description = "默认每次调用都确认，只读工具自动放行；适合首次使用或处理敏感资料。"
            ),
            ToolApprovalPreset(
                id = "autonomous",
                policy = PRESET_AUTONOMOUS,
                description = "全部直接执行、从不询问，包括支付与外部动作；只在完全信任的任务里使用。"
            )
        )

        fun preset(id: String): ToolApprovalPreset? = PRESETS.firstOrNull { it.id == id }

        /** Strict key shape for stored overrides; a corrupt document drops the bad row only. */
        fun isAcceptableToolKey(toolKey: String): Boolean =
            toolKey.isNotBlank() &&
                toolKey.length <= MAX_TOOL_KEY_CHARS &&
                toolKey.none { it.isWhitespace() || it == '\u0000' }
    }
}

/**
 * One selectable approval preset: the policy itself plus the sentence shown in settings.
 *
 * The preset is a presentation wrapper only; the persisted document is always the plain
 * [ToolApprovalPolicy], so editing an individual tool afterwards simply leaves the preset list.
 */
data class ToolApprovalPreset(
    val id: String,
    val policy: ToolApprovalPolicy,
    val description: String
) {
    init {
        require(id.isNotBlank()) { "preset id must not be blank" }
        require(description.isNotBlank()) { "preset description must not be blank" }
    }
}

/**
 * Versioned JSON document of [ToolApprovalPolicy] used by the settings store.
 *
 * Decoding is deliberately forgiving but never widening: a missing/corrupt document falls back to
 * [ToolApprovalPolicy.DEFAULT] (everything asks), an unknown mode name drops that row, and a
 * bad tool key drops that row only. It never throws, so a corrupt preference cannot block tool use.
 *
 * Version 1 documents (written before the preset work) have no `askOnSideEffects` list and decode
 * to an empty set, which is exactly their old behaviour.
 */
object ToolApprovalPolicyCodec {
    const val VERSION: Int = 2
    private const val MIN_SUPPORTED_VERSION = 1
    private const val KEY_VERSION = "version"
    private const val KEY_DEFAULT = "default"
    private const val KEY_OVERRIDES = "overrides"
    private const val KEY_ASK_ON_SIDE_EFFECTS = "askOnSideEffects"

    fun encode(policy: ToolApprovalPolicy): String {
        val overrides = JSONObject()
        policy.perToolOverrides.toSortedMap().forEach { (toolKey, mode) ->
            if (ToolApprovalPolicy.isAcceptableToolKey(toolKey)) overrides.put(toolKey, mode.name)
        }
        val askOnSideEffects = org.json.JSONArray()
        policy.askOnSideEffects.sortedBy { it.name }.forEach { askOnSideEffects.put(it.name) }
        return JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_DEFAULT, policy.defaultMode.name)
            .put(KEY_OVERRIDES, overrides)
            .put(KEY_ASK_ON_SIDE_EFFECTS, askOnSideEffects)
            .toString()
    }

    fun decode(raw: String?): ToolApprovalPolicy {
        if (raw.isNullOrBlank()) return ToolApprovalPolicy.DEFAULT
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return ToolApprovalPolicy.DEFAULT
        val version = root.optInt(KEY_VERSION, VERSION)
        if (version !in MIN_SUPPORTED_VERSION..VERSION) return ToolApprovalPolicy.DEFAULT
        val defaultMode = runCatching { ToolApprovalMode.valueOf(root.optString(KEY_DEFAULT)) }
            .getOrDefault(ToolApprovalPolicy.DEFAULT.defaultMode)
        val overridesJson = root.optJSONObject(KEY_OVERRIDES) ?: JSONObject()
        val overrides = LinkedHashMap<String, ToolApprovalMode>()
        overridesJson.keys().forEach { rawKey ->
            if (overrides.size >= ToolApprovalPolicy.MAX_TOOL_OVERRIDES) return@forEach
            if (!ToolApprovalPolicy.isAcceptableToolKey(rawKey)) return@forEach
            val mode = runCatching { ToolApprovalMode.valueOf(overridesJson.optString(rawKey)) }.getOrNull()
                ?: return@forEach
            overrides[rawKey] = mode
        }
        val askOnSideEffects = LinkedHashSet<AgentToolSideEffect>()
        val sideEffectsJson = root.optJSONArray(KEY_ASK_ON_SIDE_EFFECTS)
        if (sideEffectsJson != null) {
            for (index in 0 until sideEffectsJson.length()) {
                val decoded = runCatching {
                    AgentToolSideEffect.valueOf(sideEffectsJson.optString(index))
                }.getOrNull() ?: continue
                askOnSideEffects += decoded
            }
        }
        return ToolApprovalPolicy(
            defaultMode = defaultMode,
            perToolOverrides = overrides,
            askOnSideEffects = askOnSideEffects
        )
    }
}

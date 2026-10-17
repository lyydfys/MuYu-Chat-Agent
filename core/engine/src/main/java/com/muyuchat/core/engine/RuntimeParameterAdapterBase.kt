package com.muyuchat.core.engine

import org.json.JSONArray
import org.json.JSONObject

abstract class BaseRuntimeParameterAdapter(
    final override val runtime: LocalChatRuntime,
    final override val registry: ParameterFieldPolicyRegistry
) : RuntimeParameterAdapter {
    protected open val aliases: Map<String, String> = emptyMap()
    protected open val advancedOverrideFields: Set<String> = emptySet()

    protected abstract fun runtimeDefaults(
        identity: ModelRuntimeIdentity
    ): Pair<Map<String, Any?>, Map<String, Any?>>

    protected open fun normalize(
        identity: ModelRuntimeIdentity,
        requestedLoad: MutableMap<String, Any?>,
        requestedHot: MutableMap<String, Any?>,
        warnings: MutableList<String>,
        sourceByField: MutableMap<String, String>
    ) = Unit

    protected open fun behaviorDefaults(identity: ModelRuntimeIdentity): Map<String, Any?> =
        mapOf("chat_template_mode" to "auto", "use_jinja" to true)

    protected abstract fun activeValuesFromStats(root: JSONObject): JSONObject?

    override fun partition(identity: ModelRuntimeIdentity, rawJson: String): RuntimeParameterPartition {
        require(identity.runtime == runtime ||
            (runtime == LocalChatRuntime.LLAMA_CPP && identity.runtime == LocalChatRuntime.GENIEX_LLAMA_CPP)) {
            "${identity.runtime} cannot use $runtime adapter"
        }
        val warnings = mutableListOf<String>()
        val quarantined = mutableListOf<QuarantinedOverride>()
        val root = runCatching { JSONObject(rawJson.ifBlank { "{}" }) }.getOrElse { error ->
            return RuntimeParameterPartition(
                CanonicalParameterSet.EMPTY,
                CanonicalParameterSet.EMPTY,
                CanonicalParameterSet.EMPTY,
                "{}",
                listOf(QuarantinedOverride("$", JSONObject.quote(rawJson), "invalid JSON: ${error.message}")),
                listOf("parameter JSON was invalid and was quarantined")
            )
        }
        val flattened = linkedMapOf<String, Any?>()
        val advanced = parseAdvanced(root.opt("advanced_json"), quarantined, warnings)
        advanced?.keys()?.forEach { key -> flattened[aliases[key] ?: key] = advanced.opt(key) }
        root.keys().forEach { key ->
            if (key != "advanced_json") flattened[aliases[key] ?: key] = root.opt(key)
        }
        // MNN resolves advanced sampling controls before ordinary controls.
        // Canonical names win over aliases within the same source, as in native.
        advancedOverrideFields.forEach { field ->
            for (source in listOfNotNull(advanced, root)) {
                val key = (listOf(field) + aliases.filterValues { it == field }.keys)
                    .firstOrNull { source.has(it) && !source.isNull(it) }
                if (key != null) {
                    flattened[field] = source.opt(key)
                    break
                }
            }
        }

        val view = registry.forRuntime(identity)
        val load = linkedMapOf<String, Any?>()
        val hot = linkedMapOf<String, Any?>()
        val behavior = linkedMapOf<String, Any?>()
        val generation = JSONObject()
        flattened.forEach { (field, value) ->
            if (field == LlamaAdvancedParams.SCHEMA_VERSION_KEY) return@forEach
            val policy = view.policy(field)
            when (policy.mutability) {
                ParameterMutability.LOAD_BOUND -> load[field] = value
                ParameterMutability.HOT_EXECUTION -> {
                    if (policy.affectsSemantics) behavior[field] = value else hot[field] = value
                }
                ParameterMutability.GENERATION_ONLY -> generation.put(field, value)
                ParameterMutability.UNSUPPORTED -> quarantined += QuarantinedOverride(
                    field = field,
                    rawJson = jsonValueString(value),
                    reason = "unsupported by ${identity.runtime}/${identity.runtimeVersion}/${identity.nativeLibrarySha256}"
                )
            }
        }
        return RuntimeParameterPartition(
            loadBound = CanonicalParameterSet.of(load),
            hotExecution = CanonicalParameterSet.of(hot),
            modelBehavior = CanonicalParameterSet.of(behavior),
            generationJson = generation.toString(),
            quarantinedOverrides = quarantined.sortedBy { it.field },
            warnings = warnings
        )
    }

    override fun resolveLoadProfile(
        identity: ModelRuntimeIdentity,
        rawJson: String,
        profileId: String,
        revision: Long,
        activeLoadSignature: ActiveLoadedSignature?
    ): ParameterResolution {
        val partition = partition(identity, rawJson)
        val (defaultLoad, defaultHot) = runtimeDefaults(identity)
        val desiredLoad = defaultLoad.toMutableMap().apply {
            partition.loadBound.encodedValues.forEach { (field, encoded) -> put(field, decodeValue(encoded)) }
        }
        val desiredHot = defaultHot.toMutableMap().apply {
            partition.hotExecution.encodedValues.forEach { (field, encoded) -> put(field, decodeValue(encoded)) }
        }
        val desiredBehavior = behaviorDefaults(identity).toMutableMap().apply {
            partition.modelBehavior.encodedValues.forEach { (field, encoded) -> put(field, decodeValue(encoded)) }
        }
        val resolvedLoad = desiredLoad.toMutableMap()
        val resolvedHot = desiredHot.toMutableMap()
        val warnings = partition.warnings.toMutableList()
        val sourceByField = buildMap<String, String> {
            defaultLoad.keys.forEach { put(it, "runtime-default") }
            defaultHot.keys.forEach { put(it, "runtime-default") }
            behaviorDefaults(identity).keys.forEach { put(it, "runtime-default") }
            partition.loadBound.fields.forEach { put(it, "requested-profile") }
            partition.hotExecution.fields.forEach { put(it, "requested-profile") }
            partition.modelBehavior.fields.forEach { put(it, "requested-profile") }
        }.toMutableMap()
        normalize(identity, resolvedLoad, resolvedHot, warnings, sourceByField)
        val profile = ModelExecutionProfile(
            modelId = identity.modelId,
            runtimeIdentity = identity,
            desiredLoadBoundValues = CanonicalParameterSet.of(desiredLoad),
            resolvedLoadBoundValues = CanonicalParameterSet.of(resolvedLoad),
            hotExecutionValues = CanonicalParameterSet.of(resolvedHot),
            desiredHotExecutionValues = CanonicalParameterSet.of(desiredHot),
            modelBehaviorValues = CanonicalParameterSet.of(desiredBehavior),
            desiredModelBehaviorValues = CanonicalParameterSet.of(desiredBehavior),
            profileId = profileId,
            revision = revision,
            userOverrides = partition.loadBound.fields + partition.hotExecution.fields + partition.modelBehavior.fields,
            quarantinedOverrides = partition.quarantinedOverrides
        )
        return ParameterResolution(
            requested = profile.desiredSignature,
            resolved = profile.resolvedLoadSignature,
            profile = profile,
            sourceByField = sourceByField,
            warnings = warnings,
            reloadRequired = activeLoadSignature?.let {
                it.identityHash != identity.identityHash ||
                    it.values.differences(profile.resolvedLoadSignature.values).isNotEmpty()
            } ?: true,
            rejectedOverrides = partition.quarantinedOverrides.map { it.field },
            quarantinedOverrides = partition.quarantinedOverrides
        )
    }

    open override fun activeLoadedSignature(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): ActiveLoadedSignature? {
        if (expected.identityHash != identity.identityHash) return null
        val root = runCatching { JSONObject(nativeStatsJson) }.getOrNull() ?: return null
        if (!root.optBoolean("loaded", true)) return null
        val raw = activeValuesFromStats(root) ?: return null
        val partition = partition(identity, raw.toString())
        val observed = partition.loadBound.only(expected.values.fields)
        val observedSafe = signatureSafeValues(identity, observed)
        if (observedSafe.fields != expected.values.fields ||
            observedSafe.differences(expected.values).isNotEmpty()
        ) return null
        return ActiveLoadedSignature.of(identity, observed)
    }

    override fun loadSignatureDiagnostic(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): JSONObject {
        val root = runCatching { JSONObject(nativeStatsJson) }.getOrNull()
        val expectedValues = expected.values
        val effective = root
            ?.let(::activeValuesFromStats)
            ?.let { raw ->
                partition(identity, raw.toString()).loadBound.only(expectedValues.fields)
            }
            ?.let { observed -> signatureSafeValues(identity, observed) }
        return JSONObject().apply {
            put("nativeLoaded", root?.optBoolean("loaded", false) ?: false)
            put("expectedLoad", expectedValues.toJsonObject())
            if (effective == null) {
                put("nativeEffectiveLoad", JSONObject.NULL)
                put("missingFields", JSONArray(expectedValues.fields.sorted()))
                put("differentFields", JSONArray(expectedValues.fields.sorted()))
            } else {
                put("nativeEffectiveLoad", effective.toJsonObject())
                put(
                    "missingFields",
                    JSONArray((expectedValues.fields - effective.fields).sorted())
                )
                put(
                    "differentFields",
                    JSONArray(effective.differences(expectedValues).sorted())
                )
            }
        }
    }

    override fun nativeCompletionJson(
        partition: RuntimeParameterPartition,
        profile: ModelExecutionProfile,
        override: RuntimeOverrideSignature
    ): String = JSONObject(partition.generationJson).apply {
        mergeJson(profile.hotExecutionValues.plus(override.values).toJsonObject())
        mergeJson(profile.modelBehaviorValues.toJsonObject())
    }.toString()

    override fun nativeLoadJson(profile: ModelExecutionProfile): String = JSONObject().apply {
        mergeJson(profile.resolvedLoadBoundValues.toJsonObject())
        mergeJson(profile.hotExecutionValues.toJsonObject())
        mergeJson(profile.modelBehaviorValues.toJsonObject())
    }.toString()

    private fun parseAdvanced(
        raw: Any?,
        quarantined: MutableList<QuarantinedOverride>,
        warnings: MutableList<String>
    ): JSONObject? = when (raw) {
        null, JSONObject.NULL -> null
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrElse { error ->
            quarantined += QuarantinedOverride("advanced_json", JSONObject.quote(raw), "invalid JSON: ${error.message}")
            warnings += "advanced_json was invalid and was quarantined"
            null
        }
        else -> {
            quarantined += QuarantinedOverride("advanced_json", jsonValueString(raw), "must be a JSON object")
            warnings += "advanced_json was not an object and was quarantined"
            null
        }
    }
}

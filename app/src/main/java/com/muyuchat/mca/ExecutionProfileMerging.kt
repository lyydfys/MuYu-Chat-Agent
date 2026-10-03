package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import org.json.JSONObject

/**
 * Merging and comparing execution profiles: pure functions over `GenerationParams`,
 * `ModelExecutionProfile` and `ModelManifest`.
 *
 * These four moved together because they are one computation with **no owner state at all** -- no
 * `_uiState` read, no field, no coroutine -- so the extraction needs no port and the ViewModel keeps
 * four one-line forwarders. Splitting them would put `mergeExecutionProfile` in one file and the
 * diff that decides whether a merge is even needed in another, which is how the two drift apart.
 *
 * The bodies are the originals, verbatim. `toLoadParams` stays an extension on `ModelManifest`
 * because that is how its callers read it.
 *
 * **The names are deliberately different from the ViewModel members they replace.** A same-named
 * member would *shadow* these top-level functions inside the class, so the forwarder left behind
 * would call itself -- a silent infinite recursion. Kotlin has no way to qualify a top-level
 * extension call, so renaming is the only safe form here.
 */

/** Builds the loader parameters for this model, resolving the vision projector only for llama.cpp. */
internal fun ModelManifest.buildLoadParamsFor(params: GenerationParams): LoadParams =
    LoadParams(
        nCtx = params.nCtx,
        nThreads = params.nThreads,
        advancedJson = params.advancedJson,
        visionProjectorPath = if (runtime == ChatModelRuntime.LLAMA_CPP) {
            resolveVisionProjectorPath(path, visionProjectorPath)
        } else {
            null
        }
    )

/** Folds a committed execution profile's resolved values into the live generation parameters. */
internal fun mergedExecutionProfile(
    generation: GenerationParams,
    profile: ModelExecutionProfile
): GenerationParams = GenerationParams.fromJson(
    profile.resolvedLoadBoundValues
        .plus(profile.hotExecutionValues)
        .plus(profile.modelBehaviorValues)
        .toJsonObject(),
    generation
)

/**
 * Merges the user's requested overrides into the base profile, limited to the fields the profile is
 * authoritative for.
 *
 * Returns `base` unchanged when no authoritative field was overridden, and also when the merge left
 * both signatures identical -- so a no-op edit does not look like a new profile.
 */
internal fun mergeUserRequestedIntoProfile(
    base: ModelExecutionProfile,
    requested: ModelExecutionProfile,
    authoritativeFields: Set<String>
): ModelExecutionProfile {
    require(base.runtimeIdentity.identityHash == requested.runtimeIdentity.identityHash) {
        "用户参数候选与当前运行身份不一致"
    }
    val selected = requested.userOverrides.intersect(authoritativeFields)
    if (selected.isEmpty()) return base
    val merged = requested.copy(
        desiredLoadBoundValues = base.desiredLoadBoundValues.plus(
            requested.desiredLoadBoundValues.only(selected)
        ),
        resolvedLoadBoundValues = base.resolvedLoadBoundValues.plus(
            requested.resolvedLoadBoundValues.only(selected)
        ),
        hotExecutionValues = base.hotExecutionValues.plus(
            requested.hotExecutionValues.only(selected)
        ),
        desiredHotExecutionValues = base.desiredHotExecutionValues.plus(
            requested.desiredHotExecutionValues.only(selected)
        ),
        modelBehaviorValues = base.modelBehaviorValues.plus(
            requested.modelBehaviorValues.only(selected)
        ),
        desiredModelBehaviorValues = base.desiredModelBehaviorValues.plus(
            requested.desiredModelBehaviorValues.only(selected)
        ),
        userOverrides = base.userOverrides + selected,
        quarantinedOverrides = (base.quarantinedOverrides + requested.quarantinedOverrides)
            .distinctBy { it.field }
    )
    return if (merged.resolvedLoadSignature.digest == base.resolvedLoadSignature.digest &&
        merged.committedExecutionSignature.digest == base.committedExecutionSignature.digest
    ) {
        base
    } else {
        merged
    }
}

/**
 * True when the requested generation parameters differ from the active profile on any field the
 * profile governs. Null active profile means "nothing to compare against", which is not a difference.
 */
internal fun profileDiffersFrom(
    requested: GenerationParams,
    active: ModelExecutionProfile?
): Boolean {
    active ?: return false
    val requestedLoad = LoadParams(
        nCtx = requested.nCtx,
        nThreads = requested.nThreads,
        advancedJson = requested.advancedJson
    ).toJson().let(::JSONObject)
    val effective = active.resolvedLoadBoundValues
        .plus(active.hotExecutionValues)
        .plus(active.modelBehaviorValues)
        .toJsonObject()
    val governedFields = active.resolvedLoadBoundValues.fields +
        active.hotExecutionValues.fields + active.modelBehaviorValues.fields
    return governedFields.any { field ->
        requestedLoad.has(field) &&
            canonicalJsonValue(requestedLoad.opt(field)) != canonicalJsonValue(effective.opt(field))
    }
}

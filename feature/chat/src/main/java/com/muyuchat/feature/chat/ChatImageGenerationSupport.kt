package com.muyuchat.feature.chat

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.role
import org.json.JSONArray
import com.muyuchat.core.engine.isAsciiTriggerWordCharacter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S30：从 ChatScreen.kt 等值移出的图片生成授权/触发器/UI 参数持久化簇。
// 原样逐字迁移，未做行为改动；跨区入口按协议提升 internal。
private const val IMAGE_GENERATION_OWNED_GRANTS_KEY = "owned:persistable_image_uris"
private val generationImageGrantOwnershipLock = Any()
private val pendingGenerationImageGrantUriEpochs = mutableMapOf<String, Long>()
private var nextPendingGenerationImageGrantEpoch = 0L

// P9/S30: private→internal（跨文件被 ChatScreen 复合区引用）
internal fun imageLoraDraftsFromJson(raw: String): List<ImageGenerationUiLoraDraft> = runCatching {
    val array = JSONArray(raw)
    require(array.length() <= 8)
    buildList {
        for (index in 0 until array.length()) {
            add(ImageGenerationUiLoraDraft.fromJson(array.getJSONObject(index)))
        }
    }.also { drafts ->
        require(drafts.map(ImageGenerationUiLoraDraft::id).distinct().size == drafts.size)
    }
}.getOrDefault(emptyList())

// P9/S30: private→internal（跨文件被 ChatScreen 复合区引用）
internal fun imageLoraDraftsToJson(drafts: List<ImageGenerationUiLoraDraft>): String {
    require(drafts.size <= 8) { "At most 8 LoRA adapters may be selected." }
    return JSONArray().apply { drafts.forEach { put(it.toJson()) } }.toString()
}

internal data class ImageViggleUiControls(
    val stepsText: String,
    val cfgScaleText: String,
    val autoSteps: Boolean = false,
    val autoCfg: Boolean = false,
    val stepsEditedByUser: Boolean = false,
    val cfgEditedByUser: Boolean = false
)

internal fun imageViggleUiControlsForSelection(
    current: ImageViggleUiControls,
    model: ChatModelChoice,
    selected: Boolean
): ImageViggleUiControls {
    val defaultCfgText = model.imageDefaultCfgScale.toString().trimEnd('0').trimEnd('.')
    if (!selected) return current.copy(
        stepsText = if (current.autoSteps) model.imageDefaultSteps.toString() else current.stepsText,
        cfgScaleText = if (current.autoCfg) defaultCfgText else current.cfgScaleText,
        autoSteps = false,
        autoCfg = false
    )
    val applySteps = !current.stepsEditedByUser &&
        current.stepsText.toIntOrNull() == model.imageDefaultSteps
    val applyCfg = !current.cfgEditedByUser &&
        current.cfgScaleText.toDoubleOrNull() == model.imageDefaultCfgScale
    return current.copy(
        stepsText = if (applySteps) "6" else current.stepsText,
        cfgScaleText = if (applyCfg) "1" else current.cfgScaleText,
        autoSteps = current.autoSteps || applySteps,
        autoCfg = current.autoCfg || applyCfg
    )
}

// P9/S30: private→internal（跨文件被 ChatScreen 复合区引用）
internal fun imageTextualInversionIdsFromJson(raw: String): List<String> = runCatching {
    val array = JSONArray(raw)
    require(array.length() <= 8)
    buildList {
        for (index in 0 until array.length()) {
            add(java.util.UUID.fromString(array.getString(index)).toString())
        }
    }.also { ids -> require(ids.distinct().size == ids.size) }
}.getOrDefault(emptyList())

// P9/S30: private→internal（跨文件被 ChatScreen 复合区引用）
internal fun imageTextualInversionIdsToJson(ids: List<String>): String {
    require(ids.size <= 8) { "At most 8 textual inversions may be selected." }
    val canonical = ids.map { java.util.UUID.fromString(it).toString() }
    require(canonical.distinct().size == canonical.size)
    return JSONArray(canonical).toString()
}

private val IMAGE_TEXTUAL_INVERSION_TRIGGER_PATTERN = Regex("[A-Za-z0-9_:#<>|.-]{1,64}")

internal fun imagePromptContainsTextualInversionTrigger(
    prompt: String,
    trigger: String
): Boolean = imageTextualInversionTriggerRegex(trigger).containsMatchIn(prompt)

internal fun imagePromptWithTextualInversionTrigger(
    prompt: String,
    trigger: String
): String {
    if (imagePromptContainsTextualInversionTrigger(prompt, trigger)) return prompt
    val base = prompt.trimEnd()
    return when {
        base.isEmpty() -> trigger
        base.endsWith(',') -> "$base $trigger"
        else -> "$base, $trigger"
    }
}

internal fun imagePromptWithoutTextualInversionTrigger(
    prompt: String,
    trigger: String
): String {
    val removed = imageTextualInversionTriggerRegex(trigger).replace(prompt, "")
    return removed
        .replace(Regex("(?:[\\t ]*,[\\t ]*){2,}"), ", ")
        .replace(Regex("[\\t ]+"), " ")
        .trim()
        .trim(',')
        .trim()
}

internal data class ImageTextualInversionSelectionReconciliation(
    val ids: List<String>,
    val prompt: String,
    val triggersById: Map<String, String>,
    val additionalPrompt: String = "",
)

internal fun reconcileImageTextualInversionSelection(
    supportsTextualInversion: Boolean,
    libraryBusy: Boolean,
    currentIds: List<String>,
    prompt: String,
    knownTriggersById: Map<String, String>,
    available: List<ImageTextualInversionUiItem>,
    supportedFormats: Set<String> = IMAGE_TEXTUAL_INVERSION_ALL_FORMATS,
    additionalPrompt: String = "",
): ImageTextualInversionSelectionReconciliation {
    val normalizedFormats = supportedFormats.mapTo(mutableSetOf()) { it.trim().lowercase() }
    if (libraryBusy) {
        val knownById = available.associateBy(ImageTextualInversionUiItem::id)
        val retained = if (supportsTextualInversion) {
            currentIds.filter { id ->
                knownById[id]?.isSupportedBy(normalizedFormats) != false
            }.take(8)
        } else {
            emptyList()
        }
        var normalizedPrompt = prompt
        var normalizedAdditionalPrompt = additionalPrompt
        (currentIds + knownTriggersById.keys)
            .distinct()
            .filterNot(retained::contains)
            .forEach { id ->
                val trigger = knownTriggersById[id] ?: knownById[id]?.trigger
                if (trigger != null) {
                    normalizedPrompt = imagePromptWithoutTextualInversionTrigger(
                        normalizedPrompt,
                        trigger
                    )
                    normalizedAdditionalPrompt = imagePromptWithoutTextualInversionTrigger(
                        normalizedAdditionalPrompt,
                        trigger
                    )
                }
            }
        return ImageTextualInversionSelectionReconciliation(
            ids = retained,
            prompt = normalizedPrompt,
            additionalPrompt = normalizedAdditionalPrompt,
            triggersById = knownTriggersById.filterKeys(retained::contains)
        )
    }
    val availableById = available
        .filter { artifact -> artifact.isSupportedBy(normalizedFormats) }
        .associateBy(ImageTextualInversionUiItem::id)
    val retained = if (supportsTextualInversion) {
        currentIds.filter(availableById::containsKey).take(8)
    } else {
        emptyList()
    }
    var normalizedPrompt = prompt
    var normalizedAdditionalPrompt = additionalPrompt
    (currentIds + knownTriggersById.keys)
        .distinct()
        .filterNot(retained::contains)
        .forEach { id ->
            val trigger = knownTriggersById[id] ?: availableById[id]?.trigger
            if (trigger != null) {
                normalizedPrompt = imagePromptWithoutTextualInversionTrigger(normalizedPrompt, trigger)
                normalizedAdditionalPrompt = imagePromptWithoutTextualInversionTrigger(
                    normalizedAdditionalPrompt,
                    trigger
                )
            }
        }
    val retainedTriggers = buildMap {
        retained.forEach { id ->
            val trigger = requireNotNull(availableById[id]).trigger
            put(id, trigger)
            if (!imagePromptContainsTextualInversionTrigger(normalizedAdditionalPrompt, trigger)) {
                normalizedPrompt = imagePromptWithTextualInversionTrigger(normalizedPrompt, trigger)
            }
        }
    }
    return ImageTextualInversionSelectionReconciliation(
        ids = retained,
        prompt = normalizedPrompt,
        additionalPrompt = normalizedAdditionalPrompt,
        triggersById = retainedTriggers
    )
}

private fun imageTextualInversionTriggerRegex(trigger: String): Regex {
    require(IMAGE_TEXTUAL_INVERSION_TRIGGER_PATTERN.matches(trigger)) {
        "Textual inversion trigger is invalid."
    }
    val startsWithWord = trigger.first().isAsciiTriggerWordCharacter()
    val endsWithWord = trigger.last().isAsciiTriggerWordCharacter()
    return Regex(
        buildString {
            if (startsWithWord) append("(?<![A-Za-z0-9_])")
            append(Regex.escape(trigger))
            if (endsWithWord) append("(?![A-Za-z0-9_])")
        },
        RegexOption.IGNORE_CASE
    )
}

internal data class GenerationImageGrantReconciliationPlan(
    val retainedOwnedUris: Set<String>,
    val releaseOwnedUris: Set<String>,
    val forgetOwnedUris: Set<String>
)

internal fun generationImageSnapshotReferences(
    preferences: Map<String, *>,
    currentImageModelIds: Set<String>
): Set<String> = buildSet {
    preferences.forEach { (key, value) ->
        if (!key.startsWith(IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX)) return@forEach
        val modelId = key.removePrefix(IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX)
        if (modelId !in currentImageModelIds) return@forEach
        val snapshot = ImageGenerationUiParameterSnapshot.fromJsonOrNull(value as? String)
            ?: return@forEach
        listOf(snapshot.inputImageUri, snapshot.maskImageUri, snapshot.controlImageUri)
            .mapNotNull { raw -> raw?.trim()?.takeIf { it.startsWith("content://", true) } }
            .forEach(::add)
    }
}

internal fun normalizedGenerationImageHistoryReferences(
    references: Iterable<String>
): Set<String> = references.mapNotNullTo(mutableSetOf()) { raw ->
    raw.trim().takeIf { it.startsWith("content://", ignoreCase = true) }
}

internal fun combinedGenerationImageGrantReferences(
    snapshotReferencedUris: Set<String>,
    historyReferencedUris: Set<String>,
    transientReferencedUris: Set<String>,
    pendingUris: Set<String>
): Set<String> = snapshotReferencedUris +
    historyReferencedUris +
    transientReferencedUris +
    pendingUris

internal fun obsoleteGenerationImageSnapshotKeys(
    preferences: Map<String, *>,
    currentImageModelIds: Set<String>
): Set<String> = preferences.keys.filterTo(mutableSetOf()) { key ->
    key.startsWith(IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX) &&
        key.removePrefix(IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX) !in currentImageModelIds
}

internal fun planGenerationImageGrantReconciliation(
    ownedUris: Set<String>,
    persistedReadUris: Set<String>,
    referencedUris: Set<String>,
    deferRelease: Boolean
): GenerationImageGrantReconciliationPlan {
    val validOwned = ownedUris intersect persistedReadUris
    val forget = ownedUris - persistedReadUris
    val release = if (deferRelease) emptySet() else validOwned - referencedUris
    return GenerationImageGrantReconciliationPlan(
        retainedOwnedUris = validOwned - release,
        releaseOwnedUris = release,
        forgetOwnedUris = forget
    )
}

/**
 * Resolves only pending entries that existed when a reconciliation was scheduled. This prevents a
 * stale IO effect from pruning a grant taken by a newer picker callback before Compose has exposed
 * that URI as transient state. Any durable UI/history reference or a successful snapshot commit
 * resolves pending protection immediately; otherwise it remains only while a transient role uses it.
 */
internal fun pendingGenerationImageGrantUrisAfterReconciliation(
    pendingUriEpochs: Map<String, Long>,
    eligibleForPruneUriEpochs: Map<String, Long>,
    snapshotReferencedUris: Set<String>,
    transientReferencedUris: Set<String>,
    committedReferencedUris: Set<String> = emptySet()
): Map<String, Long> {
    val currentReferences = snapshotReferencedUris + transientReferencedUris
    return pendingUriEpochs.filterNot { (uri, epoch) ->
        eligibleForPruneUriEpochs[uri] == epoch &&
            (uri in committedReferencedUris ||
                uri in snapshotReferencedUris ||
                uri !in currentReferences)
    }
}

// P9/S30: private→internal（跨文件被 ChatScreen 复合区引用）
internal fun pendingGenerationImageGrantUriEpochsSnapshot(): Map<String, Long> =
    synchronized(generationImageGrantOwnershipLock) {
        pendingGenerationImageGrantUriEpochs.toMap()
    }

private fun registerPendingGenerationImageGrantUri(raw: String) {
    check(nextPendingGenerationImageGrantEpoch < Long.MAX_VALUE) {
        "Generation image grant pending epoch exhausted."
    }
    pendingGenerationImageGrantUriEpochs[raw] = ++nextPendingGenerationImageGrantEpoch
}

private fun ownedGenerationImageUris(preferences: SharedPreferences): Set<String> =
    preferences.getStringSet(IMAGE_GENERATION_OWNED_GRANTS_KEY, emptySet())
        ?.mapNotNullTo(mutableSetOf()) { raw ->
            raw.trim().takeIf { it.startsWith("content://", ignoreCase = true) }
        }
        .orEmpty()

internal fun persistGenerationImageUri(context: Context, uri: Uri): String? = runCatching {
    synchronized(generationImageGrantOwnershipLock) {
        require(uri.scheme.equals("content", ignoreCase = true)) {
            "Generation image inputs must use a persistable content URI."
        }
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(
            IMAGE_GENERATION_UI_PARAMETER_PREFS,
            Context.MODE_PRIVATE
        )
        val raw = uri.toString()
        val ownedBefore = ownedGenerationImageUris(preferences)
        val alreadyPersisted = appContext.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == uri
        }
        // A pre-existing grant may belong to model import, the file library, or another feature.
        // Use it without claiming ownership so generation reconciliation can never release it.
        if (alreadyPersisted && raw !in ownedBefore) {
            return@synchronized raw
        }
        if (!alreadyPersisted) {
            check(
                preferences.edit()
                    .putStringSet(IMAGE_GENERATION_OWNED_GRANTS_KEY, ownedBefore + raw)
                    .commit()
            ) { "Unable to register generation image grant ownership." }
            try {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (error: Throwable) {
                preferences.edit()
                    .putStringSet(IMAGE_GENERATION_OWNED_GRANTS_KEY, ownedBefore)
                    .commit()
                throw error
            }
        }
        val retained = appContext.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == uri
        }
        if (!retained) {
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            preferences.edit()
                .putStringSet(IMAGE_GENERATION_OWNED_GRANTS_KEY, ownedBefore)
                .commit()
            pendingGenerationImageGrantUriEpochs.remove(raw)
            error("The document provider did not retain read access.")
        }
        registerPendingGenerationImageGrantUri(raw)
        raw
    }
}.getOrNull()

// P9/S30: private→internal（跨文件被 ChatScreen 复合区引用）
internal fun reconcileGenerationImageUriGrants(
    context: Context,
    preferences: SharedPreferences,
    currentImageModelIds: Set<String>,
    deferRelease: Boolean,
    releaseOwnedUrisIfCoordinatorIdle: ((() -> Unit) -> Boolean),
    libraryHistoryReferencedUris: Set<String> = emptySet(),
    transientReferencedUris: Set<String> = emptySet(),
    committedReferencedUris: Set<String> = emptySet(),
    pendingUriEpochsEligibleForPrune: Map<String, Long> = emptyMap()
) = synchronized(generationImageGrantOwnershipLock) {
    val obsoleteKeys = obsoleteGenerationImageSnapshotKeys(
        preferences = preferences.all,
        currentImageModelIds = currentImageModelIds
    )
    if (obsoleteKeys.isNotEmpty()) {
        val editor = preferences.edit()
        obsoleteKeys.forEach(editor::remove)
        editor.commit()
    }
    val uiSnapshotReferencedUris = generationImageSnapshotReferences(
        preferences = preferences.all,
        currentImageModelIds = currentImageModelIds
    )
    val persistentReferencedUris = uiSnapshotReferencedUris + libraryHistoryReferencedUris
    val retainedPendingUris = pendingGenerationImageGrantUrisAfterReconciliation(
        pendingUriEpochs = pendingGenerationImageGrantUriEpochs,
        eligibleForPruneUriEpochs = pendingUriEpochsEligibleForPrune,
        snapshotReferencedUris = persistentReferencedUris,
        transientReferencedUris = transientReferencedUris,
        committedReferencedUris = committedReferencedUris
    )
    pendingGenerationImageGrantUriEpochs.clear()
    pendingGenerationImageGrantUriEpochs.putAll(retainedPendingUris)
    val referencedUris = combinedGenerationImageGrantReferences(
        snapshotReferencedUris = uiSnapshotReferencedUris,
        historyReferencedUris = libraryHistoryReferencedUris,
        transientReferencedUris = transientReferencedUris,
        pendingUris = retainedPendingUris.keys
    )
    val persistedReadUris = context.contentResolver.persistedUriPermissions
        .asSequence()
        .filter { it.isReadPermission }
        .map { it.uri.toString() }
        .toSet()
    val plan = planGenerationImageGrantReconciliation(
        ownedUris = ownedGenerationImageUris(preferences),
        persistedReadUris = persistedReadUris,
        referencedUris = referencedUris,
        deferRelease = deferRelease
    )
    val retained = plan.retainedOwnedUris.toMutableSet()
    val releaseWindowOpened = if (deferRelease) {
        plan.releaseOwnedUris.isEmpty()
    } else {
        releaseOwnedUrisIfCoordinatorIdle {
            plan.releaseOwnedUris.forEach { raw ->
                val uri = runCatching { Uri.parse(raw) }.getOrNull()
                if (uri == null) return@forEach
                val released = runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }.isSuccess
                val stillPersisted = context.contentResolver.persistedUriPermissions.any { permission ->
                    permission.isReadPermission && permission.uri == uri
                }
                if (!released || stillPersisted) retained += raw
            }
            GenerationImageOwnedInputStore(context).pruneUnreferenced(referencedUris)
        }
    }
    if (!releaseWindowOpened) {
        retained += plan.releaseOwnedUris
    }
    preferences.edit()
        .putStringSet(IMAGE_GENERATION_OWNED_GRANTS_KEY, retained)
        .commit()
}

internal fun persistedGenerationImageUriOrNull(context: Context, raw: String?): String? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    if (!uri.scheme.equals("content", ignoreCase = true)) return null
    if (isGenerationOwnedInputUri(context, value)) {
        return GenerationImageOwnedInputStore(context).readableOwnedUriOrNull(value)
    }
    val permissionStillGranted = context.contentResolver.persistedUriPermissions.any { permission ->
        permission.isReadPermission && permission.uri == uri
    }
    if (!permissionStillGranted) return null
    val documentStillReadable = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true }
            ?: context.contentResolver.openInputStream(uri)?.use { true }
            ?: false
    }.getOrDefault(false)
    return value.takeIf { documentStillReadable }
}

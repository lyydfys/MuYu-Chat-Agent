package com.muyuchat.mca

/** Content inputs retained by history recreation or an in-memory job retry snapshot. */
internal fun retainedGenerationImageContentReferences(
    historyReferences: Set<String>,
    jobInputDrafts: Iterable<LocalImageInputDraft>
): Set<String> = buildSet {
    historyReferences.mapNotNullTo(this) { it.safeGenerationContentReferenceOrNull() }
    jobInputDrafts.forEach { draft ->
        listOf(
            draft.inputImageReference,
            draft.maskImageReference,
            draft.controlImageReference
        ).mapNotNullTo(this) { it.safeGenerationContentReferenceOrNull() }
    }
}

private fun String?.safeGenerationContentReferenceOrNull(): String? = this
    ?.trim()
    ?.takeIf { it.startsWith("content://", ignoreCase = true) }

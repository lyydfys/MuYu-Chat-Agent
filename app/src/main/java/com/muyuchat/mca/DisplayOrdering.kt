package com.muyuchat.mca

/**
 * Display ordering for the persisted collections the user browses.
 *
 * The three lists below are the conversation history, the image library and the file
 * library. Their order is product behaviour rather than a detail: a pinned conversation
 * that drifts down the history list, or an image library that shows oldest first, is a
 * visible regression. Each policy is stated once here instead of being repeated at the
 * twenty call sites that consume it.
 *
 * Two properties hold for all three and are pinned by tests:
 *
 *  - the receiver is not modified; a new list is returned, so callers that keep the
 *    original can still use it
 *  - the sort is stable, so records with equal keys keep their relative order and the
 *    rendered list does not reshuffle between recompositions
 *
 * Extracted verbatim from `MainViewModel` during P2; no comparator or direction was
 * changed.
 */

/**
 * Conversation history order: pinned conversations first, most recently updated first.
 *
 * Pinning outranks recency in both directions -- a pinned conversation older than every
 * unpinned one still sorts above all of them.
 */
internal fun List<ChatSessionRecord>.sortedForHistory(): List<ChatSessionRecord> =
    sortedWith(
        compareByDescending<ChatSessionRecord> { it.pinned }
            .thenByDescending { it.updatedAt }
    )

/** Image library order: newest image first. */
internal fun List<ImageAssetRecord>.sortedImagesForLibrary(): List<ImageAssetRecord> =
    sortedByDescending { it.createdAt }

/** File library order: newest file first. */
internal fun List<FileAssetRecord>.sortedFilesForLibrary(): List<FileAssetRecord> =
    sortedByDescending { it.createdAt }

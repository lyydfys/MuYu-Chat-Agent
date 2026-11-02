package com.muyuchat.core.dsh.contract.capability

/**
 * Terminal result of one file pick.
 *
 * [Picked] is the only success shape. The raw SAF URI and the app-private staging path are not
 * present; DSH receives opaque document identities plus workspace-relative staging paths.
 */
sealed interface DshPickFilesResult : DshCapabilityResult {
    data class Picked(val files: List<DshStagedFile>) : DshPickFilesResult {
        init {
            require(files.size <= DshCapabilityLimits.MAX_FILES_PER_PICK) {
                "pick result exceeds ${DshCapabilityLimits.MAX_FILES_PER_PICK} files"
            }
            require(files.map { it.workspaceRelativePath }.distinct().size == files.size) {
                "pick result contains duplicate staging paths"
            }
        }

        /**
         * Typed projection of what this result actually grants: one-shot access to the staged
         * copies, never a persistent workspace tree. A `workspaceRelativePath` is a staging path,
         * not proof of directory access.
         */
        val access: DshWorkspaceAccess
            get() = DshWorkspaceAccess.oneShotFilePick()
    }

    data object Cancelled : DshPickFilesResult

    data class Rejected(val failure: DshCapabilityFailure) : DshPickFilesResult
}

/** Terminal result of one file save. */
sealed interface DshSaveFileResult : DshCapabilityResult {
    data class Saved(
        val document: DshDocumentRef,
        val displayName: String
    ) : DshSaveFileResult {
        init {
            require(DshWorkspacePaths.isValidDisplayName(displayName)) {
                "saved file display name is invalid"
            }
        }

        /**
         * A create-document save is an export of exactly one file. It never widens workspace
         * access, so a later reader cannot present it as a tree grant.
         */
        val access: DshWorkspaceAccess
            get() = DshWorkspaceAccess.downloadOrExportSave()
    }

    data object Cancelled : DshSaveFileResult

    data class Rejected(val failure: DshCapabilityFailure) : DshSaveFileResult
}

/** Terminal result of one notification request. */
sealed interface DshNotifyResult : DshCapabilityResult {
    data class Posted(val notificationId: Int) : DshNotifyResult {
        init {
            require(notificationId >= 0) {
                "notification id must be non-negative"
            }
        }
    }

    /** The request was cancelled before the broker posted anything. */
    data object Cancelled : DshNotifyResult

    data class Rejected(val failure: DshCapabilityFailure) : DshNotifyResult
}

/** Terminal result of one device action. */
sealed interface DshDeviceActionResult : DshCapabilityResult {
    /** Bounded, already-redacted screen text for `ReadScreen`; null for non-reading actions. */
    data class Completed(val data: String? = null) : DshDeviceActionResult {
        init {
            require(data == null || data.length <= DshCapabilityLimits.MAX_SCREEN_TEXT_CHARS) {
                "device action output exceeds the screen text limit"
            }
        }
    }

    /** A screenshot is always staged as a file; no Bitmap crosses the contract. */
    data class Screenshot(val staged: DshStagedFile) : DshDeviceActionResult

    data object Cancelled : DshDeviceActionResult

    data class Rejected(val failure: DshCapabilityFailure) : DshDeviceActionResult
}

/**
 * Result of a cancel attempt.
 *
 * Cancellation never fabricates an outcome and never re-executes work. A terminal result stays
 * terminal, and an unknown request id stays unknown.
 */
sealed interface DshCapabilityCancelResult {
    /** The request was pending and has been cancelled; its original call returns a typed Cancelled. */
    data object Cancelled : DshCapabilityCancelResult

    /** The request already has a terminal result; that result remains authoritative. */
    data object AlreadyTerminal : DshCapabilityCancelResult

    /** No request with this id is known in the current generation. */
    data object NotFound : DshCapabilityCancelResult

    /** The request belongs to an older or revoked generation. */
    data object StaleGeneration : DshCapabilityCancelResult
}

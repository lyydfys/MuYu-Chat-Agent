package com.muyuchat.core.dsh.contract.capability

/**
 * Persistent SAF *tree* grant owned by the app, not by the DSH payload or the WebView.
 *
 * The only way to reach [GRANTED] is the system `OpenDocumentTree` result plus a real
 * `ContentResolver.takePersistableUriPermission` against the same URI. A WebView
 * `<input type="file">` pick, a create-document save or a page download never produces this
 * state; those are one-shot operations and are projected through [DshWorkspaceAccess].
 */
enum class DshWorkspaceTreeGrantState {
    /** The durable record exists and the platform still holds the persisted permission. */
    GRANTED,

    /** No durable grant was ever recorded (or it was cleared). */
    NOT_GRANTED,

    /** The user explicitly released the grant through the bridge. */
    REVOKED,

    /** A durable record exists but the platform no longer holds the permission. */
    INVALID,

    /** The document provider (or the resolver) is missing or threw. */
    PROVIDER_UNAVAILABLE
}

/** The two platform modes this capability can persist. Write always implies read. */
data class DshWorkspaceTreeFlags(val read: Boolean, val write: Boolean) {
    init {
        require(read || !write) { "a write-only tree grant cannot be used" }
    }

    val isEmpty: Boolean
        get() = !read && !write

    companion object {
        val NONE = DshWorkspaceTreeFlags(read = false, write = false)
        val READ = DshWorkspaceTreeFlags(read = true, write = false)
        val READ_WRITE = DshWorkspaceTreeFlags(read = true, write = true)
    }
}

/**
 * Every way a DSH operation can touch a user file.
 *
 * Only [PERSISTENT_TREE_GRANT] may be presented as workspace-tree access, and it is only true when
 * the grant is currently [DshWorkspaceTreeGrantState.GRANTED]. The type forbids the other two
 * sources from claiming it at construction, so a chooser pick or a download/export cannot be
 * mislabelled by a later mapping.
 */
enum class DshWorkspaceAccessKind {
    PERSISTENT_TREE_GRANT,
    ONE_SHOT_FILE_PICK,
    DOWNLOAD_OR_EXPORT_SAVE
}

data class DshWorkspaceAccess(
    val kind: DshWorkspaceAccessKind,
    val persistentTreeGrant: Boolean
) {
    init {
        // The tree-grant source may be projected while no live grant exists (NOT_GRANTED,
        // REVOKED, INVALID, PROVIDER_UNAVAILABLE) — that projection is honest with `false`.
        // The converse stays forbidden: a chooser pick or a save may never claim a grant.
        require(!persistentTreeGrant || kind == DshWorkspaceAccessKind.PERSISTENT_TREE_GRANT) {
            "only a persistent tree grant may report persistentTreeGrant=true"
        }
    }

    companion object {
        /** The projection of one observed grant; false unless the state is [GRANTED]. */
        fun ofTreeGrant(status: DshWorkspaceTreeGrantStatus): DshWorkspaceAccess =
            DshWorkspaceAccess(
                kind = DshWorkspaceAccessKind.PERSISTENT_TREE_GRANT,
                persistentTreeGrant = status.isPersistentTreeGrant
            )
        /** A WebView file chooser / `DshPickFilesRequest` result. Never a tree grant. */
        fun oneShotFilePick(): DshWorkspaceAccess =
            DshWorkspaceAccess(DshWorkspaceAccessKind.ONE_SHOT_FILE_PICK, persistentTreeGrant = false)

        /** A create-document save, page download or console export. Never a tree grant. */
        fun downloadOrExportSave(): DshWorkspaceAccess =
            DshWorkspaceAccess(DshWorkspaceAccessKind.DOWNLOAD_OR_EXPORT_SAVE, persistentTreeGrant = false)
    }
}

/**
 * Snapshot of the one workspace-tree grant owner.
 *
 * [flags] are only reported for [DshWorkspaceTreeGrantState.GRANTED]; a lost or revoked grant
 * never echoes stale modes. [pendingPick] is true while the system picker is visible for this
 * generation, so a polling client can distinguish "no grant" from "waiting for the user".
 */
data class DshWorkspaceTreeGrantStatus(
    val state: DshWorkspaceTreeGrantState,
    val flags: DshWorkspaceTreeFlags = DshWorkspaceTreeFlags.NONE,
    val grantedAtMillis: Long? = null,
    val pendingPick: Boolean = false,
    val detail: String? = null
) {
    init {
        require(state == DshWorkspaceTreeGrantState.GRANTED || flags.isEmpty) {
            "only a granted workspace tree carries flags"
        }
        require(state == DshWorkspaceTreeGrantState.GRANTED || grantedAtMillis == null) {
            "only a granted workspace tree carries a grant time"
        }
        require(grantedAtMillis == null || grantedAtMillis >= 0L) {
            "workspace tree grant time must be non-negative"
        }
        require(!(pendingPick && state == DshWorkspaceTreeGrantState.GRANTED)) {
            "a granted workspace tree cannot have a pending picker"
        }
        require(detail == null || (detail.isNotBlank() && detail.length <= MAX_DETAIL_CHARS)) {
            "workspace tree detail must be 1..$MAX_DETAIL_CHARS characters"
        }
        require(detail == null || detail.none(::isControlCharacter)) {
            "workspace tree detail must be a prompt-free single line"
        }
    }

    /** True only for a live grant; the single source of `persistentTreeGrant=true`. */
    val isPersistentTreeGrant: Boolean
        get() = state == DshWorkspaceTreeGrantState.GRANTED

    /** Honest typed projection; pick/save/download paths never construct this shape. */
    val access: DshWorkspaceAccess
        get() = DshWorkspaceAccess.ofTreeGrant(this)

    companion object {
        const val MAX_DETAIL_CHARS = 200

        /** No durable grant was recorded. */
        val NOT_GRANTED = DshWorkspaceTreeGrantStatus(DshWorkspaceTreeGrantState.NOT_GRANTED)
    }
}

/**
 * Ask the user for a persistent workspace tree through the system `OpenDocumentTree` picker.
 *
 * The request returns immediately with [DshWorkspaceTreeGrantResult.PickStarted]; the terminal
 * fact is the next [DshWorkspaceTreeGrantStatus]. It never blocks the loopback request on user
 * latency.
 */
data class DshWorkspaceTreeGrantRequest(
    override val requestId: DshCapabilityRequestId,
    override val generation: Long,
    override val reason: String,
    /** Ask for write access too; read access is always required. */
    val writeAccess: Boolean = true
) : DshCapabilityRequest {
    init {
        requireCapabilityGeneration(generation)
        requireCapabilityReason(reason)
    }

    override val capability: DshCapability
        get() = DshCapability.WORKSPACE_TREE
}

/** Explicitly release the persisted grant; the owner calls `releasePersistableUriPermission`. */
data class DshWorkspaceTreeReleaseRequest(
    override val requestId: DshCapabilityRequestId,
    override val generation: Long,
    override val reason: String
) : DshCapabilityRequest {
    init {
        requireCapabilityGeneration(generation)
        requireCapabilityReason(reason)
    }

    override val capability: DshCapability
        get() = DshCapability.WORKSPACE_TREE
}

/** Terminal result of one workspace-tree request or release. */
sealed interface DshWorkspaceTreeGrantResult : DshCapabilityResult {
    /** The tree was already granted; nothing was opened. */
    data class Granted(val status: DshWorkspaceTreeGrantStatus) : DshWorkspaceTreeGrantResult {
        init {
            require(status.state == DshWorkspaceTreeGrantState.GRANTED) {
                "Granted requires a GRANTED workspace tree status"
            }
        }
    }

    /** The system picker is visible; poll [DshWorkspaceTreeGrantStatus] for the outcome. */
    data class PickStarted(val status: DshWorkspaceTreeGrantStatus) : DshWorkspaceTreeGrantResult {
        init {
            require(status.pendingPick) { "PickStarted requires pendingPick=true" }
        }
    }

    /** The grant was released; [DshWorkspaceTreeGrantStatus.state] is REVOKED or NOT_GRANTED. */
    data class Released(val status: DshWorkspaceTreeGrantStatus) : DshWorkspaceTreeGrantResult {
        init {
            require(
                status.state == DshWorkspaceTreeGrantState.REVOKED ||
                    status.state == DshWorkspaceTreeGrantState.NOT_GRANTED
            ) { "Released requires REVOKED or NOT_GRANTED" }
        }
    }

    data class Rejected(val failure: DshCapabilityFailure) : DshWorkspaceTreeGrantResult
}

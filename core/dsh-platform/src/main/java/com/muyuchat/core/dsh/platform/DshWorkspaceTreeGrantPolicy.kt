package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantState
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantStatus
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeFlags

/**
 * Pure rules of the persistent workspace-tree grant.
 *
 * The owner keeps the platform handle and the durable record; this object only decides what a
 * well-formed SAF tree URI looks like and how one observation maps onto the five typed states, so
 * every branch is testable on the JVM with fakes.
 */
object DshWorkspaceTreeGrantPolicy {

    /** Bounded like every other capability string; SAF tree URIs are far shorter in practice. */
    const val MAX_TREE_URI_CHARS = 2_048

    private const val SCHEME = "content://"
    private const val TREE_PATH = "tree/"

    /**
     * True only for a `content://<authority>/tree/<document-id>` URI.
     *
     * A document URI (`.../document/...`, or a nested `.../tree/<id>/document/<id>`), an absolute
     * path, a `file://` URI, credentials in the authority or a control character cannot become a
     * workspace tree grant.
     */
    fun isTreeUri(value: String): Boolean {
        if (value.length !in 1..MAX_TREE_URI_CHARS) return false
        if (value.any(::isControlCharacter)) return false
        if (!value.startsWith(SCHEME)) return false
        val remainder = value.substring(SCHEME.length)
        val authority = remainder.substringBefore('/')
        if (authority.isEmpty()) return false
        if (authority.any { it == '@' || it == '?' || it == '#' }) return false
        val path = remainder.substringAfter('/', missingDelimiterValue = "")
        if (!path.startsWith(TREE_PATH)) return false
        val documentId = path.substring(TREE_PATH.length)
        if (documentId.isEmpty()) return false
        // A tree root has one id; a nested document URI under the tree is not the granted root.
        return documentId.none { it == '/' || it == '?' || it == '#' }
    }

    /**
     * Maps one durable record plus one live observation to the contract state.
     *
     * [observation] is only consulted for an unreleased record; a missing or revoked record never
     * claims a grant. A held write-only permission is downgraded to INVALID instead of being
     * reported as usable.
     */
    fun statusOf(
        record: DshWorkspaceTreeGrantRecord?,
        observation: DshWorkspaceTreeGrantObservation?,
        pendingPick: Boolean,
        detail: String?
    ): DshWorkspaceTreeGrantStatus {
        val safeDetail = sanitizeDetail(detail)
        if (record == null) {
            return DshWorkspaceTreeGrantStatus(
                state = DshWorkspaceTreeGrantState.NOT_GRANTED,
                pendingPick = pendingPick,
                detail = safeDetail
            )
        }
        if (record.revokedAtMillis != null) {
            return DshWorkspaceTreeGrantStatus(
                state = DshWorkspaceTreeGrantState.REVOKED,
                pendingPick = pendingPick,
                detail = safeDetail
            )
        }
        return when (observation) {
            is DshWorkspaceTreeGrantObservation.Held ->
                if (observation.flags.read) {
                    DshWorkspaceTreeGrantStatus(
                        state = DshWorkspaceTreeGrantState.GRANTED,
                        flags = observation.flags,
                        grantedAtMillis = record.grantedAtMillis,
                        detail = safeDetail
                    )
                } else {
                    DshWorkspaceTreeGrantStatus(
                        state = DshWorkspaceTreeGrantState.INVALID,
                        pendingPick = pendingPick,
                        detail = "the persisted tree permission lost read access"
                    )
                }

            DshWorkspaceTreeGrantObservation.NotHeld -> DshWorkspaceTreeGrantStatus(
                state = DshWorkspaceTreeGrantState.INVALID,
                pendingPick = pendingPick,
                detail = "the persisted tree permission is no longer held"
            )

            DshWorkspaceTreeGrantObservation.ProviderUnavailable, null -> DshWorkspaceTreeGrantStatus(
                state = DshWorkspaceTreeGrantState.PROVIDER_UNAVAILABLE,
                pendingPick = pendingPick,
                detail = "the document provider is unavailable"
            )
        }
    }

    /** Normalizes owner-provided detail without ever throwing on hostile provider text. */
    fun sanitizeDetail(detail: String?): String? {
        val bounded = detail
            ?.filterNot(::isControlCharacter)
            ?.trim()
            ?.take(DshWorkspaceTreeGrantStatus.MAX_DETAIL_CHARS)
            .orEmpty()
        return bounded.ifEmpty { null }
    }

    /** True when the owner may persist this selection; write implies read at the platform layer. */
    fun isUsableSelection(treeUri: String, flags: DshWorkspaceTreeFlags): Boolean =
        isTreeUri(treeUri) && flags.read

    private fun isControlCharacter(character: Char): Boolean =
        character.code <= 0x1F || character.code in 0x7F..0x9F
}

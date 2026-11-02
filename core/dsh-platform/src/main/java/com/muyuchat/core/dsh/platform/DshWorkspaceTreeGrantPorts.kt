package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityRequestId
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeFlags

/**
 * One durable fact: the user granted this SAF tree, with these modes, at this time.
 *
 * The raw URI never crosses into DSH payloads, the WebView or the capability contract; it stays
 * inside the app-private store and the platform adapter. [revokedAtMillis] is the explicit
 * release marker, kept so REVOKED stays observable across process restarts instead of silently
 * degrading to NOT_GRANTED.
 */
data class DshWorkspaceTreeGrantRecord(
    val treeUri: String,
    val flags: DshWorkspaceTreeFlags,
    val grantedAtMillis: Long,
    val revokedAtMillis: Long? = null
) {
    init {
        require(DshWorkspaceTreeGrantPolicy.isTreeUri(treeUri)) {
            "a workspace tree record must hold a content tree URI"
        }
        require(grantedAtMillis >= 0L) { "workspace tree grant time must be non-negative" }
        require(revokedAtMillis == null || revokedAtMillis >= grantedAtMillis) {
            "workspace tree release cannot precede the grant"
        }
    }
}

/** Live platform observation of one persisted tree URI. */
sealed interface DshWorkspaceTreeGrantObservation {
    /** The resolver still lists the URI with these modes. */
    data class Held(val flags: DshWorkspaceTreeFlags) : DshWorkspaceTreeGrantObservation

    /** The URI is not in the persisted list (or the grant was never taken). */
    data object NotHeld : DshWorkspaceTreeGrantObservation

    /** The resolver or the document provider is missing or threw. */
    data object ProviderUnavailable : DshWorkspaceTreeGrantObservation
}

/**
 * Narrow platform seam of the workspace-tree grant.
 *
 * The Android adapter maps `ContentResolver` calls onto these three observations and catches every
 * exception; the owner never touches an Android type, so its state machine is JVM-testable.
 */
interface DshWorkspaceTreeGrantPlatform {
    /** Reads the persisted permission list for [treeUri] without reading user content. */
    fun observe(treeUri: String): DshWorkspaceTreeGrantObservation

    /** Calls `takePersistableUriPermission` and returns the modes actually held afterwards. */
    fun takePersistable(treeUri: String, flags: DshWorkspaceTreeFlags): DshWorkspaceTreeGrantObservation

    /** Calls `releasePersistableUriPermission`; [DshWorkspaceTreeGrantObservation.NotHeld] means done. */
    fun releasePersistable(treeUri: String): DshWorkspaceTreeGrantObservation
}

/** Durable, single-record store. The Android implementation is app-private SharedPreferences. */
interface DshWorkspaceTreeGrantStore {
    /** Returns null when nothing (or nothing valid) was stored; never throws for bad data. */
    fun read(): DshWorkspaceTreeGrantRecord?

    fun write(record: DshWorkspaceTreeGrantRecord)
}

/** What the user is asked to pick. Carries no raw path and no capability token. */
data class DshWorkspaceTreePickerRequest(
    val requestId: DshCapabilityRequestId,
    val generation: Long,
    val writeAccess: Boolean
)

/** Typed outcome of the one visible `OpenDocumentTree` dialog. */
sealed interface DshWorkspaceTreePickerOutcome {
    /** [treeUri] is the system result string; the owner validates it again before persisting. */
    data class Selected(val treeUri: String, val flags: DshWorkspaceTreeFlags) : DshWorkspaceTreePickerOutcome

    /** The user dismissed the picker or it timed out. */
    data object Cancelled : DshWorkspaceTreePickerOutcome

    /** No resumed app surface could show the system dialog. */
    data object UserNotPresent : DshWorkspaceTreePickerOutcome
}

/**
 * User-presence seam implemented by the app composition. The owner awaits one pick with a bounded
 * timeout; an implementation must never fabricate [DshWorkspaceTreePickerOutcome.Selected].
 */
fun interface DshWorkspaceTreePickerPort {
    suspend fun pick(request: DshWorkspaceTreePickerRequest): DshWorkspaceTreePickerOutcome
}

package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeFlags
import kotlinx.coroutines.CompletableDeferred

/** Shared JVM doubles of the workspace-tree grant; no Android class is referenced here. */
internal class FakeWorkspaceTreePlatform : DshWorkspaceTreeGrantPlatform {

    var observation: DshWorkspaceTreeGrantObservation = DshWorkspaceTreeGrantObservation.NotHeld
    var takeResult: DshWorkspaceTreeGrantObservation? = null
    var releaseResult: DshWorkspaceTreeGrantObservation = DshWorkspaceTreeGrantObservation.NotHeld
    var throwOnObserve = false

    var observedUri: String? = null
    var takenUri: String? = null
    var takenFlags: DshWorkspaceTreeFlags? = null
    var releasedUri: String? = null

    override fun observe(treeUri: String): DshWorkspaceTreeGrantObservation {
        observedUri = treeUri
        if (throwOnObserve) throw IllegalStateException("provider died")
        return observation
    }

    override fun takePersistable(
        treeUri: String,
        flags: DshWorkspaceTreeFlags
    ): DshWorkspaceTreeGrantObservation {
        takenUri = treeUri
        takenFlags = flags
        // A real ContentResolver starts holding the grant after a successful take, so later
        // observations must report it; otherwise every honest grant would look INVALID.
        val result = takeResult ?: DshWorkspaceTreeGrantObservation.Held(flags)
        observation = result
        return result
    }

    override fun releasePersistable(treeUri: String): DshWorkspaceTreeGrantObservation {
        releasedUri = treeUri
        // Only a positive release changes the observable state; a failed release proves nothing
        // about what the platform still holds.
        if (releaseResult is DshWorkspaceTreeGrantObservation.NotHeld) {
            observation = DshWorkspaceTreeGrantObservation.NotHeld
        }
        return releaseResult
    }
}

internal class FakeWorkspaceTreeStore(
    var stored: DshWorkspaceTreeGrantRecord? = null
) : DshWorkspaceTreeGrantStore {

    var writes = 0

    override fun read(): DshWorkspaceTreeGrantRecord? = stored

    override fun write(record: DshWorkspaceTreeGrantRecord) {
        stored = record
        writes += 1
    }
}

internal class FakeWorkspaceTreePicker(
    initial: DshWorkspaceTreePickerOutcome? = null
) : DshWorkspaceTreePickerPort {

    val outcome = CompletableDeferred<DshWorkspaceTreePickerOutcome>()
    var calls = 0
    var lastRequest: DshWorkspaceTreePickerRequest? = null

    init {
        if (initial != null) outcome.complete(initial)
    }

    override suspend fun pick(request: DshWorkspaceTreePickerRequest): DshWorkspaceTreePickerOutcome {
        calls += 1
        lastRequest = request
        return outcome.await()
    }
}

internal const val TREE_URI =
    "content://com.android.externalstorage.documents/tree/primary%3ADownload"

internal const val DOCUMENT_URI =
    "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fa.txt"

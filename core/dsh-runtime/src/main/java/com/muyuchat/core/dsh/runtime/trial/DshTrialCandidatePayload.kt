package com.muyuchat.core.dsh.runtime.trial

import java.io.File

/**
 * Resolves the executable payload tree a closure trial must launch.
 *
 * The trial runs the *candidate's* payload, never the installed one: a candidate that carries no
 * payload could only ever be trialled by silently substituting the installed tree, which would
 * prove nothing about the candidate. Every refusal is categorical and path-free because it is
 * projected into a `DshTrialSnapshot.detail` over the Binder boundary.
 */
internal object DshTrialCandidatePayload {

    sealed interface Resolution {
        data class Ok(val payloadRoot: File, val kernelEntry: File) : Resolution

        data class Refused(val reason: String) : Resolution
    }

    fun resolve(candidateRoot: File, kernelEntryRelativePath: String): Resolution {
        val payloadRoot = File(candidateRoot, DshTrialRoots.CANDIDATE_PAYLOAD_DIR_NAME)
        if (!payloadRoot.isDirectory) {
            return Resolution.Refused(
                "the staged candidate carries no executable payload; a trial of the installed payload proves nothing"
            )
        }
        if (kernelEntryRelativePath.isBlank()) {
            return Resolution.Refused("the trial configuration declares no kernel entry")
        }
        // Textual containment is not enough: `payload/../outside.js` still starts with the payload
        // path. Reject any traversal segment, then compare canonical paths so a symlinked kernel
        // cannot redirect the launch outside the candidate.
        val segments = kernelEntryRelativePath.replace('\\', '/').split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            return Resolution.Refused("the candidate payload kernel entry escapes its payload tree")
        }
        val canonicalPayload = runCatching { payloadRoot.canonicalFile }.getOrNull()
            ?: return Resolution.Refused("the candidate payload tree cannot be resolved")
        val kernelEntry = runCatching { File(payloadRoot, kernelEntryRelativePath).canonicalFile }.getOrNull()
            ?: return Resolution.Refused("the candidate payload kernel entry cannot be resolved")
        if (!DshTrialRoots.isWithin(canonicalPayload, kernelEntry) || kernelEntry == canonicalPayload) {
            return Resolution.Refused("the candidate payload kernel entry escapes its payload tree")
        }
        if (!kernelEntry.isFile || kernelEntry.length() <= 0L) {
            return Resolution.Refused("the staged candidate payload kernel entry is missing")
        }
        return Resolution.Ok(payloadRoot, kernelEntry)
    }
}

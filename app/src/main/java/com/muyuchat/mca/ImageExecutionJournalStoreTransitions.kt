package com.muyuchat.mca

import org.json.JSONObject

/** T2B22-T4: journal store transition validation; moved verbatim from ImageExecutionJournal.kt. */

internal fun ImageExecutionJournalStore.updateLocked(
        next: ImageExecutionJournalEntry,
        expectedLeaseToken: String? = null
    ): ImageExecutionJournalEntry {
        val current = read(next.requestId) ?: error("Image journal does not exist: ${next.requestId}")
        requireExpectedLeaseToken(current, expectedLeaseToken)
        validateIdentity(current, next)
        validateTransition(current, next)
        writeAtomic(journalFile(next.requestId), next)
        return next
    }

internal fun ImageExecutionJournalStore.validateIdentity(
        current: ImageExecutionJournalEntry,
        next: ImageExecutionJournalEntry
    ) {
        require(current.schemaVersion == next.schemaVersion) { "Image journal schema cannot change." }
        require(current.requestId == next.requestId) { "Image journal request identity cannot change." }
        require(current.modelId == next.modelId) { "Image journal model id cannot change." }
        require(current.modelName == next.modelName) { "Image journal model name cannot change." }
        require(current.recommendationId == next.recommendationId) {
            "Image journal recommendation id cannot change."
        }
        require(current.recommendationRevision == next.recommendationRevision) {
            "Image journal recommendation revision cannot change."
        }
        require(current.modelFingerprint == next.modelFingerprint) { "Image journal model fingerprint cannot change." }
        require(current.profileFingerprint == next.profileFingerprint) { "Image journal profile fingerprint cannot change." }
        require(current.leaseToken == next.leaseToken) { "Image journal lease token cannot change." }
        require(current.createdAtMs == next.createdAtMs) { "Image journal creation time cannot change." }
        require(current.inputTempPaths == next.inputTempPaths) {
            "Image journal input temp paths cannot change."
        }
        require(normalizedJsonObject(current.requestedSummaryJson) == normalizedJsonObject(next.requestedSummaryJson)) {
            "Image journal requested summary cannot change."
        }
        require(normalizedJsonObject(current.resolvedSummaryJson) == normalizedJsonObject(next.resolvedSummaryJson)) {
            "Image journal resolved summary cannot change."
        }
    }

internal fun ImageExecutionJournalStore.requireExpectedLeaseToken(
        current: ImageExecutionJournalEntry,
        expectedLeaseToken: String?
    ) {
        if (expectedLeaseToken == null) return
        require(expectedLeaseToken.isNotBlank() && current.leaseToken == expectedLeaseToken) {
            "Image journal lease token does not match the active request epoch."
        }
    }

internal fun ImageExecutionJournalStore.validateTransition(
        current: ImageExecutionJournalEntry,
        next: ImageExecutionJournalEntry
    ) {
        require(!current.phase.terminal) { "Terminal image journal cannot transition." }
        require(next.updatedAtMs >= current.updatedAtMs) { "Image journal time must be monotonic." }
        require(!current.cancellationRequested || next.cancellationRequested) {
            "Image journal cancellation request cannot be cleared."
        }
        if (current.nativeGenerationSequence != null) {
            require(next.nativeGenerationSequence == current.nativeGenerationSequence) {
                "Image journal native generation sequence cannot change."
            }
        }
        if (current.workerPid > 0 && next.workerPid > 0) {
            require(current.workerPid == next.workerPid) { "Image journal worker PID cannot change." }
        }
        require((next.nativeStageMask or current.nativeStageMask) == next.nativeStageMask) {
            "Image journal native stage mask cannot lose observed stages."
        }
        require(next.outputTempPaths.containsAll(current.outputTempPaths)) {
            "Image journal output temp paths cannot lose observed paths."
        }
        require(next.outputArtifacts.take(current.outputArtifacts.size) == current.outputArtifacts) {
            "Image journal output artifacts cannot lose or rewrite published evidence."
        }
        if (current.steps > 0 && next.steps > 0) {
            require(current.steps == next.steps) { "Image journal total steps cannot change." }
        }
        require(next.step >= current.step || next.phase == ImageExecutionPhase.INTERRUPTED) {
            "Image journal step cannot move backwards."
        }
        require(next.phase in allowedNextPhases(current.phase)) {
            "Invalid image journal transition: ${current.phase} -> ${next.phase}"
        }
    }

private fun allowedNextPhases(current: ImageExecutionPhase): Set<ImageExecutionPhase> = when (current) {
        ImageExecutionPhase.PREPARING -> setOf(
            ImageExecutionPhase.PREPARING,
            ImageExecutionPhase.CONDITIONING,
            ImageExecutionPhase.SAMPLING,
            ImageExecutionPhase.FAILED,
            ImageExecutionPhase.CANCELLED,
            ImageExecutionPhase.INTERRUPTED
        )
        ImageExecutionPhase.CONDITIONING -> setOf(
            ImageExecutionPhase.CONDITIONING,
            ImageExecutionPhase.SAMPLING,
            ImageExecutionPhase.FAILED,
            ImageExecutionPhase.CANCELLED,
            ImageExecutionPhase.INTERRUPTED
        )
        ImageExecutionPhase.SAMPLING -> setOf(
            ImageExecutionPhase.SAMPLING,
            ImageExecutionPhase.DECODING,
            ImageExecutionPhase.FAILED,
            ImageExecutionPhase.CANCELLED,
            ImageExecutionPhase.INTERRUPTED
        )
        ImageExecutionPhase.DECODING -> setOf(
            ImageExecutionPhase.DECODING,
            ImageExecutionPhase.PUBLISHING,
            ImageExecutionPhase.FAILED,
            ImageExecutionPhase.CANCELLED,
            ImageExecutionPhase.INTERRUPTED
        )
        ImageExecutionPhase.PUBLISHING -> setOf(
            ImageExecutionPhase.PUBLISHING,
            ImageExecutionPhase.COMPLETED,
            ImageExecutionPhase.FAILED,
            ImageExecutionPhase.CANCELLED,
            ImageExecutionPhase.INTERRUPTED
        )
        ImageExecutionPhase.COMPLETED,
        ImageExecutionPhase.FAILED,
        ImageExecutionPhase.CANCELLED,
        ImageExecutionPhase.INTERRUPTED -> emptySet()
    }

private fun normalizedJsonObject(raw: String): String = JSONObject(raw).toString()

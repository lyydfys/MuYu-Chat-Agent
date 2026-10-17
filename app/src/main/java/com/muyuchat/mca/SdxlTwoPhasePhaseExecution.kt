package com.muyuchat.mca


internal suspend fun SdxlTwoPhaseCoordinator.runPhaseWithRuntimeCandidates(
    phase: SdxlImagePhase,
    candidates: List<SdxlPhaseRuntimeCandidate>,
    request: SdxlImagePhaseRequest,
    timeoutMs: Long,
    onProgress: (SdxlImagePhaseProgress) -> Unit
): SdxlPhaseCandidateCompletion {
    val selected = executeSdxlRuntimeCandidates(
        phase = phase,
        candidates = candidates,
        isCancelled = cancelled::get
    ) { candidate ->
        runPhase(
            phase = phase,
            request = request.copy(
                runtimeProfile = candidate.runtimeProfile
            ),
            timeoutMs = timeoutMs,
            onProgress = onProgress
        )
    }
    return SdxlPhaseCandidateCompletion(
        candidate = selected.candidate,
        completion = selected.value
    )
}

internal suspend fun SdxlTwoPhaseCoordinator.runPhase(
    phase: SdxlImagePhase,
    request: SdxlImagePhaseRequest,
    timeoutMs: Long,
    onProgress: (SdxlImagePhaseProgress) -> Unit
): SdxlPhaseCompletion {
    val client = SdxlPhaseClient(appContext, phase, onProgress)
    synchronized(stateLock) { activeClient = client }
    return try {
        check(!cancelled.get()) { "SDXL generation was cancelled." }
        client.execute(request, timeoutMs)
    } finally {
        synchronized(stateLock) { if (activeClient === client) activeClient = null }
        client.close()
    }
}

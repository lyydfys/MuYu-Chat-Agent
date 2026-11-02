package com.muyuchat.core.agent.runtime.run

/**
 * Compatibility fallback for prompted local models that answer with visible prose instead of the
 * CONTROL envelope.
 *
 * It is only consulted after the single protocol repair has been spent. The fallback has no tool
 * call, no approval and no artifact, so accepting the text cannot widen the side-effect boundary;
 * it merely lets a capable chat model finish as a plain answer. Blank text stays a protocol pause.
 */
internal fun protocolFallbackCompletion(
    run: com.muyuchat.core.agent.contract.AgentRun,
    transcript: AgentLoopTranscript,
    visibleText: String
): AgentLoopResult.Completed? {
    val text = visibleText.trim()
    if (text.isEmpty()) return null
    // Never turn a malformed protocol object (or several concatenated objects) into a successful
    // plain answer: that would hide an unexecuted tool call and show raw JSON as the final answer.
    if (text.startsWith("{") || text.startsWith("[") || text.contains("\"protocol\"")) return null
    return AgentLoopResult.Completed(
        run = run,
        transcript = transcript,
        finalText = text,
        artifactIds = emptyList()
    )
}

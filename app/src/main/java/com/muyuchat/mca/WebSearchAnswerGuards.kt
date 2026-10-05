package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role

/**
 * Web-search answer guards (P7/S6), moved verbatim from WebSearchProvider.kt: the grounding guard,
 * the citation-audit guard and their text-repair helpers. The two pattern constants they consume
 * (webSearchCitationRegex, webSearchGroundingReplacementRules) were widened private->internal in
 * place; same-package resolution keeps every caller and behavioral test call unchanged.
 */

internal fun ChatMessage.withWebSearchGroundingGuard(): ChatMessage {
    if (role != Role.ASSISTANT || sourceReferences.isEmpty()) return this
    val guardedContent = content.repairWebSearchGroundingText()
    val guardedReasoning = reasoningContent.repairWebSearchGroundingText()
    return if (guardedContent == content && guardedReasoning == reasoningContent) {
        this
    } else {
        copy(content = guardedContent, reasoningContent = guardedReasoning)
    }
}

internal fun ChatMessage.withWebSearchAnswerGuards(): WebSearchAnswerGuardResult {
    val grounded = withWebSearchGroundingGuard()
    val citationResult = grounded.withWebSearchCitationAuditGuard()
    return citationResult
}

private fun ChatMessage.withWebSearchCitationAuditGuard(): WebSearchAnswerGuardResult {
    if (role != Role.ASSISTANT || sourceReferences.isEmpty()) {
        return WebSearchAnswerGuardResult(message = this)
    }
    val sourceCount = sourceReferences.size
    val citedIndices = webSearchCitationRegex.findAll(content)
        .mapNotNull { it.webSearchCitationIndex() }
        .toList()
    val invalidIndices = citedIndices
        .filter { it !in 1..sourceCount }
        .distinct()
    var repairedContent = content
    var repaired = false
    if (invalidIndices.isNotEmpty()) {
        repairedContent = webSearchCitationRegex.replace(repairedContent) { match ->
            val index = match.webSearchCitationIndex()
            if (index != null && index in 1..sourceCount) match.value else ""
        }.cleanupWebSearchCitationSpacing()
        repaired = true
    }
    val validIndicesAfterRepair = webSearchCitationRegex.findAll(repairedContent)
        .mapNotNull { it.webSearchCitationIndex() }
        .filter { it in 1..sourceCount }
        .toList()
    val appendedFallback = validIndicesAfterRepair.isEmpty() && repairedContent.isNotBlank()
    if (appendedFallback) {
        repairedContent = repairedContent.trimEnd() + "\n\n参考来源：[1]"
        repaired = true
    }
    val finalCitedIndices = webSearchCitationRegex.findAll(repairedContent)
        .mapNotNull { it.webSearchCitationIndex() }
        .toList()
    return WebSearchAnswerGuardResult(
        message = if (repaired) copy(content = repairedContent) else this,
        citationAudit = WebSearchCitationAudit(
            sourceCount = sourceCount,
            citedIndices = finalCitedIndices,
            invalidIndices = invalidIndices,
            appendedFallbackCitation = appendedFallback,
            repaired = repaired
        )
    )
}

private fun String.repairWebSearchGroundingText(): String {
    if (isBlank()) return this
    var repaired = this
    webSearchGroundingReplacementRules.forEach { (pattern, replacement) ->
        repaired = pattern.replace(repaired, replacement)
    }
    return repaired
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trimStart()
}

private fun MatchResult.webSearchCitationIndex(): Int? =
    groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()

private fun String.cleanupWebSearchCitationSpacing(): String =
    replace(Regex(""" {2,}"""), " ")
        .replace(Regex("""\s+([，。！？、；：,.!?;:])"""), "$1")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trimEnd()

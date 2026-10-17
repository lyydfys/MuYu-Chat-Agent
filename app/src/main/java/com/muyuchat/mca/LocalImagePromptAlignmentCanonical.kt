package com.muyuchat.mca

import java.security.MessageDigest

/**
 * T2B22-T5（b 工位）拆片：canonical 摘要族（CanonicalClauseOccurrence / canonicalOccurrences /
 * canonicalPlanSha256 / canonicalReceiptSha256 / CanonicalSha256）与成员 newOpaqueId、
 * ByteArray.toLowerHex（保持件内 private）自 LocalImagePromptAlignmentV4.kt 逐字节平移；
 * 声明行按登记放宽（entropy 字段 internal；newOpaqueId 转为顶层 internal 扩展）。
 */

internal fun LocalImagePromptAlignmentV4.newOpaqueId(prefix: String, usedIds: MutableSet<String>): String {
        repeat(MAX_OPAQUE_ID_ATTEMPTS) {
            val bytes = ByteArray(OPAQUE_ID_BYTES)
            entropy.nextBytes(bytes)
            val candidate = "${prefix}_${bytes.toLowerHex()}"
            if (usedIds.add(candidate)) return candidate
        }
        error("Prompt alignment entropy did not produce a unique opaque id.")
    }
internal data class CanonicalClauseOccurrence(
    val origin: LocalImagePromptAlignmentOrigin,
    val index: Int,
    val text: String
) {
    val role: LocalImagePromptAlignmentRole get() = origin.role
}

internal fun LocalImagePromptAlignmentClauseSet.canonicalOccurrences(): List<CanonicalClauseOccurrence> =
    buildList {
        promptClauses.forEachIndexed { index, text ->
            add(CanonicalClauseOccurrence(LocalImagePromptAlignmentOrigin.USER_PROMPT, index, text))
        }
        negativePromptClauses?.forEachIndexed { index, text ->
            add(CanonicalClauseOccurrence(
                LocalImagePromptAlignmentOrigin.USER_NEGATIVE_PROMPT,
                index,
                text
            ))
        }
        canaryPromptClauses.forEachIndexed { index, text ->
            add(CanonicalClauseOccurrence(LocalImagePromptAlignmentOrigin.CANARY_PROMPT, index, text))
        }
        canaryNegativePromptClauses.forEachIndexed { index, text ->
            add(CanonicalClauseOccurrence(
                LocalImagePromptAlignmentOrigin.CANARY_NEGATIVE_PROMPT,
                index,
                text
            ))
        }
    }.filter { occurrence -> occurrence.text.containsHanScript() }

internal fun LocalImagePromptAlignmentClauseSet.canonicalPlanSha256(): String {
    val digest = CanonicalSha256("mca.local-image.prompt-alignment.v4.plan")
        .integer(LOCAL_IMAGE_PROMPT_ALIGNMENT_CONTRACT_VERSION)
        .boolean(negativePromptClauses != null)
    listOf(
        LocalImagePromptAlignmentOrigin.USER_PROMPT to promptClauses,
        LocalImagePromptAlignmentOrigin.USER_NEGATIVE_PROMPT to negativePromptClauses.orEmpty(),
        LocalImagePromptAlignmentOrigin.CANARY_PROMPT to canaryPromptClauses,
        LocalImagePromptAlignmentOrigin.CANARY_NEGATIVE_PROMPT to canaryNegativePromptClauses
    ).forEach { (origin, clauses) ->
        digest.string(origin.name).integer(clauses.size)
        clauses.forEachIndexed { index, clause ->
            digest.integer(index).string(clause)
        }
    }
    return digest.finish()
}

internal fun LocalImagePromptTranslationResultV4.canonicalReceiptSha256(): String {
    val digest = CanonicalSha256("mca.local-image.prompt-alignment.v4.receipt")
        .integer(LOCAL_IMAGE_PROMPT_ALIGNMENT_CONTRACT_VERSION)
        .string(planSha256)
        .integer(plan.units.size)
    plan.units.forEach { unit ->
        digest.integer(unit.ordinal)
            .string(unit.role.name)
            .string(unit.sourceText)
            .string(translationForUnit(unit.ordinal))
    }
    return digest.string("strict-bijection-verified").finish()
}

internal class CanonicalSha256(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var finished = false

    init {
        string(domain)
    }

    fun string(value: String): CanonicalSha256 = apply {
        check(!finished)
        val bytes = value.toByteArray(Charsets.UTF_8)
        integer(bytes.size)
        digest.update(bytes)
    }

    fun integer(value: Int): CanonicalSha256 = apply {
        check(!finished)
        digest.update(byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        ))
    }

    fun boolean(value: Boolean): CanonicalSha256 = apply {
        check(!finished)
        digest.update(if (value) 1.toByte() else 0.toByte())
    }

    fun finish(): String {
        check(!finished)
        finished = true
        return digest.digest().toLowerHex()
    }
}
private fun ByteArray.toLowerHex(): String = joinToString("") { byte ->
    HEX[(byte.toInt() ushr 4) and 0x0f].toString() + HEX[byte.toInt() and 0x0f]
}

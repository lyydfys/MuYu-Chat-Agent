package com.muyuchat.mca


/**
 * T2B22-T5（b 工位）拆片：对齐协议尺寸/字段集常量块（OPAQUE_ID_BYTES / MAX_ALIGNMENT_* /
 * SHA256_PATTERN / OPAQUE_ID_PATTERN / PHASE_* 字段集 / HEX）自 LocalImagePromptAlignmentV4.kt
 * 逐字节平移；15 行 private→internal 登记。
 */

internal const val OPAQUE_ID_BYTES = 16
internal const val MAX_OPAQUE_ID_ATTEMPTS = 32
internal const val MAX_ALIGNMENT_CLAUSE_COUNT = 256
internal const val MAX_ALIGNMENT_SOURCE_CLAUSE_CHARS = 4_096
internal const val MAX_ALIGNMENT_SOURCE_CHARS = 16_384
internal const val MAX_ALIGNMENT_TRANSLATED_CLAUSE_CHARS = 4_096
internal const val MAX_ALIGNMENT_TRANSLATED_CHARS = 16_384
internal val SHA256_PATTERN = Regex("[0-9a-f]{64}")
internal val OPAQUE_ID_PATTERN = Regex("[asc]_[0-9a-f]{32}")
internal val PHASE_A_ROOT_FIELDS = setOf("contract_version", "translations")
internal val PHASE_A_ITEM_FIELDS = setOf("slot_id", "translation")
internal val PHASE_B_ROOT_FIELDS = setOf("contract_version", "matches")
internal val PHASE_B_ITEM_FIELDS = setOf("source_id", "candidate_id", "verdict")
internal const val PHASE_B_EQUIVALENT_VERDICT = "equivalent"
internal val HEX = "0123456789abcdef".toCharArray()

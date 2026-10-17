package com.muyuchat.core.engine

/**
 * Text predicates and orderings that more than one layer needs to agree on.
 *
 * Both of these existed twice, body for body, in different modules.
 *
 * ## Why the ordering has to be one function
 *
 * [compareUtf8Unsigned] was `compareUtf8Unsigned` in app's TextualInversionContract and
 * `compareImageExecutionAssetLabelsUtf8` in api:local's ImageGenerationApiContract, eleven
 * lines each and identical. Both sort asset labels, and a byte-wise UTF-8 ordering is chosen
 * precisely so the result is stable and locale-independent. Two copies mean the API and the app
 * could order the same set differently, which matters wherever an ordering feeds an identity,
 * a fingerprint or a diff.
 *
 * Note it compares bytes as unsigned (`and 0xff`). Signed comparison would order anything above
 * U+007F before ASCII, which is not what a UTF-8 ordering means.
 *
 * ## Why the predicate has to be one function
 *
 * [isAsciiTriggerWordCharacter] was `isAsciiTriggerWordCharacter` in the same contract and
 * `isAsciiImageTriggerWordCharacter` in feature:chat's ChatScreen, two lines each and identical
 * apart from indentation. Both validate the characters a user is allowed to type into a trigger
 * word, so a divergence would mean a word accepted while editing in chat and rejected when it
 * reaches the contract, or the reverse.
 *
 * Accepted characters, unchanged from all four copies: a-z, A-Z, 0-9, _.
 *
 * Both are public, not internal: Kotlin's internal is module-scoped, and the callers live in
 * app, feature:chat and api:local. This file is the second time that mistake was made in this
 * series, so it is stated here rather than left to be rediscovered.
 */
public fun Char.isAsciiTriggerWordCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

/**
 * True when [codePoint] is an actual CJK unified ideograph or one of its extensions.
 *
 * ## Why this had to become one function
 *
 * It existed twice with **different** range lists: `isNativeChineseHanCodePoint` in api:local's
 * ImageGenerationApiContract and in app's LocalImagePromptLanguage. The app copy listed
 * `U+2EBF0..U+2EE5D` (CJK Unified Ideographs Extension I, added in Unicode 15.1) and the API copy
 * did not. The result was a live divergence on the same input: a prompt containing an Ext-I
 * ideograph passed the app's language admission and was then rejected by the API contract that is
 * supposed to describe exactly that admission. The API's copy was verified to be a strict subset
 * of the app's, so this file adopts the **union** — the API now accepts everything the app accepts.
 *
 * That direction is deliberate. Widening the API to match the app cannot admit anything the app
 * would refuse, and refusing a genuine Han ideograph is not one of the concrete runtime conditions
 * (corrupt package, invalid format, missing binary, failed native load or execution) that may
 * justify a rejection.
 *
 * ## Why the list is explicit rather than delegated to a platform table
 *
 * The Kotlin contract and the native contract must agree, so the ranges are written out instead of
 * relying on platform Unicode data that can differ by OS version. Radicals, ideographic-description
 * characters and compatibility ideographs are intentionally excluded: they are not normal prompt
 * text and must not become an accidental Unicode bypass. Unicode cannot distinguish Simplified
 * Chinese from Traditional Chinese or Japanese kanji; the evidence-bound encoder declaration in the
 * image execution contract carries that product decision instead.
 */
public fun isNativeChineseHanCodePoint(codePoint: Int): Boolean =
    codePoint == 0x3007 ||
        codePoint in 0x3400..0x4DBF ||
        codePoint in 0x4E00..0x9FFF ||
        codePoint in 0x20000..0x2A6DF ||
        codePoint in 0x2A700..0x2B73F ||
        codePoint in 0x2B740..0x2B81F ||
        codePoint in 0x2B820..0x2CEAF ||
        codePoint in 0x2CEB0..0x2EBEF ||
        codePoint in 0x2EBF0..0x2EE5D ||
        codePoint in 0x30000..0x3134A ||
        codePoint in 0x31350..0x323AF

/**
 * Punctuation an image prompt may carry outside the Han ranges.
 *
 * The same 28 characters existed as two differently named constants,
 * `SAFE_ASCII_DIFFUSION_PROMPT_PUNCTUATION` in api:local and `SAFE_ASCII_PROMPT_PUNCTUATION` in
 * app. Their values compared equal at the time of consolidation, which is exactly why they are
 * merged rather than left alone: a pair that agrees today can drift tomorrow, and the sibling
 * `isNativeChineseHanCodePoint` in the same two files is the worked example — its copies had
 * already drifted by one Unicode block before anyone noticed.
 */
public const val SAFE_ASCII_PROMPT_PUNCTUATION: String = "_,.;:!?\"'+-*/\\()[]{}<>|=@#%&"

/**
 * True when [codePoint] is safe ASCII prompt text: space, a line break, an ASCII letter or digit,
 * or one of [SAFE_ASCII_PROMPT_PUNCTUATION].
 *
 * This is the code-point form of the predicate api:local also applies character by character in
 * `String.isSafeAsciiDiffusionPrompt()`. Both sides and both forms have to agree: a character the
 * editor accepts must not be rejected when the prompt reaches the API contract, and a character the
 * contract rejects must not be accepted on the way in.
 */
public fun isSafeAsciiDiffusionPromptCodePoint(codePoint: Int): Boolean =
    codePoint == ' '.code || codePoint == '\n'.code || codePoint == '\r'.code ||
        codePoint in 'A'.code..'Z'.code ||
        codePoint in 'a'.code..'z'.code ||
        codePoint in '0'.code..'9'.code ||
        (codePoint <= Char.MAX_VALUE.code &&
            codePoint.toChar() in SAFE_ASCII_PROMPT_PUNCTUATION)

/** Byte-wise unsigned comparison of two strings' UTF-8 encodings; see the file comment. */
public fun compareUtf8Unsigned(left: String, right: String): Int {
    val leftBytes = left.toByteArray(Charsets.UTF_8)
    val rightBytes = right.toByteArray(Charsets.UTF_8)
    val commonLength = minOf(leftBytes.size, rightBytes.size)
    for (index in 0 until commonLength) {
        val comparison = (leftBytes[index].toInt() and 0xff)
            .compareTo(rightBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return leftBytes.size.compareTo(rightBytes.size)
}

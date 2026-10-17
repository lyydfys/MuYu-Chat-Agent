package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject

/**
 * T2B22-T5（b 工位）拆片：严格 JSON 读取 helper（strictJsonObject / requiredInt /
 * requiredObject / isVisibleAsciiAlignmentText / shuffledWith）自 LocalImagePromptAlignmentV4.kt
 * 逐字节平移；声明行登记为 internal（requiredArray/requiredString 属既有同名私有，保留在壳）。
 */

internal fun strictJsonObject(raw: String, expectedFields: Set<String>): JSONObject {
    require(raw.length <= LOCAL_IMAGE_PROMPT_ALIGNMENT_MAX_RESPONSE_CHARS) {
        "Prompt alignment response is too large."
    }
    val trimmed = raw.trim()
    require(trimmed.startsWith('{') && trimmed.endsWith('}')) {
        "Prompt alignment response must be one JSON object."
    }
    return JSONObject(trimmed).also { value ->
        require(value.keys().asSequence().toSet() == expectedFields) {
            "Prompt alignment response contains unsupported fields."
        }
    }
}
internal fun JSONObject.requiredInt(name: String): Int {
    require(has(name) && !isNull(name) && get(name) is Number) {
        "Prompt alignment field $name must be an integer."
    }
    val number = get(name) as Number
    val longValue = number.toLong()
    require(number.toDouble().isFinite() && number.toDouble() == longValue.toDouble()) {
        "Prompt alignment field $name must be an integer."
    }
    require(longValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Prompt alignment field $name must be a 32-bit integer."
    }
    return longValue.toInt()
}
internal fun JSONArray.requiredObject(index: Int, expectedFields: Set<String>): JSONObject {
    require(get(index) is JSONObject) { "Prompt alignment array item must be an object." }
    return getJSONObject(index).also { value ->
        require(value.keys().asSequence().toSet() == expectedFields) {
            "Prompt alignment array item contains unsupported fields."
        }
    }
}
internal fun String.isVisibleAsciiAlignmentText(): Boolean =
    isNotEmpty() && all { character -> character.code in 0x20..0x7e }
internal fun <T> List<T>.shuffledWith(entropy: LocalImagePromptAlignmentEntropy): List<T> {
    val result = toMutableList()
    for (index in result.lastIndex downTo 1) {
        val randomBytes = ByteArray(4)
        entropy.nextBytes(randomBytes)
        val unsigned = ((randomBytes[0].toLong() and 0xffL) shl 24) or
            ((randomBytes[1].toLong() and 0xffL) shl 16) or
            ((randomBytes[2].toLong() and 0xffL) shl 8) or
            (randomBytes[3].toLong() and 0xffL)
        val target = (unsigned % (index + 1).toLong()).toInt()
        val current = result[index]
        result[index] = result[target]
        result[target] = current
    }
    return result.toList()
}

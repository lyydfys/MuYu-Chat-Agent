package com.muyuchat.mca

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import org.json.JSONObject

/** Card-shape queries and bounded binary reads (T2B22-T3 split). */

internal fun looksLikeLegacyCard(root: JSONObject, data: JSONObject): Boolean {
        if (root.stringValue("schema") == "mca.assistant.card") return true
        val keys = arrayOf(
            "name", "char_name", "title", "description", "desc", "char_persona", "personality",
            "scenario", "world_scenario", "first_mes", "firstMessage", "greeting", "char_greeting",
            "mes_example", "example_dialogue", "system_prompt",
            "systemPrompt", "prompt", "instructions", "creator", "creator_notes"
        )
        return keys.any { key -> data.has(key) && !data.isNull(key) }
    }

internal fun JSONObject.firstStringValue(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            val value = opt(key)
            (value as? String)?.takeIf { it.isNotBlank() }
        }.orEmpty()

internal fun JSONObject.stringValue(key: String): String = (opt(key) as? String).orEmpty()

internal fun JSONObject.stringArray(vararg keys: String): List<String> {
        val array = keys.firstNotNullOfOrNull { key -> optJSONArray(key) } ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val value = array.opt(index) as? String ?: continue
                if (value.isNotBlank()) add(value)
            }
        }
    }

internal fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

internal fun inflateBounded(bytes: ByteArray, offset: Int, length: Int): ByteArray? {
        val inflater = Inflater()
        val output = ByteArrayOutputStream(minOf(8192, CharacterCardCodec.MAX_METADATA_TEXT_BYTES))
        val buffer = ByteArray(8192)
        return try {
            inflater.setInput(bytes, offset, length)
            while (true) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (_: DataFormatException) {
                    return null
                }
                if (count > 0) {
                    if (output.size() > CharacterCardCodec.MAX_METADATA_TEXT_BYTES - count) return null
                    output.write(buffer, 0, count)
                } else if (inflater.finished()) {
                    if (inflater.remaining != 0) return null
                    break
                } else {
                    if (inflater.needsDictionary() || inflater.needsInput()) return null
                    return null
                }
            }
            output.toByteArray()
        } finally {
            inflater.end()
        }
    }

package com.muyuchat.mca

import org.json.JSONObject

private const val JSON_DATA = "data"

internal fun parseJsonBytes(bytes: ByteArray): CharacterCardParseResult {
        if (bytes.size > CharacterCardCodec.MAX_JSON_BYTES) {
            return failure(CharacterCardParseErrorCode.INPUT_TOO_LARGE, "JSON card exceeds the bounded size limit.")
        }
        val raw = decodeUtf8(bytes) ?: return failure(CharacterCardParseErrorCode.INVALID_UTF8, "JSON card is not valid UTF-8.")
        return parseJsonInternal(raw, CharacterCardSource.JSON)
    }

internal fun parseJsonInternal(rawInput: String, source: CharacterCardSource): CharacterCardParseResult {
        if (rawInput.isEmpty()) return failure(CharacterCardParseErrorCode.EMPTY_INPUT, "Card JSON is empty.")
        if (rawInput.length > CharacterCardCodec.MAX_JSON_BYTES * 2) {
            return failure(CharacterCardParseErrorCode.INPUT_TOO_LARGE, "JSON card exceeds the bounded size limit.")
        }
        if (rawInput.utf8ByteCountExceeds(CharacterCardCodec.MAX_JSON_BYTES)) {
            return failure(CharacterCardParseErrorCode.INPUT_TOO_LARGE, "JSON card exceeds the bounded size limit.")
        }

        val rawJson = rawInput.removePrefix("\uFEFF")
        val shapeError = CharacterCardCodec.validateJsonBounds(rawJson)
        if (shapeError != null) return failure(CharacterCardParseErrorCode.INVALID_JSON, shapeError)

        val parsed = runCatching { JSONObject(rawJson) }.getOrElse { error ->
            return failure(CharacterCardParseErrorCode.INVALID_JSON, "Card JSON is malformed.", error)
        }
        return buildCard(parsed, rawJson, source)
    }

internal fun buildCard(
        root: JSONObject,
        rawJson: String,
        source: CharacterCardSource
    ): CharacterCardParseResult {
        val spec = root.stringValue("spec")
        if (spec.isNotBlank() && spec != "chara_card_v2" && spec != "chara_card_v3") {
            return failure(CharacterCardParseErrorCode.UNSUPPORTED_CARD, "spec: unsupported character-card format '$spec'.")
        }
        val format = when (spec) {
            "chara_card_v2" -> CharacterCardFormat.CC_V2
            "chara_card_v3" -> CharacterCardFormat.CC_V3
            else -> CharacterCardFormat.LEGACY_JSON
        }
        val data = if (format == CharacterCardFormat.LEGACY_JSON) {
            root.optJSONObject(JSON_DATA) ?: root
        } else {
            root.optJSONObject(JSON_DATA) ?: return failure(
                CharacterCardParseErrorCode.MISSING_CARD_DATA,
                "${format.name} card is missing its data object."
            )
        }

        if (format == CharacterCardFormat.LEGACY_JSON && !looksLikeLegacyCard(root, data)) {
            return failure(CharacterCardParseErrorCode.UNSUPPORTED_CARD, "JSON does not contain a supported character-card shape.")
        }

        val fieldPrefix = if (data === root) "" else "data."
        val stringFields = listOf("name", "char_name", "title", "description", "desc", "char_persona",
            "personality", "scenario", "world_scenario", "first_mes", "firstMessage", "greeting", "char_greeting",
            "mes_example", "example_dialogue", "system_prompt", "systemPrompt", "prompt", "instructions",
            "post_history_instructions", "postHistoryInstructions", "creator_notes", "creatorNotes", "creator",
            "character_version", "characterVersion")
        stringFields.firstOrNull { key -> data.has(key) && !data.isNull(key) && data.opt(key) !is String }?.let { key ->
            return failure(CharacterCardParseErrorCode.INVALID_JSON, "$fieldPrefix$key: expected a string.")
        }
        listOf("alternate_greetings", "alternateGreetings", "group_only_greetings", "groupOnlyGreetings", "tags")
            .forEach { key ->
                if (data.has(key) && !data.isNull(key)) {
                    val array = data.optJSONArray(key) ?: return failure(
                        CharacterCardParseErrorCode.INVALID_JSON, "$fieldPrefix$key: expected an array of strings."
                    )
                    for (index in 0 until array.length()) if (array.opt(index) !is String) return failure(
                        CharacterCardParseErrorCode.INVALID_JSON, "$fieldPrefix$key[$index]: expected a string."
                    )
                }
            }

        val extensionsJson = runCatching { data.optJSONObject("extensions")?.toString() }.getOrNull()
        return CharacterCardParseResult.Success(
            card = CharacterCard(
                format = format,
                specVersion = root.stringValue("spec_version"),
                name = data.firstStringValue("name", "char_name", "title"),
                description = data.firstStringValue("description", "desc", "char_persona"),
                personality = data.firstStringValue("personality"),
                scenario = data.firstStringValue("scenario", "world_scenario"),
                firstMessage = data.firstStringValue("first_mes", "firstMessage", "greeting", "char_greeting"),
                exampleDialogue = data.firstStringValue("mes_example", "example_dialogue"),
                systemPrompt = data.firstStringValue("system_prompt", "systemPrompt", "prompt", "instructions"),
                postHistoryInstructions = data.firstStringValue("post_history_instructions", "postHistoryInstructions"),
                creatorNotes = data.firstStringValue("creator_notes", "creatorNotes"),
                creator = data.firstStringValue("creator"),
                characterVersion = data.firstStringValue("character_version", "characterVersion"),
                alternateGreetings = data.stringArray("alternate_greetings", "alternateGreetings"),
                groupOnlyGreetings = data.stringArray("group_only_greetings", "groupOnlyGreetings"),
                tags = data.stringArray("tags"),
                rawJson = rawJson,
                extensionsJson = extensionsJson,
                rawDataJson = runCatching { data.toString() }.getOrNull()
            ),
            source = source
        )
    }

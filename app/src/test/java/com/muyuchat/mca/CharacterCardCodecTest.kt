package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater

class CharacterCardCodecTest {
    @Test
    fun parsesCcv2JsonAndPreservesUnknownExtensionsInertly() {
        val raw = """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "unrecognized_top_level": {"keep": [1, true, "value"]},
              "data": {
                "name": "中文旅行记录员",
                "description": "用中文整理旅途见闻。",
                "tags": ["旅行", "中文"],
                "character_book": {
                  "name": "travel lore",
                  "entries": [{"keys": ["海边"], "content": "海风很大。"}]
                },
                "extensions": {
                  "unsafe": {
                    "script": "do_not_execute()",
                    "macro": "{{never_expand}}",
                    "regex": "^.*$"
                  }
                }
              }
            }
        """.trimIndent()

        val result = CharacterCardCodec.parseJson(raw)
        val success = assertSuccess(result)

        assertEquals(CharacterCardFormat.CC_V2, success.card.format)
        assertEquals(CharacterCardSource.JSON, success.source)
        assertEquals("中文旅行记录员", success.card.name)
        assertEquals(listOf("旅行", "中文"), success.card.tags)
        assertEquals(raw, success.card.toJsonString())
        assertEquals(
            "do_not_execute()",
            success.card.toJson()
                .getJSONObject("data")
                .getJSONObject("extensions")
                .getJSONObject("unsafe")
                .getString("script")
        )
        assertEquals("{{never_expand}}", success.card.extensionsOrNull()
            ?.getJSONObject("unsafe")
            ?.getString("macro"))
        val assistant = AssistantRecord.fromCharacterCard(success.card)
        assertEquals("中文旅行记录员", assistant.name)
        assertEquals(raw, assistant.characterCardJson)
        val persisted = AssistantRecord.fromJson(assistant.toJson())
        assertEquals(raw, persisted.characterCardJson)
        assertEquals(
            "^.*$",
            org.json.JSONObject(requireNotNull(persisted.characterCardJson))
                .getJSONObject("data")
                .getJSONObject("extensions")
                .getJSONObject("unsafe")
                .getString("regex")
        )
        assertEquals(
            "海风很大。",
            org.json.JSONObject(requireNotNull(persisted.characterCardJson))
                .getJSONObject("data")
                .getJSONObject("character_book")
                .getJSONArray("entries")
                .getJSONObject(0)
                .getString("content")
        )
    }

    @Test
    fun parsesLegacyMcaAssistantJsonThroughTheCardBridge() {
        val raw = """{"schema":"mca.assistant.card","version":1,"name":"Legacy","systemPrompt":"Keep this prompt."}"""

        val success = assertSuccess(CharacterCardCodec.parseJson(raw))

        assertEquals(CharacterCardFormat.LEGACY_JSON, success.card.format)
        assertEquals("Legacy", success.card.name)
        assertEquals("Keep this prompt.", success.card.toAssistantRecord().systemPrompt)
    }

    @Test
    fun legacyMcaAssistantExportRetainsItsExplicitDefaultPrompt() {
        val defaultPrompt = GenerationParams().systemPrompt
        val raw = JSONObject()
            .put("schema", "mca.assistant.card")
            .put("version", 1)
            .put("name", "默认助手")
            .put("systemPrompt", defaultPrompt)
            .toString()

        val card = assertSuccess(CharacterCardCodec.parseJson(raw)).card

        assertEquals(defaultPrompt, card.toAssistantRecord().systemPrompt)
        assertFalse(isMcaDefaultCharacterCardPrompt(card))
    }

    @Test
    fun parsesLegacyV1AliasesWithoutRewritingTheRawCard() {
        val raw = """{"char_name":"旧版角色","char_persona":"保留旧字段","world_scenario":"测试","char_greeting":"你好"}"""

        val success = assertSuccess(CharacterCardCodec.parseJson(raw))

        assertEquals(CharacterCardFormat.LEGACY_JSON, success.card.format)
        assertEquals("旧版角色", success.card.name)
        assertEquals("保留旧字段", success.card.description)
        assertEquals("测试", success.card.scenario)
        assertEquals("你好", success.card.firstMessage)
        assertEquals(raw, success.card.toJsonString())
        assertEquals("旧版角色", success.card.toAssistantRecord().name)
    }

    @Test
    fun cardWithMcaBoilerplateKeepsItsGreetingInsteadOfUsingTheDefaultPrompt() {
        val raw = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"空白角色","system_prompt":"${GenerationParams().systemPrompt}","first_mes":"欢迎来到故事现场。","character_book":{"entries":[{"id":1,"constant":true,"content":"角色设定"}]}}}"""

        val card = assertSuccess(CharacterCardCodec.parseJson(raw)).card
        val assistant = card.toAssistantRecord()

        assertEquals("开场白：\n欢迎来到故事现场。", assistant.systemPrompt)
        assertFalse(assistant.systemPrompt.contains(GenerationParams().systemPrompt))
        assertEquals(raw, assistant.characterCardJson)
        assertEquals("欢迎来到故事现场。", assistant.newConversationSession(null, null).messages.single().content)
        assertEquals(assistant.systemPrompt, assistant.newConversationSession(null, null).assistantSnapshot?.systemPrompt)
        assertEquals(assistant.systemPrompt, GenerationParams.fromJson(assistant.paramsJson).systemPrompt)
    }

    @Test
    fun cardWithoutPromptFieldsUsesItsNameInsteadOfTheMcaAssistantPrompt() {
        val raw = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"空白角色","character_book":{"entries":[{"id":1,"constant":true,"content":"角色设定"}]}}}"""

        val assistant = assertSuccess(CharacterCardCodec.parseJson(raw)).card.toAssistantRecord()

        assertEquals("你是空白角色。请保持角色身份，自然回应。", assistant.systemPrompt)
        assertFalse(assistant.systemPrompt.contains(GenerationParams().systemPrompt))
        assertEquals(raw, assistant.characterCardJson)
        assertEquals(assistant.systemPrompt, assistant.newConversationSession(null, null).assistantSnapshot?.systemPrompt)
    }

    @Test
    fun mcaBoilerplateDoesNotHideAuthoredCharacterCardPromptFields() {
        val raw = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"港口向导","system_prompt":"${GenerationParams().systemPrompt}","description":"熟悉旧港的小巷。","personality":"耐心而谨慎。","post_history_instructions":"不要替用户决定行动。"}}"""

        val card = assertSuccess(CharacterCardCodec.parseJson(raw)).card
        val assistant = card.toAssistantRecord()

        assertFalse(assistant.systemPrompt.contains(GenerationParams().systemPrompt))
        assertTrue(assistant.systemPrompt.contains("熟悉旧港的小巷。"))
        assertTrue(assistant.systemPrompt.contains("耐心而谨慎。"))
        assertTrue(assistant.systemPrompt.contains("不要替用户决定行动。"))
        assertEquals(raw, assistant.characterCardJson)
        assertEquals(assistant.systemPrompt, assistant.toConversationSnapshot(1L).systemPrompt)
    }

    @Test
    fun asciiParenthesesMcaBoilerplateIsRecognizedAsTheSameDefault() {
        val defaultPrompt = GenerationParams().systemPrompt
            .replace('（', '(').replace('）', ')')
        val raw = JSONObject()
            .put("spec", "chara_card_v3")
            .put("spec_version", "3.0")
            .put("data", JSONObject()
                .put("name", "港口向导")
                .put("system_prompt", defaultPrompt)
                .put("description", "熟悉旧港的小巷。"))
            .toString()

        val card = assertSuccess(CharacterCardCodec.parseJson(raw)).card

        assertTrue(isMcaDefaultCharacterCardPrompt(card))
        assertEquals("角色描述：\n熟悉旧港的小巷。", card.toAssistantRecord().systemPrompt)
        assertEquals(raw, card.toAssistantRecord().characterCardJson)
    }

    @Test
    fun defaultPromptWithAuthoredInstructionsIsNotStripped() {
        val sourcePrompt = GenerationParams().systemPrompt + "\n请始终保持港口向导的角色身份。"
        val raw = JSONObject()
            .put("spec", "chara_card_v3")
            .put("spec_version", "3.0")
            .put("data", JSONObject().put("name", "港口向导").put("system_prompt", sourcePrompt))
            .toString()

        val card = assertSuccess(CharacterCardCodec.parseJson(raw)).card

        assertFalse(isMcaDefaultCharacterCardPrompt(card))
        assertEquals(sourcePrompt, card.toAssistantRecord().systemPrompt)
    }

    @Test
    fun authoredPromptOverridesStaleDefaultParamsThroughPersistenceAndSnapshot() {
        val raw = JSONObject()
            .put("spec", "chara_card_v3")
            .put("spec_version", "3.0")
            .put("data", JSONObject()
                .put("name", "港口向导")
                .put("system_prompt", "请始终保持港口向导的角色身份。")
                .put("description", "熟悉旧港的小巷。")
                .put("paramsJson", GenerationParams().toAssistantGenerationJson()))
            .toString()

        val imported = assertSuccess(CharacterCardCodec.parseJson(raw)).card.toAssistantRecord()
        val persisted = AssistantRecord.fromJson(imported.toJson())
        val snapshot = persisted.toConversationSnapshot(1L)

        assertTrue(imported.systemPrompt.startsWith("请始终保持港口向导的角色身份。"))
        assertFalse(imported.systemPrompt.contains(GenerationParams().systemPrompt))
        assertEquals(imported.systemPrompt, persisted.systemPrompt)
        assertEquals(imported.systemPrompt, GenerationParams.fromJson(persisted.paramsJson).systemPrompt)
        assertEquals(imported.systemPrompt, snapshot.systemPrompt)
        assertEquals(raw, persisted.characterCardJson)
    }

    @Test
    fun cardsWithTheSamePromptKeepSeparateIdsAndOriginalJson() {
        val firstRaw = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"甲","system_prompt":"共享提示词"}}"""
        val secondRaw = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"乙","system_prompt":"共享提示词"}}"""

        val first = assertSuccess(CharacterCardCodec.parseJson(firstRaw)).card.toAssistantRecord()
        val second = assertSuccess(CharacterCardCodec.parseJson(secondRaw)).card.toAssistantRecord()

        assertTrue(first.id != second.id)
        assertEquals("共享提示词", first.systemPrompt)
        assertEquals("共享提示词", second.systemPrompt)
        assertEquals(firstRaw, first.characterCardJson)
        assertEquals(secondRaw, second.characterCardJson)
    }

    @Test
    fun parsesTextMetadataWithUtf8ChinesePayload() {
        val card = ccv2Card(name = "中文 tEXt", description = "这是 UTF-8 中文内容。")
        val payload = Base64.getEncoder().encodeToString(card.toByteArray(StandardCharsets.UTF_8))
        val png = png(
            chunk("IHDR", ihdr()),
            chunk("tEXt", textChunk("chara", payload)),
            chunk("IEND", byteArrayOf())
        )

        val success = assertSuccess(CharacterCardCodec.parsePng(png))

        assertEquals(CharacterCardSource.PNG_CHARA, success.source)
        assertEquals(CharacterCardFormat.CC_V2, success.card.format)
        assertEquals("中文 tEXt", success.card.name)
        assertEquals("这是 UTF-8 中文内容。", success.card.description)
    }

    @Test
    fun pngFilePickerStreamImportProducesAnAssistantSnapshotWithPersonaText() {
        val rawCard = """
            {
              "spec":"chara_card_v2",
              "spec_version":"2.0",
              "data":{
                "name":"林间旅人",
                "description":"住在林间小屋的旅行者。",
                "personality":"温和、好奇。",
                "scenario":"雨后的森林。",
                "first_mes":"你也在躲雨吗？",
                "mes_example":"<START>\n{{char}}：喝杯热茶吧。"
              }
            }
        """.trimIndent()
        val payload = Base64.getEncoder().encodeToString(rawCard.toByteArray(StandardCharsets.UTF_8))
        val png = png(
            chunk("IHDR", ihdr()),
            chunk("tEXt", textChunk("chara", payload)),
            chunk("IEND", byteArrayOf())
        )

        val parsed = assertSuccess(CharacterCardCodec.parse(ByteArrayInputStream(png)))
        val assistant = parsed.card.toAssistantRecord(AssistantRecord.default(systemPrompt = "默认提示词"))
        val snapshot = assistant.toConversationSnapshot(capturedAt = 1L)

        assertEquals("林间旅人", assistant.name)
        assertTrue(assistant.systemPrompt.contains("住在林间小屋的旅行者。"))
        assertTrue(assistant.systemPrompt.contains("温和、好奇。"))
        assertTrue(assistant.systemPrompt.contains("雨后的森林。"))
        assertTrue(assistant.systemPrompt.contains("开场白"))
        assertTrue(assistant.systemPrompt.contains("你也在躲雨吗？"))
        assertEquals("你也在躲雨吗？", assistant.initialGreetingMessage()?.content)
        assertTrue(assistant.systemPrompt.contains("喝杯热茶吧。"))
        assertEquals(assistant.systemPrompt, snapshot.systemPrompt)
        assertEquals("林间旅人", snapshot.name)
        assertEquals(rawCard, assistant.characterCardJson)
    }

    @Test
    fun ccv3CharacterBookStaysInTheAssistantExportAndItsPersonaReachesTheSessionSnapshot() {
        val raw = """
            {
              "spec":"chara_card_v3",
              "spec_version":"3.0",
              "data":{
                "name":"海港向导",
                "description":"熟悉旧港每一条小巷。",
                "system_prompt":"始终保持角色身份。",
                "post_history_instructions":"不要替用户决定行动。",
                "character_book":{
                  "name":"旧港设定",
                  "entries":[{"uid":42,"keys":["旧灯塔"],"content":"旧灯塔每晚九点点亮。"}]
                }
              }
            }
        """.trimIndent()

        val card = assertSuccess(CharacterCardCodec.parseJson(raw)).card
        val assistant = card.toAssistantRecord(AssistantRecord.default(systemPrompt = "默认提示词"))
        val snapshot = assistant.toConversationSnapshot(capturedAt = 2L)
        val importedWorldBook = WorldBookCodec.parse(
            JSONObject(requireNotNull(assistant.characterCardJson)).getJSONObject("data").getJSONObject("character_book"),
            scope = WorldBookScope.ASSISTANT,
            assistantId = assistant.id
        )
        val selectedLore = WorldBookResolver.select(
            books = listOf(importedWorldBook),
            messages = listOf(ChatMessage(Role.USER, "旧灯塔什么时候点亮？")),
            assistantId = assistant.id,
            chatSessionId = "chat",
            tokenBudget = 128
        )

        assertTrue(snapshot.systemPrompt.contains("始终保持角色身份。"))
        assertTrue(snapshot.systemPrompt.contains("熟悉旧港每一条小巷。"))
        assertTrue(snapshot.systemPrompt.contains("不要替用户决定行动。"))
        assertEquals(listOf("42"), selectedLore.selectedEntryIds)
        assertTrue(selectedLore.context.contains("旧灯塔每晚九点点亮。"))
    }

    @Test
    fun parsesUrlSafeBase64CharacterCardMetadata() {
        val card = ccv2Card(name = "URL safe", description = "accepted")
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(card.toByteArray(StandardCharsets.UTF_8))
        val png = png(
            chunk("IHDR", ihdr()),
            chunk("tEXt", textChunk("chara", payload)),
            chunk("IEND", byteArrayOf())
        )

        val success = assertSuccess(CharacterCardCodec.parsePng(png))

        assertEquals("URL safe", success.card.name)
    }

    @Test
    fun parsesCompressedZtextMetadata() {
        val card = ccv2Card(name = "zTXt card", description = "compressed metadata")
        val payload = Base64.getEncoder().encodeToString(card.toByteArray(StandardCharsets.UTF_8))
        val png = png(
            chunk("IHDR", ihdr()),
            chunk("zTXt", zTextChunk("chara", payload)),
            chunk("IEND", byteArrayOf())
        )

        val success = assertSuccess(CharacterCardCodec.parsePng(png))

        assertEquals(CharacterCardSource.PNG_CHARA, success.source)
        assertEquals("zTXt card", success.card.name)
    }

    @Test
    fun ccv3ItextPrecedesCharaRegardlessOfChunkOrder() {
        val v2 = ccv2Card(name = "older chara", description = "older")
        val v3 = """
            {"spec":"chara_card_v3","spec_version":"3.0","data":{
              "name":"新的 CCv3",
              "description":"CCv3 wins even when it is later in the PNG.",
              "assets":[{"type":"icon","uri":"not-executed"}],
              "extensions":{"opaque":{"macro":"{{no-op}}"}}
            }}
        """.trimIndent()
        val charaPayload = Base64.getEncoder().encodeToString(v2.toByteArray(StandardCharsets.UTF_8))
        val ccv3Payload = Base64.getEncoder().encodeToString(v3.toByteArray(StandardCharsets.UTF_8))
        val png = png(
            chunk("IHDR", ihdr()),
            chunk("tEXt", textChunk("chara", charaPayload)),
            chunk("zTXt", zTextChunk("Comment", "ignored metadata")),
            chunk("iTXt", iTextChunk("ccv3", ccv3Payload, compressed = true)),
            chunk("IEND", byteArrayOf())
        )

        val success = assertSuccess(CharacterCardCodec.parsePng(png))

        assertEquals(CharacterCardSource.PNG_CCV3, success.source)
        assertEquals(CharacterCardFormat.CC_V3, success.card.format)
        assertEquals("新的 CCv3", success.card.name)
        assertEquals("{{no-op}}", success.card.extensionsOrNull()
            ?.getJSONObject("opaque")
            ?.getString("macro"))
    }

    @Test
    fun malformedCcv3DoesNotFallBackToChara() {
        val v2 = ccv2Card(name = "valid fallback", description = "must not be selected")
        val charaPayload = Base64.getEncoder().encodeToString(v2.toByteArray(StandardCharsets.UTF_8))
        val png = png(
            chunk("IHDR", ihdr()),
            chunk("tEXt", textChunk("chara", charaPayload)),
            chunk("iTXt", iTextChunk("ccv3", "not-base64-json", compressed = false)),
            chunk("IEND", byteArrayOf())
        )

        val failure = assertFailure(CharacterCardCodec.parsePng(png))

        assertEquals(CharacterCardParseErrorCode.INVALID_CARD_METADATA, failure.error.code)
    }

    @Test
    fun invalidPngReturnsFailureInsteadOfThrowing() {
        val truncated = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0)

        val failure = assertFailure(CharacterCardCodec.parsePng(truncated))

        assertEquals(CharacterCardParseErrorCode.INVALID_PNG, failure.error.code)
    }

    @Test
    fun invalidStandardFieldReportsItsJsonPath() {
        val result = CharacterCardCodec.parseJson(
            """{"spec":"chara_card_v2","data":{"name":"card","tags":["valid",12]}}"""
        )
        val failure = assertFailure(result)
        assertEquals(CharacterCardParseErrorCode.INVALID_JSON, failure.error.code)
        assertTrue(failure.error.message.contains("data.tags[1]"))
    }

    @Test
    fun rejectsOversizedOrUnsupportedIhdrBeforeCardMetadata() {
        val payload = Base64.getEncoder().encodeToString(ccv2Card("valid", "card").toByteArray())
        val metadata = chunk("tEXt", textChunk("chara", payload))
        val oversized = ihdr().apply {
            this[0] = 0x7f
            this[1] = 0xff.toByte()
            this[2] = 0xff.toByte()
            this[3] = 0xff.toByte()
        }
        val unsupportedDepth = ihdr().apply { this[8] = 3 }

        listOf(oversized, unsupportedDepth).forEach { header ->
            val result = CharacterCardCodec.parsePng(
                png(chunk("IHDR", header), metadata, chunk("IEND", byteArrayOf()))
            )
            assertEquals(CharacterCardParseErrorCode.INVALID_PNG, assertFailure(result).error.code)
        }
    }

    @Test
    fun rejectsInvalidCrcAndCompressedMetadataBeforeImport() {
        val payload = Base64.getEncoder().encodeToString(ccv2Card("valid", "card").toByteArray())
        val textChunk = chunk("tEXt", textChunk("chara", payload))
        val badCrc = textChunk.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val badCompression = chunk("zTXt", "chara".toByteArray() + byteArrayOf(0, 0, 1, 2, 3))

        listOf(badCrc, badCompression).forEach { metadata ->
            val result = CharacterCardCodec.parsePng(
                png(chunk("IHDR", ihdr()), metadata, chunk("IEND", byteArrayOf()))
            )
            assertTrue(result is CharacterCardParseResult.Failure)
        }
    }

    private fun assertSuccess(result: CharacterCardParseResult): CharacterCardParseResult.Success {
        assertTrue("Expected successful parse but got $result", result is CharacterCardParseResult.Success)
        return result as CharacterCardParseResult.Success
    }

    private fun assertFailure(result: CharacterCardParseResult): CharacterCardParseResult.Failure {
        assertTrue("Expected parse failure but got $result", result is CharacterCardParseResult.Failure)
        return result as CharacterCardParseResult.Failure
    }

    private fun ccv2Card(name: String, description: String): String =
        """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"$name","description":"$description"}}"""

    private fun png(vararg chunks: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(PNG_SIGNATURE)
        chunks.forEach { chunk -> output.write(chunk) }
        return output.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
        val output = ByteArrayOutputStream()
        output.write(u32(data.size.toLong()))
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        output.write(u32(crc.value))
        return output.toByteArray()
    }

    private fun ihdr(): ByteArray = byteArrayOf(
        0, 0, 0, 1,
        0, 0, 0, 1,
        8, 2, 0, 0, 0
    )

    private fun textChunk(keyword: String, value: String): ByteArray =
        keyword.toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0) +
            value.toByteArray(StandardCharsets.ISO_8859_1)

    private fun zTextChunk(keyword: String, value: String): ByteArray =
        keyword.toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0, 0) +
            deflate(value.toByteArray(StandardCharsets.ISO_8859_1))

    private fun iTextChunk(keyword: String, value: String, compressed: Boolean): ByteArray {
        val text = value.toByteArray(StandardCharsets.UTF_8)
        val payload = if (compressed) deflate(text) else text
        return keyword.toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0) +
            byteArrayOf(if (compressed) 1.toByte() else 0.toByte(), 0.toByte()) +
            byteArrayOf(0) +
            byteArrayOf(0) +
            payload
    }

    private fun deflate(value: ByteArray): ByteArray {
        val deflater = Deflater()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        try {
            deflater.setInput(value)
            deflater.finish()
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
        } finally {
            deflater.end()
        }
        return output.toByteArray()
    }

    private fun u32(value: Long): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte()
    )

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )
}

package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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

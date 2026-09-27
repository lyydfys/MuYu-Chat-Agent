package com.muyuchat.mca

import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudImageProviderBatchTest {
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
        0, 0, 0, 1, 0, 0, 0, 1
    )

    @Test
    fun parserReturnsEveryImageEvenWhenBytesAreIdentical() = runBlocking {
        val encoded = Base64.getEncoder().encodeToString(png)
        val response = JSONObject().put("data", JSONArray()
            .put(JSONObject().put("b64_json", encoded).put("revised_prompt", "first"))
            .put(JSONObject().put("b64_json", encoded).put("revised_prompt", "second")))

        val outputs = CloudImageProvider().parseOpenAiImageResponse(response.toString())

        assertEquals(2, outputs.size)
        assertEquals(listOf("first", "second"), outputs.map { it.revisedPrompt })
        assertTrue(outputs.all { it.mimeType == "image/png" && it.bytes.contentEquals(png) })
    }

    @Test
    fun malformedSecondOutputRejectsTheWholeResponse() {
        val encoded = Base64.getEncoder().encodeToString(png)
        val response = JSONObject().put("data", JSONArray()
            .put(JSONObject().put("b64_json", encoded))
            .put(JSONObject().put("b64_json", "not valid base64!?")))

        assertThrows(Exception::class.java) {
            runBlocking { CloudImageProvider().parseOpenAiImageResponse(response.toString()) }
        }
    }

    @Test
    fun capabilityReportsNativeAndSequentialCountSeparately() {
        val provider = CloudImageProvider()
        val openAi = CloudApiConfig(imageApiFormat = CloudImageApiFormat.OPENAI_IMAGES,
            imageModel = "gpt-image-1.5")
        assertEquals(8, provider.capabilities(openAi).maxNativeCount)
        assertTrue(provider.capabilities(openAi).supportsSequentialCount)
        assertEquals(1, provider.capabilities(openAi.copy(imageModel = "dall-e-3")).maxNativeCount)
        assertEquals(1, provider.capabilities(openAi.copy(
            imageApiFormat = CloudImageApiFormat.CUSTOM_PATH)).maxNativeCount)
    }
}

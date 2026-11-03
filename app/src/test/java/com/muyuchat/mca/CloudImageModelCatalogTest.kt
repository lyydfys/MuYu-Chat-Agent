package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image editor's "获取图像模型列表" action used to be missing entirely: chat engines could list
 * provider models, image engines had to be typed from memory. These tests pin the two things that
 * decide whether the action works: which URL the image protocol builds, and how a provider payload
 * is turned into selectable ids.
 */
class CloudImageModelCatalogTest {

    @Test
    fun `openai images builds the standard models endpoint`() {
        val url = cloudImageModelsUrl(
            config(
                baseUrl = "https://api.openai.com/v1",
                imageApiFormat = CloudImageApiFormat.OPENAI_IMAGES
            )
        )
        assertEquals("https://api.openai.com/v1/models", url)
    }

    @Test
    fun `dashscope native base url is redirected to its compatible-mode models`() {
        val url = cloudImageModelsUrl(
            config(
                baseUrl = "https://dashscope.aliyuncs.com",
                imageApiFormat = CloudImageApiFormat.DASHSCOPE_IMAGE
            )
        )
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/models", url)
    }

    @Test
    fun `an already complete endpoint is not doubled`() {
        assertEquals(
            "https://gateway.example/v1/models",
            cloudImageModelsUrl(
                config(
                    baseUrl = "https://gateway.example/v1/models",
                    imageApiFormat = CloudImageApiFormat.OPENAI_IMAGES
                )
            )
        )
        // A DashScope base that already carries the compatible-mode prefix only needs `/models`.
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
            cloudImageModelsUrl(
                config(
                    baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    imageApiFormat = CloudImageApiFormat.DASHSCOPE_IMAGE
                )
            )
        )
    }

    @Test
    fun `an empty base url reports the missing field instead of building a relative url`() {
        assertNull(
            cloudImageModelsUrl(
                config(baseUrl = "  ", imageApiFormat = CloudImageApiFormat.CUSTOM_PATH)
            )
        )
    }

    @Test
    fun `openai style payload yields the model ids in order`() {
        val body = """
            {"object":"list","data":[
              {"id":"gpt-image-1.5","object":"model"},
              {"id":"dall-e-3","object":"model"}
            ]}
        """.trimIndent()

        assertEquals(listOf("gpt-image-1.5", "dall-e-3"), parseCloudImageModelsResponse(body))
    }

    @Test
    fun `lenient gateway shapes are accepted and de-duplicated`() {
        val body = """
            {"models":["qwen-image-2.0-pro","qwen-image-2.0-pro",{"id":"wanx-v1"}]}
        """.trimIndent()

        assertEquals(listOf("qwen-image-2.0-pro", "wanx-v1"), parseCloudImageModelsResponse(body))
    }

    @Test
    fun `an empty or unparseable payload fails with an actionable message`() {
        val blank = runCatching { parseCloudImageModelsResponse("") }.exceptionOrNull()
        assertTrue(blank?.message.orEmpty().contains("没有返回模型列表"))

        val empty = runCatching { parseCloudImageModelsResponse("""{"data":[]}""") }.exceptionOrNull()
        assertTrue(empty?.message.orEmpty().contains("请手动填写图像模型名"))
    }

    private fun config(baseUrl: String, imageApiFormat: CloudImageApiFormat) = CloudApiConfig(
        enabled = true,
        baseUrl = baseUrl,
        apiKey = "test-key",
        imageApiFormat = imageApiFormat
    )
}

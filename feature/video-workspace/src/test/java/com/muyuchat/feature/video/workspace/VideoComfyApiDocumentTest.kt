package com.muyuchat.feature.video.workspace

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bounded, strict UTF-8 reading of a picked ComfyUI API-format document. */
class VideoComfyApiDocumentTest {

    @Test
    fun `reads valid UTF-8 json and strips a leading BOM`() {
        val json = """{"3":{"class_type":"KSampler","inputs":{"prompt":"一只猫"}}}"""
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            json.toByteArray(StandardCharsets.UTF_8)
        assertEquals(json, readComfyApiJson(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `rejects a file above the 4 MiB ceiling`() {
        val tooLarge = ByteArray(MAX_COMFY_API_JSON_BYTES + 1) { 'a'.code.toByte() }
        val failure = runCatching { readComfyApiJson(ByteArrayInputStream(tooLarge)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("4 MiB"))
    }

    @Test
    fun `rejects malformed UTF-8 instead of replacing bytes`() {
        val malformed = byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)
        val failure = runCatching { readComfyApiJson(ByteArrayInputStream(malformed)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("UTF-8"))
    }

    @Test
    fun `rejects an empty document`() {
        val failure = runCatching { readComfyApiJson(ByteArrayInputStream(ByteArray(0))) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("为空"))
    }
}

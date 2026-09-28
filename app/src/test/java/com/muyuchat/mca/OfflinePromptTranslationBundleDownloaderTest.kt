package com.muyuchat.mca

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfflinePromptTranslationBundleDownloaderTest {
    @Test
    fun `generated manifest is the pinned verifier fixture`() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream(
            "offline_translation/hy-mt2/translation_manifest.json"
        )).bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(fixture.fields(), hyMt2PinnedManifest().fields())
    }

    @Test
    fun `packaged official llama license converts only to the pinned notice bytes`() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val license = sequenceOf(
            File(working, "src/main/assets/offline_translation/llama.cpp-LICENSE.txt"),
            File(working, "app/src/main/assets/offline_translation/llama.cpp-LICENSE.txt")
        ).firstOrNull { it.isFile } ?: error("Packaged llama.cpp LICENSE is unavailable")
        val upstream = license.readBytes()

        assertEquals(1_078, upstream.size)
        val generated = runtimeNoticeFromOfficialLicense(upstream)
        assertEquals(1_099, generated.size)
        assertEquals(
            OfflinePromptTranslationContract.RUNTIME_NOTICE_ARTIFACT_SHA256,
            MessageDigest.getInstance("SHA-256").digest(generated)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        )

        val modified = upstream.clone().also { it[0] = 'X'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            runtimeNoticeFromOfficialLicense(modified)
        }
    }

    private fun JSONObject.fields(): Map<String, Any?> = keys().asSequence().filterNotNull().associateWith { key ->
        when (val value = get(key)) {
            is JSONObject -> value.fields()
            is Number -> value.toString()
            else -> value
        }
    }
}

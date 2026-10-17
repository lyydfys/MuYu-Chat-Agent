package com.muyuchat.core.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QairtLightweightRecommendationTest {
    @Test
    fun lightweightOfficialSourceIsIndependentAndAvailableWithoutCertification() {
        val light = DEFAULT_RECOMMENDED_MODELS.single { it.id == "qwen3_06b_qairt_w4a16" }
        val original = DEFAULT_RECOMMENDED_MODELS.single { it.id == "qwen3_4b_2507_qairt_w4a16" }
        assertEquals("qualcomm/Qwen3-0.6B", light.repoId)
        assertEquals("f0864d635cfbd6f5e43f29d19ce0a01a5fa3f1e4", light.revision)
        assertEquals(RecommendedChatRuntime.GENIEX_QAIRT, light.chatRuntime)
        assertEquals(ModelRepositoryProvider.HUGGING_FACE, light.provider)
        assertEquals(RecommendedModelDownloadPolicy.ALL_DEVICES, light.downloadPolicy)
        assertTrue(light.downloadable && light.visibleInRecommendations)
        assertNull(light.downloadBlockReason)
        assertEquals("qualcomm/Qwen3-4B-Instruct-2507", original.repoId)
        assertTrue(original.downloadable && original.visibleInRecommendations)
    }

    @Test
    fun pinnedPublisherMobileVariantRemainsSelectableAlongsideGenericFallback() {
        // Mirrors the two relevant published keys at the immutable repository revision.
        val release = JSONObject("""{"precisions":{"w4a16":{"chipset_assets":{
            "qualcomm-qcs8275":{"geniex_qairt":{"download_url":"https://example.test/qcs.zip"}},
            "qualcomm-snapdragon-8-elite-for-galaxy":{"geniex_qairt":{"download_url":"https://example.test/mobile.zip"}}
        }}}}""")
        assertEquals("https://example.test/mobile.zip",
            release.selectGenieXQairtAsset(listOf("qualcomm-snapdragon-8-elite")).downloadUrl)
        assertEquals("https://example.test/qcs.zip",
            release.selectGenieXQairtAsset(listOf("unknown-device")).downloadUrl)
    }
}

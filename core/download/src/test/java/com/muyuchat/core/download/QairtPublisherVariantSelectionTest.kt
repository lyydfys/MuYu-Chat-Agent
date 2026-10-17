package com.muyuchat.core.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class QairtPublisherVariantSelectionTest {
    private val elite = "qualcomm-snapdragon-8-elite"
    private val gen5 = "qualcomm-snapdragon-8-elite-gen5"
    private val qcs = "qualcomm-qcs8275"

    private fun assets(vararg targets: String): JSONObject {
        val chipsets = JSONObject()
        targets.forEach { target ->
            chipsets.put(
                target,
                JSONObject().put(
                    "geniex_qairt",
                    JSONObject().put("download_url", "https://example.com/$target.zip")
                )
            )
        }
        return JSONObject().put(
            "precisions",
            JSONObject().put("w4a16", JSONObject().put("chipset_assets", chipsets))
        )
    }

    @Test
    fun genericExactTargetRemainsPreferredOverVendorVariant() {
        val selected = assets(qcs, "$elite-for-galaxy", elite)
            .selectGenieXQairtAsset(listOf(elite))

        assertEquals(elite, selected.chipset)
        assertEquals("https://example.com/$elite.zip", selected.downloadUrl)
    }

    @Test
    fun publisherVariantOfFirstPreferencePrecedesUnrelatedAndNextTargets() {
        val selected = assets(qcs, gen5, "$elite-for-galaxy")
            .selectGenieXQairtAsset(listOf(" $elite ", gen5))

        assertEquals("$elite-for-galaxy", selected.chipset)
        assertEquals("https://example.com/$elite-for-galaxy.zip", selected.downloadUrl)
    }

    @Test
    fun explicitVendorVariantPreservesItsRequestedIdentity() {
        val selected = assets(qcs, elite, "$elite-for-galaxy")
            .selectGenieXQairtAsset(listOf("$elite-for-galaxy", elite))

        assertEquals("$elite-for-galaxy", selected.chipset)
    }

    @Test
    fun unknownAndEmptyPreferencesStillSelectPublishedFallback() {
        val published = assets(qcs, "$elite-for-galaxy")

        assertEquals(qcs, published.selectGenieXQairtAsset(listOf("unknown-chipset")).chipset)
        assertEquals(qcs, published.selectGenieXQairtAsset(emptyList()).chipset)
    }
}

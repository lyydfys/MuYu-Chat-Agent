package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViggleV021LoraDownloaderTest {
    @Test
    fun pinsOfficialSixStepAdapterIntegrityMetadata() {
        assertEquals(679_604_800L, ViggleV021LoraDownloader.EXPECTED_SIZE_BYTES)
        assertEquals(
            "bafb91d0047df3f9b8a5a850b0c967f051164314d8aad778dfa34d9c24ec345b",
            ViggleV021LoraDownloader.EXPECTED_SHA256
        )
        assertTrue(ViggleV021LoraDownloader.DISPLAY_NAME.contains("v0.2.1-6step"))
    }
}

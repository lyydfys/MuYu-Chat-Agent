package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenImage21BundleCompatibilityTest {
    @Test
    fun qwen21FamilyAliasUsesQwenImageBundleRoute() {
        assertEquals(LocalImageModelFamily.QWEN_IMAGE, LocalImageModelFamily.from("QWEN_IMAGE_21"))
        assertEquals(LocalImageModelFamily.QWEN_IMAGE, LocalImageModelFamily.from("qwen-image-2.1"))
    }

    @Test
    fun legacyVaeProfileFingerprintIsRecognizedOnlyByQwenMigration() {
        assertTrue(
            isQwenImage21LegacyVaeProfileFingerprint(
                "2525485bba44d4d8176d05522fa7e8b4b1080c9e7b7df18dfde7d58e667ff055"
            )
        )
    }
}

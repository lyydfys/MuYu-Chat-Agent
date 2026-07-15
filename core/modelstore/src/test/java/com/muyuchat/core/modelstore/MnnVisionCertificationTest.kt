package com.muyuchat.core.modelstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MnnVisionCertificationTest {
    @Test
    fun `fingerprint migration revokes visual acceptance`() {
        val validated = ModelManifest(
            id = "mnn-vision",
            displayName = "MNN vision",
            path = "/models/mnn-vision",
            runtime = ChatModelRuntime.MNN,
            source = ModelSource.MODELSCOPE,
            repoId = "MNN/Qwen3.5-0.8B-MNN",
            revision = "validated-revision",
            fileName = "mnn-vision",
            sizeBytes = 100L,
            sha256 = "old-sha",
            visionValidated = true
        )

        val migrated = validated.withMigratedMnnFingerprint(
            coreSize = 101L,
            coreHash = "new-sha"
        )

        assertEquals(101L, migrated.sizeBytes)
        assertEquals("new-sha", migrated.sha256)
        assertFalse(migrated.visionValidated)
    }
}

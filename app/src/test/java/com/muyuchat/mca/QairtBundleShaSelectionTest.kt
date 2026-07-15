package com.muyuchat.mca

import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QairtBundleShaSelectionTest {
    @Test
    fun directoryHashRefreshReplacesTheStaleQairtManifestHashBeforeLoad() {
        val beforeDirectoryChange = qairtManifest(sha256 = "OLD-BUNDLE-HASH")
        // This represents ModelStore.validateForLoad recalculating the directory
        // fingerprint after a component was replaced or corrupted/repaired.
        val refreshedAfterDirectoryChange = beforeDirectoryChange.copy(sha256 = "NEW-BUNDLE-HASH")

        assertEquals(
            "new-bundle-hash",
            currentQairtBundleSha256(
                requested = beforeDirectoryChange,
                persistedModels = listOf(refreshedAfterDirectoryChange)
            )
        )
    }

    @Test
    fun nonQairtModelNeverProducesAQAIRTAdmissionHash() {
        val llama = qairtManifest(sha256 = "not-used").copy(runtime = ChatModelRuntime.LLAMA_CPP)

        assertNull(currentQairtBundleSha256(llama, listOf(llama)))
    }

    private fun qairtManifest(sha256: String): ModelManifest = ModelManifest(
        id = "qairt-id",
        displayName = "Qwen3-VL-4B-Instruct",
        path = "/models/qwen3-vl",
        runtime = ChatModelRuntime.GENIEX_QAIRT,
        source = ModelSource.HUGGING_FACE,
        fileName = "qwen3-vl",
        sizeBytes = 1L,
        sha256 = sha256
    )
}

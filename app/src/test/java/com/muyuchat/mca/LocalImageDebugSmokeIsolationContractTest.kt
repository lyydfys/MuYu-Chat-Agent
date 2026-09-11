package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageDebugSmokeIsolationContractTest {
    @Test
    fun `store smoke restores the product image selection on every terminal path`() {
        val source = debugSource("LocalImageStoreSmokeActivity.kt")

        assertTrue(source.contains("previousSelectedModelId = store.loadSelectedModelId()"))
        assertTrue(source.contains("previousSelectedBackend = store.loadSelectedBackend()"))
        assertTrue(source.contains("store.saveSelectedModelId(previousSelectedModelId)"))
        assertTrue(source.contains("previousSelectedBackend?.let(store::saveSelectedBackend)"))
        assertTrue(!source.contains("store.saveSelectedModelId(passedModel.id)"))
        assertTrue(!source.contains("store.saveSelectedBackend(ImageBackend.LOCAL)"))
        assertTrue(
            source.indexOf("store.saveSelectedModelId(previousSelectedModelId)") >
                source.indexOf("} finally {")
        )
    }

    private fun debugSource(fileName: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(7) {
            val candidate = File(root, "src/debug/java/com/muyuchat/mca/debug/$fileName")
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate debug source $fileName")
    }
}

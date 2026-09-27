package com.muyuchat.mca

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class LocalImageCatalogRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun discoveryPrunesStagingAndBoundsNestedDirectories() {
        val root = temp.newFolder("models")
        val valid = File(root, "bundle/model.gguf").apply { parentFile?.mkdirs(); writeText("model") }
        File(root, ".bundle.installing/content/model.gguf").apply { parentFile?.mkdirs(); writeText("partial") }
        File(root, "a/b/c/d/e/f/model.gguf").apply { parentFile?.mkdirs(); writeText("deep") }
        val found = imageDiscoveryWalk(root).filter { it.isFile }.toList()
        assertEquals(listOf(valid), found)
    }

    @Test fun oneMalformedRowDoesNotDiscardOtherModels() {
        val row = JSONObject().put("id", "good").put("displayName", "Good")
            .put("path", "/models/model.gguf").put("fileName", "model.gguf")
            .put("runtime", "STABLE_DIFFUSION_CPP").put("family", "SD15")
            .put("sizeBytes", 3).put("sha256", "a".repeat(64)).put("imageSize", "512x512")
            .put("source", "local").put("updatedAt", 1)
        val rows = JSONArray().put(row).put("corrupted entry").put(row.put("id", "second"))
        assertEquals(2, parseImageCatalogRecords(rows.toString()).size)
    }

    @Test fun sameIdDifferentArtifactsKeepQwenAndBrokenRecord() {
        val qwen = File(temp.newFolder("qwen"), "dit.mnn").apply { writeText("qwen graph") }
        val broken = File(temp.newFolder("broken"), "missing.mnn")
        val records = listOf(
            imageRecord("shared", broken, updatedAt = 20),
            imageRecord("shared", qwen, updatedAt = 10)
        )

        val normalized = normalizeLocalImageModelRecords(records)

        assertEquals(2, normalized.size)
        assertEquals(2, normalized.map { it.id }.toSet().size)
        assertEquals(broken.absolutePath, normalized[0].path)
        assertEquals(qwen.absolutePath, normalized[1].path)
        assertEquals("shared", normalized[1].id)
        assertNotEquals("shared", normalized[0].id)
        assertTrue(normalized[1].configured)
        assertEquals(normalized, normalizeLocalImageModelRecords(normalized))
    }

    @Test fun sameIdAndPrimaryPathCollapseWithoutDiscardingLatestMetadata() {
        val primary = File(temp.newFolder("image"), "dit.mnn").apply { writeText("graph") }
        val original = imageRecord("same", primary, updatedAt = 1)
        val verified = original.copy(verificationMessage = "native load passed", updatedAt = 2)

        val normalized = normalizeLocalImageModelRecords(listOf(original, verified))

        assertEquals(1, normalized.size)
        assertEquals("native load passed", normalized.single().verificationMessage)
    }

    private fun imageRecord(id: String, file: File, updatedAt: Long) = LocalImageModelRecord(
        id = id,
        displayName = file.parentFile.name,
        path = file.absolutePath,
        fileName = file.name,
        sizeBytes = file.length(),
        sha256 = "a".repeat(64),
        runtime = LocalImageRuntime.MNN_DIFFUSION,
        family = LocalImageModelFamily.QWEN_IMAGE,
        bundleRoot = file.parentFile.absolutePath,
        updatedAt = updatedAt
    )
}

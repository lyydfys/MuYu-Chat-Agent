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
}

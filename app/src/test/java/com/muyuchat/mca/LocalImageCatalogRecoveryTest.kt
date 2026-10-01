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

    @Test fun installerAndScannerRowsCollapseWithDurableIdentityAndAliases() {
        val primary = File(temp.newFolder("import-scan"), "dit.mnn").apply { writeText("graph") }
        val installed = imageRecord("imported-id", primary, 1).copy(source = "local", createdAt = 1)
        val scanned = installed.copy(id = "bundle-scan-id", source = "local:discovered", createdAt = 2, updatedAt = 2)

        val repaired = normalizeLocalImageModelRecords(listOf(scanned, installed))

        assertEquals(1, repaired.size)
        assertEquals("imported-id", repaired.single().id)
        assertTrue(repaired.single().matchesCatalogId("bundle-scan-id"))
        assertFalse(repaired.single().matchesCatalogId(null))
        assertEquals("local", repaired.single().source)
        assertEquals(repaired, normalizeLocalImageModelRecords(repaired))
        assertEquals(repaired.single(), LocalImageModelRecord.fromJson(repaired.single().toJson()))
    }

    @Test fun currentlySelectedDiscoveryIdIsRetainedWithoutBreakingInstalledId() {
        val primary = File(temp.newFolder("selected-scan"), "dit.mnn").apply { writeText("graph") }
        val installed = imageRecord("installed-id", primary, 1)
        val scanned = installed.copy(id = "selected-scan-id", source = "local:discovered", updatedAt = 2)

        val owner = normalizeLocalImageModelRecords(listOf(installed, scanned), "selected-scan-id").single()

        assertEquals("selected-scan-id", owner.id)
        assertTrue(owner.matchesCatalogId("installed-id"))
        assertEquals(installed.source, owner.source)
    }

    @Test fun sameNameAndPrimaryHashAtDifferentPathsAreNotMerged() {
        val first = File(temp.newFolder("first-variant"), "dit.mnn").apply { writeText("same primary") }
        val second = File(temp.newFolder("second-variant"), "dit.mnn").apply { writeText("same primary") }
        val records = listOf(imageRecord("one", first, 1), imageRecord("two", second, 2))
            .map { it.copy(displayName = "same display name") }

        assertEquals(2, normalizeLocalImageModelRecords(records).size)
    }

    @Test fun samePrimaryWithDifferentRuntimeKeepsBothAndProtectsSharedFile() {
        val primary = File(temp.newFolder("shared-runtime"), "dit.mnn").apply { writeText("graph") }
        val first = imageRecord("mnn", primary, 1)
        val second = first.copy(id = "qnn", runtime = LocalImageRuntime.QNN_HTP)

        assertEquals(2, normalizeLocalImageModelRecords(listOf(first, second)).size)
        assertTrue(localImageRemovalSharesFiles(first, listOf(second)))
        assertFalse(localImageRemovalSharesFiles(first, emptyList()))
    }

    @Test fun deletingParentBundleDoesNotRemoveAnotherNestedModel() {
        val root = temp.newFolder("shared-bundle")
        val primary = File(root, "dit.mnn").apply { writeText("graph") }
        val nested = File(root, "child/unet.bin").apply { parentFile.mkdirs(); writeText("child") }
        val target = imageRecord("parent", primary, 1)
        val child = imageRecord("child", nested, 2)

        assertTrue(localImageRemovalSharesFiles(target, listOf(child)))
    }

    @Test fun oldVerificationForDifferentContentIsNotCarriedAcrossReplacement() {
        val primary = File(temp.newFolder("replaced"), "dit.mnn").apply { writeText("graph") }
        val verified = imageRecord("stable", primary, 1).copy(verifiedAt = 100, verificationMessage = "old native success")
        val replaced = verified.copy(sha256 = "b".repeat(64), verifiedAt = 0, verificationMessage = "", updatedAt = 2)

        val result = normalizeLocalImageModelRecords(listOf(verified, replaced)).single()

        assertEquals("b".repeat(64), result.sha256)
        assertEquals(0L, result.verifiedAt)
        assertEquals("", result.verificationMessage)
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

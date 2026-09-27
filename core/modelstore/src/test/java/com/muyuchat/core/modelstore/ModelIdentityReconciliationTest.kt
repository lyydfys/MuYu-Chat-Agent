package com.muyuchat.core.modelstore

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelIdentityReconciliationTest {
    @Test
    fun identicalComponentsKeepBothPhysicalCopiesAndLegacyIds() {
        val first = manifest("older", "copy-a.gguf", "a".repeat(64))
        val second = manifest("newer", "copy-b.gguf", "a".repeat(64)).copy(createdAt = 20L)

        val result = reconcileStableModelIdentities(listOf(first, second))

        assertEquals(1, result.models.size)
        assertEquals("newer", result.models.single().id)
        assertEquals("newer", result.idMapping["older"])
        assertEquals(setOf("older"), result.models.single().aliases.toSet())
        assertEquals(setOf(first.path, second.path), result.models.single().physicalCopies.map { it.path }.toSet())
    }

    @Test
    fun differentProjectorAndSourceRevisionDoNotForgeVisionValidation() {
        val main = "a".repeat(64)
        val first = manifest("one", "same.gguf", main).copy(
            visionProjectorPath = "one-mmproj.gguf", visionProjectorSha256 = "b".repeat(64),
            visionProjectorSizeBytes = 9L, visionValidated = true)
        val second = manifest("two", "same.gguf", main).copy(
            visionProjectorPath = "two-mmproj.gguf", visionProjectorSha256 = "c".repeat(64),
            visionProjectorSizeBytes = 9L, visionValidated = false)

        val result = reconcileStableModelIdentities(listOf(first, second))

        assertEquals(2, result.models.size)
        assertTrue(result.models.first { it.id == "one" }.visionValidated)
        assertFalse(result.models.first { it.id == "two" }.visionValidated)
        assertEquals(2, deduplicateModelRecordsByCanonicalPath(listOf(first, second)).size)
    }

    @Test
    fun damagedAndProvisionalRowsStayVisibleUntilRealHashKnown() {
        val damaged = manifest("damaged", "missing.gguf", "").copy(
            integrityState = ModelIntegrityState.DAMAGED, diagnostic = "missing file")
        val provisional = manifest("provisional", "other.gguf", "6d63612d72656331" + "a".repeat(48))

        val result = reconcileStableModelIdentities(listOf(damaged, provisional))

        assertEquals(2, result.models.size)
        assertEquals("missing file", result.models.first { it.id == "damaged" }.diagnostic)
        assertEquals(null, provisional.componentIdentity)
    }

    @Test
    fun corruptPersistedRecordIsRetainedWithDiagnostic() {
        val rows = JSONArray().put(JSONObject().put("id", "good").put("path", "/tmp/good.gguf")
            .put("fileName", "good.gguf")).put("invalid-row")

        val parsed = parsePersistedModelManifest(rows.toString())

        assertEquals(2, parsed.size)
        assertEquals(ModelIntegrityState.DAMAGED, parsed.last().integrityState)
        assertTrue(parsed.last().diagnostic.orEmpty().contains("index=1"))
    }

    @Test
    fun identitySerializationRetainsAliasesAndCopies() {
        val model = manifest("new", "copy.gguf", "a".repeat(64)).copy(
            aliases = listOf("old"), physicalCopies = listOf(ModelPhysicalCopy("copy.gguf", 42, "a".repeat(64))))

        val restored = ModelManifest.fromJson(model.toJson())

        assertEquals(model.aliases, restored.aliases)
        assertEquals(model.physicalCopies, restored.physicalCopies)
    }

    private fun manifest(id: String, name: String, digest: String): ModelManifest {
        val root = Files.createTempDirectory("model-identity").toFile()
        val path = File(root, name).absolutePath
        return ModelManifest(id = id, displayName = name, path = path,
            source = ModelSource.LOCAL, fileName = name, sizeBytes = 42L,
            sha256 = digest, createdAt = 10L)
    }
}

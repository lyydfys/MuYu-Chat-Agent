package com.muyuchat.core.modelstore

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class ManagedGgufRecoveryTest {
    @Test
    fun nestedGgufIsRecoveredWhileRegisteredTopLevelModelIsNotDuplicated() {
        val root = Files.createTempDirectory("managed-gguf-recovery").toFile()
        val registered = writeGguf(File(root, "registered.gguf"))
        val nested = writeGguf(
            File(File(root, "qwen35_4b_geniex_q4").apply { mkdirs() }, "Qwen3.5-4B-Q4_0.gguf")
        )
        val existing = manifest(path = registered)

        val recovered = findRecoverableManagedGgufFiles(root, listOf(existing))

        assertEquals(listOf(nested.canonicalPath), recovered.map { it.file.canonicalPath })
        assertEquals("qwen3", recovered.single().metadata.architecture)
        assertEquals("Q4_0", recovered.single().metadata.quant)
    }

    @Test
    fun recoveryIgnoresProjectorsStagingArtifactsInvalidFilesAndNonPrimaryShards() {
        val root = Files.createTempDirectory("managed-gguf-filter").toFile()
        val projector = writeGguf(File(root, "mmproj-model-f16.gguf"), architecture = "clip")
        File(root, "broken.gguf").writeText("not a GGUF")
        val staging = File(root, ".importing-00000000-0000-0000-0000-000000000001").apply { mkdirs() }
        writeGguf(File(staging, "unfinished.gguf"))
        val firstShard = writeGguf(File(root, "Qwen3.5-00001-of-00002.gguf"))
        writeGguf(File(root, "Qwen3.5-00002-of-00002.gguf"))
        val boundProjector = manifest(path = File(root, "missing-main.gguf")).copy(
            visionProjectorPath = projector.absolutePath
        )

        val recovered = findRecoverableManagedGgufFiles(root, listOf(boundProjector))

        assertEquals(listOf(firstShard.canonicalPath), recovered.map { it.file.canonicalPath })
    }

    @Test
    fun oneMalformedManifestEntryDoesNotEmptyTheValidCatalog() {
        val valid = manifest(File("model.gguf"))
        val persisted = JSONArray()
            .put(valid.toJson())
            .put("not an object")
            .put(JSONObject())

        val parsed = parsePersistedModelManifest(persisted.toString())

        assertEquals(listOf(valid), parsed)
        assertTrue(parsed.single().id.isNotBlank())
    }

    @Test
    fun passiveRecoveryFingerprintHashesMetadataButDoesNotReadWeightContents() {
        val root = Files.createTempDirectory("managed-fast-fingerprint").toFile()
        val bundle = File(root, "qwen35_4b_mnn_bundle").apply { mkdirs() }
        val weight = File(bundle, "llm.mnn.weight").apply { writeText("AAAA") }
        val config = File(bundle, "config.json").apply { writeText("{\"v\":1}") }
        val stableTime = System.currentTimeMillis() - 60_000L
        assertTrue(weight.setLastModified(stableTime))
        assertTrue(config.setLastModified(stableTime))
        val weightTime = weight.lastModified()
        val configTime = config.lastModified()

        val initial = fastManagedRecoveryFingerprint(root, listOf(weight, config))
        weight.writeText("BBBB")
        assertTrue(weight.setLastModified(weightTime))
        val weightContentChanged = fastManagedRecoveryFingerprint(root, listOf(weight, config))
        config.writeText("{\"v\":2}")
        assertTrue(config.setLastModified(configTime))
        val metadataContentChanged = fastManagedRecoveryFingerprint(root, listOf(weight, config))

        assertTrue(isFastRecoveryFingerprint(initial))
        assertEquals(64, initial.length)
        assertEquals(initial, weightContentChanged)
        assertTrue(metadataContentChanged != weightContentChanged)
    }

    private fun manifest(path: File): ModelManifest = ModelManifest(
        id = "model-${path.name}",
        displayName = path.nameWithoutExtension,
        path = path.absolutePath,
        runtime = ChatModelRuntime.LLAMA_CPP,
        source = ModelSource.LOCAL,
        fileName = path.name,
        sizeBytes = path.length(),
        sha256 = "0".repeat(64),
        createdAt = 123L
    )

    private fun writeGguf(
        file: File,
        architecture: String = "qwen3",
        fileType: Int = 2
    ): File = file.apply {
        parentFile?.mkdirs()
        writeBytes(ByteArrayOutputStream().apply {
            write("GGUF".toByteArray(Charsets.US_ASCII))
            writeU32(3)
            writeU64(0)
            writeU64(2)
            writeString("general.architecture")
            writeU32(8)
            writeString(architecture)
            writeString("general.file_type")
            writeU32(4)
            writeU32(fileType)
        }.toByteArray())
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeU64(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        repeat(4) { shift -> write((value shr (shift * 8)) and 0xff) }
    }

    private fun ByteArrayOutputStream.writeU64(value: Long) {
        repeat(8) { shift -> write(((value shr (shift * 8)) and 0xff).toInt()) }
    }
}

package com.muyuchat.mca

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.muyuchat.core.download.RemoteModelFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real LocalImageModelStore / filesystem / preference fixture; no helper-only catalog simulation. */
class LocalImageModelStoreIntegrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun repeatedSingleImportAndDownloadKeepOneOwnerAndAllCompleteFiles() {
        val fixture = fixture()
        val payload = "complete weights".toByteArray()
        val imported = fixture.store.importFromSource("fixture.safetensors", { ByteArrayInputStream(payload) })
        val repeated = fixture.store.importFromSource("fixture.safetensors", { ByteArrayInputStream(payload) })
        val downloaded = File(fixture.external("image_models"), "remote/fixture.safetensors")
            .apply { parentFile!!.mkdirs(); writeBytes(payload) }
        val registered = fixture.store.registerDownloadedModel(downloaded, remote(downloaded.name))

        assertEquals(imported.id, repeated.id)
        assertEquals(imported.id, registered.id)
        val owner = fixture.store.loadModels().single()
        assertEquals(3, owner.allPhysicalCopies().size)
        assertTrue(owner.allPhysicalCopies().all { File(it.path).isFile })
        assertEquals(owner.id, fixture.store.loadSelectedModelId())
        assertEquals(owner, fixture.store.loadModels().single())
        assertEquals(owner, LocalImageModelRecord.fromJson(owner.toJson()))
    }

    @Test fun repeatedZipImportDownloadAndRescanShareDurableOwnerWithoutDeletingCompanions() {
        val fixture = fixture()
        val entries = bundleEntries()
        val archive = zip(entries)
        val first = fixture.store.importFromSource("fixture.zip", { ByteArrayInputStream(archive) })
        val second = fixture.store.importFromSource("fixture.zip", { ByteArrayInputStream(archive) })
        val downloadedRoot = File(fixture.external("image_models"), "downloaded").apply { mkdirs() }
        writeEntries(downloadedRoot, entries)
        val downloaded = fixture.store.registerDownloadedBundle("downloaded", downloadedRoot,
            File(downloadedRoot, "model.safetensors"), remote("model.safetensors"), entries.size)

        assertEquals(first.id, second.id)
        assertEquals(first.id, downloaded.id)
        val owner = fixture.store.loadModels().single()
        assertEquals(3, owner.allPhysicalCopies().size)
        owner.allPhysicalCopies().forEach { physical ->
            assertTrue(File(physical.bundleRoot!!, "vae.safetensors").isFile)
            assertTrue(File(physical.bundleRoot, "tokenizer.json").isFile)
        }
        repeat(3) { assertEquals(owner, fixture.store.loadModels().single()) }
    }

    @Test fun equalPrimaryWithDifferentVaeOrTokenizerRemainsDistinct() {
        val fixture = fixture()
        listOf(bundleEntries(), bundleEntries(vae = "different VAE"), bundleEntries(tokenizer = "different tokenizer"))
            .forEach { entries -> fixture.store.importFromSource("variant.zip", { ByteArrayInputStream(zip(entries)) }) }

        assertEquals(3, fixture.store.loadModels().size)
        assertEquals(1, fixture.store.loadModels().map { it.sha256 }.distinct().size)
        assertEquals(3, fixture.store.loadModels().map { it.contentFingerprint }.distinct().size)
    }

    @Test fun manifestOwnedEncoderDirectoryDoesNotBecomeAnotherModel() {
        val fixture = fixture()
        val root = File(fixture.external("image_models"), "owned").apply { mkdirs() }
        writeEntries(root, bundleEntries(encoderDirectory = "encoder"))

        val owner = fixture.store.loadModels().single()
        assertEquals(File(root, "model.safetensors").canonicalPath, File(owner.path).canonicalPath)
        assertTrue(owner.componentSnapshots.any { it.relativePath == "encoder/clip.safetensors" })
        assertEquals(owner, fixture.store.loadModels().single())
    }

    @Test fun directlyDeclaredNestedIndependentBundleRemainsVisible() {
        val fixture = fixture()
        val root = File(fixture.external("image_models"), "parent").apply { mkdirs() }
        writeEntries(root, bundleEntries())
        val child = File(root, "child").apply { mkdirs() }
        writeEntries(child, bundleEntries(vae = "child VAE"))

        val rows = fixture.store.loadModels()
        assertEquals(2, rows.size)
        assertTrue(rows.any { File(it.bundleRoot!!).canonicalPath == root.canonicalPath })
        assertTrue(rows.any { File(it.bundleRoot!!).canonicalPath == child.canonicalPath })
        assertFalse(rows.first { File(it.bundleRoot!!).canonicalPath == root.canonicalPath }
            .componentSnapshots.any { it.relativePath.startsWith("child/") })
    }

    @Test fun sameLengthCompanionChangeInvalidatesNativeProofEvenWhenMtimeIsRestored() {
        val fixture = fixture()
        val entries = bundleEntries(vae = "vae A")
        val original = fixture.store.importFromSource("fixture.zip", { ByteArrayInputStream(zip(entries)) })
        val verified = original.copy(verificationStatus = LocalImageVerificationStatus.PASSED,
            verificationMessage = "native executed", verifiedAt = 1_000L, qnnVerificationStamp = "stamp")
        fixture.store.updateModel(verified)
        val before = fixture.store.loadModels().single()
        assertEquals(LocalImageVerificationStatus.PASSED, before.verificationStatus)
        assertEquals(before.contentFingerprint, before.verifiedContentFingerprint)
        val companion = File(before.bundleRoot!!, "vae.safetensors")
        val modified = companion.lastModified()
        companion.writeText("vae B")
        companion.setLastModified(modified)

        val changed = fixture.store.loadModels().single()
        assertNotEquals(before.contentFingerprint, changed.contentFingerprint)
        assertEquals(LocalImageVerificationStatus.UNKNOWN, changed.verificationStatus)
        assertEquals(0L, changed.verifiedAt)
        assertEquals("", changed.verifiedContentFingerprint)
        assertEquals("", changed.qnnVerificationStamp)
        assertEquals("", changed.verificationMessage)
    }

    @Test fun mutatedRetainedCopySplitsFromOwnerAndUnchangedCopyKeepsOriginalContent() {
        val fixture = fixture()
        val archive = zip(bundleEntries(vae = "vae A"))
        fixture.store.importFromSource("fixture.zip", { ByteArrayInputStream(archive) })
        fixture.store.importFromSource("fixture.zip", { ByteArrayInputStream(archive) })
        val owner = fixture.store.loadModels().single()
        val duplicate = owner.physicalCopies.single()
        File(duplicate.bundleRoot!!, "vae.safetensors").writeText("vae B")

        val rows = fixture.store.loadModels()
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.id == owner.id && it.contentFingerprint == owner.contentFingerprint })
        assertEquals(2, rows.map { it.contentFingerprint }.distinct().size)
    }

    @Test fun missingManifestPrimaryKeepsItsRowAndNeverAdoptsVaeAsAnotherModel() {
        val fixture = fixture()
        val entries = bundleEntries()
        val imported = fixture.store.importFromSource("fixture.zip", { ByteArrayInputStream(zip(entries)) })
        fixture.store.updateModel(imported.copy(verificationStatus = LocalImageVerificationStatus.PASSED,
            verificationMessage = "native", verifiedAt = 1_000L))
        assertTrue(File(imported.path).delete())

        val missing = fixture.store.loadModels().single()
        assertEquals(imported.id, missing.id)
        assertEquals(imported.path, missing.path)
        assertFalse(missing.configured)
        assertEquals(LocalImageVerificationStatus.UNKNOWN, missing.verificationStatus)
        assertNotNull(missing.localImageStructuralReadinessMessage())
        assertTrue(File(imported.bundleRoot!!, "vae.safetensors").isFile)

        File(imported.path).writeBytes(entries.getValue("model.safetensors"))
        val repaired = fixture.store.loadModels().single()
        assertEquals(imported.id, repaired.id)
        assertTrue(repaired.configured)
        assertEquals(LocalImageVerificationStatus.UNKNOWN, repaired.verificationStatus)
        assertEquals(0L, repaired.verifiedAt)
    }

    @Test fun missingActivePrimaryUsesSurvivingCompleteCopyWithoutTransferringNativeProof() {
        val fixture = fixture()
        val archive = zip(bundleEntries())
        val first = fixture.store.importFromSource("fixture.zip", { ByteArrayInputStream(archive) })
        fixture.store.importFromSource("fixture.zip", { ByteArrayInputStream(archive) })
        fixture.store.updateModel(first.copy(verificationStatus = LocalImageVerificationStatus.PASSED,
            verificationMessage = "native", verifiedAt = 1_000L))
        assertTrue(File(first.path).delete())

        val owner = fixture.store.loadModels().single()
        assertEquals(first.id, owner.id)
        assertTrue(File(owner.path).isFile)
        assertEquals(LocalImageVerificationStatus.UNKNOWN, owner.verificationStatus)
        assertEquals(0L, owner.verifiedAt)
    }

    @Test fun cancelDuringSingleAndZipImportPublishesNothingAndRetrySucceeds() {
        for (isZip in listOf(false, true)) {
            val fixture = fixture()
            val payload = if (isZip) zip(bundleEntries(model = "x".repeat(400_000))) else ByteArray(400_000) { 7 }
            var checks = 0
            val error = runCatching { fixture.store.importFromSource(if (isZip) "cancel.zip" else "cancel.safetensors",
                { ByteArrayInputStream(payload) }, { if (++checks == 5) throw CancellationException("cancel fixture") }) }
                .exceptionOrNull()
            assertTrue(error is CancellationException)
            assertTrue(fixture.store.loadModels().isEmpty())
            assertTrue(fixture.external("image_models").listFiles().orEmpty().isEmpty())
            val retried = fixture.store.importFromSource(if (isZip) "cancel.zip" else "cancel.safetensors",
                { ByteArrayInputStream(payload) })
            assertTrue(File(retried.path).isFile)
            assertEquals(1, fixture.store.loadModels().size)
        }
    }

    @Test fun interruptedDownloadPublicationRecoversFromManifestAndPreservesHiddenBackup() {
        val fixture = fixture()
        val managed = fixture.external("image_models")
        val candidate = File(managed, ".bundle-interrupted.candidate").apply { mkdirs() }
        val backup = File(managed, ".bundle-interrupted.backup-previous").apply { mkdirs() }
        writeEntries(candidate, bundleEntries())
        writeEntries(backup, bundleEntries(vae = "previous VAE"))

        val recovered = fixture.store.loadModels().single()
        assertEquals(File(managed, "bundle-interrupted/model.safetensors").canonicalPath,
            File(recovered.path).canonicalPath)
        assertFalse(candidate.exists())
        assertTrue(backup.isDirectory)
        assertTrue(File(backup, "vae.safetensors").isFile)
        assertEquals(recovered, fixture.store.loadModels().single())
        assertEquals(LocalImageVerificationStatus.UNKNOWN, recovered.verificationStatus)
    }

    @Test fun incompleteDownloadCandidateAndBackupRemainHiddenUntilRequiredFileIsRestored() {
        val fixture = fixture()
        val managed = fixture.external("image_models")
        val candidate = File(managed, ".bundle-interrupted.candidate").apply { mkdirs() }
        val backup = File(managed, ".bundle-interrupted.backup-previous").apply { mkdirs() }
        val entries = bundleEntries()
        writeEntries(candidate, entries - "vae.safetensors")
        writeEntries(backup, bundleEntries(vae = "previous VAE"))

        assertTrue(fixture.store.loadModels().isEmpty())
        assertTrue(candidate.isDirectory)
        assertTrue(backup.isDirectory)
        File(candidate, "vae.safetensors").writeBytes(entries.getValue("vae.safetensors"))
        assertEquals(1, fixture.store.loadModels().size)
        assertFalse(candidate.exists())
        assertTrue(backup.isDirectory)
    }

    @Test fun interruptedAfterPromotionBeforeRegistrationRecoversOnlyPublicBundle() {
        val fixture = fixture()
        val managed = fixture.external("image_models")
        val published = File(managed, "bundle-published").apply { mkdirs() }
        val backup = File(managed, ".bundle-published.backup-previous").apply { mkdirs() }
        writeEntries(published, bundleEntries())
        writeEntries(backup, bundleEntries(vae = "previous VAE"))

        val recovered = fixture.store.loadModels().single()
        assertEquals(File(published, "model.safetensors").canonicalPath, File(recovered.path).canonicalPath)
        assertEquals(LocalImageVerificationStatus.UNKNOWN, recovered.verificationStatus)
        assertEquals(recovered, fixture.store.loadModels().single())
        assertTrue(backup.isDirectory)
    }

    @Test fun importReadFailureAndHiddenProcessResidueAreNeverDiscovered() {
        val fixture = fixture()
        val failed = runCatching { fixture.store.importFromSource("failure.safetensors", {
            object : java.io.InputStream() { override fun read(): Int = throw IOException("fixture read failure") }
        }) }
        assertTrue(failed.exceptionOrNull() is IOException)
        assertTrue(fixture.store.loadModels().isEmpty())
        val residue = File(fixture.external("image_models"), ".image-import-dead/content").apply { mkdirs() }
        writeEntries(residue, bundleEntries())
        assertTrue(fixture.store.loadModels().isEmpty())
    }

    @Test fun registrationCommitsRecordSelectionBackendAndDiagnosticTogether() {
        val fixture = fixture()
        val root = File(fixture.external("image_models"), "downloaded").apply { mkdirs() }
        writeEntries(root, bundleEntries())
        val before = fixture.preferences.commits
        val record = fixture.store.registerDownloadedBundle("fixture", root, File(root, "model.safetensors"),
            remote("model.safetensors"), 4, recordTransform = { it.copy(verificationMessage = "runtime advisory") })

        assertEquals(before + 1, fixture.preferences.commits)
        assertEquals(record.id, fixture.store.loadSelectedModelId())
        assertEquals(ImageBackend.LOCAL, fixture.store.loadSelectedBackend())
        assertEquals("runtime advisory", fixture.store.loadModels(discover = false).single().verificationMessage)
        assertEquals(LocalImageVerificationStatus.UNKNOWN, record.verificationStatus)
    }

    @Test fun transactionRestoresPriorCatalogAndSelectionOnFailureAfterRegistration() {
        val fixture = fixture()
        val original = fixture.store.importFromSource("original.safetensors", { ByteArrayInputStream("old".toByteArray()) })
        val snapshot = fixture.store.captureCatalogSnapshot()
        val root = File(fixture.external("image_models"), "new").apply { mkdirs() }
        writeEntries(root, bundleEntries())
        val failure = runCatching {
            fixture.store.withCatalogTransaction {
                fixture.store.registerDownloadedBundle("new", root, File(root, "model.safetensors"), remote("model.safetensors"), 4)
                throw IOException("post registration fixture failure")
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(snapshot, fixture.store.captureCatalogSnapshot())
        assertEquals(original.id, fixture.store.loadModels(discover = false).single().id)
    }

    @Test fun preferenceCommitFailureRollsBackPublishedImportAndAllowsRetry() {
        val fixture = fixture()
        fixture.preferences.failNextCommit = true
        val failure = runCatching { fixture.store.importFromSource("fixture.safetensors",
            { ByteArrayInputStream("weights".toByteArray()) }) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(fixture.store.loadModels().isEmpty())
        assertTrue(fixture.external("image_models").listFiles().orEmpty().isEmpty())
        assertEquals(1, fixture.store.importFromSource("fixture.safetensors",
            { ByteArrayInputStream("weights".toByteArray()) }).allPhysicalCopies().size)
    }

    @Test fun removingAliasSuppressesAllCopiesAndDeletingOwnerPreservesOtherRuntime() {
        val fixture = fixture()
        val archive = zip(bundleEntries())
        fixture.store.importFromSource("fixture.zip", { ByteArrayInputStream(archive) })
        fixture.store.importFromSource("fixture.zip", { ByteArrayInputStream(archive) })
        val owner = fixture.store.loadModels().single()
        assertTrue(owner.aliases.isNotEmpty())
        assertTrue(fixture.store.removeModelRecord(owner.aliases.first()))
        assertTrue(fixture.store.loadModels().isEmpty())
        assertTrue(owner.allPhysicalCopies().all { File(it.path).isFile })

        val shared = fixture()
        val image = shared.store.importFromSource("shared.safetensors", { ByteArrayInputStream("weights".toByteArray()) })
        val foreign = image.copy(id = "foreign", runtime = LocalImageRuntime.MNN_DIFFUSION, contentFingerprint = "",
            componentSnapshots = emptyList(), physicalCopies = emptyList())
        shared.store.saveModels(listOf(image, foreign))
        assertTrue(shared.store.deleteModel(image.id))
        assertTrue(File(image.path).isFile)
        assertEquals("foreign", shared.store.loadModels().single().id)
    }

    @Test fun deletingContentOwnerDeletesEveryRetainedPhysicalCopy() {
        val fixture = fixture()
        val archive = zip(bundleEntries())
        fixture.store.importFromSource("fixture.zip", { ByteArrayInputStream(archive) })
        fixture.store.importFromSource("fixture.zip", { ByteArrayInputStream(archive) })
        val owner = fixture.store.loadModels().single()

        assertTrue(fixture.store.deleteModel(owner.id))
        assertTrue(owner.allPhysicalCopies().all { !File(it.bundleRoot!!).exists() })
        assertTrue(fixture.store.loadModels().isEmpty())
    }

    @Test fun chatPackagesInSharedModelsRootAreNotDiscoveredAsImageModels() {
        val fixture = fixture()
        val shared = fixture.external("models")
        File(shared, "single-chat/gemma-4-E4B-it-Q4_K_M.gguf").apply {
            parentFile!!.mkdirs()
            writeBytes("chat weights".toByteArray())
        }
        File(shared, "mnn-chat/llm.mnn").apply {
            parentFile!!.mkdirs()
            writeBytes("llm graph".toByteArray())
        }
        File(shared, "mnn-chat/visual.mnn").writeBytes("vision graph".toByteArray())
        File(shared, "mnn-chat/llm.mnn.weight").writeBytes("llm weights".toByteArray())
        File(shared, "mnn-chat/llm_config.json").writeText("{\"model_type\":\"qwen3\"}")
        File(fixture.filesModels(), "private-chat/gemma.gguf").apply {
            parentFile!!.mkdirs()
            writeBytes("private chat weights".toByteArray())
        }
        File(shared, "sdxl-chat/gemma.gguf").apply {
            parentFile!!.mkdirs()
            writeBytes("family-name chat weights".toByteArray())
        }
        File(fixture.filesModels(), "vae-llm-chat/gemma.gguf").apply {
            parentFile!!.mkdirs()
            writeBytes("path-token chat weights".toByteArray())
        }
        File(fixture.filesModels(), "foo-vae").apply { mkdirs() }.let { root ->
            File(root, "gemma.gguf").writeBytes("vae-token chat weights".toByteArray())
            File(root, "vae-notes.txt").writeText("not a VAE")
            File(root, "llm_config.json").writeText("{\"model_type\":\"gemma\"}")
        }

        assertTrue(fixture.store.loadModels().isEmpty())
        assertTrue(fixture.store.loadModels(discover = false).isEmpty())
    }

    @Test fun persistedChatInferenceRowsAreTombstonedWithoutDeletingFiles() {
        val fixture = fixture()
        val primary = File(fixture.filesModels(), "single-chat/gemma.gguf").apply {
            parentFile!!.mkdirs()
            writeBytes("chat weights".toByteArray())
        }
        val legacy = LocalImageModelRecord(
            id = "legacy-chat-image", displayName = "Gemma chat",
            path = primary.absolutePath, fileName = primary.name, sizeBytes = primary.length(),
            sha256 = "a".repeat(64), runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            family = LocalImageModelFamily.CUSTOM, source = "local:discovered",
            recommendationId = "qwen_image_21_mnn_opencl",
            bundleRoot = primary.parentFile!!.absolutePath
        )
        assertTrue(fixture.preferences.instance.edit()
            .putString(KEY_MODELS, JSONArray().put(legacy.toJson()).toString())
            .putString(KEY_SELECTED_MODEL_ID, legacy.id).commit())

        assertTrue(fixture.store.loadModels(discover = false).isEmpty())

        assertTrue(primary.isFile)
        assertTrue(parseImageCatalogRecords(fixture.preferences.instance.getString(KEY_MODELS, "").orEmpty()).isEmpty())
        assertEquals("", fixture.preferences.instance.getString(KEY_SELECTED_MODEL_ID, "").orEmpty())
        assertTrue(legacy.catalogPhysicalKeys().all {
            it in fixture.preferences.instance.getStringSet(KEY_SUPPRESSED_PATHS, emptySet()).orEmpty()
        })
        assertTrue(fixture.preferences.instance.getString("catalog_recovery_raw", "").orEmpty().contains(legacy.id))
        val commitsAfterMigration = fixture.preferences.commits
        assertTrue(fixture.store.loadModels(discover = false).isEmpty())
        assertEquals(commitsAfterMigration, fixture.preferences.commits)
    }

    @Test fun validLegacyCompanionShapesInSharedModelsRootRemainDiscoverable() {
        val fixture = fixture()
        val sdRoot = File(fixture.external("models"), "generic-sdcpp").apply { mkdirs() }
        File(sdRoot, "model.safetensors").writeBytes("diffusion".toByteArray())
        File(sdRoot, "vae.safetensors").writeBytes("vae".toByteArray())
        File(sdRoot, "text_encoder.safetensors").writeBytes("encoder".toByteArray())
        File(sdRoot, "tokenizer.json").writeText("{}")
        val mnnRoot = File(fixture.filesModels(), "generic-mnn").apply { mkdirs() }
        File(mnnRoot, "model.mnn").writeBytes("diffusion".toByteArray())
        File(mnnRoot, "vae_decoder.mnn").writeBytes("vae".toByteArray())
        File(mnnRoot, "text_encoder.mnn").writeBytes("encoder".toByteArray())
        File(mnnRoot, "tokenizer.mtok").writeBytes("tokenizer".toByteArray())

        val models = fixture.store.loadModels()

        assertEquals(2, models.size)
        assertTrue(models.any { File(it.path).name == "model.safetensors" })
        assertTrue(models.any { File(it.path).name == "model.mnn" })
    }

    @Test fun tombstoneMatrixRetainsUnknownAndMixedCopies() {
        val fixture = fixture()
        val chatRoot = File(fixture.external("models"), "chat-a").apply { mkdirs() }
        val chatA = File(chatRoot, "gemma.gguf").apply { writeBytes("chat-a".toByteArray()) }
        val chatBRoot = File(fixture.filesModels(), "chat-b").apply { mkdirs() }
        val chatB = File(chatBRoot, "llama.gguf").apply { writeBytes("chat-b".toByteArray()) }
        fun record(id: String, file: File) = LocalImageModelRecord(
            id = id, displayName = file.name, path = file.absolutePath, fileName = file.name,
            sizeBytes = file.length(), sha256 = "a".repeat(64), runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            family = LocalImageModelFamily.CUSTOM, source = "local:discovered", bundleRoot = file.parentFile!!.absolutePath
        )
        val first = record("first", chatA)
        val second = record("second", chatB)
        assertTrue(first.copy(physicalCopies = listOf(second.asPhysicalCopy())).hasNegativeLocalImageEvidence())
        assertTrue(first.copy(recommendationId = "retired-recommendation").hasNegativeLocalImageEvidence())
        assertFalse(first.copy(physicalCopies = listOf(second.copy(path = "$chatBRoot/missing.gguf").asPhysicalCopy()))
            .hasNegativeLocalImageEvidence())
        val unknownRoot = File(fixture.filesModels(), "unknown-package").apply { mkdirs() }
        val unknown = File(unknownRoot, "model.gguf").apply { writeBytes("unknown".toByteArray()) }
        assertFalse(record("unknown", unknown).hasNegativeLocalImageEvidence())

        val imageRoot = File(fixture.external("image_models"), "valid-image").apply { mkdirs() }
        val imageFile = File(imageRoot, "unet.bin").apply { writeBytes("diffusion".toByteArray()) }
        val image = record("image", imageFile).copy(runtime = LocalImageRuntime.QNN_HTP)
        assertFalse(first.copy(physicalCopies = listOf(image.asPhysicalCopy())).hasNegativeLocalImageEvidence())
    }

    @Test fun explicitImageModelsDirectoryAcceptsBareModelWithoutManifest() {
        val fixture = fixture()
        val root = File(fixture.external("image_models"), "user-import").apply { mkdirs() }
        val modelFile = File(root, "custom-model.gguf").apply { writeBytes("image weights".toByteArray()) }

        val model = fixture.store.loadModels().single()

        assertEquals(modelFile.canonicalPath, File(model.path).canonicalPath)
        assertEquals(LocalImageRuntime.STABLE_DIFFUSION_CPP, model.runtime)
    }

    @Test fun validLegacyImageShapeInSharedModelsRootRemainsDiscoverable() {
        val fixture = fixture()
        val root = File(fixture.external("models"), "sdxl-legacy-qnn").apply { mkdirs() }
        File(root, "unet.bin").writeBytes("diffusion".toByteArray())
        File(root, "vae.bin").writeBytes("vae".toByteArray())
        File(root, "text_encoder.bin").writeBytes("encoder".toByteArray())
        File(root, "clip.mnn").writeBytes("clip graph".toByteArray())

        val model = fixture.store.loadModels().single()

        assertEquals(File(root, "unet.bin").canonicalPath, File(model.path).canonicalPath)
        assertEquals(LocalImageRuntime.QNN_HTP, model.runtime)
    }

    @Test fun validImageManifestInSharedModelsRootRemainsDiscoverable() {
        val fixture = fixture()
        val root = File(fixture.external("models"), "image-bundle").apply { mkdirs() }
        val entries = bundleEntries().toMutableMap()
        val manifest = requireNotNull(entries.remove("manifest.json"))
        entries["MANIFEST.JSON"] = manifest
        writeEntries(root, entries)

        val model = fixture.store.loadModels().single()

        assertEquals(File(root, "model.safetensors").canonicalPath, File(model.path).canonicalPath)
        assertEquals(LocalImageRuntime.STABLE_DIFFUSION_CPP, model.runtime)
    }

    private fun bundleEntries(
        model: String = "same model", vae: String = "same VAE", tokenizer: String = "same tokenizer", encoderDirectory: String? = null
    ): Map<String, ByteArray> {
        val components = JSONArray().put(JSONObject().put("role", "DIFFUSION").put("path", "model.safetensors"))
            .put(JSONObject().put("role", "VAE").put("path", "vae.safetensors"))
            .put(JSONObject().put("role", "TOKENIZER").put("path", "tokenizer.json"))
        if (encoderDirectory != null) components.put(JSONObject().put("role", "TEXT_ENCODER")
            .put("path", encoderDirectory + "/clip.safetensors"))
        val manifest = JSONObject().put("schema", "mca.image_engine.bundle.v1")
            .put("runtime", "STABLE_DIFFUSION_CPP").put("family", "SD15").put("components", components)
        return linkedMapOf("manifest.json" to manifest.toString().toByteArray(), "model.safetensors" to model.toByteArray(),
            "vae.safetensors" to vae.toByteArray(), "tokenizer.json" to tokenizer.toByteArray()).apply {
            if (encoderDirectory != null) put(encoderDirectory + "/clip.safetensors", "same encoder".toByteArray())
        }
    }

    private fun remote(name: String) = RemoteModelFile("fixture/repository", "main", name, name,
        downloadUrl = "https://example.invalid/fixture")

    private fun writeEntries(root: File, entries: Map<String, ByteArray>) = entries.forEach { (path, bytes) ->
        File(root, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { archive -> entries.forEach { (name, bytes) ->
            archive.putNextEntry(ZipEntry(name)); archive.write(bytes); archive.closeEntry()
        } }
        output.toByteArray()
    }

    private fun fixture(): Fixture = Fixture(temp.newFolder())

    private class Fixture(val root: File) {
        val preferences = MemoryPreferences()
        fun external(type: String) = File(root, "external/" + type).apply { mkdirs() }
        fun filesModels() = File(root, "files/models").apply { mkdirs() }
        private val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?): File = external(type ?: "default")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences.instance
        }
        val store = LocalImageModelStore(context)
    }

    private class MemoryPreferences {
        private val values = linkedMapOf<String, Any?>()
        var commits = 0
        var failNextCommit = false
        private val removed = Any()
        val instance: SharedPreferences = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args -> when {
            method.name == "edit" -> editor()
            method.name == "contains" -> values.containsKey(args!![0])
            method.name == "getAll" -> values.toMap()
            method.name.startsWith("get") -> values[args!![0] as String] ?: args[1]
            method.name == "toString" -> "MemoryPreferences"
            else -> null
        } } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = linkedMapOf<String, Any?>()
            var clear = false
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when {
                    method.name.startsWith("put") -> { pending[args!![0] as String] = args[1]; proxy }
                    method.name == "remove" -> { pending[args!![0] as String] = removed; proxy }
                    method.name == "clear" -> { clear = true; proxy }
                    method.name == "commit" || method.name == "apply" -> {
                        if (clear) values.clear()
                        pending.forEach { (key, value) -> if (value == null || value === removed) values.remove(key) else values[key] = value }
                        if (method.name == "commit") { commits++; val ok = !failNextCommit; failNextCommit = false; ok } else null
                    }
                    else -> null
                }
            } as SharedPreferences.Editor
        }
    }
}

package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineBundleComponentRole
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.RemoteModelFile
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DownloadedImageManifestFingerprintTest {
    @Test
    fun `runtime contract is bound before manifest publication and survives reload`() {
        withBundle { root, record, manifest ->
            assertNotNull(manifest.optJSONObject("requiredRuntimeProfile"))
            val profile = manifest.getJSONObject("executionProfile")
            val declared = profile.getString("modelFingerprint")
            assertEquals(declared, captureLocalImageContentSnapshot(record, force = true,
                manifestOverride = manifest).fingerprint)
            root.resolve("manifest.json").writeText(manifest.toString(2))
            assertEquals(declared, captureLocalImageContentSnapshot(record, force = true).fingerprint)
            assertEquals(declared, JSONObject(root.resolve("manifest.json").readText())
                .getJSONObject("executionProfile").getString("modelFingerprint"))
        }
    }

    @Test
    fun `changing runtime requirement invalidates published execution identity`() {
        withBundle { root, record, manifest ->
            val declared = manifest.getJSONObject("executionProfile").getString("modelFingerprint")
            manifest.getJSONObject("requiredRuntimeProfile").put("qnnSdk", "changed-test-runtime")
            root.resolve("manifest.json").writeText(manifest.toString())
            assertNotEquals(declared, captureLocalImageContentSnapshot(record, force = true).fingerprint)
        }
    }

    @Test
    fun `changing companion bytes invalidates published execution identity`() {
        withBundle { root, record, manifest ->
            val declared = manifest.getJSONObject("executionProfile").getString("modelFingerprint")
            root.resolve("manifest.json").writeText(manifest.toString())
            root.resolve("publisher/runtime/tokenizer.json").appendText("changed")
            assertNotEquals(declared, captureLocalImageContentSnapshot(record, force = true).fingerprint)
        }
    }

    private fun withBundle(block: (File, LocalImageModelRecord, JSONObject) -> Unit) {
        val root = Files.createTempDirectory("downloaded-manifest-fingerprint").toFile()
        try {
            val model = ModelScopeClient().recommendedModels().single { it.id == "cyberrealistic_sd15_qnn228" }
            val bundle = requireNotNull(model.imageEngineBundle)
            val targets = listOf("unet.bin", "vae_decoder.bin", "vae_encoder.bin", "clip_v2.mnn",
                "clip_v2.mnn.weight", "tokenizer.json", "token_emb.bin", "pos_emb.bin").map { name ->
                val relative = "publisher/runtime/$name"
                val file = root.resolve(relative).apply { requireNotNull(parentFile).mkdirs(); writeText("fixture:$name") }
                RemoteModelFile(repoId = "test/immutable", revision = "fixture", path = name, name = name,
                    downloadUrl = "https://example.invalid/$name", provider = ModelRepositoryProvider.MODELSCOPE,
                    bundleRole = if (name == "unet.bin") ImageEngineBundleComponentRole.DIFFUSION
                        else ImageEngineBundleComponentRole.CONFIG, relativePath = relative) to file
            }
            val primary = targets.first().second
            val record = LocalImageModelRecord(displayName = model.title, path = primary.absolutePath,
                fileName = primary.name, sizeBytes = primary.length(), sha256 = primary.sha256ForProfile(),
                runtime = LocalImageRuntime.QNN_HTP, family = LocalImageModelFamily.SD15,
                imageSize = "512x512", source = "download", bundleRoot = root.canonicalPath,
                recommendationId = bundle.recommendationId)
            val manifest = downloadedImageBundleManifestJson(model.title, bundle, targets,
                primarySha256 = record.sha256, bundleRoot = root)
            block(root, record, manifest)
        } finally {
            root.deleteRecursively()
        }
    }
}

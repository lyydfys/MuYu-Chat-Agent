package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineMinDeviceTier
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.VisionModelAccelerator
import com.muyuchat.core.download.VisionModelBundleComponentRole
import com.muyuchat.core.download.VisionModelBundleComponentSpec
import com.muyuchat.core.download.VisionModelBundleRuntime
import com.muyuchat.core.download.VisionModelBundleSpec
import com.muyuchat.core.download.VisionModelSmokeSpec
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalVisionBundleManifestTest {
    @Test
    fun downloadedVisionBundleManifestCanBeParsedBack() {
        val root = Files.createTempDirectory("vision-bundle").toFile()
        val main = root.touch("MiniCPM-V-4_6-Q4_K_M.gguf")
        val projector = root.touch("mmproj-model-f16.gguf")
        val bundle = VisionModelBundleSpec(
            id = "minicpm_v46_q4_vision_bundle",
            title = "MiniCPM-V 4.6 Q4 识图包",
            runtime = VisionModelBundleRuntime.GGUF_MMPROJ,
            accelerator = VisionModelAccelerator.CPU,
            minDeviceTier = ImageEngineMinDeviceTier.ANY,
            requiresQnnRuntime = false,
            requiresSmokeTest = true,
            smokeSpec = VisionModelSmokeSpec(
                imageWidth = 448,
                imageHeight = 448,
                prompt = "请用中文描述这张图片",
                timeoutSeconds = 120
            ),
            components = listOf(
                VisionModelBundleComponentSpec(
                    role = VisionModelBundleComponentRole.MAIN_MODEL,
                    repoId = "OpenBMB/MiniCPM-V-4.6-gguf",
                    fileName = main.name
                ),
                VisionModelBundleComponentSpec(
                    role = VisionModelBundleComponentRole.PROJECTOR,
                    repoId = "OpenBMB/MiniCPM-V-4.6-gguf",
                    fileName = projector.name
                )
            )
        )
        val targets = listOf(
            remote(main.name, VisionModelBundleComponentRole.MAIN_MODEL) to main,
            remote(projector.name, VisionModelBundleComponentRole.PROJECTOR) to projector
        )
        File(root, "manifest.json").writeText(
            downloadedVisionBundleManifestJson(
                displayName = "MiniCPM-V 4.6 Q4",
                bundle = bundle,
                targets = targets
            ).toString(2),
            Charsets.UTF_8
        )

        val manifest = localVisionBundleManifestFromRoot(root)

        assertNotNull(manifest)
        assertEquals("minicpm_v46_q4_vision_bundle", manifest!!.id)
        assertEquals("MiniCPM-V 4.6 Q4", manifest.displayName)
        assertEquals(VisionModelBundleRuntime.GGUF_MMPROJ, manifest.runtime)
        assertEquals(VisionModelAccelerator.CPU, manifest.accelerator)
        assertEquals(ImageEngineMinDeviceTier.ANY, manifest.minDeviceTier)
        assertFalse(manifest.requiresQnnRuntime)
        assertEquals("448x448", manifest.smokeImageSize)
        assertEquals("请用中文描述这张图片", manifest.smokePrompt)
        assertEquals(120, manifest.timeoutSeconds)
        assertEquals(main.canonicalFile, manifest.primaryFile!!.canonicalFile)
        assertEquals(projector.canonicalFile, manifest.projectorFile!!.canonicalFile)
        assertEquals(2, manifest.componentCount)
        assertFalse(manifest.npuActive)
    }

    @Test
    fun qnnVisionCandidateManifestKeepsNpuInactiveUntilSmoke() {
        val root = Files.createTempDirectory("vision-qnn-candidate").toFile()
        val main = root.touch("fastvlm_vision_qnn.ctx")
        root.touch("tokenizer.json")
        File(root, "manifest.json").writeText(
            """
            {
              "schema": "mca.vision_engine.bundle.v1",
              "id": "fastvlm-qnn-candidate",
              "title": "FastVLM NPU Candidate",
              "runtime": "LITERT_QNN",
              "accelerator": "QNN_HTP",
              "minDeviceTier": "SNAPDRAGON_8_ELITE",
              "requiresQnnRuntime": true,
              "requiresSmokeTest": true,
              "components": [
                {"role": "MAIN_MODEL", "path": "fastvlm_vision_qnn.ctx"},
                {"role": "TOKENIZER", "path": "tokenizer.json"}
              ],
              "smoke": {"imageWidth": 336, "imageHeight": 336, "prompt": "描述图片", "timeoutSeconds": 30}
            }
            """.trimIndent()
        )

        val manifest = localVisionBundleManifestFromRoot(root)

        assertNotNull(manifest)
        assertEquals(VisionModelBundleRuntime.LITERT_QNN, manifest!!.runtime)
        assertEquals(VisionModelAccelerator.QNN_HTP, manifest.accelerator)
        assertEquals(ImageEngineMinDeviceTier.SNAPDRAGON_8_ELITE, manifest.minDeviceTier)
        assertEquals(main.canonicalFile, manifest.primaryFile!!.canonicalFile)
        assertEquals("336x336", manifest.smokeImageSize)
        assertFalse(manifest.npuActive)
    }

    @Test
    fun manifestComponentPathCannotEscapeVisionBundleRoot() {
        val root = Files.createTempDirectory("vision-unsafe").toFile()
        root.touch("model.gguf")
        File(root, "manifest.json").writeText(
            """
            {
              "schema": "mca.vision_engine.bundle.v1",
              "runtime": "GGUF_MMPROJ",
              "components": [
                {"role": "MAIN_MODEL", "path": "../outside.gguf"}
              ]
            }
            """.trimIndent()
        )

        val manifest = localVisionBundleManifestFromRoot(root)

        assertNotNull(manifest)
        assertNull(manifest!!.primaryFile)
    }

    private fun File.touch(name: String): File =
        File(this, name).also {
            it.parentFile?.mkdirs()
            it.writeText("x")
        }

    private fun remote(
        fileName: String,
        role: VisionModelBundleComponentRole
    ): RemoteModelFile =
        RemoteModelFile(
            repoId = "OpenBMB/MiniCPM-V-4.6-gguf",
            revision = "master",
            path = fileName,
            name = fileName,
            downloadUrl = "https://example.invalid/$fileName",
            provider = ModelRepositoryProvider.MODELSCOPE,
            visionBundleRole = role
        )
}

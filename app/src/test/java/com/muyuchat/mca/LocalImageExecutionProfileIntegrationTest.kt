package com.muyuchat.mca

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalImageExecutionProfileIntegrationTest {
    @Test
    fun `manifest behavior and request overrides layer above root config and builtin`() {
        val root = Files.createTempDirectory("image-profile-integration").toFile()
        try {
            root.resolve("config.json").writeText(
                JSONObject()
                    .put("modelFamily", "SDXL")
                    .put(
                        "generation",
                        JSONObject()
                            .put("defaultSteps", 18)
                            .put("defaultCfg", 5.25)
                            .put("defaultPrompt", "config prompt")
                    )
                    .toString(),
                Charsets.UTF_8
            )
            root.resolve("manifest.json").writeText(
                JSONObject()
                    .put("id", "sd15_mnn_512_quality")
                    .put("modelFamily", "SD15")
                    .put(
                        "generationDefaults",
                        JSONObject()
                            .put("defaultSteps", 16)
                            .put("defaultPrompt", "manifest prompt")
                    )
                    .toString(),
                Charsets.UTF_8
            )

            val resolution = resolveLocalImageExecutionProfile(
                model = LocalImageModelRecord(
                    displayName = "Local image model",
                    path = root.resolve("model.bin").absolutePath,
                    fileName = "model.bin",
                    sizeBytes = 1L,
                    sha256 = FINGERPRINT,
                    runtime = LocalImageRuntime.MNN_DIFFUSION,
                    family = LocalImageModelFamily.SDXL,
                    bundleRoot = root.absolutePath
                ),
                options = LocalImageGenerationOptions(steps = 12),
                bundleRoot = root
            )

            assertEquals(LocalImageModelFamily.SD15, resolution.profile.family)
            assertEquals(12, resolution.profile.defaults.steps)
            assertEquals(5.25, resolution.profile.defaults.cfgScale, 0.0)
            assertEquals("manifest prompt", resolution.profile.defaults.defaultPrompt)
            assertEquals(ImageProfileSource.MANIFEST, resolution.fieldSources.getValue("family"))
            assertEquals(ImageProfileSource.USER_OVERRIDE, resolution.fieldSources.getValue("defaults.steps"))
            assertEquals(ImageProfileSource.SIDECAR, resolution.fieldSources.getValue("defaults.cfgScale"))
            assertEquals(ImageProfileSource.MANIFEST, resolution.fieldSources.getValue("defaults.defaultPrompt"))
            assertEquals(
                listOf(
                    ImageProfileSource.USER_OVERRIDE,
                    ImageProfileSource.MANIFEST,
                    ImageProfileSource.SIDECAR,
                    ImageProfileSource.BUILT_IN
                ),
                resolution.sourceChain
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `declared json behavior sidecar overlays root config without parsing non json graph config`() {
        val root = Files.createTempDirectory("image-profile-declared-sidecar").toFile()
        try {
            root.resolve("config.json").writeText(
                JSONObject()
                    .put("defaultCfg", 4.5)
                    .put("defaultPrompt", "root prompt")
                    .toString(),
                Charsets.UTF_8
            )
            root.resolve("metadata").mkdirs()
            root.resolve("metadata/behavior.json").writeText(
                JSONObject()
                    .put("defaultSteps", 14)
                    .put("defaultPrompt", "declared prompt")
                    .toString(),
                Charsets.UTF_8
            )
            root.resolve("runtime").mkdirs()
            root.resolve("runtime/state.bin").writeText("not-json", Charsets.UTF_8)

            val base = ImageExecutionProfileResolver.resolve(
                ImageExecutionProfileResolverInput(
                    modelFingerprint = FINGERPRINT,
                    runtime = LocalImageRuntime.CUSTOM,
                    family = LocalImageModelFamily.CUSTOM
                )
            ).profile
            val manifestProfile = base.copy(
                graph = base.graph.copy(
                    schedulerSidecar = "metadata/scheduler.json",
                    tokenizerSidecar = "metadata/tokenizer.json",
                    configSidecars = listOf("runtime/state.bin", "metadata/behavior.json")
                )
            )

            val behavior = requireNotNull(
                parseLocalImageExecutionProfileSidecars(root, manifestProfile)?.behavior
            )
            assertEquals(14, behavior.steps)
            assertEquals(4.5, behavior.cfgScale!!, 0.0)
            assertEquals("declared prompt", behavior.defaultPrompt)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `img2img strength binds evidence to the visited native timetable`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            ImageExecutionProfileResolverInput(
                modelFingerprint = FINGERPRINT,
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD15
            )
        )

        val adjusted = resolution.withProductDenoisingSchedule(
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImage = LocalImagePreparedInput(
                    path = "/cache/input.png",
                    mimeType = "image/png",
                    sha256 = "b".repeat(64),
                    sizeBytes = 1L,
                    width = 512,
                    height = 512
                ),
                strength = 0.6
            )
        )

        assertEquals(20, adjusted.layers.resolved.steps)
        assertEquals(13, adjusted.layers.resolved.timetableCount)
        assertEquals(26, adjusted.layers.resolved.unetExecutionCount)
    }

    private companion object {
        val FINGERPRINT: String = "a".repeat(64)
    }
}

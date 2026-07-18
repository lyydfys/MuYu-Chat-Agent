package com.muyuchat.mca

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            root.resolve("tokenizer.json").writeText(
                JSONObject()
                    .put("defaultSteps", 99)
                    .put("defaultPrompt", "must not be parsed as behavior")
                    .toString(),
                Charsets.UTF_8
            )

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
                    configSidecars = listOf(
                        "runtime/state.bin",
                        "tokenizer.json",
                        "metadata/behavior.json"
                    )
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

    @Test
    fun `readiness consumes migrated extracted assets before obsolete archive sidecars`() {
        val root = Files.createTempDirectory("image-profile-effective-readiness").toFile()
        try {
            val unet = root.resolve("unet.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            listOf(
                "vae_decoder.bin",
                "vae_encoder.bin",
                "clip_v2.mnn",
                "tokenizer.json",
                "token_emb.bin",
                "pos_emb.bin"
            ).forEachIndexed { index, path ->
                root.resolve(path).writeBytes(byteArrayOf((index + 5).toByte()))
            }
            val fingerprint = unet.sha256ForProfile()
            val oldProfile = requireNotNull(
                ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                    recommendationId = PINNED_RECOMMENDATION,
                    modelFingerprint = fingerprint
                )
            ).copy(
                profileRevision = 1,
                graph = requireNotNull(
                    ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                        recommendationId = PINNED_RECOMMENDATION,
                        modelFingerprint = fingerprint
                    )
                ).graph.copy(
                    schedulerSidecar = "scheduler/scheduler_config.json",
                    tokenizerSidecar = "tokenizer/tokenizer_config.json",
                    configSidecars = emptyList()
                )
            )
            val migratedProfile = oldProfile.copy(
                profileRevision = 2,
                graph = oldProfile.graph.copy(
                    schedulerSidecar = null,
                    tokenizerSidecar = null,
                    configSidecars = listOf(
                        "tokenizer.json",
                        "token_emb.bin",
                        "pos_emb.bin"
                    )
                )
            )
            val manifest = pinnedArchiveManifest(oldProfile)
            root.resolve("manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
            val effectiveResolver: LocalImageManifestProfileResolver = { input ->
                assertEquals(fingerprint, input.modelFingerprint)
                val exactIdentity = PINNED_REPOSITORY in
                    input.recommendationEvidence.sourceRepositories &&
                    input.recommendationEvidence.artifactPaths.any { path ->
                        path == "$PINNED_ARCHIVE!/unet.bin"
                    }
                resolveManifestProfileWithoutFurtherMigration(
                    input = input,
                    profile = if (exactIdentity) migratedProfile else requireNotNull(input.manifestProfile)
                )
            }

            val effective = requireNotNull(
                resolveEffectiveLocalImageManifestProfile(manifest, effectiveResolver)
            )
            val inspection = inspectLocalImageBundleManifestFromRoot(root, effectiveResolver)
            val parsed = (inspection as LocalImageBundleManifestInspection.Ready).manifest
            val contract = parsed.resolveRuntimeComponentContract(root)

            assertEquals(2, effective.profileRevision)
            assertEquals(unet.canonicalFile, parsed.primaryFile?.canonicalFile)
            assertTrue(contract is LocalImageRuntimeComponentContract.Ready)
            contract as LocalImageRuntimeComponentContract.Ready
            assertTrue(contract.missingPaths.isEmpty())
            assertTrue("scheduler/scheduler_config.json" !in contract.requiredPaths)
            assertTrue("tokenizer/tokenizer_config.json" !in contract.requiredPaths)

            manifest.getJSONArray("components").getJSONObject(0)
                .put("sourceRepo", "unrelated/publisher")
                .put("sourcePath", "unrelated.zip!/unet.bin")
            root.resolve("manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
            val conflicting = (
                inspectLocalImageBundleManifestFromRoot(root, effectiveResolver) as
                    LocalImageBundleManifestInspection.Ready
                ).manifest.resolveRuntimeComponentContract(root)
            assertTrue(conflicting is LocalImageRuntimeComponentContract.Invalid)

            manifest.getJSONArray("components").getJSONObject(0)
                .put("sourceRepo", PINNED_REPOSITORY)
                .put("sourcePath", "$PINNED_ARCHIVE!/unet.bin")
            root.resolve("pos_emb.bin").delete()
            root.resolve("manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
            val missingAsset = (
                inspectLocalImageBundleManifestFromRoot(root, effectiveResolver) as
                    LocalImageBundleManifestInspection.Ready
                ).manifest.resolveRuntimeComponentContract(root)
            assertTrue(missingAsset is LocalImageRuntimeComponentContract.Invalid)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `readiness migration uses persisted fingerprint without reading graph bytes`() {
        val persisted = requireNotNull(
            ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                recommendationId = PINNED_RECOMMENDATION,
                modelFingerprint = FINGERPRINT
            )
        ).copy(profileRevision = 1)
        val manifest = pinnedArchiveManifest(persisted)
        var resolverFingerprint: String? = null

        val effective = requireNotNull(
            resolveEffectiveLocalImageManifestProfile(manifest) { input ->
                resolverFingerprint = input.modelFingerprint
                resolveManifestProfileWithoutFurtherMigration(input, persisted)
            }
        )

        assertEquals(FINGERPRINT, resolverFingerprint)
        assertEquals(FINGERPRINT, effective.modelFingerprint)
    }

    private fun pinnedArchiveManifest(profile: ImageExecutionProfile): JSONObject = JSONObject()
        .put("schema", "mca.image_engine.bundle.v1")
        .put("id", PINNED_RECOMMENDATION)
        .put("recommendationId", PINNED_RECOMMENDATION)
        .put("revision", "master")
        .put("runtime", "QNN_HTP")
        .put("family", "SD15")
        .put("requiresQnnRuntime", false)
        .put("sourceRepo", PINNED_REPOSITORY)
        .put(
            "components",
            JSONArray().put(
                JSONObject()
                    .put("role", "DIFFUSION")
                    .put("path", PINNED_ARCHIVE)
                    .put("fileName", PINNED_ARCHIVE)
                    .put("sourceRepo", PINNED_REPOSITORY)
                    .put("sourcePath", "$PINNED_ARCHIVE!/unet.bin")
                    .put("required", true)
            )
        )
        .put("executionProfile", ImageExecutionProfileJson.toJson(profile))

    private fun resolveManifestProfileWithoutFurtherMigration(
        input: ImageExecutionProfileResolverInput,
        profile: ImageExecutionProfile
    ): ImageExecutionProfileResolution = ImageExecutionProfileResolver.resolve(
        input.copy(
            recommendationId = null,
            manifestProfile = profile,
            recommendationEvidence = ImageRecommendationEvidence()
        )
    )

    private companion object {
        val FINGERPRINT: String = "a".repeat(64)
        const val PINNED_RECOMMENDATION = "cyberrealistic_sd15_qnn228"
        const val PINNED_REPOSITORY = "Mr-J-369/CyberRealistic_Final-SD1.5-qnn2.28"
        const val PINNED_ARCHIVE = "cyberrealistic_final_qnn2.28_8gen2.zip"
    }
}

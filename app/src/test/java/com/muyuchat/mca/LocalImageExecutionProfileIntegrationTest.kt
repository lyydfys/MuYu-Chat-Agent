package com.muyuchat.mca

import com.muyuchat.core.download.ModelScopeClient
import java.io.RandomAccessFile
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageExecutionProfileIntegrationTest {
    @Test
    fun `stable diffusion catalog artifacts use the runtime native graph identity`() {
        val recommendation = ModelScopeClient().recommendedModels()
            .single { it.id == "qwen_image_21_q4_k_m" }
        val profile = requireNotNull(
            materializeDownloadedImageExecutionProfile(
                requireNotNull(recommendation.imageEngineBundle),
                "a".repeat(64)
            )
        )

        listOfNotNull(profile.graph.textEncoder, profile.graph.unet, profile.graph.vae)
            .forEach { artifact ->
                assertEquals("runtime-native", artifact.graphName)
            }
    }

    @Test
    fun `record recommendation identity resolves a minimal bundle manifest`() {
        val root = Files.createTempDirectory("qwen-minimal-manifest").toFile()
        try {
            val primary = root.resolve("qwen-image-2.1-Q4_K_M.gguf")
                .apply { writeBytes(byteArrayOf(1, 2, 3)) }
            root.resolve("manifest.json").writeText(
                JSONObject()
                    .put("id", "qwen_image_21_q4_k_m")
                    .put("runtime", "STABLE_DIFFUSION_CPP")
                    .put("family", "QWEN_IMAGE")
                    .put("primaryFile", primary.name)
                    .toString()
            )
            val resolution = resolveLocalImageExecutionProfile(
                model = LocalImageModelRecord(
                    displayName = primary.name,
                    path = primary.absolutePath,
                    fileName = primary.name,
                    sizeBytes = primary.length(),
                    sha256 = "",
                    runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                    family = LocalImageModelFamily.QWEN_IMAGE,
                    recommendationId = "qwen_image_21_q4_k_m",
                    bundleRoot = root.absolutePath
                ),
                options = LocalImageGenerationOptions(width = 320, height = 320, steps = 4),
                bundleRoot = root
            )
            assertEquals("sdcpp.qwen-image-2.1", resolution.profile.profileId)
            assertEquals("runtime-native", resolution.layers.resolved.graphName)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `upgrading installed Gen5 profiles retains publisher subdirectories before generation`() {
        val ids = setOf("qualcomm_sd15_gen5_qnn", "qualcomm_sd21_gen5_qnn", "qualcomm_controlnet_canny_gen5_qnn")
        ModelScopeClient().recommendedModels().filter { it.id in ids }.forEach { recommendation ->
            val root = Files.createTempDirectory("gen5-installed-upgrade").toFile()
            try {
                val bundle = requireNotNull(recommendation.imageEngineBundle)
                val wrapper = recommendation.recommendedFileName.removeSuffix(".zip")
                val directory = root.resolve(wrapper).apply { mkdirs() }
                val primary = directory.resolve("unet.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                val current = requireNotNull(materializeDownloadedImageExecutionProfile(bundle, primary.sha256ForProfile()))
                fun ImageGraphArtifactContract?.installedV2() = this?.let { artifact ->
                    val relative = "$wrapper/${artifact.relativePath}"
                    root.resolve(relative).takeUnless { it.exists() }?.writeBytes(byteArrayOf(4))
                    artifact.copy(relativePath = relative, graphName = "model")
                }
                val oldWithInstalledPaths = current.copy(profileRevision = 2, graph = current.graph.copy(
                    textEncoder = current.graph.textEncoder.installedV2(),
                    unet = current.graph.unet.installedV2(),
                    vae = current.graph.vae.installedV2(),
                    controlNet = current.graph.controlNet.installedV2()
                ))
                val components = JSONArray()
                listOfNotNull(
                    oldWithInstalledPaths.graph.textEncoder,
                    oldWithInstalledPaths.graph.unet,
                    oldWithInstalledPaths.graph.vae,
                    oldWithInstalledPaths.graph.controlNet
                ).forEach { graph ->
                    components.put(JSONObject()
                        .put("role", if (graph == oldWithInstalledPaths.graph.unet) "DIFFUSION" else "CONFIG")
                        .put("path", graph.relativePath).put("required", true)
                        .put("sourceRepo", recommendation.repoId)
                        .put("sourcePath", "${recommendation.recommendedFileName}!/${graph.relativePath}"))
                }
                bundle.requiredComponents.filterNot { it.fileName.endsWith(".zip") }.forEach { sidecar ->
                    // Use valid publisher sidecar fields so the production parser participates
                    // in the upgrade test instead of failing on an empty JSON placeholder.
                    val content = when (sidecar.relativePath.substringAfterLast('/')) {
                        "scheduler_config.json" -> JSONObject()
                            .put("_class_name", if (current.family == LocalImageModelFamily.SD21) "DDIMScheduler" else "PNDMScheduler")
                            .put("beta_start", 0.00085).put("beta_end", 0.012)
                            .put("beta_schedule", "scaled_linear").put("num_train_timesteps", 1_000)
                            .put("set_alpha_to_one", false).put("skip_prk_steps", true)
                            .put("steps_offset", 1).put("clip_sample", false)
                            .apply {
                                if (current.family == LocalImageModelFamily.SD21) put("prediction_type", "v_prediction")
                            }
                        "tokenizer_config.json" -> JSONObject().put("model_max_length", 77)
                            .put("bos_token", "<|startoftext|>").put("eos_token", "<|endoftext|>")
                            .put("pad_token", "<|endoftext|>")
                        else -> JSONObject()
                    }
                    root.resolve(sidecar.relativePath).apply { parentFile.mkdirs(); writeText(content.toString()) }
                    components.put(JSONObject().put("role", sidecar.role.name)
                        .put("path", sidecar.relativePath).put("required", true)
                        .put("sourceRepo", sidecar.repoId).put("sourcePath", sidecar.fileName))
                }
                val manifest = JSONObject().put("schema", "mca.image_engine.bundle.v1")
                    .put("id", bundle.id).put("recommendationId", recommendation.id)
                    .put("runtime", "QNN_HTP").put("family", current.family.name)
                    .put("task", current.task.name).put("components", components)
                    .put("executionProfile", ImageExecutionProfileJson.toJson(oldWithInstalledPaths))
                root.resolve("manifest.json").writeText(manifest.toString())
                val installedModel = LocalImageModelRecord(
                    id = recommendation.id, displayName = recommendation.title,
                    path = primary.absolutePath, fileName = primary.name,
                    sizeBytes = primary.length(), sha256 = primary.sha256ForProfile(),
                    runtime = LocalImageRuntime.QNN_HTP, family = current.family,
                    bundleRoot = root.absolutePath, source = "hugging_face:${recommendation.repoId}"
                )
                // A persisted installed profile is bound to the complete bundle inventory,
                // including the manifest contract and all publisher sidecars. The built-in
                // profile above intentionally starts with the primary-artifact hash, so this
                // fixture updates the persisted value to the observed installed-bundle hash.
                val persisted = oldWithInstalledPaths.copy(
                    modelFingerprint = captureLocalImageContentSnapshot(installedModel).fingerprint
                )
                manifest.put("executionProfile", ImageExecutionProfileJson.toJson(persisted))
                root.resolve("manifest.json").writeText(manifest.toString())
                val resolution = resolveLocalImageExecutionProfile(
                    model = installedModel,
                    options = LocalImageGenerationOptions(), bundleRoot = root
                )
                assertEquals(3, resolution.profile.profileRevision)
                listOfNotNull(resolution.profile.graph.textEncoder, resolution.profile.graph.unet,
                    resolution.profile.graph.vae, resolution.profile.graph.controlNet).forEach { artifact ->
                    assertTrue(artifact.relativePath.startsWith("$wrapper/"))
                    assertTrue(root.resolve(artifact.relativePath).isFile)
                    assertFalse(artifact.graphName == "model")
                }
                assertEquals(current.graph.unet!!.graphName, resolution.layers.resolved.graphName)
                // Resolution is request-scoped; the user's installed metadata is not rewritten.
                assertEquals(2, JSONObject(root.resolve("manifest.json").readText())
                    .getJSONObject("executionProfile").getInt("profileRevision"))
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `installed bundles that still carry the primary-artifact digest keep resolving`() {
        // Regression: the complete-bundle execution identity was introduced without a migration
        // for already-installed packages.  Those manifests still declare the catalog's
        // primary-artifact digest, so comparing it against a whole-bundle digest rejected every
        // previously installed image engine with MODEL_FINGERPRINT_MISMATCH -- which also pushed
        // them onto the legacy capability path and aborted the UI for UltraFix packages.
        val ids = setOf("qualcomm_sd15_gen5_qnn", "qualcomm_sd21_gen5_qnn", "qualcomm_controlnet_canny_gen5_qnn")
        ModelScopeClient().recommendedModels().filter { it.id in ids }.forEach { recommendation ->
            val root = Files.createTempDirectory("gen5-legacy-scope").toFile()
            try {
                val bundle = requireNotNull(recommendation.imageEngineBundle)
                val wrapper = recommendation.recommendedFileName.removeSuffix(".zip")
                val directory = root.resolve(wrapper).apply { mkdirs() }
                val primary = directory.resolve("unet.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                // Deliberately the primary artifact's digest: what a pre-migration install keeps.
                val current = requireNotNull(
                    materializeDownloadedImageExecutionProfile(bundle, primary.sha256ForProfile())
                )
                fun ImageGraphArtifactContract?.installedLegacy() = this?.let { artifact ->
                    val relative = "$wrapper/${artifact.relativePath}"
                    root.resolve(relative).takeUnless { it.exists() }?.writeBytes(byteArrayOf(4))
                    artifact.copy(relativePath = relative, graphName = "model")
                }
                val installed = current.copy(profileRevision = 2, graph = current.graph.copy(
                    textEncoder = current.graph.textEncoder.installedLegacy(),
                    unet = current.graph.unet.installedLegacy(),
                    vae = current.graph.vae.installedLegacy(),
                    controlNet = current.graph.controlNet.installedLegacy()
                ))
                val components = JSONArray()
                listOfNotNull(
                    installed.graph.textEncoder,
                    installed.graph.unet,
                    installed.graph.vae,
                    installed.graph.controlNet
                ).forEach { graph ->
                    components.put(JSONObject()
                        .put("role", if (graph == installed.graph.unet) "DIFFUSION" else "CONFIG")
                        .put("path", graph.relativePath).put("required", true)
                        .put("sourceRepo", recommendation.repoId)
                        .put("sourcePath", "${recommendation.recommendedFileName}!/${graph.relativePath}"))
                }
                bundle.requiredComponents.filterNot { it.fileName.endsWith(".zip") }.forEach { sidecar ->
                    val content = when (sidecar.relativePath.substringAfterLast('/')) {
                        "scheduler_config.json" -> JSONObject()
                            .put("_class_name", if (current.family == LocalImageModelFamily.SD21) "DDIMScheduler" else "PNDMScheduler")
                            .put("beta_start", 0.00085).put("beta_end", 0.012)
                            .put("beta_schedule", "scaled_linear").put("num_train_timesteps", 1_000)
                            .put("set_alpha_to_one", false).put("skip_prk_steps", true)
                            .put("steps_offset", 1).put("clip_sample", false)
                            .apply {
                                if (current.family == LocalImageModelFamily.SD21) put("prediction_type", "v_prediction")
                            }
                        "tokenizer_config.json" -> JSONObject().put("model_max_length", 77)
                            .put("bos_token", "<|startoftext|>").put("eos_token", "<|endoftext|>")
                            .put("pad_token", "<|endoftext|>")
                        else -> JSONObject()
                    }
                    root.resolve(sidecar.relativePath).apply { parentFile.mkdirs(); writeText(content.toString()) }
                    components.put(JSONObject().put("role", sidecar.role.name)
                        .put("path", sidecar.relativePath).put("required", true)
                        .put("sourceRepo", sidecar.repoId).put("sourcePath", sidecar.fileName))
                }
                val manifest = JSONObject().put("schema", "mca.image_engine.bundle.v1")
                    .put("id", bundle.id).put("recommendationId", recommendation.id)
                    .put("runtime", "QNN_HTP").put("family", current.family.name)
                    .put("task", current.task.name).put("components", components)
                    .put("executionProfile", ImageExecutionProfileJson.toJson(installed))
                root.resolve("manifest.json").writeText(manifest.toString())
                val installedModel = LocalImageModelRecord(
                    id = recommendation.id, displayName = recommendation.title,
                    path = primary.absolutePath, fileName = primary.name,
                    sizeBytes = primary.length(), sha256 = primary.sha256ForProfile(),
                    runtime = LocalImageRuntime.QNN_HTP, family = current.family,
                    bundleRoot = root.absolutePath, source = "hugging_face:${recommendation.repoId}"
                )

                // Must resolve: the declared digest equals this bundle's own primary artifact.
                val resolution = resolveLocalImageExecutionProfile(
                    model = installedModel,
                    options = LocalImageGenerationOptions(), bundleRoot = root
                )
                assertEquals(current.profileId, resolution.profile.profileId)
                assertTrue(resolution.profile.profileRevision >= 2)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `semantic smoke low-step marker is internal and preserves normal qnn quality floor`() {
        val root = Files.createTempDirectory("image-profile-semantic-low-step").toFile()
        try {
            val primary = root.resolve("unet.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val model = LocalImageModelRecord(
                id = PINNED_RECOMMENDATION,
                displayName = "Semantic smoke model",
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = primary.length(),
                sha256 = FINGERPRINT,
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SD15,
                bundleRoot = root.absolutePath
            )

            val ordinary = resolveLocalImageExecutionProfile(
                model = model,
                options = LocalImageGenerationOptions(steps = 4),
                bundleRoot = root
            )
            assertEquals(4, ordinary.profile.scheduler.minSteps)
            assertEquals(4, ordinary.profile.defaults.steps)

            val smoke = resolveLocalImageExecutionProfile(
                model = model,
                options = LocalImageGenerationOptions(
                    steps = 4,
                    allowLowStepSmoke = true
                ),
                bundleRoot = root
            )
            assertEquals(4, smoke.profile.scheduler.minSteps)
            assertEquals(4, smoke.profile.defaults.steps)
            assertFalse(LocalImageGenerationOptions(allowLowStepSmoke = true).toJson().has("allowLowStepSmoke"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `MNN Qwen tokenizer text table is not parsed as a JSON sidecar`() {
        val recommendation = ModelScopeClient().recommendedModels()
            .single { it.id == "qwen_image_21_mnn_opencl" }
        val profile = requireNotNull(
            materializeDownloadedImageExecutionProfile(
                bundle = requireNotNull(recommendation.imageEngineBundle),
                modelFingerprint = FINGERPRINT
            )
        ).let { profile ->
            profile.copy(graph = profile.graph.copy(tokenizerSidecar = "text_encoder/tokenizer.txt"))
        }
        val root = Files.createTempDirectory("qwen-tokenizer-sidecar").toFile()
        try {
            root.resolve("text_encoder/tokenizer.txt").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("430\n", Charsets.UTF_8)
            }

            assertNull(parseLocalImageExecutionProfileSidecars(root, profile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `semantic smoke low-step marker preserves normal mnn quality floor`() {
        val ordinaryFailure = assertThrows(ImageProfileResolutionException::class.java) {
            ImageExecutionProfileResolver.resolve(
                ImageExecutionProfileResolverInput(
                    modelFingerprint = "b".repeat(64),
                    runtime = LocalImageRuntime.MNN_DIFFUSION,
                    family = LocalImageModelFamily.SD15,
                    recommendationId = "sd15_mnn_512_quality",
                    userOverrides = ImageGenerationOverrides(
                        scheduler = ImageSchedulerAlgorithm.EULER,
                        steps = 1
                    )
                )
            )
        }
        assertTrue(ordinaryFailure.validation.issues.any {
            it.code == "SCHEDULER_CONTRACT_INVALID"
        })

        val smoke = ImageExecutionProfileResolver.resolve(
            ImageExecutionProfileResolverInput(
                modelFingerprint = "b".repeat(64),
                runtime = LocalImageRuntime.MNN_DIFFUSION,
                family = LocalImageModelFamily.SD15,
                recommendationId = "sd15_mnn_512_quality",
                userOverrides = ImageGenerationOverrides(
                    scheduler = ImageSchedulerAlgorithm.EULER,
                    steps = 1,
                    allowLowStepSmoke = true
                )
            )
        )

        assertEquals("mnn.sd15.official.512", smoke.profile.profileId)
        assertEquals(ImageSchedulerAlgorithm.EULER, smoke.profile.scheduler.algorithm)
        assertEquals(1, smoke.profile.scheduler.minSteps)
        assertEquals(1, smoke.profile.scheduler.defaultSteps)
        assertEquals(1, smoke.profile.scheduler.order)
        assertTrue(smoke.profile.scheduler.scaleModelInput)
        assertFalse(smoke.profile.scheduler.lowerOrderFinal)
        assertEquals(1, smoke.profile.defaults.steps)
    }

    @Test
    fun `legacy root graph paths rebind to one unique nested publisher directory`() {
        val root = Files.createTempDirectory("image-profile-legacy-nested-paths").toFile()
        try {
            val nested = root.resolve("stable_diffusion_v2_1").apply { mkdirs() }
            listOf("text_encoder.bin", "unet.bin", "vae.bin").forEachIndexed { index, name ->
                nested.resolve(name).writeBytes(byteArrayOf((index + 1).toByte()))
            }
            val base = ImageExecutionProfileResolver.resolve(
                ImageExecutionProfileResolverInput(
                    modelFingerprint = "a".repeat(64),
                    runtime = LocalImageRuntime.QNN_HTP,
                    family = LocalImageModelFamily.SD15
                )
            ).profile
            val legacy = base.copy(
                graph = base.graph.copy(
                    textEncoder = ImageGraphArtifactContract("text_encoder.bin"),
                    unet = ImageGraphArtifactContract("unet.bin"),
                    vae = ImageGraphArtifactContract("vae.bin")
                )
            )

            val rebound = legacy.rebindInstalledArtifactPaths(root)

            assertEquals("stable_diffusion_v2_1/text_encoder.bin", rebound.graph.textEncoder?.relativePath)
            assertEquals("stable_diffusion_v2_1/unet.bin", rebound.graph.unet?.relativePath)
            assertEquals("stable_diffusion_v2_1/vae.bin", rebound.graph.vae?.relativePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy root graph path stays unresolved when basename is ambiguous`() {
        val root = Files.createTempDirectory("image-profile-ambiguous-nested-paths").toFile()
        try {
            listOf("a", "b").forEach { directory ->
                root.resolve(directory).apply { mkdirs() }
                    .resolve("unet.bin")
                    .writeBytes(byteArrayOf(1))
            }
            val base = ImageExecutionProfileResolver.resolve(
                ImageExecutionProfileResolverInput(
                    modelFingerprint = "b".repeat(64),
                    runtime = LocalImageRuntime.QNN_HTP,
                    family = LocalImageModelFamily.SD15
                )
            ).profile
            val legacy = base.copy(graph = base.graph.copy(unet = ImageGraphArtifactContract("unet.bin")))

            assertEquals("unet.bin", legacy.rebindInstalledArtifactPaths(root).graph.unet?.relativePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy model without stored sha reaches generic runtime resolution`() {
        val root = Files.createTempDirectory("image-profile-legacy-fingerprint").toFile()
        try {
            val primary = root.resolve("legacy.safetensors").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val profile = resolveLocalImageExecutionProfile(
                model = LocalImageModelRecord(
                    id = "legacy-no-sha",
                    displayName = "Legacy model",
                    path = primary.absolutePath,
                    fileName = primary.name,
                    sizeBytes = primary.length(),
                    sha256 = "",
                    runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                    family = LocalImageModelFamily.SD15
                ),
                options = LocalImageGenerationOptions(),
                bundleRoot = null
            ).profile

            assertEquals(primary.sha256ForProfile(), profile.modelFingerprint)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy manifest fingerprint cannot self attest replaced model content`() {
        val root = Files.createTempDirectory("image-profile-legacy-mismatch").toFile()
        try {
            val primary = root.resolve("legacy.safetensors").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val declared = "0".repeat(64)
            val model = LocalImageModelRecord(
                id = "legacy-mismatch",
                displayName = "Legacy mismatch",
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = primary.length(),
                sha256 = "",
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD15
            )
            val manifest = resolveLocalImageExecutionProfile(
                model = model,
                options = LocalImageGenerationOptions(),
                bundleRoot = null
            ).profile.copy(modelFingerprint = declared)
            root.resolve("manifest.json").writeText(
                JSONObject()
                    .put("id", "legacy-mismatch")
                    .put("executionProfile", ImageExecutionProfileJson.toJson(manifest))
                    .toString(),
                Charsets.UTF_8
            )
            val error = assertThrows(IllegalArgumentException::class.java) {
                resolveLocalImageExecutionProfile(
                    model = model,
                    options = LocalImageGenerationOptions(),
                    bundleRoot = root
                )
            }
            assertTrue(error.message.orEmpty().contains("does not match"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stable fallback uses native utf8 ordering for last matching component`() {
        val root = Files.createTempDirectory("image-profile-stable-utf8-order").toFile()
        try {
            val primary = root.resolve("primary.safetensors").apply { writeBytes(byteArrayOf(1)) }
            root.resolve("\uE000_clip_g.safetensors").writeBytes(byteArrayOf(2))
            val nativeLast = root.resolve("\uD83D\uDE00_clip_g.safetensors")
                .apply { writeBytes(byteArrayOf(3)) }

            val selected = resolveStableDiffusionCompatibilityPromptComponents(root, primary)

            assertEquals(listOf(nativeLast.canonicalFile), selected)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stable fallback consumer classifier mirrors native priority and excludes decoys`() {
        val root = Files.createTempDirectory("image-profile-stable-consumers").toFile()
        try {
            val primary = root.resolve("primary.safetensors").apply { writeBytes(byteArrayOf(1)) }
            val included = setOf(
                "high_noise_diffusion.safetensors",
                "z_clip_g.safetensors",
                "clip_l.safetensors",
                "t5xxl.safetensors",
                "embeddings_connector.safetensors",
                "llm_vision.safetensors",
                "z_qwen_llm.safetensors"
            )
            val excluded = setOf(
                "clip_vision_g.safetensors",
                "llm_vae.safetensors",
                "controlnet_llm.safetensors",
                "ordinary_diffusion.safetensors",
                "a_clip_g.safetensors",
                "a_qwen_llm.safetensors"
            )
            (included + excluded - setOf(
                "z_clip_g.safetensors",
                "z_qwen_llm.safetensors",
                "a_clip_g.safetensors",
                "a_qwen_llm.safetensors"
            )).forEach { name ->
                root.resolve(name).writeBytes(byteArrayOf(2))
            }
            listOf(
                "z_clip_g.safetensors",
                "z_qwen_llm.safetensors",
                "a_clip_g.safetensors",
                "a_qwen_llm.safetensors"
            ).forEach { name -> root.resolve(name).writeBytes(byteArrayOf(3)) }

            val actual = resolveStableDiffusionCompatibilityPromptComponents(root, primary)
                .mapTo(linkedSetOf()) { file -> file.name }

            assertEquals(included, actual)
            assertTrue(excluded.intersect(actual).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `complete installed execution asset pin avoids a host content hash`() {
        val root = Files.createTempDirectory("image-profile-installed-pin").toFile()
        try {
            val asset = root.resolve("tokenizer.json").apply {
                writeText("actual bytes differ from the installed digest fixture", Charsets.UTF_8)
            }
            val installedDigest = "f".repeat(64)
            val descriptor = captureImageExecutionAssetDescriptor(
                bundleRoot = root,
                source = asset,
                installedPin = ImageProfileAsset(
                    relativePath = asset.name,
                    fingerprint = installedDigest,
                    sizeBytes = asset.length()
                )
            )

            assertEquals(installedDigest, descriptor.sha256)
            assertEquals(asset.length(), descriptor.sizeBytes)
            assertThrows(IllegalArgumentException::class.java) {
                captureImageExecutionAssetDescriptor(
                    bundleRoot = root,
                    source = asset,
                    installedPin = ImageProfileAsset(
                        relativePath = asset.name,
                        fingerprint = installedDigest,
                        sizeBytes = asset.length() + 1L
                    )
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `capability discovery derives textual inversion only from host writable clip topology`() {
        data class Case(
            val runtime: LocalImageRuntime,
            val family: LocalImageModelFamily,
            val textEncoder: String,
            val extraTextEncoder: String? = null,
            val unet: String,
            val vae: String,
            val expectedWorker: ImageWorkerStrategy,
            val expectedTextualInversion: Boolean
        )

        val cases = listOf(
            Case(
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SD15,
                textEncoder = "clip_v2.mnn",
                extraTextEncoder = "text_encoder.bin",
                unet = "unet.bin",
                vae = "vae_decoder.bin",
                expectedWorker = ImageWorkerStrategy.SHARED_UNET_VAE,
                expectedTextualInversion = true
            ),
            Case(
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SDXL,
                textEncoder = "clip.mnn",
                extraTextEncoder = "text_encoder.bin",
                unet = "unet.bin",
                vae = "vae_decoder.bin",
                expectedWorker = ImageWorkerStrategy.SPLIT_UNET_VAE,
                expectedTextualInversion = true
            ),
            Case(
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SD15,
                textEncoder = "text_encoder.bin",
                unet = "unet.bin",
                vae = "vae.bin",
                expectedWorker = ImageWorkerStrategy.SHARED_TEXT_UNET_VAE,
                expectedTextualInversion = false
            ),
            Case(
                runtime = LocalImageRuntime.MNN_DIFFUSION,
                family = LocalImageModelFamily.SD15,
                textEncoder = "clip_v2.mnn",
                extraTextEncoder = "text_encoder.mnn",
                unet = "unet.mnn",
                vae = "vae_decoder.mnn",
                expectedWorker = ImageWorkerStrategy.IN_PROCESS,
                expectedTextualInversion = true
            ),
            Case(
                runtime = LocalImageRuntime.MNN_DIFFUSION,
                family = LocalImageModelFamily.SD15,
                textEncoder = "text_encoder.mnn",
                unet = "unet.mnn",
                vae = "vae_decoder.mnn",
                expectedWorker = ImageWorkerStrategy.IN_PROCESS,
                expectedTextualInversion = false
            )
        )

        cases.forEachIndexed { index, case ->
            val root = Files.createTempDirectory("image-profile-ti-discovery-$index").toFile()
            try {
                val primary = root.resolve(case.unet).apply { writeBytes(byteArrayOf(1)) }
                root.resolve(case.vae).writeBytes(byteArrayOf(2))
                root.resolve(case.textEncoder).writeBytes(byteArrayOf(3))
                case.extraTextEncoder?.let { root.resolve(it).writeBytes(byteArrayOf(4)) }
                root.resolve("tokenizer.json").writeText("{}", Charsets.UTF_8)
                if (case.expectedTextualInversion) {
                    root.resolve("pos_emb.bin").writeBytes(byteArrayOf(5))
                    RandomAccessFile(root.resolve("token_emb.bin"), "rw").use { file ->
                        file.setLength(75_890_688L)
                    }
                    if (case.family == LocalImageModelFamily.SDXL) {
                        root.resolve("clip_2.mnn").writeBytes(byteArrayOf(6))
                        root.resolve("clip_2.mnn.weight").writeBytes(byteArrayOf(7))
                        root.resolve("pos_emb_2.bin").writeBytes(byteArrayOf(8))
                        RandomAccessFile(root.resolve("token_emb_2.bin"), "rw").use { file ->
                            file.setLength(126_484_480L)
                        }
                    }
                }

                val profile = resolveLocalImageExecutionProfile(
                    model = LocalImageModelRecord(
                        id = "generic-topology-$index",
                        displayName = "Generic topology $index",
                        path = primary.absolutePath,
                        fileName = primary.name,
                        sizeBytes = primary.length(),
                        sha256 = (index + 1).toString(16).repeat(64),
                        runtime = case.runtime,
                        family = case.family,
                        bundleRoot = root.absolutePath
                    ),
                    options = LocalImageGenerationOptions(),
                    bundleRoot = root
                ).profile

                assertEquals(
                    "Unexpected text encoder for capability-discovery case $index: $case",
                    case.textEncoder,
                    profile.graph.textEncoder?.relativePath
                )
                assertEquals(ImageProfileSource.CAPABILITY_DISCOVERY, profile.provenance.primarySource)
                assertEquals(case.expectedWorker, profile.graph.workerStrategy)
                assertEquals(
                    case.expectedTextualInversion,
                    profile.hasHostWritableClipTextualInversionTopology()
                )
                assertEquals(case.expectedTextualInversion, profile.tokenizer.supportsTextualInversion)
                assertEquals(case.expectedTextualInversion, profile.capabilities.supportsTextualInversion)
                if (!case.expectedTextualInversion) {
                    assertFalse(profile.hasHostWritableClipTextualInversionTopology())
                } else {
                    val optionalWeightName = "${case.textEncoder}.weight"
                    val withoutWeight = resolveTextualInversionConsumerAssetFiles(
                        profile = profile,
                        primaryModel = primary,
                        root = root
                    ).mapTo(linkedSetOf()) { file -> file.name }
                    assertFalse(optionalWeightName in withoutWeight)
                    assertFalse(primary.name in withoutWeight)

                    root.resolve(optionalWeightName).writeBytes(byteArrayOf(9))
                    val withWeight = resolveTextualInversionConsumerAssetFiles(
                        profile = profile,
                        primaryModel = primary,
                        root = root
                    ).mapTo(linkedSetOf()) { file -> file.name }
                    assertEquals(withoutWeight + optionalWeightName, withWeight)
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `capability discovery never splices clip and diffusion graphs across directories`() {
        val root = Files.createTempDirectory("image-profile-ti-cross-directory").toFile()
        try {
            val clipDirectory = root.resolve("conditioning").apply { mkdirs() }
            val graphDirectory = root.resolve("diffusion").apply { mkdirs() }
            val primary = clipDirectory.resolve("unet.bin").apply { writeBytes(byteArrayOf(1)) }
            clipDirectory.resolve("clip_v2.mnn").writeBytes(byteArrayOf(2))
            clipDirectory.resolve("tokenizer.json").writeText("{}", Charsets.UTF_8)
            clipDirectory.resolve("pos_emb.bin").writeBytes(byteArrayOf(3))
            RandomAccessFile(clipDirectory.resolve("token_emb.bin"), "rw").use { file ->
                file.setLength(75_890_688L)
            }
            graphDirectory.resolve("vae_decoder.bin").writeBytes(byteArrayOf(4))

            val profile = resolveLocalImageExecutionProfile(
                model = LocalImageModelRecord(
                    id = "generic-cross-directory",
                    displayName = "Generic cross-directory bundle",
                    path = primary.absolutePath,
                    fileName = primary.name,
                    sizeBytes = primary.length(),
                    sha256 = "f".repeat(64),
                    runtime = LocalImageRuntime.QNN_HTP,
                    family = LocalImageModelFamily.SD15,
                    bundleRoot = root.absolutePath
                ),
                options = LocalImageGenerationOptions(),
                bundleRoot = root
            ).profile

            assertEquals("conditioning/unet.bin", profile.graph.unet?.relativePath)
            assertTrue(profile.graph.vae == null)
            assertFalse(profile.hasHostWritableClipTextualInversionTopology())
            assertFalse(profile.tokenizer.supportsTextualInversion)
            assertFalse(profile.capabilities.supportsTextualInversion)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `capability discovery skips an earlier incomplete clip bundle`() {
        val root = Files.createTempDirectory("image-profile-ti-complete-candidate").toFile()
        try {
            fun writeClipAssets(directory: java.io.File, marker: Byte) {
                directory.mkdirs()
                directory.resolve("clip_v2.mnn").writeBytes(byteArrayOf(marker))
                directory.resolve("tokenizer.json").writeText("{}", Charsets.UTF_8)
                directory.resolve("pos_emb.bin").writeBytes(byteArrayOf(marker))
                RandomAccessFile(directory.resolve("token_emb.bin"), "rw").use { file ->
                    file.setLength(75_890_688L)
                }
            }

            val incomplete = root.resolve("a-incomplete")
            writeClipAssets(incomplete, 1)
            incomplete.resolve("unet.bin").writeBytes(byteArrayOf(2))

            val complete = root.resolve("b-complete")
            writeClipAssets(complete, 3)
            val primary = complete.resolve("unet.bin").apply { writeBytes(byteArrayOf(4)) }
            complete.resolve("vae_decoder.bin").writeBytes(byteArrayOf(5))

            val profile = resolveLocalImageExecutionProfile(
                model = LocalImageModelRecord(
                    id = "generic-complete-candidate",
                    displayName = "Generic complete candidate",
                    path = primary.absolutePath,
                    fileName = primary.name,
                    sizeBytes = primary.length(),
                    sha256 = "e".repeat(64),
                    runtime = LocalImageRuntime.QNN_HTP,
                    family = LocalImageModelFamily.SD15,
                    bundleRoot = root.absolutePath
                ),
                options = LocalImageGenerationOptions(),
                bundleRoot = root
            ).profile

            assertEquals("b-complete/clip_v2.mnn", profile.graph.textEncoder?.relativePath)
            assertEquals("b-complete/unet.bin", profile.graph.unet?.relativePath)
            assertEquals("b-complete/vae_decoder.bin", profile.graph.vae?.relativePath)
            assertTrue(profile.hasHostWritableClipTextualInversionTopology())
            assertTrue(profile.capabilities.supportsTextualInversion)
        } finally {
            root.deleteRecursively()
        }
    }

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
            val primary = root.resolve("model.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
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
                    path = primary.absolutePath,
                    fileName = primary.name,
                    sizeBytes = primary.length(),
                    sha256 = primary.sha256ForProfile(),
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

        listOf(
            0.0 to 1,
            0.05 to 1,
            0.5 to 10,
            0.75 to 15,
            1.0 to 20
        ).forEach { (strength, expectedTimetableCount) ->
            val boundary = resolution.withProductDenoisingSchedule(
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
                    strength = strength
                )
            )
            assertEquals(expectedTimetableCount, boundary.layers.resolved.timetableCount)
        }
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

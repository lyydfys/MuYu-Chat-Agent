package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineAccelerator
import com.muyuchat.core.download.ImageEngineBundleComponentRole
import com.muyuchat.core.download.ImageEngineBundleComponentSpec
import com.muyuchat.core.download.ImageEngineBundleRuntime
import com.muyuchat.core.download.ImageEngineBundleSpec
import com.muyuchat.core.download.ImageEngineIntegrityMetadataStatus
import com.muyuchat.core.download.ImageEngineMinDeviceTier
import com.muyuchat.core.download.ImageEngineQnnSmokeSpec
import com.muyuchat.core.download.ImageEngineQnnSmokeTensorSpec
import com.muyuchat.core.download.ImageEngineQnnRuntimeProfileSpec
import com.muyuchat.core.download.ImageEngineSmokeSpec
import com.muyuchat.core.download.ImageEngineTask
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.ModelScopeRecommendedKind
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.deviceprofile.QnnRuntimeProbeState
import com.muyuchat.core.deviceprofile.QnnRuntimeStatus
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageModelReadinessTest {
    @Test
    fun everyRecommendedImagePersistsItsCatalogExecutionProfileBoundToPrimaryBytes() {
        val models = ModelScopeClient().recommendedModels()
            .filter { it.kind == ModelScopeRecommendedKind.IMAGE }
        assertEquals(18, models.size)
        models.forEach { model ->
            val bundle = requireNotNull(model.imageEngineBundle)
            val source = requireNotNull(bundle.executionProfile)
            val root = Files.createTempDirectory("catalog-profile-${model.id}").toFile()
            try {
                val primary = File(root, "${model.id}.bin").apply {
                    writeText("primary:${model.id}", Charsets.UTF_8)
                }
                val manifest = downloadedImageBundleManifestJson(
                    displayName = model.title,
                    bundle = bundle,
                    targets = listOf(
                        remote(primary.name, ImageEngineBundleComponentRole.DIFFUSION) to primary
                    )
                )
                val profile = ImageExecutionProfileJson.parseProfile(
                    manifest.getJSONObject("executionProfile")
                )

                assertEquals(source.profileId, profile.profileId)
                assertEquals(source.profileRevision, profile.profileRevision)
                assertEquals(source.scheduler.algorithm.name, profile.scheduler.algorithm.name)
                assertEquals(source.defaults.steps, profile.defaults.steps)
                assertEquals(source.defaults.cfgScale, profile.defaults.cfgScale, 0.0)
                assertEquals(source.defaults.width, profile.defaults.width)
                assertEquals(source.defaults.height, profile.defaults.height)
                assertEquals(ImageProfileSource.MANIFEST, profile.provenance.primarySource)
                assertEquals(model.id, profile.provenance.recommendationId)
                assertEquals(primary.sha256ForProfile(), profile.modelFingerprint)
                assertEquals(source.graph.vaeEncoder, profile.graph.vaeEncoder?.relativePath)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun expandedQnnZipPersistsExactSd15PromptConsumerPins() {
        val root = Files.createTempDirectory("qnn-sd15-expanded-prompt-pins").toFile()
        try {
            val model = ModelScopeClient().recommendedModels()
                .single { it.id == "cyberrealistic_sd15_qnn228" }
            val bundle = requireNotNull(model.imageEngineBundle)
            val nested = File(root, "publisher/runtime").apply { mkdirs() }
            val files = listOf(
                "unet.bin",
                "vae_decoder.bin",
                "vae_encoder.bin",
                "clip_v2.mnn",
                "clip_v2.mnn.weight",
                "tokenizer.json",
                "token_emb.bin",
                "pos_emb.bin"
            ).associateWith { name -> nested.touch(name, "expanded-$name") }
            val primary = requireNotNull(files["unet.bin"])
            val targets = files.map { (name, file) ->
                remote(
                    name,
                    if (name == "unet.bin") {
                        ImageEngineBundleComponentRole.DIFFUSION
                    } else {
                        ImageEngineBundleComponentRole.CONFIG
                    }
                ).copy(relativePath = file.relativeTo(root).invariantSeparatorsPath) to file
            }

            val manifest = downloadedImageBundleManifestJson(
                displayName = model.title,
                bundle = bundle,
                targets = targets,
                primarySha256 = primary.sha256ForProfile(),
                bundleRoot = root
            )
            val profile = ImageExecutionProfileJson.parseProfile(
                manifest.getJSONObject("executionProfile")
            )
            val pins = profile.tokenizer.assets.associateBy(ImageProfileAsset::relativePath)
            val expectedNames = setOf(
                "clip_v2.mnn",
                "clip_v2.mnn.weight",
                "tokenizer.json",
                "token_emb.bin",
                "pos_emb.bin"
            )
            val expectedLabels = expectedNames.mapTo(linkedSetOf()) { name ->
                requireNotNull(files[name]).relativeTo(root).invariantSeparatorsPath
            }

            assertEquals(expectedLabels, pins.keys)
            expectedNames.forEach { name ->
                val file = requireNotNull(files[name])
                val pin = requireNotNull(pins[file.relativeTo(root).invariantSeparatorsPath])
                assertEquals(file.length(), pin.sizeBytes)
                assertEquals(file.sha256ForProfile(), pin.fingerprint)
            }
            assertFalse(pins.keys.any { path -> path.endsWith("unet.bin") })
            assertFalse(pins.keys.any { path -> path.endsWith("vae_decoder.bin") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun expandedQnnZipPersistsExactSdxlPromptConsumerPinsIncludingOptionalClipWeight() {
        val root = Files.createTempDirectory("qnn-sdxl-expanded-prompt-pins").toFile()
        try {
            val model = ModelScopeClient().recommendedModels()
                .single { it.id == "sdxl_base_qnn228" }
            val bundle = requireNotNull(model.imageEngineBundle)
            val nested = File(root, "output/qnn_models_sdxl_8gen3").apply { mkdirs() }
            val names = listOf(
                "unet.bin",
                "vae_decoder.bin",
                "vae_encoder.bin",
                "clip.mnn",
                "clip.mnn.weight",
                "clip_2.mnn",
                "clip_2.mnn.weight",
                "tokenizer.json",
                "token_emb.bin",
                "token_emb_2.bin",
                "pos_emb.bin",
                "pos_emb_2.bin"
            )
            val files = names.associateWith { name -> nested.touch(name, "expanded-$name") }
            val primary = requireNotNull(files["unet.bin"])
            val targets = files.map { (name, file) ->
                remote(
                    name,
                    if (name == "unet.bin") {
                        ImageEngineBundleComponentRole.DIFFUSION
                    } else {
                        ImageEngineBundleComponentRole.CONFIG
                    }
                ).copy(relativePath = file.relativeTo(root).invariantSeparatorsPath) to file
            }

            val manifest = downloadedImageBundleManifestJson(
                displayName = model.title,
                bundle = bundle,
                targets = targets,
                primarySha256 = primary.sha256ForProfile(),
                bundleRoot = root
            )
            val profile = ImageExecutionProfileJson.parseProfile(
                manifest.getJSONObject("executionProfile")
            )
            val pins = profile.tokenizer.assets.associateBy(ImageProfileAsset::relativePath)
            val expectedNames = names.drop(3).toSet()
            val expectedLabels = expectedNames.mapTo(linkedSetOf()) { name ->
                requireNotNull(files[name]).relativeTo(root).invariantSeparatorsPath
            }

            assertEquals(expectedLabels, pins.keys)
            expectedNames.forEach { name ->
                val file = requireNotNull(files[name])
                val pin = requireNotNull(pins[file.relativeTo(root).invariantSeparatorsPath])
                assertEquals(file.length(), pin.sizeBytes)
                assertEquals(file.sha256ForProfile(), pin.fingerprint)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stableDiffusionDownloadedProfilePinsItsActualPrimaryConsumer() {
        val root = Files.createTempDirectory("sdcpp-primary-prompt-pin").toFile()
        try {
            val model = ModelScopeClient().recommendedModels()
                .single { it.id == "sd_turbo_512_experimental" }
            val bundle = requireNotNull(model.imageEngineBundle)
            val primary = root.touch("sd_turbo.safetensors", "actual-primary-bytes")
            val target = remote(primary.name, ImageEngineBundleComponentRole.DIFFUSION)
                .copy(relativePath = primary.name) to primary

            val manifest = downloadedImageBundleManifestJson(
                displayName = model.title,
                bundle = bundle,
                targets = listOf(target),
                primarySha256 = primary.sha256ForProfile(),
                bundleRoot = root
            )
            val profile = ImageExecutionProfileJson.parseProfile(
                manifest.getJSONObject("executionProfile")
            )

            assertEquals(
                listOf(ImageProfileAsset(primary.name, primary.sha256ForProfile(), primary.length())),
                profile.tokenizer.assets
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun universalQnnArchivePersistsTheExactLocalTransportProfile() {
        val root = Files.createTempDirectory("qnn-universal-installed-profile").toFile()
        try {
            val runtime = File(root, "runtime").apply { mkdirs() }
            File(runtime, "libQnnSystem.so").writeText("system")
            File(runtime, "libQnnHtp.so").writeText("htp")
            listOf(68, 79).forEach { arch ->
                File(runtime, "libQnnHtpV${arch}Skel.so").writeText("skel-$arch")
                File(runtime, "libQnnHtpV${arch}Stub.so").writeText("stub-$arch")
            }
            val bundle = ImageEngineBundleSpec(
                id = "generic-qnn",
                title = "Generic QNN",
                components = emptyList(),
                requiredRuntimeProfile = ImageEngineQnnRuntimeProfileSpec(
                    qnnSdk = "2.28",
                    htpArch = 68,
                    completeBundleRuntime = true
                )
            )

            val elite = resolveInstalledQnnRuntimeProfile(root, bundle, preferredHtpArch = 79)
            val unknownFuture = resolveInstalledQnnRuntimeProfile(root, bundle, preferredHtpArch = 83)

            assertEquals(79, elite.requiredRuntimeProfile?.htpArch)
            assertEquals(68, unknownFuture.requiredRuntimeProfile?.htpArch)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun graphOnlyQnnArchiveDoesNotInventACompleteRuntimeContract() {
        val root = Files.createTempDirectory("qnn-graph-only-installed-profile").toFile()
        try {
            val bundle = ImageEngineBundleSpec(
                id = "graph-only-qnn",
                title = "Graph-only QNN",
                components = emptyList(),
                requiredRuntimeProfile = ImageEngineQnnRuntimeProfileSpec(
                    qnnSdk = "2.28",
                    htpArch = 68,
                    completeBundleRuntime = false
                )
            )

            val resolved = resolveInstalledQnnRuntimeProfile(root, bundle, preferredHtpArch = 79)

            assertEquals(bundle.requiredRuntimeProfile, resolved.requiredRuntimeProfile)
            assertFalse(requireNotNull(resolved.requiredRuntimeProfile).completeBundleRuntime)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun qnnRuntimeUsesPublicSnapdragonLabel() {
        assertEquals("骁龙 NPU", LocalImageRuntime.QNN_HTP.label)
    }

    @Test
    fun qnnRuntimeSearchDirectoriesAddsCanonicalBundleRuntimeWithoutDuplicates() {
        val root = Files.createTempDirectory("qnn-runtime-search").toFile()
        try {
            val runtime = File(root, "runtime").apply { mkdirs() }

            val directories = qnnRuntimeSearchDirectories(
                bundleRoot = root,
                existingDirectories = listOf(
                    runtime.absolutePath,
                    File(runtime, ".").absolutePath,
                    "/vendor/lib64"
                )
            )

            assertEquals(listOf(runtime.canonicalPath, "/vendor/lib64"), directories)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun qnnRuntimeSearchDirectoriesKeepsExistingPathsWhenBundleHasNoRuntimeDirectory() {
        val root = Files.createTempDirectory("qnn-runtime-search-missing").toFile()
        try {
            val existing = listOf("/vendor/lib64", "/system/lib64")

            assertEquals(existing, qnnRuntimeSearchDirectories(root, existing))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun qnnRuntimeDirectoriesPrioritizeDeviceSelectedCoherentHost() {
        val generic = Files.createTempDirectory("qnn-generic-runtime").toFile()
        val gen2 = Files.createTempDirectory("qnn-gen2-runtime").toFile()
        try {
            val status = QnnRuntimeStatus(
                qnnSystemLibraryPresent = true,
                qnnHtpLibraryPresent = true,
                htpSkelLibraryPresent = true,
                htpStubLibraryPresent = true,
                qnnSystemLibraryPath = File(gen2, "libQnnSystem.so").absolutePath,
                qnnHtpLibraryPath = File(gen2, "libQnnHtp.so").absolutePath,
                htpSkelLibraryPath = File(gen2, "libQnnHtpV73Skel.so").absolutePath,
                htpStubLibraryPath = File(gen2, "libQnnHtpV73Stub.so").absolutePath,
                searchDirectories = listOf(generic.absolutePath, gen2.absolutePath),
                probeState = QnnRuntimeProbeState.LOADABLE
            )

            val directories = qnnRuntimeDirectoriesFor(status)

            assertEquals(gen2.canonicalPath, directories.first())
            assertEquals(1, directories.count { it == gen2.canonicalPath })
            assertTrue(directories.contains(generic.canonicalPath))
        } finally {
            generic.deleteRecursively()
            gen2.deleteRecursively()
        }
    }

    @Test
    fun completeMnnDiffusionStableLayoutCanRunBeforeVerification() {
        val root = Files.createTempDirectory("mnn-diffusion-sd").toFile()
        val primary = root.touch("unet.mnn")
        root.touch("text_encoder.mnn")
        root.touch("text_encoder.mnn.weight")
        root.touch("unet.mnn.weight")
        root.touch("vae_decoder.mnn")
        root.touch("vae_decoder.mnn.weight")
        root.touch("tokenizer.mtok")

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.MNN_DIFFUSION
        )

        assertNull(record.localImageStructuralReadinessMessage())
        assertNull(record.localImageReadinessMessage())
        assertTrue(record.isReadyForLocalImageGeneration())
        assertEquals("未验证·可尝试", record.localImageReadinessLabel())
        assertTrue(record.localImageVerificationDiagnosticMessage()!!.contains("可直接尝试"))
    }

    @Test
    fun unverifiedStableDiffusionCppEngineCanBeManuallySelectedAndRun() {
        val root = Files.createTempDirectory("sdcpp-unverified").toFile()
        try {
            val checkpoint = root.touch("sd_turbo.safetensors")
            val record = localImageRecord(
                root = root,
                primary = checkpoint,
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD_TURBO
            )

            assertNull(record.localImageStructuralReadinessMessage())
            assertTrue(record.isReadyForLocalImageGeneration())
            assertNull(record.localImageReadinessMessage())
            assertEquals("未验证·可尝试", record.localImageReadinessLabel())
            assertTrue(record.localImageVerificationDiagnosticMessage()!!.contains("native 执行"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun passedStableDiffusionCppEngineCanBecomeReadyAfterRealSmoke() {
        val root = Files.createTempDirectory("sdcpp-passed").toFile()
        try {
            val checkpoint = root.touch("sd_turbo.safetensors")
            val record = localImageRecord(
                root = root,
                primary = checkpoint,
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD_TURBO,
                verificationStatus = LocalImageVerificationStatus.PASSED
            )

            assertTrue(record.isReadyForLocalImageGeneration())
            assertEquals("可用", record.localImageReadinessLabel())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedStableDiffusionCppExecutionKeepsRetryPathOpen() {
        val root = Files.createTempDirectory("sdcpp-failed-retry").toFile()
        try {
            val checkpoint = root.touch("sd_turbo.safetensors")
            val record = localImageRecord(
                root = root,
                primary = checkpoint,
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD_TURBO
            ).copy(
                verificationStatus = LocalImageVerificationStatus.FAILED,
                verificationMessage = "native graph execution failed",
                verifiedAt = System.currentTimeMillis()
            )

            assertNull(record.localImageReadinessMessage())
            assertTrue(record.isReadyForLocalImageGeneration())
            assertEquals("上次失败·可重试", record.localImageReadinessLabel())
            assertTrue(record.localImageVerificationDiagnosticMessage()!!.contains("仍可直接重试"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyFailedBitWithoutExecutionEvidenceIsNotReportedAsCurrentFailure() {
        val root = Files.createTempDirectory("sdcpp-legacy-failed").toFile()
        try {
            val checkpoint = root.touch("sd_turbo.safetensors")
            val record = localImageRecord(
                root = root,
                primary = checkpoint,
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD_TURBO
            ).copy(
                verificationStatus = LocalImageVerificationStatus.FAILED,
                verificationMessage = "stale certification failure",
                verifiedAt = 0L
            )

            assertNull(record.localImageReadinessMessage())
            assertTrue(record.isReadyForLocalImageGeneration())
            assertEquals("可直接尝试", record.localImageReadinessLabel())
            assertFalse(record.hasCurrentLocalImageExecutionFailure())
            assertTrue(record.localImageVerificationDiagnosticMessage()!!.contains("没有当前执行证据"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun passedMnnDiffusionRuntimeVerificationIsReady() {
        val root = Files.createTempDirectory("mnn-diffusion-sd-passed").toFile()
        val primary = root.touch("unet.mnn")
        root.touch("text_encoder.mnn")
        root.touch("text_encoder.mnn.weight")
        root.touch("unet.mnn.weight")
        root.touch("vae_decoder.mnn")
        root.touch("vae_decoder.mnn.weight")
        root.touch("tokenizer.mtok")

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.MNN_DIFFUSION
        ).copy(
            verificationStatus = LocalImageVerificationStatus.PASSED,
            verificationMessage = "1-step 通过"
        )

        assertNull(record.localImageReadinessMessage())
    }

    @Test
    fun mnnImageSmokeAllowsManualUseButCannotBecomeAutomaticDefault() {
        val root = Files.createTempDirectory("mnn-diffusion-sd-smoke").toFile()
        val primary = root.touch("unet.mnn")
        root.touch("text_encoder.mnn")
        root.touch("text_encoder.mnn.weight")
        root.touch("unet.mnn.weight")
        root.touch("vae_decoder.mnn")
        root.touch("vae_decoder.mnn.weight")
        root.touch("tokenizer.mtok")

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.MNN_DIFFUSION
        ).copy(
            verificationStatus = LocalImageVerificationStatus.MNN_SMOKE_PASSED,
            verificationMessage = "direct/opencl 512x512 20-step PNG smoke passed"
        )

        assertTrue(record.isReadyForLocalImageGeneration())
        assertEquals("MNN smoke", record.localImageReadinessLabel())
    }

    @Test
    fun failedMnnDiffusionRuntimeVerificationIsDiagnosticAndAllowsRetry() {
        val root = Files.createTempDirectory("mnn-diffusion-runtime-failed").toFile()
        val primary = root.touch("unet.mnn")
        root.touch("text_encoder.mnn")
        root.touch("text_encoder.mnn.weight")
        root.touch("unet.mnn.weight")
        root.touch("vae_decoder.mnn")
        root.touch("vae_decoder.mnn.weight")
        root.touch("tokenizer.mtok")

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.MNN_DIFFUSION
        ).copy(
            verificationStatus = LocalImageVerificationStatus.FAILED,
            verificationMessage = "UNet smoke 未通过",
            verifiedAt = System.currentTimeMillis()
        )

        val diagnostic = record.localImageVerificationDiagnosticMessage()

        assertNull(record.localImageReadinessMessage())
        assertTrue(record.isReadyForLocalImageGeneration())
        assertEquals("上次失败·可重试", record.localImageReadinessLabel())
        assertNotNull(diagnostic)
        assertTrue(diagnostic!!.contains("UNet smoke 未通过"))
        assertTrue(diagnostic.contains("直接重试"))
    }

    @Test
    fun incompleteMnnDiffusionBundleReportsMissingComponents() {
        val root = Files.createTempDirectory("mnn-diffusion-missing").toFile()
        val primary = root.touch("unet.mnn")
        root.touch("text_encoder.mnn")
        root.touch("text_encoder.mnn.weight")
        root.touch("unet.mnn.weight")
        root.touch("tokenizer.txt")

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.MNN_DIFFUSION
        )

        val message = record.localImageReadinessMessage()
        assertNotNull(message)
        assertTrue(message!!.contains("vae_decoder.mnn"))
    }

    @Test
    fun mnnDiffusionBundleCanPrepareTokenizerTxtFromVocabAndMerges() {
        val root = Files.createTempDirectory("mnn-diffusion-tokenizer").toFile()
        val primary = root.touch("unet.mnn")
        root.touch("text_encoder.mnn")
        root.touch("text_encoder.mnn.weight")
        root.touch("unet.mnn.weight")
        root.touch("vae_decoder.mnn")
        root.touch("vae_decoder.mnn.weight")
        File(root, "vocab.json").writeText(
            """{"a":0,"b":1,"ab":2}"""
        )
        File(root, "merges.txt").writeText(
            """
            #version: 0.2
            a b
            """.trimIndent()
        )

        assertTrue(prepareMnnDiffusionTokenizerIfPossible(root))

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.MNN_DIFFUSION
        )

        assertTrue(File(root, "tokenizer.txt").isFile)
        assertNull(record.localImageStructuralReadinessMessage())
        assertNull(record.localImageReadinessMessage())
        assertTrue(record.isReadyForLocalImageGeneration())
    }

    @Test
    fun sanaMnnRequiredComponentsMatchDownloadContract() {
        assertEquals(
            listOf(
                "config.json",
                "llm/config.json",
                "llm/llm_config.json",
                "llm/llm.mnn",
                "llm/llm.mnn.weight",
                "llm/tokenizer.txt",
                "llm/meta_queries.mnn",
                "connector.mnn",
                "connector.mnn.weight",
                "projector.mnn",
                "projector.mnn.weight",
                "transformer.mnn",
                "transformer.mnn.weight",
                "vae_decoder.mnn",
                "vae_decoder.mnn.weight",
                "vae_encoder.mnn",
                "vae_encoder.mnn.weight"
            ),
            LocalImageBundleContract.sanaRequiredComponentPaths
        )
    }

    @Test
    fun imageBundleExposesUnknownSourceIntegrityMetadataWithoutInventingAHash() {
        val bundle = ImageEngineBundleSpec(
            id = "unverified",
            title = "Unverified",
            components = listOf(
                ImageEngineBundleComponentSpec(
                    role = ImageEngineBundleComponentRole.DIFFUSION,
                    repoId = "publisher/model",
                    fileName = "model.gguf"
                )
            )
        )

        assertEquals(ImageEngineIntegrityMetadataStatus.UNKNOWN, bundle.integrityMetadataStatus)
        assertEquals(ImageEngineIntegrityMetadataStatus.UNKNOWN, bundle.components.single().integrityMetadataStatus)
        assertNull(bundle.components.single().sha256)
    }

    @Test
    fun mnnReadinessExplicitlyReportsUnknownPublisherHashFromDownloadAudit() {
        val root = Files.createTempDirectory("mnn-audit-unknown-source").toFile()
        try {
            val primary = root.touch("unet.mnn")
            root.touch("text_encoder.mnn")
            root.touch("text_encoder.mnn.weight")
            root.touch("unet.mnn.weight")
            root.touch("vae_decoder.mnn")
            root.touch("vae_decoder.mnn.weight")
            root.touch("tokenizer.mtok")
            root.writeComponentAudit("unet.mnn", "UNKNOWN")

            val message = localImageRecord(
                root = root,
                primary = primary,
                runtime = LocalImageRuntime.MNN_DIFFUSION
            ).localImageStructuralReadinessMessage()

            assertNotNull(message)
            assertTrue(message!!.contains("Publisher SHA-256 is unavailable"))
            assertTrue(message.contains("unet.mnn"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mnnReadinessRejectsTamperedComponentRecordedInDownloadAudit() {
        val root = Files.createTempDirectory("mnn-audit-tampered").toFile()
        try {
            val primary = root.touch("unet.mnn")
            root.touch("text_encoder.mnn")
            root.touch("text_encoder.mnn.weight")
            root.touch("unet.mnn.weight")
            root.touch("vae_decoder.mnn")
            root.touch("vae_decoder.mnn.weight")
            root.touch("tokenizer.mtok")
            root.writeComponentAudit("unet.mnn", "UNKNOWN")
            primary.writeText("tampered")

            val message = localImageRecord(
                root = root,
                primary = primary,
                runtime = LocalImageRuntime.MNN_DIFFUSION
            ).localImageStructuralReadinessMessage()

            assertNotNull(message)
            assertTrue(message!!.contains("integrity audit failed"))
            assertTrue(message.contains("SIZE_MISMATCH"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completeSanaMnnDiffusionBundleCanRunBeforeVerification() {
        val root = Files.createTempDirectory("mnn-diffusion-sana").toFile()
        val primary = root.createCompleteSanaBundle()

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.MNN_DIFFUSION,
            family = LocalImageModelFamily.SANA
        )

        assertNull(record.localImageStructuralReadinessMessage())
        assertNull(record.localImageReadinessMessage())
        assertTrue(record.isReadyForLocalImageGeneration())
        assertEquals("未验证·可尝试", record.localImageReadinessLabel())
    }

    @Test
    fun sanaManifestFamilyDoesNotCreateAVerificationAdmissionGate() {
        val root = Files.createTempDirectory("mnn-diffusion-sana-manifest").toFile()
        val primary = root.createCompleteSanaBundle()
        File(root, "manifest.json").writeText(
            """
            {
              "runtime": "MNN_DIFFUSION",
              "family": "SANA",
              "smoke": { "width": 512, "height": 512, "steps": 1 }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.MNN_DIFFUSION,
            family = LocalImageModelFamily.SD15
        )

        assertNull(record.localImageStructuralReadinessMessage())
        assertNull(record.localImageReadinessMessage())
        assertTrue(record.isReadyForLocalImageGeneration())
    }

    @Test
    fun sanaMnnReadinessReportsEveryMissingRequiredComponent() {
        LocalImageBundleContract.sanaRequiredComponentPaths.forEach { missingPath ->
            val root = Files.createTempDirectory("mnn-sana-missing").toFile()
            try {
                val primary = root.createCompleteSanaBundle()
                assertTrue(File(root, missingPath).delete())

                val check = LocalImageBundleContract.inspectMnnBundle(
                    bundleRoot = root,
                    primaryFile = primary,
                    family = LocalImageModelFamily.SANA
                )
                assertEquals("unexpected missing set for $missingPath", listOf(missingPath), check.missingComponents)

                val readinessPrimary = if (missingPath == "transformer.mnn") {
                    File(root, "connector.mnn")
                } else {
                    primary
                }
                val message = localImageRecord(
                    root = root,
                    primary = readinessPrimary,
                    runtime = LocalImageRuntime.MNN_DIFFUSION,
                    family = LocalImageModelFamily.SANA
                ).localImageStructuralReadinessMessage()
                assertNotNull("readiness should reject missing $missingPath", message)
                assertTrue("readiness should name $missingPath: $message", message!!.contains(missingPath))
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun sanaMnnReadinessRejectsEveryEmptyRequiredComponent() {
        LocalImageBundleContract.sanaRequiredComponentPaths.forEach { emptyPath ->
            val root = Files.createTempDirectory("mnn-sana-empty").toFile()
            try {
                val primary = root.createCompleteSanaBundle()
                File(root, emptyPath).writeBytes(byteArrayOf())

                val check = LocalImageBundleContract.inspectMnnBundle(
                    bundleRoot = root,
                    primaryFile = primary,
                    family = LocalImageModelFamily.SANA
                )
                assertEquals("empty file should fail for $emptyPath", listOf(emptyPath), check.missingComponents)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun sanaMnnContractResolvesExtractedWrapperDirectory() {
        val outer = Files.createTempDirectory("mnn-sana-wrapper").toFile()
        val bundle = File(outer, "MNN-Sana-Edit-V2").apply { mkdirs() }
        val primary = bundle.createCompleteSanaBundle()

        val check = LocalImageBundleContract.inspectMnnBundle(
            bundleRoot = outer,
            primaryFile = primary,
            family = LocalImageModelFamily.SANA
        )

        assertEquals(bundle.canonicalFile, check.root!!.canonicalFile)
        assertTrue(check.missingComponents.isEmpty())
        assertNull(
            localImageRecord(
                root = outer,
                primary = primary,
                runtime = LocalImageRuntime.MNN_DIFFUSION,
                family = LocalImageModelFamily.SANA
            ).localImageStructuralReadinessMessage()
        )
    }

    @Test
    fun sanaVerificationRouteUsesManifestFamilyAndMinimumTwoSteps() {
        val route = mnnVerificationRoute(
            modelFamily = LocalImageModelFamily.SD15,
            manifest = LocalImageBundleManifest(
                family = LocalImageModelFamily.SANA,
                smokeWidth = 640,
                smokeHeight = 768,
                smokeSteps = 1
            )
        )

        assertEquals(LocalImageModelFamily.SANA, route.family)
        assertEquals(2, route.steps)
        assertEquals(640, route.width)
        assertEquals(768, route.height)
        assertFalse(route.requiresUnetPreflight)
    }

    @Test
    fun sanaVerificationRouteKeepsHigherManifestStepCount() {
        val route = mnnVerificationRoute(
            modelFamily = LocalImageModelFamily.SANA,
            manifest = LocalImageBundleManifest(
                family = LocalImageModelFamily.SANA,
                smokeSteps = 10
            )
        )

        assertEquals(10, route.steps)
        assertFalse(route.requiresUnetPreflight)
    }

    @Test
    fun stableDiffusionMnnVerificationUsesValidatedDirectTwentyStepContract() {
        val route = mnnVerificationRoute(
            modelFamily = LocalImageModelFamily.SD15,
            manifest = LocalImageBundleManifest(
                family = LocalImageModelFamily.SD15,
                smokeWidth = 768,
                smokeHeight = 768,
                smokeSteps = 10
            )
        )

        assertEquals(LocalImageModelFamily.SD15, route.family)
        assertEquals(20, route.steps)
        assertEquals(512, route.width)
        assertEquals(512, route.height)
        assertTrue(route.requiresUnetPreflight)
    }

    @Test
    fun incompleteSanaMnnDiffusionBundleReportsExactMissingComponents() {
        val root = Files.createTempDirectory("mnn-diffusion-sana-missing").toFile()
        val primary = root.touch("transformer.mnn")
        root.touch("connector.mnn")
        root.touch("projector.mnn")
        root.touch("vae_decoder.mnn")
        root.touch("llm/config.json")

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.MNN_DIFFUSION,
            family = LocalImageModelFamily.SANA
        )

        val message = record.localImageStructuralReadinessMessage()
        assertNotNull(message)
        assertTrue(message!!.contains("llm/meta_queries.mnn"))
        assertFalse(message.contains("text_encoder.mnn"))
    }

    @Test
    fun fluxGgufSingleFileStillRequiresCompanionBundle() {
        val root = Files.createTempDirectory("flux-gguf").toFile()
        val primary = root.touch("flux-2-klein-4b-Q4_0.gguf")

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            family = LocalImageModelFamily.FLUX
        )

        assertNotNull(record.localImageReadinessMessage())
    }

    @Test
    fun completeQnnImageBundleKeepsRunPathOpenBeforeSmokeVerification() {
        val root = Files.createTempDirectory("qnn-sd15").toFile()
        val primary = root.touch("sd15_qnn_context.bin")
        root.touch("unet_qnn_context.bin")
        root.touch("vae_decoder_qnn_context.bin")
        root.touch("clip_text_encoder_qnn_context.bin")
        root.touch("tokenizer.json")

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.QNN_HTP
        )

        assertNull(record.localImageStructuralReadinessMessage())
        assertNull(record.localImageReadinessMessage())
        assertTrue(record.isReadyForLocalImageGeneration())
        assertEquals("NPU 待校验", record.localImageReadinessLabel())
    }

    @Test
    fun qnnSmokeEvidenceIsDiagnosticAndDoesNotGateUse() {
        val root = Files.createTempDirectory("qnn-sd15-smoke-passed").toFile()
        val primary = root.touch("sd15_qnn_context.bin")
        root.touch("unet_qnn_context.bin")
        root.touch("vae_decoder_qnn_context.bin")
        root.touch("clip_text_encoder_qnn_context.bin")
        root.touch("tokenizer.json")

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.QNN_HTP,
            verificationStatus = LocalImageVerificationStatus.QNN_SMOKE_PASSED,
            verificationMessage = "QNN smoke passed"
        )

        assertNull(record.localImageReadinessMessage())
        assertTrue(record.isReadyForLocalImageGeneration())
        assertEquals("NPU smoke", record.localImageReadinessLabel())
    }

    @Test
    fun qnnOneStepImageSmokeAllowsManualUseButCannotBecomeAutomaticDefault() {
        val root = Files.createTempDirectory("qnn-sdxl-image-smoke").toFile()
        val primary = root.touch("sdxl_qnn_context.bin")
        root.touch("unet_qnn_context.bin")
        root.touch("vae_decoder_qnn_context.bin")
        root.touch("clip_text_encoder_qnn_context.bin")
        root.touch("tokenizer.json")

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.QNN_HTP,
            family = LocalImageModelFamily.SDXL,
            verificationStatus = LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED,
            verificationMessage = "1-step NPU PNG smoke passed"
        )

        assertTrue(record.isReadyForLocalImageGeneration())
        assertEquals("NPU 1-step smoke", record.localImageReadinessLabel())
    }

    @Test
    fun qnnPipelineProbeEvidenceIsDiagnosticAndDoesNotGateUse() {
        val root = Files.createTempDirectory("qnn-sd15-pipeline-probe").toFile()
        val primary = root.touch("sd15_qnn_context.bin")
        root.touch("unet_qnn_context.bin")
        root.touch("vae_decoder_qnn_context.bin")
        root.touch("clip_text_encoder_qnn_context.bin")
        root.touch("tokenizer.json")

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.QNN_HTP,
            verificationStatus = LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED,
            verificationMessage = "QNN pipeline probe passed"
        )

        assertNull(record.localImageReadinessMessage())
        assertTrue(record.isReadyForLocalImageGeneration())
        assertEquals("NPU probe", record.localImageReadinessLabel())
    }

    @Test
    fun incompleteQnnImageBundleReportsMissingRuntimeComponents() {
        val root = Files.createTempDirectory("qnn-image-missing").toFile()
        val primary = root.touch("sd15_qnn_context.bin")
        root.touch("clip_text_encoder_qnn_context.bin")

        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = LocalImageRuntime.QNN_HTP
        )

        val message = record.localImageReadinessMessage()

        assertNotNull(message)
        assertTrue(message!!.contains("VAE"))
    }

    @Test
    fun qnnManifestSelectsDeclaredDiffusionPrimaryAndSmokeSize() {
        val root = Files.createTempDirectory("qnn-manifest-sd15").toFile()
        val primary = root.touch("diffusion/unet_context.bin")
        root.touch("runtime/libQnnHtp.so")
        root.touch("vae/vae_decoder_context.bin")
        root.touch("text_encoder/clip_context.bin")
        root.touch("tokenizer/tokenizer.json")
        File(root, "manifest.json").writeText(
            """
            {
              "schema": "mca.image_engine.bundle.v1",
              "id": "sd15-qnn-min",
              "title": "SD1.5 QNN Min",
              "runtime": "QNN_HTP",
              "family": "SD15",
              "components": [
                {"role": "DIFFUSION", "path": "diffusion/unet_context.bin"},
                {"role": "VAE", "path": "vae/vae_decoder_context.bin"},
                {"role": "TEXT_ENCODER", "path": "text_encoder/clip_context.bin"},
                {"role": "TOKENIZER", "path": "tokenizer/tokenizer.json"}
              ],
              "smoke": {"width": 384, "height": 384, "steps": 1}
            }
            """.trimIndent()
        )

        val manifest = localImageBundleManifestFromRoot(root)

        assertNotNull(manifest)
        assertEquals("sd15-qnn-min", manifest!!.id)
        assertEquals("SD1.5 QNN Min", manifest.displayName)
        assertEquals(LocalImageRuntime.QNN_HTP, manifest.runtime)
        assertEquals(LocalImageModelFamily.SD15, manifest.family)
        assertEquals("384x384", manifest.imageSize)
        assertEquals(primary.canonicalFile, manifest.primaryFile!!.canonicalFile)
        assertEquals(4, manifest.componentCount)
    }

    @Test
    fun mnnManifestUsesSmokeSpecWithoutRequiringRuntimeCertification() {
        val root = Files.createTempDirectory("mnn-manifest-sd15").toFile()
        val primary = root.touch("unet.mnn")
        root.touch("text_encoder.mnn")
        root.touch("text_encoder.mnn.weight")
        root.touch("unet.mnn.weight")
        root.touch("vae_decoder.mnn")
        root.touch("vae_decoder.mnn.weight")
        root.touch("tokenizer.mtok")
        File(root, "manifest.json").writeText(
            """
            {
              "schema": "mca.image_engine.bundle.v1",
              "runtime": "MNN_DIFFUSION",
              "family": "SD15",
              "components": [
                {"role": "DIFFUSION", "path": "unet.mnn"},
                {"role": "VAE", "path": "vae_decoder.mnn"},
                {"role": "TEXT_ENCODER", "path": "text_encoder.mnn"},
                {"role": "TOKENIZER", "path": "tokenizer.mtok"}
              ],
              "smokeSpec": {"width": 512, "height": 512, "steps": 1}
            }
            """.trimIndent()
        )

        val manifest = localImageBundleManifestFromRoot(root)
        val record = localImageRecord(
            root = root,
            primary = primary,
            runtime = manifest!!.runtime!!,
            family = manifest.family!!
        )

        assertEquals(LocalImageRuntime.MNN_DIFFUSION, manifest.runtime)
        assertEquals("512x512", manifest.imageSize)
        assertNull(record.localImageStructuralReadinessMessage())
        assertNull(record.localImageReadinessMessage())
        assertTrue(record.isReadyForLocalImageGeneration())
    }

    @Test
    fun manifestPrimaryPathCannotEscapeBundleRoot() {
        val root = Files.createTempDirectory("qnn-manifest-unsafe").toFile()
        root.touch("diffusion/unet_context.bin")
        File(root, "manifest.json").writeText(
            """
            {
              "schema": "mca.image_engine.bundle.v1",
              "runtime": "QNN_HTP",
              "family": "SD15",
              "components": [
                {"role": "DIFFUSION", "path": "../outside.bin"}
              ],
              "smoke": {"width": 384, "height": 384}
            }
            """.trimIndent()
        )

        val inspection = inspectLocalImageBundleManifestFromRoot(root)

        assertTrue(inspection is LocalImageBundleManifestInspection.Invalid)
    }

    @Test
    fun downloadedRecommendationManifestCanBeParsedAsBundleManifest() {
        val root = Files.createTempDirectory("downloaded-mnn-manifest").toFile()
        val unet = root.touch("unet.mnn")
        val vae = root.touch("vae_decoder.mnn")
        val textEncoder = root.touch("text_encoder.mnn")
        val tokenizer = root.touch("tokenizer.mtok")
        val bundle = ImageEngineBundleSpec(
            id = "sd15_mnn_bundle",
            title = "MNN SD1.5 384 验证包",
            components = emptyList(),
            runtime = ImageEngineBundleRuntime.MNN_DIFFUSION,
            accelerator = ImageEngineAccelerator.CPU,
            minDeviceTier = ImageEngineMinDeviceTier.ANY,
            smokeSpec = ImageEngineSmokeSpec(width = 384, height = 384, steps = 1, timeoutSeconds = 90)
        )
        val targets = listOf(
            remote("unet.mnn", ImageEngineBundleComponentRole.DIFFUSION) to unet,
            remote("vae_decoder.mnn", ImageEngineBundleComponentRole.VAE) to vae,
            remote("text_encoder.mnn", ImageEngineBundleComponentRole.TEXT_ENCODER) to textEncoder,
            remote("tokenizer.mtok", ImageEngineBundleComponentRole.TOKENIZER) to tokenizer
        )
        File(root, "manifest.json").writeText(
            downloadedImageBundleManifestJson(
                displayName = "MNN SD1.5 384 链路验证包",
                bundle = bundle,
                targets = targets
            ).toString(2),
            Charsets.UTF_8
        )

        val manifest = localImageBundleManifestFromRoot(root)

        assertNotNull(manifest)
        assertEquals("sd15_mnn_bundle", manifest!!.id)
        assertEquals("MNN SD1.5 384 链路验证包", manifest.displayName)
        assertEquals(LocalImageRuntime.MNN_DIFFUSION, manifest.runtime)
        assertEquals(LocalImageModelFamily.SD15, manifest.family)
        assertEquals("384x384", manifest.imageSize)
        assertEquals(unet.canonicalFile, manifest.primaryFile!!.canonicalFile)
        assertEquals(4, manifest.componentCount)
    }

    @Test
    fun downloadedRecommendationManifestPersistsCatalogIdentityAndTask() {
        val root = Files.createTempDirectory("downloaded-image-task-manifest").toFile()
        try {
            val controlNet = root.touch("controlnet.bin")
            val bundle = ImageEngineBundleSpec(
                id = "controlnet_bundle",
                title = "Control image bundle",
                components = emptyList(),
                recommendationId = "controlnet_recommendation",
                task = ImageEngineTask.CONTROL_IMAGE,
                runtime = ImageEngineBundleRuntime.QNN_HTP,
                accelerator = ImageEngineAccelerator.QNN_HTP
            )
            val manifest = downloadedImageBundleManifestJson(
                displayName = bundle.title,
                bundle = bundle,
                targets = listOf(
                    remote("controlnet.bin", ImageEngineBundleComponentRole.DIFFUSION) to controlNet
                )
            )

            assertEquals("controlnet_recommendation", manifest.getString("recommendationId"))
            assertEquals("CONTROL_IMAGE", manifest.getString("task"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun downloadedSdTurboManifestKeepsExplicitFamilyAndCheckpointRole() {
        val root = Files.createTempDirectory("downloaded-sd-turbo-manifest").toFile()
        try {
            val checkpoint = root.touch("checkpoint.safetensors")
            val bundle = ImageEngineBundleSpec(
                id = "sd_turbo_512_direct",
                title = "Stable Diffusion Turbo 512",
                components = emptyList(),
                runtime = ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP,
                accelerator = ImageEngineAccelerator.CPU,
                smokeSpec = ImageEngineSmokeSpec(width = 512, height = 512, steps = 1, timeoutSeconds = 600),
                modelFamily = "SD_TURBO"
            )
            val remote = remote("checkpoint.safetensors", ImageEngineBundleComponentRole.DIFFUSION)
                .copy(relativePath = "checkpoint.safetensors")
            val manifestJson = downloadedImageBundleManifestJson(
                displayName = bundle.title,
                bundle = bundle,
                targets = listOf(remote to checkpoint)
            )
            File(root, "manifest.json").writeText(manifestJson.toString(2), Charsets.UTF_8)

            val manifest = localImageBundleManifestFromRoot(root)

            assertEquals("SD_TURBO", manifestJson.getString("family"))
            assertEquals("DIFFUSION", manifestJson.getJSONArray("components").getJSONObject(0).getString("role"))
            assertNotNull(manifest)
            assertEquals(LocalImageModelFamily.SD_TURBO, manifest!!.family)
            assertEquals(checkpoint.canonicalFile, manifest.primaryFile!!.canonicalFile)
            assertEquals("512x512", manifest.imageSize)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun downloadedQnnZipRecommendationManifestKeepsSmokeSuiteAfterExtraction() {
        val root = Files.createTempDirectory("downloaded-qnn-manifest").toFile()
        val unet = root.touch("unet.bin")
        root.touch("vae_decoder.bin")
        root.touch("clip_v2.mnn")
        root.touch("tokenizer.json")
        root.touch("token_emb.bin")
        root.touch("pos_emb.bin")
        val bundle = ImageEngineBundleSpec(
            id = "cyberrealistic_sd15_qnn228",
            title = "CyberRealistic SD1.5 QNN 2.28",
            components = emptyList(),
            runtime = ImageEngineBundleRuntime.QNN_HTP,
            accelerator = ImageEngineAccelerator.QNN_HTP,
            minDeviceTier = ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN2,
            requiresQnnRuntime = true,
            requiresSmokeTest = true,
            smokeSpec = ImageEngineSmokeSpec(width = 512, height = 512, steps = 4, timeoutSeconds = 240),
            qnnSmokeSpecs = listOf(
                ImageEngineQnnSmokeSpec(
                    contextBinary = "unet.bin",
                    inputs = listOf(
                        ImageEngineQnnSmokeTensorSpec("sample", "uint16", listOf(1, 4, 64, 64)),
                        ImageEngineQnnSmokeTensorSpec("timestamp", "int32", listOf(1)),
                        ImageEngineQnnSmokeTensorSpec("text_embedding", "uint16", listOf(1, 77, 768))
                    ),
                    outputs = listOf(
                        ImageEngineQnnSmokeTensorSpec("output", "uint16", listOf(1, 4, 64, 64), role = "output")
                    )
                ),
                ImageEngineQnnSmokeSpec(
                    contextBinary = "vae_decoder.bin",
                    prompt = "vae decoder smoke",
                    inputs = listOf(
                        ImageEngineQnnSmokeTensorSpec("input", "uint16", listOf(1, 4, 64, 64))
                    ),
                    outputs = listOf(
                        ImageEngineQnnSmokeTensorSpec("output", "uint16", listOf(1, 3, 512, 512), role = "output")
                    )
                )
            )
        )
        val deletedZipTarget = File(root, "cyberrealistic_final_qnn2.28_8gen2.zip")
        val targets = listOf(
            remote("cyberrealistic_final_qnn2.28_8gen2.zip", ImageEngineBundleComponentRole.DIFFUSION) to deletedZipTarget
        )
        File(root, "manifest.json").writeText(
            downloadedImageBundleManifestJson(
                displayName = "CyberRealistic SD1.5 NPU",
                bundle = bundle,
                targets = targets
            ).toString(2),
            Charsets.UTF_8
        )

        val manifest = localImageBundleManifestFromRoot(root)
        val record = localImageRecord(
            root = root,
            primary = unet,
            runtime = LocalImageRuntime.QNN_HTP
        )

        assertNotNull(manifest)
        assertEquals("cyberrealistic_sd15_qnn228", manifest!!.id)
        assertEquals(LocalImageRuntime.QNN_HTP, manifest.runtime)
        assertEquals(ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN2, manifest.minDeviceTier)
        assertEquals(2, manifest.qnnSmokeSpecs.size)
        assertEquals("unet.bin", manifest.qnnSmokeSpec.contextBinary)
        assertTrue(manifest.qnnSmokeSpecs.all { it.completeForGraphSmoke })
        assertNull(manifest.primaryFile)
        assertNull(record.localImageStructuralReadinessMessage())
    }

    @Test
    fun qnnManifestRequiredPathsRejectZeroLengthComponentsInsteadOfSubstringPlaceholders() {
        val root = Files.createTempDirectory("qnn-exact-components").toFile()
        try {
            val unet = root.touch("graphs/unet.bin")
            File(root, "graphs/vae_decoder.bin").apply {
                parentFile?.mkdirs()
                createNewFile()
            }
            root.touch("clip_v2.mnn")
            root.touch("qnn-vae-clip-placeholder.txt")
            File(root, "manifest.json").writeText(
                JSONObject()
                    .put("id", "exact-qnn")
                    .put("runtime", "QNN_HTP")
                    .put(
                        "components",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("role", "DIFFUSION")
                                    .put("path", "graphs/unet.bin")
                                    .put("required", true)
                            )
                            .put(
                                JSONObject()
                                    .put("role", "VAE")
                                    .put("path", "graphs/vae_decoder.bin")
                                    .put("required", true)
                            )
                            .put(
                                JSONObject()
                                    .put("role", "TEXT_ENCODER")
                                    .put("path", "clip_v2.mnn")
                                    .put("required", true)
                            )
                    )
                    .toString(2),
                Charsets.UTF_8
            )
            val record = localImageRecord(root, unet, LocalImageRuntime.QNN_HTP)

            val message = requireNotNull(record.localImageStructuralReadinessMessage())

            assertTrue(message.contains("graphs/vae_decoder.bin"))
            assertFalse(message.contains("device", ignoreCase = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun downloadedQnn228ManifestPersistsRuntimeContractAndBlocksMissingBundleRuntime() {
        val root = Files.createTempDirectory("downloaded-qnn-runtime-contract").toFile()
        val unet = root.touch("unet.bin")
        root.touch("vae_decoder.bin")
        root.touch("clip_v2.mnn")
        root.touch("tokenizer.json")
        val bundle = ImageEngineBundleSpec(
            id = "dreamshaper_sd15_qnn228",
            title = "DreamShaper SD1.5 QNN 2.28",
            components = emptyList(),
            runtime = ImageEngineBundleRuntime.QNN_HTP,
            accelerator = ImageEngineAccelerator.QNN_HTP,
            minDeviceTier = ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN2,
            requiresQnnRuntime = true,
            requiredRuntimeProfile = ImageEngineQnnRuntimeProfileSpec("2.28", 73, true)
        )
        val remote = remote("dreamshaper_8_qnn2.28_8gen2.zip", ImageEngineBundleComponentRole.DIFFUSION)
        val persistedManifest = downloadedImageBundleManifestJson(
            displayName = bundle.title,
            bundle = bundle,
            targets = listOf(remote to File(root, remote.name))
        )
        // Compatibility: early contract writers may omit the boolean because
        // the data-model default is true. Deserialization must retain that
        // fail-closed meaning rather than silently disabling the contract.
        persistedManifest.getJSONObject("requiredRuntimeProfile").remove("completeBundleRuntime")
        File(root, "manifest.json").writeText(persistedManifest.toString(2), Charsets.UTF_8)

        val manifest = localImageBundleManifestFromRoot(root)
        val record = localImageRecord(root, unet, LocalImageRuntime.QNN_HTP)

        assertNotNull(manifest?.requiredRuntimeProfile)
        assertEquals("2.28", manifest!!.requiredRuntimeProfile!!.qnnSdk)
        assertEquals(73, manifest.requiredRuntimeProfile!!.htpArch)
        assertTrue(manifest.requiredRuntimeProfile!!.completeBundleRuntime)
        val readinessMessage = requireNotNull(record.localImageStructuralReadinessMessage())
        assertTrue(readinessMessage.contains("骁龙 8 Gen 2 NPU 运行环境"))
        assertFalse(Regex("""(?i)SM\d{4}|HTP\s*V\d+|soc_model""").containsMatchIn(readinessMessage))
    }

    @Test
    fun downloadedManifestKeepsRemoteRelativeComponentPath() {
        val root = Files.createTempDirectory("downloaded-nested-manifest").toFile()
        val primary = root.touch("diffusion/unet.mnn")
        val bundle = ImageEngineBundleSpec(
            id = "nested-mnn",
            title = "Nested MNN",
            components = emptyList(),
            runtime = ImageEngineBundleRuntime.MNN_DIFFUSION,
            accelerator = ImageEngineAccelerator.CPU
        )
        val remote = remote("unet.mnn", ImageEngineBundleComponentRole.DIFFUSION)
            .copy(relativePath = "diffusion/unet.mnn")

        val manifest = downloadedImageBundleManifestJson(
            displayName = bundle.title,
            bundle = bundle,
            targets = listOf(remote to primary)
        )

        assertEquals("diffusion/unet.mnn", manifest.getJSONArray("components")
            .getJSONObject(0)
            .getString("path"))
    }

    @Test
    fun qnnZipExtractionDoesNotOverwriteExistingMcaManifest() {
        val root = Files.createTempDirectory("qnn-zip-manifest-protection").toFile()
        val manifestFile = File(root, "manifest.json")
        manifestFile.writeText("""{"schema":"mca.image_engine.bundle.v1","id":"mca"}""", Charsets.UTF_8)
        val zipFile = File(root, "qnn.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"schema":"third.party","id":"zip"}""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("diffusion/unet_context.bin"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        extractImageBundleZipIntoDirectory(zipFile, root)

        assertEquals("mca", org.json.JSONObject(manifestFile.readText(Charsets.UTF_8)).getString("id"))
        assertTrue(File(root, "diffusion/unet_context.bin").isFile)
    }

    @Test
    fun downloadedQnnSmokeResolvesTheUniqueNestedContextArtifact() {
        val root = Files.createTempDirectory("nested-qnn-context").toFile()
        val unet = root.touch("output/qnn_models_sdxl_8gen3/unet.bin")
        val vae = root.touch("output/qnn_models_sdxl_8gen3/vae_decoder.bin")
        val targets = listOf(
            remote("unet.bin", ImageEngineBundleComponentRole.DIFFUSION)
                .copy(relativePath = unet.relativeTo(root).invariantSeparatorsPath) to unet,
            remote("vae_decoder.bin", ImageEngineBundleComponentRole.VAE)
                .copy(relativePath = vae.relativeTo(root).invariantSeparatorsPath) to vae
        )

        assertEquals(
            "output/qnn_models_sdxl_8gen3/unet.bin",
            resolvedDownloadedQnnContextPath("unet.bin", targets)
        )
        assertEquals(
            "output/qnn_models_sdxl_8gen3/vae_decoder.bin",
            resolvedDownloadedQnnContextPath("vae_decoder.bin", targets)
        )
        assertEquals("missing.bin", resolvedDownloadedQnnContextPath("missing.bin", targets))
    }

    @Test
    fun sdxlConditioningRootFollowsNestedManifestComponentDirectory() {
        val root = Files.createTempDirectory("nested-sdxl-conditioning").toFile()
        val nested = File(root, "output/qnn_models_sdxl_8gen3").apply { mkdirs() }
        val names = listOf(
            "clip.mnn",
            "clip_2.mnn",
            "clip_2.mnn.weight",
            "tokenizer.json",
            "token_emb.bin",
            "token_emb_2.bin",
            "pos_emb.bin",
            "pos_emb_2.bin"
        )
        names.forEach { name -> nested.touch(name) }
        File(root, "manifest.json").writeText(
            org.json.JSONObject()
                .put(
                    "components",
                    org.json.JSONArray().apply {
                        names.forEach { name ->
                            put(
                                org.json.JSONObject().put(
                                    "path",
                                    "output/qnn_models_sdxl_8gen3/$name"
                                )
                            )
                        }
                    }
                )
                .toString(),
            Charsets.UTF_8
        )

        assertEquals(nested.canonicalFile, resolveSdxlQnnConditioningRoot(root))
    }

    @Test
    fun sdxlConditioningRootKeepsFlatImportedBundlesCompatible() {
        val root = Files.createTempDirectory("flat-sdxl-conditioning").toFile()
        listOf(
            "clip.mnn",
            "clip_2.mnn",
            "clip_2.mnn.weight",
            "tokenizer.json",
            "token_emb.bin",
            "token_emb_2.bin",
            "pos_emb.bin",
            "pos_emb_2.bin"
        ).forEach { name -> root.touch(name) }

        assertEquals(root.canonicalFile, resolveSdxlQnnConditioningRoot(root))
    }

    @Test
    fun qnnNativeTextEncoderAndVaePathsPreserveNestedArchiveLayout() {
        val root = Files.createTempDirectory("gen5-qnn-context-paths").toFile()
        try {
            root.touch("graphs/unet.bin")
            root.touch("graphs/text_encoder.bin")
            root.touch("graphs/vae.bin")

            assertEquals("graphs/unet.bin", qnnFirstContextPath(root, "unet.bin"))
            assertEquals("graphs/text_encoder.bin", qnnNativeTextEncoderContextPath(root))
            assertEquals("graphs/vae.bin", qnnFirstContextPath(root, "vae.bin", "vae_decoder.bin"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun qnnClipTokenizerRootSelectsMtokInsteadOfRawPublisherVocabulary() {
        val root = Files.createTempDirectory("gen5-qnn-tokenizer-root").toFile()
        try {
            root.touch("publisher/tokenizer/vocab.json")
            root.touch("publisher/tokenizer/merges.txt")
            root.touch("prepared/tokenizer.mtok", "mnn-tokenizer")

            assertEquals(File(root, "prepared").canonicalFile, qnnClipTokenizerRoot(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun localImageRecord(
        root: File,
        primary: File,
        runtime: LocalImageRuntime,
        family: LocalImageModelFamily = LocalImageModelFamily.SD15,
        verificationStatus: LocalImageVerificationStatus = LocalImageVerificationStatus.UNKNOWN,
        verificationMessage: String = ""
    ): LocalImageModelRecord =
        LocalImageModelRecord(
            id = root.name,
            displayName = root.name,
            path = primary.absolutePath,
            fileName = primary.name,
            sizeBytes = primary.length(),
            sha256 = "test",
            runtime = runtime,
            family = family,
            bundleRoot = root.absolutePath,
            verificationStatus = verificationStatus,
            verificationMessage = verificationMessage
        )

    private fun File.touch(name: String, contents: String = "x"): File =
        File(this, name).also {
            it.parentFile?.mkdirs()
            it.writeText(contents)
        }

    private fun File.createCompleteSanaBundle(): File {
        LocalImageBundleContract.sanaRequiredComponentPaths.forEach { path -> touch(path) }
        return File(this, "transformer.mnn")
    }

    private fun File.writeComponentAudit(path: String, sourceMetadataStatus: String) {
        val content = File(this, path).readBytes()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(content)
            .joinToString("") { "%02x".format(it) }
        File(this, ".mca-component-audit.json").writeText(
            """
            {
              "schema": "mca.model_bundle.audit.v1",
              "components": [{
                "path": "$path",
                "observedSizeBytes": ${content.size},
                "observedSha256": "$digest",
                "sourceMetadataStatus": "$sourceMetadataStatus"
              }]
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
    }

    private fun remote(
        fileName: String,
        role: ImageEngineBundleComponentRole
    ): RemoteModelFile =
        RemoteModelFile(
            repoId = "MNN/stable-diffusion-v1-5-mnn-opencl",
            revision = "master",
            path = fileName,
            name = fileName,
            downloadUrl = "https://example.invalid/$fileName",
            provider = ModelRepositoryProvider.MODELSCOPE,
            bundleRole = role
        )
}

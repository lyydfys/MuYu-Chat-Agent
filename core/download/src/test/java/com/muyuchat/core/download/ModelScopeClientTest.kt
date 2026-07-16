package com.muyuchat.core.download

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelScopeClientTest {
    private val client = ModelScopeClient()

    @Test
    fun parsesPlainRepoId() {
        assertEquals(
            "lmstudio-community/Qwen3.5-0.8B-GGUF",
            client.parseRepoId("lmstudio-community/Qwen3.5-0.8B-GGUF")
        )
    }

    @Test
    fun stripsSummarySuffixFromModelPageUrl() {
        assertEquals(
            "lmstudio-community/Qwen3.5-0.8B-GGUF",
            client.parseRepoId("https://www.modelscope.cn/models/lmstudio-community/Qwen3.5-0.8B-GGUF/summary")
        )
    }

    @Test
    fun stripsFilesSuffixFromModelPageUrl() {
        assertEquals(
            "lmstudio-community/Qwen3.5-0.8B-GGUF",
            client.parseRepoId("https://modelscope.cn/models/lmstudio-community/Qwen3.5-0.8B-GGUF/files")
        )
    }

    @Test
    fun stripsResolvePathFromDownloadUrl() {
        assertEquals(
            "lmstudio-community/Qwen3.5-0.8B-GGUF",
            client.parseRepoId("https://www.modelscope.cn/models/lmstudio-community/Qwen3.5-0.8B-GGUF/resolve/master/model.gguf")
        )
    }

    @Test
    fun stripsExtraSegmentsFromManualInput() {
        assertEquals(
            "lmstudio-community/Qwen3.5-0.8B-GGUF",
            client.parseRepoId("lmstudio-community/Qwen3.5-0.8B-GGUF/summary")
        )
    }

    @Test
    fun parsesHuggingFaceMirrorRepoUrls() {
        assertEquals(
            "qualcomm/Qwen3-8B",
            client.parseRepoId("https://hf-mirror.com/qualcomm/Qwen3-8B/tree/main")
        )
        assertEquals(
            "qualcomm/Qwen3-4B-Instruct-2507",
            client.parseRepoId("https://hf.co/qualcomm/Qwen3-4B-Instruct-2507/resolve/main/release_assets.json")
        )
    }

    @Test
    fun decodesEncodedRepoIdFromModelPageUrl() {
        assertEquals(
            "owner name/模型 GGUF",
            client.parseRepoId("https://modelscope.cn/models/owner%20name/%E6%A8%A1%E5%9E%8B%20GGUF/files")
        )
    }

    @Test
    fun includesDefaultRecommendedModels() {
        val recommendations = client.recommendedModels()

        assertEquals(35, recommendations.size)
        assertEquals(ModelScopeRecommendedGroup.LIGHT_CHAT, recommendations[0].group)
        val qairtVisionChat = recommendations.first { it.id == "qwen3_vl_4b_qairt_w4a16" }
        assertEquals(RecommendedChatRuntime.GENIEX_QAIRT, qairtVisionChat.chatRuntime)
        assertEquals(ModelRepositoryProvider.HUGGING_FACE, qairtVisionChat.provider)
        assertEquals(ModelScopeRecommendedGroup.MAIN_CHAT, qairtVisionChat.group)
        assertEquals("qualcomm/Qwen3-VL-4B-Instruct", qairtVisionChat.repoId)
        assertEquals(0, qairtVisionChat.priority)
        assertEquals(RecommendedModelStatus.RECOMMENDED, qairtVisionChat.status)
        assertEquals(setOf("SM8750", "SM8750P", "SM8850", "SM8850P"), qairtVisionChat.supportedChipsetCodes)
        assertTrue(qairtVisionChat.tags.contains("QAIRT"))
        val qairtChat = recommendations.first { it.id == "qwen3_4b_2507_qairt_w4a16" }
        assertEquals(RecommendedChatRuntime.GENIEX_QAIRT, qairtChat.chatRuntime)
        assertEquals(ModelRepositoryProvider.HUGGING_FACE, qairtChat.provider)
        assertEquals(ModelScopeRecommendedGroup.MAIN_CHAT, qairtChat.group)
        assertEquals("qualcomm/Qwen3-4B-Instruct-2507", qairtChat.repoId)
        assertTrue(qairtChat.tags.contains("QAIRT"))
        val qairtQualityChat = recommendations.first { it.id == "qwen3_8b_qairt_w4a16" }
        assertEquals(RecommendedChatRuntime.GENIEX_QAIRT, qairtQualityChat.chatRuntime)
        assertEquals(ModelRepositoryProvider.HUGGING_FACE, qairtQualityChat.provider)
        assertEquals(ModelScopeRecommendedGroup.QUALITY_CHAT, qairtQualityChat.group)
        assertEquals("qualcomm/Qwen3-8B", qairtQualityChat.repoId)
        assertEquals(2, qairtQualityChat.priority)
        assertTrue(qairtQualityChat.tags.contains("QAIRT"))
        val qairtHighMemoryVisionChat = recommendations.first { it.id == "qwen25_vl_7b_qairt_w4a16" }
        assertEquals(RecommendedChatRuntime.GENIEX_QAIRT, qairtHighMemoryVisionChat.chatRuntime)
        assertEquals(ModelRepositoryProvider.HUGGING_FACE, qairtHighMemoryVisionChat.provider)
        assertEquals("qualcomm/Qwen2.5-VL-7B-Instruct", qairtHighMemoryVisionChat.repoId)
        assertEquals(3, qairtHighMemoryVisionChat.priority)
        assertEquals(RecommendedModelStatus.EXPERIMENTAL, qairtHighMemoryVisionChat.status)

        val qairtRecommendations = recommendations.filter { it.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT }
        assertEquals(
            listOf(
                "qwen3_vl_4b_qairt_w4a16",
                "qwen3_4b_2507_qairt_w4a16",
                "qwen3_8b_qairt_w4a16",
                "qwen25_vl_7b_qairt_w4a16"
            ),
            qairtRecommendations.sortedBy { it.priority }.map { it.id }
        )
        assertTrue(qairtRecommendations.all { it.provider == ModelRepositoryProvider.HUGGING_FACE })
        assertTrue(qairtRecommendations.all {
            it.supportedChipsetCodes == setOf("SM8750", "SM8750P", "SM8850", "SM8850P")
        })
        assertFalse(qairtRecommendations.any { it.repoId.contains("Qwen3.5") })
        assertFalse(qairtRecommendations.any { it.repoId.contains("Gemma") })
        assertEquals("MNN/Qwen3.5-4B-MNN", recommendations.first { it.id == "qwen35_4b_q4" }.repoId)
        assertEquals("config.json", recommendations.first { it.id == "qwen35_4b_q4" }.recommendedFileName)
        assertEquals(RecommendedChatRuntime.MNN, recommendations.first { it.id == "qwen35_4b_q4" }.chatRuntime)
        assertNotNull(recommendations.first { it.id == "qwen35_4b_q4" }.mnnModelBundle)
        assertEquals(RecommendedChatRuntime.MNN, recommendations.first { it.id == "qwen35_08b_q4" }.chatRuntime)
        val qwen35TwoB = recommendations.first { it.id == "qwen35_2b_q4" }
        assertEquals("MNN/Qwen3.5-2B-MNN", qwen35TwoB.repoId)
        assertEquals("config.json", qwen35TwoB.recommendedFileName)
        assertEquals(RecommendedChatRuntime.MNN, qwen35TwoB.chatRuntime)
        assertEquals(ModelScopeRecommendedGroup.LIGHT_CHAT, qwen35TwoB.group)
        assertEquals(1, qwen35TwoB.priority)
        assertEquals(6, qwen35TwoB.minRamGb)
        assertEquals(RecommendedModelStatus.RECOMMENDED, qwen35TwoB.status)
        assertNotNull(qwen35TwoB.mnnModelBundle)
        assertTrue(qwen35TwoB.mnnModelBundle!!.components.any { it.fileName == "visual.mnn" })
        assertTrue(qwen35TwoB.mnnModelBundle!!.components.any { it.fileName == "visual.mnn.weight" })
        assertEquals(RecommendedChatRuntime.MNN, recommendations.first { it.id == "qwen35_9b_q4" }.chatRuntime)
        assertEquals(RecommendedChatRuntime.MNN, recommendations.first { it.id == "gemma4_e2b_iq4" }.chatRuntime)
        assertEquals(RecommendedChatRuntime.MNN, recommendations.first { it.id == "gemma4_e4b_iq4" }.chatRuntime)
        assertEquals(RecommendedChatRuntime.GGUF, recommendations.first { it.id == "qwen35_35b_a3b_iq2_xxs" }.chatRuntime)
        assertEquals(RecommendedChatRuntime.GGUF, recommendations.first { it.id == "google_gemma4_26b_a4b_iq2_xxs" }.chatRuntime)
        assertEquals("MNN/gemma-4-E2B-it-MNN", recommendations.first { it.id == "gemma4_e2b_iq4" }.repoId)
        assertEquals("MNN/gemma-4-E4B-it-MNN", recommendations.first { it.id == "gemma4_e4b_iq4" }.repoId)
        assertEquals("mudler/Qwen3.6-35B-A3B-Claude-4.7-Opus-Reasoning-Distilled-APEX-MTP-GGUF", recommendations.first { it.id == "qwen35_35b_a3b_iq2_xxs" }.repoId)
        assertEquals("cc768c55deb10d6d08727cf66b856e9950ef0720", recommendations.first { it.id == "qwen35_35b_a3b_iq2_xxs" }.revision)
        assertEquals("bartowski/google_gemma-4-26B-A4B-it-GGUF", recommendations.first { it.id == "google_gemma4_26b_a4b_iq2_xxs" }.repoId)
        assertEquals(
            ModelRepositoryProvider.HUGGING_FACE,
            recommendations.first { it.id == "google_gemma4_26b_a4b_iq2_xxs" }.provider
        )
        assertEquals(RecommendedChatRuntime.GGUF, recommendations.first { it.id == "minicpm_v46_q4" }.chatRuntime)
        assertEquals(RecommendedChatRuntime.GGUF, recommendations.first { it.id == "bitcpm4_cann_1b_tq2" }.chatRuntime)
        assertEquals(RecommendedChatRuntime.GGUF, recommendations.first { it.id == "bitcpm4_cann_3b_tq2" }.chatRuntime)
        assertEquals(RecommendedChatRuntime.GGUF, recommendations.first { it.id == "bitcpm4_cann_8b_tq2" }.chatRuntime)
        assertEquals(RecommendedChatRuntime.GGUF, recommendations.first { it.id == "glm47_flash_tq1" }.chatRuntime)
        assertTrue(recommendations.any { it.group == ModelScopeRecommendedGroup.LOCAL_IMAGE && it.downloadable })
        assertEquals(2, recommendations.count { !it.downloadable })
        assertEquals("qwen-image-2512-Q2_K.gguf", recommendations.first { it.id == "qwen_image_2512_q2" }.recommendedFileName)
        assertEquals("unet.mnn", recommendations.first { it.id == "sd15_mnn_512_quality" }.recommendedFileName)
        assertEquals("sd_turbo.safetensors", recommendations.first { it.id == "sd_turbo_512_experimental" }.recommendedFileName)
        assertEquals("GLM-4.7-Flash-UD-TQ1_0.gguf", recommendations.first { it.id == "glm47_flash_tq1" }.recommendedFileName)
        assertEquals("google_gemma-4-26B-A4B-it-IQ2_XXS.gguf", recommendations.first { it.id == "google_gemma4_26b_a4b_iq2_xxs" }.recommendedFileName)
        assertFalse(
            recommendations.first { it.id == "google_gemma4_26b_a4b_iq2_xxs" }
                .visionModelBundle!!
                .downloadProjectorByDefault
        )
        assertNotNull(
            recommendations.first { it.id == "gemma4_e2b_iq4" }
                .mnnModelBundle!!
                .components
                .firstOrNull { it.role == MnnModelBundleComponentRole.TOKENIZER && it.fileName == "tokenizer.mtok" }
        )
        recommendations.filter { it.chatRuntime == RecommendedChatRuntime.MNN }.forEach { model ->
            val bundle = model.mnnModelBundle!!
            assertFalse("${model.id} must not track a floating MNN branch", model.revision.equals("master", ignoreCase = true))
            assertEquals(model.revision, bundle.revision)
            assertNotNull(
                bundle.requiredComponents
                    .firstOrNull { it.role == MnnModelBundleComponentRole.LLM_CONFIG && it.fileName == "llm_config.json" }
            )
        }
        assertTrue(
            recommendations.filter { it.kind != ModelScopeRecommendedKind.IMAGE }.all {
                it.provider == ModelRepositoryProvider.MODELSCOPE ||
                    it.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT ||
                    it.id == "google_gemma4_26b_a4b_iq2_xxs"
            }
        )
        assertTrue(recommendations.any { it.provider == ModelRepositoryProvider.HUGGING_FACE && it.imageEngineBundle?.accelerator == ImageEngineAccelerator.QNN_HTP })
        assertEquals(ModelScopeRecommendedKind.IMAGE, recommendations.last().kind)
        assertTrue(recommendations.any { it.repoId == "qualcomm/Qwen2.5-VL-7B-Instruct" })
        val visionRecommendation = recommendations.first { it.id == "minicpm_v46_q4" }
        assertEquals(ModelScopeRecommendedKind.CHAT, visionRecommendation.kind)
        assertEquals(ModelScopeRecommendedGroup.MAIN_CHAT, visionRecommendation.group)
        assertNotNull(visionRecommendation.visionModelBundle)
        assertEquals(2, visionRecommendation.visionModelBundle!!.requiredComponents.size)
        assertEquals(VisionModelBundleRuntime.GGUF_MMPROJ, visionRecommendation.visionModelBundle!!.runtime)
        assertEquals(VisionModelAccelerator.CPU, visionRecommendation.visionModelBundle!!.accelerator)
        assertEquals(ImageEngineMinDeviceTier.ANY, visionRecommendation.visionModelBundle!!.minDeviceTier)
        assertTrue(visionRecommendation.visionModelBundle!!.requiresSmokeTest)
        assertEquals(448, visionRecommendation.visionModelBundle!!.smokeSpec.imageWidth)
        assertEquals(448, visionRecommendation.visionModelBundle!!.smokeSpec.imageHeight)
        assertEquals("GGUF + mmproj · CPU", visionRecommendation.visionModelBundle!!.runtimeSummary)
        assertNotNull(
            visionRecommendation.visionModelBundle
                .components
                .firstOrNull { it.role == VisionModelBundleComponentRole.MAIN_MODEL && it.fileName == "MiniCPM-V-4_6-Q4_K_M.gguf" }
        )
        assertNotNull(
            visionRecommendation.visionModelBundle
                .components
                .firstOrNull { it.role == VisionModelBundleComponentRole.PROJECTOR && it.fileName == "mmproj-model-f16.gguf" }
        )
        assertTrue(recommendations.none { it.id == "fastvlm_05b_sm8750_litert_qnn" })
        assertTrue(recommendations.none { it.kind == ModelScopeRecommendedKind.VISION })
        val imageRecommendations = recommendations.filter { it.kind == ModelScopeRecommendedKind.IMAGE }
        assertTrue(imageRecommendations.all { it.imageEngineBundle != null })
        assertTrue(imageRecommendations.all { it.imageEngineBundle!!.requiredComponents.isNotEmpty() })
        assertTrue(imageRecommendations.all { it.localImageEngineTier != null })
        assertTrue(imageRecommendations.all { it.imageEngineBundle!!.requiresSmokeTest })
        assertTrue(imageRecommendations.all { it.imageEngineBundle!!.smokeSpec.width >= 384 })
        assertTrue(imageRecommendations.all { it.imageEngineBundle!!.smokeSpec.height >= 384 })
        assertTrue(imageRecommendations.all { it.imageEngineBundle!!.smokeSpec.steps >= 1 })
        assertTrue(imageRecommendations.all { it.imageEngineBundle!!.accelerator in ImageEngineAccelerator.entries })
        assertTrue(imageRecommendations.all { it.imageEngineBundle!!.runtime in ImageEngineBundleRuntime.entries })
        val cyberRealisticQnn = recommendations.first { it.id == "cyberrealistic_sd15_qnn228" }
        assertEquals(ModelRepositoryProvider.HUGGING_FACE, cyberRealisticQnn.provider)
        assertEquals("cyberrealistic_final_qnn2.28_min.zip", cyberRealisticQnn.recommendedFileName)
        assertEquals(LocalImageEngineTier.QUICK, cyberRealisticQnn.localImageEngineTier)
        assertEquals(ImageEngineBundleRuntime.QNN_HTP, cyberRealisticQnn.imageEngineBundle!!.runtime)
        assertEquals(ImageEngineAccelerator.QNN_HTP, cyberRealisticQnn.imageEngineBundle!!.accelerator)
        assertEquals("骁龙 NPU", ImageEngineBundleRuntime.QNN_HTP.label)
        assertEquals("骁龙 NPU", ImageEngineAccelerator.QNN_HTP.label)
        assertEquals(ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN1, cyberRealisticQnn.imageEngineBundle!!.minDeviceTier)
        assertTrue(cyberRealisticQnn.imageEngineBundle!!.requiresQnnRuntime)
        listOf(
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228"
        ).forEach { id ->
            val profile = recommendations.first { it.id == id }.imageEngineBundle!!.requiredRuntimeProfile
            assertNotNull(profile)
            assertEquals("2.28", profile!!.qnnSdk)
            assertEquals(68, profile.htpArch)
            assertTrue(profile.completeBundleRuntime)
        }
        val cyberProfile = cyberRealisticQnn.imageEngineBundle!!.requiredRuntimeProfile
        assertNotNull(cyberProfile)
        assertEquals("2.28", cyberProfile!!.qnnSdk)
        assertEquals(68, cyberProfile.htpArch)
        assertFalse(cyberProfile.completeBundleRuntime)
        assertNull(
            recommendations.first { it.id == "meinamix_sd15_qnn228" }
                .imageEngineBundle!!
                .requiredRuntimeProfile
        )
        assertEquals(2, cyberRealisticQnn.imageEngineBundle!!.qnnSmokeSpecs.size)
        assertEquals("unet.bin", cyberRealisticQnn.imageEngineBundle!!.qnnSmokeSpecs.first().contextBinary)
        assertNotNull(
            cyberRealisticQnn.imageEngineBundle!!
                .components
                .firstOrNull { it.role == ImageEngineBundleComponentRole.DIFFUSION && it.fileName.endsWith(".zip") }
        )
        assertEquals(
            ImageEngineBundleRuntime.QNN_HTP,
            recommendations.first { it.id == "realisticvisionhyper_sd15_qnn228" }.imageEngineBundle!!.runtime
        )
        assertEquals(
            ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN3,
            recommendations.first { it.id == "cyberrealisticxl_qnn228" }.imageEngineBundle!!.minDeviceTier
        )
        val cyberRealisticXlQnn = recommendations.first { it.id == "cyberrealisticxl_qnn228" }
        assertEquals(RecommendedModelStatus.EXPERIMENTAL, cyberRealisticXlQnn.status)
        assertTrue(cyberRealisticXlQnn.downloadable)
        assertNull(cyberRealisticXlQnn.downloadBlockReason)
        assertTrue(cyberRealisticXlQnn.description.contains("双进程隔离"))
        assertTrue(cyberRealisticXlQnn.description.contains("latent 形状不匹配"))
        assertEquals(2, cyberRealisticXlQnn.imageEngineBundle!!.qnnSmokeSpecs.size)
        assertEquals("unet.bin", cyberRealisticXlQnn.imageEngineBundle!!.qnnSmokeSpecs.first().contextBinary)
        assertEquals(5, cyberRealisticXlQnn.imageEngineBundle!!.qnnSmokeSpecs.first().inputs.size)
        assertNotNull(
            cyberRealisticXlQnn.imageEngineBundle!!
                .qnnSmokeSpecs
                .first()
                .inputs
                .firstOrNull { it.name == "time_ids" && it.shape == listOf(1, 6) }
        )
        assertEquals(
            ImageEngineBundleRuntime.MNN_DIFFUSION,
            recommendations.first { it.id == "sd15_mnn_512_quality" }.imageEngineBundle!!.runtime
        )
        assertEquals(
            ImageEngineAccelerator.CPU,
            recommendations.first { it.id == "sd15_mnn_512_quality" }.imageEngineBundle!!.accelerator
        )
        assertEquals(
            ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP,
            recommendations.first { it.id == "flux2_klein_4b_q4" }.imageEngineBundle!!.runtime
        )
        val sdTurbo = recommendations.single { it.id == "sd_turbo_512_experimental" }
        val sdTurboBundle = requireNotNull(sdTurbo.imageEngineBundle)
        assertEquals("Stable Diffusion Turbo · 512×512", sdTurbo.title)
        assertEquals(RecommendedModelStatus.RECOMMENDED, sdTurbo.status)
        assertTrue(sdTurbo.description.contains("384×384 尚未验证"))
        assertTrue(sdTurbo.downloadable)
        assertEquals(ModelRepositoryProvider.MODELSCOPE, sdTurbo.provider)
        assertEquals(ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP, sdTurboBundle.runtime)
        assertEquals(ImageEngineAccelerator.CPU, sdTurboBundle.accelerator)
        assertEquals(ImageEngineMinDeviceTier.ANY, sdTurboBundle.minDeviceTier)
        assertEquals("SD_TURBO", sdTurboBundle.modelFamily)
        assertEquals(512, sdTurboBundle.smokeSpec.width)
        assertEquals(512, sdTurboBundle.smokeSpec.height)
        assertEquals(1, sdTurboBundle.smokeSpec.steps)
        assertEquals(1, sdTurboBundle.requiredComponents.size)
        val sdTurboCheckpoint = sdTurboBundle.requiredComponents.single()
        assertEquals(ImageEngineBundleComponentRole.DIFFUSION, sdTurboCheckpoint.role)
        assertEquals("AI-ModelScope/sd-turbo", sdTurboCheckpoint.repoId)
        assertEquals("dc8a205ed5961a45a1b99c2913a194e616bd284b", sdTurbo.revision)
        assertEquals(sdTurbo.revision, sdTurboCheckpoint.revision)
        assertEquals("sd_turbo.safetensors", sdTurboCheckpoint.fileName)
        assertEquals("sd_turbo.safetensors", sdTurboCheckpoint.relativePath)
        assertEquals(5_214_561_328L, sdTurboCheckpoint.expectedSizeBytes)
        assertEquals("3f067a1b943cf162f2b8f8588f6cf5824bd5b4c7d1d88d87164b9ca123616549", sdTurboCheckpoint.sha256)
        assertFalse(recommendations.any { it.id.contains("sd_turbo_384", ignoreCase = true) })
        assertEquals(LocalImageEngineTier.HEAVY_EXPERIMENTAL, recommendations.first { it.id == "sd15_mnn_512_quality" }.localImageEngineTier)
        assertEquals("Stable Diffusion 1.5 · MNN 512×512", recommendations.first { it.id == "sd15_mnn_512_quality" }.title)
        assertEquals(RecommendedModelStatus.EXPERIMENTAL, recommendations.first { it.id == "sd15_mnn_512_quality" }.status)
        assertTrue(recommendations.first { it.id == "sd15_mnn_512_quality" }.downloadable)
        assertEquals(20, recommendations.first { it.id == "sd15_mnn_512_quality" }.imageEngineBundle!!.smokeSpec.steps)
        assertTrue(recommendations.first { it.id == "sd15_mnn_512_quality" }.description.contains("direct + OpenCL"))
        assertTrue(recommendations.first { it.id == "sd15_mnn_512_quality" }.description.contains("module"))
        assertNull(recommendations.first { it.id == "sd15_mnn_512_quality" }.downloadBlockReason)
        assertEquals(LocalImageEngineTier.COMPACT_QUALITY, recommendations.first { it.id == "flux2_klein_4b_q4" }.localImageEngineTier)
        assertEquals(LocalImageEngineTier.LARGE_QUALITY, recommendations.first { it.id == "z_image_turbo_q4" }.localImageEngineTier)
        assertEquals(LocalImageEngineTier.HEAVY_EXPERIMENTAL, recommendations.first { it.id == "qwen_image_2512_q2" }.localImageEngineTier)
        assertEquals(LocalImageEngineTier.HEAVY_EXPERIMENTAL, recommendations.first { it.id == "longcat_image_q4" }.localImageEngineTier)
        assertNotNull(
            recommendations.first { it.id == "sd15_mnn_512_quality" }
                .imageEngineBundle!!
                .components
                .firstOrNull { it.role == ImageEngineBundleComponentRole.DIFFUSION && it.fileName == "unet.mnn" }
        )
        assertNotNull(
            recommendations.first { it.id == "sd15_mnn_512_quality" }
                .imageEngineBundle!!
                .components
                .firstOrNull { it.role == ImageEngineBundleComponentRole.TOKENIZER && it.fileName == "vocab.json" }
        )
        assertNotNull(
            recommendations.first { it.id == "flux2_klein_4b_q4" }
                .imageEngineBundle!!
                .components
                .firstOrNull { it.role == ImageEngineBundleComponentRole.TEXT_ENCODER && it.fileName == "Qwen3-4B-Q4_K_M.gguf" }
        )
        assertNotNull(
            recommendations.first { it.id == "qwen_image_2512_q2" }
                .imageEngineBundle!!
                .components
                .firstOrNull { it.role == ImageEngineBundleComponentRole.VAE && it.fileName.endsWith("qwen_image_vae.safetensors") }
        )
    }

    @Test
    fun advertisedChatCapabilitiesExposeVerifiedAndExperimentalStates() {
        val userFacingChat = client.userFacingRecommendedModels()
            .filter { it.kind == ModelScopeRecommendedKind.CHAT }

        assertEquals(13, userFacingChat.size)
        assertTrue(userFacingChat.any { it.status == RecommendedModelStatus.RECOMMENDED })
        assertTrue(userFacingChat.any { it.status == RecommendedModelStatus.EXPERIMENTAL })
        assertTrue(userFacingChat.all { it.downloadable })
        assertTrue(
            userFacingChat.all { model ->
                val matchingChipset = model.supportedChipsetCodes.firstOrNull().orEmpty()
                model.downloadEligibilityFor(matchingChipset).canDownload
            }
        )
    }

    @Test
    fun chipsetPoliciesRemainAdvisoryAndNeverBlockUserDownload() {
        val npuModels = client.userFacingRecommendedModels().filter { model ->
            model.supportedChipsetCodes.isNotEmpty() ||
                model.downloadPolicy == RecommendedModelDownloadPolicy.ANY_SNAPDRAGON
        }

        assertTrue(npuModels.isNotEmpty())
        npuModels.forEach { model ->
            listOf("SM8550", "SM8750", "SM8850", "MT6989", "").forEach { chipset ->
                val eligibility = model.downloadEligibilityFor(
                    deviceChipsetCode = chipset,
                    deviceIsSnapdragon = chipset.startsWith("SM")
                )
                assertTrue("${model.id} must stay downloadable on '$chipset'", eligibility.canDownload)
                assertNull(eligibility.blockedReason)
            }
        }
    }

    @Test
    fun userFacingRecommendationsMatchApprovedFourSectionCatalog() {
        val allRecommendations = client.recommendedModels()
        val recommendations = client.userFacingRecommendedModels()

        assertEquals(29, recommendations.size)
        assertTrue(recommendations.all { it.visibleInRecommendations })

        val cpuChat = recommendations.filter {
            it.kind == ModelScopeRecommendedKind.CHAT &&
                it.chatRuntime != RecommendedChatRuntime.GENIEX_QAIRT &&
                it.visionModelBundle?.accelerator != VisionModelAccelerator.QNN_HTP
        }
        val npuChat = recommendations.filter {
            it.kind == ModelScopeRecommendedKind.CHAT &&
                (it.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT ||
                    it.visionModelBundle?.accelerator == VisionModelAccelerator.QNN_HTP)
        }
        val cpuImage = recommendations.filter {
            it.kind == ModelScopeRecommendedKind.IMAGE &&
                it.imageEngineBundle?.accelerator != ImageEngineAccelerator.QNN_HTP
        }
        val npuImage = recommendations.filter {
            it.kind == ModelScopeRecommendedKind.IMAGE &&
                it.imageEngineBundle?.accelerator == ImageEngineAccelerator.QNN_HTP
        }

        assertEquals(9, cpuChat.size)
        assertEquals(4, npuChat.size)
        assertEquals(5, cpuImage.size)
        assertEquals(11, npuImage.size)

        fun cpuChatIds(group: ModelScopeRecommendedGroup): List<String> = cpuChat
            .filter { it.group == group }
            .sortedBy { it.priority }
            .map { it.id }

        assertEquals(
            listOf("qwen35_08b_q4", "qwen35_2b_q4", "gemma4_e2b_iq4"),
            cpuChatIds(ModelScopeRecommendedGroup.LIGHT_CHAT)
        )
        assertEquals(
            listOf("qwen35_4b_q4", "minicpm_v46_q4", "gemma4_e4b_iq4"),
            cpuChatIds(ModelScopeRecommendedGroup.MAIN_CHAT)
        )
        assertEquals(
            listOf("qwen35_9b_q4", "qwen35_35b_a3b_iq2_xxs", "google_gemma4_26b_a4b_iq2_xxs"),
            cpuChatIds(ModelScopeRecommendedGroup.QUALITY_CHAT)
        )
        assertEquals(
            listOf(
                "qwen3_vl_4b_qairt_w4a16",
                "qwen3_4b_2507_qairt_w4a16",
                "qwen3_8b_qairt_w4a16",
                "qwen25_vl_7b_qairt_w4a16"
            ),
            npuChat.sortedBy { it.priority }.map { it.id }
        )

        assertEquals(
            setOf(
                "sd15_mnn_512_quality",
                "sd_turbo_512_experimental",
                "z_image_turbo_q4",
                "qwen_image_2512_q2",
                "longcat_image_q4"
            ),
            cpuImage.map { it.id }.toSet()
        )
        assertEquals(
            setOf(
                "cyberrealistic_sd15_qnn228",
                "realisticvisionhyper_sd15_qnn228",
                "dreamshaper_sd15_qnn228",
                "meinamix_sd15_qnn228",
                "sdxl_base_qnn228",
                "realismsdxl_dmd2_alt_qnn228",
                "animagine_xl_v4_qnn228",
                "cyberrealisticxl_qnn228",
                "qualcomm_sd15_gen5_qnn",
                "qualcomm_sd21_gen5_qnn",
                "qualcomm_controlnet_canny_gen5_qnn"
            ),
            npuImage.map { it.id }.toSet()
        )

        val hiddenIds = allRecommendations.filterNot { it.visibleInRecommendations }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "bitcpm4_cann_1b_tq2",
                "bitcpm4_cann_3b_tq2",
                "bitcpm4_cann_8b_tq2",
                "glm47_flash_tq1",
                "mnn_sana_edit_v2",
                "flux2_klein_4b_q4"
            ),
            hiddenIds
        )
        assertTrue(recommendations.none { it.id in hiddenIds })
        assertTrue(allRecommendations.none { it.id == "sd15_mnn_384_fast" })

        assertTrue(recommendations.all { it.downloadable })
        val meinaMix = recommendations.single { it.id == "meinamix_sd15_qnn228" }
        assertEquals(RecommendedModelStatus.EXPERIMENTAL, meinaMix.status)
        assertTrue(meinaMix.visibleInRecommendations)
        assertTrue(meinaMix.downloadEligibilityFor("", deviceIsSnapdragon = false).canDownload)
        assertNull(meinaMix.downloadBlockReason)

        val gen5ImageIds = setOf(
            "qualcomm_sd15_gen5_qnn",
            "qualcomm_sd21_gen5_qnn",
            "qualcomm_controlnet_canny_gen5_qnn"
        )
        val gen5Images = npuImage.filter { it.id in gen5ImageIds }
        assertEquals(3, gen5Images.size)
        assertTrue(gen5Images.all { it.status == RecommendedModelStatus.PENDING_INTEGRATION })
        assertTrue(gen5Images.all { it.downloadable })
        assertTrue(gen5Images.all { it.supportedChipsetCodes == setOf("SM8850", "SM8850P") })
        assertEquals(
            listOf(
                "qualcomm_sd15_gen5_qnn",
                "qualcomm_sd21_gen5_qnn",
                "qualcomm_controlnet_canny_gen5_qnn"
            ),
            gen5Images.sortedBy { it.priority }.map { it.id }
        )

        val genericSd15NpuImages = npuImage.filter {
            it.imageEngineBundle?.requiredRuntimeProfile?.htpArch == 68
        }
        assertEquals(
            listOf(
                "cyberrealistic_sd15_qnn228",
                "realisticvisionhyper_sd15_qnn228",
                "dreamshaper_sd15_qnn228"
            ),
            genericSd15NpuImages.sortedBy { it.priority }.map { it.id }
        )
        assertTrue(genericSd15NpuImages.all { it.recommendedFileName.contains("min", ignoreCase = true) })
        assertTrue(genericSd15NpuImages.all { it.downloadPolicy == RecommendedModelDownloadPolicy.ANY_SNAPDRAGON })
    }

    @Test
    fun sanaEditV2ExposesExactModelScopePackageContract() {
        val recommendation = client.recommendedModels().single { it.id == "mnn_sana_edit_v2" }
        val bundle = requireNotNull(recommendation.imageEngineBundle)
        val components = bundle.components

        assertEquals("MNN/MNN-Sana-Edit-V2", recommendation.repoId)
        assertEquals("50adc28b4682161542f893c624048adf6dd027ca", recommendation.revision)
        assertEquals(ModelRepositoryProvider.MODELSCOPE, recommendation.provider)
        assertEquals("transformer.mnn", recommendation.recommendedFileName)
        assertFalse(recommendation.downloadable)
        assertEquals(
            "MCA 安装器已能保留 Sana 必需的 llm/ 子目录，但应用侧尚未接通源图片协议、VAE encoder 调度与完整 edit pipeline；在图像编辑链路和真机 smoke test 完成前不开放一键下载。",
            recommendation.downloadBlockReason
        )
        assertEquals(ImageEngineTask.IMAGE_EDIT, bundle.task)
        assertEquals(ImageEngineBundleRuntime.MNN_DIFFUSION, bundle.runtime)
        assertEquals(ImageEngineAccelerator.CPU, bundle.accelerator)
        assertEquals(512, bundle.smokeSpec.width)
        assertEquals(512, bundle.smokeSpec.height)
        assertEquals(10, bundle.smokeSpec.steps)
        assertEquals("", bundle.smokeSpec.prompt)
        assertEquals(17, components.size)
        assertTrue(components.all { it.repoId == recommendation.repoId })
        assertTrue(components.all { it.revision == recommendation.revision })
        assertTrue(components.all { it.provider == ModelRepositoryProvider.MODELSCOPE })
        assertTrue(components.all { it.required })
        assertEquals(components.map { it.fileName }, components.map { it.relativePath })

        val expectedComponents = listOf(
            "CONFIG|config.json|810|9471a0ffd2ac3afb78d70ec8b9d4fdc4696fcdd905ba2a25f1806c3529bf00f2",
            "CONFIG|llm/config.json|210|c4bd25dbbc950feffccc3b154d634fdfbce96fbed453dd738bda4abfc763b73a",
            "CONFIG|llm/llm_config.json|4638|2e45095efda4d17853d8b565f7f354210d3f14f97ac24b24a87a5ab771f5980a",
            "TEXT_ENCODER|llm/llm.mnn|504504|a3e32dc50e8988e78d416031023345048f4b6cf152db021da6ee1de921d45096",
            "TEXT_ENCODER|llm/llm.mnn.weight|373018866|79db6ac8267ec6a7c9172a363112fa613c0cf17d6f46121d947a75c987ccf49a",
            "TOKENIZER|llm/tokenizer.txt|3193562|80e75c6cbf70c75fdd51ac1cd53505ac127dcb0e21ebe4d19751fa772e2868bd",
            "CONDITIONING|llm/meta_queries.mnn|1048824|5e80d4e591af78cca31b6e4cf4ee4ead410e9d2f64ee34c73cf5b633def16e0c",
            "CONDITIONING|connector.mnn|99096|d72239e1c2626cfb8f349b9d6b8c9d85f1cd9def0063d3ea23695aa6b2ef48fd",
            "CONDITIONING|connector.mnn.weight|76268760|7128351d2de561932741f7f874b116ea3b4e5979296d8adf41472a93fcb889cd",
            "CONDITIONING|projector.mnn|2416|6236a92633bab8ee33416b2f84ec41b934885be652aea7f0412a057de817d4c0",
            "CONDITIONING|projector.mnn.weight|2387206|34b5afdb0c3b1fc815cdee7f3ed293e8d8f1f377328e5785cad2bd1768a843c3",
            "DIFFUSION|transformer.mnn|1454264|092dd75e8b8c12694ffe43476addcbde07fe7227774a1a40b19420f87b217386",
            "DIFFUSION|transformer.mnn.weight|884435680|b3bab45fbabc8dabd05840b52ea3cd9bd3e54dd990e153ff6fbecd8b6c17f331",
            "VAE|vae_decoder.mnn|751784|9fbe51979b27339b7685cf88f1010a0ff3ab7ff1a7d873fba321eea94b762911",
            "VAE|vae_decoder.mnn.weight|162011594|a6ef7a13ba9af29754adf9b97651cb29a7eaee20b716c16dbe079f500d5eddae",
            "VAE|vae_encoder.mnn|761568|06da21081f8ee98792bd1838990068e7284351157cafbfa8793282b611eacb24",
            "VAE|vae_encoder.mnn.weight|155787522|b44ac00f4683697add9578ef4c0f561fb5753fe24a3f4525e7f492028409d05e"
        )
        assertEquals(
            expectedComponents,
            components.map { "${it.role.name}|${it.fileName}|${it.expectedSizeBytes}|${it.sha256}" }
        )
        assertEquals(1_661_731_304L, components.sumOf { requireNotNull(it.expectedSizeBytes) })
        assertTrue(components.all { requireNotNull(it.sha256).matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun existingImageBundlesKeepLegacyBasenameInstallPaths() {
        val recommendations = client.recommendedModels()
        val qwenVae = recommendations
            .single { it.id == "qwen_image_2512_q2" }
            .imageEngineBundle!!
            .components
            .single { it.role == ImageEngineBundleComponentRole.VAE }

        assertEquals("split_files/vae/qwen_image_vae.safetensors", qwenVae.fileName)
        assertEquals("qwen_image_vae.safetensors", qwenVae.relativePath)
    }

    @Test
    fun sanaEditV2BundleResolutionStopsBeforeNetworkAccess() {
        val requestCount = AtomicInteger()
        val networkClient = OkHttpClient.Builder()
            .addInterceptor {
                requestCount.incrementAndGet()
                throw AssertionError("Sana download guard must run before network access")
            }
            .build()
        val guardedClient = ModelScopeClient(client = networkClient)
        val recommendation = guardedClient.recommendedModels().single { it.id == "mnn_sana_edit_v2" }

        val failure = runCatching {
            guardedClient.recommendedImageBundleFiles(recommendation)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            "MCA 安装器已能保留 Sana 必需的 llm/ 子目录，但应用侧尚未接通源图片协议、VAE encoder 调度与完整 edit pipeline；在图像编辑链路和真机 smoke test 完成前不开放一键下载。",
            failure?.message
        )
        assertEquals(0, requestCount.get())
    }

    @Test
    fun recommendationPagePrefersMnnOnlyWhenSameModelBundleExists() {
        val recommendations = client.userFacingRecommendedModels()
        val byId = recommendations.associateBy { it.id }
        val mnnChatIds = listOf(
            "qwen35_08b_q4",
            "qwen35_2b_q4",
            "qwen35_4b_q4",
            "qwen35_9b_q4",
            "gemma4_e2b_iq4",
            "gemma4_e4b_iq4"
        )
        val ggufFallbackIds = listOf(
            "minicpm_v46_q4",
            "qwen35_35b_a3b_iq2_xxs",
            "google_gemma4_26b_a4b_iq2_xxs"
        )

        mnnChatIds.forEach { id ->
            val model = byId.getValue(id)
            assertEquals(RecommendedChatRuntime.MNN, model.chatRuntime)
            assertTrue(model.repoId.startsWith("MNN/"))
            assertEquals("config.json", model.recommendedFileName)
            assertNotNull(model.mnnModelBundle)
        }
        ggufFallbackIds.forEach { id ->
            val model = byId.getValue(id)
            assertEquals(RecommendedChatRuntime.GGUF, model.chatRuntime)
            assertTrue(model.mnnModelBundle == null)
            assertTrue(model.recommendedFileName.endsWith(".gguf"))
        }
    }

    @Test
    fun recommendedChatModelsExposeMnnAsPrimaryAndGgufAsCompatibility() {
        val chatRecommendations = client.userFacingRecommendedModels()
            .filter { it.kind == ModelScopeRecommendedKind.CHAT }
        val mnnRecommendations = chatRecommendations.filter { it.chatRuntime == RecommendedChatRuntime.MNN }
        val ggufRecommendations = chatRecommendations.filter { it.chatRuntime == RecommendedChatRuntime.GGUF }

        assertTrue(mnnRecommendations.isNotEmpty())
        assertTrue(ggufRecommendations.isNotEmpty())
        mnnRecommendations.forEach { model ->
            assertTrue(model.repoId.startsWith("MNN/"))
            assertEquals("config.json", model.recommendedFileName)
            assertEquals("MNN", model.quant)
            val requiredComponents = requireNotNull(model.mnnModelBundle).requiredComponents
            assertTrue(model.tags.contains("MNN"))
            assertNotNull(requiredComponents.firstOrNull {
                it.role == MnnModelBundleComponentRole.CONFIG && it.fileName == "config.json"
            })
            assertNotNull(requiredComponents.firstOrNull {
                it.role == MnnModelBundleComponentRole.LLM_CONFIG && it.fileName == "llm_config.json"
            })
            assertNotNull(requiredComponents.firstOrNull {
                it.role == MnnModelBundleComponentRole.MODEL && it.fileName == "llm.mnn"
            })
            assertNotNull(requiredComponents.firstOrNull {
                it.role == MnnModelBundleComponentRole.WEIGHT && it.fileName == "llm.mnn.weight"
            })
            assertNotNull(requiredComponents.firstOrNull {
                it.role == MnnModelBundleComponentRole.TOKENIZER &&
                    (it.fileName == "tokenizer.txt" || it.fileName == "tokenizer.mtok")
            })
        }
        ggufRecommendations.forEach { model ->
            assertFalse(model.repoId.startsWith("MNN/"))
            assertTrue(model.mnnModelBundle == null)
            assertTrue(model.recommendedFileName.endsWith(".gguf"))
        }
    }

    @Test
    fun gemmaMnnBundlesRequireTheirPleEmbeddingSidecar() {
        val recommendations = client.userFacingRecommendedModels()
        val expectedTextOnlyFiles = listOf(
            "config.json",
            "llm_config.json",
            "llm.mnn",
            "llm.mnn.weight",
            "llm.mnn.json",
            "tokenizer.mtok",
            "ple_embeddings_int4.bin"
        )

        listOf("gemma4_e2b_iq4", "gemma4_e4b_iq4").forEach { id ->
            val bundle = requireNotNull(recommendations.first { it.id == id }.mnnModelBundle)
            assertEquals(MnnModelBundleInstallProfile.TEXT_ONLY, bundle.installProfile)
            assertEquals(expectedTextOnlyFiles, bundle.components.map { it.fileName })
            assertTrue(bundle.components.all { it.required })
            assertTrue(bundle.components.none {
                it.fileName.startsWith("visual.") || it.fileName.startsWith("audio.")
            })
            val component = bundle.requiredComponents
                .firstOrNull { it.fileName == "ple_embeddings_int4.bin" }
            assertNotNull("$id must install Gemma 4 PLE embeddings", component)
            assertEquals(MnnModelBundleComponentRole.WEIGHT, component?.role)
        }
        assertNull(recommendations.first { it.id == "google_gemma4_26b_a4b_iq2_xxs" }.mnnModelBundle)
        assertNotNull(recommendations.first { it.id == "google_gemma4_26b_a4b_iq2_xxs" }.visionModelBundle)
    }

    @Test
    fun gemma4TwentySixBRecommendationResolvesMainModelAndProjectorFromHuggingFace() {
        val requestedUrls = mutableListOf<String>()
        val fileTree = """
            [
              {
                "type": "file",
                "path": "google_gemma-4-26B-A4B-it-IQ2_XXS.gguf",
                "size": 10800000000
              },
              {
                "type": "file",
                "path": "mmproj-google_gemma-4-26B-A4B-it-f16.gguf",
                "size": 900000000
              }
            ]
        """.trimIndent()
        val networkClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestedUrls += request.url.toString()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(fileTree.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val resolvingClient = ModelScopeClient(
            client = networkClient,
            endpoints = listOf("https://modelscope.invalid"),
            huggingFaceEndpoints = listOf("https://hf-mirror.com", "https://huggingface.co")
        )
        val recommendation = resolvingClient.recommendedModels()
            .single { it.id == "google_gemma4_26b_a4b_iq2_xxs" }

        val files = resolvingClient.listRecommendedFiles(recommendation)
        val primaryDownload = resolvingClient.recommendedFile(recommendation)

        assertEquals(2, files.size)
        assertEquals("google_gemma-4-26B-A4B-it-IQ2_XXS.gguf", primaryDownload.name)
        assertEquals(VisionModelBundleComponentRole.MAIN_MODEL, primaryDownload.visionBundleRole)
        assertEquals(
            listOf(VisionModelBundleComponentRole.MAIN_MODEL, VisionModelBundleComponentRole.PROJECTOR),
            files.map { it.visionBundleRole }
        )
        assertTrue(files.all { it.provider == ModelRepositoryProvider.HUGGING_FACE })
        assertTrue(files.all { it.downloadUrl.startsWith("https://hf-mirror.com/bartowski/") })
        assertEquals(2, requestedUrls.size)
        assertTrue(requestedUrls.all {
            it.startsWith(
                "https://hf-mirror.com/api/models/" +
                    "bartowski/google_gemma-4-26B-A4B-it-GGUF/tree/" +
                    "fabed3e586120477355eea23b92644540a79ce2f"
            )
        })
        assertTrue(requestedUrls.none { "modelscope" in it })
    }

    @Test
    fun buildsModelPageUrlForRepoId() {
        assertEquals(
            "https://www.modelscope.cn/models/lmstudio-community/Qwen3.5-2B-GGUF/summary",
            modelScopeModelPageUrl("lmstudio-community/Qwen3.5-2B-GGUF")
        )
        assertEquals(
            "https://hf-mirror.com/qualcomm/Qwen3-8B",
            huggingFaceModelPageUrl("qualcomm/Qwen3-8B")
        )
    }

    @Test
    fun prefersChinaMirrorForHuggingFaceDownloads() {
        assertEquals(
            "https://hf-mirror.com/qualcomm/Qwen3-8B/resolve/main/release_assets.json",
            client.preferredHuggingFaceDownloadUrlForTest(
                "https://huggingface.co/qualcomm/Qwen3-8B/resolve/main/release_assets.json"
            )
        )
        assertEquals(
            "https://hf-mirror.com/qualcomm/Qwen3-8B/resolve/main/model.zip",
            client.preferredHuggingFaceDownloadUrlForTest(
                "https://hf.co/qualcomm/Qwen3-8B/resolve/main/model.zip"
            )
        )
        assertEquals(
            "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/model.zip",
            client.preferredHuggingFaceDownloadUrlForTest(
                "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/model.zip"
            )
        )
    }

    @Test
    fun qairtAssetSelectionPrefersExactAndFallsBackDeterministically() {
        val releaseAssets = """
            {
              "precisions": {
                "w4a16": {
                  "chipset_assets": {
                    "qualcomm-snapdragon-8-elite": {
                      "geniex_qairt": {"download_url": "https://huggingface.co/owner/model/elite.zip"}
                    },
                    "qualcomm-snapdragon-8-elite-gen5": {
                      "geniex_qairt": {"download_url": "https://huggingface.co/owner/model/gen5.zip"}
                    }
                  }
                }
              }
            }
        """.trimIndent()

        assertEquals(
            "qualcomm-snapdragon-8-elite",
            client.selectedQairtChipsetForTest(releaseAssets, listOf("qualcomm-snapdragon-8-elite"))
        )
        assertEquals(
            "qualcomm-snapdragon-8-elite-gen5",
            client.selectedQairtChipsetForTest(releaseAssets, listOf("qualcomm-snapdragon-8-elite-gen5"))
        )
        assertEquals(
            "qualcomm-snapdragon-8-elite",
            client.selectedQairtChipsetForTest(releaseAssets, emptyList())
        )
        assertEquals(
            "qualcomm-snapdragon-8-elite",
            client.selectedQairtChipsetForTest(releaseAssets, listOf("qualcomm-qcs9075"))
        )
    }

    @Test
    fun qnnImageAssetSelectionPrefersTheExactGen5ForGalaxyPackage() {
        val releaseAssets = """
            {
              "precisions": {
                "w8a16": {
                  "chipset_assets": {
                    "qualcomm-snapdragon-8-elite-for-galaxy": {
                      "qnn_context_binary": {"download_url": "https://example.com/elite.zip"}
                    },
                    "qualcomm-snapdragon-8-elite-gen5-for-galaxy": {
                      "qnn_context_binary": {"download_url": "https://example.com/gen5.zip"}
                    }
                  }
                }
              }
            }
        """.trimIndent()

        assertEquals(
            "qualcomm-snapdragon-8-elite-gen5-for-galaxy",
            client.selectedQnnImageChipsetForTest(
                releaseAssets,
                listOf("qualcomm-snapdragon-8-elite-gen5")
            )
        )
        assertEquals(
            "qualcomm-snapdragon-8-elite-for-galaxy",
            client.selectedQnnImageChipsetForTest(
                releaseAssets,
                listOf("qualcomm-snapdragon-8gen3")
            )
        )
    }

    @Test
    fun huggingFaceRecommendationsOpenOnChinaMirror() {
        val hfRecommendations = client.recommendedModels()
            .filter { it.provider == ModelRepositoryProvider.HUGGING_FACE }

        assertTrue(hfRecommendations.isNotEmpty())
        assertTrue(hfRecommendations.all { it.modelPageUrl.startsWith("https://hf-mirror.com/") })
    }

    @Test
    fun userFacingRecommendationsUseFormalReleaseNames() {
        val recommendations = client.userFacingRecommendedModels()
        val informalMarkers = listOf("实验版", "观察版", "备用实验", "链路验证包", "官方实验包", " NPU")

        assertTrue(recommendations.all { model -> informalMarkers.none(model.title::contains) })
        assertEquals("Qwen3-VL-4B-Instruct", recommendations.single { it.id == "qwen3_vl_4b_qairt_w4a16" }.title)
        assertEquals("Qwen3-4B-Instruct-2507", recommendations.single { it.id == "qwen3_4b_2507_qairt_w4a16" }.title)
        assertEquals("Qwen2.5-VL-7B-Instruct", recommendations.single { it.id == "qwen25_vl_7b_qairt_w4a16" }.title)
        assertEquals(
            "Qualcomm Stable Diffusion 1.5 · 骁龙 8 Elite Gen 5",
            recommendations.single { it.id == "qualcomm_sd15_gen5_qnn" }.title
        )
    }

    @Test
    fun huggingFaceFileListDownloadsPreferChinaMirror() {
        val files = client.parseGgufFilesForTest(
            repoId = "owner/model",
            revision = "main",
            endpoint = "https://huggingface.co",
            provider = ModelRepositoryProvider.HUGGING_FACE,
            body = """
                [
                  {
                    "path": "model.gguf",
                    "download_url": "https://huggingface.co/owner/model/resolve/main/model.gguf"
                  },
                  {
                    "path": "adapter.gguf",
                    "url": "https://hf.co/owner/model/resolve/main/adapter.gguf"
                  },
                  {
                    "path": "nested/generated.gguf",
                    "size": 123
                  }
                ]
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://hf-mirror.com/owner/model/resolve/main/model.gguf",
                "https://hf-mirror.com/owner/model/resolve/main/adapter.gguf",
                "https://hf-mirror.com/owner/model/resolve/main/nested/generated.gguf?download=true"
            ),
            files.map { it.downloadUrl }
        )
    }

    @Test
    fun classifiesAuxiliaryGgufFiles() {
        assertTrue(remote("Qwen3.5-4B-Q4_K_M.gguf").isChatModelCandidate())
        assertEquals(RemoteModelFileKind.IMAGE_MODEL, remote("z_image_turbo-Q4_K.gguf").fileKind())
        assertEquals(RemoteModelFileKind.IMAGE_MODEL, remote("qwen-image-Q4_K_M.gguf").fileKind())
        assertEquals(RemoteModelFileKind.IMAGE_MODEL, remote("LongCat-Image-Q4_0.gguf").fileKind())
        assertEquals(RemoteModelFileKind.IMAGE_MODEL, remote("GLM-Image-Q4_0.gguf").fileKind())
        assertEquals(RemoteModelFileKind.PROJECTOR, remote("mmproj-Qwen3.5-4B-BF16.gguf").fileKind())
        assertEquals(
            RemoteModelFileKind.PROJECTOR,
            remote("visual-adapter.gguf")
                .copy(visionBundleRole = VisionModelBundleComponentRole.PROJECTOR)
                .fileKind()
        )
        assertEquals(
            RemoteModelFileKind.VISION_MODEL,
            remote("FastVLM-0.5B.qualcomm.sm8750.litertlm")
                .copy(visionBundleRole = VisionModelBundleComponentRole.MAIN_MODEL)
                .fileKind()
        )
        assertEquals(RemoteModelFileKind.IMATRIX, remote("imatrix_unsloth.gguf").fileKind())
        assertEquals(RemoteModelFileKind.SPECULATIVE, remote("mtp-Qwen3.6-35B-A3B-Q4_0.gguf").fileKind())
        assertEquals(RemoteModelFileKind.SPLIT_PART, remote("Qwen3.6-27B-BF16-00001-of-00002.gguf").fileKind())
        assertEquals(
            RemoteModelFileKind.MNN_COMPONENT,
            remote("llm.mnn.weight")
                .copy(mnnBundleRole = MnnModelBundleComponentRole.WEIGHT)
                .fileKind()
        )
        assertEquals(
            RemoteModelFileKind.MNN_COMPONENT,
            remote("config.json")
                .copy(bundleRole = ImageEngineBundleComponentRole.CONFIG)
                .fileKind()
        )
        assertEquals(
            RemoteModelFileKind.MNN_COMPONENT,
            remote("connector.mnn")
                .copy(bundleRole = ImageEngineBundleComponentRole.CONDITIONING)
                .fileKind()
        )
    }

    @Test
    fun parsesNestedGgufFileListShapes() {
        val files = client.parseGgufFilesForTest(
            repoId = "owner/model",
            revision = "main",
            endpoint = "https://modelscope.cn",
            body = """
                {
                  "Data": {
                    "Files": [
                      {"Path": "README.md", "Size": 10},
                      {"Path": "sub/Qwen3.5-4B-Q4_K_M.gguf", "Size": 2707000000, "Sha256": "abc"},
                      {"Name": "mmproj-Qwen3.5.gguf", "DownloadUrl": "https://cdn.example/mmproj.gguf"}
                    ]
                  }
                }
            """.trimIndent()
        )

        assertEquals(2, files.size)
        assertEquals("sub/Qwen3.5-4B-Q4_K_M.gguf", files[0].path)
        assertEquals("abc", files[0].sha256)
        assertEquals("https://modelscope.cn/models/owner/model/resolve/main/sub/Qwen3.5-4B-Q4_K_M.gguf", files[0].downloadUrl)
        assertEquals("https://cdn.example/mmproj.gguf", files[1].downloadUrl)
    }

    @Test
    fun parsesAlternateSearchResponseShapes() {
        val result = client.parseModelSearchResultForTest(
            query = "qwen gguf",
            body = """
                {
                  "Data": {
                    "Items": [
                      {
                        "model_id": "owner/Qwen3.5-4B-GGUF",
                        "model_name": "Qwen3.5 4B",
                        "download_count": 12,
                        "like_count": 3,
                        "License": "apache-2.0",
                        "Tags": [{"name": "gguf"}, {"name": "q4"}]
                      }
                    ],
                    "page_number": 2,
                    "page_size": 20,
                    "total_count": 99
                  }
                }
            """.trimIndent()
        )

        assertEquals(1, result.models.size)
        assertEquals("owner/Qwen3.5-4B-GGUF", result.models.single().id)
        assertEquals("Qwen3.5 4B", result.models.single().displayName)
        assertEquals(12, result.models.single().downloads)
        assertEquals(listOf("gguf", "q4"), result.models.single().tags)
    }

    @Test
    fun parsesLowercaseSearchResponseShape() {
        val result = client.parseModelSearchResultForTest(
            query = "gemma gguf",
            body = """
                {
                  "data": {
                    "models": [
                      {
                        "id": "google/gemma-4-E2B-it-GGUF",
                        "displayName": "Gemma 4 E2B GGUF",
                        "downloads": 1200,
                        "likes": 88,
                        "license": "gemma",
                        "tasks": ["text-generation"],
                        "tags": ["gguf", "q4_k_m"]
                      }
                    ]
                  }
                }
            """.trimIndent()
        )

        assertEquals("google/gemma-4-E2B-it-GGUF", result.models.single().id)
        assertEquals("Gemma 4 E2B GGUF", result.models.single().displayName)
        assertEquals(1200, result.models.single().downloads)
        assertEquals(listOf("text-generation"), result.models.single().tasks)
    }

    private fun remote(name: String): RemoteModelFile = RemoteModelFile(
        repoId = "owner/model",
        revision = "master",
        path = name,
        name = name,
        downloadUrl = "https://example.com/$name"
    )
}

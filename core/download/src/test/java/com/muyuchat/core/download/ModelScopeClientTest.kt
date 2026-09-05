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
    private val conditionalOnlyImageIds = setOf(
        "realismsdxl_dmd2_alt_qnn228",
        "sd_turbo_512_experimental",
        "z_image_turbo_q4",
        "flux2_klein_4b_q4"
    )

    @Test
    fun ignoresGitBlobSha1WhenAComponentRequiresSha256() {
        assertNull(normalizedRemoteSha256OrNull("82d05b0e688d7ea94675678646c427907419346e"))
        assertEquals(
            "699cce92eb7c122e2eb7dfdea78e6187fda76a5ed4a8e42319b85610e620e091",
            normalizedRemoteSha256OrNull(
                "699cce92eb7c122e2eb7dfdea78e6187fda76a5ed4a8e42319b85610e620e091"
            )
        )
    }

    @Test
    fun stableDiffusionCppCfgBranchTreatsOnlyScaleOneAsConditionalOnly() {
        assertTrue(stableDiffusionCppUsesCfg(0.0))
        assertFalse(stableDiffusionCppUsesCfg(1.0))
        assertTrue(stableDiffusionCppUsesCfg(1.0 + 1e-9))
        assertFalse(stableDiffusionCppUsesCfg(1.0 + 1e-13))
        assertTrue(stableDiffusionCppUsesCfg(7.0))
    }

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

        assertEquals(48, recommendations.size)
        assertEquals(ModelScopeRecommendedGroup.LIGHT_CHAT, recommendations[0].group)
        val qwen35ExperimentalMnn = recommendations.first { it.id == "qwen35_08b_uncensored_mnn" }
        assertEquals(RecommendedChatRuntime.MNN, qwen35ExperimentalMnn.chatRuntime)
        assertEquals(ModelRepositoryProvider.HUGGING_FACE, qwen35ExperimentalMnn.provider)
        assertEquals(RecommendedModelStatus.EXPERIMENTAL, qwen35ExperimentalMnn.status)
        assertEquals("config.json", qwen35ExperimentalMnn.recommendedFileName)
        assertNotNull(qwen35ExperimentalMnn.mnnModelBundle)
        val gemmaE2bGpu = recommendations.first { it.id == "gemma4_e2b_litertlm_gpu" }
        assertEquals(RecommendedChatRuntime.LITERT_LM, gemmaE2bGpu.chatRuntime)
        assertEquals(RecommendedComputeBackend.GPU, gemmaE2bGpu.computeBackend)
        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", gemmaE2bGpu.repoId)
        assertEquals(ModelRepositoryProvider.MODELSCOPE, gemmaE2bGpu.provider)
        assertEquals(RecommendedModelStatus.EXPERIMENTAL, gemmaE2bGpu.status)
        assertEquals("gemma-4-E2B-it-gpu.litertlm", gemmaE2bGpu.recommendedFileName)
        assertEquals(
            "https://www.modelscope.cn/models/litert-community/gemma-4-E2B-it-litert-lm/summary",
            gemmaE2bGpu.modelPageUrl
        )
        val gemmaE4bGpu = recommendations.first { it.id == "gemma4_e4b_litertlm_gpu" }
        assertEquals(RecommendedComputeBackend.GPU, gemmaE4bGpu.computeBackend)
        assertEquals("litert-community/gemma-4-E4B-it-litert-lm", gemmaE4bGpu.repoId)
        assertEquals(ModelRepositoryProvider.MODELSCOPE, gemmaE4bGpu.provider)
        assertEquals("gemma-4-E4B-it-gpu.litertlm", gemmaE4bGpu.recommendedFileName)
        assertFalse(gemmaE4bGpu.tags.contains("Abliterated"))
        val gemmaE2bNpu = recommendations.first { it.id == "gemma4_e2b_litertlm_npu" }
        assertEquals(RecommendedComputeBackend.NPU, gemmaE2bNpu.computeBackend)
        assertEquals(ModelRepositoryProvider.MODELSCOPE, gemmaE2bNpu.provider)
        assertEquals("gemma-4-E2B-it_qualcomm_sm8750.litertlm", gemmaE2bNpu.recommendedFileName)
        assertEquals(setOf("SM8750", "SM8750P"), gemmaE2bNpu.supportedChipsetCodes)
        assertTrue(gemmaE2bNpu.downloadable)
        val gemma12bNpu = recommendations.first { it.id == "gemma4_12b_litertlm_npu" }
        assertEquals(ModelRepositoryProvider.MODELSCOPE, gemma12bNpu.provider)
        assertEquals(RecommendedChatRuntime.LITERT_LM, gemma12bNpu.chatRuntime)
        assertEquals(RecommendedComputeBackend.NPU, gemma12bNpu.computeBackend)
        assertEquals("", gemma12bNpu.recommendedFileName)
        assertFalse(gemma12bNpu.downloadable)
        assertTrue(gemma12bNpu.downloadBlockReason.orEmpty().contains("没有可确认"))
        listOf("gemma4_12b_litertlm_cpu", "gemma4_12b_litertlm_gpu", "gemma4_12b_litertlm_npu")
            .forEach { id -> assertEquals("master", recommendations.first { it.id == id }.revision) }
        val gemma12bGpu = recommendations.first { it.id == "gemma4_12b_litertlm_gpu" }
        assertEquals(RecommendedComputeBackend.GPU, gemma12bGpu.computeBackend)
        assertEquals("gemma-4-12B-it-gpu.litertlm", gemma12bGpu.recommendedFileName)
        val gemmaE4bNpu = recommendations.first { it.id == "gemma4_e4b_litertlm_npu" }
        assertEquals(RecommendedComputeBackend.NPU, gemmaE4bNpu.computeBackend)
        assertEquals("qualcomm/Gemma-4-E4B-it", gemmaE4bNpu.repoId)
        assertEquals("https://hf-mirror.com/qualcomm/Gemma-4-E4B-it", gemmaE4bNpu.modelPageUrl)
        assertEquals("", gemmaE4bNpu.recommendedFileName)
        assertFalse(gemmaE4bNpu.downloadable)
        assertTrue(gemmaE4bNpu.downloadBlockReason.orEmpty().contains("没有可确认"))
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
        val qwen35FourB = recommendations.first { it.id == "qwen35_4b_uncensored_mnn" }
        assertEquals("darkmaniac7/Qwen3.5-4B-uncensored-MNN", qwen35FourB.repoId)
        assertEquals("config.json", qwen35FourB.recommendedFileName)
        assertEquals(RecommendedChatRuntime.MNN, qwen35FourB.chatRuntime)
        assertNotNull(qwen35FourB.mnnModelBundle)
        assertEquals(RecommendedChatRuntime.MNN, qwen35ExperimentalMnn.chatRuntime)
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
        assertEquals(
            RecommendedChatRuntime.MNN,
            recommendations.first { it.id == "qwen35_9b_uncensored_mnn" }.chatRuntime
        )
        assertEquals(RecommendedChatRuntime.MNN, recommendations.first { it.id == "gemma4_e2b_iq4" }.chatRuntime)
        assertEquals(RecommendedChatRuntime.MNN, recommendations.first { it.id == "gemma4_e4b_iq4" }.chatRuntime)
        val qwen36Mtp = recommendations.first { it.id == "qwen35_35b_a3b_iq2_xxs" }
        assertEquals(RecommendedChatRuntime.GGUF, qwen36Mtp.chatRuntime)
        assertTrue(qwen36Mtp.visibleInRecommendations)
        assertEquals(RecommendedModelDownloadPolicy.ALL_DEVICES, qwen36Mtp.downloadPolicy)
        assertEquals(RecommendedChatRuntime.GGUF, recommendations.first { it.id == "google_gemma4_26b_a4b_iq2_xxs" }.chatRuntime)
        assertEquals("MNN/gemma-4-E2B-it-MNN", recommendations.first { it.id == "gemma4_e2b_iq4" }.repoId)
        assertEquals("MNN/gemma-4-E4B-it-MNN", recommendations.first { it.id == "gemma4_e4b_iq4" }.repoId)
        assertEquals("mudler/Qwen3.6-35B-A3B-Claude-4.7-Opus-Reasoning-Distilled-APEX-MTP-GGUF", qwen36Mtp.repoId)
        assertEquals("cc768c55deb10d6d08727cf66b856e9950ef0720", qwen36Mtp.revision)
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
        assertEquals(
            setOf("gemma4_e4b_litertlm_npu", "gemma4_12b_litertlm_npu"),
            recommendations.filterNot { it.downloadable }.map { it.id }.toSet()
        )
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
                it.provider in setOf(ModelRepositoryProvider.MODELSCOPE, ModelRepositoryProvider.HUGGING_FACE)
            }
        )
        assertTrue(recommendations.any { it.provider == ModelRepositoryProvider.HUGGING_FACE && it.imageEngineBundle?.accelerator == ImageEngineAccelerator.QNN_HTP })
        assertEquals(ModelScopeRecommendedKind.IMAGE, recommendations.last().kind)
        assertTrue(recommendations.any { it.repoId == "qualcomm/Qwen2.5-VL-7B-Instruct" })
        val visionRecommendation = recommendations.first { it.id == "qwen35_2b_abliterated_gguf" }
        assertEquals(ModelScopeRecommendedKind.CHAT, visionRecommendation.kind)
        assertEquals(ModelScopeRecommendedGroup.LIGHT_CHAT, visionRecommendation.group)
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
                .firstOrNull {
                    it.role == VisionModelBundleComponentRole.MAIN_MODEL &&
                        it.fileName == "Huihui-Qwen3.5-2B-abliterated.Q4_K_M.gguf"
                }
        )
        assertNotNull(
            visionRecommendation.visionModelBundle
                .components
                .firstOrNull {
                    it.role == VisionModelBundleComponentRole.PROJECTOR &&
                        it.fileName == "Huihui-Qwen3.5-2B-abliterated.mmproj-f16.gguf"
                }
        )
        assertTrue(recommendations.none { it.id == "fastvlm_05b_sm8750_litert_qnn" })
        assertTrue(recommendations.none { it.kind == ModelScopeRecommendedKind.VISION })
        val imageRecommendations = recommendations.filter { it.kind == ModelScopeRecommendedKind.IMAGE }
        assertTrue(imageRecommendations.all { it.visibleInRecommendations })
        assertTrue(imageRecommendations.all { it.downloadable })
        assertTrue(imageRecommendations.none { it.status == RecommendedModelStatus.PENDING_INTEGRATION })
        assertTrue(imageRecommendations.none { "待接入" in it.description })
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
        val qnn228Sd15Profiles = listOf(
            "cyberrealistic_sd15_qnn228",
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228",
            "meinamix_sd15_qnn228"
        ).associateWith { id ->
            requireNotNull(recommendations.first { it.id == id }.imageEngineBundle?.executionProfile)
        }
        val expectedQnn228Sd15ProfileRevisions = mapOf(
            "cyberrealistic_sd15_qnn228" to 5,
            "realisticvisionhyper_sd15_qnn228" to 6,
            "dreamshaper_sd15_qnn228" to 6,
            "meinamix_sd15_qnn228" to 5
        )
        qnn228Sd15Profiles.forEach { (id, profile) ->
            assertEquals(
                "$id execution-profile revision drifted",
                expectedQnn228Sd15ProfileRevisions.getValue(id),
                profile.profileRevision
            )
            assertEquals("clip_v2.mnn", profile.graph.textEncoder)
            assertEquals(ImageEngineWorkerStrategy.SHARED_UNET_VAE, profile.graph.workerStrategy)
            assertTrue(profile.tokenizer.supportsTextualInversion)
            assertTrue(profile.capabilities.supportsTextualInversion)
            assertTrue(profile.capabilities.supportsLivePreview)
            assertFalse(profile.capabilities.supportsVaeTiling)
            assertTrue(profile.capabilities.supportsUltraFix)
            assertEquals(512, profile.capabilities.ultraFixMinWidth)
            assertEquals(2_048, profile.capabilities.ultraFixMaxWidth)
            assertEquals(64, profile.capabilities.ultraFixWidthMultiple)
            assertEquals(512, profile.capabilities.ultraFixRequiredTileSize)
            assertNull(profile.graph.schedulerSidecar)
            assertNull(profile.graph.tokenizerSidecar)
            assertEquals(
                listOf("tokenizer.json", "token_emb.bin", "pos_emb.bin"),
                profile.graph.configSidecars
            )
        }
        val sharedGen5Profiles = listOf(
            "qualcomm_sd15_gen5_qnn",
            "qualcomm_sd21_gen5_qnn",
            "qualcomm_controlnet_canny_gen5_qnn"
        ).map { id ->
            requireNotNull(recommendations.first { it.id == id }.imageEngineBundle?.executionProfile)
        }
        sharedGen5Profiles.forEach { profile ->
            assertEquals(2, profile.profileRevision)
            assertEquals(ImageEngineWorkerStrategy.SHARED_TEXT_UNET_VAE, profile.graph.workerStrategy)
            assertFalse(profile.tokenizer.supportsTextualInversion)
            assertFalse(profile.capabilities.supportsTextualInversion)
            assertTrue(profile.capabilities.supportsLivePreview)
            assertFalse(profile.capabilities.supportsUltraFix)
        }
        listOf(
            "sdxl_base_qnn228",
            "realismsdxl_dmd2_alt_qnn228",
            "animagine_xl_v4_qnn228",
            "cyberrealisticxl_qnn228"
        ).forEach { id ->
            val profile = requireNotNull(
                recommendations.first { it.id == id }.imageEngineBundle?.executionProfile
            )
            assertEquals(6, profile.profileRevision)
            assertEquals(ImageEngineWorkerStrategy.SPLIT_UNET_VAE, profile.graph.workerStrategy)
            assertTrue(profile.tokenizer.supportsTextualInversion)
            assertTrue(profile.capabilities.supportsTextualInversion)
            assertFalse(profile.capabilities.supportsLivePreview)
            assertTrue(profile.capabilities.supportsUltraFix)
            assertEquals(1_024, profile.capabilities.ultraFixRequiredTileSize)
        }
        listOf(
            "cyberrealistic_sd15_qnn228",
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228"
        ).forEach { id ->
            val conditioning = qnn228Sd15Profiles.getValue(id).conditioning
            assertEquals(ImageEngineEmbeddingDataType.FP16, conditioning.diskDataType)
            assertEquals(ImageEngineEmbeddingConversionStrategy.NONE, conditioning.conversionStrategy)
        }
        val meinaConditioning = qnn228Sd15Profiles.getValue("meinamix_sd15_qnn228").conditioning
        assertEquals(ImageEngineEmbeddingDataType.FP32, meinaConditioning.diskDataType)
        assertEquals(
            ImageEngineEmbeddingConversionStrategy.FP32_TO_FP16_STREAMING,
            meinaConditioning.conversionStrategy
        )
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
        assertEquals(3, cyberRealisticQnn.imageEngineBundle!!.qnnSmokeSpecs.size)
        assertEquals("unet.bin", cyberRealisticQnn.imageEngineBundle!!.qnnSmokeSpecs.first().contextBinary)
        val cyberEncoder = cyberRealisticQnn.imageEngineBundle!!.qnnSmokeSpecs.single {
            it.contextBinary == "vae_encoder.bin"
        }
        assertEquals(58_870_768L, cyberEncoder.expectedContextSizeBytes)
        assertEquals(
            "f2a5d073d0c4492361eb49005f03acd6ecdceba652c6fc7ba68eddd2b4d98da7",
            cyberEncoder.expectedContextSha256
        )
        assertEquals(listOf(1, 3, 512, 512), cyberEncoder.inputs.single().shape)
        assertEquals(setOf("mean", "std"), cyberEncoder.outputs.map { it.name }.toSet())
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
        assertTrue(cyberRealisticXlQnn.description.contains("1024×1024"))
        assertTrue(cyberRealisticXlQnn.description.contains("9 次"))
        assertTrue(cyberRealisticXlQnn.description.contains("分块解码"))
        assertTrue(cyberRealisticXlQnn.description.contains("尚待生产 MainActivity 与认证 Local API 复验"))
        assertEquals(3, cyberRealisticXlQnn.imageEngineBundle!!.qnnSmokeSpecs.size)
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
        assertEquals(RecommendedModelStatus.EXPERIMENTAL, sdTurbo.status)
        assertTrue(sdTurbo.description.contains("现有归档仅证明 debug worker 以 1-step/Euler 产图"))
        assertTrue(sdTurbo.downloadable)
        assertNull(sdTurbo.downloadBlockReason)
        assertEquals(ModelRepositoryProvider.MODELSCOPE, sdTurbo.provider)
        assertEquals(ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP, sdTurboBundle.runtime)
        assertEquals(ImageEngineAccelerator.CPU, sdTurboBundle.accelerator)
        assertEquals(ImageEngineMinDeviceTier.ANY, sdTurboBundle.minDeviceTier)
        assertEquals("SD_TURBO", sdTurboBundle.modelFamily)
        assertEquals(512, sdTurboBundle.smokeSpec.width)
        assertEquals(512, sdTurboBundle.smokeSpec.height)
        assertEquals(4, sdTurboBundle.smokeSpec.steps)
        val sdTurboProfile = requireNotNull(sdTurboBundle.executionProfile)
        assertEquals(2, sdTurboProfile.profileRevision)
        assertTrue(sdTurboProfile.tokenizer.supportsTextualInversion)
        assertTrue(sdTurboProfile.capabilities.supportsTextualInversion)
        assertTrue(sdTurboProfile.capabilities.supportsUltraFix)
        assertEquals(128, sdTurboProfile.capabilities.ultraFixMinWidth)
        assertEquals(8_192, sdTurboProfile.capabilities.ultraFixMaxWidth)
        assertEquals(64, sdTurboProfile.capabilities.ultraFixWidthMultiple)
        assertTrue(sdTurbo.description.contains("4-step"))
        assertTrue(sdTurbo.description.contains("CFG 1.0"))
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
        assertTrue(recommendations.first { it.id == "sd15_mnn_512_quality" }.description.contains("VAE encoder"))
        assertTrue(recommendations.first { it.id == "sd15_mnn_512_quality" }.description.contains("不宣称 img2img"))
        assertNull(recommendations.first { it.id == "sd15_mnn_512_quality" }.downloadBlockReason)
        assertEquals(LocalImageEngineTier.COMPACT_QUALITY, recommendations.first { it.id == "flux2_klein_4b_q4" }.localImageEngineTier)
        assertEquals(LocalImageEngineTier.LARGE_QUALITY, recommendations.first { it.id == "z_image_turbo_q4" }.localImageEngineTier)
        assertEquals(LocalImageEngineTier.HEAVY_EXPERIMENTAL, recommendations.first { it.id == "qwen_image_2512_q2" }.localImageEngineTier)
        assertEquals(LocalImageEngineTier.HEAVY_EXPERIMENTAL, recommendations.first { it.id == "longcat_image_q4" }.localImageEngineTier)
        assertTrue(
            recommendations.first { it.id == "qwen_image_2512_q2" }
                .description.contains("完整三组件包")
        )
        assertTrue(
            recommendations.first { it.id == "longcat_image_q4" }
                .description.contains("完整三组件包")
        )
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
    fun gemma12bLiteRtRecommendationsQueryTheModelScopeMasterRevision() {
        val requestedUrls = mutableListOf<String>()
        val body = """
            {"Code":200,"Data":{"Files":[
              {"Path":"gemma-4-12B-it.litertlm","Size":6547589312,
               "Sha256":"74fc29a10c20eb5b3ced6c389471a7994a0ffd657255b2a1c764262fb9054aef"}
            ]}}
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
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val resolvingClient = ModelScopeClient(
            client = networkClient,
            endpoints = listOf("https://modelscope.test")
        )
        val recommendation = resolvingClient.recommendedModels()
            .single { it.id == "gemma4_12b_litertlm_cpu" }

        val files = resolvingClient.listRecommendedFiles(recommendation)

        assertEquals(1, files.size)
        assertEquals("gemma-4-12B-it.litertlm", files.single().name)
        assertEquals(6_547_589_312L, files.single().sizeBytes)
        assertEquals(
            "74fc29a10c20eb5b3ced6c389471a7994a0ffd657255b2a1c764262fb9054aef",
            files.single().sha256
        )
        assertEquals(1, requestedUrls.size)
        assertTrue(requestedUrls.single().contains("Revision=master"))
    }

    @Test
    fun unverifiedImageCatalogClaimsStayExperimentalAndDownloadable() {
        val recommendations = client.userFacingRecommendedModels().associateBy { it.id }
        val evidenceLimitedIds = setOf(
            "cyberrealisticxl_qnn228",
            "qualcomm_sd15_gen5_qnn",
            "qualcomm_sd21_gen5_qnn",
            "qualcomm_controlnet_canny_gen5_qnn",
            "sd_turbo_512_experimental",
            "mnn_sana_edit_v2"
        )

        evidenceLimitedIds.forEach { id ->
            val model = requireNotNull(recommendations[id])
            assertEquals("$id must not claim verified status", RecommendedModelStatus.EXPERIMENTAL, model.status)
            assertTrue("$id remains visible", model.visibleInRecommendations)
            assertTrue("$id remains downloadable", model.downloadable)
            assertNull("$id has no download block reason", model.downloadBlockReason)
            assertTrue(
                "$id remains downloadable without device-profile admission",
                model.downloadEligibilityFor("", deviceIsSnapdragon = false).canDownload
            )
        }

        listOf("qualcomm_sd15_gen5_qnn", "qualcomm_sd21_gen5_qnn").forEach { id ->
            val description = requireNotNull(recommendations[id]).description
            assertTrue(description.contains("尚无生产 MainActivity 和认证 Local API 的真机出图证据"))
            assertFalse(description.contains("已完成"))
            assertFalse(description.contains("真实 QNN HTP 生图回归"))
        }

        val controlNetDescription = requireNotNull(
            recommendations["qualcomm_controlnet_canny_gen5_qnn"]
        ).description
        assertTrue(controlNetDescription.contains("产品输入链尚无生产 UI/API 真机执行证据"))
        assertFalse(controlNetDescription.contains("已接线"))

        val sanaDescription = requireNotNull(recommendations["mnn_sana_edit_v2"]).description
        assertTrue(sanaDescription.contains("尚无生产 MainActivity 与认证 Local API 的真实编辑证据"))
        assertFalse(sanaDescription.contains("已验证"))

        val sdTurboDescription = requireNotNull(recommendations["sd_turbo_512_experimental"]).description
        assertTrue(sdTurboDescription.contains("当前目录默认 512×512、4-step、CFG 1.0、Euler ancestral"))
        assertTrue(sdTurboDescription.contains("现有归档仅证明 debug worker 以 1-step/Euler 产图"))
        assertTrue(sdTurboDescription.contains("尚未证明当前预设"))
        assertFalse(sdTurboDescription.contains("三次冷启动真机出图"))

        val cyberXlDescription = requireNotNull(recommendations["cyberrealisticxl_qnn228"]).description
        assertTrue(cyberXlDescription.contains("UNet [1,4,128,128]"))
        assertTrue(cyberXlDescription.contains("VAE [1,4,64,64]"))
        assertTrue(cyberXlDescription.contains("3×3、共 9 次"))
        assertTrue(cyberXlDescription.contains("重叠融合兼容路径"))
        assertTrue(cyberXlDescription.contains("尚待生产 MainActivity 与认证 Local API 复验"))
        assertTrue(cyberXlDescription.contains("不会静默切换模型"))
    }

    @Test
    fun recommendedMeinaMixAndFlux2PackagesPinSourceIntegrity() {
        val recommendations = client.recommendedModels()

        val meinaMix = recommendations.single { it.id == "meinamix_sd15_qnn228" }
        val meinaArchive = requireNotNull(meinaMix.imageEngineBundle)
            .requiredComponents
            .single { it.role == ImageEngineBundleComponentRole.DIFFUSION }
        assertEquals("17d26a779cf2a53acc6caf0345c663767c293c5a", meinaMix.revision)
        assertEquals(meinaMix.revision, meinaArchive.revision)
        assertEquals(1_228_483_146L, meinaArchive.expectedSizeBytes)
        assertEquals(
            "120aa73eb843bf24a96fe067baa2c4f97fb3a95620c36caffe080ed7f4503d09",
            meinaArchive.sha256
        )

        val fluxComponents = requireNotNull(
            recommendations.single { it.id == "flux2_klein_4b_q4" }.imageEngineBundle
        ).requiredComponents.associateBy { it.role }
        val expectedFluxComponents = mapOf(
            ImageEngineBundleComponentRole.DIFFUSION to Pair(
                2_460_378_560L,
                "d1023499ef3f2f82ff7c50e6778495195c1b6cc34835741778868428111f9ff4"
            ),
            ImageEngineBundleComponentRole.VAE to Pair(
                336_211_292L,
                "868fe7b343cc8f3a19dbcfcafbc3d5f888802be3f89bd81b65b3621a066ce8f3"
            ),
            ImageEngineBundleComponentRole.TEXT_ENCODER to Pair(
                2_497_281_312L,
                "f6f851777709861056efcdad3af01da38b31223a3ba26e61a4f8bf3a2195813a"
            )
        )
        assertEquals(expectedFluxComponents.keys, fluxComponents.keys)
        expectedFluxComponents.forEach { (role, expected) ->
            assertEquals(expected.first, fluxComponents.getValue(role).expectedSizeBytes)
            assertEquals(expected.second, fluxComponents.getValue(role).sha256)
        }
    }

    @Test
    fun advertisedChatCapabilitiesExposeVerifiedAndExperimentalStates() {
        val userFacingChat = client.userFacingRecommendedModels()
            .filter { it.kind == ModelScopeRecommendedKind.CHAT }

        assertEquals(21, userFacingChat.size)
        val cpuChat = userFacingChat.filter {
            it.computeBackend != RecommendedComputeBackend.NPU &&
                it.chatRuntime != RecommendedChatRuntime.GENIEX_QAIRT
        }
        val npuChat = userFacingChat.filter {
            it.computeBackend == RecommendedComputeBackend.NPU ||
                it.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT
        }
        assertEquals(14, cpuChat.size)
        assertEquals(7, npuChat.size)
        assertTrue(cpuChat.all { it.status == RecommendedModelStatus.EXPERIMENTAL })
        assertEquals(
            setOf(
                RecommendedModelStatus.RECOMMENDED,
                RecommendedModelStatus.EXPERIMENTAL,
                RecommendedModelStatus.PENDING_INTEGRATION
            ),
            npuChat.map { it.status }.toSet()
        )
        assertTrue(
            userFacingChat.all { model ->
                model.downloadable || model.id in setOf("gemma4_e4b_litertlm_npu", "gemma4_12b_litertlm_npu")
            }
        )
        assertEquals(
            setOf("gemma4_e4b_litertlm_npu", "gemma4_12b_litertlm_npu"),
            userFacingChat.filterNot { it.downloadable }.map { it.id }.toSet()
        )
        assertTrue(
            userFacingChat.all { model ->
                val matchingChipset = model.supportedChipsetCodes.firstOrNull().orEmpty()
                model.downloadable && model.downloadEligibilityFor(matchingChipset).canDownload ||
                    !model.downloadable && model.id in setOf(
                        "gemma4_e4b_litertlm_npu",
                        "gemma4_12b_litertlm_npu"
                    )
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
        npuModels.filter { it.downloadable }.forEach { model ->
            listOf("SM8550", "SM8750", "SM8850", "MT6989", "").forEach { chipset ->
                val eligibility = model.downloadEligibilityFor(
                    deviceChipsetCode = chipset,
                    deviceIsSnapdragon = chipset.startsWith("SM")
                )
                assertTrue("${model.id} must stay downloadable on '$chipset'", eligibility.canDownload)
                assertNull(eligibility.blockedReason)
            }
        }
        npuModels.filterNot { it.downloadable }.forEach { model ->
            assertTrue(model.id in setOf("gemma4_e4b_litertlm_npu", "gemma4_12b_litertlm_npu"))
            assertTrue(model.downloadBlockReason.orEmpty().contains("没有可确认"))
        }
    }

    @Test
    fun everyImageRecommendationPinsTheEffectiveExecutionContract() {
        val models = client.recommendedModels().filter { it.kind == ModelScopeRecommendedKind.IMAGE }
        val expected = expectedImageProfiles()

        assertEquals(expected.keys, models.map { it.id }.toSet())
        models.forEach { model ->
            val bundle = requireNotNull(model.imageEngineBundle)
            val profile = requireNotNull(bundle.executionProfile)
            val contract = expected.getValue(model.id)

            assertEquals(model.id, bundle.recommendationId)
            assertEquals(contract.profileId, profile.profileId)
            assertEquals(contract.family, profile.family)
            assertEquals(contract.variant, profile.variant)
            assertEquals(contract.task, profile.task)
            assertEquals(contract.task, bundle.task)
            assertEquals(contract.scheduler, profile.scheduler.algorithm)
            assertEquals(contract.steps, profile.scheduler.defaultSteps)
            assertEquals(contract.steps, profile.defaults.steps)
            assertEquals(contract.cfgScale, profile.defaults.cfgScale, 0.0)
            assertEquals(contract.useCfg, profile.defaults.useCfg)
            assertEquals(contract.width, profile.defaults.width)
            assertEquals(contract.height, profile.defaults.height)
            assertEquals(contract.tokenizer, profile.tokenizer.backend)
            assertEquals(contract.tokenizerMaxLength, profile.tokenizer.maxLength)
            assertEquals(contract.clip1PadRule, profile.tokenizer.clip1PadRule)
            assertEquals(contract.clip2PadRule, profile.tokenizer.clip2PadRule)
            assertEquals(contract.vaeScalingLocation, profile.vae.scalingLocation)
            assertEquals(contract.vaeScalingFactor, profile.vae.scalingFactor, 0.0)
            assertEquals(profile.family.name, bundle.modelFamily)
            assertEquals(profile.defaults.width, bundle.smokeSpec.width)
            assertEquals(profile.defaults.height, bundle.smokeSpec.height)
            assertEquals(profile.defaults.steps, bundle.smokeSpec.steps)
            assertNull("${model.id} must not invent a catalog prompt", profile.defaults.defaultPrompt)
            assertEquals(
                "${model.id} must pin its catalog negative-prompt behavior",
                expectedNegativePrompt(model.id),
                profile.defaults.defaultNegativePrompt
            )
            val conditionalOnly = model.id in conditionalOnlyImageIds
            assertEquals(
                "${model.id} negative-prompt capability drifted",
                !conditionalOnly,
                profile.capabilities.supportsNegativePrompt
            )
            assertEquals(
                "${model.id} negative token branch drifted",
                !conditionalOnly,
                profile.tokenizer.separateNegativePrompt
            )
            assertTrue(profile.scheduler.algorithm in profile.capabilities.supportedSchedulers)
            if (model.visibleInRecommendations && model.downloadable) {
                listOf("", "UNKNOWN", "MT6989").forEach { chipset ->
                    val access = model.downloadEligibilityFor(chipset, deviceIsSnapdragon = false)
                    assertTrue("${model.id} must stay open on unknown device '$chipset'", access.canDownload)
                    assertNull(access.blockedReason)
                }
            }
        }

        val hyper = models.single { it.id == "realisticvisionhyper_sd15_qnn228" }
            .imageEngineBundle!!
            .executionProfile!!
        assertEquals(8, hyper.defaults.steps)
        assertEquals(2.0, hyper.defaults.cfgScale, 0.0)

        listOf(
            "sdxl_base_qnn228",
            "realismsdxl_dmd2_alt_qnn228",
            "animagine_xl_v4_qnn228",
            "cyberrealisticxl_qnn228"
        ).forEach { id ->
            val bundle = models.single { it.id == id }.imageEngineBundle!!
            val scheduler = bundle.executionProfile!!.scheduler
            assertEquals(ImageEngineSchedulerAlgorithm.DPMPP_2M, scheduler.algorithm)
            assertEquals(2, scheduler.order)
            assertEquals(6, bundle.executionProfile!!.profileRevision)
            assertEquals(
                setOf(
                    ImageEngineSchedulerAlgorithm.DPMPP_2M,
                    ImageEngineSchedulerAlgorithm.EULER
                ),
                bundle.executionProfile!!.capabilities.supportedSchedulers
            )
            assertFalse(
                ImageEngineSchedulerAlgorithm.LCM in
                    bundle.executionProfile!!.capabilities.supportedSchedulers
            )
            assertNull(bundle.executionProfile!!.graph.htpArch)
            assertNull(bundle.requiredRuntimeProfile)
        }

        conditionalOnlyImageIds.forEach { id ->
            val profile = models.single { it.id == id }.imageEngineBundle!!.executionProfile!!
            val defaults = profile.defaults
            assertEquals(1.0, defaults.cfgScale, 0.0)
            assertFalse(defaults.useCfg)
            assertFalse(profile.capabilities.supportsNegativePrompt)
            assertFalse(profile.tokenizer.separateNegativePrompt)
            assertNull(defaults.defaultNegativePrompt)
        }

        val sd15Gen5 = models.single { it.id == "qualcomm_sd15_gen5_qnn" }
            .imageEngineBundle!!
            .executionProfile!!
        assertEquals(1, sd15Gen5.scheduler.stepsOffset)
        assertEquals(1, sd15Gen5.scheduler.minSteps)
        assertEquals(100, sd15Gen5.scheduler.maxSteps)
        assertTrue(sd15Gen5.scheduler.skipPrkSteps)
        assertFalse(sd15Gen5.tokenizer.supportsPromptWeighting)
        assertFalse(sd15Gen5.capabilities.supportsPromptWeighting)

        listOf("qualcomm_sd21_gen5_qnn", "qualcomm_controlnet_canny_gen5_qnn").forEach { id ->
            val profile = models.single { it.id == id }.imageEngineBundle!!.executionProfile!!
            assertFalse(profile.tokenizer.supportsPromptWeighting)
            assertFalse(profile.capabilities.supportsPromptWeighting)
        }
        assertTrue(sd15Gen5.scheduler.scaleModelInput)

        val sd21Gen5 = models.single { it.id == "qualcomm_sd21_gen5_qnn" }
            .imageEngineBundle!!
            .executionProfile!!
        assertEquals(ImageEnginePredictionType.V_PREDICTION, sd21Gen5.scheduler.predictionType)
        assertEquals(1, sd21Gen5.scheduler.stepsOffset)
        assertEquals(1, sd21Gen5.scheduler.minSteps)
        assertEquals(100, sd21Gen5.scheduler.maxSteps)
        assertTrue(sd21Gen5.scheduler.skipPrkSteps)
        assertEquals(ImageEngineClipPadRule.ZERO, sd21Gen5.tokenizer.clip1PadRule)

        val control = models.single { it.id == "qualcomm_controlnet_canny_gen5_qnn" }
            .imageEngineBundle!!
            .executionProfile!!
        assertTrue(control.capabilities.requiresControlImage)
        assertEquals("controlnet.bin", control.graph.controlNet)

        listOf(
            "z_image_turbo_q4",
            "flux2_klein_4b_q4",
            "qwen_image_2512_q2",
            "longcat_image_q4"
        ).forEach { id ->
            val profile = models.single { it.id == id }.imageEngineBundle!!.executionProfile!!
            assertEquals(
                "$id must not advertise diffusion-only samplers for a flow checkpoint",
                setOf(ImageEngineSchedulerAlgorithm.FLOW_MATCH),
                profile.capabilities.supportedSchedulers
            )
        }

        val splitStableDiffusionCppIds = setOf(
            "z_image_turbo_q4",
            "flux2_klein_4b_q4",
            "qwen_image_2512_q2",
            "longcat_image_q4"
        )
        splitStableDiffusionCppIds.forEach { id ->
            val bundle = requireNotNull(models.single { it.id == id }.imageEngineBundle)
            assertEquals(
                "$id must download its complete split runtime package",
                setOf(
                    ImageEngineBundleComponentRole.DIFFUSION,
                    ImageEngineBundleComponentRole.VAE,
                    ImageEngineBundleComponentRole.TEXT_ENCODER
                ),
                bundle.requiredComponents.mapTo(linkedSetOf()) { it.role }
            )
        }
        models.filter { it.imageEngineBundle?.runtime == ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP }
            .forEach { model ->
                val profile = requireNotNull(model.imageEngineBundle?.executionProfile)
                assertTrue("${model.id} native conditioner applies token weights", profile.tokenizer.supportsPromptWeighting)
                assertTrue("${model.id} must expose its native prompt weighting", profile.capabilities.supportsPromptWeighting)
                assertEquals(ImageEngineTask.TEXT_TO_IMAGE, profile.task)
                assertFalse(profile.capabilities.requiresControlImage)
                assertFalse(profile.capabilities.requiresInputImage)
                assertFalse(profile.capabilities.supportsMask)
            }

        val sana = models.single { it.id == "mnn_sana_edit_v2" }
            .imageEngineBundle!!
            .executionProfile!!
        assertEquals(256, sana.tokenizer.maxLength)
        assertTrue(sana.tokenizer.separateNegativePrompt)
        assertEquals(listOf(1, 256), sana.conditioning.textEncoderInputShape)
        assertEquals(listOf(listOf(1, 256, 1)), sana.conditioning.textEncoderOutputShapes)
        assertEquals("vae_encoder.mnn", sana.graph.vaeEncoder)
        assertTrue(sana.capabilities.requiresInputImage)
        assertFalse(sana.capabilities.supportsMask)
        assertEquals(10, sana.scheduler.defaultSteps)
        assertEquals(2, sana.scheduler.minSteps)
        assertEquals(50, sana.scheduler.maxSteps)

        listOf(
            "cyberrealistic_sd15_qnn228",
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228",
            "meinamix_sd15_qnn228",
            "sdxl_base_qnn228",
            "realismsdxl_dmd2_alt_qnn228",
            "animagine_xl_v4_qnn228",
            "cyberrealisticxl_qnn228"
        ).forEach { id ->
            assertEquals(
                "vae_encoder.bin",
                models.single { it.id == id }.imageEngineBundle!!.executionProfile!!.graph.vaeEncoder
            )
        }
    }

    @Test
    fun mnnSd15PackageIsPinnedAndDoesNotAdvertiseAMissingVaeEncoder() {
        val model = client.recommendedModels().single { it.id == "sd15_mnn_512_quality" }
        val bundle = requireNotNull(model.imageEngineBundle)
        val repositoryComponents = bundle.components.filter {
            it.repoId == "MNN/stable-diffusion-v1-5-mnn-opencl"
        }

        assertEquals("346de5fcde406781a34368140419ac3f62440916", model.revision)
        assertTrue(repositoryComponents.isNotEmpty())
        assertTrue(repositoryComponents.all { it.revision == model.revision })
        assertTrue(bundle.requiredComponents.all { it.expectedSizeBytes != null })
        assertTrue(bundle.requiredComponents.all { it.sha256?.matches(Regex("[0-9a-f]{64}")) == true })
        assertTrue(bundle.components.none { it.fileName.startsWith("vae_encoder.") })
        assertNull(bundle.executionProfile!!.graph.vaeEncoder)
        assertFalse(bundle.executionProfile!!.capabilities.requiresInputImage)
        assertFalse(bundle.executionProfile!!.capabilities.supportsMask)
    }

    @Test
    fun everyUserFacingImageBundlePinsAllRequiredComponentBytes() {
        client.userFacingRecommendedModels()
            .filter { it.kind == ModelScopeRecommendedKind.IMAGE }
            .forEach { model ->
                val bundle = requireNotNull(model.imageEngineBundle) { model.id }
                bundle.requiredComponents.forEach { component ->
                    assertTrue(
                        "${model.id}/${component.fileName} is missing a fixed positive size",
                        component.expectedSizeBytes?.let { it > 0L } == true
                    )
                    assertTrue(
                        "${model.id}/${component.fileName} is missing fixed SHA-256",
                        component.sha256?.matches(Regex("[0-9a-f]{64}")) == true
                    )
                }
            }
    }

    @Test
    fun imageCatalogContractsRejectMissingExecutableComponentsAndPrimaryIdentityDrift() {
        val models = client.recommendedModels().filter { it.kind == ModelScopeRecommendedKind.IMAGE }

        val split = models.single { it.id == "flux2_klein_4b_q4" }.imageEngineBundle!!
        val splitFailure = runCatching {
            split.copy(
                components = split.components.filterNot {
                    it.role == ImageEngineBundleComponentRole.VAE
                }
            )
        }.exceptionOrNull()
        assertTrue(splitFailure is IllegalArgumentException)

        val sana = models.single { it.id == "mnn_sana_edit_v2" }.imageEngineBundle!!
        val sanaFailure = runCatching {
            sana.copy(
                components = sana.components.filterNot {
                    it.role == ImageEngineBundleComponentRole.VAE_ENCODER
                }
            )
        }.exceptionOrNull()
        assertTrue(sanaFailure is IllegalArgumentException)

        val mnn = models.single { it.id == "sd15_mnn_512_quality" }.imageEngineBundle!!
        val mnnFailure = runCatching {
            mnn.copy(
                components = mnn.components.filterNot {
                    it.relativePath == "unet.mnn"
                }
            )
        }.exceptionOrNull()
        assertTrue(mnnFailure is IllegalArgumentException)

        val control = models.single { it.id == "qualcomm_controlnet_canny_gen5_qnn" }
        val controlBundle = control.imageEngineBundle!!
        val controlProfile = controlBundle.executionProfile!!
        val controlFailure = runCatching {
            controlBundle.copy(
                executionProfile = controlProfile.copy(
                    graph = controlProfile.graph.copy(textEncoder = null)
                )
            )
        }.exceptionOrNull()
        assertTrue(controlFailure is IllegalArgumentException)

        val primaryIdentityFailure = runCatching {
            control.copy(recommendedFileName = "different-context.zip")
        }.exceptionOrNull()
        assertTrue(primaryIdentityFailure is IllegalArgumentException)
    }

    @Test
    fun sdxlRecommendationsUse1024UnetEncoderAndTiled512VaeGraphContracts() {
        val sdxlIds = setOf(
            "sdxl_base_qnn228",
            "realismsdxl_dmd2_alt_qnn228",
            "animagine_xl_v4_qnn228",
            "cyberrealisticxl_qnn228"
        )
        val models = client.userFacingRecommendedModels().filter { it.id in sdxlIds }

        assertEquals(sdxlIds, models.map { it.id }.toSet())
        models.forEach { model ->
            val bundle = requireNotNull(model.imageEngineBundle)
            val unet = bundle.qnnSmokeSpecs.single { it.contextBinary == "unet.bin" }
            val vae = bundle.qnnSmokeSpecs.single { it.contextBinary == "vae_decoder.bin" }
            val encoder = bundle.qnnSmokeSpecs.single { it.contextBinary == "vae_encoder.bin" }
            assertEquals(1024, unet.width)
            assertEquals(1024, unet.height)
            assertEquals(listOf(1, 4, 128, 128), unet.inputs.single { it.name == "sample" }.shape)
            assertTrue(unet.inputs.filterNot { it.name == "timestamp" }.all { it.dataType == "float32" })
            assertEquals(listOf(1, 4, 128, 128), unet.outputs.single().shape)
            assertEquals("float32", unet.outputs.single().dataType)
            assertEquals(512, vae.width)
            assertEquals(512, vae.height)
            assertEquals(listOf(1, 4, 64, 64), vae.inputs.single().shape)
            assertEquals("float32", vae.inputs.single().dataType)
            assertEquals(listOf(1, 3, 512, 512), vae.outputs.single().shape)
            assertEquals("float32", vae.outputs.single().dataType)
            assertEquals(1024, encoder.width)
            assertEquals(1024, encoder.height)
            assertEquals(listOf(1, 3, 1024, 1024), encoder.inputs.single().shape)
            assertEquals("float32", encoder.inputs.single().dataType)
            assertEquals(setOf("mean", "std"), encoder.outputs.map { it.name }.toSet())
            assertTrue(encoder.outputs.all {
                it.dataType == "float32" && it.shape == listOf(1, 4, 128, 128)
            })
        }

        val cyber = models.single { it.id == "cyberrealisticxl_qnn228" }
        assertEquals("xororz/sdxl-qnn", cyber.repoId)
        assertEquals("cyber_realistic_v10_qnn2.28_8gen3.zip", cyber.recommendedFileName)
        assertTrue(cyber.downloadEligibilityFor("", deviceIsSnapdragon = false).canDownload)
    }

    @Test
    fun sd15QnnRecommendationsPinDistinctVaeEncoderIdentities() {
        val expected = mapOf(
            "cyberrealistic_sd15_qnn228" to (58_870_768L to "f2a5d073d0c4492361eb49005f03acd6ecdceba652c6fc7ba68eddd2b4d98da7"),
            "realisticvisionhyper_sd15_qnn228" to (58_862_576L to "629797a9eb5204a2465fa993e9efa2546c60dce93d9bcd009ba7b06fc62ecf3b"),
            "dreamshaper_sd15_qnn228" to (58_862_576L to "6baf4c28749e310404c1b079230cd47296d389fb4037f05267e589a50294bc66"),
            "meinamix_sd15_qnn228" to (41_438_176L to "b32367e717c331cbacce7dc3482c7e5668dea90c8cc77396dee3761845d2bdd6")
        )

        expected.forEach { (id, identity) ->
            val encoder = client.userFacingRecommendedModels()
                .single { it.id == id }
                .imageEngineBundle!!
                .qnnSmokeSpecs
                .single { it.contextBinary == "vae_encoder.bin" }
            assertEquals(identity.first, encoder.expectedContextSizeBytes)
            assertEquals(identity.second, encoder.expectedContextSha256)
            assertEquals(listOf(1, 3, 512, 512), encoder.inputs.single().shape)
            assertEquals(
                setOf("mean", "std"),
                encoder.outputs.map { it.name }.toSet()
            )
        }
    }

    @Test
    fun userFacingRecommendationsMatchApprovedFourSectionCatalog() {
        val allRecommendations = client.recommendedModels()
        val recommendations = client.userFacingRecommendedModels()

        assertEquals(39, recommendations.size)
        assertTrue(recommendations.all { it.visibleInRecommendations })

        val cpuChat = recommendations.filter {
            it.kind == ModelScopeRecommendedKind.CHAT &&
                it.computeBackend != RecommendedComputeBackend.NPU &&
                it.chatRuntime != RecommendedChatRuntime.GENIEX_QAIRT &&
                it.visionModelBundle?.accelerator != VisionModelAccelerator.QNN_HTP
        }
        val npuChat = recommendations.filter {
            it.kind == ModelScopeRecommendedKind.CHAT &&
                (it.computeBackend == RecommendedComputeBackend.NPU ||
                    it.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT ||
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

        assertEquals(14, cpuChat.size)
        assertEquals(7, npuChat.size)
        assertEquals(7, cpuImage.size)
        assertEquals(11, npuImage.size)

        fun cpuChatIds(group: ModelScopeRecommendedGroup): List<String> = cpuChat
            .filter { it.group == group }
            .sortedBy { it.priority }
            .map { it.id }

        assertEquals(
            listOf(
                "qwen35_08b_uncensored_mnn",
                "qwen35_2b_abliterated_gguf",
                "gemma4_e2b_uncensored_gguf",
                "gemma4_e2b_litertlm_cpu",
                "gemma4_e2b_litertlm_gpu"
            ),
            cpuChatIds(ModelScopeRecommendedGroup.LIGHT_CHAT)
        )
        assertEquals(
            listOf(
                "qwen35_4b_uncensored_mnn",
                "gemma4_e4b_uncensored_gguf",
                "gemma4_e4b_litertlm_cpu",
                "gemma4_e4b_litertlm_gpu"
            ),
            cpuChatIds(ModelScopeRecommendedGroup.MAIN_CHAT)
        )
        assertEquals(
            listOf(
                "qwen35_9b_uncensored_mnn",
                "qwen35_35b_a3b_iq2_xxs",
                "gemma4_26b_a4b_abliterated_gguf",
                "gemma4_12b_litertlm_cpu",
                "gemma4_12b_litertlm_gpu"
            ),
            cpuChatIds(ModelScopeRecommendedGroup.QUALITY_CHAT)
        )
        assertEquals(
            listOf(
                "qwen3_vl_4b_qairt_w4a16",
                "qwen3_4b_2507_qairt_w4a16",
                "qwen3_8b_qairt_w4a16",
                "qwen25_vl_7b_qairt_w4a16",
                "gemma4_e2b_litertlm_npu",
                "gemma4_e4b_litertlm_npu",
                "gemma4_12b_litertlm_npu"
            ),
            npuChat.sortedBy { it.priority }.map { it.id }
        )

        assertEquals(
            setOf(
                "sd15_mnn_512_quality",
                "sd_turbo_512_experimental",
                "mnn_sana_edit_v2",
                "flux2_klein_4b_q4",
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
                "gemma4_e2b_iq4",
                "gemma4_e4b_iq4",
                "glm47_flash_tq1",
                "google_gemma4_26b_a4b_iq2_xxs",
                "minicpm_v46_q4",
                "qwen35_2b_q4"
            ),
            hiddenIds
        )
        assertTrue(
            allRecommendations.none { it.id in setOf(
                "qwen3_vl_4b_abliterated_gguf",
                "qwen3_4b_2507_abliterated_gguf",
                "minicpm_v46_abliterated_gguf",
                "qwen3_8b_abliterated_gguf",
                "qwen25_vl_7b_abliterated_gguf",
                "qwen36_35b_a3b_abliterated_mnn"
            ) }
        )
        assertTrue(recommendations.none { it.id in hiddenIds })
        assertTrue(allRecommendations.none { it.id == "sd15_mnn_384_fast" })

        assertTrue(
            recommendations.all {
                it.downloadable || it.id in setOf(
                    "gemma4_e4b_litertlm_npu",
                    "gemma4_12b_litertlm_npu"
                )
            }
        )
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
        assertTrue(gen5Images.all { it.status == RecommendedModelStatus.EXPERIMENTAL })
        assertTrue(gen5Images.all { it.downloadable })
        assertTrue(gen5Images.all { it.supportedChipsetCodes == setOf("SM8850", "SM8850P") })
        val expectedGen5Archives = mapOf(
            "qualcomm_sd15_gen5_qnn" to Pair(
                711_934_104L,
                "3716ba4c32d6dcf1af93857d22889e1e95f9c3e4c62983fff2d2a743eeff644e"
            ),
            "qualcomm_sd21_gen5_qnn" to Pair(
                874_955_354L,
                "3fc5fc8df77e4952776020d932ee5934ec432b397a456515ae7f8ed2af004ae8"
            ),
            "qualcomm_controlnet_canny_gen5_qnn" to Pair(
                950_517_794L,
                "582b4dee61584cdd2e0f96bdbaff19a6bf919af365c4a7f258ed260be5e1262d"
            )
        )
        gen5Images.forEach { model ->
            assertTrue(model.downloadEligibilityFor("", deviceIsSnapdragon = false).canDownload)
            val bundle = requireNotNull(model.imageEngineBundle)
            val archive = bundle.components.single {
                it.role == ImageEngineBundleComponentRole.DIFFUSION && it.fileName.endsWith(".zip")
            }
            assertEquals(model.revision, archive.revision)
            assertEquals(expectedGen5Archives.getValue(model.id).first, archive.expectedSizeBytes)
            assertEquals(expectedGen5Archives.getValue(model.id).second, archive.sha256)
            val tokenizerJson = bundle.components.single {
                it.relativePath == "tokenizer/tokenizer.json"
            }
            assertEquals(ImageEngineBundleComponentRole.TOKENIZER, tokenizerJson.role)
            assertEquals("openai/clip-vit-large-patch14", tokenizerJson.repoId)
            assertEquals("32bd64288804d66eefd0ccbe215aa642df71cc41", tokenizerJson.revision)
            assertEquals(2_224_003L, tokenizerJson.expectedSizeBytes)
            assertEquals(
                "a83e0809aa4c3af7208b2df632a7a69668c6d48775b3c3fe4e1b1199d1f8b8f4",
                tokenizerJson.sha256
            )
            assertTrue(bundle.components.any { it.relativePath == "scheduler/scheduler_config.json" })
            assertTrue(bundle.components.any { it.relativePath == "tokenizer/tokenizer_config.json" })
            assertEquals(
                expectedGen5Archives.getValue(model.id),
                client.pinnedQairtImageReleaseAssetIntegrity(
                    modelId = model.id,
                    resolvedName = model.recommendedFileName,
                    recommendedName = model.recommendedFileName
                )
            )
        }
        assertEquals(
            Pair(null, null),
            client.pinnedQairtImageReleaseAssetIntegrity(
                modelId = "qualcomm_sd15_gen5_qnn",
                resolvedName = "different-chipset.zip",
                recommendedName = gen5Images.single {
                    it.id == "qualcomm_sd15_gen5_qnn"
                }.recommendedFileName
            )
        )
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
        assertTrue(recommendation.downloadable)
        assertNull(recommendation.downloadBlockReason)
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
            "VAE_ENCODER|vae_encoder.mnn|761568|06da21081f8ee98792bd1838990068e7284351157cafbfa8793282b611eacb24",
            "VAE_ENCODER|vae_encoder.mnn.weight|155787522|b44ac00f4683697add9578ef4c0f561fb5753fe24a3f4525e7f492028409d05e"
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
    fun sanaEditV2BundleResolutionIsNotStoppedByALegacyIntegrationGuard() {
        val requestCount = AtomicInteger()
        val networkClient = OkHttpClient.Builder()
            .addInterceptor {
                requestCount.incrementAndGet()
                throw AssertionError("Sana bundle resolution reached its repository lookup")
            }
            .build()
        val guardedClient = ModelScopeClient(client = networkClient)
        val recommendation = guardedClient.recommendedModels().single { it.id == "mnn_sana_edit_v2" }

        val failure = runCatching {
            guardedClient.recommendedImageBundleFiles(recommendation)
        }.exceptionOrNull()

        assertTrue(requestCount.get() >= 1)
        assertTrue(failure != null)
    }

    @Test
    fun recommendationPagePrefersMnnOnlyWhenSameModelBundleExists() {
        val recommendations = client.userFacingRecommendedModels()
        val byId = recommendations.associateBy { it.id }
        val mnnChatIds = listOf(
            "qwen35_08b_uncensored_mnn",
            "qwen35_4b_uncensored_mnn",
            "qwen35_9b_uncensored_mnn"
        )
        val ggufFallbackIds = listOf(
            "qwen35_2b_abliterated_gguf",
            "gemma4_e2b_uncensored_gguf",
            "gemma4_e4b_uncensored_gguf",
            "qwen35_35b_a3b_iq2_xxs",
            "gemma4_26b_a4b_abliterated_gguf"
        )

        mnnChatIds.forEach { id ->
            val model = byId.getValue(id)
            assertEquals(RecommendedChatRuntime.MNN, model.chatRuntime)
            assertEquals("config.json", model.recommendedFileName)
            assertEquals(model.provider, requireNotNull(model.mnnModelBundle).provider)
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
            assertEquals("config.json", model.recommendedFileName)
            assertTrue(model.quant.contains("MNN"))
            val bundle = requireNotNull(model.mnnModelBundle)
            val requiredComponents = bundle.requiredComponents
            assertEquals(model.provider, bundle.provider)
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
        val recommendations = client.recommendedModels()
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
    fun qnnImageAssetSelectionPrefersGenericExactMatchBeforeVendorVariant() {
        val releaseAssets = """
            {
              "precisions": {
                "w8a16": {
                  "chipset_assets": {
                    "qualcomm-snapdragon-8-elite": {
                      "qnn_context_binary": {"download_url": "https://example.com/elite-generic.zip"}
                    },
                    "qualcomm-snapdragon-8-elite-for-galaxy": {
                      "qnn_context_binary": {"download_url": "https://example.com/elite.zip"}
                    },
                    "qualcomm-snapdragon-8-elite-gen5": {
                      "qnn_context_binary": {"download_url": "https://example.com/gen5-generic.zip"}
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
            "qualcomm-snapdragon-8-elite-gen5",
            client.selectedQnnImageChipsetForTest(
                releaseAssets,
                listOf("qualcomm-snapdragon-8-elite-gen5")
            )
        )
        assertEquals(
            "qualcomm-snapdragon-8-elite",
            client.selectedQnnImageChipsetForTest(
                releaseAssets,
                listOf("qualcomm-snapdragon-8gen3")
            )
        )

        val galaxyOnly = """
            {
              "precisions": {
                "w8a16": {
                  "chipset_assets": {
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
                galaxyOnly,
                listOf("qualcomm-snapdragon-8-elite-gen5")
            )
        )
    }

    @Test
    fun catalogQnnTargetNeverSilentlyFallsBackToAnotherChipsetArchive() {
        val releaseAssets = """
            {
              "precisions": {
                "w8a16": {
                  "chipset_assets": {
                    "qualcomm-snapdragon-8-elite": {
                      "qnn_context_binary": {"download_url": "https://example.com/elite.zip"}
                    },
                    "qualcomm-snapdragon-8-elite-gen5": {
                      "qnn_context_binary": {"download_url": "https://example.com/gen5-generic.zip"}
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
            "qualcomm-snapdragon-8-elite-gen5",
            client.selectedQnnImageChipsetForTest(
                releaseAssetsJson = releaseAssets,
                preferredChipsets = listOf("qualcomm-snapdragon-8-elite"),
                catalogTargetChipset = "qualcomm-snapdragon-8-elite-gen5"
            )
        )
        assertEquals(
            "qualcomm-snapdragon-8-elite-gen5-for-galaxy",
            client.selectedQnnImageChipsetForTest(
                releaseAssetsJson = releaseAssets,
                preferredChipsets = listOf("qualcomm-snapdragon-8-elite"),
                catalogTargetChipset = "qualcomm-snapdragon-8-elite-gen5-for-galaxy"
            )
        )

        val missingTarget = runCatching {
            client.selectedQnnImageChipsetForTest(
                releaseAssetsJson = releaseAssets.replace(
                    "qualcomm-snapdragon-8-elite-gen5-for-galaxy",
                    "qualcomm-snapdragon-8gen3"
                ),
                preferredChipsets = listOf("qualcomm-snapdragon-8-elite"),
                catalogTargetChipset = "qualcomm-snapdragon-8-elite-gen5-for-galaxy"
            )
        }.exceptionOrNull()
        assertTrue(missingTarget is IllegalStateException)
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
        val recommendations = client.recommendedModels()
        val informalMarkers = listOf("实验版", "观察版", "备用实验", "链路验证包", "官方实验包", " NPU")

        assertTrue(
            recommendations
                .filter { it.status == RecommendedModelStatus.RECOMMENDED }
                .all { model -> informalMarkers.none(model.title::contains) }
        )
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
        val liteRtLm = remote("Gemma3-1B-IT.litertlm")
        assertEquals(RemoteModelFileKind.LITERT_LM_MODEL, liteRtLm.fileKind())
        assertTrue(liteRtLm.isChatModelCandidate())
        assertTrue(liteRtLm.isLiteRtLmModelCandidate())
        assertEquals("LiteRT-LM 聊天模型", liteRtLm.kindLabel())
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
    fun parsesOnlyLiteRtLmFilesForDedicatedListing() {
        val files = client.parseLiteRtLmFilesForTest(
            repoId = "litert-community/Gemma3-1B-IT",
            revision = "main",
            endpoint = "https://huggingface.co",
            body = """
                [
                  {"path":"README.md","size":10},
                  {"path":"Gemma3-1B-IT.litertlm","size":1234},
                  {"path":"Gemma3-1B-IT.gguf","size":5678}
                ]
            """.trimIndent()
        )

        assertEquals(listOf("Gemma3-1B-IT.litertlm"), files.map { it.name })
        assertEquals(
            "https://hf-mirror.com/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT.litertlm?download=true",
            files.single().downloadUrl
        )
        assertEquals(RemoteModelFileKind.LITERT_LM_MODEL, files.single().fileKind())
    }

    @Test
    fun genericEngineListingIncludesLiteRtLmContainersAlongsideGguf() {
        val networkClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                            [
                              {"path":"model.gguf","size":100},
                              {"path":"model.litertlm","size":200}
                            ]
                        """.trimIndent().toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val resolvingClient = ModelScopeClient(
            client = networkClient,
            endpoints = listOf("https://modelscope.invalid")
        )

        val files = resolvingClient.listEngineFiles("owner/model")

        assertEquals(listOf("model.gguf", "model.litertlm"), files.map { it.name })
        assertTrue(files.last().isLiteRtLmModelCandidate())
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

    private data class ExpectedImageProfile(
        val profileId: String,
        val family: ImageEngineModelFamily,
        val variant: ImageEngineModelVariant,
        val steps: Int,
        val cfgScale: Double,
        val useCfg: Boolean,
        val scheduler: ImageEngineSchedulerAlgorithm,
        val width: Int,
        val height: Int,
        val tokenizer: ImageEngineTokenizerBackend,
        val vaeScalingLocation: ImageEngineVaeScalingLocation,
        val vaeScalingFactor: Double,
        val task: ImageEngineTask = ImageEngineTask.TEXT_TO_IMAGE,
        val tokenizerMaxLength: Int = 77,
        val clip1PadRule: ImageEngineClipPadRule = ImageEngineClipPadRule.EOS,
        val clip2PadRule: ImageEngineClipPadRule? = null
    )

    private fun expectedImageProfiles(): Map<String, ExpectedImageProfile> = mapOf(
        "cyberrealistic_sd15_qnn228" to ExpectedImageProfile(
            "community.sd15.qnn228", ImageEngineModelFamily.SD15, ImageEngineModelVariant.STANDARD,
            20, 7.0, true, ImageEngineSchedulerAlgorithm.DPMPP_2M, 512, 512,
            ImageEngineTokenizerBackend.TOKENIZERS_CPP, ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215
        ),
        "realisticvisionhyper_sd15_qnn228" to ExpectedImageProfile(
            "community.sd15.hyper.qnn228", ImageEngineModelFamily.SD15, ImageEngineModelVariant.HYPER,
            8, 2.0, true, ImageEngineSchedulerAlgorithm.DPMPP_2M, 512, 512,
            ImageEngineTokenizerBackend.TOKENIZERS_CPP, ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215
        ),
        "dreamshaper_sd15_qnn228" to ExpectedImageProfile(
            "community.sd15.qnn228", ImageEngineModelFamily.SD15, ImageEngineModelVariant.STANDARD,
            20, 7.0, true, ImageEngineSchedulerAlgorithm.DPMPP_2M, 512, 512,
            ImageEngineTokenizerBackend.TOKENIZERS_CPP, ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215
        ),
        "meinamix_sd15_qnn228" to ExpectedImageProfile(
            "community.sd15.legacy-fp32.qnn228", ImageEngineModelFamily.SD15, ImageEngineModelVariant.LEGACY_FP32,
            20, 7.0, true, ImageEngineSchedulerAlgorithm.DPMPP_2M, 512, 512,
            ImageEngineTokenizerBackend.TOKENIZERS_CPP, ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215
        ),
        "sdxl_base_qnn228" to ExpectedImageProfile(
            "community.sdxl.base.qnn228", ImageEngineModelFamily.SDXL, ImageEngineModelVariant.SDXL_BASE,
            30, 7.0, true, ImageEngineSchedulerAlgorithm.DPMPP_2M, 1024, 1024,
            ImageEngineTokenizerBackend.TOKENIZERS_CPP, ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.13025,
            clip2PadRule = ImageEngineClipPadRule.ZERO
        ),
        "realismsdxl_dmd2_alt_qnn228" to ExpectedImageProfile(
            "community.sdxl.dmd2-alt.qnn228", ImageEngineModelFamily.SDXL, ImageEngineModelVariant.DMD2_ALT,
            4, 1.0, false, ImageEngineSchedulerAlgorithm.DPMPP_2M, 1024, 1024,
            ImageEngineTokenizerBackend.TOKENIZERS_CPP, ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.13025,
            clip2PadRule = ImageEngineClipPadRule.ZERO
        ),
        "animagine_xl_v4_qnn228" to ExpectedImageProfile(
            "community.sdxl.base.qnn228", ImageEngineModelFamily.SDXL, ImageEngineModelVariant.SDXL_BASE,
            RecommendedImageDefaults.ANIMAGINE_XL_STEPS, RecommendedImageDefaults.ANIMAGINE_XL_CFG,
            true, ImageEngineSchedulerAlgorithm.DPMPP_2M, 1024, 1024,
            ImageEngineTokenizerBackend.TOKENIZERS_CPP, ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.13025,
            clip2PadRule = ImageEngineClipPadRule.ZERO
        ),
        "cyberrealisticxl_qnn228" to ExpectedImageProfile(
            "community.sdxl.base.qnn228", ImageEngineModelFamily.SDXL, ImageEngineModelVariant.SDXL_BASE,
            RecommendedImageDefaults.CYBERREALISTIC_XL_STEPS, RecommendedImageDefaults.CYBERREALISTIC_XL_CFG,
            true, ImageEngineSchedulerAlgorithm.DPMPP_2M, 1024, 1024,
            ImageEngineTokenizerBackend.TOKENIZERS_CPP, ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.13025,
            clip2PadRule = ImageEngineClipPadRule.ZERO
        ),
        "qualcomm_sd15_gen5_qnn" to ExpectedImageProfile(
            "qualcomm.sd15.gen5.qnn245", ImageEngineModelFamily.SD15, ImageEngineModelVariant.STANDARD,
            20, 7.5, true, ImageEngineSchedulerAlgorithm.EULER, 512, 512,
            ImageEngineTokenizerBackend.TOKENIZERS_CPP, ImageEngineVaeScalingLocation.GRAPH_INTERNAL, 0.18215
        ),
        "qualcomm_sd21_gen5_qnn" to ExpectedImageProfile(
            "qualcomm.sd21.gen5.qnn245", ImageEngineModelFamily.SD21, ImageEngineModelVariant.SD21,
            20, 7.5, true, ImageEngineSchedulerAlgorithm.DDIM, 512, 512,
            ImageEngineTokenizerBackend.TOKENIZERS_CPP, ImageEngineVaeScalingLocation.GRAPH_INTERNAL, 0.18215,
            clip1PadRule = ImageEngineClipPadRule.ZERO
        ),
        "qualcomm_controlnet_canny_gen5_qnn" to ExpectedImageProfile(
            "qualcomm.controlnet-canny.gen5.qnn245", ImageEngineModelFamily.SD15,
            ImageEngineModelVariant.CONTROLNET_CANNY, 20, 7.5, true, ImageEngineSchedulerAlgorithm.EULER,
            512, 512, ImageEngineTokenizerBackend.TOKENIZERS_CPP,
            ImageEngineVaeScalingLocation.GRAPH_INTERNAL, 0.18215,
            task = ImageEngineTask.CONTROL_IMAGE
        ),
        "sd15_mnn_512_quality" to ExpectedImageProfile(
            "mnn.sd15.official.512", ImageEngineModelFamily.SD15, ImageEngineModelVariant.STANDARD,
            20, 7.0, true, ImageEngineSchedulerAlgorithm.DPMPP_2M, 512, 512,
            ImageEngineTokenizerBackend.MNN_MTOK, ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215,
            clip1PadRule = ImageEngineClipPadRule.EOS
        ),
        "mnn_sana_edit_v2" to ExpectedImageProfile(
            "mnn.sana-edit.v2", ImageEngineModelFamily.SANA, ImageEngineModelVariant.SANA_EDIT,
            10, 4.5, true, ImageEngineSchedulerAlgorithm.FLOW_MATCH, 512, 512,
            ImageEngineTokenizerBackend.MNN_MTOK, ImageEngineVaeScalingLocation.RUNTIME_NATIVE, 1.0,
            task = ImageEngineTask.IMAGE_EDIT,
            tokenizerMaxLength = 256,
            clip1PadRule = ImageEngineClipPadRule.MODEL_DECLARED
        ),
        "sd_turbo_512_experimental" to ExpectedImageProfile(
            "sdcpp.sd-turbo", ImageEngineModelFamily.SD_TURBO, ImageEngineModelVariant.SD_TURBO,
            4, 1.0, false, ImageEngineSchedulerAlgorithm.EULER_A, 512, 512,
            ImageEngineTokenizerBackend.SDCPP_NATIVE, ImageEngineVaeScalingLocation.RUNTIME_NATIVE, 1.0
        ),
        "z_image_turbo_q4" to ExpectedImageProfile(
            "sdcpp.z-image-turbo", ImageEngineModelFamily.Z_IMAGE, ImageEngineModelVariant.Z_IMAGE_TURBO,
            8, 1.0, false, ImageEngineSchedulerAlgorithm.FLOW_MATCH, 512, 512,
            ImageEngineTokenizerBackend.SDCPP_NATIVE, ImageEngineVaeScalingLocation.RUNTIME_NATIVE, 1.0
        ),
        "flux2_klein_4b_q4" to ExpectedImageProfile(
            "sdcpp.flux2-klein", ImageEngineModelFamily.FLUX, ImageEngineModelVariant.FLUX2_KLEIN,
            4, 1.0, false, ImageEngineSchedulerAlgorithm.FLOW_MATCH, 1024, 1024,
            ImageEngineTokenizerBackend.SDCPP_NATIVE, ImageEngineVaeScalingLocation.RUNTIME_NATIVE, 1.0
        ),
        "qwen_image_2512_q2" to ExpectedImageProfile(
            "sdcpp.qwen-image", ImageEngineModelFamily.QWEN_IMAGE, ImageEngineModelVariant.QWEN_IMAGE,
            40, 2.5, true, ImageEngineSchedulerAlgorithm.FLOW_MATCH, 1024, 1024,
            ImageEngineTokenizerBackend.SDCPP_NATIVE, ImageEngineVaeScalingLocation.RUNTIME_NATIVE, 1.0
        ),
        "longcat_image_q4" to ExpectedImageProfile(
            "sdcpp.longcat-image", ImageEngineModelFamily.LONGCAT_IMAGE, ImageEngineModelVariant.LONGCAT_IMAGE,
            20, 5.0, true, ImageEngineSchedulerAlgorithm.FLOW_MATCH, 1024, 1024,
            ImageEngineTokenizerBackend.SDCPP_NATIVE, ImageEngineVaeScalingLocation.RUNTIME_NATIVE, 1.0
        )
    )

    private fun expectedNegativePrompt(id: String): String? = when (id) {
        "cyberrealistic_sd15_qnn228",
        "realisticvisionhyper_sd15_qnn228" -> RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT
        "dreamshaper_sd15_qnn228",
        "qualcomm_sd15_gen5_qnn",
        "qualcomm_sd21_gen5_qnn",
        "qualcomm_controlnet_canny_gen5_qnn",
        "sd15_mnn_512_quality" -> RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
        "meinamix_sd15_qnn228",
        "animagine_xl_v4_qnn228" -> RecommendedImageDefaults.ANIME_NEGATIVE_PROMPT
        "sdxl_base_qnn228" -> RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT
        "cyberrealisticxl_qnn228" -> RecommendedImageDefaults.CYBERREALISTIC_XL_NEGATIVE_PROMPT
        "mnn_sana_edit_v2" -> RecommendedImageDefaults.EDIT_NEGATIVE_PROMPT
        "qwen_image_2512_q2" -> RecommendedImageDefaults.QWEN_IMAGE_2512_NEGATIVE_PROMPT
        "longcat_image_q4" -> RecommendedImageDefaults.LONGCAT_IMAGE_NEGATIVE_PROMPT
        in conditionalOnlyImageIds -> null
        else -> error("Missing expected image negative-prompt contract for $id")
    }

    private fun remote(name: String): RemoteModelFile = RemoteModelFile(
        repoId = "owner/model",
        revision = "master",
        path = name,
        name = name,
        downloadUrl = "https://example.com/$name"
    )
}

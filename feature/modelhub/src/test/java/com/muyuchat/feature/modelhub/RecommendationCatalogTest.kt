package com.muyuchat.feature.modelhub

import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.RecommendedModelSection
import com.muyuchat.core.download.RecommendedModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationCatalogTest {
    private val recommendations = ModelScopeClient().userFacingRecommendedModels()
    private val totalRamBytes = 16L * 1024L * 1024L * 1024L

    @Test
    fun sm8550ShowsTheCompleteCatalogWithoutChipsetFiltering() {
        val catalog = catalogFor("SM8550")

        assertCpuCatalog(catalog)
        assertEquals(expectedIds(RecommendedModelSection.NPU_CHAT), catalog.npuChat.map { it.id })
        assertNpuImageCatalog(catalog)
    }

    @Test
    fun sm8750ShowsTheSameCompleteCatalog() {
        val catalog = catalogFor("SM8750")

        assertCpuCatalog(catalog)
        assertEquals(expectedIds(RecommendedModelSection.NPU_CHAT), catalog.npuChat.map { it.id })
        assertNpuImageCatalog(catalog)
    }

    @Test
    fun sm8850ShowsTheSameCompleteCatalog() {
        val catalog = catalogFor("SM8850")

        assertCpuCatalog(catalog)
        assertEquals(expectedIds(RecommendedModelSection.NPU_CHAT), catalog.npuChat.map { it.id })
        assertNpuImageCatalog(catalog)
    }

    @Test
    fun nonSnapdragonAndUnknownChipsetsStillShowNpuCards() {
        listOf("MT6989", "").forEach { chipset ->
            val catalog = catalogFor(chipset)

            assertCpuCatalog(catalog)
            assertEquals(expectedIds(RecommendedModelSection.NPU_CHAT), catalog.npuChat.map { it.id })
            assertNpuImageCatalog(catalog)
        }
    }

    @Test
    fun rawRecommendationListDoesNotResurrectModelsHiddenFromTheProductCatalog() {
        val catalog = buildRecommendationCatalog(
            models = ModelScopeClient().recommendedModels(),
            deviceChipsetCode = "SM8550",
            deviceTotalRamBytes = totalRamBytes
        )

        val visibleIds = listOf(
            catalog.lightChat,
            catalog.mainChat,
            catalog.qualityChat,
            catalog.npuChat,
            catalog.cpuImage,
            catalog.npuImage
        ).flatten().map { it.id }
        assertTrue(
            visibleIds.none {
                it.startsWith("bitcpm") ||
                    it == "glm47_flash_tq1"
            }
        )
        assertTrue("mnn_sana_edit_v2" in visibleIds)
        assertTrue("flux2_klein_4b_q4" in visibleIds)
        assertTrue("meinamix_sd15_qnn228" in visibleIds)
    }

    @Test
    fun npuImageDownloadsStayOpenOnUnmatchedAndUnknownDevices() {
        val cyberRealistic = recommendations.first { it.id == "cyberrealistic_sd15_qnn228" }

        assertTrue(recommendationDownloadAccess(cyberRealistic, "SM8550", deviceIsSnapdragon = true).canDownload)
        assertTrue(recommendationDownloadAccess(cyberRealistic, "SM8850", deviceIsSnapdragon = true).canDownload)
        assertTrue(recommendationDownloadAccess(cyberRealistic, "MT6989", deviceIsSnapdragon = false).canDownload)
        assertTrue(recommendationDownloadAccess(cyberRealistic, "MT6989", deviceIsSnapdragon = false).experimental)
        assertTrue(recommendationDownloadAccess(cyberRealistic, "", deviceIsSnapdragon = false).canDownload)
    }

    @Test
    fun gen5ExperimentalStateNeverRestrictsDownloadAccess() {
        val gen5Sd15 = recommendations.first { it.id == "qualcomm_sd15_gen5_qnn" }
        val verifiedAccess = recommendationDownloadAccess(gen5Sd15, "SM8850", deviceIsSnapdragon = true)
        val controlNet = recommendations.first { it.id == "qualcomm_controlnet_canny_gen5_qnn" }
        val pendingAccess = recommendationDownloadAccess(controlNet, "SM8850", deviceIsSnapdragon = true)

        assertTrue(verifiedAccess.canDownload)
        assertTrue(verifiedAccess.experimental)
        assertTrue(pendingAccess.canDownload)
        assertTrue(pendingAccess.experimental)
    }

    @Test
    fun splitSdxlCardsStayOpenAndDescribeTheUnverifiedProductChainAccurately() {
        val expectedCopy =
            "工程状态：已接真实 VAE encoder + 隔离 encoder→UNet→VAE 的 IMG2IMG、Inpaint、UltraFix 与 Textual Inversion 产品链；尚需代表 ARM64 设备的生产 UI/API 真机验证"
        val ids = listOf(
            "sdxl_base_qnn228",
            "realismsdxl_dmd2_alt_qnn228",
            "animagine_xl_v4_qnn228",
            "cyberrealisticxl_qnn228"
        )

        ids.forEach { id ->
            val model = recommendations.first { it.id == id }
            listOf(
                Triple("SM8750", true, "matched Snapdragon"),
                Triple("MT6989", false, "unmatched chipset"),
                Triple("", false, "unknown chipset")
            ).forEach { (chipset, isSnapdragon, deviceDescription) ->
                val access = recommendationDownloadAccess(model, chipset, isSnapdragon)
                assertTrue("$id must stay downloadable on $deviceDescription", access.canDownload)
                assertEquals(
                    "实验下载",
                    recommendationDownloadCtaLabel(model, access.canDownload, access.experimental)
                )
            }
            assertEquals(expectedCopy, recommendationVerificationLine(model, qairtVerified = false))
        }
    }

    @Test
    fun experimentalDownloadsAreExplicitAndRemainSeparateFromDefaultRecommendations() {
        val realisticVision = recommendations.first { it.id == "realisticvisionhyper_sd15_qnn228" }
        val access = recommendationDownloadAccess(realisticVision, "SM8550", deviceIsSnapdragon = true)

        assertTrue(access.canDownload)
        assertEquals(
            if (access.experimental) "实验下载" else "下载",
            recommendationDownloadCtaLabel(realisticVision, access.canDownload, access.experimental)
        )
        assertEquals(
            "工程状态：已接真实 VAE encoder→共享 UNet/VAE 的 IMG2IMG、Inpaint、UltraFix、Textual Inversion 与 VAE 预览产品链；历史文生图证据不代表这些链路已验收，尚需代表性 ARM64 生产 UI/API 真机验证",
            recommendationVerificationLine(realisticVision, qairtVerified = false)
        )
    }

    @Test
    fun sharedSd15CardsDescribeProductWiringWithoutClaimingProductionValidation() {
        val expectedCopy =
            "工程状态：已接真实 VAE encoder→共享 UNet/VAE 的 IMG2IMG、Inpaint、UltraFix、Textual Inversion 与 VAE 预览产品链；历史文生图证据不代表这些链路已验收，尚需代表性 ARM64 生产 UI/API 真机验证"
        listOf(
            "cyberrealistic_sd15_qnn228",
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228",
            "meinamix_sd15_qnn228"
        ).forEach { id ->
            val model = recommendations.first { it.id == id }
            assertEquals(expectedCopy, recommendationVerificationLine(model, qairtVerified = false))
        }
    }

    @Test
    fun collapsedTierShowsOnlyItsApprovedFirstModelWithoutReordering() {
        val catalog = catalogFor("SM8750")

        assertEquals(
            listOf("qwen35_08b_uncensored_mnn"),
            collapsedRecommendationModels(catalog.lightChat).map { it.id }
        )
        assertEquals(
            listOf("qwen35_4b_uncensored_mnn"),
            collapsedRecommendationModels(catalog.mainChat).map { it.id }
        )
        assertEquals(
            listOf("qwen35_9b_uncensored_mnn"),
            collapsedRecommendationModels(catalog.qualityChat).map { it.id }
        )
        assertEquals(emptyList<String>(), collapsedRecommendationModels(catalog.npuChat).map { it.id })
        assertEquals(listOf(catalog.cpuImage.first().id), collapsedRecommendationModels(catalog.cpuImage).map { it.id })
        assertEquals(listOf("cyberrealistic_sd15_qnn228"), collapsedRecommendationModels(catalog.npuImageSd15).map { it.id })
        assertEquals(listOf("sdxl_base_qnn228"), collapsedRecommendationModels(catalog.npuImageSdxl).map { it.id })
        assertEquals(listOf("qualcomm_sd15_gen5_qnn"), collapsedRecommendationModels(catalog.npuImageGen5).map { it.id })
    }

    @Test
    fun cpuDownloadsIgnoreRamAndStaticCatalogState() {
        val portableModel = recommendations.first { it.section == RecommendedModelSection.CPU_CHAT }

        assertTrue(recommendationDownloadAccess(portableModel, "").canDownload)
        assertTrue(recommendationDownloadAccess(portableModel, "MT6989").canDownload)
    }

    @Test
    fun gemma4TwentySixBCardStaysDownloadableAndPointsAtItsRealRepository() {
        val gemma = recommendations.first { it.id == "gemma4_26b_a4b_abliterated_gguf" }
        val access = recommendationDownloadAccess(gemma, "")

        assertTrue(catalogFor("").qualityChat.any { it.id == gemma.id })
        assertTrue(access.canDownload)
        assertTrue(access.experimental)
        assertEquals("实验下载", recommendationDownloadCtaLabel(gemma, access.canDownload, access.experimental))
        assertEquals(
            "https://hf-mirror.com/mradermacher/Huihui-gemma-4-26B-A4B-it-abliterated-GGUF",
            gemma.modelPageUrl
        )
    }

    @Test
    fun npuChatChipsetMatchIsAdvisoryAndNeverBlocksDownload() {
        val qwenVl = ModelScopeClient().recommendedModels()
            .first { it.id == "qwen3_vl_4b_qairt_w4a16" }

        assertTrue(recommendationDownloadAccess(qwenVl, "SM8750").canDownload)
        assertTrue(recommendationDownloadAccess(qwenVl, "SM8550").canDownload)
        assertTrue(recommendationDownloadAccess(qwenVl, "MT6989").canDownload)
        assertTrue(recommendationDownloadAccess(qwenVl, "").canDownload)
        assertTrue(recommendationDownloadAccess(qwenVl, "SM8550").experimental)
    }

    @Test
    fun statusLabelsExposeOnlyTheThreeProductStates() {
        assertEquals("已验证", recommendationStatusLabel(RecommendedModelStatus.RECOMMENDED))
        assertEquals("实验", recommendationStatusLabel(RecommendedModelStatus.EXPERIMENTAL))
        assertEquals("实验", recommendationStatusLabel(RecommendedModelStatus.NOT_RECOMMENDED))
        assertEquals("待接入", recommendationStatusLabel(RecommendedModelStatus.PENDING_INTEGRATION))
    }

    @Test
    fun recommendationCardPolicySeparatesHardwareAndEngineeringState() {
        val qwen = ModelScopeClient().recommendedModels().first { it.id == "qwen35_2b_q4" }

        assertEquals("硬件适配：建议 6GB+ · 适合本机", recommendationHardwareLine(qwen, "适合本机"))
        assertEquals(
            "验证状态：MNN 文本与图文链路已通过代表机型回归；兼容 ARM64 设备默认开放",
            recommendationVerificationLine(qwen, qairtVerified = false)
        )
        assertEquals("下载策略：ModelScope / 国内镜像优先", RECOMMENDATION_DOWNLOAD_SOURCE_POLICY)
    }

    @Test
    fun verifiedQairtCardsReflectTheCompletedEliteRegressions() {
        val allRecommendations = ModelScopeClient().recommendedModels()
        val qwenVl = allRecommendations.first { it.id == "qwen3_vl_4b_qairt_w4a16" }
        val qwenText = allRecommendations.first { it.id == "qwen3_4b_2507_qairt_w4a16" }

        assertEquals(
            "验证状态：当前设备冷态、连续图文、Local API 与取消恢复已通过",
            recommendationVerificationLine(qwenVl, qairtVerified = true)
        )
        assertEquals(
            "验证状态：当前设备十轮文本、Local API 与二次加载已通过",
            recommendationVerificationLine(qwenText, qairtVerified = true)
        )
    }

    @Test
    fun qnnCatalogTitlesIncludeTheEstablishedRuntimeVersion() {
        val expectedTitles = mapOf(
            "cyberrealistic_sd15_qnn228" to "CyberRealistic SD1.5 QNN 2.28",
            "realisticvisionhyper_sd15_qnn228" to "RealisticVision Hyper SD1.5 QNN 2.28",
            "dreamshaper_sd15_qnn228" to "DreamShaper SD1.5 QNN 2.28",
            "sdxl_base_qnn228" to "SDXL Base QNN 2.28",
            "realismsdxl_dmd2_alt_qnn228" to "RealismSDXL DMD2 ALT QNN 2.28",
            "animagine_xl_v4_qnn228" to "Animagine XL v4 QNN 2.28",
            "cyberrealisticxl_qnn228" to "CyberRealisticXL SDXL QNN 2.28",
            "qualcomm_sd15_gen5_qnn" to "Qualcomm Stable Diffusion 1.5 · 骁龙 8 Elite Gen 5",
            "qualcomm_sd21_gen5_qnn" to "Qualcomm Stable Diffusion 2.1 · 骁龙 8 Elite Gen 5",
            "qualcomm_controlnet_canny_gen5_qnn" to "Qualcomm ControlNet Canny · 骁龙 8 Elite Gen 5"
        )

        expectedTitles.forEach { (id, title) ->
            assertEquals(title, recommendations.first { it.id == id }.title)
        }
    }

    @Test
    fun formatsInternalChipsetCodesAsUserFacingSnapdragonNames() {
        assertEquals("骁龙 8 Gen 2", recommendationDeviceLabel("SM8550"))
        assertEquals("骁龙 8 Gen 3", recommendationDeviceLabel("SM8650P"))
        assertEquals("骁龙 8 Elite", recommendationDeviceLabel("SM8750"))
        assertEquals("骁龙 8 Elite Gen 5", recommendationDeviceLabel("SM8850P"))
        assertEquals("骁龙芯片", recommendationDeviceLabel("SM9999"))
        assertEquals("未识别芯片", recommendationDeviceLabel("MT9999"))
        assertEquals("骁龙 7+ Gen 3", recommendationDeviceLabel("SM7675"))
    }

    private fun catalogFor(chipsetCode: String): RecommendationCatalog =
        buildRecommendationCatalog(
            models = recommendations,
            deviceChipsetCode = chipsetCode,
            deviceTotalRamBytes = totalRamBytes
        )

    private fun expectedIds(section: RecommendedModelSection): List<String> =
        recommendations.filter { it.section == section }
            .sortedWith(compareBy({ it.priority }, { it.id }))
            .map { it.id }

    private fun assertCpuCatalog(catalog: RecommendationCatalog) {
        assertEquals(3, catalog.lightChat.size)
        assertEquals(5, catalog.mainChat.size)
        assertEquals(5, catalog.qualityChat.size)
        assertEquals(13, catalog.lightChat.size + catalog.mainChat.size + catalog.qualityChat.size)
        assertEquals(0, catalog.npuChat.size)
        assertEquals(
            listOf(
                "qwen35_08b_uncensored_mnn",
                "qwen35_2b_abliterated_gguf",
                "gemma4_e2b_uncensored_gguf"
            ),
            catalog.lightChat.map { it.id }
        )
        assertEquals(
            listOf(
                "qwen35_4b_uncensored_mnn",
                "qwen3_vl_4b_abliterated_gguf",
                "qwen3_4b_2507_abliterated_gguf",
                "minicpm_v46_abliterated_gguf",
                "gemma4_e4b_uncensored_gguf"
            ),
            catalog.mainChat.map { it.id }
        )
        assertEquals(
            listOf(
                "qwen35_9b_uncensored_mnn",
                "qwen3_8b_abliterated_gguf",
                "qwen36_35b_a3b_abliterated_mnn",
                "qwen25_vl_7b_abliterated_gguf",
                "gemma4_26b_a4b_abliterated_gguf"
            ),
            catalog.qualityChat.map { it.id }
        )

        assertEquals(7, catalog.cpuImage.size)
        assertEquals(
            listOf(
                "sd_turbo_512_experimental",
                "flux2_klein_4b_q4",
                "sd15_mnn_512_quality",
                "mnn_sana_edit_v2",
                "z_image_turbo_q4",
                "longcat_image_q4",
                "qwen_image_2512_q2"
            ),
            catalog.cpuImage.map { it.id }
        )
    }

    private fun assertNpuImageCatalog(catalog: RecommendationCatalog) {
        assertEquals(
            listOf(
                "cyberrealistic_sd15_qnn228",
                "realisticvisionhyper_sd15_qnn228",
                "dreamshaper_sd15_qnn228",
                "meinamix_sd15_qnn228"
            ),
            catalog.npuImageSd15.map { it.id }
        )
        assertEquals(
            listOf(
                "sdxl_base_qnn228",
                "realismsdxl_dmd2_alt_qnn228",
                "animagine_xl_v4_qnn228",
                "cyberrealisticxl_qnn228"
            ),
            catalog.npuImageSdxl.map { it.id }
        )
        assertEquals(
            listOf(
                "qualcomm_sd15_gen5_qnn",
                "qualcomm_sd21_gen5_qnn",
                "qualcomm_controlnet_canny_gen5_qnn"
            ),
            catalog.npuImageGen5.map { it.id }
        )
        assertEquals(11, catalog.npuImage.size)
    }
}

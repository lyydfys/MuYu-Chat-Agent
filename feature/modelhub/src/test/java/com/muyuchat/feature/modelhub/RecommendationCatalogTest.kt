package com.muyuchat.feature.modelhub

import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.ImageEngineAccelerator
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.RecommendedChatRuntime
import com.muyuchat.core.download.RecommendedModelSection
import com.muyuchat.core.download.RecommendedModelStatus
import com.muyuchat.core.download.downloadEligibilityFor
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationCatalogTest {
    private val recommendations = ModelScopeClient().userFacingRecommendedModels()
    private val totalRamBytes = 16L * 1024L * 1024L * 1024L

    @Test
    fun sm8550ShowsTheCompleteCatalogWithoutChipsetFiltering() {
        val catalog = catalogFor("SM8550")

        assertCpuCatalog(catalog)
        assertEquals(expectedNpuChatIds("SM8550"), catalog.npuChat.map { it.id })
        assertQwenImage21GpuCatalog(catalog)
        assertNpuImageCatalog(catalog)
    }

    @Test
    fun sm8750ShowsTheSameCompleteCatalog() {
        val catalog = catalogFor("SM8750")

        assertCpuCatalog(catalog)
        assertEquals(expectedNpuChatIds("SM8750"), catalog.npuChat.map { it.id })
        assertQwenImage21GpuCatalog(catalog)
        assertNpuImageCatalog(catalog)
    }

    @Test
    fun sdxlQnn228DeclaresV75TargetButRemainsDownloadableOnV79() {
        val sdxl = recommendations.first { it.id == "animagine_xl_v4_qnn228" }
        val profile = requireNotNull(sdxl.imageEngineBundle).requiredRuntimeProfile

        assertEquals("2.28", profile?.qnnSdk)
        assertEquals(75, profile?.htpArch)
        assertFalse(requireNotNull(profile).completeBundleRuntime)

        val access = recommendationDownloadAccess(sdxl, "SM8750P", deviceIsSnapdragon = true)
        assertTrue(access.canDownload)
        assertTrue(access.experimental)
        assertTrue(recommendationQnnCompatibilityLine(sdxl, "SM8750P")!!.contains("HTP V79"))
        assertTrue(recommendationQnnCompatibilityLine(sdxl, "SM8750P")!!.contains("HTP V75"))
        assertEquals(null, recommendationQnnCompatibilityLine(sdxl, ""))
        assertEquals(null, recommendationQnnCompatibilityLine(sdxl, "SM8650"))
    }

    @Test
    fun sm8850ShowsTheSameCompleteCatalog() {
        val catalog = catalogFor("SM8850")

        assertCpuCatalog(catalog)
        assertEquals(expectedNpuChatIds("SM8850"), catalog.npuChat.map { it.id })
        assertQwenImage21GpuCatalog(catalog)
        assertNpuImageCatalog(catalog)
    }

    @Test
    fun nonSnapdragonAndUnknownChipsetsStillShowNpuCards() {
        listOf("MT6989", "").forEach { chipset ->
            val catalog = catalogFor(chipset)

            assertCpuCatalog(catalog)
            assertEquals(expectedNpuChatIds(chipset), catalog.npuChat.map { it.id })
            assertQwenImage21GpuCatalog(catalog)
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
            catalog.gpuImage,
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
    fun imageRecommendationReflectsInstalledBundleVerificationState() {
        val model = recommendations.first { it.id == "cyberrealistic_sd15_qnn228" }
        fun local(status: String) = LocalImageModelUiItem(
            id = "local-cyber",
            displayName = model.title,
            runtimeLabel = "骁龙 NPU",
            familyLabel = "SD1.5",
            fileName = "unet.bin",
            sizeBytes = 1L,
            imageSize = "512x512",
            recommendationId = model.id,
            verificationStatus = status
        )
        assertEquals(RecommendedLocalBundleStatus.NONE, recommendedLocalBundleStatus(model, emptyList()))
        assertEquals(RecommendedLocalBundleStatus.INSTALLED_UNVERIFIED, recommendedLocalBundleStatus(model, listOf(local("UNKNOWN"))))
        assertEquals(RecommendedLocalBundleStatus.INSTALLED_VERIFIED, recommendedLocalBundleStatus(model, listOf(local("QNN_IMAGE_SMOKE_PASSED"))))
        assertEquals(RecommendedLocalBundleStatus.VERIFICATION_FAILED, recommendedLocalBundleStatus(model, listOf(local("FAILED"))))
    }

    @Test
    fun chatRecommendationMatchesManagedBundleBySourceIdentityAndRuntime() {
        val mnn = recommendations.first { it.mnnModelBundle != null }
        val bundle = requireNotNull(mnn.mnnModelBundle)
        val managedMnn = ModelManifest(
            id = "mnn-local",
            displayName = "renamed by user",
            path = "/models/mnn",
            runtime = ChatModelRuntime.MNN,
            source = ModelSource.MODELSCOPE,
            repoId = bundle.repoId,
            revision = bundle.revision,
            fileName = "managed-bundle",
            sizeBytes = 1L,
            sha256 = "mnn-sha"
        )
        assertEquals(managedMnn, recommendedLocalChatModel(mnn, listOf(managedMnn)))
        assertEquals(null, recommendedLocalChatModel(mnn, listOf(managedMnn.copy(repoId = "other/repo"))))

        val gguf = recommendations.first {
            it.chatRuntime == RecommendedChatRuntime.GGUF && it.kind == com.muyuchat.core.download.ModelScopeRecommendedKind.CHAT
        }
        val managedGguf = ModelManifest(
            id = "gguf-local",
            displayName = gguf.title,
            path = "/models/${gguf.recommendedFileName}",
            runtime = ChatModelRuntime.LLAMA_CPP,
            source = ModelSource.HUGGING_FACE,
            repoId = gguf.repoId,
            revision = gguf.revision,
            fileName = gguf.recommendedFileName.substringAfterLast('/'),
            sizeBytes = 1L,
            sha256 = "gguf-sha"
        )
        assertEquals(managedGguf, recommendedLocalChatModel(gguf, listOf(managedGguf)))
    }

    @Test
    fun remoteFileFilterSearchesLoadedResultsWithoutChangingBlankQueryOrder() {
        val files = listOf(
            RemoteModelFile(
                repoId = "demo/model",
                revision = "main",
                path = "mnn/tokenizer.txt",
                name = "tokenizer.txt",
                downloadUrl = "https://example.invalid/tokenizer.txt",
                provider = ModelRepositoryProvider.MODELSCOPE
            ),
            RemoteModelFile(
                repoId = "demo/model",
                revision = "main",
                path = "gguf/model-q4.gguf",
                name = "model-q4.gguf",
                downloadUrl = "https://example.invalid/model-q4.gguf",
                provider = ModelRepositoryProvider.HUGGING_FACE
            )
        )

        assertEquals(files, filterRemoteModelFiles(files, ""))
        assertEquals(listOf(files[0]), filterRemoteModelFiles(files, "TOKENIZER"))
        assertEquals(listOf(files[1]), filterRemoteModelFiles(files, "hugging face"))
        assertEquals(listOf(files[1]), filterRemoteModelFiles(files, ".gguf"))
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
    fun remoteMediaTekImageStaysVisibleAndDownloadableOnEveryDevice() {
        val model = recommendations.first { it.id == "mediatek_anima_npu_np9" }
        val mediatek = recommendationDownloadAccess(model, "MT6993")
        val unknown = recommendationDownloadAccess(model, "")
        val snapdragon = recommendationDownloadAccess(model, "SM8750", deviceIsSnapdragon = true)
        val mediatekEligibility = model.downloadEligibilityFor("MT6993")
        val unknownEligibility = model.downloadEligibilityFor("")
        val snapdragonEligibility = model.downloadEligibilityFor("SM8750", deviceIsSnapdragon = true)

        assertEquals(RecommendedModelStatus.EXPERIMENTAL, model.status)
        assertEquals(ImageEngineAccelerator.MEDIATEK_NPU, model.plannedImageAccelerator)
        assertEquals(null, model.imageEngineBundle)
        assertEquals(RecommendedModelSection.NPU_IMAGE, model.section)
        assertTrue(model.downloadable)
        assertFalse(model.manualImportRequired)
        assertTrue(requireNotNull(model.remoteImageBundle).variants.isNotEmpty())
        assertTrue(mediatek.canDownload)
        assertTrue(unknown.canDownload)
        assertTrue(snapdragon.canDownload)
        assertTrue(mediatek.experimental)
        assertTrue(unknown.experimental)
        assertTrue(snapdragon.experimental)
        assertEquals(null, mediatekEligibility.blockedReason)
        assertEquals(null, unknownEligibility.blockedReason)
        assertEquals(null, snapdragonEligibility.blockedReason)
        listOf("MT6993", "").forEach { chipset ->
            assertTrue(catalogFor(chipset).npuImageMediaTek.any { it.id == model.id })
        }
    }

    @Test
    fun deviceFitRanksNpuPackagesButNeverTurnsARecommendationIntoAnAdmissionGate() {
        val npu = recommendations.first { it.id == "gemma4_e2b_litertlm_npu" }

        val snapdragon = recommendationDownloadAccess(npu, "SM8850", deviceIsSnapdragon = true)
        assertTrue(snapdragon.canDownload)
        assertEquals(RecommendationDeviceFit.VENDOR_GENERIC, snapdragon.deviceFit)

        val nonSnapdragon = recommendationDownloadAccess(npu, "MT6989", deviceIsSnapdragon = false)
        assertTrue(nonSnapdragon.canDownload)
        assertEquals(RecommendationDeviceFit.CROSS_VENDOR, nonSnapdragon.deviceFit)

        val unknown = recommendationDownloadAccess(npu, "", deviceIsSnapdragon = false)
        assertTrue(unknown.canDownload)
        assertEquals(RecommendationDeviceFit.UNKNOWN, unknown.deviceFit)

        val cpu = recommendations.first {
            it.chatRuntime == RecommendedChatRuntime.LITERT_LM && it.id == "gemma4_e2b_litertlm_cpu"
        }
        assertEquals(
            RecommendationDeviceFit.UNIVERSAL,
            recommendationDownloadAccess(cpu, "MT6989", deviceIsSnapdragon = false).deviceFit
        )
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
    fun splitSdxlCardsStayOpenAndDescribeTheirImageTaskAndDimensions() {
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
                    "下载",
                    recommendationDownloadCtaLabel(access.canDownload)
                )
            }
            assertEquals("文生图 · 1024×1024", recommendationCapabilityLine(model))
        }
    }

    @Test
    fun internalAcceptanceDoesNotChangeDownloadLabel() {
        val realisticVision = recommendations.first { it.id == "realisticvisionhyper_sd15_qnn228" }
        val access = recommendationDownloadAccess(realisticVision, "SM8550", deviceIsSnapdragon = true)

        assertTrue(access.canDownload)
        assertEquals(
            "下载",
            recommendationDownloadCtaLabel(access.canDownload)
        )
        assertEquals(
            "文生图 · 512×512",
            recommendationCapabilityLine(realisticVision)
        )
    }

    @Test
    fun sharedSd15CardsDescribeImageTaskAndDimensions() {
        listOf(
            "cyberrealistic_sd15_qnn228",
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228",
            "meinamix_sd15_qnn228"
        ).forEach { id ->
            val model = recommendations.first { it.id == id }
            assertEquals("文生图 · 512×512", recommendationCapabilityLine(model))
        }
    }

    @Test
    fun collapsedTierShowsOnlyItsApprovedFirstModelWithoutReordering() {
        val catalog = catalogFor("SM8750")

        assertEquals(
            listOf("qwen35_08b_mnn36"),
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
        assertEquals(
            listOf("qwen3_vl_4b_qairt_w4a16"),
            collapsedRecommendationModels(catalog.npuChat).map { it.id }
        )
        assertEquals(listOf(catalog.cpuImage.first().id), collapsedRecommendationModels(catalog.cpuImage).map { it.id })
        assertEquals(listOf("qwen_image_21_mnn_opencl"), collapsedRecommendationModels(catalog.gpuImage).map { it.id })
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
        assertEquals("下载", recommendationDownloadCtaLabel(access.canDownload))
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
    fun internalAcceptanceStateDoesNotBlockDownloadsOrExposeAnExperimentalAction() {
        val source = recommendations.first { it.id == "gemma4_e2b_litertlm_cpu" }
        RecommendedModelStatus.entries.forEach { status ->
            val model = source.copy(status = status)
            val access = recommendationDownloadAccess(model, "")
            assertTrue(access.canDownload)
            assertEquals("下载", recommendationDownloadCtaLabel(access.canDownload))
        }
        assertEquals("暂不可下载", recommendationDownloadCtaLabel(false))
    }

    @Test
    fun recommendationCardDescribesMemoryAndCapabilities() {
        val qwen = ModelScopeClient().recommendedModels().first { it.id == "qwen35_2b_q4" }

        assertEquals("建议内存：6 GB 及以上 · 适合本机", recommendationHardwareLine(qwen, "适合本机"))
        assertEquals(
            "文本聊天 · 图片理解",
            recommendationCapabilityLine(qwen)
        )
    }

    @Test
    fun deviceFitLabelsUseUserFacingHardwareLanguage() {
        assertEquals("通用设备", RecommendationDeviceFit.UNIVERSAL.label)
        assertEquals("芯片匹配", RecommendationDeviceFit.EXACT.label)
        assertEquals("骁龙设备", RecommendationDeviceFit.VENDOR_GENERIC.label)
        assertEquals("其他芯片平台", RecommendationDeviceFit.CROSS_VENDOR.label)
        assertEquals("设备信息未识别", RecommendationDeviceFit.UNKNOWN.label)
    }

    @Test
    fun qairtCardsDistinguishTextAndVisionCapabilities() {
        val allRecommendations = ModelScopeClient().recommendedModels()
        val qwenVl = allRecommendations.first { it.id == "qwen3_vl_4b_qairt_w4a16" }
        val qwenText = allRecommendations.first { it.id == "qwen3_4b_2507_qairt_w4a16" }

        assertEquals(
            "文本聊天 · 图片理解",
            recommendationCapabilityLine(qwenVl)
        )
        assertEquals(
            "文本聊天",
            recommendationCapabilityLine(qwenText)
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

    /**
     * NPU chat cards rank by device fit before priority (see RecommendationCatalog):
     * a universal ALL_DEVICES package outranks cross-vendor/unknown matches, while an exact
     * chipset match or a Snapdragon-family vendor match outranks the universal package.
     * qwen3_06b_qairt_w4a16 is the only ALL_DEVICES NPU chat package; the four QAIRT
     * family packages declare the SM8750/SM8850 family.
     */
    private fun expectedNpuChatIds(chipsetCode: String): List<String> {
        val qairtFamily = listOf(
            "qwen3_vl_4b_qairt_w4a16",
            "qwen3_4b_2507_qairt_w4a16",
            "qwen3_8b_qairt_w4a16",
            "qwen25_vl_7b_qairt_w4a16"
        )
        val universalLightweight = "qwen3_06b_qairt_w4a16"
        val universalGenieXLlama = "ling30_tiny_qnn"
        return when (chipsetCode) {
            "SM8550", "SM8750", "SM8850" ->
                qairtFamily + listOf(universalGenieXLlama, universalLightweight)
            else -> listOf(universalGenieXLlama, universalLightweight) + qairtFamily
        }
    }

    private fun assertCpuCatalog(catalog: RecommendationCatalog) {
        assertEquals(6, catalog.lightChat.size)
        assertEquals(4, catalog.mainChat.size)
        assertEquals(5, catalog.qualityChat.size)
        assertEquals(15, catalog.lightChat.size + catalog.mainChat.size + catalog.qualityChat.size)
        assertEquals(6, catalog.npuChat.size)
        assertEquals(
            listOf(
                "gemma4_e2b_litertlm_npu",
                "gemma4_e4b_litertlm_npu",
                "gemma4_12b_litertlm_npu"
            ),
            catalog.litertNpu.map { it.id }
        )
        assertEquals(
            listOf(
                "qwen35_08b_mnn36",
                "qwen35_2b_abliterated_gguf",
                "gemma4_e2b_uncensored_gguf",
                "gemma4_e2b_litertlm_cpu",
                "gemma4_e2b_litertlm_gpu",
                "qwen35_08b_uncensored_mnn"
            ),
            catalog.lightChat.map { it.id }
        )
        assertEquals(
            listOf(
                "qwen35_4b_uncensored_mnn",
                "gemma4_e4b_uncensored_gguf",
                "gemma4_e4b_litertlm_cpu",
                "gemma4_e4b_litertlm_gpu"
            ),
            catalog.mainChat.map { it.id }
        )
        assertEquals(
            listOf(
                "qwen35_9b_uncensored_mnn",
                "qwen35_35b_a3b_iq2_xxs",
                "gemma4_26b_a4b_abliterated_gguf",
                "gemma4_12b_litertlm_cpu",
                "gemma4_12b_litertlm_gpu"
            ),
            catalog.qualityChat.map { it.id }
        )
        // npuChat order depends on device fit first: the declared SM8750/SM8850 family
        // packages rank EXACT/VENDOR_GENERIC on Snapdragon devices, while the universal
        // ALL_DEVICES lightweight package (qwen3_06b_qairt_w4a16) ranks UNIVERSAL. The exact
        // id order is therefore asserted per device via expectedNpuChatIds(...) at each call
        // site instead of once here.

        assertEquals(9, catalog.cpuImage.size)
        assertEquals(
            listOf(
                "sd_turbo_512_experimental",
                "flux2_klein_4b_q4",
                "sd15_mnn_512_quality",
                "mnn_sana_edit_v2",
                "z_image_turbo_q4",
                "longcat_image_q4",
                "qwen_image_21_q4_k_m",
                "anima_preview_q4",
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
        assertEquals(listOf("mediatek_anima_npu_np9"), catalog.npuImageMediaTek.map { it.id })
        assertEquals(12, catalog.npuImage.size)
    }

    private fun assertQwenImage21GpuCatalog(catalog: RecommendationCatalog) {
        assertEquals(listOf("qwen_image_21_mnn_opencl"), catalog.gpuImage.map { it.id })
        assertTrue(catalog.cpuImage.none { it.id == "qwen_image_21_mnn_opencl" })
        assertEquals(
            RecommendedModelSection.GPU_IMAGE,
            recommendations.first { it.id == "qwen_image_21_mnn_opencl" }.section
        )
        assertTrue(
            recommendationDownloadAccess(
                recommendations.first { it.id == "qwen_image_21_mnn_opencl" },
                "",
                deviceIsSnapdragon = false
            ).canDownload
        )
    }
}

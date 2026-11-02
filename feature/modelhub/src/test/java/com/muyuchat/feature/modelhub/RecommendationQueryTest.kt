package com.muyuchat.feature.modelhub

import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.ModelScopeRecommendedKind
import com.muyuchat.core.download.RecommendedChatRuntime
import com.muyuchat.core.download.RecommendedComputeBackend
import com.muyuchat.core.download.RecommendedModelSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推荐页纯筛选/排序逻辑。
 *
 * 覆盖方案要求的：默认顺序等值、任务/路线分类、同维度 OR 与跨维度 AND、条件计数、重置、
 * 零结果、排序稳定性与未知元数据末位。全部用真实的类型化目录数据，不解析名称或说明文字。
 */
class RecommendationQueryTest {
    private val recommendations = ModelScopeClient().userFacingRecommendedModels()
    private val totalRamBytes = 16L * 1024L * 1024L * 1024L

    private fun catalogFor(chipsetCode: String = "SM8750"): RecommendationCatalog =
        buildRecommendationCatalog(
            models = recommendations,
            deviceChipsetCode = chipsetCode,
            deviceTotalRamBytes = totalRamBytes,
            deviceIsSnapdragon = chipsetCode.startsWith("SM")
        )

    private fun RecommendationCatalog.ids(): List<String> =
        visibleModelsInCatalogOrder().map { it.id }

    // ── 默认状态与重置 ────────────────────────────────────────────────────

    @Test
    fun defaultQueryReturnsTheUnchangedCatalogInstance() {
        val catalog = catalogFor()
        val applied = filterRecommendationCatalog(catalog, RecommendationQuery())

        assertSame("无筛选时必须原样返回既有目录，不能复制或重排", catalog, applied)
        assertTrue(RecommendationQuery().isDefault)
        assertEquals(0, RecommendationQuery().filterCount)
    }

    @Test
    fun defaultOrderFollowsTheExistingGroupOrderWithoutDuplicates() {
        val catalog = catalogFor()
        val ids = catalog.ids()

        assertEquals("目录顺序必须从原有第一个分组开始", catalog.lightChat.first().id, ids.first())
        assertEquals("目录顺序必须以天玑 NPU 分组结束", catalog.npuImageMediaTek.last().id, ids.last())
        assertEquals(
            "LiteRT-LM 的 CPU/GPU 条目会同时出现在聊天档与 LiteRT 分组，平铺时必须去重",
            ids.size,
            ids.distinct().size
        )
        val beforeImage = (catalog.lightChat + catalog.mainChat + catalog.qualityChat +
            catalog.npuChat + catalog.litertNpu).map { it.id }.distinct()
        assertEquals(
            "CPU 聊天三档、NPU 聊天、LiteRT-LM NPU 必须保持原有先后顺序",
            beforeImage,
            ids.take(beforeImage.size)
        )
    }

    @Test
    fun everyVisibleModelIsReachableFromTheDefaultQuery() {
        val catalog = catalogFor()

        assertEquals(catalog.ids().size, catalog.matchingModelCount(RecommendationQuery()))
        assertEquals(
            "默认查询的平铺顺序就是目录顺序，不生成跨路线总榜",
            catalog.ids(),
            catalog.flatEntries(RecommendationQuery()).map { it.model.id }
        )
    }

    @Test
    fun defaultQueryTokenRoundTripsAndDamageFallsBackToDefault() {
        val queries = listOf(
            RecommendationQuery(),
            RecommendationQuery(
                tasks = setOf(RecommendationTask.IMAGE),
                backends = setOf(RecommendationBackend.NPU, RecommendationBackend.CPU),
                ramTiers = setOf(8, 12),
                sort = RecommendationSort.MINIMUM_RAM_ASC
            )
        )

        queries.forEach { query ->
            assertEquals(query, recommendationQueryFromToken(query.toStorageToken()))
        }
        // 未知/损坏内容退回默认状态，而不是崩溃或伪造条件。
        assertEquals(RecommendationQuery(), recommendationQueryFromToken("nonsense|values|here|oops"))
        assertEquals(RecommendationQuery(), recommendationQueryFromToken(null))
        assertEquals(RecommendationQuery(), recommendationQueryFromToken(""))
    }

    // ── 任务与运行路线来自类型化字段 ──────────────────────────────────────

    @Test
    fun visionChatCountsAsChatInsteadOfImage() {
        // 带视觉能力的聊天模型必须仍然归入“聊天”，不能因为能看图就被判成“生图”。
        val visionChats = recommendations.filter { recommendationCapabilityLine(it).contains("图片理解") }
        assertTrue("前提：目录里存在带视觉能力的聊天模型", visionChats.isNotEmpty())
        visionChats.forEach { vision ->
            assertEquals(
                "带视觉能力的聊天不得被判成生图（${vision.id}）",
                RecommendationTask.CHAT,
                vision.recommendationTask()
            )
        }

        val chatOnly = catalogFor().flatEntries(RecommendationQuery(tasks = setOf(RecommendationTask.CHAT)))
        visionChats.forEach { vision ->
            assertTrue("视觉聊天必须出现在聊天筛选里（${vision.id}）", chatOnly.any { it.model.id == vision.id })
        }
    }

    @Test
    fun taskFilterSplitsImageFromChatByKind() {
        val catalog = catalogFor()

        val imageOnly = catalog.flatEntries(RecommendationQuery(tasks = setOf(RecommendationTask.IMAGE)))
        assertTrue(imageOnly.isNotEmpty())
        assertTrue(
            "生图筛选只能包含 type=IMAGE 的条目",
            imageOnly.all { it.model.kind == ModelScopeRecommendedKind.IMAGE }
        )
        assertEquals(
            "生图筛选必须覆盖目录里所有生图条目",
            catalog.visibleModelsInCatalogOrder().count { it.kind == ModelScopeRecommendedKind.IMAGE },
            imageOnly.size
        )

        val chatOnly = catalog.flatEntries(RecommendationQuery(tasks = setOf(RecommendationTask.CHAT)))
        assertTrue("聊天筛选不得混入生图模型", chatOnly.none { it.model.kind == ModelScopeRecommendedKind.IMAGE })
        assertEquals("两个互斥任务必须刚好覆盖完整目录", catalog.ids().size, imageOnly.size + chatOnly.size)
    }

    @Test
    fun backendsComeFromTypedRouteFields() {
        val byId = recommendations.associateBy { it.id }

        assertEquals(RecommendationBackend.GPU, byId.getValue("gemma4_e2b_litertlm_gpu").recommendationBackend())
        assertEquals(RecommendationBackend.CPU, byId.getValue("gemma4_e2b_litertlm_cpu").recommendationBackend())
        assertEquals(RecommendationBackend.NPU, byId.getValue("gemma4_e2b_litertlm_npu").recommendationBackend())
        assertEquals(RecommendationBackend.NPU, byId.getValue("qwen3_vl_4b_qairt_w4a16").recommendationBackend())
        assertEquals(RecommendationBackend.NPU, byId.getValue("cyberrealistic_sd15_qnn228").recommendationBackend())
        assertEquals(RecommendationBackend.NPU, byId.getValue("mediatek_anima_npu_np9").recommendationBackend())
        assertEquals(RecommendationBackend.GPU, byId.getValue("qwen_image_21_mnn_opencl").recommendationBackend())

        val mnn = recommendations.first {
            it.chatRuntime == RecommendedChatRuntime.MNN && it.computeBackend == RecommendedComputeBackend.CPU
        }
        assertEquals(RecommendationBackend.CPU, mnn.recommendationBackend())
        assertEquals(RecommendedModelSection.GPU_IMAGE, byId.getValue("qwen_image_21_mnn_opencl").section)
    }

    @Test
    fun backendFilterMatchesOnlyTheDeclaredRoute() {
        val catalog = catalogFor()

        RecommendationBackend.entries.forEach { backend ->
            val entries = catalog.flatEntries(RecommendationQuery(backends = setOf(backend)))
            assertTrue("$backend 必须有结果", entries.isNotEmpty())
            assertTrue("$backend 只能包含该路线的模型", entries.all { it.backend == backend })
        }
    }

    @Test
    fun plannedMediaTekAnimaMatchesImageAndNpuFiltersAndCountsAsOneRecommendation() {
        val catalog = catalogFor()
        val query = RecommendationQuery(
            tasks = setOf(RecommendationTask.IMAGE),
            backends = setOf(RecommendationBackend.NPU)
        )
        val entries = catalog.flatEntries(query)

        assertTrue(entries.any { it.model.id == "mediatek_anima_npu_np9" })
        assertEquals(catalog.matchingModelCount(query), entries.size)
    }

    // ── 同维度 OR / 跨维度 AND / 计数 / 零结果 ────────────────────────────

    @Test
    fun optionsInsideOneDimensionAreOrAndDimensionsAreAnd() {
        val catalog = catalogFor()
        val cpu = catalog.flatEntries(RecommendationQuery(backends = setOf(RecommendationBackend.CPU)))
        val npu = catalog.flatEntries(RecommendationQuery(backends = setOf(RecommendationBackend.NPU)))
        val both = catalog.flatEntries(
            RecommendationQuery(backends = setOf(RecommendationBackend.CPU, RecommendationBackend.NPU))
        )

        assertEquals("同一维度的多选项必须是并集", cpu.size + npu.size, both.size)

        val combined = catalog.flatEntries(
            RecommendationQuery(
                tasks = setOf(RecommendationTask.IMAGE),
                backends = setOf(RecommendationBackend.NPU)
            )
        )
        assertTrue(
            "不同维度之间必须同时满足",
            combined.all { it.task == RecommendationTask.IMAGE && it.backend == RecommendationBackend.NPU }
        )
        assertTrue(combined.size <= both.size)
        assertTrue(
            combined.size <= catalog.flatEntries(RecommendationQuery(tasks = setOf(RecommendationTask.IMAGE))).size
        )
    }

    @Test
    fun filterCountCountsAppliedConditionsButNotTheSort() {
        assertEquals(0, RecommendationQuery().filterCount)
        assertEquals(1, RecommendationQuery(tasks = setOf(RecommendationTask.IMAGE)).filterCount)
        assertEquals(
            3,
            RecommendationQuery(
                tasks = setOf(RecommendationTask.IMAGE),
                backends = setOf(RecommendationBackend.NPU),
                ramTiers = setOf(8)
            ).filterCount
        )
        assertEquals("排序不是筛选条件", 0, RecommendationQuery(sort = RecommendationSort.DOWNLOAD_SIZE_ASC).filterCount)
        assertEquals("筛选", recommendationFilterChipLabel(RecommendationQuery()))
        assertEquals(
            "筛选 · 2",
            recommendationFilterChipLabel(
                RecommendationQuery(tasks = setOf(RecommendationTask.IMAGE), ramTiers = setOf(8))
            )
        )
    }

    @Test
    fun emptyResultIsCountableAndNotAnEmptyCatalog() {
        val catalog = catalogFor()
        val noMatch = RecommendationQuery(
            tasks = setOf(RecommendationTask.IMAGE),
            backends = setOf(RecommendationBackend.CPU),
            ramTiers = setOf(99)
        )

        assertEquals(0, catalog.matchingModelCount(noMatch))
        assertTrue(catalog.flatEntries(noMatch).isEmpty())
        assertTrue("目录本身仍有推荐，零结果只是筛选结果为空", catalog.ids().isNotEmpty())
        assertEquals("显示 0 个模型", recommendationResultCountLabel(0))
    }

    @Test
    fun clearingFiltersKeepsTheChosenSort() {
        val query = RecommendationQuery(
            tasks = setOf(RecommendationTask.IMAGE),
            backends = setOf(RecommendationBackend.NPU),
            sort = RecommendationSort.MINIMUM_RAM_ASC
        )

        val cleared = query.withoutFilters()
        assertEquals(0, cleared.filterCount)
        assertEquals(RecommendationSort.MINIMUM_RAM_ASC, cleared.sort)
        assertEquals(
            "清除筛选只应影响筛选维度，不应改变排序选择",
            query.toStorageToken().substringAfterLast('|'),
            cleared.toStorageToken().substringAfterLast('|')
        )
    }

    // ── 资源档 ────────────────────────────────────────────────────────────

    @Test
    fun ramTiersComeFromDeclaredMetadataAndUnknownIsSeparate() {
        val catalog = catalogFor()
        val tiers = catalog.availableRamTiers()
        val declared = catalog.visibleModelsInCatalogOrder()
            .map { it.minRamGb }
            .filter { it > 0 }
            .distinct()
            .sorted()

        assertEquals(declared, tiers.filter { it != RECOMMENDATION_RAM_TIER_UNKNOWN })
        assertEquals(
            "未标注必须单独排在最后",
            tiers.filter { it == RECOMMENDATION_RAM_TIER_UNKNOWN }.size,
            tiers.takeLastWhile { it == RECOMMENDATION_RAM_TIER_UNKNOWN }.size
        )
        assertEquals("8 GB", recommendationRamTierLabel(8))
        assertEquals("未标注", recommendationRamTierLabel(RECOMMENDATION_RAM_TIER_UNKNOWN))
    }

    @Test
    fun undeclaredRamTierIsSelectableAndNeverHidesTheModelFromAll() {
        val catalog = catalogFor()
        val firstLight = catalog.lightChat.first()
        val withUnknown = catalog.copy(
            lightChat = catalog.lightChat.map { if (it.id == firstLight.id) it.copy(minRamGb = 0) else it }
        )
        val unknownIds = withUnknown.visibleModelsInCatalogOrder()
            .filter { it.minRamGb <= 0 }
            .map { it.id }

        assertTrue("未知内存仍必须出现在完整目录里", withUnknown.ids().contains(firstLight.id))
        assertEquals(RECOMMENDATION_RAM_TIER_UNKNOWN, withUnknown.availableRamTiers().last())
        assertEquals(
            unknownIds,
            withUnknown.flatEntries(RecommendationQuery(ramTiers = setOf(RECOMMENDATION_RAM_TIER_UNKNOWN)))
                .map { it.model.id }
        )
        val sortedIds = withUnknown
            .flatEntries(RecommendationQuery(sort = RecommendationSort.MINIMUM_RAM_ASC))
            .map { it.model.id }
        assertEquals(
            "未标注内存模型必须整体排在最后",
            unknownIds.toSet(),
            sortedIds.takeLast(unknownIds.size).toSet()
        )
    }

    // ── 排序稳定性 ────────────────────────────────────────────────────────

    @Test
    fun downloadSizeSortIsAscendingWithUnknownLastAndStableTies() {
        val catalog = catalogFor()
        val entries = catalog.flatEntries(RecommendationQuery(sort = RecommendationSort.DOWNLOAD_SIZE_ASC))
        val sizes = entries.map { recommendationDownloadSizeBytes(it.model) }
        val known = sizes.count { it != null }

        assertEquals("排序不得增删条目", catalog.ids().size, entries.size)
        assertTrue("已知体积必须全部排在前面", sizes.take(known).all { it != null })
        assertTrue("体积未知的条目必须全部排在末尾", sizes.drop(known).all { it == null })
        val knownSizes = sizes.take(known).filterNotNull()
        assertEquals(knownSizes.sorted(), knownSizes)
        assertTrue("体积排序必须真的用到可靠数据", known > 0)
        assertStableTieBreaks(entries) { recommendationDownloadSizeBytes(it.model) }
    }

    @Test
    fun minimumRamSortIsAscendingWithUnknownLastAndStableTies() {
        val catalog = catalogFor()
        val entries = catalog.flatEntries(RecommendationQuery(sort = RecommendationSort.MINIMUM_RAM_ASC))
        val rams = entries.map { it.model.recommendationRamTier() }
        val known = rams.count { it != RECOMMENDATION_RAM_TIER_UNKNOWN }

        assertEquals(catalog.ids().size, entries.size)
        assertTrue(rams.drop(known).all { it == RECOMMENDATION_RAM_TIER_UNKNOWN })
        assertEquals(rams.take(known).sorted(), rams.take(known))
        assertStableTieBreaks(entries) { it.model.recommendationRamTier() }
    }

    @Test
    fun catalogSortIsNotReorderedByTheComparisonSorts() {
        val catalog = catalogFor()
        val catalogOrder = catalog.flatEntries(RecommendationQuery()).map { it.model.id }

        assertEquals(
            catalogOrder,
            catalog.flatEntries(RecommendationQuery(sort = RecommendationSort.CATALOG)).map { it.model.id }
        )
        assertNotEquals(
            "显式选择全局比较排序时必须真的改变顺序，否则说明排序没有生效",
            catalogOrder,
            catalog.flatEntries(RecommendationQuery(sort = RecommendationSort.MINIMUM_RAM_ASC)).map { it.model.id }
        )
    }

    @Test
    fun summaryLineStatesTheComparisonAndItsUnknownRule() {
        assertTrue(recommendationSortSummaryLine(RecommendationSort.DOWNLOAD_SIZE_ASC, 42).contains("下载体积"))
        assertTrue(recommendationSortSummaryLine(RecommendationSort.DOWNLOAD_SIZE_ASC, 42).contains("末尾"))
        assertTrue(recommendationSortSummaryLine(RecommendationSort.MINIMUM_RAM_ASC, 42).contains("最低内存"))
        assertTrue(recommendationSortSummaryLine(RecommendationSort.MINIMUM_RAM_ASC, 42).contains("42"))
    }

    /** 相同比较值时用既有 priority 与稳定 id 决胜，结果与输入顺序无关。 */
    private fun assertStableTieBreaks(
        entries: List<RecommendationFlatEntry>,
        key: (RecommendationFlatEntry) -> Any?
    ) {
        entries.groupBy(key).values.filter { it.size > 1 }.forEach { group ->
            val expected = group.sortedWith(compareBy({ it.model.priority }, { it.model.id })).map { it.model.id }
            assertEquals("相同比较值时必须用既有 priority 与稳定 id 决胜", expected, group.map { it.model.id })
        }
    }

    // ── 未知/跨芯片设备不得缩小可见集合，也不改变主操作 ────────────────────

    @Test
    fun unknownAndCrossVendorDevicesKeepTheSameVisibleModelSet() {
        val baseline = catalogFor("SM8750").ids().toSet()

        listOf("MT6989", "", "SM8550", "SM8850P").forEach { chipset ->
            assertEquals(
                "设备识别不得改变默认可见集合（$chipset）",
                baseline,
                catalogFor(chipset).ids().toSet()
            )
        }
    }

    @Test
    fun filteredModelsStayInsideTheFullCatalogAndKeepTheDownloadPrimaryAction() {
        val catalog = catalogFor("MT6989")
        val allIds = catalog.ids().toSet()
        val queries = listOf(
            RecommendationQuery(tasks = setOf(RecommendationTask.CHAT)),
            RecommendationQuery(backends = setOf(RecommendationBackend.NPU)),
            RecommendationQuery(tasks = setOf(RecommendationTask.IMAGE), backends = setOf(RecommendationBackend.CPU))
        )

        assertEquals(
            "未安装模型的筛选结果仍以下载为主操作",
            RecommendedCardPrimaryAction.DOWNLOAD,
            recommendedCardPrimaryAction(hasLocalImageModel = false, hasLocalChatModel = false)
        )
        queries.forEach { query ->
            val filtered = catalog.flatEntries(query)
            assertTrue("筛选结果必须来自完整目录", filtered.all { it.model.id in allIds })
        }
    }
}

package com.muyuchat.feature.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchDiagnosticEvidenceTest {
    @Test
    fun evidenceTextContainsProviderQueriesSourcesAndMessage() {
        val item = WebSearchDiagnosticUiItem(
            createdAtText = "2026-07-06 10:20",
            providerLabel = "Jina Search",
            triggerModeLabel = "智能",
            query = "MCA 最新说明",
            success = true,
            message = "闭环自检通过：1 个来源",
            sourceCount = 1,
            elapsedMs = 321,
            searchedQueries = listOf("MCA 最新说明", "MCA 最新说明 官方 文档"),
            directUrls = listOf("https://example.com/direct"),
            healthScore = 76,
            healthLabel = "降级",
            healthReasons = listOf("获得 1 个可用来源", "搜索服务限流"),
            qualityScore = 82,
            qualityLabel = "高",
            qualityReasons = listOf("1 个可用来源", "1 个独立站点"),
            sourceTrustSummary = listOf("官方/一手 1 个"),
            triggerReasons = listOf("触发方式为智能判断", "用户明确要求搜索/联网"),
            warnings = listOf("MCA 最新说明 官方 文档：搜索接口返回 429"),
            cacheStatus = "命中本机短缓存",
            closedLoopChecks = listOf("已生成模型联网上下文", "已生成来源卡片数据：1 张"),
            topSources = listOf(
                WebSearchDiagnosticSourceUiItem(
                    title = "MCA Docs",
                    url = "https://example.com/mca",
                    snippet = "联网检索来源摘要",
                    provider = "Jina Search",
                    trustLabel = "官方/一手",
                    hostLabel = "example.com"
                )
            )
        )

        val text = item.toEvidenceText()

        assertTrue(text.contains("MCA 联网检索诊断"))
        assertTrue(text.contains("服务: Jina Search"))
        assertTrue(text.contains("触发: 智能"))
        assertTrue(text.contains("缓存: 命中本机短缓存"))
        assertTrue(text.contains("闭环证据:"))
        assertTrue(text.contains("已生成来源卡片数据"))
        assertTrue(text.contains("资料质量: 高 (82/100)"))
        assertTrue(text.contains("来源类型: 官方/一手 1 个"))
        assertTrue(text.contains("质量依据: 1 个可用来源"))
        assertTrue(text.contains("触发依据:"))
        assertTrue(text.contains("用户明确要求搜索/联网"))
        assertTrue(text.contains("检索警告:"))
        assertTrue(text.contains("搜索接口返回 429"))
        assertTrue(text.contains("检索健康: 降级 (76/100)"))
        assertTrue(text.contains("健康依据: 获得 1 个可用来源；搜索服务限流"))
        assertTrue(text.contains("MCA 最新说明 官方 文档"))
        assertTrue(text.contains("https://example.com/direct"))
        assertTrue(text.contains("https://example.com/mca"))
        assertTrue(text.contains("类型: 官方/一手 · example.com"))
        assertTrue(text.contains("联网检索来源摘要"))
        assertTrue(text.contains("闭环自检通过"))
    }
    @Test
    fun evidenceTextContainsResearchReport() {
        val item = WebSearchDiagnosticUiItem(
            createdAtText = "2026-07-06 11:00",
            providerLabel = "Brave + Tavily",
            triggerModeLabel = "智能",
            query = "research",
            success = true,
            message = "ok",
            sourceCount = 2,
            elapsedMs = 500,
            searchedQueries = listOf("official docs", "benchmark"),
            directUrls = emptyList(),
            qualityScore = 76,
            qualityLabel = "中高",
            qualityReasons = emptyList(),
            triggerReasons = emptyList(),
            warnings = emptyList(),
            researchConfidenceScore = 72,
            researchConfidenceLabel = "中高",
            researchEvidenceGroups = listOf("多角度检索 4 组", "独立站点 2 个"),
            researchConflictWarnings = listOf("来源年份跨度较大：2023-2026"),
            researchSynthesisGuidance = listOf("优先综合官方资料"),
            topSources = emptyList()
        )

        val text = item.toEvidenceText()

        assertTrue(text.contains("研究置信度: 中高 (72/100)"))
        assertTrue(text.contains("证据分组: 多角度检索 4 组"))
        assertTrue(text.contains("冲突/不确定性: 来源年份跨度较大"))
        assertTrue(text.contains("综合建议: 优先综合官方资料"))
    }

    @Test
    fun networkTroubleshootingActionsOnlyShowForNetworkPreflightProblems() {
        assertTrue(
            "网络预检需检查：手机当前没有活动网络，请先连接 Wi-Fi 或移动数据。"
                .shouldShowWebSearchNetworkTroubleshootingActions()
        )
        assertTrue(
            "网络预检需检查：请检查系统设置或安全中心是否禁止 MCA 使用 WLAN/移动数据，并排查 VPN、私人 DNS、代理或省电策略。"
                .shouldShowWebSearchNetworkTroubleshootingActions()
        )
        assertTrue(
            "网络预检需检查：设备无法解析公网域名 example.com，请检查手机网络、DNS、VPN 或代理设置。"
                .shouldShowWebSearchNetworkTroubleshootingActions()
        )
        assertTrue(
            !"网络预检需检查：Brave Search 需要 API Key，当前未填写。"
                .shouldShowWebSearchNetworkTroubleshootingActions()
        )
        assertTrue(
            !"网络预检通过：手机网络、DNS、接口地址和必要 Key 初步可用。"
                .shouldShowWebSearchNetworkTroubleshootingActions()
        )
    }

    @Test
    fun providerSetupGuidanceExplainsRealSearchEndpoints() {
        val brave = webSearchProviderSetupGuidance(
            provider = "BRAVE",
            endpoint = "https://api.search.brave.com",
            apiKey = "",
            publicCheck = false
        )
        val tavily = webSearchProviderSetupGuidance(
            provider = "TAVILY",
            endpoint = "https://api.tavily.com/search",
            apiKey = "key",
            publicCheck = false
        )
        val publicCheck = webSearchProviderSetupGuidance(
            provider = "CUSTOM_JSON",
            endpoint = "https://hn.algolia.com/api/v1/search",
            apiKey = "",
            publicCheck = true
        )

        assertTrue(brave.primary.contains("完整 Web Search API 路径"))
        assertTrue(brave.secondary.contains("X-Subscription-Token"))
        assertTrue(brave.secondary.contains("当前未填写 API Key"))
        assertTrue(tavily.primary.contains("路径正确"))
        assertTrue(tavily.secondary.contains("POST JSON"))
        assertTrue(publicCheck.primary.contains("只适合验证链路"))
        assertTrue(publicCheck.secondary.contains("不是全网搜索"))
    }

    @Test
    fun troubleshootingAdviceTurnsProviderFailuresIntoActions() {
        val item = WebSearchDiagnosticUiItem(
            createdAtText = "2026-07-06 12:00",
            providerLabel = "Brave Search",
            triggerModeLabel = "智能",
            query = "Android AI latest",
            success = false,
            message = "搜索服务全部失败：搜索接口返回 404：接口路径不存在",
            sourceCount = 0,
            elapsedMs = 300,
            searchedQueries = listOf("Android AI latest"),
            directUrls = emptyList(),
            healthScore = 12,
            healthLabel = "失败",
            healthReasons = listOf("接口路径需要检查"),
            qualityScore = 0,
            qualityLabel = "无资料",
            qualityReasons = listOf("没有可用来源"),
            triggerReasons = listOf("触发方式为智能判断"),
            warnings = listOf("Android AI latest：搜索接口返回 401：鉴权失败"),
            topSources = emptyList()
        )

        val advice = item.webSearchTroubleshootingAdvice()
        val evidence = item.toEvidenceText()

        assertTrue(advice.any { it.contains("Web Search API") })
        assertTrue(advice.any { it.contains("API Key") })
        assertTrue(evidence.contains("处理建议:"))
        assertTrue(evidence.contains("X-Subscription-Token"))
    }
}

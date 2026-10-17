package com.muyuchat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

private const val WEB_SEARCH_PUBLIC_CHECK_ENDPOINT = "https://hn.algolia.com/api/v1/search"
private const val WEB_SEARCH_PUBLIC_CHECK_QUERY = "Android AI"
private const val WEB_SEARCH_RESEARCH_CHECK_QUERY = "手机端本地 AI 生图方案优缺点 生态 可行性 最新评测"

@Composable
fun SearchSettingsScreen(
    state: WebSearchSettingsUiState,
    onSave: (WebSearchSettingsDraft) -> Unit,
    onPreflight: (WebSearchSettingsDraft) -> Unit,
    onTest: (String, WebSearchSettingsDraft) -> Unit,
    onTestTurn: (String, WebSearchSettingsDraft, Boolean) -> Unit,
    onClearDiagnostics: () -> Unit,
    onOpenWebPage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var enabled by rememberSaveable(state.enabled) { mutableStateOf(state.enabled) }
    var provider by rememberSaveable(state.provider) { mutableStateOf(state.provider) }
    var endpoint by rememberSaveable(state.endpoint) { mutableStateOf(state.endpoint) }
    var apiKey by rememberSaveable(state.apiKey) { mutableStateOf(state.apiKey) }
    var maxResultsText by rememberSaveable(state.maxResults) { mutableStateOf(state.maxResults.toString()) }
    var fetchPageContent by rememberSaveable(state.fetchPageContent) { mutableStateOf(state.fetchPageContent) }
    var triggerMode by rememberSaveable(state.triggerMode) { mutableStateOf(state.triggerMode) }
    var researchMode by rememberSaveable(state.researchMode) { mutableStateOf(state.researchMode) }
    var testQuery by rememberSaveable { mutableStateOf("MCA 本地 AI") }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var backupExpanded by rememberSaveable { mutableStateOf(false) }
    var testExpanded by rememberSaveable { mutableStateOf(false) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    var helpExpanded by rememberSaveable { mutableStateOf(false) }
    val backup0 = state.backupProviders.getOrNull(0)
    val backup1 = state.backupProviders.getOrNull(1)
    val backup2 = state.backupProviders.getOrNull(2)
    var backup0Enabled by rememberSaveable(backup0?.enabled) { mutableStateOf(backup0?.enabled ?: false) }
    var backup0Provider by rememberSaveable(backup0?.provider) { mutableStateOf(backup0?.provider ?: "SEARXNG") }
    var backup0Endpoint by rememberSaveable(backup0?.endpoint) { mutableStateOf(backup0?.endpoint.orEmpty()) }
    var backup0ApiKey by rememberSaveable(backup0?.apiKey) { mutableStateOf(backup0?.apiKey.orEmpty()) }
    var backup1Enabled by rememberSaveable(backup1?.enabled) { mutableStateOf(backup1?.enabled ?: false) }
    var backup1Provider by rememberSaveable(backup1?.provider) { mutableStateOf(backup1?.provider ?: "SEARXNG") }
    var backup1Endpoint by rememberSaveable(backup1?.endpoint) { mutableStateOf(backup1?.endpoint.orEmpty()) }
    var backup1ApiKey by rememberSaveable(backup1?.apiKey) { mutableStateOf(backup1?.apiKey.orEmpty()) }
    var backup2Enabled by rememberSaveable(backup2?.enabled) { mutableStateOf(backup2?.enabled ?: false) }
    var backup2Provider by rememberSaveable(backup2?.provider) { mutableStateOf(backup2?.provider ?: "SEARXNG") }
    var backup2Endpoint by rememberSaveable(backup2?.endpoint) { mutableStateOf(backup2?.endpoint.orEmpty()) }
    var backup2ApiKey by rememberSaveable(backup2?.apiKey) { mutableStateOf(backup2?.apiKey.orEmpty()) }
    val providerItems = listOf(
        "SEARXNG" to "SearxNG",
        "BRAVE" to "Brave",
        "TAVILY" to "Tavily",
        "JINA" to "Jina",
        "CUSTOM_JSON" to "自定义"
    )
    val providerLabel = providerItems.firstOrNull { it.first == provider }?.second ?: state.providerLabel
    val isPublicCheckDraft = isPublicWebSearchCheckSource(provider, endpoint)
    val providerSetupGuidance = webSearchProviderSetupGuidance(
        provider = provider,
        endpoint = endpoint,
        apiKey = apiKey,
        publicCheck = isPublicCheckDraft
    )
    val endpointPlaceholder = when (provider) {
        "SEARXNG" -> "https://your-searxng.example"
        "BRAVE" -> "https://api.search.brave.com/res/v1/web/search"
        "TAVILY" -> "https://api.tavily.com/search"
        "JINA" -> "https://s.jina.ai"
        else -> "https://your-search-api.example/search"
    }
    val needsApiKey = provider == "BRAVE" || provider == "TAVILY" || provider == "JINA"
    val triggerModes = listOf(
        Triple("SMART", "智能", "按问题判断是否需要联网"),
        Triple("MANUAL", "手动", "只在本轮开关或助手默认开启时联网"),
        Triple("ALWAYS", "始终", "每轮非空问题都会先检索")
    )
    val researchModes = listOf(
        Triple("AUTO", "自动", "命中调研、评测、方案、对比等问题时启用多源研究"),
        Triple("OFF", "普通", "只做轻量检索，不强制扩展为研究查询"),
        Triple("DEEP", "深度", "把当前问题扩展为官方、评测、限制和社区等多角度查询")
    )
    fun currentDraft(): WebSearchSettingsDraft =
        WebSearchSettingsDraft(
            enabled = enabled,
            provider = provider,
            endpoint = endpoint,
            apiKey = apiKey,
            maxResults = maxResultsText.toIntOrNull()?.coerceIn(1, 8) ?: 5,
            fetchPageContent = fetchPageContent,
            triggerMode = triggerMode,
            researchMode = researchMode,
            backupProviders = listOf(
                WebSearchBackupProviderDraft(backup0Enabled, backup0Provider, backup0Endpoint, backup0ApiKey),
                WebSearchBackupProviderDraft(backup1Enabled, backup1Provider, backup1Endpoint, backup1ApiKey),
                WebSearchBackupProviderDraft(backup2Enabled, backup2Provider, backup2Endpoint, backup2ApiKey)
            )
        )
    fun publicCheckDraft(deepResearch: Boolean): WebSearchSettingsDraft =
        WebSearchSettingsDraft(
            enabled = true,
            provider = "CUSTOM_JSON",
            endpoint = WEB_SEARCH_PUBLIC_CHECK_ENDPOINT,
            apiKey = "",
            maxResults = if (deepResearch) 5 else 3,
            fetchPageContent = false,
            triggerMode = "SMART",
            researchMode = if (deepResearch) "DEEP" else "AUTO",
            backupProviders = emptyList()
        )
    fun applyPublicCheckDraft(query: String, deepResearch: Boolean) {
        enabled = true
        provider = "CUSTOM_JSON"
        endpoint = WEB_SEARCH_PUBLIC_CHECK_ENDPOINT
        apiKey = ""
        maxResultsText = if (deepResearch) "5" else "3"
        fetchPageContent = false
        triggerMode = "SMART"
        researchMode = if (deepResearch) "DEEP" else "AUTO"
        testQuery = query
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("联网检索", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            InfoCard(
                title = "工作方式",
                primary = when {
                    state.realSearchConfigured -> "已配置真实搜索：${state.realSearchProviderLabel.ifBlank { state.providerLabel }}"
                    state.isPublicWebSearchCheckSource() -> "已配置：公开 JSON 自检源（仅用于自检）"
                    state.configured -> "已配置：${state.providerLabel}"
                    state.enabled -> "已启用：可读取网页链接"
                    else -> "需要启用或配置搜索服务"
                },
                secondary = "触发：${state.triggerModeLabel} · 研究：${state.researchModeLabel}"
            )
        }
        item {
            ExpandableSettingsCard(
                title = "使用说明",
                summary = "搜索源配置、测试步骤、隐私边界",
                expanded = helpExpanded,
                onExpandedChange = { helpExpanded = it }
            ) {
                if (state.isPublicWebSearchCheckSource()) {
                    HelpTextBlock(
                        title = "覆盖范围提示",
                        primary = "当前使用的是公开 JSON 协议自检源。",
                        secondary = "它只用于验证联网链路、上下文注入和来源卡片，不是通用搜索服务。正式使用请配置自己的 SearxNG、Brave、Tavily、Jina 或可信自建搜索源。"
                    )
                }
                HelpTextBlock(
                    title = "推荐校验流程",
                    primary = "先测试完整 URL 直读，再测试关键词搜索，最后回到聊天页确认来源卡片。",
                    secondary = "公共 SearxNG 可能限流，稳定使用建议自建或使用带 Key 的搜索服务。Jina 配置 Key 后可在普通网页抓取内容不足时用 Reader 增强正文读取。"
                )
                HelpTextBlock(
                    title = "如何获取搜索源",
                    primary = "快速可用选 Tavily 或 Brave：到服务商官网注册并创建 Search API Key。",
                    secondary = "隐私可控选自建 SearxNG；网页正文增强选 Jina；团队或高级用户可接自建 JSON 网关。公开 JSON 自检源只验证链路，不能当正式搜索源。"
                )
                HelpTextBlock(
                    title = providerSetupGuidance.title,
                    primary = providerSetupGuidance.primary,
                    secondary = providerSetupGuidance.secondary
                )
                HelpTextBlock(
                    title = "安全边界",
                    primary = "本地模型不会直接联网，MCA 只把搜索摘要注入当前轮对话。",
                    secondary = "网页内容中的指令不会被当作系统指令执行；本机、局域网、链路本地和保留地址默认会被拦截。API Key 只保存在本机。"
                )
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用联网检索", fontWeight = FontWeight.Bold)
                            Text(
                                "开启后可读取用户提供的网页链接；关键词搜索还需要配置搜索服务。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        providerItems.forEach { item ->
                            FilterChip(
                                selected = provider == item.first,
                                onClick = { provider = item.first },
                                label = { Text(item.second) }
                            )
                        }
                    }
                    ExpandableSettingsInlineSection(
                        title = "高级设置",
                        summary = "触发：${triggerModes.firstOrNull { it.first == triggerMode }?.second.orEmpty()} · 研究：${researchModes.firstOrNull { it.first == researchMode }?.second.orEmpty()}",
                        expanded = advancedExpanded,
                        onExpandedChange = { advancedExpanded = it }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("触发方式", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                triggerModes.forEach { item ->
                                    FilterChip(
                                        selected = triggerMode == item.first,
                                        onClick = { triggerMode = item.first },
                                        label = { Text(item.second) }
                                    )
                                }
                            }
                            Text(
                                triggerModes.firstOrNull { it.first == triggerMode }?.third.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("研究模式", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                researchModes.forEach { item ->
                                    FilterChip(
                                        selected = researchMode == item.first,
                                        onClick = { researchMode = item.first },
                                        label = { Text(item.second) }
                                    )
                                }
                            }
                            Text(
                                researchModes.firstOrNull { it.first == researchMode }?.third.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text(if (provider == "SEARXNG") "SearxNG 地址" else "搜索接口地址") },
                        placeholder = { Text(endpointPlaceholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(if (needsApiKey) "API Key" else "API Key（可选）") },
                        placeholder = { Text(if (needsApiKey) "粘贴服务商 Key" else "自定义接口需要鉴权时填写") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (provider == "JINA") {
                        Text(
                            "Jina Search 需要 Key；开启正文抓取后，MCA 会先尝试本机直连读取公开网页，内容不足时再用 Jina Reader 获取 Markdown 摘要。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isPublicCheckDraft) {
                        Text(
                            "当前填写的是公开协议自检源，只适合测试链路，不建议保存为日常搜索服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    ExpandableSettingsInlineSection(
                        title = "备用搜索源",
                        summary = "已启用 ${listOf(backup0Enabled, backup1Enabled, backup2Enabled).count { it }} 个",
                        expanded = backupExpanded,
                        onExpandedChange = { backupExpanded = it }
                    ) {
                        Text(
                            "主搜索源限流、鉴权失败或没有可用结果时，MCA 只会按这里显式配置过的服务接力，不会调用未授权服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        BackupProviderEditor(
                            title = "备用 1",
                            enabled = backup0Enabled,
                            provider = backup0Provider,
                            endpoint = backup0Endpoint,
                            apiKey = backup0ApiKey,
                            providerItems = providerItems,
                            onEnabledChange = { backup0Enabled = it },
                            onProviderChange = { backup0Provider = it },
                            onEndpointChange = { backup0Endpoint = it },
                            onApiKeyChange = { backup0ApiKey = it }
                        )
                        BackupProviderEditor(
                            title = "备用 2",
                            enabled = backup1Enabled,
                            provider = backup1Provider,
                            endpoint = backup1Endpoint,
                            apiKey = backup1ApiKey,
                            providerItems = providerItems,
                            onEnabledChange = { backup1Enabled = it },
                            onProviderChange = { backup1Provider = it },
                            onEndpointChange = { backup1Endpoint = it },
                            onApiKeyChange = { backup1ApiKey = it }
                        )
                        BackupProviderEditor(
                            title = "备用 3",
                            enabled = backup2Enabled,
                            provider = backup2Provider,
                            endpoint = backup2Endpoint,
                            apiKey = backup2ApiKey,
                            providerItems = providerItems,
                            onEnabledChange = { backup2Enabled = it },
                            onProviderChange = { backup2Provider = it },
                            onEndpointChange = { backup2Endpoint = it },
                            onApiKeyChange = { backup2ApiKey = it }
                        )
                    }
                    OutlinedTextField(
                        value = maxResultsText,
                        onValueChange = { maxResultsText = it.filter { ch -> ch.isDigit() }.take(1).ifBlank { "" } },
                        label = { Text("结果数量") },
                        placeholder = { Text("1-8") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("抓取网页正文", fontWeight = FontWeight.Bold)
                            Text(
                                "会额外读取前几个公开网页正文，提高回答质量，但速度会慢一点；本机、局域网、链路本地和保留地址会被安全拦截。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = fetchPageContent, onCheckedChange = { fetchPageContent = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onSave(currentDraft())
                            }
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Text("保存")
                        }
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("测试检索", fontWeight = FontWeight.Bold)
                    Text(
                        "测试会使用当前表单内容，不需要先保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    WebSearchTestStatusPanel(
                        statusMessage = state.statusMessage,
                        latestDiagnostic = state.diagnostics.firstOrNull(),
                        configured = state.configured
                    )
                    OutlinedTextField(
                        value = testQuery,
                        onValueChange = { testQuery = it },
                        label = { Text("测试问题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onPreflight(currentDraft()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("网络预检")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { onTest(testQuery, currentDraft()) }, modifier = Modifier.weight(1f)) {
                            Text("测试当前填写")
                        }
                        Button(onClick = { onTestTurn(testQuery, currentDraft(), false) }, modifier = Modifier.weight(1f)) {
                            Text("闭环自检")
                        }
                    }
                    ExpandableSettingsInlineSection(
                        title = "更多测试",
                        summary = "协议自检、研究闭环、清空记录",
                        expanded = testExpanded,
                        onExpandedChange = { testExpanded = it }
                    ) {
                        Button(
                            onClick = {
                                testQuery = WEB_SEARCH_RESEARCH_CHECK_QUERY
                                onTestTurn(WEB_SEARCH_RESEARCH_CHECK_QUERY, currentDraft().copy(researchMode = "DEEP"), false)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("研究闭环自检")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    applyPublicCheckDraft(WEB_SEARCH_PUBLIC_CHECK_QUERY, deepResearch = false)
                                    onTestTurn(WEB_SEARCH_PUBLIC_CHECK_QUERY, publicCheckDraft(deepResearch = false), true)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("协议自检")
                            }
                            Button(
                                onClick = {
                                    applyPublicCheckDraft(WEB_SEARCH_RESEARCH_CHECK_QUERY, deepResearch = true)
                                    onTestTurn(WEB_SEARCH_RESEARCH_CHECK_QUERY, publicCheckDraft(deepResearch = true), true)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("研究协议自检")
                            }
                        }
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                applyPublicCheckDraft(WEB_SEARCH_PUBLIC_CHECK_QUERY, deepResearch = false)
                            }
                        ) {
                            Text("填入公开 JSON 协议自检源")
                        }
                        Text(
                            "公开协议自检无需 Key，用于验证 JSON 接入、上下文注入、来源卡片和诊断链路；它不是通用搜索服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = onClearDiagnostics,
                            enabled = state.diagnostics.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("清空记录")
                        }
                    }
                }
            }
        }
        item {
            ExpandableSettingsCard(
                title = "最近检索",
                summary = if (state.diagnostics.isEmpty()) "暂无记录" else "${state.diagnostics.size} 条本机诊断记录",
                expanded = diagnosticsExpanded,
                onExpandedChange = { diagnosticsExpanded = it }
            ) {
                if (state.diagnostics.isEmpty()) {
                    HelpTextBlock(
                        title = "暂无记录",
                        primary = "完成一次测试或聊天联网后会显示在这里。",
                        secondary = "记录只保存在本机，包含检索词、来源数、耗时和失败摘要，不保存 API Key。"
                    )
                } else {
                    state.diagnostics.take(8).forEach { diagnostic ->
                        WebSearchDiagnosticCard(diagnostic, onOpenWebPage)
                    }
                }
            }
        }
    }
}

private fun WebSearchSettingsUiState.isPublicWebSearchCheckSource(): Boolean =
    isPublicWebSearchCheckSource(provider, endpoint)

private fun isPublicWebSearchCheckSource(provider: String, endpoint: String): Boolean =
    provider == "CUSTOM_JSON" && endpoint.trim().trimEnd('/') == WEB_SEARCH_PUBLIC_CHECK_ENDPOINT

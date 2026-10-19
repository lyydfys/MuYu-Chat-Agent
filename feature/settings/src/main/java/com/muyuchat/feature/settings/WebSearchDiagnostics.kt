package com.muyuchat.feature.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.mcaPressScale
import kotlinx.coroutines.launch
import java.net.URI

@Composable
internal fun WebSearchTestStatusPanel(
    statusMessage: String?,
    latestDiagnostic: WebSearchDiagnosticUiItem?,
    configured: Boolean,
    busy: Boolean
) {
    val context = LocalContext.current
    val message = statusMessage
        ?: latestDiagnostic?.message
        ?: if (configured) {
            "当前配置可用于聊天页和助手默认联网检索。"
        } else {
            "先做网络预检；如需关键词搜索，请配置真实搜索源。"
        }
    // W9-1: status-keyword scanning is mapped once per status/diagnostic value instead of on every
    // recomposition of the settings page.
    val needsAttention = remember(statusMessage, latestDiagnostic?.message, latestDiagnostic?.success, configured) {
        message.contains("失败") ||
            message.contains("未通过") ||
            message.contains("需检查") ||
            message.contains("请先") ||
            latestDiagnostic?.success == false
    }
    val accent = if (needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(6.dp))
            }
            Text("当前测试结果", fontWeight = FontWeight.Bold, color = accent)
        }
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (latestDiagnostic != null) {
            Text(
                "${latestDiagnostic.providerLabel} · ${if (latestDiagnostic.success) "完成" else "未完成"} · ${latestDiagnostic.sourceCount} 源 · ${latestDiagnostic.elapsedMs}ms · ${latestDiagnostic.createdAtText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (latestDiagnostic.query.isNotBlank()) {
                Text(
                    "问题：${latestDiagnostic.query}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (message.shouldShowWebSearchNetworkTroubleshootingActions()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { context.openMcaNetworkSettings() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("打开网络设置")
                }
                TextButton(
                    onClick = { context.openMcaAppSettings() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("应用联网权限")
                }
            }
        }
    }
}

@Composable
internal fun BackupProviderEditor(
    title: String,
    enabled: Boolean,
    provider: String,
    endpoint: String,
    apiKey: String,
    providerItems: List<Pair<String, String>>,
    onEnabledChange: (Boolean) -> Unit,
    onProviderChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit
) {
    val endpointPlaceholder = when (provider) {
        "SEARXNG" -> "https://your-searxng.example"
        "BRAVE" -> "https://api.search.brave.com/res/v1/web/search"
        "TAVILY" -> "https://api.tavily.com/search"
        "JINA" -> "https://s.jina.ai"
        else -> "https://your-search-api.example/search"
    }
    val needsApiKey = provider == "BRAVE" || provider == "TAVILY" || provider == "JINA"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Text(
                if (enabled) "已加入故障接力链路" else "关闭时不会发起请求",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (enabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                providerItems.forEach { item ->
                    FilterChip(
                        selected = provider == item.first,
                        onClick = { onProviderChange(item.first) },
                        label = { Text(item.second) }
                    )
                }
            }
            OutlinedTextField(
                value = endpoint,
                onValueChange = onEndpointChange,
                label = { Text("接口地址") },
                placeholder = { Text(endpointPlaceholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(if (needsApiKey) "API Key" else "API Key（可选）") },
                placeholder = { Text(if (needsApiKey) "粘贴服务商 Key" else "自定义接口需要鉴权时填写") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun WebSearchDiagnosticCard(
    item: WebSearchDiagnosticUiItem,
    onOpenWebPage: (String) -> Unit = {}
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // W9-1: filter/scan the diagnostic evidence once per item value. These lists are derived from
    // strings and were rebuilt on every recomposition of the settings surface.
    val citationChecks = remember(item) { item.closedLoopChecks.filter { it.startsWith("引用审计") } }
    val closedLoopChecks = remember(item) { item.closedLoopChecks.filterNot { it.startsWith("引用审计") } }
    val troubleshootingAdvice = remember(item) { item.webSearchTroubleshootingAdvice() }
    val citationWarning = remember(item) { item.warnings.any { it.contains("引用") } }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (item.success) "检索成功" else "检索未完成", fontWeight = FontWeight.Bold)
                    Text(
                        "${item.providerLabel} · ${item.triggerModeLabel} · ${item.createdAtText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${item.sourceCount} 源",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            DiagnosticMetricRow(
                values = buildList {
                    add("耗时 ${item.elapsedMs}ms")
                    if (item.healthLabel.isNotBlank()) add("健康 ${item.healthLabel} ${item.healthScore}/100")
                    if (item.qualityLabel.isNotBlank()) add("质量 ${item.qualityLabel} ${item.qualityScore}/100")
                    if (item.cacheStatus.isNotBlank()) add(item.cacheStatus)
                }
            )
            DiagnosticEvidenceSection(
                title = "问题",
                values = listOf(item.query.ifBlank { "空问题" }, item.message)
            )
            DiagnosticEvidenceSection(
                title = "触发",
                values = item.triggerReasons.take(5),
                emptyText = "没有命中自动联网线索"
            )
            DiagnosticEvidenceSection(
                title = "检索",
                values = buildList {
                    if (item.searchedQueries.isNotEmpty()) {
                        add("检索词：${item.searchedQueries.joinToString(" / ")}")
                    }
                    if (item.directUrls.isNotEmpty()) {
                        add("网页：${item.directUrls.joinToString(" / ")}")
                    }
                    if (item.warnings.isNotEmpty()) {
                        addAll(item.warnings.take(4).map { "警告：$it" })
                    }
                },
                error = item.warnings.isNotEmpty(),
                emptyText = "未执行关键词检索"
            )
            DiagnosticEvidenceSection(
                title = "检索健康",
                values = buildList {
                    if (item.healthLabel.isNotBlank()) add("${item.healthLabel} · ${item.healthScore}/100")
                    addAll(item.healthReasons.take(6))
                },
                error = item.healthLabel == "需检查" || item.healthLabel == "失败",
                emptyText = "暂无健康评估"
            )
            if (troubleshootingAdvice.isNotEmpty()) {
                DiagnosticEvidenceSection(
                    title = "处理建议",
                    values = troubleshootingAdvice,
                    error = item.healthLabel == "需检查" || item.healthLabel == "失败" || item.warnings.isNotEmpty()
                )
            }
            DiagnosticEvidenceSection(
                title = "来源质量",
                values = buildList {
                    if (item.qualityLabel.isNotBlank()) add("${item.qualityLabel} · ${item.qualityScore}/100")
                    addAll(item.sourceTrustSummary.take(4))
                    addAll(item.qualityReasons.take(5))
                },
                emptyText = "没有可评估来源"
            )
            DiagnosticEvidenceSection(
                title = "研究综合",
                values = buildList {
                    if (item.researchConfidenceLabel.isNotBlank()) {
                        add("置信度 ${item.researchConfidenceLabel} · ${item.researchConfidenceScore}/100")
                    }
                    addAll(item.researchEvidenceGroups.take(5))
                    addAll(item.researchConflictWarnings.take(4).map { "不确定性：$it" })
                    addAll(item.researchSynthesisGuidance.take(3).map { "建议：$it" })
                },
                error = item.researchConflictWarnings.isNotEmpty(),
                emptyText = "暂无研究综合评估"
            )
            if (closedLoopChecks.isNotEmpty()) {
                DiagnosticEvidenceSection(
                    title = "闭环",
                    values = closedLoopChecks.take(5)
                )
            }
            if (citationChecks.isNotEmpty()) {
                DiagnosticEvidenceSection(
                    title = "引用审计",
                    values = citationChecks.take(4),
                    error = citationWarning
                )
            }
            if (item.topSources.isNotEmpty()) {
                HorizontalDivider()
                item.topSources.forEach { source ->
                    DiagnosticSourceRow(
                        source = source,
                        onOpenWebPage = onOpenWebPage
                    )
                }
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                ClipData.newPlainText(
                                    "MCA 联网检索诊断",
                                    item.toEvidenceText()
                                )
                            )
                        )
                    }
                }
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                Text("复制诊断", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun DiagnosticMetricRow(values: List<String>) {
    if (values.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        values.forEach { value ->
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun DiagnosticEvidenceSection(
    title: String,
    values: List<String>,
    emptyText: String = "",
    error: Boolean = false
) {
    val visibleValues = values.filter { it.isNotBlank() }
    if (visibleValues.isEmpty() && emptyText.isBlank()) return
    val textColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (visibleValues.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            visibleValues.forEach { value ->
                Text(value, style = MaterialTheme.typography.bodySmall, color = textColor)
            }
        }
    }
}

internal fun String.shouldShowWebSearchNetworkTroubleshootingActions(): Boolean {
    val text = trim()
    if (text.isBlank()) return false
    if (!text.startsWith("网络预检需检查")) return false
    val networkHints = listOf(
        "没有活动网络",
        "WLAN",
        "移动数据",
        "Wi-Fi",
        "公网",
        "DNS",
        "VPN",
        "私人 DNS",
        "代理",
        "网络受限",
        "未登录 Wi-Fi",
        "系统尚未验证",
        "安全中心",
        "省电策略"
    )
    return networkHints.any { text.contains(it, ignoreCase = true) }
}

internal fun WebSearchDiagnosticUiItem.webSearchTroubleshootingAdvice(): List<String> {
    val text = buildString {
        appendLine(providerLabel)
        appendLine(message)
        warnings.forEach { appendLine(it) }
        healthReasons.forEach { appendLine(it) }
        qualityReasons.forEach { appendLine(it) }
    }
    val lower = text.lowercase()
    return buildList {
        if (providerLabel.contains("公开 JSON 自检源") || text.contains("不是通用搜索服务") || text.contains("公开 JSON")) {
            add("当前是协议自检源，只能验证请求、JSON 解析、上下文注入和来源卡片；正式搜索请切换到自建 SearxNG、Brave、Tavily、Jina 或可信自定义 JSON。")
        }
        if ("401" in lower || "403" in lower || "鉴权失败" in text || "鉴权或权限" in text) {
            add("重新复制 API Key，确认账号已开通对应搜索接口和额度；Brave 使用 X-Subscription-Token，Tavily/Jina 使用 Bearer Key。")
        }
        if ("404" in lower || "路径不存在" in text || "接口路径" in text) {
            add(providerLabel.webSearchEndpointAdvice())
        }
        if ("429" in lower || "限流" in text) {
            add("当前搜索服务被限流，建议稍后重试、降低结果数量、配置备用搜索源，或改用自建 SearxNG / 付费 Key。")
        }
        if ("400" in lower || "请求参数" in text) {
            add("检查协议类型和 Base URL 是否匹配；自建网关可使用 /search?q={query}&limit={max_results} 这类 URL 模板，返回 results/items/data/hits/organic_results 等常见 JSON 结构。")
        }
        if ("500" in lower || "502" in lower || "503" in lower || "504" in lower || "服务端异常" in text) {
            add("搜索服务端暂时异常，先切换备用源或稍后重试；自建服务请检查服务日志和反代状态。")
        }
        if (
            "unknown host" in lower || "unable to resolve host" in lower || "no address associated" in lower ||
            "无法解析" in text || "dns" in lower || "vpn" in lower || "代理" in text
        ) {
            add("先用手机浏览器打开任意公网网页；仍失败时检查应用联网权限、Wi-Fi 登录页、私人 DNS、VPN、代理和系统安全中心。")
        }
        if ("timeout" in lower || "timed out" in lower || "超时" in text) {
            add("请求超时通常来自移动网络波动、公共实例拥堵或正文抓取过慢；可关闭抓取网页正文、降低结果数量或换更稳定的搜索源。")
        }
        if ("空结果" in text || "无结果" in text || "没有可用来源" in text) {
            add("换更明确的关键词，加入官网/文档/评测等限定词；如果公共 SearxNG 返回空结果，建议自建或切换 Brave/Tavily/Jina。")
        }
        if ("低相关" in text || "相关性较弱" in text || "相关性过滤" in text) {
            add("来源相关性不足时，尝试把问题改成具体产品名、版本号、接口名或错误码，必要时开启研究模式。")
        }
        if ("安全拦截" in text || "受限地址" in text) {
            add("MCA 默认不读取 localhost、局域网、链路本地和保留地址，这是防止网页读取误扫内网的安全策略。")
        }
    }.distinct().take(5)
}

private fun String.webSearchEndpointAdvice(): String =
    when {
        contains("Brave", ignoreCase = true) ->
            "Brave 常规搜索推荐完整 Web Search API 路径 https://api.search.brave.com/res/v1/web/search；填写官方根地址 https://api.search.brave.com 时 MCA 会自动补全到 Web Search，AI grounding/RAG 可手动填写 /res/v1/llm/context。不要填控制台或聊天模型接口。"
        contains("Tavily", ignoreCase = true) ->
            "Tavily 推荐 https://api.tavily.com/search；填写官方根地址 https://api.tavily.com 时 MCA 会自动补全 /search，并使用 POST JSON 调用。"
        contains("Jina", ignoreCase = true) ->
            "Jina Search 推荐填写 https://s.jina.ai；Jina Reader 只用于网页正文增强，不要把 Reader 地址填到搜索服务里。"
        contains("SearxNG", ignoreCase = true) ->
            "SearxNG 通常填写实例根地址即可，MCA 会自动访问 /search?format=json；如果实例禁用 JSON，请换可信实例或自建。"
        else ->
            "检查 Base URL 和协议路径是否是搜索接口，不要把聊天模型接口、控制台页面或网页地址填到联网检索服务里。"
    }

internal data class WebSearchProviderSetupGuidance(
    val title: String,
    val primary: String,
    val secondary: String
)

internal fun webSearchProviderSetupGuidance(
    provider: String,
    endpoint: String,
    apiKey: String,
    publicCheck: Boolean
): WebSearchProviderSetupGuidance {
    if (publicCheck) {
        return WebSearchProviderSetupGuidance(
            title = "当前服务建议",
            primary = "公开 JSON 自检源只适合验证链路",
            secondary = "它能证明 App 可以发起请求、解析 JSON、生成上下文和来源卡片，但覆盖范围有限，不是全网搜索服务。正式搜索请切换到自建 SearxNG、Brave、Tavily、Jina 或可信自定义 JSON。"
        )
    }
    val endpointText = endpoint.trim()
    val path = runCatching { URI(endpointText).path.orEmpty().trimEnd('/') }.getOrDefault("")
    val host = runCatching { URI(endpointText).host.orEmpty() }.getOrDefault("")
    val keyHint = if (apiKey.isBlank()) "当前未填写 API Key。" else "API Key 已填写。"
    return when (provider) {
        "SEARXNG" -> WebSearchProviderSetupGuidance(
            title = "当前服务建议",
            primary = "SearxNG 适合自建或可信实例",
            secondary = "可填写实例根地址，MCA 会自动请求 /search?format=json。公共实例经常限流或关闭 JSON，产品化使用建议自建，必要时再配置带 Key 的备用搜索源。"
        )
        "BRAVE" -> WebSearchProviderSetupGuidance(
            title = "当前服务建议",
            primary = when {
                path == "/res/v1/web/search" -> "Brave Web Search 路径正确"
                path == "/res/v1/llm/context" -> "Brave LLM Context 路径正确"
                host.equals("api.search.brave.com", ignoreCase = true) && path.isBlank() ->
                    "Brave 官方根地址会自动补全为完整 Web Search API 路径"
                else -> "Brave 需要 Search API 路径"
            },
            secondary = "常规搜索推荐：https://api.search.brave.com/res/v1/web/search；直接填写 https://api.search.brave.com 时 MCA 会自动补全到 Web Search。AI grounding/RAG 可填写：https://api.search.brave.com/res/v1/llm/context。MCA 会使用 X-Subscription-Token 发送 Key。$keyHint 如果你使用自建代理并返回兼容 JSON，也可以改用“自定义”。"
        )
        "TAVILY" -> WebSearchProviderSetupGuidance(
            title = "当前服务建议",
            primary = when {
                path.endsWith("/search") -> "Tavily Search 路径正确"
                host.equals("api.tavily.com", ignoreCase = true) && path.isBlank() ->
                    "Tavily 官方根地址会自动补全 /search"
                else -> "Tavily 应填写 Search API 地址"
            },
            secondary = "推荐地址：https://api.tavily.com/search；直接填写 https://api.tavily.com 时 MCA 会自动补全 /search。MCA 会用 POST JSON + Authorization: Bearer Key 调用，并可通过“抓取网页正文”切换 basic/advanced 深度。$keyHint"
        )
        "JINA" -> WebSearchProviderSetupGuidance(
            title = "当前服务建议",
            primary = if (host.equals("s.jina.ai", ignoreCase = true)) {
                "Jina Search 地址正确"
            } else {
                "Jina Search 推荐使用 s.jina.ai"
            },
            secondary = "推荐地址：https://s.jina.ai。MCA 会用 Bearer Key 请求搜索；开启正文抓取时，普通网页正文不足会尝试 Jina Reader 增强摘要。$keyHint"
        )
        else -> WebSearchProviderSetupGuidance(
            title = "当前服务建议",
            primary = "自定义 JSON 适合自建搜索网关",
            secondary = "MCA 支持 {query}/{max_results} URL 模板，也会尝试 q/query/max_results 参数，并解析 results、items、data、hits、organic_results 等常见结构。请确保返回项包含 url/link/href 和 title/snippet，API Key 只会以 Bearer 形式发送。"
        )
    }
}

private fun Context.openMcaNetworkSettings() {
    startFirstAvailable(
        Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS),
        Intent(AndroidSettings.ACTION_WIFI_SETTINGS),
        Intent(AndroidSettings.ACTION_SETTINGS)
    )
}

private fun Context.openMcaAppSettings() {
    startFirstAvailable(
        Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ),
        Intent(AndroidSettings.ACTION_APPLICATION_SETTINGS),
        Intent(AndroidSettings.ACTION_SETTINGS)
    )
}

private fun Context.startFirstAvailable(vararg intents: Intent) {
    val target = intents.firstOrNull { intent ->
        intent.resolveActivity(packageManager) != null
    } ?: intents.lastOrNull() ?: return
    startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * One clickable source card. The press scale reads the same interaction source the click uses, so
 * the feedback stays draw-phase and adds no layout geometry.
 */
@Composable
private fun DiagnosticSourceRow(
    source: WebSearchDiagnosticSourceUiItem,
    onOpenWebPage: (String) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), RoundedCornerShape(8.dp))
            // Bounded ripple + button role: same interaction source already drives the press scale.
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true)
            ) {
                onOpenWebPage(source.url)
            }
            .semantics { role = Role.Button }
            .mcaPressScale(interactionSource)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            listOf(source.trustLabel, source.hostLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            source.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            source.url.removePrefix("https://").removePrefix("http://"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "点击在应用内查看",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (source.provider.isNotBlank()) {
            Text(
                source.provider,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (source.snippet.isNotBlank()) {
            Text(
                source.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

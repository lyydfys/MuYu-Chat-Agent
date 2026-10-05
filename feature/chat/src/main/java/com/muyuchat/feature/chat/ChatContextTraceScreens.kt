package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S10：从 ChatChromeScreens.kt 等值移出的上下文装配追踪解析与展示簇。
// 原样逐字迁移，未做行为改动；跨区入口按协议提升为 internal。
// P9/S10: private→internal（作为提升入口函数的签名类型需要同等可见性）
internal data class ContextTraceSource(
    val sourceId: String,
    val ownerId: String,
    val scope: String?,
    val excerpt: String,
    val estimatedTokens: Int,
    val reason: String,
    val lexicalScore: Int? = null,
    val excerptTruncated: Boolean = false,
    val excerptSha256: String? = null
)

// P9/S10: private→internal（作为提升入口函数的签名类型需要同等可见性）
internal data class ContextTraceDetail(
    val rolePromptHash: String,
    val assembledHash: String,
    val tokenBudget: Int,
    val retrievalType: String,
    val selectedWorldBooks: List<ContextTraceSource>,
    val skippedWorldBooks: List<ContextTraceSource>,
    val selectedKnowledge: List<ContextTraceSource>,
    val skippedKnowledge: List<ContextTraceSource>,
    val omittedSourceCount: Int = 0
)

private fun JSONObject.traceText(key: String): String =
    (get(key) as? String)?.takeIf(String::isNotBlank) ?: error("Invalid context trace field: $key")

private fun JSONObject.traceCount(key: String): Int =
    ((get(key) as? Number)?.toInt() ?: error("Invalid context trace field: $key"))
        .also { require(it >= 0) }

// P9/S10: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun parseContextAssemblyTrace(raw: String?): ContextTraceDetail? {
    if (raw.isNullOrBlank() || raw.length > 4_000_000) return null
    return runCatching {
        val root = JSONObject(raw)
        require(root.traceCount("schemaVersion") == 1)
        fun worldSources(key: String): List<ContextTraceSource> {
            val array = root.getJSONArray(key)
            require(array.length() <= 4096)
            return List(array.length()) { index ->
                val source = array.getJSONObject(index)
                ContextTraceSource(
                    sourceId = source.traceText("entryId"),
                    ownerId = source.traceText("bookId"),
                    scope = source.traceText("scope"),
                    excerpt = source.traceText("excerpt"),
                    estimatedTokens = source.traceCount("estimatedTokens"),
                    reason = source.traceText("reason"),
                    excerptTruncated = source.optBoolean("excerptTruncated", false),
                    excerptSha256 = source.optString("excerptSha256").takeIf(String::isNotBlank)
                )
            }
        }
        fun knowledgeSources(key: String): List<ContextTraceSource> {
            val array = root.getJSONArray(key)
            require(array.length() <= 4096)
            return List(array.length()) { index ->
                val source = array.getJSONObject(index)
                ContextTraceSource(
                    sourceId = source.traceText("chunkId"),
                    ownerId = source.traceText("knowledgeBaseId"),
                    scope = source.traceText("documentId"),
                    excerpt = source.traceText("excerpt"),
                    estimatedTokens = source.traceCount("estimatedTokens"),
                    reason = source.traceText("reason"),
                    lexicalScore = source.traceCount("lexicalScore"),
                    excerptTruncated = source.optBoolean("excerptTruncated", false),
                    excerptSha256 = source.optString("excerptSha256").takeIf(String::isNotBlank)
                )
            }
        }
        ContextTraceDetail(
            rolePromptHash = root.traceText("rolePromptHash"),
            assembledHash = root.traceText("assembledHash"),
            tokenBudget = root.traceCount("tokenBudget"),
            retrievalType = root.traceText("retrievalType"),
            selectedWorldBooks = worldSources("selectedWorldBookSources"),
            skippedWorldBooks = worldSources("skippedWorldBookSources"),
            selectedKnowledge = knowledgeSources("selectedKnowledgeSources"),
            skippedKnowledge = knowledgeSources("skippedKnowledgeSources"),
            omittedSourceCount = root.optInt("omittedSourceCount", 0).coerceAtLeast(0)
        )
    }.getOrNull()
}

@Composable
// P9/S10: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun ContextAssemblyTraceDialog(trace: ContextTraceDetail, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本轮上下文来源") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("检索方式：${if (trace.retrievalType == "lexical") "词法匹配 (lexical)" else trace.retrievalType}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("动态上下文预算：${trace.tokenBudget} tokens", style = MaterialTheme.typography.bodySmall)
                        if (trace.omittedSourceCount > 0) {
                            Text("另有 ${trace.omittedSourceCount} 条来源未保存详情", style = MaterialTheme.typography.bodySmall)
                        }
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("角色提示词 SHA-256：${trace.rolePromptHash}", style = MaterialTheme.typography.labelSmall)
                                Text("组装上下文 SHA-256：${trace.assembledHash}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                contextTraceSection("世界书 · 已选", trace.selectedWorldBooks, isKnowledge = false)
                contextTraceSection("世界书 · 已跳过", trace.skippedWorldBooks, isKnowledge = false)
                contextTraceSection("知识库 · 已选", trace.selectedKnowledge, isKnowledge = true)
                contextTraceSection("知识库 · 已跳过", trace.skippedKnowledge, isKnowledge = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.contextTraceSection(
    label: String,
    sources: List<ContextTraceSource>,
    isKnowledge: Boolean
) {
    item {
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        Text("$label (${sources.size})", modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleSmall)
    }
    if (sources.isEmpty()) {
        item {
            Text("无", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        items(sources) { source ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (isKnowledge) "知识块 ${source.sourceId}" else "条目 ${source.sourceId}",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (isKnowledge) "知识库：${source.ownerId} · 文档：${source.scope}" else
                            "世界书：${source.ownerId} · 范围：${source.scope}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("原因：${contextTraceReasonLabel(source.reason)} (${source.reason}) · " +
                            "估算 ${source.estimatedTokens} tokens" +
                            (source.lexicalScore?.let { " · 词法分数 $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(source.excerpt, style = MaterialTheme.typography.bodySmall)
                        if (source.excerptTruncated) {
                            Text("节选已截断 · 完整内容 SHA-256：${source.excerptSha256.orEmpty()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun contextTraceReasonLabel(reason: String): String = when (reason) {
    "constant" -> "常驻条目"
    "keyword" -> "关键词命中"
    "budget" -> "预算不足"
    "lexical_match" -> "词法匹配"
    "budget_or_limit" -> "预算或条数限制"
    else -> "来源判定"
}

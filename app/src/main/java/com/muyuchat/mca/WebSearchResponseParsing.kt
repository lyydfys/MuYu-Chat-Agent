package com.muyuchat.mca

import com.muyuchat.core.engine.ChatSourceReference
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray?.toSearchDocuments(provider: String): List<WebSearchDocument> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val source = item.optJSONObject("source")
            val url = item.cleanString(
                "url",
                "link",
                "href",
                "html_url",
                "story_url",
                "permalink",
                "canonical_url",
                "uri",
                "display_link",
                "displayLink",
                "formatted_url",
                "formattedUrl"
            ).ifBlank {
                source?.cleanString("url", "link", "href", "canonical_url", "canonicalUrl").orEmpty()
            }
            if (url.isBlank()) continue
            val snippet = item.cleanString(
                "snippet",
                "description",
                "summary",
                "excerpt",
                "content",
                "text",
                "comment_text",
                "story_text",
                "answer"
            )
            val rawContent = item.cleanString(
                "raw_content",
                "rawContent",
                "body",
                "page_content",
                "pageContent",
                "markdown",
                "content_text",
                "contentText",
                "article"
            )
            val dateSignals = item.cleanString("age", "page_age", "published", "published_at", "date")
                .takeIf { it.isNotBlank() }
                ?.let { listOf("时间：$it") }
                .orEmpty()
            val extraSnippets = item.optJSONArray("extra_snippets")
                ?.toStringList()
                .orEmpty()
                .map { it.cleanWebSearchText() }
            add(
                WebSearchDocument(
                    title = item.cleanString("title", "name", "full_name", "story_title", "question")
                        .ifBlank { source?.cleanString("title", "name", "site_name", "siteName").orEmpty() }
                        .ifBlank { url },
                    url = url,
                    snippet = (listOf(snippet) + dateSignals + extraSnippets).filter { it.isNotBlank() }.joinToString("\n").limitForPrompt(1_000),
                    content = rawContent.limitForPrompt(3_000),
                    provider = provider
                )
            )
        }
    }
}

internal fun JSONObject.toBraveSearchDocuments(
    provider: String,
    maxResults: Int
): List<WebSearchDocument> {
    val typedDocuments = mapOf(
        "web" to optJSONObject("web").toBraveTypedDocuments(provider, "Web"),
        "news" to optJSONObject("news").toBraveTypedDocuments(provider, "News"),
        "discussions" to optJSONObject("discussions").toBraveTypedDocuments(provider, "Discussions"),
        "faq" to optJSONObject("faq").toBraveTypedDocuments(provider, "FAQ"),
        "videos" to optJSONObject("videos").toBraveTypedDocuments(provider, "Videos")
    )
    val mixedDocuments = optJSONObject("mixed")
        ?.optJSONArray("main")
        .toBraveMixedDocuments(typedDocuments)
    return (mixedDocuments.ifEmpty { typedDocuments.values.flatten() })
        .distinctBy { it.url.normalizedUrlKey() }
        .take(maxResults)
}

internal fun JSONObject?.toBraveTypedDocuments(
    provider: String,
    typeLabel: String
): List<WebSearchDocument> {
    val results = this?.optJSONArray("results") ?: return emptyList()
    val typedProvider = if (typeLabel == "Web") provider else "$provider · $typeLabel"
    return results.toSearchDocuments(typedProvider)
}

internal fun JSONArray?.toBraveMixedDocuments(
    typedDocuments: Map<String, List<WebSearchDocument>>
): List<WebSearchDocument> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val type = item.optString("type").lowercase(Locale.ROOT)
            val resultIndex = item.optInt("index", -1)
            if (type.isBlank() || resultIndex < 0) continue
            typedDocuments[type]?.getOrNull(resultIndex)?.let(::add)
        }
    }
}

internal fun JSONObject.toBraveLlmContextDocuments(provider: String): List<WebSearchDocument> {
    val grounding = optJSONObject("grounding") ?: return emptyList()
    val sources = optJSONObject("sources")
    val generic = grounding.optJSONArray("generic").toBraveLlmContextItems(provider, sources)
    val map = grounding.optJSONArray("map").toBraveLlmContextItems(provider, sources)
    val poi = grounding.optJSONObject("poi")
        ?.takeIf { it.length() > 0 }
        ?.let { listOfNotNull(it.toBraveLlmContextDocument(provider, sources)) }
        .orEmpty()
    return (generic + map + poi).distinctBy { it.url.normalizedUrlKey() }
}

internal fun JSONArray?.toBraveLlmContextItems(
    provider: String,
    sources: JSONObject?
): List<WebSearchDocument> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            item.toBraveLlmContextDocument(provider, sources)?.let(::add)
        }
    }
}

internal fun JSONObject.toBraveLlmContextDocument(
    provider: String,
    sources: JSONObject?
): WebSearchDocument? {
    val url = cleanString("url", "link", "href").ifBlank { return null }
    val source = sources?.optJSONObject(url)
    val snippets = optJSONArray("snippets")
        ?.toStringList()
        .orEmpty()
        .map { it.cleanWebSearchText() }
        .filter { it.isNotBlank() }
    val sourceTitle = source?.cleanString("title", "site_name").orEmpty()
    val sourceHost = source?.cleanString("hostname").orEmpty()
    val title = cleanString("title", "name")
        .ifBlank { sourceTitle }
        .ifBlank { sourceHost }
        .ifBlank { url }
    return WebSearchDocument(
        title = title,
        url = url,
        snippet = snippets.take(2).joinToString("\n").limitForPrompt(1_000),
        content = snippets.joinToString("\n\n").limitForPrompt(3_000),
        provider = provider
    )
}
internal fun JSONObject.cleanString(vararg keys: String): String =
    keys.firstNotNullOfOrNull { key ->
        optString(key).takeIf { it.isNotBlank() && it != "null" }
    }.orEmpty().cleanWebSearchText()

internal fun String.parseCustomSearchDocuments(provider: String): List<WebSearchDocument> {
    val payload = trim()
    val results = if (payload.startsWith("[")) {
        JSONArray(payload)
    } else {
        val root = JSONObject(payload)
        root.firstSearchResultArray()
            ?: JSONArray()
    }
    return results.toSearchDocuments(provider)
}

internal fun JSONObject.firstSearchResultArray(): JSONArray? =
    firstArray("results", "items", "data", "organic_results", "organic", "hits")
        ?: optJSONObject("web")?.firstSearchResultArray()
        ?: optJSONObject("search")?.firstSearchResultArray()
        ?: optJSONObject("data")?.firstSearchResultArray()
        ?: optJSONObject("response")?.firstSearchResultArray()

internal fun JSONObject.firstArray(vararg keys: String): JSONArray? =
    keys.firstNotNullOfOrNull { key -> optJSONArray(key) }

internal fun String.extractErrorMessage(): String =
    runCatching {
        val root = JSONObject(this)
        root.cleanString("message", "detail", "error_description")
            .ifBlank {
                val error = root.opt("error")
                when (error) {
                    is JSONObject -> error.cleanString("message", "detail", "reason", "type")
                    is String -> error
                    else -> ""
                }
            }
            .ifBlank { root.toString().take(220) }
    }.getOrDefault(take(220))

internal fun JSONArray.toStringList(): List<String> = buildList {
    for (index in 0 until length()) {
        val value = optString(index).normalizeWhitespace()
        if (value.isNotBlank()) add(value)
    }
}
internal fun JSONArray?.toWebSearchSourceReferences(): List<ChatSourceReference> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val url = item.optString("url")
            if (url.isBlank()) continue
            add(
                ChatSourceReference(
                    title = item.optString("title").ifBlank { url },
                    url = url,
                    snippet = item.optString("snippet"),
                    provider = item.optString("provider"),
                    hostLabel = item.optString("hostLabel"),
                    trustLabel = item.optString("trustLabel"),
                    trustReason = item.optString("trustReason")
                )
            )
        }
    }
}

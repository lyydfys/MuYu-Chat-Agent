package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.mca.agent.security.AgentEgressOrigins
import java.net.URI
import org.json.JSONObject

/**
 * `web.fetch`: one bounded HTTP(S) GET whose HTML body is reduced to readable plain text.
 *
 * Why this is not a second browser engine: `core:dsh-browser` owns an isolated WebView (tabs, refs,
 * snapshots, clicks) and is the right tool when the page needs scripting or interaction. This tool is
 * the other half of the pair — a single stateless document fetch for content the model wants to
 * *read*, closer to `http.get` than to `browser_open`. It therefore reuses the exact same seams as
 * `http.get`: [AgentHttpTextClient] for transport and [AgentHttpGetGate] for run egress authorization,
 * so there is one transport policy and one authorization decision in the app, not two.
 *
 * Order of checks (nothing is sent before them):
 * 1. frozen tool/schema identity ([runBuiltInTool]);
 * 2. argument shape + destination policy ([AgentWebFetchDestinationRules]: http/https, public hosts);
 * 3. run-scoped gate authorization for the canonical origin;
 * 4. bounded transport call, then HTML → text and a `maxChars` truncation.
 */
class AgentWebFetchTool(
    private val client: AgentHttpTextClient,
    private val gate: AgentHttpGetGate = AgentHttpGetGate.DENY_ALL
) : AgentToolExecutor {
    override val descriptor: AgentToolDescriptor = AgentExtendedToolDescriptors.WEB_FETCH

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = false) {
            val fields = AgentToolArguments.parse(command)
            AgentToolArguments.rejectUnknown(fields, setOf("url", "maxChars", "timeoutMillis"))
            val rawUrl = AgentToolArguments.requiredText(fields, "url", AgentWebFetchDestinationRules.MAX_URL_CHARS)
            val destination = when (val checked = AgentWebFetchDestinationRules.check(rawUrl)) {
                is AgentWebFetchUrlResult.Valid -> checked
                is AgentWebFetchUrlResult.Invalid -> {
                    return@runBuiltInTool AgentToolResults.denied(
                        command,
                        descriptor,
                        checked.code,
                        checked.safeMessage
                    )
                }
            }
            val maxChars = AgentToolArguments
                .optionalInteger(
                    fields,
                    "maxChars",
                    DEFAULT_MAX_CHARS.toLong(),
                    1L,
                    MAX_MAX_CHARS.toLong()
                )
                .toInt()
            val timeoutMillis = AgentToolArguments
                .optionalInteger(fields, "timeoutMillis", DEFAULT_TIMEOUT_MILLIS, MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS)

            val decision = gate.authorize(
                AgentHttpGetAuthorization(
                    runId = context.runId,
                    origin = destination.origin,
                    url = destination.url
                )
            )
            if (decision is AgentHttpGetGateDecision.Denied) {
                return@runBuiltInTool AgentToolResults.denied(
                    command,
                    descriptor,
                    decision.code.ifBlank { "POLICY_EGRESS_DENIED" },
                    decision.safeMessage.ifBlank { "This run is not authorized for the requested origin." }
                )
            }

            when (
                val outcome = client.get(
                    AgentHttpGetRequest(
                        url = destination.url,
                        origin = destination.origin,
                        // The byte budget is a transport guard; maxChars is the model-facing budget.
                        maxBytes = MAX_BODY_BYTES,
                        timeoutMillis = timeoutMillis
                    )
                )
            ) {
                is AgentHttpGetOutcome.Failure -> AgentToolResults.failure(
                    command,
                    descriptor,
                    outcome.code,
                    outcome.safeMessage,
                    possibleSideEffects = outcome.possibleSideEffects
                )

                is AgentHttpGetOutcome.Response -> {
                    val raw = outcome.text
                    val document = raw?.let { htmlToPlainText(it) }
                    val fetched = document?.text.orEmpty()
                    val bounded = truncateForModel(fetched, maxChars)
                    var text = bounded.text
                    val data = JSONObject()
                        .put("url", destination.url)
                        .put("origin", destination.origin)
                        .put("statusCode", outcome.statusCode)
                        .put("contentType", outcome.contentType ?: JSONObject.NULL)
                        .put("bodyBytes", outcome.bodyBytes)
                        .put("textAvailable", document != null)
                        .put("title", document?.title?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                        .put("totalChars", fetched.length)
                        .put("chars", text.length)
                        .put("truncated", bounded.truncated || outcome.truncated)
                        .put("text", text)
                    val shrink: (JSONObject) -> Boolean = { target ->
                        val current = text
                        if (current.length <= 1) {
                            false
                        } else {
                            text = current.substring(0, current.length / 2)
                            target.put("text", text)
                            target.put("chars", text.length)
                            target.put("truncated", true)
                            true
                        }
                    }
                    AgentToolResults.success(
                        command = command,
                        descriptor = descriptor,
                        summary = buildString {
                            append("Fetched and extracted ")
                            append(text.length)
                            append(" chars from ")
                            append(destination.origin)
                            append(" (HTTP ${outcome.statusCode})")
                            if (bounded.truncated) append("; truncated at the maxChars budget.")
                            else if (outcome.truncated) append("; the body was truncated at the byte budget.")
                            if (document == null) append("; the payload is not UTF-8 text.")
                        },
                        data = data,
                        truncated = bounded.truncated || outcome.truncated,
                        shrink = shrink
                    )
                }
            }
        }

    private companion object {
        const val DEFAULT_MAX_CHARS = 4_000
        const val MAX_MAX_CHARS = 20_000

        /** Transport guard: enough for one article, far below the response budget of `http.get`. */
        const val MAX_BODY_BYTES = 512 * 1024

        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val MIN_TIMEOUT_MILLIS = 1_000L
        const val MAX_TIMEOUT_MILLIS = 15_000L

        /** Visible marker so a truncated answer can never be mistaken for the whole page. */
        const val TRUNCATION_MARKER = "…（已截断）"
    }
}

/** Destination policy for `web.fetch`: http *and* https, still never loopback or private hosts. */
internal sealed interface AgentWebFetchUrlResult {
    data class Valid(val url: String, val origin: String) : AgentWebFetchUrlResult

    data class Invalid(val code: String, val safeMessage: String) : AgentWebFetchUrlResult
}

/**
 * Unlike `http.get` (https only, by architecture 11.3) this tool accepts plain HTTP pages too — the
 * task is reading documentation and plain sites, and the request still carries no credentials and
 * follows no redirect. Everything that could make it an SSRF primitive stays refused.
 */
internal object AgentWebFetchDestinationRules {
    const val MAX_URL_CHARS: Int = 2_048

    fun check(raw: String): AgentWebFetchUrlResult {
        if (raw.isBlank() || raw.length > MAX_URL_CHARS) return invalid("The URL is blank or too long.")
        if (raw.any { it.isWhitespace() || it < ' ' }) {
            return invalid("The URL contains whitespace or control characters.")
        }
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return invalid("The URL is not a valid absolute URI.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return invalid("Only absolute http and https URLs are supported.")
        }
        if (uri.rawUserInfo != null) return invalid("The URL must not embed credentials.")
        if (uri.rawFragment != null) return invalid("The URL must not contain a fragment.")
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) return invalid("The URL must name a host.")
        if (uri.port != -1 && uri.port !in 1..65_535) return invalid("The URL port is out of range.")
        val defaultPort = if (scheme == "https") 443 else 80
        val origin = if (uri.port == -1 || uri.port == defaultPort) {
            "$scheme://$host"
        } else {
            "$scheme://$host:${uri.port}"
        }
        if (AgentEgressOrigins.isLocalOrPrivate(origin)) {
            return invalid("The URL host is a loopback, link-local or private address.")
        }
        return AgentWebFetchUrlResult.Valid(uri.toString(), origin)
    }

    private fun invalid(detail: String): AgentWebFetchUrlResult.Invalid =
        AgentWebFetchUrlResult.Invalid("POLICY_EGRESS_DENIED", detail)
}

/** Plain-text projection of one HTML document. */
internal data class WebFetchText(val title: String, val text: String)

/**
 * Reduces an HTML document to readable text.
 *
 * Deliberately a pure function rather than a jsoup dependency: the app must not grow a parser for
 * this, and the reduction only has to be honest — drop everything that is not visible prose, keep
 * block boundaries as blank lines so paragraphs stay paragraphs, and decode the handful of entities
 * that actually appear in page text. Scripts and styles are removed *including their content*, which
 * is the failure mode of a naive tag strip (it leaves JavaScript in the answer).
 */
internal fun htmlToPlainText(html: String): WebFetchText {
    val titleMatch = TITLE.find(html)
    val title = titleMatch?.groupValues?.get(1)?.let(::decodeEntities)?.collapseWhitespace().orEmpty()

    var body = html
    DROP_BLOCKS.forEach { block -> body = block.replace(body, " ") }
    body = COMMENT.replace(body, " ")
    // Block-level boundaries become paragraph breaks *before* tags are removed, so "one div per line"
    // markup still reads as paragraphs instead of one run-on sentence.
    body = BLOCK_BOUNDARY.replace(body, "\n\n")
    body = LINE_BREAK.replace(body, "\n")
    // Inline markup is removed *without* a space: "<strong>加粗</b>结尾" must read as one word, and
    // block boundaries were already turned into paragraph breaks above.
    body = TAG.replace(body, "")
    body = decodeEntities(body)
    body = body
        .replace('\u00A0', ' ')
        .lines()
        .map { it.collapseWhitespace() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

    val text = if (title.isBlank()) body else if (body.isBlank()) title else "$title\n\n$body"
    return WebFetchText(title = title, text = text)
}

/** Cut to [maxChars] with a visible marker; the marker is inside the budget, never appended past it. */
internal fun truncateForModel(text: String, maxChars: Int): WebFetchTruncation {
    if (text.length <= maxChars) return WebFetchTruncation(text, truncated = false)
    val marker = "…（已截断）"
    val keep = (maxChars - marker.length).coerceAtLeast(0)
    if (keep == 0) return WebFetchTruncation(text.take(maxChars), truncated = true)
    var cut = keep
    // Never split a surrogate pair: half an emoji renders as a broken glyph.
    if (text[cut - 1].isHighSurrogate()) cut -= 1
    return WebFetchTruncation(text.substring(0, cut) + marker, truncated = true)
}

internal data class WebFetchTruncation(val text: String, val truncated: Boolean)

private fun String.collapseWhitespace(): String = replace(Regex("[ \\t\\u000B\\u000C]+"), " ").trim()

private fun decodeEntities(value: String): String {
    if (!value.contains('&')) return value
    var decoded = value
    ENTITIES.forEach { (entity, replacement) -> decoded = decoded.replace(entity, replacement) }
    NUMERIC_ENTITY.replace(decoded) { match ->
        // `&#123;` (decimal) and `&#x1F600;` (hex) share one pattern; only one group is populated.
        val hex = match.groupValues[1]
        val code = if (hex.isNotEmpty()) hex.toIntOrNull(16) else match.groupValues[2].toIntOrNull()
        if (code == null || code <= 0 || code > 0x10FFFF) match.value else String(Character.toChars(code))
    }.let { decoded = it }
    return decoded
}

private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
private val COMMENT = Regex("(?s)<!--.*?-->")
private val LINE_BREAK = Regex("(?i)<br\\s*/?>")
private val BLOCK_BOUNDARY = Regex(
    "(?i)</?(p|div|section|article|header|footer|main|aside|nav|h[1-6]|li|ul|ol|blockquote|pre|tr|table|figure|figcaption)\\b[^>]*>"
)
private val TAG = Regex("(?s)<[^>]+>")
private val NUMERIC_ENTITY = Regex("&#(?:[xX]([0-9a-fA-F]+)|(\\d+));")

/** Blocks removed with their content: their text is not page prose. */
private val DROP_BLOCKS = listOf(
    // The head is dropped so the title is not duplicated: it is read separately into [WebFetchText.title].
    Regex("(?is)<head[^>]*>.*?</head>"),
    Regex("(?is)<title[^>]*>.*?</title>"),
    Regex("(?is)<script[^>]*>.*?</script>"),
    Regex("(?is)<style[^>]*>.*?</style>"),
    Regex("(?is)<noscript[^>]*>.*?</noscript>"),
    Regex("(?is)<template[^>]*>.*?</template>"),
    Regex("(?is)<svg[^>]*>.*?</svg>"),
    Regex("(?is)<iframe[^>]*>.*?</iframe>"),
    Regex("(?s)<!\\[CDATA\\[.*?]]>")
)

private val ENTITIES = listOf(
    "&nbsp;" to " ",
    "&amp;" to "&",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&apos;" to "'",
    "&mdash;" to "—",
    "&ndash;" to "–",
    "&hellip;" to "…",
    "&middot;" to "·",
    "&laquo;" to "«",
    "&raquo;" to "»"
)

package com.muyuchat.core.engine

import com.google.ai.edge.litertlm.Content as LiteRtContent
import com.google.ai.edge.litertlm.Contents as LiteRtContents
import java.io.File
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * LiteRT-LM's Android Conversation API can carry images, but model artifacts
 * differ in whether they contain a visual encoder. This message is reserved
 * for cases where the loaded worker cannot accept an image transport at all;
 * a model-specific rejection is returned by the SDK at inference time.
 */
const val LITERT_LM_VISION_TRANSPORT_UNAVAILABLE_MESSAGE =
    "当前 LiteRT-LM 图像输入通道未就绪。下一步：重新加载模型；如果仍不可用，请切换到支持视觉的 GGUF/MNN/QNN。"

const val LITERT_LM_KNOWN_TEXT_ONLY_VISION_UNAVAILABLE_MESSAGE =
    "当前 LiteRT-LM 模型包是纯文本版，不包含图像视觉权重，无法识图。下一步：更换包含视觉组件的 LiteRT-LM 多模态包，或加载完整 MNN/GGUF 多模态模型。"

const val LITERT_LM_VISION_COMPONENTS_UNAVAILABLE_MESSAGE =
    "当前 LiteRT-LM 模型包没有报告可用的图像视觉组件，暂不能识图。下一步：确认下载的是包含视觉编码器的完整模型包，或改用完整 MNN/GGUF 多模态模型。"

/**
 * Keep image payloads bounded before Base64 decoding or a native decoder can
 * allocate a second full copy.  The same byte budget is used for inline,
 * downloaded, and local-file inputs so the API and UI paths cannot drift.
 */
const val MAX_LOCAL_VISION_IMAGE_BYTES: Long = 20L * 1024L * 1024L

/** Maximum decoded width or height accepted by the shared vision preflight. */
const val MAX_LOCAL_VISION_IMAGE_SIDE: Int = 16_384

/** Maximum decoded pixel count accepted before handing an image to native code. */
const val MAX_LOCAL_VISION_IMAGE_PIXELS: Long = 32L * 1024L * 1024L

/** Backwards-compatible name used by the inline Base64 estimator. */
const val MAX_LOCAL_VISION_INLINE_IMAGE_BYTES: Long = MAX_LOCAL_VISION_IMAGE_BYTES

/** Estimate decoded Base64 bytes without allocating a normalized copy of the payload. */
fun estimatedInlineVisionImageBytes(encoded: CharSequence): Long {
    var symbols = 0L
    var last = '\u0000'
    var previous = '\u0000'
    for (character in encoded) {
        if (character.isWhitespace()) continue
        symbols += 1L
        previous = last
        last = character
    }
    val padding = when {
        symbols >= 2L && last == '=' && previous == '=' -> 2L
        symbols >= 1L && last == '=' -> 1L
        else -> 0L
    }
    return ((symbols * 3L) / 4L - padding).coerceAtLeast(0L)
}

fun inlineVisionImageWithinLimit(
    encoded: CharSequence,
    maxBytes: Long = MAX_LOCAL_VISION_INLINE_IMAGE_BYTES
): Boolean = maxBytes >= 0L && estimatedInlineVisionImageBytes(encoded) <= maxBytes

/**
 * A small number of published LiteRT bundles are known to have had their
 * vision weights removed. Keep this package fact explicit so MCA does not
 * send an image into a text-only native delegate. Unknown bundles still get
 * the normal runtime attempt; lack of validation alone is not a blocker.
 */
internal fun isKnownTextOnlyLiteRtModel(path: String?): Boolean {
    val fileName = path?.let { runCatching { File(it).name }.getOrNull() }
        ?.lowercase(java.util.Locale.ROOT)
        .orEmpty()
    return fileName == "gemma-4-e2b-it-uncensored-max.litertlm"
}

/**
 * Transport readiness is separate from actual model vision support. Require a
 * loaded LiteRT runner and reject packages whose bounded metadata proves that
 * no visual encoder/adapter was shipped. Older worker stats that do not carry
 * the metadata field retain the transport-based compatibility fallback.
 */
fun liteRtVisionInputAvailable(statsJson: String): Boolean {
    val stats = runCatching { JSONObject(statsJson) }.getOrNull() ?: return false
    if (!stats.optBoolean("loaded", false) || stats.optBoolean("visionModelKnownTextOnly", false)) {
        return false
    }
    // New runners publish this field from LiteRT-LM's bounded header metadata.
    // Only reject an explicit false; absence means the stats came from an old
    // worker and should keep the previous transport fallback behavior.
    if (stats.has("visionModelVisualComponentsPresent") &&
        !stats.optBoolean("visionModelVisualComponentsPresent", false)
    ) {
        return false
    }
    return stats.optBoolean("visionInputTransportReady", false) ||
        stats.optBoolean("runnerReady", false)
}

internal sealed interface LiteRtPromptPart {
    data class Text(val value: String) : LiteRtPromptPart
    data class ImageFile(val absolutePath: String) : LiteRtPromptPart
}

internal data class LiteRtMessageSpec(
    val role: String,
    val parts: List<LiteRtPromptPart>
) {
    val content: String
        get() = parts.filterIsInstance<LiteRtPromptPart.Text>().joinToString(separator = "") { it.value }

    val hasImageInput: Boolean
        get() = parts.any { it is LiteRtPromptPart.ImageFile }
}

/**
 * A LiteRT SDK image transport only means that MCA can serialize an image part.
 * Model vision readiness is published only after a successful real image turn.
 */
internal fun liteRtVisionReady(loaded: Boolean, successfulImageTurn: Boolean): Boolean =
    loaded && successfulImageTurn

/** Parse MCA's OpenAI-style image parts into LiteRT's real image-content API. */
internal fun parseLiteRtMessages(messagesJson: String): List<LiteRtMessageSpec> {
    val array = JSONArray(messagesJson.ifBlank { "[]" })
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val role = canonicalLiteRtMessageRole(item.optString("role", "user")) ?: continue
            val parts = liteRtPromptParts(item.opt("content"))
            add(LiteRtMessageSpec(role = role, parts = parts))
        }
    }
}

internal fun LiteRtMessageSpec.toLiteRtContents(): LiteRtContents = LiteRtContents.of(
    parts.map { part ->
        when (part) {
            is LiteRtPromptPart.Text -> LiteRtContent.Text(part.value)
            is LiteRtPromptPart.ImageFile -> LiteRtContent.ImageFile(part.absolutePath)
        }
    }
)

private fun liteRtPromptParts(raw: Any?): List<LiteRtPromptPart> = when (raw) {
    is String -> listOf(LiteRtPromptPart.Text(raw))
    is JSONArray -> buildList {
        for (index in 0 until raw.length()) {
            val part = raw.optJSONObject(index) ?: continue
            when (part.optString("type").trim().lowercase()) {
                "text" -> add(LiteRtPromptPart.Text(part.optString("text")))
                "image_url", "input_image", "image" -> {
                    val source = imageSource(part)
                        ?: error("LiteRT-LM image part did not include an image URL or file path.")
                    add(LiteRtPromptPart.ImageFile(nativeLiteRtImagePath(source)))
                }
            }
        }
    }
    is JSONObject -> {
        when {
            raw.has("type") -> liteRtPromptParts(JSONArray().put(raw))
            raw.has("text") -> listOf(LiteRtPromptPart.Text(raw.optString("text")))
            else -> emptyList()
        }
    }
    null -> emptyList()
    else -> listOf(LiteRtPromptPart.Text(raw.toString()))
}

private fun imageSource(part: JSONObject): String? {
    val value = part.opt("image_url")
        ?: part.opt("image")
        ?: part.opt("data")
        ?: part.opt("url")
        ?: part.opt("path")
    return when (value) {
        is JSONObject -> sequenceOf("url", "data", "path", "uri")
            .mapNotNull { key -> value.optString(key).takeIf(String::isNotBlank) }
            .firstOrNull()
        is String -> value.takeIf(String::isNotBlank)
        else -> null
    }
}

private fun nativeLiteRtImagePath(source: String): String {
    val value = source.trim()
    require(!value.startsWith("data:", ignoreCase = true)) {
        "LiteRT-LM 图片尚未转换为本地文件；请重试图片预处理。"
    }
    require(!value.startsWith("http://", ignoreCase = true) &&
        !value.startsWith("https://", ignoreCase = true) &&
        !value.startsWith("content://", ignoreCase = true)
    ) {
        "LiteRT-LM 需要可读取的本地图片文件；请重试图片预处理。"
    }
    val file = if (value.startsWith("file:", ignoreCase = true)) {
        File(URI(value))
    } else {
        File(value)
    }.canonicalFile
    require(file.isAbsolute && file.isFile && file.canRead()) {
        "LiteRT-LM 图片文件不存在或不可读：${file.path}"
    }
    return file.absolutePath
}

/**
 * Detects structured image input in a ChatRequest messages JSON payload.
 *
 * We inspect parsed JSON instead of doing a raw substring search so a prompt
 * that merely mentions the words `image_url` or `input_image` is not rejected.
 * The detector accepts the OpenAI `image_url` shape and the common
 * `input_image`/`image` variants used by local API clients.
 */
internal fun liteRtLmMessagesContainImageInput(messagesJson: String): Boolean {
    val root = runCatching { JSONArray(messagesJson) }.getOrNull() ?: return false

    fun isImageKey(key: String): Boolean = when (key.trim().lowercase()) {
        "image_url",
        "imageurl",
        "input_image",
        "inputimage",
        "image_base64",
        "image_data",
        "image" -> true
        else -> false
    }

    fun isImageType(value: String): Boolean =
        value.trim().lowercase().let { it == "image_url" || it == "input_image" || it == "image" }

    fun contains(value: Any?): Boolean = when (value) {
        is JSONObject -> {
            if (isImageType(value.optString("type"))) {
                true
            } else {
                val keys = value.keys()
                var found = false
                while (keys.hasNext() && !found) {
                    val key = keys.next()
                    found = isImageKey(key) || contains(value.opt(key))
                }
                found
            }
        }
        is JSONArray -> {
            var found = false
            for (index in 0 until value.length()) {
                if (contains(value.opt(index))) {
                    found = true
                    break
                }
            }
            found
        }
        else -> false
    }

    return contains(root)
}

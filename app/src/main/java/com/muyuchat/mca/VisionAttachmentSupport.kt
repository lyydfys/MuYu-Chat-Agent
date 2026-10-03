package com.muyuchat.mca

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.security.MessageDigest
import org.json.JSONObject
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.engine.LITERT_LM_KNOWN_TEXT_ONLY_VISION_UNAVAILABLE_MESSAGE
import com.muyuchat.core.engine.LITERT_LM_VISION_COMPONENTS_UNAVAILABLE_MESSAGE
import com.muyuchat.core.engine.LITERT_LM_VISION_TRANSPORT_UNAVAILABLE_MESSAGE
import com.muyuchat.core.engine.visionDeduplicationKey

/**
 * Pure support for vision attachments: decode options, downscaling, send-boundary deduplication and
 * the "why is local vision unavailable" decision.
 *
 * Extracted from `MainViewModel` because none of it needs the owner's state:
 *
 *  * [visionBitmapOptions] and [scaledToMaxEdge] are static Android image operations;
 *  * [deduplicateVisionAttachmentsForSend] already took the `Context` it needs as a parameter;
 *  * [localVisionUnavailableMessage] used to read `engine.nativeStatsJson()` itself. It now receives
 *    that JSON as an argument, which is what makes it a **pure decision** over
 *    `(state, nativeStatsJson)` -- the caller keeps the engine access, the wording lives here.
 *
 * **The names differ from the ViewModel members they replace on purpose.** A same-named member
 * shadows a top-level function inside the class, so a forwarder left behind would call itself.
 * Kotlin cannot qualify a top-level extension call, so renaming is the only safe form.
 *
 * The remaining vision members stay in the ViewModel for now: `mimeTypeForUri`,
 * `decodeBitmapForLocalVision`, `withCompressedInlineData` and `withCompressedFileForLocalVision`
 * all reach for `contentResolver`, `cacheDir` and the application context, so they need an Android
 * adapter seam that this slice deliberately does not invent yet.
 */

/**
 * Decode options for a vision input.
 *
 * `RGB_565` on purpose: the input is immediately re-encoded as JPEG, and this keeps a malformed or
 * very large user image from exhausting the app process before the isolated native runner gets a
 * chance to reject it.
 */
internal fun visionDecodeOptions(sampleSize: Int): BitmapFactory.Options =
    BitmapFactory.Options().apply {
        inSampleSize = sampleSize.coerceAtLeast(1)
        inPreferredConfig = Bitmap.Config.RGB_565
        inScaled = false
        inDither = false
    }

/** Downscales so the longer edge is at most [maxEdge]; returns `this` when it already fits. */
internal fun Bitmap.scaledDownToMaxEdge(maxEdge: Int): Bitmap {
    val currentMax = maxOf(width, height)
    if (currentMax <= maxEdge) return this
    val scale = maxEdge.toFloat() / currentMax.toFloat()
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1),
        true
    )
}

/**
 * Final send-boundary image deduplication.
 *
 * The shared engine helper cannot open Android `content://` providers, so this pass hashes provider
 * bytes before a user turn is persisted. It is deliberately the last step before the send, so a
 * picker/preflight race cannot publish the same imported photo twice. Provider bytes are only hashed
 * up to [CHAT_SEND_ATTACHMENT_IDENTITY_MAX_BYTES]; anything larger falls back to the attachment's own
 * key rather than reading an unbounded stream.
 */
internal fun List<ChatImageAttachment>.deduplicatedVisionAttachmentsForSend(
    context: Context
): List<ChatImageAttachment> {
    if (size < 2) return this
    val seen = LinkedHashSet<String>(size)
    return filter { attachment ->
        val source = attachment.uriString.trim()
        val identity = if (source.startsWith("content:", ignoreCase = true)) {
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                val input = requireNotNull(context.contentResolver.openInputStream(Uri.parse(source)))
                var total = 0L
                input.use { stream ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= CHAT_SEND_ATTACHMENT_IDENTITY_MAX_BYTES)
                        digest.update(buffer, 0, read)
                    }
                }
                "bytes:" + digest.digest().joinToString("") { byte ->
                    "%02x".format(java.util.Locale.ROOT, byte.toInt() and 0xff)
                }
            }.getOrElse { attachment.visionDeduplicationKey() }
        } else {
            attachment.visionDeduplicationKey()
        }
        seen.add(identity)
    }
}

/**
 * Why local vision is unavailable for the loaded model, in the user's words.
 *
 * Pure over `(state, nativeStatsJson)`: [nativeStatsJson] is the raw `engine.nativeStatsJson()` the
 * caller already fetches, so this function never touches the engine itself. The branches and their
 * exact wording are unchanged from the ViewModel member it replaces -- including the distinction
 * between "no model loaded", the three LiteRT-LM states, the MNN state, a missing projector, and the
 * four `visionFailureReason` values the native runner can report.
 */
internal fun localVisionUnavailableReason(state: MainUiState, nativeStatsJson: String?): String {
    val model = state.models.firstOrNull { it.id == state.loadedModelId }
    val nativeStats = runCatching { JSONObject(nativeStatsJson.orEmpty()) }.getOrNull()
    val nativeReason = nativeStats?.optString("visionFailureReason")
        ?.takeIf { it.isNotBlank() && it != "null" }
    return when {
        model == null ->
            "当前未加载本地模型。请在模型管理加载支持视觉的多模态 GGUF，或切换到支持图片输入的云端模型。"
        model.runtime == ChatModelRuntime.LITERT_LM &&
            nativeStats?.optBoolean("visionModelKnownTextOnly", false) == true ->
            LITERT_LM_KNOWN_TEXT_ONLY_VISION_UNAVAILABLE_MESSAGE
        model.runtime == ChatModelRuntime.LITERT_LM &&
            nativeStats?.has("visionModelVisualComponentsPresent") == true &&
            !nativeStats.optBoolean("visionModelVisualComponentsPresent", false) ->
            LITERT_LM_VISION_COMPONENTS_UNAVAILABLE_MESSAGE
        model.runtime == ChatModelRuntime.LITERT_LM ->
            LITERT_LM_VISION_TRANSPORT_UNAVAILABLE_MESSAGE
        model.runtime == ChatModelRuntime.MNN ->
            "当前 MNN 模型没有就绪的视觉组件。请加载包含可读 visual.mnn 的完整多模态包后重试。"
        model.visionProjectorPath.isNullOrBlank() ->
            "当前本地模型未启用识图。纯文本 GGUF 不能直接看图，请在模型管理为多模态模型绑定匹配的 mmproj / projector 文件后重新加载。"
        nativeReason == "mmproj_not_bound" || nativeReason == "mmproj_file_missing" ||
            nativeReason == "mmproj_file_empty" || nativeReason == "mmproj_file_unreadable" ->
            "视觉投影器已记录但没有传入当前 runner（${nativeReason}）。请重新加载该模型；如果仍失败，请重新绑定与主模型匹配的 mmproj / projector。"
        nativeReason == "native_capability_reports_no_vision" ->
            "当前 mmproj 与主模型或运行时不匹配，native runner 明确拒绝视觉能力。请更换同仓库、同架构的 mmproj / projector。"
        else ->
            "已绑定视觉投影器，但本地视觉 runner 还未就绪。请重新加载当前模型；如果仍失败，请更换与主模型匹配的 mmproj / projector。"
    }
}

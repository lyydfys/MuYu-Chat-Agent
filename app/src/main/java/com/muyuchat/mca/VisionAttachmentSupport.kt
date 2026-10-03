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
import android.app.Application
import android.util.Base64
import java.io.ByteArrayOutputStream
import com.muyuchat.core.engine.ChatMessage
import kotlinx.coroutines.Dispatchers
import java.io.File
import com.muyuchat.core.engine.ModelRuntimeIdentity
import java.util.UUID
import com.muyuchat.core.engine.inlineVisionImageWithinLimit
import com.muyuchat.core.engine.liteRtVisionInputAvailable
import kotlinx.coroutines.withContext
import com.muyuchat.core.engine.deduplicateVisionAttachments

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
 * `decodeBitmapForLocalVision`, `compressedInlineForCloud` and `compressedForLocalVision`
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

/**
 * The owner seam for the Context-bound half of vision attachment preparation.
 *
 * This interface **is** an Android adapter boundary: decoding, re-encoding and provider hashing
 * genuinely need a `ContentResolver` and a cache directory, so [context] is part of the contract
 * rather than something smuggled in. The rest is the small amount of owner state the availability
 * check reads -- through functions and properties, never as a whole `MainUiState` plus an arbitrary
 * method set.
 *
 * The pure helpers above ([visionDecodeOptions], [scaledDownToMaxEdge],
 * [deduplicatedVisionAttachmentsForSend], [localVisionUnavailableReason]) need none of this and stay
 * free functions, which is why they could move first.
 *
 * **The five context-bound function names differ from the ViewModel members they replace.** A
 * same-named member extension shadows a top-level extension inside the class, so a forwarder left
 * behind would call itself -- and Kotlin cannot qualify a top-level extension call, so renaming is
 * the only safe form.
 */
internal interface VisionAttachmentPort {

    /** Application context, for `contentResolver` and the vision cache directory. */
    val context: Context

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    /** The engine's raw native stats document. The caller reads it; this file judges it. */
    fun nativeStatsJson(): String

    /** The running engine's identity, checked against the projector fingerprint. */
    val activeRuntimeIdentity: ModelRuntimeIdentity?

    /** The projector stamp the engine reported at load time. */
    val activeVisionProjectorStamp: LoadedVisionProjectorStamp?
}

/**
 * The MIME type for a picked image: the provider's own type when it is an image, otherwise inferred
 * from the file extension, defaulting to `image/jpeg`.
 *
 * The provider read is wrapped: a picker can hand back a URI whose provider has since gone away, and
 * that must not fail the send -- the extension fallback is the answer in that case.
 */
internal fun visionMimeTypeForUri(port: VisionAttachmentPort, uriString: String): String {
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return "image/jpeg"
    return runCatching { port.context.contentResolver.getType(uri) }
        .getOrNull()
        ?.takeIf { it.startsWith("image/", ignoreCase = true) }
        ?: when (uriString.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
}

/**
 * Re-encodes every attachment that has no inline data into base64 JPEG, for a cloud vision request.
 * Deduplicates before and after, and caps the count per message.
 */
internal suspend fun List<ChatMessage>.preparedForCloudVision(
    port: VisionAttachmentPort
): List<ChatMessage> =
    withContext(Dispatchers.IO) {
        map { message ->
            if (message.imageAttachments.isEmpty()) {
                message
            } else {
                message.copy(
                    imageAttachments = message.imageAttachments
                        .deduplicateVisionAttachments()
                        .take(MAX_CHAT_IMAGES_PER_MESSAGE)
                        .map { attachment ->
                            if (attachment.hasInlineData) attachment else attachment.compressedInlineForCloud(port)
                        }
                        .deduplicateVisionAttachments()
                )
            }
        }
    }

/**
 * Materialises every attachment as a JPEG file for the local vision runner.
 *
 * On any failure the files **this call** created are deleted and the error is rethrown, so a partial
 * batch never leaks into the cache directory.
 */
internal suspend fun List<ChatMessage>.preparedForLocalVision(
    port: VisionAttachmentPort,
    createdFiles: MutableList<File>
): List<ChatMessage> = withContext(Dispatchers.IO) {
    try {
        map { message ->
            if (message.imageAttachments.isEmpty()) {
                message
            } else {
                message.copy(
                    imageAttachments = message.imageAttachments
                        .deduplicateVisionAttachments()
                        .take(MAX_CHAT_IMAGES_PER_MESSAGE)
                        .map { it.compressedForLocalVision(port, createdFiles) }
                        .deduplicateVisionAttachments()
                )
            }
        }
    } catch (error: Throwable) {
        createdFiles.forEach { file -> runCatching { file.delete() } }
        createdFiles.clear()
        throw error
    }
}

/**
 * Decodes, downscales and writes one attachment into the vision cache directory as JPEG 88.
 *
 * Both bitmaps are recycled, including the scaled copy when it is a different instance -- a vision
 * batch of four images would otherwise hold four full-size bitmaps alive at once.
 */
internal fun ChatImageAttachment.compressedForLocalVision(
    port: VisionAttachmentPort,
    createdFiles: MutableList<File>
): ChatImageAttachment {
    val bitmap = decodeVisionBitmap(port)
    val prepared = bitmap.scaledDownToMaxEdge(VisionDecodeBudget.MAX_EDGE)
    val width = prepared.width
    val height = prepared.height
    val visionDir = File(port.context.cacheDir, "vision_inputs").apply { mkdirs() }
    val outputFile = File(visionDir, "vision-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.jpg")
    try {
        outputFile.outputStream().use { output ->
            check(prepared.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
                "无法压缩图片：${name.ifBlank { "image" }}"
            }
        }
    } catch (error: Throwable) {
        outputFile.delete()
        throw error
    } finally {
        if (prepared !== bitmap && !prepared.isRecycled) prepared.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
    }
    createdFiles += outputFile
    return copy(
        uriString = Uri.fromFile(outputFile).toString(),
        mimeType = "image/jpeg",
        dataBase64 = "",
        width = width,
        height = height,
        sizeBytes = outputFile.length()
    )
}

/**
 * Decodes the attachment at a bounded sample size.
 *
 * Inline data is size-checked first (the 20 MB per-image limit) and rejected with a message that
 * tells the user what to do; a URI goes through the `ContentResolver`. Both paths refuse an image
 * whose dimensions cannot be read, because decoding it would only fail later and less clearly.
 */
internal fun ChatImageAttachment.decodeVisionBitmap(port: VisionAttachmentPort): Bitmap {
    if (hasInlineData) {
        val encoded = plainBase64()
        require(inlineVisionImageWithinLimit(encoded)) {
            "图片超过本地识图的 20 MB 单张上限，请先压缩图片后重试。"
        }
        val bytes = runCatching {
            Base64.decode(encoded, Base64.DEFAULT)
        }.getOrElse {
            error("无法解析内联图片：${name.ifBlank { "api-image" }}")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "内联图片没有可识别的尺寸：${name.ifBlank { "api-image" }}"
        }
        val sampleSize = VisionDecodeBudget.sampleSize(bounds.outWidth, bounds.outHeight, VisionDecodeBudget.MAX_EDGE)
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            visionDecodeOptions(sampleSize)
        ) ?: error("无法读取内联图片：${name.ifBlank { "api-image" }}")
    }
    val uri = Uri.parse(uriString)
    val resolver = port.context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) {
        "图片没有可识别的尺寸：${name.ifBlank { uriString }}"
    }
    val sampleSize = VisionDecodeBudget.sampleSize(bounds.outWidth, bounds.outHeight, VisionDecodeBudget.MAX_EDGE)
    return resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, visionDecodeOptions(sampleSize))
    } ?: error("无法读取图片：${name.ifBlank { uriString }}")
}

/**
 * Reads the attachment and re-encodes it as JPEG 86 base64 without wrapping, returning a copy that
 * carries the new bytes, MIME type and dimensions. Both bitmaps are recycled on every path.
 */
internal fun ChatImageAttachment.compressedInlineForCloud(port: VisionAttachmentPort): ChatImageAttachment {
    val uri = Uri.parse(uriString)
    val resolver = port.context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) {
        "图片没有可识别的尺寸：${name.ifBlank { uriString }}"
    }
    val sampleSize = VisionDecodeBudget.sampleSize(bounds.outWidth, bounds.outHeight, VisionDecodeBudget.MAX_EDGE)
    val bitmap = resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, visionDecodeOptions(sampleSize))
    } ?: error("无法读取图片：${name.ifBlank { uriString }}")
    val prepared = bitmap.scaledDownToMaxEdge(VisionDecodeBudget.MAX_EDGE)
    val width = prepared.width
    val height = prepared.height
    val bytes = try {
        ByteArrayOutputStream().use { output ->
            check(prepared.compress(Bitmap.CompressFormat.JPEG, 86, output)) {
                "无法压缩图片：${name.ifBlank { "image" }}"
            }
            output.toByteArray()
        }
    } finally {
        if (prepared !== bitmap && !prepared.isRecycled) prepared.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
    }
    return copy(
        mimeType = "image/jpeg",
        dataBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        width = width,
        height = height,
        sizeBytes = bytes.size.toLong()
    )
}

/**
 * Whether the loaded model can actually take an image right now.
 *
 * Four gates, in order: the runner reports loaded; the model accepts image input (LiteRT-LM has its
 * own check); and for llama.cpp the projector path **and** fingerprint must match the ones the engine
 * actually loaded -- a projector that was rebound but not reloaded must not be treated as ready.
 *
 * A missing projector path, fingerprint or stamp is a "no" rather than an exception: this decision
 * feeds a user-facing message, not a failure.
 */
internal suspend fun isLocalVisionRunnerAvailable(port: VisionAttachmentPort): Boolean {
    val nativeStats = runCatching { JSONObject(port.nativeStatsJson()) }.getOrNull()
        ?: return false
    if (!nativeStats.optBoolean("loaded", false)) return false
    val nativeVisionReady = nativeStats.optBoolean("visionReady", false)
    val state = port.uiState()
    val model = state.models.firstOrNull { it.id == state.loadedModelId }
    if (model == null) return false
    val imageInputReady = when (model.runtime) {
        ChatModelRuntime.LITERT_LM -> liteRtVisionInputAvailable(nativeStats.toString())
        else -> model.acceptsImageInput(nativeVisionReady)
    }
    if (!imageInputReady) return false
    if (model.runtime == ChatModelRuntime.LLAMA_CPP) {
        val expected = model.visionProjectorPath?.let(::File)?.let {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
        }
        val actual = nativeStats.optString("mmprojPath").trim()
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.let { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        if (expected != null && actual != expected) return false
        val loadedFingerprint = port.activeRuntimeIdentity?.projectorFingerprint
            ?.takeIf(String::isNotBlank) ?: return false
        val stamp = port.activeVisionProjectorStamp ?: return false
        if (expected == null || !stamp.matches(File(expected), loadedFingerprint)) return false
    }
    return true
}

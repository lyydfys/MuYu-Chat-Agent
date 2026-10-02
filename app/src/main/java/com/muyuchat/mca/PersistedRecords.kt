package com.muyuchat.mca

import android.net.Uri
import java.io.File
import java.util.UUID
import com.muyuchat.core.engine.ChatImageAttachment

// The record types the app persists and renders from: memories, image and file assets,
// image generation jobs and specs, upscale jobs and specs, and the status enums for each.
//
// Moved here verbatim from MainViewModel during P2. These are pure data shapes plus the two
// functions that resolve a final negative prompt for execution; they were already top-level
// declarations above the class.
//
// This is data-compatibility territory. The field names, types, defaults and order in these
// classes are what the Room entities, the exported schemas and the JSON in imported and
// exported backups agree on, so a rename or a reordering is a migration rather than a
// refactor. The move changes none of them.
//
// There is not a single private declaration in this file -- every type here is internal or
// public -- so nothing needed its visibility widened, and every referencing file in this
// package reaches these exactly as before.
//
// No field, type, default or order was edited.

data class MemoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val assistantId: String = AssistantRecord.DEFAULT_ID,
    val scope: String = "assistant",
    val content: String,
    val source: String = "manual",
    val createdAt: Long = System.currentTimeMillis()
)

data class ImageAssetRecord(
    val id: String,
    val name: String,
    val uriString: String,
    val source: String,
    val prompt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val generationMetadataJson: String = "",
    val favorite: Boolean = false,
    val chatSessionId: String? = null,
    val projectId: String? = null
) {
    fun toInputAttachment(): String = buildString {
        append("【上传图片：").append(name).append("】\n")
        if (prompt.isNotBlank()) {
            append("描述：").append(prompt.trim()).append("\n")
        }
        append(uriString)
    }

    fun toChatAttachment(mimeType: String = "image/jpeg"): ChatImageAttachment =
        ChatImageAttachment(
            name = name,
            uriString = uriString,
            mimeType = mimeType,
            width = width,
            height = height,
            sizeBytes = sizeBytes
        )

    fun deleteLocalCopy(ownedRoot: File): Boolean = runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val root = ownedRoot.canonicalFile
                val file = uri.path?.let(::File)?.canonicalFile ?: return@runCatching false
                if (file.parentFile != root) return@runCatching false
                !file.exists() || file.delete() || !file.exists()
            } else {
                true
            }
        }.getOrDefault(false)
}

data class FileAssetRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mimeType: String = "text/plain",
    val text: String,
    val preview: String = text.toFilePreview(),
    val truncated: Boolean = false,
    val source: String = "uploaded",
    val createdAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = text.toByteArray(Charsets.UTF_8).size.toLong(),
    val chatSessionId: String? = null,
    val projectId: String? = null
) {
    fun toInputAttachment(): String = buildString {
        append("【上传文件：").append(name).append("】\n")
        append(text.trim())
        if (truncated) append("\n\n（文件较大，已截取前 64KB）")
    }
}

enum class ImageGenerationStatusRecord(
    val label: String,
    val failed: Boolean = false,
    val terminal: Boolean = false
) {
    QUEUED("排队"),
    GENERATING("生成中"),
    CANCEL_REQUESTED("停止中"),
    DONE("完成", terminal = true),
    CANCELLED("已取消", terminal = true),
    FAILED("失败", failed = true, terminal = true)
}

data class ImageGenerationJobSpec(
    val prompt: String,
    val backend: ImageBackend,
    val localModelSnapshot: LocalImageModelRecord? = null,
    val cloudConfigSnapshot: CloudApiConfig? = null,
    val modelId: String,
    val modelName: String,
    val inputDraft: LocalImageInputDraft,
    val options: LocalImageGenerationOptions,
    val promptExecution: LocalImagePromptExecution? = null,
    val chatSessionId: String? = null,
    val chatMessageId: String? = null
) {
    init {
        require(prompt.isNotBlank()) { "Image generation job prompt must not be blank." }
        require(modelId.isNotBlank()) { "Image generation job modelId must not be blank." }
        require(modelName.isNotBlank()) { "Image generation job modelName must not be blank." }
        require(
            (backend == ImageBackend.LOCAL && localModelSnapshot != null && cloudConfigSnapshot == null) ||
                (backend == ImageBackend.CLOUD && cloudConfigSnapshot != null && localModelSnapshot == null)
        ) { "Image generation job backend must have exactly one matching model snapshot." }
        require(promptExecution == null || backend == ImageBackend.LOCAL) {
            "Only a local image job may carry local prompt execution evidence."
        }
        promptExecution?.let { execution ->
            require(execution.originalPrompt == prompt &&
                execution.originalNegativePrompt == options.negativePrompt
            ) { "Image generation prompt execution must match the captured request." }
        }
    }

    fun effectivePrompt(): String = promptExecution?.effectivePrompt ?: prompt

    fun effectiveOptions(
        base: LocalImageGenerationOptions = options
    ): LocalImageGenerationOptions = promptExecution?.let { execution ->
        base.copy(negativePrompt = execution.effectiveNegativePrompt)
    } ?: base

    fun toHistoryMetadata(nativeExecutionJson: String = ""): ImageGenerationHistoryMetadata =
        ImageGenerationHistoryMetadata(
            backend = backend,
            modelId = modelId,
            modelName = modelName,
            requestPrompt = prompt,
            options = if (backend == ImageBackend.LOCAL) {
                options.copy(
                    taskMode = inputDraft.taskMode,
                    strength = inputDraft.strength,
                    controlStrength = inputDraft.controlStrength
                )
            } else {
                LocalImageGenerationOptions()
            },
            inputDraft = if (backend == ImageBackend.LOCAL) inputDraft else LocalImageInputDraft(),
            promptExecution = promptExecution,
            nativeExecutionJson = nativeExecutionJson
        )
}

/**
 * Resolves the exact negative text that any local backend will encode. A model default is not an
 * executed input when CFG is disabled; an explicit non-empty user value remains an invalid request
 * because silently ignoring it would misrepresent the generated pixels.
 */
internal fun resolveLocalImageFinalNegativePromptForExecution(
    finalNegativePrompt: LocalImageFinalNegativePrompt,
    useCfg: Boolean
): LocalImageFinalNegativePrompt {
    if (useCfg) return finalNegativePrompt
    if (finalNegativePrompt.source == LocalImageNegativePromptSource.USER &&
        finalNegativePrompt.value.isNotEmpty()
    ) {
        throw LocalImageProductContractException(
            code = "execution_contract_unsupported",
            message = "A negativePrompt cannot affect pixels when useCfg=false."
        )
    }
    return if (finalNegativePrompt.source == LocalImageNegativePromptSource.MODEL_DEFAULT) {
        LocalImageFinalNegativePrompt(
            value = "",
            source = LocalImageNegativePromptSource.EMPTY
        )
    } else {
        finalNegativePrompt
    }
}

/** Kept for source compatibility while every runtime now uses the shared resolver above. */
@Deprecated(
    message = "Use resolveLocalImageFinalNegativePromptForExecution.",
    replaceWith = ReplaceWith("resolveLocalImageFinalNegativePromptForExecution(finalNegativePrompt, useCfg)")
)
internal fun resolveQnnFinalNegativePromptForExecution(
    finalNegativePrompt: LocalImageFinalNegativePrompt,
    useCfg: Boolean
): LocalImageFinalNegativePrompt = resolveLocalImageFinalNegativePromptForExecution(
    finalNegativePrompt = finalNegativePrompt,
    useCfg = useCfg
)

data class ImageGenerationJobRecord(
    val id: String,
    val prompt: String,
    val status: ImageGenerationStatusRecord,
    val backend: ImageBackend,
    val modelId: String? = null,
    val modelName: String = "",
    val spec: ImageGenerationJobSpec? = null,
    val imageAssetId: String? = null,
    val imageAssetIds: List<String> = emptyList(),
    val completedProgressCount: Int = 0,
    val previewUriString: String? = null,
    val previewMode: String = "",
    val previewStep: Int = 0,
    val previewRevision: Long = 0L,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val message: String = "",
    val startedAtMillis: Long = System.currentTimeMillis(),
    val startedAtElapsedMs: Long? = null
)

enum class ImageUpscaleStatusRecord(
    val label: String,
    val failed: Boolean = false,
    val terminal: Boolean = false
) {
    QUEUED("排队"),
    RUNNING("放大中"),
    CANCEL_REQUESTED("停止中"),
    DONE("完成", terminal = true),
    CANCELLED("已取消", terminal = true),
    FAILED("失败", failed = true, terminal = true)
}

data class ImageUpscaleJobSpec(
    val sourceImageSnapshot: ImageAssetRecord,
    val upscalerSnapshot: LocalImagePreparedUpscaler,
    val targetScale: Int,
    val tileSize: Int,
    val threads: Int
) {
    init {
        require(targetScale in setOf(2, 3, 4)) { "Upscale target scale must be 2, 3, or 4." }
        require(tileSize in 32..1_024 && tileSize % 8 == 0) { "Invalid upscale tile size." }
        require(threads in 1..64) { "Invalid upscale thread count." }
    }
}

data class ImageUpscaleJobRecord(
    val id: String,
    val spec: ImageUpscaleJobSpec,
    val status: ImageUpscaleStatusRecord,
    val resultImageAssetId: String? = null,
    val message: String = "",
    val startedAtMillis: Long = System.currentTimeMillis()
)

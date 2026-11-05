package com.muyuchat.mca

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * The ViewModel members `createLocalGeneratedImageAsset` actually reached for, one member each.
 *
 * The member moved out of `MainViewModel` whole: it ran the local worker request, bound the prepared
 * prompt execution evidence to the resolved profile, recorded the execution outcome, and published
 * one `ImageAssetRecord` per worker output inside the image-asset write mutex. That made it the
 * second half of the product local-image path, and it could only be read through a 19k-line class.
 *
 * Six members, and nothing else:
 *
 * * [activeLocalImageWorkerRequestId] is the read/write pair around the worker call -- the same
 *   field `cancelActiveImageGeneration` observes, so it stays a property rather than being passed
 *   in as a value;
 * * [localImageWorkerClient] is the worker gateway; the `generate` call keeps its original
 *   named arguments, including `onProgress`;
 * * [recordLocalImageExecutionOutcome] writes the verification outcome twice in the original: once
 *   with the failure message inside the catch, once with `null` after the binding validation;
 * * [executionWithQnnVerificationEvidence] produces the native execution JSON that the history
 *   metadata is stamped with;
 * * [imageAssetWriteMutex] is the single-writer lock the publication loop runs under;
 * * [imageAssetDirectory] is the directory the published file is written into.
 *
 * Nothing else in the body was a ViewModel member. The helpers it calls
 * (`resolveLocalImageExecutionProfile`, `imageExtensionForMime`, `writeImageAssetBytesAtomically`,
 * `validateLocalImagePromptExecutionBinding`, `normalizedForPromptExecutionProfile`,
 * `bindToEffectiveExecutionProfile`) are top-level declarations in this same package, so they stay
 * direct calls and the body reads unchanged.
 *
 * ## What was not touched
 *
 * The body is the original, line for line. Only the six member accesses above became port calls.
 * No condition, no ordering, no user-visible wording, no exception type and no `require` message
 * changed: the `"本地 native 生图执行失败。"` fallback inside the catch, the three
 * `"Generated image ..."` messages, the `Local image ... evidence`/`lineage` guards, the
 * `imageAssetWriteMutex.withLock` critical section, the `writtenFiles` cleanup in the catch, and
 * the `ImageAssetRecord` field order are all the ones that were there before.
 *
 * ## The platform tail
 *
 * The publication loop is the one part a plain JVM test cannot execute: the atomic write finishes
 * with `AndroidParentDirectorySyncer`, and the record needs `BitmapFactory` and `Uri.fromFile` --
 * three `android.jar` stubs that throw outside a device or Robolectric. They are deliberately left
 * inline rather than widened into the port, because none of them is a ViewModel member and the port
 * is exactly the set of members the body used. The behaviour is therefore unchanged, and the JVM
 * test drives the member up to the publication boundary (see
 * `CreateLocalGeneratedImageAssetOperationsTest`).
 */
internal interface CreateLocalGeneratedImageAssetPort {

    /** `activeLocalImageWorkerRequestId`: published before the worker call, cleared after it. */
    var activeLocalImageWorkerRequestId: String?

    /** The local worker client, in the one-operation shape the body calls. */
    val localImageWorkerClient: LocalGeneratedImageWorkerGateway

    /** Records actual worker execution; `null` means the execution succeeded. */
    fun recordLocalImageExecutionOutcome(model: LocalImageModelRecord, failureMessage: String?)

    /** Stamps the execution JSON with the QNN verification evidence. */
    fun executionWithQnnVerificationEvidence(
        model: LocalImageModelRecord,
        result: LocalImageResult,
        options: LocalImageGenerationOptions,
        requestId: String,
        attemptId: String,
        entryPoint: QnnImageEvidenceEntryPoint
    ): String

    /** The image-asset single-writer lock the publication loop runs under. */
    val imageAssetWriteMutex: Mutex

    /** The directory published image assets are written into. */
    val imageAssetDirectory: File
}

/**
 * `LocalImageWorkerClient.generate` narrowed to the one call this operation makes.
 *
 * The ViewModel keeps the concrete client; this is the interface a JVM test can fake without a
 * `Context`. Parameter names and types are the worker client's own, so the delegation is a pure
 * forward with named arguments.
 */
internal interface LocalGeneratedImageWorkerGateway {

    suspend fun generate(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        inputDraft: LocalImageInputDraft,
        requestId: String,
        batchLineage: ImageGenerationBatchLineage?,
        onProgress: (LocalImageProgress) -> Unit
    ): LocalImageResult
}

/**
 * `MainViewModel.createLocalGeneratedImageAsset` moved out whole (signature and body were
 * `MainViewModel.kt:16849..16969` when it moved).
 *
 * Returns one `ImageAssetRecord` per worker output, in worker order. A failure before publication
 * propagates unchanged -- the caller that runs this inside `executeLocalImageBatchPlan` owns the
 * batch-level failure handling, exactly as it did before. A failure during publication deletes every
 * file already written in this call before rethrowing.
 */
internal object CreateLocalGeneratedImageAssetOperations {

    suspend fun createLocalGeneratedImageAsset(
        port: CreateLocalGeneratedImageAssetPort,
        requestId: String,
        prompt: String,
        model: LocalImageModelRecord,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        chatSessionId: String? = null,
        generationMetadata: ImageGenerationHistoryMetadata,
        batchLineage: ImageGenerationBatchLineage? = null,
        outputLineages: List<ImageGenerationBatchLineage>,
        onProgress: (LocalImageProgress) -> Unit = {}
    ): List<ImageAssetRecord> {
        val result = try {
            port.activeLocalImageWorkerRequestId = requestId
            try {
                port.localImageWorkerClient.generate(
                    model = model,
                    prompt = prompt,
                    options = options,
                    inputDraft = inputDraft,
                    onProgress = onProgress,
                    requestId = requestId,
                    batchLineage = batchLineage
                )
            } finally {
                if (port.activeLocalImageWorkerRequestId == requestId) {
                    port.activeLocalImageWorkerRequestId = null
                }
            }
        } catch (error: Throwable) {
            if (error !is CancellationException && error !is LocalImageWorkerCancelledException) {
                port.recordLocalImageExecutionOutcome(model, error.message ?: "本地 native 生图执行失败。")
            }
            throw error
        }
        val effectiveBundleRoot = model.bundleRoot
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isDirectory)
            ?: File(model.path).parentFile?.takeIf(File::isDirectory)
        val preparedPromptExecution = requireNotNull(generationMetadata.promptExecution) {
            "Local image generation is missing prepared prompt execution evidence."
        }
        require(
            prompt == preparedPromptExecution.effectivePrompt &&
                options.negativePrompt == preparedPromptExecution.effectiveNegativePrompt
        ) { "Local image worker inputs do not match prepared prompt execution evidence." }
        val effectivePromptExecution = if (model.runtime == LocalImageRuntime.MEDIATEK_NEURON) {
            // The Neuron worker proves its own v3/v4 manifest evidence; there is no SD execution
            // profile to rebind prompt evidence to.
            preparedPromptExecution
        } else {
            val effectiveProfile = resolveLocalImageExecutionProfile(
                model = model,
                options = options.normalizedForPromptExecutionProfile(model.runtime),
                bundleRoot = effectiveBundleRoot
            ).profile
            preparedPromptExecution.bindToEffectiveExecutionProfile(effectiveProfile).also { bound ->
                validateLocalImagePromptExecutionBinding(
                    promptExecution = bound,
                    expectedProfile = effectiveProfile,
                    executionMetadataJson = result.executionMetadataJson
                )
            }
        }
        port.recordLocalImageExecutionOutcome(model, failureMessage = null)
        require(outputLineages.size == result.outputs.size) {
            "Image batch lineage count does not match worker outputs."
        }
        val nativeGenerationMetadata = generationMetadata
            .copy(promptExecution = effectivePromptExecution)
            .withNativeExecution(port.executionWithQnnVerificationEvidence(
                model = model,
                result = result,
                options = options,
                requestId = batchLineage?.parentRequestId ?: requestId,
                attemptId = requestId,
                entryPoint = QnnImageEvidenceEntryPoint.MAIN_ACTIVITY
            ))
        val timestamp = System.currentTimeMillis()
        val requestToken = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        return port.imageAssetWriteMutex.withLock {
            val writtenFiles = mutableListOf<File>()
            try {
                result.outputs.map { output ->
                    val outputLineage = outputLineages[output.index]
                    val actualSeed = requireNotNull(output.seed) {
                        "Generated image ${output.index} is missing its actual seed."
                    }
                    require(actualSeed == outputLineage.seed.toLong()) {
                        "Generated image ${output.index} seed does not match its batch lineage."
                    }
                    val generationMetadataJson = nativeGenerationMetadata
                        .forBatchOutput(outputLineage)
                        .toJsonString()
                    val extension = imageExtensionForMime(output.mimeType)
                    val suffix = if (result.outputs.size == 1) "" else "-${output.index.toString().padStart(3, '0')}"
                    val outputFile = writeImageAssetBytesAtomically(
                        directory = port.imageAssetDirectory,
                        fileName = "local-image-$timestamp-$requestToken$suffix.$extension",
                        bytes = output.bytes
                    )
                    writtenFiles += outputFile
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(outputFile.absolutePath, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                        "Generated image ${output.index} could not be decoded after publication."
                    }
                    ImageAssetRecord(
                        id = UUID.randomUUID().toString(),
                        name = "Local Image ${java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}$suffix.$extension",
                        uriString = Uri.fromFile(outputFile).toString(),
                        source = "generated:${model.runtime.label}",
                        prompt = generationMetadata.requestPrompt,
                        sizeBytes = outputFile.length(),
                        width = bounds.outWidth,
                        height = bounds.outHeight,
                        generationMetadataJson = generationMetadataJson,
                        chatSessionId = chatSessionId
                    )
                }
            } catch (error: Throwable) {
                writtenFiles.forEach { file -> runCatching { file.delete() } }
                throw error
            }
        }
    }
}

// R3-S30：本地生图端口的两个依赖助手自 MainViewModel 顶层化（体逐字；store/门/application 参数化）。

internal fun recordLocalImageExecutionOutcomeFor(
    localImageModelStore: LocalImageModelStore,
    readinessGate: ManagedRuntimeReadinessRefreshGate,
    store: UiStateWriter<ImageModelSelectionFields>,
    model: LocalImageModelRecord,
    failureMessage: String?
) {
    if (
        model.runtime != LocalImageRuntime.MNN_DIFFUSION &&
        model.runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP
    ) {
        return
    }
    val succeeded = failureMessage == null
    val current = localImageModelStore.loadModels().firstOrNull { it.id == model.id } ?: model
    val updated = current.copy(
        verificationStatus = if (succeeded) {
            LocalImageVerificationStatus.PASSED
        } else {
            LocalImageVerificationStatus.FAILED
        },
        verificationMessage = if (succeeded) {
            "真实图片生成执行成功。"
        } else {
            failureMessage.orEmpty().ifBlank { "本地 native 生图执行失败。" }
        },
        verifiedAt = System.currentTimeMillis()
    )
    val models = localImageModelStore.updateModel(updated)
    readinessGate.invalidate()
    store.update { state -> state.copy(localImageModels = models) }
}

internal fun executionWithQnnVerificationEvidenceFor(
    application: Application,
    model: LocalImageModelRecord,
    result: LocalImageResult,
    options: LocalImageGenerationOptions,
    requestId: String,
    attemptId: String,
    entryPoint: QnnImageEvidenceEntryPoint
): String {
    if (model.runtime != LocalImageRuntime.QNN_HTP) return result.executionMetadataJson
    val execution = JSONObject(result.executionMetadataJson)
    runCatching {
        val bundleRoot = requireNotNull(model.qnnBundleRootForVerification())
        val resolved = resolveLocalImageExecutionProfile(
            model, options.normalizedForPromptExecutionProfile(model.runtime), bundleRoot
        ).layers.resolved
        val fullRegression = options.taskMode == LocalImageTaskMode.TEXT_TO_IMAGE &&
            resolved.width == 512 && resolved.height == 512 && resolved.steps == 20
        val evidence = QnnImageExecutionEvidence.fromNativeImageExecution(
            level = if (fullRegression) QnnImageEvidenceLevel.FULL_IMAGE_REGRESSION
                else QnnImageEvidenceLevel.PRODUCT_IMAGE_SMOKE,
            entryPoint = entryPoint,
            requestId = requestId,
            attemptId = attemptId,
            expected = QnnImageRunParameters(resolved.width, resolved.height, resolved.steps, resolved.seed),
            executionMetadataJson = result.executionMetadataJson,
            output = result.bytes
        )
        execution.put("qnnVerificationStamp", JSONObject(qnnImageVerificationStampFor(
            application, bundleRoot, execution = evidence
        )))
    }.onFailure { error ->
        execution.put("qnnVerificationEvidenceError", error.message ?: error.javaClass.simpleName)
    }
    return execution.toString()
}

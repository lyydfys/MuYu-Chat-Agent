package com.muyuchat.mca

import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.muyuchat.api.local.localApiPublicModelId
import com.muyuchat.feature.chat.ImageGenerationUiTaskMode
import java.security.MessageDigest

// The Local API's local-image model catalogue, and validation of the preview paths it
// publishes.
//
// Moved here verbatim from MainViewModel during P2. These were already top-level declarations
// above the class; the move gives them a file named for what they do.
//
// This is protocol-adjacent: localImageApiCatalogEntries builds the list of image models the
// authenticated Local API advertises, resolveLocalImageApiModel turns a requested model id
// back into a model, and validatedLocalImagePreviewSource decides which published preview
// paths may be served. A divergence here is visible to an API client, not only in the UI.
//
// Kept in com.muyuchat.mca: no import changes anywhere, and the four tests that call these
// directly keep working unchanged.
//
// Nothing needed its visibility widened. The eight private declarations here -- two id
// constants, the base-id helper, and five preview path constants and patterns -- are used
// nowhere outside this file and stay private.
//
// No model id, prefix, path pattern or validation branch was edited.

internal fun supportsAuthenticatedLocalImageCount(
    runtime: LocalImageRuntime,
    imageCount: Int
): Boolean = imageCount in 1..ImageGenerationBatchLineage.MAX_BATCH_COUNT

private const val LOCAL_IMAGE_API_MODEL_ID_PREFIX = "image:"
private val LOCAL_IMAGE_API_MODEL_ID_TOKEN = Regex("[A-Za-z0-9._-]{1,200}")

internal data class LocalImageApiCatalogEntry(
    val model: LocalImageModelRecord,
    val apiId: String,
    val payload: JSONObject
)

/**
 * Builds the public image-model extension for `/v1/models` without serializing the backing
 * [LocalImageModelRecord]. In particular, model paths, bundle roots, hashes, verification text,
 * and source metadata never enter this payload.
 *
 * Chat ids remain untouched. Image ids use a separate namespace and reserve every raw image id,
 * so an image alias cannot collide with either a chat id or the legacy raw id accepted by the
 * Images API.
 */
internal fun localImageApiCatalogEntries(
    chatModelIds: Collection<String>,
    imageModels: List<LocalImageModelRecord>
): List<LocalImageApiCatalogEntry> {
    val configuredModels = imageModels.filter(LocalImageModelRecord::configured)
    val usedIds = buildSet {
        addAll(chatModelIds)
        addAll(imageModels.map(LocalImageModelRecord::id))
    }.toMutableSet()

    // Duplicate persisted records can share an id while pointing at different roots.
    // Assign public aliases from the healthiest record first so the unsuffixed API id
    // never resolves to a stale parent directory. This mirrors resolveLocalImageApiModel.
    val orderedModels = configuredModels.sortedWith(
        compareByDescending<LocalImageModelRecord> { model ->
            val root = model.bundleRoot?.let(::File)
            if (root?.isDirectory == true &&
                listOf("unet.bin", "unet.mnn", "model.gguf").any { File(root, it).isFile }
            ) 1 else 0
        }
            .thenByDescending { model ->
                model.bundleRoot?.count { ch -> ch == File.separatorChar || ch == '/' } ?: -1
            }
    )

    return orderedModels.map { model ->
        val baseId = localImageApiModelBaseId(model.id)
        var apiId = baseId
        var suffix = 2
        while (!usedIds.add(apiId)) {
            apiId = "$baseId:$suffix"
            suffix += 1
        }

        val resolvedCapabilities = runCatching { model.imageCapabilitiesForUi() }
            .getOrNull()
            ?.takeIf { it.readinessError == null }
        val taskModes = resolvedCapabilities
            ?.supportedTaskModes
            ?.sortedBy { it.ordinal }
            .orEmpty()
        val taskModesJson = JSONArray().apply {
            taskModes.forEach { mode -> put(mode.wireName) }
        }
        // The synchronous Local API still exposes only the physical native request shape. UI
        // product batches are coordinated in MainViewModel and must not be advertised as one
        // forged native API batch.
        val maxBatchCount = resolvedCapabilities?.nativeMaxBatchCount
        val resolvedExecutionDefaults = resolvedCapabilities
            ?.executionDefaults
            ?.takeIf { executionDefaults ->
                executionDefaults.width > 0 &&
                    executionDefaults.height > 0 &&
                    executionDefaults.steps > 0 &&
                    executionDefaults.cfgScale.isFinite()
            }
        val defaults = resolvedExecutionDefaults
            ?.let { executionDefaults ->
                JSONObject()
                    .put("size", "${executionDefaults.width}x${executionDefaults.height}")
                    .put("width", executionDefaults.width)
                    .put("height", executionDefaults.height)
                    .put("steps", executionDefaults.steps)
                    .put("cfg_scale", executionDefaults.cfgScale)
                    .put("seed", executionDefaults.seed)
                    .put("sampler", executionDefaults.sampler)
            }
        val capabilityJson = JSONObject()
            .put("image_generation", true)
            .put(
                "task_modes",
                JSONArray().apply { taskModes.forEach { mode -> put(mode.wireName) } }
            )
            .put("max_batch_count", maxBatchCount ?: JSONObject.NULL)
            .put(
                "textual_inversion",
                resolvedCapabilities?.supportsTextualInversion ?: false
            )
            .put(
                "textual_inversion_formats",
                JSONArray().apply {
                    resolvedCapabilities
                        ?.supportedTextualInversionFormats
                        .orEmpty()
                        .sorted()
                        .forEach { format -> put(format) }
                }
            )
            .put("live_preview", resolvedCapabilities?.supportsLivePreview ?: false)
            .put("runtime_live_preview", resolvedCapabilities?.supportsLivePreview ?: false)
            .put("synchronous_live_preview", false)
            .put("preview_transport", "none")
            .put(
                "preview_mode",
                resolvedCapabilities?.previewMode?.wireName ?: JSONObject.NULL
            )
            .put(
                "default_preview_interval",
                resolvedCapabilities
                    ?.takeIf { it.supportsLivePreview }
                    ?.defaultPreviewInterval
                    ?: JSONObject.NULL
            )
            .put("ultrafix", resolvedCapabilities?.supportsUltraFix ?: false)
            .put(
                "ultrafix_dimensions",
                resolvedExecutionDefaults
                    ?.takeIf {
                        resolvedCapabilities?.supportsUltraFix == true &&
                            it.ultraFixMinWidth > 0 && it.ultraFixMinHeight > 0
                    }
                    ?.let { executionDefaults ->
                        JSONObject()
                            .put("min_width", executionDefaults.ultraFixMinWidth)
                            .put("max_width", executionDefaults.ultraFixMaxWidth)
                            .put("min_height", executionDefaults.ultraFixMinHeight)
                            .put("max_height", executionDefaults.ultraFixMaxHeight)
                            .put("width_multiple", executionDefaults.ultraFixWidthMultiple)
                            .put("height_multiple", executionDefaults.ultraFixHeightMultiple)
                            .put(
                                "required_tile_size",
                                executionDefaults.ultraFixRequiredTileSize
                                    .takeIf { it > 0 }
                                    ?: JSONObject.NULL
                            )
                    }
                    ?: JSONObject.NULL
            )
            .put(
                "supported_samplers",
                JSONArray().apply {
                    resolvedExecutionDefaults?.supportedSamplers.orEmpty().forEach(::put)
                }
            )
            .put(
                "samplers_by_task",
                JSONObject().apply {
                    taskModes.forEach { mode ->
                        val samplers = (if (mode in setOf(
                                ImageGenerationUiTaskMode.IMG2IMG,
                                ImageGenerationUiTaskMode.INPAINT
                            )) {
                            resolvedExecutionDefaults?.img2ImgSupportedSamplers
                        } else {
                            resolvedExecutionDefaults?.supportedSamplers
                        }).orEmpty()
                        put(
                            mode.wireName,
                            JSONArray().apply { samplers.forEach(::put) }
                        )
                    }
                }
            )

        LocalImageApiCatalogEntry(
            model = model,
            apiId = apiId,
            payload = JSONObject()
                .put("id", apiId)
                .put("object", "model")
                .put("owned_by", "local")
                .put("display_name", model.displayName)
                .put("type", "image_generation")
                .put("configured", true)
                .put("runtime", model.runtime.name)
                .put("family", model.family.name)
                .put("task", taskModes.firstOrNull()?.wireName ?: JSONObject.NULL)
                .put("task_modes", taskModesJson)
                .put("max_batch_count", maxBatchCount ?: JSONObject.NULL)
                .put("capabilities", capabilityJson)
                .apply { defaults?.let { put("defaults", it) } }
        )
    }
}

internal fun resolveLocalImageApiModel(
    requestedModelId: String,
    chatModelIds: Collection<String>,
    imageModels: List<LocalImageModelRecord>
): LocalImageModelRecord? {
    val requested = requestedModelId.trim()
    if (requested.isEmpty()) return null
    // Resolve persisted/downloaded identifiers even when the model is not
    // currently configured. The caller can then return the precise 409
    // "not ready" response instead of masking a known model as 404.
    imageModels.asSequence().filter { model ->
        val aliases = buildSet {
            add(model.id)
            addAll(model.aliases)
            addAll(model.aliases.map { "image:$it" })
            add(model.displayName)
            add(model.fileName)
            model.bundleRoot?.let { root ->
                add(File(root).name)
                add(File(root).nameWithoutExtension)
            }
        }
        aliases.any { it.isNotBlank() && it == requested }
    }.sortedWith(compareByDescending<LocalImageModelRecord> { it.configured }
        .thenByDescending { model ->
            val root = model.bundleRoot?.let(::File)
            if (root?.isDirectory == true &&
                listOf("unet.bin", "unet.mnn", "model.gguf").any { File(root, it).isFile }
            ) 1 else 0
        }
        .thenByDescending { it.bundleRoot?.count { ch -> ch == File.separatorChar || ch == '/' } ?: -1 })
        .firstOrNull()?.let { return it }
    localImageApiCatalogEntries(chatModelIds, imageModels)
        .firstOrNull { entry ->
            val model = entry.model
            val aliases = buildSet {
                add(entry.apiId)
                add(localApiPublicModelId(model.displayName, entry.apiId))
                // Accept stable identifiers used by downloaded manifests and older API clients.
                add(model.id)
                add(model.displayName)
                add(model.fileName)
                model.bundleRoot?.let { root ->
                    add(File(root).name)
                    add(File(root).nameWithoutExtension)
                }
            }
            aliases.any { alias -> alias.isNotBlank() && alias == requested }
        }
        ?.let { return it.model }
    // Preserve the pre-discovery Images API contract for callers that already persisted a raw id.
    return imageModels.firstOrNull { model ->
        model.id == requested || model.displayName == requested || model.fileName == requested
    }
}

private fun localImageApiModelBaseId(rawModelId: String): String {
    val normalized = rawModelId.trim()
    val segment = normalized.takeIf(LOCAL_IMAGE_API_MODEL_ID_TOKEN::matches)
        ?: MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
            }
    return LOCAL_IMAGE_API_MODEL_ID_PREFIX + segment
}

internal data class PublishedLocalImagePreview(
    val uriString: String,
    val mode: String,
    val step: Int,
    val revision: Long,
    val width: Int,
    val height: Int
)

private const val LOCAL_IMAGE_PREVIEW_OUTPUT_DIRECTORY = "local_image_outputs"
private const val LOCAL_IMAGE_PREVIEW_CHILD_REVISION_MASK = 0xffff_ffffL
private val STABLE_LOCAL_IMAGE_PREVIEW_FILE_REGEX = Regex(
    "^[A-Za-z0-9][A-Za-z0-9._-]*\\.preview-([01])\\.png$"
)
private val QNN_SHARED_IMAGE_PREVIEW_DIRECTORY_REGEX = Regex(
    "qnn-htp-[0-9]+-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
        "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.qnn-stage\\.json\\.previews"
)
private val QNN_LOCAL_IMAGE_PREVIEW_FILE_REGEX = Regex(
    "preview-([1-9][0-9]*)\\.png"
)

/**
 * Validates the native preview namespace before any bytes are copied into the UI-owned cache.
 *
 * Stable-diffusion.cpp keeps its two-slot `*.preview-0|1.png` files directly under the native
 * output directory. Shared-QNN writes immutable `preview-N.png` children under the request's
 * `<journal>.previews` directory. Product-level batches encode the output index in the high
 * 32 bits of [coordinatedRevision], while native files only carry the child revision.
 */
internal fun validatedLocalImagePreviewSource(
    cacheRoot: File,
    previewPath: String,
    coordinatedRevision: Long
): File {
    require(previewPath.isNotBlank()) { "Native image preview path must not be blank." }
    require(coordinatedRevision > 0L) { "Native image preview revision must be positive." }
    val outputIndex = coordinatedRevision ushr 32
    val childRevision = coordinatedRevision and LOCAL_IMAGE_PREVIEW_CHILD_REVISION_MASK
    require(outputIndex < ImageGenerationBatchLineage.MAX_BATCH_COUNT.toLong() && childRevision > 0L) {
        "Native image preview revision is outside the product batch range."
    }

    val canonicalCacheRoot = cacheRoot.canonicalFile
    val outputRoot = File(
        canonicalCacheRoot,
        LOCAL_IMAGE_PREVIEW_OUTPUT_DIRECTORY
    ).canonicalFile
    require(
        outputRoot.parentFile == canonicalCacheRoot &&
            outputRoot.name == LOCAL_IMAGE_PREVIEW_OUTPUT_DIRECTORY
    ) { "Native image output directory is not a direct app-cache child." }
    val source = File(previewPath).canonicalFile
    val sourceParent = source.parentFile
    val stableMatch = STABLE_LOCAL_IMAGE_PREVIEW_FILE_REGEX.matchEntire(source.name)
    val stableSlot = stableMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
    val stablePath = sourceParent == outputRoot && stableSlot == childRevision % 2L

    val qnnDirectory = sourceParent
    val qnnMatch = QNN_LOCAL_IMAGE_PREVIEW_FILE_REGEX.matchEntire(source.name)
    val qnnChildRevision = qnnMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
    val qnnSharedPath = qnnDirectory != null &&
        qnnDirectory.parentFile == outputRoot &&
        QNN_SHARED_IMAGE_PREVIEW_DIRECTORY_REGEX.matches(qnnDirectory.name) &&
        qnnChildRevision == childRevision
    require(stablePath || qnnSharedPath) {
        "Native image preview path is outside the request-scoped preview namespace."
    }
    return source
}

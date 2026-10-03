package com.muyuchat.mca

import com.muyuchat.core.engine.estimateLocalPromptTokens
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import com.muyuchat.feature.chat.ImagePromptTokenMeasurement
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What the prompt-token measurement needs from its owner.
 *
 * Six members: the model catalogue it resolves against, and the process-local caches that keep a
 * measurement from re-reading a tokenizer on every keystroke. Everything else the measurement does is
 * a top-level function or a native bridge, which is why 166 lines could move without state ownership.
 */
internal interface ImagePromptTokenMeasurementHost {
    /** The local image model catalogue to resolve the model choice against. */
    val models: List<LocalImageModelRecord>

    /** A measurement already computed for this cache key, or null. */
    fun cachedMeasurement(cacheKey: String): ImagePromptTokenMeasurement?

    /** Remembers a measurement for this cache key. */
    fun cacheMeasurement(cacheKey: String, measurement: ImagePromptTokenMeasurement)

    /** The tokenizer descriptor already resolved for this model key, or null. */
    fun cachedDescriptor(modelKey: String): LocalImagePromptTokenizerDescriptor?

    /** Remembers the resolved tokenizer descriptor for this model key. */
    fun cacheDescriptor(modelKey: String, descriptor: LocalImagePromptTokenizerDescriptor)
}

/**
 * Measures how many tokens a prompt will occupy for a local image model.
 *
 * Moved out of `MainViewModel` (166 lines, one state read, two cache fields). The body is the original
 * logic: the same model-choice prefix rule, the same `configured` gate, the same cache key including
 * the prompt, the same descriptor resolution and the same native routing with the `ESTIMATE`
 * short-circuit. Only the five owner interactions became [host] calls.
 *
 * Being a top-level function on a small interface, this is now directly testable with a fake host,
 * which the in-class version never was.
 */
internal suspend fun measureImagePromptTokens(
    host: ImagePromptTokenMeasurementHost,
    modelChoiceId: String,
    prompt: String,
): ImagePromptTokenMeasurement? = withContext(Dispatchers.IO) {
        val modelId = modelChoiceId
            .removePrefix(MainViewModel.LOCAL_IMAGE_MODEL_CHOICE_PREFIX)
            .takeIf { it != modelChoiceId && it.isNotBlank() }
            ?: return@withContext null
        val model = host.models.firstOrNull { it.matchesCatalogId(modelId) }
            ?: return@withContext null
        if (!model.configured) return@withContext null

        val key = "$modelId|${model.sha256.lowercase()}|${model.updatedAt}"
        val measurementCacheKey = "$key\u001f$prompt"
        host.cachedMeasurement(measurementCacheKey)?.let { cached ->
            return@withContext cached
        }
        val descriptor = host.cachedDescriptor(key) ?: runCatching {
            val root = (
                model.bundleRoot
                    ?.takeIf(String::isNotBlank)
                    ?.let(::File)
                    ?.takeIf(File::isDirectory)
                    ?: File(model.path).parentFile?.takeIf(File::isDirectory)
                )?.canonicalFile ?: return@runCatching null
            val profile = resolveLocalImageExecutionProfile(
                model = model,
                options = LocalImageGenerationOptions(),
                bundleRoot = root
            ).profile
            val backend = profile.tokenizer.backend
            val route = imagePromptTokenMeasurementRoute(
                runtime = profile.runtime,
                family = profile.family,
                backend = backend,
            ) ?: return@runCatching null
            // MNN_MTOK is a concrete tokenizer consumed by the same native bridge as
            // generation. It does not need a tokenizer.json sidecar; passing a null JSON
            // path lets the bridge open the verified tokenizer.mtok from bundleRoot and
            // keeps the UI token meter consistent with actual execution.
            val verifiedNativeMultilingualTokenizer = profile
                .textEncoderLanguage
                ?.evidence
                ?.takeIf { profile.hasVerifiedNativeSimplifiedChineseTextEncoder() }
                ?.let { evidence ->
                    runCatching {
                        qnnPinnedNativeMultilingualTokenizerJsonFile(root, evidence)
                    }.getOrNull()
                        ?: return@runCatching null
                }
            val tokenizerJson = if (backend == ImageTokenizerBackend.TOKENIZERS_CPP) {
                verifiedNativeMultilingualTokenizer ?: run {
                    val rootPrefix = root.path + File.separator
                    profile.tokenizer.assets.asSequence()
                        .filter { asset ->
                            asset.relativePath
                                .replace('\\', '/')
                                .substringAfterLast('/')
                                .equals("tokenizer.json", ignoreCase = true)
                        }
                        .map { asset -> File(root, asset.relativePath).canonicalFile }
                        .plus(
                            sequenceOf(
                                File(root, "tokenizer.json").canonicalFile,
                                File(root, "tokenizer/tokenizer.json").canonicalFile
                            )
                        )
                        .firstOrNull { file -> file.isFile && file.path.startsWith(rootPrefix) }
                        ?: return@runCatching null
                }
            } else {
                null
            }
            val tokenizerRoot = root
            val bosId = profile.tokenizer.bosId
                ?: return@runCatching null
            val eosId = profile.tokenizer.eosId
                ?: return@runCatching null
            val padId = profile.tokenizer.padId
                ?: return@runCatching null
            LocalImagePromptTokenizerDescriptor(
                modelKey = key,
                bundleRoot = tokenizerRoot,
                backend = backend,
                route = route,
                tokenizerJsonPath = tokenizerJson,
                bosId = bosId,
                eosId = eosId,
                padId = padId,
                maxTokens = profile.tokenizer.maxLength,
                promptWeightingEnabled = profile.tokenizer.supportsPromptWeighting,
            )
        }.getOrNull() ?: return@withContext null

        host.cacheDescriptor(key, descriptor)
        currentCoroutineContext().ensureActive()
        if (descriptor.route == ImagePromptTokenMeasurementRoute.ESTIMATE) {
            val measurement = ImagePromptTokenMeasurement(
                count = estimateLocalPromptTokens(prompt),
                maxTokens = descriptor.maxTokens,
                exact = false,
            )
            host.cacheMeasurement(measurementCacheKey, measurement)
            return@withContext measurement
        }
        val raw = runCatching {
            when (descriptor.route) {
                ImagePromptTokenMeasurementRoute.MNN_BRIDGE -> {
                    if (!NativeMnnDiffusionBridge.isAvailable) return@runCatching null
                    NativeMnnDiffusionBridge().measurePromptTokens(
                        bundleRoot = descriptor.bundleRoot.path,
                        tokenizerBackend = descriptor.backend.name,
                        tokenizerJsonPath = descriptor.tokenizerJsonPath?.path.orEmpty(),
                        prompt = prompt,
                        bosId = descriptor.bosId,
                        eosId = descriptor.eosId,
                        padId = descriptor.padId,
                        maxTokens = descriptor.maxTokens,
                        promptWeightingEnabled = descriptor.promptWeightingEnabled,
                    )
                }
                ImagePromptTokenMeasurementRoute.SDCPP_CLIP -> {
                    if (!NativeStableDiffusionBridge.isAvailable) return@runCatching null
                    NativeStableDiffusionBridge().measurePromptTokens(
                        tokenizerKind = "CLIP",
                        prompt = prompt,
                        maxTokens = descriptor.maxTokens,
                    )
                }
                ImagePromptTokenMeasurementRoute.ESTIMATE -> error("Handled before native routing.")
            }
        }.getOrNull() ?: return@withContext null
        currentCoroutineContext().ensureActive()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
        if (!json.optBoolean("ok", false) ||
            json.optString("backend") != descriptor.backend.name
        ) return@withContext null
        val count = json.opt("count")
            .takeIf { value -> value is Byte || value is Short || value is Int || value is Long }
            ?.let { (it as Number).toLong() }
            ?.takeIf { value -> value in 0L..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: return@withContext null
        val maxTokens = json.opt("maxTokens")
            .takeIf { value -> value is Byte || value is Short || value is Int || value is Long }
            ?.let { (it as Number).toLong() }
            ?.takeIf { value -> value in 1L..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: return@withContext null
        if (maxTokens != descriptor.maxTokens) return@withContext null
        val overflowOffset = json.opt("overflowByteOffset")
            .takeIf { value -> value is Byte || value is Short || value is Int || value is Long }
            ?.let { (it as Number).toLong() }
            ?.takeIf { value -> value in 0L..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?.let { offset -> utf8ByteOffsetToUtf16(prompt, offset) }
        val measurement = ImagePromptTokenMeasurement(
            count = count,
            maxTokens = maxTokens,
            overflowOffset = overflowOffset,
            exact = true,
        )
        currentCoroutineContext().ensureActive()
        host.cacheMeasurement(measurementCacheKey, measurement)
        measurement
}

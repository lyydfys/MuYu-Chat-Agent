package com.muyuchat.mca

import com.muyuchat.core.engine.estimateLocalPromptTokens
import com.muyuchat.feature.chat.ImagePromptTokenMeasurement
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [measureImagePromptTokens] with a hand-written fake host.
 *
 * The unit-test classpath only carries JUnit, org.json and sqlite-jdbc, so nothing here mocks,
 * shadows Android, or drives coroutine schedulers; `runBlocking` is the repository's existing way
 * to run a suspend function under test. Every case is asserted through what the function returns
 * and what it asks the host for: the measurement cache key it reads, the descriptor key it reads,
 * and the entries it writes.
 *
 * Real fixtures are used where the production gate is a real filesystem check
 * ([LocalImageModelRecord.configured] stats the model file), which is why each case creates a
 * temporary bundle instead of a mocked one.
 */
class ImagePromptTokenMeasurementHostTest {

    @Test
    fun `model choice without the local image prefix is rejected before any host lookup`() = runBlocking {
        withTemporaryBundle { root ->
            val host = RecordingHost(listOf(configuredModel(root, writePrimaryModel(root))))

            assertNull(measureImagePromptTokens(host, MODEL_ID, PROMPT))

            assertHostUntouched(host)
        }
    }

    @Test
    fun `blank model id after the prefix is rejected before any host lookup`() = runBlocking {
        withTemporaryBundle { root ->
            val host = RecordingHost(listOf(configuredModel(root, writePrimaryModel(root))))

            assertNull(measureImagePromptTokens(host, CHOICE_PREFIX, PROMPT))
            assertNull(measureImagePromptTokens(host, "$CHOICE_PREFIX   ", PROMPT))

            assertHostUntouched(host)
        }
    }

    @Test
    fun `model id missing from the catalogue is rejected before any cache lookup`() = runBlocking {
        withTemporaryBundle { root ->
            val host = RecordingHost(listOf(configuredModel(root, writePrimaryModel(root))))

            assertNull(measureImagePromptTokens(host, CHOICE_PREFIX + "not-in-catalogue", PROMPT))

            assertHostUntouched(host)
        }
    }

    @Test
    fun `model that is not configured is rejected before any cache lookup`() = runBlocking {
        withTemporaryBundle { root ->
            val missing = root.resolve("missing.gguf")
            val empty = root.resolve("empty.gguf").apply { writeBytes(ByteArray(0)) }
            val missingHost = RecordingHost(listOf(configuredModel(root, missing)))
            val emptyHost = RecordingHost(listOf(configuredModel(root, empty)))

            assertNull(measureImagePromptTokens(missingHost, CHOICE_PREFIX + MODEL_ID, PROMPT))
            assertNull(measureImagePromptTokens(emptyHost, CHOICE_PREFIX + MODEL_ID, PROMPT))

            assertHostUntouched(missingHost)
            assertHostUntouched(emptyHost)
        }
    }

    @Test
    fun `retired alias resolves the model and keys both caches by the requested id`() = runBlocking {
        withTemporaryBundle { root ->
            val host = RecordingHost(
                listOf(configuredModel(root, writePrimaryModel(root), aliases = listOf(ALIAS_ID)))
            )
            host.seedDescriptor(
                aliasKey(),
                seededDescriptor(
                    modelKey = aliasKey(),
                    root = root,
                    route = ImagePromptTokenMeasurementRoute.ESTIMATE,
                    backend = ImageTokenizerBackend.SDCPP_NATIVE,
                    maxTokens = SEEDED_MAX_TOKENS,
                )
            )

            val result = requireNotNull(measureImagePromptTokens(host, CHOICE_PREFIX + ALIAS_ID, PROMPT))

            assertEquals(SEEDED_MAX_TOKENS, result.maxTokens)
            assertFalse(result.exact)
            assertEquals(listOf(aliasKey()), host.descriptorReads)
            assertEquals(listOf("${aliasKey()}\u001f$PROMPT"), host.measurementWrites.keys.toList())
        }
    }

    @Test
    fun `measurement cache hit returns the cached value without resolving the descriptor`() = runBlocking {
        withTemporaryBundle { root ->
            val host = RecordingHost(listOf(configuredModel(root, writePrimaryModel(root))))
            val cached = ImagePromptTokenMeasurement(count = 42, maxTokens = 77, exact = true)
            host.seedMeasurement(measurementKey(), cached)

            assertEquals(cached, measureImagePromptTokens(host, CHOICE_PREFIX + MODEL_ID, PROMPT))

            assertEquals(listOf(measurementKey()), host.measurementReads)
            assertEquals(emptyList<String>(), host.descriptorReads)
            assertEquals(emptyMap<String, LocalImagePromptTokenizerDescriptor>(), host.descriptorWrites)
            assertEquals(emptyMap<String, ImagePromptTokenMeasurement>(), host.measurementWrites)
        }
    }

    @Test
    fun `descriptor cache hit is reused instead of resolving the bundle again`() = runBlocking {
        withTemporaryBundle { root ->
            val host = RecordingHost(listOf(configuredModel(root, writePrimaryModel(root))))
            host.seedDescriptor(
                modelKey(),
                seededDescriptor(
                    root = root,
                    route = ImagePromptTokenMeasurementRoute.ESTIMATE,
                    backend = ImageTokenizerBackend.SDCPP_NATIVE,
                    maxTokens = SEEDED_MAX_TOKENS,
                )
            )

            val result = requireNotNull(measureImagePromptTokens(host, CHOICE_PREFIX + MODEL_ID, PROMPT))

            // The temporary bundle has no tokenizer to resolve, so the seeded limit reaching the
            // measurement is the evidence that the cached descriptor was used.
            assertEquals(SEEDED_MAX_TOKENS, result.maxTokens)
            assertFalse(result.exact)
            assertNull(result.overflowOffset)
            assertEquals(estimateLocalPromptTokens(PROMPT), result.count)
            assertEquals(listOf(modelKey()), host.descriptorReads)
            assertEquals(listOf(modelKey()), host.descriptorWrites.keys.toList())
            assertEquals(listOf(measurementKey()), host.measurementWrites.keys.toList())
            assertEquals(result, host.measurementWrites[measurementKey()])
        }
    }

    @Test
    fun `descriptor cache miss resolves the bundle once and caches an estimated measurement`() = runBlocking {
        withTemporaryBundle { root ->
            // A plain imported bundle: no catalog identity, so resolution lands on the generic
            // compatible profile, which is the sdcpp route that estimates instead of tokenizing.
            val primary = writePrimaryModel(root, name = "local-image-model.gguf")
            writeBundleManifest(root, primary, id = "local_test_bundle", family = "QWEN_IMAGE")
            val host = RecordingHost(listOf(configuredModel(root, primary)))

            val result = requireNotNull(measureImagePromptTokens(host, CHOICE_PREFIX + MODEL_ID, PROMPT))

            assertEquals(listOf(modelKey()), host.descriptorReads)
            val descriptor = requireNotNull(host.descriptorWrites[modelKey()])
            assertEquals(modelKey(), descriptor.modelKey)
            assertEquals(root.canonicalPath, descriptor.bundleRoot.canonicalPath)
            assertEquals(ImagePromptTokenMeasurementRoute.ESTIMATE, descriptor.route)
            assertEquals(ImageTokenizerBackend.SDCPP_NATIVE, descriptor.backend)
            assertNull(descriptor.tokenizerJsonPath)
            assertTrue(descriptor.maxTokens > 0)
            assertFalse(result.exact)
            assertNull(result.overflowOffset)
            assertEquals(estimateLocalPromptTokens(PROMPT), result.count)
            assertEquals(descriptor.maxTokens, result.maxTokens)
            assertEquals(result, host.measurementWrites[measurementKey()])
        }
    }

    @Test
    fun `mnn bridge route without a native backend returns null and never caches a measurement`() = runBlocking {
        withTemporaryBundle { root ->
            val host = RecordingHost(listOf(configuredModel(root, writePrimaryModel(root))))
            host.seedDescriptor(
                modelKey(),
                seededDescriptor(
                    root = root,
                    route = ImagePromptTokenMeasurementRoute.MNN_BRIDGE,
                    backend = ImageTokenizerBackend.MNN_MTOK,
                )
            )

            assertNull(measureImagePromptTokens(host, CHOICE_PREFIX + MODEL_ID, PROMPT))

            assertEquals(listOf(modelKey()), host.descriptorWrites.keys.toList())
            assertEquals(emptyMap<String, ImagePromptTokenMeasurement>(), host.measurementWrites)
        }
    }

    @Test
    fun `sdcpp clip route without a native backend returns null and never caches a measurement`() = runBlocking {
        withTemporaryBundle { root ->
            val host = RecordingHost(listOf(configuredModel(root, writePrimaryModel(root))))
            host.seedDescriptor(
                modelKey(),
                seededDescriptor(
                    root = root,
                    route = ImagePromptTokenMeasurementRoute.SDCPP_CLIP,
                    backend = ImageTokenizerBackend.SDCPP_NATIVE,
                )
            )

            assertNull(measureImagePromptTokens(host, CHOICE_PREFIX + MODEL_ID, PROMPT))

            assertEquals(listOf(modelKey()), host.descriptorWrites.keys.toList())
            assertEquals(emptyMap<String, ImagePromptTokenMeasurement>(), host.measurementWrites)
        }
    }

    private fun assertHostUntouched(host: RecordingHost) {
        assertEquals(emptyList<String>(), host.measurementReads)
        assertEquals(emptyList<String>(), host.descriptorReads)
        assertEquals(emptyMap<String, ImagePromptTokenMeasurement>(), host.measurementWrites)
        assertEquals(emptyMap<String, LocalImagePromptTokenizerDescriptor>(), host.descriptorWrites)
    }

    private suspend fun <T> withTemporaryBundle(block: suspend (File) -> T): T {
        val root = Files.createTempDirectory("prompt-token-measurement").toFile().canonicalFile
        try {
            return block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writePrimaryModel(root: File, name: String = "model.gguf"): File =
        root.resolve(name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun writeBundleManifest(
        root: File,
        primary: File,
        id: String,
        family: String,
    ) {
        root.resolve("manifest.json").writeText(
            JSONObject()
                .put("id", id)
                .put("runtime", "STABLE_DIFFUSION_CPP")
                .put("family", family)
                .put("primaryFile", primary.name)
                .toString()
        )
    }

    private fun configuredModel(
        root: File,
        primary: File,
        aliases: List<String> = emptyList(),
        recommendationId: String? = null,
    ): LocalImageModelRecord = LocalImageModelRecord(
        id = MODEL_ID,
        displayName = primary.name,
        path = primary.absolutePath,
        fileName = primary.name,
        sizeBytes = primary.length(),
        sha256 = MODEL_SHA256,
        runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
        family = LocalImageModelFamily.QWEN_IMAGE,
        bundleRoot = root.absolutePath,
        recommendationId = recommendationId,
        aliases = aliases,
        updatedAt = MODEL_UPDATED_AT,
    )

    private fun seededDescriptor(
        root: File,
        route: ImagePromptTokenMeasurementRoute,
        backend: ImageTokenizerBackend,
        modelKey: String = modelKey(),
        maxTokens: Int = 77,
    ): LocalImagePromptTokenizerDescriptor = LocalImagePromptTokenizerDescriptor(
        modelKey = modelKey,
        bundleRoot = root,
        backend = backend,
        route = route,
        tokenizerJsonPath = null,
        bosId = 1,
        eosId = 2,
        padId = 0,
        maxTokens = maxTokens,
        promptWeightingEnabled = false,
    )

    private companion object {
        const val MODEL_ID = "m1"
        const val ALIAS_ID = "old-m1"
        const val MODEL_SHA256 = "AB12CD"
        const val MODEL_UPDATED_AT = 7L
        const val SEEDED_MAX_TOKENS = 12_345
        const val PROMPT = "a red panda drinking coffee in the rain"
        const val CHOICE_PREFIX = MainViewModel.LOCAL_IMAGE_MODEL_CHOICE_PREFIX

        /** "modelId|lowercase(sha256)|updatedAt", the key the descriptor cache is asked for. */
        fun modelKey(): String = "$MODEL_ID|ab12cd|$MODEL_UPDATED_AT"

        fun aliasKey(): String = "$ALIAS_ID|ab12cd|$MODEL_UPDATED_AT"

        /** The descriptor key plus the prompt, the key the measurement cache is asked for. */
        fun measurementKey(): String = "${modelKey()}\u001f$PROMPT"
    }
}

/**
 * Hand-written fake: it stores what is seeded, records every read and write separately, and never
 * pretends a missing entry exists. Writes also update the store so a second call can observe the
 * cached value, exactly like the process-local caches the host owns in production.
 */
private class RecordingHost(
    override val models: List<LocalImageModelRecord> = emptyList(),
) : ImagePromptTokenMeasurementHost {
    val measurementReads = mutableListOf<String>()
    val measurementWrites = linkedMapOf<String, ImagePromptTokenMeasurement>()
    val descriptorReads = mutableListOf<String>()
    val descriptorWrites = linkedMapOf<String, LocalImagePromptTokenizerDescriptor>()

    private val measurements = linkedMapOf<String, ImagePromptTokenMeasurement>()
    private val descriptors = linkedMapOf<String, LocalImagePromptTokenizerDescriptor>()

    fun seedMeasurement(cacheKey: String, measurement: ImagePromptTokenMeasurement) {
        measurements[cacheKey] = measurement
    }

    fun seedDescriptor(modelKey: String, descriptor: LocalImagePromptTokenizerDescriptor) {
        descriptors[modelKey] = descriptor
    }

    override fun cachedMeasurement(cacheKey: String): ImagePromptTokenMeasurement? {
        measurementReads += cacheKey
        return measurements[cacheKey]
    }

    override fun cacheMeasurement(cacheKey: String, measurement: ImagePromptTokenMeasurement) {
        measurementWrites[cacheKey] = measurement
        measurements[cacheKey] = measurement
    }

    override fun cachedDescriptor(modelKey: String): LocalImagePromptTokenizerDescriptor? {
        descriptorReads += modelKey
        return descriptors[modelKey]
    }

    override fun cacheDescriptor(modelKey: String, descriptor: LocalImagePromptTokenizerDescriptor) {
        descriptorWrites[modelKey] = descriptor
        descriptors[modelKey] = descriptor
    }
}

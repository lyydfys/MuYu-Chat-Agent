package com.muyuchat.mca.presentation

import com.muyuchat.api.local.ImageGenerationContractException
import com.muyuchat.api.local.ImageGenerationProviderException
import com.muyuchat.mca.ImageGenerationBatchLineage
import com.muyuchat.mca.LocalApiImageGenerationOwnership
import com.muyuchat.mca.LocalImageGenerationCoordinator
import com.muyuchat.mca.LocalImageGenerationOptions
import com.muyuchat.mca.LocalImageInputDraft
import com.muyuchat.mca.LocalImageLoraRecord
import com.muyuchat.mca.LocalImageModelFamily
import com.muyuchat.mca.LocalImageModelRecord
import com.muyuchat.mca.LocalImageNegativePromptSource
import com.muyuchat.mca.LocalImageOutput
import com.muyuchat.mca.LocalImagePreparedLora
import com.muyuchat.mca.LocalImageProductContractException
import com.muyuchat.mca.LocalImagePromptExecution
import com.muyuchat.mca.LocalImagePromptTransformationMethod
import com.muyuchat.mca.LocalImageResult
import com.muyuchat.mca.LocalImageRuntime
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.QnnImageEvidenceEntryPoint
import com.muyuchat.mca.localImageStructuralReadinessMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Behavioural coverage for [generateLocalApiImageOperations] -- the Local API Images protocol
 * adapter moved verbatim out of `MainViewModel.generateLocalApiImage`.
 *
 * The point of the move is that these assertions can now exist: the whole protocol path -- request
 * validation, model resolution, lease admission, batch planning, worker dispatch, native
 * execution-evidence admission and the response envelope -- runs against a hand-written fake port
 * on a plain JVM. No mockk, no Robolectric, no Turbine.
 *
 * The two `android.*` primitives the response needs are supplied by
 * [LocalApiImageGenerationPort.decodeImageBounds] / [LocalApiImageGenerationPort.encodeBase64NoWrap];
 * the fakes below are the JVM equivalents of the production calls (PNG IHDR probe and unwrapped
 * base64), so the asserted JSON is the same JSON the device produces.
 */
class LocalApiImageGenerationOperationsTest {

    // ------------------------------------------------------------------ happy path

    @Test
    fun `happy path publishes the exact images api response envelope`() = runBlocking {
        val fixture = Fixture()
        try {
            val png = pngBytes(width = 37, height = 29)
            val port = FakePort(fixture.model)
            port.worker.outputs = listOf(LocalImageOutput(bytes = png, seed = 42L, index = 0))
            port.worker.executionMetadataJson = executionEvidenceJson()

            assertEquals(
                "fixture must be structurally ready before the protocol path can succeed",
                null,
                fixture.model.localImageStructuralReadinessMessage()
            )

            val response = generateLocalApiImageOperations(
                requestId = "req-happy",
                body = """{"prompt":"a cat","model":"local-image-1","n":1,"seed":100}""",
                port = port
            )

            // ---- the envelope itself
            val json = JSONObject(response)
            assertEquals("req-happy", json.getString("request_id"))
            // The catalog alias is echoed exactly; a legacy raw id is echoed unchanged.
            assertEquals("local-image-1", json.getString("model"))
            assertTrue(json.getLong("created") > 0L)

            // ---- data[] is the wire contract for the generated image
            val data = json.getJSONArray("data")
            assertEquals(1, data.length())
            val entry = data.getJSONObject(0)
            assertEquals(0, entry.getInt("index"))
            assertEquals("image/png", entry.getString("mime_type"))
            assertEquals(37, entry.getInt("width"))
            assertEquals(29, entry.getInt("height"))
            assertEquals(42L, entry.getLong("seed"))
            assertEquals(Base64.getEncoder().encodeToString(png), entry.getString("b64_json"))

            // ---- execution evidence is admitted and exposed on the response
            val execution = json.getJSONObject("execution")
            assertTrue(execution.getBoolean("nativeExecution"))
            assertFalse(execution.getBoolean("fallback"))
            assertEquals(7L, execution.getLong("nativeGenerationSequence"))
            assertEquals(
                "STABLE_DIFFUSION_CPP",
                execution.getJSONObject("nativeEffective").getString("runtime")
            )
            assertEquals(
                "sdcpp.sd-turbo",
                execution.getJSONObject("resolvedProfile").getString("profileId")
            )
            val evidence = execution.getJSONArray("responseOutputEvidence")
            assertEquals(1, evidence.length())
            assertEquals(png.size, evidence.getJSONObject(0).getInt("sizeBytes"))
            assertEquals("image/png", evidence.getJSONObject(0).getString("mimeType"))

            // ---- prompt_processing carries the resolved prompt execution unchanged
            val promptProcessing = json.getJSONObject("prompt_processing")
            assertEquals("a cat", promptProcessing.getString("effectivePrompt"))
            assertEquals("EMPTY", promptProcessing.getString("negativePromptSource"))
            assertEquals("DIRECT", promptProcessing.getString("method"))

            // ---- the worker saw the resolved request, not the raw body
            assertEquals(1, port.worker.generateCount)
            assertEquals(fixture.model, port.worker.lastModel)
            assertEquals("a cat", port.worker.lastPrompt)
            assertEquals("req-happy", port.worker.lastRequestId)
            // The effective sampler is persisted before dispatch: sd-turbo defaults to euler_a.
            assertEquals("euler_a", port.worker.lastOptions!!.sampleMethod)
            assertEquals(100, port.worker.lastOptions!!.seed)
            assertEquals(1, port.worker.lastOptions!!.batchCount)
            // The effective negative prompt comes from the resolved prompt execution.
            assertEquals("", port.worker.lastOptions!!.negativePrompt)
            assertEquals(1, port.worker.releaseAwaitCount)

            // ---- admission ownership is fully unwound, even on the success path
            assertEquals(1, port.registerCount)
            assertEquals(1, port.unregisterCount)
            assertNull(port.activeOwnership)
            assertNull(port.activeLocalApiImageModelId)
            assertNull(port.activeLeaseRequestId)
            assertEquals(1, port.releaseCount)
            assertTrue(port.state.value.activeLocalImageLoraIds.isEmpty())
            assertTrue(port.state.value.activeLocalImageTextualInversionIds.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `sequential batch keeps the per-output index seed and dimensions`() = runBlocking {
        val fixture = Fixture(LocalImageRuntime.MNN_DIFFUSION)
        try {
            val first = pngBytes(width = 64, height = 64)
            val second = pngBytes(width = 32, height = 48)
            val port = FakePort(fixture.model)
            // MNN is not a native-batch runtime, so the plan runs one child per image: one worker
            // call each, and each child reports its own output index 0.
            port.worker.outputsPerCall = listOf(
                listOf(LocalImageOutput(bytes = first, seed = 11L, index = 0)),
                listOf(LocalImageOutput(bytes = second, seed = 12L, index = 0))
            )
            port.worker.executionMetadataJson = executionEvidenceJson(fixture.model.runtime.name)

            assertEquals(
                "fixture must be structurally ready before the protocol path can succeed",
                null,
                fixture.model.localImageStructuralReadinessMessage()
            )

            val json = JSONObject(
                generateLocalApiImageOperations(
                    requestId = "req-batch",
                    body = """{"prompt":"a cat","model":"local-image-1","n":2,"seed":100}""",
                    port = port
                )
            )

            assertEquals("req-batch", json.getString("request_id"))
            // One child per image, each with a derived request id and the next lineage seed.
            assertEquals(2, port.worker.generateCount)
            assertEquals(
                listOf("req-batch-batch-000", "req-batch-batch-001"),
                port.worker.requestIds
            )
            assertEquals(listOf(100, 101), port.worker.optionSeeds)

            val data = json.getJSONArray("data")
            assertEquals(2, data.length())
            // Per-child indices are re-based onto the parent batch offset.
            assertEquals(0, data.getJSONObject(0).getInt("index"))
            assertEquals(64, data.getJSONObject(0).getInt("width"))
            assertEquals(64, data.getJSONObject(0).getInt("height"))
            assertEquals(11L, data.getJSONObject(0).getLong("seed"))
            assertEquals(
                Base64.getEncoder().encodeToString(first),
                data.getJSONObject(0).getString("b64_json")
            )
            assertEquals(1, data.getJSONObject(1).getInt("index"))
            assertEquals(32, data.getJSONObject(1).getInt("width"))
            assertEquals(48, data.getJSONObject(1).getInt("height"))
            assertEquals(12L, data.getJSONObject(1).getLong("seed"))
            assertEquals(
                Base64.getEncoder().encodeToString(second),
                data.getJSONObject(1).getString("b64_json")
            )
            assertEquals(
                2,
                json.getJSONObject("execution").getJSONArray("responseOutputEvidence").length()
            )
        } finally {
            fixture.close()
        }
    }

    /**
     * Pins a pre-existing Local API defect this move must not hide.
     *
     * `n >= 2` on a stable-diffusion.cpp model takes the native-batch shortcut: one child that
     * declares `outputCount = n`. This adapter's `execute` lambda returns `listOf(result)` -- one
     * element -- while `executeLocalImageBatchPlan` requires the executor to return one entry per
     * declared output. The UI path returns one entry per image
     * (`createLocalGeneratedImageAsset` returns `List<ImageAssetRecord>`), so the shapes disagree and
     * the API request is rejected. `n == 1` and the sequential runtimes (MNN/QNN) are unaffected.
     *
     * The assertion records today's wire behaviour, so the refactor is provably behaviour-preserving
     * and a later fix will fail loudly here instead of silently changing the contract.
     */
    @Test
    fun `native batch on stable diffusion cpp rejects n greater than one`() = runBlocking {
        val fixture = Fixture(LocalImageRuntime.STABLE_DIFFUSION_CPP)
        try {
            val port = FakePort(fixture.model)
            port.worker.outputs = listOf(
                LocalImageOutput(bytes = pngBytes(64, 64), seed = 11L, index = 0),
                LocalImageOutput(bytes = pngBytes(64, 64), seed = 12L, index = 1)
            )
            port.worker.executionMetadataJson = executionEvidenceJson(fixture.model.runtime.name)

            val error = assertProviderFailure {
                generateLocalApiImageOperations(
                    requestId = "req-sdcpp-batch",
                    body = """{"prompt":"a cat","model":"local-image-1","n":2,"seed":100}""",
                    port = port
                )
            }

            assertEquals("invalid_image_input", error.code)
            assertEquals(422, error.httpStatus)
            assertEquals(
                "Image batch child req-sdcpp-batch returned 1 output(s); expected 2.",
                error.message
            )
            assertEquals(1, port.worker.generateCount)
        } finally {
            fixture.close()
        }
    }

    // ------------------------------------------------------------------ port failures

    @Test
    fun `worker failure keeps the provider error verbatim`() = runBlocking {
        val fixture = Fixture()
        try {
            val port = FakePort(fixture.model)
            port.worker.failure = ImageGenerationProviderException(
                code = "image_worker_disconnected",
                httpStatus = 503,
                message = "The isolated local image worker exited before producing a result."
            )

            val error = assertProviderFailure {
                generateLocalApiImageOperations(
                    requestId = "req-worker-failure",
                    body = """{"prompt":"a cat","model":"local-image-1","n":1}""",
                    port = port
                )
            }

            assertEquals("image_worker_disconnected", error.code)
            assertEquals(503, error.httpStatus)
            assertEquals(
                "The isolated local image worker exited before producing a result.",
                error.message
            )
            // The failure path still unwinds the lease and the cancellation ownership.
            assertEquals(1, port.registerCount)
            assertEquals(1, port.unregisterCount)
            assertNull(port.activeOwnership)
            assertNull(port.activeLeaseRequestId)
            assertEquals(1, port.worker.releaseAwaitCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `port that cannot resolve a requested lora maps to the 404 protocol code`() = runBlocking {
        val fixture = Fixture()
        try {
            val port = FakePort(fixture.model)
            port.loraResolutionFailure = LocalImageProductContractException(
                code = "image_lora_not_found",
                message = "所选 LoRA 已删除或不可用：adapter-9"
            )

            val error = assertProviderFailure {
                generateLocalApiImageOperations(
                    requestId = "req-lora",
                    body = """{"prompt":"a cat","model":"local-image-1","n":1}""",
                    port = port
                )
            }

            assertEquals("image_lora_not_found", error.code)
            assertEquals(404, error.httpStatus)
            assertEquals("所选 LoRA 已删除或不可用：adapter-9", error.message)
            // Rejected before admission: no lease, no worker dispatch.
            assertEquals(0, port.worker.generateCount)
            assertEquals(0, port.registerCount)
            assertEquals(0, port.leaseAttempts)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `malformed execution evidence from the port becomes a 502`() = runBlocking {
        val fixture = Fixture()
        try {
            val port = FakePort(fixture.model)
            port.worker.outputs = listOf(LocalImageOutput(bytes = pngBytes(8, 8), seed = 1L, index = 0))
            // nativeEffective is the field the protocol path admits the run on; drop it.
            port.worker.executionMetadataJson = JSONObject()
                .put("nativeExecution", true)
                .put("fallback", false)
                .toString()

            val error = assertProviderFailure {
                generateLocalApiImageOperations(
                    requestId = "req-bad-evidence",
                    body = """{"prompt":"a cat","model":"local-image-1","n":1}""",
                    port = port
                )
            }

            assertEquals("invalid_image_worker_response", error.code)
            assertEquals(502, error.httpStatus)
            assertEquals(
                "The local image result is missing nativeEffective execution evidence.",
                error.message
            )
            // The lease is still released on this failure path.
            assertNull(port.activeLeaseRequestId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `undecodable output bytes fail the same way the platform probe would`() = runBlocking {
        val fixture = Fixture()
        try {
            val port = FakePort(fixture.model)
            port.worker.outputs = listOf(
                LocalImageOutput(bytes = byteArrayOf(1, 2, 3, 4), seed = 1L, index = 0)
            )
            port.worker.executionMetadataJson = executionEvidenceJson()

            val error = assertProviderFailure {
                generateLocalApiImageOperations(
                    requestId = "req-bad-bytes",
                    body = """{"prompt":"a cat","model":"local-image-1","n":1}""",
                    port = port
                )
            }

            assertEquals("invalid_image_worker_response", error.code)
            assertEquals(502, error.httpStatus)
            assertEquals(
                "The local image worker returned an invalid image at index 0.",
                error.message
            )
        } finally {
            fixture.close()
        }
    }

    // ------------------------------------------------------------------ protocol admission

    @Test
    fun `explicit model branch never falls back to the selected model`() = runBlocking {
        val fixture = Fixture()
        try {
            // A perfectly usable model is selected; the request names one that does not exist.
            val port = FakePort(fixture.model)

            val error = assertProviderFailure {
                generateLocalApiImageOperations(
                    requestId = "req-404",
                    body = """{"prompt":"a cat","model":"not-installed","n":1}""",
                    port = port
                )
            }

            assertEquals("image_model_not_found", error.code)
            assertEquals(404, error.httpStatus)
            assertEquals("The requested local image model was not found.", error.message)
            assertEquals(0, port.selectedLocalImageModelQueries)
            assertEquals(0, port.worker.generateCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `no model in the request and none selected is 503`() = runBlocking {
        val fixture = Fixture()
        try {
            val port = FakePort(fixture.model)
            port.selectedModel = null

            val error = assertProviderFailure {
                generateLocalApiImageOperations(
                    requestId = "req-503",
                    body = """{"prompt":"a cat","n":1}""",
                    port = port
                )
            }

            assertEquals("image_runtime_unavailable", error.code)
            assertEquals(503, error.httpStatus)
            assertEquals("No configured local image model is selected.", error.message)
            assertEquals(1, port.selectedLocalImageModelQueries)
            assertEquals(0, port.worker.generateCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `the synchronous contract rejects preview before the adapter can see it`() = runBlocking {
        val fixture = Fixture()
        try {
            val port = FakePort(fixture.model)

            // The adapter carries its own defensive `unsupported_preview_transport` 422 branch, but
            // the wire contract refuses `preview` first, so no request can reach that branch. Pin the
            // real boundary: the contract's own code, and no admission work at all.
            try {
                generateLocalApiImageOperations(
                    requestId = "req-preview",
                    body = """{"prompt":"a cat","model":"local-image-1","n":1,"preview":{"interval":2,"mode":"projection"}}""",
                    port = port
                )
                fail("expected the images contract to reject preview")
            } catch (error: ImageGenerationContractException) {
                assertEquals("unsupported_preview", error.code)
                assertEquals(
                    "Synchronous local image API requests do not support preview.",
                    error.message
                )
            }
            assertEquals(0, port.leaseAttempts)
            assertEquals(0, port.worker.generateCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `lease contention is a 409 before any worker dispatch`() = runBlocking {
        val fixture = Fixture()
        try {
            val port = FakePort(fixture.model)
            port.leaseGranted = false

            val error = assertProviderFailure {
                generateLocalApiImageOperations(
                    requestId = "req-busy",
                    body = """{"prompt":"a cat","model":"local-image-1","n":1}""",
                    port = port
                )
            }

            assertEquals("image_generation_busy", error.code)
            assertEquals(409, error.httpStatus)
            assertEquals("Another UI or Local API image request is already running.", error.message)
            assertEquals(0, port.worker.generateCount)
            assertEquals(0, port.registerCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `blank request id is rejected with the plain argument contract`() = runBlocking {
        val fixture = Fixture()
        try {
            val port = FakePort(fixture.model)
            try {
                generateLocalApiImageOperations(
                    requestId = "   ",
                    body = """{"prompt":"a cat","model":"local-image-1","n":1}""",
                    port = port
                )
                fail("expected IllegalArgumentException for a blank requestId")
            } catch (error: IllegalArgumentException) {
                assertEquals("requestId must not be blank.", error.message)
            }
            assertEquals(0, port.worker.generateCount)
        } finally {
            fixture.close()
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun assertProviderFailure(
        block: suspend () -> String
    ): ImageGenerationProviderException {
        try {
            val response = block()
            fail("expected ImageGenerationProviderException, got a response: $response")
        } catch (error: ImageGenerationProviderException) {
            return error
        }
        error("unreachable")
    }

    private fun executionEvidenceJson(
        runtime: String = LocalImageRuntime.STABLE_DIFFUSION_CPP.name
    ): String = JSONObject()
        .put("nativeExecution", true)
        .put("fallback", false)
        .put("nativeGenerationSequence", 7L)
        .put(
            "nativeEffective",
            JSONObject().put("runtime", runtime)
        )
        .toString()

    /**
     * A real PNG, so the fake bounds probe reads the same bytes the platform probe would.
     *
     * Encoded by hand rather than through `javax.imageio`: Android unit tests compile against
     * `android.jar`, which carries no `java.desktop`, so any `BufferedImage`/`ImageIO` reference is a
     * hard compile error for the whole test source set. `java.util.zip` is available on Android, so
     * this stays a genuine PNG - correct signature, IHDR (with a real CRC) and IEND.
     */
    private fun pngBytes(width: Int, height: Int, rgb: Int = 0xFF3366CC.toInt()): ByteArray {
        fun crc(vararg chunks: ByteArray): Int {
            val crc = java.util.zip.CRC32()
            chunks.forEach { crc.update(it) }
            return crc.value.toInt()
        }
        fun be32(value: Int) = byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
        )
        fun chunk(type: String, data: ByteArray): ByteArray {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            return be32(data.size) + typeBytes + data + be32(crc(typeBytes, data))
        }
        val ihdr = be32(width) + be32(height) +
            byteArrayOf(8, 6, 0, 0, 0) // 8-bit, RGBA, deflate, no filter, no interlace
        // One filter byte (0 = None) plus RGBA pixels per scanline.
        val raw = ByteArray(height * (1 + width * 4))
        var cursor = 0
        repeat(height) {
            raw[cursor++] = 0
            repeat(width) {
                raw[cursor++] = ((rgb ushr 16) and 0xFF).toByte()
                raw[cursor++] = ((rgb ushr 8) and 0xFF).toByte()
                raw[cursor++] = (rgb and 0xFF).toByte()
                raw[cursor++] = 0xFF.toByte()
            }
        }
        // `java.util.zip.Deflater` is not AutoCloseable, so it needs an explicit end() rather than
        // `use { }` (which does not resolve).
        val deflated = java.io.ByteArrayOutputStream().also { sink ->
            val deflater = java.util.zip.Deflater()
            try {
                deflater.setInput(raw)
                deflater.finish()
                val buffer = ByteArray(4096)
                while (!deflater.finished()) {
                    val written = deflater.deflate(buffer)
                    sink.write(buffer, 0, written)
                }
            } finally {
                deflater.end()
            }
        }.toByteArray()
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            chunk("IHDR", ihdr) + chunk("IDAT", deflated) + chunk("IEND", ByteArray(0))
    }

    /**
     * A structurally ready record with a real primary file.
     *
     * `STABLE_DIFFUSION_CPP` + `SD_TURBO` needs one checkpoint only. `MNN_DIFFUSION` + `SD15` needs
     * the complete MNN companion layout; it is the runtime that reaches the sequential batch path
     * (one child per image) because only stable-diffusion.cpp takes the native-batch shortcut.
     */
    private class Fixture(
        runtime: LocalImageRuntime = LocalImageRuntime.STABLE_DIFFUSION_CPP
    ) {
        val root: File = Files.createTempDirectory("local-api-image-ops").toFile()
        val primary: File
        val model: LocalImageModelRecord

        init {
            val mnn = runtime == LocalImageRuntime.MNN_DIFFUSION
            primary = if (mnn) {
                MNN_COMPONENT_FILES.forEach { name ->
                    File(root, name).apply {
                        parentFile?.mkdirs()
                        writeText("x")
                    }
                }
                File(root, "unet.mnn")
            } else {
                File(root, "sd_turbo.safetensors").apply { writeText("x") }
            }
            model = LocalImageModelRecord(
                id = "local-image-1",
                displayName = if (mnn) "SD 1.5 MNN" else "SD Turbo",
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = primary.length(),
                sha256 = "test",
                runtime = runtime,
                family = if (mnn) LocalImageModelFamily.SD15 else LocalImageModelFamily.SD_TURBO,
                bundleRoot = root.absolutePath
            )
        }

        fun close() {
            root.deleteRecursively()
        }

        private companion object {
            val MNN_COMPONENT_FILES = listOf(
                "unet.mnn",
                "unet.mnn.weight",
                "text_encoder.mnn",
                "text_encoder.mnn.weight",
                "vae_decoder.mnn",
                "vae_decoder.mnn.weight",
                "tokenizer.mtok"
            )
        }
    }

    private class FakeLoraGateway(
        private val records: () -> List<LocalImageLoraRecord>
    ) : LocalApiImageLoraGateway {
        override fun load(): List<LocalImageLoraRecord> = records()
    }

    private class RecordingWorkerGateway : LocalApiImageWorkerGateway {
        var generateCount = 0
        var releaseAwaitCount = 0
        var lastModel: LocalImageModelRecord? = null
        var lastPrompt: String? = null
        var lastOptions: LocalImageGenerationOptions? = null
        var lastInputDraft: LocalImageInputDraft? = null
        var lastRequestId: String? = null
        var lastBatchLineage: ImageGenerationBatchLineage? = null
        var failure: Throwable? = null
        var outputs: List<LocalImageOutput> = emptyList()

        /** When set, the Nth call returns the Nth entry instead of [outputs]. */
        var outputsPerCall: List<List<LocalImageOutput>> = emptyList()
        val requestIds = mutableListOf<String>()
        val optionSeeds = mutableListOf<Int?>()
        var executionMetadataJson: String = "{}"

        override suspend fun generate(
            model: LocalImageModelRecord,
            prompt: String,
            options: LocalImageGenerationOptions,
            inputDraft: LocalImageInputDraft,
            requestId: String,
            batchLineage: ImageGenerationBatchLineage?
        ): LocalImageResult {
            generateCount += 1
            lastModel = model
            lastPrompt = prompt
            lastOptions = options
            lastInputDraft = inputDraft
            lastRequestId = requestId
            lastBatchLineage = batchLineage
            requestIds += requestId
            optionSeeds += options.seed
            failure?.let { throw it }
            val callOutputs = outputsPerCall.getOrNull(generateCount - 1) ?: outputs
            val first = callOutputs.first()
            return LocalImageResult(
                bytes = first.bytes,
                mimeType = first.mimeType,
                executionMetadataJson = executionMetadataJson,
                seed = first.seed,
                outputs = callOutputs
            )
        }

        override suspend fun awaitOwnedWorkerRelease() {
            releaseAwaitCount += 1
        }
    }

    private class FakePort(model: LocalImageModelRecord) : LocalApiImageGenerationPort {
        private val coordinator = LocalImageGenerationCoordinator()
        // The catalog the explicit-model branch resolves against, plus the selection the fallback
        // branch would use: both must be real, or every request 404s for the wrong reason.
        private val mutableUiState = MutableStateFlow(
            MainUiState(
                localImageModels = listOf(model),
                selectedLocalImageModelId = model.id
            )
        )

        override val uiState: StateFlow<MainUiState> get() = mutableUiState
        val state: MutableStateFlow<MainUiState> get() = mutableUiState

        override fun updateUiState(transform: (MainUiState) -> MainUiState) {
            mutableUiState.update(transform)
        }

        var selectedModel: LocalImageModelRecord? = model
        var selectedLocalImageModelQueries = 0

        override fun selectedLocalImageModel(): LocalImageModelRecord? {
            selectedLocalImageModelQueries += 1
            return selectedModel
        }

        override val localImageLoraLifecycleLock: Any = Any()
        override val localImageTextualInversionLifecycleLock: Any = Any()
        override var activeLocalApiImageModelId: String? = null

        val worker = RecordingWorkerGateway()
        override val localImageWorkerClient: LocalApiImageWorkerGateway get() = worker
        override val localImageLoraStore: LocalApiImageLoraGateway =
            FakeLoraGateway { emptyList() }

        var leaseGranted = true
        var leaseAttempts = 0
        var releaseCount = 0

        /** Non-null while a lease is still held -- the real release assertion. */
        val activeLeaseRequestId: String? get() = coordinator.activeRequestId()

        override fun tryAcquireObservedImageGenerationLease(
            requestId: String
        ): LocalImageGenerationCoordinator.Lease? {
            leaseAttempts += 1
            if (!leaseGranted) return null
            return coordinator.tryAcquire(requestId)
        }

        override fun releaseObservedImageGenerationLease(
            lease: LocalImageGenerationCoordinator.Lease
        ): Boolean {
            releaseCount += 1
            return coordinator.release(lease)
        }

        var registerCount = 0
        var unregisterCount = 0
        var activeOwnership: LocalApiImageGenerationOwnership? = null

        override fun registerLocalApiImageGenerationOwnership(
            ownership: LocalApiImageGenerationOwnership
        ) {
            registerCount += 1
            activeOwnership = ownership
        }

        override fun unregisterLocalApiImageGenerationOwnership(
            ownership: LocalApiImageGenerationOwnership
        ): Boolean {
            unregisterCount += 1
            if (activeOwnership !== ownership) return false
            activeOwnership = null
            return true
        }

        override fun cancelOwnedLocalApiImageWorker(
            ownership: LocalApiImageGenerationOwnership
        ): Boolean {
            activeOwnership = null
            return true
        }

        var loraResolutionFailure: Exception? = null

        override fun resolveCurrentLocalImageLoras(
            selections: List<Pair<String, Double>>
        ): List<LocalImagePreparedLora> {
            loraResolutionFailure?.let { throw it }
            return emptyList()
        }

        override fun resolveCurrentLocalImageLoras(
            selections: List<Pair<String, Double>>,
            records: List<LocalImageLoraRecord>
        ): List<LocalImagePreparedLora> = emptyList()

        override fun resolveCurrentLocalImageTextualInversionIds(ids: List<String>): List<String> = ids

        override suspend fun prepareLocalImagePromptExecution(
            model: LocalImageModelRecord,
            prompt: String,
            options: LocalImageGenerationOptions,
            captured: LocalImagePromptExecution?
        ): LocalImagePromptExecution = LocalImagePromptExecution(
            originalPrompt = prompt,
            effectivePrompt = prompt,
            originalNegativePrompt = null,
            effectiveNegativePrompt = "",
            negativePromptSource = LocalImageNegativePromptSource.EMPTY,
            method = LocalImagePromptTransformationMethod.DIRECT,
            imageProfileBindingFingerprint = "a".repeat(64),
            promptLanguageBindingFingerprint = "b".repeat(64)
        )

        override suspend fun prepareLocalImageRuntimeMemory():
            com.muyuchat.core.engine.EngineLifecycleLease? = null

        override fun executionWithQnnVerificationEvidence(
            model: LocalImageModelRecord,
            result: LocalImageResult,
            options: LocalImageGenerationOptions,
            requestId: String,
            attemptId: String,
            entryPoint: QnnImageEvidenceEntryPoint
        ): String = result.executionMetadataJson

        /** JVM equivalent of `BitmapFactory.Options(inJustDecodeBounds = true)` + `decodeByteArray`. */
        override fun decodeImageBounds(bytes: ByteArray): LocalApiImageBounds {
            if (bytes.size < 24) return LocalApiImageBounds(0, 0)
            val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
                bytes[12] == 0x49.toByte() && bytes[13] == 0x48.toByte() &&
                bytes[14] == 0x44.toByte() && bytes[15] == 0x52.toByte()
            if (!isPng) return LocalApiImageBounds(0, 0)
            return LocalApiImageBounds(
                outWidth = bigEndianInt(bytes, 16),
                outHeight = bigEndianInt(bytes, 20)
            )
        }

        /** JVM equivalent of `Base64.encodeToString(bytes, Base64.NO_WRAP)`. */
        override fun encodeBase64NoWrap(bytes: ByteArray): String =
            Base64.getEncoder().encodeToString(bytes)

        private fun bigEndianInt(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }
}

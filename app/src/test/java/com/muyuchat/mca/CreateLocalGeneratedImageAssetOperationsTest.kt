package com.muyuchat.mca

import com.muyuchat.api.local.imagePromptExecutionSha256
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural coverage for [CreateLocalGeneratedImageAssetOperations].
 *
 * The fake port is hand written: this repository's unit-test dependencies are JUnit, org.json and
 * sqlite-jdbc only, so there is no mocking framework here and none is added.
 *
 * What these tests can reach is the whole decision path of the moved member -- the worker call and
 * its request-id publication, the two execution-outcome records, the profile/prompt-evidence
 * binding validation, the batch-lineage and seed guards, and the entry into the publication critical
 * section with the resolved `imageAssetDirectory`.
 *
 * What they cannot reach is the last three statements of the publication loop: the atomic write
 * ends in `AndroidParentDirectorySyncer` (`android.system.Os`), and the `ImageAssetRecord` needs
 * `BitmapFactory` and `Uri.fromFile`. Those are `android.jar` stubs that throw outside a device, so
 * the JVM tests drive the member up to the write and then assert the refusal and the cleanup
 * behaviour that is observable there. The exact wording and ordering of that tail is pinned by
 * [the moved body keeps its guards, wording and publication tail without ViewModel residue] and by
 * the existing `LocalImagePromptCancellationContractTest`.
 */
class CreateLocalGeneratedImageAssetOperationsTest {

    // ---------------------------------------------------------------- worker failures

    @Test
    fun `worker failure is recorded with its own message and rethrown`() {
        withHarness { harness ->
            val failure = IllegalStateException("native worker exploded")
            harness.port.worker.failure = failure

            val thrown = harness.run().exceptionOrNull()

            assertSame(failure, thrown)
            assertEquals(listOf(harness.fixture.model.id to "native worker exploded"), harness.port.recordedOutcomes)
            assertEquals(1, harness.port.worker.generateCount)
            assertEquals(null, harness.port.activeLocalImageWorkerRequestId)
        }
    }

    @Test
    fun `worker failure without a message records the product fallback wording`() {
        withHarness { harness ->
            harness.port.worker.failure = RuntimeException()

            harness.run()

            assertEquals(
                listOf(harness.fixture.model.id to "本地 native 生图执行失败。"),
                harness.port.recordedOutcomes
            )
        }
    }

    @Test
    fun `worker cancellation is not recorded as an execution failure`() {
        withHarness { harness ->
            val cancellation = CancellationException("cancelled by the caller")
            harness.port.worker.failure = cancellation

            val thrown = harness.run().exceptionOrNull()

            assertSame(cancellation, thrown)
            assertEquals(emptyList<Pair<String, String?>>(), harness.port.recordedOutcomes)
            assertEquals(null, harness.port.activeLocalImageWorkerRequestId)
        }
    }

    @Test
    fun `isolated worker cancellation is not recorded as an execution failure`() {
        withHarness { harness ->
            val cancellation = LocalImageWorkerCancelledException()
            harness.port.worker.failure = cancellation

            val thrown = harness.run().exceptionOrNull()

            assertSame(cancellation, thrown)
            assertEquals(emptyList<Pair<String, String?>>(), harness.port.recordedOutcomes)
        }
    }

    @Test
    fun `the request id is published for the worker call and cleared afterwards`() {
        withHarness { harness ->
            harness.port.worker.failure = RuntimeException("boom")

            harness.run()

            assertEquals("req-1", harness.port.worker.activeRequestIdDuringGenerate)
            assertEquals(null, harness.port.activeLocalImageWorkerRequestId)
        }
    }

    @Test
    fun `a request id replaced during the worker call is not cleared`() {
        withHarness { harness ->
            harness.port.worker.failure = RuntimeException("boom")
            harness.port.worker.onGenerate = {
                harness.port.activeLocalImageWorkerRequestId = "other-request"
            }

            harness.run()

            assertEquals("other-request", harness.port.activeLocalImageWorkerRequestId)
        }
    }

    // ---------------------------------------------------------------- the normal path

    @Test
    fun `successful worker call records success and enters publication with the resolved profile`() {
        withHarness { harness ->
            val result = harness.run()

            assertEquals(1, harness.port.worker.generateCount)
            assertSame(harness.fixture.model, harness.port.worker.lastModel)
            assertEquals(harness.fixture.prompt, harness.port.worker.lastPrompt)
            assertSame(harness.fixture.options, harness.port.worker.lastOptions)
            assertSame(harness.fixture.inputDraft, harness.port.worker.lastInputDraft)
            assertEquals("req-1", harness.port.worker.lastRequestId)
            assertSame(harness.batchLineage, harness.port.worker.lastBatchLineage)
            assertSame(harness.progress, harness.port.worker.lastOnProgress)
            assertEquals(null, harness.port.activeLocalImageWorkerRequestId)

            // Success is recorded once, with a null failure message, before the publication loop.
            assertEquals(listOf(harness.fixture.model.id to null), harness.port.recordedOutcomes)

            // The QNN evidence call keeps the batch parent as its request id and this call's own
            // request id as the attempt id.
            assertEquals(1, harness.port.evidenceCalls.size)
            val evidence = harness.port.evidenceCalls.single()
            assertEquals("batch-parent-1", evidence.requestId)
            assertEquals("req-1", evidence.attemptId)
            assertEquals(QnnImageEvidenceEntryPoint.MAIN_ACTIVITY, evidence.entryPoint)
            assertEquals(harness.fixture.executionEvidenceJson(), evidence.executionMetadataJson)

            // Publication was entered under the write mutex, with the port's directory: the atomic
            // writer refuses a directory argument that is an existing regular file.
            assertEquals(1, harness.port.directoryReads)
            assertFalse(harness.port.imageAssetWriteMutex.isLocked)
            assertEquals(
                "Unable to create the image asset directory.",
                (result.exceptionOrNull() as? IllegalArgumentException)?.message
            )
        }
    }

    @Test
    fun `no partially published file is left behind when the platform write fails`() {
        withHarness(useRealDirectory = true) { harness ->
            val result = harness.run()

            assertTrue("the JVM cannot complete the device publication path", result.isFailure)
            assertEquals(
                "a failed publication must leave no asset behind",
                emptyList<String>(),
                harness.publicationDirectory.list().orEmpty().toList()
            )
        }
    }

    // ---------------------------------------------------------------- prompt evidence guards

    @Test
    fun `missing prepared prompt execution evidence is refused before any outcome is recorded`() {
        withHarness { harness ->
            harness.promptExecution = null

            val thrown = harness.run().exceptionOrNull()

            assertEquals(
                "Local image generation is missing prepared prompt execution evidence.",
                thrown?.message
            )
            assertEquals(emptyList<Pair<String, String?>>(), harness.port.recordedOutcomes)
            assertEquals(0, harness.port.directoryReads)
        }
    }

    @Test
    fun `worker inputs that disagree with the prepared evidence are refused`() {
        withHarness { harness ->
            // A DIRECT prepared execution always keeps effectivePrompt == originalPrompt, so the
            // disagreement has to come from the prompt the worker was actually asked to run.
            harness.prompt = "a different cat"

            val thrown = harness.run().exceptionOrNull()

            assertEquals(
                "Local image worker inputs do not match prepared prompt execution evidence.",
                thrown?.message
            )
            assertEquals(emptyList<Pair<String, String?>>(), harness.port.recordedOutcomes)
        }
    }

    @Test
    fun `execution evidence that disagrees with the resolved profile is refused`() {
        withHarness { harness ->
            harness.executionMetadataJson = harness.fixture.executionEvidenceJson(
                imageProfileBindingFingerprint = "0".repeat(64)
            )

            val thrown = harness.run().exceptionOrNull()

            assertTrue(
                "the real binding validator must reject foreign evidence, got $thrown",
                thrown is IllegalArgumentException
            )
            assertEquals(emptyList<Pair<String, String?>>(), harness.port.recordedOutcomes)
            assertEquals(0, harness.port.directoryReads)
        }
    }

    // ---------------------------------------------------------------- publication guards

    @Test
    fun `batch lineage count must match the worker outputs`() {
        withHarness { harness ->
            harness.outputs = listOf(
                LocalImageOutput(bytes = byteArrayOf(1, 2, 3), mimeType = "image/png", seed = 7L, index = 0),
                LocalImageOutput(bytes = byteArrayOf(4, 5, 6), mimeType = "image/png", seed = 8L, index = 1)
            )

            val thrown = harness.run().exceptionOrNull()

            assertEquals("Image batch lineage count does not match worker outputs.", thrown?.message)
            // The successful worker execution is recorded before the lineage guard runs.
            assertEquals(listOf(harness.fixture.model.id to null), harness.port.recordedOutcomes)
            assertEquals(0, harness.port.directoryReads)
        }
    }

    @Test
    fun `an output without an actual seed is refused before anything is written`() {
        withHarness { harness ->
            harness.outputs = listOf(
                LocalImageOutput(bytes = byteArrayOf(1, 2, 3), mimeType = "image/png", seed = null, index = 0)
            )

            val thrown = harness.run().exceptionOrNull()

            assertEquals("Generated image 0 is missing its actual seed.", thrown?.message)
            assertEquals(0, harness.port.directoryReads)
        }
    }

    @Test
    fun `an output seed that disagrees with its batch lineage is refused before anything is written`() {
        withHarness { harness ->
            harness.outputs = listOf(
                LocalImageOutput(bytes = byteArrayOf(1, 2, 3), mimeType = "image/png", seed = 9L, index = 0)
            )

            val thrown = harness.run().exceptionOrNull()

            assertEquals("Generated image 0 seed does not match its batch lineage.", thrown?.message)
            assertEquals(0, harness.port.directoryReads)
        }
    }

    // ---------------------------------------------------------------- moved-body contract

    /**
     * Pins the parts of the moved member a JVM test cannot execute: the platform calls that stay
     * inline, the exact `require` wording, and the fact that the body reads no ViewModel state.
     */
    @Test
    fun `the moved body keeps its guards, wording and publication tail without ViewModel residue`() {
        val body = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/CreateLocalGeneratedImageAssetOperations.kt"),
            "suspend fun createLocalGeneratedImageAsset("
        )

        assertFalse(body.contains("_uiState"))
        assertFalse(body.contains("viewModelScope"))
        assertFalse(body.contains("this@MainViewModel"))
        assertTrue(body.contains("port.activeLocalImageWorkerRequestId = requestId"))
        assertTrue(body.contains("port.localImageWorkerClient.generate("))
        assertTrue(body.contains("port.imageAssetWriteMutex.withLock"))
        assertTrue(body.contains("requestId = requestId"))
        assertFalse(body.contains("requestId = \"ui-img-"))
        assertTrue(body.contains("directory = port.imageAssetDirectory,"))
        assertTrue(body.contains("error !is CancellationException && error !is LocalImageWorkerCancelledException"))
        assertTrue(body.contains("error.message ?: \"本地 native 生图执行失败。\""))
        assertTrue(body.contains("\"Local image generation is missing prepared prompt execution evidence.\""))
        assertTrue(body.contains("\"Local image worker inputs do not match prepared prompt execution evidence.\""))
        assertTrue(body.contains("\"Image batch lineage count does not match worker outputs.\""))
        assertTrue(body.contains("\"Generated image \${output.index} is missing its actual seed.\""))
        assertTrue(body.contains("\"Generated image \${output.index} seed does not match its batch lineage.\""))
        assertTrue(body.contains("\"Generated image \${output.index} could not be decoded after publication.\""))
        assertTrue(body.contains("imageExtensionForMime(output.mimeType)"))
        assertTrue(body.contains("\"local-image-\$timestamp-\$requestToken\$suffix.\$extension\""))
        assertTrue(body.contains("BitmapFactory.decodeFile(outputFile.absolutePath, bounds)"))
        assertTrue(body.contains("Uri.fromFile(outputFile).toString()"))
        assertTrue(body.contains("\"generated:\${model.runtime.label}\""))
        assertTrue(body.contains("writtenFiles.forEach { file -> runCatching { file.delete() } }"))

        val validate = body.indexOf("validateLocalImagePromptExecutionBinding(")
        val outcome = body.indexOf("port.recordLocalImageExecutionOutcome(model, failureMessage = null)")
        val attachEvidence = body.indexOf(".withNativeExecution(port.executionWithQnnVerificationEvidence(")
        val write = body.indexOf("writeImageAssetBytesAtomically(")
        assertTrue("binding validation must precede the success record", validate in 0 until outcome)
        assertTrue("the success record must precede the evidence stamp", outcome < attachEvidence)
        assertTrue("the evidence stamp must precede the first write", attachEvidence < write)
        assertTrue(body.indexOf("writtenFiles.forEach") > write)
    }

    // ---------------------------------------------------------------- fixture

    private fun withHarness(
        useRealDirectory: Boolean = false,
        block: (Harness) -> Unit
    ) {
        val publishTarget = if (useRealDirectory) {
            Files.createTempDirectory("clgia-publish").toFile()
        } else {
            // An existing regular file, so the real atomic writer refuses it as a directory.
            Files.createTempFile("clgia-not-a-directory", ".tmp").toFile()
        }
        val harness = Harness(publishTarget)
        try {
            block(harness)
        } finally {
            harness.close()
        }
    }

    private class Harness(private val publishTarget: File) {
        val fixture = Fixture(LocalImageRuntime.STABLE_DIFFUSION_CPP)
        val publicationDirectory: File = publishTarget
        val port = FakePort(publishTarget)
        val batchLineage = ImageGenerationBatchLineage(
            parentRequestId = "batch-parent-1",
            index = 0,
            count = 1,
            seed = 7
        )
        val progress: (LocalImageProgress) -> Unit = {}

        var promptExecution: LocalImagePromptExecution? = fixture.promptExecution()
        var prompt: String = fixture.prompt
        var outputs: List<LocalImageOutput> = listOf(
            LocalImageOutput(bytes = byteArrayOf(1, 2, 3), mimeType = "image/png", seed = 7L, index = 0)
        )
        var outputLineages: List<ImageGenerationBatchLineage> = listOf(batchLineage)
        var executionMetadataJson: String = fixture.executionEvidenceJson()

        fun run(): Result<List<ImageAssetRecord>> {
            port.worker.outputs = outputs
            port.worker.executionMetadataJson = executionMetadataJson
            val promptExecution = promptExecution
            val metadata = fixture.metadata(promptExecution)
            return runCatching {
                runBlocking {
                    CreateLocalGeneratedImageAssetOperations.createLocalGeneratedImageAsset(
                        port = port,
                        requestId = "req-1",
                        prompt = prompt,
                        model = fixture.model,
                        options = fixture.options,
                        inputDraft = fixture.inputDraft,
                        chatSessionId = "chat-session-1",
                        generationMetadata = metadata,
                        batchLineage = batchLineage,
                        outputLineages = outputLineages,
                        onProgress = progress
                    )
                }
            }
        }

        fun close() {
            fixture.close()
            publishTarget.deleteRecursively()
        }
    }

    private class Fixture(private val runtime: LocalImageRuntime) {
        val root: File = Files.createTempDirectory("clgia-ops").toFile()
        private val primary: File = File(
            root,
            if (runtime == LocalImageRuntime.MNN_DIFFUSION) "unet.mnn" else "sd_turbo.safetensors"
        ).apply { writeText("x") }
        val prompt = "a cat"

        /**
         * The generic stable-diffusion.cpp SD-Turbo profile has no executable negative-prompt
         * branch, so an explicit non-empty negative prompt is rejected during profile resolution.
         * An explicit empty one is what that profile can execute, and the moved body compares
         * `options.negativePrompt` with the prepared execution's non-null effective negative
         * prompt, so this is the pair the fixture has to use.
         */
        val negativePrompt = ""
        val options = LocalImageGenerationOptions(negativePrompt = negativePrompt)
        val inputDraft = LocalImageInputDraft()
        val model = LocalImageModelRecord(
            id = "local-image-1",
            displayName = "SD Turbo",
            path = primary.absolutePath,
            fileName = primary.name,
            sizeBytes = primary.length(),
            sha256 = "test",
            runtime = runtime,
            family = if (runtime == LocalImageRuntime.MNN_DIFFUSION) {
                LocalImageModelFamily.SD15
            } else {
                LocalImageModelFamily.SD_TURBO
            },
            bundleRoot = root.absolutePath
        )

        /** The same resolution the moved body performs, so the fake worker can mirror its evidence. */
        val profile: ImageExecutionProfile = resolveLocalImageExecutionProfile(
            model = model,
            options = options.normalizedForPromptExecutionProfile(runtime),
            bundleRoot = root
        ).profile

        fun promptExecution(): LocalImagePromptExecution =
            LocalImagePromptExecution(
                originalPrompt = prompt,
                effectivePrompt = prompt,
                originalNegativePrompt = negativePrompt,
                effectiveNegativePrompt = negativePrompt,
                negativePromptSource = LocalImageNegativePromptSource.USER,
                method = LocalImagePromptTransformationMethod.DIRECT,
                imageProfileBindingFingerprint = profile.bindingFingerprint,
                promptLanguageBindingFingerprint = profile.promptLanguageBindingFingerprint
            )

        /** Native execution evidence that the real binding validator accepts for this profile. */
        fun executionEvidenceJson(
            imageProfileBindingFingerprint: String = profile.bindingFingerprint,
            promptLanguageBindingFingerprint: String = profile.promptLanguageBindingFingerprint
        ): String {
            val promptSha256 = imagePromptExecutionSha256(prompt, negativePrompt)
            val languageProof = profile.verifiedNativeSimplifiedChineseLanguageProofSha256()
            val nativeEffective = JSONObject()
                .put("nativePromptExecutionSha256", promptSha256)
                .put("nativePromptBindingStage", "conditioning_consumed")
                .apply { languageProof?.let { put("languageProofSha256", it) } }
            return JSONObject()
                .put("imageProfileBindingFingerprint", imageProfileBindingFingerprint)
                .put("promptLanguageBindingFingerprint", promptLanguageBindingFingerprint)
                .put("textEncoderLanguageCapability", profile.textEncoderLanguageCapability().name)
                .put("promptExecutionSha256", promptSha256)
                .put("nativePromptExecutionSha256", promptSha256)
                .put("nativePromptBindingStage", "conditioning_consumed")
                .put("nativeEffective", nativeEffective)
                .apply { languageProof?.let { put("languageProofSha256", it) } }
                .toString()
        }

        fun metadata(promptExecution: LocalImagePromptExecution?): ImageGenerationHistoryMetadata =
            ImageGenerationHistoryMetadata(
                backend = ImageBackend.LOCAL,
                modelId = model.id,
                modelName = model.displayName,
                requestPrompt = prompt,
                options = options,
                inputDraft = inputDraft,
                promptExecution = promptExecution
            )

        fun close() {
            root.deleteRecursively()
        }
    }

    /**
     * The hand-written port. Every member records what the moved body did with it, so a test asserts
     * the interaction rather than the implementation.
     */
    private class FakePort(directory: File) : CreateLocalGeneratedImageAssetPort {
        override var activeLocalImageWorkerRequestId: String? = null

        val worker = RecordingWorker { activeLocalImageWorkerRequestId }
        override val localImageWorkerClient: LocalGeneratedImageWorkerGateway get() = worker

        private val outcomes = mutableListOf<Pair<String, String?>>()
        val recordedOutcomes: List<Pair<String, String?>> get() = outcomes

        override fun recordLocalImageExecutionOutcome(
            model: LocalImageModelRecord,
            failureMessage: String?
        ) {
            outcomes += model.id to failureMessage
        }

        val evidenceCalls = mutableListOf<EvidenceCall>()

        override fun executionWithQnnVerificationEvidence(
            model: LocalImageModelRecord,
            result: LocalImageResult,
            options: LocalImageGenerationOptions,
            requestId: String,
            attemptId: String,
            entryPoint: QnnImageEvidenceEntryPoint
        ): String {
            evidenceCalls += EvidenceCall(requestId, attemptId, entryPoint, result.executionMetadataJson)
            return result.executionMetadataJson
        }

        override val imageAssetWriteMutex: Mutex = Mutex()

        private var directoryPath: File = directory
        var directoryReads = 0
            private set
        override val imageAssetDirectory: File
            get() {
                directoryReads += 1
                return directoryPath
            }
    }

    private class RecordingWorker(
        private val activeRequestId: () -> String?
    ) : LocalGeneratedImageWorkerGateway {
        var failure: Throwable? = null
        var outputs: List<LocalImageOutput> = emptyList()
        var executionMetadataJson: String = "{}"
        var onGenerate: (() -> Unit)? = null

        var generateCount = 0
        var lastModel: LocalImageModelRecord? = null
        var lastPrompt: String? = null
        var lastOptions: LocalImageGenerationOptions? = null
        var lastInputDraft: LocalImageInputDraft? = null
        var lastRequestId: String? = null
        var lastBatchLineage: ImageGenerationBatchLineage? = null
        var lastOnProgress: ((LocalImageProgress) -> Unit)? = null
        var activeRequestIdDuringGenerate: String? = null

        override suspend fun generate(
            model: LocalImageModelRecord,
            prompt: String,
            options: LocalImageGenerationOptions,
            inputDraft: LocalImageInputDraft,
            requestId: String,
            batchLineage: ImageGenerationBatchLineage?,
            onProgress: (LocalImageProgress) -> Unit
        ): LocalImageResult {
            generateCount += 1
            lastModel = model
            lastPrompt = prompt
            lastOptions = options
            lastInputDraft = inputDraft
            lastRequestId = requestId
            lastBatchLineage = batchLineage
            lastOnProgress = onProgress
            activeRequestIdDuringGenerate = activeRequestId()
            onGenerate?.invoke()
            failure?.let { throw it }
            val first = outputs.first()
            return LocalImageResult(
                bytes = first.bytes,
                mimeType = first.mimeType,
                executionMetadataJson = executionMetadataJson,
                seed = first.seed,
                outputs = outputs
            )
        }
    }

    private data class EvidenceCall(
        val requestId: String,
        val attemptId: String,
        val entryPoint: QnnImageEvidenceEntryPoint,
        val executionMetadataJson: String
    )

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            File(root, relativePath).takeIf(File::isFile)?.let {
                return it.readText(Charsets.UTF_8)
            }
            directory = root.parentFile
        }
        error("Unable to locate $relativePath")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val openingParenthesis = source.indexOf('(', start)
        require(openingParenthesis >= 0) { "Missing function parameter list: $signature" }
        var parenthesisDepth = 0
        var closingParenthesis = -1
        for (index in openingParenthesis until source.length) {
            when (source[index]) {
                '(' -> parenthesisDepth += 1
                ')' -> {
                    parenthesisDepth -= 1
                    if (parenthesisDepth == 0) {
                        closingParenthesis = index
                        break
                    }
                }
            }
        }
        require(closingParenthesis >= 0) { "Unterminated function parameter list: $signature" }
        val openingBrace = source.indexOf('{', closingParenthesis)
        require(openingBrace >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function body: $signature")
    }
}

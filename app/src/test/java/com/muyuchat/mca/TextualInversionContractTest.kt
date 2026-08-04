package com.muyuchat.mca

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextualInversionContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun contentImportBoundaryRejectsNonContentAndAuthoritylessUris() {
        requireTextualInversionContentImportUri("content", "com.example.provider")
        requireTextualInversionContentImportUri("CONTENT", "com.example.provider")

        listOf(
            "file" to "com.example.provider",
            "https" to "example.com",
            null to "com.example.provider",
            "content" to null,
            "content" to ""
        ).forEach { (scheme, authority) ->
            assertThrows(IllegalArgumentException::class.java) {
                requireTextualInversionContentImportUri(scheme, authority)
            }
        }
    }

    @Test
    fun executionAssetLabelsUseNativeUtf8ByteOrdering() {
        val privateUse = executionAsset("\uE000.safetensors", "a".repeat(64))
        val supplementary = executionAsset("\uD83D\uDE00.safetensors", "b".repeat(64))

        val binding = TextualInversionExecutionAssetBinding(
            runtime = TextualInversionRuntime.STABLE_DIFFUSION_CPP,
            bundleRoot = temporaryFolder.root.absolutePath,
            profilePromptFingerprint = "c".repeat(64),
            assets = listOf(privateUse, supplementary)
        )

        assertEquals(listOf(privateUse, supplementary), binding.assets)
        assertThrows(IllegalArgumentException::class.java) {
            binding.copy(assets = listOf(supplementary, privateUse))
        }
    }

    @Test
    fun selectionFingerprintIsCanonicalAndOrderIndependent() {
        val first = binding(artifact("cat"))
        val second = binding(artifact("style"))

        assertEquals(
            TextualInversionSelection(listOf(first, second)).bindingFingerprint,
            TextualInversionSelection(listOf(second, first)).bindingFingerprint
        )
    }

    @Test
    fun activeSelectionRejectsMoreThan256MiB() {
        val bindings = listOf("first", "second", "third").map { trigger ->
            artifact(trigger).copy(
                sizeBytes = 90L * 1024L * 1024L
            ).bind(
                modelFingerprint = MODEL_FINGERPRINT,
                tokenizerFingerprint = TOKENIZER_FINGERPRINT,
                profileId = "active-quota-test",
                profileRevision = 1
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            TextualInversionSelection(bindings)
        }
    }

    @Test
    fun bindingFingerprintMatchesNativeCanonicalVector() {
        val id = "00000000-0000-0000-0000-000000000001"
        val artifact = TextualInversionArtifact(
            id = id,
            name = "cat",
            trigger = "cat",
            fileName = "$id.bin",
            path = File(temporaryFolder.root, "$id.bin").absolutePath,
            sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            sizeBytes = 16L,
            format = TextualInversionFormat.BINARY
        )
        val binding = artifact.bind(
            modelFingerprint =
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            tokenizerFingerprint =
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            profileId = "stable-test",
            profileRevision = 1
        )

        assertEquals(
            "877205ad6c36ea77782b8c819e0859ec5b70a57541a3c91b357bbdb618867b97",
            binding.bindingFingerprint
        )
        assertEquals(
            "a9bb38fd3ed3576e7ab41cef7d3aa687bb066bd55e699b7dcff4298c8ca75e2d",
            TextualInversionSelection(listOf(binding)).bindingFingerprint
        )
    }

    @Test
    fun runtimeBindingRoundTripCarriesTheNativeEmbeddingMode() {
        listOf(
            TextualInversionRuntime.QNN_HTP,
            TextualInversionRuntime.MNN_DIFFUSION
        ).forEach { runtime ->
            val artifact = artifact("<runtime-style>")
            val binding = artifact.bind(
                modelFingerprint = MODEL_FINGERPRINT,
                tokenizerFingerprint = TOKENIZER_FINGERPRINT,
                profileId = "host-clip-test",
                profileRevision = 4,
                runtime = runtime
            )
            val restored = TextualInversionBinding.fromJson(binding.toJson())
            val native = TextualInversionSelection(listOf(binding))
                .toNativeJson(temporaryFolder.root.absolutePath)

            assertEquals(runtime, restored.runtime)
            assertEquals(binding.bindingFingerprint, restored.bindingFingerprint)
            assertEquals("MNN_CLIP_INPUT_EMBEDDING", native.getString("textualInversionNativeMode"))
            assertEquals(1, native.getInt("textualInversionCount"))
        }
    }

    @Test
    fun qnnAndMnnRejectNonSafetensorsBeforeNativeDispatch() {
        listOf(
            TextualInversionRuntime.QNN_HTP,
            TextualInversionRuntime.MNN_DIFFUSION
        ).forEach { runtime ->
            val binary = artifact("<runtime-style>")
            val safetensors = binary.copy(
                fileName = "${binary.id}.safetensors",
                path = File(temporaryFolder.root, "${binary.id}.safetensors").absolutePath,
                format = TextualInversionFormat.SAFETENSORS
            )
            fun selection(artifact: TextualInversionArtifact) = TextualInversionSelection(
                listOf(
                    artifact.bind(
                        modelFingerprint = MODEL_FINGERPRINT,
                        tokenizerFingerprint = TOKENIZER_FINGERPRINT,
                        profileId = "host-clip-test",
                        profileRevision = 4,
                        runtime = runtime
                    )
                )
            )

            assertThrows(IllegalArgumentException::class.java) {
                TextualInversionContract.validateNativeCapability(
                    runtime = runtime.wireName,
                    graphSupportsTextualInversion = true,
                    selection = selection(binary)
                )
            }
            TextualInversionContract.validateNativeCapability(
                runtime = runtime.wireName,
                graphSupportsTextualInversion = true,
                selection = selection(safetensors)
            )
        }

        TextualInversionContract.validateNativeCapability(
            runtime = TextualInversionRuntime.STABLE_DIFFUSION_CPP.wireName,
            graphSupportsTextualInversion = true,
            selection = TextualInversionSelection(
                listOf(
                    artifact("<stable-style>").bind(
                        modelFingerprint = MODEL_FINGERPRINT,
                        tokenizerFingerprint = TOKENIZER_FINGERPRINT,
                        profileId = "stable-test",
                        profileRevision = 1
                    )
                )
            )
        )
    }

    @Test
    fun promptTriggerMatchingUsesTheSameAsciiBoundariesAsNative() {
        assertTrue(TextualInversionContract.promptContainsTrigger("CAT, portrait", "cat"))
        assertFalse(TextualInversionContract.promptContainsTrigger("catering", "cat"))
        assertFalse(TextualInversionContract.promptContainsTrigger("bobcat", "cat"))
        assertTrue(
            TextualInversionContract.promptContainsTrigger(
                "portrait, <PAINT-STYLE>",
                "<paint-style>"
            )
        )
        assertEquals(
            emptyList<String>(),
            TextualInversionContract.missingPromptTriggers(
                prompts = listOf("portrait", "avoid <paint-style>"),
                artifacts = listOf(artifact("<paint-style>"))
            )
        )
    }

    @Test
    fun corruptManifestIsNotTreatedAsEmptyLibrary() = runBlocking {
        val noBackupRoot = temporaryFolder.newFolder("no-backup-corrupt")
        val root = File(noBackupRoot, "textual_inversions").apply { mkdirs() }
        File(root, "records.json").writeText("{not-json", Charsets.UTF_8)
        val store = TextualInversionStore(noBackupRoot, Dispatchers.IO)

        assertThrows(TextualInversionStoreException::class.java) {
            runBlocking { store.load() }
        }
        Unit
    }

    @Test
    fun corruptManifestProducesVisibleStartupErrorWithoutDeletingFiles() = runBlocking {
        val noBackupRoot = temporaryFolder.newFolder("no-backup-startup-corrupt")
        val root = File(noBackupRoot, "textual_inversions").apply { mkdirs() }
        val manifest = File(root, "records.json").apply {
            writeText("{not-json", Charsets.UTF_8)
        }
        val store = TextualInversionStore(noBackupRoot, Dispatchers.IO)

        val state = loadInitialTextualInversionLibraryState(store)

        assertTrue(state.records.isEmpty())
        assertTrue(state.message.contains("库读取失败"))
        assertEquals("{not-json", manifest.readText(Charsets.UTF_8))
    }

    @Test
    fun successfulNativeCommitMigratesLegacyManifestAndPersistsBinding() = runBlocking {
        val noBackupRoot = temporaryFolder.newFolder("no-backup-legacy")
        val artifact = installLegacyArtifact(noBackupRoot, "legacy")
        val store = TextualInversionStore(noBackupRoot, Dispatchers.IO)
        val binding = binding(artifact)
        val selection = TextualInversionSelection(listOf(binding))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.commitSuccessfulBindings(
                    selection,
                    selection.bindingFingerprint,
                    "context_loaded_trigger_observed"
                )
            }
        }

        store.commitSuccessfulBindings(
            selection,
            selection.bindingFingerprint,
            "conditioning_consumed"
        )

        val envelope = JSONObject(
            File(noBackupRoot, "textual_inversions/records.json").readText(Charsets.UTF_8)
        )
        assertEquals(2, envelope.getInt("version"))
        val committed = store.load().single()
        assertEquals(binding.modelFingerprint, committed.modelFingerprint)
        assertEquals(binding.tokenizerFingerprint, committed.tokenizerFingerprint)
    }

    @Test
    fun successfulBindingEvidenceDoesNotLockArtifactToTheFirstModel() = runBlocking {
        val noBackupRoot = temporaryFolder.newFolder("no-backup-rebind")
        val artifact = installLegacyArtifact(noBackupRoot, "rebind")
        val store = TextualInversionStore(noBackupRoot, Dispatchers.IO)
        val first = binding(artifact)
        store.commitSuccessfulBindings(
            TextualInversionSelection(listOf(first)),
            TextualInversionSelection(listOf(first)).bindingFingerprint,
            "conditioning_consumed"
        )

        val rebound = store.bind(
            id = artifact.id,
            modelFingerprint = SECOND_MODEL_FINGERPRINT,
            tokenizerFingerprint = SECOND_TOKENIZER_FINGERPRINT,
            profileId = "stable-test-2",
            profileRevision = 2
        )

        assertEquals(SECOND_MODEL_FINGERPRINT, rebound.modelFingerprint)
        assertEquals(SECOND_TOKENIZER_FINGERPRINT, rebound.tokenizerFingerprint)
        val secondSelection = TextualInversionSelection(listOf(rebound))
        store.commitSuccessfulBindings(
            secondSelection,
            secondSelection.bindingFingerprint,
            "conditioning_consumed"
        )
        val lastSuccessful = store.load().single()
        assertEquals(SECOND_MODEL_FINGERPRINT, lastSuccessful.modelFingerprint)
        assertEquals(SECOND_TOKENIZER_FINGERPRINT, lastSuccessful.tokenizerFingerprint)
    }

    @Test
    fun sharedGenerationLeaseBlocksMutationUntilClosed() = runBlocking {
        val noBackupRoot = temporaryFolder.newFolder("no-backup-lease")
        val artifact = installLegacyArtifact(noBackupRoot, "lease")
        val store = TextualInversionStore(noBackupRoot, Dispatchers.IO)
        val lease = store.acquireSelectionLease(
            ids = listOf(artifact.id),
            modelFingerprint = MODEL_FINGERPRINT,
            tokenizerFingerprint = TOKENIZER_FINGERPRINT,
            profileId = "stable-test",
            profileRevision = 1
        )
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Thread {
            started.countDown()
            runBlocking { store.clear(artifact.id) }
            finished.countDown()
        }.apply { start() }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertFalse(finished.await(150, TimeUnit.MILLISECONDS))
        lease.close()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        worker.join(5_000)
    }

    @Test
    fun cancellationWhileAcquiredLeaseIsReturningReleasesTheStoreLock() = runBlocking {
        val noBackupRoot = temporaryFolder.newFolder("no-backup-lease-return-cancel")
        val artifact = installLegacyArtifact(noBackupRoot, "lease-return-cancel")
        val operationJob = AtomicReference<Job?>(null)
        val returnedLease = AtomicReference<TextualInversionSelectionLease?>(null)
        val store = TextualInversionStore(
            noBackupRoot = noBackupRoot,
            ioDispatcher = Dispatchers.IO,
            afterSelectionLeaseAcquired = { operationJob.get()?.cancel() }
        )
        val operation = launch(start = CoroutineStart.LAZY) {
            returnedLease.set(
                store.acquireSelectionLease(
                    ids = listOf(artifact.id),
                    modelFingerprint = MODEL_FINGERPRINT,
                    tokenizerFingerprint = TOKENIZER_FINGERPRINT,
                    profileId = "stable-test",
                    profileRevision = 1
                )
            )
        }
        operationJob.set(operation)

        operation.start()
        operation.join()

        assertTrue(operation.isCancelled)
        assertTrue(returnedLease.get() == null)
        assertTrue(withTimeout(5_000) { store.clear(artifact.id) })
    }

    @Test
    fun strictSafetensorsImportAcceptsACompleteContiguousDocument() = runBlocking {
        val noBackupRoot = temporaryFolder.newFolder("no-backup-safetensors-valid")
        val store = TextualInversionStore(noBackupRoot, Dispatchers.IO)
        val header = JSONObject()
            .put("__metadata__", JSONObject().put("source", "fixture"))
            .put("clip_l", tensorMetadata(shape = intArrayOf(1), start = 0, end = 4))
        val bytes = safetensorsBytes(header, ByteArray(4))

        val imported = store.importFromStream(
            openInput = { ByteArrayInputStream(bytes) },
            trigger = "<strict-valid>",
            displayName = "strict-valid.safetensors"
        )

        assertEquals(TextualInversionFormat.SAFETENSORS, imported.artifact.format)
        assertEquals(bytes.size.toLong(), imported.artifact.sizeBytes)
        assertEquals(imported.artifact.id, store.load().single().id)
    }

    @Test
    fun aggregateQuotaAllowsTheExactLimitAndRejectsOneByteMore() = runBlocking {
        assertEquals(512L * 1024L * 1024L, TextualInversionStore.MAX_TOTAL_BYTES)
        val firstBytes = ByteArray(16) { index -> index.toByte() }
        val secondBytes = ByteArray(32) { index -> (index + 16).toByte() }
        val unlimitedSpace: (File) -> Long = { Long.MAX_VALUE }

        val exactRoot = temporaryFolder.newFolder("no-backup-aggregate-exact")
        val exactStore = TextualInversionStore(
            noBackupRoot = exactRoot,
            ioDispatcher = Dispatchers.IO,
            maxTotalBytes = 48L,
            usableSpaceProvider = unlimitedSpace
        )
        exactStore.importFromStream(
            openInput = { ByteArrayInputStream(firstBytes) },
            trigger = "<quota-first>",
            displayName = "quota-first.bin"
        )
        exactStore.importFromStream(
            openInput = { ByteArrayInputStream(secondBytes) },
            trigger = "<quota-second>",
            displayName = "quota-second.bin"
        )

        assertEquals(48L, exactStore.load().sumOf(TextualInversionArtifact::sizeBytes))

        val exceededRoot = temporaryFolder.newFolder("no-backup-aggregate-exceeded")
        val exceededStore = TextualInversionStore(
            noBackupRoot = exceededRoot,
            ioDispatcher = Dispatchers.IO,
            maxTotalBytes = 47L,
            usableSpaceProvider = unlimitedSpace
        )
        val retained = exceededStore.importFromStream(
            openInput = { ByteArrayInputStream(firstBytes) },
            trigger = "<quota-retained>",
            displayName = "quota-retained.bin"
        ).artifact

        val failure = runCatching {
            exceededStore.importFromStream(
                openInput = { ByteArrayInputStream(secondBytes) },
                trigger = "<quota-overflow>",
                displayName = "quota-overflow.bin"
            )
        }.exceptionOrNull()

        assertTrue(failure is TextualInversionStoreException)
        assertTrue(failure?.message.orEmpty().contains("aggregate quota"))
        assertEquals(listOf(retained.id), exceededStore.load().map(TextualInversionArtifact::id))
        assertTrue(
            File(exceededRoot, "textual_inversions").listFiles().orEmpty().none { file ->
                file.name.endsWith(".part")
            }
        )
    }

    @Test
    fun freeSpaceReserveAllowsTheExactBoundaryAndRejectsOneByteLess() = runBlocking {
        val reserveBytes = TextualInversionStore.MIN_FREE_SPACE_RESERVE_BYTES
        assertEquals(64L * 1024L * 1024L, reserveBytes)
        val artifactBytes = ByteArray(16) { index -> index.toByte() }

        val exactRoot = temporaryFolder.newFolder("no-backup-reserve-exact")
        val exactStore = TextualInversionStore(
            noBackupRoot = exactRoot,
            ioDispatcher = Dispatchers.IO,
            usableSpaceProvider = { reserveBytes + artifactBytes.size }
        )
        val imported = exactStore.importFromStream(
            openInput = { ByteArrayInputStream(artifactBytes) },
            trigger = "<reserve-exact>",
            displayName = "reserve-exact.bin"
        )

        assertEquals(imported.artifact.id, exactStore.load().single().id)

        val insufficientRoot = temporaryFolder.newFolder("no-backup-reserve-insufficient")
        val insufficientStore = TextualInversionStore(
            noBackupRoot = insufficientRoot,
            ioDispatcher = Dispatchers.IO,
            usableSpaceProvider = { reserveBytes + artifactBytes.size - 1L }
        )
        val failure = runCatching {
            insufficientStore.importFromStream(
                openInput = { ByteArrayInputStream(artifactBytes) },
                trigger = "<reserve-insufficient>",
                displayName = "reserve-insufficient.bin"
            )
        }.exceptionOrNull()

        assertTrue(failure is TextualInversionStoreException)
        assertTrue(failure?.message.orEmpty().contains("leave 64 MiB"))
        val insufficientDirectory = File(insufficientRoot, "textual_inversions")
        assertFalse(File(insufficientDirectory, "records.json").exists())
        assertTrue(
            insufficientDirectory.listFiles().orEmpty().none { file ->
                file.name.endsWith(".bin") || file.name.endsWith(".part")
            }
        )
    }

    @Test
    fun strictSafetensorsImportRejectsMalformedHeadersAndNeverPublishes() = runBlocking {
        val validTensor = tensorMetadata(shape = intArrayOf(1), start = 0, end = 4)
        val cases = listOf(
            "malformed-utf8" to safetensorsBytes(
                byteArrayOf('{'.code.toByte(), 0xff.toByte(), '}'.code.toByte()),
                ByteArray(8)
            ),
            "metadata-value" to safetensorsBytes(
                JSONObject()
                    .put("__metadata__", JSONObject().put("source", 7))
                    .put("clip_l", validTensor),
                ByteArray(4)
            ),
            "unknown-tensor-field" to safetensorsBytes(
                JSONObject().put(
                    "clip_l",
                    JSONObject(validTensor.toString()).put("unexpected", true)
                ),
                ByteArray(4)
            ),
            "overlap" to safetensorsBytes(
                JSONObject()
                    .put("clip_l", validTensor)
                    .put("auxiliary", tensorMetadata(intArrayOf(1), 0, 4)),
                ByteArray(4)
            ),
            "gap" to safetensorsBytes(
                JSONObject().put("clip_l", tensorMetadata(intArrayOf(1), 4, 8)),
                ByteArray(8)
            ),
            "trailing" to safetensorsBytes(
                JSONObject().put("clip_l", validTensor),
                ByteArray(8)
            )
        )

        cases.forEachIndexed { index, (name, bytes) ->
            val noBackupRoot = temporaryFolder.newFolder("no-backup-safetensors-invalid-$index")
            val store = TextualInversionStore(noBackupRoot, Dispatchers.IO)

            val failure = runCatching {
                store.importFromStream(
                    openInput = { ByteArrayInputStream(bytes) },
                    trigger = "<invalid-$index>",
                    displayName = "$name.safetensors"
                )
            }.exceptionOrNull()

            assertTrue("Expected strict rejection for $name.", failure is IllegalArgumentException)
            val root = File(noBackupRoot, "textual_inversions")
            assertFalse(File(root, "records.json").exists())
            assertTrue(root.listFiles().orEmpty().none { file ->
                file.name.endsWith(".safetensors") || file.name.endsWith(".part")
            })
        }
    }

    @Test
    fun cancellationAfterImportCommitReturnsCommittedResult() = runBlocking {
        val noBackupRoot = temporaryFolder.newFolder("no-backup-import-commit")
        val operationJob = AtomicReference<Job?>(null)
        val result = AtomicReference<TextualInversionImportResult?>(null)
        val store = TextualInversionStore(
            noBackupRoot = noBackupRoot,
            ioDispatcher = Dispatchers.IO,
            afterManifestCommit = { operationJob.get()?.cancel() }
        )
        val bytes = ByteArray(16) { index -> index.toByte() }
        val operation = launch(start = CoroutineStart.LAZY) {
            result.set(
                store.importFromStream(
                    openInput = { ByteArrayInputStream(bytes) },
                    trigger = "<commit-import>",
                    displayName = "commit-import.bin"
                )
            )
        }
        operationJob.set(operation)

        operation.start()
        operation.join()

        val committed = requireNotNull(result.get())
        assertEquals(committed.artifact.id, store.load().single().id)
    }

    @Test
    fun cancellationBeforeImportCommitPublishesNothing() = runBlocking {
        val noBackupRoot = temporaryFolder.newFolder("no-backup-import-cancelled")
        val operationJob = AtomicReference<Job?>(null)
        val returned = AtomicReference<TextualInversionImportResult?>(null)
        val bytes = ByteArray(16) { index -> index.toByte() }
        val store = TextualInversionStore(noBackupRoot, Dispatchers.IO)
        val operation = launch(start = CoroutineStart.LAZY) {
            returned.set(
                store.importFromStream(
                    openInput = {
                        object : ByteArrayInputStream(bytes) {
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                return super.read(buffer, offset, length).also {
                                    operationJob.get()?.cancel()
                                }
                            }
                        }
                    },
                    trigger = "<cancel-before-commit>",
                    displayName = "cancel-before-commit.bin"
                )
            )
        }
        operationJob.set(operation)

        operation.start()
        operation.join()

        assertTrue(returned.get() == null)
        val root = File(noBackupRoot, "textual_inversions")
        assertFalse(File(root, "records.json").exists())
        assertTrue(
            root.listFiles().orEmpty().none { file ->
                file.name.endsWith(".bin") || file.name.endsWith(".part")
            }
        )
    }

    @Test
    fun cancellationAfterBindingCommitReturnsCommittedSelection() = runBlocking {
        val noBackupRoot = temporaryFolder.newFolder("no-backup-binding-commit")
        val artifact = installLegacyArtifact(noBackupRoot, "binding-commit")
        val selection = TextualInversionSelection(listOf(binding(artifact)))
        val operationJob = AtomicReference<Job?>(null)
        val result = AtomicReference<TextualInversionSelection?>(null)
        val store = TextualInversionStore(
            noBackupRoot = noBackupRoot,
            ioDispatcher = Dispatchers.IO,
            afterManifestCommit = { operationJob.get()?.cancel() }
        )
        val operation = launch(start = CoroutineStart.LAZY) {
            result.set(
                store.commitSuccessfulBindings(
                    selection = selection,
                    nativeBindingFingerprint = selection.bindingFingerprint,
                    nativeBindingStage = "conditioning_consumed"
                )
            )
        }
        operationJob.set(operation)

        operation.start()
        operation.join()

        assertEquals(selection, result.get())
        val committed = store.load().single()
        assertEquals(MODEL_FINGERPRINT, committed.modelFingerprint)
        assertEquals(TOKENIZER_FINGERPRINT, committed.tokenizerFingerprint)
    }

    @Test
    fun orphanPruneDeletesOnlyRecognizedStaleTemporaryFiles() = runBlocking {
        val noBackupRoot = temporaryFolder.newFolder("no-backup-stale-temporary")
        val root = File(noBackupRoot, "textual_inversions").apply { mkdirs() }
        val firstId = "00000000-0000-0000-0000-000000000001"
        val secondId = "00000000-0000-0000-0000-000000000002"
        val staleImport = File(root, ".$firstId.bin.$secondId.part").apply { writeBytes(byteArrayOf(1)) }
        val staleManifest = File(root, ".records.json.$secondId.tmp").apply { writeBytes(byteArrayOf(2)) }
        val unknownHidden = File(root, ".unrelated.part").apply { writeBytes(byteArrayOf(3)) }
        val freshImport = File(root, ".$secondId.bin.$firstId.part").apply { writeBytes(byteArrayOf(4)) }
        val staleTimestamp = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25)
        listOf(staleImport, staleManifest, unknownHidden).forEach { file ->
            assertTrue(file.setLastModified(staleTimestamp))
        }
        val store = TextualInversionStore(noBackupRoot, Dispatchers.IO)

        val deleted = store.pruneOrphans()

        assertEquals(2, deleted)
        assertFalse(staleImport.exists())
        assertFalse(staleManifest.exists())
        assertTrue(unknownHidden.exists())
        assertTrue(freshImport.exists())
    }

    private fun installLegacyArtifact(noBackupRoot: File, trigger: String): TextualInversionArtifact {
        val root = File(noBackupRoot, "textual_inversions").apply { mkdirs() }
        val id = UUID.randomUUID().toString().lowercase()
        val bytes = ByteArray(16) { index -> index.toByte() }
        val file = File(root, "$id.bin").apply { writeBytes(bytes) }
        val artifact = TextualInversionArtifact(
            id = id,
            name = trigger,
            trigger = trigger,
            fileName = file.name,
            path = file.canonicalPath,
            sha256 = bytes.sha256(),
            sizeBytes = bytes.size.toLong(),
            format = TextualInversionFormat.BINARY,
            importedAt = 1L
        )
        File(root, "records.json").writeText(
            JSONArray().put(artifact.toJson()).toString(),
            Charsets.UTF_8
        )
        return artifact
    }

    private fun artifact(trigger: String): TextualInversionArtifact {
        val id = UUID.randomUUID().toString().lowercase()
        return TextualInversionArtifact(
            id = id,
            name = trigger,
            trigger = trigger,
            fileName = "$id.bin",
            path = File(temporaryFolder.root, "$id.bin").absolutePath,
            sha256 = ByteArray(16).sha256(),
            sizeBytes = 16L,
            format = TextualInversionFormat.BINARY
        )
    }

    private fun binding(artifact: TextualInversionArtifact): TextualInversionBinding = artifact.bind(
        modelFingerprint = MODEL_FINGERPRINT,
        tokenizerFingerprint = TOKENIZER_FINGERPRINT,
        profileId = "stable-test",
        profileRevision = 1
    )

    private fun executionAsset(
        label: String,
        sha256: String
    ): TextualInversionExecutionAssetDescriptor = TextualInversionExecutionAssetDescriptor(
        label = label,
        path = File(temporaryFolder.root, label).absolutePath,
        sizeBytes = 16L,
        sha256 = sha256
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun tensorMetadata(shape: IntArray, start: Int, end: Int): JSONObject = JSONObject()
        .put("dtype", "F32")
        .put("shape", JSONArray().apply { shape.forEach { dimension -> put(dimension) } })
        .put("data_offsets", JSONArray().put(start).put(end))

    private fun safetensorsBytes(header: JSONObject, data: ByteArray): ByteArray =
        safetensorsBytes(header.toString().toByteArray(Charsets.UTF_8), data)

    private fun safetensorsBytes(header: ByteArray, data: ByteArray): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES + header.size + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(header.size.toLong())
            .put(header)
            .put(data)
            .array()

    private companion object {
        const val MODEL_FINGERPRINT =
            "1111111111111111111111111111111111111111111111111111111111111111"
        const val TOKENIZER_FINGERPRINT =
            "2222222222222222222222222222222222222222222222222222222222222222"
        const val SECOND_MODEL_FINGERPRINT =
            "3333333333333333333333333333333333333333333333333333333333333333"
        const val SECOND_TOKENIZER_FINGERPRINT =
            "4444444444444444444444444444444444444444444444444444444444444444"
    }
}

package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageExecutionJournalTest {
    private val noOpParentDirectorySyncer = ParentDirectorySyncer { }

    @Test
    fun `journal syncs its parent directory after atomic publication`() =
        withTempDirectory { root ->
            var now = 500L
            val journalRoot = File(root, "journal")
            val synced = mutableListOf<File>()
            val store = ImageExecutionJournalStore(
                directory = journalRoot,
                parentDirectorySyncer = ParentDirectorySyncer { directory ->
                    synced += directory
                },
                clock = { ++now }
            )

            store.create(entry(requestId = "durable", createdAtMs = now))

            assertEquals(listOf(journalRoot.canonicalFile), synced)
        }

    @Test
    fun `journal atomically round trips lifecycle and native evidence`() = withTempDirectory { root ->
        var now = 1000L
        val store = ImageExecutionJournalStore(
            File(root, "journal"),
            noOpParentDirectorySyncer
        ) { ++now }
        val initial = entry(
            requestId = "request-1",
            createdAtMs = now,
            steps = 20,
            modelId = "cyberrealistic-sd15-qnn228-8gen2",
            modelName = "CyberRealistic SD1.5 QNN 2.28",
            recommendationId = "cyberrealistic_sd15_qnn228",
            recommendationRevision = "162fe0a46cb3f9017b9e2bc003eb168e8bbf4b04",
            requested = JSONObject()
                .put("seed", 42)
                .put("negativePromptSpecified", true)
                .put("negativePrompt", "")
                .toString()
        )

        store.create(initial)
        val sampling = store.update(
            initial.copy(
                phase = ImageExecutionPhase.SAMPLING,
                step = 3,
                nativeStageMask = 0b111,
                nativeGenerationSequence = 9L,
                workerPid = 4321,
                updatedAtMs = ++now
            )
        )
        val decoding = store.update(
            sampling.copy(
                phase = ImageExecutionPhase.DECODING,
                step = 20,
                nativeStageMask = 0b1111,
                updatedAtMs = ++now
            )
        )
        val publishing = store.update(
            decoding.copy(
                phase = ImageExecutionPhase.PUBLISHING,
                outputArtifacts = listOf(
                    ImageExecutionOutputArtifact(
                        index = 0,
                        mimeType = "image/png",
                        bytes = 496_103L,
                        sha256 = "7".repeat(64),
                        seed = 42L
                    )
                ),
                updatedAtMs = ++now
            )
        )
        val completed = store.markTerminal(
            requestId = publishing.requestId,
            phase = ImageExecutionPhase.COMPLETED
        )
        val restored = store.read(initial.requestId)

        assertNotNull(restored)
        assertEquals(ImageExecutionPhase.COMPLETED, restored?.phase)
        assertEquals(20, restored?.step)
        assertEquals(0b1111L, restored?.nativeStageMask)
        assertEquals(9L, restored?.nativeGenerationSequence)
        assertEquals(4321, restored?.workerPid)
        assertEquals("cyberrealistic-sd15-qnn228-8gen2", restored?.modelId)
        assertEquals("cyberrealistic_sd15_qnn228", restored?.recommendationId)
        assertEquals("7".repeat(64), restored?.outputArtifacts?.single()?.sha256)
        assertEquals(496_103L, restored?.outputArtifacts?.single()?.bytes)
        assertTrue(JSONObject(restored?.requestedSummaryJson).getBoolean("negativePromptSpecified"))
        assertEquals("", JSONObject(restored?.requestedSummaryJson).getString("negativePrompt"))
        assertTrue(completed.phase.terminal)
        assertTrue(File(root, "journal").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `schema one journal is promoted without inventing provenance`() {
        val legacy = entry(requestId = "legacy", createdAtMs = 900L)
            .toJson()
            .put("schemaVersion", 1)
            .apply {
                remove("modelId")
                remove("modelName")
                remove("recommendationId")
                remove("recommendationRevision")
                remove("outputArtifacts")
            }

        val restored = ImageExecutionJournalEntry.fromJson(legacy)

        assertEquals(ImageExecutionJournalEntry.SCHEMA_VERSION, restored.schemaVersion)
        assertEquals("", restored.modelId)
        assertEquals("", restored.recommendationId)
        assertTrue(restored.outputArtifacts.isEmpty())
    }

    @Test
    fun `cancellation cleans only allowlisted transient files and becomes terminal`() = withTempDirectory { root ->
        var now = 2000L
        val journalRoot = File(root, "journal")
        val cleanupRoot = File(root, "transient").apply { mkdirs() }
        val outsideRoot = File(root, "outside").apply { mkdirs() }
        val latent = File(cleanupRoot, "request.latent.tmp").apply { writeText("latent") }
        val input = File(cleanupRoot, "request.input.img").apply { writeText("input") }
        val secondOutput = File(cleanupRoot, "request-001.png.part").apply { writeText("output") }
        val outside = File(outsideRoot, "must-remain.tmp").apply { writeText("outside") }
        val store = ImageExecutionJournalStore(journalRoot, noOpParentDirectorySyncer) { ++now }
        store.create(
            entry(
                requestId = "cancel-me",
                createdAtMs = now,
                latentTempPath = latent.absolutePath,
                outputTempPath = outside.absolutePath,
                outputTempPaths = listOf(secondOutput.absolutePath),
                inputTempPaths = listOf(input.absolutePath)
            )
        )

        val result = store.finishCancelled("cancel-me", cleanupRoots = listOf(cleanupRoot))

        assertEquals(ImageExecutionPhase.CANCELLED, result.entry.phase)
        assertTrue(result.entry.cancellationRequested)
        assertEquals("CANCELLED", result.entry.errorCode)
        assertFalse(latent.exists())
        assertFalse(input.exists())
        assertFalse(secondOutput.exists())
        assertTrue(outside.exists())
        assertTrue(result.cleanup.deletedPaths.contains(latent.canonicalPath))
        assertTrue(result.cleanup.skippedPaths.contains(outside.canonicalPath))
        assertTrue(store.deleteTerminal("cancel-me"))
    }

    @Test
    fun `recovery marks dead workers interrupted and leaves live workers untouched`() = withTempDirectory { root ->
        var now = 3000L
        val journalRoot = File(root, "journal")
        val cleanupRoot = File(root, "transient").apply { mkdirs() }
        val deadOutput = File(cleanupRoot, "dead.tmp.png").apply { writeText("partial") }
        val liveOutput = File(cleanupRoot, "live.tmp.png").apply { writeText("partial") }
        val store = ImageExecutionJournalStore(journalRoot, noOpParentDirectorySyncer) { ++now }
        val deadInitial = store.create(
            entry(
                requestId = "dead",
                createdAtMs = now,
                steps = 20,
                outputTempPath = deadOutput.absolutePath,
                requested = JSONObject().put("seed", 20260717).toString()
            )
        )
        store.update(
            deadInitial.copy(
                phase = ImageExecutionPhase.SAMPLING,
                step = 7,
                workerPid = 111,
                updatedAtMs = ++now
            )
        )
        val liveInitial = store.create(
            entry(
                requestId = "live",
                createdAtMs = ++now,
                steps = 20,
                outputTempPath = liveOutput.absolutePath
            )
        )
        val liveSampling = store.update(
            liveInitial.copy(
                phase = ImageExecutionPhase.SAMPLING,
                step = 20,
                workerPid = 222,
                updatedAtMs = ++now
            )
        )
        store.update(
            liveSampling.copy(
                phase = ImageExecutionPhase.DECODING,
                updatedAtMs = ++now
            )
        )

        val report = store.recoverInterrupted(cleanupRoots = listOf(cleanupRoot)) { pid ->
            pid == 222
        }

        assertEquals(listOf("dead"), report.interrupted.map { it.requestId })
        assertEquals(listOf("live"), report.stillRunning.map { it.requestId })
        assertEquals(ImageExecutionPhase.INTERRUPTED, store.read("dead")?.phase)
        assertEquals("WORKER_INTERRUPTED", store.read("dead")?.errorCode)
        assertEquals(20260717, JSONObject(store.read("dead")?.requestedSummaryJson).getInt("seed"))
        assertEquals(ImageExecutionPhase.DECODING, store.read("live")?.phase)
        assertFalse(deadOutput.exists())
        assertTrue(liveOutput.exists())
        assertTrue(report.invalidJournalFiles.isEmpty())
    }

    @Test
    fun `terminal journal pruning retains active request metadata`() = withTempDirectory { root ->
        var now = 3250L
        val store = ImageExecutionJournalStore(
            File(root, "journal"),
            noOpParentDirectorySyncer
        ) { ++now }
        store.create(entry(requestId = "completed", createdAtMs = now))
        store.markTerminal("completed", ImageExecutionPhase.FAILED)
        store.create(entry(requestId = "active", createdAtMs = ++now))

        val report = store.pruneTerminalJournals()

        assertEquals(null, store.read("completed"))
        assertNotNull(store.read("active"))
        assertEquals(1, report.deletedPaths.size)
        assertTrue(report.failedPaths.isEmpty())
    }

    @Test
    fun `terminal pruning cleans provider owned artifacts before deleting legacy journal`() =
        withTempDirectory { root ->
            var now = 3400L
            val cleanupRoot = File(root, "handoff").apply { mkdirs() }
            val conditioning = File(cleanupRoot, "legacy.sdxl-conditioning.f32").apply {
                writeText("conditioning")
            }
            val output = File(cleanupRoot, "legacy.png").apply { writeText("png") }
            val store = ImageExecutionJournalStore(
                File(cleanupRoot, "request-journal"),
                noOpParentDirectorySyncer
            ) { ++now }
            store.create(
                entry(
                    requestId = "legacy-terminal-owner",
                    createdAtMs = now,
                    outputTempPath = output.canonicalPath,
                    inputTempPaths = listOf(conditioning.canonicalPath)
                )
            )
            store.markTerminal("legacy-terminal-owner", ImageExecutionPhase.FAILED)

            val report = store.pruneTerminalJournals(cleanupRoots = listOf(cleanupRoot))

            assertFalse(conditioning.exists())
            assertFalse(output.exists())
            assertEquals(null, store.read("legacy-terminal-owner"))
            assertTrue(report.deletedPaths.contains(conditioning.canonicalPath))
            assertTrue(report.deletedPaths.contains(output.canonicalPath))
        }

    @Test
    fun `split lease recovers conditioning and tensor artifacts written before coordinator entry`() =
        withTempDirectory { root ->
            var now = 5000L
            val first = splitLeaseFixture(
                root = root,
                requestId = "qnn-htp-5000-11111111-1111-1111-1111-111111111111"
            ) { ++now }
            first.lease.releaseProcessOwnershipForFaultInjection()
            first.embeddingsFile.writeText("conditioning")
            first.inputTensorFile.writeText("complete tensor")
            File(first.inputTensorFile.path + ".part").writeText("partial tensor")

            val second = splitLeaseFixture(
                root,
                "qnn-htp-5001-22222222-2222-2222-2222-222222222222"
            ) { ++now }

            assertFalse(first.embeddingsFile.exists())
            assertFalse(first.inputTensorFile.exists())
            assertFalse(File(first.inputTensorFile.path + ".part").exists())
            assertEquals(ImageExecutionPhase.PREPARING, second.lease.entry.phase)
            assertEquals(
                1,
                File(root, "request-journal").listFiles().orEmpty()
                    .count { file -> file.name.endsWith(".json") }
            )
        }

    @Test
    fun `split artifacts stay in cache root while locks and journals use durable coordination root`() =
        withTempDirectory { root ->
            var now = 5500L
            val artifactRoot = File(root, "cache/sdxl_two_phase").apply { mkdirs() }
            val coordinationRoot = File(root, "no_backup/sdxl_two_phase_coordination")
            val fixture = splitLeaseFixture(
                root = artifactRoot,
                requestId = "qnn-htp-5500-12121212-1212-1212-1212-121212121212",
                coordinationRoot = coordinationRoot
            ) { ++now }

            val declaredLock = File(
                fixture.params.getString(SDXL_REQUEST_LEASE_LOCK_PATH_FIELD)
            ).canonicalFile
            assertEquals(coordinationRoot.canonicalFile, declaredLock.parentFile)
            assertTrue(
                coordinationRoot.listFiles().orEmpty().any { file -> file.name.endsWith(".json") }
            )
            assertFalse(File(artifactRoot, "request-journal").exists())
            assertEquals(artifactRoot.canonicalFile, fixture.outputFile.parentFile?.canonicalFile)

            assertTrue(
                fixture.lease.tryFinishAfterProviderCleanup(
                    succeeded = false,
                    cancelled = false,
                    error = null,
                    admissionTimeoutMs = 0L,
                    requestTimeoutMs = 0L
                )
            )
        }

    @Test
    fun `split lease stays publishing until provider copies and cleans output`() =
        withTempDirectory { root ->
            var now = 6000L
            val fixture = splitLeaseFixture(
                root = root,
                requestId = "qnn-htp-6000-33333333-3333-3333-3333-333333333333"
            ) { ++now }
            fixture.embeddingsFile.writeText("conditioning")
            fixture.outputFile.writeText("png")
            val unetJournal = File(root, "${fixture.lease.requestId}.unet-stage.json")
            val projectionSidecar = QnnImageStageJournal
                .sdxlProjectionPreviewJournalFile(unetJournal)
                .apply { writeText("projection") }
            val projectionDirectory = File(unetJournal.path + ".previews").apply { mkdirs() }
            File(projectionDirectory, "preview-1.png").writeText("png")
            var entry = fixture.lease.entry
            entry = fixture.lease.update(
                entry.copy(
                    phase = ImageExecutionPhase.SAMPLING,
                    updatedAtMs = ++now
                )
            )
            entry = fixture.lease.update(
                entry.copy(
                    phase = ImageExecutionPhase.DECODING,
                    updatedAtMs = ++now
                )
            )
            fixture.lease.update(
                entry.copy(
                    phase = ImageExecutionPhase.PUBLISHING,
                    updatedAtMs = ++now
                )
            )
            fixture.lease.releaseProcessOwnershipForFaultInjection()
            val phaseOwnership = SdxlTwoPhaseRequestLease.acquirePhaseOwnership(
                requestId = fixture.lease.requestId,
                paramsJson = fixture.params.toString(),
                artifactRoot = root,
                coordinationRoot = File(root, "request-journal")
            )

            assertFalse(
                fixture.lease.tryFinishAfterProviderCleanup(
                    succeeded = true,
                    cancelled = false,
                    error = null
                )
            )
            assertEquals(ImageExecutionPhase.PUBLISHING, fixture.lease.entry.phase)
            assertTrue(File(root, "request-journal").listFiles().orEmpty().any { it.name.endsWith(".json") })
            assertTrue(fixture.embeddingsFile.exists())
            assertTrue(fixture.outputFile.exists())
            assertTrue(projectionSidecar.exists())
            assertTrue(projectionDirectory.exists())

            phaseOwnership.close()
            assertTrue(
                fixture.lease.tryFinishAfterProviderCleanup(
                    succeeded = true,
                    cancelled = false,
                    error = null
                )
            )
            assertFalse(fixture.embeddingsFile.exists())
            assertFalse(fixture.outputFile.exists())
            assertFalse(projectionSidecar.exists())
            assertFalse(projectionDirectory.exists())
            assertTrue(File(root, "request-journal").listFiles().orEmpty().none { it.name.endsWith(".json") })
        }

    @Test
    fun `provider cleanup barrier retry is bounded and diagnostics are throttled`() {
        assertEquals(25L, sdxlCleanupBarrierRetryDelayMs(1L))
        assertEquals(50L, sdxlCleanupBarrierRetryDelayMs(2L))
        assertEquals(800L, sdxlCleanupBarrierRetryDelayMs(6L))
        assertEquals(1_000L, sdxlCleanupBarrierRetryDelayMs(7L))
        assertEquals(1_000L, sdxlCleanupBarrierRetryDelayMs(Long.MAX_VALUE))

        assertTrue(shouldReportSdxlCleanupBarrierRetry(1L))
        assertTrue(shouldReportSdxlCleanupBarrierRetry(2L))
        assertFalse(shouldReportSdxlCleanupBarrierRetry(3L))
        assertTrue(shouldReportSdxlCleanupBarrierRetry(60L))
        assertFalse(shouldReportSdxlCleanupBarrierRetry(61L))
    }

    @Test
    fun `provider cleanup barrier waits for exact phase lock before admitting another lease`() =
        withTempDirectory { root ->
            var now = 6500L
            val fixture = splitLeaseFixture(
                root = root,
                requestId = "qnn-htp-6500-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            ) { ++now }
            fixture.lease.releaseProcessOwnershipForFaultInjection()
            val phaseOwnership = SdxlTwoPhaseRequestLease.acquirePhaseOwnership(
                requestId = fixture.lease.requestId,
                paramsJson = fixture.params.toString(),
                artifactRoot = root,
                coordinationRoot = File(root, "request-journal")
            )
            val started = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit {
                started.countDown()
                runBlocking {
                    fixture.lease.awaitFinishAfterProviderCleanup(
                        succeeded = false,
                        cancelled = true,
                        error = null,
                        lockAttemptTimeoutMs = 25L
                    )
                }
                finished.countDown()
            }
            try {
                assertTrue(started.await(1L, TimeUnit.SECONDS))
                var barrierOwnsAdmission = false
                repeat(100) {
                    val probe = SdxlRequestFileLock.acquire(
                        File(File(root, "request-journal"), "split-admission.lock"),
                        shared = false,
                        timeoutMs = 0L
                    )
                    if (probe == null) {
                        barrierOwnsAdmission = true
                        return@repeat
                    }
                    probe.close()
                    Thread.sleep(10L)
                }
                assertTrue(barrierOwnsAdmission)
                assertFalse(finished.await(150L, TimeUnit.MILLISECONDS))
                assertTrue(
                    runCatching {
                        splitLeaseFixture(
                            root = root,
                            requestId = "qnn-htp-6501-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                            recoveryRequestLockTimeoutMs = 0L
                        ) { ++now }
                    }.isFailure
                )

                phaseOwnership.close()
                assertTrue(finished.await(2L, TimeUnit.SECONDS))
                future.get(2L, TimeUnit.SECONDS)
                assertTrue(
                    File(root, "request-journal").listFiles().orEmpty()
                        .none { file -> file.name.endsWith(".json") }
                )
            } finally {
                runCatching { phaseOwnership.close() }
                executor.shutdownNow()
            }
        }

    @Test
    fun `cleanup revokes journal before failed artifact deletion rejects a late phase and new lease`() =
        withTempDirectory { root ->
            var now = 6800L
            val requestId = "qnn-htp-6800-cccccccc-cccc-cccc-cccc-cccccccccccc"
            val fixture = splitLeaseFixture(root, requestId) { ++now }
            val undeletableChild = File(fixture.outputFile, "still-open").also { child ->
                child.parentFile?.mkdirs()
                child.writeText("busy")
            }

            assertTrue(
                fixture.lease.tryFinishAfterProviderCleanup(
                    succeeded = false,
                    cancelled = true,
                    error = null,
                    admissionTimeoutMs = 0L,
                    requestTimeoutMs = 0L
                )
            )
            val coordinationRoot = File(root, "request-journal")
            val terminal = ImageExecutionJournalStore(
                coordinationRoot,
                noOpParentDirectorySyncer
            ).read(requestId)
            assertNotNull(terminal)
            assertEquals(ImageExecutionPhase.CANCELLED, terminal?.phase)
            assertTrue(fixture.outputFile.exists())
            assertTrue(
                runCatching {
                    SdxlTwoPhaseRequestLease.acquirePhaseOwnership(
                        requestId = requestId,
                        paramsJson = fixture.params.toString(),
                        artifactRoot = root,
                        coordinationRoot = coordinationRoot
                    )
                }.isFailure
            )
            assertTrue(
                runCatching {
                    splitLeaseFixture(
                        root = root,
                        requestId = "qnn-htp-6801-dddddddd-dddd-dddd-dddd-dddddddddddd",
                        recoveryRequestLockTimeoutMs = 0L
                    ) { ++now }
                }.isFailure
            )

            assertTrue(undeletableChild.delete())
            assertTrue(fixture.outputFile.delete())
            SdxlTwoPhaseRequestLease.recoverAbandonedRequests(
                artifactRoot = root,
                coordinationRoot = coordinationRoot,
                requestLockTimeoutMs = 0L,
                parentDirectorySyncer = noOpParentDirectorySyncer
            )
            assertEquals(null, ImageExecutionJournalStore(coordinationRoot).read(requestId))
        }

    @Test
    fun `missing journal orphan sweep waits for exact phase request lock`() =
        withTempDirectory { root ->
            var now = 7000L
            val requestId = "qnn-htp-7000-12345678-1234-1234-1234-123456789abc"
            val fixture = splitLeaseFixture(root, requestId) { ++now }
            fixture.outputFile.writeText("in-flight png")
            fixture.lease.releaseProcessOwnershipForFaultInjection()
            val phaseOwnership = SdxlTwoPhaseRequestLease.acquirePhaseOwnership(
                requestId = requestId,
                paramsJson = fixture.params.toString(),
                artifactRoot = root,
                coordinationRoot = File(root, "request-journal")
            )
            File(root, "request-journal").listFiles().orEmpty()
                .single { file -> file.name.endsWith(".json") }
                .delete()

            assertTrue(
                runCatching {
                    SdxlTwoPhaseRequestLease.recoverAbandonedRequests(
                        artifactRoot = root,
                        coordinationRoot = File(root, "request-journal"),
                        requestLockTimeoutMs = 0L,
                        parentDirectorySyncer = noOpParentDirectorySyncer
                    )
                }.isFailure
            )
            assertTrue(fixture.outputFile.exists())
            assertTrue(
                runCatching {
                    splitLeaseFixture(
                        root,
                        "qnn-htp-7001-44444444-4444-4444-4444-444444444444",
                        recoveryRequestLockTimeoutMs = 0L
                    ) { ++now }
                }.isFailure
            )

            phaseOwnership.close()
            SdxlTwoPhaseRequestLease.recoverAbandonedRequests(
                artifactRoot = root,
                coordinationRoot = File(root, "request-journal"),
                requestLockTimeoutMs = 0L,
                parentDirectorySyncer = noOpParentDirectorySyncer
            )
            assertFalse(fixture.outputFile.exists())
        }

    @Test
    fun `persistent request lock alone blocks recovery and another lease until phase exits`() =
        withTempDirectory { root ->
            var now = 7100L
            val requestId = "qnn-htp-7100-55555555-5555-5555-5555-555555555555"
            val fixture = splitLeaseFixture(root, requestId) { ++now }
            fixture.lease.releaseProcessOwnershipForFaultInjection()
            val phaseOwnership = SdxlTwoPhaseRequestLease.acquirePhaseOwnership(
                requestId = requestId,
                paramsJson = fixture.params.toString(),
                artifactRoot = root,
                coordinationRoot = File(root, "request-journal")
            )
            val requestLockTombstone = File(
                fixture.params.getString(SDXL_REQUEST_LEASE_LOCK_PATH_FIELD)
            )
            val journal = File(root, "request-journal").listFiles().orEmpty()
                .single { file -> file.name.endsWith(".json") }
            assertTrue(journal.delete())
            assertFalse(fixture.embeddingsFile.exists())
            assertFalse(fixture.inputTensorFile.exists())
            assertFalse(fixture.outputFile.exists())

            try {
                assertTrue(
                    runCatching {
                        SdxlTwoPhaseRequestLease.recoverAbandonedRequests(
                            artifactRoot = root,
                            coordinationRoot = File(root, "request-journal"),
                            requestLockTimeoutMs = 0L,
                            parentDirectorySyncer = noOpParentDirectorySyncer
                        )
                    }.isFailure
                )
                assertTrue(
                    runCatching {
                        splitLeaseFixture(
                            root,
                            "qnn-htp-7101-66666666-6666-6666-6666-666666666666",
                            recoveryRequestLockTimeoutMs = 0L
                        ) { ++now }
                    }.isFailure
                )
                assertTrue(requestLockTombstone.exists())
            } finally {
                phaseOwnership.close()
            }

            SdxlTwoPhaseRequestLease.recoverAbandonedRequests(
                artifactRoot = root,
                coordinationRoot = File(root, "request-journal"),
                requestLockTimeoutMs = 0L,
                parentDirectorySyncer = noOpParentDirectorySyncer
            )
            assertFalse(requestLockTombstone.exists())
            val next = splitLeaseFixture(
                root,
                "qnn-htp-7102-77777777-7777-7777-7777-777777777777",
                recoveryRequestLockTimeoutMs = 0L
            ) { ++now }
            assertEquals(ImageExecutionPhase.PREPARING, next.lease.entry.phase)
            next.lease.close()
            SdxlTwoPhaseRequestLease.recoverAbandonedRequests(
                artifactRoot = root,
                coordinationRoot = File(root, "request-journal"),
                requestLockTimeoutMs = 0L,
                parentDirectorySyncer = noOpParentDirectorySyncer
            )
        }

    @Test
    fun `request lock directory enumeration failure defers recovery and new lease`() =
        withTempDirectory { root ->
            var now = 7150L
            val recoveryFailure = runCatching {
                SdxlTwoPhaseRequestLease.recoverAbandonedRequests(
                    artifactRoot = root,
                    coordinationRoot = File(root, "request-journal"),
                    requestLockTimeoutMs = 0L,
                    parentDirectorySyncer = noOpParentDirectorySyncer,
                    requestLockDirectoryLister = { null }
                )
            }.exceptionOrNull()

            assertNotNull(recoveryFailure)
            assertTrue(
                recoveryFailure!!.message.orEmpty().contains(
                    "Unable to enumerate persistent split-SDXL request locks"
                )
            )
            val acquireFailure = runCatching {
                splitLeaseFixture(
                    root = root,
                    requestId = "qnn-htp-7150-99999999-9999-9999-9999-999999999999",
                    recoveryRequestLockTimeoutMs = 0L,
                    requestLockDirectoryLister = { null }
                ) { ++now }
            }.exceptionOrNull()
            assertNotNull(acquireFailure)
            assertTrue(
                acquireFailure!!.message.orEmpty().contains(
                    "Unable to enumerate persistent split-SDXL request locks"
                )
            )
            assertTrue(
                File(root, "request-journal").listFiles().orEmpty()
                    .none { file -> file.name.endsWith(".json") }
            )
        }

    @Test
    fun `finish admission timeout retains provider owner until a real barrier is acquired`() =
        withTempDirectory { root ->
            var now = 7200L
            val fixture = splitLeaseFixture(
                root,
                "qnn-htp-7200-88888888-8888-8888-8888-888888888888"
            ) { ++now }
            val journal = File(root, "request-journal").listFiles().orEmpty()
                .single { file -> file.name.endsWith(".json") }
            val admissionLock = SdxlRequestFileLock.acquire(
                File(File(root, "request-journal"), "split-admission.lock"),
                shared = false,
                timeoutMs = 0L
            ) ?: throw AssertionError("Expected the test to own split-SDXL admission")

            admissionLock.use {
                assertFalse(
                    fixture.lease.tryFinishAfterProviderCleanup(
                        succeeded = false,
                        cancelled = false,
                        error = null,
                        admissionTimeoutMs = 0L
                    )
                )
                val requestExclusive = SdxlRequestFileLock.acquire(
                    File(fixture.params.getString(SDXL_REQUEST_LEASE_LOCK_PATH_FIELD)),
                    shared = false,
                    timeoutMs = 0L
                )
                assertEquals(null, requestExclusive)
                assertTrue(journal.exists())
            }

            assertTrue(
                fixture.lease.tryFinishAfterProviderCleanup(
                    succeeded = false,
                    cancelled = false,
                    error = null,
                    admissionTimeoutMs = 0L,
                    requestTimeoutMs = 0L
                )
            )
            assertFalse(journal.exists())
        }

    @Test
    fun `recovery removes worker input files and their request directory`() = withTempDirectory { root ->
        var now = 3500L
        val cleanupRoot = File(root, "cache").apply { mkdirs() }
        val requestDirectory = File(cleanupRoot, "worker-input-request").apply { mkdirs() }
        val input = File(requestDirectory, "input.img").apply { writeText("input") }
        val inputPath = input.canonicalPath
        val directoryPath = requestDirectory.canonicalPath
        val store = ImageExecutionJournalStore(
            File(root, "journal"),
            noOpParentDirectorySyncer
        ) { ++now }
        store.create(
            entry(
                requestId = "dead-input",
                createdAtMs = now,
                workerPid = 333,
                inputTempPaths = listOf(inputPath, directoryPath)
            )
        )

        val report = store.recoverInterrupted(cleanupRoots = listOf(cleanupRoot)) { false }

        assertEquals(listOf("dead-input"), report.interrupted.map { it.requestId })
        assertFalse(input.exists())
        assertFalse(requestDirectory.exists())
        assertTrue(report.cleanup.deletedPaths.contains(inputPath))
        assertTrue(report.cleanup.deletedPaths.contains(directoryPath))
    }

    @Test
    fun `publishing recovery preserves complete result long enough for client consumption`() =
        withTempDirectory { root ->
            var now = 3750L
            val cleanupRoot = File(root, "cache").apply { mkdirs() }
            val complete = File(cleanupRoot, "result.png").apply { writeText("complete") }
            val partial = File(cleanupRoot, "result-2.png.part").apply { writeText("partial") }
            val input = File(cleanupRoot, "input.png").apply { writeText("input") }
            val store = ImageExecutionJournalStore(
                File(root, "journal"),
                noOpParentDirectorySyncer
            ) { ++now }
            val preparing = store.create(
                entry(
                    requestId = "publishing-dead",
                    createdAtMs = now,
                    workerPid = 444,
                    inputTempPaths = listOf(input.canonicalPath)
                )
            )
            val sampling = store.update(
                preparing.copy(
                    phase = ImageExecutionPhase.SAMPLING,
                    updatedAtMs = ++now
                )
            )
            val decoding = store.update(
                sampling.copy(
                    phase = ImageExecutionPhase.DECODING,
                    updatedAtMs = ++now
                )
            )
            val publishing = store.update(
                decoding.copy(
                    phase = ImageExecutionPhase.PUBLISHING,
                    updatedAtMs = ++now
                )
            )
            store.update(
                publishing.copy(
                    outputTempPath = complete.canonicalPath,
                    outputTempPaths = listOf(complete.canonicalPath, partial.canonicalPath),
                    updatedAtMs = ++now
                )
            )

            val report = store.recoverInterrupted(cleanupRoots = listOf(cleanupRoot)) { false }

            assertEquals(listOf("publishing-dead"), report.interrupted.map { it.requestId })
            assertTrue(complete.exists())
            assertFalse(partial.exists())
            assertFalse(input.exists())
            assertFalse(report.cleanup.deletedPaths.contains(complete.canonicalPath))
        }

    @Test
    fun `terminal and regressive transitions fail closed`() = withTempDirectory { root ->
        var now = 4000L
        val store = ImageExecutionJournalStore(
            File(root, "journal"),
            noOpParentDirectorySyncer
        ) { ++now }
        val initial = entry(requestId = "strict", createdAtMs = now, steps = 4)
        store.create(initial)
        val sampling = store.update(
            initial.copy(
                phase = ImageExecutionPhase.SAMPLING,
                step = 2,
                nativeStageMask = 0b11,
                updatedAtMs = ++now
            )
        )

        assertInvalid {
            store.update(
                sampling.copy(
                    phase = ImageExecutionPhase.CONDITIONING,
                    step = 1,
                    nativeStageMask = 0b1,
                    updatedAtMs = ++now
                )
            )
        }

        val failed = store.markTerminal("strict", ImageExecutionPhase.FAILED, "NATIVE_FAILED", "failed")
        assertEquals(ImageExecutionPhase.FAILED, failed.phase)
        assertInvalid { store.requestCancellation("strict") }
    }

    @Test
    fun `journal read rejects content whose request identity differs from its file`() =
        withTempDirectory { root ->
            var now = 3150L
            val store = ImageExecutionJournalStore(
                File(root, "journal"),
                noOpParentDirectorySyncer
            ) { ++now }
            store.create(entry(requestId = "expected-request", createdAtMs = now))
            val journal = File(root, "journal").listFiles().orEmpty()
                .single { file -> file.name.endsWith(".json") }
            journal.writeText(
                entry(requestId = "substituted-request", createdAtMs = now)
                    .toJson()
                    .toString(),
                Charsets.UTF_8
            )

            assertInvalid { store.read("expected-request") }
            assertTrue(store.listReadableEntries().isEmpty())
        }

    @Test
    fun `same process journal stores serialize before taking the cross process file lock`() =
        withTempDirectory { root ->
            val journalRoot = File(root, "journal")
            val firstPublicationEntered = CountDownLatch(1)
            val releaseFirstPublication = CountDownLatch(1)
            val syncCalls = AtomicInteger()
            val blockingSyncer = ParentDirectorySyncer {
                if (syncCalls.incrementAndGet() == 1) {
                    firstPublicationEntered.countDown()
                    check(releaseFirstPublication.await(5, TimeUnit.SECONDS))
                }
            }
            val firstStore = ImageExecutionJournalStore(journalRoot, blockingSyncer)
            val secondStore = ImageExecutionJournalStore(journalRoot, blockingSyncer)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit<ImageExecutionJournalEntry> {
                    firstStore.create(entry(requestId = "parallel-a", createdAtMs = 1_000L))
                }
                assertTrue(firstPublicationEntered.await(5, TimeUnit.SECONDS))
                val secondStarted = CountDownLatch(1)
                val second = executor.submit<ImageExecutionJournalEntry> {
                    secondStarted.countDown()
                    secondStore.create(entry(requestId = "parallel-b", createdAtMs = 1_001L))
                }
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
                Thread.sleep(100L)
                assertFalse(second.isDone)

                releaseFirstPublication.countDown()
                assertEquals("parallel-a", first.get(5, TimeUnit.SECONDS).requestId)
                assertEquals("parallel-b", second.get(5, TimeUnit.SECONDS).requestId)
                assertEquals(
                    setOf("parallel-a", "parallel-b"),
                    firstStore.listReadableEntries().map { it.requestId }.toSet()
                )
            } finally {
                releaseFirstPublication.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun `interrupted split request lock wait releases its opened descriptor`() =
        withTempDirectory { root ->
            val lockFile = File(root, "request.lock")
            val owner = requireNotNull(
                SdxlRequestFileLock.acquire(lockFile, shared = false, timeoutMs = 0L)
            )
            val executor = Executors.newSingleThreadExecutor()
            try {
                val interrupted = executor.submit<Throwable?> {
                    Thread.currentThread().interrupt()
                    runCatching {
                        SdxlRequestFileLock.acquire(lockFile, shared = false, timeoutMs = 1_000L)
                    }.exceptionOrNull()
                }.get(5, TimeUnit.SECONDS)
                assertTrue(interrupted is InterruptedException)
            } finally {
                owner.close()
                executor.shutdownNow()
            }

            assertTrue(lockFile.delete())
            SdxlRequestFileLock.acquire(lockFile, shared = false, timeoutMs = 0L).use { reacquired ->
                assertNotNull(reacquired)
            }
        }

    private fun entry(
        requestId: String,
        createdAtMs: Long,
        phase: ImageExecutionPhase = ImageExecutionPhase.PREPARING,
        step: Int = 0,
        steps: Int = 0,
        workerPid: Int = -1,
        latentTempPath: String = "",
        outputTempPath: String = "",
        outputTempPaths: List<String> = emptyList(),
        inputTempPaths: List<String> = emptyList(),
        modelId: String = "",
        modelName: String = "",
        recommendationId: String = "",
        recommendationRevision: String = "",
        requested: String = "{}"
    ): ImageExecutionJournalEntry = ImageExecutionJournalEntry(
        requestId = requestId,
        modelId = modelId,
        modelName = modelName,
        recommendationId = recommendationId,
        recommendationRevision = recommendationRevision,
        modelFingerprint = "model-sha256",
        profileFingerprint = "profile-sha256",
        requestedSummaryJson = requested,
        resolvedSummaryJson = JSONObject().put("profileId", "profile.image.v1").toString(),
        phase = phase,
        step = step,
        steps = steps,
        workerPid = workerPid,
        createdAtMs = createdAtMs,
        latentTempPath = latentTempPath,
        outputTempPath = outputTempPath,
        outputTempPaths = outputTempPaths,
        inputTempPaths = inputTempPaths
    )

    private data class SplitLeaseFixture(
        val lease: SdxlTwoPhaseRequestLease,
        val params: JSONObject,
        val embeddingsFile: File,
        val inputTensorFile: File,
        val outputFile: File
    )

    private fun splitLeaseFixture(
        root: File,
        requestId: String,
        coordinationRoot: File = File(root, "request-journal"),
        recoveryRequestLockTimeoutMs: Long = 5_000L,
        requestLockDirectoryLister: (File) -> Array<File>? = File::listFiles,
        clock: () -> Long
    ): SplitLeaseFixture {
        root.mkdirs()
        val embeddingsFile = File(root, "$requestId.sdxl-conditioning.f32")
        val latentFile = File(root, "$requestId.latent.f32")
        val metadataFile = File(root, "$requestId.latent.json")
        val inputTensorFile = File(root, "$requestId.input-rgb-nchw.f32")
        val encoderLatentFile = File(root, "$requestId.encoder-latent.f32")
        val encoderMetadataFile = File(root, "$requestId.encoder-latent.json")
        val outputFile = File(root, "$requestId.png")
        val params = JSONObject()
            .put("workerStrategy", ImageWorkerStrategy.SPLIT_UNET_VAE.name)
            .put("taskMode", LocalImageTaskMode.TEXT_TO_IMAGE.wireName)
            .put("profileId", "sdxl.qnn.split")
            .put("profileRevision", 4)
            .put("modelFingerprint", "a".repeat(64))
            .put("steps", 30)
        val lease = SdxlTwoPhaseRequestLease.acquire(
            requestId = requestId,
            params = params,
            workerPid = 1234,
            coordinationRoot = coordinationRoot,
            embeddingsFile = embeddingsFile,
            latentFile = latentFile,
            metadataFile = metadataFile,
            inputTensorFile = inputTensorFile,
            encoderLatentFile = encoderLatentFile,
            encoderMetadataFile = encoderMetadataFile,
            outputFile = outputFile,
            encoderJournal = File(root, "$requestId.encoder-stage.json"),
            unetJournal = File(root, "$requestId.unet-stage.json"),
            vaeJournal = File(root, "$requestId.vae-stage.json"),
            parentDirectorySyncer = noOpParentDirectorySyncer,
            clock = clock,
            recoveryRequestLockTimeoutMs = recoveryRequestLockTimeoutMs,
            requestLockDirectoryLister = requestLockDirectoryLister
        )
        return SplitLeaseFixture(
            lease = lease,
            params = params,
            embeddingsFile = embeddingsFile,
            inputTensorFile = inputTensorFile,
            outputFile = outputFile
        )
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid journal operation")
        } catch (_: IllegalArgumentException) {
            // Expected.
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("image-execution-journal-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

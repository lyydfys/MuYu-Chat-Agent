package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsolatedNativeFailureDiagnosticsTest {
    @Test
    fun promptFingerprintIsStableWithoutStoringPromptText() {
        val prompt = "private system prompt with user content"
        val fingerprint = LocalDiagnosticRedactor.promptFingerprint(prompt)

        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertEquals(fingerprint, LocalDiagnosticRedactor.promptFingerprint(prompt))
        assertNotEquals(fingerprint, LocalDiagnosticRedactor.promptFingerprint("different"))
    }

    @Test
    fun sanitizerRemovesPromptPathAndCredentialMaterial() {
        val raw = "prompt=$PROMPT path=/data/user/0/com.example/files/model.gguf " +
            "token=Bearer abc.def.ghi"
        val sanitized = LocalDiagnosticRedactor.sanitize(raw, listOf(PROMPT))

        assertFalse(sanitized.contains(PROMPT))
        assertFalse(sanitized.contains("/data/user"))
        assertFalse(sanitized.contains("abc.def.ghi"))
        assertTrue(sanitized.contains("<prompt-redacted>") || sanitized.contains("<redacted>"))
    }

    @Test
    fun timeoutAndWatchdogHaveStageSpecificStableCodes() {
        assertEquals("native_load_timeout", IsolatedNativeFailureDiagnostics.timeout("load").code)
        assertEquals("smoke_execution_timeout", IsolatedNativeFailureDiagnostics.timeout("minimum_text").code)
        assertEquals("worker_watchdog_timeout", IsolatedNativeFailureDiagnostics.watchdog("decode", 10).code)
        assertEquals("smoke", IsolatedNativeFailureDiagnostics.watchdog("decode", 10).stage)
    }

    @Test
    fun loadAndSmokeFailuresRemainConcreteAndPromptFree() {
        val load = IsolatedNativeFailureDiagnostics.classify(
            IllegalStateException("MCA_LOAD_GGUF_METADATA_OR_ARCHITECTURE_INVALID path=/sdcard/model.gguf"),
            stage = "load"
        )
        assertEquals("gguf_metadata_or_architecture_invalid", load.code)
        assertFalse(load.message.contains("/sdcard"))

        val smoke = IsolatedNativeFailureDiagnostics.classify(
            IllegalStateException("generation failed; prompt=$PROMPT"),
            stage = "minimum_text"
        )
        assertEquals("smoke_execution_failed", smoke.code)
        assertFalse(smoke.message.contains(PROMPT))
    }

    @Test
    fun workerSourcesReportTimeoutBeforeGenericCancellationAndWatchdogCode() {
        val tuning = source("app/src/main/java/com/muyuchat/mca/TuningProbeWorkerService.kt")
        val qairt = source("app/src/main/java/com/muyuchat/mca/QairtDryRunWorkerService.kt")
        listOf(tuning, qairt).forEach { worker ->
            assertTrue(worker.contains("catch (_: TimeoutCancellationException)"))
            assertTrue(worker.contains("IsolatedNativeFailureDiagnostics.watchdog"))
            assertTrue(worker.contains("IsolatedNativeFailureDiagnostics.timeout"))
            assertTrue(worker.contains("IsolatedNativeFailureDiagnostics.classify"))
        }
        assertTrue(tuning.indexOf("catch (_: TimeoutCancellationException)") < tuning.indexOf("catch (_: CancellationException)"))
        assertTrue(qairt.indexOf("catch (_: TimeoutCancellationException)") < qairt.indexOf("catch (_: CancellationException)"))
    }

    @Test
    fun workerStageJournalPersistsOnlySafeCrashDiagnostics() {
        val directory = Files.createTempDirectory("mca-worker-journal-test").toFile()
        try {
            val journalFile = File(directory, "worker-stage.json")
            var timestamp = 1_000L
            val journal = LocalChatWorkerStageJournal.forFile(journalFile) { timestamp }
            val mainProcessReader = LocalChatWorkerStageJournal.forFile(journalFile)
            val prompt = "private prompt that must never reach the worker journal"
            val modelPath = File(directory, "private-model.gguf").absolutePath
            val parameters = LocalChatWorkerStageJournal.parameterSummary(
                """{"n_ctx":4096,"n_threads":4,"temperature":0.7,"system_prompt":"$prompt"}"""
            )

            journal.recordStarted(
                stage = "prefill",
                runtime = "LLAMA_CPP",
                modelFingerprint = LocalChatWorkerStageJournal.modelFingerprint(modelPath),
                parameterSummary = parameters,
                workerPid = 1234,
                pssKb = 8192
            )
            assertEquals("prefill", mainProcessReader.read()?.stage)
            timestamp = 1_250L
            journal.recordFailure(
                stage = "prefill",
                runtime = "LLAMA_CPP",
                modelFingerprint = LocalChatWorkerStageJournal.modelFingerprint(modelPath),
                parameterSummary = parameters,
                workerPid = 1234,
                pssKb = 9216,
                failureCode = "worker_watchdog_timeout"
            )

            val persisted = journalFile.readText(Charsets.UTF_8)
            val restored = mainProcessReader.read()

            assertFalse(persisted.contains(prompt))
            assertFalse(persisted.contains(modelPath))
            assertFalse(persisted.contains("system_prompt"))
            assertEquals("prefill", restored?.stage)
            assertEquals("failed", restored?.state)
            assertEquals("LLAMA_CPP", restored?.runtime)
            assertEquals(9216L, restored?.pssKb)
            assertEquals(250L, restored?.elapsedMs)
            assertEquals("worker_watchdog_timeout", restored?.failureCode)
            assertEquals(4096, restored?.parameterSummary?.get("n_ctx"))
            assertEquals(4, restored?.parameterSummary?.get("n_threads"))
            assertFalse(restored?.parameterSummary?.containsKey("system_prompt") == true)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun workerStageJournalRejectsUnsafeOrMalformedPersistedValues() {
        val directory = Files.createTempDirectory("mca-worker-journal-invalid").toFile()
        try {
            val journalFile = File(directory, "worker-stage.json")
            journalFile.writeText(
                """{"version":1,"stage":"decode","state":"active","runtime":"LLAMA_CPP","modelFingerprint":"${"a".repeat(64)}","parameters":{"system_prompt":"secret"},"workerPid":1,"pssKb":1,"startedAtEpochMs":1,"updatedAtEpochMs":1,"elapsedMs":0}""",
                Charsets.UTF_8
            )

            assertNull(LocalChatWorkerStageJournal.forFile(journalFile).read())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun source(relative: String): String {
        var root: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (root != null) {
            val candidate = File(root, relative)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            root = root.parentFile
        }
        error("Unable to locate source: $relative")
    }

    private companion object {
        const val PROMPT = "do not persist this prompt"
    }
}

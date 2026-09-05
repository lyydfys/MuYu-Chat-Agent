package com.muyuchat.mca

import com.muyuchat.core.engine.LocalChatRuntime
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLocalChatRunnerStateTest {
    @Test
    fun successfulLoadRequiresTheSameBinderAndSessionEpoch() {
        assertEquals(
            WorkerLoadResultDisposition.COMMITTED,
            classifyWorkerLoadResult(0, endpointStillCurrent = true, epochStillCurrent = true)
        )
        assertEquals(
            WorkerLoadResultDisposition.LOST_AFTER_SUCCESS,
            classifyWorkerLoadResult(0, endpointStillCurrent = false, epochStillCurrent = false)
        )
        assertEquals(
            WorkerLoadResultDisposition.STALE,
            classifyWorkerLoadResult(0, endpointStillCurrent = true, epochStillCurrent = false)
        )
        assertEquals(
            WorkerLoadResultDisposition.FAILED,
            classifyWorkerLoadResult(-1, endpointStillCurrent = false, epochStillCurrent = false)
        )
    }

    @Test
    fun deferredStatsPreserveTheLastConfirmedLoadedState() {
        val deferred = JSONObject(
            buildDeferredRuntimeStatsJson(
                stableStatsJson = "{\"loaded\":true,\"backend\":\"llama.cpp\"}",
                stage = "prefill"
            )
        )

        assertTrue(deferred.getBoolean("loaded"))
        assertTrue(deferred.getBoolean("runtimeStatsDeferred"))
        assertEquals("prefill", deferred.getString("runtimeBusyStage"))
    }

    @Test
    fun statsReadbackIsAcceptedOnlyForTheCapturedRunnerEpoch() {
        assertTrue(isRuntimeStatsSnapshotCurrent(7L, 7L, sameRunner = true))
        assertFalse(isRuntimeStatsSnapshotCurrent(7L, 8L, sameRunner = true))
        assertFalse(isRuntimeStatsSnapshotCurrent(7L, 7L, sameRunner = false))
    }

    @Test
    fun workerStatsPathIsNonBlockingWhenAnotherNativeOperationOwnsTheGate() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/LocalChatWorkerService.kt")
        val body = functionBody(source, "override fun getRuntimeStatsJson()")

        assertTrue(body.contains("nativeOperationGate.tryLock()"))
        assertTrue(body.contains("deferredRuntimeStatsJson"))
        assertTrue(body.contains("guardedNativeCall(\"stats\""))
        assertEquals(
            1_800_000L,
            localChatWorkerOperationPolicy(
                target = LocalChatWorkerOperationTarget(LocalChatRuntime.LLAMA_CPP, "cpu"),
                rawStage = "stats"
            ).timeoutMs
        )
    }

    @Test
    fun acceleratedTimeoutPoliciesRemainBackendSpecificAndCpuStaysGeneric() {
        val litert = localChatWorkerOperationTarget(
            LocalChatRuntime.LITERT_LM,
            "{\"backend\":\"gpu\"}"
        )
        assertEquals("gpu", litert.backend)
        assertEquals(
            120_000L,
            localChatWorkerOperationPolicy(litert, "load").timeoutMs
        )
        assertEquals(
            "litert_gpu_prefill_timeout",
            localChatWorkerOperationPolicy(litert, "prefill").timeoutFailureCode
        )
        assertTrue(localChatWorkerOperationPolicy(litert, "decode").forceProcessRecoveryOnCancel)

        val openCl = localChatWorkerOperationTarget(
            LocalChatRuntime.MNN_CPU,
            "{\"advanced_json\":{\"backend_type\":\"GPU\"}}"
        )
        assertEquals("opencl", openCl.backend)
        assertEquals(
            75_000L,
            localChatWorkerOperationPolicy(openCl, "prefill").timeoutMs
        )
        assertEquals(
            "mnn_opencl_decode_timeout",
            localChatWorkerOperationPolicy(openCl, "decode").timeoutFailureCode
        )

        val cpu = localChatWorkerOperationTarget(LocalChatRuntime.MNN_CPU, "{\"backend_type\":\"cpu\"}")
        val cpuPolicy = localChatWorkerOperationPolicy(cpu, "prefill")
        assertEquals(30L * 60L * 1_000L, cpuPolicy.timeoutMs)
        assertFalse(cpuPolicy.forceProcessRecoveryOnCancel)
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function: $signature")
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate source file: $relativePath")
    }
}

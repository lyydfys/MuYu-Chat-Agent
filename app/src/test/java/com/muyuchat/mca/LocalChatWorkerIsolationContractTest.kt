package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatWorkerIsolationContractTest {
    @Test
    fun ordinaryTextRuntimesUseTheDedicatedProcessAndBinderProxy() {
        val manifest = sourceFile("app/src/main/AndroidManifest.xml")
        val service = sourceFile("app/src/main/java/com/muyuchat/mca/LocalChatWorkerService.kt")
        val runners = sourceFile("app/src/main/java/com/muyuchat/mca/IsolatedLocalChatRunners.kt")
        val client = sourceFile("app/src/main/java/com/muyuchat/mca/RemoteLocalChatRunner.kt")
        val aidl = sourceFile("app/src/main/aidl/com/muyuchat/mca/ILocalChatWorker.aidl")
        val transport = sourceFile(
            "app/src/main/java/com/muyuchat/mca/LocalChatWorkerRequestTransport.kt"
        )

        assertTrue(manifest.contains("android:name=\".LocalChatWorkerService\""))
        assertTrue(manifest.contains("android:process=\":local_chat\""))
        assertTrue(manifest.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(manifest.contains("android:value=\"resident_local_ai_model\""))
        assertTrue(service.contains("Process.killProcess(Process.myPid())"))
        assertTrue(service.contains("promoteLoadedModelToForeground()"))
        assertTrue(service.contains("ServiceCompat.startForeground("))
        assertTrue(service.contains("leaveLoadedModelForeground()"))
        assertTrue(service.contains("ServiceCompat.stopForeground("))
        assertTrue(runners.contains("put(LocalChatRuntime.LLAMA_CPP, llama)"))
        assertTrue(runners.contains("put(LocalChatRuntime.MNN_CPU, mnn)"))
        assertTrue(runners.contains("RemoteLocalChatRunner(context, LocalChatRuntime.GENIEX_QAIRT)"))
        assertTrue(runners.contains("put(LocalChatRuntime.GENIEX_QAIRT, qairt)"))
        assertTrue(service.contains("runtime == LocalChatRuntime.GENIEX_QAIRT"))
        assertTrue(client.contains("ILocalChatWorker.Stub.asInterface"))
        assertTrue(client.contains("RemoteLocalChatRunnerException"))
        assertTrue(aidl.contains("in ParcelFileDescriptor requestPayload"))
        assertTrue(client.contains("LocalChatWorkerRequestTransport.write"))
        assertFalse(runners.contains("defaultLocalChatRunners(context)"))
        assertTrue(runners.contains("defaultLocalChatRunner(LocalChatRuntime.GENIEX_LLAMA_CPP"))
        assertTrue(service.contains("defaultLocalChatRunner(runtime, applicationContext)"))
        assertTrue(service.contains("runners.getOrPut(runtime)"))
        assertTrue(transport.contains("MAX_MESSAGES_BYTES = 16 * 1024 * 1024"))
        assertTrue(transport.contains("MAX_PARAMS_BYTES = 1 * 1024 * 1024"))
        assertTrue(transport.contains("MAX_FIXED_SYSTEM_PROMPT_BYTES = 128 * 1024"))
        assertTrue(transport.contains("MAX_PAYLOAD_BYTES = 32 * 1024 * 1024"))
        assertTrue(client.contains("WORKER_SESSION_LOST_FIELD"))
        assertTrue(service.contains("LocalChatWorkerStageJournal.forContext"))
        assertTrue(service.contains("recordStageFailure(stage, diagnostic.code)"))
        assertTrue(service.contains("stage = \"prefill\""))
        assertTrue(service.contains("stage = \"load\""))
        assertTrue(service.contains("guarded(\"decode\", recordStage = recordDecodeStart)"))
        assertTrue(client.contains("workerFailure(\"process exited unexpectedly.\")"))
        assertTrue(client.contains("WORKER_STAGE_JOURNAL_FIELD"))
        assertTrue(client.contains("Last durable worker diagnostic"))
        assertTrue(aidl.contains("oneway void shutdown()"))
        assertTrue(service.contains("ConcurrentHashMap<Long, Runnable>"))
        assertTrue(service.contains("NATIVE_PREFILL_TIMEOUT_MS"))
        assertTrue(aidl.contains("void resetPrefillProgress()"))
        assertTrue(service.contains("override fun resetPrefillProgress()"))
        assertTrue(client.contains("service.resetPrefillProgress()"))
        assertTrue(
            client.indexOf("catch (error: DeadObjectException)") <
                client.indexOf("catch (error: RemoteException)")
        )
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

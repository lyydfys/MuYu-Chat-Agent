package com.muyuchat.mca

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal class SdxlTwoPhaseCoordinator(
    context: Context
) {
    private val appContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)
    private val stateLock = Any()
    private var activeClient: SdxlPhaseClient? = null

    fun cancel(): Boolean {
        cancelled.set(true)
        synchronized(stateLock) { activeClient }?.cancelAndTerminate()
        return true
    }

    suspend fun generate(
        requestId: String,
        bundleRoot: File,
        runtimeDirsJson: String,
        params: JSONObject,
        embeddingsFile: File,
        latentFile: File,
        metadataFile: File,
        outputFile: File,
        unetJournal: File,
        vaeJournal: File,
        onProgress: (LocalImageProgress) -> Unit
    ): String {
        cancelled.set(false)
        var mergedStages = emptyList<String>()
        fun report(envelope: SdxlImagePhaseProgress) {
            mergedStages = SdxlTwoPhaseJournal.merge(mergedStages, envelope)
            onProgress(
                envelope.progress.copy(
                    phase = "sdxl_${envelope.phase.wireName}:${envelope.progress.phase}",
                    message = "${envelope.phase.wireName.uppercase()} ${envelope.runtimeProfile} " +
                        "pid=${envelope.workerPid}: ${envelope.progress.message}",
                    stageTrace = mergedStages
                )
            )
        }
        cleanupHandoff(latentFile, metadataFile, outputFile, unetJournal, vaeJournal)
        try {
            val phaseRuntimeDirs = stageBothRuntimeProfiles(
                bundleRoot = bundleRoot
            )
            check(!cancelled.get()) { "SDXL generation was cancelled." }
            val unet = runPhase(
                phase = SdxlImagePhase.UNET,
                request = SdxlImagePhaseRequest(
                    requestId = requestId,
                    phase = SdxlImagePhase.UNET,
                    expectedHtpArch = SDXL_UNET_HTP_ARCH,
                    bundleRoot = bundleRoot.canonicalPath,
                    runtimeDirsJson = phaseRuntimeDirs.unetDirsJson,
                    paramsJson = params.toString(),
                    embeddingsPath = embeddingsFile.canonicalPath,
                    latentPath = latentFile.canonicalPath,
                    metadataPath = metadataFile.canonicalPath,
                    outputPath = "",
                    journalPath = unetJournal.canonicalPath
                ),
                timeoutMs = SDXL_UNET_PHASE_TIMEOUT_MS,
                onProgress = ::report
            )
            check(unet.processDeathConfirmed) { "UNet phase process did not exit before VAE admission." }
            val metadata = SdxlLatentArtifact.validate(
                requestId = requestId,
                latentFile = latentFile,
                metadataFile = metadataFile,
                expectedProducerArch = SDXL_UNET_HTP_ARCH
            )
            mergedStages = SdxlTwoPhaseJournal.appendBoundary(
                mergedStages,
                SdxlImagePhase.UNET,
                unet.result.workerPid,
                unet.result.runtimeProfile,
                "process_exit_confirmed"
            )
            check(!cancelled.get()) { "SDXL generation was cancelled." }
            val vae = runPhase(
                phase = SdxlImagePhase.VAE,
                request = SdxlImagePhaseRequest(
                    requestId = requestId,
                    phase = SdxlImagePhase.VAE,
                    expectedHtpArch = SDXL_VAE_HTP_ARCH,
                    bundleRoot = bundleRoot.canonicalPath,
                    runtimeDirsJson = phaseRuntimeDirs.vaeDirsJson,
                    paramsJson = params.toString(),
                    embeddingsPath = "",
                    latentPath = latentFile.canonicalPath,
                    metadataPath = metadataFile.canonicalPath,
                    outputPath = outputFile.canonicalPath,
                    journalPath = vaeJournal.canonicalPath
                ),
                timeoutMs = SDXL_VAE_PHASE_TIMEOUT_MS,
                onProgress = ::report
            )
            check(vae.processDeathConfirmed) { "VAE phase process did not exit after PNG publication." }
            check(outputFile.isFile && outputFile.length() > 0L) { "VAE phase output is missing." }
            mergedStages = SdxlTwoPhaseJournal.appendBoundary(
                mergedStages,
                SdxlImagePhase.VAE,
                vae.result.workerPid,
                vae.result.runtimeProfile,
                "process_exit_confirmed"
            )
            onProgress(
                LocalImageProgress(
                    phase = "sdxl_two_phase_completed",
                    message = "SDXL V75 UNet and V73 VAE completed in separate exited processes.",
                    step = 1,
                    steps = 1,
                    elapsedMs = 0L,
                    secondsPerStep = 0.0,
                    threads = 0,
                    width = 1024,
                    height = 1024,
                    cancelRequested = false,
                    stageTrace = mergedStages
                )
            )
            return JSONObject(vae.result.nativeResultJson)
                .put("runtimeSessionMode", "isolated_unet_v75_then_vae_v73")
                .put("unetWorkerPid", unet.result.workerPid)
                .put("unetRuntimeProfile", unet.result.runtimeProfile)
                .put("unetProcessDeathConfirmed", true)
                .put("vaeWorkerPid", vae.result.workerPid)
                .put("vaeRuntimeProfile", vae.result.runtimeProfile)
                .put("vaeProcessDeathConfirmed", true)
                .put("latentSha256", metadata.sha256)
                .put("stageTrace", org.json.JSONArray(mergedStages))
                .toString()
        } finally {
            runCatching { latentFile.delete() }
            runCatching { File(latentFile.path + ".part").delete() }
            runCatching { metadataFile.delete() }
            runCatching { File(metadataFile.path + ".part").delete() }
            runCatching { unetJournal.delete() }
            runCatching { File(unetJournal.path + ".tmp").delete() }
            runCatching { vaeJournal.delete() }
            runCatching { File(vaeJournal.path + ".tmp").delete() }
        }
    }

    private suspend fun runPhase(
        phase: SdxlImagePhase,
        request: SdxlImagePhaseRequest,
        timeoutMs: Long,
        onProgress: (SdxlImagePhaseProgress) -> Unit
    ): SdxlPhaseCompletion {
        val client = SdxlPhaseClient(appContext, phase, onProgress)
        synchronized(stateLock) { activeClient = client }
        return try {
            check(!cancelled.get()) { "SDXL generation was cancelled." }
            client.execute(request, timeoutMs)
        } finally {
            synchronized(stateLock) { if (activeClient === client) activeClient = null }
            client.close()
        }
    }

    private fun cleanupHandoff(vararg files: File) {
        files.forEach { file ->
            runCatching { file.delete() }
            runCatching { File(file.path + ".part").delete() }
            file.parentFile?.mkdirs()
        }
    }

    private fun stageBothRuntimeProfiles(
        bundleRoot: File
    ): SdxlPhaseRuntimeDirectories {
        val stager = QnnImageRuntimeStager(
            File(appContext.codeCacheDir, "qnn-image-runtime-sdxl-phases")
        )
        val device = DeviceProfileReader(appContext).read()
        val transportArch = DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(
            device.accelerationProfile.chipsetCode
        ) ?: device.accelerationProfile.qnnRuntime.htpArchVersion.takeIf { it > 0 }
        val transportProfile = transportArch?.let { arch ->
            qnnImageBundleRuntimeProfileForArchOrNull(bundleRoot, arch)
        }
        fun stage(arch: Int): String {
            val contextProfile = qnnImageBundleRuntimeProfileForArchOrNull(bundleRoot, arch)
                ?: error("SDXL bundle is missing a complete HTP V$arch runtime profile.")
            // Unknown devices or bundles without an exact transport profile
            // keep the execution path by using the context's coherent generic
            // transport. The real phase process is the compatibility test.
            val selectedTransport = transportProfile ?: contextProfile
            val result = if (selectedTransport.htpArchVersion == contextProfile.htpArchVersion) {
                stager.stage(contextProfile)
            } else {
                stager.stage(
                    QnnImageRuntimeStagePlan(
                        contextProfile = contextProfile,
                        transportProfile = selectedTransport
                    )
                )
            }
            require(!result.failed && result.runtime != null) {
                result.error ?: "Unable to stage the HTP V$arch runtime for real execution."
            }
            return requireNotNull(result.runtime).directory.canonicalPath
        }
        val unetDirectory = stage(SDXL_UNET_HTP_ARCH)
        val vaeDirectory = stage(SDXL_VAE_HTP_ARCH)
        // Each disposable process receives only its complete content-addressed
        // stage. Native code adds platform ADSP paths for DSP discovery, but no
        // APK/app-private QAIRT directory can be selected as a host fallback.
        return SdxlPhaseRuntimeDirectories(
            unetDirsJson = orderedSdxlRuntimeDirs(unetDirectory, emptyList()),
            vaeDirsJson = orderedSdxlRuntimeDirs(vaeDirectory, emptyList())
        )
    }
}

internal data class SdxlPhaseRuntimeDirectories(
    val unetDirsJson: String,
    val vaeDirsJson: String
)

@Suppress("UNUSED_PARAMETER")
internal fun orderedSdxlRuntimeDirs(primary: String, fallback: List<String>): String =
    JSONArray(listOf(File(primary).canonicalPath)).toString()

internal data class SdxlPhaseCompletion(
    val result: SdxlImagePhaseResult,
    val processDeathConfirmed: Boolean
)

internal object SdxlTwoPhaseJournal {
    fun merge(
        previous: List<String>,
        envelope: SdxlImagePhaseProgress
    ): List<String> {
        var merged = previous
        val prefix = "${envelope.phase.wireName}[pid=${envelope.workerPid},profile=${envelope.runtimeProfile}]"
        if (merged.none { it == "$prefix:worker_started" }) {
            merged = merged + "$prefix:worker_started"
        }
        envelope.progress.stageTrace.forEach { stage ->
            val tagged = "$prefix:$stage"
            if (tagged !in merged) merged = merged + tagged
        }
        return merged
    }

    fun appendBoundary(
        previous: List<String>,
        phase: SdxlImagePhase,
        pid: Int,
        profile: String,
        boundary: String
    ): List<String> {
        val tagged = "${phase.wireName}[pid=$pid,profile=$profile]:$boundary"
        return if (tagged in previous) previous else previous + tagged
    }
}

private class SdxlPhaseClient(
    private val context: Context,
    private val phase: SdxlImagePhase,
    private val onProgress: (SdxlImagePhaseProgress) -> Unit
) : AutoCloseable {
    private val serviceReady = CompletableDeferred<ISdxlImagePhaseWorker>()
    private val result = CompletableDeferred<SdxlImagePhaseResult>()
    private val processDeath = CompletableDeferred<Unit>()
    private var service: ISdxlImagePhaseWorker? = null
    private var binder: IBinder? = null
    private var workerPid: Int = -1
    private var bound = false
    private var requestId: String = ""

    private val deathRecipient = IBinder.DeathRecipient {
        processDeath.complete(Unit)
        if (!result.isCompleted) {
            result.completeExceptionally(
                LocalImageWorkerDisconnectedException("SDXL ${phase.wireName} phase process died before result publication.")
            )
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, connectedBinder: IBinder) {
            binder = connectedBinder
            service = ISdxlImagePhaseWorker.Stub.asInterface(connectedBinder)
            runCatching { connectedBinder.linkToDeath(deathRecipient, 0) }
                .onFailure { serviceReady.completeExceptionally(it) }
                .onSuccess { serviceReady.complete(requireNotNull(service)) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            processDeath.complete(Unit)
        }

        override fun onBindingDied(name: ComponentName) {
            processDeath.complete(Unit)
        }

        override fun onNullBinding(name: ComponentName) {
            serviceReady.completeExceptionally(
                LocalImageWorkerDisconnectedException("SDXL ${phase.wireName} phase returned a null binding.")
            )
        }
    }

    private val callback = object : ISdxlImagePhaseWorkerCallback.Stub() {
        override fun onProgress(payloadJson: String) {
            val envelope = runCatching { SdxlImagePhaseProtocol.parseProgress(payloadJson) }
                .getOrElse {
                    result.completeExceptionally(it)
                    return
                }
            if (envelope.requestId != requestId || envelope.phase != phase) {
                result.completeExceptionally(IllegalStateException("SDXL phase progress identity mismatch."))
                return
            }
            if (envelope.workerPid > 0) workerPid = envelope.workerPid
            onProgress(envelope)
        }

        override fun onComplete(payloadJson: String) {
            val envelope = runCatching { SdxlImagePhaseProtocol.parseResult(payloadJson) }
                .getOrElse {
                    result.completeExceptionally(it)
                    return
                }
            if (envelope.requestId != requestId || envelope.phase != phase) {
                result.completeExceptionally(IllegalStateException("SDXL phase result identity mismatch."))
                return
            }
            if (envelope.workerPid > 0) workerPid = envelope.workerPid
            result.complete(envelope)
        }

        override fun onError(payloadJson: String) {
            val envelope = runCatching { SdxlImagePhaseProtocol.parseError(payloadJson) }
                .getOrElse {
                    result.completeExceptionally(it)
                    return
                }
            if (envelope.workerPid > 0) workerPid = envelope.workerPid
            result.completeExceptionally(LocalImageWorkerRemoteException(envelope.code, envelope.message))
        }
    }

    suspend fun execute(request: SdxlImagePhaseRequest, timeoutMs: Long): SdxlPhaseCompletion {
        requestId = request.requestId
        val serviceClass = when (phase) {
            SdxlImagePhase.UNET -> SdxlUnetWorkerService::class.java
            SdxlImagePhase.VAE -> SdxlVaeWorkerService::class.java
        }
        bound = context.bindService(Intent(context, serviceClass), connection, Context.BIND_AUTO_CREATE)
        check(bound) { "Unable to bind SDXL ${phase.wireName} phase worker." }
        try {
            return withTimeout(timeoutMs) {
                val remote = serviceReady.await()
                check(remote.execute(SdxlImagePhaseProtocol.request(request), callback)) {
                    "SDXL ${phase.wireName} phase rejected the request."
                }
                val completed = result.await()
                require(completed.workerPid > 0) { "SDXL phase did not report a worker PID." }
                // The child deliberately exits instead of unloading QNN.  The
                // binding is released first so Android does not restart the
                // disposable service, then the parent confirms /proc death.
                releaseBindingForExit()
                withTimeout(SDXL_PHASE_EXIT_CONFIRM_TIMEOUT_MS) {
                    while (File("/proc/${completed.workerPid}").exists()) delay(25L)
                }
                processDeath.complete(Unit)
                SdxlPhaseCompletion(completed, true)
            }
        } catch (timeout: TimeoutCancellationException) {
            cancelAndTerminate()
            throw LocalImageWorkerRemoteException(
                code = "qnn_sdxl_${phase.wireName}_worker_timeout",
                message = "SDXL ${phase.wireName} phase exceeded ${timeoutMs / 1000L}s and its isolated worker was terminated."
            )
        }
    }

    fun cancelAndTerminate() {
        runCatching { service?.cancel(requestId) }
        if (workerPid > 0 && workerPid != Process.myPid()) {
            runCatching { Process.killProcess(workerPid) }
        }
    }

    private fun releaseBindingForExit() {
        if (!bound) return
        runCatching { context.unbindService(connection) }
        bound = false
    }

    override fun close() {
        binder?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
        releaseBindingForExit()
    }
}

internal const val SDXL_UNET_PHASE_TIMEOUT_MS = 4L * 60L * 1_000L
internal const val SDXL_VAE_PHASE_TIMEOUT_MS = 90L * 1_000L
private const val SDXL_PHASE_EXIT_CONFIRM_TIMEOUT_MS = 5L * 1_000L

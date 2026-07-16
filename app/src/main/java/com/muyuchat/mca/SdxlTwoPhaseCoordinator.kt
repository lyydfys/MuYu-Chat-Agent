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
                bundleRoot = bundleRoot,
                packagedRuntimeDirsJson = runtimeDirsJson
            )
            check(!cancelled.get()) { "SDXL generation was cancelled." }
            val unet = runPhase(
                phase = SdxlImagePhase.UNET,
                request = SdxlImagePhaseRequest(
                    requestId = requestId,
                    phase = SdxlImagePhase.UNET,
                    expectedHtpArch = SDXL_AUTO_TRANSPORT_HTP_ARCH,
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
            val unetNative = JSONObject(unet.result.nativeResultJson)
            val transportHtpArch = validateSdxlNativeTransport(
                phase = SdxlImagePhase.UNET,
                expectedHtpArch = SDXL_AUTO_TRANSPORT_HTP_ARCH,
                nativeResult = unetNative
            )
            val metadata = SdxlLatentArtifact.validate(
                requestId = requestId,
                latentFile = latentFile,
                metadataFile = metadataFile,
                expectedProducerArch = transportHtpArch
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
                    expectedHtpArch = transportHtpArch,
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
            val vaeNative = JSONObject(vae.result.nativeResultJson)
            val vaeTransportHtpArch = validateSdxlNativeTransport(
                phase = SdxlImagePhase.VAE,
                expectedHtpArch = transportHtpArch,
                nativeResult = vaeNative
            )
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
                    message = "SDXL UNet and VAE completed on HTP V$transportHtpArch in separate exited processes.",
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
            return vaeNative
                .put("backend", "qnn_htp")
                .put("npuActive", true)
                .put("qnnGraphExecution", true)
                .put("nativeExecution", true)
                .put("fallback", false)
                .put("executionStage", "sdxl_two_phase_passed")
                .put("runtimeSessionMode", "isolated_unet_then_vae_same_transport")
                .put("archiveContextHtpArch", SDXL_ARCHIVE_CONTEXT_HTP_ARCH)
                .put("transportHtpArch", transportHtpArch)
                .put("unetWorkerPid", unet.result.workerPid)
                .put("unetRuntimeProfile", unet.result.runtimeProfile)
                .put("unetProcessDeathConfirmed", true)
                .put("unetGraph", params.optString("graphName", "model"))
                .put("unetContextLoadMs", unetNative.optLong("unetContextLoadMs"))
                .put("unetExecuteMsTotal", unetNative.optLong("unetExecuteMsTotal"))
                .put("vaeWorkerPid", vae.result.workerPid)
                .put("vaeRuntimeProfile", vae.result.runtimeProfile)
                .put("vaeTransportHtpArch", vaeTransportHtpArch)
                .put("vaeProcessDeathConfirmed", true)
                .put("vaeGraph", params.optString("graphName", "model"))
                .put("steps", params.optInt("steps", 1))
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
        bundleRoot: File,
        packagedRuntimeDirsJson: String
    ): SdxlPhaseRuntimeDirectories {
        val bundleContextProfile = qnnImageBundleRuntimeProfileForArchOrNull(
            bundleRoot,
            SDXL_ARCHIVE_CONTEXT_HTP_ARCH
        )
        val isolatedRuntimeDirs = if (bundleContextProfile == null) {
            // Public SDXL archives contain graph contexts and prompt encoders,
            // but no host/Skel/Stub libraries. Use the APK's coherent runtime
            // selected for this device. Chipset detection only orders the
            // packaged transport; the real context load and graph execute in
            // each disposable worker remain the compatibility decision.
            isolatedSdxlPackagedRuntimeDirs(packagedRuntimeDirsJson)
        } else {
            val stager = QnnImageRuntimeStager(
                File(appContext.codeCacheDir, "qnn-image-runtime-sdxl-phases")
            )
            val device = DeviceProfileReader(appContext).read()
            val transportArch = DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(
                device.accelerationProfile.chipsetCode
            ) ?: device.accelerationProfile.qnnRuntime.htpArchVersion.takeIf { it > 0 }
            val selectedTransport = transportArch
                ?.let { arch -> qnnImageBundleRuntimeProfileForArchOrNull(bundleRoot, arch) }
                ?: bundleContextProfile
            val result = if (selectedTransport.htpArchVersion == bundleContextProfile.htpArchVersion) {
                stager.stage(bundleContextProfile)
            } else {
                stager.stage(
                    QnnImageRuntimeStagePlan(
                        contextProfile = bundleContextProfile,
                        transportProfile = selectedTransport
                    )
                )
            }
            require(!result.failed && result.runtime != null) {
                result.error ?: "Unable to stage the SDXL QNN runtime for real execution."
            }
            orderedSdxlRuntimeDirs(
                requireNotNull(result.runtime).directory.canonicalPath,
                emptyList()
            )
        }
        // Each disposable process receives only its complete content-addressed
        // runtime directory. Native code adds platform ADSP paths for DSP
        // discovery, but cannot mix a second host profile into either process.
        return SdxlPhaseRuntimeDirectories(
            unetDirsJson = isolatedRuntimeDirs,
            vaeDirsJson = isolatedRuntimeDirs
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

/**
 * Selects one complete APK/app-private QNN runtime for an isolated SDXL phase.
 * The directory may contain several physical-device transports; native context
 * metadata selects the compatible one and a real graph execute decides support.
 */
internal fun isolatedSdxlPackagedRuntimeDirs(runtimeDirsJson: String): String {
    val raw = JSONArray(runtimeDirsJson)
    val directories = buildList {
        for (index in 0 until raw.length()) {
            raw.optString(index)
                .takeIf(String::isNotBlank)
                ?.let(::File)
                ?.let { runCatching { it.canonicalFile }.getOrNull() }
                ?.takeIf(File::isDirectory)
                ?.let(::add)
        }
    }.distinctBy(File::getPath)
    val coherent = directories.firstOrNull(File::hasCoherentSdxlQnnRuntime)
        ?: error("The APK does not contain a complete QNN runtime for SDXL graph execution.")
    return orderedSdxlRuntimeDirs(coherent.path, emptyList())
}

private fun File.hasCoherentSdxlQnnRuntime(): Boolean {
    if (!File(this, "libQnnSystem.so").isFile || !File(this, "libQnnHtp.so").isFile) return false
    return listFiles().orEmpty().any { skel ->
        val arch = SDXL_HTP_SKEL.matchEntire(skel.name)?.groupValues?.getOrNull(1) ?: return@any false
        File(this, "libQnnHtpV${arch}Stub.so").isFile
    }
}

private val SDXL_HTP_SKEL = Regex("^libQnnHtpV(\\d+)Skel\\.so$")

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

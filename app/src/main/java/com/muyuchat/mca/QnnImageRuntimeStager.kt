package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.GregorianCalendar
import java.util.UUID

/**
 * Android 16's linker namespace rejects QNN shared objects under external
 * storage, even when the application owns that directory.  A downloaded image
 * bundle therefore cannot be passed to dlopen directly.  This stager copies
 * one internally coherent profile into code-cache storage before native code
 * is allowed to see it.
 *
 * The destination is content-addressed and immutable: all mandatory context
 * and transport files are hashed before copying, verified after copying, then
 * the completed staging directory is renamed into place atomically. A failed
 * stage intentionally returns no fallback directory, so an older APK runtime
 * cannot be mixed with the selected bundle's HTP profile.
 */
internal data class QnnImageStagedRuntimeFile(
    val name: String,
    val sizeBytes: Long,
    val sha256: String
)

internal data class QnnImageStagedRuntime(
    val directory: File,
    val sourceDirectory: File,
    val htpArchVersion: Int,
    val transportSourceDirectory: File = sourceDirectory,
    val transportHtpArchVersion: Int = htpArchVersion,
    val qnnSdkBuildId: String? = null,
    val fingerprint: String,
    val files: List<QnnImageStagedRuntimeFile>,
    val reused: Boolean
)

/**
 * A context binary may target an older HTP architecture than the physical
 * device transport.  Both profiles must be staged together from one QNN SDK;
 * otherwise HTP can fall back to an APK library and poison the phase process.
 */
internal data class QnnImageRuntimeStagePlan(
    val contextProfile: QnnImageBundleRuntimeProfile,
    val transportProfile: QnnImageBundleRuntimeProfile
)

internal data class QnnImageRuntimeStagingResult(
    val runtime: QnnImageStagedRuntime? = null,
    val error: String? = null
) {
    val failed: Boolean
        get() = error != null
}

/** Stages a complete bundle profile into the current application's code cache. */
internal fun stageQnnImageBundleRuntime(
    context: Context,
    bundleRoot: File?,
    requiredRuntimeProfile: LocalImageQnnRuntimeProfile? = null
): QnnImageRuntimeStagingResult {
    val profile = if (bundleRoot != null && requiredRuntimeProfile?.completeBundleRuntime == true) {
        val resolution = qnnRequiredBundleRuntimeResolution(bundleRoot, requiredRuntimeProfile)
        if (resolution.error != null) {
            return QnnImageRuntimeStagingResult(error = resolution.error)
        }
        resolution.runtimeProfile
    } else {
        bundleRoot?.let(::coherentQnnImageBundleRuntimeProfileOrNull)
    } ?: return QnnImageRuntimeStagingResult()
    val stager = QnnImageRuntimeStager(
        destinationRoot = File(context.applicationContext.codeCacheDir, QNN_IMAGE_RUNTIME_STAGE_DIRECTORY)
    )
    if (bundleRoot == null || requiredRuntimeProfile?.completeBundleRuntime != true) {
        return stager.stage(profile)
    }
    val device = runCatching { DeviceProfileReader(context.applicationContext).read() }.getOrNull()
    val chipsetCode = device?.let { profile ->
        profile.accelerationProfile.chipsetCode.ifBlank { profile.socModel }
    }.orEmpty()
    val deviceArch = DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(chipsetCode)
        ?: device?.accelerationProfile?.qnnRuntime?.htpArchVersion?.takeIf { it > 0 }
        ?: return stager.stage(profile)
    val transport = qnnImageBundleRuntimeProfileForArchOrNull(bundleRoot, deviceArch)
        // A device/profile comparison is a transport hint, never an admission
        // gate. If no exact physical transport is packaged, stage the verified
        // generic context and let the real NPU smoke decide.
        ?: return stager.stage(profile)
    return if (transport.htpArchVersion == profile.htpArchVersion) {
        stager.stage(profile)
    } else {
        stager.stage(QnnImageRuntimeStagePlan(contextProfile = profile, transportProfile = transport))
    }
}

/** Extracted for deterministic JVM tests; production callers use the Context wrapper above. */
internal class QnnImageRuntimeStager(
    private val destinationRoot: File
) {
    fun stage(profile: QnnImageBundleRuntimeProfile): QnnImageRuntimeStagingResult =
        stageInternal(
            contextProfile = profile,
            transportProfile = profile,
            requireSdkBuildIdentity = false
        )

    fun stage(plan: QnnImageRuntimeStagePlan): QnnImageRuntimeStagingResult =
        stageInternal(
            contextProfile = plan.contextProfile,
            transportProfile = plan.transportProfile,
            requireSdkBuildIdentity = true
        )

    private fun stageInternal(
        contextProfile: QnnImageBundleRuntimeProfile,
        transportProfile: QnnImageBundleRuntimeProfile,
        requireSdkBuildIdentity: Boolean
    ): QnnImageRuntimeStagingResult {
        var stagingDirectory: File? = null
        return runCatching {
            val qnnSdk = qnnSdkForRuntimeProfile(contextProfile)
            val sourceFiles = sourceFiles(contextProfile, transportProfile, qnnSdk)
            val sdkBuildId = if (requireSdkBuildIdentity) {
                verifyOneQnnSdkBuild(contextProfile, transportProfile, qnnSdk)
            } else {
                null
            }
            val fingerprint = runtimeFingerprint(
                contextHtpArchVersion = contextProfile.htpArchVersion,
                transportHtpArchVersion = transportProfile.htpArchVersion,
                qnnSdkBuildId = sdkBuildId,
                files = sourceFiles,
                schemaVersion = if (requireSdkBuildIdentity) 2 else 1
            )
            val destinationName = if (requireSdkBuildIdentity) {
                "v${contextProfile.htpArchVersion}-on-v${transportProfile.htpArchVersion}-$fingerprint"
            } else {
                "v${contextProfile.htpArchVersion}-$fingerprint"
            }
            val destination = File(destinationRoot, destinationName)
            if (verifyDestination(destination, sourceFiles)) {
                return@runCatching QnnImageRuntimeStagingResult(
                    runtime = stagedRuntime(
                        destination = destination,
                        contextProfile = contextProfile,
                        transportProfile = transportProfile,
                        qnnSdkBuildId = sdkBuildId,
                        fingerprint = fingerprint,
                        sourceFiles = sourceFiles,
                        reused = true
                    )
                )
            }
            require(!destination.exists()) {
                "Existing QNN runtime stage is incomplete or has a different digest: ${destination.absolutePath}. " +
                    "It was left untouched so this run cannot mix runtime profiles."
            }

            require(destinationRoot.exists() || destinationRoot.mkdirs()) {
                "Unable to create QNN runtime staging root: ${destinationRoot.absolutePath}"
            }
            stagingDirectory = File(
                destinationRoot,
                ".${destination.name}.staging-${UUID.randomUUID().toString().replace("-", "")}"
            )
            val stage = requireNotNull(stagingDirectory)
            require(stage.mkdirs()) { "Unable to create QNN runtime staging directory: ${stage.absolutePath}" }
            stage.setReadable(true, true)
            stage.setExecutable(true, true)

            sourceFiles.forEach { source -> copyAndVerify(source, stage) }
            require(verifyDestination(stage, sourceFiles)) {
                "QNN runtime staging verification failed before publish: ${stage.absolutePath}"
            }

            // The target name is content-addressed.  renameTo is therefore an
            // atomic publish on the same app-private filesystem.  If another
            // thread won the race, only reuse it after verifying every digest.
            var reusedExistingStage = false
            if (!stage.renameTo(destination)) {
                require(verifyDestination(destination, sourceFiles)) {
                    "Unable to atomically publish QNN runtime stage: ${destination.absolutePath}"
                }
                stage.deleteRecursively()
                reusedExistingStage = true
            }
            stagingDirectory = null
            QnnImageRuntimeStagingResult(
                runtime = stagedRuntime(
                    destination = destination,
                    contextProfile = contextProfile,
                    transportProfile = transportProfile,
                    qnnSdkBuildId = sdkBuildId,
                    fingerprint = fingerprint,
                    sourceFiles = sourceFiles,
                    reused = reusedExistingStage
                )
            )
        }.getOrElse {
            QnnImageRuntimeStagingResult(
                error = "无法安装 ${qnnPublicNpuRuntimeName(contextProfile.htpArchVersion)}。" +
                    "模型包中的 QNN 上下文与设备传输运行库不完整或版本不一致，请重新下载完整模型包。"
            )
        }.also {
            stagingDirectory?.deleteRecursively()
        }
    }

    private fun sourceFiles(
        contextProfile: QnnImageBundleRuntimeProfile,
        transportProfile: QnnImageBundleRuntimeProfile,
        qnnSdk: String?
    ): List<SourceRuntimeFile> {
        val selected = linkedMapOf<String, SourceRuntimeFile>()
        qnnImageRuntimeFileNames(contextProfile.htpArchVersion).forEach { name ->
            selected[name] = sourceFile(contextProfile, name)
        }
        qnnImageTransportRuntimeFileNames(transportProfile.htpArchVersion, qnnSdk).forEach { name ->
            selected.putIfAbsent(name, sourceFile(transportProfile, name))
        }
        return selected.values.toList()
    }

    private fun sourceFile(
        profile: QnnImageBundleRuntimeProfile,
        name: String
    ): SourceRuntimeFile {
        val sourceDirectory = profile.directory.canonicalFile
        val file = File(sourceDirectory, name).canonicalFile
        require(file.path.startsWith(sourceDirectory.path + File.separator)) {
            "Unsafe QNN runtime file path: $name"
        }
        require(file.isFile && file.length() > 0L) {
            "Missing required QNN runtime file: ${file.absolutePath}"
        }
        return SourceRuntimeFile(
            name = name,
            file = file,
            sizeBytes = file.length(),
            sha256 = file.sha256Contents()
        )
    }

    private fun verifyOneQnnSdkBuild(
        contextProfile: QnnImageBundleRuntimeProfile,
        transportProfile: QnnImageBundleRuntimeProfile,
        qnnSdk: String?
    ): String {
        val files = buildList {
            qnnImageRuntimeFileNames(contextProfile.htpArchVersion).forEach { name ->
                add(sourceFile(contextProfile, name).file)
            }
            (qnnImageRuntimeFileNames(transportProfile.htpArchVersion) +
                qnnImageTransportRuntimeFileNames(transportProfile.htpArchVersion, qnnSdk)).forEach { name ->
                add(sourceFile(transportProfile, name).file)
            }
        }.distinctBy { file -> file.canonicalPath }
        val identities = files.associateWith { file ->
            requireNotNull(file.qnnSdkBuildIdentityOrNull()) {
                "Unable to verify the QNN SDK build for ${file.absolutePath}."
            }
        }
        val timestamps = identities.values.map(QnnSdkBuildIdentity::timestamp).distinct()
        val versions = identities.values.mapNotNull(QnnSdkBuildIdentity::version).distinct()
        val serials = identities.values.mapNotNull(QnnSdkBuildIdentity::serial).distinct()
        require(
            timestamps.size == 1 &&
                timestamps.single().isValidQnnBuildTimestamp() &&
                versions.size == 1 &&
                serials.size <= 1
        ) {
            "QNN SDK build mismatch across host, context, and transport runtime files."
        }
        val expected = identities.values.firstOrNull { it.version != null }
            ?: requireNotNull(identities.values.firstOrNull())
        return expected.canonicalId
    }

    private fun copyAndVerify(source: SourceRuntimeFile, stage: File) {
        val partial = File(stage, ".${source.name}.part")
        val target = File(stage, source.name)
        source.file.inputStream().buffered().use { input ->
            FileOutputStream(partial).use { output ->
                input.copyTo(output, bufferSize = STAGING_BUFFER_BYTES)
                output.fd.sync()
            }
        }
        require(partial.length() == source.sizeBytes && partial.sha256Contents() == source.sha256) {
            "QNN runtime file changed or was copied incompletely: ${source.name}"
        }
        require(partial.renameTo(target)) { "Unable to finalize staged QNN runtime file: ${source.name}" }
        target.setReadable(true, true)
        target.setExecutable(true, true)
    }

    private fun verifyDestination(destination: File, sourceFiles: List<SourceRuntimeFile>): Boolean =
        destination.isDirectory &&
            destination.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet() ==
            sourceFiles.map { it.name }.toSet() &&
            sourceFiles.all { source ->
            val candidate = File(destination, source.name)
            candidate.isFile &&
                candidate.length() == source.sizeBytes &&
                runCatching { candidate.sha256Contents() == source.sha256 }.getOrDefault(false)
        }

    private fun stagedRuntime(
        destination: File,
        contextProfile: QnnImageBundleRuntimeProfile,
        transportProfile: QnnImageBundleRuntimeProfile,
        qnnSdkBuildId: String?,
        fingerprint: String,
        sourceFiles: List<SourceRuntimeFile>,
        reused: Boolean
    ): QnnImageStagedRuntime = QnnImageStagedRuntime(
        directory = destination,
        sourceDirectory = contextProfile.directory,
        htpArchVersion = contextProfile.htpArchVersion,
        transportSourceDirectory = transportProfile.directory,
        transportHtpArchVersion = transportProfile.htpArchVersion,
        qnnSdkBuildId = qnnSdkBuildId,
        fingerprint = fingerprint,
        files = sourceFiles.map { source ->
            QnnImageStagedRuntimeFile(source.name, source.sizeBytes, source.sha256)
        },
        reused = reused
    )

}

private data class SourceRuntimeFile(
    val name: String,
    val file: File,
    val sizeBytes: Long,
    val sha256: String
)

private fun runtimeFingerprint(
    contextHtpArchVersion: Int,
    transportHtpArchVersion: Int,
    qnnSdkBuildId: String?,
    files: List<SourceRuntimeFile>,
    schemaVersion: Int
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("mca.qnn.image.runtime.stage.v$schemaVersion\n".toByteArray(Charsets.UTF_8))
    digest.update("contextHtp=$contextHtpArchVersion\n".toByteArray(Charsets.UTF_8))
    digest.update("transportHtp=$transportHtpArchVersion\n".toByteArray(Charsets.UTF_8))
    qnnSdkBuildId?.let { digest.update("qnnSdkBuild=$it\n".toByteArray(Charsets.UTF_8)) }
    files.forEach { file ->
        digest.update("${file.name}\u0000${file.sizeBytes}\u0000${file.sha256}\n".toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private data class QnnSdkBuildIdentity(
    val version: String?,
    val timestamp: String,
    val serial: String?
) {
    val canonicalId: String
        get() = buildString {
            version?.let { append("v").append(it).append('.') }
            append(timestamp)
            serial?.let { append('_').append(it) }
        }
}

private fun File.qnnSdkBuildIdentityOrNull(): QnnSdkBuildIdentity? {
    val fullMatches = linkedSetOf<QnnSdkBuildIdentity>()
    val timestampMatches = linkedSetOf<QnnSdkBuildIdentity>()
    inputStream().buffered().use { input ->
        val buffer = ByteArray(QNN_BUILD_ID_SCAN_BYTES)
        var tail = ""
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val text = tail + String(buffer, 0, count, Charsets.ISO_8859_1)
            QNN_FULL_BUILD_ID.findAll(text).forEach { match ->
                fullMatches += QnnSdkBuildIdentity(
                    version = match.groupValues[1],
                    timestamp = match.groupValues[2],
                    serial = match.groupValues[3].ifBlank { null }
                )
            }
            QNN_BUILD_TIMESTAMP.findAll(text).forEach { match ->
                timestampMatches += QnnSdkBuildIdentity(
                    version = null,
                    timestamp = match.groupValues[1],
                    serial = match.groupValues[2].ifBlank { null }
                )
            }
            tail = text.takeLast(QNN_BUILD_ID_TAIL_CHARS)
        }
    }
    return fullMatches.singleOrNull() ?: timestampMatches.singleOrNull()
}

private fun String.isValidQnnBuildTimestamp(): Boolean {
    if (length != 12 || any { !it.isDigit() }) return false
    val year = 2000 + substring(0, 2).toInt()
    val month = substring(2, 4).toInt()
    val day = substring(4, 6).toInt()
    val hour = substring(6, 8).toInt()
    val minute = substring(8, 10).toInt()
    val second = substring(10, 12).toInt()
    return runCatching {
        val calendar = GregorianCalendar(year, month - 1, day, hour, minute, second)
        calendar.isLenient = false
        calendar.timeInMillis
    }.isSuccess
}

private fun File.sha256Contents(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(STAGING_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private const val QNN_IMAGE_RUNTIME_STAGE_DIRECTORY = "qnn-image-runtime"
private const val STAGING_BUFFER_BYTES = 128 * 1024
private const val QNN_BUILD_ID_SCAN_BYTES = 256 * 1024
private const val QNN_BUILD_ID_TAIL_CHARS = 128
private val QNN_FULL_BUILD_ID = Regex("""v(\d+\.\d+\.\d+)\.(\d{12})(?:_(\d+))?""")
private val QNN_BUILD_TIMESTAMP = Regex("""(?<!\d)(\d{12})(?:_(\d+))?(?!\d)""")

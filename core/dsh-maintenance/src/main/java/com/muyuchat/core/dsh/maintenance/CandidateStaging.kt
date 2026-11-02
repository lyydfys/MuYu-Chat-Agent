package com.muyuchat.core.dsh.maintenance

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/**
 * Debug/test-only materialiser for a staged D24 closure candidate.
 *
 * Production has no way to create `<dataRoot>/closure/incoming/<id>/candidate.json`: the real
 * candidate is promoted out of band by an installer or the engineering console. That makes
 * end-to-end verification of the candidate/trial path impossible without hand-crafting app-private
 * files, so this file adds a *typed builder* that materialises one candidate from the build's own
 * verified payload archive (`dsh/MANIFEST.json` + `dsh/payload.zip`):
 *
 * 1. the archive is streamed once and must match the manifest's declared size and SHA-256 - the
 *    same two facts the runtime installer trusts, so a tampered archive is refused, not copied;
 * 2. every component of [DSH_CLOSURE_COMPONENTS] gets a deterministic identity descriptor whose
 *    declared hash is computed from the bytes that were actually written;
 * 3. the tree is built in `<closure>/.staging-...` (outside `incoming`), atomically renamed into
 *    `incoming/<id>`, then re-verified through the production [DshClosureCandidateSource]; a
 *    rejected candidate is removed instead of being left behind as a half-staged trap.
 *
 * Gating (all of these are deliberate):
 * - the whole surface is behind the [DshCandidateStagingTestOnly] opt-in marker, so production code
 *   cannot call it without an explicit, reviewable opt-in;
 * - [DshCandidateStagingGate] can only be obtained by a JVM test or by a debuggable application
 *   process ([DshCandidateStagingGate.debugInstrumentation] returns null otherwise);
 * - a JVM-test gate is rejected inside a Dalvik/ART process ("Dalvik" VM), so the test gate cannot
 *   be smuggled into the installed app at runtime;
 * - the Android side lives in `app/src/debug`, which is not part of a release variant at all;
 * - nothing in the console, Local API, owner or agent tool surface accepts a staging request, so an
 *   agent/tool cannot trigger it - staging is a deliberate developer action.
 *
 * Identity descriptors are *not* executable payload bytes. The candidate therefore also carries a
 * real payload tree at `payload/`, extracted from the digest-verified archive, with its kernel
 * entry and critical-file hashes recorded in `candidate.json`. `DshClosureTrialHost` launches that
 * tree (never the installed payload), and `DshClosureCandidateSource` re-verifies its critical
 * bytes at the point of use.
 */
@RequiresOptIn(
    message = "Candidate staging is a debug/test-only capability; never wire it into production UI, API or agent tools",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY
)
annotation class DshCandidateStagingTestOnly

/** Where a permitted staging request came from. Informational; never a security boundary by itself. */
@DshCandidateStagingTestOnly
class DshCandidateStagingGate private constructor(val origin: Origin) {

    enum class Origin { JVM_TEST, DEBUG_INSTRUMENTATION }

    override fun toString(): String = "DshCandidateStagingGate(origin=$origin)"

    companion object {
        /** The only gate a plain JVM test may use. */
        @DshCandidateStagingTestOnly
        fun jvmTest(): DshCandidateStagingGate = DshCandidateStagingGate(Origin.JVM_TEST)

        /**
         * The only gate an instrumentation process may use. [applicationDebuggable] must be the
         * real `ApplicationInfo.FLAG_DEBUGGABLE` observation; a release APK answers false and the
         * caller receives null instead of a gate.
         */
        @DshCandidateStagingTestOnly
        fun debugInstrumentation(applicationDebuggable: Boolean): DshCandidateStagingGate? =
            if (applicationDebuggable) {
                DshCandidateStagingGate(Origin.DEBUG_INSTRUMENTATION)
            } else {
                null
            }
    }
}

/** The packaged manifest plus a repeatable opener for the packaged payload archive asset. */
@DshCandidateStagingTestOnly
data class DshPayloadArchiveSpec(
    val manifestJson: String,
    val openArchive: () -> InputStream?
)

/** Facts the caller declares about the candidate; staging verifies the archive, not these. */
@DshCandidateStagingTestOnly
data class DshCandidateStageRequest(
    val candidateId: String,
    val targetAbi: String,
    val dataSchemaVersion: Int,
    val minReadableDataSchema: Int,
    val migratesData: Boolean,
    val nativeLibraryReplaced: Boolean,
    val prePackagedSlotVerified: Boolean,
    val abiCompatibleWithPrevious: Boolean,
    val archive: DshPayloadArchiveSpec
)

/** Typed outcome; only [Staged] means exactly one candidate was materialised and re-verified. */
@DshCandidateStagingTestOnly
sealed interface DshCandidateStageResult {
    data class Staged(
        val candidateId: String,
        val stagedRoot: File,
        val fingerprint: String,
        val componentHashes: Map<String, String>,
        val archiveSha256: String,
        val archiveBytes: Long
    ) : DshCandidateStageResult

    data class Refused(val failure: DshMaintenanceFailure) : DshCandidateStageResult
}

/**
 * Builds exactly one candidate below [dataRoot]/closure/incoming and never touches the live home,
 * workspace or payload tree. The constructor rejects an unusable root immediately; [stage] keeps
 * the same discipline for every request.
 */
@DshCandidateStagingTestOnly
class DshCandidateStager(
    private val gate: DshCandidateStagingGate,
    private val dataRoot: File,
    private val supportedAbis: Set<String>,
    private val protectedRoots: List<File> = emptyList(),
    private val maxArchiveBytes: Long = MAX_ARCHIVE_BYTES,
    /** The closure the selector serves today; never counted as a second staged candidate. */
    private val activeRoot: () -> File? = { null }
) {
    private val closureRoot = File(dataRoot, "closure")
    private val incomingDir = File(closureRoot, "incoming")

    init {
        require(gate.origin == DshCandidateStagingGate.Origin.JVM_TEST ||
            gate.origin == DshCandidateStagingGate.Origin.DEBUG_INSTRUMENTATION) {
            "dsh: candidate staging requires a test or debug gate"
        }
        require(dataRoot.isAbsolute) { "dsh: the managed data root must be absolute" }
        // A JVM-test gate must never work inside a real Android app process: only the debuggable
        // instrumentation bridge may stage there. This keeps a stray production caller from turning
        // the test gate into a runtime capability.
        check(!(gate.origin == DshCandidateStagingGate.Origin.JVM_TEST && isAndroidRuntime())) {
            "dsh: a JVM-test staging gate cannot materialise a candidate inside the app process"
        }
    }

    fun stage(request: DshCandidateStageRequest): DshCandidateStageResult {
        requestProblem(request)?.let { return refuse(it.first, it.second) }
        val target = File(incomingDir, request.candidateId)
        if (DshMaintenanceIo.relativeWithin(closureRoot, target) == null) {
            return refuse(DshMaintenanceError.PATH_ESCAPE, "candidate staging escapes the closure root")
        }
        if (protectedRoots.any { DshMaintenanceIo.relativeWithin(it, target) != null }) {
            return refuse(DshMaintenanceError.PATH_ESCAPE, "a candidate may not be staged inside live user data")
        }
        if (target.exists()) {
            return refuse(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "a candidate with this id is already staged; remove it before staging again"
            )
        }
        val archive = when (val verified = verifyArchive(request.archive)) {
            is ArchiveVerification.Refused -> return DshCandidateStageResult.Refused(verified.failure)
            is ArchiveVerification.Verified -> verified.facts
        }
        // Build outside `incoming` so a crash between writing candidate.json and the atomic rename
        // can never leave a half-promoted directory that the candidate source would count.
        val staging = File(closureRoot, ".staging-${request.candidateId}-${UUID.randomUUID()}")
        val written = runCatching { materialise(request, archive, staging) }.getOrElse {
            DshMaintenanceIo.deleteOwnedTree(staging)
            return refuse(
                DshMaintenanceError.IO_FAILURE,
                "candidate staging could not be written",
                // The exception class is enough to tell a full disk (IOException) from a
                // platform path rejection; no path or message is projected.
                it.javaClass.simpleName
            )
        }
        val promoted = runCatching {
            DshMaintenanceIo.moveDurably(staging, target)
            target.isDirectory
        }.getOrDefault(false)
        if (!promoted) {
            DshMaintenanceIo.deleteOwnedTree(staging)
            if (target.isDirectory) DshMaintenanceIo.deleteOwnedTree(target)
            return refuse(DshMaintenanceError.IO_FAILURE, "candidate staging could not be promoted atomically")
        }
        val lookup = DshClosureCandidateSource(
            closureRoot = closureRoot,
            supportedAbis = supportedAbis,
            protectedRoots = protectedRoots,
            activeRoot = activeRoot
        ).lookup()
        val available = lookup as? DshClosureCandidateLookup.Available
        if (available == null || available.candidate.candidateId != request.candidateId) {
            DshMaintenanceIo.deleteOwnedTree(target)
            val failure = (lookup as? DshClosureCandidateLookup.Rejected)?.failure
                ?: DshMaintenanceFailure(
                    DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                    "the staged candidate did not verify as the only staged candidate"
                )
            return DshCandidateStageResult.Refused(failure)
        }
        return DshCandidateStageResult.Staged(
            candidateId = request.candidateId,
            stagedRoot = target,
            fingerprint = available.candidate.identity().fingerprint,
            componentHashes = written,
            archiveSha256 = archive.sha256,
            archiveBytes = archive.bytes
        )
    }

    // ------------------------------------------------------------ request checks

    private fun requestProblem(request: DshCandidateStageRequest): Pair<DshMaintenanceError, String>? {
        if (!isToken(request.candidateId)) {
            return DshMaintenanceError.UPGRADE_INCOMPATIBLE to "candidate id is not a stable token"
        }
        if (request.targetAbi !in supportedAbis) {
            return DshMaintenanceError.UPGRADE_INCOMPATIBLE to "candidate ABI is not supported by this build"
        }
        if (request.dataSchemaVersion < 1) {
            return DshMaintenanceError.UPGRADE_INCOMPATIBLE to "candidate does not declare a data schema"
        }
        if (request.minReadableDataSchema < 0 || request.minReadableDataSchema > request.dataSchemaVersion) {
            return DshMaintenanceError.UPGRADE_INCOMPATIBLE to "candidate data schema bounds are incoherent"
        }
        if (request.migratesData && request.dataSchemaVersion <= request.minReadableDataSchema) {
            return DshMaintenanceError.UPGRADE_INCOMPATIBLE to "a migrating candidate must declare a real schema step"
        }
        return null
    }

    // ------------------------------------------------------------ archive proof

    private sealed interface ArchiveVerification {
        data class Verified(val facts: DshCandidateArchiveFacts) : ArchiveVerification
        data class Refused(val failure: DshMaintenanceFailure) : ArchiveVerification
    }

    private fun verifyArchive(spec: DshPayloadArchiveSpec): ArchiveVerification {
        val manifest = runCatching { JSONObject(spec.manifestJson) }.getOrNull()
            ?: return refused(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "the payload manifest is not valid JSON")
        val archive = manifest.optJSONObject("archive")
            ?: return refused(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "the payload manifest declares no archive")
        val declaredSha = archive.optString("sha256", "").lowercase()
        if (!DshMaintenanceIo.isSha256Hex(declaredSha)) {
            return refused(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "the payload manifest declares no archive sha256")
        }
        val declaredBytes = archive.optLong("size", 0L)
        val stream = try {
            spec.openArchive()
        } catch (io: java.io.IOException) {
            // A provider that throws is a real I/O failure, not an absent archive; keeping the two
            // distinguishable matters for diagnostics and for the caller's retry decision.
            return refused(DshMaintenanceError.IO_FAILURE, "the packaged payload archive could not be opened")
        } ?: return refused(DshMaintenanceError.STAGING_MISSING, "the packaged payload archive is unavailable")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var exceeded = false
        val read = runCatching {
            stream.use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxArchiveBytes) {
                        exceeded = true
                        return@use false
                    }
                    digest.update(buffer, 0, count)
                }
                true
            }
        }.getOrDefault(false)
        if (!read) {
            return refused(
                DshMaintenanceError.IO_FAILURE,
                if (exceeded) {
                    "the payload archive exceeds the staging budget"
                } else {
                    "the packaged payload archive could not be read"
                }
            )
        }
        if (total <= 0L) return refused(DshMaintenanceError.STAGING_MISSING, "the packaged payload archive is empty")
        if (declaredBytes > 0L && declaredBytes != total) {
            return refused(
                DshMaintenanceError.ARCHIVE_INVALID,
                "the payload archive does not match its declared size",
                "declared=$declaredBytes actual=$total"
            )
        }
        val actualSha = hex(digest.digest())
        if (!actualSha.equals(declaredSha, ignoreCase = false)) {
            return refused(
                DshMaintenanceError.ARCHIVE_INVALID,
                "the payload archive does not match its declared sha256",
                "declared=${declaredSha.take(12)} actual=${actualSha.take(12)}"
            )
        }
        val node = manifest.optJSONObject("node")
        return ArchiveVerification.Verified(
            DshCandidateArchiveFacts(
                sha256 = actualSha,
                bytes = total,
                payloadVersion = manifest.optString("payloadVersion", ""),
                officialCommit = manifest.optJSONObject("source")?.optString("officialCommit", "").orEmpty(),
                fileCount = manifest.optLong("fileCount", 0L),
                kernelEntry = manifest.optString("kernelEntry", ""),
                nodeJniLib = node?.optString("jniLib", "").orEmpty(),
                nodeSha256 = node?.optString("sha256", "").orEmpty(),
                nodeBytes = node?.optLong("size", 0L) ?: 0L,
                criticalHashes = criticalHashes(manifest)
            )
        )
    }

    private fun criticalHashes(manifest: JSONObject): Map<String, String> {
        val files = manifest.optJSONArray("criticalFiles") ?: return emptyMap()
        val hashes = LinkedHashMap<String, String>()
        for (index in 0 until files.length()) {
            val entry = files.optJSONObject(index) ?: continue
            val path = entry.optString("path", "")
            val sha = entry.optString("sha256", "").lowercase()
            if (!DshMaintenanceIo.isSafeRelativePath(path) || !DshMaintenanceIo.isSha256Hex(sha)) continue
            if (!hashes.containsKey(path)) hashes[path] = sha
        }
        return hashes
    }

    // ------------------------------------------------------------ materialisation

    private fun materialise(
        request: DshCandidateStageRequest,
        archive: DshCandidateArchiveFacts,
        staging: File
    ): Map<String, String> {
        if (!staging.isDirectory && !staging.mkdirs() && !staging.isDirectory) {
            throw java.io.IOException("cannot create the candidate staging directory")
        }
        // The executable half: the candidate owns a real payload tree extracted from the archive
        // whose digest was verified above. A descriptor-only candidate cannot be launched, so
        // staging it would only produce a trial that proves nothing.
        val payloadDir = File(staging, DshUpgradeCandidate.PAYLOAD_DIR_NAME)
        val extracted = extractPayload(request, payloadDir)
            ?: throw java.io.IOException("the candidate payload could not be extracted")
        if (archive.fileCount > 0L && extracted != archive.fileCount) {
            throw java.io.IOException("candidate payload file count $extracted != manifest ${archive.fileCount}")
        }
        if (archive.kernelEntry.isEmpty() ||
            !DshMaintenanceIo.isSafeRelativePath(archive.kernelEntry) ||
            !File(payloadDir, archive.kernelEntry).isFile
        ) {
            throw java.io.IOException("the candidate payload kernel entry is missing")
        }
        if (archive.criticalHashes.isEmpty()) {
            throw java.io.IOException("the payload manifest declares no critical files")
        }
        val criticalFiles = org.json.JSONArray()
        for ((path, expected) in archive.criticalHashes.toSortedMap()) {
            val file = File(payloadDir, path)
            if (!file.isFile || !DshMaintenanceIo.sha256(file).equals(expected, ignoreCase = false)) {
                throw java.io.IOException("candidate payload critical file does not match its manifest")
            }
            criticalFiles.put(JSONObject().put("path", path).put("sha256", expected))
        }
        val artifacts = JSONObject()
        val declared = JSONObject()
        val hashes = LinkedHashMap<String, String>()
        for (component in DSH_CLOSURE_COMPONENTS.sorted()) {
            val relative = "identity/$component.json"
            val file = DshMaintenanceIo.resolveWithin(staging, relative)
                ?: throw java.io.IOException("unsafe descriptor path")
            val bytes = DshCandidateIdentityDescriptors.render(request, archive, component)
            DshMaintenanceIo.writeDurably(file, bytes)
            val sha = DshMaintenanceIo.sha256(file)
            if (bytes.isEmpty() || sha.length != 64) throw java.io.IOException("descriptor was not written")
            artifacts.put(component, relative)
            declared.put(component, sha)
            hashes[component] = sha
        }
        val payload = JSONObject()
            .put("root", DshUpgradeCandidate.PAYLOAD_DIR_NAME)
            .put("kernelEntry", archive.kernelEntry)
            .put("archiveSha256", archive.sha256)
            .put("bytes", archive.bytes)
            .put("fileCount", archive.fileCount)
            .put("criticalFiles", criticalFiles)
        val manifest = JSONObject()
            .put("candidateId", request.candidateId)
            .put("targetAbi", request.targetAbi)
            .put("dataSchemaVersion", request.dataSchemaVersion)
            .put("minReadableDataSchema", request.minReadableDataSchema)
            .put("migratesData", request.migratesData)
            .put("nativeLibraryReplaced", request.nativeLibraryReplaced)
            .put("prePackagedSlotVerified", request.prePackagedSlotVerified)
            .put("abiCompatibleWithPrevious", request.abiCompatibleWithPrevious)
            .put("artifacts", artifacts)
            .put("hashes", declared)
            .put("payload", payload)
        DshMaintenanceIo.writeDurably(File(staging, MANIFEST_NAME), manifest.toString())
        return hashes
    }

    /**
     * Streams the (already digest-verified) archive into `staging/payload`.
     *
     * Every entry is confined to the payload root, and both the file count and the total expanded
     * bytes are bounded, so a zip bomb or a traversal entry is refused instead of written.
     *
     * @return the number of regular files written, or null when an entry was unsafe or unreadable.
     */
    private fun extractPayload(request: DshCandidateStageRequest, payloadDir: File): Long? {
        if (!payloadDir.isDirectory && !payloadDir.mkdirs() && !payloadDir.isDirectory) return null
        val stream = runCatching { request.archive.openArchive() }.getOrNull() ?: return null
        var files = 0L
        var total = 0L
        return runCatching {
            stream.use { raw ->
                java.util.zip.ZipInputStream(raw).use zipScan@{ zip ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        if (entry.isDirectory) {
                            // The safety predicate rejects a trailing separator, so the entry name
                            // is normalised before it is resolved; a raw "bin/" would abort every
                            // archive that carries directory entries.
                            val relative = name.trimEnd('/')
                            if (!DshMaintenanceIo.isSafeRelativePath(relative)) return@zipScan null
                            val dir = DshMaintenanceIo.resolveWithin(payloadDir, relative)
                                ?: return@zipScan null
                            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                                return@zipScan null
                            }
                            continue
                        }
                        if (!DshMaintenanceIo.isSafeRelativePath(name)) return@zipScan null
                        val target = DshMaintenanceIo.resolveWithin(payloadDir, name) ?: return@zipScan null
                        target.parentFile?.let { parent ->
                            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                                return@zipScan null
                            }
                        }
                        files += 1
                        if (files > MAX_PAYLOAD_FILES) return@zipScan null
                        java.io.FileOutputStream(target).use { output ->
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > maxArchiveBytes) return@zipScan null
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    files
                }
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------ helpers

    private fun refuse(code: DshMaintenanceError, message: String, detail: String? = null) =
        DshCandidateStageResult.Refused(
            DshMaintenanceFailure(code, message, retryable = false, detail = detail?.take(200))
        )

    private fun refused(code: DshMaintenanceError, message: String, detail: String? = null) =
        ArchiveVerification.Refused(
            DshMaintenanceFailure(code, message, retryable = false, detail = detail?.take(200))
        )

    private fun isToken(value: String): Boolean =
        value.length in 1..64 && value.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-'
        }

    private fun isAndroidRuntime(): Boolean {
        val vmName = System.getProperty("java.vm.name").orEmpty()
        val runtimeName = System.getProperty("java.runtime.name").orEmpty()
        return vmName.contains("Dalvik", ignoreCase = true) ||
            runtimeName.contains("Android", ignoreCase = true)
    }

    private fun hex(bytes: ByteArray): String {
        val chars = "0123456789abcdef".toCharArray()
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            out[index * 2] = chars[value ushr 4]
            out[index * 2 + 1] = chars[value and 0x0f]
        }
        return String(out)
    }

    private companion object {
        const val MANIFEST_NAME = "candidate.json"
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_ARCHIVE_BYTES = 2L * 1024L * 1024L * 1024L
        const val MAX_PAYLOAD_FILES = 200_000L
    }
}

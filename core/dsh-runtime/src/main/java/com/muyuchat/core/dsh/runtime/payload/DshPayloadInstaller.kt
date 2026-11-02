package com.muyuchat.core.dsh.runtime.payload

import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Supplies the packaged payload. Production reads APK assets; tests read byte arrays.
 * Returning null means the asset is absent (a documented NOT_RUN staging state), which the
 * host must report as PAYLOAD_MISSING rather than starting an unverified tree.
 */
interface DshPayloadSource {
    val description: String
    fun openManifest(): InputStream?
    fun openArchive(): InputStream?
}

/** Outcome of ensuring a verified payload tree on disk. */
sealed interface DshPayloadInstallResult {
    data class Installed(
        val manifest: DshPayloadManifest,
        val archiveSha256: String,
        val reused: Boolean,
        /**
         * Honest durability/platform notes of this install (e.g. a host that cannot fsync a
         * directory). Empty on a plain Android install.
         */
        val notes: List<String> = emptyList()
    ) : DshPayloadInstallResult

    /** The APK carries no payload (staging was not run for this build). */
    data class Missing(val detail: String) : DshPayloadInstallResult

    /** The payload exists but its manifest/hash verification failed. Fails closed. */
    data class Integrity(val detail: String) : DshPayloadInstallResult
}

/**
 * Installs the official payload from APK assets.
 *
 * Cross-process protocol (C02a/C02b):
 *
 * 1. take the real OS exclusive lock over [DshRuntimeConfig.payloadInstallLockFile]
 *    ([DshPayloadLock], `FileChannel.tryLock`; the kernel releases it on close/crash);
 * 2. under the lock: reuse a valid verified tree, validate the destination shape (no symlink,
 *    no non-directory target), clean aged stale artifacts, create a **unique reservation** and
 *    an empty unique staging directory, then release the lock;
 * 3. do the long archive streaming/extraction/critical-file verification **outside** the lock;
 * 4. re-take the lock and re-check reuse (a concurrent installer may have won). Only the lock
 *    holder may move the previous verified tree aside and atomically `ATOMIC_MOVE` the staged
 *    tree into the fixed destination; a second `exists` race or any non-atomic move fails closed;
 * 5. publish the marker with a hard-link no-replace primitive (a fresh marker is only created
 *    after the payload tree is in place), then delete the previous tree/reservation/staging.
 *
 * A crash before step 5 leaves no marker, so the next run re-verifies/reinstalls instead of
 * reporting a fake success. Artifacts from crashed runs are cleaned when older than
 * [STALE_ARTIFACT_MILLIS] (young directories may belong to a concurrent installer that is still
 * extracting outside the lock).
 */
class DshPayloadInstaller(
    private val config: DshRuntimeConfig,
    private val source: DshPayloadSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val digestProvider: () -> MessageDigest = { MessageDigest.getInstance("SHA-256") },
    private val lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS,
    private val lockPollMillis: Long = DEFAULT_LOCK_POLL_MILLIS,
    private val staleArtifactMillis: Long = STALE_ARTIFACT_MILLIS,
    /**
     * Atomic no-replace publication + durability seam (D10). The production default uses real
     * `mkdir`/`rename(ATOMIC_MOVE)`/`fsync`; tests inject failures to prove every fail-closed path.
     */
    private val publisher: DshDirectoryPublisher = DshDirectoryPublisher()
) : DshPayloadProvisioner {
    private val hex = "0123456789abcdef".toCharArray()

    override fun ensureInstalled(): DshPayloadInstallResult {
        config.validated()
        val manifest = try {
            val stream = source.openManifest()
                ?: return DshPayloadInstallResult.Missing(
                    "no manifest asset at ${config.manifestAsset} (${source.description})"
                )
            stream.use { DshPayloadManifest.parse(it.readBytes().toString(Charsets.UTF_8)) }
        } catch (t: Throwable) {
            return DshPayloadInstallResult.Integrity(
                "manifest unreadable: " + describe(t)
            )
        }
        if (manifest.archiveAsset != config.payloadAsset) {
            return DshPayloadInstallResult.Integrity(
                "manifest archive ${manifest.archiveAsset} does not match configured ${config.payloadAsset}"
            )
        }
        val root = config.dataRoot
        if (!root.isDirectory && !root.mkdirs()) {
            return DshPayloadInstallResult.Integrity("cannot create data root ${root.path}")
        }

        var reservationId: String? = null
        var staging: File? = null
        var archiveTmp: File? = null
        val notes = mutableListOf<String>()
        try {
            // ---- phase 1: reservation + quick reuse check under the real cross-process lock.
            val first = DshPayloadLock.acquire(config.payloadInstallLockFile, lockTimeoutMillis, lockPollMillis)
            val firstLock = (first as? DshPayloadLockOutcome.Acquired)?.lock
                ?: return lockFailure(first)
            firstLock.use {
                reuse(manifest)?.let { return it }
                stateError()?.let { return DshPayloadInstallResult.Integrity(it) }
                cleanupStale(root)
                val id = newReservationId()
                reservationId = id
                val stagingDir = File(root, "$STAGING_PREFIX$id")
                if (!stagingDir.mkdirs()) {
                    deleteRecursively(stagingDir)
                    return DshPayloadInstallResult.Integrity(
                        "cannot create payload staging directory ${stagingDir.path}"
                    )
                }
                staging = stagingDir
                archiveTmp = File(root, "$ARCHIVE_PREFIX$id.tmp")
                if (!writeReservation(root, id, manifest)) {
                    deleteRecursively(stagingDir)
                    return DshPayloadInstallResult.Integrity("cannot write payload install reservation")
                }
            }

            // ---- phase 2: long streaming/extraction/verification outside the lock.
            val staged = staging
            val archivedFile = archiveTmp
            if (staged == null || archivedFile == null) {
                cleanupOwn(staging, archiveTmp, reservationId)
                return DshPayloadInstallResult.Integrity("payload staging reservation invariant failed")
            }
            val prepared = prepareStaging(manifest, staged, archivedFile, notes)
            if (prepared != null) {
                cleanupOwn(staging, archiveTmp, reservationId)
                return prepared
            }

            // ---- phase 3: atomic publish under the lock (loser reuses the winner's tree).
            val second = DshPayloadLock.acquire(config.payloadInstallLockFile, lockTimeoutMillis, lockPollMillis)
            val secondLock = (second as? DshPayloadLockOutcome.Acquired)?.lock
                ?: return lockFailure(second, staging, archiveTmp, reservationId)
            secondLock.use {
                reuse(manifest)?.let { installed ->
                    cleanupOwn(staging, archiveTmp, reservationId)
                    return installed
                }
                stateError()?.let { detail ->
                    cleanupOwn(staging, archiveTmp, reservationId)
                    return DshPayloadInstallResult.Integrity(detail)
                }
                if (!reservationFile(root, reservationId!!).isFile) {
                    cleanupOwn(staging, archiveTmp, reservationId)
                    return DshPayloadInstallResult.Integrity(
                        "payload install reservation $reservationId disappeared before commit"
                    )
                }
                val previous = File(root, "$OLD_PREFIX$reservationId")
                deleteRecursively(previous)
                if (existsNoFollow(config.payloadDir)) {
                    try {
                        Files.move(
                            config.payloadDir.toPath(),
                            previous.toPath(),
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (t: Throwable) {
                        cleanupOwn(staging, archiveTmp, reservationId)
                        return DshPayloadInstallResult.Integrity(
                            "cannot move previous payload aside: " + describe(t)
                        )
                    }
                }
                // No stat-then-rename: claim the destination with a raw no-replace mkdir and then
                // rename the staged tree over the empty claim this protocol owns. A foreign
                // directory (or symlink) appearing now makes the mkdir fail, so user data can
                // never be silently replaced.
                when (val claim = publisher.claimTarget(config.payloadDir)) {
                    is DshTargetClaim.Claimed -> Unit
                    is DshTargetClaim.AlreadyPresent -> {
                        restore(previous)
                        cleanupOwn(staging, archiveTmp, reservationId)
                        return DshPayloadInstallResult.Integrity(
                            "payload target appeared during commit; refusing to overwrite it"
                        )
                    }
                    is DshTargetClaim.Failed -> {
                        restore(previous)
                        cleanupOwn(staging, archiveTmp, reservationId)
                        return DshPayloadInstallResult.Integrity(claim.detail)
                    }
                }
                when (val published = publisher.publishOntoClaim(staged, config.payloadDir)) {
                    is DshDirectoryPublish.Published -> notes.addAll(published.notes)
                    is DshDirectoryPublish.NotPublished -> {
                        publisher.releaseClaim(config.payloadDir)
                        restore(previous)
                        cleanupOwn(staging, archiveTmp, reservationId)
                        return DshPayloadInstallResult.Integrity(
                            "atomic payload publish failed: ${published.detail}"
                        )
                    }
                }
                // The rename must be durable BEFORE a marker may claim a verified tree; otherwise a
                // power loss could leave a marker over a tree with unflushed content.
                val renameDurability = publisher.syncDirectory(root)
                if (renameDurability is DshSyncOutcome.Failed) {
                    deleteRecursively(config.payloadDir)
                    restore(previous)
                    cleanupOwn(staging, archiveTmp, reservationId)
                    return DshPayloadInstallResult.Integrity(
                        "published payload is not durable: ${renameDurability.detail}"
                    )
                }
                if (renameDurability is DshSyncOutcome.Unsupported) {
                    notes += "payload directory fsync unsupported: ${renameDurability.detail}"
                }

                val marker = DshPayloadMarker(
                    manifestVersion = manifest.manifestVersion,
                    payloadVersion = manifest.payloadVersion,
                    archiveSha256 = manifest.archiveSha256,
                    fileCount = manifest.fileCount,
                    installedAtMillis = clock()
                )
                val markerError = writeMarkerNoReplace(marker)
                if (markerError != null) {
                    // No marker means no fake success. Remove the just-published tree and put the
                    // previous verified tree back before reporting the failure.
                    runCatching {
                        Files.move(
                            config.payloadDir.toPath(),
                            staged.toPath(),
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    }
                    restore(previous)
                    cleanupOwn(staging, archiveTmp, reservationId)
                    return DshPayloadInstallResult.Integrity(
                        "verified payload installed but marker publish failed: $markerError"
                    )
                }
                // Persist the marker directory entry; a crash here only loses the marker, which is
                // an honest "reinstall" on the next start, never a corrupt success.
                when (val markerDurability = publisher.syncDirectory(root)) {
                    is DshSyncOutcome.Failed ->
                        notes += "marker directory fsync failed: ${markerDurability.detail}"
                    is DshSyncOutcome.Unsupported ->
                        notes += "marker directory fsync unsupported: ${markerDurability.detail}"
                    is DshSyncOutcome.Synced -> Unit
                }
                deleteRecursively(previous)
                cleanupOwn(staging, archiveTmp, reservationId)
                return DshPayloadInstallResult.Installed(
                    manifest = manifest,
                    archiveSha256 = manifest.archiveSha256,
                    reused = false,
                    notes = notes.toList()
                )
            }
        } catch (t: Throwable) {
            cleanupOwn(staging, archiveTmp, reservationId)
            return DshPayloadInstallResult.Integrity("payload install failed: " + describe(t))
        }
    }

    /** Streams the archive, extracts it, and verifies count + critical files. */
    private fun prepareStaging(
        manifest: DshPayloadManifest,
        staging: File,
        archiveTmp: File,
        notes: MutableList<String>
    ): DshPayloadInstallResult? {
        try {
            val archived = source.openArchive()
                ?: return DshPayloadInstallResult.Missing(
                    "no payload archive at ${config.payloadAsset} (${source.description})"
                ).also { cleanupOwn(staging, archiveTmp, null) }
            val digest = digestProvider()
            var bytes = 0L
            archived.use { input ->
                FileOutputStream(archiveTmp).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        digest.update(buf, 0, read)
                        out.write(buf, 0, read)
                        bytes += read
                    }
                }
            }
            // The archive copy is the extraction source: make it durable before it is trusted.
            when (val archiveSync = publisher.syncStagedFile(archiveTmp)) {
                is DshSyncOutcome.Failed -> {
                    cleanupOwn(staging, archiveTmp, null)
                    return DshPayloadInstallResult.Integrity(
                        "payload archive copy is not durable: " + archiveSync.detail
                    )
                }
                is DshSyncOutcome.Unsupported ->
                    notes += "archive fsync unsupported: " + archiveSync.detail
                is DshSyncOutcome.Synced -> Unit
            }
            val actualSha = toHex(digest.digest())
            if (bytes != manifest.archiveSize || actualSha != manifest.archiveSha256) {
                cleanupOwn(staging, archiveTmp, null)
                return DshPayloadInstallResult.Integrity(
                    if (bytes != manifest.archiveSize) {
                        "payload archive size $bytes != manifest ${manifest.archiveSize}"
                    } else {
                        "payload archive sha256 $actualSha != manifest ${manifest.archiveSha256}"
                    }
                )
            }
            val extracted = extractZip(archiveTmp, staging, notes)
            if (extracted == null) {
                cleanupOwn(staging, archiveTmp, null)
                return DshPayloadInstallResult.Integrity("payload archive contains an unsafe entry")
            }
            if (extracted != manifest.fileCount) {
                cleanupOwn(staging, archiveTmp, null)
                return DshPayloadInstallResult.Integrity(
                    "payload file count $extracted != manifest ${manifest.fileCount}"
                )
            }
            val mismatch = verifyCriticalFiles(manifest, staging)
            if (mismatch != null) {
                cleanupOwn(staging, archiveTmp, null)
                return DshPayloadInstallResult.Integrity(mismatch)
            }
            when (val stagingSync = publisher.syncDirectory(staging)) {
                is DshSyncOutcome.Failed -> {
                    cleanupOwn(staging, archiveTmp, null)
                    return DshPayloadInstallResult.Integrity(
                        "staging directory is not durable: " + stagingSync.detail
                    )
                }
                is DshSyncOutcome.Unsupported ->
                    notes += "staging directory fsync unsupported: " + stagingSync.detail
                is DshSyncOutcome.Synced -> Unit
            }
            return null
        } catch (t: Throwable) {
            cleanupOwn(staging, archiveTmp, null)
            return DshPayloadInstallResult.Integrity("payload staging failed: " + describe(t))
        }
    }

    private fun reuse(manifest: DshPayloadManifest): DshPayloadInstallResult.Installed? {
        val payload = config.payloadDir.toPath()
        if (!Files.isDirectory(payload, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(config.payloadMarkerFile.toPath())) return null
        val marker = readMarker() ?: return null
        if (!marker.matches(manifest)) return null
        if (verifyCriticalFiles(manifest, config.payloadDir) != null) return null
        return DshPayloadInstallResult.Installed(manifest, manifest.archiveSha256, reused = true)
    }

    /** Rejects a destination shape this installer must never replace. */
    private fun stateError(): String? {
        // A symlinked data root would publish through an attacker/other-app controlled path; the
        // whole DSH tree must live on a plain directory.
        if (Files.isSymbolicLink(config.dataRoot.toPath())) {
            return "the DSH data root is a symlink; refusing to publish a payload through it"
        }
        val payload = config.payloadDir.toPath()
        if (Files.isSymbolicLink(payload)) {
            return "payload target is a symlink; refusing to publish into it"
        }
        if (existsNoFollow(config.payloadDir) && !Files.isDirectory(payload, LinkOption.NOFOLLOW_LINKS)) {
            return "payload target exists and is not a directory; refusing to replace it"
        }
        val marker = config.payloadMarkerFile.toPath()
        if (Files.isSymbolicLink(marker)) {
            return "payload marker is a symlink; refusing to replace it"
        }
        if (existsNoFollow(config.payloadMarkerFile) &&
            !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
        ) {
            return "payload marker is not a regular file"
        }
        return null
    }

    private fun lockFailure(
        outcome: DshPayloadLockOutcome,
        staging: File? = null,
        archiveTmp: File? = null,
        reservationId: String? = null
    ): DshPayloadInstallResult.Integrity {
        cleanupOwn(staging, archiveTmp, reservationId)
        return when (outcome) {
            is DshPayloadLockOutcome.Busy -> DshPayloadInstallResult.Integrity(
                "payload install lock busy: ${outcome.detail}"
            )
            is DshPayloadLockOutcome.Unavailable -> DshPayloadInstallResult.Integrity(
                "payload install lock unavailable: ${outcome.detail}"
            )
            is DshPayloadLockOutcome.Acquired -> {
                outcome.lock.close()
                DshPayloadInstallResult.Integrity("payload install lock invariant failed")
            }
        }
    }

    private fun existsNoFollow(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun newReservationId(): String =
        "${clock()}-${UUID.randomUUID().toString().replace("-", "").take(12)}"

    private fun reservationFile(root: File, id: String): File =
        File(root, "$RESERVATION_PREFIX$id$RESERVATION_SUFFIX")

    private fun writeReservation(root: File, id: String, manifest: DshPayloadManifest): Boolean = try {
        val json = DshJson.write(
            linkedMapOf(
                "id" to id,
                "archiveSha256" to manifest.archiveSha256,
                "createdAtMillis" to clock()
            )
        )
        Files.write(
            reservationFile(root, id).toPath(),
            json.toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * Deletes artifacts from crashed installers, but only when old enough that they cannot
     * belong to a live concurrent installer that is currently extracting outside the lock.
     */
    private fun cleanupStale(root: File) {
        val now = clock()
        root.listFiles()?.forEach { file ->
            val name = file.name
            val artifact = name.startsWith(STAGING_PREFIX) ||
                name.startsWith(OLD_PREFIX) ||
                name.startsWith(ARCHIVE_PREFIX) ||
                (name.startsWith(RESERVATION_PREFIX) && name.endsWith(RESERVATION_SUFFIX)) ||
                name == MARKER_TMP
            if (!artifact) return@forEach
            val modified = file.lastModified()
            val aged = modified <= 0L || now - modified >= staleArtifactMillis
            if (aged) deleteRecursively(file)
        }
    }

    private fun cleanupOwn(staging: File?, archiveTmp: File?, reservationId: String?) {
        staging?.let { deleteRecursively(it) }
        archiveTmp?.let { deleteRecursively(it) }
        if (reservationId != null) {
            deleteRecursively(reservationFile(config.dataRoot, reservationId))
        }
    }

    private fun restore(previous: File) {
        if (!existsNoFollow(previous) || existsNoFollow(config.payloadDir)) return
        runCatching {
            Files.move(previous.toPath(), config.payloadDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /**
     * Atomic marker publication inside one directory: fsync a temporary marker, then rename it
     * over the final name.
     *
     * The previous implementation used `link(2)` for no-replace semantics. A real Android 16 /
     * HyperOS device denied that syscall inside the app sandbox (`AccessDeniedException:
     * payload.marker.json -> payload.marker.tmp`), so a perfectly verified payload could never be
     * marked. The exclusive install lock already guarantees that no other process publishes this
     * marker; under that lock a same-directory `ATOMIC_MOVE` is the portable primitive. A crash
     * before the move leaves only the tmp marker (next start re-verifies/reinstalls); a crash
     * after the move leaves a complete marker because the tmp file was fsynced first. No partial
     * marker can ever become visible.
     */
    private fun writeMarkerNoReplace(marker: DshPayloadMarker): String? {
        val tmp = File(config.dataRoot, MARKER_TMP)
        return try {
            deleteRecursively(tmp)
            FileOutputStream(tmp).use { out ->
                out.write(marker.toJson().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            val markerPath = config.payloadMarkerFile.toPath()
            // Under the install lock the previous marker belongs to us; delete it first so the
            // rename is a create, then publish atomically in the same directory.
            Files.deleteIfExists(markerPath)
            Files.move(tmp.toPath(), markerPath, StandardCopyOption.ATOMIC_MOVE)
            null
        } catch (t: Throwable) {
            deleteRecursively(tmp)
            describe(t)
        }
    }

    /**
     * Extract the archive into the staging tree.
     *
     * Every extracted file is fsync'ed before the tree can be published, so the marker never
     * claims a tree whose bytes may still be in page cache after a power loss. A failed fsync
     * aborts the install (fail closed); an unsupported fsync is recorded as a note.
     *
     * @return number of extracted files, or null when an entry would escape the staging root.
     */
    private fun extractZip(archive: File, staging: File, notes: MutableList<String>): Long? {
        val rootPath = staging.canonicalFile.path + File.separator
        var files = 0L
        FileInputStream(archive).use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    if (name.startsWith("/") || name.contains('\\') || name.split('/').any { it == ".." }) {
                        zip.closeEntry()
                        return null
                    }
                    val target = File(staging, name)
                    if (!target.canonicalFile.path.startsWith(rootPath)) {
                        zip.closeEntry()
                        return null
                    }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val read = zip.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                        }
                    }
                    when (val sync = publisher.syncStagedFile(target)) {
                        is DshSyncOutcome.Failed -> {
                            zip.closeEntry()
                            throw java.io.IOException("staged file fsync failed for : ")
                        }
                        is DshSyncOutcome.Unsupported ->
                            if (notes.none { it.startsWith("content fsync unsupported") }) {
                                notes += "content fsync unsupported: "
                            }
                        is DshSyncOutcome.Synced -> Unit
                    }
                    files++
                    zip.closeEntry()
                }
            }
        }
        return files
    }

    private fun verifyCriticalFiles(manifest: DshPayloadManifest, base: File): String? {
        for (critical in manifest.criticalFiles) {
            val file = File(base, critical.path)
            if (!file.isFile) return "payload is missing critical file ${critical.path}"
            if (file.length() != critical.size) {
                return "payload critical file ${critical.path} size ${file.length()} != ${critical.size}"
            }
            val actual = sha256(file) ?: return "payload critical file ${critical.path} unreadable"
            if (actual != critical.sha256) {
                return "payload critical file ${critical.path} sha256 mismatch"
            }
        }
        if (!File(base, manifest.kernelEntry).isFile) {
            return "payload is missing the official kernel entry ${manifest.kernelEntry}"
        }
        if (!File(base, manifest.runtimeLibraryDir).isDirectory) {
            return "payload is missing the Node runtime library directory ${manifest.runtimeLibraryDir}"
        }
        return null
    }

    private fun readMarker(): DshPayloadMarker? {
        val file = config.payloadMarkerFile
        if (!file.isFile) return null
        return try {
            DshPayloadMarker.parse(file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            null
        }
    }

    fun sha256(file: File): String? = try {
        val digest = digestProvider()
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        toHex(digest.digest())
    } catch (t: Throwable) {
        null
    }

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            out[i * 2] = hex[b ushr 4]
            out[i * 2 + 1] = hex[b and 0x0F]
        }
        return String(out)
    }

    /** Symlink-safe recursive delete: never follows a link into another tree. */
    private fun deleteRecursively(file: File) {
        val path = file.toPath()
        if (!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            runCatching {
                Files.newDirectoryStream(path).use { stream ->
                    for (child in stream) deleteRecursively(child.toFile())
                }
            }
        }
        runCatching { Files.deleteIfExists(path) }
    }

    private fun describe(t: Throwable): String {
        val reason = (t as? java.nio.file.FileSystemException)?.reason?.takeIf { it.isNotBlank() }
        return buildString {
            append(t.javaClass.simpleName)
            t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            if (reason != null && t.message?.contains(reason) != true) {
                append(" (").append(reason).append(')')
            }
        }
    }

    private companion object {
        const val STAGING_PREFIX = "payload.staging."
        const val OLD_PREFIX = "payload.old."
        const val ARCHIVE_PREFIX = "payload.archive."
        const val RESERVATION_PREFIX = "payload.reservation."
        const val RESERVATION_SUFFIX = ".json"
        const val MARKER_TMP = "payload.marker.tmp"
        const val STALE_ARTIFACT_MILLIS = 60 * 60 * 1000L
        const val DEFAULT_LOCK_TIMEOUT_MILLIS = 600_000L
        const val DEFAULT_LOCK_POLL_MILLIS = 50L
    }
}




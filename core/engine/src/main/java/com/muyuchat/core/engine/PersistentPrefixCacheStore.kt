package com.muyuchat.core.engine

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Metadata returned for a verified persisted prefix state.
 *
 * [stateFile] is deliberately exposed instead of eagerly reading its bytes.
 * Native callers can pass the app-private file to a state restore API without
 * duplicating a potentially large KV cache in the managed heap.
 */
data class PersistentPrefixCacheEntry(
    val key: PrefixCacheKey,
    val stateFile: File,
    val stateSizeBytes: Long,
    val stateSha256: String,
    val createdAtEpochMs: Long,
    val lastAccessEpochMs: Long
)

/** Aggregate storage information for the app-private persistent prefix cache. */
data class PersistentPrefixCacheSummary(
    val entryCount: Int,
    val totalBytes: Long
) {
    init {
        require(entryCount >= 0) { "entryCount must not be negative." }
        require(totalBytes >= 0L) { "totalBytes must not be negative." }
    }
}

internal fun interface PrefixCacheStateFileSyncer {
    fun sync(file: File)
}

private object FileDescriptorPrefixCacheStateFileSyncer : PrefixCacheStateFileSyncer {
    override fun sync(file: File) {
        // The native exporter has already closed its writer. Reopen without
        // truncating so sync() flushes those bytes before they are verified and
        // made reachable through cache metadata.
        FileOutputStream(file, true).use { output ->
            output.flush()
            output.fd.sync()
        }
    }
}

/**
 * Android exposes app storage below a system-owned per-user directory alias.
 * That alias precedes the app-controlled cache root, so accepting exactly this
 * path does not allow a cache entry to follow a caller-controlled link.
 */
internal fun isFrameworkManagedAndroidUserAlias(path: Path): Boolean {
    val segments = path.toString()
        .replace('\\', '/')
        .split('/')
        .filter(String::isNotBlank)
    return segments.size == 3 &&
        segments[0] == "data" &&
        segments[1] in setOf("user", "user_de") &&
        segments[2].isNotEmpty() &&
        segments[2].all(Char::isDigit)
}

/**
 * Small, defensive disk store for persisted llama.cpp prefix states.
 *
 * The caller supplies a dedicated app-private `noBackupFilesDir` child. This
 * class does not attempt to choose a public storage location and never follows
 * symbolic links. A failed cache read or write returns null/false so inference
 * can fall back to normal prefill.
 */
class PersistentPrefixCacheStore private constructor(
    private val rootDirectory: File,
    private val maxBytes: Long,
    private val clock: () -> Long,
    private val stateFileSyncer: PrefixCacheStateFileSyncer
) {
    constructor(
        rootDirectory: File,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        clock: () -> Long = { System.currentTimeMillis() }
    ) : this(
        rootDirectory = rootDirectory,
        maxBytes = maxBytes,
        clock = clock,
        stateFileSyncer = FileDescriptorPrefixCacheStateFileSyncer
    )

    /**
     * Native code writes a complete state into [stateFile], then calls
     * [commit]. The constructor is internal so a store never accepts an
     * arbitrary caller-selected path as a cache staging file.
     */
    class PendingWrite internal constructor(
        val key: PrefixCacheKey,
        val stateFile: File,
        internal val operationId: String,
        internal val storeLock: Closeable
    )

    init {
        require(maxBytes > 0L) { "maxBytes must be positive." }
    }

    /**
     * Persists [state] with an atomic state publication followed by an atomic
     * metadata publication. The caller retains ownership of [state].
     */
    fun save(key: PrefixCacheKey, state: InputStream): PersistentPrefixCacheEntry? = synchronized(lock) {
        val root = resolveSafeRoot() ?: return@synchronized null
        val storeLock = acquireStoreLock(root) ?: return@synchronized null
        storeLock.use { saveLocked(root, key, state) }
    }

    /** Convenience overload for runtimes that export state as a byte array. */
    fun save(key: PrefixCacheKey, state: ByteArray): PersistentPrefixCacheEntry? =
        save(key, state.inputStream())

    /**
     * Allocates a safe, same-directory staging file for a native state export.
     * Call [commit] after the native writer has closed the file, or [discard]
     * when generation is cancelled.
     */
    fun prepareWrite(key: PrefixCacheKey): PendingWrite? = synchronized(lock) {
        val root = resolveSafeRoot() ?: return@synchronized null
        // Native state export is intentionally serialized. Besides matching the
        // engine lifecycle lock, this prevents abandoned external writers from
        // consuming an unbounded amount of the dedicated cache root.
        if (pendingWrites.isNotEmpty()) return@synchronized null
        val storeLock = acquireStoreLock(root) ?: return@synchronized null
        try {
            val staging = createTemporaryFile(root, key.cacheId) ?: return@synchronized null
            PendingWrite(
                key = key,
                stateFile = staging,
                operationId = UUID.randomUUID().toString(),
                storeLock = storeLock
            ).also { pending ->
                pendingWrites[pending.operationId] = pending
            }
        } finally {
            if (pendingWrites.values.none { it.storeLock === storeLock }) {
                closeQuietly(storeLock)
            }
        }
    }

    /**
     * Verifies a native-written staging file and publishes state before its
     * metadata. A failed commit remains a cache miss and never throws into the
     * inference path.
     */
    fun commit(pending: PendingWrite): PersistentPrefixCacheEntry? = synchronized(lock) {
        val registered = pendingWrites[pending.operationId]
        if (registered !== pending) return@synchronized null
        var root: File? = null
        try {
            root = resolveSafeRoot() ?: return@synchronized null
            if (!isDirectChild(root, pending.stateFile) || !TEMP_FILE_NAME.matches(pending.stateFile.name) ||
                !isRegularFileWithoutLinks(pending.stateFile)
            ) {
                return@synchronized null
            }
            if (!syncStagedFile(pending.stateFile)) return@synchronized null
            val state = digestStagedFile(pending.stateFile) ?: return@synchronized null
            return@synchronized publishStagedState(root, pending.key, pending.stateFile, state)
        } finally {
            pendingWrites.remove(pending.operationId)
            root?.let { deleteTemporary(it, pending.stateFile) }
            closeQuietly(pending.storeLock)
        }
    }

    /** Explicitly abandons a native staging file that will not be committed. */
    fun discard(pending: PendingWrite): Boolean = synchronized(lock) {
        val registered = pendingWrites[pending.operationId]
        if (registered !== pending) return@synchronized false
        try {
            val root = resolveSafeRoot() ?: return@synchronized false
            return@synchronized deleteTemporary(root, pending.stateFile)
        } finally {
            pendingWrites.remove(pending.operationId)
            closeQuietly(pending.storeLock)
        }
    }

    /**
     * Returns a verified state file and records an LRU access. A null result is
     * a cache miss; callers must continue with an ordinary prompt prefill.
     */
    fun load(key: PrefixCacheKey): PersistentPrefixCacheEntry? = synchronized(lock) {
        val root = resolveSafeRoot() ?: return@synchronized null
        val storeLock = acquireStoreLock(root) ?: return@synchronized null
        storeLock.use { loadLocked(root, key) }
    }

    /** Returns all verified entries, newest access first, without mutating their LRU order. */
    fun entries(): List<PersistentPrefixCacheEntry> = synchronized(lock) {
        val root = resolveSafeRoot() ?: return@synchronized emptyList()
        val storeLock = acquireStoreLock(root) ?: return@synchronized emptyList()
        storeLock.use {
            scan(root, cleanupInvalid = true)
                .map { it.toPublicEntry() }
                .sortedWith(
                    compareByDescending<PersistentPrefixCacheEntry> { it.lastAccessEpochMs }
                        .thenByDescending { it.createdAtEpochMs }
                        .thenBy { it.key.cacheId }
            )
        }
    }

    /** Returns verified entry count and byte usage without exposing cache keys to the UI. */
    fun summary(): PersistentPrefixCacheSummary {
        val entries = entries()
        return PersistentPrefixCacheSummary(
            entryCount = entries.size,
            totalBytes = entries.sumOf { entry -> entry.stateSizeBytes }
        )
    }

    /** Removes every managed cache artifact beneath the supplied root. */
    fun clear(): Boolean = synchronized(lock) {
        val root = resolveSafeRoot() ?: return@synchronized false
        val storeLock = acquireStoreLock(root) ?: return@synchronized false
        storeLock.use {
            var succeeded = true
            root.listFiles().orEmpty().forEach { candidate ->
                if (isManagedName(candidate.name)) {
                    succeeded = deleteManagedFile(root, candidate) && succeeded
                }
            }
            succeeded
        }
    }

    /** Removes one key and every managed state version associated with it. */
    fun clear(key: PrefixCacheKey): Boolean = synchronized(lock) {
        val root = resolveSafeRoot() ?: return@synchronized false
        val storeLock = acquireStoreLock(root) ?: return@synchronized false
        storeLock.use {
            val cacheId = key.cacheId
            var succeeded = true
            root.listFiles().orEmpty().forEach { candidate ->
                val name = candidate.name
                val belongsToKey = name == metadataFileName(cacheId) ||
                    STATE_FILE_NAME.matches(name) && name.startsWith("$cacheId.") ||
                    TEMP_FILE_NAME.matches(name) && name.startsWith(".$cacheId-")
                if (belongsToKey) {
                    succeeded = deleteManagedFile(root, candidate) && succeeded
                }
            }
            succeeded
        }
    }

    /** Trims verified entries to the configured quota using least-recently-used eviction. */
    fun trimToQuota(): Boolean = synchronized(lock) {
        val root = resolveSafeRoot() ?: return@synchronized false
        val storeLock = acquireStoreLock(root) ?: return@synchronized false
        storeLock.use {
            evictToQuota(root, scan(root, cleanupInvalid = true), maxBytes)
        }
    }

    private fun saveLocked(
        root: File,
        key: PrefixCacheKey,
        source: InputStream
    ): PersistentPrefixCacheEntry? {
        val temporary = createTemporaryFile(root, key.cacheId) ?: return null
        try {
            val state = copyAndDigest(source, temporary) ?: return null
            return publishStagedState(root, key, temporary, state)
        } catch (_: Exception) {
            return null
        } finally {
            deleteTemporary(root, temporary)
        }
    }

    /** Publishes an already-complete, same-root state staging file. */
    private fun publishStagedState(
        root: File,
        key: PrefixCacheKey,
        temporary: File,
        state: StateDigest
    ): PersistentPrefixCacheEntry? {
        if (state.sizeBytes <= 0L || !isDirectChild(root, temporary) ||
            !TEMP_FILE_NAME.matches(temporary.name) || !isRegularFileWithoutLinks(temporary)
        ) {
            return null
        }
        val existing = scan(
            root = root,
            cleanupInvalid = true,
            preservedTemporaryNames = protectedTemporaryNames(temporary.name)
        )
        val previous = existing.firstOrNull { it.key.cacheId == key.cacheId }
        val now = safeNow()
        val metadata = CacheMetadata(
            key = key,
            stateFileName = stateFileName(key.cacheId, state.sha256),
            stateSha256 = state.sha256,
            stateSizeBytes = state.sizeBytes,
            createdAtEpochMs = previous?.createdAtEpochMs ?: now,
            lastAccessEpochMs = maxOf(previous?.lastAccessEpochMs ?: 0L, now)
        )
        val metadataBytes = metadata.serialize().toByteArray(StandardCharsets.UTF_8)
        if (!makeRoomFor(root, existing, key.cacheId, state.sizeBytes, metadataBytes.size.toLong())) {
            return null
        }

        val stateTarget = safeChild(root, metadata.stateFileName, STATE_FILE_NAME) ?: return null
        val targetIsValid = verifyStateFile(
            file = stateTarget,
            expectedSha256 = state.sha256,
            expectedSizeBytes = state.sizeBytes
        )
        if (!targetIsValid) {
            if (existsAsSymbolicLink(stateTarget) || !atomicMove(temporary, stateTarget)) return null
        } else {
            // State may already have been published by a previous interrupted
            // save. It is content-addressed and verified above, so the staged
            // duplicate can be discarded before publishing its metadata.
            deleteTemporary(root, temporary)
        }

        val metadataTarget = safeChild(root, metadataFileName(key.cacheId), METADATA_FILE_NAME) ?: return null
        if (existsAsSymbolicLink(metadataTarget)) return null
        if (!writeAtomically(root, key.cacheId, metadataTarget, metadataBytes)) return null

        previous?.takeIf { it.stateFile.name != metadata.stateFileName }?.let { old ->
            deleteManagedFile(root, old.stateFile)
        }
        scan(root, cleanupInvalid = true)
        return metadata.toStored(root)?.toPublicEntry()
    }

    private fun loadLocked(root: File, key: PrefixCacheKey): PersistentPrefixCacheEntry? {
        val metadataFile = safeChild(root, metadataFileName(key.cacheId), METADATA_FILE_NAME) ?: return null
        val metadata = readMetadata(metadataFile) ?: return null
        if (metadata.key != key) {
            deleteManagedFile(root, metadataFile)
            return null
        }
        val stored = metadata.toStored(root) ?: run {
            deleteManagedFile(root, metadataFile)
            return null
        }
        if (!verifyStoredEntry(stored)) {
            deleteEntry(root, stored)
            return null
        }

        val accessedAt = maxOf(metadata.lastAccessEpochMs, safeNow())
        val refreshed = metadata.copy(lastAccessEpochMs = accessedAt)
        val refreshedBytes = refreshed.serialize().toByteArray(StandardCharsets.UTF_8)
        val published = writeAtomically(root, key.cacheId, metadataFile, refreshedBytes)
        return if (published) refreshed.toStored(root)?.toPublicEntry() else stored.toPublicEntry()
    }

    /**
     * Scans only direct children with a store-owned filename. Invalid metadata,
     * invalid state, stale state versions, and interrupted temporary files are
     * best-effort cleaned without touching arbitrary caller-owned files.
     */
    private fun scan(
        root: File,
        cleanupInvalid: Boolean,
        preservedTemporaryNames: Set<String> = protectedTemporaryNames()
    ): List<StoredEntry> {
        val stored = ArrayList<StoredEntry>()
        root.listFiles().orEmpty()
            .asSequence()
            .filter { METADATA_FILE_NAME.matches(it.name) }
            .sortedBy { it.name }
            .forEach { metadataFile ->
                val metadata = readMetadata(metadataFile)
                val entry = metadata?.toStored(root)?.takeIf(::verifyStoredEntry)
                if (entry == null) {
                    if (cleanupInvalid) deleteManagedFile(root, metadataFile)
                } else {
                    stored += entry
                }
            }

        if (cleanupInvalid) {
            val referencedStates = stored.mapTo(HashSet()) { it.stateFile.name }
            root.listFiles().orEmpty().forEach { candidate ->
                when {
                    STATE_FILE_NAME.matches(candidate.name) && candidate.name !in referencedStates ->
                        deleteManagedFile(root, candidate)
                    TEMP_FILE_NAME.matches(candidate.name) && candidate.name !in preservedTemporaryNames ->
                        deleteTemporary(root, candidate)
                }
            }
        }
        return stored
    }

    private fun protectedTemporaryNames(additional: String? = null): Set<String> = buildSet {
        pendingWrites.values.forEach { pending -> add(pending.stateFile.name) }
        additional?.let(::add)
    }

    private fun makeRoomFor(
        root: File,
        entries: List<StoredEntry>,
        replacementCacheId: String,
        replacementStateBytes: Long,
        replacementMetadataBytes: Long
    ): Boolean {
        val replacementBytes = saturatedAdd(replacementStateBytes, replacementMetadataBytes)
        if (replacementBytes > maxBytes) return false

        val retained = entries.filterNot { it.key.cacheId == replacementCacheId }
        var projected = saturatedAdd(totalBytes(retained), replacementBytes)
        retained.sortedWith(
            compareBy<StoredEntry> { it.lastAccessEpochMs }
                .thenBy { it.createdAtEpochMs }
                .thenBy { it.key.cacheId }
        ).forEach { entry ->
            if (projected <= maxBytes) return@forEach
            if (deleteEntry(root, entry)) {
                projected = (projected - entry.totalBytes).coerceAtLeast(0L)
            }
        }
        return projected <= maxBytes
    }

    private fun evictToQuota(root: File, entries: List<StoredEntry>, quotaBytes: Long): Boolean {
        var total = totalBytes(entries)
        var succeeded = true
        entries.sortedWith(
            compareBy<StoredEntry> { it.lastAccessEpochMs }
                .thenBy { it.createdAtEpochMs }
                .thenBy { it.key.cacheId }
        ).forEach { entry ->
            if (total <= quotaBytes) return@forEach
            if (deleteEntry(root, entry)) {
                total = (total - entry.totalBytes).coerceAtLeast(0L)
            } else {
                succeeded = false
            }
        }
        return succeeded && total <= quotaBytes
    }

    private fun totalBytes(entries: List<StoredEntry>): Long = entries.fold(0L) { total, entry ->
        saturatedAdd(total, entry.totalBytes)
    }

    private fun deleteEntry(root: File, entry: StoredEntry): Boolean {
        val metadataDeleted = deleteManagedFile(root, entry.metadataFile)
        val stateDeleted = deleteManagedFile(root, entry.stateFile)
        return metadataDeleted && stateDeleted
    }

    private fun verifyStoredEntry(entry: StoredEntry): Boolean =
        verifyStateFile(entry.stateFile, entry.stateSha256, entry.stateSizeBytes) &&
            regularFileSize(entry.metadataFile) == entry.metadataFileSizeBytes

    private fun verifyStateFile(
        file: File,
        expectedSha256: String,
        expectedSizeBytes: Long
    ): Boolean {
        if (!PrefixCacheKey.isSha256Hex(expectedSha256) || expectedSizeBytes <= 0L || expectedSizeBytes > maxBytes) {
            return false
        }
        if (!isRegularFileWithoutLinks(file) || regularFileSize(file) != expectedSizeBytes) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileInputStream(file).use { input ->
                val buffer = ByteArray(IO_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total = saturatedAdd(total, read.toLong())
                    if (total > expectedSizeBytes) return false
                    digest.update(buffer, 0, read)
                }
            }
            total == expectedSizeBytes && digest.digest().toLowercaseHex() == expectedSha256
        } catch (_: Exception) {
            false
        }
    }

    private fun copyAndDigest(source: InputStream, target: File): StateDigest? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(IO_BUFFER_BYTES)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total = saturatedAdd(total, read.toLong())
                    if (total > maxBytes) return null
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
            StateDigest(
                sha256 = digest.digest().toLowercaseHex(),
                sizeBytes = total
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun digestStagedFile(file: File): StateDigest? {
        if (!isRegularFileWithoutLinks(file)) return null
        return try {
            val expectedSize = regularFileSize(file)
            if (expectedSize <= 0L || expectedSize > maxBytes) return null
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileInputStream(file).use { input ->
                val buffer = ByteArray(IO_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total = saturatedAdd(total, read.toLong())
                    if (total > maxBytes) return null
                    digest.update(buffer, 0, read)
                }
            }
            if (total != expectedSize) return null
            StateDigest(
                sha256 = digest.digest().toLowercaseHex(),
                sizeBytes = total
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun syncStagedFile(file: File): Boolean = try {
        stateFileSyncer.sync(file)
        isRegularFileWithoutLinks(file)
    } catch (_: Exception) {
        false
    }

    private fun writeAtomically(
        root: File,
        cacheId: String,
        target: File,
        content: ByteArray
    ): Boolean {
        val temporary = createTemporaryFile(root, cacheId) ?: return false
        return try {
            FileOutputStream(temporary).use { output ->
                output.write(content)
                output.flush()
                output.fd.sync()
            }
            atomicMove(temporary, target)
        } catch (_: Exception) {
            false
        } finally {
            deleteTemporary(root, temporary)
        }
    }

    private fun atomicMove(source: File, target: File): Boolean {
        if (!isDirectChild(rootDirectory = target.parentFile, candidate = source) ||
            !isRegularFileWithoutLinks(source) ||
            existsAsSymbolicLink(target)
        ) {
            return false
        }
        return try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            isRegularFileWithoutLinks(target)
        } catch (_: Exception) {
            false
        }
    }

    private fun readMetadata(file: File): CacheMetadata? {
        if (!isRegularFileWithoutLinks(file)) return null
        val metadataSize = regularFileSize(file)
        if (metadataSize <= 0L || metadataSize > MAX_METADATA_BYTES) return null
        return try {
            // Re-check the bound while reading so a file replaced between the
            // size check and open cannot force an unbounded allocation.
            val documentBytes = ByteArrayOutputStream(metadataSize.toInt())
            FileInputStream(file).use { input ->
                val buffer = ByteArray(1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total = saturatedAdd(total, read.toLong())
                    if (total > MAX_METADATA_BYTES) return null
                    documentBytes.write(buffer, 0, read)
                }
            }
            val document = documentBytes.toByteArray().toString(StandardCharsets.UTF_8)
            CacheMetadata.parse(document)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Acquires the process-wide store mutex without waiting. Cache work is
     * optional, so contention must immediately fall back to an ordinary
     * prefill instead of delaying inference behind another process.
     */
    private fun acquireStoreLock(root: File): Closeable? {
        val lockFile = File(root, LOCK_FILE_NAME)
        if (!isDirectChild(root, lockFile) || existsAsSymbolicLink(lockFile)) return null

        var channelToClose: FileChannel? = null
        var lockToRelease: FileLock? = null
        return try {
            val channel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            )
            channelToClose = channel
            if (!isDirectChild(root, lockFile) || !isRegularFileWithoutLinks(lockFile)) return null

            val fileLock = channel.tryLock() ?: return null
            lockToRelease = fileLock
            if (!isDirectChild(root, lockFile) || !isRegularFileWithoutLinks(lockFile)) return null

            HeldStoreLock(channel, fileLock).also {
                channelToClose = null
                lockToRelease = null
            }
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            lockToRelease?.let { lock -> runCatching { lock.release() } }
            channelToClose?.let { channel -> runCatching { channel.close() } }
        }
    }

    private fun closeQuietly(closeable: Closeable) {
        runCatching { closeable.close() }
    }

    private fun resolveSafeRoot(): File? = try {
        val requested = rootDirectory.toPath().toAbsolutePath().normalize()
        if (containsSymbolicLink(requested)) return null
        Files.createDirectories(requested)
        if (containsSymbolicLink(requested) || !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        requested.toFile().canonicalFile.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    private fun containsSymbolicLink(path: java.nio.file.Path): Boolean {
        var current = path.root ?: return true
        path.forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                // Android exposes each app's credential/device-encrypted
                // directory through this system-owned per-user alias. It is
                // outside the app-controlled root; every component from the
                // package directory downward remains subject to the strict
                // no-link check below.
                if (!isFrameworkManagedAndroidUserAlias(current)) return true
            }
        }
        return false
    }

    private fun safeChild(root: File, name: String, pattern: Regex): File? {
        if (!pattern.matches(name)) return null
        val child = File(root, name)
        if (!isDirectChild(root, child)) return null
        return child.takeUnless(::existsAsSymbolicLink)
    }

    private fun isDirectChild(rootDirectory: File?, candidate: File): Boolean {
        val root = rootDirectory ?: return false
        return try {
            candidate.toPath().toAbsolutePath().normalize().parent ==
                root.toPath().toAbsolutePath().normalize()
        } catch (_: Exception) {
            false
        }
    }

    private fun isRegularFileWithoutLinks(file: File): Boolean = try {
        !Files.isSymbolicLink(file.toPath()) &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        false
    }

    private fun existsAsSymbolicLink(file: File): Boolean = try {
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(file.toPath())
    } catch (_: Exception) {
        true
    }

    private fun regularFileSize(file: File): Long = try {
        if (isRegularFileWithoutLinks(file)) Files.size(file.toPath()) else -1L
    } catch (_: Exception) {
        -1L
    }

    private fun createTemporaryFile(root: File, cacheId: String): File? = try {
        Files.createTempFile(root.toPath(), ".$cacheId-", TEMPORARY_SUFFIX).toFile()
    } catch (_: Exception) {
        null
    }

    private fun deleteTemporary(root: File, file: File): Boolean =
        if (TEMP_FILE_NAME.matches(file.name)) deleteManagedFile(root, file) else false

    private fun deleteManagedFile(root: File, file: File): Boolean {
        if (!isDirectChild(root, file) || !isManagedName(file.name)) return false
        return try {
            Files.deleteIfExists(file.toPath())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun safeNow(): Long = runCatching { clock() }.getOrDefault(0L).coerceAtLeast(0L)

    private data class StateDigest(
        val sha256: String,
        val sizeBytes: Long
    )

    private class HeldStoreLock(
        private val channel: FileChannel,
        private val fileLock: FileLock
    ) : Closeable {
        override fun close() {
            try {
                if (fileLock.isValid) fileLock.release()
            } catch (_: Exception) {
                // Cache cleanup is best effort; the channel close below also
                // releases this process's lock if an explicit release failed.
            } finally {
                runCatching { channel.close() }
            }
        }
    }

    private data class StoredEntry(
        val key: PrefixCacheKey,
        val metadataFile: File,
        val metadataFileSizeBytes: Long,
        val stateFile: File,
        val stateSha256: String,
        val stateSizeBytes: Long,
        val createdAtEpochMs: Long,
        val lastAccessEpochMs: Long
    ) {
        val totalBytes: Long
            get() = saturatedAdd(metadataFileSizeBytes, stateSizeBytes)

        fun toPublicEntry(): PersistentPrefixCacheEntry = PersistentPrefixCacheEntry(
            key = key,
            stateFile = stateFile,
            stateSizeBytes = stateSizeBytes,
            stateSha256 = stateSha256,
            createdAtEpochMs = createdAtEpochMs,
            lastAccessEpochMs = lastAccessEpochMs
        )
    }

    private data class CacheMetadata(
        val key: PrefixCacheKey,
        val stateFileName: String,
        val stateSha256: String,
        val stateSizeBytes: Long,
        val createdAtEpochMs: Long,
        val lastAccessEpochMs: Long
    ) {
        fun serialize(): String = buildString {
            append("format_version=").append(FORMAT_VERSION).append('\n')
            append("cache_key=").append(key.cacheId).append('\n')
            append("model_fingerprint=").append(key.modelFingerprint).append('\n')
            append("tokenizer_fingerprint=").append(key.tokenizerFingerprint).append('\n')
            append("template_fingerprint=").append(key.templateFingerprint).append('\n')
            append("system_prompt_fingerprint=").append(key.systemPromptFingerprint).append('\n')
            append("runtime_fingerprint=").append(key.runtimeFingerprint).append('\n')
            append("prefix_fingerprint=").append(key.prefixFingerprint).append('\n')
            append("state_file=").append(stateFileName).append('\n')
            append("state_sha256=").append(stateSha256).append('\n')
            append("state_size_bytes=").append(stateSizeBytes).append('\n')
            append("created_at_epoch_ms=").append(createdAtEpochMs).append('\n')
            append("last_access_epoch_ms=").append(lastAccessEpochMs).append('\n')
        }

        fun toStored(root: File): StoredEntry? {
            if (!PrefixCacheKey.isSha256Hex(stateSha256) || stateSizeBytes <= 0L ||
                createdAtEpochMs < 0L || lastAccessEpochMs < 0L ||
                stateFileName != stateFileName(key.cacheId, stateSha256)
            ) {
                return null
            }
            val metadataFile = safeMetadataFile(root, key.cacheId) ?: return null
            val stateFile = safeStateFile(root, stateFileName) ?: return null
            val metadataSize = regularSize(metadataFile)
            if (metadataSize <= 0L || metadataSize > MAX_METADATA_BYTES) return null
            return StoredEntry(
                key = key,
                metadataFile = metadataFile,
                metadataFileSizeBytes = metadataSize,
                stateFile = stateFile,
                stateSha256 = stateSha256,
                stateSizeBytes = stateSizeBytes,
                createdAtEpochMs = createdAtEpochMs,
                lastAccessEpochMs = lastAccessEpochMs
            )
        }

        companion object {
            fun parse(document: String): CacheMetadata? {
                if (document.isEmpty() || !document.endsWith('\n') || '\r' in document) return null
                val fields = LinkedHashMap<String, String>()
                document.dropLast(1).split('\n').forEach { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0 || separator != line.lastIndexOf('=')) return null
                    val field = line.substring(0, separator)
                    val value = line.substring(separator + 1)
                    if (field !in METADATA_FIELD_ORDER || fields.put(field, value) != null) return null
                }
                if (fields.keys.toList() != METADATA_FIELD_ORDER) return null
                if (fields["format_version"] != FORMAT_VERSION.toString()) return null

                val key = try {
                    val cacheId = fields.getValue("cache_key")
                    PrefixCacheKey(
                        modelFingerprint = fields.getValue("model_fingerprint"),
                        tokenizerFingerprint = fields.getValue("tokenizer_fingerprint"),
                        templateFingerprint = fields.getValue("template_fingerprint"),
                        systemPromptFingerprint = fields.getValue("system_prompt_fingerprint"),
                        runtimeFingerprint = fields.getValue("runtime_fingerprint"),
                        prefixFingerprint = fields.getValue("prefix_fingerprint")
                    ).takeIf { it.cacheId == cacheId }
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return null

                val stateSha256 = fields.getValue("state_sha256")
                val stateSize = parseNonNegativeLong(fields.getValue("state_size_bytes")) ?: return null
                val createdAt = parseNonNegativeLong(fields.getValue("created_at_epoch_ms")) ?: return null
                val lastAccess = parseNonNegativeLong(fields.getValue("last_access_epoch_ms")) ?: return null
                return CacheMetadata(
                    key = key,
                    stateFileName = fields.getValue("state_file"),
                    stateSha256 = stateSha256,
                    stateSizeBytes = stateSize,
                    createdAtEpochMs = createdAt,
                    lastAccessEpochMs = lastAccess
                )
            }
        }
    }

    companion object {
        // Full-session KV states for 7B–12B models can exceed 256 MiB even
        // with quantized KV. Keep enough room for a few role sessions while
        // retaining the store's LRU eviction and app-private boundary.
        const val DEFAULT_MAX_BYTES: Long = 1024L * 1024L * 1024L
        const val FORMAT_VERSION: Int = 1

        internal fun createForTest(
            rootDirectory: File,
            maxBytes: Long,
            clock: () -> Long,
            stateFileSyncer: PrefixCacheStateFileSyncer
        ): PersistentPrefixCacheStore = PersistentPrefixCacheStore(
            rootDirectory = rootDirectory,
            maxBytes = maxBytes,
            clock = clock,
            stateFileSyncer = stateFileSyncer
        )

        private const val IO_BUFFER_BYTES = 32 * 1024
        private const val MAX_METADATA_BYTES = 16L * 1024L
        private const val LOCK_FILE_NAME = ".prefix-cache.lock"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private val SHA256_FILE_COMPONENT = "[0-9a-f]{64}"
        private val METADATA_FILE_NAME = Regex("^$SHA256_FILE_COMPONENT\\.meta$")
        private val STATE_FILE_NAME = Regex("^$SHA256_FILE_COMPONENT\\.$SHA256_FILE_COMPONENT\\.state$")
        private val TEMP_FILE_NAME = Regex("^\\.$SHA256_FILE_COMPONENT-[A-Za-z0-9._-]+\\.tmp$")
        private val NON_NEGATIVE_LONG = Regex("^(0|[1-9][0-9]*)$")
        private val METADATA_FIELD_ORDER = listOf(
            "format_version",
            "cache_key",
            "model_fingerprint",
            "tokenizer_fingerprint",
            "template_fingerprint",
            "system_prompt_fingerprint",
            "runtime_fingerprint",
            "prefix_fingerprint",
            "state_file",
            "state_sha256",
            "state_size_bytes",
            "created_at_epoch_ms",
            "last_access_epoch_ms"
        )

        private fun metadataFileName(cacheId: String): String = "$cacheId.meta"

        private fun stateFileName(cacheId: String, stateSha256: String): String =
            "$cacheId.$stateSha256.state"

        private fun safeMetadataFile(root: File, cacheId: String): File? =
            safeChildStatic(root, metadataFileName(cacheId), METADATA_FILE_NAME)

        private fun safeStateFile(root: File, name: String): File? =
            safeChildStatic(root, name, STATE_FILE_NAME)

        private fun safeChildStatic(root: File, name: String, pattern: Regex): File? {
            if (!pattern.matches(name)) return null
            val child = File(root, name)
            return try {
                val directChild = child.toPath().toAbsolutePath().normalize().parent ==
                    root.toPath().toAbsolutePath().normalize()
                if (!directChild || Files.isSymbolicLink(child.toPath())) null else child
            } catch (_: Exception) {
                null
            }
        }

        private fun regularSize(file: File): Long = try {
            if (!Files.isSymbolicLink(file.toPath()) &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                Files.size(file.toPath())
            } else {
                -1L
            }
        } catch (_: Exception) {
            -1L
        }

        private fun parseNonNegativeLong(value: String): Long? =
            value.takeIf { NON_NEGATIVE_LONG.matches(it) }?.toLongOrNull()

        private fun isManagedName(name: String): Boolean =
            METADATA_FILE_NAME.matches(name) || STATE_FILE_NAME.matches(name) || TEMP_FILE_NAME.matches(name)

        private fun saturatedAdd(left: Long, right: Long): Long =
            if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

        private fun ByteArray.toLowercaseHex(): String {
            val digits = "0123456789abcdef"
            return buildString(size * 2) {
                this@toLowercaseHex.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(digits[value ushr 4])
                    append(digits[value and 0x0f])
                }
            }
        }
    }

    private val lock = Any()
    private val pendingWrites = LinkedHashMap<String, PendingWrite>()
}

package com.muyuchat.core.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal fun PersistentPrefixCacheStore.loadImpl(key: PrefixCacheKey): PersistentPrefixCacheEntry? = synchronized(lock) {
        val root = resolveSafeRoot() ?: return@synchronized null
        val storeLock = acquireStoreLock(root) ?: return@synchronized null
        storeLock.use { loadLocked(root, key) }
    }

internal fun PersistentPrefixCacheStore.entriesImpl(): List<PersistentPrefixCacheEntry> = synchronized(lock) {
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

internal fun PersistentPrefixCacheStore.summaryImpl(): PersistentPrefixCacheSummary {
        val entries = entries()
        return PersistentPrefixCacheSummary(
            entryCount = entries.size,
            totalBytes = entries.sumOf { entry -> entry.stateSizeBytes }
        )
    }

internal fun PersistentPrefixCacheStore.clearImpl(): Boolean = synchronized(lock) {
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

internal fun PersistentPrefixCacheStore.clearImpl(key: PrefixCacheKey): Boolean = synchronized(lock) {
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

internal fun PersistentPrefixCacheStore.clearPrefixFingerprintsImpl(fingerprints: Set<String>): Boolean = synchronized(lock) {
        if (fingerprints.isEmpty()) return@synchronized true
        if (fingerprints.any { !PrefixCacheKey.isSha256Hex(it) }) return@synchronized false
        val root = resolveSafeRoot() ?: return@synchronized false
        val storeLock = acquireStoreLock(root) ?: return@synchronized false
        storeLock.use {
            var succeeded = true
            scan(root, cleanupInvalid = true)
                .filter { it.key.prefixFingerprint in fingerprints }
                .forEach { entry -> succeeded = deleteEntry(root, entry) && succeeded }
            succeeded
        }
    }

internal fun PersistentPrefixCacheStore.trimToQuotaImpl(): Boolean = synchronized(lock) {
        val root = resolveSafeRoot() ?: return@synchronized false
        val storeLock = acquireStoreLock(root) ?: return@synchronized false
        storeLock.use {
            evictToQuota(root, scan(root, cleanupInvalid = true), maxBytes)
        }
    }

internal fun PersistentPrefixCacheStore.loadLocked(root: File, key: PrefixCacheKey): PersistentPrefixCacheEntry? {
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
internal fun PersistentPrefixCacheStore.scan(
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

internal fun PersistentPrefixCacheStore.protectedTemporaryNames(additional: String? = null): Set<String> = buildSet {
        pendingWrites.values.forEach { pending -> add(pending.stateFile.name) }
        additional?.let(::add)
    }

internal fun PersistentPrefixCacheStore.makeRoomFor(
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

internal fun PersistentPrefixCacheStore.evictToQuota(root: File, entries: List<StoredEntry>, quotaBytes: Long): Boolean {
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

internal fun PersistentPrefixCacheStore.totalBytes(entries: List<StoredEntry>): Long = entries.fold(0L) { total, entry ->
        saturatedAdd(total, entry.totalBytes)
    }

internal fun PersistentPrefixCacheStore.deleteEntry(root: File, entry: StoredEntry): Boolean {
        val metadataDeleted = deleteManagedFile(root, entry.metadataFile)
        val stateDeleted = deleteManagedFile(root, entry.stateFile)
        return metadataDeleted && stateDeleted
    }

internal fun PersistentPrefixCacheStore.verifyStoredEntry(entry: StoredEntry): Boolean =
        verifyStateFile(entry.stateFile, entry.stateSha256, entry.stateSizeBytes) &&
            regularFileSize(entry.metadataFile) == entry.metadataFileSizeBytes

internal fun PersistentPrefixCacheStore.verifyStateFile(
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

internal fun PersistentPrefixCacheStore.readMetadata(file: File): CacheMetadata? {
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

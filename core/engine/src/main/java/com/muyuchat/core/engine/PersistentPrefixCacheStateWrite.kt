package com.muyuchat.core.engine

import com.muyuchat.core.engine.PersistentPrefixCacheStore.PendingWrite
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal fun PersistentPrefixCacheStore.saveImpl(key: PrefixCacheKey, state: InputStream): PersistentPrefixCacheEntry? = synchronized(lock) {
        val root = resolveSafeRoot() ?: return@synchronized null
        val storeLock = acquireStoreLock(root) ?: return@synchronized null
        storeLock.use { saveLocked(root, key, state) }
    }

internal fun PersistentPrefixCacheStore.prepareWriteImpl(key: PrefixCacheKey): PendingWrite? = synchronized(lock) {
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

internal fun PersistentPrefixCacheStore.commitImpl(pending: PendingWrite): PersistentPrefixCacheEntry? = synchronized(lock) {
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

internal fun PersistentPrefixCacheStore.discardImpl(pending: PendingWrite): Boolean = synchronized(lock) {
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

internal fun PersistentPrefixCacheStore.saveLocked(
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
internal fun PersistentPrefixCacheStore.publishStagedState(
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

internal fun PersistentPrefixCacheStore.copyAndDigest(source: InputStream, target: File): StateDigest? {
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

internal fun PersistentPrefixCacheStore.digestStagedFile(file: File): StateDigest? {
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

internal fun PersistentPrefixCacheStore.syncStagedFile(file: File): Boolean = try {
        stateFileSyncer.sync(file)
        isRegularFileWithoutLinks(file)
    } catch (_: Exception) {
        false
    }

internal fun PersistentPrefixCacheStore.writeAtomically(
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

internal fun PersistentPrefixCacheStore.atomicMove(source: File, target: File): Boolean {
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

internal data class StateDigest(
    val sha256: String,
    val sizeBytes: Long
)

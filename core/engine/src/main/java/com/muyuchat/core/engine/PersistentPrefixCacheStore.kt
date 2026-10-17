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
    internal val rootDirectory: File,
    internal val maxBytes: Long,
    internal val clock: () -> Long,
    internal val stateFileSyncer: PrefixCacheStateFileSyncer
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
    fun save(key: PrefixCacheKey, state: InputStream): PersistentPrefixCacheEntry? = saveImpl(key, state)

    /** Convenience overload for runtimes that export state as a byte array. */
    fun save(key: PrefixCacheKey, state: ByteArray): PersistentPrefixCacheEntry? =
        save(key, state.inputStream())

    /**
     * Allocates a safe, same-directory staging file for a native state export.
     * Call [commit] after the native writer has closed the file, or [discard]
     * when generation is cancelled.
     */
    fun prepareWrite(key: PrefixCacheKey): PendingWrite? = prepareWriteImpl(key)

    /**
     * Verifies a native-written staging file and publishes state before its
     * metadata. A failed commit remains a cache miss and never throws into the
     * inference path.
     */
    fun commit(pending: PendingWrite): PersistentPrefixCacheEntry? = commitImpl(pending)

    /** Explicitly abandons a native staging file that will not be committed. */
    fun discard(pending: PendingWrite): Boolean = discardImpl(pending)

    /**
     * Returns a verified state file and records an LRU access. A null result is
     * a cache miss; callers must continue with an ordinary prompt prefill.
     */
    fun load(key: PrefixCacheKey): PersistentPrefixCacheEntry? = loadImpl(key)

    /** Returns all verified entries, newest access first, without mutating their LRU order. */
    fun entries(): List<PersistentPrefixCacheEntry> = entriesImpl()

    /** Returns verified entry count and byte usage without exposing cache keys to the UI. */
    fun summary(): PersistentPrefixCacheSummary {
        return summaryImpl()
    }

    /** Removes every managed cache artifact beneath the supplied root. */
    fun clear(): Boolean = clearImpl()

    /** Removes one key and every managed state version associated with it. */
    fun clear(key: PrefixCacheKey): Boolean = clearImpl(key)

    /** Removes every verified cache entry for the supplied conversation fingerprints. */
    fun clearPrefixFingerprints(fingerprints: Set<String>): Boolean = clearPrefixFingerprintsImpl(fingerprints)

    /** Trims verified entries to the configured quota using least-recently-used eviction. */
    fun trimToQuota(): Boolean = trimToQuotaImpl()

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

    }

    internal val lock = Any()
    internal val pendingWrites = LinkedHashMap<String, PendingWrite>()
}

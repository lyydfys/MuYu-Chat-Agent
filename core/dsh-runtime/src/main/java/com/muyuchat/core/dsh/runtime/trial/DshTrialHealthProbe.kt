package com.muyuchat.core.dsh.runtime.trial

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/** Positive, catalogue-only observation of the isolated home. */
internal data class DshTrialHomeProof(
    val readable: Boolean,
    val detail: String
) {
    companion object {
        /** Official profile directory of the seeded template (`DshGatewayConfigSeeder`). */
        private const val PROFILE_DIR_RELATIVE = "profiles/web"

        /** MCA-managed profile file the seeder always writes for the current template version. */
        private const val PROFILE_FILE = "cordis.yml"

        private const val CONFIG_DIR = "config"

        /**
         * A readable directory is not a proof by itself: this reads one required profile file and
         * lists the official config directory, and reports false with a categorical reason when
         * any of that fails.
         */
        fun prove(home: File): DshTrialHomeProof {
            if (!home.isDirectory) return DshTrialHomeProof(false, "the isolated home is absent")
            if (!home.canRead()) return DshTrialHomeProof(false, "the isolated home is not readable")
            val entries = home.list()
                ?: return DshTrialHomeProof(false, "the isolated home cannot be listed")
            if (entries.isEmpty()) return DshTrialHomeProof(false, "the isolated home is empty")
            val profile = File(File(home, PROFILE_DIR_RELATIVE), PROFILE_FILE)
            if (!profile.isFile) return DshTrialHomeProof(false, "the official profile file is absent")
            val text = runCatching { profile.readText(Charsets.UTF_8) }.getOrNull()
                ?: return DshTrialHomeProof(false, "the official profile file is unreadable")
            if (text.isEmpty()) return DshTrialHomeProof(false, "the official profile file is empty")
            val config = File(home, CONFIG_DIR)
            if (!config.isDirectory) return DshTrialHomeProof(false, "the official config directory is absent")
            if (config.list() == null) return DshTrialHomeProof(false, "the official config directory is unreadable")
            return DshTrialHomeProof(true, "isolated home and official profile are readable")
        }
    }
}

/** What one bounded session-container probe proved. */
internal data class DshTrialSessionProof(
    val verified: Boolean,
    val sessions: Int,
    val detail: String,
    /** The official session store directory exists and is readable (an empty store is valid). */
    val storeReady: Boolean = false
)

/**
 * Bounded, content-free proof that the isolated home holds a *real* official session log.
 *
 * Scope of the proof: the official session store layout (`sessions/<slug>/<session>/session*.jsonl.zstd`)
 * and the official **frame container** — the same structural scan the kernel uses (frame magic,
 * frame header descriptor, block headers, optional checksum). A structurally complete concatenation
 * of Zstandard frames is exactly what the official reader consumes; a truncated tail is reported
 * but does not invalidate the frames before it (the kernel truncates a torn tail the same way).
 *
 * The header *record* decode (type/version of the first JSONL line) stays with the official codec
 * owned by `:core:dsh-maintenance`; this module must not re-implement a zstd decoder. The reported
 * detail says which of the two proofs was performed, so no caller can mistake it for a decoded
 * header.
 */
internal object DshTrialSessionReader {

    private val SESSION_FILE_NAME = Regex("^session(\\.[A-Za-z0-9]+)?\\.jsonl\\.zstd$")

    /** The kernel's current container name. Preferred when several logs exist. */
    private const val CURRENT_FILE_NAME = "session.v4.jsonl.zstd"

    private const val SESSIONS_DIR = "sessions"
    private const val MAX_PROBED_FILES = 5

    /** Only the container head is needed: frame 0 is the header record, frame 1 the first batch. */
    private const val MAX_PROBED_BYTES = 8L * 1024L * 1024L
    private const val MAX_SCANNED_FRAMES = 2

    fun probe(home: File, expectSessions: Boolean = true): DshTrialSessionProof {
        val sessionsDir = File(home, SESSIONS_DIR)
        if (!sessionsDir.isDirectory) {
            return DshTrialSessionProof(false, 0, "the isolated home holds no session store")
        }
        val logs = runCatching { collectLogs(sessionsDir) }.getOrElse {
            return DshTrialSessionProof(false, 0, "the isolated session store cannot be listed")
        }
        if (logs.isEmpty()) {
            if (!expectSessions) {
                // New-user trial: the positive fact is that the official store exists and can be
                // read empty. No session file is fabricated.
                return DshTrialSessionProof(
                    verified = true,
                    sessions = 0,
                    detail = "official session store initialized (empty; no production session to migrate)",
                    storeReady = true
                )
            }
            return DshTrialSessionProof(
                verified = false,
                sessions = 0,
                detail = "the isolated home holds no committed session log",
                storeReady = true
            )
        }
        var lastDetail = "no session log could be verified"
        for (log in logs.take(MAX_PROBED_FILES)) {
            val bytes = readHead(log)
                ?: run {
                    lastDetail = "a session log could not be read"
                    continue
                }
            when (val scan = DshZstdFrameContainer.scan(bytes, MAX_SCANNED_FRAMES)) {
                is DshZstdFrameContainer.Result.Invalid -> {
                    lastDetail = "a session log container is structurally invalid"
                }

                is DshZstdFrameContainer.Result.Scanned -> {
                    if (scan.frames.isEmpty()) {
                        lastDetail = "a session log holds no complete frame"
                        continue
                    }
                    return DshTrialSessionProof(
                        verified = true,
                        sessions = logs.size,
                        detail = if (scan.tornTail) {
                            "official session container verified (${scan.frames.size} complete frame(s), torn tail)"
                        } else {
                            "official session container verified (${scan.frames.size} complete frame(s))"
                        },
                        storeReady = true
                    )
                }
            }
        }
        return DshTrialSessionProof(false, logs.size, lastDetail, storeReady = true)
    }

    /** Session logs below `sessions/<slug>/<session>/`, current format first, bounded. */
    private fun collectLogs(sessionsDir: File): List<File> {
        val slugs = sessionsDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        val found = ArrayList<File>(8)
        for (slug in slugs.sortedBy { it.name }) {
            if (Files.isSymbolicLink(slug.toPath())) continue
            val sessions = slug.listFiles { file -> file.isDirectory } ?: continue
            for (session in sessions.sortedBy { it.name }) {
                if (Files.isSymbolicLink(session.toPath())) continue
                val logs = session.listFiles { file ->
                    file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                        SESSION_FILE_NAME.matches(file.name)
                } ?: continue
                found.addAll(logs.sortedBy { if (it.name == CURRENT_FILE_NAME) 0 else 1 })
            }
        }
        return found
    }

    private fun readHead(file: File): ByteArray? = runCatching {
        if (!file.isFile || file.length() <= 0L) return null
        RandomAccessFile(file, "r").use { handle ->
            val budget = minOf(file.length(), MAX_PROBED_BYTES).toInt()
            if (budget <= 0) return null
            val buffer = ByteArray(budget)
            var read = 0
            while (read < budget) {
                val n = handle.read(buffer, read, budget - read)
                if (n <= 0) break
                read += n
            }
            if (read <= 0) null else if (read == buffer.size) buffer else buffer.copyOf(read)
        }
    }.getOrNull()
}

/**
 * Structural scan of a concatenated-frame Zstandard artifact (frame layer only).
 *
 * Byte-for-byte the same walk the pinned official persistence backend performs, and the same one
 * ported for the maintenance codec: it never splits on the magic bytes (compressed payloads may
 * contain them), walks frame headers, block headers and the optional content checksum, and reports
 * an incomplete final frame instead of guessing. Reserved bits and a bad magic are structural
 * corruption, not something to repair by searching for the next magic sequence.
 */
internal object DshZstdFrameContainer {

    private const val MAGIC: Long = 0xFD2FB528L

    data class Frame(val start: Long, val end: Long, val checksummed: Boolean)

    sealed interface Result {
        data class Scanned(val frames: List<Frame>, val tornTail: Boolean) : Result

        data class Invalid(val reason: String) : Result
    }

    fun scan(bytes: ByteArray, maxFrames: Int = Int.MAX_VALUE): Result {
        val frames = ArrayList<Frame>(minOf(maxFrames, 4))
        var offset = 0
        while (offset < bytes.size) {
            val start = offset
            if (bytes.size - offset < 4) return Result.Scanned(frames, tornTail = true)
            if (readUInt32LE(bytes, offset) != MAGIC) return Result.Invalid("invalid frame magic")
            offset += 4
            if (offset == bytes.size) return Result.Scanned(frames, tornTail = true)
            val descriptor = bytes[offset].toInt() and 0xff
            offset += 1
            if ((descriptor and 24) != 0) return Result.Invalid("reserved frame-header bit")
            val contentSizeFlag = descriptor ushr 6
            val singleSegment = (descriptor and 32) != 0
            val checksum = (descriptor and 4) != 0
            val dictionaryFlag = descriptor and 3
            val dictionaryBytes = if (dictionaryFlag == 3) 4 else dictionaryFlag
            val contentSizeBytes = when (contentSizeFlag) {
                0 -> if (singleSegment) 1 else 0
                else -> 1 shl contentSizeFlag
            }
            val remainingHeaderBytes = (if (singleSegment) 0 else 1) + dictionaryBytes + contentSizeBytes
            if (bytes.size - offset < remainingHeaderBytes) return Result.Scanned(frames, tornTail = true)
            offset += remainingHeaderBytes
            while (true) {
                if (bytes.size - offset < 3) return Result.Scanned(frames, tornTail = true)
                val blockHeader = readUIntLE(bytes, offset, 3)
                offset += 3
                val lastBlock = (blockHeader and 1L) != 0L
                val blockType = ((blockHeader ushr 1) and 3L).toInt()
                val blockSize = (blockHeader ushr 3).toInt()
                if (blockType == 3) return Result.Invalid("reserved block type")
                val payloadBytes = if (blockType == 1) 1 else blockSize
                if (bytes.size - offset < payloadBytes) return Result.Scanned(frames, tornTail = true)
                offset += payloadBytes
                if (lastBlock) break
            }
            if (checksum) {
                if (bytes.size - offset < 4) return Result.Scanned(frames, tornTail = true)
                offset += 4
            }
            frames.add(Frame(start.toLong(), offset.toLong(), checksum))
            if (frames.size == maxFrames) return Result.Scanned(frames, tornTail = false)
        }
        return Result.Scanned(frames, tornTail = false)
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun readUIntLE(bytes: ByteArray, offset: Int, count: Int): Long {
        var value = 0L
        for (index in 0 until count) {
            value = value or ((bytes[offset + index].toLong() and 0xff) shl (8 * index))
        }
        return value
    }
}

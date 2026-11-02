package com.muyuchat.core.dsh.maintenance

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject

/**
 * Official `session.v4.jsonl.zstd` reading/writing (D21 / OPS-11).
 *
 * The container is a concatenation of independent Zstandard frames written by the pinned official
 * persistence backend: frame 0 is exactly one newline-terminated v4 header record, later frames
 * are JSONL event batches, and a crash may leave a torn final frame. The kernel reads complete
 * frames only and truncates the torn tail, so this codec does the same:
 *
 * - [DshZstdFrameScanner] is a byte-exact port of the official structural frame scan. It never
 *   splits on the magic bytes (compressed payloads may contain them); it walks frame headers,
 *   block headers and the optional content checksum, and reports the start of an incomplete final
 *   frame instead of guessing.
 * - [DshNodeSessionCodec] performs the actual inflate/deflate through a bundled Node helper that
 *   uses `node:zlib` zstd (the payload already ships a Node with that API). Kotlin keeps the frame
 *   boundaries and the original bytes, so untouched frames are written back verbatim and only a
 *   frame that contains a proven repair is re-encoded.
 *
 * Nothing here logs session content, and every failure code is stable and content-free.
 */

/** One structurally complete frame, as offsets into the artifact (`end` exclusive). */
data class DshZstdFrame(val start: Long, val end: Long, val checksummed: Boolean)

/** Complete frames plus the start of a structurally incomplete final frame, if any. */
data class DshZstdScan(val frames: List<DshZstdFrame>, val tornStart: Long? = null)

/**
 * Independent structural scan of a concatenated-frame Zstandard artifact.
 *
 * This mirrors `scanZstdFrames` from the pinned official persistence backend. Throwing on invalid
 * magic or reserved header bits is deliberate: a structurally invalid artifact must be refused,
 * not "repaired" by searching for the next magic sequence.
 */
object DshZstdFrameScanner {
    private const val MAGIC: Long = 0xFD2FB528L

    fun scan(bytes: ByteArray, maxFrames: Int = Int.MAX_VALUE): DshZstdScan {
        val frames = ArrayList<DshZstdFrame>(minOf(maxFrames, 64))
        var offset = 0
        while (offset < bytes.size) {
            val start = offset
            if (bytes.size - offset < 4) return DshZstdScan(frames, start.toLong())
            if (readUInt32LE(bytes, offset) != MAGIC) {
                throw DshSessionDecodeException("corrupt Zstandard session log: invalid frame magic at byte $offset")
            }
            offset += 4
            if (offset == bytes.size) return DshZstdScan(frames, start.toLong())
            val descriptor = bytes[offset].toInt() and 0xff
            offset += 1
            if ((descriptor and 24) != 0) {
                throw DshSessionDecodeException(
                    "corrupt Zstandard session log: reserved frame-header bit at byte ${offset - 1}"
                )
            }
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
            if (bytes.size - offset < remainingHeaderBytes) return DshZstdScan(frames, start.toLong())
            offset += remainingHeaderBytes
            while (true) {
                if (bytes.size - offset < 3) return DshZstdScan(frames, start.toLong())
                val blockHeader = readUIntLE(bytes, offset, 3)
                offset += 3
                val lastBlock = (blockHeader and 1L) != 0L
                val blockType = ((blockHeader ushr 1) and 3L).toInt()
                val blockSize = (blockHeader ushr 3).toInt()
                if (blockType == 3) {
                    throw DshSessionDecodeException(
                        "corrupt Zstandard session log: reserved block type at byte ${offset - 3}"
                    )
                }
                val payloadBytes = if (blockType == 1) 1 else blockSize
                if (bytes.size - offset < payloadBytes) return DshZstdScan(frames, start.toLong())
                offset += payloadBytes
                if (lastBlock) break
            }
            if (checksum) {
                if (bytes.size - offset < 4) return DshZstdScan(frames, start.toLong())
                offset += 4
            }
            frames.add(DshZstdFrame(start.toLong(), offset.toLong(), checksum))
            if (frames.size == maxFrames) return DshZstdScan(frames)
        }
        return DshZstdScan(frames)
    }

    /** True when [bytes] is exactly one complete, checksummed frame. */
    fun isSingleChecksummedFrame(bytes: ByteArray): Boolean {
        val scan = runCatching { scan(bytes, 2) }.getOrNull() ?: return false
        return scan.tornStart == null && scan.frames.size == 1 && scan.frames.single().checksummed
    }

    fun rejectOversized(bytes: ByteArray, maxSessionBytes: Long) {
        if (bytes.size.toLong() > maxSessionBytes) throw DshSessionDecodeException("session exceeds the byte budget")
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

/** Positive proof that a real session header was decoded, or an honest reason why not. */
data class DshSessionHeaderProof(
    val verified: Boolean,
    val detail: String,
    val version: Int = 0
) {
    companion object {
        val NOT_VERIFIED = DshSessionHeaderProof(false, "no session header was read")
        val CODEC_UNAVAILABLE = DshSessionHeaderProof(false, "official session codec is not wired in this build")
        val NO_SESSIONS = DshSessionHeaderProof(false, "no session log exists yet")
    }
}

/**
 * Message/event counts over the official v4 event vocabulary, without returning content.
 *
 * [messageCount] is null when the artifact could not be counted; callers must then surface an
 * unknown count instead of a fabricated zero.
 */
data class DshSessionStats(
    val messageCount: Int?,
    val eventCount: Int = 0,
    val userMessages: Int = 0,
    val assistantMessages: Int = 0,
    val tornTail: Boolean = false,
    val tornTailBytes: Long = 0L,
    val headerVersion: Int = 0,
    val failure: String? = null
)

/** Result of one helper process run. `stderr` is already bounded by the caller. */
data class DshNodeHelperResult(val exitCode: Int, val stderr: String = "")

/**
 * Platform seam: executes the bundled helper with the payload's Node.
 *
 * The app composition supplies the real process runner (Node executable from `nativeLibraryDir`,
 * payload library path, isolated TMPDIR). JVM tests substitute a fake; no Android type leaks in.
 */
fun interface DshNodeHelperRunner {
    fun run(helper: File, args: List<String>, workDir: File, timeoutMillis: Long): DshNodeHelperResult
}

/**
 * [DshSessionCodec] backed by the bundled Node helper, with the frame container owned here.
 *
 * Every operation uses a private temporary directory below [workDirectory] and deletes it after
 * the helper exits. The helper never receives a path outside that directory and never writes to
 * the session itself: [encodeFrame] returns bytes, the maintenance engine performs the only write.
 */
class DshNodeSessionCodec(
    private val runner: DshNodeHelperRunner,
    private val helper: File,
    private val workDirectory: File,
    private val maxSessionBytes: Long = 256L * 1024L * 1024L,
    private val timeoutMillis: Long = 120_000L
) : DshSessionCodec {

    override fun decode(bytes: ByteArray): DshSessionContent {
        DshZstdFrameScanner.rejectOversized(bytes, maxSessionBytes)
        val scan = DshZstdFrameScanner.scan(bytes)
        if (scan.frames.isEmpty()) throw DshSessionDecodeException("empty or header-less session log")
        val response = call("decode", inputs = mapOf("input.bin" to bytes)) { temp ->
            JSONObject().put("input", File(temp, "input.bin").absolutePath)
        }
        if (!response.optBoolean("ok", false)) throw DshSessionDecodeException(failureText(response))
        validateHeaderRecord(response)
        val reported = response.optJSONArray("frames")
            ?: throw DshSessionDecodeException("session codec returned no frames")
        if (reported.length() != scan.frames.size) {
            throw DshSessionDecodeException("frame scan disagreement between Kotlin and the official reader")
        }
        val frames = ArrayList<DshSessionFrame>(scan.frames.size)
        for (index in 0 until reported.length()) {
            val item = reported.getJSONObject(index)
            val expected = scan.frames[index]
            if (item.optLong("start") != expected.start || item.optLong("end") != expected.end) {
                throw DshSessionDecodeException("frame boundary disagreement at frame $index")
            }
            val error = item.optString("error").takeIf { it.isNotEmpty() }
            frames.add(
                DshSessionFrame(
                    index = index,
                    text = if (error == null) item.optString("text") else "",
                    decodable = error == null,
                    raw = bytes.copyOfRange(expected.start.toInt(), expected.end.toInt()),
                    detail = error.orEmpty()
                )
            )
        }
        val torn = response.optJSONObject("tornTail")
        return DshSessionContent(
            formatId = response.optString("formatId", "session.v4.jsonl.zstd"),
            frames = frames,
            tornTailStart = torn?.takeIf { it.has("start") }?.optLong("start"),
            tornTailBytes = torn?.optLong("bytes", 0L) ?: 0L,
            tornTailLines = torn?.optInt("recoveredLines", 0) ?: 0
        )
    }

    /** One checksummed, independently decodable frame. Unchanged frames never pass through here. */
    override fun encodeFrame(text: String): ByteArray {
        val input = text.toByteArray(Charsets.UTF_8)
        if (input.isEmpty()) throw DshSessionDecodeException("refusing to encode an empty session frame")
        val response = call(
            op = "encode",
            inputs = mapOf("frame.txt" to input),
            outputs = listOf("frame.zst")
        ) { temp ->
            JSONObject()
                .put("input", File(temp, "frame.txt").absolutePath)
                .put("output", File(temp, "frame.zst").absolutePath)
        }
        if (!response.optBoolean("ok", false)) throw DshSessionDecodeException(failureText(response))
        val encoded = response.output("frame.zst")
        if (!DshZstdFrameScanner.isSingleChecksummedFrame(encoded)) {
            throw DshSessionDecodeException("encoded session frame is not one complete checksummed frame")
        }
        return encoded
    }

    override fun inspectHeader(file: File): DshSessionHeaderProof {
        if (!file.isFile) return DshSessionHeaderProof(false, "session log is absent")
        if (file.length() <= 0L || file.length() > maxSessionBytes) {
            return DshSessionHeaderProof(false, "session log is outside the readable byte budget")
        }
        val response = try {
            call("header", inputs = mapOf("session.bin" to file.readBytes())) { temp ->
                JSONObject().put("input", File(temp, "session.bin").absolutePath)
            }
        } catch (error: Exception) {
            return DshSessionHeaderProof(false, "session header could not be decoded")
        }
        if (!response.optBoolean("ok", false)) {
            return DshSessionHeaderProof(false, failureText(response))
        }
        val header = response.optJSONObject("header")
            ?: return DshSessionHeaderProof(false, "session header record is missing")
        val version = header.optInt("version", 0)
        return DshSessionHeaderProof(true, "header version=$version", version)
    }

    /**
     * Batch message/event counts. One helper process covers every cache miss of the console
     * projection; a per-file failure is returned as unknown instead of failing the whole list.
     */
    override fun stats(files: List<File>): List<DshSessionStats?> {
        if (files.isEmpty()) return emptyList()
        if (files.size > BATCH_MAX_FILES) return List(files.size) { null }
        val readable = files.map { file ->
            if (file.isFile && file.length() in 1..maxSessionBytes) file else null
        }
        val present = readable.filterNotNull()
        if (present.isEmpty()) return List(files.size) { null }
        val names = present.indices.associateWith { index -> "session-$index.zst" }
        val inputs = LinkedHashMap<String, ByteArray>()
        names.forEach { (index, name) -> inputs[name] = present[index].readBytes() }
        val response = try {
            call("stats", inputs = inputs) { temp ->
                JSONObject().put(
                    "files",
                    JSONArray().apply {
                        names.toSortedMap().values.forEach { name ->
                            put(File(temp, name).absolutePath)
                        }
                    }
                )
            }
        } catch (error: Exception) {
            return List(files.size) { null }
        }
        if (!response.optBoolean("ok", false)) return List(files.size) { null }
        val sessions = response.optJSONArray("sessions") ?: return List(files.size) { null }
        val byPosition = HashMap<Int, DshSessionStats>()
        for (index in 0 until sessions.length()) {
            val item = sessions.getJSONObject(index)
            val position = item.optInt("index", -1)
            if (position !in present.indices) continue
            byPosition[position] = if (!item.optBoolean("ok", false)) {
                DshSessionStats(
                    messageCount = null,
                    headerVersion = item.optJSONObject("header")?.optInt("version", 0) ?: 0,
                    failure = item.optString("code", "UNKNOWN")
                )
            } else {
                DshSessionStats(
                    messageCount = item.optInt("messageCount", 0),
                    eventCount = item.optInt("eventCount", 0),
                    userMessages = item.optInt("userMessages", 0),
                    assistantMessages = item.optInt("assistantMessages", 0),
                    tornTail = item.optBoolean("tornTail", false),
                    tornTailBytes = item.optLong("tornTailBytes", 0L),
                    headerVersion = item.optJSONObject("header")?.optInt("version", 0) ?: 0
                )
            }
        }
        var presentCursor = 0
        return files.mapIndexed { index, _ ->
            if (readable[index] == null) return@mapIndexed null
            val stats = byPosition[presentCursor]
            presentCursor += 1
            stats
        }
    }

    // ------------------------------------------------------------------ helper protocol

    /**
     * Runs one helper operation. [bind] receives the private temp directory so every path in the
     * request stays absolute and inside it; [inputs] are written before the helper starts and
     * [outputs] are read back into the response object.
     */
    private fun call(
        op: String,
        inputs: Map<String, ByteArray> = emptyMap(),
        outputs: List<String> = emptyList(),
        bind: (File) -> JSONObject
    ): JSONObject {
        if (!helper.isFile) throw DshSessionDecodeException("session codec helper is missing")
        if (!workDirectory.isDirectory && !workDirectory.mkdirs() && !workDirectory.isDirectory) {
            throw DshSessionDecodeException("session codec work directory is unavailable")
        }
        val temp = Files.createTempDirectory(workDirectory.toPath(), "codec-").toFile()
        try {
            for ((name, bytes) in inputs) File(temp, name).writeBytes(bytes)
            val requestFile = File(temp, "request.json")
            requestFile.writeText(bind(temp).toString(), Charsets.UTF_8)
            val responseFile = File(temp, "response.json")
            val result = runner.run(
                helper,
                listOf(op, requestFile.absolutePath, responseFile.absolutePath),
                temp,
                timeoutMillis
            )
            if (!responseFile.isFile) {
                throw DshSessionDecodeException("session codec helper produced no response (exit=${result.exitCode})")
            }
            if (responseFile.length() > MAX_RESPONSE_BYTES) {
                throw DshSessionDecodeException("session codec response exceeds the byte budget")
            }
            val parsed = JSONObject(responseFile.readText(Charsets.UTF_8))
            for (name in outputs) {
                val output = File(temp, name)
                if (output.isFile) parsed.put(OUTPUT_PREFIX + name, output.readBytes())
            }
            if (result.exitCode != 0 && parsed.optBoolean("ok", false)) {
                throw DshSessionDecodeException("session codec helper reported success with exit ${result.exitCode}")
            }
            return parsed
        } catch (error: DshSessionDecodeException) {
            throw error
        } catch (error: Exception) {
            throw DshSessionDecodeException("session codec helper failed: ${error.javaClass.simpleName}")
        } finally {
            deleteTree(temp)
        }
    }

    private fun JSONObject.output(name: String): ByteArray {
        val value = opt(OUTPUT_PREFIX + name)
            ?: throw DshSessionDecodeException("session codec helper did not produce $name")
        return value as? ByteArray ?: throw DshSessionDecodeException("session codec output has the wrong shape")
    }

    private fun validateHeaderRecord(response: JSONObject) {
        val header = response.optJSONObject("header")
            ?: throw DshSessionDecodeException("session log has no header record")
        if (header.optString("type") != "session") {
            throw DshSessionDecodeException("session log header is not a session record")
        }
        val version = header.optInt("version", 0)
        if (version !in 1..4) throw DshSessionDecodeException("session log version is not supported")
    }

    private fun failureText(response: JSONObject): String {
        val code = response.optString("code", "UNKNOWN")
        val detail = response.optString("detail", "").take(160)
        return if (detail.isEmpty()) "session codec refused: $code" else "session codec refused: $code ($detail)"
    }

    private fun deleteTree(file: File) {
        if (!file.exists()) return
        file.listFiles()?.forEach(::deleteTree)
        if (!file.delete()) file.deleteOnExit()
    }

    private companion object {
        const val OUTPUT_PREFIX = "output:"
        const val MAX_RESPONSE_BYTES = 64L * 1024L * 1024L
        const val BATCH_MAX_FILES = 64
    }
}

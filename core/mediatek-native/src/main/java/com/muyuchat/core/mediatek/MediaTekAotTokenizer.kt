package com.muyuchat.core.mediatek

import java.io.File
import java.text.Normalizer
import kotlin.math.min

/**
 * ATOK v2 tokenizer used by the reference MediaTek Anima package.
 *
 * The container holds a 256-entry byte-to-token table, 151,387 BPE merges and a
 * 32,100-piece T5 unigram vocabulary. It has no length prefixes between
 * sections; the order is fixed and each section is delimited by its count.
 */
class MediaTekAotTokenizer private constructor(
    private val byteToToken: IntArray,
    private val mergeRank: HashMap<Long, Int>,
    private val t5Pieces: List<String>,
    private val t5Scores: FloatArray,
    private val t5Lookup: HashMap<String, Int>,
    private val unknownId: Int,
    private val minimumScore: Float,
) {
    val mergeCount: Int get() = mergeRank.size
    val pieceCount: Int get() = t5Pieces.size
    val unkId: Int get() = unknownId

    fun byteToken(byte: Int): Int = byteToToken[byte and 0xff]

    fun qwenPieces(text: String): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val length = normalized.length
        val out = ArrayList<String>(64)
        var index = 0
        while (index < length) {
            val start = index
            var contraction = false
            for (candidate in CONTRACTIONS) {
                val end = start + candidate.length
                if (end <= length && normalized.regionMatches(start, candidate, 0, candidate.length, ignoreCase = true)) {
                    out += normalized.substring(start, end)
                    index = end
                    contraction = true
                    break
                }
            }
            if (contraction) continue

            var end = if (isNewline(normalized[start]) || isLetter(normalized[start]) || isNumber(normalized[start])) {
                start
            } else {
                start + 1
            }
            if (end < length && isLetter(normalized[end])) {
                while (end < length && isLetter(normalized[end])) end++
                out += normalized.substring(start, end)
                index = end
                continue
            }
            if (isNumber(normalized[start])) {
                out += normalized.substring(start, start + 1)
                index = start + 1
                continue
            }

            var cursor = if (normalized[start] == ' ') start + 1 else start
            if (cursor < length && !isSpace(normalized[cursor]) && !isLetter(normalized[cursor]) && !isNumber(normalized[cursor])) {
                while (cursor < length && !isSpace(normalized[cursor]) && !isLetter(normalized[cursor]) && !isNumber(normalized[cursor])) {
                    cursor++
                }
                while (cursor < length && isNewline(normalized[cursor])) cursor++
                out += normalized.substring(start, cursor)
                index = cursor
                continue
            }

            cursor = start
            while (cursor < length && isSpace(normalized[cursor]) && !isNewline(normalized[cursor])) cursor++
            if (cursor < length && isNewline(normalized[cursor])) {
                while (cursor < length && isNewline(normalized[cursor])) cursor++
                out += normalized.substring(start, cursor)
                index = cursor
            } else if (isSpace(normalized[start])) {
                cursor = start
                while (cursor < length && isSpace(normalized[cursor])) cursor++
                if (cursor == length) {
                    out += normalized.substring(start, cursor)
                    index = cursor
                } else {
                    val last = cursor - 1
                    if (last > start) {
                        out += normalized.substring(start, last)
                        index = last
                    } else {
                        out += normalized.substring(start, cursor)
                        index = cursor
                    }
                }
            } else {
                index = start + 1
            }
        }
        return out
    }

    fun t5Pieces(text: String): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = ArrayList<String>(32)
        val builder = StringBuilder()
        for (character in normalized) {
            if (Character.isWhitespace(character)) {
                if (builder.isNotEmpty()) {
                    out += "\u2581$builder"
                    builder.setLength(0)
                }
            } else {
                builder.append(character)
            }
        }
        if (builder.isNotEmpty()) out += "\u2581$builder"
        if (out.isEmpty()) out += "\u2581"
        return out
    }

    fun encodeQwen(text: String): IntArray = encodeQwen(qwenPieces(text))

    fun encodeQwen(pieces: List<String>): IntArray {
        val out = IntArrayBuilder()
        for (piece in pieces) {
            val bytes = piece.toByteArray(Charsets.UTF_8)
            val ids = IntArray(bytes.size) { index -> byteToToken[bytes[index].toInt() and 0xff] }
            out.addAll(merge(ids))
        }
        return out.toArray()
    }

    fun encodeT5(text: String): IntArray = encodeT5(t5Pieces(text))

    fun encodeT5(pieces: List<String>): IntArray {
        val out = IntArrayBuilder()
        for (piece in pieces) out.addAll(encodeT5Piece(piece))
        return out.toArray()
    }

    /**
     * Mirrors nativeEncodePrompt: Qwen ids truncate to lk, T5 ids truncate to
     * lk-1 and receive EOS=1, and an empty Qwen stream becomes 151643.
     */
    fun encodePrompt(prompt: String, lk: Int): PromptTokens {
        require(lk >= 2) { "lk must be at least 2" }
        val qwen = encodeQwen(prompt)
        val t5 = encodeT5(prompt)
        val qwenOut = if (qwen.isEmpty()) intArrayOf(QWEN_END_OF_TEXT) else qwen.copyOf(min(qwen.size, lk))
        val t5Out = IntArray(min(t5.size, lk - 1) + 1)
        System.arraycopy(t5, 0, t5Out, 0, t5Out.size - 1)
        t5Out[t5Out.size - 1] = T5_EOS_ID
        return PromptTokens(qwenOut, t5Out)
    }

    private fun merge(ids: IntArray): IntArray {
        if (ids.size < 2) return ids
        val current = ArrayList<Int>(ids.size)
        ids.forEach { current.add(it) }
        while (current.size > 1) {
            var bestIndex = -1
            var bestRank = Int.MAX_VALUE
            var bestResult = -1
            for (index in 0 until current.size - 1) {
                val rank = mergeRank[key(current[index], current[index + 1])] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = index
                    bestResult = 256 + rank
                }
            }
            if (bestIndex < 0) break
            current[bestIndex] = bestResult
            current.removeAt(bestIndex + 1)
        }
        return IntArray(current.size) { current[it] }
    }

    private fun encodeT5Piece(piece: String): IntArray {
        val bytes = piece.toByteArray(Charsets.UTF_8)
        val size = bytes.size
        val best = DoubleArray(size + 1) { Double.NEGATIVE_INFINITY }
        val previous = IntArray(size + 1) { -1 }
        val previousUnknown = BooleanArray(size + 1)
        best[0] = 0.0
        val unknownScore = minimumScore.toDouble() - 10.0
        for (start in 0 until size) {
            if (best[start] == Double.NEGATIVE_INFINITY) continue
            val limit = min(size, start + MAX_PIECE_BYTES)
            for (end in start + 1..limit) {
                if (end < size && isContinuation(bytes[end])) continue
                val candidate = String(bytes, start, end - start, Charsets.UTF_8)
                val id = t5Lookup[candidate] ?: continue
                val score = best[start] + t5Scores[id]
                if (score > best[end]) {
                    best[end] = score
                    previous[end] = start
                    previousUnknown[end] = false
                }
            }
            var next = start + 1
            while (next < size && isContinuation(bytes[next])) next++
            val score = best[start] + unknownScore
            if (score > best[next]) {
                best[next] = score
                previous[next] = start
                previousUnknown[next] = true
            }
        }
        val reversed = ArrayList<Int>()
        var cursor = size
        while (cursor > 0) {
            val start = previous[cursor]
            require(start >= 0) { "T5 unigram failed to consume $piece" }
            reversed += if (previousUnknown[cursor]) unknownId else t5Lookup.getValue(String(bytes, start, cursor - start, Charsets.UTF_8))
            cursor = start
        }
        val result = ArrayList<Int>(reversed.size)
        for (index in reversed.indices.reversed()) {
            val id = reversed[index]
            if (id == unknownId && result.isNotEmpty() && result.last() == unknownId) continue
            result += id
        }
        return IntArray(result.size) { result[it] }
    }

    private fun key(left: Int, right: Int): Long =
        (left.toLong() shl 32) or (right.toLong() and 0xffffffffL)

    private class IntArrayBuilder(initial: Int = 256) {
        private var values = IntArray(initial)
        private var size = 0
        fun addAll(items: IntArray) {
            ensure(size + items.size)
            System.arraycopy(items, 0, values, size, items.size)
            size += items.size
        }
        fun toArray(): IntArray = values.copyOf(size)
        private fun ensure(capacity: Int) {
            if (capacity <= values.size) return
            var next = values.size * 2
            while (next < capacity) next *= 2
            values = values.copyOf(next)
        }
    }

    data class PromptTokens(val qwen: IntArray, val t5: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is PromptTokens && qwen.contentEquals(other.qwen) && t5.contentEquals(other.t5)
        override fun hashCode(): Int = 31 * qwen.contentHashCode() + t5.contentHashCode()
    }

    companion object {
        const val MAGIC = 0x4B4F5441
        const val VERSION = 2
        const val QWEN_END_OF_TEXT = 151643
        const val T5_EOS_ID = 1
        private const val MAX_PIECE_BYTES = 20
        private val CONTRACTIONS = arrayOf("'s", "'t", "'re", "'ve", "'m", "'ll", "'d")

        fun parse(file: File): MediaTekAotTokenizer = parse(file.readBytes())

        fun parse(bytes: ByteArray): MediaTekAotTokenizer {
            require(bytes.size > 0x40f) { "ATOK container is too small" }
            require(readInt(bytes, 0) == MAGIC) { "not an ATOK container" }
            require(readInt(bytes, 4) == VERSION) { "unsupported ATOK version ${readInt(bytes, 4)}" }

            val byteToToken = IntArray(256) { index -> readInt(bytes, 8 + index * 4) }
            require(byteToToken.toSet().size == 256) { "ATOK byte table is not a permutation" }

            var offset = 0x408
            val mergeCount = readInt(bytes, offset)
            offset += 4
            require(mergeCount in 1..1_000_000) { "invalid ATOK merge count $mergeCount" }
            val mergeRank = HashMap<Long, Int>(mergeCount * 2)
            for (index in 0 until mergeCount) {
                require(offset + 12 <= bytes.size) { "truncated ATOK merge table" }
                val left = readInt(bytes, offset)
                val right = readInt(bytes, offset + 4)
                val result = readInt(bytes, offset + 8)
                offset += 12
                require(result == 256 + index) { "unexpected ATOK merge result $result at $index" }
                mergeRank[(left.toLong() shl 32) or (right.toLong() and 0xffffffffL)] = index
            }

            val pieceCount = readInt(bytes, offset)
            offset += 4
            require(pieceCount in 1..1_000_000) { "invalid ATOK piece count $pieceCount" }
            val pieces = ArrayList<String>(pieceCount)
            val scores = FloatArray(pieceCount)
            val lookup = HashMap<String, Int>(pieceCount * 2)
            for (index in 0 until pieceCount) {
                require(offset + 2 <= bytes.size) { "truncated ATOK piece table" }
                val length = readShort(bytes, offset)
                offset += 2
                require(offset + length + 4 <= bytes.size) { "truncated ATOK piece $index" }
                val piece = String(bytes, offset, length, Charsets.UTF_8)
                offset += length
                val score = Float.fromBits(readInt(bytes, offset))
                offset += 4
                pieces += piece
                scores[index] = score
                lookup[piece] = index
            }
            require(offset + 4 == bytes.size) { "ATOK container has ${bytes.size - offset - 4} trailing bytes" }
            val unknownId = readInt(bytes, offset)
            require(unknownId in 0 until pieceCount) { "invalid ATOK unknown id $unknownId" }
            val minimumScore = scores.min()
            return MediaTekAotTokenizer(
                byteToToken = byteToToken,
                mergeRank = mergeRank,
                t5Pieces = pieces,
                t5Scores = scores,
                t5Lookup = lookup,
                unknownId = unknownId,
                minimumScore = minimumScore,
            )
        }

        private fun readInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)

        private fun readShort(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun isContinuation(byte: Byte): Boolean = (byte.toInt() and 0xC0) == 0x80

        private fun isNewline(character: Char): Boolean = character == '\r' || character == '\n'

        private fun isLetter(character: Char): Boolean = Character.isLetter(character)

        private fun isNumber(character: Char): Boolean {
            val type = Character.getType(character)
            return type == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
                type == Character.LETTER_NUMBER.toInt() ||
                type == Character.OTHER_NUMBER.toInt()
        }

        private fun isSpace(character: Char): Boolean =
            Character.isWhitespace(character) || Character.isSpaceChar(character)
    }
}

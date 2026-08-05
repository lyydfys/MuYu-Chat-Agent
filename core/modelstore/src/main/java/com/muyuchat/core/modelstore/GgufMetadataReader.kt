package com.muyuchat.core.modelstore

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

object GgufMetadataReader {
    private val magic = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    fun read(file: File): GgufMetadata = file.inputStream().buffered().use { stream ->
        read(stream, file.name)
    }

    fun read(resolver: ContentResolver, uri: Uri, fileName: String): GgufMetadata =
        resolver.openInputStream(uri)?.buffered()?.use { stream ->
            read(stream, fileName)
        } ?: GgufMetadata(isGguf = false)

    internal fun read(stream: InputStream, fileName: String): GgufMetadata {
        val input = if (stream is BufferedInputStream) stream else BufferedInputStream(stream)
        val magicBytes = input.readPrefix(magic.size)
        val isGguf = magicBytes.contentEquals(magic)
        if (!isGguf) return GgufMetadata(isGguf = false)

        val version = runCatching { input.readUInt32Le().toInt() }.getOrNull()
        val parsed = runCatching { readMetadata(input, fileName) }.getOrElse {
            ParsedGgufMetadata(
                architecture = inferArchitecture(fileName),
                quant = inferQuant(fileName),
                fileType = null,
                causalAttention = null,
                poolingType = null,
                contextLength = null,
                nextnPredictLayers = null
            )
        }
        return GgufMetadata(
            isGguf = true,
            version = version,
            architecture = parsed.architecture ?: inferArchitecture(fileName),
            quant = parsed.quant ?: inferQuant(fileName),
            fileType = parsed.fileType,
            causalAttention = parsed.causalAttention,
            poolingType = parsed.poolingType,
            contextLength = parsed.contextLength,
            nextnPredictLayers = parsed.nextnPredictLayers
        )
    }

    private fun readMetadata(input: InputStream, fileName: String): ParsedGgufMetadata {
        input.readUInt64Le()
        val metadataCount = input.readUInt64Le().coerceAtMost(MAX_METADATA_KEYS)
        var architecture: String? = null
        var fileType: Int? = null
        val causalAttentionByArchitecture = mutableMapOf<String, Boolean>()
        val poolingTypeByArchitecture = mutableMapOf<String, Int>()
        val contextLengthByArchitecture = mutableMapOf<String, Int>()
        val nextnPredictLayersByArchitecture = mutableMapOf<String, Int>()

        for (index in 0 until metadataCount.toInt()) {
            val key = input.readGgufString()
            val type = input.readUInt32Le().toInt()
            when {
                key == "general.architecture" && type == GGUF_TYPE_STRING -> {
                    architecture = input.readGgufString().takeIf { it.isNotBlank() }
                }
                key == "general.file_type" && type.isIntegerType() -> {
                    fileType = input.readIntegerValue(type).toInt()
                }
                key.endsWith(ATTENTION_CAUSAL_SUFFIX) &&
                    (type == GGUF_TYPE_BOOL || type.isIntegerType()) -> {
                    val owner = key.removeSuffix(ATTENTION_CAUSAL_SUFFIX)
                    causalAttentionByArchitecture[owner] = if (type == GGUF_TYPE_BOOL) {
                        input.readOne() != 0
                    } else {
                        input.readIntegerValue(type) != 0L
                    }
                }
                key.endsWith(POOLING_TYPE_SUFFIX) && type.isIntegerType() -> {
                    val owner = key.removeSuffix(POOLING_TYPE_SUFFIX)
                    poolingTypeByArchitecture[owner] = input.readIntegerValue(type).toInt()
                }
                key.endsWith(CONTEXT_LENGTH_SUFFIX) && type.isIntegerType() -> {
                    val owner = key.removeSuffix(CONTEXT_LENGTH_SUFFIX)
                    input.readIntegerValue(type)
                        .takeIf { it in MIN_CONTEXT_LENGTH.toLong()..MAX_CONTEXT_LENGTH.toLong() }
                        ?.toInt()
                        ?.let { contextLengthByArchitecture[owner] = it }
                }
                key.endsWith(NEXTN_PREDICT_LAYERS_SUFFIX) && type.isIntegerType() -> {
                    val owner = key.removeSuffix(NEXTN_PREDICT_LAYERS_SUFFIX)
                    input.readIntegerValue(type)
                        .takeIf { it in 0L..MAX_NEXTN_PREDICT_LAYERS.toLong() }
                        ?.toInt()
                        ?.let { nextnPredictLayersByArchitecture[owner] = it }
                }
                else -> input.skipGgufValue(type)
            }
        }

        val causalAttention = architecture?.let(causalAttentionByArchitecture::get)
            ?: causalAttentionByArchitecture.values.singleOrNull()
        val poolingType = architecture?.let(poolingTypeByArchitecture::get)
            ?: poolingTypeByArchitecture.values.singleOrNull()
        val contextLength = architecture?.let(contextLengthByArchitecture::get)
            ?: contextLengthByArchitecture.values.singleOrNull()
        val nextnPredictLayers = architecture?.let(nextnPredictLayersByArchitecture::get)
            ?: nextnPredictLayersByArchitecture.values.singleOrNull()

        return ParsedGgufMetadata(
            architecture = architecture,
            quant = fileType?.let(::fileTypeToQuant) ?: inferQuant(fileName),
            fileType = fileType,
            causalAttention = causalAttention,
            poolingType = poolingType,
            contextLength = contextLength,
            nextnPredictLayers = nextnPredictLayers
        )
    }

    private fun InputStream.readGgufString(): String {
        val length = readUInt64Le()
        require(length <= MAX_STRING_BYTES) { "GGUF metadata string is too large: $length" }
        val bytes = ByteArray(length.toInt())
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun InputStream.skipGgufValue(type: Int) {
        when (type) {
            GGUF_TYPE_UINT8, GGUF_TYPE_INT8, GGUF_TYPE_BOOL -> skipFully(1)
            GGUF_TYPE_UINT16, GGUF_TYPE_INT16 -> skipFully(2)
            GGUF_TYPE_UINT32, GGUF_TYPE_INT32, GGUF_TYPE_FLOAT32 -> skipFully(4)
            GGUF_TYPE_UINT64, GGUF_TYPE_INT64, GGUF_TYPE_FLOAT64 -> skipFully(8)
            GGUF_TYPE_STRING -> skipFully(readUInt64Le())
            GGUF_TYPE_ARRAY -> {
                val elementType = readUInt32Le().toInt()
                val length = readUInt64Le()
                require(length <= MAX_ARRAY_ITEMS_TO_SKIP) { "GGUF metadata array is too large: $length" }
                if (elementType == GGUF_TYPE_STRING) {
                    repeat(length.toInt()) { skipFully(readUInt64Le()) }
                } else {
                    val size = elementType.fixedSizeBytes()
                    require(size > 0) { "Unsupported GGUF array element type: $elementType" }
                    skipFully(size.toLong() * length)
                }
            }
            else -> throw IllegalArgumentException("Unsupported GGUF metadata type: $type")
        }
    }

    private fun InputStream.readIntegerValue(type: Int): Long = when (type) {
        GGUF_TYPE_UINT8, GGUF_TYPE_INT8 -> readOne().toLong()
        GGUF_TYPE_UINT16, GGUF_TYPE_INT16 -> readUInt16Le().toLong()
        GGUF_TYPE_UINT32, GGUF_TYPE_INT32 -> readUInt32Le()
        GGUF_TYPE_UINT64, GGUF_TYPE_INT64 -> readUInt64Le()
        else -> throw IllegalArgumentException("Type is not integer: $type")
    }

    private fun InputStream.readUInt16Le(): Int {
        val b0 = readOne()
        val b1 = readOne()
        return b0 or (b1 shl 8)
    }

    private fun InputStream.readUInt32Le(): Long {
        var out = 0L
        repeat(4) { shift -> out = out or (readOne().toLong() shl (shift * 8)) }
        return out
    }

    private fun InputStream.readUInt64Le(): Long {
        var out = 0L
        repeat(8) { shift -> out = out or (readOne().toLong() shl (shift * 8)) }
        return out
    }

    private fun InputStream.readOne(): Int {
        val value = read()
        if (value < 0) throw EOFException("Unexpected end of GGUF metadata.")
        return value and 0xff
    }

    private fun InputStream.readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val read = read(bytes, offset, bytes.size - offset)
            if (read < 0) throw EOFException("Unexpected end of GGUF metadata.")
            if (read == 0) {
                val value = read()
                if (value < 0) throw EOFException("Unexpected end of GGUF metadata.")
                bytes[offset] = value.toByte()
                offset += 1
            } else {
                offset += read
            }
        }
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped <= 0L) {
                if (read() < 0) throw EOFException("Unexpected end of GGUF metadata.")
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun Int.isIntegerType(): Boolean = this in setOf(
        GGUF_TYPE_UINT8,
        GGUF_TYPE_INT8,
        GGUF_TYPE_UINT16,
        GGUF_TYPE_INT16,
        GGUF_TYPE_UINT32,
        GGUF_TYPE_INT32,
        GGUF_TYPE_UINT64,
        GGUF_TYPE_INT64
    )

    private fun Int.fixedSizeBytes(): Int = when (this) {
        GGUF_TYPE_UINT8, GGUF_TYPE_INT8, GGUF_TYPE_BOOL -> 1
        GGUF_TYPE_UINT16, GGUF_TYPE_INT16 -> 2
        GGUF_TYPE_UINT32, GGUF_TYPE_INT32, GGUF_TYPE_FLOAT32 -> 4
        GGUF_TYPE_UINT64, GGUF_TYPE_INT64, GGUF_TYPE_FLOAT64 -> 8
        else -> -1
    }

    private fun inferArchitecture(fileName: String): String? {
        val lower = fileName.lowercase()
        return when {
            "qwen" in lower -> "qwen"
            "llama" in lower -> "llama"
            "gemma" in lower -> "gemma"
            "mistral" in lower -> "mistral"
            "phi" in lower -> "phi"
            else -> null
        }
    }

    private fun inferQuant(fileName: String): String? {
        val quantPattern = Regex(
            "(Q[0-9]_[A-Z0-9]+(?:_[A-Z0-9]+)?|Q[0-9]|IQ[0-9]_[A-Z]+|TQ[0-9]_[0-9]|MXFP4_MOE|NVFP4|F16|BF16)",
            RegexOption.IGNORE_CASE
        )
        return quantPattern.find(fileName)?.value?.uppercase()
    }

    private fun fileTypeToQuant(type: Int): String? = when (type) {
        0 -> "F32"
        1 -> "F16"
        2 -> "Q4_0"
        3 -> "Q4_1"
        7 -> "Q8_0"
        8 -> "Q5_0"
        9 -> "Q5_1"
        10 -> "Q2_K"
        11 -> "Q3_K_S"
        12 -> "Q3_K_M"
        13 -> "Q3_K_L"
        14 -> "Q4_K_S"
        15 -> "Q4_K_M"
        16 -> "Q5_K_S"
        17 -> "Q5_K_M"
        18 -> "Q6_K"
        19 -> "IQ2_XXS"
        20 -> "IQ2_XS"
        21 -> "Q2_K_S"
        22 -> "IQ3_XS"
        23 -> "IQ3_XXS"
        24 -> "IQ1_S"
        25 -> "IQ4_NL"
        26 -> "IQ3_S"
        27 -> "IQ3_M"
        28 -> "IQ2_S"
        29 -> "IQ2_M"
        30 -> "IQ4_XS"
        31 -> "IQ1_M"
        32 -> "BF16"
        36 -> "TQ1_0"
        37 -> "TQ2_0"
        38 -> "MXFP4_MOE"
        39 -> "NVFP4"
        40 -> "Q1_0"
        41 -> "Q2_0"
        else -> null
    }

    private data class ParsedGgufMetadata(
        val architecture: String?,
        val quant: String?,
        val fileType: Int?,
        val causalAttention: Boolean?,
        val poolingType: Int?,
        val contextLength: Int?,
        val nextnPredictLayers: Int?
    )

    private const val MAX_METADATA_KEYS = 4_096L
    private const val MAX_STRING_BYTES = 2L * 1024L * 1024L
    private const val MAX_ARRAY_ITEMS_TO_SKIP = 2_000_000L
    private const val ATTENTION_CAUSAL_SUFFIX = ".attention.causal"
    private const val POOLING_TYPE_SUFFIX = ".pooling_type"
    private const val CONTEXT_LENGTH_SUFFIX = ".context_length"
    private const val NEXTN_PREDICT_LAYERS_SUFFIX = ".nextn_predict_layers"
    private const val MIN_CONTEXT_LENGTH = 128
    private const val MAX_CONTEXT_LENGTH = 1_048_576
    private const val MAX_NEXTN_PREDICT_LAYERS = 128

    private const val GGUF_TYPE_UINT8 = 0
    private const val GGUF_TYPE_INT8 = 1
    private const val GGUF_TYPE_UINT16 = 2
    private const val GGUF_TYPE_INT16 = 3
    private const val GGUF_TYPE_UINT32 = 4
    private const val GGUF_TYPE_INT32 = 5
    private const val GGUF_TYPE_FLOAT32 = 6
    private const val GGUF_TYPE_BOOL = 7
    private const val GGUF_TYPE_STRING = 8
    private const val GGUF_TYPE_ARRAY = 9
    private const val GGUF_TYPE_UINT64 = 10
    private const val GGUF_TYPE_INT64 = 11
    private const val GGUF_TYPE_FLOAT64 = 12
}

/** Reads up to [byteCount] bytes without assuming a provider fills a bulk read. */
internal fun InputStream.readPrefix(byteCount: Int): ByteArray {
    require(byteCount >= 0) { "Prefix byte count must not be negative." }
    val bytes = ByteArray(byteCount)
    var offset = 0
    while (offset < bytes.size) {
        val read = read(bytes, offset, bytes.size - offset)
        if (read < 0) break
        if (read == 0) {
            val value = read()
            if (value < 0) break
            bytes[offset] = value.toByte()
            offset += 1
        } else {
            offset += read
        }
    }
    return if (offset == bytes.size) bytes else bytes.copyOf(offset)
}

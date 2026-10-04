package com.muyuchat.mca

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import org.json.JSONObject

/**
 * Worker output evidence and byte validation for every local image runtime.
 *
 * Moved out of `LocalImageProvider.kt` verbatim: the same functions, the same constants, the same
 * `require`/`error` wording, and the same checks in the same order. Nothing here holds state and
 * nothing here touches `LocalImageProvider`: every entry point is a pure function of the worker
 * result, the published file, and the verified bytes.
 *
 * The QNN output PNG byte bounds and the output SHA-256 pattern are `internal` because
 * `LocalImageProvider.kt` still validates the same runtimes with them; the PNG container constants
 * and the eight-byte PNG signature stay private to this file.
 */
internal const val MAX_QNN_OUTPUT_PNG_BYTES = 64L * 1_024L * 1_024L
internal const val MIN_QNN_OUTPUT_PNG_BYTES = 57L
internal const val MAX_STABLE_DIFFUSION_OUTPUT_PNG_BYTES = 256L * 1_024L * 1_024L
internal const val MIN_STABLE_DIFFUSION_OUTPUT_PNG_BYTES = 57L

private const val QNN_PNG_CRC_CANCELLATION_STRIDE_BYTES = 64 * 1_024
private const val QNN_PNG_CHUNK_IHDR = 0x49484452L
private const val QNN_PNG_CHUNK_IDAT = 0x49444154L
private const val QNN_PNG_CHUNK_IEND = 0x49454e44L
private const val QNN_PNG_CHUNK_TEXT = 0x74455874L
internal val QNN_OUTPUT_SHA256 = Regex("^[0-9a-f]{64}$")

private val QNN_OUTPUT_PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
)

internal fun verifyMnnPublishedOutputEvidence(
    nativeResult: JSONObject,
    expectedOutputFile: File,
    verifiedOutput: VerifiedQnnImageOutput
) {
    val expectedPath = expectedOutputFile.canonicalFile
    val expectedSha256 = nativeResult.getString("outputSha256")

    fun JSONObject.requireExactLong(field: String, layer: String): Long {
        require(has(field) && !isNull(field)) { "$layer is missing $field." }
        val raw = get(field)
        require(raw is Byte || raw is Short || raw is Int || raw is Long) {
            "$layer $field must be an exact integer."
        }
        return (raw as Number).toLong()
    }

    fun JSONObject.requirePath(field: String, layer: String) {
        require(has(field) && get(field) is String &&
            File(getString(field)).canonicalFile == expectedPath
        ) { "$layer $field does not identify this request output." }
    }

    require(QNN_OUTPUT_SHA256.matches(expectedSha256) &&
        nativeResult.requireExactLong("outputBytes", "outer") == verifiedOutput.outputBytes
    ) { "MNN outer output publication evidence is malformed." }
    nativeResult.requirePath("path", "outer")
    nativeResult.requirePath("outputPath", "outer")

    val nativeEffective = nativeResult.optJSONObject("nativeEffective")
        ?: error("MNN output publication is missing nativeEffective evidence.")
    nativeEffective.requirePath("outputPath", "nativeEffective")
    require(nativeEffective.requireExactLong("outputBytes", "nativeEffective") ==
        verifiedOutput.outputBytes &&
        nativeEffective.optString("outputSha256") == expectedSha256
    ) { "MNN nativeEffective output publication evidence conflicts with the outer result." }

    val outputs = nativeResult.optJSONArray("outputs")
        ?: error("MNN output publication is missing its outputs evidence.")
    require(outputs.length() == 1) { "MNN must publish exactly one native output item." }
    val item = outputs.optJSONObject(0) ?: error("MNN output evidence item must be an object.")
    item.requirePath("path", "outputs[0]")
    require(item.requireExactLong("index", "outputs[0]") == 0L &&
        item.requireExactLong("outputBytes", "outputs[0]") == verifiedOutput.outputBytes &&
        item.optString("outputSha256") == expectedSha256 &&
        item.optString("mimeType").trim().lowercase() == "image/png"
    ) { "MNN output item evidence conflicts with the committed PNG." }
}

/**
 * Binds a native QNN publication to the exact app-owned request path before copying any bytes.
 * The bounded single-file read and strict PNG chunk validation keep native evidence, storage,
 * and the returned image inseparable even when malformed JSON or a stale sibling file is present.
 */
internal fun verifyAndReadQnnImageOutput(
    nativeResult: JSONObject,
    expectedOutputFile: File,
    expectedWidth: Int,
    expectedHeight: Int,
    checkCancelled: () -> Unit = {}
): VerifiedQnnImageOutput = verifyAndReadLocalImagePngOutput(
    nativeResult = nativeResult,
    expectedOutputFile = expectedOutputFile,
    expectedWidth = expectedWidth,
    expectedHeight = expectedHeight,
    expectedColorTypes = setOf(2),
    outputLabel = "Native QNN",
    checkCancelled = checkCancelled
)

/**
 * Validates the native-only proof attached to Qwen-Image-2.1 results.  A
 * configured backend is not sufficient: the isolated JNI bridge must report a
 * completed native run, the effective MNN backend, and the post-run runtime
 * resolution proof. This helper is deliberately strict so a CPU/OpenCL
 * fallback cannot be presented as a QNN result.
 */
internal fun verifyQwenNativeBackendExecutionEvidence(
    audit: JSONObject,
    expectedBackend: String
) {
    val normalizedExpected = expectedBackend.trim().uppercase()
    require(
        normalizedExpected == "MNN_OPENCL" ||
            normalizedExpected == "MNN_CPU" ||
            normalizedExpected == "MNN_QNN"
    ) {
        "Qwen native execution evidence requested an unsupported backend."
    }
    require(audit.optBoolean("nativeRunCompleted", false) &&
        audit.optBoolean("backendExecutionConfirmedByNative", false)
    ) { "Qwen native execution proof is incomplete." }
    require(audit.optBoolean("nativeExecution", false) &&
        !audit.optBoolean("fallback", true) &&
        audit.optLong("nativeGenerationSequence", 0L) > 0L
    ) { "Qwen native execution publication evidence is incomplete." }
    require(audit.optString("effectiveBackend").trim().uppercase() == normalizedExpected) {
        "Qwen native execution backend does not match the requested backend."
    }
    require(audit.optString("backendExecutionProof") == "mnn_runtime_resolution_after_native_run") {
        "Qwen native execution proof has an unsupported source."
    }
    require(audit.optInt("nativeGenerationCount", 0) > 0) {
        "Qwen native execution proof has no completed generation count."
    }
    val inner = audit.optJSONObject("nativeEffective")
        ?: error("Qwen native execution proof is missing nativeEffective.")
    run {
        require(inner.optString("runtime") == "MNN_DIFFUSION" &&
            inner.optBoolean("nativeExecution", false) &&
            !inner.optBoolean("fallback", true) &&
            inner.optLong("nativeGenerationSequence", 0L) ==
                audit.optLong("nativeGenerationSequence", 0L) &&
            inner.optBoolean("nativeRunCompleted", false) &&
            inner.optBoolean("backendExecutionConfirmedByNative", false) &&
            inner.optString("effectiveBackend").trim().uppercase() == normalizedExpected
        ) { "Qwen nativeEffective backend proof is incomplete or inconsistent." }
    }
}

/**
 * Verifies the PNG published by the Qwen-Image-2.1 worker.  Qwen's MNN VAE may
 * publish either an RGB8 (colour type 2) or RGBA8 (colour type 6) PNG.  Keep
 * this path separate from the QNN validator: QNN output is intentionally still
 * constrained to RGB8, while Qwen output is validated with the same strict
 * path, byte, chunk, CRC and zlib checks for both legal colour layouts.
 */
internal fun verifyAndReadQwenImageOutput(
    nativeResult: JSONObject,
    expectedOutputFile: File,
    expectedWidth: Int,
    expectedHeight: Int,
    checkCancelled: () -> Unit = {}
): VerifiedQnnImageOutput = verifyAndReadLocalImagePngOutput(
    nativeResult = nativeResult,
    expectedOutputFile = expectedOutputFile,
    expectedWidth = expectedWidth,
    expectedHeight = expectedHeight,
    expectedColorTypes = setOf(2, 6),
    outputLabel = "Native Qwen-Image-2.1",
    checkCancelled = checkCancelled
)

private fun verifyAndReadLocalImagePngOutput(
    nativeResult: JSONObject,
    expectedOutputFile: File,
    expectedWidth: Int,
    expectedHeight: Int,
    expectedColorTypes: Set<Int>,
    outputLabel: String,
    checkCancelled: () -> Unit = {}
): VerifiedQnnImageOutput {
    require(expectedWidth > 0 && expectedHeight > 0) {
        "$outputLabel output dimensions must be positive."
    }

    fun requiredString(field: String): String {
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "$outputLabel output is missing $field."
        }
        val value = nativeResult.get(field)
        require(value is String && value.isNotBlank()) {
            "$outputLabel output $field must be a non-blank string."
        }
        return value
    }

    fun requiredLong(field: String): Long {
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "$outputLabel output is missing $field."
        }
        val value = nativeResult.get(field)
        require(value is Byte || value is Short || value is Int || value is Long) {
            "$outputLabel output $field must be an exact integer."
        }
        return (value as Number).toLong()
    }

    require(requiredLong("width") == expectedWidth.toLong() &&
        requiredLong("height") == expectedHeight.toLong()
    ) { "$outputLabel output dimensions differ from the resolved request." }
    if (nativeResult.has("mimeType")) {
        require(requiredString("mimeType").trim().lowercase() == "image/png") {
            "$outputLabel output MIME type must be image/png."
        }
    }

    val lexicalOutput = expectedOutputFile.absoluteFile
    val outputRoot = requireNotNull(lexicalOutput.parentFile).canonicalFile
    val expectedCanonical = lexicalOutput.canonicalFile
    require(expectedCanonical.parentFile == outputRoot && expectedCanonical.name == lexicalOutput.name) {
        "$outputLabel request output path must remain a direct app-owned output file."
    }
    val reportedPath = File(requiredString("outputPath"))
    require(reportedPath.isAbsolute) {
        "$outputLabel output path must be absolute."
    }
    val reportedCanonical = reportedPath.canonicalFile
    require(reportedCanonical == expectedCanonical) {
        "$outputLabel output path does not match this request."
    }

    val reportedBytes = requiredLong("outputBytes")
    val reportedSha256 = requiredString("outputSha256")
    require(QNN_OUTPUT_SHA256.matches(reportedSha256)) {
        "$outputLabel output SHA-256 must be fixed-width lowercase hexadecimal."
    }
    val physicalBytes = expectedCanonical.length()
    require(expectedCanonical.isFile &&
        reportedBytes == physicalBytes &&
        reportedBytes in MIN_QNN_OUTPUT_PNG_BYTES..MAX_QNN_OUTPUT_PNG_BYTES
    ) { "$outputLabel output byte proof is missing, mismatched, or outside the bounded PNG limit." }

    checkCancelled()
    val copied = ByteArray(physicalBytes.toInt())
    val copiedDigest = MessageDigest.getInstance("SHA-256")
    FileInputStream(expectedCanonical).use { input ->
        require(input.channel.size() == reportedBytes) {
            "$outputLabel output changed before its app-owned descriptor was opened."
        }
        var total = 0
        while (total < copied.size) {
            checkCancelled()
            val count = input.read(
                copied,
                total,
                minOf(DEFAULT_BUFFER_SIZE, copied.size - total)
            )
            require(count >= 0) { "$outputLabel output changed while being copied." }
            if (count == 0) continue
            copiedDigest.update(copied, total, count)
            total = Math.addExact(total, count)
        }
        checkCancelled()
        require(input.read() < 0 && input.channel.size() == reportedBytes) {
            "$outputLabel output descriptor size changed while being copied."
        }
    }
    checkCancelled()
    require(expectedCanonical.length() == reportedBytes) {
        "$outputLabel output changed after being copied."
    }
    val copiedSha256 = copiedDigest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
    require(copiedSha256 == reportedSha256) {
        "$outputLabel output SHA-256 does not match the exact copied descriptor bytes."
    }
    copied.requireValidLocalImageOutputPng(
        expectedWidth = expectedWidth,
        expectedHeight = expectedHeight,
        expectedColorTypes = expectedColorTypes,
        outputLabel = outputLabel,
        checkCancelled = checkCancelled
    )
    checkCancelled()

    return VerifiedQnnImageOutput(
        bytes = copied,
        mimeType = "image/png",
        outputBytes = reportedBytes
    )
}

private fun ByteArray.requireValidLocalImageOutputPng(
    expectedWidth: Int,
    expectedHeight: Int,
    expectedColorTypes: Set<Int>,
    outputLabel: String,
    checkCancelled: () -> Unit
) {
    require(size.toLong() in MIN_QNN_OUTPUT_PNG_BYTES..MAX_QNN_OUTPUT_PNG_BYTES &&
        size >= QNN_OUTPUT_PNG_SIGNATURE.size &&
        copyOfRange(0, QNN_OUTPUT_PNG_SIGNATURE.size).contentEquals(QNN_OUTPUT_PNG_SIGNATURE)
    ) { "$outputLabel output does not have a bounded PNG signature." }

    var offset = QNN_OUTPUT_PNG_SIGNATURE.size.toLong()
    var chunkIndex = 0
    var sawIhdr = false
    var sawIdat = false
    var idatDataBytes = 0L
    var idatSequenceEnded = false
    var sawIend = false
    var observedColorType: Int? = null
    while (offset < size.toLong()) {
        checkCancelled()
        val remaining = size.toLong() - offset
        require(remaining >= 12L) { "$outputLabel PNG ended inside a chunk record." }

        val chunkOffset = offset.toInt()
        val chunkLength = readQnnPngU32(chunkOffset)
        require(chunkLength <= MAX_QNN_OUTPUT_PNG_BYTES) {
            "$outputLabel PNG declared an oversized chunk."
        }
        val typeOffset = Math.addExact(offset, 4L)
        val dataOffset = Math.addExact(typeOffset, 4L)
        val crcOffset = Math.addExact(dataOffset, chunkLength)
        val nextOffset = Math.addExact(crcOffset, 4L)
        require(nextOffset <= size.toLong()) {
            "$outputLabel PNG chunk length exceeds the bounded file."
        }

        val typeOffsetInt = typeOffset.toInt()
        require(hasValidQnnPngChunkType(typeOffsetInt)) {
            "$outputLabel PNG has an invalid chunk type."
        }
        val chunkType = readQnnPngU32(typeOffsetInt)
        val chunkLengthInt = chunkLength.toInt()
        requireQnnPngChunkCrc(
            typeOffset = typeOffsetInt,
            dataLength = chunkLengthInt,
            expectedCrcOffset = crcOffset.toInt(),
            outputLabel = outputLabel,
            checkCancelled = checkCancelled
        )

        require(sawIhdr || chunkType == QNN_PNG_CHUNK_IHDR) {
            "$outputLabel PNG must begin with IHDR."
        }
        when (chunkType) {
            QNN_PNG_CHUNK_IHDR -> {
                require(chunkIndex == 0 && !sawIhdr && chunkLength == 13L) {
                    "$outputLabel PNG must contain exactly one first IHDR chunk."
                }
                val dataOffsetInt = dataOffset.toInt()
                val colorType = this[dataOffsetInt + 9].toInt() and 0xff
                require(readQnnPngU32(dataOffsetInt) == expectedWidth.toLong() &&
                    readQnnPngU32(dataOffsetInt + 4) == expectedHeight.toLong() &&
                    (this[dataOffsetInt + 8].toInt() and 0xff) == 8 &&
                    colorType in expectedColorTypes &&
                    this[dataOffsetInt + 10].toInt() == 0 &&
                    this[dataOffsetInt + 11].toInt() == 0 &&
                    this[dataOffsetInt + 12].toInt() == 0
                ) {
                    val layouts = expectedColorTypes.sorted().joinToString("/") {
                        when (it) {
                            2 -> "RGB"
                            6 -> "RGBA"
                            else -> "color-type-$it"
                        }
                    }
                    "$outputLabel output is not the expected non-interlaced 8-bit $layouts PNG."
                }
                observedColorType = colorType
                sawIhdr = true
            }
            QNN_PNG_CHUNK_IDAT -> {
                require(!idatSequenceEnded && !sawIend) {
                    "$outputLabel PNG IDAT chunks must be consecutive and precede IEND."
                }
                sawIdat = true
                idatDataBytes = Math.addExact(idatDataBytes, chunkLength)
            }
            QNN_PNG_CHUNK_IEND -> {
                require(sawIdat && idatDataBytes > 0L && !sawIend && chunkLength == 0L &&
                    nextOffset == size.toLong()
                ) { "$outputLabel PNG must end with one terminal IEND after non-empty IDAT data." }
                sawIend = true
            }
            else -> {
                require(!sawIend && !isCriticalQnnPngChunk(typeOffsetInt) &&
                    chunkType == QNN_PNG_CHUNK_TEXT
                ) {
                    "$outputLabel PNG contains an unsupported critical or rendering-affecting ancillary chunk."
                }
                if (sawIdat) idatSequenceEnded = true
            }
        }
        if (sawIdat && chunkType != QNN_PNG_CHUNK_IDAT && chunkType != QNN_PNG_CHUNK_IEND) {
            idatSequenceEnded = true
        }
        offset = nextOffset
        chunkIndex = Math.addExact(chunkIndex, 1)
    }
    require(sawIhdr && sawIdat && idatDataBytes > 0L && sawIend) {
        "$outputLabel PNG is missing IHDR, IDAT, or terminal IEND."
    }
    val bytesPerPixel = when (observedColorType) {
        2 -> 3L
        6 -> 4L
        else -> error("$outputLabel PNG is missing a valid IHDR colour type.")
    }
    requireValidLocalImagePngIdatStream(
        expectedWidth = expectedWidth,
        expectedHeight = expectedHeight,
        bytesPerPixel = bytesPerPixel,
        outputLabel = outputLabel,
        checkCancelled = checkCancelled
    )
}

/**
 * Validates the one zlib stream formed by consecutive IDAT chunks without allocating decoded
 * pixels. RGB8/RGBA8 non-interlaced IHDR means every row must contain one filter byte plus
 * width*3 or width*4 bytes; proving that exact stream shape keeps a CRC-valid but undecodable
 * PNG out of the UI.
 */
private fun ByteArray.requireValidLocalImagePngIdatStream(
    expectedWidth: Int,
    expectedHeight: Int,
    bytesPerPixel: Long,
    outputLabel: String,
    checkCancelled: () -> Unit
) {
    val rowBytes = Math.addExact(Math.multiplyExact(expectedWidth.toLong(), bytesPerPixel), 1L)
    val expectedInflatedBytes = Math.multiplyExact(rowBytes, expectedHeight.toLong())
    require(expectedInflatedBytes > 0L) {
        "$outputLabel PNG has an invalid decoded pixel byte count."
    }

    val inflater = Inflater()
    val decoded = ByteArray(DEFAULT_BUFFER_SIZE)
    var inflatedBytes = 0L
    var nextFilterOffset = 0L
    var observedRows = 0L

    fun consumeDecoded(count: Int) {
        val nextInflatedBytes = Math.addExact(inflatedBytes, count.toLong())
        require(nextInflatedBytes <= expectedInflatedBytes) {
            "$outputLabel PNG IDAT expands beyond the expected scanlines."
        }
        while (nextFilterOffset < nextInflatedBytes) {
            if (nextFilterOffset >= inflatedBytes) {
                val bufferOffset = (nextFilterOffset - inflatedBytes).toInt()
                val filter = decoded[bufferOffset].toInt() and 0xff
                require(filter in 0..4) {
                    "$outputLabel PNG contains an invalid scanline filter."
                }
                observedRows = Math.addExact(observedRows, 1L)
            }
            nextFilterOffset = Math.addExact(nextFilterOffset, rowBytes)
        }
        inflatedBytes = nextInflatedBytes
    }

    fun drainInflater() {
        while (true) {
            checkCancelled()
            val count = try {
                inflater.inflate(decoded)
            } catch (error: DataFormatException) {
                throw IllegalArgumentException(
                    "$outputLabel PNG IDAT is not a valid zlib stream.",
                    error
                )
            }
            if (count > 0) {
                consumeDecoded(count)
                continue
            }
            if (inflater.finished() || inflater.needsInput()) return
            require(!inflater.needsDictionary()) {
                "$outputLabel PNG IDAT unexpectedly requires an external dictionary."
            }
            error("$outputLabel PNG IDAT decoder made no progress.")
        }
    }

    try {
        var pngOffset = QNN_OUTPUT_PNG_SIGNATURE.size.toLong()
        while (pngOffset < size.toLong()) {
            checkCancelled()
            val chunkLength = readQnnPngU32(pngOffset.toInt())
            val typeOffset = Math.addExact(pngOffset, 4L)
            val dataOffset = Math.addExact(typeOffset, 4L)
            val nextOffset = Math.addExact(Math.addExact(dataOffset, chunkLength), 4L)
            require(nextOffset <= size.toLong()) {
                "$outputLabel PNG changed between structural and IDAT validation."
            }
            if (readQnnPngU32(typeOffset.toInt()) == QNN_PNG_CHUNK_IDAT) {
                val length = chunkLength.toInt()
                if (inflater.finished()) {
                    require(length == 0) {
                        "$outputLabel PNG contains compressed bytes after the zlib stream ended."
                    }
                } else if (length > 0) {
                    inflater.setInput(this, dataOffset.toInt(), length)
                    drainInflater()
                    require(inflater.remaining == 0) {
                        "$outputLabel PNG contains trailing bytes after the zlib stream ended."
                    }
                }
            }
            pngOffset = nextOffset
        }
        require(inflater.finished() && inflatedBytes == expectedInflatedBytes &&
            observedRows == expectedHeight.toLong()
        ) {
            "$outputLabel PNG IDAT does not decode to the expected complete scanlines."
        }
    } finally {
        inflater.end()
    }
}

private fun ByteArray.requireQnnPngChunkCrc(
    typeOffset: Int,
    dataLength: Int,
    expectedCrcOffset: Int,
    outputLabel: String,
    checkCancelled: () -> Unit
) {
    val crc = CRC32()
    val crcEnd = Math.addExact(typeOffset, Math.addExact(4, dataLength))
    var cursor = typeOffset
    while (cursor < crcEnd) {
        checkCancelled()
        val count = minOf(QNN_PNG_CRC_CANCELLATION_STRIDE_BYTES, crcEnd - cursor)
        crc.update(this, cursor, count)
        cursor += count
    }
    require(crc.value == readQnnPngU32(expectedCrcOffset)) {
        "$outputLabel PNG chunk CRC does not match its type and data."
    }
}

private fun ByteArray.hasValidQnnPngChunkType(offset: Int): Boolean {
    for (index in 0 until 4) {
        val value = this[offset + index].toInt() and 0xff
        if (value !in 'A'.code..'Z'.code && value !in 'a'.code..'z'.code) return false
    }
    return (this[offset + 2].toInt() and 0x20) == 0
}

private fun ByteArray.isCriticalQnnPngChunk(typeOffset: Int): Boolean =
    (this[typeOffset].toInt() and 0x20) == 0

private fun ByteArray.readQnnPngU32(offset: Int): Long =
    ((this[offset].toLong() and 0xffL) shl 24) or
        ((this[offset + 1].toLong() and 0xffL) shl 16) or
        ((this[offset + 2].toLong() and 0xffL) shl 8) or
        (this[offset + 3].toLong() and 0xffL)

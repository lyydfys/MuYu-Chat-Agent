package com.muyuchat.mca

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.muyuchat.api.local.imagePromptExecutionSha256
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.download.ImageEngineMinDeviceTier
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.nativebridge.NativeQnnBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_UPSCALE_SOURCE_SIDE = 2_048
private const val MAX_UPSCALE_SOURCE_PIXELS = 4_000_000L
private const val MAX_UPSCALE_NATIVE_OUTPUT_SIDE = 8_192
private const val MAX_UPSCALE_NATIVE_OUTPUT_PIXELS = 64_000_000L
private const val MAX_UPSCALE_NATIVE_PNG_BYTES = 256L * 1_024L * 1_024L
private const val MAX_UPSCALE_OUTPUT_SIDE = 4_096
private const val MAX_UPSCALE_OUTPUT_PIXELS = 16_000_000L
private const val MAX_UPSCALE_PNG_BYTES = 64L * 1_024L * 1_024L
private const val MAX_QNN_OUTPUT_PNG_BYTES = 64L * 1_024L * 1_024L
private const val MIN_QNN_OUTPUT_PNG_BYTES = 57L
private const val MAX_STABLE_DIFFUSION_OUTPUT_PNG_BYTES = 256L * 1_024L * 1_024L
private const val MIN_STABLE_DIFFUSION_OUTPUT_PNG_BYTES = 57L
internal const val STABLE_DIFFUSION_OUTPUT_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
internal const val MNN_DIFFUSION_OUTPUT_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
private const val STABLE_DIFFUSION_OUTPUT_MAX_BATCH_COUNT = 8
private const val STABLE_DIFFUSION_ULTRAFIX_EVIDENCE_VERSION = 5L
private const val QNN_ULTRAFIX_EVIDENCE_VERSION = 2L
private const val QNN_PNG_CRC_CANCELLATION_STRIDE_BYTES = 64 * 1_024
private const val QNN_PNG_CHUNK_IHDR = 0x49484452L
private const val QNN_PNG_CHUNK_IDAT = 0x49444154L
private const val QNN_PNG_CHUNK_IEND = 0x49454e44L
private const val QNN_PNG_CHUNK_TEXT = 0x74455874L
private val QNN_OUTPUT_SHA256 = Regex("^[0-9a-f]{64}$")
private const val OWNED_IMAGE_BUNDLE_SCHEMA = "mca.image_engine.bundle.v1"
private val OWNED_IMAGE_BUNDLE_SCHEMA_PATTERN = Regex(
    "\\\"schema\\\"\\s*:\\s*\\\"mca\\.image_engine\\.bundle\\.v1\\\""
)
private val QNN_OUTPUT_PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
)

internal data class LocalImageUpscalePublicationDimensions(
    val width: Int,
    val height: Int,
    val pixels: Long
)

internal fun validatedLocalImageUpscalePublicationDimensions(
    sourceWidth: Int,
    sourceHeight: Int,
    targetScale: Int
): LocalImageUpscalePublicationDimensions {
    require(targetScale in setOf(2, 3, 4)) { "Upscale target scale must be 2, 3, or 4." }
    val sourcePixels = Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong())
    require(sourceWidth in 1..MAX_UPSCALE_SOURCE_SIDE &&
        sourceHeight in 1..MAX_UPSCALE_SOURCE_SIDE &&
        sourcePixels in 1L..MAX_UPSCALE_SOURCE_PIXELS
    ) { "Upscale source exceeds the 2048-pixel side or 4-megapixel execution limit." }
    val width = Math.multiplyExact(sourceWidth, targetScale)
    val height = Math.multiplyExact(sourceHeight, targetScale)
    val pixels = Math.multiplyExact(width.toLong(), height.toLong())
    require(width <= MAX_UPSCALE_OUTPUT_SIDE &&
        height <= MAX_UPSCALE_OUTPUT_SIDE &&
        pixels in 1L..MAX_UPSCALE_OUTPUT_PIXELS
    ) { "Requested upscale output exceeds the bounded Android publication limit." }
    return LocalImageUpscalePublicationDimensions(width, height, pixels)
}

internal data class VerifiedQnnImageOutput(
    val bytes: ByteArray,
    val mimeType: String,
    val outputBytes: Long
)

private enum class SplitSdxlRequestTerminalState {
    PENDING,
    CANCELLED,
    TRANSFERRED
}

private class SplitSdxlRequestTerminalArbiter {
    private val state = AtomicReference(SplitSdxlRequestTerminalState.PENDING)

    fun cancel(): Boolean = state.compareAndSet(
        SplitSdxlRequestTerminalState.PENDING,
        SplitSdxlRequestTerminalState.CANCELLED
    )

    fun transfer(): Boolean = state.compareAndSet(
        SplitSdxlRequestTerminalState.PENDING,
        SplitSdxlRequestTerminalState.TRANSFERRED
    )

    fun current(): SplitSdxlRequestTerminalState = state.get()
}

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
): VerifiedQnnImageOutput {
    require(expectedWidth > 0 && expectedHeight > 0) {
        "QNN output dimensions must be positive."
    }

    fun requiredString(field: String): String {
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "Native QNN output is missing $field."
        }
        val value = nativeResult.get(field)
        require(value is String && value.isNotBlank()) {
            "Native QNN output $field must be a non-blank string."
        }
        return value
    }

    fun requiredLong(field: String): Long {
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "Native QNN output is missing $field."
        }
        val value = nativeResult.get(field)
        require(value is Byte || value is Short || value is Int || value is Long) {
            "Native QNN output $field must be an exact integer."
        }
        return (value as Number).toLong()
    }

    require(requiredLong("width") == expectedWidth.toLong() &&
        requiredLong("height") == expectedHeight.toLong()
    ) { "Native QNN output dimensions differ from the resolved request." }
    if (nativeResult.has("mimeType")) {
        require(requiredString("mimeType").trim().lowercase() == "image/png") {
            "Native QNN output MIME type must be image/png."
        }
    }

    val lexicalOutput = expectedOutputFile.absoluteFile
    val outputRoot = requireNotNull(lexicalOutput.parentFile).canonicalFile
    val expectedCanonical = lexicalOutput.canonicalFile
    require(expectedCanonical.parentFile == outputRoot && expectedCanonical.name == lexicalOutput.name) {
        "QNN request output path must remain a direct app-owned output file."
    }
    val reportedPath = File(requiredString("outputPath"))
    require(reportedPath.isAbsolute) {
        "Native QNN output path must be absolute."
    }
    val reportedCanonical = reportedPath.canonicalFile
    require(reportedCanonical == expectedCanonical) {
        "Native QNN output path does not match this request."
    }

    val reportedBytes = requiredLong("outputBytes")
    val reportedSha256 = requiredString("outputSha256")
    require(QNN_OUTPUT_SHA256.matches(reportedSha256)) {
        "Native QNN output SHA-256 must be fixed-width lowercase hexadecimal."
    }
    val physicalBytes = expectedCanonical.length()
    require(expectedCanonical.isFile &&
        reportedBytes == physicalBytes &&
        reportedBytes in MIN_QNN_OUTPUT_PNG_BYTES..MAX_QNN_OUTPUT_PNG_BYTES
    ) { "Native QNN output byte proof is missing, mismatched, or outside the bounded PNG limit." }

    checkCancelled()
    val copied = ByteArray(physicalBytes.toInt())
    val copiedDigest = MessageDigest.getInstance("SHA-256")
    FileInputStream(expectedCanonical).use { input ->
        require(input.channel.size() == reportedBytes) {
            "Native QNN output changed before its app-owned descriptor was opened."
        }
        var total = 0
        while (total < copied.size) {
            checkCancelled()
            val count = input.read(
                copied,
                total,
                minOf(DEFAULT_BUFFER_SIZE, copied.size - total)
            )
            require(count >= 0) { "Native QNN output changed while being copied." }
            if (count == 0) continue
            copiedDigest.update(copied, total, count)
            total = Math.addExact(total, count)
        }
        checkCancelled()
        require(input.read() < 0 && input.channel.size() == reportedBytes) {
            "Native QNN output descriptor size changed while being copied."
        }
    }
    checkCancelled()
    require(expectedCanonical.length() == reportedBytes) {
        "Native QNN output changed after being copied."
    }
    val copiedSha256 = copiedDigest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
    require(copiedSha256 == reportedSha256) {
        "Native QNN output SHA-256 does not match the exact copied descriptor bytes."
    }
    copied.requireValidQnnOutputPng(expectedWidth, expectedHeight, checkCancelled)
    checkCancelled()

    return VerifiedQnnImageOutput(
        bytes = copied,
        mimeType = "image/png",
        outputBytes = reportedBytes
    )
}

private fun ByteArray.requireValidQnnOutputPng(
    expectedWidth: Int,
    expectedHeight: Int,
    checkCancelled: () -> Unit
) {
    require(size.toLong() in MIN_QNN_OUTPUT_PNG_BYTES..MAX_QNN_OUTPUT_PNG_BYTES &&
        size >= QNN_OUTPUT_PNG_SIGNATURE.size &&
        copyOfRange(0, QNN_OUTPUT_PNG_SIGNATURE.size).contentEquals(QNN_OUTPUT_PNG_SIGNATURE)
    ) { "Native QNN output does not have a bounded PNG signature." }

    var offset = QNN_OUTPUT_PNG_SIGNATURE.size.toLong()
    var chunkIndex = 0
    var sawIhdr = false
    var sawIdat = false
    var idatDataBytes = 0L
    var idatSequenceEnded = false
    var sawIend = false
    while (offset < size.toLong()) {
        checkCancelled()
        val remaining = size.toLong() - offset
        require(remaining >= 12L) { "Native QNN PNG ended inside a chunk record." }

        val chunkOffset = offset.toInt()
        val chunkLength = readQnnPngU32(chunkOffset)
        require(chunkLength <= MAX_QNN_OUTPUT_PNG_BYTES) {
            "Native QNN PNG declared an oversized chunk."
        }
        val typeOffset = Math.addExact(offset, 4L)
        val dataOffset = Math.addExact(typeOffset, 4L)
        val crcOffset = Math.addExact(dataOffset, chunkLength)
        val nextOffset = Math.addExact(crcOffset, 4L)
        require(nextOffset <= size.toLong()) {
            "Native QNN PNG chunk length exceeds the bounded file."
        }

        val typeOffsetInt = typeOffset.toInt()
        require(hasValidQnnPngChunkType(typeOffsetInt)) {
            "Native QNN PNG has an invalid chunk type."
        }
        val chunkType = readQnnPngU32(typeOffsetInt)
        val chunkLengthInt = chunkLength.toInt()
        requireQnnPngChunkCrc(
            typeOffset = typeOffsetInt,
            dataLength = chunkLengthInt,
            expectedCrcOffset = crcOffset.toInt(),
            checkCancelled = checkCancelled
        )

        require(sawIhdr || chunkType == QNN_PNG_CHUNK_IHDR) {
            "Native QNN PNG must begin with IHDR."
        }
        when (chunkType) {
            QNN_PNG_CHUNK_IHDR -> {
                require(chunkIndex == 0 && !sawIhdr && chunkLength == 13L) {
                    "Native QNN PNG must contain exactly one first IHDR chunk."
                }
                val dataOffsetInt = dataOffset.toInt()
                require(readQnnPngU32(dataOffsetInt) == expectedWidth.toLong() &&
                    readQnnPngU32(dataOffsetInt + 4) == expectedHeight.toLong() &&
                    (this[dataOffsetInt + 8].toInt() and 0xff) == 8 &&
                    (this[dataOffsetInt + 9].toInt() and 0xff) == 2 &&
                    this[dataOffsetInt + 10].toInt() == 0 &&
                    this[dataOffsetInt + 11].toInt() == 0 &&
                    this[dataOffsetInt + 12].toInt() == 0
                ) { "Native QNN output is not the expected non-interlaced 8-bit RGB PNG." }
                sawIhdr = true
            }
            QNN_PNG_CHUNK_IDAT -> {
                require(!idatSequenceEnded && !sawIend) {
                    "Native QNN PNG IDAT chunks must be consecutive and precede IEND."
                }
                sawIdat = true
                idatDataBytes = Math.addExact(idatDataBytes, chunkLength)
            }
            QNN_PNG_CHUNK_IEND -> {
                require(sawIdat && idatDataBytes > 0L && !sawIend && chunkLength == 0L &&
                    nextOffset == size.toLong()
                ) { "Native QNN PNG must end with one terminal IEND after non-empty IDAT data." }
                sawIend = true
            }
            else -> {
                require(!sawIend && !isCriticalQnnPngChunk(typeOffsetInt) &&
                    chunkType == QNN_PNG_CHUNK_TEXT
                ) {
                    "Native QNN PNG contains an unsupported critical or rendering-affecting ancillary chunk."
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
        "Native QNN PNG is missing IHDR, IDAT, or terminal IEND."
    }
    requireValidQnnPngIdatStream(
        expectedWidth = expectedWidth,
        expectedHeight = expectedHeight,
        checkCancelled = checkCancelled
    )
}

/**
 * Validates the one zlib stream formed by consecutive IDAT chunks without allocating decoded
 * pixels. RGB8/non-interlaced IHDR means every row must contain one filter byte plus width*3
 * bytes; proving that exact stream shape keeps a CRC-valid but undecodable PNG out of the UI.
 */
private fun ByteArray.requireValidQnnPngIdatStream(
    expectedWidth: Int,
    expectedHeight: Int,
    checkCancelled: () -> Unit
) {
    val rowBytes = Math.addExact(Math.multiplyExact(expectedWidth.toLong(), 3L), 1L)
    val expectedInflatedBytes = Math.multiplyExact(rowBytes, expectedHeight.toLong())
    require(expectedInflatedBytes > 0L) {
        "Native QNN PNG has an invalid decoded RGB byte count."
    }

    val inflater = Inflater()
    val decoded = ByteArray(DEFAULT_BUFFER_SIZE)
    var inflatedBytes = 0L
    var nextFilterOffset = 0L
    var observedRows = 0L

    fun consumeDecoded(count: Int) {
        val nextInflatedBytes = Math.addExact(inflatedBytes, count.toLong())
        require(nextInflatedBytes <= expectedInflatedBytes) {
            "Native QNN PNG IDAT expands beyond the expected RGB scanlines."
        }
        while (nextFilterOffset < nextInflatedBytes) {
            if (nextFilterOffset >= inflatedBytes) {
                val bufferOffset = (nextFilterOffset - inflatedBytes).toInt()
                val filter = decoded[bufferOffset].toInt() and 0xff
                require(filter in 0..4) {
                    "Native QNN PNG contains an invalid scanline filter."
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
                    "Native QNN PNG IDAT is not a valid zlib stream.",
                    error
                )
            }
            if (count > 0) {
                consumeDecoded(count)
                continue
            }
            if (inflater.finished() || inflater.needsInput()) return
            require(!inflater.needsDictionary()) {
                "Native QNN PNG IDAT unexpectedly requires an external dictionary."
            }
            error("Native QNN PNG IDAT decoder made no progress.")
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
                "Native QNN PNG changed between structural and IDAT validation."
            }
            if (readQnnPngU32(typeOffset.toInt()) == QNN_PNG_CHUNK_IDAT) {
                val length = chunkLength.toInt()
                if (inflater.finished()) {
                    require(length == 0) {
                        "Native QNN PNG contains compressed bytes after the zlib stream ended."
                    }
                } else if (length > 0) {
                    inflater.setInput(this, dataOffset.toInt(), length)
                    drainInflater()
                    require(inflater.remaining == 0) {
                        "Native QNN PNG contains trailing bytes after the zlib stream ended."
                    }
                }
            }
            pngOffset = nextOffset
        }
        require(inflater.finished() && inflatedBytes == expectedInflatedBytes &&
            observedRows == expectedHeight.toLong()
        ) {
            "Native QNN PNG IDAT does not decode to the expected complete RGB scanlines."
        }
    } finally {
        inflater.end()
    }
}

private fun ByteArray.requireQnnPngChunkCrc(
    typeOffset: Int,
    dataLength: Int,
    expectedCrcOffset: Int,
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
        "Native QNN PNG chunk CRC does not match its type and data."
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

enum class ImageBackend {
    LOCAL,
    CLOUD
}

enum class LocalImageRuntime(val label: String) {
    STABLE_DIFFUSION_CPP("stable-diffusion.cpp"),
    MNN_DIFFUSION("MNN Diffusion"),
    QNN_HTP("骁龙 NPU"),
    ONNX_RUNTIME("ONNX Runtime"),
    CUSTOM("自定义本地图像引擎");

    companion object {
        fun from(value: String?): LocalImageRuntime =
            entries.firstOrNull { it.name == value } ?: when (value) {
                "MEDIAPIPE", "NCNN", "DIFFUSERS" -> STABLE_DIFFUSION_CPP
                "MNN", "MNN_DIFFUSION", "MNN_DIFFUSION_ENGINE" -> MNN_DIFFUSION
                "QNN", "QNN_HTP", "QAIRT", "HTP" -> QNN_HTP
                "ONNX" -> ONNX_RUNTIME
                else -> CUSTOM
            }

        fun infer(fileName: String): LocalImageRuntime {
            val lower = fileName.lowercase()
            return when {
                "qnn" in lower || "qairt" in lower || "htp" in lower -> QNN_HTP
                lower.endsWith(".mnn") -> MNN_DIFFUSION
                lower.endsWith(".zip") && "mnn" in lower -> MNN_DIFFUSION
                lower.endsWith(".onnx") -> ONNX_RUNTIME
                lower.endsWith(".gguf") ||
                    lower.endsWith(".safetensors") ||
                    lower.endsWith(".ckpt") ||
                    lower.endsWith(".pth") ||
                    lower.endsWith(".pt") ||
                    lower.endsWith(".zip") -> STABLE_DIFFUSION_CPP
                else -> CUSTOM
            }
        }
    }
}

enum class LocalImageModelFamily(val label: String) {
    Z_IMAGE("Z-Image"),
    QWEN_IMAGE("Qwen-Image"),
    GLM_IMAGE("GLM-Image"),
    LONGCAT_IMAGE("LongCat-Image"),
    DREAMLITE("DreamLite"),
    SANA("Sana"),
    FLUX("Flux"),
    SD_TURBO("SD-Turbo"),
    SDXL("SDXL"),
    SD21("Stable Diffusion 2.1"),
    SD15("Stable Diffusion 1.5"),
    WAN("Wan"),
    CUSTOM("自定义");

    companion object {
        fun from(value: String?): LocalImageModelFamily =
            entries.firstOrNull { it.name == value } ?: CUSTOM

        fun infer(fileName: String): LocalImageModelFamily {
            val lower = fileName.lowercase()
            return when {
                "z-image" in lower || "z_image" in lower || "zimage" in lower -> Z_IMAGE
                "qwen-image" in lower || "qwen_image" in lower -> QWEN_IMAGE
                "glm-image" in lower || "glm_image" in lower -> GLM_IMAGE
                "longcat-image" in lower || "longcat_image" in lower -> LONGCAT_IMAGE
                "dreamlite" in lower -> DREAMLITE
                "sana" in lower -> SANA
                "flux" in lower -> FLUX
                "sd-turbo" in lower || "sd_turbo" in lower -> SD_TURBO
                "sdxl" in lower || "stable-diffusion-xl" in lower -> SDXL
                "sd-2.1" in lower || "sd2.1" in lower || "sd21" in lower || "v2-1" in lower || "stable-diffusion-2-1" in lower -> SD21
                "sd-1.5" in lower || "sd1.5" in lower || "sd15" in lower || "v1-5" in lower || "stable-diffusion-v1-5" in lower -> SD15
                "wan" in lower -> WAN
                else -> CUSTOM
            }
        }
    }
}

enum class LocalImageVerificationStatus {
    UNKNOWN,
    MNN_SMOKE_PASSED,
    QNN_IMAGE_SMOKE_PASSED,
    QNN_SMOKE_PASSED,
    QNN_PIPELINE_PROBE_PASSED,
    PASSED,
    FAILED;

    companion object {
        fun from(value: String?): LocalImageVerificationStatus =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

internal data class LocalImageBundleComponentContract(
    val role: String,
    val relativePath: String,
    val required: Boolean
)

internal data class LocalImageBundleManifest(
    val id: String? = null,
    val displayName: String? = null,
    val task: String? = null,
    val runtime: LocalImageRuntime? = null,
    val family: LocalImageModelFamily? = null,
    val imageSize: String? = null,
    val minDeviceTier: ImageEngineMinDeviceTier = ImageEngineMinDeviceTier.ANY,
    val requiresQnnRuntime: Boolean = false,
    val requiredRuntimeProfile: LocalImageQnnRuntimeProfile? = null,
    val requiresSmokeTest: Boolean = true,
    val smokeWidth: Int = 0,
    val smokeHeight: Int = 0,
    val smokeSteps: Int = 0,
    val smokeTimeoutSeconds: Int = 0,
    val qnnSmokeSpec: QnnSmokeSpec = QnnSmokeSpec.Empty,
    val qnnSmokeSpecs: List<QnnSmokeSpec> = emptyList(),
    val primaryFile: File? = null,
    val components: List<LocalImageBundleComponentContract> = emptyList(),
    val executionProfileRequiredPaths: List<String> = emptyList(),
    val executionProfileGraphPaths: Map<String, String> = emptyMap(),
    val executionProfileDeclared: Boolean = false,
    val componentCount: Int = 0
)

internal sealed interface LocalImageBundleManifestInspection {
    data object Undeclared : LocalImageBundleManifestInspection
    data class Ready(val manifest: LocalImageBundleManifest) : LocalImageBundleManifestInspection
    data class Invalid(val message: String) : LocalImageBundleManifestInspection
}

internal sealed interface LocalImageRuntimeComponentContract {
    data object UndeclaredLegacy : LocalImageRuntimeComponentContract
    data class Ready(
        val requiredPaths: List<String>,
        val missingPaths: List<String>
    ) : LocalImageRuntimeComponentContract
    data class Invalid(val message: String) : LocalImageRuntimeComponentContract
}

internal data class LocalImageQnnRuntimeProfile(
    val qnnSdk: String,
    val htpArch: Int,
    val completeBundleRuntime: Boolean
)

data class LocalImageModelRecord(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val runtime: LocalImageRuntime,
    val family: LocalImageModelFamily = LocalImageModelFamily.CUSTOM,
    val imageSize: String = "512x512",
    val source: String = "local",
    val bundleRoot: String? = null,
    val componentCount: Int = 1,
    val verificationStatus: LocalImageVerificationStatus = LocalImageVerificationStatus.UNKNOWN,
    val verificationMessage: String = "",
    val verifiedAt: Long = 0L,
    val qnnVerificationStamp: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val configured: Boolean
        get() = path.isNotBlank() && File(path).exists() && bundleRoot?.let { File(it).exists() } != false

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("path", path)
        .put("fileName", fileName)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
        .put("runtime", runtime.name)
        .put("family", family.name)
        .put("imageSize", imageSize)
        .put("source", source)
        .put("bundleRoot", bundleRoot)
        .put("componentCount", componentCount)
        .put("verificationStatus", verificationStatus.name)
        .put("verificationMessage", verificationMessage)
        .put("verifiedAt", verifiedAt)
        .put("qnnVerificationStamp", qnnVerificationStamp)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(json: JSONObject): LocalImageModelRecord =
            LocalImageModelRecord(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                displayName = json.optString("displayName"),
                path = json.optString("path"),
                fileName = json.optString("fileName"),
                sizeBytes = json.optLong("sizeBytes"),
                sha256 = json.optString("sha256"),
                runtime = LocalImageRuntime.from(json.optString("runtime")),
                family = LocalImageModelFamily.from(json.optString("family")),
                imageSize = json.optString("imageSize", "512x512"),
                source = json.optString("source", "local"),
                bundleRoot = json.optString("bundleRoot").takeIf { it.isNotBlank() && it != "null" },
                componentCount = json.optInt("componentCount", 1).coerceAtLeast(1),
                verificationStatus = LocalImageVerificationStatus.from(json.optString("verificationStatus")),
                verificationMessage = json.optString("verificationMessage"),
                verifiedAt = json.optLong("verifiedAt", 0L),
                qnnVerificationStamp = json.optString("qnnVerificationStamp"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
    }
}

data class LocalImageOutput(
    val bytes: ByteArray,
    val mimeType: String = "image/png",
    val seed: Long? = null,
    val index: Int = 0
) {
    init {
        require(bytes.isNotEmpty()) { "Local image output must not be empty." }
        require(mimeType.startsWith("image/")) { "Local image output MIME type must use image/*." }
        require(index >= 0) { "Local image output index must be non-negative." }
    }
}

data class LocalImageResult(
    val bytes: ByteArray,
    val mimeType: String = "image/png",
    /** Native execution audit returned through the isolated product worker. */
    val executionMetadataJson: String = "",
    val seed: Long? = null,
    /** Ordered batch outputs. The legacy bytes/mimeType fields always mirror index 0. */
    val outputs: List<LocalImageOutput> = listOf(
        LocalImageOutput(bytes = bytes, mimeType = mimeType, seed = seed, index = 0)
    )
) {
    init {
        require(outputs.isNotEmpty()) { "Local image result must contain at least one output." }
        require(outputs.map(LocalImageOutput::index) == outputs.indices.toList()) {
            "Local image output indices must be contiguous and start at zero."
        }
        require(outputs.first().bytes.contentEquals(bytes) &&
            outputs.first().mimeType == mimeType && outputs.first().seed == seed
        ) {
            "Legacy local image result fields must mirror output index zero."
        }
    }
}

/**
 * Consumes the concrete files reported by stable-diffusion.cpp and always removes every
 * worker-private output after reading it. Older single-image runtimes may omit outputs[], but a
 * batch must prove its exact count, contiguous indices, deterministic seeds, MIME type, and files.
 */
internal fun consumeStableDiffusionOutputs(
    result: JSONObject,
    expectedCount: Int,
    expectedSeed: Long,
    legacyOutputFile: File,
    requireCommittedEvidence: Boolean = false
): List<LocalImageOutput> {
    require(expectedCount in 1..8) { "stable-diffusion.cpp output count must be between 1 and 8." }
    require(expectedSeed >= 0L) { "stable-diffusion.cpp output seed must be non-negative." }
    val outputRoot = requireNotNull(legacyOutputFile.parentFile).canonicalFile
    val cleanupCandidates = linkedSetOf(legacyOutputFile.canonicalFile)
    val hasOutputsField = result.has("outputs")
    val rawOutputs = result.opt("outputs")
    val outputArray = rawOutputs as? JSONArray

    fun rememberCleanupCandidate(path: String) {
        if (path.isBlank()) return
        runCatching { File(path).canonicalFile }
            .getOrNull()
            ?.takeIf { candidate ->
                candidate.path.startsWith(outputRoot.path + File.separator)
            }
            ?.let(cleanupCandidates::add)
    }

    (result.opt("path") as? String)?.let(::rememberCleanupCandidate)
    if (outputArray != null) {
        for (index in 0 until outputArray.length()) {
            val item = outputArray.optJSONObject(index) ?: continue
            (item.opt("path") as? String)?.let(::rememberCleanupCandidate)
        }
    } else if (rawOutputs is JSONObject) {
        (rawOutputs.opt("path") as? String)?.let(::rememberCleanupCandidate)
    }

    fun JSONObject.requiredOutputString(name: String): String {
        require(has(name) && !isNull(name)) { "stable-diffusion.cpp output is missing $name." }
        val raw = get(name)
        require(raw is String && raw.isNotBlank()) {
            "stable-diffusion.cpp output $name must be a non-blank string."
        }
        return raw
    }

    fun JSONObject.requiredOutputLong(name: String): Long {
        require(has(name) && !isNull(name)) { "stable-diffusion.cpp output is missing $name." }
        val raw = get(name)
        require(raw is Byte || raw is Short || raw is Int || raw is Long) {
            "stable-diffusion.cpp output $name must be an integer."
        }
        return (raw as Number).toLong()
    }

    fun validatedMimeType(raw: String): String {
        val normalized = raw.trim().lowercase()
        require(normalized == "image/png") {
            "stable-diffusion.cpp output MIME type must be image/png."
        }
        return normalized
    }

    fun validatedOutputFile(path: String): File {
        val file = File(path).canonicalFile
        require(file.path.startsWith(outputRoot.path + File.separator)) {
            "stable-diffusion.cpp output path escaped its output directory."
        }
        require(file.isFile && file.length() > 0L) {
            "stable-diffusion.cpp output file is missing or empty: ${file.name}"
        }
        require(file.length() in
            MIN_STABLE_DIFFUSION_OUTPUT_PNG_BYTES..MAX_STABLE_DIFFUSION_OUTPUT_PNG_BYTES
        ) {
            "stable-diffusion.cpp output file is outside the bounded PNG size contract."
        }
        return file
    }

    data class ValidatedOutputDescriptor(
        val file: File,
        val mimeType: String,
        val seed: Long,
        val sha256: String?,
        val sizeBytes: Long?,
        val atomicCommit: Boolean?
    )

    fun JSONObject.optionalCommittedOutputEvidence(file: File): Triple<String?, Long?, Boolean?> {
        val fields = listOf("sha256", "sizeBytes", "atomicCommit")
        if (fields.none(::has)) return Triple(null, null, null)
        require(fields.all(::has) && fields.none(::isNull)) {
            "stable-diffusion.cpp committed output evidence must be complete."
        }
        val sha256 = requiredOutputString("sha256").lowercase()
        require(QNN_OUTPUT_SHA256.matches(sha256)) {
            "stable-diffusion.cpp output sha256 must be a lowercase digest."
        }
        val sizeBytes = requiredOutputLong("sizeBytes")
        require(sizeBytes > 0L && sizeBytes == file.length()) {
            "stable-diffusion.cpp output size evidence does not match the committed file."
        }
        val atomicCommit = opt("atomicCommit") as? Boolean
            ?: error("stable-diffusion.cpp output atomicCommit must be boolean.")
        require(atomicCommit) {
            "stable-diffusion.cpp must not attach commit evidence to a non-atomic output."
        }
        return Triple(sha256, sizeBytes, atomicCommit)
    }

    return try {
        require(!hasOutputsField || outputArray != null) {
            "stable-diffusion.cpp outputs must be an array."
        }
        if (outputArray == null) {
            require(!requireCommittedEvidence) {
                "stable-diffusion.cpp committed output evidence requires outputs[]."
            }
            require(expectedCount == 1) {
                "stable-diffusion.cpp batch output is missing outputs[]."
            }
            val path = if (result.has("path")) {
                result.requiredOutputString("path")
            } else {
                legacyOutputFile.canonicalPath
            }
            val mimeType = if (result.has("mimeType")) {
                validatedMimeType(result.requiredOutputString("mimeType"))
            } else {
                "image/png"
            }
            val file = validatedOutputFile(path)
            listOf(
                LocalImageOutput(
                    bytes = file.readBytes(),
                    mimeType = mimeType,
                    seed = expectedSeed,
                    index = 0
                )
            )
        } else {
            require(outputArray.length() == expectedCount) {
                "stable-diffusion.cpp returned ${outputArray.length()} outputs; expected $expectedCount."
            }
            listOf("outputCount", "n").forEach { field ->
                if (result.has(field)) {
                    require(result.requiredOutputLong(field) == expectedCount.toLong()) {
                        "stable-diffusion.cpp $field does not match outputs[]."
                    }
                }
            }
            val descriptors = buildList {
                for (index in 0 until outputArray.length()) {
                    val item = outputArray.optJSONObject(index)
                        ?: error("stable-diffusion.cpp output item must be an object.")
                    require(item.requiredOutputLong("index") == index.toLong()) {
                        "stable-diffusion.cpp output indices must be contiguous and start at zero."
                    }
                    val expectedOutputSeed = Math.addExact(expectedSeed, index.toLong())
                    require(item.requiredOutputLong("seed") == expectedOutputSeed) {
                        "stable-diffusion.cpp output seed does not match index $index."
                    }
                    val mimeType = validatedMimeType(item.requiredOutputString("mimeType"))
                    val file = validatedOutputFile(item.requiredOutputString("path"))
                    val (sha256, sizeBytes, atomicCommit) =
                        item.optionalCommittedOutputEvidence(file)
                    if (requireCommittedEvidence) {
                        require(sha256 != null && sizeBytes != null && atomicCommit == true) {
                            "stable-diffusion.cpp UltraFix output is missing committed byte evidence."
                        }
                    }
                    add(ValidatedOutputDescriptor(
                        file = file,
                        mimeType = mimeType,
                        seed = expectedOutputSeed,
                        sha256 = sha256,
                        sizeBytes = sizeBytes,
                        atomicCommit = atomicCommit
                    ))
                }
            }
            require(descriptors.map { it.file.canonicalPath }.distinct().size == descriptors.size) {
                "stable-diffusion.cpp returned duplicate output paths."
            }
            val legacyPath = validatedOutputFile(result.requiredOutputString("path"))
            val legacyMimeType = validatedMimeType(result.requiredOutputString("mimeType"))
            require(legacyPath.canonicalPath == descriptors.first().file.canonicalPath &&
                legacyMimeType == descriptors.first().mimeType
            ) {
                "stable-diffusion.cpp legacy output fields must mirror output index zero."
            }
            descriptors.mapIndexed { index, descriptor ->
                val bytes = descriptor.file.readBytes()
                descriptor.sha256?.let { expectedSha256 ->
                    val actualSha256 = MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { byte ->
                            "%02x".format(byte.toInt() and 0xff)
                        }
                    require(actualSha256 == expectedSha256 &&
                        descriptor.sizeBytes == bytes.size.toLong() &&
                        descriptor.atomicCommit == true
                    ) {
                        "stable-diffusion.cpp committed output bytes do not match native evidence."
                    }
                }
                LocalImageOutput(
                    bytes = bytes,
                    mimeType = descriptor.mimeType,
                    seed = descriptor.seed,
                    index = index
                )
            }
        }
    } finally {
        cleanupCandidates.forEach { file -> runCatching { file.delete() } }
    }
}

data class LocalImageProgress(
    val phase: String,
    val message: String,
    val step: Int,
    val steps: Int,
    val elapsedMs: Long,
    val secondsPerStep: Double,
    val threads: Int,
    val width: Int,
    val height: Int,
    val cancelRequested: Boolean,
    /** Requested/effective controls for audit only; never native execution proof. */
    val requestOptionsJson: String = "",
    /** Actual stable-diffusion.cpp component paths selected by native execution. */
    val componentSelectionJson: String = "",
    /** Monotonic native stage evidence; useful even when a short stage is missed by polling. */
    val stageTrace: List<String> = emptyList(),
    /** App-private PNG written by a native preview publisher and valid only for this request. */
    val previewPath: String = "",
    val previewMimeType: String = "",
    val previewMode: String = "",
    val previewStep: Int = 0,
    val previewRevision: Long = 0L,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val previewFrameCount: Int = 0,
    val previewNoisy: Boolean = false,
    /** Shared-QNN preview audit; these counters never satisfy final VAE execution evidence. */
    val previewVaeExecutionAttemptCount: Int = 0,
    val previewVaeExecutionCount: Int = 0,
    val previewVaeExecutionMsTotal: Long = 0L,
    val previewPublicationCount: Int = 0,
    val previewLastStep: Int = 0,
    val previewLastRevision: Long = 0L,
    val previewFailureCode: String = ""
)

/**
 * Explicit generation controls carried across the main-process/worker IPC
 * boundary.  Normal product calls leave every field null and retain the
 * model-family defaults; smoke and benchmark calls can require an exact,
 * auditable configuration instead of silently falling back to those defaults.
 */
data class LocalImageGenerationOptions(
    /** null = use profile default; empty string = explicitly disable it. */
    val negativePrompt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val threads: Int? = null,
    val seed: Int? = null,
    val cfgScale: Double? = null,
    val distilledGuidance: Double? = null,
    val flowShift: Double? = null,
    val sampleMethod: String? = null,
    val backendMode: String? = null,
    val tokenEmbeddingMode: String? = null,
    val memoryMode: Int? = null,
    val runner: String? = null,
    val useCfg: Boolean? = null,
    val taskMode: LocalImageTaskMode = LocalImageTaskMode.TEXT_TO_IMAGE,
    val inputImage: LocalImagePreparedInput? = null,
    val maskImage: LocalImagePreparedInput? = null,
    val controlImage: LocalImagePreparedInput? = null,
    val strength: Double? = null,
    val controlStrength: Double? = null,
    val clipSkip: Int? = null,
    val batchCount: Int = 1,
    val loras: List<LocalImagePreparedLora> = emptyList(),
    val vaeTiling: LocalImageVaeTilingOptions? = null,
    val textualInversionIds: List<String> = emptyList(),
    val ultraFix: LocalImageUltraFixOptions? = null,
    val preview: LocalImagePreviewOptions? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        negativePrompt?.let { put("negativePrompt", it) }
        width?.let { put("width", it) }
        height?.let { put("height", it) }
        steps?.let { put("steps", it) }
        threads?.let { put("threads", it) }
        seed?.let { put("seed", it) }
        cfgScale?.let { put("cfgScale", it) }
        distilledGuidance?.let { put("distilledGuidance", it) }
        flowShift?.let { put("flowShift", it) }
        sampleMethod?.let { put("sampleMethod", it) }
        backendMode?.let { put("backendMode", it) }
        tokenEmbeddingMode?.let { put("tokenEmbeddingMode", it) }
        memoryMode?.let { put("memoryMode", it) }
        runner?.let { put("runner", it) }
        useCfg?.let { put("useCfg", it) }
        put("taskMode", taskMode.wireName)
        inputImage?.let { put("inputImage", it.toJson()) }
        maskImage?.let { put("maskImage", it.toJson()) }
        controlImage?.let { put("controlImage", it.toJson()) }
        strength?.let { put("strength", it) }
        controlStrength?.let { put("controlStrength", it) }
        clipSkip?.let { put("clipSkip", it) }
        put("batchCount", batchCount)
        if (loras.isNotEmpty()) {
            put("loras", JSONArray().apply { loras.forEach { put(it.toJson()) } })
        }
        vaeTiling?.let { put("vaeTiling", it.toJson()) }
        if (textualInversionIds.isNotEmpty()) put("textualInversionIds", JSONArray(textualInversionIds))
        ultraFix?.let { put("ultraFix", it.toJson()) }
        preview?.let { put("preview", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject?): LocalImageGenerationOptions =
            parseJson(json, validateForExecution = true)

        /** History intentionally omits prepared paths; execution validation happens after restaging. */
        internal fun fromHistoryJson(json: JSONObject?): LocalImageGenerationOptions =
            parseJson(json, validateForExecution = false)

        private fun parseJson(
            json: JSONObject?,
            validateForExecution: Boolean
        ): LocalImageGenerationOptions {
            if (json == null) return LocalImageGenerationOptions()
            fun optionalInt(key: String): Int? =
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.get(key)
                    require(raw is Number) { "$key must be an integer." }
                    val value = raw.toDouble()
                    require(value.isFinite() && value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                        "$key must be a finite 32-bit integer."
                    }
                    value.toInt()
                } else null
            fun optionalDouble(key: String): Double? =
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.get(key)
                    require(raw is Number) { "$key must be numeric." }
                    raw.toDouble().also { value -> require(value.isFinite()) { "$key must be finite." } }
                } else null
            fun optionalString(key: String, preserveBlank: Boolean = false): String? =
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.get(key)
                    require(raw is String) { "$key must be a string." }
                    raw.trim().let { value ->
                        if (preserveBlank || value.isNotBlank()) value else null
                    }
                } else {
                    null
                }
            fun optionalBoolean(key: String): Boolean? =
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.get(key)
                    require(raw is Boolean) { "$key must be a boolean." }
                    raw
                } else null
            fun optionalObject(key: String): JSONObject? =
                if (json.has(key)) {
                    require(!json.isNull(key)) { "$key must be an object when specified." }
                    val raw = json.get(key)
                    require(raw is JSONObject) { "$key must be an object when specified." }
                    raw
                } else {
                    null
                }
            fun optionalArray(key: String): JSONArray? =
                if (json.has(key)) {
                    require(!json.isNull(key)) { "$key must be an array when specified." }
                    val raw = json.get(key)
                    require(raw is JSONArray) { "$key must be an array when specified." }
                    raw
                } else {
                    null
                }

            val parsed = LocalImageGenerationOptions(
                negativePrompt = optionalString("negativePrompt", preserveBlank = true)
                    ?: optionalString("negative_prompt", preserveBlank = true),
                width = optionalInt("width"),
                height = optionalInt("height"),
                steps = optionalInt("steps"),
                threads = optionalInt("threads"),
                seed = optionalInt("seed") ?: optionalInt("randomSeed"),
                cfgScale = optionalDouble("cfgScale"),
                distilledGuidance = optionalDouble("distilledGuidance"),
                flowShift = optionalDouble("flowShift"),
                sampleMethod = optionalString("sampleMethod", preserveBlank = true),
                // A present-but-empty execution control is invalid and must reach
                // the MNN resolver rather than becoming an implicit fallback.
                backendMode = optionalString("backendMode", preserveBlank = true),
                tokenEmbeddingMode = optionalString("tokenEmbeddingMode", preserveBlank = true),
                memoryMode = optionalInt("memoryMode"),
                runner = optionalString("runner", preserveBlank = true),
                useCfg = optionalBoolean("useCfg"),
                taskMode = if (json.has("taskMode")) {
                    require(!json.isNull("taskMode")) { "taskMode must be a string when specified." }
                    LocalImageTaskMode.fromWireName(optionalString("taskMode", preserveBlank = true))
                } else {
                    LocalImageTaskMode.TEXT_TO_IMAGE
                },
                inputImage = optionalObject("inputImage")?.let(LocalImagePreparedInput::fromJson),
                maskImage = optionalObject("maskImage")?.let(LocalImagePreparedInput::fromJson),
                controlImage = optionalObject("controlImage")?.let(LocalImagePreparedInput::fromJson),
                strength = optionalDouble("strength"),
                controlStrength = optionalDouble("controlStrength"),
                clipSkip = optionalInt("clipSkip"),
                batchCount = optionalInt("batchCount") ?: 1,
                loras = optionalArray("loras")?.let { array ->
                    require(array.length() <= LocalImagePreparedLora.MAX_COUNT) {
                        "Too many LoRA adapters."
                    }
                    buildList {
                        for (index in 0 until array.length()) {
                            add(LocalImagePreparedLora.fromJson(array.getJSONObject(index)))
                        }
                    }
                }.orEmpty(),
                vaeTiling = optionalObject("vaeTiling")?.let(LocalImageVaeTilingOptions::fromJson),
                textualInversionIds = optionalArray("textualInversionIds")?.let { values ->
                    require(values.length() <= 8) { "Too many textual inversion identifiers." }
                    buildList {
                        for (index in 0 until values.length()) {
                            val value = values.get(index) as? String
                                ?: error("textualInversionIds[$index] must be a UUID.")
                            add(UUID.fromString(value.trim()).toString())
                        }
                    }.also { ids -> require(ids.distinct().size == ids.size) { "Textual inversion ids must be unique." } }
                }.orEmpty(),
                ultraFix = optionalObject("ultraFix")?.let(LocalImageUltraFixOptions::fromJson),
                preview = optionalObject("preview")?.let(LocalImagePreviewOptions::fromJson)
            )
            if (!validateForExecution) {
                require(parsed.inputImage == null && parsed.maskImage == null &&
                    parsed.controlImage == null && parsed.loras.isEmpty() && parsed.preview == null
                ) { "Image history options must not contain transient execution artifacts." }
                return parsed
            }
            return parsed.withCanonicalUltraFixControls().also { options ->
                options.validateProductInputContract()
            }
        }
    }
}

data class LocalImageUltraFixOptions(
    val targetWidth: Int,
    val targetHeight: Int,
    val strength: Double,
    val inversionSteps: Int,
    val refinementSteps: Int,
    val tileSize: Int,
    val overlap: Double
) {
    init {
        require(targetWidth in 64..8192 && targetWidth % 8 == 0)
        require(targetHeight in 64..8192 && targetHeight % 8 == 0)
        require(targetWidth.toLong() * targetHeight.toLong() <= 64L * 1024L * 1024L)
        require(strength > 0.0 && strength <= 1.0)
        require(inversionSteps in 1..100 && refinementSteps in 1..100)
        require(inversionSteps == localImageDenoisingTailStepCount(refinementSteps, strength))
        require(tileSize in 128..2048 && tileSize % 8 == 0 &&
            tileSize <= minOf(targetWidth, targetHeight))
        require(overlap in 0.0..0.5)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("targetWidth", targetWidth)
        .put("targetHeight", targetHeight)
        .put("strength", strength)
        .put("inversionSteps", inversionSteps)
        .put("refinementSteps", refinementSteps)
        .put("tileSize", tileSize)
        .put("overlap", overlap)

    companion object {
        fun fromJson(json: JSONObject): LocalImageUltraFixOptions {
            val fields = setOf("targetWidth", "targetHeight", "strength", "inversionSteps", "refinementSteps", "tileSize", "overlap")
            require(json.keys().asSequence().toSet() == fields) { "UltraFix options must contain only request fields." }
            fun int(name: String): Int = (json.get(name) as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
                ?: error("UltraFix $name must be an integer.")
            fun double(name: String): Double = (json.get(name) as? Number)?.toDouble()?.takeIf(Double::isFinite)
                ?: error("UltraFix $name must be finite.")
            return LocalImageUltraFixOptions(
                int("targetWidth"), int("targetHeight"), double("strength"), int("inversionSteps"),
                int("refinementSteps"), int("tileSize"), double("overlap")
            )
        }
    }
}

/** Preserves a concrete native product error instead of collapsing it into generation_failed. */
internal fun throwLocalImageNativeFailure(
    result: JSONObject,
    fallbackMessage: String
): Nothing {
    val message = sequenceOf(
        result.optString("error"),
        result.optString("message"),
        fallbackMessage
    ).map { it.trim() }.firstOrNull(String::isNotEmpty).orEmpty()
    val code = result.optString("errorCode").trim()
    if (code.isNotEmpty()) {
        throw LocalImageProductContractException(code.lowercase(), message)
    }
    error(message)
}

internal data class VerifiedTextualInversionExecutionEvidence(
    val bindingFingerprint: String,
    val bindingStage: String,
    val conditioningConsumptionCount: Long
)

internal fun verifyStableDiffusionTextualInversionEvidence(
    result: JSONObject,
    selection: TextualInversionSelection?,
    expectedNativeMode: String = TextualInversionRuntime.STABLE_DIFFUSION_CPP.nativeMode,
    expectedBindingStage: String = "conditioning_consumed",
    requireConditioningConsumption: Boolean = true
): VerifiedTextualInversionExecutionEvidence {
    val expectedBindings = selection?.bindings.orEmpty()
    val expectedCount = expectedBindings.size
    val expectedMask = if (expectedCount == 0) 0L else (1L shl expectedCount) - 1L
    val expectedFingerprint = selection?.bindingFingerprint.orEmpty()

    fun strictLong(source: JSONObject, field: String, layer: String): Long {
        require(source.has(field) && !source.isNull(field)) {
            "$layer textual inversion evidence is missing $field."
        }
        val number = source.get(field) as? Number
            ?: error("$layer textual inversion evidence $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble() && value >= 0L) {
            "$layer textual inversion evidence $field must be a non-negative integer."
        }
        return value
    }

    fun verifyArtifacts(source: JSONObject, layer: String) {
        val artifacts = source.optJSONArray("textualInversions")
            ?: error("$layer textual inversion artifact evidence is missing.")
        require(artifacts.length() == expectedCount) {
            "$layer textual inversion artifact count does not match the request."
        }
        expectedBindings.forEachIndexed { index, binding ->
            val actual = artifacts.optJSONObject(index)
                ?: error("$layer textual inversion artifact $index must be an object.")
            require(actual.optString("id") == binding.artifact.id &&
                actual.optString("trigger") == binding.artifact.trigger &&
                actual.optString("sha256") == binding.artifact.sha256 &&
                strictLong(actual, "sizeBytes", "$layer artifact $index") ==
                    binding.artifact.sizeBytes &&
                actual.optString("format") == binding.artifact.format.wireName &&
                actual.optString("modelFingerprint") == binding.modelFingerprint.lowercase() &&
                actual.optString("tokenizerFingerprint") ==
                    binding.tokenizerFingerprint.lowercase() &&
                actual.optString("profileId") == binding.profileId &&
                strictLong(actual, "profileRevision", "$layer artifact $index") ==
                    binding.profileRevision.toLong() &&
                actual.optString("runtime") == binding.runtime.wireName &&
                actual.optString("bindingFingerprint") == binding.bindingFingerprint
            ) { "$layer textual inversion artifact $index does not match its leased binding." }
        }
    }

    fun verifyExecutionAssets(source: JSONObject, layer: String) {
        val expected = selection?.executionAssetBinding ?: return
        val assets = source.optJSONArray("textualInversionExecutionAssets")
            ?: error("$layer textual inversion execution-asset evidence is missing.")
        require(source.optString("textualInversionExecutionAssetsSha256") == expected.compositeSha256 &&
            source.optString("textualInversionExecutionRuntime") == expected.runtime.wireName &&
            source.optString("textualInversionExecutionBundleRoot") == expected.bundleRoot &&
            source.optString("textualInversionExecutionProfileFingerprint") ==
                expected.profilePromptFingerprint &&
            assets.length() == expected.assets.size
        ) { "$layer textual inversion execution-asset binding does not match the leased snapshot." }
        expected.assets.forEachIndexed { index, descriptor ->
            val actual = assets.optJSONObject(index)
                ?: error("$layer textual inversion execution asset $index must be an object.")
            require(actual.optString("label") == descriptor.label &&
                actual.optString("path") == descriptor.path &&
                strictLong(actual, "sizeBytes", "$layer execution asset $index") ==
                    descriptor.sizeBytes &&
                actual.optString("sha256") == descriptor.sha256
            ) { "$layer textual inversion execution asset $index differs from the leased bytes." }
        }
    }

    fun verifyEvidence(source: JSONObject, layer: String): VerifiedTextualInversionExecutionEvidence {
        val evidence = source.optJSONObject("textualInversionEvidence")
            ?: error("$layer textual inversion execution evidence is missing.")
        val requestedCount = strictLong(evidence, "requestedCount", layer)
        val validatedCount = strictLong(evidence, "validatedCount", layer)
        val loadAttemptCount = strictLong(evidence, "loadAttemptCount", layer)
        val loadedCount = strictLong(evidence, "loadedCount", layer)
        val tokenizerMatchCount = strictLong(evidence, "tokenizerMatchCount", layer)
        val appliedCount = strictLong(evidence, "appliedCount", layer)
        val appliedVectorCount = strictLong(evidence, "appliedVectorCount", layer)
        val consumptionCount = strictLong(evidence, "conditioningConsumptionCount", layer)
        val clipLAppliedCount = strictLong(evidence, "clipLAppliedCount", layer)
        val clipGAppliedCount = strictLong(evidence, "clipGAppliedCount", layer)
        val requestedMask = strictLong(evidence, "requestedMask", layer)
        val loadedMask = strictLong(evidence, "loadedMask", layer)
        val tokenizerMatchMask = strictLong(evidence, "tokenizerMatchMask", layer)
        val appliedMask = strictLong(evidence, "appliedMask", layer)
        val consumedMask = strictLong(evidence, "consumedMask", layer)
        val clipLMask = strictLong(evidence, "clipLMask", layer)
        val clipGMask = strictLong(evidence, "clipGMask", layer)
        val clipGRequiredMask = strictLong(evidence, "clipGRequiredMask", layer)
        val bindingFingerprint = evidence.optString("bindingFingerprint")
        val nativeMode = evidence.optString("nativeMode")
        val bindingStage = evidence.optString("bindingStage")

        val expectedConsumedMask = if (requireConditioningConsumption) expectedMask else 0L
        val consumptionComplete = if (requireConditioningConsumption) {
            if (expectedCount == 0) consumptionCount == 0L else consumptionCount >= expectedCount.toLong()
        } else {
            consumptionCount == 0L
        }
        require(requestedCount == expectedCount.toLong() &&
            validatedCount == expectedCount.toLong() &&
            loadAttemptCount == expectedCount.toLong() &&
            loadedCount == expectedCount.toLong() &&
            tokenizerMatchCount == expectedCount.toLong() &&
            appliedCount == expectedCount.toLong() &&
            consumptionComplete &&
            requestedMask == expectedMask && loadedMask == expectedMask &&
            tokenizerMatchMask == expectedMask && appliedMask == expectedMask &&
            consumedMask == expectedConsumedMask && clipLMask == expectedMask &&
            clipLAppliedCount == expectedCount.toLong() &&
            (clipGRequiredMask == 0L || clipGRequiredMask == expectedMask) &&
            clipGMask == clipGRequiredMask &&
            clipGAppliedCount == java.lang.Long.bitCount(clipGRequiredMask).toLong() &&
            evidence.optString("failureCode") == "none"
        ) { "$layer textual inversion execution was incomplete or internally inconsistent." }

        if (expectedCount == 0) {
            require(appliedVectorCount == 0L && consumptionCount == 0L &&
                bindingFingerprint.isEmpty() &&
                nativeMode == "none" && bindingStage == "none"
            ) { "$layer unexpectedly reported textual inversion execution." }
        } else {
            require(appliedVectorCount >= expectedCount.toLong() &&
                bindingFingerprint == expectedFingerprint &&
                nativeMode == expectedNativeMode &&
                bindingStage == expectedBindingStage
            ) { "$layer textual inversion binding evidence does not prove native consumption." }
        }
        return VerifiedTextualInversionExecutionEvidence(
            bindingFingerprint = bindingFingerprint,
            bindingStage = bindingStage,
            conditioningConsumptionCount = consumptionCount
        )
    }

    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: error("stable-diffusion.cpp result is missing nativeEffective evidence.")
    verifyExecutionAssets(result, "outer")
    verifyExecutionAssets(nativeEffective, "nativeEffective")
    verifyArtifacts(result, "outer")
    verifyArtifacts(nativeEffective, "nativeEffective")
    val outer = verifyEvidence(result, "outer")
    val inner = verifyEvidence(nativeEffective, "nativeEffective")
    require(outer == inner) {
        "Outer and nativeEffective textual inversion evidence conflict."
    }
    return inner
}

internal fun verifyEncodedMnnTextualInversionEvidence(
    encoded: JSONObject,
    selection: TextualInversionSelection,
    runtime: TextualInversionRuntime
) {
    require(!encoded.has("nativeEffective")) {
        "The standalone MNN conditioner must not impersonate final runtime consumption evidence."
    }
    val wrapper = JSONObject(encoded.toString())
        .put("nativeEffective", JSONObject(encoded.toString()))
    verifyStableDiffusionTextualInversionEvidence(
        result = wrapper,
        selection = selection,
        expectedNativeMode = runtime.nativeMode,
        expectedBindingStage = "conditioning_encoded",
        requireConditioningConsumption = false
    )
}

internal fun promoteConsumedQnnTextualInversionEvidence(
    result: JSONObject,
    encoded: JSONObject,
    selection: TextualInversionSelection,
    expectedConditioningSha256: String,
    splitWorkers: Boolean
): VerifiedTextualInversionExecutionEvidence {
    verifyEncodedMnnTextualInversionEvidence(
        encoded = encoded,
        selection = selection,
        runtime = TextualInversionRuntime.QNN_HTP
    )
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: error("QNN textual inversion requires final nativeEffective evidence.")
    require(result.optString("conditioningArtifactSha256").lowercase() == expectedConditioningSha256 &&
        nativeEffective.optString("conditioningArtifactSha256").lowercase() == expectedConditioningSha256
    ) { "QNN textual inversion conditioning bytes differ from the native-consumed artifact." }
    if (splitWorkers) {
        require(result.optInt("unetExecutionCount", 0) > 0 &&
            nativeEffective.optInt("unetExecutionCount", 0) > 0 &&
            result.optJSONObject("sdxlPhaseProof") != null &&
            nativeEffective.optJSONObject("sdxlPhaseProof") != null
        ) { "Split QNN textual inversion lacks the completed UNet phase proof." }
    } else {
        require(result.optBoolean("conditioningArtifactConsumed", false) &&
            nativeEffective.optBoolean("conditioningArtifactConsumed", false)
        ) { "Shared QNN textual inversion conditioning was not consumed by native UNet execution." }
    }
    require(!result.has("textualInversions") && !result.has("textualInversionEvidence") &&
        !nativeEffective.has("textualInversions") &&
        !nativeEffective.has("textualInversionEvidence")
    ) { "QNN runtime returned untrusted textual inversion evidence instead of the MNN encoder proof." }

    val expectedCount = selection.bindings.size
    val expectedMask = (1L shl expectedCount) - 1L
    val artifacts = encoded.getJSONArray("textualInversions")
    val consumedEvidence = JSONObject(encoded.getJSONObject("textualInversionEvidence").toString())
        .put("conditioningConsumptionCount", expectedCount)
        .put("consumedMask", expectedMask)
        .put("bindingStage", "conditioning_consumed")
    result
        .put("textualInversions", JSONArray(artifacts.toString()))
        .put("textualInversionEvidence", JSONObject(consumedEvidence.toString()))
    nativeEffective
        .put("textualInversions", JSONArray(artifacts.toString()))
        .put("textualInversionEvidence", JSONObject(consumedEvidence.toString()))
    selection.executionAssetBinding?.let {
        val assets = encoded.getJSONArray("textualInversionExecutionAssets")
        listOf(result, nativeEffective).forEach { target ->
            target
                .put("textualInversionExecutionAssets", JSONArray(assets.toString()))
                .put(
                    "textualInversionExecutionAssetsSha256",
                    encoded.getString("textualInversionExecutionAssetsSha256")
                )
                .put(
                    "textualInversionExecutionRuntime",
                    encoded.getString("textualInversionExecutionRuntime")
                )
                .put(
                    "textualInversionExecutionBundleRoot",
                    encoded.getString("textualInversionExecutionBundleRoot")
                )
                .put(
                    "textualInversionExecutionProfileFingerprint",
                    encoded.getString("textualInversionExecutionProfileFingerprint")
                )
        }
    }
    return verifyStableDiffusionTextualInversionEvidence(
        result = result,
        selection = selection,
        expectedNativeMode = TextualInversionRuntime.QNN_HTP.nativeMode
    )
}

/**
 * Treats UltraFix as a distinct native algorithm, not as an img2img label. The bridge already
 * rejects incomplete native evidence; this second boundary binds that evidence to the immutable
 * Android request and to the exact PNG bytes that leave the isolated worker.
 */
internal fun verifyStableDiffusionUltraFixEvidence(
    result: JSONObject,
    request: LocalImageUltraFixOptions?,
    inputImage: LocalImagePreparedInput?,
    requestedUseCfg: Boolean,
    outputs: List<LocalImageOutput>
) {
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: error("stable-diffusion.cpp result is missing nativeEffective evidence.")
    val ultraFixOnlyFields = listOf(
        "ultraFix",
        "strengthMechanism",
        "outputSha256",
        "outputSizeBytes",
        "outputAtomicCommit"
    )
    if (request == null) {
        ultraFixOnlyFields.forEach { field ->
            require(!result.has(field) && !nativeEffective.has(field)) {
                "stable-diffusion.cpp reported UltraFix-only evidence for a regular request."
            }
        }
        return
    }

    val source = requireNotNull(inputImage) { "UltraFix evidence requires its prepared source." }
    require(outputs.size == 1) { "UltraFix must publish exactly one committed output." }

    fun JSONObject.requireExactKeys(expected: Set<String>, layer: String) {
        require(keys().asSequence().toSet() == expected) {
            "$layer UltraFix evidence fields do not match the strict schema."
        }
    }

    fun JSONObject.exactLong(field: String, layer: String): Long {
        require(has(field) && !isNull(field)) { "$layer is missing $field." }
        val raw = get(field)
        require(raw is Byte || raw is Short || raw is Int || raw is Long) {
            "$layer $field must be an exact integer."
        }
        return (raw as Number).toLong()
    }

    fun JSONObject.exactBoolean(field: String, layer: String): Boolean {
        require(has(field) && !isNull(field) && get(field) is Boolean) {
            "$layer $field must be boolean."
        }
        return getBoolean(field)
    }

    fun JSONObject.exactString(field: String, layer: String): String {
        require(has(field) && !isNull(field) && get(field) is String) {
            "$layer $field must be a string."
        }
        return getString(field)
    }

    fun JSONObject.exactDouble(field: String, layer: String): Double {
        require(has(field) && !isNull(field) && get(field) is Number) {
            "$layer $field must be numeric."
        }
        return (get(field) as Number).toDouble().also { value ->
            require(value.isFinite()) { "$layer $field must be finite." }
        }
    }

    data class StageEvidence(
        val invocationCount: Long,
        val successCount: Long,
        val tileInvocationCount: Long,
        val tileSuccessCount: Long,
        val stepCount: Long
    )

    data class VaeTilingPhaseEvidence(
        val invocationCount: Long,
        val successCount: Long,
        val plannedTileCount: Long,
        val tileComputeAttemptCount: Long,
        val tileComputeSuccessCount: Long,
        val tileSizeX: Long,
        val tileSizeY: Long,
        val overlapX: Double,
        val overlapY: Double
    )

    data class VaeTilingEvidence(
        val enabled: Boolean,
        val requestedTileSize: Long,
        val requestedOverlap: Double,
        val encode: VaeTilingPhaseEvidence,
        val decode: VaeTilingPhaseEvidence
    )

    data class UltraFixEvidence(
        val version: Long,
        val generationCompleted: Boolean,
        val cancelled: Boolean,
        val previewPublished: Boolean,
        val sourceWidth: Long,
        val sourceHeight: Long,
        val targetWidth: Long,
        val targetHeight: Long,
        val sourceFit: String,
        val sourceResizedWidth: Long,
        val sourceResizedHeight: Long,
        val sourceCropLeft: Long,
        val sourceCropTop: Long,
        val tileSize: Long,
        val overlap: Double,
        val inversionSteps: Long,
        val refinementSteps: Long,
        val denoiseStepCount: Long,
        val sampleMethod: String,
        val nativeScheduler: String,
        val vaeEncode: StageEvidence,
        val ddimInversion: StageEvidence,
        val tiledUnetRefinement: StageEvidence,
        val tiledVaeDecode: StageEvidence,
        val physicalDiffusionModelComputeCount: Long,
        val qualityStepEvaluationCount: Long,
        val noiseInjectionStepCount: Long,
        val noiseInjectionSeedFingerprint: String,
        val noiseInjectionChecksum: String,
        val structureGuidanceStepCount: Long,
        val structureGuidanceChecksum: String,
        val trajectoryNoiseChecksum: String
    )

    val stageFields = setOf(
        "invocationCount",
        "successCount",
        "tileInvocationCount",
        "tileSuccessCount",
        "stepCount"
    )
    val evidenceFields = setOf(
        "version",
        "generationCompleted",
        "cancelled",
        "previewPublished",
        "sourceWidth",
        "sourceHeight",
        "targetWidth",
        "targetHeight",
        "sourceFit",
        "sourceResizedWidth",
        "sourceResizedHeight",
        "sourceCropLeft",
        "sourceCropTop",
        "tileSize",
        "overlap",
        "inversionSteps",
        "refinementSteps",
        "denoiseStepCount",
        "sampleMethod",
        "nativeScheduler",
        "vaeEncode",
        "ddimInversion",
        "tiledUnetRefinement",
        "tiledVaeDecode",
        "physicalDiffusionModelComputeCount",
        "qualityStepEvaluationCount",
        "noiseInjectionStepCount",
        "noiseInjectionSeedFingerprint",
        "noiseInjectionChecksum",
        "structureGuidanceStepCount",
        "structureGuidanceChecksum",
        "trajectoryNoiseChecksum"
    )

    fun parseStage(parent: JSONObject, field: String, layer: String): StageEvidence {
        val stage = parent.optJSONObject(field) ?: error("$layer is missing $field.")
        stage.requireExactKeys(stageFields, "$layer.$field")
        return StageEvidence(
            invocationCount = stage.exactLong("invocationCount", "$layer.$field"),
            successCount = stage.exactLong("successCount", "$layer.$field"),
            tileInvocationCount = stage.exactLong("tileInvocationCount", "$layer.$field"),
            tileSuccessCount = stage.exactLong("tileSuccessCount", "$layer.$field"),
            stepCount = stage.exactLong("stepCount", "$layer.$field")
        )
    }

    fun parseEvidence(parent: JSONObject, layer: String): UltraFixEvidence {
        val evidence = parent.optJSONObject("ultraFix")
            ?: error("$layer UltraFix execution evidence is missing.")
        evidence.requireExactKeys(evidenceFields, "$layer.ultraFix")
        return UltraFixEvidence(
            version = evidence.exactLong("version", "$layer.ultraFix"),
            generationCompleted = evidence.exactBoolean("generationCompleted", "$layer.ultraFix"),
            cancelled = evidence.exactBoolean("cancelled", "$layer.ultraFix"),
            previewPublished = evidence.exactBoolean("previewPublished", "$layer.ultraFix"),
            sourceWidth = evidence.exactLong("sourceWidth", "$layer.ultraFix"),
            sourceHeight = evidence.exactLong("sourceHeight", "$layer.ultraFix"),
            targetWidth = evidence.exactLong("targetWidth", "$layer.ultraFix"),
            targetHeight = evidence.exactLong("targetHeight", "$layer.ultraFix"),
            sourceFit = evidence.exactString("sourceFit", "$layer.ultraFix"),
            sourceResizedWidth = evidence.exactLong("sourceResizedWidth", "$layer.ultraFix"),
            sourceResizedHeight = evidence.exactLong("sourceResizedHeight", "$layer.ultraFix"),
            sourceCropLeft = evidence.exactLong("sourceCropLeft", "$layer.ultraFix"),
            sourceCropTop = evidence.exactLong("sourceCropTop", "$layer.ultraFix"),
            tileSize = evidence.exactLong("tileSize", "$layer.ultraFix"),
            overlap = evidence.exactDouble("overlap", "$layer.ultraFix"),
            inversionSteps = evidence.exactLong("inversionSteps", "$layer.ultraFix"),
            refinementSteps = evidence.exactLong("refinementSteps", "$layer.ultraFix"),
            denoiseStepCount = evidence.exactLong("denoiseStepCount", "$layer.ultraFix"),
            sampleMethod = evidence.exactString("sampleMethod", "$layer.ultraFix"),
            nativeScheduler = evidence.exactString("nativeScheduler", "$layer.ultraFix"),
            vaeEncode = parseStage(evidence, "vaeEncode", "$layer.ultraFix"),
            ddimInversion = parseStage(evidence, "ddimInversion", "$layer.ultraFix"),
            tiledUnetRefinement = parseStage(evidence, "tiledUnetRefinement", "$layer.ultraFix"),
            tiledVaeDecode = parseStage(evidence, "tiledVaeDecode", "$layer.ultraFix"),
            physicalDiffusionModelComputeCount = evidence.exactLong(
                "physicalDiffusionModelComputeCount",
                "$layer.ultraFix"
            ),
            qualityStepEvaluationCount = evidence.exactLong(
                "qualityStepEvaluationCount",
                "$layer.ultraFix"
            ),
            noiseInjectionStepCount = evidence.exactLong(
                "noiseInjectionStepCount",
                "$layer.ultraFix"
            ),
            noiseInjectionSeedFingerprint = evidence.exactString(
                "noiseInjectionSeedFingerprint",
                "$layer.ultraFix"
            ),
            noiseInjectionChecksum = evidence.exactString(
                "noiseInjectionChecksum",
                "$layer.ultraFix"
            ),
            structureGuidanceStepCount = evidence.exactLong(
                "structureGuidanceStepCount",
                "$layer.ultraFix"
            ),
            structureGuidanceChecksum = evidence.exactString(
                "structureGuidanceChecksum",
                "$layer.ultraFix"
            ),
            trajectoryNoiseChecksum = evidence.exactString(
                "trajectoryNoiseChecksum",
                "$layer.ultraFix"
            )
        )
    }

    fun parseVaeTilingPhase(
        parent: JSONObject,
        field: String,
        layer: String
    ): VaeTilingPhaseEvidence {
        val phase = parent.optJSONObject(field) ?: error("$layer is missing $field.")
        return VaeTilingPhaseEvidence(
            invocationCount = phase.exactLong("invocationCount", "$layer.$field"),
            successCount = phase.exactLong("successCount", "$layer.$field"),
            plannedTileCount = phase.exactLong("plannedTileCount", "$layer.$field"),
            tileComputeAttemptCount = phase.exactLong(
                "tileComputeAttemptCount",
                "$layer.$field"
            ),
            tileComputeSuccessCount = phase.exactLong(
                "tileComputeSuccessCount",
                "$layer.$field"
            ),
            tileSizeX = phase.exactLong("tileSizeX", "$layer.$field"),
            tileSizeY = phase.exactLong("tileSizeY", "$layer.$field"),
            overlapX = phase.exactDouble("overlapX", "$layer.$field"),
            overlapY = phase.exactDouble("overlapY", "$layer.$field")
        )
    }

    fun parseVaeTiling(parent: JSONObject, layer: String): VaeTilingEvidence {
        val tiling = parent.optJSONObject("vaeTiling")
            ?: error("$layer UltraFix VAE tiling evidence is missing.")
        return VaeTilingEvidence(
            enabled = tiling.exactBoolean("enabled", "$layer.vaeTiling"),
            requestedTileSize = tiling.exactLong("requestedTileSize", "$layer.vaeTiling"),
            requestedOverlap = tiling.exactDouble("requestedOverlap", "$layer.vaeTiling"),
            encode = parseVaeTilingPhase(tiling, "encode", "$layer.vaeTiling"),
            decode = parseVaeTilingPhase(tiling, "decode", "$layer.vaeTiling")
        )
    }

    val outer = parseEvidence(result, "outer")
    val inner = parseEvidence(nativeEffective, "nativeEffective")
    require(outer == inner) { "Outer and nativeEffective UltraFix evidence conflict." }
    val outerVaeTiling = parseVaeTiling(result, "outer")
    val innerVaeTiling = parseVaeTiling(nativeEffective, "nativeEffective")
    require(outerVaeTiling == innerVaeTiling) {
        "Outer and nativeEffective UltraFix VAE tile plans conflict."
    }
    require(outerVaeTiling.enabled &&
        outerVaeTiling.requestedTileSize == request.tileSize.toLong() &&
        kotlin.math.abs(outerVaeTiling.requestedOverlap - request.overlap) <= 1.0e-6
    ) { "UltraFix VAE tiling evidence does not match the immutable tile request." }
    val sourceWidth = source.orientedWidth.toLong()
    val sourceHeight = source.orientedHeight.toLong()
    val targetWidth = request.targetWidth.toLong()
    val targetHeight = request.targetHeight.toLong()
    val fitByWidth = Math.multiplyExact(targetWidth, sourceHeight) >=
        Math.multiplyExact(targetHeight, sourceWidth)
    val expectedResizedWidth: Long
    val expectedResizedHeight: Long
    if (fitByWidth) {
        expectedResizedWidth = targetWidth
        expectedResizedHeight = Math.multiplyExact(sourceHeight, targetWidth) / sourceWidth
    } else {
        expectedResizedWidth = Math.multiplyExact(sourceWidth, targetHeight) / sourceHeight
        expectedResizedHeight = targetHeight
    }
    require(expectedResizedWidth >= targetWidth && expectedResizedHeight >= targetHeight) {
        "UltraFix center-cover geometry does not cover the target canvas."
    }
    val expectedCropLeft = (expectedResizedWidth - targetWidth) / 2L
    val expectedCropTop = (expectedResizedHeight - targetHeight) / 2L
    require(result.exactString("strengthMechanism", "outer") == "ddim_inversion" &&
        nativeEffective.exactString("strengthMechanism", "nativeEffective") == "ddim_inversion"
    ) { "UltraFix did not prove its DDIM inversion strength mechanism." }
    require(outer.version == STABLE_DIFFUSION_ULTRAFIX_EVIDENCE_VERSION &&
        outer.generationCompleted && !outer.cancelled && !outer.previewPublished &&
        outer.sourceWidth == source.orientedWidth.toLong() &&
        outer.sourceHeight == source.orientedHeight.toLong() &&
        outer.targetWidth == request.targetWidth.toLong() &&
        outer.targetHeight == request.targetHeight.toLong() &&
        outer.sourceFit == "cover_center" &&
        outer.sourceResizedWidth == expectedResizedWidth &&
        outer.sourceResizedHeight == expectedResizedHeight &&
        outer.sourceCropLeft == expectedCropLeft &&
        outer.sourceCropTop == expectedCropTop &&
        outer.tileSize == request.tileSize.toLong() &&
        kotlin.math.abs(outer.overlap - request.overlap) <= 1.0e-6 &&
        outer.inversionSteps == request.inversionSteps.toLong() &&
        outer.refinementSteps == request.refinementSteps.toLong() &&
        outer.denoiseStepCount == request.inversionSteps.toLong() &&
        outer.sampleMethod == result.exactString("sampleMethod", "outer") &&
        outer.nativeScheduler == result.exactString("nativeScheduler", "outer")
    ) { "Native UltraFix evidence does not match the immutable Android request." }

    fun requireStage(
        name: String,
        stage: StageEvidence,
        invocations: Long,
        steps: Long,
        requireTiles: Boolean
    ) {
        require(stage.invocationCount == invocations && stage.successCount == invocations &&
            stage.stepCount == steps && stage.tileInvocationCount == stage.tileSuccessCount &&
            (!requireTiles || stage.tileInvocationCount > 0L)
        ) { "$name UltraFix stage evidence is incomplete." }
    }

    requireStage("VAE encode", outer.vaeEncode, 1L, 1L, requireTiles = true)
    requireStage(
        "DDIM inversion",
        outer.ddimInversion,
        request.inversionSteps.toLong(),
        request.inversionSteps.toLong(),
        requireTiles = true
    )
    requireStage(
        "tiled UNet refinement",
        outer.tiledUnetRefinement,
        request.inversionSteps.toLong(),
        request.inversionSteps.toLong(),
        requireTiles = true
    )
    requireStage("tiled VAE decode", outer.tiledVaeDecode, 1L, 1L, requireTiles = true)

    fun requireVaePhasePlan(
        name: String,
        stage: StageEvidence,
        phase: VaeTilingPhaseEvidence
    ) {
        require(phase.invocationCount == 1L && phase.successCount == 1L &&
            phase.plannedTileCount > 0L &&
            phase.tileComputeAttemptCount == phase.plannedTileCount &&
            phase.tileComputeSuccessCount == phase.tileComputeAttemptCount &&
            phase.tileSizeX > 0L && phase.tileSizeY > 0L &&
            phase.overlapX in 0.0..0.5 && phase.overlapY in 0.0..0.5 &&
            stage.invocationCount == phase.invocationCount &&
            stage.successCount == phase.successCount &&
            stage.tileInvocationCount == phase.plannedTileCount &&
            stage.tileInvocationCount == phase.tileComputeAttemptCount &&
            stage.tileSuccessCount == phase.tileComputeSuccessCount
        ) { "$name UltraFix evidence does not match its physical VAE tile plan." }
    }

    fun requireRepeatedTilePlan(
        name: String,
        stage: StageEvidence,
        completedSteps: Long
    ): Long {
        require(completedSteps > 0L &&
            stage.invocationCount == completedSteps &&
            stage.successCount == completedSteps &&
            stage.stepCount == completedSteps &&
            stage.tileInvocationCount > 0L &&
            stage.tileInvocationCount == stage.tileSuccessCount &&
            stage.tileInvocationCount % completedSteps == 0L
        ) { "$name UltraFix evidence does not contain a complete per-step tile plan." }
        val tilesPerStep = stage.tileInvocationCount / completedSteps
        require(tilesPerStep > 0L &&
            Math.multiplyExact(tilesPerStep, completedSteps) == stage.tileInvocationCount
        ) { "$name UltraFix tile plan is invalid." }
        return tilesPerStep
    }

    requireVaePhasePlan("VAE encode", outer.vaeEncode, outerVaeTiling.encode)
    requireVaePhasePlan("tiled VAE decode", outer.tiledVaeDecode, outerVaeTiling.decode)
    val inversionTilesPerStep = requireRepeatedTilePlan(
        "DDIM inversion",
        outer.ddimInversion,
        request.inversionSteps.toLong()
    )
    val refinementTilesPerStep = requireRepeatedTilePlan(
        "tiled UNet refinement",
        outer.tiledUnetRefinement,
        request.inversionSteps.toLong()
    )
    require(inversionTilesPerStep == refinementTilesPerStep) {
        "DDIM inversion and UNet refinement did not use one latent tile plan."
    }

    val expectedPositive = Math.addExact(
        outer.ddimInversion.tileInvocationCount,
        outer.tiledUnetRefinement.tileInvocationCount
    )
    val expectedNegative = if (requestedUseCfg) {
        outer.tiledUnetRefinement.tileInvocationCount
    } else {
        0L
    }
    val expectedPhysical = Math.addExact(expectedPositive, expectedNegative)
    require(outer.physicalDiffusionModelComputeCount == expectedPhysical &&
        result.exactLong("actualDiffusionModelComputeCount", "outer") == expectedPhysical &&
        result.exactLong("actualPositiveDiffusionModelComputeCount", "outer") == expectedPositive &&
        result.exactLong("actualNegativeDiffusionModelComputeCount", "outer") == expectedNegative &&
        result.exactLong("actualAuxiliaryDiffusionModelComputeCount", "outer") == 0L &&
        result.exactLong("actualSamplingStepCount", "outer") == request.inversionSteps.toLong() &&
        result.exactLong("actualSamplingPassCount", "outer") == 1L &&
        result.exactLong("totalUnetExecutionCount", "outer") == expectedPhysical &&
        nativeEffective.exactLong("positiveDiffusionModelComputeCount", "nativeEffective") ==
            expectedPositive &&
        nativeEffective.exactLong("negativeDiffusionModelComputeCount", "nativeEffective") ==
            expectedNegative &&
        nativeEffective.exactLong("auxiliaryDiffusionModelComputeCount", "nativeEffective") == 0L &&
        nativeEffective.exactLong("samplingPassCount", "nativeEffective") == 1L &&
        nativeEffective.exactLong("totalUnetExecutionCount", "nativeEffective") == expectedPhysical
    ) { "UltraFix physical diffusion evidence is incomplete or misclassified." }

    val qualityStepCount = Math.max(outer.inversionSteps - 1L, 0L)
    val executedSeed = result.exactLong("seed", "outer")
    val zeroQualityChecksum = "0000000000000000"
    val noiseEvidenceConsistent =
        (outer.noiseInjectionStepCount == 0L) ==
            (outer.noiseInjectionChecksum == zeroQualityChecksum)
    val structureEvidenceConsistent =
        (outer.structureGuidanceStepCount == 0L) ==
            (outer.structureGuidanceChecksum == zeroQualityChecksum)
    val qualityActionCount = runCatching {
        Math.addExact(outer.noiseInjectionStepCount, outer.structureGuidanceStepCount)
    }.getOrDefault(-1L)
    require(executedSeed >= 0L &&
        nativeEffective.exactLong("seed", "nativeEffective") == executedSeed
    ) { "UltraFix quality evidence is missing the resolved non-negative seed." }
    require(outer.qualityStepEvaluationCount == qualityStepCount &&
        outer.noiseInjectionStepCount in 0L..qualityStepCount &&
        outer.structureGuidanceStepCount in 0L..qualityStepCount &&
        outer.noiseInjectionSeedFingerprint ==
            localImageUltraFixNoiseSeedFingerprint(executedSeed, outer.inversionSteps.toInt()) &&
        QNN_OUTPUT_SHA256.matches(outer.noiseInjectionSeedFingerprint) &&
        LOWERCASE_UINT64_HEX.matches(outer.noiseInjectionChecksum) &&
        LOWERCASE_UINT64_HEX.matches(outer.structureGuidanceChecksum) &&
        LOWERCASE_UINT64_HEX.matches(outer.trajectoryNoiseChecksum) &&
        if (qualityStepCount == 0L) {
            outer.noiseInjectionStepCount == 0L &&
                outer.structureGuidanceStepCount == 0L &&
                outer.noiseInjectionChecksum == zeroQualityChecksum &&
                outer.structureGuidanceChecksum == zeroQualityChecksum &&
                outer.trajectoryNoiseChecksum == zeroQualityChecksum
        } else {
            noiseEvidenceConsistent && structureEvidenceConsistent &&
                qualityActionCount >= qualityStepCount &&
                outer.trajectoryNoiseChecksum != zeroQualityChecksum
        }
    ) { "UltraFix quality, seed, and checksum evidence is incomplete." }

    data class CommitEvidence(val sha256: String, val sizeBytes: Long, val atomic: Boolean)
    fun parseCommit(parent: JSONObject, layer: String): CommitEvidence {
        val sha256 = parent.exactString("outputSha256", layer)
        require(QNN_OUTPUT_SHA256.matches(sha256)) { "$layer output SHA-256 is malformed." }
        return CommitEvidence(
            sha256 = sha256,
            sizeBytes = parent.exactLong("outputSizeBytes", layer),
            atomic = parent.exactBoolean("outputAtomicCommit", layer)
        )
    }

    val outerCommit = parseCommit(result, "outer")
    val innerCommit = parseCommit(nativeEffective, "nativeEffective")
    require(outerCommit == innerCommit && outerCommit.atomic &&
        outerCommit.sizeBytes in
            MIN_STABLE_DIFFUSION_OUTPUT_PNG_BYTES..MAX_STABLE_DIFFUSION_OUTPUT_PNG_BYTES
    ) { "UltraFix output commit evidence is incomplete or conflicts across layers." }
    val output = outputs.single()
    val actualSha256 = MessageDigest.getInstance("SHA-256")
        .digest(output.bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    require(output.mimeType == "image/png" &&
        output.bytes.size.toLong() == outerCommit.sizeBytes &&
        actualSha256 == outerCommit.sha256
    ) { "UltraFix output evidence does not bind the exact returned PNG bytes." }
}

/** Independent QNN UltraFix v2 verifier for both shared and isolated phase topologies. */
internal fun verifyQnnUltraFixEvidence(
    result: JSONObject,
    request: LocalImageUltraFixOptions?,
    inputImage: LocalImagePreparedInput?,
    requestedUseCfg: Boolean,
    outputBytes: ByteArray,
    outputMimeType: String
) {
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: error("QNN result is missing nativeEffective evidence.")
    if (request == null) {
        require(!result.has("ultraFix") && !nativeEffective.has("ultraFix") &&
            !result.has("strengthMechanism") && !nativeEffective.has("strengthMechanism")
        ) { "QNN reported UltraFix-only evidence for a regular request." }
        return
    }
    val source = requireNotNull(inputImage) { "QNN UltraFix evidence requires its prepared source." }

    fun JSONObject.requireExactKeys(expected: Set<String>, layer: String) {
        require(keys().asSequence().toSet() == expected) {
            "$layer QNN UltraFix evidence fields do not match the strict v2 schema."
        }
    }

    fun JSONObject.exactLong(field: String, layer: String): Long {
        require(has(field) && !isNull(field)) { "$layer is missing $field." }
        val raw = get(field)
        require(raw is Byte || raw is Short || raw is Int || raw is Long) {
            "$layer $field must be an exact integer."
        }
        return (raw as Number).toLong()
    }

    fun JSONObject.exactBoolean(field: String, layer: String): Boolean {
        require(has(field) && !isNull(field) && get(field) is Boolean) {
            "$layer $field must be boolean."
        }
        return getBoolean(field)
    }

    fun JSONObject.exactString(field: String, layer: String): String {
        require(has(field) && !isNull(field) && get(field) is String) {
            "$layer $field must be a string."
        }
        return getString(field)
    }

    fun JSONObject.exactDouble(field: String, layer: String): Double {
        require(has(field) && !isNull(field) && get(field) is Number) {
            "$layer $field must be numeric."
        }
        return (get(field) as Number).toDouble().also { value ->
            require(value.isFinite()) { "$layer $field must be finite." }
        }
    }

    data class StageEvidence(
        val invocationCount: Long,
        val successCount: Long,
        val tileInvocationCount: Long,
        val tileSuccessCount: Long,
        val stepCount: Long
    )

    data class QnnEvidence(
        val version: Long,
        val generationCompleted: Boolean,
        val cancelled: Boolean,
        val previewPublished: Boolean,
        val sourceWidth: Long,
        val sourceHeight: Long,
        val targetWidth: Long,
        val targetHeight: Long,
        val sourceFit: String,
        val sourceResizedWidth: Long,
        val sourceResizedHeight: Long,
        val sourceCropLeft: Long,
        val sourceCropTop: Long,
        val tileSize: Long,
        val overlap: Double,
        val tileCount: Long,
        val tilePlanSha256: String,
        val inversionSteps: Long,
        val refinementSteps: Long,
        val denoiseStepCount: Long,
        val sampleMethod: String,
        val nativeScheduler: String,
        val vaeEncode: StageEvidence,
        val ddimInversion: StageEvidence,
        val tiledUnetRefinement: StageEvidence,
        val tiledVaeDecode: StageEvidence,
        val encoderGraphExecutionCount: Long,
        val inversionPositiveGraphExecutionCount: Long,
        val refinementPositiveGraphExecutionCount: Long,
        val refinementNegativeGraphExecutionCount: Long,
        val decoderGraphExecutionCount: Long,
        val physicalDiffusionModelComputeCount: Long,
        val qualityStepEvaluationCount: Long,
        val noiseInjectionStepCount: Long,
        val noiseInjectionSeedFingerprint: String,
        val noiseInjectionChecksum: String,
        val structureGuidanceStepCount: Long,
        val structureGuidanceChecksum: String,
        val trajectoryNoiseChecksum: String,
        val outputSha256: String,
        val outputBytes: Long,
        val outputAtomicCommit: Boolean
    )

    val stageFields = setOf(
        "invocationCount", "successCount", "tileInvocationCount", "tileSuccessCount", "stepCount"
    )
    val evidenceFields = setOf(
        "version", "generationCompleted", "cancelled", "previewPublished",
        "sourceWidth", "sourceHeight", "targetWidth", "targetHeight", "sourceFit",
        "sourceResizedWidth", "sourceResizedHeight", "sourceCropLeft", "sourceCropTop",
        "tileSize", "overlap", "tileCount", "tilePlanSha256", "inversionSteps",
        "refinementSteps", "denoiseStepCount", "sampleMethod", "nativeScheduler",
        "vaeEncode", "ddimInversion", "tiledUnetRefinement", "tiledVaeDecode",
        "encoderGraphExecutionCount", "inversionPositiveGraphExecutionCount",
        "refinementPositiveGraphExecutionCount", "refinementNegativeGraphExecutionCount",
        "decoderGraphExecutionCount", "physicalDiffusionModelComputeCount",
        "qualityStepEvaluationCount", "noiseInjectionStepCount",
        "noiseInjectionSeedFingerprint", "noiseInjectionChecksum",
        "structureGuidanceStepCount", "structureGuidanceChecksum", "trajectoryNoiseChecksum",
        "outputSha256", "outputBytes", "outputAtomicCommit"
    )

    fun parseStage(parent: JSONObject, field: String, layer: String): StageEvidence {
        val stage = parent.optJSONObject(field) ?: error("$layer is missing $field.")
        stage.requireExactKeys(stageFields, "$layer.$field")
        return StageEvidence(
            invocationCount = stage.exactLong("invocationCount", "$layer.$field"),
            successCount = stage.exactLong("successCount", "$layer.$field"),
            tileInvocationCount = stage.exactLong("tileInvocationCount", "$layer.$field"),
            tileSuccessCount = stage.exactLong("tileSuccessCount", "$layer.$field"),
            stepCount = stage.exactLong("stepCount", "$layer.$field")
        )
    }

    fun parseEvidence(parent: JSONObject, layer: String): QnnEvidence {
        val evidence = parent.optJSONObject("ultraFix")
            ?: error("$layer QNN UltraFix execution evidence is missing.")
        evidence.requireExactKeys(evidenceFields, "$layer.ultraFix")
        return QnnEvidence(
            version = evidence.exactLong("version", "$layer.ultraFix"),
            generationCompleted = evidence.exactBoolean("generationCompleted", "$layer.ultraFix"),
            cancelled = evidence.exactBoolean("cancelled", "$layer.ultraFix"),
            previewPublished = evidence.exactBoolean("previewPublished", "$layer.ultraFix"),
            sourceWidth = evidence.exactLong("sourceWidth", "$layer.ultraFix"),
            sourceHeight = evidence.exactLong("sourceHeight", "$layer.ultraFix"),
            targetWidth = evidence.exactLong("targetWidth", "$layer.ultraFix"),
            targetHeight = evidence.exactLong("targetHeight", "$layer.ultraFix"),
            sourceFit = evidence.exactString("sourceFit", "$layer.ultraFix"),
            sourceResizedWidth = evidence.exactLong("sourceResizedWidth", "$layer.ultraFix"),
            sourceResizedHeight = evidence.exactLong("sourceResizedHeight", "$layer.ultraFix"),
            sourceCropLeft = evidence.exactLong("sourceCropLeft", "$layer.ultraFix"),
            sourceCropTop = evidence.exactLong("sourceCropTop", "$layer.ultraFix"),
            tileSize = evidence.exactLong("tileSize", "$layer.ultraFix"),
            overlap = evidence.exactDouble("overlap", "$layer.ultraFix"),
            tileCount = evidence.exactLong("tileCount", "$layer.ultraFix"),
            tilePlanSha256 = evidence.exactString("tilePlanSha256", "$layer.ultraFix"),
            inversionSteps = evidence.exactLong("inversionSteps", "$layer.ultraFix"),
            refinementSteps = evidence.exactLong("refinementSteps", "$layer.ultraFix"),
            denoiseStepCount = evidence.exactLong("denoiseStepCount", "$layer.ultraFix"),
            sampleMethod = evidence.exactString("sampleMethod", "$layer.ultraFix"),
            nativeScheduler = evidence.exactString("nativeScheduler", "$layer.ultraFix"),
            vaeEncode = parseStage(evidence, "vaeEncode", "$layer.ultraFix"),
            ddimInversion = parseStage(evidence, "ddimInversion", "$layer.ultraFix"),
            tiledUnetRefinement = parseStage(evidence, "tiledUnetRefinement", "$layer.ultraFix"),
            tiledVaeDecode = parseStage(evidence, "tiledVaeDecode", "$layer.ultraFix"),
            encoderGraphExecutionCount = evidence.exactLong(
                "encoderGraphExecutionCount", "$layer.ultraFix"
            ),
            inversionPositiveGraphExecutionCount = evidence.exactLong(
                "inversionPositiveGraphExecutionCount", "$layer.ultraFix"
            ),
            refinementPositiveGraphExecutionCount = evidence.exactLong(
                "refinementPositiveGraphExecutionCount", "$layer.ultraFix"
            ),
            refinementNegativeGraphExecutionCount = evidence.exactLong(
                "refinementNegativeGraphExecutionCount", "$layer.ultraFix"
            ),
            decoderGraphExecutionCount = evidence.exactLong(
                "decoderGraphExecutionCount", "$layer.ultraFix"
            ),
            physicalDiffusionModelComputeCount = evidence.exactLong(
                "physicalDiffusionModelComputeCount", "$layer.ultraFix"
            ),
            qualityStepEvaluationCount = evidence.exactLong(
                "qualityStepEvaluationCount", "$layer.ultraFix"
            ),
            noiseInjectionStepCount = evidence.exactLong(
                "noiseInjectionStepCount", "$layer.ultraFix"
            ),
            noiseInjectionSeedFingerprint = evidence.exactString(
                "noiseInjectionSeedFingerprint", "$layer.ultraFix"
            ),
            noiseInjectionChecksum = evidence.exactString(
                "noiseInjectionChecksum", "$layer.ultraFix"
            ),
            structureGuidanceStepCount = evidence.exactLong(
                "structureGuidanceStepCount", "$layer.ultraFix"
            ),
            structureGuidanceChecksum = evidence.exactString(
                "structureGuidanceChecksum", "$layer.ultraFix"
            ),
            trajectoryNoiseChecksum = evidence.exactString(
                "trajectoryNoiseChecksum", "$layer.ultraFix"
            ),
            outputSha256 = evidence.exactString("outputSha256", "$layer.ultraFix"),
            outputBytes = evidence.exactLong("outputBytes", "$layer.ultraFix"),
            outputAtomicCommit = evidence.exactBoolean("outputAtomicCommit", "$layer.ultraFix")
        )
    }

    val outer = parseEvidence(result, "outer")
    val inner = parseEvidence(nativeEffective, "nativeEffective")
    require(outer == inner) { "Outer and nativeEffective QNN UltraFix evidence conflict." }
    require(result.exactString("strengthMechanism", "outer") == "ddim_inversion" &&
        nativeEffective.exactString("strengthMechanism", "nativeEffective") == "ddim_inversion"
    ) { "QNN UltraFix did not prove DDIM inversion strength mechanism." }
    require(outer.version == QNN_ULTRAFIX_EVIDENCE_VERSION &&
        outer.generationCompleted && !outer.cancelled && !outer.previewPublished
    ) { "QNN UltraFix evidence is incomplete or has an unsupported version." }

    val plan = localQnnUltraFixTilePlanEvidence(request)
    val sourceWidth = source.orientedWidth.toLong()
    val sourceHeight = source.orientedHeight.toLong()
    val targetWidth = request.targetWidth.toLong()
    val targetHeight = request.targetHeight.toLong()
    require(sourceWidth > 0L && sourceHeight > 0L) {
        "QNN UltraFix source geometry must retain positive prepared dimensions."
    }
    val fitByWidth = Math.multiplyExact(targetWidth, sourceHeight) >=
        Math.multiplyExact(targetHeight, sourceWidth)
    val expectedResizedWidth: Long
    val expectedResizedHeight: Long
    if (fitByWidth) {
        expectedResizedWidth = targetWidth
        expectedResizedHeight = Math.multiplyExact(sourceHeight, targetWidth) / sourceWidth
    } else {
        expectedResizedWidth = Math.multiplyExact(sourceWidth, targetHeight) / sourceHeight
        expectedResizedHeight = targetHeight
    }
    require(outer.sourceWidth == sourceWidth && outer.sourceHeight == sourceHeight &&
        outer.targetWidth == targetWidth && outer.targetHeight == targetHeight &&
        outer.sourceFit == "cover_center" &&
        outer.sourceResizedWidth == expectedResizedWidth &&
        outer.sourceResizedHeight == expectedResizedHeight &&
        outer.sourceCropLeft == (expectedResizedWidth - targetWidth) / 2L &&
        outer.sourceCropTop == (expectedResizedHeight - targetHeight) / 2L &&
        outer.tileSize == request.tileSize.toLong() &&
        kotlin.math.abs(outer.overlap - request.overlap) <= 1.0e-6 &&
        outer.tileCount == plan.tileCount && outer.tilePlanSha256 == plan.tilePlanSha256 &&
        QNN_OUTPUT_SHA256.matches(outer.tilePlanSha256) &&
        outer.inversionSteps == request.inversionSteps.toLong() &&
        outer.refinementSteps == request.refinementSteps.toLong() &&
        outer.denoiseStepCount == request.inversionSteps.toLong()
    ) { "QNN UltraFix evidence does not match the immutable request or fixed tile plan." }
    listOf("sampleMethod", "nativeScheduler").forEach { field ->
        val value = outer.run { if (field == "sampleMethod") sampleMethod else nativeScheduler }
        if (result.has(field)) require(result.exactString(field, "outer") == value) {
            "QNN outer $field evidence conflicts with nested UltraFix evidence."
        }
        if (nativeEffective.has(field)) require(nativeEffective.exactString(field, "nativeEffective") == value) {
            "QNN nativeEffective $field evidence conflicts with nested UltraFix evidence."
        }
    }

    fun requireStage(name: String, stage: StageEvidence, invocations: Long, tiles: Long, steps: Long) {
        require(stage.invocationCount == invocations && stage.successCount == invocations &&
            stage.tileInvocationCount == tiles && stage.tileSuccessCount == tiles &&
            stage.stepCount == steps
        ) { "$name QNN UltraFix stage evidence is incomplete." }
    }
    val tail = request.inversionSteps.toLong()
    val branches = if (requestedUseCfg) 2L else 1L
    val inversionTiles = Math.multiplyExact(plan.tileCount, tail)
    val refinementPositive = inversionTiles
    val refinementNegative = if (requestedUseCfg) inversionTiles else 0L
    val refinementTiles = Math.addExact(refinementPositive, refinementNegative)
    val expectedPhysical = Math.addExact(inversionTiles, refinementTiles)
    requireStage("VAE encode", outer.vaeEncode, 1L, plan.tileCount, 1L)
    requireStage("DDIM inversion", outer.ddimInversion, tail, inversionTiles, tail)
    requireStage("tiled UNet refinement", outer.tiledUnetRefinement, tail, refinementTiles, tail)
    requireStage("tiled VAE decode", outer.tiledVaeDecode, 1L, plan.tileCount, 1L)
    require(outer.encoderGraphExecutionCount == plan.tileCount &&
        outer.inversionPositiveGraphExecutionCount == inversionTiles &&
        outer.refinementPositiveGraphExecutionCount == refinementPositive &&
        outer.refinementNegativeGraphExecutionCount == refinementNegative &&
        outer.decoderGraphExecutionCount == plan.tileCount &&
        outer.physicalDiffusionModelComputeCount == expectedPhysical &&
        nativeEffective.exactBoolean("useCfg", "nativeEffective") == requestedUseCfg &&
        result.exactBoolean("useCfg", "outer") == requestedUseCfg &&
        nativeEffective.exactLong("positiveDiffusionModelComputeCount", "nativeEffective") ==
            Math.addExact(inversionTiles, refinementPositive) &&
        nativeEffective.exactLong("negativeDiffusionModelComputeCount", "nativeEffective") ==
            refinementNegative &&
        nativeEffective.exactLong("auxiliaryDiffusionModelComputeCount", "nativeEffective") == 0L &&
        nativeEffective.exactLong("samplingPassCount", "nativeEffective") == 1L &&
        nativeEffective.exactLong("totalUnetExecutionCount", "nativeEffective") == expectedPhysical
    ) { "QNN UltraFix logical and physical graph counts are inconsistent." }
    if (result.has("actualDiffusionModelComputeCount")) {
        require(result.exactLong("actualDiffusionModelComputeCount", "outer") == expectedPhysical &&
            result.exactLong("actualPositiveDiffusionModelComputeCount", "outer") ==
                inversionTiles + refinementPositive &&
            result.exactLong("actualNegativeDiffusionModelComputeCount", "outer") == refinementNegative &&
            result.exactLong("actualAuxiliaryDiffusionModelComputeCount", "outer") == 0L
        ) { "QNN UltraFix outer physical counters are inconsistent." }
    }

    val executedSeed = result.exactLong("seed", "outer")
    val zeroQualityChecksum = "0000000000000000"
    val noiseEvidenceConsistent =
        (outer.noiseInjectionStepCount == 0L) ==
            (outer.noiseInjectionChecksum == zeroQualityChecksum)
    val structureEvidenceConsistent =
        (outer.structureGuidanceStepCount == 0L) ==
            (outer.structureGuidanceChecksum == zeroQualityChecksum)
    val qualityActionCount = runCatching {
        Math.addExact(outer.noiseInjectionStepCount, outer.structureGuidanceStepCount)
    }.getOrDefault(-1L)
    require(executedSeed in 0L..0xffff_ffffL &&
        nativeEffective.exactLong("seed", "nativeEffective") == executedSeed &&
        outer.qualityStepEvaluationCount == Math.max(tail - 1L, 0L) &&
        outer.noiseInjectionStepCount in 0L..outer.qualityStepEvaluationCount &&
        outer.structureGuidanceStepCount in 0L..outer.qualityStepEvaluationCount &&
        outer.noiseInjectionSeedFingerprint ==
            localImageUltraFixNoiseSeedFingerprint(executedSeed, request.inversionSteps) &&
        QNN_OUTPUT_SHA256.matches(outer.noiseInjectionSeedFingerprint) &&
        LOWERCASE_UINT64_HEX.matches(outer.noiseInjectionChecksum) &&
        LOWERCASE_UINT64_HEX.matches(outer.structureGuidanceChecksum) &&
        LOWERCASE_UINT64_HEX.matches(outer.trajectoryNoiseChecksum) &&
        if (tail == 1L) {
            outer.noiseInjectionStepCount == 0L && outer.structureGuidanceStepCount == 0L &&
                outer.noiseInjectionChecksum == zeroQualityChecksum &&
                outer.structureGuidanceChecksum == zeroQualityChecksum &&
                outer.trajectoryNoiseChecksum == zeroQualityChecksum
        } else {
            noiseEvidenceConsistent && structureEvidenceConsistent &&
                qualityActionCount >= outer.qualityStepEvaluationCount &&
                outer.trajectoryNoiseChecksum != zeroQualityChecksum
        }
    ) { "QNN UltraFix quality, seed, or checksum evidence is invalid." }

    listOf(result, nativeEffective).forEach { layer ->
        require(layer.exactBoolean("img2imgAddNoiseApplied", "QNN UltraFix") == false &&
            layer.exactString("img2imgNoiseChecksum", "QNN UltraFix") == "0000000000000000"
        ) { "QNN UltraFix must prove addNoise=false." }
    }

    data class CommitEvidence(val sha256: String, val sizeBytes: Long, val atomic: Boolean)
    fun parseCommit(parent: JSONObject, layer: String): CommitEvidence {
        val sha256 = parent.exactString("outputSha256", layer)
        require(QNN_OUTPUT_SHA256.matches(sha256)) { "$layer output SHA-256 is malformed." }
        return CommitEvidence(
            sha256 = sha256,
            sizeBytes = parent.exactLong("outputSizeBytes", layer),
            atomic = parent.exactBoolean("outputAtomicCommit", layer)
        )
    }
    val outerCommit = parseCommit(result, "outer")
    val innerCommit = parseCommit(nativeEffective, "nativeEffective")
    val nestedCommit = CommitEvidence(
        sha256 = outer.outputSha256,
        sizeBytes = outer.outputBytes,
        atomic = outer.outputAtomicCommit
    )
    require(outerCommit == innerCommit && outerCommit == nestedCommit && outerCommit.atomic &&
        outerCommit.sizeBytes in MIN_STABLE_DIFFUSION_OUTPUT_PNG_BYTES..MAX_STABLE_DIFFUSION_OUTPUT_PNG_BYTES &&
        outputMimeType == "image/png" && outputBytes.size.toLong() == outerCommit.sizeBytes
    ) { "QNN UltraFix output commit evidence is incomplete or conflicts across layers." }
    val actualSha256 = MessageDigest.getInstance("SHA-256")
        .digest(outputBytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    require(actualSha256 == outerCommit.sha256) {
        "QNN UltraFix output evidence does not bind the exact returned PNG bytes."
    }
}

class LocalImageProvider(context: Context) {
    private val appContext = context.applicationContext
    private val textualInversionStore by lazy { TextualInversionStore(appContext) }
    private val bridge by lazy { NativeStableDiffusionBridge() }
    private val mnnDiffusionBridge by lazy { NativeMnnDiffusionBridge() }
    private val qnnBridge by lazy { NativeQnnBridge() }
    private val sdxlCoordinator by lazy { SdxlTwoPhaseCoordinator(appContext) }
    private val cancellationRequested = AtomicBoolean(false)
    private val activeSplitSdxlTerminal =
        AtomicReference<SplitSdxlRequestTerminalArbiter?>(null)

    @Volatile
    private var activeRuntime: LocalImageRuntime? = null

    fun begin(runtime: LocalImageRuntime) {
        cancellationRequested.set(false)
        if (runtime in setOf(LocalImageRuntime.QNN_HTP, LocalImageRuntime.MNN_DIFFUSION) &&
            NativeMnnDiffusionBridge.isAvailable
        ) {
            runCatching { mnnDiffusionBridge.resetImageCancellation() }
        }
        if (runtime == LocalImageRuntime.QNN_HTP) {
            sdxlCoordinator.begin()
        }
        activeRuntime = runtime
    }

    fun cancel(): Boolean {
        return when (activeRuntime ?: return false) {
            LocalImageRuntime.QNN_HTP -> {
                // The split request state is the linearization point against final output transfer.
                activeSplitSdxlTerminal.get()?.cancel()
                cancellationRequested.set(true)
                activeSplitSdxlTerminal.get()?.cancel()
                runCatching { sdxlCoordinator.cancel() }
                if (NativeQnnBridge.isAvailable) {
                    runCatching { qnnBridge.cancelImageGeneration() }
                }
                if (NativeMnnDiffusionBridge.isAvailable) {
                    runCatching { mnnDiffusionBridge.cancel() }
                }
                true
            }
            LocalImageRuntime.MNN_DIFFUSION -> {
                cancellationRequested.set(true)
                if (NativeMnnDiffusionBridge.isAvailable) {
                    runCatching { mnnDiffusionBridge.cancel() }
                }
                true
            }
            LocalImageRuntime.STABLE_DIFFUSION_CPP -> {
                cancellationRequested.set(true)
                if (NativeStableDiffusionBridge.isAvailable) {
                    runCatching { bridge.cancel() }
                }
                true
            }
            else -> false
        }
    }

    fun nativeConfig(): JSONObject? =
        if (NativeStableDiffusionBridge.isAvailable) {
            runCatching { JSONObject(bridge.getNativeConfig()) }.getOrNull()
        } else {
            null
        }

    suspend fun upscale(
        input: LocalImagePreparedInput,
        upscaler: LocalImagePreparedUpscaler,
        targetScale: Int,
        tileSize: Int = 128,
        threads: Int = defaultLocalImageThreads(),
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult = withContext(Dispatchers.IO) {
        if (activeRuntime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
            begin(LocalImageRuntime.STABLE_DIFFUSION_CPP)
        }
        val nativeOutput = File(
            appContext.cacheDir,
            "local-image-upscale-native-${UUID.randomUUID()}.png"
        ).canonicalFile
        val postprocessedOutput = File(
            appContext.cacheDir,
            "local-image-upscale-final-${UUID.randomUUID()}.png"
        ).canonicalFile
        require(nativeOutput.parentFile == appContext.cacheDir.canonicalFile &&
            postprocessedOutput.parentFile == appContext.cacheDir.canonicalFile
        ) {
            "Upscale output escaped the app cache directory."
        }
        try {
            coroutineContext.ensureActive()
            require(NativeStableDiffusionBridge.isAvailable) {
                val reason = NativeStableDiffusionBridge.loadError?.message.orEmpty()
                "stable-diffusion.cpp upscaler failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(targetScale in setOf(2, 3, 4)) { "Upscale target scale must be 2, 3, or 4." }
            validatedLocalImageUpscalePublicationDimensions(input.width, input.height, targetScale)
            require(tileSize in 32..1_024 && tileSize % 8 == 0) {
                "Upscale tile size must be a multiple of 8 in 32-1024."
            }
            require(threads in 1..64) { "Upscale thread count must be in 1-64." }
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            val upscalerRoot = File(upscaler.path).canonicalFile.parentFile
                ?: error("Upscaler model has no app-private root.")
            val params = JSONObject()
                .put("upscalerId", upscaler.id)
                .put("upscalerSha256", upscaler.sha256)
                .put("upscalerSizeBytes", upscaler.sizeBytes)
                .put("inputImageSha256", input.sha256)
                .put("targetScale", targetScale)
                .put("tileSize", tileSize)
                .put("threads", threads)
            val progressPoller = launch {
                while (isActive) {
                    bridge.currentProgressOrNull()?.let(onProgress)
                    delay(500)
                }
            }
            val raw = try {
                bridge.upscale(
                    upscalerPath = upscaler.path,
                    upscalerRoot = upscalerRoot.canonicalPath,
                    inputPath = input.path,
                    paramsJson = params.toString(),
                    outputPath = nativeOutput.path
                )
            } finally {
                progressPoller.cancelAndJoin()
                bridge.currentProgressOrNull()?.let(onProgress)
            }
            coroutineContext.ensureActive()
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            val json = JSONObject(raw)
            if (!json.optBoolean("ok", false)) {
                if (json.optBoolean("cancelled", false)) throw LocalImageWorkerCancelledException()
                throwLocalImageNativeFailure(json, "stable-diffusion.cpp ESRGAN upscale failed.")
            }
            val nativeEffective = json.optJSONObject("nativeEffective")
                ?: error("ESRGAN upscale did not report nativeEffective execution evidence.")
            require(json.optBoolean("nativeExecution", false) &&
                !nativeEffective.optBoolean("fallback", true) &&
                nativeEffective.optString("operation") == "ESRGAN_UPSCALE" &&
                nativeEffective.optString("runtime") == LocalImageRuntime.STABLE_DIFFUSION_CPP.name &&
                nativeEffective.optString("backendMode") == "cpu"
            ) { "ESRGAN upscale did not prove direct stable-diffusion.cpp execution." }
            require(nativeEffective.optString("upscalerId") == upscaler.id &&
                nativeEffective.optString("upscalerFileName") == File(upscaler.path).name &&
                nativeEffective.optString("upscalerSha256").lowercase() == upscaler.sha256 &&
                nativeEffective.optLong("upscalerSizeBytes", -1L) == upscaler.sizeBytes &&
                nativeEffective.optBoolean("modelHashVerified", false) &&
                nativeEffective.optBoolean("modelFileIdentityStable", false) &&
                nativeEffective.optString("inputImageSha256").lowercase() == input.sha256
            ) { "ESRGAN upscale identity or input digest evidence does not match the request." }
            val sourceWidth = nativeEffective.optInt("sourceWidth", -1)
            val sourceHeight = nativeEffective.optInt("sourceHeight", -1)
            val nativeScale = nativeEffective.optInt("nativeScale", -1)
            val nativeWidth = nativeEffective.optInt("width", -1)
            val nativeHeight = nativeEffective.optInt("height", -1)
            val nativePixels = runCatching {
                Math.multiplyExact(nativeWidth.toLong(), nativeHeight.toLong())
            }.getOrDefault(-1L)
            val sourcePixels = runCatching {
                Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong())
            }.getOrDefault(-1L)
            require(sourceWidth in 1..MAX_UPSCALE_SOURCE_SIDE &&
                sourceHeight in 1..MAX_UPSCALE_SOURCE_SIDE &&
                sourcePixels in 1L..MAX_UPSCALE_SOURCE_PIXELS &&
                nativeScale in 2..8 &&
                nativeScale >= targetScale &&
                nativeWidth.toLong() == sourceWidth.toLong() * nativeScale.toLong() &&
                nativeHeight.toLong() == sourceHeight.toLong() * nativeScale.toLong() &&
                nativeWidth <= MAX_UPSCALE_NATIVE_OUTPUT_SIDE &&
                nativeHeight <= MAX_UPSCALE_NATIVE_OUTPUT_SIDE &&
                nativePixels in 1L..MAX_UPSCALE_NATIVE_OUTPUT_PIXELS &&
                nativeEffective.optInt("requestedTargetScale", -1) == targetScale &&
                nativeEffective.optInt("tileSize", -1) == tileSize &&
                nativeEffective.optInt("threads", -1) == threads
            ) { "ESRGAN upscale dimensions or controls do not match the request." }
            val physicalComputeCount = nativeEffective.optLong("physicalComputeCount", -1L)
            val physicalComputeSuccessCount =
                nativeEffective.optLong("physicalComputeSuccessCount", -1L)
            val physicalTileComputeCount =
                nativeEffective.optLong("physicalTileComputeCount", -1L)
            val physicalTileComputeSuccessCount =
                nativeEffective.optLong("physicalTileComputeSuccessCount", -1L)
            val expectedTiled = sourceWidth > tileSize || sourceHeight > tileSize
            require(nativeEffective.optBoolean("executionCompleted", false) &&
                physicalComputeCount > 0L &&
                physicalComputeSuccessCount == physicalComputeCount &&
                nativeEffective.optBoolean("tiledExecution", false) == expectedTiled &&
                (if (expectedTiled) {
                    physicalTileComputeCount == physicalComputeCount &&
                        physicalTileComputeSuccessCount == physicalComputeSuccessCount
                } else {
                    physicalComputeCount == 1L &&
                        physicalTileComputeCount == 0L &&
                        physicalTileComputeSuccessCount == 0L
                })
            ) { "ESRGAN upscale did not prove its physical model compute invocations." }
            require(json.optBoolean("contextReleased", false) &&
                json.optLong("nativeGenerationSequence", 0L) > 0L
            ) { "ESRGAN upscale did not release its native context or publish a sequence." }
            val requiredStages = 255L
            listOf("nativeStageMask", "nativeDetailStageMask").forEach { field ->
                val mask = json.optLong(field, -1L)
                require(mask >= 0L && mask and requiredStages == requiredStages) {
                    "ESRGAN upscale $field is missing required execution stages."
                }
            }
            require(nativeOutput.isFile &&
                nativeOutput.length() in 1L..MAX_UPSCALE_NATIVE_PNG_BYTES
            ) {
                "ESRGAN upscale did not publish a bounded non-empty PNG."
            }
            val nativeBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(nativeOutput.path, nativeBounds)
            require(nativeBounds.outWidth == nativeWidth && nativeBounds.outHeight == nativeHeight) {
                "ESRGAN native PNG dimensions differ from native execution evidence."
            }
            val publicationDimensions = validatedLocalImageUpscalePublicationDimensions(
                sourceWidth,
                sourceHeight,
                targetScale
            )
            val finalWidth = publicationDimensions.width
            val finalHeight = publicationDimensions.height
            var decodeSampleSize = 1
            val finalFile = if (targetScale == nativeScale) {
                nativeOutput
            } else {
                coroutineContext.ensureActive()
                decodeSampleSize = (nativeScale / targetScale).takeIf { ratio ->
                    nativeScale % targetScale == 0 && ratio >= 2 &&
                        (ratio and (ratio - 1)) == 0
                } ?: 1
                val nativeBitmap = BitmapFactory.decodeFile(
                    nativeOutput.path,
                    BitmapFactory.Options().apply { inSampleSize = decodeSampleSize }
                )
                    ?: error("ESRGAN native PNG could not be decoded for target-scale resize.")
                try {
                    require(nativeBitmap.width > 0 && nativeBitmap.height > 0 &&
                        nativeBitmap.width <= nativeWidth && nativeBitmap.height <= nativeHeight
                    ) {
                        "ESRGAN native PNG downsample could not be decoded safely."
                    }
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                    val resized = Bitmap.createScaledBitmap(nativeBitmap, finalWidth, finalHeight, true)
                    try {
                        coroutineContext.ensureActive()
                        FileOutputStream(postprocessedOutput).use { output ->
                            check(resized.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                "Unable to encode target-scale upscale output."
                            }
                            output.fd.sync()
                        }
                    } finally {
                        if (resized !== nativeBitmap) resized.recycle()
                    }
                } finally {
                    nativeBitmap.recycle()
                }
                postprocessedOutput
            }
            coroutineContext.ensureActive()
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            require(finalFile.isFile && finalFile.length() in 1L..MAX_UPSCALE_PNG_BYTES) {
                "Final upscale PNG exceeds the bounded worker publication limit."
            }
            val finalBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(finalFile.path, finalBounds)
            require(finalBounds.outWidth == finalWidth && finalBounds.outHeight == finalHeight) {
                "Final upscale output dimensions do not match the requested scale."
            }
            coroutineContext.ensureActive()
            val finalBytes = finalFile.readBytes()
            val executionMetadata = json
                .put(
                    "productOutput",
                    JSONObject()
                        .put("targetScale", targetScale)
                        .put("nativeFixedScale", nativeScale)
                        .put("postResizeApplied", targetScale != nativeScale)
                        .put(
                            "postResizeMethod",
                            if (targetScale == nativeScale) "none" else "android_bitmap_filtered"
                        )
                        .put("sourceWidth", sourceWidth)
                        .put("sourceHeight", sourceHeight)
                        .put("nativeWidth", nativeWidth)
                        .put("nativeHeight", nativeHeight)
                        .put("decodeSampleSize", decodeSampleSize)
                        .put("width", finalWidth)
                        .put("height", finalHeight)
                        .put("mimeType", "image/png")
                )
                .toString()
            LocalImageResult(
                bytes = finalBytes,
                mimeType = "image/png",
                executionMetadataJson = executionMetadata,
                seed = null
            )
        } finally {
            runCatching { nativeOutput.delete() }
            runCatching { postprocessedOutput.delete() }
            runCatching { bridge.shutdown() }
            if (activeRuntime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
                activeRuntime = null
                cancellationRequested.set(false)
            }
        }
    }

    suspend fun generate(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult = withContext(Dispatchers.IO) {
        val options = options.withCanonicalUltraFixControls()
        if (activeRuntime != model.runtime) begin(model.runtime)
        var textualInversionLease: TextualInversionSelectionLease? = null
        try {
            require(model.configured) { "本地图像生成模型文件不存在，请重新导入。" }
            require(prompt.isNotBlank()) { "请输入图片描述。" }
            require(!cancellationRequested.get()) { "本地生图已停止" }
            options.validateProductInputContract()
            validateLocalImageRuntimeProductOptions(model.runtime, options)
        if (model.runtime == LocalImageRuntime.QNN_HTP) {
            require(NativeQnnBridge.isAvailable) {
                val reason = NativeQnnBridge.loadError?.message.orEmpty()
                "Snapdragon NPU image backend failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(NativeQnnBridge.runnerReady) {
                JSONObject(qnnBridge.getRuntimeStatsJson()).optString("lastError")
                    .ifBlank { "Snapdragon NPU image runner is not packaged in this APK." }
            }
            require(NativeMnnDiffusionBridge.isAvailable) {
                val reason = NativeMnnDiffusionBridge.loadError?.message.orEmpty()
                "MNN prompt encoder failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(NativeMnnDiffusionBridge.runnerReady) {
                JSONObject(mnnDiffusionBridge.getRuntimeStatsJson()).optString("lastError")
                    .ifBlank { "MNN prompt encoder is not packaged in this APK." }
            }
            model.localImageReadinessMessage()?.let { message -> error(message) }
            val bundleRoot = model.bundleRoot
                ?.let(::File)
                ?.takeIf { it.isDirectory }
                ?: File(model.path).parentFile?.takeIf { it.isDirectory }
                ?: error("QNN image engine requires a complete QNN bundle directory.")
            val runtimeResolution = qnnRuntimeDirectoryResolutionFor(appContext, bundleRoot)
            require(runtimeResolution.stagingError == null) {
                runtimeResolution.stagingError.orEmpty()
            }
            val qnnHealth = QnnHtpImageRunner(context = appContext).health(
                device = DeviceProfileReader(appContext).read(),
                bundleRoot = bundleRoot
            )
            require(qnnHealth.state == LocalImageQnnState.SMOKE_REQUIRED) {
                qnnHealth.message
            }
            val fallbackSeed = (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
            val effectiveOptions = if (options.seed == null) options.copy(seed = fallbackSeed) else options
            val profileResolution = resolveLocalImageExecutionProfile(
                model = model,
                options = effectiveOptions,
                bundleRoot = bundleRoot,
                captureTextualInversionExecutionAssets = true
            ).withQnnProductSchedule(effectiveOptions)
                .withQnnInpaintProductSchedule(effectiveOptions)
            val profile = profileResolution.profile
            validateLocalImageProfileProductOptions(profile, effectiveOptions)
            textualInversionLease = effectiveOptions.textualInversionIds
                .takeIf(List<String>::isNotEmpty)
                ?.let { ids ->
                    require(profile.capabilities.supportsTextualInversion &&
                        profile.tokenizer.supportsTextualInversion &&
                        profile.hasHostWritableClipTextualInversionTopology()
                    ) {
                        "The resolved QNN profile has no host-writable CLIP textual-inversion topology."
                    }
                    val executionAssets = requireNotNull(profile.textualInversionExecutionAssets) {
                        "QNN textual inversion is missing its exact execution-asset snapshot."
                    }
                    textualInversionStore.acquireSelectionLease(
                        ids = ids,
                        modelFingerprint = profile.modelFingerprint,
                        tokenizerFingerprint = executionAssets.compositeSha256,
                        profileId = profile.profileId,
                        profileRevision = profile.profileRevision,
                        runtime = TextualInversionRuntime.QNN_HTP,
                        executionAssetBinding = executionAssets
                    ).also { lease ->
                        TextualInversionContract.validateNativeCapability(
                            runtime = LocalImageRuntime.QNN_HTP.name,
                            graphSupportsTextualInversion =
                                profile.hasHostWritableClipTextualInversionTopology(),
                            selection = lease.selection
                        )
                    }
                }
            val qnnTextualInversionJson = textualInversionLease
                ?.let { lease -> lease.selection.toNativeJson(lease.rootPath).toString() }
                .orEmpty()
            val resolved = profileResolution.layers.resolved
            val conditioningOrder = if (resolved.useCfg) {
                "negative_then_positive"
            } else {
                "positive_only"
            }
            val resolvedFinalNegativePrompt = resolveLocalImageFinalNegativePrompt(
                userNegativePrompt = effectiveOptions.negativePrompt,
                modelDefaultNegativePrompt = profile.defaults.defaultNegativePrompt
            )
            val effectiveFamily = profile.family
            val isSdxlQnn = effectiveFamily == LocalImageModelFamily.SDXL
            val usesSplitQnnWorkers =
                profile.graph.workerStrategy == ImageWorkerStrategy.SPLIT_UNET_VAE
            // Only isolated SDXL workers serialize a conditional-only payload.
            // Shared QNN topologies retain the fixed dual-branch layout.
            val useCfgForSdxlConditioning = !usesSplitQnnWorkers || resolved.useCfg
            val sdxlConditioningOrder = if (useCfgForSdxlConditioning) {
                "negative_then_positive"
            } else {
                "positive_only"
            }
            val expectedSdxlConditioningBranches = if (useCfgForSdxlConditioning) 2 else 1
            val expectedSdxlConditioningExecutions = if (useCfgForSdxlConditioning) 4 else 2
            require(!usesSplitQnnWorkers || effectiveOptions.preview == null) {
                "Split-SDXL uses isolated workers and does not support live preview."
            }
            val usesQnnImg2Img =
                effectiveOptions.taskMode == LocalImageTaskMode.IMG2IMG &&
                    profile.hasExecutableQnnImg2ImgTopology()
            val usesSharedQnnImg2Img = usesQnnImg2Img && profile.hasSharedQnnImg2ImgTopology()
            val usesQnnInpaint =
                effectiveOptions.taskMode == LocalImageTaskMode.INPAINT &&
                    profile.hasExecutableQnnInpaintTopology()
            // Qualcomm's Gen5 archives include a native QNN CLIP graph. Keep
            // legacy QNN archives on their existing MNN-embedding path, while
            // shared-text topologies bind the exact declared .bin artifact.
            val qnnTextEncoderContextPath = when (profile.graph.workerStrategy) {
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE ->
                    qnnDeclaredTextEncoderContextPath(bundleRoot, profile.graph.textEncoder)
                else -> qnnNativeTextEncoderContextPath(bundleRoot)
            }
            val usesQnnClipTokenIds = !isSdxlQnn && qnnTextEncoderContextPath != null
            when (profile.graph.workerStrategy) {
                ImageWorkerStrategy.SHARED_UNET_VAE -> {
                    if (!isSdxlQnn) {
                        require(!usesQnnClipTokenIds &&
                            profile.graph.textEncoder
                                ?.relativePath
                                ?.substringAfterLast('/')
                                ?.substringAfterLast('\\') == "clip_v2.mnn"
                        ) {
                            "Resolved shared QNN UNet/VAE profile must use the installed MNN clip_v2.mnn conditioner."
                        }
                    }
                }
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE -> {
                    require(usesQnnClipTokenIds &&
                        profile.graph.textEncoder?.relativePath?.endsWith(".bin", ignoreCase = true) == true
                    ) {
                        "Resolved shared QNN text/UNet/VAE profile requires a real text-encoder context."
                    }
                }
                else -> Unit
            }
            val conditioningRoot = when {
                isSdxlQnn -> resolveSdxlQnnConditioningRoot(bundleRoot)
                profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_UNET_VAE -> {
                    val relativePath = requireNotNull(profile.graph.textEncoder?.relativePath) {
                        "Shared QNN UNet/VAE profile is missing its MNN conditioning graph path."
                    }
                    requireNotNull(
                        bundleRoot.safeDescendantOrNull(relativePath)
                            ?.takeIf { file -> file.isFile && file.length() > 0L }
                            ?.parentFile
                    ) { "MNN conditioning graph is missing or empty: $relativePath" }
                }
                else -> bundleRoot
            }
            val outputDir = File(
                appContext.cacheDir,
                if (usesSplitQnnWorkers) SDXL_TWO_PHASE_DIRECTORY else "local_image_outputs"
            ).apply { mkdirs() }
            if (!usesSplitQnnWorkers) {
                runCatching { QnnImageStageJournal.sweepStalePreviewDirectories(outputDir) }
                runCatching { QnnInputImageArtifact.cleanupStaleSharedArtifacts(appContext.cacheDir) }
                runCatching { QnnInpaintInputArtifact.cleanupStaleArtifacts(appContext.cacheDir) }
            }
            val requestToken = "qnn-htp-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            val outputFile = File(outputDir, "$requestToken.png")
            val embeddingFile = File(
                outputDir,
                if (isSdxlQnn) {
                    "${outputFile.nameWithoutExtension}.sdxl-conditioning.f32"
                } else if (usesQnnClipTokenIds) {
                    if (resolved.promptWeightingSupported) {
                        "${outputFile.nameWithoutExtension}.qnn-clip-conditioning.bin"
                    } else {
                        "${outputFile.nameWithoutExtension}.qnn-clip-token-ids.i32"
                    }
                } else {
                    "${outputFile.nameWithoutExtension}.sd15-embeddings.f32"
                }
            )
            val progressJournalFile = File(
                outputDir,
                "${outputFile.nameWithoutExtension}.qnn-stage.json"
            )
            val latentFile = File(outputDir, "${outputFile.nameWithoutExtension}.latent.f32")
            val latentMetadataFile = File(outputDir, "${outputFile.nameWithoutExtension}.latent.json")
            val inputTensorFile = File(outputDir, "${outputFile.nameWithoutExtension}.input-rgb-nchw.f32")
            val inpaintMaskTensorFile = File(
                outputDir,
                "${outputFile.nameWithoutExtension}.inpaint-mask-latent.f32"
            )
            val inpaintFullMaskTensorFile = File(
                outputDir,
                "${outputFile.nameWithoutExtension}.inpaint-mask-full.f32"
            )
            val inpaintMaskedInputTensorFile = File(
                outputDir,
                "${outputFile.nameWithoutExtension}.inpaint-masked-rgb-nchw.f32"
            )
            val encoderLatentFile = File(outputDir, "${outputFile.nameWithoutExtension}.encoder-latent.f32")
            val encoderMetadataFile = File(outputDir, "${outputFile.nameWithoutExtension}.encoder-latent.json")
            val encoderJournalFile = File(outputDir, "${outputFile.nameWithoutExtension}.encoder-stage.json")
            val unetJournalFile = File(outputDir, "${outputFile.nameWithoutExtension}.unet-stage.json")
            val vaeJournalFile = File(outputDir, "${outputFile.nameWithoutExtension}.vae-stage.json")
            val contract = resolveQnnImageGenerationContract(
                resolution = profileResolution,
                defaultThreads = defaultLocalImageThreads(),
                options = effectiveOptions
            )
            val finalNegativePrompt = resolveLocalImageFinalNegativePromptForExecution(
                finalNegativePrompt = resolvedFinalNegativePrompt,
                useCfg = contract.useCfg
            )
            val executedNegativePrompt = resolveQnnExecutedNegativePrompt(
                useCfg = contract.useCfg,
                effectiveNegativePrompt = finalNegativePrompt.value
            )
            // QNN may discard a model-default negative prompt when CFG is disabled. Admit the
            // final native pair, not the request's pre-resolution negative-prompt value.
            requireLocalImagePromptLanguageAdmission(
                profile = profile,
                prompt = prompt.trim(),
                executedNegativePrompt = executedNegativePrompt
            )
            requireNativeMultilingualTextEncoderEvidenceAsset(bundleRoot, profile)
            val width = contract.width
            val height = contract.height
            val steps = contract.steps
            val threads = contract.threads
            val startedAt = System.currentTimeMillis()
            fun progress(
                phase: String,
                message: String,
                step: Int = 0,
                totalSteps: Int = steps
            ) {
                onProgress(
                    LocalImageProgress(
                        phase = phase,
                        message = message,
                        step = step,
                        steps = totalSteps,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        secondsPerStep = 0.0,
                        threads = threads,
                        width = width,
                        height = height,
                        cancelRequested = false,
                        requestOptionsJson = contract.auditJson.toString()
                    )
                )
            }

            val params = ImageExecutionProfileNativeContract.toNativeParamsJson(profileResolution)
                .put("prompt", prompt.trim())
                .put("negativePrompt", executedNegativePrompt)
                .put("family", effectiveFamily.name)
                .put("variant", profile.variant.name)
                .put("width", width)
                .put("height", height)
                .put("steps", steps)
                .put("threads", threads)
                .put("seed", contract.seed)
                .put("cfgScale", contract.cfgScale)
                .put("sampleMethod", contract.sampleMethod)
                .put("backendMode", contract.backendMode)
                .put("tokenEmbeddingMode", contract.tokenEmbeddingMode)
                .put("memoryMode", contract.memoryMode)
                .put("useCfg", contract.useCfg)
                .put("workerStrategy", profile.graph.workerStrategy.name)
                .put("progressJournalPath", progressJournalFile.absolutePath)
                .putQnnSemanticDefaults(
                    bundleRoot = bundleRoot,
                    profile = profile,
                    includeVaeEncoderEvidence = usesQnnImg2Img || usesQnnInpaint
                )
                .putQnnNativeMultilingualTextEncoderEvidence(
                    bundleRoot = bundleRoot,
                    profile = profile
                )
            if (profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_UNET_VAE) {
                params.put("conditioningContractMode", "shared_unet_vae")
            }
            effectiveOptions.putProductInputNativeParams(params)
            if (usesSplitQnnWorkers) {
                require(!params.has("preview")) {
                    "Split-SDXL params must not carry a live-preview request."
                }
            }
            val nativeMultilingualTextEncoderEvidence = profile
                .textEncoderLanguage
                ?.evidence
                ?.takeIf { profile.hasVerifiedNativeSimplifiedChineseTextEncoder() }
            val requiresQnnVersionedTokenPayload =
                resolved.promptWeightingSupported || nativeMultilingualTextEncoderEvidence != null
            if (isSdxlQnn) {
                params.put("conditioningFormat", "sdxl_qnn_conditioning")
            } else if (usesQnnClipTokenIds) {
                params.put(
                    "conditioningFormat",
                    if (requiresQnnVersionedTokenPayload) {
                        "qnn_clip_token_ids_weights_v1"
                    } else {
                        "qnn_clip_token_ids_i32"
                    }
                )
            }
            val splitRequestLease = if (usesSplitQnnWorkers) {
                sdxlCoordinator.prepareLease(
                    requestId = requestToken,
                    params = params,
                    embeddingsFile = embeddingFile,
                    latentFile = latentFile,
                    metadataFile = latentMetadataFile,
                    inputTensorFile = inputTensorFile,
                    encoderLatentFile = encoderLatentFile,
                    encoderMetadataFile = encoderMetadataFile,
                    outputFile = outputFile,
                    encoderJournal = encoderJournalFile,
                    unetJournal = unetJournalFile,
                    vaeJournal = vaeJournalFile,
                    maskTensorFile = inpaintMaskTensorFile.takeIf { usesQnnInpaint },
                    fullMaskTensorFile = inpaintFullMaskTensorFile.takeIf { usesQnnInpaint }
                )
            } else {
                null
            }
            val splitRequestTerminal = if (usesSplitQnnWorkers) {
                SplitSdxlRequestTerminalArbiter().also { terminal ->
                    activeSplitSdxlTerminal.set(terminal)
                    if (cancellationRequested.get()) terminal.cancel()
                }
            } else {
                null
            }
            var splitOutputTransferred = false
            var splitRequestError: Throwable? = null

            try {
                progress(
                    "conditioning",
                    if (isSdxlQnn) {
                        "Encoding SDXL prompt conditioning for QNN image generation"
                    } else {
                        "Encoding prompt embeddings for QNN image generation"
                    }
                )
                val embeddingRaw = if (isSdxlQnn) {
                    mnnDiffusionBridge.encodeSdxlPromptConditioning(
                        conditioningRoot.absolutePath,
                        prompt.trim(),
                        executedNegativePrompt,
                        embeddingFile.absolutePath,
                        width,
                        height,
                        contract.backendMode,
                        threads,
                        useCfgForSdxlConditioning,
                        resolved.promptWeightingSupported,
                        qnnTextualInversionJson
                    )
                } else if (usesQnnClipTokenIds) {
                    encodeQnnClipPromptTokenIds(
                        bridge = mnnDiffusionBridge,
                        bundleRoot = bundleRoot,
                        prompt = prompt.trim(),
                        outputFile = embeddingFile,
                        negativePrompt = executedNegativePrompt,
                        bosId = requireNotNull(profile.tokenizer.bosId) {
                            "Resolved tokenizer profile is missing BOS id."
                        },
                        eosId = requireNotNull(profile.tokenizer.eosId) {
                            "Resolved tokenizer profile is missing EOS id."
                        },
                        padId = requireNotNull(profile.tokenizer.padId) {
                            "Resolved tokenizer profile is missing PAD id."
                        },
                        maxTokens = profile.tokenizer.maxLength,
                        promptWeightingEnabled = resolved.promptWeightingSupported,
                        nativeMultilingualEvidence = nativeMultilingualTextEncoderEvidence
                    )
                } else {
                    mnnDiffusionBridge.encodeSd15PromptEmbeddings(
                        conditioningRoot.absolutePath,
                        prompt.trim(),
                        executedNegativePrompt,
                        embeddingFile.absolutePath,
                        contract.backendMode,
                        threads,
                        conditioningOrder,
                        resolved.promptWeightingSupported,
                        qnnTextualInversionJson
                    )
                }
                val embeddingJson = JSONObject(embeddingRaw)
                if (!embeddingJson.optBoolean("ok", false)) {
                    if (embeddingJson.optBoolean("cancelled", false) || cancellationRequested.get()) {
                        throw LocalImageWorkerCancelledException()
                    }
                    throwLocalImageNativeFailure(
                        embeddingJson,
                        "Failed to encode QNN prompt embeddings."
                    )
                }
                val nativeMultilingualPromptHandoff = nativeMultilingualTextEncoderEvidence
                    ?.let { evidence ->
                        verifyQnnNativeMultilingualPromptHandoff(
                            bundleRoot = bundleRoot,
                            evidence = evidence,
                            encoded = embeddingJson
                        )
                    }
                nativeMultilingualPromptHandoff?.let { handoff ->
                    // This opaque capability can only be issued after MNN commits the exact token
                    // payload. QNN atomically consumes it while matching prompt, payload,
                    // tokenizer identity, and the signed prompt-to-encoder closure.
                    params.put("mnnPromptHandoff", handoff.opaqueHandle)
                }
                val actualConditioningFormat = embeddingJson
                    .optString("conditioningFormat")
                    .ifBlank { embeddingJson.optString("format") }
                    .trim()
                require(actualConditioningFormat.isNotEmpty()) {
                    "Prompt conditioning did not report its native file format."
                }
                val requestedConditioningFormat = params.optString("conditioningFormat").trim()
                if (requestedConditioningFormat.isEmpty()) {
                    params.put("conditioningFormat", actualConditioningFormat)
                } else {
                    require(requestedConditioningFormat == actualConditioningFormat) {
                        "Prompt conditioning format mismatch: resolved=$requestedConditioningFormat, " +
                            "encoder=$actualConditioningFormat."
                    }
                }
                ImageExecutionProfileNativeContract.nativeEvidenceOnlyFields.forEach { field ->
                    require(embeddingJson.has(field) && !embeddingJson.isNull(field)) {
                        "Prompt conditioning did not report native weighting evidence: $field"
                    }
                    params.put(field, embeddingJson.get(field))
                }
                requireNativePromptEncodingEvidence(
                    source = embeddingJson,
                    prompt = prompt.trim(),
                    negativePrompt = executedNegativePrompt
                )
                ImageExecutionProfileNativeContract.qnnPromptConditioningHandoffFields.forEach { field ->
                    params.put(field, embeddingJson.get(field))
                }
                require(embeddingFile.isFile && embeddingFile.length() > 0L) {
                    "Prompt conditioning did not produce a concrete artifact."
                }
                val conditioningArtifactSha256 = embeddingFile.sha256Contents()
                textualInversionLease?.let { lease ->
                    verifyEncodedMnnTextualInversionEvidence(
                        encoded = embeddingJson,
                        selection = lease.selection,
                        runtime = TextualInversionRuntime.QNN_HTP
                    )
                }
                if (isSdxlQnn) {
                    require(
                        embeddingJson.optString("conditioningExecutionMode") ==
                            "external_mnn_sdxl_embeddings" &&
                            embeddingJson.optString("conditioningBackend") == "MNN" &&
                            embeddingJson.optString("conditioningGraph") ==
                            SDXL_QNN_CONDITIONING_GRAPH_EVIDENCE &&
                            embeddingJson.optInt("conditioningEncoderExecutionCount", -1) ==
                            expectedSdxlConditioningExecutions &&
                            embeddingJson.optString("conditioningOrder") ==
                            sdxlConditioningOrder &&
                            embeddingJson.optJSONArray("hiddenShape")?.let { shape ->
                                shape.length() == 3 &&
                                    shape.optInt(0, -1) == expectedSdxlConditioningBranches &&
                                    shape.optInt(1, -1) == 77 &&
                                    shape.optInt(2, -1) == 2_048
                            } == true &&
                            embeddingJson.optJSONArray("pooledShape")?.let { shape ->
                                shape.length() == 2 &&
                                    shape.optInt(0, -1) == expectedSdxlConditioningBranches &&
                                    shape.optInt(1, -1) == 1_280
                            } == true &&
                            embeddingJson.optJSONArray("timeIdsShape")?.let { shape ->
                                shape.length() == 2 &&
                                    shape.optInt(0, -1) == 1 && shape.optInt(1, -1) == 6
                            } == true &&
                            embeddingJson.optString("conditioningArtifactSha256").lowercase() ==
                            conditioningArtifactSha256 &&
                            embeddingJson.optString("promptWeightFingerprint").lowercase() ==
                            conditioningArtifactSha256
                    ) {
                        "MNN SDXL conditioning did not publish complete graph and artifact evidence."
                    }
                    params.put("conditioningExecutionMode", "external_mnn_sdxl_embeddings")
                    params.put("conditioningBackend", "MNN")
                    params.put("conditioningGraph", SDXL_QNN_CONDITIONING_GRAPH_EVIDENCE)
                    params.put(
                        "conditioningGraphSha256",
                        sdxlQnnConditioningGraphSha256(conditioningRoot)
                    )
                    params.put("conditioningEncoderExecutionCount", expectedSdxlConditioningExecutions)
                    params.put("conditioningOrder", sdxlConditioningOrder)
                } else if (profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_UNET_VAE) {
                    val expectedConditioningExecutions = if (resolved.useCfg) 2 else 1
                    val conditioningGraphPath = requireNotNull(
                        profile.graph.textEncoder?.relativePath
                    ) { "Shared QNN UNet/VAE profile is missing its MNN conditioning graph path." }
                    val conditioningGraphFile = requireNotNull(
                        bundleRoot.safeDescendantOrNull(conditioningGraphPath)
                            ?.takeIf { file -> file.isFile && file.length() > 0L }
                    ) { "MNN conditioning graph is missing or empty: $conditioningGraphPath" }
                    val conditioningGraphSha256 = conditioningGraphFile.sha256Contents()
                    val conditioningShape = embeddingJson.optJSONArray("shape")
                    require(embeddingJson.optString("conditioningExecutionMode") ==
                        "external_mnn_embeddings" &&
                        embeddingJson.optString("conditioningBackend") == "MNN" &&
                        embeddingJson.optString("conditioningGraph") == "clip_v2.mnn" &&
                        embeddingJson.optInt("conditioningEncoderExecutionCount", -1) ==
                        expectedConditioningExecutions &&
                        embeddingJson.optString("conditioningOrder") == conditioningOrder &&
                        conditioningShape?.optInt(0, -1) == expectedConditioningExecutions &&
                        embeddingJson.optString("conditioningArtifactSha256").lowercase() ==
                        conditioningArtifactSha256 &&
                        embeddingJson.optString("promptWeightFingerprint").lowercase() ==
                        conditioningArtifactSha256
                    ) {
                        "MNN clip_v2.mnn conditioning did not publish complete native graph and artifact evidence."
                    }
                    params.put("conditioningExecutionMode", "external_mnn_embeddings")
                    params.put("conditioningBackend", "MNN")
                    params.put("conditioningGraph", "clip_v2.mnn")
                    params.put("conditioningGraphSha256", conditioningGraphSha256)
                    params.put("conditioningEncoderExecutionCount", expectedConditioningExecutions)
                    params.put("conditioningOrder", conditioningOrder)
                }
                params.put("conditioningArtifactSha256", conditioningArtifactSha256)
                val preparedQnnInput = if (usesQnnImg2Img) {
                    val input = requireNotNull(effectiveOptions.inputImage) {
                        "QNN img2img requires a prepared input image."
                    }
                    progress("input_preprocess", "Preparing the bounded QNN VAE-encoder input tensor")
                    val prepared = if (usesSplitQnnWorkers && effectiveOptions.ultraFix == null) {
                        SdxlInputImageArtifact.prepare(
                            input = input,
                            tensorFile = inputTensorFile,
                            targetWidth = width,
                            targetHeight = height,
                            isCancelled = cancellationRequested::get
                        )
                    } else {
                        // UltraFix preprocesses the complete target canvas before the isolated
                        // encoder worker executes its fixed-size graph once per planned tile.
                        QnnInputImageArtifact.prepare(
                            input = input,
                            tensorFile = inputTensorFile,
                            targetWidth = width,
                            targetHeight = height,
                            isCancelled = cancellationRequested::get
                        )
                    }
                    prepared.also { preparedInput ->
                        preparedInput.putNativeParams(params)
                        val schedule = resolveQnnImg2ImgSchedule(
                            steps = steps,
                            fullTimetableCount = steps,
                            strength = effectiveOptions.strength ?: 1.0
                        )
                        params.put("fullTimetableCount", schedule.fullTimetableCount)
                            .put("effectiveDenoiseSteps", schedule.effectiveSteps)
                            .put("img2imgBeginIndex", schedule.beginIndex)
                    }
                } else {
                    null
                }
                val preparedQnnInpaint = if (usesQnnInpaint) {
                    val input = requireNotNull(effectiveOptions.inputImage) {
                        "QNN inpaint requires a prepared source image."
                    }
                    val mask = requireNotNull(effectiveOptions.maskImage) {
                        "QNN inpaint requires a prepared mask image."
                    }
                    progress("input_preprocess", "Preparing bounded QNN inpaint tensors")
                    QnnInpaintInputArtifact.prepare(
                        input = input,
                        mask = mask,
                        sourceTensorFile = inputTensorFile,
                        maskTensorFile = inpaintMaskTensorFile,
                        fullMaskTensorFile = inpaintFullMaskTensorFile,
                        maskedInputTensorFile = inpaintMaskedInputTensorFile,
                        topology = profile.inspectQnnInpaintTopology(),
                        targetWidth = width,
                        targetHeight = height,
                        isCancelled = cancellationRequested::get
                    ).also { prepared ->
                        prepared.putNativeParams(params)
                        val schedule = resolveQnnInpaintSchedule(
                            steps = steps,
                            fullTimetableCount = steps,
                            strength = effectiveOptions.strength ?: 1.0
                        )
                        params
                            .put("fullTimetableCount", schedule.fullTimetableCount)
                            .put("img2imgBeginIndex", schedule.beginIndex)
                            .put("effectiveDenoiseSteps", schedule.effectiveSteps)
                    }
                } else {
                    null
                }
                if (cancellationRequested.get()) {
                    error("本地生图已停止")
                }

                progress("sampling", "正在骁龙 NPU 上运行 QNN UNet 和 VAE")
                var sharedQnnLastJournalProgress: LocalImageProgress? = null
                var sharedQnnNativeCompletedSuccessfully = false
                val raw = if (usesSplitQnnWorkers) {
                    sdxlCoordinator.generate(
                        lease = requireNotNull(splitRequestLease),
                        requestId = requestToken,
                        bundleRoot = bundleRoot,
                        runtimeDirsJson = qnnRuntimeDirsJson(bundleRoot),
                        params = params,
                        embeddingsFile = embeddingFile,
                        latentFile = latentFile,
                        metadataFile = latentMetadataFile,
                        preparedInput = preparedQnnInput,
                        preparedInpaint = preparedQnnInpaint,
                        inputTensorFile = inputTensorFile,
                        encoderLatentFile = encoderLatentFile,
                        encoderMetadataFile = encoderMetadataFile,
                        outputFile = outputFile,
                        encoderJournal = encoderJournalFile,
                        unetJournal = unetJournalFile,
                        vaeJournal = vaeJournalFile,
                        onProgress = onProgress
                    )
                } else {
                    runCatching { progressJournalFile.delete() }
                    runCatching { File(progressJournalFile.absolutePath + ".tmp").delete() }
                    val progressPoller = launch(Dispatchers.Default) {
                        while (isActive) {
                            val observed = QnnImageStageJournal.readOrPrevious(
                                file = progressJournalFile,
                                previous = sharedQnnLastJournalProgress,
                                threads = threads,
                                width = width,
                                height = height
                            )
                            if (observed != null && observed != sharedQnnLastJournalProgress) {
                                sharedQnnLastJournalProgress = observed
                                onProgress(observed)
                            }
                            delay(250)
                        }
                    }
                    var qnnRawResult: String? = null
                    try {
                        qnnBridge.runImageSemanticGenerate(
                            bundleRoot.absolutePath,
                            qnnRuntimeDirsJson(bundleRoot),
                            params.toString(),
                            embeddingFile.absolutePath,
                            outputFile.absolutePath
                        ).also { qnnRawResult = it }
                    } finally {
                        // The parent provider coroutine may already be cancelled here. Join in a
                        // non-cancellable cleanup region so no preview source is deleted while
                        // the poller is still reading it or completing a synchronous callback.
                        withContext(NonCancellable) {
                            progressPoller.cancelAndJoin()
                        }
                        sharedQnnNativeCompletedSuccessfully = qnnRawResult?.let { rawResult ->
                            runCatching {
                                val result = JSONObject(rawResult)
                                result.optBoolean("ok", false) &&
                                    !result.optBoolean("cancelled", false)
                            }.getOrDefault(false)
                        } == true
                    }
                }
                if (!usesSplitQnnWorkers) {
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                }
                val json = JSONObject(raw)
                if (!json.optBoolean("ok", false)) {
                    json.optString("errorCode")
                        .trim()
                        .takeIf(String::isNotEmpty)
                        ?.let { code ->
                            throw LocalImageProductContractException(
                                code.lowercase(),
                                json.optString("error")
                                    .ifBlank { json.optString("message") }
                                    .ifBlank { "Snapdragon NPU image generation failed." }
                            )
                        }
                    error(
                        if (json.optBoolean("cancelled", false)) {
                            "本地生图已停止"
                        } else {
                            json.optString("message").ifBlank { "Snapdragon NPU image generation failed." }
                        }
                    )
                }
                if (!usesSplitQnnWorkers) {
                    val nativeEffective = json.optJSONObject("nativeEffective")
                        ?: error("QNN image generation did not report nativeEffective evidence.")
                    require(json.optString("conditioningArtifactSha256") == conditioningArtifactSha256 &&
                        nativeEffective.optString("conditioningArtifactSha256") == conditioningArtifactSha256 &&
                        json.optBoolean("conditioningArtifactConsumed", false) &&
                        nativeEffective.optBoolean("conditioningArtifactConsumed", false) &&
                        (profile.graph.workerStrategy != ImageWorkerStrategy.SHARED_UNET_VAE ||
                            nativeEffective.optString("conditioningGraphSha256") ==
                            params.optString("conditioningGraphSha256"))
                    ) {
                        "QNN image generation did not consume the exact prepared conditioning artifact."
                    }
                }
                val qnnTextualInversionEvidence = textualInversionLease?.let { lease ->
                    promoteConsumedQnnTextualInversionEvidence(
                        result = json,
                        encoded = embeddingJson,
                        selection = lease.selection,
                        expectedConditioningSha256 = conditioningArtifactSha256,
                        splitWorkers = usesSplitQnnWorkers
                    )
                }
                ImageExecutionProfileNativeContract.parseAndValidate(profileResolution, json)
                val inputExecutionAudit = when (effectiveOptions.taskMode) {
                    LocalImageTaskMode.CONTROL ->
                        verifyAndSanitizeQnnProductInput(json, effectiveOptions)
                    LocalImageTaskMode.IMG2IMG -> if (effectiveOptions.ultraFix != null) {
                        verifyAndSanitizeQnnUltraFixProductInput(
                            result = json,
                            options = effectiveOptions,
                            prepared = requireNotNull(preparedQnnInput),
                            usesSplitWorkers = usesSplitQnnWorkers,
                            profile = profile,
                            expectedVaeEncoderContextSha256 = params
                                .getString("vaeEncoderContextSha256")
                                .lowercase(),
                            expectedVaeEncoderGraphName = params.getString("vaeEncoderGraphName")
                        )
                    } else if (usesSplitQnnWorkers) {
                        verifyAndSanitizeQnnImg2ImgProductInput(
                            result = json,
                            options = effectiveOptions,
                            prepared = requireNotNull(preparedQnnInput),
                            expectedVaeEncoderContextSha256 = params
                                .getString("vaeEncoderContextSha256")
                                .lowercase()
                        )
                    } else {
                        require(usesSharedQnnImg2Img) {
                            "Resolved QNN img2img execution is neither a split nor coherent shared topology."
                        }
                        verifyAndSanitizeSharedQnnImg2ImgProductInput(
                            result = json,
                            options = effectiveOptions,
                            prepared = requireNotNull(preparedQnnInput),
                            profile = profile,
                            expectedVaeEncoderContextSha256 = params
                                .getString("vaeEncoderContextSha256")
                                .lowercase(),
                            expectedVaeEncoderGraphName = params.getString("vaeEncoderGraphName")
                        )
                    }
                    LocalImageTaskMode.INPAINT -> {
                        require(usesQnnInpaint) {
                            "Resolved QNN inpaint execution has no executable topology."
                        }
                        if (usesSplitQnnWorkers) {
                            verifyAndSanitizeSplitQnnInpaintProductInput(
                                result = json,
                                options = effectiveOptions,
                                prepared = requireNotNull(preparedQnnInpaint),
                                expectedVaeEncoderContextSha256 = params
                                    .getString("vaeEncoderContextSha256")
                                    .lowercase()
                            )
                        } else {
                            verifyAndSanitizeSharedQnnInpaintProductInput(
                                result = json,
                                options = effectiveOptions,
                                prepared = requireNotNull(preparedQnnInpaint),
                                profile = profile,
                                expectedVaeEncoderContextSha256 = params
                                    .getString("vaeEncoderContextSha256")
                                    .lowercase(),
                                expectedVaeEncoderGraphName = params.getString("vaeEncoderGraphName")
                            )
                        }
                    }
                    LocalImageTaskMode.TEXT_TO_IMAGE -> {
                        verifyAndSanitizeQnnTextToImagePrivatePaths(json, effectiveOptions)
                        null
                    }
                    else -> error(
                        "QNN native product input verification is unavailable for " +
                            effectiveOptions.taskMode.wireName
                    )
                }
                if (!usesSplitQnnWorkers) {
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                }
                val verifiedOutput = verifyAndReadQnnImageOutput(
                    nativeResult = json,
                    expectedOutputFile = outputFile,
                    expectedWidth = width,
                    expectedHeight = height,
                    checkCancelled = {
                        coroutineContext.ensureActive()
                        if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                    }
                )
                verifyQnnUltraFixEvidence(
                    result = json,
                    request = effectiveOptions.ultraFix,
                    inputImage = effectiveOptions.inputImage,
                    requestedUseCfg = profileResolution.layers.resolved.useCfg,
                    outputBytes = verifiedOutput.bytes,
                    outputMimeType = verifiedOutput.mimeType
                )
                if (!usesSplitQnnWorkers) {
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                }
                val completedStepCount = if (usesSharedQnnImg2Img || usesQnnInpaint) {
                    profileResolution.layers.resolved.timetableCount
                } else {
                    steps
                }
                val qnnExecutionMetadata = sanitizeNativeExecutionJson(
                    qnnImageExecutionMetadata(
                        nativeRequestId = requestToken,
                        nativeResult = json,
                        outputBytes = verifiedOutput.outputBytes,
                        inputExecutionAudit = inputExecutionAudit,
                        requestedPreview = effectiveOptions.preview
                    ).toString()
                ).takeIf(String::isNotBlank)
                    ?: error("QNN execution evidence could not be sanitized.")
                val result = LocalImageResult(
                    bytes = verifiedOutput.bytes,
                    mimeType = verifiedOutput.mimeType,
                    executionMetadataJson = JSONObject(qnnExecutionMetadata).putPromptExecutionBinding(
                        profile = profile,
                        prompt = prompt.trim(),
                        negativePrompt = executedNegativePrompt
                    ).toString(),
                    seed = contract.seed.toLong()
                )
                coroutineContext.ensureActive()
                if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                if (qnnTextualInversionEvidence != null) {
                    val lease = requireNotNull(textualInversionLease)
                    lease.verifyUnchanged()
                    lease.close()
                    textualInversionLease = null
                    textualInversionStore.commitSuccessfulBindings(
                        selection = lease.selection,
                        nativeBindingFingerprint =
                            qnnTextualInversionEvidence.bindingFingerprint,
                        nativeBindingStage = qnnTextualInversionEvidence.bindingStage
                    )
                }
                coroutineContext.ensureActive()
                if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                // The native worker can leave one committed frame between the last poll and its
                // return. Drain it only after every Java-side output/evidence/TI validation has
                // succeeded, so a product-contract failure cannot publish a new stale preview.
                if (!usesSplitQnnWorkers && sharedQnnNativeCompletedSuccessfully) {
                    QnnImageStageJournal.readOrPrevious(
                        file = progressJournalFile,
                        previous = sharedQnnLastJournalProgress,
                        threads = threads,
                        width = width,
                        height = height
                    )?.let { terminalProgress ->
                        if (terminalProgress != sharedQnnLastJournalProgress) {
                            sharedQnnLastJournalProgress = terminalProgress
                            onProgress(terminalProgress)
                        }
                    }
                }
                coroutineContext.ensureActive()
                if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                progress(
                    "completed",
                    "QNN NPU image generation completed",
                    step = completedStepCount,
                    totalSteps = completedStepCount
                )
                coroutineContext.ensureActive()
                if (usesSplitQnnWorkers) {
                    val terminal = requireNotNull(splitRequestTerminal)
                    if (cancellationRequested.get()) terminal.cancel()
                    if (!terminal.transfer()) {
                        check(terminal.current() == SplitSdxlRequestTerminalState.CANCELLED) {
                            "Split-SDXL output transfer was resolved more than once."
                        }
                        throw LocalImageWorkerCancelledException()
                    }
                } else if (cancellationRequested.get()) {
                    throw LocalImageWorkerCancelledException()
                }
                splitOutputTransferred = true
                return@withContext result
            } catch (error: Throwable) {
                splitRequestError = error
                throw error
            } finally {
                runCatching {
                    QnnImageStageJournal.cleanupRequestPreviewDirectory(progressJournalFile)
                }
                runCatching { progressJournalFile.delete() }
                runCatching { File(progressJournalFile.absolutePath + ".tmp").delete() }
                if (!usesSplitQnnWorkers) {
                    runCatching { embeddingFile.delete() }
                    runCatching { File(embeddingFile.absolutePath + ".part").delete() }
                    runCatching { outputFile.delete() }
                    runCatching { File(outputFile.absolutePath + ".part").delete() }
                    runCatching { latentFile.delete() }
                    runCatching { File(latentFile.absolutePath + ".part").delete() }
                    runCatching { latentMetadataFile.delete() }
                    runCatching { File(latentMetadataFile.absolutePath + ".part").delete() }
                    runCatching { inputTensorFile.delete() }
                    runCatching { File(inputTensorFile.absolutePath + ".part").delete() }
                    runCatching { inpaintMaskTensorFile.delete() }
                    runCatching { File(inpaintMaskTensorFile.absolutePath + ".part").delete() }
                    runCatching { inpaintFullMaskTensorFile.delete() }
                    runCatching { File(inpaintFullMaskTensorFile.absolutePath + ".part").delete() }
                    runCatching { inpaintMaskedInputTensorFile.delete() }
                    runCatching { File(inpaintMaskedInputTensorFile.absolutePath + ".part").delete() }
                    runCatching { encoderLatentFile.delete() }
                    runCatching { File(encoderLatentFile.absolutePath + ".part").delete() }
                    runCatching { encoderMetadataFile.delete() }
                    runCatching { File(encoderMetadataFile.absolutePath + ".part").delete() }
                    runCatching { encoderJournalFile.delete() }
                    runCatching { File(encoderJournalFile.absolutePath + ".tmp").delete() }
                    runCatching { unetJournalFile.delete() }
                    runCatching { File(unetJournalFile.absolutePath + ".tmp").delete() }
                    runCatching { vaeJournalFile.delete() }
                    runCatching { File(vaeJournalFile.absolutePath + ".tmp").delete() }
                }
                val splitTerminalState = splitRequestTerminal?.let { terminal ->
                    if (cancellationRequested.get()) terminal.cancel()
                    terminal.current()
                }
                try {
                    splitRequestLease?.awaitFinishAfterProviderCleanup(
                        succeeded = if (usesSplitQnnWorkers) {
                            splitTerminalState == SplitSdxlRequestTerminalState.TRANSFERRED
                        } else {
                            splitOutputTransferred
                        },
                        cancelled = if (usesSplitQnnWorkers) {
                            splitTerminalState == SplitSdxlRequestTerminalState.CANCELLED
                        } else {
                            cancellationRequested.get()
                        },
                        error = splitRequestError
                    )
                } finally {
                    splitRequestTerminal?.let { terminal ->
                        activeSplitSdxlTerminal.compareAndSet(terminal, null)
                    }
                }
            }
        }
        require(model.runtime != LocalImageRuntime.QNN_HTP) {
            "QNN/QAIRT 本地生图 runner 尚未打包。该引擎包需要骁龙 NPU runtime、完整 QNN context/bin 组件，并通过 1-step smoke test 后才能生成。"
        }
        if (model.runtime == LocalImageRuntime.MNN_DIFFUSION) {
            require(NativeMnnDiffusionBridge.isAvailable) {
                val reason = NativeMnnDiffusionBridge.loadError?.message.orEmpty()
                "MNN-Diffusion image backend failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(NativeMnnDiffusionBridge.runnerReady) {
                JSONObject(mnnDiffusionBridge.getRuntimeStatsJson()).optString("lastError")
                    .ifBlank { "MNN-Diffusion native runner is not packaged in this APK." }
            }
            val bundleRoot = model.bundleRoot
                ?.let(::File)
                ?.takeIf(File::isDirectory)
                ?: File(model.path).parentFile?.takeIf(File::isDirectory)
                ?: error("MNN-Diffusion requires a complete model bundle directory.")
            prepareMnnDiffusionTokenizerIfPossible(bundleRoot)
            model.localImageStructuralReadinessMessage()?.let { message -> error(message) }
            val outputDir = File(appContext.cacheDir, "local_image_outputs").apply { mkdirs() }
            pruneStaleMnnDiffusionOutputs(outputDir)
            val outputFile = File(outputDir, "mnn-diffusion-${UUID.randomUUID()}.png")
            try {
            val fallbackSeed = (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
            val effectiveOptions = if (options.seed == null) options.copy(seed = fallbackSeed) else options
            val profileResolution = resolveLocalImageExecutionProfile(
                model = model,
                options = effectiveOptions,
                bundleRoot = bundleRoot,
                captureTextualInversionExecutionAssets = true
            )
            val profile = profileResolution.profile
            validateLocalImageProfileProductOptions(profile, effectiveOptions)
            textualInversionLease = effectiveOptions.textualInversionIds
                .takeIf(List<String>::isNotEmpty)
                ?.let { ids ->
                    require(profile.capabilities.supportsTextualInversion &&
                        profile.tokenizer.supportsTextualInversion &&
                        profile.hasHostWritableClipTextualInversionTopology()
                    ) {
                        "The resolved MNN profile has no host-writable CLIP textual-inversion topology."
                    }
                    val executionAssets = requireNotNull(profile.textualInversionExecutionAssets) {
                        "MNN textual inversion is missing its exact execution-asset snapshot."
                    }
                    textualInversionStore.acquireSelectionLease(
                        ids = ids,
                        modelFingerprint = profile.modelFingerprint,
                        tokenizerFingerprint = executionAssets.compositeSha256,
                        profileId = profile.profileId,
                        profileRevision = profile.profileRevision,
                        runtime = TextualInversionRuntime.MNN_DIFFUSION,
                        executionAssetBinding = executionAssets
                    ).also { lease ->
                        TextualInversionContract.validateNativeCapability(
                            runtime = LocalImageRuntime.MNN_DIFFUSION.name,
                            graphSupportsTextualInversion =
                                profile.hasHostWritableClipTextualInversionTopology(),
                            selection = lease.selection
                        )
                    }
                }
            val resolved = profileResolution.layers.resolved
            val effectiveNegativePrompt = resolveLocalImageFinalNegativePromptForExecution(
                finalNegativePrompt = resolveLocalImageFinalNegativePrompt(
                    userNegativePrompt = effectiveOptions.negativePrompt,
                    modelDefaultNegativePrompt = profile.defaults.defaultNegativePrompt
                ),
                useCfg = resolved.useCfg
            ).value
            requireLocalImagePromptLanguageAdmission(
                profile = profile,
                prompt = prompt.trim(),
                executedNegativePrompt = effectiveNegativePrompt
            )
            requireNativeMultilingualTextEncoderEvidenceAsset(bundleRoot, profile)
            val nativeTextEncoderBinding = captureMnnNativeTextEncoderBinding(bundleRoot, profile)
            val runner = resolveMnnDiffusionProfileRunner(profile, effectiveOptions.runner)
            val (defaultWidth, defaultHeight) = resolved.width to resolved.height
            // The direct SD1.5 interpreter has a fixed 64x64 latent today.
            // Accepting a mismatched requested size would produce a misleading
            // result, so reject it rather than silently rendering another size.
            val (width, height) = resolveMnnDiffusionDimensions(
                defaultWidth = defaultWidth,
                defaultHeight = defaultHeight,
                requestedWidth = resolved.width,
                requestedHeight = resolved.height
            )
            val steps = resolved.steps
            val threads = resolveMnnDiffusionThreads(effectiveOptions.threads, defaultLocalImageThreads())
            val backendMode = resolveMnnDiffusionBackendMode(effectiveOptions.backendMode)
            val sampleMethod = imageSchedulerProductName(resolved.scheduler)
            require(effectiveOptions.tokenEmbeddingMode == null) {
                "MNN-Diffusion no longer accepts tokenEmbeddingMode; token table precision is " +
                    "determined from the package's exact byte contract."
            }
            val memoryMode = resolveMnnDiffusionMemoryMode(effectiveOptions.memoryMode)
            require(runner != "direct" || memoryMode == 0) {
                "MNN-Diffusion direct runner requires memoryMode=0."
            }
            val seed = resolved.seed.toInt()
            val cfgScale = resolved.cfgScale
            val useCfg = resolved.useCfg
            val params = ImageExecutionProfileNativeContract.toNativeParamsJson(profileResolution)
                .put("prompt", prompt.trim())
                .put("negativePrompt", effectiveNegativePrompt)
                .put("family", profile.family.name)
                .put("variant", profile.variant.name)
                .put("width", width)
                .put("height", height)
                .put("steps", steps)
                .put("threads", threads)
                .put("seed", seed)
                .put("randomSeed", seed)
                .put("cfgScale", cfgScale)
                .put("useCfg", useCfg)
                .put("sampleMethod", sampleMethod)
                .put("runner", runner)
                .put("backendMode", backendMode)
                .put("memoryMode", memoryMode)
            effectiveOptions.putProductInputNativeParams(params)
            nativeTextEncoderBinding?.let { binding ->
                params.put("nativeTextEncoderEvidence", binding.toNativeJson())
            }
            textualInversionLease?.let { lease ->
                val nativeSelection = lease.selection.toNativeJson(lease.rootPath)
                nativeSelection.keys().forEach { key ->
                    params.put(key, nativeSelection.get(key))
                }
            }
            onProgress(
                LocalImageProgress(
                    phase = "request_validated",
                    message = "MNN-Diffusion request controls validated; native execution has not started.",
                    step = 0,
                    steps = steps,
                    elapsedMs = 0L,
                    secondsPerStep = 0.0,
                    threads = threads,
                    width = width,
                    height = height,
                    cancelRequested = false,
                    requestOptionsJson = mnnDiffusionControlAuditJson(params).toString()
                )
            )
            val progressPoller = launch {
                while (isActive) {
                    mnnDiffusionBridge.currentProgressOrNull()?.let(onProgress)
                    delay(500)
                }
            }
            val raw = try {
                mnnDiffusionBridge.generate(
                    bundleRoot.absolutePath,
                    params.toString(),
                    outputFile.absolutePath
                )
            } finally {
                progressPoller.cancelAndJoin()
                mnnDiffusionBridge.currentProgressOrNull()?.let(onProgress)
            }
            val json = JSONObject(raw)
            if (!json.optBoolean("ok", false)) {
                if (json.optBoolean("cancelled", false) || cancellationRequested.get()) {
                    throw LocalImageWorkerCancelledException()
                }
                throwLocalImageNativeFailure(json, "MNN-Diffusion image generation failed.")
            }
            ImageExecutionProfileNativeContract.parseAndValidate(profileResolution, json)
            nativeTextEncoderBinding?.verifyNativeReceipt(json)
            require(mnnDiffusionBackendMatches(backendMode, json.getString("backendMode"))) {
                "MNN-Diffusion did not execute on the resolved $backendMode backend."
            }
            require(json.getString("runner").trim().lowercase() == runner) {
                "MNN-Diffusion did not execute with the resolved $runner runner."
            }
            require(json.getString("sampleMethod") == sampleMethod) {
                "MNN-Diffusion did not execute the resolved scheduler."
            }
            require(json.getInt("memoryMode") == memoryMode) {
                "MNN-Diffusion did not execute the resolved memory mode."
            }
            val inputExecutionAudit = verifyAndSanitizeMnnProductInput(json, effectiveOptions)
            val verifiedOutput = verifyAndReadQnnImageOutput(
                nativeResult = json,
                expectedOutputFile = outputFile,
                expectedWidth = width,
                expectedHeight = height,
                checkCancelled = {
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                }
            )
            verifyMnnPublishedOutputEvidence(
                nativeResult = json,
                expectedOutputFile = outputFile,
                verifiedOutput = verifiedOutput
            )
            val textualInversionEvidence = textualInversionLease?.let { lease ->
                verifyStableDiffusionTextualInversionEvidence(
                    result = json,
                    selection = lease.selection,
                    expectedNativeMode = TextualInversionRuntime.MNN_DIFFUSION.nativeMode
                )
            }
            val executionMetadataJson = sanitizeNativeExecutionJson(
                json
                    .put("imageInput", inputExecutionAudit)
                    .putPromptExecutionBinding(
                        profile = profile,
                        prompt = prompt.trim(),
                        negativePrompt = effectiveNegativePrompt
                    )
                    .toString()
            ).takeIf(String::isNotBlank)
                ?: error("MNN-Diffusion execution evidence could not be sanitized.")
            val result = LocalImageResult(
                bytes = verifiedOutput.bytes,
                mimeType = verifiedOutput.mimeType,
                executionMetadataJson = executionMetadataJson,
                seed = seed.toLong()
            )
            coroutineContext.ensureActive()
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            if (textualInversionEvidence != null) {
                val lease = requireNotNull(textualInversionLease)
                lease.verifyUnchanged()
                lease.close()
                textualInversionLease = null
                textualInversionStore.commitSuccessfulBindings(
                    selection = lease.selection,
                    nativeBindingFingerprint = textualInversionEvidence.bindingFingerprint,
                    nativeBindingStage = textualInversionEvidence.bindingStage
                )
            }
            return@withContext result
            } finally {
                cleanupMnnDiffusionRequestOutputs(outputFile)
            }
        }
        require(model.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
            "当前仅支持 stable-diffusion.cpp 或 MNN-Diffusion 本地图像引擎。"
        }
        require(NativeStableDiffusionBridge.isAvailable) {
            val reason = NativeStableDiffusionBridge.loadError?.message.orEmpty()
            "stable-diffusion.cpp 本地后端加载失败${if (reason.isBlank()) "" else "：$reason"}"
        }
        model.localImageStructuralReadinessMessage()?.let { message -> error(message) }
        val componentSelection = resolveStableDiffusionComponentSelection(model)
        val effectiveFamily = componentSelection.family
        val bundleRoot = File(componentSelection.bundleRoot).takeIf(File::isDirectory)
            ?: error("stable-diffusion.cpp requires a complete model bundle directory.")
        val fallbackSeed = (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
        val effectiveOptions = options.copy(seed = options.seed ?: fallbackSeed)
            .normalizedForPromptExecutionProfile(LocalImageRuntime.STABLE_DIFFUSION_CPP)
        val profileResolution = resolveLocalImageExecutionProfile(
            model = model,
            options = effectiveOptions,
            bundleRoot = bundleRoot,
            familyOverride = effectiveFamily,
            captureTextualInversionExecutionAssets = true
        )
        val profile = profileResolution.profile
        validateLocalImageProfileProductOptions(profile, effectiveOptions)
        textualInversionLease = effectiveOptions.textualInversionIds
            .takeIf(List<String>::isNotEmpty)
            ?.let { ids ->
                require(profile.capabilities.supportsTextualInversion &&
                    profile.tokenizer.supportsTextualInversion
                ) {
                    "The resolved stable-diffusion.cpp profile does not expose textual inversion."
                }
                val executionAssets = requireNotNull(profile.textualInversionExecutionAssets) {
                    "stable-diffusion.cpp textual inversion is missing its exact execution-asset snapshot."
                }
                textualInversionStore.acquireSelectionLease(
                    ids = ids,
                    modelFingerprint = profile.modelFingerprint,
                    tokenizerFingerprint = executionAssets.compositeSha256,
                    profileId = profile.profileId,
                    profileRevision = profile.profileRevision,
                    executionAssetBinding = executionAssets
                ).also { lease ->
                    TextualInversionContract.validateNativeCapability(
                        runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP.name,
                        graphSupportsTextualInversion =
                            profile.capabilities.supportsTextualInversion &&
                                profile.tokenizer.supportsTextualInversion,
                        selection = lease.selection
                    )
                }
            }
        val nativeComponentSelection = componentSelection
            .withControlNetPath(resolveProfileControlNetPath(bundleRoot, profile))
            .let { selection ->
                if (profile.textEncoderLanguageCapability() ==
                    LocalImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL
                ) {
                    selection.requireTextEncoderPath(
                        requireNotNull(resolveProfileTextEncoderPath(bundleRoot, profile)) {
                            "Native multilingual profile is missing its evidence-bound text encoder."
                        }
                    )
                } else {
                    selection
                }
            }
        val resolved = profileResolution.layers.resolved

        val outputDir = File(appContext.cacheDir, "local_image_outputs").apply { mkdirs() }
        pruneStaleStableDiffusionOutputs(outputDir)
        val outputFile = File(outputDir, "sdcpp-${UUID.randomUUID()}.png")
        try {
        val (width, height) = resolveStableDiffusionDimensions(
            defaultWidth = resolved.width,
            defaultHeight = resolved.height,
            requestedWidth = resolved.width,
            requestedHeight = resolved.height
        )
        val steps = resolved.steps
        val threads = resolveStableDiffusionThreads(effectiveOptions.threads, defaultLocalImageThreads())
        val seed = resolved.seed
        val cfgScale = resolved.cfgScale
        val distilledGuidance = resolveStableDiffusionFiniteControl(
            name = "distilledGuidance",
            requested = effectiveOptions.distilledGuidance,
            defaultValue = 3.5
        )
        val flowShift = resolveStableDiffusionFiniteControl(
            name = "flowShift",
            requested = effectiveOptions.flowShift,
            defaultValue = defaultStableDiffusionFlowShiftFor(profile)
        )
        val sampleMethod = imageSchedulerProductName(resolved.scheduler)
        val backendMode = resolveStableDiffusionBackendMode(effectiveOptions.backendMode)
        val effectiveNegativePrompt = resolveLocalImageFinalNegativePromptForExecution(
            finalNegativePrompt = resolveLocalImageFinalNegativePrompt(
                userNegativePrompt = effectiveOptions.negativePrompt,
                modelDefaultNegativePrompt = profile.defaults.defaultNegativePrompt
            ),
            useCfg = resolved.useCfg
        ).value
        requireLocalImagePromptLanguageAdmission(
            profile = profile,
            prompt = prompt.trim(),
            executedNegativePrompt = effectiveNegativePrompt
        )
        requireNativeMultilingualTextEncoderEvidenceAsset(bundleRoot, profile)
        require(effectiveOptions.runner == null) {
            "stable-diffusion.cpp does not support the MNN runner option."
        }
        require(effectiveOptions.tokenEmbeddingMode == null) {
            "stable-diffusion.cpp no longer accepts tokenEmbeddingMode; its native tokenizer owns conditioning storage."
        }
        require(effectiveOptions.memoryMode == null || effectiveOptions.memoryMode == 0) {
            "stable-diffusion.cpp supports only memoryMode=0."
        }
        val params = ImageExecutionProfileNativeContract.toNativeParamsJson(profileResolution)
            .put("prompt", prompt.trim())
            .put("negativePrompt", effectiveNegativePrompt)
            .put("family", effectiveFamily.name)
            .put("variant", profile.variant.name)
            .put("width", width)
            .put("height", height)
            .put("steps", steps)
            .put("threads", threads)
            .put("seed", seed)
            .put("cfgScale", cfgScale)
            .put("distilledGuidance", distilledGuidance)
            .put("distilledGuidanceSpecified", effectiveOptions.distilledGuidance != null)
            .put("flowShift", flowShift)
            .put("flowShiftSpecified", effectiveOptions.flowShift != null)
            .put("sampleMethod", sampleMethod)
            .put("backendMode", backendMode)
        effectiveOptions.putProductInputNativeParams(params)
        textualInversionLease?.let { lease ->
            val nativeTextualInversionParams = TextualInversionContract.run {
                lease.selection.toNativeJson(rootPath = lease.rootPath)
            }
            nativeTextualInversionParams.keys().forEach { key ->
                params.put(key, nativeTextualInversionParams.get(key))
            }
        }
        nativeComponentSelection.putIntoNativeParams(params)

        val progressPoller = launch {
            while (isActive) {
                bridge.currentProgressOrNull()?.let(onProgress)
                delay(500)
            }
        }
        val raw = try {
            bridge.generate(
                componentSelection.primaryPath,
                componentSelection.bundleRoot,
                params.toString(),
                outputFile.absolutePath
            )
        } finally {
            progressPoller.cancelAndJoin()
            bridge.currentProgressOrNull()?.let(onProgress)
            // stable-diffusion.cpp keeps a process-global context for reuse.
            // The product worker is disposable and must return the model's
            // multi-gigabyte mappings after every terminal outcome instead.
            runCatching { bridge.shutdown() }
            stableDiffusionPreviewCandidates(outputFile).forEach { preview ->
                runCatching { preview.delete() }
            }
        }
        val json = JSONObject(raw)
        if (!json.optBoolean("ok", false)) {
            if (json.optBoolean("cancelled", false) || cancellationRequested.get()) {
                throw LocalImageWorkerCancelledException()
            }
            throwLocalImageNativeFailure(json, "stable-diffusion.cpp image generation failed.")
        }
        ImageExecutionProfileNativeContract.parseAndValidate(profileResolution, json)
        require(json.optInt("width", -1) == width && json.optInt("height", -1) == height) {
            "stable-diffusion.cpp did not execute the requested ${width}x${height} dimensions."
        }
        require(json.optInt("steps", -1) == steps) {
            "stable-diffusion.cpp did not execute the requested $steps steps."
        }
        require(json.optInt("threads", -1) == threads) {
            "stable-diffusion.cpp did not execute the requested $threads threads."
        }
        require(json.has("seed") && json.getLong("seed") == seed) {
            "stable-diffusion.cpp did not execute the requested seed."
        }
        verifyStableDiffusionResultControl(json, "cfgScale", cfgScale)
        verifyStableDiffusionDistilledGuidanceResult(
            result = json,
            requested = distilledGuidance,
            specified = effectiveOptions.distilledGuidance != null
        )
        verifyStableDiffusionFlowShiftResult(
            result = json,
            requested = flowShift,
            specified = effectiveOptions.flowShift != null,
            expectApplied = resolved.predictionType == ImagePredictionType.FLOW
        )
        require(stableDiffusionNativeSampleMethodMatches(resolved.scheduler, json.optString("sampleMethod"))) {
            "stable-diffusion.cpp did not execute the resolved ${resolved.scheduler} sampler."
        }
        require(
            json.has("negativePrompt") &&
                json.getString("negativePrompt") == effectiveNegativePrompt
        ) {
            "stable-diffusion.cpp did not execute the resolved negative prompt."
        }
        require(json.optString("backendMode") == backendMode) {
            "stable-diffusion.cpp did not execute on backendMode=$backendMode."
        }
        require(json.optBoolean("contextReleased", false)) {
            "stable-diffusion.cpp did not confirm native context release."
        }
        val textualInversionEvidence = verifyStableDiffusionTextualInversionEvidence(
            result = json,
            selection = textualInversionLease?.selection
        )
        val componentSelectionAudit = nativeComponentSelection.verifyNativeEcho(json)
        val inputExecutionAudit = verifyAndSanitizeStableDiffusionProductInput(json, effectiveOptions)
        val outputs = consumeStableDiffusionOutputs(
            result = json,
            expectedCount = effectiveOptions.batchCount,
            expectedSeed = seed,
            legacyOutputFile = outputFile,
            requireCommittedEvidence = effectiveOptions.ultraFix != null
        )
        verifyStableDiffusionUltraFixEvidence(
            result = json,
            request = effectiveOptions.ultraFix,
            inputImage = effectiveOptions.inputImage,
            requestedUseCfg = resolved.useCfg,
            outputs = outputs
        )
        val executionMetadataJson = sanitizeNativeExecutionJson(
            json
                .put("componentSelection", componentSelectionAudit)
                .put("imageInput", inputExecutionAudit)
                .putPromptExecutionBinding(
                    profile = profile,
                    prompt = prompt.trim(),
                    negativePrompt = effectiveNegativePrompt
                )
                .toString()
        ).takeIf(String::isNotBlank)
            ?: error("stable-diffusion.cpp execution evidence could not be sanitized.")
        val first = outputs.first()
        val result = LocalImageResult(
            bytes = first.bytes,
            mimeType = first.mimeType,
            executionMetadataJson = executionMetadataJson,
            seed = first.seed,
            outputs = outputs
        )
        coroutineContext.ensureActive()
        if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
        textualInversionLease?.let { lease ->
            lease.verifyUnchanged()
            lease.close()
            textualInversionLease = null
            textualInversionStore.commitSuccessfulBindings(
                selection = lease.selection,
                nativeBindingFingerprint = textualInversionEvidence.bindingFingerprint,
                nativeBindingStage = textualInversionEvidence.bindingStage
            )
        }
        result
        } finally {
            cleanupStableDiffusionRequestOutputs(outputFile)
        }
        } finally {
            textualInversionLease?.close()
            textualInversionLease = null
            if (activeRuntime == model.runtime) {
                activeRuntime = null
                cancellationRequested.set(false)
            }
        }
    }

    private fun NativeStableDiffusionBridge.currentProgressOrNull(): LocalImageProgress? =
        runCatching {
            localImageProgressFromJson(JSONObject(getProgress()))
        }.getOrNull()

    private fun NativeMnnDiffusionBridge.currentProgressOrNull(): LocalImageProgress? =
        runCatching {
            localImageProgressFromJson(JSONObject(getProgress()))
        }.getOrNull()

    private fun NativeQnnBridge.currentImageProgressOrNull(
        threads: Int,
        width: Int,
        height: Int
    ): LocalImageProgress? = runCatching {
        val json = JSONObject(getImageGenerationProgressJson())
        if (!json.optBoolean("active") &&
            !json.optBoolean("cancelRequested") &&
            (json.optJSONArray("stageTrace")?.length() ?: 0) == 0
        ) {
            return@runCatching null
        }
        localImageProgressFromJson(json).copy(
            threads = threads,
            width = width,
            height = height
        )
    }.getOrNull()

    private fun localImageProgressFromJson(json: JSONObject): LocalImageProgress =
        LocalImageProgress(
            phase = json.optString("phase"),
            message = json.optString("message"),
            step = json.optInt("step"),
            steps = json.optInt("steps"),
            elapsedMs = json.optLong("elapsedMs"),
            secondsPerStep = json.optDouble("secondsPerStep"),
            threads = json.optInt("threads"),
            width = json.optInt("width"),
            height = json.optInt("height"),
            cancelRequested = json.optBoolean("cancelRequested"),
            componentSelectionJson = json.optJSONObject("componentSelection")?.toString().orEmpty(),
            previewPath = json.optString("previewPath"),
            previewMimeType = json.optString("previewMimeType"),
            previewMode = json.optString("previewMode"),
            previewStep = json.optInt("previewStep"),
            previewRevision = json.optLong("previewRevision"),
            previewWidth = json.optInt("previewWidth"),
            previewHeight = json.optInt("previewHeight"),
            previewFrameCount = json.optInt("previewFrameCount"),
            previewNoisy = json.optBoolean("previewNoisy"),
            previewVaeExecutionAttemptCount = json.optInt("previewVaeExecutionAttemptCount"),
            previewVaeExecutionCount = json.optInt("previewVaeExecutionCount"),
            previewVaeExecutionMsTotal = json.optLong("previewVaeExecutionMsTotal"),
            previewPublicationCount = json.optInt("previewPublicationCount"),
            previewLastStep = json.optInt("previewLastStep"),
            previewLastRevision = json.optLong("previewLastRevision"),
            previewFailureCode = json.optString("previewFailureCode"),
            stageTrace = json.optJSONArray("stageTrace")?.let { trace ->
                buildList {
                    for (index in 0 until trace.length()) {
                        trace.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }.orEmpty()
        )

    private fun defaultLocalImageThreads(): Int {
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return when {
            available >= 10 -> 5
            available >= 8 -> 4
            available >= 6 -> 4
            available >= 4 -> 3
            else -> 2
        }.coerceAtMost(available).coerceAtLeast(1)
    }

    private fun qnnRuntimeDirsJson(bundleRoot: File): String {
        return JSONArray(qnnRuntimeDirectoriesFor(appContext, bundleRoot)).toString()
    }

    private fun JSONObject.putQnnSemanticDefaults(
        bundleRoot: File,
        profile: ImageExecutionProfile,
        includeVaeEncoderEvidence: Boolean
    ): JSONObject {
        val manifest = localImageBundleManifestFromRoot(bundleRoot)
        val smokes = manifest?.qnnSmokeSpecs.orEmpty()
        val unet = smokes.firstOrNull { spec ->
            val lower = spec.contextBinary.lowercase()
            "unet" in lower || "diffusion" in lower || spec.inputs.size >= 3
        } ?: smokes.firstOrNull()
        val vae = smokes.firstOrNull { spec ->
            val lower = spec.contextBinary.lowercase()
            "vae" in lower || "decoder" in lower || (spec.inputs.size == 1 && spec.outputs.any { it.shape.contains(3) })
        }
        val manifestBound = profile.provenance.primarySource == ImageProfileSource.MANIFEST
        fun resolveProfileArtifact(
            artifact: ImageGraphArtifactContract?,
            required: Boolean,
            vararg legacyNames: String
        ): String? {
            artifact?.relativePath?.trim()?.takeIf(String::isNotEmpty)?.let { relativePath ->
                require(!File(relativePath).isAbsolute && !Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(relativePath)) {
                    "Image execution graph path must stay inside the installed bundle."
                }
                val root = bundleRoot.canonicalFile
                val candidate = File(root, relativePath).canonicalFile
                require(candidate.path.startsWith(root.path + File.separator)) {
                    "Image execution graph path escapes the installed bundle."
                }
                if (candidate.isFile) return candidate.relativeTo(root).invariantSeparatorsPath
                if (manifestBound && required) {
                    error("Installed image execution profile is missing required graph: $relativePath")
                }
            }
            return qnnFirstContextPath(bundleRoot, *legacyNames)
        }
        val profileUnet = resolveProfileArtifact(profile.graph.unet, true, "unet.bin")
        val profileVae = resolveProfileArtifact(
            profile.graph.vae,
            true,
            "vae.bin",
            "vae_decoder.bin"
        )
        val textEncoderContext = resolveProfileArtifact(
            profile.graph.textEncoder?.takeIf { it.relativePath.endsWith(".bin", ignoreCase = true) },
            false,
            "text_encoder.bin"
        )
        val vaeEncoderContext = if (includeVaeEncoderEvidence) {
            requireNotNull(
                resolveProfileArtifact(
                    profile.graph.vaeEncoder,
                    true,
                    "vae_encoder.bin"
                )
            ) { "Resolved img2img profile is missing its required VAE encoder context." }
        } else {
            null
        }
        val controlNetContext = resolveProfileArtifact(
            profile.graph.controlNet,
            profile.task == ImageTask.CONTROL_IMAGE,
            "controlnet.bin"
        )
        put(
            "unetContextBinary",
            profileUnet
                ?: unet?.contextBinary?.takeIf { it.isNotBlank() }
                ?: "unet.bin"
        )
        put(
            "vaeDecoderContextBinary",
            profileVae
                ?: vae?.contextBinary?.takeIf { it.isNotBlank() }
                ?: "vae_decoder.bin"
        )
        textEncoderContext?.let { put("textEncoderContextBinary", it) }
        vaeEncoderContext?.let { relativePath ->
            val root = bundleRoot.canonicalFile
            val encoderContext = File(root, relativePath).canonicalFile
            require(
                encoderContext.path.startsWith(root.path + File.separator) &&
                    encoderContext.isFile && encoderContext.length() > 0L
            ) { "Resolved VAE encoder context is missing or escapes the installed bundle." }
            put("vaeEncoderContextBinary", relativePath)
            put("vaeEncoderContextSha256", encoderContext.sha256Contents())
            val normalizedEncoderPath = relativePath.replace('\\', '/').trimStart('/')
            val smokeGraphName = smokes.firstOrNull { spec ->
                spec.contextBinary.replace('\\', '/').trimStart('/')
                    .equals(normalizedEncoderPath, ignoreCase = true)
            }?.graphName?.trim()?.takeIf(String::isNotEmpty)
            put(
                "vaeEncoderGraphName",
                profile.graph.vaeEncoder?.graphName?.trim()?.takeIf(String::isNotEmpty)
                    ?: smokeGraphName
                    ?: "model"
            )
        }
        controlNetContext?.let { put("controlNetContextBinary", it) }
        put("graphName", profile.graph.unet?.graphName?.takeIf { it.isNotBlank() }
            ?: unet?.graphName?.takeIf { it.isNotBlank() }
            ?: "model")
        profile.graph.textEncoder?.graphName?.takeIf { it.isNotBlank() }
            ?.let { put("textEncoderGraphName", it) }
        profile.graph.controlNet?.graphName?.takeIf { it.isNotBlank() }
            ?.let { put("controlNetGraphName", it) }
        return this
    }

    /**
     * Enables the QNN bridge's strict text-encoder path only for a profile that has already
     * passed the generic topology and evidence checks. The bridge receives bundle-relative paths
     * exclusively and re-hashes the file before mmap, after mmap, and after graph execution.
     */
    private fun JSONObject.putQnnNativeMultilingualTextEncoderEvidence(
        bundleRoot: File,
        profile: ImageExecutionProfile
    ): JSONObject {
        if (!profile.hasVerifiedNativeSimplifiedChineseTextEncoder()) return this
        require(profile.runtime == LocalImageRuntime.QNN_HTP &&
            profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
        ) {
            "Direct Chinese QNN prompts require a shared native text-encoder graph."
        }
        val evidence = requireNotNull(profile.textEncoderLanguage?.evidence) {
            "Direct Chinese QNN prompts require a pinned text-encoder evidence asset."
        }
        val tokenizerEvidence = evidence.promptToEncoderAssets.singleOrNull { entry ->
            entry.role == ImagePromptToEncoderAssetRole.TOKENIZER_JSON
        }?.asset ?: error(
            "Direct Chinese QNN prompts require one pinned tokenizer JSON evidence asset."
        )
        val expectedGraphName = requireNotNull(profile.graph.textEncoder)
            .graphName
            .trim()
            .takeIf(String::isNotEmpty)
            ?: error("Direct Chinese QNN prompts require an explicit text-encoder graph name.")
        val expectedRelativePath = evidence.textEncoderAsset.relativePath
            .replace('\\', '/')
            .trim()
        val expectedSizeBytes = requireNotNull(evidence.textEncoderAsset.sizeBytes) {
            "Direct Chinese QNN prompts require a size-pinned text-encoder evidence asset."
        }
        val requestedRelativePath = optString("textEncoderContextBinary")
            .replace('\\', '/')
            .trim()
        require(requestedRelativePath == expectedRelativePath) {
            "QNN semantic defaults selected a text encoder different from the language evidence asset."
        }
        val root = bundleRoot.canonicalFile
        val encoder = File(root, requestedRelativePath).canonicalFile
        require(encoder.path.startsWith(root.path + File.separator) &&
            encoder.isFile &&
            encoder.length() == expectedSizeBytes
        ) {
            "Pinned QNN text-encoder evidence asset is unavailable or has an unexpected size."
        }
        val actualSha256 = encoder.sha256Contents()
        require(actualSha256.equals(evidence.textEncoderAsset.fingerprint, ignoreCase = true)) {
            "Pinned QNN text-encoder evidence asset SHA-256 differs from the resolved profile."
        }
        val tokenizer = qnnPinnedNativeMultilingualTokenizerJsonFile(bundleRoot, evidence)
        val expectedTokenizerSizeBytes = requireNotNull(tokenizerEvidence.sizeBytes) {
            "Direct Chinese QNN prompts require a size-pinned tokenizer JSON evidence asset."
        }
        val actualTokenizerSha256 = tokenizer.sha256Contents()
        require(actualTokenizerSha256.equals(tokenizerEvidence.fingerprint, ignoreCase = true) &&
            tokenizer.length() == expectedTokenizerSizeBytes
        ) {
            "Pinned QNN tokenizer JSON evidence asset differs from the resolved profile."
        }
        val tokenizerRelativePath = tokenizer.relativeTo(root).invariantSeparatorsPath
        require(tokenizerRelativePath == tokenizerEvidence.relativePath.replace('\\', '/').trim()) {
            "Pinned QNN tokenizer JSON evidence path differs from the resolved profile."
        }
        val proofSha256 = requireNotNull(
            profile.verifiedNativeSimplifiedChineseLanguageProofSha256()
        ) { "Direct Chinese QNN prompts require a verified signed semantic proof." }
        require(optString("textEncoderGraphName").trim() == expectedGraphName) {
            "QNN semantic defaults selected a text-encoder graph different from the signed language proof."
        }
        return put("textEncoderPath", requestedRelativePath)
            .put("textEncoderSha256", actualSha256.lowercase())
            .put("textEncoderSizeBytes", encoder.length())
            .put("textEncoderGraphName", expectedGraphName)
            .put("tokenizerJsonPath", tokenizerRelativePath)
            .put("tokenizerJsonSha256", actualTokenizerSha256.lowercase())
            .put("tokenizerJsonSizeBytes", tokenizer.length())
            .put("promptToEncoderClosureSha256", evidence.promptToEncoderClosureSha256())
            .put("languageProofSha256", proofSha256)
    }
}

/**
 * Returns the exact bundle-relative QNN text-encoder context path.  Presence
 * of this graph selects the token-ID contract; it is a package capability,
 * never a device admission rule.
 */
internal fun qnnNativeTextEncoderContextPath(bundleRoot: File): String? =
    qnnFirstContextPath(bundleRoot, "text_encoder.bin")

/** Resolves the exact QNN text-encoder artifact declared by a shared-text graph topology. */
internal fun qnnDeclaredTextEncoderContextPath(
    bundleRoot: File,
    declaredArtifact: ImageGraphArtifactContract?
): String? {
    val relativePath = declaredArtifact
        ?.relativePath
        ?.trim()
        ?.takeIf { it.endsWith(".bin", ignoreCase = true) }
        ?: return null
    require(!File(relativePath).isAbsolute &&
        !relativePath.startsWith('/') &&
        !relativePath.startsWith('\\') &&
        !Regex("^[A-Za-z]:").containsMatchIn(relativePath)
    ) { "Declared QNN text-encoder path must stay inside the installed bundle." }
    val root = bundleRoot.canonicalFile
    val candidate = File(root, relativePath).canonicalFile
    require(candidate.path.startsWith(root.path + File.separator)) {
        "Declared QNN text-encoder path escapes the installed bundle."
    }
    return candidate
        .takeIf { it.isFile && it.length() > 0L }
        ?.relativeTo(root)
        ?.invariantSeparatorsPath
}

internal fun qnnFirstContextPath(bundleRoot: File, vararg names: String): String? {
    val expected = names.map(String::lowercase).toSet()
    val root = runCatching { bundleRoot.canonicalFile }.getOrNull() ?: return null
    return root.walkTopDown()
        .firstOrNull { file -> file.isFile && file.name.lowercase() in expected }
        ?.let { file ->
            runCatching { file.canonicalFile.relativeTo(root).invariantSeparatorsPath }.getOrNull()
        }
}

/** Locates the MNN tokenizer sidecar consumed by [MtokTokenizer]. */
internal fun qnnClipTokenizerRoot(bundleRoot: File): File? {
    val root = runCatching { bundleRoot.canonicalFile }.getOrNull() ?: return null
    return root.walkTopDown()
        .filter { file ->
            file.isFile &&
                file.length() > 0L &&
                file.name.equals("tokenizer.mtok", ignoreCase = true)
        }
        .mapNotNull { file ->
            runCatching { file.canonicalFile }
                .getOrNull()
                ?.takeIf { candidate ->
                    candidate.path.startsWith(root.path + File.separator)
                }
                ?.parentFile
        }
        .firstOrNull()
}

/** Locates the complete tokenizer contract used by the standard CLIP backend. */
internal fun qnnClipTokenizerJsonFile(bundleRoot: File): File? {
    val root = runCatching { bundleRoot.canonicalFile }.getOrNull() ?: return null
    return root.walkTopDown()
        .firstOrNull { file ->
            file.isFile &&
                file.length() > 0L &&
                file.name.equals("tokenizer.json", ignoreCase = true)
        }
        ?.let { file ->
            runCatching { file.canonicalFile }
                .getOrNull()
                ?.takeIf { candidate ->
                    candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)
                }
        }
}

/**
 * Resolves the tokenizer JSON named by a verified native-multilingual proof.
 *
 * This deliberately does not search the bundle: a bundle may contain tokenizer sidecars for
 * unrelated graphs, and choosing the first matching file would disconnect Chinese text from the
 * signed prompt-to-encoder closure. Native opens this exact path with no-follow semantics and
 * verifies its descriptor while it is consumed.
 */
internal fun qnnPinnedNativeMultilingualTokenizerJsonFile(
    bundleRoot: File,
    evidence: ImageTextEncoderLanguageEvidence
): File {
    val tokenizerAsset = evidence.promptToEncoderAssets.singleOrNull { entry ->
        entry.role == ImagePromptToEncoderAssetRole.TOKENIZER_JSON
    }?.asset ?: error(
        "Verified native-multilingual QNN execution requires exactly one TOKENIZER_JSON asset."
    )
    val relativePath = tokenizerAsset.relativePath.replace('\\', '/').trim()
    require(
        relativePath.isNotBlank() &&
            !relativePath.startsWith('/') &&
            !Regex("^[A-Za-z]:").containsMatchIn(relativePath) &&
            relativePath.split('/').all { segment ->
                segment.isNotBlank() && segment != "." && segment != ".."
            }
    ) { "Pinned native-multilingual tokenizer path must stay inside the image bundle." }
    val root = bundleRoot.canonicalFile
    require(root.isDirectory) { "Pinned native-multilingual tokenizer bundle root is unavailable." }
    val requested = File(root, relativePath).canonicalFile
    require(requested.path.startsWith(root.path + File.separator)) {
        "Pinned native-multilingual tokenizer path escapes the image bundle."
    }
    require(requested.isFile && requested.length() == tokenizerAsset.sizeBytes) {
        "Pinned native-multilingual tokenizer asset is missing or has an unexpected size."
    }
    require(requested.sha256Contents().equals(tokenizerAsset.fingerprint, ignoreCase = true)) {
        "Pinned native-multilingual tokenizer asset digest differs from the signed closure."
    }
    return requested
}

/**
 * The token-ID conditioner is implemented by the MNN bridge even when the text encoder runs on
 * QNN. The serializable fields below are checked only as diagnostics; authorization comes from
 * the one-time native handoff that QNN consumes against the observed artifact and closure.
 */
private data class QnnNativeMultilingualPromptHandoff(
    val opaqueHandle: String
)

private fun verifyQnnNativeMultilingualPromptHandoff(
    bundleRoot: File,
    evidence: ImageTextEncoderLanguageEvidence,
    encoded: JSONObject
): QnnNativeMultilingualPromptHandoff {
    val tokenizerAsset = evidence.promptToEncoderAssets.singleOrNull { entry ->
        entry.role == ImagePromptToEncoderAssetRole.TOKENIZER_JSON
    }?.asset ?: error(
        "Verified native-multilingual QNN execution requires exactly one TOKENIZER_JSON asset."
    )
    val expectedFile = qnnPinnedNativeMultilingualTokenizerJsonFile(bundleRoot, evidence)
    val expectedPath = expectedFile.canonicalPath
    val expectedSha256 = tokenizerAsset.fingerprint.lowercase()
    val expectedSizeBytes = requireNotNull(tokenizerAsset.sizeBytes) {
        "Verified native-multilingual QNN execution requires a size-pinned tokenizer JSON asset."
    }
    require(expectedFile.length() == expectedSizeBytes) {
        "Pinned native-multilingual tokenizer asset size changed before native conditioning."
    }
    require(expectedFile.sha256Contents().equals(expectedSha256, ignoreCase = true)) {
        "Pinned native-multilingual tokenizer asset digest changed before native conditioning."
    }

    val actualPath = (encoded.opt("tokenizerAssetPath") as? String)
        ?.takeIf(String::isNotBlank)
        ?: error("Native tokenizer receipt is missing tokenizerAssetPath.")
    val actualSha256 = (encoded.opt("tokenizerAssetSha256") as? String)
        ?.lowercase()
        ?: error("Native tokenizer receipt is missing tokenizerAssetSha256.")
    val actualSizeValue = encoded.opt("tokenizerAssetSizeBytes")
    val actualSizeBytes = actualSizeValue
        .takeIf { value ->
            value is Byte || value is Short || value is Int || value is Long
        }
        ?.let { value -> (value as Number).toLong() }
        ?: error("Native tokenizer receipt is missing an exact tokenizerAssetSizeBytes value.")
    val bindingStage = (encoded.opt("tokenizerAssetBindingStage") as? String)
        ?: error("Native tokenizer receipt is missing tokenizerAssetBindingStage.")
    val receiptFile = runCatching { File(actualPath).canonicalFile }.getOrNull()
        ?: error("Native tokenizer receipt path is invalid.")
    require(
        receiptFile.path == expectedPath &&
            actualSha256 == expectedSha256 &&
            actualSizeBytes == expectedSizeBytes &&
            bindingStage == "tokenizer_consumed" &&
            Regex("^[0-9a-f]{64}$").matches(actualSha256)
    ) {
        "Native tokenizer receipt differs from the signed QNN prompt-to-encoder closure."
    }
    val opaqueHandle = (encoded.opt("mnnPromptHandoff") as? String)
        ?.lowercase()
        ?: error("Native tokenizer receipt is missing its one-time MNN-to-QNN handoff.")
    require(Regex("^[0-9a-f]{64}$").matches(opaqueHandle)) {
        "Native tokenizer handoff must be a 256-bit lowercase opaque handle."
    }
    return QnnNativeMultilingualPromptHandoff(opaqueHandle = opaqueHandle)
}

/** Writes raw token IDs or the versioned token+weight payload selected by the resolved profile. */
internal fun encodeQnnClipPromptTokenIds(
    bridge: NativeMnnDiffusionBridge,
    bundleRoot: File,
    prompt: String,
    outputFile: File,
    negativePrompt: String = "",
    bosId: Int = 49_406,
    eosId: Int = 49_407,
    padId: Int = 49_407,
    maxTokens: Int = 77,
    promptWeightingEnabled: Boolean = false,
    /** Non-null only after the caller verifies the complete signed QNN Chinese closure. */
    nativeMultilingualEvidence: ImageTextEncoderLanguageEvidence? = null
): String = runCatching {
    require(maxTokens in 1..4_096) { "CLIP tokenizer max length is invalid: $maxTokens." }
    val tokenizerJson = nativeMultilingualEvidence?.let { evidence ->
        qnnPinnedNativeMultilingualTokenizerJsonFile(bundleRoot, evidence)
    } ?: qnnClipTokenizerJsonFile(bundleRoot)
    val tokenizerRoot = nativeMultilingualEvidence
        ?.let { null }
        ?: qnnClipTokenizerRoot(bundleRoot)
    val requireVersionedTokenWeightPayload = nativeMultilingualEvidence != null
    val tokenizerBackend: String
    var unweightedProbeEvidence: NativePromptEncodingEvidence? = null
    outputFile.parentFile?.mkdirs()
    if (promptWeightingEnabled || requireVersionedTokenWeightPayload) {
        require(tokenizerJson != null) {
            if (requireVersionedTokenWeightPayload) {
                "Native-multilingual QNN execution requires its pinned tokenizer JSON asset."
            } else {
                "Prompt weighting requires tokenizer/tokenizer.json in the image bundle."
            }
        }
        return@runCatching bridge.encodePromptTokenIdsWithWeightsFromJson(
            tokenizerJsonPath = tokenizerJson.absolutePath,
            prompt = prompt,
            negativePrompt = negativePrompt,
            bosId = bosId,
            eosId = eosId,
            padId = padId,
            maxTokens = maxTokens,
            promptToEncoderClosureSha256 = nativeMultilingualEvidence
                ?.promptToEncoderClosureSha256()
                .orEmpty(),
            outputPath = outputFile.absolutePath
        )
    }
    if (tokenizerJson != null) {
        // An int32 token-id graph cannot apply attention weights before its Transformer. Parse
        // with the same native tokenizer first so a genuinely weighted prompt fails explicitly
        // instead of being silently reinterpreted as literal punctuation.
        val probeFile = File(outputFile.parentFile, outputFile.name + ".weight-probe")
        try {
            val probe = JSONObject(
                bridge.encodePromptTokenIdsWithWeightsFromJson(
                    tokenizerJsonPath = tokenizerJson.absolutePath,
                    prompt = prompt,
                    negativePrompt = negativePrompt,
                    bosId = bosId,
                    eosId = eosId,
                    padId = padId,
                    maxTokens = maxTokens,
                    promptToEncoderClosureSha256 = "",
                    outputPath = probeFile.absolutePath
                )
            )
            require(probe.optBoolean("ok", false)) {
                probe.optString("error").ifBlank { "Failed to inspect CLIP prompt weighting." }
            }
            val positiveWeighted = probe.optInt("positiveWeightedTokenCount", -1)
            val negativeWeighted = probe.optInt("negativeWeightedTokenCount", -1)
            require(positiveWeighted >= 0 && negativeWeighted >= 0) {
                "Native CLIP prompt-weighting evidence is incomplete."
            }
            val weightedTokenCount = positiveWeighted + negativeWeighted
            require(
                probe.optBoolean("promptWeightingApplied", false) == (weightedTokenCount > 0)
            ) { "Native CLIP prompt-weighting evidence is inconsistent." }
            if (weightedTokenCount > 0) {
                throw LocalImageProductContractException(
                    "prompt_weighting_execution_unsupported",
                    "This package's int32 token-id text encoder cannot apply non-unity prompt weights before the Transformer."
                )
            }
            unweightedProbeEvidence = requireNativePromptEncodingEvidence(
                source = probe,
                prompt = prompt,
                negativePrompt = negativePrompt,
                requireNoAppliedWeights = true
            )
        } finally {
            runCatching { probeFile.delete() }
            runCatching { File(probeFile.absolutePath + ".part").delete() }
        }
    }
    val tokenIds = if (tokenizerJson != null) {
        tokenizerBackend = "tokenizers_cpp"
        bridge.tokenizePromptTokenIdsFromJson(
            tokenizerJsonPath = tokenizerJson.absolutePath,
            prompt = prompt,
            negativePrompt = negativePrompt,
            bosId = bosId,
            eosId = eosId,
            padId = padId,
            maxTokens = maxTokens
        )
    } else if (tokenizerRoot != null && negativePrompt.isEmpty()) {
        tokenizerBackend = "mnn_mtok"
        bridge.tokenizePromptTokenIdsWithConfig(
            bundleRoot = bundleRoot.absolutePath,
            prompt = prompt,
            tokenizerRoot = tokenizerRoot.absolutePath,
            bosId = bosId,
            eosId = eosId,
            maxTokens = maxTokens
        )
    } else if (negativePrompt.isEmpty() && bosId == 49_406 && eosId == 49_407 && padId == 49_407 && maxTokens == 77) {
        tokenizerBackend = "mnn_mtok"
        bridge.tokenizePromptTokenIds(bundleRoot.absolutePath, prompt)
    } else {
        error(
            "The image bundle does not contain tokenizer/tokenizer.json; " +
                "the requested negative prompt or tokenizer contract cannot be executed exactly."
        )
    }
    val expectedTokenCount = maxTokens * 2
    require(tokenIds.size == expectedTokenCount) {
        "QNN CLIP tokenizer returned ${tokenIds.size} IDs; expected $expectedTokenCount."
    }
    val bytes = java.nio.ByteBuffer
        .allocate(tokenIds.size * Int.SIZE_BYTES)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
    tokenIds.forEach(bytes::putInt)
    outputFile.writeBytes(bytes.array())
    val promptEvidence = requireNotNull(unweightedProbeEvidence) {
        "QNN CLIP token conditioning requires tokenizer.json native prompt evidence."
    }
    JSONObject()
        .put("ok", true)
        .put("conditioningFormat", "qnn_clip_token_ids_i32")
        .put("tokenizerBackend", tokenizerBackend)
        .put("negativePromptSpecified", negativePrompt.isNotEmpty())
        .put("bosId", bosId)
        .put("eosId", eosId)
        .put("padId", padId)
        .put("tokenCount", tokenIds.size)
        .put("outputPath", outputFile.absolutePath)
        .put("promptWeightingApplied", promptEvidence.promptWeightingApplied)
        .put("positiveWeightedTokenCount", promptEvidence.positiveWeightedTokenCount)
        .put("negativeWeightedTokenCount", promptEvidence.negativeWeightedTokenCount)
        .put("promptWeightFingerprint", promptEvidence.promptWeightFingerprint)
        .put("nativePromptExecutionSha256", promptEvidence.nativePromptExecutionSha256)
        .put("nativePromptBindingStage", promptEvidence.nativePromptBindingStage)
        .toString()
}.getOrElse { error ->
    JSONObject()
        .put("ok", false)
        .apply {
            if (error is LocalImageProductContractException) {
                put("errorCode", error.code)
            }
        }
        .put("error", error.message ?: "Failed to tokenize QNN CLIP prompt.")
        .toString()
}

internal fun qnnSharedPreviewExecutionAudit(
    nativeResult: JSONObject,
    requestedPreview: LocalImagePreviewOptions?
): JSONObject {
    require(!nativeResult.toString().contains("\"previewPath\"")) {
        "Final QNN execution metadata must not expose a transient preview path."
    }
    fun exactLong(field: String): Long {
        val number = nativeResult.opt(field) as? Number
            ?: error("Native QNN preview evidence field $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "Native QNN preview evidence field $field must be an exact integer."
        }
        return value
    }
    val finalVaeExecutionCount = exactLong("finalVaeExecutionCount")
    val finalVaeGraphExecutionCount = exactLong("finalVaeGraphExecutionCount")
    val vaeExecutionCount = exactLong("vaeExecutionCount")
    val vaeTileCount = exactLong("vaeTileCount")
    val vaeTiled = nativeResult.opt("vaeTiled") as? Boolean
        ?: error("Native QNN vaeTiled evidence must be boolean.")
    require(finalVaeExecutionCount == 1L) {
        "Final QNN VAE evidence must describe exactly one independent logical decode."
    }
    require(finalVaeGraphExecutionCount >= 1L &&
        vaeExecutionCount == finalVaeGraphExecutionCount &&
        vaeTileCount == finalVaeGraphExecutionCount
    ) {
        "Final QNN VAE physical graph execution evidence is inconsistent."
    }
    if (vaeTiled) {
        require(vaeTileCount > 1L) {
            "A tiled final QNN VAE decode must execute more than one concrete tile graph."
        }
    } else {
        require(vaeTileCount == 1L) {
            "A direct final QNN VAE decode must execute exactly one graph."
        }
    }

    val previewRequested = nativeResult.opt("previewRequested") as? Boolean
        ?: error("Native QNN previewRequested evidence must be boolean.")
    require(previewRequested == (requestedPreview != null)) {
        "Native QNN preview request evidence differs from the Android request."
    }
    val expectedMode = if (requestedPreview == null) "none" else "vae"
    val expectedInterval = requestedPreview?.interval?.toLong() ?: 0L
    require(requestedPreview == null || expectedInterval in 1L..10L) {
        "Shared QNN preview interval must be in [1, 10]."
    }
    require(nativeResult.opt("previewMode") is String &&
        nativeResult.getString("previewMode") == expectedMode &&
        exactLong("previewInterval") == expectedInterval
    ) { "Native QNN preview mode or interval differs from the Android request." }

    val attemptCount = exactLong("previewVaeExecutionAttemptCount")
    val executionCount = exactLong("previewVaeExecutionCount")
    val executionMsTotal = exactLong("previewVaeExecutionMsTotal")
    val publicationCount = exactLong("previewPublicationCount")
    val lastStep = exactLong("previewLastStep")
    val lastRevision = exactLong("previewLastRevision")
    require(attemptCount >= executionCount && executionCount >= publicationCount &&
        executionMsTotal >= 0L && publicationCount >= 0L &&
        lastRevision == publicationCount
    ) { "Native QNN preview counters are inconsistent." }
    val fullSteps = exactLong("steps")
    require(fullSteps > 0L) { "Native QNN preview evidence requires positive scheduler steps." }
    val previewTotalSteps = if (nativeResult.optString("taskMode") in setOf(
            LocalImageTaskMode.IMG2IMG.wireName,
            LocalImageTaskMode.INPAINT.wireName
        )
    ) {
        val effectiveSteps = exactLong("effectiveDenoiseSteps")
        require(effectiveSteps == exactLong("timetableCount") &&
            effectiveSteps in 1L..fullSteps
        ) { "Native QNN image-input preview evidence has an invalid effective scheduler tail." }
        effectiveSteps
    } else {
        fullSteps
    }
    if (publicationCount == 0L) {
        require(lastStep == 0L && lastRevision == 0L) {
            "A pathless QNN preview audit cannot claim a last frame."
        }
    } else {
        require(lastStep in 1 until previewTotalSteps && lastRevision > 0L &&
            expectedInterval > 0L && lastStep % expectedInterval == 0L &&
            lastStep == publicationCount * expectedInterval
        ) {
            "Native QNN preview last-step evidence is invalid or duplicates the final decode."
        }
    }
    val failureCode = nativeResult.opt("previewFailureCode") as? String
        ?: error("Native QNN previewFailureCode evidence must be a string.")
    val knownFailureCodes = setOf(
        "PREVIEW_STORAGE_INVALID",
        "PREVIEW_VAE_INPUT_BIND_FAILED",
        "PREVIEW_VAE_EXECUTE_FAILED",
        "PREVIEW_VAE_OUTPUT_READ_FAILED",
        "PREVIEW_REVISION_INVALID",
        "PREVIEW_PNG_WRITE_FAILED",
        "PREVIEW_PNG_INVALID",
        "PREVIEW_ATOMIC_RENAME_FAILED",
        "PREVIEW_DIRECTORY_FSYNC_FAILED",
        "PREVIEW_JOURNAL_COMMIT_FAILED"
    )
    require(failureCode.length <= 128 &&
        failureCode.all { it.isUpperCase() || it.isDigit() || it == '_' } &&
        (failureCode.isEmpty() || failureCode in knownFailureCodes)
    ) { "Native QNN preview failure code is invalid." }
    val previewDegraded = nativeResult.opt("previewDegraded") as? Boolean
        ?: error("Native QNN previewDegraded evidence must be boolean.")
    require(previewDegraded == failureCode.isNotEmpty()) {
        "Native QNN preview degradation evidence conflicts with previewFailureCode."
    }
    val expectedEligiblePreviewCount = if (requestedPreview == null) {
        0L
    } else {
        (previewTotalSteps - 1L) / expectedInterval
    }
    require(publicationCount <= expectedEligiblePreviewCount) {
        "Native QNN preview published more frames than its interval schedule allows."
    }
    if (vaeTiled && requestedPreview != null && expectedEligiblePreviewCount > 0L &&
        failureCode != "PREVIEW_STORAGE_INVALID" &&
        failureCode != "PREVIEW_JOURNAL_COMMIT_FAILED"
    ) {
        require(failureCode == "PREVIEW_VAE_INPUT_BIND_FAILED" &&
            attemptCount == 1L && executionCount == 0L && publicationCount == 0L
        ) {
            "The current shared QNN publisher cannot directly bind full latents to a tiled VAE graph."
        }
    }
    if (requestedPreview != null && failureCode.isEmpty()) {
        require(publicationCount == expectedEligiblePreviewCount &&
            attemptCount == publicationCount && executionCount == publicationCount
        ) { "Native QNN preview evidence does not match the deterministic eligible-step schedule." }
    } else if (failureCode == "PREVIEW_STORAGE_INVALID" ||
        (failureCode == "PREVIEW_JOURNAL_COMMIT_FAILED" &&
            attemptCount == 0L && executionCount == 0L && publicationCount == 0L)
    ) {
        require(attemptCount == 0L && executionCount == 0L && publicationCount == 0L) {
            "QNN preview initialization failure cannot claim a VAE attempt or publication."
        }
    } else if (failureCode.isNotEmpty()) {
        require(attemptCount == publicationCount + 1L &&
            executionCount in publicationCount..attemptCount
        ) { "QNN preview failure counters do not describe exactly one stopped attempt." }
    }
    if (requestedPreview == null) {
        require(
            attemptCount == 0L && executionCount == 0L && executionMsTotal == 0L &&
                publicationCount == 0L && lastStep == 0L && lastRevision == 0L &&
                failureCode.isEmpty() && !previewDegraded
        ) { "A QNN request without preview carried preview execution evidence." }
    }
    return JSONObject()
        .put("finalVaeExecutionCount", finalVaeExecutionCount)
        .put("finalVaeGraphExecutionCount", finalVaeGraphExecutionCount)
        .put("vaeExecutionCount", vaeExecutionCount)
        .put("vaeTileCount", vaeTileCount)
        .put("vaeTiled", vaeTiled)
        .put("previewRequested", previewRequested)
        .put("previewMode", expectedMode)
        .put("previewInterval", expectedInterval)
        .put("previewVaeExecutionAttemptCount", attemptCount)
        .put("previewVaeExecutionCount", executionCount)
        .put("previewVaeExecutionMsTotal", executionMsTotal)
        .put("previewPublicationCount", publicationCount)
        .put("previewLastStep", lastStep)
        .put("previewLastRevision", lastRevision)
        .put("previewFailureCode", failureCode)
        .put("previewDegraded", previewDegraded)
}

internal fun qnnSdxlProjectionPreviewExecutionAudit(
    nativeResult: JSONObject,
    requestedPreview: LocalImagePreviewOptions?
): JSONObject {
    require(requestedPreview == null) {
        "Split-SDXL uses isolated workers and does not support live preview."
    }
    fun exactLong(field: String): Long {
        val number = nativeResult.opt(field) as? Number
            ?: error("Split-SDXL preview evidence field $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "Split-SDXL preview evidence field $field must be an exact integer."
        }
        return value
    }

    val requested = nativeResult.opt("previewRequested") as? Boolean
        ?: error("Split-SDXL previewRequested evidence must be boolean.")
    require(!requested) { "Split-SDXL result claimed a forbidden live-preview request." }
    require(nativeResult.opt("previewMode") is String &&
        nativeResult.getString("previewMode") == "none" &&
        exactLong("previewInterval") == 0L
    ) { "Split-SDXL preview mode or interval must be disabled." }

    val finalVaeExecutionCount = exactLong("finalVaeExecutionCount")
    val finalVaeGraphExecutionCount = exactLong("finalVaeGraphExecutionCount")
    val vaeExecutionCount = exactLong("vaeExecutionCount")
    require(finalVaeExecutionCount == 1L && finalVaeGraphExecutionCount >= 1L &&
        vaeExecutionCount == finalVaeGraphExecutionCount
    ) {
        "Split-SDXL preview counters cannot replace the one final VAE decode."
    }
    val degraded = nativeResult.opt("previewDegraded") as? Boolean
        ?: error("Split-SDXL previewDegraded evidence must be boolean.")
    require(!degraded) { "Split-SDXL result reported a forbidden preview degradation." }
    listOf(
        "previewVaeExecutionAttemptCount",
        "previewVaeExecutionCount",
        "previewVaeExecutionMsTotal",
        "previewPublicationCount",
        "previewLastStep",
        "previewLastRevision",
        "projectionPreviewAttemptCount",
        "projectionPreviewPublicationCount",
        "projectionPreviewProjectionMsTotal",
        "projectionPreviewLastStep",
        "projectionPreviewLastRevision"
    ).forEach { field ->
        require(exactLong(field) == 0L) {
            "Split-SDXL result carried forbidden preview evidence in $field."
        }
    }
    // These fields are optional on legacy isolated-worker result wires, but any
    // occurrence would still be live-preview publication evidence and must stay disabled.
    listOf(
        "previewStep",
        "previewRevision",
        "previewWidth",
        "previewHeight",
        "previewFrameCount"
    ).forEach { field ->
        if (nativeResult.has(field)) {
            require(exactLong(field) == 0L) {
                "Split-SDXL result carried forbidden preview frame evidence in $field."
            }
        }
    }
    require(nativeResult.opt("previewFailureCode") == "" &&
        nativeResult.opt("projectionPreviewFailureCode") == "" &&
        (!nativeResult.has("previewPath") || nativeResult.opt("previewPath") == "") &&
        (!nativeResult.has("previewMimeType") || nativeResult.opt("previewMimeType") == "") &&
        (!nativeResult.has("previewNoisy") || nativeResult.opt("previewNoisy") == false)
    ) { "Split-SDXL result exposed forbidden transient preview evidence." }

    val zero = 0L
    val empty = ""
    val disabledMode = "none"
    val disabled = false
    return JSONObject()
        .put("finalVaeExecutionCount", finalVaeExecutionCount)
        .put("finalVaeGraphExecutionCount", finalVaeGraphExecutionCount)
        .put("vaeExecutionCount", vaeExecutionCount)
        .put("previewRequested", disabled)
        .put("previewMode", disabledMode)
        .put("previewInterval", zero)
        .put("previewVaeExecutionAttemptCount", zero)
        .put("previewVaeExecutionCount", zero)
        .put("previewVaeExecutionMsTotal", zero)
        .put("previewPublicationCount", zero)
        .put("previewLastStep", zero)
        .put("previewLastRevision", zero)
        .put("previewFailureCode", empty)
        .put("projectionPreviewAttemptCount", zero)
        .put("projectionPreviewPublicationCount", zero)
        .put("projectionPreviewProjectionMsTotal", zero)
        .put("projectionPreviewLastStep", zero)
        .put("projectionPreviewLastRevision", zero)
        .put("projectionPreviewFailureCode", empty)
        .put("previewDegraded", disabled)
}

internal fun qnnPreviewExecutionAuditForRuntime(
    nativeResult: JSONObject,
    requestedPreview: LocalImagePreviewOptions?
): JSONObject? {
    val sharedSessionModes = setOf(
        "shared_unet_vae",
        "shared_text_unet_vae",
        "shared_unet_controlnet_vae",
        "shared_text_unet_controlnet_vae"
    )
    return when (nativeResult.optString("runtimeSessionMode")) {
        in sharedSessionModes -> qnnSharedPreviewExecutionAudit(nativeResult, requestedPreview)
        SDXL_ISOLATED_UNET_VAE_MODE,
        SDXL_ISOLATED_ENCODER_UNET_VAE_MODE,
        SDXL_ISOLATED_ULTRAFIX_MODE ->
            qnnSdxlProjectionPreviewExecutionAudit(nativeResult, requestedPreview)
        else -> {
            require(requestedPreview == null) {
                "Unknown QNN execution cannot carry a live preview request."
            }
            require(!nativeResult.toString().contains("\"previewPath\"")) {
                "Final QNN execution metadata must not expose a transient preview path."
            }
            null
        }
    }
}

internal fun qnnImageExecutionMetadata(
    nativeRequestId: String,
    nativeResult: JSONObject,
    outputBytes: Long,
    inputExecutionAudit: JSONObject? = null,
    requestedPreview: LocalImagePreviewOptions? = null
): JSONObject = JSONObject().also { metadata ->
    val nativeDetailStageMask =
        nativeResult.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD)
    metadata.put(
        QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD,
        nativeDetailStageMask.toFixedUInt64Hex()
    )
    ImageExecutionProfileNativeContract.requiredFields.forEach { field ->
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "Native QNN execution metadata is missing required field: $field"
        }
        metadata.put(field, nativeResult.get(field))
    }
    val nativeEffective = requireNotNull(nativeResult.optJSONObject("nativeEffective")) {
        "Native QNN execution metadata is missing nativeEffective."
    }
    if (nativeEffective.has(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ||
        nativeEffective.has("nativeDetailStageMask")
    ) {
        require(
            nativeEffective.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ==
                nativeDetailStageMask
        ) { "Native QNN detail stage mask conflicts with nativeEffective." }
    }
    listOf("encoder", "unet", "vae").forEach { phase ->
        val prefix = phase.replaceFirstChar(Char::uppercaseChar)
        val hexField = "${phase}NativeDetailStageMaskHex"
        val legacyField = "${phase}NativeDetailStageMask"
        if (nativeResult.has(hexField) || nativeResult.has(legacyField)) {
            val value = nativeResult.strictUInt64Hex(hexField, legacyField)
            metadata.put(hexField, value.toFixedUInt64Hex())
            nativeEffective.optJSONObject("sdxlPhaseProof")?.let { proof ->
                if (proof.has(hexField) || proof.has(legacyField)) {
                    require(proof.strictUInt64Hex(hexField, legacyField) == value) {
                        "Native QNN $prefix detail stage mask conflicts with the phase proof."
                    }
                }
            }
        }
    }
    ImageExecutionProfileNativeContract.qnnNativeEffectiveFields.forEach { field ->
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "Native QNN execution metadata is missing required field: $field"
        }
        require(nativeEffective.has(field) && !nativeEffective.isNull(field)) {
            "Native QNN nativeEffective metadata is missing required field: $field"
        }
        require(nativeResult.get(field) == nativeEffective.get(field)) {
            "Native QNN $field evidence conflicts with nativeEffective."
        }
        metadata.put(field, nativeResult.get(field))
    }
    val pixelRange = ImagePixelRange.entries.firstOrNull {
        it.name == nativeResult.getString("pixelRange")
    } ?: error("Native QNN execution reported an unknown pixelRange.")
    require(pixelRange != ImagePixelRange.RUNTIME_NATIVE) {
        "Native QNN execution must report an explicit pixelRange."
    }
    val expectedConversion =
        ImageExecutionProfileNativeContract.qnnPixelRangeConversionName(pixelRange)
    require(nativeResult.getString("pixelRangeConversion") == expectedConversion) {
        "Native QNN pixel-range conversion evidence does not match pixelRange."
    }
    fun requiredExactLong(field: String): Long {
        val number = nativeResult.opt(field) as? Number
            ?: error("Native QNN execution metadata field $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "Native QNN execution metadata field $field must be an exact integer."
        }
        return value
    }
    qnnPreviewExecutionAuditForRuntime(nativeResult, requestedPreview)?.let { previewAudit ->
        previewAudit.keys().forEach { field -> metadata.put(field, previewAudit.get(field)) }
    }
    val valueCount = requiredExactLong("pixelRangeValueCount")
    val clampedValueCount = requiredExactLong("pixelRangeClampedValueCount")
    val expectedValueCount = Math.multiplyExact(
        Math.multiplyExact(nativeResult.getLong("width"), nativeResult.getLong("height")),
        3L
    )
    require(valueCount == expectedValueCount) {
        "Native QNN pixel-range value count does not match the generated RGB image."
    }
    require(clampedValueCount in 0L..valueCount) {
        "Native QNN pixel-range clamp count is invalid."
    }
    val observedMin = (nativeResult.opt("pixelRangeObservedMin") as? Number)?.toDouble()
        ?: error("Native QNN pixelRangeObservedMin evidence must be numeric.")
    val observedMax = (nativeResult.opt("pixelRangeObservedMax") as? Number)?.toDouble()
        ?: error("Native QNN pixelRangeObservedMax evidence must be numeric.")
    require(observedMin.isFinite() && observedMax.isFinite() && observedMin <= observedMax) {
        "Native QNN observed pixel range is invalid."
    }
    ImageExecutionProfileNativeContract.qnnPixelRangeEvidenceFields.forEach { field ->
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "Native QNN execution metadata is missing pixel-range evidence: $field"
        }
        metadata.put(field, nativeResult.get(field))
    }
    metadata.put("nativeEffective", nativeEffective)
    nativeResult.optJSONArray("timesteps")?.let { metadata.put("timesteps", it) }
    nativeResult.optJSONArray("sigmas")?.let { metadata.put("sigmas", it) }
    inputExecutionAudit?.let { metadata.put("imageInput", it) }
    listOf(
        "taskMode",
        "batchCount",
        "inputImageExecutionCount",
        "maskImageExecutionCount",
        "controlImageExecutionCount",
        "inputImageSha256",
        "inputImageTensorSha256",
        "inputImagePreprocess",
        "inputImageSourceWidth",
        "inputImageSourceHeight",
        "inputImageOrientedWidth",
        "inputImageOrientedHeight",
        "inputImageExifOrientation",
        "inputImageTensorWidth",
        "inputImageTensorHeight",
        "inputImageTensorChannels",
        "inputImageTensorBytes",
        "inputImageTensorShape",
        "inputImageTensorDtype",
        "inputImageTensorLayout",
        "inputImageTensorRange",
        "strength",
        "fullTimetableCount",
        "effectiveDenoiseSteps",
        "img2imgBeginIndex",
        "encoderLatentSha256",
        "encoderContextSha256",
        "encoderContextLoadCount",
        "encoderExecutionCount",
        "encoderGraphName",
        "encoderInputName",
        "encoderMeanOutputName",
        "encoderStdOutputName",
        "encoderInputDtype",
        "encoderMeanDtype",
        "encoderStdDtype",
        "encoderInputShape",
        "encoderMeanShape",
        "encoderStdShape",
        "encoderInputBufferSha256",
        "encoderMeanBufferSha256",
        "encoderStdBufferSha256",
        "posteriorSampling",
        "posteriorSampleCount",
        "encoderLatentScalingFactor",
        "encoderContextReleasedBeforeSharedSession",
        "encoderRuntimeMode",
        "img2imgAddNoiseApplied",
        "img2imgAddNoiseBeginIndex",
        "img2imgAddNoiseTimestep",
        "img2imgNoiseChecksum",
        "controlImageSha256",
        "controlImagePreprocessedSha256",
        "controlImagePreprocess",
        "controlImageSourceWidth",
        "controlImageSourceHeight",
        "controlImageSourceChannels",
        "controlImageOrientedWidth",
        "controlImageOrientedHeight",
        "controlImageExifOrientation",
        "controlImageTensorWidth",
        "controlImageTensorHeight",
        "controlImageTensorChannels",
        "controlImageTensorLayout",
        "controlImageEdgePixelCount",
        "controlImageTensorChecksum",
        "controlStrength",
        "controlStrengthApplied",
        "controlNetExecutionCount",
        "controlNetResidualTensorCount",
        "controlNetResidualWriteCount",
        "controlNetResidualUnetReuseCount",
        "controlNetConditioningBranch",
        "controlNetInputConsumed",
        "controlNetInputBufferSha256",
        "controlNetScaledResidualChecksum",
        "controlNetGraph",
        "conditioningArtifactSha256"
    ).forEach { field ->
        if (nativeResult.has(field) && !nativeResult.isNull(field)) {
            metadata.put(field, nativeResult.get(field))
        }
    }
    listOf(
        "initNoiseSigma",
        "scaleModelInput",
        "textEncoderExecutionCount",
        "vaeExecutionCount",
        "effectiveVaeHostScale",
        "vaeTileCount",
        "vaeTiled",
        "outputSha256",
        "outputSizeBytes",
        "outputAtomicCommit",
        "ultraFix",
        "strengthMechanism",
        "sampleMethod",
        "nativeScheduler",
        "actualDiffusionModelComputeCount",
        "actualPositiveDiffusionModelComputeCount",
        "actualNegativeDiffusionModelComputeCount",
        "actualAuxiliaryDiffusionModelComputeCount",
        "actualSamplingStepCount",
        "actualSamplingPassCount",
        "totalUnetExecutionCount"
    ).forEach { field ->
        if (nativeResult.has(field) && !nativeResult.isNull(field)) {
            metadata.put(field, nativeResult.get(field))
        }
    }
}.apply {
    put("nativeRequestId", nativeRequestId)
    .put("backend", nativeResult.optString("backend"))
    .put("executionStage", nativeResult.optString("executionStage"))
    .put("npuActive", nativeResult.optBoolean("npuActive", false))
    .put("qnnGraphExecution", nativeResult.optBoolean("qnnGraphExecution", false))
    .put("nativeExecution", nativeResult.optBoolean("nativeExecution", false))
    .put("fallback", nativeResult.optBoolean("fallback", true))
    .put("nativeGenerationSequence", nativeResult.optLong("nativeGenerationSequence"))
    .put("nativeStartedAtMonotonicMs", nativeResult.optLong("nativeStartedAtMonotonicMs"))
    .put("nativeStageMask", nativeResult.optLong("nativeStageMask"))
    .put("runtimeSessionMode", nativeResult.optString("runtimeSessionMode"))
    .put("conditioningFormat", nativeResult.optString("conditioningFormat"))
    .put("transportHtpArch", nativeResult.optInt("transportHtpArch"))
    .put("encoderWorkerPid", nativeResult.optInt("encoderWorkerPid"))
    .put("encoderRuntimeProfile", nativeResult.optString("encoderRuntimeProfile"))
    .put("encoderTransportHtpArch", nativeResult.optInt("encoderTransportHtpArch"))
    .put("encoderProcessDeathConfirmed", nativeResult.optBoolean("encoderProcessDeathConfirmed", false))
    .put("encoderGraph", nativeResult.optString("encoderGraph"))
    .put("unetWorkerPid", nativeResult.optInt("unetWorkerPid"))
    .put("unetRuntimeProfile", nativeResult.optString("unetRuntimeProfile"))
    .put("unetTransportHtpArch", nativeResult.optInt("unetTransportHtpArch"))
    .put("unetProcessDeathConfirmed", nativeResult.optBoolean("unetProcessDeathConfirmed", false))
    .put("unetGraph", nativeResult.optString("unetGraph"))
    .put("vaeWorkerPid", nativeResult.optInt("vaeWorkerPid"))
    .put("vaeRuntimeProfile", nativeResult.optString("vaeRuntimeProfile"))
    .put("vaeTransportHtpArch", nativeResult.optInt("vaeTransportHtpArch"))
    .put("vaeProcessDeathConfirmed", nativeResult.optBoolean("vaeProcessDeathConfirmed", false))
    .put("vaeGraph", nativeResult.optString("vaeGraph"))
    .put("steps", nativeResult.optInt("steps"))
    .put("width", nativeResult.optInt("width"))
    .put("height", nativeResult.optInt("height"))
    .put("elapsedMs", nativeResult.optLong("elapsedMs"))
    .put("unetContextLoadMs", nativeResult.optLong("unetContextLoadMs"))
    .put("unetExecuteMsTotal", nativeResult.optLong("unetExecuteMsTotal"))
    .put("unetExecuteMsAvg", nativeResult.optLong("unetExecuteMsAvg"))
    .put("vaeContextLoadMs", nativeResult.optLong("vaeContextLoadMs"))
    .put("vaeExecuteMs", nativeResult.optLong("vaeExecuteMs"))
    .put("textEncoderGraph", nativeResult.optString("textEncoderGraph"))
    .put("textEncoderContextLoadMs", nativeResult.optLong("textEncoderContextLoadMs"))
    .put("textEncoderExecuteMsTotal", nativeResult.optLong("textEncoderExecuteMsTotal"))
    .put("textEncoderEmbeddingWidth", nativeResult.optLong("textEncoderEmbeddingWidth"))
    .put("latentSha256", nativeResult.optString("latentSha256"))
    .put("outputBytes", outputBytes)
    .also { metadata ->
        nativeResult.optJSONObject("runtime")?.let { runtime ->
            metadata
                .put("selectedHtpArch", runtime.optInt("htpArchVersion"))
                .put("runtimeLoadable", runtime.optBoolean("loadable", false))
                .put("qnnInterfacePresent", runtime.optBoolean("qnnInterfacePresent", false))
            runtime.optJSONObject("compile")?.let { compile ->
                metadata
                    .put("sdkHeadersPresent", compile.optBoolean("sdkHeadersPresent", false))
                    .put("typedGraphBindingsCompiled", compile.optBoolean("typedGraphBindingsCompiled", false))
            }
        }
    }
}

internal fun resolveQnnExecutedNegativePrompt(
    useCfg: Boolean,
    effectiveNegativePrompt: String
): String {
    if (!useCfg && effectiveNegativePrompt.isNotEmpty()) {
        throw LocalImageProductContractException(
            code = "execution_contract_unsupported",
            message = "A negativePrompt cannot affect pixels when useCfg=false."
        )
    }
    return effectiveNegativePrompt
}

internal data class QnnImageGenerationContract(
    val width: Int,
    val height: Int,
    val steps: Int,
    val threads: Int,
    val seed: Int,
    val cfgScale: Double,
    val sampleMethod: String,
    val backendMode: String,
    val tokenEmbeddingMode: String,
    val memoryMode: Int,
    val useCfg: Boolean
) {
    val auditJson: JSONObject
        get() = JSONObject()
            .put("width", width)
            .put("height", height)
            .put("steps", steps)
            .put("threads", threads)
            .put("seed", seed)
            .put("cfgScale", cfgScale)
            .put("sampleMethod", sampleMethod)
            .put("backendMode", backendMode)
            .put("tokenEmbeddingMode", tokenEmbeddingMode)
            .put("memoryMode", memoryMode)
            .put("useCfg", useCfg)
}

internal fun resolveQnnImageGenerationContract(
    resolution: ImageExecutionProfileResolution,
    defaultThreads: Int,
    options: LocalImageGenerationOptions
): QnnImageGenerationContract {
    require(options.distilledGuidance == null) {
        "QNN image graphs do not expose a distilled-guidance input."
    }
    require(options.flowShift == null) {
        "QNN image schedulers do not expose a writable flow-shift control."
    }
    val resolved = resolution.layers.resolved
    val width = resolved.width
    val height = resolved.height
    require(width > 0 && height > 0 && width % 8 == 0 && height % 8 == 0) {
        "Resolved QNN image dimensions must be positive multiples of 8."
    }
    val steps = resolved.steps
    require(steps in resolution.profile.scheduler.minSteps..resolution.profile.scheduler.maxSteps) {
        "Resolved QNN steps are outside the execution profile bounds."
    }
    val threads = options.threads ?: defaultThreads
    require(threads in 1..16) { "QNN prompt encoder threads 必须在 1..16。" }
    val cfgScale = resolved.cfgScale
    require(cfgScale.isFinite() && cfgScale in 0.0..30.0) { "QNN CFG 必须是 0..30 的有限数值。" }
    val backendMode = options.backendMode?.trim()?.lowercase().orEmpty().ifBlank { "cpu" }
    require(backendMode == "cpu" || backendMode == "opencl") {
        "QNN prompt encoder backend 只支持 cpu 或 opencl。"
    }
    val tokenEmbeddingMode = options.tokenEmbeddingMode?.trim()?.lowercase().orEmpty().ifBlank { "auto" }
    require(tokenEmbeddingMode in setOf("auto", "module", "direct")) {
        "QNN token embedding mode 只支持 auto、module 或 direct。"
    }
    val memoryMode = options.memoryMode ?: 0
    require(memoryMode in 0..2) { "QNN memory mode 必须在 0..2。" }
    return QnnImageGenerationContract(
        width = width,
        height = height,
        steps = steps,
        threads = threads,
        seed = resolved.seed.toInt().also {
            require(resolved.seed in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "QNN seed must fit the native 32-bit RNG contract."
            }
        },
        cfgScale = cfgScale,
        sampleMethod = imageSchedulerProductName(resolved.scheduler),
        backendMode = backendMode,
        tokenEmbeddingMode = tokenEmbeddingMode,
        memoryMode = memoryMode,
        useCfg = resolved.useCfg
    )
}

internal fun resolveQnnImageGenerationContract(
    family: LocalImageModelFamily,
    defaultWidth: Int,
    defaultHeight: Int,
    defaultThreads: Int,
    fallbackSeed: Int,
    options: LocalImageGenerationOptions
): QnnImageGenerationContract {
    require(options.distilledGuidance == null) {
        "QNN image graphs do not expose a distilled-guidance input."
    }
    require(options.flowShift == null) {
        "QNN image schedulers do not expose a writable flow-shift control."
    }
    val width = options.width ?: defaultWidth
    val height = options.height ?: defaultHeight
    require(width == defaultWidth && height == defaultHeight) {
        "QNN context 固定为 ${defaultWidth}x${defaultHeight}，不能执行 ${width}x${height}。"
    }
    val defaultSteps = if (family == LocalImageModelFamily.SDXL) 30 else defaultStepsFor(family).coerceIn(1, 100)
    val steps = options.steps ?: defaultSteps
    require(steps in 1..100) { "QNN steps 必须在 1..100。" }
    val threads = options.threads ?: defaultThreads
    require(threads in 1..16) { "QNN prompt encoder threads 必须在 1..16。" }
    val cfgScale = options.cfgScale ?: defaultCfgFor(family)
    require(cfgScale.isFinite() && cfgScale in 0.0..30.0) { "QNN CFG 必须是 0..30 的有限数值。" }
    val sampleMethod = options.sampleMethod?.trim()?.lowercase().orEmpty().ifBlank { "pndm" }
    imageSchedulerAlgorithmFromProductName(sampleMethod)
    val backendMode = options.backendMode?.trim()?.lowercase().orEmpty().ifBlank { "cpu" }
    require(backendMode == "cpu" || backendMode == "opencl") {
        "QNN prompt encoder backend 只支持 cpu 或 opencl。"
    }
    val tokenEmbeddingMode = options.tokenEmbeddingMode?.trim()?.lowercase().orEmpty().ifBlank { "auto" }
    require(tokenEmbeddingMode in setOf("auto", "module", "direct")) {
        "QNN token embedding mode 只支持 auto、module 或 direct。"
    }
    val memoryMode = options.memoryMode ?: 0
    require(memoryMode in 0..2) { "QNN memory mode 必须在 0..2。" }
    return QnnImageGenerationContract(
        width = width,
        height = height,
        steps = steps,
        threads = threads,
        seed = options.seed ?: fallbackSeed,
        cfgScale = cfgScale,
        sampleMethod = sampleMethod,
        backendMode = backendMode,
        tokenEmbeddingMode = tokenEmbeddingMode,
        memoryMode = memoryMode,
        useCfg = options.useCfg ?: true
    )
}

class LocalImageModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("mca_local_image_models", Context.MODE_PRIVATE)
    private val managedDir: File by lazy {
        (appContext.getExternalFilesDir("image_models") ?: File(appContext.filesDir, "image_models")).also { it.mkdirs() }
    }

    fun loadModels(): List<LocalImageModelRecord> {
        val raw = prefs.getString(KEY_MODELS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                LocalImageModelRecord.fromJson(array.getJSONObject(index))
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun saveModels(models: List<LocalImageModelRecord>) {
        val array = JSONArray()
        models.sortedByDescending { it.updatedAt }.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_MODELS, array.toString()).apply()
    }

    fun updateModel(record: LocalImageModelRecord): List<LocalImageModelRecord> {
        val now = System.currentTimeMillis()
        val models = loadModels().map { existing ->
            if (existing.id == record.id) record.copy(updatedAt = now) else existing
        }
        saveModels(models)
        return models
    }

    fun importFromUri(uri: Uri): LocalImageModelRecord {
        val fileName = queryDisplayName(uri) ?: "image-model.task"
        val extension = fileName.substringAfterLast('.', "").lowercase()
        require(extension in SUPPORTED_EXTENSIONS) {
            "请选择 .gguf、.safetensors、.ckpt、.pth、.pt、.onnx，或包含 diffusion 主模型、VAE/AE、文本编码器/LLM 的 .zip 图像生成引擎包。"
        }
        if (extension == "zip") {
            return importBundleFromUri(uri, fileName)
        }
        managedDir.mkdirs()
        val target = uniqueTarget(fileName)
        inputStreamFor(uri).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val record = LocalImageModelRecord(
            displayName = fileName.substringBeforeLast('.', fileName),
            path = target.absolutePath,
            fileName = target.name,
            sizeBytes = target.length(),
            sha256 = sha256(target),
            runtime = LocalImageRuntime.infer(fileName),
            family = LocalImageModelFamily.infer(fileName),
            imageSize = defaultImageSizeFor(fileName)
        )
        saveModels(listOf(record) + loadModels().filterNot { it.id == record.id })
        if (loadSelectedModelId() == null && record.isReadyForLocalImageGeneration()) saveSelectedModelId(record.id)
        return record
    }

    fun registerDownloadedModel(
        file: File,
        remote: RemoteModelFile
    ): LocalImageModelRecord {
        require(file.exists()) { "下载完成的图像模型文件不存在：${file.absolutePath}" }
        if (file.extension.equals("zip", ignoreCase = true)) {
            return importBundleFromUri(Uri.fromFile(file), file.name).also {
                runCatching { file.delete() }
            }
        }
        val record = LocalImageModelRecord(
            displayName = file.name.substringBeforeLast('.', file.name),
            path = file.absolutePath,
            fileName = file.name,
            sizeBytes = file.length(),
            sha256 = sha256(file),
            runtime = LocalImageRuntime.infer(file.name),
            family = LocalImageModelFamily.infer("${remote.repoId}/${remote.path}/${file.name}"),
            imageSize = defaultImageSizeFor("${remote.repoId}/${file.name}"),
            source = "${remote.provider.name.lowercase()}:${remote.repoId}",
            updatedAt = System.currentTimeMillis()
        )
        saveModels(listOf(record) + loadModels().filterNot { it.sha256.equals(record.sha256, ignoreCase = true) })
        if (loadSelectedModelId() == null && record.isReadyForLocalImageGeneration()) saveSelectedModelId(record.id)
        return record
    }

    fun managedBundleDirFor(bundleId: String): File {
        val safeName = bundleId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image-engine" }
        return File(managedDir, "bundle-$safeName").also { it.mkdirs() }
    }

    fun managedBundleFileFor(bundleDir: File, fileName: String): File {
        val safeName = fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(bundleDir, safeName.ifBlank { "component-${System.currentTimeMillis()}" })
    }

    fun registerDownloadedBundle(
        displayName: String,
        bundleDir: File,
        primaryFile: File,
        primaryRemote: RemoteModelFile,
        componentCount: Int,
        runtimeOverride: LocalImageRuntime? = null,
        imageSizeOverride: String? = null,
        primarySha256: String? = null
    ): LocalImageModelRecord {
        require(bundleDir.isDirectory) { "本地生图引擎包目录不存在：${bundleDir.absolutePath}" }
        require(primaryFile.exists()) { "Local image engine bundle is missing a diffusion model: ${primaryFile.name}" }
        if (primaryFile.extension.equals("zip", ignoreCase = true)) {
            extractImageBundleZipIntoDirectory(primaryFile, bundleDir)
            runCatching { primaryFile.delete() }
        }
        prepareMnnDiffusionTokenizerIfPossible(bundleDir)
        val manifest = localImageBundleManifestFromRoot(bundleDir)
        val resolvedPrimary = manifest?.primaryFile ?: findPrimaryImageModel(bundleDir)
            ?: error("Local image engine bundle is missing a diffusion model.")
        val familyHint = "$displayName/${primaryRemote.repoId}/${primaryRemote.path}/${resolvedPrimary.name}"
        val record = LocalImageModelRecord(
            displayName = displayName,
            path = resolvedPrimary.absolutePath,
            fileName = resolvedPrimary.name,
            sizeBytes = bundleDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            sha256 = primarySha256
                ?.trim()
                ?.lowercase()
                ?.also { digest ->
                    require(digest.matches(Regex("^[0-9a-f]{64}$"))) {
                        "Downloaded image model SHA-256 is invalid."
                    }
                }
                ?: sha256(resolvedPrimary),
            runtime = runtimeOverride ?: manifest?.runtime ?: inferLocalImageRuntimeForBundle(bundleDir, resolvedPrimary),
            family = manifest?.family ?: LocalImageModelFamily.infer(familyHint),
            imageSize = imageSizeOverride ?: manifest?.imageSize ?: defaultImageSizeFor(familyHint),
            source = "${primaryRemote.provider.name.lowercase()}:${primaryRemote.repoId}",
            bundleRoot = bundleDir.absolutePath,
            componentCount = bundleDir.walkTopDown().count { it.isFile }
                .coerceAtLeast(componentCount)
                .coerceAtLeast(manifest?.componentCount ?: 0)
                .coerceAtLeast(1),
            updatedAt = System.currentTimeMillis()
        )
        record.localImageStructuralReadinessMessage()?.let { readiness ->
            error("图像生成引擎包不完整：$readiness")
        }
        saveModels(
            listOf(record) + loadModels().filterNot {
                it.bundleRoot == record.bundleRoot ||
                    (it.sha256.equals(record.sha256, ignoreCase = true) && it.imageSize == record.imageSize)
            }
        )
        if (record.isReadyForLocalImageGeneration()) {
            saveSelectedModelId(record.id)
            saveSelectedBackend(ImageBackend.LOCAL)
        }
        return record
    }

    fun managedFileFor(fileName: String): File {
        managedDir.mkdirs()
        return uniqueTarget(fileName)
    }

    fun deleteModel(id: String): Boolean {
        val models = loadModels()
        val target = models.firstOrNull { it.id == id } ?: return false
        val targetFile = target.bundleRoot
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(target.path)
        val deleted = runCatching {
            if (!targetFile.exists()) {
                true
            } else if (targetFile.isDirectory) {
                targetFile.deleteRecursively() && !targetFile.exists()
            } else {
                targetFile.delete() && !targetFile.exists()
            }
        }.getOrDefault(false)
        if (!deleted || targetFile.exists()) return false
        val remaining = models.filterNot { it.id == id }
        saveModels(remaining)
        if (loadSelectedModelId() == id) saveSelectedModelId(remaining.firstOrNull { it.isReadyForLocalImageGeneration() }?.id)
        return true
    }

    fun loadSelectedModelId(): String? =
        prefs.getString(KEY_SELECTED_MODEL_ID, null)?.takeIf { it.isNotBlank() }

    fun saveSelectedModelId(modelId: String?) {
        prefs.edit().putString(KEY_SELECTED_MODEL_ID, modelId.orEmpty()).apply()
    }

    fun loadSelectedBackend(): ImageBackend =
        runCatching { ImageBackend.valueOf(prefs.getString(KEY_SELECTED_BACKEND, ImageBackend.CLOUD.name).orEmpty()) }
            .getOrDefault(ImageBackend.CLOUD)

    fun saveSelectedBackend(backend: ImageBackend) {
        prefs.edit().putString(KEY_SELECTED_BACKEND, backend.name).apply()
    }

    private fun importBundleFromUri(uri: Uri, fileName: String): LocalImageModelRecord {
        val bundleDir = uniqueBundleDir(fileName.substringBeforeLast('.', fileName))
        bundleDir.mkdirs()
        try {
            val extraction = inputStreamFor(uri).use { input ->
                extractBoundedImageBundleZip(input, bundleDir)
            }
            val extracted = extraction.extractedFiles.filter {
                it.extension.lowercase() in MODEL_FILE_EXTENSIONS
            }
            prepareMnnDiffusionTokenizerIfPossible(bundleDir)
            val manifest = localImageBundleManifestFromRoot(bundleDir)
            val primary = manifest?.primaryFile ?: extracted.sortedWith(
                compareByDescending<File> { it.name.isPrimaryImageModelName() }
                    .thenByDescending { it.length() }
            ).firstOrNull() ?: run {
                bundleDir.deleteRecursively()
                error("引擎包内没有找到可识别的 GGUF / safetensors / ckpt / ONNX / MNN 模型文件。")
            }
            val family = manifest?.family
                ?: LocalImageModelFamily.infer(fileName).takeIf { it != LocalImageModelFamily.CUSTOM }
                ?: LocalImageModelFamily.infer(primary.name)
            val displayName = manifest?.displayName ?: fileName.substringBeforeLast('.', fileName)
            val record = LocalImageModelRecord(
                displayName = displayName,
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = bundleDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                sha256 = sha256(primary),
                runtime = manifest?.runtime ?: inferLocalImageRuntimeForBundle(bundleDir, primary),
                family = family,
                imageSize = manifest?.imageSize
                    ?: defaultImageSizeFor(if (family != LocalImageModelFamily.CUSTOM) family.name else primary.name),
                bundleRoot = bundleDir.absolutePath,
                componentCount = bundleDir.walkTopDown().count { it.isFile }
                    .coerceAtLeast(manifest?.componentCount ?: 0)
                    .coerceAtLeast(1)
            )
            record.localImageStructuralReadinessMessage()?.let { readiness ->
                bundleDir.deleteRecursively()
                error("图像生成引擎包不完整：$readiness")
            }
            saveModels(listOf(record) + loadModels().filterNot { it.id == record.id })
            if (
                loadSelectedModelId() == null &&
                record.isReadyForLocalImageGeneration()
            ) {
                saveSelectedModelId(record.id)
            }
            return record
        } catch (error: Throwable) {
            if (bundleDir.exists()) runCatching { bundleDir.deleteRecursively() }
            throw error
        }
    }

    private fun inputStreamFor(uri: Uri): InputStream {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: error("无法读取图像生成引擎文件。")
            return File(path).inputStream()
        }
        return requireNotNull(appContext.contentResolver.openInputStream(uri)) {
            "无法读取图像生成引擎文件。"
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun uniqueTarget(fileName: String): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val baseName = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "")
        var target = File(managedDir, safeName)
        var index = 1
        while (target.exists()) {
            val next = if (extension.isBlank()) "$baseName-$index" else "$baseName-$index.$extension"
            target = File(managedDir, next)
            index += 1
        }
        return target
    }

    private fun uniqueBundleDir(name: String): File {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image-bundle" }
        var target = File(managedDir, safeName)
        var index = 1
        while (target.exists()) {
            target = File(managedDir, "$safeName-$index")
            index += 1
        }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_MODELS = "local_image_models_json"
        private const val KEY_SELECTED_MODEL_ID = "selected_local_image_model_id"
        private const val KEY_SELECTED_BACKEND = "selected_image_backend"
        private val MODEL_FILE_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "sft", "mnn", "bin", "ctx", "qnn")
        private val SUPPORTED_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "mnn", "zip")
    }
}

internal data class ImageBundleZipExtractionLimits(
    val maxEntryCount: Int = 32_768,
    val maxEntryBytes: Long = 32L * 1_024L * 1_024L * 1_024L,
    val maxTotalBytes: Long = 64L * 1_024L * 1_024L * 1_024L,
    val minFreeSpaceReserveBytes: Long = 64L * 1_024L * 1_024L
) {
    init {
        require(maxEntryCount >= 0) { "Image bundle zip entry limit must not be negative." }
        require(maxEntryBytes >= 0L) { "Image bundle zip entry byte limit must not be negative." }
        require(maxTotalBytes >= 0L) { "Image bundle zip total byte limit must not be negative." }
        require(minFreeSpaceReserveBytes >= 0L) {
            "Image bundle zip free-space reserve must not be negative."
        }
    }
}

internal data class ImageBundleZipExtractionResult(
    val extractedFiles: List<File>,
    val entryCount: Int,
    val extractedBytes: Long
)

/**
 * Extracts an image bundle without trusting zip directory sizes. Every entry path and every
 * expanded chunk is checked before bytes are committed to an app-owned target.
 */
internal fun extractBoundedImageBundleZip(
    source: InputStream,
    bundleDir: File,
    limits: ImageBundleZipExtractionLimits = ImageBundleZipExtractionLimits(),
    shouldSkipTarget: (File) -> Boolean = { false },
    usableSpaceProvider: (File) -> Long = { root -> root.usableSpace },
    copyBufferSize: Int = DEFAULT_BUFFER_SIZE
): ImageBundleZipExtractionResult {
    require(copyBufferSize > 0) { "Image bundle zip copy buffer must be positive." }
    val canonicalRoot = bundleDir.canonicalFile
    require(canonicalRoot.isDirectory) {
        "Image bundle extraction root must be an existing directory."
    }
    val rootPath = canonicalRoot.toPath()
    val archiveTargets = linkedSetOf<java.nio.file.Path>()
    val archiveFileTargets = linkedSetOf<java.nio.file.Path>()
    val createdFiles = mutableListOf<File>()
    val createdDirectories = mutableListOf<File>()
    val extractedFiles = mutableListOf<File>()
    var entryCount = 0
    var totalBytes = 0L
    var remainingWritableBudget = Long.MAX_VALUE

    try {
        ZipInputStream(source.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount <= limits.maxEntryCount) {
                    "Image bundle zip contains too many entries (limit=${limits.maxEntryCount})."
                }
                val relativePath = normalizeImageBundleZipEntryPath(entry.name, entry.isDirectory)
                val target = File(
                    canonicalRoot,
                    relativePath.replace('/', File.separatorChar)
                ).canonicalFile
                val targetPath = target.toPath()
                require(targetPath != rootPath && targetPath.startsWith(rootPath)) {
                    "Image bundle zip entry escapes the extraction root: $relativePath"
                }
                require(archiveTargets.add(targetPath)) {
                    "Image bundle zip contains a duplicate target: $relativePath"
                }
                var parentPath = targetPath.parent
                while (parentPath != null && parentPath != rootPath) {
                    require(parentPath !in archiveFileTargets) {
                        "Image bundle zip path conflicts with a parent file: $relativePath"
                    }
                    parentPath = parentPath.parent
                }
                if (!entry.isDirectory) {
                    require(archiveTargets.none { existing ->
                        existing != targetPath && existing.startsWith(targetPath)
                    }) {
                        "Image bundle zip file conflicts with an existing child path: $relativePath"
                    }
                    archiveFileTargets.add(targetPath)
                }

                val skipTarget = !entry.isDirectory && shouldSkipTarget(target)
                when {
                    skipTarget -> require(target.isFile) {
                        "Image bundle zip skip target must be an existing file: $relativePath"
                    }
                    entry.isDirectory && target.exists() -> require(
                        target.isDirectory && target in createdDirectories
                    ) {
                        "Image bundle zip target already exists: $relativePath"
                    }
                    !entry.isDirectory -> require(!target.exists()) {
                        "Image bundle zip target already exists: $relativePath"
                    }
                }

                val declaredSize = entry.size
                require(declaredSize < 0L || declaredSize <= limits.maxEntryBytes) {
                    "Image bundle zip entry is too large: $relativePath"
                }
                var entryBytes = 0L
                val output = if (!entry.isDirectory && !skipTarget) {
                    createImageBundleDirectories(
                        canonicalRoot,
                        requireNotNull(target.parentFile),
                        createdDirectories
                    )
                    require(target.createNewFile()) {
                        "Image bundle zip target appeared during extraction: $relativePath"
                    }
                    createdFiles.add(target)
                    target.outputStream().buffered()
                } else {
                    null
                }
                try {
                    val buffer = ByteArray(copyBufferSize)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        entryBytes = checkedImageBundleZipAdd(
                            entryBytes,
                            read.toLong(),
                            "Image bundle zip entry is too large: $relativePath"
                        )
                        totalBytes = checkedImageBundleZipAdd(
                            totalBytes,
                            read.toLong(),
                            "Image bundle zip expands beyond the supported total size."
                        )
                        require(entryBytes <= limits.maxEntryBytes) {
                            "Image bundle zip entry is too large: $relativePath"
                        }
                        require(totalBytes <= limits.maxTotalBytes) {
                            "Image bundle zip expands beyond the supported total size."
                        }
                        if (output != null) {
                            val usableSpace = usableSpaceProvider(canonicalRoot)
                            require(usableSpace >= limits.minFreeSpaceReserveBytes) {
                                "Image bundle zip extraction would consume the reserved free space."
                            }
                            remainingWritableBudget = minOf(
                                remainingWritableBudget,
                                usableSpace - limits.minFreeSpaceReserveBytes
                            )
                            require(read.toLong() <= remainingWritableBudget) {
                                "Image bundle zip extraction would consume the reserved free space."
                            }
                            remainingWritableBudget -= read.toLong()
                        }
                        output?.write(buffer, 0, read)
                    }
                } finally {
                    output?.close()
                }
                require(declaredSize < 0L || declaredSize == entryBytes) {
                    "Image bundle zip entry size does not match its directory record: $relativePath"
                }
                zip.closeEntry()

                if (entry.isDirectory) {
                    createImageBundleDirectories(canonicalRoot, target, createdDirectories)
                } else if (!skipTarget) {
                    require(target.isFile && target.length() == entryBytes) {
                        "Image bundle zip entry was not written completely: $relativePath"
                    }
                    extractedFiles += target
                }
            }
        }
        return ImageBundleZipExtractionResult(
            extractedFiles = extractedFiles.toList(),
            entryCount = entryCount,
            extractedBytes = totalBytes
        )
    } catch (error: Throwable) {
        createdFiles.asReversed().forEach { file -> runCatching { file.delete() } }
        createdDirectories.asReversed().forEach { directory -> runCatching { directory.delete() } }
        throw error
    }
}

private fun normalizeImageBundleZipEntryPath(raw: String, directory: Boolean): String {
    require(raw.isNotBlank()) { "Image bundle zip contains a blank entry path." }
    require('\u0000' !in raw) { "Image bundle zip entry path contains a NUL character." }
    val withForwardSeparators = raw.replace('\\', '/')
    val normalized = if (directory) {
        withForwardSeparators.removeSuffix("/")
    } else {
        withForwardSeparators
    }
    require(normalized.isNotBlank()) { "Image bundle zip contains an invalid root entry." }
    require(!normalized.startsWith('/')) {
        "Image bundle zip entry path must be relative: $raw"
    }
    require(!Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
        "Image bundle zip entry path must not use a drive path: $raw"
    }
    val segments = normalized.split('/')
    require(segments.none { it.isBlank() || it == "." || it == ".." }) {
        "Image bundle zip entry path contains an unsafe segment: $raw"
    }
    return segments.joinToString("/")
}

private fun createImageBundleDirectories(
    canonicalRoot: File,
    requestedDirectory: File,
    createdDirectories: MutableList<File>
) {
    val rootPath = canonicalRoot.toPath()
    val directory = requestedDirectory.canonicalFile
    require(directory.toPath().startsWith(rootPath)) {
        "Image bundle zip directory escapes the extraction root."
    }
    val missing = mutableListOf<File>()
    var cursor = directory
    while (cursor != canonicalRoot && !cursor.exists()) {
        missing += cursor
        cursor = requireNotNull(cursor.parentFile).canonicalFile
        require(cursor.toPath().startsWith(rootPath)) {
            "Image bundle zip directory escapes the extraction root."
        }
    }
    require(cursor.isDirectory) { "Image bundle zip path has a non-directory parent." }
    missing.asReversed().forEach { candidate ->
        require(candidate.mkdir()) {
            "Unable to create image bundle zip directory: ${candidate.name}"
        }
        createdDirectories += candidate
    }
}

private fun checkedImageBundleZipAdd(current: Long, increment: Long, message: String): Long {
    require(increment >= 0L && current <= Long.MAX_VALUE - increment) { message }
    return current + increment
}

internal fun extractImageBundleZipIntoDirectory(zipFile: File, bundleDir: File) {
    val canonicalRoot = bundleDir.canonicalFile
    val canonicalZip = zipFile.canonicalFile
    val mcaManifest = File(canonicalRoot, "manifest.json").canonicalFile
    zipFile.inputStream().use { input ->
        extractBoundedImageBundleZip(
            source = input,
            bundleDir = canonicalRoot,
            shouldSkipTarget = { target ->
                target == canonicalZip ||
                    (target == mcaManifest && mcaManifest.isMcaImageBundleManifest())
            }
        )
    }
}

internal fun findPrimaryImageModel(root: File): File? =
    root.walkTopDown()
        .filter { it.isFile }
        .filter { it.extension.lowercase() in READINESS_MODEL_EXTENSIONS }
        .sortedWith(
            compareByDescending<File> { it.name.isPrimaryImageModelName() }
                .thenByDescending { it.length() }
        )
        .firstOrNull()

private fun File.isMcaImageBundleManifest(): Boolean =
    isFile && runCatching {
        JSONObject(readText(Charsets.UTF_8)).optString("schema") == "mca.image_engine.bundle.v1"
    }.getOrDefault(false)

internal fun prepareMnnDiffusionTokenizerIfPossible(root: File): Boolean {
    if (!root.isDirectory) return false
    val rootCanonical = root.canonicalFile
    val rootMtok = File(root, "tokenizer.mtok")
    val existingMtok = root.findDescendantFile("tokenizer.mtok")
    if (existingMtok != null) {
        if (existingMtok.parentFile?.canonicalFile != rootCanonical || !rootMtok.isFile) {
            existingMtok.copyTo(rootMtok, overwrite = true)
        }
        return true
    }
    val existingTxt = root.findDescendantFile("tokenizer.txt")
    if (existingTxt != null) {
        val rootTxt = File(root, "tokenizer.txt")
        val tokenizerTxt = if (existingTxt.parentFile?.canonicalFile != rootCanonical) {
            existingTxt.copyTo(rootTxt, overwrite = true)
            rootTxt
        } else {
            existingTxt
        }
        tokenizerTxt.copyTo(rootMtok, overwrite = true)
        return true
    }

    val vocabFile = root.findDescendantFile("vocab.json") ?: return false
    val mergesFile = root.findDescendantFile("merges.txt") ?: return false
    val vocabJson = JSONObject(vocabFile.readText(Charsets.UTF_8))
    val idToToken = mutableMapOf<Int, String>()
    var maxId = -1
    val keys = vocabJson.keys()
    while (keys.hasNext()) {
        val token = keys.next()
        val rawId = vocabJson.opt(token)
        val id = when (rawId) {
            is Number -> rawId.toInt()
            is String -> rawId.toIntOrNull()
            else -> null
        } ?: continue
        if (id >= 0) {
            idToToken[id] = token
            if (id > maxId) maxId = id
        }
    }
    require(maxId >= 0) { "MNN-Diffusion tokenizer vocab.json has no valid token ids." }
    val decoder = (0..maxId).map { id ->
        idToToken[id] ?: error("MNN-Diffusion tokenizer vocab.json is missing token id $id.")
    }
    val merges = mergesFile.readLines(Charsets.UTF_8)
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
    require(merges.isNotEmpty()) { "MNN-Diffusion tokenizer merges.txt has no merge rules." }

    val target = File(root, "tokenizer.txt")
    val temp = File(root, "tokenizer.txt.tmp")
    temp.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.appendLine("430 3")
        writer.appendLine("0 0 0")
        writer.appendLine()
        writer.appendLine("${decoder.size} ${merges.size}")
        decoder.forEach { writer.appendLine(it) }
        merges.forEach { writer.appendLine(it) }
    }
    if (target.exists()) target.delete()
    require(temp.renameTo(target)) { "Failed to write MNN-Diffusion tokenizer.txt." }
    target.copyTo(rootMtok, overwrite = true)
    return true
}

internal fun qnnRuntimeSearchDirectories(
    bundleRoot: File?,
    existingDirectories: List<String>
): List<String> {
    val bundleRuntime = bundleRoot?.canonicalQnnRuntimeDirectoryOrNull()?.absolutePath
    return (listOfNotNull(bundleRuntime) + existingDirectories)
        .filter { it.isNotBlank() }
        .distinctBy { directory ->
            runCatching { File(directory).canonicalPath }
                .getOrElse { File(directory).absolutePath }
        }
}

/**
 * Resolves only linker-loadable QNN directories for an image bundle. A
 * complete runtime found beside a downloaded bundle is staged to private
 * code-cache storage first; it is never returned from external storage.
 */
internal data class QnnRuntimeDirectoryResolution(
    val directories: List<String>,
    val stagedRuntime: QnnImageStagedRuntime? = null,
    val stagingError: String? = null
)

internal fun qnnRuntimeDirectoryResolutionFor(
    context: Context,
    bundleRoot: File?
): QnnRuntimeDirectoryResolution {
    val appContext = context.applicationContext
    val requiredRuntimeProfile = bundleRoot
        ?.let { root -> runCatching { localImageBundleManifestFromRoot(root)?.requiredRuntimeProfile }.getOrNull() }
    val staged = stageQnnImageBundleRuntime(appContext, bundleRoot, requiredRuntimeProfile)
    if (staged.failed) {
        // Do not quietly fall back to an APK/GenieX host pair here. Mixing it
        // with the bundle's intended Skel/Stub profile can poison HTP state
        // and produce a misleading incompatible-context result.
        return QnnRuntimeDirectoryResolution(
            directories = emptyList(),
            stagingError = staged.error
        )
    }
    val fallback = listOf(
        File(appContext.filesDir, "qnnlibs").absolutePath,
        File(appContext.filesDir, "runtime_libs").absolutePath,
        appContext.applicationInfo.nativeLibraryDir,
        "/vendor/lib64",
        "/vendor/lib/rfsa/adsp",
        "/odm/lib64",
        "/system/lib64",
        "/system_ext/lib64",
        "/product/lib64"
    )
    val detectedRuntime = runCatching {
        // This runs after staging, so DeviceProfileReader can only see the
        // coherent private profile rather than the external bundle path.
        DeviceProfileReader(appContext).read().accelerationProfile.qnnRuntime
    }.getOrNull()
    val selectedHostDirectory = detectedRuntime?.qnnSystemLibraryPath
        ?.let(::File)
        ?.parentFile
        ?.absolutePath
    return QnnRuntimeDirectoryResolution(
        directories = qnnRuntimeSearchDirectories(
            bundleRoot = null,
            existingDirectories = listOfNotNull(staged.runtime?.directory?.absolutePath, selectedHostDirectory) +
                fallback + detectedRuntime?.searchDirectories.orEmpty()
        ),
        stagedRuntime = staged.runtime
    )
}

internal fun qnnRuntimeDirectoriesFor(context: Context, bundleRoot: File?): List<String> =
    qnnRuntimeDirectoryResolutionFor(context, bundleRoot).directories

/** Puts the device-selected coherent QNN host profile ahead of generic paths. */
internal fun qnnRuntimeDirectoriesFor(
    runtimeStatus: com.muyuchat.core.deviceprofile.QnnRuntimeStatus
): List<String> {
    val selectedHostDirectory = runtimeStatus.qnnSystemLibraryPath
        ?.let(::File)
        ?.parentFile
        ?.absolutePath
    return qnnRuntimeSearchDirectories(
        bundleRoot = null,
        existingDirectories = listOfNotNull(selectedHostDirectory) + runtimeStatus.searchDirectories
    )
}

private fun File.canonicalQnnRuntimeDirectoryOrNull(): File? {
    val root = runCatching { canonicalFile }.getOrNull()?.takeIf { it.isDirectory } ?: return null
    val runtime = runCatching { File(root, "runtime").canonicalFile }.getOrNull() ?: return null
    return runtime.takeIf {
        it.isDirectory && it.path.startsWith(root.path + File.separator)
    }
}

internal fun qnnImageVerificationStampFor(context: Context, bundleRoot: File): String {
    val appContext = context.applicationContext
    val bundle = QnnImageBundleIdentity.fromDirectory(bundleRoot)
    require(bundle.status == QnnImageBundleIdentityStatus.AVAILABLE) {
        "QNN image bundle is unavailable for verification."
    }
    return QnnImageVerificationStamp.create(
        device = currentQnnImageDeviceIdentity(appContext),
        runtime = currentQnnImageRuntimeIdentity(appContext, bundleRoot),
        bundleDirectory = bundleRoot
    ).toJsonString()
}

internal fun LocalImageModelRecord.hasCurrentQnnVerificationStamp(
    context: Context,
    bundleRoot: File
): Boolean {
    if (qnnVerificationStamp.isBlank()) return false
    val appContext = context.applicationContext
    return runCatching {
        QnnImageVerificationStamp.fromJson(qnnVerificationStamp).matchesCurrent(
            device = currentQnnImageDeviceIdentity(appContext),
            runtime = currentQnnImageRuntimeIdentity(appContext, bundleRoot),
            bundleDirectory = bundleRoot
        )
    }.getOrDefault(false)
}

private fun currentQnnImageDeviceIdentity(context: Context): QnnImageDeviceIdentity {
    val profile = DeviceProfileReader(context).read()
    return QnnImageDeviceIdentity(
        soc = profile.accelerationProfile.chipsetCode.ifBlank { profile.socModel.ifBlank { "unknown" } },
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
        buildFingerprint = Build.FINGERPRINT.orEmpty().ifBlank { "unknown" }
    )
}

private fun currentQnnImageRuntimeIdentity(
    context: Context,
    bundleRoot: File
): QnnImageRuntimeIdentity {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        packageInfo.versionCode.toLong()
    }
    val appIdentity = buildString {
        append(context.packageName)
        append('/')
        append(packageInfo.versionName.orEmpty().ifBlank { "unknown" })
        append('#')
        append(versionCode)
    }
    val nativeRuntime = runCatching {
        val runtimeDirsJson = JSONArray(qnnRuntimeDirectoriesFor(context, bundleRoot)).toString()
        qnnRuntimeIdentityJson(NativeQnnBridge().inspectRuntime(runtimeDirsJson))
    }.getOrDefault("unavailable")
    return QnnImageRuntimeIdentity(
        app = appIdentity,
        nativeRuntime = nativeRuntime
    )
}

internal fun qnnRuntimeIdentityJson(runtimeProbeJson: String): String {
    val probe = JSONObject(runtimeProbeJson)
    val identity = JSONObject()
        .put("schema", "mca.qnn.runtime.identity.v1")
        .put("ready", probe.optBoolean("ready", false))
        .put("loadable", probe.optBoolean("loadable", false))
        .put("qnnInterfacePresent", probe.optBoolean("qnnInterfacePresent", false))
        .put("qnnSystemInterfacePresent", probe.optBoolean("qnnSystemInterfacePresent", false))
        .put("compile", probe.optJSONObject("compile") ?: JSONObject())
    val libraries = JSONArray()
    QNN_RUNTIME_LIBRARY_KEYS.forEach { (role, key) ->
        libraries.put(qnnRuntimeFileIdentity(role, probe.optString(key)))
    }
    return identity.put("selectedLibraries", libraries).toString()
}

private fun qnnRuntimeFileIdentity(role: String, rawPath: String): JSONObject {
    val identity = JSONObject().put("role", role)
    if (rawPath.isBlank()) return identity.put("status", "not_selected")

    val file = File(rawPath)
    val canonicalPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    identity.put("path", canonicalPath)
    if (!file.exists()) return identity.put("status", "missing")
    if (!file.isFile) return identity.put("status", "not_file")

    identity
        .put("length", file.length())
        .put("lastModified", file.lastModified())
    val digest = runCatching { file.sha256Contents() }.getOrNull()
        ?: return identity.put("status", "unreadable")
    return identity
        .put("status", "available")
        .put("sha256", digest)
}

private fun File.sha256Contents(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

internal fun sdxlQnnConditioningGraphSha256(conditioningRoot: File): String {
    val executionAssetNames = buildList {
        add(SDXL_QNN_CONDITIONING_REQUIRED_EXECUTION_ASSET_NAMES.first())
        File(conditioningRoot, SDXL_QNN_OPTIONAL_CLIP1_WEIGHT_NAME)
            .takeIf { file -> file.isFile && file.length() > 0L }
            ?.let { add(SDXL_QNN_OPTIONAL_CLIP1_WEIGHT_NAME) }
        addAll(SDXL_QNN_CONDITIONING_REQUIRED_EXECUTION_ASSET_NAMES.drop(1))
    }
    val payload = executionAssetNames.joinToString(
        separator = "\n",
        postfix = "\n"
    ) { name ->
        val asset = File(conditioningRoot, name)
        require(asset.isFile && asset.length() > 0L) {
            "MNN SDXL conditioning execution asset is missing or empty: ${asset.absolutePath}"
        }
        "$name=${asset.sha256Contents()}"
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private val QNN_RUNTIME_LIBRARY_KEYS = listOf(
    "qnn_system" to "qnnSystemLibraryPath",
    "qnn_htp" to "qnnHtpLibraryPath",
    "htp_skel" to "htpSkelLibraryPath",
    "htp_stub" to "htpStubLibraryPath",
    "cdsp_rpc" to "cdspRpcLibraryPath"
)

fun LocalImageModelRecord.localImageReadinessMessage(): String? {
    // Persisted verification is diagnostic evidence from an earlier attempt,
    // not a certificate.  A complete bundle must always be allowed to reach
    // the real native load/graph/generation path, including UNKNOWN and FAILED
    // records.  Concrete package/format/runtime structure remains the only
    // pre-execution admission check.
    return localImageStructuralReadinessMessage()
}

/**
 * Returns true only when FAILED is backed by a result recorded for the current
 * model record.  Legacy/stale FAILED bits must not be presented as a current
 * native failure and never participate in admission.
 */
internal fun LocalImageModelRecord.hasCurrentLocalImageExecutionFailure(): Boolean =
    verificationStatus == LocalImageVerificationStatus.FAILED &&
        verificationMessage.isNotBlank() &&
        verifiedAt > 0L &&
        localImageStructuralReadinessMessage() == null

/** Advisory text only; callers must never use this value to block selection or execution. */
fun LocalImageModelRecord.localImageVerificationDiagnosticMessage(): String? {
    if (localImageStructuralReadinessMessage() != null) return null
    val runtimeName = when (runtime) {
        LocalImageRuntime.MNN_DIFFUSION -> "MNN-Diffusion"
        LocalImageRuntime.STABLE_DIFFUSION_CPP -> "stable-diffusion.cpp"
        else -> return null
    }
    return when (verificationStatus) {
        LocalImageVerificationStatus.UNKNOWN ->
            "$runtimeName 尚未记录真实运行结果，可直接尝试；本次 native 执行结果将作为诊断依据。"
        LocalImageVerificationStatus.FAILED -> if (hasCurrentLocalImageExecutionFailure()) {
            "$runtimeName 上次真实执行失败：${verificationMessage.trim()}；仍可直接重试，以本次执行结果为准。"
        } else {
            "$runtimeName 的历史失败状态没有当前执行证据，不会阻止使用；可直接重新尝试。"
        }
        LocalImageVerificationStatus.PASSED -> null
        LocalImageVerificationStatus.MNN_SMOKE_PASSED ->
            if (runtime == LocalImageRuntime.MNN_DIFFUSION) null else "$runtimeName 可直接尝试，历史验证类型与当前引擎不匹配。"
        LocalImageVerificationStatus.QNN_SMOKE_PASSED,
        LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED,
        LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED ->
            "$runtimeName 可直接尝试，历史验证类型与当前引擎不匹配。"
    }
}

fun LocalImageModelRecord.localImageStructuralReadinessMessage(): String? {
    if (!configured) return "本地图像生成模型文件不存在，请重新导入。"
    if (runtime == LocalImageRuntime.MNN_DIFFUSION) {
        return mnnDiffusionReadinessMessage()
    }
    if (runtime == LocalImageRuntime.QNN_HTP) {
        return qnnImageBundleReadinessMessage()
    }
    if (runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        return "当前仅支持 stable-diffusion.cpp 或 MNN-Diffusion 本地图像引擎。"
    }
    val componentSelection = runCatching { resolveStableDiffusionComponentSelection(this) }
        .getOrElse { error ->
            return error.message ?: "stable-diffusion.cpp image bundle component selection failed."
        }
    if (componentSelection.mode == STABLE_DIFFUSION_COMPONENT_MODE_MANIFEST) return null
    if (!family.requiresCompanionComponents()) return null
    val requirement = family.requiredCompanionComponentHint()
    val root = bundleRoot?.let(::File)?.takeIf { it.isDirectory }
        ?: return "缺少组件包：${displayName} 只有 diffusion 主模型，还需要 $requirement。请在模型管理 > 文件中导入包含 diffusion 主模型、VAE/AE、文本编码器/LLM 的 zip 引擎包。"
    val primary = runCatching { File(path).canonicalPath }.getOrDefault(path)
    val files = root.walkTopDown()
        .filter { it.isFile }
        .filter { it.extension.lowercase() in READINESS_MODEL_EXTENSIONS }
        .filterNot { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) == primary }
        .toList()
    val missing = buildList {
        if (files.none { it.isVaeComponentFile() }) add("VAE")
        if (files.none { it.isTextEncoderComponentFile() }) add("文本编码器/LLM")
    }
    return if (missing.isEmpty()) {
        null
    } else {
        "缺少组件：${missing.joinToString("、")}。${displayName} 需要 $requirement，不能只用单个 GGUF 生成图片。"
    }
}

fun LocalImageModelRecord.isReadyForLocalImageGeneration(): Boolean =
    localImageReadinessMessage() == null

/** Automatic selection is structural only; verification state is deliberately ignored. */
internal fun selectStructurallyReadyLocalImageModelId(
    models: List<LocalImageModelRecord>,
    preferredId: String?
): String? = preferredId
    ?.takeIf { id ->
        models.any { model -> model.id == id && model.localImageStructuralReadinessMessage() == null }
    }
    ?: models.firstOrNull { model -> model.localImageStructuralReadinessMessage() == null }?.id

fun LocalImageModelRecord.localImageReadinessLabel(): String {
    val structuralReady = localImageStructuralReadinessMessage() == null
    if (!structuralReady) return "缺少组件"
    if (runtime == LocalImageRuntime.MNN_DIFFUSION) {
        return when (verificationStatus) {
            LocalImageVerificationStatus.PASSED -> "可用"
            LocalImageVerificationStatus.MNN_SMOKE_PASSED -> "MNN smoke"
            LocalImageVerificationStatus.UNKNOWN -> "未验证·可尝试"
            LocalImageVerificationStatus.FAILED ->
                if (hasCurrentLocalImageExecutionFailure()) "上次失败·可重试" else "可直接尝试"
            LocalImageVerificationStatus.QNN_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED -> "可直接尝试"
        }
    }
    if (runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        return when (verificationStatus) {
            LocalImageVerificationStatus.PASSED -> "可用"
            LocalImageVerificationStatus.UNKNOWN -> "未验证·可尝试"
            LocalImageVerificationStatus.FAILED ->
                if (hasCurrentLocalImageExecutionFailure()) "上次失败·可重试" else "可直接尝试"
            LocalImageVerificationStatus.MNN_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED -> "可直接尝试"
        }
    }
    if (runtime == LocalImageRuntime.QNN_HTP) {
        return when (verificationStatus) {
            LocalImageVerificationStatus.MNN_SMOKE_PASSED -> "MNN smoke"
            LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED -> "NPU 1-step smoke"
            LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED -> "NPU probe"
            LocalImageVerificationStatus.QNN_SMOKE_PASSED -> "NPU smoke"
            LocalImageVerificationStatus.UNKNOWN -> "NPU 待校验"
            LocalImageVerificationStatus.FAILED -> "NPU 校验失败"
            LocalImageVerificationStatus.PASSED -> "可用"
        }
    }
    return "可用"
}

private fun LocalImageModelRecord.qnnImageBundleReadinessMessage(): String? {
    val root = bundleRoot?.let(::File)?.takeIf { it.isDirectory }
        ?: File(path).parentFile?.takeIf { it.isDirectory }
        ?: return "QNN image engine requires a complete QNN bundle directory."
    val manifest = when (val inspection = inspectLocalImageBundleManifestFromRoot(root)) {
        LocalImageBundleManifestInspection.Undeclared -> null
        is LocalImageBundleManifestInspection.Ready -> inspection.manifest
        is LocalImageBundleManifestInspection.Invalid ->
            return "QNN image bundle manifest is invalid: ${inspection.message}"
    }
    qnnRequiredBundleRuntimeReadinessMessage(root, manifest?.requiredRuntimeProfile)?.let { return it }
    val requiresControlNet = manifest?.task == "CONTROL_IMAGE" ||
        manifest?.id.orEmpty().contains("controlnet", ignoreCase = true)
    if (manifest != null) {
        when (val contract = manifest.resolveRuntimeComponentContract(root)) {
            is LocalImageRuntimeComponentContract.Invalid ->
                return "QNN image bundle runtime contract is invalid: ${contract.message}"
            is LocalImageRuntimeComponentContract.Ready -> {
                if (contract.missingPaths.isNotEmpty()) {
                    return "QNN image bundle manifest requires missing or empty components: " +
                        contract.missingPaths.joinToString(", ") + "."
                }
                return if (requiresControlNet && root.nonEmptyQnnContextPath("controlnet.bin") == null) {
                    "QNN image bundle is incomplete: controlnet.bin."
                } else {
                    null
                }
            }
            LocalImageRuntimeComponentContract.UndeclaredLegacy -> Unit
        }
    }
    val files = root.walkTopDown().filter { it.isFile }.toList()
    val names = files.map { it.invariantSeparatorsPath.lowercase() }
    fun hasAny(vararg tokens: String): Boolean = names.any { name -> tokens.any { it in name } }
    val missing = buildList {
        if (!hasAny("qnn", "context", "unet", "diffusion", "transformer")) add("QNN diffusion/context")
        if (!hasAny("vae", "decoder", "ae")) add("VAE/AE decoder")
        if (!hasAny("text_encoder", "clip", "t5", "tokenizer", "qwen", "llm")) add("text encoder/tokenizer")
        if (requiresControlNet && root.nonEmptyQnnContextPath("controlnet.bin") == null) {
            add("controlnet.bin")
        }
    }
    return if (missing.isEmpty()) null else "QNN image bundle is incomplete: ${missing.joinToString(", ")}."
}

private fun LocalImageModelRecord.mnnDiffusionReadinessMessage(): String? {
    val effectiveFamily = resolvedMnnFamily()
    return LocalImageBundleContract.inspectMnnBundle(
        bundleRoot = bundleRoot?.let(::File),
        primaryFile = File(path),
        family = effectiveFamily
    ).readinessMessage(effectiveFamily)
}

private fun LocalImageModelRecord.resolvedMnnFamily(): LocalImageModelFamily {
    val primaryFile = File(path)
    val manifest = sequenceOf(
        bundleRoot?.takeIf { it.isNotBlank() }?.let(::File),
        primaryFile.parentFile
    )
        .filterNotNull()
        .distinctBy { root ->
            runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        }
        .mapNotNull { root ->
            runCatching { localImageBundleManifestFromRoot(root) }.getOrNull()
        }
        .firstOrNull()
    return mnnVerificationRoute(family, manifest).family
}

private fun inferLocalImageRuntimeForBundle(root: File, primary: File): LocalImageRuntime =
    when {
        root.walkTopDown().any {
            it.isFile && listOf("qnn", "qairt", "htp").any { token -> token in it.invariantSeparatorsPath.lowercase() }
        } -> LocalImageRuntime.QNN_HTP
        primary.extension.equals("mnn", ignoreCase = true) -> LocalImageRuntime.MNN_DIFFUSION
        root.walkTopDown().any { it.isFile && it.extension.equals("mnn", ignoreCase = true) } -> LocalImageRuntime.MNN_DIFFUSION
        else -> LocalImageRuntime.infer(primary.name)
    }

internal fun inspectLocalImageBundleManifestFromRoot(
    root: File,
    effectiveProfileResolver: LocalImageManifestProfileResolver =
        ImageExecutionProfileResolver::resolve
): LocalImageBundleManifestInspection {
    if (!root.isDirectory) return LocalImageBundleManifestInspection.Undeclared
    val manifestFile = root.findDescendantFile("manifest.json")
        ?: return LocalImageBundleManifestInspection.Undeclared
    val rawManifest = runCatching { manifestFile.readText(Charsets.UTF_8) }
        .getOrElse { return LocalImageBundleManifestInspection.Undeclared }
    val manifest = try {
        JSONObject(rawManifest)
    } catch (error: Throwable) {
        return if (OWNED_IMAGE_BUNDLE_SCHEMA_PATTERN.containsMatchIn(rawManifest)) {
            LocalImageBundleManifestInspection.Invalid(
                error.message?.takeIf(String::isNotBlank)
                    ?: "Image bundle manifest is malformed."
            )
        } else {
            LocalImageBundleManifestInspection.Undeclared
        }
    }
    val declaredSchema = (manifest.opt("schema") as? String)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    if (declaredSchema != null && declaredSchema != OWNED_IMAGE_BUNDLE_SCHEMA) {
        return LocalImageBundleManifestInspection.Undeclared
    }
    val ownedSchema = declaredSchema == OWNED_IMAGE_BUNDLE_SCHEMA
    return try {
        LocalImageBundleManifestInspection.Ready(
            parseLocalImageBundleManifest(
                root = root,
                manifest = manifest,
                effectiveProfileResolver = effectiveProfileResolver,
                resolveVersionedProfile = ownedSchema
            )
        )
    } catch (error: Throwable) {
        if (ownedSchema) {
            LocalImageBundleManifestInspection.Invalid(
                error.message?.takeIf(String::isNotBlank)
                    ?: "Image bundle manifest is malformed."
            )
        } else {
            LocalImageBundleManifestInspection.Undeclared
        }
    }
}

internal fun localImageBundleManifestFromRoot(root: File): LocalImageBundleManifest? =
    when (val inspection = inspectLocalImageBundleManifestFromRoot(root)) {
        LocalImageBundleManifestInspection.Undeclared -> null
        is LocalImageBundleManifestInspection.Ready -> inspection.manifest
        is LocalImageBundleManifestInspection.Invalid ->
            throw IllegalArgumentException(inspection.message)
    }

private fun parseLocalImageBundleManifest(
    root: File,
    manifest: JSONObject,
    effectiveProfileResolver: LocalImageManifestProfileResolver,
    resolveVersionedProfile: Boolean
): LocalImageBundleManifest {
    val runtime = manifest.optString("runtime").takeIf { it.isNotBlank() }?.let(LocalImageRuntime::from)
    val family = manifest.optString("family").takeIf { it.isNotBlank() }?.let(LocalImageModelFamily::from)
    val imageSize = manifest.manifestImageSize()
    val smoke = manifest.strictOptionalObject("smoke")
        ?: manifest.strictOptionalObject("smokeSpec")
    val qnnSmokeSpecs = manifest.strictOptionalArray("smokes")
        .takeIf { it != null && it.length() > 0 }
        ?.toQnnSmokeSpecs()
        ?: manifest.strictOptionalArray("smokeSpecs")
            .takeIf { it != null && it.length() > 0 }
            ?.toQnnSmokeSpecs()
        ?: smoke?.let { listOf(QnnSmokeSpec.fromSmokeJson(it)) }
        ?: emptyList()
    val components = manifest.strictOptionalArray("components")
    val componentContracts = components?.toLocalImageBundleComponentContracts().orEmpty()
    val executionProfileDeclared = manifest.has("executionProfile")
    val executionProfile = manifest.strictOptionalObject("executionProfile")
    val effectiveExecutionProfile = executionProfile?.takeIf { resolveVersionedProfile }?.let {
        resolveEffectiveLocalImageManifestProfile(manifest, effectiveProfileResolver)
    }
    val executionProfilePaths = effectiveExecutionProfile?.requiredExecutionProfilePaths()
        ?: executionProfile?.requiredExecutionProfilePaths()
        ?: ParsedExecutionProfilePaths.Empty
    val requiredRuntimeProfile = manifest.strictOptionalObject("requiredRuntimeProfile")?.let { profile ->
        val qnnSdk = profile.strictRequiredString("qnnSdk")
        val htpArch = profile.strictRequiredPositiveInt("htpArch")
        LocalImageQnnRuntimeProfile(
            qnnSdk = qnnSdk,
            htpArch = htpArch,
            completeBundleRuntime = profile.strictOptionalBoolean(
                "completeBundleRuntime",
                default = true
            )
        )
    }
    val primaryPath = components?.firstComponentPath("DIFFUSION")
        ?: components?.firstComponentPath("MODEL")
        ?: components?.firstComponentPath("UNET")
        ?: components?.firstComponentPath("TRANSFORMER")
        ?: manifest.optString("primary").takeIf { it.isNotBlank() }
        ?: manifest.optString("primaryFile").takeIf { it.isNotBlank() }
    val primaryFile = primaryPath
        ?.let { root.safeDescendantOrNull(it) }
        ?.takeIf { it.isFile }
        ?: executionProfilePaths.graphPaths["unet"]
            ?.let { root.safeDescendantOrNull(it) }
            ?.takeIf { it.isFile && it.length() > 0L }
    return LocalImageBundleManifest(
        id = manifest.optString("id").takeIf { it.isNotBlank() },
        displayName = manifest.optString("title").takeIf { it.isNotBlank() }
            ?: manifest.optString("displayName").takeIf { it.isNotBlank() }
            ?: manifest.optString("name").takeIf { it.isNotBlank() },
        task = manifest.optString("task")
            .trim()
            .uppercase()
            .takeIf(String::isNotBlank),
        runtime = runtime,
        family = family,
        imageSize = imageSize,
        minDeviceTier = manifest.optImageEngineMinDeviceTier(),
        requiresQnnRuntime = manifest.strictOptionalBoolean(
            "requiresQnnRuntime",
            default = runtime == LocalImageRuntime.QNN_HTP
        ),
        requiredRuntimeProfile = requiredRuntimeProfile,
        requiresSmokeTest = manifest.strictOptionalBoolean("requiresSmokeTest", default = true),
        smokeWidth = smoke?.optInt("width", 0) ?: 0,
        smokeHeight = smoke?.optInt("height", 0) ?: 0,
        smokeSteps = smoke?.optInt("steps", 0) ?: 0,
        smokeTimeoutSeconds = smoke?.optInt("timeoutSeconds", 0) ?: 0,
        qnnSmokeSpec = qnnSmokeSpecs.firstOrNull() ?: QnnSmokeSpec.Empty,
        qnnSmokeSpecs = qnnSmokeSpecs,
        primaryFile = primaryFile,
        components = componentContracts,
        executionProfileRequiredPaths = executionProfilePaths.requiredPaths,
        executionProfileGraphPaths = executionProfilePaths.graphPaths,
        executionProfileDeclared = executionProfileDeclared,
        componentCount = components?.length() ?: 0
    )
}

internal fun LocalImageBundleManifest.resolveRuntimeComponentContract(
    root: File
): LocalImageRuntimeComponentContract {
    fun exists(relativePath: String): Boolean = root.safeDescendantOrNull(relativePath)
        ?.let { file -> file.isFile && file.length() > 0L }
        ?: false
    val smokePaths = qnnSmokeSpecs
        .asSequence()
        .map(QnnSmokeSpec::contextBinary)
        .filter(String::isNotBlank)
        .toList()
    val requiredNonArchiveComponents = components
        .asSequence()
        .filter(LocalImageBundleComponentContract::required)
        .filterNot { component -> component.relativePath.isArchiveContainerPath() }
        .map(LocalImageBundleComponentContract::relativePath)
        .toList()
    val requiredArchives = components
        .asSequence()
        .filter(LocalImageBundleComponentContract::required)
        .filter { component -> component.relativePath.isArchiveContainerPath() }
        .map(LocalImageBundleComponentContract::relativePath)
        .toList()
    val communityClipAssets = listOf(
        "clip_v2.mnn",
        "tokenizer.json",
        "token_emb.bin",
        "pos_emb.bin"
    )
    val communityClipLayoutAttempted = communityClipAssets.any(::exists)
    val inferredLegacyAssets = if (!executionProfileDeclared && communityClipLayoutAttempted) {
        communityClipAssets
    } else {
        emptyList()
    }
    val requiredRuntimePaths = (
        executionProfileRequiredPaths + smokePaths +
            requiredNonArchiveComponents + inferredLegacyAssets
        ).distinct()
    try {
        requiredRuntimePaths.forEachIndexed { index, path ->
            requireSafeImageRuntimePath(path, "runtimeComponents[$index]")
        }
        requiredArchives.forEachIndexed { index, path ->
            requireSafeImageRuntimePath(path, "archiveComponents[$index]")
        }
    } catch (error: IllegalArgumentException) {
        return LocalImageRuntimeComponentContract.Invalid(
            error.message ?: "Image runtime component path is invalid."
        )
    }

    val graphTextEncoder = executionProfileGraphPaths["textEncoder"]
    val graphComplete = listOf("textEncoder", "unet", "vae")
        .all(executionProfileGraphPaths::containsKey) &&
        (task != "CONTROL_IMAGE" || executionProfileGraphPaths.containsKey("controlNet"))
    val declaredCommunityClipComplete = graphTextEncoder
        ?.substringAfterLast('/')
        ?.equals("clip_v2.mnn", ignoreCase = true) != true ||
        listOf("tokenizer.json", "token_emb.bin", "pos_emb.bin")
            .all(executionProfileRequiredPaths::contains)
    val profileContractComplete = executionProfileDeclared &&
        graphComplete && declaredCommunityClipComplete

    val smokeNames = smokePaths.map { it.substringAfterLast('/').lowercase() }
    val smokeHasUnet = smokeNames.any { name ->
        "unet" in name || "diffusion" in name || "transformer" in name
    }
    val smokeHasVae = smokeNames.any { name -> "vae" in name || "decoder" in name }
    val smokeHasQnnTextEncoder = smokeNames.any { name ->
        "text_encoder" in name || "clip" in name
    }
    val legacySmokeContractComplete = !executionProfileDeclared &&
        smokeHasUnet && smokeHasVae &&
        (smokeHasQnnTextEncoder || communityClipLayoutAttempted)

    val componentRoles = components
        .asSequence()
        .filter(LocalImageBundleComponentContract::required)
        .filterNot { component -> component.relativePath.isArchiveContainerPath() }
        .map(LocalImageBundleComponentContract::role)
        .toSet()
    val directComponentContractComplete =
        componentRoles.any { role -> role in setOf("DIFFUSION", "UNET", "TRANSFORMER") } &&
            componentRoles.any { role -> role in setOf("VAE", "VAE_DECODER") } &&
            componentRoles.any { role -> role in setOf("TEXT_ENCODER", "TOKENIZER") }
    val expandedContractComplete = profileContractComplete ||
        legacySmokeContractComplete || directComponentContractComplete

    if (executionProfileDeclared && !profileContractComplete) {
        return LocalImageRuntimeComponentContract.Invalid(
            "Image executionProfile does not declare a complete text encoder, UNet, VAE" +
                if (task == "CONTROL_IMAGE") ", and ControlNet runtime graph contract." else " runtime graph contract."
        )
    }
    val missingRuntimePaths = requiredRuntimePaths.filterNot(::exists)
    val missingArchives = requiredArchives.filterNot(::exists)
    if (missingArchives.isNotEmpty() &&
        (!expandedContractComplete || missingRuntimePaths.isNotEmpty())
    ) {
        val missingExtractedRuntime = missingRuntimePaths
            .takeIf { it.isNotEmpty() }
            ?.joinToString(
                prefix = " Missing or empty extracted runtime components: ",
                postfix = "."
            )
            .orEmpty()
        return LocalImageRuntimeComponentContract.Invalid(
            "Required archive container is absent before a complete extracted runtime contract was proven: " +
                missingArchives.joinToString(", ") + "." + missingExtractedRuntime
        )
    }
    if (expandedContractComplete) {
        return LocalImageRuntimeComponentContract.Ready(
            requiredPaths = requiredRuntimePaths,
            missingPaths = missingRuntimePaths
        )
    }
    return LocalImageRuntimeComponentContract.UndeclaredLegacy
}

internal fun LocalImageBundleManifest.missingRequiredComponentPaths(root: File): List<String> =
    when (val contract = resolveRuntimeComponentContract(root)) {
        LocalImageRuntimeComponentContract.UndeclaredLegacy -> emptyList()
        is LocalImageRuntimeComponentContract.Ready -> contract.missingPaths
        is LocalImageRuntimeComponentContract.Invalid -> listOf("<invalid-contract: ${contract.message}>")
    }

internal fun LocalImageBundleManifest.hasExactRuntimeComponentContract(root: File): Boolean =
    resolveRuntimeComponentContract(root) is LocalImageRuntimeComponentContract.Ready

private fun String.isArchiveContainerPath(): Boolean {
    val lower = trim().lowercase()
    return lower.endsWith(".zip") || lower.endsWith(".7z") ||
        lower.endsWith(".tar") || lower.endsWith(".tgz") ||
        lower.endsWith(".tar.gz")
}

private data class ParsedExecutionProfilePaths(
    val graphPaths: Map<String, String>,
    val requiredPaths: List<String>
) {
    companion object {
        val Empty = ParsedExecutionProfilePaths(emptyMap(), emptyList())
    }
}

// Provider binding for the effective strings handed to the selected runtime. Runtime-specific
// verifiers independently establish native graph and conditioning execution.
internal data class NativePromptEncodingEvidence(
    val promptWeightingApplied: Boolean,
    val positiveWeightedTokenCount: Int,
    val negativeWeightedTokenCount: Int,
    val promptWeightFingerprint: String,
    val nativePromptExecutionSha256: String,
    val nativePromptBindingStage: String
)

internal fun requireNativePromptEncodingEvidence(
    source: JSONObject,
    prompt: String,
    negativePrompt: String?,
    requireNoAppliedWeights: Boolean = false
): NativePromptEncodingEvidence {
    fun strictBoolean(field: String): Boolean =
        (source.opt(field) as? Boolean)
            ?: error("Native prompt encoding evidence field $field must be boolean.")

    fun strictNonNegativeInt(field: String): Int {
        val number = source.opt(field) as? Number
            ?: error("Native prompt encoding evidence field $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble() &&
            value in 0L..Int.MAX_VALUE.toLong()
        ) { "Native prompt encoding evidence field $field must be a non-negative integer." }
        return value.toInt()
    }

    fun strictSha256(field: String): String {
        val value = source.opt(field) as? String
            ?: error("Native prompt encoding evidence field $field must be a string.")
        require(Regex("[0-9a-f]{64}").matches(value)) {
            "Native prompt encoding evidence field $field must be a lowercase SHA-256 value."
        }
        return value
    }

    val applied = strictBoolean("promptWeightingApplied")
    val positiveCount = strictNonNegativeInt("positiveWeightedTokenCount")
    val negativeCount = strictNonNegativeInt("negativeWeightedTokenCount")
    require(applied == (positiveCount.toLong() + negativeCount.toLong() > 0L)) {
        "Native prompt weighting flag conflicts with its token counts."
    }
    if (requireNoAppliedWeights) {
        require(!applied && positiveCount == 0 && negativeCount == 0) {
            "The non-weighted QNN token path cannot accept applied prompt weights."
        }
    }
    val promptSha256 = strictSha256("nativePromptExecutionSha256")
    require(promptSha256 == imagePromptExecutionSha256(prompt, negativePrompt)) {
        "Native prompt encoding evidence does not match the effective positive and negative prompts."
    }
    val stage = source.opt("nativePromptBindingStage") as? String
        ?: error("Native prompt encoding evidence field nativePromptBindingStage must be a string.")
    require(stage == "conditioning_encoded") {
        "Native prompt encoding evidence must be published at conditioning_encoded."
    }
    return NativePromptEncodingEvidence(
        promptWeightingApplied = applied,
        positiveWeightedTokenCount = positiveCount,
        negativeWeightedTokenCount = negativeCount,
        promptWeightFingerprint = strictSha256("promptWeightFingerprint"),
        nativePromptExecutionSha256 = promptSha256,
        nativePromptBindingStage = stage
    )
}

private fun JSONObject.putPromptExecutionBinding(
    profile: ImageExecutionProfile,
    prompt: String,
    negativePrompt: String?
): JSONObject {
    val expectedPromptSha256 = imagePromptExecutionSha256(prompt, negativePrompt)
    val nativeEffective = optJSONObject("nativeEffective")
        ?: error("Native image execution metadata is missing nativeEffective prompt evidence.")
    fun requireConsumedPromptEvidence(source: JSONObject, layer: String) {
        val sha256 = source.opt("nativePromptExecutionSha256") as? String
            ?: error("Native $layer prompt execution SHA-256 must be a string.")
        val stage = source.opt("nativePromptBindingStage") as? String
            ?: error("Native $layer prompt binding stage must be a string.")
        require(sha256 == expectedPromptSha256) {
            "Native $layer prompt evidence does not match the effective prompts."
        }
        require(stage == "conditioning_consumed") {
            "Native $layer prompt evidence was not published after conditioning consumption."
        }
    }
    requireConsumedPromptEvidence(this, "outer")
    requireConsumedPromptEvidence(nativeEffective, "nativeEffective")
    require(
        getString("nativePromptExecutionSha256") ==
            nativeEffective.getString("nativePromptExecutionSha256") &&
            getString("nativePromptBindingStage") ==
            nativeEffective.getString("nativePromptBindingStage")
    ) { "Native outer and nativeEffective prompt evidence conflict." }
    profile.verifiedNativeSimplifiedChineseLanguageProofSha256()?.let { expectedProof ->
        val nativeProof = nativeEffective.opt("languageProofSha256") as? String
            ?: error("Native multilingual receipt is missing languageProofSha256.")
        require(nativeProof == expectedProof) {
            "Native multilingual receipt returned a different text-encoder semantic proof."
        }
        put("languageProofSha256", expectedProof)
    }
    return put("imageProfileBindingFingerprint", profile.bindingFingerprint)
        .put("promptLanguageBindingFingerprint", profile.promptLanguageBindingFingerprint)
        .put("textEncoderLanguageCapability", profile.textEncoderLanguageCapability().name)
        .put("promptExecutionSha256", expectedPromptSha256)
}

private fun JSONObject.requiredExecutionProfilePaths(): ParsedExecutionProfilePaths {
    val graph = strictOptionalObject("graph")
        ?: throw IllegalArgumentException(
            "Image executionProfile must declare a graph object."
        )
    val graphPaths = linkedMapOf<String, String>()
    val requiredPaths = mutableListOf<String>()
    listOf("textEncoder", "unet", "vae", "vaeEncoder", "controlNet").forEach { field ->
        if (!graph.has(field) || graph.isNull(field)) return@forEach
        val artifact = graph.strictOptionalObject(field)
            ?: throw IllegalArgumentException("Image executionProfile.graph.$field must be an object.")
        val relativePath = artifact.strictRequiredString("relativePath")
        requireSafeImageRuntimePath(relativePath, "executionProfile.graph.$field.relativePath")
        graphPaths[field] = relativePath
        requiredPaths += relativePath
    }
    listOf("schedulerSidecar", "tokenizerSidecar").forEach { field ->
        if (!graph.has(field) || graph.isNull(field)) return@forEach
        val relativePath = graph.strictRequiredString(field)
        requireSafeImageRuntimePath(relativePath, "executionProfile.graph.$field")
        requiredPaths += relativePath
    }
    graph.strictOptionalArray("configSidecars")?.let { sidecars ->
        for (index in 0 until sidecars.length()) {
            val value = sidecars.opt(index)
            val relativePath = (value as? String)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException(
                    "Image executionProfile.graph.configSidecars[$index] must be a non-empty string."
                )
            requireSafeImageRuntimePath(
                relativePath,
                "executionProfile.graph.configSidecars[$index]"
            )
            requiredPaths += relativePath
        }
    }
    return ParsedExecutionProfilePaths(
        graphPaths = graphPaths,
        requiredPaths = requiredPaths.distinct()
    )
}

private fun ImageExecutionProfile.requiredExecutionProfilePaths(): ParsedExecutionProfilePaths {
    val graphPaths = linkedMapOf<String, String>()
    val requiredPaths = mutableListOf<String>()
    listOf(
        "textEncoder" to graph.textEncoder,
        "unet" to graph.unet,
        "vae" to graph.vae,
        "vaeEncoder" to graph.vaeEncoder,
        "controlNet" to graph.controlNet
    ).forEach { (field, artifact) ->
        val relativePath = artifact?.relativePath ?: return@forEach
        requireSafeImageRuntimePath(relativePath, "executionProfile.graph.$field.relativePath")
        graphPaths[field] = relativePath
        requiredPaths += relativePath
    }
    listOfNotNull(graph.schedulerSidecar, graph.tokenizerSidecar)
        .forEachIndexed { index, relativePath ->
            requireSafeImageRuntimePath(
                relativePath,
                "executionProfile.graph.sidecars[$index]"
            )
            requiredPaths += relativePath
        }
    graph.configSidecars.forEachIndexed { index, relativePath ->
        requireSafeImageRuntimePath(
            relativePath,
            "executionProfile.graph.configSidecars[$index]"
        )
        requiredPaths += relativePath
    }
    return ParsedExecutionProfilePaths(
        graphPaths = graphPaths,
        requiredPaths = requiredPaths.distinct()
    )
}

private fun JSONObject.strictOptionalObject(field: String): JSONObject? {
    if (!has(field) || isNull(field)) return null
    return opt(field) as? JSONObject
        ?: throw IllegalArgumentException("Image bundle manifest $field must be an object.")
}

private fun JSONObject.strictOptionalArray(field: String): JSONArray? {
    if (!has(field) || isNull(field)) return null
    return opt(field) as? JSONArray
        ?: throw IllegalArgumentException("Image bundle manifest $field must be an array.")
}

private fun JSONObject.strictRequiredString(field: String): String {
    val value = opt(field)
    return (value as? String)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: throw IllegalArgumentException("Image bundle manifest $field must be a non-empty string.")
}

private fun JSONObject.strictRequiredPositiveInt(field: String): Int {
    val value = opt(field)
    return (value as? Number)
        ?.let { number ->
            val longValue = runCatching { BigDecimal(number.toString()).longValueExact() }
                .getOrNull()
            longValue?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        }
        ?: throw IllegalArgumentException(
            "Image bundle manifest $field must be a positive integer."
        )
}

private fun JSONObject.strictOptionalBoolean(field: String, default: Boolean): Boolean {
    if (!has(field) || isNull(field)) return default
    return opt(field) as? Boolean
        ?: throw IllegalArgumentException("Image bundle manifest $field must be a boolean.")
}

private fun requireSafeImageRuntimePath(path: String, field: String) {
    val normalized = path.trim().replace('\\', '/')
    val unsafe = normalized.isBlank() ||
        normalized.startsWith('/') ||
        Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
        normalized.split('/').any { segment ->
            segment.isBlank() || segment == "." || segment == ".."
        }
    require(!unsafe) { "Image bundle manifest $field escapes the bundle root: $path" }
}

private fun JSONArray.toLocalImageBundleComponentContracts(): List<LocalImageBundleComponentContract> =
    buildList {
        for (index in 0 until length()) {
            val component = opt(index) as? JSONObject
                ?: throw IllegalArgumentException(
                    "Image bundle manifest components[$index] must be an object."
                )
            val role = component.strictRequiredString("role").uppercase()
            val path = (component.opt("path") as? String)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: (component.opt("fileName") as? String)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException(
                    "Image bundle manifest components[$index] must declare path or fileName."
                )
            requireSafeImageRuntimePath(path, "components[$index]")
            val required = if (!component.has("required") || component.isNull("required")) {
                !role.equals("OPTIONAL", ignoreCase = true)
            } else {
                component.opt("required") as? Boolean
                    ?: throw IllegalArgumentException(
                        "Image bundle manifest components[$index].required must be a boolean."
                    )
            }
            add(
                LocalImageBundleComponentContract(
                    role = role,
                    relativePath = path,
                    required = required
                )
            )
        }
    }

private fun JSONArray.toQnnSmokeSpecs(): List<QnnSmokeSpec> =
    buildList {
        for (index in 0 until length()) {
            val smoke = opt(index) as? JSONObject
                ?: throw IllegalArgumentException(
                    "Image bundle manifest smokes[$index] must be an object."
                )
            smoke.opt("contextBinary")?.let { rawPath ->
                if (rawPath !is String || rawPath.isBlank()) {
                    throw IllegalArgumentException(
                        "Image bundle manifest smokes[$index].contextBinary must be a non-empty string."
                    )
                }
                requireSafeImageRuntimePath(rawPath, "smokes[$index].contextBinary")
            }
            add(QnnSmokeSpec.fromSmokeJson(smoke))
        }
    }

private fun JSONObject.optImageEngineMinDeviceTier(): ImageEngineMinDeviceTier {
    val value = optString("minDeviceTier").takeIf { it.isNotBlank() } ?: return ImageEngineMinDeviceTier.ANY
    return runCatching { ImageEngineMinDeviceTier.valueOf(value) }.getOrDefault(ImageEngineMinDeviceTier.ANY)
}

private fun JSONObject.manifestImageSize(): String? {
    val direct = optString("imageSize").takeIf { it.isNotBlank() }
        ?: optString("size").takeIf { it.isNotBlank() }
    if (direct != null) return direct
    val smoke = optJSONObject("smoke") ?: optJSONObject("smokeSpec")
    val width = smoke?.optInt("width", 0)?.takeIf { it > 0 }
        ?: optInt("width", 0).takeIf { it > 0 }
    val height = smoke?.optInt("height", 0)?.takeIf { it > 0 }
        ?: optInt("height", 0).takeIf { it > 0 }
    return if (width != null && height != null) "${width}x${height}" else null
}

private fun JSONArray.firstComponentPath(role: String): String? {
    for (index in 0 until length()) {
        val component = optJSONObject(index) ?: continue
        if (component.optString("role").equals(role, ignoreCase = true)) {
            return component.optString("path").takeIf { it.isNotBlank() }
                ?: component.optString("fileName").takeIf { it.isNotBlank() }
        }
    }
    return null
}

private fun File.safeDescendantOrNull(relativePath: String): File? {
    val normalized = relativePath.replace('\\', '/').trim().trimStart('/')
    if (normalized.isBlank()) return null
    val rootCanonical = canonicalFile
    val candidate = File(rootCanonical, normalized).canonicalFile
    return candidate.takeIf { it.path == rootCanonical.path || it.path.startsWith(rootCanonical.path + File.separator) }
}

/**
 * Public SDXL archives commonly keep all prompt-conditioning assets in a
 * chipset-neutral nested directory. The manifest path is authoritative; a
 * bounded unique-directory fallback keeps older imported bundles usable.
 */
internal fun resolveSdxlQnnConditioningRoot(bundleRoot: File): File {
    val root = bundleRoot.canonicalFile
    require(root.isDirectory) { "SDXL QNN bundle directory is missing." }
    if (root.hasCompleteSdxlQnnConditioningAssets()) return root

    val manifestFile = File(root, "manifest.json").takeIf(File::isFile)
        ?: root.findDescendantFile("manifest.json")
    val manifestDirectories = manifestFile
        ?.let { file -> runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() }
        ?.optJSONArray("components")
        ?.let { components ->
            buildList {
                for (index in 0 until components.length()) {
                    val path = components.optJSONObject(index)
                        ?.optString("path")
                        ?.takeIf(String::isNotBlank)
                        ?: continue
                    val component = root.safeDescendantOrNull(path) ?: continue
                    if (component.name in SDXL_QNN_CONDITIONING_ASSET_NAMES) {
                        component.parentFile?.let(::add)
                    }
                }
            }
        }
        .orEmpty()

    val candidates = (manifestDirectories + root.walkTopDown()
        .maxDepth(5)
        .filter(File::isDirectory)
        .filter(File::hasCompleteSdxlQnnConditioningAssets)
        .toList())
        .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .filter { it.path.startsWith(root.path + File.separator) }
        .distinctBy(File::getPath)
        .filter(File::hasCompleteSdxlQnnConditioningAssets)
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }

    return candidates.firstOrNull()
        ?: error(
            "SDXL QNN bundle requires clip.mnn, clip_2.mnn(+weight), tokenizer.json, " +
                "token_emb*.bin and pos_emb*.bin in one component directory."
        )
}

private fun File.hasCompleteSdxlQnnConditioningAssets(): Boolean =
    SDXL_QNN_CONDITIONING_ASSET_NAMES.all { name -> File(this, name).isFile }

private val SDXL_QNN_CONDITIONING_ASSET_NAMES = setOf(
    "clip.mnn",
    "clip_2.mnn",
    "clip_2.mnn.weight",
    "tokenizer.json",
    "token_emb.bin",
    "token_emb_2.bin",
    "pos_emb.bin",
    "pos_emb_2.bin"
)

private const val SDXL_QNN_CONDITIONING_GRAPH_EVIDENCE = "clip.mnn+clip_2.mnn"
private val SDXL_QNN_CONDITIONING_REQUIRED_EXECUTION_ASSET_NAMES = listOf(
    "clip.mnn",
    "clip_2.mnn",
    "clip_2.mnn.weight"
)
private const val SDXL_QNN_OPTIONAL_CLIP1_WEIGHT_NAME = "clip.mnn.weight"

private val READINESS_MODEL_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "sft", "mnn", "bin", "ctx", "qnn")

private fun LocalImageModelFamily.requiresCompanionComponents(): Boolean =
    when (this) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.WAN -> true
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> false
    }

private fun LocalImageModelFamily.requiredCompanionComponentHint(): String =
    when (this) {
        LocalImageModelFamily.FLUX -> "VAE/AE（如 flux2_ae、ae.sft 或 ae.safetensors）和 Qwen3 4B 文本编码器/LLM"
        LocalImageModelFamily.QWEN_IMAGE -> "Qwen-Image VAE/AE 和 Qwen2.5-VL 文本编码器/LLM"
        LocalImageModelFamily.Z_IMAGE -> "VAE/AE 和 Qwen3 文本编码器/LLM"
        LocalImageModelFamily.LONGCAT_IMAGE -> "FLUX VAE/AE 和 Qwen2.5-VL 文本编码器/LLM"
        LocalImageModelFamily.SANA -> "connector、projector、transformer、VAE decoder 和 Sana LLM"
        LocalImageModelFamily.SD_TURBO -> "SD-Turbo 完整 checkpoint"
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.WAN -> "VAE/AE 和文本编码器/LLM"
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> "VAE/AE 和文本编码器/LLM"
    }

private fun File.isVaeComponentFile(): Boolean {
    val lower = invariantSeparatorsPath.lowercase()
    return "vae" in lower ||
        lower.endsWith("/ae.sft") ||
        lower.endsWith("/ae.safetensors") ||
        lower.endsWith("_ae.safetensors") ||
        lower.endsWith("-ae.safetensors") ||
        lower.endsWith("_ae.gguf") ||
        lower.endsWith("-ae.gguf")
}

private fun File.isTextEncoderComponentFile(): Boolean {
    val lower = invariantSeparatorsPath.lowercase()
    return "text_encoder" in lower ||
        "text-encoder" in lower ||
        "text_encoders" in lower ||
        "t5xxl" in lower ||
        "t5-xxl" in lower ||
        "umt5" in lower ||
        "qwen2.5" in lower ||
        "qwen3" in lower ||
        "qwen_3" in lower ||
        "qwen-3" in lower ||
        "mistral" in lower ||
        "gemma" in lower ||
        "llm" in lower
}

private fun File.findDescendantFile(fileName: String): File? =
    walkTopDown().firstOrNull { it.isFile && it.name.equals(fileName, ignoreCase = true) }

private fun String.isPrimaryImageModelName(): Boolean {
    val lower = lowercase()
    val extension = substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("gguf", "safetensors", "sft", "ckpt", "pth", "pt", "onnx", "mnn", "bin", "ctx", "qnn") &&
        (
        lower == "unet.mnn" ||
                lower == "unet.ctx" ||
                lower == "unet.qnn" ||
                lower.endsWith("unet.bin") ||
                lower.endsWith("unet.ctx") ||
                lower.endsWith("unet.qnn") ||
                lower.endsWith("diffusion.bin") ||
                lower.endsWith("diffusion.ctx") ||
                lower.endsWith("diffusion.qnn") ||
                lower.endsWith("transformer.bin") ||
                lower.endsWith("transformer.ctx") ||
                lower.endsWith("transformer.qnn") ||
                lower.endsWith("context.bin") ||
                lower.endsWith("context.ctx") ||
                lower.endsWith("context.qnn") ||
                lower == "transformer.mnn" ||
                lower == "diffusion.mnn" ||
                "qnn" in lower ||
            "diffusion" in lower ||
                "z-image" in lower ||
                "z_image" in lower ||
                "qwen-image" in lower ||
                "qwen_image" in lower ||
                "glm-image" in lower ||
                "glm_image" in lower ||
                "longcat-image" in lower ||
                "longcat_image" in lower ||
                "dreamlite" in lower ||
                "flux" in lower ||
                "sd-turbo" in lower ||
                "sd_turbo" in lower
            ) &&
        "vae" !in lower &&
        "clip" !in lower &&
        "text" !in lower &&
        "tokenizer" !in lower &&
        "encoder" !in lower
}

private fun String.toImageDimensions(family: LocalImageModelFamily): Pair<Int, Int> {
    val parts = lowercase().split("x", "×").mapNotNull { it.trim().toIntOrNull() }
    if (parts.size >= 2) {
        return parts[0].coerceAtLeast(64) to parts[1].coerceAtLeast(64)
    }
    return when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SDXL -> 1024 to 1024
        LocalImageModelFamily.WAN -> 832 to 480
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> 512 to 512
    }
}

internal fun Pair<Int, Int>.fastLocalDimensions(family: LocalImageModelFamily): Pair<Int, Int> {
    val maxDimension = when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15 -> 512
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.WAN,
        LocalImageModelFamily.CUSTOM -> 384
    }
    val largest = maxOf(first, second)
    if (largest <= maxDimension) return first.alignImageDimension() to second.alignImageDimension()
    return ((first * maxDimension) / largest).alignImageDimension() to
        ((second * maxDimension) / largest).alignImageDimension()
}

private fun Int.alignImageDimension(): Int =
    (((this.coerceAtLeast(256) + 32) / 64) * 64).coerceIn(256, 1536)

internal fun resolveMnnDiffusionDimensions(
    defaultWidth: Int,
    defaultHeight: Int,
    requestedWidth: Int?,
    requestedHeight: Int?
): Pair<Int, Int> {
    requestedWidth?.let { width ->
        require(width == defaultWidth) {
            "MNN-Diffusion 当前模型只支持 ${defaultWidth}x${defaultHeight}，不能请求 ${width}x${requestedHeight ?: defaultHeight}。"
        }
    }
    requestedHeight?.let { height ->
        require(height == defaultHeight) {
            "MNN-Diffusion 当前模型只支持 ${defaultWidth}x${defaultHeight}，不能请求 ${requestedWidth ?: defaultWidth}x${height}。"
        }
    }
    return defaultWidth to defaultHeight
}

internal fun resolveStableDiffusionDimensions(
    defaultWidth: Int,
    defaultHeight: Int,
    requestedWidth: Int?,
    requestedHeight: Int?
): Pair<Int, Int> {
    val width = requestedWidth ?: defaultWidth
    val height = requestedHeight ?: defaultHeight
    require(width in 256..1536 && width % 64 == 0) {
        "stable-diffusion.cpp width must be a multiple of 64 between 256 and 1536."
    }
    require(height in 256..1536 && height % 64 == 0) {
        "stable-diffusion.cpp height must be a multiple of 64 between 256 and 1536."
    }
    return width to height
}

internal fun resolveStableDiffusionSteps(
    family: LocalImageModelFamily,
    requestedSteps: Int?
): Int {
    val steps = requestedSteps ?: defaultStepsFor(family)
    require(steps in 1..50) { "stable-diffusion.cpp steps must be between 1 and 50." }
    return steps
}

internal fun resolveStableDiffusionThreads(requestedThreads: Int?, defaultThreads: Int): Int {
    val threads = requestedThreads ?: defaultThreads
    require(threads in 1..64) { "stable-diffusion.cpp threads must be between 1 and 64." }
    return threads
}

internal fun resolveStableDiffusionFiniteControl(
    name: String,
    requested: Double?,
    defaultValue: Double
): Double {
    val value = requested ?: defaultValue
    require(value.isFinite()) { "stable-diffusion.cpp $name must be finite." }
    when (name) {
        "distilledGuidance" -> require(value in 0.0..30.0) {
            "stable-diffusion.cpp distilledGuidance must be in [0, 30]."
        }
        "flowShift" -> require(value == -1.0 || value in 0.0..100.0) {
            "stable-diffusion.cpp flowShift must be -1 (model default) or in [0, 100]."
        }
    }
    return value
}

internal fun resolveStableDiffusionBackendMode(requestedBackendMode: String?): String {
    val backend = requestedBackendMode?.trim()?.lowercase() ?: "cpu"
    require(backend == "cpu") {
        "stable-diffusion.cpp Android backend currently supports only backendMode=cpu."
    }
    return backend
}

internal fun stableDiffusionPreviewCandidates(outputFile: File): List<File> = buildList {
    repeat(2) { slot ->
        val preview = File(outputFile.absolutePath + ".preview-$slot.png")
        add(preview)
        add(File(preview.absolutePath + ".part"))
    }
}

private val STABLE_DIFFUSION_OWNED_OUTPUT_NAME = Regex(
    "^sdcpp-(?:[0-9]{1,19}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
        "[0-9a-f]{4}-[0-9a-f]{12})(?:-[2-8])?\\.png" +
        "(?:\\.part|\\.preview-[01]\\.png(?:\\.part)?)?$"
)

internal fun stableDiffusionRequestOutputCandidates(outputFile: File): List<File> {
    val parent = outputFile.absoluteFile.parentFile ?: return listOf(outputFile.absoluteFile)
    val fileName = outputFile.name
    val stem = fileName.removeSuffix(".png")
    if (stem == fileName) return listOf(outputFile.absoluteFile)
    return buildList {
        for (index in 1..STABLE_DIFFUSION_OUTPUT_MAX_BATCH_COUNT) {
            val output = if (index == 1) {
                File(parent, fileName)
            } else {
                File(parent, "$stem-$index.png")
            }
            add(output)
            add(File(output.absolutePath + ".part"))
        }
        addAll(stableDiffusionPreviewCandidates(File(parent, fileName)))
    }.distinctBy(File::getAbsolutePath)
}

internal fun cleanupStableDiffusionRequestOutputs(outputFile: File) {
    stableDiffusionRequestOutputCandidates(outputFile).forEach { candidate ->
        runCatching { candidate.delete() }
    }
}

internal fun pruneStaleStableDiffusionOutputs(
    outputDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
    maxAgeMillis: Long = STABLE_DIFFUSION_OUTPUT_MAX_AGE_MS
) {
    require(maxAgeMillis > 0L) { "stable-diffusion.cpp stale output age must be positive." }
    outputDirectory.listFiles().orEmpty().forEach { candidate ->
        val modifiedAt = candidate.lastModified()
        if (candidate.isFile &&
            STABLE_DIFFUSION_OWNED_OUTPUT_NAME.matches(candidate.name) &&
            modifiedAt > 0L &&
            nowMillis >= modifiedAt &&
            nowMillis - modifiedAt >= maxAgeMillis
        ) {
            runCatching { candidate.delete() }
        }
    }
}

private val MNN_DIFFUSION_OWNED_OUTPUT_NAME = Regex(
    "^mnn-diffusion-(?:[0-9]{1,19}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
        "[0-9a-f]{4}-[0-9a-f]{12})\\.png(?:\\.part)?$"
)

internal fun mnnDiffusionRequestOutputCandidates(outputFile: File): List<File> = listOf(
    outputFile.absoluteFile,
    File(outputFile.absolutePath + ".part")
)

internal fun cleanupMnnDiffusionRequestOutputs(outputFile: File) {
    mnnDiffusionRequestOutputCandidates(outputFile).forEach { candidate ->
        runCatching { candidate.delete() }
    }
}

internal fun pruneStaleMnnDiffusionOutputs(
    outputDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
    maxAgeMillis: Long = MNN_DIFFUSION_OUTPUT_MAX_AGE_MS
) {
    require(maxAgeMillis > 0L) { "MNN-Diffusion stale output age must be positive." }
    outputDirectory.listFiles().orEmpty().forEach { candidate ->
        val modifiedAt = candidate.lastModified()
        if (candidate.isFile &&
            MNN_DIFFUSION_OWNED_OUTPUT_NAME.matches(candidate.name) &&
            modifiedAt > 0L &&
            nowMillis >= modifiedAt &&
            nowMillis - modifiedAt >= maxAgeMillis
        ) {
            runCatching { candidate.delete() }
        }
    }
}

internal fun resolveStableDiffusionSampleMethod(requestedSampleMethod: String?): String {
    val method = requestedSampleMethod?.trim()?.lowercase() ?: "euler"
    require(method in STABLE_DIFFUSION_SAMPLE_METHODS) {
        "Unsupported stable-diffusion.cpp sample method '$method'."
    }
    return method
}

internal fun stableDiffusionNativeSampleMethodMatches(
    scheduler: ImageSchedulerAlgorithm,
    nativeSampleMethod: String
): Boolean {
    val actual = nativeSampleMethod.trim().lowercase()
    return actual in when (scheduler) {
        ImageSchedulerAlgorithm.EULER -> setOf("euler")
        ImageSchedulerAlgorithm.EULER_A -> setOf("euler_a")
        ImageSchedulerAlgorithm.DPMPP_2M -> setOf("dpmpp_2m", "dpm++2m")
        ImageSchedulerAlgorithm.DDIM -> setOf("ddim", "ddim_trailing")
        ImageSchedulerAlgorithm.LCM -> setOf("lcm")
        ImageSchedulerAlgorithm.FLOW_MATCH -> setOf("flow_match", "euler")
        ImageSchedulerAlgorithm.PNDM_PLMS -> emptySet()
    }
}

private val STABLE_DIFFUSION_SAMPLE_METHODS = setOf(
    "euler",
    "euler_a",
    "heun",
    "dpm2",
    "dpm++2s_a",
    "dpm++2m",
    "dpm++2mv2",
    "ipndm",
    "ipndm_v",
    "lcm",
    "ddim_trailing",
    "tcd",
    "res_multistep",
    "res_2s",
    "er_sde",
    "euler_cfg_pp",
    "euler_a_cfg_pp",
    "euler_ge"
)

internal fun resolveMnnDiffusionThreads(requestedThreads: Int?, defaultThreads: Int): Int {
    val threads = requestedThreads ?: defaultThreads
    require(threads in 1..64) { "MNN-Diffusion threads must be between 1 and 64." }
    return threads
}

internal fun resolveMnnDiffusionMemoryMode(requestedMemoryMode: Int?): Int {
    val memoryMode = requestedMemoryMode ?: 0
    require(memoryMode in 0..2) { "MNN-Diffusion memoryMode must be 0, 1, or 2." }
    return memoryMode
}

internal fun mnnDiffusionControlAuditJson(params: JSONObject): JSONObject = JSONObject().apply {
    listOf(
        "family",
        "width",
        "height",
        "steps",
        "threads",
        "seed",
        "cfgScale",
        "useCfg",
        "sampleMethod",
        "runner",
        "backendMode",
        "memoryMode"
    ).forEach { key ->
        if (params.has(key)) put(key, params.get(key))
    }
}

internal fun verifyStableDiffusionResultControl(
    result: JSONObject,
    key: String,
    expected: Double
) {
    require(result.has(key) && !result.isNull(key)) {
        "stable-diffusion.cpp native result did not report $key."
    }
    val actual = result.get(key)
    require(actual is Number && kotlin.math.abs(actual.toDouble() - expected) <= 1e-6) {
        "stable-diffusion.cpp native result reported $key=$actual, expected $expected."
    }
}

internal fun verifyStableDiffusionDistilledGuidanceResult(
    result: JSONObject,
    requested: Double,
    specified: Boolean
) {
    verifyStableDiffusionResultControl(result, "requestedDistilledGuidance", requested)
    require(
        result.has("distilledGuidanceSpecified") &&
            result.get("distilledGuidanceSpecified") is Boolean &&
            result.getBoolean("distilledGuidanceSpecified") == specified
    ) {
        "stable-diffusion.cpp did not preserve whether distilled guidance was explicitly requested."
    }
    require(
        result.has("distilledGuidanceApplied") &&
            result.get("distilledGuidanceApplied") is Boolean
    ) {
        "stable-diffusion.cpp did not report distilled-guidance applicability."
    }
    if (result.getBoolean("distilledGuidanceApplied")) {
        verifyStableDiffusionResultControl(result, "distilledGuidance", requested)
    } else {
        require(result.has("distilledGuidance") && result.isNull("distilledGuidance")) {
            "stable-diffusion.cpp claimed an inert distilled-guidance value as effective."
        }
    }
}

internal fun verifyStableDiffusionFlowShiftResult(
    result: JSONObject,
    requested: Double,
    specified: Boolean,
    expectApplied: Boolean
) {
    verifyStableDiffusionResultControl(result, "requestedFlowShift", requested)
    require(
        result.has("flowShiftSpecified") &&
            result.get("flowShiftSpecified") is Boolean &&
            result.getBoolean("flowShiftSpecified") == specified
    ) {
        "stable-diffusion.cpp did not preserve whether flow shift was explicitly requested."
    }
    require(result.has("flowShiftApplied") && result.get("flowShiftApplied") is Boolean) {
        "stable-diffusion.cpp did not report flow-shift applicability."
    }
    val applied = result.getBoolean("flowShiftApplied")
    require(applied == expectApplied) {
        "stable-diffusion.cpp flow-shift applicability conflicts with the resolved prediction mode."
    }
    require(result.has("dynamicFlowShift") && result.get("dynamicFlowShift") is Boolean) {
        "stable-diffusion.cpp did not report whether flow shift was dynamically resolved."
    }
    val dynamic = result.getBoolean("dynamicFlowShift")
    if (!applied) {
        require(!dynamic && result.has("flowShift") && result.isNull("flowShift")) {
            "stable-diffusion.cpp claimed an inert flow shift as effective."
        }
        return
    }
    require(result.has("flowShift") && !result.isNull("flowShift")) {
        "stable-diffusion.cpp did not report the effective flow shift."
    }
    val effective = result.get("flowShift")
    require(effective is Number && effective.toDouble().isFinite() && effective.toDouble() >= 0.0) {
        "stable-diffusion.cpp reported an invalid effective flow shift."
    }
    if (dynamic) {
        require(requested < 0.0) {
            "stable-diffusion.cpp dynamically replaced an explicit flow-shift override."
        }
    } else if (requested >= 0.0) {
        require(kotlin.math.abs(effective.toDouble() - requested) <= 1e-6) {
            "stable-diffusion.cpp effective flow shift differs from the configured value."
        }
    }
}

internal data class LocalImageSmokePixelQuality(
    val passed: Boolean,
    val message: String,
    val lumaDynamicRange: Int,
    val distinctLumaBins: Int,
    val meanHorizontalDelta: Double,
    val meanVerticalDelta: Double
)

/**
 * Cheap deterministic corruption gate for product smoke tests.  This proves
 * that a decoder returned a non-trivial image, not that the prompt semantics
 * are correct; semantic/default certification deliberately remains separate.
 */
internal fun evaluateLocalImageSmokePixels(
    width: Int,
    height: Int,
    pixels: IntArray
): LocalImageSmokePixelQuality {
    require(width > 1 && height > 1 && pixels.size == width * height) {
        "Image pixels do not match the declared dimensions."
    }
    val stride = (pixels.size / 65_536).coerceAtLeast(1)
    val lumas = ArrayList<Int>((pixels.size + stride - 1) / stride)
    val occupiedBins = BooleanArray(64)
    var index = 0
    while (index < pixels.size) {
        val pixel = pixels[index]
        val red = pixel ushr 16 and 0xff
        val green = pixel ushr 8 and 0xff
        val blue = pixel and 0xff
        val luma = (red * 54 + green * 183 + blue * 19) ushr 8
        lumas += luma
        occupiedBins[(luma ushr 2).coerceIn(0, occupiedBins.lastIndex)] = true
        index += stride
    }
    lumas.sort()
    val p02 = lumas[((lumas.size - 1) * 2) / 100]
    val p98 = lumas[((lumas.size - 1) * 98) / 100]
    val dynamicRange = p98 - p02
    val distinctBins = occupiedBins.count { it }

    fun luma(pixel: Int): Int {
        val red = pixel ushr 16 and 0xff
        val green = pixel ushr 8 and 0xff
        val blue = pixel and 0xff
        return (red * 54 + green * 183 + blue * 19) ushr 8
    }

    var horizontalDelta = 0L
    var horizontalCount = 0L
    var verticalDelta = 0L
    var verticalCount = 0L
    for (y in 0 until height) {
        val row = y * width
        for (x in 1 until width) {
            horizontalDelta += kotlin.math.abs(luma(pixels[row + x]) - luma(pixels[row + x - 1]))
            horizontalCount += 1
        }
    }
    for (y in 1 until height) {
        val row = y * width
        val previous = row - width
        for (x in 0 until width) {
            verticalDelta += kotlin.math.abs(luma(pixels[row + x]) - luma(pixels[previous + x]))
            verticalCount += 1
        }
    }
    val meanHorizontal = horizontalDelta.toDouble() / horizontalCount.coerceAtLeast(1)
    val meanVertical = verticalDelta.toDouble() / verticalCount.coerceAtLeast(1)
    val reasons = buildList {
        if (dynamicRange < 24) add("luma dynamic range is too low ($dynamicRange)")
        if (distinctBins < 10) add("too few occupied luma bins ($distinctBins)")
        if (meanVertical > 12.0 && meanVertical > meanHorizontal * 3.5) {
            add("strong horizontal stripe pattern detected")
        }
    }
    return LocalImageSmokePixelQuality(
        passed = reasons.isEmpty(),
        message = if (reasons.isEmpty()) {
            "PNG pixel quality smoke passed."
        } else {
            reasons.joinToString("; ")
        },
        lumaDynamicRange = dynamicRange,
        distinctLumaBins = distinctBins,
        meanHorizontalDelta = meanHorizontal,
        meanVerticalDelta = meanVertical
    )
}

internal fun resolveMnnDiffusionBackendMode(requestedBackendMode: String?): String {
    if (requestedBackendMode == null) return "opencl"
    return when (requestedBackendMode.trim().lowercase()) {
        "opencl", "gpu" -> "opencl"
        "cpu" -> "cpu"
        else -> throw IllegalArgumentException(
            "Unsupported MNN-Diffusion backend '$requestedBackendMode'. Supported values are cpu and opencl (gpu is an alias for opencl)."
        )
    }
}

internal fun resolveMnnDiffusionRunner(
    family: LocalImageModelFamily,
    requestedRunner: String?
): String {
    val normalized = requestedRunner?.trim()?.lowercase()
    return if (family == LocalImageModelFamily.SANA) {
        when (normalized) {
            null, "sana", "sana_varp", "module" -> "sana_varp"
            else -> throw IllegalArgumentException(
                "Unsupported MNN Sana runner '$requestedRunner'. Supported values are sana_varp, sana, and module."
            )
        }
    } else {
        when (normalized) {
            null -> "direct"
            "direct", "module" -> normalized
            else -> throw IllegalArgumentException(
                "Unsupported MNN Stable Diffusion runner '$requestedRunner'. Supported values are direct and module."
            )
        }
    }
}

internal fun mnnDiffusionBackendMatches(requestedBackend: String, actualBackend: String): Boolean =
    when (requestedBackend) {
        "opencl" -> actualBackend.trim().lowercase() in setOf("opencl", "gpu")
        "cpu" -> actualBackend.trim().lowercase() == "cpu"
        else -> false
    }

private fun defaultStepsFor(family: LocalImageModelFamily): Int =
    when (family) {
        LocalImageModelFamily.Z_IMAGE -> 4
        LocalImageModelFamily.FLUX -> 4
        LocalImageModelFamily.SD_TURBO -> 1
        LocalImageModelFamily.QWEN_IMAGE -> 6
        LocalImageModelFamily.GLM_IMAGE -> 6
        LocalImageModelFamily.LONGCAT_IMAGE -> 6
        LocalImageModelFamily.DREAMLITE -> 6
        LocalImageModelFamily.SANA -> 5
        LocalImageModelFamily.SDXL -> 8
        LocalImageModelFamily.SD21 -> 8
        LocalImageModelFamily.SD15 -> 8
        LocalImageModelFamily.WAN -> 6
        LocalImageModelFamily.CUSTOM -> 6
    }

internal fun defaultCfgFor(family: LocalImageModelFamily): Double =
    when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SD_TURBO -> 1.0
        LocalImageModelFamily.QWEN_IMAGE -> 2.5
        LocalImageModelFamily.SANA -> 4.5
        LocalImageModelFamily.LONGCAT_IMAGE -> 5.0
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.WAN,
        LocalImageModelFamily.CUSTOM -> 7.0
    }

internal fun defaultStableDiffusionFlowShiftFor(profile: ImageExecutionProfile): Double {
    if (profile.scheduler.predictionType != ImagePredictionType.FLOW) return -1.0
    return when (profile.family) {
        LocalImageModelFamily.FLUX -> -1.0
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.WAN -> 3.0
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> -1.0
    }
}

private fun defaultImageSizeFor(fileName: String): String =
    when (LocalImageModelFamily.infer(fileName)) {
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE -> "768x768"
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SDXL -> "512x512"
        LocalImageModelFamily.SD_TURBO -> if ("384" in fileName) "384x384" else "512x512"
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15 -> if ("384" in fileName) "384x384" else "512x512"
        LocalImageModelFamily.WAN,
        LocalImageModelFamily.CUSTOM -> "512x512"
    }

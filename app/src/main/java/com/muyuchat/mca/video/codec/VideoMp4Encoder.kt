package com.muyuchat.mca.video.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.muyuchat.core.video.contract.VideoErrorCode
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * RGB frame spool → H.264 MP4 (proposal §8.3). V1 writes no audio track and no B frames.
 *
 * The encoder never re-runs the model: frames come from the attempt's own spool produced by the
 * fused native call. Timestamps are checked 64-bit `frameIndex * 1_000_000 / fps` starting at 0;
 * generation frame count and container duration stay separate facts. EOS drain, codec release and
 * part-file cleanup are bounded, so a stalled encoder fails the attempt instead of hanging forever.
 *
 * This class is Android-platform code; pure frame parsing/colour conversion helpers are kept
 * internal for JVM tests without a device.
 */
class VideoMp4Encoder(private val isCancelled: () -> Boolean = { false }) {

    sealed class Result {
        data class Encoded(
            val sizeBytes: Long,
            val durationUs: Long,
            val sampleCount: Int,
            val codecName: String,
            val colorFormat: Int,
        ) : Result()

        data object Cancelled : Result()

        data class Failed(val code: VideoErrorCode, val detail: String) : Result()
    }

    /**
     * Push API: [open] once, [submit] per frame as it is decoded, [close] to finish.
     *
     * The consumer that drives this runs on the thread inside the native generator, so submitting a
     * frame blocks that thread until the codec takes it — the generator cannot outrun the encoder
     * and no frame ever touches the disk.
     */
    fun open(width: Int, height: Int, fps: Int, output: File): Result? {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            return Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "编码尺寸必须是正偶数")
        }
        if (fps <= 0) return Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "帧率必须为正整数")
        output.parentFile?.mkdirs()
        if (output.exists() && !output.delete()) {
            return Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "无法清理旧的暂存编码文件")
        }
        val bitrate = (width.toLong() * height * fps * 0.12).toInt().coerceIn(1_000_000, 20_000_000)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val selected = selectEncoder(width, height)
        val created = if (selected != null) {
            runCatching { MediaCodec.createByCodecName(selected) }
                .getOrElse { return Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "无法创建 H.264 编码器 $" + "{selected}") }
        } else {
            runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }
                .getOrElse { return Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "设备没有支持 $" + "{width}x$height 的 H.264 编码器") }
        }
        val configured = runCatching {
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }.isSuccess || runCatching {
            created.reset()
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }.isSuccess
        if (!configured) {
            created.release()
            return Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "编码器 $" + "{created.name} 拒绝 YUV420 输入配置")
        }
        return try {
            created.start()
            codec = created
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            this.width = width
            this.height = height
            this.fps = fps
            this.output = output
            yuv = YuvFrameBuffer(width, height)
            lastProgressAt = System.currentTimeMillis()
            null
        } catch (error: Exception) {
            runCatching { created.stop() }
            runCatching { created.release() }
            Result.Failed(
                VideoErrorCode.ENCODER_UNAVAILABLE,
                "编码器启动失败：" + (error.message ?: error.javaClass.simpleName),
            )
        }
    }

    /** Queues one packed RGB frame. Returns false when the attempt must stop. */
    fun submit(rgb: ByteArray): Boolean {
        val codec = codec ?: return false
        val yuv = yuv ?: return false
        if (failure != null) return false
        if (isCancelled()) {
            failure = Result.Cancelled
            return false
        }
        if (rgb.size != width * height * 3) {
            failure = Result.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "帧尺寸与请求不符（$" + "{rgb.size} 字节）")
            return false
        }
        val deadline = System.currentTimeMillis() + ENCODER_STALL_TIMEOUT_MS
        while (true) {
            if (isCancelled()) {
                failure = Result.Cancelled
                return false
            }
            val inIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inIndex >= 0) {
                yuv.fillFromRgb(rgb)
                val input = codec.getInputImage(inIndex)
                    ?: run {
                        failure = Result.Failed(
                            VideoErrorCode.ENCODER_UNAVAILABLE,
                            "编码器不支持 YUV420 Image 输入（不能伪造编码通过）",
                        )
                        return false
                    }
                yuv.copyInto(input)
                val ptsUs = frameIndex.toLong() * 1_000_000L / fps
                codec.queueInputBuffer(inIndex, 0, yuv.inputSize, ptsUs, 0)
                lastPtsUs = ptsUs
                frameIndex += 1
                lastProgressAt = System.currentTimeMillis()
                drain()
                return failure == null
            }
            drain()
            if (failure != null) return false
            if (System.currentTimeMillis() - lastProgressAt > ENCODER_STALL_TIMEOUT_MS ||
                System.currentTimeMillis() > deadline
            ) {
                failure = Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "编码器长时间没有推进（输入停滞）")
                return false
            }
        }
    }

    /** Signals end of stream, drains the codec and finalises the container. */
    fun close(): Result {
        val codec = codec ?: return failure ?: Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "编码器未启动")
        val muxer = muxer ?: return Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "封装器未创建")
        failure?.let { return it }
        try {
            val deadline = System.currentTimeMillis() + ENCODER_STALL_TIMEOUT_MS
            while (!inputDone) {
                if (isCancelled()) return Result.Cancelled
                if (System.currentTimeMillis() > deadline) {
                    return Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "编码器长时间没有推进（EOS 停滞）")
                }
                val inIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inIndex >= 0) {
                    codec.queueInputBuffer(
                        inIndex,
                        0,
                        0,
                        lastPtsUs + 1_000_000L / fps,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    inputDone = true
                }
                drain()
            }
            val drainDeadline = System.currentTimeMillis() + ENCODER_STALL_TIMEOUT_MS
            while (!outputDone) {
                if (isCancelled()) return Result.Cancelled
                if (System.currentTimeMillis() > drainDeadline) {
                    return Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "编码器长时间没有推进（输出停滞）")
                }
                drain()
            }
            if (!muxerStarted || sampleCount == 0) {
                return Result.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "编码器没有产出任何视频采样")
            }
            this.muxer = null
            muxer.stop()
            muxer.release()
            val file = output!!
            // A one-sample clip has no sample delta of its own, so MediaMuxer leaves every duration
            // at 0 and players see a zero-length file. Repair the metadata instead of duplicating
            // the frame, which would change the frame count the caller asked for.
            if (sampleCount == 1) Mp4DurationRepair.repairSingleSampleDuration(file, fps.toLong())
            FileOutputStream(file, true).use { it.fd.sync() }
            if (file.length() <= 0) {
                return Result.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "编码输出为空文件")
            }
            return Result.Encoded(
                sizeBytes = file.length(),
                durationUs = lastPtsUs + 1_000_000L / fps,
                sampleCount = sampleCount,
                codecName = codec.name,
                colorFormat = yuv?.colorFormat ?: 0,
            )
        } catch (cancelled: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.Cancelled
        } catch (error: Exception) {
            android.util.Log.e("VideoMp4Encoder", "close failed", error)
            return Result.Failed(
                VideoErrorCode.ENCODER_UNAVAILABLE,
                "编码失败[close]：" + (error.message ?: error.javaClass.simpleName),
            )
        } finally {
            release()
        }
    }

    /** Releases codec and muxer without producing a result (used on cancel/failure paths). */
    fun abort() {
        release()
    }

    private fun release() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        muxer?.let { muxer ->
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
        muxer = null
        muxerStarted = false
        val file = output
        if (file != null && file.exists() && sampleCount == 0) file.delete()
    }

    private fun drain() {
        val codec = codec ?: return
        val muxer = muxer ?: return
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }

                outIndex >= 0 -> {
                    val buffer: ByteBuffer? = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        muxerStarted
                    ) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, buffer, info)
                        sampleCount += 1
                        lastProgressAt = System.currentTimeMillis()
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }

                else -> return
            }
        }
    }

    private var failure: Result? = null
    private var width = 0
    private var height = 0
    private var fps = 0
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var trackIndex = -1
    private var yuv: YuvFrameBuffer? = null
    private var output: File? = null
    private val info = MediaCodec.BufferInfo()
    private var frameIndex = 0
    private var sampleCount = 0
    private var lastPtsUs = 0L
    private var inputDone = false
    private var outputDone = false
    private var lastProgressAt = 0L

    /**
     * File-driven path kept for the built-in engine, which still spools PPM frames. Reads one frame
     * at a time, submits it and deletes it, so both paths share the same push core.
     */
    fun encode(
        frameDir: File,
        frameNames: List<String>,
        width: Int,
        height: Int,
        fps: Int,
        output: File,
    ): Result {
        if (frameNames.isEmpty()) return Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "没有可编码的视频帧")
        open(width, height, fps, output)?.let { return it }
        val rgb = ByteArray(width * height * 3)
        try {
            for (name in frameNames) {
                if (isCancelled()) return Result.Cancelled
                val read = readPpm(frameDir, name, rgb, width, height)
                    ?: return Result.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "帧文件损坏或尺寸不符：$name")
                if (!submit(read)) return failure ?: Result.Failed(VideoErrorCode.ENCODER_UNAVAILABLE, "编码失败")
                frameDir.resolve(name).delete()
            }
        } finally {
            if (failure != null) abort()
        }
        return close()
    }

    /** PPM P6 reader shared by encoder and JVM tests. Returns null on any structural mismatch. */
    internal fun readPpm(frameDir: File, name: String, target: ByteArray, width: Int, height: Int): ByteArray? {
        val file = File(frameDir, name)
        if (!file.isFile) return null
        BufferedInputStream(FileInputStream(file)).use { input ->
            if (readToken(input) != "P6") return null
            val fileWidth = readToken(input)?.toIntOrNull() ?: return null
            val fileHeight = readToken(input)?.toIntOrNull() ?: return null
            val maxValue = readToken(input)?.toIntOrNull() ?: return null
            if (fileWidth != width || fileHeight != height || maxValue != 255) return null
            var offset = 0
            while (offset < target.size) {
                val read = input.read(target, offset, target.size - offset)
                if (read <= 0) return null
                offset += read
            }
            if (input.read() != -1) return null
            return target
        }
    }

    private fun readToken(input: BufferedInputStream): String? {
        var value = -1
        while (true) {
            value = input.read()
            if (value < 0) return null
            if (value == '#'.code) {
                while (value >= 0 && value != '\n'.code) value = input.read()
                continue
            }
            if (!value.toChar().isWhitespace()) break
        }
        val builder = StringBuilder()
        while (value >= 0 && !value.toChar().isWhitespace()) {
            builder.append(value.toChar())
            value = input.read()
        }
        return if (builder.isEmpty()) null else builder.toString()
    }

    /** Prefer an encoder that advertises the exact size and flexible YUV input. */
    private fun selectEncoder(width: Int, height: Int): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                runCatching {
                    val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    capabilities.colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    ) && capabilities.videoCapabilities?.isSizeSupported(width, height) == true
                }.getOrDefault(false)
        }?.name

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val ENCODER_STALL_TIMEOUT_MS = 60_000L
    }
}

/**
 * I420 buffer with explicit stride handling. The conversion is limited-range BT.601, matching the
 * encoder's default colour space; the exact codec colour format is recorded in the run result.
 */
internal class YuvFrameBuffer(val width: Int, val height: Int) {
    val y = ByteArray(width * height)
    val u = ByteArray(((width + 1) / 2) * ((height + 1) / 2))
    val v = ByteArray(u.size)
    val inputSize: Int = y.size + u.size + v.size
    var colorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    fun fillFromRgb(rgb: ByteArray) {
        var yIndex = 0
        var chromaIndex = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val base = (row * width + col) * 3
                val r = rgb[base].toInt() and 0xff
                val g = rgb[base + 1].toInt() and 0xff
                val b = rgb[base + 2].toInt() and 0xff
                y[yIndex++] = clamp(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
            }
        }
        for (row in 0 until height step 2) {
            for (col in 0 until width step 2) {
                var r = 0
                var g = 0
                var b = 0
                var samples = 0
                for (dy in 0 until 2) {
                    for (dx in 0 until 2) {
                        val yRow = row + dy
                        val xCol = col + dx
                        if (yRow >= height || xCol >= width) continue
                        val base = (yRow * width + xCol) * 3
                        r += rgb[base].toInt() and 0xff
                        g += rgb[base + 1].toInt() and 0xff
                        b += rgb[base + 2].toInt() and 0xff
                        samples += 1
                    }
                }
                r /= samples
                g /= samples
                b /= samples
                u[chromaIndex] = clamp(((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
                v[chromaIndex] = clamp(((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
                chromaIndex += 1
            }
        }
    }

    fun copyInto(image: android.media.Image) {
        val planes = image.planes
        if (planes.size < 3) throw IllegalStateException("YUV Image did not expose three planes")
        copyPlane(planes[0], y, width, height)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        copyPlane(planes[1], u, chromaWidth, chromaHeight)
        copyPlane(planes[2], v, chromaWidth, chromaHeight)
    }

    private fun copyPlane(plane: android.media.Image.Plane, data: ByteArray, planeWidth: Int, planeHeight: Int) {
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var source = 0
        for (row in 0 until planeHeight) {
            val rowStart = row * rowStride
            for (col in 0 until planeWidth) {
                if (source < data.size) buffer.put(rowStart + col * pixelStride, data[source++])
            }
        }
    }

    private fun clamp(value: Int): Byte = value.coerceIn(0, 255).toByte()
}

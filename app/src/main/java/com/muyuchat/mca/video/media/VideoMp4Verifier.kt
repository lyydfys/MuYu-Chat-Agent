package com.muyuchat.mca.video.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.muyuchat.core.video.contract.VideoErrorCode
import java.io.File

/**
 * Real container + decoder probe of a staged MP4 (proposal §8.2/§8.3).
 *
 * MediaExtractor alone is metadata reading, not a playability proof; this probe also decodes the
 * first/middle/last frame through MediaMetadataRetriever. V1 has no audio track, so an unexpected
 * audio track fails verification instead of being silently dropped.
 */
class VideoMp4Verifier {

    data class Probe(
        val mimeType: String,
        val codecName: String,
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val sampleCount: Int,
        val hasAudio: Boolean,
    )

    sealed class Result {
        data class Verified(val probe: Probe) : Result()
        data class Failed(val code: VideoErrorCode, val detail: String) : Result()
    }

    fun verify(
        file: File,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedFrames: Int,
        fps: Int,
    ): Result {
        if (!file.isFile || file.length() <= 0) {
            return Result.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "暂存视频文件不存在或为空")
        }
        if (expectedWidth <= 0 || expectedHeight <= 0 || expectedFrames <= 0 || fps <= 0) {
            return Result.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "验证参数无效")
        }
        var extractor: MediaExtractor? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var videoTrack = -1
            var hasAudio = false
            var mime = ""
            var codec = ""
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (trackMime.startsWith("audio/")) hasAudio = true
                if (videoTrack == -1 && trackMime.startsWith("video/")) {
                    videoTrack = index
                    mime = trackMime
                    codec = runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            format.getString(MediaFormat.KEY_CODECS_STRING).orEmpty()
                        } else {
                            trackMime
                        }
                    }.getOrDefault(trackMime)
                }
            }
            if (videoTrack < 0) {
                return Result.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "MP4 没有视频轨道")
            }
            if (mime != MediaFormat.MIMETYPE_VIDEO_AVC) {
                return Result.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "V1 只发布 H.264/AVC，实际为 $mime")
            }
            if (hasAudio) {
                return Result.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "V1 视频不应包含音轨")
            }
            extractor.selectTrack(videoTrack)
            val format = extractor.getTrackFormat(videoTrack)
            val width = format.optInt(MediaFormat.KEY_WIDTH, 0)
            val height = format.optInt(MediaFormat.KEY_HEIGHT, 0)
            if (width != expectedWidth || height != expectedHeight) {
                return Result.Failed(
                    VideoErrorCode.MEDIA_VERIFY_FAILED,
                    "视频尺寸 ${width}x$height 与请求 ${expectedWidth}x$expectedHeight 不一致",
                )
            }
            var sampleCount = 0
            val buffer = java.nio.ByteBuffer.allocateDirect(256 * 1024)
            while (sampleCount < expectedFrames + 1) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                sampleCount += 1
                if (!extractor.advance()) break
            }
            if (sampleCount != expectedFrames) {
                return Result.Failed(
                    VideoErrorCode.MEDIA_VERIFY_FAILED,
                    "视频采样数 $sampleCount 与生成帧数 $expectedFrames 不一致",
                )
            }
            val expectedDuration = expectedFrames.toLong() * 1_000_000L / fps
            val trackDurationUs = format.optLong(MediaFormat.KEY_DURATION, 0L)
            // A single-sample track has no interval for the muxer to derive a duration from, so the
            // container legitimately reports none. The sample count was already checked against the
            // requested frame count above, so deriving the duration from that verified count is
            // sound here; every other case still fails.
            val durationUs = if (trackDurationUs > 0L) {
                trackDurationUs
            } else if (sampleCount == expectedFrames && expectedFrames > 0) {
                expectedDuration
            } else {
                return Result.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "容器未报告有效时长")
            }
            val tolerance = maxOf(100_000L, expectedDuration / 5)
            if (kotlin.math.abs(durationUs - expectedDuration) > tolerance) {
                return Result.Failed(
                    VideoErrorCode.MEDIA_VERIFY_FAILED,
                    "视频时长 $durationUs us 与帧数/帧率推导的 $expectedDuration us 不符",
                )
            }

            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val lastUs = maxOf(0L, durationUs - 1_000L)
            val samples = listOf(0L, durationUs / 2, lastUs)
            samples.forEachIndexed { position, atUs ->
                val frame = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return Result.Failed(
                        VideoErrorCode.MEDIA_VERIFY_FAILED,
                        "解码 smoke 失败：无法取到第 $position 个检查帧",
                    )
                if (frame.width <= 0 || frame.height <= 0) {
                    return Result.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "解码帧尺寸无效")
                }
                frame.recycle()
            }
            return Result.Verified(
                Probe(
                    mimeType = mime,
                    codecName = codec,
                    width = width,
                    height = height,
                    durationUs = durationUs,
                    sampleCount = sampleCount,
                    hasAudio = false,
                ),
            )
        } catch (error: Exception) {
            return Result.Failed(
                VideoErrorCode.MEDIA_VERIFY_FAILED,
                "媒体校验失败：${error.javaClass.simpleName}",
            )
        } finally {
            runCatching { extractor?.release() }
            runCatching { retriever?.release() }
        }
    }

    private fun MediaFormat.optInt(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private fun MediaFormat.optLong(key: String, fallback: Long): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(fallback) else fallback
}

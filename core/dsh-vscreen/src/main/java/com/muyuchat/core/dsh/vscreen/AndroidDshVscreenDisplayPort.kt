package com.muyuchat.core.dsh.vscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android display/frame adapter backed by an app-owned `MediaProjection`.
 *
 * Why not `DisplayManager.createVirtualDisplay` or a shell helper: that entry point is gated by the
 * signature `CAPTURE_VIDEO_OUTPUT` permission (or a MediaProjection token), so an app uid and a
 * root/Shizuku shell both fail, and `cmd display` has no add-display subcommand. The supported way
 * for an app to own a real virtual display is the user-granted screen-capture projection:
 *
 * 1. [DshVscreenProjectionHolder] holds the projection the user consented to;
 * 2. this adapter creates a `PUBLIC` virtual display on the projection with an [ImageReader] surface,
 *    so the display has a real display id and other apps can be launched onto it;
 * 3. [AndroidDshVscreenDisplayHandle.latestFrame] reads and PNG-encodes the newest frame;
 * 4. [AndroidDshVscreenDisplayHandle.release] releases the display and its reader but deliberately
 *    does **not** stop the projection, which stays owned by the holder across create/close cycles.
 *
 * Start failures are typed and honest: no consent ⇒ `consent_required`; a rejected projection ⇒
 * `privilege_unavailable`; anything else ⇒ `start_failed`. Nothing falls back to a fake display.
 *
 * Device status: no run of this path happens in the source-only environment, so real frame,
 * orientation, touch and teardown evidence is `NOT_RUN` until it is exercised on a device.
 */
class AndroidDshVscreenDisplayPort(
    private val projectionSource: DshVscreenProjectionSource,
    private val onDisplayCreated: () -> Unit = {}
) : DshVscreenDisplayPort {

    override suspend fun start(geometry: DshVscreenGeometry): DshVscreenDisplayHandle =
        withContext(Dispatchers.IO) {
            val projection = when (val access = projectionSource.acquire()) {
                is DshVscreenProjectionAccess.Granted -> access.projection

                is DshVscreenProjectionAccess.Unavailable -> throw DshVscreenDisplayStartException(
                    access.code,
                    access.detail.take(DshVscreenLimits.MAX_MESSAGE_CHARS)
                )
            }
            val reader = try {
                ImageReader.newInstance(
                    geometry.width,
                    geometry.height,
                    PixelFormat.RGBA_8888,
                    MAX_IMAGES
                )
            } catch (failure: RuntimeException) {
                throw DshVscreenDisplayStartException(
                    DshVscreenErrorCode.START_FAILED,
                    "the frame reader could not be created"
                )
            }
            val frameThread = HandlerThread(FRAME_THREAD_NAME).apply { start() }
            val display = try {
                projection.createVirtualDisplay(
                    DISPLAY_NAME,
                    geometry.width,
                    geometry.height,
                    geometry.dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    reader.surface,
                    object : VirtualDisplay.Callback() {},
                    Handler(frameThread.looper)
                )
            } catch (security: SecurityException) {
                releaseQuietly(reader, frameThread)
                throw DshVscreenDisplayStartException(
                    DshVscreenErrorCode.PRIVILEGE_UNAVAILABLE,
                    "the system rejected the virtual display for this projection (SecurityException)"
                )
            } catch (failure: RuntimeException) {
                releaseQuietly(reader, frameThread)
                throw DshVscreenDisplayStartException(
                    DshVscreenErrorCode.START_FAILED,
                    "MediaProjection.createVirtualDisplay was rejected (${failure.javaClass.simpleName})"
                )
            }
            if (display == null) {
                releaseQuietly(reader, frameThread)
                throw DshVscreenDisplayStartException(
                    DshVscreenErrorCode.START_FAILED,
                    "MediaProjection returned no virtual display"
                )
            }
            if (display.display == null || display.display.displayId < 0) {
                runCatching { display.release() }
                releaseQuietly(reader, frameThread)
                throw DshVscreenDisplayStartException(
                    DshVscreenErrorCode.START_FAILED,
                    "the virtual display has no usable display id"
                )
            }
            onDisplayCreated()
            AndroidDshVscreenDisplayHandle(display, reader, frameThread, geometry)
        }

    private fun releaseQuietly(reader: ImageReader, frameThread: HandlerThread) {
        runCatching { reader.close() }
        frameThread.quitSafely()
    }

    private companion object {
        const val DISPLAY_NAME = "dsh-vscreen"
        const val FRAME_THREAD_NAME = "dsh-vscreen-frames"

        /** Two buffers are enough for "latest frame" and keep GPU memory bounded. */
        const val MAX_IMAGES = 2
    }
}

private class AndroidDshVscreenDisplayHandle(
    private val display: VirtualDisplay,
    private val reader: ImageReader,
    private val frameThread: HandlerThread,
    override val geometry: DshVscreenGeometry
) : DshVscreenDisplayHandle {

    private val released = AtomicBoolean(false)

    override val displayId: Int = display.display.displayId

    override suspend fun latestFrame(timeoutMillis: Long): DshVscreenFrame? = withContext(Dispatchers.IO) {
        if (released.get()) return@withContext null
        val image: Image? = try {
            reader.acquireLatestImage()
        } catch (failure: RuntimeException) {
            null
        }
        if (image == null) return@withContext null
        try {
            encode(image)
        } catch (failure: RuntimeException) {
            null
        } finally {
            runCatching { image.close() }
        }
    }

    override suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { display.release() }
        runCatching { reader.close() }
        frameThread.quitSafely()
    }

    private fun encode(image: Image): DshVscreenFrame? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val paddedWidth = rowStride / pixelStride
        if (paddedWidth < geometry.width) return null
        val padded = Bitmap.createBitmap(paddedWidth, geometry.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = if (paddedWidth == geometry.width) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, geometry.width, geometry.height)
        }
        val output = ByteArrayOutputStream()
        val compressed = cropped.compress(Bitmap.CompressFormat.PNG, 100, output)
        if (!compressed) {
            padded.recycle()
            if (cropped !== padded) cropped.recycle()
            return null
        }
        val bytes = output.toByteArray()
        padded.recycle()
        if (cropped !== padded) cropped.recycle()
        return DshVscreenFrame(
            format = DshVscreenFrameFormat.PNG,
            bytes = bytes,
            width = geometry.width,
            height = geometry.height,
            capturedAtMillis = System.currentTimeMillis()
        )
    }
}

/**
 * Persists frames under `cacheDir/vscreen` for the Node tool package to read and prunes old files
 * so a long session cannot grow the cache without bound.
 */
class AndroidDshVscreenFrameSink(private val context: Context) : DshVscreenFrameSink {

    override suspend fun persist(frame: DshVscreenFrame, displayId: Int): DshVscreenPersistedFrame =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "vscreen").apply { mkdirs() }
            prune(directory)
            val file = File(
                directory,
                "frame-" + displayId + "-" + System.currentTimeMillis() + "." + frame.format.extension
            )
            file.writeBytes(frame.bytes)
            DshVscreenPersistedFrame(
                path = file.absolutePath,
                imageWidth = frame.width,
                imageHeight = frame.height
            )
        }

    private fun prune(directory: File) {
        val files = directory.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CACHED_FRAMES).forEach { stale -> stale.delete() }
    }

    private companion object {
        const val MAX_CACHED_FRAMES = 8
    }
}

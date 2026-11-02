package com.muyuchat.core.dsh.a11y.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import com.muyuchat.core.dsh.a11y.policy.A11yLimits
import com.muyuchat.core.dsh.a11y.policy.A11yOutcome
import com.muyuchat.core.dsh.a11y.policy.A11yRejection
import com.muyuchat.core.dsh.a11y.policy.A11yRect
import com.muyuchat.core.dsh.a11y.policy.A11yScreenGeometry
import com.muyuchat.core.dsh.a11y.policy.A11yScreenshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * `/screenshot` capture through `AccessibilityService.takeScreenshot` (API 30+).
 *
 * Honest limits:
 * - below API 30 the framework has no accessibility screenshot API, so the request is rejected with
 *   `CAPABILITY_UNAVAILABLE` instead of returning a fake path;
 * - a secure window (`FLAG_SECURE`) makes the platform fail the capture; that failure is surfaced;
 * - password fields are blacked out on the bitmap before it is written, and the number of masked
 *   regions is reported (`redactedRegions`), so pixel redaction is observable rather than assumed;
 * - the PNG is written inside the app's private files directory (same UID as the DSH Node process),
 *   which avoids the reference's cross-package EACCES failure; a file above
 *   [A11yLimits.MAX_SCREENSHOT_BYTES] is deleted and reported as `TOO_LARGE`.
 */
internal object DshA11yScreenshotCapture {

    suspend fun capture(
        service: AccessibilityService,
        displayId: Int,
        grid: Int,
        geometry: A11yScreenGeometry,
        mainHandler: Handler
    ): A11yOutcome {
        if (Build.VERSION.SDK_INT < 30) {
            return A11yOutcome.Rejected(
                A11yRejection(
                    com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE,
                    "screenshot requires Android 11 (API 30); current API is ${Build.VERSION.SDK_INT}, use /dump instead"
                )
            )
        }
        val passwordRects = DshA11yTreeReader.passwordRects(service)
        val bitmap = suspendCancellableCoroutine<Bitmap?> { continuation ->
            val executor = Executor { command -> mainHandler.post(command) }
            try {
                service.takeScreenshot(
                    displayId,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val software = runCatching {
                                val hardware = result.hardwareBuffer
                                val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                                val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, true)
                                wrapped?.recycle()
                                hardware.close()
                                copy
                            }.getOrNull()
                            if (continuation.isActive) continuation.resume(software)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resume(null)
            }
        } ?: return A11yOutcome.Rejected(
            A11yRejection(
                com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode.INTERNAL,
                "screenshot capture failed (secure window or platform rejection)"
            )
        )

        return try {
            withContext(Dispatchers.IO) {
                writeScreenshot(service, bitmap, passwordRects, grid, geometry)
            }
        } catch (error: Throwable) {
            A11yOutcome.Rejected(
                A11yRejection(
                    com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode.INTERNAL,
                    "screenshot could not be stored"
                )
            )
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private fun writeScreenshot(
        service: AccessibilityService,
        bitmap: Bitmap,
        passwordRects: List<A11yRect>,
        grid: Int,
        geometry: A11yScreenGeometry
    ): A11yOutcome {
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        if (imageWidth <= 0 || imageHeight <= 0) {
            return A11yOutcome.Rejected(
                A11yRejection(
                    com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode.INTERNAL,
                    "screenshot bitmap is empty"
                )
            )
        }

        val canvas = Canvas(bitmap)
        if (grid > 0) {
            val paint = Paint().apply {
                color = 0x66FFFFFF
                strokeWidth = 2f
            }
            for (index in 1 until grid) {
                val x = imageWidth * index / grid.toFloat()
                val y = imageHeight * index / grid.toFloat()
                canvas.drawLine(x, 0f, x, imageHeight.toFloat(), paint)
                canvas.drawLine(0f, y, imageWidth.toFloat(), y, paint)
            }
        }
        var redactedRegions = 0
        if (passwordRects.isNotEmpty()) {
            val redaction = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            passwordRects.forEach { rect ->
                canvas.drawRect(
                    rect.x.toFloat().coerceIn(0f, imageWidth.toFloat()),
                    rect.y.toFloat().coerceIn(0f, imageHeight.toFloat()),
                    (rect.x + rect.w).toFloat().coerceIn(0f, imageWidth.toFloat()),
                    (rect.y + rect.h).toFloat().coerceIn(0f, imageHeight.toFloat()),
                    redaction
                )
                redactedRegions++
            }
        }

        val directory = File(service.filesDir, SCREENSHOT_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            return A11yOutcome.Rejected(
                A11yRejection(
                    com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode.INTERNAL,
                    "screenshot directory is not writable"
                )
            )
        }
        val file = File(directory, "screen-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                return A11yOutcome.Rejected(
                    A11yRejection(
                        com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode.INTERNAL,
                        "screenshot PNG encoding failed"
                    )
                )
            }
        }
        val bytes = file.length()
        if (bytes > A11yLimits.MAX_SCREENSHOT_BYTES) {
            file.delete()
            return A11yOutcome.Rejected(
                A11yRejection(
                    com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode.TOO_LARGE,
                    "screenshot is $bytes bytes, above the ${A11yLimits.MAX_SCREENSHOT_BYTES} byte limit"
                )
            )
        }

        val screenW = geometry.widthPx
        val screenH = geometry.heightPx
        return A11yOutcome.Screenshot(
            A11yScreenshot(
                path = file.absolutePath,
                screenW = screenW,
                screenH = screenH,
                imageW = imageWidth,
                imageH = imageHeight,
                width = imageWidth,
                height = imageHeight,
                scaleX = if (imageWidth > 0) screenW.toDouble() / imageWidth else 1.0,
                scaleY = if (imageHeight > 0) screenH.toDouble() / imageHeight else 1.0,
                grid = grid,
                bytes = bytes,
                redactedRegions = redactedRegions,
                hint = if (redactedRegions > 0) {
                    "密码输入区域已做黑色遮罩（$redactedRegions 处）"
                } else {
                    null
                }
            )
        )
    }

    private const val SCREENSHOT_DIRECTORY = "dsh-a11y/screenshots"
}

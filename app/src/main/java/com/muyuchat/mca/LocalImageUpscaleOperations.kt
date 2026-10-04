package com.muyuchat.mca

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The local-image upscale owner (P5/L9). LocalImageProvider.upscale's body moved here verbatim as
 * runLocalImageUpscale(port, ...): the provider keeps begin/cancel/nativeConfig/generate/QNN helpers
 * and supplies state through LocalImageUpscalePort implemented by a private port object. activeRuntime
 * stays a live volatile read/write through the port (begin() updates it mid-call; the finally block
 * clears it) so the runtime handoff semantics are unchanged. The MAX_UPSCALE_* bounds,
 * LocalImageUpscalePublicationDimensions and validatedLocalImageUpscalePublicationDimensions moved
 * here with the body in P5/L9 (their only users are the upscale path; tests were re-derived).
 */

private const val MAX_UPSCALE_SOURCE_SIDE = 2_048
private const val MAX_UPSCALE_SOURCE_PIXELS = 4_000_000L
private const val MAX_UPSCALE_NATIVE_OUTPUT_SIDE = 8_192
private const val MAX_UPSCALE_NATIVE_OUTPUT_PIXELS = 64_000_000L
private const val MAX_UPSCALE_NATIVE_PNG_BYTES = 256L * 1_024L * 1_024L
private const val MAX_UPSCALE_OUTPUT_SIDE = 4_096
private const val MAX_UPSCALE_OUTPUT_PIXELS = 16_000_000L
private const val MAX_UPSCALE_PNG_BYTES = 64L * 1_024L * 1_024L

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

internal interface LocalImageUpscalePort {
    val appContext: Context
    val bridge: NativeStableDiffusionBridge
    val cancellationRequested: AtomicBoolean
    var activeRuntime: LocalImageRuntime?

    fun logLocalImageNativeFailure(result: JSONObject, fallbackMessage: String)

    fun begin(runtime: LocalImageRuntime)
}

internal suspend fun runLocalImageUpscale(
    port: LocalImageUpscalePort,
    input: LocalImagePreparedInput,
    upscaler: LocalImagePreparedUpscaler,
    targetScale: Int,
    tileSize: Int = 128,
    threads: Int = defaultLocalImageThreads(),
    onProgress: (LocalImageProgress) -> Unit = {}
): LocalImageResult {
    val appContext = port.appContext
    val bridge = port.bridge
    val cancellationRequested = port.cancellationRequested
    return withContext(Dispatchers.IO) {
        if (port.activeRuntime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
            port.begin(LocalImageRuntime.STABLE_DIFFUSION_CPP)
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
                port.logLocalImageNativeFailure(json, "stable-diffusion.cpp ESRGAN upscale failed.")
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
            if (port.activeRuntime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
                port.activeRuntime = null
                cancellationRequested.set(false)
            }
        }
    }
}

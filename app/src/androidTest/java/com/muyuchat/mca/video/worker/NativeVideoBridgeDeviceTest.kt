package com.muyuchat.mca.video.worker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real JNI load/mapping evidence for V1-03 on a device (no model weights required).
 *
 * It proves the new `generateVideo` symbol is packaged, the request JSON is parsed, typed
 * validation rejects invalid geometry before any native context is allocated, and missing pinned
 * components fail as MODEL_MISSING. Actual model load/sampling/decode remains BLOCKED on weights
 * and is NOT claimed here.
 */
@RunWith(AndroidJUnit4::class)
class NativeVideoBridgeDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bridgeSymbolIsPackagedAndInvalidGeometryFailsBeforeModelLoad() {
        assertTrue("mca_sd_native failed to load: ${NativeStableDiffusionBridge.loadError}", NativeStableDiffusionBridge.isAvailable)
        val root = File(context.filesDir, "video/runs").apply { mkdirs() }
        val runDir = File(root, "v-native-invalid").apply { mkdirs() }
        try {
            val request = baseRequest(runDir, root).apply { put("width", 33) }
            val bridge = NativeStableDiffusionBridge()
            val result = JSONObject(bridge.generateVideo(request.toString()))
            assertEquals("failed", result.optString("status"))
            assertEquals("INPUT_INVALID", result.optString("code"))
            assertTrue(result.optString("detail").contains("32"))
            bridge.cancelVideo()
            val progress = JSONObject(bridge.getVideoProgress())
            assertEquals("cancelling", progress.optString("phase"))
        } finally {
            runDir.deleteRecursively()
        }
    }

    @Test
    fun missingPinnedComponentsFailAsModelMissing() {
        val root = File(context.filesDir, "video/runs").apply { mkdirs() }
        val runDir = File(root, "v-native-missing").apply { mkdirs() }
        try {
            val bridge = NativeStableDiffusionBridge()
            val result = JSONObject(bridge.generateVideo(baseRequest(runDir, root).toString()))
            assertEquals("failed", result.optString("status"))
            assertEquals("MODEL_MISSING", result.optString("code"))
        } finally {
            runDir.deleteRecursively()
        }
    }

    private fun baseRequest(runDir: File, root: File): JSONObject = JSONObject().apply {
        put("runDir", runDir.absolutePath)
        put("stagingRoot", root.absolutePath)
        put("ditPath", File(runDir, "missing-dit.gguf").absolutePath)
        put("encoderPath", File(runDir, "missing-encoder.gguf").absolutePath)
        put("vaePath", File(runDir, "missing-vae.safetensors").absolutePath)
        put("prompt", "bridge validation")
        put("negativePrompt", "")
        put("imagePath", "")
        put("width", 64)
        put("height", 64)
        put("frames", 9)
        put("fps", 16)
        put("steps", 1)
        put("seed", 1)
        put("guidance", 6.0)
        put("flowShift", 3.0)
        put("sampler", "euler")
        put("threads", 2)
    }
}

package com.muyuchat.mca.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import org.json.JSONObject

class LocalImageSmokeActivity : Activity() {
    private val tag = "MCA-SD-SMOKE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread { runSmoke() }.start()
    }

    private fun runSmoke() {
        val startedAt = System.currentTimeMillis()
        val outDir = File(getExternalFilesDir("image_bench"), "runs").apply { mkdirs() }
        val runId = intent.getStringExtra("runId").orEmpty().ifBlank { "run-${startedAt}" }
        val logFile = File(outDir, "$runId.json")
        fun write(event: JSONObject) {
            event.put("runId", runId)
            event.put("elapsedMs", System.currentTimeMillis() - startedAt)
            logFile.writeText(event.toString(2))
            Log.i(tag, event.toString())
        }

        try {
            val modelPath = requireNotNull(intent.getStringExtra("modelPath")) { "modelPath is required" }
            val bundleRoot = intent.getStringExtra("bundleRoot").orEmpty()
            val prompt = intent.getStringExtra("prompt")
                ?: "a tiny ceramic robot sitting on a wooden desk, soft morning light, clean background, no text"
            val width = intent.getIntExtra("width", 256)
            val height = intent.getIntExtra("height", 256)
            val steps = intent.getIntExtra("steps", 2)
            val threads = intent.getIntExtra("threads", 4)
            val cfgScale = intent.numberExtra("cfgScale", 7.0)
            val distilledGuidance = intent.numberExtra("distilledGuidance", 3.5)
            val flowShift = intent.numberExtra("flowShift", -1.0)
            val sampleMethod = intent.getStringExtra("sampleMethod").orEmpty().ifBlank { "euler" }
            val family = intent.getStringExtra("family").orEmpty().ifBlank { "SD15" }
            val outputFile = File(outDir, "$runId.png")
            val requestJson = JSONObject()
                .put("prompt", prompt)
                .put("family", family)
                .put("width", width)
                .put("height", height)
                .put("steps", steps)
                .put("threads", threads)
                .put("cfgScale", cfgScale)
                .put("distilledGuidance", distilledGuidance)
                .put("flowShift", flowShift)
                .put("sampleMethod", sampleMethod)
                .put("backendMode", "cpu")

            write(
                JSONObject()
                    .put("status", "starting")
                    .put("modelPath", modelPath)
                    .put("bundleRoot", bundleRoot)
                    .put("prompt", prompt)
                    .put("width", width)
                    .put("height", height)
                    .put("steps", steps)
                    .put("threads", threads)
                    .put("cfgScale", cfgScale)
                    .put("distilledGuidance", distilledGuidance)
                    .put("flowShift", flowShift)
                    .put("sampleMethod", sampleMethod)
                    .put("family", family)
                    .put("nativeAvailable", NativeStableDiffusionBridge.isAvailable)
                    .put("nativeLoadError", NativeStableDiffusionBridge.loadError?.message.orEmpty())
            )

            require(NativeStableDiffusionBridge.isAvailable) {
                "mca_sd_native is unavailable: ${NativeStableDiffusionBridge.loadError?.message.orEmpty()}"
            }

            val bridge = NativeStableDiffusionBridge()
            val progressThread = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    runCatching {
                        val progress = JSONObject(bridge.getProgress())
                        write(JSONObject().put("status", "progress").put("progress", progress))
                    }
                    Thread.sleep(1000)
                }
            }
            progressThread.start()
            val result = try {
                bridge.generate(
                    modelPath,
                    bundleRoot,
                    requestJson.toString(),
                    outputFile.absolutePath
                )
            } finally {
                progressThread.interrupt()
            }
            val resultJson = JSONObject(result)
            write(
                JSONObject()
                    .put("status", if (resultJson.optBoolean("ok")) "completed" else "failed")
                    .put("request", requestJson)
                    .put("result", resultJson)
                    .put("outputPath", outputFile.absolutePath)
                    .put("outputBytes", outputFile.takeIf { it.exists() }?.length() ?: 0L)
            )
        } catch (error: Throwable) {
            write(JSONObject().put("status", "failed").put("error", error.stackTraceToString()))
        } finally {
            runOnUiThread { finish() }
        }
    }

    private fun Intent.numberExtra(name: String, default: Double): Double {
        val value = extras?.get(name) ?: return default
        return when (value) {
            is Double -> value
            is Float -> value.toDouble()
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: default
            else -> default
        }
    }
}

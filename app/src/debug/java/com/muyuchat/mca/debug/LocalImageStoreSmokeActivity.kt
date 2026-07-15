package com.muyuchat.mca.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.muyuchat.mca.ImageBackend
import com.muyuchat.mca.LocalImageModelFamily
import com.muyuchat.mca.LocalImageModelRecord
import com.muyuchat.mca.LocalImageModelStore
import com.muyuchat.mca.LocalImageProvider
import com.muyuchat.mca.LocalImageRuntime
import com.muyuchat.mca.LocalImageVerificationStatus
import com.muyuchat.mca.qnnImageVerificationStampFor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocalImageStoreSmokeActivity : Activity() {
    private val tag = "MCA-IMAGE-STORE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread { runSmoke() }.start()
    }

    private fun runSmoke() {
        val startedAt = System.currentTimeMillis()
        val outDir = File(getExternalFilesDir("image_bench"), "runs").apply { mkdirs() }
        val runId = intent.getStringExtra("runId").orEmpty().ifBlank { "store-${startedAt}" }
        val logFile = File(outDir, "$runId.json")
        val outputFile = File(outDir, "$runId.png")
        val events = JSONArray()

        fun write(event: JSONObject) {
            event.put("runId", runId)
            event.put("elapsedMs", System.currentTimeMillis() - startedAt)
            events.put(JSONObject(event.toString()))
            logFile.writeText(JSONObject(event.toString()).put("events", events).toString(2))
            Log.i(tag, event.toString())
        }

        try {
            val store = LocalImageModelStore(this)
            val initialModels = store.loadModels()
            val bootstrapBundleRoot = intent.getStringExtra("bootstrapBundleRoot")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
            val models = if (initialModels.isEmpty() && bootstrapBundleRoot?.isDirectory == true) {
                val primary = File(bootstrapBundleRoot, "unet.bin")
                require(primary.isFile && primary.length() > 0L) {
                    "Bootstrap QNN bundle is missing unet.bin."
                }
                LocalImageModelRecord(
                    id = "debug-qnn-bootstrap",
                    displayName = "Debug QNN bootstrap",
                    path = primary.absolutePath,
                    fileName = primary.name,
                    sizeBytes = bootstrapBundleRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    sha256 = "debug-bootstrap",
                    runtime = LocalImageRuntime.QNN_HTP,
                    family = LocalImageModelFamily.SD15,
                    imageSize = "512x512",
                    source = "debug:bootstrap",
                    bundleRoot = bootstrapBundleRoot.absolutePath,
                    componentCount = bootstrapBundleRoot.walkTopDown().count { it.isFile }
                ).let { record ->
                    store.saveModels(listOf(record))
                    listOf(record)
                }
            } else {
                initialModels
            }
            write(
                JSONObject()
                    .put("status", "loaded_store")
                    .put("modelCount", models.size)
                    .put("models", JSONArray(models.map { it.toSmokeJson() }))
                    .put("selectedModelId", store.loadSelectedModelId().orEmpty())
                    .put("selectedBackend", store.loadSelectedBackend().name)
            )

            val requestedId = intent.getStringExtra("modelId").orEmpty()
            val model = models.firstOrNull { it.id == requestedId }
                ?: models.firstOrNull { it.runtime.name == "QNN_HTP" }
                ?: models.firstOrNull()
                ?: error("Local image model store is empty.")

            if (intent.getBooleanExtra("generate", true)) {
                val width = intent.getIntExtra("width", 512)
                val height = intent.getIntExtra("height", 512)
                val steps = intent.getIntExtra("steps", 4)
                val seed = intent.getIntExtra("seed", 1234)
                val prompt = intent.getStringExtra("prompt")
                    ?: "a small white ceramic cup on a wooden desk, morning light, clean background, photo realistic"
                val request = JSONObject()
                    .put("prompt", prompt)
                    .put("width", width)
                    .put("height", height)
                    .put("steps", steps)
                    .put("seed", seed)
                    .put("randomSeed", seed)
                    .put("cfgScale", intent.getDoubleExtra("cfgScale", 7.0))
                    .put("backendMode", intent.getStringExtra("backendMode") ?: "cpu")
                    .put("threads", intent.getIntExtra("threads", 4))

                val runnableModel = model.copy(
                    verificationStatus = LocalImageVerificationStatus.PASSED,
                    verificationMessage = "Debug store smoke is verifying this QNN engine through LocalImageProvider.",
                    qnnVerificationStamp = when {
                        model.runtime.name != "QNN_HTP" -> ""
                        intent.getBooleanExtra("forceStaleStamp", false) -> "{}"
                        else -> qnnImageVerificationStampFor(
                            this@LocalImageStoreSmokeActivity,
                            File(model.bundleRoot.orEmpty())
                        )
                    },
                    verifiedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                write(
                    JSONObject()
                        .put("status", "generation_starting")
                        .put("model", runnableModel.toSmokeJson())
                        .put("request", request)
                        .put("outputPath", outputFile.absolutePath)
                )
                val provider = LocalImageProvider(this@LocalImageStoreSmokeActivity)
                val cancelAfterMs = intent.getLongExtra("cancelAfterMs", 0L)
                val canceller = cancelAfterMs.takeIf { it > 0L }?.let { delayMs ->
                    Thread {
                        try {
                            Thread.sleep(delayMs)
                            provider.cancel()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }.also { it.start() }
                }
                val result = try {
                    runBlocking {
                        provider.generate(runnableModel, prompt) { progress ->
                            write(
                                JSONObject()
                                    .put("status", "progress")
                                    .put("phase", progress.phase)
                                    .put("step", progress.step)
                                    .put("steps", progress.steps)
                                    .put("message", progress.message)
                                    .put("elapsedMs", progress.elapsedMs)
                            )
                        }
                    }
                } finally {
                    canceller?.interrupt()
                }
                outputFile.writeBytes(result.bytes)
                val passedModel = runnableModel.copy(
                    verificationStatus = LocalImageVerificationStatus.PASSED,
                    verificationMessage = "Debug store smoke generated ${width}x$height image via ${model.runtime.name}.",
                    verifiedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                store.updateModel(passedModel)
                store.saveSelectedModelId(passedModel.id)
                store.saveSelectedBackend(ImageBackend.LOCAL)
                write(
                    JSONObject()
                        .put("status", "completed")
                        .put("model", passedModel.toSmokeJson())
                        .put("outputPath", outputFile.absolutePath)
                        .put("outputBytes", outputFile.length())
                        .put("mimeType", result.mimeType)
                        .put("selectedModelId", passedModel.id)
                        .put("selectedBackend", ImageBackend.LOCAL.name)
                )
            }
        } catch (error: Throwable) {
            write(JSONObject().put("status", "failed").put("error", error.stackTraceToString()))
        } finally {
            runOnUiThread { finish() }
        }
    }

    private fun LocalImageModelRecord.toSmokeJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("displayName", displayName)
            .put("path", path)
            .put("bundleRoot", bundleRoot.orEmpty())
            .put("runtime", runtime.name)
            .put("family", family.name)
            .put("imageSize", imageSize)
            .put("componentCount", componentCount)
            .put("verificationStatus", verificationStatus.name)
            .put("verificationMessage", verificationMessage)
            .put("configured", configured)
}

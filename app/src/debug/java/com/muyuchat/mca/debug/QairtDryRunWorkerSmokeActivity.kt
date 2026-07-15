package com.muyuchat.mca.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.muyuchat.mca.QairtDryRunWorkerClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Debug-only harness for the product bound worker, not the older direct smoke
 * activity.  It gives device CI an ADB entry point that exercises the exact
 * process boundary used by Model Hub's “隔离验收” button.
 */
class QairtDryRunWorkerSmokeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modelId = intent.getStringExtra("modelId").orEmpty()
        val runId = intent.getStringExtra("runId").orEmpty().ifBlank {
            "qairt-worker-${System.currentTimeMillis()}"
        }
        val nCtx = intent.getIntExtra("nCtx", 2_048)
        val nThreads = intent.getIntExtra("nThreads", 2)
        scope.launch {
            val started = System.currentTimeMillis()
            val root = JSONObject()
                .put("runId", runId)
                .put("modelId", modelId)
                .put("startedAt", started)
            runCatching {
                require(modelId.isNotBlank()) { "modelId is required" }
                QairtDryRunWorkerClient(applicationContext).certify(
                    modelId = modelId,
                    nCtx = nCtx,
                    nThreads = nThreads
                )
            }.onSuccess { result ->
                root.put("status", "completed")
                    .put("bundleSha256", result.bundleSha256)
                    .put("npuEvidence", result.npuEvidence)
                    .put("visibleChars", result.visibleChars)
                    .put("visionChecked", result.visionChecked)
                    .put("elapsedMs", result.elapsedMs)
            }.onFailure { error ->
                root.put("status", "failed")
                    .put("error", error.stackTraceToString())
                    .put("elapsedMs", System.currentTimeMillis() - started)
            }
            root.put("finishedAt", System.currentTimeMillis())
            val target = File(
                getExternalFilesDir("qairt_worker_smoke") ?: filesDir,
                "$runId.json"
            )
            target.parentFile?.mkdirs()
            target.writeText(root.toString(2), Charsets.UTF_8)
            Log.i(TAG, "QAIRT product worker smoke: ${target.absolutePath}")
            runOnUiThread(::finish)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "MCA-QAIRT-WORKER-SMOKE"
    }
}

package com.muyuchat.mca

import android.os.Build
import android.util.Log
import com.muyuchat.core.mediatek.MediaTekAnimaDiagnosticSample
import com.muyuchat.core.mediatek.MediaTekAnimaDiagnosticStats
import org.json.JSONObject

internal object MediaTekAnimaDiagnosticLog {
    private const val TAG = "McaMediaTekNpu"

    fun recordRunStart(width: Int, height: Int, steps: Int) {
        Log.i(
            TAG,
            JSONObject()
                .put("schema", "mca.mediatek-anima-trace/1")
                .put("type", "run_start")
                .put("deviceManufacturer", Build.MANUFACTURER)
                .put("deviceModel", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("androidApi", Build.VERSION.SDK_INT)
                .put("width", width)
                .put("height", height)
                .put("steps", steps)
                .put("requestedAccelerator", "MEDIATEK_NPU")
                .put("liteRtDependencyVersion", "2.3.0")
                .put("actualDelegate", JSONObject.NULL)
                .put("actualNeuronVersion", JSONObject.NULL)
                .put("npuPartitions", JSONObject.NULL)
                .put("cpuFallbackOps", JSONObject.NULL)
                .toString(),
        )
    }

    fun record(sample: MediaTekAnimaDiagnosticSample) {
        Log.i(TAG, toJson(sample).toString())
    }

    internal fun toJson(sample: MediaTekAnimaDiagnosticSample): JSONObject = JSONObject()
        .put("schema", "mca.mediatek-anima-trace/1")
        .put("type", "tensor_trace")
        .put("stage", sample.stage.name)
        .put("stepIndex", sample.stepIndex)
        .put("cfgBranch", sample.cfgBranch.name)
        .put("blockIndex", sample.blockIndex ?: JSONObject.NULL)
        .put("graphFile", sample.graphFile)
        .put("inputTensor", sample.inputTensorName)
        .put("outputTensor", sample.outputTensorName)
        .put("input", sample.inputStats.toJson())
        .put("output", sample.outputStats.toJson())
        .put("stdDriftRatio", sample.stdDriftRatio.asJsonValue())

    private fun MediaTekAnimaDiagnosticStats.toJson(): JSONObject = JSONObject()
        .put("count", count)
        .put("finiteCount", finiteCount)
        .put("mean", mean.asJsonValue())
        .put("standardDeviation", standardDeviation.asJsonValue())
        .put("maxAbs", maxAbs.asJsonValue())

    private fun Double?.asJsonValue(): Any =
        this?.takeIf(Double::isFinite) ?: JSONObject.NULL
}

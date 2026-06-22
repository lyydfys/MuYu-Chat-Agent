package com.muyuchat.core.benchmark

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

data class BenchmarkHistoryRecord(
    val time: Long = System.currentTimeMillis(),
    val modelId: String?,
    val modelName: String?,
    val modelPath: String?,
    val deviceSummary: String,
    val paramsJson: String,
    val result: BenchmarkResult
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time)
        .put("modelId", modelId)
        .put("modelName", modelName)
        .put("modelPath", modelPath)
        .put("deviceSummary", deviceSummary)
        .put("params", JSONObject(paramsJson))
        .put("result", result.toJson())

    companion object {
        fun fromJson(line: String): BenchmarkHistoryRecord {
            val root = JSONObject(line)
            return BenchmarkHistoryRecord(
                time = root.optLong("time"),
                modelId = root.optString("modelId").takeIf { it.isNotBlank() && it != "null" },
                modelName = root.optString("modelName").takeIf { it.isNotBlank() && it != "null" },
                modelPath = root.optString("modelPath").takeIf { it.isNotBlank() && it != "null" },
                deviceSummary = root.optString("deviceSummary"),
                paramsJson = root.optJSONObject("params")?.toString() ?: "{}",
                result = root.optJSONObject("result").toBenchmarkResult()
            )
        }

        private fun JSONObject?.toBenchmarkResult(): BenchmarkResult {
            if (this == null) return BenchmarkResult(error = "Missing benchmark result.")
            return BenchmarkResult(
                time = optLong("time"),
                loadMs = optLong("loadMs"),
                ttftMs = optLong("ttftMs"),
                promptTokens = optInt("promptTokens"),
                genTokens = optInt("genTokens"),
                decodeMs = optLong("decodeMs"),
                decodeTps = optDouble("decodeTps"),
                e2eTps = optDouble("e2eTps"),
                nativePssKb = optLong("nativePssKb"),
                processRssKb = optLong("processRssKb"),
                nativeHeapKb = optLong("nativeHeapKb"),
                javaHeapKb = optLong("javaHeapKb"),
                availMemKb = optLong("availMemKb"),
                totalMemKb = optLong("totalMemKb"),
                modelMemoryBudgetKb = optLong("modelMemoryBudgetKb"),
                isLowMemory = optBoolean("isLowMemory", false),
                bestThreadCount = optInt("bestThreadCount"),
                threadResultsJson = optJSONArray("threadResults")?.toString() ?: "[]",
                thermalDelta = optInt("thermalDelta"),
                stable = optBoolean("stable", true),
                error = optString("error").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }
}

class BenchmarkHistoryLogger(context: Context) {
    private val logDir: File = File(context.applicationContext.filesDir, "logs").also { it.mkdirs() }
    private val logFile: File = File(logDir, "mca-benchmark-history.jsonl")

    fun append(record: BenchmarkHistoryRecord) {
        logDir.mkdirs()
        logFile.appendText(record.toJson().toString() + "\n", Charsets.UTF_8)
    }

    fun recent(limit: Int = 30): List<BenchmarkHistoryRecord> {
        if (!logFile.exists()) return emptyList()
        val queue = ArrayDeque<String>(limit)
        logFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (queue.size == limit) queue.removeFirst()
                queue.addLast(line)
            }
        }
        return queue.mapNotNull { line ->
            runCatching { BenchmarkHistoryRecord.fromJson(line) }.getOrNull()
        }.asReversed()
    }

    fun exportFile(): File = logFile
}

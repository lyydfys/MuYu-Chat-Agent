package com.muyuchat.core.advisor

import android.content.Context
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.tuning.TuningPlan
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

data class AgentDecisionLog(
    val time: Long = System.currentTimeMillis(),
    val deviceProfileJson: String,
    val recommendationJson: String,
    val benchmarkJson: String? = null,
    val appliedParamsJson: String? = null,
    val userConfirmed: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time)
        .put("deviceProfile", JSONObject(deviceProfileJson))
        .put("recommendation", JSONObject(recommendationJson))
        .put("benchmarkResult", benchmarkJson?.let { JSONObject(it) })
        .put("appliedParams", appliedParamsJson?.let { JSONObject(it) })
        .put("userConfirmed", userConfirmed)

    companion object {
        fun fromJson(line: String): AgentDecisionLog {
            val root = JSONObject(line)
            return AgentDecisionLog(
                time = root.optLong("time"),
                deviceProfileJson = root.optJSONObject("deviceProfile")?.toString() ?: "{}",
                recommendationJson = root.optJSONObject("recommendation")?.toString() ?: "{}",
                benchmarkJson = root.optJSONObject("benchmarkResult")?.toString(),
                appliedParamsJson = root.optJSONObject("appliedParams")?.toString(),
                userConfirmed = root.optBoolean("userConfirmed")
            )
        }
    }
}

class AgentDecisionLogger(context: Context) {
    private val logDir: File = File(context.applicationContext.filesDir, "logs").also { it.mkdirs() }
    private val logFile: File = File(logDir, "mca-agent.jsonl")

    fun append(
        device: DeviceProfile,
        recommendation: AgentRecommendation,
        benchmark: BenchmarkResult? = null,
        appliedPlan: TuningPlan? = null,
        userConfirmed: Boolean = false
    ) {
        logDir.mkdirs()
        val log = AgentDecisionLog(
            deviceProfileJson = device.toJson().toString(),
            recommendationJson = recommendation.toJson().toString(),
            benchmarkJson = benchmark?.toJson()?.toString(),
            appliedParamsJson = appliedPlan?.toJson()?.toString(),
            userConfirmed = userConfirmed
        )
        logFile.appendText(log.toJson().toString() + "\n", Charsets.UTF_8)
    }

    fun recent(limit: Int = 50): List<AgentDecisionLog> {
        if (!logFile.exists()) return emptyList()
        val queue = ArrayDeque<String>(limit)
        logFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (queue.size == limit) queue.removeFirst()
                queue.addLast(line)
            }
        }
        return queue.mapNotNull { line -> runCatching { AgentDecisionLog.fromJson(line) }.getOrNull() }
            .asReversed()
    }
}

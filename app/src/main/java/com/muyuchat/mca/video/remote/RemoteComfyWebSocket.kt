package com.muyuchat.mca.video.remote

import com.muyuchat.core.video.contract.VideoRunStage
import org.json.JSONObject

/**
 * Parsed ComfyUI WebSocket message. `prompt_id` is optional because older server revisions omit it
 * on some events; when present it is the only correlation the adapter trusts.
 */
internal sealed class RemoteComfyWsEvent {
    abstract val promptId: String?

    data class ExecutionStart(override val promptId: String?) : RemoteComfyWsEvent()

    data class Executing(override val promptId: String?, val nodeId: String?) : RemoteComfyWsEvent()

    data class Progress(
        override val promptId: String?,
        val nodeId: String?,
        val value: Long,
        val max: Long,
    ) : RemoteComfyWsEvent()

    data class Executed(override val promptId: String?, val nodeId: String?) : RemoteComfyWsEvent()

    data class ExecutionError(
        override val promptId: String?,
        val nodeId: String?,
        val errorType: String?,
    ) : RemoteComfyWsEvent()

    data class ExecutionInterrupted(override val promptId: String?) : RemoteComfyWsEvent()

    data class ExecutionSuccess(override val promptId: String?) : RemoteComfyWsEvent()

    data class Other(override val promptId: String?) : RemoteComfyWsEvent()
}

/** Late or foreign events are ignored; an event without a prompt id can only be ours (one socket). */
internal fun RemoteComfyWsEvent.matchesPrompt(promptId: String): Boolean =
    this.promptId == null || this.promptId == promptId

internal object RemoteComfyWsParser {

    fun parse(text: String): RemoteComfyWsEvent? {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val type = root.opt("type") as? String ?: return null
        val data = root.optJSONObject("data")
        val promptId = (data?.opt("prompt_id") as? String) ?: (root.opt("prompt_id") as? String)
        return when (type) {
            "execution_start" -> RemoteComfyWsEvent.ExecutionStart(promptId)
            "executing" -> RemoteComfyWsEvent.Executing(promptId, nodeId(data?.opt("node")))
            "progress" -> {
                val value = number(data?.opt("value"))
                val max = number(data?.opt("max"))
                if (value == null || max == null) {
                    RemoteComfyWsEvent.Other(promptId)
                } else {
                    RemoteComfyWsEvent.Progress(promptId, nodeId(data?.opt("node")), value, max)
                }
            }

            "executed" -> RemoteComfyWsEvent.Executed(promptId, nodeId(data?.opt("node")))
            "execution_error" -> RemoteComfyWsEvent.ExecutionError(
                promptId = promptId,
                nodeId = nodeId(data?.opt("node_id")) ?: nodeId(data?.opt("node")),
                errorType = (data?.opt("exception_type") as? String),
            )

            "execution_interrupted" -> RemoteComfyWsEvent.ExecutionInterrupted(promptId)
            "execution_success" -> RemoteComfyWsEvent.ExecutionSuccess(promptId)
            else -> RemoteComfyWsEvent.Other(promptId)
        }
    }

    private fun nodeId(value: Any?): String? = when (value) {
        is String -> value
        is Number -> value.toString()
        else -> null
    }

    private fun number(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Number -> value.toDouble().takeIf { it.isFinite() && it >= 0.0 }?.toLong()
        else -> null
    }
}

/**
 * Class-type → business stage. The remote server may run arbitrary custom nodes; unknown classes
 * simply produce no stage signal. Only the five video-relevant groups are mapped.
 */
internal object RemoteComfyNodeStages {

    fun stageFor(classType: String): VideoRunStage? {
        val normalized = normalize(classType)
        return when {
            normalized.contains("vaedecode") -> VideoRunStage.DECODE
            normalized.contains("savevideo") ||
                normalized.contains("createvideo") ||
                normalized.contains("videocombine") -> VideoRunStage.ENCODE

            normalized.contains("textencode") -> VideoRunStage.TEXT_ENCODE
            isSampler(normalized) -> VideoRunStage.SAMPLE
            else -> null
        }
    }

    fun isSampler(classType: String): Boolean {
        val normalized = normalize(classType)
        if (normalized.contains("select")) return false
        return normalized.contains("ksampler") ||
            normalized.startsWith("sampler") ||
            normalized.endsWith("sampler")
    }

    private fun normalize(classType: String): String =
        classType.lowercase().filterNot { it.isWhitespace() }
}

internal data class RemoteComfyStageSignal(
    val stage: VideoRunStage,
    val step: Long? = null,
    val stepTotal: Long? = null,
)

internal sealed class RemoteComfyWsProjectionResult {
    data class Stage(val signal: RemoteComfyStageSignal) : RemoteComfyWsProjectionResult()

    /** `executing` with a null node, or `execution_success`: reconcile through history now. */
    data object Completed : RemoteComfyWsProjectionResult()

    data class Failed(val summary: String) : RemoteComfyWsProjectionResult()

    data object Ignored : RemoteComfyWsProjectionResult()
}

/**
 * Stateful event→stage reducer for one attempt. It only reports stages that the server actually
 * announced (`executing` for a mapped node) and only reports sampling steps while the current node
 * is a sampler. It never guesses a stage from the mere presence of a status event.
 */
internal class RemoteComfyWsProjection(private val nodeClasses: Map<String, String>) {

    var currentNodeId: String? = null
        private set
    private var completed = false

    fun apply(event: RemoteComfyWsEvent): RemoteComfyWsProjectionResult = when (event) {
        is RemoteComfyWsEvent.Executing -> {
            if (completed) {
                RemoteComfyWsProjectionResult.Ignored
            } else {
                val nodeId = event.nodeId
                currentNodeId = nodeId
                if (nodeId == null) {
                    completed = true
                    RemoteComfyWsProjectionResult.Completed
                } else {
                    val stage = nodeClasses[nodeId]?.let { classType ->
                        RemoteComfyNodeStages.stageFor(classType)
                    }
                    if (stage == null) {
                        RemoteComfyWsProjectionResult.Ignored
                    } else {
                        RemoteComfyWsProjectionResult.Stage(RemoteComfyStageSignal(stage))
                    }
                }
            }
        }

        is RemoteComfyWsEvent.Progress -> {
            if (completed) {
                RemoteComfyWsProjectionResult.Ignored
            } else {
                val node = event.nodeId ?: currentNodeId
                if (node == null) {
                    RemoteComfyWsProjectionResult.Ignored
                } else if (event.nodeId != null && currentNodeId != null && event.nodeId != currentNodeId) {
                    // A delayed progress from a node that is no longer executing must not move a
                    // finished sampler back into SAMPLE.
                    RemoteComfyWsProjectionResult.Ignored
                } else {
                    val classType = nodeClasses[node]
                    if (classType == null || !RemoteComfyNodeStages.isSampler(classType)) {
                        RemoteComfyWsProjectionResult.Ignored
                    } else if (event.max <= 0L || event.value < 0L) {
                        RemoteComfyWsProjectionResult.Ignored
                    } else {
                        RemoteComfyWsProjectionResult.Stage(
                            RemoteComfyStageSignal(VideoRunStage.SAMPLE, event.value, event.max),
                        )
                    }
                }
            }
        }

        is RemoteComfyWsEvent.ExecutionSuccess -> {
            completed = true
            RemoteComfyWsProjectionResult.Completed
        }

        is RemoteComfyWsEvent.ExecutionError -> RemoteComfyWsProjectionResult.Failed(
            RemoteComfySafeText.errorSummary(event.nodeId, event.errorType),
        )

        is RemoteComfyWsEvent.ExecutionInterrupted -> RemoteComfyWsProjectionResult.Failed("远端任务被中断")
        else -> RemoteComfyWsProjectionResult.Ignored
    }
}

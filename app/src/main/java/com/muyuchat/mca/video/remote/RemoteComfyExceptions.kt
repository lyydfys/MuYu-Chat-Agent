package com.muyuchat.mca.video.remote

import com.muyuchat.core.video.contract.VideoErrorCode

/**
 * Typed, credential-free failures of the V3-01 remote ComfyUI transport.
 *
 * Every message is constructed locally and contains only a status code or a fixed sentence; no
 * exception carries the endpoint URL, the bearer key, the workflow JSON or a server response body.
 */
internal class RemoteComfyConfigException(message: String) : Exception(message)

internal class RemoteComfyRedirectException(val statusCode: Int) :
    Exception("远端返回重定向（HTTP $statusCode），已拒绝")

internal class RemoteComfyAuthException(val statusCode: Int) :
    Exception("远端认证失败（HTTP $statusCode）")

internal class RemoteComfyStatusException(val statusCode: Int) :
    Exception("远端请求失败（HTTP $statusCode）")

internal class RemoteComfyProtocolException(message: String) : Exception(message)

internal class RemoteComfyLimitException(message: String) : Exception(message)

/** A saved raw workflow that cannot safely be submitted; carries the owner-facing error code. */
internal class RemoteComfyWorkflowException(
    val code: VideoErrorCode,
    message: String,
) : Exception(message)

/**
 * Safe text for error details. Only node ids and exception TYPE names are ever copied from a
 * remote response; exception messages, prompts and credentials are deliberately not read.
 */
internal object RemoteComfySafeText {

    fun errorSummary(nodeId: String?, errorType: String?): String = buildString {
        append("远端执行失败")
        val node = label(nodeId, 64)
        if (node != null) {
            append("（节点 ")
            append(node)
            append("）")
        }
        val type = label(errorType, 80)
        if (type != null) {
            append("：")
            append(type)
        }
    }.take(240)

    fun label(value: String?, maxChars: Int): String? {
        if (value.isNullOrEmpty()) return null
        val builder = StringBuilder(minOf(value.length, maxChars))
        for (ch in value) {
            if (builder.length >= maxChars) break
            if (!ch.isISOControl()) builder.append(ch)
        }
        return builder.toString().ifEmpty { null }
    }
}

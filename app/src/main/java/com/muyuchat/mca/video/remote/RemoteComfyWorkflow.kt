package com.muyuchat.mca.video.remote

import com.muyuchat.core.video.contract.VideoErrorCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Validated ComfyUI API-format workflow (`{ nodeId: { class_type, inputs } }`).
 *
 * The graph is re-serialized as the `prompt` member of POST /prompt. UI-format exports, non-object
 * nodes, oversized graphs and image-upload nodes are rejected BEFORE any network call. Upload nodes
 * are never removed or rewritten: V3-01 has no remote image upload, so silently deleting a
 * LoadImage node would execute a different graph than the user saved.
 */
internal class RemoteComfyWorkflow private constructor(
    val graph: JSONObject,
    val nodeClasses: Map<String, String>,
) {

    companion object {
        const val MAX_NODES = 128
        const val UPLOAD_UNSUPPORTED_MESSAGE = "V3-01 暂不支持远端图生视频上传；请使用文生视频图"

        fun parse(raw: String): RemoteComfyWorkflow {
            if (raw.length > REMOTE_MAX_REQUEST_BODY_BYTES) {
                throw RemoteComfyWorkflowException(
                    VideoErrorCode.INPUT_TOO_LARGE,
                    "远端工作流 JSON 超过 4 MiB 上限",
                )
            }
            val graph = try {
                JSONObject(raw)
            } catch (_: Exception) {
                throw RemoteComfyWorkflowException(
                    VideoErrorCode.WORKFLOW_INVALID,
                    "原始工作流必须是 ComfyUI API-format JSON 对象",
                )
            }
            if (graph.opt("nodes") is JSONArray || graph.opt("links") is JSONArray) {
                throw RemoteComfyWorkflowException(
                    VideoErrorCode.WORKFLOW_INVALID,
                    "检测到 ComfyUI UI format（nodes/links）；请导出 API format 后再提交",
                )
            }
            if (graph.length() == 0) {
                throw RemoteComfyWorkflowException(
                    VideoErrorCode.WORKFLOW_INVALID,
                    "原始工作流没有节点",
                )
            }
            if (graph.length() > MAX_NODES) {
                throw RemoteComfyWorkflowException(
                    VideoErrorCode.INPUT_TOO_LARGE,
                    "原始工作流节点数 ${graph.length()} 超过上限 $MAX_NODES",
                )
            }
            val classes = LinkedHashMap<String, String>(graph.length())
            val keys = graph.keys()
            while (keys.hasNext()) {
                val nodeId = keys.next()
                val node = graph.optJSONObject(nodeId)
                    ?: throw RemoteComfyWorkflowException(
                        VideoErrorCode.WORKFLOW_INVALID,
                        "节点 '$nodeId' 不是 API-format 对象",
                    )
                val classType = node.opt("class_type") as? String
                if (classType == null || classType.isBlank()) {
                    throw RemoteComfyWorkflowException(
                        VideoErrorCode.WORKFLOW_INVALID,
                        "节点 '$nodeId' 缺少字符串 class_type",
                    )
                }
                if (isUploadClass(classType)) {
                    throw RemoteComfyWorkflowException(
                        VideoErrorCode.INPUT_INVALID,
                        UPLOAD_UNSUPPORTED_MESSAGE,
                    )
                }
                classes[nodeId] = classType
            }
            return RemoteComfyWorkflow(graph, classes)
        }

        /**
         * `{"prompt": <saved graph>, "client_id": <uuid>}`. The body cap is enforced on the exact
         * UTF-8 bytes that would be sent, not on the character count.
         */
        fun promptBody(workflow: RemoteComfyWorkflow, clientId: String): ByteArray {
            val root = JSONObject()
                .put("prompt", workflow.graph)
                .put("client_id", clientId)
            val bytes = root.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > REMOTE_MAX_REQUEST_BODY_BYTES) {
                throw RemoteComfyWorkflowException(
                    VideoErrorCode.INPUT_TOO_LARGE,
                    "远端提交体超过 4 MiB 上限",
                )
            }
            return bytes
        }

        /** Only a non-empty, bounded, control-free string is a usable prompt identity. */
        fun readPromptId(body: JSONObject?): String {
            val value = (body?.opt("prompt_id")) as? String
            if (value == null || value.isBlank() || value.length > 256 || value.any { it.isISOControl() }) {
                throw RemoteComfyProtocolException("远端未返回有效的 prompt_id")
            }
            // `addPathSegment("." / "..")` is normalized away by HttpUrl; reject those exact ids and
            // path separators so a server cannot turn a history lookup into another path.
            if (value == "." || value == ".." || value.contains('/') || value.contains('\\')) {
                throw RemoteComfyProtocolException("远端未返回有效的 prompt_id")
            }
            return value
        }

        private fun isUploadClass(classType: String): Boolean {
            val compact = classType.lowercase().filterNot { it == '_' || it == '-' || it.isWhitespace() }
            return compact.contains("loadimage") || compact.contains("upload")
        }
    }
}

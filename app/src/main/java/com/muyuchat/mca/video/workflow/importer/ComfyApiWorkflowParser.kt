package com.muyuchat.mca.video.workflow.importer

import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoImportDiagnostic
import com.muyuchat.core.video.contract.VideoImportSeverity
import java.security.MessageDigest

/** One parsed input: either a literal from the JSON or a `[nodeId, outputIndex]` reference. */
sealed class ComfyApiInputValue {
    data class Literal(val value: ComfyJsonValue) : ComfyApiInputValue()

    data class Link(val nodeId: String, val outputIndex: Int) : ComfyApiInputValue()
}

data class ComfyApiNode(
    val id: String,
    val classType: String,
    val inputs: Map<String, ComfyApiInputValue>,
    val title: String? = null,
)

data class ComfyApiGraph(
    val sourceSha256: String,
    val fileName: String?,
    val nodes: List<ComfyApiNode>,
    val warnings: List<VideoImportDiagnostic> = emptyList(),
) {
    fun node(id: String): ComfyApiNode? = nodes.firstOrNull { it.id == id }

    val classTypeCounts: Map<String, Int>
        get() = nodes.groupingBy { it.classType }.eachCount().toSortedMap()
}

sealed class ComfyApiParseResult {
    data class Parsed(val graph: ComfyApiGraph) : ComfyApiParseResult()

    data class Rejected(
        val code: VideoErrorCode,
        val diagnostics: List<VideoImportDiagnostic>,
    ) : ComfyApiParseResult()
}

/**
 * ComfyUI API-format parser. Accepts only `{ nodeId: { class_type, inputs } }`; UI format is
 * rejected with an explicit export hint. Every reference is checked against the class/arity
 * matrix, and the semantic graph must be a single-root DAG with no dangling/self references.
 */
object ComfyApiWorkflowParser {

    const val MAX_NODES = 128
    const val MAX_LINKS = 512
    private const val MAX_DIAGNOSTICS = 256

    private val NODE_FIELDS = setOf("class_type", "inputs", "_meta")

    fun parse(json: String, fileName: String? = null): ComfyApiParseResult {
        val root = try {
            ComfyApiJson.parse(json)
        } catch (error: ComfyJsonException) {
            return ComfyApiParseResult.Rejected(
                code = jsonErrorCode(error.reason),
                diagnostics = listOf(
                    VideoImportDiagnostic(
                        severity = VideoImportSeverity.ERROR,
                        nodeId = null,
                        path = error.path,
                        message = error.message ?: "JSON parse failed",
                    ),
                ),
            )
        }

        if (root !is ComfyJsonValue.ObjectValue) {
            return rejected(
                VideoErrorCode.INPUT_INVALID,
                null,
                "$",
                "API format 顶层必须是对象（nodeId → {class_type, inputs}）；若这是 UI format，请导出 API format",
            )
        }
        if (root["nodes"] is ComfyJsonValue.ArrayValue || root["links"] is ComfyJsonValue.ArrayValue) {
            return rejected(
                VideoErrorCode.INPUT_INVALID,
                null,
                "$",
                "检测到 ComfyUI UI format（nodes/links）；请导出 API format 后再导入",
            )
        }
        if (root.entries.isEmpty()) {
            return rejected(VideoErrorCode.INPUT_INVALID, null, "$", "API format 对象为空，没有节点")
        }
        if (root.entries.size > MAX_NODES) {
            return rejected(
                VideoErrorCode.INPUT_TOO_LARGE,
                null,
                "$",
                "节点数 ${root.entries.size} 超过上限 $MAX_NODES",
            )
        }

        val issues = mutableListOf<PendingIssue>()
        val nodes = ArrayList<ComfyApiNode>(root.entries.size)
        var linkCount = 0
        var linkLimitReported = false

        for ((nodeId, rawNode) in root.entries) {
            if (!isValidNodeId(nodeId)) {
                error(
                    issues,
                    VideoErrorCode.INPUT_INVALID,
                    nodeId,
                    nodeId,
                    "节点 id 必须非空、不超过 128 字符且不含控制字符",
                )
                continue
            }
            if (rawNode !is ComfyJsonValue.ObjectValue) {
                error(
                    issues,
                    VideoErrorCode.WORKFLOW_INVALID,
                    nodeId,
                    nodeId,
                    "节点 '${preview(nodeId)}' 必须是对象",
                )
                continue
            }
            val classType = (rawNode["class_type"] as? ComfyJsonValue.StringValue)?.value
            if (classType.isNullOrEmpty()) {
                error(
                    issues,
                    VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                    nodeId,
                    "$nodeId.class_type",
                    "节点 '${preview(nodeId)}' 缺少字符串 class_type",
                )
                continue
            }
            if (classType.length > 128 || classType.any { it < ' ' }) {
                error(
                    issues,
                    VideoErrorCode.INPUT_INVALID,
                    nodeId,
                    "$nodeId.class_type",
                    "class_type 必须不超过 128 字符且不含控制字符",
                )
                continue
            }
            val spec = ComfyCompatibilityMatrix.nodeSpec(classType)
            if (spec == null) {
                error(
                    issues,
                    VideoErrorCode.WORKFLOW_UNSUPPORTED_NODE,
                    nodeId,
                    nodeId,
                    "class_type '$classType' 不在固定支持集合内",
                )
                continue
            }

            for (field in rawNode.entries.keys) {
                if (field !in NODE_FIELDS) {
                    error(
                        issues,
                        VideoErrorCode.WORKFLOW_INVALID,
                        nodeId,
                        "$nodeId.$field",
                        "class_type '$classType' 不支持节点字段 '$field'",
                    )
                }
            }

            val meta = rawNode["_meta"]
            if (meta != null && meta !is ComfyJsonValue.ObjectValue) {
                error(
                    issues,
                    VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                    nodeId,
                    "$nodeId._meta",
                    "_meta 必须是对象",
                )
            }
            val title = ((meta as? ComfyJsonValue.ObjectValue)?.get("title") as? ComfyJsonValue.StringValue)?.value

            val inputsObject = rawNode["inputs"]
            if (inputsObject !is ComfyJsonValue.ObjectValue) {
                error(
                    issues,
                    VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                    nodeId,
                    "$nodeId.inputs",
                    "class_type '$classType' 缺少对象 inputs",
                )
                continue
            }

            val parsedInputs = LinkedHashMap<String, ComfyApiInputValue>()
            for ((inputName, inputValue) in inputsObject.entries) {
                val inputSpec = spec.input(inputName)
                if (inputSpec == null) {
                    error(
                        issues,
                        VideoErrorCode.WORKFLOW_UNSUPPORTED_NODE,
                        nodeId,
                        "$nodeId.inputs.$inputName",
                        "class_type '$classType' 不支持输入 '$inputName'",
                    )
                    continue
                }
                if (inputValue is ComfyJsonValue.NullValue) continue
                when (inputSpec.kind) {
                    ComfyInputKind.LINK -> {
                        val link = parseLink(inputValue, nodeId, inputName, issues) ?: continue
                        parsedInputs[inputName] = link
                        linkCount++
                        if (linkCount > MAX_LINKS && !linkLimitReported) {
                            linkLimitReported = true
                            error(
                                issues,
                                VideoErrorCode.INPUT_TOO_LARGE,
                                nodeId,
                                "$nodeId.inputs.$inputName",
                                "引用数量超过上限 $MAX_LINKS",
                            )
                        }
                    }

                    ComfyInputKind.STRING -> {
                        if (inputValue !is ComfyJsonValue.StringValue) {
                            error(
                                issues,
                                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                                nodeId,
                                "$nodeId.inputs.$inputName",
                                mismatchMessage(inputName, "string", inputValue),
                            )
                        } else {
                            parsedInputs[inputName] = ComfyApiInputValue.Literal(inputValue)
                        }
                    }

                    ComfyInputKind.INTEGER -> {
                        val number = inputValue as? ComfyJsonValue.NumberValue
                        if (number == null || number.scale != 0) {
                            error(
                                issues,
                                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                                nodeId,
                                "$nodeId.inputs.$inputName",
                                mismatchMessage(inputName, "integer", inputValue),
                            )
                        } else {
                            parsedInputs[inputName] = ComfyApiInputValue.Literal(number)
                        }
                    }

                    ComfyInputKind.DECIMAL -> {
                        if (inputValue !is ComfyJsonValue.NumberValue) {
                            error(
                                issues,
                                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                                nodeId,
                                "$nodeId.inputs.$inputName",
                                mismatchMessage(inputName, "number", inputValue),
                            )
                        } else {
                            parsedInputs[inputName] = ComfyApiInputValue.Literal(inputValue)
                        }
                    }
                }
            }

            for (required in spec.inputs) {
                if (required.required && !parsedInputs.containsKey(required.name)) {
                    error(
                        issues,
                        VideoErrorCode.WORKFLOW_INVALID,
                        nodeId,
                        "$nodeId.inputs.${required.name}",
                        "class_type '$classType' 缺少必需输入 '${required.name}'",
                    )
                }
            }

            nodes += ComfyApiNode(
                id = nodeId,
                classType = classType,
                inputs = parsedInputs,
                title = title,
            )
        }

        val nodesById = nodes.associateBy { it.id }
        detectCycle(nodes, issues)
        validateLinks(nodes, nodesById, issues)
        validateOutputRoot(nodes, issues)

        if (issues.any { it.diagnostic.severity == VideoImportSeverity.ERROR }) {
            val first = issues.first { it.diagnostic.severity == VideoImportSeverity.ERROR }
            return ComfyApiParseResult.Rejected(
                code = first.code,
                diagnostics = issues.map { it.diagnostic },
            )
        }

        return ComfyApiParseResult.Parsed(
            ComfyApiGraph(
                sourceSha256 = sha256(json),
                fileName = fileName,
                nodes = nodes,
            ),
        )
    }

    private fun parseLink(
        value: ComfyJsonValue,
        nodeId: String,
        inputName: String,
        issues: MutableList<PendingIssue>,
    ): ComfyApiInputValue.Link? {
        val path = "$nodeId.inputs.$inputName"
        if (value !is ComfyJsonValue.ArrayValue) {
            error(
                issues,
                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                nodeId,
                path,
                "input '$inputName' 期望 [nodeId, outputIndex] 引用，收到 ${describe(value)}",
            )
            return null
        }
        if (value.values.size != 2) {
            error(
                issues,
                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                nodeId,
                path,
                "input '$inputName' 的引用必须是二元数组 [nodeId, outputIndex]",
            )
            return null
        }
        val sourceId = (value.values[0] as? ComfyJsonValue.StringValue)?.value
        if (sourceId.isNullOrEmpty()) {
            error(
                issues,
                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                nodeId,
                path,
                "input '$inputName' 的引用第一个元素必须是节点 id 字符串",
            )
            return null
        }
        val outputNumber = value.values[1] as? ComfyJsonValue.NumberValue
        if (outputNumber == null || outputNumber.scale != 0 || outputNumber.unscaled !in 0L..Int.MAX_VALUE.toLong()) {
            error(
                issues,
                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                nodeId,
                path,
                "input '$inputName' 的引用第二个元素必须是非负整数 outputIndex",
            )
            return null
        }
        return ComfyApiInputValue.Link(sourceId, outputNumber.unscaled.toInt())
    }

    private fun validateLinks(
        nodes: List<ComfyApiNode>,
        nodesById: Map<String, ComfyApiNode>,
        issues: MutableList<PendingIssue>,
    ) {
        for (node in nodes) {
            val nodeSpec = ComfyCompatibilityMatrix.nodeSpec(node.classType) ?: continue
            for ((inputName, inputValue) in node.inputs) {
                val link = inputValue as? ComfyApiInputValue.Link ?: continue
                val path = "${node.id}/inputs/$inputName"
                val source = nodesById[link.nodeId]
                if (source == null) {
                    error(
                        issues,
                        VideoErrorCode.WORKFLOW_INVALID,
                        node.id,
                        path,
                        "input '$inputName' 引用了不存在的节点 '${link.nodeId}'",
                    )
                    continue
                }
                if (source.id == node.id) {
                    error(
                        issues,
                        VideoErrorCode.WORKFLOW_CYCLE,
                        node.id,
                        path,
                        "节点 '${node.id}' 的输入 '$inputName' 自引用",
                    )
                }
                val sourceSpec = ComfyCompatibilityMatrix.nodeSpec(source.classType)
                if (sourceSpec == null) continue
                if (link.outputIndex >= sourceSpec.outputCount) {
                    error(
                        issues,
                        VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                        node.id,
                        path,
                        "节点 '${source.id}' 只有 ${sourceSpec.outputCount} 个输出，outputIndex=${link.outputIndex} 越界",
                    )
                }
                val inputSpec = nodeSpec.input(inputName)
                if (inputSpec != null &&
                    inputSpec.sourceClassTypes.isNotEmpty() &&
                    source.classType !in inputSpec.sourceClassTypes
                ) {
                    error(
                        issues,
                        VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                        node.id,
                        path,
                        "input '$inputName' 不能连接 '${source.classType}'；允许 ${inputSpec.sourceClassTypes.sorted()}",
                    )
                }
                val allowedIndexes = inputSpec?.sourceOutputIndexes?.get(source.classType)
                if (allowedIndexes != null && link.outputIndex !in allowedIndexes) {
                    error(
                        issues,
                        VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                        node.id,
                        path,
                        "input '$inputName' 只能使用 '${source.classType}' 的输出 ${allowedIndexes.sorted()}，" +
                            "收到 outputIndex=${link.outputIndex}",
                    )
                }
            }
        }
    }

    private fun detectCycle(nodes: List<ComfyApiNode>, issues: MutableList<PendingIssue>) {
        val ids = nodes.map { it.id }
        if (ids.isEmpty()) return
        val indegree = ids.associateWith { 0 }.toMutableMap()
        val outgoing = HashMap<String, MutableList<String>>()
        for (node in nodes) {
            for (input in node.inputs.values) {
                val link = input as? ComfyApiInputValue.Link ?: continue
                if (link.nodeId !in indegree) continue
                outgoing.getOrPut(link.nodeId) { mutableListOf() }.add(node.id)
                indegree[node.id] = (indegree[node.id] ?: 0) + 1
            }
        }
        val queue = ArrayDeque<String>()
        indegree.forEach { (nodeId, degree) -> if (degree == 0) queue.add(nodeId) }
        var visited = 0
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            visited++
            for (next in outgoing[current].orEmpty()) {
                val remaining = (indegree[next] ?: 0) - 1
                indegree[next] = remaining
                if (remaining == 0) queue.add(next)
            }
        }
        if (visited != nodes.size) {
            val members = ids.filter { (indegree[it] ?: 0) > 0 }.sorted()
            error(
                issues,
                VideoErrorCode.WORKFLOW_CYCLE,
                members.firstOrNull(),
                members.joinToString(","),
                "图包含环：${members.joinToString(", ")}",
            )
        }
    }

    private fun validateOutputRoot(nodes: List<ComfyApiNode>, issues: MutableList<PendingIssue>) {
        val roots = nodes.filter { it.classType == ComfyCompatibilityMatrix.SAVE_VIDEO }
        if (roots.isEmpty()) {
            error(
                issues,
                VideoErrorCode.WORKFLOW_INVALID,
                nodes.firstOrNull()?.id,
                "$",
                "图缺少唯一的 SaveVideo 输出根",
            )
        } else {
            for (duplicate in roots.drop(1)) {
                error(
                    issues,
                    VideoErrorCode.WORKFLOW_INVALID,
                    duplicate.id,
                    duplicate.id,
                    "图包含多个 SaveVideo 输出根",
                )
            }
        }
    }

    private fun mismatchMessage(inputName: String, expected: String, actual: ComfyJsonValue): String {
        val suffix = if (actual is ComfyJsonValue.ArrayValue && actual.values.size == 2) {
            "；二元素数组只在 schema 明确声明为引用的字段上被视为 [nodeId, outputIndex]"
        } else {
            ""
        }
        return "input '$inputName' 期望 $expected，收到 ${describe(actual)}$suffix"
    }

    private fun describe(value: ComfyJsonValue): String = when (value) {
        is ComfyJsonValue.ObjectValue -> "object"
        is ComfyJsonValue.ArrayValue -> "array(${value.values.size})"
        is ComfyJsonValue.StringValue -> "string"
        is ComfyJsonValue.NumberValue -> "number"
        is ComfyJsonValue.BooleanValue -> "boolean"
        ComfyJsonValue.NullValue -> "null"
    }

    private fun isValidNodeId(nodeId: String): Boolean =
        nodeId.isNotEmpty() && nodeId.length <= 128 && nodeId.none { it < ' ' }

    private fun preview(value: String): String =
        if (value.length <= 64) value else value.take(64) + "..."

    private fun rejected(
        code: VideoErrorCode,
        nodeId: String?,
        path: String,
        message: String,
    ): ComfyApiParseResult.Rejected = ComfyApiParseResult.Rejected(
        code = code,
        diagnostics = listOf(
            VideoImportDiagnostic(
                severity = VideoImportSeverity.ERROR,
                nodeId = nodeId,
                path = path,
                message = message,
            ),
        ),
    )

    private fun error(
        issues: MutableList<PendingIssue>,
        code: VideoErrorCode,
        nodeId: String?,
        path: String,
        message: String,
    ) {
        if (issues.size >= MAX_DIAGNOSTICS) return
        issues += PendingIssue(
            code = code,
            diagnostic = VideoImportDiagnostic(
                severity = VideoImportSeverity.ERROR,
                nodeId = nodeId,
                path = path,
                message = message,
            ),
        )
    }

    private fun jsonErrorCode(reason: ComfyJsonErrorReason): VideoErrorCode = when (reason) {
        ComfyJsonErrorReason.INPUT_TOO_LARGE,
        ComfyJsonErrorReason.DEPTH_LIMIT,
        ComfyJsonErrorReason.STRING_LIMIT -> VideoErrorCode.INPUT_TOO_LARGE

        ComfyJsonErrorReason.EMPTY,
        ComfyJsonErrorReason.DUPLICATE_KEY,
        ComfyJsonErrorReason.NUMBER_OUT_OF_RANGE,
        ComfyJsonErrorReason.SYNTAX,
        ComfyJsonErrorReason.TRAILING_CONTENT -> VideoErrorCode.INPUT_INVALID
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private data class PendingIssue(
        val code: VideoErrorCode,
        val diagnostic: VideoImportDiagnostic,
    )
}

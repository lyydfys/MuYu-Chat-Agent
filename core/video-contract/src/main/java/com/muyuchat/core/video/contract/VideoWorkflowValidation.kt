package com.muyuchat.core.video.contract

/**
 * Pure structural validation of a workflow document (proposal §4.1 rules 1–6).
 *
 * Validation is total: every check emits an anchored [VideoWorkflowIssue] and collection
 * continues, so the editor can surface the full diagnosis at once. Nothing here reads disk,
 * resolves models or contacts a backend — asset-level checks belong to the compiler/owner.
 */
data class VideoWorkflowIssue(
    val code: VideoErrorCode,
    /** `nodeId` or `nodeId/port` or `nodeId/port/param`; always anchored to a concrete site. */
    val path: String,
    val nodeId: String? = null,
    val port: String? = null,
    val detail: String,
)

/**
 * Structural validation of a workflow document. The Wan frame/geometry baseline rules live in
 * [VideoWanBaselineRules]; parameter/port value checks live in [VideoWorkflowParamValidation].
 */
fun validateVideoWorkflow(document: VideoWorkflowDocument): List<VideoWorkflowIssue> {
    val issues = mutableListOf<VideoWorkflowIssue>()

    // 1. Duplicate node ids.
    val nodesById = HashMap<String, VideoNode>(document.nodes.size)
    for (node in document.nodes) {
        if (nodesById.put(node.nodeId, node) != null) {
            issues += VideoWorkflowIssue(
                code = VideoErrorCode.WORKFLOW_INVALID,
                path = node.nodeId,
                nodeId = node.nodeId,
                detail = "duplicate node id '${node.nodeId}'",
            )
        }
    }

    // 2. Unknown / unsupported node types and version bounds.
    val resolvedTypes = HashMap<String, VideoNodeTypeId>()
    for (node in document.nodes) {
        val resolved = VideoNodeTypeId.fromTypeId(node.typeId)
        if (resolved == null) {
            issues += VideoWorkflowIssue(
                code = VideoErrorCode.WORKFLOW_UNSUPPORTED_NODE,
                path = node.nodeId,
                nodeId = node.nodeId,
                detail = "node type '${node.typeId}' is not registered",
            )
            continue
        }
        val current = VideoNodeTypeRegistry.currentVersion(resolved)
        if (node.typeVersion < 1 || node.typeVersion > current) {
            issues += VideoWorkflowIssue(
                code = VideoErrorCode.WORKFLOW_UNSUPPORTED_NODE,
                path = node.nodeId,
                nodeId = node.nodeId,
                detail = "node type '${node.typeId}' version ${node.typeVersion} outside 1..$current",
            )
            continue
        }
        resolvedTypes[node.nodeId] = resolved
    }

    // 3. Edge endpoints, self-loops, duplicate targets and port kinds.
    data class PortSite(val nodeId: String, val port: String)

    val connectedInputs = HashMap<PortSite, VideoEdge>()
    for (edge in document.edges) {
        val site = "${edge.fromNodeId}/${edge.fromPort} -> ${edge.toNodeId}/${edge.toPort}"
        if (edge.fromNodeId == edge.toNodeId) {
            issues += VideoWorkflowIssue(
                code = VideoErrorCode.WORKFLOW_CYCLE,
                path = site,
                nodeId = edge.fromNodeId,
                port = edge.fromPort,
                detail = "self-loop on node '${edge.fromNodeId}'",
            )
        }
        val fromType = resolvedTypes[edge.fromNodeId]
        val toType = resolvedTypes[edge.toNodeId]
        if (fromType == null || toType == null) {
            // Endpoint existence/unknown-type already reported (or node id dangling below).
            if (nodesById[edge.fromNodeId] == null) {
                issues += VideoWorkflowIssue(
                    code = VideoErrorCode.WORKFLOW_INVALID,
                    path = edge.fromNodeId,
                    nodeId = edge.fromNodeId,
                    detail = "edge references missing source node '${edge.fromNodeId}'",
                )
            }
            if (nodesById[edge.toNodeId] == null) {
                issues += VideoWorkflowIssue(
                    code = VideoErrorCode.WORKFLOW_INVALID,
                    path = edge.toNodeId,
                    nodeId = edge.toNodeId,
                    detail = "edge references missing target node '${edge.toNodeId}'",
                )
            }
            continue
        }
        val outPort = VideoNodeTypeRegistry.outputs(fromType, nodesById.getValue(edge.fromNodeId).typeVersion)
            .firstOrNull { it.name == edge.fromPort }
        if (outPort == null) {
            issues += VideoWorkflowIssue(
                code = VideoErrorCode.WORKFLOW_INVALID,
                path = "$site",
                nodeId = edge.fromNodeId,
                port = edge.fromPort,
                detail = "output port '${edge.fromPort}' does not exist on '${edge.fromNodeId}'",
            )
        }
        val inDecl = VideoNodeTypeRegistry.inputs(toType, nodesById.getValue(edge.toNodeId).typeVersion)
            .firstOrNull { it.name == edge.toPort }
        if (inDecl == null) {
            issues += VideoWorkflowIssue(
                code = VideoErrorCode.WORKFLOW_INVALID,
                path = site,
                nodeId = edge.toNodeId,
                port = edge.toPort,
                detail = "input port '${edge.toPort}' does not exist on '${edge.toNodeId}'",
            )
        } else if (inDecl.feed == VideoPortFeed.LITERAL_ONLY) {
            issues += VideoWorkflowIssue(
                code = VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                path = site,
                nodeId = edge.toNodeId,
                port = edge.toPort,
                detail = "input port '${edge.toPort}' accepts a literal value only",
            )
        }
        if (outPort != null && inDecl != null && outPort.kind != inDecl.kind) {
            issues += VideoWorkflowIssue(
                code = VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                path = site,
                nodeId = edge.toNodeId,
                port = edge.toPort,
                detail = "port kind mismatch: '${edge.fromPort}' is ${outPort.kind}, " +
                    "'${edge.toPort}' expects ${inDecl.kind}",
            )
        }
        if (connectedInputs.put(PortSite(edge.toNodeId, edge.toPort), edge) != null) {
            issues += VideoWorkflowIssue(
                code = VideoErrorCode.WORKFLOW_INVALID,
                path = site,
                nodeId = edge.toNodeId,
                port = edge.toPort,
                detail = "input port '${edge.toPort}' has more than one incoming edge",
            )
        }
    }

    // 4. Required ports, literal/edge exclusivity, port literal kinds and parameter bounds.
    // Literal values live in `params`, keyed either by a LITERAL-feed port name or by a pure
    // parameter name declared by the node type. CONNECTED_ONLY ports must never carry one.
    for (node in document.nodes) {
        val type = resolvedTypes[node.nodeId] ?: continue
        val inDecls = VideoNodeTypeRegistry.inputs(type, node.typeVersion)
        val paramDecls = VideoNodeTypeRegistry.params(type, node.typeVersion)
        for (decl in inDecls) {
            val connected = connectedInputs.any { it.key.nodeId == node.nodeId && it.key.port == decl.name }
            val literal = node.params[decl.name]
            if (decl.feed == VideoPortFeed.CONNECTED_ONLY && literal != null) {
                issues += VideoWorkflowIssue(
                    code = VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                    path = "${node.nodeId}/${decl.name}",
                    nodeId = node.nodeId,
                    port = decl.name,
                    detail = "port '${decl.name}' must be connected, not a literal",
                )
            }
            if (decl.feed == VideoPortFeed.LITERAL_ONLY && connected) {
                issues += VideoWorkflowIssue(
                    code = VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                    path = "${node.nodeId}/${decl.name}",
                    nodeId = node.nodeId,
                    port = decl.name,
                    detail = "port '${decl.name}' accepts a literal only, not an edge",
                )
            }
            if (connected && literal != null) {
                issues += VideoWorkflowIssue(
                    code = VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                    path = "${node.nodeId}/${decl.name}",
                    nodeId = node.nodeId,
                    port = decl.name,
                    detail = "port '${decl.name}' is fed by both an edge and a literal",
                )
            }
            if (decl.required && !connected && literal == null) {
                issues += VideoWorkflowIssue(
                    code = VideoErrorCode.WORKFLOW_INVALID,
                    path = "${node.nodeId}/${decl.name}",
                    nodeId = node.nodeId,
                    port = decl.name,
                    detail = "required input '${decl.name}' is neither connected nor set",
                )
            }
            if (literal != null) {
                validatePortLiteralKind(node, decl, literal, issues)
            }
        }
        val declByName = paramDecls.associateBy { it.name }
        for ((name, value) in node.params) {
            if (inDecls.any { it.name == name }) {
                continue // Port literal handled above.
            }
            val decl = declByName[name]
            if (decl == null) {
                issues += VideoWorkflowIssue(
                    code = VideoErrorCode.WORKFLOW_INVALID,
                    path = "${node.nodeId}/$name",
                    nodeId = node.nodeId,
                    detail = "unknown parameter '$name' on node '${node.nodeId}'",
                )
            } else {
                validateParamValue(node, decl, value, issues)
            }
        }
        for (decl in paramDecls) {
            if (decl.required && !node.params.containsKey(decl.name)) {
                issues += VideoWorkflowIssue(
                    code = VideoErrorCode.WORKFLOW_INVALID,
                    path = "${node.nodeId}/${decl.name}",
                    nodeId = node.nodeId,
                    detail = "required parameter '${decl.name}' is missing",
                )
            }
        }
    }

    // 5. Cycles (documented separately from self-loops, which step 3 already anchored).
    issues += detectCycles(document, resolvedTypes)

    // 6. An executable native graph needs exactly one output root (save asset node).
    val saveRoots = document.nodes.filter { resolvedTypes[it.nodeId] == VideoNodeTypeId.SAVE_ASSET }
    if (saveRoots.isEmpty() && document.nodes.isNotEmpty()) {
        issues += VideoWorkflowIssue(
            code = VideoErrorCode.WORKFLOW_INVALID,
            path = document.nodes.first().nodeId,
            nodeId = document.nodes.first().nodeId,
            detail = "graph has no save-asset output root",
        )
    } else if (saveRoots.size > 1) {
        for (duplicate in saveRoots.drop(1)) {
            issues += VideoWorkflowIssue(
                code = VideoErrorCode.WORKFLOW_INVALID,
                path = duplicate.nodeId,
                nodeId = duplicate.nodeId,
                detail = "graph has more than one save-asset output root",
            )
        }
    }

    return issues
}

/** Kahn cycle check over edges whose endpoints are known node types; unknown ends are skipped. */
private fun detectCycles(
    document: VideoWorkflowDocument,
    resolvedTypes: Map<String, VideoNodeTypeId>,
): List<VideoWorkflowIssue> {
    val issues = mutableListOf<VideoWorkflowIssue>()
    val nodes = document.nodes.map { it.nodeId }.filter { resolvedTypes.containsKey(it) }.toSet()
    val indegree = HashMap<String, Int>().apply { nodes.forEach { put(it, 0) } }
    val outgoing = HashMap<String, MutableList<String>>()
    for (edge in document.edges) {
        if (edge.fromNodeId !in nodes || edge.toNodeId !in nodes) continue
        indegree[edge.toNodeId] = (indegree[edge.toNodeId] ?: 0) + 1
        outgoing.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge.toNodeId)
    }
    val queue = ArrayDeque<String>()
    indegree.forEach { (id, degree) -> if (degree == 0) queue.add(id) }
    var visited = 0
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        visited += 1
        for (next in outgoing[current].orEmpty()) {
            val remaining = (indegree[next] ?: 0) - 1
            indegree[next] = remaining
            if (remaining == 0) queue.add(next)
        }
    }
    if (visited != nodes.size) {
        val cycleMembers = nodes.filter { (indegree[it] ?: 0) > 0 }.sorted()
        issues += VideoWorkflowIssue(
            code = VideoErrorCode.WORKFLOW_CYCLE,
            path = cycleMembers.joinToString(","),
            nodeId = cycleMembers.firstOrNull(),
            detail = "graph contains a cycle through ${cycleMembers.joinToString(", ")}",
        )
    }
    return issues
}

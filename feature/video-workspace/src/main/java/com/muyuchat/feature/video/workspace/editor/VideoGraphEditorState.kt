package com.muyuchat.feature.video.workspace.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.muyuchat.core.video.contract.VideoEdge
import com.muyuchat.core.video.contract.VideoNode
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeUi
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoWorkflowDocument
import com.muyuchat.core.video.contract.VideoWorkflowIssue
import com.muyuchat.core.video.contract.VideoNodeTypeRegistry
import com.muyuchat.core.video.contract.validateVideoWorkflow

/**
 * The draft editor core (proposal §9.4.1). Every edit command produces a NEW immutable draft
 * document and recomputes the shared contract validation; nothing here touches the UI, runs or
 * storage. Undo/redo is bounded and covers parameter edits and node/edge operations; canvas
 * positions are UI-only state and deliberately NOT history entries (dragging would flood the
 * stack — position is excluded from the executable digest for the same reason).
 *
 * A connection is accepted only when it is type-correct and acyclic; a rejected command leaves
 * the draft untouched and returns an anchored reason. Nothing silently deletes nodes or adds
 * edges on the user's behalf.
 */
class VideoGraphEditorState(
    initialDocument: VideoWorkflowDocument,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
    // Compose state: every edit replaces the immutable document, and the page's summaries,
    // validation badges and undo/redo affordances must recompose from that change.
    var document: VideoWorkflowDocument by mutableStateOf(initialDocument)
        private set

    var issues: List<VideoWorkflowIssue> by mutableStateOf(validateVideoWorkflow(initialDocument))
        private set

    private val undoStack = ArrayDeque<VideoWorkflowDocument>()
    private val redoStack = ArrayDeque<VideoWorkflowDocument>()
    private var idCounter: Int = nextTemplateCounter(initialDocument)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Replaces the whole draft (template switch); clears history — a new editing session. */
    fun resetTo(document: VideoWorkflowDocument) {
        this.document = document
        this.issues = validateVideoWorkflow(document)
        undoStack.clear()
        redoStack.clear()
        idCounter = nextTemplateCounter(document)
    }

    fun setParam(nodeId: String, name: String, value: VideoNodeValue): Boolean {
        val node = document.node(nodeId) ?: return false
        return commit(document.copy(nodes = document.nodes.map { n ->
            if (n.nodeId == nodeId) n.copy(params = n.params + (name to value)) else n
        }))
    }

    fun removeParam(nodeId: String, name: String): Boolean {
        val node = document.node(nodeId) ?: return false
        if (!node.params.containsKey(name)) return false
        return commit(document.copy(nodes = document.nodes.map { n ->
            if (n.nodeId == nodeId) n.copy(params = n.params - name) else n
        }))
    }

    /** Canvas position/collapsed/label are UI-only: applied without history or revalidation. */
    fun setNodeUi(nodeId: String, ui: VideoNodeUi): Boolean {
        val node = document.node(nodeId) ?: return false
        document = document.copy(nodes = document.nodes.map { n ->
            if (n.nodeId == nodeId) n.copy(ui = ui) else n
        })
        return true
    }

    fun addNode(type: VideoNodeTypeId): String? {
        val nodeId = freshNodeId(type)
        val node = VideoNode(
            nodeId = nodeId,
            typeId = type.typeId,
            typeVersion = VideoNodeTypeRegistry.currentVersion(type),
            ui = VideoNodeUi(positionX = 40f, positionY = 120f * document.nodes.size + 60f),
        )
        return if (commit(document.copy(nodes = document.nodes + node))) nodeId else null
    }

    /** Edges that deleting this node would remove — shown BEFORE the destructive command. */
    fun deleteImpact(nodeId: String): List<VideoEdge> =
        document.edges.filter { it.fromNodeId == nodeId || it.toNodeId == nodeId }

    fun deleteNode(nodeId: String): Boolean {
        if (document.node(nodeId) == null) return false
        return commit(
            document.copy(
                nodes = document.nodes.filterNot { it.nodeId == nodeId },
                edges = document.edges.filterNot { it.fromNodeId == nodeId || it.toNodeId == nodeId },
            ),
        )
    }

    fun connect(fromNodeId: String, fromPort: String, toNodeId: String, toPort: String): ConnectDecision {
        if (fromNodeId == toNodeId) return ConnectDecision.Rejected("不能连接到节点自身")
        val fromNode = document.node(fromNodeId) ?: return ConnectDecision.Rejected("源节点不存在")
        val toNode = document.node(toNodeId) ?: return ConnectDecision.Rejected("目标节点不存在")
        val fromType = VideoNodeTypeId.fromTypeId(fromNode.typeId)
            ?: return ConnectDecision.Rejected("源节点类型未注册")
        val toType = VideoNodeTypeId.fromTypeId(toNode.typeId)
            ?: return ConnectDecision.Rejected("目标节点类型未注册")

        val outPort = VideoNodeTypeRegistry.outputs(fromType, fromNode.typeVersion)
            .firstOrNull { it.name == fromPort }
            ?: return ConnectDecision.Rejected("输出端口 '$fromPort' 不存在于 '$fromNodeId'")
        val inDecl = VideoNodeTypeRegistry.inputs(toType, toNode.typeVersion)
            .firstOrNull { it.name == toPort }
            ?: return ConnectDecision.Rejected("输入端口 '$toPort' 不存在于 '$toNodeId'")
        if (inDecl.feed == com.muyuchat.core.video.contract.VideoPortFeed.LITERAL_ONLY) {
            return ConnectDecision.Rejected("输入端口 '$toPort' 只接受字面量，不接受连线")
        }
        if (outPort.kind != inDecl.kind) {
            return ConnectDecision.Rejected("端口类型不匹配：'$fromPort' 是 ${outPort.kind}，'$toPort' 需要 ${inDecl.kind}")
        }
        if (document.edges.any { it.toNodeId == toNodeId && it.toPort == toPort }) {
            return ConnectDecision.Rejected("输入端口 '$toPort' 已有连线，请先断开原连线")
        }
        if (createsCycle(fromNodeId, toNodeId)) {
            return ConnectDecision.Rejected("这条连线会构成环，被拒绝")
        }

        val edge = VideoEdge(fromNodeId, fromPort, toNodeId, toPort)
        return if (commit(document.copy(edges = document.edges + edge))) {
            ConnectDecision.Connected(edge)
        } else {
            ConnectDecision.Rejected("草稿更新失败")
        }
    }

    fun disconnect(edge: VideoEdge): Boolean =
        commit(document.copy(edges = document.edges.filterNot { it == edge }))

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(document)
        trimHistory(redoStack)
        document = previous
        issues = validateVideoWorkflow(previous)
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(document)
        trimHistory(undoStack)
        document = next
        issues = validateVideoWorkflow(next)
        return true
    }

    private fun commit(next: VideoWorkflowDocument): Boolean {
        undoStack.addLast(document)
        trimHistory(undoStack)
        redoStack.clear()
        document = next
        issues = validateVideoWorkflow(next)
        return true
    }

    private fun trimHistory(stack: ArrayDeque<VideoWorkflowDocument>) {
        while (stack.size > historyLimit) stack.removeFirst()
    }

    /** Would adding from -> to close a loop (can `to` already reach `from`)? */
    private fun createsCycle(fromNodeId: String, toNodeId: String): Boolean {
        val outgoing = HashMap<String, MutableList<String>>()
        document.edges.forEach { edge ->
            outgoing.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge.toNodeId)
        }
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(toNodeId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == fromNodeId) return true
            if (!seen.add(current)) continue
            queue.addAll(outgoing[current].orEmpty())
        }
        return false
    }

    private fun freshNodeId(type: VideoNodeTypeId): String {
        val prefix = "n_${type.name.lowercase()}"
        var candidate: String
        do {
            idCounter += 1
            candidate = "${prefix}_$idCounter"
        } while (document.node(candidate) != null)
        return candidate
    }

    private fun nextTemplateCounter(document: VideoWorkflowDocument): Int {
        // Start above any numeric suffix already present so generated ids never collide.
        return document.nodes.size * 10 + 10
    }

    sealed class ConnectDecision {
        data class Connected(val edge: VideoEdge) : ConnectDecision()

        data class Rejected(val reason: String) : ConnectDecision()
    }

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 50
    }
}

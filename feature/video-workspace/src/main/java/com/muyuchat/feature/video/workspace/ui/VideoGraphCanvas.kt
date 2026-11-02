package com.muyuchat.feature.video.workspace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaShape
import com.muyuchat.core.video.contract.VideoNode
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeTypeRegistry
import com.muyuchat.core.video.contract.VideoPortFeed
import com.muyuchat.core.video.contract.VideoPortKind
import com.muyuchat.core.video.contract.VideoWorkflowDocument
import com.muyuchat.feature.video.workspace.VideoWorkspaceViewModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val NODE_WIDTH = 260.dp
private const val NODE_HEADER_DP = 30
private const val NODE_SUMMARY_DP = 32
// Each port row is a full 48dp touch target; the visible dot stays 16dp inside it.
private const val NODE_PORT_ROW_DP = 48
// Row 0 centre below the node top: header + body padding + two-line summary + half a row.
private const val NODE_PORT_BASE_DP = 90f
private const val NODE_PORT_STEP_DP = 48f
// The clickable port box is kept fully inside the node card (>=48dp) so accessibility bounds are
// never clipped by a neighbouring card; the 16dp dot is offset back to the card edge, unchanged.
private val PORT_HIT_DP = 48.dp
private val PORT_DOT_DP = 16.dp

/**
 * ComfyUI-style node canvas: a dotted work surface with node cards, bezier edges and pan/zoom.
 * The draft remains the single source of truth; positions are UI-only. Connections can be made
 * by tapping ports here or through the accessible node list (never drag-only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoGraphCanvas(
    viewModel: VideoWorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    val document = viewModel.document()
    val layout = graphLayout(document)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var pendingFrom by remember { mutableStateOf<Pair<String, String>?>(null) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var showNodeList by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val wireColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "节点图",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            A11yLabeledControl(
                contentDescription = "节点列表：打开节点与连接列表",
                onClick = { showNodeList = true },
            ) {
                McaButton(
                    onClick = { showNodeList = true },
                    style = McaButtonStyle.Text,
                    modifier = Modifier.heightIn(min = 40.dp).testTag("video.canvas_node_list"),
                ) { Text("节点列表") }
            }
            A11yLabeledControl(
                contentDescription = "添加节点",
                onClick = { addMenuOpen = true },
            ) {
                McaButton(
                    onClick = { addMenuOpen = true },
                    style = McaButtonStyle.Outlined,
                    modifier = Modifier.heightIn(min = 40.dp),
                ) { Text("添加节点") }
            }
            DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                VideoNodeTypeId.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(VideoNodePresentations.labelOf(type.typeId)) },
                        onClick = {
                            addMenuOpen = false
                            viewModel.addNode(type)
                        },
                    )
                }
            }
        }

        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(0.4f, 2.5f)
            offset += panChange
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 460.dp)
                .clip(RoundedCornerShape(18.dp))
                .drawBehind {
                    val step = 28.dp.toPx() * scale
                    if (step >= 6f) {
                        var x = ((offset.x % step) + step) % step
                        while (x < size.width) {
                            var y = ((offset.y % step) + step) % step
                            while (y < size.height) {
                                drawCircle(gridColor, radius = 1.dp.toPx(), center = Offset(x, y))
                                y += step
                            }
                            x += step
                        }
                    }
                }
                .testTag("video.canvas")
                .semantics { contentDescription = "节点图画布" }
                .transformable(transformState)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { pendingFrom = null })
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val widthPx = NODE_WIDTH.toPx() * scale
                document.edges.forEach { edge ->
                    val from = document.nodes.firstOrNull { it.nodeId == edge.fromNodeId } ?: return@forEach
                    val to = document.nodes.firstOrNull { it.nodeId == edge.toNodeId } ?: return@forEach
                    val fromType = VideoNodeTypeId.fromTypeId(from.typeId) ?: return@forEach
                    val toType = VideoNodeTypeId.fromTypeId(to.typeId) ?: return@forEach
                    val outIndex = VideoNodeTypeRegistry.outputs(fromType, from.typeVersion)
                        .indexOfFirst { it.name == edge.fromPort }
                    val inIndex = VideoNodeTypeRegistry.inputs(toType, to.typeVersion)
                        .indexOfFirst { it.name == edge.toPort }
                    if (outIndex < 0 || inIndex < 0) return@forEach
                    val f = nodeOrigin(from, layout)
                    val t = nodeOrigin(to, layout)
                    val x1 = f.x * density * scale + offset.x + widthPx
                    val y1 = (f.y * density * scale + offset.y) + (NODE_PORT_BASE_DP + NODE_PORT_STEP_DP * outIndex).dp.toPx() * scale
                    val x2 = t.x * density * scale + offset.x
                    val y2 = (t.y * density * scale + offset.y) + (NODE_PORT_BASE_DP + NODE_PORT_STEP_DP * inIndex).dp.toPx() * scale
                    val bend = max(56.dp.toPx(), abs(x2 - x1) * 0.5f)
                    val path = Path().apply {
                        moveTo(x1, y1)
                        cubicTo(x1 + bend, y1, x2 - bend, y2, x2, y2)
                    }
                    drawPath(path, color = wireColor, style = Stroke(width = 2.dp.toPx() * scale, cap = StrokeCap.Round))
                }
            }

            document.nodes.forEach { node ->
                val position = nodeOrigin(node, layout)
                CanvasNodeBox(
                    node = node,
                    position = position,
                    density = density,
                    scale = scale,
                    offset = offset,
                    selected = viewModel.selectedNodeId.value == node.nodeId,
                    hasIssues = viewModel.issues().any { it.nodeId == node.nodeId },
                    onTap = { viewModel.selectNode(node.nodeId) },
                    onDrag = { delta ->
                        viewModel.moveNode(
                            node.nodeId,
                            position.x + delta.x / (scale * density),
                            position.y + delta.y / (scale * density),
                        )
                    },
                    onOutputPortTap = { port -> pendingFrom = node.nodeId to port },
                    onInputPortTap = { port ->
                        val from = pendingFrom
                        if (from != null) {
                            viewModel.connect(from.first, from.second, node.nodeId, port)
                            pendingFrom = null
                        }
                    },
                    pendingOutput = pendingFrom?.first == node.nodeId,
                )
            }
            if (pendingFrom != null) {
                Text(
                    "已选择输出端口，点击目标输入端口完成连线",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(6.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = "已选择输出端口，点击目标输入端口完成连线"
                        },
                )
            }
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val zoomState = "当前缩放 ${(scale * 100).roundToInt()}%"
                CanvasToolButton("＋", "放大画布", zoomState) { scale = (scale * 1.2f).coerceAtMost(2.5f) }
                CanvasToolButton("−", "缩小画布", zoomState) { scale = (scale / 1.2f).coerceAtLeast(0.4f) }
                CanvasToolButton("1:1", "复位画布", zoomState) {
                    scale = 1f
                    offset = Offset.Zero
                }
            }
        }
        Text(
            "双指缩放与平移；拖动节点；点输出圆点再点输入圆点连线。节点列表提供按钮式替代。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (showNodeList) {
        ModalBottomSheet(
            onDismissRequest = { showNodeList = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            AccessibleNodeList(viewModel)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CanvasToolButton(
    label: String,
    description: String,
    stateDescription: String,
    onClick: () -> Unit,
) {
    A11yLabeledControl(
        contentDescription = description,
        stateDescription = stateDescription,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        McaButton(
            onClick = onClick,
            style = McaButtonStyle.Outlined,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(label)
        }
    }
}

private fun nodeOrigin(node: VideoNode, layout: Map<String, Offset>): Offset {
    val x = node.ui.positionX
    val y = node.ui.positionY
    return if (x != null && y != null) Offset(x, y) else layout[node.nodeId] ?: Offset(48f, 96f)
}

/**
 * Auto-layout for template nodes (their UI positions are unset): longest-path depth selects the
 * column, document order selects the row. A node the user dragged keeps its explicit position.
 */
private fun graphLayout(document: VideoWorkflowDocument): Map<String, Offset> {
    val incoming = document.edges.groupBy { it.toNodeId }
    val depths = mutableMapOf<String, Int>()
    val visiting = mutableSetOf<String>()
    fun depthOf(nodeId: String): Int {
        depths[nodeId]?.let { return it }
        if (!visiting.add(nodeId)) return 0
        val depth = incoming[nodeId].orEmpty().maxOfOrNull { depthOf(it.fromNodeId) + 1 } ?: 0
        visiting.remove(nodeId)
        depths[nodeId] = depth
        return depth
    }
    document.nodes.forEach { depthOf(it.nodeId) }
    val positions = mutableMapOf<String, Offset>()
    document.nodes.groupBy { depths[it.nodeId] ?: 0 }.forEach { (depth, nodes) ->
        nodes.forEachIndexed { row, node ->
            positions[node.nodeId] = Offset(52f + depth * 286f, 84f + row * 300f)
        }
    }
    return positions
}

@Composable
private fun CanvasNodeBox(
    node: VideoNode,
    position: Offset,
    density: Float,
    scale: Float,
    offset: Offset,
    selected: Boolean,
    hasIssues: Boolean,
    onTap: () -> Unit,
    onDrag: (Offset) -> Unit,
    onOutputPortTap: (String) -> Unit,
    onInputPortTap: (String) -> Unit,
    pendingOutput: Boolean,
) {
    val type = VideoNodeTypeId.fromTypeId(node.typeId)
    val inputs = type?.let { VideoNodeTypeRegistry.inputs(it, node.typeVersion) }.orEmpty()
    val outputs = type?.let { VideoNodeTypeRegistry.outputs(it, node.typeVersion) }.orEmpty()
    val headerColor = nodeHeaderColor(type)

    Box(
        modifier = Modifier
            .offset { IntOffset((position.x * density * scale + offset.x).roundToInt(), (position.y * density * scale + offset.y).roundToInt()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            }
            .width(NODE_WIDTH)
            .pointerInput(node.nodeId) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount / scale)
                }
            }
            .pointerInput(node.nodeId) {
                detectTapGestures { onTap() }
            }
            .semantics {
                contentDescription = "${VideoNodePresentations.labelOf(node.typeId)}，${VideoNodePresentations.summaryOf(node)}"
                role = Role.Button
                this.selected = selected
                when {
                    pendingOutput -> stateDescription = "已选择输出端口"
                    selected -> stateDescription = "已选中"
                    hasIssues -> stateDescription = "存在校验问题"
                }
                onClick(label = "打开节点参数") {
                    onTap()
                    true
                }
            }
            .focusable()
            .onKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.Spacebar)
                ) {
                    onTap()
                    true
                } else {
                    false
                }
            },
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = if (hasIssues) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            shape = McaShape.Card,
            border = BorderStroke(
                width = if (selected || pendingOutput) 1.5.dp else 0.5.dp,
                color = when {
                    pendingOutput -> MaterialTheme.colorScheme.primary
                    selected -> MaterialTheme.colorScheme.secondary
                    hasIssues -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
            ),
            shadowElevation = if (selected || pendingOutput) 6.dp else 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Surface(color = headerColor, shape = McaShape.Card) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(NODE_HEADER_DP.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            VideoNodePresentations.labelOf(node.typeId),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (hasIssues) {
                            Text("!", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(
                        VideoNodePresentations.summaryOf(node),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().height(NODE_SUMMARY_DP.dp),
                    )
                    repeat(maxOf(inputs.size, outputs.size)) { index ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(NODE_PORT_ROW_DP.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                inputs.getOrNull(index)?.let { "←${it.name}" }.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                outputs.getOrNull(index)?.let { "${it.name}→" }.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        outputs.forEachIndexed { index, port ->
            PortDot(
                kind = port.kind,
                alignment = Alignment.TopEnd,
                offsetDp = Offset(0f, NODE_PORT_BASE_DP + NODE_PORT_STEP_DP * index - 24f),
                density = density,
                contentDescription = "${VideoNodePresentations.labelOf(node.typeId)} 输出端口 ${port.name}",
                highlight = pendingOutput,
                dotOffsetX = PORT_HIT_DP / 2f,
                onTap = { onOutputPortTap(port.name) },
            )
        }
        inputs.forEachIndexed { index, port ->
            if (port.feed != VideoPortFeed.LITERAL_ONLY) {
                PortDot(
                    kind = port.kind,
                    alignment = Alignment.TopStart,
                    offsetDp = Offset(0f, NODE_PORT_BASE_DP + NODE_PORT_STEP_DP * index - 24f),
                    density = density,
                    contentDescription = "${VideoNodePresentations.labelOf(node.typeId)} 输入端口 ${port.name}",
                    highlight = false,
                    dotOffsetX = -PORT_HIT_DP / 2f,
                    onTap = { onInputPortTap(port.name) },
                )
            }
        }
    }
}

@Composable
private fun nodeHeaderColor(type: VideoNodeTypeId?): Color = when (type) {
    VideoNodeTypeId.INPUT_TEXT,
    VideoNodeTypeId.INPUT_IMAGE,
    -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)

    VideoNodeTypeId.WAN22_TI2V_5B_MODEL,
    VideoNodeTypeId.WAN_CONDITIONING,
    -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)

    VideoNodeTypeId.WAN_SAMPLER -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
}

@Composable
private fun BoxScope.PortDot(
    kind: VideoPortKind,
    alignment: Alignment,
    offsetDp: Offset,
    density: Float,
    contentDescription: String,
    highlight: Boolean,
    dotOffsetX: Dp,
    onTap: () -> Unit,
) {
    val color = when (kind) {
        VideoPortKind.TEXT -> MaterialTheme.colorScheme.tertiary
        VideoPortKind.IMAGE_REF -> MaterialTheme.colorScheme.secondary
        VideoPortKind.MODEL_REF -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    A11yLabeledControl(
        contentDescription = contentDescription,
        stateDescription = if (highlight) "已选择" else null,
        onClick = onTap,
        clickActionLabel = "连接端口",
        modifier = Modifier
            .align(alignment)
            .offset { IntOffset((offsetDp.x * density).roundToInt(), (offsetDp.y * density).roundToInt()) }
            .sizeIn(minWidth = PORT_HIT_DP, minHeight = PORT_HIT_DP),
    ) {
        Box(
            modifier = Modifier
                .size(PORT_HIT_DP)
                .testTag("video.port")
                .clickable(role = Role.Button, onClickLabel = "连接端口", onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = if (highlight) MaterialTheme.colorScheme.error else color,
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surface),
                // Hit-area-only change: the box moved fully inside the card, so the dot is pushed
                // back to its original centre on the card edge. Its drawn bounds do not move.
                modifier = Modifier.offset(x = dotOffsetX).size(PORT_DOT_DP),
            ) {}
        }
    }
}

/** Button-first alternative to the canvas (drag-only is never the only way). */
@Composable
internal fun AccessibleNodeList(viewModel: VideoWorkspaceViewModel) {
    val document = viewModel.document()
    var connectSource by remember { mutableStateOf<Pair<String, String>?>(null) }
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "节点与连接列表",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        document.nodes.forEach { node ->
            val type = VideoNodeTypeId.fromTypeId(node.typeId)
            val outputs = type?.let { VideoNodeTypeRegistry.outputs(it, node.typeVersion) }.orEmpty()
                .filter { it.kind != VideoPortKind.VIDEO_ASSET }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(VideoNodePresentations.labelOf(node.typeId), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        VideoNodePresentations.summaryOf(node),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                A11yLabeledControl(
                    contentDescription = "${VideoNodePresentations.labelOf(node.typeId)} 参数",
                    onClick = { viewModel.selectNode(node.nodeId) },
                ) {
                    McaButton(
                        onClick = { viewModel.selectNode(node.nodeId) },
                        style = McaButtonStyle.Text,
                    ) { Text("参数") }
                }
            }
            if (connectSource?.first == node.nodeId) {
                Text(
                    "选择下方任一节点的输入端口完成连线",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            outputs.forEach { port ->
                val active = connectSource == (node.nodeId to port.name)
                A11yLabeledControl(
                    contentDescription =
                        "${VideoNodePresentations.labelOf(node.typeId)} 从 ${port.name} 连出",
                    stateDescription = if (active) "已选择此输出端口" else null,
                    onClick = { connectSource = if (active) null else (node.nodeId to port.name) },
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    McaButton(
                        onClick = { connectSource = if (active) null else (node.nodeId to port.name) },
                        style = if (active) McaButtonStyle.Tonal else McaButtonStyle.Outlined,
                    ) { Text("从 ${port.name} 连出") }
                }
            }
        }
        if (connectSource != null) {
            Spacer(Modifier.height(6.dp))
            Text("目标节点输入端口：", style = MaterialTheme.typography.labelMedium)
            document.nodes.forEach { target ->
                val type = VideoNodeTypeId.fromTypeId(target.typeId)
                val inputs = type?.let { VideoNodeTypeRegistry.inputs(it, target.typeVersion) }.orEmpty()
                    .filter { it.feed == VideoPortFeed.CONNECTED_ONLY }
                inputs.forEach { port ->
                    val connectToTarget: () -> Unit = {
                        val source = connectSource
                        if (source != null) {
                            viewModel.connect(source.first, source.second, target.nodeId, port.name)
                            connectSource = null
                        }
                    }
                    A11yLabeledControl(
                        contentDescription =
                            "连接 ${VideoNodePresentations.labelOf(target.typeId)} 的 ${port.name} 输入端口",
                        onClick = connectToTarget,
                        modifier = Modifier.padding(start = 12.dp),
                    ) {
                        McaButton(
                            onClick = connectToTarget,
                            style = McaButtonStyle.Outlined,
                        ) { Text("${target.nodeId} ← ${port.name}") }
                    }
                }
            }
        }
    }
}

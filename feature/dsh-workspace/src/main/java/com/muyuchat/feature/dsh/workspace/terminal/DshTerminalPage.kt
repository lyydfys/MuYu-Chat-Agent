package com.muyuchat.feature.dsh.workspace.terminal

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.dsh.contract.console.DshTerminalOpenResult
import com.muyuchat.core.dsh.contract.console.DshTerminalRead
import com.muyuchat.core.dsh.contract.console.DshTerminalUiPort
import kotlinx.coroutines.delay

/**
 * Native terminal page.
 *
 * One owner port ([DshTerminalUiPort]) renders a multi-tab shell over the PTY the runtime already
 * provides. The page never spawns a process and never invents a session: a tab is `OPENING` until
 * the owner returns an id, and a refusal keeps the tab with its reason.
 *
 * Scope of this page (documented, not hidden): line-oriented output with carriage return /
 * backspace / ANSI handling ([DshTerminalScreen]); a sticky-modifier extended key row; pinch-to-zoom
 * text; a bounded tab strip. Full-screen TUIs (vim, top) render their printed text rather than an
 * emulated screen — the "简易兜底" this page promises. Input is a single-line field, which is what a
 * phone keyboard can actually deliver.
 */
@Composable
fun DshTerminalPage(
    port: DshTerminalUiPort,
    modifier: Modifier = Modifier
) {
    var tabs by remember { mutableStateOf(DshTerminalTabs()) }
    var screens by remember { mutableStateOf<Map<String, DshTerminalScreen>>(emptyMap()) }
    var offsets by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var modifiers by remember { mutableStateOf(DshTerminalModifiers()) }
    var input by remember { mutableStateOf("") }
    var fontSize by remember { mutableStateOf(DshTerminalZoom.DEFAULT_SP) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun screenFor(tabId: String): DshTerminalScreen =
        screens[tabId] ?: DshTerminalScreen().also { screens = screens + (tabId to it) }

    // Open the first tab automatically: an empty terminal page is a dead end.
    LaunchedEffect(Unit) {
        if (tabs.tabs.isEmpty()) {
            val opened = tabs.open()
            val tab = opened.selected ?: return@LaunchedEffect
            tabs = opened
            val result = port.open(tab.title, columns = 80, rows = 24)
            tabs = when (result) {
                is DshTerminalOpenResult.Opened -> opened.opened(tab.id, result.sessionId)
                is DshTerminalOpenResult.Refused -> opened.failed(tab.id, result.detail)
            }
        }
    }

    // Poll the selected ready session; the PTY pushes nothing to us, the owner drains a buffer.
    val selected = tabs.selected
    LaunchedEffect(selected?.sessionId) {
        val sessionId = selected?.sessionId ?: return@LaunchedEffect
        val tabId = selected.id
        while (true) {
            val offset = offsets[tabId] ?: 0L
            when (val outcome = port.read(sessionId, offset)) {
                is DshTerminalRead.Data -> {
                    if (outcome.text.isNotEmpty()) screenFor(tabId).append(outcome.text)
                    offsets = offsets + (tabId to outcome.nextOffset)
                }
                is DshTerminalRead.Idle -> offsets = offsets + (tabId to outcome.nextOffset)
                is DshTerminalRead.Failed -> {
                    notice = outcome.detail
                    return@LaunchedEffect
                }
            }
            delay(POLL_MILLIS)
        }
    }

    fun send(payload: String, submit: Boolean = false) {
        val sessionId = tabs.selected?.sessionId ?: return
        if (payload.isEmpty() && !submit) return
        val folded = DshTerminalKeys.applyModifiers(payload, modifiers)
        val written = port.write(sessionId, folded, submit)
        if (!written.ok) notice = written.detail ?: "写入失败" 
        modifiers = modifiers.cleared()
    }

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        DshTerminalTabStrip(
            tabs = tabs,
            onSelect = { tabs = tabs.select(it) },
            onOpen = {
                if (!tabs.canOpen) {
                    notice = "最多 ${DshTerminalTabs.DEFAULT_MAX_TABS} 个标签"
                    return@DshTerminalTabStrip
                }
                val opened = tabs.open()
                val tab = opened.selected ?: return@DshTerminalTabStrip
                tabs = opened
                val result = port.open(tab.title, 80, 24)
                tabs = when (result) {
                    is DshTerminalOpenResult.Opened -> opened.opened(tab.id, result.sessionId)
                    is DshTerminalOpenResult.Refused -> opened.failed(tab.id, result.detail)
                }
            },
            onClose = { id ->
                tabs.tabs.firstOrNull { it.id == id }?.sessionId?.let { sessionId ->
                    port.close(sessionId)
                }
                tabs = tabs.close(id)
            }
        )
        notice?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }

        val currentTab = tabs.selected
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        fontSize = DshTerminalZoom.zoom(fontSize, zoom)
                    }
                }
        ) {
            when {
                currentTab == null -> Text(
                    "没有打开的终端。点「新建」。",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium
                )
                currentTab.state == DshTerminalTab.State.FAILED ->
                    Text(
                        "终端启动失败：" + (currentTab.detail ?: "原因未知"),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                else -> DshTerminalOutput(
                    lines = screenFor(currentTab.id).lines(),
                    fontSize = fontSize
                )
            }
        }

        DshTerminalKeyRow(
            modifiers = modifiers,
            onToggleCtrl = { modifiers = modifiers.toggleCtrl() },
            onToggleAlt = { modifiers = modifiers.toggleAlt() },
            onKey = { key -> send(key.payload) }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                label = { Text("输入（回车执行）") }
            )
            McaButton(
                style = McaButtonStyle.Primary,
                enabled = tabs.selected?.isReady == true,
                onClick = {
                    send(input, submit = true)
                    input = ""
                }
            ) { Text("发送") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            McaButton(style = McaButtonStyle.Text, onClick = {
                fontSize = DshTerminalZoom.step(fontSize, -1)
            }) { Text("A-") }
            McaButton(style = McaButtonStyle.Text, onClick = {
                fontSize = DshTerminalZoom.step(fontSize, 1)
            }) { Text("A+") }
            McaButton(style = McaButtonStyle.Text, onClick = {
                tabs.selected?.let { screenFor(it.id).clear() }
            }) { Text("清屏") }
        }
    }
}

@Composable
private fun DshTerminalTabStrip(
    tabs: DshTerminalTabs,
    onSelect: (String) -> Unit,
    onOpen: () -> Unit,
    onClose: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.tabs.forEach { tab ->
            val selected = tab.id == tabs.selectedId
            McaButton(
                style = if (selected) McaButtonStyle.Primary else McaButtonStyle.Outlined,
                onClick = { onSelect(tab.id) }
            ) { Text(if (tab.state == DshTerminalTab.State.OPENING) tab.title + "…" else tab.title) }
            McaButton(style = McaButtonStyle.Text, onClick = { onClose(tab.id) }) { Text("×") }
        }
        McaButton(style = McaButtonStyle.Text, enabled = tabs.canOpen, onClick = onOpen) { Text("新建") }
    }
}

@Composable
private fun DshTerminalOutput(lines: List<String>, fontSize: Float) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.scrollToItem(lines.lastIndex)
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items(lines) { line ->
            Text(
                text = line.ifEmpty { " " },
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Normal,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun DshTerminalKeyRow(
    modifiers: DshTerminalModifiers,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKey: (DshTerminalKey) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        McaButton(
            style = if (modifiers.ctrl) McaButtonStyle.Primary else McaButtonStyle.Outlined,
            onClick = onToggleCtrl
        ) { Text("Ctrl") }
        McaButton(
            style = if (modifiers.alt) McaButtonStyle.Primary else McaButtonStyle.Outlined,
            onClick = onToggleAlt
        ) { Text("Alt") }
        DshTerminalKeys.ROW.take(6).forEach { key ->
            McaButton(style = McaButtonStyle.Outlined, onClick = { onKey(key) }) { Text(key.label) }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DshTerminalKeys.ROW.drop(6).forEach { key ->
            McaButton(style = McaButtonStyle.Outlined, onClick = { onKey(key) }) { Text(key.label) }
        }
    }
}

private const val POLL_MILLIS: Long = 350L

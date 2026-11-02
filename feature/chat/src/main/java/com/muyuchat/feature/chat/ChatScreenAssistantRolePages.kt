package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.engine.GenerationParams

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import com.muyuchat.core.designsystem.rememberMcaNotify
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIconButton
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import com.muyuchat.core.designsystem.McaIconSize

/**
 * The assistant role page: the role screen, its cards and the assistant editor page.
 *
 * Cut verbatim out of ChatScreen.kt; layout, spacing, colours, iconography, wording and
 * interaction are unchanged. `AssistantRoleScreen` widens from private to internal because
 * ChatScreen.kt still calls it -- Kotlin has no package-private scope. The three declarations
 * only it calls stay private here, and MAX_ASSISTANT_SYSTEM_PROMPT_CHARS comes along because
 * both of its readers are in this file.
 */

@Composable
internal fun AssistantRoleScreen(
    assistants: List<AssistantUiItem>,
    assistantMemories: List<AssistantMemoryUiItem>,
    worldBooks: List<WorldBookUiItem>,
    knowledgeBases: List<KnowledgeBaseUiItem>,
    statusMessage: String?,
    selectedAssistantId: String,
    hasActiveConversation: Boolean,
    selectedModelName: String?,
    selectedModelId: String?,
    selectedModelIsCloud: Boolean,
    modelGenerationPreset: GenerationParams,
    modelChoices: List<ChatModelChoice> = emptyList(),
    onSaveAssistant: (AssistantEditorDraft) -> Boolean,
    onUpsertAssistantMemory: (assistantId: String, id: String?, scope: String, content: String) -> Deferred<Boolean>,
    onDeleteAssistantMemory: (assistantId: String, id: String) -> Unit,
    onSelectAssistant: (String) -> Unit,
    onDeleteAssistant: (String) -> Unit,
    onImportAssistantCard: (String) -> Unit,
    onImportAssistantCardFile: () -> Unit,
    onImportWorldBookFile: (WorldBookImportScope) -> Unit,
    onDeleteWorldBook: (String) -> Unit,
    onSetWorldBookEnabled: (String, Boolean) -> Unit,
    onCreateKnowledgeBase: (String) -> Unit,
    onImportKnowledgeDocument: (String) -> Unit,
    onSetKnowledgeBaseSelected: (String, Boolean) -> Unit,
    onDeleteKnowledgeBase: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notify = rememberMcaNotify()
    var editing by remember { mutableStateOf<AssistantUiItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var managingContext by remember { mutableStateOf(false) }
    val selected = assistants.firstOrNull { it.id == selectedAssistantId } ?: assistants.firstOrNull()
    val editingAssistant = editing
    fun closeAssistantSubPage() {
        creating = false
        editing = null
        importing = false
        managingContext = false
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .navigationBarsPadding()
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Shared icon button: same 48dp target and 24dp glyph as the previous IconButton,
                // now with the layer-phase press weight; the accessible name stays on the button.
                McaIconButton(
                    contentDescription = "关闭",
                    onClick = onBack,
                    glyphSize = McaIconSize.L
                ) {
                    Icon(McaIcons.Close, contentDescription = null)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("助手与角色", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "当前助手、角色卡、提示词与能力",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                McaButton(style = McaButtonStyle.Text,onClick = { creating = true }) {
                    Text("新建")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            AssistantCurrentCard(
                assistant = selected,
                selectedModelName = selectedModelName,
                onEdit = { selected?.let { editing = it } },
                onExport = {
                    selected?.let { item ->
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA assistant", item.exportJson)))
                            notify("已复制角色卡 JSON")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
            McaButton(style = McaButtonStyle.Primary,
                onClick = { managingContext = true },
                // 2.0x font: keep the 48dp minimum but let the button grow instead of clipping.
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(McaIcons.Folder, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("世界书与知识库")
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "助手列表",
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 14.dp)
            ) {
                if (assistants.isEmpty()) {
                    // Empty state: without this the labelled list slot is an unexplained blank
                    // area on a fresh profile. Text-only, same list slot, no layout change.
                    item {
                        Text(
                            "暂无助手，可从下方导入角色卡或新建",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                items(assistants) { assistant ->
                    AssistantListCard(
                        assistant = assistant,
                        onSelect = { onSelectAssistant(assistant.id) },
                        onEdit = { editing = assistant },
                        onDelete = { onDeleteAssistant(assistant.id) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                McaButton(style = McaButtonStyle.Primary,onClick = { importing = true }, modifier = Modifier.weight(1f)) {
                    Text("导入角色卡")
                }
                McaButton(style = McaButtonStyle.Primary,
                    onClick = {
                        selected?.let { item ->
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA assistant", item.exportJson)))
                                notify("已复制当前助手角色卡")
                            }
                        }
                    },
                    // No selected assistant means there is nothing to export; keep the action
                    // visibly disabled instead of leaving a dead tap that looks available.
                    enabled = selected != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出当前助手")
                }
            }
        }

        SmoothRightToLeftPage(
            visible = creating || editingAssistant != null || importing || managingContext,
            onDismiss = ::closeAssistantSubPage
        ) { pageModifier, closePage ->
            when {
                managingContext -> ContextLibraryPage(
                    worldBooks = worldBooks,
                    knowledgeBases = knowledgeBases,
                    statusMessage = statusMessage,
                    hasActiveConversation = hasActiveConversation,
                    onBack = closePage,
                    onImportWorldBook = onImportWorldBookFile,
                    onDeleteWorldBook = onDeleteWorldBook,
                    onSetWorldBookEnabled = onSetWorldBookEnabled,
                    onCreateKnowledgeBase = onCreateKnowledgeBase,
                    onImportKnowledgeDocument = onImportKnowledgeDocument,
                    onSetKnowledgeBaseSelected = onSetKnowledgeBaseSelected,
                    onDeleteKnowledgeBase = onDeleteKnowledgeBase,
                    modifier = pageModifier
                )
                creating -> AssistantEditorPage(
                    assistant = null,
                    onBack = closePage,
                    selectedModelName = selectedModelName,
                    selectedModelId = selectedModelId,
                    selectedModelIsCloud = selectedModelIsCloud,
                    modelGenerationPreset = modelGenerationPreset,
                    modelChoices = modelChoices,
                    onDelete = onDeleteAssistant,
                    onSave = onSaveAssistant,
                    modifier = pageModifier
                )
                editingAssistant != null -> AssistantEditorPage(
                    assistant = editingAssistant,
                    memories = assistantMemories.filter { it.assistantId == editingAssistant.id },
                    onBack = closePage,
                    selectedModelName = selectedModelName,
                    selectedModelId = selectedModelId,
                    selectedModelIsCloud = selectedModelIsCloud,
                    modelGenerationPreset = modelGenerationPreset,
                    modelChoices = modelChoices,
                    onDelete = { id ->
                        onDeleteAssistant(id)
                        closePage()
                    },
                    onSave = onSaveAssistant,
                    onUpsertMemory = onUpsertAssistantMemory,
                    onDeleteMemory = onDeleteAssistantMemory,
                    modifier = pageModifier
                )
                importing -> AssistantImportPage(
                    onBack = closePage,
                    onImportFile = onImportAssistantCardFile,
                    onImport = onImportAssistantCard,
                    statusMessage = statusMessage,
                    modifier = pageModifier
                )
                else -> Unit
            }
        }
    }
}

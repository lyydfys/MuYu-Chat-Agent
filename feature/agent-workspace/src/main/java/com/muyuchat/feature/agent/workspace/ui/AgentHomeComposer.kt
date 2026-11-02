package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.mcaPressScale
import com.muyuchat.core.designsystem.rememberMcaHaptics
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions
import com.muyuchat.feature.agent.workspace.AgentWorkspaceCommandKind
import com.muyuchat.feature.agent.workspace.AgentWorkspaceUiState
import com.muyuchat.feature.agent.workspace.MODEL_UNLOADED_ACTION
import com.muyuchat.feature.agent.workspace.R
import com.muyuchat.feature.agent.workspace.modelUnloadedHint
import com.muyuchat.core.designsystem.McaIconSize

/** Shared MCA mark; the drawer header and footer keep using it. */
@Composable
internal fun BrandMark(size: Dp = 78.dp) {
    Image(
        painter = painterResource(R.drawable.ic_agent_brand),
        contentDescription = "MCA 品牌标志",
        modifier = Modifier.size(size)
    )
}

/**
 * Chat-shaped home composer, pinned above the IME.
 *
 * The rounded 24dp input shell is one line (56dp) at rest and grows to 120dp; model selection,
 * import, permission and send keep their existing real actions. The shell owns the IME and
 * navigation-bar insets so the field never sits under the keyboard. The previous oversized brand
 * hero, two selector rows and 112dp+ scroll-item card are gone.
 */
@Composable
internal fun AgentHomeComposer(
    state: AgentWorkspaceUiState,
    actions: AgentWorkspaceActions,
    onOpenHistory: () -> Unit
) {
    val models = state.models
    var plusMenuOpen by rememberSaveable { mutableStateOf(false) }
    var egressOpen by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val dark = isSystemInDarkTheme()
    val haptics = rememberMcaHaptics()
    val sendInteraction = remember { MutableInteractionSource() }
    val modelInteraction = remember { MutableInteractionSource() }
    val canSend = models.canCreate &&
        state.draft.isNotBlank() &&
        state.commandInFlight == null
    val busy = state.commandInFlight == AgentWorkspaceCommandKind.CREATE_TASK

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp, bottom = 2.dp)
    ) {
        if (models.loaded && models.options.isEmpty()) {
            AgentHomeModelSourceNotice(onOpenChat = actions.onBack)
            Spacer(Modifier.height(8.dp))
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("agent.composer"),
            shape = RoundedCornerShape(24.dp),
            color = if (dark) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surface
            },
            border = BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            ),
            shadowElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .animateContentSize(animationSpec = McaMotion.sizeChangePx)
                            .heightIn(min = 56.dp, max = 120.dp)
                    ) {
                        if (state.draft.isEmpty()) {
                            Text(
                                text = "描述目标，/ 调用指令，@ 文件",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }
                        BasicTextField(
                            value = state.draft,
                            onValueChange = actions.onDraftChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp, max = 120.dp)
                                .padding(vertical = 16.dp)
                                .focusRequester(focusRequester)
                                .testTag("agent.composer.input"),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                        )
                    }
                    FilledIconButton(
                        onClick = {
                            haptics.confirm()
                            actions.onCreateTask()
                        },
                        enabled = canSend,
                        interactionSource = sendInteraction,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .size(48.dp)
                            .mcaPressScale(sendInteraction, enabled = canSend)
                            .testTag("agent.send")
                            .semantics {
                                contentDescription = if (busy) "正在创建任务" else "开始任务"
                            },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = McaIcons.Send,
                            contentDescription = null
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McaIconButton(
                        contentDescription = "更多输入选项",
                        onClick = { plusMenuOpen = true }
                    ) {
                        Icon(McaIcons.Add, contentDescription = null)
                    }
                    McaIconButton(
                        contentDescription = "导入任务包",
                        onClick = actions.onImportTaskPackage
                    ) {
                        Icon(McaIcons.AttachFile, contentDescription = null)
                    }
                    McaIconButton(
                        contentDescription = "网络与权限说明",
                        onClick = { egressOpen = true }
                    ) {
                        Icon(McaIcons.Shield, contentDescription = null)
                    }
                    McaButton(
                        style = McaButtonStyle.Text,
                        shape = ButtonDefaults.textShape,
                        onClick = {
                            actions.onOpenModelPicker()
                        },
                        interactionSource = modelInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("agent.model")
                    ) {
                        Text(
                            text = models.selected?.displayName
                                ?: if (models.loaded) "选择模型" else "载入模型…",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Icon(
                            imageVector = McaIcons.Forward,
                            contentDescription = null,
                            modifier = Modifier.size(McaIconSize.S)
                        )
                    }
                }
            }
        }
    }

    if (egressOpen) {
        EgressInfoDialog(
            networkEnabled = models.networkEnabled,
            onToggleNetwork = { actions.onNetworkEnabledChange(!models.networkEnabled) },
            onDismiss = { egressOpen = false }
        )
    }

    if (plusMenuOpen) {
        AgentHomePlusSheet(
            onDismiss = { plusMenuOpen = false },
            onImportFile = {
                plusMenuOpen = false
                actions.onImportTaskPackage()
            },
            onFocusGoal = {
                plusMenuOpen = false
                focusRequester.requestFocus()
            },
            onPermissions = {
                plusMenuOpen = false
                egressOpen = true
            },
            onModel = {
                plusMenuOpen = false
                actions.onOpenModelPicker()
            },
            onTaskHistory = {
                plusMenuOpen = false
                onOpenHistory()
            }
        )
    }
}

/**
 * Actionable companion to the passive "no model installed" state. Agent has no model loader of its
 * own: it reuses the engine profile already loaded by the Chat page, so the only real recovery path
 * is the host's existing back callback. Rendered only once the catalog finished loading empty; a
 * still-loading catalog or a populated catalog shows nothing here.
 */
@Composable
private fun AgentHomeModelSourceNotice(onOpenChat: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent.composer.model-source"),
        shape = RoundedCornerShape(12.dp),
        color = if (dark) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Agent 复用聊天页当前已加载的本地模型。请先在聊天页加载模型。",
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = {
                    onOpenChat()
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("agent.composer.model-source.action")
            ) {
                Text(
                    text = "去聊天页加载",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/**
 * Honest state for a selected model that is installed but not loaded. The page cannot load it; the
 * only real recovery path is the host's Chat-page transition, so the action is a real navigation,
 * not a toast or a fake "loading" state.
 */
@Composable
internal fun AgentModelUnloadedNotice(
    displayName: String,
    onOpenChatToLoad: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent.composer.model-unloaded"),
        shape = RoundedCornerShape(12.dp),
        color = if (dark) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = modelUnloadedHint(displayName),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = {
                    onOpenChatToLoad()
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("agent.composer.model-unloaded.action")
            ) {
                Text(
                    text = MODEL_UNLOADED_ACTION,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/**
 * Real permissions/egress explanation. Toggling here changes the same network preference the old
 * selector row used; the dialog never pretends a token or credential exists.
 */
@Composable
private fun EgressInfoDialog(
    networkEnabled: Boolean,
    onToggleNetwork: () -> Unit,
    onDismiss: () -> Unit
) {
    McaDialog(
        onDismissRequest = onDismiss,
        title = { Text("网络与权限") },
        text = {
            Text(
                text = "默认仅本地运行。联网模式只为当前任务申请出站授权；" +
                    "MCP 服务器的网络许可需要在管理页单独开启，且同样按任务生效。" +
                    "这里不会保存 token、Authorization 或任何长期凭据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        dismissButton = {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = {
                    onDismiss()
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("关闭")
            }
        },
        confirmButton = {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = {
                    onToggleNetwork()
                    onDismiss()
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (networkEnabled) "切换为离线模式" else "切换为联网模式")
            }
        }
    )
}

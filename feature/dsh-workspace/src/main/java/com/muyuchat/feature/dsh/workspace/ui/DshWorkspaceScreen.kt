package com.muyuchat.feature.dsh.workspace.ui

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaHairline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.mcaEnterReveal
import com.muyuchat.feature.dsh.workspace.DshFailureProjection
import com.muyuchat.feature.dsh.workspace.DshPageError
import com.muyuchat.feature.dsh.workspace.DshPageUiState
import com.muyuchat.feature.dsh.workspace.DshWebErrorClassifier
import com.muyuchat.feature.dsh.workspace.DshWorkspaceUiState
import kotlin.math.roundToInt

/** Callbacks the stateless shell needs; the Route owns their ViewModel wiring. */
data class DshWorkspaceActions(
    val onRetry: () -> Unit,
    val onReloadPage: () -> Unit,
    val onDismissPageError: () -> Unit,
    val onExit: () -> Unit
)

/**
 * Outer MCA shell for the DSH workspace.
 *
 * Only host-level states are rendered here: starting, start/exited failure and the WebView slot.
 * The official DSH settings, history, approvals, model picker and plugin pages are never
 * re-implemented in Compose; they are rendered unchanged by the WebView in [webContent].
 *
 * Large-screen policy (foldables, tablets, desktop-mode windows):
 * - COMPACT (< 600dp) is byte-for-byte the previous phone layout: one full-bleed WebView slot,
 *   the same shells, the same banners. No new container is composed on a phone.
 * - MEDIUM/EXPANDED add a left rail (status + 返回/控制台/重新加载) and inset the WebView slot by
 *   the rail width. The WebView is *not* moved into a conditional branch: an unfolded foldable
 *   resizes the same composed page instead of disposing and recreating the renderer (that would
 *   reload the official page and lose its scroll/session view).
 * - Centred, width-capped host text on wide windows so a 900dp-tall panel does not stretch a
 *   three-line failure message across two metres.
 */
@Composable
fun DshWorkspaceScreen(
    state: DshWorkspaceUiState,
    page: DshPageUiState,
    snackbarHostState: SnackbarHostState,
    actions: DshWorkspaceActions,
    modifier: Modifier = Modifier,
    /**
     * Maintenance console entry for the startup-recovery block. Without it an interrupted trial
     * leaves the workspace on a screen whose only action is "返回", and the pending upgrade can
     * never be resumed or abandoned from inside the app.
     */
    onOpenMaintenanceConsole: (() -> Unit)? = null,
    webContent: @Composable (DshWorkspaceUiState.Web) -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = {
                fadeIn(animationSpec = McaMotion.fade(millis = 160)) togetherWith
                    fadeOut(animationSpec = McaMotion.fade(millis = 110))
            },
            label = "dshWorkspaceStateTransition",
            modifier = Modifier.fillMaxSize(),
            // Shells fill the surface on COMPACT, so this only positions the width-capped
            // wide-window shells (see DshStartingShell / DshFailureShell).
            contentAlignment = Alignment.Center
        ) { targetState ->
            when (targetState) {
                DshWorkspaceUiState.Starting -> DshStartingShell(
                    onExit = actions.onExit,
                    maxContentWidthDp = dshShellMaxWidthFor(dshWindowWidth())
                )
                is DshWorkspaceUiState.Failure -> DshFailureShell(
                    projection = targetState.projection,
                    onRetry = actions.onRetry,
                    onExit = actions.onExit,
                    maxContentWidthDp = dshShellMaxWidthFor(dshWindowWidth()),
                    onOpenConsole = onOpenMaintenanceConsole?.takeIf {
                        targetState.projection.code == DshFailureCode.STARTUP_RECOVERY_REQUIRED
                    }
                )
                is DshWorkspaceUiState.Web -> DshWebSlot(
                    web = targetState,
                    page = page,
                    actions = actions,
                    onOpenConsole = onOpenMaintenanceConsole,
                    webContent = webContent
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}

/**
 * The WebView slot plus the optional wide-window rail.
 *
 * The rail is a sibling overlay and the WebView pane is inset by its width, so the page keeps a
 * single stable composition across a compact ↔ expanded transition.
 */
@Composable
private fun DshWebSlot(
    web: DshWorkspaceUiState.Web,
    page: DshPageUiState,
    actions: DshWorkspaceActions,
    onOpenConsole: (() -> Unit)?,
    webContent: @Composable (DshWorkspaceUiState.Web) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Same convention as the chat composer: adjustResize alone is not reliable on
            // every OEM/edge-to-edge combination, so keep the page above the IME too.
            .imePadding()
    ) {
        val availableWidthDp = maxWidth.value.roundToInt()
        val windowWidth = dshWindowWidthFor(availableWidthDp)
        val railWidthDp = dshWorkspaceRailWidthFor(
            availableWidthDp = availableWidthDp,
            twoPane = dshSupportsRail(windowWidth)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                // COMPACT composes no padding modifier at all: the phone slot is unchanged.
                .then(if (railWidthDp > 0) Modifier.padding(start = railWidthDp.dp) else Modifier)
        ) {
            webContent(web)
            if (page.loading) {
                LinearProgressIndicator(
                    progress = { page.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }
            page.error?.let { error ->
                DshPageErrorBanner(
                    error = error,
                    onReload = actions.onReloadPage,
                    onDismiss = actions.onDismissPageError,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
        if (railWidthDp > 0) {
            DshWorkspaceRail(
                web = web,
                page = page,
                onReload = actions.onReloadPage,
                onExit = actions.onExit,
                onOpenConsole = onOpenConsole,
                railWidthDp = railWidthDp,
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }
    }
}

/**
 * Left rail of the wide two-pane layout: page status, the loopback origin (already a non-secret
 * `http://127.0.0.1:<port>` value), and the same host actions the floating ball carries.
 *
 * It owns no state and starts nothing; leaving the rail is the same `actions.onExit` the ball and
 * the failure shell use.
 */
@Composable
private fun DshWorkspaceRail(
    web: DshWorkspaceUiState.Web,
    page: DshPageUiState,
    onReload: () -> Unit,
    onExit: () -> Unit,
    onOpenConsole: (() -> Unit)?,
    railWidthDp: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .widthIn(max = railWidthDp.dp)
            .fillMaxWidth()
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "DSH 工作区",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = dshRailStatusText(page),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "本机页面",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = web.origin.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "会话、设置与插件仍由官方页面负责。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DshRailAction(label = "重新加载页面", icon = McaIcons.Refresh, onClick = onReload)
                if (onOpenConsole != null) {
                    DshRailAction(label = "DSH 控制台", icon = McaIcons.Settings, onClick = onOpenConsole)
                }
                DshRailAction(label = "返回 MCA", icon = McaIcons.Back, onClick = onExit)
            }
            Box(
                modifier = Modifier
                    .width(McaHairline.Width)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            )
        }
    }
}

@Composable
private fun DshRailAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    McaButton(
        style = McaButtonStyle.Outlined,
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** One line of page status for the rail; wording mirrors the banner/shell vocabulary. */
private fun dshRailStatusText(page: DshPageUiState): String {
    val error = page.error
    return when {
        error is DshPageError.Http -> "页面返回 HTTP ${error.statusCode}"
        error is DshPageError.Network -> DshWebErrorClassifier.messageFor(error.kind)
        page.loading -> "正在加载 ${page.progress.coerceIn(0, 100)}%"
        else -> "页面已加载"
    }
}

@Composable
private fun DshStartingShell(
    onExit: () -> Unit,
    maxContentWidthDp: Int?
) {
    val exitInteraction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            // COMPACT passes null: no width constraint, so the phone shell is exactly as before.
            // The cap must come BEFORE fillMaxSize: an outer fixed size cannot be reduced inward.
            .then(if (maxContentWidthDp != null) Modifier.widthIn(max = maxContentWidthDp.dp) else Modifier)
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp)
            .mcaEnterReveal(index = 0, travelDp = 10),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
        Text(
            text = "正在启动 DSH",
            modifier = Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "首次启动会准备官方 DSH 运行时，可能需要几秒。",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        McaButton(
            style = McaButtonStyle.Text,
            onClick = onExit,
            interactionSource = exitInteraction,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("返回")
        }
    }
}

@Composable
private fun DshFailureShell(
    projection: DshFailureProjection,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    maxContentWidthDp: Int?,
    onOpenConsole: (() -> Unit)? = null
) {
    val retryInteraction = remember { MutableInteractionSource() }
    val exitInteraction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            // COMPACT passes null: the phone failure shell keeps its current full-width text.
            .then(if (maxContentWidthDp != null) Modifier.widthIn(max = maxContentWidthDp.dp) else Modifier)
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp)
            .mcaEnterReveal(index = 0, travelDp = 10),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = McaIcons.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Text(
            text = projection.title,
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Text(
            text = projection.message,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = "错误代码：${projection.code.name}",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (projection.retryable) {
                McaButton(
                    style = McaButtonStyle.Primary,
                    onClick = onRetry,
                    interactionSource = retryInteraction,
                ) {
                    Text(projection.primaryActionLabel)
                }
            }
            if (onOpenConsole != null) {
                McaButton(
                    style = McaButtonStyle.Text,
                    onClick = onOpenConsole,
                ) {
                    Text("维护控制台")
                }
            }
            McaButton(
                style = McaButtonStyle.Text,
                onClick = onExit,
                interactionSource = exitInteraction,
            ) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun DshPageErrorBanner(
    error: DshPageError,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissInteraction = remember { MutableInteractionSource() }
    val reloadInteraction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = error.messageFor(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                McaButton(
                    style = McaButtonStyle.Text,
                    onClick = onDismiss,
                    interactionSource = dismissInteraction,
                ) {
                    Text("关闭")
                }
                McaButton(
                    style = McaButtonStyle.Primary,
                    onClick = onReload,
                    interactionSource = reloadInteraction,
                ) {
                    Text("重新加载")
                }
            }
        }
    }
}

private fun DshPageError.messageFor(): String = when (this) {
    is DshPageError.Network -> DshWebErrorClassifier.messageFor(kind)
    is DshPageError.Http -> "官方页面返回 HTTP $statusCode。"
}

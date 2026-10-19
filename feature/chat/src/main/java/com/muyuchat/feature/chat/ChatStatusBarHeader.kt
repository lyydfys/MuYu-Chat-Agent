package com.muyuchat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.mcaPressScale

@Composable
internal fun ChatStatusBar(
    state: ChatUiState,
    onOpenHistory: () -> Unit,
    onNewConversation: () -> Unit,
    onLoadModel: (String) -> Unit,
    onModelBackendChange: (String, String) -> Unit,
    generationParams: GenerationParams,
    onGenerationParamsChange: (GenerationParams) -> Unit,
    onOpenModels: () -> Unit,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onCloudReasoningModeLocked: () -> Unit,
    onOpenAppMenu: () -> Unit
) {
    val apiActive = state.selectedModelIsCloud || state.apiEnabled || state.restEnabled
    var modelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Press weight for the model capsule; drawn in graphicsLayer so the 48dp/56dp header geometry,
    // the API pill and the model text metrics are untouched.
    val modelCapsuleInteraction = remember { MutableInteractionSource() }
    val compactForLargeText = LocalDensity.current.fontScale >= 1.5f
    // Large-font accessibility adaptation, not a default-style change: the API state is information
    // and must never be dropped just because the header is tight. Only the pill padding tightens at
    // large font scale; at the default scale these values are exactly the previous 8/4 dp.
    val apiBadgeHorizontalPadding = if (compactForLargeText) 5.dp else 8.dp
    val apiBadgeVerticalPadding = if (compactForLargeText) 2.dp else 4.dp
    val cacheEvidence = cacheEvidenceLabel(state.stats).takeIf { state.hasReadyLocalModelForHeader() }
    val modelSubtitle = state.modelHeaderSubtitle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = if (compactForLargeText) 12.dp else 18.dp, end = if (compactForLargeText) 12.dp else 18.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 5.dp
        ) {
            McaIconButton(
                contentDescription = "打开历史",
                onClick = onOpenHistory,
                glyphSize = 24.dp,
                // Preserve the previous IconButton content colour (the Surface's onSurface),
                // instead of the shared component's lower-emphasis default tint.
                tint = LocalContentColor.current,
            ) {
                Icon(
                    Icons.Rounded.Menu,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        // The visible model name/subtitle already name this capsule; an extra
                        // contentDescription duplicated the text and hid the current model.
                        role = SemanticsRole.Button
                    }
                    .clickable(
                        interactionSource = modelCapsuleInteraction,
                        indication = ripple(bounded = true)
                    ) {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        modelMenuExpanded = true
                    }
                    .mcaPressScale(modelCapsuleInteraction),
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
                shadowElevation = 5.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // At 2.0x font scale the model name needs every dp of the capsule; the mark
                    // is decorative, the name is the information.
                    if (!compactForLargeText) {
                        McaLogoMark(size = 24.dp, cornerRadius = 8.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayModelName(state.selectedModelName) ?: "选择模型",
                            // Only the large-font layout gets a second line so the name stays
                            // readable instead of clipping; the default layout keeps one line.
                            // One ellipsised line: at 2.0x font scale the narrow capsule turned the name
                            // into a vertical character stack, which reads as broken.
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 15.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = modelSubtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        cacheEvidence?.let { evidence ->
                            Text(
                                text = evidence,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                            )
                        }
                    }
                    // The API pill is state, not chrome, so it stays at every font scale. At large
                    // font scale it only tightens its padding; the dropdown chevron below is a
                    // decorative affordance (the whole capsule already exposes "选择模型" as a
                    // button), so it is the only element that yields space there.
                    Surface(
                        color = if (apiActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ) {
                        Text(
                            text = "API",
                            modifier = Modifier.padding(
                                horizontal = apiBadgeHorizontalPadding,
                                vertical = apiBadgeVerticalPadding
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (apiActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (!compactForLargeText) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(15.dp)
                        )
                    }
                }
            }
            ModelSwitcherDropdown(
                expanded = modelMenuExpanded,
                models = state.localModels,
                selectedModelIsCloud = state.selectedModelIsCloud,
                isGenerating = state.isGenerating,
                reasoningMode = state.reasoningMode,
                onDismiss = { modelMenuExpanded = false },
                onLoadModel = { id ->
                    modelMenuExpanded = false
                    onLoadModel(id)
                },
                onModelBackendChange = onModelBackendChange,
                generationParams = generationParams,
                onGenerationParamsChange = onGenerationParamsChange,
                onReasoningModeChange = onReasoningModeChange,
                onCloudReasoningModeLocked = onCloudReasoningModeLocked,
                onOpenModels = {
                    modelMenuExpanded = false
                    onOpenModels()
                }
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 5.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                McaIconButton(
                    contentDescription = "新建聊天",
                    onClick = onNewConversation,
                    glyphSize = 22.dp,
                    // Previous IconButton content colour; keeps the header icons unchanged.
                    tint = LocalContentColor.current,
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                }
                McaIconButton(
                    contentDescription = "打开应用菜单",
                    onClick = onOpenAppMenu,
                    glyphSize = 28.dp,
                ) {
                    McaLogoMark(size = 28.dp, cornerRadius = 10.dp)
                }
            }
        }
    }
}

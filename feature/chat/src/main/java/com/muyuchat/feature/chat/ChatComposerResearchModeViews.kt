package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaIcons

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll








import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.visionDeduplicationKey
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.designsystem.McaActionLabels
import com.muyuchat.core.designsystem.mcaPressScale
import com.muyuchat.core.designsystem.rememberMcaHaptics
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private enum class ResearchModeChoice(val key: String, val label: String) {
    OFF("OFF", "普通"),
    AUTO("AUTO", "自动"),
    DEEP("DEEP", "深度");

    companion object {
        fun selectedKey(value: String): String =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) }?.key ?: AUTO.key
    }
}

@Composable
internal fun ResearchModeFloatingCapsule(
    menu: ComposerMenuLifecycle,
    selected: String,
    overridden: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberMcaHaptics()
    val scrollState = rememberScrollState()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomStart
    ) {
        val capsuleWidth = ComposerMenuMetrics.CAPSULE_WIDTH_DP.dp
        val startPadding = minOf(252.dp, maxWidth - capsuleWidth - 12.dp).coerceAtLeast(12.dp)
        // R05：same real-height budget as the sheet: a 2x font or an open keyboard may not push the
        // last mode pill outside the panel; the pills scroll instead.
        var contentHeightPx by remember { mutableStateOf(0) }
        val contentHeightDp = with(LocalDensity.current) { contentHeightPx.toDp() }
        val hostAvailableHeightDp = if (constraints.hasBoundedHeight) {
            maxHeight.value.roundToInt()
        } else {
            Int.MAX_VALUE
        }
        val heightBudget = composerMenuHeightBudget(
            hostAvailableHeightDp = hostAvailableHeightDp,
            contentHeightDp = if (contentHeightPx == 0) {
                Int.MAX_VALUE
            } else {
                contentHeightDp.value.roundToInt()
            }
        )
        // R04：the capsule stays composed for its own exit frame instead of being removed by the
        // host condition on the same frame the dismiss happens.
        AnimatedVisibility(
            visible = menu.research.wantsVisible,
            enter = fadeIn(tween(120)) + slideInHorizontally(
                animationSpec = spring(
                    dampingRatio = ChatMotion.SURFACE_DAMPING_RATIO,
                    stiffness = ChatMotion.SURFACE_STIFFNESS,
                    visibilityThreshold = IntOffset.VisibilityThreshold
                ),
                initialOffsetX = { -it / 4 }
            ),
            exit = fadeOut(tween(100)),
            modifier = Modifier
                // Same placement as before (86dp above the composer baseline); keeping it on the
                // animated node leaves `maxHeight` above as the honest available height for the
                // budget below.
                .padding(bottom = ComposerMenuMetrics.SHEET_BOTTOM_OFFSET_DP.dp)
                .padding(start = startPadding)
                .width(capsuleWidth)
        ) {
            DisposableEffect(menu) {
                menu.research.onContentComposed()
                onDispose { menu.research.onContentDisposed() }
            }
            val darkTheme = isSystemInDarkTheme()
            val acceptsActions = menu.research.acceptsActions
            val closeInteraction = remember { MutableInteractionSource() }
            // R08：the close target follows the measured header, so a glyph that grows with the font
            // scale (1.5x/2x) stays inside its own target instead of drifting below a fixed square.
            var headerHeightPx by remember { mutableStateOf(0) }
            val closeTargetHeightDp = researchCloseTargetHeightDp(
                with(LocalDensity.current) { headerHeightPx.toDp() }.value.roundToInt()
            )
            Surface(
                modifier = Modifier.clickable(enabled = false) {},
                color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 10.dp,
                border = BorderStroke(
                    0.5.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(
                        alpha = if (darkTheme) 0.55f else 0.72f
                    )
                )
            ) {
                Box {
                    Column(
                        modifier = Modifier
                            .then(
                                if (heightBudget.maxHeightDp >= Int.MAX_VALUE) {
                                    Modifier
                                } else {
                                    Modifier.heightIn(max = heightBudget.maxHeightDp.dp)
                                }
                            )
                            .verticalScroll(scrollState, enabled = heightBudget.scrollable)
                    ) {
                        Column(
                            modifier = Modifier
                                .onSizeChanged { contentHeightPx = it.height }
                                .padding(
                                    horizontal = ComposerMenuMetrics.CAPSULE_HORIZONTAL_PADDING_DP.dp,
                                    vertical = ComposerMenuMetrics.CAPSULE_PADDING_DP.dp
                                ),
                            verticalArrangement = Arrangement.spacedBy(
                                ComposerMenuMetrics.CAPSULE_ITEM_SPACING_DP.dp
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .onSizeChanged { headerHeightPx = it.height }
                                    .padding(
                                        horizontal = 10.dp,
                                        vertical = ComposerMenuMetrics.CAPSULE_HEADER_VERTICAL_PADDING_DP.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (overridden) "本轮研究" else "研究模式",
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                                    color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                // R08：the visible glyph keeps its 16dp slot and tint; the enlarged
                                // target is the transparent overlay below, so the panel geometry does
                                // not move. The glyph only carries the shared press scale.
                                Icon(
                                    imageVector = McaIcons.Close,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(ComposerMenuMetrics.CAPSULE_CLOSE_GLYPH_DP.dp)
                                        .mcaPressScale(closeInteraction, enabled = acceptsActions)
                                )
                            }
                            val selectedKey = ResearchModeChoice.selectedKey(selected)
                            ResearchModeChoice.entries.forEach { mode ->
                                ReasoningModePill(
                                    label = mode.label,
                                    selected = selectedKey == mode.key,
                                    // Closing refuses the selection once; the pill keeps its own
                                    // ripple primitive from the shared reasoning capsules.
                                    onClick = {
                                        haptics.tick()
                                        menu.runResearchAction { onSelect(mode.key) }
                                    }
                                )
                            }
                        }
                    }
                    // 16dp visible glyph -> a real target at the panel's top-right corner: at least
                    // 36dp tall and always covering the whole header, while staying clear of the
                    // first pill (the header is followed by 7dp of item spacing). A repeated close
                    // is consumed idempotently by the lifecycle owner.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .width(ComposerMenuMetrics.CAPSULE_CLOSE_TARGET_DP.dp)
                            .height(closeTargetHeightDp.dp)
                            .clickable(
                                interactionSource = closeInteraction,
                                indication = null,
                                enabled = acceptsActions,
                                onClickLabel = "${McaActionLabels.CLOSE}研究模式选择",
                                role = Role.Button,
                                onClick = {
                                    haptics.tick()
                                    menu.dismissResearch()
                                }
                            )
                            .semantics { contentDescription = "${McaActionLabels.CLOSE}研究模式选择" }
                    )
                }
            }
        }
    }
}

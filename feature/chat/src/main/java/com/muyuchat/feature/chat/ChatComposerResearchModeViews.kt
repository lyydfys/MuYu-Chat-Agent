package com.muyuchat.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
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
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
    selected: String,
    overridden: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 86.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomStart
    ) {
        val capsuleWidth = 128.dp
        val startPadding = minOf(252.dp, maxWidth - capsuleWidth - 12.dp).coerceAtLeast(12.dp)
        // This capsule is only composed while it is open, so `visible = true` on the first frame is
        // a dead animation. Entering from an explicit hidden state lets the spring actually play.
        val capsuleVisibility = remember { MutableTransitionState(initialState = false) }
        capsuleVisibility.targetState = true
        AnimatedVisibility(
            visibleState = capsuleVisibility,
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
                .padding(start = startPadding)
                .width(capsuleWidth)
        ) {
            val darkTheme = isSystemInDarkTheme()
            Surface(
                modifier = Modifier.clickable(enabled = false) {},
                color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (overridden) "本轮研究" else "研究模式",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                            color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭研究模式选择",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(onClick = onDismiss)
                        )
                    }
                    val selectedKey = ResearchModeChoice.selectedKey(selected)
                    ResearchModeChoice.entries.forEach { mode ->
                        ReasoningModePill(
                            label = mode.label,
                            selected = selectedKey == mode.key,
                            onClick = { onSelect(mode.key) }
                        )
                    }
                }
            }
        }
    }
}


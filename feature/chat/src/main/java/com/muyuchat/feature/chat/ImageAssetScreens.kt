package com.muyuchat.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.muyuchat.core.designsystem.mcaPressScale

// P9/S23：从 ImageWorkspaceScreens.kt 等值移出的图库资产簇（瓦片 + 预览浮层）。
// 原样逐字迁移，未做行为改动；2 个跨区入口按协议提升 internal。
@Composable
// P9/S23: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageAssetTile(
    image: ImageAssetUiItem,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    var retryKey by remember(image.id) { mutableStateOf(0) }
    val loadState = rememberChatImageLoadState(
        context = context,
        uriString = image.uriString,
        maxDimensionPx = 384,
        revisionKey = retryKey
    )
    val tileInteraction = remember { MutableInteractionSource() }
    val favoriteInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .testTag("image.asset.${image.id}")
            .clip(RoundedCornerShape(2.dp))
            .background(if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFEDEFF1))
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                } else {
                    Modifier
                }
            )
            .mcaPressScale(tileInteraction)
            .combinedClickable(
                interactionSource = tileInteraction,
                onClick = onOpen,
                onLongClick = onLongPress
            )
    ) {
        when (loadState) {
            is ChatImageLoadState.Ready -> Image(
                bitmap = loadState.bitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            ChatImageLoadState.Loading -> CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            ChatImageLoadState.Failed -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF9AA0A6),
                    modifier = Modifier.size(26.dp)
                )
                TextButton(onClick = { retryKey += 1 }) { Text("重试") }
            }
        }
        if (image.favorite && !selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(48.dp)
                    .mcaPressScale(favoriteInteraction)
            ) {
                Surface(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(28.dp),
                    color = if (darkTheme) {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                    } else {
                        Color.White.copy(alpha = 0.9f)
                    },
                    shape = CircleShape
                ) {}
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.fillMaxSize(),
                    interactionSource = favoriteInteraction
                ) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = "取消收藏",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (selectionMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(28.dp),
                color = if (darkTheme) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                } else {
                    Color.White.copy(alpha = 0.9f)
                },
                shape = CircleShape
            ) {
                Icon(
                    if (selected) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = if (selected) "已选择" else "选择图片",
                    modifier = Modifier.padding(6.dp).size(16.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        val badgeText = imageAssetBadgeText(image)
        if (badgeText.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(7.dp),
                color = Color.Black.copy(alpha = 0.62f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    badgeText,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

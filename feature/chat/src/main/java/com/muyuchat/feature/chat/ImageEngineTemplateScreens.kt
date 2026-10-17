package com.muyuchat.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S21：从 ImageWorkspaceScreens.kt 等值移出的引擎/模板簇。
// 原样逐字迁移，未做行为改动；4 个跨区入口按协议提升 internal。
private enum class ImageEngineSource(val title: String, val subtitle: String) {
    LOCAL("本地生图", "设备端图像引擎"),
    CLOUD("云端生图", "API 图像引擎")
}

@Composable
// P9/S21: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageEngineSwitcher(
    models: List<ChatModelChoice>,
    selectedModelId: String?,
    selectedModelName: String?,
    selectedModelIsCloud: Boolean,
    enabled: Boolean = true,
    loading: Boolean = false,
    onSelectModel: (String) -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val chipColor = if (darkTheme) MaterialTheme.colorScheme.surface else Color(0xFFF4F6F9)
    val modelChipColor = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White
    val chipBorder = if (darkTheme) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f) else Color(0xFFE0E3E7)
    val chipTextColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124)
    val chipMutedColor = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF5F6368)
    var sourceMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var modelMenuSource by rememberSaveable { mutableStateOf<ImageEngineSource?>(null) }
    val selectedSource = if (selectedModelIsCloud) ImageEngineSource.CLOUD else ImageEngineSource.LOCAL
    val localModels = models.filterNot { it.cloud }
    val cloudModels = models.filter { it.cloud }
    LaunchedEffect(enabled) {
        if (!enabled) {
            sourceMenuExpanded = false
            modelMenuSource = null
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Surface(
                onClick = { sourceMenuExpanded = true },
                enabled = enabled,
                color = chipColor,
                shape = CircleShape,
                border = BorderStroke(1.dp, chipBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp), tint = chipTextColor)
                    Text(selectedSource.title, fontWeight = FontWeight.Bold, color = chipTextColor)
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = chipMutedColor)
                }
            }
            DropdownMenu(
                expanded = enabled && sourceMenuExpanded,
                onDismissRequest = { sourceMenuExpanded = false }
            ) {
                ImageEngineSource.entries.forEach { source ->
                    DropdownMenuItem(
                        text = {
                            Text(source.title, fontWeight = FontWeight.Bold)
                        },
                        onClick = {
                            sourceMenuExpanded = false
                            modelMenuSource = source
                        }
                    )
                }
            }
        }

        val source = modelMenuSource ?: selectedSource
        val sourceModels = if (source == ImageEngineSource.CLOUD) cloudModels else localModels
        Box(modifier = Modifier.weight(1f)) {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("image.model.selector"),
                    onClick = { modelMenuSource = source },
                    enabled = enabled,
                    color = modelChipColor,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, chipBorder),
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            if (source == selectedSource) selectedModelName ?: "选择模型" else source.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = chipTextColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = chipMutedColor)
                    }
                }
                DropdownMenu(
                    expanded = enabled && modelMenuSource == source,
                    onDismissRequest = { modelMenuSource = null },
                    modifier = Modifier
                        .widthIn(min = 220.dp, max = 320.dp)
                        .heightIn(max = 360.dp)
                ) {
                    if (sourceModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(imageEngineMenuEmptyText(isCloud = source == ImageEngineSource.CLOUD, loading = loading)) },
                            onClick = { modelMenuSource = null }
                        )
                    } else {
                        sourceModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(model.quant, model.subtitle.takeIf { it.isNotBlank() }).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectModel(model.id)
                                    modelMenuSource = null
                                }
                            )
                        }
                    }
                }
        }
    }
}

/** B45/S2: three-state empty text; only the local source can be "still loading". */
internal fun imageEngineMenuEmptyText(isCloud: Boolean, loading: Boolean): String = when {
    isCloud -> "暂无云端生图模型"
    loading -> "正在加载本地生图模型…"
    else -> "暂无本地生图模型"
}

@Composable
// P9/S21: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageGenerationTerminalCard(job: ImageGenerationUiJob, onRetry: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val cancelled = job.statusLabel == "已取消"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF4F6F9),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (cancelled) "已取消" else job.statusLabel,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    job.prompt,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124)
                )
                if (job.modelName.isNotBlank()) {
                    Text(
                        job.modelName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
                    )
                }
                Text(
                    job.message.ifBlank { if (cancelled) "图片生成已取消" else job.statusLabel },
                    maxLines = 2,
                    color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF5F6368),
                    fontSize = 13.sp
                )
            }
            TextButton(onClick = onRetry) {
                Text(if (cancelled) "按原参数再生成" else "重试")
            }
        }
    }
}

// P9/S21: private→internal（作为提升入口的签名类型/元素类型需要同等可见性）
internal data class ImagePromptTemplate(
    val title: String,
    val prompt: String,
    val negativePrompt: String,
    val imageRes: Int,
    val accent: Color
)

// P9/S21: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal val imagePromptTemplates = listOf(
    ImagePromptTemplate(
        "液态金属花园",
        "luminous blue-white liquid metal garden, flowing chrome blossoms, polished silver petals, soft studio lighting, delicate cinematic 3D render, shallow depth of field, pristine reflective surfaces, elegant futuristic atmosphere",
        "blurry, low quality, distorted geometry, warped metal, misshapen flowers, oversaturated colors, harsh shadows, watermark, text, logo, jpeg artifacts",
        R.drawable.template_liquid_garden,
        Color(0xFF7EA9F5)
    ),
    ImagePromptTemplate(
        "晨光工作岛",
        "morning light island desk, blue-white minimalist workspace, clean futuristic office setup, soft sunrise glow through large windows, tidy surfaces, gentle reflections, professional 3D product render, inviting calm atmosphere",
        "clutter, messy cables, low quality, blurry, dim lighting, warped perspective, reflected glare, watermark, text, logo, oversaturated colors",
        R.drawable.template_work_island,
        Color(0xFF74A2E8)
    ),
    ImagePromptTemplate(
        "山海便签",
        "mountain and sea sticky-note collage, misty blue ink-wash painting style, embossed paper texture, layered memo cards, tranquil distant landscape, soft fog, delicate brushwork, harmonious balanced composition",
        "sharp harsh edges, overly bright, low contrast, smudged notes, illegible handwriting, cluttered layout, blurry, watermark, text, logo, jpeg artifacts",
        R.drawable.template_mountain_memo,
        Color(0xFF8BAFA9)
    ),
    ImagePromptTemplate(
        "纸雕分身",
        "paper-cut character portrait, layered pale blue background, glowing blue pencil accent, delicate cut-paper shadows, crisp clean silhouette, dreamy studio lighting, minimal elegant illustration, subtle paper grain",
        "low resolution, blurry edges, torn paper fragments, distorted face, harsh shadows, oversaturated colors, text, watermark, logo, messy composition",
        R.drawable.template_paper_avatar,
        Color(0xFF8BB7F0)
    ),
    ImagePromptTemplate(
        "旅行手帐",
        "travel journal collage, mountain and sea vista, warm analog film tones, vintage paper ticket and map layers, sunlit horizon, nostalgic handmade texture, rich layered composition, gentle photographic grain",
        "cold blue cast, washed-out colors, cluttered layout, unreadable text, low quality, blurry, watermark, logo, oversaturated highlights, harsh contrast",
        R.drawable.template_travel_journal,
        Color(0xFFD4A36D)
    ),
    ImagePromptTemplate(
        "黑白漫画感",
        "black and white comic illustration, small hand-drawn robot character, bold clean ink lines, white background, expressive pose, retro manga style, strong visual hierarchy, no text, crisp monochrome artwork",
        "color, text, speech bubbles, sound effects, watermark, noise, blurry, awkward anatomy, broken linework, low quality, gray gradient",
        R.drawable.template_mono_comic,
        Color(0xFF7F8795)
    ),
    ImagePromptTemplate(
        "未来城市",
        "glass-domed futuristic city, blue-white soft lighting, lush green plants along clean boulevards, soaring transparent architecture, morning mist, optimistic sci-fi atmosphere, detailed wide cinematic 3D render",
        "dark grungy dystopia, neon overload, smog, low quality, blurry, warped buildings, text, watermark, logo, overcrowded scene, harsh shadows",
        R.drawable.template_future_city,
        Color(0xFF79B6E8)
    ),
    ImagePromptTemplate(
        "陶瓷甜点",
        "delicate pale blue ceramic dessert, pastel glaze, soft morning window light, minimalist still life photography, gentle shadows, smooth porcelain texture, airy elegant styling, high-end food photography",
        "harsh shadows, overexposed, dull colors, cracked ceramic, messy background, text, watermark, logo, low quality, blurry, plasticky texture",
        R.drawable.template_ceramic_dessert,
        Color(0xFFB7D9A7)
    )
)

@Composable
// P9/S21: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageTemplateCard(
    template: ImagePromptTemplate,
    cardWidth: Dp,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            modifier = Modifier
                .width(cardWidth)
                .height(cardWidth * 1.42f),
            color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, template.accent.copy(alpha = 0.18f))
        ) {
            Image(
                painter = painterResource(template.imageRes),
                contentDescription = template.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            template.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF858C98),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

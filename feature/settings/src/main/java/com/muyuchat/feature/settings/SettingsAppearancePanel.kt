package com.muyuchat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaShape
import com.muyuchat.core.designsystem.appearance.McaAppearanceSection
import com.muyuchat.core.designsystem.appearance.McaAppearanceThemeChoice
import com.muyuchat.core.designsystem.appearance.McaWallpaperThumbnail

/**
 * The user-visible state of the wallpaper row.
 *
 * A separate type rather than three loose parameters so the caller cannot pass a preview of one image
 * with the "set" flag of another. Every field is already-bounded/decoded data: this module never
 * touches the file system or a picker Uri.
 */
data class SettingsAppearanceWallpaperUiState(
    /** Decoded preview, already bounded by the app's wallpaper store; null when none is set. */
    val preview: ImageBitmap? = null,
    /** True once at least one wallpaper is stored, which enables the clear action. */
    val hasWallpaper: Boolean = false,
    /** Set while a pick/import/decode is in flight, so the row cannot start a second one. */
    val busy: Boolean = false,
    /** Chinese, user-facing failure text from the last attempt; cleared by the caller. */
    val message: String? = null
)

/**
 * Full appearance panel: colour mode, global wallpaper, launch screen and app icon.
 *
 * Stateless on purpose, exactly like [McaAppearanceSection]: the app composes it with its own owners and
 * it performs no persistence, no file IO and no picker launch. That keeps the whole panel renderable in
 * a preview or a screenshot test without an Activity, a preference store or a SAF grant.
 *
 * ## Signature contract (the `:app` caller owns all of this)
 *  - **colour mode**: `selected` + `onSelect`, same pair as the existing section;
 *  - **wallpaper**: [wallpaper] plus `onPickWallpaper` (app launches the SAF picker and calls
 *    `AppearanceWallpaperController.apply` off the main thread) and `onClearWallpaper`;
 *  - **launch screen**: [launchScreenSummary] is display-only text. The launch window is a build-time
 *    theme resource and cannot be changed per user at runtime, so this row reports the shipped
 *    configuration instead of pretending to be a control;
 *  - **app icon**: [iconSummary] and [onOpenIconPicker]. When [iconSwitchingAvailable] is false the row
 *    renders the honest provided statement and never shows a dead toggle.
 *
 * @param selected the persisted colour mode.
 * @param onSelect invoked with the newly chosen colour mode.
 * @param wallpaper current wallpaper projection; see [SettingsAppearanceWallpaperUiState].
 * @param onPickWallpaper asks the host to launch the image picker.
 * @param onClearWallpaper removes the wallpaper.
 * @param launchScreenSummary Chinese one-line description of the shipped launch screen.
 * @param iconSummary Chinese one-line description of the app icon capability.
 * @param iconSwitchingAvailable whether the build actually ships alternate launcher icons.
 * @param onOpenIconPicker opens the icon chooser; only invoked when [iconSwitchingAvailable].
 * @param onReplayOnboarding re-runs the first-run guide.
 * @param modifier applied to the panel's own scroll container.
 */
@Composable
public fun SettingsAppearancePanel(
    selected: McaAppearanceThemeChoice,
    onSelect: (McaAppearanceThemeChoice) -> Unit,
    wallpaper: SettingsAppearanceWallpaperUiState,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit,
    launchScreenSummary: String,
    iconSummary: String,
    onReplayOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    iconSwitchingAvailable: Boolean = false,
    onOpenIconPicker: (() -> Unit)? = null
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            McaAppearanceSection(selected = selected, onSelect = onSelect)
        }
        item {
            WallpaperCard(
                wallpaper = wallpaper,
                onPick = onPickWallpaper,
                onClear = onClearWallpaper
            )
        }
        item {
            InfoCard(
                title = "启动图",
                body = launchScreenSummary
            )
        }
        item {
            IconCard(
                summary = iconSummary,
                available = iconSwitchingAvailable,
                onOpen = onOpenIconPicker
            )
        }
        item {
            InfoCard(
                title = "新手引导",
                body = "重新走一遍首次启动时的功能说明；不会清除聊天记录、模型或已保存的设置。",
                action = "重新查看新手引导",
                onAction = onReplayOnboarding
            )
        }
    }
}

@Composable
private fun WallpaperCard(
    wallpaper: SettingsAppearanceWallpaperUiState,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, McaShape.CardAction)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("壁纸", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "选一张图片作为全局背景。图片会被压缩后保存在应用私有目录，不会上传。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        McaWallpaperThumbnail(
            wallpaper = wallpaper.preview,
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            McaButton(
                style = McaButtonStyle.Primary,
                enabled = !wallpaper.busy,
                onClick = onPick
            ) {
                Text(if (wallpaper.hasWallpaper) "更换壁纸" else "选择壁纸")
            }
            Spacer(Modifier.width(8.dp))
            McaButton(
                style = McaButtonStyle.Outlined,
                enabled = wallpaper.hasWallpaper && !wallpaper.busy,
                onClick = onClear
            ) {
                Text("清除")
            }
        }
        if (wallpaper.busy) {
            Text(
                "正在处理所选图片…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val message = wallpaper.message
        if (!message.isNullOrBlank()) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun IconCard(
    summary: String,
    available: Boolean,
    onOpen: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, McaShape.CardAction)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("应用图标", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (available && onOpen != null) {
            McaButton(style = McaButtonStyle.Outlined, onClick = onOpen) {
                Text("选择图标")
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, McaShape.CardAction)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) {
            McaButton(style = McaButtonStyle.Outlined, onClick = onAction) {
                Text(action)
            }
        }
    }
}

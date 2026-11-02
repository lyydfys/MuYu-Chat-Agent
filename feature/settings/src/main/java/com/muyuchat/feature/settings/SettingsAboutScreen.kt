package com.muyuchat.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaIcons

private const val ABOUT_AUTHOR = "lyydfys"
private const val ABOUT_REPOSITORY = "lyydfys/MuYu-Chat-Agent"
private const val ABOUT_AUTHOR_URL = "https://github.com/$ABOUT_AUTHOR"
private const val ABOUT_REPOSITORY_URL = "https://github.com/$ABOUT_REPOSITORY"
private const val ABOUT_ISSUES_URL = "$ABOUT_REPOSITORY_URL/issues"
private const val ABOUT_DONATE_URL = "$ABOUT_REPOSITORY_URL/blob/master/docs/DONATE.md"

private const val ABOUT_PREFERENCES = "mca_about_ui"
private const val ABOUT_STAR_DISMISSED_KEY = "star_dismissed"
private const val ABOUT_SKIPPED_VERSION_KEY = "skipped_version"

/**
 * Settings -> About. Reuses the existing app-update owner state; this screen never starts a second
 * update flow or persists update metadata of its own. The two UI-only preferences (star banner and
 * skipped version) stay beside the screen because they do not describe app or model state.
 */
@Composable
internal fun SettingsAboutScreen(
    state: SettingsUiState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenRelease: () -> Unit,
    onOpenWebPage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences(ABOUT_PREFERENCES, Context.MODE_PRIVATE)
    }
    var starDismissed by remember {
        mutableStateOf(preferences.getBoolean(ABOUT_STAR_DISMISSED_KEY, false))
    }
    var skippedVersion by remember {
        mutableStateOf(preferences.getString(ABOUT_SKIPPED_VERSION_KEY, "").orEmpty())
    }
    var showSponsor by rememberSaveable { mutableStateOf(false) }
    var showReleaseNotes by rememberSaveable { mutableStateOf(false) }

    val update = state.appUpdate
    val currentVersion = update.currentVersionName.ifBlank { "未知" }
    val latestVersion = update.latestVersionName
    val status = update.status.uppercase()
    val hasUpdate = aboutHasUpdate(status)
    val readyToInstall = aboutReadyToInstall(status)
    val busy = aboutBusy(status)
    val skipped = hasUpdate && !latestVersion.isNullOrBlank() && skippedVersion == latestVersion

    fun dismissStar() {
        starDismissed = true
        preferences.edit().putBoolean(ABOUT_STAR_DISMISSED_KEY, true).apply()
    }

    fun skipVersion() {
        val latest = latestVersion ?: return
        skippedVersion = latest
        preferences.edit().putString(ABOUT_SKIPPED_VERSION_KEY, latest).apply()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AboutHero(
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            status = aboutStatusLabel(update),
            onCheckUpdate = onCheckUpdate,
            onOpenRelease = {
                if (update.releaseNotes.isNotBlank()) showReleaseNotes = true else onOpenRelease()
            }
        )

        if (hasUpdate && !skipped) {
            AboutUpdateCard(
                update = update,
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                readyToInstall = readyToInstall,
                busy = busy,
                onDownloadUpdate = onDownloadUpdate,
                onInstallUpdate = onInstallUpdate,
                onSkip = ::skipVersion,
                onOpenRelease = onOpenRelease
            )
        } else if (skipped) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已跳过 v${latestVersion.orEmpty()}，可在检查更新后重新查看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    McaButton(style = McaButtonStyle.Text, onClick = onCheckUpdate) {
                        Text("检查更新")
                    }
                }
            }
        } else if (status == "ERROR") {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        update.message?.takeIf { it.isNotBlank() } ?: "检查更新失败，请稍后重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    McaButton(style = McaButtonStyle.Text, onClick = onCheckUpdate) {
                        Text("重试")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AboutLinkCard(
                icon = McaIcons.Person,
                title = "主作者",
                subtitle = ABOUT_AUTHOR,
                modifier = Modifier.weight(1f),
                onClick = { onOpenWebPage(ABOUT_AUTHOR_URL) }
            )
            AboutLinkCard(
                icon = McaIcons.Code,
                title = "开源仓库",
                subtitle = ABOUT_REPOSITORY,
                modifier = Modifier.weight(1f),
                onClick = { onOpenWebPage(ABOUT_REPOSITORY_URL) }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AboutLinkCard(
                icon = McaIcons.Favorite,
                title = "赞助支持",
                subtitle = "支持项目持续开发",
                modifier = Modifier.weight(1f),
                onClick = { showSponsor = true }
            )
            AboutLinkCard(
                icon = McaIcons.Feedback,
                title = "意见反馈",
                subtitle = "报告问题或提交建议",
                modifier = Modifier.weight(1f),
                onClick = { onOpenWebPage(ABOUT_ISSUES_URL) }
            )
        }

        if (!starDismissed) {
            AboutStarBanner(
                onOpenGithub = { onOpenWebPage(ABOUT_REPOSITORY_URL) },
                onDismiss = ::dismissStar
            )
        }

        AboutBuildInfo(
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            lastCheckedText = update.lastCheckedText,
            autoCheckEnabled = update.autoCheckEnabled
        )
    }

    if (showSponsor) {
        AboutSponsorDialog(
            onDismiss = { showSponsor = false },
            onOpenDonatePage = {
                showSponsor = false
                onOpenWebPage(ABOUT_DONATE_URL)
            },
            onOpenGithub = { onOpenWebPage(ABOUT_REPOSITORY_URL) },
            onOpenIssues = { onOpenWebPage(ABOUT_ISSUES_URL) }
        )
    }

    if (showReleaseNotes) {
        AboutReleaseNotesDialog(
            notes = update.releaseNotes,
            version = latestVersion ?: currentVersion,
            onOpenRelease = {
                showReleaseNotes = false
                onOpenRelease()
            },
            onDismiss = { showReleaseNotes = false }
        )
    }
}

@Composable
private fun AboutHero(
    currentVersion: String,
    latestVersion: String?,
    status: String,
    onCheckUpdate: () -> Unit,
    onOpenRelease: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.about_logo),
                contentDescription = null,
                modifier = Modifier.size(64.dp)
            )
            Text(
                "MCA",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "MuYu Chat Agent · 本地优先 · 模型与算力由你掌控",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "v$currentVersion",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                McaButton(style = McaButtonStyle.Outlined, onClick = onCheckUpdate) {
                    Icon(McaIcons.Refresh, contentDescription = null)
                    Text("检查更新", modifier = Modifier.padding(start = 6.dp))
                }
                McaButton(style = McaButtonStyle.Text, onClick = onOpenRelease) {
                    Icon(McaIcons.History, contentDescription = null)
                    Text("更新记录", modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (!latestVersion.isNullOrBlank() && latestVersion != currentVersion) {
                Text(
                    "最新版本 v$latestVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AboutUpdateCard(
    update: AppUpdateSettingsUiState,
    currentVersion: String,
    latestVersion: String?,
    readyToInstall: Boolean,
    busy: Boolean,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onSkip: () -> Unit,
    onOpenRelease: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "发现新版本 v${latestVersion.orEmpty()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "当前 v$currentVersion · 最新 v${latestVersion.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            update.latestTitle?.takeIf { it.isNotBlank() }?.let { title ->
                Text(title, fontWeight = FontWeight.SemiBold)
            }
            update.apkSizeText.takeIf { it.isNotBlank() }?.let { size ->
                Text(
                    "安装包：$size",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            update.message?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (update.releaseNotes.isNotBlank()) {
                ReleaseNotes(update.releaseNotes)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                McaButton(
                    style = McaButtonStyle.Primary,
                    onClick = if (readyToInstall) onInstallUpdate else onDownloadUpdate,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (readyToInstall) McaIcons.InstallMobile else McaIcons.Download,
                            contentDescription = null
                        )
                    }
                    Text(
                        when {
                            readyToInstall -> "安装更新"
                            busy -> "处理中…"
                            else -> "立即更新"
                        },
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                McaButton(style = McaButtonStyle.Outlined, onClick = onSkip, enabled = !busy) {
                    Text("跳过此版本")
                }
            }
            McaButton(style = McaButtonStyle.Text, onClick = onOpenRelease) {
                Icon(McaIcons.OpenInNew, contentDescription = null)
                Text("打开 GitHub Release", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun AboutLinkCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 116.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AboutStarBanner(
    onOpenGithub: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                McaIcons.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "如果 MCA 帮到了你",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    "请在 GitHub 点个 Star，这对我们意义重大",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            McaButton(style = McaButtonStyle.Outlined, onClick = onOpenGithub) {
                Text("去点 Star")
            }
            McaIconButton(
                contentDescription = "关闭 Star 提示",
                onClick = onDismiss
            ) {
                Icon(McaIcons.Close, contentDescription = null)
            }
        }
    }
}

@Composable
private fun AboutBuildInfo(
    currentVersion: String,
    latestVersion: String?,
    lastCheckedText: String,
    autoCheckEnabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("版本信息", fontWeight = FontWeight.SemiBold)
            AboutInfoRow("当前版本", "v$currentVersion")
            AboutInfoRow("最新版本", latestVersion?.let { "v$it" } ?: "尚未检查")
            AboutInfoRow("上次检查", lastCheckedText.ifBlank { "—" })
            AboutInfoRow("自动检查", if (autoCheckEnabled) "已开启" else "已关闭")
            AboutInfoRow("开源许可", "MIT")
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

@Composable
private fun AboutSponsorDialog(
    onDismiss: () -> Unit,
    onOpenDonatePage: () -> Unit,
    onOpenGithub: () -> Unit,
    onOpenIssues: () -> Unit
) {
    val context = LocalContext.current
    var showQr by rememberSaveable { mutableStateOf(false) }

    fun shareProject() {
        val url = ABOUT_REPOSITORY_URL
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "MCA — MuYu Chat Agent\n$url")
        }
        val chooser = Intent.createChooser(send, "分享 MCA").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }.onFailure {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("MCA", url))
            Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
        }
    }

    McaDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.about_logo),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("赞助支持", fontWeight = FontWeight.Bold)
                    Text(
                        "如果 MCA 帮到了你",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                McaIconButton(contentDescription = "关闭赞助支持", onClick = onDismiss) {
                    Icon(McaIcons.Close, contentDescription = null)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "开源项目的持续维护离不开每一份支持。可以点 Star、分享项目，或前往 GitHub 赞助页支持持续开发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    McaButton(style = McaButtonStyle.Primary, onClick = onOpenDonatePage) {
                        Icon(McaIcons.Favorite, contentDescription = null)
                        Text("去赞助", modifier = Modifier.padding(start = 6.dp))
                    }
                    McaButton(style = McaButtonStyle.Outlined, onClick = onOpenGithub) {
                        Icon(McaIcons.Star, contentDescription = null)
                        Text("去点 Star", modifier = Modifier.padding(start = 6.dp))
                    }
                    McaButton(style = McaButtonStyle.Outlined, onClick = ::shareProject) {
                        Icon(McaIcons.Share, contentDescription = null)
                        Text("分享项目", modifier = Modifier.padding(start = 6.dp))
                    }
                    McaButton(style = McaButtonStyle.Outlined, onClick = onOpenIssues) {
                        Icon(McaIcons.Feedback, contentDescription = null)
                        Text("意见反馈", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Text(
                    "赞助完全自愿，不影响任何功能。感谢每一份支持！",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                McaButton(style = McaButtonStyle.Text, onClick = { showQr = !showQr }) {
                    Text(if (showQr) "收起离线赞赏码" else "扫码赞助（国内可直连）")
                }
                if (showQr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AboutQrCode(
                            drawable = R.drawable.about_alipay_qr,
                            label = "支付宝",
                            modifier = Modifier.weight(1f)
                        )
                        AboutQrCode(
                            drawable = R.drawable.about_wechat_qr,
                            label = "微信支付",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "GitHub 访问不稳定时，可直接使用上方离线赞赏码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            McaButton(style = McaButtonStyle.Outlined, onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun AboutQrCode(drawable: Int, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Image(
            painter = painterResource(drawable),
            contentDescription = "${label}收款码",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutReleaseNotesDialog(
    notes: String,
    version: String,
    onOpenRelease: () -> Unit,
    onDismiss: () -> Unit
) {
    McaDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新记录 · v$version", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (notes.isBlank()) {
                    Text("该版本没有填写发布说明。", style = MaterialTheme.typography.bodySmall)
                } else {
                    ReleaseNotes(notes)
                }
            }
        },
        confirmButton = {
            McaButton(style = McaButtonStyle.Text, onClick = onOpenRelease) {
                Text("打开 GitHub Release")
            }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text, onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun aboutHasUpdate(status: String): Boolean =
    status == "AVAILABLE" || status == "DOWNLOADING" || status == "VERIFYING" ||
        status == "READY_TO_INSTALL" || status == "READY" || status.contains("可更新")

private fun aboutReadyToInstall(status: String): Boolean =
    status == "READY_TO_INSTALL" || status == "READY" || status.contains("可安装")

private fun aboutBusy(status: String): Boolean =
    status == "CHECKING" || status == "DOWNLOADING" || status == "VERIFYING" ||
        status.contains("检查中") || status.contains("下载中") || status.contains("校验中")

private fun aboutStatusLabel(update: AppUpdateSettingsUiState): String {
    val status = update.status.uppercase()
    val latest = update.latestVersionName
    return when {
        status == "IDLE" || status.isBlank() -> "尚未检查更新"
        status == "CHECKING" -> "正在检查 GitHub Releases…"
        status == "UP_TO_DATE" -> "已是最新版本 v${latest ?: update.currentVersionName.ifBlank { "—" }}"
        status == "AVAILABLE" -> "发现新版本 v${latest.orEmpty()}"
        status == "DOWNLOADING" -> "正在下载更新…"
        status == "VERIFYING" -> "正在校验更新包…"
        status == "READY_TO_INSTALL" || status == "READY" -> "更新包已就绪，可安装"
        status == "ERROR" -> update.message?.takeIf { it.isNotBlank() } ?: "检查更新失败"
        else -> update.message?.takeIf { it.isNotBlank() } ?: update.status
    }
}

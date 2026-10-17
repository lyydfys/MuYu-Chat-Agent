package com.muyuchat.feature.chat

import android.os.SystemClock
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ImagePromptTagPanelDismissState(
    val observedText: String,
    val wasFocused: Boolean,
    val dismissed: Boolean
) {
    fun observe(text: String, focused: Boolean): ImagePromptTagPanelDismissState {
        val shouldRestore = text != observedText || (focused && !wasFocused)
        return ImagePromptTagPanelDismissState(
            observedText = text,
            wasFocused = focused,
            dismissed = if (shouldRestore) false else dismissed
        )
    }

    fun dismiss(): ImagePromptTagPanelDismissState = copy(dismissed = true)
}

internal fun imagePromptTagDictionaryStateText(state: ImagePromptTagDictionaryState): String =
    when (state) {
        ImagePromptTagDictionaryState.NotConfigured -> "未导入"
        is ImagePromptTagDictionaryState.Available ->
            "${state.rowCount} 条 · ${formatImagePromptTagByteCount(state.byteCount)}"
        is ImagePromptTagDictionaryState.Corrupt ->
            "文件不可用：${imagePromptTagStoreIssueMessage(state.issue)}"
        ImagePromptTagDictionaryState.ReadFailed -> "读取失败，请重新导入。"
    }

internal fun imagePromptTagStoreIssueMessage(issue: ImagePromptTagStoreIssue): String = when (issue) {
    ImagePromptTagStoreIssue.UNSUPPORTED_URI -> "请选择系统文件选择器中的 CSV 文件。"
    ImagePromptTagStoreIssue.SOURCE_UNREADABLE -> "无法读取所选文件，请检查访问权限。"
    ImagePromptTagStoreIssue.FILE_TOO_LARGE -> "文件超过大小限制。"
    ImagePromptTagStoreIssue.TOO_MANY_LINES -> "词典行数超过上限。"
    ImagePromptTagStoreIssue.EMPTY_DICTIONARY -> "词典中没有可用记录。"
    ImagePromptTagStoreIssue.MALFORMED_CSV -> "CSV 格式或 UTF-8 编码无效。"
    ImagePromptTagStoreIssue.INDEX_REJECTED -> "词典包含重复标签或索引规模超限。"
    ImagePromptTagStoreIssue.STORAGE_READ_FAILED -> "词典读取失败，请重新导入。"
    ImagePromptTagStoreIssue.TRANSACTION_FAILED -> "词典保存失败，原有词典未被替换。"
}

internal fun formatImagePromptTagByteCount(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1_024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1_024.0 * 1_024.0))
}

internal fun formatImagePromptTagPopularity(postCount: Long): String = when {
    postCount >= 100_000_000L -> String.format(Locale.ROOT, "%.1f 亿", postCount / 100_000_000.0)
    postCount >= 10_000L -> String.format(Locale.ROOT, "%.1f 万", postCount / 10_000.0)
    postCount >= 1_000L -> String.format(Locale.ROOT, "%.1f k", postCount / 1_000.0)
    else -> postCount.coerceAtLeast(0L).toString()
}

internal fun imagePromptTagMatchKindLabel(kind: ImagePromptTagMatchKind): String = when (kind) {
    ImagePromptTagMatchKind.TAG_PREFIX -> "标签"
    ImagePromptTagMatchKind.ALIAS_PREFIX -> "别名"
    ImagePromptTagMatchKind.TRANSLATION_PREFIX -> "翻译"
    ImagePromptTagMatchKind.FUZZY_TAG -> "模糊标签"
    ImagePromptTagMatchKind.FUZZY_ALIAS -> "模糊别名"
    ImagePromptTagMatchKind.FUZZY_TRANSLATION -> "模糊翻译"
}

internal class ImagePromptTagEnabledPreference(context: Context) {
    private val root = File(
        context.applicationContext.noBackupFilesDir,
        "image_prompt_tag_autocomplete_ui"
    )
    private val stateFile = File(root, "enabled.state")

    suspend fun read(): Boolean = withContext(Dispatchers.IO) {
        if (!stateFile.isFile) return@withContext true
        when (stateFile.readText(Charsets.UTF_8).trim()) {
            "1" -> true
            "0" -> false
            else -> throw IllegalStateException("Invalid autocomplete preference state.")
        }
    }

    suspend fun write(enabled: Boolean) = withContext(Dispatchers.IO) {
        if (!root.exists() && !root.mkdirs()) {
            throw IllegalStateException("Unable to create autocomplete preference directory.")
        }
        check(root.isDirectory) { "Autocomplete preference root is invalid." }
        val staged = File(root, ".enabled.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(staged).use { output ->
                output.write(if (enabled) '1'.code else '0'.code)
                output.flush()
                output.fd.sync()
            }
            replaceImagePromptTagSnapshot(staged, stateFile)
        } finally {
            staged.delete()
        }
    }
}

internal val MIN_TOUCH_TARGET_DP = 48.dp
internal const val PANEL_SUGGESTION_LIMIT: Int = 6

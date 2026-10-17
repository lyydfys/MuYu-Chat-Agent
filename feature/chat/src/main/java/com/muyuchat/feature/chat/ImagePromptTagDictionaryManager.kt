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

@Composable
internal fun ImagePromptTagDictionaryManagerDialog(
    session: ImagePromptTagAutocompleteUiSession,
    onDismiss: () -> Unit,
    onImportTags: () -> Unit,
    onImportTranslations: () -> Unit,
    onClear: (ImagePromptTagDictionaryKind) -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标签联想词典") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(
                            value = session.enabled,
                            enabled = !session.busy,
                            role = Role.Switch,
                            onValueChange = onEnabledChange
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用标签联想", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "正向与负向提示词共用同一份词典。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = session.enabled,
                        onCheckedChange = null,
                        enabled = !session.busy
                    )
                }

                HorizontalDivider()
                DictionaryStateSection(
                    title = "主标签词典",
                    supportingText = imagePromptTagDictionaryStateText(session.status.tags),
                    configured = session.status.tags !is ImagePromptTagDictionaryState.NotConfigured,
                    busy = session.busy,
                    onImport = onImportTags,
                    onClear = { onClear(ImagePromptTagDictionaryKind.TAGS) }
                )
                HorizontalDivider()
                DictionaryStateSection(
                    title = "翻译词典（可选）",
                    supportingText = imagePromptTagDictionaryStateText(session.status.translations),
                    configured = session.status.translations !is ImagePromptTagDictionaryState.NotConfigured,
                    busy = session.busy,
                    onImport = onImportTranslations,
                    onClear = { onClear(ImagePromptTagDictionaryKind.TRANSLATIONS) }
                )

                if (session.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                session.notice?.let { notice ->
                    Text(
                        notice.text,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notice is ImagePromptTagUiNotice.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP)
            ) { Text("完成") }
        }
    )
}

@Composable
private fun DictionaryStateSection(
    title: String,
    supportingText: String,
    configured: Boolean,
    busy: Boolean,
    onImport: () -> Unit,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            supportingText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onImport,
                enabled = !busy,
                modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP)
            ) { Text(if (configured) "替换 CSV" else "导入 CSV") }
            if (configured) {
                TextButton(
                    onClick = onClear,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP)
                ) { Text("清除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}


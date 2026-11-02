package com.muyuchat.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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

// P9/S13: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun extractAttachmentName(input: String): String? =
    ATTACHMENT_NAME_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(input)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

// P9/S13: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun displayInputWithoutAttachment(input: String): String {
    val marker = firstAttachmentMarker(input)
    if (marker < 0) return input
    return input.substring(0, marker).trimEnd()
}

internal fun saveCameraPreview(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, "mca_camera").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
    }
    return Uri.fromFile(file)
}

internal fun removeAttachmentFromInput(input: String): String =
    displayInputWithoutAttachment(input).trimEnd()

internal fun mergeInputWithExistingAttachment(currentInput: String, visibleText: String): String {
    val marker = firstAttachmentMarker(currentInput)
    if (marker < 0) return visibleText
    val attachment = currentInput.substring(marker).trimStart()
    return buildString {
        append(visibleText)
        if (visibleText.isNotBlank()) append("\n\n")
        append(attachment)
    }
}

private val ATTACHMENT_PREFIXES = listOf("【上传文件：", "【上传图片：")

private fun firstAttachmentMarker(input: String): Int =
    ATTACHMENT_PREFIXES
        .map { input.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull() ?: -1

private val ATTACHMENT_NAME_PATTERNS = listOf(
    Regex("""【上传文件：([^】]+)】"""),
    Regex("""【上传图片：([^】]+)】""")
)

private val IMAGE_ATTACHMENT_PATTERN = Regex("""【上传图片：([^】]+)】""")
private val IMAGE_ATTACHMENT_WITH_URI_PATTERN = Regex("""【上传图片：([^】]+)】(?:\s*\n描述：[^\n]+)?\s*\n(\S+)""")

private const val CHAT_ATTACHMENT_IDENTITY_MAX_BYTES = 64L * 1024L * 1024L

/**
 * Resolves the content identity used by the composer preflight and message bubble.
 *
 * Picker URIs are not the only representation that can occur here.  Imported
 * assets are persisted as private `file://` URIs, and an older history can also
 * contain an absolute path.  The old implementation returned `null` for those
 * paths, so two copies of the same picked image survived the UI preflight and
 * were rendered as two attachments until the conversation was reloaded.  Keep
 * content-provider reads context-aware, then delegate every file/data/URL form
 * to the engine's shared identity contract.
 *
 * This function is called from a coroutine on Dispatchers.IO.  It streams raw
 * bytes and never retains the image in memory.
 */
internal fun chatImageAttachmentContentIdentity(context: Context, rawUri: String): String? {
    val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
    if (!uri.scheme.equals("content", ignoreCase = true)) {
        // Local imported files and inline data URLs can use the same byte-based
        // identity as the native/cloud request path.  Remote URLs remain
        // conservative and are normalized by the shared helper.
        return runCatching {
            ChatImageAttachment(uriString = rawUri).visionDeduplicationKey()
        }.getOrNull()
    }
    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
            "Cannot read image attachment URI."
        }
        var totalBytes = 0L
        input.use { stream ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                totalBytes += read
                require(totalBytes <= CHAT_ATTACHMENT_IDENTITY_MAX_BYTES) {
                    "Image attachment is too large to hash for duplicate detection."
                }
                digest.update(buffer, 0, read)
            }
        }
        "bytes:" + digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }.getOrNull()
}

// P9/S13: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun hasImageAttachmentMarker(input: String): Boolean =
    IMAGE_ATTACHMENT_PATTERN.containsMatchIn(input)

// P9/S13: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun extractAttachmentMeta(input: String): String? {
    val uriString = IMAGE_ATTACHMENT_WITH_URI_PATTERN.find(input)
        ?.groupValues
        ?.getOrNull(2)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val sourceLabel = when {
        uriString.startsWith("content://", ignoreCase = true) -> "系统图片"
        uriString.startsWith("file://", ignoreCase = true) -> "本地文件"
        uriString.startsWith("http://", ignoreCase = true) || uriString.startsWith("https://", ignoreCase = true) -> "网络图片"
        uriString.startsWith("data:image", ignoreCase = true) -> "内联图片"
        else -> "图片输入"
    }
    val extension = uriString
        .substringBefore('?')
        .substringAfterLast('.', "")
        .takeIf { it.length in 2..5 }
        ?.uppercase()
    return listOfNotNull(sourceLabel, extension, "发送前自动压缩").joinToString(" · ")
}


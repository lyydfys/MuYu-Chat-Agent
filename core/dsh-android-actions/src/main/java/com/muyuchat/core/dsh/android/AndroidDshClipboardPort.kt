package com.muyuchat.core.dsh.android

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `POST /clipboard` adapter over `ClipboardManager`.
 *
 * Android 10+ only lets a focused app read the clipboard; the adapter reports that as
 * `NOT_ENABLED` with a reason instead of returning empty content, so the model does not silently
 * conclude the clipboard is empty. Writes mark the clip sensitive (`EXTRA_IS_SENSITIVE`), which
 * Android 13+ uses to hide the clipboard preview.
 */
class AndroidDshClipboardPort(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : DshClipboardPort {

    private val appContext: Context = context.applicationContext

    private val clipboardManager: ClipboardManager? =
        appContext.getSystemService(ClipboardManager::class.java)

    override fun state(): DshCapabilityState =
        if (clipboardManager == null) DshCapabilityState.UNSUPPORTED else DshCapabilityState.GRANTED

    override suspend fun read(): DshClipboardReadOutcome = withContext(dispatcher) {
        val manager = clipboardManager
            ?: return@withContext DshClipboardReadOutcome.Unavailable(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE,
                    "当前系统没有剪贴板服务"
                )
            )
        val clip = try {
            manager.primaryClip
        } catch (_: SecurityException) {
            return@withContext DshClipboardReadOutcome.Unavailable(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.NOT_ENABLED,
                    "只有在应用处于前台时才能读取剪贴板"
                )
            )
        } catch (_: RuntimeException) {
            return@withContext DshClipboardReadOutcome.Unavailable(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.INTERNAL,
                    "读取剪贴板失败"
                )
            )
        }
        if (clip == null || clip.itemCount == 0) {
            return@withContext DshClipboardReadOutcome.Empty
        }
        val item = clip.getItemAt(0)
            ?: return@withContext DshClipboardReadOutcome.Empty
        val text = try {
            item.coerceToText(appContext)?.toString().orEmpty()
        } catch (_: RuntimeException) {
            ""
        }
        if (text.isEmpty() && item.text == null) {
            DshClipboardReadOutcome.Empty
        } else {
            DshClipboardReadOutcome.Text(text)
        }
    }

    override suspend fun write(
        text: String,
        sensitive: Boolean
    ): DshAndroidActionFailure? = withContext(dispatcher) {
        val manager = clipboardManager
            ?: return@withContext DshAndroidActionFailure.of(
                DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE,
                "当前系统没有剪贴板服务"
            )
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        try {
            manager.setPrimaryClip(clip)
            null
        } catch (_: SecurityException) {
            DshAndroidActionFailure.of(
                DshAndroidActionErrorCode.NOT_ENABLED,
                "只有在应用处于前台时才能写入剪贴板"
            )
        } catch (_: RuntimeException) {
            DshAndroidActionFailure.of(
                DshAndroidActionErrorCode.INTERNAL,
                "写入剪贴板失败"
            )
        }
    }

    companion object {
        private const val CLIP_LABEL = "DeepSeek Harness"
    }
}

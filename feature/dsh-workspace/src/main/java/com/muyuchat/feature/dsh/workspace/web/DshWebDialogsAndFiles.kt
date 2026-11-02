package com.muyuchat.feature.dsh.workspace.web

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.muyuchat.feature.dsh.workspace.DshWebFileChooserPolicy

/**
 * One pending `<input type="file">` request from the official page.
 *
 * The intent is built by [WebChromeClient.FileChooserParams.createIntent] so the page's own
 * `accept` and `multiple` attributes are preserved; the host never guesses MIME types. The result
 * contract below reads both the single-URI and ClipData (multi-select) shapes.
 */
internal data class DshFileChooserRequest(val id: Long, val intent: Intent)

internal class DshFileChooserCoordinator {

    private var sequence = 0L
    private var pending: ValueCallback<Array<Uri>>? = null

    var request by mutableStateOf<DshFileChooserRequest?>(null)
        private set

    fun begin(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean {
        cancelPending()
        val intent = runCatching { params.createIntent() }.getOrNull()
        if (intent == null) {
            runCatching { callback.onReceiveValue(null) }
            return false
        }
        // A directory tree is a persistent grant; only the capability owner's own
        // OpenDocumentTree flow may request one, never a page-driven <input type="file">.
        if (DshWebFileChooserPolicy.refusalFor(intent.action) != null) {
            runCatching { callback.onReceiveValue(null) }
            return false
        }
        if (intent.action == Intent.ACTION_GET_CONTENT || intent.action == Intent.ACTION_OPEN_DOCUMENT) {
            intent.addCategory(Intent.CATEGORY_OPENABLE)
        }
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        // One-shot read only: no FLAG_GRANT_PERSISTABLE_URI_PERMISSION is ever added here.
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        pending = callback
        sequence += 1
        request = DshFileChooserRequest(sequence, intent)
        return true
    }

    /** Delivers the SAF result; an empty list means the user cancelled. */
    fun deliver(uris: List<Uri>) {
        val callback = pending ?: return
        pending = null
        request = null
        runCatching { callback.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray()) }
    }

    fun cancelPending() {
        val callback = pending ?: return
        pending = null
        request = null
        runCatching { callback.onReceiveValue(null) }
    }
}

internal class DshFileChooserContract : ActivityResultContract<Intent, List<Uri>>() {

    override fun createIntent(context: Context, input: Intent): Intent = input

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val uris = LinkedHashSet<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let(uris::add)
            }
        }
        intent.data?.let(uris::add)
        return uris.toList()
    }
}

internal enum class DshJsDialogKind { ALERT, CONFIRM, PROMPT }

internal data class DshJsDialogRequest(
    val id: Long,
    val kind: DshJsDialogKind,
    val message: String,
    val defaultValue: String?,
    val onConfirm: (String?) -> Unit,
    val onCancel: () -> Unit
)

/**
 * JS dialogs the official page may use (`alert`/`confirm`/`prompt`). The default WebChromeClient
 * silently drops them, which breaks confirmation flows; the host renders them with MCA's own
 * Material dialog instead. Text is redacted and never logged.
 */
internal class DshJsDialogController {

    private var sequence = 0L

    var request by mutableStateOf<DshJsDialogRequest?>(null)
        private set

    fun showAlert(message: String, result: JsResult) {
        show(DshJsDialogKind.ALERT, message, null, onConfirm = { result.confirm() }, onCancel = { result.cancel() })
    }

    fun showConfirm(message: String, result: JsResult) {
        show(DshJsDialogKind.CONFIRM, message, null, onConfirm = { result.confirm() }, onCancel = { result.cancel() })
    }

    fun showPrompt(message: String, defaultValue: String?, result: JsPromptResult) {
        show(
            DshJsDialogKind.PROMPT,
            message,
            defaultValue,
            onConfirm = { value -> result.confirm(value.orEmpty()) },
            onCancel = { result.cancel() }
        )
    }

    fun confirm(value: String?) {
        val current = request ?: return
        request = null
        runCatching { current.onConfirm(value) }
    }

    fun cancel() {
        val current = request ?: return
        request = null
        runCatching { current.onCancel() }
    }

    fun dismissAll() = cancel()

    private fun show(
        kind: DshJsDialogKind,
        message: String,
        defaultValue: String?,
        onConfirm: (String?) -> Unit,
        onCancel: () -> Unit
    ) {
        cancel()
        sequence += 1
        val safeMessage = com.muyuchat.feature.dsh.workspace.DshUrlRedaction.redactText(message)
        request = DshJsDialogRequest(
            id = sequence,
            kind = kind,
            message = safeMessage,
            defaultValue = defaultValue,
            onConfirm = onConfirm,
            onCancel = onCancel
        )
    }
}

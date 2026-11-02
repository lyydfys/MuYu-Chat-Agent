package com.muyuchat.mca.composition

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Share/save entry for an exported DSH diagnostic bundle.
 *
 * The export itself is owned by the maintenance adapter: it writes one already-redacted JSON file
 * (the exporter fails closed when a secret or an absolute private path survives) under
 * `files/dsh/maintenance/diagnostics/`. This class is the only code that exposes that file outside
 * the app, and it does so with the narrowest possible grant:
 *
 *  - the URI is issued by the existing app [FileProvider], whose paths file exposes exactly that one
 *    diagnostics directory (not the files root, not the cache, not external storage);
 *  - the intent is a plain `ACTION_SEND` chooser with a read-only URI grant;
 *  - a missing handler returns false so the console reports "exported, share unavailable" instead of
 *    claiming the share sheet opened;
 *  - nothing else (paths, session content, credentials, tokens) is ever put in the intent.
 */
internal class DshDiagnosticsSharer(
    private val context: Context,
    private val startChooser: (Intent) -> Unit = { intent -> context.startActivity(intent) }
) {
    fun share(file: File): Boolean {
        if (!DshDiagnosticsShareContract.isShareable(file)) return false
        val authority = context.applicationContext.packageName + DshDiagnosticsShareContract.FILE_PROVIDER_SUFFIX
        val uri: Uri = runCatching {
            FileProvider.getUriForFile(context.applicationContext, authority, file)
        }.getOrNull() ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = DshDiagnosticsShareContract.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, DshDiagnosticsShareContract.SUBJECT)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, DshDiagnosticsShareContract.CHOOSER_TITLE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { startChooser(chooser) }.isSuccess
    }
}

/**
 * Platform-free part of the share contract (unit-testable).
 *
 * Only a non-empty `.json` produced by the diagnostics exporter is shareable: the console must not
 * hand an arbitrary file to the chooser even if a caller passes one by mistake.
 */
internal object DshDiagnosticsShareContract {
    const val MIME_TYPE = "application/json"
    const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    const val SUBJECT = "MCA DSH 诊断包"
    const val CHOOSER_TITLE = "分享诊断包"

    /** The provider path entry that must exist for [DshDiagnosticsSharer] to be able to share. */
    const val PROVIDER_PATH_ENTRY = "dsh/maintenance/diagnostics"

    fun isShareable(file: File): Boolean =
        file.isFile && file.length() > 0L && file.name.endsWith(".json", ignoreCase = true)
}

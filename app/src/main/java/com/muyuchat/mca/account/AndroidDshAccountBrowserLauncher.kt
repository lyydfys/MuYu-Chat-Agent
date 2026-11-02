package com.muyuchat.mca.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.muyuchat.core.dsh.contract.auth.DshBrowserHandoffResult
import com.muyuchat.core.dsh.runtime.auth.DshExternalBrowserLauncher
import java.net.URI

/**
 * Android transport for the official account authorize URL.
 *
 * This is the D04 handoff: the URL is handed to the system browser (a separate surface, so the
 * official authorize page never shares the DSH WebView cookie jar). It is never loaded inside the
 * DSH WebView, never copied into a log and never persisted.
 *
 * The launcher is deliberately dumb: the account lane has already validated the URL against the
 * live official attempt. The only local checks are transport-shaped (a browser can handle an
 * absolute `https` URL) and every failure is a typed [DshBrowserHandoffResult], never a fake
 * "opened".
 */
internal class AndroidDshAccountBrowserLauncher(
    context: Context
) : DshExternalBrowserLauncher {

    private val applicationContext: Context = context.applicationContext

    override fun open(url: String): DshBrowserHandoffResult {
        val candidate = url.trim()
        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return DshBrowserHandoffResult.Rejected(BROWSER_CANNOT_OPEN)
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return DshBrowserHandoffResult.Rejected(BROWSER_CANNOT_OPEN)
        }
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
            return DshBrowserHandoffResult.Rejected(BROWSER_CANNOT_OPEN)
        }
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull()
            ?: return DshBrowserHandoffResult.Rejected(BROWSER_CANNOT_OPEN)
        val intent = Intent(Intent.ACTION_VIEW, parsed).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            // The lane is app-scoped and may be invoked while no Activity is resumed.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { applicationContext.startActivity(intent) }
            .fold(
                onSuccess = { DshBrowserHandoffResult.Opened },
                // No activity can handle the URL (or the launch was refused): typed degradation.
                onFailure = { DshBrowserHandoffResult.NoHandler }
            )
    }

    private companion object {
        const val BROWSER_CANNOT_OPEN = "browser_cannot_open_authorize_url"
    }
}

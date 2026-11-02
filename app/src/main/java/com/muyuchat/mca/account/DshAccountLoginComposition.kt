package com.muyuchat.mca.account

import android.content.Context
import java.io.File
import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.console.DshExternalBrowserPort
import com.muyuchat.core.dsh.runtime.auth.DshAccountLoginOwner
import kotlinx.coroutines.CoroutineScope

/**
 * Creates the app-scoped DeepSeek account lane for the DSH workspace.
 *
 * The lane is created once per app composition (never per page or per WebView) because one sign-in
 * attempt spans: the hidden DSH popup, the system browser round-trip, the callback inside the DSH
 * node host, and possibly a page/tab switch. Composition owns creation and cancellation; the lane
 * owns correlation and terminal states.
 *
 * @param runtime the single DSH runtime client. Its loopback `origin` is both the official
 *   `callbackOrigin` and the only origin allowed to pre-open the account popup.
 * @param externalFallback the existing browser port, used for ordinary external links so their
 *   behavior does not change.
 */
internal fun createDshAccountLoginOwner(
    context: Context,
    scope: CoroutineScope,
    runtime: DshRuntimeClient,
    externalFallback: DshExternalBrowserPort
): DshAccountLoginOwner = DshAccountLoginOwner(
    scope = scope,
    callbackOrigin = { runtime.snapshot.value.origin },
    trustedWebOrigin = { runtime.snapshot.value.origin },
    launcher = AndroidDshAccountBrowserLauncher(context),
    externalFallback = externalFallback,
    journal = SharedPreferencesDshAccountAttemptJournal(context),
    credentialFingerprint = { dshCredentialFingerprint(context) }
)

/**
 * Presence-only fingerprint of the official DeepSeek credential store (`size:mtime`).
 *
 * The official module writes this file exactly when it commits a grant; the fingerprint proves a
 * store change without reading a single credential byte. Null means absent/unreadable, never "signed
 * out".
 */
private fun dshCredentialFingerprint(context: Context): String? = runCatching {
    val store = File(context.filesDir, "dsh/home/.credentials.yaml")
    if (store.isFile && store.length() > 0L) "${store.length()}:${store.lastModified()}" else null
}.getOrNull()

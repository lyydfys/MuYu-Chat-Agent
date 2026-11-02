package com.muyuchat.core.dsh.platform

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Constructs the one persistent workspace-tree grant owner for this app process.
 *
 * This is the only place that wires the `ContentResolver` adapter and the app-private store to the
 * owner, so the app composition keeps a single owner and the capability assembly stays small. The
 * picker port is supplied by the app composition (it needs the resumed Activity); the scope is
 * the caller's, so a host stop can cancel the pending pick without touching the persisted grant.
 */
class DshWorkspaceTreeGrantHost(
    context: Context,
    picker: DshWorkspaceTreePickerPort,
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis
) {
    val owner: DshWorkspaceTreeGrantOwner = DshWorkspaceTreeGrantOwner(
        platform = AndroidDshWorkspaceTreeGrantPlatform(context),
        store = SharedPreferencesDshWorkspaceTreeGrantStore(context),
        picker = picker,
        scope = scope,
        clock = clock
    )
}

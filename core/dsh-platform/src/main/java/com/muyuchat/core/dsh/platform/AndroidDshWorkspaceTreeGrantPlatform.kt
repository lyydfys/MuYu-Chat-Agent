package com.muyuchat.core.dsh.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeFlags

/**
 * Thin `ContentResolver` adapter of the workspace-tree grant.
 *
 * Every platform exception is mapped onto [DshWorkspaceTreeGrantObservation.ProviderUnavailable];
 * no exception escapes into the owner, and no provider is asked to open user content just to
 * answer a status query. The only side effects are the two platform permission calls.
 */
class AndroidDshWorkspaceTreeGrantPlatform(context: Context) : DshWorkspaceTreeGrantPlatform {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override fun observe(treeUri: String): DshWorkspaceTreeGrantObservation {
        val uri = parse(treeUri) ?: return DshWorkspaceTreeGrantObservation.ProviderUnavailable
        val held = runCatching { resolver.persistedUriPermissions }
            .getOrElse { return DshWorkspaceTreeGrantObservation.ProviderUnavailable }
        val match = held.firstOrNull { it.uri == uri }
            ?: return DshWorkspaceTreeGrantObservation.NotHeld
        if (!providerReachable(uri)) {
            return DshWorkspaceTreeGrantObservation.ProviderUnavailable
        }
        return DshWorkspaceTreeGrantObservation.Held(
            DshWorkspaceTreeFlags(
                read = match.isReadPermission,
                write = match.isWritePermission
            )
        )
    }

    override fun takePersistable(
        treeUri: String,
        flags: DshWorkspaceTreeFlags
    ): DshWorkspaceTreeGrantObservation {
        val uri = parse(treeUri) ?: return DshWorkspaceTreeGrantObservation.ProviderUnavailable
        if (!providerReachable(uri)) {
            return DshWorkspaceTreeGrantObservation.ProviderUnavailable
        }
        val modeFlags = buildModeFlags(flags)
        if (modeFlags == 0) return DshWorkspaceTreeGrantObservation.NotHeld
        val taken = runCatching {
            resolver.takePersistableUriPermission(uri, modeFlags)
        }.isSuccess
        if (!taken) return DshWorkspaceTreeGrantObservation.ProviderUnavailable
        return observe(treeUri)
    }

    override fun releasePersistable(treeUri: String): DshWorkspaceTreeGrantObservation {
        val uri = parse(treeUri) ?: return DshWorkspaceTreeGrantObservation.ProviderUnavailable
        val held = runCatching { resolver.persistedUriPermissions }
            .getOrElse { return DshWorkspaceTreeGrantObservation.ProviderUnavailable }
        val match = held.firstOrNull { it.uri == uri }
            ?: return DshWorkspaceTreeGrantObservation.NotHeld
        // Release exactly the modes the platform currently holds; passing a mode that was never
        // granted is rejected by the system and would leave the real grant behind.
        var mode = 0
        if (match.isReadPermission) mode = mode or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (match.isWritePermission) mode = mode or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (mode == 0) return DshWorkspaceTreeGrantObservation.NotHeld
        val released = runCatching {
            resolver.releasePersistableUriPermission(uri, mode)
        }.isSuccess
        return if (released) {
            DshWorkspaceTreeGrantObservation.NotHeld
        } else {
            DshWorkspaceTreeGrantObservation.ProviderUnavailable
        }
    }

    private fun parse(treeUri: String): Uri? =
        runCatching { Uri.parse(treeUri) }.getOrNull()?.takeIf { it.scheme == "content" }

    private fun buildModeFlags(flags: DshWorkspaceTreeFlags): Int {
        var mode = 0
        if (flags.read) mode = mode or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (flags.write) mode = mode or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return mode
    }

    /**
     * Observes provider liveness without reading content. `acquireUnstableContentProviderClient`
     * resolves through the app's own URI grant, so package-visibility filtering cannot turn a
     * healthy provider into a false PROVIDER_UNAVAILABLE.
     */
    private fun providerReachable(uri: Uri): Boolean = runCatching {
        val client = resolver.acquireUnstableContentProviderClient(uri) ?: return@runCatching false
        try {
            true
        } finally {
            client.close()
        }
    }.getOrDefault(false)
}

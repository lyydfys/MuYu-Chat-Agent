package com.muyuchat.core.dsh.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Small, shared permission probes used by the Android adapters.
 *
 * The distinction between "not declared in the manifest" and "declared but not granted" is real
 * user information (add the permission vs grant it), so the adapters ask the package manager for
 * the declared list instead of guessing from the runtime result.
 */
internal object AndroidDshPermissionChecks {

    /** True when the merged manifest of this app declares [permission]. */
    fun declares(context: Context, permission: String): Boolean {
        val packageInfo = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return packageInfo.requestedPermissions?.contains(permission) == true
    }

    /** True when the runtime grant for [permission] is currently held. */
    fun runtimeGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * `POST_NOTIFICATIONS` only exists from API 33; below that the permission is not a runtime
     * grant and notification delivery is controlled solely by the user's app-notification switch.
     */
    fun notificationPermissionRequired(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun postNotificationsPermission(): String = Manifest.permission.POST_NOTIFICATIONS
}

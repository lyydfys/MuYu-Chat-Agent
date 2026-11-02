package com.muyuchat.core.dsh.android

import android.content.Context
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentPort

/**
 * Builds the six App-local Android routes from Android adapters.
 *
 * App composition usage (the Lead owns the `app` module):
 *
 * ```kotlin
 * val androidActions = AndroidDshActionsFactory.create(
 *     context = appContext,
 *     overlayController = dshOverlayController,
 *     channels = privilegedChannelProvider,   // root probe + Shizuku bridge state
 *     consent = capabilityConsentPort,        // shows the in-app consent card
 *     shizukuShellTransport = shizukuShellTransport // null ⇒ typed CAPABILITY_UNAVAILABLE
 * )
 * val routeTable = DshCapabilityRouteTable.builder()
 *     .also(androidActions::registerInto)
 *     .build()
 * ```
 *
 * The factory does not mint or read the capability token, does not start the server and does not
 * decide when the app process is bound; those belong to `:core:dsh-platform` and the runtime owner.
 *
 * Required app-side platform declarations (Lead-owned manifest, listed here so the choice is
 * explicit): `POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS` and
 * `PACKAGE_USAGE_STATS` (the last two are appop/signature permissions and need an explicit lint
 * suppression in the app manifest). Notification taps resolve the app's own launcher intent only.
 */
object AndroidDshActionsFactory {

    fun create(
        context: Context,
        overlayController: DshOverlayWindowController,
        channels: DshPrivilegedChannelProvider,
        consent: DshCapabilityConsentPort,
        shizukuShellTransport: DshShizukuShellTransport? = null,
        smallNotificationIconResId: Int = android.R.drawable.ic_dialog_info,
        shellConfig: DshShellActionConfig = DshShellActionConfig(),
        consentAvailable: Boolean = true
    ): DshAndroidActions {
        val appContext = context.applicationContext
        val notificationPort = AndroidDshNotificationPort(appContext, smallNotificationIconResId)
        val overlayPort = AndroidDshOverlayPort(appContext, overlayController)
        val usagePort = AndroidDshUsageStatsPort(appContext)
        val clipboardPort = AndroidDshClipboardPort(appContext)
        val settingsPort = AndroidDshSettingsPort(appContext)
        val environmentProbe = AndroidDshEnvironmentProbe(
            context = appContext,
            channels = channels,
            notificationPort = notificationPort,
            overlayPort = overlayPort,
            usagePort = usagePort,
            clipboardPort = clipboardPort,
            consentAvailable = consentAvailable
        )
        return DshAndroidActions.create(
            notificationPort = notificationPort,
            overlayPort = overlayPort,
            usagePort = usagePort,
            clipboardPort = clipboardPort,
            settingsPort = settingsPort,
            channels = channels,
            consent = consent,
            runner = RootProcessShellRunner(shizuku = shizukuShellTransport),
            environmentProvider = environmentProbe::snapshot,
            shellConfig = shellConfig
        )
    }
}

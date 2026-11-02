package com.muyuchat.core.dsh.android

import android.Manifest
import android.content.Context
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState

/**
 * Builds one [DshAndroidEnvironment] snapshot from the real platform state.
 *
 * The probe composes the same port instances the routes use, so the projection can never disagree
 * with the adapter: the availability of an action and the refusal the action produces on the wire
 * come from the same check. `consentAvailable` is the composition's answer to "is a consent port
 * wired"; the projection refuses PRIVILEGED_SHELL when it is false.
 */
class AndroidDshEnvironmentProbe(
    context: Context,
    private val channels: DshPrivilegedChannelProvider,
    private val notificationPort: DshNotificationPort,
    private val overlayPort: DshOverlayPort,
    private val usagePort: DshUsageStatsPort,
    private val clipboardPort: DshClipboardPort,
    private val settingsWriteState: () -> DshCapabilityState = { defaultSettingsWriteState(context) },
    private val consentAvailable: Boolean = true
) {

    fun snapshot(): DshAndroidEnvironment = DshAndroidEnvironment(
        notificationPost = notificationPort.state(),
        overlay = overlayPort.state(),
        usageAccess = usagePort.access(),
        settingsWrite = settingsWriteState(),
        clipboardRead = clipboardPort.state(),
        clipboardWrite = clipboardPort.state(),
        rootShell = channelState(channels.rootAvailable()),
        shizukuShell = channelState(channels.shizukuAvailable()),
        consentAvailable = consentAvailable
    )

    private fun channelState(available: Boolean): DshCapabilityState =
        if (available) DshCapabilityState.GRANTED else DshCapabilityState.DENIED

    private companion object {
        fun defaultSettingsWriteState(context: Context): DshCapabilityState {
            val appContext = context.applicationContext
            if (!AndroidDshPermissionChecks.declares(appContext, Manifest.permission.WRITE_SETTINGS)) {
                return DshCapabilityState.NOT_DECLARED
            }
            return if (android.provider.Settings.System.canWrite(appContext)) {
                DshCapabilityState.GRANTED
            } else {
                DshCapabilityState.DENIED
            }
        }
    }
}

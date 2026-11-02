package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentPort
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.core.dsh.contract.capability.DshCapabilityStatus

/**
 * Registration entry of the App-local Android routes.
 *
 * ## What it registers
 *
 * `POST /notify`, `GET /overlay`, `GET /usage`, `POST /clipboard`, `POST /setting`,
 * `POST /shell` — every path the two pinned official packages actually call on
 * `127.0.0.1:$APP_NOTIFY_PORT` (`dsh-tool-android` and `dsh-tool-shizuku`). The shizuku package
 * sends `/shell` with its token only in the JSON body; `DshCapabilityServerCore` accepts that
 * body token for this one route/method while the header remains the primary transport for every
 * other request. The token is never echoed or logged.
 *
 * ## Wiring (app composition root)
 *
 * ```kotlin
 * val androidActions = DshAndroidActions.create(
 *     notificationPort = AndroidDshNotificationPort(context, smallIconResId = R.drawable.ic_notify),
 *     overlayPort = AndroidDshOverlayPort(context, overlayController),
 *     usagePort = AndroidDshUsageStatsPort(context),
 *     clipboardPort = AndroidDshClipboardPort(context),
 *     settingsPort = AndroidDshSettingsPort(context),
 *     channels = RootAndShizukuChannelProvider(rootProbe, shizukuBridge),
 *     consent = DshCapabilityConsentPort { prompt -> consentUi.request(prompt) },
 *     runner = RootProcessShellRunner(),
 *     environmentProvider = { AndroidDshEnvironmentProbe(context, channels).snapshot() }
 * )
 * val routeTable = DshCapabilityRouteTable.builder()
 *     .also(androidActions::registerInto)
 *     .build()
 * ```
 *
 * The platform server (`:core:dsh-platform`) owns the token check, the generation fence and the
 * idempotency ledger; it calls [registerInto] once per app composition. The environment variables
 * the official packages need (`APP_NOTIFY_PORT`, `APP_LOCAL_TOKEN`, `ROOT_AVAILABLE`,
 * `SHIZUKU_AVAILABLE`, `SHIZUKU_APP_ID`, `SHIZUKU_DEX`, `SHIZUKU_APPROVE`) come from
 * `DshCapabilityBridgeEndpoint.environment()`; this module neither mints nor reads them.
 *
 * ## Ownership and release
 *
 * One instance per application process. The adapters are stateless apart from Android system
 * services; the privileged runner owns at most one live process at a time and terminates it on
 * timeout/cancel. Expose [suspendCancelAllNotifications] to the runtime owner so a stopped
 * generation clears its posted notifications.
 */
class DshAndroidActions(
    val notification: DshNotificationAction,
    val overlay: DshOverlayAction,
    val usage: DshUsageAction,
    val clipboard: DshClipboardAction,
    val setting: DshSettingAction,
    val shell: DshShellAction,
    private val environmentProvider: () -> DshAndroidEnvironment
) {
    init {
        val paths = routes().map { it.path }
        require(paths.distinct().size == paths.size) {
            "Android action routes must not share a path"
        }
    }

    fun routes(): List<DshAndroidRoute> =
        listOf(notification, overlay, usage, clipboard, setting, shell)

    /** Adds every owned path to the platform route table; a duplicate path fails loudly there. */
    fun registerInto(builder: DshCapabilityRouteTable.Builder): DshCapabilityRouteTable.Builder {
        routes().forEach { route -> builder.route(route.path, route) }
        return builder
    }

    /** Current environment snapshot; re-read after the user returns from a system settings screen. */
    fun environment(): DshAndroidEnvironment = environmentProvider()

    /** Honest tool-roster input: DSH may register a family only when it is usable. */
    fun capabilityStatuses(): List<DshCapabilityStatus> = environmentProvider().project()

    fun usableCapabilities(): Set<DshCapability> = environmentProvider().usableCapabilities()

    /** Lifecycle half of `POST /notify`: clear posted notices when the runtime generation stops. */
    suspend fun suspendCancelAllNotifications(): Int = notification.cancelAll()

    companion object {
        const val PATH_NOTIFY: String = DshNotificationAction.ROUTE
        const val PATH_OVERLAY: String = DshOverlayAction.ROUTE
        const val PATH_USAGE: String = DshUsageAction.ROUTE
        const val PATH_CLIPBOARD: String = DshClipboardAction.ROUTE
        const val PATH_SETTING: String = DshSettingAction.ROUTE
        const val PATH_SHELL: String = DshShellAction.ROUTE

        val PATHS: List<String> = listOf(
            PATH_NOTIFY, PATH_OVERLAY, PATH_USAGE, PATH_CLIPBOARD, PATH_SETTING, PATH_SHELL
        )

        /** Builds the six routes from typed ports. No Android type is required here. */
        fun create(
            notificationPort: DshNotificationPort,
            overlayPort: DshOverlayPort,
            usagePort: DshUsageStatsPort,
            clipboardPort: DshClipboardPort,
            settingsPort: DshAppSettingsPort,
            channels: DshPrivilegedChannelProvider,
            consent: DshCapabilityConsentPort,
            runner: DshPrivilegedShellRunner,
            environmentProvider: () -> DshAndroidEnvironment,
            shellConfig: DshShellActionConfig = DshShellActionConfig()
        ): DshAndroidActions = DshAndroidActions(
            notification = DshNotificationAction(notificationPort),
            overlay = DshOverlayAction(overlayPort),
            usage = DshUsageAction(usagePort),
            clipboard = DshClipboardAction(clipboardPort),
            setting = DshSettingAction(settingsPort),
            shell = DshShellAction(channels, consent, runner, shellConfig),
            environmentProvider = environmentProvider
        )
    }
}

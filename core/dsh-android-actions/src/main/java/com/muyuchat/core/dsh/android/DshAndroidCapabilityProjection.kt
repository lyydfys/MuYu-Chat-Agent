package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import com.muyuchat.core.dsh.contract.capability.DshCapabilityStatus

/**
 * Prompt-free snapshot of every platform grant the six App-local Android routes depend on.
 *
 * The snapshot is the only input of the availability projection, so the mapping is pure: an
 * adapter probes the platform, the projection decides whether DSH may register a tool, and no
 * route ever guesses a capability from a stale flag. Every `*` field is already a
 * [DshCapabilityState], so the adapter owns the `NOT_DECLARED` vs `DENIED` vs `NOT_ENABLED`
 * distinction (manifest declaration, runtime grant, service switch) instead of the policy.
 */
data class DshAndroidEnvironment(
    val notificationPost: DshCapabilityState = DshCapabilityState.DENIED,
    val overlay: DshCapabilityState = DshCapabilityState.DENIED,
    val usageAccess: DshCapabilityState = DshCapabilityState.DENIED,
    val settingsWrite: DshCapabilityState = DshCapabilityState.DENIED,
    val clipboardRead: DshCapabilityState = DshCapabilityState.DENIED,
    val clipboardWrite: DshCapabilityState = DshCapabilityState.DENIED,
    val rootShell: DshCapabilityState = DshCapabilityState.DENIED,
    val shizukuShell: DshCapabilityState = DshCapabilityState.DENIED,
    /** False when the app composition has no [com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentPort]. */
    val consentAvailable: Boolean = true
) {

    /** The transport the `/shell` route will use, or null when no real channel is granted. */
    fun privilegedChannel(): DshPrivilegedChannel? = when {
        rootShell == DshCapabilityState.GRANTED -> DshPrivilegedChannel.ROOT
        shizukuShell == DshCapabilityState.GRANTED -> DshPrivilegedChannel.SHIZUKU
        else -> null
    }

    /**
     * One status per capability this lane serves: NOTIFICATION (the notification route),
     * ANDROID_SYSTEM (overlay/usage/setting/clipboard) and PRIVILEGED_SHELL (`/shell`).
     *
     * ANDROID_SYSTEM is a partial capability: DSH may register the family when at least one action
     * works, and the detail names the degraded actions so the tool roster can be explained.
     */
    fun project(): List<DshCapabilityStatus> = listOf(
        notificationStatus(),
        androidSystemStatus(),
        privilegedShellStatus()
    )

    fun statusOf(capability: DshCapability): DshCapabilityStatus? =
        project().firstOrNull { it.capability == capability }

    /** Exactly the capabilities DSH may register a tool for right now. */
    fun usableCapabilities(): Set<DshCapability> =
        project().filter { it.isUsable }.map { it.capability }.toSet()

    private fun notificationStatus(): DshCapabilityStatus = DshCapabilityStatus(
        capability = DshCapability.NOTIFICATION,
        state = notificationPost,
        detail = describe("通知权限", notificationPost)
    )

    private fun androidSystemStatus(): DshCapabilityStatus {
        val components = linkedMapOf(
            "overlay" to overlay,
            "usage" to usageAccess,
            "setting" to settingsWrite,
            "clipboard" to clipboardState()
        )
        val grantedComponents = components.filterValues { it == DshCapabilityState.GRANTED }
        val state = when {
            grantedComponents.isNotEmpty() -> DshCapabilityState.GRANTED
            components.values.all { it == DshCapabilityState.UNSUPPORTED } -> DshCapabilityState.UNSUPPORTED
            components.values.any { it == DshCapabilityState.NOT_DECLARED } -> DshCapabilityState.NOT_DECLARED
            components.values.all { it == DshCapabilityState.NOT_ENABLED } -> DshCapabilityState.NOT_ENABLED
            else -> DshCapabilityState.DENIED
        }
        val detail = if (state == DshCapabilityState.GRANTED) {
            components.filterValues { it != DshCapabilityState.GRANTED }
                .entries
                .joinToString(" ") { (name, componentState) -> "$name=$componentState" }
                .ifEmpty { null }
        } else {
            describe("Android 系统能力", state)
        }
        return DshCapabilityStatus(
            capability = DshCapability.ANDROID_SYSTEM,
            state = state,
            detail = detail?.take(DshAndroidActionLimits.MAX_STATUS_DETAIL_CHARS)
        )
    }

    private fun clipboardState(): DshCapabilityState = when {
        clipboardRead == DshCapabilityState.GRANTED || clipboardWrite == DshCapabilityState.GRANTED ->
            DshCapabilityState.GRANTED
        clipboardRead == DshCapabilityState.UNSUPPORTED && clipboardWrite == DshCapabilityState.UNSUPPORTED ->
            DshCapabilityState.UNSUPPORTED
        clipboardRead == DshCapabilityState.NOT_DECLARED || clipboardWrite == DshCapabilityState.NOT_DECLARED ->
            DshCapabilityState.NOT_DECLARED
        clipboardRead == DshCapabilityState.NOT_ENABLED || clipboardWrite == DshCapabilityState.NOT_ENABLED ->
            DshCapabilityState.NOT_ENABLED
        else -> DshCapabilityState.DENIED
    }

    private fun privilegedShellStatus(): DshCapabilityStatus {
        val channel = privilegedChannel()
        val bothUnsupported = rootShell == DshCapabilityState.UNSUPPORTED &&
            shizukuShell == DshCapabilityState.UNSUPPORTED
        val state = when {
            channel != null && consentAvailable -> DshCapabilityState.GRANTED
            channel != null -> DshCapabilityState.DENIED
            bothUnsupported -> DshCapabilityState.UNSUPPORTED
            else -> DshCapabilityState.DENIED
        }
        val detail = when {
            channel != null && consentAvailable -> channel.displayName
            channel != null -> "特权通道可用但审批端口（DshCapabilityConsentPort）未接入"
            bothUnsupported -> "当前构建不支持 root/Shizuku 特权通道"
            else -> "未授予 root 或 Shizuku 特权通道"
        }
        return DshCapabilityStatus(
            capability = DshCapability.PRIVILEGED_SHELL,
            state = state,
            detail = detail
        )
    }

    private fun describe(label: String, state: DshCapabilityState): String? = when (state) {
        DshCapabilityState.GRANTED -> null
        DshCapabilityState.DENIED -> "$label 未授权"
        DshCapabilityState.NOT_DECLARED -> "$label 未在清单中声明"
        DshCapabilityState.NOT_ENABLED -> "$label 服务未启用"
        DshCapabilityState.UNSUPPORTED -> "$label 不受当前系统支持"
    }
}

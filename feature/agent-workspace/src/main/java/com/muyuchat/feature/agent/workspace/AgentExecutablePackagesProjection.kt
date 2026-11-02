package com.muyuchat.feature.agent.workspace

import java.util.Locale

/** Projected executable-package row. Display strings are bounded here, never in the composable. */
data class AgentExecutablePackageUiItem(
    val packageId: String,
    val displayName: String,
    val versionLabel: String,
    /** 已开启 / 已停用 / 已撤销 / 校验失败 */
    val statusLabel: String,
    val problemLabel: String?,
    val enabled: Boolean,
    val revoked: Boolean,
    val rollbackAvailable: Boolean,
    val contentVerified: Boolean,
    val toolCountLabel: String,
    val busy: Boolean
) {
    val rowKey: String get() = packageId

    val canEnable: Boolean get() = !busy && !enabled && !revoked && contentVerified
    val canDisable: Boolean get() = !busy && enabled
    val canRollback: Boolean get() = !busy && rollbackAvailable && contentVerified
    val canRevoke: Boolean get() = !busy && !revoked
    val canClearRevocation: Boolean get() = !busy && revoked
    val canUninstall: Boolean get() = !busy
}

/** Pending destructive action shown as one explicit confirmation dialog. */
data class AgentExecutablePackageConfirmation(
    val packageId: String,
    val displayName: String,
    val action: AgentExecutablePackageAction,
    val title: String,
    val body: String,
    val confirmLabel: String
)

/**
 * Executable-package block state.
 *
 * [supported] is false when the port answers `TOOL_PACKAGE_EXECUTABLE_NOT_SUPPORTED` (host not
 * wired): the block then shows an inert explanation and never fabricates a package. [busyPackageId]
 * disables every row action while one lifecycle call is in flight, so two contradictory actions can
 * never be requested for the same page state.
 */
data class AgentExecutablePackagesUiState(
    val items: List<AgentExecutablePackageUiItem> = emptyList(),
    val loading: Boolean = false,
    val installing: Boolean = false,
    val busyPackageId: String? = null,
    val supported: Boolean = true,
    val error: AgentToolPackageErrorUi? = null,
    val notice: String? = null,
    val confirmation: AgentExecutablePackageConfirmation? = null
) {
    val empty: Boolean get() = !loading && items.isEmpty()

    val busy: Boolean get() = loading || installing || busyPackageId != null

    val canInstall: Boolean get() = supported && !busy

    val countLabel: String
        get() = String.format(Locale.ROOT, "%d 个可执行包", items.size)

    val enabledCount: Int get() = items.count { it.enabled }
}

internal data class AgentExecutablePackagesProjectionInput(
    val packages: List<AgentExecutablePackageItem> = emptyList(),
    val loading: Boolean = false,
    val installing: Boolean = false,
    val busyPackageId: String? = null,
    val supported: Boolean = true,
    val error: AgentToolPackageErrorUi? = null,
    val notice: String? = null,
    val confirmation: AgentExecutablePackageConfirmation? = null
)

internal fun projectExecutablePackagesState(
    input: AgentExecutablePackagesProjectionInput
): AgentExecutablePackagesUiState = AgentExecutablePackagesUiState(
    items = input.packages
        .map { projectExecutablePackageItem(it, input.busyPackageId) }
        .sortedWith(compareBy({ it.displayName.lowercase(Locale.ROOT) }, { it.packageId })),
    loading = input.loading,
    installing = input.installing,
    busyPackageId = input.busyPackageId,
    supported = input.supported,
    error = input.error,
    notice = input.notice,
    confirmation = input.confirmation
)

internal fun projectExecutablePackageItem(
    item: AgentExecutablePackageItem,
    busyPackageId: String? = null
): AgentExecutablePackageUiItem {
    val statusLabel = when {
        item.revoked -> "已撤销"
        !item.contentVerified -> "校验失败"
        item.enabled -> "已开启"
        else -> "已停用"
    }
    return AgentExecutablePackageUiItem(
        packageId = item.packageId.take(AgentToolPackageLimits.MAX_VERSION_CHARS + 8),
        displayName = item.displayName.take(AgentToolPackageLimits.MAX_DISPLAY_NAME_CHARS),
        versionLabel = item.version.take(AgentToolPackageLimits.MAX_VERSION_CHARS),
        statusLabel = statusLabel,
        problemLabel = item.problemCode
            ?.takeIf { !item.contentVerified }
            ?.take(AgentToolPackageLimits.MAX_SIDE_EFFECT_CHARS)
            ?.let { "校验问题：$it" },
        enabled = item.enabled && item.contentVerified && !item.revoked,
        revoked = item.revoked,
        rollbackAvailable = item.rollbackAvailable,
        contentVerified = item.contentVerified,
        toolCountLabel = toolPackageToolCountLabel(item.toolCount),
        busy = busyPackageId != null && busyPackageId == item.packageId
    )
}

/** Confirmation copy for one lifecycle action; returns null for actions that need no confirmation. */
internal fun executablePackageConfirmation(
    packageId: String,
    displayName: String,
    action: AgentExecutablePackageAction
): AgentExecutablePackageConfirmation? = when (action) {
    AgentExecutablePackageAction.ENABLE -> AgentExecutablePackageConfirmation(
        packageId = packageId,
        displayName = displayName,
        action = action,
        title = "启用工具包？",
        body = "启用后，该工具包的工具可在下一次运行中使用；需要写权限的宿主操作仍会逐次询问，" +
            "声明写能力的包会被拒绝启用。",
        confirmLabel = "启用"
    )
    AgentExecutablePackageAction.DISABLE -> AgentExecutablePackageConfirmation(
        packageId = packageId,
        displayName = displayName,
        action = action,
        title = "停用工具包？",
        body = "停用后下一次运行不再提供该包工具；已安装内容会保留，可随时再次启用。",
        confirmLabel = "停用"
    )
    AgentExecutablePackageAction.ROLLBACK -> AgentExecutablePackageConfirmation(
        packageId = packageId,
        displayName = displayName,
        action = action,
        title = "回滚到上一版本？",
        body = "当前版本会保留为新的回滚目标；正在运行的运行仍使用其启动时冻结的工具快照。",
        confirmLabel = "回滚"
    )
    AgentExecutablePackageAction.REVOKE -> AgentExecutablePackageConfirmation(
        packageId = packageId,
        displayName = displayName,
        action = action,
        title = "撤销工具包？",
        body = "撤销会立即停用该包并阻止再次启用，直到手动解除撤销；本地内容不会删除。",
        confirmLabel = "撤销"
    )
    AgentExecutablePackageAction.UNINSTALL -> AgentExecutablePackageConfirmation(
        packageId = packageId,
        displayName = displayName,
        action = action,
        title = "卸载工具包？",
        body = "卸载会先停用再删除该包的本地记录与内容（可重新安装）。此操作不会回滚已发生的工具副作用。",
        confirmLabel = "卸载"
    )
    AgentExecutablePackageAction.CLEAR_REVOCATION -> null
}

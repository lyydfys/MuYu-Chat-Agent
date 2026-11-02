package com.muyuchat.feature.agent.workspace

/**
 * Executable (format v2) tool-package settings surface.
 *
 * This is a separate model set from the read-only v1 preview surface in
 * `AgentToolPackageContracts.kt`: an executable package can be installed, verified, enabled,
 * disabled, rolled back, revoked and uninstalled, so an `enabled`/`revoked` state is real here. It is
 * still **not** a tool executor handle: the page only sees status and can only request typed
 * lifecycle actions through [AgentExecutableToolPackagePort].
 */
data class AgentExecutablePackageItem(
    val packageId: String,
    val displayName: String,
    val version: String,
    /** True only when the backend reports the package as active for future runs. */
    val enabled: Boolean,
    /** A revoked package is inactive and cannot be enabled until the revocation is cleared. */
    val revoked: Boolean,
    /** A previous version is stored and can be restored. */
    val rollbackAvailable: Boolean,
    val toolCount: Int,
    /** False when the stored bytes no longer match the recorded content hash. */
    val contentVerified: Boolean,
    /** Typed verification problem code (for example `TOOLPACK_CONTENT_HASH_MISMATCH`). */
    val problemCode: String? = null
) {
    init {
        require(packageId.isNotBlank()) { "executable packageId must not be blank" }
        require(displayName.isNotBlank()) { "executable displayName must not be blank" }
        require(version.isNotBlank()) { "executable version must not be blank" }
        require(toolCount >= 0) { "executable toolCount must not be negative" }
        require(problemCode == null || problemCode.isNotBlank()) {
            "executable problemCode must be null or non-blank"
        }
    }
}

sealed interface AgentExecutablePackageListResult {
    data class Loaded(val packages: List<AgentExecutablePackageItem>) : AgentExecutablePackageListResult

    data class Rejected(val code: String, val safeMessage: String) : AgentExecutablePackageListResult {
        init {
            require(code.isNotBlank()) { "executable list rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "executable list rejection message must not be blank" }
        }
    }
}

sealed interface AgentExecutablePackageActionResult {
    data class Applied(
        val packageId: String?,
        val notice: String
    ) : AgentExecutablePackageActionResult {
        init {
            require(notice.isNotBlank()) { "executable action notice must not be blank" }
        }
    }

    data class Rejected(val code: String, val safeMessage: String) : AgentExecutablePackageActionResult {
        init {
            require(code.isNotBlank()) { "executable action rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "executable action rejection message must not be blank" }
        }
    }
}

/** Every user-visible lifecycle action of one executable package. */
enum class AgentExecutablePackageAction {
    ENABLE,
    DISABLE,
    ROLLBACK,
    REVOKE,
    CLEAR_REVOCATION,
    UNINSTALL
}

/** Stable typed defaults for a host that has not wired the executable lifecycle backend yet. */
object AgentExecutablePackageSupport {
    const val NOT_SUPPORTED_CODE: String = "TOOL_PACKAGE_EXECUTABLE_NOT_SUPPORTED"
    const val NOT_SUPPORTED_MESSAGE: String = "可执行工具包管理尚未接入。"

    const val LIST_FAILED_CODE: String = "TOOL_PACKAGE_EXECUTABLE_LIST_FAILED"
    const val LIST_FAILED_MESSAGE: String = "可执行工具包列表暂时无法载入，请稍后重试。"

    const val ACTION_FAILED_CODE: String = "TOOL_PACKAGE_EXECUTABLE_ACTION_FAILED"
    const val ACTION_FAILED_MESSAGE: String = "可执行工具包操作失败，未显示具体原因。"

    const val INSTALL_UNAVAILABLE_CODE: String = "TOOL_PACKAGE_EXECUTABLE_INSTALL_UNAVAILABLE"
    const val INSTALL_UNAVAILABLE_MESSAGE: String = "当前无法读取所选 ZIP，可执行工具包未安装。"
}

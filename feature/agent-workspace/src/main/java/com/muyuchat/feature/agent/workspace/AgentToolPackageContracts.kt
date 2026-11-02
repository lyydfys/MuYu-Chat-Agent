package com.muyuchat.feature.agent.workspace

/**
 * Feature-local tool-package administration port.
 *
 * The surface is read-only: [AgentToolPackageAdminPort.list] shows installed preview snapshots and
 * [AgentToolPackageAdminPort.preview] validates a user-picked ZIP into the same private preview
 * store. Neither [AgentToolPackageItem] nor [AgentToolPackagePreviewItem] has an `enabled` field or
 * an executor handle, so a fake "active package" state is unrepresentable rather than merely
 * disabled.
 *
 * The approval/invocation closure is implemented, so this read-only preview surface may be shown.
 * Exposing a package tool to a run catalog is a separate explicit-enable decision that stays off
 * ([AgentToolPackagePolicy.PACKAGE_CATALOG_EXPOSURE_ENABLED]); no package tool may enter a run
 * catalog before that decision is made through the P1 catalog/approval wiring.
 */

object AgentToolPackagePolicy {
    /**
     * The P0 approval/invocation closure is implemented (see `AgentToolApprovalClosureTest`). This
     * is a code-owned fact, not a remote/user preference; it only opens the read-only preview.
     */
    const val APPROVAL_CLOSURE_COMPLETE: Boolean = true

    /**
     * Catalog exposure (a package tool becoming a run-catalog entry) is NOT enabled. Flipping this
     * requires the P1 catalog/approval wiring and an explicit user enable; the preview UI must not
     * fake it with a switch.
     */
    const val PACKAGE_CATALOG_EXPOSURE_ENABLED: Boolean = false

    /** User-facing copy scoping the v1 read-only preview against the v2 per-package enable path. */
    const val PACKAGE_CATALOG_EXPOSURE_REQUIRED_COPY: String =
        "只读预览不会加入运行目录；可执行包需逐包启用"
}

object AgentToolPackageLimits {
    const val MAX_DISPLAY_NAME_CHARS: Int = 128
    const val MAX_VERSION_CHARS: Int = 64
    const val MAX_SIDE_EFFECT_CHARS: Int = 160
    const val MAX_SOURCE_LABEL_CHARS: Int = 160
    const val MAX_TOOLS: Int = 32
    const val MAX_EXPOSED_PARAMETERS: Int = 32
}

/**
 * One installed package manifest as a settings row needs it.
 *
 * There is deliberately no `enabled`/`active` field and no executable handle: a package is a
 * declarative binding, and the page may not claim it is active while catalog exposure is closed.
 */
data class AgentToolPackageItem(
    val packageId: String,
    val displayName: String,
    val version: String,
    val toolCount: Int,
    val sideEffectSummary: String,
    val sourceLabel: String? = null
) {
    init {
        require(packageId.isNotBlank()) { "packageId must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(toolCount >= 0) { "toolCount must not be negative" }
        require(sideEffectSummary.isNotBlank()) { "sideEffectSummary must not be blank" }
    }
}

/** One declared package tool as previewed. Metadata only: no executor, schema or catalog entry. */
data class AgentToolPackagePreviewToolItem(
    val toolName: String,
    val displayName: String,
    val hostOperation: String,
    val sideEffectLabel: String,
    val dataClassLabel: String,
    val exposedParameters: List<String>,
    val timeoutMillis: Long
) {
    init {
        require(toolName.isNotBlank()) { "toolName must not be blank" }
        require(displayName.isNotBlank()) { "tool displayName must not be blank" }
        require(hostOperation.isNotBlank()) { "hostOperation must not be blank" }
        require(sideEffectLabel.isNotBlank()) { "sideEffectLabel must not be blank" }
        require(dataClassLabel.isNotBlank()) { "dataClassLabel must not be blank" }
        require(exposedParameters.size <= AgentToolPackageLimits.MAX_EXPOSED_PARAMETERS) {
            "a previewed tool exposes too many parameters"
        }
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
    }
}

/**
 * Read-only preview of one stored package.
 *
 * [previewOnly] is a computed `true`: this model carries no executor, no enable flag and no catalog
 * registration hook. It is display data for the settings page, never an installation.
 */
data class AgentToolPackagePreviewItem(
    val packageId: String,
    val displayName: String,
    val version: String,
    val toolCount: Int,
    val sideEffectSummary: String,
    val sourceLabel: String,
    val tools: List<AgentToolPackagePreviewToolItem>,
    val resourceCount: Int,
    val totalExpandedBytes: Long
) {
    init {
        require(packageId.isNotBlank()) { "packageId must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(toolCount >= 0) { "toolCount must not be negative" }
        require(toolCount == tools.size) { "preview tool count must match the tool list" }
        require(tools.size <= AgentToolPackageLimits.MAX_TOOLS) { "preview has too many tools" }
        require(sideEffectSummary.isNotBlank()) { "sideEffectSummary must not be blank" }
        require(sourceLabel.isNotBlank()) { "sourceLabel must not be blank" }
        require(resourceCount >= 0) { "resourceCount must not be negative" }
        require(totalExpandedBytes >= 0L) { "totalExpandedBytes must not be negative" }
    }

    /** A preview can never be treated as an enabled/installed-for-run tool source. */
    val previewOnly: Boolean get() = true
}

sealed interface AgentToolPackageListResult {
    data class Loaded(val packages: List<AgentToolPackageItem>) : AgentToolPackageListResult

    data class Rejected(val code: String, val safeMessage: String) : AgentToolPackageListResult {
        init {
            require(code.isNotBlank()) { "list rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "list rejection message must not be blank" }
        }
    }
}

sealed interface AgentToolPackagePreviewResult {
    data class Previewed(val preview: AgentToolPackagePreviewItem) : AgentToolPackagePreviewResult

    data class Rejected(
        val code: String,
        val safeMessage: String,
        /**
         * Optional, already-safe lines describing what the archive itself declared (format, package,
         * tools, host APIs and their access, unsupported contexts). Empty for ordinary failures; the
         * page renders them under the message so a package can be compared with what this runtime
         * actually supports before anything is installed.
         */
        val details: List<String> = emptyList()
    ) : AgentToolPackagePreviewResult {
        init {
            require(code.isNotBlank()) { "preview rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "preview rejection message must not be blank" }
            require(details.none { it.isBlank() }) { "preview detail lines must not be blank" }
            require(details.all { detail -> detail.none { it.isISOControl() } }) {
                "preview detail lines must not contain control characters"
            }
        }
    }
}

/**
 * Read-only query surface for the v1 preview store, plus the executable (format v2) lifecycle surface.
 *
 * The preview methods are unchanged: listing and validating a private preview cannot make a package
 * runnable. The executable methods were added by R6 with **default NOT_SUPPORTED implementations**,
 * so every existing port/fake keeps compiling and a host that has not wired
 * `ToolPackageLifecycleService` degrades to a typed "not supported" answer instead of pretending an
 * activation happened. The interface still exposes no executor handle and no enable flag on the v1
 * models; executable activation state lives only in [AgentExecutablePackageItem].
 */
interface AgentToolPackageAdminPort {
    suspend fun list(): AgentToolPackageListResult

    /** Validates [source] into the private preview store; never installs, enables or executes it. */
    suspend fun preview(source: AgentDocumentRef): AgentToolPackagePreviewResult

    // --- Executable (format v2) lifecycle: default NOT_SUPPORTED -----------------------------

    /** Installed executable packages with their activation state. */
    suspend fun listExecutable(): AgentExecutablePackageListResult = executableNotSupported()

    /** Copies and installs the selected ZIP as an executable package (still disabled afterwards). */
    suspend fun installExecutable(source: AgentDocumentRef): AgentExecutablePackageActionResult =
        executableNotSupportedAction()

    suspend fun enableExecutable(packageId: String): AgentExecutablePackageActionResult =
        executableNotSupportedAction()

    suspend fun disableExecutable(packageId: String): AgentExecutablePackageActionResult =
        executableNotSupportedAction()

    /** Swaps the active content with the stored previous version. */
    suspend fun rollbackExecutable(packageId: String): AgentExecutablePackageActionResult =
        executableNotSupportedAction()

    /** Disables the package and blocks re-enable until [clearExecutableRevocation]. */
    suspend fun revokeExecutable(packageId: String): AgentExecutablePackageActionResult =
        executableNotSupportedAction()

    suspend fun clearExecutableRevocation(packageId: String): AgentExecutablePackageActionResult =
        executableNotSupportedAction()

    /** Deactivates, removes the recorded package and purges its content when unreferenced. */
    suspend fun uninstallExecutable(packageId: String): AgentExecutablePackageActionResult =
        executableNotSupportedAction()

    /** Reference GC; never removes content of an enabled/rollback-referenced package. */
    suspend fun collectExecutableGarbage(): AgentExecutablePackageActionResult =
        executableNotSupportedAction()

    companion object {
        /** Preview/test fallback only; production wiring must provide a real port. */
        val Unavailable: AgentToolPackageAdminPort = object : AgentToolPackageAdminPort {
            override suspend fun list(): AgentToolPackageListResult =
                AgentToolPackageListResult.Rejected(
                    code = "TOOL_PACKAGE_ADMIN_UNAVAILABLE",
                    safeMessage = "工具包管理尚未接入。"
                )

            override suspend fun preview(
                source: AgentDocumentRef
            ): AgentToolPackagePreviewResult = AgentToolPackagePreviewResult.Rejected(
                code = "TOOL_PACKAGE_ADMIN_UNAVAILABLE",
                safeMessage = "工具包管理尚未接入。"
            )
        }

        private fun executableNotSupported(): AgentExecutablePackageListResult =
            AgentExecutablePackageListResult.Rejected(
                code = AgentExecutablePackageSupport.NOT_SUPPORTED_CODE,
                safeMessage = AgentExecutablePackageSupport.NOT_SUPPORTED_MESSAGE
            )

        private fun executableNotSupportedAction(): AgentExecutablePackageActionResult =
            AgentExecutablePackageActionResult.Rejected(
                code = AgentExecutablePackageSupport.NOT_SUPPORTED_CODE,
                safeMessage = AgentExecutablePackageSupport.NOT_SUPPORTED_MESSAGE
            )
    }
}

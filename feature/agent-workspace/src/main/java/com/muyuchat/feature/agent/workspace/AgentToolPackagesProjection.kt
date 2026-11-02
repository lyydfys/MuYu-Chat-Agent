package com.muyuchat.feature.agent.workspace

import java.util.Locale

/** Typed, already-safe package failure; no raw exception or provider body is carried. */
data class AgentToolPackageErrorUi(
    val code: String,
    val message: String,
    /** Already-safe lines describing what the archive declared; rendered under the message. */
    val details: List<String> = emptyList()
)

/** Projected package row. Display strings are bounded in [projectToolPackageItem]. */
data class AgentToolPackageUiItem(
    val packageId: String,
    val displayName: String,
    val versionLabel: String,
    val toolCount: Int,
    val toolCountLabel: String,
    val sideEffectLabel: String,
    val sourceLabel: String?
) {
    /** Stable row identifier for the host that maps a package to its tools; never an executor. */
    val rowKey: String get() = packageId
}

/** Projected preview tool row. Every field is already display-bounded metadata. */
data class AgentToolPackagePreviewToolUi(
    val toolName: String,
    val displayName: String,
    val hostOperation: String,
    val sideEffectLabel: String,
    val dataClassLabel: String,
    val parametersLabel: String,
    val timeoutLabel: String
)

/** Projected read-only preview. It carries no enable flag and no executable handle. */
data class AgentToolPackagePreviewUi(
    val packageId: String,
    val displayName: String,
    val versionLabel: String,
    val toolCountLabel: String,
    val sideEffectLabel: String,
    val sourceLabel: String,
    val resourceLabel: String,
    val sizeLabel: String,
    val tools: List<AgentToolPackagePreviewToolUi>
) {
    /** The page may only ever present this block as a preview. */
    val previewOnly: Boolean get() = true
}

/**
 * Tool-package surface state.
 *
 * [previewing] tracks a read-only ZIP validation; [preview] holds its result. There is no enable
 * switch and no `enabled` row field: catalog exposure stays closed
 * ([AgentToolPackagePolicy.PACKAGE_CATALOG_EXPOSURE_ENABLED]) until it is explicitly enabled by the
 * P1 wiring. [empty] distinguishes "still loading" from "no previews installed".
 */
data class AgentToolPackagesUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val previewing: Boolean = false,
    val items: List<AgentToolPackageUiItem> = emptyList(),
    val preview: AgentToolPackagePreviewUi? = null,
    val error: AgentToolPackageErrorUi? = null,
    val notice: String? = null,
    /**
     * Executable (format v2) block. Kept as a separate sub-state: the v1 preview list stays exactly
     * as it was, and there is still no global package enable switch on this page state.
     */
    val executable: AgentExecutablePackagesUiState = AgentExecutablePackagesUiState()
) {
    val empty: Boolean get() = !loading && items.isEmpty()

    val totalToolCount: Int get() = items.sumOf { it.toolCount }

    val canRefresh: Boolean get() = !loading && !previewing

    val previewEnabled: Boolean get() = AgentToolPackagePolicy.APPROVAL_CLOSURE_COMPLETE && !previewing

    /** Import into the run catalog stays closed until catalog exposure is explicitly enabled. */
    val importEnabled: Boolean get() = AgentToolPackagePolicy.PACKAGE_CATALOG_EXPOSURE_ENABLED

    /** Package tools are still absent from every run catalog; the page must not imply otherwise. */
    val catalogExposureEnabled: Boolean get() = AgentToolPackagePolicy.PACKAGE_CATALOG_EXPOSURE_ENABLED

    val gateMessage: String get() = AgentToolPackagePolicy.PACKAGE_CATALOG_EXPOSURE_REQUIRED_COPY
}

internal data class AgentToolPackagesProjectionInput(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val previewing: Boolean = false,
    val packages: List<AgentToolPackageItem> = emptyList(),
    val preview: AgentToolPackagePreviewUi? = null,
    val error: AgentToolPackageErrorUi? = null,
    val notice: String? = null,
    val executable: AgentExecutablePackagesUiState = AgentExecutablePackagesUiState()
)

internal fun projectToolPackagesState(
    input: AgentToolPackagesProjectionInput
): AgentToolPackagesUiState = AgentToolPackagesUiState(
    visible = input.visible,
    loading = input.loading,
    previewing = input.previewing,
    items = input.packages.map(::projectToolPackageItem)
        .sortedWith(compareBy({ it.displayName.lowercase(Locale.ROOT) }, { it.packageId })),
    preview = input.preview,
    error = input.error,
    notice = input.notice,
    executable = input.executable
)

internal fun projectToolPackageItem(item: AgentToolPackageItem): AgentToolPackageUiItem =
    AgentToolPackageUiItem(
        packageId = item.packageId,
        displayName = item.displayName.take(AgentToolPackageLimits.MAX_DISPLAY_NAME_CHARS),
        versionLabel = item.version.take(AgentToolPackageLimits.MAX_VERSION_CHARS),
        toolCount = item.toolCount,
        toolCountLabel = toolPackageToolCountLabel(item.toolCount),
        sideEffectLabel = item.sideEffectSummary.take(AgentToolPackageLimits.MAX_SIDE_EFFECT_CHARS),
        sourceLabel = item.sourceLabel
            ?.takeIf { it.isNotBlank() }
            ?.take(AgentToolPackageLimits.MAX_SOURCE_LABEL_CHARS)
    )

internal fun projectToolPackagePreview(
    item: AgentToolPackagePreviewItem
): AgentToolPackagePreviewUi = AgentToolPackagePreviewUi(
    packageId = item.packageId,
    displayName = item.displayName.take(AgentToolPackageLimits.MAX_DISPLAY_NAME_CHARS),
    versionLabel = item.version.take(AgentToolPackageLimits.MAX_VERSION_CHARS),
    toolCountLabel = toolPackageToolCountLabel(item.toolCount),
    sideEffectLabel = item.sideEffectSummary.take(AgentToolPackageLimits.MAX_SIDE_EFFECT_CHARS),
    sourceLabel = item.sourceLabel.take(AgentToolPackageLimits.MAX_SOURCE_LABEL_CHARS),
    resourceLabel = "${item.resourceCount} 个资源",
    sizeLabel = formatBytes(item.totalExpandedBytes),
    tools = item.tools.map { tool ->
        AgentToolPackagePreviewToolUi(
            toolName = tool.toolName,
            displayName = tool.displayName.take(AgentToolPackageLimits.MAX_DISPLAY_NAME_CHARS),
            hostOperation = tool.hostOperation,
            sideEffectLabel = tool.sideEffectLabel.take(AgentToolPackageLimits.MAX_SIDE_EFFECT_CHARS),
            dataClassLabel = tool.dataClassLabel.take(AgentToolPackageLimits.MAX_SIDE_EFFECT_CHARS),
            parametersLabel = if (tool.exposedParameters.isEmpty()) {
                "无可调参数"
            } else {
                tool.exposedParameters.joinToString("、")
            },
            timeoutLabel = formatDurationShort(tool.timeoutMillis)
        )
    }
)

internal fun toolPackageToolCountLabel(count: Int): String = when (count) {
    0 -> "0 个工具"
    1 -> "1 个工具"
    else -> String.format(Locale.ROOT, "%d 个工具", count)
}

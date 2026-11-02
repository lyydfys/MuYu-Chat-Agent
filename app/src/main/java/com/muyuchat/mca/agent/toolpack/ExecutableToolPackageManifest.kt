package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolPackageToolRef
import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJson

/**
 * Immutable, validated representation of one executable `mca-tool-package.json` (format v2).
 *
 * The manifest is still **untrusted data**: it selects script entries and names host capabilities,
 * but it cannot define schemas the app has not validated against the tool-schema subset, cannot
 * widen a host operation's effects and cannot grant itself a capability. Every declared host API is
 * checked against the code-owned [ToolPackageHostApis] registry before this model is built.
 *
 * `entry` is the package's main registration script. Registration itself is a later slice (N12);
 * tools execute their own `entry`. [canonicalJson] is the strict canonical form used for the content
 * hash, so two imports with the same bytes always resolve to the same package identity.
 *
 * [dependencies] is the optional exact dependency lock (PKG-08/PKG-17/PKG-19). It participates in
 * [canonicalJson] only when non-empty, so a manifest written before the field existed keeps its
 * original bytes and hash.
 */
data class ExecutableToolPackageManifest(
    val formatVersion: Int,
    val packageId: String,
    val packageVersion: String,
    val hostApiVersion: Int,
    val displayName: String,
    val description: String,
    val entry: String,
    val hostApis: List<String>,
    val tools: List<ExecutableToolPackageTool>,
    val dependencies: List<ToolPackageDependency>,
    val canonicalJson: String,
    val manifestSha256: String
) {
    init {
        require(formatVersion == ToolPackageLimits.FORMAT_VERSION) {
            "executable tool package format version must be ${ToolPackageLimits.FORMAT_VERSION}"
        }
        require(AgentToolPackageToolRef.isValidPackageId(packageId)) {
            "parsed executable package id is invalid"
        }
        require(hostApiVersion == ToolPackageLimits.HOST_API_VERSION) {
            "parsed executable package host API version is unsupported"
        }
        require(entry == ToolPackageScriptPaths.normalize(entry)) {
            "parsed executable package entry is not a normalised script path"
        }
        require(hostApis.distinct().size == hostApis.size) {
            "parsed executable package repeats a host API"
        }
        require(hostApis.all { ToolPackageHostApis.find(it) != null }) {
            "parsed executable package declares an unregistered host API"
        }
        require(tools.size in 1..ToolPackageLimits.MAX_TOOLS) {
            "parsed executable package tool count is outside the bounded range"
        }
        require(tools.map { it.name }.distinct().size == tools.size) {
            "parsed executable package repeats a tool name"
        }
        require(dependencies.size <= ToolPackageLimits.MAX_DEPENDENCIES) {
            "parsed executable package dependency count is outside the bounded range"
        }
        require(dependencies.map { it.packageId }.distinct().size == dependencies.size) {
            "parsed executable package repeats a dependency package id"
        }
        require(dependencies.none { it.packageId == packageId }) {
            "parsed executable package must not depend on itself"
        }
        require(manifestSha256 == ToolPackageJson.sha256Hex(canonicalJson.toByteArray(Charsets.UTF_8))) {
            "parsed executable package manifest hash is stale"
        }
    }

    fun tool(name: String): ExecutableToolPackageTool? = tools.firstOrNull { it.name == name }

    /** Tools that may mutate host state, derived from the declared capabilities only. */
    val declaresWriteCapability: Boolean
        get() = hostApis.any { ToolPackageHostApis.require(it).access == ToolPackageHostAccess.WRITE }

    val declaresWorkspaceRead: Boolean
        get() = hostApis.any { it.startsWith(WORKSPACE_PREFIX) }

    fun runtimeToolKey(toolName: String): String = ToolPackageLimits.runtimeToolKey(packageId, toolName)

    private companion object {
        const val WORKSPACE_PREFIX = "workspace."
    }
}

/**
 * One tool exported by an executable package.
 *
 * [inputSchemaJson] is always canonical JSON validated by the app's tool-schema subset; the package
 * cannot publish an unsupported keyword, an unbounded schema or a remote `$ref`.
 */
data class ExecutableToolPackageTool(
    val name: String,
    val displayName: String,
    val description: String,
    val entry: String,
    val inputSchemaJson: String,
    val timeoutMillis: Long
) {
    init {
        require(AgentToolPackageToolRef.isValidToolName(name)) { "parsed package tool name is invalid" }
        require(entry == ToolPackageScriptPaths.normalize(entry)) {
            "parsed package tool entry is not a normalised script path"
        }
        require(inputSchemaJson.length >= 2) { "parsed package tool schema is empty" }
        require(timeoutMillis in ToolPackageLimits.MIN_TOOL_TIMEOUT_MILLIS..ToolPackageLimits.MAX_TOOL_TIMEOUT_MILLIS) {
            "parsed package tool timeout is outside the bounded range"
        }
    }
}

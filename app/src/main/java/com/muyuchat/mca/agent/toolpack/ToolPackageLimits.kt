package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolPackageToolRef

/**
 * Frozen bounds of the **executable** tool-package format (format v2).
 *
 * These are intentionally separate from [com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageLimits]:
 * the inert v1 preview format stays readable with its own budgets, while an executable package has
 * to declare a script entry and host API capabilities. Executable packages are still small,
 * offline-only bundles; nothing here allows a package to install dependencies or fetch content at
 * run time.
 */
object ToolPackageLimits {
    /** `mca-tool-package.json` `schemaVersion` of the executable format. */
    const val FORMAT_VERSION: Int = 2

    /** Host API generation understood by this runtime. */
    const val HOST_API_VERSION: Int = 1

    const val MAX_MANIFEST_BYTES: Long = 128L * 1024
    const val MAX_TOOLS: Int = 32
    const val MAX_HOST_APIS: Int = 16

    /** Exact dependency-lock entries a manifest may declare (PKG-08/PKG-17/PKG-19). */
    const val MAX_DEPENDENCIES: Int = 32

    /** Bound of the install-time dependency DFS; deeper graphs are rejected, not truncated. */
    const val MAX_DEPENDENCY_DEPTH: Int = 16

    /** Bound of nodes visited while resolving GC reachability through stored manifests. */
    const val MAX_DEPENDENCY_GRAPH_NODES: Int = 256

    const val MAX_SCRIPTS: Int = 128
    const val MAX_SCRIPT_BYTES: Long = 512L * 1024
    const val MAX_SCRIPTS_TOTAL_BYTES: Long = 2L * 1024 * 1024

    const val MAX_PATH_CHARS: Int = 512
    const val MAX_PATH_DEPTH: Int = 16
    const val MAX_SEGMENT_CHARS: Int = 255

    const val MAX_DISPLAY_NAME_CHARS: Int = 128
    const val MAX_TOOL_DESCRIPTION_BYTES: Int = 2 * 1024
    const val MAX_DESCRIPTION_BYTES: Int = 4 * 1024
    const val MAX_TOOL_SCHEMA_BYTES: Int = 32 * 1024
    const val MAX_VERSION_CHARS: Int = 64

    const val MIN_TOOL_TIMEOUT_MILLIS: Long = 1L
    const val DEFAULT_TOOL_TIMEOUT_MILLIS: Long = 30_000L
    const val MAX_TOOL_TIMEOUT_MILLIS: Long = 120_000L

    /** Execution budgets applied to every script run, even if a manifest asks for more. */
    const val MAX_TOOL_ARGUMENTS_BYTES: Int = 16 * 1024
    const val MAX_OUTPUT_BYTES: Int = 64 * 1024
    const val MAX_HOST_CALLS: Int = 64
    const val MAX_HOST_ARGUMENT_BYTES: Int = 8 * 1024
    const val MAX_HOST_RESULT_BYTES: Int = 32 * 1024
    const val MAX_HOST_TOTAL_RESULT_BYTES: Int = 128 * 1024

    /**
     * Upper bound of a guest-chosen stable host-call ordinal. Ordinals are correlation ids, not a
     * quota: the host call budget still limits how many distinct calls may execute.
     */
    const val MAX_STABLE_CALL_ORDINAL: Int = 1_000_000

    /**
     * Manifest keys that would embed a second execution, permission or network channel. They get a
     * dedicated error instead of the generic unknown-field error so their presence is never
     * mistaken for a harmless typo.
     */
    val FORBIDDEN_FIELDS: Set<String> = setOf(
        "network", "http", "https", "url", "uri", "socket", "fetch",
        "filesystem", "fs", "file", "files", "path",
        "shell", "bash", "command", "exec", "spawn", "process",
        "native", "jni", "dex", "jar", "so", "apk", "abi",
        "wasm", "webassembly", "runtime", "executor", "hook", "hooks",
        "permissions", "permission", "grants", "grant", "autoApprove",
        "ui", "provider", "credentials", "secrets", "mcp", "toolsProxy"
    )

    fun requirePackageId(raw: String, field: String): String {
        if (!AgentToolPackageToolRef.isValidPackageId(raw)) {
            toolPackageFailure(
                ToolPackageErrorCode.MANIFEST_INVALID_ID,
                "$field must be lowercase [a-z0-9][a-z0-9-]{1,63}"
            )
        }
        return raw
    }

    /**
     * Run-facing tool key. `AgentToolDescriptor` only accepts dotted lowercase segments made of
     * `[a-z0-9_]`, so a hyphenated package id (valid for the inert v1 format and for the contract's
     * reserved `pkg.<packageId>.<toolName>` identity) is mapped injectively: package ids may never
     * contain `_`, therefore `-` -> `_` cannot collide. The original id stays in the manifest and in
     * every host-call audit record.
     */
    fun runtimeToolKey(packageId: String, toolName: String): String =
        "pkg." + packageId.replace('-', '_') + "." + toolName
}

/**
 * Normalisation policy for every script/resource path inside an executable package.
 *
 * A package never receives a host path: this policy only decides whether a path that is *relative
 * to the immutable package snapshot* is well formed. Absolute paths, `..`, backslashes, drive
 * letters, control characters and non-JavaScript files are rejected.
 */
object ToolPackageScriptPaths {
    private val SEGMENT = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]*$")
    private const val SCRIPT_SUFFIX = ".js"

    /** Returns the normalised relative path, or `null` when the path is not a package script. */
    fun normalize(raw: String): String? {
        if (raw.isEmpty() || raw.length > ToolPackageLimits.MAX_PATH_CHARS) return null
        if (raw.any { it.isISOControl() }) return null
        if (raw.startsWith('/') || raw.startsWith('~') || raw.contains('\\')) return null
        if (raw.contains(':')) return null
        val segments = raw.split('/')
        if (segments.size > ToolPackageLimits.MAX_PATH_DEPTH) return null
        if (segments.any { segment ->
                segment.isEmpty() ||
                    segment == "." ||
                    segment == ".." ||
                    segment.length > ToolPackageLimits.MAX_SEGMENT_CHARS ||
                    !SEGMENT.matches(segment)
            }
        ) {
            return null
        }
        val fileName = segments.last()
        if (!fileName.endsWith(SCRIPT_SUFFIX) || fileName.length <= SCRIPT_SUFFIX.length) return null
        return segments.joinToString("/")
    }
}

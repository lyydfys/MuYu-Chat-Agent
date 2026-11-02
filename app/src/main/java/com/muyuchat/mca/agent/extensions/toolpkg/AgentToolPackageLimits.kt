package com.muyuchat.mca.agent.extensions.toolpkg

import com.muyuchat.core.agent.contract.AgentToolPackageToolRef

/**
 * Frozen P0-B limits and identifiers for manifest-first tool packages.
 *
 * A tool package is a curated, read-only tool surface plus text/metadata resources. It is much
 * smaller than a model or Skill package, so these limits are intentionally tighter. Every limit is
 * enforced before or during the private snapshot copy; a caller that bypasses the importer cannot
 * widen them through the store.
 */
object AgentToolPackageLimits {
    /** Root manifest name. Exactly one copy is allowed at the archive root. */
    const val MANIFEST_FILE_NAME: String = "mca-tool-package.json"

    /** Private store record written next to the immutable manifest snapshot. */
    const val RECORD_FILE_NAME: String = "record.json"

    const val SCHEMA_VERSION: Int = 1
    const val API_VERSION: Int = 1

    /** Whole archive bytes or a directory tree's declared bytes. */
    const val MAX_ARCHIVE_BYTES: Long = 16L * 1024 * 1024

    /** Sum of all expanded regular-file bytes (including the manifest). */
    const val MAX_EXPANDED_BYTES: Long = 32L * 1024 * 1024

    const val MAX_ENTRIES: Int = 256
    const val MAX_SINGLE_ENTRY_BYTES: Long = 1L * 1024 * 1024
    const val MAX_MANIFEST_BYTES: Long = 64L * 1024
    const val MAX_COMPRESSION_RATIO: Long = 100L

    const val MAX_PATH_DEPTH: Int = 16
    const val MAX_PATH_CHARS: Int = 512
    const val MAX_SEGMENT_CHARS: Int = 255

    const val MAX_TOOLS: Int = 32
    const val MAX_REQUIRES: Int = 8
    const val MAX_RESOURCES: Int = 192
    const val MAX_PRESET_KEYS: Int = 32
    const val MAX_EXPOSED_PARAMETERS: Int = 32

    const val MAX_DISPLAY_NAME_CHARS: Int = 128
    const val MAX_TOOL_DESCRIPTION_BYTES: Int = 2 * 1024
    const val MAX_DESCRIPTION_BYTES: Int = 4 * 1024
    const val MAX_VERSION_CHARS: Int = 64
    const val MAX_SOURCE_URI_CHARS: Int = 1_024

    const val MIN_TIMEOUT_MILLIS: Long = 1L
    const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000L

    /** Preview-store maintenance bounds. */
    const val MAX_QUARANTINE_COPIES: Int = 16
    const val MAX_STORE_INDEX_BYTES: Long = 1024L * 1024

    /** Operations a P0 preview may bind to: code-owned, read-only first. */
    const val HOST_WORKSPACE_LIST: String = "workspace.list"
    const val HOST_WORKSPACE_READ_TEXT: String = "workspace.read_text"
    const val HOST_WORKSPACE_SEARCH_TEXT: String = "workspace.search_text"

    val READ_ONLY_HOST_OPERATIONS: List<String> = listOf(
        HOST_WORKSPACE_LIST,
        HOST_WORKSPACE_READ_TEXT,
        HOST_WORKSPACE_SEARCH_TEXT
    )

    /** Control files a resource may not shadow inside a private snapshot. */
    val RESERVED_RESOURCE_PATHS: Set<String> = setOf(MANIFEST_FILE_NAME, RECORD_FILE_NAME)

    /**
     * Extensions accepted as package resources, mapped to their allowed base media types.
     * Executable/script/binary extensions (.js, .wasm, .dex, .jar, .so, .apk, ...) are absent.
     */
    val RESOURCE_MEDIA_TYPES: Map<String, Set<String>> = mapOf(
        "md" to setOf("text/markdown"),
        "markdown" to setOf("text/markdown"),
        "txt" to setOf("text/plain"),
        "rst" to setOf("text/x-rst"),
        "csv" to setOf("text/csv"),
        "tsv" to setOf("text/tab-separated-values"),
        "json" to setOf("application/json"),
        "yaml" to setOf("application/yaml", "text/yaml"),
        "yml" to setOf("application/yaml", "text/yaml"),
        "xml" to setOf("application/xml", "text/xml")
    )

    /**
     * Manifest keys that would imply executable behaviour, self-authorization or a second
     * permission/execution channel. They get a dedicated error code so adding one can never be
     * mistaken for a harmless typo. Unknown keys are rejected too.
     */
    val EXECUTABLE_FIELD_NAMES: Set<String> = setOf(
        "main", "entry", "entrypoint", "script", "scripts", "js", "mjs", "cjs", "ts",
        "code", "exec", "execute", "executor", "executors", "runtime", "command", "shell",
        "wasm", "dex", "jar", "so", "apk", "native", "hooks", "hook", "network", "http",
        "https", "url", "uri", "permissions", "permission", "autoApprove", "enabledByDefault",
        "enabled", "ui", "provider", "model", "credentials", "secrets", "mcp", "toolsProxy"
    )

    fun requirePackageId(raw: String, field: String): String {
        if (!AgentToolPackageToolRef.isValidPackageId(raw)) {
            toolPackageFailure(
                AgentToolPackageErrorCode.MANIFEST_INVALID_ID,
                "$field must be lowercase [a-z0-9][a-z0-9-]{1,63}"
            )
        }
        return raw
    }
}

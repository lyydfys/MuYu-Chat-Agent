package com.muyuchat.core.agent.contract

/**
 * Platform-free contract records for the manifest-first tool-package foundation (proposal P0-B).
 *
 * P0 stores, validates and previews a package; it never executes one. None of the types in this
 * file is an [AgentToolExecutor], can create one, or carries executable content. A future P1 binding
 * layer must construct an executor from a **code-owned** host operation; a package manifest only
 * selects an allowlisted operation id and a static preset.
 */

/**
 * Stable identity of one tool declared by a tool package.
 *
 * The reserved key shape is `pkg.<packageId>.<toolName>`. It is deliberately a separate value type:
 * the existing [AgentToolDescriptor] only accepts built-in dotted keys and `mcp:<serverId>:<name>`,
 * and P0 must not widen the live production catalog. P1 will translate this ref into a descriptor
 * only after the catalog/approval gates are approved.
 */
data class AgentToolPackageToolRef(
    val packageId: String,
    val toolName: String
) {
    init {
        require(isValidPackageId(packageId)) {
            "package id must match [a-z0-9][a-z0-9-]{1,63}: '$packageId'"
        }
        require(isValidToolName(toolName)) {
            "package tool name must be lowercase snake case: '$toolName'"
        }
    }

    /** Reserved run-facing key; never registered by the P0 catalog. */
    val toolKey: String get() = TOOL_KEY_PREFIX + packageId + "." + toolName

    companion object {
        const val TOOL_KEY_PREFIX: String = "pkg."

        private val PACKAGE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{1,63}$")
        private val TOOL_NAME_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")
        private val HOST_OPERATION_PATTERN = Regex("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$")
        private val PARAMETER_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

        fun isValidPackageId(value: String): Boolean = PACKAGE_ID_PATTERN.matches(value)

        fun isValidToolName(value: String): Boolean = TOOL_NAME_PATTERN.matches(value)

        fun isValidHostOperationId(value: String): Boolean = HOST_OPERATION_PATTERN.matches(value)

        fun isValidParameterName(value: String): Boolean = PARAMETER_NAME_PATTERN.matches(value)

        fun isValidSha256(value: String): Boolean = SHA256_PATTERN.matches(value)
    }
}

/** Scalar parameter kinds a package can preset/expose in V1. */
enum class AgentToolPackageParameterType {
    STRING,
    INTEGER,
    BOOLEAN
}

/**
 * One trusted parameter of a code-owned host operation.
 *
 * These values come from the app's frozen built-in schema, never from a package. [minLength],
 * [maxLength], [minimum] and [maximum] mirror the host schema constraints so a static preset can be
 * validated at preview time instead of becoming a run-time surprise.
 */
data class AgentToolPackageHostParameter(
    val name: String,
    val type: AgentToolPackageParameterType,
    val required: Boolean,
    val nullable: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minimum: Long? = null,
    val maximum: Long? = null
) {
    init {
        require(AgentToolPackageToolRef.isValidParameterName(name)) {
            "host parameter name is invalid: '$name'"
        }
        require(minLength == null || minLength >= 0) { "host parameter minLength must not be negative" }
        require(maxLength == null || maxLength >= 0) { "host parameter maxLength must not be negative" }
        require(minLength == null || maxLength == null || minLength <= maxLength) {
            "host parameter length bounds are inverted: '$name'"
        }
        require(minimum == null || maximum == null || minimum <= maximum) {
            "host parameter numeric bounds are inverted: '$name'"
        }
    }
}

/**
 * Metadata of one code-owned host operation that a package may target.
 *
 * The operation id must exist in the app's trusted registry; the package content never provides the
 * implementation, schema, side effect or data class. [sideEffect] and [dataClass] are the floor a
 * future derived package descriptor must keep (they can never be lowered by a manifest).
 */
data class AgentToolPackageHostOperation(
    val operationId: String,
    val displayName: String,
    val sideEffect: AgentToolSideEffect,
    val dataClass: AgentDataClass,
    val parameters: List<AgentToolPackageHostParameter>,
    val maxTimeoutMillis: Long
) {
    init {
        require(AgentToolPackageToolRef.isValidHostOperationId(operationId)) {
            "host operation id must be a dotted lowercase identifier: '$operationId'"
        }
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME_CHARS) {
            "host operation display name is outside the bounded range"
        }
        require(parameters.size <= MAX_HOST_PARAMETERS) {
            "host operation declares too many parameters"
        }
        require(parameters.map { it.name }.toSet().size == parameters.size) {
            "host operation declares a duplicate parameter name"
        }
        require(maxTimeoutMillis in 1L..MAX_HOST_TIMEOUT_MILLIS) {
            "host operation timeout is outside the bounded range"
        }
    }

    fun parameter(name: String): AgentToolPackageHostParameter? = parameters.firstOrNull { it.name == name }

    companion object {
        const val MAX_HOST_PARAMETERS: Int = 32
        const val MAX_DISPLAY_NAME_CHARS: Int = 128
        const val MAX_HOST_TIMEOUT_MILLIS: Long = 5L * 60L * 1000L
    }
}

/**
 * Immutable binding produced by the preview binder for one package tool.
 *
 * `presetCanonicalJson` is a canonical JSON object (keys sorted, no whitespace) whose keys are the
 * host operation's parameter names. [exposedParameters] is sorted/unique. [packageContentHash]
 * binds the manifest and every previewed resource, so re-using this binding for different package
 * bytes is impossible without changing the hash.
 */
data class AgentToolPackageBinding(
    val ref: AgentToolPackageToolRef,
    val packageVersion: String,
    val hostOperation: String,
    val presetCanonicalJson: String,
    val exposedParameters: List<String>,
    val timeoutMillis: Long,
    val packageContentHash: String,
    val sideEffect: AgentToolSideEffect,
    val dataClass: AgentDataClass
) {
    init {
        require(packageVersion.isNotBlank() && packageVersion.length <= MAX_VERSION_CHARS) {
            "package version is outside the bounded range"
        }
        require(AgentToolPackageToolRef.isValidHostOperationId(hostOperation)) {
            "package binding host operation id is invalid: '$hostOperation'"
        }
        require(presetCanonicalJson.length in 2..MAX_PRESET_CHARS) {
            "package binding preset is outside the bounded range"
        }
        require(presetCanonicalJson.startsWith("{") && presetCanonicalJson.endsWith("}")) {
            "package binding preset must be a JSON object"
        }
        require(exposedParameters.size <= AgentToolPackageHostOperation.MAX_HOST_PARAMETERS) {
            "package binding exposes too many parameters"
        }
        require(exposedParameters == exposedParameters.distinct().sorted()) {
            "package binding exposed parameters must be sorted and unique"
        }
        require(exposedParameters.all { AgentToolPackageToolRef.isValidParameterName(it) }) {
            "package binding exposes an invalid parameter name"
        }
        require(timeoutMillis in 1L..AgentToolPackageHostOperation.MAX_HOST_TIMEOUT_MILLIS) {
            "package binding timeout is outside the bounded range"
        }
        require(AgentToolPackageToolRef.isValidSha256(packageContentHash)) {
            "package binding content hash must be lowercase SHA-256"
        }
    }

    /** Version suffix a future derived descriptor should use: `<packageVersion>+<hash16>`. */
    val versionIdentity: String get() = packageVersion + "+" + packageContentHash.take(16)

    /** Declared metadata only; the actual read/write/network decision remains with the broker. */
    val isReadOnly: Boolean get() = sideEffect == AgentToolSideEffect.READ_ONLY

    companion object {
        const val MAX_VERSION_CHARS: Int = 64
        const val MAX_PRESET_CHARS: Int = 16 * 1024
    }
}

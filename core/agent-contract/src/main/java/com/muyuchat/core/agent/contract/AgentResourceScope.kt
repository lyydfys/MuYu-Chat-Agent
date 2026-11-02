package com.muyuchat.core.agent.contract

import java.security.MessageDigest

/**
 * Versioned, platform-free resource scope (N02c / DESIGN 3.3).
 *
 * A scope describes *what exact resources* one frozen invocation may touch. It is deliberately a
 * closed set of data classes rather than one broad interface: tools compose the scopes they really
 * need, and the approval binds the canonical content hash instead of a display string.
 *
 * Invariants:
 * - collections are normalized (sorted, de-duplicated) before they enter [canonicalJson];
 * - relative paths are task/package-relative, never absolute and never contain `..`;
 * - network origins are canonical `scheme://host[:port]` values without path/query/fragment;
 * - an empty scope is invalid: a tool that touches nothing should not ask for approval at all.
 */
sealed interface AgentResourceScope {
    val scopeVersion: Int

    data class Workspace(
        val scopeId: String,
        val relativePaths: List<String>,
        val access: AgentResourceAccess,
        val contentPreconditionsJson: String = "{}",
        override val scopeVersion: Int = VERSION
    ) : AgentResourceScope {
        init {
            AgentContract.requireId(scopeId, "workspace scopeId")
            require(relativePaths.isNotEmpty()) { "workspace scope needs at least one path" }
            relativePaths.forEach { requireRelativePath(it, "workspace scope path") }
            require(contentPreconditionsJson.startsWith("{")) {
                "contentPreconditionsJson must be a JSON object"
            }
        }
    }

    data class Memory(
        val spaceIds: List<String>,
        val itemIds: List<String>? = null,
        val revisions: Map<String, Long> = emptyMap(),
        val access: AgentResourceAccess = AgentResourceAccess.READ,
        override val scopeVersion: Int = VERSION
    ) : AgentResourceScope {
        init {
            require(spaceIds.isNotEmpty()) { "memory scope needs at least one space" }
            spaceIds.forEach { AgentContract.requireId(it, "memory spaceId") }
            itemIds?.forEach { AgentContract.requireId(it, "memory itemId") }
            require(revisions.keys.all { AgentContract.isValidId(it) }) {
                "memory revision keys must be stable ids"
            }
            require(revisions.values.all { it >= 0L }) { "memory revision must not be negative" }
        }
    }

    data class Network(
        val origins: List<String>,
        val methods: Set<String>,
        val dataClass: AgentDataClass = AgentDataClass.PRIVATE,
        val bodyHash: String? = null,
        override val scopeVersion: Int = VERSION
    ) : AgentResourceScope {
        init {
            require(origins.isNotEmpty()) { "network scope needs at least one origin" }
            origins.forEach { requireCanonicalOrigin(it) }
            require(methods.isNotEmpty()) { "network scope needs at least one method" }
            require(methods.all { it.matches(Regex("^[A-Z]{1,16}$")) }) {
                "network methods must be upper-case tokens"
            }
            require(bodyHash == null || bodyHash.matches(Regex("^[0-9a-f]{64}$"))) {
                "network body hash must be lowercase SHA-256"
            }
        }
    }

    data class Device(
        val operation: String,
        val targetPackage: String? = null,
        val selectorHash: String? = null,
        val userVisibleSummary: String,
        override val scopeVersion: Int = VERSION
    ) : AgentResourceScope {
        init {
            AgentContract.requireNotBlank(operation, "device operation")
            AgentContract.requireNotBlank(userVisibleSummary, "device user-visible summary")
            targetPackage?.let {
                require(it.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$"))) {
                    "device target package is not a canonical package name: '$it'"
                }
            }
            selectorHash?.let {
                require(it.matches(Regex("^[0-9a-f]{64}$"))) {
                    "device selector hash must be lowercase SHA-256"
                }
            }
        }
    }

    data class Process(
        val runtimeId: String,
        val cwdHandle: String,
        val argvHash: String,
        val environmentPolicy: String,
        override val scopeVersion: Int = VERSION
    ) : AgentResourceScope {
        init {
            AgentContract.requireId(runtimeId, "process runtimeId")
            AgentContract.requireNotBlank(cwdHandle, "process cwdHandle")
            require(argvHash.matches(Regex("^[0-9a-f]{64}$"))) {
                "process argv hash must be lowercase SHA-256"
            }
            AgentContract.requireNotBlank(environmentPolicy, "process environmentPolicy")
        }
    }

    data class PackageResource(
        val packageContentHash: String,
        val relativePaths: List<String>,
        override val scopeVersion: Int = VERSION
    ) : AgentResourceScope {
        init {
            require(packageContentHash.matches(Regex("^[0-9a-f]{64}$"))) {
                "package content hash must be lowercase SHA-256"
            }
            require(relativePaths.isNotEmpty()) { "package scope needs at least one resource" }
            relativePaths.forEach { requireRelativePath(it, "package resource path") }
        }
    }

    data class Composite(
        val requiredScopes: List<AgentResourceScope>,
        override val scopeVersion: Int = VERSION
    ) : AgentResourceScope {
        init {
            require(requiredScopes.isNotEmpty()) { "composite scope needs at least one child" }
            require(requiredScopes.none { it is Composite && it.requiredScopes.isEmpty() }) {
                "composite child scopes must not be empty"
            }
        }
    }

    companion object {
        /** Bumped only when the persisted/canonical shape changes incompatibly. */
        const val VERSION: Int = 2

        fun requireRelativePath(value: String, field: String) {
            require(value.isNotBlank()) { "$field must not be blank" }
            require(!value.startsWith("/") && !value.startsWith("\\")) {
                "$field must be relative: '$value'"
            }
            require(!value.contains("..")) { "$field must not contain '..': '$value'" }
            require(!value.contains('\\')) { "$field must use '/' separators: '$value'" }
            require(!value.contains('\u0000')) { "$field must not contain NUL" }
        }

        fun requireCanonicalOrigin(value: String) {
            val match = ORIGIN_PATTERN.matchEntire(value)
            require(match != null) { "network origin is not canonical: '$value'" }
            require(value == value.lowercase()) { "network origin must be lowercase: '$value'" }
        }

        private val ORIGIN_PATTERN = Regex("^https?://[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?$")
    }
}

enum class AgentResourceAccess {
    READ,
    WRITE,
    READ_WRITE
}

/**
 * Effect classes for one invocation (N02c). The legacy [AgentToolSideEffect] stays readable for old
 * approvals; new approvals bind the full set so "read a private file and send it" cannot collapse
 * into one enum value.
 */
enum class AgentEffectKind {
    LOCAL_READ,
    LOCAL_WRITE,
    NETWORK_READ,
    NETWORK_WRITE,
    DEVICE_ACTION,
    PROCESS_EXEC,
    PACKAGE_CALL,
    MODEL_CALL
}

/**
 * Canonical encoding + stable fingerprint for [AgentResourceScope].
 *
 * The JSON shape is a hand-written, dependency-free encoding so the contract module stays platform
 * neutral. Keys are emitted in fixed order; string values are JSON escaped by [appendJsonString].
 */
object AgentResourceScopeCodec {
    private const val FINGERPRINT_DOMAIN = "mca.agent.scope.v2\n"

    fun canonicalJson(scope: AgentResourceScope): String = buildString { appendScope(scope) }

    fun fingerprint(scope: AgentResourceScope): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((FINGERPRINT_DOMAIN + canonicalJson(scope)).toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }
    private fun StringBuilder.appendScope(scope: AgentResourceScope) {
        when (scope) {
            is AgentResourceScope.Workspace -> {
                append("{\"kind\":\"workspace\",\"scopeVersion\":").append(scope.scopeVersion)
                append(",\"scopeId\":").appendJsonString(scope.scopeId)
                append(",\"access\":").appendJsonString(scope.access.name)
                append(",\"paths\":").appendJsonStringArray(scope.relativePaths.distinct().sorted())
                append(",\"preconditions\":").append(scope.contentPreconditionsJson)
                append('}')
            }
            is AgentResourceScope.Memory -> {
                append("{\"kind\":\"memory\",\"scopeVersion\":").append(scope.scopeVersion)
                append(",\"spaceIds\":").appendJsonStringArray(scope.spaceIds.distinct().sorted())
                append(",\"access\":").appendJsonString(scope.access.name)
                append(",\"itemIds\":")
                if (scope.itemIds == null) append("null")
                else appendJsonStringArray(scope.itemIds.distinct().sorted())
                append(",\"revisions\":{")
                scope.revisions.toSortedMap().entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendJsonString(entry.key)
                    append(':')
                    append(entry.value.toString())
                }
                append("}}")
            }
            is AgentResourceScope.Network -> {
                append("{\"kind\":\"network\",\"scopeVersion\":").append(scope.scopeVersion)
                append(",\"origins\":").appendJsonStringArray(scope.origins.distinct().sorted())
                append(",\"methods\":").appendJsonStringArray(scope.methods.distinct().sorted())
                append(",\"dataClass\":").appendJsonString(scope.dataClass.name)
                append(",\"bodyHash\":")
                if (scope.bodyHash == null) append("null") else appendJsonString(scope.bodyHash)
                append('}')
            }
            is AgentResourceScope.Device -> {
                append("{\"kind\":\"device\",\"scopeVersion\":").append(scope.scopeVersion)
                append(",\"operation\":").appendJsonString(scope.operation)
                append(",\"targetPackage\":")
                if (scope.targetPackage == null) append("null") else appendJsonString(scope.targetPackage)
                append(",\"selectorHash\":")
                if (scope.selectorHash == null) append("null") else appendJsonString(scope.selectorHash)
                append(",\"userVisibleSummary\":").appendJsonString(scope.userVisibleSummary)
                append('}')
            }
            is AgentResourceScope.Process -> {
                append("{\"kind\":\"process\",\"scopeVersion\":").append(scope.scopeVersion)
                append(",\"runtimeId\":").appendJsonString(scope.runtimeId)
                append(",\"cwdHandle\":").appendJsonString(scope.cwdHandle)
                append(",\"argvHash\":").appendJsonString(scope.argvHash)
                append(",\"environmentPolicy\":").appendJsonString(scope.environmentPolicy)
                append('}')
            }
            is AgentResourceScope.PackageResource -> {
                append("{\"kind\":\"packageResource\",\"scopeVersion\":").append(scope.scopeVersion)
                append(",\"packageContentHash\":").appendJsonString(scope.packageContentHash)
                append(",\"paths\":").appendJsonStringArray(scope.relativePaths.distinct().sorted())
                append('}')
            }
            is AgentResourceScope.Composite -> {
                append("{\"kind\":\"composite\",\"scopeVersion\":").append(scope.scopeVersion)
                append(",\"children\":[")
                scope.requiredScopes
                    .map { canonicalJson(it) to it }
                    .sortedBy { it.first }
                    .forEachIndexed { index, pair ->
                        if (index > 0) append(',')
                        append(pair.first)
                    }
                append("]}")
            }
        }
    }
    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
    private fun StringBuilder.appendJsonStringArray(values: List<String>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            appendJsonString(value)
        }
        append(']')
    }
}
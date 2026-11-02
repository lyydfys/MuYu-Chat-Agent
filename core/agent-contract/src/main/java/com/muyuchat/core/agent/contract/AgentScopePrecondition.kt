package com.muyuchat.core.agent.contract

import java.security.MessageDigest

/**
 * Versioned typed resource precondition for one frozen invocation (N02c / DESIGN 3.3).
 *
 * The V1 precondition was `(taskRoot, logicalPaths, expectedOldHash)` — a workspace-only shape that
 * forced memory to carry the fake path `memory`. This type expresses the exact resources a call may
 * touch (memory spaces, network origins, device targets, process argv, package resources) together
 * with the effect classes the caller declared.
 *
 * Canonical form (encoded by [AgentScopePreconditionCodec]) is deterministic: scopes are ordered by
 * their canonical JSON, effects are ordered by name. The persisted precondition JSON and its
 * fingerprint are what an approval binds; an executor re-derives the same value from trusted host
 * facts and rejects a mismatch instead of trusting the stored string.
 *
 * V1 approvals stay readable: they keep their legacy fingerprint and are only rejected by tools that
 * require a typed scope (for example `memory.search`), which must then re-request approval.
 */
data class AgentScopePrecondition(
    val scopes: List<AgentResourceScope>,
    val effects: Set<AgentEffectKind>,
    val schemaVersion: Int = VERSION
) {
    init {
        require(scopes.isNotEmpty()) { "a typed scope precondition needs at least one scope" }
        require(effects.isNotEmpty()) { "a typed scope precondition needs at least one effect" }
        require(schemaVersion == VERSION) { "unsupported typed scope version: $schemaVersion" }
        scopes.forEach { scope ->
            require(covers(scope, effects)) {
                "scope ${scope::class.simpleName} declares no matching effect; expected one of " +
                    requiredEffects(scope)
            }
        }
    }

    companion object {
        const val VERSION: Int = 2

        /** True when every concrete resource in [scope] has at least one declared effect. */
        fun covers(scope: AgentResourceScope, effects: Set<AgentEffectKind>): Boolean = when (scope) {
            is AgentResourceScope.Composite ->
                scope.requiredScopes.isNotEmpty() &&
                    scope.requiredScopes.all { covers(it, effects) }
            else -> requiredEffects(scope).any { it in effects }
        }

        /** The effect classes that can make a scope of this shape meaningful. */
        fun requiredEffects(scope: AgentResourceScope): Set<AgentEffectKind> = when (scope) {
            is AgentResourceScope.Workspace -> when (scope.access) {
                AgentResourceAccess.READ -> setOf(AgentEffectKind.LOCAL_READ)
                AgentResourceAccess.WRITE -> setOf(AgentEffectKind.LOCAL_WRITE)
                AgentResourceAccess.READ_WRITE ->
                    setOf(AgentEffectKind.LOCAL_READ, AgentEffectKind.LOCAL_WRITE)
            }
            is AgentResourceScope.Memory -> when (scope.access) {
                AgentResourceAccess.READ -> setOf(AgentEffectKind.LOCAL_READ)
                AgentResourceAccess.WRITE -> setOf(AgentEffectKind.LOCAL_WRITE)
                AgentResourceAccess.READ_WRITE ->
                    setOf(AgentEffectKind.LOCAL_READ, AgentEffectKind.LOCAL_WRITE)
            }
            is AgentResourceScope.Network ->
                setOf(AgentEffectKind.NETWORK_READ, AgentEffectKind.NETWORK_WRITE)
            is AgentResourceScope.Device -> setOf(AgentEffectKind.DEVICE_ACTION)
            is AgentResourceScope.Process -> setOf(AgentEffectKind.PROCESS_EXEC)
            is AgentResourceScope.PackageResource -> setOf(AgentEffectKind.PACKAGE_CALL)
            is AgentResourceScope.Composite ->
                scope.requiredScopes.flatMap { requiredEffects(it) }.toSet()
        }
    }
}

/**
 * Canonical encoding and fingerprint for [AgentScopePrecondition].
 *
 * Dependency-free (the contract module stays platform neutral): scope JSON comes from
 * [AgentResourceScopeCodec]; this codec fixes the field order and the fingerprint domain.
 */
object AgentScopePreconditionCodec {
    private const val DOMAIN = "mca.scope-precondition.v2\n"

    fun canonicalJson(precondition: AgentScopePrecondition): String = buildString {
        append("{\"v\":").append(precondition.schemaVersion)
        append(",\"scopes\":[")
        precondition.scopes
            .map { AgentResourceScopeCodec.canonicalJson(it) }
            .sorted()
            .forEachIndexed { index, json ->
                if (index > 0) append(',')
                append(json)
            }
        append("],\"effects\":[")
        precondition.effects
            .map { it.name }
            .sorted()
            .forEachIndexed { index, name ->
                if (index > 0) append(',')
                append('"').append(name).append('"')
            }
        append("]}")
    }

    fun fingerprint(precondition: AgentScopePrecondition): String =
        fingerprintJson(canonicalJson(precondition))

    /**
     * Fingerprint of an already-canonical precondition document.
     *
     * Durable records keep the canonical JSON; verification re-derives the document from trusted
     * host facts and compares strings, so this overload never needs a JSON parser and never trusts
     * an arbitrary serialization as canonical.
     */
    fun fingerprintJson(canonicalPreconditionJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((DOMAIN + canonicalPreconditionJson).toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    /** Compact human-readable summary for approval surfaces; never contains arguments or secrets. */
    fun summary(precondition: AgentScopePrecondition): String =
        precondition.scopes.joinToString(separator = " + ") { scope ->
            when (scope) {
                is AgentResourceScope.Workspace ->
                    "workspace:${scope.scopeId}:${scope.access.name.lowercase()}:${scope.relativePaths.size}"
                is AgentResourceScope.Memory ->
                    "memory:${scope.spaceIds.sorted().joinToString(",")}:${scope.access.name.lowercase()}"
                is AgentResourceScope.Network ->
                    "network:${scope.origins.sorted().joinToString(",")}:${scope.methods.sorted().joinToString(",")}"
                is AgentResourceScope.Device ->
                    "device:${scope.operation}${scope.targetPackage?.let { ":$it" } ?: ""}"
                is AgentResourceScope.Process -> "process:${scope.runtimeId}"
                is AgentResourceScope.PackageResource ->
                    "package:${scope.packageContentHash.take(12)}:${scope.relativePaths.size}"
                is AgentResourceScope.Composite ->
                    "composite:${scope.requiredScopes.size}"
            }
        }
}

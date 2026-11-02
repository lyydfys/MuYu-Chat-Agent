package com.muyuchat.mca.agent.toolpack

import com.muyuchat.mca.agent.tools.AgentToolJson

/**
 * Run-level revocation identity bound into every nested host call.
 *
 * A nested host call is only valid for the run epoch and policy version that produced it. An
 * approval issued for a different pair is stale even when its arguments still match, so a revoked
 * or superseded policy can never be replayed into a new epoch.
 */
data class ToolPackageHostRevocation(val runEpoch: Long, val policyVersion: Long) {
    init {
        require(runEpoch > 0L) { "host call run epoch must be positive" }
        require(policyVersion > 0L) { "host call policy version must be positive" }
    }
}

enum class ToolPackageHostResourceAccess { READ, WRITE }

/**
 * The exact host resource one nested call may touch.
 *
 * This is a closed set: there is no network origin, shell, process or arbitrary host path variant,
 * so a package cannot request a capability this build does not model. Paths are logical,
 * task-relative and already canonicalised by the owning handler; [canonicalId] never contains an
 * absolute device path and is what the approval binds.
 */
sealed interface ToolPackageHostResource {
    /** Host API method this resource belongs to; must equal the called method. */
    val capability: String

    /** Stable, content-free identity used by the per-call approval. */
    val canonicalId: String

    /** Resource family declared by the code-owned registry for [capability]. */
    val kind: ToolPackageHostResourceKind

    data class Workspace(
        val workspaceId: String,
        val relativePath: String?,
        val access: ToolPackageHostResourceAccess,
        override val capability: String
    ) : ToolPackageHostResource {
        init {
            require(workspaceId.isNotBlank()) { "workspace resource needs a workspace id" }
            require(capability.isNotBlank()) { "workspace resource needs a capability" }
            if (relativePath != null) {
                require(relativePath.isNotBlank()) { "workspace resource path must not be blank" }
                require(!relativePath.startsWith("/")) { "workspace resource path must be relative" }
                require(!relativePath.contains('\\')) { "workspace resource path must use '/' separators" }
                require(relativePath.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
                    "workspace resource path must be canonical"
                }
            }
        }

        override val kind: ToolPackageHostResourceKind = ToolPackageHostResourceKind.WORKSPACE
        override val canonicalId: String =
            "workspace/$workspaceId/${access.name.lowercase()}/${relativePath ?: "."}"
    }

    data class Memory(
        val workspaceId: String,
        val spaceId: String,
        override val capability: String
    ) : ToolPackageHostResource {
        init {
            require(workspaceId.isNotBlank()) { "memory resource needs a workspace id" }
            require(spaceId.isNotBlank()) { "memory resource needs a space id" }
            require(capability.isNotBlank()) { "memory resource needs a capability" }
        }

        override val kind: ToolPackageHostResourceKind = ToolPackageHostResourceKind.MEMORY
        override val canonicalId: String = "memory/$workspaceId/$spaceId"
    }

    /** A capability that touches no resource at all (bounded pure host computation). */
    data class None(override val capability: String) : ToolPackageHostResource {
        init {
            require(capability.isNotBlank()) { "resource-free host call needs a capability" }
        }

        override val kind: ToolPackageHostResourceKind = ToolPackageHostResourceKind.NONE
        override val canonicalId: String = "none/$capability"
    }
}

/** The resource family a host capability may touch. Declared in the code-owned registry. */
enum class ToolPackageHostResourceKind { NONE, WORKSPACE, MEMORY }

/** Resource probe handed to the owning handler before an approval is requested or a call dispatched. */
data class ToolPackageHostResourceRequest(
    val api: ToolPackageHostApi,
    val argumentsJson: String,
    val instanceId: String,
    val packageId: String,
    val packageContentHash: String,
    val scope: ToolPackageExecutionScope
)

/** Outcome of resolving the resource surface of one host call. A refusal never reaches a handler. */
sealed interface ToolPackageHostResourceResolution {
    data class Resolved(val resource: ToolPackageHostResource) : ToolPackageHostResourceResolution

    data class Refused(val error: ToolPackageError) : ToolPackageHostResourceResolution
}

/**
 * Exact identity of one nested host call: the only value a per-call approval may authorise.
 *
 * Every field comes from trusted host facts (the frozen package snapshot, the run scope and the
 * bridge's own canonical hashing). The script contributes only the method, the arguments and an
 * optional correlation ordinal; it cannot choose the package hash, the run, the parent invocation,
 * the resolved resource or the revocation epoch. [argumentsHash] is the SHA-256 of the canonical
 * argument object, so a semantically identical replay maps to the same binding while a changed byte
 * does not.
 */
data class ToolPackageHostCallBinding(
    val packageId: String,
    val packageContentHash: String,
    val runId: String,
    val parentInvocationId: String,
    val instanceId: String,
    val stableCallOrdinal: Int,
    val method: String,
    val access: ToolPackageHostAccess,
    val argumentsHash: String,
    val resource: ToolPackageHostResource,
    val revocation: ToolPackageHostRevocation
) {
    init {
        require(packageId.isNotBlank()) { "host call binding needs a package id" }
        require(packageContentHash.isNotBlank()) { "host call binding needs a package hash" }
        require(runId.isNotBlank()) { "host call binding needs a run id" }
        require(parentInvocationId.isNotBlank()) { "host call binding needs a parent invocation id" }
        require(instanceId.isNotBlank()) { "host call binding needs an instance id" }
        require(stableCallOrdinal >= 1) { "host call binding needs a stable ordinal" }
        require(method.isNotBlank()) { "host call binding needs a method" }
        require(
            argumentsHash.length == 64 &&
                argumentsHash.all { it in '0'..'9' || it in 'a'..'f' }
        ) { "host call binding needs a lowercase SHA-256 arguments hash" }
        require(resource.capability == method) { "host call resource must belong to the called method" }
    }

    /** Deterministic, content-free identity: one binding authorises at most one dispatch. */
    val bindingId: String = AgentToolJson.sha256Hex(
        listOf(
            packageId,
            packageContentHash,
            runId,
            parentInvocationId,
            instanceId,
            stableCallOrdinal.toString(),
            method,
            access.name,
            argumentsHash,
            resource.canonicalId,
            revocation.runEpoch.toString(),
            revocation.policyVersion.toString()
        ).joinToString(separator = "\n").toByteArray(Charsets.UTF_8)
    )
}

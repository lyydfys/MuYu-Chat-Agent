package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolPackageToolRef

/**
 * One exact dependency lock entry: `{packageId, version, contentHash}`.
 *
 * The lock is deliberately exact and content-addressed. [version] is informational for resolution
 * (it must equal the installed record's version) while [contentHash] is the identity that must match
 * stored content byte-for-byte. An executable package never fetches, downloads or installs a
 * dependency; every entry must already be provisioned locally before the parent can be installed.
 */
data class ToolPackageDependency(
    val packageId: String,
    val version: String,
    val contentHash: String
) {
    init {
        require(AgentToolPackageToolRef.isValidPackageId(packageId)) {
            "dependency package id must be lowercase [a-z0-9][a-z0-9-]{1,63}"
        }
        require(version.isNotBlank() && version.length <= ToolPackageLimits.MAX_VERSION_CHARS) {
            "dependency version must be nonblank and bounded"
        }
        require(SHA256.matches(contentHash)) { "dependency content hash must be lowercase SHA-256" }
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Stable install-time codes of the exact dependency lock.
 *
 * They are surfaced unchanged through [ToolPackageInstallResult.Rejected] and therefore through the
 * lifecycle service; callers may branch on them without parsing messages.
 */
object ToolPackageDependencyCodes {
    /** No installed record exists for the declared package id. */
    const val MISSING: String = "TOOLPACK_DEPENDENCY_MISSING"

    /** The record exists but its stored content is absent or no longer verifies. */
    const val UNPROVISIONED: String = "TOOLPACK_DEPENDENCY_UNPROVISIONED"

    /** The installed record's version or content hash differs from the exact lock. */
    const val MISMATCH: String = "TOOLPACK_DEPENDENCY_MISMATCH"

    /** Following declared dependencies reaches the incoming package id again. */
    const val CYCLE: String = "TOOLPACK_DEPENDENCY_CYCLE"

    /** The reachable dependency graph exceeds the bounded depth/node budget. */
    const val DEPTH_EXCEEDED: String = "TOOLPACK_DEPENDENCY_DEPTH"
}

internal sealed interface ToolPackageDependencyResolution {
    data object Satisfied : ToolPackageDependencyResolution

    data class Rejected(val code: String, val safeMessage: String) : ToolPackageDependencyResolution
}

/**
 * Resolves one incoming manifest's dependency lock against **already stored** state.
 *
 * Resolution is offline and read-only: it consults installed records, re-verifies stored content and
 * walks stored manifests for the cycle check. It never writes, downloads or executes anything, so a
 * rejection leaves the previous activation and content untouched. The caller runs it before the
 * install transaction starts.
 *
 * Cycle detection is a bounded DFS over stored manifests keyed by package id and following each
 * manifest's declared content hash. A reachable package id repeated on the current path (including
 * the incoming package id) is a cycle; fully explored nodes are memoised, so shared dependencies are
 * visited once.
 */
internal class ToolPackageDependencyResolver(
    private val recordOf: (packageId: String) -> ToolPackageInstalledRecord?,
    private val verifyContent: suspend (contentHash: String) -> ToolPackageVerifyResult
) {
    private class Frame(
        val dependency: ToolPackageDependency,
        val depth: Int,
        val exiting: Boolean
    )

    /**
     * Verifies the whole exact lock closure, not just the direct edges: every reachable edge must
     * match an installed record (id, version, content hash) and its stored content must verify.
     * A dependency that was removed while an older parent still declares it therefore blocks the new
     * install instead of being ignored.
     */
    suspend fun resolve(incoming: ExecutableToolPackageManifest): ToolPackageDependencyResolution {
        if (incoming.dependencies.isEmpty()) return ToolPackageDependencyResolution.Satisfied
        val verifiedLocks = HashMap<String, String>(incoming.dependencies.size)
        val onPath = HashSet<String>()
        val stack = ArrayDeque<Frame>()
        incoming.dependencies.asReversed().forEach { stack.addLast(Frame(it, 1, exiting = false)) }
        var visitedNodes = 0

        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val dependency = frame.dependency
            if (frame.exiting) {
                onPath.remove(dependency.packageId)
                continue
            }
            if (dependency.packageId == incoming.packageId) {
                return rejected(CYCLE, "The package dependency lock contains a cycle.")
            }
            if (frame.depth > ToolPackageLimits.MAX_DEPENDENCY_DEPTH) {
                return rejected(DEPTH_EXCEEDED, "The package dependency graph exceeds the bounded depth.")
            }
            // A back edge to a node still on the current path is a cycle even though that node was
            // already "verified" on the way down; check the path before memoisation.
            if (dependency.packageId in onPath) {
                return rejected(CYCLE, "The package dependency lock contains a cycle.")
            }
            val earlierLock = verifiedLocks[dependency.packageId]
            if (earlierLock != null) {
                if (earlierLock != dependency.contentHash) {
                    return rejected(
                        MISMATCH,
                        "Two packages require different content for the same dependency."
                    )
                }
                continue
            }
            onPath.add(dependency.packageId)
            visitedNodes += 1
            if (visitedNodes > ToolPackageLimits.MAX_DEPENDENCY_GRAPH_NODES) {
                return rejected(DEPTH_EXCEEDED, "The package dependency graph exceeds the bounded size.")
            }
            val record = recordOf(dependency.packageId)
                ?: return rejected(MISSING, "A required package dependency is not installed.")
            if (record.contentHash != dependency.contentHash ||
                record.packageVersion != dependency.version
            ) {
                return rejected(
                    MISMATCH,
                    "A required package dependency does not match its exact version and content lock."
                )
            }
            val verified = when (val result = verifyContent(dependency.contentHash)) {
                is ToolPackageVerifyResult.Valid -> result
                is ToolPackageVerifyResult.Rejected -> return rejected(
                    UNPROVISIONED,
                    "A required package dependency's stored content is missing or failed verification."
                )
            }
            val bundle = verified.bundle
            if (bundle.packageId != dependency.packageId ||
                bundle.packageVersion != dependency.version
            ) {
                return rejected(
                    MISMATCH,
                    "A required package dependency does not match its exact version and content lock."
                )
            }
            verifiedLocks[dependency.packageId] = dependency.contentHash
            stack.addLast(Frame(dependency, frame.depth, exiting = true))
            bundle.manifest.dependencies.asReversed().forEach { child ->
                stack.addLast(Frame(child, frame.depth + 1, exiting = false))
            }
        }
        return ToolPackageDependencyResolution.Satisfied
    }

    private fun rejected(code: String, message: String): ToolPackageDependencyResolution.Rejected =
        ToolPackageDependencyResolution.Rejected(code, message)

    private companion object {
        const val MISSING: String = ToolPackageDependencyCodes.MISSING
        const val UNPROVISIONED: String = ToolPackageDependencyCodes.UNPROVISIONED
        const val MISMATCH: String = ToolPackageDependencyCodes.MISMATCH
        const val CYCLE: String = ToolPackageDependencyCodes.CYCLE
        const val DEPTH_EXCEEDED: String = ToolPackageDependencyCodes.DEPTH_EXCEEDED
    }
}

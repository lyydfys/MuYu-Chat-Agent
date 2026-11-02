package com.muyuchat.mca.agent.extensions.toolpkg

import com.muyuchat.core.agent.contract.AgentToolPackageToolRef

/**
 * Immutable, validated representation of one `mca-tool-package.json`.
 *
 * The model contains only primitive/list values so it can be persisted as canonical JSON and
 * compared across process restarts. It deliberately has no executable, permission, network,
 * provider or model fields. [presetJson] is canonical JSON text (keys sorted) and [presetKeys] is
 * its sorted key list; resources carry the declared SHA-256 that the archive must match.
 */
data class AgentToolPackageManifest(
    val schemaVersion: Int,
    val packageId: String,
    val packageVersion: String,
    val apiVersion: Int,
    val displayName: String,
    val description: String,
    val requires: List<AgentToolPackageDependency>,
    val tools: List<AgentToolPackageTool>,
    val resources: List<AgentToolPackageResource>,
    val canonicalJson: String,
    val manifestSha256: String
) {
    init {
        require(AgentToolPackageToolRef.isValidPackageId(packageId)) { "parsed manifest package id is invalid" }
        require(AgentToolPackageToolRef.isValidSha256(manifestSha256)) {
            "parsed manifest hash must be lowercase SHA-256"
        }
    }
}

/** One declared tool. The host operation id is resolved against the app-owned catalog. */
data class AgentToolPackageTool(
    val name: String,
    val displayName: String,
    val description: String,
    val hostOperation: String,
    val presetJson: String,
    val presetKeys: List<String>,
    val exposedParameters: List<String>,
    val timeoutMillis: Long
) {
    init {
        require(AgentToolPackageToolRef.isValidToolName(name)) { "parsed tool name is invalid" }
        require(presetKeys == presetKeys.distinct().sorted()) { "parsed preset keys must be sorted" }
        require(exposedParameters == exposedParameters.distinct().sorted()) {
            "parsed exposed parameters must be sorted"
        }
    }

    /** Binds to a package identity once the caller also knows the package id. */
    fun ref(packageId: String): AgentToolPackageToolRef = AgentToolPackageToolRef(packageId, name)
}

/** One declared text/metadata resource and its declared content hash. */
data class AgentToolPackageResource(
    val path: String,
    val mediaType: String,
    val sha256: String
) {
    init {
        require(AgentToolPackagePathPolicy.isNormalizedRelativeFile(path)) {
            "parsed resource path is not a normalized relative file"
        }
        require(AgentToolPackageToolRef.isValidSha256(sha256)) {
            "parsed resource hash must be lowercase SHA-256"
        }
    }
}

/**
 * One dependency declaration. P0 only validates the declaration shape (bounded id, explicit version
 * range, no self-reference/duplicates); install-time resolution/cycles belong to the P1 lifecycle.
 */
data class AgentToolPackageDependency(
    val packageId: String,
    val versionRange: String
)

enum class AgentToolPackageSourceKind { ZIP }

/** Where a previewed package came from, plus the bytes actually copied into the private store. */
data class AgentToolPackageSource(
    val sourceUri: String,
    val kind: AgentToolPackageSourceKind,
    val archiveSizeBytes: Long,
    val archiveSha256: String
) {
    init {
        require(sourceUri.isNotBlank() && sourceUri.length <= AgentToolPackageLimits.MAX_SOURCE_URI_CHARS) {
            "tool-package source URI is outside the bounded range"
        }
        require(sourceUri.none { it.isISOControl() }) { "tool-package source URI contains control characters" }
        require(archiveSizeBytes in 0L..AgentToolPackageLimits.MAX_ARCHIVE_BYTES) {
            "tool-package source size is outside the bounded range"
        }
        require(AgentToolPackageToolRef.isValidSha256(archiveSha256)) {
            "tool-package source hash must be lowercase SHA-256"
        }
    }
}

/**
 * Strict, bounded semantic version used for `packageVersion` and dependency ranges.
 *
 * Comparison is explicit (no locale/string-order guesswork): numeric core fields compare
 * numerically and prerelease identifiers follow SemVer 2.0 rules. Build metadata is not accepted.
 */
internal data class ToolPackageVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String?
) : Comparable<ToolPackageVersion> {

    val text: String
        get() = if (prerelease == null) "$major.$minor.$patch" else "$major.$minor.$patch-$prerelease"

    override fun compareTo(other: ToolPackageVersion): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        if (prerelease == null && other.prerelease == null) return 0
        if (prerelease == null) return 1
        if (other.prerelease == null) return -1
        return comparePrerelease(prerelease, other.prerelease)
    }

    companion object {
        private val PATTERN = Regex(
            "^(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
        )
        private const val MAX_PART = 9_999

        fun parseOrNull(raw: String): ToolPackageVersion? {
            if (raw.length > AgentToolPackageLimits.MAX_VERSION_CHARS) return null
            val match = PATTERN.matchEntire(raw) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            if (major > MAX_PART || minor > MAX_PART || patch > MAX_PART) return null
            val prerelease = match.groupValues[4].ifEmpty { null }
            return ToolPackageVersion(major, minor, patch, prerelease)
        }

        fun requireVersion(raw: String, field: String): ToolPackageVersion =
            parseOrNull(raw) ?: toolPackageFailure(
                AgentToolPackageErrorCode.MANIFEST_INVALID_VERSION,
                "$field must be a bounded semantic version (MAJOR.MINOR.PATCH[-prerelease])"
            )

        private fun comparePrerelease(left: String, right: String): Int {
            val leftParts = left.split('.')
            val rightParts = right.split('.')
            val shared = minOf(leftParts.size, rightParts.size)
            for (index in 0 until shared) {
                val comparison = comparePrereleaseIdentifier(leftParts[index], rightParts[index])
                if (comparison != 0) return comparison
            }
            return leftParts.size.compareTo(rightParts.size)
        }

        private fun comparePrereleaseIdentifier(left: String, right: String): Int {
            val leftNumeric = left.all { it in '0'..'9' }
            val rightNumeric = right.all { it in '0'..'9' }
            return when {
                leftNumeric && rightNumeric -> {
                    val leftValue = left.toBigIntegerOrNull()
                    val rightValue = right.toBigIntegerOrNull()
                    if (leftValue == null || rightValue == null) {
                        left.compareTo(right)
                    } else {
                        leftValue.compareTo(rightValue)
                    }
                }
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
        }
    }
}

/**
 * Dependency range accepted by P0. Resolution is deferred, but range syntax is explicit and the
 * comparison uses [ToolPackageVersion], not string ordering.
 */
internal sealed interface ToolPackageVersionRange {
    fun matches(version: ToolPackageVersion): Boolean

    data object AnyVersion : ToolPackageVersionRange {
        override fun matches(version: ToolPackageVersion): Boolean = true
    }

    data class Exact(val version: ToolPackageVersion) : ToolPackageVersionRange {
        override fun matches(version: ToolPackageVersion): Boolean = version.compareTo(this.version) == 0
    }

    data class AtLeast(val version: ToolPackageVersion) : ToolPackageVersionRange {
        override fun matches(version: ToolPackageVersion): Boolean = version.compareTo(this.version) >= 0
    }

    data class AtMost(val version: ToolPackageVersion) : ToolPackageVersionRange {
        override fun matches(version: ToolPackageVersion): Boolean = version.compareTo(this.version) <= 0
    }

    companion object {
        fun parseOrNull(raw: String): ToolPackageVersionRange? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > AgentToolPackageLimits.MAX_VERSION_CHARS + 2) return null
            if (trimmed == "*") return AnyVersion
            if (trimmed.startsWith(">=")) {
                return ToolPackageVersion.parseOrNull(trimmed.substring(2))?.let(::AtLeast)
            }
            if (trimmed.startsWith("<=")) {
                return ToolPackageVersion.parseOrNull(trimmed.substring(2))?.let(::AtMost)
            }
            return ToolPackageVersion.parseOrNull(trimmed)?.let(::Exact)
        }
    }
}

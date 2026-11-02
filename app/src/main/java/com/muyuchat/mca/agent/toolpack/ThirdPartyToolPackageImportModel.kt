package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolPackageToolRef
import java.security.MessageDigest

/**
 * Compatibility classes of the third-party import report (proposal 5.5).
 *
 * The four values are deliberately identical to the acceptance vocabulary:
 * - [SUPPORTED]: the declared surface is understood and can be used by MCA as-is;
 * - [TRANSLATED]: understood, but the descriptor stores a normalised MCA value (id/version default);
 * - [REQUIRES_HOST_CAPABILITY]: understood, but MCA has no host port/runtime for it yet;
 * - [UNSUPPORTED]: understood, but MCA has no compatible runtime and must not pretend to run it.
 *
 * [isBlocking] is true for everything that keeps a package from becoming executable. A preview can
 * still be inspected, but the import result never enables or installs the package.
 */
enum class ThirdPartyCompatibilityStatus {
    SUPPORTED,
    TRANSLATED,
    REQUIRES_HOST_CAPABILITY,
    UNSUPPORTED;

    val isBlocking: Boolean
        get() = this == REQUIRES_HOST_CAPABILITY || this == UNSUPPORTED
}

/** The four third-party execution contexts fixed by the upstream format. */
enum class ThirdPartyExecutionContext(val wireName: String) {
    MAIN("main"),
    UI("ui"),
    SANDBOX("sandbox"),
    PROVIDER("provider");

    companion object {
        /** Returns null for any context name that is not one of the four documented contexts. */
        fun fromWireName(raw: String): ThirdPartyExecutionContext? =
            entries.firstOrNull { it.wireName == raw }
    }
}

/** Report dimensions; used only for display/grouping, never for admission decisions. */
enum class ThirdPartyCompatibilityCategory {
    FORMAT,
    IDENTITY,
    VERSION,
    API,
    ENTRY,
    CONTEXT,
    DEPENDENCY,
    RESOURCE,
    WASM,
    TEMPLATE
}

/**
 * One declared third-party surface and how MCA treats it.
 *
 * [symbol] is the stable, content-bounded symbol a reviewer can search for (a manifest key, a
 * resource key, a subpackage id or a template id). [fieldPath] points at the exact manifest/listing
 * location. [alternative] states what would make the item usable; it is never a promise that the
 * capability exists.
 */
data class ThirdPartyCompatibilityFinding(
    val category: ThirdPartyCompatibilityCategory,
    val symbol: String,
    val fieldPath: String,
    val status: ThirdPartyCompatibilityStatus,
    val reason: String,
    val alternative: String? = null
) {
    init {
        require(symbol.isNotBlank() && symbol.length <= MAX_SYMBOL_CHARS) { "compatibility symbol is invalid" }
        require(fieldPath.isNotBlank() && fieldPath.length <= MAX_PATH_CHARS) { "compatibility path is invalid" }
        require(reason.isNotBlank()) { "compatibility reason must not be blank" }
        require(symbol.none { it.isISOControl() }) { "compatibility symbol contains control characters" }
        require(fieldPath.none { it.isISOControl() }) { "compatibility path contains control characters" }
    }

    private companion object {
        const val MAX_SYMBOL_CHARS = 256
        const val MAX_PATH_CHARS = 512
    }
}

/** One of the four contexts and whether this package declares it in a statically visible way. */
data class ThirdPartyContextCompatibility(
    val context: ThirdPartyExecutionContext,
    val declared: Boolean,
    val status: ThirdPartyCompatibilityStatus,
    val reason: String,
    val alternative: String? = null
) {
    init {
        require(reason.isNotBlank()) { "context compatibility reason must not be blank" }
    }

    /** Only a *declared* context that MCA cannot host blocks the package. */
    val isBlocking: Boolean get() = declared && status.isBlocking
}

/**
 * Per-field compatibility report produced by the import preview.
 *
 * [runnable] is false whenever a declared surface needs a host capability MCA does not have, and it
 * is always false for a third-party package in this slice: the main context itself has no MCA runtime.
 * The flag is advisory for the admin UI; the import result has no install/enable operation at all.
 */
data class ThirdPartyCompatibilityReport(
    val findings: List<ThirdPartyCompatibilityFinding>,
    val contexts: List<ThirdPartyContextCompatibility>
) {
    init {
        require(contexts.map { it.context }.distinct().size == contexts.size) {
            "compatibility report repeats a context"
        }
    }

    val blockingFindings: List<ThirdPartyCompatibilityFinding>
        get() = findings.filter { it.status.isBlocking }

    val blockingContexts: List<ThirdPartyContextCompatibility>
        get() = contexts.filter { it.isBlocking }

    val runnable: Boolean
        get() = blockingFindings.isEmpty() && blockingContexts.isEmpty()

    /** Worst status across findings and declared contexts, for a single summary label. */
    val summaryStatus: ThirdPartyCompatibilityStatus
        get() {
            val relevant = findings.map { it.status } + contexts.filter { it.declared }.map { it.status }
            return when {
                relevant.any { it == ThirdPartyCompatibilityStatus.UNSUPPORTED } -> ThirdPartyCompatibilityStatus.UNSUPPORTED
                relevant.any { it == ThirdPartyCompatibilityStatus.REQUIRES_HOST_CAPABILITY } ->
                    ThirdPartyCompatibilityStatus.REQUIRES_HOST_CAPABILITY
                relevant.any { it == ThirdPartyCompatibilityStatus.TRANSLATED } -> ThirdPartyCompatibilityStatus.TRANSLATED
                else -> ThirdPartyCompatibilityStatus.SUPPORTED
            }
        }

    fun findingsFor(symbol: String): List<ThirdPartyCompatibilityFinding> =
        findings.filter { it.symbol == symbol }

    fun context(context: ThirdPartyExecutionContext): ThirdPartyContextCompatibility =
        contexts.first { it.context == context }
}

/** Detected source syntax of the third-party manifest text. */
enum class ThirdPartyManifestFormat {
    JSON,
    HJSON
}

/** One entry of the caller-supplied, already-safe package listing (no ZIP parsing happens here). */
data class ThirdPartyListedEntry(
    val path: String,
    val isDirectory: Boolean = false,
    val sizeBytes: Long = 0L,
    /** Optional content hash; when present it participates in the stable content fingerprint. */
    val sha256: String? = null
)

/**
 * Archives/directories are listed by the caller, not parsed by this adapter. The archive overload of
 * the adapter fills this from the existing hardened ZIP reader.
 */
data class ThirdPartyPackageListing(
    val entries: List<ThirdPartyListedEntry> = emptyList()
) {
    companion object {
        fun paths(vararg paths: String): ThirdPartyPackageListing =
            ThirdPartyPackageListing(paths.map { ThirdPartyListedEntry(it) })
    }
}

/**
 * A context a caller has *statically* discovered (for example from a registration scan). The
 * adapter never executes registration; an unknown name is a typed rejection, never a guess.
 * Validation happens in the adapter so a malformed declaration also returns a typed rejection.
 */
data class ThirdPartyDeclaredContext(
    val name: String,
    val fieldPath: String
)

data class ThirdPartySubpackageDescriptor(
    val id: String,
    val entry: String,
    val enabledByDefault: Boolean
)

data class ThirdPartyResourceDescriptor(
    val key: String,
    val path: String,
    val mime: String?,
    val isDirectory: Boolean
)

data class ThirdPartyWasmModuleDescriptor(
    val id: String,
    val path: String,
    val exports: List<String>,
    val abi: String?,
    val sourceLanguage: String?
)

data class ThirdPartyTemplateDescriptor(
    val id: String,
    val resourceKey: String,
    val projectType: String?
)

data class ThirdPartyDependencyDescriptor(
    val id: String,
    val minVersion: String?,
    val maxVersion: String?
)

/**
 * Normalised, read-only view of a third-party ToolPkg manifest plus its declared resources.
 *
 * This is **not** an MCA executable v2 manifest: [executable] is computed false, there is no
 * translation to host APIs, and no package tool key is registered. [manifestSha256] is the canonical
 * manifest hash; [contentSha256] covers the canonical manifest hash and the sorted, normalised
 * listing so repeat previews of identical input are stable.
 */
data class ThirdPartyToolPackageDescriptor(
    val manifestFormat: ThirdPartyManifestFormat,
    val toolPkgId: String,
    val normalizedPackageId: String,
    val version: String,
    val apiVersion: String,
    val displayName: String,
    val description: String,
    val mainEntry: String,
    val subpackages: List<ThirdPartySubpackageDescriptor>,
    val resources: List<ThirdPartyResourceDescriptor>,
    val wasmModules: List<ThirdPartyWasmModuleDescriptor>,
    val workflowTemplates: List<ThirdPartyTemplateDescriptor>,
    val workspaceTemplates: List<ThirdPartyTemplateDescriptor>,
    val dependencies: List<ThirdPartyDependencyDescriptor>,
    val manifestSha256: String,
    val contentSha256: String
) {
    init {
        require(toolPkgId.isNotBlank()) { "descriptor toolpkg id must not be blank" }
        require(AgentToolPackageToolRef.isValidPackageId(normalizedPackageId)) {
            "descriptor normalised package id is invalid"
        }
        require(AgentToolPackageToolRef.isValidSha256(manifestSha256)) {
            "descriptor manifest hash is not SHA-256"
        }
        require(AgentToolPackageToolRef.isValidSha256(contentSha256)) {
            "descriptor content hash is not SHA-256"
        }
    }

    /** Import preview only: no install, enable or run action exists for this descriptor. */
    val previewOnly: Boolean get() = true

    /** This slice never presents a third-party package as an executable MCA package. */
    val executable: Boolean get() = false
}

/** Outcome of one third-party import preview. A rejection carries a stable code and exact field path. */
sealed interface ThirdPartyToolPackageImportResult {
    data class Previewed(
        val descriptor: ThirdPartyToolPackageDescriptor,
        val report: ThirdPartyCompatibilityReport
    ) : ThirdPartyToolPackageImportResult

    data class Rejected(
        val code: ThirdPartyImportErrorCode,
        val fieldPath: String,
        val safeMessage: String
    ) : ThirdPartyToolPackageImportResult
}

/**
 * Stable third-party-import failure vocabulary. [wireCode] is what the admin surface may branch on; it is
 * UPPER_SNAKE_CASE and never contains manifest content.
 */
enum class ThirdPartyImportErrorCode(val wireCode: String) {
    MANIFEST_MALFORMED("THIRD_PARTY_MANIFEST_MALFORMED"),
    MANIFEST_TOO_LARGE("THIRD_PARTY_MANIFEST_TOO_LARGE"),
    MANIFEST_DUPLICATE_KEY("THIRD_PARTY_MANIFEST_DUPLICATE_KEY"),
    MISSING_FIELD("THIRD_PARTY_MISSING_FIELD"),
    INVALID_FIELD("THIRD_PARTY_INVALID_FIELD"),
    UNKNOWN_FIELD("THIRD_PARTY_UNKNOWN_FIELD"),
    FORBIDDEN_FIELD("THIRD_PARTY_FORBIDDEN_FIELD"),
    INVALID_ID("THIRD_PARTY_INVALID_ID"),
    UNSUPPORTED_SCHEMA_VERSION("THIRD_PARTY_UNSUPPORTED_SCHEMA_VERSION"),
    UNSUPPORTED_API_VERSION("THIRD_PARTY_UNSUPPORTED_API_VERSION"),
    UNKNOWN_CONTEXT("THIRD_PARTY_UNKNOWN_CONTEXT"),
    UNSUPPORTED_ENTRY("THIRD_PARTY_UNSUPPORTED_ENTRY"),
    ENTRY_MISSING("THIRD_PARTY_ENTRY_MISSING"),
    RESOURCE_MISSING("THIRD_PARTY_RESOURCE_MISSING"),
    DUPLICATE_ENTRY("THIRD_PARTY_DUPLICATE_ENTRY"),
    INVALID_PATH("THIRD_PARTY_INVALID_PATH"),
    ARCHIVE_INVALID("THIRD_PARTY_ARCHIVE_INVALID")
}

/** Internal typed rejection; every public entry point converts it to [ThirdPartyToolPackageImportResult]. */
internal class ThirdPartyImportRejection(
    val code: ThirdPartyImportErrorCode,
    val fieldPath: String,
    message: String
) : Exception(message)

internal fun thirdpartyFailure(
    code: ThirdPartyImportErrorCode,
    fieldPath: String,
    message: String
): Nothing = throw ThirdPartyImportRejection(code, fieldPath, message)

/** Deterministic identity of the canonical manifest plus the sorted normalised listing. */
internal object ThirdPartyContentHash {
    fun compute(manifestSha256: String, entries: List<ThirdPartyListedEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN.toByteArray(Charsets.UTF_8))
        digest.update(manifestSha256.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        entries.sortedWith(compareBy<ThirdPartyListedEntry> { it.path }.thenBy { it.isDirectory })
            .forEach { entry ->
                digest.update(entry.path.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(if (entry.isDirectory) 1.toByte() else 0.toByte())
                digest.update(le64(entry.sizeBytes))
                digest.update((entry.sha256 ?: "").toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }
        return toHex(digest.digest())
    }

    private fun le64(value: Long): ByteArray = ByteArray(8) { index ->
        ((value ushr (8 * index)) and 0xFFL).toByte()
    }

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private const val DOMAIN = "MCA-THIRD_PARTY-IMPORT\n"
    private const val HEX = "0123456789abcdef"
}

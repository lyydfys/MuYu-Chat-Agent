package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolPackageToolRef
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageException
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageLimits
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackagePathPolicy

/** Caller-supplied listing normalised against the existing package path policy. */
internal data class ThirdPartyNormalizedListing(
    val files: Map<String, ThirdPartyListedEntry>,
    val directories: Map<String, ThirdPartyListedEntry>,
    val entries: List<ThirdPartyListedEntry>
) {
    fun hasFile(path: String): Boolean = files.containsKey(path)

    fun hasDirectory(path: String): Boolean =
        directories.containsKey(path) || files.keys.any { it.startsWith("$path/") }

    fun containsPath(path: String): Boolean =
        files.containsKey(path) || directories.containsKey(path)
}

/**
 * Validates a listing without opening, extracting or executing anything.
 *
 * Paths are normalised by the same policy the hardened ZIP reader uses
 * ([AgentToolPackagePathPolicy]); the adapter never invents a second path grammar. ZIP-level
 * rejection (traversal, symlinks, encryption, duplicate central-directory names, bombs) happens in
 * [com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageZipArchive] before a listing exists.
 */
internal class ThirdPartyPackageListingReader {

    fun read(listing: ThirdPartyPackageListing): ThirdPartyNormalizedListing {
        if (listing.entries.size > AgentToolPackageLimits.MAX_ENTRIES) {
            thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_FIELD,
                "listing",
                "package listing exceeds ${AgentToolPackageLimits.MAX_ENTRIES} entries"
            )
        }
        val files = LinkedHashMap<String, ThirdPartyListedEntry>()
        val directories = LinkedHashMap<String, ThirdPartyListedEntry>()
        val normalizedEntries = ArrayList<ThirdPartyListedEntry>(listing.entries.size)
        listing.entries.forEachIndexed { index, raw ->
            val field = "listing[$index]"
            if (raw.sizeBytes < 0L) {
                thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, "$field.sizeBytes", "listed size is negative")
            }
            val sha256 = raw.sha256
            if (sha256 != null && !AgentToolPackageToolRef.isValidSha256(sha256)) {
                thirdpartyFailure(
                    ThirdPartyImportErrorCode.INVALID_FIELD,
                    "$field.sha256",
                    "listed content hash is not lowercase SHA-256"
                )
            }
            val isDirectory = raw.isDirectory || raw.path.endsWith('/')
            val path = try {
                if (isDirectory) {
                    AgentToolPackagePathPolicy.normalizeDirectory(raw.path)
                } else {
                    AgentToolPackagePathPolicy.normalizeFile(raw.path)
                }
            } catch (_: AgentToolPackageException) {
                thirdpartyFailure(
                    ThirdPartyImportErrorCode.INVALID_PATH,
                    field,
                    "listed path is not a normalised relative package path"
                )
            }
            if (files.containsKey(path) || directories.containsKey(path)) {
                thirdpartyFailure(ThirdPartyImportErrorCode.DUPLICATE_ENTRY, field, "listing repeats a path")
            }
            val entry = ThirdPartyListedEntry(path, isDirectory, raw.sizeBytes, sha256)
            if (isDirectory) directories[path] = entry else files[path] = entry
            normalizedEntries += entry
        }
        return ThirdPartyNormalizedListing(files, directories, normalizedEntries)
    }
}

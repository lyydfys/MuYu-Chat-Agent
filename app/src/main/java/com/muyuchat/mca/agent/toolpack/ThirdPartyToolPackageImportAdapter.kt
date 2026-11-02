package com.muyuchat.mca.agent.toolpack

import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageErrorCode
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageException
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageLimits
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageZipArchive
import com.muyuchat.mca.agent.extensions.toolpkg.ByteArrayToolPackageSource
import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJson
import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJsonValue
import java.io.InputStream

/**
 * third-party ToolPkg import adapter (design 5.5, acceptance PKG-01/PKG-03).
 *
 * The adapter accepts a JSON or HJSON manifest plus a resource listing and produces a normalised
 * descriptor with a per-symbol compatibility report. It never executes manifest content, never
 * extracts a ZIP, never resolves a dependency, never touches user data and never enables a package.
 *
 * ZIP-level admission is delegated to the existing hardened reader
 * ([AgentToolPackageZipArchive]): traversal, symlinks, encryption, unknown compression, duplicate
 * names, ZIP64 and expansion bombs are rejected there before a listing reaches this adapter. The
 * only new parsing here is the documented HJSON syntax subset, which is normalised to strict JSON
 * and then read by the existing [ToolPackageJson] parser.
 */
class ThirdPartyToolPackageImportAdapter {

    fun preview(
        manifestText: String,
        listing: ThirdPartyPackageListing = ThirdPartyPackageListing(),
        declaredContexts: List<ThirdPartyDeclaredContext> = emptyList()
    ): ThirdPartyToolPackageImportResult = previewInternal(
        manifestText = manifestText,
        listing = listing,
        declaredContexts = declaredContexts,
        forcedFormat = null
    )

    /**
     * Previews a `.toolpkg` ZIP without extracting it.
     *
     * The root must contain exactly one of `manifest.json` / `manifest.hjson`. The returned listing
     * describes the remaining entries; directory entries stay structural and no bytes are written.
     */
    fun previewArchive(archiveBytes: ByteArray): ThirdPartyToolPackageImportResult {
        if (archiveBytes.isEmpty()) {
            return rejected(ThirdPartyImportErrorCode.ARCHIVE_INVALID, "archive", "the package archive is empty")
        }
        if (archiveBytes.size.toLong() > AgentToolPackageLimits.MAX_ARCHIVE_BYTES) {
            return rejected(
                ThirdPartyImportErrorCode.ARCHIVE_INVALID,
                "archive",
                "the package archive exceeds the archive size budget"
            )
        }
        return try {
            AgentToolPackageZipArchive(ByteArrayToolPackageSource(archiveBytes)).use { archive ->
                val entries = archive.entries()
                val jsonEntry = entries.firstOrNull { !it.isDirectory && it.path == JSON_MANIFEST }
                val hjsonEntry = entries.firstOrNull { !it.isDirectory && it.path == HJSON_MANIFEST }
                if ((jsonEntry == null) == (hjsonEntry == null)) {
                    return rejected(
                        ThirdPartyImportErrorCode.ARCHIVE_INVALID,
                        "manifest",
                        "the archive root must contain exactly one third-party manifest"
                    )
                }
                val manifestEntry = requireNotNull(jsonEntry ?: hjsonEntry)
                val manifestText = ToolPackageJson.decodeUtf8(
                    readBounded(archive.openStream(manifestEntry))
                )
                val listing = ThirdPartyPackageListing(
                    entries.asSequence()
                        .filter { it !== manifestEntry }
                        .map { ThirdPartyListedEntry(it.path, it.isDirectory, it.uncompressedSize) }
                        .toList()
                )
                previewInternal(
                    manifestText = manifestText,
                    listing = listing,
                    declaredContexts = emptyList(),
                    forcedFormat = if (jsonEntry != null) ThirdPartyManifestFormat.JSON else ThirdPartyManifestFormat.HJSON
                )
            }
        } catch (rejection: ThirdPartyImportRejection) {
            rejected(rejection)
        } catch (failure: AgentToolPackageException) {
            when (failure.error.code) {
                AgentToolPackageErrorCode.MANIFEST_DUPLICATE_KEY -> rejected(
                    ThirdPartyImportErrorCode.MANIFEST_DUPLICATE_KEY,
                    "manifest",
                    "the manifest repeats an object key"
                )
                AgentToolPackageErrorCode.MANIFEST_INVALID_UTF8,
                AgentToolPackageErrorCode.MANIFEST_MALFORMED -> rejected(
                    ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                    "manifest",
                    "the manifest is not a valid third-party document"
                )
                else -> rejected(
                    ThirdPartyImportErrorCode.ARCHIVE_INVALID,
                    "archive",
                    "the package archive was rejected (${failure.error.code.name})"
                )
            }
        } catch (_: Exception) {
            rejected(
                ThirdPartyImportErrorCode.ARCHIVE_INVALID,
                "archive",
                "the package archive could not be inspected"
            )
        }
    }

    private fun previewInternal(
        manifestText: String,
        listing: ThirdPartyPackageListing,
        declaredContexts: List<ThirdPartyDeclaredContext>,
        forcedFormat: ThirdPartyManifestFormat?
    ): ThirdPartyToolPackageImportResult = try {
        val bytes = manifestText.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > AgentToolPackageLimits.MAX_MANIFEST_BYTES) {
            thirdpartyFailure(
                ThirdPartyImportErrorCode.MANIFEST_TOO_LARGE,
                "manifest",
                "the manifest exceeds the manifest size budget"
            )
        }
        val normalizedListing = ThirdPartyPackageListingReader().read(listing)
        val (format, root) = parseDocument(manifestText, forcedFormat)
        val canonical = ToolPackageJson.canonical(root)
        val manifestSha256 = ToolPackageJson.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
        ThirdPartyManifestImportReader().read(
            root = root,
            manifestFormat = format,
            manifestSha256 = manifestSha256,
            listing = normalizedListing,
            declaredContexts = declaredContexts
        )
    } catch (rejection: ThirdPartyImportRejection) {
        rejected(rejection)
    } catch (_: Exception) {
        rejected(
            ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
            "manifest",
            "the manifest could not be validated"
        )
    }

    private fun parseDocument(
        text: String,
        forcedFormat: ThirdPartyManifestFormat?
    ): Pair<ThirdPartyManifestFormat, ToolPackageJsonValue.ObjectValue> {
        if (forcedFormat == ThirdPartyManifestFormat.HJSON) {
            return ThirdPartyManifestFormat.HJSON to asRoot(
                strictParse(ThirdPartyHjsonManifest.toStrictJson(text))
            )
        }
        if (forcedFormat == ThirdPartyManifestFormat.JSON) {
            return ThirdPartyManifestFormat.JSON to asRoot(strictParse(text))
        }
        return try {
            ThirdPartyManifestFormat.JSON to asRoot(strictParse(text))
        } catch (rejection: ThirdPartyImportRejection) {
            if (rejection.code == ThirdPartyImportErrorCode.MANIFEST_DUPLICATE_KEY) throw rejection
            ThirdPartyManifestFormat.HJSON to asRoot(strictParse(ThirdPartyHjsonManifest.toStrictJson(text)))
        }
    }

    private fun strictParse(text: String): ToolPackageJsonValue = try {
        ToolPackageJson.parse(text)
    } catch (failure: AgentToolPackageException) {
        val code = when (failure.error.code) {
            AgentToolPackageErrorCode.MANIFEST_DUPLICATE_KEY -> ThirdPartyImportErrorCode.MANIFEST_DUPLICATE_KEY
            AgentToolPackageErrorCode.MANIFEST_TOO_LARGE -> ThirdPartyImportErrorCode.MANIFEST_TOO_LARGE
            else -> ThirdPartyImportErrorCode.MANIFEST_MALFORMED
        }
        thirdpartyFailure(code, "manifest", "the manifest is not a well-formed third-party document")
    }

    private fun asRoot(value: ToolPackageJsonValue): ToolPackageJsonValue.ObjectValue =
        value as? ToolPackageJsonValue.ObjectValue
            ?: thirdpartyFailure(
                ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                "manifest",
                "the third-party manifest root must be an object"
            )

    private fun readBounded(input: InputStream): ByteArray {
        input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > AgentToolPackageLimits.MAX_MANIFEST_BYTES) {
                    thirdpartyFailure(
                        ThirdPartyImportErrorCode.MANIFEST_TOO_LARGE,
                        "manifest",
                        "the manifest exceeds the manifest size budget"
                    )
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun rejected(rejection: ThirdPartyImportRejection): ThirdPartyToolPackageImportResult.Rejected =
        rejected(rejection.code, rejection.fieldPath, rejection.message.orEmpty())

    private fun rejected(
        code: ThirdPartyImportErrorCode,
        fieldPath: String,
        message: String
    ): ThirdPartyToolPackageImportResult.Rejected = ThirdPartyToolPackageImportResult.Rejected(
        code = code,
        fieldPath = safeFieldPath(fieldPath),
        safeMessage = safeMessage(message, code)
    )

    private fun safeFieldPath(raw: String): String = buildString {
        for (character in raw.take(512)) {
            append(if (character.isISOControl()) '?' else character)
        }
    }.ifBlank { "manifest" }

    private fun safeMessage(raw: String, code: ThirdPartyImportErrorCode): String = buildString {
        for (character in raw.take(512)) {
            append(if (character.isISOControl()) ' ' else character)
        }
    }.trim().ifBlank { code.wireCode }

    internal companion object {
        const val JSON_MANIFEST = "manifest.json"
        const val HJSON_MANIFEST = "manifest.hjson"
    }
}

package com.muyuchat.mca.agent.toolpack

import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageErrorCode
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageException
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageLimits
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageZipArchive
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageZipEntry
import com.muyuchat.mca.agent.extensions.toolpkg.ByteArrayToolPackageSource
import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJson
import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJsonValue
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads which format a package archive declares, and nothing else.
 *
 * Each of the three formats declares itself: an MCA manifest states `schemaVersion` (1 = legacy inert
 * preview, 2 = executable), and a third-party package is a root `manifest.json`/`manifest.hjson`. This
 * classifier reads exactly that declaration. It never infers a format from which fields happen to be
 * present, never parses a tool list, never extracts an entry to disk and never executes package
 * content. An archive that does not declare exactly one supported format is rejected, not guessed.
 *
 * The per-format parsers stay the owners of their grammars: [AgentToolPackageManifestParser] for v1,
 * [ExecutableToolPackageManifestParser] for v2 and [ThirdPartyToolPackageImportAdapter] for third-party. This
 * class only tells an import surface which of them may be asked.
 */
object ToolPackageFormatClassifier {

    enum class Format { INERT_V1, EXECUTABLE_V2, THIRD_PARTY }

    sealed interface Result {
        data class Classified(val format: Format, val declaration: String) : Result

        data class Rejected(val error: ToolPackageError) : Result
    }

    private const val MCA_MANIFEST: String = ToolPackageArchive.MANIFEST_FILE_NAME

    fun classify(archiveBytes: ByteArray): Result {
        if (archiveBytes.isEmpty()) {
            return rejected(ToolPackageErrorCode.ARCHIVE_INVALID, "The package archive is empty.")
        }
        if (archiveBytes.size.toLong() > AgentToolPackageLimits.MAX_ARCHIVE_BYTES) {
            return rejected(
                ToolPackageErrorCode.ARCHIVE_INVALID,
                "The package archive exceeds the archive size budget."
            )
        }
        return try {
            AgentToolPackageZipArchive(ByteArrayToolPackageSource(archiveBytes)).use { archive ->
                classifyEntries(archive, archive.entries().filterNot { it.isDirectory })
            }
        } catch (_: AgentToolPackageException) {
            rejected(
                ToolPackageErrorCode.ARCHIVE_INVALID,
                "The package archive is not a readable ZIP container."
            )
        }
    }

    private fun classifyEntries(
        archive: AgentToolPackageZipArchive,
        entries: List<AgentToolPackageZipEntry>
    ): Result {
        val mcaEntry = entries.firstOrNull { it.path == MCA_MANIFEST }
        val thirdpartyEntries = entries.filter {
            it.path == ThirdPartyToolPackageImportAdapter.JSON_MANIFEST ||
                it.path == ThirdPartyToolPackageImportAdapter.HJSON_MANIFEST
        }
        if (mcaEntry != null && thirdpartyEntries.isNotEmpty()) {
            return rejected(
                ToolPackageErrorCode.MANIFEST_FORMAT_AMBIGUOUS,
                "The archive declares both an MCA manifest and a third-party manifest."
            )
        }
        if (thirdpartyEntries.size > 1) {
            return rejected(
                ToolPackageErrorCode.MANIFEST_FORMAT_AMBIGUOUS,
                "The archive declares more than one third-party manifest."
            )
        }
        if (thirdpartyEntries.size == 1) {
            return Result.Classified(Format.THIRD_PARTY, "root ${thirdpartyEntries.single().path}")
        }
        if (mcaEntry == null) {
            return rejected(
                ToolPackageErrorCode.ARCHIVE_MISSING_MANIFEST,
                "The archive root does not declare a package manifest."
            )
        }
        return classifyMcaManifest(archive, mcaEntry)
    }

    private fun classifyMcaManifest(
        archive: AgentToolPackageZipArchive,
        entry: AgentToolPackageZipEntry
    ): Result {
        val bytes = try {
            readBounded(archive.openStream(entry), ToolPackageLimits.MAX_MANIFEST_BYTES)
        } catch (_: AgentToolPackageException) {
            return rejected(
                ToolPackageErrorCode.ARCHIVE_INVALID,
                "The package manifest entry could not be read."
            )
        } ?: return rejected(
            ToolPackageErrorCode.MANIFEST_TOO_LARGE,
            "The package manifest exceeds the manifest size budget."
        )
        val root = try {
            ToolPackageJson.parse(ToolPackageJson.decodeUtf8(bytes))
        } catch (failure: AgentToolPackageException) {
            return rejected(
                manifestJsonCode(failure),
                "The package manifest is not well-formed strict JSON."
            )
        }
        if (root !is ToolPackageJsonValue.ObjectValue) {
            return rejected(
                ToolPackageErrorCode.MANIFEST_MALFORMED,
                "The package manifest is not a JSON object."
            )
        }
        val declared = root.fields["schemaVersion"] as? ToolPackageJsonValue.IntegerValue
            ?: return rejected(
                ToolPackageErrorCode.MANIFEST_INVALID_FIELD,
                "The package manifest must declare an integer schemaVersion."
            )
        return when (declared.value) {
            AgentToolPackageLimits.SCHEMA_VERSION.toLong() ->
                Result.Classified(Format.INERT_V1, "schemaVersion ${declared.value}")
            ToolPackageLimits.FORMAT_VERSION.toLong() ->
                Result.Classified(Format.EXECUTABLE_V2, "schemaVersion ${declared.value}")
            else -> rejected(
                ToolPackageErrorCode.MANIFEST_UNSUPPORTED_FORMAT,
                "The package manifest declares an unsupported schemaVersion."
            )
        }
    }

    private fun readBounded(stream: InputStream, limit: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) return null
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun manifestJsonCode(failure: AgentToolPackageException): ToolPackageErrorCode =
        when (failure.error.code) {
            AgentToolPackageErrorCode.MANIFEST_DUPLICATE_KEY -> ToolPackageErrorCode.MANIFEST_DUPLICATE_KEY
            AgentToolPackageErrorCode.MANIFEST_INVALID_UTF8 -> ToolPackageErrorCode.MANIFEST_INVALID_UTF8
            AgentToolPackageErrorCode.MANIFEST_TOO_LARGE -> ToolPackageErrorCode.MANIFEST_TOO_LARGE
            else -> ToolPackageErrorCode.MANIFEST_MALFORMED
        }

    private fun rejected(code: ToolPackageErrorCode, message: String): Result =
        Result.Rejected(ToolPackageError(code, message))
}

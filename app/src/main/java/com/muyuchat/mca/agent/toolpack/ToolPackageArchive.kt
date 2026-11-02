package com.muyuchat.mca.agent.toolpack

import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageException
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageLimits
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageZipArchive
import com.muyuchat.mca.agent.extensions.toolpkg.ByteArrayToolPackageSource
import java.io.IOException
import java.io.InputStream

/**
 * Strict ZIP admission for an executable package (format v2).
 *
 * The archive reader itself is the hardened v1 reader ([AgentToolPackageZipArchive]): it parses the
 * central directory, rejects ZIP64/encryption/duplicates/symlinks/devices/unknown compression,
 * enforces entry-count and expanded-size limits and refuses any entry whose name escapes the
 * archive. On top of that this class applies the **v2 content policy**:
 *
 * - the manifest must be exactly one entry at the archive root named
 *   [AgentToolPackageLimits.MANIFEST_FILE_NAME];
 * - every other regular file must be a normalised relative `.js` path under the strict v2 path
 *   rules ([ToolPackageScriptPaths]); directory entries are structural and ignored;
 * - any other entry type (DEX/JNI/resource/binary) is a typed rejection rather than silently
 *   dropped, so an imported archive cannot smuggle a second execution channel;
 * - every script is read with its own hard byte cap before it reaches the manifest loader.
 *
 * Nothing is extracted to disk here: the caller hands the returned snapshot to
 * [ToolPackageInstallStore.install], which writes the immutable content-addressed copy.
 */
object ToolPackageArchive {
    /** Root manifest entry name; same convention as the v1 inert package. */
    const val MANIFEST_FILE_NAME: String = AgentToolPackageLimits.MANIFEST_FILE_NAME

    class Extracted(
        val manifestBytes: ByteArray,
        val scripts: Map<String, String>
    )

    /**
     * Parses one archive into a manifest blob plus script sources.
     *
     * @throws ToolPackageException with `ARCHIVE_*`, `MANIFEST_*` or `SCRIPT_*` codes; messages never
     *   contain entry names, file contents or absolute paths.
     */
    fun read(bytes: ByteArray): Extracted {
        if (bytes.isEmpty()) {
            throw ToolPackageException(
                ToolPackageError(ToolPackageErrorCode.ARCHIVE_INVALID, "The package archive is empty.")
            )
        }
        if (bytes.size.toLong() > AgentToolPackageLimits.MAX_ARCHIVE_BYTES) {
            throw ToolPackageException(
                ToolPackageError(
                    ToolPackageErrorCode.ARCHIVE_INVALID,
                    "The package archive exceeds the archive size budget."
                )
            )
        }
        try {
            AgentToolPackageZipArchive(ByteArrayToolPackageSource(bytes)).use { archive ->
                val entries = archive.entries()
                val manifestEntry = entries.firstOrNull {
                    !it.isDirectory && it.path == MANIFEST_FILE_NAME
                } ?: throw ToolPackageException(
                    ToolPackageError(
                        ToolPackageErrorCode.ARCHIVE_MISSING_MANIFEST,
                        "The archive root does not contain the package manifest."
                    )
                )
                val manifestBytes = readEntry(
                    archive.openStream(manifestEntry),
                    ToolPackageLimits.MAX_MANIFEST_BYTES
                )
                val scripts = LinkedHashMap<String, String>()
                var totalBytes = manifestBytes.size.toLong()
                for (entry in entries) {
                    if (entry.isDirectory) continue
                    if (entry === manifestEntry || entry.path == MANIFEST_FILE_NAME) continue
                    val normalized = ToolPackageScriptPaths.normalize(entry.path)
                        ?: throw ToolPackageException(
                            ToolPackageError(
                                ToolPackageErrorCode.ARCHIVE_ENTRY_UNSUPPORTED,
                                "The archive contains an entry that is not a package script."
                            )
                        )
                    if (scripts.containsKey(normalized)) {
                        throw ToolPackageException(
                            ToolPackageError(
                                ToolPackageErrorCode.ARCHIVE_INVALID,
                                "The archive contains two entries that normalise to the same script."
                            )
                        )
                    }
                    val source = readEntry(archive.openStream(entry), ToolPackageLimits.MAX_SCRIPT_BYTES)
                    totalBytes += source.size
                    if (totalBytes > ToolPackageLimits.MAX_SCRIPTS_TOTAL_BYTES) {
                        throw ToolPackageException(
                            ToolPackageError(
                                ToolPackageErrorCode.SCRIPT_BUNDLE_TOO_LARGE,
                                "The archive scripts exceed the total script budget."
                            )
                        )
                    }
                    if (scripts.size >= ToolPackageLimits.MAX_SCRIPTS) {
                        throw ToolPackageException(
                            ToolPackageError(
                                ToolPackageErrorCode.SCRIPT_TOO_MANY,
                                "The archive declares too many script files."
                            )
                        )
                    }
                    scripts[normalized] = String(source, Charsets.UTF_8)
                }
                if (scripts.isEmpty()) {
                    throw ToolPackageException(
                        ToolPackageError(
                            ToolPackageErrorCode.SCRIPT_MISSING,
                            "The archive contains no script files."
                        )
                    )
                }
                return Extracted(manifestBytes, scripts)
            }
        } catch (failure: AgentToolPackageException) {
            // The v1 reader already produced a stable coded rejection; only its code name is kept.
            throw ToolPackageException(
                ToolPackageError(
                    ToolPackageErrorCode.ARCHIVE_INVALID,
                    "The package archive was rejected (${failure.error.code.name})."
                )
            )
        } catch (failure: IOException) {
            throw ToolPackageException(
                ToolPackageError(ToolPackageErrorCode.ARCHIVE_INVALID, "The package archive could not be read.")
            )
        }
    }

    private fun readEntry(stream: InputStream, limitBytes: Long): ByteArray {
        stream.use { input ->
            val builder = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > limitBytes) {
                    throw ToolPackageException(
                        ToolPackageError(
                            ToolPackageErrorCode.ARCHIVE_INVALID,
                            "An archive entry exceeds the per-entry size budget."
                        )
                    )
                }
                builder.write(chunk, 0, read)
            }
            return builder.toByteArray()
        }
    }
}

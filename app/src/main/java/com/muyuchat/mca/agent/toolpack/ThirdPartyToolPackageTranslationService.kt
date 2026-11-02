package com.muyuchat.mca.agent.toolpack

import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageException
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageLimits
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageZipArchive
import com.muyuchat.mca.agent.extensions.toolpkg.ByteArrayToolPackageSource
import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJson
import java.io.InputStream

/**
 * Narrow production seam that turns a third-party ToolPkg archive into a translated MCA executable
 * package (or a typed refusal) and can install that package through the existing lifecycle.
 *
 * The service is pure CPU for [translateArchive]/[translate]; it never extracts the archive to
 * disk, never starts the runtime and never enables or executes anything. Installation is delegated
 * to [ToolPackageLifecycleService.install], so the same store, content hash, update/rollback and
 * approval policy apply as for a hand-authored MCA package.
 *
 * This is the backend for a future admin import page. The admin UI itself (the remaining PKG-23
 * UI part) is deliberately not wired in this slice.
 */
class ThirdPartyToolPackageTranslationService(
    private val previewAdapter: ThirdPartyToolPackageImportAdapter = ThirdPartyToolPackageImportAdapter(),
    private val translator: ThirdPartyExecutableTranslator = ThirdPartyExecutableTranslator()
) {

    /** Translates an already-decoded manifest + caller-supplied script map. No file or store IO. */
    fun translate(
        manifestText: String,
        scripts: Map<String, String>,
        listing: ThirdPartyPackageListing = ThirdPartyPackageListing(
            scripts.keys.map { ThirdPartyListedEntry(it) }
        ),
        declaredContexts: List<ThirdPartyDeclaredContext> = emptyList()
    ): ThirdPartyTranslationResult {
        val preview = previewAdapter.preview(manifestText, listing, declaredContexts)
        return when (preview) {
            is ThirdPartyToolPackageImportResult.Rejected -> ThirdPartyTranslationResult.Rejected(
                code = ThirdPartyTranslationErrorCode.PREVIEW_REJECTED,
                fieldPath = preview.fieldPath,
                symbol = null,
                safeMessage = "The third-party preview rejected the manifest (${preview.code.wireCode})."
            )
            is ThirdPartyToolPackageImportResult.Previewed -> translator.translate(
                descriptor = preview.descriptor,
                previewReport = preview.report,
                scripts = scripts
            )
        }
    }

    /**
     * Reads the manifest plus `.js` entries of a third-party `.toolpkg` ZIP with the hardened v1
     * archive reader and translates the result. Nothing is extracted; the archive's manifest is
     * read with the manifest budget and each script with the executable-v2 script budget.
     */
    fun translateArchive(archiveBytes: ByteArray): ThirdPartyTranslationResult {
        if (archiveBytes.isEmpty()) {
            return archiveRejected("the package archive is empty")
        }
        if (archiveBytes.size.toLong() > AgentToolPackageLimits.MAX_ARCHIVE_BYTES) {
            return archiveRejected("the package archive exceeds the archive size budget")
        }
        return try {
            AgentToolPackageZipArchive(ByteArrayToolPackageSource(archiveBytes)).use { archive ->
                val entries = archive.entries()
                val json = entries.firstOrNull { !it.isDirectory && it.path == "manifest.json" }
                val hjson = entries.firstOrNull { !it.isDirectory && it.path == "manifest.hjson" }
                if ((json == null) == (hjson == null)) {
                    return archiveRejected("the archive root must contain exactly one third-party manifest")
                }
                val manifestEntry = requireNotNull(json ?: hjson)
                val manifestText = ToolPackageJson.decodeUtf8(
                    readBounded(archive.openStream(manifestEntry), AgentToolPackageLimits.MAX_MANIFEST_BYTES)
                )
                val listing = ThirdPartyPackageListing(
                    entries.asSequence()
                        .filter { it !== manifestEntry }
                        .map { ThirdPartyListedEntry(it.path, it.isDirectory, it.uncompressedSize) }
                        .toList()
                )
                val scripts = LinkedHashMap<String, String>()
                var total = 0L
                for (entry in entries) {
                    if (entry.isDirectory || entry === manifestEntry) continue
                    if (!entry.path.endsWith(".js")) continue
                    if (scripts.size >= ToolPackageLimits.MAX_SCRIPTS) {
                        return archiveRejected("the package declares too many JavaScript entries")
                    }
                    val bytes = readBounded(archive.openStream(entry), ToolPackageLimits.MAX_SCRIPT_BYTES)
                    total += bytes.size
                    if (total > ToolPackageLimits.MAX_SCRIPTS_TOTAL_BYTES) {
                        return archiveRejected("the package JavaScript entries exceed the script budget")
                    }
                    scripts[entry.path] = String(bytes, Charsets.UTF_8)
                }
                translate(manifestText, scripts, listing)
            }
        } catch (_: AgentToolPackageException) {
            archiveRejected("the package archive was rejected by the hardened ZIP reader")
        } catch (_: Exception) {
            archiveRejected("the package archive could not be inspected")
        }
    }

    private fun archiveRejected(message: String): ThirdPartyTranslationResult.Rejected =
        ThirdPartyTranslationResult.Rejected(
            code = ThirdPartyTranslationErrorCode.ARCHIVE_INVALID,
            fieldPath = "archive",
            symbol = null,
            safeMessage = message
        )

    private fun readBounded(input: InputStream, limitBytes: Long): ByteArray = input.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > limitBytes) throw java.io.IOException("entry budget exceeded")
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
}

/** Result of the combined translate+install path; a refusal never writes to the store. */
sealed interface ThirdPartyTranslationInstallResult {
    data class Installed(
        val translation: ThirdPartyTranslatedExecutablePackage,
        val status: ToolPackageLifecycleStatus?
    ) : ThirdPartyTranslationInstallResult

    /** Translation refused; nothing was installed and no store byte was touched. */
    data class TranslationRejected(
        val result: ThirdPartyTranslationResult.Rejected
    ) : ThirdPartyTranslationInstallResult

    /** The translated package was valid but the lifecycle/store refused it (for example policy). */
    data class InstallRejected(
        val translation: ThirdPartyTranslatedExecutablePackage,
        val code: String,
        val safeMessage: String
    ) : ThirdPartyTranslationInstallResult
}

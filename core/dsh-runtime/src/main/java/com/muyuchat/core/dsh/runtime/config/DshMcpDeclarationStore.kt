package com.muyuchat.core.dsh.runtime.config

import com.muyuchat.core.dsh.runtime.payload.DefaultDshDurability
import com.muyuchat.core.dsh.runtime.payload.DshDurability
import com.muyuchat.core.dsh.runtime.payload.DshSyncOutcome
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * What one declaration write produced.
 *
 * [Refused] carries exactly the [DshMcpConfigFailureCode]s the reader would report for the same
 * document, so a settings surface can render one error vocabulary for read and write. Neither the
 * values offered by the caller nor the contents of the previous file are ever attached to a
 * diagnostic: a command/argument/URL/env or header value may be a credential.
 */
sealed interface DshMcpWriteOutcome {
    /** The server this write targeted (created, updated, or removed); the file re-reads cleanly. */
    data class Written(
        val server: DshMcpServerSpec,
        /** SHA-256 of the published declaration bytes; never a secret. */
        val declarationSha256: String,
        /**
         * Honest durability notes (e.g. a host that cannot fsync the directory). Empty on a plain
         * Android publish.
         */
        val notes: List<String> = emptyList()
    ) : DshMcpWriteOutcome

    /** The requested document was refused by the existing schema; nothing on disk changed. */
    data class Refused(val diagnostics: List<DshMcpConfigDiagnostic>) : DshMcpWriteOutcome

    /**
     * The schema accepted the document but the platform could not publish it (unreadable/foreign
     * path, fsync failure, rename failure). [detail] is platform state, never declaration content.
     */
    data class IoFailure(val detail: String) : DshMcpWriteOutcome

    /**
     * A whole declaration document was imported (replacing the previous server list). Only a
     * document the production reader accepts can reach this outcome; [serverCount] is the number of
     * validated servers, never the number of entries the user attempted.
     */
    data class Imported(
        val serverCount: Int,
        val declarationSha256: String,
        val notes: List<String> = emptyList()
    ) : DshMcpWriteOutcome {
        init { require(serverCount >= 0) { "dsh: imported server count must be non-negative" } }
    }
}

/**
 * Validation-preserving write/update/remove port for `<DSH_HOME>/mcp-servers.json`.
 *
 * This is the write half of [DshMcpServerConfigLoader]; it deliberately does not define a second
 * format and does not touch the official `dsh-mcp-client` overlay itself:
 *
 * - **One schema.** A mutation is rendered into the file's own document shape, parsed again with
 *   the production reader, and only published when that read yields no diagnostics and the exact
 *   same server list. An invalid value can therefore never be half-written: it is refused with the
 *   reader's typed code before the disk is touched.
 * - **Never clobber an invalid file.** If the existing declaration already carries diagnostics,
 *   a write refuses with those diagnostics instead of silently dropping the rejected entries. The
 *   user (or a future "reset declaration" action) has to fix or remove the file deliberately.
 * - **Atomic same-directory publish.** The document is written to a sibling `.mca-tmp`, fsynced,
 *   and renamed over the target (`ATOMIC_MOVE` with a replacement fallback), so a reader never
 *   observes a partial document. The directory entry is fsynced best effort and recorded in
 *   [DshMcpWriteOutcome.Written.notes] when the host cannot do it.
 * - **Ownership is re-evaluated by the next seed.** The seeder re-reads this file, composes one
 *   `mcp-<serverName>` row per accepted server, and removes only the rows it previously marked as
 *   its own. A removed server therefore loses its row while a hand-written user row survives.
 * - **No credential logging.** No method logs or echoes a command, argument, URL, env or header
 *   value. Only server names (bounded tokens) and schema field names appear in diagnostics.
 *
 * A successful write only changes the declaration. It does not restart or reload a running host:
 * the next seed composes the new rows. A user-facing surface must request a host reload separately.
 */
class DshMcpDeclarationStore(
    val homeDir: File,
    private val durability: DshDurability = DefaultDshDurability,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** The one declaration file this store owns. */
    val declarationFile: File get() = File(homeDir, DshMcpServerConfigLoader.FILE_NAME)

    /** Current declaration (absent file -> [DshMcpServerCatalog.ABSENT]). */
    fun read(): DshMcpServerCatalog = DshMcpServerConfigLoader.load(declarationFile)

    /** Adds [server]; refuses a duplicate name or a full catalog. */
    fun create(server: DshMcpServerSpec): DshMcpWriteOutcome = mutate {
        val duplicateIndex = it.indexOfFirst { existing -> existing.serverName == server.serverName }
        when {
            duplicateIndex >= 0 -> Mutation.Rejected(
                DshMcpConfigDiagnostic(
                    code = DshMcpConfigFailureCode.SERVER_NAME_DUPLICATE,
                    serverIndex = duplicateIndex,
                    serverName = server.serverName,
                    field = "serverName"
                )
            )
            it.size >= DshMcpServerConfigLoader.MAX_SERVERS -> Mutation.Rejected(
                DshMcpConfigDiagnostic(
                    code = DshMcpConfigFailureCode.TOO_MANY_SERVERS,
                    serverIndex = -1,
                    serverName = null,
                    field = "servers"
                )
            )
            else -> Mutation.Accepted(it + server, server)
        }
    }

    /**
     * Replaces the entry with the same [DshMcpServerSpec.serverName] in place (other entries and
     * their order are preserved). Renaming is intentionally not an update: a renamed server is a
     * new server, so the surface must create the new name and remove the old one.
     */
    fun update(server: DshMcpServerSpec): DshMcpWriteOutcome = mutate {
        val index = it.indexOfFirst { existing -> existing.serverName == server.serverName }
        if (index < 0) {
            Mutation.Rejected(serverNameMissing(server.serverName))
        } else {
            Mutation.Accepted(it.toMutableList().also { servers -> servers[index] = server }, server)
        }
    }

    /** Removes the entry with [serverName]; refuses an unknown name. */
    fun remove(serverName: String): DshMcpWriteOutcome {
        if (!DshMcpServerConfigLoader.isValidServerName(serverName)) {
            return DshMcpWriteOutcome.Refused(
                listOf(
                    DshMcpConfigDiagnostic(
                        code = DshMcpConfigFailureCode.SERVER_NAME_INVALID,
                        serverIndex = -1,
                        serverName = null,
                        field = "serverName"
                    )
                )
            )
        }
        return mutate {
            val index = it.indexOfFirst { existing -> existing.serverName == serverName }
            if (index < 0) {
                Mutation.Rejected(serverNameMissing(serverName))
            } else {
                val removed = it[index]
                Mutation.Accepted(it.toMutableList().also { servers -> servers.removeAt(index) }, removed)
            }
        }
    }

    // ------------------------------------------------------------------ mutation pipeline

    private sealed interface Mutation {
        data class Accepted(val servers: List<DshMcpServerSpec>, val target: DshMcpServerSpec) : Mutation
        data class Rejected(val diagnostic: DshMcpConfigDiagnostic) : Mutation
    }

    /**
     * Replaces the whole declaration with [document], after the production reader accepts every
     * server. The previous file is left untouched on any diagnostic, and an already-invalid file is
     * never silently replaced (the user must repair or remove it deliberately).
     *
     * The document is re-rendered to the store's canonical shape before publishing, so the bytes on
     * disk are exactly the shape this reader and the seeder consume; the server list must survive
     * that round-trip unchanged.
     */
    fun importDocument(document: String): DshMcpWriteOutcome {
        declarationShapeError()?.let { return DshMcpWriteOutcome.IoFailure(it) }
        val current = DshMcpServerConfigLoader.load(declarationFile)
        if (current.diagnostics.isNotEmpty()) return DshMcpWriteOutcome.Refused(current.diagnostics)
        if (document.toByteArray(Charsets.UTF_8).size > DshMcpServerConfigLoader.MAX_FILE_BYTES) {
            return DshMcpWriteOutcome.Refused(listOf(fileDiagnostic(DshMcpConfigFailureCode.FILE_TOO_LARGE)))
        }
        val parsed = DshMcpServerConfigLoader.parse(document)
        if (parsed.diagnostics.isNotEmpty()) return DshMcpWriteOutcome.Refused(parsed.diagnostics)
        val rendered = DshMcpDeclarationDocument.render(parsed.servers)
        if (rendered.toByteArray(Charsets.UTF_8).size > DshMcpServerConfigLoader.MAX_FILE_BYTES) {
            return DshMcpWriteOutcome.Refused(listOf(fileDiagnostic(DshMcpConfigFailureCode.FILE_TOO_LARGE)))
        }
        val recheck = DshMcpServerConfigLoader.parse(rendered)
        if (recheck.diagnostics.isNotEmpty()) return DshMcpWriteOutcome.Refused(recheck.diagnostics)
        if (recheck.servers != parsed.servers) {
            return DshMcpWriteOutcome.Refused(
                listOf(fileDiagnostic(DshMcpConfigFailureCode.ROOT_SHAPE_INVALID, field = "servers"))
            )
        }
        return when (val published = publishDocument(rendered)) {
            is DocumentPublish.Failed -> DshMcpWriteOutcome.IoFailure(published.detail)
            is DocumentPublish.Published -> DshMcpWriteOutcome.Imported(
                serverCount = parsed.servers.size,
                declarationSha256 = published.sha256,
                notes = published.notes
            )
        }
    }

    private fun mutate(command: (List<DshMcpServerSpec>) -> Mutation): DshMcpWriteOutcome {
        declarationShapeError()?.let { return DshMcpWriteOutcome.IoFailure(it) }

        val current = DshMcpServerConfigLoader.load(declarationFile)
        if (current.diagnostics.isNotEmpty()) {
            // A declaration with rejected entries must be repaired deliberately; rewriting only
            // the accepted entries here would delete the user's rejected ones without saying so.
            return DshMcpWriteOutcome.Refused(current.diagnostics)
        }

        val mutation = command(current.servers)
        val accepted = when (mutation) {
            is Mutation.Rejected -> return DshMcpWriteOutcome.Refused(listOf(mutation.diagnostic))
            is Mutation.Accepted -> mutation
        }

        // Validate the exact document that would be published, with the production reader. This is
        // what keeps the write path from ever producing a file the read path would refuse.
        val rendered = DshMcpDeclarationDocument.render(accepted.servers)
        if (rendered.toByteArray(Charsets.UTF_8).size > DshMcpServerConfigLoader.MAX_FILE_BYTES) {
            return DshMcpWriteOutcome.Refused(listOf(fileDiagnostic(DshMcpConfigFailureCode.FILE_TOO_LARGE)))
        }
        val recheck = DshMcpServerConfigLoader.parse(rendered)
        if (recheck.diagnostics.isNotEmpty()) {
            return DshMcpWriteOutcome.Refused(recheck.diagnostics)
        }
        if (recheck.servers != accepted.servers) {
            // Internal invariant: the renderer and the reader must agree on every field.
            return DshMcpWriteOutcome.Refused(
                listOf(fileDiagnostic(DshMcpConfigFailureCode.ROOT_SHAPE_INVALID, field = "servers"))
            )
        }

        val previousText = runCatching {
            if (declarationFile.isFile) declarationFile.readText(Charsets.UTF_8) else null
        }.getOrNull()
        if (previousText == rendered) {
            // Byte-identical rewrite: an idempotent no-op, not a new file version.
            return DshMcpWriteOutcome.Written(accepted.target, sha256Hex(rendered))
        }
        return publish(rendered, accepted.target)
    }

    /** Refuses a path shape this store must never write through. */
    private fun declarationShapeError(): String? {
        if (!homeDir.isDirectory) return "the DSH home is not a directory"
        val home = homeDir.toPath()
        if (Files.isSymbolicLink(home)) return "the DSH home is a symlink; refusing to write through it"
        val target = declarationFile.toPath()
        if (Files.isSymbolicLink(target)) {
            return "the MCP declaration is a symlink; refusing to write through it"
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
        ) {
            return "the MCP declaration exists and is not a regular file"
        }
        return null
    }

    private sealed interface DocumentPublish {
        data class Published(val sha256: String, val notes: List<String>) : DocumentPublish
        data class Failed(val detail: String) : DocumentPublish
    }

    private fun publish(text: String, target: DshMcpServerSpec): DshMcpWriteOutcome =
        when (val published = publishDocument(text)) {
            is DocumentPublish.Failed -> DshMcpWriteOutcome.IoFailure(published.detail)
            is DocumentPublish.Published ->
                DshMcpWriteOutcome.Written(target, published.sha256, published.notes)
        }

    private fun publishDocument(text: String): DocumentPublish {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val temp = File(
            homeDir,
            ".${DshMcpServerConfigLoader.FILE_NAME}.${clock()}.${System.nanoTime().toString(16)}$TEMP_SUFFIX"
        )
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
            }
            when (val fileSync = durability.syncFile(temp)) {
                is DshSyncOutcome.Failed -> {
                    deleteQuietly(temp)
                    return DocumentPublish.Failed("declaration file fsync failed: " + fileSync.detail)
                }
                is DshSyncOutcome.Unsupported, DshSyncOutcome.Synced -> Unit
            }
            moveAtomically(temp, declarationFile)
        } catch (t: Throwable) {
            deleteQuietly(temp)
            return DocumentPublish.Failed("cannot publish the MCP declaration: " + describe(t))
        }
        val notes = when (val directorySync = durability.syncDirectory(homeDir)) {
            is DshSyncOutcome.Synced -> emptyList()
            is DshSyncOutcome.Unsupported ->
                listOf("declaration directory fsync unsupported: " + directorySync.detail)
            is DshSyncOutcome.Failed ->
                listOf("declaration directory fsync failed: " + directorySync.detail)
        }
        val published = runCatching { declarationFile.readText(Charsets.UTF_8) }.getOrNull()
        if (published != text) {
            return DocumentPublish.Failed("the published declaration could not be read back")
        }
        return DocumentPublish.Published(sha256Hex(text), notes)
    }

    private fun moveAtomically(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteQuietly(file: File) {
        runCatching { Files.deleteIfExists(file.toPath()) }
    }

    private fun serverNameMissing(serverName: String): DshMcpConfigDiagnostic =
        DshMcpConfigDiagnostic(
            code = DshMcpConfigFailureCode.MISSING_FIELD,
            serverIndex = -1,
            serverName = serverName.take(DshMcpServerConfigLoader.MAX_SERVER_NAME_CHARS),
            field = "serverName"
        )

    private fun fileDiagnostic(
        code: DshMcpConfigFailureCode,
        field: String? = null
    ): DshMcpConfigDiagnostic =
        DshMcpConfigDiagnostic(code = code, serverIndex = -1, serverName = null, field = field)

    private fun describe(t: Throwable): String {
        val reason = (t as? java.nio.file.FileSystemException)?.reason?.takeIf { it.isNotBlank() }
        return buildString {
            append(t.javaClass.simpleName)
            t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            if (reason != null && t.message?.contains(reason) != true) {
                append(" (").append(reason).append(')')
            }
        }
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private companion object {
        const val TEMP_SUFFIX = ".mca-tmp"
        const val HEX = "0123456789abcdef"
    }
}

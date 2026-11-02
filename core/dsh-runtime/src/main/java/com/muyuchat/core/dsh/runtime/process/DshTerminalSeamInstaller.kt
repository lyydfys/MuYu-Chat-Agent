package com.muyuchat.core.dsh.runtime.process

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Typed state of the home-level JS seam that forwards the official `spawnTerminal` to the App PTY.
 *
 * The seam is a file plus one overlay row, so "installed" is a claim about two real artifacts and
 * is only [Active] when both are on disk. Nothing here reports success for a written plugin whose
 * overlay row is missing: the kernel would never import it.
 */
sealed interface DshTerminalSeamState {

    /** Bounded, secret-free one-line fact for the console/diagnostics projection. */
    val summary: String

    /** The plugin file and its overlay insert row are both in place for the next Node boot. */
    data class Active(
        val pluginRelativePath: String,
        val sha256: String,
        /** The exact `name:` specifier written into the overlay row. */
        val moduleSpecifier: String,
        val atMillis: Long,
        /** Who owns the overlay row that inserts the seam. */
        val rowOwner: RowOwner = RowOwner.SEAM_INSTALLER
    ) : DshTerminalSeamState {
        override val summary: String get() = "seam=active sha=${sha256.take(12)}"
    }

    /** Which writer owns the `- insert:` row that names the seam. */
    enum class RowOwner {
        /** This installer's own single-row block. */
        SEAM_INSTALLER,

        /**
         * Another owner (the MCA seeder) already inserts the seam inside a shared insert block.
         * The installer then keeps its hands off the overlay entirely: rewriting a foreign block
         * would delete the rows it shares with the shell provider and the mobile tools.
         */
        SHARED_BLOCK
    }

    /** A precondition of the seeded home is missing; nothing unsafe was written. */
    data class Pending(val code: SkipCode, val detail: String) : DshTerminalSeamState {
        override val summary: String get() = "seam=pending(${code.name})"
    }

    /** A write or read failed; the previous artifacts are left as they were. */
    data class Failed(val code: FailureCode, val detail: String) : DshTerminalSeamState {
        override val summary: String get() = "seam=failed(${code.name})"
    }

    /** Why the seam is not active yet. None of these is an error. */
    enum class SkipCode {
        /** `$DSH_HOME` does not exist (first run); the seeder has not created it yet. */
        HOME_NOT_SEEDED,
        /** The home exists but the seeded profile template is missing. */
        PROFILE_MISSING,
        /** The MCA overlay does not exist yet; the seeder owns creating it. */
        OVERLAY_MISSING,
        /** Inspection only: the plugin file is not installed in the home. */
        PLUGIN_MISSING,
        /** Inspection only: the installed plugin bytes differ from the asset in this build. */
        SOURCE_STALE
    }

    enum class FailureCode {
        /** The asset is empty, oversized or not the terminal seam. */
        SOURCE_REJECTED,
        PLUGIN_WRITE_FAILED,
        OVERLAY_UNREADABLE,
        OVERLAY_WRITE_FAILED
    }
}

/**
 * Installs the MCA-owned terminal seam into the app-private DSH home.
 *
 * Why a home-level plugin: patching the verified payload tree is forbidden (it is hash-verified and
 * replaced on every payload upgrade), and the official loader already imports patch rows from the
 * home `cordis.patch.yml`. The seam therefore ships as one dependency-free `.mjs` file below the
 * seeded profile and one `- insert:` row naming it relatively.
 *
 * Specifier form: a relative `./`-prefixed row is resolved by the loader against `DSH_HOME`, not
 * against the profile, so `./mca-terminal-bridge/index.mjs` was looked up at
 * `<home>/mca-terminal-bridge/index.mjs` and never imported (plugins.log 2026-10-25:
 * `ERR_MODULE_NOT_FOUND .../files/dsh/home/mca-terminal-bridge/index.mjs`). A bare package name is
 * resolved from the profile's `node_modules`, so the seam now ships as
 * `profiles/web/node_modules/@mca/dsh-terminal-bridge/{index.mjs,package.json}` and the row names
 * `@mca/dsh-terminal-bridge`.
 *
 * Ownership boundary of the overlay file (this is the delicate part, so it is stated explicitly):
 *  - `DshGatewayConfigSeeder` owns every MCA row and rewrites them per host generation;
 *  - this installer owns **exactly one** self-identified `- insert:` block. It refuses to create
 *    the overlay file, never rewrites another block, and is idempotent (a second install replaces
 *    only its own block, byte-for-byte identical output). The seeder's three-way merge preserves
 *    unknown rows verbatim, so this block survives a reseed without the seeder knowing it.
 *
 * Data-loss guard: nothing is written until the home is really seeded (`profiles/web/cordis.yml`
 * exists). Writing into a first-run home before the seeder would create a non-empty home with no
 * template marker, which the seeder must classify as lost data and refuse.
 *
 * Android-free on purpose: the App composition reads the asset and passes its text, so the same
 * logic is unit tested on the JVM.
 */
class DshTerminalSeamInstaller(
    private val homeDir: File,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Writes the plugin and its overlay row. Idempotent; safe to call once per capability
     * generation. Never throws: every failure is a typed [DshTerminalSeamState.Failed].
     */
    fun install(source: String): DshTerminalSeamState {
        if (!isSeamSource(source)) {
            return DshTerminalSeamState.Failed(
                DshTerminalSeamState.FailureCode.SOURCE_REJECTED,
                "the terminal seam asset is empty or is not the spawnTerminal wrapper"
            )
        }
        val bytes = source.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SOURCE_BYTES) {
            return DshTerminalSeamState.Failed(
                DshTerminalSeamState.FailureCode.SOURCE_REJECTED,
                "the terminal seam asset is ${bytes.size} bytes (limit $MAX_SOURCE_BYTES)"
            )
        }
        val readiness = readiness()
        if (readiness != null) return readiness
        val overlay = File(homeDir, OVERLAY_NAME)
        if (!overlay.isFile) {
            // The seeder owns overlay creation; adding only our row would boot a patch layer
            // without the MCA platform rows and the bash-local shell provider.
            return DshTerminalSeamState.Pending(
                DshTerminalSeamState.SkipCode.OVERLAY_MISSING,
                "the seeded home has no $OVERLAY_NAME yet"
            )
        }
        val plugin = pluginFile()
        if (!writeAtomically(plugin, bytes)) {
            return DshTerminalSeamState.Failed(
                DshTerminalSeamState.FailureCode.PLUGIN_WRITE_FAILED,
                "cannot write ${relative(plugin)}"
            )
        }
        // The loader resolves a bare package name through this manifest.
        if (!writeAtomically(packageJsonFile(), PACKAGE_JSON.toByteArray(Charsets.UTF_8))) {
            return DshTerminalSeamState.Failed(
                DshTerminalSeamState.FailureCode.PLUGIN_WRITE_FAILED,
                "cannot write ${relative(packageJsonFile())}"
            )
        }
        val previous = runCatching { overlay.readText(Charsets.UTF_8) }.getOrNull()
            ?: return DshTerminalSeamState.Failed(
                DshTerminalSeamState.FailureCode.OVERLAY_UNREADABLE,
                "cannot read $OVERLAY_NAME (the plugin file was written)"
            )
        // A shared insert block already names the seam (the seeder owns the row): leave the
        // overlay byte-identical. Rewriting that block would drop the shell provider and tools.
        if (ownsRowInForeignBlock(previous)) {
            return DshTerminalSeamState.Active(
                pluginRelativePath = relative(plugin),
                sha256 = sha256Hex(bytes),
                moduleSpecifier = moduleSpecifier(),
                atMillis = clock(),
                rowOwner = DshTerminalSeamState.RowOwner.SHARED_BLOCK
            )
        }
        val merged = withOwnedBlock(previous, block())
        if (!writeAtomically(overlay, merged.toByteArray(Charsets.UTF_8))) {
            return DshTerminalSeamState.Failed(
                DshTerminalSeamState.FailureCode.OVERLAY_WRITE_FAILED,
                "cannot write the $OVERLAY_NAME insert row (the plugin file was written)"
            )
        }
        return DshTerminalSeamState.Active(
            pluginRelativePath = relative(plugin),
            sha256 = sha256Hex(bytes),
            moduleSpecifier = moduleSpecifier(),
            atMillis = clock()
        )
    }

    /** Read-only check of the same two artifacts; performs no write. */
    fun inspect(source: String): DshTerminalSeamState {
        if (!isSeamSource(source)) {
            return DshTerminalSeamState.Failed(
                DshTerminalSeamState.FailureCode.SOURCE_REJECTED,
                "the terminal seam asset is empty or is not the spawnTerminal wrapper"
            )
        }
        readiness()?.let { return it }
        val plugin = pluginFile()
        val installedSha = runCatching { sha256Hex(plugin.readBytes()) }.getOrNull()
            ?: return DshTerminalSeamState.Pending(
                DshTerminalSeamState.SkipCode.PLUGIN_MISSING,
                "the terminal seam plugin is not installed"
            )
        val expected = sha256Hex(source.toByteArray(Charsets.UTF_8))
        if (installedSha != expected) {
            return DshTerminalSeamState.Pending(
                DshTerminalSeamState.SkipCode.SOURCE_STALE,
                "the installed terminal seam differs from this build's asset"
            )
        }
        if (!packageJsonFile().isFile) {
            return DshTerminalSeamState.Pending(
                DshTerminalSeamState.SkipCode.PLUGIN_MISSING,
                "the terminal seam package manifest is not installed"
            )
        }
        val overlay = File(homeDir, OVERLAY_NAME)
        val text = if (overlay.isFile) {
            runCatching { overlay.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        } ?: return DshTerminalSeamState.Pending(
            DshTerminalSeamState.SkipCode.OVERLAY_MISSING,
            "the seeded home has no $OVERLAY_NAME"
        )
        val owned = ownedBlockRange(text)
        if (owned != null) {
            return DshTerminalSeamState.Active(
                pluginRelativePath = relative(plugin),
                sha256 = installedSha,
                moduleSpecifier = moduleSpecifier(),
                atMillis = clock()
            )
        }
        if (ownsRowInForeignBlock(text)) {
            return DshTerminalSeamState.Active(
                pluginRelativePath = relative(plugin),
                sha256 = installedSha,
                moduleSpecifier = moduleSpecifier(),
                atMillis = clock(),
                rowOwner = DshTerminalSeamState.RowOwner.SHARED_BLOCK
            )
        }
        return DshTerminalSeamState.Pending(
            DshTerminalSeamState.SkipCode.OVERLAY_MISSING,
            "the $OVERLAY_NAME has no $BLOCK_ID insert row"
        )
    }

    /** The exact overlay row this installer owns. Exposed so tests and diagnostics can assert it. */
    fun block(): String = buildString {
        appendLine("- insert:")
        appendLine("    # MCA-owned terminal seam ($BLOCK_ID). Regenerated per capability generation;")
        appendLine("    # every other row in this file belongs to the seeder or to the user.")
        appendLine("    - id: $BLOCK_ID")
        appendLine("      name: '${moduleSpecifier()}'")
    }

    /** The module specifier the loader imports, relative to the profile's `baseUrl`. */
    fun moduleSpecifier(): String = MODULE_SPECIFIER

    fun pluginFile(): File = File(File(homeDir, SEAM_DIR_RELATIVE), SEAM_FILE_NAME)

    fun packageJsonFile(): File = File(pluginFile().parentFile, PACKAGE_FILE_NAME)

    private fun readiness(): DshTerminalSeamState.Pending? {
        if (!homeDir.isDirectory) {
            return DshTerminalSeamState.Pending(
                DshTerminalSeamState.SkipCode.HOME_NOT_SEEDED,
                "the DSH home does not exist yet"
            )
        }
        if (!File(File(homeDir, PROFILE_ROOT_RELATIVE), PROFILE_ROOT_FILE).isFile) {
            return DshTerminalSeamState.Pending(
                DshTerminalSeamState.SkipCode.PROFILE_MISSING,
                "the DSH home has no $PROFILE_ROOT_RELATIVE/$PROFILE_ROOT_FILE yet"
            )
        }
        return null
    }

    /**
     * Replaces only this installer's `- insert:` block and keeps every other byte of the overlay.
     * Returns the append form when no owned block exists yet.
     */
    private fun withOwnedBlock(previous: String, block: String): String {
        val owned = ownedBlockRange(previous)
        if (owned != null) {
            val tail = previous.substring(owned.second)
            val separator = if (tail.isEmpty() || tail.startsWith("\n")) "" else "\n"
            return previous.substring(0, owned.first) + block + separator + tail
        }
        if (previous.isBlank()) return block
        return previous.trimEnd() + "\n\n" + block
    }

    /**
     * Half-open range of the single-row `- insert:` block this installer owns, or null.
     *
     * The scan mirrors the seeder's block rule (a top-level line starting with `- ` begins a
     * block). A block only counts as ours when it is an insert block, carries our id and has no
     * other `- id:` row: the seeder's shared insert block (shell provider + mobile tools) is never
     * matched, so it can never be replaced by our single row.
     */
    private fun ownedBlockRange(text: String): Pair<Int, Int>? {
        for (block in blocks(text)) {
            if (!block.raw.startsWith("- insert:")) continue
            if (!block.raw.contains("id: $BLOCK_ID")) continue
            if (occurrences(block.raw, "- id:") != 1) continue
            return block.first to block.second
        }
        return null
    }

    /**
     * True when another owner's insert block already names the seam. In that case the seeder is
     * the row's writer and this installer must not add a second insert row (the plugin would be
     * applied twice) nor rewrite the shared block.
     */
    private fun ownsRowInForeignBlock(text: String): Boolean =
        ownedBlockRange(text) == null && blocks(text).any { block ->
            block.raw.startsWith("- insert:") && block.raw.contains("id: $BLOCK_ID")
        }

    private class Block(val first: Int, val second: Int, val raw: String)

    /** Top-level `- ` blocks with their half-open ranges; leading comments are not a block. */
    private fun blocks(text: String): List<Block> {
        val starts = ArrayList<Int>()
        var index = 0
        while (index < text.length) {
            val isBlockStart = index == 0 || text[index - 1] == '\n'
            if (isBlockStart && text.startsWith("- ", index)) starts += index
            val newline = text.indexOf('\n', index)
            if (newline < 0) break
            index = newline + 1
        }
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { position, from ->
            val to = if (position + 1 < starts.size) starts[position + 1] else text.length
            Block(from, to, text.substring(from, to))
        }
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
        val temp = File(parent, ".${target.name}$TEMP_SUFFIX")
        return runCatching {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            moveAtomically(temp, target)
            target.isFile
        }.getOrElse {
            runCatching { temp.delete() }
            false
        }
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

    /** Home-relative, '/'-separated path for facts. Never an absolute app-private path in a log. */
    private fun relative(file: File): String = file.absolutePath
        .removePrefix(homeDir.absolutePath)
        .trimStart(File.separatorChar, '/')
        .replace(File.separatorChar, '/')

    private fun isSeamSource(source: String): Boolean =
        source.isNotBlank() && source.contains("spawnTerminal") && source.contains("APP_NOTIFY_PORT")

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    companion object {
        /** Home-relative directory of the installed plugin, inside the seeded profile tree. */
        const val SEAM_DIR_RELATIVE = "profiles/web/node_modules/@mca/dsh-terminal-bridge"
        const val SEAM_FILE_NAME = "index.mjs"
        const val PACKAGE_FILE_NAME = "package.json"

        /** The `name:` the loader resolves against the profile `baseUrl`. Path-independent. */
        const val MODULE_SPECIFIER = "@mca/dsh-terminal-bridge"

        /**
         * Minimal manifest for the package form. The loader resolves the package by name; the
         * entry point is `index.mjs`, which is the same file the SHA check covers.
         */
        const val PACKAGE_JSON =
            """{"name":"@mca/dsh-terminal-bridge","version":"0.0.0","private":true,""" +
                """"type":"module","main":"index.mjs","exports":"./index.mjs"}"""

        /** Stable id of the overlay insert row this installer owns. */
        const val BLOCK_ID = "mca-terminal-bridge"

        /** The MCA overlay the seeder owns; this installer only edits its own block in it. */
        const val OVERLAY_NAME = "cordis.patch.yml"

        /** Seeded profile anchors; their presence is the "home is really seeded" evidence. */
        const val PROFILE_ROOT_RELATIVE = "profiles/web"
        const val PROFILE_ROOT_FILE = "cordis.yml"

        const val MAX_SOURCE_BYTES = 512 * 1024

        private const val TEMP_SUFFIX = ".mca-tmp"
        private val HEX = "0123456789abcdef".toCharArray()
    }
}

package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.runtime.payload.DefaultDshDurability
import com.muyuchat.core.dsh.runtime.payload.DshDurability
import com.muyuchat.core.dsh.runtime.payload.DshPublishPolicy
import com.muyuchat.core.dsh.runtime.payload.DshSyncOutcome
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * The MCA-owned bundled skill root.
 *
 * The official filesystem skill provider (`@deepseek-ai/dsh-skill-filesystem`) always adds a
 * `bundled` root at rank `BUNDLED_SKILL_RANK` (600) when `DSH_BUNDLED_SKILL_DIR` is set, below the
 * user/project roots (project `.dsh/skills` 100, project `.agents/skills` 200, custom 300,
 * `$DSH_HOME/skills` 400, `$AGENTS_HOME/skills` 500). A user can therefore override an MCA-shipped
 * skill by name without editing the app.
 *
 * The root lives inside the MCA-managed DSH home and never inside the hash-verified payload tree:
 * the payload is a fixed, replaceable build input (`core/dsh-runtime/src/main/assets/dsh/payload.zip`),
 * so writing skill content there would break its manifest verification and be destroyed by the next
 * payload swap.
 */
object DshBundledSkillRoot {
    /** Node child environment variable read by the official provider (`:83-84` of its lib). */
    const val ENV_NAME = "DSH_BUNDLED_SKILL_DIR"

    /** One directory directly below `$DSH_HOME`; distinct from the user-owned `skills` root. */
    const val DIRECTORY_NAME = "bundled-skills"

    fun of(homeDir: File): File = File(homeDir, DIRECTORY_NAME)

    fun environmentValue(homeDir: File): String = of(homeDir).absolutePath
}

/**
 * Supplies the MCA-shipped skill bundle packaged in this APK.
 *
 * Production reads `assets/dsh-skills/`; tests read a directory so the installer stays a plain JVM
 * unit. The interface exists for that seam, not as a general plugin mechanism: the installed content
 * is a build artifact that only ships with the app.
 */
interface DshBundledSkillSource {
    val description: String

    /**
     * Bounded relative files below the asset root (`<skill>/SKILL.md`, `<skill>/references/x.md`),
     * or null when the build carries no bundled skill content at all.
     */
    fun listFiles(): List<String>?

    fun open(relativePath: String): InputStream?
}

/** Outcome of materialising the bundled skill assets into the managed DSH home. */
sealed interface DshBundledSkillInstallResult {
    /** Every shipped file is present at [root] (with the same bytes). */
    data class Installed(
        val root: File,
        val written: Int,
        val unchanged: Int,
        val notes: List<String> = emptyList()
    ) : DshBundledSkillInstallResult

    /** The APK carries no `dsh-skills` content; the provider simply has no bundled root content. */
    data class Missing(val detail: String) : DshBundledSkillInstallResult

    /** The assets exist but could not be materialised; [detail] is path/state evidence only. */
    data class Failed(val detail: String) : DshBundledSkillInstallResult
}

/**
 * Materialises the packaged skill bundle into [DshBundledSkillRoot] before the Node host starts.
 *
 * Rules:
 * - the asset set is bounded (files, bytes, path segments) and every relative path is validated,
 *   so a malformed/foreign asset entry can never escape the managed root;
 * - a file whose bytes already match is left untouched, so a steady-state boot writes nothing;
 * - new/changed content is written to a sibling `.mca-tmp`, fsynced and renamed into place, so the
 *   provider never watches a half-written SKILL.md;
 * - the installer only ever creates/replaces files it ships. It never deletes anything, so a user
 *   file dropped into the (MCA-owned but plain-directory) root is not destroyed by an app update;
 * - the caller decides whether a failure blocks the host. Skills are additive content: an absent or
 *   failed bundle leaves the official catalog empty, exactly like a payload with no skills.
 *
 * The installer does not validate frontmatter at runtime: the shipped asset is fixed and its
 * format is pinned by the JVM test that installs the real file. Frontmatter mistakes in an app build
 * are therefore a build/test defect, not a device-time condition.
 */
class DshBundledSkillInstaller(
    private val source: DshBundledSkillSource,
    private val durability: DshDurability = DefaultDshDurability,
    private val policy: DshPublishPolicy = DshPublishPolicy()
) {

    fun ensureInstalled(homeDir: File): DshBundledSkillInstallResult {
        val files = try {
            source.listFiles()
        } catch (t: Throwable) {
            return DshBundledSkillInstallResult.Failed(
                "cannot list ${source.description}: " + describe(t)
            )
        } ?: return DshBundledSkillInstallResult.Missing("no bundled skill asset in ${source.description}")
        if (files.isEmpty()) {
            return DshBundledSkillInstallResult.Missing("no bundled skill file in ${source.description}")
        }
        if (files.size > MAX_FILES) {
            return DshBundledSkillInstallResult.Failed(
                "the bundled skill asset carries ${files.size} files, more than the $MAX_FILES bound"
            )
        }
        val root = DshBundledSkillRoot.of(homeDir)
        if (!ensureRoot(root)) {
            return DshBundledSkillInstallResult.Failed(
                "cannot create the managed bundled skill root: ${root.absolutePath}"
            )
        }
        var written = 0
        var unchanged = 0
        var totalBytes = 0L
        val notes = mutableListOf<String>()
        for (relativePath in files) {
            if (!isSafeRelativePath(relativePath)) {
                return DshBundledSkillInstallResult.Failed(
                    "the bundled skill asset carries an unsafe path: $relativePath"
                )
            }
            val target = File(root, relativePath.replace('/', File.separatorChar))
            if (!isWithin(root, target)) {
                return DshBundledSkillInstallResult.Failed(
                    "the bundled skill path escapes the managed root: $relativePath"
                )
            }
            val content = try {
                readBounded(relativePath) ?: return DshBundledSkillInstallResult.Failed(
                    "the bundled skill asset is unreadable: $relativePath"
                )
            } catch (t: Throwable) {
                return DshBundledSkillInstallResult.Failed(
                    "cannot read the bundled skill asset $relativePath: " + describe(t)
                )
            }
            totalBytes += content.size
            if (totalBytes > MAX_TOTAL_BYTES) {
                return DshBundledSkillInstallResult.Failed(
                    "the bundled skill asset exceeds the $MAX_TOTAL_BYTES byte bound"
                )
            }
            when (val materialised = materialise(target, content, notes)) {
                MaterialiseResult.Written -> written++
                MaterialiseResult.Unchanged -> unchanged++
                is MaterialiseResult.Failed -> return DshBundledSkillInstallResult.Failed(
                    "cannot install $relativePath: ${materialised.detail}"
                )
            }
        }
        return DshBundledSkillInstallResult.Installed(root, written, unchanged, notes)
    }

    // ------------------------------------------------------------------ materialisation

    private sealed interface MaterialiseResult {
        data object Written : MaterialiseResult
        data object Unchanged : MaterialiseResult
        data class Failed(val detail: String) : MaterialiseResult
    }

    private fun materialise(
        target: File,
        content: ByteArray,
        notes: MutableList<String>
    ): MaterialiseResult {
        val targetPath = target.toPath()
        if (Files.isSymbolicLink(targetPath)) {
            return MaterialiseResult.Failed("the target is a symlink; refusing to write through it")
        }
        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            return MaterialiseResult.Failed("the target exists and is not a regular file")
        }
        if (Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { target.readBytes().contentEquals(content) }.getOrDefault(false)
        ) {
            return MaterialiseResult.Unchanged
        }
        val parent = target.parentFile
            ?: return MaterialiseResult.Failed("the target has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            return MaterialiseResult.Failed("cannot create ${parent.absolutePath}")
        }
        if (Files.isSymbolicLink(parent.toPath())) {
            return MaterialiseResult.Failed("the target directory is a symlink")
        }
        val temp = File(parent, ".${target.name}$TEMP_SUFFIX")
        try {
            FileOutputStream(temp).use { output ->
                output.write(content)
                output.flush()
            }
            when (val sync = if (policy.fsyncStagedContent) durability.syncFile(temp) else DshSyncOutcome.Synced) {
                is DshSyncOutcome.Failed -> {
                    deleteQuietly(temp)
                    return MaterialiseResult.Failed("file fsync failed: " + sync.detail)
                }
                is DshSyncOutcome.Synced, is DshSyncOutcome.Unsupported -> Unit
            }
            moveAtomically(temp, target)
        } catch (t: Throwable) {
            deleteQuietly(temp)
            return MaterialiseResult.Failed(describe(t))
        }
        if (policy.fsyncDirectories) {
            when (val sync = durability.syncDirectory(parent)) {
                is DshSyncOutcome.Failed -> notes += "skill directory fsync failed: " + sync.detail
                is DshSyncOutcome.Unsupported ->
                    if (notes.none { it.startsWith("skill directory fsync unsupported") }) {
                        notes += "skill directory fsync unsupported: " + sync.detail
                    }
                is DshSyncOutcome.Synced -> Unit
            }
        }
        return MaterialiseResult.Written
    }

    private fun ensureRoot(root: File): Boolean {
        if (Files.isSymbolicLink(root.toPath())) return false
        if (Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS) && !root.isDirectory) return false
        return root.isDirectory || root.mkdirs() || root.isDirectory
    }

    private fun readBounded(relativePath: String): ByteArray? {
        val stream = source.open(relativePath) ?: return null
        stream.use { input ->
            val bytes = input.readNBytes(MAX_FILE_BYTES + 1)
            if (bytes.size > MAX_FILE_BYTES) {
                throw IllegalArgumentException("asset exceeds the $MAX_FILE_BYTES byte bound")
            }
            return bytes
        }
    }

    /**
     * Safe relative path: at most [MAX_SEGMENTS] plain segments, no empty/dot/dot-dot segment, no
     * absolute path, no backslash and no control character. This is stricter than the provider
     * needs because the installer must not be able to write outside its own root.
     */
    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_PATH_CHARS) return false
        if (path.startsWith('/') || path.contains('\\')) return false
        if (path.any { it < ' ' || it == '\u007f' }) return false
        val segments = path.split('/')
        if (segments.size > MAX_SEGMENTS) return false
        return segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun isWithin(root: File, target: File): Boolean = runCatching {
        val rootPath = root.canonicalFile.path + File.separator
        target.canonicalFile.path.startsWith(rootPath)
    }.getOrDefault(false)

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

    private companion object {
        const val MAX_FILES = 64
        const val MAX_FILE_BYTES = 512 * 1024
        const val MAX_TOTAL_BYTES = 4L * 1024L * 1024L
        const val MAX_PATH_CHARS = 240
        const val MAX_SEGMENTS = 4
        const val TEMP_SUFFIX = ".mca-tmp"
    }
}

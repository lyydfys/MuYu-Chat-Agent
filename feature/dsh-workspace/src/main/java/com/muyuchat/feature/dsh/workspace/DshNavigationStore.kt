package com.muyuchat.feature.dsh.workspace

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistence port for the DSH workspace's own navigation memory.
 *
 * Only the last same-origin **path** is stored. The authenticated entry URL, its query string, the
 * launch token, cookies, tab drafts and any session content are explicitly out of scope. This state
 * is separate from Kotlin Agent / chat navigation state and from DSH's own authoritative session
 * files; losing it only means the next entry opens the DSH root page.
 */
interface DshNavigationStore {
    suspend fun readLastPath(): String?
    suspend fun writeLastPath(path: String?)
}

/**
 * Versioned, bounded file codec. A corrupt or truncated file decodes to null instead of throwing:
 * restore is an optimization, never a reason to fail the workspace.
 */
object DshNavigationCodec {
    const val HEADER = "dsh-nav-v1"
    const val MAX_FILE_BYTES = 1024

    fun encode(path: String): String? {
        val sanitized = DshNavigationPolicy.sanitizeRestorePath(path) ?: return null
        return "$HEADER\n$sanitized\n"
    }

    fun decode(text: String?): String? {
        val normalized = text
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?: return null
        val lines = normalized.split('\n').filter { it.isNotEmpty() }
        if (lines.size != 2 || lines[0] != HEADER) return null
        return DshNavigationPolicy.sanitizeRestorePath(lines[1])
    }
}

/**
 * File-backed store under `filesDir/dsh/nav/last_path.txt`.
 *
 * The DSH workspace owns this tiny directory; the DSH runtime's own `files/dsh/...` data
 * directories are written by the runtime owner, never by this feature. Writes are atomic
 * (temp file + rename) and any IO failure is contained: the UI keeps working without restore.
 */
class FileDshNavigationStore(private val directory: File) : DshNavigationStore {

    private val target = File(directory, "last_path.txt")
    private val temporary = File(directory, "last_path.tmp")

    override suspend fun readLastPath(): String? = withContext(Dispatchers.IO) {
        try {
            if (!target.isFile || target.length() <= 0L ||
                target.length() > DshNavigationCodec.MAX_FILE_BYTES
            ) {
                null
            } else {
                DshNavigationCodec.decode(target.readText(Charsets.UTF_8))
            }
        } catch (error: IOException) {
            null
        } catch (error: SecurityException) {
            null
        }
    }

    override suspend fun writeLastPath(path: String?) = withContext(Dispatchers.IO) {
        try {
            if (path == null) {
                target.delete()
                temporary.delete()
                return@withContext
            }
            val encoded = DshNavigationCodec.encode(path) ?: return@withContext
            if (!directory.isDirectory && !directory.mkdirs()) return@withContext
            temporary.writeText(encoded, Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                target.writeText(encoded, Charsets.UTF_8)
                temporary.delete()
            }
        } catch (error: IOException) {
            // Restore memory is best-effort; a full disk must not break the workspace.
        } catch (error: SecurityException) {
        }
    }

    companion object {
        /** `files/dsh/nav`, owned by this feature. */
        fun forContext(context: Context): FileDshNavigationStore =
            FileDshNavigationStore(File(context.applicationContext.filesDir, "dsh/nav"))
    }
}

/** Deterministic store for previews and tests; never used by production wiring. */
class InMemoryDshNavigationStore(initialPath: String? = null) : DshNavigationStore {
    var storedPath: String? = DshNavigationPolicy.sanitizeRestorePath(initialPath)
        private set

    override suspend fun readLastPath(): String? = storedPath

    override suspend fun writeLastPath(path: String?) {
        storedPath = DshNavigationPolicy.sanitizeRestorePath(path)
    }
}

package com.muyuchat.core.dsh.vscreen

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/** Where a provisioned `rish` loader dex came from. */
enum class DshShizukuDexSource {
    /** Explicit `SHIZUKU_DEX` from the environment (the pinned package's own variable). */
    ENVIRONMENT,

    /** A read-only copy inside MCA's private directory. */
    APP_PRIVATE_COPY,

    /** Extracted from the installed Shizuku manager APK's `assets/rish_shizuku.dex`. */
    MANAGER_APK_ASSET,

    /** A `/data/local/tmp` path the user created with `rish` from a terminal. */
    SHELL_TMP
}

/** Typed outcome of loader provisioning. Never carries a token or a full environment. */
sealed interface DshShizukuDexResolution {
    data class Found(val path: String, val source: DshShizukuDexSource) : DshShizukuDexResolution

    data class Missing(val detail: String) : DshShizukuDexResolution

    val pathOrNull: String?
        get() = (this as? Found)?.path
}

/**
 * Provisions the `rish_shizuku.dex` loader that the Shizuku shell channel execs.
 *
 * MCA does not depend on the Shizuku AIDL artifact, so the transport is the loader the Shizuku
 * app itself ships (`manager/src/main/assets/rish_shizuku.dex`) driven exactly like its own `rish`
 * script. Order:
 *
 * 1. `SHIZUKU_DEX` (the pinned package's variable) when it points at a real file;
 * 2. an existing app-private copy;
 * 3. extraction from the installed Shizuku APK into `filesDir/dsh-shizuku/`;
 * 4. well-known `/data/local/tmp` paths (usually unreadable for an app uid, last resort).
 *
 * Android 14+ refuses to load a *writable* dex through `app_process`, so every copy is made
 * read-only before it is handed to the channel — the same `chmod 400` step Shizuku's `rish` script
 * performs. Only the fixed asset name is accepted, the copy is size-bounded, and the destination
 * path is constructed here (never taken from an argument).
 *
 * The resolved value is memoized for [MEMO_TTL_MILLIS] so a missing loader cannot trigger an APK
 * open on every privileged command.
 */
class DshShizukuDexProvisioner(
    private val filesDir: File,
    private val managerApkPath: () -> String?,
    private val environment: () -> Map<String, String> = { System.getenv() },
    private val knownShellPaths: List<String> = SHELL_TMP_PATHS,
    private val exists: (String) -> Boolean = { File(it).isFile },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    @Volatile
    private var memo: Pair<Long, DshShizukuDexResolution>? = null

    fun currentDexPath(): String? = resolve().pathOrNull

    fun invalidate() {
        memo = null
    }

    fun resolve(): DshShizukuDexResolution {
        val now = clock()
        memo?.let { (resolvedAt, resolution) ->
            if (now - resolvedAt in 0..MEMO_TTL_MILLIS) return resolution
        }
        return synchronized(this) {
            val cached = memo
            if (cached != null && now - cached.first in 0..MEMO_TTL_MILLIS) {
                cached.second
            } else {
                resolveUncached().also { resolution -> memo = now to resolution }
            }
        }
    }

    private fun resolveUncached(): DshShizukuDexResolution {
        val override = environment()[ENVIRONMENT_KEY]?.trim().orEmpty()
        if (override.isNotEmpty() && exists(override)) {
            return DshShizukuDexResolution.Found(override, DshShizukuDexSource.ENVIRONMENT)
        }
        val copy = File(File(filesDir, DIRECTORY), DEX_NAME)
        if (copy.isFile && copy.length() > 0L) {
            makeReadOnly(copy)
            return DshShizukuDexResolution.Found(copy.absolutePath, DshShizukuDexSource.APP_PRIVATE_COPY)
        }
        val extracted = extractFromManagerApk()
        if (extracted != null) return extracted
        knownShellPaths.firstOrNull(exists)?.let { path ->
            return DshShizukuDexResolution.Found(path, DshShizukuDexSource.SHELL_TMP)
        }
        val overrideDetail = if (override.isNotEmpty()) {
            " SHIZUKU_DEX is set but does not point at a readable file."
        } else {
            ""
        }
        return DshShizukuDexResolution.Missing(
            "no rish loader dex is available: install Shizuku 12+ (MCA extracts its " +
                "assets/$DEX_NAME into app storage) or set SHIZUKU_DEX.$overrideDetail"
        )
    }

    private fun extractFromManagerApk(): DshShizukuDexResolution? {
        val apkPath = managerApkPath() ?: return null
        val apk = File(apkPath)
        if (!apk.isFile) return null
        return try {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(ASSET_ENTRY) ?: return null
                if (entry.size <= 0L || entry.size > DshVscreenLimits.MAX_PROVISIONED_DEX_BYTES) return null
                val directory = File(filesDir, DIRECTORY)
                if (!directory.isDirectory && !directory.mkdirs()) return null
                val target = File(directory, DEX_NAME)
                val temporary = File(directory, "$DEX_NAME.tmp")
                val copied = try {
                    zip.getInputStream(entry).use { input -> copyBounded(input, temporary) }
                } catch (failure: IOException) {
                    temporary.delete()
                    null
                }
                if (copied == null || !makeReadOnly(temporary)) {
                    temporary.delete()
                    return null
                }
                if (target.exists() && !target.delete()) {
                    temporary.delete()
                    return null
                }
                if (!temporary.renameTo(target)) {
                    temporary.delete()
                    return null
                }
                DshShizukuDexResolution.Found(target.absolutePath, DshShizukuDexSource.MANAGER_APK_ASSET)
            }
        } catch (failure: IOException) {
            null
        } catch (failure: SecurityException) {
            null
        }
    }

    /** Copies at most [DshVscreenLimits.MAX_PROVISIONED_DEX_BYTES]; returns the byte count or null. */
    private fun copyBounded(input: InputStream, target: File): Long? {
        var total = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1_024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > DshVscreenLimits.MAX_PROVISIONED_DEX_BYTES) return null
                output.write(buffer, 0, read)
            }
        }
        return if (total > 0L) total else null
    }

    /**
     * `app_process` (Android 14+) refuses a writable classpath entry, so the dex is 0444.
     * Failure is reported by the caller instead of being assumed.
     */
    private fun makeReadOnly(file: File): Boolean = try {
        file.setReadOnly()
    } catch (failure: SecurityException) {
        false
    }

    companion object {
        const val ENVIRONMENT_KEY = "SHIZUKU_DEX"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val ASSET_ENTRY = "assets/rish_shizuku.dex"
        const val DEX_NAME = "rish_shizuku.dex"

        /** App-private directory, never shared and never exported. */
        const val DIRECTORY = "dsh-shizuku"

        private const val MEMO_TTL_MILLIS = 30_000L

        /** Created by the Shizuku app / `rish` export; unreachable for most app uids. */
        val SHELL_TMP_PATHS = listOf(
            "/data/local/tmp/rish_shizuku.dex",
            "/data/local/tmp/shizuku/rish_shizuku.dex",
            "/data/local/tmp/rish/rish_shizuku.dex"
        )

        private fun installedApkPath(context: Context): String? = try {
            context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0).sourceDir
        } catch (failure: Exception) {
            null
        }

        /** Production construction: app-private directory plus the installed manager APK. */
        fun forContext(context: Context): DshShizukuDexProvisioner = DshShizukuDexProvisioner(
            filesDir = context.filesDir,
            managerApkPath = { installedApkPath(context) }
        )
    }
}

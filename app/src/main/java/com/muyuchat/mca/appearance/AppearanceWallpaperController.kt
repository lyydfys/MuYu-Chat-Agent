package com.muyuchat.mca.appearance

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * Outcome of one wallpaper edit.
 *
 * A returned outcome rather than a sticky error flow, for the same reasons as
 * [AppearanceSelectionOutcome]: the caller that picked the image is the only one that can react, and a
 * failure here has to be shown immediately (the user just chose a file and nothing appeared).
 */
internal sealed interface AppearanceWallpaperOutcome {

    /** No wallpaper is set; the app paints its plain background. */
    data object Cleared : AppearanceWallpaperOutcome

    /**
     * @param fileName the stored file name now in effect.
     * @param bitmap the freshly decoded wallpaper, already bounded; null when the caller does not need
     *   an immediate preview and the next frame decodes it itself.
     */
    data class Applied(
        val fileName: String,
        val bitmap: Bitmap? = null
    ) : AppearanceWallpaperOutcome

    /**
     * The image was rejected and the previous wallpaper (if any) is still in effect.
     *
     * @param userMessage Chinese, user-facing explanation; safe to show verbatim.
     * @param diagnostic technical cause for engineering logs; never shown raw to the user.
     */
    data class Rejected(
        val userMessage: String,
        val diagnostic: String? = null
    ) : AppearanceWallpaperOutcome
}

/**
 * App-side owner of the global wallpaper.
 *
 * Holds one piece of state (the stored file name, or none) as a read-only flow, exactly like
 * [AppearanceController] holds the colour mode, and for the same reason: the preference file is the
 * single authority and nothing here mutates `MainUiState`.
 *
 * Threading: [apply] and [clear] copy and re-encode an image, which is disk and CPU work. They are
 * **not** safe to call on the main thread; the caller runs them off it (the settings screen uses
 * `withContext(Dispatchers.IO)`), while [wallpaperFileName] and [currentFileName] stay cheap and
 * lock-free reads of a `StateFlow`. The synchronized block only guards the read-modify-write of the
 * preference plus the orphan sweep, so two fast taps cannot leave two files on disk.
 *
 * Bitmap ownership: every `Bitmap` returned by [loadPreview] belongs to the caller and must be
 * recycled when replaced; this class never holds one.
 */
internal class AppearanceWallpaperController(
    private val preferences: AppearanceWallpaperPreferences,
    private val store: AppearanceWallpaperStore
) {

    private val lock = Any()

    private val mutableFileName = MutableStateFlow(
        // A stored name whose file was deleted by the user or a cleaner is treated as "no wallpaper",
        // so the first frame never tries to decode a path that no longer exists.
        preferences.loadFileName()?.takeIf { store.resolve(it) != null }
    )

    /** The stored wallpaper file name, or null when no wallpaper is set. Read-only. */
    val wallpaperFileName: StateFlow<String?> = mutableFileName.asStateFlow()

    /** Synchronous read for callers outside composition; the flow's value is the same fact. */
    fun currentFileName(): String? = mutableFileName.value

    /** True when a wallpaper is set *and* its file still exists. */
    fun hasWallpaper(): Boolean = currentFileName()?.let { store.resolve(it) != null } == true

    /**
     * Imports [uri] as the new wallpaper and makes it current.
     *
     * The previous wallpaper is only deleted after the new file is written and persisted, so a failure
     * halfway through leaves the user with the wallpaper they already had rather than none.
     */
    @Synchronized
    fun apply(uri: Uri?, maxDimension: Int = AppearanceWallpaperStore.MAX_DIMENSION): AppearanceWallpaperOutcome {
        if (uri == null) {
            return AppearanceWallpaperOutcome.Rejected(
                userMessage = "没有选中图片。",
                diagnostic = "null uri"
            )
        }
        val previous = currentFileName()
        val imported = try {
            store.import(uri)
        } catch (error: IOException) {
            // The source is user-chosen, so the message stays about the file, not about our internals.
            return AppearanceWallpaperOutcome.Rejected(
                userMessage = "这张图片无法用作壁纸，请换一张试试。",
                diagnostic = "${error.javaClass.simpleName}: ${error.message}"
            )
        } catch (error: SecurityException) {
            return AppearanceWallpaperOutcome.Rejected(
                userMessage = "无法读取这张图片，请重新选择。",
                diagnostic = "SecurityException: ${error.message}"
            )
        } catch (error: OutOfMemoryError) {
            // import() throws before returning a name, so nothing of the new image is referenced yet;
            // the staging file was already deleted in its finally block. The previous wallpaper stays.
            return AppearanceWallpaperOutcome.Rejected(
                userMessage = "这张图片太大，无法作为壁纸使用。",
                diagnostic = "OutOfMemoryError while decoding"
            )
        }

        synchronized(lock) {
            preferences.saveFileName(imported)
            mutableFileName.value = imported
            if (previous != null && previous != imported) store.delete(previous)
            store.retainOnly(keep = imported)
        }
        val preview = store.decode(imported, maxDimension)
        return AppearanceWallpaperOutcome.Applied(imported, preview)
    }

    /**
     * Clears the wallpaper and deletes its file.
     *
     * The preference is cleared first so a crash between the two steps cannot resurrect a wallpaper
     * whose file is already gone.
     */
    @Synchronized
    fun clear(): AppearanceWallpaperOutcome {
        val previous = currentFileName()
        synchronized(lock) {
            preferences.saveFileName(null)
            mutableFileName.value = null
            if (previous != null) store.delete(previous)
            store.retainOnly(keep = null)
        }
        return AppearanceWallpaperOutcome.Cleared
    }

    /**
     * Decodes the current wallpaper for rendering, bounded by [maxDimension].
     *
     * Call off the main thread. Returns null when no wallpaper is set or the file is unreadable; a
     * missing file also clears the stale preference so the next launch does not keep trying.
     */
    fun loadPreview(maxDimension: Int = AppearanceWallpaperStore.MAX_DIMENSION): Bitmap? {
        val fileName = currentFileName() ?: return null
        val bitmap = store.decode(fileName, maxDimension)
        if (bitmap == null) {
            synchronized(lock) {
                if (mutableFileName.value == fileName) {
                    preferences.saveFileName(null)
                    mutableFileName.value = null
                }
            }
        }
        return bitmap
    }
}

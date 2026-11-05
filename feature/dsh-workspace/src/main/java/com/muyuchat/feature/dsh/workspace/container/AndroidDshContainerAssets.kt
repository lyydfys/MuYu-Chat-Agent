package com.muyuchat.feature.dsh.workspace.container

import android.content.res.AssetManager
import java.io.InputStream

/**
 * The packaged container runtime, read from APK assets.
 *
 * Asset paths are fixed by `scripts/dsh/stage-container.ps1`; the directory is git-ignored, so a
 * build that never ran the staging script has no assets at all and every call returns `null`. That
 * is the documented NOT_RUN state, not an error to hide: the console then shows "缺少离线资产"
 * instead of offering an install that cannot finish.
 */
class AndroidDshContainerAssets(
    private val assets: AssetManager,
    private val baseDir: String = DEFAULT_BASE_DIR
) : DshContainerAssetSource {

    override val description: String get() = "apk:$baseDir"

    override fun openManifest(): InputStream? = open(MANIFEST_NAME)

    override fun openRootfsArchive(): InputStream? = open(ROOTFS_ARCHIVE_NAME)

    private fun open(name: String): InputStream? = runCatching {
        assets.open("$baseDir/$name")
    }.getOrNull()

    companion object {
        /** Must match the destination of `scripts/dsh/stage-container.ps1`. */
        const val DEFAULT_BASE_DIR: String = "offline/container"

        /** Must match [DshContainerAssetManifest] of the same staging script. */
        const val MANIFEST_NAME: String = "container-manifest.json"

        const val ROOTFS_ARCHIVE_NAME: String = "rootfs.tar.gz"
    }
}

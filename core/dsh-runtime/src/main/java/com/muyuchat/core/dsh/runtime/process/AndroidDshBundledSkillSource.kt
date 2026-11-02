package com.muyuchat.core.dsh.runtime.process

import android.content.Context
import java.io.InputStream

/**
 * APK-asset source for the MCA-shipped skill bundle (`assets/dsh-skills/`).
 *
 * The bundle is small and fixed at build time, so this adapter only enumerates the packaged tree
 * and opens one entry at a time; [DshBundledSkillInstaller] owns path validation, bounds, fsync and
 * atomic publication into `$DSH_HOME/bundled-skills`.
 *
 * It lives next to the other Android platform seams ([AndroidDshNodeHost]) because the `:dsh`
 * service composition is the caller that seeds the home. JVM tests supply a directory-backed
 * [DshBundledSkillSource] instead, so the installer contract never depends on a device.
 */
class AndroidDshBundledSkillSource(
    context: Context,
    private val assetRoot: String = ASSET_ROOT
) : DshBundledSkillSource {

    private val assets = context.assets

    override val description: String = "APK assets $assetRoot/"

    override fun listFiles(): List<String>? {
        // A build without the bundle stage returns null/empty here; the installer maps that to
        // Missing and the host still starts (skills are additive content).
        val top = runCatching { assets.list(assetRoot) }.getOrNull() ?: return null
        if (top.isEmpty()) return null
        val files = ArrayList<String>()
        walk(assetRoot, depth = 0, files = files)
        return files
    }

    override fun open(relativePath: String): InputStream? =
        runCatching { assets.open("$assetRoot/$relativePath") }.getOrNull()

    /**
     * `AssetManager` cannot describe a file directly: a directory answers `list()`, a file or an
     * absent path returns an empty array. Files are opened later by the installer, which fails the
     * install on an unreadable entry instead of silently skipping it.
     */
    private fun walk(directory: String, depth: Int, files: MutableList<String>) {
        if (depth > MAX_DEPTH || files.size >= MAX_FILES) return
        val children = runCatching { assets.list(directory) }.getOrNull().orEmpty()
        for (name in children) {
            if (files.size >= MAX_FILES) return
            val child = "$directory/$name"
            val grandChildren = runCatching { assets.list(child) }.getOrNull().orEmpty()
            if (grandChildren.isNotEmpty()) {
                walk(child, depth + 1, files)
            } else {
                files += child.removePrefix("$assetRoot/")
            }
        }
    }

    private companion object {
        const val ASSET_ROOT = "dsh-skills"

        /** Cheap defensive bounds; the installer enforces the real contract. */
        const val MAX_FILES = 128
        const val MAX_DEPTH = 4
    }
}

package com.muyuchat.mca

import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.ModelManifest
import java.io.File

/** Cheap request-time guard for a projector whose contents were hashed at model load. */
internal data class LoadedVisionProjectorStamp(
    val canonicalPath: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val loadedSha256: String
) {
    fun matches(file: File, loadedFingerprint: String): Boolean =
        loadedFingerprint.equals(loadedSha256, ignoreCase = true) &&
            file.isFile && file.canRead() &&
            runCatching { file.canonicalPath }.getOrNull() == canonicalPath &&
            file.length() == sizeBytes && file.lastModified() == modifiedAtMs

    companion object {
        fun capture(file: File, loadedFingerprint: String): LoadedVisionProjectorStamp? {
            if (!file.isFile || !file.canRead() || loadedFingerprint.isBlank()) return null
            val path = runCatching { file.canonicalPath }.getOrNull() ?: return null
            return LoadedVisionProjectorStamp(path, file.length(), file.lastModified(), loadedFingerprint)
        }
    }
}

internal fun loadedVisionProjectorStamp(
    model: ModelManifest,
    identity: ModelRuntimeIdentity
): LoadedVisionProjectorStamp? = model.visionProjectorPath?.let { path ->
    LoadedVisionProjectorStamp.capture(File(path), identity.projectorFingerprint)
}

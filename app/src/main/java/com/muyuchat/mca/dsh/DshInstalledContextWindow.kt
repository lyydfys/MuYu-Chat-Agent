package com.muyuchat.mca.dsh

import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.GgufMetadataReader
import com.muyuchat.core.modelstore.ModelManifest
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Projection of the model-declared context limit for installed-but-not-loaded local models.
 *
 * DSH needs a window before compaction can be offered; the live active row already carries the
 * engine's effective n_ctx. For the other rows the GGUF header is the only source, because the
 * manifest does not persist it. Reads are bounded to the metadata prefix and cached by
 * (id, sha256); a rejection is cached as 0 so a header read never repeats per catalog refresh.
 */
internal object DshInstalledContextWindow {
    private val cache = ConcurrentHashMap<String, Int>()

    fun of(model: ModelManifest): Int? {
        if (model.runtime != ChatModelRuntime.LLAMA_CPP) return null
        val key = model.id + '\u0000' + model.sha256
        val cached = cache[key] ?: run {
            val read = runCatching {
                GgufMetadataReader.read(File(model.path)).contextLength?.takeIf { it > 0 }
            }.getOrNull() ?: 0
            cache[key] = read
            read
        }
        return cached.takeIf { it > 0 }
    }
}

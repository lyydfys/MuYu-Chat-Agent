package com.muyuchat.mca.video.settings

import com.muyuchat.feature.video.workspace.VideoDraftStore
import java.io.File

/**
 * File-backed draft store: one small text file inside app-private storage. Writes are atomic
 * (temp file + rename) so a crash mid-write cannot leave a half-encoded draft that would fail to
 * decode on the next launch.
 */
internal class VideoDraftFileStore(private val file: File) : VideoDraftStore {

    override fun load(): String? = runCatching {
        if (!file.isFile) return null
        val text = file.readText()
        text.ifBlank { null }
    }.getOrNull()

    override fun save(encoded: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(encoded)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}

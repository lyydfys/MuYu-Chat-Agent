package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** The URI list can exceed WorkManager's 10 KiB limit; only this request's ID goes in work data. */
internal data class ManagedModelImportRequest(
    val id: String = UUID.randomUUID().toString(),
    val uris: List<String>,
    val directory: Boolean = false,
    val textOnly: Boolean = false,
    val persistentAccess: Boolean = false
) {
    init {
        require(UUID.fromString(id).toString() == id) { "导入任务编号无效。" }
        require(uris.isNotEmpty() && uris.size <= 20_000 && uris.all { it.isNotBlank() }) { "请选择模型文件或完整目录。" }
        require(!directory || uris.size == 1) { "每次请选择一个模型目录。" }
    }

    fun toJson(): String = JSONObject().put("id", id).put("uris", JSONArray(uris))
        .put("directory", directory).put("textOnly", textOnly)
        .put("persistentAccess", persistentAccess).toString()

    fun save(filesDir: File) {
        val file = requestFile(filesDir, id)
        require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法保存导入任务，请检查存储空间。" }
        val bytes = toJson().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_REQUEST_BYTES) { "所选文件过多，请改为选择模型目录或 ZIP。" }
        val pending = File(file.parentFile, "${file.name}.pending")
        FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 4 * 1024 * 1024

        private fun requestFile(filesDir: File, id: String): File {
            require(UUID.fromString(id).toString() == id) { "导入任务编号无效。" }
            return File(filesDir, "model-import-tasks/$id.json")
        }

        fun read(filesDir: File, id: String): ManagedModelImportRequest {
            val file = requestFile(filesDir, id)
            require(file.isFile && file.length() in 1..MAX_REQUEST_BYTES.toLong()) { "导入任务记录不可用，请重新选择源文件。" }
            return fromJson(file.readText(Charsets.UTF_8)).also { require(it.id == id) }
        }

        fun fromJson(text: String): ManagedModelImportRequest {
            val json = JSONObject(text)
            val uris = json.getJSONArray("uris")
            return ManagedModelImportRequest(
                id = json.getString("id"),
                uris = (0 until uris.length()).map { uris.getString(it) },
                directory = json.optBoolean("directory"),
                textOnly = json.optBoolean("textOnly"),
                persistentAccess = json.optBoolean("persistentAccess")
            )
        }
    }
}

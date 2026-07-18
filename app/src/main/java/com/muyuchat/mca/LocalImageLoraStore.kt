package com.muyuchat.mca

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class LocalImageLoraRecord(
    val id: String,
    val name: String,
    val fileName: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val importedAt: Long
) {
    fun toPrepared(multiplier: Double): LocalImagePreparedLora = LocalImagePreparedLora(
        id = id,
        name = name,
        path = path,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        multiplier = multiplier
    )
}

internal class LocalImageLoraStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, DIRECTORY).apply { mkdirs() }
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<LocalImageLoraRecord> {
        val raw = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toRecordOrNull()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
        val valid = parsed
            .distinctBy(LocalImageLoraRecord::id)
            .filter(::recordFileIsCurrent)
            .sortedByDescending(LocalImageLoraRecord::importedAt)
        if (valid != parsed) save(valid)
        return valid
    }

    @Synchronized
    fun import(uri: Uri): LocalImageLoraRecord {
        val displayName = displayName(uri)
        val extension = displayName.substringAfterLast('.', "").lowercase()
        require(extension in ALLOWED_EXTENSIONS) {
            "LoRA 仅支持 .safetensors 或 .ckpt 文件"
        }
        val id = UUID.randomUUID().toString()
        val fileName = "lora-$id.$extension"
        val target = directChild(fileName)
        val part = directChild("$fileName.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取 LoRA 文件")
            input.use { source ->
                FileOutputStream(part).use { fileOutput ->
                    BufferedOutputStream(fileOutput).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > MAX_LORA_BYTES) {
                                throw IOException("LoRA 文件超过大小限制")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                        fileOutput.fd.sync()
                    }
                }
            }
            require(copied >= MIN_LORA_BYTES) { "LoRA 文件为空或不完整" }
            if (extension == "safetensors") {
                validateSafetensorsHeader(part)
            } else {
                validateCkptHeader(part)
            }
            val sha256 = digest.digest().toHex()
            load().firstOrNull { it.sha256 == sha256 && it.sizeBytes == copied }?.let { duplicate ->
                part.delete()
                return duplicate
            }
            if (target.exists() || !part.renameTo(target)) {
                throw IOException("无法原子保存 LoRA 文件")
            }
            val record = LocalImageLoraRecord(
                id = id,
                name = displayName.substringBeforeLast('.').ifBlank { "LoRA" }.take(MAX_NAME_CHARS),
                fileName = fileName,
                path = target.canonicalPath,
                sha256 = sha256,
                sizeBytes = copied,
                importedAt = System.currentTimeMillis()
            )
            save(listOf(record) + load().filterNot { it.id == record.id })
            return record
        } catch (error: Throwable) {
            part.delete()
            target.delete()
            throw error
        }
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val records = load()
        val record = records.firstOrNull { it.id == id } ?: return false
        val file = runCatching { File(record.path).canonicalFile }.getOrNull() ?: return false
        if (file.parentFile != root.canonicalFile) return false
        if (file.exists() && !file.delete() && file.exists()) return false
        save(records.filterNot { it.id == id })
        return true
    }

    private fun displayName(uri: Uri): String = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()?.trim()?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: "adapter.safetensors"

    private fun recordFileIsCurrent(record: LocalImageLoraRecord): Boolean = runCatching {
        val file = File(record.path).canonicalFile
        file.parentFile == root.canonicalFile &&
            file.name == record.fileName &&
            file.isFile &&
            record.sizeBytes in MIN_LORA_BYTES..MAX_LORA_BYTES &&
            file.length() == record.sizeBytes &&
            record.sha256.matches(SHA256_REGEX)
    }.getOrDefault(false)

    private fun JSONObject.toRecordOrNull(): LocalImageLoraRecord? = runCatching {
        val id = getString("id")
        require(UUID_REGEX.matches(id))
        val fileName = getString("fileName")
        val extension = fileName.substringAfterLast('.', "").lowercase()
        require(fileName == "lora-$id.$extension" && extension in ALLOWED_EXTENSIONS)
        val file = directChild(fileName)
        LocalImageLoraRecord(
            id = id,
            name = getString("name").trim().take(MAX_NAME_CHARS).ifBlank { "LoRA" },
            fileName = fileName,
            path = file.canonicalPath,
            sha256 = getString("sha256").lowercase(),
            sizeBytes = getLong("sizeBytes"),
            importedAt = getLong("importedAt")
        )
    }.getOrNull()

    private fun directChild(fileName: String): File = File(root, fileName).canonicalFile.also { file ->
        require(file.parentFile == root.canonicalFile) { "LoRA 文件路径无效" }
    }

    private fun save(records: List<LocalImageLoraRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("name", record.name)
                    .put("fileName", record.fileName)
                    .put("sha256", record.sha256)
                    .put("sizeBytes", record.sizeBytes)
                    .put("importedAt", record.importedAt)
            )
        }
        check(preferences.edit().putString(KEY_RECORDS, array.toString()).commit()) {
            "无法保存 LoRA 索引"
        }
    }

    private fun validateSafetensorsHeader(file: File) {
        BufferedInputStream(file.inputStream()).use { input ->
            val lengthBytes = ByteArray(Long.SIZE_BYTES)
            require(input.read(lengthBytes) == lengthBytes.size) { "safetensors 头不完整" }
            val headerLength = ByteBuffer.wrap(lengthBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .long
            require(headerLength in 2..MAX_SAFETENSORS_HEADER_BYTES) {
                "safetensors 头长度无效"
            }
            val header = ByteArray(headerLength.toInt())
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                require(read > 0) { "safetensors 头不完整" }
                offset += read
            }
            val json = JSONObject(header.toString(Charsets.UTF_8))
            require(json.keys().asSequence().any { key -> key != "__metadata__" }) {
                "safetensors 未包含张量"
            }
        }
    }

    private fun validateCkptHeader(file: File) {
        file.inputStream().buffered().use { input ->
            val first = input.read()
            val second = input.read()
            val zipContainer = first == 'P'.code && second == 'K'.code
            val pickleStream = first == 0x80 && second in 0x02..0x05
            require(zipContainer || pickleStream) { "ckpt 文件头无效" }
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    companion object {
        private const val DIRECTORY = "image_loras"
        private const val PREFERENCES = "image_loras_v1"
        private const val KEY_RECORDS = "records"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MIN_LORA_BYTES = 16L
        private const val MAX_LORA_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_SAFETENSORS_HEADER_BYTES = 16L * 1024L * 1024L
        private const val MAX_NAME_CHARS = 128
        private val ALLOWED_EXTENSIONS = setOf("safetensors", "ckpt")
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
        private val UUID_REGEX =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

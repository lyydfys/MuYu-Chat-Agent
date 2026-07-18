package com.muyuchat.mca

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class LocalImageUpscalerRecord(
    val id: String,
    val name: String,
    val fileName: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val importedAt: Long
) {
    fun toPrepared(): LocalImagePreparedUpscaler = LocalImagePreparedUpscaler(
        id = id,
        name = name,
        path = path,
        sha256 = sha256,
        sizeBytes = sizeBytes
    )
}

data class LocalImagePreparedUpscaler(
    val id: String,
    val name: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long
) {
    init {
        require(UUID_PATTERN.matches(id)) { "Upscaler id must be a UUID." }
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARS) { "Upscaler name is invalid." }
        require(path.isNotBlank() && path.length <= MAX_PATH_CHARS) { "Upscaler path is invalid." }
        require(SHA256_PATTERN.matches(sha256)) { "Upscaler sha256 is invalid." }
        require(sizeBytes in MIN_MODEL_BYTES..MAX_MODEL_BYTES) { "Upscaler size is invalid." }
    }

    fun toJson(includePath: Boolean = true): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("sha256", sha256)
        .put("sizeBytes", sizeBytes)
        .apply { if (includePath) put("path", path) }

    companion object {
        private const val MAX_NAME_CHARS = 128
        private const val MAX_PATH_CHARS = 4_096
        internal const val MIN_MODEL_BYTES = 16L
        internal const val MAX_MODEL_BYTES = 2L * 1024L * 1024L * 1024L
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val UUID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun fromJson(json: JSONObject): LocalImagePreparedUpscaler = LocalImagePreparedUpscaler(
            id = json.getString("id").trim().lowercase(),
            name = json.getString("name").trim(),
            path = json.getString("path").trim(),
            sha256 = json.getString("sha256").trim().lowercase(),
            sizeBytes = json.getLong("sizeBytes")
        )
    }
}

internal class LocalImageUpscalerStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, DIRECTORY).apply { mkdirs() }
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<LocalImageUpscalerRecord> {
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
            .distinctBy(LocalImageUpscalerRecord::id)
            .filter(::recordFileIsCurrent)
            .sortedByDescending(LocalImageUpscalerRecord::importedAt)
        if (valid != parsed) save(valid)
        return valid
    }

    @Synchronized
    fun import(uri: Uri): LocalImageUpscalerRecord {
        val displayName = displayName(uri)
        val extension = displayName.substringAfterLast('.', "").lowercase()
        require(extension in ALLOWED_EXTENSIONS) {
            "超分模型仅支持 .pth、.safetensors、.ckpt 或 .bin 文件"
        }
        val id = UUID.randomUUID().toString()
        val fileName = "upscaler-$id.$extension"
        val target = directChild(fileName)
        val part = directChild("$fileName.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取超分模型文件")
            input.use { source ->
                FileOutputStream(part).use { fileOutput ->
                    BufferedOutputStream(fileOutput).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > LocalImagePreparedUpscaler.MAX_MODEL_BYTES) {
                                throw IOException("超分模型文件超过大小限制")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                        fileOutput.fd.sync()
                    }
                }
            }
            require(copied >= LocalImagePreparedUpscaler.MIN_MODEL_BYTES) {
                "超分模型文件为空或不完整"
            }
            when (extension) {
                "safetensors" -> validateSafetensorsHeader(part)
                "pth", "ckpt" -> validateTorchArchiveHeader(part)
                "bin" -> Unit
            }
            val sha256 = digest.digest().toHex()
            load().firstOrNull { it.sha256 == sha256 && it.sizeBytes == copied }?.let { duplicate ->
                part.delete()
                return duplicate
            }
            if (target.exists() || !part.renameTo(target)) {
                throw IOException("无法原子保存超分模型文件")
            }
            val record = LocalImageUpscalerRecord(
                id = id,
                name = displayName.substringBeforeLast('.').ifBlank { "ESRGAN" }.take(MAX_NAME_CHARS),
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
        val lockFile = directChild(lockFileName(record.fileName))
        val lockChannel = runCatching { RandomAccessFile(lockFile, "rw").channel }
            .getOrNull() ?: return false
        return lockChannel.use { channel ->
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (_: IOException) {
                null
            } ?: return@use false
            try {
                if (file.exists() && !file.delete() && file.exists()) return@use false
                save(records.filterNot { it.id == id })
                true
            } finally {
                runCatching { lock.release() }
            }
        }
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
        ?: "upscaler.pth"

    private fun recordFileIsCurrent(record: LocalImageUpscalerRecord): Boolean = runCatching {
        val file = File(record.path).canonicalFile
        file.parentFile == root.canonicalFile &&
            file.name == record.fileName &&
            file.isFile &&
            record.sizeBytes in LocalImagePreparedUpscaler.MIN_MODEL_BYTES..LocalImagePreparedUpscaler.MAX_MODEL_BYTES &&
            file.length() == record.sizeBytes &&
            record.sha256.matches(SHA256_REGEX)
    }.getOrDefault(false)

    private fun JSONObject.toRecordOrNull(): LocalImageUpscalerRecord? = runCatching {
        val id = getString("id")
        require(UUID_REGEX.matches(id))
        val fileName = getString("fileName")
        val extension = fileName.substringAfterLast('.', "").lowercase()
        require(fileName == "upscaler-$id.$extension" && extension in ALLOWED_EXTENSIONS)
        val file = directChild(fileName)
        LocalImageUpscalerRecord(
            id = id,
            name = getString("name").trim().take(MAX_NAME_CHARS).ifBlank { "ESRGAN" },
            fileName = fileName,
            path = file.canonicalPath,
            sha256 = getString("sha256").lowercase(),
            sizeBytes = getLong("sizeBytes"),
            importedAt = getLong("importedAt")
        )
    }.getOrNull()

    private fun directChild(fileName: String): File = File(root, fileName).canonicalFile.also { file ->
        require(file.parentFile == root.canonicalFile) { "超分模型路径无效" }
    }

    private fun save(records: List<LocalImageUpscalerRecord>) {
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
            "无法保存超分模型索引"
        }
    }

    private fun validateSafetensorsHeader(file: File) {
        BufferedInputStream(file.inputStream()).use { input ->
            val lengthBytes = ByteArray(Long.SIZE_BYTES)
            require(input.read(lengthBytes) == lengthBytes.size) { "safetensors 头不完整" }
            val headerLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).long
            require(headerLength in 2..MAX_SAFETENSORS_HEADER_BYTES) { "safetensors 头长度无效" }
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

    private fun validateTorchArchiveHeader(file: File) {
        file.inputStream().buffered().use { input ->
            val first = input.read()
            val second = input.read()
            val zipContainer = first == 'P'.code && second == 'K'.code
            val pickleStream = first == 0x80 && second in 0x02..0x05
            require(zipContainer || pickleStream) { "PyTorch/ckpt 文件头无效" }
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    companion object {
        internal const val DIRECTORY = "image_upscalers"
        internal fun lockFileName(modelFileName: String): String = "$modelFileName.lock"
        private const val PREFERENCES = "image_upscalers_v1"
        private const val KEY_RECORDS = "records"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_SAFETENSORS_HEADER_BYTES = 16L * 1024L * 1024L
        private const val MAX_NAME_CHARS = 128
        private val ALLOWED_EXTENSIONS = setOf("pth", "safetensors", "ckpt", "bin")
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
        private val UUID_REGEX =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

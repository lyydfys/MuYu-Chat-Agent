package com.muyuchat.core.modelstore

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

internal data class ModelImportSource(
    val identity: String,
    val path: String,
    val size: Long? = null,
    val modified: Long? = null,
    val open: () -> InputStream
)

/** Only completed, re-hashed components are reused. A partial stream is always recopied from byte zero. */
internal class ResumableModelImport(
    private val managedRoot: File,
    identity: String,
    resumeKey: String?,
    newDestination: () -> File,
    private val onProgress: (ModelImportProgress) -> Unit = {},
    private val checkCancelled: () -> Unit = {}
) {
    private val key = digestText(resumeKey ?: UUID.randomUUID().toString())
    private val workspace = safeMnnImportFile(managedRoot, ".mca-import-resume-$key")
    private val stateFile = File(workspace, "state.json")
    private val state: JSONObject
    val contentRoot = File(workspace, "content")
    val destination: File

    init {
        require(workspace.mkdirs() || workspace.isDirectory) { "无法创建模型导入暂存目录。" }
        state = if (stateFile.isFile) readBoundedMnnJson(stateFile) else JSONObject()
        val identityHash = digestText(identity)
        require(!state.has("identity") || state.getString("identity") == identityHash) {
            "此导入任务对应的源文件或模式已变更，请创建新导入任务。"
        }
        destination = if (state.has("destination")) safeMnnImportFile(managedRoot, state.getString("destination"))
            else newDestination().canonicalFile
        require(destination.parentFile == managedRoot.canonicalFile && destination != workspace) {
            "模型导入目标不在受管理目录内。"
        }
        state.put("identity", identityHash).put("destination", destination.name)
        if (!state.has("components")) state.put("components", JSONObject())
        writeMnnImportJsonAtomically(stateFile, state)
        require(contentRoot.mkdirs() || contentRoot.isDirectory) { "无法创建模型组件暂存目录。" }
    }

    fun completedModel(): ModelManifest? = state.optJSONObject("completedModel")?.let {
        if (destination.exists()) {
            requireOwnedDestination()
            ModelManifest.fromJson(it)
        } else null
    }

    fun requireOwnedDestination() {
        if (!destination.exists()) return
        when (state.optString("commitType")) {
            "file" -> require(destination.isFile &&
                cancellableImportSha256(destination, checkCancelled) == state.optString("commitSha256")) {
                "已提交的模型文件已变更，请重新选择源文件导入。"
            }
            "directory" -> require(destination.isDirectory &&
                File(destination, OWNER_MARKER).takeIf { it.isFile }?.let { readBoundedMnnJson(it).optString("owner") } == key) {
                "模型导入目标已被其他文件占用，请重新选择源文件导入。"
            }
            else -> error("模型导入目标已存在但不属于此任务，请重新选择源文件导入。")
        }
    }

    fun copySources(sources: List<ModelImportSource>): List<File> {
        require(sources.map { normalizeMnnImportPath(it.path) }.distinct().size == sources.size) {
            "模型导入包含重名组件，无法确定每个配置引用对应哪个文件。请改用完整目录或 ZIP。"
        }
        val total = sources.map { it.size }.takeIf { sizes -> sizes.all { it != null && it >= 0L } }
            ?.fold(0L) { sum, size -> Math.addExact(sum, requireNotNull(size)) }
        var completedBytes = 0L
        return sources.mapIndexed { index, source ->
            checkCancelled()
            val path = normalizeMnnImportPath(source.path)
            val target = safeMnnImportFile(contentRoot, path)
            val fingerprint = digestText("${source.identity}\n${source.size}\n${source.modified}")
            val record = state.getJSONObject("components").optJSONObject(path)
            val completedBefore = completedBytes
            val reusable = source.size != null && source.modified != null && source.modified > 0 &&
                target.isFile && record?.optString("source") == fingerprint &&
                record.optLong("bytes", -1L) == target.length() &&
                cancellableImportSha256(target, checkCancelled) { bytes ->
                    onProgress(ModelImportProgress("verifying", path, index + 1, sources.size, completedBefore + bytes, total))
                } == record.optString("sha256")
            if (!reusable) {
                target.parentFile?.mkdirs()
                val part = File(target.parentFile, ".${target.name}.mca-copy-part")
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                try {
                    source.open().buffered().use { input ->
                        FileOutputStream(part).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                checkCancelled()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                bytes = Math.addExact(bytes, read.toLong())
                                onProgress(ModelImportProgress("copying", path, index + 1, sources.size, completedBefore + bytes, total))
                            }
                            output.fd.sync()
                        }
                    }
                    checkCancelled()
                    val sourceHash = digest.digest().toHex()
                    require(cancellableImportSha256(part, checkCancelled) { read ->
                        onProgress(ModelImportProgress("verifying", path, index + 1, sources.size, completedBefore + read, total))
                    } == sourceHash) { "模型组件复制后摘要不一致：$path" }
                    checkCancelled()
                    java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    state.getJSONObject("components").put(path, JSONObject().put("source", fingerprint)
                        .put("bytes", bytes).put("sha256", sourceHash))
                    writeMnnImportJsonAtomically(stateFile, state)
                } finally {
                    // This file belongs solely to this transaction; retain completed siblings for retry.
                    if (part.exists()) part.delete()
                }
            }
            completedBytes += target.length()
            onProgress(ModelImportProgress("copying", path, index + 1, sources.size, completedBytes, total))
            target
        }
    }

    fun commit(staged: File) {
        checkCancelled()
        require(staged.canonicalFile.toPath().startsWith(workspace.canonicalFile.toPath())) {
            "模型导入暂存路径无效。"
        }
        require(!destination.exists()) { "模型导入目标已存在，请重新检查导入任务。" }
        onProgress(ModelImportProgress("committing"))
        checkCancelled()
        if (staged.isDirectory) {
            val marker = File(staged, OWNER_MARKER)
            if (marker.exists()) require(readBoundedMnnJson(marker).optString("owner") == key) { "模型组件与导入事务标记冲突。" }
            writeMnnImportJsonAtomically(marker, JSONObject().put("owner", key))
            state.put("commitType", "directory")
        } else {
            state.put("commitType", "file").put("commitSha256", cancellableImportSha256(staged, checkCancelled))
        }
        writeMnnImportJsonAtomically(stateFile, state)
        checkCancelled()
        require(staged.renameTo(destination)) { "无法原子提交模型导入。" }
    }

    fun complete(model: ModelManifest): ModelManifest {
        require(File(model.path).canonicalFile == destination) { "模型导入完成路径不一致。" }
        state.put("completedModel", model.toJson())
        writeMnnImportJsonAtomically(stateFile, state)
        // Keep the small receipt for idempotent WorkManager redelivery, not a second copy of the model.
        if (contentRoot.exists()) contentRoot.deleteRecursively()
        onProgress(ModelImportProgress("completed", model.fileName, 1, 1, model.sizeBytes, model.sizeBytes))
        return model
    }

    companion object { private const val OWNER_MARKER = ".mca-import-owner.json" }
}

internal fun cancellableImportSha256(file: File, checkCancelled: () -> Unit = {}, onBytes: (Long) -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            bytes += count
            onBytes(bytes)
        }
    }
    return digest.digest().toHex()
}

private fun digestText(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Recover subdirectories from config dependencies only when every basename maps unambiguously. */
internal fun mapMnnSelectedComponents(sources: List<ModelImportSource>, mode: MnnImportMode): List<ModelImportSource> {
    val grouped = sources.groupBy { it.path.substringAfterLast('/') }
    require(grouped.values.none { it.size > 1 }) {
        "MNN 多选包含同名文件，无法判断组件目录。请选择完整模型文件夹或 ZIP。"
    }
    val configSource = grouped["config.json"]?.singleOrNull()
        ?: error("MNN 多选缺少 config.json，请选择完整模型文件夹或全部组件。")
    fun read(source: ModelImportSource): JSONObject = source.open().buffered().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= 16 * 1024 * 1024) {
            val count = input.read(buffer, 0, minOf(buffer.size, 16 * 1024 * 1024 + 1 - output.size()))
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= 16 * 1024 * 1024) { "MNN 配置超过 16 MiB：${source.path}" }
        JSONObject(bytes.toString(Charsets.UTF_8))
    }
    val original = read(configSource)
    val prefix = mnnImportBasePrefix(original)
    val root = normalizedMnnImportConfig(original, prefix, mode, rootConfig = true)
    val modelPath = root.optString("llm_config").ifBlank { "llm_config.json" }
    val modelSource = grouped[modelPath.substringAfterLast('/')]?.singleOrNull()
    val model = modelSource?.let { normalizedMnnImportConfig(read(it), prefix, mode) }
    val dependencies = linkedSetOf("config.json", normalizeMnnImportPath(modelPath))
    fun collect(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                val child = value.opt(key)
                if (key in MNN_IMPORT_COMPONENT_KEYS && child is String && child.isNotBlank()) {
                    dependencies += normalizeMnnImportPath(child)
                } else collect(child)
            }
            is org.json.JSONArray -> (0 until value.length()).forEach { collect(value.opt(it)) }
        }
    }
    collect(root)
    collect(model)
    val effective = JSONObject(root.toString()).apply { model?.keys()?.forEach { key -> put(key, model.opt(key)) } }
    mapOf("llm_model" to "llm.mnn", "llm_weight" to "llm.mnn.weight", "embedding_file" to "embeddings_bf16.bin").forEach { (key, name) ->
        if (!effective.has(key)) dependencies += listOf(prefix, name).filter(String::isNotEmpty).joinToString("/")
    }
    if (!effective.has("tokenizer_file")) {
        listOf("tokenizer.txt", "tokenizer.mtok").forEach { dependencies += listOf(prefix, it).filter(String::isNotEmpty).joinToString("/") }
    }
    if (effective.optBoolean("is_visual") && !effective.has("visual_model")) dependencies += listOf(prefix, "visual.mnn").filter(String::isNotEmpty).joinToString("/")
    if (effective.optBoolean("is_audio") && !effective.has("audio_model")) dependencies += listOf(prefix, "audio.mnn").filter(String::isNotEmpty).joinToString("/")
    val byName = dependencies.groupBy { it.substringAfterLast('/') }
    return sources.map { source ->
        val matches = byName[source.path.substringAfterLast('/')].orEmpty()
        require(matches.size <= 1) { "MNN 配置多处引用同名组件 ${source.path}，多选无法确定映射；请改用完整目录或 ZIP。" }
        source.copy(path = matches.singleOrNull() ?: normalizeMnnImportPath(source.path))
    }
}

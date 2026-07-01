package com.muyuchat.core.modelstore

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ModelStoreRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val manifestFile: File by lazy { File(appContext.filesDir, "mca-models.json") }
    private val managedModelDir: File by lazy {
        (appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models")).also { it.mkdirs() }
    }

    fun listModels(): List<ModelManifest> {
        if (!manifestFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(manifestFile.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    add(ModelManifest.fromJson(array.getJSONObject(index)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun getModel(id: String): ModelManifest? = listModels().firstOrNull { it.id == id }

    fun importFromUri(uri: Uri, displayNameOverride: String? = null): ModelManifest {
        val fileName = displayNameOverride?.takeIf { it.isNotBlank() } ?: queryDisplayName(uri) ?: "model.gguf"
        require(fileName.endsWith(".gguf", ignoreCase = true)) { "请选择 .gguf 模型文件。" }
        val metadata = GgufMetadataReader.read(appContext.contentResolver, uri, fileName)
        require(metadata.isGguf) { "文件头不是 GGUF，可能不是 llama.cpp 可加载模型。" }

        managedModelDir.mkdirs()
        val target = uniqueTarget(fileName)
        appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选文件。" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val compatibility = ModelCompatibility.check(target, metadata)
        if (!compatibility.canLoad) {
            target.delete()
            error(compatibility.message)
        }

        val manifest = ModelManifest(
            id = UUID.randomUUID().toString(),
            displayName = fileName.removeSuffix(".gguf"),
            path = target.absolutePath,
            runtime = ChatModelRuntime.LLAMA_CPP,
            source = ModelSource.LOCAL,
            fileName = target.name,
            sizeBytes = target.length(),
            sha256 = sha256(target),
            quant = metadata.quant,
            architecture = metadata.architecture
        )
        upsert(manifest)
        return manifest
    }

    fun registerDownloadedModel(
        file: File,
        repoId: String,
        revision: String,
        license: String? = null,
        source: ModelSource = ModelSource.MODELSCOPE
    ): ModelManifest {
        require(file.exists()) { "Downloaded file does not exist: ${file.absolutePath}" }
        require(file.extension.equals("gguf", ignoreCase = true)) { "下载完成的聊天模型不是 GGUF。" }
        val metadata = GgufMetadataReader.read(file)
        require(metadata.isGguf) { "下载完成的文件不是 GGUF。" }
        val compatibility = ModelCompatibility.check(file, metadata)
        require(compatibility.canLoad) { compatibility.message }
        val manifest = ModelManifest(
            id = UUID.randomUUID().toString(),
            displayName = file.name.removeSuffix(".gguf"),
            path = file.absolutePath,
            runtime = ChatModelRuntime.LLAMA_CPP,
            source = source,
            repoId = repoId,
            revision = revision,
            fileName = file.name,
            sizeBytes = file.length(),
            sha256 = sha256(file),
            quant = metadata.quant,
            architecture = metadata.architecture,
            license = license
        )
        upsert(manifest)
        return manifest
    }

    fun deleteModel(id: String): Boolean {
        val models = listModels()
        val target = models.firstOrNull { it.id == id } ?: return false
        runCatching {
            val file = File(target.path)
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        runCatching {
            val projector = target.visionProjectorPath?.let(::File)
            if (projector != null && projector.parentFile?.absolutePath == managedModelDir.absolutePath) {
                projector.delete()
            }
        }
        save(models.filterNot { it.id == id })
        return true
    }

    fun attachVisionProjector(modelId: String, uri: Uri, displayNameOverride: String? = null): ModelManifest {
        val models = listModels()
        val model = models.firstOrNull { it.id == modelId } ?: error("未找到要绑定视觉文件的本地模型。")
        val fileName = displayNameOverride?.takeIf { it.isNotBlank() } ?: queryDisplayName(uri) ?: "mmproj.gguf"
        require(fileName.endsWith(".gguf", ignoreCase = true)) { "请选择 .gguf 视觉投影器文件（通常文件名包含 mmproj）。" }
        val metadata = GgufMetadataReader.read(appContext.contentResolver, uri, fileName)
        require(metadata.isGguf) { "文件头不是 GGUF，可能不是 llama.cpp 可加载的视觉投影器。" }
        require(isVisionProjectorCandidate(fileName, metadata)) {
            "这个 GGUF 不像视觉投影器。请选择与主模型匹配的 mmproj / projector 文件。"
        }

        managedModelDir.mkdirs()
        val target = uniqueTarget(fileName)
        appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选视觉文件。" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val updated = model.copy(
            visionProjectorPath = target.absolutePath,
            visionProjectorFileName = target.name,
            visionProjectorSizeBytes = target.length(),
            visionProjectorSha256 = sha256(target)
        )
        save(models.map { if (it.id == modelId) updated else it })
        return updated
    }

    fun markLoaded(id: String) {
        save(listModels().map { model ->
            if (model.id == id) model.copy(lastLoadedAt = System.currentTimeMillis()) else model
        })
    }

    fun updateModel(model: ModelManifest): List<ModelManifest> {
        val models = listModels().map { existing ->
            if (existing.id == model.id) model else existing
        }
        save(models)
        return listModels()
    }

    fun verify(id: String): Boolean {
        val model = getModel(id) ?: return false
        val file = File(model.path)
        return file.exists() && file.length() == model.sizeBytes && sha256(file).equals(model.sha256, ignoreCase = true)
    }

    fun validateForLoad(id: String): ModelCompatibilityResult {
        val model = getModel(id) ?: return ModelCompatibilityResult(
            canLoad = false,
            title = "模型清单不存在",
            details = id
        )
        val file = File(model.path)
        val compatibility = runCatching { ModelCompatibility.check(file) }.getOrElse { error ->
            ModelCompatibilityResult(
                canLoad = false,
                title = "模型预检失败",
                details = error.message.orEmpty()
            )
        }
        if (!compatibility.canLoad) return compatibility
        val hashOk = runCatching {
            file.length() == model.sizeBytes && sha256(file).equals(model.sha256, ignoreCase = true)
        }.getOrDefault(false)
        val projectorOk = model.visionProjectorPath?.let { path ->
            val projector = File(path)
            projector.exists() &&
                projector.length() == model.visionProjectorSizeBytes &&
                model.visionProjectorSha256?.let { sha256(projector).equals(it, ignoreCase = true) } != false
        } ?: true
        return if (hashOk) {
            if (projectorOk) {
                compatibility
            } else {
                ModelCompatibilityResult(
                    canLoad = false,
                    title = "视觉投影器校验失败",
                    details = "绑定的 mmproj 文件不存在、大小变化或 SHA-256 不一致，请重新绑定视觉文件"
                )
            }
        } else {
            ModelCompatibilityResult(
                canLoad = false,
                title = "模型文件校验失败",
                details = "文件大小或 SHA-256 与导入/下载时不一致，建议删除后重新下载"
            )
        }
    }

    fun managedFileFor(fileName: String): File {
        managedModelDir.mkdirs()
        return uniqueTarget(fileName)
    }

    private fun upsert(model: ModelManifest) {
        val without = listModels().filterNot { it.id == model.id }
        save(without + model)
    }

    private fun save(models: List<ModelManifest>) {
        val array = JSONArray()
        models.sortedByDescending { it.createdAt }.forEach { array.put(it.toJson()) }
        manifestFile.writeText(array.toString(2), Charsets.UTF_8)
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun uniqueTarget(fileName: String): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var target = File(managedModelDir, safeName)
        val baseName = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        var index = 1
        while (target.exists()) {
            val nextName = if (extension == null) "$baseName-$index" else "$baseName-$index.$extension"
            target = File(managedModelDir, nextName)
            index += 1
        }
        return target
    }

    private fun isVisionProjectorCandidate(fileName: String, metadata: GgufMetadata): Boolean {
        val lower = fileName.lowercase()
        val architecture = metadata.architecture?.lowercase().orEmpty()
        return "mmproj" in lower ||
            "projector" in lower ||
            lower.startsWith("clip") ||
            architecture == "clip"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

}


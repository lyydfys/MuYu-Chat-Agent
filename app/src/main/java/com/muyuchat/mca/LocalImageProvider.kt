package com.muyuchat.mca

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ImageBackend {
    LOCAL,
    CLOUD
}

enum class LocalImageRuntime(val label: String) {
    STABLE_DIFFUSION_CPP("stable-diffusion.cpp"),
    ONNX_RUNTIME("ONNX Runtime"),
    CUSTOM("自定义本地图像引擎");

    companion object {
        fun from(value: String?): LocalImageRuntime =
            entries.firstOrNull { it.name == value } ?: when (value) {
                "MEDIAPIPE", "NCNN", "DIFFUSERS" -> STABLE_DIFFUSION_CPP
                "ONNX" -> ONNX_RUNTIME
                else -> CUSTOM
            }

        fun infer(fileName: String): LocalImageRuntime {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".onnx") -> ONNX_RUNTIME
                lower.endsWith(".gguf") ||
                    lower.endsWith(".safetensors") ||
                    lower.endsWith(".ckpt") ||
                    lower.endsWith(".pth") ||
                    lower.endsWith(".pt") ||
                    lower.endsWith(".zip") -> STABLE_DIFFUSION_CPP
                else -> CUSTOM
            }
        }
    }
}

enum class LocalImageModelFamily(val label: String) {
    Z_IMAGE("Z-Image"),
    QWEN_IMAGE("Qwen-Image"),
    GLM_IMAGE("GLM-Image"),
    LONGCAT_IMAGE("LongCat-Image"),
    DREAMLITE("DreamLite"),
    FLUX("Flux"),
    SD_TURBO("SD-Turbo"),
    SDXL("SDXL"),
    SD15("Stable Diffusion 1.5"),
    WAN("Wan"),
    CUSTOM("自定义");

    companion object {
        fun from(value: String?): LocalImageModelFamily =
            entries.firstOrNull { it.name == value } ?: CUSTOM

        fun infer(fileName: String): LocalImageModelFamily {
            val lower = fileName.lowercase()
            return when {
                "z-image" in lower || "z_image" in lower || "zimage" in lower -> Z_IMAGE
                "qwen-image" in lower || "qwen_image" in lower -> QWEN_IMAGE
                "glm-image" in lower || "glm_image" in lower -> GLM_IMAGE
                "longcat-image" in lower || "longcat_image" in lower -> LONGCAT_IMAGE
                "dreamlite" in lower -> DREAMLITE
                "flux" in lower -> FLUX
                "sd-turbo" in lower || "sd_turbo" in lower -> SD_TURBO
                "sdxl" in lower || "stable-diffusion-xl" in lower -> SDXL
                "sd-1.5" in lower || "sd15" in lower || "v1-5" in lower || "stable-diffusion-v1-5" in lower -> SD15
                "wan" in lower -> WAN
                else -> CUSTOM
            }
        }
    }
}

data class LocalImageModelRecord(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val runtime: LocalImageRuntime,
    val family: LocalImageModelFamily = LocalImageModelFamily.CUSTOM,
    val imageSize: String = "512x512",
    val source: String = "local",
    val bundleRoot: String? = null,
    val componentCount: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val configured: Boolean
        get() = path.isNotBlank() && File(path).exists() && bundleRoot?.let { File(it).exists() } != false

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("path", path)
        .put("fileName", fileName)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
        .put("runtime", runtime.name)
        .put("family", family.name)
        .put("imageSize", imageSize)
        .put("source", source)
        .put("bundleRoot", bundleRoot)
        .put("componentCount", componentCount)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(json: JSONObject): LocalImageModelRecord =
            LocalImageModelRecord(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                displayName = json.optString("displayName"),
                path = json.optString("path"),
                fileName = json.optString("fileName"),
                sizeBytes = json.optLong("sizeBytes"),
                sha256 = json.optString("sha256"),
                runtime = LocalImageRuntime.from(json.optString("runtime")),
                family = LocalImageModelFamily.from(json.optString("family")),
                imageSize = json.optString("imageSize", "512x512"),
                source = json.optString("source", "local"),
                bundleRoot = json.optString("bundleRoot").takeIf { it.isNotBlank() && it != "null" },
                componentCount = json.optInt("componentCount", 1).coerceAtLeast(1),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
    }
}

data class LocalImageResult(
    val bytes: ByteArray,
    val mimeType: String = "image/png"
)

data class LocalImageProgress(
    val phase: String,
    val message: String,
    val step: Int,
    val steps: Int,
    val elapsedMs: Long,
    val secondsPerStep: Double,
    val threads: Int,
    val width: Int,
    val height: Int,
    val cancelRequested: Boolean
)

class LocalImageProvider(context: Context) {
    private val appContext = context.applicationContext
    private val bridge by lazy { NativeStableDiffusionBridge() }

    fun cancel() {
        if (NativeStableDiffusionBridge.isAvailable) {
            runCatching { bridge.cancel() }
        }
    }

    fun nativeConfig(): JSONObject? =
        if (NativeStableDiffusionBridge.isAvailable) {
            runCatching { JSONObject(bridge.getNativeConfig()) }.getOrNull()
        } else {
            null
        }

    suspend fun generate(
        model: LocalImageModelRecord,
        prompt: String,
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult = withContext(Dispatchers.IO) {
        require(model.configured) { "本地图像生成模型文件不存在，请重新导入。" }
        require(prompt.isNotBlank()) { "请输入图片描述。" }
        require(model.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
            "当前仅支持 stable-diffusion.cpp 本地图像引擎。"
        }
        require(NativeStableDiffusionBridge.isAvailable) {
            val reason = NativeStableDiffusionBridge.loadError?.message.orEmpty()
            "stable-diffusion.cpp 本地后端加载失败${if (reason.isBlank()) "" else "：$reason"}"
        }
        model.localImageReadinessMessage()?.let { message -> error(message) }

        val outputDir = File(appContext.cacheDir, "local_image_outputs").apply { mkdirs() }
        val outputFile = File(outputDir, "sdcpp-${System.currentTimeMillis()}.png")
        val (width, height) = model.imageSize.toImageDimensions(model.family).fastLocalDimensions(model.family)
        val params = JSONObject()
            .put("prompt", prompt.trim())
            .put("family", model.family.name)
            .put("width", width)
            .put("height", height)
            .put("steps", defaultStepsFor(model.family))
            .put("threads", defaultLocalImageThreads())
            .put("cfgScale", defaultCfgFor(model.family))
            .put("distilledGuidance", 3.5)
            .put("flowShift", defaultFlowShiftFor(model.family))
            .put("sampleMethod", "euler")
            .put("backendMode", "cpu")

        val progressPoller = launch {
            while (isActive) {
                bridge.currentProgressOrNull()?.let(onProgress)
                delay(500)
            }
        }
        val raw = try {
            bridge.generate(
                model.path,
                model.bundleRoot.orEmpty(),
                params.toString(),
                outputFile.absolutePath
            )
        } finally {
            progressPoller.cancelAndJoin()
            bridge.currentProgressOrNull()?.let(onProgress)
        }
        val json = JSONObject(raw)
        if (!json.optBoolean("ok", false)) {
            val message = json.optString("error").ifBlank { "stable-diffusion.cpp 生成失败。" }
            error(message)
        }
        val generated = File(json.optString("path", outputFile.absolutePath))
        require(generated.exists() && generated.length() > 0L) { "stable-diffusion.cpp 未输出有效图片。" }
        LocalImageResult(
            bytes = generated.readBytes(),
            mimeType = json.optString("mimeType", "image/png")
        )
    }

    private fun NativeStableDiffusionBridge.currentProgressOrNull(): LocalImageProgress? =
        runCatching {
            val json = JSONObject(getProgress())
            LocalImageProgress(
                phase = json.optString("phase"),
                message = json.optString("message"),
                step = json.optInt("step"),
                steps = json.optInt("steps"),
                elapsedMs = json.optLong("elapsedMs"),
                secondsPerStep = json.optDouble("secondsPerStep"),
                threads = json.optInt("threads"),
                width = json.optInt("width"),
                height = json.optInt("height"),
                cancelRequested = json.optBoolean("cancelRequested")
            )
        }.getOrNull()

    private fun defaultLocalImageThreads(): Int {
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return when {
            available >= 10 -> 5
            available >= 8 -> 4
            available >= 6 -> 4
            available >= 4 -> 3
            else -> 2
        }.coerceAtMost(available).coerceAtLeast(1)
    }
}

class LocalImageModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("mca_local_image_models", Context.MODE_PRIVATE)
    private val managedDir: File by lazy {
        (appContext.getExternalFilesDir("image_models") ?: File(appContext.filesDir, "image_models")).also { it.mkdirs() }
    }

    fun loadModels(): List<LocalImageModelRecord> {
        val raw = prefs.getString(KEY_MODELS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                LocalImageModelRecord.fromJson(array.getJSONObject(index))
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun saveModels(models: List<LocalImageModelRecord>) {
        val array = JSONArray()
        models.sortedByDescending { it.updatedAt }.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_MODELS, array.toString()).apply()
    }

    fun updateModel(record: LocalImageModelRecord): List<LocalImageModelRecord> {
        val now = System.currentTimeMillis()
        val models = loadModels().map { existing ->
            if (existing.id == record.id) record.copy(updatedAt = now) else existing
        }
        saveModels(models)
        return models
    }

    fun importFromUri(uri: Uri): LocalImageModelRecord {
        val fileName = queryDisplayName(uri) ?: "image-model.task"
        val extension = fileName.substringAfterLast('.', "").lowercase()
        require(extension in SUPPORTED_EXTENSIONS) {
            "请选择 .gguf、.safetensors、.ckpt、.pth、.pt、.onnx，或包含 diffusion 主模型、VAE/AE、文本编码器/LLM 的 .zip 图像生成引擎包。"
        }
        if (extension == "zip") {
            return importBundleFromUri(uri, fileName)
        }
        managedDir.mkdirs()
        val target = uniqueTarget(fileName)
        inputStreamFor(uri).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val record = LocalImageModelRecord(
            displayName = fileName.substringBeforeLast('.', fileName),
            path = target.absolutePath,
            fileName = target.name,
            sizeBytes = target.length(),
            sha256 = sha256(target),
            runtime = LocalImageRuntime.infer(fileName),
            family = LocalImageModelFamily.infer(fileName),
            imageSize = defaultImageSizeFor(fileName)
        )
        saveModels(listOf(record) + loadModels().filterNot { it.id == record.id })
        if (loadSelectedModelId() == null && record.isReadyForLocalImageGeneration()) saveSelectedModelId(record.id)
        return record
    }

    fun registerDownloadedModel(
        file: File,
        remote: RemoteModelFile
    ): LocalImageModelRecord {
        require(file.exists()) { "下载完成的图像模型文件不存在：${file.absolutePath}" }
        if (file.extension.equals("zip", ignoreCase = true)) {
            return importBundleFromUri(Uri.fromFile(file), file.name).also {
                runCatching { file.delete() }
            }
        }
        val record = LocalImageModelRecord(
            displayName = file.name.substringBeforeLast('.', file.name),
            path = file.absolutePath,
            fileName = file.name,
            sizeBytes = file.length(),
            sha256 = sha256(file),
            runtime = LocalImageRuntime.infer(file.name),
            family = LocalImageModelFamily.infer("${remote.repoId}/${remote.path}/${file.name}"),
            imageSize = defaultImageSizeFor("${remote.repoId}/${file.name}"),
            source = "${remote.provider.name.lowercase()}:${remote.repoId}",
            updatedAt = System.currentTimeMillis()
        )
        saveModels(listOf(record) + loadModels().filterNot { it.sha256.equals(record.sha256, ignoreCase = true) })
        if (loadSelectedModelId() == null && record.isReadyForLocalImageGeneration()) saveSelectedModelId(record.id)
        return record
    }

    fun managedBundleDirFor(bundleId: String): File {
        val safeName = bundleId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image-engine" }
        return File(managedDir, "bundle-$safeName").also { it.mkdirs() }
    }

    fun managedBundleFileFor(bundleDir: File, fileName: String): File {
        val safeName = fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(bundleDir, safeName.ifBlank { "component-${System.currentTimeMillis()}" })
    }

    fun registerDownloadedBundle(
        displayName: String,
        bundleDir: File,
        primaryFile: File,
        primaryRemote: RemoteModelFile,
        componentCount: Int
    ): LocalImageModelRecord {
        require(bundleDir.isDirectory) { "本地生图引擎包目录不存在：${bundleDir.absolutePath}" }
        require(primaryFile.exists()) { "Local image engine bundle is missing a diffusion model: ${primaryFile.name}" }
        if (primaryFile.extension.equals("zip", ignoreCase = true)) {
            extractZipFileIntoDirectory(primaryFile, bundleDir)
            runCatching { primaryFile.delete() }
        }
        val resolvedPrimary = findPrimaryImageModel(bundleDir)
            ?: error("Local image engine bundle is missing a diffusion model.")
        val familyHint = "$displayName/${primaryRemote.repoId}/${primaryRemote.path}/${resolvedPrimary.name}"
        val record = LocalImageModelRecord(
            displayName = displayName,
            path = resolvedPrimary.absolutePath,
            fileName = resolvedPrimary.name,
            sizeBytes = bundleDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            sha256 = sha256(resolvedPrimary),
            runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            family = LocalImageModelFamily.infer(familyHint),
            imageSize = defaultImageSizeFor(familyHint),
            source = "${primaryRemote.provider.name.lowercase()}:${primaryRemote.repoId}",
            bundleRoot = bundleDir.absolutePath,
            componentCount = bundleDir.walkTopDown().count { it.isFile }.coerceAtLeast(componentCount).coerceAtLeast(1),
            updatedAt = System.currentTimeMillis()
        )
        record.localImageReadinessMessage()?.let { readiness ->
            error("图像生成引擎包不完整：$readiness")
        }
        saveModels(
            listOf(record) + loadModels().filterNot {
                it.bundleRoot == record.bundleRoot ||
                    (it.sha256.equals(record.sha256, ignoreCase = true) && it.imageSize == record.imageSize)
            }
        )
        saveSelectedModelId(record.id)
        saveSelectedBackend(ImageBackend.LOCAL)
        return record
    }

    fun managedFileFor(fileName: String): File {
        managedDir.mkdirs()
        return uniqueTarget(fileName)
    }

    fun deleteModel(id: String): Boolean {
        val models = loadModels()
        val target = models.firstOrNull { it.id == id } ?: return false
        runCatching {
            target.bundleRoot?.let { File(it).deleteRecursively() } ?: File(target.path).delete()
        }
        val remaining = models.filterNot { it.id == id }
        saveModels(remaining)
        if (loadSelectedModelId() == id) saveSelectedModelId(remaining.firstOrNull { it.isReadyForLocalImageGeneration() }?.id)
        return true
    }

    fun loadSelectedModelId(): String? =
        prefs.getString(KEY_SELECTED_MODEL_ID, null)?.takeIf { it.isNotBlank() }

    fun saveSelectedModelId(modelId: String?) {
        prefs.edit().putString(KEY_SELECTED_MODEL_ID, modelId.orEmpty()).apply()
    }

    fun loadSelectedBackend(): ImageBackend =
        runCatching { ImageBackend.valueOf(prefs.getString(KEY_SELECTED_BACKEND, ImageBackend.CLOUD.name).orEmpty()) }
            .getOrDefault(ImageBackend.CLOUD)

    fun saveSelectedBackend(backend: ImageBackend) {
        prefs.edit().putString(KEY_SELECTED_BACKEND, backend.name).apply()
    }

    private fun importBundleFromUri(uri: Uri, fileName: String): LocalImageModelRecord {
        val bundleDir = uniqueBundleDir(fileName.substringBeforeLast('.', fileName))
        bundleDir.mkdirs()
        val extracted = mutableListOf<File>()
        try {
            inputStreamFor(uri).use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        val target = File(bundleDir, entry.name.replace('\\', '/'))
                        val canonicalRoot = bundleDir.canonicalFile
                        val canonicalTarget = target.canonicalFile
                        require(canonicalTarget.path.startsWith(canonicalRoot.path)) { "图像生成引擎包包含不安全路径。" }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> zip.copyTo(output) }
                        if (target.extension.lowercase() in MODEL_FILE_EXTENSIONS) {
                            extracted += target
                        }
                        zip.closeEntry()
                    }
                }
            }
            val primary = extracted.sortedWith(
                compareByDescending<File> { it.name.isPrimaryImageModelName() }
                    .thenByDescending { it.length() }
            ).firstOrNull() ?: run {
                bundleDir.deleteRecursively()
                error("引擎包内没有找到可识别的 GGUF / safetensors / ckpt / ONNX 模型文件。")
            }
            val family = LocalImageModelFamily.infer(fileName).takeIf { it != LocalImageModelFamily.CUSTOM }
                ?: LocalImageModelFamily.infer(primary.name)
            val record = LocalImageModelRecord(
                displayName = fileName.substringBeforeLast('.', fileName),
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = bundleDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                sha256 = sha256(primary),
                runtime = LocalImageRuntime.infer(primary.name),
                family = family,
                imageSize = defaultImageSizeFor(if (family != LocalImageModelFamily.CUSTOM) family.name else primary.name),
                bundleRoot = bundleDir.absolutePath,
                componentCount = extracted.size.coerceAtLeast(1)
            )
            record.localImageReadinessMessage()?.let { readiness ->
                bundleDir.deleteRecursively()
                error("图像生成引擎包不完整：$readiness")
            }
            saveModels(listOf(record) + loadModels().filterNot { it.id == record.id })
            if (loadSelectedModelId() == null) saveSelectedModelId(record.id)
            return record
        } catch (error: Throwable) {
            if (bundleDir.exists()) runCatching { bundleDir.deleteRecursively() }
            throw error
        }
    }

    private fun extractZipFileIntoDirectory(zipFile: File, bundleDir: File) {
        val canonicalRoot = bundleDir.canonicalFile
        val canonicalZip = zipFile.canonicalFile
        zipFile.inputStream().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val target = File(bundleDir, entry.name.replace('\\', '/'))
                    val canonicalTarget = target.canonicalFile
                    require(canonicalTarget.path.startsWith(canonicalRoot.path)) {
                        "Image engine bundle contains an unsafe path."
                    }
                    if (canonicalTarget == canonicalZip) {
                        zip.closeEntry()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun findPrimaryImageModel(root: File): File? =
        root.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension.lowercase() in MODEL_FILE_EXTENSIONS }
            .sortedWith(
                compareByDescending<File> { it.name.isPrimaryImageModelName() }
                    .thenByDescending { it.length() }
            )
            .firstOrNull()

    private fun inputStreamFor(uri: Uri): InputStream {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: error("无法读取图像生成引擎文件。")
            return File(path).inputStream()
        }
        return requireNotNull(appContext.contentResolver.openInputStream(uri)) {
            "无法读取图像生成引擎文件。"
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun uniqueTarget(fileName: String): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val baseName = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "")
        var target = File(managedDir, safeName)
        var index = 1
        while (target.exists()) {
            val next = if (extension.isBlank()) "$baseName-$index" else "$baseName-$index.$extension"
            target = File(managedDir, next)
            index += 1
        }
        return target
    }

    private fun uniqueBundleDir(name: String): File {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image-bundle" }
        var target = File(managedDir, safeName)
        var index = 1
        while (target.exists()) {
            target = File(managedDir, "$safeName-$index")
            index += 1
        }
        return target
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

    companion object {
        private const val KEY_MODELS = "local_image_models_json"
        private const val KEY_SELECTED_MODEL_ID = "selected_local_image_model_id"
        private const val KEY_SELECTED_BACKEND = "selected_image_backend"
        private val MODEL_FILE_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "sft")
        private val SUPPORTED_EXTENSIONS = (MODEL_FILE_EXTENSIONS - "sft") + "zip"
    }
}

fun LocalImageModelRecord.localImageReadinessMessage(): String? {
    if (!configured) return "本地图像生成模型文件不存在，请重新导入。"
    if (runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        return "当前仅支持 stable-diffusion.cpp 本地图像引擎。"
    }
    if (!family.requiresCompanionComponents()) return null
    val requirement = family.requiredCompanionComponentHint()
    val root = bundleRoot?.let(::File)?.takeIf { it.isDirectory }
        ?: return "缺少组件包：${displayName} 只有 diffusion 主模型，还需要 $requirement。请在模型管理 > 文件导入包含 diffusion 主模型、VAE/AE、文本编码器/LLM 的 zip 引擎包。"
    val primary = runCatching { File(path).canonicalPath }.getOrDefault(path)
    val files = root.walkTopDown()
        .filter { it.isFile }
        .filter { it.extension.lowercase() in READINESS_MODEL_EXTENSIONS }
        .filterNot { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) == primary }
        .toList()
    val missing = buildList {
        if (files.none { it.isVaeComponentFile() }) add("VAE")
        if (files.none { it.isTextEncoderComponentFile() }) add("文本编码器/LLM")
    }
    return if (missing.isEmpty()) {
        null
    } else {
        "缺少组件：${missing.joinToString("、")}。${displayName} 需要 $requirement，不能只用单个 GGUF 生成图片。"
    }
}

fun LocalImageModelRecord.isReadyForLocalImageGeneration(): Boolean =
    localImageReadinessMessage() == null

private val READINESS_MODEL_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "sft")

private fun LocalImageModelFamily.requiresCompanionComponents(): Boolean =
    when (this) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.WAN -> true
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> false
    }

private fun LocalImageModelFamily.requiredCompanionComponentHint(): String =
    when (this) {
        LocalImageModelFamily.FLUX -> "VAE/AE（如 flux2_ae、ae.sft 或 ae.safetensors）和 Qwen3 4B 文本编码器/LLM"
        LocalImageModelFamily.QWEN_IMAGE -> "Qwen-Image VAE/AE 和 Qwen2.5-VL 文本编码器/LLM"
        LocalImageModelFamily.Z_IMAGE -> "VAE/AE 和 Qwen3 文本编码器/LLM"
        LocalImageModelFamily.LONGCAT_IMAGE -> "FLUX VAE/AE 和 Qwen2.5-VL 文本编码器/LLM"
        LocalImageModelFamily.SD_TURBO -> "SD-Turbo 完整 checkpoint"
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.WAN -> "VAE/AE 和文本编码器/LLM"
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> "VAE/AE 和文本编码器/LLM"
    }

private fun File.isVaeComponentFile(): Boolean {
    val lower = invariantSeparatorsPath.lowercase()
    return "vae" in lower ||
        lower.endsWith("/ae.sft") ||
        lower.endsWith("/ae.safetensors") ||
        lower.endsWith("_ae.safetensors") ||
        lower.endsWith("-ae.safetensors") ||
        lower.endsWith("_ae.gguf") ||
        lower.endsWith("-ae.gguf")
}

private fun File.isTextEncoderComponentFile(): Boolean {
    val lower = invariantSeparatorsPath.lowercase()
    return "text_encoder" in lower ||
        "text-encoder" in lower ||
        "text_encoders" in lower ||
        "t5xxl" in lower ||
        "t5-xxl" in lower ||
        "umt5" in lower ||
        "qwen2.5" in lower ||
        "qwen3" in lower ||
        "qwen_3" in lower ||
        "qwen-3" in lower ||
        "mistral" in lower ||
        "gemma" in lower ||
        "llm" in lower
}

private fun String.isPrimaryImageModelName(): Boolean {
    val lower = lowercase()
    val extension = substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("gguf", "safetensors", "sft", "ckpt", "pth", "pt", "onnx") &&
        (
            "diffusion" in lower ||
                "z-image" in lower ||
                "z_image" in lower ||
                "qwen-image" in lower ||
                "qwen_image" in lower ||
                "glm-image" in lower ||
                "glm_image" in lower ||
                "longcat-image" in lower ||
                "longcat_image" in lower ||
                "dreamlite" in lower ||
                "flux" in lower ||
                "sd-turbo" in lower ||
                "sd_turbo" in lower
            ) &&
        "vae" !in lower &&
        "clip" !in lower &&
        "text" !in lower &&
        "encoder" !in lower
}

private fun String.toImageDimensions(family: LocalImageModelFamily): Pair<Int, Int> {
    val parts = lowercase().split("x", "×").mapNotNull { it.trim().toIntOrNull() }
    if (parts.size >= 2) {
        return parts[0].coerceAtLeast(64) to parts[1].coerceAtLeast(64)
    }
    return when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SDXL -> 1024 to 1024
        LocalImageModelFamily.WAN -> 832 to 480
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> 512 to 512
    }
}

private fun Pair<Int, Int>.fastLocalDimensions(family: LocalImageModelFamily): Pair<Int, Int> {
    val maxDimension = when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SD_TURBO -> 512
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.WAN,
        LocalImageModelFamily.CUSTOM -> 384
    }
    val largest = maxOf(first, second)
    if (largest <= maxDimension) return first.alignImageDimension() to second.alignImageDimension()
    return ((first * maxDimension) / largest).alignImageDimension() to
        ((second * maxDimension) / largest).alignImageDimension()
}

private fun Int.alignImageDimension(): Int =
    (((this.coerceAtLeast(256) + 32) / 64) * 64).coerceIn(256, 1536)

private fun defaultStepsFor(family: LocalImageModelFamily): Int =
    when (family) {
        LocalImageModelFamily.Z_IMAGE -> 4
        LocalImageModelFamily.FLUX -> 4
        LocalImageModelFamily.SD_TURBO -> 1
        LocalImageModelFamily.QWEN_IMAGE -> 6
        LocalImageModelFamily.GLM_IMAGE -> 6
        LocalImageModelFamily.LONGCAT_IMAGE -> 6
        LocalImageModelFamily.DREAMLITE -> 6
        LocalImageModelFamily.SDXL -> 8
        LocalImageModelFamily.SD15 -> 8
        LocalImageModelFamily.WAN -> 6
        LocalImageModelFamily.CUSTOM -> 6
    }

private fun defaultCfgFor(family: LocalImageModelFamily): Double =
    when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SD_TURBO -> 1.0
        LocalImageModelFamily.QWEN_IMAGE -> 2.5
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.WAN,
        LocalImageModelFamily.CUSTOM -> 7.0
    }

private fun defaultFlowShiftFor(family: LocalImageModelFamily): Double =
    when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.WAN -> 3.0
        else -> 0.0
    }

private fun defaultImageSizeFor(fileName: String): String =
    when (LocalImageModelFamily.infer(fileName)) {
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE -> "768x768"
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SDXL -> "512x512"
        LocalImageModelFamily.SD_TURBO -> if ("384" in fileName) "384x384" else "512x512"
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.WAN,
        LocalImageModelFamily.CUSTOM -> "512x512"
    }

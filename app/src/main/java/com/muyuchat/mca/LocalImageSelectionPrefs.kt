package com.muyuchat.mca

import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import org.json.JSONArray

/**
 * T2B22-T5（b 工位）拆片：选择偏好读写（selectedModelId / selectedBackend）与引擎包导入
 * importBundleFromSource 自 LocalImageModelStore.kt 逐字节平移；声明行去缩进并登记为
 * LocalImageModelStore 扩展。debug 变体消费面以 import 接缝补齐（见 b-split 补丁）。
 */

fun LocalImageModelStore.loadSelectedModelId(): String? =
        prefs.getString(KEY_SELECTED_MODEL_ID, null)?.takeIf { it.isNotBlank() }

fun LocalImageModelStore.saveSelectedModelId(modelId: String?) = synchronized(imageModelCatalogLock) {
        val persisted = parseImageCatalogRecords(prefs.getString(KEY_MODELS, null).orEmpty())
        val normalized = normalizeLocalImageModelRecords(persisted, modelId)
        val canonicalId = normalized.firstOrNull { it.matchesCatalogId(modelId) }?.id ?: modelId
        val editor = prefs.edit().putString(KEY_SELECTED_MODEL_ID, canonicalId.orEmpty())
        if (normalized != persisted) {
            val rows = JSONArray()
            normalized.sortedByDescending { it.updatedAt }.forEach { rows.put(it.toJson()) }
            editor.putString(KEY_MODELS, rows.toString())
        }
        check(editor.commit()) { "无法保存所选模型。请释放存储空间后重试。" }
    }

fun LocalImageModelStore.loadSelectedBackend(): ImageBackend =
        runCatching { ImageBackend.valueOf(prefs.getString(KEY_SELECTED_BACKEND, ImageBackend.CLOUD.name).orEmpty()) }
            .getOrDefault(ImageBackend.CLOUD)

fun LocalImageModelStore.saveSelectedBackend(backend: ImageBackend) {
        prefs.edit().putString(KEY_SELECTED_BACKEND, backend.name).apply()
    }

internal fun LocalImageModelStore.importBundleFromSource(
        fileName: String, openSource: () -> InputStream, checkCancelled: () -> Unit, source: String
    ): LocalImageModelRecord {
        val catalogBefore = captureCatalogSnapshot()
        val workspace = File(managedDir, ".image-import-" + UUID.randomUUID())
        val stagedRoot = File(workspace, "content")
        check(stagedRoot.mkdirs()) { "无法创建图像引擎包导入临时目录。" }
        var promoted: File? = null
        try {
            val extraction = openSource().use { input ->
                extractBoundedImageBundleZip(input, stagedRoot, checkCancelled = checkCancelled)
            }
            checkCancelled()
            prepareMnnDiffusionTokenizerIfPossible(stagedRoot, checkCancelled)
            val manifest = localImageBundleManifestFromRoot(stagedRoot)
            val primary = manifest?.primaryFile ?: extraction.extractedFiles
                .filter { it.extension.lowercase(Locale.ROOT) in MODEL_FILE_EXTENSIONS }
                .sortedWith(compareByDescending<File> { it.name.isPrimaryImageModelName() }.thenByDescending { it.length() })
                .firstOrNull() ?: error("引擎包内没有找到可识别的 GGUF / safetensors / ckpt / ONNX / MNN 模型文件。")
            require(primary.canonicalFile.toPath().startsWith(stagedRoot.canonicalFile.toPath())) {
                "图像引擎包主模型位于导入目录之外。"
            }
            val family = manifest?.family
                ?: LocalImageModelFamily.infer(fileName).takeIf { it != LocalImageModelFamily.CUSTOM }
                ?: LocalImageModelFamily.infer(primary.name)
            val draft = LocalImageModelRecord(
                displayName = manifest?.displayName ?: fileName.substringBeforeLast('.', fileName),
                path = primary.absolutePath, fileName = primary.name, sizeBytes = 0L, sha256 = "",
                runtime = manifest?.runtime ?: inferLocalImageRuntimeForBundle(stagedRoot, primary), family = family,
                imageSize = manifest?.imageSize ?: defaultImageSizeFor(if (family != LocalImageModelFamily.CUSTOM) family.name else primary.name),
                bundleRoot = stagedRoot.absolutePath, source = source,
                recommendationId = manifest?.recommendationId ?: manifest?.id
            ).withObservedContent(checkCancelled)
            draft.localImageStructuralReadinessMessage()?.let { readiness -> error("图像生成引擎包不完整：$readiness") }
            checkCancelled()
            val target = uniqueBundleDir(fileName.substringBeforeLast('.', fileName))
            require(!target.exists() && stagedRoot.renameTo(target)) { "图像引擎包导入提交失败，源文件仍可重新导入。" }
            promoted = target
            val finalPrimary = File(target, primary.relativeTo(stagedRoot).path)
            checkCancelled()
            return registerRecord(draft.copy(path = finalPrimary.absolutePath, bundleRoot = target.absolutePath))
        } catch (failure: Throwable) {
            runCatching { restoreCatalogSnapshot(catalogBefore) }.exceptionOrNull()?.let(failure::addSuppressed)
            promoted?.let { file -> if (!runCatching { imageFileSharesCatalogOwnership(file, loadModels(discover = false)) }.getOrDefault(true))
                runCatching { if (file.exists()) file.deleteRecursively() } }
            throw failure
        } finally {
            if (workspace.exists()) runCatching { workspace.deleteRecursively() }
        }
    }

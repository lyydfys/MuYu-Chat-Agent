package com.muyuchat.mca

import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * T2B22-T5（b 工位）拆片：底层存储 IO helper（输入流 / 显示名 / 唯一路径 / sha256）与 6 个存储键
 * 常量自 LocalImageModelStore.kt 逐字节平移；常量行由 companion 迁为顶层 internal（去缩进登记），
 * helper 声明行去缩进并登记为 LocalImageModelStore 扩展。
 */

internal fun LocalImageModelStore.inputStreamFor(uri: Uri): InputStream {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: error("无法读取图像生成引擎文件。")
            return File(path).inputStream()
        }
        return requireNotNull(appContext.contentResolver.openInputStream(uri)) {
            "无法读取图像生成引擎文件。"
        }
    }

internal fun LocalImageModelStore.queryDisplayName(uri: Uri): String? {
        if (uri.scheme.equals("file", ignoreCase = true)) return uri.path?.let { File(it).name }
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

internal fun LocalImageModelStore.uniqueTarget(fileName: String): File {
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

internal fun LocalImageModelStore.uniqueBundleDir(name: String): File {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image-bundle" }
        var target = File(managedDir, safeName)
        var index = 1
        while (target.exists()) {
            target = File(managedDir, "$safeName-$index")
            index += 1
        }
        return target
    }

internal fun LocalImageModelStore.sha256(file: File): String {
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

internal const val KEY_MODELS = "local_image_models_json"
internal const val KEY_SUPPRESSED_PATHS = "suppressed_local_image_model_paths"
internal const val KEY_SELECTED_MODEL_ID = "selected_local_image_model_id"
internal const val KEY_SELECTED_BACKEND = "selected_image_backend"
internal val MODEL_FILE_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "sft", "mnn", "bin", "ctx", "qnn")
internal val SUPPORTED_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "mnn", "zip")

package com.muyuchat.mca

import java.io.File
import org.json.JSONObject

/**
 * T2B22-T5（b 工位）拆片：已装包 manifest 读取（readInstalledImageTask /
 * readInstalledImageManifest / findInstalledImageManifestForUi / installedImageBundleRootForUi）自
 * LocalImageUiCapabilities.kt 逐字节平移；声明行登记为 internal（跨件消费）。
 */

internal fun LocalImageModelRecord.readInstalledImageTask(): String? {
    val json = readInstalledImageManifest() ?: return null
    return runCatching {
        json.optString("task").trim().uppercase().takeIf(String::isNotEmpty)
            ?: json.optJSONObject("executionProfile")
                ?.optString("task")
                ?.trim()
                ?.uppercase()
                ?.takeIf(String::isNotEmpty)
            ?: json.optString("recommendationId")
                .trim()
                .lowercase()
                .let { id ->
                    when {
                        "controlnet" in id -> "CONTROL_IMAGE"
                        "sana" in id && "edit" in id -> "IMAGE_EDIT"
                        else -> null
                    }
                }
    }.getOrNull()
}

internal fun LocalImageModelRecord.readInstalledImageManifest(): JSONObject? {
    val root = installedImageBundleRootForUi() ?: return null
    val manifest = findInstalledImageManifestForUi(root) ?: return null
    return runCatching { JSONObject(manifest.readText(Charsets.UTF_8)) }.getOrNull()
}

internal fun findInstalledImageManifestForUi(root: File): File? =
    File(root, "manifest.json").takeIf(File::isFile)
        ?: root.walkTopDown()
            .maxDepth(4)
            .firstOrNull { file -> file.isFile && file.name.equals("manifest.json", ignoreCase = true) }

internal fun LocalImageModelRecord.installedImageBundleRootForUi(): File? = bundleRoot
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.takeIf(File::isDirectory)
        ?: File(path).parentFile?.takeIf(File::isDirectory)

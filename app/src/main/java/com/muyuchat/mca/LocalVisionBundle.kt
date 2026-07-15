package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineMinDeviceTier
import com.muyuchat.core.download.VisionModelAccelerator
import com.muyuchat.core.download.VisionModelBundleComponentRole
import com.muyuchat.core.download.VisionModelBundleRuntime
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class LocalVisionBundleComponent(
    val role: VisionModelBundleComponentRole,
    val path: String,
    val file: File?,
    val required: Boolean = true
)

internal data class LocalVisionBundleManifest(
    val id: String? = null,
    val displayName: String? = null,
    val runtime: VisionModelBundleRuntime? = null,
    val accelerator: VisionModelAccelerator? = null,
    val minDeviceTier: ImageEngineMinDeviceTier? = null,
    val requiresQnnRuntime: Boolean = false,
    val requiresSmokeTest: Boolean = true,
    val imageWidth: Int = 448,
    val imageHeight: Int = 448,
    val smokePrompt: String = "请用中文描述这张图片",
    val timeoutSeconds: Int = 60,
    val qnnSmokeSpec: QnnSmokeSpec = QnnSmokeSpec.Empty,
    val components: List<LocalVisionBundleComponent> = emptyList(),
    val primaryFile: File? = null,
    val projectorFile: File? = null,
    val componentCount: Int = 0
) {
    val smokeImageSize: String
        get() = "${imageWidth}x${imageHeight}"

    val npuActive: Boolean
        get() = false

    val missingRequiredComponents: List<String>
        get() = components
            .filter { it.required && it.file?.isFile != true }
            .map { component -> "${component.role.name}:${component.path}" }
}

internal fun localVisionBundleManifestFromRoot(root: File): LocalVisionBundleManifest? {
    if (!root.isDirectory) return null
    val manifestFile = root.findDescendantFile("manifest.json") ?: return null
    val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
    val components = manifest.optJSONArray("components")
    val smoke = manifest.optJSONObject("smoke") ?: manifest.optJSONObject("smokeSpec") ?: JSONObject()
    val runtime = manifest.optString("runtime").takeIf { it.isNotBlank() }?.let { value ->
        VisionModelBundleRuntime.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
    val accelerator = manifest.optString("accelerator").takeIf { it.isNotBlank() }?.let { value ->
        VisionModelAccelerator.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
    val minDeviceTier = manifest.optString("minDeviceTier").takeIf { it.isNotBlank() }?.let { value ->
        ImageEngineMinDeviceTier.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
    val parsedComponents = components?.toVisionComponents(root).orEmpty()
    val primaryFile = parsedComponents.firstOrNull { it.role == VisionModelBundleComponentRole.MAIN_MODEL }?.file
    val projectorFile = parsedComponents.firstOrNull { it.role == VisionModelBundleComponentRole.PROJECTOR }?.file
    return LocalVisionBundleManifest(
        id = manifest.optString("id").takeIf { it.isNotBlank() },
        displayName = manifest.optString("title").takeIf { it.isNotBlank() }
            ?: manifest.optString("displayName").takeIf { it.isNotBlank() }
            ?: manifest.optString("name").takeIf { it.isNotBlank() },
        runtime = runtime,
        accelerator = accelerator,
        minDeviceTier = minDeviceTier,
        requiresQnnRuntime = manifest.optBoolean("requiresQnnRuntime", false),
        requiresSmokeTest = manifest.optBoolean("requiresSmokeTest", true),
        imageWidth = smoke.optInt("imageWidth", smoke.optInt("width", 448)).coerceAtLeast(64),
        imageHeight = smoke.optInt("imageHeight", smoke.optInt("height", 448)).coerceAtLeast(64),
        smokePrompt = smoke.optString("prompt").ifBlank { "请用中文描述这张图片" },
        timeoutSeconds = smoke.optInt("timeoutSeconds", 60).coerceAtLeast(1),
        qnnSmokeSpec = QnnSmokeSpec.fromSmokeJson(smoke),
        components = parsedComponents,
        primaryFile = primaryFile,
        projectorFile = projectorFile,
        componentCount = components?.length() ?: 0
    )
}

private fun JSONArray.toVisionComponents(root: File): List<LocalVisionBundleComponent> = buildList {
    for (index in 0 until length()) {
        val component = optJSONObject(index) ?: continue
        val role = component.optString("role")
            .takeIf { it.isNotBlank() }
            ?.let { raw -> VisionModelBundleComponentRole.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
            ?: VisionModelBundleComponentRole.OPTIONAL
        val path = component.optString("path").takeIf { it.isNotBlank() }
            ?: component.optString("fileName").takeIf { it.isNotBlank() }
            ?: continue
        val file = root.safeDescendantOrNull(path)?.takeIf { it.isFile }
        add(
            LocalVisionBundleComponent(
                role = role,
                path = path,
                file = file,
                required = component.optBoolean("required", role != VisionModelBundleComponentRole.OPTIONAL)
            )
        )
    }
}

private fun File.findDescendantFile(fileName: String): File? =
    walkTopDown().firstOrNull { it.isFile && it.name.equals(fileName, ignoreCase = true) }

private fun File.safeDescendantOrNull(relativePath: String): File? {
    val normalized = relativePath.replace('\\', '/').trim().trimStart('/')
    if (normalized.isBlank()) return null
    val rootCanonical = canonicalFile
    val candidate = File(rootCanonical, normalized).canonicalFile
    return candidate.takeIf {
        it.path == rootCanonical.path || it.path.startsWith(rootCanonical.path + File.separator)
    }
}

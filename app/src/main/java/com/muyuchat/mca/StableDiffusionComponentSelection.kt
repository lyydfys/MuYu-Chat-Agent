package com.muyuchat.mca

import java.io.File
import org.json.JSONObject

internal const val STABLE_DIFFUSION_COMPONENT_MODE_MANIFEST = "manifest_roles"
internal const val STABLE_DIFFUSION_COMPONENT_MODE_COMPATIBILITY = "compatibility_inference"

/**
 * Auditable stable-diffusion.cpp component selection resolved before JNI is
 * entered. Installed MCA bundles use manifest roles and fail closed. The old
 * filename inference is retained only for manually imported bundles that do
 * not declare component roles.
 */
internal data class StableDiffusionComponentSelection(
    val mode: String,
    val family: LocalImageModelFamily,
    val bundleRoot: String,
    val primaryPath: String,
    val primarySlot: String,
    val vaePath: String? = null,
    val textEncoderPath: String? = null,
    val textEncoderSlot: String? = null,
    val tokenizerPath: String? = null,
    val manifestPath: String? = null,
    val requireVae: Boolean = false,
    val requireTextEncoder: Boolean = false,
    val requireTokenizer: Boolean = false
) {
    val fallback: Boolean
        get() = mode == STABLE_DIFFUSION_COMPONENT_MODE_COMPATIBILITY

    fun putIntoNativeParams(params: JSONObject): JSONObject = params
        .put("componentSelectionMode", mode)
        .put("componentBundleRoot", bundleRoot)
        .put("componentPrimaryPath", primaryPath)
        .put("componentPrimarySlot", primarySlot)
        .put("componentVaePath", vaePath.orEmpty())
        .put("componentTextEncoderPath", textEncoderPath.orEmpty())
        .put("componentTextEncoderSlot", textEncoderSlot.orEmpty())
        .put("componentTokenizerPath", tokenizerPath.orEmpty())
        .put("componentManifestPath", manifestPath.orEmpty())
        .put("componentRequireVae", requireVae)
        .put("componentRequireTextEncoder", requireTextEncoder)
        .put("componentRequireTokenizer", requireTokenizer)

    fun toAuditJson(): JSONObject = JSONObject()
        .put("mode", mode)
        .put("fallback", fallback)
        .put("family", family.name)
        .put("bundleRoot", bundleRoot)
        .put("primarySlot", primarySlot)
        .put("primaryPath", primaryPath)
        .put("vaePath", vaePath.orEmpty())
        .put("textEncoderPath", textEncoderPath.orEmpty())
        .put("textEncoderSlot", textEncoderSlot.orEmpty())
        .put("tokenizerPath", tokenizerPath.orEmpty())
        .put("manifestPath", manifestPath.orEmpty())
        .put("requireVae", requireVae)
        .put("requireTextEncoder", requireTextEncoder)
        .put("requireTokenizer", requireTokenizer)

    fun verifyNativeEcho(result: JSONObject): JSONObject {
        val actual = result.optJSONObject("componentSelection")
            ?: throw IllegalArgumentException(
                "stable-diffusion.cpp did not report the selected component paths."
            )
        require(actual.optString("mode") == mode) {
            "stable-diffusion.cpp component selection mode changed from $mode to ${actual.optString("mode")}."
        }
        require(actual.optBoolean("fallback", !fallback) == fallback) {
            "stable-diffusion.cpp component fallback state did not match the request."
        }
        require(actual.optString("primarySlot") == primarySlot) {
            "stable-diffusion.cpp selected an unexpected primary component slot."
        }
        requireSamePath("primaryPath", primaryPath, actual.optString("primaryPath"))
        if (fallback) {
            listOf("vaePath", "textEncoderPath", "tokenizerPath").forEach { name ->
                actual.optString(name).takeIf { it.isNotBlank() }?.let { selectedPath ->
                    requirePathInsideBundle(name, selectedPath)
                }
            }
            return actual
        }
        requireOptionalSamePath("vaePath", vaePath, actual.optString("vaePath"))
        requireOptionalSamePath(
            "textEncoderPath",
            textEncoderPath,
            actual.optString("textEncoderPath")
        )
        requireOptionalSamePath("tokenizerPath", tokenizerPath, actual.optString("tokenizerPath"))
        if (textEncoderPath != null) {
            require(actual.optString("textEncoderSlot") == textEncoderSlot) {
                "stable-diffusion.cpp selected an unexpected text encoder slot."
            }
        }
        return actual
    }

    private fun requirePathInsideBundle(name: String, selectedPath: String) {
        val root = File(bundleRoot).canonicalFile
        val selected = File(selectedPath).canonicalFile
        require(selected.isFile && selected.path.startsWith(root.path + File.separator)) {
            "stable-diffusion.cpp compatibility fallback selected unsafe $name=${selected.path}."
        }
    }

    private fun requireOptionalSamePath(name: String, expected: String?, actual: String) {
        if (expected == null) {
            require(actual.isBlank()) { "stable-diffusion.cpp unexpectedly selected $name=$actual." }
        } else {
            requireSamePath(name, expected, actual)
        }
    }

    private fun requireSamePath(name: String, expected: String, actual: String) {
        require(actual.isNotBlank()) { "stable-diffusion.cpp did not report $name." }
        val expectedCanonical = File(expected).canonicalPath
        val actualCanonical = File(actual).canonicalPath
        require(expectedCanonical == actualCanonical) {
            "stable-diffusion.cpp selected $name=$actualCanonical, expected $expectedCanonical."
        }
    }
}

internal fun resolveStableDiffusionComponentSelection(
    model: LocalImageModelRecord
): StableDiffusionComponentSelection {
    require(model.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        "Component selection is only available for stable-diffusion.cpp models."
    }
    val primary = File(model.path).canonicalFile
    require(primary.isFile) { "stable-diffusion.cpp primary model does not exist: ${primary.path}" }

    val candidateRoots = buildList {
        model.bundleRoot?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }?.let(::add)
        primary.parentFile?.takeIf { it.isDirectory }?.let(::add)
    }.map { it.canonicalFile }.distinctBy { it.path }
    val manifestFile = candidateRoots.asSequence()
        .mapNotNull(::findStableDiffusionManifest)
        .firstOrNull()
        ?: return compatibilityStableDiffusionSelection(model, primary, candidateRoots.firstOrNull())

    val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
    val components = manifest.optJSONArray("components")
    val hasDeclaredRole = components?.let { array ->
        (0 until array.length()).any { index ->
            array.optJSONObject(index)?.optString("role")?.isNotBlank() == true
        }
    } == true
    val isInstalledMcaManifest = manifest.optString("schema") == "mca.image_engine.bundle.v1"
    if (!hasDeclaredRole) {
        require(!isInstalledMcaManifest) {
            "Installed image bundle manifest does not declare component roles."
        }
        return compatibilityStableDiffusionSelection(model, primary, manifestFile.parentFile)
    }

    require(components != null && components.length() > 0) {
        "Image bundle manifest components are missing."
    }
    val root = manifestFile.parentFile.canonicalFile
    val effectiveFamily = manifest.optString("family")
        .takeIf { it.isNotBlank() }
        ?.let { LocalImageModelFamily.from(it) }
        ?.takeUnless { it == LocalImageModelFamily.CUSTOM && model.family != LocalImageModelFamily.CUSTOM }
        ?: model.family
    val byRole = linkedMapOf<String, ManifestComponentPath>()
    for (index in 0 until components.length()) {
        val component = components.optJSONObject(index)
            ?: throw IllegalArgumentException("Image bundle manifest component $index is not an object.")
        val role = component.optString("role").trim().uppercase()
        val required = component.optBoolean("required", role != "OPTIONAL")
        require(role.isNotBlank()) {
            "Role-based image bundle manifest component $index is missing role."
        }
        val rawPath = component.optString("path")
            .ifBlank { component.optString("fileName") }
        if (rawPath.isBlank()) {
            require(!required) { "Required image bundle component $role is missing path." }
            continue
        }
        val file = resolveManifestComponent(root, rawPath)
        if (required) {
            require(file.isFile) { "Required image bundle component $role does not exist: ${file.path}" }
        } else if (!file.exists()) {
            continue
        } else {
            require(file.isFile) { "Image bundle component $role is not a file: ${file.path}" }
        }
        if (role != "OPTIONAL" && role != "CONFIG" && role != "CONDITIONING") {
            require(byRole.put(role, ManifestComponentPath(file, required)) == null) {
                "Image bundle manifest declares more than one $role component."
            }
        }
    }

    val primaryComponent = listOf("DIFFUSION", "MODEL", "UNET", "TRANSFORMER")
        .mapNotNull(byRole::get)
        .singleOrNull()
        ?: throw IllegalArgumentException(
            "Role-based image bundle manifest must declare exactly one DIFFUSION component."
        )
    val splitFamily = effectiveFamily.requiresExplicitStableDiffusionCompanions()
    val vae = byRole["VAE"]
    val textEncoder = byRole["TEXT_ENCODER"]
    val tokenizer = byRole["TOKENIZER"]
    require(!splitFamily || vae != null) {
        "${effectiveFamily.label} image bundle manifest is missing required VAE role."
    }
    require(!splitFamily || textEncoder != null) {
        "${effectiveFamily.label} image bundle manifest is missing required TEXT_ENCODER role."
    }

    return StableDiffusionComponentSelection(
        mode = STABLE_DIFFUSION_COMPONENT_MODE_MANIFEST,
        family = effectiveFamily,
        bundleRoot = root.path,
        primaryPath = primaryComponent.file.path,
        primarySlot = if (splitFamily) "diffusion" else "model",
        vaePath = vae?.file?.path,
        textEncoderPath = textEncoder?.file?.path,
        textEncoderSlot = textEncoder?.let { effectiveFamily.stableDiffusionTextEncoderSlot() },
        tokenizerPath = tokenizer?.file?.path,
        manifestPath = manifestFile.canonicalPath,
        requireVae = splitFamily,
        requireTextEncoder = splitFamily,
        requireTokenizer = tokenizer?.required == true
    )
}

private data class ManifestComponentPath(val file: File, val required: Boolean)

private fun compatibilityStableDiffusionSelection(
    model: LocalImageModelRecord,
    primary: File,
    candidateRoot: File?
): StableDiffusionComponentSelection {
    val root = candidateRoot?.canonicalFile ?: primary.parentFile?.canonicalFile
        ?: throw IllegalArgumentException("stable-diffusion.cpp model has no bundle directory.")
    return StableDiffusionComponentSelection(
        mode = STABLE_DIFFUSION_COMPONENT_MODE_COMPATIBILITY,
        family = model.family,
        bundleRoot = root.path,
        primaryPath = primary.path,
        primarySlot = if (model.family.requiresExplicitStableDiffusionCompanions()) {
            "diffusion"
        } else {
            "model"
        }
    )
}

private fun findStableDiffusionManifest(root: File): File? {
    val direct = File(root, "manifest.json")
    if (direct.isFile) return direct.canonicalFile
    return root.walkTopDown()
        .maxDepth(2)
        .firstOrNull { it.isFile && it.name.equals("manifest.json", ignoreCase = true) }
        ?.canonicalFile
}

private fun resolveManifestComponent(root: File, rawPath: String): File {
    require(rawPath.isNotBlank() && '\u0000' !in rawPath) {
        "Image bundle component path is invalid."
    }
    val normalized = rawPath.replace('\\', '/').trim()
    require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:/").containsMatchIn(normalized)) {
        "Image bundle component path must be relative: $rawPath"
    }
    val candidate = File(root, normalized.replace('/', File.separatorChar)).canonicalFile
    require(candidate.path.startsWith(root.path + File.separator)) {
        "Image bundle component path escapes its bundle root: $rawPath"
    }
    return candidate
}

private fun LocalImageModelFamily.requiresExplicitStableDiffusionCompanions(): Boolean = when (this) {
    LocalImageModelFamily.Z_IMAGE,
    LocalImageModelFamily.QWEN_IMAGE,
    LocalImageModelFamily.GLM_IMAGE,
    LocalImageModelFamily.LONGCAT_IMAGE,
    LocalImageModelFamily.DREAMLITE,
    LocalImageModelFamily.FLUX,
    LocalImageModelFamily.WAN -> true

    LocalImageModelFamily.SANA,
    LocalImageModelFamily.SD_TURBO,
    LocalImageModelFamily.SDXL,
    LocalImageModelFamily.SD21,
    LocalImageModelFamily.SD15,
    LocalImageModelFamily.CUSTOM -> false
}

private fun LocalImageModelFamily.stableDiffusionTextEncoderSlot(): String = when (this) {
    LocalImageModelFamily.WAN -> "t5xxl"
    else -> "llm"
}

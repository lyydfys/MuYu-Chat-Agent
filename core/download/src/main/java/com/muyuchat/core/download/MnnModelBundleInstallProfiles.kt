package com.muyuchat.core.download

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

fun MnnModelBundleInstallProfile.stagedTransformer(): ModelBundleStagedTransformer? = when (this) {
    MnnModelBundleInstallProfile.STANDARD -> null
    MnnModelBundleInstallProfile.TEXT_ONLY -> MnnTextOnlyStagedTransformer
}

/**
 * Derives a text-only MNN package from publisher configs without touching the
 * downloaded model, tokenizer, metadata, or PLE bytes.
 */
internal object MnnTextOnlyStagedTransformer : ModelBundleStagedTransformer {
    private val transformedPaths = setOf(ROOT_CONFIG, LLM_CONFIG)

    override fun transform(
        contentRoot: File,
        stagedFiles: Map<String, File>
    ): ModelBundleStagedTransformResult {
        val canonicalRoot = contentRoot.canonicalFile
        transformedPaths.forEach { relativePath ->
            val file = requireNotNull(stagedFiles[relativePath]) {
                "MNN text-only install profile requires $relativePath."
            }.canonicalFile
            require(file.toPath().startsWith(canonicalRoot.toPath()) && file != canonicalRoot) {
                "MNN text-only config escapes the staging directory: $relativePath"
            }
            deriveTextOnlyConfig(file, relativePath)
        }
        return ModelBundleStagedTransformResult(transformedPaths)
    }

    private fun deriveTextOnlyConfig(file: File, relativePath: String) {
        require(file.isFile && file.canRead() && file.length() > 0L) {
            "MNN text-only install profile cannot read $relativePath."
        }
        val config = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }
            .getOrElse { error ->
                throw IllegalArgumentException(
                    "MNN text-only install profile found invalid JSON in $relativePath.",
                    error
                )
            }
        config.put("is_visual", false)
        config.put("is_audio", false)
        atomicWriteUtf8(file, config.toString(2) + "\n")
    }

    private fun atomicWriteUtf8(target: File, content: String) {
        val parent = requireNotNull(target.parentFile) { "Derived config has no parent directory." }
        val temp = File(parent, ".${target.name}.mca-text-only")
        if (temp.exists() && !temp.delete()) {
            throw IllegalStateException("Unable to remove stale derived-config temp file: $temp")
        }
        try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private const val ROOT_CONFIG = "config.json"
    private const val LLM_CONFIG = "llm_config.json"
}

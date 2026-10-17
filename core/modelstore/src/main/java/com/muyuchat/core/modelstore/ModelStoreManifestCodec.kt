package com.muyuchat.core.modelstore

import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import com.muyuchat.core.modelstore.ModelStoreRepository.Companion.MODEL_IMPORT_LOCK

private val CANONICAL_SHA256 = Regex("^[0-9a-fA-F]{64}$")

private fun String.isCanonicalSha256(): Boolean = CANONICAL_SHA256.matches(trim())

    internal fun ModelStoreRepository.listModelsImpl(): List<ModelManifest> = synchronized(MODEL_IMPORT_LOCK) {
        val persisted = readPersistedModels()
        val normalized = persisted.map { normalizeManifest(it) }
        // Catalog reads must retain damaged records for diagnosis and never
        // merge or delete distinct physical files from a bounded fingerprint.
        val pathDistinct = deduplicateModelRecordsByCanonicalPath(normalized)
        val recoveredMnn = recoverManagedMnnBundles(pathDistinct)
        val recoveredGguf = recoverManagedGgufModels(pathDistinct + recoveredMnn)
        val recoveredLiteRtLm = recoverManagedLiteRtLmModels(pathDistinct + recoveredMnn + recoveredGguf)
        val merged = deduplicateModelRecordsByCanonicalPath(
            pathDistinct + recoveredMnn + recoveredGguf + recoveredLiteRtLm
        )
        merged.sortedByDescending { it.createdAt }
    }

    internal fun ModelStoreRepository.readPersistedModels(): List<ModelManifest> {
        val backups = listOf(
            manifestFile,
            File(manifestFile.parentFile, ".${manifestFile.name}.backup"),
            File(manifestFile.parentFile, ".${manifestFile.name}.writing")
        )
        var damaged: List<ModelManifest>? = null
        backups.forEach { source ->
            if (source.isFile) {
                runCatching { parsePersistedModelManifest(source.readText(Charsets.UTF_8)) }
                    .getOrNull()?.let { parsed ->
                        if (parsed.none { it.integrityState == ModelIntegrityState.DAMAGED }) return parsed
                        if (damaged == null) damaged = parsed
                    }
            }
        }
        return damaged.orEmpty()
    }

    /** Keep compound registration (main model + projector) atomic to catalog readers. */

    internal fun <T> ModelStoreRepository.withCatalogTransactionImpl(block: () -> T): T = synchronized(MODEL_IMPORT_LOCK) {
        val previous = readPersistedModels()
        try {
            block()
        } catch (error: Throwable) {
            runCatching { save(previous) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    internal fun ModelStoreRepository.getModelImpl(id: String): ModelManifest? = listModels().firstOrNull { it.id == id || id in it.aliases }

    /**
     * Explicit, full-byte identity audit. The catalog is not rewritten until
     * every record has been checked and the replacement manifest is durable.
     * The caller can then migrate external references with [idMapping].
     */
    internal fun ModelStoreRepository.reconcileStableIdentitiesImpl(): ModelIdentityReconciliation = synchronized(MODEL_IMPORT_LOCK) {
        val persisted = readPersistedModels()
        val catalog = listModels()
        val retainedCopies = persisted.flatMap { model ->
            model.physicalCopies.filter { copy -> canonicalModelPath(copy.path) != canonicalModelPath(model.path) }
                .map { copy -> model.copy(
                id = UUID.nameUUIDFromBytes(
                    "retained-copy:${model.id}:${canonicalModelPath(copy.path)}".toByteArray(Charsets.UTF_8)
                ).toString(),
                path = copy.path,
                sizeBytes = copy.sizeBytes,
                sha256 = copy.sha256,
                visionProjectorPath = copy.visionProjectorPath,
                visionProjectorSizeBytes = copy.visionProjectorSizeBytes,
                visionProjectorSha256 = copy.visionProjectorSha256,
                source = copy.source,
                repoId = copy.repoId,
                revision = copy.revision,
                createdAt = Long.MIN_VALUE,
                lastLoadedAt = null,
                fileName = File(copy.path).name,
                visionValidated = false,
                physicalCopies = emptyList(),
                aliases = emptyList()
            ) }
        }
        // Audit each physical path independently. A retained copy may have changed
        // since the last reconciliation and must not inherit its parent's id or
        // enter a complete component group before its own byte hash is checked.
        val checked = (catalog.map { it.copy(physicalCopies = emptyList()) } + retainedCopies)
            .map(::inspectModelIdentity)
        val result = reconcileStableModelIdentities(checked)
        if (result.models != persisted) {
            val pending = pendingIdentityReferenceMigration()
            val mergedMapping = pending.mapValues { (_, priorTarget) ->
                result.idMapping[priorTarget] ?: priorTarget
            } + result.idMapping
            if (mergedMapping.any { (old, new) -> old != new }) writeModelIdentityJournal(mergedMapping)
            save(result.models)
        }
        result
    }

    internal fun ModelStoreRepository.pendingIdentityReferenceMigrationImpl(): Map<String, String> = synchronized(MODEL_IMPORT_LOCK) {
        val journal = File(appContext.filesDir, "mca-model-identity-migration.json")
        val backup = File(journal.parentFile, ".${journal.name}.backup")
        val source = journal.takeIf(File::isFile) ?: backup.takeIf(File::isFile) ?: return@synchronized emptyMap()
        runCatching {
            val mapping = org.json.JSONObject(source.readText(Charsets.UTF_8)).getJSONObject("idMapping")
            mapping.keys().asSequence().associateWith(mapping::getString)
        }.getOrDefault(emptyMap())
    }

    internal fun ModelStoreRepository.acknowledgeIdentityReferenceMigrationImpl() = synchronized(MODEL_IMPORT_LOCK) {
        val journal = File(appContext.filesDir, "mca-model-identity-migration.json")
        if (journal.exists() && !journal.delete()) error("无法清理已完成的模型引用迁移日志。")
        val backup = File(journal.parentFile, ".${journal.name}.backup")
        if (backup.exists() && !backup.delete()) error("无法清理已完成的模型引用迁移日志备份。")
    }

    private fun ModelStoreRepository.writeModelIdentityJournal(idMapping: Map<String, String>) {
        val journal = File(appContext.filesDir, "mca-model-identity-migration.json")
        val mapping = org.json.JSONObject().apply {
            idMapping.forEach { (old, stable) -> put(old, stable) }
        }
        val payload = org.json.JSONObject().put("schema", 1).put("idMapping", mapping)
        val staging = File(journal.parentFile, ".${journal.name}.writing")
        val backup = File(journal.parentFile, ".${journal.name}.backup")
        staging.parentFile?.mkdirs()
        FileOutputStream(staging).use { output ->
            output.write(payload.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (journal.isFile) {
            if (backup.exists() && !backup.delete()) error("无法替换模型引用迁移日志备份。")
            if (!journal.renameTo(backup)) error("无法备份模型引用迁移日志。")
        }
        if (!staging.renameTo(journal)) {
            if (!journal.exists()) backup.renameTo(journal)
            error("无法提交模型引用迁移日志。")
        }
        backup.delete()
    }

    private fun ModelStoreRepository.inspectModelIdentity(model: ModelManifest): ModelManifest {
        if (model.integrityState == ModelIntegrityState.DAMAGED &&
            model.diagnostic?.startsWith("模型清单") == true) return model
        val main = File(model.path)
        if (!main.exists() || !main.canRead()) return model.copy(
            integrityState = ModelIntegrityState.DAMAGED,
            diagnostic = "模型文件不存在或不可读：${model.path}"
        )
        val digest = runCatching {
            when (model.runtime) {
                ChatModelRuntime.MNN -> {
                    val readiness = MnnBundleReadinessAnalyzer.analyze(main)
                    require(readiness.canLoad) { readiness.diagnosticSummary() }
                    sha256MnnBundle(main, readiness.requiredComponentPaths)
                }
                ChatModelRuntime.GENIEX_QAIRT -> sha256Directory(main)
                else -> {
                    require(main.isFile) { "模型路径不是文件" }
                    sha256(main)
                }
            }
        }.getOrElse { error ->
            return model.copy(integrityState = ModelIntegrityState.DAMAGED,
                diagnostic = "无法计算模型完整摘要：${error.message.orEmpty()}")
        }
        val legacyMnnDirectoryHash = if (model.runtime == ChatModelRuntime.MNN &&
            !model.sha256.equals(digest, ignoreCase = true) && main.isDirectory
        ) runCatching { sha256Directory(main) }.getOrNull() else null
        if (model.sha256.isCanonicalSha256() && !isFastRecoveryFingerprint(model.sha256) &&
            !model.sha256.equals(digest, ignoreCase = true) &&
            !model.sha256.equals(legacyMnnDirectoryHash, ignoreCase = true)) {
            return model.copy(integrityState = ModelIntegrityState.DAMAGED,
                diagnostic = "模型完整 SHA-256 与清单不一致")
        }
        val projector = model.visionProjectorPath?.let(::File)
        val projectorDigest = projector?.let { file ->
            if (!file.isFile || !file.canRead()) return model.copy(
                integrityState = ModelIntegrityState.DAMAGED,
                diagnostic = "视觉投影器不存在或不可读：${model.visionProjectorPath}"
            )
            runCatching { sha256(file) }.getOrElse { error ->
                return model.copy(integrityState = ModelIntegrityState.DAMAGED,
                    diagnostic = "无法计算视觉投影器摘要：${error.message.orEmpty()}")
            }
        }
        if (projectorDigest != null && model.visionProjectorSha256?.isCanonicalSha256() == true &&
            !model.visionProjectorSha256.equals(projectorDigest, ignoreCase = true)) {
            return model.copy(integrityState = ModelIntegrityState.DAMAGED,
                diagnostic = "视觉投影器完整 SHA-256 与清单不一致")
        }
        return model.copy(
            sha256 = digest,
            sizeBytes = when {
                main.isFile -> main.length()
                model.runtime == ChatModelRuntime.MNN ->
                    mnnBundleSize(main, MnnBundleReadinessAnalyzer.analyze(main).requiredComponentPaths)
                else -> directorySize(main)
            },
            visionProjectorSha256 = projectorDigest,
            visionProjectorSizeBytes = projector?.length() ?: model.visionProjectorSizeBytes,
            integrityState = ModelIntegrityState.COMPLETE,
            visionValidated = model.visionValidated && legacyMnnDirectoryHash == null,
            diagnostic = null
        )
    }

    /** Every imported/downloaded physical copy remains addressable until explicit reconciliation. */
    internal fun ModelStoreRepository.upsert(model: ModelManifest): ModelManifest = synchronized(MODEL_IMPORT_LOCK) {
        val existingModels = readPersistedModels()
        val old = existingModels.firstOrNull { it.id == model.id } ?: existingModels.firstOrNull {
            canonicalModelPath(it.path) == canonicalModelPath(model.path) && it.componentIdentity == model.componentIdentity &&
                it.componentIdentity != null
        }
        val stable = if (old == null) model else model.copy(
            id = old.id,
            aliases = (old.aliases + model.aliases + listOfNotNull(model.id.takeIf { it != old.id })).distinct(),
            physicalCopies = (old.physicalCopies + model.physicalCopies +
                ModelPhysicalCopy(old.path, old.sizeBytes, old.sha256, old.visionProjectorPath,
                    old.visionProjectorSizeBytes, old.visionProjectorSha256,
                    old.source, old.repoId, old.revision)).distinctBy { canonicalModelPath(it.path) },
            createdAt = old.createdAt,
            lastLoadedAt = old.lastLoadedAt ?: model.lastLoadedAt,
            visionValidated = if (old.componentIdentity != null && old.componentIdentity == model.componentIdentity) {
                old.visionValidated || model.visionValidated
            } else model.visionValidated
        )
        save(existingModels.filterNot { it.id == old?.id } + stable)
        stable
    }

    internal fun ModelStoreRepository.save(models: List<ModelManifest>) = synchronized(MODEL_IMPORT_LOCK) {
        val array = JSONArray()
        models.sortedByDescending { it.createdAt }.forEach { array.put(it.toJson()) }
        val parent = requireNotNull(manifestFile.parentFile) { "模型清单目录不存在。" }
        parent.mkdirs()
        val staging = File(parent, ".${manifestFile.name}.writing")
        val backup = File(parent, ".${manifestFile.name}.backup")
        if (staging.exists() && !staging.delete()) error("无法清理旧的模型清单暂存文件。")
        FileOutputStream(staging).use { output ->
            output.write(array.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        JSONArray(staging.readText(Charsets.UTF_8))
        if (backup.exists() && !backup.delete()) error("无法清理旧的模型清单备份。")
        val hadManifest = manifestFile.exists()
        if (hadManifest && !manifestFile.renameTo(backup)) {
            staging.delete()
            error("无法备份当前模型清单。")
        }
        if (!staging.renameTo(manifestFile)) {
            if (hadManifest && !manifestFile.exists()) backup.renameTo(manifestFile)
            staging.delete()
            error("无法原子提交模型清单。")
        }
        backup.delete()
    }

    private fun ModelStoreRepository.normalizeManifest(model: ModelManifest): ModelManifest = when (model.runtime) {
        ChatModelRuntime.MNN -> normalizeMnnManifest(model)
        ChatModelRuntime.GENIEX_QAIRT -> normalizeQairtManifest(model)
        ChatModelRuntime.LLAMA_CPP -> normalizeLlamaManifest(model)
        ChatModelRuntime.LITERT_LM -> normalizeLiteRtLmManifest(model)
    }

    private fun ModelStoreRepository.normalizeLiteRtLmManifest(model: ModelManifest): ModelManifest {
        val file = File(model.path)
        if (file.name.isBlank()) return model
        val inferredName = stripKnownExtension(file.name, ".litertlm")
        val inferredArchitecture = model.architecture
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: inferArchitectureLabel(file.name, ChatModelRuntime.LITERT_LM)
        val displayName = model.displayName.trim()
        val normalizedDisplayName = if (displayName.isBlank()) inferredName else displayName
        val normalizedFileName = if (model.fileName.isBlank()) file.name else model.fileName
        return if (normalizedDisplayName != model.displayName ||
            normalizedFileName != model.fileName ||
            inferredArchitecture != model.architecture) {
            model.copy(
                displayName = normalizedDisplayName,
                fileName = normalizedFileName,
                architecture = inferredArchitecture
            )
        } else {
            model
        }
    }

    private fun ModelStoreRepository.normalizeLlamaManifest(model: ModelManifest): ModelManifest {
        val file = File(model.path)
        if (!file.isFile) return model
        val normalizedName = file.nameWithoutExtension
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
        val inferred = model.architecture
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: when {
                "gemma" in normalizedName -> "gemma"
                "qwen" in normalizedName -> "qwen"
                "llama" in normalizedName -> "llama"
                "mistral" in normalizedName -> "mistral"
                "phi" in normalizedName -> "phi"
                else -> "gguf"
            }
        return if (inferred != model.architecture) model.copy(architecture = inferred) else model
    }

    private fun ModelStoreRepository.normalizeMnnManifest(model: ModelManifest): ModelManifest {
        val bundleDir = File(model.path)
        val inferredName = inferMnnDisplayName(bundleDir.name)
        val inferredArchitecture = model.architecture
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: inferArchitectureLabel(bundleDir.name, ChatModelRuntime.MNN)
        if (inferredName.isBlank() && inferredArchitecture == model.architecture) return model
        val current = model.displayName.trim()
        val compactCurrent = current.lowercase().replace(Regex("[^a-z0-9]"), "")
        val compactInferred = inferredName.lowercase().replace(Regex("[^a-z0-9]"), "")
        val looksTruncated = current.isBlank() ||
            compactCurrent.length < compactInferred.length &&
            compactInferred.startsWith(compactCurrent)
        return if (looksTruncated || inferredArchitecture != model.architecture) {
            model.copy(
                displayName = if (looksTruncated) inferredName else model.displayName,
                fileName = if (looksTruncated) bundleDir.name.ifBlank { model.fileName } else model.fileName,
                architecture = inferredArchitecture
            )
        } else {
            model
        }
    }

    private fun ModelStoreRepository.normalizeQairtManifest(model: ModelManifest): ModelManifest {
        val normalized = normalizeQairtManifestRoot(model)
        if (normalized == model) return model
        val bundleDir = File(normalized.path)
        val size = directorySize(bundleDir)
        val hash = runCatching { sha256Directory(bundleDir) }.getOrNull() ?: return normalized
        return normalized.copy(sizeBytes = size, sha256 = hash)
    }

    internal fun List<ModelManifest>.distinctByPathKeepingNewest(): List<ModelManifest> =
        deduplicateModelRecordsByCanonicalPath(this)

    internal fun ModelStoreRepository.stripKnownExtension(value: String, extension: String): String =
        if (value.endsWith(extension, ignoreCase = true)) value.dropLast(extension.length) else value

    /**
     * Best-effort presentation metadata only.  This never participates in
     * compatibility, admission, or runtime selection; native load remains the
     * authority for whether a package can execute.
     */

    internal fun ModelStoreRepository.inferArchitectureLabel(value: String, runtime: ChatModelRuntime): String? {
        val name = value.lowercase().replace('-', '_').replace(' ', '_')
        return when (runtime) {
            ChatModelRuntime.MNN -> when {
                name.contains("sdxl") -> "stable_diffusion_xl"
                name.contains("sd15") || name.contains("sd_1_5") || name.contains("stable_diffusion") ->
                    "stable_diffusion_1_5"
                name.contains("sana") -> "sana_diffusion"
                name.contains("qwen") -> "qwen"
                name.contains("gemma") -> "gemma"
                name.contains("llama") -> "llama"
                else -> "mnn"
            }
            ChatModelRuntime.LITERT_LM -> when {
                name.contains("gemma") -> "gemma"
                name.contains("qwen") -> "qwen"
                name.contains("llama") -> "llama"
                else -> "litert_lm_text_decoder"
            }
            else -> null
        }
    }

/**
 * A damaged legacy entry must not hide every otherwise valid model. A malformed
 * top-level document still fails so repository recovery can rebuild it from the
 * managed model directory.
 */
internal fun parsePersistedModelManifest(contents: String): List<ModelManifest> {
    val array = JSONArray(contents)
    return buildList {
        for (index in 0 until array.length()) {
            val raw = array.opt(index)
            runCatching {
                when (raw) {
                    is org.json.JSONObject -> ModelManifest.fromJson(raw)
                    else -> ModelManifest.fromJson(org.json.JSONObject()
                        .put("id", "damaged-row-$index")
                        .put("diagnostic", "模型清单记录不是对象：index=$index"))
                }
            }
                .getOrNull()
                ?.let(::add)
        }
    }
}

/**
 * Preserve distinct ids for conflicting components on the same main path.
 * Only exact matching component identity can be reconciled into aliases.
 */

internal fun deduplicateModelRecordsByCanonicalPath(models: List<ModelManifest>): List<ModelManifest> {
    return models.sortedByDescending { it.createdAt }.distinctBy { model ->
        Triple(model.id, canonicalModelPath(model.path), model.stableIdentity)
    }
}

private fun canonicalModelPath(path: String): String = runCatching { File(path).canonicalPath }
    .getOrElse { File(path).absolutePath }

data class ModelIdentityReconciliation(
    val models: List<ModelManifest>,
    val idMapping: Map<String, String>,
    val diagnostics: List<String>
)

internal fun reconcileStableModelIdentities(models: List<ModelManifest>): ModelIdentityReconciliation {
    val result = mutableListOf<ModelManifest>()
    val mapping = linkedMapOf<String, String>()
    val diagnostics = mutableListOf<String>()
    models.withIndex().groupBy { (index, model) ->
        if (model.integrityState == ModelIntegrityState.COMPLETE) model.componentIdentity
            ?: "unresolved:$index"
        else "unresolved:$index"
    }.values.forEach { indexedGroup ->
        val group = indexedGroup.map { it.value }
        val preferred = group.maxWithOrNull(compareBy<ModelManifest> { it.physicalCopies.isNotEmpty() }
            .thenBy { it.lastLoadedAt ?: Long.MIN_VALUE }.thenBy { it.createdAt }) ?: return@forEach
        val allIds = group.flatMap { listOf(it.id) + it.aliases }.distinct()
        val copies = group.flatMap { model ->
            model.physicalCopies + ModelPhysicalCopy(
                model.path, model.sizeBytes, model.sha256, model.visionProjectorPath,
                model.visionProjectorSizeBytes, model.visionProjectorSha256,
                model.source, model.repoId, model.revision
            )
        }.distinctBy { copy ->
            listOf(canonicalModelPath(copy.path), copy.sha256,
                copy.visionProjectorPath.orEmpty(), copy.visionProjectorSha256.orEmpty()).joinToString("|")
        }
        val merged = preferred.copy(
            aliases = allIds.filter { it != preferred.id },
            physicalCopies = copies,
            lastLoadedAt = group.mapNotNull(ModelManifest::lastLoadedAt).maxOrNull(),
            visionValidated = preferred.visionValidated
        )
        result += merged
        allIds.forEach { mapping[it] = merged.id }
        if (group.size > 1) diagnostics += "合并 ${group.size} 条完全相同的组件记录；保留 ${copies.size} 份实体文件"
    }
    return ModelIdentityReconciliation(result.sortedByDescending { it.createdAt }, mapping, diagnostics)
}

/** Compatibility helper for older callers; reconciliation uses full component identity. */

internal fun deduplicateEquivalentModelRecordsForCatalog(models: List<ModelManifest>): List<ModelManifest> {
    return reconcileStableModelIdentities(models).models
}

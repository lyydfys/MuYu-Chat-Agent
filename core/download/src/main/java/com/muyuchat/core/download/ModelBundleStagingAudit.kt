package com.muyuchat.core.download

import com.muyuchat.core.download.ModelBundleInstaller.Companion.AUDIT_FILE_NAME
import com.muyuchat.core.download.ModelBundleInstaller.Companion.AUDIT_SCHEMA
import com.muyuchat.core.download.ModelBundleInstaller.Companion.SHA256_HEX
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

    internal suspend fun applyStagedTransformer(
        plan: ModelBundleInstallPlan,
        transformer: ModelBundleStagedTransformer?
    ): Set<String> {
        if (transformer == null) return emptySet()
        currentCoroutineContext().ensureActive()
        // A transformer derives new bytes whose final audit intentionally
        // omits the publisher digest. Verify the original bytes before that
        // boundary, including files supplied by an injected downloader or
        // resumed staging content. Never let transformation hide corruption.
        for (target in plan.targets) {
            currentCoroutineContext().ensureActive()
            requireDownloadedFile(target)
            val expectedSha = normalizedRemoteSha256OrNull(target.remote.sha256)
            if (expectedSha != null) {
                require(cancellableSha256(target.stagedFile).equals(expectedSha, ignoreCase = true)) {
                    "Downloaded bundle component has an unexpected SHA-256: ${target.relativePath}"
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val stagedFiles = plan.targets.associate { target -> target.relativePath to target.stagedFile }
        val transformed = transformer.transform(plan.contentRoot, stagedFiles)
            .transformedRelativePaths
            .map(::normalizeRelativePath)
            .toSet()
        require(transformed.all(stagedFiles::containsKey)) {
            "A staged transformer may only report downloaded bundle components."
        }
        plan.targets.forEach { target ->
            currentCoroutineContext().ensureActive()
            if (target.relativePath in transformed) {
                require(target.stagedFile.isFile && target.stagedFile.canRead() && target.stagedFile.length() > 0L) {
                    "Transformed bundle component is missing, unreadable, or empty: ${target.relativePath}"
                }
            }
        }
        return transformed
    }

    internal suspend fun auditStagedFile(
        target: ModelBundleDownloadTarget,
        transformed: Boolean
    ): ModelBundleComponentAudit {
        val observedSha256 = cancellableSha256(target.stagedFile)
        val expectedSha = normalizedRemoteSha256OrNull(target.remote.sha256)
        require(transformed || expectedSha == null || observedSha256.equals(expectedSha, ignoreCase = true)) {
            "Downloaded bundle component has an unexpected SHA-256: ${target.relativePath}"
        }
        return ModelBundleComponentAudit(
            relativePath = target.relativePath,
            observedSizeBytes = target.stagedFile.length(),
            observedSha256 = observedSha256,
            // Once product installation derives a file, its bytes are no longer
            // the publisher artifact. Keep only the local observed digest and do
            // not misrepresent source size/SHA metadata as a verified match.
            sourceSizeBytes = if (transformed) null else target.remote.sizeBytes?.takeIf { it > 0L },
            sourceSha256 = if (transformed) null else normalizedRemoteSha256OrNull(target.remote.sha256),
            sourceMetadataStatus = if (transformed) {
                ImageEngineIntegrityMetadataStatus.UNKNOWN
            } else {
                target.remote.integrityMetadataStatus
            },
            transformed = transformed,
            sourceIdentity = target.remote.sourceIdentity()
        )
    }

    internal fun writeAuditManifest(contentRoot: File, audits: List<ModelBundleComponentAudit>) {
        val auditFile = File(contentRoot, AUDIT_FILE_NAME)
        val components = JSONArray()
        audits.forEach { audit ->
            components.put(
                JSONObject()
                    .put("path", audit.relativePath)
                    .put("observedSizeBytes", audit.observedSizeBytes)
                    .put("observedSha256", audit.observedSha256)
                    .put("sourceSizeBytes", audit.sourceSizeBytes ?: JSONObject.NULL)
                    .put("sourceSha256", audit.sourceSha256 ?: JSONObject.NULL)
                    .put("sourceMetadataStatus", audit.sourceMetadataStatus.name)
                    .put("transformed", audit.transformed)
                    .put("sourceIdentity", audit.sourceIdentity ?: JSONObject.NULL)
            )
        }
        auditFile.writeText(
            JSONObject()
                .put("schema", AUDIT_SCHEMA)
                .put("components", components)
                .toString(2),
            Charsets.UTF_8
        )
    }

    internal fun readAuditManifest(auditFile: File): List<ModelBundleComponentAudit>? = runCatching {
        if (!auditFile.isFile) return null
        val manifest = JSONObject(auditFile.readText(Charsets.UTF_8))
        require(manifest.optString("schema") == AUDIT_SCHEMA) { "Unsupported bundle audit schema." }
        val components = manifest.optJSONArray("components") ?: return null
        require(components.length() > 0) { "Bundle audit has no components." }
        buildList {
            for (index in 0 until components.length()) {
                val component = components.getJSONObject(index)
                val relativePath = normalizeRelativePath(component.getString("path"))
                val observedSize = component.getLong("observedSizeBytes")
                val observedSha = component.getString("observedSha256")
                require(observedSize >= 0L && observedSha.matches(SHA256_HEX)) { "Invalid component audit." }
                add(
                    ModelBundleComponentAudit(
                        relativePath = relativePath,
                        observedSizeBytes = observedSize,
                        observedSha256 = observedSha,
                        sourceSizeBytes = component.optLong("sourceSizeBytes", -1L).takeIf { it > 0L },
                        sourceSha256 = component.optString("sourceSha256").takeIf { it.isNotBlank() && it != "null" },
                        sourceMetadataStatus = component.optString("sourceMetadataStatus")
                            .let { value -> ImageEngineIntegrityMetadataStatus.entries.firstOrNull { it.name == value } }
                            ?: ImageEngineIntegrityMetadataStatus.UNKNOWN,
                        transformed = component.optBoolean("transformed", false),
                        sourceIdentity = component.optString("sourceIdentity").takeIf { it.matches(SHA256_HEX) }
                    )
                )
            }
        }
    }.getOrNull()

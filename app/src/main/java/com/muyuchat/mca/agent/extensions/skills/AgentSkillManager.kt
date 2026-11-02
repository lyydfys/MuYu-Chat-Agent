package com.muyuchat.mca.agent.extensions.skills

import java.io.File

/**
 * K12 Skill lifecycle owner.
 *
 * Responsibilities: import/update packages into private immutable snapshots, persist the narrow
 * manifest through [AgentSkillStore], enforce the V1 enable cap (4), emit typed revocation signals
 * for disable/uninstall, and freeze the run-time [AgentSkillSnapshot]. It never executes package
 * content, never reads script bytes and never grants workspace/network access.
 *
 * Persistence, approval invalidation on [AgentSkillRevocation] and the actual tool-broker
 * intersection are integration points owned by the Lead; this class only computes the Skill side.
 */
class AgentSkillManager(
    private val store: AgentSkillStore,
    private val snapshots: AgentSkillSnapshotRepository,
    private val references: AgentSkillSnapshotReferencePort = AgentSkillSnapshotReferencePort.NONE,
    private val revocations: AgentSkillRevocationPort = AgentSkillRevocationPort.NO_OP,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val importer: AgentSkillImporter = AgentSkillImporter(snapshots, clock = clock)
) {
    fun list(): List<AgentSkillManifest> = store.list().sortedBy { it.id }

    fun installZip(
        sourceUri: String,
        packageFile: File,
        enable: Boolean = false
    ): AgentSkillManifest {
        if (enable) requireEnableSlot()
        val manifest = importer.importZip(sourceUri, packageFile, previous = null, enable = enable)
        return persistNew(manifest)
    }

    fun installTree(
        sourceUri: String,
        root: AgentSkillTreeDocument,
        enable: Boolean = false
    ): AgentSkillManifest {
        if (enable) requireEnableSlot()
        val manifest = importer.importTree(sourceUri, root, previous = null, enable = enable)
        return persistNew(manifest)
    }

    fun updateZip(
        skillId: String,
        sourceUri: String,
        packageFile: File
    ): AgentSkillManifest {
        val existing = requireInstalled(skillId)
        val updated = importer.importZip(sourceUri, packageFile, previous = existing, enable = existing.enabled)
        return persistUpdate(updated, existing)
    }

    fun updateTree(
        skillId: String,
        sourceUri: String,
        root: AgentSkillTreeDocument
    ): AgentSkillManifest {
        val existing = requireInstalled(skillId)
        val updated = importer.importTree(sourceUri, root, previous = existing, enable = existing.enabled)
        return persistUpdate(updated, existing)
    }

    fun previewZip(sourceUri: String, packageFile: File): AgentSkillPackagePreview =
        importer.inspectZip(sourceUri, packageFile)

    fun previewTree(sourceUri: String, root: AgentSkillTreeDocument): AgentSkillPackagePreview =
        importer.inspectTree(sourceUri, root)

    /**
     * Enable/disable is an explicit policy change. Disabling revokes pending approvals via
     * [AgentSkillRevocationPort] before the manifest is persisted; enabling enforces the V1 cap.
     */
    fun setEnabled(skillId: String, enabled: Boolean): AgentSkillManifest {
        val existing = requireInstalled(skillId)
        if (existing.enabled == enabled) return existing
        if (enabled) {
            requireEnableSlot(ignoreSkillId = skillId)
        } else {
            revoke(existing, AgentSkillRevocationReason.DISABLED)
        }
        return store.setEnabled(skillId, enabled)
    }

    /**
     * Uninstall removes the manifest and revokes the Skill, but keeps the snapshot when a historical
     * run still references it; only an unreferenced snapshot is deleted (GC later for the rest).
     */
    fun uninstall(skillId: String): AgentSkillUninstallResult {
        val existing = requireInstalled(skillId)
        revoke(existing, AgentSkillRevocationReason.UNINSTALLED)
        store.delete(skillId)
        val referenced = references.isSnapshotReferenced(existing.snapshotId)
        if (!referenced) snapshots.delete(existing.snapshotId)
        return AgentSkillUninstallResult(removed = existing, snapshotRetained = referenced)
    }

    /**
     * Builds the immutable run-start snapshot. The effective tool set is the intersection of the
     * enabled registry keys with every enabled Skill's declaration; declarations can only narrow.
     */
    fun buildRunSnapshot(availableToolKeys: Set<String>): AgentSkillSnapshot {
        val enabled = store.list().filter { it.enabled }.sortedBy { it.id }
        if (enabled.size > AgentSkillLimits.MAX_ENABLED_SKILLS) {
            skillFailure(
                AgentSkillErrorCode.TOO_MANY_ENABLED_SKILLS,
                "more than ${AgentSkillLimits.MAX_ENABLED_SKILLS} Skills are enabled"
            )
        }
        val skills = enabled.map { manifest ->
            if (!snapshots.exists(manifest.snapshotId)) {
                skillFailure(
                    AgentSkillErrorCode.STORE_FAILURE,
                    "enabled Skill '${manifest.id}' has no private snapshot"
                )
            }
            AgentSkillSnapshotSkill(
                id = manifest.id,
                version = manifest.version,
                name = manifest.name,
                sha256 = manifest.sha256,
                packageVersion = manifest.packageVersion,
                entryPath = manifest.entryPath,
                declaredToolPatterns = manifest.declaredToolPatterns.map(AgentSkillToolPattern::parse),
                sourceFingerprint = manifest.source.fingerprint
            )
        }
        val effective = AgentSkillToolScope.effectiveToolKeys(skills, availableToolKeys)
        val seed = buildString {
            append("MCA-SKILL-RUN-SNAPSHOT\n")
            skills.forEach { skill ->
                append(skill.id).append('@').append(skill.version).append(':')
                    .append(skill.sha256).append(':')
                    .append(skill.declaredToolPatterns.joinToString(",") { it.canonical })
                    .append('\n')
            }
            availableToolKeys.sorted().forEach { append("tool:").append(it).append('\n') }
        }
        return AgentSkillSnapshot(
            snapshotId = AgentSkillHash.sha256Hex(seed),
            skills = skills,
            effectiveToolKeys = effective,
            createdAtMillis = clock(),
            registryToolKeyCount = availableToolKeys.size
        )
    }

    private fun persistNew(manifest: AgentSkillManifest): AgentSkillManifest = try {
        if (manifest.enabled) requireEnableSlot()
        store.create(manifest)
    } catch (failure: AgentSkillException) {
        runCatching { snapshots.delete(manifest.snapshotId) }
        throw failure
    }

    private fun persistUpdate(
        updated: AgentSkillManifest,
        existing: AgentSkillManifest
    ): AgentSkillManifest = try {
        // The old snapshot is retained unconditionally: existing runs keep their immutable view.
        store.update(updated, expectedVersion = existing.version)
    } catch (failure: AgentSkillException) {
        runCatching { snapshots.delete(updated.snapshotId) }
        throw failure
    }

    private fun requireInstalled(skillId: String): AgentSkillManifest =
        store.find(skillId) ?: skillFailure(
            AgentSkillErrorCode.SKILL_NOT_FOUND,
            "skill '$skillId' is not installed"
        )

    private fun requireEnableSlot(ignoreSkillId: String? = null) {
        val enabled = store.list().count { it.enabled && it.id != ignoreSkillId }
        if (enabled >= AgentSkillLimits.MAX_ENABLED_SKILLS) {
            skillFailure(
                AgentSkillErrorCode.TOO_MANY_ENABLED_SKILLS,
                "V1 allows at most ${AgentSkillLimits.MAX_ENABLED_SKILLS} enabled Skills"
            )
        }
    }

    private fun revoke(manifest: AgentSkillManifest, reason: AgentSkillRevocationReason) {
        revocations.onRevoked(
            AgentSkillRevocation(
                skillId = manifest.id,
                version = manifest.version,
                reason = reason,
                declaredToolPatterns = manifest.declaredToolPatterns
            )
        )
    }
}

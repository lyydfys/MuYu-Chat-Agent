package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineBundleComponentRole
import com.muyuchat.core.download.ImageEngineBundleSpec
import com.muyuchat.core.download.ModelRepositoryProvider

/**
 * P5/S44: the pinned-manifest revision-migration pass, moved verbatim out of the
 * ImageExecutionProfileResolver object together with the five identity-rule extensions it
 * shares with the resolver. Read-only object tables are widened to `internal` and qualified;
 * `migratePinnedCatalogManifestProfile` stays the resolver's entry point. Behavior unchanged.
 */

internal fun BuiltInImageProfileIdentityRule.catalogContract(): CatalogImageProfileContract? =
    ImageExecutionProfileResolver.catalogProfileContractsByRecommendationId[recommendationId]

internal fun BuiltInImageProfileIdentityRule.effectiveAliases(): Set<String> =
    aliases + catalogContract()?.aliases.orEmpty()

internal fun BuiltInImageProfileIdentityRule.effectivePrimaryRepositories(): Set<String> =
    primaryRepositories + catalogContract()?.primaryRepositories.orEmpty()

internal fun BuiltInImageProfileIdentityRule.effectiveArtifactMarkers(): Set<String> =
    artifactMarkers + catalogContract()?.artifactMarkers.orEmpty()

internal fun BuiltInImageProfileIdentityRule.effectiveModelFingerprints(): Set<String> =
    modelFingerprints + listOfNotNull(catalogContract()?.modelFingerprint)

/**
 * Revision migration is deliberately narrower than normal recommendation
 * resolution. It only repairs an older persisted contract for its exact
 * pinned catalog bundle (archives require their pinned `sourcePath!/member`
 * proof; Qwen direct-file bundles have a separate exact-file proof). Aliases,
 * device hints and profile names alone can never select a migration target.
 */
internal fun migratePinnedCatalogManifestProfile(
    input: ImageExecutionProfileResolverInput,
    fingerprint: String
): ImageExecutionProfile? {
    val persisted = input.manifestProfile ?: return null
    val persistedFingerprint = persisted.modelFingerprint.trim().lowercase()
    if (persistedFingerprint != fingerprint) {
        // A known Qwen bundle revision wrote the VAE decoder SHA into the profile identity
        // even though the registered model row points at the DIFFUSION primary. Permit only
        // that observed legacy value, and only after the Qwen-specific check below proves the
        // current input is the exact pinned bundle. A complete bundle fingerprint is also
        // allowed to reach that check; it must never be treated as a primary-file SHA.
        val qwenCandidate = ImageExecutionProfileResolver.resolveBuiltInTarget(input)
            ?.recommendationId == QWEN_IMAGE_21_RECOMMENDATION_ID
        val completeQwenCandidate = qwenCandidate &&
            input.modelFingerprintScope == ImageModelFingerprintScope.COMPLETE_BUNDLE
        if (!qwenCandidate ||
            (!completeQwenCandidate &&
                persistedFingerprint != QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT)
        ) {
            return persisted
        }
    }
    val target = ImageExecutionProfileResolver.resolveBuiltInTarget(input)
        ?.takeIf { it.recommendationId in MIGRATABLE_PINNED_RECOMMENDATIONS }
        ?: return persisted
    val recommendationId = target.recommendationId
    val contract = ImageExecutionProfileResolver.catalogProfileContractsByRecommendationId[recommendationId]
        ?: return persisted
    if (recommendationId == QWEN_IMAGE_21_RECOMMENDATION_ID) {
        if (!qwenImage21MigrationMetadataAgrees(input, persisted, contract)) return persisted
    }
    if (!manifestMigrationIdentityAgrees(input, persisted, recommendationId)) return persisted
    if (!manifestMigrationSourceEvidenceAgrees(input, recommendationId)) return persisted

    val catalogProfile = materializeDownloadedImageExecutionProfile(
        bundle = contract.bundle,
        modelFingerprint = fingerprint
    ) ?: return persisted
    if (
        persisted.profileId != catalogProfile.profileId ||
        (if (recommendationId == QWEN_IMAGE_21_RECOMMENDATION_ID) {
            persisted.profileRevision != 1
        } else {
            persisted.profileRevision <= 0 ||
                persisted.profileRevision >= catalogProfile.profileRevision
        }) ||
        persisted.runtime != catalogProfile.runtime ||
        persisted.family != catalogProfile.family ||
        persisted.task != catalogProfile.task
    ) {
        return persisted
    }
    return catalogProfile.copy(
        provenance = catalogProfile.provenance.copy(
            primarySource = ImageProfileSource.MANIFEST,
            sources = listOf(ImageProfileSource.MANIFEST),
            recommendationId = recommendationId,
            recommendationRevision = persisted.provenance.recommendationRevision
                ?: input.recommendationRevision
                ?: contract.bundle.requiredComponents
                    .firstOrNull { component -> component.role == ImageEngineBundleComponentRole.DIFFUSION }
                    ?.revision,
            notes = persisted.provenance.notes
        )
    )
}

/**
 * Qwen's package is a pinned set of direct Hugging Face files, not an archive. Older manifests
 * do not persist the source revision in the top-level metadata, so the primary file's exact
 * catalog SHA-256, repository, direct source path and the catalog's immutable revision jointly
 * bind it. If either old provenance or the current request does declare a revision, require it
 * to match that same pinned revision.
 */
private fun qwenImage21MigrationMetadataAgrees(
    input: ImageExecutionProfileResolverInput,
    persisted: ImageExecutionProfile,
    contract: CatalogImageProfileContract
): Boolean {
    val bundle = contract.bundle
    val primary = bundle.requiredComponents.singleOrNull { component ->
        component.role == ImageEngineBundleComponentRole.DIFFUSION &&
            component.fileName.equals("dit.mnn", ignoreCase = true)
    } ?: return false
    val pinnedRevision = primary.revision.trim().takeIf(String::isNotEmpty) ?: return false
    val pinnedRepository = ImageExecutionProfileResolver.normalizeRepositoryIdentity(primary.repoId)
    val pinnedFingerprint = primary.sha256?.trim()?.lowercase() ?: return false
    val pinnedLegacyVaeFingerprint = bundle.requiredComponents
        .singleOrNull { component ->
            component.role == ImageEngineBundleComponentRole.VAE &&
                component.fileName.equals("vae_decoder.mnn", ignoreCase = true)
        }
        ?.sha256
        ?.trim()
        ?.lowercase()
    val inputFingerprint = input.modelFingerprint.trim().lowercase()
    val persistedFingerprint = persisted.modelFingerprint.trim().lowercase()
    val readinessUsesKnownLegacyIdentity =
        inputFingerprint == QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT &&
            persistedFingerprint == QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT &&
            pinnedLegacyVaeFingerprint == QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT
    val inputUsesCompleteBundleIdentity =
        input.modelFingerprintScope == ImageModelFingerprintScope.COMPLETE_BUNDLE
    val completeBundleEvidenceAgrees = inputUsesCompleteBundleIdentity &&
        qwenImage21CompleteBundleEvidenceAgrees(
            evidence = input.bundleComponentEvidence,
            bundle = bundle
        )
    val inputFingerprintAgrees = if (inputUsesCompleteBundleIdentity) {
        // The complete digest is computed from the observed files by the integration layer.
        // The resolver only accepts it when the same observation proves every pinned required
        // component, so a catalog primary SHA can never be mistaken for the bundle digest.
        completeBundleEvidenceAgrees && inputFingerprint.matches(Regex("^[0-9a-f]{64}$"))
    } else {
        inputFingerprint == pinnedFingerprint || readinessUsesKnownLegacyIdentity
    }
    if (
        bundle.recommendationId != QWEN_IMAGE_21_RECOMMENDATION_ID ||
        bundle.requiredComponents.isEmpty() ||
        bundle.requiredComponents.any { component ->
            component.provider != ModelRepositoryProvider.HUGGING_FACE ||
                ImageExecutionProfileResolver.normalizeRepositoryIdentity(component.repoId) != pinnedRepository ||
                component.revision.trim() != pinnedRevision ||
                component.fileName.isBlank() ||
                component.fileName.endsWith(".zip", ignoreCase = true) ||
                component.fileName.contains("!/")
        } ||
        contract.modelFingerprint != pinnedFingerprint ||
        !inputFingerprintAgrees ||
        (persistedFingerprint != pinnedFingerprint &&
            (persistedFingerprint != QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT ||
                pinnedLegacyVaeFingerprint != QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT)) ||
        input.recommendationId?.trim() != QWEN_IMAGE_21_RECOMMENDATION_ID ||
        persisted.provenance.recommendationId?.trim() != QWEN_IMAGE_21_RECOMMENDATION_ID ||
        input.recommendationRevision?.trim()?.takeIf(String::isNotEmpty)
            ?.let { it != pinnedRevision } == true ||
        persisted.provenance.recommendationRevision?.trim()?.takeIf(String::isNotEmpty)
            ?.let { it != pinnedRevision } == true
    ) {
        return false
    }
    return qwenImage21DirectFileSourceEvidenceAgrees(input, contract, pinnedRepository)
}

/**
 * Validates the actual complete bundle observation against the immutable catalog closure.
 * Extra files are allowed because optional edit assets may be installed, but every required
 * path must occur exactly once with the catalog role, size, and SHA-256. This is intentionally
 * Qwen-specific: no generic complete-bundle shortcut may weaken other model migrations.
 */
private fun qwenImage21CompleteBundleEvidenceAgrees(
    evidence: List<ImageBundleComponentEvidence>,
    bundle: ImageEngineBundleSpec
): Boolean {
    if (evidence.isEmpty()) return false
    val observedByPath = evidence.groupBy { normalizeBundleComponentPath(it.relativePath) }
    if (observedByPath.keys.any(String::isBlank)) return false
    return bundle.requiredComponents.all { expected ->
        val expectedPath = normalizeBundleComponentPath(expected.relativePath)
        val candidates = observedByPath[expectedPath].orEmpty()
        candidates.size == 1 && candidates.single().let { actual ->
            actual.role.trim().uppercase() == expected.role.name &&
                actual.sha256.trim().equals(expected.sha256?.trim(), ignoreCase = true) &&
                (expected.expectedSizeBytes == null || actual.sizeBytes == expected.expectedSizeBytes)
        }
    }
}

private fun normalizeBundleComponentPath(value: String): String = value
    .trim()
    .replace('\\', '/')
    .trimStart('/')
    .lowercase()

private fun qwenImage21DirectFileSourceEvidenceAgrees(
    input: ImageExecutionProfileResolverInput,
    contract: CatalogImageProfileContract,
    pinnedRepository: String = ImageExecutionProfileResolver.normalizeRepositoryIdentity(
        contract.bundle.requiredComponents.firstOrNull { component ->
            component.role == ImageEngineBundleComponentRole.DIFFUSION &&
                component.fileName.equals("dit.mnn", ignoreCase = true)
        }?.repoId.orEmpty()
    )
): Boolean {
    val requiredComponents = contract.bundle.requiredComponents
    val pinnedPaths = requiredComponents.map { component ->
        normalizeDirectSourcePath(component.fileName)
    }.toSet()
    if (pinnedPaths.isEmpty() || pinnedPaths.any(String::isBlank)) return false
    val evidenceRepositories = input.recommendationEvidence.sourceRepositories
        .asSequence()
        .filter(String::isNotBlank)
        .map(ImageExecutionProfileResolver::normalizeRepositoryIdentity)
        .filter { repository -> '/' in repository }
        .toSet()
    val directPaths = input.recommendationEvidence.artifactPaths
        .asSequence()
        .filter(String::isNotBlank)
        .map(::normalizeDirectSourcePath)
        .toSet()
    return evidenceRepositories == setOf(pinnedRepository) &&
        directPaths.none { path -> "!/" in path || path.endsWith(".zip") } &&
        pinnedPaths.all(directPaths::contains)
}

private fun normalizeDirectSourcePath(value: String): String = value
    .trim()
    .replace('\\', '/')
    .trimStart('/')
    .lowercase()

private fun manifestMigrationIdentityAgrees(
    input: ImageExecutionProfileResolverInput,
    persisted: ImageExecutionProfile,
    recommendationId: String
): Boolean {
    val targetRule = identityRules.single { rule -> rule.recommendationId == recommendationId }
    val persistedIdentity = persisted.provenance.recommendationId
        ?.takeIf(String::isNotBlank)
        ?: return false
    if (persistedIdentity.trim() != recommendationId) return false
    input.recommendationId
        ?.takeIf(String::isNotBlank)
        ?.let { requestedIdentity ->
            if (requestedIdentity.trim() != recommendationId) return false
        }

    val conflictingAlias = input.recommendationEvidence.aliases
        .asSequence()
        .filter(String::isNotBlank)
        .map(ImageExecutionProfileResolver::normalizeIdentityToken)
        .any { alias ->
            identityRules.any { rule ->
                rule.recommendationId != recommendationId && alias in rule.effectiveAliases()
            }
        }
    if (conflictingAlias) return false
    val conflictingRepository = input.recommendationEvidence.sourceRepositories
        .asSequence()
        .filter(String::isNotBlank)
        .map(ImageExecutionProfileResolver::normalizeRepositoryIdentity)
        .any { repository ->
            repository !in targetRule.effectivePrimaryRepositories() &&
                identityRules.any { rule ->
                    rule.recommendationId != recommendationId &&
                        repository in rule.effectivePrimaryRepositories()
                }
        }
    if (conflictingRepository) return false
    val conflictingArtifact = input.recommendationEvidence.artifactPaths
        .asSequence()
        .filter(String::isNotBlank)
        .flatMap { path -> ImageExecutionProfileResolver.artifactIdentityTokens(path).asSequence() }
        .any { artifact ->
            identityRules.any { rule ->
                rule.recommendationId != recommendationId &&
                    artifact in rule.effectiveArtifactMarkers()
            }
        }
    return !conflictingArtifact
}

/**
 * Installed archive profiles fingerprint the extracted diffusion graph, not the deleted ZIP.
 * Bind migration to both immutable publisher identity fields retained in the generated manifest
 * so a hand-written recommendation id alone cannot opt an unrelated package into this repair.
 */
private fun manifestMigrationSourceEvidenceAgrees(
    input: ImageExecutionProfileResolverInput,
    recommendationId: String
): Boolean {
    val contract = ImageExecutionProfileResolver.catalogProfileContractsByRecommendationId[recommendationId]
        ?: return false
    if (recommendationId == QWEN_IMAGE_21_RECOMMENDATION_ID) {
        // Direct-file Qwen bundle evidence is checked separately. Keep the existing strict
        // `archive!/member` rule unchanged for archive-backed recommendations.
        return qwenImage21DirectFileSourceEvidenceAgrees(input, contract)
    }
    val primaryComponents = contract.bundle.requiredComponents
        .filter { component -> component.role == ImageEngineBundleComponentRole.DIFFUSION }
    val pinnedRepositories = primaryComponents
        .asSequence()
        .map { component -> ImageExecutionProfileResolver.normalizeRepositoryIdentity(component.repoId) }
        .filter(String::isNotBlank)
        .toSet()
    val pinnedArchiveSourcePaths = primaryComponents
        .asSequence()
        .map { component -> normalizePinnedArchiveSourcePath(component.fileName) }
        .filter(String::isNotBlank)
        .toSet()
    if (pinnedRepositories.isEmpty() || pinnedArchiveSourcePaths.isEmpty()) return false
    val repositoryEvidence = input.recommendationEvidence.sourceRepositories
        .asSequence()
        .filter(String::isNotBlank)
        .map(ImageExecutionProfileResolver::normalizeRepositoryIdentity)
        // Local records also carry provider labels such as "local". Only owner/repository
        // identities are publisher evidence for this migration boundary.
        .filter { repository -> '/' in repository }
        .toSet()
    val archiveSourceEvidence = input.recommendationEvidence.artifactPaths
        .asSequence()
        .filter(String::isNotBlank)
        .mapNotNull(::archiveSourcePathOrNull)
        .toSet()
    return repositoryEvidence.isNotEmpty() &&
        repositoryEvidence.all(pinnedRepositories::contains) &&
        archiveSourceEvidence.isNotEmpty() &&
        archiveSourceEvidence.all(pinnedArchiveSourcePaths::contains)
}

private fun archiveSourcePathOrNull(value: String): String? {
    val normalized = value.trim().replace('\\', '/')
    val archiveDelimiter = normalized.indexOf("!/")
    if (archiveDelimiter <= 0) return null
    return normalizePinnedArchiveSourcePath(
        normalized
        .substring(0, archiveDelimiter)
    )
}

private fun normalizePinnedArchiveSourcePath(value: String): String = value
    .trim()
    .replace('\\', '/')
    .trim('/')
    .lowercase()

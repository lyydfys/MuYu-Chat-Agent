package com.muyuchat.mca

/**
 * P5/S46: the identity-normalization cluster (repository/id/token normalizers, the artifact
 * stem set and the strength classifier), moved verbatim out of ImageExecutionProfileResolver;
 * the classifier is internal because the resolver's catalog map still calls it. Behavior
 * unchanged.
 */

private val GENERIC_DIFFUSION_ARTIFACT_STEMS = setOf(
    "unet",
    "transformer",
    "model",
    "diffusion_model"
)

// P5/S44: called by the manifest-migration extraction (pure normalizer).
internal fun normalizeRepositoryIdentity(value: String): String {
    val normalized = value.trim().lowercase().replace('\\', '/').trim('/')
    val withoutProvider = listOf("hugging_face:", "modelscope:")
        .firstOrNull(normalized::startsWith)
        ?.let { prefix -> normalized.removePrefix(prefix) }
        ?: normalized
    return withoutProvider.removeSuffix(".git").trim('/')
}

// P5/S44: called by the manifest-migration extraction (pure tokenizer).
internal fun artifactIdentityTokens(value: String): Set<String> = buildSet {
    value.trim()
        .replace('\\', '/')
        .split('/')
        .asSequence()
        .filter(String::isNotBlank)
        .forEach { segment ->
            val normalized = normalizeIdentityToken(segment)
            if (normalized.isNotBlank()) {
                add(normalized)
                normalized.removePrefix("bundle_").takeIf(String::isNotBlank)?.let(::add)
                normalized.removeSuffix("_bundle").takeIf(String::isNotBlank)?.let(::add)
            }
            val stem = segment.substringBeforeLast('.', segment)
            val normalizedStem = normalizeIdentityToken(stem)
            if (normalizedStem.isNotBlank()) {
                add(normalizedStem)
                normalizedStem.removePrefix("bundle_").takeIf(String::isNotBlank)?.let(::add)
                normalizedStem.removeSuffix("_bundle").takeIf(String::isNotBlank)?.let(::add)
            }
        }
}

// P5/S44: called by the manifest-migration extraction (pure normalizer).
internal fun normalizeIdentityToken(value: String): String = value
    .trim()
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "_")
    .trim('_')

// P5/S46: module-visible for the resolver's catalog map (pure classifier).
internal fun isStrongCatalogImageArtifactIdentity(value: String): Boolean {
    val leaf = value.trim().replace('\\', '/').substringAfterLast('/')
    val stem = leaf.substringBeforeLast('.', leaf)
    return normalizeIdentityToken(stem) !in GENERIC_DIFFUSION_ARTIFACT_STEMS
}

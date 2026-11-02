package com.muyuchat.core.download

private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

/**
 * A pinned, remotely published image bundle (single ZIP) whose manifest is
 * fetched from a small download descriptor.  The descriptor is only a
 * transport for the pinned variants; the app trusts [variants] and verifies the
 * descriptor's content against [descriptorSha256] before using it.
 */
data class ImageEngineRemoteBundleVariant(
    val id: String,
    val conditioningMode: String,
    val file: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String
) {
    init {
        require(id.isNotBlank()) { "remote bundle variant id must not be blank." }
        require(conditioningMode.isNotBlank()) { "remote bundle variant conditioning mode must not be blank." }
        val normalized = file.trim().replace('\\', '/')
        require(normalized.endsWith(".zip", ignoreCase = true)) { "remote bundle variant must be a .zip archive." }
        require(normalized.isNotBlank() && !normalized.startsWith('/') &&
            !Regex("^[A-Za-z]:").containsMatchIn(normalized) &&
            normalized.split('/').all { it.isNotBlank() && it != "." && it != ".." }
        ) { "remote bundle variant path must be a safe relative path." }
        require(url.startsWith("https://")) { "remote bundle variant URL must use HTTPS." }
        require(sizeBytes > 0L) { "remote bundle variant size must be positive." }
        require(SHA256_PATTERN.matches(sha256)) { "remote bundle variant SHA-256 must be 64 lowercase hex characters." }
    }
}

data class ImageEngineRemoteBundleSpec(
    val recommendationId: String,
    val descriptorUrl: String,
    val descriptorBytes: Long,
    val descriptorSha256: String,
    val license: String,
    val licenseUrl: String,
    val noticeUrl: String,
    val defaultVariantId: String,
    val variants: List<ImageEngineRemoteBundleVariant>
) {
    init {
        require(recommendationId.isNotBlank()) { "remote bundle recommendation id must not be blank." }
        require(descriptorUrl.startsWith("https://")) { "download descriptor URL must use HTTPS." }
        require(descriptorBytes > 0L) { "download descriptor size must be positive." }
        require(SHA256_PATTERN.matches(descriptorSha256)) {
            "download descriptor SHA-256 must be 64 lowercase hex characters."
        }
        require(license.isNotBlank()) { "remote bundle license must not be blank." }
        require(licenseUrl.startsWith("https://")) { "license URL must use HTTPS." }
        require(noticeUrl.startsWith("https://")) { "notice URL must use HTTPS." }
        require(variants.isNotEmpty()) { "remote bundle must declare at least one variant." }
        require(variants.map { it.id }.distinct().size == variants.size) {
            "remote bundle variant ids must be unique."
        }
        require(variants.any { it.id == defaultVariantId }) {
            "remote bundle default variant must exist."
        }
    }

    fun variant(id: String = defaultVariantId): ImageEngineRemoteBundleVariant =
        variants.firstOrNull { it.id == id }
            ?: error("remote bundle variant $id is not declared.")
}

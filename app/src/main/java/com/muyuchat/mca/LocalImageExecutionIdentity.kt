package com.muyuchat.mca

import java.io.File
import java.security.MessageDigest

// P7/S37: private→internal pair after the identity-cluster move; the type is the return
// of the identity helper below, whose one caller stays in the old file.
internal data class LocalImageExecutionIdentity(
    val fingerprint: String,
    val scope: ImageModelFingerprintScope,
    val components: List<ImageBundleComponentEvidence> = emptyList()
)

internal fun modelExecutionIdentity(
    model: LocalImageModelRecord,
    manifestProfile: ImageExecutionProfile?
): LocalImageExecutionIdentity {
    // A bundle execution profile is bound to the complete on-disk bundle, not to
    // the diffusion file alone.  The persisted primary SHA is intentionally not
    // an identity source here: changing VAE, text encoder, tokenizer, context,
    // or the manifest contract must invalidate the old profile.
    if (model.bundleRoot?.isNotBlank() == true) {
        val snapshot = runCatching { captureLocalImageContentSnapshot(model) }
            .getOrElse { failure ->
                throw IllegalStateException(
                    "Image model bundle content cannot be fingerprinted: ${failure.message}",
                    failure
                )
            }
        val observed = snapshot.fingerprint.trim().lowercase().also { value ->
            require(value.matches(Regex("^[0-9a-f]{64}$"))) {
                "Image model bundle produced an invalid content fingerprint."
            }
        }
        val components = snapshot.components.map { component ->
            ImageBundleComponentEvidence(
                relativePath = component.relativePath,
                role = component.role,
                sha256 = component.sha256,
                sizeBytes = component.sizeBytes
            )
        }
        // Two fingerprint scopes are in circulation and both must keep working.
        //
        //  * A package installed by the current downloader has the observed complete-bundle
        //    digest written into its execution profile, so the bundle scope validates it.
        //  * A package installed before that contract existed still carries the primary-artifact
        //    digest, which is what the published recommendation profiles are authored against.
        //    Rejecting it would strand every already-installed image engine.
        //
        // Adopt the strong bundle identity whenever it agrees.  When the declared value instead
        // matches this bundle's own primary artifact, honour the legacy primary scope.  A value
        // that matches neither is still a hard mismatch: this is not a "trust the manifest" path,
        // because the declared value has to equal bytes we just observed on disk.
        val declared = manifestProfile?.modelFingerprint
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
        // Match the primary artifact by its bundle-relative path: a manifest that already
        // declares that path keeps its own role ("DIFFUSION"), so role lookup is not reliable.
        val primaryArtifactFingerprint = runCatching {
            File(model.path).canonicalFile
                .relativeTo(File(model.bundleRoot).canonicalFile)
                .toString()
                .replace('\\', '/')
        }.getOrNull()
            ?.let { relative -> snapshot.components.firstOrNull { it.relativePath == relative } }
            ?.sha256
            ?.trim()
            ?.lowercase()
        // The pinned Qwen-Image-2.1 package is a third case: its published profile identity is the
        // known legacy VAE-decoder digest rather than the diffusion primary, and the resolver has
        // a matching `QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT` readiness path for it.
        val isKnownLegacyQwenIdentity = declared != null &&
            manifestProfile?.variant == ImageModelVariant.QWEN_IMAGE_21 &&
            manifestProfile.provenance.recommendationId == "qwen_image_21_mnn_opencl" &&
            isQwenImage21LegacyVaeProfileFingerprint(declared)
        if (declared != null && declared != observed &&
            (declared == primaryArtifactFingerprint || isKnownLegacyQwenIdentity)
        ) {
            return LocalImageExecutionIdentity(
                fingerprint = declared,
                scope = ImageModelFingerprintScope.PRIMARY_FILE,
                components = components
            )
        }
        return LocalImageExecutionIdentity(
            fingerprint = observed,
            scope = ImageModelFingerprintScope.COMPLETE_BUNDLE,
            components = components
        )
    }

    // Single-file models have no companion content to bind. Resolve their real
    // bytes at the execution boundary; trusting a manifest value as both
    // expected and actual would let a replaced/corrupt model self-attest.
    val manifestFingerprint = manifestProfile?.modelFingerprint
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
    val file = File(model.path)
    require(file.isFile && file.length() > 0L) {
        "Image model file is missing or empty: ${file.path}"
    }
    val actual = observedLocalImageFileSha256(file).lowercase()
    val knownQwenLegacyProfile = manifestProfile?.variant == ImageModelVariant.QWEN_IMAGE_21 &&
        manifestProfile.provenance.recommendationId == "qwen_image_21_mnn_opencl" &&
        manifestFingerprint != null &&
        isQwenImage21LegacyVaeProfileFingerprint(manifestFingerprint)
    require(manifestFingerprint == null || manifestFingerprint == actual || knownQwenLegacyProfile) {
        "Image model content does not match the execution profile fingerprint: " +
            "profile=${manifestFingerprint ?: "unknown"}, observed=$actual, path=${file.path}."
    }
    return LocalImageExecutionIdentity(
        fingerprint = actual,
        scope = ImageModelFingerprintScope.PRIMARY_FILE
    )
}

internal fun File.sha256ForProfile(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

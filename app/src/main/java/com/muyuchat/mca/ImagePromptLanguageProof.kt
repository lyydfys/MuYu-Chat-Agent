package com.muyuchat.mca

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.Locale

private const val IMAGE_PROMPT_LANGUAGE_PROOF_VERSION = 2
private const val IMAGE_PROMPT_LANGUAGE_SIGNER_KEY_ID = "mca-app-signing-certificate-v1"
private const val IMAGE_PROMPT_LANGUAGE_PROOF_FRAME =
    "mca.image.text-encoder-language-semantic-proof.v2"
private val IMAGE_PROMPT_LANGUAGE_SHA256 = Regex("^[0-9a-f]{64}$")
private val IMAGE_PROMPT_LANGUAGE_BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

private data class TrustedImagePromptLanguageSigner(
    val certificateSha256: String,
    val publicKey: PublicKey,
    val signatureAlgorithm: String
)

/**
 * Verifies the publisher's semantic proof against the app's own signing-certificate lineage.
 *
 * This is a release-stable trust root without a model, recommendation, chipset, device, or
 * profile allowlist. A debug or locally signed APK has a different real signer and therefore
 * cannot accidentally claim that a release-signed proof was validated. A missing/invalid proof
 * simply makes the profile English-dominant; it never blocks download, import, load, or English
 * generation.
 */
internal object ImagePromptLanguageProofTrust {
    @Volatile
    private var trustedSigners: List<TrustedImagePromptLanguageSigner> = emptyList()

    fun initialize(context: Context) {
        trustedSigners = runCatching {
            signingCertificates(context.applicationContext)
                .mapNotNull(::trustedSignerOrNull)
                .distinctBy { signer -> signer.certificateSha256 }
        }.getOrElse { emptyList() }
    }

    fun isVerified(
        profile: ImageExecutionProfile,
        contract: ImageTextEncoderLanguageContract,
        evidence: ImageTextEncoderLanguageEvidence
    ): Boolean {
        val proof = evidence.semanticProof ?: return false
        val signers = trustedSigners
        if (signers.isEmpty() ||
            proof.proofVersion != IMAGE_PROMPT_LANGUAGE_PROOF_VERSION ||
            proof.signerKeyId != IMAGE_PROMPT_LANGUAGE_SIGNER_KEY_ID ||
            !IMAGE_PROMPT_LANGUAGE_SHA256.matches(proof.signerCertificateSha256) ||
            !IMAGE_PROMPT_LANGUAGE_SHA256.matches(proof.payloadSha256) ||
            !isStrictBase64(proof.signatureBase64)
        ) {
            return false
        }

        val payload = canonicalPayload(profile, contract, evidence)
        val payloadSha256 = sha256Hex(payload)
        if (!MessageDigest.isEqual(
                payloadSha256.toByteArray(Charsets.US_ASCII),
                proof.payloadSha256.toByteArray(Charsets.US_ASCII)
            )
        ) {
            return false
        }
        val signature = try {
            Base64.getDecoder().decode(proof.signatureBase64)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (signature.isEmpty()) return false

        return signers.any { signer ->
            signer.certificateSha256 == proof.signerCertificateSha256 &&
                signer.signatureAlgorithm == proof.signatureAlgorithm &&
                verifies(signer, payload, signature)
        }
    }

    internal fun canonicalPayloadSha256(
        profile: ImageExecutionProfile,
        contract: ImageTextEncoderLanguageContract,
        evidence: ImageTextEncoderLanguageEvidence
    ): String = sha256Hex(canonicalPayload(profile, contract, evidence))

    private fun verifies(
        signer: TrustedImagePromptLanguageSigner,
        payload: String,
        signature: ByteArray
    ): Boolean = try {
        Signature.getInstance(signer.signatureAlgorithm).run {
            initVerify(signer.publicKey)
            update(payload.toByteArray(Charsets.UTF_8))
            verify(signature)
        }
    } catch (_: Exception) {
        false
    }

    private fun canonicalPayload(
        profile: ImageExecutionProfile,
        contract: ImageTextEncoderLanguageContract,
        evidence: ImageTextEncoderLanguageEvidence
    ): String = buildList {
        add(IMAGE_PROMPT_LANGUAGE_PROOF_FRAME)
        add("proofVersion=$IMAGE_PROMPT_LANGUAGE_PROOF_VERSION")
        add("promptLanguageContractVersion=$LOCAL_IMAGE_PROMPT_LANGUAGE_CONTRACT_VERSION")
        add("profileSchemaVersion=${profile.schemaVersion}")
        add("profileId=${profile.profileId}")
        add("profileRevision=${profile.profileRevision}")
        add("modelFingerprint=${profile.modelFingerprint.lowercase(Locale.ROOT)}")
        add("runtime=${profile.runtime.name}")
        add("workerStrategy=${profile.graph.workerStrategy.name}")
        add("graphTextEncoder=${profile.graph.textEncoder?.relativePath.orEmpty().normalizeProofPath()}")
        add("textEncoderGraphName=${profile.graph.textEncoder?.graphName.orEmpty().normalizeGraphName()}")
        add("tokenizerBackend=${profile.tokenizer.backend.name}")
        add("tokenizerBosId=${profile.tokenizer.bosId ?: -1}")
        add("tokenizerEosId=${profile.tokenizer.eosId ?: -1}")
        add("tokenizerPadId=${profile.tokenizer.padId ?: -1}")
        add("tokenizerMaxLength=${profile.tokenizer.maxLength}")
        add("tokenizerUnicodeNormalization=${profile.tokenizer.unicodeNormalization.name}")
        add("tokenizerLowercase=${profile.tokenizer.lowercase}")
        add("tokenizerPreTokenizer=${profile.tokenizer.preTokenizer}")
        add("tokenizerPostProcessor=${profile.tokenizer.postProcessor}")
        add("tokenizerClip1PadRule=${profile.tokenizer.clip1PadRule.name}")
        add("tokenizerClip2PadRule=${profile.tokenizer.clip2PadRule?.name.orEmpty()}")
        add("tokenizerSeparateNegativePrompt=${profile.tokenizer.separateNegativePrompt}")
        add("dualEncoder=${profile.conditioning.dualEncoder}")
        add("capability=${contract.capability.name}")
        add(
            "supportedLanguages=" + contract.supportedLanguages
                .sortedBy { language -> language.ordinal }
                .joinToString(",") { language -> language.name }
        )
        add("evidenceId=${evidence.evidenceId.lowercase(Locale.ROOT)}")
        add("evidenceSha256=${evidence.evidenceSha256.lowercase(Locale.ROOT)}")
        val assets = evidence.promptToEncoderAssets
            .sortedBy { entry -> entry.role.ordinal }
        add("assetCount=${assets.size}")
        assets.forEachIndexed { index, asset ->
            add("asset[$index].role=${asset.role.name}")
            add("asset[$index].path=${asset.asset.relativePath.normalizeProofPath()}")
            add("asset[$index].sha256=${asset.asset.fingerprint.lowercase(Locale.ROOT)}")
            add("asset[$index].sizeBytes=${asset.asset.sizeBytes ?: -1L}")
        }
    }.joinToString("\u001f")

    private fun String.normalizeProofPath(): String = replace('\\', '/').trim()

    private fun String.normalizeGraphName(): String = trim()

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun isStrictBase64(value: String): Boolean =
        value.length in 4..16_384 && value.length % 4 == 0 && IMAGE_PROMPT_LANGUAGE_BASE64.matches(value)

    private fun signingCertificates(context: Context): List<ByteArray> {
        val packageManager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo ?: return emptyList()
            buildList {
                signingInfo.apkContentsSigners?.forEach { signature -> add(signature.toByteArray()) }
                if (signingInfo.hasPastSigningCertificates()) {
                    signingInfo.signingCertificateHistory?.forEach { signature ->
                        add(signature.toByteArray())
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            ).signatures.orEmpty().map { signature -> signature.toByteArray() }
        }
    }

    private fun trustedSignerOrNull(
        certificateBytes: ByteArray
    ): TrustedImagePromptLanguageSigner? {
        return try {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificateBytes))
            val publicKey = certificate.publicKey
            val signatureAlgorithm = when (publicKey.algorithm.uppercase(Locale.ROOT)) {
                "RSA" -> "SHA256withRSA"
                "EC", "ECDSA" -> "SHA256withECDSA"
                else -> return null
            }
            TrustedImagePromptLanguageSigner(
                certificateSha256 = MessageDigest.getInstance("SHA-256")
                    .digest(certificate.encoded)
                    .joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    },
                publicKey = publicKey,
                signatureAlgorithm = signatureAlgorithm
            )
        } catch (_: Exception) {
            null
        }
    }
}

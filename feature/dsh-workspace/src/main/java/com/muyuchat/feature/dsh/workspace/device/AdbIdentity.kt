package com.muyuchat.feature.dsh.workspace.device

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * The app's ADB RSA identity.
 *
 * The adb daemon authenticates a host by its key: it sends a 20-byte token, the host signs it with
 * RSA PKCS#1 v1.5 + SHA-1, and if the public key is not trusted yet the host uploads the key in
 * adb's own `RSAPublicKey` wire format so the user can authorise it (system wireless-debugging pair
 * dialog, or a line in `/data/misc/adb/adb_keys`).
 *
 * Storage: a 2048-bit PKCS#8 private key in an app-private file. The file path is injected by the
 * caller so tests can use a temporary directory; the key is generated once and reused on every later
 * load (a new key would silently invalidate the user's authorisation).
 */
class AdbIdentity private constructor(private val keyPair: KeyPair) {

    private val rsaPublic: RSAPublicKey = keyPair.public as RSAPublicKey

    /** RSA PKCS#1 v1.5 + SHA-1 signature over the adb token, as adbd expects. */
    fun signToken(token: ByteArray): ByteArray = runCatching {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(keyPair.private)
        signature.update(token)
        signature.sign()
    }.getOrDefault(ByteArray(0))

    /**
     * adb's `RSAPublicKey` structure, little-endian:
     * `uint32 len | uint32 n0inv | uint8 n[len] | uint8 rr[len]`.
     *
     * `n` is the modulus (LE, zero padded to `len`), `n0inv = -1/n mod 2^32` and `rr = R^2 mod n`
     * with `R = 2^(8*len)` — the values adbd's `BN_mod_inverse`-based verifier needs.
     */
    fun publicKeyWireFormat(): ByteArray {
        val modulus = rsaPublic.modulus
        val length = (modulus.bitLength() + 7) / 8
        val out = ByteArray(4 + 4 + length + length)
        writeIntLe(out, 0, length)
        writeIntLe(out, 4, n0inv(modulus))
        copyLittleEndian(out, 8, modulus, length)
        val r = BigInteger.ONE.shiftLeft(length * 8)
        copyLittleEndian(out, 8 + length, r.multiply(r).mod(modulus), length)
        return out
    }

    /** Single-line base64 of [publicKeyWireFormat]; this exact string is what `adb_keys` stores. */
    fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(publicKeyWireFormat())

    /** Payload of `AUTH(ADB_AUTH_RSAPUBLICKEY)`: base64 of [publicKeyWireFormat] plus a NUL. */
    fun publicKeyPayload(): ByteArray = (publicKeyBase64() + "\u0000").toByteArray(Charsets.UTF_8)

    companion object {

        /** adb expects 2048-bit RSA keys; the wire format hard-codes the split accordingly. */
        const val KEY_SIZE_BITS: Int = 2048

        /** `SHA1withRSA` is PKCS#1 v1.5 with SHA-1, the pair adbd's token check uses. */
        const val SIGNATURE_ALGORITHM: String = "SHA1withRSA"

        private val LOAD_LOCK = Any()

        /**
         * Loads the identity from [keyFile], generating and persisting a new 2048-bit key on first
         * use (or when the stored file cannot be parsed anymore).
         */
        fun loadOrCreate(keyFile: File): AdbIdentity {
            synchronized(LOAD_LOCK) {
                readExisting(keyFile)?.let { return AdbIdentity(it) }
                val generated = generateKeyPair()
                persist(keyFile, generated)
                return AdbIdentity(generated)
            }
        }

        private fun generateKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE_BITS) }.generateKeyPair()

        private fun readExisting(keyFile: File): KeyPair? = runCatching {
            if (!keyFile.isFile) return null
            val encoded = keyFile.readBytes()
            if (encoded.isEmpty()) return null
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(encoded))
            publicKeyOf(privateKey)?.let { KeyPair(it, privateKey) }
        }.getOrNull()

        /** PKCS#8 stores the private key only; the public half is derived from the CRT parameters. */
        private fun publicKeyOf(privateKey: PrivateKey): RSAPublicKey? {
            val crt = privateKey as? RSAPrivateCrtKey ?: return null
            return runCatching {
                KeyFactory.getInstance("RSA").generatePublic(
                    RSAPublicKeySpec(crt.modulus, crt.publicExponent)
                ) as? RSAPublicKey
            }.getOrNull()
        }

        private fun persist(keyFile: File, keyPair: KeyPair) {
            runCatching {
                keyFile.parentFile?.mkdirs()
                keyFile.writeBytes(keyPair.private.encoded)
            }
            // Best effort on both Android and a plain JVM: only this app's uid may read the key.
            runCatching {
                keyFile.setReadable(false, false)
                keyFile.setReadable(true, true)
                keyFile.setWritable(false, false)
                keyFile.setWritable(true, true)
            }
        }

        /** `-1/n mod 2^32`, as a 32-bit two's-complement int (adb stores it as a `uint32_t`). */
        private fun n0inv(modulus: BigInteger): Int {
            val twoPow32 = BigInteger.ONE.shiftLeft(32)
            val n0 = modulus.mod(twoPow32)
            val inverse = n0.modInverse(twoPow32)
            return twoPow32.subtract(inverse).toLong().toInt()
        }

        /** Writes [value] as [size] little-endian bytes; a value wider than [size] keeps its low bytes. */
        private fun copyLittleEndian(target: ByteArray, offset: Int, value: BigInteger, size: Int) {
            val bigEndian = value.toByteArray()
            val first = if (bigEndian.size > 1 && bigEndian[0] == 0.toByte()) 1 else 0
            val length = minOf(bigEndian.size - first, size)
            for (index in 0 until length) {
                target[offset + index] = bigEndian[bigEndian.size - 1 - index]
            }
        }
    }
}

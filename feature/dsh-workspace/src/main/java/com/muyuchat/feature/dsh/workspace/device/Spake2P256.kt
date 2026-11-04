package com.muyuchat.feature.dsh.workspace.device

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** Which side of SPAKE2 this endpoint is. Role A masks with M, role B masks with N. */
enum class Spake2Role { A, B }

/**
 * The group-independent choices of one SPAKE2 instantiation: masking points and hash.
 *
 * [RFC9382_P256_SHA256] is the P-256 / SHA-256 / HKDF / HMAC ciphersuite of RFC 9382 §6, using the
 * published compressed seed points from §6.
 *
 * **Not adb's parameters.** AOSP `pairing_auth/pairing_auth.cpp` does not use P-256 SPAKE2 at all:
 * it calls BoringSSL's `SPAKE2_CTX_new` (Curve25519, `system/core`/`packages/modules/adb`, roles
 * `spake2_role_alice`/`spake2_role_bob`, names `"adb pair client"`/`"adb pair server"`). There are
 * no `adbM`/`adbN` constants in AOSP to copy, and RFC 9382's P-256 masking points are unrelated to
 * whatever masking BoringSSL derives internally. Any M/N for a future adb-compatible implementation
 * must come from BoringSSL's `spake25519` source, i.e. from a Curve25519 rewrite — see the module
 * report; the constants are injectable here so that a real suite can be supplied without touching
 * the protocol code.
 */
class Spake2Suite private constructor(
    /** Masking point used by role A. */
    val maskForA: P256Point,
    /** Masking point used by role B. */
    val maskForB: P256Point,
    /** JCA digest name, e.g. `SHA-256`. */
    val hashAlgorithm: String,
    /** JCA MAC name matching [hashAlgorithm], e.g. `HmacSHA256`. */
    val macAlgorithm: String
) {

    companion object {
        /** RFC 9382 §6, P-256 + SHA-256 + HKDF-SHA256 + HMAC-SHA256. */
        val RFC9382_P256_SHA256: Spake2Suite = of(
            compressedMaskA = hexToBytes("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"),
            compressedMaskB = hexToBytes("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49")
        )

        /**
         * Builds a suite from SEC1 compressed masking points.
         *
         * Injected rather than hard-coded so a future adb-compatible Curve25519 work (or a
         * per-user M/N variant from RFC 9382 §5) can be plugged in; [suite] is validated here.
         */
        fun of(
            compressedMaskA: ByteArray,
            compressedMaskB: ByteArray,
            hashAlgorithm: String = "SHA-256",
            macAlgorithm: String = "HmacSHA256"
        ): Spake2Suite {
            val maskA = requireNotNull(P256Point.decode(compressedMaskA)) {
                "SPAKE2 masking point M is not a valid P-256 point"
            }
            val maskB = requireNotNull(P256Point.decode(compressedMaskB)) {
                "SPAKE2 masking point N is not a valid P-256 point"
            }
            return Spake2Suite(maskA, maskB, hashAlgorithm, macAlgorithm)
        }
    }
}

/**
 * How a password becomes SPAKE2's integer `w`.
 *
 * RFC 9382 §3.2 deliberately leaves this to the application and only requires that the choice be
 * specified and that hashing be an MHF where possible. MCA's choice: PBKDF2-HMAC-SHA256 with a
 * per-install salt, 32 bytes of output, then `w = PBKDF2 output mod (n - 1) + 1` so `w` is in
 * `[1, n-1]` and never zero. Salt and iteration count are constructor parameters, so a deployment
 * can raise the cost without touching the protocol.
 *
 * **This is not adb's rule.** adb feeds the six-digit pairing code straight to
 * `SPAKE2_generate_msg()` as the password; BoringSSL's Curve25519 SPAKE2 hashes it internally and
 * there is no PBKDF2 iteration count in `pairing_auth.cpp`. Do not read this policy as wire
 * compatible with adbd.
 */
class Spake2PasswordPolicy(
    val salt: ByteArray = DEFAULT_SALT,
    val iterations: Int = DEFAULT_ITERATIONS
) {
    init {
        require(salt.isNotEmpty()) { "SPAKE2 password salt must not be empty" }
        require(iterations >= 1) { "PBKDF2 iterations must be at least 1" }
    }

    /** `w` for [password]; deterministic for a given salt/iteration pair. */
    fun deriveW(password: ByteArray): BigInteger {
        require(password.isNotEmpty()) { "SPAKE2 password must not be empty" }
        val derived = pbkdf2Sha256(password, salt, iterations, P256.FIELD_BYTES)
        return BigInteger(1, derived).mod(P256.n.subtract(BigInteger.ONE)).add(BigInteger.ONE)
    }

    companion object {
        /** Protocol domain separation for the MCA preset/ADB pairing experiments. */
        val DEFAULT_SALT: ByteArray = "mca.spake2.p256.v1".toByteArray(Charsets.US_ASCII)

        /** Deliberately expensive default; tests override it to keep the suite fast. */
        const val DEFAULT_ITERATIONS: Int = 100_000
    }
}

/**
 * Result of the SPAKE2 key schedule (RFC 9382 §3.3 + §4).
 *
 * The intermediate values are exposed because the RFC's Appendix B vectors pin them; production
 * callers only need [sharedKey], [ownConfirm] and [verifyPeerConfirm].
 */
class Spake2Result internal constructor(
    val role: Spake2Role,
    /** The peer share that was accepted (SEC1 uncompressed). */
    val peerShare: ByteArray,
    /** `K`, the raw unmasked DH value. Must never be used as a key (RFC 9382 §3.3). */
    val sharedPoint: ByteArray,
    /** `TT`, the protocol transcript. */
    val transcript: ByteArray,
    /** `Hash(TT)`, i.e. `Ke || Ka`. */
    val transcriptHash: ByteArray,
    /** `Ke`: the SPAKE2 shared key. Half of the digest. */
    val sharedKey: ByteArray,
    /** `Ka`: confirmation-key derivation material. */
    val confirmationMaterial: ByteArray,
    /** `KcA`: MAC key of role A. */
    val confirmKeyA: ByteArray,
    /** `KcB`: MAC key of role B. */
    val confirmKeyB: ByteArray,
    /** `cA` for role A, `cB` for role B. */
    val ownConfirm: ByteArray,
    /** The MAC this endpoint expects from its peer. */
    val expectedPeerConfirm: ByteArray
) {
    /**
     * Verifies the peer's key-confirmation message in constant time.
     *
     * A SPAKE2 exchange is only finished when this returns true — a wrong password produces
     * different `w`, hence different `K`, hence different confirmation keys.
     */
    fun verifyPeerConfirm(received: ByteArray): Boolean =
        constantTimeEquals(expectedPeerConfirm, received)
}

/**
 * One endpoint of a SPAKE2 exchange over P-256 (RFC 9382).
 *
 * Flow is two rounds:
 * 1. Construct the endpoint, send [share] to the peer.
 * 2. Feed the peer's share to [complete], send [Spake2Result.ownConfirm].
 * 3. Verify the peer's confirmation with [Spake2Result.verifyPeerConfirm].
 *
 * **Scope warning.** This implements RFC 9382, which is *not* the protocol used by a real
 * `adbd` pairing port: AOSP adb pairs with BoringSSL's Curve25519 SPAKE2 plus its own AES-128-GCM
 * packet layer (and a certificate-based TLS handshake whose exported keying material is folded into
 * the password). A share or confirm produced here cannot be understood by `adbd`, and no TLS
 * 1.3-PSK stack exists in this repository. This class is the verifiable cryptography half only.
 */
class Spake2P256 internal constructor(
    val role: Spake2Role,
    /** Identity of role A (empty allowed, as in RFC 9382 §3.3 for implicit-identity uses). */
    val identityA: ByteArray,
    /** Identity of role B. */
    val identityB: ByteArray,
    private val scalar: BigInteger,
    private val passwordScalar: BigInteger,
    private val suite: Spake2Suite
) {

    private val mask: P256Point =
        if (role == Spake2Role.A) suite.maskForA else suite.maskForB

    private val peerMask: P256Point =
        if (role == Spake2Role.A) suite.maskForB else suite.maskForA

    /** Our public SPAKE2 share (`pA` for role A, `pB` for role B), SEC1 uncompressed, 65 bytes. */
    val share: ByteArray = P256.add(
        P256.multiply(scalar, P256.g),
        P256.multiply(passwordScalar, mask)
    ).encodeUncompressed()

    /**
     * Takes the peer's share and derives the key schedule, or returns null when the peer's value is
     * unusable: malformed SEC1, not on the curve, out of range, the identity element, or a share
     * that unmasks to the identity. All of those must abort the protocol (RFC 9382 §7).
     */
    fun complete(peerShare: ByteArray): Spake2Result? {
        val peerPoint = P256Point.decode(peerShare) ?: return null
        if (peerPoint.isInfinity) return null
        // K = h * scalar * (peerShare - w * peerMask); h = 1 for P-256.
        val unmasked = P256.add(peerPoint, P256.negate(P256.multiply(passwordScalar, peerMask)))
        if (unmasked.isInfinity) return null
        val shared = P256.multiply(scalar, unmasked)
        if (shared.isInfinity) return null

        val ownShare = share
        val shareA = if (role == Spake2Role.A) ownShare else peerShare
        val shareB = if (role == Spake2Role.A) peerShare else ownShare
        val transcript = transcript(
            identityA = identityA,
            identityB = identityB,
            shareA = shareA,
            shareB = shareB,
            sharedPoint = shared.encodeUncompressed(),
            passwordScalar = passwordScalar
        )
        val digest = MessageDigest.getInstance(suite.hashAlgorithm).digest(transcript)
        val half = digest.size / 2
        val sharedKey = digest.copyOfRange(0, half)
        val confirmationMaterial = digest.copyOfRange(half, digest.size)
        val confirmationKeys = hkdf(
            ikm = confirmationMaterial,
            salt = ByteArray(0),
            info = CONFIRMATION_INFO,
            length = digest.size,
            macAlgorithm = suite.macAlgorithm
        )
        val confirmKeyA = confirmationKeys.copyOfRange(0, half)
        val confirmKeyB = confirmationKeys.copyOfRange(half, confirmationKeys.size)
        val ownKey = if (role == Spake2Role.A) confirmKeyA else confirmKeyB
        val peerKey = if (role == Spake2Role.A) confirmKeyB else confirmKeyA

        return Spake2Result(
            role = role,
            peerShare = peerShare.copyOf(),
            sharedPoint = shared.encodeUncompressed(),
            transcript = transcript,
            transcriptHash = digest,
            sharedKey = sharedKey,
            confirmationMaterial = confirmationMaterial,
            confirmKeyA = confirmKeyA,
            confirmKeyB = confirmKeyB,
            ownConfirm = hmac(ownKey, transcript, suite.macAlgorithm),
            expectedPeerConfirm = hmac(peerKey, transcript, suite.macAlgorithm)
        )
    }

    companion object {
        /** RFC 9382 §4: `KcA || KcB = KDF(Ka, nil, "ConfirmationKeys" || AAD, L)`. */
        private val CONFIRMATION_INFO: ByteArray = "ConfirmationKeys".toByteArray(Charsets.US_ASCII)

        /**
         * Starts an endpoint from a password.
         *
         * [random] is injectable so tests can pin scalars; production callers use a fresh
         * [SecureRandom] (RFC 9382 §7: scalars must be uniform and never reused).
         */
        fun start(
            role: Spake2Role,
            identityA: ByteArray,
            identityB: ByteArray,
            password: ByteArray,
            policy: Spake2PasswordPolicy = Spake2PasswordPolicy(),
            random: SecureRandom = SecureRandom(),
            suite: Spake2Suite = Spake2Suite.RFC9382_P256_SHA256
        ): Spake2P256 {
            require(password.isNotEmpty()) { "SPAKE2 password must not be empty" }
            return Spake2P256(
                role = role,
                identityA = identityA,
                identityB = identityB,
                scalar = P256.randomScalar(random),
                passwordScalar = policy.deriveW(password),
                suite = suite
            )
        }

        /**
         * Test seam: pins `x`/`y` and `w` so published RFC 9382 vectors can be reproduced exactly.
         * Production code must use [start], which derives `w` from a password and draws a fresh
         * random scalar.
         */
        internal fun startWithScalars(
            role: Spake2Role,
            identityA: ByteArray,
            identityB: ByteArray,
            scalar: BigInteger,
            passwordScalar: BigInteger,
            suite: Spake2Suite = Spake2Suite.RFC9382_P256_SHA256
        ): Spake2P256 {
            require(scalar.signum() > 0 && scalar < P256.n) { "SPAKE2 scalar must be in (0, n)" }
            require(passwordScalar.signum() > 0 && passwordScalar < P256.n) {
                "SPAKE2 password scalar w must be in (0, n)"
            }
            return Spake2P256(role, identityA, identityB, scalar, passwordScalar, suite)
        }
    }
}

package com.muyuchat.feature.dsh.workspace.device

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** Role in adb's SPAKE2: the host is Alice (masks with M), the device is Bob (masks with N). */
enum class Spake2Curve25519Role {
    /** `spake2_role_alice`; AOSP `pairing_auth` maps the *client* (adb host) to this role. */
    ALICE,

    /** `spake2_role_bob`; AOSP maps the device (adbd) to this role. */
    BOB
}

/**
 * The Ed25519 group, enough for BoringSSL's SPAKE2: strict decoding, canonical encoding, unified
 * twisted-Edwards addition (doubling is the same formula) and scalar multiplication.
 *
 * Deliberately independent of any crypto provider: the whole point of this file is to reproduce
 * BoringSSL's arithmetic on a JVM/Android without native code. Field elements are [BigInteger]s mod
 * `2^255 - 19`; that is not constant-time (a local side-channel attacker could observe BigInteger's
 * behaviour), which is acceptable only because the peers are an on-device app and its own device —
 * this caveat is carried into the module report, not hidden here.
 */
internal object Ed25519 {
    /** Field prime `2^255 - 19`. */
    val p: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

    /** Order of the prime-order subgroup, `2^252 + 27742317777372353535851937790883648493`. */
    val l: BigInteger = BigInteger.valueOf(2).pow(252)
        .add(BigInteger("14def9dea2f79cd65812631a5cf5d3ed", 16))

    private val EIGHT = BigInteger.valueOf(8)
    private val TWO = BigInteger.valueOf(2)

    /** Curve constant `d = -121665/121666 mod p`. */
    val d: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(p))
        .mod(p)

    /** A point in affine coordinates. Instances are only produced by [decode]/[base]/[add]. */
    data class Point(val x: BigInteger, val y: BigInteger)

    /** The Ed25519 basepoint `B`. */
    val base: Point by lazy {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(p)).mod(p)
        val x = recoverX(y, signBit = false) ?: error("failed to derive the Ed25519 basepoint")
        Point(x, y)
    }

    /** Identity element `(0, 1)`. */
    val identity: Point = Point(BigInteger.ZERO, BigInteger.ONE)

    fun isIdentity(point: Point): Boolean =
        point.x.signum() == 0 && point.y == BigInteger.ONE

    /**
     * Unified twisted-Edwards addition (`a = -1`, `add-2008-hwcd` in affine form), which also
     * doubles when both operands are equal.
     */
    fun add(left: Point, right: Point): Point {
        val x1x2 = left.x.multiply(right.x).mod(p)
        val y1y2 = left.y.multiply(right.y).mod(p)
        val dxy = d.multiply(x1x2).multiply(y1y2).mod(p)
        val xNumerator = left.x.multiply(right.y).add(right.x.multiply(left.y)).mod(p)
        val yNumerator = y1y2.add(x1x2).mod(p)
        val x = xNumerator.multiply(BigInteger.ONE.add(dxy).modInverse(p)).mod(p)
        val y = yNumerator.multiply(BigInteger.ONE.subtract(dxy).mod(p).modInverse(p)).mod(p)
        return Point(x, y)
    }

    /** `-P`. */
    fun negate(point: Point): Point = Point(p.subtract(point.x).mod(p), point.y)

    /** `-P` by group subtraction, used to unmask a peer share. */
    fun subtract(left: Point, right: Point): Point = add(left, negate(right))

    /** `k * P` for any non-negative `k` (BoringSSL passes full-width, non-reduced scalars). */
    fun multiply(scalar: BigInteger, point: Point): Point {
        require(scalar.signum() >= 0) { "scalar must be non-negative" }
        var k = scalar
        var addend = point
        var result = identity
        while (k.signum() > 0) {
            if (k.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            k = k.shiftRight(1)
        }
        return result
    }

    /**
     * Canonical 32-byte encoding: little-endian `y` with the low bit of `x` in the top bit.
     *
     * Matches BoringSSL's `ge_tobytes`: the identity encodes as `01 00 … 00`, and coordinates are
     * reduced mod p first, so the output is canonical.
     */
    fun encode(point: Point): ByteArray {
        val out = littleEndian(point.y.mod(p), 32)
        if (point.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /**
     * Strict decoding of a 32-byte compressed point, or null.
     *
     * Rejected: `y >= p` (non-canonical), a `y` whose `(y^2-1)/(d*y^2+1)` is not a square, and the
     * `x = 0` with sign bit set encoding. BoringSSL's `ge_frombytes_vartime` is slightly more
     * permissive about non-canonical `y`; rejecting it fails closed, which is the safe direction for
     * a peer-supplied value.
     */
    fun decode(encoded: ByteArray): Point? {
        if (encoded.size != 32) return null
        val bytes = encoded.copyOf()
        val signBit = (bytes[31].toInt() and 0x80) != 0
        bytes[31] = (bytes[31].toInt() and 0x7f).toByte()
        val y = fromLittleEndian(bytes)
        if (y >= p) return null
        val x = recoverX(y, signBit) ?: return null
        if (x.signum() == 0 && signBit) return null
        return Point(x, y)
    }

    /**
     * Reduces a 64-byte little-endian value modulo [l], returning 32 little-endian bytes.
     *
     * This is BoringSSL's `x25519_sc_reduce`, which the SPAKE2 code applies to the ephemeral random
     * value and to `SHA-512(password)`.
     */
    fun scalarReduce(bytes64: ByteArray): ByteArray {
        require(bytes64.size == 64) { "scalarReduce expects 64 bytes" }
        val value = fromLittleEndian(bytes64)
        return littleEndian(value.mod(l), 32)
    }

    /** Little-endian unsigned decode. */
    fun fromLittleEndian(bytes: ByteArray): BigInteger {
        val bigEndian = bytes.reversedArray()
        return BigInteger(1, bigEndian)
    }

    /** Fixed-width little-endian encode; a value that does not fit is a programming error. */
    fun littleEndian(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val first = if (raw.size > 1 && raw[0] == 0.toByte()) 1 else 0
        val significant = raw.size - first
        require(significant <= length) { "value does not fit in $length bytes" }
        val out = ByteArray(length)
        for (index in 0 until significant) {
            out[index] = raw[raw.size - 1 - index]
        }
        return out
    }

    /** `x = ±sqrt((y^2 - 1) / (d*y^2 + 1))`, or null when the right-hand side is not a square. */
    private fun recoverX(y: BigInteger, signBit: Boolean): BigInteger? {
        val y2 = y.multiply(y).mod(p)
        val numerator = y2.subtract(BigInteger.ONE).mod(p)
        val denominator = d.multiply(y2).add(BigInteger.ONE).mod(p)
        if (denominator.signum() == 0) return null
        val rhs = numerator.multiply(denominator.modInverse(p)).mod(p)
        val x = sqrt(rhs) ?: return null
        val signed = if (x.testBit(0) == signBit) x else p.subtract(x).mod(p)
        // Verify the candidate really is on the curve: -x^2 + y^2 == 1 + d*x^2*y^2.
        val x2 = signed.multiply(signed).mod(p)
        val left = y2.subtract(x2).mod(p)
        val right = BigInteger.ONE.add(d.multiply(x2).multiply(y2).mod(p)).mod(p)
        return if (left == right) signed else null
    }

    /** Square root mod p using RFC 8032's `(p+3)/8` recipe, plus the `sqrt(-1)` correction step. */
    internal fun sqrt(value: BigInteger): BigInteger? {
        val v = value.mod(p)
        if (v.signum() == 0) return BigInteger.ZERO
        // s = v^((p+3)/8); s^2 is then either v or -v, depending on whether v is a quartic residue.
        val s = v.modPow(p.add(BigInteger.valueOf(3)).shiftRight(3), p)
        val square = s.multiply(s).mod(p)
        val root = when {
            square == v -> s
            square == p.subtract(v).mod(p) -> s.multiply(SQRT_MINUS_ONE).mod(p)
            else -> return null
        }
        return if (root.multiply(root).mod(p) == v) root else null
    }

    /** `2^((p-1)/4) mod p`, a square root of -1 used by [sqrt]. */
    private val SQRT_MINUS_ONE: BigInteger =
        TWO.modPow(p.subtract(BigInteger.ONE).shiftRight(2), p)
}

/**
 * BoringSSL's SPAKE2 over edwards25519, as used by AOSP `adb` wireless pairing.
 *
 * Faithful to `crypto/curve25519/spake25519.cc` (google/boringssl, `main`):
 * - masking points are BoringSSL's own (see [BORINGSSL_M_ENCODED] / [BORINGSSL_N_ENCODED], taken from
 *   the precomputation-table comment in that file, which also prints both points' decimal x/y);
 * - the ephemeral scalar is `sc_reduce(64 random bytes) * 8`;
 * - the password scalar is `sc_reduce(SHA-512(password))`, then `l`, `2l`, `4l` are conditionally
 *   added to clear its low three bits ("password_scalar hack", enabled by default upstream);
 * - the shared key material is `SHA-512` over eight-byte-little-endian length-prefixed
 *   `my_name, their_name, my_msg, their_msg, dh_shared, password_hash` (role-dependent order),
 *   truncated to what the caller asks for.
 *
 * Names are used verbatim: AOSP passes `sizeof(kClientName)` / `sizeof(kServerName)`, so the
 * trailing NUL is part of the name and must be included by the caller.
 */
class Spake2Curve25519 internal constructor(
    val role: Spake2Curve25519Role,
    /** Our identity bytes, exactly as they go into the transcript (AOSP includes the trailing NUL). */
    val myName: ByteArray,
    /** The peer's identity bytes. */
    val theirName: ByteArray,
    private val privateScalar: BigInteger,
    private val passwordScalar: BigInteger,
    private val passwordHash: ByteArray
) {

    /** Our 32-byte SPAKE2 share (`pA` for Alice, `pB` for Bob). */
    val share: ByteArray = run {
        val basePoint = Ed25519.multiply(privateScalar, Ed25519.base)
        val mask = Ed25519.multiply(passwordScalar, ownMaskPoint())
        Ed25519.encode(Ed25519.add(basePoint, mask))
    }

    /**
     * Derives the 64-byte key material from the peer's share, or null when the share is not a valid
     * curve point (BoringSSL returns 0 / `SPAKE2_process_msg` fails in the same situation).
     */
    fun keyMaterial(peerShare: ByteArray): ByteArray? {
        if (peerShare.size != 32) return null
        val peerPoint = Ed25519.decode(peerShare) ?: return null
        val peerMask = Ed25519.multiply(passwordScalar, peerMaskPoint())
        val unmasked = Ed25519.subtract(peerPoint, peerMask)
        val shared = Ed25519.multiply(privateScalar, unmasked)

        val transcript = ByteArrayOutputStream()
        fun writeLengthPrefixed(chunk: ByteArray) {
            transcript.write(littleEndianLength(chunk.size))
            transcript.write(chunk)
        }
        if (role == Spake2Curve25519Role.ALICE) {
            writeLengthPrefixed(myName)
            writeLengthPrefixed(theirName)
            writeLengthPrefixed(share)
            writeLengthPrefixed(peerShare)
        } else {
            writeLengthPrefixed(theirName)
            writeLengthPrefixed(myName)
            writeLengthPrefixed(peerShare)
            writeLengthPrefixed(share)
        }
        writeLengthPrefixed(Ed25519.encode(shared))
        writeLengthPrefixed(passwordHash)
        return MessageDigest.getInstance("SHA-512").digest(transcript.toByteArray())
    }

    private fun ownMaskPoint(): Ed25519.Point =
        if (role == Spake2Curve25519Role.ALICE) MASK_M else MASK_N

    private fun peerMaskPoint(): Ed25519.Point =
        if (role == Spake2Curve25519Role.ALICE) MASK_N else MASK_M

    companion object {
        /**
         * BoringSSL `kSpakeMSmallPrecomp` point M, as printed in `spake25519.cc`
         * (`//   encoded: 5ada…`). Decimal x/y from the same comment:
         * x = 31406539342727633121250288103050113562375374900226415211311216773867585644232,
         * y = 21177308356423958466833845032658859666296341766942662650232962324899758529114.
         */
        const val BORINGSSL_M_ENCODED: String =
            "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"

        /**
         * BoringSSL `kSpakeNSmallPrecomp` point N (`//   encoded: 10e3…`). Decimal x/y from the same
         * comment: x = 49918732221787544735331783592030787422991506689877079631459872391322455579424,
         * y = 54629554431565467720832445949441049581317094546788069926228343916274969994000.
         */
        const val BORINGSSL_N_ENCODED: String =
            "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"

        /** Size of one SPAKE2 share / message; `SPAKE2_MAX_MSG_SIZE` is 32 in BoringSSL. */
        const val SHARE_SIZE: Int = 32

        /** `SPAKE2_MAX_KEY_SIZE` is 64 (SHA-512 output) in BoringSSL. */
        const val KEY_MATERIAL_SIZE: Int = 64

        private val MASK_M: Ed25519.Point by lazy {
            requireNotNull(Ed25519.decode(hexToBytes(BORINGSSL_M_ENCODED))) {
                "BoringSSL SPAKE2 masking point M does not decode"
            }
        }
        private val MASK_N: Ed25519.Point by lazy {
            requireNotNull(Ed25519.decode(hexToBytes(BORINGSSL_N_ENCODED))) {
                "BoringSSL SPAKE2 masking point N does not decode"
            }
        }

        /** `sizeof(kClientName)` in AOSP: the trailing NUL is part of the identity. */
        val ADB_CLIENT_NAME: ByteArray = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)

        /** `sizeof(kServerName)` in AOSP: the trailing NUL is part of the identity. */
        val ADB_SERVER_NAME: ByteArray = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

        /**
         * Starts one side of the real adb pairing exchange with the correct role-dependent identities.
         *
         * AOSP's `pairing_auth` passes `kClientName` as *my* name and `kServerName` as *their* name for
         * the client (Alice) and the opposite for the device (Bob); wiring that by hand is easy to get
         * wrong, so the production path uses this factory instead of the explicit-name overload.
         */
        fun startForAdb(
            role: Spake2Curve25519Role,
            password: ByteArray,
            random: SecureRandom = SecureRandom()
        ): Spake2Curve25519 = start(
            role = role,
            myName = if (role == Spake2Curve25519Role.ALICE) ADB_CLIENT_NAME else ADB_SERVER_NAME,
            theirName = if (role == Spake2Curve25519Role.ALICE) ADB_SERVER_NAME else ADB_CLIENT_NAME,
            password = password,
            random = random
        )

        /**
         * Masking point M (Alice). Exposed so tests can pin the decoded coordinates.
         */
        internal fun maskM(): Ed25519.Point = MASK_M

        /** Masking point N (Bob). */
        internal fun maskN(): Ed25519.Point = MASK_N

        /**
         * Starts a peer, deriving everything exactly as `SPAKE2_generate_msg` does.
         *
         * [password] must be the raw pairing-code bytes (AOSP feeds the six-digit code verbatim; no
         * PBKDF2, no trimming).
         */
        fun start(
            role: Spake2Curve25519Role,
            myName: ByteArray,
            theirName: ByteArray,
            password: ByteArray,
            random: SecureRandom = SecureRandom()
        ): Spake2Curve25519 {
            require(password.isNotEmpty()) { "SPAKE2 password must not be empty" }
            val randomBytes = ByteArray(64)
            random.nextBytes(randomBytes)
            val reduced = Ed25519.scalarReduce(randomBytes)
            // left_shift_3: the ephemeral scalar is a multiple of eight so the cofactor cancels.
            val privateScalar = Ed25519.fromLittleEndian(reduced).multiply(BigInteger.valueOf(8))
            // A 64-byte reduced scalar times eight still fits in 32 bytes (l < 2^253).
            require(privateScalar.bitLength() <= 255) { "ephemeral scalar overflow" }
            val passwordHash = MessageDigest.getInstance("SHA-512").digest(password)
            return Spake2Curve25519(
                role = role,
                myName = myName.copyOf(),
                theirName = theirName.copyOf(),
                privateScalar = privateScalar,
                passwordScalar = passwordScalarFromHash(passwordHash),
                passwordHash = passwordHash
            )
        }

        /**
         * Test seam: pins the ephemeral scalar and password so published/derived vectors can be
         * reproduced. Production code must use [start].
         */
        internal fun startWithSecrets(
            role: Spake2Curve25519Role,
            myName: ByteArray,
            theirName: ByteArray,
            privateScalar: BigInteger,
            password: ByteArray
        ): Spake2Curve25519 {
            require(privateScalar.signum() > 0) { "ephemeral scalar must be positive" }
            val passwordHash = MessageDigest.getInstance("SHA-512").digest(password)
            return Spake2Curve25519(
                role = role,
                myName = myName.copyOf(),
                theirName = theirName.copyOf(),
                privateScalar = privateScalar,
                passwordScalar = passwordScalarFromHash(passwordHash),
                passwordHash = passwordHash
            )
        }

        /**
         * `password_scalar` exactly as `SPAKE2_generate_msg` builds it: reduce `SHA-512(password)` mod
         * l, then conditionally add `l`, `2l`, `4l` so the scalar becomes a multiple of eight while
         * staying congruent mod l (the upstream fix for a copy-paste bug that leaked three bits).
         */
        internal fun passwordScalarFromHash(passwordHash: ByteArray): BigInteger {
            require(passwordHash.size == 64) { "password hash must be 64 bytes" }
            var scalar = Ed25519.fromLittleEndian(Ed25519.scalarReduce(passwordHash))
            var order = Ed25519.l
            for (bit in 0..2) {
                if (scalar.testBit(bit)) scalar += order
                order = order.shiftLeft(1)
            }
            check(scalar.testBit(0).not() && scalar.testBit(1).not() && scalar.testBit(2).not()) {
                "password scalar must be a multiple of eight"
            }
            return scalar
        }

        /** Eight-byte little-endian length prefix, as `update_with_length_prefix` writes it. */
        internal fun littleEndianLength(value: Int): ByteArray {
            require(value >= 0)
            val wide = value.toLong() and 0xFFFF_FFFFL
            return ByteArray(8) { index -> ((wide ushr (8 * index)) and 0xFF).toByte() }
        }
    }
}

/** Lowercase hex, for tests and stable diagnostics (never used for secrets in logs). */
internal fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

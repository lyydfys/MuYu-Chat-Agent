package com.muyuchat.feature.dsh.workspace.device

import java.math.BigInteger
import java.security.SecureRandom

/**
 * A point on NIST P-256 (secp256r1) in affine coordinates.
 *
 * Instances are only produced by [create]/[decode], so every point that exists is on the curve and
 * in range. [INFINITY] is the group identity and has no affine coordinates — it is deliberately not
 * encodable as SEC1 `0x04||X||Y`, and SEC1's single-byte `0x00` infinity encoding is rejected on
 * input, because a protocol message must never be allowed to carry the identity element.
 *
 * Why affine here and Jacobian internally: the arithmetic in [P256] runs in Jacobian coordinates
 * (no modular inversion per step) and converts once at the end, so this type stays a validated,
 * comparable value object instead of a mutable accumulator.
 */
class P256Point private constructor(
    val x: BigInteger?,
    val y: BigInteger?
) {

    /** True for the group identity. It can be compared and negated, but not encoded. */
    val isInfinity: Boolean get() = x == null || y == null

    /**
     * SEC1 uncompressed encoding: `0x04 || X(32) || Y(32)`, 65 bytes.
     *
     * This is the encoding adb's SPAKE2 messages would use for P-256 (and the one RFC 9382 test
     * vectors print). Throws for [INFINITY]; nothing in this module ever encodes it.
     */
    fun encodeUncompressed(): ByteArray {
        val px = requireNotNull(x) { "the point at infinity has no SEC1 encoding" }
        val py = requireNotNull(y) { "the point at infinity has no SEC1 encoding" }
        val out = ByteArray(UNCOMPRESSED_LENGTH)
        out[0] = 0x04
        P256.toFixed(px).copyInto(out, 1)
        P256.toFixed(py).copyInto(out, 1 + P256.FIELD_BYTES)
        return out
    }

    /** SEC1 compressed encoding: `0x02|0x03 || X(32)`, 33 bytes (prefix carries Y's parity). */
    fun encodeCompressed(): ByteArray {
        val px = requireNotNull(x) { "the point at infinity has no SEC1 encoding" }
        val py = requireNotNull(y) { "the point at infinity has no SEC1 encoding" }
        val out = ByteArray(COMPRESSED_LENGTH)
        out[0] = if (py.testBit(0)) 0x03 else 0x02
        P256.toFixed(px).copyInto(out, 1)
        return out
    }

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is P256Point -> false
        else -> x == other.x && y == other.y
    }

    override fun hashCode(): Int = 31 * (x?.hashCode() ?: 0) + (y?.hashCode() ?: 0)

    override fun toString(): String {
        val px = x ?: return "P256Point(infinity)"
        val py = y ?: return "P256Point(infinity)"
        return "P256Point(x=${px.toString(16)}, y=${py.toString(16)})"
    }

    companion object {
        /** The group identity. */
        val INFINITY = P256Point(null, null)

        const val UNCOMPRESSED_LENGTH: Int = 1 + 2 * P256.FIELD_BYTES
        const val COMPRESSED_LENGTH: Int = 1 + P256.FIELD_BYTES

        /**
         * Validates and returns the point `(x, y)`, or null when it is not a usable public point.
         *
         * Rejected: coordinates outside `[0, p)`, `(0, 0)` (not on the curve, but named explicitly
         * because it is the classic "empty" placeholder), and any pair that does not satisfy
         * `y^2 = x^3 - 3x + b`. Group-membership checking is mandatory for SPAKE2 shares — a peer
         * point that is not on the curve leaks the password through invalid-curve attacks.
         */
        fun create(x: BigInteger, y: BigInteger): P256Point? {
            if (x.signum() < 0 || x >= P256.p) return null
            if (y.signum() < 0 || y >= P256.p) return null
            if (x.signum() == 0 && y.signum() == 0) return null
            if (y.multiply(y).mod(P256.p) != P256.curveRhs(x)) return null
            return P256Point(x, y)
        }

        /** SEC1 decoding; null for a malformed encoding or a point that is not on the curve. */
        fun decode(encoded: ByteArray): P256Point? = P256.decodePoint(encoded)
    }
}

/**
 * P-256 group arithmetic on [BigInteger].
 *
 * Scope and honesty:
 * - The curve is NIST P-256 (`y^2 = x^3 - 3x + b`) with cofactor 1, so SPAKE2's `h * ...` step is
 *   the identity and no cofactor multiplication is needed.
 * - [multiply] is a fixed 256-iteration double-and-add ladder: it always performs the same number
 *   of point additions/doublings regardless of the scalar's bit length, but the *branch* on each
 *   bit is a real operation (add vs. not), so it is **not** constant-time against a local attacker
 *   with fine-grained timing or power measurements. RFC 9382 requires constant-time multiplication
 *   for production; this implementation is a correctness-complete, verifiable slice. Do not treat
 *   it as hardened against a co-located side-channel attacker.
 * - Scalars are reduced mod [n]; the only points this module ever multiplies are approved points.
 */
object P256 {

    /** Field prime. */
    val p: BigInteger = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)

    /** Curve coefficient `a = -3 mod p`. */
    val a: BigInteger = p.subtract(BigInteger.valueOf(3))

    /** Curve coefficient `b`. */
    val b: BigInteger = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    /** Group order (prime). */
    val n: BigInteger = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    /** Cofactor of P-256; 1, which is why SPAKE2's `h` factor is omitted. */
    const val COFACTOR: Int = 1

    /** Bytes per field element / coordinate. */
    const val FIELD_BYTES: Int = 32

    private val ZERO = BigInteger.ZERO
    private val ONE = BigInteger.ONE
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val FOUR = BigInteger.valueOf(4)
    private val EIGHT = BigInteger.valueOf(8)

    /** `(p + 1) / 4`; P-256 has `p = 3 mod 4`, so this is the square-root exponent. */
    private val SQRT_EXPONENT: BigInteger = p.add(ONE).shiftRight(2)

    /** Base point G, in the coordinates from SEC 2. */
    val g: P256Point = requireNotNull(
        P256Point.create(
            BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
            BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)
        )
    ) { "the P-256 base point failed self-validation" }

    /** `x^3 - 3x + b mod p`, i.e. the right-hand side of the curve equation. */
    fun curveRhs(x: BigInteger): BigInteger =
        x.multiply(x).multiply(x).add(a.multiply(x)).add(b).mod(p)

    /** Group addition. `P + (-P) = INFINITY`, and INFINITY is neutral. */
    fun add(left: P256Point, right: P256Point): P256Point =
        toAffine(addJacobian(fromAffine(left), fromAffine(right)))

    /** Group doubling. */
    fun double(point: P256Point): P256Point = toAffine(doubleJacobian(fromAffine(point)))

    /** `-P`, i.e. `(x, p - y)`; the identity negates to itself. */
    fun negate(point: P256Point): P256Point {
        if (point.isInfinity) return P256Point.INFINITY
        return P256Point.create(requireNotNull(point.x), p.subtract(requireNotNull(point.y)).mod(p))
            ?: error("negation left the curve")
    }

    /**
     * `k * P` with a fixed 256-step ladder. `k` is reduced mod [n]; `k = 0` (or `n`) yields
     * [P256Point.INFINITY]. See the constant-time caveat in this object's KDoc.
     */
    fun multiply(scalar: BigInteger, point: P256Point): P256Point {
        require(scalar.signum() >= 0) { "scalar must be non-negative" }
        if (point.isInfinity) return P256Point.INFINITY
        val reduced = scalar.mod(n)
        if (reduced.signum() == 0) return P256Point.INFINITY
        val base = fromAffine(point)
        var accumulator = Jacobian.INFINITY
        for (bit in (n.bitLength() - 1) downTo 0) {
            accumulator = doubleJacobian(accumulator)
            if (reduced.testBit(bit)) {
                accumulator = addJacobian(accumulator, base)
            }
        }
        return toAffine(accumulator)
    }

    /** Uniform scalar in `[1, n-1]` via rejection sampling (RFC 9382 §7 forbids biased scalars). */
    fun randomScalar(random: SecureRandom = SecureRandom()): BigInteger {
        while (true) {
            val candidate = BigInteger(n.bitLength(), random)
            if (candidate.signum() > 0 && candidate < n) return candidate
        }
    }

    /**
     * SEC1 point decoding.
     *
     * Accepts 65-byte uncompressed (`0x04`) and 33-byte compressed (`0x02`/`0x03`) forms; everything
     * else is rejected, including SEC1's `0x00` infinity encoding, wrong lengths, `x >= p` and
     * non-curve pairs. Compressed input is decompressed with the `(p + 1) / 4` square root and the
     * result re-validated, so a non-square right-hand side is rejected rather than guessed.
     */
    fun decodePoint(encoded: ByteArray): P256Point? {
        if (encoded.isEmpty()) return null
        val tag = encoded[0].toInt() and 0xFF
        return when {
            encoded.size == P256Point.UNCOMPRESSED_LENGTH && tag == 0x04 -> {
                val x = unsigned(encoded, 1, FIELD_BYTES)
                val y = unsigned(encoded, 1 + FIELD_BYTES, FIELD_BYTES)
                P256Point.create(x, y)
            }
            encoded.size == P256Point.COMPRESSED_LENGTH && (tag == 0x02 || tag == 0x03) -> {
                val x = unsigned(encoded, 1, FIELD_BYTES)
                if (x >= p) return null
                val root = sqrt(curveRhs(x)) ?: return null
                val odd = tag == 0x03
                val y = if (root.testBit(0) == odd) root else p.subtract(root).mod(p)
                P256Point.create(x, y)
            }
            else -> null
        }
    }

    /** Big-endian fixed-width encoding; a value that does not fit is a programming error. */
    internal fun toFixed(value: BigInteger, length: Int = FIELD_BYTES): ByteArray {
        val raw = value.toByteArray()
        val first = if (raw.size > 1 && raw[0] == 0.toByte()) 1 else 0
        val significant = raw.size - first
        require(significant <= length) { "value does not fit in $length bytes" }
        val out = ByteArray(length)
        raw.copyInto(out, length - significant, first, raw.size)
        return out
    }

    /** Unsigned big-endian read of [length] bytes at [offset]. */
    internal fun unsigned(bytes: ByteArray, offset: Int, length: Int): BigInteger {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "range out of bounds" }
        return BigInteger(1, bytes.copyOfRange(offset, offset + length))
    }

    /** `sqrt(value)` mod p, or null when `value` is not a quadratic residue. */
    internal fun sqrt(value: BigInteger): BigInteger? {
        val root = value.mod(p).modPow(SQRT_EXPONENT, p)
        return if (root.multiply(root).mod(p) == value.mod(p)) root else null
    }

    // --- Jacobian coordinates -------------------------------------------------------------------
    //
    // (X, Y, Z) represents the affine point (X/Z^2, Y/Z^3); Z = 0 is the identity. Formulas are the
    // standard "add-2007-bl" / "dbl-2001-b" ones for a = -3, which is what P-256 uses.

    private data class Jacobian(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        companion object {
            val INFINITY = Jacobian(ONE, ONE, ZERO)
        }
    }

    private fun fromAffine(point: P256Point): Jacobian {
        if (point.isInfinity) return Jacobian.INFINITY
        return Jacobian(requireNotNull(point.x), requireNotNull(point.y), ONE)
    }

    private fun toAffine(value: Jacobian): P256Point {
        if (value.z.signum() == 0) return P256Point.INFINITY
        val zInverse = value.z.modInverse(p)
        val zInverse2 = zInverse.multiply(zInverse).mod(p)
        val x = value.x.multiply(zInverse2).mod(p)
        val y = value.y.multiply(zInverse2).multiply(zInverse).mod(p)
        return P256Point.create(x, y) ?: error("Jacobian conversion produced a non-curve point")
    }

    private fun addJacobian(left: Jacobian, right: Jacobian): Jacobian {
        if (left.z.signum() == 0) return right
        if (right.z.signum() == 0) return left
        val z1z1 = left.z.multiply(left.z).mod(p)
        val z2z2 = right.z.multiply(right.z).mod(p)
        val u1 = left.x.multiply(z2z2).mod(p)
        val u2 = right.x.multiply(z1z1).mod(p)
        val s1 = left.y.multiply(right.z).multiply(z2z2).mod(p)
        val s2 = right.y.multiply(left.z).multiply(z1z1).mod(p)
        if (u1 == u2) {
            return if (s1 == s2) doubleJacobian(left) else Jacobian.INFINITY
        }
        val h = u2.subtract(u1).mod(p)
        val i = TWO.multiply(h).let { it.multiply(it) }.mod(p)
        val j = h.multiply(i).mod(p)
        val r = TWO.multiply(s2.subtract(s1)).mod(p)
        val v = u1.multiply(i).mod(p)
        val x3 = r.multiply(r).subtract(j).subtract(TWO.multiply(v)).mod(p)
        val y3 = r.multiply(v.subtract(x3)).subtract(TWO.multiply(s1).multiply(j)).mod(p)
        val z3 = left.z.add(right.z).let { it.multiply(it) }.subtract(z1z1).subtract(z2z2)
            .multiply(h).mod(p)
        return Jacobian(x3, y3, z3)
    }

    private fun doubleJacobian(value: Jacobian): Jacobian {
        if (value.z.signum() == 0 || value.y.signum() == 0) return Jacobian.INFINITY
        val delta = value.z.multiply(value.z).mod(p)
        val gamma = value.y.multiply(value.y).mod(p)
        val beta = value.x.multiply(gamma).mod(p)
        val alpha = THREE.multiply(value.x.subtract(delta)).multiply(value.x.add(delta)).mod(p)
        val x3 = alpha.multiply(alpha).subtract(EIGHT.multiply(beta)).mod(p)
        val z3 = value.y.add(value.z).let { it.multiply(it) }
            .subtract(gamma).subtract(delta).mod(p)
        val y3 = alpha.multiply(FOUR.multiply(beta).subtract(x3))
            .subtract(EIGHT.multiply(gamma).multiply(gamma)).mod(p)
        return Jacobian(x3, y3, z3)
    }
}

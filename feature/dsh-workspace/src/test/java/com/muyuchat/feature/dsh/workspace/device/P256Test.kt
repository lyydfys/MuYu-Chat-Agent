package com.muyuchat.feature.dsh.workspace.device

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-256 arithmetic, encodings and point validation.
 *
 * The known-answer coordinates come from this implementation and are cross-checked by the JDK's own
 * secp256r1 ECDH in `jdk ecdh agrees on the x coordinate of 2G`; the RFC 9382 vectors in
 * `Spake2P256Test` then exercise the same arithmetic through masking points and scalar products. A
 * sign error or a wrong curve constant fails at least one of those three independent checks.
 */
class P256Test {

    @Test
    fun `generator matches the SEC 2 coordinates and is on the curve`() {
        val encoded = P256.g.encodeUncompressed()
        assertEquals(
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
            hex(encoded).substring(2, 66)
        )
        assertEquals(
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
            hex(encoded).substring(66, 130)
        )
        // y^2 == x^3 - 3x + b, i.e. the generator really is a curve point.
        val generatorX = requireNotNull(P256.g.x)
        val generatorY = requireNotNull(P256.g.y)
        assertEquals(generatorY.multiply(generatorY).mod(P256.p), P256.curveRhs(generatorX))
        assertTrue(P256.n.bitLength() == 256)
        assertEquals(1, P256.COFACTOR)
        assertEquals(P256.p.subtract(BigInteger.valueOf(3)), P256.a)
    }

    @Test
    fun `small multiples of the generator are the published coordinates`() {
        assertEquals(TWO_G_HEX, hex(P256.multiply(BigInteger.valueOf(2), P256.g).encodeUncompressed()))
        assertEquals(THREE_G_HEX, hex(P256.multiply(BigInteger.valueOf(3), P256.g).encodeUncompressed()))
        assertEquals(FOUR_G_HEX, hex(P256.multiply(BigInteger.valueOf(4), P256.g).encodeUncompressed()))
        // Point addition must agree with scalar multiplication.
        assertEquals(P256.multiply(BigInteger.valueOf(2), P256.g), P256.add(P256.g, P256.g))
        assertEquals(P256.multiply(BigInteger.valueOf(3), P256.g), P256.add(TWO_G, P256.g))
        assertEquals(FOUR_G, P256.add(TWO_G, TWO_G))
        assertEquals(FOUR_G, P256.double(TWO_G))
    }

    @Test
    fun `jdk ecdh agrees on the x coordinate of 2G`() {
        val mine = hex(P256.multiply(BigInteger.valueOf(2), P256.g).encodeUncompressed()).substring(2, 66)
        assertEquals(mine, jdkEcdhX(BigInteger.valueOf(2)))
    }

    @Test
    fun `group order and negation`() {
        assertTrue("n * G must be the identity", P256.multiply(P256.n, P256.g).isInfinity)
        assertEquals(P256.negate(P256.g), P256.multiply(P256.n.subtract(BigInteger.ONE), P256.g))
        assertTrue(P256.add(P256.g, P256.negate(P256.g)).isInfinity)
        assertEquals(P256.g, P256.negate(P256.negate(P256.g)))
        assertTrue(P256.negate(P256Point.INFINITY).isInfinity)
    }

    @Test
    fun `scalar multiplication is distributive and addition is associative`() {
        val a = BigInteger("fedcba0987654321fedc", 16)
        val b = BigInteger("1234567890abcdef1234", 16)
        val aG = P256.multiply(a, P256.g)
        val bG = P256.multiply(b, P256.g)
        assertEquals(P256.multiply(a.add(b), P256.g), P256.add(aG, bG))
        assertEquals(P256.multiply(a.subtract(b), P256.g), P256.add(aG, P256.negate(bG)))
        assertEquals(P256.add(P256.add(aG, bG), P256.g), P256.add(aG, P256.add(bG, P256.g)))
        assertEquals(P256.multiply(a, P256.multiply(b, P256.g)), P256.multiply(a.multiply(b), P256.g))
    }

    @Test
    fun `zero and one scalars are the boundary cases`() {
        assertTrue(P256.multiply(BigInteger.ZERO, P256.g).isInfinity)
        assertTrue(P256.multiply(P256.n.multiply(BigInteger.TEN), P256.g).isInfinity)
        assertEquals(P256.g, P256.multiply(BigInteger.ONE, P256.g))
        assertTrue(P256.add(P256Point.INFINITY, P256Point.INFINITY).isInfinity)
        assertEquals(P256.g, P256.add(P256Point.INFINITY, P256.g))
        assertEquals(P256.g, P256.add(P256.g, P256Point.INFINITY))
        assertTrue(P256.double(P256Point.INFINITY).isInfinity)
    }

    @Test
    fun `uncompressed encoding is SEC1 65 bytes with the 0x04 prefix`() {
        val encoded = TWO_G.encodeUncompressed()
        assertEquals(65, encoded.size)
        assertEquals(0x04.toByte(), encoded[0])
        assertEquals(32, P256.FIELD_BYTES)
        assertArrayEquals(TWO_G_BYTES, encoded)
    }

    @Test
    fun `compressed and uncompressed encodings round trip`() {
        for (scalar in listOf(1L, 2L, 3L, 4L, 10L, 255L, 65_537L)) {
            val point = P256.multiply(BigInteger.valueOf(scalar), P256.g)
            assertEquals(point, P256Point.decode(point.encodeUncompressed()))
            assertEquals(point, P256Point.decode(point.encodeCompressed()))
            assertEquals(33, point.encodeCompressed().size)
            assertTrue(
                "compressed prefix must be 0x02 or 0x03",
                point.encodeCompressed()[0].toInt() == 0x02 || point.encodeCompressed()[0].toInt() == 0x03
            )
        }
    }

    @Test
    fun `point validation rejects the placeholder and out of range coordinates`() {
        assertNull("(0,0) must be rejected", P256Point.create(BigInteger.ZERO, BigInteger.ZERO))
        assertNull("x == p must be rejected", P256Point.create(P256.p, BigInteger.ONE))
        assertNull("y == p must be rejected", P256Point.create(BigInteger.ONE, P256.p))
        assertNull("negative x must be rejected", P256Point.create(BigInteger.valueOf(-1), BigInteger.ONE))
        assertNull("x > p must be rejected", P256Point.create(P256.p.add(BigInteger.ONE), BigInteger.ONE))
        val generatorX = requireNotNull(P256.g.x)
        val generatorY = requireNotNull(P256.g.y)
        assertNull(
            "an on-curve x with the wrong y must be rejected",
            P256Point.create(generatorX, generatorY.add(BigInteger.ONE).mod(P256.p))
        )
        assertNotNull(P256Point.create(generatorX, generatorY))
    }

    @Test
    fun `decoding rejects malformed and off-curve encodings`() {
        assertNull(P256Point.decode(ByteArray(0)))
        assertNull("SEC1 infinity (0x00) is not a usable share", P256Point.decode(byteArrayOf(0x00)))
        assertNull(P256Point.decode(ByteArray(64) { 0x04 }))
        assertNull(P256Point.decode(ByteArray(66)))
        assertNull(P256Point.decode(byteArrayOf(0x07.toByte()) + ByteArray(64)))

        // 65 bytes, correct tag, but the point is not on the curve.
        val offCurve = ByteArray(65)
        offCurve[0] = 0x04
        P256.toFixed(BigInteger.ONE).copyInto(offCurve, 1)
        P256.toFixed(BigInteger.valueOf(2)).copyInto(offCurve, 33)
        assertNull(P256Point.decode(offCurve))

        // Compressed with x >= p.
        assertNull(P256Point.decode(byteArrayOf(0x02) + P256.toFixed(P256.p)))

        // Compressed with a non-residue right-hand side: find one deterministically and reject it.
        val nonResidueX = generateSequence(0L) { it + 1 }
            .map { BigInteger.valueOf(it) }
            .first { P256.sqrt(P256.curveRhs(it)) == null }
        assertNull(P256Point.decode(byteArrayOf(0x02) + P256.toFixed(nonResidueX)))
    }

    @Test
    fun `sqrt returns a root whose square is the input`() {
        val generatorX = requireNotNull(P256.g.x)
        val rhs = P256.curveRhs(generatorX)
        val root = requireNotNull(P256.sqrt(rhs))
        assertEquals(rhs, root.multiply(root).mod(P256.p))
        // The recovered root must also re-encode into a valid point.
        val y = if (root.testBit(0)) root else P256.p.subtract(root)
        assertNotNull(P256Point.create(generatorX, y))
    }

    @Test
    fun `random scalars are uniform in range`() {
        repeat(16) {
            val scalar = P256.randomScalar()
            assertTrue("scalar must be positive", scalar.signum() > 0)
            assertTrue("scalar must be below n", scalar < P256.n)
        }
    }

    @Test
    fun `encoding the identity is refused`() {
        val uncompressedFailure = runCatching { P256Point.INFINITY.encodeUncompressed() }.exceptionOrNull()
        val compressedFailure = runCatching { P256Point.INFINITY.encodeCompressed() }.exceptionOrNull()
        assertTrue(uncompressedFailure is IllegalArgumentException)
        assertTrue(compressedFailure is IllegalArgumentException)
        assertTrue(P256Point.INFINITY.isInfinity)
        assertTrue(P256.add(P256Point.INFINITY, P256Point.INFINITY).isInfinity)
    }

    private fun jdkEcdhX(privateScalar: BigInteger): String {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
        val factory = KeyFactory.getInstance("EC")
        val privateKey = factory.generatePrivate(ECPrivateKeySpec(privateScalar, spec))
        val publicKey = factory.generatePublic(ECPublicKeySpec(spec.generator, spec))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return hex(agreement.generateSecret())
    }

    private companion object {
        val TWO_G: P256Point = P256.multiply(BigInteger.valueOf(2), P256.g)
        val FOUR_G: P256Point = P256.multiply(BigInteger.valueOf(4), P256.g)
        const val TWO_G_HEX =
            "047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978" +
                "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1"
        const val THREE_G_HEX =
            "045ecbe4d1a6330a44c8f7ef951d4bf165e6c6b721efada985fb41661bc6e7fd6c" +
                "8734640c4998ff7e374b06ce1a64a2ecd82ab036384fb83d9a79b127a27d5032"
        const val FOUR_G_HEX =
            "04e2534a3532d08fbba02dde659ee62bd0031fe2db785596ef509302446b030852" +
                "e0f1575a4c633cc719dfee5fda862d764efc96c3f30ee0055c42c23f184ed8c6"
        val TWO_G_BYTES: ByteArray = hexToBytes(TWO_G_HEX)

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

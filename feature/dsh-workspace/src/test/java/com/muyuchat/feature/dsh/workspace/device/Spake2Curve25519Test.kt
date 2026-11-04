package com.muyuchat.feature.dsh.workspace.device

import java.math.BigInteger
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BoringSSL SPAKE2 over edwards25519.
 *
 * The known answers come from the source itself: `spake25519.cc` prints the decimal x/y coordinates of
 * its two masking points next to their encodings, and RFC 8032 pins Ed25519 basepoint
 * multiplication (which is the same arithmetic the protocol uses). `sc_reduce` is checked against the
 * group order `l` directly.
 */
class Spake2Curve25519Test {

    @Test
    fun `masking points decode to the coordinates printed in spake25519 cc`() {
        val m = Spake2Curve25519.maskM()
        val n = Spake2Curve25519.maskN()
        assertEquals(
            "31406539342727633121250288103050113562375374900226415211311216773867585644232",
            m.x.toString()
        )
        assertEquals(
            "21177308356423958466833845032658859666296341766942662650232962324899758529114",
            m.y.toString()
        )
        assertEquals(
            "49918732221787544735331783592030787422991506689877079631459872391322455579424",
            n.x.toString()
        )
        assertEquals(
            "54629554431565467720832445949441049581317094546788069926228343916274969994000",
            n.y.toString()
        )
        // The encodings are canonical, so re-encoding round-trips.
        assertEquals(Spake2Curve25519.BORINGSSL_M_ENCODED, toHex(Ed25519.encode(m)))
        assertEquals(Spake2Curve25519.BORINGSSL_N_ENCODED, toHex(Ed25519.encode(n)))
    }

    @Test
    fun `ed25519 basepoint multiplication matches rfc 8032 vector 1`() {
        // RFC 8032 7.1 TEST 1: seed -> clamped scalar -> public key.
        val seed = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val hash = MessageDigest.getInstance("SHA-512").digest(seed)
        val scalarBytes = hash.copyOfRange(0, 32)
        scalarBytes[0] = (scalarBytes[0].toInt() and 248).toByte()
        scalarBytes[31] = (scalarBytes[31].toInt() and 127 or 64).toByte()
        val scalar = Ed25519.fromLittleEndian(scalarBytes)
        val publicKey = Ed25519.encode(Ed25519.multiply(scalar, Ed25519.base))
        assertEquals(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            toHex(publicKey)
        )
    }

    @Test
    fun `group order arithmetic and encoding round trips`() {
        // l * B is the identity, which encodes as 01 00 ... 00.
        val orderMultiple = Ed25519.multiply(Ed25519.l, Ed25519.base)
        assertTrue(Ed25519.isIdentity(orderMultiple))
        assertArrayEquals(Ed25519.encode(Ed25519.identity), byteArrayOf(1) + ByteArray(31))
        // (l-1) * B == -B.
        assertArrayEquals(
            Ed25519.encode(Ed25519.negate(Ed25519.base)),
            Ed25519.encode(Ed25519.multiply(Ed25519.l.subtract(BigInteger.ONE), Ed25519.base))
        )
        // Decode/encode round trip for several multiples.
        for (k in listOf(1L, 2L, 3L, 17L, 255L, 65_537L)) {
            val point = Ed25519.multiply(BigInteger.valueOf(k), Ed25519.base)
            assertEquals(point, Ed25519.decode(Ed25519.encode(point)))
        }
        // Addition is associative and consistent with scalar addition.
        val a = BigInteger("fedcba0987654321", 16)
        val b = BigInteger("1234567890abcdef", 16)
        val left = Ed25519.add(
            Ed25519.multiply(a, Ed25519.base),
            Ed25519.multiply(b, Ed25519.base)
        )
        assertEquals(
            Ed25519.multiply(a.add(b), Ed25519.base),
            left
        )
        assertEquals(
            Ed25519.multiply(a.subtract(b), Ed25519.base),
            Ed25519.subtract(Ed25519.multiply(a, Ed25519.base), Ed25519.multiply(b, Ed25519.base))
        )
    }

    @Test
    fun `scalar reduce implements mod l on sixty four bytes`() {
        assertEquals(
            "0".repeat(64),
            toHex(Ed25519.scalarReduce(littleEndian(Ed25519.l, 64)))
        )
        // l - 1 must be preserved.
        val lMinusOne = littleEndian(Ed25519.l.subtract(BigInteger.ONE), 64)
        assertEquals(toHex(littleEndian(Ed25519.l.subtract(BigInteger.ONE), 32)), toHex(Ed25519.scalarReduce(lMinusOne)))
        // A large value is reduced exactly: (l * 3 + 5) mod l == 5.
        val enormous = littleEndian(Ed25519.l.multiply(BigInteger.valueOf(3)).add(BigInteger.valueOf(5)), 64)
        assertEquals(toHex(littleEndian(BigInteger.valueOf(5), 32)), toHex(Ed25519.scalarReduce(enormous)))
        // All-ones (2^512-1) is a valid input and yields something below l.
        val reduced = Ed25519.scalarReduce(ByteArray(64) { 0xFF.toByte() })
        assertEquals(32, reduced.size)
        assertTrue(Ed25519.fromLittleEndian(reduced) < Ed25519.l)
    }

    @Test
    fun `password scalar is a multiple of eight and congruent to the hash modulo l`() {
        for (code in listOf("000000", "123456", "999999", "12")) {
            val hash = MessageDigest.getInstance("SHA-512").digest(code.toByteArray())
            val scalar = Spake2Curve25519.passwordScalarFromHash(hash)
            assertTrue("scalar must be a multiple of eight", scalar.testBit(0).not())
            assertTrue(scalar.testBit(1).not())
            assertTrue(scalar.testBit(2).not())
            // Congruent to SHA-512(password) mod l, i.e. the hack only clears bits.
            val reduced = Ed25519.fromLittleEndian(Ed25519.scalarReduce(hash))
            assertEquals(0, scalar.mod(Ed25519.l).compareTo(reduced))
            assertTrue("scalar stays below 8l", scalar < Ed25519.l.multiply(BigInteger.valueOf(8)))
        }
    }

    @Test
    fun `six digit code with leading zeros is used as raw bytes`() {
        val withLeadingZero = Spake2Curve25519.start(
            role = Spake2Curve25519Role.ALICE,
            myName = CLIENT_NAME,
            theirName = SERVER_NAME,
            password = "012345".toByteArray()
        )
        val withoutLeadingZero = Spake2Curve25519.start(
            role = Spake2Curve25519Role.ALICE,
            myName = CLIENT_NAME,
            theirName = SERVER_NAME,
            password = "12345".toByteArray()
        )
        assertNotEquals(toHex(withLeadingZero.share), toHex(withoutLeadingZero.share))
        assertEquals(32, withLeadingZero.share.size)
    }

    @Test
    fun `both peers derive the same sixty four byte key material`() {
        val alice = Spake2Curve25519.startForAdb(Spake2Curve25519Role.ALICE, CODE.toByteArray())
        val bob = Spake2Curve25519.startForAdb(Spake2Curve25519Role.BOB, CODE.toByteArray())

        val aliceKey = requireNotNull(alice.keyMaterial(bob.share))
        val bobKey = requireNotNull(bob.keyMaterial(alice.share))

        assertEquals(64, aliceKey.size)
        assertArrayEquals(aliceKey, bobKey)
        // SPAKE2's guarantee: the peers' shares differ even with the same password.
        assertNotEquals(toHex(alice.share), toHex(bob.share))
    }

    @Test
    fun `wrong code produces different keys`() {
        val alice = Spake2Curve25519.startForAdb(Spake2Curve25519Role.ALICE, "123456".toByteArray())
        val bob = Spake2Curve25519.startForAdb(Spake2Curve25519Role.BOB, "123457".toByteArray())
        val aliceKey = requireNotNull(alice.keyMaterial(bob.share))
        val bobKey = requireNotNull(bob.keyMaterial(alice.share))
        assertNotEquals(toHex(aliceKey), toHex(bobKey))
    }

    @Test
    fun `names take part in the key and the trailing NUL matters`() {
        val withNul = Spake2Curve25519.start(
            Spake2Curve25519Role.ALICE, CLIENT_NAME, SERVER_NAME, CODE.toByteArray()
        )
        val withoutNul = Spake2Curve25519.start(
            Spake2Curve25519Role.ALICE, CLIENT_NAME.copyOf(CLIENT_NAME.size - 1), SERVER_NAME, CODE.toByteArray()
        )
        val bob = Spake2Curve25519.startForAdb(Spake2Curve25519Role.BOB, CODE.toByteArray())
        val keyWithNul = requireNotNull(withNul.keyMaterial(bob.share))
        val keyWithoutNul = requireNotNull(withoutNul.keyMaterial(bob.share))
        assertNotEquals("AOSP passes sizeof() including the NUL", toHex(keyWithNul), toHex(keyWithoutNul))
        assertEquals(16, CLIENT_NAME.size)
        assertEquals(16, SERVER_NAME.size)
    }

    @Test
    fun `invalid peer shares are rejected`() {
        val alice = Spake2Curve25519.start(Spake2Curve25519Role.ALICE, CLIENT_NAME, SERVER_NAME, CODE.toByteArray())
        assertNull("empty", alice.keyMaterial(ByteArray(0)))
        assertNull("short", alice.keyMaterial(ByteArray(31)))
        assertNull("long", alice.keyMaterial(ByteArray(33)))
        // Find a `y` whose right-hand side is not a square: such a value is not a curve point.
        val nonPointY = generateSequence(0L) { it + 1 }
            .map { BigInteger.valueOf(it) }
            .first { Ed25519.decode(Ed25519.littleEndian(it, 32)) == null }
        assertNull("y=$nonPointY is not on the curve", alice.keyMaterial(Ed25519.littleEndian(nonPointY, 32)))
        // Non-canonical y >= p must be rejected even though ref10 would accept some of these.
        val nonCanonical = littleEndian(Ed25519.p, 32)
        assertNull(alice.keyMaterial(nonCanonical))
    }

    @Test
    fun `role ordering is part of the transcript`() {
        // Same secrets on both sides but with the roles swapped: keys must not match, because the
        // transcript order and the masking point both depend on the role.
        val alice = Spake2Curve25519.startWithSecrets(
            Spake2Curve25519Role.ALICE,
            myName = CLIENT_NAME,
            theirName = SERVER_NAME,
            privateScalar = BigInteger.valueOf(8),
            password = CODE.toByteArray()
        )
        val bob = Spake2Curve25519.startWithSecrets(
            Spake2Curve25519Role.BOB,
            myName = CLIENT_NAME,
            theirName = SERVER_NAME,
            privateScalar = BigInteger.valueOf(8),
            password = CODE.toByteArray()
        )
        val aliceAsBob = requireNotNull(alice.keyMaterial(bob.share))
        val bobAsAlice = requireNotNull(bob.keyMaterial(alice.share))
        assertNotEquals(toHex(aliceAsBob), toHex(bobAsAlice))
    }

    @Test
    fun `deterministic secrets produce stable shares and keys`() {
        fun run(): Pair<String, String> {
            val alice = Spake2Curve25519.startWithSecrets(
                Spake2Curve25519Role.ALICE, CLIENT_NAME, SERVER_NAME, BigInteger.valueOf(24), CODE.toByteArray()
            )
            val bob = Spake2Curve25519.startWithSecrets(
                Spake2Curve25519Role.BOB, CLIENT_NAME, SERVER_NAME, BigInteger.valueOf(32), CODE.toByteArray()
            )
            return toHex(alice.share) to toHex(requireNotNull(alice.keyMaterial(bob.share)))
        }
        val first = run()
        val second = run()
        assertEquals(first.first, second.first)
        assertEquals(first.second, second.second)
        assertNotNull(first.second)
    }

    @Test
    fun `empty password is refused`() {
        val failure = runCatching {
            Spake2Curve25519.start(Spake2Curve25519Role.ALICE, CLIENT_NAME, SERVER_NAME, ByteArray(0))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun littleEndian(value: BigInteger, length: Int): ByteArray = Ed25519.littleEndian(value, length)

    private companion object {
        const val CODE = "314159"

        /** AOSP passes `sizeof("adb pair client")`, i.e. including the trailing NUL. */
        val CLIENT_NAME = "adb pair client\u0000".toByteArray()

        /** AOSP passes `sizeof("adb pair server")`. */
        val SERVER_NAME = "adb pair server\u0000".toByteArray()
    }
}

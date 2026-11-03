package com.muyuchat.feature.dsh.workspace.device

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The app's adb identity: signature compatibility, adb's `RSAPublicKey` wire format and key reuse.
 */
class AdbIdentityTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `a token signature verifies with the persisted key`() {
        val keyFile = keyFile("verify")
        val identity = AdbIdentity.loadOrCreate(keyFile)
        val token = ByteArray(20) { (it * 3 + 1).toByte() }

        val signature = identity.signToken(token)

        assertEquals(256, signature.size)
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(publicKeyOf(keyFile))
        verifier.update(token)
        assertTrue("adb must be able to verify this signature", verifier.verify(signature))
    }

    @Test
    fun `a different token produces a different signature`() {
        val identity = AdbIdentity.loadOrCreate(keyFile("distinct"))
        val first = identity.signToken(ByteArray(20) { 1 })
        val second = identity.signToken(ByteArray(20) { 2 })

        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `the wire format is the adb RSAPublicKey structure`() {
        val keyFile = keyFile("wire")
        val identity = AdbIdentity.loadOrCreate(keyFile)
        val wire = identity.publicKeyWireFormat()
        val publicKey = publicKeyOf(keyFile)

        // uint32 len | uint32 n0inv | n[256] | rr[256]
        assertEquals(4 + 4 + 256 + 256, wire.size)
        assertEquals(256, readIntLe(wire, 0))
        assertEquals(publicKey.modulus, readLittleEndian(wire, offset = 8, size = 256))
        val r = BigInteger.ONE.shiftLeft(2048)
        assertEquals(
            r.multiply(r).mod(publicKey.modulus),
            readLittleEndian(wire, offset = 8 + 256, size = 256)
        )
    }

    @Test
    fun `n0inv is minus the inverse of the modulus modulo two to the thirty two`() {
        val keyFile = keyFile("n0inv")
        val identity = AdbIdentity.loadOrCreate(keyFile)
        val wire = identity.publicKeyWireFormat()
        val modulus = publicKeyOf(keyFile).modulus
        val twoPow32 = BigInteger.ONE.shiftLeft(32)
        val n0 = modulus.mod(twoPow32)
        val n0inv = readIntLe(wire, 4).toLong() and 0xFFFFFFFFL

        val product = n0.multiply(BigInteger.valueOf(n0inv)).mod(twoPow32)

        assertEquals(twoPow32.subtract(BigInteger.ONE), product)
    }

    @Test
    fun `the auth payload is base64 of the wire format plus a NUL`() {
        val identity = AdbIdentity.loadOrCreate(keyFile("payload"))
        val payload = identity.publicKeyPayload()

        assertEquals(0, payload.last().toInt())
        val decoded = Base64.getDecoder().decode(payload.copyOf(payload.size - 1))
        assertArrayEquals(identity.publicKeyWireFormat(), decoded)
    }

    @Test
    fun `the key is generated once and reused`() {
        val keyFile = keyFile("reuse")

        val first = AdbIdentity.loadOrCreate(keyFile).publicKeyWireFormat()
        val firstLength = keyFile.length()
        val second = AdbIdentity.loadOrCreate(keyFile).publicKeyWireFormat()

        assertTrue("the PKCS#8 key file must exist", keyFile.isFile)
        assertTrue("the key file must not be empty", firstLength > 0)
        assertArrayEquals("reloading must not regenerate the key", first, second)
        assertEquals(firstLength, keyFile.length())
    }

    @Test
    fun `a corrupt key file is replaced by a working identity`() {
        val keyFile = keyFile("corrupt")
        keyFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        val identity = AdbIdentity.loadOrCreate(keyFile)

        assertEquals(520, identity.publicKeyWireFormat().size)
        val reloaded = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        assertTrue(reloaded is RSAPrivateCrtKey)
    }

    @Test
    fun `signing still works after a reload`() {
        val keyFile = keyFile("reload-sign")
        AdbIdentity.loadOrCreate(keyFile)
        val reloaded = AdbIdentity.loadOrCreate(keyFile)
        val token = ByteArray(20) { 9 }

        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(publicKeyOf(keyFile))
        verifier.update(token)

        assertTrue(verifier.verify(reloaded.signToken(token)))
    }

    private fun keyFile(name: String): File =
        File(temporaryFolder.newFolder(name), "adbkey.pkcs8")

    private fun publicKeyOf(keyFile: File): RSAPublicKey {
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val crt = privateKey as RSAPrivateCrtKey
        return KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent)) as RSAPublicKey
    }

    private fun readLittleEndian(bytes: ByteArray, offset: Int, size: Int): BigInteger {
        val bigEndian = ByteArray(size)
        for (index in 0 until size) {
            bigEndian[size - 1 - index] = bytes[offset + index]
        }
        return BigInteger(1, bigEndian)
    }
}

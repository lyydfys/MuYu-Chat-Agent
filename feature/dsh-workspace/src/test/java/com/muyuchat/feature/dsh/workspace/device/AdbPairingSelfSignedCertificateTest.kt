package com.muyuchat.feature.dsh.workspace.device

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-signed pairing certificate.
 *
 * The DER encoder is hand-written (bctls 1.86 no longer ships a PKIX builder in the jars the app
 * depends on), so it is checked the only way that matters: the platform's own X.509 parser must accept
 * it, the public key must match the generated key pair, and the self-signature must verify.
 */
class AdbPairingSelfSignedCertificateTest {

    @Test
    fun `generated certificate parses and carries the generated public key`() {
        val identity = AdbPairingSelfSignedCertificate.generate()
        val certificate = parse(identity.certificateDer)

        assertEquals("X.509", certificate.type)
        assertTrue(
            "subject should name this module",
            certificate.subjectX500Principal.name.contains("mca-adb-pairing")
        )
        assertEquals(
            "the certificate is self-signed",
            certificate.subjectX500Principal,
            certificate.issuerX500Principal
        )
        val certificateKey = certificate.publicKey as ECPublicKey
        val generatedKey = identity.keyPair.public as ECPublicKey
        assertArrayEquals(certificateKey.encoded, generatedKey.encoded)
        assertEquals(256, certificateKey.params.order.bitLength())
    }

    @Test
    fun `the self signature verifies`() {
        val identity = AdbPairingSelfSignedCertificate.generate()
        val certificate = parse(identity.certificateDer)

        // X509Certificate.verify(publicKey) checks the certificate signature; for a self-signed
        // certificate this proves the hand-written DER signature blob is correct.
        certificate.verify(certificate.publicKey)
    }

    @Test
    fun `each pairing gets a fresh identity`() {
        val first = AdbPairingSelfSignedCertificate.generate()
        val second = AdbPairingSelfSignedCertificate.generate()
        assertNotEquals(
            first.certificateDer.joinToString("") { "%02x".format(it) },
            second.certificateDer.joinToString("") { "%02x".format(it) }
        )
        assertNotEquals(
            (first.keyPair.public as ECPublicKey).w.affineX,
            (second.keyPair.public as ECPublicKey).w.affineX
        )
    }

    @Test
    fun `validity window is short lived`() {
        val identity = AdbPairingSelfSignedCertificate.generate(validityDays = 2)
        val certificate = parse(identity.certificateDer)
        val windowMillis = certificate.notAfter.time - certificate.notBefore.time
        assertEquals(2L, windowMillis / 86_400_000L)
        assertTrue(certificate.notBefore.time <= System.currentTimeMillis() + 5_000)
    }

    private fun parse(der: ByteArray): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }
}

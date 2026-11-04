package com.muyuchat.feature.dsh.workspace.device

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.Certificate as BcCertificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsContext
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate

/**
 * A throw-away self-signed X.509 identity for the pairing handshake.
 *
 * adb's pairing TLS is *not* about authenticating a certificate: both sides accept any peer
 * certificate (`SetCertVerifyCallback([] { return 1; })` in `pairing_connection.cpp`) and the actual
 * authentication is the SPAKE2 code bound to the TLS exporter material. That is why generating a fresh
 * self-signed certificate per pairing is correct here — and why this class must not be reused as a
 * general-purpose identity.
 *
 * ## Why a hand-written DER encoder
 *
 * `bctls` depends on `bcprov`/`bcutil` (which the app now ships) but the X.509 builder lives in
 * `bcpkix`, which was deliberately not added. The certificate this protocol needs is a fixed, tiny
 * shape (v3, one EC P-256 key, no extensions), so building ~400 bytes of DER locally keeps the
 * dependency list small. The encoding is checked by `CertificateFactory` in the unit tests.
 */
class AdbPairingSelfSignedCertificate private constructor(
    /** DER-encoded X.509 certificate. */
    val certificateDer: ByteArray,
    /** Key pair whose public half is inside [certificateDer]. */
    val keyPair: KeyPair
) {

    /** BC's `Certificate` wrapper, ready for `DefaultTlsClient.getAuthentication()`. */
    fun toBcCertificate(crypto: BcTlsCrypto): BcCertificate =
        BcCertificate(arrayOf(BcTlsCertificate(crypto, certificateDer)))

    /** BC's key parameter for signing the client `CertificateVerify`. */
    fun privateKeyParameter(): AsymmetricKeyParameter =
        PrivateKeyFactory.createKey(keyPair.private.encoded)

    companion object {
        private const val SUBJECT = "CN=mca-adb-pairing,O=MCA"

        /** Generates a fresh EC P-256 identity, valid for one pairing attempt. */
        fun generate(validityDays: Long = 1): AdbPairingSelfSignedCertificate {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair = generator.generateKeyPair()
            return AdbPairingSelfSignedCertificate(encodeCertificate(keyPair, validityDays), keyPair)
        }

        /** Test seam: builds the certificate around a supplied key pair. */
        internal fun fromKeyPair(keyPair: KeyPair, validityDays: Long = 1): AdbPairingSelfSignedCertificate =
            AdbPairingSelfSignedCertificate(encodeCertificate(keyPair, validityDays), keyPair)

        private fun encodeCertificate(keyPair: KeyPair, validityDays: Long): ByteArray {
            val now = Date()
            val notBefore = utcTime(now)
            val notAfter = utcTime(Date(now.time + validityDays * 86_400_000L))
            val name = der(0x30, listOf(
                der(0x31, listOf(
                    der(0x30, listOf(
                        derOid("2.5.4.3"), // CN
                        der(0x0C, "mca-adb-pairing".toByteArray(Charsets.UTF_8))
                    ))
                )),
                der(0x31, listOf(
                    der(0x30, listOf(
                        derOid("2.5.4.10"), // O
                        der(0x0C, "MCA".toByteArray(Charsets.UTF_8))
                    ))
                ))
            ))
            val signatureAlgorithm = der(0x30, listOf(derOid(ECDSA_WITH_SHA256)))
            val spki = subjectPublicKeyInfo(keyPair)
            val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)

            val tbs = der(0x30, listOf(
                der(0xA0, listOf(derInteger(BigInteger.valueOf(2)))), // v3
                derInteger(serial),
                signatureAlgorithm,
                name,
                der(0x30, listOf(der(0x17, notBefore), der(0x17, notAfter))),
                name,
                spki
            ))

            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(keyPair.private)
            signer.update(tbs)
            val signature = signer.sign()
            val signatureBits = der(0x03, byteArrayOf(0).plus(signature))
            require(SUBJECT.isNotBlank())
            return der(0x30, listOf(tbs, signatureAlgorithm, signatureBits))
        }

        private fun subjectPublicKeyInfo(keyPair: KeyPair): ByteArray {
            val publicKey = keyPair.public as java.security.interfaces.ECPublicKey
            val point = byteArrayOf(0x04) +
                fixed(publicKey.w.affineX, 32) +
                fixed(publicKey.w.affineY, 32)
            val algorithm = der(0x30, listOf(
                derOid("1.2.840.10045.2.1"), // id-ecPublicKey
                derOid("1.2.840.10045.3.1.7") // prime256v1
            ))
            return der(0x30, listOf(algorithm, der(0x03, byteArrayOf(0).plus(point))))
        }

        /** ECDSA-with-SHA256, `1.2.840.10045.4.3.2`. */
        private const val ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"

        private fun utcTime(date: Date): ByteArray {
            val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(date).toByteArray(Charsets.US_ASCII)
        }

        private fun der(tag: Int, content: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + derLength(content.size) + content

        private fun der(tag: Int, parts: List<ByteArray>): ByteArray = der(tag, parts.concat())

        private fun List<ByteArray>.concat(): ByteArray {
            val out = ByteArrayOutputStream()
            forEach { out.write(it) }
            return out.toByteArray()
        }

        private fun derLength(length: Int): ByteArray = when {
            length < 0x80 -> byteArrayOf(length.toByte())
            else -> {
                val bytes = mutableListOf<Byte>()
                var remaining = length
                while (remaining > 0) {
                    bytes.add(0, (remaining and 0xFF).toByte())
                    remaining = remaining ushr 8
                }
                byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
            }
        }

        private fun derInteger(value: BigInteger): ByteArray =
            der(0x02, fixed(value, ((value.bitLength() + 7) / 8).coerceAtLeast(1)))

        private fun derOid(oid: String): ByteArray {
            val parts = oid.split('.').map { it.toInt() }
            require(parts.size >= 2) { "OID needs at least two arcs" }
            val out = ByteArrayOutputStream()
            out.write(parts[0] * 40 + parts[1])
            for (index in 2 until parts.size) {
                var arc = parts[index]
                val stack = mutableListOf<Byte>()
                stack.add((arc and 0x7F).toByte())
                arc = arc ushr 7
                while (arc > 0) {
                    stack.add(0, ((arc and 0x7F) or 0x80).toByte())
                    arc = arc ushr 7
                }
                stack.forEach { out.write(it.toInt()) }
            }
            return der(0x06, out.toByteArray())
        }

        private fun fixed(value: BigInteger, length: Int): ByteArray {
            val raw = value.toByteArray()
            val first = if (raw.size > 1 && raw[0] == 0.toByte()) 1 else 0
            val significant = raw.size - first
            require(significant <= length) { "value does not fit in $length bytes" }
            val out = ByteArray(length)
            raw.copyInto(out, length - significant, first, raw.size)
            return out
        }
    }
}

/**
 * TLS client for the adb pairing port.
 *
 * Behaviour mirrors AOSP `pairing_connection.cpp`:
 * - TLS 1.3 with a self-signed client certificate;
 * - **any** server certificate is accepted (see [AdbPairingSelfSignedCertificate] for why);
 * - `exportKeyingMaterial("", null, 64)` after the handshake.
 *
 * The exporter call is the one piece whose exact label/context is assumed (AOSP passes
 * `nullptr, 0, 0`), so [AdbPairingWireAssumptions.exporterLabel] must be flipped only after a device
 * confirms it.
 */
class AdbPairingTls(
    private val identityFactory: () -> AdbPairingSelfSignedCertificate = {
        AdbPairingSelfSignedCertificate.generate()
    },
    private val random: SecureRandom = SecureRandom(),
    private val socketFactory: (InetSocketAddress, Int) -> Socket = { address, timeoutMs ->
        Socket().apply {
            connect(address, timeoutMs)
            soTimeout = AdbPairingController.OVERALL_TIMEOUT_MS.toInt()
            tcpNoDelay = true
        }
    }
) : AdbPairingTransport {

    override fun connect(host: String, port: Int, connectTimeoutMs: Long): AdbPairingChannel {
        val socket = socketFactory(InetSocketAddress(host, port), connectTimeoutMs.toInt())
        try {
            val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
            val client = PairingTlsClient(BcTlsCrypto(random), identityFactory())
            protocol.connect(client)
            val exporter = client.exportExporterMaterial()
                ?: throw IllegalStateException("no exporter material after handshake")
            return AdbPairingTlsChannel(socket, protocol, exporter)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    /** BC TLS client that accepts any server certificate and offers client credentials. */
    private class PairingTlsClient(
        crypto: BcTlsCrypto,
        private val identity: AdbPairingSelfSignedCertificate
    ) : DefaultTlsClient(crypto) {

        private val bcCrypto = crypto
        private var exporter: ByteArray? = null
        private var finished = false

        override fun getProtocolVersions(): Array<ProtocolVersion> = arrayOf(ProtocolVersion.TLSv13)

        override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
            /** Accept any certificate: the PAKE (bound to the exporter) is the authentication. */
            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate?) = Unit

            override fun getClientCredentials(certificateRequest: CertificateRequest?): TlsCredentials? {
                if (certificateRequest == null) return null
                return BcDefaultTlsCredentialedSigner(
                    org.bouncycastle.tls.crypto.TlsCryptoParameters(context),
                    bcCrypto,
                    identity.privateKeyParameter(),
                    identity.toBcCertificate(bcCrypto),
                    // null lets BC pick the signature scheme from the certificate key; bctls 1.86 no
                    // longer exposes a P-256 ECDSA SignatureAndHashAlgorithm constant.
                    null
                )
            }
        }

        override fun notifyHandshakeComplete() {
            super.notifyHandshakeComplete()
            finished = true
            exporter = context.exportKeyingMaterial("", ByteArray(0), AdbPairingController.EXPORTER_BYTES)
        }

        fun exportExporterMaterial(): ByteArray? = if (finished) exporter else null
    }
}

/** [AdbPairingChannel] over the finished TLS session, using [AdbPairingPacket] framing. */
internal class AdbPairingTlsChannel(
    private val socket: Socket,
    private val protocol: TlsClientProtocol,
    override val exporterMaterial: ByteArray
) : AdbPairingChannel {

    private val input: InputStream = protocol.inputStream
    private val output: OutputStream = protocol.outputStream

    override fun readPacket(): AdbPairingPacket {
        val headerBytes = readExactly(AdbPairingLimits.HEADER_BYTES)
        val header = AdbPairingPacketHeader.decode(headerBytes)
            ?: throw IllegalStateException("invalid pairing packet header")
        val payload = readExactly(header.payloadSize)
        return AdbPairingPacket(header, payload)
    }

    override fun writePacket(packet: AdbPairingPacket) {
        val encoded = packet.encode()
        output.write(encoded)
        output.flush()
    }

    override fun close() {
        runCatching { protocol.close() }
        runCatching { socket.close() }
    }

    private fun readExactly(length: Int): ByteArray {
        require(length > 0) { "cannot read $length bytes" }
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) throw EOFException("pairing stream closed after $offset of $length bytes")
            offset += read
        }
        return buffer
    }
}

/** Marker so the controller can recognise a channel it did not build (tests use their own). */
internal fun AdbPairingChannel.isTlsBacked(): Boolean = this is AdbPairingTlsChannel

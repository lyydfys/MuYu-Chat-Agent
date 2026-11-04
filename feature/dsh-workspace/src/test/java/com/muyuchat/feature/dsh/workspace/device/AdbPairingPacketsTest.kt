package com.muyuchat.feature.dsh.workspace.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing, protobuf and AES-GCM building blocks for adb pairing.
 *
 * Only the framing is pinned by AOSP source; the protobuf layout and the GCM key/nonce derivation are
 * recorded assumptions ([AdbPairingWireAssumptions]) and the controller refuses to run while they are
 * unverified. These tests pin the *encoding* behaviour, not device compatibility.
 */
class AdbPairingPacketsTest {

    @Test
    fun `header is six bytes with a big endian payload size`() {
        val realistic = AdbPairingPacketHeader(
            version = 1,
            type = AdbPairingPacketType.PEER_INFO,
            payloadSize = 0x0000_1234
        )
        assertArrayEquals(
            byteArrayOf(1, 1, 0x00, 0x00, 0x12, 0x34),
            realistic.encode()
        )
        assertEquals(6, realistic.encode().size)
        assertEquals(AdbPairingLimits.HEADER_BYTES, realistic.encode().size)
    }

    @Test
    fun `header round trips through decode`() {
        for (size in listOf(1, 32, 256, 4_096, AdbPairingLimits.MAX_PAYLOAD_BYTES)) {
            val header = AdbPairingPacketHeader(1, AdbPairingPacketType.PEER_INFO, size)
            assertEquals(header, AdbPairingPacketHeader.decode(header.encode()))
        }
    }

    @Test
    fun `header decode rejects every malformed shape`() {
        assertNull(AdbPairingPacketHeader.decode(ByteArray(5)))
        assertNull(AdbPairingPacketHeader.decode(ByteArray(7)))
        // Wrong version.
        assertNull(AdbPairingPacketHeader.decode(byteArrayOf(2, 0, 0, 0, 0, 1)))
        // Unknown packet type.
        assertNull(AdbPairingPacketHeader.decode(byteArrayOf(1, 9, 0, 0, 0, 1)))
        // Zero payload.
        assertNull(AdbPairingPacketHeader.decode(byteArrayOf(1, 0, 0, 0, 0, 0)))
        // Payload above the configured ceiling.
        val tooLarge = byteArrayOf(1, 0, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertNull(AdbPairingPacketHeader.decode(tooLarge))
    }

    @Test
    fun `packet frame round trips and rejects truncation or trailing bytes`() {
        val payload = ByteArray(48) { it.toByte() }
        val packet = AdbPairingPacket.of(AdbPairingPacketType.SPAKE2_MSG, payload)
        val encoded = packet.encode()
        assertEquals(AdbPairingLimits.HEADER_BYTES + payload.size, encoded.size)
        assertEquals(packet, AdbPairingPacket.decode(encoded))
        assertNull(AdbPairingPacket.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(AdbPairingPacket.decode(encoded + byteArrayOf(0)))
        assertNull(AdbPairingPacket.decode(encoded.copyOf(3)))
    }

    @Test
    fun `protobuf varint boundaries are encoded correctly`() {
        fun encodeSingle(value: Long): ByteArray = AdbProtobufWriter().writeVarintField(1, value).toByteArray()
        assertArrayEquals(byteArrayOf(0x08, 0x00), encodeSingle(0))
        assertArrayEquals(byteArrayOf(0x08, 0x01), encodeSingle(1))
        assertArrayEquals(byteArrayOf(0x08, 0x7F), encodeSingle(127))
        // 128 -> 0x80 0x01 (two bytes, continuation bit).
        assertArrayEquals(byteArrayOf(0x08, 0x80.toByte(), 0x01), encodeSingle(128))
        // 300 -> 0xAC 0x02.
        assertArrayEquals(byteArrayOf(0x08, 0xAC.toByte(), 0x02), encodeSingle(300))
        // Five-byte varint for 2^28.
        val large = encodeSingle(268_435_456L)
        assertEquals(6, large.size)
        assertEquals(0x08.toByte(), large[0])
    }

    @Test
    fun `protobuf length delimited fields carry a varint length`() {
        val payload = ByteArray(300) { 0x41 }
        val encoded = AdbProtobufWriter().writeBytesField(2, payload).toByteArray()
        // tag (field 2, wire type 2) = 0x12, length 300 = 0xAC 0x02.
        assertEquals(0x12.toByte(), encoded[0])
        assertEquals(0xAC.toByte(), encoded[1])
        assertEquals(0x02.toByte(), encoded[2])
        assertEquals(3 + 300, encoded.size)
    }

    @Test
    fun `peer info encodes type public key and identity with documented field numbers`() {
        val publicKey = ByteArray(8) { (it + 1).toByte() }
        val peerInfo = AdbPeerInfo(AdbPeerInfo.TYPE_ADB, publicKey, "mca-adb-pairing")
        val bytes = peerInfo.toProtobufBytes()

        // field 1 varint 1 -> 0x08 0x01
        assertArrayEquals(byteArrayOf(0x08, 0x01), bytes.copyOfRange(0, 2))
        // field 2, length 8 -> 0x12 0x08 then the key
        assertEquals(0x12.toByte(), bytes[2])
        assertEquals(8.toByte(), bytes[3])
        assertArrayEquals(publicKey, bytes.copyOfRange(4, 12))
        // field 3 string "mca-adb-pairing" (15 chars) -> 0x1A 0x0F
        assertEquals(0x1A.toByte(), bytes[12])
        assertEquals(15.toByte(), bytes[13])
        assertEquals("mca-adb-pairing", bytes.copyOfRange(14, bytes.size).toString(Charsets.UTF_8))
    }

    @Test
    fun `peer info handles a large public key and an empty identity`() {
        val publicKey = ByteArray(1_024) { 0x5A }
        val bytes = AdbPeerInfo(AdbPeerInfo.TYPE_ADB, publicKey, "").toProtobufBytes()
        // The length prefix for 1024 is a two-byte varint (0x80 0x08).
        assertEquals(0x12.toByte(), bytes[2])
        assertEquals(0x80.toByte(), bytes[3])
        assertEquals(0x08.toByte(), bytes[4])
        assertArrayEquals(publicKey, bytes.copyOfRange(5, 5 + publicKey.size))
    }

    @Test
    fun `aes gcm round trips and detects tampering`() {
        val cipher = AdbPairingCipher(ByteArray(16) { it.toByte() }, ByteArray(12) { (it * 3).toByte() })
        val plaintext = "peer-info".toByteArray()

        val encrypted = cipher.encrypt(plaintext)
        assertEquals(plaintext.size + AdbPairingCipher.TAG_BYTES, encrypted.size)
        assertArrayEquals(plaintext, cipher.decrypt(encrypted))

        // Any flipped bit must fail authentication.
        for (index in encrypted.indices) {
            val tampered = encrypted.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertNull("tampered byte $index must not decrypt", cipher.decrypt(tampered))
        }
        // Truncated blob.
        assertNull(cipher.decrypt(encrypted.copyOf(encrypted.size - 1)))
        assertNull(cipher.decrypt(ByteArray(0)))
        // A different key must not decrypt it.
        val otherKey = AdbPairingCipher(ByteArray(16) { (it + 1).toByte() }, ByteArray(12))
        assertNull(otherKey.decrypt(encrypted))
    }

    @Test
    fun `cipher rejects wrong key or nonce sizes`() {
        assertTrue(runCatching { AdbPairingCipher(ByteArray(15), ByteArray(12)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { AdbPairingCipher(ByteArray(16), ByteArray(11)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { AdbPairingCipher(ByteArray(32), ByteArray(12)) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `nonce counter issues big endian sequence starting at zero`() {
        val counter = AdbPairingNonceCounter()
        val first = counter.next()
        val second = counter.next()
        assertEquals(12, first.size)
        assertArrayEquals(ByteArray(12), first)
        assertEquals(1, second[11].toInt())
        assertEquals(0, second[3].toInt())
        val third = counter.next()
        assertEquals(2, third[11].toInt())
        assertEquals(3L, counter.issued)
        // 255 -> 256 carries into the previous byte.
        val wrapping = AdbPairingNonceCounter(start = 255)
        wrapping.next()
        val afterCarry = wrapping.next()
        assertEquals(1, afterCarry[10].toInt())
        assertEquals(0, afterCarry[11].toInt())
    }

    @Test
    fun `packet type wire values and assumptions gate`() {
        assertEquals(0, AdbPairingPacketType.SPAKE2_MSG.wireValue)
        assertEquals(1, AdbPairingPacketType.PEER_INFO.wireValue)
        assertNull(AdbPairingPacketType.fromWireValue(2))

        val unverified = AdbPairingWireAssumptions.UNVERIFIED
        assertFalse(unverified.isFullyVerified)
        assertEquals(
            listOf("peer_info_layout", "aes_gcm_derivation", "packet_type_values", "tls_exporter_label"),
            unverified.missingVerifications()
        )
        assertTrue(AdbPairingWireAssumptions.ALL_VERIFIED.isFullyVerified)
        assertTrue(AdbPairingWireAssumptions.ALL_VERIFIED.missingVerifications().isEmpty())
    }

    @Test
    fun `key schedule default is deterministic and spans key plus nonce`() {
        val schedule = AdbPairingKeySchedule.UnverifiedDefault
        val first = schedule.derive(ByteArray(64) { 0x33 })
        assertEquals(16, first.first.size)
        assertEquals(12, first.second.size)
        val second = schedule.derive(ByteArray(64) { 0x33 })
        assertArrayEquals(first.first, second.first)
        assertArrayEquals(first.second, second.second)
        val different = schedule.derive(ByteArray(64) { 0x34 })
        assertNotEquals(
            first.first.joinToString("") { "%02x".format(it) },
            different.first.joinToString("") { "%02x".format(it) }
        )
    }
}

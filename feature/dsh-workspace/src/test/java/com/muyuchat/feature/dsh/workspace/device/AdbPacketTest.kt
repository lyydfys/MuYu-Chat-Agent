package com.muyuchat.feature.dsh.workspace.device

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADB framing: every malformed byte sequence must be a typed [AdbPacketError], never an exception.
 */
class AdbPacketTest {

    @Test
    fun `command ids are the little endian fourcc of the protocol`() {
        assertEquals(0x4e584e43, AdbCommand.CNXN)
        assertEquals(0x48545541, AdbCommand.AUTH)
        assertEquals(0x4e45504f, AdbCommand.OPEN)
        assertEquals(0x59414b4f, AdbCommand.OKAY)
        assertEquals(0x45545257, AdbCommand.WRTE)
        assertEquals(0x45534c43, AdbCommand.CLSE)
        assertTrue(AdbCommand.isKnown(AdbCommand.WRTE))
        assertEquals("WRTE", AdbCommand.nameOf(AdbCommand.WRTE))
    }

    @Test
    fun `crc32 matches the reference check value`() {
        // The standard CRC32 check value for "123456789" is 0xCBF43926.
        assertEquals(0xCBF43926.toInt(), AdbPacketCodec.crc32("123456789".toByteArray()))
        assertEquals(0, AdbPacketCodec.crc32(ByteArray(0)))
    }

    @Test
    fun `cnxn round trips with payload length crc and magic`() {
        val payload = "host::\u0000".toByteArray(Charsets.UTF_8)
        val encoded = encodeOrFail(AdbCommand.CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_PAYLOAD, payload)
        assertEquals(AdbPacketCodec.HEADER_SIZE + payload.size, encoded.size)

        val header = (AdbPacketCodec.decodeHeader(encoded) as AdbHeaderRead.Ok).header
        assertEquals(AdbCommand.CNXN, header.command)
        assertEquals(AdbProtocol.VERSION, header.arg0)
        assertEquals(AdbProtocol.MAX_PAYLOAD, header.arg1)
        assertEquals(payload.size, header.payloadLength)
        assertEquals(AdbPacketCodec.crc32(payload), header.payloadCrc32)
        assertEquals(AdbCommand.CNXN xor -1, header.magic)
        assertEquals(header.expectedMagic, header.magic)

        val read = AdbPacketCodec.readPacket(ByteArrayInputStream(encoded)) as AdbPacketRead.Ok
        assertEquals(header, read.header)
        assertArrayEquals(payload, read.payload)
    }

    @Test
    fun `an auth signature packet round trips without a NUL payload`() {
        val signature = ByteArray(256) { it.toByte() }
        val encoded = encodeOrFail(AdbCommand.AUTH, AdbProtocol.AUTH_SIGNATURE, 0, signature)
        val read = AdbPacketCodec.readPacket(ByteArrayInputStream(encoded)) as AdbPacketRead.Ok
        assertEquals(AdbProtocol.AUTH_SIGNATURE, read.header.arg0)
        assertArrayEquals(signature, read.payload)
    }

    @Test
    fun `a bad magic is rejected`() {
        val encoded = encodeOrFail(AdbCommand.CNXN, 1, 2, "x".toByteArray())
        writeIntLe(encoded, 20, 0x00000000)
        assertEquals(AdbPacketError.BAD_MAGIC, headerFailure(encoded))
        assertEquals(AdbPacketError.BAD_MAGIC, readFailure(encoded))
    }

    @Test
    fun `a payload length above the ceiling is rejected before allocation`() {
        val encoded = encodeOrFail(AdbCommand.WRTE, 1, 1, "x".toByteArray())
        writeIntLe(encoded, 12, AdbPacketCodec.MAX_PAYLOAD + 1)
        assertEquals(AdbPacketError.BAD_LENGTH, headerFailure(encoded))
        writeIntLe(encoded, 12, -1)
        assertEquals(AdbPacketError.BAD_LENGTH, headerFailure(encoded))
    }

    @Test
    fun `a truncated header is rejected`() {
        assertEquals(AdbHeaderRead.Failure(AdbPacketError.TRUNCATED_HEADER), AdbPacketCodec.decodeHeader(ByteArray(23)))
        val partial = encodeOrFail(AdbCommand.CNXN, 0, 0, ByteArray(0)).copyOf(10)
        assertEquals(AdbPacketError.TRUNCATED_HEADER, readFailure(partial))
    }

    @Test
    fun `a clean end of stream is not an error`() {
        assertEquals(AdbPacketRead.EndOfStream, AdbPacketCodec.readPacket(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a truncated payload is rejected`() {
        val encoded = encodeOrFail(AdbCommand.WRTE, 1, 1, "abcdef".toByteArray())
        val truncated = encoded.copyOf(AdbPacketCodec.HEADER_SIZE + 3)
        assertEquals(AdbPacketError.TRUNCATED_PAYLOAD, readFailure(truncated))
    }

    @Test
    fun `a corrupted payload fails the crc check`() {
        val encoded = encodeOrFail(AdbCommand.WRTE, 1, 1, "abcdef".toByteArray())
        encoded[AdbPacketCodec.HEADER_SIZE] = 'z'.code.toByte()
        assertEquals(AdbPacketError.BAD_CRC, readFailure(encoded))
    }

    @Test
    fun `encoding refuses a payload above the ceiling`() {
        val oversize = ByteArray(AdbPacketCodec.MAX_PAYLOAD + 1)
        val failure = AdbPacketCodec.encode(AdbCommand.WRTE, 1, 1, oversize)
        assertEquals(AdbPacketWrite.Failure(AdbPacketError.BAD_LENGTH), failure)
    }

    @Test
    fun `a max sized payload is accepted`() {
        val payload = ByteArray(AdbPacketCodec.MAX_PAYLOAD) { 0x5A }
        val encoded = encodeOrFail(AdbCommand.WRTE, 7, 8, payload)
        val read = AdbPacketCodec.readPacket(ByteArrayInputStream(encoded)) as AdbPacketRead.Ok
        assertEquals(AdbPacketCodec.MAX_PAYLOAD, read.header.payloadLength)
        assertArrayEquals(payload, read.payload)
    }

    @Test
    fun `fragmentation splits exactly at the payload ceiling`() {
        assertEquals(0, AdbPacketCodec.fragment(ByteArray(0)).size)
        val exactly = AdbPacketCodec.fragment(ByteArray(AdbPacketCodec.MAX_PAYLOAD))
        assertEquals(1, exactly.size)
        assertEquals(AdbPacketCodec.MAX_PAYLOAD, exactly[0].size)
        val oneMore = AdbPacketCodec.fragment(ByteArray(AdbPacketCodec.MAX_PAYLOAD + 1))
        assertEquals(2, oneMore.size)
        assertEquals(1, oneMore[1].size)
        val small = AdbPacketCodec.fragment(ByteArray(3), max = 1)
        assertEquals(3, small.size)
        assertEquals(0, AdbPacketCodec.fragment(ByteArray(3), max = 0).size)
    }

    @Test
    fun `little endian integer helpers are symmetric`() {
        val bytes = ByteArray(8)
        writeIntLe(bytes, 0, 0x01020304)
        writeIntLe(bytes, 4, -1)
        assertEquals(0x01020304, readIntLe(bytes, 0))
        assertEquals(-1, readIntLe(bytes, 4))
        assertEquals(0x04, bytes[0].toInt() and 0xFF)
        assertEquals(0x01, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun `an auth token packet is exactly the 20 byte token`() {
        val token = ByteArray(20) { (it + 1).toByte() }
        val encoded = encodeOrFail(AdbCommand.AUTH, AdbProtocol.AUTH_TOKEN, 0, token)
        val read = AdbPacketCodec.readPacket(ByteArrayInputStream(encoded)) as AdbPacketRead.Ok
        assertEquals(20, read.header.payloadLength)
        assertArrayEquals(token, read.payload)
    }

    private fun encodeOrFail(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ): ByteArray = (AdbPacketCodec.encode(command, arg0, arg1, payload) as AdbPacketWrite.Ok).bytes

    private fun headerFailure(bytes: ByteArray): AdbPacketError =
        (AdbPacketCodec.decodeHeader(bytes) as AdbHeaderRead.Failure).error

    private fun readFailure(bytes: ByteArray): AdbPacketError =
        (AdbPacketCodec.readPacket(ByteArrayInputStream(bytes)) as AdbPacketRead.Failure).error
}

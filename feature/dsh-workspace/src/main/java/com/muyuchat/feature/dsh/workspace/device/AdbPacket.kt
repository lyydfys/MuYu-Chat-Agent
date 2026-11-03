package com.muyuchat.feature.dsh.workspace.device

import java.io.InputStream
import java.util.zip.CRC32

/**
 * ADB wire protocol framing.
 *
 * One message is a 24-byte little-endian header followed by `payloadLength` bytes:
 *
 * ```
 * command | arg0 | arg1 | payloadLength | payloadCrc32 | magic   (magic == command xor -1)
 * ```
 *
 * The codec is pure except for [AdbPacketCodec.readPacket], which reads from an already-open stream.
 * It never throws on malformed input: every rejection is a stable [AdbPacketError] code, so a broken
 * peer becomes a `protocol_error` result instead of an exception in the UI.
 */
object AdbCommand {

    /** `'C','N','X','N'` read as a little-endian int. */
    const val CNXN: Int = 0x4e584e43

    /** `'A','U','T','H'`. */
    const val AUTH: Int = 0x48545541

    /** `'O','P','E','N'`. */
    const val OPEN: Int = 0x4e45504f

    /** `'O','K','A','Y'`. */
    const val OKAY: Int = 0x59414b4f

    /** `'W','R','T','E'`. */
    const val WRTE: Int = 0x45545257

    /** `'C','L','S','E'`. */
    const val CLSE: Int = 0x45534c43

    fun isKnown(command: Int): Boolean = when (command) {
        CNXN, AUTH, OPEN, OKAY, WRTE, CLSE -> true
        else -> false
    }

    /** Fourcc for diagnostics; never carries payload data. */
    fun nameOf(command: Int): String = when (command) {
        CNXN -> "CNXN"
        AUTH -> "AUTH"
        OPEN -> "OPEN"
        OKAY -> "OKAY"
        WRTE -> "WRTE"
        CLSE -> "CLSE"
        else -> "0x" + command.toUInt().toString(16)
    }
}

/** Protocol constants the transport and the in-process test server share. */
object AdbProtocol {

    /** `A_VERSION`: adbd accepts this and echoes it back. */
    const val VERSION: Int = 0x01000000

    /** Largest payload of one packet; larger streams are split into several packets. */
    const val MAX_PAYLOAD: Int = 4096

    const val HEADER_SIZE: Int = 24

    /** `arg0` of an `AUTH` message asking for an RSA signature of its token. */
    const val AUTH_TOKEN: Int = 1

    /** `arg0` of an `AUTH` message carrying the RSA signature. */
    const val AUTH_SIGNATURE: Int = 2

    /** `arg0` of an `AUTH` message carrying the base64 public key. */
    const val AUTH_RSAPUBLICKEY: Int = 3
}

/** One decoded header. [magic] must equal [expectedMagic]. */
data class AdbPacketHeader(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payloadLength: Int,
    val payloadCrc32: Int,
    val magic: Int
) {
    val expectedMagic: Int get() = command xor -1

    val commandName: String get() = AdbCommand.nameOf(command)
}

/** Stable framing failure codes; never contain payload bytes. */
enum class AdbPacketError(val code: String) {
    TRUNCATED_HEADER("adb_truncated_header"),
    TRUNCATED_PAYLOAD("adb_truncated_payload"),
    BAD_MAGIC("adb_bad_magic"),
    BAD_LENGTH("adb_bad_length"),
    BAD_CRC("adb_bad_crc")
}

/** Result of encoding one message. */
sealed interface AdbPacketWrite {
    data class Ok(val bytes: ByteArray) : AdbPacketWrite

    data class Failure(val error: AdbPacketError) : AdbPacketWrite
}

/** Result of decoding a header. */
sealed interface AdbHeaderRead {
    data class Ok(val header: AdbPacketHeader) : AdbHeaderRead

    data class Failure(val error: AdbPacketError) : AdbHeaderRead

    data object EndOfStream : AdbHeaderRead
}

/** Result of reading one whole message. */
sealed interface AdbPacketRead {
    data class Ok(val header: AdbPacketHeader, val payload: ByteArray) : AdbPacketRead

    data class Failure(val error: AdbPacketError) : AdbPacketRead

    data object EndOfStream : AdbPacketRead
}

/**
 * Header/payload codec.
 *
 * Every public entry point is total: malformed bytes produce [AdbPacketError], never an exception.
 */
object AdbPacketCodec {

    const val HEADER_SIZE: Int = AdbProtocol.HEADER_SIZE

    const val MAX_PAYLOAD: Int = AdbProtocol.MAX_PAYLOAD

    /** CRC32 of [length] bytes starting at [offset]; adb uses the plain zlib CRC32 of the payload. */
    fun crc32(payload: ByteArray, offset: Int = 0, length: Int = payload.size): Int {
        if (length <= 0) return 0
        val crc = CRC32()
        crc.update(payload, offset, length)
        return crc.value.toInt()
    }

    /** Encodes one complete message (header + payload) with the correct magic and checksum. */
    fun encode(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray = ByteArray(0)
    ): AdbPacketWrite {
        if (payload.size > MAX_PAYLOAD) {
            return AdbPacketWrite.Failure(AdbPacketError.BAD_LENGTH)
        }
        val bytes = ByteArray(HEADER_SIZE + payload.size)
        writeIntLe(bytes, 0, command)
        writeIntLe(bytes, 4, arg0)
        writeIntLe(bytes, 8, arg1)
        writeIntLe(bytes, 12, payload.size)
        writeIntLe(bytes, 16, crc32(payload))
        writeIntLe(bytes, 20, command xor -1)
        System.arraycopy(payload, 0, bytes, HEADER_SIZE, payload.size)
        return AdbPacketWrite.Ok(bytes)
    }

    /** Decodes a 24-byte header from the first [HEADER_SIZE] bytes of [bytes]. */
    fun decodeHeader(bytes: ByteArray): AdbHeaderRead {
        if (bytes.size < HEADER_SIZE) return AdbHeaderRead.Failure(AdbPacketError.TRUNCATED_HEADER)
        val header = AdbPacketHeader(
            command = readIntLe(bytes, 0),
            arg0 = readIntLe(bytes, 4),
            arg1 = readIntLe(bytes, 8),
            payloadLength = readIntLe(bytes, 12),
            payloadCrc32 = readIntLe(bytes, 16),
            magic = readIntLe(bytes, 20)
        )
        val failure = validateHeader(header)
        return if (failure == null) AdbHeaderRead.Ok(header) else AdbHeaderRead.Failure(failure)
    }

    /**
     * Reads exactly one message from [source].
     *
     * A clean end of stream before the first byte is [AdbPacketRead.EndOfStream]; a stream that ends
     * inside a header/payload is a framing failure.
     */
    fun readPacket(source: InputStream): AdbPacketRead {
        val headerBytes = ByteArray(HEADER_SIZE)
        var headerRead = 0
        while (headerRead < HEADER_SIZE) {
            val read = source.read(headerBytes, headerRead, HEADER_SIZE - headerRead)
            if (read < 0) {
                if (headerRead == 0) return AdbPacketRead.EndOfStream
                return AdbPacketRead.Failure(AdbPacketError.TRUNCATED_HEADER)
            }
            headerRead += read
        }
        val header = when (val decoded = decodeHeader(headerBytes)) {
            is AdbHeaderRead.Ok -> decoded.header
            is AdbHeaderRead.Failure -> return AdbPacketRead.Failure(decoded.error)
            AdbHeaderRead.EndOfStream -> return AdbPacketRead.Failure(AdbPacketError.TRUNCATED_HEADER)
        }
        val payload = ByteArray(header.payloadLength)
        var payloadRead = 0
        while (payloadRead < payload.size) {
            val read = source.read(payload, payloadRead, payload.size - payloadRead)
            if (read < 0) return AdbPacketRead.Failure(AdbPacketError.TRUNCATED_PAYLOAD)
            payloadRead += read
        }
        if (payload.isNotEmpty() && crc32(payload) != header.payloadCrc32) {
            return AdbPacketRead.Failure(AdbPacketError.BAD_CRC)
        }
        return AdbPacketRead.Ok(header, payload)
    }

    /**
     * Splits [payload] into at most [max]-byte chunks.
     *
     * Used to document and test the fragmentation boundary; the transport itself sends one packet per
     * AUTH/OPEN/OKAY/CLSE message and streams long device output through repeated `WRTE` messages.
     */
    fun fragment(payload: ByteArray, max: Int = MAX_PAYLOAD): List<ByteArray> {
        if (max <= 0) return emptyList()
        if (payload.isEmpty()) return emptyList()
        val chunks = ArrayList<ByteArray>((payload.size + max - 1) / max)
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + max, payload.size)
            chunks.add(payload.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    private fun validateHeader(header: AdbPacketHeader): AdbPacketError? {
        if (header.magic != header.expectedMagic) return AdbPacketError.BAD_MAGIC
        if (header.payloadLength < 0 || header.payloadLength > MAX_PAYLOAD) {
            return AdbPacketError.BAD_LENGTH
        }
        return null
    }
}

/** Little-endian int write; adb framing is LE on every platform. */
internal fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

/** Little-endian int read; adb framing is LE on every platform. */
internal fun readIntLe(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xFF) or
        ((source[offset + 1].toInt() and 0xFF) shl 8) or
        ((source[offset + 2].toInt() and 0xFF) shl 16) or
        ((source[offset + 3].toInt() and 0xFF) shl 24)

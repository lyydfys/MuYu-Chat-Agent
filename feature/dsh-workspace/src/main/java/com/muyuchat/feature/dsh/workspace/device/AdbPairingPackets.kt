package com.muyuchat.feature.dsh.workspace.device

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wire framing for AOSP adb wireless pairing (`pairing_connection/pairing_connection.cpp`).
 *
 * Framing is the part of the protocol that is pinned by the AOSP source and therefore implemented
 * here without assumptions:
 *
 * ```
 * struct PairingPacketHeader {   // written as-is, payload converted with htonl()
 *   uint8_t version;             // 1
 *   uint8_t type;                // PairingPacket::Type
 *   uint32_t payload;            // payload byte count, network byte order
 * };
 * ```
 *
 * `kMaxPayloadSize` is `kMaxPeerInfoSize * 2` upstream; the exact `kMaxPeerInfoSize` value is not
 * re-exported to this module, so [AdbPairingLimits.MAX_PAYLOAD_BYTES] is a conservative bound and
 * is listed as an assumption.
 */
object AdbPairingLimits {
    /** `PairingPacketHeader` size on the wire. */
    const val HEADER_BYTES: Int = 6

    /** Only version 1 is accepted, as in `kCurrentKeyHeaderVersion`. */
    const val HEADER_VERSION: Int = 1

    /**
     * Conservative copy of `kMaxPayloadSize`. AOSP computes it as `kMaxPeerInfoSize * 2`; this module
     * keeps the same shape but treats the absolute number as an assumption until the proto/struct
     * sizes are confirmed on a device.
     */
    const val MAX_PAYLOAD_BYTES: Int = 16_384

    /** Bounds the encrypted `PEER_INFO` blob so a hostile peer cannot make us allocate without limit. */
    const val MAX_ENCRYPTED_PEER_INFO_BYTES: Int = MAX_PAYLOAD_BYTES
}

/**
 * `adb::proto::PairingPacket::Type` values used by the pairing connection.
 *
 * The numeric values follow the proto declaration order (`SPAKE2_MSG` then `PEER_INFO`), which is how
 * protobuf enumerates them. This is an explicit **assumption**: it must be checked against
 * `pairing_connection/pairing.proto` before claiming device compatibility.
 */
enum class AdbPairingPacketType(val wireValue: Int) {
    SPAKE2_MSG(0),
    PEER_INFO(1);

    companion object {
        fun fromWireValue(value: Int): AdbPairingPacketType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * The six-byte pairing packet header.
 *
 * `version` and `type` are single bytes; `payloadSize` is written big-endian because AOSP does
 * `network_header.payload = htonl(network_header.payload)` before writing the struct.
 */
data class AdbPairingPacketHeader(
    val version: Int,
    val type: AdbPairingPacketType,
    val payloadSize: Int
) {
    init {
        require(version in 0..0xFF) { "version must fit in one byte" }
        require(payloadSize in 1..AdbPairingLimits.MAX_PAYLOAD_BYTES) {
            "payload size must be 1..${AdbPairingLimits.MAX_PAYLOAD_BYTES}"
        }
    }

    /** Serialises to exactly [AdbPairingLimits.HEADER_BYTES] bytes. */
    fun encode(): ByteArray = byteArrayOf(
        version.toByte(),
        type.wireValue.toByte(),
        ((payloadSize ushr 24) and 0xFF).toByte(),
        ((payloadSize ushr 16) and 0xFF).toByte(),
        ((payloadSize ushr 8) and 0xFF).toByte(),
        (payloadSize and 0xFF).toByte()
    )

    companion object {
        /**
         * Decodes and validates a header, or returns null.
         *
         * Rejected: wrong length, unsupported version, unknown packet type, zero payload and a payload
         * larger than [AdbPairingLimits.MAX_PAYLOAD_BYTES] — all four are explicit failure checks in
         * AOSP's `ReadHeader`.
         */
        fun decode(bytes: ByteArray): AdbPairingPacketHeader? {
            if (bytes.size != AdbPairingLimits.HEADER_BYTES) return null
            val version = bytes[0].toInt() and 0xFF
            if (version != AdbPairingLimits.HEADER_VERSION) return null
            val type = AdbPairingPacketType.fromWireValue(bytes[1].toInt() and 0xFF) ?: return null
            val payloadSize = ((bytes[2].toInt() and 0xFF) shl 24) or
                ((bytes[3].toInt() and 0xFF) shl 16) or
                ((bytes[4].toInt() and 0xFF) shl 8) or
                (bytes[5].toInt() and 0xFF)
            if (payloadSize <= 0 || payloadSize > AdbPairingLimits.MAX_PAYLOAD_BYTES) return null
            return AdbPairingPacketHeader(version, type, payloadSize)
        }
    }
}

/** Header plus payload, as one write on the TLS stream. */
data class AdbPairingPacket(val header: AdbPairingPacketHeader, val payload: ByteArray) {
    init {
        require(payload.size == header.payloadSize) {
            "payload size ${payload.size} does not match header ${header.payloadSize}"
        }
    }

    fun encode(): ByteArray = header.encode() + payload

    override fun equals(other: Any?): Boolean =
        other is AdbPairingPacket && other.header == header && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * header.hashCode() + payload.contentHashCode()

    companion object {
        fun of(type: AdbPairingPacketType, payload: ByteArray): AdbPairingPacket = AdbPairingPacket(
            AdbPairingPacketHeader(AdbPairingLimits.HEADER_VERSION, type, payload.size),
            payload
        )

        /** Splits a buffered frame, or null when the buffer is not exactly one valid frame. */
        fun decode(bytes: ByteArray): AdbPairingPacket? {
            if (bytes.size < AdbPairingLimits.HEADER_BYTES) return null
            val header = AdbPairingPacketHeader.decode(bytes.copyOfRange(0, AdbPairingLimits.HEADER_BYTES))
                ?: return null
            if (bytes.size != AdbPairingLimits.HEADER_BYTES + header.payloadSize) return null
            return AdbPairingPacket(header, bytes.copyOfRange(AdbPairingLimits.HEADER_BYTES, bytes.size))
        }
    }
}

/**
 * Minimal protobuf writer: only the wire types this protocol needs (varint, length-delimited).
 *
 * Hand-written on purpose — the app does not ship a protobuf runtime, and the only message involved
 * has three fields.
 */
internal class AdbProtobufWriter {
    private val out = ByteArrayOutputStream()

    fun writeVarintField(fieldNumber: Int, value: Long): AdbProtobufWriter {
        writeTag(fieldNumber, wireType = 0)
        writeVarint(value)
        return this
    }

    fun writeBytesField(fieldNumber: Int, value: ByteArray): AdbProtobufWriter {
        writeTag(fieldNumber, wireType = 2)
        writeVarint(value.size.toLong())
        out.write(value)
        return this
    }

    fun writeStringField(fieldNumber: Int, value: String): AdbProtobufWriter =
        writeBytesField(fieldNumber, value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeTag(fieldNumber: Int, wireType: Int) {
        require(fieldNumber in 1..0x1FFF_FFFF) { "field number out of range: $fieldNumber" }
        writeVarint(((fieldNumber shl 3) or wireType).toLong())
    }

    private fun writeVarint(value: Long) {
        require(value >= 0) { "varint must be non-negative" }
        var remaining = value
        while (true) {
            val sevenBits = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining == 0L) {
                out.write(sevenBits)
                return
            }
            out.write(sevenBits or 0x80)
        }
    }
}

/**
 * The `PeerInfo` payload, as this module encodes it.
 *
 * **Assumption, not verified.** AOSP's `pairing_connection.cpp` writes `sizeof(PeerInfo)` raw bytes,
 * which suggests a packed struct rather than a protobuf message, while the same file includes a
 * generated `pairing.pb.h` for the packet type enum. Both candidate layouts are plausible; this module
 * encodes the protobuf shape (`type = 1`, `public_key = 2`, `system_identity = 3`) behind
 * [AdbPairingWireAssumptions], and [AdbPairingWireAssumptions.PEER_INFO_LAYOUT_PROTOBUF] is marked
 * unverified so the controller refuses to run a real pairing until it is confirmed.
 */
data class AdbPeerInfo(
    /** `PeerInfo::Type`: 0 = UNKNOWN, 1 = ADB. */
    val type: Int,
    /** Our adb RSA public key in adb's own `RSAPublicKey` wire format (see [AdbIdentity]). */
    val publicKey: ByteArray,
    /** Human-readable device/system identity shown in the pairing UI. */
    val systemIdentity: String
) {
    /** Protobuf encoding with field numbers 1/2/3 (see class KDoc: unverified layout). */
    fun toProtobufBytes(): ByteArray = AdbProtobufWriter()
        .writeVarintField(FIELD_TYPE, type.toLong())
        .writeBytesField(FIELD_PUBLIC_KEY, publicKey)
        .writeStringField(FIELD_SYSTEM_IDENTITY, systemIdentity)
        .toByteArray()

    companion object {
        const val TYPE_ADB: Int = 1
        const val FIELD_TYPE: Int = 1
        const val FIELD_PUBLIC_KEY: Int = 2
        const val FIELD_SYSTEM_IDENTITY: Int = 3
    }
}

/**
 * AES-128-GCM with an explicitly supplied nonce.
 *
 * AOSP's `Aes128Gcm` owns the key/nonce derivation from the SPAKE2 key material; that derivation is
 * **not** reproduced here (it could not be read from source in this slice), so the cipher takes the
 * 16-byte key and 12-byte nonce as parameters. [AdbPairingWireAssumptions] records the unresolved
 * derivation and [AdbPairingNonceCounter] keeps the counter policy in one testable place.
 */
class AdbPairingCipher(private val key: ByteArray, private val nonce: ByteArray) {

    init {
        require(key.size == KEY_BYTES) { "AES-128-GCM needs a 16-byte key" }
        require(nonce.size == NONCE_BYTES) { "GCM nonce must be 12 bytes" }
    }

    /** Encrypts [plaintext] and returns `ciphertext || tag` (no nonce is prepended). */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        return cipher.doFinal(plaintext)
    }

    /** Decrypts `ciphertext || tag`; null when the tag does not verify (tampering or wrong key). */
    fun decrypt(blob: ByteArray): ByteArray? = runCatching {
        if (blob.size < TAG_BITS / 8) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.doFinal(blob)
    }.getOrNull()

    companion object {
        const val KEY_BYTES: Int = 16
        const val NONCE_BYTES: Int = 12
        const val TAG_BYTES: Int = 16
        private const val TAG_BITS = TAG_BYTES * 8
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * Twelve-byte counter nonce, starting at zero and incrementing big-endian.
 *
 * The exact nonce policy of AOSP's `Aes128Gcm` is one of the open questions for this slice; keeping it
 * in a tiny class means a corrected policy is a one-file change and can be tested for wraparound.
 */
class AdbPairingNonceCounter(start: Long = 0) {
    private var counter: Long = start

    /** Bytes for the next packet; increments afterwards. */
    fun next(): ByteArray {
        val out = ByteArray(AdbPairingCipher.NONCE_BYTES)
        // First four bytes stay zero: 64-bit counter in the low half, big-endian.
        for (index in 0 until 8) {
            out[AdbPairingCipher.NONCE_BYTES - 1 - index] =
                ((counter ushr (8 * index)) and 0xFF).toByte()
        }
        counter++
        return out
    }

    /** Number of nonces handed out so far (start offset excluded). */
    val issued: Long get() = counter
}

/**
 * The protocol decisions this slice could not pin from source.
 *
 * Each flag is a fact, not a preference: while any is false the controller reports
 * [PairingOutcome.UNSUPPORTED] instead of driving a pairing that would silently fail against a real
 * device. Flip a flag only with evidence (AOSP file + line or a captured device session).
 */
data class AdbPairingWireAssumptions(
    /** `PeerInfo` protobuf layout (`type=1`, `public_key=2`, `system_identity=3`) is unverified. */
    val peerInfoLayoutProtobuf: Boolean = false,
    /** AES-GCM key/nonce derivation from SPAKE2 key material is unverified. */
    val aesGcmDerivation: Boolean = false,
    /** `PairingPacket::Type` numeric values (0/1) are assumed from proto declaration order. */
    val packetTypeValues: Boolean = false,
    /** TLS exporter label/context (empty/empty) is believed correct but unverified on device. */
    val exporterLabel: Boolean = false
) {
    /** True only when every protocol decision is backed by source or a captured session. */
    val isFullyVerified: Boolean
        get() = peerInfoLayoutProtobuf && aesGcmDerivation && packetTypeValues && exporterLabel

    /** Stable identifiers of what is still open, for logs/UI (never contains secrets). */
    fun missingVerifications(): List<String> = buildList {
        if (!peerInfoLayoutProtobuf) add("peer_info_layout")
        if (!aesGcmDerivation) add("aes_gcm_derivation")
        if (!packetTypeValues) add("packet_type_values")
        if (!exporterLabel) add("tls_exporter_label")
    }

    companion object {
        /** Nothing about the peer-info/GCM half has been verified against a device yet. */
        val UNVERIFIED: AdbPairingWireAssumptions = AdbPairingWireAssumptions()

        /**
         * The best-supported hypothesis set, used by the **experimental** console flow.
         *
         * Nothing here is proven on a device yet, so this value is not "verified": it only means "go
         * ahead and try". A wrong decision still cannot fake success — success requires the device's
         * reply to authenticate under the derived key — it merely fails with a concrete reason, which
         * is the only way to obtain the evidence that would flip these flags for real.
         */
        val EXPERIMENTAL: AdbPairingWireAssumptions = AdbPairingWireAssumptions(
            peerInfoLayoutProtobuf = true,
            aesGcmDerivation = true,
            packetTypeValues = true,
            exporterLabel = true
        )

        /** Only for unit tests that exercise the happy path of the state machine. */
        internal val ALL_VERIFIED: AdbPairingWireAssumptions = AdbPairingWireAssumptions(
            peerInfoLayoutProtobuf = true,
            aesGcmDerivation = true,
            packetTypeValues = true,
            exporterLabel = true
        )
    }
}

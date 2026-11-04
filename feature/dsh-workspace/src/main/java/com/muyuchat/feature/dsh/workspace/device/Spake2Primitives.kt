package com.muyuchat.feature.dsh.workspace.device

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Byte-level primitives behind SPAKE2: the RFC 9382 transcript encoding, constant-time comparison,
 * HKDF-SHA256, PBKDF2-HMAC-SHA256 and hex decoding.
 *
 * Kept in their own file so [Spake2P256] reads as the protocol and each primitive can be reviewed or
 * replaced on its own. Everything here is plain JVM (`java.math` + `javax.crypto`), with no Android
 * or JCE-provider-specific API.
 */

/**
 * RFC 9382 transcript: `len(A)||A||len(B)||B||len(pA)||pA||len(pB)||pB||len(K)||K||len(w)||w`,
 * where every `len` is an eight-byte little-endian byte count and `w` is padded to a field element.
 */
internal fun transcript(
    identityA: ByteArray,
    identityB: ByteArray,
    shareA: ByteArray,
    shareB: ByteArray,
    sharedPoint: ByteArray,
    passwordScalar: BigInteger
): ByteArray {
    val out = ByteArrayOutputStream()
    fun writeChunk(chunk: ByteArray) {
        out.write(littleEndianLength(chunk.size))
        out.write(chunk)
    }
    writeChunk(identityA)
    writeChunk(identityB)
    writeChunk(shareA)
    writeChunk(shareB)
    writeChunk(sharedPoint)
    writeChunk(P256.toFixed(passwordScalar))
    return out.toByteArray()
}

/** Eight-byte little-endian length, as RFC 9382 §3.2 requires. */
internal fun littleEndianLength(value: Int): ByteArray {
    require(value >= 0)
    // Shift a Long: `Int ushr 32` would be masked to a shift of 0 by the JVM and repeat the low byte.
    val wide = value.toLong() and 0xFFFF_FFFFL
    return ByteArray(8) { index -> ((wide ushr (8 * index)) and 0xFF).toByte() }
}

/** Constant-time byte comparison for MAC/confirmation checks (length is assumed public). */
internal fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
    if (left.size != right.size) return false
    var difference = 0
    for (index in left.indices) {
        difference = difference or (left[index].toInt() xor right[index].toInt())
    }
    return difference == 0
}

/** HKDF-SHA (RFC 5869) with an empty salt mapped to `hashLength` zero bytes, per the RFC. */
internal fun hkdf(
    ikm: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    length: Int,
    macAlgorithm: String = "HmacSHA256"
): ByteArray {
    require(length in 1..255 * 32) { "HKDF output length out of range: $length" }
    val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
    val mac = Mac.getInstance(macAlgorithm)
    mac.init(SecretKeySpec(effectiveSalt, macAlgorithm))
    val prk = mac.doFinal(ikm)
    val output = ByteArrayOutputStream()
    var previous = ByteArray(0)
    var counter = 1
    while (output.size() < length) {
        mac.init(SecretKeySpec(prk, macAlgorithm))
        mac.update(previous)
        mac.update(info)
        mac.update(counter.toByte())
        previous = mac.doFinal()
        val take = minOf(previous.size, length - output.size())
        output.write(previous, 0, take)
        counter++
    }
    return output.toByteArray()
}

/** PBKDF2-HMAC-SHA256 (RFC 8018 §5.2), implemented here so no provider/API-level dependency leaks. */
internal fun pbkdf2Sha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    length: Int
): ByteArray {
    require(iterations >= 1) { "PBKDF2 iterations must be at least 1" }
    require(password.isNotEmpty()) { "PBKDF2 password must not be empty" }
    require(length in 1..(Int.MAX_VALUE / 8)) { "PBKDF2 length out of range: $length" }
    val mac = Mac.getInstance("HmacSHA256")
    val key = SecretKeySpec(password, "HmacSHA256")
    val output = ByteArrayOutputStream()
    var blockIndex = 1
    while (output.size() < length) {
        mac.init(key)
        mac.update(salt)
        mac.update(
            byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte()
            )
        )
        var value = mac.doFinal()
        val block = value.copyOf()
        for (round in 1 until iterations) {
            mac.init(key)
            value = mac.doFinal(value)
            for (index in block.indices) {
                block[index] = (block[index].toInt() xor value[index].toInt()).toByte()
            }
        }
        val take = minOf(block.size, length - output.size())
        output.write(block, 0, take)
        blockIndex++
    }
    return output.toByteArray()
}

/** HMAC over [message] with [key]. */
internal fun hmac(key: ByteArray, message: ByteArray, macAlgorithm: String = "HmacSHA256"): ByteArray {
    val mac = Mac.getInstance(macAlgorithm)
    mac.init(SecretKeySpec(key, macAlgorithm))
    return mac.doFinal(message)
}

/** Hex decoding for the RFC 9382 seed points; accepts only hex digits of even length. */
internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have even length" }
    return ByteArray(hex.length / 2) { index ->
        val high = Character.digit(hex[index * 2], 16)
        val low = Character.digit(hex[index * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "invalid hex digit in $hex" }
        ((high shl 4) or low).toByte()
    }
}

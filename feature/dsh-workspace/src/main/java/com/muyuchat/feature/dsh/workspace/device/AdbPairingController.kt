package com.muyuchat.feature.dsh.workspace.device

import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Stable failure reasons for the pairing UI/console (never free-form device text). */
enum class AdbPairingFailure(val reasonCode: String) {
    /** Host/port/pairing code did not pass local validation. */
    INVALID_INPUT("invalid_input"),

    /** A slice of the wire protocol is not verified yet, so no attempt is made. */
    UNSUPPORTED("unsupported"),

    /** The TLS handshake or the exporter call failed. */
    TLS_FAILED("tls_failed"),

    /** The device's SPAKE2 share was unusable, or key material could not be derived. */
    SPAKE2_FAILED("spake2_failed"),

    /** The device refused our peer info, or its reply could not be authenticated. */
    PEER_INFO_REJECTED("peer_info_rejected"),

    /** Connect/overall deadline elapsed. */
    TIMEOUT("timeout"),

    /** The caller cancelled the attempt. */
    CANCELLED("cancelled"),

    /** Another pairing attempt is already running (one at a time, as adb itself insists). */
    CONCURRENT_PAIRING("concurrent_pairing"),

    /** Framing/transport failure that is neither TLS nor peer-info specific. */
    TRANSPORT_FAILED("transport_failed")
}

/** Outcome of one pairing attempt. */
sealed interface AdbPairingResult {
    /**
     * The device authenticated the exchange and returned a peer-info blob we could decrypt.
     *
     * This is the only success shape: no result is produced from "the socket accepted our bytes".
     */
    data class Success(
        /** Decrypted peer info from the device (its certificate/identity payload). */
        val peerInfo: ByteArray
    ) : AdbPairingResult

    /** Failure with a stable [reason] and optionally the unverified pieces that blocked the attempt. */
    data class Failure(
        val reason: AdbPairingFailure,
        val detail: List<String> = emptyList()
    ) : AdbPairingResult
}

/** One established, encrypted pairing channel (TLS + exporter). */
interface AdbPairingChannel : Closeable {

    /** `exportKeyingMaterial(label = "", context = "", 64)` from the completed TLS session. */
    val exporterMaterial: ByteArray

    /** Reads exactly one `PairingPacket`; throws on EOF, framing or timeout errors. */
    fun readPacket(): AdbPairingPacket

    /** Writes one `PairingPacket`. */
    fun writePacket(packet: AdbPairingPacket)
}

/** Opens the TLS channel to the pairing port. Implementations own socket + certificate handling. */
interface AdbPairingTransport {
    /**
     * Connects to `host:pairingPort`, performs the certificate TLS handshake and returns a channel
     * whose [AdbPairingChannel.exporterMaterial] already reflects the finished session.
     */
    fun connect(host: String, port: Int, connectTimeoutMs: Long): AdbPairingChannel
}

/**
 * Derives the AES-128-GCM key and nonce from the SPAKE2 key material.
 *
 * AOSP's `Aes128Gcm` does this internally; the exact construction could not be read in this slice, so
 * the derivation is an injected seam ([UnverifiedDefault] documents what is assumed). The controller
 * refuses to run while [AdbPairingWireAssumptions.aesGcmDerivation] is false.
 */
fun interface AdbPairingKeySchedule {
    /** Returns `key(16) to nonce(12)`. */
    fun derive(keyMaterial: ByteArray): Pair<ByteArray, ByteArray>

    companion object {
        /**
         * **Assumption.** `SHA-256(keyMaterial)`, first 16 bytes as the AES-128 key, next 12 as the
         * GCM nonce. Deterministic and testable, but not verified against `pairing_auth/aes_128_gcm.cpp`.
         */
        val UnverifiedDefault: AdbPairingKeySchedule = AdbPairingKeySchedule { keyMaterial ->
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(keyMaterial)
            digest.copyOfRange(0, 16) to digest.copyOfRange(16, 28)
        }
    }
}

/**
 * Drives one adb wireless-pairing attempt: TLS → SPAKE2 → encrypted peer info → device receipt.
 *
 * **Honesty contract.** [AdbPairingWireAssumptions] gates the whole flow: while any protocol decision
 * is unverified the controller returns [AdbPairingFailure.UNSUPPORTED] *before touching the network*.
 * Success requires a decrypted reply from the device; a written socket or a proud-looking log is never
 * enough.
 *
 * Concurrency: exactly one attempt at a time ([CONCURRENT_PAIRING]). The blocking [pair] call is meant
 * to be invoked from a background dispatcher; [cancel] flips a flag that is checked between steps and
 * closes the channel.
 */
class AdbPairingController(
    private val transport: AdbPairingTransport,
    private val assumptions: AdbPairingWireAssumptions = AdbPairingWireAssumptions.UNVERIFIED,
    private val keySchedule: AdbPairingKeySchedule = AdbPairingKeySchedule.UnverifiedDefault,
    private val random: SecureRandom = SecureRandom(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    private val inFlight = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** True while an attempt holds the single-flight slot. */
    fun isPairing(): Boolean = inFlight.get()

    /** Requests cancellation; checked between protocol steps. */
    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Runs the full pairing exchange.
     *
     * [pairingCode] is the six-digit code shown on the device; it is used only as raw SPAKE2 password
     * bytes and is never logged or included in a failure detail.
     */
    fun pair(host: String, port: Int, pairingCode: String): AdbPairingResult {
        val codeBytes = runCatching { validateInput(host, port, pairingCode) }.getOrElse {
            return AdbPairingResult.Failure(AdbPairingFailure.INVALID_INPUT)
        }
        if (!assumptions.isFullyVerified) {
            return AdbPairingResult.Failure(
                AdbPairingFailure.UNSUPPORTED,
                assumptions.missingVerifications()
            )
        }
        if (!inFlight.compareAndSet(false, true)) {
            return AdbPairingResult.Failure(AdbPairingFailure.CONCURRENT_PAIRING)
        }
        cancelled.set(false)
        val deadline = nowMillis() + OVERALL_TIMEOUT_MS
        var channel: AdbPairingChannel? = null
        try {
            if (cancelled.get()) return AdbPairingResult.Failure(AdbPairingFailure.CANCELLED)

            channel = runCatching {
                transport.connect(host, port, CONNECT_TIMEOUT_MS)
            }.getOrElse {
                return AdbPairingResult.Failure(AdbPairingFailure.TLS_FAILED)
            }

            val exporter = channel.exporterMaterial
            if (exporter.size != EXPORTER_BYTES) {
                return AdbPairingResult.Failure(AdbPairingFailure.TLS_FAILED, listOf("exporter_length"))
            }

            // AOSP folds the exporter material into the pairing code: `pswd_ += exportedKeyMaterial`.
            val password = codeBytes + exporter
            val alice = Spake2Curve25519.startForAdb(
                Spake2Curve25519Role.ALICE,
                password,
                random
            )

            abortReason(deadline)?.let { return AdbPairingResult.Failure(it) }
            val peerShare = exchangeShare(channel, alice.share)
                ?: return AdbPairingResult.Failure(AdbPairingFailure.SPAKE2_FAILED)
            val keyMaterial = alice.keyMaterial(peerShare)
                ?: return AdbPairingResult.Failure(AdbPairingFailure.SPAKE2_FAILED)

            val (key, nonce) = keySchedule.derive(keyMaterial)
            val cipher = AdbPairingCipher(key, nonce)

            abortReason(deadline)?.let { return AdbPairingResult.Failure(it) }
            val peerInfo = AdbPeerInfo(
                type = AdbPeerInfo.TYPE_ADB,
                publicKey = ownPublicKeyWireFormat,
                systemIdentity = SYSTEM_IDENTITY
            ).toProtobufBytes()
            val encrypted = cipher.encrypt(peerInfo)
            if (encrypted.size > AdbPairingLimits.MAX_ENCRYPTED_PEER_INFO_BYTES) {
                return AdbPairingResult.Failure(AdbPairingFailure.PEER_INFO_REJECTED, listOf("peer_info_too_large"))
            }
            runCatching {
                channel.writePacket(AdbPairingPacket.of(AdbPairingPacketType.PEER_INFO, encrypted))
            }.getOrElse {
                return AdbPairingResult.Failure(AdbPairingFailure.PEER_INFO_REJECTED)
            }

            abortReason(deadline)?.let { return AdbPairingResult.Failure(it) }
            val reply = runCatching { channel.readPacket() }.getOrNull()
                ?: return AdbPairingResult.Failure(AdbPairingFailure.PEER_INFO_REJECTED)
            if (reply.header.type != AdbPairingPacketType.PEER_INFO) {
                return AdbPairingResult.Failure(AdbPairingFailure.PEER_INFO_REJECTED)
            }
            val decrypted = cipher.decrypt(reply.payload)
                ?: return AdbPairingResult.Failure(AdbPairingFailure.PEER_INFO_REJECTED)
            return AdbPairingResult.Success(decrypted)
        } catch (cancelledError: InterruptedException) {
            Thread.currentThread().interrupt()
            return AdbPairingResult.Failure(AdbPairingFailure.CANCELLED)
        } finally {
            runCatching { channel?.close() }
            codeBytes.fill(0)
            inFlight.set(false)
            cancelled.set(false)
        }
    }

    private fun exchangeShare(channel: AdbPairingChannel, ourShare: ByteArray): ByteArray? {
        if (cancelled.get()) return null
        val written = runCatching {
            channel.writePacket(AdbPairingPacket.of(AdbPairingPacketType.SPAKE2_MSG, ourShare))
        }.isSuccess
        if (!written) return null
        val packet = runCatching { channel.readPacket() }.getOrNull() ?: return null
        if (packet.header.type != AdbPairingPacketType.SPAKE2_MSG) return null
        if (packet.payload.size != Spake2Curve25519.SHARE_SIZE) return null
        return packet.payload
    }

    /** Cancellation and the overall deadline share one check so every step aborts identically. */
    private fun abortReason(deadline: Long): AdbPairingFailure? = when {
        cancelled.get() -> AdbPairingFailure.CANCELLED
        nowMillis() > deadline -> AdbPairingFailure.TIMEOUT
        else -> null
    }

    private fun validateInput(host: String, port: Int, pairingCode: String): ByteArray {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65_535) { "port must be 1..65535" }
        require(pairingCode.length == PAIRING_CODE_DIGITS) { "pairing code must be six digits" }
        require(pairingCode.all { it in '0'..'9' }) { "pairing code must be numeric" }
        return pairingCode.toByteArray(Charsets.US_ASCII)
    }

    companion object {
        /** Connection (TLS handshake) budget, per the product requirement. */
        const val CONNECT_TIMEOUT_MS: Long = 5_000

        /** Whole-attempt budget, per the product requirement. */
        const val OVERALL_TIMEOUT_MS: Long = 20_000

        /** AOSP exports 64 bytes of keying material (`kExportedKeySize`). */
        const val EXPORTER_BYTES: Int = 64

        /** adb pairing codes are six digits. */
        const val PAIRING_CODE_DIGITS: Int = 6

        /** Identity we advertise in `PeerInfo::system_identity`; no user or device secrets. */
        const val SYSTEM_IDENTITY: String = "mca-adb-pairing"

        /**
         * Our adb RSA public key in adb's wire format. Injected by the composition root so this class
         * stays free of Android/key-store concerns; the default is empty only because a controller
         * built without an identity cannot produce valid peer info (and the verified gate normally
         * prevents that configuration from reaching the network).
         */
        @Volatile
        private var injectedPublicKey: ByteArray = ByteArray(0)

        /** Installs the adb public key wire format produced by [AdbIdentity]. */
        fun installPublicKeyWireFormat(publicKey: ByteArray) {
            injectedPublicKey = publicKey.copyOf()
        }

        internal val ownPublicKeyWireFormat: ByteArray
            get() = injectedPublicKey.copyOf()
    }
}

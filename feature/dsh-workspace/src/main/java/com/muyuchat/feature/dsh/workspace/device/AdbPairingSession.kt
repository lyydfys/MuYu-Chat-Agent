package com.muyuchat.feature.dsh.workspace.device

import java.security.SecureRandom

/**
 * Observable stage of one wireless-pairing attempt.
 *
 * The pipeline is `IDLE → SHARE_SENT → SHARE_RECEIVED → CONFIRMED`, and any unusable or out-of-order
 * input drops it to [REJECTED] for good (a pairing attempt is single-shot; retrying means a new
 * session, which is also what a keyboard-attack-resistant PAKE wants).
 *
 * [UNSUPPORTED] is the honest "this build cannot speak the wire protocol" state, not a failure.
 */
enum class AdbPairingState {
    /** Constructed; no share handed out yet. */
    IDLE,

    /** Our PAKE share exists and may be written to the transport. */
    SHARE_SENT,

    /** The peer's share was accepted and the PAKE key schedule is derived. */
    SHARE_RECEIVED,

    /** The peer's key-confirmation MAC verified. */
    CONFIRMED,

    /** A share/confirm was malformed, out of order, or the peer proved the wrong password. */
    REJECTED,

    /** This build has no PAKE/transport implementation to run. */
    UNSUPPORTED
}

/** Final classification of a pairing attempt. */
enum class PairingOutcome {
    /**
     * The PAKE and both key confirmations succeeded; the derived key is available and the caller may
     * hand the socket to a transport layer.
     */
    READY_FOR_TLS,

    /** The exchange was refused: bad point, empty/duplicate message, out-of-order call, wrong code. */
    REJECTED,

    /** No pairing implementation exists in this build. */
    UNSUPPORTED
}

/**
 * The transport half of a pairing: framing and shipping the two PAKE messages.
 *
 * Implementations must not reinterpret the messages. A real `adbd` pairing port would need TLS setup
 * (certificate handshake + exported keying material folded into the password), adb's
 * `PairingPacketHeader` framing and protobuf `PeerInfo` — none of that is implemented here, and this
 * interface deliberately stops short of it so the state machine can be tested with an in-memory
 * exchange.
 */
interface AdbPairingExchange {

    /** Sends our PAKE share and returns the peer's share bytes. */
    fun exchangeShare(ownShare: ByteArray): ByteArray

    /** Sends our key-confirmation MAC and returns the peer's. */
    fun exchangeConfirm(ownConfirm: ByteArray): ByteArray
}

/**
 * State machine for one wireless-ADB pairing attempt, driven by [Spake2P256].
 *
 * **What this is not.** It cannot pair with a real `adbd`. AOSP adb pairing
 * (`packages/modules/adb/pairing_auth/pairing_auth.cpp`) runs BoringSSL's **Curve25519** SPAKE2 with
 * the identities `"adb pair client"`/`"adb pair server"`, folds 64 bytes of TLS-exported keying
 * material into the pairing code, then encrypts `PeerInfo` with adb's own AES-128-GCM packets — no
 * P-256, no RFC 9382 transcript, and no TLS 1.3 external-PSK handshake. This session therefore
 * reports [PairingOutcome.READY_FOR_TLS] for a successful *RFC 9382* exchange only and never claims
 * wire compatibility. With no transport layer in this repository the real-device path stays
 * [PairingOutcome.UNSUPPORTED] via [unsupported].
 *
 * Single-shot and not thread-safe: one owner drives one session.
 */
class AdbPairingSession private constructor(
    private val engine: Spake2P256?
) {

    /** Current stage; see [AdbPairingState]. */
    var state: AdbPairingState = if (engine == null) AdbPairingState.UNSUPPORTED else AdbPairingState.IDLE
        private set

    private var result: Spake2Result? = null

    /** True only while the session can still accept messages. */
    val isActive: Boolean
        get() = state == AdbPairingState.IDLE ||
            state == AdbPairingState.SHARE_SENT ||
            state == AdbPairingState.SHARE_RECEIVED

    /**
     * Our public share (`pA`/`pB`, SEC1 uncompressed), or null for an unsupported session.
     *
     * Handing the share out moves [state] to [AdbPairingState.SHARE_SENT]. The bytes are public, so
     * a copy is returned and the caller may send it as often as the transport needs.
     */
    fun ownShare(): ByteArray? {
        val active = engine ?: return null
        if (state == AdbPairingState.IDLE) state = AdbPairingState.SHARE_SENT
        return active.share.copyOf()
    }

    /**
     * Accepts the peer's share and derives the key schedule.
     *
     * Returns false and moves the session to [AdbPairingState.REJECTED] for an empty share, a
     * malformed/off-curve/out-of-range point, the SEC1 identity encoding, a share that unmasks to the
     * identity, or a second share after one was already accepted.
     */
    fun acceptPeerShare(peerShare: ByteArray): Boolean {
        val active = engine ?: return unsupported()
        if (state != AdbPairingState.IDLE && state != AdbPairingState.SHARE_SENT) return reject()
        if (peerShare.isEmpty()) return reject()
        val derived = active.complete(peerShare) ?: return reject()
        result = derived
        state = AdbPairingState.SHARE_RECEIVED
        return true
    }

    /** Our confirmation MAC (`cA`/`cB`), or null until the peer's share has been accepted. */
    fun ownConfirm(): ByteArray? = result?.ownConfirm?.copyOf()

    /**
     * Verifies the peer's confirmation MAC in constant time.
     *
     * Only valid once, and only after a share was accepted; a duplicate or premature call is
     * [PairingOutcome.REJECTED] so a replayed confirmation cannot resurrect a finished attempt.
     */
    fun acceptPeerConfirm(peerConfirm: ByteArray): PairingOutcome {
        engine ?: return unsupportedOutcome()
        val derived = result
        if (state != AdbPairingState.SHARE_RECEIVED || derived == null) return rejectOutcome()
        if (peerConfirm.isEmpty() || !derived.verifyPeerConfirm(peerConfirm)) return rejectOutcome()
        state = AdbPairingState.CONFIRMED
        return PairingOutcome.READY_FOR_TLS
    }

    /**
     * The SPAKE2 shared key (`Ke`), or null until [state] is [AdbPairingState.CONFIRMED].
     *
     * Withheld until the peer's confirmation verified on purpose: an unconfirmed PAKE key must never
     * be used to protect real traffic.
     */
    fun sharedKey(): ByteArray? =
        if (state == AdbPairingState.CONFIRMED) result?.sharedKey?.copyOf() else null

    /** Classification of the attempt so far; null while the exchange is still in progress. */
    fun outcome(): PairingOutcome? = when (state) {
        AdbPairingState.CONFIRMED -> PairingOutcome.READY_FOR_TLS
        AdbPairingState.REJECTED -> PairingOutcome.REJECTED
        AdbPairingState.UNSUPPORTED -> PairingOutcome.UNSUPPORTED
        else -> null
    }

    /**
     * Runs the whole two-round exchange over [exchange].
     *
     * A transport that throws is treated as a rejection, not as a crash: pairing failures must stay a
     * typed outcome on a path the UI can show.
     */
    fun runExchange(exchange: AdbPairingExchange): PairingOutcome {
        val active = engine ?: return unsupportedOutcome()
        if (state != AdbPairingState.IDLE) return rejectOutcome()
        val share = ownShare() ?: return rejectOutcome()
        val peerShare = runCatching { exchange.exchangeShare(share) }.getOrElse { return rejectOutcome() }
        if (!acceptPeerShare(peerShare)) return rejectOutcome()
        val ownConfirm = ownConfirm() ?: return rejectOutcome()
        val peerConfirm = runCatching { exchange.exchangeConfirm(ownConfirm) }.getOrElse {
            return rejectOutcome()
        }
        return acceptPeerConfirm(peerConfirm)
    }

    private fun reject(): Boolean {
        state = AdbPairingState.REJECTED
        return false
    }

    private fun rejectOutcome(): PairingOutcome {
        state = AdbPairingState.REJECTED
        return PairingOutcome.REJECTED
    }

    private fun unsupported(): Boolean {
        state = AdbPairingState.UNSUPPORTED
        return false
    }

    private fun unsupportedOutcome(): PairingOutcome {
        state = AdbPairingState.UNSUPPORTED
        return PairingOutcome.UNSUPPORTED
    }

    companion object {
        /** Identity role A, matching the label adb uses for the host side. */
        val CLIENT_IDENTITY: ByteArray = "adb pair client".toByteArray(Charsets.US_ASCII)

        /** Identity role B, matching the label adb uses for the device side. */
        val SERVER_IDENTITY: ByteArray = "adb pair server".toByteArray(Charsets.US_ASCII)

        /**
         * Host side (role A) of an RFC 9382 exchange.
         *
         * [random] and [suite] are injectable for tests and for a future Curve25519 rewrite; the
         * defaults are a fresh [SecureRandom] and the RFC 9382 P-256 suite.
         */
        fun client(
            pairingCode: String,
            policy: Spake2PasswordPolicy = Spake2PasswordPolicy(),
            random: SecureRandom = SecureRandom(),
            suite: Spake2Suite = Spake2Suite.RFC9382_P256_SHA256
        ): AdbPairingSession = AdbPairingSession(
            Spake2P256.start(
                role = Spake2Role.A,
                identityA = CLIENT_IDENTITY,
                identityB = SERVER_IDENTITY,
                password = pairingCode.toByteArray(Charsets.UTF_8),
                policy = policy,
                random = random,
                suite = suite
            )
        )

        /** Device side (role B); otherwise identical to [client]. */
        fun server(
            pairingCode: String,
            policy: Spake2PasswordPolicy = Spake2PasswordPolicy(),
            random: SecureRandom = SecureRandom(),
            suite: Spake2Suite = Spake2Suite.RFC9382_P256_SHA256
        ): AdbPairingSession = AdbPairingSession(
            Spake2P256.start(
                role = Spake2Role.B,
                identityA = CLIENT_IDENTITY,
                identityB = SERVER_IDENTITY,
                password = pairingCode.toByteArray(Charsets.UTF_8),
                policy = policy,
                random = random,
                suite = suite
            )
        )

        /**
         * Explicitly unsupported session: no PAKE transport exists in this build.
         *
         * This is the state a real-device flow must report today — a UI that shows "pairing" without
         * a TLS/PSK (or adb AES-GCM) transport would be lying about a capability that is not wired.
         */
        fun unsupported(): AdbPairingSession = AdbPairingSession(null)
    }
}

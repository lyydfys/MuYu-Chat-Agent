package com.muyuchat.core.dsh.vscreen

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper

/**
 * Android-free bookkeeping of the one screen-capture grant MCA holds.
 *
 * The grant is not a boolean cached from the environment: it is created by a real
 * [MediaProjection] accepted from the system consent dialog, and it is dropped when the system
 * stops the projection or when the owner releases it. An *unused* grant expires after
 * [DshVscreenLimits.PROJECTION_GRANT_TTL_MILLIS] so a stale consent cannot be replayed much later;
 * once a display was created the grant stays valid until it is released.
 *
 * This class is the only writer of the grant generation, mirroring the display lease discipline of
 * [VirtualScreenOwner].
 */
class DshVscreenCaptureLedger(
    private val ttlMillis: Long = DshVscreenLimits.PROJECTION_GRANT_TTL_MILLIS
) {
    init {
        require(ttlMillis > 0L) { "grant ttl must be positive" }
    }

    private var generation: Long = 0L
    private var granted: Boolean = false
    private var grantedAtMillis: Long = 0L
    private var inUse: Boolean = false
    private var createdDisplays: Int = 0

    @Synchronized
    fun grant(nowMillis: Long): Long {
        generation += 1L
        granted = true
        grantedAtMillis = nowMillis
        inUse = false
        createdDisplays = 0
        return generation
    }

    /** Drops the grant; returns the revoked generation, or null when nothing was granted. */
    @Synchronized
    fun revoke(): Long? {
        val previous = if (granted) generation else null
        granted = false
        inUse = false
        createdDisplays = 0
        return previous
    }

    @Synchronized
    fun recordDisplayCreated(): Int {
        if (!granted) return createdDisplays
        createdDisplays += 1
        inUse = true
        return createdDisplays
    }

    @Synchronized
    fun isGranted(nowMillis: Long): Boolean =
        granted && (inUse || (nowMillis - grantedAtMillis) in 0..ttlMillis)

    @Synchronized
    fun createdDisplayCount(): Int = createdDisplays

    @Synchronized
    fun activeGeneration(): Long? = if (granted) generation else null
}

/** A live projection, or the typed reason creation cannot even be attempted. */
sealed interface DshVscreenProjectionAccess {
    data class Granted(val projection: MediaProjection) : DshVscreenProjectionAccess

    data class Unavailable(
        val code: DshVscreenErrorCode,
        val detail: String
    ) : DshVscreenProjectionAccess
}

/** Narrow seam between the display adapter and the consent owner. */
fun interface DshVscreenProjectionSource {
    fun acquire(): DshVscreenProjectionAccess
}

/** Outcome of accepting a consent result. The detail is bounded and secret-free. */
sealed interface DshVscreenProjectionGrantResult {
    data class Granted(val generation: Long) : DshVscreenProjectionGrantResult

    data class Rejected(
        val code: DshVscreenErrorCode,
        val detail: String
    ) : DshVscreenProjectionGrantResult
}

/**
 * Owns the app's screen-capture projection.
 *
 * Lifecycle: the app composition starts the mediaProjection foreground service, then hands the
 * consent result here ([grant]); the display adapter borrows the projection through
 * [DshVscreenProjectionSource.acquire]; [release] stops the projection and revokes the grant on a
 * confirmed host stop or an explicit user action. The system can stop the projection at any time
 * (user revoke, another capture taking over); [MediaProjection.Callback.onStop] then clears the
 * grant so the next create reports `consent_required` instead of using a dead token.
 */
class DshVscreenProjectionHolder(
    context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : DshVscreenProjectionSource {

    private val appContext = context.applicationContext
    private val ledger = DshVscreenCaptureLedger()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null

    private val systemStopCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            synchronized(this@DshVscreenProjectionHolder) {
                projection = null
                ledger.revoke()
            }
        }
    }

    fun grant(resultCode: Int, data: Intent): DshVscreenProjectionGrantResult {
        val manager = appContext.getSystemService(MediaProjectionManager::class.java)
            ?: return DshVscreenProjectionGrantResult.Rejected(
                DshVscreenErrorCode.PRIVILEGE_UNAVAILABLE,
                "the media-projection service is unavailable in this build"
            )
        val accepted: MediaProjection? = try {
            manager.getMediaProjection(resultCode, data)
        } catch (security: SecurityException) {
            null
        } catch (failure: RuntimeException) {
            null
        }
        if (accepted == null) {
            return DshVscreenProjectionGrantResult.Rejected(
                DshVscreenErrorCode.CONSENT_REQUIRED,
                "the system did not hand over a screen-capture projection (resultCode=$resultCode); " +
                    "on Android 14+ the mediaProjection foreground service must already be running"
            )
        }
        val previous = synchronized(this) {
            val old = projection
            projection = accepted
            old
        }
        if (previous != null && previous !== accepted) {
            runCatching { previous.unregisterCallback(systemStopCallback) }
            runCatching { previous.stop() }
        }
        val registered = try {
            accepted.registerCallback(systemStopCallback, mainHandler)
            true
        } catch (failure: RuntimeException) {
            false
        }
        if (!registered) {
            synchronized(this) { if (projection === accepted) projection = null }
            runCatching { accepted.stop() }
            return DshVscreenProjectionGrantResult.Rejected(
                DshVscreenErrorCode.INTERNAL,
                "the projection stop-callback could not be registered"
            )
        }
        return DshVscreenProjectionGrantResult.Granted(ledger.grant(clock()))
    }

    override fun acquire(): DshVscreenProjectionAccess {
        val now = clock()
        val current = synchronized(this) { projection }
        if (current == null) {
            return DshVscreenProjectionAccess.Unavailable(
                DshVscreenErrorCode.CONSENT_REQUIRED,
                "screen-capture consent has not been granted; the in-app capture flow must run first"
            )
        }
        if (!ledger.isGranted(now)) {
            release()
            return DshVscreenProjectionAccess.Unavailable(
                DshVscreenErrorCode.CONSENT_REQUIRED,
                "the unused screen-capture grant expired; request consent again"
            )
        }
        return DshVscreenProjectionAccess.Granted(current)
    }

    /** Called by the display adapter after the system returned a real virtual display. */
    fun recordDisplayCreated(): Int = ledger.recordDisplayCreated()

    fun isGranted(): Boolean = ledger.isGranted(clock()) && synchronized(this) { projection != null }

    /**
     * Display-source evidence. `PROVEN` is only reported after a display was actually created with
     * this grant in this process; a consent alone stays `UNPROVEN` and says so.
     */
    fun evidence(): DshVscreenCaptureEvidence {
        val manager = appContext.getSystemService(MediaProjectionManager::class.java)
            ?: return DshVscreenCaptureEvidence.unsupported(
                "screen capture is unavailable in this build (no MediaProjectionManager)"
            )
        val granted = isGranted()
        val created = ledger.createdDisplayCount()
        return when {
            granted && created > 0 -> DshVscreenCaptureEvidence(
                source = DshVscreenDisplaySource.PROJECTION,
                proof = DshVscreenProof.PROVEN,
                detail = "screen capture is granted and a virtual display was created on this device"
            )

            granted -> DshVscreenCaptureEvidence(
                source = DshVscreenDisplaySource.PROJECTION,
                proof = DshVscreenProof.UNPROVEN,
                detail = "screen capture is granted; the display path has not succeeded since the grant"
            )

            else -> DshVscreenCaptureEvidence(
                source = DshVscreenDisplaySource.PROJECTION,
                proof = DshVscreenProof.UNPROVEN,
                detail = "screen capture is available but no consent is granted yet"
            )
        }
    }

    /** Idempotent teardown: revokes the grant and stops the projection it owns. */
    fun release() {
        val current = synchronized(this) {
            val old = projection
            projection = null
            old
        }
        ledger.revoke()
        current?.let { started ->
            runCatching { started.unregisterCallback(systemStopCallback) }
            runCatching { started.stop() }
        }
    }
}

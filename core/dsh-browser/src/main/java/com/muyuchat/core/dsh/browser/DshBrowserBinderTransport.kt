package com.muyuchat.core.dsh.browser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cross-process transport from the App-local server process to [DshBrowserService] in `:browser`.
 *
 * The payload is the untouched `/browser` envelope; the service owns the strict parser and the
 * session. A caller cancellation sends one cancel message carrying the request id, so an in-flight
 * click is aborted in the browser process rather than silently completing after the HTTP caller
 * went away.
 *
 * If the service process is killed, the next call simply rebinds (the OS starts a fresh
 * `:browser`); [onServiceDisconnected] drops the cached messenger so no stale binder is reused.
 */
class DshBrowserBinderTransport(private val context: Context) : DshBrowserTransport {

    private val connectLock = Mutex()

    @Volatile
    private var remote: Messenger? = null

    @Volatile
    private var connection: ServiceConnection? = null

    override suspend fun call(bodyJson: String): String {
        val messenger = connect()
        val requestId = UUID.randomUUID().toString()
        val answer = withTimeoutOrNull(CALL_GUARD_MILLIS) { sendAwait(messenger, requestId, bodyJson) }
        if (answer == null) {
            return DshBrowserJson.failure(
                DshBrowserFailure(
                    DshBrowserFailureReason.TIMEOUT,
                    "The browser process did not answer within the call budget",
                    "retry once; the page may be stuck in a load or a dialog"
                )
            ).toString()
        }
        return answer
    }

    /** Sends one release message and drops the binding. Safe to call twice. */
    suspend fun shutdown() {
        val messenger = remote ?: return
        runCatching {
            messenger.send(
                Message.obtain(null, DshBrowserService.MSG_RELEASE).apply {
                    data = Bundle()
                }
            )
        }
        disconnect()
    }

    private fun disconnect() {
        val bound = connection
        connection = null
        remote = null
        if (bound != null) runCatching { context.unbindService(bound) }
    }

    private suspend fun connect(): Messenger {
        remote?.let { return it }
        return connectLock.withLock {
            remote?.let { return@withLock it }
            val messenger = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { awaitBinding() }
                ?: throw DshBrowserEngineException(
                    DshBrowserEngineError.UNAVAILABLE,
                    "the :browser service did not publish a binder"
                )
            remote = messenger
            messenger
        }
    }

    private suspend fun awaitBinding(): Messenger = suspendCancellableCoroutine { continuation ->
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            DshBrowserEngineException(
                                DshBrowserEngineError.UNAVAILABLE,
                                "the :browser service returned no binder"
                            )
                        )
                    }
                    return
                }
                if (continuation.isActive) continuation.resume(Messenger(binder))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
                connection = null
            }
        }
        connection = serviceConnection
        val intent = Intent(context, DshBrowserService::class.java)
        val bound = runCatching { context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!bound) {
            connection = null
            if (continuation.isActive) {
                continuation.resumeWithException(
                    DshBrowserEngineException(
                        DshBrowserEngineError.UNAVAILABLE,
                        "the :browser service is not declared in the merged manifest"
                    )
                )
            }
        }
        continuation.invokeOnCancellation {
            if (continuation.isActive.not()) {
                runCatching { context.unbindService(serviceConnection) }
            }
        }
    }

    private suspend fun sendAwait(
        messenger: Messenger,
        requestId: String,
        bodyJson: String
    ): String = suspendCancellableCoroutine { continuation ->
        val replyHandler = Handler(Looper.getMainLooper()) { message ->
            val result = message.data?.getString(DshBrowserService.KEY_RESULT)
            if (result != null && continuation.isActive) continuation.resume(result)
            true
        }
        val request = Message.obtain(null, DshBrowserService.MSG_CALL)
        request.data = Bundle().apply {
            putString(DshBrowserService.KEY_BODY, bodyJson)
            putString(DshBrowserService.KEY_REQUEST_ID, requestId)
        }
        request.replyTo = Messenger(replyHandler)
        continuation.invokeOnCancellation {
            runCatching {
                messenger.send(
                    Message.obtain(null, DshBrowserService.MSG_CANCEL).apply {
                        data = Bundle().apply { putString(DshBrowserService.KEY_REQUEST_ID, requestId) }
                    }
                )
            }
        }
        try {
            messenger.send(request)
        } catch (error: RemoteException) {
            if (continuation.isActive) {
                continuation.resumeWithException(
                    DshBrowserEngineException(
                        DshBrowserEngineError.UNAVAILABLE,
                        "the :browser service is not reachable"
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 10_000L

        /** Guard above the protocol's own per-call budget (120 s hard ceiling). */
        private const val CALL_GUARD_MILLIS = 125_000L
    }
}

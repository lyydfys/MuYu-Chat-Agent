package com.muyuchat.core.dsh.browser

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.provider.Settings
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Browser host service, declared with `android:process=":browser"` in this module's manifest.
 *
 * Why a separate process: a page that crashes the renderer then kills only `:browser`; the DSH
 * engine (its own Node process), the App UI and the capability server keep running. Closing the
 * browser releases every `WebView` and the process becomes reclaimable.
 *
 * Honest scope of that claim, from the DSH V2 architecture record: a separate process is
 * crash-containment, not a security boundary 鈥?it still shares the app UID and cannot be treated
 * as a sandbox for hostile plugins. `caps.processIsolation` reports the containment fact, not a
 * security guarantee.
 *
 * IPC is a `Messenger` with the *same JSON envelope* the App-local `/browser` route receives; the
 * service runs the identical strict parser and session, so the wire cannot drift between the
 * in-process and cross-process paths. Each call carries a request id so a cancellation on the
 * caller side can abort the in-flight page operation instead of leaking it.
 */
class DshBrowserService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = ConcurrentHashMap<String, Job>()
    private var opThread: HandlerThread? = null
    private var messenger: Messenger? = null
    private var session: DshBrowserSession? = null
    private var profileError: String? = null

    override fun onCreate() {
        super.onCreate()
        // Must run before *any* WebView API in this process. Android forbids two processes of one
        // app sharing a WebView data directory, and this suffix also isolates the browser profile
        // (cookies/DOM storage) from the DSH WebView profile.
        profileError = try {
            WebView.setDataDirectorySuffix(DshBrowserProcessPolicy.DATA_DIRECTORY_SUFFIX)
            null
        } catch (error: Throwable) {
            "webview data directory suffix was rejected in :browser"
        }
        val thread = HandlerThread("dsh-browser-op")
        thread.start()
        opThread = thread
        messenger = Messenger(
            Handler(thread.looper) { message ->
                when (message.what) {
                    MSG_CALL -> onCall(message)
                    MSG_CANCEL -> onCancel(message)
                    MSG_RELEASE -> onRelease(message)
                    else -> Unit
                }
                true
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        // Closing owns WebView teardown, which must run on the main thread *after* onDestroy
        // returns, so it gets a short-lived scope that is not cancelled here.
        val closing = session
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { closing?.close() }
        serviceScope.cancel()
        opThread?.quitSafely()
        opThread = null
        session = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        onRelease(null)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /** One request: body JSON in, response JSON out. Runs on the op thread, not the main thread. */
    private fun onCall(message: Message) {
        val data = message.data ?: return
        val body = data.getString(KEY_BODY) ?: return
        val requestId = data.getString(KEY_REQUEST_ID) ?: UUID.randomUUID().toString()
        val replyTo = message.replyTo ?: return
        val job = serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val answer = try {
                val active = ensureSession()
                DshBrowserRequestHandler(active).handleBody(body)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                DshBrowserJson.failure(
                    DshBrowserFailure(
                        DshBrowserFailureReason.ENGINE_UNAVAILABLE,
                        "The browser process could not serve the request"
                    )
                ).toString()
            } finally {
                jobs.remove(requestId)
            }
            reply(replyTo, clamp(answer))
        }
        jobs[requestId] = job
        job.start()
    }

    private fun onCancel(message: Message) {
        val requestId = message.data?.getString(KEY_REQUEST_ID) ?: return
        jobs.remove(requestId)?.cancel()
    }

    private fun onRelease(message: Message?) {
        serviceScope.launch {
            session?.close()
            session = null
            val target = message?.replyTo
            if (target != null) {
                reply(
                    target,
                    DshBrowserJson.failure(
                        DshBrowserFailure(
                            DshBrowserFailureReason.BROWSER_CLOSED,
                            "The browser session is closed"
                        )
                    ).toString()
                )
            }
        }
    }

    private fun ensureSession(): DshBrowserSession {
        session?.let { return it }
        val overlay = Settings.canDrawOverlays(this)
        if (profileError != null) {
            throw DshBrowserEngineException(DshBrowserEngineError.UNAVAILABLE, profileError!!)
        }
        val isolation = DshBrowserProcessPolicy.decide(
            processDeclared = true,
            overlayGranted = overlay,
            engineAvailable = true
        )
        val created = DshBrowserWebViewEngine(this)
        val owner = DshBrowserSession(created, isolation)
        session = owner
        return owner
    }

    private fun reply(to: Messenger, body: String) {
        val out = Message.obtain(null, MSG_CALL)
        out.data = Bundle().apply { putString(KEY_RESULT, body) }
        runCatching { to.send(out) }
    }

    /** Binder transactions are ~1 MiB; the protocol budget is below that, this is a last guard. */
    private fun clamp(body: String): String =
        if (body.length <= DshBrowserLimits.MAX_RESPONSE_BYTES) {
            body
        } else {
            DshBrowserJson.failure(
                DshBrowserFailure(
                    DshBrowserFailureReason.TOO_LARGE,
                    "The browser response exceeded the contract budget"
                )
            ).toString()
        }

    companion object {
        const val MSG_CALL = 1
        const val MSG_CANCEL = 2
        const val MSG_RELEASE = 3
        const val KEY_BODY = "body"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_RESULT = "result"
    }
}

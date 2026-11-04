package com.muyuchat.mca.runtime

import android.content.Context
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Persisted DSH runtime preferences: the Web port and the refresh-rate ceiling.
 *
 * Storage is a tiny key/value port so the codec is testable without Android; the production adapter is
 * SharedPreferences. Values are validated on read *and* write: a hand-edited preference can never make
 * the runtime bind a reserved port or request a rate this build does not offer.
 */
internal data class McaRuntimeSettings(
    /** 0 keeps the official behaviour ("let the runtime pick"), 1024–65535 pins a port. */
    val webPort: Int = DEFAULT_WEB_PORT,
    val frameRate: FrameRatePreference = FrameRatePreference.FOLLOW_SYSTEM
) {
    companion object {
        const val DEFAULT_WEB_PORT: Int = 0
    }
}

/** Minimal key/value seam over SharedPreferences. */
internal interface McaRuntimeSettingsStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

internal object McaRuntimeSettingsCodec {

    const val KEY_WEB_PORT: String = "dsh_web_port"
    const val KEY_FRAME_RATE: String = "dsh_frame_rate"

    /** Trimmed, digits only; null or blank keeps the default (runtime picks the port). */
    fun parseWebPort(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return McaRuntimeSettings.DEFAULT_WEB_PORT
        val port = text.toIntOrNull() ?: return null
        return port.takeIf { RuntimePortPlanner.isValidPort(it) || it == McaRuntimeSettings.DEFAULT_WEB_PORT }
    }

    fun parseFrameRate(raw: String?): FrameRatePreference = FrameRatePreference.fromWireName(raw)

    /** Applies what was stored; an invalid stored port falls back to the default instead of failing. */
    fun read(store: McaRuntimeSettingsStore): McaRuntimeSettings = McaRuntimeSettings(
        webPort = parseWebPort(store.read(KEY_WEB_PORT)) ?: McaRuntimeSettings.DEFAULT_WEB_PORT,
        frameRate = parseFrameRate(store.read(KEY_FRAME_RATE))
    )

    /** Returns the rejected reason when [webPort] is not a usable preference. */
    fun write(
        store: McaRuntimeSettingsStore,
        webPort: Int,
        frameRate: FrameRatePreference
    ): String? {
        if (webPort != McaRuntimeSettings.DEFAULT_WEB_PORT && !RuntimePortPlanner.isValidPort(webPort)) {
            return "端口必须在 ${RuntimePortPlanner.MIN_PORT}–${RuntimePortPlanner.MAX_PORT} 之间"
        }
        store.write(KEY_WEB_PORT, webPort.toString())
        store.write(KEY_FRAME_RATE, frameRate.name)
        return null
    }
}

/** Production store; one SharedPreferences file owned by the runtime feature. */
internal class AndroidMcaRuntimeSettingsStore(context: Context) : McaRuntimeSettingsStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("mca_dsh_runtime", Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/**
 * Binds a loopback socket to test availability.
 *
 * [probe] is the production probe: `ServerSocket` with `reuseAddress`, a 1 s connect timeout and an
 * explicit close. Any failure (bind refused, timeout, security exception) means "not available", so a
 * port is never chosen optimistically.
 */
internal class McaRuntimePortProbe(
    private val bind: (Int, Int) -> Boolean = ::bindLoopback
) : AutoCloseable {

    fun isAvailable(port: Int): Boolean {
        if (port == 0) return true // "let the runtime pick" is always acceptable
        if (!RuntimePortPlanner.isValidPort(port)) return false
        return runCatching { bind(port, PROBE_TIMEOUT_MS) }.getOrDefault(false)
    }

    /** Planner entry point: probes candidate ports in the documented order. */
    fun choose(preferred: Int): RuntimePortChoice =
        RuntimePortPlanner.choose(preferred, ::isAvailable)

    override fun close() = Unit

    companion object {
        const val PROBE_TIMEOUT_MS: Int = 1_000

        /** Binds to 127.0.0.1, then closes immediately; true only when the bind succeeded. */
        fun bindLoopback(port: Int, timeoutMs: Int): Boolean {
            val socket = ServerSocket()
            return try {
                socket.reuseAddress = true
                socket.soTimeout = timeoutMs
                socket.bind(InetSocketAddress("127.0.0.1", port), 1)
                true
            } catch (error: IOException) {
                false
            } catch (error: SecurityException) {
                false
            } finally {
                runCatching { socket.close() }
            }
        }
    }
}

/** Cheap gateway/Web liveness check used by the watchdog and by the self-check rows. */
internal class McaRuntimeTcpProbe(
    private val open: (String, Int, Int) -> Boolean = ::connectLoopback
) {
    fun isReachable(port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Boolean {
        if (port !in RuntimePortPlanner.MIN_PORT..RuntimePortPlanner.MAX_PORT) return false
        return runCatching { open("127.0.0.1", port, timeoutMs) }.getOrDefault(false)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Int = 1_000

        fun connectLoopback(host: String, port: Int, timeoutMs: Int): Boolean {
            val socket = Socket()
            return try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            } catch (error: IOException) {
                false
            } catch (error: SecurityException) {
                false
            } finally {
                runCatching { socket.close() }
            }
        }

        /** Binds a loopback listener on a background thread for tests; returns the port and a closer. */
        internal fun startLoopbackListener(): Pair<Int, () -> Unit> {
            val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val worker = thread(isDaemon = true) {
                runCatching { server.accept()?.close() }
            }
            return server.localPort to {
                runCatching { server.close() }
                runCatching { worker.interrupt() }
                Unit
            }
        }
    }
}

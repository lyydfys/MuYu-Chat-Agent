package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityAuthority
import com.muyuchat.core.dsh.contract.capability.DshCapabilityToken
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmissionGate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Network options of the loopback adapter. Port 0 asks the OS for an ephemeral loopback port. */
data class DshCapabilityServerOptions(
    val bindPort: Int = 0,
    val backlog: Int = 16,
    val clientReadTimeoutMillis: Int = 10_000,
    val drainTimeoutMillis: Long = 2_000L
) {
    init {
        require(bindPort in 0..65_535) { "capability bind port is out of range" }
        require(backlog >= 1) { "capability accept backlog must be positive" }
        require(clientReadTimeoutMillis in 1..120_000) { "capability client read timeout is out of range" }
        require(drainTimeoutMillis in 0L..30_000L) { "capability drain timeout is out of range" }
    }
}

/**
 * What the composition root needs to seed the official DSH tool packages.
 *
 * It carries the per-generation token object, never a raw string in logs; [toString] is redacted.
 * Seed `APP_NOTIFY_PORT = port` and `APP_LOCAL_TOKEN = token.value` only into the managed DSH
 * process environment (or [DshCapabilityBridgeEndpoint.environment]).
 */
data class DshCapabilityLoopbackBinding(
    val port: Int,
    val generation: Long,
    val token: DshCapabilityToken
) {
    init {
        require(port in 1..65_535) { "capability loopback port is out of range" }
        require(generation >= 1L) { "capability binding generation must be positive" }
    }

    val baseUrl: String
        get() = "http://127.0.0.1:$port"

    override fun toString(): String =
        "DshCapabilityLoopbackBinding(port=$port, generation=$generation, token=redacted)"
}

/**
 * Thin Android adapter: an authenticated HTTP/1.1 server bound to `127.0.0.1` only.
 *
 * It contains no business rule. Dispatch, authentication, idempotency and limits are decided by
 * [DshCapabilityServerCore]; this class owns the socket lifecycle: accept loop, per-connection
 * coroutine, bounded read timeout, response write and graceful stop.
 *
 * Wiring (composition root):
 * ```kotlin
 * val authority = DshCapabilityAuthority()
 * val grant = authority.startGeneration(generation = nextGeneration, capabilities = setOf(...))
 * val routes = DshCapabilityRouteRegistry.builder()
 *     .post("/notify", DshCapability.ANDROID_SYSTEM, notifyHandler)
 *     .get("/usage", DshCapability.ANDROID_SYSTEM, usageHandler)
 *     .build()
 * val ledger = DshCapabilityLedger()              // shared across generations
 * val server = DshCapabilityLoopbackServer(
 *     authority = authority,
 *     registry = routes,
 *     ledger = ledger,
 *     throttle = DshCapabilityThrottle(),        // one per generation
 *     options = DshCapabilityServerOptions()
 * )
 * val binding = server.start()                    // suspend; binds 127.0.0.1 ephemeral
 * // seed APP_NOTIFY_PORT=binding.port, APP_LOCAL_TOKEN=binding.token.value
 * server.stop()                                   // suspend; idempotent
 * ```
 *
 * Owner: the runtime/platform owner creates it, publishes [binding] for one generation and calls
 * [stop] before the generation is torn down. [start] may run once per instance; rotation is a new
 * instance, so a stale endpoint cannot survive into the next generation.
 *
 * Security: the token is never logged, the body is never logged, and only loopback accepts
 * connections because the socket is bound to [LOOPBACK_HOST]. This adapter requires no external
 * dependency beyond the JDK.
 */
class DshCapabilityLoopbackServer(
    private val authority: DshCapabilityAuthority,
    private val registry: DshCapabilityRouteRegistry,
    private val ledger: DshCapabilityLedger,
    private val throttle: DshCapabilityThrottle,
    private val options: DshCapabilityServerOptions = DshCapabilityServerOptions(),
    private val requestTimeoutMillis: Long = 15_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logSink: (String) -> Unit = {},
    /** See [DshCapabilityServerCore]: a per-path budget for an intrinsically polling family. */
    private val budgetFor: (String) -> DshCapabilityThrottle = { throttle },
    /** Shared maintenance admission; every request executes under one MUTATION lease. */
    private val maintenance: DshMaintenanceAdmissionGate? = null
) {
    private enum class ServerState { NEW, RUNNING, STOPPED }

    private val stateLock = Any()
    private val activeSockets: MutableSet<Socket> =
        Collections.synchronizedSet(LinkedHashSet<Socket>())
    private var state = ServerState.NEW
    private var serverSocket: ServerSocket? = null
    private var serverScope: CoroutineScope? = null
    private var serverJob: Job? = null
    private var acceptJob: Job? = null
    private var currentBinding: DshCapabilityLoopbackBinding? = null

    /** The published binding of the running server, or null before start / after stop. */
    fun binding(): DshCapabilityLoopbackBinding? = synchronized(stateLock) { currentBinding }

    /**
     * Binds `127.0.0.1:<options.bindPort>` and starts serving. Fails with
     * [IllegalStateException] when no generation is active or the server was already used.
     */
    suspend fun start(): DshCapabilityLoopbackBinding {
        val grant = authority.currentGrant()
            ?: throw IllegalStateException("capability authority has no active generation")
        synchronized(stateLock) {
            check(state == ServerState.NEW) { "capability loopback server can only be started once" }
            val socket = bindLoopbackSocket()
            val rootJob = SupervisorJob()
            val scope = CoroutineScope(rootJob + Dispatchers.IO)
            val core = DshCapabilityServerCore(
                authority = authority,
                registry = registry,
                ledger = ledger,
                throttle = throttle,
                requestTimeoutMillis = requestTimeoutMillis,
                clock = clock,
                budgetFor = budgetFor,
                maintenance = maintenance
            )
            val binding = DshCapabilityLoopbackBinding(
                port = socket.localPort,
                generation = grant.generation,
                token = grant.token
            )
            serverSocket = socket
            serverScope = scope
            serverJob = rootJob
            acceptJob = scope.launch { acceptLoop(socket, core, scope) }
            currentBinding = binding
            state = ServerState.RUNNING
            logSink(
                "capability loopback server started port=${binding.port} " +
                    "generation=${binding.generation}"
            )
            return binding
        }
    }

    /**
     * Stops accepting, closes live sockets and joins the accept loop. Idempotent: the first call
     * returns true, later calls return false. Never throws for an already-closed socket.
     */
    suspend fun stop(): Boolean {
        var wasRunning = false
        var socket: ServerSocket? = null
        var scope: CoroutineScope? = null
        var job: Job? = null
        var rootJob: Job? = null
        synchronized(stateLock) {
            if (state == ServerState.RUNNING) {
                wasRunning = true
                socket = serverSocket
                scope = serverScope
                job = acceptJob
                rootJob = serverJob
                state = ServerState.STOPPED
                currentBinding = null
            }
        }
        if (!wasRunning) return false
        runCatching { socket?.close() }
        // Drain bounded: an already-reading request may finish its handler and receive the
        // response; anything still live after the deadline is cancelled below and the ledger
        // settles it as unknown through the handler's cancellation path.
        val drained = withTimeoutOrNull(options.drainTimeoutMillis) {
            while (synchronized(activeSockets) { activeSockets.isNotEmpty() }) {
                delay(DRAIN_POLL_MILLIS)
            }
            true
        }
        if (drained == null) {
            logSink("capability loopback server drain timed out")
        }
        synchronized(activeSockets) {
            activeSockets.forEach { client -> runCatching { client.close() } }
            activeSockets.clear()
        }
        scope?.cancel()
        job?.cancelAndJoin()
        rootJob?.cancelAndJoin()
        logSink("capability loopback server stopped")
        return true
    }

    private fun bindLoopbackSocket(): ServerSocket {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(
                InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), options.bindPort),
                options.backlog
            )
            return socket
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            throw failure
        }
    }

    private suspend fun acceptLoop(
        socket: ServerSocket,
        core: DshCapabilityServerCore,
        owner: CoroutineScope
    ) {
        while (owner.isActive) {
            val client = try {
                socket.accept()
            } catch (failure: IOException) {
                break
            }
            activeSockets.add(client)
            owner.launch {
                try {
                    serve(client, core)
                } finally {
                    activeSockets.remove(client)
                    runCatching { client.close() }
                }
            }
        }
    }

    private suspend fun serve(client: Socket, core: DshCapabilityServerCore) {
        client.soTimeout = options.clientReadTimeoutMillis
        val result = try {
            val request = DshCapabilityHttpParser.parse(BufferedInputStream(client.getInputStream()))
            core.handle(request)
        } catch (rejected: DshCapabilityHttpParseException) {
            DshCapabilityErrorEnvelope.error(
                code = rejected.errorCode,
                message = rejected.message,
                statusCode = rejected.statusCode
            )
        } catch (_: SocketTimeoutException) {
            return
        } catch (_: IOException) {
            return
        }
        runCatching { writeResponse(client, result) }
    }

    private fun writeResponse(client: Socket, result: DshCapabilityHttpResult) {
        val body = result.bodyJson.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(result.statusCode).append(' ')
            append(DshCapabilityHttpStatus.reason(result.statusCode)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            result.extraHeaders.forEach { (name, value) ->
                val safeName = name.filter { it.isLetterOrDigit() || it == '-' }
                val safeValue = value.replace("\r", "").replace("\n", "")
                if (safeName.isNotEmpty() && safeName == name) {
                    append(safeName).append(": ").append(safeValue).append("\r\n")
                }
            }
            append("Connection: close\r\n\r\n")
        }
        val output = BufferedOutputStream(client.getOutputStream())
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val DRAIN_POLL_MILLIS = 20L
    }
}

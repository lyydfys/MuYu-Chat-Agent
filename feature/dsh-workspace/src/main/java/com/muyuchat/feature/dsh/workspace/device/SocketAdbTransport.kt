package com.muyuchat.feature.dsh.workspace.device

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plain-text ADB transport over TCP.
 *
 * Handshake (what a real `adbd` accepts):
 * 1. we send `CNXN(version=0x01000000, maxdata=4096, "host::\0")`;
 * 2. the device answers `AUTH(TOKEN)` with a 20-byte token → we answer `AUTH(SIGNATURE)` with the
 *    RSA/SHA-1 signature of that token;
 * 3. if the device does not trust our key it answers `AUTH(TOKEN)` again → we publish the key with
 *    `AUTH(RSAPUBLICKEY)` (base64 of adb's `RSAPublicKey` + NUL) and the transport becomes
 *    [DshAdbTransportState.AWAITING_PAIRING]; the device's prompt (or a line in `adb_keys`) decides;
 * 4. once the key is trusted the device sends `CNXN` → [DshAdbTransportState.CONNECTED].
 *
 * Shell: `OPEN("shell:<command>\0")` → `OKAY` (remote id) → every `WRTE` for our local id is
 * acknowledged with `OKAY(local, remote)` and accumulated → `CLSE` ends the stream. A plain-text adb
 * shell multiplexes stdout and stderr into one stream and carries no exit status, so the accumulated
 * bytes are returned as `stdout`, `stderr` stays empty and a clean `CLSE` reports `exitCode = 0`.
 *
 * Blocking IO always runs on [io] (IO in production). Every failure becomes a typed
 * [DshDeviceCommandFailure]; no exception reaches the UI. Output is bounded at [outputLimitBytes]
 * (256 KB by default) and a shell budget overrun closes the socket.
 */
class SocketAdbTransport internal constructor(
    private val identity: () -> AdbIdentity,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val shellTimeoutMs: Long = DSH_DEVICE_COMMAND_TIMEOUT_MS,
    private val outputLimitBytes: Int = DSH_DEVICE_OUTPUT_LIMIT_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : AdbTransport {

    /** Production wiring: the caller injects the app-private key file (tests use a temp directory). */
    constructor(identityFile: File) : this(identity = { AdbIdentity.loadOrCreate(identityFile) })

    @Volatile
    private var transportState: DshAdbTransportState = DshAdbTransportState.NOT_CONFIGURED

    @Volatile
    private var transportFailure: DshDeviceCommandFailure? = null

    private val lock = Any()

    private var socket: Socket? = null

    private var input: InputStream? = null

    private var output: OutputStream? = null

    private val nextStreamId = AtomicInteger(1)

    override fun state(): DshAdbTransportState = transportState

    override fun lastFailure(): DshDeviceCommandFailure? = transportFailure

    override suspend fun connect(endpoint: DshAdbEndpoint): Boolean = withContext(io) {
        closeSession()
        transportState = DshAdbTransportState.NOT_CONFIGURED
        transportFailure = null

        val opened = try {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
                soTimeout = readTimeoutMs
            }
        } catch (error: IOException) {
            fail(DshDeviceCommandFailure.CONNECT_FAILED)
            return@withContext false
        } catch (error: SecurityException) {
            fail(DshDeviceCommandFailure.CONNECT_FAILED)
            return@withContext false
        }

        synchronized(lock) {
            socket = opened
            input = runCatching { opened.getInputStream() }.getOrNull()
            output = runCatching { opened.getOutputStream() }.getOrNull()
        }
        if (input == null || output == null) {
            fail(DshDeviceCommandFailure.CONNECT_FAILED)
            closeSession()
            return@withContext false
        }

        val connected = try {
            handshake()
        } catch (timeout: SocketTimeoutException) {
            if (transportState == DshAdbTransportState.AWAITING_PAIRING) {
                transportFailure = DshDeviceCommandFailure.PAIRING_REQUIRED
            } else {
                fail(DshDeviceCommandFailure.TIMEOUT)
            }
            false
        } catch (error: IOException) {
            if (transportState == DshAdbTransportState.AWAITING_PAIRING) {
                transportFailure = DshDeviceCommandFailure.PAIRING_REQUIRED
            } else {
                fail(DshDeviceCommandFailure.HANDSHAKE_FAILED)
            }
            false
        }
        if (!connected) {
            closeSession()
            if (transportState != DshAdbTransportState.AWAITING_PAIRING) {
                transportState = DshAdbTransportState.FAILED
            }
        }
        connected
    }

    override suspend fun shell(command: String): DshAdbShellResult = withContext(io) {
        if (transportState != DshAdbTransportState.CONNECTED) {
            return@withContext shellFailure(
                transportFailure ?: DshDeviceCommandFailure.NOT_GRANTED
            )
        }
        val localId = nextStreamId.getAndIncrement()
        val openPayload = ("shell:" + command + "\u0000").toByteArray(Charsets.UTF_8)
        if (!send(AdbCommand.OPEN, localId, 0, openPayload)) {
            closeSession()
            fail(DshDeviceCommandFailure.PROTOCOL_ERROR)
            return@withContext shellFailure(DshDeviceCommandFailure.PROTOCOL_ERROR)
        }

        val deadline = System.currentTimeMillis() + shellTimeoutMs
        val sink = ByteArrayOutputStream()
        var total = 0
        var overflowed = false
        var remoteId = 0

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                closeSession()
                fail(DshDeviceCommandFailure.TIMEOUT)
                return@withContext shellFailure(DshDeviceCommandFailure.TIMEOUT)
            }
            val slice = minOf(readTimeoutMs.toLong(), remaining).toInt().coerceAtLeast(1)
            runCatching { socket?.soTimeout = slice }

            when (val wire = readWire()) {
                WireRead.Timeout -> {
                    closeSession()
                    fail(DshDeviceCommandFailure.TIMEOUT)
                    return@withContext shellFailure(DshDeviceCommandFailure.TIMEOUT)
                }

                WireRead.Eof -> {
                    // The peer closed the stream without CLSE: keep the bytes that arrived, but report
                    // the incomplete stream instead of pretending the command finished cleanly.
                    return@withContext if (remoteId == 0) {
                        shellFailure(DshDeviceCommandFailure.PROTOCOL_ERROR)
                    } else {
                        shellOutput(sink, overflowed)
                            .copy(failure = DshDeviceCommandFailure.PROTOCOL_ERROR)
                    }
                }

                is WireRead.Failure -> {
                    closeSession()
                    fail(DshDeviceCommandFailure.PROTOCOL_ERROR)
                    return@withContext shellFailure(DshDeviceCommandFailure.PROTOCOL_ERROR)
                }

                is WireRead.Ok -> {
                    val header = wire.header
                    when (header.command) {
                        AdbCommand.OKAY -> {
                            if (header.arg1 == localId) {
                                remoteId = header.arg0
                            }
                        }

                        AdbCommand.WRTE -> {
                            if (header.arg1 == localId) {
                                if (remoteId == 0) remoteId = header.arg0
                                val chunk = wire.payload
                                val room = outputLimitBytes - total
                                if (chunk.size > room) {
                                    if (room > 0) sink.write(chunk, 0, room)
                                    total = outputLimitBytes
                                    overflowed = true
                                } else {
                                    sink.write(chunk, 0, chunk.size)
                                    total += chunk.size
                                }
                                if (!send(AdbCommand.OKAY, localId, remoteId)) {
                                    closeSession()
                                    fail(DshDeviceCommandFailure.PROTOCOL_ERROR)
                                    return@withContext shellFailure(DshDeviceCommandFailure.PROTOCOL_ERROR)
                                }
                                if (overflowed) {
                                    // Stop the stream at the ceiling instead of growing without bound.
                                    send(AdbCommand.CLSE, localId, remoteId)
                                    return@withContext shellOutput(sink, overflowed = true)
                                }
                            }
                        }

                        AdbCommand.CLSE -> {
                            if (header.arg1 == localId) {
                                return@withContext shellOutput(sink, overflowed)
                            }
                        }

                        AdbCommand.CNXN -> Unit

                        else -> {
                            if (header.arg1 == localId) {
                                closeSession()
                                fail(DshDeviceCommandFailure.PROTOCOL_ERROR)
                                return@withContext shellFailure(DshDeviceCommandFailure.PROTOCOL_ERROR)
                            }
                        }
                    }
                }
            }
        }
        // The loop only exits through the returns above; this satisfies the lambda type without
        // inventing a fabricated success, and reports a protocol error if it is ever reached.
        return@withContext shellFailure(DshDeviceCommandFailure.PROTOCOL_ERROR)
    }

    override fun close() {
        closeSession()
        transportState = DshAdbTransportState.NOT_CONFIGURED
        transportFailure = null
    }

    // -------------------------------------------------------------------------------------------
    // Handshake
    // -------------------------------------------------------------------------------------------

    private fun handshake(): Boolean {
        val banner = HOST_BANNER.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        if (!send(AdbCommand.CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_PAYLOAD, banner)) {
            fail(DshDeviceCommandFailure.HANDSHAKE_FAILED)
            return false
        }
        var signatureSent = false
        var keyPublished = false
        while (true) {
            when (val wire = readWire()) {
                WireRead.Timeout -> return handshakeInterrupted(DshDeviceCommandFailure.TIMEOUT)
                WireRead.Eof -> return handshakeInterrupted(DshDeviceCommandFailure.HANDSHAKE_FAILED)
                is WireRead.Failure -> {
                    fail(DshDeviceCommandFailure.PROTOCOL_ERROR)
                    return false
                }

                is WireRead.Ok -> {
                    val header = wire.header
                    when (header.command) {
                        AdbCommand.CNXN -> {
                            transportState = DshAdbTransportState.CONNECTED
                            transportFailure = null
                            return true
                        }

                        AdbCommand.AUTH -> {
                            if (header.arg0 != AdbProtocol.AUTH_TOKEN) {
                                fail(DshDeviceCommandFailure.PROTOCOL_ERROR)
                                return false
                            }
                            if (!signatureSent) {
                                val signed = signToken(wire.payload) ?: return false
                                if (!send(
                                        AdbCommand.AUTH,
                                        AdbProtocol.AUTH_SIGNATURE,
                                        0,
                                        signed
                                    )
                                ) {
                                    fail(DshDeviceCommandFailure.HANDSHAKE_FAILED)
                                    return false
                                }
                                signatureSent = true
                            } else if (!keyPublished) {
                                val key = identityOrNull() ?: return false
                                if (!send(
                                        AdbCommand.AUTH,
                                        AdbProtocol.AUTH_RSAPUBLICKEY,
                                        0,
                                        key.publicKeyPayload()
                                    )
                                ) {
                                    fail(DshDeviceCommandFailure.HANDSHAKE_FAILED)
                                    return false
                                }
                                keyPublished = true
                                // The key is published; only the device's prompt can unblock it.
                                transportState = DshAdbTransportState.AWAITING_PAIRING
                                transportFailure = DshDeviceCommandFailure.PAIRING_REQUIRED
                            }
                        }

                        else -> {
                            fail(DshDeviceCommandFailure.PROTOCOL_ERROR)
                            return false
                        }
                    }
                }
            }
        }
    }

    /** A published key keeps the AWAITING_PAIRING status; anything else is a real handshake failure. */
    private fun handshakeInterrupted(failure: DshDeviceCommandFailure): Boolean {
        if (transportState == DshAdbTransportState.AWAITING_PAIRING) {
            transportFailure = DshDeviceCommandFailure.PAIRING_REQUIRED
        } else {
            fail(failure)
        }
        return false
    }

    private fun signToken(token: ByteArray): ByteArray? {
        val key = identityOrNull() ?: return null
        val signature = key.signToken(token)
        if (signature.isEmpty()) {
            fail(DshDeviceCommandFailure.HANDSHAKE_FAILED)
            return null
        }
        return signature
    }

    private fun identityOrNull(): AdbIdentity? = try {
        identity()
    } catch (error: Exception) {
        fail(DshDeviceCommandFailure.HANDSHAKE_FAILED)
        null
    }

    // -------------------------------------------------------------------------------------------
    // Wire IO
    // -------------------------------------------------------------------------------------------

    private fun send(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray = EMPTY_PAYLOAD
    ): Boolean {
        val bytes = when (val write = AdbPacketCodec.encode(command, arg0, arg1, payload)) {
            is AdbPacketWrite.Ok -> write.bytes
            is AdbPacketWrite.Failure -> return false
        }
        val stream = output ?: return false
        return try {
            stream.write(bytes)
            stream.flush()
            true
        } catch (error: IOException) {
            false
        }
    }

    private fun readWire(): WireRead {
        val stream = input ?: return WireRead.Eof
        return try {
            when (val read = AdbPacketCodec.readPacket(stream)) {
                is AdbPacketRead.Ok -> WireRead.Ok(read.header, read.payload)
                AdbPacketRead.EndOfStream -> WireRead.Eof
                is AdbPacketRead.Failure -> WireRead.Failure(read.error)
            }
        } catch (timeout: SocketTimeoutException) {
            WireRead.Timeout
        } catch (error: IOException) {
            WireRead.Eof
        }
    }

    private fun closeSession() {
        synchronized(lock) {
            runCatching { input?.close() }
            runCatching { output?.close() }
            runCatching { socket?.close() }
            input = null
            output = null
            socket = null
        }
    }

    private fun fail(failure: DshDeviceCommandFailure) {
        transportState = DshAdbTransportState.FAILED
        transportFailure = failure
    }

    private fun shellFailure(failure: DshDeviceCommandFailure): DshAdbShellResult =
        DshAdbShellResult(
            exitCode = DSH_DEVICE_EXIT_NOT_EXECUTED,
            stdout = "",
            stderr = "",
            failure = failure
        )

    private fun shellOutput(sink: ByteArrayOutputStream, overflowed: Boolean): DshAdbShellResult =
        DshAdbShellResult(
            exitCode = 0,
            stdout = sink.toByteArray().toString(Charsets.UTF_8),
            stderr = "",
            failure = null,
            overflowed = overflowed
        )

    private sealed interface WireRead {
        data class Ok(val header: AdbPacketHeader, val payload: ByteArray) : WireRead

        data object Eof : WireRead

        data object Timeout : WireRead

        data class Failure(val error: AdbPacketError) : WireRead
    }

    private companion object {
        /** Connection attempt budget, matching adb's own 5 s default. */
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 5_000

        /** Per-read socket timeout; a stalled peer cannot hold the console forever. */
        const val DEFAULT_READ_TIMEOUT_MS: Int = 10_000

        /** Minimal client banner; adbd accepts `host::`. */
        const val HOST_BANNER: String = "host::"

        val EMPTY_PAYLOAD: ByteArray = ByteArray(0)
    }
}

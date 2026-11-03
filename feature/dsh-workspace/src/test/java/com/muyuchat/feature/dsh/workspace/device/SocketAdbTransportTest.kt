package com.muyuchat.feature.dsh.workspace.device

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The socket transport against an in-process ADB server.
 *
 * Nothing here touches a real device or root: the fake server speaks the same framing (through
 * [AdbPacketCodec]) and lets the test drive a signature-only handshake, a key-publishing handshake,
 * shell streams, a stalled peer and a refused port.
 */
class SocketAdbTransportTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val servers = mutableListOf<FakeAdbServer>()
    private val transports = mutableListOf<SocketAdbTransport>()

    @After
    fun tearDown() {
        transports.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
    }

    @Test
    fun `connects with one signature challenge and runs a shell command`() = runTest {
        val server = startServer(FakeAdbServer.Handshake.ONE_SIGNATURE, FakeAdbServer.Shell.Echo("uid=0(root)\n"))
        val transport = newTransport()

        assertTrue(transport.connect(server.endpoint))
        assertEquals(DshAdbTransportState.CONNECTED, transport.state())
        assertNull(transport.lastFailure())
        assertEquals("the device token is 20 bytes", 20, server.issuedToken.size)
        assertEquals("the client must sign with a 2048-bit RSA key", 256, server.capturedSignature?.size)

        val result = transport.shell("id")

        assertNull(result.failure)
        assertEquals(0, result.exitCode)
        assertEquals("uid=0(root)\n", result.stdout)
        assertEquals("shell:id\u0000", server.capturedOpenPayload)
        assertTrue("the stream must be acknowledged", waitUntil { server.okayAcks.contains(1 to 71) })
        assertNull(server.serverError)
    }

    @Test
    fun `a device that rejects the key publishes it and reports awaiting pairing`() = runTest {
        val server = startServer(FakeAdbServer.Handshake.TWO_TOKENS)
        val transport = newTransport()

        val connected = transport.connect(server.endpoint)

        assertTrue("an unpaired device must not connect", !connected)
        assertEquals(DshAdbTransportState.AWAITING_PAIRING, transport.state())
        assertEquals(DshDeviceCommandFailure.PAIRING_REQUIRED, transport.lastFailure())
        val published = server.capturedPublicKeyPayload
        assertTrue("the public key payload must be published", published != null && published.isNotEmpty())
        assertEquals(0, published!!.last().toInt())
        val decoded = Base64.getDecoder().decode(published.copyOf(published.size - 1))
        assertEquals("adb's RSAPublicKey wire format is 520 bytes", 520, decoded.size)
        assertNull(server.serverError)
    }

    @Test
    fun `an immediate cnxn means the key is already trusted`() = runTest {
        val server = startServer(FakeAdbServer.Handshake.IMMEDIATE_CNXN)
        val transport = newTransport()

        assertTrue(transport.connect(server.endpoint))
        assertEquals(DshAdbTransportState.CONNECTED, transport.state())
    }

    @Test
    fun `a garbage handshake is a protocol failure`() = runTest {
        val server = startServer(FakeAdbServer.Handshake.GARBAGE)
        val transport = newTransport()

        val connected = transport.connect(server.endpoint)

        assertTrue(!connected)
        assertEquals(DshAdbTransportState.FAILED, transport.state())
        assertEquals(DshDeviceCommandFailure.PROTOCOL_ERROR, transport.lastFailure())
    }

    @Test
    fun `a device that never answers times out`() = runTest {
        val server = startServer(FakeAdbServer.Handshake.NEVER_ANSWERS)
        val transport = newTransport(readTimeoutMs = 200, connectTimeoutMs = 1_000)

        val connected = transport.connect(server.endpoint)

        assertTrue(!connected)
        assertEquals(DshAdbTransportState.FAILED, transport.state())
        assertEquals(DshDeviceCommandFailure.TIMEOUT, transport.lastFailure())
    }

    @Test
    fun `a refused port is reported as connect_failed`() = runTest {
        val freePort = ServerSocket(0).use { it.localPort }
        val transport = newTransport()

        val connected = transport.connect(DshAdbEndpoint("127.0.0.1", freePort))

        assertTrue(!connected)
        assertEquals(DshAdbTransportState.FAILED, transport.state())
        assertEquals(DshDeviceCommandFailure.CONNECT_FAILED, transport.lastFailure())
    }

    @Test
    fun `shell output above the ceiling is truncated and flagged`() = runTest {
        val server = startServer(
            FakeAdbServer.Handshake.ONE_SIGNATURE,
            FakeAdbServer.Shell.Huge(byteCount = 8 * 1024)
        )
        val transport = newTransport(outputLimitBytes = 1_024)

        assertTrue(transport.connect(server.endpoint))
        val result = transport.shell("cat /proc/version")

        assertNull(result.failure)
        assertTrue("the ceiling must be flagged", result.overflowed)
        assertEquals(1_024, result.stdout.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `a stalled shell times out and closes the session`() = runTest {
        val server = startServer(FakeAdbServer.Handshake.ONE_SIGNATURE, FakeAdbServer.Shell.Silent)
        val transport = newTransport(readTimeoutMs = 150, shellTimeoutMs = 250)

        assertTrue(transport.connect(server.endpoint))
        val result = transport.shell("id")

        assertEquals(DshDeviceCommandFailure.TIMEOUT, result.failure)
        assertEquals(DshAdbTransportState.FAILED, transport.state())
        assertEquals(DshDeviceCommandFailure.TIMEOUT, transport.lastFailure())
    }

    @Test
    fun `shell before connect is refused without opening a socket`() = runTest {
        val transport = newTransport()

        val result = transport.shell("id")

        assertEquals(DshDeviceCommandFailure.NOT_GRANTED, result.failure)
    }

    @Test
    fun `close is idempotent`() = runTest {
        val server = startServer(FakeAdbServer.Handshake.ONE_SIGNATURE)
        val transport = newTransport()
        assertTrue(transport.connect(server.endpoint))

        transport.close()
        transport.close()

        assertEquals(DshAdbTransportState.NOT_CONFIGURED, transport.state())
        assertNull(transport.lastFailure())
        assertEquals(DshDeviceCommandFailure.NOT_GRANTED, transport.shell("id").failure)
    }

    // -------------------------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------------------------

    private fun newTransport(
        connectTimeoutMs: Int = 1_000,
        readTimeoutMs: Int = 500,
        shellTimeoutMs: Long = 2_000,
        outputLimitBytes: Int = DSH_DEVICE_OUTPUT_LIMIT_BYTES
    ): SocketAdbTransport {
        val identity = AdbIdentity.loadOrCreate(
            File(temporaryFolder.newFolder(), "adbkey.pkcs8")
        )
        val transport = SocketAdbTransport(
            identity = { identity },
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            shellTimeoutMs = shellTimeoutMs,
            outputLimitBytes = outputLimitBytes,
            io = Dispatchers.IO
        )
        transports += transport
        return transport
    }

    private fun startServer(
        handshake: FakeAdbServer.Handshake,
        shell: FakeAdbServer.Shell = FakeAdbServer.Shell.Echo("ok\n")
    ): FakeAdbServer = FakeAdbServer(handshake, shell).also { servers += it }

    /** Bounded wait for a server-side observation; avoids asserting on a still-in-flight ack. */
    private fun waitUntil(timeoutMs: Long = 1_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}

/**
 * Minimal adbd stand-in: one connection, the framing subset the transport uses.
 */
private class FakeAdbServer(
    private val handshake: Handshake,
    private val shell: Shell
) : AutoCloseable {

    enum class Handshake { ONE_SIGNATURE, TWO_TOKENS, IMMEDIATE_CNXN, GARBAGE, NEVER_ANSWERS }

    sealed interface Shell {
        data class Echo(val text: String) : Shell

        data object Silent : Shell

        data class Huge(val byteCount: Int) : Shell
    }

    private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    val endpoint: DshAdbEndpoint = DshAdbEndpoint("127.0.0.1", serverSocket.localPort)

    val issuedToken: ByteArray = ByteArray(20) { (it + 7).toByte() }

    val okayAcks = CopyOnWriteArrayList<Pair<Int, Int>>()

    @Volatile
    var capturedSignature: ByteArray? = null
        private set

    @Volatile
    var capturedPublicKeyPayload: ByteArray? = null
        private set

    @Volatile
    var capturedOpenPayload: String? = null
        private set

    @Volatile
    var serverError: Throwable? = null
        private set

    @Volatile
    private var accepted: Socket? = null

    private val thread = Thread({ runServer() }, "fake-adbd").apply {
        isDaemon = true
        start()
    }

    override fun close() {
        runCatching { accepted?.close() }
        runCatching { serverSocket.close() }
        runCatching { thread.join(500) }
    }

    private fun runServer() {
        val socket = try {
            serverSocket.accept()
        } catch (error: IOException) {
            return
        }
        accepted = socket
        try {
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val cnxn = read(input) ?: return
            if (cnxn.header.command != AdbCommand.CNXN) {
                serverError = IllegalStateException("expected CNXN, got ${cnxn.header.commandName}")
                return
            }
            when (handshake) {
                Handshake.IMMEDIATE_CNXN -> sendCnxn(output)

                Handshake.GARBAGE -> {
                    output.write(ByteArray(AdbPacketCodec.HEADER_SIZE) { 0x41 })
                    output.flush()
                }

                Handshake.NEVER_ANSWERS -> {
                    // Block until the client gives up and closes the socket.
                    while (input.read() >= 0) {
                        // drain
                    }
                    return
                }

                Handshake.ONE_SIGNATURE -> {
                    sendAuthToken(output)
                    val auth = read(input) ?: return
                    if (auth.header.arg0 != AdbProtocol.AUTH_SIGNATURE) {
                        serverError = IllegalStateException("expected AUTH(SIGNATURE)")
                        return
                    }
                    capturedSignature = auth.payload
                    sendCnxn(output)
                }

                Handshake.TWO_TOKENS -> {
                    sendAuthToken(output)
                    val signature = read(input) ?: return
                    if (signature.header.arg0 != AdbProtocol.AUTH_SIGNATURE) {
                        serverError = IllegalStateException("expected the first AUTH(SIGNATURE)")
                        return
                    }
                    capturedSignature = signature.payload
                    sendAuthToken(output)
                    val publicKey = read(input) ?: return
                    if (publicKey.header.arg0 != AdbProtocol.AUTH_RSAPUBLICKEY) {
                        serverError = IllegalStateException("expected AUTH(RSAPUBLICKEY)")
                        return
                    }
                    capturedPublicKeyPayload = publicKey.payload
                    // A real adbd now waits for the user; here the session simply ends.
                    return
                }
            }
            serveShell(input, output)
        } catch (error: IOException) {
            // The client closing first is the normal teardown path.
        } catch (error: Throwable) {
            serverError = error
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun serveShell(input: InputStream, output: OutputStream) {
        while (true) {
            val packet = read(input) ?: return
            when (packet.header.command) {
                AdbCommand.OPEN -> {
                    val localId = packet.header.arg0
                    capturedOpenPayload = packet.payload.toString(Charsets.UTF_8)
                    send(output, AdbCommand.OKAY, REMOTE_ID, localId, ByteArray(0))
                    when (val mode = shell) {
                        is Shell.Echo -> {
                            send(output, AdbCommand.WRTE, REMOTE_ID, localId, mode.text.toByteArray(Charsets.UTF_8))
                            send(output, AdbCommand.CLSE, REMOTE_ID, localId, ByteArray(0))
                        }

                        is Shell.Huge -> {
                            var remaining = mode.byteCount
                            val chunk = ByteArray(1_024) { 'x'.code.toByte() }
                            while (remaining > 0) {
                                val size = minOf(remaining, chunk.size)
                                send(output, AdbCommand.WRTE, REMOTE_ID, localId, chunk.copyOf(size))
                                remaining -= size
                            }
                            send(output, AdbCommand.CLSE, REMOTE_ID, localId, ByteArray(0))
                        }

                        Shell.Silent -> Unit
                    }
                }

                AdbCommand.OKAY -> okayAcks += packet.header.arg0 to packet.header.arg1

                AdbCommand.CLSE -> return

                else -> Unit
            }
        }
    }

    private fun sendAuthToken(output: OutputStream) {
        send(output, AdbCommand.AUTH, AdbProtocol.AUTH_TOKEN, 0, issuedToken)
    }

    private fun sendCnxn(output: OutputStream) {
        send(
            output,
            AdbCommand.CNXN,
            AdbProtocol.VERSION,
            AdbProtocol.MAX_PAYLOAD,
            "device::\u0000".toByteArray(Charsets.UTF_8)
        )
    }

    private fun send(
        output: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ) {
        val bytes = (AdbPacketCodec.encode(command, arg0, arg1, payload) as AdbPacketWrite.Ok).bytes
        output.write(bytes)
        output.flush()
    }

    private fun read(input: InputStream): AdbPacketRead.Ok? = when (
        val packet = AdbPacketCodec.readPacket(input)
    ) {
        is AdbPacketRead.Ok -> packet
        AdbPacketRead.EndOfStream -> null
        is AdbPacketRead.Failure -> throw IOException("fake server read failure: ${packet.error.code}")
    }

    private companion object {
        const val REMOTE_ID = 71
    }
}

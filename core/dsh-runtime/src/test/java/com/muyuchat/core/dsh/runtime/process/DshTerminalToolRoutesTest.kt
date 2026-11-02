package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.core.dsh.runtime.payload.DshJson
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.muyuchat.core.dsh.contract.runtime.DshTerminalTransport

class DshTerminalToolRoutesTest {

    private val root = tempDirectory("dsh-terminal-routes")
    private val environment: DshToolchainEnvironment

    init {
        materializePayload(root)
        environment = DshToolchainEnvironment(
            payloadDir = root,
            nativeLibraryDir = File(root, "native"),
            runtimeLibraryDir = File(root, "runtime/lib"),
            payloadBinDir = File(root, "bin"),
            homeDir = File(root, "home"),
            workspaceDir = File(root, "workspace"),
            configDir = File(root, "home"),
            tmpDir = File(root, "tmp")
        )
    }

    @After
    fun cleanUp() {
        root.walkBottomUp().forEach { it.delete() }
    }

    private fun service(
        port: ScriptedTerminalPort? = ScriptedTerminalPort(initialOutput = listOf("$ ".toByteArray())),
        pipePort: ScriptedTerminalPort? = null,
        allowFallback: Boolean = false,
        requireRealPty: Boolean = true
    ): DshTerminalToolService {
        val factory = fakeFactory(
            port = port,
            allowPipeFallback = allowFallback,
            pipePort = pipePort
        )
        val host = DshTerminalHost(
            factory = factory,
            modelProvider = { environment.closureModel() },
            environmentProvider = { mapOf("PATH" to "${environment.payloadBinDir}:/system/bin") },
            workingDirectoryProvider = { environment.workingDirectory() },
            initialOutputMillis = 80L,
            autoPump = false
        )
        return DshTerminalToolService(
            environment = environment,
            host = host,
            verifier = DshToolchainVerifier(successfulProbeRunner()) { 0L },
            requireRealPty = requireRealPty
        )
    }

    private fun call(
        table: DshCapabilityRouteTable,
        path: String,
        body: String = "{}"
    ): Pair<Int, Map<String, Any?>> = runBlocking {
        val response = table.handlerFor(path)!!.handle(
            DshCapabilityRouteRequest(
                path = path,
                method = DshCapabilityHttpMethod.POST,
                query = emptyMap(),
                bodyJson = body,
                localToken = null,
                generation = 1L,
                receivedAtMillis = 0L
            )
        )
        val json = response as DshCapabilityRouteResponse.Json
        json.statusCode to DshJson.asObject(DshJson.parse(json.bodyJson), "response")
    }

    @Test
    fun openWriteControlResizeAttachReadAndCloseRoundTrip() {
        val terminal = service()
        val table = DshTerminalToolRoutes.table(terminal)

        val (openStatus, opened) = call(table, DshTerminalToolRoutes.OPEN, """{"type":"shell"}""")
        assertEquals(200, openStatus)
        assertEquals(true, opened["ok"])
        val session = DshJson.asObject(opened["session"], "session")
        val id = DshJson.asString(session, "sessionId", "session")
        assertEquals("REAL_PTY", DshJson.asString(session, "transport", "session"))
        assertEquals(true, session["realPty"])
        assertEquals("$ ", opened["motd"])

        val (writeStatus, written) = call(table, DshTerminalToolRoutes.WRITE, """{"sessionId":"$id","text":"pwd"}""")
        assertEquals(200, writeStatus)
        assertEquals(true, written["ok"])
        assertEquals(4L, DshJson.asLong(written, "bytes", "write"))

        val (controlStatus, controlled) = call(
            table,
            DshTerminalToolRoutes.CONTROL,
            """{"sessionId":"$id","control":"CTRL_C"}"""
        )
        assertEquals(200, controlStatus)
        assertEquals(true, controlled["ok"])

        val (resizeStatus, resized) = call(
            table,
            DshTerminalToolRoutes.RESIZE,
            """{"sessionId":"$id","cols":120,"rows":40}"""
        )
        assertEquals(200, resizeStatus)
        assertEquals(true, resized["applied"])
        assertEquals("REAL_PTY", DshJson.asString(resized, "transport", "resize"))

        val (attachStatus, attached) = call(
            table,
            DshTerminalToolRoutes.ATTACH,
            """{"sessionId":"$id","cols":100,"rows":30,"maxBytes":4096}"""
        )
        assertEquals(200, attachStatus)
        assertEquals("$ ", attached["text"])

        val (readStatus, read) = call(
            table,
            DshTerminalToolRoutes.READ,
            """{"sessionId":"$id","offset":0,"maxBytes":64}"""
        )
        assertEquals(200, readStatus)
        assertEquals("RUNNING", DshJson.asString(read, "status", "read"))

        val (listStatus, listed) = call(table, DshTerminalToolRoutes.LIST)
        assertEquals(200, listStatus)
        assertEquals(1, DshJson.asArray(listed["sessions"], "sessions").size)

        val (closeStatus, closed) = call(table, DshTerminalToolRoutes.CLOSE, """{"sessionId":"$id"}""")
        assertEquals(200, closeStatus)
        assertEquals(true, closed["settled"])
        assertEquals(true, closed["ok"])
        assertTrue(terminal.list().isEmpty())
    }

    @Test
    fun thePipeFallbackSurfaceRefusesPtysOnlyFeatures() {
        val pipePort = ScriptedTerminalPort(DshTerminalTransport.PIPE_FALLBACK)
        val terminal = service(port = null, pipePort = pipePort, allowFallback = true, requireRealPty = false)
        val table = DshTerminalToolRoutes.table(terminal)

        val (_, opened) = call(table, DshTerminalToolRoutes.OPEN, """{"type":"shell","requirePty":false}""")
        val session = DshJson.asObject(opened["session"], "session")
        val id = DshJson.asString(session, "sessionId", "session")
        assertEquals("PIPE_FALLBACK", DshJson.asString(session, "transport", "session"))

        val (controlStatus, control) = call(
            table,
            DshTerminalToolRoutes.CONTROL,
            """{"sessionId":"$id","control":"CTRL_C"}"""
        )
        assertEquals(409, controlStatus)
        assertEquals("pty-unavailable", DshJson.asString(control, "reason", "control"))
        assertTrue(pipePort.writes.isEmpty())

        val (resizeStatus, resize) = call(
            table,
            DshTerminalToolRoutes.RESIZE,
            """{"sessionId":"$id","cols":100,"rows":30}"""
        )
        assertEquals(200, resizeStatus)
        assertEquals(false, resize["ok"])
        assertEquals(false, resize["applied"])
        assertEquals("resize-unsupported", DshJson.asString(resize, "reason", "resize"))
    }

    @Test
    fun requiringARealPtyRefusesTheFallbackThroughTheWire() {
        val pipePort = ScriptedTerminalPort(DshTerminalTransport.PIPE_FALLBACK)
        val terminal = service(port = null, pipePort = pipePort, allowFallback = true, requireRealPty = true)
        val table = DshTerminalToolRoutes.table(terminal)

        val (status, body) = call(table, DshTerminalToolRoutes.OPEN, """{"type":"shell","requirePty":true}""")
        assertEquals(409, status)
        assertEquals(false, body["ok"])
        assertEquals("pty-unavailable", DshJson.asString(body, "reason", "open"))
        assertTrue(pipePort.closeCalls >= 1)
    }

    @Test
    fun malformedBodiesAndUnknownSessionsAreTypedRefusals() {
        val terminal = service()
        val table = DshTerminalToolRoutes.table(terminal)

        val (badStatus, bad) = call(table, DshTerminalToolRoutes.READ, "not-json")
        assertEquals(400, badStatus)
        assertEquals("bad-args", DshJson.asString(bad, "reason", "bad"))

        val (missingStatus, missing) = call(table, DshTerminalToolRoutes.READ, "{}")
        assertEquals(400, missingStatus)
        assertEquals("bad-args", DshJson.asString(missing, "reason", "missing"))

        val (unknownStatus, unknown) = call(
            table,
            DshTerminalToolRoutes.READ,
            """{"sessionId":"term-999"}"""
        )
        assertEquals(404, unknownStatus)
        assertEquals("unknown-session", DshJson.asString(unknown, "reason", "unknown"))

        val (closeStatus, _) = call(table, DshTerminalToolRoutes.CLOSE, """{"sessionId":"term-999"}""")
        assertEquals(404, closeStatus)

        val (tooLargeStatus, tooLarge) = call(
            table,
            DshTerminalToolRoutes.WRITE,
            """{"sessionId":"term-1","text":"${"x".repeat(70_000)}"}"""
        )
        assertEquals(413, tooLargeStatus)
        assertEquals("input-too-large", DshJson.asString(tooLarge, "reason", "large"))
    }

    @Test
    fun theToolchainRouteExecutesEveryProbeAndReportsRealDependencies() {
        val terminal = service()
        val table = DshTerminalToolRoutes.table(terminal)

        val (status, body) = call(table, DshTerminalToolRoutes.TOOLCHAIN_VERIFY)
        assertEquals(200, status)
        assertEquals(true, body["ok"])
        assertEquals(true, body["usable"])
        val probes = DshJson.asArray(body["probes"], "probes")
        assertEquals(DshToolchainVerifier.PROBE_ROLES.size, probes.size)
        assertTrue(probes.all { DshJson.asObject(it, "probe")["passed"] == true })
        val dependencies = DshJson.asArray(body["dependencies"], "dependencies")
        assertTrue(dependencies.any { DshJson.asObject(it, "dependency")["name"] == DshCommandClosure.PTY_LIBRARY })
        assertTrue(DshJson.asArray(body["failures"], "failures").isEmpty())
        assertTrue(DshJson.asString(body, "summary", "summary").contains("probes=10/10"))
    }

    @Test
    fun toolchainVerificationIsCachedButCanBeForced() {
        var probes = 0
        val runner = DshProcessRunner { argv, _, _, _ ->
            probes++
            successfulProbeRunner().run(argv, emptyMap(), null, 0L)
        }
        val host = DshTerminalHost(
            factory = fakeFactory(ScriptedTerminalPort()),
            modelProvider = { environment.closureModel() },
            environmentProvider = { emptyMap() },
            workingDirectoryProvider = { environment.workingDirectory() },
            autoPump = false
        )
        val terminal = DshTerminalToolService(
            environment = environment,
            host = host,
            verifier = DshToolchainVerifier(runner) { 0L }
        )

        val first = terminal.verifyToolchain()
        val callsAfterFirst = probes
        val second = terminal.verifyToolchain()
        assertEquals(callsAfterFirst, probes)
        assertTrue(first.usable)
        assertTrue(second.usable)
        terminal.verifyToolchain(force = true)
        assertEquals(callsAfterFirst * 2, probes)
    }

    @Test
    fun stopGenerationClosesEverySessionAndIsIdempotent() {
        val ports = mutableListOf<ScriptedTerminalPort>()
        val host = DshTerminalHost(
            factory = fakeFactory(portProvider = { ScriptedTerminalPort(initialOutput = listOf("$ ".toByteArray())).also { ports.add(it) } }),
            modelProvider = { environment.closureModel() },
            environmentProvider = { emptyMap() },
            workingDirectoryProvider = { environment.workingDirectory() },
            initialOutputMillis = 40L,
            autoPump = false
        )
        val terminal = DshTerminalToolService(environment = environment, host = host)
        assertTrue(terminal.open() is DshTerminalOpenOutcome.Opened)
        assertTrue(terminal.open() is DshTerminalOpenOutcome.Opened)

        val shutdown = terminal.stopGeneration()
        assertTrue(shutdown.settled)
        assertEquals(2, shutdown.sessions.size)
        assertTrue(ports.all { it.closeCalls == 1 })

        val second = terminal.stopGeneration()
        assertTrue(second.settled)
        assertTrue(second.sessions.isEmpty())
    }
}

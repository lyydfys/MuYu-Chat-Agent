package com.muyuchat.core.dsh.a11y.policy

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Route-seam tests: the platform-owned table must reach the engine only through valid commands and
 * must always return a parseable JSON document, including when the service is disabled or the
 * engine throws.
 */
class A11yRoutesTest {

    @Test
    fun routeTableOwnsExactlyTheFourteenAccessibilityPaths() {
        val routes = DshA11yCapabilityRoutes(FakeEngine(outcome())).routeTable()
        assertEquals(A11yRequestParser.SUPPORTED_PATHS.sorted(), routes.paths())
        assertEquals(14, routes.paths().size)
    }

    @Test
    fun statusCommandReachesTheEngineAndIsEncoded() = runBlocking {
        val engine = FakeEngine(outcome())
        val routes = DshA11yCapabilityRoutes(engine)
        val response = respond(routes, "/status", method = DshCapabilityHttpMethod.GET)
        assertEquals(listOf<A11yCommand>(A11yCommand.Status), engine.commands)
        val json = JSONObject(response)
        assertTrue(json.getBoolean("ok"))
        assertTrue(json.getBoolean("running"))
    }

    @Test
    fun disabledServiceIsAnExplicitNotEnabledFailure() = runBlocking {
        val engine = FakeEngine(A11yOutcome.Rejected(A11yRejection.notEnabled()))
        val routes = DshA11yCapabilityRoutes(engine)
        val json = JSONObject(respond(routes, "/tap", mapOf("text" to "OK")))
        assertFalse(json.getBoolean("ok"))
        assertEquals(DshCapabilityErrorCode.NOT_ENABLED.name, json.getString("code"))
        assertTrue(json.getString("error").contains("无障碍服务"))
    }

    @Test
    fun malformedRequestNeverReachesTheEngine() = runBlocking {
        val engine = FakeEngine(outcome())
        val routes = DshA11yCapabilityRoutes(engine)
        val json = JSONObject(respond(routes, "/tap", emptyMap()))
        assertFalse(json.getBoolean("ok"))
        assertEquals(DshCapabilityErrorCode.INVALID_REQUEST.name, json.getString("code"))
        assertTrue("engine must not see an invalid command", engine.commands.isEmpty())
    }

    @Test
    fun gestureOnlyAcceptsPost() = runBlocking {
        val engine = FakeEngine(outcome())
        val routes = DshA11yCapabilityRoutes(engine)

        val getResponse = JSONObject(
            respond(routes, "/gesture", method = DshCapabilityHttpMethod.GET, body = """[{"kind":"wait","ms":1}]""")
        )
        assertFalse(getResponse.getBoolean("ok"))
        assertTrue(engine.commands.isEmpty())

        val postResponse = JSONObject(
            respond(
                routes,
                "/gesture",
                method = DshCapabilityHttpMethod.POST,
                body = """[{"kind":"tap","x":10,"y":20}]"""
            )
        )
        assertTrue(postResponse.getBoolean("ok"))
        assertEquals(1, engine.commands.size)
        assertTrue(engine.commands.single() is A11yCommand.Gesture)
    }

    @Test
    fun engineCrashBecomesATypedInternalFailure() = runBlocking {
        val engine = FakeEngine(outcome()).apply { failure = IllegalStateException("boom") }
        val routes = DshA11yCapabilityRoutes(engine)
        val json = JSONObject(respond(routes, "/dump"))
        assertFalse(json.getBoolean("ok"))
        assertEquals(DshCapabilityErrorCode.INTERNAL.name, json.getString("code"))
    }

    @Test
    fun cancellationIsPropagatedInsteadOfSwallowed() = runBlocking {
        val engine = FakeEngine(outcome()).apply { failure = CancellationException("cancelled") }
        val routes = DshA11yCapabilityRoutes(engine)
        try {
            respond(routes, "/dump")
            fail("a cancelled engine call must propagate")
        } catch (expected: CancellationException) {
            assertTrue(expected.message == "cancelled")
        }
    }

    @Test
    fun duplicateRouteOwnerIsRejected() {
        val engine = FakeEngine(outcome())
        val builder = DshCapabilityRouteTable.builder()
        DshA11yCapabilityRoutes(engine).install(builder)
        try {
            DshA11yCapabilityRoutes(engine).install(builder)
            fail("two owners of the same accessibility path must not be accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "duplicate route must name the owned path",
                expected.message.orEmpty().contains("already owned")
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun respond(
        routes: DshA11yCapabilityRoutes,
        path: String,
        query: Map<String, String> = emptyMap(),
        method: DshCapabilityHttpMethod = DshCapabilityHttpMethod.GET,
        body: String? = null
    ): String {
        val handler = routes.routeTable().handlerFor(path)
            ?: throw AssertionError("no handler registered for $path")
        val request = DshCapabilityRouteRequest(
            path = path,
            method = method,
            query = query,
            bodyJson = body,
            localToken = "test-token",
            generation = 1L,
            receivedAtMillis = 1_000L
        )
        val response = handler.handle(request)
        assertTrue(response is DshCapabilityRouteResponse.Json)
        return (response as DshCapabilityRouteResponse.Json).bodyJson
    }

    private fun outcome(): A11yOutcome = A11yOutcome.Status(
        A11yServiceStatus(
            running = true,
            packageName = "com.example",
            nodeCount = 3,
            apiLevel = 34,
            canScreenshot = true,
            windowId = 1
        )
    )

    private class FakeEngine(var outcome: A11yOutcome) : A11yEngine {
        val commands = mutableListOf<A11yCommand>()
        var failure: Throwable? = null

        override suspend fun execute(command: A11yCommand): A11yOutcome {
            commands += command
            failure?.let { throw it }
            return outcome
        }
    }
}

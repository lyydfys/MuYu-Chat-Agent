package com.muyuchat.core.dsh.vscreen

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSON envelope and route registration of the eight official actions. No Android code. */
class DshVscreenWireTest {

    private class FakeControl : DshVscreenControl {
        var createResult: DshVscreenResult<DshVscreenCreateInfo> = DshVscreenResult.Success(
            DshVscreenCreateInfo(
                displayId = 7,
                geometry = DshVscreenGeometry.canonical(DshVscreenOrientation.PORTRAIT),
                reused = false,
                lease = DshVscreenLease(1L, 7),
                hint = "virtual display ready"
            )
        )
        var statusResult: DshVscreenResult<DshVscreenStatusInfo> = DshVscreenResult.Success(
            DshVscreenStatusInfo(
                state = DshVscreenState.CLOSED,
                displayId = -1,
                geometry = null,
                running = false,
                hasFrame = false,
                lease = null,
                transport = null
            )
        )
        var seeResult: DshVscreenResult<DshVscreenFrameInfo> = DshVscreenResult.Success(
            DshVscreenFrameInfo(
                path = "/cache/vscreen/frame.png",
                displayId = 7,
                screenWidth = 1008,
                screenHeight = 1792,
                imageWidth = 100,
                imageHeight = 200
            )
        )
        var launchResult: DshVscreenResult<DshVscreenLaunchInfo> = DshVscreenResult.Success(
            DshVscreenLaunchInfo("com.example.app", null, 7, "launch dispatched")
        )
        var actionResult: DshVscreenResult<Unit> = DshVscreenResult.Success(Unit)
        val calls = mutableListOf<String>()
        var lastCreate: DshVscreenCreateRequest? = null
        var lastSee: DshVscreenSeeRequest? = null
        var lastTap: DshVscreenTapRequest? = null
        var lastSwipe: DshVscreenSwipeRequest? = null
        var lastKey: DshVscreenKeyRequest? = null
        var lastLaunch: DshVscreenLaunchRequest? = null

        override suspend fun create(request: DshVscreenCreateRequest): DshVscreenResult<DshVscreenCreateInfo> {
            calls += "create"
            lastCreate = request
            val current = createResult
            return if (current is DshVscreenResult.Success) {
                DshVscreenResult.Success(current.value.copy(geometry = request.geometry))
            } else {
                current
            }
        }

        override suspend fun status(): DshVscreenResult<DshVscreenStatusInfo> {
            calls += "status"
            return statusResult
        }

        override suspend fun launch(request: DshVscreenLaunchRequest): DshVscreenResult<DshVscreenLaunchInfo> {
            calls += "launch"
            lastLaunch = request
            return launchResult
        }

        override suspend fun see(request: DshVscreenSeeRequest): DshVscreenResult<DshVscreenFrameInfo> {
            calls += "see"
            lastSee = request
            return seeResult
        }

        override suspend fun tap(request: DshVscreenTapRequest): DshVscreenResult<Unit> {
            calls += "tap"
            lastTap = request
            return actionResult
        }

        override suspend fun swipe(request: DshVscreenSwipeRequest): DshVscreenResult<Unit> {
            calls += "swipe"
            lastSwipe = request
            return actionResult
        }

        override suspend fun key(request: DshVscreenKeyRequest): DshVscreenResult<Unit> {
            calls += "key"
            lastKey = request
            return actionResult
        }

        override suspend fun close(): DshVscreenResult<Unit> {
            calls += "close"
            return actionResult
        }
    }

    private fun table(control: DshVscreenControl): DshCapabilityRouteTable =
        DshVscreenRoutes(control).register(DshCapabilityRouteTable.builder()).build()

    private suspend fun invoke(
        table: DshCapabilityRouteTable,
        path: String,
        query: Map<String, String> = emptyMap(),
        method: DshCapabilityHttpMethod = DshCapabilityHttpMethod.GET,
        token: String? = null
    ): Pair<Int, JSONObject> {
        val handler = requireNotNull(table.handlerFor(path)) { "no handler for $path" }
        val response = handler.handle(
            DshCapabilityRouteRequest(
                path = path,
                method = method,
                query = query,
                bodyJson = null,
                localToken = token,
                generation = 1L,
                receivedAtMillis = 1_000L
            )
        )
        assertTrue("expected a JSON response but was $response", response is DshCapabilityRouteResponse.Json)
        val json = response as DshCapabilityRouteResponse.Json
        return json.statusCode to JSONObject(json.bodyJson)
    }

    @Test
    fun registersExactlyTheEightOfficialRoutes() {
        val table = table(FakeControl())
        assertEquals(DshVscreenAction.routes.sorted(), table.paths())
        assertEquals(8, table.paths().size)
        assertNull(table.handlerFor("/vscreen/unknown"))
    }

    @Test
    fun createReturnsTheStableFieldsThePackageReads() = runTest {
        val control = FakeControl()
        val (status, json) = invoke(
            table(control),
            DshVscreenAction.CREATE.route,
            mapOf("orientation" to "portrait", "dpi" to "320")
        )
        assertEquals(200, status)
        assertTrue(json.getBoolean("ok"))
        assertEquals(7, json.getInt("displayId"))
        assertEquals(1008, json.getInt("width"))
        assertEquals(1792, json.getInt("height"))
        assertEquals(320, json.getInt("dpi"))
        assertEquals("portrait", json.getString("orientation"))
        assertFalse(json.getBoolean("reused"))
        assertEquals("virtual display ready", json.getString("hint"))
        assertEquals(320, control.lastCreate!!.geometry.dpi)
    }

    @Test
    fun invalidOrientationReturnsOkFalseAndTheStableCode() = runTest {
        val control = FakeControl()
        val (status, json) = invoke(
            table(control),
            DshVscreenAction.CREATE.route,
            mapOf("orientation" to "diagonal")
        )
        assertEquals(200, status)
        assertFalse(json.getBoolean("ok"))
        assertEquals("unsupported_orientation", json.getString("errorCode"))
        assertTrue(control.calls.isEmpty())
    }

    @Test
    fun unknownQueryParameterIsRejectedBeforeTheControlRuns() = runTest {
        val control = FakeControl()
        val (_, json) = invoke(
            table(control),
            DshVscreenAction.CREATE.route,
            mapOf("orientation" to "portrait", "fps" to "60")
        )
        assertFalse(json.getBoolean("ok"))
        assertEquals("invalid_request", json.getString("errorCode"))
        assertTrue(control.calls.isEmpty())
    }

    @Test
    fun statusWithoutDisplayUsesMinusOneAndZeroSizes() = runTest {
        val (_, json) = invoke(table(FakeControl()), DshVscreenAction.STATUS.route)
        assertTrue(json.getBoolean("ok"))
        assertEquals(-1, json.getInt("displayId"))
        assertEquals(0, json.getInt("width"))
        assertEquals(0, json.getInt("height"))
        assertFalse(json.getBoolean("running"))
        assertFalse(json.getBoolean("hasFrame"))
    }

    @Test
    fun seeFailureCarriesThePackageErrorCodesAndDiagnostics() = runTest {
        val control = FakeControl()
        control.seeResult = DshVscreenResult.Failure(
            code = DshVscreenErrorCode.NOT_RENDERED,
            message = "no first frame",
            displayId = 7,
            hasFrame = false,
            waitedMs = 600L,
            attempts = 3,
            hint = "wait for the app to draw"
        )
        val (_, json) = invoke(
            table(control),
            DshVscreenAction.SEE.route,
            mapOf("waitMs" to "800")
        )
        assertFalse(json.getBoolean("ok"))
        assertEquals("not_rendered", json.getString("errorCode"))
        assertEquals(7, json.getInt("displayId"))
        assertFalse(json.getBoolean("hasFrame"))
        assertEquals(600L, json.getLong("waitedMs"))
        assertEquals(3, json.getInt("attempts"))
        assertEquals("wait for the app to draw", json.getString("hint"))
        assertEquals(800L, control.lastSee!!.waitMs)
    }

    @Test
    fun seeSuccessCarriesScaleFactorsForCoordinateConversion() = runTest {
        val (_, json) = invoke(table(FakeControl()), DshVscreenAction.SEE.route)
        assertTrue(json.getBoolean("ok"))
        assertEquals("/cache/vscreen/frame.png", json.getString("path"))
        assertEquals(1008, json.getInt("screenW"))
        assertEquals(1792, json.getInt("screenH"))
        assertEquals(100, json.getInt("imageW"))
        assertEquals(200, json.getInt("imageH"))
        assertEquals(10.08, json.getDouble("scaleX"), 0.0001)
        assertEquals(8.96, json.getDouble("scaleY"), 0.0001)
    }

    @Test
    fun tapSwipeKeyAndLaunchMapTheirQueryStrings() = runTest {
        val control = FakeControl()
        val table = table(control)

        invoke(table, DshVscreenAction.TAP.route, mapOf("x" to "10", "y" to "20"))
        assertEquals(DshVscreenTapRequest(10, 20), control.lastTap)

        invoke(
            table,
            DshVscreenAction.SWIPE.route,
            mapOf("x1" to "0", "y1" to "1", "x2" to "2", "y2" to "3")
        )
        assertEquals(300, control.lastSwipe!!.durationMillis)

        invoke(table, DshVscreenAction.KEY.route, mapOf("keycode" to "KEYCODE_BACK"))
        assertEquals(DshVscreenKey.BACK, control.lastKey!!.key)

        invoke(table, DshVscreenAction.LAUNCH.route, mapOf("pkg" to "com.example.app"))
        assertEquals("com.example.app", control.lastLaunch!!.packageName)

        invoke(table, DshVscreenAction.CLOSE.route)
        assertEquals(listOf("tap", "swipe", "key", "launch", "close"), control.calls)
    }

    @Test
    fun unknownKeyReturnsTheAcceptedNamesAndDoesNotReachTheControl() = runTest {
        val control = FakeControl()
        val (_, json) = invoke(
            table(control),
            DshVscreenAction.KEY.route,
            mapOf("keycode" to "not-a-key")
        )
        assertFalse(json.getBoolean("ok"))
        assertEquals("unknown_key", json.getString("errorCode"))
        assertTrue(json.getString("error").contains("back"))
        assertTrue(control.calls.isEmpty())
    }

    @Test
    fun missingCoordinateReturnsInvalidCoordinate() = runTest {
        val control = FakeControl()
        val (_, json) = invoke(table(control), DshVscreenAction.TAP.route, mapOf("x" to "10"))
        assertFalse(json.getBoolean("ok"))
        assertEquals("invalid_coordinate", json.getString("errorCode"))
        assertTrue(control.calls.isEmpty())
    }

    @Test
    fun actionFailuresUseTheSameEnvelope() = runTest {
        val control = FakeControl()
        control.actionResult = DshVscreenResult.Failure(
            code = DshVscreenErrorCode.PRIVILEGE_UNAVAILABLE,
            message = "no privileged transport",
            hint = "grant Shizuku or root"
        )
        val (_, json) = invoke(table(control), DshVscreenAction.CLOSE.route)
        assertFalse(json.getBoolean("ok"))
        assertEquals("privilege_unavailable", json.getString("errorCode"))
        assertEquals("grant Shizuku or root", json.getString("hint"))
    }

    @Test
    fun nonGetVerbsAreRejected() = runTest {
        val control = FakeControl()
        val (status, json) = invoke(
            table(control),
            DshVscreenAction.CREATE.route,
            mapOf("orientation" to "portrait"),
            method = DshCapabilityHttpMethod.POST
        )
        assertEquals(405, status)
        assertFalse(json.getBoolean("ok"))
        assertEquals("invalid_request", json.getString("errorCode"))
        assertTrue(control.calls.isEmpty())
    }

    @Test
    fun capabilityTokenIsNeverEchoedInSuccessOrFailureBodies() = runTest {
        val token = "dshc_" + "A".repeat(43)
        val control = FakeControl()
        val table = table(control)

        val (_, successBody) = invoke(
            table,
            DshVscreenAction.CREATE.route,
            mapOf("orientation" to "portrait"),
            token = token
        )
        assertFalse(successBody.toString().contains(token))

        control.createResult = DshVscreenResult.Failure(
            code = DshVscreenErrorCode.PRIVILEGE_UNAVAILABLE,
            message = "no privileged transport",
            hint = "grant Shizuku or root"
        )
        val (_, failureBody) = invoke(
            table,
            DshVscreenAction.CREATE.route,
            mapOf("orientation" to "portrait"),
            token = token
        )
        assertFalse(failureBody.toString().contains(token))
    }
}

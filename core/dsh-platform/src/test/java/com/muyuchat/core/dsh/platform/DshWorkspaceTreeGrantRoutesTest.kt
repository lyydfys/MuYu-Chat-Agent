package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshWorkspaceTreeGrantRoutesTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val platform = FakeWorkspaceTreePlatform()
    private val store = FakeWorkspaceTreeStore()
    private val picker = FakeWorkspaceTreePicker(DshWorkspaceTreePickerOutcome.Cancelled)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun owner(): DshWorkspaceTreeGrantOwner =
        DshWorkspaceTreeGrantOwner(platform, store, picker, scope, clock = { 5_000L })

    private fun table(owner: DshWorkspaceTreeGrantOwner): DshCapabilityRouteTable =
        DshWorkspaceTreeGrantRoutes.install(DshCapabilityRouteTable.builder(), owner).build()

    private fun request(
        path: String,
        method: DshCapabilityHttpMethod,
        query: Map<String, String> = emptyMap()
    ): DshCapabilityRouteRequest = DshCapabilityRouteRequest(
        path = path,
        method = method,
        query = query,
        bodyJson = null,
        localToken = null,
        generation = 3L,
        receivedAtMillis = 1_000L
    )

    private fun body(response: DshCapabilityRouteResponse): String {
        assertTrue(response is DshCapabilityRouteResponse.Json)
        return (response as DshCapabilityRouteResponse.Json).bodyJson
    }

    @Test
    fun installsExactlyTheThreeTreeRoutes() {
        val table = table(owner())

        assertEquals(
            listOf(
                DshWorkspaceTreeGrantRoutes.RELEASE_PATH,
                DshWorkspaceTreeGrantRoutes.REQUEST_PATH,
                DshWorkspaceTreeGrantRoutes.STATUS_PATH
            ),
            table.paths()
        )
    }

    @Test
    fun mountsStatusAsReadAndRequestAndReleaseAsSideEffects() {
        assertEquals(
            setOf(DshCapabilityHttpMethod.GET),
            DshWorkspaceTreeGrantRoutes.methodsFor(DshWorkspaceTreeGrantRoutes.STATUS_PATH)
        )
        assertEquals(
            setOf(DshCapabilityHttpMethod.POST),
            DshWorkspaceTreeGrantRoutes.methodsFor(DshWorkspaceTreeGrantRoutes.REQUEST_PATH)
        )
        assertEquals(
            setOf(DshCapabilityHttpMethod.POST),
            DshWorkspaceTreeGrantRoutes.methodsFor(DshWorkspaceTreeGrantRoutes.RELEASE_PATH)
        )
    }

    @Test
    fun statusReportsNoGrantAsOneShotSafeJson() = runBlocking {
        val handler = table(owner()).handlerFor(DshWorkspaceTreeGrantRoutes.STATUS_PATH)!!
        val json = body(handler.handle(request(DshWorkspaceTreeGrantRoutes.STATUS_PATH, DshCapabilityHttpMethod.GET)))

        assertTrue(json.contains("\"state\":\"NOT_GRANTED\""))
        assertTrue(json.contains("\"persistentTreeGrant\":false"))
        assertTrue(json.contains("\"access\":\"PERSISTENT_TREE_GRANT\""))
        assertTrue(json.contains("\"ok\":true"))
    }

    @Test
    fun statusReportsALiveGrantWithItsModes() = runBlocking {
        store.stored = DshWorkspaceTreeGrantRecord(
            treeUri = TREE_URI,
            flags = DshWorkspaceTreeFlags.READ_WRITE,
            grantedAtMillis = 1_000L
        )
        platform.observation = DshWorkspaceTreeGrantObservation.Held(DshWorkspaceTreeFlags.READ)

        val handler = table(owner()).handlerFor(DshWorkspaceTreeGrantRoutes.STATUS_PATH)!!
        val json = body(handler.handle(request(DshWorkspaceTreeGrantRoutes.STATUS_PATH, DshCapabilityHttpMethod.GET)))

        assertTrue(json.contains("\"state\":\"GRANTED\""))
        assertTrue(json.contains("\"persistentTreeGrant\":true"))
        assertTrue(json.contains("\"read\":true"))
        assertTrue(json.contains("\"write\":false"))
        assertTrue(json.contains("\"grantedAtMillis\":1000"))
    }

    @Test
    fun statusRejectsAWrongMethod() = runBlocking {
        val response = table(owner()).handlerFor(DshWorkspaceTreeGrantRoutes.STATUS_PATH)!!
            .handle(request(DshWorkspaceTreeGrantRoutes.STATUS_PATH, DshCapabilityHttpMethod.POST))

        assertEquals(405, (response as DshCapabilityRouteResponse.Json).statusCode)
    }

    @Test
    fun requestStartsOnepickerAndNeverBlocksOnTheUser() = runBlocking {
        val handler = table(owner()).handlerFor(DshWorkspaceTreeGrantRoutes.REQUEST_PATH)!!
        val json = body(
            handler.handle(
                request(
                    DshWorkspaceTreeGrantRoutes.REQUEST_PATH,
                    DshCapabilityHttpMethod.POST,
                    mapOf("write" to "false", "requestId" to "workspace-tree-req-0001")
                )
            )
        )

        assertTrue(json.contains("\"pending\":true"))
        assertEquals(1, picker.calls)
        assertEquals(false, picker.lastRequest?.writeAccess)
    }

    @Test
    fun requestForAnAlreadyGrantedTreeDoesNotOpenThePicker() = runBlocking {
        store.stored = DshWorkspaceTreeGrantRecord(
            treeUri = TREE_URI,
            flags = DshWorkspaceTreeFlags.READ,
            grantedAtMillis = 1_000L
        )
        platform.observation = DshWorkspaceTreeGrantObservation.Held(DshWorkspaceTreeFlags.READ)

        val handler = table(owner()).handlerFor(DshWorkspaceTreeGrantRoutes.REQUEST_PATH)!!
        val json = body(handler.handle(request(DshWorkspaceTreeGrantRoutes.REQUEST_PATH, DshCapabilityHttpMethod.POST)))

        assertTrue(json.contains("\"state\":\"GRANTED\""))
        assertEquals(0, picker.calls)
    }

    @Test
    fun requestRejectsUnknownQueryKeysAndBadWriteFlags() = runBlocking {
        val handler = table(owner()).handlerFor(DshWorkspaceTreeGrantRoutes.REQUEST_PATH)!!

        val unknown = body(
            handler.handle(
                request(
                    DshWorkspaceTreeGrantRoutes.REQUEST_PATH,
                    DshCapabilityHttpMethod.POST,
                    mapOf("path" to "/sdcard")
                )
            )
        )
        assertTrue(unknown.contains("\"ok\":false"))
        assertTrue(unknown.contains("INVALID_REQUEST"))
        assertEquals(0, picker.calls)

        val badWrite = body(
            handler.handle(
                request(
                    DshWorkspaceTreeGrantRoutes.REQUEST_PATH,
                    DshCapabilityHttpMethod.POST,
                    mapOf("write" to "maybe")
                )
            )
        )
        assertTrue(badWrite.contains("\"ok\":false"))
        assertEquals(0, picker.calls)
    }

    @Test
    fun releaseReportsRevokedAfterThePlatformReleased() = runBlocking {
        store.stored = DshWorkspaceTreeGrantRecord(
            treeUri = TREE_URI,
            flags = DshWorkspaceTreeFlags.READ_WRITE,
            grantedAtMillis = 1_000L
        )
        platform.observation = DshWorkspaceTreeGrantObservation.Held(DshWorkspaceTreeFlags.READ_WRITE)

        val handler = table(owner()).handlerFor(DshWorkspaceTreeGrantRoutes.RELEASE_PATH)!!
        val json = body(handler.handle(request(DshWorkspaceTreeGrantRoutes.RELEASE_PATH, DshCapabilityHttpMethod.POST)))

        assertTrue(json.contains("\"state\":\"REVOKED\""))
        assertTrue(json.contains("\"persistentTreeGrant\":false"))
        assertEquals(TREE_URI, platform.releasedUri)
    }

    @Test
    fun releaseWithoutAGrantStaysNotGranted() = runBlocking {
        val handler = table(owner()).handlerFor(DshWorkspaceTreeGrantRoutes.RELEASE_PATH)!!
        val json = body(handler.handle(request(DshWorkspaceTreeGrantRoutes.RELEASE_PATH, DshCapabilityHttpMethod.POST)))

        assertTrue(json.contains("\"state\":\"NOT_GRANTED\""))
    }
}

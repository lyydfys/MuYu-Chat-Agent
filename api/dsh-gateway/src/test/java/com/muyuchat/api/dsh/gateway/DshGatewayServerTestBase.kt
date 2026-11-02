package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.DshGatewayModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import java.util.concurrent.CopyOnWriteArrayList

/** Shared lifecycle for the real-loopback-socket integration tests. */
abstract class DshGatewayServerTestBase {

    protected lateinit var backend: FakeDshGatewayBackend
    protected lateinit var server: DshGatewayServer
    protected lateinit var endpoint: DshGatewayEndpoint
    protected val logs = CopyOnWriteArrayList<String>()

    protected open fun limits(): DshGatewayLimits = DshGatewayLimits.Default

    protected open fun models(): List<DshGatewayModel> = listOf(fakeGatewayModel())

    @Before
    fun startGateway() {
        backend = FakeDshGatewayBackend(models())
        server = DshGatewayServer(
            backend = backend,
            runtimeEpoch = 7L,
            limits = limits(),
            logger = DshGatewayLogger { level, message -> logs += "$level $message" }
        )
        endpoint = runBlocking { server.start() }
    }

    @After
    fun stopGateway() {
        if (::server.isInitialized) runBlocking { server.stop() }
    }

    protected fun activeCallCount(): Int {
        val response = DshTestHttp.request(endpoint, "GET", "/healthz")
        check(response.status == 200) { "healthz failed: ${response.status} ${response.body}" }
        return JSONObject(response.body).getInt("active_calls")
    }

    protected fun chat(stream: Boolean, bodyExtra: String = ""): DshTestHttpResponse =
        DshTestHttp.request(
            endpoint = endpoint,
            method = "POST",
            path = "/v1/chat/completions",
            body = standardChatBody(
                stream = stream,
                includeUsage = if (stream) true else null,
                extraFieldsJson = bodyExtra
            )
        )

    protected fun cancelCall(callId: String): DshTestHttpResponse =
        DshTestHttp.request(
            endpoint = endpoint,
            method = "POST",
            path = "/v1/mca/calls/cancel",
            body = JSONObject().put("call_id", callId).toString()
        )
}

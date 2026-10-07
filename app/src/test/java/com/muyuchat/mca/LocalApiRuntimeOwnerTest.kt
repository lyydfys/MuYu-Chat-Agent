package com.muyuchat.mca

import com.muyuchat.api.local.LocalApiControlPlane
import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.engine.GenerationParams
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalApiRuntimeOwnerTest {

    @Test
    fun formatsUrlsCorrectly() {
        val owner = LocalApiRuntimeOwner()
        assertEquals("http://127.0.0.1:11435/v1", owner.apiUrl("127.0.0.1"))
        assertEquals("http://192.168.1.100:11435/v1", owner.currentOpenApiAddress("192.168.1.100"))
        assertEquals("http://本机局域网IP:11435/v1", owner.currentOpenApiAddress(null))
    }

    @Test
    fun managesPublicationAndReleaseLifecycle() {
        val owner = LocalApiRuntimeOwner()
        var replaced = false
        owner.claim { replaced = true }

        val fakeControlPlane = Proxy.newProxyInstance(
            LocalApiControlPlane::class.java.classLoader,
            arrayOf(LocalApiControlPlane::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> args?.getOrNull(0) === proxy
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeControlPlane"
                else -> null
            }
        } as LocalApiControlPlane

        val fakeAuxPort = object : LocalApiAuxProviderPort {
            override fun analysisParamsJson(): String = "{}"
            override fun analysisGenerationParams(): GenerationParams = GenerationParams()
            override fun modelsJsonText(): String = "{}"
            override fun textualInversionsJsonText(): String = "{}"
            override fun modelRuntimeStatesJsonText(): String = "{}"
            override fun deviceProfileJsonText(): String = "{}"
            override fun recommendationJsonText(requestJson: String): String = "{}"
            override suspend fun benchmarkJsonText(): String = "{}"
            override suspend fun generateImage(requestId: String, body: String): String = "{}"
        }

        owner.publishBindings(fakeAuxPort, fakeControlPlane)
        assertEquals(fakeControlPlane, LocalApiRuntime.controlPlane)
        assertNotNull(LocalApiRuntime.benchmarkJsonProvider)

        owner.release()
        assertEquals(null, LocalApiRuntime.controlPlane)
    }
}

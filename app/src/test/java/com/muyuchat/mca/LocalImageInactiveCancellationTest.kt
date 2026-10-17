package com.muyuchat.mca

import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalImageInactiveCancellationTest {
    @Test
    fun cancellingAnInactiveProviderDoesNotInitializeAnyRuntimeResource() {
        val reads = mutableListOf<String>()
        val port = Proxy.newProxyInstance(
            LocalImageCancellationPort::class.java.classLoader,
            arrayOf(LocalImageCancellationPort::class.java)
        ) { _, method, _ ->
            reads += method.name
            check(method.name == "getActiveRuntime") {
                "Inactive cancellation initialized ${method.name}"
            }
            null
        } as LocalImageCancellationPort

        assertFalse(runLocalImageCancellation(port))
        assertEquals(listOf("getActiveRuntime"), reads)
    }
}

package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DshCapabilityRouteRegistryTest {

    private val handler = DshCapabilityRouteHandler {
        DshCapabilityRouteResponse.Json(200, "{}")
    }

    @Test
    fun duplicateRouteRegistrationIsRejected() {
        val builder = DshCapabilityRouteRegistry.builder()
            .post("/notify", DshCapability.ANDROID_SYSTEM, handler)

        assertThrows(IllegalArgumentException::class.java) {
            builder.get("/notify", DshCapability.ANDROID_SYSTEM, handler)
        }
    }

    @Test
    fun emptyMethodSetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DshCapabilityRouteDefinition(
                path = "/notify",
                methods = emptySet(),
                capability = DshCapability.ANDROID_SYSTEM,
                handler = handler
            )
        }
    }

    @Test
    fun unknownPathIsNotFound() {
        val registry = DshCapabilityRouteRegistry.builder()
            .post("/notify", DshCapability.ANDROID_SYSTEM, handler)
            .build()

        assertEquals(
            DshCapabilityRouteResolution.NotFound,
            registry.resolve("/clipboard", DshCapabilityHttpMethod.POST)
        )
    }

    @Test
    fun wrongMethodReportsTheAllowedSet() {
        val registry = DshCapabilityRouteRegistry.builder()
            .post("/notify", DshCapability.ANDROID_SYSTEM, handler)
            .build()

        val resolution = registry.resolve("/notify", DshCapabilityHttpMethod.GET)

        assertTrue(resolution is DshCapabilityRouteResolution.MethodNotAllowed)
        assertEquals(
            setOf(DshCapabilityHttpMethod.POST),
            (resolution as DshCapabilityRouteResolution.MethodNotAllowed).allowed
        )
    }

    @Test
    fun unknownVerbNameResolvesAsMethodNotAllowed() {
        val registry = DshCapabilityRouteRegistry.builder()
            .get("/usage", DshCapability.ANDROID_SYSTEM, handler)
            .build()

        val resolution = registry.resolve("/usage", null)

        assertTrue(resolution is DshCapabilityRouteResolution.MethodNotAllowed)
    }

    @Test
    fun registryPublishesASortedContractRouteTable() {
        val registry = DshCapabilityRouteRegistry.builder()
            .post("/notify", DshCapability.ANDROID_SYSTEM, handler)
            .get("/usage", DshCapability.ANDROID_SYSTEM, handler)
            .build()

        assertEquals(listOf("/notify", "/usage"), registry.paths())
        assertEquals(listOf("/notify", "/usage"), registry.routeTable().paths())
        assertNotNull(registry.routeTable().handlerFor("/notify"))
        assertNull(registry.routeTable().handlerFor("/unknown"))
        assertEquals(DshCapability.ANDROID_SYSTEM, requireNotNull(registry.definitionFor("/usage")).capability)
    }
}

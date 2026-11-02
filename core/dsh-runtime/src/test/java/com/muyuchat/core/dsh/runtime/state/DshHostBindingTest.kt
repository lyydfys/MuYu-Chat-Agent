package com.muyuchat.core.dsh.runtime.state

import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.capability.DshCapabilityBridgeEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The binding id is the only thing the app process and the `:dsh` process exchange to decide
 * whether the Node host already runs the requested configuration, so it must be
 * - sensitive to every field the host actually reads at process start, and
 * - safe to log/transport: never a bearer or bridge token.
 */
class DshHostBindingTest {

    private fun gateway(
        baseUrl: String = "http://127.0.0.1:41000/v1",
        token: String = "gateway-token-0001",
        catalog: String = "rev-a"
    ) = DshGatewayEndpoint(baseUrl = baseUrl, bearerToken = token, catalogRevision = catalog)

    private fun bridge(
        generation: Long = 1L,
        port: Int = 42001,
        token: String = "bridge-token-0001",
        root: Boolean = false,
        shizuku: Boolean = false
    ) = DshCapabilityBridgeEndpoint(
        appLocalPort = port,
        accessibilityPort = port,
        generation = generation,
        localToken = token,
        rootAvailable = root,
        shizukuAvailable = shizuku,
        approvalAsk = true
    )

    @Test
    fun identicalBindingsShareOneId() {
        val one = DshHostConfig(gateway(), bridge(), hostBuild = "engine-1")
        val two = DshHostConfig(gateway(), bridge(), hostBuild = "engine-1")
        assertEquals(one.id, two.id)
        assertEquals(one, two)
        assertEquals(one.hashCode(), two.hashCode())
    }

    @Test
    fun everyHostReadFieldChangesTheId() {
        val base = DshHostConfig(gateway(), bridge(), hostBuild = "engine-1")
        val variants = listOf(
            "gateway port" to DshHostConfig(gateway(baseUrl = "http://127.0.0.1:41001/v1"), bridge(), "engine-1"),
            "gateway token" to DshHostConfig(gateway(token = "gateway-token-0002"), bridge(), "engine-1"),
            "catalog revision" to DshHostConfig(gateway(catalog = "rev-b"), bridge(), "engine-1"),
            "bridge generation" to DshHostConfig(gateway(), bridge(generation = 2L), "engine-1"),
            "bridge token" to DshHostConfig(gateway(), bridge(token = "bridge-token-0002"), "engine-1"),
            "bridge port" to DshHostConfig(gateway(), bridge(port = 42002), "engine-1"),
            "privilege facts" to DshHostConfig(gateway(), bridge(root = true), "engine-1"),
            "host build" to DshHostConfig(gateway(), bridge(), "engine-2"),
            "unbound gateway" to DshHostConfig(null, bridge(), "engine-1")
        )
        for ((label, variant) in variants) {
            assertNotEquals(label, base.id, variant.id)
        }
    }

    @Test
    fun aModelCatalogChangeAloneIsVisibleInTheId() {
        val before = DshHostConfig(gateway(catalog = "rev-before"), bridge(), "engine-1")
        val after = DshHostConfig(gateway(catalog = "rev-after"), bridge(), "engine-1")
        assertNotEquals(before.id, after.id)
        // The id is a digest, so neither id leaks the endpoint's bearer token.
        assertFalse(before.id.value.contains("gateway-token-0001"))
        assertFalse(after.id.value.contains("gateway-token-0001"))
    }

    @Test
    fun summariesAndToStringNeverContainASecret() {
        val config = DshHostConfig(gateway(token = "secret-gateway-token"), bridge(token = "secret-bridge-token"), "engine-1")
        assertFalse(config.summary.contains("secret-gateway-token"))
        assertFalse(config.summary.contains("secret-bridge-token"))
        assertFalse(config.toString().contains("secret-gateway-token"))
        assertFalse(config.toString().contains("secret-bridge-token"))
        // The endpoint is still identifiable for diagnosis.
        assertTrue(config.summary.contains("http://127.0.0.1:41000/v1"))
        assertTrue(config.summary.contains("engine-1"))
    }

    @Test
    fun wireIdsAreValidatedAndNeverRestoredFromArbitraryText() {
        val real = DshHostConfig(gateway(), bridge(), "engine-1").id
        assertEquals(real, DshBindingId.fromWire(real.value))
        assertEquals(DshBindingId.Unknown, DshBindingId.fromWire(null))
        assertEquals(DshBindingId.Unknown, DshBindingId.fromWire(""))
        assertEquals(DshBindingId.Unknown, DshBindingId.fromWire("http://127.0.0.1:1/v1"))
        assertEquals(DshBindingId.Unknown, DshBindingId.fromWire("b-" + "x".repeat(64)))
        assertNotEquals(real, DshBindingId.Unknown)
    }

    @Test
    fun changeLabelsNameEveryObservedDifference() {
        val previous = DshHostConfig(gateway(), bridge(), hostBuild = "engine-1")
        val desired = DshHostConfig(
            gateway(
                baseUrl = "http://127.0.0.1:41001/v1",
                token = "gateway-token-0002",
                catalog = "rev-b"
            ),
            bridge(generation = 2L, port = 42002, token = "bridge-token-0002"),
            hostBuild = "engine-2"
        )
        assertEquals(
            "gateway-port+gateway-token+model-catalog+capability-bridge+host-build",
            DshApplyPolicy.changeLabel(previous, desired)
        )
        assertEquals("first-binding", DshApplyPolicy.changeLabel(null, desired))
        assertEquals("binding-unchanged", DshApplyPolicy.changeLabel(previous, previous))
        assertEquals(
            "gateway-bound",
            DshApplyPolicy.changeLabel(DshHostConfig(null, bridge()), DshHostConfig(gateway(), bridge()))
        )
        assertEquals(
            "gateway-unbound",
            DshApplyPolicy.changeLabel(DshHostConfig(gateway(), bridge()), DshHostConfig(null, bridge()))
        )
        assertEquals(
            "capability-unbound",
            DshApplyPolicy.changeLabel(DshHostConfig(gateway(), bridge()), DshHostConfig(gateway(), null))
        )
    }
}

package com.muyuchat.core.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekNpugRouteJsonTest {
    private val ditc1c = resource("/npug/ditc1c.route.json")
    private val dva1 = resource("/npug/dva1.route.json")

    @Test
    fun lockedDitc1cRouteCarriesAllSeventyInputsAndOneOutput() {
        val route = MediaTekNpugRouteJson.parse(ditc1c)

        assertEquals("ditc1c_w8_w16L1024s4", route.graph)
        assertEquals(70, route.inputs.size)
        assertEquals(1, route.outputs.size)
        assertEquals((0 until 70).toList(), route.inputs.map { it.routeIndex })
        assertEquals(listOf(0), route.outputs.map { it.routeIndex })
        assertEquals(8_416, route.tensorCount)
        assertEquals(3_481, route.operationCount)
        assertEquals("[1,1024,2048]", shape(route.inputs[0].dims))
        assertEquals("[1024,64]", shape(route.outputs.single().dims))
        assertTrue(route.inputs.all { it.scale.isFinite() && it.scale > 0.0 })
        assertTrue(route.inputs.all { it.quantTag in 0..1 })
    }

    @Test
    fun lockedDva1RouteCarriesAllSixtyEightInputs() {
        val route = MediaTekNpugRouteJson.parse(dva1)

        assertEquals("dva1_w8_w16L1024s4", route.graph)
        assertEquals(68, route.inputs.size)
        assertEquals((0 until 68).toList(), route.inputs.map { it.routeIndex })
        assertEquals(8_210, route.tensorCount)
        assertEquals(3_398, route.operationCount)
        assertEquals("[1,1024,68]", shape(route.inputs[0].dims))
    }

    @Test
    fun routeIdentityMustMatchTheManifestTopologyAndCompiledNetwork() {
        val manifest = validManifest()
        val variant = manifest.variants.single()
        val route = MediaTekNpugRouteJson.parse(ditc1c)

        assertTrue(route.verifyAgainst(variant.route, variant.topology, variant.compiledNetwork).isEmpty())

        val wrongTopology = variant.topology.copy(
            asset = variant.topology.asset.copy(sha256 = "9".repeat(64)),
        )
        assertTrue(
            route.verifyAgainst(variant.route, wrongTopology, variant.compiledNetwork)
                .single().contains("stub identity"),
        )
        assertTrue(
            route.verifyAgainst(variant.route, variant.topology, null)
                .single().contains("compiled network that the manifest does not install"),
        )
    }

    @Test
    fun declaredCountsMustMatchTheListedTensors() {
        assertRejected("declares 69 but lists 70") {
            MediaTekNpugRouteJson.parse(ditc1c.replace("\"inputCount\": 70", "\"inputCount\": 69"))
        }
        assertRejected("declares 2 but lists 1") {
            MediaTekNpugRouteJson.parse(ditc1c.replace("\"outputCount\": 1", "\"outputCount\": 2"))
        }
    }

    @Test
    fun duplicateIndexesAndNonPositiveScalesAreRejected() {
        assertRejected("indexes must be contiguous") {
            MediaTekNpugRouteJson.parse(ditc1c.replace("\"routeIndex\": 1,", "\"routeIndex\": 0,"))
        }
        assertRejected("scale must be finite and positive") {
            MediaTekNpugRouteJson.parse(
                ditc1c.replace("\"scale\": 0.0003345913137309253", "\"scale\": 0.0"),
            )
        }
    }

    @Test
    fun failedGeneratorChecksAreRejected() {
        assertRejected("must be a nonempty set of passing checks") {
            MediaTekNpugRouteJson.parse(
                ditc1c.replace("\"inputCountMatchesSchema\": true", "\"inputCountMatchesSchema\": false"),
            )
        }
        assertRejected("checksAllPass") {
            MediaTekNpugRouteJson.parse(ditc1c.replace("\"checksAllPass\": true", "\"checksAllPass\": false"))
        }
    }

    @Test
    fun unknownFieldsFailClosed() {
        assertRejected("unknown fields") {
            MediaTekNpugRouteJson.parse(ditc1c.replaceFirst("\"graph\"", "\"deviceAllowlist\": [], \"graph\""))
        }
    }

    private fun validManifest(): MediaTekAotManifest = MediaTekAotManifest(
        schema = MediaTekAotManifest.SCHEMA,
        family = MediaTekAotManifest.ANIMA_FAMILY,
        runtime = MediaTekAotManifest.RUNTIME,
        modelIdentity = MediaTekAotModelIdentity("ANIMA", "e".repeat(64)),
        variants = listOf(
            MediaTekAotVariant(
                id = "npug-aot",
                transport = MediaTekGraphTransport.NEURON_COMPILED_NETWORK,
                topology = MediaTekNpugTopologyContract(
                    asset = asset(
                        "assets/npug/ditc1c_w8_w16L1024s4.npug",
                        1_306_364L,
                        "5d92980596391a3aa67e94251eccaff75d799e59d93ddd8aad589891dab0c013",
                    ),
                    containerMagic = MediaTekNpugTopologyContract.CONTAINER_MAGIC,
                    mode = MediaTekNpugContainerMode.STUB_TOPOLOGY,
                    tensorCount = 8_416,
                    operationCount = 3_481,
                    weightDataBytes = 0L,
                ),
                compiledNetwork = MediaTekCompiledNetworkContract(
                    asset = asset(
                        "assets/npug/ditc1c_w8_w16L1024s4.dla",
                        447_541_580L,
                        "3a5b1ac7310d71d68f8b9262b2b923d6954e801d594c29c952fb7dea6460307d",
                    ),
                    runtimeMajor = 9,
                    graphId = "ditc1c_w8_w16L1024s4",
                ),
                route = MediaTekNpugRouteContract(
                    asset = asset("assets/npug/ditc1c.route.json", 29_998L, "a".repeat(64)),
                    graphId = "ditc1c_w8_w16L1024s4",
                    inputCount = 70,
                    outputCount = 1,
                    inputEncoding = MediaTekNpugInputEncodingContract(
                        defaultEncoding = MediaTekNpugTensorEncoding.IEEE_HALF,
                        overrides = emptyMap(),
                        rounding = MediaTekNpugRounding.HALF_AWAY_FROM_ZERO,
                        saturates = true,
                    ),
                ),
            ),
        ),
        conditioning = MediaTekAotConditioningContract(
            mode = MediaTekAotConditioningMode.FIXED_CONTEXT,
            promptEffective = false,
            contextVersion = 1,
            contextAsset = asset("assets/npug/ctx.bin", 1_048_592L, "f".repeat(64)),
            contextDtype = "float32",
            contextShape = listOf(256, 1024),
            textAssets = emptyList(),
        ),
        parameters = MediaTekAotParameterContract(false, MediaTekAotCfgMode.NONE),
        profiles = listOf(
            MediaTekAotProfile(
                id = "profile-1024",
                widthPixels = 512,
                heightPixels = 512,
                gridWidth = 32,
                gridHeight = 32,
                tokenCount = 1024,
                supportedSteps = setOf(6),
                defaultSteps = 6,
                defaultCfgScale = 1.0f,
                minimumCfgScale = 1.0f,
                maximumCfgScale = 1.0f,
                sigmaSchedule = MediaTekNpugSigmaSchedule.SHIFTED_FLOW_MATCHING_3T,
                modulations = listOf(
                    MediaTekAotModulationContract(
                        asset = asset("assets/npug/modsq16s4_1024_6.bin", 6_242_324L, "1".repeat(64)),
                        stepCount = 6,
                        blockCount = 28,
                        blockSegments = 9,
                        finalSegments = 2,
                    ),
                ),
                ropeAsset = asset("assets/npug/rope_32x32.bin", 524_288L, "2".repeat(64)),
                vae = vaeContract(),
            ),
        ),
    )

    private fun vaeContract(): MediaTekAotVaeContract = MediaTekAotVaeContract(
        transport = MediaTekAotVaeTransport.NEURON_COMPILED_NETWORK,
        asset = asset("assets/npug/vaedec_64.dla", 26_432_870L, "3".repeat(64)),
        input = MediaTekAotVaeTensorContract(
            dims = listOf(1, 64, 64, 16),
            dtype = MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16,
            layout = "NHWC",
            scale = 1.9019890169147402e-4,
            zeroPoint = 0,
        ),
        output = MediaTekAotVaeTensorContract(
            dims = listOf(1, 512, 512, 3),
            dtype = MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16,
            layout = "NHWC",
            scale = 4.065645771333948e-5,
            zeroPoint = 0,
        ),
    )

    private fun asset(file: String, size: Long, sha: String) = MediaTekBundleAssetContract(
        file = file,
        sha256 = sha,
        sizeBytes = size,
        mediaType = "application/octet-stream",
    )

    private fun resource(path: String): String =
        javaClass.getResource(path)?.readText(Charsets.UTF_8)
            ?: throw AssertionError("missing test resource $path")

    private fun shape(dims: List<Int>) = dims.joinToString(separator = ",", prefix = "[", postfix = "]")

    private fun assertRejected(expected: String, action: () -> Unit) {
        try {
            action()
        } catch (error: IllegalArgumentException) {
            assertTrue("Expected '${error.message}' to contain '$expected'", error.message.orEmpty().contains(expected))
            return
        }
        throw AssertionError("Expected failure containing '$expected'")
    }
}

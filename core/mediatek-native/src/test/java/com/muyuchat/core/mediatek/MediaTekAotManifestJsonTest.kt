package com.muyuchat.core.mediatek

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MediaTekAotManifestJsonTest {
    private val stubAsset = asset(
        "assets/npug/ditc1c_w8_w16L1024s4.npug",
        1_306_364L,
        "5d92980596391a3aa67e94251eccaff75d799e59d93ddd8aad589891dab0c013",
    )
    private val dlaAsset = asset(
        "assets/npug/ditc1c_w8_w16L1024s4.dla",
        447_541_580L,
        "3a5b1ac7310d71d68f8b9262b2b923d6954e801d594c29c952fb7dea6460307d",
    )
    private val routeAsset = asset("assets/npug/ditc1c.route.json", 29_998L, "a".repeat(64))

    @Test
    fun compiledNetworkVariantRoundTripsAndVerifiesItsEncodingContract() {
        val manifest = validManifest()
        val encoded = MediaTekAotManifestJson.encode(manifest)
        val parsed = MediaTekAotManifestJson.parse(encoded)

        assertEquals(manifest, parsed)
        assertEquals(encoded, MediaTekAotManifestJson.encode(parsed))
        assertEquals("mca.mediatek-image/4", parsed.schema)
        assertEquals(MediaTekGraphTransport.NEURON_COMPILED_NETWORK, parsed.variants.single().transport)
        assertEquals(MediaTekNpugContainerMode.STUB_TOPOLOGY, parsed.variants.single().topology.mode)
        assertEquals(70, parsed.variants.single().route.inputCount)
        assertTrue(parsed.executionBlockers().isEmpty())
        assertTrue(parsed.variants.single().executable)
    }

    @Test
    fun stubTopologyWithoutCompiledNetworkParsesButIsNotExecutable() {
        val variant = validManifest().variants.single().copy(compiledNetwork = null)
        val parsed = MediaTekAotManifestJson.parse(
            MediaTekAotManifestJson.encode(validManifest().copy(variants = listOf(variant))),
        )

        assertFalse(parsed.executionBlockers().isEmpty())
        assertFalse(parsed.variants.single().executable)
        assertTrue(parsed.executionBlockers().single().contains("requires a matching compiled network"))
    }

    @Test
    fun fullGraphWithoutConstantDataIsRejected() {
        val topology = validManifest().variants.single().topology.copy(
            mode = MediaTekNpugContainerMode.FULL_GRAPH,
            weightDataBytes = 0L,
        )
        assertRejected("FULL_GRAPH requires its constant weight data") {
            MediaTekAotManifestJson.parse(encodeWithVariant(validManifest().variants.single().copy(topology = topology)))
        }
    }

    @Test
    fun stubTopologyWithWeightDataIsRejected() {
        val topology = validManifest().variants.single().topology.copy(weightDataBytes = 512L)
        assertRejected("STUB_TOPOLOGY must not declare weight data") {
            MediaTekAotManifestJson.parse(encodeWithVariant(validManifest().variants.single().copy(topology = topology)))
        }
    }

    @Test
    fun jitFallbackAndNonAotTransportsAreRejected() {
        assertRejected("must not allow a JIT fallback") {
            MediaTekAotManifestJson.parse(
                encodeWithVariant(validManifest().variants.single().copy(jitFallbackAllowed = true)),
            )
        }
        assertRejected("is not an NPUG/AOT transport") {
            MediaTekAotManifestJson.parse(
                encodeWithVariant(validManifest().variants.single().copy(transport = MediaTekGraphTransport.LITERT_JIT)),
            )
        }
        assertRejected("is not an NPUG/AOT transport") {
            MediaTekAotManifestJson.parse(
                encodeWithVariant(validManifest().variants.single().copy(transport = MediaTekGraphTransport.LITERT_AOT)),
            )
        }
    }

    @Test
    fun restoreFailureNeverFallsBackToJit() {
        val stubOnly = validManifest().copy(
            variants = listOf(validManifest().variants.single().copy(compiledNetwork = null)),
        )
        assertTrue(stubOnly.recoveryVariantsAfterCompiledNetworkFailure().isEmpty())

        val fullGraph = validManifest().variants.single().copy(
            id = "npug-full",
            transport = MediaTekGraphTransport.NPUG_NATIVE,
            topology = validManifest().variants.single().topology.copy(
                asset = asset("assets/npug/ditc1c-full.npug", 443_597_568L, "b".repeat(64)),
                mode = MediaTekNpugContainerMode.FULL_GRAPH,
                weightDataBytes = 443_597_568L,
            ),
            compiledNetwork = null,
        )
        val withFullGraph = MediaTekAotManifestJson.parse(
            MediaTekAotManifestJson.encode(validManifest().copy(variants = validManifest().variants + fullGraph)),
        )
        val recovery = withFullGraph.recoveryVariantsAfterCompiledNetworkFailure()
        assertEquals(1, recovery.size)
        assertEquals(MediaTekGraphTransport.NPUG_NATIVE, recovery.single().transport)
        assertTrue(recovery.none { it.transport == MediaTekGraphTransport.LITERT_JIT })
    }

    @Test
    fun fixedContextRejectsPromptsNegativeAndNonDefaultCfgBeforeExecution() {
        val manifest = validManifest()

        assertEquals(
            listOf("unsupported prompt: bundle uses a fixed ACTX context; prompts are not applied"),
            manifest.requestRejections("profile-1024", "a cat", "", null, null),
        )
        val rejections = manifest.requestRejections("profile-1024", "", "blurry", 2.0f, 8)
        assertTrue(rejections.any { it.contains("unsupported negativePrompt") })
        assertTrue(rejections.any { it.contains("unsupported steps") })
        assertTrue(rejections.any { it.contains("unsupported cfgScale") })
        assertTrue(manifest.requestRejections("profile-1024", "", "", 1.0f, 6).isEmpty())
    }

    @Test
    fun dynamicTextRequiresItsTextAssetsAndCannotCarryAFixedContext() {
        val dynamic = MediaTekAotConditioningContract(
            mode = MediaTekAotConditioningMode.DYNAMIC_TEXT,
            promptEffective = true,
            contextVersion = null,
            contextAsset = null,
            contextDtype = null,
            contextShape = emptyList(),
            textAssets = listOf(asset("assets/npug/text.bin", 1024L, "c".repeat(64))),
        )
        val manifest = validManifest().copy(conditioning = dynamic)
        assertTrue(MediaTekAotManifestJson.parse(MediaTekAotManifestJson.encode(manifest)).requestRejections(
            "profile-1024", "a cat", "", null, null,
        ).isEmpty())

        assertRejected("DYNAMIC_TEXT must not declare a fixed context") {
            MediaTekAotManifestJson.parse(
                MediaTekAotManifestJson.encode(
                    manifest.copy(
                        conditioning = dynamic.copy(
                            contextAsset = asset("assets/npug/ctx.bin", 1024L, "d".repeat(64)),
                            contextVersion = 1,
                            contextDtype = "float32",
                            contextShape = listOf(256, 1024),
                        ),
                    ),
                ),
            )
        }
        assertRejected("DYNAMIC_TEXT requires at least one text asset") {
            MediaTekAotManifestJson.parse(
                MediaTekAotManifestJson.encode(manifest.copy(conditioning = dynamic.copy(textAssets = emptyList()))),
            )
        }
    }

    @Test
    fun int16InputsMustDeclareSaturationAndStayInsideInt16() {
        val route = validManifest().variants.single().route
        val encoding = route.inputEncoding.copy(
            overrides = mapOf(2 to MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16),
            saturates = false,
        )
        assertRejected("SCALED_SIGNED_INT16 inputs must declare saturation") {
            MediaTekAotManifestJson.parse(
                encodeWithVariant(validManifest().variants.single().copy(route = route.copy(inputEncoding = encoding))),
            )
        }
        assertRejected("encoding overrides reference missing route indexes") {
            MediaTekAotManifestJson.parse(
                encodeWithVariant(
                    validManifest().variants.single().copy(
                        route = route.copy(
                            inputEncoding = route.inputEncoding.copy(
                                overrides = mapOf(70 to MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun profileGridPixelTokenAndAnimaSegmentRulesAreEnforced() {
        val profile = validManifest().profiles.single()
        assertRejected("pixels must be 16 x grid") {
            MediaTekAotManifestJson.parse(MediaTekAotManifestJson.encode(validManifest().copy(
                profiles = listOf(profile.copy(widthPixels = 500)),
            )))
        }
        assertRejected("tokenCount must equal gridWidth x gridHeight") {
            MediaTekAotManifestJson.parse(MediaTekAotManifestJson.encode(validManifest().copy(
                profiles = listOf(profile.copy(tokenCount = 512)),
            )))
        }
        val threeWay = profile.modulations.single().copy(blockSegments = 3)
        assertRejected("blockSegments must be 9") {
            MediaTekAotManifestJson.parse(MediaTekAotManifestJson.encode(validManifest().copy(
                profiles = listOf(profile.copy(modulations = listOf(threeWay))),
            )))
        }
    }

    @Test
    fun unknownSchemaUnknownFieldsAndWrongTypesFailClosed() {
        val encoded = MediaTekAotManifestJson.encode(validManifest())
        assertRejected("schema must be mca.mediatek-image/4") {
            MediaTekAotManifestJson.parse(encoded.replace("mca.mediatek-image/4", "mca.mediatek-image/3"))
        }
        assertRejected("unknown fields") {
            MediaTekAotManifestJson.parse(encoded.replace("{\"schema\"", "{\"deviceAllowlist\":[],\"schema\""))
        }
        assertRejected("must be a boolean") {
            MediaTekAotManifestJson.parse(encoded.replace("\"jitFallbackAllowed\":false", "\"jitFallbackAllowed\":\"false\""))
        }
    }

    private fun encodeWithVariant(variant: MediaTekAotVariant): String =
        MediaTekAotManifestJson.encode(validManifest().copy(variants = listOf(variant)))

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
                    asset = stubAsset,
                    containerMagic = MediaTekNpugTopologyContract.CONTAINER_MAGIC,
                    mode = MediaTekNpugContainerMode.STUB_TOPOLOGY,
                    tensorCount = 8_416,
                    operationCount = 3_481,
                    weightDataBytes = 0L,
                ),
                compiledNetwork = MediaTekCompiledNetworkContract(
                    asset = dlaAsset,
                    runtimeMajor = 9,
                    graphId = "ditc1c_w8_w16L1024s4",
                ),
                route = MediaTekNpugRouteContract(
                    asset = routeAsset,
                    graphId = "ditc1c_w8_w16L1024s4",
                    inputCount = 70,
                    outputCount = 1,
                    inputEncoding = MediaTekNpugInputEncodingContract(
                        defaultEncoding = MediaTekNpugTensorEncoding.IEEE_HALF,
                        overrides = mapOf(2 to MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16),
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
        parameters = MediaTekAotParameterContract(
            supportsNegativePrompt = false,
            cfgMode = MediaTekAotCfgMode.NONE,
        ),
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

    private fun assertRejected(expected: String, action: () -> Unit) {
        try {
            action()
        } catch (error: IllegalArgumentException) {
            assertTrue("Expected '${error.message}' to contain '$expected'", error.message.orEmpty().contains(expected))
            return
        }
        throw AssertionError("Expected failure containing '$expected'")
    }

    @Test
    fun parsesTheDynamicTextDeviceKitWhenPresent() {
        val file = File("../../build/chain-kit-dynamic/chain-manifest.json")
        assumeTrue("dynamic text kit is absent", file.isFile)
        val manifest = MediaTekAotManifestJson.parse(file.readText())
        assertEquals(MediaTekAotConditioningMode.DYNAMIC_TEXT, manifest.conditioning.mode)
        assertTrue(manifest.conditioning.promptEffective)
        assertEquals(null, manifest.conditioning.contextAsset)
        assertEquals(2, manifest.conditioning.textAssets.size)
        val weights = manifest.conditioning.textAssets.first { it.file.endsWith("text.bin") }
        assertEquals(1_461_438_080L, weights.sizeBytes)
        assertEquals("685e2d45d750068310a36a15de8c7b32b44312c1dc89dcaf1737bb321044e59e", weights.sha256)
        val tokenizer = manifest.conditioning.textAssets.first { it.file.endsWith("tok.bin") }
        assertEquals(2_276_873L, tokenizer.sizeBytes)
        assertEquals("987b9ef5a03c11f6e3783a2c83e5d4fe165cadafd456e1989dbb7c0d685d0e6e", tokenizer.sha256)
        assertTrue(manifest.assets().any { it.file.endsWith("text.bin") })
    }
}

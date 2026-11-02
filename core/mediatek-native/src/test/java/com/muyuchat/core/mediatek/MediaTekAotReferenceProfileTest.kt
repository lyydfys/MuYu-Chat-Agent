package com.muyuchat.core.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekAotReferenceProfileTest {
    @Test
    fun lockedReferenceProfileParsesAndItsRouteVerifies() {
        val manifest = MediaTekAotManifestJson.parse(resource("/npug/reference-aot-manifest.json"))
        val route = MediaTekNpugRouteJson.parse(resource("/npug/ditc1c.route.json"))
        val variant = manifest.variants.single()
        val profile = manifest.profiles.single()

        assertEquals("mca.mediatek-image/4", manifest.schema)
        assertEquals("reference-1024-32x32", profile.id)
        assertEquals(512, profile.widthPixels)
        assertEquals(512, profile.heightPixels)
        assertEquals(1024, profile.tokenCount)
        assertEquals(setOf(6), profile.supportedSteps)
        assertEquals(MediaTekNpugSigmaSchedule.SHIFTED_FLOW_MATCHING_3T, profile.sigmaSchedule)
        assertEquals(6, profile.defaultSteps)
        assertEquals(70, variant.route.inputCount)
        assertEquals(1, variant.route.outputCount)
        assertEquals(MediaTekGraphTransport.NEURON_COMPILED_NETWORK, variant.transport)
        assertEquals(MediaTekNpugContainerMode.STUB_TOPOLOGY, variant.topology.mode)
        assertEquals(0L, variant.topology.weightDataBytes)
        assertEquals(
            MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16,
            variant.route.inputEncoding.defaultEncoding,
        )
        assertEquals(MediaTekNpugRounding.HALF_AWAY_FROM_ZERO, variant.route.inputEncoding.rounding)
        assertTrue(variant.route.inputEncoding.saturates)
        assertEquals(MediaTekAotVaeTransport.NEURON_COMPILED_NETWORK, profile.vae.transport)
        assertEquals(listOf(1, 64, 64, 16), profile.vae.input.dims)
        assertEquals(listOf(1, 512, 512, 3), profile.vae.output.dims)
        assertEquals(1.9019890169147402e-4, profile.vae.input.scale, 1e-15)
        assertTrue(manifest.executionBlockers().isEmpty())
        assertTrue(variant.executable)
        assertTrue(route.verifyAgainst(variant.route, variant.topology, variant.compiledNetwork).isEmpty())
        assertEquals(70, route.inputs.size)
        assertEquals(1, route.outputs.size)
        assertEquals(
            "b0bd7e79e6ebc0cac291377585474d23c266d4bb0708a2d25a78e138369a9151",
            manifest.modelIdentity.modelSha256,
        )
        assertTrue(
            manifest.requestRejections(profile.id, "a cat", "", null, null)
                .single().contains("fixed ACTX"),
        )
        assertTrue(manifest.requestRejections(profile.id, "", "", 1.0f, 6).isEmpty())
    }

    private fun resource(path: String): String =
        javaClass.getResource(path)?.readText(Charsets.UTF_8)
            ?: throw AssertionError("missing test resource $path")
}

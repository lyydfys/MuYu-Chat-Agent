package com.muyuchat.core.mediatek

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekAnimaManifestJsonTest {
    @Test
    fun completeManifestRoundTripsWithStableEnumNamesAndCanonicalEncoding() {
        val manifest = MediaTekNeuronContractTest().validManifest()
        val encoded = MediaTekAnimaManifestJson.encode(manifest)
        val parsed = MediaTekAnimaManifestJson.parse(encoded)
        val root = parseTree(encoded).jsonObject
        val text = root.getValue("textEncoder").jsonObject
        val sampling = root.getValue("sampling").jsonObject
        val numericGates = sampling.getValue("generationProfiles").jsonArray.first().jsonObject
            .getValue("numericGates").jsonObject

        assertEquals(manifest, parsed)
        assertEquals(encoded, MediaTekAnimaManifestJson.encode(parsed))
        assertEquals("FLOW_MATCHING_EULER", sampling.getValue("scheduler").jsonPrimitive.content)
        assertEquals("PROMPT_ATTENTION", text.getValue("t5Tokenizer").jsonObject.getValue("weighting").jsonPrimitive.content)
        assertTrue(numericGates.containsKey("TEXT_CONTEXT_ADAPTER"))
        assertEquals("ZERO_PAD_RIGHT", sampling.getValue("contextAdapter").jsonObject.getValue("paddingPolicy").jsonPrimitive.content)
    }

    @Test
    fun roundTripsBothTokenizerAssetsVerifiedLatentAndNullableGateRatios() {
        val manifest = MediaTekNeuronContractTest().validManifest()
        val qwen = manifest.textEncoder.qwenTokenizer.copy(
            source = MediaTekTokenizerSource.BUNDLE_ASSET,
            asset = asset("assets/qwen-tokenizer.json"),
            providerId = null,
        )
        val t5 = manifest.textEncoder.t5Tokenizer.copy(
            source = MediaTekTokenizerSource.BUNDLE_ASSET,
            asset = asset("assets/t5-tokenizer.json"),
            providerId = null,
        )
        val profiles = manifest.sampling.generationProfiles.mapIndexed { index, profile ->
            if (index != 0) profile else profile.copy(
                latentInitialization = MediaTekAnimaLatentInitializationContract(
                    distribution = MediaTekAnimaLatentDistribution.VERIFIED_ASSET,
                    seedRequired = false,
                    verifiedLatentAsset = MediaTekShapedAssetContract(
                        asset = asset("assets/verified-latent.f32"),
                        dtype = "float32",
                        shape = listOf(1, 16, 64, 64),
                    ),
                ),
                numericGates = profile.numericGates + (
                    MediaTekAnimaNumericStage.PATCHIFY to profile.numericGates.getValue(MediaTekAnimaNumericStage.PATCHIFY).copy(
                        minimumOutputToInputStdRatio = null,
                        maximumOutputToInputStdRatio = null,
                    )
                ),
            )
        }
        val variant = manifest.copy(
            textEncoder = manifest.textEncoder.copy(qwenTokenizer = qwen, t5Tokenizer = t5),
            sampling = manifest.sampling.copy(generationProfiles = profiles),
        )

        assertTrue(variant.validate().toString(), variant.validate().isEmpty())
        assertEquals(variant, MediaTekAnimaManifestJson.parse(MediaTekAnimaManifestJson.encode(variant)))
    }

    @Test
    fun missingFieldsFailEvenWhenTheKotlinContractHasDefaultsOrNullableValues() {
        val root = parseTree(MediaTekAnimaManifestJson.encode(MediaTekNeuronContractTest().validManifest()))
        assertRejected("outputMime") { MediaTekAnimaManifestJson.parse(root.withoutAt(emptyList(), "outputMime").toString()) }
        assertRejected("maximumNonFiniteElements") {
            MediaTekAnimaManifestJson.parse(
                root.withoutAt(
                    listOf("sampling", "generationProfiles", "0", "numericGates", "PATCHIFY"),
                    "maximumNonFiniteElements",
                ).toString(),
            )
        }
        assertRejected("providerId") {
            MediaTekAnimaManifestJson.parse(root.withoutAt(listOf("textEncoder", "qwenTokenizer"), "providerId").toString())
        }
    }

    @Test
    fun unknownFieldsAndEnumNamesFailClosedAtEveryObjectBoundary() {
        val root = parseTree(MediaTekAnimaManifestJson.encode(MediaTekNeuronContractTest().validManifest()))
        assertRejected("unknown fields") {
            MediaTekAnimaManifestJson.parse(root.withField(emptyList(), "deviceAllowlist", JsonArray(emptyList())).toString())
        }
        assertRejected("unknown fields") {
            MediaTekAnimaManifestJson.parse(
                root.withField(listOf("sampling", "contextAdapter"), "cpuFallback", JsonPrimitive(true)).toString(),
            )
        }
        assertRejected("unknown MediaTekAnimaScheduler") {
            MediaTekAnimaManifestJson.parse(root.withField(listOf("sampling"), "scheduler", JsonPrimitive("flow_matching_euler")).toString())
        }
        assertRejected("unknown MediaTekAnimaNumericStage key") {
            val changed = root.withField(
                listOf("sampling", "generationProfiles", "0", "numericGates"),
                "UNKNOWN_STAGE",
                JsonPrimitive("invalid"),
            )
            MediaTekAnimaManifestJson.parse(changed.toString())
        }
    }

    @Test
    fun wrongTypesMalformedNumbersAndDuplicateEnumsAreRejected() {
        val root = parseTree(MediaTekAnimaManifestJson.encode(MediaTekNeuronContractTest().validManifest()))
        assertRejected("must be a string") {
            MediaTekAnimaManifestJson.parse(root.withField(emptyList(), "family", JsonPrimitive(7)).toString())
        }
        assertRejected("in-range integer") {
            MediaTekAnimaManifestJson.parse(root.withField(listOf("sampling"), "latentChannels", JsonPrimitive(16.5)).toString())
        }
        assertRejected("must be a number") {
            MediaTekAnimaManifestJson.parse(
                root.withField(
                    listOf("sampling", "generationProfiles", "0", "numericGates", "PATCHIFY"),
                    "minimumStd",
                    JsonPrimitive("NaN"),
                ).toString(),
            )
        }
        assertRejected("contains duplicate enum values") {
            MediaTekAnimaManifestJson.parse(
                root.withField(
                    listOf("textEncoder"),
                    "conditioningBranches",
                    buildJsonArray { add(JsonPrimitive("POSITIVE")); add(JsonPrimitive("POSITIVE")) },
                ).toString(),
            )
        }
        assertRejected("malformed JSON") { MediaTekAnimaManifestJson.parse("{\"schema\":true,}") }
    }

    @Test
    fun encodeRejectsSemanticallyInvalidManifest() {
        val invalid = MediaTekNeuronContractTest().validManifest().copy(outputMime = "image/jpeg")

        assertRejected("contract validation failed") { MediaTekAnimaManifestJson.encode(invalid) }
    }

    private fun parseTree(value: String): JsonElement =
        kotlinx.serialization.json.Json.parseToJsonElement(value)

    private fun asset(file: String) = MediaTekBundleAssetContract(
        file = file,
        sha256 = "c".repeat(64),
        sizeBytes = 4096,
        mediaType = "application/octet-stream",
    )

    private fun JsonElement.withField(path: List<String>, name: String, value: JsonElement): JsonElement =
        replaceAt(path) { target ->
            val fields = target.jsonObject.toMutableMap()
            fields[name] = value
            JsonObject(fields)
        }

    private fun JsonElement.withoutAt(path: List<String>, name: String): JsonElement =
        replaceAt(path) { target ->
            val fields = target.jsonObject.toMutableMap()
            fields.remove(name)
            JsonObject(fields)
        }

    private fun JsonElement.replaceAt(path: List<String>, transform: (JsonElement) -> JsonElement): JsonElement {
        if (path.isEmpty()) return transform(this)
        val key = path.first()
        if (key.toIntOrNull() != null) {
            val values = jsonArray
            val index = key.toInt()
            return JsonArray(values.mapIndexed { itemIndex, item ->
                if (itemIndex == index) item.replaceAt(path.drop(1), transform) else item
            })
        }
        val fields = jsonObject.toMutableMap()
        fields[key] = fields.getValue(key).replaceAt(path.drop(1), transform)
        return JsonObject(fields)
    }

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

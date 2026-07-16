package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageExecutionProfileNativeContractTest {
    @Test
    fun `resolved execution serializes every native effective field`() {
        val resolution = resolution()
        val resolved = resolution.layers.resolved
        val json = ImageExecutionProfileNativeContract.toNativeParamsJson(resolution)

        assertTrue(
            json.keys().asSequence().toSet()
                .containsAll(ImageExecutionProfileNativeContract.requiredFields)
        )
        assertEquals(resolved.profileId, json.getString("profileId"))
        assertEquals(resolved.profileRevision, json.getInt("profileRevision"))
        assertEquals(resolved.modelFingerprint, json.getString("modelFingerprint"))
        assertEquals(resolved.runtime.name, json.getString("runtime"))
        assertEquals(resolved.scheduler.name, json.getString("scheduler"))
        assertEquals(resolved.predictionType.name, json.getString("predictionType"))
        assertEquals(resolved.steps, json.getInt("steps"))
        assertEquals(resolved.timetableCount, json.getInt("timetableCount"))
        assertEquals(resolved.unetExecutionCount, json.getInt("unetExecutionCount"))
        assertEquals(resolved.cfgScale, json.getDouble("cfgScale"), 0.0)
        assertEquals(resolved.useCfg, json.getBoolean("useCfg"))
        assertEquals(resolved.unconditionalBranch, json.getBoolean("unconditionalBranch"))
        assertEquals(resolved.tokenizerBackend.name, json.getString("tokenizerBackend"))
        assertEquals(resolved.tokenCount, json.getInt("tokenCount"))
        assertEquals(resolved.embeddingDiskDataType.name, json.getString("embeddingDiskDataType"))
        assertEquals(resolved.vaeScalingLocation.name, json.getString("vaeScalingLocation"))
        assertEquals(resolved.vaeScalingFactor, json.getDouble("vaeScalingFactor"), 0.0)
        assertEquals(resolved.width, json.getInt("width"))
        assertEquals(resolved.height, json.getInt("height"))
        assertEquals(resolved.seed, json.getLong("seed"))
        assertEquals(resolved.graphName, json.getString("graphName"))
        assertEquals(resolved.fallback, json.getBoolean("fallback"))
        assertEquals(resolution.profile.scheduler.numTrainTimesteps, json.getInt("numTrainTimesteps"))
        assertEquals(resolved.timetableCount, json.getInt("expectedTimetableCount"))
        assertEquals(resolved.unetExecutionCount, json.getInt("expectedUnetExecutionCount"))
    }

    @Test
    fun `complete native echo parses and validates`() {
        val resolution = resolution()
        val raw = nativeEcho(resolution).toString()

        val result = ImageExecutionProfileNativeContract.parseAndValidate(resolution, raw)

        assertTrue(result.validation.valid)
        assertEquals(resolution.layers.resolved.profileId, result.nativeEffective.profileId)
        assertEquals(resolution.layers.resolved.seed, result.nativeEffective.seed)
        assertEquals(resolution.layers.resolved.unetExecutionCount, result.nativeEffective.unetExecutionCount)
    }

    @Test
    fun `every required native field fails when missing`() {
        val resolution = resolution()
        ImageExecutionProfileNativeContract.requiredFields.forEach { field ->
            val json = nativeEcho(resolution).apply {
                getJSONObject("nativeEffective").remove(field)
            }
            val error = expectFailure {
                ImageExecutionProfileNativeContract.parseAndValidate(resolution, json)
            }

            assertEquals(IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID, error.code)
            assertEquals(field, error.field)
        }
    }

    @Test
    fun `unknown native enum values fail instead of falling back`() {
        val resolution = resolution()
        listOf(
            "runtime",
            "scheduler",
            "predictionType",
            "tokenizerBackend",
            "embeddingDiskDataType",
            "vaeScalingLocation"
        ).forEach { field ->
            val json = nativeEcho(resolution).apply {
                getJSONObject("nativeEffective").put(field, "UNKNOWN_VALUE")
            }
            val error = expectFailure {
                ImageExecutionProfileNativeContract.parseAndValidate(resolution, json)
            }

            assertEquals(IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID, error.code)
            assertEquals(field, error.field)
        }
    }

    @Test
    fun `all covered execution mismatches fail validator`() {
        val resolution = resolution()
        val mutations = linkedMapOf<String, (JSONObject) -> Unit>(
            "profileId" to { it.put("profileId", "generic.compat.changed") },
            "profileRevision" to { it.put("profileRevision", it.getInt("profileRevision") + 1) },
            "modelFingerprint" to { it.put("modelFingerprint", "b".repeat(64)) },
            "runtime" to { it.put("runtime", LocalImageRuntime.MNN_DIFFUSION.name) },
            "scheduler" to { it.put("scheduler", ImageSchedulerAlgorithm.EULER.name) },
            "predictionType" to { it.put("predictionType", ImagePredictionType.V_PREDICTION.name) },
            "steps" to { it.put("steps", it.getInt("steps") + 1) },
            "timetableCount" to { it.put("timetableCount", it.getInt("timetableCount") + 1) },
            "unetExecutionCount" to { it.put("unetExecutionCount", it.getInt("unetExecutionCount") + 1) },
            "cfgScale" to { it.put("cfgScale", it.getDouble("cfgScale") + 0.5) },
            "useCfg" to { it.put("useCfg", !it.getBoolean("useCfg")) },
            "unconditionalBranch" to {
                it.put("unconditionalBranch", !it.getBoolean("unconditionalBranch"))
            },
            "tokenizerBackend" to { it.put("tokenizerBackend", ImageTokenizerBackend.TOKENIZERS_CPP.name) },
            "tokenCount" to { it.put("tokenCount", it.getInt("tokenCount") + 1) },
            "embeddingDiskDataType" to {
                it.put("embeddingDiskDataType", ImageEmbeddingDiskDataType.FP16.name)
            },
            "vaeScalingLocation" to {
                it.put("vaeScalingLocation", ImageVaeScalingLocation.HOST_BEFORE_GRAPH.name)
            },
            "vaeScalingFactor" to {
                it.put("vaeScalingFactor", it.getDouble("vaeScalingFactor") + 0.5)
            },
            "width" to { it.put("width", it.getInt("width") + 8) },
            "height" to { it.put("height", it.getInt("height") + 8) },
            "seed" to { it.put("seed", it.getLong("seed") + 1L) },
            "graphName" to { it.put("graphName", "different-graph") },
            "fallback" to { it.put("fallback", !it.getBoolean("fallback")) }
        )

        assertEquals(ImageExecutionProfileNativeContract.requiredFields, mutations.keys)
        mutations.forEach { (field, mutate) ->
            val json = nativeEcho(resolution).apply {
                mutate(getJSONObject("nativeEffective"))
            }
            val error = expectFailure {
                ImageExecutionProfileNativeContract.parseAndValidate(resolution, json)
            }

            assertEquals(EXECUTION_CONTRACT_MISMATCH, error.code)
            assertEquals(field, error.field)
            assertEquals(field, error.mismatches.single().field)
        }
    }

    @Test
    fun `fractional and non numeric native numbers fail strict parsing`() {
        val resolution = resolution()
        val fractional = nativeEcho(resolution).apply {
            getJSONObject("nativeEffective").put("steps", 20.5)
        }
        val nonNumeric = nativeEcho(resolution).apply {
            getJSONObject("nativeEffective").put("cfgScale", "7.0")
        }

        assertEquals("steps", expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, fractional)
        }.field)
        assertEquals("cfgScale", expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, nonNumeric)
        }.field)
    }

    @Test
    fun `flat top-level echo cannot replace native effective evidence`() {
        val resolution = resolution()
        val flat = ImageExecutionProfileNativeContract.toNativeParamsJson(resolution)

        val error = expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, flat)
        }

        assertEquals(IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID, error.code)
        assertEquals("nativeEffective", error.field)
    }

    private fun nativeEcho(resolution: ImageExecutionProfileResolution): JSONObject =
        JSONObject().put(
            "nativeEffective",
            ImageExecutionProfileNativeContract.toNativeParamsJson(resolution)
        )

    private fun resolution(): ImageExecutionProfileResolution =
        ImageExecutionProfileResolver.resolve(
            ImageExecutionProfileResolverInput(
                modelFingerprint = "a".repeat(64),
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.CUSTOM,
                recommendationId = null,
                deviceHints = ImageDeviceExecutionHints(localProfileKnown = false)
            )
        )

    private fun expectFailure(block: () -> Unit): ImageNativeExecutionContractException = try {
        block()
        fail("Expected native execution contract failure.")
        throw AssertionError("unreachable")
    } catch (error: ImageNativeExecutionContractException) {
        error
    }
}

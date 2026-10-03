package com.muyuchat.mca

import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct tests for [ExecutionProfileMerging].
 *
 * These four functions are pure -- they read no ViewModel state -- so they need no fake, no
 * Robolectric and no Android stub: the whole file runs against real values. Until this existed they
 * were only covered indirectly through the ViewModel, which is exactly the kind of coverage that
 * silently disappears when a caller changes.
 *
 * The two merge functions are the ones worth pinning hardest: `mergeUserRequestedIntoProfile` decides
 * whether an edit is even a change (it returns `base` when both signatures are untouched), and a
 * regression there would make a no-op edit look like a new profile.
 */
class ExecutionProfileMergingTest {

    // ---- buildLoadParamsFor ------------------------------------------------------------

    @Test
    fun `load params copy the caller's sampling settings`() {
        val params = GenerationParams(nCtx = 4096, nThreads = 6, advancedJson = """{"flash_attn":"off"}""")

        val load = manifest(runtime = ChatModelRuntime.MNN).buildLoadParamsFor(params)

        assertEquals(4096, load.nCtx)
        assertEquals(6, load.nThreads)
        assertEquals("""{"flash_attn":"off"}""", load.advancedJson)
    }

    @Test
    fun `a non llama dot cpp runtime never resolves a vision projector`() {
        val params = GenerationParams(nCtx = 2048, nThreads = 4)
        val withProjector = manifest(
            runtime = ChatModelRuntime.MNN,
            visionProjectorPath = "/models/mmproj.gguf"
        )

        // The projector is deliberately dropped for runtimes that do not take one: passing it through
        // would advertise a visual component the runner cannot load.
        assertNull(withProjector.buildLoadParamsFor(params).visionProjectorPath)
    }

    @Test
    fun `llama dot cpp keeps a vision projector it can resolve`() {
        val params = GenerationParams(nCtx = 2048, nThreads = 4)
        val withProjector = manifest(
            runtime = ChatModelRuntime.LLAMA_CPP,
            visionProjectorPath = "/models/mmproj.gguf"
        )

        assertNotNull(withProjector.buildLoadParamsFor(params).visionProjectorPath)
    }

    @Test
    fun `llama dot cpp without a projector stays null`() {
        val params = GenerationParams(nCtx = 2048, nThreads = 4)

        assertNull(
            manifest(runtime = ChatModelRuntime.LLAMA_CPP, visionProjectorPath = null)
                .buildLoadParamsFor(params).visionProjectorPath
        )
    }

    // ---- mergedExecutionProfile --------------------------------------------------------

    @Test
    fun `the merged generation is built from all three profile value sets`() {
        val generation = GenerationParams(nCtx = 1024, nThreads = 2, temperature = 0.5f)
        val profile = profile(
            resolvedLoadBoundValues = CanonicalParameterSet.of(mapOf("n_ctx" to 4096)),
            hotExecutionValues = CanonicalParameterSet.of(mapOf("n_threads" to 8)),
            // A key the generation parser actually reads. `modelBehaviorValues` still has to be
            // folded in -- it is simply not observable through `advancedJson`, because the parser
            // only lifts the keys it knows and does not copy unknown ones through.
            modelBehaviorValues = CanonicalParameterSet.of(mapOf("temperature" to 0.9))
        )

        val merged = mergedExecutionProfile(generation, profile)

        assertEquals(4096, merged.nCtx)
        assertEquals(8, merged.nThreads)
        assertEquals(0.9f, merged.temperature, 0.0001f)
    }

    @Test
    fun `an unreadable profile value leaves the caller's default in place`() {
        // `template_policy_ref` is not a generation key, so folding it in must not disturb the value
        // the caller already had. This pins that the merge is a fallback chain, not a replacement.
        val generation = GenerationParams(nCtx = 1024, nThreads = 2, temperature = 0.5f)
        val profile = profile(
            modelBehaviorValues = CanonicalParameterSet.of(mapOf("template_policy_ref" to "gemma-v1"))
        )

        val merged = mergedExecutionProfile(generation, profile)

        assertEquals(1024, merged.nCtx)
        assertEquals(2, merged.nThreads)
        assertEquals(0.5f, merged.temperature, 0.0001f)
    }

    @Test
    fun `an empty profile leaves the caller's generation untouched`() {
        val generation = GenerationParams(nCtx = 1024, nThreads = 2)

        val merged = mergedExecutionProfile(generation, profile())

        assertEquals(generation.nCtx, merged.nCtx)
        assertEquals(generation.nThreads, merged.nThreads)
    }

    // ---- mergeUserRequestedIntoProfile -------------------------------------------------

    @Test
    fun `merging a candidate from another runtime identity is refused`() {
        val base = profile(modelId = "model-a")
        val requested = profile(modelId = "model-b")

        val thrown = runCatching { mergeUserRequestedIntoProfile(base, requested, setOf("n_ctx")) }
            .exceptionOrNull()

        assertTrue("expected the identity guard to fire, got $thrown", thrown is IllegalArgumentException)
        assertEquals("用户参数候选与当前运行身份不一致", thrown?.message)
    }

    @Test
    fun `a candidate that overrides no authoritative field returns the base instance`() {
        val base = profile(userOverrides = setOf("n_ctx"))
        val requested = profile(userOverrides = setOf("n_ctx"))

        // Same instance, not merely equal: callers compare identity to decide whether to publish.
        assertSame(base, mergeUserRequestedIntoProfile(base, requested, setOf("n_threads")))
    }

    @Test
    fun `an authoritative override is merged and the signature change is reported`() {
        val base = profile(
            resolvedLoadBoundValues = CanonicalParameterSet.of(mapOf("n_ctx" to 2048)),
            userOverrides = emptySet()
        )
        val requested = profile(
            resolvedLoadBoundValues = CanonicalParameterSet.of(mapOf("n_ctx" to 8192)),
            userOverrides = setOf("n_ctx")
        )

        val merged = mergeUserRequestedIntoProfile(base, requested, setOf("n_ctx"))

        assertEquals(8192, merged.resolvedLoadBoundValues.value("n_ctx"))
        assertTrue(merged.userOverrides.contains("n_ctx"))
    }

    @Test
    fun `a merge that leaves both signatures identical still returns the base instance`() {
        val base = profile()
        // The candidate declares an override, but on a field the profile is not authoritative for,
        // so `selected` is empty and the base is returned before any signature comparison happens.
        val requested = profile(userOverrides = setOf("n_ctx"))

        assertSame(base, mergeUserRequestedIntoProfile(base, requested, setOf("n_threads")))
    }

    // ---- profileDiffersFrom ------------------------------------------------------------

    @Test
    fun `a null active profile is never a difference`() {
        assertFalse(profileDiffersFrom(GenerationParams(nCtx = 4096, nThreads = 8), null))
    }

    @Test
    fun `a requested value that differs on a governed field is a difference`() {
        val active = profile(resolvedLoadBoundValues = CanonicalParameterSet.of(mapOf("n_ctx" to 2048)))

        assertTrue(profileDiffersFrom(GenerationParams(nCtx = 4096, nThreads = 8), active))
    }

    @Test
    fun `matching every governed field is not a difference`() {
        val active = profile(
            resolvedLoadBoundValues = CanonicalParameterSet.of(mapOf("n_ctx" to 4096)),
            hotExecutionValues = CanonicalParameterSet.of(mapOf("n_threads" to 8))
        )

        assertFalse(profileDiffersFrom(GenerationParams(nCtx = 4096, nThreads = 8), active))
    }

    @Test
    fun `a field the profile does not govern is ignored`() {
        // n_threads is only in the requested parameters here; the profile governs n_ctx alone, so the
        // difference on n_threads must not report a profile change.
        val active = profile(resolvedLoadBoundValues = CanonicalParameterSet.of(mapOf("n_ctx" to 4096)))

        assertFalse(profileDiffersFrom(GenerationParams(nCtx = 4096, nThreads = 64), active))
    }

    // ---- fixtures ----------------------------------------------------------------------

    private fun identity(modelId: String = "gemma-vision") = ModelRuntimeIdentity(
        modelId = modelId,
        artifactFingerprint = "artifact-sha",
        runtime = LocalChatRuntime.LLAMA_CPP,
        runtimeVersion = "runtime-1",
        nativeLibrarySha256 = "native-sha",
        abi = "arm64-v8a",
        backendFingerprint = "backend-sha",
        projectorFingerprint = "projector-sha",
        bundleFingerprint = "bundle-sha",
        tokenizerFingerprint = "tokenizer-sha",
        templateFingerprint = "template-sha",
        deviceCapabilityFingerprint = "device-capability-sha",
        installationScopeId = "81012bd3-c920-440d-9cc1-bc3b5a598667",
        ruleSetFingerprint = "rules-v1",
        evaluatorFingerprint = "evaluator-v1",
        engineContractVersion = "engine-contract-v2",
        schemaFingerprint = "schema-v2",
        parameterPolicyVersion = "policy-v2",
        capabilities = setOf("vision", "tool-use")
    )

    private fun profile(
        modelId: String = "gemma-vision",
        resolvedLoadBoundValues: CanonicalParameterSet = CanonicalParameterSet.of(emptyMap()),
        hotExecutionValues: CanonicalParameterSet = CanonicalParameterSet.of(emptyMap()),
        modelBehaviorValues: CanonicalParameterSet = CanonicalParameterSet.of(emptyMap()),
        userOverrides: Set<String> = emptySet()
    ): ModelExecutionProfile {
        val runtimeIdentity = identity(modelId)
        return ModelExecutionProfile(
            modelId = runtimeIdentity.modelId,
            runtimeIdentity = runtimeIdentity,
            desiredLoadBoundValues = resolvedLoadBoundValues,
            resolvedLoadBoundValues = resolvedLoadBoundValues,
            hotExecutionValues = hotExecutionValues,
            desiredHotExecutionValues = hotExecutionValues,
            modelBehaviorValues = modelBehaviorValues,
            desiredModelBehaviorValues = modelBehaviorValues,
            profileId = "profile-1",
            revision = 1,
            userOverrides = userOverrides,
            resolvedAt = 1234L
        )
    }

    private fun manifest(
        runtime: ChatModelRuntime,
        visionProjectorPath: String? = null
    ) = ModelManifest(
        id = "model-1",
        displayName = "Model One",
        path = "/models/model.gguf",
        runtime = runtime,
        source = ModelSource.LOCAL,
        fileName = "model.gguf",
        sizeBytes = 1024L,
        sha256 = "model-sha",
        visionProjectorPath = visionProjectorPath
    )
}

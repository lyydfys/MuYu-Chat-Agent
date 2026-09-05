package com.muyuchat.core.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeParametersTest {
    @Test
    fun llamaImplicitBatchThreadsFollowTheSelectedDecodeThreadCount() {
        val identity = identity(LocalChatRuntime.LLAMA_CPP)
        val resolution = LlamaCppRuntimeParameterAdapter().resolveLoadProfile(
            identity,
            """{"n_threads":6}"""
        )

        assertEquals(6, resolution.profile.hotExecutionValues.value("n_threads"))
        assertEquals(6, resolution.profile.hotExecutionValues.value("n_threads_batch"))
        assertEquals(
            "runtime-default:aligned-with-n_threads",
            resolution.sourceByField["n_threads_batch"]
        )

        val explicit = LlamaCppRuntimeParameterAdapter().resolveLoadProfile(
            identity,
            """{"n_threads":6,"n_threads_batch":2}"""
        )
        assertEquals(2, explicit.profile.hotExecutionValues.value("n_threads_batch"))
        assertEquals("requested-profile", explicit.sourceByField["n_threads_batch"])
    }

    @Test
    fun llamaLoadSignatureDiagnosticReportsOnlyRedactedReadbackDifferences() {
        val identity = identity(LocalChatRuntime.LLAMA_CPP)
        val adapter = LlamaCppRuntimeParameterAdapter()
        val profile = adapter.resolveLoadProfile(identity, "{}").profile
        val nativeConfig = profile.resolvedLoadBoundValues.toJsonObject()
            .put("n_batch", 256)

        val diagnostic = adapter.loadSignatureDiagnostic(
            identity = identity,
            nativeStatsJson = JSONObject()
                .put("loaded", true)
                .put("effectiveConfig", nativeConfig)
                .toString(),
            expected = profile.resolvedLoadSignature
        )

        assertTrue(diagnostic.getBoolean("nativeLoaded"))
        assertEquals(256, diagnostic.getJSONObject("nativeEffectiveLoad").getInt("n_batch"))
        assertEquals(1, diagnostic.getJSONArray("differentFields").length())
        assertEquals("n_batch", diagnostic.getJSONArray("differentFields").getString(0))
        assertEquals(0, diagnostic.getJSONArray("missingFields").length())
    }

    @Test
    fun llamaCacheReuseUsesShortConversationDefaultButHonorsExplicitOverrides() {
        val adapter = LlamaCppRuntimeParameterAdapter()
        val baseIdentity = identity(LocalChatRuntime.LLAMA_CPP)

        val defaults = adapter.resolveLoadProfile(baseIdentity, "{}")
        assertEquals(16, defaults.profile.hotExecutionValues.value("cache_reuse"))
        assertEquals("runtime-default", defaults.sourceByField["cache_reuse"])
        assertEquals(16, JSONObject(adapter.nativeLoadJson(defaults.profile)).getInt("cache_reuse"))

        val explicitDisabled = adapter.resolveLoadProfile(baseIdentity, "{\"cache_reuse\":0}")
        assertEquals(0, explicitDisabled.profile.hotExecutionValues.value("cache_reuse"))
        assertEquals("requested-profile", explicitDisabled.sourceByField["cache_reuse"])

        val explicitThreshold = adapter.resolveLoadProfile(baseIdentity, "{\"cache_reuse\":512}")
        assertEquals(512, explicitThreshold.profile.hotExecutionValues.value("cache_reuse"))
        assertEquals("requested-profile", explicitThreshold.sourceByField["cache_reuse"])
    }

    @Test
    fun unsupportedDraftMtpStillDisablesCacheReuseDespiteTheNewDefault() {
        val identity = identity(LocalChatRuntime.LLAMA_CPP)
        val resolution = LlamaCppRuntimeParameterAdapter().resolveLoadProfile(
            identity,
            """{
                "cache_reuse":512,
                "advanced_json":{
                    "spec_type":"draft-mtp",
                    "spec_draft_n_max":2
                }
            }""".trimIndent()
        )

        // The requested profile remains auditable, but the resolved execution
        // values retain the existing safety fallback for unsupported MTP.
        assertEquals(512, resolution.profile.desiredHotExecutionValues.value("cache_reuse"))
        assertEquals(0, resolution.profile.hotExecutionValues.value("cache_reuse"))
        assertEquals("runtime-safety", resolution.sourceByField["spec_type"])
    }

    @Test
    fun registryIsRuntimeAndNativeVersionSpecificAndUnknownFailsClosed() {
        val override = VersionedParameterPolicySet(
            runtime = LocalChatRuntime.MNN_CPU,
            runtimeVersionPrefix = "3.6",
            nativeLibrarySha256Prefix = "abcd",
            policyVersion = "mnn-3.6-abcd",
            policies = mapOf(
                "n_threads" to ParameterFieldPolicy(
                    field = "n_threads",
                    owner = ParameterOwner.MODEL_EXECUTION,
                    mutability = ParameterMutability.LOAD_BOUND,
                    apiOverridePolicy = ParameterApiOverridePolicy.TRUSTED_PROFILE_ONLY
                )
            )
        )
        val registry = ParameterFieldPolicyRegistry(listOf(override))

        assertEquals(
            ParameterMutability.HOT_EXECUTION,
            registry.forRuntime(LocalChatRuntime.MNN_CPU, "3.5.0", "abcd00").policy("n_threads").mutability
        )
        assertEquals(
            ParameterMutability.LOAD_BOUND,
            registry.forRuntime(LocalChatRuntime.MNN_CPU, "3.6.1", "abcdef").policy("n_threads").mutability
        )
        assertEquals(
            ParameterMutability.UNSUPPORTED,
            registry.forRuntime(LocalChatRuntime.LLAMA_CPP, "b7000", "native").policy("future_native").mutability
        )
        assertEquals(
            ParameterMutability.UNSUPPORTED,
            registry.forRuntime(LocalChatRuntime.MNN_CPU, "3.5.0", "native").policy("n_batch").mutability
        )
        assertEquals(
            ParameterApiOverridePolicy.TRUSTED_PROFILE_ONLY,
            registry.forRuntime(LocalChatRuntime.LLAMA_CPP, "b7000", "native")
                .policy("chat_template_mode").apiOverridePolicy
        )
    }

    @Test
    fun unknownAdvancedFieldsAreQuarantinedAndNeverReachNativeJson() {
        val identity = identity(LocalChatRuntime.LLAMA_CPP)
        val adapter = LlamaCppRuntimeParameterAdapter()
        val partition = adapter.partition(
            identity,
            """{
                "temperature":0.7,
                "n_ctx":4096,
                "advanced_json":{
                    "n_batch":512,
                    "future_native":{"danger":true}
                }
            }""".trimIndent()
        )
        val resolution = adapter.resolveLoadProfile(
            identity,
            """{"n_ctx":4096,"advanced_json":{"n_batch":512,"future_native":{"danger":true}}}"""
        )
        val native = JSONObject(
            adapter.nativeCompletionJson(
                partition,
                resolution.profile,
                RuntimeOverrideSignature.none(identity)
            )
        )

        assertEquals(0.7, native.getDouble("temperature"), 0.0001)
        assertFalse(native.has("n_ctx"))
        assertFalse(native.has("n_batch"))
        assertFalse(native.has("future_native"))
        assertFalse(native.has("advanced_json"))
        assertEquals(listOf("future_native"), partition.quarantinedOverrides.map { it.field })
        assertEquals(listOf("future_native"), resolution.profile.quarantinedOverrides.map { it.field })
    }

    @Test
    fun cpuOnlyResolutionClearsDirtyGpuAndMoeValuesBeforeNative() {
        val identity = identity(LocalChatRuntime.LLAMA_CPP, capabilities = emptySet())
        val resolution = LlamaCppRuntimeParameterAdapter().resolveLoadProfile(
            identity,
            """{
              "n_ctx":8192,
              "n_threads":6,
              "advanced_json":{
                "n_gpu_layers":-1,
                "main_gpu":1,
                "split_mode":"layer",
                "n_cpu_moe":5
              }
            }""".trimIndent()
        )
        val resolved = resolution.resolved.values.toJsonObject()

        assertEquals(0, resolved.getInt("n_gpu_layers"))
        assertEquals(0, resolved.getInt("main_gpu"))
        assertEquals(0, resolved.getInt("n_cpu_moe"))
        assertEquals("none", resolved.getString("split_mode"))
        assertTrue(resolution.warnings.any { "main_gpu" in it })
        assertEquals("runtime-safety", resolution.sourceByField["n_cpu_moe"])
    }

    @Test
    fun sparseMoeForcesFileBackedReclaimableWeights() {
        val identity = identity(
            LocalChatRuntime.LLAMA_CPP,
            capabilities = setOf("sparse_moe")
        )
        val resolution = LlamaCppRuntimeParameterAdapter().resolveLoadProfile(
            identity,
            """{"n_ctx":8192,"mmap":false,"mlock":true}"""
        )

        assertEquals(true, resolution.resolved.values.value("mmap"))
        assertEquals(false, resolution.resolved.values.value("mlock"))
        assertEquals("runtime-safety", resolution.sourceByField["mmap"])
        assertEquals("runtime-safety", resolution.sourceByField["mlock"])
    }

    @Test
    fun llamaContextKeepsExactCustomValueWhileAcceptingOnlyNativePadding() {
        val identity = identity(LocalChatRuntime.LLAMA_CPP)
        val adapter = LlamaCppRuntimeParameterAdapter()
        val resolution = adapter.resolveLoadProfile(
            identity,
            """{"n_ctx":8190,"advanced_json":{"n_batch":512}}"""
        )

        assertEquals(8190, resolution.profile.desiredLoadBoundValues.value("n_ctx"))
        assertEquals(8190, resolution.profile.resolvedLoadBoundValues.value("n_ctx"))
        assertEquals("requested-profile", resolution.sourceByField["n_ctx"])
        assertFalse(resolution.warnings.any { "n_ctx normalized" in it })

        val nativeReadback = resolution.profile.resolvedLoadBoundValues.toJsonObject()
            .put("n_ctx", 8192)
        val stats = JSONObject()
            .put("loaded", true)
            .put("effectiveConfig", nativeReadback)

        val active = adapter.activeLoadedSignature(
            identity,
            stats.toString(),
            resolution.profile.resolvedLoadSignature
        )
        assertNotNull(active)
        assertEquals(8190, active!!.values.value("n_ctx"))

        val mismatchedOtherField = JSONObject(nativeReadback.toString()).put("n_batch", 256)
        val mismatchedStats = JSONObject()
            .put("loaded", true)
            .put("effectiveConfig", mismatchedOtherField)
        assertNull(
            adapter.activeLoadedSignature(
                identity,
                mismatchedStats.toString(),
                resolution.profile.resolvedLoadSignature
            )
        )

        val wrongPadding = JSONObject(nativeReadback.toString()).put("n_ctx", 8448)
        assertNull(
            adapter.activeLoadedSignature(
                identity,
                JSONObject().put("loaded", true).put("effectiveConfig", wrongPadding).toString(),
                resolution.profile.resolvedLoadSignature
            )
        )
    }

    @Test
    fun llamaContextAlreadyAlignedValueAlsoRemainsExact() {
        val resolution = LlamaCppRuntimeParameterAdapter().resolveLoadProfile(
            identity(LocalChatRuntime.LLAMA_CPP),
            """{"n_ctx":8192}"""
        )

        assertEquals(8192, resolution.profile.desiredLoadBoundValues.value("n_ctx"))
        assertEquals(8192, resolution.profile.resolvedLoadBoundValues.value("n_ctx"))
        assertEquals("requested-profile", resolution.sourceByField["n_ctx"])
        assertFalse(resolution.warnings.any { it.startsWith("n_ctx normalized") })
    }

    @Test
    fun llamaAutoGpuRequestAcceptsOnlyTheNativeCpuFallbackPair() {
        val identity = identity(
            LocalChatRuntime.LLAMA_CPP,
            capabilities = setOf("gpu_offload")
        )
        val adapter = LlamaCppRuntimeParameterAdapter()
        val resolution = adapter.resolveLoadProfile(identity, "{}")
        assertEquals(-1, resolution.profile.resolvedLoadBoundValues.value("n_gpu_layers"))
        assertEquals("layer", resolution.profile.resolvedLoadBoundValues.value("split_mode"))

        val cpuFallback = resolution.profile.resolvedLoadBoundValues.toJsonObject()
            .put("n_gpu_layers", 0)
            .put("split_mode", "none")
        val active = adapter.activeLoadedSignature(
            identity,
            JSONObject().put("loaded", true).put("effectiveConfig", cpuFallback).toString(),
            resolution.profile.resolvedLoadSignature
        )
        assertNotNull(active)
        assertEquals(-1, active!!.values.value("n_gpu_layers"))
        assertEquals("layer", active.values.value("split_mode"))

        val incompleteFallback = JSONObject(cpuFallback.toString()).put("split_mode", "layer")
        assertNull(
            adapter.activeLoadedSignature(
                identity,
                JSONObject().put("loaded", true).put("effectiveConfig", incompleteFallback).toString(),
                resolution.profile.resolvedLoadSignature
            )
        )

        val unrelatedMismatch = JSONObject(cpuFallback.toString()).put("n_batch", 256)
        assertNull(
            adapter.activeLoadedSignature(
                identity,
                JSONObject().put("loaded", true).put("effectiveConfig", unrelatedMismatch).toString(),
                resolution.profile.resolvedLoadSignature
            )
        )
    }

    @Test
    fun llamaExplicitCpuRequestUsesNativeCpuSplitModeEvenOnGpuCapableRuntime() {
        val identity = identity(
            LocalChatRuntime.LLAMA_CPP,
            capabilities = setOf("gpu_offload")
        )
        val resolution = LlamaCppRuntimeParameterAdapter().resolveLoadProfile(
            identity,
            """{"advanced_json":{"n_gpu_layers":0}}"""
        )

        assertEquals(0, resolution.profile.resolvedLoadBoundValues.value("n_gpu_layers"))
        assertEquals("none", resolution.profile.resolvedLoadBoundValues.value("split_mode"))
        assertEquals("runtime-safety", resolution.sourceByField["split_mode"])
        assertTrue(resolution.warnings.any { it.contains("n_gpu_layers=0 disables GPU layer splitting") })
    }

    @Test
    fun llamaForcedGpuRequestNeverAcceptsCpuReadbackAsAnAutomaticFallback() {
        val identity = identity(
            LocalChatRuntime.LLAMA_CPP,
            capabilities = setOf("gpu_offload")
        )
        val adapter = LlamaCppRuntimeParameterAdapter()
        val resolution = adapter.resolveLoadProfile(
            identity,
            """{"advanced_json":{"n_gpu_layers":12,"split_mode":"layer"}}"""
        )
        val invalidCpuReadback = resolution.profile.resolvedLoadBoundValues.toJsonObject()
            .put("n_gpu_layers", 0)
            .put("split_mode", "none")

        assertNull(
            adapter.activeLoadedSignature(
                identity,
                JSONObject().put("loaded", true).put("effectiveConfig", invalidCpuReadback).toString(),
                resolution.profile.resolvedLoadSignature
            )
        )
    }

    @Test
    fun lowMemorySparseMoePreservesCustomContextWhileBoundingBatchAndQuantizedKv() {
        val identity = identity(
            LocalChatRuntime.LLAMA_CPP,
            capabilities = setOf(
                "sparse_moe",
                "sparse_moe_16gb_tier",
                "verified_q4_kv_cache",
                "draft_mtp"
            )
        )
        val resolution = LlamaCppRuntimeParameterAdapter().resolveLoadProfile(
            identity,
            """{
              "n_ctx":32768,
              "advanced_json":{
                "n_batch":4096,
                "n_ubatch":1000,
                "cache_type_k":"f16",
                "cache_type_v":"f16",
                "flash_attn":"off",
                "n_parallel":4,
                "spec_type":"draft-mtp",
                "spec_draft_n_max":2,
                "mmap":false,
                "mlock":true
              }
            }""".trimIndent()
        )
        val resolved = resolution.resolved.values.toJsonObject()

        assertEquals(32768, resolved.getInt("n_ctx"))
        assertEquals(2048, resolved.getInt("n_batch"))
        assertEquals(256, resolved.getInt("n_ubatch"))
        assertEquals("q4_0", resolved.getString("cache_type_k"))
        assertEquals("q4_0", resolved.getString("cache_type_v"))
        assertEquals("on", resolved.getString("flash_attn"))
        assertEquals(1, resolved.getInt("n_parallel"))
        assertEquals("draft-mtp", resolved.getString("spec_type"))
        assertEquals(2, resolved.getInt("spec_draft_n_max"))
        assertTrue(resolved.getBoolean("mmap"))
        assertFalse(resolved.getBoolean("mlock"))
    }

    @Test
    fun lowMemorySparseMoePreservesCompactCustomContextExactly() {
        val identity = identity(
            LocalChatRuntime.LLAMA_CPP,
            capabilities = setOf("sparse_moe", "sparse_moe_16gb_tier")
        )
        val resolution = LlamaCppRuntimeParameterAdapter().resolveLoadProfile(
            identity,
            """{"n_ctx":128,"advanced_json":{"n_batch":128,"n_ubatch":64}}"""
        )

        assertEquals(128, resolution.profile.desiredLoadBoundValues.value("n_ctx"))
        assertEquals(128, resolution.profile.resolvedLoadBoundValues.value("n_ctx"))
        assertFalse(resolution.warnings.any { "n_ctx normalized" in it })
    }

    @Test
    fun genericSparseMoeTierKeepsCustomContextAndQualityKvWhileBoundingBatch() {
        val identity = identity(
            LocalChatRuntime.LLAMA_CPP,
            capabilities = setOf("sparse_moe", "sparse_moe_16gb_tier")
        )
        val resolution = LlamaCppRuntimeParameterAdapter().resolveLoadProfile(
            identity,
            """{
              "n_ctx":32768,
              "advanced_json":{
                "n_batch":4096,
                "n_ubatch":1000,
                "cache_type_k":"f16",
                "cache_type_v":"f16",
                "flash_attn":"auto"
              }
            }""".trimIndent()
        )
        val resolved = resolution.resolved.values.toJsonObject()

        assertEquals(32768, resolved.getInt("n_ctx"))
        assertEquals(2048, resolved.getInt("n_batch"))
        assertEquals(256, resolved.getInt("n_ubatch"))
        assertEquals("f16", resolved.getString("cache_type_k"))
        assertEquals("f16", resolved.getString("cache_type_v"))
        assertEquals("auto", resolved.getString("flash_attn"))
    }

    @Test
    fun mnnVisionResolutionDisablesBothModelAndKvMmapBeforeSigning() {
        val identity = identity(LocalChatRuntime.MNN_CPU, capabilities = setOf("vision"))
        val resolution = MnnRuntimeParameterAdapter().resolveLoadProfile(
            identity,
            """{"n_ctx":4096,"mmap":true,"advanced_json":{"kvcache_mmap":true}}""",
            profileId = "mnn-vision"
        )

        assertEquals(false, resolution.profile.resolvedLoadBoundValues.value("mmap"))
        assertEquals(false, resolution.profile.resolvedLoadBoundValues.value("kvcache_mmap"))
        assertEquals("runtime-safety:mnn-vision", resolution.sourceByField["mmap"])
        assertEquals("runtime-safety:mnn-vision", resolution.sourceByField["kvcache_mmap"])
        val native = JSONObject(MnnRuntimeParameterAdapter().nativeLoadJson(resolution.profile))
        assertFalse(native.getBoolean("mmap"))
        assertFalse(native.getJSONObject("advanced_json").getBoolean("kvcache_mmap"))
    }

    @Test
    fun mnnDebugTraceIsGenerationOnlyAndReachesOnlyNestedNativeAdvancedJson() {
        val identity = identity(LocalChatRuntime.MNN_CPU)
        val adapter = MnnRuntimeParameterAdapter()
        val partition = adapter.partition(
            identity,
            """{"advanced_json":{"mca_debug_trace":true}}"""
        )
        val resolution = adapter.resolveLoadProfile(
            identity,
            """{"advanced_json":{"mca_debug_trace":true}}""",
            profileId = "mnn-debug-trace"
        )
        val policy = adapter.registry.forRuntime(identity).policy("mca_debug_trace")

        assertEquals(ParameterOwner.SESSION_DIAGNOSTIC, policy.owner)
        assertEquals(ParameterMutability.GENERATION_ONLY, policy.mutability)
        assertTrue(partition.quarantinedOverrides.isEmpty())
        assertTrue(JSONObject(partition.generationJson).getBoolean("mca_debug_trace"))
        assertFalse(resolution.profile.resolvedLoadBoundValues.fields.contains("mca_debug_trace"))

        val nativeLoad = JSONObject(adapter.nativeLoadJson(resolution.profile))
        assertFalse(nativeLoad.has("mca_debug_trace"))
        assertFalse(nativeLoad.optJSONObject("advanced_json")?.has("mca_debug_trace") == true)

        val nativeCompletion = JSONObject(
            adapter.nativeCompletionJson(
                partition,
                resolution.profile,
                RuntimeOverrideSignature.none(identity)
            )
        )
        assertFalse(nativeCompletion.has("mca_debug_trace"))
        assertTrue(nativeCompletion.getJSONObject("advanced_json").getBoolean("mca_debug_trace"))
    }

    @Test
    fun mnnAdvancedSamplingSurvivesGenerationPartitionAndNativeCompletion() {
        val identity = identity(LocalChatRuntime.MNN_CPU)
        val adapter = MnnRuntimeParameterAdapter()
        for (backend in listOf("cpu", "opencl")) {
            for (camelCase in listOf(false, true)) {
                for (stringAdvanced in listOf(false, true)) {
                    val advanced = JSONObject()
                        .put("backend_type", backend)
                        .put("temperature", 0.7).put("seed", 123)
                        .put(if (camelCase) "topK" else "top_k", 24)
                        .put(if (camelCase) "topP" else "top_p", 0.8)
                        .put(if (camelCase) "minP" else "min_p", 0.02)
                    val ordinary = GenerationParams(
                        temperature = 0.5f, topK = 50, topP = 0.95f, minP = 0f, seed = 9,
                        advancedJson = JSONObject().put("backend_type", backend).toString()
                    )
                    val baseline = adapter.resolveLoadProfile(identity, ordinary.toJson(), profileId = "baseline")
                    val request = JSONObject(ordinary.copy(advancedJson = advanced.toString()).toJson())
                    if (stringAdvanced) request.put("advanced_json", request.getJSONObject("advanced_json").toString())
                    val partition = adapter.partition(identity, request.toString())
                    val resolved = adapter.resolveLoadProfile(identity, request.toString(), profileId = "advanced")
                    val native = JSONObject(adapter.nativeCompletionJson(
                        partition, resolved.profile, RuntimeOverrideSignature.none(identity)
                    ))
                    assertEquals(0.7, native.getDouble("temperature"), 0.000001)
                    assertEquals(24, native.getInt("top_k"))
                    assertEquals(0.8, native.getDouble("top_p"), 0.000001)
                    assertEquals(0.02, native.getDouble("min_p"), 0.000001)
                    assertEquals(123, native.getInt("seed"))
                    assertTrue(partition.quarantinedOverrides.none { it.field in setOf("topK", "topP", "minP") })
                    assertEquals(baseline.profile.resolvedLoadSignature, resolved.profile.resolvedLoadSignature)
                    val load = native.getJSONObject("advanced_json")
                    assertEquals(backend, load.getString("backend_type"))
                    assertEquals(if (backend == "cpu") "high" else "low", load.getString("precision"))
                }
            }
        }
    }

    @Test
    fun mnnSamplingCanonicalWinsAliasAndNullDoesNotMaskOrdinaryValue() {
        val adapter = MnnRuntimeParameterAdapter()
        val partition = adapter.partition(identity(LocalChatRuntime.MNN_CPU),
            """{"top_k":50,"top_p":0.9,"min_p":0.1,"seed":9,"temperature":0.5,
                "advanced_json":{"topK":24,"top_k":0,"topP":0.8,"top_p":null,"minP":0,
                    "temperature":null,"seed":null}}""")
        val generation = JSONObject(partition.generationJson)
        assertEquals(0, generation.getInt("top_k"))
        assertEquals(0.8, generation.getDouble("top_p"), 0.000001)
        assertEquals(0.0, generation.getDouble("min_p"), 0.000001)
        assertEquals(0.5, generation.getDouble("temperature"), 0.000001)
        assertEquals(9, generation.getInt("seed"))
        val llama = LlamaCppRuntimeParameterAdapter().partition(identity(LocalChatRuntime.LLAMA_CPP),
            """{"top_k":50,"advanced_json":{"top_k":24}}""")
        assertEquals(50, JSONObject(llama.generationJson).getInt("top_k"))
    }

    @Test
    fun allSixSignaturesAreCanonicalAndRuntimeOverrideDoesNotMutateCommitted() {
        val identity = identity(LocalChatRuntime.LLAMA_CPP)
        val first = CanonicalParameterSet.of(linkedMapOf("n_ctx" to 4096, "mmap" to true))
        val reordered = CanonicalParameterSet.of(linkedMapOf("mmap" to true, "n_ctx" to 4096))
        assertEquals(
            ResolvedLoadSignature.of(identity, first).digest,
            ResolvedLoadSignature.of(identity, reordered).digest
        )
        assertNotEquals(
            ResolvedLoadSignature.of(identity, first).digest,
            DesiredProfileSignature.of(identity, first).digest
        )

        val profile = LlamaCppRuntimeParameterAdapter().resolveLoadProfile(identity, "{}", profileId = "p1").profile
        val active = ActiveLoadedSignature.of(identity, profile.resolvedLoadBoundValues)
        val committed = profile.committedExecutionSignature
        val none = RuntimeOverrideSignature.none(identity)
        val override = RuntimeOverrideSignature.of(identity, CanonicalParameterSet.of(mapOf("n_threads" to 2)))
        val normal = EffectiveExecutionSignature.of(identity, active, committed, none)
        val throttled = EffectiveExecutionSignature.of(identity, active, committed, override)

        assertTrue(none.isNone)
        assertNotEquals(normal.digest, throttled.digest)
        assertEquals(profile.hotExecutionValues.value("n_threads"), committed.values.value("n_threads"))
        assertEquals(2, throttled.values.value("n_threads"))

        val projectorIdentity = identity.copy(projectorFingerprint = "projector-sha256")
        val projectorSignature = ResolvedLoadSignature.of(
            projectorIdentity,
            CanonicalParameterSet.of(mapOf("mmproj_path" to "D:/private/models/mmproj.gguf"))
        )
        assertFalse(projectorSignature.values.value("mmproj_path").toString().contains("D:/private"))
        assertEquals("projector:projector-sha256", projectorSignature.values.value("mmproj_path"))
    }

    @Test
    fun coordinatorRequiresUnforgeablePendingAuthorizationAndBoundsRecovery() {
        val identity = identity(LocalChatRuntime.LLAMA_CPP)
        val adapter = LlamaCppRuntimeParameterAdapter()
        val activeProfile = adapter.resolveLoadProfile(identity, "{\"n_ctx\":4096}", profileId = "active").profile
        val pendingProfile = adapter.resolveLoadProfile(identity, "{\"n_ctx\":8192}", profileId = "pending").profile
        val coordinator = ParameterCoordinator()
        coordinator.commit(activeProfile)
        coordinator.publishLoaded(activeProfile, llamaStats(activeProfile))

        val authorization = coordinator.createLoadAuthorization("tx-1", pendingProfile)
        coordinator.stageAuthorizedPending(pendingProfile, authorization, rollbackTargetProfileId = "active")
        val forged = ParameterCoordinator().createLoadAuthorization("tx-1", pendingProfile)
        try {
            coordinator.stageAuthorizedPending(pendingProfile, forged, rollbackTargetProfileId = "active")
            fail("forged authorization must not stage a pending profile")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        val unauthorized = coordinator.preflight(identity, "{\"n_ctx\":8192,\"temperature\":0.8}")
        assertTrue(unauthorized is CompletionPreflight.Rejected)
        assertEquals("model_reload_required", (unauthorized as CompletionPreflight.Rejected).code)

        val authorized = coordinator.preflight(
            identity,
            "{\"n_ctx\":8192,\"temperature\":0.8}",
            trustedAuthorization = authorization
        )
        assertEquals("model_reload_required_authorized", (authorized as CompletionPreflight.Rejected).code)

        assertTrue(
            coordinator.decideMismatchRecovery("request-1", identity, authorization) is
                MismatchRecoveryDecision.ReloadAuthorizedPending
        )
        assertTrue(
            coordinator.decideMismatchRecovery("request-1", identity, authorization) is
                MismatchRecoveryDecision.Fail
        )
        assertTrue(
            coordinator.decideMismatchRecovery("request-2", identity) is
                MismatchRecoveryDecision.ReloadCommittedForDrift
        )

        val sameResolvedDifferentRevision = pendingProfile.copy(profileId = "pending-copy", revision = 2)
        try {
            coordinator.stageAuthorizedPending(
                sameResolvedDifferentRevision,
                authorization,
                rollbackTargetProfileId = "active"
            )
            fail("authorization must bind profileId/revision and every signature")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun authorizedPendingSignatureVerificationRequiresAllSixCandidateSignatures() {
        val identity = identity(LocalChatRuntime.LLAMA_CPP)
        val adapter = LlamaCppRuntimeParameterAdapter()
        val committed = adapter.resolveLoadProfile(
            identity,
            "{\"n_ctx\":4096,\"n_threads\":4}",
            profileId = "active"
        ).profile
        val candidate = adapter.resolveLoadProfile(
            identity,
            "{\"n_ctx\":8192,\"n_threads\":6}",
            profileId = "candidate",
            revision = 2
        ).profile
        val coordinator = ParameterCoordinator()
        coordinator.commit(committed)
        coordinator.publishLoaded(committed, llamaStats(committed))
        val authorization = coordinator.createLoadAuthorization("tx-signatures", candidate)
        coordinator.stageAuthorizedPending(candidate, authorization, rollbackTargetProfileId = committed.profileId)
        coordinator.publishLoaded(candidate, llamaStats(candidate), authorization)

        val verification = coordinator.authorizedPendingSignatureVerification(authorization)

        assertNotNull(verification)
        verification!!
        assertTrue(verification.strictlyMatches)
        assertEquals(candidate.desiredSignature, verification.signatures.desired)
        assertEquals(candidate.resolvedLoadSignature, verification.signatures.resolved)
        assertEquals(candidate.committedExecutionSignature, verification.signatures.committed)
        assertEquals(
            ActiveLoadedSignature.of(identity, candidate.resolvedLoadSignature.values),
            verification.signatures.active
        )
        assertTrue(verification.signatures.override.isNone)
        assertEquals("NONE", verification.signatures.override.digest)
        assertEquals(
            EffectiveExecutionSignature.of(
                identity,
                verification.signatures.active!!,
                candidate.committedExecutionSignature,
                RuntimeOverrideSignature.none(identity)
            ),
            verification.signatures.effective
        )
        assertTrue(coordinator.isAuthorizedPendingActive(authorization))
    }

    @Test
    fun authorizedPendingSignatureVerificationRejectsUnloadedForgedAndMismatchedState() {
        val identity = identity(LocalChatRuntime.LLAMA_CPP)
        val adapter = LlamaCppRuntimeParameterAdapter()
        val committed = adapter.resolveLoadProfile(identity, "{\"n_ctx\":4096}", profileId = "active").profile
        val candidate = adapter.resolveLoadProfile(
            identity,
            "{\"n_ctx\":8192}",
            profileId = "candidate",
            revision = 2
        ).profile
        val coordinator = ParameterCoordinator()
        coordinator.commit(committed)
        coordinator.publishLoaded(committed, llamaStats(committed))
        val authorization = coordinator.createLoadAuthorization("tx-mismatch", candidate)
        coordinator.stageAuthorizedPending(candidate, authorization, rollbackTargetProfileId = committed.profileId)

        val mismatched = coordinator.authorizedPendingSignatureVerification(authorization)
        assertNotNull(mismatched)
        assertFalse(mismatched!!.strictlyMatches)
        assertEquals(committed.resolvedLoadSignature.values, mismatched.signatures.active?.values)
        assertFalse(coordinator.isAuthorizedPendingActive(authorization))

        coordinator.markUnloaded()
        val unloaded = coordinator.authorizedPendingSignatureVerification(authorization)
        assertNotNull(unloaded)
        assertFalse(unloaded!!.strictlyMatches)
        assertNull(unloaded.signatures.active)
        assertNull(unloaded.signatures.effective)

        val forged = ParameterCoordinator().createLoadAuthorization("tx-mismatch", candidate)
        assertNull(coordinator.authorizedPendingSignatureVerification(forged))
        assertFalse(coordinator.isAuthorizedPendingActive(forged))

        assertTrue(coordinator.abortAuthorizedPending(authorization))
        assertNull(coordinator.authorizedPendingSignatureVerification(authorization))
    }

    @Test
    fun pendingSignatureVerificationForcesNoneDespiteRuntimeOverridePollution() {
        val identity = identity(LocalChatRuntime.LLAMA_CPP)
        val adapter = LlamaCppRuntimeParameterAdapter()
        val committed = adapter.resolveLoadProfile(
            identity,
            "{\"n_ctx\":4096,\"n_threads\":4}",
            profileId = "active"
        ).profile
        val candidate = adapter.resolveLoadProfile(
            identity,
            "{\"n_ctx\":8192,\"n_threads\":6}",
            profileId = "candidate",
            revision = 2
        ).profile
        val coordinator = ParameterCoordinator()
        coordinator.commit(committed)
        coordinator.publishLoaded(committed, llamaStats(committed))
        coordinator.setRuntimeOverride(CanonicalParameterSet.of(mapOf("n_threads" to 2)))
        assertFalse(coordinator.snapshot()!!.override.isNone)

        val authorization = coordinator.createLoadAuthorization("tx-none", candidate)
        coordinator.stageAuthorizedPending(candidate, authorization, rollbackTargetProfileId = committed.profileId)
        coordinator.publishLoaded(candidate, llamaStats(candidate), authorization)

        val verification = coordinator.authorizedPendingSignatureVerification(authorization)
        assertNotNull(verification)
        verification!!
        assertTrue(verification.strictlyMatches)
        assertTrue(verification.signatures.override.isNone)
        assertEquals("NONE", verification.signatures.override.digest)
        assertEquals(
            candidate.hotExecutionValues.value("n_threads"),
            verification.signatures.effective?.values?.value("n_threads")
        )
        assertNotEquals(2, verification.signatures.effective?.values?.value("n_threads"))

        val rollback = coordinator.rollbackTargetFor(authorization)
        val rolledBack = coordinator.publishLoaded(rollback, llamaStats(rollback))
        assertEquals(identity.identityHash, rolledBack.active?.identityHash)
        assertEquals(identity.identityHash, rolledBack.committed.identityHash)
        assertEquals(identity.identityHash, rolledBack.override.identityHash)
        assertEquals(identity.identityHash, rolledBack.effective?.identityHash)
        assertFalse(rolledBack.override.isNone)
        assertEquals(2, rolledBack.override.values.value("n_threads"))
        assertEquals(2, rolledBack.effective?.values?.value("n_threads"))
        assertFalse(coordinator.isAuthorizedPendingActive(authorization))
        assertTrue(coordinator.abortAuthorizedPending(authorization))
    }

    @Test
    fun mnnModelSwitchWithoutUnloadClearsPreviousIdentityRuntimeOverride() {
        val firstIdentity = identity(LocalChatRuntime.MNN_CPU)
        val secondIdentity = firstIdentity.copy(
            modelId = "second-mnn-model",
            artifactFingerprint = "sha256:second-mnn-model"
        )
        val adapter = MnnRuntimeParameterAdapter()
        val first = adapter.resolveLoadProfile(
            firstIdentity,
            "{\"n_ctx\":4096,\"n_threads\":6,\"mmap\":true}",
            profileId = "first-mnn"
        ).profile
        val second = adapter.resolveLoadProfile(
            secondIdentity,
            "{\"n_ctx\":8192,\"n_threads\":8,\"mmap\":false}",
            profileId = "second-mnn"
        ).profile
        val coordinator = ParameterCoordinator()
        coordinator.commit(first)
        coordinator.publishLoaded(first, mnnStats(first))
        val thermalOverride = coordinator.setRuntimeOverride(
            CanonicalParameterSet.of(mapOf("n_threads" to 2))
        )
        assertFalse(thermalOverride.isNone)
        assertEquals(firstIdentity.identityHash, thermalOverride.identityHash)

        val published = coordinator.publishLoaded(second, mnnStats(second))

        assertEquals(secondIdentity.identityHash, published.override.identityHash)
        assertTrue(published.override.isNone)
        assertEquals("NONE", published.override.digest)
        assertTrue(published.override.values.fields.isEmpty())
        assertEquals(secondIdentity.identityHash, published.active?.identityHash)
        assertEquals(secondIdentity.identityHash, published.committed.identityHash)
        assertEquals(secondIdentity.identityHash, published.effective?.identityHash)
        assertEquals(8, published.effective?.values?.value("n_threads"))

        coordinator.commit(second)
        val committed = coordinator.snapshot()!!
        assertEquals(secondIdentity.identityHash, committed.desired.identityHash)
        assertEquals(secondIdentity.identityHash, committed.resolved.identityHash)
        assertEquals(secondIdentity.identityHash, committed.active?.identityHash)
        assertEquals(secondIdentity.identityHash, committed.committed.identityHash)
        assertEquals(secondIdentity.identityHash, committed.override.identityHash)
        assertEquals(secondIdentity.identityHash, committed.effective?.identityHash)
        assertTrue(committed.override.isNone)
    }

    @Test
    fun mnnSameIdentityRepublishPreservesNonEmptyRuntimeOverride() {
        val identity = identity(LocalChatRuntime.MNN_CPU)
        val profile = MnnRuntimeParameterAdapter().resolveLoadProfile(
            identity,
            "{\"n_ctx\":4096,\"n_threads\":6,\"mmap\":false}",
            profileId = "mnn-active"
        ).profile
        val coordinator = ParameterCoordinator()
        coordinator.commit(profile)
        coordinator.publishLoaded(profile, mnnStats(profile))
        val thermalOverride = coordinator.setRuntimeOverride(
            CanonicalParameterSet.of(mapOf("n_threads" to 2))
        )

        val republished = coordinator.publishLoaded(profile, mnnStats(profile))

        assertFalse(republished.override.isNone)
        assertEquals(thermalOverride, republished.override)
        assertEquals(identity.identityHash, republished.override.identityHash)
        assertEquals(identity.identityHash, republished.effective?.identityHash)
        assertEquals(2, republished.override.values.value("n_threads"))
        assertEquals(2, republished.effective?.values?.value("n_threads"))
        assertEquals(thermalOverride, coordinator.snapshot()?.override)
    }

    @Test
    fun coordinatorPreflightEmitsGenerationOnlyJsonAndRejectsHotOverridesByDefault() {
        val identity = identity(LocalChatRuntime.LLAMA_CPP)
        val adapter = LlamaCppRuntimeParameterAdapter()
        val profile = adapter.resolveLoadProfile(identity, "{\"n_ctx\":4096,\"n_threads\":4}", profileId = "active").profile
        val coordinator = ParameterCoordinator()
        coordinator.commit(profile)
        coordinator.publishLoaded(profile, llamaStats(profile))

        val ready = coordinator.preflight(identity, "{\"temperature\":0.9,\"future_native\":7}")
            as CompletionPreflight.Ready
        val native = JSONObject(ready.nativeParamsJson)
        assertEquals(0.9, native.getDouble("temperature"), 0.0001)
        assertEquals(4, native.getInt("n_threads"))
        assertFalse(native.has("n_ctx"))
        assertFalse(native.has("future_native"))
        assertEquals(listOf("future_native"), ready.quarantinedOverrides.map { it.field })

        val rejected = coordinator.preflight(identity, "{\"n_threads\":2,\"temperature\":0.9}")
            as CompletionPreflight.Rejected
        assertEquals("execution_override_forbidden", rejected.code)

        val nakedBoolean = coordinator.preflight(
            identity,
            "{\"n_threads\":2,\"temperature\":0.9}",
            allowTrustedHotOverride = true
        ) as CompletionPreflight.Rejected
        assertEquals("execution_override_forbidden", nakedBoolean.code)

        val overrideValues = CanonicalParameterSet.of(mapOf("n_threads" to 2))
        val lease = coordinator.createHotOverrideAuthorization(profile, overrideValues)
        val authorized = coordinator.preflight(
            identity,
            "{\"n_threads\":2,\"temperature\":0.9}",
            allowTrustedHotOverride = true,
            hotOverrideAuthorization = lease
        ) as CompletionPreflight.Ready
        assertEquals(2, JSONObject(authorized.nativeParamsJson).getInt("n_threads"))
    }

    @Test
    fun mnnPrecisionIsNormalizedPerBackendBeforeSigning() {
        val identity = identity(LocalChatRuntime.MNN_CPU)
        val adapter = MnnRuntimeParameterAdapter()
        for ((backend, requestedPrecision, expectedPrecision) in listOf(
            Triple("cpu", "low", "high"),
            Triple("opencl", "high", "low"),
            Triple("unknown", "low", "high")
        )) {
            val profile = adapter.resolveLoadProfile(
                identity,
                JSONObject().put("backend", backend).put("precision", requestedPrecision).toString()
            ).profile
            assertEquals(expectedPrecision, profile.resolvedLoadBoundValues.value("precision"))
            assertEquals(
                expectedPrecision,
                JSONObject(adapter.nativeLoadJson(profile)).getJSONObject("advanced_json").getString("precision")
            )
        }
    }

    @Test
    fun mnnLoadedSignatureUsesLoadSnapshotNotMutableGenerationConfig() {
        val identity = identity(LocalChatRuntime.MNN_CPU)
        val adapter = MnnRuntimeParameterAdapter()
        val profile = adapter.resolveLoadProfile(
            identity,
            "{\"n_ctx\":4096,\"n_threads\":6,\"mmap\":false}",
            profileId = "mnn"
        ).profile
        val loadedConfig = JSONObject()
            .put("max_all_tokens", 4096)
            .put("n_ctx", 4096)
            .put("backend_type", "cpu")
            .put("precision", "high")
            .put("memory", "low")
            .put("power", "normal")
            .put("use_mmap", false)
            .put("kvcache_mmap", true)
        val stats = JSONObject()
            .put("loaded", true)
            .put("loadedConfigJson", loadedConfig.toString())
            .put("lastConfigJson", JSONObject(loadedConfig.toString()).put("max_all_tokens", 8192).toString())

        assertTrue(adapter.activeLoadedSignature(identity, stats.toString(), profile.resolvedLoadSignature) != null)
        val unsafeStats = JSONObject(stats.toString()).put(
            "loadedConfigJson", JSONObject(loadedConfig.toString()).put("precision", "low").toString()
        )
        assertNull(adapter.activeLoadedSignature(identity, unsafeStats.toString(), profile.resolvedLoadSignature))
        assertTrue(
            adapter.isLoadSignatureMismatch(
                NativeRuntimeErrorCodes.LOAD_SIGNATURE_MISMATCH,
                "MNN load signature mismatch"
            )
        )
    }

    private fun identity(
        runtime: LocalChatRuntime,
        capabilities: Set<String> = emptySet()
    ) = ModelRuntimeIdentity(
        modelId = "model",
        artifactFingerprint = "sha256:model",
        runtime = runtime,
        runtimeVersion = if (runtime == LocalChatRuntime.MNN_CPU) "3.5.0" else "b7000",
        nativeLibrarySha256 = "native-sha",
        deviceCapabilityFingerprint = "device-capabilities",
        installationScopeId = "installation-test",
        capabilities = capabilities
    )

    private fun llamaStats(profile: ModelExecutionProfile): String = JSONObject()
        .put("loaded", true)
        .put("effectiveConfig", profile.resolvedLoadBoundValues.toJsonObject())
        .toString()

    private fun mnnStats(profile: ModelExecutionProfile): String = JSONObject()
        .put("loaded", true)
        .put("loadedConfigJson", profile.resolvedLoadBoundValues.toJsonObject().toString())
        .toString()
}

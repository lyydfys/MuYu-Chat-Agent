package com.muyuchat.mca.agent.inference

import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.ModelExecutionProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for the device `model_reload_required` failure: Agent sampling must be derived from the
 * active profile's load-bound values, not only its hot values.
 */
class AgentActiveSamplingTest {

    @Test
    fun loadBoundDefaultsComeFromTheActiveProfile() {
        val identity = testIdentity()
        val profile = ModelExecutionProfile(
            modelId = identity.modelId,
            runtimeIdentity = identity,
            desiredLoadBoundValues = CanonicalParameterSet.EMPTY,
            resolvedLoadBoundValues = CanonicalParameterSet.of(
                mapOf("n_ctx" to 3072, "n_threads" to 5, "n_batch" to 256)
            ),
            hotExecutionValues = CanonicalParameterSet.of(
                mapOf("temperature" to 0.2, "top_p" to 0.8)
            )
        )

        val params = activeAgentSampling(profile)

        assertEquals(3072, params.nCtx)
        assertEquals(5, params.nThreads)
        assertEquals(0.2f, params.temperature, 0.001f)
        assertEquals(0.8f, params.topP, 0.001f)
        val submitted = JSONObject(params.toJson())
        assertEquals(3072, submitted.getInt("n_ctx"))
        assertEquals(5, submitted.getInt("n_threads"))
    }
}

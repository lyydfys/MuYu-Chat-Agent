package com.muyuchat.api.local

import com.muyuchat.core.engine.GenerateEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A runtime call must never observe a half-installed owner.
 *
 * Before [LocalApiRuntime.publishBindings] existed, a call read the individual `var` provider fields
 * one after another. Replacing an owner assigns those fields one at a time, so a call that started
 * mid-swap could pair the **new** owner's chat stream with the **old** owner's stop handle — exactly
 * the torn snapshot the refactor plan forbids ("不得…制造竞争、旧值覆盖或撕裂 snapshot").
 *
 * The fix keeps the per-field assignments (so existing integrations and focused tests keep working)
 * and adds one immutable set the owner publishes after it finishes installing. A call captures that
 * set once and uses only that value.
 *
 * These tests are the behavioural guard for that contract. They assert the *mechanism* — a captured
 * set does not move — rather than mocking a race, because the property is structural: once captured,
 * a [LocalApiRuntime.LocalApiBindings] instance cannot change.
 */
class LocalApiRuntimeBindingsSnapshotTest {

    private val token = Any()

    @After
    fun tearDown() {
        // LocalApiRuntime is a process-global singleton, so every test must leave it released.
        runCatching { LocalApiRuntime.releaseOwner(token) }
        assertFalse(
            "tearDown must leave no publisher behind",
            LocalApiRuntime.hasPublishedBindings
        )
    }

    // ── The core property: one captured set stays consistent across a swap ──────────────

    @Test
    fun aCapturedSetDoesNotMoveWhenAnotherOwnerPublishesMidCall() { runBlocking {
            LocalApiRuntime.claimOwner(token) {}
            LocalApiRuntime.imageGenerationProvider = { _, _ -> "owner-1" }
            LocalApiRuntime.modelsJsonProvider = { """["owner-1"]""" }
            val captured = LocalApiRuntime.publishBindings()

            // A call holds `captured` and is suspended here. A replacement owner now installs a full,
            // different set — including a different chat stream, stop handle and model catalogue.
            val replacement = Any()
            LocalApiRuntime.claimOwner(replacement) {}
            LocalApiRuntime.imageGenerationProvider = { _, _ -> "owner-2" }
            LocalApiRuntime.modelsJsonProvider = { """["owner-2"]""" }
            LocalApiRuntime.streamChatProvider = { flowOf<GenerateEvent>() }
            LocalApiRuntime.publishBindings()

            // Everything the first call captured still comes from owner 1, as one consistent set.
            assertEquals("owner-1", runBlocking { captured.imageGenerationProvider?.invoke("r", "{}") })
            assertEquals("""["owner-1"]""", captured.modelsJsonProvider())
            assertNull("owner 1 never installed a chat stream", captured.streamChatProvider)

            runCatching { LocalApiRuntime.releaseOwner(replacement) }
        }
    }

    @Test
    fun currentBindingsReturnsTheMostRecentlyPublishedSet() { runBlocking {
            LocalApiRuntime.claimOwner(token) {}
            LocalApiRuntime.imageGenerationProvider = { _, _ -> "first" }
            LocalApiRuntime.publishBindings()

            LocalApiRuntime.imageGenerationProvider = { _, _ -> "second" }
            // Unpublished edits must not be visible to a call once a set has been published.
            assertEquals("first", runBlocking { LocalApiRuntime.currentBindings().imageGenerationProvider?.invoke("r", "{}") })

            LocalApiRuntime.publishBindings()
            assertEquals("second", runBlocking { LocalApiRuntime.currentBindings().imageGenerationProvider?.invoke("r", "{}") })
        }
    }

    // ── Backward compatibility for integrations that only assign individual providers ───

    @Test
    fun withoutAPublishTheLiveFieldsAreStillRead() { runBlocking {
            // The debug smoke activity and several focused tests assign providers directly and never
            // publish. They must keep working.
            assertFalse(LocalApiRuntime.hasPublishedBindings)
            LocalApiRuntime.claimOwner(token) {}
            LocalApiRuntime.imageGenerationProvider = { _, _ -> "live" }

            assertFalse(LocalApiRuntime.hasPublishedBindings)
            assertEquals("live", runBlocking { LocalApiRuntime.currentBindings().imageGenerationProvider?.invoke("r", "{}") })
        }
    }

    // ── Release semantics ───────────────────────────────────────────────────────────────

    @Test
    fun releasingTheOwnerDropsThePublishedSetAndClearsTheFields() {
        LocalApiRuntime.claimOwner(token) {}
        LocalApiRuntime.imageGenerationProvider = { _, _ -> "owner" }
        LocalApiRuntime.modelsJsonProvider = { """["owner"]""" }
        LocalApiRuntime.publishBindings()
        assertTrue(LocalApiRuntime.hasPublishedBindings)

        assertTrue("the owner may release", LocalApiRuntime.releaseOwner(token))

        assertFalse("release must drop the published set", LocalApiRuntime.hasPublishedBindings)
        assertNull(LocalApiRuntime.imageGenerationProvider)
        // The cleared defaults are what a call now sees, never the retired owner's callbacks.
        assertEquals("[]", LocalApiRuntime.currentBindings().modelsJsonProvider())
    }

    @Test
    fun aStaleOwnerCannotReleaseOrRepublishOverItsReplacement() {
        LocalApiRuntime.claimOwner(token) {}
        LocalApiRuntime.imageGenerationProvider = { _, _ -> "stale" }
        LocalApiRuntime.publishBindings()

        val replacement = Any()
        LocalApiRuntime.claimOwner(replacement) {}
        LocalApiRuntime.imageGenerationProvider = { _, _ -> "replacement" }
        LocalApiRuntime.publishBindings()

        // The token that was replaced must not be able to clear the new owner's bindings.
        assertFalse(LocalApiRuntime.releaseOwner(token))
        assertTrue(LocalApiRuntime.hasPublishedBindings)
        assertEquals(
            "replacement",
            runBlocking { LocalApiRuntime.currentBindings().imageGenerationProvider?.invoke("r", "{}") }
        )

        runCatching { LocalApiRuntime.releaseOwner(replacement) }
    }

    // ── The published set carries every field the runtime call needs ────────────────────

    @Test
    fun thePublishedSetCarriesEveryFieldACallMayRead() { runBlocking {
            LocalApiRuntime.claimOwner(token) {}
            LocalApiRuntime.streamChatProvider = { flowOf<GenerateEvent>() }
            LocalApiRuntime.stopGenerationProvider = {}
            LocalApiRuntime.stopGenerationIfRequestActiveProvider = { true }
            LocalApiRuntime.loadedModelJsonProvider = { """{"loaded":true}""" }
            LocalApiRuntime.paramsJsonProvider = { """{"nCtx":4096}""" }
            LocalApiRuntime.generationParamsProvider = { com.muyuchat.core.engine.GenerationParams() }
            LocalApiRuntime.modelsJsonProvider = { """["m"]""" }
            LocalApiRuntime.imageTextualInversionsJsonProvider = { """["ti"]""" }
            LocalApiRuntime.modelRuntimeStatesJsonProvider = { """{"m":"ready"}""" }
            LocalApiRuntime.deviceProfileJsonProvider = { """{"abi":"arm64"}""" }
            LocalApiRuntime.agentRecommendationJsonProvider = { """{"rec":1}""" }
            LocalApiRuntime.benchmarkJsonProvider = { """{"bench":1}""" }
            LocalApiRuntime.imageGenerationProvider = { _, _ -> """{"image":1}""" }
            LocalApiRuntime.controlPlane = null
            val bindings = LocalApiRuntime.publishBindings()

            assertNotNull(bindings.streamChatProvider)
            assertNotNull(bindings.stopGenerationProvider)
            assertNotNull(bindings.stopGenerationIfRequestActiveProvider)
            assertEquals("""{"loaded":true}""", bindings.loadedModelJsonProvider())
            assertEquals("""{"nCtx":4096}""", bindings.paramsJsonProvider())
            assertEquals("""["m"]""", bindings.modelsJsonProvider())
            assertEquals("""["ti"]""", bindings.imageTextualInversionsJsonProvider())
            assertEquals("""{"m":"ready"}""", bindings.modelRuntimeStatesJsonProvider())
            assertEquals("""{"abi":"arm64"}""", bindings.deviceProfileJsonProvider())
            assertEquals("""{"rec":1}""", bindings.agentRecommendationJsonProvider("{}"))
            // benchmarkJsonProvider is a suspend supplier, so it is invoked inside a coroutine.
            assertEquals("""{"bench":1}""", runBlocking { bindings.benchmarkJsonProvider("{}") })
            assertEquals("""{"image":1}""", runBlocking { bindings.imageGenerationProvider?.invoke("r", "{}") })
            // generationParamsProvider is a value supplier; assert the published instance is the one
            // the caller installed rather than guessing at engine defaults.
            assertNotNull(bindings.generationParamsProvider())
            assertNull(bindings.controlPlane)
        }
    }
}

package com.muyuchat.mca.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the first-run flow's persisted progress.
 *
 * The store is in-memory on purpose: the SharedPreferences adapter is a pass-through, and everything
 * that can actually go wrong — a corrupt step reopening or skipping the flow, an interrupted transition
 * leaving contradictory keys — is a pure codec question.
 */
class OnboardingPreferencesTest {

    private class MemoryStore : OnboardingPreferenceStore {
        val values = LinkedHashMap<String, String>()
        val flags = LinkedHashMap<String, Boolean>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
        override fun readBoolean(key: String): Boolean = flags[key] ?: false
        override fun writeBoolean(key: String, value: Boolean) {
            flags[key] = value
        }
    }

    private fun preferences(store: MemoryStore) = OnboardingPreferences(store)

    @Test
    fun `a fresh install starts on the welcome page and shows the flow`() {
        val state = preferences(MemoryStore()).load()

        assertFalse(state.completed)
        assertTrue(state.shouldShowFlow)
        assertEquals(OnboardingStep.WELCOME, state.step)
        assertNull(state.path)
        assertFalse(state.skippedModel)
    }

    @Test
    fun `completing the flow is terminal`() {
        val store = MemoryStore()
        preferences(store).markCompleted()

        val state = preferences(store).load()

        assertTrue(state.completed)
        assertFalse(state.shouldShowFlow)
        assertEquals(OnboardingStep.DONE, state.step)
    }

    @Test
    fun `an interrupted flow resumes on the step it had saved`() {
        val store = MemoryStore()
        preferences(store).savePath(OnboardingPath.EXPERIENCE)
        preferences(store).saveStep(OnboardingStep.MODEL)

        val state = preferences(store).load()

        assertEquals(OnboardingStep.MODEL, state.step)
        assertEquals(OnboardingPath.EXPERIENCE, state.path)
        assertTrue(state.shouldShowFlow)
    }

    @Test
    fun `a corrupt step reopens the flow at the welcome page rather than skipping it`() {
        val store = MemoryStore()
        store.values[OnboardingPreferences.KEY_STEP] = "finished"

        val state = preferences(store).load()

        assertFalse("a corrupt step must never mark the user as onboarded", state.completed)
        assertEquals(OnboardingStep.WELCOME, state.step)
        assertTrue(state.shouldShowFlow)
    }

    @Test
    fun `a model step without a chosen path is clamped back to the path page`() {
        // This is the state an interrupted transition can leave: the step advanced before the answer
        // was written. The model page's copy depends on the path, so it must not render alone.
        val store = MemoryStore()
        store.values[OnboardingPreferences.KEY_STEP] = OnboardingStep.MODEL.wireName

        val state = preferences(store).load()

        assertEquals(OnboardingStep.PATH, state.step)
        assertNull(state.path)
    }

    @Test
    fun `a corrupt path reads as unanswered`() {
        val store = MemoryStore()
        store.values[OnboardingPreferences.KEY_PATH] = "cloud"

        assertNull(preferences(store).load().path)
    }

    @Test
    fun `saving the done step completes the flow in one write`() {
        val store = MemoryStore()
        preferences(store).saveStep(OnboardingStep.DONE)

        assertTrue(store.flags[OnboardingPreferences.KEY_COMPLETED] ?: false)
        assertEquals(
            OnboardingStep.DONE.wireName,
            store.values[OnboardingPreferences.KEY_STEP]
        )
    }

    @Test
    fun `a step write never clears the completed flag`() {
        val store = MemoryStore()
        preferences(store).markCompleted()
        preferences(store).saveStep(OnboardingStep.WELCOME)

        assertTrue("completion is terminal for the install", store.flags[OnboardingPreferences.KEY_COMPLETED] ?: false)
    }

    @Test
    fun `the skipped model flag survives a reload`() {
        val store = MemoryStore()
        preferences(store).saveSkippedModel(true)

        assertTrue(preferences(store).load().skippedModel)
    }

    @Test
    fun `reset returns the install to a fresh flow`() {
        val store = MemoryStore()
        preferences(store).savePath(OnboardingPath.PRODUCTION)
        preferences(store).saveSkippedModel(true)
        preferences(store).markCompleted()

        preferences(store).reset()

        val state = preferences(store).load()
        assertFalse(state.completed)
        assertEquals(OnboardingStep.WELCOME, state.step)
        assertNull(state.path)
        assertFalse(state.skippedModel)
    }

    @Test
    fun `the completed state type carries the terminal step invariant`() {
        assertEquals(OnboardingStep.DONE, OnboardingState.Completed.step)
        assertTrue(OnboardingState.Completed.completed)
        assertFalse(OnboardingState.Completed.shouldShowFlow)
    }

    @Test
    fun `only done and completed may be paired`() {
        val thrown = runCatching {
            OnboardingState(completed = true, step = OnboardingStep.MODEL)
        }.isFailure

        assertTrue("the impossible pair must be rejected at construction", thrown)
    }

    @Test
    fun `wire names are unique and lower case`() {
        val stepNames = OnboardingStep.entries.map { it.wireName }
        val pathNames = OnboardingPath.entries.map { it.wireName }

        assertEquals(stepNames.size, stepNames.toSet().size)
        assertEquals(pathNames.size, pathNames.toSet().size)
        (stepNames + pathNames).forEach { name ->
            assertEquals(name, name.lowercase())
            assertEquals(name, name.trim())
        }
    }

    @Test
    fun `the step order matches the page order`() {
        // The progress indicator reads these ordinals, so the declaration order is a user-visible
        // contract rather than an implementation detail.
        assertEquals(
            listOf(
                OnboardingStep.WELCOME,
                OnboardingStep.PATH,
                OnboardingStep.MODEL,
                OnboardingStep.DONE
            ),
            OnboardingStep.entries.toList()
        )
    }

    @Test
    fun `every step and path round trips through its wire name`() {
        OnboardingStep.entries.forEach { step ->
            assertEquals(step, OnboardingStep.fromWire(step.wireName))
        }
        OnboardingPath.entries.forEach { path ->
            assertEquals(path, OnboardingPath.fromWire(path.wireName))
        }
    }

    @Test
    fun `stored text is matched case insensitively and trimmed`() {
        assertEquals(OnboardingStep.MODEL, OnboardingStep.fromWire(" Model "))
        assertEquals(OnboardingPath.EXPERIENCE, OnboardingPath.fromWire("EXPERIENCE"))
    }

    @Test
    fun `the onboarding store name is its own`() {
        assertEquals("mca_onboarding", SharedPreferencesOnboardingStore.PREFERENCES_NAME)
    }
}

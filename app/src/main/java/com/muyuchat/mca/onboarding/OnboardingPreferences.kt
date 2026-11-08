package com.muyuchat.mca.onboarding

import android.content.Context

/**
 * Which install shape the user picked on the first-run path page.
 *
 * The two names describe the same engine reached two ways, not two engines: every build runs the same
 * official DSH kernel, the difference is whether the phone installs the local runtime payload now
 * ([EXPERIENCE]) or whether the user only keeps MCA's own chat and image features ([PRODUCTION]).
 * Nothing downstream may treat the choice as a capability gate — it only decides which page the user
 * sees next.
 */
internal enum class OnboardingPath(val wireName: String) {
    /** Install and run everything locally: the default for a self-hosted phone-first user. */
    EXPERIENCE("experience"),

    /** Start with MCA's own features and skip the local runtime for now. */
    PRODUCTION("production");

    companion object {
        /** No choice stored yet; the path page has not been answered. */
        val Unset: OnboardingPath? = null

        fun fromWire(raw: String?): OnboardingPath? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return Unset
            return entries.firstOrNull { it.wireName.equals(text, ignoreCase = true) }
        }
    }
}

/**
 * The page the first-run flow is currently on.
 *
 * The order is meaningful: the ordinal is the step number shown in the progress indicator, and the flow
 * only ever advances forward except when the user goes back. [DONE] is terminal, and the only state that
 * suppresses the whole flow on the next launch.
 */
internal enum class OnboardingStep(val wireName: String) {
    WELCOME("welcome"),
    PATH("path"),
    MODEL("model"),
    DONE("done");

    companion object {
        /**
         * Parses a stored step, falling back to [WELCOME] for anything unrecognised.
         *
         * Failing back to the first page (never to [DONE]) is deliberate: a corrupt preference must cost
         * a returning user three taps, not silently mark a brand-new user as already onboarded.
         */
        fun fromWire(raw: String?): OnboardingStep {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return WELCOME
            return entries.firstOrNull { it.wireName.equals(text, ignoreCase = true) } ?: WELCOME
        }
    }
}

/** Everything the first-run flow needs to restore itself after a process death. */
internal data class OnboardingState(
    val completed: Boolean = false,
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val path: OnboardingPath? = OnboardingPath.Unset,
    val skippedModel: Boolean = false
) {
    init {
        // A completed flow is always on its terminal page. Encoding that here rather than at each call
        // site means no reader can observe the impossible pair (completed, WELCOME).
        require(!completed || step == OnboardingStep.DONE) {
            "A completed onboarding must be on the done step."
        }
    }

    /** True when the flow should be shown instead of the app. */
    val shouldShowFlow: Boolean get() = !completed

    companion object {
        val Completed: OnboardingState = OnboardingState(
            completed = true,
            step = OnboardingStep.DONE
        )
    }
}

/** Minimal key/value seam over SharedPreferences so the codec stays testable without Android. */
internal interface OnboardingPreferenceStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
    fun readBoolean(key: String): Boolean
    fun writeBoolean(key: String, value: Boolean)
}

/** The real store. [name] is fixed to [PREFERENCES_NAME] by the factory below. */
internal class SharedPreferencesOnboardingStore(
    context: Context,
    name: String = PREFERENCES_NAME
) : OnboardingPreferenceStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun read(key: String): String? = preferences.getString(key, null)

    override fun write(key: String, value: String?) {
        preferences.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    override fun readBoolean(key: String): Boolean = preferences.getBoolean(key, false)

    override fun writeBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    internal companion object {
        const val PREFERENCES_NAME: String = "mca_onboarding"
    }
}

/**
 * Reads and writes the first-run flow's progress.
 *
 * Every write is a single key so an interrupted step transition can never leave the four keys in a
 * contradictory combination: [load] reconciles what it finds instead of trusting it.
 */
internal class OnboardingPreferences(private val store: OnboardingPreferenceStore) {

    /**
     * Reconciles the stored keys into a state the flow can render.
     *
     * Rules, in order:
     * 1. `completed` wins: a completed flow always reads as [OnboardingState.Completed], so a stale step
     *    key left behind by an older build cannot reopen the flow.
     * 2. An unreadable step falls back to [OnboardingStep.WELCOME], never to done.
     * 3. A step that cannot be reached without an answer is clamped: [OnboardingStep.MODEL] requires a
     *    chosen path, because the model page's copy depends on it.
     */
    fun load(): OnboardingState {
        if (store.readBoolean(KEY_COMPLETED)) return OnboardingState.Completed

        val storedStep = OnboardingStep.fromWire(store.read(KEY_STEP))
        val path = OnboardingPath.fromWire(store.read(KEY_PATH))
        val skippedModel = store.readBoolean(KEY_SKIPPED_MODEL)
        val step = when {
            storedStep == OnboardingStep.MODEL && path == OnboardingPath.Unset -> OnboardingStep.PATH
            else -> storedStep
        }
        return OnboardingState(
            completed = false,
            step = step,
            path = path,
            skippedModel = skippedModel
        )
    }

    /** Persists the step the user is looking at, so a process death resumes there. */
    fun saveStep(step: OnboardingStep) {
        if (step == OnboardingStep.DONE) {
            markCompleted()
            return
        }
        store.write(KEY_STEP, step.wireName)
        // The completion flag is never cleared by a step write: once done, the flow stays done.
        if (store.readBoolean(KEY_COMPLETED)) {
            store.writeBoolean(KEY_COMPLETED, true)
        }
    }

    /**
     * Persists [step] exactly as given — including [OnboardingStep.DONE] — without asserting completion.
     *
     * [saveStep] reads the done step as "the flow is over", which is what a one-shot wizard means. The
     * first-run controller needs the other reading: the last screen is still a screen, so an interrupted
     * flow has to resume on it, and only [markCompleted] may end the flow for the install.
     *
     * One key, so it can never pair `completed` with a non-done step.
     */
    fun saveStepWithoutCompleting(step: OnboardingStep) {
        store.write(KEY_STEP, step.wireName)
    }

    /** Persists the path answer. Choosing a path is what makes the model step reachable. */
    fun savePath(path: OnboardingPath) {
        store.write(KEY_PATH, path.wireName)
    }

    /** Records that the user moved past the model step without downloading anything. */
    fun saveSkippedModel(skipped: Boolean) {
        store.writeBoolean(KEY_SKIPPED_MODEL, skipped)
    }

    /** Terminal write: the flow never appears again for this install. */
    fun markCompleted() {
        store.write(KEY_STEP, OnboardingStep.DONE.wireName)
        store.writeBoolean(KEY_COMPLETED, true)
    }

    /** Test and "reset onboarding" support; also clears the answers that only make sense inside the flow. */
    fun reset() {
        store.writeBoolean(KEY_COMPLETED, false)
        store.write(KEY_STEP, null)
        store.write(KEY_PATH, null)
        store.writeBoolean(KEY_SKIPPED_MODEL, false)
    }

    internal companion object {
        const val KEY_COMPLETED: String = "onboarding_completed"
        const val KEY_STEP: String = "onboarding_step"
        const val KEY_PATH: String = "onboarding_path"
        const val KEY_SKIPPED_MODEL: String = "onboarding_skipped_model"
    }
}

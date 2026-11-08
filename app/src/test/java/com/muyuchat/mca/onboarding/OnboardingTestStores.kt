package com.muyuchat.mca.onboarding

/**
 * In-memory preference store for the onboarding tests.
 *
 * `OnboardingPreferencesTest` already has a private fake of its own; it is deliberately left untouched, and
 * this one is the shared version the new flow tests need because it also counts writes (the "every step
 * persists immediately" and "complete is idempotent" contracts are write-count contracts) and keeps the
 * raw key maps visible so a test can seed the state a broken or interrupted install would leave behind.
 */
internal class RecordingOnboardingStore : OnboardingPreferenceStore {
    /** Raw string preferences, exactly as `SharedPreferences` would hold them. */
    val values = LinkedHashMap<String, String>()

    /** Raw boolean preferences. */
    val flags = LinkedHashMap<String, Boolean>()

    /** Total number of `write` + `writeBoolean` calls, so a test can assert persistence actually happened. */
    var writeCount: Int = 0
        private set

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String?) {
        writeCount++
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun readBoolean(key: String): Boolean = flags[key] ?: false

    override fun writeBoolean(key: String, value: Boolean) {
        writeCount++
        flags[key] = value
    }
}

/**
 * A store whose writes fail, to prove a broken disk cannot crash the flow or make a refused tap look saved.
 *
 * Reads pass through to [delegate], so a test can still inspect what actually landed (nothing, for a failed
 * key) and whether an unrelated key was written.
 */
internal class FailingPreferenceStore(
    val delegate: RecordingOnboardingStore = RecordingOnboardingStore(),
    private val failOn: (key: String) -> Boolean = { true },
    private val message: String = "disk full"
) : OnboardingPreferenceStore {
    override fun read(key: String): String? = delegate.read(key)

    override fun write(key: String, value: String?) {
        if (failOn(key)) throw IllegalStateException(message)
        delegate.write(key, value)
    }

    override fun readBoolean(key: String): Boolean = delegate.readBoolean(key)

    override fun writeBoolean(key: String, value: Boolean) {
        if (failOn(key)) throw IllegalStateException(message)
        delegate.writeBoolean(key, value)
    }
}

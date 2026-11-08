package com.muyuchat.mca.appearance

/**
 * In-memory [AppearancePreferenceStore] shared by every appearance test.
 *
 * Extracted from `AppearancePreferencesTest` rather than copied, so the codec test and the controller
 * test keep exercising one fake. A second, subtly different in-memory store is how "the test passed"
 * and "the shipped store behaves differently" drift apart.
 *
 * @param initial pre-seeded raw values, keyed exactly as a real preferences file would hold them.
 */
internal class MemoryAppearanceStore(
    initial: Map<String, String> = emptyMap()
) : AppearancePreferenceStore {

    /**
     * When non-null, every [write] throws it. Used to prove that a rejected save does not crash the
     * caller, does not roll back the in-memory choice, and leaves the previously stored value
     * untouched. Mutable so one controller can be tested through a failure *and* a later recovery,
     * which static failure injection cannot show.
     */
    var writeFailure: Exception? = null

    /** Current contents, exposed so a test can assert what actually reached the store. */
    val values = LinkedHashMap<String, String>(initial)

    /** Number of accepted writes; write ordering is observable through [values] plus this counter. */
    var writeCount: Int = 0
        private set

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String?) {
        val failure = writeFailure
        if (failure != null) throw failure
        writeCount += 1
        if (value == null) values.remove(key) else values[key] = value
    }
}

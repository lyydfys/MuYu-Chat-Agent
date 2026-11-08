package com.muyuchat.mca.appearance

import com.muyuchat.core.designsystem.appearance.McaAppearanceThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What happened to one [AppearanceController.select] call.
 *
 * A returned outcome, not a `lastError` StateFlow, is the deliberate choice:
 *  - the only actor that can react to a failed write is the one that just performed it (show a
 *    message next to the control), so the failure travels back to that call site instead of being
 *    parked in a sticky flow that needs a `clear()` and silently swallows the error when nobody
 *    collects it;
 *  - it keeps the state holder free of a second, write-only piece of state (the architecture
 *    contract wants one authoritative state per business fact, and "the last save failed" is an
 *    event, not state);
 *  - it is synchronous, so the failing-write path is covered by a plain unit test with no
 *    coroutine collection.
 * The memory value is updated before the write is attempted, so a failure never rolls the visible
 * choice back; [AppearanceSelectionOutcome.KeptInMemoryOnly] says exactly that.
 */
internal sealed interface AppearanceSelectionOutcome {

    /** The choice is now both in memory and on disk. */
    data object Persisted : AppearanceSelectionOutcome

    /**
     * The choice is live for this composition but the store rejected the write, so the mode read back
     * from disk is still the previous one.
     *
     * "This composition" is meant literally: the owner is built by `remember(appContext)`, so the
     * in-memory value survives recomposition but **not** an activity recreation. After a rotation, or
     * on the next launch, the persisted value wins and this choice is gone.
     *
     * @param mode the choice that is in effect right now (never rolled back).
     * @param userMessage Chinese, user-facing explanation; safe to show verbatim.
     * @param diagnostic technical cause for engineering logs; never shown raw to the user.
     */
    data class KeptInMemoryOnly(
        val mode: AppearanceThemeMode,
        val userMessage: String,
        val diagnostic: String? = null
    ) : AppearanceSelectionOutcome
}

/**
 * The app-side owner of the colour-mode preference: one authoritative in-memory value, one persisted
 * value, and the write that keeps them aligned.
 *
 * It is Android-free and takes [AppearancePreferences] (itself a store port) by constructor, so the
 * whole holder is exercised by JVM tests; the production `Context` never reaches this class, which
 * keeps a stray `Context` from turning into an app-scope leak or a second preferences file.
 *
 * One read path: [themeMode], a read-only flow. Composition collects it (`collectAsStateWithLifecycle`)
 * and any non-composition caller reads `themeMode.value`, which is the same synchronous value the
 * constructor already loaded from the store. There is deliberately no second synchronous accessor: it
 * would only forward to that `.value` and become an API with no distinct meaning.
 *
 * Ownership: exactly one instance belongs to the app composition (the same place that builds
 * [AppearancePreferences]); the settings surface holds it read-only and calls [select]. It never
 * writes a global UI state and exposes no mutable flow.
 *
 * Threading: [select] may be driven by fast repeated taps and, in principle, from any thread, so the
 * memory update and the store write share one `synchronized` critical section. Serialising them is
 * what guarantees that the value on disk is the *last* selection rather than a write that raced ahead
 * of it. The section is expected to stay short because the production port is
 * `SharedPreferences.apply()` (memory commit plus a queued asynchronous disk write); nothing here
 * invokes a callback, a UI, or long IO while holding the lock. Reading [themeMode] takes no lock:
 * `StateFlow` publishes a `volatile` value, so a reader never blocks a writer.
 */
internal class AppearanceController(
    private val preferences: AppearancePreferences
) {

    private val lock = Any()

    private val mutableThemeMode: MutableStateFlow<AppearanceThemeMode>

    /**
     * The current choice as published state; read-only, so no consumer can become a second writer.
     */
    val themeMode: StateFlow<AppearanceThemeMode>

    init {
        // An unreadable store must not take the first frame down with it: this is a cosmetic
        // preference, and a throwing read is the same situation as "nothing was ever saved". The
        // fallback is SYSTEM, which is what every build before this preference existed painted, so the
        // first frame still paints one coherent scheme instead of a half-initialised one.
        val initial = try {
            preferences.loadThemeMode()
        } catch (error: Exception) {
            AppearanceThemeMode.SYSTEM
        }
        mutableThemeMode = MutableStateFlow(initial)
        themeMode = mutableThemeMode.asStateFlow()
    }

    /**
     * Applies [mode]: memory first, then disk.
     *
     * The in-memory value is published even when the write fails, so the user's tap always takes
     * effect for as long as this owner lives; the outcome says whether the choice will survive a
     * restart. Only [Exception] is caught, not `Throwable`: a VM-level error (out of memory, stack
     * overflow) is a real crash and must not be relabelled "外观设置未能保存". The store port is
     * synchronous, so there is no cancellation to swallow here; if it ever becomes suspend, this catch
     * has to be narrowed so a `CancellationException` propagates.
     */
    fun select(mode: AppearanceThemeMode): AppearanceSelectionOutcome = synchronized(lock) {
        mutableThemeMode.value = mode
        try {
            preferences.saveThemeMode(mode)
            AppearanceSelectionOutcome.Persisted
        } catch (error: Exception) {
            AppearanceSelectionOutcome.KeptInMemoryOnly(
                mode = mode,
                userMessage = "外观设置未能保存，本次运行仍会按所选模式显示；重启后会回到上次保存的模式。",
                diagnostic = error.describeForDiagnostics()
            )
        }
    }

    private fun Exception.describeForDiagnostics(): String =
        "${javaClass.simpleName}: ${message ?: "no message"}"
}

/**
 * App-side preference to the design-system choice rendered by `McaAppearanceSection`.
 *
 * The mapping has to live in `:app`: `:core:designsystem` must not depend on `:app`, so it cannot know
 * [AppearanceThemeMode] at all, while `:app` already depends on `:core:designsystem`. The design-system
 * enum is public and this enum is internal, which is fine — an internal declaration may expose a public
 * type, only the reverse would leak.
 *
 * The `when` is exhaustive and maps value by value on purpose: adding a mode later becomes a compile
 * error here instead of a silent fallback through `wireName`.
 */
internal fun AppearanceThemeMode.toMcaChoice(): McaAppearanceThemeChoice = when (this) {
    AppearanceThemeMode.SYSTEM -> McaAppearanceThemeChoice.SYSTEM
    AppearanceThemeMode.LIGHT -> McaAppearanceThemeChoice.LIGHT
    AppearanceThemeMode.DARK -> McaAppearanceThemeChoice.DARK
}

/** Design-system choice back to the persisted preference; see [toMcaChoice]. */
internal fun McaAppearanceThemeChoice.toThemeMode(): AppearanceThemeMode = when (this) {
    McaAppearanceThemeChoice.SYSTEM -> AppearanceThemeMode.SYSTEM
    McaAppearanceThemeChoice.LIGHT -> AppearanceThemeMode.LIGHT
    McaAppearanceThemeChoice.DARK -> AppearanceThemeMode.DARK
}

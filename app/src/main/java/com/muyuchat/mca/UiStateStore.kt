package com.muyuchat.mca

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the app's single atomic state. Composition issues field-limited writers; readers and writers
 * share the same read-only flow. The mutable source and arbitrary root-state reducer never escape.
 */
class UiStateStore(initial: MainUiState) : UiStatusWriter {
    private val mutable = MutableStateFlow(initial)

    override val state: StateFlow<MainUiState> = mutable.asStateFlow()

    override val current: MainUiState
        get() = mutable.value

    /**
     * Issues a writer for one domain's fields. Only trusted composition factories supply [project]
     * and [merge]; business callers receive the resulting writer rather than this store.
     *
     * Projection, reduction and merge all run inside the same CAS attempt. A retry must project the
     * latest snapshot again so concurrent updates in both this domain and other domains survive.
     * All three functions must be pure because contention can cause them to run more than once.
     */
    internal fun <Fields> writer(
        project: (MainUiState) -> Fields,
        merge: (MainUiState, Fields) -> MainUiState,
    ): UiStateWriter<Fields> = UiStateWriter(
        source = this,
        committer = object : UiFieldCommitter<Fields> {
            override fun <Result> commit(
                reducer: (MainUiState, Fields) -> UiFieldCommit<Fields, Result>,
            ): UiFieldCommit<Fields, Result> = commitFields(project, merge, reducer)
        },
        writeStatus = ::showStatus,
    )

    private fun <Fields, Result> commitFields(
        project: (MainUiState) -> Fields,
        merge: (MainUiState, Fields) -> MainUiState,
        reducer: (MainUiState, Fields) -> UiFieldCommit<Fields, Result>,
    ): UiFieldCommit<Fields, Result> {
        while (true) {
            val snapshot = mutable.value
            val outcome = reducer(snapshot, project(snapshot))
            val committed = merge(snapshot, outcome.fields)
            if (mutable.compareAndSet(snapshot, committed)) return UiFieldCommit(project(committed), outcome.result)
        }
    }

    /** The named status-only command preserves all other fields in the same atomic state. */
    override fun showStatus(message: String?) {
        mutable.update { it.copy(statusMessage = message) }
    }
}

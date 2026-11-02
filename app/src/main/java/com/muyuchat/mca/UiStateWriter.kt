package com.muyuchat.mca

import kotlinx.coroutines.flow.StateFlow

/** Read-only projection of the app's authoritative snapshot; it grants no state mutation. */
interface UiStateReadPort {
    val current: MainUiState
    val state: StateFlow<MainUiState>
}

/** A named transient-status command for callers that do not own any other state fields. */
interface UiStatusWriter : UiStateReadPort {
    fun showStatus(message: String?)
}

/**
 * A capability to update only [Fields] while reading one consistent whole-app snapshot.
 *
 * The reducer returns fields, not a replacement [MainUiState]. Its trusted factory merges those
 * fields into the current CAS snapshot. No store, mutable flow or arbitrary root update is exposed.
 */
class UiStateWriter<Fields> internal constructor(
    private val source: UiStateReadPort,
    private val committer: UiFieldCommitter<Fields>,
    private val writeStatus: (String?) -> Unit,
) : UiStatusWriter {
    override val current: MainUiState
        get() = source.current

    override val state: StateFlow<MainUiState>
        get() = source.state

    /** May run repeatedly under contention; the reducer must be free of side effects. */
    fun update(reducer: (snapshot: MainUiState, fields: Fields) -> Fields) {
        committer.commit { snapshot, fields -> UiFieldCommit(reducer(snapshot, fields), Unit) }
    }

    /** Field-only form; projection is still repeated inside each CAS attempt. */
    fun update(reducer: (fields: Fields) -> Fields) {
        update { _, fields -> reducer(fields) }
    }

    /** Returns the successful CAS result, even if another update immediately follows it. */
    fun updateAndGet(reducer: (MainUiState, Fields) -> Fields): Fields =
        committer.commit { snapshot, fields -> UiFieldCommit(reducer(snapshot, fields), Unit) }.fields

    fun updateAndGet(reducer: (Fields) -> Fields): Fields = updateAndGet { _, fields -> reducer(fields) }

    /** The result belongs to the successful CAS attempt; failed attempts publish no result. */
    fun <Result> updateWithResult(reducer: (MainUiState, Fields) -> Pair<Fields, Result>): Result =
        committer.commit { snapshot, fields ->
            val (next, result) = reducer(snapshot, fields)
            UiFieldCommit(next, result)
        }.result

    fun <Result> updateWithResult(reducer: (Fields) -> Pair<Fields, Result>): Result =
        updateWithResult { _, fields -> reducer(fields) }

    /** Derives a smaller capability without access to the root store or a wider merge. */
    fun <Child> restrict(
        project: (Fields) -> Child,
        merge: (Fields, Child) -> Fields,
    ): UiStateWriter<Child> = UiStateWriter(
        source = this,
        committer = object : UiFieldCommitter<Child> {
            override fun <Result> commit(
                reducer: (MainUiState, Child) -> UiFieldCommit<Child, Result>,
            ): UiFieldCommit<Child, Result> {
                val committed = committer.commit { snapshot, fields ->
                    val child = reducer(snapshot, project(fields))
                    UiFieldCommit(merge(fields, child.fields), child.result)
                }
                return UiFieldCommit(project(committed.fields), committed.result)
            }
        },
        writeStatus = ::showStatus,
    )

    override fun showStatus(message: String?) {
        writeStatus(message)
    }
}

internal data class UiFieldCommit<Fields, Result>(val fields: Fields, val result: Result)

internal interface UiFieldCommitter<Fields> {
    fun <Result> commit(reducer: (MainUiState, Fields) -> UiFieldCommit<Fields, Result>): UiFieldCommit<Fields, Result>
}

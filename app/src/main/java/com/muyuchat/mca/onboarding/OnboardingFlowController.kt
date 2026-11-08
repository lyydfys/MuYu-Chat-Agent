package com.muyuchat.mca.onboarding

import com.muyuchat.core.designsystem.onboarding.McaOnboardingStep

/**
 * The first-run flow's state machine: `WELCOME -> PATH -> MODEL -> DONE`.
 *
 * It owns every rule the four screens share, and it owns nothing else: no composable, no `Context`, no
 * coroutine scope. It is injected with [OnboardingPreferences], so the whole flow can be driven on a plain
 * JVM against an in-memory store.
 *
 * ## Rules
 *
 * 1. Page order is fixed and forward-only: [advance] moves one page, [back] moves one page the other way.
 *    The welcome page is the floor of `back` (it returns itself, the flow never exits itself) and the done
 *    page refuses both directions.
 * 2. `PATH` cannot be left forward while [OnboardingPath.Unset]; [advance] then returns the unchanged
 *    projection with [OnboardingRefusal.PATH_NOT_SELECTED]. It never throws and never pretends to succeed.
 * 3. The model page is left in one of three ways: [skipModel] (records `skippedModel = true`),
 *    [markModelReady] (records `skippedModel = false`), or a bare [advance] that records no answer. A UI
 *    that knows which happened should call the specific one so the stored answer stays truthful.
 * 4. `DONE` is the last page but is not, by itself, completion: reaching it writes only the step, so an
 *    interrupted flow resumes on it. [complete] is the single terminal write that suppresses the flow.
 * 5. `completed` and `step` can never contradict each other, not even transiently: a forward step write
 *    never touches the completion flag, and [complete] writes `step = done` before `completed = true`
 *    (see [OnboardingPreferences.markCompleted]). A reader therefore never observes
 *    `completed = true, step = welcome`.
 * 6. Every state change is written immediately, one preference key at a time. There is exactly one
 *    authority for the flow's progress — the preference store — and this class keeps no cached copy, so a
 *    process death can only lose an in-flight tap, never a completed transition.
 * 7. Repeated [complete] is idempotent: the second call returns the same projection and writes nothing.
 * 8. A failing write never crashes the flow. It is reported through [OnboardingFlowUiState.writeFailure]
 *    while the in-memory projection still advances, so a broken store degrades into "the flow works until
 *    the process dies" rather than into a screen the user cannot leave.
 *
 * Refusals and write failures are values ([OnboardingRefusal], [OnboardingWriteFailure]), not exceptions:
 * every public function returns a projection and none of them throws for a rule violation.
 */
internal class OnboardingFlowController(private val preferences: OnboardingPreferences) {

    /** Restores the projection from the persisted state. Reads only, so a cold start writes nothing. */
    fun load(): OnboardingFlowUiState = project(preferences.load())

    /**
     * Moves one page forward: `WELCOME -> PATH -> MODEL -> DONE`.
     *
     * Refused — with no write and with `step`, `path`, `skippedModel` and `completed` unchanged — when the
     * path page has no answer ([OnboardingRefusal.PATH_NOT_SELECTED]) or when the flow is already on the
     * done page ([OnboardingRefusal.FLOW_COMPLETED]). The returned projection carrying a refusal has
     * `canAdvance == false`, which is how the caller distinguishes "button was disabled" from "the tap
     * did nothing".
     */
    fun advance(): OnboardingFlowUiState {
        val current = preferences.load()
        val target = when (current.step) {
            OnboardingStep.WELCOME -> OnboardingStep.PATH
            OnboardingStep.PATH -> OnboardingStep.MODEL
            OnboardingStep.MODEL -> OnboardingStep.DONE
            // The last page is the end of the flow: a further tap is refused rather than wrapped around.
            OnboardingStep.DONE -> return project(current, rejection = OnboardingRefusal.FLOW_COMPLETED)
        }
        current.forwardBlocker()?.let { blocker -> return project(current, rejection = blocker) }
        return moveTo(current, target)
    }

    /**
     * Moves one page back: `MODEL -> PATH -> WELCOME`.
     *
     * The welcome page returns itself with [OnboardingRefusal.AT_FIRST_STEP]: backing out of the first
     * screen must not drop the user into an app whose first run is unfinished.
     *
     * The done page refuses ([OnboardingRefusal.FLOW_COMPLETED]) rather than reopening a completed flow:
     * walking back from a terminal page would either clear `completed` behind the user's back or leave
     * `completed = true` paired with an earlier step. "Show the flow again" is [reset], an explicit choice.
     *
     * Answers survive a back step: returning to the path page still shows the earlier choice, and the
     * model page is reached again without re-answering.
     */
    fun back(): OnboardingFlowUiState {
        val current = preferences.load()
        val target = when (current.step) {
            OnboardingStep.WELCOME -> return project(current, rejection = OnboardingRefusal.AT_FIRST_STEP)
            OnboardingStep.PATH -> OnboardingStep.WELCOME
            OnboardingStep.MODEL -> OnboardingStep.PATH
            OnboardingStep.DONE -> return project(current, rejection = OnboardingRefusal.FLOW_COMPLETED)
        }
        return moveTo(current, target)
    }

    /**
     * Records the path answer without moving the flow.
     *
     * The choice is an answer, not a transition: the path page renders the selection and the user presses
     * forward themselves, so this never changes [OnboardingFlowUiState.step]. It is accepted on any page
     * before completion, which lets a restored flow correct its answer, and refused afterwards with
     * [OnboardingRefusal.FLOW_COMPLETED] because a finished install is not edited from behind the user.
     */
    fun selectPath(path: OnboardingPath): OnboardingFlowUiState {
        val current = preferences.load()
        if (current.completed) {
            return project(current, rejection = OnboardingRefusal.FLOW_COMPLETED)
        }
        val failure = attempt(OnboardingWriteTarget.PATH) { preferences.savePath(path) }
        return project(current.copy(path = path), writeFailure = failure)
    }

    /**
     * Records that the model page was left without a download, and advances to the last page.
     *
     * The flag is written before the step, so a process death between the two writes resumes as "skipped,
     * still on the model page" — an answer the user can act on — instead of "on the last page, never
     * skipped", which would be a lie about what they did. Refused off the model page
     * ([OnboardingRefusal.NOT_ON_MODEL_STEP]), so the answer can never be recorded for a page the user has
     * not seen.
     */
    fun skipModel(): OnboardingFlowUiState = answerModelStep(skipped = true)

    /**
     * Records that a model is installed and ready, and advances to the last page.
     *
     * Clears an earlier skip so a flow that visited the model page twice cannot claim both answers. The flag
     * is only written when it disagrees with the stored value, so confirming twice does not churn the
     * preference file. Refused off the model page ([OnboardingRefusal.NOT_ON_MODEL_STEP]).
     */
    fun markModelReady(): OnboardingFlowUiState = answerModelStep(skipped = false)

    /**
     * Ends the flow for this install: the terminal write, after which the first-run screens never appear
     * again.
     *
     * Idempotent. A flow that is already completed is returned unchanged with nothing written, so a
     * double-tapped final button, a re-composed screen or a retried callback cannot add writes or flip the
     * flag back. The projection matches what the next launch will read: [OnboardingState.Completed] (path
     * and skip are not part of a finished flow's projection, exactly as [OnboardingPreferences.load] decides).
     *
     * Unlike [advance], this is allowed from any page: it is the flow's explicit escape hatch, and it still
     * writes the legal pair (`step = done` first, then `completed = true`).
     */
    fun complete(): OnboardingFlowUiState {
        val current = preferences.load()
        if (current.completed) {
            return project(current)
        }
        val failure = attempt(OnboardingWriteTarget.COMPLETION) { preferences.markCompleted() }
        return project(OnboardingState.Completed, writeFailure = failure)
    }

    /**
     * Clears the flow and its answers so "review onboarding again" starts over at the welcome page.
     *
     * The projection is read back from the store afterwards rather than predicted, because a partially
     * failed reset leaves a mixture of cleared and stale keys; reporting what is actually persisted is the
     * only honest answer, and any failure is surfaced through [OnboardingFlowUiState.writeFailure].
     */
    fun reset(): OnboardingFlowUiState {
        val failure = attempt(OnboardingWriteTarget.RESET) { preferences.reset() }
        return project(preferences.load(), writeFailure = failure)
    }

    /** Shared body of [skipModel] and [markModelReady]: record the answer, then move to the last page. */
    private fun answerModelStep(skipped: Boolean): OnboardingFlowUiState {
        val current = preferences.load()
        if (current.step != OnboardingStep.MODEL) {
            return project(current, rejection = OnboardingRefusal.NOT_ON_MODEL_STEP)
        }
        // Only write the answer when it actually changes, so repeating the action is not extra I/O.
        val answerFailure = if (current.skippedModel == skipped) {
            null
        } else {
            attempt(OnboardingWriteTarget.SKIPPED_MODEL) { preferences.saveSkippedModel(skipped) }
        }
        val stepFailure = attempt(OnboardingWriteTarget.STEP) {
            preferences.saveStepWithoutCompleting(OnboardingStep.DONE)
        }
        return project(
            current.copy(step = OnboardingStep.DONE, skippedModel = skipped),
            writeFailure = answerFailure ?: stepFailure
        )
    }

    /**
     * The single step write every transition goes through.
     *
     * The done page is written with [OnboardingPreferences.saveStepWithoutCompleting] so it stays a page
     * rather than a completion claim; every earlier page keeps using the legacy `saveStep`, whose extra
     * guard is that a completed install is never un-completed by a step write.
     */
    private fun moveTo(current: OnboardingState, target: OnboardingStep): OnboardingFlowUiState {
        val failure = attempt(OnboardingWriteTarget.STEP) {
            if (target == OnboardingStep.DONE) {
                preferences.saveStepWithoutCompleting(target)
            } else {
                preferences.saveStep(target)
            }
        }
        // `completed` is never set here: a completed flow is refused above every call path, and only
        // complete() may end it. copy() keeps the answers the earlier pages already recorded.
        return project(current.copy(step = target), writeFailure = failure)
    }

    /**
     * The forward gate per page, and the single definition behind both [advance] and
     * [OnboardingFlowUiState.canAdvance], so a disabled button and a refused transition cannot disagree.
     * `null` means the page may move forward. Pinned by `前进按钮与前进结果始终一致`.
     */
    private fun OnboardingState.forwardBlocker(): OnboardingRefusal? = when (step) {
        OnboardingStep.WELCOME -> null
        OnboardingStep.PATH -> if (path == null) OnboardingRefusal.PATH_NOT_SELECTED else null
        OnboardingStep.MODEL -> null
        OnboardingStep.DONE -> OnboardingRefusal.FLOW_COMPLETED
    }

    /** Builds the UI projection: one place that decides what each page looks like to the caller. */
    private fun project(
        state: OnboardingState,
        rejection: OnboardingRefusal? = null,
        writeFailure: OnboardingWriteFailure? = null
    ): OnboardingFlowUiState {
        val step = state.step.toMcaStep()
        return OnboardingFlowUiState(
            step = step,
            path = state.path?.toMcaChoice(),
            skippedModel = state.skippedModel,
            completed = state.completed,
            canAdvance = state.forwardBlocker() == null,
            stepIndex = step.ordinal + 1,
            stepCount = McaOnboardingStep.entries.size,
            rejection = rejection,
            writeFailure = writeFailure
        )
    }

    /**
     * Runs one write, converting a store failure into a reportable value.
     *
     * Only [RuntimeException] is caught: an `Error` (out of memory, a broken class) is not a recoverable
     * storage condition and must keep propagating. Nothing in the first-run flow suspends, so there is no
     * `CancellationException` to preserve here.
     */
    private fun attempt(target: OnboardingWriteTarget, write: () -> Unit): OnboardingWriteFailure? =
        try {
            write()
            null
        } catch (error: RuntimeException) {
            OnboardingWriteFailure(
                target = target,
                reason = error.message ?: error.javaClass.simpleName
            )
        }
}

package com.muyuchat.mca.onboarding

import com.muyuchat.core.designsystem.onboarding.McaOnboardingPathChoice
import com.muyuchat.core.designsystem.onboarding.McaOnboardingStep

/**
 * Why the first-run flow refused a navigation request.
 *
 * A refusal is a normal, reportable outcome rather than an error: [OnboardingFlowController] returns the
 * unchanged projection with one of these set instead of throwing or silently succeeding, so the caller can
 * ignore the tap, show a hint, or disable the button that fired it.
 */
internal enum class OnboardingRefusal {
    /** `advance()` on the path page while no path is chosen; the model page's copy depends on that answer. */
    PATH_NOT_SELECTED,

    /** `back()` on the welcome page: it is the floor of the flow, and the flow must not exit itself. */
    AT_FIRST_STEP,

    /** The model page's answers are only accepted while the model page is actually showing. */
    NOT_ON_MODEL_STEP,

    /** The flow already completed for this install; completion is terminal and never reopened implicitly. */
    FLOW_COMPLETED
}

/** The persisted value an [OnboardingWriteFailure] was about. */
internal enum class OnboardingWriteTarget {
    STEP,
    PATH,
    SKIPPED_MODEL,
    COMPLETION,
    RESET
}

/**
 * A preference write that did not land, reported instead of thrown.
 *
 * The controller keeps moving forward in memory — the user's tap is not dropped — while this tells the
 * caller the progress is not durable: a process death would resume on the previous page. [reason] is
 * diagnostic text taken from the store and is never shown to the user as-is.
 */
internal data class OnboardingWriteFailure(
    val target: OnboardingWriteTarget,
    val reason: String
)

/**
 * What the first-run UI renders: one immutable projection of the persisted flow state.
 *
 * Produced only by [OnboardingFlowController]; the UI reads it and never builds or mutates it. [step] and
 * [path] use the `:core:designsystem` enums because the pages take those types directly, while the
 * persisted `:app` enums stay private to this package.
 *
 * [rejection] and [writeFailure] describe the operation that produced *this* value, so a stale error cannot
 * outlive the next tap: every call returns a fresh projection. A `null` [rejection] together with a
 * non-null [writeFailure] means the tap was legal but could not be persisted.
 */
internal data class OnboardingFlowUiState(
    /** The page the flow is showing. */
    val step: McaOnboardingStep,
    /** The chosen install shape, or `null` while the path page is unanswered. */
    val path: McaOnboardingPathChoice?,
    /** True once the user left the model page without downloading anything. */
    val skippedModel: Boolean,
    /** True only after [OnboardingFlowController.complete]; a completed flow never reappears. */
    val completed: Boolean,
    /** True when [OnboardingFlowController.advance] would actually move to the next page. */
    val canAdvance: Boolean,
    /** 1-based position of [step] in the progress row; one of `1..stepCount`. */
    val stepIndex: Int,
    /** Total number of first-run pages, so the progress row never hard-codes it. */
    val stepCount: Int,
    /** Set when the request that produced this value was refused; see [OnboardingRefusal]. */
    val rejection: OnboardingRefusal? = null,
    /** Set when the write behind this value failed; the in-memory flow still moved. */
    val writeFailure: OnboardingWriteFailure? = null
)

/**
 * Maps a persisted step onto the page enum the design system renders.
 *
 * Deliberately an exhaustive `when` with no `else`: the design system cannot depend on `:app`, so the two
 * enums are only kept in step by this pair of functions, and adding a page on either side must fail to
 * compile here instead of silently rendering the wrong screen.
 */
internal fun OnboardingStep.toMcaStep(): McaOnboardingStep = when (this) {
    OnboardingStep.WELCOME -> McaOnboardingStep.WELCOME
    OnboardingStep.PATH -> McaOnboardingStep.PATH
    OnboardingStep.MODEL -> McaOnboardingStep.MODEL
    OnboardingStep.DONE -> McaOnboardingStep.DONE
}

/** Inverse of [toMcaStep]. */
internal fun McaOnboardingStep.toOnboardingStep(): OnboardingStep = when (this) {
    McaOnboardingStep.WELCOME -> OnboardingStep.WELCOME
    McaOnboardingStep.PATH -> OnboardingStep.PATH
    McaOnboardingStep.MODEL -> OnboardingStep.MODEL
    McaOnboardingStep.DONE -> OnboardingStep.DONE
}

/**
 * Maps the persisted path answer onto the design system's choice row.
 *
 * The real path payload never crosses the module boundary: only the label/description pair the page shows.
 */
internal fun OnboardingPath.toMcaChoice(): McaOnboardingPathChoice = when (this) {
    OnboardingPath.EXPERIENCE -> McaOnboardingPathChoice.EXPERIENCE
    OnboardingPath.PRODUCTION -> McaOnboardingPathChoice.PRODUCTION
}

/** Inverse of [toMcaChoice]. */
internal fun McaOnboardingPathChoice.toOnboardingPath(): OnboardingPath = when (this) {
    McaOnboardingPathChoice.EXPERIENCE -> OnboardingPath.EXPERIENCE
    McaOnboardingPathChoice.PRODUCTION -> OnboardingPath.PRODUCTION
}

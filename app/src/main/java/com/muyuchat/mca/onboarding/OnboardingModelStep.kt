package com.muyuchat.mca.onboarding

/**
 * What the model step of the first-run flow should show, expressed as plain values.
 *
 * This type exists so the guide can render a *real* download without ever touching `MainUiState`:
 * the composition root maps the app's download state onto these fields, and `FirstRunHost` /
 * `OnboardingFlowHost` only ever see this small value. That keeps the "the guide does not depend on
 * global session state" contract intact while still letting the page show genuine progress.
 *
 * Every field is derived from an observed event or a persisted fact — never from a timer:
 *  - [downloading] and [progressPercent] come from the download owner's own byte counters;
 *  - [failure] carries the concrete reason (and the next step, when the owner supplies one);
 *  - [available] is false when no recommendation could be resolved for this device, or when the
 *    owner says this model cannot be downloaded here. The page must then degrade to
 *    「稍后下载模型」 rather than promise a download that cannot start.
 *
 * [Unavailable] is the default so a caller with no download wiring keeps the honest copy.
 */
internal data class OnboardingModelStep(
    /** Human label of the model the primary action would download; null when none was resolved. */
    val modelLabel: String? = null,
    /** True when tapping the primary action can actually start a download right now. */
    val available: Boolean = false,
    /** True while the owner reports an in-flight download. */
    val downloading: Boolean = false,
    /** 0..100 derived from the owner's byte counters; null when no denominator is known yet. */
    val progressPercent: Int? = null,
    /** Concrete failure text for the user, including what to do next; null when there is none. */
    val failure: String? = null
) {
    companion object {
        /** No recommendation resolved, or no download owner wired: the page must not promise one. */
        val Unavailable: OnboardingModelStep = OnboardingModelStep()
    }
}

/**
 * Maps the app's download facts onto [OnboardingModelStep].
 *
 * Pure and synchronous so the whole mapping — including every degradation branch — is unit-testable
 * without Android. Callers pass the raw facts they already observe; this function owns only the
 * decisions:
 *
 * 1. No recommendation, or the owner says this device cannot download it → unavailable. The page
 *    degrades to 「稍后下载模型」 (the existing `downloadAvailable = false` state), never to a button
 *    that silently does nothing.
 * 2. A block reason wins over a stale failure: it explains *why nothing can start* right now, which
 *    is more actionable than the last error.
 * 3. A percentage is only ever `downloaded / total` from the owner's counters, clamped to 0..100.
 *    With no total there is no percentage — the page shows 「正在下载…」 instead of inventing one.
 *
 * @param modelLabel label of the resolved recommendation, or null when none could be resolved.
 * @param downloadedBytes bytes the owner reports for the current task.
 * @param totalBytes total bytes of the current task, or 0 when unknown.
 * @param running true while the owner reports an in-flight download.
 * @param failed true when the owner's last task ended in failure.
 * @param failureReason the owner's reason for that failure, if it supplied one.
 * @param blockReason why this model cannot be downloaded here, if the owner refused it up front.
 */
internal fun onboardingModelStep(
    modelLabel: String?,
    downloadedBytes: Long,
    totalBytes: Long,
    running: Boolean,
    failed: Boolean,
    failureReason: String?,
    blockReason: String?
): OnboardingModelStep {
    if (modelLabel == null) return OnboardingModelStep.Unavailable
    val available = blockReason == null
    val failure = when {
        blockReason != null -> "$blockReason 你可以先进入应用，稍后在「模型管理」里重试。"
        failed -> (failureReason?.takeIf { it.isNotBlank() }
            ?: "下载没有完成。检查网络后，可以在这里重试，或先进入应用稍后在「模型管理」里下载。")
        else -> null
    }
    return OnboardingModelStep(
        modelLabel = modelLabel,
        available = available,
        downloading = available && running,
        progressPercent = if (available && running && totalBytes > 0L) {
            ((downloadedBytes.coerceAtLeast(0L) * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            null
        },
        failure = failure
    )
}

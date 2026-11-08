package com.muyuchat.mca.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.dsh.contract.container.DshContainerInstallSnapshot
import com.muyuchat.core.designsystem.onboarding.McaOnboardingDonePage
import com.muyuchat.core.designsystem.onboarding.McaOnboardingModelPage
import com.muyuchat.core.designsystem.onboarding.McaOnboardingPathChoice
import com.muyuchat.core.designsystem.onboarding.McaOnboardingPathPage
import com.muyuchat.core.designsystem.onboarding.McaOnboardingStep
import com.muyuchat.core.designsystem.onboarding.McaOnboardingWelcomePage

/**
 * The first-run flow's only host: maps the persisted projection onto the four design-system pages.
 *
 * It renders and nothing else. Every answer is forwarded to [OnboardingFlowController] by the caller,
 * which returns a fresh [OnboardingFlowUiState]; this composable never builds or mutates one. That is
 * what keeps the persisted preference the single authority for "which page am I on": a process death
 * mid-flow resumes through `OnboardingFlowController.load()` and the UI has no state of its own to
 * lose.
 *
 * Why a host file rather than four branches inline in `MainActivity`: the activity is already 825
 * lines against an 800-line review budget, and the page-to-page wiring is a real responsibility that
 * deserves its own file and its own tests. `MainActivity` keeps one call site.
 *
 * Back handling is owned here because the flow must not fall out of its first page: the system back
 * button returns to the welcome page and stops there. [OnboardingFlowController.back] already returns
 * the unchanged projection at the floor instead of throwing, so this handler is a plain forward.
 */
@Composable
internal fun OnboardingFlowHost(
    state: OnboardingFlowUiState,
    onStart: () -> Unit,
    onSelectPath: (McaOnboardingPathChoice) -> Unit,
    onContinuePath: () -> Unit,
    onDownloadRecommended: () -> Unit,
    onSkipModel: () -> Unit,
    onBack: () -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
    modelStep: OnboardingModelStep = OnboardingModelStep.Unavailable,
    /**
     * The container install's real state, or null when no install port is wired (tests, previews).
     * Passing null keeps the path page exactly as it was before this port existed.
     */
    installState: DshContainerInstallSnapshot? = null,
    onRetryInstall: () -> Unit = {},
    onCancelInstall: () -> Unit = {}
) {
    BackHandler { onBack() }

    // One surface for the whole window. The pages paint their own background, but on API 35+ (this app
    // targets SDK 36, so edge-to-edge is enforced) the status-bar strip would be left unpainted under
    // the cutout and the progress row would be drawn behind the clock. `AppStartupScreen` solves the
    // same problem the same way: the background stays full-bleed and only the readable content respects
    // `safeDrawing`, which is a no-op on devices that still fit system windows. Modifier order is what
    // draws that split: `background` after `fillMaxSize` covers the whole window, the inset padding
    // added afterwards only shrinks the area handed to the children.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Exactly one notice line for the two non-fatal outcomes the controller can report. A
        // refusal means the tap was ignored (the copy explains what is missing); a write failure
        // means the choice is live now but will not survive a restart. Neither is an error dialog:
        // the flow stays usable. Showing one line rather than two is safe because the controller
        // never reports both at once — every refusal returns before a write is attempted — and
        // `拒绝与写盘失败不会同时出现` pins that.
        val notice = state.notice()
        if (notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp)
            )
        }
        Column(verticalArrangement = Arrangement.Top) {
            when (state.step) {
                McaOnboardingStep.WELCOME -> McaOnboardingWelcomePage(onStart = onStart)

                McaOnboardingStep.PATH -> Column {
                    // The real install progress sits above the page, so choosing 「本机运行」 has a
                    // visible consequence within one frame instead of silently recording a preference.
                    if (installState != null) {
                        McaOnboardingInstallStrip(
                            state = installState,
                            started = state.path == McaOnboardingPathChoice.EXPERIENCE,
                            onRetry = onRetryInstall,
                            onCancel = onCancelInstall
                        )
                    }
                    McaOnboardingPathPage(
                        selected = state.path,
                        onSelect = onSelectPath,
                        onContinue = onContinuePath,
                        onBack = onBack,
                        modifier = Modifier.weight(1f)
                    )
                }

                McaOnboardingStep.MODEL -> McaOnboardingModelPage(
                    onDownloadRecommended = onDownloadRecommended,
                    onSkip = onSkipModel,
                    onBack = onBack,
                    // `downloadAvailable` is true only while a recommendation is actually resolvable
                    // and the download owner accepts it. When it is false the primary action reads
                    // 「稍后下载模型」, the "downloads start automatically" line is hidden and the
                    // redundant second action disappears — the honest degradation, never a button
                    // that silently does nothing.
                    downloadAvailable = modelStep.available,
                    downloading = modelStep.downloading,
                    progressPercent = modelStep.progressPercent,
                    modelLabel = modelStep.modelLabel,
                    failure = modelStep.failure
                )

                McaOnboardingStep.DONE -> McaOnboardingDonePage(
                    onEnter = onEnter,
                    skippedModel = state.skippedModel
                )
            }
        }
    }
}

/** The single line for a refused tap or a failed write, or `null` when the last tap was fine. */
private fun OnboardingFlowUiState.notice(): String? {
    val refusal = rejection
    if (refusal != null) return refusal.hint(completed)
    return if (writeFailure != null) WRITE_FAILURE_HINT else null
}

/**
 * Chinese, plain and actionable: it names what to do, not which rule fired.
 *
 * [completed] disambiguates [OnboardingRefusal.FLOW_COMPLETED], which the controller reports for the
 * done page whether or not the flow was completed: the last page refuses `back` while `completed` is
 * still false (the user is standing on it and has not pressed 进入应用), and telling them "引导已经
 * 完成，可以在设置里重新查看" there would be both false and unactionable — settings is not reachable
 * from inside the guide.
 */
private fun OnboardingRefusal.hint(completed: Boolean): String = when (this) {
    OnboardingRefusal.PATH_NOT_SELECTED -> "先选择一种运行方式，再继续。"
    OnboardingRefusal.AT_FIRST_STEP -> "已经是第一步了。"
    OnboardingRefusal.NOT_ON_MODEL_STEP -> "回到模型这一步才能这样操作。"
    OnboardingRefusal.FLOW_COMPLETED -> if (completed) {
        "引导已经完成，可以在设置里重新查看。"
    } else {
        "这是最后一步，点下面的「进入应用」继续。"
    }
}

/**
 * A failed write still let the flow move, so this must not read like a blocker; it only tells the user
 * the choice may not be remembered if the app is closed and reopened. [OnboardingWriteFailure.reason]
 * is diagnostic text and is deliberately not rendered.
 */
private const val WRITE_FAILURE_HINT = "这一步没能保存；如果现在关闭应用，可能会回到上一步。"

/**
 * The real container-install state, shown above the path page.
 *
 * Every branch renders a fact the install owner reported: an owner line for progress and failures, and
 * a distinct "已取消" branch because a user-requested stop is not an error. There is no timer and no
 * estimate here — when the owner reports no denominator the percentage is simply absent.
 *
 * Actions are only offered when they exist: 取消 while running, 重试 after a failure or cancellation,
 * and nothing at all once the runtime is ready.
 */
@Composable
private fun McaOnboardingInstallStrip(
    state: DshContainerInstallSnapshot,
    started: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val headline = when (state.phase) {
        DshContainerInstallSnapshot.Phase.RUNNING ->
            state.progressPercent?.let { "正在安装运行环境 $it%" } ?: "正在安装运行环境…"
        DshContainerInstallSnapshot.Phase.SUCCEEDED -> "运行环境已就绪。"
        DshContainerInstallSnapshot.Phase.CANCELLED -> "已取消安装。已解压的内容会保留，重试时不会重复下载。"
        DshContainerInstallSnapshot.Phase.FAILED -> "安装没有完成。"
        DshContainerInstallSnapshot.Phase.IDLE ->
            if (started) "准备安装运行环境…" else "选择上面的「本机运行」会开始安装运行环境。"
    }
    val detail = when (state.phase) {
        DshContainerInstallSnapshot.Phase.RUNNING,
        DshContainerInstallSnapshot.Phase.FAILED -> state.detail
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.phase == DshContainerInstallSnapshot.Phase.FAILED) scheme.error else scheme.onSurface
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.phase == DshContainerInstallSnapshot.Phase.FAILED) {
                    scheme.error
                } else {
                    scheme.onSurfaceVariant
                }
            )
        }
        when (state.phase) {
            DshContainerInstallSnapshot.Phase.RUNNING -> McaButton(
                onClick = onCancel,
                style = McaButtonStyle.Text
            ) { Text("取消安装") }

            DshContainerInstallSnapshot.Phase.FAILED,
            DshContainerInstallSnapshot.Phase.CANCELLED -> McaButton(
                onClick = onRetry,
                style = McaButtonStyle.Outlined
            ) { Text("重试安装") }

            else -> Unit
        }
    }
}

package com.muyuchat.mca

import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muyuchat.core.designsystem.McaFeedbackLayer
import com.muyuchat.core.designsystem.McaTheme
import com.muyuchat.core.designsystem.appearance.McaAppearanceThemeChoice
import com.muyuchat.core.designsystem.onboarding.McaOnboardingPathChoice
import com.muyuchat.core.dsh.contract.container.DshContainerInstallPort
import com.muyuchat.mca.appearance.AppearanceController
import com.muyuchat.mca.appearance.AppearancePreferences
import com.muyuchat.mca.appearance.AppearanceSelectionOutcome
import com.muyuchat.mca.appearance.AppearanceThemeMode
import com.muyuchat.mca.appearance.AppearanceThemeResolver
import com.muyuchat.mca.appearance.SharedPreferencesAppearanceStore
import com.muyuchat.mca.appearance.toMcaChoice
import com.muyuchat.mca.appearance.toThemeMode
import com.muyuchat.mca.onboarding.DshContainerInstallAdapter
import com.muyuchat.mca.onboarding.OnboardingFlowController
import com.muyuchat.mca.onboarding.OnboardingFlowHost
import com.muyuchat.mca.onboarding.OnboardingModelStep
import com.muyuchat.mca.onboarding.OnboardingPreferences
import com.muyuchat.mca.onboarding.SharedPreferencesOnboardingStore
import com.muyuchat.mca.onboarding.toOnboardingPath
import kotlinx.coroutines.flow.StateFlow

/**
 * The composition-root state for the two app-wide first-frame decisions: colour mode and whether the
 * first-run flow still has to be shown.
 *
 * It lives in its own file because `MainActivity` is the app's largest file and already sits past the
 * 800-line review budget in `docs/architecture/MCA_PROJECT_HARD_LOCK.md` §8. Keeping the gate, the
 * page callbacks and the settings callbacks here means the activity grows by a few lines instead of
 * sixty.
 *
 * Two preference owners are held, and only projections cross into the composition:
 *  - [appearance] owns the persisted colour mode; [appearanceChoice] and [selectAppearance] are the
 *    read/write pair the settings surface needs, and [darkTheme] resolves it for `McaTheme`;
 *  - [onboarding] owns the persisted first-run progress; [flow] is the immutable page projection the
 *    guide renders.
 *
 * [flow] is the only mutable field and it is always replaced wholesale by a value the controller
 * returned. That is deliberate: the persisted preference stays the single authority, so a process
 * death mid-flow resumes through `OnboardingFlowController.load()` and nothing here has to be
 * restored or migrated.
 *
 * Deliberately **not** `@Stable`: [appearanceChoice], [themeModeFlow] and the single-argument
 * [darkTheme] expose values that change without invalidating composition (`StateFlow` is not snapshot
 * state). `@Stable` promises the opposite — that a public property change notifies composition — so
 * annotating this class would be exactly the "stable annotation hiding mutable data" the architecture
 * contract forbids. Composition must read [darkThemeForFrame] / [appearanceChoiceForFrame], which
 * collect the flow; the leaky readers stay for plain JUnit and for callers outside composition. The
 * cost of dropping the annotation is only lost skipping, and nothing passes this state into a
 * skippable composable as a value.
 */
internal class FirstRunState(
    private val appearance: AppearanceController,
    private val onboarding: OnboardingFlowController,
    /**
     * The local-runtime install, or null when this build wires none.
     *
     * Null keeps every existing caller and test valid: the guide then renders exactly the pre-port path
     * page, with no install strip and no pretend progress.
     */
    private val containerInstall: DshContainerInstallPort? = null
) {

    /** The page the guide is showing, or a completed projection once it has been finished. */
    var flow by mutableStateOf(onboarding.load())
        private set

    /**
     * The persisted choice for readers **outside** composition (tests, diagnostics).
     *
     * Do NOT read this inside a composable: it returns `StateFlow.value`, which Compose does not
     * track, so switching the mode would not recompose the caller and the rows would keep showing the
     * old selection. Composition uses [appearanceChoiceForFrame] instead.
     */
    val appearanceChoice: McaAppearanceThemeChoice get() = appearance.themeMode.value.toMcaChoice()

    /**
     * The colour mode as a flow, so callers inside composition can observe it.
     *
     * Reading [appearanceChoice] or [darkTheme] directly in a composable would NOT work: they read
     * `StateFlow.value`, which Compose does not track, so the theme would keep painting the value it
     * saw on the first composition and a user switching to dark would see nothing happen. The two
     * `@Composable` helpers below are the supported entry points for that reason.
     */
    val themeModeFlow: StateFlow<AppearanceThemeMode> get() = appearance.themeMode

    /**
     * Resolves the colour mode from the current value, for readers **outside** composition.
     *
     * Takes the system value as a parameter rather than reading it here so this class stays free of
     * composition-only calls and is constructible in a plain unit test.
     *
     * Do NOT call this inside a composable: it reads `StateFlow.value` without collecting, so the frame
     * would keep whatever mode it saw first and a switch to dark would appear to do nothing. The
     * composition path is [darkThemeForFrame].
     */
    fun darkTheme(systemDark: Boolean): Boolean =
        AppearanceThemeResolver.resolveDarkTheme(systemDark, appearance.themeMode.value)

    /** Same resolution with the mode supplied by the caller, for the observed composable paths. */
    fun darkTheme(systemDark: Boolean, mode: AppearanceThemeMode): Boolean =
        AppearanceThemeResolver.resolveDarkTheme(systemDark, mode)

    /**
     * Applies a colour mode.
     *
     * The controller publishes the new value even when the write fails, so the theme flips either
     * way, which is why this never interrupts the user over a cosmetic preference. The failure is
     * still logged rather than dropped: [appearance] is rebuilt per composition, so a mode that
     * could not be persisted silently reverts to the stored one on the next launch (and after an
     * activity recreation), and without this line that "it changed back by itself" is untraceable.
     */
    fun selectAppearance(choice: McaAppearanceThemeChoice) {
        val outcome = appearance.select(choice.toThemeMode())
        if (outcome is AppearanceSelectionOutcome.KeptInMemoryOnly) {
            Log.w(APPEARANCE_LOG_TAG, "colour mode not persisted: ${outcome.diagnostic}")
        }
    }

    fun start() { flow = onboarding.advance() }

    fun continuePath() { flow = onboarding.advance() }

    /**
     * Records the path answer; the persisted enum never crosses the module boundary.
     *
     * Choosing 「本机运行」 is what starts the real install: the answer is not just recorded, it is the
     * trigger. Starting from here (and not from a composable effect) keeps the run owned by the install
     * port — a recomposition or a rotation cannot start it twice.
     */
    fun selectPath(choice: McaOnboardingPathChoice) {
        flow = onboarding.selectPath(choice.toOnboardingPath())
        if (choice == McaOnboardingPathChoice.EXPERIENCE) containerInstall?.start()
    }

    /** Restarts the install after a failure or a user cancellation. */
    fun retryInstall() { containerInstall?.start() }

    /** Stops a running install; the owner lands on its own cancelled state. */
    fun cancelInstall() { containerInstall?.cancel() }

    fun skipModel() { flow = onboarding.skipModel() }

    fun back() { flow = onboarding.back() }

    fun enter() { flow = onboarding.complete() }

    /**
     * Replays the guide without touching anything else: the controller clears only its own keys, and
     * the flow resumes on the welcome page because [flow] is replaced by its return value.
     */
    fun replay() { flow = onboarding.reset() }

    /**
     * The guide itself: the host renders whichever page [flow] currently projects.
     *
     * [modelStep] is the model step's real download state and [onDownloadModel] is the app's real
     * download entry point. Both are supplied by the composition root, so this class still never
     * touches `MainUiState`; passing [OnboardingModelStep.Unavailable] degrades the page to the honest
     * 「稍后下载模型」 state instead of promising a download that cannot start.
     */
    @Composable
    fun Guide(
        modifier: Modifier = Modifier,
        modelStep: OnboardingModelStep = OnboardingModelStep.Unavailable,
        onDownloadModel: () -> Unit = ::skipModel
    ) {
        // Observed, not read from `.value`: the strip must repaint on every real owner event.
        val install = containerInstall?.state?.collectAsStateWithLifecycle()?.value
        OnboardingFlowHost(
            state = flow,
            onStart = ::start,
            onSelectPath = ::selectPath,
            onContinuePath = ::continuePath,
            // With a resolvable recommendation the primary action starts the real download; without
            // one it must record "no model chosen" instead of pretending, which is exactly what
            // skipModel already does.
            onDownloadRecommended = if (modelStep.available) onDownloadModel else ::skipModel,
            onSkipModel = ::skipModel,
            onBack = ::back,
            onEnter = ::enter,
            modelStep = modelStep,
            installState = install,
            onRetryInstall = ::retryInstall,
            onCancelInstall = ::cancelInstall,
            modifier = modifier
        )
    }
}

/** Log tag for the appearance preference; one place so logcat filtering is stable. */
private const val APPEARANCE_LOG_TAG = "MCA-Appearance"

/**
 * Builds [FirstRunState] once per process.
 *
 * `applicationContext` on purpose: the state outlives any single composition (the activity is
 * recreated on rotation) and needs no window, so holding the activity would leak it for the process
 * lifetime. `remember(appContext)` keys on context identity, which is stable per process, so the
 * SharedPreferences reads behind these owners happen once rather than on every chat repaint.
 */
@Composable
internal fun rememberFirstRunState(): FirstRunState {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        FirstRunState(
            appearance = AppearanceController(
                AppearancePreferences(SharedPreferencesAppearanceStore(appContext))
            ),
            onboarding = OnboardingFlowController(
                OnboardingPreferences(SharedPreferencesOnboardingStore(appContext))
            ),
            // The one place in :app that names the implementation module; everything else sees the
            // narrow contract. Built from applicationContext, so it cannot leak the Activity.
            containerInstall = DshContainerInstallAdapter(appContext)
        )
    }
}

/**
 * The resolved dark flag for the current frame, observed.
 *
 * Collecting here rather than reading `StateFlow.value` is what makes a colour-mode change repaint
 * the app: `McaTheme` is above the settings surface, so the switch has to recompose the root, and
 * only a collected state does that.
 */
@Composable
internal fun FirstRunState.darkThemeForFrame(): Boolean {
    val mode by themeModeFlow.collectAsStateWithLifecycle()
    return darkTheme(isSystemInDarkTheme(), mode)
}

/** The appearance rows' selected value, observed for the same reason as [darkThemeForFrame]. */
@Composable
internal fun FirstRunState.appearanceChoiceForFrame(): McaAppearanceThemeChoice {
    val mode by themeModeFlow.collectAsStateWithLifecycle()
    return mode.toMcaChoice()
}

/**
 * The first-frame shell: colour mode, the first-run gate and the app-wide consent dialog in one node.
 *
 * `MainActivity` only supplies "the app body". Everything that has to wrap *both* the guide and the app
 * lives here, because three facts are only true if one node owns them:
 *  - `McaTheme` sits above the gate, so the guide and the app can never disagree about the scheme in the
 *    frame that swaps them, and the system-bar owner stays single;
 *  - the gate is one `if`, not a convention — the app body is reachable only through [content], which is
 *    invoked in the completed branch alone, so no caller can render the shell under the guide;
 *  - the DSH capability-consent dialog is app-wide and must still be reachable while the guide is
 *    showing: a privileged request arriving during onboarding must not be silently dropped.
 *
 * Both first-frame decisions are read synchronously through [state], so a returning user never sees one
 * onboarding frame and a dark-mode user never sees one light frame.
 *
 * [modifier] applies to the guide only: the app body supplies its own layout, because this shell must not
 * pad or size a subtree it does not own.
 *
 * @param content the app body, invoked only once the flow is completed. Its three arguments are the
 *   observed appearance choice, the writer for it, and the "show the guide again" action — handed over
 *   instead of the whole [FirstRunState] so the shell keeps the single writer.
 * @param modelStep the model step's real download state, forwarded to [FirstRunState.Guide]. It is a
 *   plain value so the shell never needs `MainUiState`; the default degrades the page honestly.
 * @param onDownloadModel the app's real download entry point for the recommended model.
 */
@Composable
internal fun FirstRunContent(
    modifier: Modifier = Modifier,
    state: FirstRunState = rememberFirstRunState(),
    modelStep: OnboardingModelStep = OnboardingModelStep.Unavailable,
    onDownloadModel: () -> Unit = {},
    content: @Composable (McaAppearanceThemeChoice, (McaAppearanceThemeChoice) -> Unit, () -> Unit) -> Unit
) {
    McaTheme(darkTheme = state.darkThemeForFrame()) {
        McaFeedbackLayer {
            if (!state.flow.completed) {
                state.Guide(modifier, modelStep = modelStep, onDownloadModel = onDownloadModel)
            } else {
                content(state.appearanceChoiceForFrame(), state::selectAppearance, state::replay)
            }
            // D16: the one host for DSH capability consent. It renders nothing until a
            // privileged/consent-gated request arrives and never pulls itself to front.
            com.muyuchat.mca.composition.DshCapabilityConsentDialog()
        }
    }
}

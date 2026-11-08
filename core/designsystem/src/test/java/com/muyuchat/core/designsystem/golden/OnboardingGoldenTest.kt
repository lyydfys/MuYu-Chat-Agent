package com.muyuchat.core.designsystem.golden

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muyuchat.core.designsystem.onboarding.McaOnboardingDonePage
import com.muyuchat.core.designsystem.onboarding.McaOnboardingModelPage
import com.muyuchat.core.designsystem.onboarding.McaOnboardingPathChoice
import com.muyuchat.core.designsystem.onboarding.McaOnboardingPathPage
import com.muyuchat.core.designsystem.onboarding.McaOnboardingWelcomePage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual baselines for the four first-run pages, each in the light and the dark scheme.
 *
 * Every page is stateless and takes its callbacks as parameters, so a baseline drives each state
 * directly: no Activity, no preference store and no running engine is involved.
 *
 * Recording and reviewing these images is a deliberate, human step. Read
 * `GOLDEN.md` in this directory before updating any of them.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
public class OnboardingGoldenTest {

    @Test
    public fun `引导欢迎页在浅色主题下的外观`() {
        golden(name = "onboarding-welcome-light", dark = false) {
            McaOnboardingWelcomePage(onStart = {})
        }
    }

    @Test
    public fun `引导欢迎页在深色主题下的外观`() {
        golden(name = "onboarding-welcome-dark", dark = true) {
            McaOnboardingWelcomePage(onStart = {})
        }
    }

    /**
     * The path page is captured with the recommended row selected: an unanswered page renders the
     * primary action disabled, and the selected row carries the second, non-colour signal
     * (the trailing check glyph) that this baseline exists to protect.
     */
    @Test
    public fun `引导运行方式页在浅色主题下选中推荐项的外观`() {
        golden(name = "onboarding-path-light", dark = false) {
            McaOnboardingPathPage(
                selected = McaOnboardingPathChoice.Recommended,
                onSelect = {},
                onContinue = {},
                onBack = {}
            )
        }
    }

    @Test
    public fun `引导运行方式页在深色主题下选中推荐项的外观`() {
        golden(name = "onboarding-path-dark", dark = true) {
            McaOnboardingPathPage(
                selected = McaOnboardingPathChoice.Recommended,
                onSelect = {},
                onContinue = {},
                onBack = {}
            )
        }
    }

    @Test
    public fun `引导模型页在浅色主题下的外观`() {
        golden(name = "onboarding-model-light", dark = false) {
            McaOnboardingModelPage(
                onDownloadRecommended = {},
                onSkip = {},
                onBack = {}
            )
        }
    }

    @Test
    public fun `引导模型页在深色主题下的外观`() {
        golden(name = "onboarding-model-dark", dark = true) {
            McaOnboardingModelPage(
                onDownloadRecommended = {},
                onSkip = {},
                onBack = {}
            )
        }
    }

    @Test
    public fun `引导完成页在浅色主题下的外观`() {
        golden(name = "onboarding-done-light", dark = false) {
            McaOnboardingDonePage(onEnter = {})
        }
    }

    @Test
    public fun `引导完成页在深色主题下的外观`() {
        golden(name = "onboarding-done-dark", dark = true) {
            McaOnboardingDonePage(onEnter = {})
        }
    }
}

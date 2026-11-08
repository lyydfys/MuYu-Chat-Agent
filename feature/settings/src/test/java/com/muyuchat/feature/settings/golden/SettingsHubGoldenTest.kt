package com.muyuchat.feature.settings.golden

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muyuchat.feature.settings.SettingsHubScreen
import com.muyuchat.feature.settings.SettingsSearchPage
import com.muyuchat.feature.settings.SettingsUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual baselines for the settings hub and its search page, each in the light and the dark scheme.
 *
 * The hub is captured from a **default-constructed [SettingsUiState]**: every field of that state has
 * a default, so no ViewModel, store or running engine is involved, and the captured screen is the
 * same one a fresh install shows. The fourteen callbacks this screen requires without a default are
 * passed as no-ops; they are never invoked by a capture, and passing real ones would pull in wiring
 * this module does not own.
 *
 * Recording and reviewing these images is a deliberate human step: see `GOLDEN.md` in this directory.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
public class SettingsHubGoldenTest {

    /**
     * The dashboard itself: top bar, hero, the search entry, the "配置中心 / 7 个分区" row and the
     * seven section cards. This is the surface the original P2 ask named as "设置首页".
     */
    @Test
    public fun `设置首页在浅色主题下的外观`() {
        settingsGolden(name = "settings-hub-light", dark = false) {
            SettingsHubScreen(
                state = SettingsUiState(),
                onRefreshLogs = {},
                onRefreshDiagnostics = {},
                onExportDiagnostics = {},
                onClearChatHistory = {},
                onClearImageLibrary = {},
                onClearFileLibrary = {},
                onPersistentPrefixCacheEnabledChanged = { _ -> },
                onClearPersistentPrefixCache = {},
                onSaveWebSearchSettings = { _ -> },
                onPreflightWebSearch = { _ -> },
                onTestWebSearch = { _, _ -> },
                onTestWebSearchTurn = { _, _, _ -> },
                onClearWebSearchDiagnostics = {},
                onBack = {}
            )
        }
    }

    @Test
    public fun `设置首页在深色主题下的外观`() {
        settingsGolden(name = "settings-hub-dark", dark = true) {
            SettingsHubScreen(
                state = SettingsUiState(),
                onRefreshLogs = {},
                onRefreshDiagnostics = {},
                onExportDiagnostics = {},
                onClearChatHistory = {},
                onClearImageLibrary = {},
                onClearFileLibrary = {},
                onPersistentPrefixCacheEnabledChanged = { _ -> },
                onClearPersistentPrefixCache = {},
                onSaveWebSearchSettings = { _ -> },
                onPreflightWebSearch = { _ -> },
                onTestWebSearch = { _, _ -> },
                onTestWebSearchTurn = { _, _, _ -> },
                onClearWebSearchDiagnostics = {},
                onBack = {}
            )
        }
    }

    /**
     * The search page with a query typed in.
     *
     * Why this state and not the appearance sub-page: the hub keeps `searchOpen` and the open
     * `SettingsSection` in internal `rememberSaveable` state, so neither is reachable from outside the
     * composable -- the one externally drivable non-default state on [SettingsHubScreen] is
     * `startInWebSearch`. The search *page* is reachable directly instead, because
     * [SettingsSearchPage] is `internal` to this module and this test lives in the module's own test
     * source set. That gives a real, drivable "search expanded" capture.
     *
     * The appearance surface was deliberately not chosen for these two slots: it is already covered
     * by four design-system baselines (`appearance-section-*` in `:core:designsystem`), so capturing
     * it again would add a near-duplicate rather than new coverage.
     *
     * The query `检索` is one of the placeholder's suggested keywords and matches several rows across
     * two sections, so the captured page exercises the grouped-results layout rather than an empty
     * state.
     */
    @Test
    public fun `设置搜索页在浅色主题下展开的外观`() {
        settingsGolden(name = "settings-search-light", dark = false) {
            SettingsSearchPage(
                query = "检索",
                onQueryChange = {},
                onClose = {},
                onOpenSection = { _ -> }
            )
        }
    }

    @Test
    public fun `设置搜索页在深色主题下展开的外观`() {
        settingsGolden(name = "settings-search-dark", dark = true) {
            SettingsSearchPage(
                query = "检索",
                onQueryChange = {},
                onClose = {},
                onOpenSection = { _ -> }
            )
        }
    }
}

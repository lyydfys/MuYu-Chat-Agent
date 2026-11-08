package com.muyuchat.feature.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.muyuchat.feature.settings.search.SettingsSearchEntries
import kotlinx.coroutines.delay

/**
 * Settings **row-level** reveal: the missing half of the search jump.
 *
 * Settings search used to end at "open the owning section", because the index carries a stable
 * `spotId` per row but no page knew where that row was. This contract closes the gap: the caller
 * requests a `spotId`, the owning page scrolls to that row, and the row draws a brief ring around
 * itself so the eye can land on it.
 *
 * Contract, in three parts:
 *
 * 1. [SettingsRevealState] is the request channel. It is created by the settings host, not by a page,
 *    because the request arrives from the search page and must survive the page transition. It holds
 *    at most one request: revealing a second row supersedes the first, which is what a user pressing
 *    a second search result expects.
 * 2. A page declares its scroll order in [SettingsRevealOrder] and feeds it to [RevealScrollEffect].
 *    The order is the page's real item order (first entry is the page heading), so the effect can
 *    convert a `spotId` into a `LazyListState` item index. Pages that scroll instead of lazy-listing
 *    (About) do not need an order: every row is composed, so the row brings itself into view.
 * 3. A row attaches [settingsRevealSpot]. While its `spotId` is the active request the row brings
 *    itself into view and draws a fading ring, then clears the request. The ring is **time-boxed**
 *    ([HIGHLIGHT_MILLIS]) and always clears, so no row stays highlighted after the reveal.
 *
 * Accessibility: colour alone would be the only signal for sighted users and none at all for a
 * screen reader, so the host also renders [SettingsRevealBanner] — a short text line naming the
 * located row, marked as a polite live region so TalkBack announces it. Both the ring and the banner
 * disappear together; nothing persistent is left behind.
 *
 * **Not every `spotId` can be revealed yet.** See [SettingsRevealDegradation]: every row that cannot
 * be located this round is listed there with its reason, and `SettingsRevealContractTest` fails if an
 * index entry is neither revealable nor explicitly degraded — a new row cannot silently fall through.
 */
internal object SettingsReveal {

    /** How long a located row stays marked before the request clears itself. */
    const val HIGHLIGHT_MILLIS: Long = 1800L
}

/**
 * One reveal request. [token] distinguishes two consecutive requests for the same row: without it,
 * searching the same setting twice would not re-fire the effect because the state value is equal.
 */
internal data class SettingsRevealRequest(
    val spotId: String,
    val token: Long
)

/**
 * The single request channel between the search page and the settings pages.
 *
 * State is page-scoped (owned by the settings host via `rememberSettingsRevealState`), never global:
 * it must not outlive the settings surface, and it must not become a second writer of any setting.
 */
@Stable
internal class SettingsRevealState internal constructor() {

    var request: SettingsRevealRequest? by mutableStateOf(null)
        private set

    private var lastToken: Long = 0L

    /** Ask the owning page to scroll to [spotId] and mark it briefly. */
    fun revealTo(spotId: String) {
        lastToken += 1L
        request = SettingsRevealRequest(spotId = spotId, token = lastToken)
    }

    /** Drop the current request; used when the user navigates away. */
    fun clear() {
        request = null
    }

    /**
     * End the request that produced [token], but only if it is still the active one. A row that
     * finishes its own highlight must not cancel a newer request for a different row.
     */
    internal fun consume(spotId: String, token: Long) {
        val active = request ?: return
        if (active.spotId == spotId && active.token == token) request = null
    }
}

/** Creates the reveal channel owned by the settings host. */
@Composable
internal fun rememberSettingsRevealState(): SettingsRevealState = remember { SettingsRevealState() }

/**
 * Item order of the pages that reveal rows, as the page actually renders them.
 *
 * The first entry is the page heading (`null`), so a list index *is* the `LazyColumn` item index and
 * [RevealScrollEffect] needs no offset arithmetic. Keep this list in sync with the page: the contract
 * test reads the page source and fails when a listed id has no `settingsRevealSpot(...)` marker.
 */
internal object SettingsRevealOrder {

    /**
     * `SettingsAboutScreen` rows in render order. The page scrolls with `verticalScroll` instead of a
     * lazy list, so every row is composed and brings itself into view: the order is not used for index
     * math, it is the audited list of what the page can reveal.
     */
    val about: List<String> = listOf(
        "about.check_update",
        "about.update_history",
        "about.author",
        "about.repository",
        "about.sponsor",
        "about.feedback",
        "about.version_info"
    )

    /** `RuntimeScreen` items: heading, then the seven runtime cards. */
    val runtime: List<String?> = listOf(
        null, // "运行状态"
        "runtime.offline_translation",
        "runtime.app_update",
        "runtime.model",
        "runtime.performance",
        "runtime.memory",
        "runtime.load",
        "runtime.diagnostic"
    )

    /** The item index of [spotId] in [order], or null when the page does not own that row. */
    fun itemIndexOf(order: List<String?>, spotId: String): Int? =
        order.indexOf(spotId).takeIf { it >= 0 }
}

/**
 * Rows that currently resolve to their **section page only** (no scroll, no ring).
 *
 * Every entry is a real index `spotId` that this round deliberately does not reach, with the concrete
 * reason. This table exists so the gap is auditable instead of implicit: the contract test requires
 * each index entry to be either revealable or listed here, so adding an index row without a reveal
 * path is a test failure, not a silent downgrade.
 */
internal val SettingsRevealDegradation: Map<String, String> = mapOf(
    "runtime.app_update" to "更新卡是整块卡片，行内没有独立定位点；D2 已给它加了「完整设置见 关于」跳转，搜索命中它时落到 RUNTIME 页即可用",
    // -- SEARCH: the page's own rows sit inside collapsed expanders.
    "search.work_mode" to "工作方式卡是只读说明，不是可定位的行",
    "search.usage_help" to "使用说明是可展开区块，折叠时行未组合，无法滚动定位",
    "search.enable" to "启用开关在页首固定段，没有独立定位点",
    "search.advanced" to "高级设置是可展开容器，需先展开才能定位子行",
    "search.trigger_mode" to "位于「高级设置」展开器内，折叠时不组合",
    "search.research_mode" to "位于「高级设置」展开器内，折叠时不组合",
    "search.endpoint" to "位于「高级设置」展开器内，折叠时不组合",
    "search.api_key" to "位于「高级设置」展开器内，折叠时不组合",
    "search.backup_providers" to "备用搜索源是可展开容器，折叠时不组合",
    "search.max_results" to "页内固定段，没有独立定位点",
    "search.fetch_page" to "页内固定段，没有独立定位点",
    "search.testing" to "测试区是可展开容器，折叠时不组合",
    "search.recent" to "最近检索是可展开容器，折叠时不组合",
    // -- PRIVACY: the two DSH capability rows are generated inside their cards.
    "privacy.memory" to "记忆管理占位卡无点击目标，尚未接入 reveal",
    "privacy.local_data" to "页内固定卡，没有独立定位点",
    "privacy.file_index" to "页内固定卡，没有独立定位点",
    "privacy.prefix_cache" to "页内固定卡，没有独立定位点",
    "privacy.cleanup" to "页内固定卡，没有独立定位点",
    "privacy.device_access" to "传感器行由 8 个开关循环生成，无稳定行边界",
    "privacy.sms" to "短信区行由权限状态动态生成，无稳定行边界",
    // -- LOGS: the item set is runtime-dependent, so a static item index does not exist.
    "logs.diagnostic_report" to "该行只在有诊断报告时才插入 LazyColumn，item 下标不稳定，无法按下标滚动",
    "logs.runtime_records" to "运行记录区仅在日志非空时插入，item 下标随运行态变化",
    "logs.agent_records" to "调参记录区仅在非空时插入，item 下标随运行态变化",
    "logs.backup" to "备份卡之前有若干条件 item，静态下标不成立",
    // -- APPEARANCE: the control lives in :core:designsystem, which this task must not modify.
    "appearance.theme.system" to "外观行由 :core:designsystem 的共享控件渲染，本模块不修改该控件",
    "appearance.theme.light" to "外观行由 :core:designsystem 的共享控件渲染，本模块不修改该控件",
    "appearance.theme.dark" to "外观行由 :core:designsystem 的共享控件渲染，本模块不修改该控件",
    // -- EXPERIMENTS: cards exist but are not wired this round.
    "experiments.image_gen" to "只读说明卡，没有独立定位点",
    "experiments.multimodal" to "只读说明卡，没有独立定位点",
    "experiments.web_search" to "只读说明卡，没有独立定位点",
    "experiments.local_tools" to "页内固定卡，没有独立定位点",
    "experiments.rich_blocks" to "页内固定卡，没有独立定位点",
    "experiments.tools_mcp" to "只读说明卡，没有独立定位点",
    // -- ABOUT is wired (all seven rows); see SettingsRevealOrder.about.
)

/**
 * Scrolls [state] to the row that [SettingsRevealOrder] maps to the active request.
 *
 * Runs once per request token: `animateScrollToItem` is animated, and re-triggering it on every
 * recomposition would fight the user's own scrolling for as long as the ring is visible.
 */
@Composable
internal fun RevealScrollEffect(
    state: LazyListState,
    reveal: SettingsRevealState?,
    order: List<String?>
) {
    val request = reveal?.request ?: return
    LaunchedEffect(request.token) {
        val index = SettingsRevealOrder.itemIndexOf(order, request.spotId) ?: return@LaunchedEffect
        state.animateScrollToItem(index)
    }
}

/**
 * Marks the row that owns [spotId] while it is the active reveal target.
 *
 * While active the row brings itself into view (needed for scrolling pages such as About, and a cheap
 * correction after a lazy list lands the item at the viewport edge) and draws a ring that fades out
 * over [SettingsReveal.HIGHLIGHT_MILLIS]. When the fade finishes the request is cleared, so the row
 * returns to its untouched appearance and the next search can re-trigger it.
 *
 * The ring is drawn behind the content with `drawBehind`, so attaching this modifier cannot change a
 * single measurement, padding or colour of the frozen layout.
 */
@Composable
internal fun Modifier.settingsRevealSpot(
    spotId: String,
    reveal: SettingsRevealState?
): Modifier {
    val request = reveal?.request?.takeIf { it.spotId == spotId } ?: return this
    val alpha = remember(spotId) { Animatable(0f) }
    val bringIntoView = remember(spotId) { BringIntoViewRequester() }
    val ring = MaterialTheme.colorScheme.primary
    val radiusPx = with(LocalDensity.current) { 12.dp.toPx() }
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }

    LaunchedEffect(request.token) {
        // Scrolling pages (About) need this; a lazy list may also land the row at the viewport edge.
        bringIntoView.bringIntoView()
        alpha.snapTo(1f)
        alpha.animateTo(0f, animationSpec = tween(durationMillis = SettingsReveal.HIGHLIGHT_MILLIS.toInt()))
        reveal.consume(spotId, request.token)
    }

    return this
        .bringIntoViewRequester(bringIntoView)
        .drawBehind {
        if (alpha.value > 0f) {
            drawRoundRect(
                color = ring.copy(alpha = alpha.value),
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                style = Stroke(width = strokePx)
            )
        }
    }
}

/**
 * The accessible half of the reveal: one short line naming the located row.
 *
 * It is a polite live region, so a screen reader announces it when it appears, and it disappears with
 * the request — the highlight is not colour-only and leaves no persistent state. The row text comes
 * from the search index (never a second copy), so the banner cannot drift from what search showed.
 */
@Composable
internal fun SettingsRevealBanner(
    reveal: SettingsRevealState?,
    modifier: Modifier = Modifier
) {
    val request = reveal?.request ?: return
    val title = remember(request.spotId) {
        SettingsSearchEntries.firstOrNull { it.spotId == request.spotId }?.title
    } ?: return
    Text(
        text = "已定位到「$title」",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/** One-frame settle before bringing a row into view, so layout has measured the target row. */
internal const val REVEAL_SETTLE_MILLIS: Long = 16L

/** Suspends briefly; split out so the reveal effect stays readable. */
internal suspend fun revealSettle() = delay(REVEAL_SETTLE_MILLIS)

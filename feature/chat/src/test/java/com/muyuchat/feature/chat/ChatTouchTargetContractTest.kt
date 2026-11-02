package com.muyuchat.feature.chat

import com.muyuchat.core.designsystem.McaActionLabels
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prevents small decorative glyphs from shrinking the actual chat interaction bounds, pins the
 * registered touch-target exceptions (EX-01/02/03), and keeps accessible names on the control that
 * is actually clickable.
 */
class ChatTouchTargetContractTest {
    @Test
    fun `primary chat actions use forty eight dp touch targets`() {
        val status = source("ChatStatusBarHeader.kt")
        assertTrue(status.contains("Modifier.size(48.dp)"))
        assertTrue(status.contains("compactForLargeText"))
        assertTrue(status.contains("contentDescription = \"打开应用菜单\""))
        assertFalse(status.contains("Modifier.size(38.dp)"))

        // The three small icon actions must share the press-scale + bounded-ripple primitive
        // instead of mixing native IconButton, a bare clickable and a text glyph.
        assertTrue(status.contains("McaIconButton("))
        assertTrue(status.contains("ripple(bounded = true)"))
        assertTrue(status.contains(".mcaPressScale(modelCapsuleInteraction)"))
        assertFalse(status.contains("import androidx.compose.material3.IconButton"))
        assertFalse(status.contains("LocalIndication"))

        val composer = source("ChatComposerScreens.kt")
        assertTrue(composer.contains("RoundedCornerShape(36.dp)"))
        assertTrue(composer.contains(".size(40.dp)"))
        assertTrue(composer.contains("if (!sendFabEnabled) disabled()"))

        // User message actions use McaIconButton's shared 48dp default target; that default is
        // pinned by McaIconButtonContractTest after the call-site migration.
        assertTrue(source("ChatMessageBubbleScreens.kt").contains("McaIconButton("))
        assertTrue(source("ChatAssistantActionViews.kt").contains("McaIconButton("))
        assertTrue(source("ChatHistoryDrawerRows.kt").contains("Modifier.size(48.dp)"))
        // The visual plates need a 32dp content slot inside the independent 48dp target. A
        // default 20dp glyph slot would silently clamp their requested plate size.
        assertEquals(
            3,
            Regex("glyphSize = McaPlateSize\\.Medium")
                .findAll(source("ChatAssistantActionViews.kt")).count()
        )
        assertEquals(
            2,
            Regex("glyphSize = McaPlateSize\\.Medium")
                .findAll(source("ChatMessageBubbleScreens.kt")).count()
        )
    }

    @Test
    fun `visible composer controls keep their previous compact geometry`() {
        val composer = source("ChatComposerScreens.kt")
        assertTrue(composer.contains("if (!sendFabEnabled) disabled()"))
        assertTrue(composer.contains("else -> McaActionLabels.SEND"))
        assertTrue(composer.contains("containerColor = McaPrimaryBlue"))
    }

    /**
     * R08：the three controls that had a bare clickable gain a named, press-shaped interaction
     * without moving their visible geometry. The exact dp values live in
     * `ChatComposerMenuMetricsTest`; this guards the wiring itself.
     */
    @Test
    fun `r08 small controls gain names and press feedback without moving`() {
        // Research capsule: the 16dp glyph keeps its slot and the target is the larger overlay.
        val capsule = source("ChatComposerResearchModeViews.kt")
        assertTrue(capsule.contains("ComposerMenuMetrics.CAPSULE_CLOSE_GLYPH_DP.dp"))
        assertTrue(capsule.contains("ComposerMenuMetrics.CAPSULE_CLOSE_TARGET_DP.dp"))
        assertTrue(capsule.contains("contentDescription = \"\${McaActionLabels.CLOSE}研究模式选择\""))

        // Composer "more": visible 40dp circle unchanged, shared press scale + role + action label.
        val composer = source("ChatComposerScreens.kt")
        assertTrue(composer.contains("ComposerMenuMetrics.COMPOSER_MORE_VISIBLE_DP.dp"))
        assertTrue(composer.contains(".mcaPressScale(moreInteraction"))
        assertTrue(composer.contains("ripple(bounded = true)"))
        assertTrue(composer.contains("onClickLabel = McaActionLabels.OPEN_MORE"))

        // User message actions: the pin carries its state-dependent name on the 48dp button, and
        // the copy confirmation is the same in-place check as the assistant actions (no Toast).
        val bubble = source("ChatMessageBubbleScreens.kt")
        assertTrue(bubble.contains("\"取消固定上下文\""))
        assertTrue(bubble.contains("\"固定到上下文\""))
        assertTrue(bubble.contains("contentDescription = if (copied) \"已复制\" else \"复制提示词\""))
        assertFalse("user copy must not fall back to a Toast", bubble.contains("Toast.makeText"))
    }

    /**
     * Classification of the two unnamed slivers from the menu-open a11y dump
     * (`artifacts/a11y/20261020-122456-38916/ui-dump.xml`): 21x132 at [56,2440][77,2572] and
     * 10x132 at [2059,2600][2069,2732].
     *
     * Both are direct children of the assistant message card `[50,2165][2075,2754]` (the composer
     * is `[44,2832][2092,3128]`), and each owns a child button with full bounds 233x110 / 132x132.
     * The slivers are what remains of the context-source chip and the overflow button when the open
     * composer menu clips the card: Android keeps the partially visible clickable but prunes its
     * label child, so the naming audit reported an unnamed strip.
     *
     * They are not separate controls and no global a11y exclusion was added for them. The fix is to
     * keep the name on the clickable itself, which is what the assistant action row now does.
     * The context-source chip lives in `ChatBubbleMessageBlocks.kt` (owned by another workspace);
     * its visible text already names it when not clipped.
     */
    @Test
    fun `clipped message-action slivers are named buttons not separate controls`() {
        val actions = source("ChatAssistantActionViews.kt")
        assertTrue(actions.contains("contentDescription = \"更多消息操作\""))

        // The left sliver is the context-source TextButton; it is named by its visible text.
        assertTrue(source("ChatBubbleMessageBlocks.kt").contains("Text(\"上下文来源\""))

        // No hard-coded exclusion may hide real controls from accessibility instead of naming them.
        for (file in listOf("ChatAssistantActionViews.kt", "ChatMessageBubbleScreens.kt", "ChatComposerScreens.kt")) {
            val text = source(file)
            assertFalse("$file must not hide controls with invisibleToUser", text.contains("invisibleToUser"))
            assertFalse("$file must not erase controls with clearAndSetSemantics", text.contains("clearAndSetSemantics"))
        }
    }

    /**
     * Renders the names on the button node itself, not only on the icon child. When the label child
     * is pruned (clipped by an open overlay), the clickable node must still be named; the icon is
     * decorative so the name is not announced twice.
     */
    @Test
    fun `assistant message actions own their names on the clickable`() {
        val actions = source("ChatAssistantActionViews.kt")
        assertTrue(actions.contains("contentDescription = McaActionLabels.REGENERATE"))
        assertTrue(
            actions.contains("contentDescription = if (copied) \"已复制\" else \"\${McaActionLabels.COPY}回答\"")
        )
        assertTrue(actions.contains("contentDescription = \"更多消息操作\""))

        // The same copy action must not be "复制消息" in the row and "复制回答" in the menu.
        assertFalse("the assistant copy action keeps one wording", actions.contains("复制消息"))
        assertTrue(actions.contains("Text(\"复制回答\")"))

        // Icons stay decorative: the button carries the name, so an icon description would double
        // announce and the label would vanish again whenever the icon child is pruned.
        // Icon family selection now belongs to McaIcons; retain the same semantic glyph and
        // decorative-child assertion instead of pinning the old direct Material import.
        assertTrue(
            Regex("""McaIcons\.Replay,\s*contentDescription = null""").containsMatchIn(actions)
        )
        assertTrue(
            Regex("""McaIcons\.MoreVert,\s*contentDescription = null""").containsMatchIn(actions)
        )
        assertTrue(
            Regex("""McaIcons\.(?:Check|ContentCopy),\s*contentDescription = null""")
                .containsMatchIn(actions)
        )
    }

    /**
     * EX-01 and EX-02 are registered exceptions, not oversights. Their measured targets stay where
     * the frozen 1x geometry puts them, and no layout-expanding modifier may silently grow them:
     *
     * - EX-01 composer "more" is the visible 40dp circle. A 48dp target (layout expansion) inside
     *   the frozen 40dp composer row would add 8dp to the whole input shell.
     * - EX-02 research-capsule close is 36dp at 1x and follows the measured header; the first mode
     *   pill starts 39dp below the panel top, so a fixed 48dp target would cover the top 9dp of a
     *   neighbouring selectable pill. At 2x the header drives the target past 48dp.
     */
    @Test
    fun `ex-01 and ex-02 stay measured exceptions that cannot silently grow`() {
        assertEquals(40, ComposerMenuMetrics.COMPOSER_MORE_VISIBLE_DP)
        assertTrue(
            "EX-01 is a registered exception; see ComposerMenuMetrics for the frozen-row reason",
            ComposerMenuMetrics.COMPOSER_MORE_VISIBLE_DP < 48
        )
        val composer = source("ChatComposerScreens.kt")
        assertTrue(composer.contains(".size(ComposerMenuMetrics.COMPOSER_MORE_VISIBLE_DP.dp)"))
        assertFalse(
            "a layout-expanding modifier would move the frozen composer row",
            composer.contains("minimumInteractiveComponentSize(")
        )

        assertEquals(36, ComposerMenuMetrics.CAPSULE_CLOSE_TARGET_DP)
        assertTrue(
            "EX-02 is a registered exception; the first pill bounds the target",
            ComposerMenuMetrics.CAPSULE_CLOSE_TARGET_DP < 48
        )
        val firstPillTopDp = ComposerMenuMetrics.CAPSULE_PADDING_DP +
            ComposerMenuMetrics.CAPSULE_HEADER_VERTICAL_PADDING_DP * 2 +
            ComposerMenuMetrics.CAPSULE_CLOSE_GLYPH_DP +
            ComposerMenuMetrics.CAPSULE_ITEM_SPACING_DP
        assertTrue("48dp cannot fit above the first pill", 48 > firstPillTopDp)
        assertEquals(36, researchCloseTargetHeightDp(20))
        assertTrue(
            "at 2x the header target must exceed the 1x baseline",
            researchCloseTargetHeightDp(48) >= 48
        )
        val capsule = source("ChatComposerResearchModeViews.kt")
        assertTrue(capsule.contains(".width(ComposerMenuMetrics.CAPSULE_CLOSE_TARGET_DP.dp)"))
        assertTrue(capsule.contains(".height(closeTargetHeightDp.dp)"))
        assertFalse(capsule.contains("minimumInteractiveComponentSize("))
    }

    /**
     * EX-03 (code-block copy) is registered around a 30dp minimum height that grows with the font
     * scale. The renderer is owned by the markdown workspace; this pins the registered value and
     * its accessible name so a "fix" cannot silently drop either.
     */
    @Test
    fun `ex-03 code copy keeps its registered minimum and name`() {
        val renderer = source("ChatMarkdownBlockRenderers.kt")
        assertTrue(renderer.contains("modifier = Modifier.heightIn(min = 30.dp)"))
        assertTrue(renderer.contains("contentDescription = if (copied) \"已复制代码\" else \"复制代码\""))
        assertTrue(renderer.contains("\"已复制\" else \"复制\""))
    }

    @Test
    fun `primary and header controls expose action labels and disabled state`() {
        val composer = source("ChatComposerScreens.kt")
        assertTrue(composer.contains("ComposerPrimaryAction.Stop -> McaActionLabels.STOP"))
        assertTrue(composer.contains("else -> McaActionLabels.SEND"))
        assertTrue(composer.contains("if (!sendFabEnabled) disabled()"))
        // The transient preflight ring has no glyph name, so the FAB names that state explicitly.
        assertTrue(composer.contains("contentDescription = \"正在检查附件\""))
        // The visible "more" affordance is a bare clickable, so its action label is explicit.
        assertTrue(composer.contains("onClickLabel = McaActionLabels.OPEN_MORE"))

        val status = source("ChatStatusBarHeader.kt")
        assertTrue(status.contains("onClickLabel = \"切换模型\""))
        assertTrue(status.contains("onClickLabel = \"打开历史\""))
        assertTrue(status.contains("onClickLabel = \"新建聊天\""))
        assertTrue(status.contains("onClickLabel = \"打开应用菜单\""))

        val capsule = source("ChatComposerResearchModeViews.kt")
        assertTrue(capsule.contains("onClickLabel = \"\${McaActionLabels.CLOSE}研究模式选择\""))
        assertTrue(capsule.contains("enabled = acceptsActions"))
    }

    /** One verb per action across chat and the shared design system, not drifting literals. */
    @Test
    fun `shared accessibility vocabulary keeps one verb per action`() {
        assertEquals("关闭", McaActionLabels.CLOSE)
        assertEquals("取消", McaActionLabels.CANCEL)
        assertEquals("重试", McaActionLabels.RETRY)
        assertEquals("复制", McaActionLabels.COPY)
        assertEquals("重新生成", McaActionLabels.REGENERATE)
        assertEquals("发送", McaActionLabels.SEND)
        assertEquals("停止", McaActionLabels.STOP)
        assertEquals("打开更多操作", McaActionLabels.OPEN_MORE)

        // The controlled surfaces consume the shared verbs instead of re-typing them.
        assertTrue(source("ChatComposerScreens.kt").contains("import com.muyuchat.core.designsystem.McaActionLabels"))
        assertTrue(source("ChatAssistantActionViews.kt").contains("McaActionLabels.REGENERATE"))
        assertTrue(source("ChatMessageBubbleScreens.kt").contains("contentDescription = if (copied) \"已复制\" else \"复制提示词\""))
        assertTrue(source("ChatStatusBarHeader.kt").contains("McaIconButton("))
    }

    private fun source(name: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(
                directory,
                "feature/chat/src/main/java/com/muyuchat/feature/chat/$name"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $name")
    }
}

package com.muyuchat.core.designsystem.onboarding

import com.muyuchat.core.designsystem.designSystemSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首启引导的无障碍契约。
 *
 * `:core:designsystem` 的测试依赖只有 JUnit 与 kotlinx-coroutines-test，没有 compose-ui-test-junit4，
 * 也没有 Robolectric，因此无法用 `createComposeRule()` 断言语义树。这里的取舍是：
 *
 * - 能用纯 JVM 判断的（枚举契约、文案、`fromWire` 回退）直接断言真实类型；
 * - 必须读 Composable 内部才能成立的（滚动正文与固定底栏、选中态的第二信号、单选语义、48dp）
 *   按 [McaOnboardingCopyTest] 之外已有的源码金样先例，读 `McaOnboardingPages.kt` 原文断言。
 *   源码级断言只钉住「用户可见行为所依赖的结构」，不钉缩进、不钉行号。
 *
 * 失败信息一律点名到具体步骤或选项，便于定位是哪一页、哪一行坏了。
 */
class McaOnboardingAccessibilityContractTest {

    @Test
    fun `每个引导步骤都有非空标题与副标题`() {
        // 屏幕上每个页面只有「标题 + 副标题」这两句解释；进度行还会把标题再播报一次。
        // 任意一处变空，用户就被要求做一个没有上下文的决定。
        McaOnboardingStep.entries.forEach { step ->
            assertTrue("$step 缺少标题，页面顶部会是一块空白", step.title.isNotBlank())
            assertTrue("$step 缺少副标题，用户看不到这一步要他做什么", step.subtitle.isNotBlank())
            assertEquals("$step 的标题带首尾空白，会渲染成错位的文字", step.title, step.title.trim())
            assertEquals("$step 的副标题带首尾空白，会渲染成错位的文字", step.subtitle, step.subtitle.trim())
        }
    }

    @Test
    fun `引导步骤顺序即用户可见顺序`() {
        // 顺序是契约而不是实现细节：进度行按 `step.ordinal` 画点，`semanticsStepProgress` 又用同一个
        // ordinal 播报「第 N 步，共 M 步」。调换两个常量会让播报的步号和真实页面错位，盲用户会先听到
        // 「第 3 步」却停在第 1 页。
        assertEquals(
            "引导页序被改动：进度点与「第 N 步」播报都会和真实页面错位",
            listOf(
                McaOnboardingStep.WELCOME,
                McaOnboardingStep.PATH,
                McaOnboardingStep.MODEL,
                McaOnboardingStep.DONE
            ),
            McaOnboardingStep.entries.toList()
        )
        McaOnboardingStep.entries.forEachIndexed { index, step ->
            assertEquals("$step 的序号必须等于它的声明位置，否则进度播报的步数会错", index, step.ordinal)
        }
    }

    @Test
    fun `路径选项的 wireName 唯一且稳定`() {
        val names = McaOnboardingPathChoice.entries.map { it.wireName }
        assertEquals("两个路径选项共用了同一个 wireName，持久化后无法区分用户选了什么", names.size, names.toSet().size)
        // wireName 是落盘与 :app 侧映射的稳定键。改名不是文案修改，而是让老用户的选择静默失效。
        assertEquals(
            "路径 wireName 是持久化契约，改名会让已保存的运行方式选择失效",
            setOf("experience", "production"),
            names.toSet()
        )
    }

    @Test
    fun `路径选项都有非空标签与说明`() {
        McaOnboardingPathChoice.entries.forEach { choice ->
            assertTrue("路径 ${choice.wireName} 没有标签，选项行只剩说明文字", choice.label.isNotBlank())
            // 说明文字是这一页唯一解释「装不装运行环境」的地方：删掉它用户只能靠猜。
            assertTrue("路径 ${choice.wireName} 没有说明，用户无法判断要不要装运行环境", choice.summary.isNotBlank())
        }
    }

    @Test
    fun `未知 wireName 不会崩溃并返回空`() {
        // 引导页不允许替用户预选路径：返回值 null 才让「继续」保持禁用，逼用户真的读一遍。
        assertNull("未知 wireName 必须返回 null，不能替用户挑一条路径", McaOnboardingPathChoice.fromWire("bogus"))
        assertNull("旧版本或云端路径不能被当成有效选择", McaOnboardingPathChoice.fromWire("cloud"))
        assertNull("空值必须返回 null，而不是默认第一条", McaOnboardingPathChoice.fromWire(null))
    }

    @Test
    fun `大小写与空白不敏感`() {
        assertEquals(
            "带首尾空格的大写 wireName 必须命中对应选项",
            McaOnboardingPathChoice.EXPERIENCE,
            McaOnboardingPathChoice.fromWire(" EXPERIENCE ")
        )
        assertEquals(
            "混合大小写必须命中对应选项",
            McaOnboardingPathChoice.PRODUCTION,
            McaOnboardingPathChoice.fromWire("Production")
        )
        assertEquals(
            "制表符与换行包围的值必须被裁掉",
            McaOnboardingPathChoice.PRODUCTION,
            McaOnboardingPathChoice.fromWire("\tProduction\n")
        )
        McaOnboardingPathChoice.entries.forEach { choice ->
            assertEquals("${choice.wireName} 必须能按原样回读", choice, McaOnboardingPathChoice.fromWire(choice.wireName))
        }
    }

    @Test
    fun `每个引导页面源码都声明了可滚动正文与固定操作栏`() {
        // 理由：最大字号下正文会超过小屏视口。主操作一旦跟着正文一起滚动，首启第一屏的「开始」就会
        // 被滚出屏幕，用户被卡在一个没有出口的页面上。四个页面共用一个 scaffold，所以只需检查这一处。
        val source = designSystemSource(PAGES)
        val scaffold = codeOnly(source.substringAfter("public fun McaOnboardingScaffold("))

        assertTrue("引导正文必须可滚动，否则大字号下正文会被裁掉且无法翻到", scaffold.contains("verticalScroll(rememberScrollState())"))

        val scrollStart = scaffold.indexOf("weight(1f)")
        assertTrue("找不到承载滚动正文的 weight(1f) 容器，正文与底栏的分离结构已被改动", scrollStart >= 0)
        val scrollingBody = blockAfter(scaffold, scrollStart)
        assertTrue("滚动正文里必须仍然包含步骤标题", scrollingBody.contains("text = step.title,"))
        assertTrue("滚动正文里必须仍然包含步骤副标题", scrollingBody.contains("text = step.subtitle,"))
        assertFalse(
            "主操作/次操作按钮不能放进滚动正文，否则大字号下会被滚出屏幕",
            scrollingBody.contains("McaButton(")
        )
        assertTrue(
            "主操作必须挂在 scaffold 根列（固定底栏）上，且引用调用方传入的 onClick",
            scaffold.substringAfter(scrollingBody).contains("onClick = onPrimary,")
        )

        // 四个页面都必须走同一个 scaffold；自建布局就能绕过上面的滚动/底栏结构。
        listOf(
            "McaOnboardingWelcomePage",
            "McaOnboardingPathPage",
            "McaOnboardingModelPage",
            "McaOnboardingDonePage"
        ).forEach { page ->
            val body = source.substringAfter("public fun $page(").substringBefore("@Composable")
            assertTrue("$page 必须通过 McaOnboardingScaffold 渲染，自建布局会绕过可滚动正文与固定底栏", body.contains("McaOnboardingScaffold("))
        }
    }

    @Test
    fun `引导页面不出现仅靠颜色传达状态的选择控件`() {
        // 选中态给了两个信号：容器填充色 + 尾部对勾图标；推荐项另有一枚「推荐」文字标注。
        // 只留颜色的话，色盲用户和低对比深色模式下这一页就等于没有选中反馈。
        val source = designSystemSource(PAGES)
        val row = codeOnly(source.substringAfter("private fun OnboardingPathRow("))

        val selectedStart = row.indexOf("if (selected) {")
        assertTrue("路径行必须有一个专门的 if (selected) 分支来表达选中态", selectedStart >= 0)
        val selectedBranch = blockAfter(row, selectedStart)
        assertTrue("选中的路径行必须出现对勾图标，不能只靠容器颜色区分", selectedBranch.contains("McaIcons.Check"))
        assertTrue(
            "对勾必须是装饰性的（contentDescription = null）：选中状态已由 selectable 播报，重复描述会让 TalkBack 念两遍",
            selectedBranch.contains("contentDescription = null")
        )
        assertTrue("推荐项必须有可见文字标注，而不是只在色板上与另一项区分", source.contains("text = \"推荐\","))
        assertTrue("选项行必须始终渲染文字标签，不能只画图标", source.contains("text = choice.label,"))
        assertTrue("选项行必须始终渲染说明文字，图标不能替代解释", source.contains("text = choice.summary,"))
        assertFalse(
            "路径行不能改用 clickable 冒充选择，否则丢失选中语义",
            row.contains(".clickable(")
        )
    }

    @Test
    fun `路径选项行使用单选语义且触控高度不低于 48dp`() {
        val row = designSystemSource(PAGES)
            .substringAfter("private fun OnboardingPathRow(")
            .substringBefore("public fun McaOnboardingModelPage(")

        assertTrue("路径行必须声明 RadioButton 角色，否则 TalkBack 只会念「按钮」而不知道这是一组单选", row.contains("role = Role.RadioButton,"))
        assertTrue("路径行必须上报当前选中状态，否则读屏用户听不出哪一条已选", row.contains("selected = selected,"))
        assertTrue("路径行触控高度不得低于 48dp（无障碍触控底线）", row.contains("heightIn(min = 48.dp)"))
    }

    @Test
    fun `进度行用文本播报第几步共几步而不是只靠色块`() {
        // 进度点是纯色块（Box + background），对屏幕阅读器没有任何语义；
        // 只有挂上「第 N 步，共 M 步：标题」的朗读文本，盲用户才知道流程还剩多少。
        val source = designSystemSource(PAGES)

        assertTrue("进度行必须挂上步进语义修饰符", source.contains("semanticsStepProgress(step, index, total)"))
        assertTrue("进度行必须提供 contentDescription，否则整行对 TalkBack 是空的", source.contains("contentDescription = \"第 "))
        assertTrue(
            "播报文本必须同时给出当前步、总步数与步骤标题，只念数字等于没说",
            source.contains("步，共 \$total 步：\${step.title}\"")
        )
    }

    /**
     * 去掉字符串字面量的内容，只留结构。
     *
     * 源码级断言要按大括号配对切块，而文案里可能出现 `{}`（例如步骤播报的字符串模板）；
     * 不先剥掉字符串内容，配对就会错位成一条难以理解的失败。
     */
    private fun codeOnly(source: String): String = buildString(source.length) {
        var inString = false
        var index = 0
        while (index < source.length) {
            val char = source[index]
            if (inString) {
                when (char) {
                    '\\' -> index++ // 跳过被转义的字符
                    '"' -> {
                        inString = false
                        append('"')
                    }
                    else -> Unit
                }
            } else {
                when (char) {
                    '"' -> {
                        inString = true
                        append('"')
                    }
                    else -> append(char)
                }
            }
            index++
        }
    }

    /** 返回 [start] 之后第一个 `{` 所打开代码块的正文（不含两侧大括号）。 */
    private fun blockAfter(source: String, start: Int): String {
        val open = source.indexOf('{', start)
        require(open >= 0) { "从索引 $start 起找不到代码块，无法判定结构边界" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        throw AssertionError("引导页面源码的大括号不平衡，无法判定滚动正文的范围")
    }

    private companion object {
        const val PAGES = "onboarding/McaOnboardingPages.kt"
    }
}

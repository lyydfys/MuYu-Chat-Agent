package com.muyuchat.core.designsystem.appearance

import com.muyuchat.core.designsystem.designSystemSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观设置分区的无障碍契约。
 *
 * `:core:designsystem` 的测试依赖只有 JUnit 与 kotlinx-coroutines-test，没有 compose-ui-test-junit4，
 * 也没有 Robolectric，因此不能 `createComposeRule()` 去断言语义树。取舍与
 * `McaOnboardingAccessibilityContractTest` 一致：
 *
 * - 枚举与 `fromWire` 的回退/大小写行为用真实类型断言；
 * - 「选中不只靠颜色」「单选语义」「48dp」「合并为单一节点」这类必须进 Composable 才能看见的东西，
 *   读 `McaAppearanceSection.kt` 原文做源码级契约断言（仓库里已有同类源码契约先例）。
 *
 * 这一项的特殊性：它是全应用唯一「选错就读不下去」的控件，所以两个选中信号、单选组语义和触控高度
 * 都按硬要求钉住，而不是当作可选装饰。
 */
class McaAppearanceAccessibilityContractTest {

    @Test
    fun `三个主题选项的 wireName 唯一且稳定`() {
        val names = McaAppearanceThemeChoice.entries.map { it.wireName }

        assertEquals("两个主题选项共用了同一个 wireName，持久化后无法区分", names.size, names.toSet().size)
        // wireName 是落盘键：改名不是文案修改，而是让已保存的外观偏好静默失效（用户会被重置回跟随系统）。
        assertEquals(
            "主题 wireName 是持久化契约，改名会让已保存的外观偏好失效",
            setOf("system", "light", "dark"),
            names.toSet()
        )
    }

    @Test
    fun `默认值跟随系统`() {
        // 跟随系统是「不为既有用户改变任何东西」的默认：深色模式用户在升级后不会被突然刷成浅色。
        assertEquals("默认外观必须是跟随系统", McaAppearanceThemeChoice.SYSTEM, McaAppearanceThemeChoice.Default)
        assertEquals(
            "全新安装必须落在跟随系统，而不是把用户钉死在某个色板上",
            McaAppearanceThemeChoice.SYSTEM,
            McaAppearanceThemeChoice.entries.first()
        )
    }

    @Test
    fun `未知持久值回退到跟随系统`() {
        // 回退目标是「跟随系统」而不是「浅色」：坏值绝不能让一个深色模式用户在低光下被强刷成白屏。
        listOf(null, "", "   ", "\t\n", "purple", "darkk", "SYSTEM-LIGHT", "系统").forEach { dirty ->
            assertEquals(
                "持久值 [$dirty] 不能被当成有效模式，也不能把用户钉在某个色板上",
                McaAppearanceThemeChoice.SYSTEM,
                McaAppearanceThemeChoice.fromWire(dirty)
            )
        }
    }

    @Test
    fun `大小写不敏感`() {
        McaAppearanceThemeChoice.entries.forEach { choice ->
            assertEquals("${choice.wireName} 的大写形式必须命中", choice, McaAppearanceThemeChoice.fromWire(choice.wireName.uppercase()))
            assertEquals("${choice.wireName} 带首尾空白时也必须命中", choice, McaAppearanceThemeChoice.fromWire("  ${choice.wireName}  "))
        }
        assertEquals("混合大小写必须命中", McaAppearanceThemeChoice.DARK, McaAppearanceThemeChoice.fromWire("DaRk"))
    }

    @Test
    fun `每个选项都有非空标签`() {
        McaAppearanceThemeChoice.entries.forEach { choice ->
            assertTrue("主题 ${choice.wireName} 没有标签，行内只剩一个图标，读屏时无从选择", choice.label.isNotBlank())
            assertEquals("主题 ${choice.wireName} 的标签带首尾空白", choice.label, choice.label.trim())
        }
        val labels = McaAppearanceThemeChoice.entries.map { it.label }
        assertEquals("两个主题行会读成同一个名字，用户无法区分选了哪一个", labels.size, labels.toSet().size)
    }

    @Test
    fun `枚举顺序即界面呈现顺序`() {
        // 分区直接遍历 `entries` 渲染，所以声明顺序就是屏幕上从上到下的顺序。
        // 「跟随系统」必须排第一：它是默认值，也是回退值，放在最前才和默认选中位置一致。
        assertEquals(
            "外观选项顺序被改动：行序与默认选中位置会错位",
            listOf(
                McaAppearanceThemeChoice.SYSTEM,
                McaAppearanceThemeChoice.LIGHT,
                McaAppearanceThemeChoice.DARK
            ),
            McaAppearanceThemeChoice.entries.toList()
        )
        val section = designSystemSource(SECTION)
        assertTrue(
            "分区必须遍历枚举渲染，手写一份顺序会与默认值/持久化键脱节",
            section.contains("McaAppearanceThemeChoice.entries.forEach { choice ->")
        )
        assertTrue("每一行都必须渲染枚举里的标签", section.contains("text = choice.label,"))
        assertTrue("分区必须有一句说明，告诉用户跟随系统与固定的区别", section.contains("text = \"外观\","))
    }

    @Test
    fun `选中状态不只靠颜色表达`() {
        // 只靠容器填充色的选中态在色盲与低对比深色模式下都会糊掉，而这一项选错的后果是「读不下去」。
        // 现在的实现给了三个信号：容器色 + 1dp 主色边框 + 尾部对勾图标。
        val section = designSystemSource(SECTION)

        assertTrue(
            "选中行必须有容器色变化",
            section.contains("val container = if (selected) scheme.secondaryContainer else scheme.surface")
        )
        assertTrue("选中行必须有边框变化，仅改填充色不够", section.contains("BorderStroke(1.dp, scheme.primary)"))
        assertTrue("边框必须真的被应用到行上，声明了却没用等于没有", section.contains(".border(border, McaShape.CardAction)"))
        assertTrue("容器色必须真的被应用到行上", section.contains(".background(container, McaShape.CardAction)"))

        val row = section.substringAfter("private fun McaAppearanceThemeRow(")
        // 行内有两处 `if (selected) {`：第一处是边框，最后那处才是尾部对勾。
        val glyphBranchStart = row.lastIndexOf("if (selected) {")
        assertTrue("外观行必须有一个专门的 if (selected) 分支来画尾部对勾", glyphBranchStart >= 0)
        val selectedGlyphBranch = blockAfter(row, glyphBranchStart)
        assertTrue("选中的行必须出现对勾图标，这是颜色之外的第二信号", selectedGlyphBranch.contains("McaIcons.Check"))
        assertTrue(
            "对勾必须是装饰性的（contentDescription = null）：选中状态已由 radio 语义播报，重复描述会让 TalkBack 念两遍",
            selectedGlyphBranch.contains("contentDescription = null")
        )
    }

    @Test
    fun `选项行使用单选语义`() {
        val section = designSystemSource(SECTION)

        assertTrue("三选一必须放进 selectableGroup，TalkBack 才知道它们是同一组选项", section.contains(".selectableGroup()"))
        assertTrue("每一行必须声明 RadioButton 角色，否则只会被念成「按钮」", section.contains("role = Role.RadioButton,"))
        assertTrue("每一行必须上报选中状态，否则读屏用户听不出当前是哪个主题", section.contains("selected = selected,"))
        assertFalse("外观行不能用 clickable 冒充单选，否则丢失角色与选中语义", section.contains(".clickable("))
    }

    @Test
    fun `选项行触控高度不低于 48dp`() {
        // 48dp 是无障碍触控底线（源码注释亦写明）。行内图标盘保持 Medium，命中区靠行高撑起，
        // 视觉重量不随触控面积一起变大。
        val section = designSystemSource(SECTION)

        assertTrue("选项行触控高度不得低于 48dp", section.contains(".heightIn(min = 48.dp)"))
        assertTrue("图标盘必须保持 Medium，不能靠放大图标来凑触控高度", section.contains("size = McaPlateSize.Medium,"))

        val row = section.substringAfter("private fun McaAppearanceThemeRow(")
        assertEquals("高度约束只能有一处，重复声明会互相覆盖并掩盖真实行高", 1, row.split(".heightIn(min = 48.dp)").size - 1)
    }

    @Test
    fun `选项行合并为单一无障碍节点`() {
        val section = designSystemSource(SECTION)

        assertTrue(
            "整行必须 mergeDescendants，否则 TalkBack 会把图标盘、标签、对勾当成三个节点逐个朗读",
            section.contains(".semantics(mergeDescendants = true) { },")
        )

        val row = section
            .substringAfter("private fun McaAppearanceThemeRow(")
            .substringBefore("private fun McaAppearanceThemeChoice.glyph()")
        assertEquals(
            "整行只能有一个 selectable，多一个就会让同一个选项被朗读两遍",
            1,
            row.split(".selectable(").size - 1
        )
        assertTrue("合并后的节点必须仍然带上文字标签", row.contains("text = choice.label,"))
    }

    /** 返回 [start] 之后第一个 `{` 所打开代码块的正文（不含两侧大括号）。 */
    private fun blockAfter(source: String, start: Int): String {
        require(start >= 0) { "找不到要检查的代码块起点" }
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
        throw AssertionError("外观分区源码的大括号不平衡，无法判定选中分支的范围")
    }

    private companion object {
        const val SECTION = "appearance/McaAppearanceSection.kt"
    }
}

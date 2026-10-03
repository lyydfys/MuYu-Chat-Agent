package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `renderLatexMath` / `looksLikeMath` 的行为契约。
 *
 * **realWorldTableCells 用例来自真机实测**：设备 `98a37aa7` 上由真实 GGUF 模型
 * （`gemma-4-E4B-it-uncensored-Q4_K_M`）输出的 Markdown 表格，其单元格在修复前
 * **原样显示** `Short ($\approx 400 \text{ nm}$)`（含 `$` 定界符与反斜杠命令），
 * 且因原始串过长而换行、把行高从 50px 撑到 100px。
 */
class MarkdownMathTextTest {

    // ── 真机实测原始串（修复前逐字如此） ────────────────────────────────

    private val realWorldTableCells = mapOf(
        "Short (\$\\approx 400 \\text{ nm}\$)" to "Short (≈ 400 nm)",
        "Long (\$\\approx 700 \\text{ nm}\$)" to "Long (≈ 700 nm)"
    )

    /** 模拟 `buildInlineMarkdown` 的分派：把 `$…$` 替换为可读文本，其余原样。 */
    private fun renderInline(value: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val span = if (value[i] == '$') findMathSpan(value, i) else null
            if (span != null) {
                sb.append(renderLatexMath(span.tex))
                i = span.endExclusive
            } else {
                sb.append(value[i])
                i++
            }
        }
        return sb.toString()
    }

    @Test
    fun realWorldDeviceTableCellsBecomeReadable() {
        realWorldTableCells.forEach { (raw, expected) ->
            assertEquals("真机实测单元格应可读化: $raw", expected, renderInline(raw))
        }
    }

    @Test
    fun realWorldCellsNoLongerContainDollarOrBackslash() {
        realWorldTableCells.keys.forEach { raw ->
            val rendered = renderInline(raw)
            assertFalse("不应残留 \$: $rendered", rendered.contains('$'))
            assertFalse("不应残留反斜杠: $rendered", rendered.contains('\\'))
        }
    }

    @Test
    fun tableCellWithoutMathIsUnchanged() {
        // 表格里的普通单元格不得被改动
        assertEquals("High", renderInline("High"))
        assertEquals("Blue/Violet", renderInline("Blue/Violet"))
        assertEquals("Wavelength Range", renderInline("Wavelength Range"))
        assertEquals("", renderInline(""))
    }

    // ── findMathSpan：定界符识别（这是 `$5 到 $10` 防误伤的关键）────────

    @Test
    fun findMathSpanMatchesInlineMath() {
        val span = findMathSpan("a \$x^2\$ b", 2)
        assertNotNull(span)
        assertEquals("x^2", span!!.tex)
        assertEquals(7, span.endExclusive)
    }

    @Test
    fun findMathSpanMatchesDisplayMath() {
        val span = findMathSpan("\$\$\\frac{a}{b}\$\$", 0)
        assertNotNull(span)
        assertEquals("\\frac{a}{b}", span!!.tex)
        assertEquals(15, span.endExclusive)
    }

    @Test
    fun findMathSpanRejectsCurrency() {
        // 🔴 核心防误伤契约：价格文本不得被当成数学
        assertNull(findMathSpan("\$5 到 \$10", 0))
        assertNull(findMathSpan("价格 \$19.99 元", 3))
    }

    @Test
    fun findMathSpanRejectsUnclosedAndEmpty() {
        assertNull(findMathSpan("\$x", 0))
        assertNull(findMathSpan("\$\$", 0))
        assertNull(findMathSpan("a", 0))
        assertNull(findMathSpan("", 0))
        assertNull(findMathSpan("\$\$x\$", 0))
    }

    @Test
    fun findMathSpanRejectsNewlineContent() {
        assertNull(findMathSpan("\$x\ny\$", 0))
    }

    @Test
    fun currencyTextSurvivesInlineRendering() {
        assertEquals("价格 \$5 到 \$10 元", renderInline("价格 \$5 到 \$10 元"))
    }

    @Test
    fun bareShortMathTokenIsRendered() {
        // `$x$` 没有显式标记，但存在闭合 `$` ⇒ 走宽松分支，应被识别
        assertEquals("x", renderInline("\$x\$"))
        assertEquals("n+1", renderInline("\$n+1\$"))
    }

    @Test
    fun looksLikeMathStaysStrictForPlainNumbers() {
        // 宽松判定**不得**下沉到 looksLikeMath，否则普通价格数字会被误判
        assertFalse(looksLikeMath("19.99"))
        assertFalse(looksLikeMath("100"))
        assertFalse(looksLikeMath("x"))
    }

    // ── 符号命令 ────────────────────────────────────────────────────────

    @Test
    fun relationSymbols() {
        assertEquals("≈", renderLatexMath("\\approx"))
        assertEquals("≠", renderLatexMath("\\neq"))
        assertEquals("≤", renderLatexMath("\\leq"))
        assertEquals("≥", renderLatexMath("\\geq"))
        assertEquals("∈", renderLatexMath("\\in"))
        assertEquals("∝", renderLatexMath("\\propto"))
    }

    @Test
    fun operatorSymbols() {
        assertEquals("×", renderLatexMath("\\times"))
        assertEquals("÷", renderLatexMath("\\div"))
        assertEquals("±", renderLatexMath("\\pm"))
        assertEquals("·", renderLatexMath("\\cdot"))
        assertEquals("∑", renderLatexMath("\\sum"))
        assertEquals("∫", renderLatexMath("\\int"))
        assertEquals("∞", renderLatexMath("\\infty"))
        assertEquals("∂", renderLatexMath("\\partial"))
    }

    @Test
    fun greekLetters() {
        assertEquals("π", renderLatexMath("\\pi"))
        assertEquals("λ", renderLatexMath("\\lambda"))
        assertEquals("Ω", renderLatexMath("\\Omega"))
        assertEquals("θ", renderLatexMath("\\theta"))
        assertEquals("σ", renderLatexMath("\\sigma"))
    }

    @Test
    fun arrows() {
        assertEquals("→", renderLatexMath("\\to"))
        assertEquals("→", renderLatexMath("\\rightarrow"))
        assertEquals("⇒", renderLatexMath("\\Rightarrow"))
        assertEquals("↔", renderLatexMath("\\leftrightarrow"))
    }

    // ── 结构命令 ────────────────────────────────────────────────────────

    @Test
    fun textLikeCommandsKeepInnerText() {
        assertEquals("400 nm", renderLatexMath("\\text{400 nm}"))
        assertEquals("abc", renderLatexMath("\\mathrm{abc}"))
        assertEquals("f(x)", renderLatexMath("\\operatorname{f}(x)"))
        assertEquals("bold", renderLatexMath("\\textbf{bold}"))
    }

    @Test
    fun fracBecomesSlash() {
        assertEquals("a/b", renderLatexMath("\\frac{a}{b}"))
        assertEquals("1/2", renderLatexMath("\\frac{1}{2}"))
    }

    @Test
    fun sqrtBecomesRootSign() {
        assertEquals("√x", renderLatexMath("\\sqrt{x}"))
        assertEquals("√2", renderLatexMath("\\sqrt{2}"))
    }

    @Test
    fun groupingBracesAreRemoved() {
        assertEquals("abc", renderLatexMath("{abc}"))
        assertEquals("x+y", renderLatexMath("{x}+{y}"))
    }

    // ── 上下标 ──────────────────────────────────────────────────────────

    @Test
    fun superscriptsBecomeUnicode() {
        assertEquals("x²", renderLatexMath("x^2"))
        assertEquals("x²", renderLatexMath("x^{2}"))
        assertEquals("10⁻³", renderLatexMath("10^{-3}"))
        // `^{i}` 是**上标** ⇒ 用上标表（`ⁱ`），不是下标 `ᵢ`
        assertEquals("eⁱ", renderLatexMath("e^{i}"))
    }

    @Test
    fun subscriptsBecomeUnicode() {
        assertEquals("H₂O", renderLatexMath("H_2O"))
        assertEquals("H₂O", renderLatexMath("H_{2}O"))
        assertEquals("aₙ", renderLatexMath("a_n"))
    }

    @Test
    fun unmappableScriptFallsBackToLinearForm() {
        // 中文无法映射为 Unicode 上下标 ⇒ 退回线性写法，不丢信息
        assertEquals("x^中", renderLatexMath("x^{中}"))
    }

    // ── 转义与空格 ──────────────────────────────────────────────────────

    @Test
    fun escapedSymbols() {
        assertEquals("%", renderLatexMath("\\%"))
        assertEquals("$", renderLatexMath("\\$"))
        assertEquals("a b", renderLatexMath("a\\,b"))
        assertEquals("A", renderLatexMath("\\text{A}"))
    }

    @Test
    fun tildeBecomesNonBreakingSpace() {
        assertEquals("a\u00A0b", renderLatexMath("a~b"))
    }

    @Test
    fun unknownCommandKeepsNameWithoutBackslash() {
        assertEquals("foo", renderLatexMath("\\foo"))
        assertEquals("x", renderLatexMath("\\unknowncmd{x}"))
    }

    @Test
    fun nestedCommandsAreHandled() {
        assertEquals("≈ 400 nm", renderLatexMath("\\approx 400 \\text{ nm}"))
        // 有意**不**自动补括号：本函数只做可读化，不做数学排版
        assertEquals("√a/b", renderLatexMath("\\sqrt{\\frac{a}{b}}"))
    }

    @Test
    fun consecutiveSpacesAreCollapsed() {
        // `\text{ nm}` 内部以空格开头，与前面的空格叠加 ⇒ 折叠为一个
        assertEquals("≈ 400 nm", renderLatexMath("\\approx 400 \\text{ nm}"))
        assertEquals("a b", renderLatexMath("a  b"))
    }

    // ── looksLikeMath：**防误伤**是关键契约 ─────────────────────────────

    @Test
    fun plainCurrencyIsNotMath() {
        assertFalse(looksLikeMath("5 到 "))
        assertFalse(looksLikeMath("19.99"))
        assertFalse(looksLikeMath("100"))
        assertFalse(looksLikeMath("价格"))
        assertFalse(looksLikeMath(""))
    }

    @Test
    fun cjkWithOperatorsIsNotMath() {
        // 含中文的表达式不应被当作数学（避免误伤「总计 = 5 元」这类正文）
        assertFalse(looksLikeMath("总计 = 5 元"))
    }

    @Test
    fun latexCommandsAreMath() {
        assertTrue(looksLikeMath("\\approx"))
        assertTrue(looksLikeMath("\\frac{a}{b}"))
        assertTrue(looksLikeMath("x^2"))
        assertTrue(looksLikeMath("H_2O"))
    }

    @Test
    fun asciiExpressionWithOperatorsIsMath() {
        assertTrue(looksLikeMath("x = 1"))
        assertTrue(looksLikeMath("a + b"))
    }

    // ── 幂等与边界 ──────────────────────────────────────────────────────

    @Test
    fun renderingIsStableForAlreadyPlainText() {
        assertEquals("hello world", renderLatexMath("hello world"))
        assertEquals("400 nm", renderLatexMath("400 nm"))
    }

    @Test
    fun emptyAndWhitespaceAreSafe() {
        assertEquals("", renderLatexMath(""))
        assertEquals("", renderLatexMath("   "))
    }

    @Test
    fun unbalancedBracesDoNotCrash() {
        assertEquals("abc", renderLatexMath("\\text{abc"))
        assertEquals("x", renderLatexMath("{x"))
    }

    @Test
    fun trailingBackslashDoesNotCrash() {
        assertEquals("a", renderLatexMath("a\\"))
    }

    @Test
    fun renderingIsIdempotent() {
        val once = renderLatexMath("\\approx 400 \\text{ nm}")
        assertEquals(once, renderLatexMath(once))
    }
}

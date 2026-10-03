package com.muyuchat.feature.chat

/**
 * LaTeX 数学片段的**可读化**渲染。
 *
 * 背景：模型常在回复里输出 `$...$` 形式的行内数学（尤其在 Markdown 表格单元格内，例如
 * `Short ($\approx 400 \text{ nm}$)`）。此前这些 `$` 定界符与 `\command` 会被**原样显示**，
 * 既不可读，也会因为原始串过长而把表格行撑高。
 *
 * 本文件只做**纯文本层面的等价可读化**，不引入数学排版引擎、不改变字体度量：
 * - 已知符号命令 → 对应 Unicode 字符（`\approx` → `≈`）
 * - 结构命令 → 线性写法（`\frac{a}{b}` → `a/b`，`\sqrt{x}` → `√x`）
 * - `\text{...}` / `\mathrm{...}` 等 → 只保留内部文字
 * - 上下标 → Unicode 上下标（`x^2` → `x²`，`H_2O` → `H₂O`）
 * - 未知命令 → **去掉反斜杠、保留名字**（比显示 `\foo` 更可读）
 * - 分组花括号 → 去掉
 *
 * **安全性**：只有内容看起来确实是数学时才转换（见 [looksLikeMath]），
 * 因此 `价格是 $5 到 $10` 这类普通文本不会被误判。
 */
internal fun renderLatexMath(tex: String): String {
    val out = StringBuilder(tex.length)
    var i = 0
    while (i < tex.length) {
        val c = tex[i]
        when {
            c == '\\' -> {
                val nameStart = i + 1
                var j = nameStart
                while (j < tex.length && tex[j].isLetter()) j++
                if (j == nameStart) {
                    // 转义符号：\, \; \! \% \$ \& \# \_ \{ \} \\ 等
                    if (j < tex.length) {
                        out.append(escapedSymbol(tex[j]))
                        i = j + 1
                    } else {
                        i++
                    }
                } else {
                    val command = tex.substring(nameStart, j)
                    i = appendCommand(tex, command, j, out)
                }
            }

            c == '{' || c == '}' -> i++ // 分组花括号在纯文本层面无意义

            c == '^' || c == '_' -> {
                // 上标表与下标表必须按标记区分：`H_2O` 是下标、`x^2` 是上标
                val (consumed, rendered) = readScript(tex, i + 1, if (c == '^') SUPERSCRIPTS else SUBSCRIPTS)
                if (rendered != null) {
                    out.append(rendered)
                    i = consumed
                } else {
                    // 无法表示为 Unicode 上下标时，退回线性写法
                    val (end, inner) = readGroupOrChar(tex, i + 1)
                    if (inner == null) {
                        i++
                    } else {
                        out.append(if (c == '^') "^" else "_").append(inner)
                        i = end
                    }
                }
            }

            c == '~' -> {
                out.append('\u00A0') // LaTeX 的不换行空格
                i++
            }

            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString().trim().replace(SPACE_RUN, " ")
}

/**
 * 折叠连续空格。
 * 说明：`\text{ nm}` 之类命令的内部文本常以空格开头，与前面的空格叠加后会产出双空格；
 * 折叠后视觉更整齐。代价是 `\quad` / `\qquad` 也会被折叠为单个空格 —— 这可接受，
 * 因为本函数的目的是**可读化**而非数学排版。
 */
private val SPACE_RUN = Regex(" {2,}")

/**
 * 判断内容是否含**显式数学标记**。
 *
 * 本函数**刻意保持严格**：只承认 `\命令`、`^`/`_`、数学符号、或不含中文的运算符表达式。
 * 「`$x$` 这类短记号」的宽松判定**不在这里**，而在 [findMathSpan] —— 因为那里已经要求
 * 存在闭合 `$`，所以宽松判定是安全的；若放到这里，`looksLikeMath("19.99")`（普通价格数字）
 * 会被误判为数学。
 */
internal fun looksLikeMath(content: String): Boolean {
    if (content.isEmpty()) return false
    if (content.any { it == '\\' || it == '^' || it == '_' }) return true
    // 纯数字或含明显数学运算符的也算
    val mathSymbols = "≈≤≥≠±×÷√∑∫∞∂∇→←⇒·°π"
    if (content.any { it in mathSymbols }) return true
    // 形如 "x = 1" / "a + b" 的简单表达式（含空格与运算符，且不含中文）
    val hasOperator = content.any { it in "=+*/<>" }
    val hasCjk = content.any { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }
    return hasOperator && !hasCjk
}

/**
 * `$x$` 这类**无显式标记的极短记号**是否可视为数学。
 * 仅在已确认存在闭合 `$…$` 的前提下调用（见 [findMathSpan]）。
 */
private fun looksLikeBareMathToken(content: String): Boolean {
    if (content.isEmpty() || content.length > 24) return false
    if (content.any { it == ' ' }) return false
    if (content.any { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }) return false
    return true
}

/** `$…$` / `$$…$$` 数学片段在原文中的跨度。 */
internal data class MathSpan(val endExclusive: Int, val tex: String)

/**
 * 若 [start] 处是 `$…$` 或 `$$…$$` 且内容看起来是数学，返回其跨度与内容；否则返回 null。
 *
 * 把这段判定提取为**纯函数**，是为了让「定界符识别」可以被单元测试直接覆盖 ——
 * 使用它的 `buildInlineMarkdown` 是 private，无法直接测试。
 */
internal fun findMathSpan(value: String, start: Int): MathSpan? {
    if (start >= value.length || value[start] != '$') return null
    val display = value.startsWith("$$", start)
    val closeToken = if (display) "$$" else "$"
    val contentStart = start + closeToken.length
    val end = value.indexOf(closeToken, startIndex = contentStart)
    if (end <= contentStart) return null
    val inner = value.substring(contentStart, end)
    if (inner.contains('\n')) return null
    // 宽松分支只在此处生效：已确认存在闭合 `$…$`，故 `$x$` 可识别，
    // 而 `$5 到 $10`（含空格与中文）、`$19.99`（无闭合）都不会命中。
    if (!looksLikeMath(inner) && !looksLikeBareMathToken(inner)) return null
    return MathSpan(end + closeToken.length, inner)
}

private fun escapedSymbol(c: Char): String = when (c) {
    ',' -> " "
    ';' -> " "
    ':' -> " "
    '!' -> ""
    ' ' -> " "
    '%' -> "%"
    '$' -> "$"
    '&' -> "&"
    '#' -> "#"
    '_' -> "_"
    '{' -> "{"
    '}' -> "}"
    '\\' -> "\n"
    else -> c.toString()
}

/**
 * 追加一个 `\command`。
 * @return 处理完该命令后应继续扫描的下标（已跳过可选的 `{...}` 参数）
 */
private fun appendCommand(tex: String, command: String, afterName: Int, out: StringBuilder): Int {
    // 取全部 {…} 参数（如 \frac{a}{b} 有两个）
    val args = ArrayList<String>(2)
    var cursor = afterName
    while (cursor < tex.length && tex[cursor] == '{') {
        val (end, inner) = readGroupOrChar(tex, cursor)
        if (inner == null) break
        args.add(inner)
        cursor = end
    }

    val symbol = LATEX_SYMBOLS[command]
    when {
        symbol != null -> out.append(symbol)

        command in TEXT_LIKE_COMMANDS -> out.append(args.firstOrNull().orEmpty())

        command == "frac" && args.size >= 2 ->
            out.append(renderLatexMath(args[0])).append('/').append(renderLatexMath(args[1]))

        command == "sqrt" && args.isNotEmpty() ->
            out.append('√').append(renderLatexMath(args[0]))

        command == "overline" || command == "underline" || command == "vec" ->
            out.append(args.firstOrNull()?.let(::renderLatexMath).orEmpty())

        command == "left" || command == "right" || command == "displaystyle" ||
            command == "textstyle" || command == "limits" || command == "nolimits" -> Unit

        command == "begin" || command == "end" -> Unit // 环境名在参数里，直接丢弃

        else -> {
            // 未知命令：**优先保留参数内容**（用户需要的是内容，命令多半只是格式），
            // 没有参数时才退回命令名（去掉反斜杠，比显示 `\foo` 可读）。
            if (args.isNotEmpty()) {
                args.forEach { out.append(renderLatexMath(it)) }
            } else {
                out.append(command)
            }
        }
    }
    return cursor
}

/** 读取 `{...}` 内容或单个字符；返回 (结束下标, 内容)，无法读取时内容为 null。 */
private fun readGroupOrChar(tex: String, start: Int): Pair<Int, String?> {
    if (start >= tex.length) return start to null
    if (tex[start] == '{') {
        var depth = 0
        var i = start
        while (i < tex.length) {
            when (tex[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return (i + 1) to tex.substring(start + 1, i)
                    }
                }
            }
            i++
        }
        return tex.length to tex.substring(start + 1)
    }
    // 单字符（含 `\x` 形式）
    if (tex[start] == '\\' && start + 1 < tex.length) {
        return (start + 2) to tex.substring(start, start + 2)
    }
    return (start + 1) to tex[start].toString()
}

/** 读取 `^` / `_` 后的内容并尝试转成 Unicode 上下标；不可表示时返回 (原下标, null)。 */
private fun readScript(tex: String, start: Int, table: Map<Char, Char>): Pair<Int, String?> {
    val (end, raw) = readGroupOrChar(tex, start)
    val inner = raw ?: return start to null
    val sb = StringBuilder(inner.length)
    for (ch in inner) {
        val mapped = table[ch] ?: return start to null
        sb.append(mapped)
    }
    return end to sb.toString()
}

private val TEXT_LIKE_COMMANDS = setOf(
    "text", "textrm", "textnormal", "textup", "textit", "textbf", "textsf", "texttt",
    "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "mathcal", "mathbb", "mathfrak",
    "operatorname", "mbox", "hbox", "bm", "boldsymbol", "emph"
)

private val LATEX_SYMBOLS: Map<String, String> = mapOf(
    // 关系
    "approx" to "≈", "neq" to "≠", "ne" to "≠", "equiv" to "≡", "sim" to "∼",
    "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "ll" to "≪", "gg" to "≫",
    "propto" to "∝", "in" to "∈", "notin" to "∉", "subset" to "⊂", "supset" to "⊃",
    // 运算
    "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "·",
    "ast" to "∗", "star" to "⋆", "circ" to "∘", "bullet" to "•",
    "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
    "partial" to "∂", "nabla" to "∇", "infty" to "∞",
    // 注意：`sqrt` **不在此表**中 —— 它带参数，必须走下面 appendCommand 的专用分支，
    // 否则会被这里抢先命中而只剩下一个 `√`、丢掉根号内容。
    // 箭头
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
    "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔",
    "uparrow" to "↑", "downarrow" to "↓", "mapsto" to "↦",
    // 希腊字母（小写）
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
    "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ",
    "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν",
    "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "sigma" to "σ",
    "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ",
    "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    // 希腊字母（大写）
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
    "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ",
    "Omega" to "Ω",
    // 其它
    "degree" to "°", "angle" to "∠", "perp" to "⊥", "parallel" to "∥",
    "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧", "lor" to "∨",
    "quad" to " ", "qquad" to "  ", "space" to " ", "thinspace" to " ",
    "dots" to "…", "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱"
)

private val SUPERSCRIPTS: Map<Char, Char> = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
    'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'y' to 'ʸ', 'a' to 'ᵃ', 'b' to 'ᵇ',
    'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ',
    'j' to 'ʲ', 'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'o' to 'ᵒ', 'p' to 'ᵖ',
    'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ', 'v' to 'ᵛ', 'w' to 'ʷ',
    'z' to 'ᶻ', ' ' to ' '
)

private val SUBSCRIPTS: Map<Char, Char> = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
    'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
    'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
    'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
    'v' to 'ᵥ', 'x' to 'ₓ', ' ' to ' '
)

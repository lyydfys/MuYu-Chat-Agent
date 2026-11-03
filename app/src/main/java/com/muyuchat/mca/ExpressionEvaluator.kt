package com.muyuchat.mca

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A deliberately small arithmetic evaluator for the chat-page `calc` tool.
 *
 * Why not `ScriptEngine`/`eval`: the model supplies the input. A JavaScript engine would hand a
 * remote-controlled script host to a role-play turn, and Ripper/MVEL-style evaluators have the
 * same property. This tokenizer + recursive-descent parser only understands numbers, five
 * operators, parentheses and a fixed function table, so there is no evaluation surface beyond
 * arithmetic. Anything else is a typed failure, never a partial result.
 *
 * Deterministic failure behaviour (covered by `ExpressionEvaluatorTest`):
 *  - unknown identifiers, implicit multiplication, assignment, commas outside functions, quotes,
 *    braces and any other character are rejected by the tokenizer;
 *  - unbalanced parentheses, an empty expression, a stray operator, `1 2` style juxtaposition,
 *    or trailing input after a complete expression are rejected;
 *  - division by zero, `sqrt(-1)`, `ln(0)` and overflow to ±Infinity/NaN are failures rather than
 *    values, so the model never receives `Infinity` as an answer;
 *  - the expression length and nesting depth are bounded before parsing.
 */
internal object ExpressionEvaluator {

    const val MAX_EXPRESSION_CHARS = 200
    const val MAX_NESTING_DEPTH = 24

    sealed interface Result {
        data class Value(
            /** Exact double result, used for further composition in tests and diagnostics. */
            val value: Double,
            /** Human-readable form with at most 12 significant digits and no `1.0E12` noise. */
            val formatted: String
        ) : Result

        data class Failure(val reason: String) : Result
    }

    fun evaluate(expression: String): Result {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return Result.Failure("表达式为空。")
        if (trimmed.length > MAX_EXPRESSION_CHARS) {
            return Result.Failure("表达式超过 $MAX_EXPRESSION_CHARS 字符限制。")
        }
        val tokens = try {
            Tokenizer(trimmed).tokenize()
        } catch (error: ArithmeticException) {
            return Result.Failure(error.message ?: "表达式无法解析。")
        }
        val parser = Parser(tokens)
        val value = try {
            parser.parse()
        } catch (error: ArithmeticException) {
            return Result.Failure(error.message ?: "表达式无法计算。")
        }
        if (value.isNaN()) return Result.Failure("结果不是有效数字。")
        if (value.isInfinite()) return Result.Failure("结果超出可表示范围。")
        return Result.Value(value, format(value))
    }

    // ---- tokens -----------------------------------------------------------------------------

    private sealed interface Token {
        data class Number(val value: Double) : Token
        data class Identifier(val name: String) : Token
        data class Symbol(val char: Char) : Token
    }

    private class Tokenizer(private val input: String) {
        private var index = 0

        fun tokenize(): List<Token> {
            val tokens = mutableListOf<Token>()
            while (true) {
                skipSpaces()
                if (index >= input.length) break
                val char = input[index]
                when {
                    char.isDigit() || char == '.' -> tokens += Token.Number(readNumber())
                    char.isLetter() || char == '_' -> tokens += Token.Identifier(readIdentifier())
                    char in SYMBOLS -> {
                        tokens += Token.Symbol(char)
                        index += 1
                    }
                    else -> throw ArithmeticException("不支持的字符：$char")
                }
            }
            if (tokens.isEmpty()) throw ArithmeticException("表达式为空。")
            return tokens
        }

        private fun skipSpaces() {
            while (index < input.length && input[index].isWhitespace()) index += 1
        }

        private fun readNumber(): Double {
            val start = index
            var seenDot = false
            var seenExponent = false
            while (index < input.length) {
                val char = input[index]
                when {
                    char.isDigit() -> index += 1
                    char == '.' && !seenDot && !seenExponent -> {
                        seenDot = true
                        index += 1
                    }
                    (char == 'e' || char == 'E') && !seenExponent && index > start -> {
                        val next = input.getOrNull(index + 1)
                        val afterSign = input.getOrNull(index + 2)
                        val hasDigitNext = next?.isDigit() == true ||
                            ((next == '+' || next == '-') && afterSign?.isDigit() == true)
                        if (!hasDigitNext) break
                        seenExponent = true
                        index += 1
                        if (input[index] == '+' || input[index] == '-') index += 1
                        while (index < input.length && input[index].isDigit()) index += 1
                        break
                    }
                    else -> break
                }
            }
            val text = input.substring(start, index)
            // `1.` and `.5` are accepted by Double.parseDouble; a bare `.` is not a number.
            return text.toDoubleOrNull() ?: throw ArithmeticException("不是有效数字：$text")
        }

        private fun readIdentifier(): String {
            val start = index
            while (index < input.length && (input[index].isLetterOrDigit() || input[index] == '_')) {
                index += 1
            }
            return input.substring(start, index)
        }

        private companion object {
            val SYMBOLS = setOf('+', '-', '*', '/', '%', '^', '(', ')', ',')
        }
    }

    // ---- parser -----------------------------------------------------------------------------

    private class Parser(private val tokens: List<Token>) {
        private var index = 0
        private var depth = 0

        fun parse(): Double {
            val value = expression()
            if (index != tokens.size) throw ArithmeticException("表达式尾部有多余内容。")
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                val symbol = peekSymbol()
                when (symbol) {
                    '+' -> {
                        index += 1
                        value += term()
                    }
                    '-' -> {
                        index += 1
                        value -= term()
                    }
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = unary()
            while (true) {
                when (peekSymbol()) {
                    '*' -> {
                        index += 1
                        value *= unary()
                    }
                    '/' -> {
                        index += 1
                        val divisor = unary()
                        if (divisor == 0.0) throw ArithmeticException("不能除以零。")
                        value /= divisor
                    }
                    '%' -> {
                        index += 1
                        val divisor = unary()
                        if (divisor == 0.0) throw ArithmeticException("不能对零取余。")
                        value %= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun unary(): Double = when (peekSymbol()) {
            '+' -> {
                index += 1
                unary()
            }
            '-' -> {
                index += 1
                -unary()
            }
            else -> power()
        }

        /** `^` binds tighter than unary minus on the right and is right-associative. */
        private fun power(): Double {
            val base = primary()
            if (peekSymbol() != '^') return base
            index += 1
            return base.pow(unary())
        }

        private fun primary(): Double {
            val token = tokens.getOrNull(index) ?: throw ArithmeticException("表达式不完整。")
            return when (token) {
                is Token.Number -> {
                    index += 1
                    token.value
                }
                is Token.Symbol -> when (token.char) {
                    '(' -> {
                        enter()
                        index += 1
                        val value = expression()
                        if (peekSymbol() != ')') throw ArithmeticException("括号没有闭合。")
                        index += 1
                        leave()
                        value
                    }
                    else -> throw ArithmeticException("这里需要一个数字或括号，实际是 ${token.char}。")
                }
                is Token.Identifier -> callOrConstant(token.name)
            }
        }

        private fun callOrConstant(name: String): Double {
            val constant = CONSTANTS[name.lowercase()]
            if (constant != null) {
                index += 1
                return constant
            }
            val arity = FUNCTIONS[name.lowercase()]
                ?: throw ArithmeticException("不支持的函数或名称：$name")
            index += 1
            if (peekSymbol() != '(') throw ArithmeticException("$name 后面需要参数括号。")
            enter()
            index += 1
            val arguments = mutableListOf<Double>()
            if (peekSymbol() != ')') {
                arguments += expression()
                while (peekSymbol() == ',') {
                    index += 1
                    arguments += expression()
                }
            }
            if (peekSymbol() != ')') throw ArithmeticException("$name 的参数括号没有闭合。")
            index += 1
            leave()
            if (arguments.size != arity) {
                throw ArithmeticException("$name 需要 $arity 个参数，实际 ${arguments.size} 个。")
            }
            return apply(name.lowercase(), arguments)
        }

        private fun apply(name: String, arguments: List<Double>): Double = when (name) {
            "abs" -> abs(arguments[0])
            "sqrt" -> {
                if (arguments[0] < 0.0) throw ArithmeticException("负数没有实平方根。")
                sqrt(arguments[0])
            }
            "round" -> round(arguments[0])
            "floor" -> floor(arguments[0])
            "ceil" -> ceil(arguments[0])
            "ln" -> {
                if (arguments[0] <= 0.0) throw ArithmeticException("对数参数必须大于零。")
                ln(arguments[0])
            }
            "log" -> {
                if (arguments[0] <= 0.0) throw ArithmeticException("对数参数必须大于零。")
                log10(arguments[0])
            }
            "exp" -> exp(arguments[0])
            "sin" -> sin(arguments[0])
            "cos" -> cos(arguments[0])
            "tan" -> tan(arguments[0])
            "min" -> min(arguments[0], arguments[1])
            "max" -> max(arguments[0], arguments[1])
            "pow" -> arguments[0].pow(arguments[1])
            else -> throw ArithmeticException("不支持的函数：$name")
        }

        private fun peekSymbol(): Char? = (tokens.getOrNull(index) as? Token.Symbol)?.char

        private fun enter() {
            depth += 1
            if (depth > MAX_NESTING_DEPTH) throw ArithmeticException("括号层数超过 $MAX_NESTING_DEPTH 限制。")
        }

        private fun leave() {
            depth -= 1
        }
    }

    private val CONSTANTS = mapOf("pi" to Math.PI, "e" to Math.E)

    /** Fixed argument counts per function; an unknown name or a wrong count is a failure. */
    private val FUNCTIONS = mapOf(
        "abs" to 1,
        "sqrt" to 1,
        "round" to 1,
        "floor" to 1,
        "ceil" to 1,
        "ln" to 1,
        "log" to 1,
        "exp" to 1,
        "sin" to 1,
        "cos" to 1,
        "tan" to 1,
        "min" to 2,
        "max" to 2,
        "pow" to 2
    )

    /**
     * Keeps small integers readable (`7`, not `7.0`) and long decimals honest without switching
     * to scientific notation that a role-play answer would have to explain.
     */
    private fun format(value: Double): String {
        val normalized = if (value == 0.0) 0.0 else value
        if (abs(normalized) < 1.0e15 && normalized == floor(normalized)) {
            return normalized.toLong().toString()
        }
        return BigDecimal(normalized)
            .setScale(10, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }
}

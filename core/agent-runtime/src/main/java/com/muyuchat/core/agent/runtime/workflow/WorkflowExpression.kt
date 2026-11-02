package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.runtime.AgentJsonValue
import java.math.BigDecimal

/**
 * Restricted condition expression for workflow graphs (N17 / DESIGN §7.2).
 *
 * There is no string evaluation and no scripting: the AST is parsed from a bounded JSON object and
 * evaluated by this file only. Comparisons are type-strict and case-sensitive; a mismatch is a typed
 * error (never a silent `false`), so a broken branch cannot be mistaken for "condition not met".
 */
sealed interface WorkflowExpression {
    data class Literal(val value: AgentJsonValue) : WorkflowExpression

    /** Dotted path resolved by the engine (`inputs.x`, `nodes.<id>.outputs.<port>`). */
    data class Reference(val path: String) : WorkflowExpression

    data class Unary(val op: UnaryOp, val operand: WorkflowExpression) : WorkflowExpression

    data class Binary(
        val op: BinaryOp,
        val left: WorkflowExpression,
        val right: WorkflowExpression
    ) : WorkflowExpression
}

enum class UnaryOp { NOT }

enum class BinaryOp {
    AND,
    OR,
    EQ,
    NE,
    GT,
    GTE,
    LT,
    LTE,
    CONTAINS,
    NOT_CONTAINS,
    IN,
    NOT_IN
}

sealed interface WorkflowExpressionParseResult {
    data class Parsed(val expression: WorkflowExpression) : WorkflowExpressionParseResult

    data class Invalid(val code: String, val safeMessage: String) : WorkflowExpressionParseResult
}

sealed interface WorkflowExpressionResult {
    data class Value(val value: AgentJsonValue) : WorkflowExpressionResult

    data class Error(val code: String, val safeMessage: String) : WorkflowExpressionResult
}

object WorkflowExpressionLimits {
    const val MAX_DEPTH = 16
    const val MAX_NODES = 128
    const val MAX_REFERENCE_CHARS = 256
    const val MAX_LITERAL_STRING_CHARS = 4_096
    const val MAX_LITERAL_ARRAY_ITEMS = 64
}

/** Strict parser: only the documented shapes are accepted; extra keys are rejected. */
object WorkflowExpressionParser {
    fun parse(root: AgentJsonValue): WorkflowExpressionParseResult {
        var nodes = 0
        return parseNode(root, depth = 0, nodes = { nodes += 1; nodes })
    }

    private fun parseNode(
        node: AgentJsonValue,
        depth: Int,
        nodes: () -> Int
    ): WorkflowExpressionParseResult {
        if (depth > WorkflowExpressionLimits.MAX_DEPTH) {
            return invalid("WF_EXPR_DEPTH", "The expression is nested too deeply.")
        }
        if (nodes() > WorkflowExpressionLimits.MAX_NODES) {
            return invalid("WF_EXPR_TOO_LARGE", "The expression has too many nodes.")
        }
        val obj = node as? AgentJsonValue.Obj
            ?: return invalid("WF_EXPR_SHAPE", "An expression node must be a JSON object.")
        val fields = obj.fields.toMap()
        return when {
            fields.containsKey("literal") -> {
                if (fields.keys != setOf("literal")) {
                    return invalid("WF_EXPR_UNKNOWN_FIELD", "A literal node has no other fields.")
                }
                val value = fields.getValue("literal")
                when (value) {
                    is AgentJsonValue.Str ->
                        if (value.value.length > WorkflowExpressionLimits.MAX_LITERAL_STRING_CHARS) {
                            invalid("WF_EXPR_LITERAL_TOO_LARGE", "A literal string is too long.")
                        } else {
                            parsed(WorkflowExpression.Literal(value))
                        }
                    is AgentJsonValue.Num,
                    is AgentJsonValue.Bool,
                    AgentJsonValue.Null -> parsed(WorkflowExpression.Literal(value))
                    is AgentJsonValue.Arr ->
                        if (value.items.size > WorkflowExpressionLimits.MAX_LITERAL_ARRAY_ITEMS) {
                            invalid("WF_EXPR_LITERAL_TOO_LARGE", "A literal array has too many items.")
                        } else if (value.items.any { it !is AgentJsonValue.Str && it !is AgentJsonValue.Num &&
                                it !is AgentJsonValue.Bool && it != AgentJsonValue.Null }
                        ) {
                            invalid("WF_EXPR_LITERAL_SHAPE", "A literal array may contain scalars only.")
                        } else {
                            parsed(WorkflowExpression.Literal(value))
                        }
                    is AgentJsonValue.Obj ->
                        invalid("WF_EXPR_LITERAL_SHAPE", "A literal object is not supported.")
                }
            }
            fields.containsKey("ref") -> {
                if (fields.keys != setOf("ref")) {
                    return invalid("WF_EXPR_UNKNOWN_FIELD", "A reference node has no other fields.")
                }
                val path = (fields["ref"] as? AgentJsonValue.Str)?.value
                    ?: return invalid("WF_EXPR_REF", "A reference must be a string path.")
                if (path.isBlank() || path.length > WorkflowExpressionLimits.MAX_REFERENCE_CHARS) {
                    return invalid("WF_EXPR_REF", "A reference path is blank or too long.")
                }
                if (!path.matches(REFERENCE_PATTERN)) {
                    return invalid("WF_EXPR_REF", "A reference path contains unsupported characters.")
                }
                parsed(WorkflowExpression.Reference(path))
            }
            fields.containsKey("op") -> {
                val op = (fields["op"] as? AgentJsonValue.Str)?.value
                    ?: return invalid("WF_EXPR_OP", "An operation node needs a string op.")
                val unary = runCatching { UnaryOp.valueOf(op) }.getOrNull()
                val binary = runCatching { BinaryOp.valueOf(op) }.getOrNull()
                when {
                    unary != null -> {
                        if (fields.keys != setOf("op", "arg")) {
                            return invalid("WF_EXPR_UNKNOWN_FIELD", "A unary operation has op+arg only.")
                        }
                        val arg = fields["arg"] ?: return invalid("WF_EXPR_SHAPE", "A unary operation needs an arg.")
                        when (val parsedArg = parseNode(arg, depth + 1, nodes)) {
                            is WorkflowExpressionParseResult.Invalid -> return parsedArg
                            is WorkflowExpressionParseResult.Parsed -> parsed(
                                WorkflowExpression.Unary(unary, parsedArg.expression)
                            )
                        }
                    }
                    binary != null -> {
                        if (fields.keys != setOf("op", "left", "right")) {
                            return invalid("WF_EXPR_UNKNOWN_FIELD", "A binary operation has op+left+right only.")
                        }
                        val left = fields["left"] ?: return invalid("WF_EXPR_SHAPE", "A binary operation needs left.")
                        val right = fields["right"] ?: return invalid("WF_EXPR_SHAPE", "A binary operation needs right.")
                        val parsedLeft = parseNode(left, depth + 1, nodes)
                        if (parsedLeft is WorkflowExpressionParseResult.Invalid) return parsedLeft
                        val parsedRight = parseNode(right, depth + 1, nodes)
                        if (parsedRight is WorkflowExpressionParseResult.Invalid) return parsedRight
                        parsed(
                            WorkflowExpression.Binary(
                                op = binary,
                                left = (parsedLeft as WorkflowExpressionParseResult.Parsed).expression,
                                right = (parsedRight as WorkflowExpressionParseResult.Parsed).expression
                            )
                        )
                    }
                    else -> invalid("WF_EXPR_OP", "The operator '$op' is not supported.")
                }
            }
            else -> invalid("WF_EXPR_SHAPE", "An expression node needs literal, ref or op.")
        }
    }

    private fun parsed(expression: WorkflowExpression): WorkflowExpressionParseResult =
        WorkflowExpressionParseResult.Parsed(expression)

    private fun invalid(code: String, message: String): WorkflowExpressionParseResult =
        WorkflowExpressionParseResult.Invalid(code, message)

    private val REFERENCE_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)+$")
}

/** Type-strict evaluator; it never throws for data/type problems, it returns a typed error. */
object WorkflowExpressionEvaluator {
    fun evaluate(
        expression: WorkflowExpression,
        resolve: (String) -> AgentJsonValue?
    ): WorkflowExpressionResult = when (expression) {
        is WorkflowExpression.Literal -> WorkflowExpressionResult.Value(expression.value)
        is WorkflowExpression.Reference -> resolve(expression.path)?.let { WorkflowExpressionResult.Value(it) }
            ?: error("WF_EXPR_MISSING_REF", "A referenced value is not available.")
        is WorkflowExpression.Unary -> {
            val operandResult = evaluate(expression.operand, resolve)
            if (operandResult is WorkflowExpressionResult.Error) return operandResult
            val operand = (operandResult as WorkflowExpressionResult.Value).value
            val bool = operand as? AgentJsonValue.Bool
                ?: return error("WF_EXPR_TYPE", "NOT requires a boolean operand.")
            WorkflowExpressionResult.Value(AgentJsonValue.Bool(!bool.value))
        }
        is WorkflowExpression.Binary -> evaluateBinary(expression, resolve)
    }

    /** Convenience for a condition node: the expression must yield a boolean. */
    fun evaluateCondition(
        expression: WorkflowExpression,
        resolve: (String) -> AgentJsonValue?
    ): WorkflowExpressionResult {
        val value = evaluate(expression, resolve)
        if (value is WorkflowExpressionResult.Value && value.value !is AgentJsonValue.Bool) {
            return error("WF_EXPR_TYPE", "A condition must evaluate to a boolean.")
        }
        return value
    }

    private fun evaluateBinary(
        expression: WorkflowExpression.Binary,
        resolve: (String) -> AgentJsonValue?
    ): WorkflowExpressionResult {
        if (expression.op == BinaryOp.AND || expression.op == BinaryOp.OR) {
            return evaluateLogical(expression, resolve)
        }
        val left = evaluate(expression.left, resolve)
        if (left is WorkflowExpressionResult.Error) return left
        val right = evaluate(expression.right, resolve)
        if (right is WorkflowExpressionResult.Error) return right
        val a = (left as WorkflowExpressionResult.Value).value
        val b = (right as WorkflowExpressionResult.Value).value
        return when (expression.op) {
            BinaryOp.EQ, BinaryOp.NE -> {
                val equal = equalsValue(a, b) ?: return error(
                    "WF_EXPR_TYPE",
                    "Equality requires two scalars of the same type."
                )
                WorkflowExpressionResult.Value(AgentJsonValue.Bool(equal == (expression.op == BinaryOp.EQ)))
            }
            BinaryOp.GT, BinaryOp.GTE, BinaryOp.LT, BinaryOp.LTE -> {
                val numbers = compareNumbers(a, b) ?: return error(
                    "WF_EXPR_TYPE",
                    "Ordering requires two numbers."
                )
                val result = when (expression.op) {
                    BinaryOp.GT -> numbers > 0
                    BinaryOp.GTE -> numbers >= 0
                    BinaryOp.LT -> numbers < 0
                    else -> numbers <= 0
                }
                WorkflowExpressionResult.Value(AgentJsonValue.Bool(result))
            }
            BinaryOp.CONTAINS, BinaryOp.NOT_CONTAINS -> {
                val contains = containsValue(a, b) ?: return error(
                    "WF_EXPR_TYPE",
                    "CONTAINS requires a string haystack and a string needle, or an array and a scalar."
                )
                WorkflowExpressionResult.Value(
                    AgentJsonValue.Bool(contains == (expression.op == BinaryOp.CONTAINS))
                )
            }
            BinaryOp.IN, BinaryOp.NOT_IN -> {
                val array = b as? AgentJsonValue.Arr ?: return error(
                    "WF_EXPR_TYPE",
                    "IN requires an array on the right side."
                )
                var member = false
                array.items.forEach { candidate ->
                    val equal = equalsValue(a, candidate) ?: return error(
                        "WF_EXPR_TYPE",
                        "IN requires scalars of the same type."
                    )
                    if (equal) member = true
                }
                WorkflowExpressionResult.Value(
                    AgentJsonValue.Bool(member == (expression.op == BinaryOp.IN))
                )
            }
        }
    }

    /**
     * AND/OR are the only short-circuit operators: a missing/unauthorized value on the untouched
     * side must not turn into an error (or a silent false) once the result is already decided.
     */
    private fun evaluateLogical(
        expression: WorkflowExpression.Binary,
        resolve: (String) -> AgentJsonValue?
    ): WorkflowExpressionResult {
        val leftResult = evaluate(expression.left, resolve)
        if (leftResult is WorkflowExpressionResult.Error) return leftResult
        val left = (leftResult as WorkflowExpressionResult.Value).value as? AgentJsonValue.Bool
            ?: return error("WF_EXPR_TYPE", "AND/OR require boolean operands.")
        if (expression.op == BinaryOp.AND && !left.value) {
            return WorkflowExpressionResult.Value(AgentJsonValue.Bool(false))
        }
        if (expression.op == BinaryOp.OR && left.value) {
            return WorkflowExpressionResult.Value(AgentJsonValue.Bool(true))
        }
        val rightResult = evaluate(expression.right, resolve)
        if (rightResult is WorkflowExpressionResult.Error) return rightResult
        val right = (rightResult as WorkflowExpressionResult.Value).value as? AgentJsonValue.Bool
            ?: return error("WF_EXPR_TYPE", "AND/OR require boolean operands.")
        return WorkflowExpressionResult.Value(AgentJsonValue.Bool(right.value))
    }

    private fun equalsValue(left: AgentJsonValue, right: AgentJsonValue): Boolean? = when {
        left is AgentJsonValue.Str && right is AgentJsonValue.Str -> left.value == right.value
        left is AgentJsonValue.Bool && right is AgentJsonValue.Bool -> left.value == right.value
        left is AgentJsonValue.Num && right is AgentJsonValue.Num ->
            number(left)?.compareTo(number(right) ?: return null) == 0
        left == AgentJsonValue.Null && right == AgentJsonValue.Null -> true
        left != AgentJsonValue.Null && right != AgentJsonValue.Null &&
            (left is AgentJsonValue.Str || left is AgentJsonValue.Bool || left is AgentJsonValue.Num) &&
            (right is AgentJsonValue.Str || right is AgentJsonValue.Bool || right is AgentJsonValue.Num) -> false
        left == AgentJsonValue.Null || right == AgentJsonValue.Null -> false
        else -> null
    }

    private fun compareNumbers(left: AgentJsonValue, right: AgentJsonValue): Int? {
        if (left !is AgentJsonValue.Num || right !is AgentJsonValue.Num) return null
        val a = number(left) ?: return null
        val b = number(right) ?: return null
        return a.compareTo(b)
    }

    private fun containsValue(haystack: AgentJsonValue, needle: AgentJsonValue): Boolean? = when {
        haystack is AgentJsonValue.Str && needle is AgentJsonValue.Str -> haystack.value.contains(needle.value)
        haystack is AgentJsonValue.Arr -> haystack.items.any { candidate -> equalsValue(needle, candidate) == true }
        else -> null
    }

    private fun number(value: AgentJsonValue.Num): BigDecimal? =
        runCatching { BigDecimal(value.literal) }.getOrNull()

    private fun error(code: String, message: String): WorkflowExpressionResult.Error =
        WorkflowExpressionResult.Error(code, message)
}

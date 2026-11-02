package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.runtime.AgentJsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** N17 / WF-05 (U): restricted expression AST parsing, type-strict evaluation and validator hook. */
class WorkflowExpressionTest {

    private fun obj(vararg fields: Pair<String, AgentJsonValue>) = AgentJsonValue.Obj(fields.toList())

    private fun str(value: String) = AgentJsonValue.Str(value)

    private fun num(value: String) = AgentJsonValue.Num(value)

    private fun bool(value: Boolean) = AgentJsonValue.Bool(value)

    private fun parse(root: AgentJsonValue): WorkflowExpression =
        (WorkflowExpressionParser.parse(root) as WorkflowExpressionParseResult.Parsed).expression

    private fun parseError(root: AgentJsonValue): String =
        (WorkflowExpressionParser.parse(root) as WorkflowExpressionParseResult.Invalid).code

    private fun eval(
        root: AgentJsonValue,
        values: Map<String, AgentJsonValue> = emptyMap()
    ): WorkflowExpressionResult = WorkflowExpressionEvaluator.evaluate(parse(root)) { values[it] }

    private fun boolResult(result: WorkflowExpressionResult): Boolean =
        (result as WorkflowExpressionResult.Value).value.let { (it as AgentJsonValue.Bool).value }

    private fun errorCode(result: WorkflowExpressionResult): String =
        (result as WorkflowExpressionResult.Error).code

    @Test
    fun literalsAndReferencesParseUnderStrictShapes() {
        assertEquals(WorkflowExpression.Literal(str("x")), parse(obj("literal" to str("x"))))
        assertEquals(WorkflowExpression.Reference("inputs.scope"), parse(obj("ref" to str("inputs.scope"))))
        assertEquals(
            "WF_EXPR_UNKNOWN_FIELD",
            parseError(obj("literal" to str("x"), "ref" to str("inputs.a")))
        )
        assertEquals("WF_EXPR_REF", parseError(obj("ref" to str("../etc/passwd"))))
        assertEquals("WF_EXPR_OP", parseError(obj("op" to str("EXEC"), "arg" to obj("literal" to bool(true)))))
    }

    @Test
    fun unaryNotAndBinaryOperatorsParse() {
        val not = parse(obj("op" to str("NOT"), "arg" to obj("literal" to bool(true))))
        assertTrue(not is WorkflowExpression.Unary)

        val eq = parse(
            obj(
                "op" to str("EQ"),
                "left" to obj("ref" to str("inputs.a")),
                "right" to obj("literal" to num("3"))
            )
        )
        assertTrue(eq is WorkflowExpression.Binary)
    }

    @Test
    fun equalityIsTypeAwareAndCaseSensitive() {
        val values = mapOf<String, AgentJsonValue>(
            "inputs.a" to str("Yes"),
            "inputs.n" to num("3.0"),
            "inputs.b" to bool(true)
        )
        assertTrue(boolResult(eval(obj("op" to str("EQ"), "left" to obj("ref" to str("inputs.a")), "right" to obj("literal" to str("Yes"))), values)))
        assertTrue(!boolResult(eval(obj("op" to str("EQ"), "left" to obj("ref" to str("inputs.a")), "right" to obj("literal" to str("yes"))), values)))
        assertTrue(boolResult(eval(obj("op" to str("EQ"), "left" to obj("ref" to str("inputs.n")), "right" to obj("literal" to num("3"))), values)))
        assertTrue(!boolResult(eval(obj("op" to str("EQ"), "left" to obj("ref" to str("inputs.a")), "right" to obj("literal" to num("1"))), values)))
        assertTrue(
            !boolResult(
                eval(
                    obj(
                        "op" to str("NE"),
                        "left" to obj("ref" to str("inputs.b")),
                        "right" to obj("literal" to bool(true))
                    ),
                    values
                )
            )
        )
    }

    @Test
    fun orderingRequiresNumbers() {
        val values = mapOf<String, AgentJsonValue>("inputs.n" to num("5"))
        assertTrue(boolResult(eval(obj("op" to str("GT"), "left" to obj("ref" to str("inputs.n")), "right" to obj("literal" to num("4"))), values)))
        assertTrue(boolResult(eval(obj("op" to str("LTE"), "left" to obj("ref" to str("inputs.n")), "right" to obj("literal" to num("5"))), values)))
        assertEquals(
            "WF_EXPR_TYPE",
            errorCode(eval(obj("op" to str("GT"), "left" to obj("ref" to str("inputs.n")), "right" to obj("literal" to str("4"))), values))
        )
    }

    @Test
    fun containsAndInUseDistinctSemantics() {
        val values = mapOf<String, AgentJsonValue>("inputs.text" to str("hello world"))
        assertTrue(boolResult(eval(obj("op" to str("CONTAINS"), "left" to obj("ref" to str("inputs.text")), "right" to obj("literal" to str("world"))), values)))
        assertTrue(boolResult(eval(obj("op" to str("NOT_CONTAINS"), "left" to obj("ref" to str("inputs.text")), "right" to obj("literal" to str("WORLD"))), values)))

        val array = AgentJsonValue.Arr(listOf(str("a"), str("b")))
        assertTrue(boolResult(eval(obj("op" to str("IN"), "left" to obj("literal" to str("a")), "right" to obj("literal" to array)))))
        assertTrue(boolResult(eval(obj("op" to str("NOT_IN"), "left" to obj("literal" to str("c")), "right" to obj("literal" to array)))))
    }

    @Test
    fun missingReferencesAndNonBooleanConditionsAreTypedErrors() {
        assertEquals(
            "WF_EXPR_MISSING_REF",
            errorCode(eval(obj("ref" to str("inputs.missing"))))
        )
        val notString = obj("op" to str("NOT"), "arg" to obj("literal" to str("x")))
        assertEquals("WF_EXPR_TYPE", errorCode(eval(notString)))
        assertEquals(
            "WF_EXPR_TYPE",
            (WorkflowExpressionEvaluator.evaluateCondition(parse(obj("literal" to str("x")))) { null } as
                WorkflowExpressionResult.Error).code
        )
    }

    @Test
    fun unboundedNestingAndOversizedReferencesAreRejected() {
        var nested: AgentJsonValue = obj("literal" to bool(true))
        repeat(WorkflowExpressionLimits.MAX_DEPTH + 2) {
            nested = obj("op" to str("NOT"), "arg" to nested)
        }
        assertEquals("WF_EXPR_DEPTH", parseError(nested))
        assertEquals(
            "WF_EXPR_REF",
            parseError(obj("ref" to str("a.".repeat(WorkflowExpressionLimits.MAX_REFERENCE_CHARS / 2))))
        )
    }

    @Test
    fun theValidatorAcceptsAnAstConditionAndRejectsABrokenOne() {
        val base = com.muyuchat.core.agent.contract.workflow.WorkflowDefinition(
            definitionId = "wf-expr",
            version = 1,
            displayName = "expr",
            outputs = listOf(
                com.muyuchat.core.agent.contract.workflow.WorkflowPort(
                    "result",
                    com.muyuchat.core.agent.contract.workflow.WorkflowPortType.STRING
                )
            ),
            nodes = listOf(
                com.muyuchat.core.agent.contract.workflow.WorkflowNode(
                    "trigger",
                    com.muyuchat.core.agent.contract.workflow.WorkflowNodeType.TRIGGER
                ),
                com.muyuchat.core.agent.contract.workflow.WorkflowNode(
                    "check",
                    com.muyuchat.core.agent.contract.workflow.WorkflowNodeType.CONDITION,
                    configJson = """{"expression":{"op":"EQ","left":{"ref":"inputs.a"},"right":{"literal":1}}}"""
                ),
                com.muyuchat.core.agent.contract.workflow.WorkflowNode(
                    "done",
                    com.muyuchat.core.agent.contract.workflow.WorkflowNodeType.OUTPUT,
                    configJson = """{"port":"result"}"""
                )
            ),
            edges = listOf(
                com.muyuchat.core.agent.contract.workflow.WorkflowEdge("trigger", "check"),
                com.muyuchat.core.agent.contract.workflow.WorkflowEdge("check", "done", whenLabel = "true")
            )
        )
        // A single labeled edge is incomplete but the expression itself is valid.
        val invalidRouting = WorkflowDefinitionValidator().validate(base)
                as WorkflowValidationResult.Invalid
        assertTrue(invalidRouting.issues.none { it.code == "WF_EXPRESSION_INVALID" })

        val broken = base.copy(
            nodes = base.nodes.map { node ->
                if (node.nodeId == "check") {
                    node.copy(configJson = """{"expression":{"op":"NOPE","arg":{"literal":true}}}""")
                } else {
                    node
                }
            }
        )
        val brokenResult = WorkflowDefinitionValidator().validate(broken)
                as WorkflowValidationResult.Invalid
        assertTrue(brokenResult.issues.any { it.code == "WF_EXPRESSION_INVALID" })
    }
    @Test
    fun everyOrderingOperatorIsTypeStrict() {
        val values = mapOf<String, AgentJsonValue>("inputs.n" to num("5"))
        fun compare(op: String, right: AgentJsonValue): WorkflowExpressionResult =
            eval(
                obj(
                    "op" to str(op),
                    "left" to obj("ref" to str("inputs.n")),
                    "right" to obj("literal" to right)
                ),
                values
            )
        assertTrue(boolResult(compare("LT", num("6"))))
        assertTrue(!boolResult(compare("LT", num("5"))))
        assertTrue(boolResult(compare("GTE", num("5"))))
        assertTrue(!boolResult(compare("GTE", num("6"))))
        assertTrue(boolResult(compare("LTE", num("5"))))
        assertTrue(!boolResult(compare("LTE", num("4"))))
        assertTrue(boolResult(compare("GT", num("4"))))
        assertTrue(!boolResult(compare("GT", num("5"))))
        for (op in listOf("LT", "LTE", "GT", "GTE")) {
            assertEquals("WF_EXPR_TYPE", errorCode(compare(op, str("5"))))
        }
    }

    @Test
    fun logicalOperatorsShortCircuitMissingRightSide() {
        val falseLeft = obj("literal" to bool(false))
        val trueLeft = obj("literal" to bool(true))
        fun logical(op: String, left: AgentJsonValue, right: AgentJsonValue) =
            eval(obj("op" to str(op), "left" to left, "right" to right))

        // AND false does not touch the right side; OR true does not touch the right side.
        assertTrue(!boolResult(logical("AND", falseLeft, obj("ref" to str("inputs.missing")))))
        assertTrue(boolResult(logical("OR", trueLeft, obj("ref" to str("inputs.missing")))))

        // The right side is evaluated when the result is not decided yet.
        assertEquals(
            "WF_EXPR_MISSING_REF",
            errorCode(logical("AND", trueLeft, obj("ref" to str("inputs.missing"))))
        )
        assertEquals(
            "WF_EXPR_MISSING_REF",
            errorCode(logical("OR", falseLeft, obj("ref" to str("inputs.missing"))))
        )
        assertTrue(boolResult(logical("AND", trueLeft, obj("literal" to bool(true)))))
        assertTrue(!boolResult(logical("AND", trueLeft, obj("literal" to bool(false)))))
        assertTrue(boolResult(logical("OR", falseLeft, obj("literal" to bool(true)))))
        assertTrue(!boolResult(logical("OR", falseLeft, obj("literal" to bool(false)))))

        assertEquals(
            "WF_EXPR_TYPE",
            errorCode(logical("AND", obj("literal" to str("x")), trueLeft))
        )
        // OR true decides the result, so the non-boolean right side is never inspected.
        assertTrue(boolResult(logical("OR", trueLeft, obj("literal" to str("x")))))
        assertEquals(
            "WF_EXPR_TYPE",
            errorCode(logical("OR", falseLeft, obj("literal" to str("x"))))
        )
    }

}

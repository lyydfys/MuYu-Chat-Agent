package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.runtime.AgentJsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** N18 MCA enhancement: closed, bounded LOGIC operations with typed errors and no eval. */
class WorkflowLogicTest {

    private fun value(result: WorkflowLogicResult): AgentJsonValue =
        (result as WorkflowLogicResult.Value).value

    private fun error(result: WorkflowLogicResult): String =
        (result as WorkflowLogicResult.Error).code

    @Test
    fun setReturnsTheConfiguredJsonValue() {
        val result = WorkflowLogic.execute("""{"operation":"SET","value":{"a":[1,2]}}""")
        assertEquals("""{"a":[1,2]}""", codecWrite(value(result)))
    }

    @Test
    fun pickWalksAnObjectAndAnArrayIndex() {
        val input = AgentJsonValue.Obj(
            listOf(
                "items" to AgentJsonValue.Arr(
                    listOf(
                        AgentJsonValue.Obj(listOf("name" to AgentJsonValue.Str("first"))),
                        AgentJsonValue.Obj(listOf("name" to AgentJsonValue.Str("second")))
                    )
                )
            )
        )
        val result = WorkflowLogic.execute("""{"operation":"PICK","path":"items.1.name"}""", listOf(input))
        assertEquals("second", (value(result) as AgentJsonValue.Str).value)
    }

    @Test
    fun pickRejectsMissingPathsAndDeepPaths() {
        val input = AgentJsonValue.Obj(listOf("a" to AgentJsonValue.Str("x")))
        assertEquals(
            "WF_LOGIC_PATH_INVALID",
            error(WorkflowLogic.execute("""{"operation":"PICK","path":"a.b"}""", listOf(input)))
        )
        assertEquals(
            "WF_LOGIC_PATH_MISSING",
            error(WorkflowLogic.execute("""{"operation":"PICK","path":"missing"}""", listOf(input)))
        )
        assertEquals(
            "WF_LOGIC_PATH_INVALID",
            error(
                WorkflowLogic.execute(
                    """{"operation":"PICK","path":"a.b.c.d.e.f.g.h.i"}""",
                    listOf(input)
                )
            )
        )
        assertEquals(
            "WF_LOGIC_INPUT_MISSING",
            error(WorkflowLogic.execute("""{"operation":"PICK","path":"a"}"""))
        )
    }

    @Test
    fun defaultUsesTheInputWhenPresentAndTheConfiguredValueOtherwise() {
        val input = AgentJsonValue.Str("present")
        assertEquals(
            "present",
            (value(WorkflowLogic.execute("""{"operation":"DEFAULT","value":"fallback"}""", listOf(input))) as AgentJsonValue.Str).value
        )
        assertEquals(
            "fallback",
            (value(WorkflowLogic.execute("""{"operation":"DEFAULT","value":"fallback"}""")) as AgentJsonValue.Str).value
        )
        assertEquals(
            "WF_LOGIC_INPUT_AMBIGUOUS",
            error(
                WorkflowLogic.execute(
                    """{"operation":"DEFAULT","value":"fallback"}""",
                    listOf(input, input)
                )
            )
        )
    }

    @Test
    fun concatRequiresStringInputAndStaysBounded() {
        assertEquals(
            "hello!",
            (value(WorkflowLogic.execute("""{"operation":"CONCAT","suffix":"!"}""", listOf(AgentJsonValue.Str("hello")))) as AgentJsonValue.Str).value
        )
        assertEquals(
            "WF_LOGIC_TYPE",
            error(WorkflowLogic.execute("""{"operation":"CONCAT","suffix":"!"}""", listOf(AgentJsonValue.Num("1"))))
        )
        // The config stays under 8 KiB while the concatenated output exceeds the output limit.
        val input = "x".repeat(5 * 1024)
        val suffix = "y".repeat(5 * 1024)
        assertEquals(
            "WF_LOGIC_LIMIT",
            error(
                WorkflowLogic.execute(
                    """{"operation":"CONCAT","suffix":"$suffix"}""",
                    listOf(AgentJsonValue.Str(input))
                )
            )
        )
        assertEquals(
            "WF_LOGIC_CONFIG",
            error(WorkflowLogic.execute("""{"operation":"SET","value":"${"z".repeat(9 * 1024)}"}"""))
        )
    }

    @Test
    fun unknownOperationsAndMalformedConfigAreTypedErrors() {
        assertEquals("WF_LOGIC_OP", error(WorkflowLogic.execute("""{"operation":"EVAL"}""")))
        assertEquals("WF_LOGIC_CONFIG", error(WorkflowLogic.execute("not json")))
        assertEquals("WF_LOGIC_CONFIG", error(WorkflowLogic.execute("""{"operation":"SET"}""")))
        assertTrue(
            WorkflowLogic.execute("""{"operation":"SET","value":1}""") is WorkflowLogicResult.Value
        )
    }

    @Test
    fun andReturnsFalseOnceDecidedAndSkipsALaterMissingOperand() {
        val result = WorkflowLogic.execute(
            """{"operation":"AND","operands":[{"input":0},{"input":1},{"input":9}]}""",
            listOf(AgentJsonValue.Bool(true), AgentJsonValue.Bool(false))
        )
        assertEquals(AgentJsonValue.Bool(false), value(result))
    }

    @Test
    fun orReturnsTrueOnceDecidedAndSkipsALaterMissingOperand() {
        val result = WorkflowLogic.execute(
            """{"operation":"OR","operands":[{"input":0},{"input":1},{"input":9}]}""",
            listOf(AgentJsonValue.Bool(false), AgentJsonValue.Bool(true))
        )
        assertEquals(AgentJsonValue.Bool(true), value(result))
    }

    @Test
    fun anUndecidedMissingOperandIsATypedErrorNotFalse() {
        assertEquals(
            "WF_LOGIC_INPUT_MISSING",
            error(
                WorkflowLogic.execute(
                    """{"operation":"AND","operands":[{"input":0},{"input":9}]}""",
                    listOf(AgentJsonValue.Bool(true))
                )
            )
        )
        assertEquals(
            "WF_LOGIC_TYPE",
            error(
                WorkflowLogic.execute(
                    """{"operation":"OR","operands":[{"input":0}]}""",
                    listOf(AgentJsonValue.Str("x"))
                )
            )
        )
        assertEquals(
            "WF_LOGIC_CONFIG",
            error(WorkflowLogic.execute("""{"operation":"AND","operands":[]}"""))
        )
        assertEquals(
            "WF_LOGIC_CONFIG",
            error(WorkflowLogic.execute("""{"operation":"AND"}"""))
        )
    }

    private fun codecWrite(value: AgentJsonValue): String =
        com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec().write(value)
}

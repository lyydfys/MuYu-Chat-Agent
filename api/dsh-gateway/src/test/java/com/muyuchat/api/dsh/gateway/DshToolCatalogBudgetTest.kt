package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.console.DSH_MAX_MODEL_VISIBLE_TOOLS
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP tool-catalog bound at the model-facing edge.
 *
 * The pinned DSH sends its complete visible tool catalog (built-in tools plus every
 * `mcp__<server>__<tool>` registered by `@deepseek-ai/dsh-mcp-client`) with each
 * chat-completions request. The gateway refuses a request above the shared budget instead of
 * silently dropping tools; this test pins the budget to the contract constant so the documented
 * MCP limit and the enforced gateway limit cannot drift.
 *
 * Scope: JVM request parsing only. It does not connect an MCP server and does not prove how many
 * tools the shipped DSH preset publishes at runtime.
 */
class DshToolCatalogBudgetTest {

    @Test
    fun theEnforcedBudgetIsTheContractConstant() {
        assertEquals(
            DSH_MAX_MODEL_VISIBLE_TOOLS,
            DshGatewayLimits.Default.maxToolsPerRequest
        )
    }

    @Test
    fun exactlyTheBudgetIsAcceptedAndOneMoreIsRefused() {
        val accepted = parse(chatBodyWithTools(DSH_MAX_MODEL_VISIBLE_TOOLS))
        assertTrue("expected success, got $accepted", accepted is DshOpenAiParse.Success)
        assertEquals(
            DSH_MAX_MODEL_VISIBLE_TOOLS,
            (accepted as DshOpenAiParse.Success).request.tools.size
        )

        val rejected = parse(chatBodyWithTools(DSH_MAX_MODEL_VISIBLE_TOOLS + 1))
        assertTrue("expected rejection, got $rejected", rejected is DshOpenAiParse.Rejected)
        rejected as DshOpenAiParse.Rejected
        assertEquals(400, rejected.status)
        assertEquals("too_many_tools", rejected.code)
        assertEquals("tools", rejected.param)
    }

    @Test
    fun theBudgetIsConfigurableOnlyWithinTheHardCeiling() {
        assertEquals(512, DshGatewayLimits(maxToolsPerRequest = 512).maxToolsPerRequest)
        val failure = runCatching { DshGatewayLimits(maxToolsPerRequest = 513) }.exceptionOrNull()
        assertTrue(
            "a budget above the hard ceiling must be refused at construction",
            failure is IllegalArgumentException
        )
    }

    private fun parse(body: String): DshOpenAiParse =
        DshOpenAiRequest.parse(body, callId = "dsh:budget:1", limits = DshGatewayLimits.Default)

    private fun chatBodyWithTools(count: Int): String {
        val tools = JSONArray()
        for (index in 0 until count) {
            tools.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", "tool_$index")
                            .put("parameters", JSONObject().put("type", "object"))
                    )
            )
        }
        return JSONObject()
            .put("model", "fake-model")
            .put("messages", JSONArray("""[{"role":"user","content":"hello"}]"""))
            .put("stream", false)
            .put("tools", tools)
            .toString()
    }
}

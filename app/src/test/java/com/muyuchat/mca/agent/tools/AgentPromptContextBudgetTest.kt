package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.runtime.protocol.AgentPromptedSystemPromptBuilder
import com.muyuchat.core.engine.estimateLocalPromptTokens
import com.muyuchat.core.engine.localContextWindowBudget
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompted system prompt carries the whole tool catalog, so it alone decides whether a new
 * Agent task can start on a small local model. On device the only loadable model exposes
 * n_ctx=8192, which admits a 7072-token prompt; every new task was rejected before generation.
 * This pins the shipped catalogs against that window so the regression is caught on the JVM.
 */
class AgentPromptContextBudgetTest {

    private val budget = localContextWindowBudget(8192)

    @Test
    fun `minimal catalog prompt fits the 8192-token local window`() = assertFits(
        "builtin",
        builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())
    )

    @Test
    fun `extended catalog with every device surface fits the 8192-token local window`() = assertFits(
        "extended+device",
        extendedAgentToolCatalog(
            filePort = FakeAgentWorkspaceFilePort(),
            deviceStatusSource = { error("not read while measuring") },
            deviceNotificationSink = object : DeviceNotificationSink {
                override fun canPost(): Boolean = false
                override fun post(notification: DeviceNotification): Boolean = false
            },
            deviceUrlLauncher = { false },
            deviceClipboardSink = { false },
            deviceSettingsLauncher = { false }
        )
    )

    private fun assertFits(label: String, catalog: AgentToolCatalog) {
        val tools = catalog.snapshot()
        val prompt = AgentPromptedSystemPromptBuilder().build(
            tools = tools,
            policyVersion = 1L,
            budgetSummaryJson = """{"maxSteps":8,"maxToolCalls":8,"maxBytes":65536}""",
            protocolMode = AgentProtocolMode.PROMPTED_V1
        )
        val estimate = estimateLocalPromptTokens(prompt)
        println(
            "PROMPT_BUDGET label=$label tools=${tools.size} chars=${prompt.length} " +
                "bytes=${prompt.toByteArray(Charsets.UTF_8).size} estimate=$estimate " +
                "promptBudget=${budget.promptBudgetTokens} " +
                "reservedOutput=${budget.reservedOutputTokens} headroom=${budget.headroomTokens}"
        )
        tools.sortedBy { it.toolKey }.forEach { tool ->
            println("PROMPT_TOOL label=$label key=${tool.toolKey} schemaChars=${tool.schemaJson.length}")
        }
        assertTrue(
            "$label prompt alone estimates $estimate tokens against a " +
                "${budget.promptBudgetTokens}-token window (n_ctx=8192)",
            estimate <= budget.promptBudgetTokens
        )
    }
}

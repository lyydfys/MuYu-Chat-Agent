package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * One read surface over two definition writers.
 *
 * Legacy recipes keep the `recipe-` namespace; graph drafts/published versions are separate and
 * immutable once published. A run that pinned a version must resolve that exact version even after
 * the draft moves on.
 */
class CompositeWorkflowDefinitionStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun graphDefinition(
        id: String = "graph-1",
        displayName: String = "Graph flow",
        nodes: List<WorkflowNode> = listOf(trigger(), tool(), output()),
        edges: List<WorkflowEdge> = listOf(
            WorkflowEdge("trigger", "gather"),
            WorkflowEdge("gather", "done")
        )
    ): WorkflowDefinition = WorkflowDefinition(
        definitionId = id,
        version = 1L,
        displayName = displayName,
        outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
        nodes = nodes,
        edges = edges
    )

    private fun trigger() = WorkflowNode("trigger", WorkflowNodeType.TRIGGER)

    private fun tool(id: String = "gather") = WorkflowNode(
        id,
        WorkflowNodeType.TOOL,
        configJson = """{"toolKey":"workspace.read_text"}"""
    )

    private fun output() = WorkflowNode(
        "done",
        WorkflowNodeType.OUTPUT,
        configJson = """{"port":"result"}"""
    )

    private fun recipe(id: String = "review", version: Long = 3L): AgentWorkflowRecipe =
        AgentWorkflowRecipe(
            id = id,
            version = version,
            title = "Legacy recipe",
            goalTemplate = "Summarise the notes",
            steps = listOf(
                AgentWorkflowStep(
                    id = "review",
                    check = AgentWorkflowCheck.GOAL_BOUNDED,
                    title = "Review the goal"
                )
            )
        )

    private fun graphStore(): FileAgentWorkflowDefinitionStore =
        FileAgentWorkflowDefinitionStore(folder.newFolder("definitions"))

    private fun composite(
        graph: FileAgentWorkflowDefinitionStore = graphStore(),
        recipes: FileAgentWorkflowRecipeStore = FileAgentWorkflowRecipeStore(folder.newFolder("recipes"))
    ): CompositeWorkflowDefinitionStore = CompositeWorkflowDefinitionStore(
        recipes = RecipeWorkflowDefinitionStore(recipes),
        graphStore = graph
    )

    @Test
    fun `list merges both writers and filters nothing from the read surface`() {
        val recipes = FileAgentWorkflowRecipeStore(folder.newFolder("recipes"))
        recipes.publish(recipe(), nowMillis = 1_000L)
        val graph = graphStore()
        graph.saveDraft(graphDefinition(), expectedRevision = null, nowMillis = 1_100L)

        val summaries = composite(graph = graph, recipes = recipes).list()

        assertEquals(2, summaries.size)
        val byId = summaries.associateBy { it.definitionId }
        assertEquals(3L, byId.getValue("recipe-review").latestPublishedVersion)
        assertEquals(0L, byId.getValue("graph-1").latestPublishedVersion)
        assertEquals(1L, byId.getValue("graph-1").draftRevision)
    }

    @Test
    fun `reads route by the reserved recipe prefix`() {
        val recipes = FileAgentWorkflowRecipeStore(folder.newFolder("recipes"))
        recipes.publish(recipe(version = 3L), nowMillis = 1_000L)
        val graph = graphStore()
        graph.saveDraft(graphDefinition(id = "graph-1"), expectedRevision = null, nowMillis = 1_100L)
        graph.publish("graph-1", expectedDraftRevision = 1L, nowMillis = 1_200L)
        val store = composite(graph = graph, recipes = recipes)

        assertEquals(3L, store.version("recipe-review", 3L)!!.version)
        assertEquals("graph-1", store.latest("graph-1")!!.definitionId)
        assertNull(store.latest("recipe-missing"))
        assertNull(store.latest("graph-missing"))
        assertNull(store.version("recipe-review", 1L))
        assertNull(store.draft("recipe-review"))
    }

    @Test
    fun `a published graph version stays pinned while the draft moves`() {
        val store = composite()
        store.saveDraft(graphDefinition(), expectedRevision = null, nowMillis = 1_000L)
        val published = store.publish("graph-1", expectedDraftRevision = 1L, nowMillis = 1_100L)
        store.saveDraft(
            graphDefinition(displayName = "Renamed flow"),
            expectedRevision = 1L,
            nowMillis = 1_200L
        )
        val second = store.publish("graph-1", expectedDraftRevision = 2L, nowMillis = 1_300L)

        assertEquals(2L, second.version)
        assertNotEquals(published.definitionHash, second.definitionHash)
        assertEquals("Graph flow", store.version("graph-1", 1L)!!.displayName)
        assertEquals(published.definitionHash, store.version("graph-1", 1L)!!.definitionHash)
        assertEquals("Renamed flow", store.latest("graph-1")!!.displayName)
    }

    @Test
    fun `graph writes are refused for a legacy recipe id`() {
        val store = composite()

        val save = runCatching {
            store.saveDraft(
                graphDefinition(id = "recipe-review"),
                expectedRevision = null,
                nowMillis = 1_000L
            )
        }.exceptionOrNull() as AgentWorkflowException
        assertEquals(AgentWorkflowErrorCodes.RECIPE_INVALID, save.code)

        val publish = runCatching {
            store.publish("recipe-review", expectedDraftRevision = 1L, nowMillis = 1_000L)
        }.exceptionOrNull() as AgentWorkflowException
        assertEquals(AgentWorkflowErrorCodes.RECIPE_INVALID, publish.code)
    }

    @Test
    fun `a graph id cannot claim the recipe prefix through its own definition`() {
        val store = composite()
        val refusal = runCatching {
            store.saveDraft(
                graphDefinition(id = "recipe-hijack"),
                expectedRevision = null,
                nowMillis = 1_000L
            )
        }.exceptionOrNull() as AgentWorkflowException

        assertEquals(AgentWorkflowErrorCodes.RECIPE_INVALID, refusal.code)
        assertTrue(store.list().isEmpty())
    }
}

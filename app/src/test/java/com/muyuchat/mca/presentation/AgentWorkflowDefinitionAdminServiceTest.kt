package com.muyuchat.mca.presentation

import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import com.muyuchat.feature.agent.workspace.AgentWorkflowDefinitionItem
import com.muyuchat.feature.agent.workspace.AgentWorkflowDefinitionsResult
import com.muyuchat.feature.agent.workspace.AgentWorkflowDraftResult
import com.muyuchat.feature.agent.workspace.AgentWorkflowPublishOutcome
import com.muyuchat.mca.agent.workflow.AgentWorkflowErrorCodes
import com.muyuchat.mca.agent.workflow.AgentWorkflowLauncher
import com.muyuchat.mca.agent.workflow.AgentWorkflowRecipe
import com.muyuchat.mca.agent.workflow.AgentWorkflowStep
import com.muyuchat.mca.agent.workflow.AgentWorkflowCheck
import com.muyuchat.mca.agent.workflow.AgentWorkflowTaskCreation
import com.muyuchat.mca.agent.workflow.AgentWorkflowTaskGateway
import com.muyuchat.mca.agent.workflow.CompositeWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.FileAgentWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.FileAgentWorkflowRecipeStore
import com.muyuchat.mca.agent.workflow.RecipeWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.WorkflowDefinitionJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The production management adapter over the immutable draft/publish repository.
 *
 * The service is the only caller the page uses: it validates JSON, CASes draft revisions and
 * publishes immutable versions while the legacy recipe rows keep their own store.
 */
class AgentWorkflowDefinitionAdminServiceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000L

    private class UnusedGateway : AgentWorkflowTaskGateway {
        override suspend fun createTask(command: CreateAgentTask): AgentWorkflowTaskCreation =
            error("definition management must not create a task")

        override suspend fun startRun(command: StartAgentRun): AgentCommandResult =
            error("definition management must not start a run")
    }

    private fun definition(
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

    private fun json(definition: WorkflowDefinition): String =
        WorkflowDefinitionJson.encode(definition).toString()

    private fun service(
        graph: FileAgentWorkflowDefinitionStore,
        recipes: FileAgentWorkflowRecipeStore = FileAgentWorkflowRecipeStore(folder.newFolder("recipes"))
    ): AgentWorkflowAdminService {
        val store = CompositeWorkflowDefinitionStore(
            recipes = RecipeWorkflowDefinitionStore(recipes),
            graphStore = graph
        )
        return AgentWorkflowAdminService(
            store = recipes,
            launcher = AgentWorkflowLauncher(recipes, UnusedGateway()),
            ioDispatcher = Dispatchers.Unconfined,
            clock = { now },
            idFactory = { "fixed-id" },
            definitions = store
        )
    }

    private fun graphStore(): FileAgentWorkflowDefinitionStore =
        FileAgentWorkflowDefinitionStore(folder.newFolder("definitions"))

    @Test
    fun `definitions list hides legacy recipes and shows graph drafts`() = runTest {
        val recipes = FileAgentWorkflowRecipeStore(folder.newFolder("recipes"))
        recipes.publish(
            AgentWorkflowRecipe(
                id = "review",
                version = 3L,
                title = "Legacy recipe",
                goalTemplate = "Summarise",
                steps = listOf(AgentWorkflowStep("goal", AgentWorkflowCheck.GOAL_BOUNDED, "Goal"))
            ),
            nowMillis = 500L
        )
        val graph = graphStore()
        graph.saveDraft(definition(), expectedRevision = null, nowMillis = 600L)
        val service = service(graph, recipes)

        val result = service.workflowDefinitions()

        assertTrue(result is AgentWorkflowDefinitionsResult.Loaded)
        val rows = (result as AgentWorkflowDefinitionsResult.Loaded).definitions
        assertEquals(listOf("graph-1"), rows.map { it.definitionId })
        assertEquals(1L, rows.single().draftRevision)
        assertTrue(rows.filterIsInstance<AgentWorkflowDefinitionItem>().isNotEmpty())
    }

    @Test
    fun `save draft round trips and publish freezes the version`() = runTest {
        val graph = graphStore()
        val service = service(graph)

        val first = service.saveWorkflowDraft(json(definition()), expectedRevision = null)
        assertTrue(first is AgentWorkflowDraftResult.Saved)
        assertEquals(1L, (first as AgentWorkflowDraftResult.Saved).revision)

        val loaded = service.workflowDraft("graph-1")
        assertTrue(loaded is AgentWorkflowDraftResult.Loaded)
        assertEquals(1L, (loaded as AgentWorkflowDraftResult.Loaded).draft.revision)
        assertTrue(loaded.draft.definitionJson.contains("\"definitionId\":\"graph-1\""))

        now = 2_000L
        val published = service.publishWorkflowDefinition("graph-1", expectedDraftRevision = 1L)
        assertTrue(published is AgentWorkflowPublishOutcome.Published)
        val version1 = published as AgentWorkflowPublishOutcome.Published
        assertEquals(1L, version1.version)
        assertEquals(64, version1.definitionHash.length)

        val second = service.saveWorkflowDraft(
            json(definition(displayName = "Renamed flow")),
            expectedRevision = 1L
        )
        assertEquals(2L, (second as AgentWorkflowDraftResult.Saved).revision)
        val published2 = service.publishWorkflowDefinition("graph-1", expectedDraftRevision = 2L)
            as AgentWorkflowPublishOutcome.Published
        assertEquals(2L, published2.version)
        assertNotEquals(version1.definitionHash, published2.definitionHash)
        assertEquals(version1.definitionHash, graph.version("graph-1", 1L)!!.definitionHash)
        assertEquals("Graph flow", graph.version("graph-1", 1L)!!.displayName)
    }

    @Test
    fun `a stale draft revision is rejected without overwriting`() = runTest {
        val service = service(graphStore())
        service.saveWorkflowDraft(json(definition()), expectedRevision = null)

        val stale = service.saveWorkflowDraft(json(definition(displayName = "Other")), expectedRevision = 9L)

        assertTrue(stale is AgentWorkflowDraftResult.Rejected)
        assertEquals(
            AgentWorkflowErrorCodes.VERSION_CONFLICT,
            (stale as AgentWorkflowDraftResult.Rejected).code
        )
        val snapshot = service.workflowDraft("graph-1") as AgentWorkflowDraftResult.Loaded
        assertTrue(snapshot.draft.definitionJson.contains("Graph flow"))
    }

    @Test
    fun `malformed json and the reserved recipe prefix are rejected`() = runTest {
        val service = service(graphStore())

        val malformed = service.saveWorkflowDraft("{not json", expectedRevision = null)
        assertEquals(
            AgentWorkflowErrorCodes.RECIPE_INVALID,
            (malformed as AgentWorkflowDraftResult.Rejected).code
        )

        val reserved = service.saveWorkflowDraft(
            json(definition(id = "recipe-review")),
            expectedRevision = null
        )
        assertEquals(
            AgentWorkflowErrorCodes.RECIPE_INVALID,
            (reserved as AgentWorkflowDraftResult.Rejected).code
        )

        val missingDraft = service.publishWorkflowDefinition("missing", expectedDraftRevision = 1L)
        assertEquals(
            AgentWorkflowErrorCodes.NOT_FOUND,
            (missingDraft as AgentWorkflowPublishOutcome.Rejected).code
        )
    }

    @Test
    fun `publishing resolves a pinned subworkflow version`() = runTest {
        val graph = graphStore()
        val child = WorkflowDefinition(
            definitionId = "child-1",
            version = 1L,
            displayName = "Child flow",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(trigger(), output()),
            edges = listOf(WorkflowEdge("trigger", "done"))
        )
        graph.saveDraft(child, expectedRevision = null, nowMillis = 100L)
        graph.publish("child-1", expectedDraftRevision = 1L, nowMillis = 200L)
        val parent = definition(
            id = "parent-1",
            nodes = listOf(
                trigger(),
                WorkflowNode(
                    "sub",
                    WorkflowNodeType.SUBWORKFLOW,
                    subworkflowDefinitionId = "child-1",
                    subworkflowVersion = 1L
                ),
                output()
            ),
            edges = listOf(
                WorkflowEdge("trigger", "sub"),
                WorkflowEdge("sub", "done")
            )
        )
        val service = service(graph)
        service.saveWorkflowDraft(json(parent), expectedRevision = null)

        val published = service.publishWorkflowDefinition("parent-1", expectedDraftRevision = 1L)

        assertTrue(published is AgentWorkflowPublishOutcome.Published)
        assertEquals(1L, (published as AgentWorkflowPublishOutcome.Published).version)
        assertEquals("child-1", graph.version("child-1", 1L)!!.definitionId)
    }

    @Test
    fun `a pinned subworkflow version that is absent blocks publish`() = runTest {
        val graph = graphStore()
        val parent = definition(
            id = "parent-1",
            nodes = listOf(
                trigger(),
                WorkflowNode(
                    "sub",
                    WorkflowNodeType.SUBWORKFLOW,
                    subworkflowDefinitionId = "child-1",
                    subworkflowVersion = 7L
                ),
                output()
            ),
            edges = listOf(
                WorkflowEdge("trigger", "sub"),
                WorkflowEdge("sub", "done")
            )
        )
        val service = service(graph)
        service.saveWorkflowDraft(json(parent), expectedRevision = null)

        val published = service.publishWorkflowDefinition("parent-1", expectedDraftRevision = 1L)

        assertEquals(
            AgentWorkflowErrorCodes.RECIPE_INVALID,
            (published as AgentWorkflowPublishOutcome.Rejected).code
        )
        assertEquals(null, graph.latest("parent-1"))
    }
}

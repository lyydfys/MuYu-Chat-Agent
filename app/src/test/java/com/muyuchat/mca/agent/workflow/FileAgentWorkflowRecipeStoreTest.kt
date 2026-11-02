package com.muyuchat.mca.agent.workflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileAgentWorkflowRecipeStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val clockValue = 1_760_000_000_000L

    @Test
    fun roundTripSurvivesReopen() {
        val root = temporary.newFolder("workflows")
        val store = FileAgentWorkflowRecipeStore(root)
        val published = store.publish(testWorkflowRecipe(), clockValue)

        val reopened = FileAgentWorkflowRecipeStore(root)
        val loaded = reopened.latest(published.recipe.id)
        assertEquals(published, loaded)
        assertEquals(AgentWorkflowRecipeState.ACTIVE, loaded!!.state)
        assertEquals(1L, loaded.revision)
    }

    @Test
    fun newerVersionBecomesTheOnlyListedVersion() {
        val store = FileAgentWorkflowRecipeStore(temporary.newFolder("workflows"))
        store.publish(testWorkflowRecipe(version = 1L), clockValue)
        val second = store.publish(
            testWorkflowRecipe(version = 2L, title = "每周回顾 v2"),
            clockValue + 1L
        )
        assertEquals(2L, store.latest(second.recipe.id)!!.recipe.version)
        assertEquals(listOf(2L), store.list().map { it.recipe.version })
        assertEquals(2L, FileAgentWorkflowRecipeStore(temporary.root.resolve("workflows"))
            .latest(second.recipe.id)!!.recipe.version)
    }

    @Test
    fun duplicateVersionIsRejected() {
        val store = FileAgentWorkflowRecipeStore(temporary.newFolder("workflows"))
        store.publish(testWorkflowRecipe(version = 1L), clockValue)
        assertCode(AgentWorkflowErrorCodes.DUPLICATE_ID) {
            store.publish(testWorkflowRecipe(version = 1L), clockValue + 1L)
        }
    }

    @Test
    fun lowerVersionIsRejected() {
        val store = FileAgentWorkflowRecipeStore(temporary.newFolder("workflows"))
        store.publish(testWorkflowRecipe(version = 2L), clockValue)
        assertCode(AgentWorkflowErrorCodes.VERSION_CONFLICT) {
            store.publish(testWorkflowRecipe(version = 1L), clockValue + 1L)
        }
    }

    @Test
    fun archiveAndRevokeBlockPublishingAndAreVisibleToTheLauncher() {
        val store = FileAgentWorkflowRecipeStore(temporary.newFolder("workflows"))
        store.publish(testWorkflowRecipe(), clockValue)
        assertEquals(
            AgentWorkflowRecipeState.ARCHIVED,
            store.archive("weekly-review", clockValue + 1L).state
        )
        assertEquals(AgentWorkflowRecipeState.ARCHIVED, store.latest("weekly-review")!!.state)
        assertEquals(
            AgentWorkflowRecipeState.REVOKED,
            store.revoke("weekly-review", clockValue + 2L).state
        )
        assertCode(AgentWorkflowErrorCodes.REVOKED) {
            store.publish(testWorkflowRecipe(version = 2L), clockValue + 3L)
        }
    }

    @Test
    fun listIncludesInactiveRecordsOnlyWhenRequested() {
        val store = FileAgentWorkflowRecipeStore(temporary.newFolder("workflows"))
        store.publish(testWorkflowRecipe(id = "active"), clockValue)
        store.publish(testWorkflowRecipe(id = "archived"), clockValue)
        store.archive("archived", clockValue + 1L)

        assertEquals(listOf("active"), store.list().map { it.recipe.id })
        assertEquals(
            listOf("active", "archived"),
            store.list(includeInactive = true).map { it.recipe.id }
        )
    }

    @Test
    fun corruptFileFailsClosedInsteadOfDroppingRecipes() {
        val root = temporary.newFolder("workflows")
        File(root, "workflow-recipes.json").writeText("{not-json", Charsets.UTF_8)
        assertCode(AgentWorkflowErrorCodes.STORE_CORRUPT) {
            FileAgentWorkflowRecipeStore(root)
        }
    }

    @Test
    fun unknownStoreFieldFailsClosed() {
        val root = temporary.newFolder("workflows")
        File(root, "workflow-recipes.json").writeText(
            "{\"schemaVersion\":1,\"evil\":true,\"recipes\":[]}",
            Charsets.UTF_8
        )
        assertCode(AgentWorkflowErrorCodes.STORE_CORRUPT) {
            FileAgentWorkflowRecipeStore(root)
        }
    }

    @Test
    fun atomicWriteLeavesNoTemporaryFilesBehind() {
        val root = temporary.newFolder("workflows")
        val store = FileAgentWorkflowRecipeStore(root)
        store.publish(testWorkflowRecipe(), clockValue)
        store.archive("weekly-review", clockValue + 1L)
        store.revoke("weekly-review", clockValue + 2L)

        assertTrue(root.listFiles().orEmpty().none { it.name.contains(".tmp") })
        assertFalse(File(root, "workflow-recipes.json").readText(Charsets.UTF_8).isBlank())
    }

    @Test
    fun recipeLimitIsEnforced() {
        val store = FileAgentWorkflowRecipeStore(temporary.newFolder("workflows"))
        repeat(AgentWorkflowRecipeLimits.MAX_RECIPES) { index ->
            store.publish(
                testWorkflowRecipe(id = "recipe-$index", title = "配方 $index"),
                clockValue + index
            )
        }
        assertCode(AgentWorkflowErrorCodes.LIMIT_EXCEEDED) {
            store.publish(testWorkflowRecipe(id = "recipe-overflow"), clockValue + 100L)
        }
    }

    @Test
    fun missingRecipeIsNotFound() {
        val store = FileAgentWorkflowRecipeStore(temporary.newFolder("workflows"))
        assertNull(store.latest("missing"))
        assertCode(AgentWorkflowErrorCodes.NOT_FOUND) {
            store.archive("missing", clockValue)
        }
    }

    private fun assertCode(expected: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected a typed workflow failure, got $failure", failure is AgentWorkflowException)
        assertEquals(expected, (failure as AgentWorkflowException).code)
    }
}

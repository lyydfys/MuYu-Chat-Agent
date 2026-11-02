package com.muyuchat.mca.agent.workflow

import java.util.Locale

/**
 * Narrow persistence port for versioned task recipes.
 *
 * A recipe version is immutable once published. [publish] accepts a brand-new id at version 1 or a
 * higher version of an existing id; equal/lower versions are rejected. [archive] and [revoke] only
 * change the record state used by the launch preflight — they never delete history or touch a task.
 */
interface AgentWorkflowRecipeStore {
    /** Latest version per recipe id, sorted by title then id. Inactive records are opt-in. */
    fun list(includeInactive: Boolean = false): List<AgentWorkflowRecipeRecord>

    /** Latest version for [recipeId], regardless of its state. */
    fun latest(recipeId: String): AgentWorkflowRecipeRecord?

    fun publish(recipe: AgentWorkflowRecipe, nowMillis: Long): AgentWorkflowRecipeRecord

    fun archive(recipeId: String, nowMillis: Long): AgentWorkflowRecipeRecord

    fun revoke(recipeId: String, nowMillis: Long): AgentWorkflowRecipeRecord
}

/**
 * Deterministic in-memory store for tests and preview wiring. Production uses
 * [FileAgentWorkflowRecipeStore]; both share the same semantics.
 */
class InMemoryAgentWorkflowRecipeStore(
    initial: List<AgentWorkflowRecipeRecord> = emptyList()
) : AgentWorkflowRecipeStore {
    private val lock = Any()
    private val records = LinkedHashMap<String, AgentWorkflowRecipeRecord>()

    init {
        initial.forEach { record -> records[key(record.recipe)] = record }
    }

    override fun list(includeInactive: Boolean): List<AgentWorkflowRecipeRecord> = synchronized(lock) {
        latestPerIdLocked()
            .filter { includeInactive || it.active }
            .sortedWith(compareBy({ it.recipe.title.lowercase(Locale.ROOT) }, { it.recipe.id }))
    }

    override fun latest(recipeId: String): AgentWorkflowRecipeRecord? = synchronized(lock) {
        latestForIdLocked(recipeId)
    }

    override fun publish(recipe: AgentWorkflowRecipe, nowMillis: Long): AgentWorkflowRecipeRecord =
        synchronized(lock) {
            validatePublishLocked(recipe)
            val latest = latestForIdLocked(recipe.id)
            val record = AgentWorkflowRecipeRecord(
                recipe = recipe,
                state = AgentWorkflowRecipeState.ACTIVE,
                revision = (latest?.revision ?: 0L) + 1L,
                updatedAtMillis = nowMillis.coerceAtLeast(0L)
            )
            records[key(recipe)] = record
            record
        }

    override fun archive(recipeId: String, nowMillis: Long): AgentWorkflowRecipeRecord =
        synchronized(lock) {
            val latest = requireLatestLocked(recipeId)
            if (latest.state == AgentWorkflowRecipeState.REVOKED) {
                workflowFailure(AgentWorkflowErrorCodes.REVOKED, "recipe '$recipeId' is revoked")
            }
            if (latest.state == AgentWorkflowRecipeState.ARCHIVED) return latest
            latest.copy(
                state = AgentWorkflowRecipeState.ARCHIVED,
                revision = latest.revision + 1L,
                updatedAtMillis = nowMillis.coerceAtLeast(0L)
            ).also { records[key(it.recipe)] = it }
        }

    override fun revoke(recipeId: String, nowMillis: Long): AgentWorkflowRecipeRecord =
        synchronized(lock) {
            val latest = requireLatestLocked(recipeId)
            if (latest.state == AgentWorkflowRecipeState.REVOKED) return latest
            latest.copy(
                state = AgentWorkflowRecipeState.REVOKED,
                revision = latest.revision + 1L,
                updatedAtMillis = nowMillis.coerceAtLeast(0L)
            ).also { records[key(it.recipe)] = it }
        }

    private fun validatePublishLocked(recipe: AgentWorkflowRecipe) {
        val latest = latestForIdLocked(recipe.id)
        if (latest == null) {
            if (latestPerIdLocked().size >= AgentWorkflowRecipeLimits.MAX_RECIPES) {
                workflowFailure(
                    AgentWorkflowErrorCodes.LIMIT_EXCEEDED,
                    "recipe limit ${AgentWorkflowRecipeLimits.MAX_RECIPES} reached"
                )
            }
            return
        }
        if (latest.state == AgentWorkflowRecipeState.REVOKED) {
            workflowFailure(AgentWorkflowErrorCodes.REVOKED, "cannot publish over a revoked recipe")
        }
        when {
            recipe.version == latest.recipe.version -> workflowFailure(
                AgentWorkflowErrorCodes.DUPLICATE_ID,
                "recipe '${recipe.id}' version ${recipe.version} already exists"
            )

            recipe.version < latest.recipe.version -> workflowFailure(
                AgentWorkflowErrorCodes.VERSION_CONFLICT,
                "recipe '${recipe.id}' version ${recipe.version} precedes ${latest.recipe.version}"
            )
        }
        val versions = records.values.count { it.recipe.id == recipe.id }
        if (versions >= AgentWorkflowRecipeLimits.MAX_VERSIONS_PER_RECIPE) {
            workflowFailure(
                AgentWorkflowErrorCodes.LIMIT_EXCEEDED,
                "recipe '${recipe.id}' exceeds its version history limit"
            )
        }
    }

    private fun requireLatestLocked(recipeId: String): AgentWorkflowRecipeRecord =
        latestForIdLocked(recipeId)
            ?: workflowFailure(AgentWorkflowErrorCodes.NOT_FOUND, "recipe '$recipeId' is not installed")

    private fun latestForIdLocked(recipeId: String): AgentWorkflowRecipeRecord? =
        records.values
            .filter { it.recipe.id == recipeId }
            .maxByOrNull { it.recipe.version }

    private fun latestPerIdLocked(): List<AgentWorkflowRecipeRecord> =
        records.values
            .groupBy { it.recipe.id }
            .values
            .mapNotNull { versions -> versions.maxByOrNull { it.recipe.version } }

    private fun key(recipe: AgentWorkflowRecipe): String = "${recipe.id}@${recipe.version}"
}

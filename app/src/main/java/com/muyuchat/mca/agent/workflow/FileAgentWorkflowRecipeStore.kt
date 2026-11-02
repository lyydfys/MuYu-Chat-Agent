package com.muyuchat.mca.agent.workflow

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Durable task-recipe store backed by one private JSON file.
 *
 * The file holds only bounded recipe metadata and lifecycle state. Every mutation is staged in a
 * temp file, flushed, and moved into place atomically; a failed write rolls the in-memory map back
 * to its previous state. A corrupt file fails closed with [AgentWorkflowErrorCodes.STORE_CORRUPT]
 * instead of silently dropping recipes. Body/archive parsing is strict
 * ([AgentWorkflowRecipeParser] plus exact record keys).
 */
class FileAgentWorkflowRecipeStore(
    private val rootDirectory: File,
    private val fileName: String = "workflow-recipes.json"
) : AgentWorkflowRecipeStore {
    private val lock = Any()
    private val root: File = rootDirectory.absoluteFile
    private val file: File = File(root, fileName).also { target ->
        require(target.canonicalFile.parentFile == root.canonicalFile) {
            "workflow store file must stay inside the private root"
        }
    }
    private val records = LinkedHashMap<String, AgentWorkflowRecipeRecord>()

    init {
        root.mkdirs()
        if (!root.isDirectory) {
            workflowFailure(AgentWorkflowErrorCodes.STORE_FAILURE, "workflow store root is unavailable")
        }
        if (file.isFile) load()
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
            mutateLocked { records[key(recipe)] = record }
            record
        }

    override fun archive(recipeId: String, nowMillis: Long): AgentWorkflowRecipeRecord =
        synchronized(lock) {
            val latest = requireLatestLocked(recipeId)
            if (latest.state == AgentWorkflowRecipeState.REVOKED) {
                workflowFailure(AgentWorkflowErrorCodes.REVOKED, "recipe '$recipeId' is revoked")
            }
            if (latest.state == AgentWorkflowRecipeState.ARCHIVED) return latest
            val updated = latest.copy(
                state = AgentWorkflowRecipeState.ARCHIVED,
                revision = latest.revision + 1L,
                updatedAtMillis = nowMillis.coerceAtLeast(0L)
            )
            mutateLocked { records[key(updated.recipe)] = updated }
            updated
        }

    override fun revoke(recipeId: String, nowMillis: Long): AgentWorkflowRecipeRecord =
        synchronized(lock) {
            val latest = requireLatestLocked(recipeId)
            if (latest.state == AgentWorkflowRecipeState.REVOKED) return latest
            val updated = latest.copy(
                state = AgentWorkflowRecipeState.REVOKED,
                revision = latest.revision + 1L,
                updatedAtMillis = nowMillis.coerceAtLeast(0L)
            )
            mutateLocked { records[key(updated.recipe)] = updated }
            updated
        }

    // ------------------------------------------------------------------ load

    private fun load() {
        if (file.length() > AgentWorkflowRecipeLimits.MAX_STORE_BYTES) {
            workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow store exceeds its byte limit")
        }
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (failure: Exception) {
            workflowFailure(AgentWorkflowErrorCodes.STORE_FAILURE, "workflow store could not be read", failure)
        }
        if (text.toByteArray(Charsets.UTF_8).size > AgentWorkflowRecipeLimits.MAX_STORE_BYTES) {
            workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow store exceeds its byte limit")
        }
        try {
            val json = JSONObject(text)
            json.requireStoreKeys()
            val schemaVersion = json.requireStoreInt("schemaVersion")
            if (schemaVersion != AgentWorkflowRecipeLimits.SCHEMA_VERSION) {
                workflowFailure(
                    AgentWorkflowErrorCodes.STORE_CORRUPT,
                    "unsupported workflow store schemaVersion $schemaVersion"
                )
            }
            val array = json.get("recipes") as? JSONArray
                ?: workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow store recipes is not an array")
            val maxRecords = AgentWorkflowRecipeLimits.MAX_RECIPES *
                AgentWorkflowRecipeLimits.MAX_VERSIONS_PER_RECIPE
            if (array.length() > maxRecords) {
                workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow store has too many records")
            }
            val loaded = LinkedHashMap<String, AgentWorkflowRecipeRecord>()
            for (index in 0 until array.length()) {
                val recordJson = array.get(index) as? JSONObject
                    ?: workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow record is not an object")
                val record = decodeRecord(recordJson)
                val recordKey = key(record.recipe)
                if (loaded.containsKey(recordKey)) {
                    workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "duplicate workflow record '$recordKey'")
                }
                loaded[recordKey] = record
            }
            records.clear()
            records.putAll(loaded)
            val distinct = loaded.values.map { it.recipe.id }.distinct().size
            if (distinct > AgentWorkflowRecipeLimits.MAX_RECIPES) {
                workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow store has too many recipes")
            }
            loaded.values.groupBy { it.recipe.id }.forEach { (recipeId, versions) ->
                if (versions.size > AgentWorkflowRecipeLimits.MAX_VERSIONS_PER_RECIPE) {
                    workflowFailure(
                        AgentWorkflowErrorCodes.STORE_CORRUPT,
                        "workflow '$recipeId' has too many versions"
                    )
                }
            }
        } catch (failure: AgentWorkflowException) {
            records.clear()
            throw failure
        } catch (failure: Exception) {
            records.clear()
            workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow store is corrupt", failure)
        }
    }

    private fun decodeRecord(json: JSONObject): AgentWorkflowRecipeRecord {
        json.requireRecordKeys()
        val recipeJson = json.get("recipe") as? JSONObject
            ?: workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow record recipe is not an object")
        val stateRaw = json.get("state") as? String
            ?: workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow record state is not a string")
        val state = AgentWorkflowRecipeState.entries.firstOrNull { it.name == stateRaw }
            ?: workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "unsupported workflow record state")
        return try {
            AgentWorkflowRecipeRecord(
                recipe = AgentWorkflowRecipeParser.decodeRecipe(recipeJson),
                state = state,
                revision = json.getLong("revision"),
                updatedAtMillis = json.getLong("updatedAtMillis")
            )
        } catch (failure: AgentWorkflowException) {
            throw AgentWorkflowException(
                AgentWorkflowErrorCodes.STORE_CORRUPT,
                "workflow record is invalid: ${failure.message}",
                cause = failure
            )
        } catch (failure: JSONException) {
            workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow record is invalid", failure)
        } catch (failure: IllegalArgumentException) {
            workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow record is invalid", failure)
        }
    }

    // ------------------------------------------------------------------ persist

    private fun mutateLocked(mutation: () -> Unit) {
        val before = LinkedHashMap(records)
        mutation()
        try {
            persistLocked()
        } catch (failure: Exception) {
            records.clear()
            records.putAll(before)
            throw failure
        }
    }

    private fun persistLocked() {
        root.mkdirs()
        val array = JSONArray()
        records.values
            .sortedWith(compareBy({ it.recipe.id }, { it.recipe.version }))
            .forEach { record -> array.put(encodeRecord(record)) }
        val payload = JSONObject()
            .put("schemaVersion", AgentWorkflowRecipeLimits.SCHEMA_VERSION)
            .put("recipes", array)
            .toString()
        if (payload.toByteArray(Charsets.UTF_8).size > AgentWorkflowRecipeLimits.MAX_STORE_BYTES) {
            workflowFailure(AgentWorkflowErrorCodes.LIMIT_EXCEEDED, "workflow store exceeds its byte limit")
        }
        val temp = File(root, "$fileName.${UUID.randomUUID()}.tmp")
        try {
            writeSynced(temp, payload)
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: AgentWorkflowException) {
            temp.delete()
            throw failure
        } catch (failure: Exception) {
            temp.delete()
            workflowFailure(AgentWorkflowErrorCodes.STORE_FAILURE, "workflow store could not be written", failure)
        }
    }

    private fun encodeRecord(record: AgentWorkflowRecipeRecord): JSONObject = JSONObject()
        .put("recipe", AgentWorkflowRecipeParser.encodeRecipe(record.recipe))
        .put("state", record.state.name)
        .put("revision", record.revision)
        .put("updatedAtMillis", record.updatedAtMillis)

    private fun writeSynced(target: File, payload: String) {
        FileOutputStream(target).use { output ->
            output.write(payload.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    // ------------------------------------------------------------------ lookup / validation

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

    private fun JSONObject.requireStoreKeys() {
        val allowed = setOf("schemaVersion", "recipes")
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key !in allowed) {
                workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow store has unknown field '$key'")
            }
        }
    }

    private fun JSONObject.requireRecordKeys() {
        val allowed = setOf("recipe", "state", "revision", "updatedAtMillis")
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key !in allowed) {
                workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow record has unknown field '$key'")
            }
        }
    }

    private fun JSONObject.requireStoreInt(name: String): Int {
        if (!has(name) || isNull(name)) {
            workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "workflow store is missing '$name'")
        }
        return getInt(name)
    }
}

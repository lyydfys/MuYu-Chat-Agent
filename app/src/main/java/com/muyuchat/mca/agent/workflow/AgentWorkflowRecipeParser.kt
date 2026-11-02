package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentProtocolMode
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Strict JSON codec for [AgentWorkflowRecipe] v1.
 *
 * Rules: exact schema version, exact known fields at every level, exact JSON types, bounded
 * strings/arrays, canonical sorted-and-unique lists and no executable/script/node/trigger field.
 * Unknown fields are rejected instead of being ignored, and every failure is a typed
 * [AgentWorkflowException] that never echoes a raw parser exception to the UI.
 */
object AgentWorkflowRecipeParser {

    fun parse(raw: String): AgentWorkflowRecipe {
        if (raw.toByteArray(Charsets.UTF_8).size > AgentWorkflowRecipeLimits.MAX_RECIPE_BYTES) {
            workflowFailure(AgentWorkflowErrorCodes.LIMIT_EXCEEDED, "recipe definition exceeds its byte limit")
        }
        val json = try {
            JSONObject(raw)
        } catch (failure: JSONException) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "recipe is not a JSON object", failure)
        }
        return decodeRecipe(json)
    }

    fun encode(recipe: AgentWorkflowRecipe): String {
        val encoded = encodeRecipe(recipe).toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > AgentWorkflowRecipeLimits.MAX_RECIPE_BYTES) {
            workflowFailure(AgentWorkflowErrorCodes.LIMIT_EXCEEDED, "encoded recipe exceeds its byte limit")
        }
        return encoded
    }

    internal fun decodeRecipe(json: JSONObject): AgentWorkflowRecipe {
        json.requireOnlyKeys(
            "recipe",
            "schemaVersion",
            "id",
            "version",
            "title",
            "description",
            "goalTemplate",
            "inputs",
            "modelPolicy",
            "approvalPolicy",
            "tools",
            "steps"
        )
        val schemaVersion = json.requireInt("recipe", "schemaVersion")
        if (schemaVersion != AgentWorkflowRecipeLimits.SCHEMA_VERSION) {
            workflowFailure(
                AgentWorkflowErrorCodes.UNSUPPORTED_SCHEMA,
                "unsupported recipe schemaVersion $schemaVersion"
            )
        }
        val id = json.requireString("recipe", "id")
        val version = json.requireLong("recipe", "version")
        val title = json.requireString("recipe", "title")
        val description = json.optStringOrNull("recipe", "description") ?: ""
        val goalTemplate = json.requireString("recipe", "goalTemplate")
        val inputs = decodeInputs(json.requireArray("recipe", "inputs"))
        val modelPolicy = decodeModelPolicy(json.requireObject("recipe", "modelPolicy"))
        val approvalPolicy = decodeApprovalPolicy(json.requireString("recipe", "approvalPolicy"))
        val tools = decodeTools(json.requireArray("recipe", "tools"))
        val steps = decodeSteps(json.requireArray("recipe", "steps"))

        return building("recipe") {
            AgentWorkflowRecipe(
                id = id,
                version = version,
                title = title,
                goalTemplate = goalTemplate,
                description = description,
                inputs = inputs,
                modelPolicy = modelPolicy,
                approvalPolicy = approvalPolicy,
                enabledToolKeys = tools,
                steps = steps,
                schemaVersion = schemaVersion
            )
        }
    }

    internal fun encodeRecipe(recipe: AgentWorkflowRecipe): JSONObject {
        val inputs = JSONArray()
        recipe.inputs.forEach { input -> inputs.put(encodeInput(input)) }
        val tools = JSONArray()
        recipe.enabledToolKeys.forEach { key -> tools.put(key) }
        val steps = JSONArray()
        recipe.steps.forEach { step -> steps.put(encodeStep(step)) }
        return JSONObject()
            .put("schemaVersion", recipe.schemaVersion)
            .put("id", recipe.id)
            .put("version", recipe.version)
            .put("title", recipe.title)
            .put("description", recipe.description)
            .put("goalTemplate", recipe.goalTemplate)
            .put("inputs", inputs)
            .put("modelPolicy", encodeModelPolicy(recipe.modelPolicy))
            .put("approvalPolicy", recipe.approvalPolicy.name)
            .put("tools", tools)
            .put("steps", steps)
    }

    // ------------------------------------------------------------------ input sources

    private fun decodeInputs(array: JSONArray): List<AgentWorkflowInputSource> {
        if (array.length() > AgentWorkflowRecipeLimits.MAX_INPUTS) {
            workflowFailure(AgentWorkflowErrorCodes.LIMIT_EXCEEDED, "too many recipe inputs")
        }
        val inputs = ArrayList<AgentWorkflowInputSource>(array.length())
        val seen = HashSet<String>()
        for (index in 0 until array.length()) {
            val json = array.requireObjectAt("recipe.inputs[$index]", index)
            val kindRaw = json.requireString("recipe.inputs[$index]", "kind")
            val kind = when (kindRaw) {
                "USER_TEXT" -> AgentWorkflowInputKind.USER_TEXT
                "WORKSPACE_INPUT" -> AgentWorkflowInputKind.WORKSPACE_INPUT
                else -> workflowFailure(
                    AgentWorkflowErrorCodes.RECIPE_INVALID,
                    "unsupported recipe input kind '$kindRaw'"
                )
            }
            json.requireOnlyKeys(
                "recipe.inputs[$index]",
                "id",
                "kind",
                "label",
                "required",
                "maxChars",
                "path"
            )
            val input = building("recipe.inputs[$index]") {
                when (kind) {
                    AgentWorkflowInputKind.USER_TEXT -> {
                        if (json.has("path") && !json.isNull("path")) {
                            workflowFailure(
                                AgentWorkflowErrorCodes.RECIPE_INVALID,
                                "recipe.inputs[$index] USER_TEXT must not declare a path"
                            )
                        }
                        AgentWorkflowInputSource(
                            id = json.requireString("recipe.inputs[$index]", "id"),
                            kind = kind,
                            label = json.requireString("recipe.inputs[$index]", "label"),
                            required = json.optBoolean("recipe.inputs[$index]", "required") ?: true,
                            maxChars = json.optInt("recipe.inputs[$index]", "maxChars")
                                ?: AgentWorkflowRecipeLimits.MAX_INPUT_VALUE_CHARS
                        )
                    }

                    AgentWorkflowInputKind.WORKSPACE_INPUT -> {
                        if (json.has("maxChars") && !json.isNull("maxChars")) {
                            workflowFailure(
                                AgentWorkflowErrorCodes.RECIPE_INVALID,
                                "recipe.inputs[$index] WORKSPACE_INPUT must not declare maxChars"
                            )
                        }
                        AgentWorkflowInputSource(
                            id = json.requireString("recipe.inputs[$index]", "id"),
                            kind = kind,
                            label = json.requireString("recipe.inputs[$index]", "label"),
                            required = json.optBoolean("recipe.inputs[$index]", "required") ?: true,
                            path = json.requireString("recipe.inputs[$index]", "path")
                        )
                    }
                }
            }
            if (!seen.add(input.id)) {
                workflowFailure(AgentWorkflowErrorCodes.DUPLICATE_ID, "duplicate recipe input id '${input.id}'")
            }
            inputs += input
        }
        return inputs
    }

    private fun encodeInput(input: AgentWorkflowInputSource): JSONObject = JSONObject().apply {
        put("id", input.id)
        put("kind", input.kind.name)
        put("label", input.label)
        put("required", input.required)
        when (input.kind) {
            AgentWorkflowInputKind.USER_TEXT -> put("maxChars", input.maxChars)
            AgentWorkflowInputKind.WORKSPACE_INPUT -> put("path", input.path)
        }
    }

    // ------------------------------------------------------------------ model/network policy

    private fun decodeModelPolicy(json: JSONObject): AgentWorkflowModelPolicy {
        json.requireOnlyKeys("recipe.modelPolicy", "networkPolicy", "allowedProviderIds")
        val networkRaw = json.requireString("recipe.modelPolicy", "networkPolicy")
        val networkPolicy = when (networkRaw) {
            AgentNetworkPolicy.LOCAL_ONLY.name -> AgentNetworkPolicy.LOCAL_ONLY
            AgentNetworkPolicy.NETWORK_ENABLED.name -> workflowFailure(
                AgentWorkflowErrorCodes.NETWORK_POLICY_UNSUPPORTED,
                "workflow recipes v1 must stay LOCAL_ONLY"
            )

            else -> workflowFailure(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                "unsupported network policy '$networkRaw'"
            )
        }
        val providers = decodeStringList(
            json.requireArray("recipe.modelPolicy", "allowedProviderIds"),
            "recipe.modelPolicy.allowedProviderIds",
            AgentWorkflowRecipeLimits.MAX_PROVIDER_IDS
        )
        if (providers != providers.distinct().sorted()) {
            workflowFailure(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                "allowedProviderIds must be unique and sorted"
            )
        }
        return building("recipe.modelPolicy") {
            AgentWorkflowModelPolicy(
                networkPolicy = networkPolicy,
                allowedProviderIds = providers
            )
        }
    }

    private fun encodeModelPolicy(policy: AgentWorkflowModelPolicy): JSONObject = JSONObject().apply {
        put("networkPolicy", policy.networkPolicy.name)
        put("allowedProviderIds", JSONArray(policy.allowedProviderIds))
    }

    private fun decodeApprovalPolicy(raw: String): AgentWorkflowApprovalPolicy = when (raw) {
        AgentWorkflowApprovalPolicy.STANDARD.name -> AgentWorkflowApprovalPolicy.STANDARD
        else -> workflowFailure(
            AgentWorkflowErrorCodes.RECIPE_INVALID,
            "unsupported approval policy '$raw'"
        )
    }

    // ------------------------------------------------------------------ tools

    private fun decodeTools(array: JSONArray): List<String> {
        if (array.length() > AgentWorkflowRecipeLimits.MAX_TOOLS) {
            workflowFailure(AgentWorkflowErrorCodes.LIMIT_EXCEEDED, "too many recipe tools")
        }
        val tools = ArrayList<String>(array.length())
        val seen = HashSet<String>()
        for (index in 0 until array.length()) {
            val key = array.requireStringAt("recipe.tools[$index]", index)
            if (!AgentWorkflowToolAllowlist.isKnownTool(key)) {
                workflowFailure(
                    AgentWorkflowErrorCodes.TOOL_NOT_ALLOWED,
                    "workflow recipe references unknown tool '$key'"
                )
            }
            if (!AgentWorkflowToolAllowlist.isReadOnlyTool(key)) {
                workflowFailure(
                    AgentWorkflowErrorCodes.WRITE_TOOL_NOT_ALLOWED,
                    "workflow recipe references non-read-only tool '$key'"
                )
            }
            if (!seen.add(key)) {
                workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "duplicate workflow tool '$key'")
            }
            tools += key
        }
        if (tools != tools.sorted()) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "workflow tools must be sorted")
        }
        return tools
    }

    // ------------------------------------------------------------------ steps

    private fun decodeSteps(array: JSONArray): List<AgentWorkflowStep> {
        if (array.length() == 0 || array.length() > AgentWorkflowRecipeLimits.MAX_STEPS) {
            workflowFailure(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                "a recipe must declare 1..${AgentWorkflowRecipeLimits.MAX_STEPS} checks"
            )
        }
        val steps = ArrayList<AgentWorkflowStep>(array.length())
        val seen = HashSet<String>()
        for (index in 0 until array.length()) {
            val json = array.requireObjectAt("recipe.steps[$index]", index)
            json.requireOnlyKeys("recipe.steps[$index]", "id", "check", "title", "inputId")
            val checkRaw = json.requireString("recipe.steps[$index]", "check")
            val check = AgentWorkflowCheck.entries.firstOrNull { it.name == checkRaw }
                ?: workflowFailure(
                    AgentWorkflowErrorCodes.RECIPE_INVALID,
                    "unsupported workflow check '$checkRaw'"
                )
            val step = building("recipe.steps[$index]") {
                AgentWorkflowStep(
                    id = json.requireString("recipe.steps[$index]", "id"),
                    check = check,
                    title = json.requireString("recipe.steps[$index]", "title"),
                    inputId = json.optStringOrNull("recipe.steps[$index]", "inputId")
                )
            }
            if (!seen.add(step.id)) {
                workflowFailure(AgentWorkflowErrorCodes.DUPLICATE_ID, "duplicate workflow step id '${step.id}'")
            }
            steps += step
        }
        return steps
    }

    private fun encodeStep(step: AgentWorkflowStep): JSONObject = JSONObject().apply {
        put("id", step.id)
        put("check", step.check.name)
        put("title", step.title)
        step.inputId?.let { put("inputId", it) }
    }

    // ------------------------------------------------------------------ strict primitive helpers

    private fun decodeStringList(array: JSONArray, where: String, max: Int): List<String> {
        if (array.length() > max) {
            workflowFailure(AgentWorkflowErrorCodes.LIMIT_EXCEEDED, "$where exceeds its limit")
        }
        val values = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            values += array.requireStringAt("$where[$index]", index)
        }
        return values
    }

    private fun JSONObject.requireOnlyKeys(where: String, vararg allowed: String) {
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key !in allowed) {
                workflowFailure(
                    AgentWorkflowErrorCodes.RECIPE_INVALID,
                    "$where contains unsupported field '$key'"
                )
            }
        }
    }

    private fun JSONObject.requireString(where: String, name: String): String {
        if (!has(name) || isNull(name)) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where is missing '$name'")
        }
        val value = get(name)
        if (value !is String) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where.$name must be a string")
        }
        return value
    }

    private fun JSONObject.optStringOrNull(where: String, name: String): String? {
        if (!has(name) || isNull(name)) return null
        val value = get(name)
        if (value !is String) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where.$name must be a string")
        }
        return value
    }

    private fun JSONObject.requireInt(where: String, name: String): Int {
        val long = requireLong(where, name)
        if (long !in Int.MIN_VALUE..Int.MAX_VALUE) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where.$name is out of range")
        }
        return long.toInt()
    }

    private fun JSONObject.optInt(where: String, name: String): Int? {
        if (!has(name) || isNull(name)) return null
        val value = requireLong(where, name)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where.$name is out of range")
        }
        return value.toInt()
    }

    private fun JSONObject.requireLong(where: String, name: String): Long {
        if (!has(name) || isNull(name)) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where is missing '$name'")
        }
        val value = get(name)
        if (value !is Number) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where.$name must be a number")
        }
        val asLong = value.toLong()
        if (value.toDouble() != asLong.toDouble()) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where.$name must be an integer")
        }
        return asLong
    }

    private fun JSONObject.optBoolean(where: String, name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        val value = get(name)
        if (value !is Boolean) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where.$name must be a boolean")
        }
        return value
    }

    private fun JSONObject.requireArray(where: String, name: String): JSONArray {
        if (!has(name) || isNull(name)) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where is missing '$name'")
        }
        return get(name) as? JSONArray
            ?: workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where.$name must be an array")
    }

    private fun JSONObject.requireObject(where: String, name: String): JSONObject {
        if (!has(name) || isNull(name)) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where is missing '$name'")
        }
        return get(name) as? JSONObject
            ?: workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where.$name must be an object")
    }

    private fun JSONArray.requireObjectAt(where: String, index: Int): JSONObject =
        get(index) as? JSONObject
            ?: workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where must be an object")

    private fun JSONArray.requireStringAt(where: String, index: Int): String =
        get(index) as? String
            ?: workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "$where must be a string")

    private inline fun <T> building(where: String, block: () -> T): T = try {
        block()
    } catch (failure: AgentWorkflowException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        workflowFailure(
            AgentWorkflowErrorCodes.RECIPE_INVALID,
            "$where is invalid: ${failure.message ?: "validation failed"}",
            failure
        )
    } catch (failure: JSONException) {
        workflowFailure(
            AgentWorkflowErrorCodes.RECIPE_INVALID,
            "$where is invalid JSON: ${failure.message ?: "parse failed"}",
            failure
        )
    }
}

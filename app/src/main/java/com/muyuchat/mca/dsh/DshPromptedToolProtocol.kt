package com.muyuchat.mca.dsh

import com.muyuchat.core.dsh.contract.DshModelToolDefinition
import com.muyuchat.core.dsh.contract.DshToolChoice
import org.json.JSONArray
import org.json.JSONObject

/**
 * The clearly labelled prompt-compatibility tool protocol used whenever MCA cannot carry native
 * tool calls for a model.
 *
 * Why it exists: the MCA local engine does not serialize `ChatRequest.tools`/`toolExchanges` into
 * the native runner today, and the existing OpenAI-compatible / Anthropic cloud transports do not
 * return native tool calls either. Pretending those paths are native would be dishonest. The model
 * is instead given a bounded tool catalog and a strict JSON envelope, and the adapter parses that
 * envelope back into [com.muyuchat.core.dsh.contract.DshModelEvent.ToolCallDelta] events. The
 * catalog marks these models `nativeToolCalling = false` with `toolProtocolLabel = PROMPTED_TOOL_V1`.
 *
 * Deterministic error behaviour (all covered by tests):
 *  - a malformed / incomplete envelope is a semantic failure
 *    ([DshAdapterErrorCodes.PROMPTED_TOOL_MALFORMED]); no tool call is invented and no half-call
 *    is executed;
 *  - unknown / hallucinated tool name -> [DshAdapterErrorCodes.PROMPTED_TOOL_UNKNOWN_NAME];
 *  - a required tool choice with no call -> [DshAdapterErrorCodes.TOOL_REQUIRED_MISSING];
 *  - more calls than the configured maximum, one call whose arguments exceed the explicit
 *    per-call limit, or multiple calls when parallel calls are not allowed are each their own
 *    semantic failure;
 *  - an envelope larger than [DshAdapterLimits.maxPromptedEnvelopeChars] is treated as plain text;
 *  - trailing non-whitespace text after the end marker makes the whole envelope malformed, so a
 *    model that keeps writing after its call cannot smuggle prose into a tool call.
 *
 * The builder never truncates the protocol text and never silently drops a tool: tools that cannot
 * fit the catalog budget are returned in [DshPromptedToolCatalog.omittedNames] and named in the
 * instruction. Core shell/file tools and then the background job controls are packed first so a
 * tight budget still leaves a usable tool set; the adapter degrades to that subset (or to plain
 * chat when nothing fits) rather than failing the whole turn.
 */
internal enum class DshToolEnvelopeStyle {
    /** MCA's labelled JSON marker; used for cloud providers that follow the full guide. */
    JSON,

    /** `<tool_call>{"name":…,"arguments":{…}}</tool_call>`; Qwen2.5/Qwen3 chat templates. */
    QWEN_JSON,

    /** `<tool_call><function=…><parameter=…>`; Qwen3.5/Qwen3-Coder templates. */
    XML
}

internal object DshPromptedToolProtocol {
    const val PROTOCOL_LABEL: String = "PROMPTED_TOOL_V1"
    const val START_MARKER: String = "<<<MCA_TOOL_CALLS_V1>>>"
    /**
     * Marker body without the bracket frame. A small model can emit `V1>>` or `V1>` instead of
     * the exact `V1>>>`; that must still be treated as a protocol envelope (and fail typed),
     * never leaked to the user as assistant prose. Device evidence: 2026-10-22 Qwen2.5-0.5B
     * emitted `<<<MCA_TOOL_CALLS_V1>>` and the raw envelope was shown in the transcript.
     */
    const val MARKER_CORE: String = "MCA_TOOL_CALLS_V1"
    /**
     * Family fragment shared by protocol markers. A small model can also invent a different
     * family (`BASH_TOOL_CALLS_V1`) or drop brackets; any `<<<…TOOL_CALLS_V1` tail is a
     * protocol attempt and must never be shown as assistant prose.
     */
    const val MARKER_FRAGMENT: String = "TOOL_CALLS_V1"
    const val END_MARKER: String = "<<<END_MCA_TOOL_CALLS_V1>>>"
    /** Alternate syntax some function-tuned models emit; parsed into the same validated call list. */
    const val XML_START: String = "<tool_call>"
    const val REQUIRED_TOOL_NOTE: String =
        "You must call at least one of the provided tools in this reply."

    private const val MAX_REPORTED_UNKNOWN_NAME_CHARS = 64
    private const val CALL_ID_MAX_CHARS = 128

    /**
     * Builds the bounded system instruction that offers [tools] and explains the envelope.
     *
     * The fixed rules/envelope guide are always complete (never `take()`-truncated) and the
     * returned [DshPromptedToolCatalog.offeredNames] is exactly the set of tools described in the
     * text. A tool that cannot be fully represented within the catalog budget is omitted from both
     * the text and the offered names, and is reported in
     * [DshPromptedToolCatalog.omittedNames] so the caller can reject the request explicitly.
     */
    fun buildInstruction(
        tools: List<DshModelToolDefinition>,
        toolChoice: DshToolChoice,
        limits: DshAdapterLimits,
        style: DshToolEnvelopeStyle = DshToolEnvelopeStyle.JSON
    ): DshPromptedToolCatalog {
        if (tools.isEmpty() || toolChoice == DshToolChoice.NONE) {
            return DshPromptedToolCatalog(
                instruction = "",
                offeredNames = emptyList(),
                omittedNames = emptyList()
            )
        }

        val rules = buildString {
            appendLine("Call rules:")
            when (style) {
                DshToolEnvelopeStyle.XML -> {
                    appendLine("1. The function name must be copied exactly from the available tool names above (for example the shell tool is named \"bash\"); never use a command word such as \"printf\" or invent a name.")
                    appendLine("2. Each argument is one <parameter=KEY>VALUE</parameter> pair matching that tool's schema; use one pair per argument.")
                    appendLine("3. You may emit one or more <tool_call> blocks.")
                    appendLine("4. Emit exactly the block shown; no JSON, no fences, no ids and no extra keys.")
                    appendLine("5. Put the block at the very end of your answer, with no text after it.")
                }
                DshToolEnvelopeStyle.QWEN_JSON -> {
                    appendLine("1. The \"name\" value must be copied exactly from the available tool names above (for example the shell tool is named \"bash\"); never use a command word such as \"printf\" or invent a name.")
                    appendLine("2. Every \"arguments\" value must be one complete JSON object matching that tool's schema.")
                    appendLine("3. Put each call in its own <tool_call></tool_call> block, one JSON object with exactly \"name\" and \"arguments\".")
                    appendLine("4. Emit exactly the block shown; no fences, no ids and no extra keys.")
                    appendLine("5. Put the block at the very end of your answer, with no text after it.")
                }
                DshToolEnvelopeStyle.JSON -> {
                    appendLine("1. The \"name\" value must be copied exactly from the available tool names above (for example the shell tool is named \"bash\"); never use a command word such as \"printf\" or invent a name.")
                    appendLine("2. Every \"arguments\" value must be one complete JSON object matching that tool's schema.")
                    appendLine("3. You may request one or more tools in one tool_calls array.")
                    appendLine("4. Emit exactly the block shown; never XML tags such as <tool_call>; no fences, ids or extra keys.")
                    appendLine("5. Put the block at the very end of your answer, with no text after it.")
                }
            }
            append("6. If the user asks you to run a command, do not describe it in prose; emit the block with that exact command. If no tool is needed, answer normally and do not emit the block.")
            if (toolChoice == DshToolChoice.REQUIRED) append("\n7. ").append(REQUIRED_TOOL_NOTE)
        }
        val exampleTool = tools.firstOrNull { it.name == "bash" } ?: tools.first()
        val envelopeGuide = when (style) {
            DshToolEnvelopeStyle.XML -> buildString {
                appendLine("To call tools, reply with exactly this block and nothing after it:")
                appendLine(XML_START)
                appendLine("<function=" + exampleTool.name + ">")
                if (exampleTool.name == "bash") {
                    appendLine("<parameter=command>pwd</parameter>")
                } else {
                    appendLine("<parameter=value>x</parameter>")
                }
                appendLine("</function>")
                appendLine("</tool_call>")
                append("Use the user's command or arguments in place of the example values.")
            }
            DshToolEnvelopeStyle.QWEN_JSON -> {
                val exampleArguments = if (exampleTool.name == "bash") {
                    "{\"command\":\"pwd\"}"
                } else {
                    "{}"
                }
                buildString {
                    appendLine("To call tools, reply with exactly this block and nothing after it:")
                    appendLine(XML_START)
                    appendLine(
                        "{\"name\":\"" + exampleTool.name + "\",\"arguments\":" + exampleArguments + "}"
                    )
                    appendLine("</tool_call>")
                    append("The example arguments are valid JSON; use the user's command or arguments in place of the example values.")
                }
            }
            DshToolEnvelopeStyle.JSON -> {
                val exampleArguments = if (exampleTool.name == "bash") {
                    "{\"command\":\"pwd\"}"
                } else {
                    "{}"
                }
                buildString {
                    appendLine("To call tools, reply with exactly this block and nothing after it:")
                    appendLine(START_MARKER)
                    appendLine(
                        "{\"tool_calls\":[{\"name\":\"" + exampleTool.name +
                            "\",\"arguments\":" + exampleArguments + "}]}"
                    )
                    append(END_MARKER)
                    appendLine()
                    append("The example arguments are valid JSON; use the user's command or arguments in place of the example values.")
                }
            }
        }
        val header = "You can use the following tools by emitting one tool-call block."
        val fixed = listOf(header, "", rules, "", envelopeGuide).joinToString("\n")
        if (fixed.length >= limits.maxPromptedToolCatalogChars) {
            throw DshMappingException(
                DshAdapterErrorCodes.TOOL_CATALOG_TOO_LARGE,
                "The prompted tool protocol text alone exceeds the adapter's tool-catalog budget."
            )
        }

        // Pack the tools a local model needs for real work first: a small catalog budget must not
        // drop read/write/terminal or the background job controls while keeping decorative tools.
        val prioritized = tools.sortedBy { toolPriority(it.name) }
        val entries = ArrayList<Pair<String, String>>()
        for (tool in prioritized) {
            val parameters = tool.parametersJson.trim().ifBlank { "{}" }
            if (parameters.length > limits.maxToolParametersChars ||
                tool.description.trim().length > limits.maxToolDescriptionChars
            ) {
                // Not representable in full: leave it out of the offered set (reported below).
                continue
            }
            entries += tool.name to buildEntry(tool, parameters, style)
        }

        fun render(kept: List<Pair<String, String>>): String {
            val keptNames = kept.mapTo(HashSet()) { it.first }
            val unavailable = tools.map { it.name }.filter { it !in keptNames }
            val body = kept.joinToString("\n") { it.second }
            val catalog = if (kept.isEmpty()) {
                "Available tools: (none)"
            } else if (style == DshToolEnvelopeStyle.JSON) {
                "Available tools:\n$body"
            } else {
                // Both installed native templates wrap `tool | tojson` in <tools>; matching that
                // layout keeps the catalog inside the shape the model was trained on.
                "Available tools:\n<tools>\n$body\n</tools>"
            }
            return buildString {
                append(header).append("\n\n").append(catalog)
                if (unavailable.isNotEmpty()) {
                    append("\nThe following tools are not available for this call because their schema/description or the catalog budget exceeds the adapter limit: ")
                    append(unavailable.joinToString(", "))
                }
                append("\n\n").append(envelopeGuide).append("\n\n").append(rules)
            }
        }

        val kept = ArrayList(entries)
        while (kept.isNotEmpty() && render(kept).length > limits.maxPromptedToolCatalogChars) {
            kept.removeAt(kept.size - 1)
        }
        val instruction = render(kept)
        if (instruction.length > limits.maxPromptedToolCatalogChars) {
            throw DshMappingException(
                DshAdapterErrorCodes.TOOL_CATALOG_TOO_LARGE,
                "The prompted tool catalog cannot be represented within the adapter's budget."
            )
        }
        val keptNames = kept.map { it.first }
        val keptNameSet = keptNames.toSet()
        val omitted = tools.map { it.name }.filter { it !in keptNameSet }
        return DshPromptedToolCatalog(
            instruction = instruction,
            offeredNames = keptNames,
            omittedNames = omitted
        )
    }

    /**
     * Exact essential names outrank family variants. A tight local budget must keep `bash`
     * before `read_image`/`list_agents`-style prefixes; matching those families first pushed the
     * only shell tool out of the catalog and the model was told bash was unavailable
     * (device evidence 2026-10-25: qwen 4B answered 1 token instead of calling bash).
     *
     * A configured MCP tool (`mcp__<server>__<tool>`) takes the `mcp` class slot itself: it is a
     * first-class user-configured capability with no in-payload fallback, so it must not fall into
     * the family tier where `edit`/`apply_patch`/`list`/`glob`/`grep`/`search` all outrank it.
     */
    private fun toolPriority(name: String): Int {
        val exact = ESSENTIAL_TOOL_ORDER.indexOf(name)
        if (exact >= 0) return exact
        if (name.startsWith(MCP_TOOL_NAME_PREFIX)) return ESSENTIAL_TOOL_ORDER.indexOf(MCP_CLASS_NAME)
        val family = ESSENTIAL_TOOL_ORDER.indexOfFirst { name.startsWith(it + "_") }
        return if (family >= 0) family + ESSENTIAL_TOOL_ORDER.size else ESSENTIAL_TOOL_ORDER.size * 2
    }

    /**
     * Tools that must survive a tight prompted-catalog budget, in priority order.
     *
     * The shell/file core (`bash`, `bash_persistent`, `terminal`, `read`, `write`) is packed
     * first so a small budget always leaves a usable working set. The official DSH background
     * job controls come next. Their exact names were verified against the host's captured
     * request header (`job_kill`, `job_list`, `job_output` in `dsh-request-header-4b.json`) and
     * the payload manifest `@deepseek-ai/dsh-tool-jobs`; the earlier 8 KiB catalog omitted them,
     * so a real device turn that called `job_list` was rejected as an unoffered tool
     * (2026-10-26, dispatch `offered=5 tools=60`). `job_list` ranks first because it yields the
     * ids the other two need; `job_kill` before `job_output` because stopping a runaway job is
     * the time-sensitive action. `mcp` follows the job tools: a tool from a user-configured MCP
     * server has no fallback inside the payload (bash cannot reach it), so a tight budget must not
     * spend the remaining room on `edit`/`apply_patch`/discovery that bash can still cover.
     * `edit`/`apply_patch` and discovery (`list`/`glob`/`grep`/`search`)
     * follow the job controls because `bash` can still edit and discover files, while the host
     * has no fallback when the job tools are absent. The official MCP resource tools
     * (`list_mcp_resources`, `list_mcp_resource_templates`, `read_mcp_resource`) share the MCP slot
     * for the same reason: no other payload tool can enumerate or read an MCP server's resources,
     * and the family tier would otherwise rank all three behind `read`/`list` look-alikes.
     */
    private val ESSENTIAL_TOOL_ORDER = listOf(
        "bash", "bash_persistent", "terminal", "read", "write",
        "job_list", "job_kill", "job_output",
        "mcp",
        // Official `@deepseek-ai/dsh-mcp-resources` tools (dsh-base patch row `mcp-resources`).
        // They are the only way the model can address an MCP resource; bash cannot reach an MCP
        // server, so the 8 KiB prompted catalog must not spend their room on bash-coverable
        // `list`/`read`/`glob`/`grep` discovery that the family tier would otherwise prefer.
        "list_mcp_resources", "list_mcp_resource_templates", "read_mcp_resource",
        "edit", "apply_patch", "list", "glob", "grep", "search"
    )

    /** Namespaced MCP tool names are `mcp__<serverName>__<toolName>`. */
    private val MCP_TOOL_NAME_PREFIX = "mcp__"

    /** The [ESSENTIAL_TOOL_ORDER] slot that MCP tools occupy (between the job tools and edit). */
    private val MCP_CLASS_NAME = "mcp"

    private fun buildEntry(
        tool: DshModelToolDefinition,
        parameters: String,
        style: DshToolEnvelopeStyle
    ): String = buildString {
        if (style != DshToolEnvelopeStyle.JSON) {
            // Native template shape: one OpenAI function object per line, serialized as JSON.
            append("{\"type\":\"function\",\"function\":{\"name\":")
            append(JSONObject.quote(tool.name))
            append(",\"description\":").append(JSONObject.quote(tool.description.trim()))
            append(",\"parameters\":").append(parameters)
            append("}}")
            return@buildString
        }
        append("### ").append(tool.name)
        val description = tool.description.trim()
        if (description.isNotEmpty()) append("\n").append(description)
        append("\nparameters (JSON Schema): ").append(parameters)
    }

    /**
     * Parses a complete model answer. A well-formed envelope must be the tail of the answer and
     * must contain 1..maxPromptedToolCalls calls with a known tool name and one complete JSON
     * object of arguments each.
     *
     * [allowParallelCalls] mirrors the request's parallel-tool-calls policy: when false, an answer
     * that requests more than one tool is a distinct semantic failure instead of a successful
     * parallel batch. The frozen model contract carries no parallel flag yet, so the adapter
     * currently always passes true; the parameter is exercised by tests and ready for the additive
     * contract field requested from the integrator.
     */
    fun parse(
        raw: String,
        offeredNames: List<String>,
        callId: String,
        limits: DshAdapterLimits,
        allowParallelCalls: Boolean = true
    ): DshPromptedParse {
        val xmlStart = raw.indexOf("<tool_call", ignoreCase = true)
        if (xmlStart >= 0) {
            return parseXmlCalls(raw.substring(xmlStart), offeredNames, callId, limits, allowParallelCalls)
        }
        val start = protocolAttemptStart(raw)
        if (start < 0) return DshPromptedParse.NoCalls(raw)
        if (!raw.startsWith("<<<", start)) return DshPromptedParse.Malformed(raw)
        // Tolerant framing: small models drop closing brackets (`V1>>`) or invent a family
        // (`BASH_TOOL_CALLS_V1`). The payload itself is still held to the strict JSON/tool rules
        // below, so malformed JSON or unknown tools never execute.
        val fragment = raw.indexOf(MARKER_FRAGMENT, start)
        if (fragment < 0) return DshPromptedParse.Malformed(raw)
        var payloadStart = fragment + MARKER_FRAGMENT.length
        while (payloadStart < raw.length && raw[payloadStart] == '>') payloadStart++
        val end = raw.indexOf(END_MARKER, payloadStart)
        val payloadEnd = if (end >= 0) end else raw.length
        if (end >= 0 && raw.substring(end + END_MARKER.length).isNotBlank()) {
            return DshPromptedParse.Malformed(raw)
        }
        val payload = raw.substring(payloadStart, payloadEnd).trim()
        if (payload.isEmpty()) return DshPromptedParse.Malformed(raw)
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return DshPromptedParse.Malformed(raw)
        if (root.keys().asSequence().toList() != listOf("tool_calls")) {
            return DshPromptedParse.Malformed(raw)
        }
        val array = root.optJSONArray("tool_calls") ?: return DshPromptedParse.Malformed(raw)
        if (array.length() == 0) return DshPromptedParse.Malformed(raw)
        if (array.length() > limits.maxPromptedToolCalls) {
            return DshPromptedParse.TooManyCalls(array.length())
        }

        val calls = ArrayList<DshParsedToolCall>(array.length())
        val unknown = LinkedHashSet<String>()
        val argumentsTooLarge = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return DshPromptedParse.Malformed(raw)
            // The envelope must not carry ids: ids are adapter-generated and must stay unique.
            if (item.keys().asSequence().toList().sorted() != listOf("arguments", "name")) {
                return DshPromptedParse.Malformed(raw)
            }
            val rawName = item.opt("name") as? String ?: return DshPromptedParse.Malformed(raw)
            val name = resolveOfferedName(rawName, offeredNames)
            if (name == null) {
                unknown += rawName.trim().take(MAX_REPORTED_UNKNOWN_NAME_CHARS)
                continue
            }
            val arguments = when (val value = item.opt("arguments")) {
                is JSONObject -> value
                is String -> runCatching { JSONObject(value) }.getOrNull()
                else -> null
            } ?: return DshPromptedParse.Malformed(raw)
            val argumentsJson = arguments.toString()
            if (argumentsJson.length > limits.maxToolCallArgumentsChars) {
                argumentsTooLarge += name
                continue
            }
            calls += DshParsedToolCall(
                id = promptedToolCallId(callId, index),
                name = name,
                argumentsJson = argumentsJson
            )
        }
        if (unknown.isNotEmpty()) return DshPromptedParse.UnknownTools(unknown.toList())
        if (argumentsTooLarge.isNotEmpty()) {
            return DshPromptedParse.ArgumentsTooLarge(argumentsTooLarge.toList())
        }
        if (!allowParallelCalls && calls.size > 1) {
            return DshPromptedParse.ParallelCallsForbidden(calls.size)
        }
        if (calls.isEmpty()) return DshPromptedParse.Malformed(raw)
        return DshPromptedParse.Calls(calls)
    }

    /**
     * Parses the `<tool_call><function=NAME><parameter=KEY>VALUE</parameter></function></tool_call>`
     * form. Values are text; the arguments object is validated like the JSON envelope's.
     */
    private fun parseXmlCalls(
        raw: String,
        offeredNames: List<String>,
        callId: String,
        limits: DshAdapterLimits,
        allowParallelCalls: Boolean
    ): DshPromptedParse {
        val blockRegex = Regex("""<tool_call\b[^>]*>(.*?)</tool_call\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val blocks = blockRegex.findAll(raw).toList()
        if (blocks.isEmpty()) return DshPromptedParse.Malformed(raw)
        if (blocks.size > limits.maxPromptedToolCalls) return DshPromptedParse.TooManyCalls(blocks.size)
        if (!allowParallelCalls && blocks.size > 1) return DshPromptedParse.ParallelCallsForbidden(blocks.size)
        val calls = ArrayList<DshParsedToolCall>(blocks.size)
        val unknown = LinkedHashSet<String>()
        val argumentsTooLarge = LinkedHashSet<String>()
        val functionRegex = Regex("""<function\s*=\s*"?([A-Za-z0-9_.:-]+)"?\s*>(.*?)</function\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val parameterRegex = Regex("""<parameter\s*=\s*"?([A-Za-z0-9_.-]+)"?\s*>(.*?)</parameter\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        blocks.forEachIndexed { index, block ->
            val body = block.groupValues[1].trim()
            val rawName: String
            val arguments: JSONObject
            if (body.startsWith("{")) {
                // Qwen2.5/Qwen3 native shape: one strict {"name","arguments"} object per block.
                val root = runCatching { JSONObject(body) }.getOrNull()
                    ?: return DshPromptedParse.Malformed(raw)
                if (root.keys().asSequence().toList().sorted() != listOf("arguments", "name")) {
                    return DshPromptedParse.Malformed(raw)
                }
                rawName = root.opt("name") as? String ?: return DshPromptedParse.Malformed(raw)
                arguments = when (val value = root.opt("arguments")) {
                    is JSONObject -> value
                    is String -> runCatching { JSONObject(value) }.getOrNull()
                    else -> null
                } ?: return DshPromptedParse.Malformed(raw)
            } else {
                val function = functionRegex.find(body) ?: return DshPromptedParse.Malformed(raw)
                rawName = function.groupValues[1]
                arguments = JSONObject()
                parameterRegex.findAll(function.groupValues[2]).forEach { parameter ->
                    arguments.put(parameter.groupValues[1], xmlScalar(parameter.groupValues[2]))
                }
            }
            val name = resolveOfferedName(rawName, offeredNames)
            if (name == null) {
                unknown += rawName.trim().take(MAX_REPORTED_UNKNOWN_NAME_CHARS)
                return@forEachIndexed
            }
            val argumentsJson = arguments.toString()
            if (argumentsJson.length > limits.maxToolCallArgumentsChars) {
                argumentsTooLarge += name
                return@forEachIndexed
            }
            calls += DshParsedToolCall(
                id = promptedToolCallId(callId, index),
                name = name,
                argumentsJson = argumentsJson
            )
        }
        if (unknown.isNotEmpty()) return DshPromptedParse.UnknownTools(unknown.toList())
        if (argumentsTooLarge.isNotEmpty()) return DshPromptedParse.ArgumentsTooLarge(argumentsTooLarge.toList())
        if (calls.isEmpty()) return DshPromptedParse.Malformed(raw)
        return DshPromptedParse.Calls(calls)
    }

    fun promptedToolCallId(callId: String, index: Int): String {
        val readable = "ptc:$callId:$index"
        if (readable.length <= CALL_ID_MAX_CHARS) return readable
        return "ptc:" + sha256Hex(callId).take(24) + ":" + index
    }

    /**
     * XML parameter bodies are text, but tool schemas carry booleans, numbers and JSON objects.
     * Coerce the unambiguous JSON scalars instead of sending everything as String (a strict
     * schema would otherwise reject `run_in_background=true` or a numeric timeout).
     */
    private fun xmlScalar(raw: String): Any {
        val value = raw.trim()
        return when {
            value == "true" -> true
            value == "false" -> false
            value.toLongOrNull() != null -> value.toLong()
            value.toDoubleOrNull() != null -> value.toDouble()
            value.startsWith("{") -> runCatching { JSONObject(value) }.getOrNull() ?: value
            value.startsWith("[") -> runCatching { JSONArray(value) }.getOrNull() ?: value
            else -> value
        }
    }

    private fun resolveOfferedName(raw: String, offered: List<String>): String? {
        val trimmed = raw.trim().take(CALL_ID_MAX_CHARS)
        if (trimmed.isEmpty()) return null
        offered.firstOrNull { it == trimmed }?.let { return it }
        return offered.filter { it.equals(trimmed, ignoreCase = true) }.singleOrNull()
    }
}

internal sealed interface DshPromptedParse {
    /** No envelope: the whole answer is model text. */
    data class NoCalls(val text: String) : DshPromptedParse

    data class Calls(val calls: List<DshParsedToolCall>) : DshPromptedParse

    /** The model named tools that were not offered; no tool event may be emitted. */
    data class UnknownTools(val names: List<String>) : DshPromptedParse

    /** The answer looked like an envelope but is not a valid one; never invent calls from it. */
    data class Malformed(val rawEnvelope: String) : DshPromptedParse

    /** More tool calls than the configured per-answer maximum. */
    data class TooManyCalls(val count: Int) : DshPromptedParse

    /** One or more calls carry complete arguments above the explicit per-call limit. */
    data class ArgumentsTooLarge(val names: List<String>) : DshPromptedParse

    /** The model requested parallel calls while the request disabled them. */
    data class ParallelCallsForbidden(val count: Int) : DshPromptedParse
}

internal data class DshPromptedToolCatalog(
    /** Complete system instruction text; empty when no tool is offered. */
    val instruction: String,
    /** Tool names actually described in the instruction; the parser must use exactly this set. */
    val offeredNames: List<String>,
    /** Tools that could not be fully represented and must cause an explicit rejection. */
    val omittedNames: List<String>
)

internal data class DshParsedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/**
 * Streaming gate that separates safe assistant text from a possible prompted tool-call envelope.
 *
 * It emits text as soon as it cannot be part of the start marker, holds a suffix that could be a
 * partial marker, and holds everything from a real marker until [finish]. This keeps normal
 * answers streaming while guaranteeing an envelope is never leaked to the gateway as text before
 * it is parsed.
 */
/**
 * True when [raw] looks like a (possibly malformed) protocol envelope rather than prose: the
 * exact marker, the marker body, or a `<<<…TOOL_CALLS_V1` family header. Deliberately
 * conservative; it only withholds text that contains the protocol's signature.
 */
fun looksLikeProtocolEnvelope(raw: String): Boolean =
    raw.contains("<tool_call", ignoreCase = true) ||
        raw.contains(DshPromptedToolProtocol.MARKER_CORE) ||
        (raw.contains("<<<") && raw.contains(DshPromptedToolProtocol.MARKER_FRAGMENT))

/**
 * First offset at which a protocol envelope starts in [text], or -1. Includes the `<<<` frame
 * when present so no partial marker is emitted before the hold begins.
 */
fun protocolAttemptStart(text: String): Int {
    val xml = text.indexOf("<tool_call", ignoreCase = true)
    if (xml >= 0) return xml
    val exact = text.indexOf(DshPromptedToolProtocol.START_MARKER)
    if (exact >= 0) return exact
    val core = text.indexOf(DshPromptedToolProtocol.MARKER_CORE)
    if (core >= 0) {
        val open = text.lastIndexOf("<<<", core)
        return if (open >= 0) open else core
    }
    val fragment = text.indexOf(DshPromptedToolProtocol.MARKER_FRAGMENT)
    if (fragment >= 0) {
        val open = text.lastIndexOf("<<<", fragment)
        if (open >= 0) return open
    }
    return -1
}

internal class DshPromptedToolStreamGate(
    private val offeredNames: List<String>,
    private val callId: String,
    private val limits: DshAdapterLimits,
    private val allowParallelCalls: Boolean = true
) {
    private enum class Phase { NORMAL, HOLDING, PASS_THROUGH }

    private val pending = StringBuilder()
    private val envelope = StringBuilder()
    private var phase = Phase.NORMAL

    /** Returns text that is safe to emit now. */
    fun feed(fragment: String): String {
        if (fragment.isEmpty()) return ""
        when (phase) {
            Phase.PASS_THROUGH -> return fragment
            Phase.HOLDING -> return hold(fragment)
            Phase.NORMAL -> Unit
        }
        pending.append(fragment)
        val markerStart = protocolAttemptStart(pending.toString())
        if (markerStart >= 0) {
            val visible = pending.substring(0, markerStart)
            envelope.append(pending, markerStart, pending.length)
            pending.setLength(0)
            phase = Phase.HOLDING
            return visible + releaseEnvelopeIfOversized()
        }
        val keep = maxOf(
            longestMarkerPrefixSuffix(pending, DshPromptedToolProtocol.START_MARKER),
            longestMarkerPrefixSuffix(pending, "<tool_call>")
        )
        val emitLength = pending.length - keep
        if (emitLength <= 0) return ""
        val visible = pending.substring(0, emitLength)
        pending.delete(0, emitLength)
        return visible
    }

    fun finish(): DshPromptedParse = when (phase) {
        Phase.NORMAL -> {
            val text = pending.toString()
            pending.setLength(0)
            if (looksLikeProtocolEnvelope(text)) {
                DshPromptedParse.Malformed(text)
            } else {
                DshPromptedParse.NoCalls(text)
            }
        }
        Phase.HOLDING -> {
            val raw = envelope.toString()
            envelope.setLength(0)
            phase = Phase.PASS_THROUGH
            DshPromptedToolProtocol.parse(raw, offeredNames, callId, limits, allowParallelCalls)
        }
        Phase.PASS_THROUGH -> DshPromptedParse.NoCalls("")
    }

    /** Text held back when the call fails or is cancelled before a terminal answer. */
    fun flushForError(): String = when (phase) {
        Phase.NORMAL -> {
            val text = pending.toString()
            pending.setLength(0)
            text
        }
        Phase.HOLDING -> {
            val text = envelope.toString()
            envelope.setLength(0)
            phase = Phase.PASS_THROUGH
            text
        }
        Phase.PASS_THROUGH -> ""
    }

    private fun hold(fragment: String): String {
        envelope.append(fragment)
        return releaseEnvelopeIfOversized()
    }

    /** Oversized envelopes are handed through as plain text instead of being buffered forever. */
    private fun releaseEnvelopeIfOversized(): String {
        if (envelope.length <= limits.maxPromptedEnvelopeChars) return ""
        val visible = envelope.toString()
        envelope.setLength(0)
        phase = Phase.PASS_THROUGH
        return visible
    }

    private fun longestMarkerPrefixSuffix(builder: StringBuilder, marker: String): Int {
        val max = minOf(builder.length, marker.length - 1)
        var length = max
        while (length > 0) {
            if (builder.regionMatches(builder.length - length, marker, 0, length)) return length
            length -= 1
        }
        return 0
    }
}

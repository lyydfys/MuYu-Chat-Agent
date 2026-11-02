package com.muyuchat.mca.dsh

import com.muyuchat.core.dsh.contract.DshToolChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the labelled PROMPTED_TOOL_V1 protocol: the catalog instruction, the strict envelope
 * parser (including hallucination guards) and the streaming gate that must never leak an envelope
 * as assistant text before it is understood.
 */
class DshPromptedToolProtocolTest {

    private val limits = DshAdapterLimits.Default

    @Test
    fun `instruction carries catalog, markers and required-call rule only when requested`() {
        val tools = listOf(toolDefinition("read"), toolDefinition("bash", description = "Run a command."))
        val auto = DshPromptedToolProtocol.buildInstruction(tools, DshToolChoice.AUTO, limits)
        assertTrue(auto.instruction.contains("### read"))
        assertTrue(auto.instruction.contains("### bash"))
        assertTrue(auto.instruction.contains("Run a command."))
        assertTrue(auto.instruction.contains(DshPromptedToolProtocol.START_MARKER))
        assertTrue(auto.instruction.contains(DshPromptedToolProtocol.END_MARKER))
        assertTrue(auto.instruction.endsWith("do not emit the block."))
        assertTrue(auto.instruction.contains("never use a command word such as \"printf\""))
        assertFalse(auto.instruction.contains(DshPromptedToolProtocol.REQUIRED_TOOL_NOTE))
        // bash outranks the file tools in the catalog so a tight local budget keeps the shell.
        assertEquals(listOf("bash", "read"), auto.offeredNames)
        assertTrue(auto.omittedNames.isEmpty())

        val required = DshPromptedToolProtocol.buildInstruction(tools, DshToolChoice.REQUIRED, limits)
        assertTrue(required.instruction.contains(DshPromptedToolProtocol.REQUIRED_TOOL_NOTE))

        val disabled = DshPromptedToolProtocol.buildInstruction(tools, DshToolChoice.NONE, limits)
        assertEquals("", disabled.instruction)
        assertTrue(disabled.offeredNames.isEmpty())
        assertTrue(disabled.omittedNames.isEmpty())

        val noTools = DshPromptedToolProtocol.buildInstruction(emptyList(), DshToolChoice.AUTO, limits)
        assertEquals("", noTools.instruction)
        assertTrue(noTools.offeredNames.isEmpty())
    }

    @Test
    fun `instruction stays within the catalog budget and names omitted tools`() {
        val tight = limits.copy(
            // The fixed rules/guide alone are ~1.3k chars after the exact-name rule was added;
            // this budget still leaves room for one small tool and no room for the huge one.
            maxPromptedToolCatalogChars = 2_000,
            maxToolParametersChars = 32
        )
        val huge = toolDefinition(
            "huge",
            parametersJson = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}"
        )
        val small = toolDefinition("read")
        val catalog = DshPromptedToolProtocol.buildInstruction(
            listOf(huge, small),
            DshToolChoice.AUTO,
            tight
        )
        assertTrue(catalog.instruction.length <= tight.maxPromptedToolCatalogChars)
        assertTrue(catalog.instruction.endsWith("do not emit the block."))
        assertTrue(catalog.instruction.contains("not available"))
        assertTrue(catalog.instruction.contains("huge"))
        assertTrue(catalog.omittedNames.contains("huge"))
        assertEquals(2, catalog.offeredNames.size + catalog.omittedNames.size)
    }

    @Test
    fun `builder reports an oversized description as omitted instead of truncating it`() {
        val tight = limits.copy(maxToolDescriptionChars = 8)
        val longDescription = toolDefinition("read", description = "This description is far longer than eight characters.")
        val other = toolDefinition("bash", description = "ok")
        val catalog = DshPromptedToolProtocol.buildInstruction(
            listOf(longDescription, other),
            DshToolChoice.AUTO,
            tight
        )
        assertEquals(listOf("bash"), catalog.offeredNames)
        assertEquals(listOf("read"), catalog.omittedNames)
        assertTrue(catalog.instruction.contains("read"))
        assertFalse(catalog.instruction.contains("far longer than eight"))
    }

    @Test
    fun `local budget offers bash with its real description ahead of family variants`() {
        val local = limits.forLocalPrompted()
        val bash = toolDefinition(
            "bash",
            description = "x".repeat(795),
            parametersJson = "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}," +
                "\"timeoutMs\":{\"type\":\"integer\"}}}"
        )
        val core = listOf("read", "write", "edit", "glob", "grep").map { toolDefinition(it) }
        val familyNames = listOf("read_image", "read_mcp_resource", "list_agents", "list_mcp_resources")
        val family = familyNames.map { toolDefinition(it, description = "y".repeat(300)) }
        val decorative = (1..10).map { toolDefinition("decorative_$it", description = "z".repeat(400)) }

        val catalog = DshPromptedToolProtocol.buildInstruction(
            core + family + decorative + bash,
            DshToolChoice.AUTO,
            local
        )

        assertTrue("bash must be offered under the local budget", "bash" in catalog.offeredNames)
        val bashIndex = catalog.offeredNames.indexOf("bash")
        catalog.offeredNames.filter { it in familyNames }.forEach {
            assertTrue("$it must not outrank bash", catalog.offeredNames.indexOf(it) > bashIndex)
        }
        assertTrue(catalog.instruction.length <= local.maxPromptedToolCatalogChars)
    }

    @Test
    fun `local budget keeps bash and the verified job tools`() {
        val local = limits.forLocalPrompted()
        // Real local DSH surface: the standard preset loads tool-bash and tool-jobs, and the
        // host's captured request header named job_kill, job_list and job_output next to the
        // file tools (`raw/dsh-request-header-4b.json`). The previous 8 KiB catalog omitted
        // them, so the host rejected a real `job_list` call as an unoffered tool.
        val shellAndFile = listOf(
            toolDefinition(
                "bash",
                description = "x".repeat(795),
                parametersJson = "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}," +
                    "\"run_in_background\":{\"type\":\"boolean\"}}}"
            ),
            toolDefinition("terminal"),
            toolDefinition("read"),
            toolDefinition("write"),
            toolDefinition("edit"),
            toolDefinition("glob"),
            toolDefinition("grep")
        )
        val jobTools = listOf(
            toolDefinition(
                "job_list",
                description = "List your background jobs (running and finished) with their ids, kinds, and statuses.",
                parametersJson = "{\"type\":\"object\",\"properties\":{}}"
            ),
            toolDefinition(
                "job_kill",
                description = "Request cancellation of a running background job.",
                parametersJson = "{\"type\":\"object\",\"properties\":{\"job_id\":{\"type\":\"string\"}}}"
            ),
            toolDefinition(
                "job_output",
                description = "Read a background job: output since the previous read for stream jobs, " +
                    "or the result of a finished final-output job.",
                parametersJson = "{\"type\":\"object\",\"properties\":{\"job_id\":{\"type\":\"string\"}}}"
            )
        )
        val extras = listOf("read_image", "list_agents", "schedule_create", "workflow").map {
            toolDefinition(it, description = "y".repeat(400))
        } + (1..8).map { toolDefinition("decorative_$it", description = "z".repeat(400)) }
        val all = shellAndFile + jobTools + extras

        // Reversed input order: priority must come from the essential order, not the request order.
        val catalog = DshPromptedToolProtocol.buildInstruction(all.reversed(), DshToolChoice.AUTO, local)

        val coreThenJobs = listOf("bash", "terminal", "read", "write", "job_list", "job_kill", "job_output")
        coreThenJobs.forEach {
            assertTrue("$it must be offered under the local budget", it in catalog.offeredNames)
        }
        assertEquals(
            "the shell/file core must outrank the job controls",
            coreThenJobs,
            catalog.offeredNames.filter { it in coreThenJobs }
        )
        assertTrue(catalog.instruction.length <= local.maxPromptedToolCatalogChars)
        assertTrue("decorative tools must not displace the essentials", catalog.omittedNames.isNotEmpty())

        // Honest budget: every submitted tool is either offered or named as unavailable, once.
        val allNames = all.map { it.name }
        assertEquals(allNames.size, catalog.offeredNames.size + catalog.omittedNames.size)
        assertEquals(allNames.toSet(), (catalog.offeredNames + catalog.omittedNames).toSet())
        catalog.offeredNames.forEach { assertFalse("$it is both offered and omitted", it in catalog.omittedNames) }
        val unavailableLine = catalog.instruction.lineSequence()
            .first { it.startsWith("The following tools are not available") }
        catalog.omittedNames.forEach {
            assertTrue("$it must be named in the unavailable line", unavailableLine.contains(it))
        }

        // An omitted name is not callable even when other job tools are offered.
        val omittedName = catalog.omittedNames.first()
        assertEquals(
            DshPromptedParse.UnknownTools(listOf(omittedName)),
            DshPromptedToolProtocol.parse(
                envelope(omittedName to "{}"),
                catalog.offeredNames,
                TEST_CALL_ID,
                local
            )
        )
    }

    @Test
    fun `local budget keeps a configured MCP tool ahead of bash-coverable discovery`() {
        val local = limits.forLocalPrompted()
        // A user-configured stdio MCP server (device: mcp-servers.json + mcp-echo-server.mjs)
        // contributes namespaced tools that are only reachable through the MCP host call. Bash can
        // still edit and discover files, so under budget pressure those must be dropped first.
        val essentials = listOf(
            toolDefinition(
                "bash",
                description = "x".repeat(795),
                parametersJson = "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}}}"
            ),
            toolDefinition("read", description = "r".repeat(200)),
            toolDefinition("write", description = "w".repeat(200)),
            toolDefinition(
                "job_list",
                description = "List your background jobs (running and finished) with their ids, kinds, and statuses.",
                parametersJson = "{\"type\":\"object\",\"properties\":{}}"
            )
        )
        val mcpTool = toolDefinition(
            "mcp__echo__echo",
            description = "Echo text",
            parametersJson = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}"
        )
        val discovery = listOf("edit", "apply_patch", "list", "glob", "grep", "search").map {
            toolDefinition(it, description = "d".repeat(2_000))
        }
        val decorative = (1..6).map { toolDefinition("decorative_$it", description = "z".repeat(2_000)) }
        val submitted = discovery + decorative + listOf(mcpTool) + essentials

        // Reversed submission order: the ranking, not the request order, decides the outcome.
        val catalog = DshPromptedToolProtocol.buildInstruction(submitted.reversed(), DshToolChoice.AUTO, local)

        assertTrue("bash must still be offered", "bash" in catalog.offeredNames)
        assertTrue(
            "a configured MCP tool must survive the local catalog budget",
            "mcp__echo__echo" in catalog.offeredNames
        )
        assertTrue(
            "the fixture must actually exert budget pressure",
            catalog.omittedNames.isNotEmpty()
        )
        val mcpIndex = catalog.offeredNames.indexOf("mcp__echo__echo")
        discovery.forEach { tool ->
            val index = catalog.offeredNames.indexOf(tool.name)
            if (index >= 0) {
                assertTrue("${tool.name} must not outrank a configured MCP tool", index > mcpIndex)
            }
        }
        assertTrue(
            "bash-coverable discovery must yield before the MCP tool",
            discovery.any { it.name in catalog.omittedNames }
        )
        assertTrue(catalog.instruction.length <= local.maxPromptedToolCatalogChars)

        // Dropping is explicit, never silent: every submitted tool is offered or named once.
        val allNames = submitted.map { it.name }
        assertEquals(allNames.size, catalog.offeredNames.size + catalog.omittedNames.size)
        assertEquals(allNames.toSet(), (catalog.offeredNames + catalog.omittedNames).toSet())
        val unavailableLine = catalog.instruction.lineSequence()
            .first { it.startsWith("The following tools are not available") }
        catalog.omittedNames.forEach {
            assertTrue("$it must be named in the unavailable line", unavailableLine.contains(it))
        }

        // The offered MCP tool parses into a real call with its arguments intact.
        val parsed = DshPromptedToolProtocol.parse(
            envelope("mcp__echo__echo" to "{\"text\":\"hello\"}"),
            catalog.offeredNames,
            TEST_CALL_ID,
            local
        )
        assertEquals(1, (parsed as DshPromptedParse.Calls).calls.size)
        assertEquals("mcp__echo__echo", parsed.calls.single().name)
    }

    @Test
    fun `local budget keeps the official MCP resource discovery tools`() {
        val local = limits.forLocalPrompted()
        // Official tools registered by `@deepseek-ai/dsh-mcp-resources` (payload row
        // `mcp-resources`). `read_mcp_resource` already lives in the `read` family; the two
        // discovery tools used to fall into the `list` family and were trimmed before bash-coverable
        // discovery, leaving the model unable to learn any resource URI.
        val resourceTools = listOf(
            toolDefinition(
                "list_mcp_resources",
                description = "List resources available from an MCP server.",
                parametersJson = "{\"type\":\"object\",\"properties\":{\"server\":{\"type\":\"string\"}," +
                    "\"cursor\":{\"type\":\"string\"}}}"
            ),
            toolDefinition(
                "list_mcp_resource_templates",
                description = "List parameterized resource URI templates from an MCP server.",
                parametersJson = "{\"type\":\"object\",\"properties\":{\"server\":{\"type\":\"string\"}," +
                    "\"cursor\":{\"type\":\"string\"}}}"
            ),
            toolDefinition(
                "read_mcp_resource",
                description = "Read an MCP resource by URI from the named server.",
                parametersJson = "{\"type\":\"object\",\"properties\":{\"server\":{\"type\":\"string\"}," +
                    "\"uri\":{\"type\":\"string\"}}}"
            )
        )
        val essentials = listOf(
            toolDefinition(
                "bash",
                description = "x".repeat(795),
                parametersJson = "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}}}"
            ),
            toolDefinition("read", description = "r".repeat(120)),
            toolDefinition("write", description = "w".repeat(120)),
            toolDefinition(
                "job_list",
                description = "List your background jobs (running and finished) with their ids, kinds, and statuses.",
                parametersJson = "{\"type\":\"object\",\"properties\":{}}"
            ),
            toolDefinition(
                "job_kill",
                description = "Request cancellation of a running background job.",
                parametersJson = "{\"type\":\"object\",\"properties\":{\"job_id\":{\"type\":\"string\"}}}"
            ),
            toolDefinition(
                "job_output",
                description = "Read a background job: output since the previous read for stream jobs.",
                parametersJson = "{\"type\":\"object\",\"properties\":{\"job_id\":{\"type\":\"string\"}}}"
            )
        )
        val pressure = (1..20).map { toolDefinition("decorative_$it", description = "z".repeat(800)) } +
            listOf("list", "glob", "grep", "search", "edit", "apply_patch").map {
                toolDefinition(it, description = "d".repeat(800))
            }

        val catalog = DshPromptedToolProtocol.buildInstruction(
            (pressure + resourceTools + essentials).reversed(),
            DshToolChoice.AUTO,
            local
        )

        resourceTools.forEach {
            assertTrue("${it.name} must survive the local catalog budget", it.name in catalog.offeredNames)
        }
        val offered = catalog.offeredNames
        assertTrue(
            "the fixture must exert budget pressure",
            catalog.omittedNames.isNotEmpty()
        )
        assertTrue(
            "bash-coverable discovery must yield before MCP resource discovery",
            listOf("list", "glob", "grep", "search").any { it in catalog.omittedNames }
        )
        assertTrue(
            "MCP resource discovery must not outrank the shell",
            offered.indexOf("list_mcp_resources") > offered.indexOf("bash")
        )
        assertTrue(
            "MCP resource discovery must rank with the MCP slot after the job controls",
            offered.indexOf("list_mcp_resources") > offered.indexOf("job_output")
        )
        assertTrue(catalog.instruction.length <= local.maxPromptedToolCatalogChars)

        // The discovery tool is callable with the schema the model was offered.
        val parsed = DshPromptedToolProtocol.parse(
            envelope("list_mcp_resources" to "{\"server\":\"echo\"}"),
            offered,
            TEST_CALL_ID,
            local
        )
        assertEquals("list_mcp_resources", (parsed as DshPromptedParse.Calls).calls.single().name)
    }

    @Test
    fun `unknown job-like tool name is still rejected when the job tools are offered`() {
        val offered = listOf("bash", "job_list", "job_kill", "job_output")
        assertEquals(
            DshPromptedParse.UnknownTools(listOf("job_wait")),
            DshPromptedToolProtocol.parse(
                envelope("job_wait" to "{\"job_id\":\"bash-1\"}"),
                offered,
                TEST_CALL_ID,
                limits
            )
        )
        // A family prefix must not bless a sibling name the catalog never offered.
        assertEquals(
            DshPromptedParse.UnknownTools(listOf("job")),
            DshPromptedToolProtocol.parse(envelope("job" to "{}"), offered, TEST_CALL_ID, limits)
        )
        // The XML envelope form is held to the same exact-name set.
        val xml = "<tool_call><function=job_wait><parameter=job_id>bash-1</parameter></function></tool_call>"
        assertTrue(
            DshPromptedToolProtocol.parse(xml, offered, TEST_CALL_ID, limits)
                is DshPromptedParse.UnknownTools
        )
        // Control: a genuinely offered job tool still parses.
        val parsed = DshPromptedToolProtocol.parse(envelope("job_list" to "{}"), offered, TEST_CALL_ID, limits)
        assertTrue(parsed is DshPromptedParse.Calls)
        assertEquals("job_list", (parsed as DshPromptedParse.Calls).calls.single().name)
    }

    @Test
    fun `job controls rank after the shell and file core`() {
        val tools = listOf(
            "job_output", "edit", "job_kill", "write", "job_list", "read",
            "terminal", "bash_persistent", "bash"
        ).map { toolDefinition(it) }
        val catalog = DshPromptedToolProtocol.buildInstruction(tools, DshToolChoice.AUTO, limits)
        assertEquals(
            listOf(
                "bash", "bash_persistent", "terminal", "read", "write",
                "job_list", "job_kill", "job_output", "edit"
            ),
            catalog.offeredNames
        )
        assertTrue(catalog.omittedNames.isEmpty())
        assertTrue(catalog.instruction.length <= limits.maxPromptedToolCatalogChars)
    }

    @Test
    fun `core tools are packed before decorative tools in a tight catalog`() {
        val tight = limits.copy(maxPromptedToolCatalogChars = 3_000)
        val tools = listOf(
            toolDefinition("zebra"),
            toolDefinition("read"),
            toolDefinition("write"),
            toolDefinition("bash")
        ) + (1..20).map { index -> toolDefinition("decorative_$index") }
        val catalog = DshPromptedToolProtocol.buildInstruction(tools, DshToolChoice.AUTO, tight)
        assertTrue("read must survive", "read" in catalog.offeredNames)
        assertTrue("write must survive", "write" in catalog.offeredNames)
        assertTrue("the 24-tool surface cannot fit a quarter budget", catalog.omittedNames.isNotEmpty())
        assertTrue(catalog.instruction.length <= tight.maxPromptedToolCatalogChars)
    }

    @Test
    fun `builder rejects a catalog whose omitted-tool report cannot fit its budget`() {
        val tight = limits.copy(maxPromptedToolCatalogChars = 1_024)
        val manyLongNames = (1..20).map { index ->
            toolDefinition("tool_${"x".repeat(90)}_$index")
        }
        try {
            DshPromptedToolProtocol.buildInstruction(manyLongNames, DshToolChoice.AUTO, tight)
            org.junit.Assert.fail("Expected DshMappingException")
        } catch (expected: DshMappingException) {
            assertEquals(DshAdapterErrorCodes.TOOL_CATALOG_TOO_LARGE, expected.code)
        }
    }

    @Test
    fun `normal answer without envelope is plain text`() {
        val parsed = DshPromptedToolProtocol.parse("Just an answer.", listOf("read"), TEST_CALL_ID, limits)
        assertEquals(DshPromptedParse.NoCalls("Just an answer."), parsed)
    }

    @Test
    fun `single call is parsed with a stable id and complete argument object`() {
        val raw = "Sure.\n" + envelope("read" to "{\"path\":\"a.txt\"}")
        val parsed = DshPromptedToolProtocol.parse(raw, listOf("read", "bash"), TEST_CALL_ID, limits)
        assertTrue(parsed is DshPromptedParse.Calls)
        val calls = (parsed as DshPromptedParse.Calls).calls
        assertEquals(1, calls.size)
        assertEquals("read", calls[0].name)
        assertEquals("{\"path\":\"a.txt\"}", calls[0].argumentsJson)
        assertEquals(
            DshPromptedToolProtocol.promptedToolCallId(TEST_CALL_ID, 0),
            calls[0].id
        )
        val again = DshPromptedToolProtocol.parse(raw, listOf("read", "bash"), TEST_CALL_ID, limits)
        assertEquals(calls, (again as DshPromptedParse.Calls).calls)
    }

    @Test
    fun `multiple calls keep order and get distinct ids`() {
        val raw = envelope("read" to "{\"path\":\"a\"}", "bash" to "{\"cmd\":\"ls\"}")
        val parsed = DshPromptedToolProtocol.parse(raw, listOf("read", "bash"), TEST_CALL_ID, limits)
        val calls = (parsed as DshPromptedParse.Calls).calls
        assertEquals(listOf("read", "bash"), calls.map { it.name })
        assertEquals(2, calls.map { it.id }.toSet().size)
    }

    @Test
    fun `tool names match case-insensitively to the canonical catalog name`() {
        val raw = envelope("READ" to "{\"path\":\"a\"}")
        val parsed = DshPromptedToolProtocol.parse(raw, listOf("read"), TEST_CALL_ID, limits)
        assertEquals("read", (parsed as DshPromptedParse.Calls).calls.single().name)
    }

    @Test
    fun `unknown tool name is rejected and never becomes a call`() {
        val raw = envelope("write" to "{\"path\":\"a\"}")
        val parsed = DshPromptedToolProtocol.parse(raw, listOf("read"), TEST_CALL_ID, limits)
        assertEquals(DshPromptedParse.UnknownTools(listOf("write")), parsed)
    }

    @Test
    fun `arguments may be a JSON object string but not an array`() {
        val asString = envelope("read" to "\"{\\\"path\\\":\\\"a\\\"}\"")
        val parsed = DshPromptedToolProtocol.parse(asString, listOf("read"), TEST_CALL_ID, limits)
        assertEquals(
            "read",
            (parsed as DshPromptedParse.Calls).calls.single().name
        )

        val arrayArguments = envelope("read" to "[\"a\"]")
        val rejected = DshPromptedToolProtocol.parse(arrayArguments, listOf("read"), TEST_CALL_ID, limits)
        assertTrue(rejected is DshPromptedParse.Malformed)
    }

    @Test
    fun `malformed json is a semantic failure and never becomes calls`() {
        val raw = DshPromptedToolProtocol.START_MARKER + "\nnot json\n" +
            DshPromptedToolProtocol.END_MARKER
        val parsed = DshPromptedToolProtocol.parse(raw, listOf("read"), TEST_CALL_ID, limits)
        assertEquals(DshPromptedParse.Malformed(raw), parsed)
    }

    @Test
    fun `envelope must be the tail and must have a closing marker`() {
        val trailing = envelope("read" to "{}") + "\nand more prose"
        assertTrue(DshPromptedToolProtocol.parse(trailing, listOf("read"), TEST_CALL_ID, limits) is DshPromptedParse.Malformed)

        val unclosed = DshPromptedToolProtocol.START_MARKER + "\n{\"tool_calls\":["
        assertTrue(DshPromptedToolProtocol.parse(unclosed, listOf("read"), TEST_CALL_ID, limits) is DshPromptedParse.Malformed)
    }

    @Test
    fun `strict envelope rejects extra keys and empty call arrays`() {
        val extraKey = DshPromptedToolProtocol.START_MARKER +
            "\n{\"tool_calls\":[{\"name\":\"read\",\"arguments\":{},\"id\":\"x\"}]}\n" +
            DshPromptedToolProtocol.END_MARKER
        assertTrue(DshPromptedToolProtocol.parse(extraKey, listOf("read"), TEST_CALL_ID, limits) is DshPromptedParse.Malformed)

        val extraRoot = DshPromptedToolProtocol.START_MARKER +
            "\n{\"tool_calls\":[{\"name\":\"read\",\"arguments\":{}}],\"text\":\"x\"}\n" +
            DshPromptedToolProtocol.END_MARKER
        assertTrue(DshPromptedToolProtocol.parse(extraRoot, listOf("read"), TEST_CALL_ID, limits) is DshPromptedParse.Malformed)

        val empty = DshPromptedToolProtocol.START_MARKER +
            "\n{\"tool_calls\":[]}\n" + DshPromptedToolProtocol.END_MARKER
        assertTrue(DshPromptedToolProtocol.parse(empty, listOf("read"), TEST_CALL_ID, limits) is DshPromptedParse.Malformed)
    }

    @Test
    fun `call count above the configured maximum is a distinct semantic rejection`() {
        val tight = limits.copy(maxPromptedToolCalls = 1)
        val raw = envelope("read" to "{}", "read" to "{}")
        assertEquals(
            DshPromptedParse.TooManyCalls(2),
            DshPromptedToolProtocol.parse(raw, listOf("read"), TEST_CALL_ID, tight)
        )
    }

    @Test
    fun `parallel multiple calls are rejected when parallel calling is disabled`() {
        val raw = envelope("read" to "{}", "bash" to "{}")
        assertEquals(
            DshPromptedParse.ParallelCallsForbidden(2),
            DshPromptedToolProtocol.parse(
                raw,
                listOf("read", "bash"),
                TEST_CALL_ID,
                limits,
                allowParallelCalls = false
            )
        )
        // The same answer stays valid when parallel calling is allowed.
        assertTrue(
            DshPromptedToolProtocol.parse(raw, listOf("read", "bash"), TEST_CALL_ID, limits)
                is DshPromptedParse.Calls
        )
    }

    @Test
    fun `arguments above the explicit per-call limit are rejected without truncation`() {
        val tight = limits.copy(maxToolCallArgumentsChars = 64)
        val raw = envelope("read" to "{\"path\":\"${"x".repeat(80)}\"}")
        assertEquals(
            DshPromptedParse.ArgumentsTooLarge(listOf("read")),
            DshPromptedToolProtocol.parse(raw, listOf("read"), TEST_CALL_ID, tight)
        )
    }

    @Test
    fun `envelope ids are rejected so duplicate or missing ids cannot be fabricated`() {
        val withDuplicateIds = DshPromptedToolProtocol.START_MARKER +
            "\n{\"tool_calls\":[" +
            "{\"id\":\"call_1\",\"name\":\"read\",\"arguments\":{}}," +
            "{\"id\":\"call_1\",\"name\":\"read\",\"arguments\":{}}]}\n" +
            DshPromptedToolProtocol.END_MARKER
        assertTrue(
            DshPromptedToolProtocol.parse(withDuplicateIds, listOf("read"), TEST_CALL_ID, limits)
                is DshPromptedParse.Malformed
        )
        assertTrue(
            DshPromptedToolProtocol.parse(envelope("read" to "{}"), listOf("read"), TEST_CALL_ID, limits)
                is DshPromptedParse.Calls
        )
    }

    @Test
    fun `streaming gate emits text before the marker and holds the envelope until finish`() {
        val gate = DshPromptedToolStreamGate(listOf("read"), TEST_CALL_ID, limits)
        val emitted = StringBuilder()
        emitted.append(gate.feed("Let me check"))

        val full = envelope("read" to "{\"path\":\"a\"}")
        // Split the start marker itself across two fragments: no part of it may leak as text.
        val splitAt = 12
        emitted.append(gate.feed("\n" + full.substring(0, splitAt)))
        emitted.append(gate.feed(full.substring(splitAt)))

        val parsed = gate.finish()
        assertEquals("Let me check\n", emitted.toString())
        assertEquals("read", (parsed as DshPromptedParse.Calls).calls.single().name)
        assertEquals("", gate.flushForError())
    }

    @Test
    fun `streaming gate holds a partial marker suffix and flushes it at finish`() {
        val gate = DshPromptedToolStreamGate(listOf("read"), TEST_CALL_ID, limits)
        assertEquals("abc", gate.feed("abc<"))
        val parsed = gate.finish()
        assertEquals(DshPromptedParse.NoCalls("<"), parsed)
    }

    @Test
    fun `oversized envelope is treated as plain text and stays bounded`() {
        val tight = limits.copy(maxPromptedEnvelopeChars = 512)
        val gate = DshPromptedToolStreamGate(listOf("read"), TEST_CALL_ID, tight)
        val huge = DshPromptedToolProtocol.START_MARKER + "\n" + "x".repeat(2_000)
        val visible = gate.feed(huge)
        assertTrue(visible.startsWith(DshPromptedToolProtocol.START_MARKER))
        assertEquals(DshPromptedParse.NoCalls(""), gate.finish())
        assertEquals("tail", gate.feed("tail"))
    }

    @Test
    fun `gate carries the parallel policy into the final parse`() {
        val gate = DshPromptedToolStreamGate(
            offeredNames = listOf("read", "bash"),
            callId = TEST_CALL_ID,
            limits = limits,
            allowParallelCalls = false
        )
        gate.feed(envelope("read" to "{}", "bash" to "{}"))
        assertEquals(DshPromptedParse.ParallelCallsForbidden(2), gate.finish())
    }

    @Test
    fun `cancelled prompted stream flushes held envelope text`() {
        val gate = DshPromptedToolStreamGate(listOf("read"), TEST_CALL_ID, limits)
        assertEquals("pre", gate.feed("pre" + DshPromptedToolProtocol.START_MARKER + "\n{"))
        val flushed = gate.flushForError()
        assertTrue(flushed.contains(DshPromptedToolProtocol.START_MARKER))
        assertTrue(flushed.endsWith("{"))
    }

    @Test
    fun `near-miss closing brackets still parse a complete call and never leak`() {
        val raw = "Sure.\n<<<MCA_TOOL_CALLS_V1>>\n" +
            "{\"tool_calls\":[{\"name\":\"read\",\"arguments\":{\"path\":\"a\"}}]}"
        val parsed = DshPromptedToolProtocol.parse(raw, listOf("read"), TEST_CALL_ID, limits)
        assertTrue(parsed is DshPromptedParse.Calls)
        assertEquals("read", (parsed as DshPromptedParse.Calls).calls.single().name)
    }

    @Test
    fun `streaming gate holds a near-miss marker and never leaks it as text`() {
        val gate = DshPromptedToolStreamGate(listOf("read"), TEST_CALL_ID, limits)
        val emitted = StringBuilder()
        emitted.append(gate.feed("pre"))
        emitted.append(
            gate.feed(
                "<<<MCA_TOOL_CALLS_V1>>\n" +
                    "{\"tool_calls\":[{\"name\":\"read\",\"arguments\":{}}]}"
            )
        )
        val parsed = gate.finish()
        assertEquals("pre", emitted.toString())
        assertTrue(parsed is DshPromptedParse.Calls)
        assertTrue(!emitted.toString().contains(DshPromptedToolProtocol.MARKER_CORE))
    }

    @Test
    fun `invented protocol family with complete json is parsed and never leaks`() {
        val raw = "<<<BASH_TOOL_CALLS_V1>>{\"tool_calls\":[{\"name\":\"bash\"," +
            "\"arguments\":{\"command\":\"echo hi\"}}]}"
        val parsed = DshPromptedToolProtocol.parse(raw, listOf("bash"), TEST_CALL_ID, limits)
        assertTrue(parsed is DshPromptedParse.Calls)
        assertEquals("bash", (parsed as DshPromptedParse.Calls).calls.single().name)

        val gate = DshPromptedToolStreamGate(listOf("bash"), TEST_CALL_ID, limits)
        val emitted = StringBuilder()
        emitted.append(gate.feed("pre"))
        emitted.append(gate.feed(raw))
        assertEquals("pre", emitted.toString())
        assertTrue(gate.finish() is DshPromptedParse.Calls)
    }

    @Test
    fun `invented family with invalid json is still malformed`() {
        val raw = "<<<BASH_TOOL_CALLS_V1>>{\"tool_calls\":[{\"name\":\"bash\""
        val parsed = DshPromptedToolProtocol.parse(raw, listOf("bash"), TEST_CALL_ID, limits)
        assertTrue(parsed is DshPromptedParse.Malformed)
    }

    @Test
    fun `xml function-call form is parsed into the same validated calls`() {
        val raw = "<tool_call><function=bash><parameter=description>Run a command</parameter>" +
            "<parameter=command>echo hi</parameter></function></tool_call>"
        val parsed = DshPromptedToolProtocol.parse(raw, listOf("bash"), "call", limits)
        assertTrue(parsed is DshPromptedParse.Calls)
        val call = (parsed as DshPromptedParse.Calls).calls.single()
        assertEquals("bash", call.name)
        assertTrue(call.argumentsJson.contains("\"command\":\"echo hi\""))
        assertTrue(call.argumentsJson.contains("\"description\":\"Run a command\""))
    }

    @Test
    fun `xml parameters coerce booleans and numbers for strict schemas`() {
        val raw = "<tool_call><function=bash><parameter=command>echo hi</parameter>" +
            "<parameter=run_in_background>true</parameter>" +
            "<parameter=timeoutMs>1234</parameter></function></tool_call>"
        val parsed = DshPromptedToolProtocol.parse(raw, listOf("bash"), "call", limits)
        assertTrue(parsed is DshPromptedParse.Calls)
        val args = (parsed as DshPromptedParse.Calls).calls.single().argumentsJson
        assertTrue(args.contains("\"run_in_background\":true"))
        assertTrue(args.contains("\"timeoutMs\":1234"))
    }

    @Test
    fun `xml function-call with an unknown name is a typed failure`() {
        val raw = "<tool_call><function=nope><parameter=x>1</parameter></function></tool_call>"
        val parsed = DshPromptedToolProtocol.parse(raw, listOf("bash"), "call", limits)
        assertTrue(parsed is DshPromptedParse.UnknownTools)
    }

    @Test
    fun `stream gate holds an xml call instead of leaking it as text`() {
        val gate = DshPromptedToolStreamGate(listOf("bash"), "call", limits)
        assertTrue(gate.feed("<tool_call><function=bash><parameter=command>").isEmpty())
        assertTrue(gate.feed("echo hi</parameter></function></tool_call>").isEmpty())
        val parsed = gate.finish()
        assertTrue(parsed is DshPromptedParse.Calls)
        assertEquals("bash", (parsed as DshPromptedParse.Calls).calls.single().name)
    }

    @Test
    fun `qwen json guide uses the native tool_call object`() {
        val tools = listOf(toolDefinition("bash"))
        val catalog = DshPromptedToolProtocol.buildInstruction(
            tools,
            DshToolChoice.AUTO,
            limits,
            DshToolEnvelopeStyle.QWEN_JSON
        )
        assertTrue(
            catalog.instruction.contains(
                "<tool_call>\n{\"name\":\"bash\",\"arguments\":{\"command\":\"pwd\"}}"
            )
        )
        assertTrue(catalog.instruction.contains("<tools>"))
        assertTrue(catalog.instruction.contains("\"function\":{\"name\":\"bash\""))
        assertTrue(catalog.instruction.contains("\"arguments\""))
        assertFalse(catalog.instruction.contains(DshPromptedToolProtocol.START_MARKER))
    }

    @Test
    fun `qwen json tool_call form is parsed with the same strict rules`() {
        val raw = "<tool_call>\n{\"name\":\"bash\",\"arguments\":{\"command\":\"echo hi\"}}\n</tool_call>"
        val parsed = DshPromptedToolProtocol.parse(raw, listOf("bash"), "call", limits)
        assertTrue(parsed is DshPromptedParse.Calls)
        val call = (parsed as DshPromptedParse.Calls).calls.single()
        assertEquals("bash", call.name)
        assertTrue(call.argumentsJson.contains("\"command\":\"echo hi\""))
    }

    @Test
    fun `qwen json tool_call rejects ids and unknown tools`() {
        val withId = "<tool_call>{\"name\":\"bash\",\"arguments\":{},\"id\":\"x\"}</tool_call>"
        val rejected = DshPromptedToolProtocol.parse(withId, listOf("bash"), "call", limits)
        assertTrue(rejected is DshPromptedParse.Malformed)
        val unknown = "<tool_call>{\"name\":\"nope\",\"arguments\":{}}</tool_call>"
        val parsed = DshPromptedToolProtocol.parse(unknown, listOf("bash"), "call", limits)
        assertTrue(parsed is DshPromptedParse.UnknownTools)
    }

    @Test
    fun `stream gate holds a qwen json call instead of leaking it as text`() {
        val gate = DshPromptedToolStreamGate(listOf("bash"), "call", limits)
        assertTrue(gate.feed("<tool_call>{\"name\":\"bash\",").isEmpty())
        assertTrue(gate.feed("\"arguments\":{\"command\":\"echo hi\"}}</tool_call>").isEmpty())
        val parsed = gate.finish()
        assertTrue(parsed is DshPromptedParse.Calls)
        assertEquals("bash", (parsed as DshPromptedParse.Calls).calls.single().name)
    }
}

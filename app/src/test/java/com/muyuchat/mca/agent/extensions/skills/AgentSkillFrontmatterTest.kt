package com.muyuchat.mca.agent.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillFrontmatterTest {
    private val parser = AgentSkillFrontmatterParser()

    @Test
    fun `parses scalar comma list block list folded block and unknown metadata`() {
        val markdown = """
            ---
            name: Matrix Skill
            description: >
              Folded description
              on two lines
            allowed-tools: [workspace.*, artifact.write]
            version: 2.1.0
            x-harness: other-harness
            x-tags:
              - alpha
              - beta
            ---
            # Body
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val parsed = parser.parse(markdown)

        assertEquals("Matrix Skill", parsed.frontmatter.name)
        assertEquals("Folded description on two lines\n", parsed.frontmatter.description)
        assertEquals("2.1.0", parsed.frontmatter.packageVersion)
        assertEquals(
            listOf("workspace.*", "artifact.write"),
            parsed.frontmatter.allowedTools!!.patterns.map { it.canonical }
        )
        assertEquals(
            AgentSkillMetadataValue.Text("other-harness"),
            parsed.frontmatter.metadata["x-harness"]
        )
        assertEquals(
            AgentSkillMetadataValue.ListValue(listOf("alpha", "beta")),
            parsed.frontmatter.metadata["x-tags"]
        )
        assertTrue(parsed.body.startsWith("# Body"))
    }

    @Test
    fun `parses literal block scalar block list and scalar comma list`() {
        val literal = """
            ---
            name: Literal Skill
            description: |
              line one
              line two
            allowed-tools:
              - mcp:files:*
              - workspace.read
            ---
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val literalParsed = parser.parse(literal)
        assertEquals("line one\nline two\n", literalParsed.frontmatter.description)
        assertEquals(
            listOf("mcp:files:*", "workspace.read"),
            literalParsed.frontmatter.allowedTools!!.patterns.map { it.canonical }
        )

        val comma = """
            ---
            name: Comma Skill
            description: plain description
            allowed-tools: workspace.*, artifact.write
            ---
        """.trimIndent().toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf("workspace.*", "artifact.write"),
            parser.parse(comma).frontmatter.allowedTools!!.patterns.map { it.canonical }
        )
    }

    @Test
    fun `rejects unsupported yaml constructs with typed errors`() {
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.FRONTMATTER_UNSUPPORTED) {
            parser.parse(frontmatter("description: !custom tagged"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.FRONTMATTER_UNSUPPORTED) {
            parser.parse(frontmatter("description: &anchor anchored"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.FRONTMATTER_UNSUPPORTED) {
            parser.parse(frontmatter("description: *alias"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.FRONTMATTER_UNSUPPORTED) {
            parser.parse(frontmatter("description: {a: b}"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.FRONTMATTER_UNSUPPORTED) {
            parser.parse(frontmatter("description: plain", "  nested: map"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.FRONTMATTER_UNSUPPORTED) {
            parser.parse(frontmatter("description: |+", "  text"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.FRONTMATTER_DUPLICATE_KEY) {
            parser.parse(frontmatter("description: plain", "name: Two"))
        }
    }

    @Test
    fun `rejects name and description bounds`() {
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.NAME_INVALID) {
            parser.parse(
                "---\nname: ${"x".repeat(65)}\ndescription: bounded\n---\n".toByteArray(Charsets.UTF_8)
            )
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.DESCRIPTION_TOO_LARGE) {
            parser.parse(
                "---\nname: Bounded\ndescription: ${"y".repeat(2 * 1024 + 1)}\n---\n".toByteArray(Charsets.UTF_8)
            )
        }
    }

    @Test
    fun `rejects invalid allowed-tools forms`() {
        // An unquoted leading '*' is a YAML alias token and is rejected as unsupported syntax.
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.FRONTMATTER_UNSUPPORTED) {
            parser.parse(markdown("allowed-tools: *"))
        }
        for (invalid in listOf("workspace.?", "workspace.[ab]", "mcp:*", "mcp:Server:*", "Workspace.*", "workspace.*.read")) {
            AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.ALLOWED_TOOLS_INVALID) {
                parser.parse(markdown("allowed-tools: $invalid"))
            }
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.ALLOWED_TOOLS_INVALID) {
            parser.parse(markdown("allowed-tools: []"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.ALLOWED_TOOLS_INVALID) {
            parser.parse(markdown("allowed-tools:\n" + (1..65).joinToString("\n") { "  - workspace.t$it" }))
        }
    }

    @Test
    fun `rejects missing frontmatter oversized skill md and invalid utf8`() {
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.FRONTMATTER_MISSING) {
            parser.parse("# no frontmatter".toByteArray(Charsets.UTF_8))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.FRONTMATTER_MISSING) {
            parser.parse("---\nname: N\ndescription: D\n".toByteArray(Charsets.UTF_8))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.SKILL_MD_TOO_LARGE) {
            parser.parse(ByteArray((64 * 1024) + 1))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.INVALID_UTF8) {
            parser.parse(byteArrayOf(0x2D, 0x2D, 0x2D, 0x0A, 0xC3.toByte(), 0x28))
        }
    }

    private fun frontmatter(vararg lines: String): ByteArray = (
        "---\nname: Bounded Skill\n" + lines.joinToString("\n") + "\n---\n"
        ).toByteArray(Charsets.UTF_8)

    private fun markdown(extraLine: String): ByteArray = (
        "---\nname: Bounded Skill\ndescription: bounded description\n$extraLine\n---\n"
        ).toByteArray(Charsets.UTF_8)
}

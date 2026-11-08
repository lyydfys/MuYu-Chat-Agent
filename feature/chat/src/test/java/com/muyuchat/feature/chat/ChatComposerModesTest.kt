package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `+` menu has three turn-scoping modes and they are mutually exclusive.
 *
 * Pinning the rule here (instead of in a Compose test) is what makes it cheap to check: every case
 * below is a statement about one pure transition. The important property is not just "enabling agent
 * disables research" but "there is no reachable combination with two modes on" — a UI that could show
 * two armed modes would let a user start a turn whose owner is ambiguous.
 */
class ChatComposerModesTest {

    @Test
    fun `nothing is armed by default`() {
        val modes = ChatComposerModes()

        assertFalse(modes.agentMode)
        assertFalse(modes.imagePromptMode)
        assertFalse(modes.researchMode)
        assertFalse(modes.anyActive)
    }

    @Test
    fun `enabling agent mode clears the other two and enables itself`() {
        val modes = ChatComposerModes(imagePromptMode = true, researchMode = true)
            .toggle(ChatComposerMode.AGENT)

        assertTrue(modes.agentMode)
        assertFalse(modes.imagePromptMode)
        assertFalse(modes.researchMode)
    }

    @Test
    fun `enabling image prompt clears the other two`() {
        val modes = ChatComposerModes(agentMode = true, researchMode = true)
            .toggle(ChatComposerMode.IMAGE_PROMPT)

        assertFalse(modes.agentMode)
        assertTrue(modes.imagePromptMode)
        assertFalse(modes.researchMode)
    }

    @Test
    fun `enabling research clears the other two`() {
        val modes = ChatComposerModes(agentMode = true, imagePromptMode = true)
            .toggle(ChatComposerMode.RESEARCH)

        assertFalse(modes.agentMode)
        assertFalse(modes.imagePromptMode)
        assertTrue(modes.researchMode)
    }

    @Test
    fun `toggling the active mode turns it off and arms nothing else`() {
        listOf(
            ChatComposerMode.AGENT,
            ChatComposerMode.IMAGE_PROMPT,
            ChatComposerMode.RESEARCH
        ).forEach { mode ->
            val armed = ChatComposerModes().toggle(mode)
            val disarmed = armed.toggle(mode)

            assertFalse("$mode must be off again", disarmed.anyActive)
        }
    }

    @Test
    fun `disabling one mode never arms another`() {
        val modes = ChatComposerModes(researchMode = true)
            .disable(ChatComposerMode.AGENT)

        assertFalse(modes.agentMode)
        assertTrue("an unrelated mode must survive", modes.researchMode)
    }

    @Test
    fun `no reachable transition has two modes on`() {
        val modes = ChatComposerMode.entries
        // Exhaustive over (start state, two consecutive toggles): 8 start states x 3 x 3 transitions.
        for (mask in 0 until 8) {
            val start = ChatComposerModes(
                agentMode = mask and 1 != 0,
                imagePromptMode = mask and 2 != 0,
                researchMode = mask and 4 != 0
            ).normalized()
            modes.forEach { first ->
                val once = start.toggle(first)
                assertAtMostOne(once, "start=$start toggle=$first")
                modes.forEach { second ->
                    assertAtMostOne(once.toggle(second), "start=$start $first then $second")
                }
            }
        }
    }

    @Test
    fun `the menu row text follows the mode`() {
        assertEquals("转为 Agent 任务", ChatComposerModes.agentMenuLabel(agentMode = false))
        assertEquals("退出 Agent 模式", ChatComposerModes.agentMenuLabel(agentMode = true))
        assertEquals(
            "把这一轮交给 Agent：可读写文件、操作设备",
            ChatComposerModes.agentMenuSubtitle(agentMode = false)
        )
        assertEquals(
            "下一轮将由 Agent 执行，可调用工具并需要审批",
            ChatComposerModes.agentMenuSubtitle(agentMode = true)
        )
    }

    @Test
    fun `the placeholder follows the active mode`() {
        assertEquals(
            "描述你想让 Agent 做什么…",
            ChatComposerModes.composerPlaceholder(ChatComposerModes(agentMode = true))
        )
        assertEquals(
            "描述要生成的图片",
            ChatComposerModes.composerPlaceholder(ChatComposerModes(imagePromptMode = true))
        )
        assertEquals(
            "问问 MCA",
            ChatComposerModes.composerPlaceholder(ChatComposerModes())
        )
        assertEquals(
            "问问 MCA",
            ChatComposerModes.composerPlaceholder(ChatComposerModes(researchMode = true))
        )
    }

    @Test
    fun `agent placeholder wins over the image prompt precedence`() {
        // An impossible state can still be constructed directly; the placeholder must resolve it the
        // same way the toggle rule would (agent is the strongest claim on a turn), not by two rules.
        val both = ChatComposerModes(agentMode = true, imagePromptMode = true)

        assertEquals("描述你想让 Agent 做什么…", ChatComposerModes.composerPlaceholder(both))
    }

    private fun assertAtMostOne(modes: ChatComposerModes, label: String) {
        val on = listOf(modes.agentMode, modes.imagePromptMode, modes.researchMode).count { it }
        assertTrue("$label produced $modes", on <= 1)
    }

    /** Collapses a directly-constructed multi-mode value through the same rule a user tap uses. */
    private fun ChatComposerModes.normalized(): ChatComposerModes = when {
        agentMode -> toggle(ChatComposerMode.AGENT)
        imagePromptMode -> toggle(ChatComposerMode.IMAGE_PROMPT)
        researchMode -> toggle(ChatComposerMode.RESEARCH)
        else -> this
    }
}

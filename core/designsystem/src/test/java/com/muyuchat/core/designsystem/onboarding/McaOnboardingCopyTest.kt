package com.muyuchat.core.designsystem.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the first-run flow's copy and ordering.
 *
 * These assertions are cheap and they catch the two ways an onboarding flow rots: a page loses its
 * explanation (so the user is asked a question with no context), or two pages start asking the same thing
 * with the same button label (so the flow reads as one page rendered twice).
 */
class McaOnboardingCopyTest {

    @Test
    fun `every step has a non blank title and subtitle`() {
        McaOnboardingStep.entries.forEach { step ->
            assertTrue("title of $step must not be blank", step.title.isNotBlank())
            assertTrue("subtitle of $step must not be blank", step.subtitle.isNotBlank())
            assertEquals(step.title, step.title.trim())
            assertEquals(step.subtitle, step.subtitle.trim())
        }
    }

    @Test
    fun `step titles are distinct`() {
        val titles = McaOnboardingStep.entries.map { it.title }

        assertEquals("two onboarding pages would read the same", titles.size, titles.toSet().size)
    }

    @Test
    fun `the step order is welcome path model done`() {
        // The progress row renders `.ordinal + 1`, so declaration order is a user-visible contract.
        assertEquals(
            listOf(
                McaOnboardingStep.WELCOME,
                McaOnboardingStep.PATH,
                McaOnboardingStep.MODEL,
                McaOnboardingStep.DONE
            ),
            McaOnboardingStep.entries.toList()
        )
    }

    @Test
    fun `step ordinals are contiguous from zero`() {
        McaOnboardingStep.entries.forEachIndexed { index, step ->
            assertEquals("$step must sit at its declared position", index, step.ordinal)
        }
    }

    @Test
    fun `every path choice has a non blank label summary and wire name`() {
        McaOnboardingPathChoice.entries.forEach { choice ->
            assertTrue("label of $choice must not be blank", choice.label.isNotBlank())
            assertTrue("summary of $choice must not be blank", choice.summary.isNotBlank())
            assertTrue("wire name of $choice must not be blank", choice.wireName.isNotBlank())
        }
    }

    @Test
    fun `path labels and summaries are distinct`() {
        val labels = McaOnboardingPathChoice.entries.map { it.label }
        val summaries = McaOnboardingPathChoice.entries.map { it.summary }

        assertEquals(labels.size, labels.toSet().size)
        assertEquals(summaries.size, summaries.toSet().size)
    }

    @Test
    fun `path wire names are distinct and normalised`() {
        val names = McaOnboardingPathChoice.entries.map { it.wireName }

        assertEquals(names.size, names.toSet().size)
        names.forEach { name ->
            assertEquals(name, name.lowercase())
            assertEquals(name, name.trim())
        }
    }

    @Test
    fun `the recommended path is one of the choices`() {
        assertTrue(
            "the recommendation badge must point at a real row",
            McaOnboardingPathChoice.entries.contains(McaOnboardingPathChoice.Recommended)
        )
    }

    @Test
    fun `no path is preselected`() {
        // The flow starts with nothing chosen, so the continue button stays disabled until the user
        // answers. A preselected default would let a tap-through user keep a choice they never read.
        assertNull(McaOnboardingPathChoice.fromWire(null))
        assertNull(McaOnboardingPathChoice.fromWire(""))
        assertNull(McaOnboardingPathChoice.fromWire("  "))
    }

    @Test
    fun `unknown stored path text is not treated as a choice`() {
        assertNull(McaOnboardingPathChoice.fromWire("cloud"))
        assertNull(McaOnboardingPathChoice.fromWire("EXPERIENCE-LOCAL"))
    }

    @Test
    fun `every path wire name round trips case insensitively`() {
        McaOnboardingPathChoice.entries.forEach { choice ->
            assertEquals(choice, McaOnboardingPathChoice.fromWire(choice.wireName))
            assertEquals(choice, McaOnboardingPathChoice.fromWire(choice.wireName.uppercase()))
            assertEquals(choice, McaOnboardingPathChoice.fromWire(" ${choice.wireName} "))
        }
    }

    @Test
    fun `the two paths describe different install shapes`() {
        val experience = McaOnboardingPathChoice.EXPERIENCE.summary
        val production = McaOnboardingPathChoice.PRODUCTION.summary

        assertFalse(
            "the two summaries must not be the same sentence",
            experience == production
        )
        assertTrue(
            "the local option must say it needs preparation",
            experience.contains("安装") || experience.contains("准备")
        )
        assertTrue(
            "the light option must say it skips the install",
            production.contains("不安装") || production.contains("不装")
        )
    }
}

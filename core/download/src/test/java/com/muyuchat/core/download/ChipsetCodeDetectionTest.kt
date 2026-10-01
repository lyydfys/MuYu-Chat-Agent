package com.muyuchat.core.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the chipset predicate now that there is one copy of it instead of two.
 *
 * The two previous copies were byte-identical, so this test is not about behaviour that was
 * previously covered -- it is about keeping the single copy honest. RecommendationCatalogTest
 * covers the consequence that matters most (a non-Snapdragon or unknown device keeps its
 * download action); this covers the predicate those results are built on.
 */
class ChipsetCodeDetectionTest {

    @Test
    fun `uppercase vendor names are recognised`() {
        assertTrue("SM8550".isSnapdragonChipsetCode())
        assertTrue("SNAPDRAGON".isSnapdragonChipsetCode())
        assertTrue("SNAPDRAGON 8 GEN 3".isSnapdragonChipsetCode())
        assertTrue("骁龙8Gen3".isSnapdragonChipsetCode())
    }

    @Test
    fun `the predicate is case sensitive, so callers must normalise first`() {
        // This is a real property of the shared function, not an oversight, and it is why
        // every call site uppercases the code before calling. Pinning it here means a future
        // caller that forgets to normalise fails loudly instead of silently getting `false`
        // for a genuine Snapdragon device -- which would quietly downgrade its ranking.
        assertFalse("snapdragon 8 gen 3".isSnapdragonChipsetCode())
        assertFalse("sm8550".isSnapdragonChipsetCode())
        assertTrue("snapdragon 8 gen 3".uppercase().isSnapdragonChipsetCode())
        assertTrue("sm8550".uppercase().isSnapdragonChipsetCode())
    }

    @Test
    fun `every qualcomm prefix in the pattern is recognised`() {
        listOf("SM8550", "SDM845", "MSM8998", "APQ8096", "QCS8550", "QCM6490").forEach { code ->
            assertTrue("$code should be recognised as a Snapdragon code", code.isSnapdragonChipsetCode())
        }
    }

    @Test
    fun `non-qualcomm and unknown codes are not snapdragon`() {
        listOf("MT6989", "Exynos 2400", "Tensor G3", "Kirin 9000", "", "   ").forEach { code ->
            assertFalse("$code must not be treated as a Snapdragon code", code.isSnapdragonChipsetCode())
        }
    }

    @Test
    fun `the prefix match is anchored, not a substring search`() {
        // A code that merely contains a prefix later on is not a Qualcomm part.
        assertFalse("XYZSM8550".isSnapdragonChipsetCode())
        // And a bare prefix with nothing after it does not match either.
        assertFalse("SM".isSnapdragonChipsetCode())
        // A trailing separator is allowed by the character class, a trailing space is not.
        assertFalse("SM8550 ".isSnapdragonChipsetCode())
    }

    @Test
    fun `a blank code short-circuits before the vendor check`() {
        // Guards the early return: without it, a blank string would still be tested against
        // the regex and the result would be the same, but the intent would be lost.
        assertFalse("".isSnapdragonChipsetCode())
        assertFalse(" \t\n ".isSnapdragonChipsetCode())
    }
}

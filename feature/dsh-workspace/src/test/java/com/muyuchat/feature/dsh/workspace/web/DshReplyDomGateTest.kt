package com.muyuchat.feature.dsh.workspace.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure gate behind the account-path reply probe.
 *
 * Every rule the injected script relies on is pinned here: minimum growth, silence window, one report
 * per burst, re-arming, typing suppression, page reset, truncation and the gateway-vs-DOM dedup.
 */
class DshReplyDomGateTest {

    private fun tick(
        text: String = "",
        at: Long,
        focused: Boolean = false,
        reset: Boolean = false
    ): DshReplyDomTick = DshReplyDomTick(
        text = text,
        nowMillis = at,
        inputFocused = focused,
        pageReset = reset
    )

    @Test
    fun `the probe limits are pinned`() {
        assertEquals(24, DshReplyDomLimits.MIN_GROWTH_CHARS)
        assertEquals(1_200L, DshReplyDomLimits.SILENCE_MILLIS)
        assertEquals(280, DshReplyDomLimits.MAX_REPORT_CHARS)
        assertEquals(1_000L, DshReplyDomLimits.GATEWAY_DEDUP_MILLIS)
    }

    @Test
    fun `a quiet burst of 24 characters is reported`() {
        val gate = DshReplyDomGate()

        val growing = gate.observe(tick("a".repeat(24), at = 1_000))
        val settled = gate.observe(tick(at = 2_200))

        assertEquals(DshReplyDomDecision.None, growing)
        assertTrue(settled is DshReplyDomDecision.Report)
        assertEquals("a".repeat(24), (settled as DshReplyDomDecision.Report).text)
    }

    @Test
    fun `23 characters are below the threshold`() {
        val gate = DshReplyDomGate()

        gate.observe(tick("a".repeat(23), at = 1_000))

        assertEquals(DshReplyDomDecision.None, gate.observe(tick(at = 5_000)))
    }

    @Test
    fun `growth without silence is not reported`() {
        val gate = DshReplyDomGate()

        gate.observe(tick("a".repeat(24), at = 1_000))
        assertEquals(DshReplyDomDecision.None, gate.observe(tick("b", at = 1_100)))
        val settled = gate.observe(tick(at = 2_300))

        assertTrue(settled is DshReplyDomDecision.Report)
        assertEquals("a".repeat(24) + "b", (settled as DshReplyDomDecision.Report).text)
    }

    @Test
    fun `exactly the silence window is enough`() {
        val gate = DshReplyDomGate()

        gate.observe(tick("x".repeat(24), at = 1_000))
        val settled = gate.observe(tick(at = 2_200))

        assertTrue(settled is DshReplyDomDecision.Report)
    }

    @Test
    fun `one burst reports only once`() {
        val gate = DshReplyDomGate()
        gate.observe(tick("a".repeat(24), at = 1_000))
        assertTrue(gate.observe(tick(at = 2_300)) is DshReplyDomDecision.Report)

        assertEquals("a later silence tick must not repeat it", DshReplyDomDecision.None, gate.observe(tick(at = 9_000)))
    }

    @Test
    fun `a second report needs another 24 characters`() {
        val gate = DshReplyDomGate()
        gate.observe(tick("a".repeat(24), at = 1_000))
        assertTrue(gate.observe(tick(at = 2_300)) is DshReplyDomDecision.Report)

        gate.observe(tick("b".repeat(10), at = 3_000))
        assertEquals("10 new characters are not a new burst", DshReplyDomDecision.None, gate.observe(tick(at = 4_300)))

        gate.observe(tick("c".repeat(14), at = 5_000))
        val second = gate.observe(tick(at = 6_300))

        assertTrue(second is DshReplyDomDecision.Report)
        assertEquals("b".repeat(10) + "c".repeat(14), (second as DshReplyDomDecision.Report).text)
    }

    @Test
    fun `typing is ignored while an input is focused`() {
        val gate = DshReplyDomGate()

        val typed = gate.observe(tick("user typing ".repeat(5), at = 1_000, focused = true))
        val settled = gate.observe(tick(at = 9_000))

        assertEquals(DshReplyDomDecision.None, typed)
        assertEquals("focused growth must not accumulate", DshReplyDomDecision.None, settled)
    }

    @Test
    fun `a page reset drops the accumulator`() {
        val gate = DshReplyDomGate()
        gate.observe(tick("a".repeat(24), at = 1_000))

        assertEquals(DshReplyDomDecision.None, gate.observe(tick(at = 1_100, reset = true)))
        assertEquals(DshReplyDomDecision.None, gate.observe(tick(at = 9_000)))
    }

    @Test
    fun `the report keeps only the last 280 characters`() {
        val gate = DshReplyDomGate()
        val body = (1..400).joinToString("") { ('a' + (it % 26)).toString() }

        gate.observe(tick(body, at = 1_000))
        val settled = gate.observe(tick(at = 2_300)) as DshReplyDomDecision.Report

        assertEquals(DshReplyDomLimits.MAX_REPORT_CHARS, settled.text.length)
        assertEquals(body.takeLast(DshReplyDomLimits.MAX_REPORT_CHARS), settled.text)
    }

    @Test
    fun `an oversized batch is clamped before it reaches the buffer`() {
        val gate = DshReplyDomGate()
        val huge = "z".repeat(DshReplyDomLimits.MAX_BATCH_CHARS + 5_000)

        gate.observe(tick(huge, at = 1_000))
        val settled = gate.observe(tick(at = 2_300)) as DshReplyDomDecision.Report

        assertEquals(DshReplyDomLimits.MAX_REPORT_CHARS, settled.text.length)
        assertTrue(settled.text.all { it == 'z' })
    }

    @Test
    fun `whitespace only growth never reports`() {
        val gate = DshReplyDomGate()

        gate.observe(tick("   \n\t".repeat(10), at = 1_000))

        assertEquals(DshReplyDomDecision.None, gate.observe(tick(at = 5_000)))
    }

    @Test
    fun `the gateway dedup window is one second and skew counts as recent`() {
        assertFalse(dshAccountReplyAllowed(nowMillis = 10_000, lastGatewayReplyAtMillis = 9_500))
        assertTrue(dshAccountReplyAllowed(nowMillis = 10_000, lastGatewayReplyAtMillis = 8_900))
        assertTrue(dshAccountReplyAllowed(nowMillis = 10_000, lastGatewayReplyAtMillis = null))
        assertFalse(
            "a future gateway timestamp is clock skew and must suppress the duplicate",
            dshAccountReplyAllowed(nowMillis = 10_000, lastGatewayReplyAtMillis = 12_000)
        )
        assertTrue(
            "exactly one second later is allowed",
            dshAccountReplyAllowed(nowMillis = 10_000, lastGatewayReplyAtMillis = 9_000)
        )
    }

    @Test
    fun `the deduper drops a DOM reply that the gateway just reported`() {
        var now = 5_000L
        val deduper = DshAccountReplyDeduper(clock = { now })

        assertFalse(deduper.accept("hello", lastGatewayReplyAtMillis = 4_500))

        now = 6_500
        assertTrue(deduper.accept("hello", lastGatewayReplyAtMillis = 4_500))
        assertFalse("blank text is never a reply", deduper.accept("   ", lastGatewayReplyAtMillis = null))
    }

    @Test
    fun `the bridge control field is parsed defensively`() {
        val parsed = dshParseReplyProbeControl("1700000000000|1|0")
        assertEquals(1_700_000_000_000L, parsed.jsMillis)
        assertTrue(parsed.inputFocused)
        assertFalse(parsed.pageReset)

        val junk = dshParseReplyProbeControl("garbage")
        assertEquals(0L, junk.jsMillis)
        assertFalse(junk.inputFocused)
        assertFalse(junk.pageReset)

        val reset = dshParseReplyProbeControl("1|0|1")
        assertTrue(reset.pageReset)
        assertFalse(reset.inputFocused)
    }
}

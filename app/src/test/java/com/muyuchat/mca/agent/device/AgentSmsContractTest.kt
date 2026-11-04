package com.muyuchat.mca.agent.device

import com.muyuchat.mca.agent.device.sms.AgentSmsConfirmationGate
import com.muyuchat.mca.agent.device.sms.AgentSmsConfirmationOutcome
import com.muyuchat.mca.agent.device.sms.AgentSmsPolicy
import com.muyuchat.mca.agent.device.sms.AgentSmsRawMessage
import com.muyuchat.mca.agent.device.sms.AgentSmsQueryResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the read-only SMS lane's privacy and access rules.
 *
 * The rules that matter to the user:
 *  - nothing is returned without the Settings switch, the `READ_SMS` grant and a per-query
 *    confirmation (unless the session skip is on);
 *  - the response never carries a full number or a full body: last four digits and 120 characters;
 *  - "not granted" and "no matching message" are different results;
 *  - the newest messages win, the response is capped at 20, and the confirmation re-arms in a new
 *    app session.
 */
class AgentSmsContractTest {

    private val rows = listOf(
        AgentSmsRawMessage("+8613812345678", "您的验证码是 445566，5 分钟内有效。", 3_000L),
        AgentSmsRawMessage("95588", "余额变动提醒", 1_000L),
        AgentSmsRawMessage("10086", "本月流量已用完", 5_000L),
        AgentSmsRawMessage("Alice", "See you at 8pm", 2_000L)
    )

    @Test
    fun `sender keeps only the last four digits`() {
        assertEquals("****5678", AgentSmsPolicy.maskSender("+8613812345678"))
        assertEquals("****5678", AgentSmsPolicy.maskSender("138 1234 5678"))
        assertEquals("****5588", AgentSmsPolicy.maskSender("95588"))
        assertEquals("****", AgentSmsPolicy.maskSender("BANK"))
        assertEquals("****", AgentSmsPolicy.maskSender("12"))
        assertEquals(AgentSmsPolicy.UNKNOWN_SENDER, AgentSmsPolicy.maskSender(null))
        assertEquals(AgentSmsPolicy.UNKNOWN_SENDER, AgentSmsPolicy.maskSender("   "))
    }

    @Test
    fun `body preview is bounded and single line`() {
        val long = "预".repeat(200)
        val preview = AgentSmsPolicy.preview(long)
        assertEquals(AgentSmsPolicy.PREVIEW_CHARS, preview.length)

        assertEquals("a b c d", AgentSmsPolicy.preview("a\nb\r\nc\rd"))
        assertEquals("", AgentSmsPolicy.preview(null))
        assertEquals("短消息", AgentSmsPolicy.preview("  短消息  "))
    }

    @Test
    fun `newest messages come first and the response is capped`() {
        val result = AgentSmsPolicy.query(rows, limit = 20, keyword = null)

        assertEquals(4, result.messages.size)
        assertEquals(
            listOf(5_000L, 3_000L, 2_000L, 1_000L),
            result.messages.map { it.receivedAtMillis }
        )
        assertEquals("****5678", result.messages[1].senderMasked)
        assertFalse("the full number must never appear", result.messages.toString().contains("13812345678"))
    }

    @Test
    fun `limit is clamped into one through twenty`() {
        assertEquals(1, AgentSmsPolicy.clampLimit(0))
        assertEquals(1, AgentSmsPolicy.clampLimit(-5))
        assertEquals(AgentSmsPolicy.MAX_RESULTS, AgentSmsPolicy.clampLimit(99))
        assertEquals(AgentSmsPolicy.DEFAULT_RESULTS, AgentSmsPolicy.clampLimit(null))
        assertEquals(7, AgentSmsPolicy.clampLimit(7))

        val capped = AgentSmsPolicy.query(rows, limit = 99, keyword = null)
        assertTrue(capped.messages.size <= AgentSmsPolicy.MAX_RESULTS)
        assertEquals(2, AgentSmsPolicy.query(rows, limit = 2, keyword = null).messages.size)
    }

    @Test
    fun `keyword filters body and sender case insensitively`() {
        val byBody = AgentSmsPolicy.query(rows, limit = 20, keyword = "验证码")
        assertEquals(1, byBody.messages.size)
        assertEquals("****5678", byBody.messages.single().senderMasked)

        val bySender = AgentSmsPolicy.query(rows, limit = 20, keyword = "alice")
        assertEquals(1, bySender.messages.size)

        val none = AgentSmsPolicy.query(rows, limit = 20, keyword = "没有这句话")
        assertTrue("an empty result is a success, not a refusal", none.messages.isEmpty())
        assertEquals(rows.size, none.scanned)
        assertEquals(rows.size, none.filteredOut)
    }

    @Test
    fun `keyword longer than the contract is rejected`() {
        assertFalse(AgentSmsPolicy.keywordTooLong("正常关键词"))
        assertTrue(AgentSmsPolicy.keywordTooLong("长".repeat(AgentSmsPolicy.MAX_KEYWORD_CHARS + 1)))
        assertFalse(AgentSmsPolicy.keywordTooLong(null))
    }

    @Test
    fun `preview of a verification code is not special cased but stays a preview`() {
        // The lane is read-only; a code may be quoted like any other text, but the full body and
        // the full number never leave the platform query.
        val message = AgentSmsPolicy.query(rows, limit = 1, keyword = "验证码").messages.single()
        assertTrue(message.bodyPreview.contains("445566"))
        assertTrue(message.bodyPreview.length <= AgentSmsPolicy.PREVIEW_CHARS)
        assertTrue(message.senderMasked.startsWith("****"))
    }

    @Test
    fun `confirmation is required for every query unless the session switch is on`() = runBlocking {
        var skip = false
        val gate = AgentSmsConfirmationGate(skipThisSession = { skip })
        var prompts = 0
        val allowOnce = AgentSmsConfirmationPort {
            prompts += 1
            true
        }

        assertTrue(gate.needsConfirmation())
        assertEquals(AgentSmsConfirmationOutcome.ALLOWED, gate.confirm("读取短信？", allowOnce))
        assertEquals(AgentSmsConfirmationOutcome.ALLOWED, gate.confirm("读取短信？", allowOnce))
        assertEquals("each query asks again by default", 2, prompts)

        // The switch explicitly says "本次运行内不再询问"; while it is on, no dialog is shown.
        skip = true
        assertFalse(gate.needsConfirmation())
        assertEquals(
            AgentSmsConfirmationOutcome.SKIPPED_BY_SESSION_SWITCH,
            gate.confirm("读取短信？", allowOnce)
        )
        assertEquals("the skipped session must not prompt", 2, prompts)

        // Turning it off restores the per-query prompt immediately.
        skip = false
        assertTrue(gate.needsConfirmation())
        assertEquals(AgentSmsConfirmationOutcome.ALLOWED, gate.confirm("读取短信？", allowOnce))
        assertEquals(3, prompts)
    }

    @Test
    fun `denied confirmation stays denied`() = runBlocking {
        val gate = AgentSmsConfirmationGate({ false })
        val deny = AgentSmsConfirmationPort { false }

        assertEquals(AgentSmsConfirmationOutcome.DENIED, gate.confirm("读取短信？", deny))
    }

    @Test
    fun `unanswered confirmation times out instead of hanging the request`() = runBlocking {
        val gate = AgentSmsConfirmationGate(
            skipThisSession = { false },
            confirmationTimeoutMillis = 1L
        )
        val slow = AgentSmsConfirmationPort {
            kotlinx.coroutines.delay(2_000)
            true
        }

        assertEquals(AgentSmsConfirmationOutcome.TIMED_OUT, gate.confirm("读取短信？", slow))
    }

    @Test
    fun `success result reports scanned and filtered counts`() {
        val result = AgentSmsPolicy.query(rows, limit = 2, keyword = "提醒")
        assertEquals(1, result.messages.size)
        assertEquals(rows.size, result.scanned)
        assertEquals(rows.size - 1, result.filteredOut)
    }
}

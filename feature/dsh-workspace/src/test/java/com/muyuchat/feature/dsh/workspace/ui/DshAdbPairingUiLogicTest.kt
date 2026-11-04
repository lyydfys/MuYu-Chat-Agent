package com.muyuchat.feature.dsh.workspace.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic behind the experimental pairing form.
 *
 * This file deliberately depends on nothing but Kotlin + JUnit: it pins the validation messages, the
 * six-digit filter and the failure line (reason code + assumption labels) without AndroidX or Compose,
 * so it can run in a plain JVM test runner.
 */
class DshAdbPairingUiLogicTest {

    @Test
    fun `valid input passes`() {
        assertNull(dshAdbPairingInputError(hostPortValid = true, code = "314159"))
    }

    @Test
    fun `an unparsable endpoint is rejected locally`() {
        assertEquals(
            "配对端点格式应为 host:port，例如 10.0.0.2:43521",
            dshAdbPairingInputError(hostPortValid = false, code = "314159")
        )
    }

    @Test
    fun `the pairing code must be six digits`() {
        val tooShort = dshAdbPairingInputError(hostPortValid = true, code = "31415")
        val tooLong = dshAdbPairingInputError(hostPortValid = true, code = "3141592")
        val letters = dshAdbPairingInputError(hostPortValid = true, code = "31a159")
        val empty = dshAdbPairingInputError(hostPortValid = true, code = "")
        for (message in listOf(tooShort, tooLong, letters, empty)) {
            assertEquals("配对码是设备上显示的 6 位数字", message)
        }
    }

    @Test
    fun `the code field keeps digits only and truncates to six`() {
        assertEquals("123456", dshAdbPairingCodeDigits("12ab34567"))
        assertEquals("123456", dshAdbPairingCodeDigits("123456"))
        assertEquals("", dshAdbPairingCodeDigits("abc"))
        assertEquals("00", dshAdbPairingCodeDigits("a0b0c"))
        // Leading zeros survive: the code is text, not a number.
        assertEquals("000123", dshAdbPairingCodeDigits("000123"))
    }

    @Test
    fun `failure line lists the documented assumption labels`() {
        val line = dshAdbPairingFailureLine(
            reasonCode = "peer_info_rejected",
            unverifiedAssumptions = listOf(
                "tls_exporter_label",
                "aes_gcm_derivation",
                "peer_info_layout",
                "packet_type_values"
            )
        )
        assertEquals(
            "配对失败：peer_info_rejected · 未验证假设：" +
                "exporter label / GCM 派生 / PeerInfo 布局 / packet type",
            line
        )
    }

    @Test
    fun `failure line without assumptions is just the reason code`() {
        assertEquals(
            "配对失败：timeout",
            dshAdbPairingFailureLine("timeout", emptyList())
        )
        assertEquals(
            "配对失败：unsupported",
            dshAdbPairingFailureLine("unsupported", emptyList())
        )
    }

    @Test
    fun `unknown assumption names pass through unchanged`() {
        val line = dshAdbPairingFailureLine("unsupported", listOf("experimental_switch"))
        assertEquals("配对失败：unsupported · 未验证假设：experimental_switch", line)
    }

    @Test
    fun `failure line never embeds the pairing code`() {
        val code = "314159"
        val line = dshAdbPairingFailureLine("spake2_failed", listOf("aes_gcm_derivation"))
        assertTrue("reason line must not echo the code", !line.contains(code))
        assertTrue("reason line must stay short", line.length < 160)
    }
}

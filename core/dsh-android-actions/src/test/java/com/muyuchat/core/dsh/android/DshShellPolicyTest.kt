package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshShellPolicyTest {

    @Test
    fun keeps_scripts_and_non_ascii_but_normalizes_line_endings() {
        assertEquals(
            "pm list packages\nls /sdcard/APK安装包",
            DshShellPolicy.sanitizeCommand("  pm list packages\r\nls /sdcard/APK安装包  ")
        )
        assertEquals("input text '你好'", DshShellPolicy.sanitizeCommand("input text '你好'"))
    }

    @Test
    fun rejects_blank_oversized_and_control_character_commands() {
        assertThrowsType<DshAndroidBadRequest> { DshShellPolicy.sanitizeCommand(null) }
        assertThrowsType<DshAndroidBadRequest> { DshShellPolicy.sanitizeCommand("   ") }
        assertThrowsType<DshAndroidBadRequest> { DshShellPolicy.sanitizeCommand("id\u0000") }
        assertThrowsType<DshAndroidBadRequest> {
            DshShellPolicy.sanitizeCommand("a".repeat(DshAndroidActionLimits.MAX_SHELL_COMMAND_CHARS + 1))
        }
    }

    @Test
    fun clamps_timeouts_like_the_official_packages() {
        assertEquals(30_000L, DshShellPolicy.clampTimeoutMillis(null))
        assertEquals(30_000L, DshShellPolicy.clampTimeoutMillis(0))
        assertEquals(1_000L, DshShellPolicy.clampTimeoutMillis(1))
        assertEquals(5_000L, DshShellPolicy.clampTimeoutMillis(5_000))
        assertEquals(120_000L, DshShellPolicy.clampTimeoutMillis(999_999))
        assertEquals(
            20L,
            DshShellPolicy.clampTimeoutMillis(5, minimum = 20L, maximum = 500L, default = 100L)
        )
        assertEquals(
            500L,
            DshShellPolicy.clampTimeoutMillis(5_000, minimum = 20L, maximum = 500L, default = 100L)
        )
    }

    @Test
    fun child_environment_drops_linker_variables_and_credentials() {
        val filtered = DshShellPolicy.childEnvironment(
            mapOf(
                "PATH" to "/system/bin",
                "HOME" to "/data/user/0/pkg",
                "LD_LIBRARY_PATH" to "/data/app/lib",
                "LD_PRELOAD" to "libevil.so",
                "LD_DEBUG" to "libs",
                "APP_LOCAL_TOKEN" to "dshc_secret",
                "DSH_API_KEY" to "key",
                "SSH_AUTH_SOCK" to "/tmp/agent",
                "LANGUAGE" to "zh-CN"
            )
        )

        assertEquals(
            mapOf("PATH" to "/system/bin", "HOME" to "/data/user/0/pkg", "LANGUAGE" to "zh-CN"),
            filtered
        )
    }

    @Test
    fun consent_detail_is_a_single_prompt_free_line() {
        val multiLine = DshShellPolicy.consentDetail("pm list packages\nsettings put system x 1\r\n\u0007")
        assertFalse(multiLine.contains('\n'))
        assertTrue(multiLine.startsWith("特权命令："))
        assertTrue(multiLine.length <= DshAndroidActionLimits.MAX_STATUS_DETAIL_CHARS)

        val long = DshShellPolicy.consentDetail("a".repeat(2_000))
        assertTrue(long.length <= DshAndroidActionLimits.MAX_STATUS_DETAIL_CHARS)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun output_buffer_caps_and_keeps_dropping() {
        val buffer = DshShellOutputBuffer(limit = 10)

        buffer.append("0123456789ABCDEF")
        assertEquals(10, buffer.capturedChars)
        assertEquals("0123456789", buffer.text())
        assertTrue(buffer.truncated)
        assertEquals(6L, buffer.dropped)

        buffer.append("more")
        assertEquals("0123456789", buffer.text())
        assertEquals(10L, buffer.dropped)

        val exact = DshShellOutputBuffer(limit = 4)
        exact.append("abcd")
        assertEquals("abcd", exact.text())
        assertFalse(exact.truncated)
    }

    @Test
    fun failures_are_sanitized_into_one_short_line() {
        val failure = DshAndroidActionFailure.of(
            DshAndroidActionErrorCode.INTERNAL,
            "line one\nline two\t\u0007tail"
        )
        assertEquals("line one line two tail", failure.message)
        assertTrue(failure.wireMessage.startsWith("INTERNAL: "))
        assertEquals(DshCapabilityErrorCode.INTERNAL, failure.contractCode)

        val capped = DshAndroidActionFailure.of(
            DshAndroidActionErrorCode.PERMISSION_DENIED,
            "x".repeat(500)
        )
        assertTrue(capped.message.length <= DshAndroidActionFailure.MAX_MESSAGE_CHARS)

        val empty = DshAndroidActionFailure.of(DshAndroidActionErrorCode.TIMEOUT, "  \n ")
        assertTrue(empty.message.isNotBlank())
        val missing = DshAndroidActionFailure.of(DshAndroidActionErrorCode.NOT_FOUND, null)
        assertTrue(missing.message.isNotBlank())
    }

    @Test
    fun refusal_mapping_covers_every_capability_state() {
        assertEquals(
            DshAndroidActionErrorCode.PERMISSION_DENIED,
            DshAndroidActionErrorCode.refusalFor(
                com.muyuchat.core.dsh.contract.capability.DshCapabilityState.DENIED
            )
        )
        assertEquals(
            DshAndroidActionErrorCode.PERMISSION_DENIED,
            DshAndroidActionErrorCode.refusalFor(
                com.muyuchat.core.dsh.contract.capability.DshCapabilityState.NOT_DECLARED
            )
        )
        assertEquals(
            DshAndroidActionErrorCode.NOT_ENABLED,
            DshAndroidActionErrorCode.refusalFor(
                com.muyuchat.core.dsh.contract.capability.DshCapabilityState.NOT_ENABLED
            )
        )
        assertEquals(
            DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE,
            DshAndroidActionErrorCode.refusalFor(
                com.muyuchat.core.dsh.contract.capability.DshCapabilityState.UNSUPPORTED
            )
        )
        assertEquals(
            DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE,
            DshAndroidActionErrorCode.refusalFor(
                com.muyuchat.core.dsh.contract.capability.DshCapabilityState.GRANTED
            )
        )
    }
}

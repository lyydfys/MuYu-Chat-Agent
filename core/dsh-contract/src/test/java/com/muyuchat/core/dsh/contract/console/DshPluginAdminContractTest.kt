package com.muyuchat.core.dsh.contract.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plugin name reaches a child process argv, so the accepted shape is pinned here rather than
 * discovered on a device: anything that could be read as an option, a path, a URL or a shell
 * metacharacter must be refused before the CLI ever sees it.
 */
class DshPluginAdminContractTest {

    @Test
    fun `plain and scoped names are accepted`() {
        listOf(
            "dsh-plugin-market",
            "@deepseek-ai/dsh-plugin-manager",
            "@mca/terminal-bridge",
            "plugin.js",
            "a"
        ).forEach { name ->
            assertNull("expected <$name> to be acceptable", DshPluginName.rejectReason(name))
            assertTrue(DshPluginName.isAcceptable(name))
        }
    }

    @Test
    fun `option-shaped and traversal names are refused`() {
        listOf(
            "-g",
            "--global",
            ".hidden",
            "/etc/passwd",
            "../escape",
            "name with space",
            "name;rm -rf /",
            "name&&id",
            "https://example.invalid/x.tgz",
            "file:../local",
            "name\nsecond",
            "@scope/../escape"
        ).forEach { name ->
            assertNotNull("expected <$name> to be refused", DshPluginName.rejectReason(name))
        }
    }

    @Test
    fun `names are bounded`() {
        val long = "a".repeat(DshPluginName.MAX_LENGTH + 1)
        assertNotNull(DshPluginName.rejectReason(long))
        assertNull(DshPluginName.rejectReason("a".repeat(DshPluginName.MAX_LENGTH)))
        assertEquals("插件名不能为空", DshPluginName.rejectReason("   "))
    }

    @Test
    fun `version suffix is bounded but not interpreted`() {
        listOf(null, "", "latest", "1.2.3", "^1.0.0", "~2", ">=1 <2").forEach { version ->
            assertNull("expected <$version> to be acceptable", DshPluginName.versionRejectReason(version))
        }
        listOf("-v", "--registry", "1".repeat(65), "1.0.0;id").forEach { version ->
            assertNotNull("expected <$version> to be refused", DshPluginName.versionRejectReason(version))
        }
    }

    @Test
    fun `results carry a stable operation and plugin identity`() {
        val succeeded = DshPluginAdminResult.Succeeded(DshPluginOperation.INSTALL, "dsh-plugin-market")
        assertEquals(DshPluginOperation.INSTALL, succeeded.operation)
        assertEquals("dsh-plugin-market", succeeded.plugin)

        val failed = DshPluginAdminResult.Failed(
            operation = DshPluginOperation.REMOVE,
            plugin = "dsh-plugin-market",
            reasonCode = "not_installed"
        )
        assertEquals("not_installed", failed.reasonCode)
        assertTrue("detail defaults to empty", failed.detail.isEmpty())
    }
}

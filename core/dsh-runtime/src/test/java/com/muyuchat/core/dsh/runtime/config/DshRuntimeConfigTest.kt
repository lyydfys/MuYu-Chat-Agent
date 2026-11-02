package com.muyuchat.core.dsh.runtime.config

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * DSHA G1 path contract: the managed runtime cwd is the user workspace below `files/dsh`, never
 * the replaceable verified payload tree. `workspaceDirName` is configurable, so the configuration
 * itself must reject values that would resolve the cwd onto the payload (or another managed root).
 */
class DshRuntimeConfigTest {

    @Test
    fun defaultWorkspaceIsASiblingOfTheReplaceablePayloadTree() {
        val root = tempRoot()
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            assertEquals(File(root, "workspace"), config.workspaceDir)

            val workspace = config.workspaceDir.canonicalFile
            val payload = config.payloadDir.canonicalFile
            assertFalse("workspace must not equal the payload root", workspace == payload)
            assertFalse(
                "workspace must not be nested inside the payload root",
                workspace.path.startsWith(payload.path + File.separator)
            )

            // A default-cwd tool write lands in the workspace, not in the replaceable tree.
            assertTrue(config.workspaceDir.mkdirs() || config.workspaceDir.isDirectory)
            File(config.workspaceDir, "tool-output.txt").writeText("user data\n")
            assertTrue(File(config.workspaceDir, "tool-output.txt").isFile)
            assertFalse(File(config.payloadDir, "tool-output.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun workspaceCannotBePointedAtThePayloadOrEscapeTheDataRoot() {
        val root = tempRoot()
        try {
            val rejected = listOf(
                "payload",
                "home",
                "tmp",
                ".",
                "..",
                "../payload",
                "a/b",
                "a\\b",
                " "
            )
            for (name in rejected) {
                try {
                    DshRuntimeConfig(dataRoot = root, workspaceDirName = name).validated()
                    fail("workspaceDirName \"$name\" must be rejected before any process starts")
                } catch (expected: IllegalArgumentException) {
                    assertTrue(
                        "unexpected rejection reason for \"$name\": ${expected.message}",
                        expected.message?.contains("workspace") == true
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun tempRoot(): File = Files.createTempDirectory("dsh-config").toFile()
}

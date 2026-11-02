package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentFileEntry
import com.muyuchat.core.agent.contract.AgentFileKind
import com.muyuchat.core.agent.contract.AgentFileResult
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class AgentWorkspacePathGuardTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val workspaceId = "workspace-1"
    private val taskId = "task-1"

    @Test
    fun `invalid logical paths are rejected`() {
        val invalid = listOf(
            "",
            "/etc/passwd",
            "C:\\Windows\\system.ini",
            "inputs\\file.txt",
            "inputs/../secrets.txt",
            "inputs/./file.txt",
            "inputs//file.txt",
            "inputs/\u0000file.txt",
            "secrets/file.txt",
            "inputs/",
            "artifacts/.versions/x.blob",
            "inputs/" + "a".repeat(600),
            (1..17).joinToString("/") { "segment$it" }.let { "inputs/$it" }
        )
        invalid.forEach { path ->
            try {
                AgentWorkspacePaths.canonicalize(path)
                fail("expected rejection for '$path'")
            } catch (error: AgentStorageException) {
                assertEquals("code for '$path'", AgentDataErrorCodes.INPUT_INVALID, error.code)
            }
        }
    }

    @Test
    fun `valid logical paths canonicalize deterministically`() {
        assertEquals("inputs/a.txt", AgentWorkspacePaths.canonicalize("inputs/a.txt"))
        assertEquals("inputs", AgentWorkspacePaths.canonicalize("inputs"))
        assertEquals("artifacts/report.md", AgentWorkspacePaths.canonicalize("artifacts/report.md"))
        assertEquals(
            "inputs/caf\u00e9.txt",
            AgentWorkspacePaths.canonicalize("inputs/cafe\u0301.txt")
        )
    }

    @Test
    fun `unicode normalization detects duplicate imported names`() {
        val existing = listOf("caf\u00e9.txt")
        val unique = AgentWorkspacePaths.uniqueImportName("cafe\u0301.txt", existing)
        assertTrue("expected a suffixed unique name, got $unique", unique != "caf\u00e9.txt")
        assertEquals(
            "caf\u00e9 (2).txt",
            unique
        )
        assertEquals("plain.txt", AgentWorkspacePaths.uniqueImportName("plain.txt", existing))
        assertTrue(AgentWorkspacePaths.uniqueImportName("CON", emptyList()).startsWith("_"))
    }

    @Test
    fun `import names are sanitized`() {
        assertEquals("brief.txt", AgentWorkspacePaths.sanitizeImportName("brief.txt"))
        assertEquals("brief.txt", AgentWorkspacePaths.sanitizeImportName("dir/sub\\brief.txt"))
        assertEquals("hidden", AgentWorkspacePaths.sanitizeImportName(".hidden"))
        assertEquals("input", AgentWorkspacePaths.sanitizeImportName("   "))
        assertFalse(AgentWorkspacePaths.sanitizeImportName("a:b.txt").contains(':'))
    }

    @Test
    fun `symbolic link components escape is rejected`() = runBlocking {
        val store = AgentWorkspaceReadStore(temporary.root)
        val taskRoot = store.ensureTaskRoots(workspaceId, taskId)
        val outside = temporary.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("secret") }
        val link = File(taskRoot, "inputs/link.txt")
        val created = try {
            Files.createSymbolicLink(link.toPath(), secret.toPath())
            true
        } catch (_: Exception) {
            false
        }
        Assume.assumeTrue("symbolic links are not supported on this platform", created)
        val result = store.readText(workspaceId, taskId, "inputs/link.txt", 0L, 16)
        assertTrue(result is AgentFileResult.Failure)
        assertEquals(AgentDataErrorCodes.INPUT_INVALID, (result as AgentFileResult.Failure).errorCode)
    }

    @Test
    fun `task prefix collisions never resolve into a sibling task root`() {
        val store = AgentWorkspaceReadStore(temporary.root)
        val firstRoot = store.ensureTaskRoots("workspace-1", "task-1")
        val siblingRoot = store.ensureTaskRoots("workspace-1", "task-10")
        File(siblingRoot, "inputs/leak.txt").writeText("leak")
        try {
            AgentWorkspacePaths.canonicalize("inputs/../task-10/inputs/leak.txt")
            fail("traversal must be rejected before resolution")
        } catch (error: AgentStorageException) {
            assertEquals(AgentDataErrorCodes.INPUT_INVALID, error.code)
        }
        try {
            AgentWorkspacePaths.resolve(firstRoot, "../../task-10/inputs/leak.txt")
            fail("out-of-root resolution must be rejected")
        } catch (error: AgentStorageException) {
            assertEquals(AgentDataErrorCodes.INPUT_INVALID, error.code)
        }
    }

    @Test
    fun `task root cannot be a symlink`() {
        val workspaces = temporary.newFolder("workspaces")
        val workspace = File(workspaces, "workspace-1").apply { mkdirs() }
        val tasks = File(workspace, "tasks").apply { mkdirs() }
        val outside = temporary.newFolder("outside-task")
        val created = try {
            Files.createSymbolicLink(File(tasks, "task-1").toPath(), outside.toPath())
            true
        } catch (_: Exception) {
            false
        }
        Assume.assumeTrue("symbolic links are not supported on this platform", created)
        try {
            AgentWorkspacePaths.taskRoot(temporary.root, "workspace-1", "task-1", create = false)
            fail("symlinked task root must be rejected")
        } catch (error: AgentStorageException) {
            assertEquals(AgentDataErrorCodes.INPUT_INVALID, error.code)
        }
    }

    @Test
    fun `logical path of a file outside the task root is rejected`() {
        val store = AgentWorkspaceReadStore(temporary.root)
        val taskRoot = store.ensureTaskRoots(workspaceId, taskId)
        val outside = File(temporary.root, "elsewhere.txt").apply { writeText("x") }
        try {
            AgentWorkspacePaths.logicalPathOf(taskRoot, outside)
            fail("outside file must not map to a logical path")
        } catch (error: AgentStorageException) {
            assertEquals(AgentDataErrorCodes.INPUT_INVALID, error.code)
        }
    }

    @Test
    fun `list cursor encoding is version bound`() {
        val cursor = AgentListCursor.encode("version-a", "inputs/a.txt")
        val decoded = AgentListCursor.decode(cursor, "version-a")
        assertEquals("inputs/a.txt", decoded.lastPath)
        try {
            AgentListCursor.decode(cursor, "version-b")
            fail("cursor must be invalidated")
        } catch (error: AgentStorageException) {
            assertEquals("CURSOR_STALE", error.code)
        }
        assertNotNull(cursor)
    }

    @Test
    fun `entry model carries size and kind`() {
        val entry = AgentFileEntry("inputs/a.txt", AgentFileKind.FILE, 12L, null)
        assertEquals(AgentFileKind.FILE, entry.kind)
        assertEquals(12L, entry.sizeBytes)
    }
}

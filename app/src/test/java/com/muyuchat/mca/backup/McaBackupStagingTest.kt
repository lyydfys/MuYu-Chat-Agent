package com.muyuchat.mca.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the staged file swap that protects the current file from a bad backup.
 *
 * The user-visible promise is "恢复失败也保留原文件". These cases drive every branch with real temp
 * files — validation failure, first rename failure, second rename failure, missing target — so the
 * promise is pinned by behaviour rather than by the comment above the function.
 */
class McaBackupStagingTest {

    @Test
    fun `a verified payload replaces the previous file`() {
        withTempRoot { root ->
            val target = File(root, "live.json").apply { writeText("old") }

            val result = McaBackupStaging.restoreAtomically(
                target = target,
                payload = "new".toByteArray(),
                workDir = File(root, "work"),
                validate = { staged -> staged.readText() == "new" }
            )

            assertEquals(McaBackupRestoreOutcome.RESTORED, result.outcome)
            assertTrue(result.succeeded)
            assertFalse(result.keptOriginal)
            assertEquals("new", target.readText())
            assertEquals(listOf("live.json"), root.listFiles()!!.map { it.name }.filter { it != "work" })
        }
    }

    @Test
    fun `a payload that fails validation never touches the original`() {
        withTempRoot { root ->
            val target = File(root, "live.json").apply { writeText("current-library") }

            val result = McaBackupStaging.restoreAtomically(
                target = target,
                payload = "truncated".toByteArray(),
                workDir = File(root, "work"),
                validate = { false }
            )

            assertEquals(McaBackupRestoreOutcome.VALIDATION_FAILED, result.outcome)
            assertTrue(result.keptOriginal)
            assertEquals("current-library", target.readText())
            assertFalse(File(root, "work/.mca-restore.staging").exists())
        }
    }

    @Test
    fun `a failure while moving the original aside keeps it in place`() {
        withTempRoot { root ->
            val target = File(root, "live.json").apply { writeText("current-library") }

            val result = McaBackupStaging.restoreAtomically(
                target = target,
                payload = "new".toByteArray(),
                workDir = File(root, "work"),
                validate = { true },
                rename = { _, _ -> false }
            )

            assertEquals(McaBackupRestoreOutcome.PROMOTION_FAILED, result.outcome)
            assertTrue(result.keptOriginal)
            assertEquals("current-library", target.readText())
        }
    }

    @Test
    fun `a failure while promoting the staged file restores the original`() {
        withTempRoot { root ->
            val target = File(root, "live.json").apply { writeText("current-library") }
            var renameCalls = 0

            val result = McaBackupStaging.restoreAtomically(
                target = target,
                payload = "new".toByteArray(),
                workDir = File(root, "work"),
                validate = { true },
                // Call 1 moves the live file aside; call 2 (staging -> live) fails; call 3 is the
                // rollback that must put the original back.
                rename = { from, to ->
                    renameCalls++
                    if (renameCalls == 2) false else from.renameTo(to)
                }
            )

            assertEquals(3, renameCalls)
            assertEquals(McaBackupRestoreOutcome.PROMOTION_FAILED, result.outcome)
            assertTrue(result.keptOriginal)
            assertEquals("current-library", target.readText())
        }
    }

    @Test
    fun `a first restore into an empty directory creates the file`() {
        withTempRoot { root ->
            val target = File(root, "nested/live.json")

            val result = McaBackupStaging.restoreAtomically(
                target = target,
                payload = "fresh".toByteArray(),
                workDir = File(root, "work"),
                validate = { true }
            )

            assertTrue(result.succeeded)
            assertTrue(target.isFile)
            assertEquals("fresh", target.readText())
        }
    }

    @Test
    fun `the staging directory is created on demand`() {
        withTempRoot { root ->
            val target = File(root, "live.json")
            val workDir = File(root, "does/not/exist/yet")

            val result = McaBackupStaging.restoreAtomically(
                target = target,
                payload = "payload".toByteArray(),
                workDir = workDir,
                validate = { it.length() > 0 }
            )

            assertTrue(result.succeeded)
            assertTrue(workDir.isDirectory)
        }
    }

    @Test
    fun `publishVerified keeps the previous export when the new one is not verifiable`() {
        withTempRoot { root ->
            val file = File(root, "last-verified-backup.json").apply { writeText("""{"kind":"mca-backup"}""") }

            val result = McaBackupStaging.publishVerified(
                file = file,
                payload = "{}".toByteArray(),
                validate = { staged -> McaBackupVerify.verify(staged.readText()).isCompatible }
            )

            assertEquals(McaBackupRestoreOutcome.VALIDATION_FAILED, result.outcome)
            assertEquals("""{"kind":"mca-backup"}""", file.readText())
        }
    }

    @Test
    fun `publishVerified accepts a manifest the verifier understands`() {
        withTempRoot { root ->
            val file = File(root, "last-verified-backup.json")
            val payload = emptyMcaBackupEnvelope(
                createdAtMillis = 1_700_000_000_000L,
                appVersionName = "1.0"
            ).toJson().toByteArray()

            val result = McaBackupStaging.publishVerified(
                file = file,
                payload = payload,
                validate = { staged -> McaBackupVerify.verify(staged.readText()).isCompatible }
            )

            assertTrue(result.succeeded)
            assertTrue(McaBackupVerify.verify(file.readText()).isCompatible)
        }
    }

    private fun withTempRoot(block: (File) -> Unit) {
        val root = File.createTempFile("mca-backup-staging", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

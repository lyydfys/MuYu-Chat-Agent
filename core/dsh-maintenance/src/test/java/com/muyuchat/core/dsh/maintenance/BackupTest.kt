package com.muyuchat.core.dsh.maintenance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupTest {

    private val roots = ArrayList<File>()

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("dsh-backup")
        roots.add(root)
        return root
    }

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private fun home(): File {
        val root = newRoot()
        val home = File(root, "home")
        TestSupport.write(File(home, ".credentials.yaml"), "deepseek: supersecrettoken123456")
        TestSupport.write(File(home, "config/settings.json"), "{\"apiKey\":\"sk-abcdefghijklmnop\",\"x\":1}")
        TestSupport.write(File(home, "config/ui.json"), "{\"theme\":\"dark\"}")
        TestSupport.write(File(home, "settings.json"), "{\"language\":\"zh\"}")
        TestSupport.write(File(home, "sessions/main/session-a/session.v4.jsonl.zstd"), "session-bytes")
        TestSupport.write(File(home, "plugins/tool/package.json"), "{\"name\":\"tool\"}")
        TestSupport.write(File(home, "plugins/tool/index.js"), "console.log('tool')")
        TestSupport.writeBytes(File(home, "plugins/tool/native.so"), byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        TestSupport.writeBytes(File(home, "cache/blob.bin"), ByteArray(32) { 1 })
        TestSupport.writeBytes(File(home, "downloads/report.pdf.part"), byteArrayOf(1, 2, 3))
        return home
    }

    private fun sources(home: File) = listOf(DshBackupSource(home, "home"))

    @Test
    fun plannerExcludesSecretsCachesAndExecutables() {
        val home = home()
        val selection = DshBackupPlanner().plan(sources(home))
        assertFalse(selection.entries.isEmpty())
        val paths = selection.entries.map { it.path }
        assertTrue(paths.contains("home/sessions/main/session-a/session.v4.jsonl.zstd"))
        assertTrue(paths.contains("home/plugins/tool/index.js"))
        assertTrue(paths.contains("home/config/ui.json"))
        assertTrue(paths.contains("home/settings.json"))
        assertFalse(paths.any { it.contains(".credentials") })
        assertFalse(paths.any { it.endsWith("settings.json") && it.contains("config/") })
        assertFalse(paths.any { it.contains("cache/") })
        assertFalse(paths.any { it.endsWith(".so") })
        assertFalse(paths.any { it.endsWith(".part") })
        val skipped = selection.skipped.associate { it.path to it.reason }
        assertEquals(DshBackupSkipReason.SECRET_NAME, skipped["home/.credentials.yaml"])
        assertEquals(DshBackupSkipReason.SECRET_CONTENT, skipped["home/config/settings.json"])
        assertEquals(DshBackupSkipReason.CACHE, skipped["home/cache/blob.bin"])
        assertEquals(DshBackupSkipReason.EXECUTABLE, skipped["home/plugins/tool/native.so"])
    }

    @Test
    fun archiveContainsNoSecretAndValidates() {
        val root = newRoot()
        val home = home()
        val selection = DshBackupPlanner().plan(sources(home))
        val archive = File(root, "backup.zip")
        val written = DshBackupWriter().write(selection, archive)
        assertTrue(written is DshBackupWriteResult.Created)
        val created = written as DshBackupWriteResult.Created
        assertEquals(selection.entries.size, created.manifest.entries.size)
        ZipFile(archive).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.contains(MANIFEST_ENTRY_NAME))
            assertFalse(names.any { it.contains("credentials") })
            for (name in names) {
                val bytes = zip.getInputStream(zip.getEntry(name)).readBytes().toString(Charsets.UTF_8)
                assertFalse("archive leaked a secret in $name", bytes.contains("supersecrettoken123456"))
                assertFalse("archive leaked a secret in $name", bytes.contains("sk-abcdefghijklmnop"))
            }
        }
        val validation = DshBackupValidator().validate(archive, File(root, "validate"))
        assertTrue(validation is DshBackupValidation.Valid)
        assertEquals(1, (validation as DshBackupValidation.Valid).manifest.schemaVersion)
    }

    @Test
    fun restorePopulatesFreshTargetAndVerifiesManifest() {
        val root = newRoot()
        val home = home()
        val selection = DshBackupPlanner().plan(sources(home))
        val archive = File(root, "backup.zip")
        assertTrue(DshBackupWriter().write(selection, archive) is DshBackupWriteResult.Created)
        val engine = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), TestSupport.healthyProbe())
        val service = DshBackupService(engine)
        val target = File(root, "restored-home")
        val result = service.restore(
            DshRestoreRequest(
                archive = archive,
                homeTarget = target,
                targetRoot = root,
                requester = TestSupport.requester(),
                quiescence = TestSupport.stopped()
            )
        )
        assertTrue(result is DshBackupRestoreResult.Restored)
        assertEquals(
            "session-bytes",
            File(target, "sessions/main/session-a/session.v4.jsonl.zstd").readText()
        )
        assertEquals("{\"name\":\"tool\"}", File(target, "plugins/tool/package.json").readText())
        assertFalse(File(target, ".credentials.yaml").exists())
        assertFalse(File(target, "config/settings.json").exists())
    }

    @Test
    fun maliciousArchiveEntryIsRejected() {
        val root = newRoot()
        val archive = File(root, "evil.zip")
        val content = "evil".toByteArray()
        val manifest = manifestJson(
            entries = """[{"path":"home/ok.txt","sha256":"${DshMaintenanceIo.sha256(content)}","bytes":4}]""",
            total = 4
        )
        zip(archive, manifest, "home/ok.txt" to content, "../escape.txt" to content)
        val validation = DshBackupValidator().validate(archive, File(root, "validate"))
        assertTrue(validation is DshBackupValidation.Invalid)
        assertEquals(
            DshMaintenanceError.ARCHIVE_MALICIOUS,
            (validation as DshBackupValidation.Invalid).failure.code
        )
    }

    @Test
    fun hashMismatchAndUnknownVersionAreRejected() {
        val root = newRoot()
        val tampered = File(root, "tampered.zip")
        val manifest = manifestJson(
            entries = """[{"path":"home/ok.txt","sha256":"${"a".repeat(64)}","bytes":4}]""",
            total = 4
        )
        zip(tampered, manifest, "home/ok.txt" to "evil".toByteArray())
        val mismatch = DshBackupValidator().validate(tampered, File(root, "validate1"))
        assertEquals(
            DshMaintenanceError.ARCHIVE_INVALID,
            (mismatch as DshBackupValidation.Invalid).failure.code
        )
        val future = File(root, "future.zip")
        val futureManifest = manifestJson(
            entries = """[{"path":"home/ok.txt","sha256":"${DshMaintenanceIo.sha256("evil".toByteArray())}","bytes":4}]""",
            total = 4,
            schemaVersion = 99
        )
        zip(future, futureManifest, "home/ok.txt" to "evil".toByteArray())
        val version = DshBackupValidator().validate(future, File(root, "validate2"))
        assertEquals(
            DshMaintenanceError.VERSION_UNSUPPORTED,
            (version as DshBackupValidation.Invalid).failure.code
        )
    }

    @Test
    fun restoreConflictIsVisibleAndOverwriteKeepsRestorePoint() {
        val root = newRoot()
        val home = home()
        val selection = DshBackupPlanner().plan(sources(home))
        val archive = File(root, "backup.zip")
        DshBackupWriter().write(selection, archive)
        val engine = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), TestSupport.healthyProbe())
        val service = DshBackupService(engine)
        val target = File(root, "restored-home")
        val base = DshRestoreRequest(
            archive = archive,
            homeTarget = target,
            targetRoot = root,
            requester = TestSupport.requester(),
            quiescence = TestSupport.stopped()
        )
        assertTrue(service.restore(base) is DshBackupRestoreResult.Restored)
        val sessionFile = File(target, "sessions/main/session-a/session.v4.jsonl.zstd")
        sessionFile.writeText("local-edit")
        val conflict = service.restore(base)
        assertEquals(
            DshMaintenanceError.RESTORE_CONFLICT,
            (conflict as DshBackupRestoreResult.Rejected).failure.code
        )
        assertEquals("local-edit", sessionFile.readText())
        val overwrite = service.restore(
            DshRestoreRequest(
                archive = archive,
                homeTarget = target,
                targetRoot = root,
                requester = TestSupport.requester("op-2"),
                quiescence = TestSupport.stopped(),
                overwriteExisting = true
            )
        )
        assertTrue(overwrite is DshBackupRestoreResult.Restored)
        assertEquals("session-bytes", sessionFile.readText())
        val point = (overwrite as DshBackupRestoreResult.Restored).restorePoint
        assertNotNull(point)
        val savedOld = File(point!!, "restored-home/sessions/main/session-a/session.v4.jsonl.zstd")
        assertEquals("local-edit", savedOld.readText())
    }

    @Test
    fun interruptedRestoreIsRolledBackByTheJournal() {
        val root = newRoot()
        val home = home()
        val selection = DshBackupPlanner().plan(
            listOf(DshBackupSource(File(home, "sessions"), "home/sessions"))
        )
        val archive = File(root, "session-only.zip")
        DshBackupWriter().write(selection, archive)
        val target = File(root, "restored-home")
        TestSupport.write(File(target, "sessions/main/session-a/session.v4.jsonl.zstd"), "local-edit")
        val admission = TestSupport.FakeAdmission()
        val crashing = DshMaintenanceEngine(
            root,
            admission,
            TestSupport.healthyProbe(),
            crashHook = TestSupport.CrashAt(DshMaintenanceBoundary.STEP_APPLIED, stepIndex = 0)
        )
        val restorer = DshBackupRestorer(crashing, DshBackupValidator())
        assertThrows(DshSimulatedCrash::class.java) {
            restorer.restore(
                DshRestoreRequest(
                    archive = archive,
                    homeTarget = target,
                    targetRoot = root,
                    requester = TestSupport.requester(),
                    quiescence = TestSupport.stopped(),
                    overwriteExisting = true
                )
            )
        }
        val afterCrash = File(target, "sessions/main/session-a/session.v4.jsonl.zstd")
        assertEquals("session-bytes", afterCrash.readText())
        val recovering = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), TestSupport.healthyProbe())
        val recovered = recovering.recoverOnStart()
        assertEquals(1, recovered.size)
        assertTrue(recovered.first() is DshMaintenanceRecoveryResult.RolledBack)
        assertEquals("local-edit", afterCrash.readText())
    }

    @Test
    fun encryptedBackupRoundTripsOnlyWithTheSelectedCipher() {
        val root = newRoot()
        val home = home()
        val selection = DshBackupPlanner().plan(sources(home))
        val archive = File(root, "backup.enc")
        val cipher = XorCipher()
        val encryption = DshBackupEncryption(enabled = true, cipherId = cipher.cipherId)
        val written = DshBackupWriter(cipher = cipher).write(
            selection = selection,
            output = archive,
            encryption = encryption
        )
        assertTrue(written is DshBackupWriteResult.Created)
        assertTrue(DshBackupValidator().validate(archive, File(root, "v1")) is DshBackupValidation.Invalid)
        assertTrue(
            DshBackupValidator(cipher = cipher).validate(archive, File(root, "v2")) is DshBackupValidation.Valid
        )
    }

    private fun manifestJson(entries: String, total: Long, schemaVersion: Int = 1): String =
        "{\"format\":\"mca.dsh.backup\",\"schemaVersion\":$schemaVersion,\"createdAt\":1," +
            "\"creator\":\"test\",\"closureId\":\"\",\"totalBytes\":$total,\"note\":\"\"," +
            "\"encryption\":{\"enabled\":false,\"cipherId\":\"\"}," +
            "\"sourceRoots\":[\"home\"],\"entries\":$entries,\"skipped\":[]}"

    private fun zip(file: File, manifest: String, vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private class XorCipher : DshBackupCipherPort {
        override val cipherId: String = "test-xor"
        override fun encrypt(plain: File, sealed: File) {
            sealed.writeBytes(plain.readBytes().map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
        }

        override fun decrypt(sealed: File, plain: File) {
            plain.writeBytes(sealed.readBytes().map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
        }
    }
}

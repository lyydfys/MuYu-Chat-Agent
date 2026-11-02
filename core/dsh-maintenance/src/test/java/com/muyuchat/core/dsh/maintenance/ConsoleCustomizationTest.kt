package com.muyuchat.core.dsh.maintenance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConsoleCustomizationTest {

    private val roots = ArrayList<File>()

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("dsh-console")
        roots.add(root)
        return root
    }

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private class Fixture(val root: File) {
        val home = File(root, "home")
        val engine = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), TestSupport.healthyProbe())
        val patch = DshConsolePatchManager(engine, home)
        val layer = File(home, "user-patches/dsh-console")

        fun seed(): Fixture {
            TestSupport.write(File(layer, "console/theme.css"), "old-css")
            TestSupport.write(File(home, "sessions/main/session-a/session.v4.jsonl.zstd"), "session")
            return this
        }

        fun proposal(
            files: List<DshPatchFile>,
            scope: DshPatchScope = DshPatchScope.DSH_CONSOLE
        ) = DshPatchProposal(
            patchId = "patch-1",
            targetVersion = "kernel-1",
            scope = scope,
            factoryBaseHash = "a".repeat(64),
            managedPatchHash = "b".repeat(64),
            files = files
        )

        fun file(relative: String, content: String, base: String?): DshPatchFile {
            val bytes = content.toByteArray()
            return DshPatchFile(relative, base, DshMaintenanceIo.sha256(bytes), bytes)
        }
    }

    @Test
    fun appliesAndRollsBackAnExactFilePatch() {
        val fixture = Fixture(newRoot()).seed()
        val oldHash = DshMaintenanceIo.sha256(File(fixture.layer, "console/theme.css").readBytes())
        val proposal = fixture.proposal(listOf(fixture.file("console/theme.css", "new-css", oldHash)))
        val applied = fixture.patch.apply(proposal, TestSupport.requester(), TestSupport.stopped())
        assertTrue(applied is DshPatchResult.Applied)
        assertEquals("new-css", File(fixture.layer, "console/theme.css").readText())
        assertEquals(1, fixture.patch.state().appliedFiles.size)
        val rolled = fixture.patch.rollback(TestSupport.requester("rb"), TestSupport.stopped())
        assertTrue(rolled is DshPatchResult.RolledBack)
        assertEquals("old-css", File(fixture.layer, "console/theme.css").readText())
        assertTrue(File(fixture.home, "sessions/main/session-a/session.v4.jsonl.zstd").isFile)
    }

    @Test
    fun conflictsAndForbiddenTargetsAreRejectedWithoutWrites() {
        val fixture = Fixture(newRoot()).seed()
        val wrongBase = "c".repeat(64)
        val conflict = fixture.patch.apply(
            fixture.proposal(listOf(fixture.file("console/theme.css", "hijack", wrongBase))),
            TestSupport.requester(),
            TestSupport.stopped()
        )
        assertTrue(conflict is DshPatchResult.Conflict)
        assertTrue((conflict as DshPatchResult.Conflict).conflicts.isNotEmpty())
        assertEquals("old-css", File(fixture.layer, "console/theme.css").readText())

        val executable = fixture.patch.validate(
            fixture.proposal(listOf(fixture.file("console/app.so", "elf", null)))
        )
        assertEquals(DshMaintenanceError.PATCH_FORBIDDEN, executable?.code)
        val foreign = fixture.patch.validate(
            fixture.proposal(listOf(fixture.file("console/theme.css", "css", null)), scope = DshPatchScope.OTHER)
        )
        assertEquals(DshMaintenanceError.PATCH_FORBIDDEN, foreign?.code)
        val traversal = fixture.patch.validate(
            fixture.proposal(listOf(fixture.file("../escape.css", "css", null)))
        )
        assertEquals(DshMaintenanceError.PATCH_FORBIDDEN, traversal?.code)
        val managed = fixture.patch.validate(
            fixture.proposal(listOf(fixture.file("payload/dshroot/index.js", "x", null)))
        )
        assertEquals(DshMaintenanceError.PATCH_FORBIDDEN, managed?.code)
        val execution = fixture.patch.validate(
            fixture.proposal(listOf(fixture.file("console/config.json", "{\"command\":\"rm -rf /\"}", null)))
        )
        assertEquals(DshMaintenanceError.PATCH_FORBIDDEN, execution?.code)
    }

    @Test
    fun clearingTheUserLayerKeepsSessionsAndPlugins() {
        val fixture = Fixture(newRoot()).seed()
        TestSupport.write(File(fixture.home, "plugins/tool/index.js"), "plugin")
        val oldHash = DshMaintenanceIo.sha256(File(fixture.layer, "console/theme.css").readBytes())
        assertTrue(
            fixture.patch.apply(
                fixture.proposal(listOf(fixture.file("console/theme.css", "new-css", oldHash))),
                TestSupport.requester(),
                TestSupport.stopped()
            ) is DshPatchResult.Applied
        )
        val cleared = fixture.patch.clearUserLayer(TestSupport.requester("clear"), TestSupport.stopped())
        assertTrue(cleared is DshPatchResult.Cleared)
        assertFalse(fixture.layer.exists())
        assertEquals("plugin", File(fixture.home, "plugins/tool/index.js").readText())
        assertTrue(File(fixture.home, "sessions/main/session-a/session.v4.jsonl.zstd").isFile)
        assertTrue(fixture.patch.state().activePatchId == null)
    }

    @Test
    fun themeValidationEnforcesContrastAndClosedSchema() {
        assertTrue(DshConsoleThemeValidator.validate(DshConsoleTheme.DEFAULT) is DshThemeValidation.Valid)
        val lowContrast = DshConsoleTheme(
            schemaVersion = 1,
            id = "bad",
            name = "bad",
            tokens = DshConsoleTheme.DEFAULT.tokens.toMutableMap().apply {
                put("textPrimary", "#0B0F14")
            }
        )
        assertEquals(
            DshMaintenanceError.THEME_INVALID,
            (DshConsoleThemeValidator.validate(lowContrast) as DshThemeValidation.Invalid).failure.code
        )
        val unknownToken = DshConsoleTheme(
            schemaVersion = 1,
            id = "bad",
            name = "bad",
            tokens = DshConsoleTheme.DEFAULT.tokens + ("shadowScript" to "#FFFFFF")
        )
        assertTrue(DshConsoleThemeValidator.validate(unknownToken) is DshThemeValidation.Invalid)
        val scriptCopy = DshConsoleTheme(
            schemaVersion = 1,
            id = "bad",
            name = "bad",
            tokens = DshConsoleTheme.DEFAULT.tokens,
            text = mapOf("send" to "javascript:alert(1)")
        )
        assertTrue(DshConsoleThemeValidator.validate(scriptCopy) is DshThemeValidation.Invalid)
        val privilegedWithoutConfirm = DshConsoleTheme(
            schemaVersion = 1,
            id = "bad",
            name = "bad",
            tokens = DshConsoleTheme.DEFAULT.tokens,
            actions = listOf(
                DshThemeActionBinding(DshConsoleGesture.LONG_PRESS, DshConsoleActionId.OPEN_RESCUE)
            )
        )
        assertTrue(DshConsoleThemeValidator.validate(privilegedWithoutConfirm) is DshThemeValidation.Invalid)
        val confirmed = privilegedWithoutConfirm.copy(
            actions = listOf(
                DshThemeActionBinding(
                    DshConsoleGesture.LONG_PRESS,
                    DshConsoleActionId.OPEN_RESCUE,
                    requireConfirmation = true
                )
            )
        )
        assertTrue(DshConsoleThemeValidator.validate(confirmed) is DshThemeValidation.Valid)
    }

    @Test
    fun themeRoundTripAndBadStoredConfigFallsBackToDefault() {
        val root = newRoot()
        val engine = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), TestSupport.healthyProbe())
        val manager = DshConsoleThemeManager(engine)
        val encoded = manager.exportTheme(DshConsoleTheme.DEFAULT)
        val decoded = DshConsoleThemeCodec.decode(encoded)
        assertTrue(decoded is DshThemeDecodeResult.Valid)
        assertEquals(
            DshConsoleTheme.DEFAULT.tokens,
            (decoded as DshThemeDecodeResult.Valid).theme.tokens
        )
        assertTrue(
            DshConsoleThemeCodec.decode("{\"schemaVersion\":1}") is DshThemeDecodeResult.Invalid
        )
        val saved = manager.save(DshConsoleTheme.DEFAULT, TestSupport.requester(), TestSupport.stopped())
        assertTrue(saved is DshThemeSaveResult.Saved)
        assertFalse(manager.load().usedDefault)
        // Corrupt the stored document: the console must fall back to the default, never execute it.
        File(root, "console/theme.json").writeText("{\"schemaVersion\":1,\"id\":\"x\",\"name\":\"x\",\"tokens\":{}}")
        val loaded = manager.load()
        assertTrue(loaded.usedDefault)
        assertEquals(DshConsoleTheme.DEFAULT.id, loaded.theme.id)
    }
}

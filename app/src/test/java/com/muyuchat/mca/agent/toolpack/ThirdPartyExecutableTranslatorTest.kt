package com.muyuchat.mca.agent.toolpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translation matrix for the third-party -> MCA executable-v2 adapter.
 *
 * These are pure JVM tests: no store, no Rhino execution and no device. They prove that the
 * supported subset produces an admissible MCA package and that every unsupported third-party surface is
 * a typed refusal naming the symbol/path, never a silent drop or a fake success.
 */
class ThirdPartyExecutableTranslatorTest {

    private val service = ThirdPartyToolPackageTranslationService()

    @Test
    fun `a supported third-party subpackage becomes one executable MCA tool`() {
        val result = service.translate(
            ThirdPartyTranslationFixtures.manifest(),
            ThirdPartyTranslationFixtures.scripts()
        )
        assertTrue("expected translation, was $result", result is ThirdPartyTranslationResult.Translated)
        val packageBytes = (result as ThirdPartyTranslationResult.Translated).translated
        assertEquals("thirdparty-demo-writer", packageBytes.mcaPackageId)
        assertEquals(listOf("write_report"), packageBytes.toolNames)
        assertEquals(
            listOf(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, ToolPackageHostApis.WORKSPACE_READ_TEXT),
            packageBytes.mappedHostApis
        )
        assertTrue(packageBytes.report.runnable)
        // The MCA manifest must be admissible by the real v2 loader (the translator already checks
        // this; re-assert so a future regression fails here, not at install time).
        ExecutableToolPackageLoader().load(packageBytes.manifestJson, packageBytes.scripts)
        // The generated scripts carry the only translated host surface.
        assertTrue(packageBytes.scripts.getValue(ThirdPartyTranslationFixtures.SUBPACKAGE_ENTRY).contains("host.call(\"workspace.read_text\""))
        assertTrue(packageBytes.scripts.getValue(ThirdPartyTranslationFixtures.SUBPACKAGE_ENTRY).contains("host.call(\"artifact.write_text\""))
    }

    @Test
    fun `translation is deterministic and idempotent for identical input`() {
        val first = service.translate(
            ThirdPartyTranslationFixtures.manifest(),
            ThirdPartyTranslationFixtures.scripts()
        ) as ThirdPartyTranslationResult.Translated
        val second = service.translate(
            ThirdPartyTranslationFixtures.manifest(),
            ThirdPartyTranslationFixtures.scripts()
        ) as ThirdPartyTranslationResult.Translated
        assertEquals(first.translated.manifestJson, second.translated.manifestJson)
        assertEquals(first.translated.scripts, second.translated.scripts)
        assertEquals(first.translated.toolNames, second.translated.toolNames)
        assertEquals(first.translated.report.summaryStatus, second.translated.report.summaryStatus)
    }

    @Test
    fun `a third-party archive is translated without extracting it`() {
        val result = service.translateArchive(ThirdPartyTranslationFixtures.archive())
        assertTrue("expected translation, was $result", result is ThirdPartyTranslationResult.Translated)
        val packageBytes = (result as ThirdPartyTranslationResult.Translated).translated
        assertEquals(listOf("write_report"), packageBytes.toolNames)
    }

    @Test
    fun `WASM modules are refused with the exact symbol`() {
        val result = service.translate(
            ThirdPartyTranslationFixtures.manifest(wasm = true),
            ThirdPartyTranslationFixtures.scripts(),
            ThirdPartyTranslationFixtures.listing(listOf("modules/core.wasm"))
        ) as ThirdPartyTranslationResult.Rejected
        assertEquals(ThirdPartyTranslationErrorCode.WASM_UNSUPPORTED, result.code)
        assertEquals("modules/core.wasm", result.symbol)
    }

    @Test
    fun `third-party dependencies are refused instead of being silently ignored`() {
        val result = service.translate(
            ThirdPartyTranslationFixtures.manifest(requires = true),
            ThirdPartyTranslationFixtures.scripts()
        ) as ThirdPartyTranslationResult.Rejected
        assertEquals(ThirdPartyTranslationErrorCode.DEPENDENCY_UNSUPPORTED, result.code)
        assertEquals("thirdparty.dep", result.symbol)
    }

    @Test
    fun `declared resources are refused because the v2 archive only carries JavaScript`() {
        val result = service.translate(
            ThirdPartyTranslationFixtures.manifest(resources = true),
            ThirdPartyTranslationFixtures.scripts(),
            ThirdPartyTranslationFixtures.listing(listOf("resources/logo.png"))
        ) as ThirdPartyTranslationResult.Rejected
        assertEquals(ThirdPartyTranslationErrorCode.RESOURCE_UNSUPPORTED, result.code)
        assertEquals("logo", result.symbol)
    }

    @Test
    fun `a third-party ui context file is refused`() {
        val listing = ThirdPartyTranslationFixtures.listing(listOf("ui/index.ui.js"))
        val scripts = ThirdPartyTranslationFixtures.scripts() + ("ui/index.ui.js" to "exports.main = function () {};")
        val result = service.translate(
            ThirdPartyTranslationFixtures.manifest(),
            scripts,
            listing
        ) as ThirdPartyTranslationResult.Rejected
        assertEquals(ThirdPartyTranslationErrorCode.UI_UNSUPPORTED, result.code)
    }

    @Test
    fun `a third-party provider context file is refused`() {
        val listing = ThirdPartyTranslationFixtures.listing(listOf("provider/model.provider.js"))
        val scripts = ThirdPartyTranslationFixtures.scripts() +
            ("provider/model.provider.js" to "exports.main = function () {};")
        val result = service.translate(
            ThirdPartyTranslationFixtures.manifest(),
            scripts,
            listing
        ) as ThirdPartyTranslationResult.Rejected
        assertEquals(ThirdPartyTranslationErrorCode.PROVIDER_UNSUPPORTED, result.code)
    }

    @Test
    fun `an unknown third-party host symbol is refused with the symbol name`() {
        val result = service.translate(
            ThirdPartyTranslationFixtures.manifest(),
            ThirdPartyTranslationFixtures.scripts(
                subpackageSource = "exports.main = function () { return Tools.Net.get('https://example.com'); };"
            )
        ) as ThirdPartyTranslationResult.Rejected
        assertEquals(ThirdPartyTranslationErrorCode.UNSUPPORTED_SYMBOL, result.code)
        assertEquals("Tools.Net.get", result.symbol)
        assertEquals(ThirdPartyTranslationFixtures.SUBPACKAGE_ENTRY, result.fieldPath)
    }

    @Test
    fun `the Java bridge is refused even when the Rhino engine would also deny it`() {
        val result = service.translate(
            ThirdPartyTranslationFixtures.manifest(),
            ThirdPartyTranslationFixtures.scripts(
                subpackageSource = "exports.main = function () { return Java.type('java.io.File'); };"
            )
        ) as ThirdPartyTranslationResult.Rejected
        assertEquals(ThirdPartyTranslationErrorCode.FORBIDDEN_SYMBOL, result.code)
        assertEquals("Java", result.symbol)
    }

    @Test
    fun `a non-relative require is refused with the specifier`() {
        val result = service.translate(
            ThirdPartyTranslationFixtures.manifest(),
            ThirdPartyTranslationFixtures.scripts(
                subpackageSource = "var fs = require('fs');\nexports.main = function () { return {}; };"
            )
        ) as ThirdPartyTranslationResult.Rejected
        assertEquals(ThirdPartyTranslationErrorCode.REQUIRE_INVALID, result.code)
        assertEquals("fs", result.symbol)
    }

    @Test
    fun `a third-party hook registration is refused`() {
        val result = service.translate(
            ThirdPartyTranslationFixtures.manifest(),
            ThirdPartyTranslationFixtures.scripts(
                subpackageSource = "onBeforeTool(function () {});\nexports.main = function () { return {}; };"
            )
        ) as ThirdPartyTranslationResult.Rejected
        assertEquals(ThirdPartyTranslationErrorCode.HOOK_UNSUPPORTED, result.code)
    }

    @Test
    fun `a missing enabled subpackage entry is refused before producing any package`() {
        val scripts = ThirdPartyTranslationFixtures.scripts() - ThirdPartyTranslationFixtures.SUBPACKAGE_ENTRY
        val result = service.translate(
            ThirdPartyTranslationFixtures.manifest(),
            scripts,
            ThirdPartyTranslationFixtures.listing()
        ) as ThirdPartyTranslationResult.Rejected
        assertEquals(ThirdPartyTranslationErrorCode.ENTRY_MISSING, result.code)
    }

    @Test
    fun `an unsupported script path is refused`() {
        val scripts = ThirdPartyTranslationFixtures.scripts() + ("extra.cjs" to "exports.main = function () {};")
        val listing = ThirdPartyTranslationFixtures.listing(listOf("extra.cjs"))
        val result = service.translate(
            ThirdPartyTranslationFixtures.manifest(),
            scripts,
            listing
        ) as ThirdPartyTranslationResult.Rejected
        assertEquals(ThirdPartyTranslationErrorCode.SCRIPT_PATH_UNSUPPORTED, result.code)
    }
}

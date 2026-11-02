package com.muyuchat.core.dsh.runtime.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D02: the composed overlay must stay honest and must not destroy work owned by the user or by the
 * official plugin manager.
 *
 * The merge is the MCA-managed layer only: managed row ids are regenerated, every other patch row
 * survives verbatim, an unreadable previous file is kept as a backup instead of being merged, and
 * the honesty inspector refuses a configuration that would lie about the bound provider, disable
 * the official account provider, or drop a required Android platform row.
 */
class DshConfigCompositionPolicyTest {

    // ------------------------------------------------------------------ merge

    @Test
    fun firstGenerationWithNoPreviousOverlayPublishesTheGeneratedTextVerbatim() {
        val plan = DshManagedOverlayMerge.plan(previous = null, generated = GENERATED)
        assertEquals(GENERATED, plan.text)
        assertTrue(plan.preservedUserRows.isEmpty())
        assertFalse(plan.previousWasCorrupt)
        assertFalse(plan.backupPrevious)
    }

    @Test
    fun blankPreviousOverlayIsTreatedAsFirstGeneration() {
        val plan = DshManagedOverlayMerge.plan(previous = "  \n\t\n", generated = GENERATED)
        assertEquals(GENERATED, plan.text)
        assertFalse(plan.previousWasCorrupt)
    }

    @Test
    fun userOwnedRowsArePreservedAndManagedRowsAreRegenerated() {
        val previous = buildString {
            appendLine("- id: llm-pi-ai")
            appendLine("  config:")
            appendLine("    providers: {}")
            appendLine("- id: user-analytics")
            appendLine("  enabled: false")
            appendLine("- insert:")
            appendLine("    - id: user-extra-shell")
            appendLine("      name: '@user/extra-shell'")
        }
        val plan = DshManagedOverlayMerge.plan(previous, GENERATED)

        assertTrue("the generated managed rows must be present", plan.text.startsWith(GENERATED.trimEnd()))
        assertTrue("the user's own row must survive", plan.text.contains("user-analytics"))
        assertTrue("user rows that are not MCA-managed must survive verbatim", plan.text.contains("user-extra-shell"))
        assertEquals(listOf("user-analytics", "insert"), plan.preservedUserRows)
        assertTrue("managed rows are regenerated, not kept", plan.managedConflicts.contains("llm-pi-ai"))
        assertFalse(plan.text.contains("providers: {}"))
        assertFalse(plan.previousWasCorrupt)
    }

    @Test
    fun aManagedInsertRowIsRegeneratedEvenWhenTheUserEditedIt() {
        val previous = buildString {
            appendLine("- insert:")
            appendLine("    - id: bash-local")
            appendLine("      name: '@deepseek-ai/dsh-bash-local'")
            appendLine("      config:")
            appendLine("        cwd: /wrong/path")
        }
        val plan = DshManagedOverlayMerge.plan(previous, GENERATED)
        assertFalse("the stale MCA cwd must not survive", plan.text.contains("/wrong/path"))
        assertEquals(1, plan.text.lines().count { it.trim() == "- id: bash-local" })
    }

    @Test
    fun anUnreadablePreviousOverlayIsBackedUpAndReplacedByTheValidGeneration() {
        val previous = "not: a patch sequence\n```garbage"
        val plan = DshManagedOverlayMerge.plan(previous, GENERATED)
        assertTrue(plan.previousWasCorrupt)
        assertTrue("the caller must keep the previous file before replacing it", plan.backupPrevious)
        assertEquals(GENERATED, plan.text)
    }

    @Test
    fun aMappingBeforeTheFirstSequenceRowIsRejectedAsNotAPatch() {
        assertNull(DshManagedOverlayMerge.parse("permission:\n  presets: {}\n- id: x\n"))
    }

    @Test
    fun aCommentOnlyFileIsAParseableEmptyPatch() {
        val parsed = DshManagedOverlayMerge.parse("# just a comment\n")
        assertNotNull(parsed)
        assertTrue(parsed!!.isEmpty())
        val plan = DshManagedOverlayMerge.plan("# just a comment\n", GENERATED)
        assertEquals(GENERATED, plan.text)
        assertFalse(plan.backupPrevious)
    }

    // ------------------------------------------------------------------ honesty

    @Test
    fun aGeneratedEndpointOverlayPassesTheHonestyGate() {
        val facts = DshComposedConfigInspector.inspect(GENERATED)
        assertTrue(facts.bashSandboxDisabled)
        assertTrue(facts.pwshSandboxDisabled)
        assertTrue(facts.sessionLogDownloadDisabled)
        assertTrue(facts.telemetryDisabled)
        assertTrue(facts.bashLocalInserted)
        assertEquals("workspace-write", facts.permissionDefaultPreset)
        assertTrue("a normal loopback baseURL is concrete", facts.mcaBaseUrlIsLoopback)
        assertEquals(2, facts.mcaModelsDeclared)
        assertFalse(facts.touchesOfficialAccountProvider)
        assertTrue(DshComposedConfigInspector.violations(facts, requireMcaProvider = true).isEmpty())
    }

    @Test
    fun anEmptyCatalogMustBeDeclaredAsAnExplicitEmptySequence() {
        val overlay = GENERATED.replace(
            "        models:\n          - id: \"mca-local:a\"\n            name: \"A\"\n" +
                "          - id: \"mca-cloud:b\"\n            name: \"B\"\n",
            "        models: []\n"
        )
        val facts = DshComposedConfigInspector.inspect(overlay)
        assertEquals(0, facts.mcaModelsDeclared)
        assertTrue(facts.mcaModelsEmptyArray)
        assertTrue(DshComposedConfigInspector.violations(facts, requireMcaProvider = true).isEmpty())
    }

    @Test
    fun aProviderRowWithoutModelsIsRejectedInsteadOfBecomingYamlNull() {
        val overlay = GENERATED
            .replace("        models: []\n", "")
            .replace(
                "        models:\n          - id: \"mca-local:a\"\n            name: \"A\"\n" +
                    "          - id: \"mca-cloud:b\"\n            name: \"B\"\n",
                ""
            )
        val facts = DshComposedConfigInspector.inspect(overlay)
        val violations = DshComposedConfigInspector.violations(facts, requireMcaProvider = true)
        assertTrue(violations.any { it.contains("models: []") })
    }

    @Test
    fun aFakeOrNonLoopbackEndpointIsAHonestyViolation() {
        val facts = DshComposedConfigInspector.inspect(
            GENERATED.replace("http://127.0.0.1:41234/v1", "https://example.com/v1")
        )
        assertFalse(facts.mcaBaseUrlIsLoopback)
        assertTrue(
            DshComposedConfigInspector.violations(facts, requireMcaProvider = true)
                .any { it.contains("loopback") }
        )
    }

    @Test
    fun anAccountOnlyOverlayMustNotAdvertiseAnUnboundMcaProvider() {
        val accountOnly = GENERATED.replace(mcaProviderBlock(), "")
        val facts = DshComposedConfigInspector.inspect(accountOnly)
        assertFalse(facts.mcaProviderPresent)
        assertTrue(DshComposedConfigInspector.violations(facts, requireMcaProvider = false).isEmpty())
        assertTrue(
            "an account-only generation may not keep an mca provider row",
            DshComposedConfigInspector.violations(facts, requireMcaProvider = false)
                .none { it.contains("llm-pi-ai") }
        )
        val mismatched = DshComposedConfigInspector.violations(facts, requireMcaProvider = true)
        assertTrue(mismatched.any { it.contains("MCA provider row is missing") })
    }

    @Test
    fun modifyingTheOfficialDeepseekAccountProviderIsAHonestyViolation() {
        val overlay = GENERATED + "- id: deepseek-account\n  disabled: true\n"
        val facts = DshComposedConfigInspector.inspect(overlay)
        assertTrue(facts.touchesOfficialAccountProvider)
        assertTrue(
            DshComposedConfigInspector.violations(facts, requireMcaProvider = true)
                .any { it.contains("account provider") }
        )
    }

    @Test
    fun disablingLlmPiAiDroppingBashLocalOrCopyingTheReferenceDangerDefaultAreViolations() {
        val disabledProvider = DshComposedConfigInspector.inspect(
            GENERATED.replace("- id: llm-pi-ai\n  config:", "- id: llm-pi-ai\n  disabled: true\n  config:")
        )
        assertTrue(disabledProvider.mcaProviderDisabled)
        assertTrue(
            DshComposedConfigInspector.violations(disabledProvider, requireMcaProvider = true)
                .any { it.contains("must not be disabled") }
        )

        val noBashLocal = DshComposedConfigInspector.inspect(
            GENERATED.replace("    - id: bash-local", "    - id: other-shell")
        )
        assertFalse(noBashLocal.bashLocalInserted)
        assertTrue(
            DshComposedConfigInspector.violations(noBashLocal, requireMcaProvider = true)
                .any { it.contains("bash-local") }
        )

        val danger = DshComposedConfigInspector.inspect(
            GENERATED.replace("    defaultPreset: workspace-write", "    defaultPreset: danger-full-access\n    approval: never")
        )
        assertTrue(danger.copiesApprovalNever)
        assertTrue(danger.touchesReferenceDangerDefault)
        assertTrue(
            DshComposedConfigInspector.violations(danger, requireMcaProvider = true).size >= 2
        )
    }

    @Test
    fun allFiveOfficialMobileToolPackagesMustStayInserted() {
        val facts = DshComposedConfigInspector.inspect(GENERATED)
        assertEquals(5, facts.insertedMobileTools.size)
        assertTrue(DshComposedConfigInspector.violations(facts, requireMcaProvider = true).isEmpty())

        val withoutBrowser = GENERATED.replace(
            "    - id: tool-browser\n      name: '@deepseek-ai/dsh-tool-browser'\n",
            ""
        )
        val missing = DshComposedConfigInspector.inspect(withoutBrowser)
        assertTrue(
            DshComposedConfigInspector.violations(missing, requireMcaProvider = true)
                .any { it.contains("tool-browser") }
        )

        val withoutAccessibility = GENERATED.replace(
            "    - id: tool-accessibility\n      name: '@deepseek-ai/dsh-tool-accessibility'\n",
            ""
        )
        assertTrue(
            DshComposedConfigInspector.violations(
                DshComposedConfigInspector.inspect(withoutAccessibility),
                requireMcaProvider = true
            ).any { it.contains("tool-accessibility") }
        )
    }

    @Test
    fun theOfficialSandboxServiceMustNeverBeDisabledBecausePtcRuntimeInjectsIt() {
        val facts = DshComposedConfigInspector.inspect(GENERATED)
        assertFalse(facts.sandboxServiceDisabled)
        val disabled = DshComposedConfigInspector.inspect(
            GENERATED + "- id: sandbox\n  disabled: true\n"
        )
        assertTrue(disabled.sandboxServiceDisabled)
        assertTrue(
            DshComposedConfigInspector.violations(disabled, requireMcaProvider = true)
                .any { it.contains("sandbox service") }
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun mcaProviderBlock(): String = buildString {
        appendLine("- id: llm-pi-ai")
        appendLine("  config:")
        appendLine("    providers:")
        appendLine("      mca:")
        appendLine("        displayName: MCA")
        appendLine("        api: openai-completions")
        appendLine("        baseURL: \"http://127.0.0.1:41234/v1\"")
        appendLine("        apiKeyEnv: MCA_DSH_GATEWAY_TOKEN")
        appendLine("        models:")
        appendLine("          - id: \"mca-local:a\"")
        appendLine("            name: \"A\"")
        appendLine("          - id: \"mca-cloud:b\"")
        appendLine("            name: \"B\"")
    }

    private companion object {
        val GENERATED: String = buildString {
            appendLine("# MCA-owned DSH overlay. Regenerated for each Android DSH host generation.")
            appendLine("# mca-sandbox: advisory (RUNNER_MISSING)")
            appendLine("- id: bash-sandbox")
            appendLine("  disabled: true")
            appendLine("- id: pwsh-sandbox")
            appendLine("  disabled: true")
            appendLine("- id: session-log-download")
            appendLine("  disabled: true")
            appendLine("- id: session-telemetry-otel")
            appendLine("  disabled: true")
            appendLine("- id: permission")
            appendLine("  config:")
            appendLine("    presets:")
            appendLine("      read-only:")
            appendLine("        sandbox: read-only")
            appendLine("        approval: ask")
            appendLine("    defaultPreset: workspace-write")
            appendLine("- id: workspace-controller")
            appendLine("  config:")
            appendLine("    documentsDirectory: \"/data/user/0/com.muyuchat.mca/files/dsh/workspace\"")
            appendLine("- id: llm-pi-ai")
            appendLine("  config:")
            appendLine("    providers:")
            appendLine("      mca:")
            appendLine("        displayName: MCA")
            appendLine("        api: openai-completions")
            appendLine("        baseURL: \"http://127.0.0.1:41234/v1\"")
            appendLine("        apiKeyEnv: MCA_DSH_GATEWAY_TOKEN")
            appendLine("        models:")
            appendLine("          - id: \"mca-local:a\"")
            appendLine("            name: \"A\"")
            appendLine("          - id: \"mca-cloud:b\"")
            appendLine("            name: \"B\"")
            appendLine("- insert:")
            appendLine("    # mca:managed-insert")
            appendLine("    - id: bash-local")
            appendLine("      name: '@deepseek-ai/dsh-bash-local'")
            appendLine("      config:")
            appendLine("        cwd: \"/data/user/0/com.muyuchat.mca/files/dsh/workspace\"")
            appendLine("        timeoutMs: 60000")
            appendLine("    - id: tool-shizuku")
            appendLine("      name: '@deepseek-ai/dsh-tool-shizuku'")
            appendLine("    - id: tool-android")
            appendLine("      name: '@deepseek-ai/dsh-tool-android'")
            appendLine("    - id: tool-accessibility")
            appendLine("      name: '@deepseek-ai/dsh-tool-accessibility'")
            appendLine("    - id: tool-vscreen")
            appendLine("      name: '@deepseek-ai/dsh-tool-vscreen'")
            appendLine("    - id: tool-browser")
            appendLine("      name: '@deepseek-ai/dsh-tool-browser'")
        }.toString()
    }
}



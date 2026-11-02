package com.muyuchat.core.dsh.runtime.config

/**
 * One model row as the overlay writer sees it. The seeder's catalog adapter owns discovery and
 * failures; this type is only the immutable row needed to render `llm-pi-ai`.
 */
internal data class DshOverlayModel(
    val id: String,
    val displayName: String,
    val contextWindow: Int?,
    val maxOutputTokens: Int? = null
)

/**
 * Renders the MCA-owned `cordis.patch.yml` body for one host generation.
 *
 * This is deliberately separate from [DshGatewayConfigSeeder]: seeding owns home classification,
 * template/marker migration, merge and atomic publication; this composer owns the exact declarative
 * rows and the kernel/advisory shell split. It has no file or process side effects, so both modes
 * can be tested without a device and the seeder no longer grows every time a platform row changes.
 *
 * The MCA provider row exists only for a generation with a concrete gateway endpoint. An
 * account-only generation writes no `llm-pi-ai` row: a placeholder baseURL/token for a gateway that
 * does not exist would be a dishonest composed configuration.
 */
internal object DshOverlayComposer {

    /** 15 minutes: above a phone local-model prefill, below an indefinitely stuck backend. */
    private const val STREAM_IDLE_TIMEOUT_MILLIS = 900_000

    fun compose(
        config: DshRuntimeConfig,
        baseUrl: String?,
        models: List<DshOverlayModel>,
        sandbox: DshSandboxEnforcement
    ): String = buildString {
        appendLine("# MCA-owned DSH overlay. Regenerated for each Android DSH host generation.")
        appendLine("# Source of truth: core/dsh-runtime DshOverlayComposer. Do not copy the")
        appendLine("# reference Android app's danger-full-access / approval:never configuration.")
        // The official shell executor is either kept (with MCA's Landlock runner as its enforcement
        // backend) or replaced by the advisory bash-local executor. The marker line makes the
        // chosen fact machine-checkable by the honesty gate and readable by diagnostics: a
        // workspace-write preset without a kernel runner is advisory, never isolation.
        when (sandbox) {
            is DshSandboxEnforcement.Kernel -> {
                appendLine("# mca-sandbox: kernel (landlock abi=${sandbox.landlockAbi})")
                appendLine("- id: bash-sandbox")
                appendLine("  config:")
                appendLine("    timeoutMs: 60000")
                appendLine("- id: sandbox")
                appendLine("  config:")
                appendLine("    runnerCommand:")
                appendLine("      - ${yamlScalar(requireNotNull(config.sandboxRunnerFile).absolutePath)}")
                appendLine("    runnerFailureSignatures:")
                appendLine("      - \"mca-dsh-sandbox: \"")
                appendLine("    probeTimeoutMs: 3000")
            }

            is DshSandboxEnforcement.Advisory -> {
                appendLine("# mca-sandbox: advisory (${sandbox.reason.name})")
                appendLine("- id: bash-sandbox")
                appendLine("  disabled: true")
            }
        }
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
        appendLine("      workspace-write:")
        appendLine("        sandbox: workspace-write")
        appendLine("        approval: ask")
        appendLine("      danger-full-access:")
        appendLine("        sandbox: danger-full-access")
        appendLine("        approval: ask")
        appendLine("    defaultPreset: workspace-write")
        // The official workspace controller resolves its first-use directory from
        // documentsDirectory; Android has no OS Documents lookup, so it throws and the UI shows
        // "无法创建默认工作区". Pin the MCA-owned workspace so first use creates
        // <workspace>/deepseek-harness/default-workspace without a native directory picker.
        appendLine("- id: workspace-controller")
        appendLine("  config:")
        appendLine("    documentsDirectory: ${yamlScalar(config.workspaceDir.absolutePath)}")
        if (baseUrl != null) {
            appendLine("- id: llm-pi-ai")
            appendLine("  config:")
            appendLine("    providers:")
            appendLine("      mca:")
            appendLine("        displayName: MCA")
            appendLine("        api: openai-completions")
            appendLine("        baseURL: ${yamlScalar(baseUrl)}")
            appendLine("        apiKeyEnv: MCA_DSH_GATEWAY_TOKEN")
            // A local model's prefill can exceed the official 300 s stream idle window by minutes
            // (~10k-token tool catalogs at phone prefill rates). The official provider exposes this
            // bound per profile, so MCA raises it instead of injecting synthetic output to defeat
            // the watchdog. The elapsed-time guard for a genuinely stuck backend still applies.
            appendLine("        streamIdleTimeoutMs: $STREAM_IDLE_TIMEOUT_MILLIS")
            // An explicit empty sequence is required: a bare models: would be YAML null and would
            // fail the official provider configuration schema instead of opening the UI.
            appendLine(if (models.isEmpty()) "        models: []" else "        models:")
            for (model in models) {
                appendLine("          - id: ${yamlScalar(model.id)}")
                appendLine("            name: ${yamlScalar(model.displayName)}")
                val window = model.contextWindow?.takeIf { it > 0 }
                if (window != null) {
                    appendLine("            contextWindow: $window")
                } else {
                    // ECO-5: no window was advertised for this row, so no `contextWindow` may be
                    // written (a fabricated one would lie). Keep the official row shape and mark
                    // the gap in the file the maintenance/diagnostics path can read.
                    appendLine(
                        "            # mca: contextWindow unavailable; " +
                            "official compaction will refuse this model"
                    )
                }
                model.maxOutputTokens?.takeIf { it > 0 }?.let { budget ->
                    appendLine("            maxTokens: $budget")
                }
            }
        }
        // The insert block is MCA-owned whichever shell provider is composed; the marker keeps
        // that ownership stable when the advisory path's bash-local row is absent.
        appendLine("- insert:")
        appendLine("    # mca:managed-insert")
        // Advisory mode: the official executor is unconfined on this kernel, so bash-local is the
        // only honest provider. Kernel mode keeps the official bash-sandbox provider and does not
        // insert a second ctx.shell.
        if (sandbox is DshSandboxEnforcement.Advisory) {
            appendLine("    - id: bash-local")
            appendLine("      name: '@deepseek-ai/dsh-bash-local'")
            appendLine("      config:")
            appendLine("        cwd: ${yamlScalar(config.workspaceDir.absolutePath)}")
            appendLine("        timeoutMs: 60000")
        }
        // The five official mobile tool packages stay enabled exactly as the pinned payload's
        // reference overlay does. They are the DSH-side tool definitions and schemas; MCA's
        // capability bridge only replaces the App-side HTTP implementation they call. Without
        // these inserts the agent never sees the android/shizuku/accessibility/vscreen/browser
        // tool families, no matter how healthy the bridge is. Each package self-gates its roster
        // on APP_NOTIFY_PORT / APP_A11Y_PORT / APP_LOCAL_TOKEN and the root/Shizuku flags.
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
        // The official bash tool rebuilds its env from shellEnv contributors and drops ambient
        // DSH_* values; the bundled `bash` dispatcher needs DSH_PAYLOAD_DIR. The registry accepts
        // only DSH_* keys and aborts the whole registration on any other name, so TMPDIR is not
        // declared here (it survives through the executor's scrubbedParentEnv). The installer
        // writes this as a real package under profiles/web/node_modules because the loader
        // silently skips a row whose name is a relative .mjs file (device evidence 2026-10-25).
        appendLine("    - id: mca-shell-env")
        appendLine("      name: '@mca/dsh-shell-env'")
        appendLine("      config:")
        appendLine("        payloadDir: ${yamlScalar(config.payloadDir.absolutePath)}")
    }
}

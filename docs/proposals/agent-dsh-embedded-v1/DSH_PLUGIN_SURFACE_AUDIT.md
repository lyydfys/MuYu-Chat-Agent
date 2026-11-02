# DSH Plugin Surface Audit

Status: read-only audit. Date: 2026-10-19. Scope: `feature/dsh-workspace/**`, `app/**/dsh/**`,
`api/dsh-gateway/**`, `scripts/dsh/**`, with the runtime/config seams they depend on read as
context. No production code, build, test, APK, device or network run was performed. Results use
`PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE` with an explicit scope.

Related: [DSHA multi-project review](DSHA_MULTI_PROJECT_REVIEW_20261019.md),
[embedded architecture](ARCHITECTURE.md), [ADR 0003](../../adr/0003-dsh-embedded-dual-agent-modes.md).

---

## 0. Verdict

1. **MCA exposes the official DSH surface, not a plugin surface of its own.** The DSH tab is one
   WebView over the official Web profile. The pinned bundle configuration enables the official
   Plugins page (`ui-plugin-manager`) and the official `plugin-manager` host service. MCA adds no
   plugin list, install dialog, approval dialog, market page or rollback dialog.
2. **No Kotlin code re-implements plugin install, approval or rollback.** In scope, the only
   Kotlin writes into the DSH home are (a) copy-on-first-run of the official `profiles/web`
   template and (b) the per-generation MCA overlay at `<DSH_HOME>/cordis.patch.yml`. No code
   writes `package.json`, `pnpm-lock.yaml`, `pnpm-workspace.yaml`, `allowBuilds`, version
   exemptions or the profile-level `cordis.patch.yml`.
3. **The report's recommendation is architecturally correct and already largely satisfied, but it
   is not yet executable end-to-end on Android.** The official installer spawns `pnpm`. The pinned
   payload ships `bin/pnpm` as a `#!/system/bin/sh` wrapper inside writable app storage, while MCA
   stages the runtime at `targetSdk 36` and can only exec ELF files from `nativeLibraryDir`.
   Install/remove therefore have a platform prerequisite gap; this is a reason to add a narrow
   official-compatible execution seam, **not** to write a Kotlin plugin installer.
4. **All runtime plugin behaviour is `NOT_RUN`:** page rendering, list, install, build approval,
   version exemption, cancel, remove, rollback and recovery. Static source/config conclusions below
   must not be promoted to device or release evidence.

---

## 1. Baseline and method

| Item | Value |
| --- | --- |
| Repository | `E:\MAC` |
| Branch / HEAD | `codex/enterprise-refactor` / `7475fd640e21154dd9543bcae2c83ab112e30361` |
| Worktree | Large pre-existing dirty set (app/chat/engine/DSH/agent and root build files); this audit changed none of it |
| Submodules | `third_party/llama.cpp`, `stable-diffusion.cpp`, `tokenizers-cpp`; `tokenizers-cpp` has a pre-existing internal diff |
| Payload pin | DSH `0.2.1-rc.2`, official commit `687f282e8625c6b8d11ff58211a90524be2e4317`, reference APK `v1.20.1`, payload sha256 `975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab` |
| Android target | `compileSdk 36`, `targetSdk 36`, `minSdk 26` ([libs.versions.toml](../../../gradle/libs.versions.toml)) |
| Host process | `:dsh`, `exported=false`, `foregroundServiceType=specialUse`, no `isolatedProcess` ([manifest](../../../core/dsh-runtime/src/main/AndroidManifest.xml)) |

Method: read the four named areas, the runtime/config seams they call, and the **pinned local
payload** (`core/dsh-runtime/src/main/assets/dsh/payload.zip`) plus its generated
`third_party/dsh/FILES.tsv`. The payload is a local staged artifact (git-ignored), not committed
source. No external repository was re-fetched for this audit; the report's external links were not
re-verified.

Snapshot caveat: other workstreams were editing this dirty worktree during the audit (runtime
startup/readiness files, DSH backend/README files, and sibling proposal documents). The in-scope
plugin greps, gateway routes and the payload pin were re-checked at the end of the audit and were
unchanged; the structural conclusions here still depend on the dirty worktree, so re-run the checks
before implementing. This document is the only file this audit created.

---

## 2. What MCA currently exposes

### 2.1 Entry and UI

| Surface | Current fact | Plugin relevance |
| --- | --- | --- |
| DSH tab | `AppTab.DSH_WORKSPACE`, rendered by `DshWorkspaceRoute` ([MainActivity.kt](../../../app/src/main/java/com/muyuchat/mca/MainActivity.kt), [MainChatScreenActions.kt](../../../app/src/main/java/com/muyuchat/mca/MainChatScreenActions.kt)) | Entry to the official UI; no plugin-specific action |
| Page shell | Starting / failure / exited / Web states; official page otherwise unchanged ([DshWorkspaceScreen.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/ui/DshWorkspaceScreen.kt)) | No MCA-rendered plugin UI |
| Runtime client | Only `snapshot`, `ensureStarted`, `restart`, `stop`, `webEntry` ([DshRuntimeContract.kt](../../../core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/DshRuntimeContract.kt)) | No plugin, install, approval, rollback or maintenance method |
| Module boundary | `feature:dsh-workspace` depends only on `core:dsh-contract` and `core:designsystem`; no runtime implementation, app, engine or gateway dependency ([build.gradle.kts](../../../feature/dsh-workspace/build.gradle.kts)) | UI cannot execute Node/pnpm or own plugin transactions |

The module README is explicit: it "does not implement DSH settings, history, approvals, model
picker, **plugin manager** or session rendering"; DSH sessions/tools/plugins are "out of scope by
design" ([README.md](../../../feature/dsh-workspace/README.md)). The screen and ViewModel repeat
that the official page owns plugin state ([DshWorkspaceScreen.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/ui/DshWorkspaceScreen.kt),
[DshWorkspaceViewModel.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceViewModel.kt)).

**Static `PASS`**: no native plugin surface exists. **`NOT_RUN`**: whether the official Plugins
page actually renders and is navigable inside the Android WebView.

### 2.2 WebView behaviour relevant to plugin flows

- Exact same-origin fence for `http://127.0.0.1:<current port>`; all other top-level navigation is
  classified external or blocked ([DshNavigationPolicy.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshNavigationPolicy.kt)).
- `window.open` / `target="_blank"` is reduced to one URL and routed through the same policy; the
  pinned frontend's account/plugin-market links are named as a real case
  ([DshWebChromeClient.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebChromeClient.kt)).
  A same-origin popup navigates the main page; an external `http(s)` link goes to the host hook.
- No `addJavascriptInterface`, no JS bridge, no request interception, third-party cookies off,
  camera/mic/geolocation denied ([README.md](../../../feature/dsh-workspace/README.md)).
- Downloads: `DownloadManager` for `http(s)`, SAF for bounded `data:`; `blob:` refused.

Consequence: plugin-market documentation/registry links can leave the app, but the market **page
and its install flow** are ordinary same-origin official UI; MCA does not intercept or replay them.
This is the correct boundary and should stay.

### 2.3 Host, profile and data layout

- One authoritative owner (`DshRuntimeOwner`) in the `:dsh` process; one Node Web profile per
  generation; start/stop/restart serialized; readiness is HTTP/auth/probe based, not stdout-only
  ([DshRuntimeOwner.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt)).
- `DSH_HOME` is `files/dsh/home`; `profiles/web` is seeded from the payload template; `payload/`,
  `workspace/`, `tmp/` are siblings; payload updates replace `payload/` only, so plugin state under
  `home/profiles/web` survives payload installs ([DshRuntimeConfig.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshRuntimeConfig.kt),
  [DshPayloadInstaller.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadInstaller.kt)).
- Launch is `node --expose-internals <kernelEntry> web --no-open --host 127.0.0.1 --port 0`; no
  `plugin` subcommand is ever launched ([DshNodeCommand.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt)).
- Environment: `HOME`/`DSH_HOME`/`DSH_CONFIG_DIR`/`DSH_WORKSPACE`/`TMPDIR` inside `files/dsh`,
  `PATH` = payload `bin` + `runtime/bin` + system bins, `LD_LIBRARY_PATH` = payload runtime libs.

### 2.4 MCA-owned writes into the DSH home

There are exactly two write families, both in `DshGatewayConfigSeeder`:

| Write | Rule | Why it matters for plugins |
| --- | --- | --- |
| Official `profiles/web` template | Copies `cordis.yml`, `package.json`, `pnpm-workspace.yaml` **only when missing**; copies `settings.yaml` only when missing ([DshGatewayConfigSeeder.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt)) | Official plugin installs edit `package.json`/`pnpm-workspace.yaml`; MCA never overwrites existing files, so installed state survives |
| `<DSH_HOME>/cordis.patch.yml` | Rewritten atomically **every host generation** with only `permission` presets (`approval: ask`) and the `llm-pi-ai` MCA provider/model list | The official loader applies this as the **home patch layer after** the profile user patch (`readProfilePatches` app-boot line 1131). It does not erase the profile patch where the official manager persists enable/disable. It *would* override a same-id row, but it only uses `permission` and `llm-pi-ai` |

Ownership caveat: the home patch is MCA-owned and regenerated; any manual edits to
`<DSH_HOME>/cordis.patch.yml` are lost on the next start. That is a documented MCA choice, not an
official plugin-state file.

### 2.5 Model gateway

`api:dsh-gateway` is a loopback-only OpenAI-compatible **model** adapter. Its complete HTTP surface
is `GET /healthz`, `GET /v1/models`, `POST /v1/chat/completions`, `POST /v1/mca/calls/cancel`
([README.md](../../../api/dsh-gateway/README.md), [DshGatewayServer.kt](../../../api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewayServer.kt)).
It explicitly executes no DSH tool and owns no plugin state. There is no plugin route to extend or
secure, and none should be added.

### 2.6 Staging

`scripts/dsh` stages the official kernel and mirrors only ELF executables into
`jniLibs/arm64-v8a` (`node`, `rg`, `curl`, `git`, `git-remote-http(s)`, `python3.14`). It pins the
APK, payload, kernel and critical files. It contains **no plugin package, no plugin manifest and no
pnpm executable** ([stage.ps1](../../../scripts/dsh/stage.ps1), [payload-lock.json](../../../scripts/dsh/payload-lock.json)).
The payload does contain `bin/pnpm`, `pnpm/bin/pnpm.cjs`, `pnpm/dist/pnpm.cjs` and
`pnpm/dist/worker.js` (`FILES.tsv` lines 8, 29815-29819), but `bin/pnpm` is a shell wrapper and is
not mirrored.

### 2.7 Native plugin entries: none

In scope there is no MCA-native plugin page, list, install field, approval prompt,
version-exemption prompt, cancel button, remove button, market, CLI console or recovery action. `feature/dsh-workspace` has
20 main Kotlin files (2,745 lines) and 9 test files (1,004 lines); `app/.../dsh` has 8 files (1,819
lines); `api/dsh-gateway` has 12 main files (2,615 lines). Grep for `plugin` (case-insensitive)
finds **3 comment-only matches** in `feature/dsh-workspace`, **0** in `app/.../dsh`, **0** in
`api/dsh-gateway`, and **1** in `scripts/dsh`.

---

## 3. Does any Kotlin code re-implement install or approval?

**No.** Evidence:

| Check | Result |
| --- | --- |
| `installPlugin` / `PluginInstaller` / `pluginManager` / `approvePlugin` / `PluginMarket` / `npm install` / `pnpm add` / `node_modules` in `app`, `feature`, `core`, `api` (1,930 Kotlin files) | Only DSH kernel-entry strings and tests. No plugin transaction code |
| Writes to profile `package.json`, `pnpm-lock.yaml`, `pnpm-workspace.yaml`, `allowBuilds`, version exemptions or profile `cordis.patch.yml` | None. Only the seeder's copy-if-missing template and home overlay described in §2.4 |
| Plugin routes in `api/dsh-gateway` | None; four model/health routes only |
| `DshRuntimeClient` operations | Five host-lifecycle operations; contract comment: official session/task/approval/plugin state "stay inside DSH and are never mirrored here" |
| Approval store / pending approval table / plugin DB in MCA | None |

**Disambiguation — do not count these as DSH plugin management:**

- `AgentPackageService`, `AgentSkillAdminService`, `AgentMcpAdminService`,
  `AgentModelCatalogAdapter` are adapters for the **Kotlin Agent** (task package export, Agent
  skills, Agent MCP registry, Agent model catalog). They write Agent-owned storage, not
  `$DSH_HOME`, and never call the DSH host.
- `feature/agent-workspace` has a "plugins" drawer row; that is the Kotlin Agent extension surface,
  not the DSH Cordis plugin manager.
- The DSH model backend's prompted tool protocol merely carries official tool calls; it does not
  implement any plugin operation ([McaDshGatewayBackend.kt](../../../app/src/main/java/com/muyuchat/mca/dsh/McaDshGatewayBackend.kt)).

So the report's "avoid building a second installer in Kotlin" target is already met. The audit found
no hidden installer to remove.

---

## 4. What the official surface actually is in the pinned payload

This section establishes what "use the official pluginManager" means concretely. All facts are
static reads of the pinned payload; none was executed.

### 4.1 Three official invocation surfaces

| # | Surface | Entry | Transaction owner |
| --- | --- | --- | --- |
| O1 | Official Web UI | Sidebar `ui-plugin-manager` page plus Settings plugin inventory/plugins rows (dsh-web-app `cordis.patch.yml` lines 315-324) | In-kernel `pluginManager` service via its typed Remote |
| O2 | Official model-facing tools | `plugin_manager` (`@deepseek-ai/dsh-plugin-manager/tools`) and `plugin_market` (`@deepseek-ai/dsh-plugin-market/tools`), re-enabled by the default `standard` agent preset | Same in-kernel `pluginManager` service |
| O3 | Official CLI | `dsh plugin --profile web <pnpm args>`, e.g. `add <spec>`, `remove <name>`, `why <name>`; plus `allow-version`, `revoke-version`, `version-exemptions` | `runPlugin` → `runPluginCommand` / `runProfilePnpm`, with a profile file lock (120 s wait) |

The web bundle **disables** `tool-plugin-manager` at the bare bundle layer, then the shipped
`standard` preset re-enables it together with `plugin-market`; `agent-preset-registry` declares
`default: standard`. The `plugin-manager` host row itself is enabled when `profileContext` exists,
which the web profile provides. Runtime availability is `NOT_RUN`.

### 4.2 Official host operations

`@deepseek-ai/dsh-plugin-manager` exposes (method names from its bundle):
`listBundles`, `listPlugins`, `inspect`, `registries`, `installBundle`, `waitForInstall`,
`cancelInstall`, `removeBundle`, `setBundleEnabled`, `setPluginEnabled`, `listVersionExemptions`,
`setVersionExemption`.

Notable semantics read from the bundle:

- `installBundle` runs `pnpm add <spec>` in the profile; accepts `approvedBuilds`; returns
  `pendingBuilds` when pnpm reports ignored build scripts; rejects incompatible plugin versions
  unless exempted; reports `application` as `applied | restart-required | cancelled | failed`.
- `installBundle` snapshots only **`package.json` and `pnpm-lock.yaml`** and restores them when the
  run fails, is cancelled, or adds a non-bundle package. Downloaded files may remain.
- `writePluginEnabled` persists enable/disable as an override in the **profile** `cordis.patch.yml`
  (`<DSH_HOME>/profiles/web/cordis.patch.yml`), atomically and comment-preserving.
- `approveBuilds` persists `allowBuilds: true` in the **profile** `pnpm-workspace.yaml`.
- `setVersionExemption` persists an exact package-version/runtime-version exemption (with
  `acceptRisk` for grants).
- `diskState()` reads `package.json`, `cordis.patch.yml`, `pnpm-workspace.yaml` and the
  compatibility file to detect external changes; it does not write the patch file itself.

### 4.3 Three different "approvals"

The report's single word "审批" covers three unrelated decisions. They must never be presented as
one approval:

| Approval | What it authorizes | Where it persists | MCA today |
| --- | --- | --- | --- |
| Permission/sandbox approval | A tool operation under a preset (`read-only`/`workspace-write`/`danger-full-access`) | Official session/approval state; MCA sets `approval: ask` for all three presets in the home overlay | Configured; runtime `NOT_RUN` |
| Build-script approval | Exact package names whose pnpm install scripts may run (`allowBuilds`) | Profile `pnpm-workspace.yaml` → `allowBuilds` | Not managed by MCA; official UI/tool owns it |
| Version-compatibility exemption | An exact plugin version on an exact DSH runtime version, explicitly accepting crash/data-loss risk | Profile compatibility file via `setVersionExemption` / CLI `allow-version` | Not managed by MCA; official UI/CLI owns it |

"Approved" is therefore not a security boundary: the `:dsh` process is same-UID app code, so an
installed plugin can use Node `fs`/`net`/`process` on anything the app UID can reach. The report
already states this; MCA must not add UI that implies otherwise.

### 4.4 Rollback scope (important precision)

Official rollback is a **failed-operation file restoration**, not a plugin version rollback and not
a data rollback:

- restores `package.json` + `pnpm-lock.yaml` after a failed/cancelled/ambiguous install;
- may re-run `install` to repair `node_modules`, but downloaded files can remain;
- does **not** restore plugin-created data, sessions, workspace files or native side effects;
- does **not** undo `allowBuilds` written before a later failure;
- does **not** provide "downgrade the working plugin to its previous version"; that requires a new
  `add <pkg>@<old>` (or `remove`) operation;
- a plugin that installs successfully but breaks the host boot is outside this restore path.
  Official startup skips incompatible bundles (`skippedBundles`) rather than crashing, but an
  unreadable profile manifest/patch still fails startup; MCA has no `dsh plugin remove` or profile
  reset entry.

### 4.5 Why pnpm is a hard prerequisite

- The official manager and CLI spawn `pnpm` (`pnpmCommand` default `"pnpm"`; CLI
  `runPluginCommand` / `runProfilePnpm`).
- The payload's `bin/pnpm` is `#!/system/bin/sh` and lives under writable `files/dsh/payload/bin`.
  MCA targets SDK 36 and executes only `nativeLibraryDir` ELFs; the project's own staging README
  says the payload `python/npm/pnpm` wrappers cannot be executed from writable app storage at
  `targetSdk 36` and only ELF executables are mirrored.
- `DshNodeCommand` puts `payload/bin` on `PATH` and launches only `bin.js web ...`; no
  `packageManager` launch option is passed. `profileContext.packageManager` is a launcher option,
  not profile YAML, so it cannot be set from `cordis.patch.yml`.
- `pnpm/bin/pnpm.cjs` can be run by Node (`node .../pnpm/bin/pnpm.cjs`), but the official spawn
  needs an executable command; a Kotlin-side `ProcessBuilder` call to `node pnpm.cjs` would be a
  new MCA execution path and is not present today.

**Static conclusion**: install/remove through O1/O2/O3 will fail at the pnpm spawn on the current
staging, unless a pnpm execution seam is added. **`NOT_RUN`**: the exact error (`EACCES`, `ENOENT`,
`127`) on a real device.

---

## 5. Comparison with the report's recommendation

The report recommends:

> reuse the official pluginManager's check, install, approval and rollback; MCA may enhance
> directory and entry; execution and data transactions stay with the official mechanism; avoid
> rebuilding a plugin installer in Kotlin
> ([DSHA_MULTI_PROJECT_REVIEW_20261019.md](DSHA_MULTI_PROJECT_REVIEW_20261019.md) line 127).

| Aspect | Verdict |
| --- | --- |
| Reuse official execution/data transactions | **Aligned.** MCA already does this; O1 is reachable through the existing WebView, O2 is the official agent path, O3 exists in the payload |
| Do not build a Kotlin installer | **Already satisfied.** No installer, approval store, plugin DB or market clone exists |
| "MCA can enhance directory and entry" | **Valid, but entry-only.** A native CTA/deep link may open the official page; it must not carry the install transaction |
| "Install/approval/rollback" as one unit | **Imprecise.** Three different approvals and a narrow rollback (§4.3, §4.4); the plan must name which one it means |
| Implied "it works today" | **Not established.** The pnpm execution gap (§4.5) blocks the official install path; page behaviour is `NOT_RUN` |

The report's other relevant statement — same-UID plugins are trusted app code, not an isolation
boundary — is confirmed by the manifest and must remain in any UI copy.

---

## 6. Where the official CLI/manager should be invoked

Ordered from least to most MCA involvement. The first two need **no new Kotlin call site**.

| Layer | Where | How | Status |
| --- | --- | --- | --- |
| L0 Official UI (preferred) | Inside the existing WebView | User opens the official Plugins page; `pluginManager` Remote runs install/approve/cancel/remove in-kernel | Page configured; actual render/install `NOT_RUN` |
| L1 Official model tool | DSH agent session | Model calls `plugin_manager` / `plugin_market`; the kernel executes them. MCA's gateway only carries `tool_calls` | Bundle/preset configured; prompted-tool reliability on local models `NOT_RUN` |
| L2 Official CLI | `:dsh` process, same `DSH_HOME`/env as the kernel | `node --expose-internals <kernelEntry> plugin --profile web <args>`; run with the host stopped, or accept the official profile file lock. Must be a typed `core:dsh-runtime` maintenance port driven by app composition | Not wired; **requires a pnpm execution seam**; `NOT_RUN` |
| L3 Recovery | `:dsh` process | `dsh plugin --profile web remove <pkg>` / `why`, or the official profile-template/rescue mechanism for a broken profile | Not wired; design only; `NOT_RUN` |

Call-site rules if L2/L3 are ever implemented:

- Put the invocation in `core:dsh-runtime` behind a narrow typed port; drive it from app
  composition. Do not put process execution in `feature:dsh-workspace` (it has no runtime
  dependency) and do not put plugin routes in `api:dsh-gateway` (model-only).
- Reuse the exact generation environment (`DshNodeCommand.environment`), `kernelEntry`, `DSH_HOME`
  and `TMPDIR`. Do not invent a second home or a second Node install.
- Fix the command and spec (exact package@version and source) at the call site; stream bounded,
  redacted output; expose official diagnostics, not a raw shell.
- Do not run an install concurrently with another install; honor the official lock; do not run
  during an active agent turn unless the official operation is designed for it.
- Do not parse or emulate the internal `pluginManager` Remote/typert protocol from Kotlin.

Explicit anti-patterns (none present today; keep it that way):

1. Kotlin downloader/unpacker/pnpm runner that writes `package.json`/`node_modules` directly.
2. A second approval prompt or approval table that claims to authorize official plugin operations.
3. A JS bridge or injected script that clicks the official UI.
4. Plugin routes or plugin state in the model gateway.
5. Auto-install of a plugin on tab open, boot or update without user action.
6. Presenting the official Plugins page as "MCA verified/safe".

---

## 7. Safe minimal integration plan

Each slice is independently verifiable and reversible. No slice authorizes a native installer.

### P0 — Baseline observation only (no code)

- Open the DSH tab on a real device with the staged payload; confirm the official sidebar Plugins
  page and Settings plugin inventory render.
- Record `list` output; attempt one harmless read-only inspect; do **not** install.
- Capture the exact failure if any control is unavailable (host reports itself unavailable, page
  missing, etc.).
- Write set: evidence only. Verification: device screen/log evidence; still `NOT_RUN` until done.
- Rollback: none needed.

### P1 — Decide the pnpm execution seam (prerequisite)

Run a read-only probe under the real `:dsh` environment:

1. `node <payload>/pnpm/bin/pnpm.cjs --version` — proves cjs execution works.
2. `pnpm --version` from `payload/bin` — expects exec denial; records the real code.
3. `dsh plugin --profile web why <an installed package>` — read-only official CLI path.

Then choose exactly one, with a written decision:

- **Option A (packaging):** add a pnpm launcher as a native-library artifact, like `node`/`rg`, so
  the official spawn works unchanged. No MCA plugin logic; only staging + `nativeLibraryDir` link.
- **Option B (launch option):** start the kernel through a minimal MCA-owned boot shim that calls
  the official `runCli({ packageManager: { command: node, args: [pnpm.cjs] } })`. Still official
  `plugin-manager` code; requires a new boot path and must be proven not to change web startup.
- **Option C (defer):** keep install unsupported on Android V1 and say so in the UI; do not expose
  a non-working install control.
- No option adds a Kotlin installer, approval store or direct profile writes.
- Verification: a pinned pure-JS test plugin installs, lists, disables/enables and removes with
  official diagnostics; failed install restores `package.json`/`pnpm-lock.yaml`; cancel path
  settles. All `NOT_RUN` until executed.
- Rollback: revert the staging/launch change; DSH home is untouched by the seam itself.

### P2 — Optional entry enhancement (only after P0/P1)

- If a native affordance is wanted, make it a pure navigation/entry action ("open official
  Plugins") or a documented instruction. Do not build a form, directory or approval dialog.
- A deep link may be used only if the official frontend route is confirmed; do not guess a route.
- Write set: `feature/dsh-workspace` navigation or none; frozen visual language applies.
- Verification: entry opens the official page; no transaction is duplicated; back/navigation
  behaviour unchanged. `NOT_RUN`.

### P3 — Optional recovery entry (gated by observed risk)

- Only if P0/P1 show a real soft-brick/removal need, add a host-side maintenance action backed by
  official `dsh plugin ... remove/why` or the official profile-template/rescue mechanism.
- Must require explicit user confirmation, run while the host is stopped, and never replay an
  operation whose result is unknown.
- Do not add a general terminal or arbitrary command runner.
- Verification: removal of a test plugin restores a bootable host; corrupt-profile recovery is
  demonstrated on a disposable profile. `NOT_RUN`.

### Non-goals for this integration

- Native plugin browser/market, install wizard, approval wizard, version picker, rollback UI.
- Plugin package hosting, signing, mirroring or auto-update.
- Plugin data backup/restore or cross-device migration.
- Isolation claims; same-UID trusted code remains the contract.

---

## 8. Top risks

### R1 — Official install cannot complete because pnpm is not executable (feasibility)

The UI/CLI/host all converge on spawning `pnpm`; MCA's current staging cannot exec the payload
shell wrapper at `targetSdk 36`. Result: the official Plugins page may be visible but install,
remove and build-approval retries fail. This is the first gate; no amount of UI work fixes it.
Mitigation: P1 seam (A/B) or explicit deferral (C); never a Kotlin installer.

### R2 — No MCA recovery or true rollback boundary (operability)

Official rollback restores `package.json`/`pnpm-lock.yaml` only; it does not restore plugin data or
a previously working plugin version, and MCA exposes no `dsh plugin remove` / profile reset /
rescue entry. A plugin that installs successfully but breaks the host boot, or a profile manifest
damaged outside the official path, can leave the DSH tab unusable until data is reset outside the
app. Mitigation: P3 gated recovery, and keep the official remove path reachable; document
that rollback ≠ data rollback.

### R3 — Approval/trust overclaim and a second authority (security/truthfulness)

"Approval" is three different decisions (permission, build scripts, version exemption). MCA
configures only the permission preset; build/version approvals live in official profile files, and
the default `standard` preset re-enables model-facing plugin tools. Any native approve button would
create a second, non-authoritative approval surface. Same-UID plugins are trusted app code, so no
approval UI can claim isolation. Mitigation: keep approvals in the official UI, label them
precisely, and keep the existing same-UID limitation in copy.

**Watch items (not top 3):** prompted-tool reliability for O2 on local models; DSH version upgrades
invalidating installed plugins; registry/network availability; concurrent install vs. active turn;
loss of manual edits to MCA's home `cordis.patch.yml`; supply-chain pinning of third-party plugin
specs; plugin package size/ABI and native addons.

---

## 9. Verification status

| Check | Result | Scope / reason |
| --- | --- | --- |
| No native plugin installer/approval implementation in scope | `PASS` (static) | Source grep, route table, contract, module deps |
| No profile plugin-state writes from Kotlin | `PASS` (static) | Only seeder template + home overlay |
| Official plugin packages/pages/CLI present in pinned payload | `PASS` (static) | `FILES.tsv` + payload entries |
| MCA home overlay does not overwrite official profile patch | `PASS` (static) | app-boot home-patch layer; different paths |
| pnpm executable under targetSdk 36 | `NOT_RUN` | Static evidence predicts exec denial; device probe required |
| Official Plugins page renders/navigates | `NOT_RUN` | No device/UI run |
| Install / build approval / version exemption / cancel | `NOT_RUN` | No device/network run |
| Remove / failed-install restore | `NOT_RUN` | No device run |
| Recovery / rescue | `NOT_RUN` | No entry exists; design only |
| Gradle build / unit tests / APK / instrumentation | `NOT_RUN` | Explicitly out of scope ("no code/build/device") |

No result above is a device, release or performance claim. Static `PASS` means "the named source
or pinned artifact was read and says this", not "the behaviour ran".

---

## 10. Open decisions

1. Is Android V1 expected to install third-party plugins at all? If not, choose P1-C and label the
   official page honestly instead of adding a seam.
2. If yes, choose P1-A vs P1-B; both are packaging/launch changes with different blast radius.
3. Is an MCA entry/CTA required, or is the official sidebar page sufficient? If required, confirm
   the official route before coding; do not invent one.
4. Is a recovery/maintenance entry in scope? If not, document the out-of-app recovery procedure.
5. Which plugin(s) are first-party requirements? They need pinned spec, license, ABI/native-addon
   review and a compatibility statement before any install UX.

---

## Appendix A — Evidence index

MCA source:

- [feature/dsh-workspace/README.md](../../../feature/dsh-workspace/README.md) — no plugin manager; out of scope; no reimplementation.
- [DshWorkspaceScreen.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/ui/DshWorkspaceScreen.kt) — official plugin pages never re-rendered.
- [DshWorkspaceViewModel.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceViewModel.kt) — official page owns plugin state.
- [DshWebChromeClient.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebChromeClient.kt) — popup/external/plugin-market link policy.
- [DshNavigationPolicy.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshNavigationPolicy.kt) — origin fence.
- [DshRuntimeContract.kt](../../../core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/DshRuntimeContract.kt) — five host operations; plugin state never mirrored.
- [DshGatewayConfigSeeder.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt) — template copy + home overlay.
- [DshNodeCommand.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt) — web-only launch and PATH.
- [DshRuntimeConfig.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshRuntimeConfig.kt) — home/payload layout.
- [core/dsh-runtime AndroidManifest.xml](../../../core/dsh-runtime/src/main/AndroidManifest.xml) — `:dsh`, same UID.
- [api/dsh-gateway/README.md](../../../api/dsh-gateway/README.md), [DshGatewayServer.kt](../../../api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewayServer.kt) — model-only routes.
- [scripts/dsh/README.md](../../../scripts/dsh/README.md), [stage.ps1](../../../scripts/dsh/stage.ps1), [payload-lock.json](../../../scripts/dsh/payload-lock.json) — no pnpm/plugin packaging.

Pinned payload (local staged artifact, not committed):

- `payload.zip!dshroot/lib/node_modules/@deepseek-ai/dsh/package.json` — "dsh CLI: profile launch, plugin management, and configuration inspection"; depends on `dsh-plugin-manager`.
- `payload.zip!dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js` — `dsh plugin --profile <name> <pnpm args>`; `allow-version`/`revoke-version`/`version-exemptions`.
- `payload.zip!dshroot/lib/node_modules/@deepseek-ai/dsh/lib/plugin-BiveYA7h.js` — `runPluginCommand`, file lock (120 s), CLI execution, pnpm-missing note.
- `payload.zip!.../@deepseek-ai/dsh-plugin-manager/lib/index.js` — operations, install/approval/restore semantics.
- `payload.zip!.../@deepseek-ai/dsh-plugin-manager/lib/types/patch.js` — enable/disable persists in profile `cordis.patch.yml`.
- `payload.zip!.../@deepseek-ai/dsh-plugin-manager/lib/types/build-approval.js` — `allowBuilds` in profile `pnpm-workspace.yaml`.
- `payload.zip!.../@deepseek-ai/dsh-app-boot/lib/index.js` — profile dir/patch path (lines 509, 546-549, 1041-1042); home patch layer (lines 1127-1136).
- `payload.zip!.../@deepseek-ai/dsh-base/cordis.patch.yml` — `plugin-manager` host row; `tool-plugin-manager` disabled at base.
- `payload.zip!.../@deepseek-ai/dsh-web-app/cordis.patch.yml` — `ui-plugin-manager` row; web-layer tool disable; default preset `standard`.
- `payload.zip!.../@deepseek-ai/dsh-web-app/presets/standard.patch.yml` — re-enables `tool-plugin-manager` and adds `tool-plugin-market`.
- `payload.zip!dshhome/profiles/web/package.json` — bundles `dsh-base`, `dsh-web-app`.
- `payload.zip!bin/pnpm`, `pnpm/bin/pnpm.cjs`, `pnpm/dist/pnpm.cjs` — wrapper + JS entry.
- `third_party/dsh/FILES.tsv` — payload inventory; `bin/pnpm` line 8; plugin packages lines 13490, 13839, etc.

Report:

- [DSHA_MULTI_PROJECT_REVIEW_20261019.md](DSHA_MULTI_PROJECT_REVIEW_20261019.md) line 114 (official plugin CLI), line 127 (reuse official pluginManager), line 205 (same-UID is not isolation).

## Appendix B — Read-only commands used

```powershell
git rev-parse --abbrev-ref HEAD; git rev-parse HEAD; git status --porcelain=v1; git submodule status
Get-ChildItem ... (source inventory, excluding build/)
Select-String / rg for plugin, pnpm, approval, cordis.patch, allowBuilds, node_modules
System.IO.Compression.ZipFile.OpenRead(payload.zip)  # read-only entry inspection; no extraction to disk
```

No `gradlew`, test, APK, install, device, emulator or network command was run.

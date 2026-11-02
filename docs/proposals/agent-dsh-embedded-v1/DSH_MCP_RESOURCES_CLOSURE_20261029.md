# DSH MCP resources closure (2026-10-29)

Status: **static payload evidence + JVM declaration validation**. No DSH host, MCP server, device,
Gradle build or APK was run for this note. Runtime MCP actions are `NOT_RUN`.

Baseline: branch `codex/enterprise-refactor`, dirty shared worktree (pre-existing changes were not
touched). Pinned payload `dsh-0.2.1-rc.2+android-ref-c324272bff00`, payload sha256
`975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab`, official DSH commit
`687f282e8625c6b8d11ff58211a90524be2e4317`
(`scripts/dsh/payload-lock.json`, `core/dsh-runtime/src/main/assets/dsh/MANIFEST.json`).

All payload citations below are entries of
`core/dsh-runtime/src/main/assets/dsh/payload.zip` (mirrored in `third_party/dsh/FILES.tsv`).

---

## 0. Verdict

| MCP surface | Pinned-payload verdict | Evidence class |
| --- | --- | --- |
| `tools/list` + `tools/call` | **SERVED** by `@deepseek-ai/dsh-mcp-client` 0.2.1-rc.2 | measured: packaged JS |
| `resources/list` | **SERVED**, exposed as the model tool `list_mcp_resources` | measured: packaged JS + bundle row |
| `resources/templates/list` | **SERVED**, exposed as `list_mcp_resource_templates` | measured: packaged JS |
| `resources/read` | **SERVED**, exposed as `read_mcp_resource` | measured: packaged JS |
| `prompts/list`, `prompts/get` | **BLOCKED**: no DSH plugin performs them | measured: package inventory + plugin source |
| MCP session expiry / reconnect | implemented by the official client; behaviour **NOT_RUN** | measured code path, unverified runtime |
| MCP resource cancel | DSH forwards the execution signal; behaviour **NOT_RUN** | measured code path, unverified runtime |
| stdio env isolation | **NOT an allowlist**: scrubbed host env + explicit map; runtime **NOT_RUN** | measured code + official docs in JS |
| Model-facing tool count | **bounded at 128 per request** by the MCA gateway, not by DSH | measured MCA code + JVM test |

MCA must not implement an MCP resource proxy. DSH owns the protocol, the transports, pagination and
the model-facing tool names. MCA owns only the bounded declaration that seeds official
`dsh-mcp-client` rows.

---

## 1. What MCA already had

- Declaration store / import path:
  `app/src/main/java/com/muyuchat/mca/composition/DshHostAssembly.kt:172-173` constructs
  `DshMcpDeclarationStore(File(filesDir, "dsh/home"))`;
  `DshMaintenanceConsoleAdapter.kt:234` routes `ImportMcpDeclaration` and `:717-770` calls
  `DshMcpDeclarationStore.importDocument`.
- One schema, one writer, one reader:
  `core/dsh-runtime/.../config/DshMcpServerConfig.kt` (reader),
  `DshMcpDeclarationDocument.kt` (writer), `DshMcpDeclarationStore.kt` (create/update/remove/import).
- Official row composition: `DshMcpOverlayRows.kt` writes one `@deepseek-ai/dsh-mcp-client` row per
  accepted server; `DshGatewayConfigSeeder.kt:242-255,357-392` merges those rows into
  `<DSH_HOME>/cordis.patch.yml`.
- No Kotlin MCP protocol exists: `api/dsh-gateway` is a model-only OpenAI-compatible adapter
  (`DshGatewayServer.kt` routes: `/healthz`, `/v1/models`, `/v1/chat/completions`,
  `/v1/mca/calls/cancel`). There is no `resources/*` or `prompts/*` HTTP route to add or secure.

The pinned payload's own plugin layers never name `dsh-mcp-client` as an active row (search over
all payload `.js/.mjs/.cjs/.json/.yml` outside its package finds only imports/package metadata), so
MCA's overlay is the only activation path. That is why the declaration schema is the correct place
to make the support surface honest.

---

## 2. Packaged-payload evidence

### 2.1 Tools — SERVED

`dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-mcp-client/lib/index.js`:

- `:129` — `syncTools` calls `client.listTools(..., { cacheMode: "refresh" })`; it registers each
  tool on `ctx.tools`.
- `:68,96-102` — public name is `mcp__<serverName>__<rawName>`, normalized to the 64-character
  DeepSeek function-name contract with a 12-hex SHA-256 suffix on lossy normalization.
- `:140-147` — `tools/call` forwards the model arguments, `execution.signal` and the per-call
  timeout.

### 2.2 Resources — SERVED, as official model tools

`dsh-mcp-resources` is present at version 0.2.1-rc.2 and **loaded by the shipped base bundle**:

- `.../@deepseek-ai/dsh-base/cordis.patch.yml:492-493` inserts
  `- id: mcp-resources` / `name: '@deepseek-ai/dsh-mcp-resources'` (no `disabled:`).
- `.../@deepseek-ai/dsh-mcp-client/package.json:37,63-66` declares `dsh-mcp-resources` as an
  **optional peer dependency**, so the client works with or without the resource service.
- `.../@deepseek-ai/dsh-mcp-client/lib/index.js:731-734` registers each connected server's
  `connection.resources` through `ctx.inject(["mcpResources"], ...)`.
- `.../@deepseek-ai/dsh-mcp-client/lib/index.js:685-699` maps the provider's closed operation
  union onto the SDK:
  `resources/list` (:693), `resources/templates/list` (:694), `resources/read` (:695), passing
  `exec.signal` and `toolCallTimeoutMs` (:688-691).
- `.../@deepseek-ai/dsh-mcp-resources/lib/index.js:53-92` registers exactly three model tools:
  `list_mcp_resources` (:56, method `resources/list` at :61),
  `list_mcp_resource_templates` (:66, `resources/templates/list` at :71), and
  `read_mcp_resource` (:76, `resources/read` at :88).

Consequences:

- There is no need for an MCA resource proxy, resource table or URI allowlist; adding one would
  duplicate DSH's protocol ownership.
- MCA already recognizes these exact tool names in its prompted-tool catalog for models without
  native tool calling: `app/src/main/java/com/muyuchat/mca/dsh/DshPromptedToolProtocol.kt:278-292`
  reserves the MCP slot for `list_mcp_resources`, `list_mcp_resource_templates` and
  `read_mcp_resource`. That is a prompt projection of official tools, not a protocol
  implementation.
- Resource tools become visible to the model only while at least one MCP server is registered in
  the same scope (`McpResourceRuntime.registerTools`), and they are **shared tools taking a
  `server` argument** — they are not per-server `mcp__*` tools.
- Binary `blob` payloads are replaced with a text descriptor for the model while the canonical
  value stays available to programmatic callers (`dsh-mcp-resources/lib/index.js:16-24`).
- A resource read while the server is down throws `mcp-client(<server>): server is disconnected`
  (`dsh-mcp-client/lib/index.js:687`); it is not silently retried.

### 2.3 Prompts — BLOCKED

Measured over every `@deepseek-ai/**` `.js/.mjs/.cjs/.json/.yml` entry in the payload:

- no `dsh-mcp-prompts` package exists (`FILES.tsv` has no entry; zip inventory empty);
- no DSH plugin calls `prompts/list`, `prompts/get`, `listPrompts` or `getPrompt`. The only
  matches are the generic `@modelcontextprotocol/client`/`core` SDK (which can encode the method),
  an unrelated Anthropic SDK helper, and OpenTelemetry attribute names;
- `dsh-mcp-client` only injects `tools` (`lib/index.js:761-763`) and registers tools, server
  instructions and resource providers (`:127-161`, `:731-743`).

**Blocking condition**: DSH 0.2.1-rc.2 has no prompt discovery UI, no prompt-to-message injection
path and no prompt tool. MCA cannot serve prompts without either a payload upgrade that adds a
prompt plugin or MCA duplicating the MCP protocol — the latter is explicitly out of bounds (and
would still have no DSH consumer). A declaration that names `prompts` is therefore refused with the
typed `CAPABILITY_UNSUPPORTED` code; no UI may claim prompt support.

---

## 3. Tool-catalog limits

Measured:

- DSH's tool registry has no per-server or total tool cap in `dsh-mcp-client` / `dsh-tools`; a
  server can publish an arbitrary `tools/list` result. The only naming bound is the 64-character
  public name (`dsh-mcp-client/lib/index.js:68,96-102`).
- The MCA model gateway refuses a chat request carrying more than
  `DSH_MAX_MODEL_VISIBLE_TOOLS = 128` tools
  (`core/dsh-contract/.../DshConsoleMaintenanceContract.kt:297`,
  `api/dsh-gateway/.../DshGatewayConfig.kt:98`,
  `api/dsh-gateway/.../DshOpenAiRequest.kt:291-295`, error code `too_many_tools`), and more than 32
  tool calls per response (`DshGatewayConfig.kt:99`).
- The declaration reader bounds the inputs it can see: at most 8 servers
  (`DshMcpServerConfig.kt:175`), 32 args, 32 env entries, 16 headers, 64 KiB document.

Inference (not device-measured): because DSH sends its complete visible catalog with every
chat-completions request, built-in DSH tools and all `mcp__*` tools share the 128-tool budget. A
large MCP catalog can therefore make every turn fail with `too_many_tools`; the declaration cannot
pre-count a server's tools because that requires a real `tools/list` handshake. The honest bound is
enforced at the gateway, not guessed at import time.

JVM proof: `DshToolCatalogBudgetTest` (`theEnforcedBudgetIsTheContractConstant`,
`exactlyTheBudgetIsAcceptedAndOneMoreIsRefused`, `theBudgetIsConfigurableOnlyWithinTheHardCeiling`).

---

## 4. Session expiry, reconnect, cancel

Measured code paths in `dsh-mcp-client/lib/index.js`:

- reconnect is **enabled by default** with 500 ms initial delay, 30 s maximum delay and 10 attempts
  (`:434-439`); each new generation creates a fresh transport + client (`:595-675`), so an expired
  Streamable-HTTP session is not resumed — a new session is negotiated;
- a connection that stays up for at least `maxDelayMs` closes the outage and resets the attempt
  budget (`:564`); exhaustion unregisters that server's tools and stops retrying (`:567-575`);
- transport close (`onclose`) is the disconnect signal (`:617-621`); a failed generation that
  cannot confirm transport closure stops reconnecting to avoid overlapping server processes
  (`:534-543`);
- resource reads pass `exec.signal` and the per-call timeout (default 60 s,
  `:765,688-691,780-800`); `resources.request` refuses while disconnected (`:687`);
- tool calls pass the same signal and timeout (`:140-147`).

**NOT_RUN / PENDING**: no runtime evidence exists for an actual HTTP `404` session expiry, a
backoff sequence, tool re-registration after reconnect, cancellation of a long `resources/read`,
or the 60 s timeout. These are code-path facts only.

---

## 5. stdio environment isolation

Measured:

- `dsh-mcp-client/lib/index.js:26-31` builds the child env as
  `{ ...scrubbedParentEnv(), ...config.env }`; the declaration's explicit `env` is merged **last**.
- `@deepseek-ai/dsh-subprocess/lib/index.js:32` defines
  `SENSITIVE_ENV_PATTERN = /KEY|PASSWORD|SECRET|TOKEN/i`; `:50-53` (`scrubbedParentEnv`) drops every
  matching name and every `DSH_*` name (case-insensitive) from `process.env`, then adds proxy
  variables.
- Therefore the pinned payload provides a **name scrub plus explicit overlay, not an allowlist**.
  `PATH`, `HOME`, locale and proxy values are inherited; an explicit declaration can deliberately
  forward a credential-shaped value or re-introduce `DSH_*`.

MCA boundary: the declaration reader now refuses `DSH_*` env keys with `ENV_KEY_RESERVED`
(`DshMcpServerConfig.kt:410-413`) because the explicit map is the only way a reserved harness name
could re-enter the child. It deliberately still allows an explicitly declared credential such as
`GITHUB_TOKEN`: the official client documents that a deliberately forwarded credential survives,
and refusing it would break legitimate servers. `DshMcpPinnedPayloadSurface.stdioEnvironment`
(`DshMcpPayloadSurface.kt:89-95`) records `explicitAllowlist = false` so no surface can claim
otherwise.

**NOT_RUN**: the actual child environment on device (which names survive, whether `DSH_HOME` is
absent) is unverified.

---

## 6. MCA changes in this slice

| File | Change |
| --- | --- |
| `core/dsh-runtime/.../config/DshMcpPayloadSurface.kt` | New typed capability matrix (`TOOLS`/`RESOURCES` served, `PROMPTS` blocked), official resource tool names, stdio env contract with `explicitAllowlist = false`. |
| `core/dsh-runtime/.../config/DshMcpServerConfig.kt` | Optional `capabilities` array (v2) parsed per entry; `CAPABILITY_INVALID`, `CAPABILITY_UNSUPPORTED`, `ENV_KEY_RESERVED`; `DSH_*` env keys refused; 8-server bound retained. |
| `core/dsh-runtime/.../config/DshMcpDeclarationDocument.kt` | Writes `capabilities`; capability-free documents stay schema v1, capability documents are v2. |
| `core/dsh-runtime/.../config/DshMcpOverlayRows.kt` | Documents that `capabilities` is declaration-only and is never forwarded to the official row. |
| `core/dsh-contract/.../console/DshConsoleMaintenanceContract.kt:297` | `DSH_MAX_MODEL_VISIBLE_TOOLS = 128`, the shared built-in + MCP catalog budget. |
| `api/dsh-gateway/.../DshGatewayConfig.kt:98` | Default tool budget now references the contract constant (no drift). |
| `core/dsh-runtime/src/test/.../DshMcpDeclarationSurfaceTest.kt` | Six JVM tests: resources accepted/preserved but not forwarded; prompts refused; v1+capabilities refused; capability array bounded/closed; reserved env refused with explicit credential allowed; matrix honest. |
| `api/dsh-gateway/src/test/.../DshToolCatalogBudgetTest.kt` | Three JVM tests: budget equals the contract constant; 128 accepted/129 refused; hard ceiling 512. |

No Kotlin resource proxy, no prompt shim, no second MCP client, no change to `DshMcpOverlayRows`
output for capability-free declarations.

Format compatibility: a declaration without `capabilities` is still written as schema version 1
(byte-compatible with existing files and older strict readers). Only a document that declares a
capability is written as version 2; an older build refuses it with `SCHEMA_VERSION_UNSUPPORTED`
instead of misreading the field, and the file itself is left untouched.

---

## 7. What a device test must prove (all currently NOT_RUN)

1. **Tools + resources together.** Start a fixture MCP server (stdio and Streamable HTTP) that
   advertises tools, resources and resource templates. In a real DSH session confirm:
   `mcp__<server>__<tool>` entries exist; `list_mcp_resources`, `list_mcp_resource_templates` and
   `read_mcp_resource` are callable; a text resource round-trips; a `blob` resource is described
   to the model without dumping base64.
2. **Pagination.** Return a cursor from `resources/list` and prove the model tool follows it.
3. **Prompts absence.** The same server advertises `prompts`; confirm DSH exposes no prompt tool and
   that MCA's `prompts` declaration is refused before the host starts.
4. **Session expiry.** Restart an HTTP MCP server (or force a 404 for the session id) and verify:
   the next resource read fails with the client's disconnected/session error, reconnect begins,
   tools re-register exactly once, and no stale generation serves a call.
5. **Reconnect exhaustion.** Kill the server and observe 10 attempts with the documented backoff,
   then tool unregistration and a stable "disconnected" state (no fake connected UI).
6. **Cancel.** Start a deliberately slow `read_mcp_resource`, cancel from the DSH UI, and verify the
   server observes cancellation, the call settles, and no late result reaches model history.
7. **Environment.** A fixture stdio server prints `Object.keys(process.env)`; set a parent
   `FOO_TOKEN` and `DSH_HOME` and confirm both are absent, while a declared `GITHUB_TOKEN` is
   present. Separately confirm MCA refuses a declaration containing `DSH_*`.
8. **Tool budget.** A server with 129+ tools must produce the gateway's `too_many_tools` 400 and a
   visible DSH error, not a silent truncation.

Until item 1 and 4-6 pass on the representative ARM64 device, resources/reconnect/cancel remain
`NOT_RUN`, and prompts remain `BLOCKED` at the payload level.

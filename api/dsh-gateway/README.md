# `:api:dsh-gateway` - internal MCA model gateway for the official DSH

Status: implemented R02/R03 gateway, verified with JVM unit tests and real loopback-socket
integration tests (fake backend). Not yet verified against a real MCA model adapter, a real
cloud model or an Android device -- see [NOT_RUN](#not_run).

This module is the internal, loopback-only OpenAI-compatible model endpoint that the official
DeepSeek Harness (DSH) `openai-completions` provider calls. It is a **narrow protocol adapter**:

- it owns HTTP/SSE, OpenAI field fidelity, call identity, cancellation and resource bounds;
- it does **not** execute DSH tools, read or store MCA cloud keys, touch Room/chat history, or
  rewrite the DSH prompt;
- model admission, chat templates, leases, native stop evidence, local/cloud selection and the
  model catalog belong to the app-owned `DshGatewayBackend` implementation.

## Module dependencies and ownership

- Gradle: Android library `:api:dsh-gateway` (namespace `com.muyuchat.api.dsh.gateway`).
- Depends on `:core:dsh-contract` (frozen, read-only here) and `kotlinx-coroutines`. No Android
  UI or engine dependency. Unit tests add JUnit, `org.json` and `kotlinx-coroutines-test`.
- This module is scoped to `api/dsh-gateway/**`; wiring in `app/**`, `settings.gradle.kts`,
  `core/**`, `api/local/**` and `feature/**` belongs to the Lead/integrator.
- The public Local API server is not reused or mutated.

## App/Lead wiring

```kotlin
// app composition / DSH runtime owner, one gateway per DSH host generation
val gateway = DshGatewayServer(
    backend = mcaDshBackend,          // app-owned DshGatewayBackend implementation
    runtimeEpoch = hostEpoch,         // the DSH runtime epoch for this generation
    limits = DshGatewayLimits.Default,
    logger = DshGatewayLogger { level, message -> /* local logcat; no token */ }
)

// host ensureStarted():
val endpoint = gateway.start()        // suspend; binds 127.0.0.1:0 and returns the token once
// host start failure cleanup / generation teardown:
gateway.stop()                        // suspend; cancels each active call individually

// Contract bridge for DshGatewayEndpointProvider ("start on demand"):
val provider = DshGatewayEndpointProvider {
    if (!gateway.isRunning) gateway.start() else gateway.currentEndpoint()
}
```

Then configure the DSH model route (official settings / `cordis.patch.yml` route) as:

| DSH route field | Value |
| --- | --- |
| `api` | `openai-completions` |
| `baseURL` | `endpoint.baseUrl` (`http://127.0.0.1:<ephemeral>/v1`) |
| credential / `apiKey` | `endpoint.bearerToken` |
| `models` | the ids returned by `GET {baseURL}/models` (MCA catalog is authoritative) |

App composition must add `implementation(project(":api:dsh-gateway"))`.

The endpoint value is the **only** place the run token is exposed. Do not log it, persist it,
put it in an Intent, a WebView URL, a crash report or a screenshot. `DshGatewayServer.toString()`
does not contain it and every gateway log line is token-free by construction.

## Backend port

```kotlin
interface DshGatewayBackend {
    suspend fun catalog(): List<DshGatewayModel>
    fun stream(request: DshModelRequest): Flow<DshModelEvent>
    fun cancel(callId: String) { /* optional out-of-band cancel */ }
}
```

Implementation contract:

- `catalog()` is authoritative and is requested by `/v1/models` and before every chat call. A
  model that is unloaded/unavailable must disappear from the catalog instead of being silently
  served. Unknown model ids are rejected with `404 model_not_found`; a model whose
  `supportsTools == false` rejects tool-bearing requests with `400 tools_not_supported`.
- `stream(request)` is a cold flow that emits any number of `TextDelta`, `ReasoningDelta` and
  `ToolCallDelta` events and ends with **exactly one** `Completed` or `Failed`. The gateway
  enforces that contract:
  - a flow that completes without a terminal event is reported as `backend_stream_closed`
    (an error, never `stop`);
  - a thrown exception becomes `backend_error`;
  - events after the first terminal are ignored, so a duplicated or late terminal never writes a
    second terminal frame (the first real terminal wins);
  - text/reasoning/tool-argument totals are bounded by `DshGatewayLimits.maxResponseChars`, and one
    tool call's complete argument string is additionally bounded by
    `DshGatewayLimits.maxToolArgumentChars`.
- Tool deltas: `index` is stable per call; the **first** delta for an index must carry the function
  `name` and should carry the `id` (the gateway mints a deterministic id if absent). Every later
  non-null `id`/`name` for that index must equal the first value, and an id may not be reused by a
  different index; a mutation or duplicate is `invalid_tool_call`, never a silent overwrite.
  Argument fragments are concatenated verbatim and must form one strict JSON object at the
  terminal; a no-argument tool must send `{}` explicitly. A missing name, missing argument
  fragments or incomplete/oversized arguments is a protocol failure (`invalid_tool_call` /
  `tool_arguments_too_large`), not a fake `tool_calls` success, and the gateway never synthesizes
  executable `{}` from absent fragments.
- Terminal reasons are copied from the producer's `DshModelEvent` and are never inferred from token
  counts, text length or event order. Usage is copied as-is: zero is preserved, a missing side stays
  JSON `null` (or the whole `usage` object is omitted when both sides are unknown), and the gateway
  never estimates, rounds or filters values.
- `cancel(callId)` is invoked **exactly once per call** when the HTTP peer disconnects, the host
  calls the explicit cancel route, or the gateway stops. It must cancel only that call and must
  return promptly: it is a signal to start the precise native stop, not a place to wait for native
  settle evidence. Structural Flow cancellation happens at the same time, so an adapter using
  `collect`/`flowOn`/suspend engine calls still stops; `cancel` is the out-of-band signal for
  native/worker work that needs its own stop call.
- The request already carries the stable call id `dsh:<runtimeEpoch>:<ordinal>`; keep it on the
  backend envelope and map it to the MCA `requestId`/lease for precise cancellation evidence.

## HTTP surface

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/healthz` | `{"status":"ok","service":"mca-dsh-gateway","active_calls":N,"catalog_revision":"<revision>"}` |
| GET | `/v1/models` | OpenAI list (`object:list`, `data[]`) with `id`, `name`, `context_window`, `max_output_tokens`, `supports_tools`, `native_tool_calling`, `tool_protocol_label`, `mca_display_name`, `mca_catalog_revision` |
| POST | `/v1/chat/completions` | OpenAI chat completion; `stream:true` -> SSE, otherwise JSON |
| POST | `/v1/mca/calls/cancel` | Internal explicit cancel (`{"call_id":"dsh:<epoch>:<ordinal>"}`) -> exactly that call; used by the MCA host/tests. DSH itself cancels by closing the HTTP request (AbortSignal). |

All routes require `Authorization: Bearer <run token>`, including `/healthz`. Requests are
accepted only from a loopback peer, with a `Host` header for `127.0.0.1`/`localhost`, without a
browser `Origin`, without chunked request bodies and within the configured header/body limits.
Every response closes the connection. Wrong/absent method -> `405` with `Allow`; unknown route -> `404`; oversized body -> `413`; missing/wrong token -> `401 invalid_api_key`; too many concurrent
model calls -> `429 too_many_concurrent_calls`; connection limit -> `503 server_busy`.

### OpenAI field fidelity

Accepted and preserved: `model`; messages with roles `system`/`developer` (mapped to SYSTEM),
`user`, `assistant` (content and/or `tool_calls` with `id`/`type:"function"`/`function.name`/
`function.arguments` JSON string) and `tool` (`tool_call_id` + text result); `tools` function
definitions (`description`, `parameters` object or JSON string); `tool_choice`
`auto`/`none`/`required` and named-function objects; `temperature` (0-2); `max_tokens` or
`max_completion_tokens`; `stream`; `stream_options.include_usage`.

Deliberately rejected (no silent data loss): image content parts, legacy `functions`/
`function_call`/`function` role, `response_format`, `n > 1`, logprobs, `audio`, `modalities`,
`prediction`, non-boolean `stream`, and the unsupported named mapping limitations listed below.

Known, documented contract limitations:

- **Named `tool_choice`** is validated against `tools` and then mapped to
  `DshToolChoice.REQUIRED`; the frozen contract has only AUTO/NONE/REQUIRED, so the adapter
  cannot carry the function name. The serving adapter should not treat this as proof that a
  specific tool will be called.
- Sampling knobs not present on `DshModelRequest` (`top_p`, `stop`, `seed`, penalties,
  `frequency_penalty`, ...) are accepted and ignored; the official DSH provider does not send them.
  `parallel_tool_calls:false` is rejected because it cannot be enforced.
- The gateway carries text only (the frozen message type has no image field).

### Streaming and terminal semantics

SSE frames are standard `chat.completion.chunk` objects; the stream always ends with
`data: [DONE]`. Deltas:

- text -> `choices[0].delta.content`
- reasoning -> `choices[0].delta.reasoning_content` (the field the official pi-ai
  OpenAI-completions adapter reads first)
- tool call -> `choices[0].delta.tool_calls[{index,id,type:"function",function:{name,arguments}}]`
  with a stable index; `id`/`name` appear on the first fragment, later frames carry only
  `index`-stable argument fragments
- terminal -> one chunk with `choices[0].finish_reason` and empty `delta`
- usage (when requested and reported) -> one final chunk with `choices: []` and `usage`

`finish_reason` is never normalised across states:

| Gateway/backend state | `finish_reason` | HTTP (non-stream) |
| --- | --- | --- |
| `Completed(STOP)` | `stop` | 200 JSON `finish_reason:"stop"` |
| `Completed(TOOL_CALLS)` | `tool_calls` | 200 JSON `finish_reason:"tool_calls"` |
| `Completed(LENGTH)` | `length` | 200 JSON `finish_reason:"length"` |
| `Completed(CANCELLED)` / explicit cancel | `cancelled` | 499 `cancelled` |
| `Failed(...)` / backend exception | `error` + an SSE `{"error":...}` frame | 502/413/503 by code |
| flow ends without terminal (EOF) | `error`, code `backend_stream_closed` | 502 `backend_stream_closed` |
| invalid tool-call protocol | `error`, code `invalid_tool_call(s)` | 502 |

For a streaming `Failed`, the gateway writes the terminal `finish_reason:"error"` chunk, then an
in-band `{"error":{...}}` frame, then `[DONE]`, so a client never sees `stop` for a failure.
Explicit cancellation is only observable while the HTTP connection is still open; a client
disconnect cancels the backend call and writes nothing further.

## Prompt-compat tool-mode labelling rules

Local models without a native tool/template path may still be served in a prompted tool mode by
the app adapter. That mode must be labelled honestly end to end:

1. `DshGatewayModel.nativeToolCalling` **must be `false`** for prompted mode. Never set it to
   `true` for a model whose tools are implemented by prompt instructions and text parsing; native tool calling means the serving template/runtime tokenises and emits the tool protocol.
2. Set `DshGatewayModel.toolProtocolLabel` to a short honest label such as
   `"prompt-compat"` (or `"prompted-json"`). If `supportsTools = true` and
   `nativeToolCalling = false` without a label, the gateway exposes `"prompt-compat"`.
3. `GET /v1/models` appends the label to the user-visible listing name
   (`"<MCA display name> [prompt-compat]"`) while exposing the exact MCA display name in
   `mca_display_name`, `supports_tools` and `native_tool_calling`. The official DSH model picker
   therefore shows the honest mode instead of claiming native tools.
4. The app UI / model catalog must not advertise "native tool calling" for prompted models, and
   must not describe output quality as equivalent to a native-tool model. The gateway forwards the
   OpenAI `tools` schema unchanged; it never invents a native protocol or executes a tool.
5. If a model cannot support tools at all, set `supportsTools = false`; tool-bearing requests are
   then rejected with `tools_not_supported` rather than downgraded or silently ignored.

## Security constraints

- **Loopback only.** The listener binds `127.0.0.1` on an ephemeral port; non-loopback peers are
  refused, foreign `Host` values and browser `Origin` headers are rejected. It must never bind
  `0.0.0.0` or a LAN interface, and it must not be exposed by the public Local API setting.
- **Per-run bearer token.** 32 bytes from `SecureRandom`, compared in constant time, regenerated
  on every `start()`, exposed only in `DshGatewayEndpoint`. The token is never logged, never
  echoed in error bodies, and never persisted.
- **Model catalog from MCA.** `/v1/models` is the MCA catalog (`DshGatewayBackend.catalog()`), not
  a DSH-side list and not a second model configuration UI. Catalog revision is a hash of the
  served list.
- **No cloud raw keys.** The gateway has no field for a cloud provider key and never reads one.
  Cloud/local selection, credentials and transport stay in the app adapter; DSH receives only the
  short-lived gateway token.
- **No MCA data or tool execution.** The gateway has no Room history, chat memory, search or
  prompt-building path and executes no tool. DSH owns its transcript; MCA owns model execution
  and resource admission.
- **Bounded resources.** Header 32 KiB, body 8 MiB, response 8 MiB characters, 16 TCP connections,
  4 concurrent model calls (all overridable through `DshGatewayLimits`, with validation).
- **Cancellation isolation.** Disconnect/explicit cancel/server stop cancel exactly one call id;
  nothing in the gateway performs a global model stop.

## Catalog lifecycle and official-client compatibility

Catalog behavior on one running gateway (verified by `DshGatewayCatalogIntegrationTest`):

- A successfully read **empty catalog is valid**: `GET /v1/models` returns HTTP 200 with
  `{"object":"list","data":[]}` and a non-empty `mca_catalog_revision`. It is not an error and not
  a fake model. `POST /v1/chat/completions` for any model then returns `404 model_not_found`
  before the backend is called.
- Every `/v1/models` call and every chat call re-read `DshGatewayBackend.catalog()`. Loading a
  model (empty -> one) makes chat routing accept its id immediately and makes the next listing
  return it, without restarting the gateway; unloading it reverses both. The published
  `DshGatewayEndpoint.catalogRevision` follows the latest listing.
- A **catalog read failure** is `503 catalog_unavailable` on both routes; it is never turned into
  an empty success. The same gateway recovers once a later read succeeds. Auth (`401`) is checked
  before catalog access, so an empty or failing catalog never weakens authentication.

Known compatibility notes against the official DSH pi-ai OpenAI-completions provider:

1. **The DSH model picker is seeded once per host generation.** The runtime seeder
   (`core/dsh-runtime` `DshGatewayConfigSeeder`) fetches `/v1/models` before launching DSH and
   writes the ids into `cordis.patch.yml`. A model loaded while the host is READY is routable by
   the gateway, but it will not appear in the official picker until the user re-runs model
   discovery or the host is restarted/reseeded. The gateway cannot push configuration into DSH.
2. **Prompt-compat label visibility.** The gateway appends `[prompt-compat]` (or the declared
   `toolProtocolLabel`) to `name`/`display_name` and keeps the unlabelled MCA name in
   `mca_display_name`. The current seeder prefers `mca_display_name`, so the seeded DSH picker
   shows the unlabelled name; prefer `display_name`/`name` in the seeder if the label must be
   visible in the official picker.
3. **Reasoning content replay.** pi-ai sends `reasoning_content` back on later assistant turns
   when a response contained thinking blocks. The frozen `DshModelMessage` contract has no
   reasoning field, so the gateway accepts and does not carry it; tool-call loops still work, but
   the model's earlier thinking text is not replayed.
4. **Reasoning effort.** If a DSH model entry declares `reasoning: true`, pi-ai adds an
   OpenAI-style `reasoning_effort` request field. The frozen contract cannot carry it; the gateway
   ignores the field (it does not reject the request), so the selected effort is not applied.
   Keep catalog entries reasoning-disabled unless the adapter carries effort itself.
5. **`finish_reason: "cancelled"`** is a gateway extension for MCA-initiated explicit cancel.
   pi-ai maps unknown finish reasons to a provider error. A DSH AbortSignal/disconnect closes the
   HTTP connection (no terminal frame is written), so this only matters if the MCA host uses its
   explicit cancel route while DSH is still waiting.
6. **Unknown model ids** return `404 model_not_found`, which pi-ai classifies as a generic
   `PI_AI_ERROR` and DSH's default retry policy does not retry (`EMPTY_RESPONSE`, `RATE_LIMIT`,
   `SERVER`, `TIMEOUT`, `TRANSPORT` are retryable). Keep DSH model ids sourced from the catalog.
7. Heartbeat comments, usage-only final chunks with `choices: []`, `[DONE]` and
   `Connection: close` are all accepted by the official OpenAI SDK/pi-ai path; no change needed.
8. **Producer terminal cause is still missing (outside this module).** The real
   `GenerateEvent.Done` in `core:inference-contract` carries only `stats` and `toolCalls`, with no
   finish/length/cancel reason. The app adapter therefore emits `Completed(STOP)` for every
   non-cancelled local/cloud `Done` and for malformed prompted output. The gateway cannot invent a
   better reason; a truncated generation can currently only reach DSH as `stop`. Needed producer
   change: add a neutral terminal reason (for example `Stopped/Length/Cancelled/Error/Unknown`) to
   `GenerateEvent.Done`, populate it in the local/cloud producers, and have the adapter map it to
   `DshFinishReason`; an unproven reason must become `Failed`, not STOP.
9. **Usage has no estimated/actual marker and zero is dropped upstream.** The gateway preserves
   any value it receives (including `0`, and `null` for unknown), but the app adapter calls
   `stats?.promptTokens?.takeIf { it > 0 }`, converting zero to unknown, and does not forward
   `RuntimeStats` estimated flags. Needed producer/contract change: a usage value carrying
   `promptTokens`/`completionTokens` plus estimated booleans, passed through without filtering.
10. **No-argument tool calls need an explicit `{}` fragment.** The frozen contract has no
    "arguments were absent" flag, so the gateway now rejects a call with no observed argument
    fragments instead of synthesizing `{}` (C06: never execute half-proven arguments). Producers
    that can represent a complete no-arg call as an empty string (for example the cloud decoder's
    argument accumulator) must normalize it to `{}`; otherwise the gateway reports
    `invalid_tool_call`. If empty-vs-absent must be distinguished, add an explicit completeness flag
    to the contract rather than inferring it in the gateway.

## Tests

```powershell
# JDK 21 + the official shared build lock (tools/build-guards/McaBuildLock.psm1, kind test)
Import-Module E:\MAC\tools\build-guards\McaBuildLock.psm1
$lock = Enter-McaBuildLock -Kind test -CommandLine '.\gradlew.bat :api:dsh-gateway:testDebugUnitTest'
try { .\gradlew.bat :api:dsh-gateway:testDebugUnitTest --console=plain }
finally { Exit-McaBuildLock -Lock $lock }
```

Current evidence: **95 tests, 0 failed** on
`.\gradlew.bat :api:dsh-gateway:testDebugUnitTest --console=plain` under
`Enter-McaBuildLock -Kind test` (official lock; `BUILD SUCCESSFUL in 2m43s`, 27 tasks:
5 executed, 22 up-to-date; XML written 2026-10-20 12:54:12). The suite is JVM unit + real
loopback socket integration with a fake backend and covers auth/loopback/Host/Origin rejection,
method/route handling, malformed JSON, oversize body, `/v1/models` shape (including a valid empty
`data: []`) and prompt-compat labelling, catalog refresh/routing without restart, catalog-error 503
(never an empty success), full transcript field fidelity, interleaved multi-tool streaming,
stop/length/error/EOF/cancel terminals, duplicate/late terminals ignored after the first one,
single-terminal framing, tool index/id/name stability, cross-index duplicate ids, missing argument
fragments, incomplete and oversized arguments, per-tool and total response caps, zero/unknown/
partial usage, SSE/buffered equivalence, explicit cancel (streaming and buffered), cancel of a held
tool envelope, disconnect isolation, concurrency limits and token redaction.

## NOT_RUN

- Real MCA local-model adapter tool round-trip and real cloud-model tool round-trip: not
  independently verified by this module's tests (the gateway is tested with a fake backend; the
  app-level adapter has its own tests). The integrator reports the device DSH host reaches this
  gateway; that device evidence is owned by the final integration report, not by this module.
- Real official DSH provider <-> this gateway message exchange on a desktop/device: not verified
  inside this module (the pi-ai request/response expectations were checked against the pinned
  official source, not executed here).
- Android instrumentation / device execution of the module.
- `tools/architecture/check_architecture_rules.py` (its declared scopes do not include this new
  module; not claimed as run).
- Full app build / APK and release validation (out of this task's scope).

## Rollback

The module is additive and has no persistent state, no schema and no migration. To roll back:
remove `implementation(project(":api:dsh-gateway"))` and the composition wiring, stop constructing
`DshGatewayServer`, and delete `api/dsh-gateway`; `settings.gradle.kts` already declares the
module for the other DSH work. Stopping the server closes the listener and cancels only its own
calls; Kotlin Agent, chat and the public Local API are unaffected.

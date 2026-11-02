# DSH Android Capability Bridge Audit

Status: static, read-only audit. No production code, build, test, device or DSH runtime was
executed. Findings are source observations, not gate results.

Date: 2026-10-19.

Scope requested: `app/**`, `core/dsh-runtime/**`, `feature/dsh-workspace/**`,
`api/dsh-gateway/**`. Supporting reads: `core/dsh-contract/**` (the shared contract),
`docs/adr/0003-dsh-embedded-dual-agent-modes.md`, this proposal directory, and the read-only
payload archive under `core/dsh-runtime/src/main/assets/dsh/`.

Baseline: branch `codex/enterprise-refactor`, HEAD
`7475fd640e21154dd9543bcae2c83ab112e30361`, plus a working tree in which the DSH modules
(`core/dsh-contract/`, `core/dsh-runtime/`, `feature/dsh-workspace/`, `api/dsh-gateway/`, the
`app` DSH files) are untracked. This audit therefore describes the current working tree, not a
reproducible commit. The three submodules are unchanged by this audit.

## 0. Verdict

**MCA has no Android capability bridge for DSH.** It has a well-isolated DSH page and runtime,
and it has a page-local file chooser/save path for the official Web UI, but it has no reusable,
authenticated, typed capability service that a DSH tool or plugin can call for file picking,
notifications, accessibility, or device actions.

What exists is narrower than "bridge":

| Capability family | Exists today? | Actual form | Callable by a DSH tool/plugin? |
| --- | --- | --- | --- |
| File pick (read) | PARTIAL | WebView `<input type="file">` -> `FileChooserParams.createIntent()` -> SAF, inside the Compose WebView host | No |
| File save | PARTIAL | Page download -> `DownloadManager` or `ACTION_CREATE_DOCUMENT`; bounded `data:` only | No |
| Notification | PARTIAL | Foreground-service status notification for the `:dsh` host | No |
| Accessibility | ABSENT | No `AccessibilityService`, no service config, no action adapter | No |
| Device actions / Shizuku / root | ABSENT | No Shizuku dependency, no privileged shell, no screenshot/global-action adapter | No |
| Capability discovery / auth | ABSENT | No capability token, no capability route, no state API | No |

The positive side of the current design is real: the DSH page does **not** depend on
`MainViewModel` or the global UI store. `DshWorkspaceRoute` receives only the frozen
`DshRuntimeClient` and a leave-tab callback
(`feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceRoute.kt:44-58`),
and `DshWorkspaceViewModel` receives only that client plus a navigation store
(`.../DshWorkspaceViewModel.kt:69-72`). Feature Rule 16 also forbids `feature:dsh-workspace` from
importing `com.muyuchat.mca.*`, runtime internals, the gateway, engine, or other features
(`tools/architecture/check_architecture_rules.py:420-445`). The only app-level coupling is
composition: `MainActivity` passes `viewModel.dshRuntimeClient`
(`app/src/main/java/com/muyuchat/mca/MainActivity.kt:391-407`) and `MainViewModel` exposes that
property (`app/src/main/java/com/muyuchat/mca/MainViewModel.kt:108-112`). That is wiring, not a
capability dispatch path.

The gap is exactly at the tool/plugin boundary: the only Android interactions are inside the
Compose WebView host, and they are not reachable by a DSH Node tool. Notifications are only the
host foreground notification. Accessibility and device actions do not exist at all.

## 1. Baseline, method and evidence rules

This is a static source audit. The method was:

1. Read the mandatory engineering contract and the DSH ADR/proposal for intended ownership.
2. Enumerate the four requested source scopes plus `core/dsh-contract`.
3. Search for capability, bridge, file-picker, notification, accessibility, device-action,
   permission, token, and WebView-bridge terms.
4. Read the concrete call chains around the matches, including one failure/cancel branch.
5. Read selected entries of the packaged payload archive read-only to compare reference tool
   expectations with MCA's active code. No archive entry was extracted to disk.

Evidence notation:

- `path:line` is a repository file and physical line range.
- `payload.zip!entry:line` is a line inside an archive entry, not a filesystem file. It is
  reference evidence only; it is not MCA's active configuration.
- "ABSENT" means no production implementation was found in the requested scopes by static
  search. It does not mean "impossible"; it means "not wired".
- No `PASS` is claimed. Runtime and device behavior are `NOT_RUN`.

## 2. Existing seams

### 2.1 Runtime contract: lifecycle only

`core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/DshRuntimeContract.kt` is the
entire shared DSH contract today:

- `DshRuntimeClient` has `snapshot`, `ensureStarted`, `restart`, `stop`, `webEntry` only
  (`:91-109`).
- `DshWebEntry` is the single-use authenticated Web URL (`:74-82`).
- `DshGatewayEndpointProvider`/`DshGatewayEndpoint` are the model gateway coordinates
  (`:112-126`).

There is no `DshCapability*` type in the module. A repository-wide search for
`DshCapability`, `CapabilityPort`, `CapabilityBroker`, `CapabilityServer`, `CapabilityEndpoint`
or `CapabilityState` returns no production DSH symbol.

`DshRuntimeOwner` is deliberately platform-free and gets Android seams through `Deps`
(`core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt:31-43` and
`:50-84`). Its `Deps` surface is payload, exec strategy, readiness, ABI, process, links, logging,
`gatewayEndpoint()` and `seedDshHome()` only. A capability endpoint is not part of this seam.

### 2.2 IPC: lifecycle control only

The app-side client and the `:dsh` service speak a small Messenger protocol:

- Messages: register, ensure-started, restart, stop, get-entry, release
  (`core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeIpc.kt:21-30`).
- The handler dispatches only those messages
  (`.../DshRuntimeService.kt:113-200`).
- Gateway coordinates are sent in a Bundle before start
  (`DshRuntimeIpc.kt:92-119`; `DshRuntimeServiceClient.kt:126-148`).

This is a good control channel. It is not a request/response capability channel: no file, no
notification, no accessibility, no device-action message exists. Adding them here would mix
lifecycle control with potentially long user-interaction waits and would make every capability
call a Binder transaction; the recommendation below keeps them on a separate loopback transport.

### 2.3 Model gateway: model-only, not a capability gateway

`api/dsh-gateway` is a loopback OpenAI-compatible model adapter. Its own contract says it "never
executes DSH tools, never touches MCA chat history, never reads cloud keys"
(`api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewayServer.kt:27-41`).
Its route table is only:

- `GET /healthz`
- `GET /v1/models`
- `POST /v1/chat/completions`
- `POST /v1/mca/calls/cancel`

(`DshGatewayServer.kt:228-248` and `:460-471`). Adding capability routes here would mix a model
audience with a privileged device audience and would violate the gateway's one-job contract.
Do not extend this server for capabilities.

### 2.4 WebView interactions: page-local, not a bridge

The official page is hosted by `DshWebViewHost`. It is explicitly the only place Android WebView
platform objects are created
(`feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewHost.kt:64-70`).
Its Android interactions are:

- **File upload**: `DshWebChromeClient.onShowFileChooser` delegates to
  `DshFileChooserCoordinator.begin`, which builds the page's own
  `FileChooserParams.createIntent()`, adds read grant and multiple-selection flags, and stores a
  pending `ValueCallback` in Compose state
  (`.../web/DshWebDialogsAndFiles.kt:16-50`; `.../web/DshWebChromeClient.kt:53-58`).
- **Launching the picker**: `rememberLauncherForActivityResult(DshFileChooserContract())` inside
  the Composable host (`DshWebViewHost.kt:108-114`).
- **Saving a page download**: a bounded `data:` download becomes `DshSaveFileRequest`; a
  `rememberLauncherForActivityResult(DshSaveFileContract())` launches
  `ACTION_CREATE_DOCUMENT` and writes through `ContentResolver`
  (`DshWebViewHost.kt:47-62`, `:116-138`, `:164-179`).
- **Downloads**: `DshDownloadPolicy` allows `http(s)` and bounded `data:`; `blob:` is explicitly
  rejected because reading it would require a JavaScript bridge
  (`feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshDownloads.kt:48-58`).
- **JS dialogs / external links / fullscreen**: page helpers, not capability APIs
  (`.../web/DshWebChromeClient.kt:70-97`; `.../DshWorkspaceRoute.kt:68-100`).

These interactions are **initiated by the page** and are bound to the Compose host's lifecycle.
The coordinator is `internal` to the feature module; it holds the pending
`ValueCallback` in a plain field and exposes the pending request as `mutableStateOf`
(`DshWebDialogsAndFiles.kt:25-50`, `:30-31`), and there is no service, port, token, or callable
entry point outside the WebView composition. A DSH Node tool cannot reach it.

The module README is explicit: "Everything else (shell, WebView, file picker, downloads,
navigation memory) is self-contained" (`feature/dsh-workspace/README.md:47`), and the file
picker described there is the page upload path (`README.md:142-149`). It also states "No JS
bridge" (`README.md:122`) and lists DSH sessions/tools/plugins as out of scope
(`README.md:243-244`).

### 2.5 Notifications: foreground-service status only

`DshRuntimeNotifications` builds one notification for the `:dsh` foreground service: host phase,
a launch `PendingIntent`, and a stop action
(`core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeNotifications.kt:13-19`,
`:35-59`). `DshRuntimeService` creates the channel, starts foreground, and refreshes it when the
runtime snapshot changes (`.../DshRuntimeService.kt:63-80`, `:245-269`). The channel importance is
`IMPORTANCE_LOW` (`DshRuntimeNotifications.kt:24-32`).

There is no DSH-callable notification API, no task/approval notification, no notification
permission state, and no user notification channel for DSH agent work. The app has no
`mca_dsh_agent` channel; the only DSH channel is `mca_dsh_runtime`
(`core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshRuntimeConfig.kt:49-52`).

### 2.6 Composition and MainViewModel isolation

The DSH composition is already split into typed app-level assemblies:

- `MainRuntimeAssembly` creates the model gateway backend, `DshGatewayAssembly`, and
  `DshModeAssembly`, and exposes `dshRuntimeClient`
  (`app/src/main/java/com/muyuchat/mca/composition/MainRuntimeAssembly.kt:106-144`).
- `DshGatewayAssembly` owns one model gateway per host generation and rotates its token
  (`.../DshGatewayAssembly.kt:13-24`, `:36-65`).
- `DshModeAssembly` binds the gateway lifecycle to `DshRuntimeClient.ensureStarted/restart/stop`
  (`.../DshModeAssembly.kt:13-58`).

This is the right place to add a capability assembly. It is not `MainViewModel`: `MainViewModel`
only exposes the app-facing client and already has a large constructor with existing `Any?`
injection debt (`MainViewModel.kt:67-83`, `:101-112`). New capability code must not add another
untyped constructor parameter or a `MainUiState` field.

### 2.7 Reusable precedent: Kotlin Agent document launcher

The Kotlin Agent has a useful pattern: a platform-free `AgentDocumentLauncher` with plain
`AgentDocumentRef` values
(`feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/AgentPackageContracts.kt:5-11`,
`:25-48`), implemented in the app by Compose Activity Result launchers and `ContentResolver`
(`app/src/main/java/com/muyuchat/mca/presentation/AgentDocumentLauncherUi.kt:13-19`, `:26-46`).

It proves that "plain contract + app-level Activity adapter" is acceptable in this codebase. It
is not reusable for DSH as-is:

- it is feature-local to `feature:agent-workspace`;
- `feature:dsh-workspace` may depend only on `core:dsh-contract` and `core:designsystem`
  (`feature/dsh-workspace/build.gradle.kts:46-52`; Rule 16
  `tools/architecture/check_architecture_rules.py:420-433`);
- it has no capability token, generation, consent, stub, or notification/accessibility surface;
- its `AgentDocumentRef` is an opaque string, but there is no staging/path policy.

The DSH bridge should copy the shape (typed port + app adapter), not the module location.

## 3. Authentication and token model

MCA currently has three relevant credential domains, and a fourth that is missing.

| Credential | Owner / issuer | Audience | Lifetime and storage | Current status |
| --- | --- | --- | --- | --- |
| DSH Web launch token -> HttpOnly cookie | DSH kernel, handed to the WebView by `DshRuntimeOwner` | DSH loopback Web origin only | Single-use in-process URL; cookie is HttpOnly/SameSite; URL is never logged/persisted/Intented | Implemented |
| MCA model gateway bearer (`dshg_...`) | `DshGatewayAssembly` / `DshGatewayServer` | `/v1/models`, `/v1/chat/completions`, cancel | One per host generation; in-memory endpoint; passed to DSH child via `MCA_DSH_GATEWAY_TOKEN`; never logged | Implemented |
| Capability bearer (`dshc_...`) | Would be a new capability owner | File/notification/device routes | Not present | **ABSENT** |
| Android runtime permission / accessibility enablement | Android system / user settings | App UID / service | Persistent until user revokes | Partially declared; no request flow |

### 3.1 DSH Web entry

`DshAuthEntryParser` defines the launch entry as in-memory only and redacts it from logs
(`core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/auth/DshAuthEntryParser.kt:5-18`).
It accepts only a strict loopback `http://127.0.0.1:<port>/?token=...` shape, with one token
query parameter and a minimum length (`:43-63`). The readiness gate proves the official
token -> 303 -> HttpOnly cookie flow and then proves an unauthenticated request is refused with
401 (`core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/DshReadinessGate.kt:85-105`,
`:162-169`). This is the WebView's authentication, not a tool bridge.

### 3.2 Model gateway bearer

`DshGatewayServer.start` binds `127.0.0.1:0`, mints a fresh `dshg_` token with `SecureRandom`,
and publishes the endpoint only after the state is installed
(`api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewayServer.kt:92-120`;
`.../DshGatewaySecret.kt:7-22`). Every request is checked with a constant-time comparison
(`DshGatewayServer.kt:389-394`; `DshGatewaySecret.kt:28-34`). Non-loopback peers are closed
(`DshGatewayServer.kt:162-165`), and the transport rejects a browser `Origin` header and a
non-loopback `Host` (`api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewayHttp.kt:92-98`,
`:183-198`). The token is never logged and is cleared on stop
(`DshGatewayServer.kt:27-41`, `:131-145`).

### 3.3 Binder carriage

The gateway endpoint travels from the app process to the `:dsh` service as in-memory Bundle
fields: base URL, bearer, catalog revision (`DshRuntimeIpc.kt:92-119`). The service stores it in
`DshGatewayEndpointHolder` (`DshAndroidRuntimeAssembly.kt:25-41`) and `DshRuntimeOwner` adds it to
the child environment as `MCA_DSH_GATEWAY_TOKEN` (`DshRuntimeOwner.kt:278-290`). The Web entry
travels back over Binder too (`DshRuntimeService.kt:175-190`). None of these is written to an
Intent extra, preference, or log.

### 3.4 What the capability bridge would need

A capability token must be a **fourth, separate credential**, not a reuse of the Web cookie or
the model gateway token:

- separate audience and prefix (e.g. `dshc_`) so a model token cannot call file/notification
  routes and a capability token cannot call model routes;
- minted per runtime generation with `SecureRandom`, compared in constant time, never logged,
  persisted, placed in an Intent, or put in a URL query;
- delivered to the DSH host via environment (like the gateway token), not written into
  `cordis.patch.yml` or DSH session data;
- revoked when the host generation stops/restarts/fails, with pending capability calls failing
  closed;
- Host/`Origin` checks and loopback binding equivalent to the model gateway, plus an explicit
  generation check.

The current code has no place for any of that: `DshRuntimeIpc` has no capability fields,
`DshRuntimeOwner.Deps` has no capability endpoint, and there is no capability server. That is
the first blocker.

## 4. Permission ownership

### 4.1 Manifest ownership

`feature:dsh-workspace` intentionally declares no permission, provider, service, or activity
(`feature/dsh-workspace/src/main/AndroidManifest.xml:2-14`). The app owns the real permissions:
`INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, and
`REQUEST_INSTALL_PACKAGES` (`app/src/main/AndroidManifest.xml:4-10`). The DSH runtime module also
declares the foreground-service and notification permissions
(`core/dsh-runtime/src/main/AndroidManifest.xml:4-7`) and the `:dsh` service
(`:10-25`). The app manifest has no storage permission, no accessibility service, no overlay
permission, and no Shizuku SDK.

### 4.2 No runtime permission request flow

A search across the four requested scopes for
`ActivityResultContracts.RequestPermission`, `requestPermissions(`, `checkSelfPermission`,
`shouldShowRequestPermissionRationale`, and `areNotificationsEnabled` returns **zero** hits.
`POST_NOTIFICATIONS` appears only as two manifest declarations
(`app/src/main/AndroidManifest.xml:9`; `core/dsh-runtime/src/main/AndroidManifest.xml:7`).

Consequences:

- On Android 13+, the app declares `POST_NOTIFICATIONS` but never asks for it. The DSH foreground
  service still starts, but the notification may be invisible in the drawer; `notify()` may be a
  no-op. `DshRuntimeNotifications.build` does not check `areNotificationsEnabled`
  (`DshRuntimeNotifications.kt:35-59`), and `DshRuntimeService` calls `manager.notify` without a
  permission check (`DshRuntimeService.kt:260-268`). The Kotlin Agent's own notification
  controller also posts without a permission check
  (`app/src/main/java/com/muyuchat/mca/agent/platform/AgentRunNotificationController.kt:183`).
- A capability bridge cannot truthfully report `GRANTED` for notifications until it checks the
  runtime permission and the channel state.
- A permission request must be initiated from a visible Activity/user gesture. It cannot be
  silently triggered by a DSH tool in the background.

### 4.3 DSH approval is not an Android permission

`DshGatewayConfigSeeder` writes DSH permission presets (`read-only`, `workspace-write`,
`danger-full-access`) and defaults to `workspace-write` + `approval: ask`
(`core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt:153-186`).
That is DSH's own tool-approval policy. It does **not** grant `POST_NOTIFICATIONS`, SAF URI
access, accessibility service enablement, `WRITE_SETTINGS`, or any device privilege.

ADR 0003 makes the same distinction: DSH keeps official approval semantics while MCA is to
provide a narrow Android platform contract for file selection/save, share/external links,
notifications, and necessary system permissions
(`docs/adr/0003-dsh-embedded-dual-agent-modes.md:45-53`), and approval/path conventions do not
constitute isolation from malicious plugins (`:129`). The DSH architecture also says Android
control/SAF/system permissions may be provided by a new Cordis platform plugin while MCA
supplies the required system capability port
(`docs/proposals/agent-dsh-embedded-v1/ARCHITECTURE.md:156`, `:164`).

### 4.4 SAF grants

The current page upload path adds `FLAG_GRANT_READ_URI_PERMISSION` to the picker Intent
(`DshWebDialogsAndFiles.kt:46`) but never calls `takePersistableUriPermission` for DSH. The only
persistable-URI code in `MainActivity` belongs to model import
(`app/src/main/java/com/muyuchat/mca/MainActivity.kt:180-187`). A DSH tool that receives a SAF
URI today has only a transient grant, and it cannot use a `content://` URI directly from Node
anyway.

### 4.5 Accessibility and device actions

There is no `AccessibilityService` declaration, no `BIND_ACCESSIBILITY_SERVICE`, no
`res/xml/accessibility_service_config.xml`, no `ACTION_ACCESSIBILITY_SETTINGS` flow, no
`MediaProjection`, no Shizuku/root dependency, and no device-action adapter. The only
DSH-runtime mention of the reference accessibility tooling is a seeder comment saying the
reference Android accessibility/virtual-screen/browser tool inserts are deliberately **not**
copied (`DshGatewayConfigSeeder.kt:21-24`). A search for `Shizuku`,
`BIND_ACCESSIBILITY_SERVICE`, `AccessibilityService`, `MediaProjection`, and
`SYSTEM_ALERT_WINDOW` across the requested scopes returns no production implementation.
(`app/src/androidTest/**` uses instrumentation `AccessibilityNodeInfo` and
`device.takeScreenshot` for test automation; those are test helpers, not an app accessibility
service or a DSH capability.)

### 4.6 Trust boundary

The `:dsh` process is not an OS security sandbox: the service comment says both processes share
the app UID, so DSH and its npm plugins can read the same UID-visible files
(`core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeService.kt:29-41`).
A capability bridge does not change that. Its token limits accidental/cross-app access and
provides an audit/consent boundary; it does not isolate a malicious installed plugin. Any design
that claims "the bridge secures DSH plugins" would be false. This matches ADR 0003
(`docs/adr/0003-dsh-embedded-dual-agent-modes.md:129`) and the V1 architecture
(`ARCHITECTURE.md:168-175`).

## 5. Missing capabilities

| ID | Capability | Current state | What is missing | Evidence |
| --- | --- | --- | --- | --- |
| M1 | DSH-callable file pick | PARTIAL (page-only) | Typed request, user-presence gate, multiple/accept policy, staging, opaque result | `DshWebViewHost.kt:108-114`; `DshWebDialogsAndFiles.kt:25-50`; `README.md:47` |
| M2 | DSH-callable file save | PARTIAL (page download only) | Typed source (workspace/inline), SAF create, size/type limits, result | `DshWebViewHost.kt:47-62`, `:116-138`; `DshDownloads.kt:48-58` |
| M3 | URI grant lifecycle | ABSENT | Persist/release policy, generation-scoped grant ledger, no raw `content://` to Node | `DshWebDialogsAndFiles.kt:46`; `MainActivity.kt:180-187` |
| M4 | DSH-callable notification | ABSENT | Notification sink, fixed channel, title/body limits, deep-link policy, idempotency | `DshRuntimeNotifications.kt:13-19`, `:35-59`; `DshRuntimeService.kt:260-268` |
| M5 | Notification permission state/request | ABSENT | `POST_NOTIFICATIONS` check/request from visible Activity, settings deep link, state in capability API | `app/AndroidManifest.xml:9`; `core/dsh-runtime/AndroidManifest.xml:7`; zero permission-request hits |
| M6 | Accessibility actions | ABSENT | `AccessibilityService`, config XML, enablement state, target verification, read/tap/type/scroll | `DshGatewayConfigSeeder.kt:21-24`; no manifest service/config |
| M7 | Screenshot | ABSENT | Accessibility screenshot or MediaProjection with consent, staging, redaction | No production screenshot/`MediaProjection` capability; `app/src/androidTest/**` uses UiAutomation for test evidence only |
| M8 | Device/global actions / Shizuku / root | ABSENT | Separate opt-in adapter, per-command approval, availability state; not in V1 | No Shizuku dependency; seeder comment `DshGatewayConfigSeeder.kt:21-24` |
| M9 | Capability discovery/status | ABSENT | Authenticated `GET capabilities`, per-family state, no fake tools when unavailable | `DshRuntimeIpc.kt:21-30`; `DshGatewayServer.kt:460-471` |
| M10 | Consent, rate limit, audit | ABSENT | App-level consent surface, per-action confirmation, bounded audit, dedupe | No capability code |
| M11 | Foreground/user-presence contract | ABSENT | `USER_PRESENCE_REQUIRED` semantics, Activity-resumed check, process-death behavior | Activity Result launchers are page-local; `McaApplication.kt:12-47` has a foreground relay but it is not exposed to DSH |
| M12 | DSH-side tool plugin/packaging | ABSENT | First-party Cordis plugin that registers tools only when `GRANTED`; pinned package/hash | Payload reference tools expect `APP_NOTIFY_PORT`/`APP_A11Y_PORT`; MCA has no such server or env |
| M13 | Share/clipboard (R04 adjacent) | ABSENT in DSH feature | `ACTION_SEND`/clipboard policy if product wants it | `feature/dsh-workspace/README.md:243-244`; R04 scope `EXECUTION_PACK.md:74`; app-wide `ClipboardManager` at `AppStartupScreen.kt:210` is diagnostics, not a DSH bridge |

The payload comparison matters because it shows the expected shape of the missing bridge, not a
working MCA feature. The packaged payload contains reference Android tool packages and a
reference root patch that inserts them:

- `payload.zip!dshhome/cordis.patch.yml:59-79` inserts `dsh-tool-shizuku`, `dsh-tool-android`,
  `dsh-tool-accessibility`, `dsh-tool-vscreen`, and `dsh-tool-browser`, with a
  `danger-full-access`/`approval: never` default.
- `payload.zip!.../dsh-tool-android/lib/index.js:65-66` expects `APP_NOTIFY_PORT` (default 3081);
  `:102-119` posts to the app's local HTTP service with `X-DSH-Token`.
- `payload.zip!.../dsh-tool-accessibility/lib/index.js:3`, `:20`, `:32-37` expects the app
  `AccessibilityService` on `APP_A11Y_PORT` (default 3181), also with `X-DSH-Token`.
- `payload.zip!.../dsh-tool-shizuku/lib/index.js:171-191` expects
  `127.0.0.1:<APP_NOTIFY_PORT>/shell`; `:409-461` expects `/notify` with the same header.
- `payload.zip!.../dsh-tool-vscreen/lib/index.js:150-153` expects `/vscreen/status` on the same
  app service.

MCA source contains no `APP_NOTIFY_PORT`, `APP_A11Y_PORT`, `APP_LOCAL_TOKEN`, `X-DSH-Token`,
`3081`, or `3181` reference, and no app-side endpoints for `/notify`, `/shell`, `/setting`,
`/clipboard`, `/input`, `/screenshot`, or `/vscreen/*`. The payload tools are therefore
reference-only; loading them without an MCA app-side counterpart would produce failures, not a
capability.

MCA's seeder correctly refuses to copy the reference patch: it writes only the permission and
`llm-pi-ai` rows (`DshGatewayConfigSeeder.kt:153-186`), and its test asserts that
`tool-android` must never be inserted
(`core/dsh-runtime/src/test/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeederTest.kt:64-65`).
This fail-closed behavior should stay until the capability contract exists.

## 6. Minimal typed bridge contract

### 6.1 Design constraints

The contract must satisfy all of these:

1. **Platform-free.** The typed contract lives in `core:dsh-contract` and imports no
   `android.*`, `androidx.*`, `org.json`, `com.muyuchat.mca.*`, feature, API, Room, or OkHttp
   types. This is already enforced by Rule 15
   (`tools/architecture/check_architecture_rules.py:386-417`).
2. **Narrow.** Five capability operations at most in V1; no generic `exec`, `shell`,
   `readAnyFile`, `intent`, `reflection`, or `dispatchGesture` escape hatch.
3. **No UI objects.** No `Activity`, `Context`, `Uri`, `Intent`, `PendingIntent`,
   `Notification`, `AccessibilityNodeInfo`, `Bitmap`, `MainViewModel`, `MainUiState`,
   `UiStateStore`, or `MainCoordinatorHost` in the contract.
4. **One owner.** A single app-level capability owner per runtime generation owns grants,
   consent, rate limits, idempotency, and audit. The WebView page does not own it; the model
   gateway does not own it; `MainViewModel` does not own it.
5. **Generation-scoped.** Requests carry the runtime epoch. A stale generation is rejected; a
   restart rotates the capability token and cancels pending work.
6. **User-mediated.** File selection/save always show the system picker; dangerous device
   actions require an explicit app-level consent surface; the OS permission alone is not
   treated as consent.
7. **Fail closed.** No fake success, no replay of unknown outcomes, no auto-registration of
   tools whose state is not `GRANTED`.
8. **Secret-free.** Request/response/error text never contains the capability token, model token,
   DSH cookie, raw app-private path, or raw `content://` URI.

### 6.2 Typed Kotlin contract (proposed)

The following is a proposal sketch, not compiled code. Final names may change; the semantics and
forbidden-type list are the contract. It is intentionally small enough for the 300-line contract
budget (`tools/architecture/check_architecture_rules.py:50-56`).

```kotlin
package com.muyuchat.core.dsh.contract.capability

/** Stable capability families for the DSH Android bridge. */
enum class DshCapability {
    FILE_PICK,
    FILE_SAVE,
    NOTIFICATION,
    DEVICE_ACTION
}

/** Why a capability is or is not usable right now. */
enum class DshCapabilityState {
    GRANTED,
    DENIED,
    NOT_DECLARED,
    NOT_ENABLED,
    UNSUPPORTED
}

data class DshCapabilityStatus(
    val capability: DshCapability,
    val state: DshCapabilityState,
    /** Prompt-free detail, e.g. "notification permission denied by user". */
    val detail: String? = null
)

@JvmInline
value class DshCapabilityRequestId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_-]{16,64}"))) {
            "DSH capability request id must be 16..64 URL-safe characters"
        }
    }
}

/** Opaque SAF document reference. Never a raw Uri, path, or content:// string to DSH. */
@JvmInline
value class DshDocumentRef(val value: String) {
    init { require(value.isNotBlank() && value.length <= 512) }
}

/** A file copied by the broker into the DSH workspace, ready for Node tools to read. */
data class DshStagedFile(
    val document: DshDocumentRef,
    /** Relative to DSH_WORKSPACE; validated by the broker, never absolute. */
    val workspaceRelativePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String? = null
)

sealed interface DshCapabilityRequest {
    val requestId: DshCapabilityRequestId
    /** Runtime epoch this request belongs to. */
    val generation: Long
    /** Short user-facing reason; bounded by DshCapabilityLimits.MAX_REASON_CHARS. */
    val reason: String
}

data class DshPickFilesRequest(
    override val requestId: DshCapabilityRequestId,
    override val generation: Long,
    override val reason: String,
    val acceptMimeTypes: List<String>,
    val allowMultiple: Boolean = false,
    val maxBytesPerFile: Long = DshCapabilityLimits.MAX_STAGED_FILE_BYTES
) : DshCapabilityRequest

sealed interface DshPickFilesResult {
    data class Picked(val files: List<DshStagedFile>) : DshPickFilesResult
    data object Cancelled : DshPickFilesResult
    data class Rejected(val failure: DshCapabilityFailure) : DshPickFilesResult
}

data class DshSaveFileRequest(
    override val requestId: DshCapabilityRequestId,
    override val generation: Long,
    override val reason: String,
    val source: DshSaveSource,
    val suggestedName: String,
    val mimeType: String
) : DshCapabilityRequest

sealed interface DshSaveSource {
    /** A file already inside DSH_WORKSPACE (validated relative path). */
    data class WorkspaceFile(val relativePath: String) : DshSaveSource
    /** Small inline payload; larger content must use WorkspaceFile. */
    data class InlineBytes(val bytes: ByteArray) : DshSaveSource
}

sealed interface DshSaveFileResult {
    data class Saved(
        val document: DshDocumentRef,
        val displayName: String
    ) : DshSaveFileResult
    data object Cancelled : DshSaveFileResult
    data class Rejected(val failure: DshCapabilityFailure) : DshSaveFileResult
}

data class DshNotifyRequest(
    override val requestId: DshCapabilityRequestId,
    override val generation: Long,
    override val reason: String,
    val title: String,
    val body: String,
    val ongoing: Boolean = false,
    /** Same-origin DSH path only; the broker builds the PendingIntent, never DSH. */
    val deepLinkPath: String? = null
) : DshCapabilityRequest

sealed interface DshNotifyResult {
    data class Posted(val notificationId: Int) : DshNotifyResult
    data class Rejected(val failure: DshCapabilityFailure) : DshNotifyResult
}

data class DshDeviceActionRequest(
    override val requestId: DshCapabilityRequestId,
    override val generation: Long,
    override val reason: String,
    val action: DshDeviceAction,
    /** Optional target check; an action on a different package/window is rejected. */
    val expectedPackage: String? = null,
    val expectedWindowId: Int? = null
) : DshCapabilityRequest

sealed interface DshDeviceAction {
    data object ReadScreen : DshDeviceAction
    data class Tap(val x: Int, val y: Int) : DshDeviceAction
    data class Swipe(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val durationMillis: Long
    ) : DshDeviceAction
    data class PressKey(val key: DshDeviceKey) : DshDeviceAction
    data class TypeText(val text: String) : DshDeviceAction
    data object Screenshot : DshDeviceAction
}

enum class DshDeviceKey { BACK, HOME, RECENTS, ENTER, MENU }

sealed interface DshDeviceActionResult {
    data class Completed(val data: String? = null) : DshDeviceActionResult
    data class Screenshot(val staged: DshStagedFile) : DshDeviceActionResult
    data object Cancelled : DshDeviceActionResult
    data class Rejected(val failure: DshCapabilityFailure) : DshDeviceActionResult
}

data class DshCapabilityFailure(
    val code: DshCapabilityErrorCode,
    val capability: DshCapability,
    /** Prompt-free, secret-free user-facing message. */
    val message: String,
    val retryable: Boolean = false
)

enum class DshCapabilityErrorCode {
    STALE_GENERATION,
    INVALID_REQUEST,
    USER_CANCELLED,
    USER_PRESENCE_REQUIRED,
    PERMISSION_DENIED,
    NOT_ENABLED,
    CAPABILITY_UNAVAILABLE,
    RATE_LIMITED,
    TOO_LARGE,
    TIMEOUT,
    TARGET_CHANGED,
    REQUEST_IN_PROGRESS,
    INTERNAL
}

/**
 * The only interface a DSH capability server may call. Implementations live in the app/platform
 * layer and own Activity result registration, NotificationManager, AccessibilityService, etc.
 */
interface DshCapabilityPort {
    suspend fun status(capability: DshCapability): DshCapabilityStatus
    suspend fun pickFiles(request: DshPickFilesRequest): DshPickFilesResult
    suspend fun saveFile(request: DshSaveFileRequest): DshSaveFileResult
    suspend fun notify(request: DshNotifyRequest): DshNotifyResult
    suspend fun performDeviceAction(request: DshDeviceActionRequest): DshDeviceActionResult
}

/** Loopback coordinates for one runtime generation. Mirrors DshGatewayEndpoint. */
data class DshCapabilityEndpoint(
    val baseUrl: String,
    val bearerToken: String,
    val generation: Long
)

fun interface DshCapabilityEndpointProvider {
    /** Null means no capability listener for this generation; DSH may still start. */
    suspend fun endpoint(): DshCapabilityEndpoint?
}

object DshCapabilityLimits {
    const val MAX_INLINE_BYTES = 1_048_576L
    const val MAX_STAGED_FILE_BYTES = 67_108_864L
    const val MAX_FILES_PER_PICK = 8
    const val MAX_REASON_CHARS = 200
    const val MAX_NOTIFICATION_TITLE_CHARS = 120
    const val MAX_NOTIFICATION_BODY_CHARS = 500
    const val MAX_SCREEN_TEXT_CHARS = 20_000
}
```

Notes:

- `DshCapabilityPort` has five operations, below the hard-lock review threshold of 10.
- `DshDocumentRef` is deliberately opaque. The contract never exposes `Uri`, `content://`, a
  file path outside the DSH workspace, or a `ParcelFileDescriptor`.
- `DshStagedFile.workspaceRelativePath` is allowed because DSH already owns
  `DSH_WORKSPACE`; it must be relative, canonicalized, and checked for traversal/symlinks by the
  broker. It is not an arbitrary app-private path.
- `InlineBytes` is capped at 1 MiB. Larger saves use `WorkspaceFile`.
- `Screenshot` returns a staged file, not a `Bitmap`; the broker chooses the representation.
- `DshCapabilityEndpoint` contains a secret; it must never be logged, persisted, or placed in a
  public Intent, exactly like `DshGatewayEndpoint`.

### 6.3 Wire protocol

A DSH-side Cordis plugin is the only DSH-side code that speaks this protocol. It is a thin
adapter: no Android classes, no Activity, no MainViewModel. The server can be a new
`:api:dsh-capability` protocol adapter (recommended, mirrors `:api:dsh-gateway`) or an app-owned
thin adapter if a new module is not justified; it must not live in `feature:dsh-workspace` or
`MainViewModel`.

| Method | Route | Request payload | Success result | Default timeout |
| --- | --- | --- | --- | --- |
| GET | `/v1/capabilities` | none | `{generation, capabilities:[{capability,state,detail}]}` | 2 s |
| POST | `/v1/files/pick` | `DshPickFilesRequest` | `DshPickFilesResult` | 180 s (user interaction) |
| POST | `/v1/files/save` | `DshSaveFileRequest` | `DshSaveFileResult` | 180 s (user interaction) |
| POST | `/v1/notifications` | `DshNotifyRequest` | `DshNotifyResult` | 5 s |
| POST | `/v1/device/actions` | `DshDeviceActionRequest` | `DshDeviceActionResult` | 15 s (screenshot 30 s) |
| POST | `/v1/cancel` | `{requestId}` | `{cancelled:boolean}` | 2 s |

Envelope:

```json
{
  "schema": "mca.dsh.capability.v1",
  "requestId": "01J...",
  "generation": 7,
  "capability": "file.pick",
  "payload": { }
}
```

Response:

```json
{
  "schema": "mca.dsh.capability.v1",
  "requestId": "01J...",
  "ok": true,
  "result": { "outcome": "picked", "files": [] }
}
```

Error:

```json
{
  "schema": "mca.dsh.capability.v1",
  "requestId": "01J...",
  "ok": false,
  "error": {
    "code": "PERMISSION_DENIED",
    "capability": "file.pick",
    "message": "用户未允许选择文件。",
    "retryable": false
  }
}
```

Transport rules:

- `Authorization: Bearer <capability-token>` only. A `?token=` query is rejected; the token is
  never logged, echoed, or returned.
- `X-MCA-DSH-Generation: <epoch>` is required and must equal the current generation; mismatch
  returns `409 STALE_GENERATION`.
- Bind `127.0.0.1:0`; reject non-loopback peers, a non-loopback `Host`, and any `Origin` header
  (same fail-closed model as the model gateway: `DshGatewayHttp.kt:92-98`, `:183-198`).
- `Content-Type: application/json`; no chunked request bodies; bounded headers/body; no CORS; no
  WebSocket.
- Authenticated capability outcomes (including user cancel and permission denied) use HTTP 200
  with a typed error/result so a normal cancel is not confused with a transport failure. HTTP
  4xx/5xx are reserved for auth/transport/protocol errors.
- `requestId` is idempotent per generation. A terminal result is cached for a bounded window; a
  duplicate returns the same result and must not re-post a notification or re-execute a device
  action. A duplicate while still pending returns `REQUEST_IN_PROGRESS` (`retryable = true`).

### 6.4 Authentication, generation and cancellation

The server owns a per-generation capability token, separate from both existing credentials:

- `SecureRandom` 32 bytes, prefix `dshc_`, Base64url without padding; constant-time compare.
- Server starts before `DshRuntimeClient.ensureStarted()` for the generation and stops on
  `stop`, `restart`, start failure, or app teardown, analogous to the gateway lifecycle in
  `DshModeAssembly.kt:33-55`.
- Endpoint is passed app -> `:dsh` service over Binder (extend `DshRuntimeIpc` with a capability
  Bundle), stored in memory, and injected into the Node environment by `DshRuntimeOwner`
  (extend `Deps` with `capabilityEndpoint()` and add
  `MCA_DSH_CAPABILITY_URL`/`MCA_DSH_CAPABILITY_TOKEN`/`MCA_DSH_RUNTIME_EPOCH` to
  `DshNodeCommand.environment`; current env is `DshNodeCommand.kt:48-75`).
- If the capability server cannot start, DSH still starts (capability is optional). The plugin
  sees `CAPABILITY_UNAVAILABLE`; it must not register tools or fake success. This is the opposite
  of the model gateway, where a null endpoint is fatal (`DshRuntimeOwner.kt:252-273`).
- On generation stop, the server closes its listener and cancels pending requests with
  `STALE_GENERATION`/`Cancelled`; the token is dead. Pending Activity Result callbacks are
  detached; a late result is ignored. Nothing is replayed after process death.
- The token authenticates the DSH host process, not an individual plugin. A malicious installed
  plugin can read the Node environment and impersonate another plugin; this is within the
  same-UID trusted-code boundary described in `DshRuntimeService.kt:29-41`. Per-plugin identity
  is a non-goal for V1.

### 6.5 Permission and consent ownership

| Capability | OS declaration | User/OS grant | MCA owner | What a DSH plugin may do |
| --- | --- | --- | --- | --- |
| File pick | None (SAF) | System picker per call | `DshCapabilityPort.pickFiles` + `DshFilePickerHost` in app | Request a pick; never see a raw Uri |
| File save | None (SAF) | System picker per call | `DshCapabilityPort.saveFile` + app host | Request a save from a workspace file or small inline bytes |
| Notification | `POST_NOTIFICATIONS` (already declared) | Runtime permission API 33+; channel enabled | `DshNotificationSink` + app permission flow | Request a bounded notification; no custom PendingIntent |
| Accessibility | `BIND_ACCESSIBILITY_SERVICE` (not declared) | User enables in system Settings; service connected | `DshAccessibilityService` + broker | Request a typed action after session consent |
| Device/Shizuku/root | None (not declared) | Separate external grant | Not in V1 | None in V1 |

Ownership rules:

- **Declarations** are app-level. The feature module stays permission-free
  (`feature/dsh-workspace/src/main/AndroidManifest.xml:2-14`).
- **Runtime permission requests** must come from a visible Activity and a user gesture. The
  broker must not pop a permission dialog from a background Node call. If no UI is available,
  return `USER_PRESENCE_REQUIRED` or `PERMISSION_DENIED`.
- **Consent UI** is app-owned (a small dialog/surface in the app shell), not a DSH plugin and not
  `MainViewModel`. A consent request carries only the capability, a bounded reason, and the
  target summary; it must not carry a token or raw path.
- **Dangerous device actions** require per-session enablement plus per-action confirmation for
  mutating actions (tap/swipe/type/key), target verification, and a rate limit. Read-screen and
  screenshot require explicit consent and must redact password fields and sensitive text.
- **Accessibility is opt-in and revocable.** When the service disconnects or the user disables
  it, the broker transitions to `NOT_ENABLED` and rejects all subsequent actions; in-flight
  actions are completed or rejected, never replayed.
- **Shizuku/root is not in V1.** If later required, it must be a separate capability family with
  its own user grant, per-command approval, and a clear "not available on ordinary devices"
  state. It must never be a default dependency or a silent fallback.

### 6.6 File staging and path rules

The broker, not the DSH plugin, owns `ContentResolver` and SAF:

1. `pickFiles` launches the system picker from the app's Activity host while the app is
   foreground. The page's existing `onShowFileChooser` path remains untouched for Web UI uploads.
2. The broker copies each selected document into
   `files/dsh/workspace/inbox/<generation>/<requestId>/<sanitized-name>`, with a per-file size
   cap and a per-request file-count cap. It never returns the raw `content://` URI or an
   app-private path.
3. A `DshDocumentRef` identifies the original document for audit/result purposes; the staged
   relative path is what Node tools read.
4. `saveFile` accepts only a relative path under `DSH_WORKSPACE` or bounded inline bytes. The
   broker canonicalizes the path, rejects absolute paths, `..`, symlinks, and paths outside the
   workspace root, then writes through `ContentResolver.openOutputStream` to the user-picked
   destination.
5. Staged files are cleaned up on generation stop, a bounded TTL, or an explicit request. No
   silent overwrite of an existing workspace file.
6. `maxBytesPerFile` cannot exceed `MAX_STAGED_FILE_BYTES`; `InlineBytes` cannot exceed
   `MAX_INLINE_BYTES`; excessive input returns `TOO_LARGE` before any write.

### 6.7 Device action policy

If accessibility is implemented, V1 should be deliberately narrow:

- `ReadScreen`: bounded text (`MAX_SCREEN_TEXT_CHARS`), password fields redacted, no raw
  `AccessibilityNodeInfo` to DSH.
- `Tap`/`Swipe`: coordinates bounded to the current display; duration bounded; target package
  and window checked before and after; `TARGET_CHANGED` on drift.
- `PressKey`: only `BACK`, `HOME`, `RECENTS`, `ENTER`, `MENU`; no power/volume/secret codes.
- `TypeText`: only into a focused editable field; reject password fields; no shell/IME
  injection.
- `Screenshot`: accessibility screenshot or a consented MediaProjection; staged as a PNG with a
  size cap; no raw bitmap over the wire.
- Deny-list: system permission dialogs, package installer/uninstaller, payment apps, and
  settings screens that grant permissions. The broker must reject actions whose target is a
  sensitive system surface.
- No background automation: require app foreground, screen unlocked, and an active session
  grant. Screen lock, app background, or service disconnect rejects the action.

These are product/security rules, not just implementation details; they belong in the contract
doc and tests even if the first implementation only ships file/notification capabilities.

### 6.8 Integration points and explicit non-coupling

Recommended placement:

| Concern | Placement | Why |
| --- | --- | --- |
| Typed contract | `core:dsh-contract` (new `capability` package/file) | Shared, platform-free, under the 300-line contract budget |
| Wire/auth loopback server | new `:api:dsh-capability` (preferred) or a thin app-owned adapter | Protocol adapter; mirrors `:api:dsh-gateway`; no UI/engine deps |
| Broker/owner (generation, grants, consent, rate limit, audit) | app/platform layer | Owns Android authorities and lifecycle |
| Activity Result host | app, registered from `MainActivity`/composition | Only an Activity can own Activity Result contracts |
| Notification/accessibility adapters | app/platform layer | Own `NotificationManager`/`AccessibilityService` |
| Endpoint carriage | `DshRuntimeIpc` + `DshRuntimeOwner.Deps` + `DshNodeCommand.environment` | Runtime host seam; no new MainViewModel path |
| DSH-side tools | first-party Cordis plugin, pinned/staged | Registers tools; no Activity/MainViewModel |

Explicitly do **not**:

- add capability methods to `MainViewModel` or fields to `MainUiState`;
- add capability methods to `MainCoordinatorHost`;
- pass `Any?`, a `Map<String, Any>`, `Context`, `Activity`, `Uri`, or `UiStateStore` into the
  contract;
- put capability routes into `api:dsh-gateway` or reuse `dshg_` for capabilities;
- inject a JavaScript interface into the DSH WebView (the module's "No JS bridge" rule stays:
  `README.md:122`);
- let `feature:dsh-workspace` import `com.muyuchat.mca.*` or construct the capability server
  (Rule 16);
- enable the reference `tool-android`/`tool-accessibility`/`tool-shizuku` inserts before the
  app-side contract exists.

### 6.9 DSH-side plugin

The DSH side needs exactly one first-party adapter plugin (for example
`@muyuchat/dsh-capability-bridge`), pinned and shipped with the DSH payload:

- It reads `MCA_DSH_CAPABILITY_URL`, `MCA_DSH_CAPABILITY_TOKEN`, and
  `MCA_DSH_RUNTIME_EPOCH` from the environment. It never hard-codes 3081/3181 and never uses
  `APP_LOCAL_TOKEN`.
- On startup it calls `GET /v1/capabilities`. It registers a tool only when the corresponding
  state is `GRANTED`; `NOT_DECLARED`/`NOT_ENABLED`/`DENIED` tools are not registered, so the
  model does not repeatedly call a dead capability. The plugin may expose one small
  `capability_status` tool that explains how to enable a capability, but it must not pretend the
  action worked.
- Tool names should be stable and model-facing, e.g. `file_pick`, `file_save`, `notify_user`,
  `device_action`; descriptions must state the user-consent and foreground requirements.
- It sends typed JSON, checks `ok`/`error.code`, and returns the typed result to DSH. It never
  returns raw HTML/HTTP errors or secrets.
- It is a thin transport adapter. It must not implement Android policy, file copying, or
  accessibility logic; those are broker/server responsibilities.
- Packaging must be reproducible (source, version, hash, license) and installed into the DSH
  profile under MCA control. If the profile cannot yet install a local plugin, that packaging gap
  is a blocker for the tool surface, not a reason to enable the reference tools.

### 6.10 V1 non-goals

- Generic shell/`exec`/reflection bridge, arbitrary file read/write, arbitrary `Intent` dispatch.
- Shizuku/root by default; virtual-screen/`vscreen`; ADB/desktop control.
- Arbitrary accessibility automation, background screen control, or password-field reading.
- A JS bridge for `blob:` downloads; the existing rejection stays
  (`DshDownloads.kt:48-58`, `README.md:248-249`).
- Reusing the DSH Web cookie or model gateway token for Android actions.
- Per-plugin cryptographic identity; the same-UID trust boundary is explicit.

## 7. Verification plan (all NOT_RUN here)

No build, unit test, instrumentation, DSH runtime, or device was run for this audit. The
following is the minimum future verification, split by layer.

### 7.1 Static

- `python tools/architecture/check_architecture_rules.py`; confirm Rule 15 (contract purity) and
  Rule 16 (workspace boundary) still pass.
- Diff check: no `MainViewModel`/`MainUiState`/`MainCoordinatorHost` capability additions; no
  `Any?` injection; no new JS interface; no capability routes in `api:dsh-gateway`.
- Manifest check: `feature:dsh-workspace` still declares nothing; app-level notification and
  (if implemented) accessibility declarations are explicit and non-exported.

### 7.2 JVM / contract

- Wire codec round-trips for every request/result/error; strict JSON; no unknown fields silently
  accepted.
- Token generation prefix/length, constant-time comparison, redaction, no token in
  `toString`/logs/errors.
- Generation mismatch, duplicate `requestId`, pending duplicate, cancel, timeout, and
  cancellation races.
- File path validation: absolute, `..`, symlink, workspace escape, oversized inline bytes,
  oversized staged file, invalid MIME, file count.
- Notification idempotency: duplicate request does not post twice; permission-denied maps to
  `PERMISSION_DENIED`; channel disabled maps to `NOT_ENABLED`/`DENIED`.
- Device action target drift: package/window mismatch returns `TARGET_CHANGED` before any
  action; rate limit returns `RATE_LIMITED`; service disconnect returns `NOT_ENABLED`.

### 7.3 Device / end-to-end (not run)

- Upload/pick: single, multiple, cancel, provider with no display name, large file, MIME filter,
  app background during picker, Activity recreation.
- Save: SAF create, overwrite handling, cancel, large workspace file, inline bytes cap.
- Notification: permission grant/deny on API 33+, channel disabled, duplicate request, deep link
  opens the DSH tab without a token in the Intent.
- Accessibility: enable/disable service, target app/window change, screen lock, background,
  screenshot consent, password field redaction.
- Security: unauthenticated/wrong-token request rejected; model token rejected on capability
  routes; capability token rejected on model routes; `Origin`/non-loopback Host rejected; logcat
  and crash report contain no token or raw `content://` URI.
- Lifecycle: runtime restart rotates the token and cancels pending requests; app process death
  makes capability calls fail closed; DSH host remains alive but no tool pretends success.

Results must be recorded as `PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE` with the exact
source revision, APK, device, and evidence. This audit's result for all runtime items is
`NOT_RUN`.

## 8. Top 3 concrete gaps and recommended order

1. **No capability transport or contract exists.** The `:dsh` IPC is lifecycle-only
   (`DshRuntimeIpc.kt:21-30`; `DshRuntimeService.kt:113-200`), and the model gateway is
   model-only (`DshGatewayServer.kt:228-248`, `:460-471`). There is no capability token, no
   endpoint, no request/response, no cancellation, and no discovery. This blocks every other
   capability. First slice: add the typed contract in `core:dsh-contract`, a fake port with JVM
   tests, and an authenticated loopback server with a distinct `dshc_` token/generation.

2. **The file picker/save implementation is page-local and cannot be called by DSH tools.**
   `DshFileChooserCoordinator` is an internal Compose-state helper
   (`DshWebDialogsAndFiles.kt:25-50`), launched by `rememberLauncherForActivityResult` inside
   `DshWebViewHost` (`DshWebViewHost.kt:108-114`, `:116-138`). It has no typed file port, no
   staging, no path validation, no persisted URI policy, and no foreground/user-presence
   contract. Second slice: extract the Activity Result host into the app layer, implement
   `pickFiles`/`saveFile` with workspace staging and strict path checks, and keep the existing
   WebView page path unchanged.

3. **Notifications and accessibility/device actions have no DSH-facing API or permission
   ownership.** `POST_NOTIFICATIONS` is declared but never requested/checked
   (`app/AndroidManifest.xml:9`; `core/dsh-runtime/AndroidManifest.xml:7`; zero permission-request
   hits), and the only DSH notification is the FGS status notification
   (`DshRuntimeNotifications.kt:13-19`, `:35-59`). There is no `AccessibilityService`, no
   accessibility config, no Shizuku/device-action adapter, and the payload's reference Android
   tools expect missing `APP_NOTIFY_PORT`/`APP_A11Y_PORT` services
   (`payload.zip!.../dsh-tool-android/lib/index.js:65-66`;
   `.../dsh-tool-accessibility/lib/index.js:3`, `:20`). Third slice: add a notification sink with
   runtime permission state/request from a visible Activity; either implement a narrowly scoped
   accessibility adapter with explicit consent/target checks, or declare it out of V1 and keep
   the reference tools disabled. Do not enable the reference Android tool inserts as a shortcut
   (`DshGatewayConfigSeeder.kt:21-24`; `DshGatewayConfigSeederTest.kt:64-65`).

Recommended order: contract + fake port + wire/auth server; file pick/save + staging; notification
sink + permission ownership; accessibility/device actions (optional V1); DSH-side plugin
packaging; device verification. The first two slices are independent of the accessibility
decision and deliver the highest user-visible value with the least new authority.

## Appendix A: Evidence index

Runtime and contract:

- `core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/DshRuntimeContract.kt:74-126`
- `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt:31-84`,
  `:149-156`, `:252-290`
- `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeService.kt:29-41`,
  `:63-80`, `:113-200`, `:245-269`
- `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeIpc.kt:21-30`,
  `:47-60`, `:92-119`
- `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeServiceClient.kt:33-46`,
  `:126-148`, `:258-283`
- `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt:48-75`

Model gateway and auth:

- `api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewayServer.kt:27-41`,
  `:92-120`, `:162-165`, `:228-248`, `:389-394`, `:460-471`
- `api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewaySecret.kt:7-34`
- `api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewayHttp.kt:32-38`,
  `:92-98`, `:183-198`
- `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/auth/DshAuthEntryParser.kt:5-18`,
  `:43-63`, `:81-85`
- `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/DshReadinessGate.kt:6-24`,
  `:85-105`, `:162-169`

Workspace and platform interactions:

- `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceRoute.kt:44-58`,
  `:68-100`, `:136-152`
- `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceViewModel.kt:57-72`
- `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewHost.kt:47-62`,
  `:64-70`, `:108-138`, `:164-179`
- `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebDialogsAndFiles.kt:16-50`,
  `:69-84`
- `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebChromeClient.kt:23-38`,
  `:53-68`
- `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewFactory.kt:13-30`,
  `:45-81`
- `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewClient.kt:19-35`,
  `:129-133`
- `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshDownloads.kt:48-58`
- `feature/dsh-workspace/README.md:47`, `:122`, `:142-152`, `:243-249`
- `feature/dsh-workspace/src/main/AndroidManifest.xml:2-14`
- `feature/dsh-workspace/build.gradle.kts:46-52`

Permissions and composition:

- `app/src/main/AndroidManifest.xml:4-10`, `:44-182`, `:184-192`, `:194-203`
- `core/dsh-runtime/src/main/AndroidManifest.xml:4-7`, `:10-25`
- `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeNotifications.kt:13-19`,
  `:35-59`
- `app/src/main/java/com/muyuchat/mca/agent/platform/AgentRunNotificationController.kt:183`,
  `:235-236`
- `app/src/main/java/com/muyuchat/mca/McaApplication.kt:12-47`, `:63-76`
- `app/src/main/java/com/muyuchat/mca/MainViewModel.kt:53-58`, `:67-83`, `:101-112`
- `app/src/main/java/com/muyuchat/mca/MainActivity.kt:180-187`, `:391-407`
- `app/src/main/java/com/muyuchat/mca/composition/MainRuntimeAssembly.kt:106-144`
- `app/src/main/java/com/muyuchat/mca/composition/DshGatewayAssembly.kt:13-24`, `:36-65`
- `app/src/main/java/com/muyuchat/mca/composition/DshModeAssembly.kt:13-58`

Seeder and architecture rules:

- `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt:12-24`,
  `:41-46`, `:50-87`, `:153-186`, `:211-217`
- `core/dsh-runtime/src/test/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeederTest.kt:64-65`
- `tools/architecture/check_architecture_rules.py:50-56`, `:386-417`, `:420-445`

Intent documents:

- `docs/adr/0003-dsh-embedded-dual-agent-modes.md:31`, `:45-53`, `:129`, `:132`
- `docs/proposals/agent-dsh-embedded-v1/ARCHITECTURE.md:78`, `:156`, `:164`, `:168-175`
- `docs/proposals/agent-dsh-embedded-v1/EXECUTION_PACK.md:73-78`
- `docs/proposals/agent-dsh-embedded-v1/DSHA_MULTI_PROJECT_REVIEW_20261019.md:84-90`

## Appendix B: Reference payload observations

These are read-only archive observations, not MCA active configuration. They are included
because they show the reference tool contract that MCA currently does not satisfy.

- `payload.zip!dshhome/cordis.patch.yml:59-79` inserts the reference Android tools and a
  `danger-full-access`/`approval: never` default (`:55-58`); MCA's seeder does not copy this
  file and writes its own `workspace-write`/`approval: ask` overlay
  (`DshGatewayConfigSeeder.kt:153-186`).
- `payload.zip!dshhome/profiles/web/package.json` bundles only `@deepseek-ai/dsh-base` and
  `@deepseek-ai/dsh-web-app`; it does not bundle the Android tool packages by default.
- `payload.zip!.../dsh-tool-android/lib/index.js:65-66`, `:102-119` expects an app local HTTP
  service on `APP_NOTIFY_PORT` (default 3081) with `X-DSH-Token`.
- `payload.zip!.../dsh-tool-accessibility/lib/index.js:3`, `:20`, `:32-37` expects an app
  `AccessibilityService` HTTP service on `APP_A11Y_PORT` (default 3181).
- `payload.zip!.../dsh-tool-shizuku/lib/index.js:171-191`, `:409-461` expects `/shell` and
  `/notify` on the app service.
- `payload.zip!.../dsh-tool-vscreen/lib/index.js:150-153` expects `/vscreen/status`.
- MCA source has no `APP_NOTIFY_PORT`, `APP_A11Y_PORT`, `APP_LOCAL_TOKEN`, `X-DSH-Token`, 3081,
  or 3181 reference, and no app-side routes for those endpoints.

## Appendix C: Search method and limitations

Searches included, at minimum:

- `DshCapability`, `CapabilityPort`, `CapabilityBroker`, `CapabilityServer`,
  `CapabilityEndpoint`, `CapabilityState`;
- `file picker`, `FileChooser`, `OpenDocument`, `CreateDocument`, `ContentResolver`,
  `takePersistableUriPermission`;
- `notification`, `NotificationChannel`, `POST_NOTIFICATIONS`, `areNotificationsEnabled`,
  `RequestPermission`, `requestPermissions(`, `checkSelfPermission`;
- `AccessibilityService`, `BIND_ACCESSIBILITY_SERVICE`, `MediaProjection`, `SYSTEM_ALERT_WINDOW`,
  `Shizuku`, `vscreen`;
- `APP_NOTIFY_PORT`, `APP_A11Y_PORT`, `APP_LOCAL_TOKEN`, `X-DSH-Token`, `3081`, `3181`;
- `addJavascriptInterface`, `evaluateJavascript`, `WebMessageListener`.

Limitations:

- The DSH modules are untracked working-tree files; the audit is not reproducible from HEAD
  alone.
- No build was run, so compile-level compatibility of the proposed contract is `NOT_RUN`.
- No device was used, so Android 13+ notification permission behavior, accessibility
  enablement, SAF providers, and process death are `NOT_RUN`.
- The payload archive is reference material and may not be the exact future release; its
  entries are used only to show the expected reference tool contract.
- "ABSENT" is a static source finding, not proof that no code path can dynamically install a
  plugin or request a permission. The source search found none in the requested scopes.

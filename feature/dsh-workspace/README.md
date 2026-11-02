# feature:dsh-workspace

Independent MCA workspace page that hosts the **official DeepSeek Harness Web UI** in a WebView.
It is the R04+R06 UI slice of `docs/proposals/agent-dsh-embedded-v1/`. Kotlin Agent and chat are
untouched; DSH owns its own kernel, frontend, sessions, tools and approvals.

```text
app (composition root, tab shell)                     DSH runtime owner (core:dsh-runtime, not here)
        │  DshRuntimeClient (core:dsh-contract, frozen)          ▲
        ▼                                                        │ Node Web profile
┌───────────────────────────────────────────────┐                │
│ feature:dsh-workspace                         │   loopback http://127.0.0.1:<random port>
│  DshWorkspaceRoute (public)                   │◄── WebView ────┘
│   ├─ ViewModel: host lifecycle, entry, restore│
│   ├─ Shell: starting / failure / exited       │
│   └─ WebView host: origin fence, SAF, download│
└───────────────────────────────────────────────┘
```

This module **does not** implement DSH settings, history, approvals, model picker, plugin manager
or session rendering. Those remain the official page inside the WebView.

---

## 1. Public API

```kotlin
@Composable
fun DshWorkspaceRoute(
    client: DshRuntimeClient,
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
    onOpenExternal: ((Uri) -> Unit)? = null
)
```

- `client` — the frozen `com.muyuchat.core.dsh.contract.DshRuntimeClient` of the single DSH runtime
  owner. The route only calls `snapshot`, `ensureStarted()`, `restart()` and `webEntry()`. It never
  calls `stop()`: leaving the tab or the app must not cancel DSH work (architecture §7).
- `modifier` — applied to the page root. `MainActivity` passes the `SwipeBackPage` page modifier, as
  it does for `AgentWorkspaceRoute`.
- `onExit` — leave the DSH tab (the host's `closePage` / page-exit motion).
- `onOpenExternal` — optional hook for validated external `http(s)` links. When `null` the route
  starts a validated `ACTION_VIEW` Intent itself. When supplied (e.g. MCA's internal browser), MCA
  owns the external page; the DSH cookie is never forwarded because the URL is not the DSH origin.

Everything else (shell, WebView, file picker, downloads, navigation memory) is self-contained.
There is no second runtime, no engine, no Room access and no DSH session model in this module.

## 2. What `app/**` must wire

1. `app/build.gradle.kts` (Lead/integrator only):

   ```kotlin
   implementation(project(":feature:dsh-workspace"))
   ```

   `settings.gradle.kts` already contains `include(":feature:dsh-workspace")`.

2. Create/inject the single `DshRuntimeClient` in the app composition root (it must be the same
   instance used by any DSH notification/stop action). Do not construct a runtime in the route.

3. Render the tab with the existing overlay pattern in `MainActivity.kt`:

   ```kotlin
   CrossfadeTabPage(
       visible = state.tab == AppTab.DSH_WORKSPACE,
       stateHolder = tabStateHolder,
       tabKey = AppTab.DSH_WORKSPACE.name,
       label = "tab-dsh-workspace",
       onDismissStart = { if (appMenuOpen) appMenuOpen = true },
       onDismiss = { onTab(AppTab.CHAT) }
   ) { pageModifier, closePage ->
       DshWorkspaceRoute(
           client = viewModel.dshRuntimeClient,   // app-owned instance name
           modifier = pageModifier,
           onExit = closePage,
           onOpenExternal = null                  // or { url -> internalBrowserUrl = url.toString() }
       )
   }
   ```

   `AppTab.DSH_WORKSPACE` was appended after `AGENT_WORKSPACE` by the integrator; existing ordinals
   are unchanged. The chat tab remains the always-composed base layer.

4. Manifest: **nothing is required from this module** (it declares no permission, activity, service
   or provider). The app already declares `INTERNET` and `android:usesCleartextTraffic="true"`,
   which admits the loopback `http://127.0.0.1:<port>` origin. If cleartext is ever tightened into a
   `networkSecurityConfig`, add a `domain-config cleartextTrafficPermitted="true"` for
   `127.0.0.1` (and only that domain) or DSH cannot load.

5. If the host wants MCA's internal browser for external links, pass `onOpenExternal`; the route
   already validated the URL as external `http(s)` without credentials.

### Explicitly **not** required

- `WRITE_EXTERNAL_STORAGE`: downloads target public Downloads on API 29+ (no permission) and the
  app-specific external files directory on API 26–28. See limitations.
- `CAMERA` / `RECORD_AUDIO`: `onPermissionRequest` is denied by the module. Enabling voice input is
  an app-level product decision and needs a manifest permission plus a deliberate host change; the
  module does not silently grant it.
- FileProvider: no provider is used. `data:` downloads go through SAF `ACTION_CREATE_DOCUMENT`;
  `http(s)` downloads go through `DownloadManager`.
- INTERNET is already app-owned; this library never adds networking of its own.

## 3. Behaviour contract

### Security

- **Exact origin fence.** Only `http`, host `127.0.0.1`, and the exact current random port stay in
  the WebView. `https`, `localhost`, other loopback spellings, other ports, credentials in the URL,
  non-http schemes (`javascript:`, `intent:`, `file:`, `content:`, `blob:`, `data:`) and over-long
  URLs are refused. Subresource/iframe requests are not rerouted (only top-level navigations cross
  the fence); no `shouldInterceptRequest` rewrite exists.
- **Authenticated entry.** `webEntry().authenticatedUrl` (`<origin>/?token=...`) is loaded into the
  WebView exactly once, immediately, and never logged, persisted, saved in instance state, put in
  an Intent or shown in UI text. The official 303 redirect mints an HttpOnly/SameSite=Strict cookie
  and leaves the token URL out of history (`clearHistory()` on the first clean same-origin load).
- **Cookies.** First-party cookies are enabled for the DSH origin; third-party cookies are denied.
  On runtime epoch change the previous origin's cookies are expired explicitly (their random port
  is gone anyway); unrelated WebView cookies are left alone.
- **No JS bridge.** `addJavascriptInterface` is never called. No reflection/`exec` bridge.
- **WebView settings.** JS + DOM storage required by the official SPA; file/content access, mixed
  content, universal file URL access and geolocation are off; password saving is off; WebView remote
  debugging is not enabled. Multiple-window support is on only so the official page's
  `window.open`/`target="_blank"` links reach `onCreateWindow`, which reduces each popup to one
  verified URL and destroys the popup WebView without displaying it (see below).
- **Logs.** Only debug builds read console messages, and every message/URL is redacted through
  `DshUrlRedaction` first (queries and fragments dropped, high-entropy runs replaced).
- **No reimplementation.** The official page's DOM/CSS/interaction is rendered unchanged.

### Shell states

| State | UI |
| --- | --- |
| Starting/attaching | centered progress, "正在启动 DSH", 返回 |
| Start failure | specific `DshFailureCode` title + message + code, 重试/返回 |
| Runtime exited | "DSH 运行时已退出" + 重新启动/返回; restart creates a new epoch/token |
| Web | WebView + thin load progress; recoverable network/HTTP banner with 重新加载 |
| Renderer killed | WebView recreated under a new generation and reloaded at the current clean path |

### Android interactions

- **Back:** fullscreen video → `WebView.canGoBack()` → `onExit`. Same hierarchy as the Kotlin Agent
  workspace, so the host's swipe/predictive-back page exit still works when the WebView is at root.
- **Uploads:** `WebChromeClient.onShowFileChooser` → `FileChooserParams.createIntent()` (the page's
  own `accept`/`multiple` are preserved) → Activity Result contract → URI array back to WebView.
  Cancelling clears the callback; MIME guessing is avoided.
- **Downloads:** DownloadManager for `http(s)` (same-origin cookie forwarded when CookieManager
  exposes it, UA only from the page), SAF create-document for bounded (≤2 MB) `data:` URLs.
  `blob:` cannot be fetched without a JS bridge and is rejected with an explicit message.
- **JS dialogs:** `alert`/`confirm`/`prompt` rendered as MCA Material dialogs (`JsResult`/`JsPromptResult`
  confirmed/cancelled exactly once).
- **Permissions:** camera/mic denied (no manifest permission); geolocation denied.
- **Fullscreen video:** `onShowCustomView` shell; back exits fullscreen first; `onHideCustomView`
  releases it.
- **Keyboard/insets:** `imePadding()` on the page slot, matching the chat composer convention.
- **Dark mode:** not force-darkened. The app's `values-night` theme makes WebView report the dark
  `prefers-color-scheme`, so the official page renders its own dark design; the outer shell uses
  `McaTheme` colours.
- **Font scale:** `textZoom` is never set. Chromium WebView applies the system font scale by default
  (overriding it would break 2.0x accessibility text). Pinch zoom remains available.
- **Narrow screens:** the official page declares `width=device-width`; the host keeps
  `useWideViewPort=true`, now enables `loadWithOverviewMode=true` (see *Mobile adaptation* below),
  and leaves pinch zoom enabled.
- **External links:** the official page opens links with `window.open(url, '_blank', ...)` and
  `<a target="_blank">` (`ui-chat/apply.ts`, `ui-primitives/WebBlock.tsx`, account/plugin-market
  links). Android WebView silently drops those unless `setSupportMultipleWindows(true)` is combined
  with `WebChromeClient.onCreateWindow`, so both are wired: each popup is reduced to a single URL,
  classified by the same fence (external `http(s)` → `onOpenExternal`/`ACTION_VIEW`; same-origin →
  main WebView; everything else blocked with a notice) and its temporary WebView is destroyed
  immediately. No popup content is ever rendered or persisted.

### Official DSH page assumptions (verified against the pinned `687f282e` source)

| Assumption | Evidence (official source) | Container handling |
| --- | --- | --- |
| First-party loopback auth cookie, HttpOnly + SameSite=Strict, `Path=/`, named `dsh-auth-<sha256(authority)>` | `packages/client/connection/src/browser-auth.ts:252-302` | WebView first-party cookies on, third-party off; cookie is never read or copied into app state |
| Token entry is exactly `http://127.0.0.1:<port>/?token=…`; only `pathname === '/'` mints the cookie and answers `303 Location: ./` | `browser-auth.ts:241-302` | Entry loaded verbatim once; clean redirect detected; token URL dropped from state and WebView history |
| `<base href="./">` is inserted ahead of every injected resource row; plugin assets are document-relative (`plugins/...`) | `packages/host/frontend-static/src/index.ts:113-121`, `packages/client/modules/src/index.ts:479` | No base/URL rewriting and no `shouldInterceptRequest`; relative plugin/asset requests load from the same origin |
| `window.__DSH_BOOT__` graph + `__DSH_BOOT_READY__` deferred are server-injected; the shell awaits them before rendering the app | `packages/client/modules/src/index.ts:592`, `packages/host/webserver/src/injections.ts:79-86`, `apps/web/src/main.ts:22` | WebView executes the served HTML/JS unchanged; no native boot gate is required (the page renders its own boot failure if boot rejects) |
| Remote stream transport is one same-origin WebSocket at `/api/remote.mux`; it uses the same auth cookie | `packages/api/gateway/src/stream-protocol.ts:7`, `packages/client/connection/README.md:34` | Not a navigation: the origin fence and `shouldInterceptRequest` do not touch it; first-party cookie is sent by WebView on the same-origin WS handshake |
| Attachments are a hidden `<input type="file" multiple>` | `packages/client/ui-conversation/src/client/skeleton/InputBar.tsx:456-463` | `onShowFileChooser` uses the page's `createIntent()` (MIME/multiple preserved) and delivers SAF results back |
| Unknown non-index paths return 404 (only `/` or the configured index path serves HTML) | `packages/host/frontend-static/src/index.ts:93-121` | A restored non-root path 404s and the ViewModel falls back to `/` once (no broken base-href page) |
| Deliverable downloads use the same-origin `/api/present.download` route | `packages/client/ui-deliverables/tests/present-open.host.spec.ts:69` | DownloadManager path; HttpOnly cookie exposure is a device NOT_RUN (see below) |

### Mobile adaptation (Android WebView)

The official DSH frontend is a desktop-first responsive SPA. MCA does not fork or rewrite its
HTML/CSS/JS; this section records the reference Android project's approach, what MCA currently
does, and what remains unverified.

**Reference Android project** (`deepseek-harness-android-app`):

- Injects `mobile.css` + `mobile.js` into the DSH dist `index.html` via
  `mobile-patch/inject.sh` (same-origin `<link>`/`<script>`; no rewrite of the official
  `index.html` beyond that). The CSS uses class-name-robust `[class*="_name_"]` selectors,
  touch/tap sizing, portrait overflow fixes, a fixed plugin badge and narrow-screen sidebar
  layout; the narrow sidebar itself is a separate core-source patch (hamburger + overlay).
- Patches the viewport meta to `maximum-scale=1.0, user-scalable=no` and blocks multi-touch
  pinch events because `setSupportZoom(false)` was found unreliable; the viewport declaration is
  treated as authoritative.
- `mobile.js` implements keyboard "fixed layer" compensation with `visualViewport`
  (`--kb-height`, rAF-throttled `translateY` of the app container) and anti-auto-focus logic so
  switching sessions does not pop the keyboard.
- Chooses the workspace root in its own native console via SAF `ACTION_OPEN_DOCUMENT_TREE`
  (`REQ_WORKSPACE_TREE`) and passes the path to the engine; there is no web-side directory-picker
  bridge, and the official web directory-picker slot is not used.

**MCA now**:

- No `mobile.css`/`mobile.js` injection and no viewport `user-scalable` patch; the official page
  is rendered unchanged and pinch zoom remains available.
- WebView: `useWideViewPort=true` and `loadWithOverviewMode=true`. Overview mode was enabled
  after observing (through the WebView DevTools protocol) a state where `window.innerHeight`
  reported the real viewport but `100vh` resolved against a zero-height initial containing block
  and the main pane stayed blank. Risk: for content wider than the screen, overview mode may
  scale the layout down. The official page declares `width=device-width`, so this is expected to
  be a fallback only; device visual check is NOT_RUN.
- `web/DshPageLayoutShim.kt` compensates at the layout level without a DOM fork:
  - writes the measured viewport height (`max(window.innerHeight, documentElement.clientHeight)`)
    as an explicit inline height on `<html>`, restoring the official
    `html, body, #root { height: 100% }` chain;
  - walks same-origin stylesheets recursively (`@media`, `@supports`, `@layer`, keyframes) and
    rewrites `vh`/`dvh` declarations to pixel values; the original `CSSStyleDeclaration.cssText`
    is kept per `CSSStyleDeclaration` in a `WeakMap`, so every reapply (rotation/resize) works
    from the original declaration rather than a previously rewritten one;
  - skips cross-origin sheets when `cssRules` throws and skips individual unreadable rules;
  - runs on page start, on commit-visible (only when the displayed document is the clean official
    page), and on a clean same-origin page finish; re-runs on `resize`, `orientationchange` and
    `visualViewport.resize`; initial retries at 120 ms / 600 ms. Event listeners are installed at
    most once per document (`window.__mcaDshViewportShim`); no bridge is added and no cookie/token
    is read or written.
- Workspace directory: the generated DSH overlay (owned by `core:dsh-runtime`, not this module)
  pins `workspace-controller.documentsDirectory` to MCA's `files/dsh/workspace`, so the official
  first-use flow creates `…/deepseek-harness/default-workspace` without a native directory picker.
  MCA has no directory-picker bridge and does not patch official UI labels.

**Review findings and residual risks (NOT_RUN on device)**:

- *Memory/listeners:* `WeakMap` keys are `CSSStyleDeclaration` objects owned by their stylesheets,
  so entries are collectible with the sheet; one listener set per document, no per-apply
  listeners or timers. No leak found by inspection.
- *Rewrite coverage:* `@import`-ed sheets, `adoptedStyleSheets`, shadow-root styles, inline
  `style="…vh…"`, CSS nesting inside a `CSSStyleRule` (the walk takes the `rule.style` branch and
  does not descend into its nested `cssRules`), `svh`/`lvh`/`cqh`, uppercase units and viewport
  units in media-query conditions are not rewritten. The pinned official CSS uses lowercase
  `vh`/`dvh` in declarations and has no nesting/imports/adopted sheets/shadow DOM, so these are
  latent gaps rather than observed breakage.
- *Late CSS:* a stylesheet injected after the last apply (lazy plugin/menu CSS, theme swap) is not
  rewritten until the next resize/reapply. This is the known "model menu clipped only if vh
  rewrite fails" path: `ui-model-selection/ModelSelect.module.css` uses
  `max-height: min(360px, calc(100vh - 96px))`. Proposed improvement: a debounced
  `MutationObserver` for new `<style>`/`<link>` nodes plus rAF/debounce on resize.
- *Page-driven CSSOM mutation:* originals are snapshotted once; if the page later mutates the
  same rule style, the next apply rewrites from the stale original. The official UI toggles
  classes/custom properties instead of editing rule objects, so this is latent; storing
  `{original, written}` and treating an external change as a new original would make it safe.
- *Layout-corruption risk of rewriting official CSS:* `cssText` round-tripping normalizes
  declarations, freezes `dvh`/animation vh to the last measured pixels, and the inline `<html>`
  height overrides page-provided heights. The pinned page renders with this shim, but there is no
  per-rule visual regression evidence; any future official CSS that relies on live `dvh` or
  mutates rule objects should be re-reviewed before keeping the rewrite.
- *Keyboard/IME:* Compose `imePadding()` resizes the WebView; the shim also listens to
  `visualViewport.resize`, but whether the pixel height and official composer track the IME
  animation correctly is NOT_RUN.
- *Safe area/cutout/gesture insets:* MCA relies on Compose scaffold insets; the official page is
  not patched with `env(safe-area-inset-*)`, so layout under cutouts/gesture bars is NOT_RUN.
- *Rotation/orientation:* correctness depends on `resize`/`orientationchange` delivery and event
  ordering; there is no explicit post-orientation settle timer beyond the initial 120/600 ms
  retries. Portrait/landscape/tablet checks are NOT_RUN.
- *Known product gaps outside this module:* local models run with an 8k context
  (`nCtx=8192` in device regression evidence), and the pinned default workspace can surface an
  opaque/UUID label in the official sidebar because the official workspace-controller derives it
  from the pinned directory. MCA does not patch either in this slice.

This review is docs-only: no production edit was justified (no reproducible defect with a minimal
safe fix); the module compile/test result from the previous slice remains applicable.

### Navigation restore

- Only the last clean same-origin **path** is written to `files/dsh/nav/last_path.txt` (versioned
  `dsh-nav-v1` header, atomic temp+rename, ≤1 KB). No query (therefore no token), no origin, no
  DSH session content, no Kotlin Agent/chat state.
- Restore sequence: load the fresh authenticated entry → wait for the clean cookie page → load
  `origin + lastPath` once. If the official static fallback answers 404/error for that path, the
  page returns to `/` once and the stored path is corrected. `/` restores as no-op.
- Tab identity itself is app navigation state (`AppTab.DSH_WORKSPACE`), not this module's state.

## 4. Module layout

```text
DshOrigin.kt                     exact scheme/host/port origin value + same-origin fence
DshNavigationPolicy.kt           navigation classification, restore-path sanitization, token detection
DshUrlRedaction.kt               log-safe URL/text rendering
DshFailureProjection.kt          every DshFailureCode -> specific prompt-free Chinese message
DshLoadPlanner.kt                pure start/load decision (attach/restart, token vs cookie resume)
DshNavigationStore.kt            files/dsh/nav codec + atomic file store
DshDownloads.kt                  pure download policy, filename sanitization, data: URL decoding
DshCookiePurge.kt                pure cookie-expiry construction
DshWorkspaceViewModel.kt         host lifecycle owner, READY/recovery/epoch projection, restore
DshWorkspaceRoute.kt             public Route: ViewModel wiring, external Intent, BackHandler
DshWebHostCallbacks.kt           typed event surface between WebView and Route/ViewModel
DshFirstFrameGate.kt             pure first-frame acceptance policy for WebView page events
DshFirstFrameReporter.kt         observation edge that records the first visible READY frame
ui/DshWorkspaceScreen.kt         stateless outer shell (starting/failure/web)
web/DshWebViewHost.kt            Compose WebView host, SAF launchers, fullscreen overlay, JS dialogs
web/DshPageLayoutShim.kt         html-height + same-origin CSSOM vh/dvh compensation (mobile)
web/DshWebViewFactory.kt         WebSettings/cookie configuration
web/DshWebViewClient.kt          origin fence, error/HTTP/renderer callbacks, history purge
web/DshWebChromeClient.kt        progress, file chooser, dialogs, permissions, popup interception, console
web/DshWebDiagnostics.kt         debug-only WebView console/probe instrumentation
web/DshWebViewDialogsAndFiles.kt file-chooser coordinator/contract, JS dialog controller
web/DshWebViewController.kt      back hierarchy + fullscreen controller
web/DshDownloader.kt             DownloadManager request construction
web/DshWebStorage.kt             per-origin cookie expiry on epoch change
```

File budgets follow the hard-lock review budgets: Route ≈170 lines, ViewModel ≈520 (page state
holder band 500–600), WebView host ≈350, layout shim ≈100, each pure policy file well under 300.

## 5. Verification

Executed (this workspace, 2026-10-20):

```powershell
$env:JAVA_HOME='E:\model\toolchains\jdk-21\jdk-21.0.12.1+1'
Import-Module E:\MAC\tools\build-guards\McaBuildLock.psm1
$lock = Enter-McaBuildLock -Kind test -CommandLine '...' -RetrySeconds 3600 -RetryIntervalSeconds 15
try { .\gradlew.bat :feature:dsh-workspace:testDebugUnitTest --console=plain }
finally { Exit-McaBuildLock -Lock $lock }
```

Result: **BUILD SUCCESSFUL — 10 suites, 57 tests, 0 failures, 0 skipped**, including the
`DshWorkspaceViewModelTest` cases for external STARTING→READY recovery, external startup
failure/STOPPED recovery, stale start result reconciliation, and epoch isolation:
`readySnapshotDoesNotCompeteWithAnActiveStartForTheEntry`,
`repeatedReadySnapshotsAndPageReentryShareTheActiveGenerationLoad`,
`externalStartupFailureLeavesLoadingAndCanBeRetried`,
`failedGenerationRejectsAnEntryReturnedAfterCancellation`,
`oldGenerationEntryFailureCannotReplaceTheNewOfficialPage`,
`newerReadyGenerationSupersedesAnInFlightEntryWithoutStartingSnapshot`,
`externalRestartReturnsFromStartingToOfficialPage`,
`staleStartResultReconcilesTheLatestReadyGenerationWithoutConsumingOldEntry`,
`externalStartupEndingStoppedShowsAnExitInsteadOfLoadingForever`.
See `E:\tmp\dsh-worklogs\dsh-workspace.md` for the exact run results.

The module was also compiled in the app `:app:assembleRelease` configuration by the integrator
(release Kotlin classes regenerated successfully). JVM unit tests cover:

- origin parsing and exact same-origin fencing (`DshOriginTest`),
- navigation classification, restore-path validation, token detection, restore decisions
  (`DshNavigationPolicyTest`),
- URL/text redaction (`DshUrlRedactionTest`),
- every `DshFailureCode` projection (`DshFailureProjectionTest`),
- token-vs-cookie load planning and start policy (`DshLoadPlannerTest`),
- navigation file codec and in-memory store (`DshCookiePurgeTest` contains
  `DshNavigationCodecTest`),
- download classification/filename/data-URL policy (`DshDownloadsTest`),
- WebView error-code classification (`DshWebErrorClassifierTest`),
- host READY/recovery/epoch-isolation state transitions (`DshWorkspaceViewModelTest`).

### NOT_RUN (no device verification in this task)

These require a real device with a working DSH runtime payload and are **not** claimed as passed:

- official page rendering/visual comparison (light/dark, 2.0x font, narrow portrait, landscape,
  tablet width),
- real authenticated launch-token → cookie handoff against `dsh --profile web`,
- same-origin authenticated download (depends on whether the platform exposes the HttpOnly DSH
  cookie to `CookieManager.getCookie()`; if it does not, DownloadManager receives 401 — the
  fallback would be a host-side export/SAF route, not a JS bridge),
- upload/attachment flows with real file providers, multi-select, SAF cancellation,
- JS dialog flows used by the official frontend, fullscreen media,
- renderer-process-gone recreation (`chrome://crash` style), network loss/reconnect, process death
  and re-attach to the same runtime generation,
- the `window.open`/`target="_blank"` popup path on a real device (the interception code compiles
  and is reviewed against Chromium's new-window contract, but no device was driven),
- cookie/epoch purge observation, external Intent handoff to a real browser,
- WebView version compatibility (the pinned official boot tail uses `Promise.withResolvers` and a
  modern Chromium; the container does not version-gate, so the acceptance device needs an up-to-date
  Android System WebView),
- Compose UI test of the shell (no app module / device in this slice; androidTest not authored),
- accessibility (TalkBack/focus) inside the official WebView.

Also out of scope by design (not failures of this module): DSH Node execution, payload staging,
model gateway, DSH sessions/tools/plugins, and the runtime's own process lifecycle.

## 6. Known limitations / decisions to revisit

1. **`blob:` downloads** are refused. Supporting them requires a minimal, origin-verified JS
   download bridge; it is intentionally not part of this slice.
2. **Public Downloads on API 26–28** are not used because that would need `WRITE_EXTERNAL_STORAGE`.
   On those devices a download lands in the app-specific external files directory and the snackbar
   says so. Adding the permission is an app-level decision (runtime request + UX) not taken here.
3. **Camera/microphone** are denied. Voice features require an app permission plus an explicit
   `onPermissionRequest` policy; `CAMERA`/`RECORD_AUDIO` are deliberately absent.
4. **Non-http(s) external schemes** (`mailto:`, `tel:`, `intent:`) are blocked per the task's
   “ACTION_VIEW with http/https only” rule; they can be added later with an explicit scheme policy.
5. **Iframes / external subresources** are not individually rerouted; the fence governs top-level
   navigation. Third-party cookies are off, limiting cross-site state.
6. **No custom User-Agent** (keeps the official responsive layout honest). Fixing to a desktop UA
   for narrow-screen rendering is a separate, evidence-based mobile patch.
7. **Fullscreen video** hides inside the app page but does not force-hide system bars.
8. **Epoch cookie purge is session-local and origin-scoped.** After an app-process restart the
   in-memory “previous origin” is gone; stale cookies for the dead random port are unusable but
   remain until WebView cleanup. The new generation always gets a new random port/cookie, so a purge
   is keyed on the previous origin (a same-port epoch reuse is vanishingly unlikely and would keep
   the profile's own valid cookie).
9. **Same-generation re-attach** relies on the frozen client contract: if `webEntry()` returns null
   after process death, cookie resume is attempted, and a 401 leads to an explicit 重新连接
   (restart/new epoch) rather than reusing a consumed token.
10. **Popup semantics**: `window.open`/`target="_blank"` is intercepted as one URL (external →
    Android; same-origin → main WebView; other → blocked) and the popup is never displayed. The
    official page uses popups only to open external links, so this matches its intent; a future
    plugin that truly needs a second visible window would require a product decision.
11. **Starting has no client-side timeout.** If the runtime owner reports `STARTING` and then never
    emits `READY`/`FAILED`, the shell stays on “正在启动 DSH” with only 返回. The host owner's own
    readiness/timeout fences are the authoritative bound; the workspace does not invent a second
    timeout. This is only reachable if the runtime owner violates its contract.
12. **Mobile adaptation** is documented in §3 *Mobile adaptation (Android WebView)*: the CSSOM
    vh/dvh shim's coverage limits (late CSS, @import/shadow/adopted sheets, nested CSS, inline
    styles, svh/lvh), the overview-mode downscale risk, and the remaining keyboard/safe-area/
    rotation NOT_RUN. Known product gaps (model menu clipped only if vh rewrite misses; local 8k
    context; UUID workspace label) are listed there too.

## 7. Rollback

- Remove the `DshWorkspaceRoute` call site from `MainActivity` (Lead), remove the
  `implementation(project(":feature:dsh-workspace"))` line, and delete `feature/dsh-workspace/`.
  `settings.gradle.kts` can drop `include(":feature:dsh-workspace")` last.
- No database migration, no DSH data mutation, no Kotlin Agent/chat change is involved.
- Runtime state is not owned here; deleting this module cannot stop or corrupt the DSH host.
- `files/dsh/nav/last_path.txt` is harmless navigation memory and may be deleted at any time.

## 8. Ownership / handoff

- Write set: `feature/dsh-workspace/**` only. Frozen contract and runtime modules are read-only.
- The app/tab wiring, `AppTab.DSH_WORKSPACE` mapping and the real `DshRuntimeClient` injection are
  integrator-owned. This module never edits `app/**`, other features, `core/**`, `api/**` or root
  Gradle files.

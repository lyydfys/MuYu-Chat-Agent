# DSH Data and Runtime Trial — Read-Only Audit

Status: **READ-ONLY AUDIT**. No code, build, test, APK install, device operation or DSH launch was performed.
Date: 2026-10-19 (Asia/Shanghai).
Repository: `E:\MAC`, branch `codex/enterprise-refactor`, HEAD `7475fd640e21154dd9543bcae2c83ab112e30361`.

All runtime behavior in this document is **`NOT_RUN`**. Source facts are labelled **`STATIC`**; they are
not runtime proof. This document is the only file created by this audit.

Throughout this document, **"library"** means the user's DSH-owned session/storage collection under
`$DSH_HOME` (expected `files/dsh/home/sessions` and `files/dsh/home/storages`, plus user files in
`files/dsh/workspace`). The official DSH UI label for that collection is not part of this source audit
and is `NOT_RUN`.

---

## 1. Exact baseline used for this audit

| Item | Value | Evidence |
| --- | --- | --- |
| Repo / branch | `E:\MAC` / `codex/enterprise-refactor` | `git rev-parse --abbrev-ref HEAD` |
| HEAD | `7475fd640e21154dd9543bcae2c83ab112e30361` | `git rev-parse HEAD` |
| Working tree | dirty; DSH modules are untracked (`core/dsh-runtime/`, `core/dsh-contract/`, `app/.../dsh/`, `feature/dsh-workspace/`, `api/dsh-gateway/`) | `git status --porcelain=v1` |
| Staged payload archive | 160,464,248 bytes, sha256 `975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab` | `scripts/dsh/payload-lock.json:5-8`; `core/dsh-runtime/src/main/assets/dsh/MANIFEST.json:4-8` |
| Packaged Node ELF | 49,715,528 bytes, sha256 `a0d21a1589312919114b24a5cc520c30ab7f26f9985b3f0c643c153172ebd1e6` | `scripts/dsh/payload-lock.json:12-17`; `core/dsh-runtime/src/main/assets/dsh/MANIFEST.json:9-13` |
| Payload version | `dsh-0.2.1-rc.2+android-ref-c324272bff00` | `core/dsh-runtime/src/main/assets/dsh/MANIFEST.json:3` |
| Payload file count | 31,021 | `core/dsh-runtime/src/main/assets/dsh/MANIFEST.json:16` |
| Kernel entry | `dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js` | `core/dsh-runtime/src/main/assets/dsh/MANIFEST.json:14` |

No build, Gradle task, unit test, APK install, service start, DSH process or device transfer was run.
The DSH source tree was not re-fetched; this audit used the checked-in runtime code, the staged payload
archive, the proposal/ADR/repair documents and the current working tree.

### 1.1 Executive answers

| Question | Answer |
| --- | --- |
| Current paths | Managed payload `files/dsh/payload`; DSH home `files/dsh/home`; workspace `files/dsh/workspace`; temp `files/dsh/tmp`; Node ELFs in PackageManager `nativeLibraryDir` (§2). |
| Managed vs user data | Payload, marker and MCA overlay are managed; sessions, storages, credentials, plugin state and workspace are user data. All are under one `dsh` directory and therefore one backup exclusion (§3). |
| Can updates silently create an empty library? | Direct payload reinstall: no home deletion in code. Restore/device transfer/lost home: yes, the seeder treats missing home as first run. New-kernel migration: unknown/`NOT_RUN` (§4). |
| Candidate health-tested before switch/rollback? | **No.** One active payload slot; old tree deleted before readiness; no trial home, no health journal, no rollback API; old Node is not retained across APK update (§5). |
| Concrete minimal changes | Home first-run/lost-home guard; stop/fence + signer check before update; last-known-good payload with post-READY commit; isolated candidate trial; version snapshot/export; manifest/node verification (§8). |

---

## 2. Current path layout

### 2.1 Tree as constructed by the current code

```text
APK (assets, build/staging input)
├── dsh/payload.zip                         # 160 MB official/reference payload
└── dsh/MANIFEST.json                       # archive + critical-file hashes

PackageManager-managed (outside writable app home)
└── nativeLibraryDir/libdshnode.so
    nativeLibraryDir/libdshrg.so
    nativeLibraryDir/libdshcurl.so
    nativeLibraryDir/libdshgit.so
    nativeLibraryDir/libdshgitremotehttp.so
    nativeLibraryDir/libdshgitremotehttps.so
    nativeLibraryDir/libdshpython.so

App-private filesDir/dsh  (DSH_DATA_ROOT)
├── payload/                                # verified kernel + runtime tree
│   ├── dshroot/...
│   ├── runtime/lib/...
│   ├── dshhome/...                         # official profile template
│   └── bin/...                             # tool shims, partly symlinked to nativeLibraryDir
├── payload.marker.json                     # installed payload identity
├── home/                                   # DSH_HOME / HOME / DSH_CONFIG_DIR
│   ├── sessions/                           # expected official session JSONL root
│   ├── storages/                           # expected official KV/storage root
│   ├── profiles/web/                       # profile template files
│   ├── settings.yaml
│   └── cordis.patch.yml                    # MCA-owned provider/permission overlay
├── workspace/                              # DSH_WORKSPACE (tool working directory)
├── tmp/                                    # TMPDIR
└── nav/last_path.txt                       # feature-owned workspace navigation memory
```

### 2.2 Path producers and exact evidence

| Path | Produced / owned by | Evidence |
| --- | --- | --- |
| `files/dsh` root | `DshRuntimeConfig(dataRoot = File(filesDir, "dsh"))` | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeService.kt:65`; `DshRuntimeServiceClient.kt:47-50` |
| `files/dsh/home`, `workspace`, `payload`, `tmp`, `config`, marker | `DshRuntimeConfig` computed properties | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshRuntimeConfig.kt:60-65` |
| `files/dsh/payload` contents | `DshPayloadInstaller` extracts the APK asset and verifies it | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadInstaller.kt:37-48,85-177` |
| `files/dsh/payload.marker.json` | installer writes version/hash/file-count marker | `DshPayloadInstaller.kt:159-168,249-256`; `DshPayloadManifest.kt:173-213` |
| `nativeLibraryDir/libdsh*.so` | PackageManager extracts APK `jniLibs`; app only symlinks to it | `scripts/dsh/README.md:59-78`; `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt:68,103-150` |
| `files/dsh/home` / `DSH_HOME` | runtime environment sent to Node | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt:43-74` |
| official session storage path | expected `$DSH_HOME/sessions`; KV `$DSH_HOME/storages` | `docs/proposals/agent-dsh-embedded-v1/BASELINE.md:217-219` (static source audit) |
| `files/dsh/nav/last_path.txt` | DSH workspace navigation memory | `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshNavigationStore.kt:47-55,95-97` |
| APK staging inputs | `scripts/dsh/stage.ps1` | `scripts/dsh/stage.ps1:107-111,154-162,312-325` |

The official home path is passed to Node as follows:

```text
HOME             = files/dsh/home
DSH_HOME         = files/dsh/home
DSH_CONFIG_DIR   = files/dsh/home/config
DSH_WORKSPACE    = files/dsh/workspace
TMPDIR           = files/dsh/tmp
LD_LIBRARY_PATH  = files/dsh/payload/runtime/lib
PATH             = files/dsh/payload/bin:files/dsh/payload/runtime/bin:/system/bin:/system/xbin
```

Evidence: `DshNodeCommand.kt:56-68`.

The committed `scripts/dsh/payload-lock.json` is a **build-time** pin: `stage.ps1` compares the archive,
executables and critical files against it (`scripts/dsh/stage.ps1:235-271`). At runtime,
`DshPayloadInstaller` reads `dsh/MANIFEST.json` from the APK (`DshAndroidRuntimeAssembly.kt:43-55`); it
does not read `payload-lock.json`. The APK signature is therefore the runtime trust root, and the
signer-compatibility gap in §4.5 matters.

---

## 3. What is managed data vs user data

### 3.1 Ownership table

| Data | Class | Regenerable from APK? | Android backup / transfer | Evidence |
| --- | --- | --- | --- | --- |
| `assets/dsh/payload.zip`, `assets/dsh/MANIFEST.json` | Build/managed | yes | part of APK | `DshRuntimeConfig.kt:26-28`; `app/build.gradle.kts:312-319` |
| `nativeLibraryDir/libdsh*.so` | PackageManager-managed | yes | part of APK | `DshAndroidRuntimeAssembly.kt:68,103-150`; `scripts/dsh/README.md:59-66` |
| `files/dsh/payload/**` | Managed runtime tree | yes | excluded (whole `dsh`) | `DshPayloadInstaller.kt:85-177`; `backup_rules.xml:25-28`; `data_extraction_rules.xml:23-25,57-59` |
| `files/dsh/payload.marker.json` | Managed install identity | yes | excluded | `DshPayloadInstaller.kt:239-256` |
| `files/dsh/payload.staging.*`, `payload.old.*`, `payload.archive.tmp` | Managed transients | yes | excluded | `DshPayloadInstaller.kt:91-92,144-157` |
| `files/dsh/home/cordis.patch.yml` | MCA-managed overlay; rewritten every host generation | yes | excluded | `DshGatewayConfigSeeder.kt:41-46,153-186` |
| `files/dsh/home/profiles/web/{cordis.yml,package.json,pnpm-workspace.yaml}` | Seeded from payload **only when missing** | partially | excluded | `DshGatewayConfigSeeder.kt:50-87` |
| `files/dsh/home/profiles/web/cordis.patch.yml` | DSH profile patch; not in MCA's seed list, so treated as DSH/user-owned if created | no | excluded | `DshGatewayConfigSeeder.kt:62-72,211-217`; the reference template file is not copied |
| `files/dsh/home/settings.yaml` | Seeded only when missing; then user/DSH state | partially | excluded | `DshGatewayConfigSeeder.kt:73-77` |
| `files/dsh/home/sessions/**` | DSH user data (append-only session logs) | **no** | **excluded** | `docs/proposals/agent-dsh-embedded-v1/BASELINE.md:217-218`; `docs/proposals/agent-v1/DSH_KERNEL_PARITY.md:79-82`; backup rules cited above |
| `files/dsh/home/storages/**` | DSH user data (KV/storage) | **no** | **excluded** | `docs/proposals/agent-dsh-embedded-v1/BASELINE.md:219`; backup rules cited above |
| `files/dsh/home` other official state (credentials, plugin config, approvals, history) | DSH user data | **no** | **excluded** | `DshRuntimeConfig.kt:8-19`; `DshGatewayConfigSeeder.kt:15-21`; `docs/adr/0003-dsh-embedded-dual-agent-modes.md:82-88` |
| `files/dsh/workspace/**` | User files/tool working tree | **no** | **excluded** | `DshRuntimeConfig.kt:61`; `DshNodeCommand.kt:60`; backup rules cited above |
| `files/dsh/tmp/**` | Ephemeral | yes | excluded | `DshRuntimeConfig.kt:63` |
| `files/dsh/nav/last_path.txt` | Feature-owned UI memory | yes | excluded | `DshNavigationStore.kt:15-20,47-55,95-97` |

Evidence for the exclusion rule is duplicated deliberately: the legacy full-backup rule excludes the
whole `dsh` directory, and the Android 12+ data-extraction rules exclude `dsh` in **both** `<cloud-backup>`
and `<device-transfer>`:

- `app/src/main/res/xml/backup_rules.xml:25-28`
- `app/src/main/res/xml/data_extraction_rules.xml:23-25` (cloud)
- `app/src/main/res/xml/data_extraction_rules.xml:57-59` (device transfer)
- application enables backup: `app/src/main/AndroidManifest.xml:16-21`

### 3.2 Important ownership findings

1. **The payload tree and the user home are separate directories, but not separately backed up.**
   Excluding `dsh` protects credentials and huge payload bytes, but it also excludes `home/sessions`,
   `home/storages` and `workspace`, which are not regenerable. There is no second explicit export owner
   in production code (see §4.3).
2. **`home/cordis.patch.yml` is written inside DSH's user home on every start.** The seeder describes
   MCA as owner of this overlay (`DshGatewayConfigSeeder.kt:15-21,41-46`), and the file is regenerated
   from the current gateway endpoint/model catalog (`:153-186`). It carries permission presets, provider
   `baseURL`, model list and `apiKeyEnv`; the bearer token itself is passed only through the child
   environment (`DshRuntimeOwner.kt:286-290`), not persisted in the overlay. If DSH or a user edits that
   same root patch, the next start overwrites it. The official writer/merge behavior for this file is
   `NOT_RUN`; there is no MCA header/ownership marker or backup-before-overwrite in the current code.
3. **Seeded template files are not versioned.** The seeder copies a hard-coded list
   `cordis.yml`, `package.json`, `pnpm-workspace.yaml` from the current payload only when the target is
   absent (`DshGatewayConfigSeeder.kt:62-77,211-217`). A new payload that changes required template
   content will not refresh an existing home; conversely, a user who deletes a template file gets the
   payload version silently restored.
4. **Logical separation, not OS isolation.** Both processes share the app UID; the `:dsh` process only
   isolates crash/lifecycle. This audit does not treat `files/dsh` as a security boundary
   (`core/dsh-runtime/src/main/AndroidManifest.xml:10-25`; `docs/adr/0003-dsh-embedded-dual-agent-modes.md:126-134`).
5. **The app updater's `updates` directory is not DSH data.** Downloaded APKs go to
   `filesDir/updates` (`AppUpdateOwner.kt:31,270-273`), outside `files/dsh`; it is neither a DSH
   backup nor a DSH restore path.

---

## 4. Can an update silently create an empty library?

### 4.1 Direct answer

- **Payload reinstall / APK update through the current installer: no direct code path deletes
  `home`, `workspace` or `tmp`.** The installer only manipulates `files/dsh/payload*`, the archive
  temp file and the payload marker (`DshPayloadInstaller.kt:85-177,239-256`; `DshRuntimeConfig.kt:60-65`).
  A same-signature APK update is therefore expected to preserve the DSH home, but that is `NOT_RUN`.
- **Restore/device transfer or a lost/unreadable home: yes, an empty library can appear without an
  explicit reset.** `dsh` is excluded from both cloud backup and device transfer; when `home` is absent,
  the seeder creates the profile template and starts DSH with whatever data remains (`DshGatewayConfigSeeder.kt:50-87`;
  `DshRuntimeOwner.kt:320-329`). There is no first-run/lost-home sentinel and no user-facing warning.
- **A new kernel version against an old home: unknown.** There is no home schema/version marker, no
  pre-update snapshot and no migration gate before the new payload starts (§4.2, §4.4). Whether the
  official kernel migrates, refuses or reinitializes old sessions is `NOT_RUN`.

### 4.2 Update/restore scenarios

| Scenario | Does code touch `home`? | Library outcome | Result |
| --- | --- | --- | --- |
| New APK ships a new `payload.zip` | No; installer deletes only `payload*` paths | Expected preserved; new kernel then runs against old home | Runtime `NOT_RUN`; no migration gate |
| New APK has no payload / corrupt payload | No | Host fails closed (`PAYLOAD_MISSING` / `PAYLOAD_INTEGRITY`); old home stays | Static; runtime `NOT_RUN` |
| Same-signature APK update while stopped | No | Android preserves app data; expected preserved | Runtime `NOT_RUN` |
| APK update with a different signing key | No direct deletion; system installer rejects update | If user uninstalls to proceed, `files/dsh` is deleted and excluded from backup | **High risk**; runtime `NOT_RUN` |
| Device transfer / cloud restore | Yes, indirectly: `dsh` is not transferred | Home absent → seeder creates empty home → empty library | **High risk**; static path; runtime `NOT_RUN` |
| App data cleared / uninstall | Yes, indirectly | Home absent → empty library | Expected but no warning; runtime `NOT_RUN` |
| Home exists but is unreadable/partially deleted | Seeder only checks `isDirectory`/`isFile`; it may create missing pieces | May start with partial/empty library; no integrity check | **High risk**; runtime `NOT_RUN` |
| App update while DSH is running | No stop/snapshot in update path | Process may be killed mid-write; official JSONL torn-tail recovery is not verified here | **High risk**; runtime `NOT_RUN` |

### 4.3 Why the restore path is silent

The seeder has no "first run" marker. Its only decision is "does this file/directory exist?":

- `DshGatewayConfigSeeder.kt:50-87`: if `home/profiles/web` is missing, create it and copy the template;
  if `settings.yaml` is missing, copy it; create `home`, `home/config`, `workspace`, `tmp`.
- `DshRuntimeOwner.kt:320-329`: creates the same directories unconditionally just before launch.
- `DshRuntimeConfig.kt:60-65`: there is no `homeInitialized`/`homeVersion`/`lastKnownGoodHome` field.
- `DshFailureProjection.kt:76-99`: there is no failure code or message for "existing DSH data is
  missing/unreadable"; all messages describe payload, startup, gateway or stop failures.
- `DshWorkspaceScreen.kt:38-43,66-100,112-138`: the shell only renders Starting / Failure / Web, and
  the only "first start" text is a generic "首次启动会准备官方 DSH 运行时" line (`:128`). There is no
  data-loss notice, export/import action or "restore vs reset" choice.
- `DshWorkspaceActions` has only `onRetry`, `onReloadPage`, `onDismissPageError`, `onExit`
  (`DshWorkspaceScreen.kt:38-43`). There is no data export/import route.

The docs already describe the intended explicit transfer path, but it is not implemented:

- `docs/proposals/agent-dsh-embedded-v1/ARCHITECTURE.md:183`: "SAF 授权导入导出，备份/设备转移策略随真实敏感内容评审".
- `docs/proposals/agent-dsh-embedded-v1/EXECUTION_PACK.md:92`: R06 write set includes "DSH 数据目录/备份/导入导出平台接线".
- `docs/proposals/agent-dsh-embedded-v1/BASELINE.md:351`: data directory/backup is still a R06 unknown.
- `docs/proposals/agent-dsh-embedded-v1/DSHA_MULTI_PROJECT_REVIEW_20261019.md:96` cites the reference
  implementation rule: "数据不可读时拒绝静默创建一个空库". MCA has no equivalent guard.
- `docs/adr/0003-dsh-embedded-dual-agent-modes.md:90-96` says a new data format is not guaranteed to
  roll back with a simple APK downgrade; snapshots/forward fixes are required.

### 4.4 Kernel-version change has no home migration gate

The payload marker only answers "which payload archive is installed", not "which home version is safe":

- `DshPayloadManifest.kt:178-213`: marker fields are `manifestVersion`, `payloadVersion`,
  `archiveSha256`, `fileCount`, `installedAtMillis`; no home schema, last health check or previous slot.
- `DshPayloadInstaller.kt:72-82`: marker match + six critical files → reuse; otherwise reinstall.
- `DshRuntimeOwner.kt:213-239`: installs the payload, then later starts Node; no comparison with the
  home's last payload version and no snapshot.
- `DshGatewayConfigSeeder.kt:50-87`: no payload-version check before copying templates.

Consequence: the first start after a payload/kernel change runs the **new** kernel against the **old**
home with no pre-update snapshot. If the official kernel migrates in place, the result is `NOT_RUN`; if
it ignores or resets an old session store, the user can see an empty library. There is no MCA code that
distinguishes those outcomes.

### 4.5 In-app APK update path adds two more data-loss risks

1. **Signer compatibility is not compared, although signing info is read.**
   `GitHubReleaseUpdate.kt:440-452` requests `GET_SIGNING_CERTIFICATES` / `GET_SIGNATURES`, but
   `:454-464` only compares package name, version name and version code. There is no comparison against
   the current package's signer. A differently signed APK cannot be installed over MCA; if the user
   uninstalls to proceed, `files/dsh` is deleted and is not backed up. The docs require compatible
   signatures (`docs/proposals/agent-dsh-embedded-v1/VERIFICATION_PLAN.md:138,148-150`;
   `docs/proposals/agent-dsh-embedded-v1/BASELINE.md:165`), but the code does not enforce it.
2. **The update install path does not stop or fence the DSH host.**
   `AppUpdateOwner.kt:318-396` validates the APK and opens the system installer; the file contains no
   `Dsh` reference, and `MainRuntimeAssembly.kt:143-149` exposes `dshRuntimeClient` but the update owner
   does not use it. A running DSH Node process can be killed by the package update with no pre-update
   stop, snapshot or "in-flight work" check. The official session format claims torn-tail tolerance
   (`docs/proposals/agent-v1/DSH_KERNEL_PARITY.md:79-82`), but MCA's own update behavior is `NOT_RUN`.

Related stop-contract bug (also affects any future update/switch gate):

- `DshRuntimeContract.kt:100-101` says `stop()` "Stops the host and waits for the stop fence."
- `DshRuntimeServiceClient.kt:174-198` sends `MSG_STOP`, **ignores the reply**, unbinds, and
  unconditionally publishes `DshRuntimeSnapshot(phase = STOPPED)`. A service-side
  `FAILED/STOP_TIMEOUT` is therefore hidden from the caller.
- `DshRuntimeService.kt:158-172` calls `stopHostAndSelf()` even when `owner.stop()` returned a stop
  timeout; `:100-109` only performs an asynchronous `shutdown()` in `onDestroy`.
- The stop fence is in-memory only (`DshRuntimeStateMachine.kt:122-136`; `DshRuntimeOwner.kt:111,184-197`),
  and the service is `START_NOT_STICKY` (`DshRuntimeService.kt:85-98`). If the service process dies
  while Node survives, a newly created service/owner has no fence to block a second launch.

This is a direct contradiction between the documented stop contract and the app-side client behavior.

---

## 5. Can a candidate runtime be health-tested before switch/rollback?

### 5.1 Direct answer

**No, not with the current code.** The only installed slot is `files/dsh/payload`. A candidate payload is
not staged separately, not launched against an isolated home, and not health-tested before it becomes
the active tree. Hash verification before the swap (`DshPayloadInstaller.kt:114-141`) is not runtime
health: a tree can pass every checked hash and still fail Node exec, loader, plugin or readiness. The
only health gate (`DshReadinessGate`) runs **after** the active payload has already been swapped in and
Node has started. There is no rollback API, no persisted last-known-good pointer and no retained old
Node executable.

### 5.2 Current sequence (source order)

```text
build/stage time (manual, not on device)
  stage.ps1 -> APK assets dsh/payload.zip + dsh/MANIFEST.json + jniLibs libdsh*.so

first DSH start after install/update
  DshRuntimeOwner.startInternal
    -> payload.ensureInstalled()
         -> verify archive sha256/size
         -> extract staging
         -> verify file count + critical files
         -> rename payload -> payload.old.<clock>
         -> rename staging -> payload
         -> DELETE payload.old.<clock>            <-- old tree is gone here
         -> write payload.marker.json
    -> check nativeLibraryDir/libdshnode.so exists
    -> seed DSH home + write MCA overlay
    -> node --version probe (nativeLibraryDir or /system/bin/linker64)
    -> start Node
    -> await authenticated entry line
    -> DshReadinessGate: 303 + HttpOnly cookie + boot marker + plugin JS + second stable fetch + unauth 401
    -> READY
```

Evidence:

- `DshPayloadInstaller.kt:57-83,85-177` (verify/extract/swap/delete)
- `DshPayloadInstaller.kt:143-157` (`previous` is deleted immediately after the rename)
- `DshRuntimeOwner.kt:213-251` (install, native-library existence)
- `DshRuntimeOwner.kt:254-329` (gateway, seed, environment, exec strategy, directories)
- `DshRuntimeOwner.kt:332-395` (Node launch, auth line, readiness)
- `DshReadinessGate.kt:54-176` (post-launch health gate)
- `DshNodeExecStrategy.kt:36-68` (only `nativeLibraryDir` + linker64 candidates)

### 5.3 What is missing for a pre-switch trial

| Capability | Current state | Evidence |
| --- | --- | --- |
| Separate candidate directory/slot | no; one `payloadDir` | `DshRuntimeConfig.kt:62`; `DshPayloadInstaller.kt:57` |
| Stage candidate without touching active tree | no | `DshPayloadInstaller.kt:85-177` |
| Trial with isolated `DSH_HOME`/workspace/tmp | no; host always uses `config.homeDir`/`workspaceDir` | `DshRuntimeOwner.kt:278-290,320-329`; `DshNodeCommand.kt:56-68` |
| Trial health evidence persisted | no; marker has no health field | `DshPayloadManifest.kt:178-213` |
| Promote candidate only after trial | no | `DshRuntimeOwner.kt:332-395` |
| Keep last-known-good payload | no; deleted before launch | `DshPayloadInstaller.kt:156` |
| Keep last-known-good Node ELF | no; Node comes from PackageManager `nativeLibraryDir` | `DshAndroidRuntimeAssembly.kt:68`; `DshNodeExecStrategy.kt:36-68` |
| Rollback API / failure code | no | `DshRuntimeContract.kt:23-46,91-109` |
| UI/API rollback control | no | `DshWorkspaceScreen.kt:38-43` |
| Update-triggered trial on device | no; payload is APK-asset-only, no DSH downloader | `DshAndroidRuntimeAssembly.kt:43-55`; no update/download path in `core/dsh-runtime` |

The authoritative client contract exposes only `ensureStarted`, `restart`, `stop`, `webEntry`
(`DshRuntimeContract.kt:91-109`). `restart()` creates a new epoch but reuses the same installed payload
(`DshRuntimeOwner.kt:123-134,165-239`); it is not a rollback.

### 5.4 The key rollback blocker: old Node is not retained by the app

A retained old `payload/` tree is **not sufficient** to roll back an APK update:

- The packaged Node executable is extracted by PackageManager into `nativeLibraryDir`; the app obtains
  that path from `context.applicationInfo.nativeLibraryDir` (`DshAndroidRuntimeAssembly.kt:68`).
- The app creates payload `bin/` links to `nativeLibraryDir` (`DshAndroidRuntimeAssembly.kt:103-150`);
  it does not copy `libdshnode.so` into persistent app storage.
- `DshNodeExecStrategy` probes only the `nativeLibraryDir` Node path (direct exec or
  `/system/bin/linker64 <nativeLibraryDir>/libdshnode.so`) (`DshNodeExecStrategy.kt:36-68`).
- `scripts/dsh/README.md:59-66` and `app/build.gradle.kts:350-357` confirm the Node/ELF tools are APK
  `jniLibs`, not payload files.
- The payload archive does contain `runtime/bin/node`, but `DshNodeExecStrategy` does not use it and
  targetSdk 36 forbids exec from the writable home. Whether
  `/system/bin/linker64 files/dsh/payload/runtime/bin/node` works is `NOT_RUN`.

Therefore, after an APK update, retaining `payload.old` restores the **kernel tree**, not necessarily the
**Node executable** that kernel was tested with. A true runtime rollback needs one of:

1. an old-Node copy in a persistent, exec-capable location plus a linker64 path (needs real-device proof), or
2. a platform-supported APK downgrade (not available in-app; Android usually rejects lower versionCode), or
3. an explicit "update not rollback-capable" product boundary with a mandatory pre-update home export.

The current code has none of these.

### 5.5 What does exist (but only after switch)

`DshReadinessGate` is a meaningful post-launch health check: token exchange, HttpOnly cookie, index
boot marker, client plugin JS, second stable fetch and unauthenticated 401
(`DshReadinessGate.kt:85-176`). It is stronger than "port open" and should be reused for a trial. It is
not a pre-switch test.

The existing tests cover payload install/reuse/hash/traversal (`DshPayloadInstallerTest.kt:159-237`) and
owner start/seed/stop (`DshRuntimeOwnerTest.kt:184-229`), but there is no update-preserves-home test, no
candidate-trial test and no rollback test.

---

## 6. Documentation consistency

| Document claim / recommendation | Code reality | Status |
| --- | --- | --- |
| `docs/proposals/agent-dsh-embedded-v1/DSHA_MULTI_PROJECT_REVIEW_20261019.md:157` recommends "增加隔离试运行与健康提交" for payload updates | No candidate slot, isolated trial, health journal or rollback | Not implemented |
| `docs/proposals/agent-dsh-embedded-v1/DSHA_MULTI_PROJECT_REVIEW_20261019.md:96` cites "数据不可读时拒绝静默创建一个空库" | Seeder treats missing home as first run; no guard | Not implemented |
| `ARCHITECTURE.md:183` mentions "SAF 授权导入导出" | No DSH export/import action; only WebView download and nav memory | Not implemented |
| `EXECUTION_PACK.md:92` assigns R06 "备份/导入导出平台接线" | No production backup/export/import owner | Not implemented |
| `docs/proposals/agent-dsh-embedded-v1/BASELINE.md:351` marks data directory/backup as an R06 unknown | Consistent with the code gap; not a pass | `NOT_RUN` |
| `VERIFICATION_PLAN.md:148-150` V05 requires compatible signature + data continuity | `validateArchive` does not compare signers; no V05 run | `NOT_RUN` |
| `VERIFICATION_PLAN.md:354` rollback = uninstall candidate, reinstall previous APK, restore snapshot | No snapshot tool, no in-app rollback | Manual only / `NOT_RUN` |
| `docs/adr/0003-dsh-embedded-dual-agent-modes.md:90-96` says a new data format does not guarantee simple APK downgrade | Consistent; no snapshot/forward-fix tool exists in MCA | `NOT_RUN` |
| `docs/proposals/agent-v1/DSH_KERNEL_PARITY.md:79-82` documents official JSONL torn-tail recovery | MCA's update path does not stop the host before app update; not verified here | `NOT_RUN` |

The docs are honest about V05/R06 being unverified; the defect is that some future-tense design text
("SAF import/export", "isolated trial") can be read as present capability if the audit status is lost.

---

## 7. Risk register (ordered)

| ID | Severity | Gap | Evidence |
| --- | --- | --- | --- |
| R1 | Critical | No pre-switch candidate trial and no rollback: old payload is deleted before readiness; old Node is not retained; no rollback API/health journal | `DshPayloadInstaller.kt:143-157`; `DshRuntimeOwner.kt:332-395`; `DshAndroidRuntimeAssembly.kt:68`; `DshNodeExecStrategy.kt:36-68`; `DshRuntimeContract.kt:91-109` |
| R2 | Critical | DSH library can become empty silently after device transfer/restore/lost home; no first-run marker, no warning, no explicit export/import | `backup_rules.xml:25-28`; `data_extraction_rules.xml:23-25,57-59`; `DshGatewayConfigSeeder.kt:50-87`; `DshFailureProjection.kt:76-99`; `DshWorkspaceScreen.kt:38-43` |
| R3 | Critical | Update/switch seam is unsafe: no signer compatibility check; app update does not stop/fence DSH; client `stop()` masks `STOP_TIMEOUT` as `STOPPED`; fence is in-memory | `GitHubReleaseUpdate.kt:440-465`; `AppUpdateOwner.kt:318-396`; `DshRuntimeServiceClient.kt:174-198`; `DshRuntimeService.kt:100-109,158-172`; `DshRuntimeStateMachine.kt:122-136` |
| R4 | High | New payload/kernel version has no home migration gate or pre-update snapshot; payload marker has no home version | `DshPayloadManifest.kt:178-213`; `DshPayloadInstaller.kt:72-82`; `DshGatewayConfigSeeder.kt:50-87` |
| R5 | High | Reuse verifies only six critical files + kernel entry + runtime dir; a partially deleted 31,021-file payload can pass; manifest `fileCount` is not rechecked on reuse | `DshPayloadInstaller.kt:72-81,131-141,218-236`; `core/dsh-runtime/src/main/assets/dsh/MANIFEST.json:16` |
| R6 | Medium-high | Manifest/config path duplication: installer verifies manifest `kernelEntry`/`runtimeLibraryDir`, but owner launches hard-coded config paths; `nodeLibraryName`/`nodeSha256` are never verified at runtime | `DshPayloadManifest.kt:38-42,103-107`; `DshRuntimeConfig.kt:68-77`; `DshRuntimeOwner.kt:243-251,335`; `DshPayloadInstaller.kt:66-70,218-236` |
| R7 | Medium | `home/cordis.patch.yml` is an MCA-managed file inside the user home, rewritten every start with no user-edit backup/ownership marker | `DshGatewayConfigSeeder.kt:15-21,41-46,153-186` |
| R8 | Medium | No explicit DSH export/import; the only transfer story is the excluded Android backup | `ARCHITECTURE.md:183`; `EXECUTION_PACK.md:92`; `DshWorkspaceScreen.kt:38-43` |
| R9 | Medium | Staged payload artifacts are not git-ignored, although the README says they are never committed; `core/dsh-runtime` is currently untracked | `.gitignore:49-52`; read-only `git check-ignore` showed `payload.zip`, `MANIFEST.json` and `jniLibs/libdshnode.so` are **not ignored** |
| R10 | Low-medium | Crash between payload renames can leave orphan `payload.old.*`/`payload.staging.*`; the next install does not sweep them and may delete/reuse by same-millisecond clock name | `DshPayloadInstaller.kt:144-157,283-287` |

---

## 8. Concrete minimal changes

The following are proposals only; none was implemented or tested in this audit. They reuse the existing
owner/seams and do not create a second runtime.

### M1 (P0) — Make "missing home" distinguishable from "first run"

Purpose: stop silently creating an empty library after restore/transfer/lost data.

Minimal implementation:

1. Add a small persisted home-state owner (Android `SharedPreferences`, **not** under `files/dsh`, so it
   is not excluded with the DSH directory). The intent is deliberate asymmetry: Android backup/transfer
   can restore the small marker even when the whole `dsh` file tree is excluded, so a restored marker
   plus a missing home becomes detectable instead of looking like a fresh install. Store:
   - `homeInitialized: Boolean`
   - `lastHomePayloadVersion: String?`
   - `lastReadyAtMillis: Long?`
2. Inject it into `DshGatewayConfigSeeder`/`DshRuntimeOwner` (or a narrow `DshHomeGuard` seam) and decide:
   - `!initialized && !homeDir.exists()` → first run: seed template, then write `homeInitialized=true`.
   - `initialized && !homeDir.isDirectory` → fail closed with new `DSH_HOME_MISSING`.
   - `!initialized && homeDir.exists()` → fail closed with new `DSH_HOME_UNVERIFIED` (do not seed over
     unknown data).
   - `initialized && homeDir.isDirectory` → proceed; missing managed template files may be restored,
     but never delete/replace user data.
   Note: clearing app data also clears this marker; that is an explicit reset, not the restore case the
   guard is meant to catch.
3. Add a user-visible projection for both codes with "不会自动新建空库" and an explicit
   "恢复/导出导入" or "确认重置" action. Do **not** auto-reset.
4. Tests: (a) pre-populated `home/sessions` + no marker → `DSH_HOME_UNVERIFIED`, bytes unchanged;
   (b) marker true + home absent → `DSH_HOME_MISSING`; (c) fresh install → seeded + marker.

Files likely touched: `DshRuntimeConfig.kt`, new small `DshHomeStateStore` in `core/dsh-runtime`,
`DshGatewayConfigSeeder.kt`, `DshRuntimeOwner.kt`, `core:dsh-contract` `DshFailureCode`,
`DshFailureProjection.kt`, `DshWorkspaceScreen.kt`, JVM tests.

### M2 (P0) — Stop/fence and signer-check before installing an app update

Purpose: prevent an update from killing a writing host or requiring an uninstall/data wipe.

Minimal implementation:

1. Fix `DshRuntimeServiceClient.stop()` to apply/return the service's snapshot instead of unconditionally
   publishing `STOPPED`; change `DshRuntimeClient.stop()` to return `DshRuntimeSnapshot` (or add
   `stopAndAwait()`), and update `DshModeAssembly` callers.
2. In `DshRuntimeService` `MSG_STOP`, do not `stopHostAndSelf()` when the snapshot is
   `FAILED/STOP_TIMEOUT`; keep the service/fence alive so it can be retried. If the process may be killed,
   persist the fenced PIDs in `files/dsh/stop-fence.json` and re-check them on the next `onCreate`.
3. Add `beforeInstall: suspend () -> Boolean` (or a narrow `UpdateInstallBarrier`) to `AppUpdateOwner`;
   wire it in `MainViewModel` to `dshRuntimeClient.stop()` + snapshot check. Block the installer intent
   unless the host reports `STOPPED`.
4. In `ApkUpdateInstaller.validateArchive`, compare the archive signer SHA-256 set with the current
   package signer set; return a clear error on mismatch before opening the installer.
5. Tests: fake client returns `STOP_TIMEOUT` → no installer intent; signer mismatch → validation error;
   clean stop → installer intent.

Files likely touched: `DshRuntimeServiceClient.kt`, `DshRuntimeService.kt`, `DshRuntimeOwner.kt`,
`DshRuntimeContract.kt`, `AppUpdateOwner.kt`, `MainViewModel.kt`, `GitHubReleaseUpdate.kt`, tests.

### M3 (P0/P1) — Last-known-good payload and post-READY commit

Purpose: make a failed payload switch recoverable instead of one-way.

Minimal implementation:

1. Keep the previous payload tree until the new one is READY:
   - `DshPayloadInstallResult.Installed` gains `previousDir: File?`.
   - `DshPayloadInstaller.install()` no longer deletes `previous` at `:156`; it moves it to a stable
     `payload.previous` and writes a `payload.current.json` pointer.
2. Add `commit()` / `rollback()` to the provisioner seam:
   - On `DshReadinessGate` READY → `commit()` deletes/marks previous as last-known-good.
   - On startup failure after a swap → `rollback()` restores `payload.previous` before reporting failure.
3. Persist `payload-trial.json`/`payload-current.json` with candidate hash, previous hash, Node version,
   ABI and readiness evidence. This is the health evidence that is currently missing.
4. Tests: install v1, install v2, inject readiness failure; assert v1 active again and `home` unchanged.

Files likely touched: `DshPayloadInstaller.kt`, `DshPayloadProvisioner.kt`, `DshRuntimeOwner.kt`,
`DshPayloadManifest.kt`, tests.

### M4 (P1) — Isolated candidate trial before promotion

Purpose: answer "can the candidate be health-tested before switch?" with yes.

Minimal implementation:

1. Extract the new payload into `files/dsh/payload.candidate.<hash>` first; keep active tree untouched.
2. Add a small `DshRuntimeTrial` coordinator that reuses existing `DshNodeCommand`, `DshNodeExecStrategy`,
   `AndroidDshNodeHost` and `DshReadinessGate`, but points:
   - `HOME`/`DSH_HOME` to `files/dsh/trial/<id>/home`
   - `DSH_WORKSPACE` to `files/dsh/trial/<id>/workspace`
   - `TMPDIR` to `files/dsh/trial/<id>/tmp`
   - `port = 0`
3. Seed only the official profile template into the trial home; never point the trial at the real home.
4. On trial READY: promote candidate and start the real host. On failure: delete trial/candidate, keep
   active tree, report `CANDIDATE_UNHEALTHY`.
5. Add a second trial start/stop to verify storage/session reopen, matching the reference `RuntimeTrial`
   behavior cited in `docs/proposals/agent-dsh-embedded-v1/DSHA_MULTI_PROJECT_REVIEW_20261019.md:98`.
6. Tests: failing readiness leaves active tree and real home byte-identical; passing trial promotes.

This is the largest slice; it should not expand `DshRuntimeOwner.Deps` further. Prefer a separate
coordinator that takes the existing owner/builders as explicit collaborators.

### M5 (P1) — Version/snapshot and explicit export/import

1. Persist `lastHomePayloadVersion` in the M1 state store. On a payload-version change, copy
   `home/sessions` + `home/storages` (and optionally `workspace`) to
   `files/dsh/backups/<oldVersion>-<timestamp>` before the new kernel starts; never overwrite the backup.
2. Implement SAF export/import of an allowlisted DSH home archive:
   - export: `home/sessions`, `home/storages`, `home/settings.yaml`, profile patch, optional `workspace`;
   - import: stage, reject path traversal, stop host, replace, then mark home initialized;
   - exclude credentials/known secrets from the export, or encrypt with a user secret (explicit review).
3. Add a visible notice in the DSH shell that Android backup/device transfer does **not** carry DSH data.
4. Tests: version change creates snapshot and preserves session files; malformed import rejected; round-trip.

### M6 (P1) — Verify the managed runtime before executing it

1. In `DshPayloadInstaller.ensureInstalled`, require:
   - `manifest.nodeLibraryName == config.nodeLibraryName`
   - `manifest.kernelEntry == config.kernelEntryRelativePath`
   - `manifest.runtimeLibraryDir == config.runtimeLibraryRelativePath`
   Fail `Integrity` on mismatch instead of verifying a different path than the owner launches.
2. Before exec, compute SHA-256 of `nativeLibraryDir/libdshnode.so` and compare
   `manifest.nodeSha256`; fail `PAYLOAD_INTEGRITY` on mismatch.
3. Re-check `manifest.fileCount` on reuse, or write/validate a complete-tree sentinel plus a bounded
   directory/file-count check; the current six critical files cannot prove a 31,021-file tree is intact.
4. Tests: mismatched manifest path; corrupted `libdshnode.so`; partial tree missing a non-critical file.

### M7 (P2) — Repository/build hygiene

1. Add exact `.gitignore` rules for staged DSH binaries:
   `/core/dsh-runtime/src/main/assets/dsh/payload.zip`
   `/core/dsh-runtime/src/main/assets/dsh/MANIFEST.json`
   `/core/dsh-runtime/src/main/jniLibs/arm64-v8a/libdsh*.so`
   The current `.gitignore:49-52` only ignores `/third_party/dsh/`; read-only `git check-ignore`
   confirmed the payload, manifest and jniLibs are not ignored.
2. Replace the hard-coded `E:\tmp` default in `scripts/dsh/stage.ps1:111,22` with a repo-local/parameterized
   scratch path, or document it as a local-only script.
3. Treat `stage.ps1 -UpdateLock` as a reviewed operation (it accepts all mismatches at `:258-265` and
   rewrites the lock); add a CI/check step that fails on a lock mismatch without `-UpdateLock`.

---

## 9. `NOT_RUN` register

Every item below is runtime behavior not executed by this audit:

| Runtime behavior | Result | Why |
| --- | --- | --- |
| Payload extraction/install into `files/dsh/payload` | `NOT_RUN` | No build/device; only source inspection |
| APK update over an existing install | `NOT_RUN` | No APK/device; Android update behavior not observed |
| First-run home seeding | `NOT_RUN` | No DSH start |
| Restore/device transfer producing an empty library | `NOT_RUN` | No backup/restore/device |
| Kernel-version change against old home | `NOT_RUN` | No second payload/device |
| Candidate trial in an isolated home | `NOT_RUN` | Not implemented; no run |
| Pre-switch health test | `NOT_RUN` | Not implemented |
| Post-switch `DshReadinessGate` | `NOT_RUN` | No DSH/device |
| Stop/fence behavior, including `STOP_TIMEOUT` | `NOT_RUN` | No service/device |
| App-update stop barrier | `NOT_RUN` | Not implemented |
| Signer compatibility check | `NOT_RUN` | Not implemented; static code shows it is absent |
| DSH export/import | `NOT_RUN` | Not implemented |
| Rollback to previous payload/Node | `NOT_RUN` | Not implemented |
| Any Gradle build, unit test or instrumentation test | `NOT_RUN` | Audit was read-only |
| Any DSH/Node process launch | `NOT_RUN` | Audit was read-only |
| Any Android backup/restore/device transfer | `NOT_RUN` | Audit was read-only |

The following static checks were run read-only: `git status`, `git rev-parse`, `git submodule status`,
`git check-ignore`, file reads, and reading/list of the staged ZIP central directory. None executed DSH,
built the app, or changed a file other than this document.

### 9.1 Limits of this audit

- Android `PackageManager` behavior when an APK update replaces `nativeLibraryDir` was not observed on a
  device; the "old Node is not retained" conclusion is based on the app's source and APK packaging, not a
  device experiment. `NOT_RUN`.
- The official DSH kernel's behavior against an older `$DSH_HOME` (migration, refusal or reinitialization)
  was not executed. `NOT_RUN`.
- Android cloud backup/device transfer was not run; the empty-library path is inferred from the backup
  rules and seeder. `NOT_RUN`.
- No APK was built or installed; signer compatibility, native-library execution and `DshReadinessGate`
  were not observed. `NOT_RUN`.
- The staged payload was inspected via its archive manifest and selected ZIP entries; a full
  per-file hash audit of all 31,021 entries was not run. `NOT_RUN`.
- No test in `core/dsh-runtime`, `feature/dsh-workspace` or `app` was executed. `NOT_RUN`.

---

## 10. Conclusion — three highest-risk gaps

1. **The runtime switch is one-way.** A candidate payload is verified and swapped into the single
   `files/dsh/payload` slot, the old tree is deleted before readiness (`DshPayloadInstaller.kt:143-157`),
   and the old Node executable is PackageManager-owned (`DshAndroidRuntimeAssembly.kt:68`;
   `DshNodeExecStrategy.kt:36-68`). There is no candidate slot, no isolated trial, no persisted health
   evidence and no rollback API. A bad APK/payload update cannot be recovered in-product.
2. **The DSH library can become empty silently after restore/transfer or a lost home.** The whole `dsh`
   directory is excluded from cloud backup and device transfer (`backup_rules.xml:25-28`;
   `data_extraction_rules.xml:23-25,57-59`), no export/import exists, and the seeder treats any missing
   home as first run (`DshGatewayConfigSeeder.kt:50-87`). There is no first-run marker, no data-loss
   failure code and no warning in the DSH shell.
3. **The update seam is unsafe even before considering the payload.** The in-app update validation reads
   signing certificates but never compares them to the current package (`GitHubReleaseUpdate.kt:440-465`);
   the installer path never stops/fences DSH (`AppUpdateOwner.kt:318-396`); and `DshRuntimeServiceClient.stop()`
   masks a `STOP_TIMEOUT` as `STOPPED` (`DshRuntimeServiceClient.kt:174-198`) while the fence is in-memory
   only (`DshRuntimeStateMachine.kt:122-136`). This can produce an uninstall/data-wipe path or an
   unsettled old host during an update.

No claim in this document is a device or runtime pass. All runtime items remain `NOT_RUN`.

# DSH device acceptance harness

`scripts/dsh/device-dsh-acceptance.ps1` drives one real Android device through the DSH V2 entry
path and writes evidence that a later reviewer can re-check: install the candidate APK, launch
`MainActivity`, open the sidebar, enter the DSH WebView, wait for bounded readiness, open the DSH
console and capture the capability projection. It never fabricates a PASS: a step without captured
evidence is FAIL/NOT_RUN/BLOCKED.

## Usage

```powershell
pwsh -NoProfile -File scripts/dsh/device-dsh-acceptance.ps1 `
    -Device <adb-serial> `
    -Apk <path\to\mca-debug.apk> `
    [-OutDir artifacts/dsh-v2/verification/device-acceptance-<stamp>] `
    [-Package com.muyuchat.mca] `
    [-SkipInstall] [-SkipUiNavigation] [-PreconditionsOnly] `
    [-BootTimeoutSeconds 180] [-InstallTimeoutSeconds 300] [-ReadyTimeoutSeconds 150] `
    [-SidebarSelectors ...] [-DshSelectors ...] [-ConsoleSelectors ...]
```

`-Device` is the serial from `adb devices -l`. `-Apk` is the built candidate APK; `-SkipInstall`
uses an already-installed build (then install evidence is NOT_RUN, not PASS). `-SkipUiNavigation`
runs only install/launch/log capture and marks UI steps NOT_RUN. `-PreconditionsOnly` runs
PRE-01..PRE-03 (adb/device/inventory/APK identity when given/dry-run install probe) and marks every
later step NOT_RUN; it is the safe mode for a device whose vendor install restriction is still
being resolved, and exits 1 (NOT_RUN is not PASS).

`adb` resolution order: `$env:ADB`, `adb` on PATH, `<repo>\local.properties` `sdk.dir`
`platform-tools\adb.exe`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`. No SDK path is baked in.

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Every executed step PASS (no FAIL/BLOCKED/NOT_RUN). |
| 1 | At least one FAIL, or only NOT_RUN/NOT_RUN-skipped work; see `summary.md`. |
| 3 | BLOCKED: missing adb, no/offline device, missing APK, vendor install restriction, device lost. |

BLOCKED and FAIL are never reported as PASS. A NOT_RUN step also prevents exit 0, because
"all steps pass" cannot be claimed for work that did not run.

## Steps and PARITY_ACCEPTANCE.md scenarios

| Step | What it proves | Scenarios |
| --- | --- | --- |
| PRE-01 | adb present, device serial online in state `device` | environment freeze §3.1 (precondition) |
| PRE-02 | model, SDK, ABI, android release, `/data` free KiB, screen, density, MIUI detection, adb-install verifier flag, `user_setup_complete` | environment freeze §3.1 (precondition) |
| PRE-03 | APK identity (path/sha256/size) + dry-run install session probe; on refusal: BLOCKED with exact device-side remediation | precondition for REG-01 |
| INST-01 | `adb install -r -t` of the candidate; vendor refusal maps to BLOCKED, APK/parse failure to FAIL | REG-01 precondition |
| APP-01 | launcher activity resolved, `am start -W`, app process appears within bound, app is foreground | REG-01, REG-02 |
| APP-02 | logcat slice, ANR/FATAL scan, screenshot, `ps -A -o PID,PPID,NAME` tree, process matches | REG-12 precheck |
| UI-01 | sidebar opened through uiautomator (selector-driven) | REG-01 |
| UI-02 | DSH entry opened, readiness observed (WebView node or ready log marker, bounded), first-frame screenshot | REG-01, REG-02 |
| UI-03 | DSH console opened and capability projection text captured (GRANTED/DENIED/NOT_DECLARED/NOT_ENABLED/UNSUPPORTED tokens) | REG-06 |
| UI-04 | post-navigation foreground, process tree, ANR/FATAL scan | REG-12 precheck |

PHONE-xx / ACTION-xx tool execution is **not** covered by this harness. UI-03 captures what the
console displays; it does not call `android_notify`, `android_overlay` or any tool. Those remain
separate cases (PHONE-03, PHONE-07, ACTION-03) and stay NOT_RUN until executed and evidenced.

## Evidence layout

```
<OutDir>/
  result.json          # machine-readable: steps, statuses, device/APK identity, overall/exit
  summary.md           # human summary + MIUI remediation when BLOCKED
  steps.log            # chronological step statuses
  commands.log         # every adb invocation + exit code (no tokens; no user content)
  preconditions/       # adb version/devices, getprop, df, wm, install probe, apk identity
  app/                 # resolve-activity, am start, activities, install result, ps tree
  logs/                # logcat slices, ANR/FATAL extracts
  ui-01*.xml|*.txt     # uiautomator dumps + tap actions per UI step
  *.png                # screencaps (app-first-frame, ui-02-first-frame)
```

Every step lists its evidence files in `result.json`/`summary.md`. A file listed but empty is not
evidence; the harness fails a step when the dump/XML/screenshot is missing.

## MIUI / HyperOS install restriction

The dry-run probe creates a package-install session and abandons it. It cannot prove commit-time
MIUI verification, so a passed probe is recorded with that caveat and the real install step maps
`INSTALL_FAILED_USER_RESTRICTED`, `INSTALL_FAILED_VERIFICATION_FAILURE`,
`INSTALL_CANCELED_BY_USER`, `SecurityException`/`not allowed` to BLOCKED.

Exact remediation (never automated, never bypassed):

1. Settings → About phone → tap **MIUI version / HyperOS version** 7 times to enable Developer options.
2. Settings → Additional settings → Developer options: enable **USB debugging** and **Install via USB**.
3. If **Install via USB** refuses to stay on, MIUI requires a signed-in Mi account and a SIM in the
   device. Sign in on the device yourself; the harness does not automate accounts.
4. Developer options → turn off **Verify apps over USB** / install verification (name varies by ROM).
5. During install, confirm the on-device MIUI prompt (**Allow**/确认) within the timeout.
6. Corporate/MDM devices may forbid sideloading; use a personal test device instead of bypassing policy.

## Assumptions and limitations

- The script is stored as UTF-8 **with BOM** so Windows PowerShell 5.1 parses the Chinese selector
  defaults; PowerShell 7 reads it too. Keep the BOM if you edit it. It avoids PowerShell-7-only
  APIs, so it runs on both editions (prefer 7).
- UI selectors are the documented defaults (`菜单/侧栏/导航/更多/Menu/Drawer`, `DSH/DeepSeek…`,
  `控制台/Console/能力…`). If the real UI uses different labels the step FAILs with the dumped XML;
  re-run with `-SidebarSelectors`, `-DshSelectors`, `-ConsoleSelectors`. A selector mismatch is never
  silently treated as success.
- Readiness is structural/log-based (WebView node or configured log markers) plus a screenshot; it
  does not judge rendered pixels.
- `adb logcat -c` is called before launch (bounded, standard for acceptance). Logs are captured
  device-wide; no token, credential or user content is written by the harness itself.
- The harness does not power on, unlock, grant permissions, accept MIUI prompts, or change settings.
  Those are user actions; a missing precondition becomes BLOCKED with the exact step needed.
- One run one device. Model/account/tool execution are out of scope (see PARITY_ACCEPTANCE.md).

## Self-test (no device)

```powershell
pwsh -NoProfile -File scripts/dsh/device-dsh-acceptance.ps1 -Device '' -Apk ''
# writes a blocked evidence dir, prints RESULT BLOCKED exit=3
pwsh -NoProfile -File scripts/dsh/device-dsh-acceptance.ps1 -Device 'no-such-serial' -Apk scripts/dsh/DEVICE-ACCEPTANCE.md
# adb present, serial absent -> BLOCKED (3), no crash
```

## Device states observed in round 3 (2026-10-21)

- On `f9d98aa3` (Xiaomi 2304FPN6DC, HyperOS OS3.0/Android 16) the first `adb install` was refused
  with `INSTALL_FAILED_USER_RESTRICTED`. Installing the same APK through the on-device Files
  installer succeeded; after that, `adb install` also succeeds on this device.
- `scripts/dsh/install-mca-via-fileexplorer.ps1` automates that Files-UI path (push, open
  Downloads, tap the APK, tap only Allow/Continue install/Continue update/Install/Done, then verify
  `lastUpdateTime`). It never taps Cancel/Deny and changes no system policy.
- The harness exposes `-AllowFilesUiInstall`: when `adb install` is refused by the vendor, it runs
  the helper and records `INST-01` from the real `lastUpdateTime` change. Without the switch the
  historical BLOCKED behaviour is unchanged.
- Real entry points on this build: `打开历史` (history drawer) → `打开 DSH 工作区` (DSH row) →
  `DSH 控制台` (console gear). The default selector lists already contain these labels.
- UI dumps are UTF-8; the harness reads them with `[System.IO.File]::ReadAllText(..., UTF8)` so
  Windows PowerShell 5.1 does not mangle Chinese labels.
- After a device reboot (2026-10-21 07:09) MIUI again refuses `adb install`
  (`INSTALL_FAILED_USER_RESTRICTED`) and the phone is on its secure lockscreen. The owner must
  unlock it and use the Files-UI path/`-AllowFilesUiInstall` again; the round-5 one-shot recorded
  `device smoke: BLOCKED` for exactly this reason.
- Green runs on the final round-3 APK (sha256 `c4b610da…97a743`):
  `artifacts/dsh-v2/verification/device-acceptance-20261021-r3j/` and
  `...-r3k/` — `PRE-01…UI-04 PASS`, `RESULT PASS exit=0`.

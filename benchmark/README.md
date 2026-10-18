# :benchmark — UI Macrobenchmark harness

This module measures the **real production `MainActivity`** of `:app`. It owns no product code and
nothing depends on it.

- Status: module + three scenarios are written; **no Gradle build and no device measurement was run**
  while adding them. The dependencies `androidx.benchmark:benchmark-macro-junit4:1.5.0` (and the
  transitive `androidx.test.uiautomator:2.4.0`) are not in the local Gradle cache, so the first
  enabled build needs network access to Google Maven. See
  `docs/proposals/audit/v9/MEASUREMENT-FACILITY.md`.
- The module is **opt-in** in `settings.gradle.kts`; the default build does not include `:benchmark`.

## Enable and build

```powershell
$env:MCA_INCLUDE_BENCHMARK = 'true'
# equivalent: append -Pmca.includeBenchmark=true to every Gradle command
.\gradlew.bat :app:assembleBenchmark :benchmark:assembleBenchmark
```

`app`'s `benchmark` build type is release-like (R8 + resource shrinking), non-debuggable, signed with
the local debug key, and uses `applicationIdSuffix = ".benchmark"` so its data cannot collide with a
real install.

## Required target manifest

Macrobenchmark requires the target app to be *profileable* on API 29+. The harness relies on
`app/src/benchmark/AndroidManifest.xml` (benchmark build type only; the production manifest is not
edited), which is now committed:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application>
        <profileable android:shell="true" tools:targetApi="29" />
    </application>
</manifest>
```

Without it `connectedBenchmarkAndroidTest` fails with a "not profileable" configuration error
rather than producing a number.

## Known device restriction (BLOCKED, not FAIL)

Some OEM ROMs (observed: Xiaomi HyperOS/MIUI) reject the first install of a new package from
`adb` with `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` unless *Install via USB* is
enabled in developer options and confirmed on-device. That blocks both `com.muyuchat.mca.benchmark`
and the benchmark test APK, so `:benchmark:connectedBenchmarkAndroidTest` cannot start.

Report this as **BLOCKED**, never as a benchmark FAIL: no measurement ran. Until the device is
unblocked, collect performance evidence with the update-in-place path instead (release APK of
`com.muyuchat.mca` + `am start -W` + `dumpsys gfxinfo` + `scripts/verify-ui-motion.ps1`).

## Prepare the device

1. Physical ARM64 device is strongly preferred; emulators do not produce representative numbers.
2. Install the benchmark target:
   `.\gradlew.bat -Pmca.includeBenchmark=true :app:installBenchmark`
3. Launch `com.muyuchat.mca.benchmark` once and prepare the fixture you need:
   - **all scenarios**: dismiss any first-run/update dialog and leave the app on the chat surface;
     a first-run overlay would otherwise be measured as part of cold startup and invalidate it;
   - **streaming**: load a local chat model (or configure a cloud chat model) and set the output
     budget to roughly 500 tokens. Without a ready backend the test is skipped (`NOT_RUN`), it is
     never reported as passing.
   - **image scroll**: have at least 20 images in the image gallery.
4. Keep the device awake, charging/idle, with notifications and background jobs quiet. Record
   temperature/battery/refresh rate with the results.

## Run

```powershell
# Discover the real task name first (the build type is `benchmark`).
.\gradlew.bat -Pmca.includeBenchmark=true :benchmark:tasks --all

.\gradlew.bat -Pmca.includeBenchmark=true `
  :benchmark:connectedBenchmarkAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.benchmark.ColdStartupBenchmark

.\gradlew.bat -Pmca.includeBenchmark=true `
  :benchmark:connectedBenchmarkAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.benchmark.StreamingTokenBenchmark `
  -Pandroid.testInstrumentationRunnerArguments.mca.iterations=10

.\gradlew.bat -Pmca.includeBenchmark=true `
  :benchmark:connectedBenchmarkAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.benchmark.ImageGalleryScrollBenchmark
```

Instrumentation arguments:

| Argument | Default | Meaning |
|---|---|---|
| `mca.iterations` | `10` | measured iterations (V9 7.5 wants ≥10 for stable scenes) |
| `mca.compilationMode` | `default` | `default`, `partial`, `full`, `none`; never compare across modes (`ignore` stays out: it is still an experimental Macrobenchmark API) |
| `mca.generationTimeoutMs` | `180000` | upper bound for one streaming response |
| `mca.streamingPrompt` | ~500-token Chinese prose request | prompt used by the streaming scenario |

## Selectors used

Macrobenchmark runs in its own process, so it cannot use Compose `onNodeWithTag` (the production
semantics tree does not enable `testTagsAsResourceId`). The harness uses only existing production
content descriptions / visible text:

- `打开历史` (history drawer), `图片` (gallery entry), `我的图片` (gallery marker)
- `新建聊天`, `问问 MCA` (composer), `发送`, `停止`
- model header readiness: the benchmark treats `待加载`, `选择模型`, `加载中`, `重新加载中`,
  `恢复参数中`, `停止中`, `需要重载`, `加载失败` as NOT ready (JUnit assumption -> `NOT_RUN`)

If one of these production strings changes, the benchmark fails loudly; do not "fix" it with screen
coordinates.

## Outputs

- Test XML (AGP 8.x connected tests): `benchmark/build/outputs/androidTest-results/connected/...`
  (the runner also scans `benchmark/build/test-results/` and `benchmark/build/reports/androidTests/`
  so an AGP layout change cannot hide the evidence)
- Macrobenchmark JSON / Perfetto traces:
  `benchmark/build/outputs/connected_android_test_additional_output/benchmarkAndroidTest/connected/<device>/`

`scripts/verify-ui-quality.ps1 -Lane macro -DeviceSerial <serial>` archives those files under
`artifacts/ui-quality/<runId>/` and records their SHA256. It returns `BLOCKED` when no device is
attached and never substitutes an emulator for device evidence.

## Result handling

- The runner's `PASS` covers task execution and a non-empty test XML only. A **performance PASS**
  additionally requires: non-zero valid frames, fixture consumed, prompt/model/compile-state
  recorded, and raw traces archived — checked by a human against the traces.
- `NOT_RUN` when: no device, no benchmark target, no model fixture, no gallery fixture, skipped test,
  or zero valid frames.
- `FAIL` when: generation never starts, generation does not terminate within the timeout, the chat
  surface never becomes interactive, or the gallery cannot be opened.
- The streaming scenario proves start/terminal of a generation; it does **not** count tokens or
  verify reply content. Report the token budget and prompt alongside any number so it cannot be
  misread as "exactly 500 tokens measured".

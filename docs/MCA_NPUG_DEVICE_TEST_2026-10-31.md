# Tester message — MCA NPUG/DLA four-chunk chain (2026-10-31)

This is the **mainline** test: reference NPUG topology + Neuron DLA restore, not the LiteRT JIT diagnostic app.

## Artifact

```
D:/anima-spike/dist-probe/mca-mediatek-chain-androidTest-20261031-r2.apk
bytes    22,381,497
SHA-256  1884eb09e1b5bfc98949d9db6390310b10292bb1aee579484b5b742cc8541e2d
package  com.muyuchat.core.mediatek.test
runner   com.muyuchat.core.mediatek.test/androidx.test.runner.AndroidJUnitRunner
```

It contains the production `MediaTekNpugExecutor` and the narrow Neuron JNI adapter. No vendor binary is bundled; the adapter `dlopen`s the on-device Neuron runtime. The r2 build adds the final-latent export (`final-latent.f32`); it supersedes the earlier 20261031 APK.

## Production v4 bundle (fixed ACTX)

```
E:/MAC/build/mca-mediatek-reference-v4-fixed.zip
bytes    1,830,262,377
SHA-256  438440754de27f49a395bce448dc8c2fe2df944c83f90a980b438e431fdbce9f
```

Built by `python tools/mediatek/build_v4_bundle.py` from the pinned four-chunk manifest and the locked assets. It contains 4 stubs, 4 DLAs, 4 routes, `ctx.bin`, AMOD, RoPE and VAE; envelope `mca.image_engine.bundle.v1`, AOT manifest `mca.mediatek-image/4`, profile `reference-1024-32x32` (512x512, 6 steps), conditioning FIXED_CONTEXT. Import it into the production app, then run `t08-npug-acceptance.ps1` with `EXPECT_PROMPT_SUPPRESSED=yes`.

A dynamic-text variant is also available:

```
E:/MAC/build/mca-mediatek-reference-v4-dynamic.zip
bytes    3,292,929,429
SHA-256  e4c020640fcf567ce743c364a36a67a85a4eb29d54a166025f6db132cd7070fe
```

It declares DYNAMIC_TEXT with the pinned `text.bin`/`tok.bin` (18 components) and `recommendationId=mediatek_anima_npu_np9`. ZIP entries were verified against the envelope pins (paths, sizes and SHA-256). Readiness was verified by the production parser (`MediaTekReferenceBundleHostTest`, 2/2 PASS, 7.968 s): both envelopes, AOT manifests, 4 variants and all asset pins are accepted. The bundles are local-only: the assets' redistribution rights remain blocked, so do not publish the ZIP or its contents.
**2026-11-02 update:** after reviewing CircleStone NC v1.2 and the NVIDIA notices, the v4 derivative bundles were published on ModelScope as `lyydfys/anima-npug-dla-mt6993-nc` under the non-commercial terms. The sentence above records the 2026-10-31 position; this repository still contains no weights or bundle ZIPs.

## One-tap harness APK (no adb scripts, 1.0.17-style)

```
D:/anima-spike/dist-probe/mca-mtk-chain-harness-20261031-r3.apk
bytes    684,193,834
SHA-256  7021196f2dd1e5d16140358733d4329aa5eec111b78f3ed735e62f0b8796ee79
package  com.muyuchat.mca (debug, 0.2.4)
launcher MCA MTK Chain Test
```

It contains the production `executeMediaTekAotGeneration` path plus the Neuron JNI adapter. r3 fixes the VAE input denormalization (the previous 8 px grid); r2 had fixed the runtime loader: it tries `libneuronusdk_adapter.9.mtk.so` first (the name seen in the working 1.0.17 log), scans vendor/system lib dirs, and reports every discovered `*neuron*.so` when loading fails. The tester only installs the APK, copies a bundle ZIP to the phone, opens **MCA MTK Chain Test**, picks the ZIP, and waits. The run writes `result.png`, `metadata.json`, `final-latent.f32`, `run.log` under `Android/data/com.muyuchat.mca/files/mtk-chain-runs/<runId>/`; the **Share last results** button sends a ZIP with those files.

Steps:

1. Uninstall any existing `com.muyuchat.mca` app (the debug APK uses the debug signature and cannot update a release install), or use a spare device.
2. Install the harness APK.
3. Copy `mca-mediatek-reference-v4-fixed.zip` to the phone (Downloads or any folder). For the dynamic run, copy the dynamic ZIP and enter a prompt in the app.
4. Open **MCA MTK Chain Test**; tap **Select bundle ZIP and run**; pick the fixed ZIP first.
5. Keep the phone awake and charging; extraction plus 6 steps can take several minutes.
6. When the screen shows `PASS`, tap **Share last results** and send the ZIP. Then repeat with the dynamic ZIP and prompt `a cat`.
7. If it shows `FAIL` or crashes, send the share ZIP (it contains `error.txt`/`run.log`) and logcat filtered by `MCA-MTK-CHAIN`.

Expected in `metadata.json`: `executionStatus=NPUG_CHAIN_AND_VAE_COMPLETED`, `qualityStatus=NOT_VERIFIED_NO_REFERENCE_BASELINE`, `acceptanceStatus=NOT_RUN`, 24 chunk runs. `final-latent.f32` is 16×64×64 FP32 (262,144 bytes). This is a diagnostic bridge; it does not replace the production UI/Local API acceptance, but it produces the same device evidence in the 1.0.17 style.

## Production app with the MediaTek model card (2026-11-01)

```
D:/anima-spike/dist-probe/mca-mediatek-app-20261101.apk
bytes    684,193,834
SHA-256  1515360a19a025376cae1d28a65cb83e100a8806eeeeb82ba7b42f750e5fa374
```

It contains the VAE denormalization fix, the model-hub "天玑 NPU" section (one `mediatek_anima_npu_np9` card describing the Anima AOT variant matrix), the card action **导入本地包**, and the one-tap chain harness.

Steps: uninstall the old `com.muyuchat.mca` debug build, install this APK, copy the v4 bundle ZIP to the phone, open MCA → Model Hub → Recommended → 天玑 NPU → Anima · 天玑 NPU → **导入本地包** → select the ZIP. After import, open the local model and generate 512×512 / 6 steps. The card text lists the other SoC/resolution variants as unverified; they are not separate model cards.

## First Dimensity device run (2026-10-31)

The tester ran the first harness APK on a MediaTek device with the fixed bundle. The bundle side passed:

- extracted 1,745 MB; bundle id `mediatek-reference-anima-512-fixed`; `conditioning=FIXED_CONTEXT`; profile `reference-1024-32x32`; steps=6; variants=4; `blockers=[]`.
- It reached `mediatek_npug_context 0/6` and then failed at the native runtime load:
  `dlopen failed: library "/system/lib/libneuronusdk.so" not found`.

Root cause: the adapter searched for `libneuronusdk.so` / `libNeuronAdapter.so`, but the working 1.0.17 log shows the device runtime is `libneuronusdk_adapter.9.mtk.so`. The loader now tries that name first, scans `/vendor|/system|/odm|/product` `lib64`/`lib` for `*neuron*.so`, and retries through the vendor namespace; the failure text lists the discovered paths. Re-run the fixed bundle with the r2 harness APK.

## Host prerequisites

- MediaTek Dimensity device (the tested V2545A/MT6993 is fine), adb authorized.
- Repo checkout `E:/MAC` and assets at `E:/mtk-npu-reference-assets`:
  - `dva1/dva2/dva3/ditc1c` L1024s4 `.dla` (about 447 MB each)
  - `vaedec_64.dla` (26 MB), `modsq16s4_1024_6.bin` (6 MB), `rope_32x32.bin`
  - `text.bin` (1.46 GB) and `tok.bin` (2.27 MB) for the dynamic-text run
- Device free space: about 5 GB (four DLAs + VAE + AMOD + 1.46 GB text + kit).

## Run order (from `E:/MAC`)

```bash
# 1) runtime trace
python tools/mediatek/run_neuron_smoke.py --mode probe --apk D:/anima-spike/dist-probe/mca-mediatek-chain-androidTest-20261031-r2.apk --install

# 2) one compiled-network restore/run/close
python tools/mediatek/run_neuron_smoke.py --mode restore --apk D:/anima-spike/dist-probe/mca-mediatek-chain-androidTest-20261031-r2.apk

# 3) fixed-ACTX four-chunk chain + VAE (1 step by default)
python tools/mediatek/run_neuron_smoke.py --mode chain --apk D:/anima-spike/dist-probe/mca-mediatek-chain-androidTest-20261031-r2.apk

# 3b) after the 1-step smoke passes: full planned steps to exercise Euler state across steps
python tools/mediatek/run_neuron_smoke.py --mode chain --run-steps 6 --apk D:/anima-spike/dist-probe/mca-mediatek-chain-androidTest-20261031-r2.apk

# 4) dynamic text: pushes text.bin/tok.bin, Qwen3+adapter on device, same four chunks
python tools/mediatek/run_neuron_smoke.py --mode chain --dynamic-text --prompt "a cat" --apk D:/anima-spike/dist-probe/mca-mediatek-chain-androidTest-20261031-r2.apk
```

Each run writes `report.json` into the kit dir (`artifacts/device/mca-neuron-smoke` by default) and prints it. For the dynamic run it also pulls `dynamic-actx.bin`.

Once the chain reports are healthy, import the v4 bundle into the production app and run the Local API acceptance (two generations, cancellation/retry evidence):

```powershell
powershell -ExecutionPolicy Bypass -File tools/device/t08-npug-acceptance.ps1 -ModelId <installed-model-id> -Runs 2
```

The script expects an already installed/imported v4 bundle; it does not build or install. It emits `artifacts/device/npug-acceptance` and only prints PASS on `NPUG_ACCEPTANCE_PASS`.

## What counts as evidence

- `probe`: `runtime.status=READY`, library path/version, devices.
- `restore`: one DLA restored and executed; report `status=COMPLETED`.
- `chain`: `baseline` object with model/manifest/profile/latent/chunk/output SHA-256; per-chunk min/max/mean/std; `final-latent.f32` (16×64×64 FP32, `finalLatentSHA256/Min/Max/Mean/Std`); `status=COMPLETED`.
- `chain --dynamic-text`: additionally `textEncoding` with qwen/t5 token ids, context SHA-256, elapsedMs, and `dynamic-actx.bin` (ACTX v1, 256×1024 FP32).

The chain driver now runs this evidence gate automatically after pulling the report and tensors; rerun it manually when needed (completeness, hashes, tensor sizes; not image quality):

```bash
python tools/mediatek/validate_chain_report.py artifacts/device/mca-neuron-smoke/report.json
```

Compare captured tensors offline:

```bash
python tools/mediatek/compare_latent.py <reference.f32> <final-latent.f32>
python tools/mediatek/compare_actx.py <reference-actx.bin> <dynamic-actx.bin>
```

**Do not treat `status=COMPLETED` as numeric/quality success.** `status=COMPLETED` only says the native chain returned; the report also carries `qualityStatus=NOT_VERIFIED_NO_REFERENCE_BASELINE` and `deviceVerification=NOT_RUN` (here `deviceVerification` means "verified against a reference baseline", not "not executed on a device"). A reference comparison is a separate step.

## If it fails

Send `report.json`, the pulled `dynamic-actx.bin` (if any), the kit `chain-config.json`, and logcat lines containing `Neuron`, `neuron`, `MediaTek`, or `mca`. Do not retry by swapping in a different DLA or changing the manifest; the pins are identity evidence.

## Out of scope

The 1.0.18 `com.muyuchat.mca.mediateknputest` APK is the LiteRT JIT second channel. Its NPU run is expected to fail (`FAILED_DIVERGED`) because of fp16 range; that result is not a substitute for this chain run.

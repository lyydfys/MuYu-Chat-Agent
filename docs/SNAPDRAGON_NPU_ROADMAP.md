# Snapdragon NPU Roadmap

This document defines MCA's product-grade path for local multimodal vision and
local image generation on Snapdragon NPU devices. It is intentionally strict:
device capability, packaged runtime, complete model bundle, and actual NPU
execution are separate states.

For the full product and engineering blueprint, including UI placement, bundle
download flow, acceptance gates, and release language, see
[`MCA_SNAPDRAGON_NPU_FLAGSHIP_PLAN.md`](MCA_SNAPDRAGON_NPU_FLAGSHIP_PLAN.md).

## Sources

- Google LiteRT Qualcomm NPU documentation:
  <https://developers.google.com/edge/litert/android/npu/qualcomm>
- Google LiteRT Qualcomm AI Engine Direct notes:
  <https://developers.google.com/edge/litert/next/qualcomm>
- Qualcomm AI Engine Direct SDK:
  <https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk>
- Qualcomm QAIRT overview:
  <https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-50/introduction.html?product=1601111740009302>
- External Android Stable Diffusion quality reference, used only as a local
  behavior and output baseline; no source is copied into MCA.

## Device Tiers

MCA profiles devices through `DeviceAccelerationProfile`.

| Tier | SoC codes | QNN target | MCA behavior |
|---|---|---|---|
| Snapdragon 8 Elite Gen 5 | `SM8850`, `SM8850P` | Highest priority NPU candidate | Enable NPU validation surfaces when runtime and bundles are present. |
| Snapdragon 8 Elite | `SM8750`, `SM8750P` | Primary flagship target | Target FastVLM/LiteRT vision and SD1.5 QNN image first; SDXL stays experimental. |
| Snapdragon 8 Gen 3 | `SM8650`, `SM8650P`, `SM8635` | Strong candidate | SD1.5 QNN candidate; SDXL can be shown only as experimental with enough RAM. |
| Snapdragon 8 Gen 2 | `SM8550`, `SM8550P`, `QCS8550`, `QCM8550` | Baseline candidate | SD1.5 QNN image and small LiteRT vision only. |
| Snapdragon 8 Gen 1 | `SM8450`, `SM8475` | Conservative candidate | SD1.5-only validation; no SDXL default. |
| Other / unknown | any other code | Fallback | Keep CPU/MNN/GGUF routes and hide NPU claims. |

## State Machine

MCA must use these states in code, UI, docs, and release notes.

| State | Evidence | User claim |
|---|---|---|
| Device candidate | SoC tier detected by `DeviceAccelerationAnalyzer`. | "This device can validate the Snapdragon NPU route." |
| Runtime missing | QNN libraries are not found. | "Device is capable; QNN runtime is not packaged." |
| Runtime files found | `libQnnSystem.so`, `libQnnHtp.so`, and an HTP skel library are detected. | "QNN runtime files were found." |
| Runtime load not requested | Runtime files are present, but MCA has not called the native load probe. | "QNN runtime still needs validation before smoke." |
| Runtime load failed | Runtime files are present, but `System.load` or the native probe failed. | "QNN runtime is present but cannot run on this build/device yet." |
| Runtime loadable | Runtime files are present and the native load probe succeeds. | "QNN runtime can enter smoke validation." |
| Bundle incomplete | Required model components are missing. | "The engine package is incomplete." |
| Smoke failed | A graph smoke run failed or crashed. | "This engine is not selectable." |
| Smoke passed | Required QNN subgraphs execute without crash. | "The NPU subgraph path works; full image generation is not proven yet." |
| Pipeline probe passed | UNet and VAE decoder execute sequentially and write an image artifact. | "The NPU image graph chain works; prompt semantics still need text encoder and scheduler." |
| Semantic generation passed | Tokenizer/text encoder, scheduler loop, UNet, and VAE produce an image from a prompt. | "This engine can be selected for local NPU image generation." |
| NPU active | Logs prove QNN/HTP graph execution. | "This run used Snapdragon NPU." |

## Local Vision Track

The first NPU vision target should be a small LiteRT/LiteRT-LM bundle, not a
large GGUF vision model.

### Recommended first target

- Runtime: LiteRT / LiteRT-LM with Qualcomm AI Engine Direct delegate.
- Model class: FastVLM-0.5B-class or equivalent small VLM.
- Input: one JPEG/PNG resized by MCA's existing image preprocessing pipeline.
- Output: short Chinese and English image description smoke prompts.

### Required implementation

1. Add a `LocalVisionBundleSpec` equivalent for LiteRT/QNN bundles.
2. Download or import a complete vision bundle instead of a single file.
3. Add a native/runtime wrapper that can report:
   - delegate backend,
   - runtime library versions,
   - first-token latency,
   - peak memory,
   - whether QNN/HTP was used.
4. Keep GGUF+`mmproj` and MNN multimodal as fallback paths.
5. Keep an isolated MNN vision runner as a future hardening option, not as a
   product admission gate. MNN CPU vision is available through both production
   UI and Local API whenever native reports `visionReady=true` and the
   representative-device dual-surface gate has passed.

## Local Image Track

The first NPU image target should be an SD1.5-style QNN bundle. Large or newer
architectures can be shown later only after they pass the same gate.

### Optional QNN SDK build binding

`core:native` now supports an optional Qualcomm AI Runtime / QNN SDK root. This
does not package model weights or Qualcomm SDK files into the repository; it
only lets local builds compile typed QNN headers when the SDK is available.

Use one of:

```powershell
$env:MCA_QNN_SDK_ROOT="D:\path\to\qairt"
.\gradlew.bat '-Pmca.abis=arm64-v8a' :core:native:externalNativeBuildDebug
```

or:

```powershell
.\gradlew.bat '-PmcaQnnSdkRoot=D:\path\to\qairt' '-Pmca.abis=arm64-v8a' :core:native:externalNativeBuildDebug
```

The expected header layout is:

```text
<QNN SDK root>/
  include/
    QNN/
      QnnInterface.h
      QnnBackend.h
      QnnContext.h
      QnnGraph.h
      QnnTensor.h
```

When these headers are found, `libmca_qnn_native.so` reports
`sdkHeadersPresent=true` and `typedGraphBindingsCompiled=true` in QNN smoke
diagnostics. This is still not enough to claim NPU execution; `NPU_ACTIVE`
requires a successful graph execute on device.

### Bundle layout

```text
bundle-id/
  manifest.json
  runtime/
    libQnnSystem.so              optional when APK packages runtime globally
    libQnnHtp.so                 optional when APK packages runtime globally
    libQnnHtpVxxSkel.so          optional when APK packages runtime globally
  diffusion/
    *.bin | *.ctx | *.so
  vae/
    *.bin | *.ctx | *.so
  text_encoder/
    *.bin | *.ctx | *.so
  tokenizer/
    tokenizer.json | vocab.json | merges.txt
  smoke.json
```

### Manifest contract

```json
{
  "schema": "mca.image_engine.bundle.v1",
  "id": "sd15-qnn-min",
  "runtime": "QNN_HTP",
  "accelerator": "QNN_HTP",
  "minDeviceTier": "SNAPDRAGON_8_GEN2",
  "family": "SD15",
  "components": [
    {"role": "DIFFUSION", "path": "diffusion/unet_context.bin", "sha256": "..."},
    {"role": "VAE", "path": "vae/vae_decoder_context.bin", "sha256": "..."},
    {"role": "TEXT_ENCODER", "path": "text_encoder/clip_context.bin", "sha256": "..."},
    {"role": "TOKENIZER", "path": "tokenizer/tokenizer.json", "sha256": "..."}
  ],
  "smoke": {
    "width": 384,
    "height": 384,
    "steps": 1,
    "timeoutSeconds": 180,
    "prompt": "a small ceramic cup on a bright wooden desk",
    "graphName": "sd15_unet",
    "contextBinary": "diffusion/unet_context.bin",
    "inputs": [
      {"name": "latent", "dataType": "float32", "shape": [1, 4, 48, 48]},
      {"name": "timestep", "dataType": "int32", "shape": [1]},
      {"name": "text_embeddings", "dataType": "float32", "shape": [1, 77, 768]}
    ],
    "outputs": [
      {"name": "noise_pred", "dataType": "float32", "shape": [1, 4, 48, 48]}
    ]
  }
}
```

The QNN smoke metadata is mandatory for graph execution. MCA treats a bundle as
graph-smoke-ready only when `contextBinary`, at least one input tensor, and at
least one output tensor are declared with names, data types, and shapes. This is
separate from structural bundle completeness and still does not imply NPU
execution.

### Runner requirements

The QNN image runner must be process-isolated from the Compose UI process and
must expose:

- `health`: runtime and backend readiness.
- `load`: graph/context load result and memory usage.
- `smoke`: 1-step validation with fixed metadata.
- `generate`: queued, loading, step, decoding, complete, failed progress.
- `cancel`: cooperative cancel plus forced process teardown fallback.

The runner must not make an engine selectable for the production image page
until semantic generation passes. `QNN_SMOKE_PASSED` and
`QNN_PIPELINE_PROBE_PASSED` are diagnostic milestones only.

## UI Rules

Do not rewrite MCA's design language. Add status inside existing surfaces:

- Model Hub recommendation cards show runtime, accelerator, minimum device tier,
  and smoke dimensions.
- Local image engine cards show `QNN HTP / Snapdragon NPU` only for QNN bundles.
- Image page selection lists only engines whose readiness is clear.
- If the device is capable but runtime is missing, say exactly that.
- If runtime is ready but smoke failed, show the error and keep the engine
  unselectable.

## Current Code Status

| Area | Current state |
|---|---|
| Device profiling | Implemented in `core:deviceprofile` with unit coverage for `SM8550`, `SM8750P`, `SM8850`, runtime missing, runtime ready, and non-Snapdragon fallback. |
| QNN runtime detection | Implemented by scanning device vendor, app/native, managed, and debug runtime directories for QNN system, HTP, and skel libraries. Vendor runtime directories are preferred for smoke validation because the SM8750P test device can open the OEM HTP path there, while the side-loaded QAIRT 2.45 runtime is transport-blocked on the same device. The status separates files found, native load probe not requested, native load failed, and loadable. |
| Local image bundle metadata | Implemented in `core:download` with runtime, accelerator, minimum device tier, and smoke spec. |
| Recommendation UI | Existing Model Hub cards show runtime, accelerator, minimum device tier, and smoke shape for image bundles. |
| Local image registry | Can represent `QNN_HTP` engines and blocks them until a real smoke test passes. |
| Local image registry | Can represent `QNN_HTP` engines. QNN smoke/probe milestones are recorded separately from production readiness, so a subgraph smoke pass does not expose the engine as a finished image-page generator. |
| Native QNN bridge | Implemented in `core:native` as `libmca_qnn_native.so`. The bridge can load-probe QNN runtime libraries, detect `QnnInterface_getProviders`, inspect engine bundle files, parse smoke tensor metadata, and return structured smoke diagnostics. When built with QNN/QAIRT headers it now attempts the typed `backendCreate -> deviceCreate -> contextCreateFromBinary -> graphRetrieve -> tensor bind -> graphExecute` smoke path. The tensor bind step reads QNN System context metadata and preserves backend tensor IDs, matching the standalone SD1.5 UNet and VAE decoder smoke runs that reached `graphExecute=true` on SM8750P. A debug-only pipeline probe now chains UNet and VAE decoder and writes a PNG artifact, but production semantic generation still requires tokenizer/text encoder and scheduler integration. Context-load failures still explicitly call out likely runtime/context SDK, target SoC, or binary-configuration mismatches. |
| QNN image runner | Partially wired. App-level readiness now calls the native QNN bridge and requires `graphRunnerReady + graphExecute + npuActive + smokePassed` before reporting `NPU_ACTIVE`. Native smoke results also report `executionStage` and per-stage booleans such as runtime loaded, QNN interface found, bundle graph artifact found, smoke metadata complete, tensor buffer plan ready, SDK headers compiled, backend created, context loaded, graph resolved, tensors bound, and graph executed. A standalone SD1.5 UNet context has passed QNN/HTP graph execution; full image generation still requires CLIP/text encoding, scheduler loop, VAE decode, progress, cancel, and output file registration. |
| LiteRT/QNN vision runner | Partially wired. App-level readiness now calls the native QNN bridge, passes bundle smoke tensor metadata, and applies the same graph-execution proof gate and stage diagnostics. Real LiteRT/QNN vision graph execution is still pending. |

## Latest Device Evidence

The device collector is available at `tools/qnn/collect-device-qnn.ps1`.

On 2026-07-09, the connected Xiaomi `25091RP04C` device reported:

- SoC: `SM8750P`
- Android: `16` / SDK `36`
- ABI: `arm64-v8a`
- Complete readable QNN runtime found at `/data/local/tmp/mca-qnn`
- System QNN files also visible under `/vendor/lib64` and `/vendor/lib/rfsa/adsp`

Evidence file:

- `docs/experiments/qnn-sm8750p-runtime-scan-2026-07-09.md`

This proves runtime discovery on one Snapdragon 8 Elite-class device.

Follow-up smoke evidence on the same device shows:

- Side-loaded QAIRT 2.45 runtime under `/data/local/tmp/mca-qnn` can create the
  QNN backend but fails HTP transport setup during `QnnDevice_create`.
- The OEM runtime under `/vendor/lib64` with skel under `/vendor/lib/rfsa/adsp`
  can pass `QnnDevice_create`.
- The current add-one context binary then fails `QnnContext_createFromBinary`
  with an SDK/context-version mismatch because the device vendor runtime reports
  QNN HTP `v2.29.0...`, while the test context was generated with a newer
  QAIRT/QNN toolchain.

Follow-up UNet evidence on the same device shows:

- The `CyberRealistic_Final-SD1.5-qnn2.28` UNet context loads through the OEM
  QNN runtime.
- QNN System metadata exposes `sample`, `timestamp`, `text_embedding`, and
  `output` tensors with backend IDs.
- Binding tensors from that metadata allows `QnnGraph_execute` to pass.
- The standalone runner printed `graphExecute=true checksum=4153132`;
  measured graph execution was about 186 ms after context load.
- The same metadata-binding runner also executed `vae_decoder.bin`; measured
  graph execution was about 457 ms after context load.

Therefore the current NPU image status is: one SD1.5 UNet graph executes on
QNN/HTP and the matching VAE decoder graph executes on QNN/HTP, but full image
generation is not done until CLIP, scheduler, repeated UNet execution, image
saving, progress, cancel, and fallback are wired and tested in-app.

## Release Gate

Do not claim usable NPU vision or NPU image generation until all items pass on a
real device:

- exact APK version and git commit recorded,
- exact device and SoC recorded,
- QNN runtime detection captured,
- model bundle id and component hashes captured,
- smoke test dimensions and steps captured,
- elapsed time and peak memory captured,
- logs prove QNN/HTP graph execution,
- no crash, ANR, or fatal signal after the run,
- fallback behavior verified after removing or corrupting one required bundle
  component.

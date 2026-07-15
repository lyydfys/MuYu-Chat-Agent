# MCA Snapdragon NPU Flagship Plan

This plan treats "MAC" as MCA Android. It defines the product and engineering
path for local multimodal image understanding and local image generation on
Snapdragon phones, with Snapdragon NPU as a real, evidence-gated accelerator.

The target devices are Snapdragon 8 Gen 2, Snapdragon 8 Elite, and Snapdragon
8 Elite Gen 5 class phones. The design must also scale to other Snapdragon
devices through tiered capability detection instead of user-specific hardcoding.

## Non-Negotiable Product Boundary

MCA must never collapse these states into one "accelerated" label:

| State | Meaning | Allowed user wording |
|---|---|---|
| Device candidate | The SoC is in a known Snapdragon tier. | "This device can validate the Snapdragon NPU route." |
| Runtime found | QNN/QAIRT libraries and HTP skel files are present. | "QNN runtime files were found." |
| Runtime loadable | Native load probe can load the runtime. | "QNN runtime can enter smoke validation." |
| Bundle complete | The model package has all required graph/model components. | "The engine package is complete." |
| Graph metadata ready | Context binary, graph name, tensor names, data types, shapes, and buffer sizes are declared. | "Graph smoke metadata is ready." |
| Graph execute passed | A real QNN/LiteRT run completed on device. | "Smoke test passed." |
| NPU active | Logs prove QNN/HTP graph execution for the actual request. | "This run used Snapdragon NPU." |

This is the core product promise: Snapdragon NPU is feasible, but only for
certified model bundles with matching graph/runtime metadata. Arbitrary GGUF
image-generation files must stay CPU/MNN/stable-diffusion.cpp compatible paths.

## Reference Stack

- Qualcomm AI Engine Direct / QAIRT / QNN: native Snapdragon NPU graph route.
- Google LiteRT Qualcomm NPU delegate: productized Android route for supported
  LiteRT models and compiled models.
- Local Dream: proof that Android Stable Diffusion-style generation can use
  Snapdragon NPU with model-specific packages.
- Qualcomm AI Hub: preferred source for QNN/LiteRT-ready examples and model
  conversion patterns.
- ModelScope: preferred download source when an equivalent public bundle exists.

References:

- <https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk>
- <https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-50/introduction.html?product=1601111740009302>
- <https://developers.google.com/edge/litert/android/npu/qualcomm>
- <https://developers.google.com/edge/litert/next/qualcomm>
- <https://github.com/xororz/local-dream>
- <https://github.com/qualcomm/ai-hub-models>

## Product Architecture

MCA should expose three local AI lanes:

| Lane | Primary backend | Fallback backend | Product status |
|---|---|---|---|
| Local chat | MNN CPU bundles | GGUF / llama.cpp CPU | Stable local capability |
| Local vision chat | LiteRT-QNN / QNN HTP bundles | GGUF + projector / MNN vision | Alpha now, NPU experimental after smoke |
| Local image generation | QNN/QAIRT certified SD bundles | stable-diffusion.cpp / MNN diffusion CPU | Experimental until device proof |

The UI should keep the existing MCA design language. Add capability and status
inside existing Model Hub, chat, and image-generation surfaces instead of doing
a broad visual redesign.

## Device Capability Profile

`DeviceAccelerationProfile` is the authoritative gate for NPU surfaces.

Required inputs:

- SoC code from Android device metadata, normalized as `SM8750`, `SM8850`,
  `SM8550`, and similar.
- Android version and supported ABI.
- Total and available RAM.
- Battery, charging, and thermal state.
- QNN runtime file scan.
- Native load probe result.
- Model bundle minimum tier.

Tier policy:

| Tier | Expected SoC codes | Vision default | Image default |
|---|---|---|---|
| Snapdragon 8 Elite Gen 5 | `SM8850`, `SM8850P` | Enable NPU validation surfaces when runtime exists. | SD1.5 QNN first; SDXL QNN experimental. |
| Snapdragon 8 Elite | `SM8750`, `SM8750P` | Primary FastVLM/LiteRT target. | SD1.5 QNN first; SDXL QNN experimental. |
| Snapdragon 8 Gen 3 | `SM8650`, `SM8650P`, `SM8635` | Small LiteRT/QNN vision target. | SD1.5 QNN; SDXL only with enough RAM. |
| Snapdragon 8 Gen 2 | `SM8550`, `SM8550P`, `QCS8550`, `QCM8550` | Small vision smoke only. | SD1.5 QNN only. |
| Older / unknown | Other | CPU/MNN/GGUF fallback. | CPU/MNN/stable-diffusion.cpp fallback. |

## Local Multimodal Vision Plan

### First flagship target

The first NPU vision target should be a small LiteRT-QNN VLM bundle, such as a
FastVLM-0.5B-class model or a Qualcomm AI Hub equivalent. This is more realistic
than starting with a large Qwen-VL or full multimodal GGUF model on NPU.

### Product behavior

- Chat image attachment stays in the existing composer.
- If the selected local model is text-only, MCA shows a clear switch prompt:
  "Current local model cannot read images; switch to a local vision engine."
- If a cloud multimodal engine is selected, image input uses the existing cloud
  vision path.
- If a local vision engine is selected, the image is preprocessed locally,
  passed to the local vision runner, and never leaves the device.
- The local API should advertise `vision_ready` and reject image turns with a
  specific error when the loaded local engine cannot process images.

### Vision bundle contract

```text
vision-bundle-id/
  manifest.json
  model/
    *.litert | *.tflite | *.ctx | *.bin
  tokenizer/
    tokenizer.json | tokenizer.model | vocab.*
  projector/
    optional projector/mmproj files
  runtime/
    optional QNN/HTP runtime files
  smoke.json
```

Required manifest fields:

- `schema`: `mca.vision_engine.bundle.v1`
- `runtime`: `LITERT_QNN` or `QNN_HTP`
- `accelerator`: `QNN_HTP`
- `minDeviceTier`
- `components`
- `smoke.graphName`
- `smoke.contextBinary`
- `smoke.inputs`
- `smoke.outputs`
- `smoke.timeoutSeconds`

### Vision acceptance gates

- One image can be described ten consecutive times without crash or manual
  reload.
- A failed run does not poison the next text-only chat turn.
- Local API image requests preserve role/system prompts.
- `NPU_ACTIVE` is never emitted unless QNN/LiteRT graph execution is proven.
- Logs record device, bundle id, runtime paths, graph name, elapsed time, and
  peak memory.

## Local Image Generation Plan

### First flagship target

Use SD1.5-style QNN/QAIRT bundles first. This is the shortest path to real
device proof because the graph shape, text encoder, UNet, VAE, and scheduler are
well understood and Local Dream has already proven the product direction.

Do not use FLUX, Z-Image, Qwen-Image, or other frontier architectures as the
first NPU target. Keep them visible as advanced experiments after SD1.5 and SDXL
QNN paths are proven.

### Image bundle contract

```text
image-bundle-id/
  manifest.json
  runtime/
    libQnnSystem.so
    libQnnHtp.so
    libQnnHtpVxxSkel.so
  diffusion/
    unet_context.bin | transformer_context.bin | *.ctx
  vae/
    decoder_context.bin | ae_context.bin
  text_encoder/
    clip_context.bin | t5_context.bin | qwen_context.bin
  tokenizer/
    tokenizer.json | vocab.json | merges.txt
  scheduler/
    scheduler.json
  smoke.json
```

Manifest requirements:

- component roles: `DIFFUSION`, `VAE`, `TEXT_ENCODER`, `TOKENIZER`, optional
  `SCHEDULER`.
- SHA-256 for every downloaded file.
- minimum Snapdragon tier.
- default smoke size, steps, and timeout.
- QNN graph name and context binary.
- all smoke input/output tensors with names, shapes, data types, and byte sizes.

### Runtime behavior

The image runner should be isolated from the Compose UI process:

```text
MCA UI
  -> LocalImageEngineRegistry
  -> LocalImageBackendService
  -> native QNN image runner
       health
       load
       smoke
       generate
       cancel
```

Required progress events:

- `queued`
- `loading_runtime`
- `loading_graph`
- `encoding_prompt`
- `denoising_step`
- `decoding_vae`
- `saving_image`
- `complete`
- `failed`
- `cancelled`

The cancel action must be cooperative first and process-teardown fallback second.
The main app must survive native runner crashes.

### Image acceptance gates

- SD1.5 QNN 384 smoke passes once on Snapdragon 8 Gen 2 or newer.
- SD1.5 QNN 512 passes on Snapdragon 8 Elite.
- Five consecutive runs do not crash or leak enough memory to block the sixth.
- Cancel works during model loading and denoising.
- A corrupted VAE or missing tokenizer keeps the engine unselectable.
- Logs prove QNN/HTP graph execution before the app shows "NPU active".

## UI Placement

Keep the existing MCA visual language and navigation model.

Model Hub:

- `Local inference engines`: MNN and GGUF chat models.
- `Local vision engines`: LiteRT-QNN, MNN vision, GGUF + projector.
- `Local image engines`: CPU compatible, QNN experimental, and verified NPU
  packages.

Chat:

- Keep the current top model capsules.
- Image attachments live in the existing composer.
- The active model capsule should indicate vision support only when the runner
  reports readiness.

Images:

- Keep the current local/cloud engine capsule design.
- Local engines show `CPU`, `QNN experimental`, or `NPU verified`.
- Generating cards show stage, elapsed time, cancel, and backend.
- Finished image details show model, runtime, accelerator, dimensions, steps,
  elapsed time, and whether the run proved NPU execution.

Settings / diagnostics:

- Add a Snapdragon acceleration diagnostics page only under advanced settings.
- Show device tier, QNN runtime files, load probe, SDK header compile state,
  and latest smoke stage.

## QNN Native Runner Milestones

Current native bridge can inspect runtime files, bundle files, QNN interface
symbols, SDK header availability, and smoke tensor metadata. The next milestones
are:

1. Add typed QNN wrapper under `MCA_WITH_QNN_SDK_HEADERS`.
2. Load system and HTP backend provider interfaces.
3. Create backend/device/context.
4. Load context binary from the bundle.
5. Resolve graph by manifest graph name.
6. Allocate input and output tensor buffers from `QnnSmokeSpec.bufferPlan`.
7. Bind tensors by name.
8. Execute one smoke graph.
9. Record execution stage and elapsed time.
10. Mark `graphRunnerReady`, `graphExecute`, `smokePassed`, and `npuActive`
    only when the real execute path succeeds.

### Device runtime collection

Use the device-side collector before running any QNN smoke test:

```powershell
$env:ANDROID_HOME='D:\model\android-sdk'
$env:ANDROID_SDK_ROOT='D:\model\android-sdk'
$env:PATH="$env:ANDROID_HOME\platform-tools;$env:PATH"
powershell -ExecutionPolicy Bypass -File tools\qnn\collect-device-qnn.ps1 -Json
```

To copy readable QNN libraries into a local evidence directory:

```powershell
powershell -ExecutionPolicy Bypass -File tools\qnn\collect-device-qnn.ps1 `
  -Pull `
  -OutDir .tmp\qnn-device-runtime
```

The collector records SoC, Android version, ABI, readable QNN runtime files,
package-native QNN files, and whether the complete runtime triplet
`libQnnSystem.so`, `libQnnHtp.so`, and `libQnnHtpVxxSkel.so` exists. A pass here
only proves the "runtime found" stage. It does not prove QNN graph execution.

Native smoke diagnostics must include:

- `executionStage`
- `runtimeLoaded`
- `qnnInterfaceFound`
- `bundleManifestFound`
- `bundleGraphArtifactFound`
- `bundleContextBinaryFound`
- `bundleContextBinaryNonEmpty`
- `smokeMetadataComplete`
- `tensorBufferPlanReady`
- smoke validation readiness and blocking reasons, including unsafe context
  paths, missing graph names, duplicate tensor names, unsupported dtypes, and
  oversized native smoke buffers. The manifest's `contextBinary` must resolve
  to a real, non-empty file inside the bundle root before native smoke can
  start.
- `sdkHeadersCompiled`
- `backendCreated`
- `contextLoaded`
- `graphResolved`
- `tensorsBound`
- `graphExecuted`
- per-input tensor diagnostics: name, role, data type, shape, element count,
  bytes per element, byte size, supported, bindable, and failure reason.
- per-output tensor diagnostics with the same fields.

## Download and Distribution

MCA should prefer ModelScope when a complete legal bundle is available there.
Otherwise the app can support user-provided bundles or official vendor download
links, but must not ship proprietary weights or Qualcomm SDK files inside the
open-source repository.

Download flow:

1. Download all bundle components.
2. Verify SHA-256.
3. Validate manifest schema.
4. Validate device tier.
5. Probe runtime.
6. Run 1-step smoke.
7. Register only if smoke passes.

## Release Language

Allowed:

> MCA supports local image understanding and local image generation paths on
> Android. Snapdragon NPU acceleration is available for certified bundles after
> runtime and smoke validation.

Not allowed until device logs prove it:

> Local image generation is fast and stable on NPU.

> All GGUF image models can use Snapdragon NPU.

> NPU is active because the phone has a Snapdragon flagship chip.

## Flagship Definition of Done

The NPU flagship work is complete only when evidence exists for both tracks:

Local vision:

- exact APK and git commit recorded,
- Snapdragon device tier recorded,
- QNN/LiteRT runtime load verified,
- certified vision bundle hash recorded,
- image smoke output saved,
- logs prove QNN/HTP execution,
- repeated image turns and text-after-image turns pass.

Local image generation:

- exact APK and git commit recorded,
- Snapdragon device tier recorded,
- certified SD1.5 QNN bundle hash recorded,
- 384 and 512 smoke/generation timings recorded where device tier allows,
- progress and cancel verified,
- corrupted bundle fallback verified,
- logs prove QNN/HTP execution,
- generated image saved into MCA image library.

Until those gates pass, the correct status is still "experimental route ready
for validation", not "NPU image generation is complete".

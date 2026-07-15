# Model and API Compatibility

MCA is a bring-your-own-model and bring-your-own-endpoint Android app. It does
not include model weights, provider accounts, or API keys.

## Capability Matrix

| Area | Supported path | Status | Notes |
|---|---|---|---|
| Local chat | MNN CPU engine bundles | Preferred product route | Recommended local chat entries prefer MNN packages when a verified same-model package exists. MCA can download, import, register, verify, and load complete MNN bundles through the MNN CPU runner when the APK is built with the official MNN Android LLM library; development builds without that library return an explicit native-runner error. A complete chat bundle must include `config.json`, `llm_config.json`, `llm.mnn`, `llm.mnn.weight`, and `tokenizer.txt` or `tokenizer.mtok`. |
| Local chat | GenieX QAIRT / Snapdragon NPU engine bundles | Experimental verified on one flagship | MCA can read Qualcomm `release_assets.json`, prefer China-friendly Hugging Face mirrors for HF-hosted files, select a matching `geniex_qairt` w4a16 bundle, unpack it, and register it as a `GenieX QAIRT NPU` local engine. On 骁龙 8 Elite, `qualcomm/Qwen3-VL-4B-Instruct` passed cold, repeated direct image, Local API image, cancellation recovery and clean V79 destroy gates. The text-only `qualcomm/Qwen3-4B-Instruct-2507` passed native `system,user` roles, ten exact `42` turns, Local API, second load and clean destroy. Both remain experimental because peak DMA-BUF pressure is device-sensitive; RAM is advisory and does not block download. `qualcomm/Qwen3-8B` remains an unverified high-memory research target. |
| Local chat | GGUF through `llama.cpp` CPU backend | Compatibility path | Best for user-supplied models, uncommon architectures, local vision projector workflows, and the broader GGUF ecosystem. Performance depends on SoC, RAM, storage, thermal state, and quantization. |
| Cloud chat | OpenAI-compatible chat completions | Supported | Use for OpenAI-style providers, self-hosted gateways, and compatible routing services. User provides Base URL, model name, and API key. |
| Cloud chat | Anthropic Messages | Supported | Base URL must point to the provider root that exposes Anthropic Messages-style endpoints. MCA does not auto-discover vendor-specific routes. |
| Cloud vision chat | OpenAI-compatible or Anthropic-compatible image input | Supported alpha | Image attachments are sent as inline image data only when the selected cloud chat engine is marked as supporting image input. Capability is provider/model dependent. |
| Local vision chat | MNN multimodal engine bundles | Default-open alpha | Image input is available on compatible ARM64 devices whenever the complete bundle loads successfully and native reports `visionReady=true`. MCA does not require a per-device certification allowlist. One representative device must pass both the production UI and authenticated Local API; that pass opens all compatible devices, with later device-specific failures handled as explicit exceptions. |
| Local vision chat | Multimodal GGUF plus matching `mmproj` / projector | Supported alpha | Pure text GGUF files cannot see images. The native runner must report `visionReady=true` after the main model and matching projector are loaded. Remote `mmproj` files can be downloaded from the model file list and bound to the currently loaded local main model. |
| Local vision chat | GenieX QAIRT / Snapdragon NPU VLM bundle | Verified experimental on one flagship | `Qwen3-VL-4B-Instruct` w4a16 completed repeated same-process direct and Local API image turns on a 12GB `SM8750P`. The runner reported `computeUnit=npu`, `backendDevices=QAIRT HTP / VLM`, `visionReady=true`, clean destroy, and no fallback. Other SoCs, firmware versions, memory states, and bundles must still pass the same load, repeated-turn, destroy, and image-answer checks before MCA exposes them as verified. |
| Cloud images | OpenAI Images | Supported | For providers exposing an OpenAI Images-style generation endpoint. |
| Cloud images | DashScope Image | Supported | For Qwen-Image-style DashScope image generation. Use the provider's documented image endpoint and model name. |
| Cloud images | Custom image path | Experimental | Useful when a provider is mostly OpenAI-compatible but uses a non-standard image path. |
| Local images | `stable-diffusion.cpp` bundle | Experimental | Requires a complete bundle: diffusion model plus required VAE/AE and text encoder/LLM components. |
| Local images | MNN Diffusion bundle | Experimental guarded | MCA can download/register complete MNN Diffusion bundles with text encoder, UNet, VAE decoder, tokenizer, and weights, but real-device evidence currently keeps them behind a required 1-step runtime verification gate. Failed bundles remain visible for diagnostics but are not selectable for generation. |
| Local images | QNN / QAIRT / Snapdragon NPU bundle | Verified experimental on supported Snapdragon devices | MCA can represent QNN HTP image bundles, validate runtime files and smoke metadata, encode SD1.5 prompts through the packaged MNN CLIP path, and run QNN UNet plus QNN VAE decoder through `runImageSemanticGenerate`. On `SM8750P`, the CyberRealistic SD1.5 QNN bundle completed three repeated 512x512, 20-step PNDM runs in 12.775-12.937s with `npuActive=true`, `qnnGraphExecution=true`, `fallback=false`, and UNet averaging 184-185ms/step. Each device and bundle must still pass runtime, bundle, graph smoke, repeated generation, and semantic-quality checks before MCA exposes it as usable. |
| Local API | OpenAI-compatible local server | Supported alpha | Intended for trusted same-device, browser, desktop, and same-LAN workflows. Supports `/v1/models`, `/v1/chat/completions`, JSON replies, SSE streaming, GGUF+mmproj image input, and MNN image input whenever native reports `visionReady=true`. |
| Web search | Direct public URL reading | Supported alpha | Enabled from Settings. Blocks localhost, private LAN, link-local, and reserved addresses by default. |
| Web search | SearxNG / Brave / Tavily / Jina | Supported alpha | User provides endpoint and any required key. Public SearxNG instances can be unstable; self-hosted or provider keys are recommended. |
| Web search | Custom JSON search gateway | Supported alpha | Supports URL templates such as `/search?q={query}&limit={max_results}` and common result fields including nested `source.url/title`. |
| Assistants | MCA role cards | Supported alpha | Local JSON import/export with `mca.assistant.card` schema metadata, system prompt, model preference, and generation settings. |
| Assistants | Common nested character cards | Compatible import | Nested `data.name`, `description`, `personality`, `scenario`, `first_mes`, and `mes_example` are converted into an MCA system prompt. |

## Cloud Engine Configuration

Cloud engines are saved separately for chat and image generation.

### Cloud inference engines

| Field | Meaning |
|---|---|
| Protocol | `OpenAI-compatible` or `Anthropic Messages`. |
| Base URL | Provider or gateway root URL. |
| API key | User-owned provider key, stored locally with Android Keystore-backed encryption. |
| Model | Exact provider model identifier. |
| Display name | Optional local label shown in MCA. |
| Supports image input | Enable only for cloud chat models that truly accept image attachments. When disabled, MCA blocks image turns before sending the request. |

### Image generation engines

| Field | Meaning |
|---|---|
| Protocol | `OpenAI Images`, `DashScope Image`, or custom path. |
| Base URL | Provider or gateway root URL. |
| API key | User-owned provider key. |
| Model | Exact image model identifier. |
| Image path | Optional provider-specific image generation path. |
| Size | Provider-supported image size or ratio, for example `1024x1024` or `1:1`. |

## Local API Configuration

MCA can expose the loaded local chat model through an OpenAI-compatible API.
This is designed for trusted local integrations and third-party clients that
support custom OpenAI-compatible endpoints.

| Field | Recommended value |
|---|---|
| Protocol | `OpenAI-compatible` or custom OpenAI-compatible endpoint. |
| Same-device Base URL | `http://127.0.0.1:11435/v1`. |
| Same-LAN Base URL | `http://<phone-lan-ip>:11435/v1`; requires enabling open port in MCA. |
| API key | The key generated in MCA Settings -> Local API. |
| Model | Select from `/v1/models`; if manual entry is required, use the returned `id`. |

Supported endpoints:

- `GET /health`
- `GET /v1/models`
- `POST /v1/chat/completions`
- `GET /` for the built-in web chat page with text and image attachment input

`POST /v1/chat/completions` accepts common OpenAI Chat Completions fields,
including `messages`, `model`, `max_tokens`, `temperature`, and `stream`.
`stream=true` returns Server-Sent Events with `data: {...}` chunks and a final
`data: [DONE]`. The compatibility layer also tolerates common connection-test
requests such as multiple `system` messages or probes without a `user` turn.

Vision input:

- OpenAI-style `image_url` message parts are accepted in Chat Completions
  `messages`. Responses-style `input` arrays with `input_image` parts are also
  normalized into the same local vision path.
- The built-in web chat page can attach browser-selected images and sends them
  as OpenAI-compatible `image_url` data URLs.
- Image URLs may be inline `data:image/...;base64,...` values from trusted
  clients, local absolute paths, `file:` URLs, and reachable `http(s)` image
  URLs.
- `content://` image URIs from other Android apps are not portable through the
  local HTTP API. Use inline base64, a readable file path, or an `http(s)` URL
  instead.
- `/v1/models` includes MCA extension fields such as `vision_ready` and
  `vision_projector` so clients can show whether the loaded local model can
  currently process images.
- In-app MNN multimodal vision is supported when the active MNN bundle reports
  `visionReady=true`. After an image turn, MCA automatically refreshes the MNN
  session before the next turn so repeated in-app image requests do not require
  manual model reload.
- Local vision requires either a loaded MNN multimodal bundle with a readable
  `visual.mnn`, or a loaded multimodal GGUF model with a matching `mmproj` /
  projector. If native reports `visionReady=false`, image requests fail with a
  clear local-vision-not-ready message.
- MNN Local HTTP API image requests use the same default-open rule as the app:
  compatible ARM64 devices are admitted whenever the active bundle loads and
  native reports `visionReady=true`. Per-device `visionValidated` metadata is
  retained only for manifest/API compatibility and does not block image input.
  One representative device must pass both the production UI and authenticated
  Local API. That pass opens all compatible ARM64 devices; a later
  device-specific failure becomes an explicit compatibility exception.
- In Model Hub recommendations, local vision bundles such as MiniCPM-V 4.6 Q4
  can download the main GGUF and matching projector together, then register the
  local model with the projector already bound.
- For manual setup, load the multimodal main GGUF first. Then either tap `绑定`
  on the local model card to choose a local `mmproj` file, or download a remote
  `mmproj` / projector from the same model repository; MCA binds it to the
  current local main model and reloads when appropriate.
- Cloud vision is routed through the selected cloud chat engine only after the
  engine is marked as supporting image input, and still depends on the
  configured provider/model accepting images for that protocol.

Security notes:

- Same-LAN access should only be enabled on trusted Wi-Fi or hotspot networks.
- Do not publish the API key, phone LAN IP, or private prompts in issues.
- If same-device `127.0.0.1` access fails in an Android client, try the
  same-LAN address with open port enabled, then disable open port after use.

## Web Search Configuration

Web search is a user-configured retrieval layer. The local or cloud model does
not browse by itself; MCA fetches sources first, then injects a shortened source
context into the current turn and shows source cards under the answer.

| Provider | Endpoint shape | Auth | Notes |
|---|---|---|---|
| SearxNG | Instance root, MCA calls `/search?format=json` | Usually none | Prefer self-hosted or trusted instances for stable JSON responses. |
| Brave Search | `https://api.search.brave.com` or `/res/v1/web/search` | `X-Subscription-Token` | Official root is normalized to Web Search. `/res/v1/llm/context` is also accepted for grounding snippets. |
| Tavily Search | `https://api.tavily.com` or `/search` | `Authorization: Bearer <key>` | Official root is normalized to `/search`; MCA uses POST JSON. |
| Jina Search | `https://s.jina.ai` | `Authorization: Bearer <key>` | Jina Reader may be used to improve public page body extraction when configured. |
| Custom JSON | Any trusted HTTPS search gateway | Optional Bearer key | May return top-level arrays or `results/items/data/hits/organic_results`, including nested `source.url/title`. |

Recent search diagnostics are stored locally and can be cleared. They include
queries, trigger reasons, provider labels, source URLs/snippets, latency,
quality score, and closed-loop evidence, but not API keys.

## Local Chat Recommendations

MNN recommendations are treated as complete engine bundles, not single-file
model downloads. If `llm_config.json` or any core MNN component is missing, MCA
marks the bundle incomplete before native loading so the app does not surface a
zero-token generation failure.

HF-hosted recommendations prefer `https://hf-mirror.com` for file downloads
and fall back to `https://huggingface.co` when the mirror or asset route is not
usable. Qualcomm QAIRT assets may still point to Qualcomm-hosted S3 release
files; MCA keeps those URLs unchanged because they are not Hugging Face files.
Model pages for Hugging Face-hosted recommendations also open on `hf-mirror.com`
by default.

QNN chat recommendations are gated by the model's public `release_assets.json`
and by MCA real-device smoke evidence, not by the model family name alone. The
2026-07-10 asset audit found `geniex_qairt` w4a16 packages for
`qualcomm/Qwen3-4B`, `qualcomm/Qwen3-4B-Instruct-2507`, `qualcomm/Qwen3-8B`,
and `qualcomm/Qwen3-VL-4B-Instruct`. The same audit found only
`geniex_llamacpp` universal assets for `qualcomm/Qwen3.5-0.8B`,
`qualcomm/Qwen3.5-2B`, `qualcomm/Qwen3-0.6B`, `qualcomm/Qwen3-1.7B`,
`qualcomm/Qwen3-VL-2B-Instruct`, `qualcomm/Gemma-4-E2B-it`, and
`qualcomm/Gemma-4-E4B-it`, so MCA must not present those entries as QNN/NPU
chat engines until Qualcomm publishes matching QAIRT assets. The later
2026-07-11 real-device regression supersedes the first failed VLM attempt:
`Qwen3-VL-4B-Instruct` completed two same-process direct image turns and two
same-process Local API image turns on `SM8750P`, with `visionReady=true`, clean
destroy, 513-635ms TTFT, and 20.61-22.53 tok/s decode. It is therefore the
current verified unified QAIRT chat-and-vision candidate for this device. The
text-only `Qwen3-4B-Instruct-2507` later passed a formal isolated product-worker
regression with native `system,user` roles, ten exact text turns, Local API,
second load and clean destroy. An earlier create did reproduce DMA-BUF
exhaustion (`proc_size` about 6.39GB, `total_size` about 6.21GB), so the model
remains experimental and memory-sensitive rather than being blocked solely by
RAM. `Qwen3-8B` remains a 24GB+ research target. A follow-up
HF mirror search for
Qualcomm-hosted `Qwen3.6`, `Gemma 4`, `Mistral`, and `Ministral` QAIRT assets
did not reveal a stronger public `geniex_qairt` chat bundle, so MCA should keep
those model families on MNN/GGUF routes unless a future `release_assets.json`
proves a real QAIRT package and a real device produces tokens.

| Device tier | Suggested model class | Notes |
|---|---|---|
| Entry / older phones | 0.5B to 2B MNN when available; otherwise low/mid-quant GGUF | Prioritize responsiveness and thermal stability. |
| Mainstream flagship | 3B to 8B MNN when available; otherwise Q4-class GGUF | Best balance for daily local chat. |
| 12GB+ Snapdragon 8 Elite / 8 Elite Gen 5 | `Qwen3-VL-4B-Instruct` GenieX QAIRT w4a16 | Current verified unified QNN/NPU chat-and-vision candidate on the tested `SM8750P`. Repeated direct and API vision turns passed, but the feature remains experimental until more firmware and device families reproduce the result. |
| Verified with memory sensitivity | `Qwen3-4B-Instruct-2507` GenieX QAIRT w4a16 | The isolated product worker retained native `system,user` roles and passed ten exact text turns, Local API, second load and clean destroy. Keep it experimental because create still has high DMA-BUF pressure; do not block download solely by RAM. |
| 24GB+ high-memory Snapdragon flagship | `Qwen3-8B` GenieX QAIRT w4a16 | High-quality QNN/NPU experiment. The 12GB `SM8750P` run reached `qairt_llm_create_start` but did not produce token output, so it should not be promoted as a stable engine yet. |
| High-memory flagship | 9B+ or MoE active-parameter MNN packages; GGUF fallback for unsupported models | Treat as advanced use; validate memory headroom before long sessions. |

## Local Image Recommendations

Local image generation is experimental. Product wording should avoid promising
stable phone-side image generation until each model bundle has device-specific
evidence.

MCA treats local image engines as complete bundles, not single model files. A
bundle recommendation declares its runtime, accelerator, minimum device tier,
and smoke-test shape. Downloading a bundle registers it as one local image
engine, but the image page can select it only after the structural checks and
required smoke test pass.

| Product label | Intended use | Default posture |
|---|---|---|
| Fast local image | Small SD-Turbo-style bundles and short-step smoke tests | Recommended for first local image validation. |
| Clear local image | Slightly larger local bundles or higher resolution | Use after fast path is proven on the device. |
| Quality experiment | FLUX/Z-Image-style compact bundles | Advanced users only; expect long runs and device variance. |
| Frontier archive | Qwen-Image/LongCat/large experimental bundles | Keep visible as research targets, not daily defaults. |

### Snapdragon NPU Image and Vision Gates

MCA separates four states that should not be collapsed in UI, documentation, or
release notes:

| State | Meaning | User-facing claim |
|---|---|---|
| Device candidate | The SoC is in a supported Snapdragon tier, such as `SM8550`, `SM8750`, or `SM8850`. | "NPU route is available for validation." |
| Runtime files found | QNN/QAIRT runtime libraries and HTP skel are packaged and detected. | "QNN runtime files were found." |
| Runtime load not requested | MCA has not attempted the native load probe yet. | "QNN runtime still needs validation." |
| Runtime load failed | QNN files were found, but the native load probe failed. | "QNN runtime is present but cannot enter smoke validation yet." |
| Runtime loadable | QNN files were found and the native load probe succeeded. | "QNN runtime can enter smoke validation." |
| Bundle complete | The model bundle has all required diffusion, VAE/AE, text encoder, tokenizer, and metadata files. | "Bundle is complete; smoke test can run." |
| Smoke metadata ready | The bundle declares the QNN context binary plus input and output tensor names, data types, and shapes. | "Graph smoke metadata is ready." |
| Typed QNN bindings compiled | The local build was given `MCA_QNN_SDK_ROOT`, `QNN_SDK_ROOT`, or `QAIRT_SDK_ROOT`, and QNN headers compiled into `libmca_qnn_native.so`. | "QNN SDK headers are compiled for graph work." |
| Stage diagnostics available | Native smoke reports `executionStage` plus runtime, bundle, metadata, tensor, SDK, backend, context, graph, tensor-binding, and graph-execute booleans. | "The failure stage is known." |
| Smoke passed | A real 1-step device run completed without crash and produced output. | "This engine can be selected." |
| Semantic generation passed | Prompt embedding, QNN UNet sampling, QNN VAE decode, and PNG output all completed for the requested bundle. | "This QNN image engine can generate images on this device." |
| NPU active | Runtime logs prove QNN/HTP graph execution for the actual request. | "This run used Snapdragon NPU." |

For Snapdragon 8 Gen 2 class devices, SD1.5-style QNN image bundles are the
first target. For Snapdragon 8 Gen 3, Snapdragon 8 Elite, and newer devices,
SDXL-class QNN bundles may be shown as experimental only after enough memory is
available. Other devices keep using the CPU/MNN/GGUF compatibility routes.

Current real-device evidence: on `SM8750P` / Android 16, the SD1.5
CyberRealistic QNN bundle completed 512x512 semantic generation with MNN CLIP
conditioning, QNN UNet, and QNN VAE decode. The 4-step smoke run reported
`npuActive=true`, finite prompt embeddings, and about 185ms per UNet step; the
20-step run completed in about 14.5s and produced a recognizable image.

## Reporting Compatibility Results

When reporting a compatibility result, include:

- App version and commit.
- Device model, SoC, Android version, and RAM.
- Model repo, exact file names, quantization, and source.
- For local images: diffusion model, VAE/AE, text encoder/LLM, image size,
  step count, thread count, elapsed time, and whether cancel/progress worked.
- For cloud engines: protocol, provider, model name, endpoint shape, HTTP status,
  and sanitized error body.

Do not share API keys, private prompts, account IDs, or proprietary model files
in issues.

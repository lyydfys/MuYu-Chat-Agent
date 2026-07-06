# Model and API Compatibility

MCA is a bring-your-own-model and bring-your-own-endpoint Android app. It does
not include model weights, provider accounts, or API keys.

## Capability Matrix

| Area | Supported path | Status | Notes |
|---|---|---|---|
| Local chat | GGUF through `llama.cpp` CPU backend | Primary local path | Best for small and mid-sized quantized instruct models. Performance depends on SoC, RAM, storage, thermal state, and quantization. |
| Cloud chat | OpenAI-compatible chat completions | Supported | Use for OpenAI-style providers, self-hosted gateways, and compatible routing services. User provides Base URL, model name, and API key. |
| Cloud chat | Anthropic Messages | Supported | Base URL must point to the provider root that exposes Anthropic Messages-style endpoints. MCA does not auto-discover vendor-specific routes. |
| Cloud images | OpenAI Images | Supported | For providers exposing an OpenAI Images-style generation endpoint. |
| Cloud images | DashScope Image | Supported | For Qwen-Image-style DashScope image generation. Use the provider's documented image endpoint and model name. |
| Cloud images | Custom image path | Experimental | Useful when a provider is mostly OpenAI-compatible but uses a non-standard image path. |
| Local images | `stable-diffusion.cpp` bundle | Experimental | Requires a complete bundle: diffusion model plus required VAE/AE and text encoder/LLM components. |
| Local API | OpenAI-compatible local server | Supported alpha | Intended for trusted same-device, browser, desktop, and same-LAN workflows. Supports `/v1/models`, `/v1/chat/completions`, JSON replies, and SSE streaming. |
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
- `GET /` for the built-in web chat page

`POST /v1/chat/completions` accepts common OpenAI Chat Completions fields,
including `messages`, `model`, `max_tokens`, `temperature`, and `stream`.
`stream=true` returns Server-Sent Events with `data: {...}` chunks and a final
`data: [DONE]`. The compatibility layer also tolerates common connection-test
requests such as multiple `system` messages or probes without a `user` turn.

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

| Device tier | Suggested model class | Notes |
|---|---|---|
| Entry / older phones | 0.5B to 2B GGUF, low or mid quantization | Prioritize responsiveness and thermal stability. |
| Mainstream flagship | 3B to 8B GGUF, Q4-class quantization | Best balance for daily local chat. |
| High-memory flagship | 9B+ or MoE active-parameter models with low quantization | Treat as advanced use; validate memory headroom before long sessions. |

## Local Image Recommendations

Local image generation is experimental. Product wording should avoid promising
stable phone-side image generation until each model bundle has device-specific
evidence.

| Product label | Intended use | Default posture |
|---|---|---|
| Fast local image | Small SD-Turbo-style bundles and short-step smoke tests | Recommended for first local image validation. |
| Clear local image | Slightly larger local bundles or higher resolution | Use after fast path is proven on the device. |
| Quality experiment | FLUX/Z-Image-style compact bundles | Advanced users only; expect long runs and device variance. |
| Frontier archive | Qwen-Image/LongCat/large experimental bundles | Keep visible as research targets, not daily defaults. |

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

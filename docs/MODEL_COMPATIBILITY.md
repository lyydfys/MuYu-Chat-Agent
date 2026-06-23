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
| Local API | Loopback OpenAI-style API | Experimental | Intended for trusted local-network workflows and development integration. |

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

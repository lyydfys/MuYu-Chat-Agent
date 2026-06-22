# MCA Real-Device Regression Checklist

Use this checklist before publishing an APK or claiming device support. Record
the exact APK, device, model bundle, and result so performance and compatibility
claims stay honest.

## Test Environment

| Item | Value |
|---|---|
| Test date |  |
| APK version / commit |  |
| Device brand and model |  |
| SoC | Snapdragon / Dimensity / other |
| Android version |  |
| RAM / free RAM |  |
| Chat model |  |
| Image model bundle |  |
| Network | Offline / Wi-Fi / mobile hotspot |

## Core App Flow

| # | Area | Action | Expected result | Result |
|---|---|---|---|---|
| 1 | Cold start | Install and open the APK | App opens to chat without crash |  |
| 2 | Workspace | Open history/workspace navigation | Image entry and recent chats render correctly |  |
| 3 | Image page | Open Images from workspace | Template row, library grid, and prompt bar render |  |
| 4 | Model management | Open model management from the top action | Model management, tuning, local API, and settings entries render |  |
| 5 | Local import | Import a `.gguf` file | Model appears in the local engine list |  |
| 6 | Model validation | Validate an imported model | Success or actionable failure reason appears |  |
| 7 | Local chat load | Load a local chat model | Model name and backend status update |  |
| 8 | Local chat | Send a Chinese prompt | Streaming output is readable and can be stopped |  |
| 9 | Cloud chat | Configure a cloud chat engine | Engine can be saved, selected, tested, edited, and deleted |  |
| 10 | Cloud images | Configure a cloud image engine | Engine can be saved, selected, tested, edited, and deleted |  |
| 11 | Local images | Run a small image smoke test | Progress updates, cancel works, no UI freeze |  |
| 12 | Image library | Open a generated image | Image preview opens; download action is visible |  |
| 13 | Files | Add a local file from composer | File can be reused without deleting source assets |  |
| 14 | Persistence | Kill and reopen the app | Recent chats and configured engines remain available |  |

## Local API Flow

When testing the local API, use a device and computer on the same trusted
network.

| # | Area | Action | Expected result | Result |
|---|---|---|---|---|
| 15 | API enable | Enable local API in settings | Local endpoint and key are shown |  |
| 16 | Health | `curl http://PHONE_IP:11435/health` | Returns a healthy response |  |
| 17 | Models | Request `/v1/models` | Returns OpenAI-style model list |  |
| 18 | Chat | Request `/v1/chat/completions` | Returns completion JSON or SSE stream |  |
| 19 | Stop | Call stop endpoint during generation | Active generation stops safely |  |

## Pass Criteria

- No crash during startup, navigation, cloud configuration, or model selection.
- Local image generation can fail only with an actionable model-bundle or device
  explanation.
- Logs, screenshots, and exported diagnostics contain no API keys or private
  user content before they are shared.
- Device-specific performance claims include model name, quantization, image
  size, steps, thread count, elapsed time, and thermal state.

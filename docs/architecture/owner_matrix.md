# MCA Enterprise Architecture Owner Matrix

## 1. Unified Architecture Pipeline

Every command or user intent flows through an invariant single-owner pipeline. No command may possess a second execution path.

```text
Incoming Interface (UI / Local API / Worker)
  │  Intent DTO
  ▼
Feature Facade / UseCase
  │  Domain Command & RequestIdentity
  ▼
Singular Owner (Exclusive lifecycle, state, and coordination owner)
  │  Domain Port
  ▼
Platform Adapter (Android / Storage / Network / JNI Gateway)
  │  C/C++ ABI & Gateway Invocation
  ▼
JNI Boundary (Thin shim: arguments, bounds check, exception isolation)
  │
  ▼
Native Runtime Engine (mca_contract, mca_native, mca_mnn_native, mca_qnn_native, mca_sd_native)
```

## 2. Exhaustive Command Owner Matrix

| Command / Intent | Ingress Source | Facade / Entry | Singular Owner | Port Interface | Adapter Implementation | Native Target |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Chat Inference (Stream / Single)** | UI, Local API `/v1/chat/completions` | `ChatFacade` | `ChatExecutionCoordinator` / `LocalChatRunner` | `ChatExecutionPort` | `CorePortAdapters.ChatExecutionPortAdapter` | `mca_native`, `mca_mnn_native` |
| **Chat Inference Cancel** | UI Cancel button, Local API disconnect | `ChatFacade` | `ChatExecutionCoordinator` | `ChatExecutionPort` | `CorePortAdapters.ChatExecutionPortAdapter` | `mca_native`, `mca_mnn_native` |
| **Image Generation (Diffusion)** | UI Drawing, Local API `/v1/images/generations` | `ImageGenerationFacade` | `ChatImageWorkflowCoordinator` | `ImageGenerationPort` | `CorePortAdapters.ImageGenerationPortAdapter` | `mca_sd_native`, `mca_mnn_native`, `mca_qnn_native` |
| **Image Generation Cancel** | UI Cancel, Local API abort | `ImageGenerationFacade` | `ChatImageWorkflowCoordinator` | `ImageGenerationPort` | `CorePortAdapters.ImageGenerationPortAdapter` | `mca_sd_native`, `mca_mnn_native`, `mca_qnn_native` |
| **Model Lifecycle (Load/Unload)** | UI Model Picker, Local API load | `ModelFacade` | `ChatModelCoordinator` | `ModelLifecyclePort` | `CorePortAdapters.ModelLifecyclePortAdapter` | Native runtimes |
| **Model Download / Import** | UI Hub, Content Intent | `ModelDownloadFacade` | `ModelDownloadCoordinator` | `DownloadPort`, `ModelStorePort` | `DownloadServiceAdapter`, `RoomModelStoreAdapter` | N/A (Storage/Net) |
| **Tuning & Benchmark** | UI Settings, CLI Smoke | `TuningFacade` | `TuningExecutionCoordinator` | `TuningBenchmarkPort` | `BenchmarkPortAdapter` | `mca_native`, `mca_qnn_native` |
| **Local API Server Control** | UI Toggle, Foreground Service | `LocalApiFacade` | `LocalApiLifecycleOwner` | `LocalApiServerPort` | `KtorApiServerAdapter` | N/A (Network/HTTP) |
| **Session Persistence** | UI Chat, API Chat | `SessionFacade` | `ChatSessionManagementCoordinator` | `ChatSessionPersistencePort` | `RoomChatSessionStorageAdapter` | N/A (SQLite/Room) |

## 3. Strict Prohibitions

1. **No Direct UI-to-Native**: UI components and Composable screens must never reference native bridges, JNI classes, or engine runtimes directly.
2. **No Second Implementation**: Local API and Worker tasks must invoke the same domain Facade and Owner as the interactive UI.
3. **No ViewModel in Local API**: The Local API layer must never hold or invoke `MainViewModel` or UI coordinators.
4. **No Device Whitelist Gate**: Neither the Owner nor the Adapter layer may check device model/chipset to block capability admission.

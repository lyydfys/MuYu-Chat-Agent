# MCA 原生视频工作流 V1：执行方案

状态：工程方案已补齐，待实施；本次仅修改方案文档，不修改生产代码。  
目标读者：接手 MCA 的弱模型、实现 Agent、代码审查者。  
编写日期：2026-10-25。  
产品入口名称：视频。入口位置：左侧栏 DSH 下方。  
首个本地模型：Wan2.2 TI2V-5B。  
后续顺序：视频媒体存储/播放 → ComfyUI API 工作流导入 → 远程 ComfyUI 与更多节点。  
实验阶段：Wan2.2 14B、MiniMax H3 本地运行。

阅读顺序：[主方案](EXECUTION_PLAN.md) → [来源与实现差异审计](REFERENCE_AUDIT.md) → [弱模型任务包](TASK_PACK.md)。主方案定义产品和技术合同，任务包定义实施次序、写集与检查点。所有运行结果目前均为 NOT_RUN；这里的“方案完成”不代表视频能力已经实现。

---

## 0. 先给执行者的结论

MCA 要做的是 Android 原生的“视频工作流产品”，不是把完整 Python ComfyUI 塞进 APK，也不是先复制一个桌面节点编辑器。视频页面由 MCA 的 Compose、主题、导航和状态体系负责；节点图只负责表达可验证的输入、模型、采样、解码和输出关系；真正的 Wan 推理由受控的 VideoGenerationOwner、独立 worker 和 native bridge 负责。

完整 ComfyUI 的模型、插件和节点生态远强于自研，但它不能解决 MCA 的本地 Android 生命周期、内存、取消、Room、媒体发布和权限问题。正确顺序是：

1. 先建立 MCA 原生视频入口和工作流契约。
2. 以真实设备上的 Wan2.2 TI2V-5B 小片段可行性为硬门。
3. 通过真实 native 执行、编码、校验、持久化和播放形成一个闭环。
4. 再导入 ComfyUI API-format prompt graph，把能证明语义等价的子图映射到 MCA。
5. 最后接远程 ComfyUI；远程任务必须显式选择，不能在本地失败时偷偷切云端。
6. 14B 与 H3 仅在另一个实验阶段评估，不能影响 V1 的发布边界。

本方案中的“完成”必须同时具备源码调用链、真实执行、取消、资源释放、文件发布、Room 记录和播放证据。编译通过、静态 stub 通过或看到上游接口都不能单独算完成。

---

## 1. 范围、目标与不做的事

### 1.1 V1 目标

V1 完成后，用户应能：

- 从 DSH 下方的“视频”进入 MCA 视频工作区。
- 选择“文生视频”或“图生视频”模板。
- 输入提示词、负面提示词、种子、分辨率、帧数、帧率和步数；图生视频可选择本地图片。
- 看到一个 MCA 原生节点图或简化工作流视图，知道输入、模型、采样、解码和输出的关系。
- 提交一个本地 Wan2.2 TI2V-5B 任务，看到准备、条件编码、采样、解码、编码、发布阶段。
- 中途请求取消，得到“请求已发出、native 已停止、资源已释放”的可区分结果。
- 生成成功后，在本地媒体库中看到视频、时长、尺寸、帧率、模型和工作流摘要，并能播放、删除、分享或重新运行。
- 在应用重启或 worker 被杀后看到中断状态，而不是看到假装继续采样的旧进度。

### 1.2 V1 明确不做

- 不在 APK 中运行完整 Python ComfyUI、任意 Custom Node、Python 插件或任意第三方代码。
- 不承诺所有 ComfyUI UI-format JSON、所有版本、所有节点或任意模型文件名兼容。
- 不把当前 MNN 的 runVideo 占位函数包装成可用能力。
- 不把 stable-diffusion.cpp 返回整段 sd_image_t 数组描述为常量内存；峰值内存必须实测。
- 不将 720p/24fps、长视频、音频、4K、LoRA、控制视频、首尾帧等未经测量的能力写进首个稳定预设。
- 沿用现有模型导入与运行行为，不增加新的能力可用性限制。
- 不把 Wan2.2 14B 或 MiniMax H3 混入 TI2V-5B 的验收；它们需要独立资产、许可证、内存和后端实验报告。
- 不改写现有 image_assets 表来承载视频；图片链路保持原样。

### 1.3 与 nightmare-mobile 的关系

参考仓库固定到 commit `42163d86e91fc2717a9d3673417d970f2998000d`，已通过 Git remote 和 GitHub API 交叉核对。它提供了有价值的产品和 native 参考：Kotlin 节点图、QuickJS 插件层、C++ 后端、本地 HTTP、WanVideoNode、SSE、NMV1 帧到 MP4 的处理、取消和多种 Wan 变体。

它不是完整 ComfyUI 内嵌，也不是 MCA 可以直接复制的实现。其整体采用 CC BY-NC 4.0，native 二进制、QNN runtime、模型、蒸馏 LoRA 和 TAEHV 等依赖还要单独审核。MCA 可以深入对照它的接口、任务状态、进度、帧编码、取消和模型准备流程，但只能借鉴可独立描述的设计与行为；不得拷贝受限源码、二进制或品牌。要采用算法时应追溯 MIT upstream stable-diffusion.cpp/ggml 及每个补丁和模型的许可证、固定 revision 与 checksum。

README 中的移动性能数字属于作者特定设备、精度和配置，不能作为 MCA 的性能承诺。

尤其要区分：参考项目的首选 5B 是 **Turbo 蒸馏变体、专用四步 schedule、特定解码器和定制 native/加速补丁**；本方案 V1 是基础 Wan2.2 TI2V-5B。二者不能共用未经核对的 steps、guidance、VAE、时长和速度结论。`VideoGate.kt` 实际是按应用版本缓存 canary 探测结果和探测中断标记，不是任务互斥锁，也不适合作为 MCA 的可用性设计来源。具体差异见[来源审计](REFERENCE_AUDIT.md)。

### 1.4 nightmare-mobile 的实现对照表

以下引用全部固定到上述 commit，避免弱模型跟随变化的 master 猜代码。执行者先读这些文件，再读 MCA 对应入口，最后只在方案允许的语义范围内实现 MCA 版本。

| 参考实现 | 参考代码和可观察行为 | MCA 对照决策 |
|---|---|---|
| Native 后端进程 | [BackendProcess.kt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/java/com/abrah/nightmare/BackendProcess.kt) 启动本地 native 子进程；[Backend.kt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/java/com/abrah/nightmare/Backend.kt) 连接 127.0.0.1:8189。 | 重点借鉴进程隔离和 backend 生命周期。MCA 优先评估现有 Android Worker/AIDL 生命周期，避免为了模仿 HTTP 再启动无必要本地服务；只有确有协议复用、独立崩溃边界或流式需求，并通过安全与端口/进程测试后，才采用本地 loopback HTTP。 |
| Wan 节点请求 | [WanVideoNode.kt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/java/com/abrah/nightmare/npu/WanVideoNode.kt) 将节点参数送入 `/video`，接收服务端事件和生成结果。 | 借鉴“图节点参数 → 明确后端请求 → 阶段进度/错误 → 输出”的端到端链。MCA 的首版用 typed `VideoRequest` 和 owner 管理的 callback/Flow；不得把整段 JSON 或帧数组作为 JNI/Binder 的通用逃生通道。逐字段核对参数单位、默认值、范围、模型模式和失败语义。 |
| SSE 和任务状态 | 同一 `WanVideoNode.kt` 包含提交与 SSE 事件处理；`VideoGate.kt` 缓存版本级 canary 探测及中断标记。 | 借鉴事件关联、进度、错误和探测中断诊断；不移植其入口控制。资源互斥依据 MCA 现有生命周期租约与跨进程执行锁，见 §7.4；不能把 canary cache 当成执行 semaphore。SSE 适合远端协议，本地优先 typed AIDL/Flow。 |
| 帧到 MP4 | [VideoWriter.kt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/java/com/abrah/nightmare/npu/VideoWriter.kt) 负责参考项目视频帧和 Android 编码输出。 | 读清帧格式、像素布局、时间戳、结束/错误处理和内存策略，再以 Android MediaCodec/MediaMuxer 做 MCA 自有适配。不能只因参考项目能写 MP4 就省略 MediaExtractor 验证、fsync、原子发布或崩溃恢复。 |
| 图执行和扩展 | [DitEngine.kt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/java/com/abrah/nightmare/DitEngine.kt) 及其节点/插件实现体现图到后端的编排；项目使用 QuickJS 扩展能力。 | 第一版借鉴可检查的 typed graph 与编译/执行分离，但建立 MCA 固定版本、白名单的节点注册，不引入 QuickJS 或任意用户脚本。只有插件 ABI、权限、沙箱、兼容性和撤销能力另有完整方案后，才讨论第三方节点。 |
| Native 构建 | [CMakeLists.txt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/cpp/CMakeLists.txt) 包含参考 fork、QuickJS、QNN runner 的构建接线。 | 只用来理解 native 组件关系。MCA 当前 sd-native 的 CMake/ABI/子模块差异要独立审计；不得复制该 fork、QNN binary 或把其设备 backend 误标成 MCA 已支持。 |
| 模型/场景范围 | [video flow guide](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/guide/pages/flows/video.md) 描述其视频流程；[LICENSE](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/LICENSE) 与 [NOTICE](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/NOTICE) 是来源审查入口。 | Wan2.2 5B 作为唯一首批本地模型；14B/H3、LoRA、控制和长视频只进入独立实验。每个模型组件和蒸馏资产都单独核验来源、授权、hash、转换方式和设备资源预算。 |

弱模型的读码任务按这个顺序做：先读 `BackendProcess.kt` + `Backend.kt`，再读 `VideoGate.kt`，然后读 `WanVideoNode.kt` 和 `VideoWriter.kt`，最后读 MCA 本方案列出的同职责源码。输出逐项列出“参考语义、MCA 选择、差异原因、待验证证据”，不得提交参考项目代码复制件。

---

## 2. 当前源码基线：已存在什么，缺什么

以下是写方案时已经核对过的事实。实现 Agent 进入每张任务卡时都要重新确认当前工作树，因为该仓库存在大量未提交改动和子模块内部差异。

### 2.1 导航和页面装配

- AppTab.kt 已有 CHAT、AGENT、MODELS、API、SETTINGS、AGENT_WORKSPACE、DSH_WORKSPACE；新 VIDEO 必须追加，不能重排旧 enum。
- MainChatScreenActions.kt 的 ChatShellActions 是聊天页到外层导航的窄回调；新增 onOpenVideoWorkspace，不把视频业务写进 MainViewModel 的聊天逻辑。
- ChatHistoryDrawerScreens.kt 当前顺序是图片、Agent 工作区、DSH；“视频”应紧跟 DSH，位置固定为 DSH 下方。
- MainActivity.kt 使用 CrossfadeTabPage 和 rememberSaveableStateHolder 承载 Agent、Agent workspace、DSH 等覆盖页。视频要沿同一模式挂载自己的 Route，返回 CHAT；不要让 Activity 变成视频业务主 VM。
- LoadModelCompletionPublish.kt 与 ChatModelCancelAndSwitchOperations.kt 当前有“模型切换完成后回到 CHAT”的默认语义，DSH 已有特例。视频加入后必须明确：视频页中的晚到聊天模型加载不能把用户踢回 CHAT；视频运行自己的模型绑定和状态，不直接复用聊天选中模型。
- 可参考 feature/dsh-workspace 的做法：feature 只依赖契约和 design system，由 app composition 注入客户端，不直连 native 或 app。

### 2.2 现有推理和 native

- core/sd-native/NativeStableDiffusionBridge.kt 目前暴露 generate、upscale、getSystemInfo、getProgress、getNativeConfig、measurePromptTokens、cancel、shutdown，没有 video 方法。
- third_party/stable-diffusion.cpp/include/stable-diffusion.h 已存在 sd_vid_gen_params_t 和 generate_video；src/model.h 已识别 WAN2、WAN2_2_I2V、WAN2_2_TI2V；stable-diffusion.cpp 有帧数对齐、条件处理、采样和解码。
- 当前 Android sd-native 构建关闭 SD_WEBP、SD_WEBM、VULKAN、GGML_VULKAN，并未证明移动硬件加速；现有 LocalImageGenerationSdcppOperations 只写 PNG。
- core/native/src/main/cpp/mca/mnn/mnn_diffusion_generate_entries.cpp 的 Diffusion::runVideo() 仍忽略参数并返回 false，不能作为视频后端。
- stable-diffusion.cpp 的 generate_video 返回 bool，经出参交付整段 sd_image_t[]；即使随后逐帧编码，也不能宣称整条 native 链是常量内存。
- 现有图片流程已有 NativeStableDiffusionBridge、LocalImageGenerationOwner、LocalImageWorkerService、ImageExecutionJournal、图片路径安全和原子发布经验；视频应借鉴边界，但不能把 image/* 校验和 96 MiB 图片上限直接复制过来。

### 2.3 现有运行时与 worker

- RuntimeSupervisor、BackendRuntimeOwner 和现有 ImageGenerationOwner 已处理请求身份、epoch、lease、取消和释放。
- 不同能力可以拥有各自 owner；共享大模型、native backend、GPU/NPU 或进程资源必须由同一个明确的仲裁者处理。不要为了“统一”把所有业务塞进 ImageGenerationOwner，也不要另开一套没有仲裁的全局 native。
- LocalImageWorkerService 已有单 active、foreground、binder death、journal recovery 和 cancel/cancelAndExit 语义。视频可有独立 video worker 进程，但必须共享重型资源仲裁；UI、Local API 和 Worker 只能调用同一个 VideoGenerationOwner。
- 页面离开不是取消。用户点击取消才产生 cancelRequested；native 返回、worker 退出、资源释放和最终状态要分别记账。
- 进程死亡后不能伪造继续采样；恢复协议只能把任务标为 INTERRUPTED/UNKNOWN，并提供基于 immutable snapshot 的显式 retry。

### 2.4 数据和媒体

- app/src/main/java/com/muyuchat/mca/ChatSessionStoreRoomDatabase.kt 当前 version=29，单例数据库名为 mca.db，已包含 chat、image、file、Agent 和 memory 表。
- PersistedRecords.kt 的 ImageAssetRecord 与 image_assets 只描述图片；ImageGenerationJobSpec、ImageExecutionJournal 和图片 worker 都是 image 语义。
- 当前生产代码未发现 Media3、ExoPlayer、MediaPlayer 或 VideoView；DSH WebView 的 HTML video 不是 MCA 视频播放链路。
- 文件系统已有 ImageAssetFiles.kt 的安全叶路径、根目录校验、磁盘余量、fsync、atomic rename 和 reconcile；视频需要独立的大小、媒体探测、帧和临时 spool 规则。
- 文件与 Room 没有天然原子事务，必须用 journal + staged file + fsync + rename + Room finalize + startup reconcile 两阶段协议。

### 2.5 基线风险

本次复核的父仓库 HEAD 为 `68b6eef1e67870b02bfef6afda2e625d36663da9`，工作区存在大量已跟踪和未跟踪变更；llama.cpp、stable-diffusion.cpp、tokenizers-cpp 子模块均有内部补丁。数字随其他工作变化，不能把旧统计当作实施基线。实现者不得 reset、clean、覆盖或把既有改动当作本任务产物。每张卡都记录“起点 revision、既有差异、本卡写集和验证范围”。

---

## 3. 目标架构和依赖方向

### 3.1 目标分层

    feature:video-workspace
        ↓ 只依赖 video-contract + designsystem
    VideoWorkflowPort / VideoWorkflowUiState
        ↓
    VideoGenerationOwner（视频领域独立 owner）
        ↓              ↓
    VideoRuntimeArbiter  RuntimeSupervisor/BackendRuntimeOwner
        ↓
    LocalVideoWorker（独立进程，可选） / RemoteComfyClient（后置）
        ↓
    core:sd-native video bridge / Android MediaCodec + MediaMuxer
        ↓
    stable-diffusion.cpp 或后续已验证 backend

app composition root 只创建和注入这些对象；Room、FileProvider、worker、JNI 适配器和 endpoint 凭据属于 app/engine 实现。feature 不依赖 app、core:native 或 core:sd-native。

默认建议新增 feature:video-workspace 和 core:video-contract；若模块审计证明增加模块会造成依赖环，纯契约可以进入已有 core:inference-contract 的 video 子包，但 feature 仍不得直接依赖 native。无论选择哪种落点，都必须先跑模块依赖审计并记录结果，不能先把 native import 写进 Compose。

### 3.2 所有权五问

在代码和测试中必须能回答：

| 资源/任务 | 创建者 | 使用者 | 取消者 | 释放者 | 进程死后 |
|---|---|---|---|---|---|
| 视频请求 | VideoGenerationOwner | UI/API/Worker 通过同一 port | Owner 根据 requestId | Owner + backend lease | journal 标 INTERRUPTED |
| Wan native context | VideoRuntimeArbiter/worker | video native adapter | Owner→bridge.cancel | worker finally + arbiter | 不恢复 context |
| MP4 临时文件 | VideoMediaRepository | encoder/publisher | owner cleanup | repository reconcile | 删除 orphan |
| Room run row | VideoRunRepository | Route、history、reconcile | repository transition | Room migration/cleanup | 保留状态用于恢复 |
| 播放器 | VideoPlayerHost | visible screen | screen lifecycle | player owner | release，不影响任务 |

独立的 VideoGenerationOwner 是允许的，因为视频和图片是不同业务；但它不能另开一套共享资源。现有 RuntimeSupervisor 统一请求身份与结算，资源互斥还涉及聊天生命周期租约和 `LocalImageExecutionGate` 的跨进程文件锁，见 §7.4。先做 V1-00 等价推广现有锁域，再让视频进入；不能让旧图片路径留在另一个互不相干的 gate 后面。

视频 owner 的装配落点固定为 `app/.../composition/VideoHostAssembly.kt`（新增），由 `MainRuntimeAssembly` 创建并使用 `McaProcessRuntimeRoot` 的进程 scope，纳入同一关闭流程。Route/页面 VM 只持窄 port 和只读投影，不持 MainViewModel、不管理 worker/context。

当前最后 Activity 关闭触发点还在 `McaApplication`，`McaProcessRuntimeOwner` 在清根时管理 holder，因此三处必须一起审。活跃前台视频任务持有有界的 application/task hold；最后 Activity 退出只进入 pending-close，不先置空 holder。重新打开页面重用同一根；最后任务结算/停止确认且 hold 归零后，通过 closing barrier 停止接单、关闭 worker/owner、取消 scope，最后清 holder。真正关闭期间新页面等待该 barrier 或明确复用尚未关闭的根，不能创建第二套 engine。进程被系统杀死按 journal 恢复，不依赖内存 ref-count 继续 native。

### 3.3 禁止的依赖和实现

- Composable 不构造 native、访问 Room、写文件或决定模型准入。
- Route 不把 Activity、View、Composable scope 或页面 ViewModel 缓存进应用单例。
- 不用 Any? + as? + 默认真实实例注入。
- 不用 GlobalScope、无主 fire-and-forget 或吞掉 CancellationException。
- 不把整个 MainUiState 交给视频 feature 写；使用窄 command/port。
- 不新增“万能 WorkflowExecutor”去复用 Agent workflow；Agent 语义和媒体 DAG 语义不同。
- 不在图上显示每个逻辑节点就每次重新创建一个 native context；支持的子图要 lower 到一次可验证的 fused pipeline，并保留阶段进度。

---

## 4. 领域契约：先定义数据，再写 UI 或 JNI

### 4.1 工作流文档

纯契约建议使用以下概念：

    VideoWorkflowDocument
      workflowId: stable id
      revisionId: immutable revision id
      schemaVersion: integer
      source: MCA_NATIVE | COMFY_API
      nodes: List<VideoNode>
      edges: List<VideoEdge>
      metadata: non-executable display metadata
      rawSourceRef: optional encrypted/file reference
      canonicalSha256: digest of canonical executable graph

    VideoNode
      nodeId: unique string
      typeId: versioned type id
      typeVersion: integer
      inputs: typed values or input references
      outputs: typed port declarations
      ui: optional position/label/collapsed state

    VideoEdge
      fromNodeId/fromPort
      toNodeId/toPort

图规则：

1. 节点 ID、端口名、typeId、版本和输入类型都必须有稳定定义。
2. 执行图必须是有向无环图；重复 ID、缺失引用、自引用、环、端口不存在、类型不匹配、数值越界都在编译前拒绝。
3. canonical JSON 对键排序、数组顺序、数字格式、默认值和 URI 做规范化；原始 JSON 和 canonical JSON 都保留，不能把用户输入直接当 digest。
4. UI 位置、折叠、颜色等不可执行字段不进入执行 digest；seed、模型 fingerprint、分辨率、帧数、采样器和输入 asset digest 必须进入 run snapshot。
5. revision 不可变；用户编辑产生新 revision。run 永远绑定一份 immutable revision snapshot，不读取编辑后的“当前图”。
6. 每个错误要指出 nodeId、port、path、错误码和修复建议；unsupported node 不能静默删除、换节点、改分辨率或降画质。

### 4.2 V1 MCA 原生节点类型

首个垂直切片只需要这些类型：

- InputText：prompt、negativePrompt。
- InputImage：图生视频的已验证本地 media reference。
- Wan22Ti2v5bModel：模型 manifest 引用，不接受裸文件路径。
- WanConditioning：将文字/图片和尺寸、帧数、seed 形成模型条件。
- WanSampler：steps、sampler、flow shift、guidance 等已映射参数。
- WanDecode：latent/frame decode。
- VideoEncodeMp4：Android MediaCodec/MediaMuxer 参数。
- SaveVideoAsset：发布到 MCA owned media root 并写 Room。

V1 图可以由模板自动生成；节点画布是可检查和编辑的第二层，不要求第一版用户手动连线才能生成。图上显示的 WanSampler 等节点是产品语义，编译器可以将它们 lower 为一个 stable-diffusion.cpp 视频调用。

### 4.3 Run 与状态

建议状态集合：

    DRAFT
    QUEUED
    PREPARING
    CONDITIONING
    SAMPLING
    DECODING
    ENCODING
    PUBLISHING
    COMPLETED
    CANCEL_REQUESTED
    CANCELLED
    FAILED
    INTERRUPTED
    UNKNOWN
    RECONCILING

VideoGenerationOwner 是唯一业务转换决策者；Repository 只执行带版本条件的事务，不创建另一套状态机。UI 只能观察只读 projection。每个 run 至少保存 requestId、attemptId/epoch、workflow revision、requested/resolved/nativeEffective 参数、model fingerprint、profile fingerprint、backend kind、created/updated/terminal time、error code/message、progress stage/step、worker PID/启动身份、temp/published paths、output media id 和 stateVersion。

### 4.3.1 合法转换与竞争规则

`DRAFT` 属于可编辑文档，不是已接单任务。提交前冻结 revision、输入摘要、随机 seed 和 resolved 参数，成功插入 run 后才进入 `QUEUED`。进度事件带 `runId + attemptId + epoch`，只允许更新当前 attempt。

| 当前状态 | 允许的下一状态 | 决策/持久化条件 |
|---|---|---|
| QUEUED | PREPARING、CANCELLED、FAILED、INTERRUPTED | 排队取消尚无 worker/资源，可直接结算；取消后禁止派工；主进程死亡且输入准备不全则 INTERRUPTED |
| PREPARING | CONDITIONING、CANCEL_REQUESTED、FAILED、INTERRUPTED | 持有有效租约；输入/model snapshot 已校验 |
| CONDITIONING | SAMPLING、CANCEL_REQUESTED、FAILED、INTERRUPTED | 没有精细回调时只报告已证实阶段，不伪造百分比 |
| SAMPLING | DECODING、CANCEL_REQUESTED、FAILED、INTERRUPTED | 进度不能倒退，也不能被旧回调推进 |
| DECODING | ENCODING、CANCEL_REQUESTED、FAILED、INTERRUPTED | 全量 native 帧合法且归属本 attempt |
| ENCODING | PUBLISHING、CANCEL_REQUESTED、FAILED、INTERRUPTED | 已完成 EOS、封装检查和解码 smoke |
| PUBLISHING | COMPLETED、CANCEL_REQUESTED、FAILED、RECONCILING | 取消命令与发布事务由同一 owner 串行决策 |
| CANCEL_REQUESTED | CANCELLED、INTERRUPTED、RECONCILING | 只有物理停止和释放均确认才 CANCELLED；未确认不伪称已释放 |
| UNKNOWN、RECONCILING | 依据持久证据恢复为对应运行阶段或终态 | 必须确认同一远端/worker attempt，不允许猜测成功或自动重提 |
| COMPLETED、CANCELLED、FAILED、INTERRUPTED | 无后续业务转换 | 清理/媒体丢失/远端晚到结果作为独立事实；重试创建新 attempt |

每次更新通过 `runId + attemptId + expected stateVersion/status` 的条件事务；不匹配返回 stale，不回写整份旧快照。`FAILED` 之前也必须处理仍在执行的 worker；终态之外另存 `resourceState=HELD/STOPPING/RELEASED/UNCONFIRMED` 和 cleanupError，不能用 FAILED 掩盖未停止执行。新任务是否可进入取决于真实资源释放。

发布竞态固定为：取消先被 owner 接受则不提交成功媒体；COMPLETED 事务先提交则晚到取消返回 already-completed，不能删成功文件或倒写 CANCELLED。文件已 rename 但事务未提交时按 §8.2 的 journal 对账，不盲删。成功后的临时清理失败只记录并重试 GC，不把成功任务改成普通推理失败。

重试显式创建新 `attemptId/requestId`，可引用原 revision 和输入快照；要用新 seed 必须在新快照中明确记录。terminal 提交与 lease release 都幂等，且用 token/对象身份校验。

### 4.4 错误码

至少保留可测试的类别：

- INPUT_INVALID、INPUT_MISSING、INPUT_TOO_LARGE。
- WORKFLOW_INVALID、WORKFLOW_UNSUPPORTED_NODE、WORKFLOW_CYCLE、WORKFLOW_TYPE_MISMATCH。
- MODEL_MISSING、MODEL_CHECKSUM_MISMATCH、MODEL_ROLE_MISMATCH、MODEL_LOAD_FAILED。
- BACKEND_UNAVAILABLE、NATIVE_EXECUTION_FAILED、ACCELERATION_UNAVAILABLE。
- ENCODER_UNAVAILABLE、MEDIA_VERIFY_FAILED、DISK_FULL、PUBLISH_FAILED。
- CANCEL_REQUESTED、CANCELLED_AFTER_NATIVE_STOP、INTERRUPTED_PROCESS_DEATH。
- REMOTE_AUTH_FAILED、REMOTE_TIMEOUT、REMOTE_UNKNOWN、REMOTE_CANCEL_UNSUPPORTED。

用户文案可以更友好，但日志必须保留原始 cause、阶段和 requestId；不要把密钥、完整 prompt 或私有 URI 写入普通日志。

---

## 5. 模型 manifest 和资产准入

### 5.1 Wan2.2 TI2V-5B manifest

模型资产必须以受校验的 manifest 进入，而不是由 UI 猜文件名。manifest 至少包含：

    modelId: wan2.2-ti2v-5b
    family: WAN2_2
    variant: TI2V_5B
    revision: fixed upstream/export revision
    roles: dit, text_encoder_umt5_xxl, wan2_2_5b_vae
    format/quantization/precision
    per-file relative path, size, sha256
    tokenizer/encoder vocabulary revision
    license and attribution
    expected native API/ABI
    supported modes: text_to_video, image_to_video
    frame rule and dimension multiples
    recommended presets: measured only
    source URL and provenance record

角色、revision、checksum 与本次选定 manifest 一致后进入 load，沿用现有资产导入和运行错误语义。

本基线的组件映射已经能从源码确认：

| 角色 | 当前 MCA/upstream 映射 | 不能混用的内容 |
|---|---|---|
| 基础 5B DiT | `sd_ctx_params_t.diffusion_model_path`；格式/量化以真实 export manifest 为准 | Turbo 蒸馏权重、A14B high/low-noise 专家不是同一组件 |
| UMT5-XXL 文本编码器 | `sd_ctx_params_t.t5xxl_path` | 不以聊天 LLM、CLIP 文本编码器代替 |
| 5B 专用 VAE | upstream `docs/wan.md` 中 `wan2.2_vae.safetensors` | `wan_2.1_vae`、TAEHV 不能仅因文件可读就视为等价 |
| UMT5 tokenizer | 当前 `t5_unigram_tokenizer.cpp` 编译内嵌词表，绑定 native/词表 revision/hash | 不虚构一个必须另下 tokenizer 文件的要求；后续外置实现才加对应角色 |

TI2V-5B 不套用 Wan2.1 I2V 的 CLIP Vision 要求。TAEHV/其他近似 decoder 属于独立质量和许可实验，首个 correctness baseline 使用匹配的完整 VAE；变更 decoder 后另建 profile 与证据，不沿用原图像质量/内存结论。来源、文件大小和 hash 必须实际获取，任务包不得填看似可信的假值。

官方 Wan2.2 TI2V-5B 文档描述了 720P、24fps 和 T2V/I2V，但官方 Python/Comfy 的显存条件不能直接转换成 Android 内存承诺。MCA 初始预设使用 P0 实测结果；若只能在较小尺寸/较短片段正确运行，应在 UI 诚实显示该 preset，而不是伪称完整 720P。

### 5.2 许可证和来源门

实现前必须提交一份资产清单，逐项包含 URL、commit/revision、许可证、checksum、是否允许随 APK 分发、是否需要用户下载、是否带非商用限制。以下内容不能混成“Wan 官方 Apache-2.0”：

- 模型权重、量化权重、text encoder、VAE、TAEHV、LoRA。
- stable-diffusion.cpp 和 ggml 的 upstream 与 MCA patch。
- nightmare-mobile 参考代码和二进制。
- QNN/Hexagon/NPU SDK 和设备 runtime。
- ComfyUI GPL-3.0 及其自定义节点。

未完成法律审核时，可以做 fixture、契约和无模型 UI，但不能把权重或受限二进制打进分发包。

---

## 6. P0：Wan2.2 TI2V-5B 可行性门（必须先过）

P0 回答“当前 Android 运行时能否真实生成、取消并释放一个小视频”。P0 是 V1 任务卡的证据标签，不是另一套要重复实现的实验链：P0-A 对应 V1-01，P0-B 对应 V1-02a/02b，P0-C 对应 V1-03，P0-D 对应独立加速/内存评估。spike 通过后合入同一生产链并删实验入口；未通过保留证据，不遗留第二套 owner/bridge。

### 6.1 P0-A：资产和 native 静态对齐

任务：

1. 固定模型 manifest、stable-diffusion.cpp revision 和本地 patch。
2. 确认 WAN2_2_TI2V 需要的 Dit、text encoder、VAE、tokenizer、可选 TAE 角色。
3. 对比 sd_vid_gen_params_t、generate_video 的参数与 MCA contract，列出 prompt、negative prompt、init image、width/height、frames、fps、steps、seed、guidance、flow shift、cancel 和 progress 映射。
4. 确认 Android ABI、CMake 开关、线程、内存分配、native logging 和 shutdown 行为。
5. 记录当前仅 CPU、关闭 Vulkan/WEBM 等事实；任何加速方案单列 spike，不写“已有 NPU 支持”。

验收：有一份可复现的 manifest、参数映射表、license 表和 native diff 清单；没有未经证据的性能数字。

### 6.2 P0-B：最小 bridge 和 worker spike

只实现一个可删除的 vertical spike：

- bridge 提供 typed generateVideo request/result/progress/cancel/shutdown，不复用图片 JSON 字符串来隐藏视频字段。
- 输入通过安全 staging file 或 PFD/token 传递，不把整张图、全部帧或 MP4 放入 Binder/JSON。
- worker 只接受一个 active request；重复提交有明确拒绝。
- owner 生成 requestId、epoch 和 lease，回调做 identity 校验；旧请求不能写新状态。
- native 调用结束后的 finally 统一释放结果和 context；仍在执行时绝不跨线程 shutdown/free。不可及时停止则按 §7.2 回收隔离进程。
- 先输出受控临时帧/文件，不能直接写最终媒体 URI。

验收：bridge 单测能覆盖参数校验、callback 顺序、cancel 后回调、shutdown 异常；worker 测试能覆盖 binder death、进程退出、重复 cancel 和 stale completion。

### 6.3 P0-C：真实 correctness baseline

在至少一台代表性 ARM64 设备上，使用 debug/内部构建完成：

- TI2V 文生视频小片段真实 load、condition、sample、decode。
- TI2V 图生视频小片段真实 load、输入图校验、sample、decode。
- 生成输出帧数与帧规则一致，不能只返回黑帧或空文件。
- 取消一次正在采样的任务，观察用户取消、native 停止、worker 退出/复用和资源释放四个时间点。
- 连续运行两次，第二次不能使用已释放 context 或 stale progress。
- 记录 Java heap、native heap、PSS/RSS、峰值磁盘、耗时、阶段耗时和温度/热状态；CPU correctness 结果不能替代加速结果。

P0 通过条件：每种模式至少一个真实可辨识片段，错误/取消/释放均有证据；不要求在此阶段承诺生产分辨率和时长。

### 6.4 P0-D：移动加速和内存 spike

CPU 仅作为正确性基线。另做独立 acceleration spike：

- 检查 stable-diffusion.cpp/ggml 的可用 backend、Vulkan/Android GPU、Hexagon/QNN 或其他已许可 transport。
- 只在有真实 ABI/runtime 和 graph smoke 的情况下宣传加速；不能把聊天 QAIRT、MNN 占位或设备 profile 名称当作 Wan DiT 支持。
- 对“整段帧数组”与“frame sink/分块解码/受控 spool”分别测峰值；如果峰值超出设备可用内存，记录为 BLOCKED，并给出最小 native API 改造，而不是下调画质后宣称通过。
- 不以减少帧数、偷偷改变分辨率或更换模型来伪造同一测试。

P0 输出必须明确：

    CPU correctness: PASS/FAIL/NOT_RUN
    acceleration: PASS/FAIL/NOT_RUN/BLOCKED
    text-to-video: PASS/FAIL/NOT_RUN/BLOCKED
    image-to-video: PASS/FAIL/NOT_RUN/BLOCKED
    cancellation: PASS/FAIL/NOT_RUN/BLOCKED
    release/repeat: PASS/FAIL/NOT_RUN/BLOCKED
    peak memory and storage: measured values + device/build/model
    unsupported capability: explicit list
    rollback: remove spike and keep UI/contract behind unavailable state

P0 未通过时，本地 Wan 生成保持诊断/不可用状态；已验证的入口、图编辑、媒体链可以继续。用户要求的主线次序保持 V1→V2→V3，不能把远程结果冒充本地通过。若要改变优先级，单独记录产品决定；局部后端阻塞不等于所有领域都必须停工。

### 6.5 当前 native ABI、参数与内存边界

当前头文件的真实接口是：

```cpp
sd_vid_gen_params_t params;
sd_vid_gen_params_init(&params);
sd_image_t* frames = nullptr;
int frame_count = 0;
// Wan V1 无音轨，audio_out 传 nullptr。
bool ok = generate_video(ctx, &params, &frames, &frame_count, nullptr);
```

这只是 ABI 示意，不能当成完整可运行代码。adapter 必须检查 bool、count、每帧宽高/通道/指针、字节乘法溢出及实际帧数，再转交编码。RAII 管理每帧 `free(frames[i].data)`、数组 `free(frames)`、输入 buffer 和 context；包括失败返回后的部分输出、JNI pending exception、编码失败和取消。`free_sd_ctx` 不释放结果数组。若未来输出音轨，单独接 `free_sd_audio` 与音频契约，不在无音轨 V1 偷加。

| MCA 字段 | 本基线 native/输出落点 | 必须明确的行为 |
|---|---|---|
| prompt / negativePrompt | `params.prompt / negative_prompt` | UTF-8 buffer 活到 native 返回，日志脱敏 |
| initImage | `params.init_image` | 方向/色彩/裁切先固定并记录，输入 buffer 活到返回；V1 不映射 end_image/control_frames |
| width / height | `params.width / height` | 本基线 TI2V VAE scale=16、DiT down factor=2，即 32 的倍数；换模型/revision 重新核对 |
| frameCount | `params.video_frames` | 当前 Wan 向下对齐 `4k+1`，6 会变 5；编译前只接受合法值或显式确认 resolved，禁止静默缩短 |
| fps | `params.fps` 与编码时基 | 当前 ABI 为正整数；与训练参考帧率分开记录，不提供未实现的小数帧率 |
| steps / sampler / scheduler / eta | `params.sample_params` 对应字段 | 记录 requested/resolved；不套用 Turbo 四步预设 |
| guidance | `params.sample_params.guidance.txt_cfg` | 不与 distilled guidance 混名 |
| flowShift | `params.sample_params.flow_shift` | 仅在已核实的 Wan 路径生效；native effective 回显 |
| seed | `params.seed` | 随机选择在提交前执行一次并持久化；不在 CAS reducer 生成 |

本基线 single-DiT 5B 不使用 A14B 双专家的 high_noise_sample_params/moe_boundary。V1 compiler 必须拒绝尚未映射的参数，不能接受后忽略。

上游先持整段解码 float tensor，再分配全部 RGB 帧；仅把结果逐帧写磁盘或送 encoder **不会消除上游 decode 峰值**。`W×H×F×3` 只是 RGB 下界，不能代替权重、latent、attention workspace、VAE float tensor、RGB、IPC/codec 和其他进程的总峰值。预检用 checked 64-bit 乘法、实际磁盘可用空间和运行时分配结果，测量记录模型/精度/参数/热状态/样本数。

Wan VAE 中现有分块分支被禁用且附输出异常注释，禁止把 `if (true)` 改成 false 就宣布流式成功。真正分块/流式需要独立切片，证明时域 cache、边界帧、数值/视觉质量和取消等价。保留完整 VAE 的 correctness baseline 作比较。

P0-D 加速可标 BLOCKED/NOT_RUN，而 P0-C 仍独立成立；只有已有真实结果的 backend 才列可用。另设“移动实用性”记录生成时间/热/内存/重复执行，CPU correctness 通过不能宣称已达到参考项目速度。V1 是否可产品化由这些实际结果决定，不把加速支持写成无证据的完成前提或承诺。

V1-03 的独立出口是已校验的真实帧序列、参数、内存、取消和释放证据，编码尚未完成时不要求凭空出现 MP4。P0-C 的完整短片联合出口在 V1-04/V1-06：把同一真实帧序列接真实 codec，完成媒体与播放验证；不得用合成帧或另一模型补这个出口。

在 V1-03 形成实测 execution profile 后，V1-06 前冻结产品预算表：最小可交付宽高/帧数/fps、端到端和分阶段最长时限、总内存峰值/最低余量、临时磁盘上限、取消受理/物理停止时限、连续样本数/成功要求、热状态与降速处理。值必须来自 P0 测量及目标体验，不在方案中编造。预算未确定或未达标只能称“实验闭环”，不能称“产品可用”；后续变更预算须说明规格/质量影响，不能事后改门槛消除失败。

---

## 7. 执行链和资源生命周期

### 7.1 提交链

    UI intent
      → VideoWorkflowPort.submit(revisionId, inputRefs, options)
      → VideoGenerationOwner.admit()
      → freeze snapshot + Room insert QUEUED run + durable journal
      → stage/verify input files
      → acquire chat lifecycle lease（主进程）
      → worker begin(requestId, snapshot)
      → acquire shared media file gate（worker）
      → native load/condition/sample/decode
      → encoder produces staged MP4
      → container verification + real decoder smoke
      → fsync + atomic rename
      → Room transaction publishes media + run COMPLETED
      → release lease, delete journal, emit read-only projection

任何一步失败都要走可追踪 terminal state；不能在 UI 层 catch 后只显示“生成失败”并丢掉 requestId。

Room run 和 journal 的首次写入本身仍有崩溃窗口，处理规则见 §8.2；不把两个 IO 称为原子事务。请求提交返回 durable runId 以后，页面重进只观察该 run，不自动再 submit。V1 的产品主入口是视频 UI；内部保留可复用 typed port。现有聊天/图片 Local API 路由保持不变，视频 HTTP 路由若另行增加，必须有独立版本、认证、幂等和断连合同，不能把视频任务塞进 `/v1/images/generations` 冒充现有协议兼容。

### 7.2 取消

取消分三层：

1. CANCEL_REQUESTED：用户命令已被 owner 接受并持久化。
2. NATIVE_STOPPED：bridge 返回或 worker 明确确认 native 停止。
3. RESOURCES_RELEASED：context、lease、临时文件和 worker 资源已释放。

只有三层完成后才显示“已取消并释放资源”。如果 UI 断开但任务继续，页面重进后从 Room/journal 观察；如果进程死亡，标 INTERRUPTED，不说“已取消”也不说“自动续跑”。

真实取消 API 为 `sd_set_cancel_callback`，采样在协作检查点轮询；不能承诺打断正在进行的 graph compute。当前 Wan VAE 完整 compute 缺少细粒度取消点。Owner 为 load/sample/decode/encode 分别记录超时与 watchdog；超时先请求停止，仍未停止则退出专用 `:local_video` worker。只有确认对应 PID/启动身份的进程已终止，才释放其租约和回调状态。禁止 native 仍执行时并发 free context，也不能只取消 Flow collector 就报告停止。

cancel/progress/preview 在当前 DSO 内是全局 callback；单个 worker 内须串行安装、绑定 attempt 并在停止后解除，不能与同进程图片请求互相覆盖。V1 默认独立 `:local_video` 进程隔离这些全局状态，跨进程资源仍按 §7.4 共用锁域。JNI 回调跨线程需正确 attach/detach，禁止把 pending exception 带回 native 或重入 UI/持锁业务回调。

### 7.3 进程和 worker

新建 LocalVideoWorkerService/AIDL，采用独立 `:local_video` 进程，并复用现有 worker 的身份和死亡检测模式：

- begin(requestId, epoch, immutable snapshot, file tokens)
- observe(requestId)
- cancel(requestId, epoch)
- cancelAndExit(requestId, epoch)
- shutdown(reason)

接口保持窄小，文件用 content/PFD/token，metadata 用 typed parcelable；禁止传 ByteArray 全帧。worker 不拥有 UI 状态，owner 是唯一终态提交者。

本项目 minSdk26、targetSdk36。Manifest、AIDL 打包/混淆、通知渠道、启动时序、前台服务类型/权限及对应 subtype 必须同时接线。现有 image service 的 specialUse 声明仅作参照；根据真实用途核对系统要求，不能通过乱标 dataSync 或 mediaPlayback 绕限制。覆盖前后台启动限制、通知权限拒绝、`Service.onTimeout` 适用路径、系统回收与重新进入页面。长任务不能依赖一个未注册或未合法提升的 service 存活。

### 7.4 共享资源仲裁

当前源码有三个不同职责，不能混称一个“RuntimeSupervisor 锁”：

| 层次 | 当前源码 | 视频接入要求 |
|---|---|---|
| 产品请求身份/单 active | `LocalImageGenerationCoordinator`、`ImageExecutionGate`、共享 RuntimeSupervisor | 保留既有身份与终态合同；视频有自己的 owner，不增加图片领域职责 |
| 聊天模型生命周期/内存让路 | `RuntimeReleaseOperations.acquireExclusiveLifecycleLease` 所调用的 engine 租约 | 视频通过窄 typed port 复用这一能力；不能回调 MainViewModel 取得整应用状态 |
| native 物理执行互斥 | `LocalImageExecutionGate` 的 `native_image_execution.lock`；图片 generate/upscale 在 `LocalImageWorkerExecution` 接入 | V1-00 等价推广同一锁域，使视频 worker 也进入；保留旧锁文件兼容，不能改成另一个名字令新旧 worker 同时运行 |

统一获取顺序为：产品请求登记 → 聊天生命周期租约（主进程）→ 物理媒体文件锁（worker）→ native context。反向释放；完整生命周期内按 identity 校验。owner 不再次获取同一个非重入文件锁，worker 不回头等待聊天租约，避免自锁和跨任务互等。排队不持 native 锁；锁竞争、超时、取消等待、进程死亡释放、旧 release 和回调污染分别测试。

V1-00 必须审完整 UI 生图、聊天图片工具、Local API 图片、upscale、native smoke/其他实际调用者：同一物理资源全部落同锁域，同时保持原有取消和失败行为。只新增一个视频 semaphore 没有价值。先证明实际资源已释放，再允许下一 native 任务；cleanup、文件发布和播放器不任意延长 GPU/NPU 锁。需要减少持锁范围时，确认 native 全部帧已转移所有权且 context 已释放，另设受控编码并发预算。

---

## 8. 视频媒体存储与播放链路

### 8.1 Room 表设计

不要修改 image_assets。将新表注册到同一个 McaRoomDatabase，实际版本号必须在实现前从工作树重读后递增，不能盲写“29→30”。

建议表：

- video_workflow_revisions：workflowId、revisionId、source、schemaVersion、canonicalGraphJson、rawSourceRef、sha256、created/updated。
- video_runs：runId、attemptId、requestId、stateVersion、revisionId、backend、model/profile fingerprint、requested/resolved/nativeEffective 参数、状态、资源释放状态、阶段、时间、错误、remoteEndpointId、remotePromptId。
- video_run_nodes 或 video_run_events：节点状态、阶段、进度、错误和时间；限制保留量，避免每个采样 step 无限写 Room。
- video_media_assets：id、runId、owned content URI/token、mime video/*、size、sha256、width、height、durationUs、fps、frameCount、codec、audio flag、source、favorite、session/project。
- video_run_media_links：输入/输出关系；不要把一个视频同时靠字符串字段关联多个业务对象。

图 revision 不可变，run 绑定 snapshot，media 只引用已验证的 published file。Room migration 必须保留 v29 的旧行、索引和 foreign key，并有 schema JSON、MigrationTestHelper 和升级/回退边界说明。

### 8.2 文件和数据库两阶段提交

建议每个 run 使用独立私有目录：

    video/
      runs/<runId>/
        input/
        frames-or-spool/
        output/
        journal.json

协议：

1. Room 写入 run + PREPARING journal；输入文件复制到 staging，校验大小、mime、digest、路径和空间。
2. native/encoder 只写 run 私有临时目录，绝不直接写最终媒体 URI。
3. MP4 完成 EOS 并关闭 muxer 后 fsync；MediaExtractor 验证轨道、尺寸、取样、时长与时间戳合同，再用真实 decoder/播放器做可播放 smoke，二者分开记证据。
4. 在 owned root 内 atomic rename 到最终 leaf，禁止路径逃逸、符号链接和覆盖已有文件。
5. Room transaction 插入 video_media_assets/link，并把 run 标 COMPLETED。
6. transaction 成功后删除 journal 和临时文件；任何中间失败保留可诊断状态。
7. 启动 reconcile 扫描 journals、orphan temp、已发布但未登记的文件和 DB 指向不存在的文件；操作必须幂等。native 无法恢复时只能标 INTERRUPTED/RECONCILING，不能从 latent 假装续跑。

视频不能复用图片 96 MiB 上限、Bitmap decoder 或 image/* MIME 校验。应配置独立的最大输入、最大输出、磁盘保留、最大帧数和 staging TTL，全部以可解释错误码拒绝。

journal 在发布前持久保存 schemaVersion、run/attempt/request、计划 assetId、相对临时/最终路径、输入与快照 digest、期望帧数、大小/hash、publishPhase 和状态版本；只存受控 root 下的相对路径。不能信任磁盘上 journal 中任意绝对路径。文件与 rename 所在目录按平台能力同步，记录 fsync 不支持/失败；不得把 copy 跨卷操作写成 atomic rename。

| 崩溃点/启动发现 | 幂等恢复规则 |
|---|---|
| journal 有计划，Room 无 run | 校验身份与来源；没有已提交请求则隔离/清理本 attempt 暂存，不凭 journal 自动生成新任务 |
| Room 有 QUEUED/PREPARING，journal/输入尚不完整 | 标 INTERRUPTED/输入准备失败；保留快照供显式重试，不执行猜测的模型 |
| 暂存 MP4 完整，尚未 rename | 校验 journal、digest 和完整性；有 PUBLISHING 证据且未取消才可补发布，否则隔离，不把任意 part 当成功 |
| rename 完成，media row 未提交 | 根据计划 assetId、最终路径/hash、attempt 与取消顺序补登记或标待处理；冲突隔离，禁止删除别的 run 文件 |
| media row + COMPLETED 已提交，journal 未删除 | 以事务为权威，保留已发布文件，仅幂等清理 journal/temp |
| media row 存在，文件丢失/损坏 | 标媒体 MISSING/CORRUPT 并提供重试/移除；保留 run 历史成功事实，不伪造空库 |
| worker 已死但资源状态未结算 | 按 PID/启动身份确认终止、处理锁释放并结算 INTERRUPTED；不能重启原 native 从中间继续 |
| 无可验证归属的孤儿文件 | 进入隔离区/带 TTL 的待回收记录；先核对所有引用与活跃 lease，不按文件名批量删 |

启动对账只操作失效 attempt，避免与活跃 worker 竞争。连续运行两次 reconcile 必须产生相同有效媒体集合；每个窗口用故障注入测试证明。主 schema/journal 的最小切片在 V1-02a 先落地，V1-04 增补媒体发布字段/表，不能真实跑完任务后才首次设计持久化。

### 8.3 编码器

V1 默认 Android MediaCodec + MediaMuxer，H.264 MP4、无音轨，直到有真实音频需求。必须验证：

- MIME、profile/level、颜色格式、stride/slice height、YUV/RGB 转换。
- 输入展示时间戳为 checked 64-bit `frameIndex * 1_000_000L / fps`，从 0 起；记录实际 sampleCount、EOS、最后一帧时长和 muxer finalize。生成帧数与视频时长分别保存，不能把 `4k+1` 直接四舍五入成用户声称的秒数。
- V1 选择无 B 帧的编码配置/能力路径；若编码器仍重排，按展示顺序校验并显式处理 DTS/PTS，不对压缩输出读取顺序强加错误的单调断言。
- 编码器能力查询失败时显示不可用；不在设备不支持时生成半文件。
- 取消、异常、磁盘满时停止 codec、删除 part 文件并保留 run 错误。
- 如果 native decode 峰值超标，先在 VAE/结果生产边界验证真正的流式/分块改造，再扩大分辨率/时长；结果数组生成后的 spool 只降低下游持有量。

编码必须有输入/输出推进 watchdog，区分“当前没有可用 buffer”和“长期不前进”；EOS drain 无界等待是失败，不能在 100% 永久悬停。优先调查 Surface 输入＋受控 EGL presentation time 的路径，或经实测正确的 YUV buffer 路径，不直接复制参考项目编码器。硬件编码失败后如有软件 fallback，使用同一已冻结帧和编码参数，清理旧 part，从头编码并回显实际 codec；不能重跑模型或改变 fps/画面质量来掩盖失败。输出验证包含首/中/末帧解码和完整短片播放，MediaExtractor 不单独承担可播放证明。

### 8.4 播放、导出和删除

- 播放层使用固定版本 Media3/ExoPlayer（依赖审计通过后再加入），由 video feature 接收 content URI 和 metadata，不接收 native path。
- Compose 用 AndroidView 或现有项目认可的 Media3 Compose 封装；页面离开/旋转/后台按生命周期 pause/release，不能停止生成任务。
- 后台不自动播放；首帧/缩略图生成失败时仍显示可重试状态。
- API29+ 导出到 MediaStore.Video 使用 IS_PENDING，完整复制/校验后 publish；API26–28 默认用 SAF `ACTION_CREATE_DOCUMENT` 取得用户选择目标，不索取不必要的广泛存储权限。失败清理只涉及本次创建的目标，不删除用户已有文件。分享使用受控 FileProvider/SAF URI，不暴露内部路径。
- 删除先提交 tombstone，隐藏相应可见条目，再由幂等 GC 在引用计数和播放/导出 lease 释放后删除自有文件。失败保留待重试记录；物理删除不可撤销，不宣称能够“回滚已删视频”。不删除用户原始输入或已导出的独立副本。

---

## 9. 视频页面和交互契约

任务模式：EXISTING_SYSTEM + android-native。参考 ComfyUI 的“节点关系可见、参数可检查、运行状态可追踪”，但沿用 MCA 的主题、密度、图标和导航。

### 9.1 入口

左侧栏固定为：

    图片
    Agent
    DSH
    视频
    最近

视频的 test tag 建议 drawer.video_workflow，content description 为“打开视频工作区”。AppTab 追加 VIDEO_WORKSPACE 或 VIDEO，旧 tab 的持久化/ordinal 不重排。返回、系统 back、抽屉关闭和横竖屏行为先写测试再接 UI。

### 9.1.1 实施前冻结的手机 UI 合同

| 项目 | V1 合同/复用来源 |
|---|---|
| 主任务与层级 | 提示词/输入图→检查图和参数→生成→看视频；运行详情从属，原始日志默认折叠 |
| 视觉来源 | `core/designsystem/McaTheme.kt`、McaButton/McaIconButton、McaIcons、McaMotion；使用 MaterialTheme 已有语义色/字体/shape，不新增平行 token 或专属黑色主题 |
| 页面外壳 | 沿 MainActivity 覆盖页/状态保持模式，抽屉复用现有 AppFeatureButton；只新增已授权的视频入口 |
| 滚动 | 简化视图一个主要纵向滚动区；高级画布单独平移/缩放；参数 sheet 自己滚动，输入焦点可见 |
| 形状/强调 | 沿用对应现有控件半径与边界；连线节点只因可操作对象需要容器，不把每段信息都包卡片 |
| 动效 | 页面沿现有转场，节点选中/连线反馈轻量；进度以文本和真实阶段表达，遵守系统减少动态效果 |
| 适配 | 紧凑手机先模板＋简化图＋sheet；较大窗口才画布＋inspector，按实际可用宽度切换，不能根据机型分布局 |
| 返回/草稿 | 先关参数/播放器覆盖层，再退出视频；离页保存草稿，不取消运行；运行绑定原 revision，改图不影响正在生成的快照 |

精确尺寸、字体度量和圆角从对应现有组件读取，不在方案里凭空给新数值。支持的窗口/方向和布局变化必须由真实渲染证明；不因为方案画了大屏结构就宣称已支持平板。

### 9.2 首屏构图

手机竖屏：

1. 顶栏：返回、标题“视频”、当前工作流 revision/运行状态；不放大幅 hero。
2. 模式行：本地 MCA（V1 唯一可运行）、文生视频/图生视频模板切换。
3. 主工作区：默认简化图，显示 Input → Wan2.2 TI2V-5B → Sample/Decode → MP4；点击节点打开参数 bottom sheet。
4. 预览区：空态、生成中占位、首帧/视频播放器、错误和重试。
5. 底部操作：生成/取消/重试；键盘打开时由 imePadding 和明确 scroll owner 保证按钮可见。

较大屏幕/横屏：

- 左侧窄导航或保持 app shell；
- 中央节点画布；
- 右侧 inspector；
- 底部输出/运行日志抽屉；
- 不能把手机底部 sheet 直接拉宽成桌面卡片墙。

滚动所有权必须明确：画布平移/缩放独立于参数表单滚动，不能有互相抢手势的无限嵌套 LazyColumn。

### 9.3 参数分组

第一版只暴露能映射到真实 native 的参数：

- 输入：prompt、negative prompt、本地输入图。
- 输出：宽、高、帧数、fps；选项来自 manifest 和 measured preset。
- 采样：steps、seed、sampler、guidance/flow shift（只有 native 已证明支持才显示）。
- 资源：线程/transport 放在 advanced/debug，默认界面只显示有助用户选择的预计成本与运行信息。
- 运行：生成、取消、保存、删除、分享。

禁用或未实现的参数要显示“当前模型/运行时不支持”和诊断，不显示一个看似可改但实际被忽略的滑杆。

### 9.4 状态矩阵

必须设计并测试：

- 首次空态：解释本地模型下载/校验，不伪造示例视频。
- 模型缺失/校验中/加载失败。
- 图编辑草稿、保存 revision、导入诊断。
- 排队、准备、采样、编码、发布。
- 首帧可用但最终文件未完成。
- 正常成功、播放错误、分享失败、磁盘空间不足。
- 取消请求、取消中、已停止、资源释放。
- worker death、应用重启后的 INTERRUPTED/RECONCILING。
- 深色/浅色、大字体、长 prompt、键盘、系统 back、旋转。
- 无障碍：节点有 role/label，连接关系不只靠颜色，进度有文本，按钮触达区域符合 Android 规范。

采用已有 McaTheme、McaIcons、McaButton 等组件和语义色；默认 cardless、一个主 accent、少量 surface 层级。不要复制 ComfyUI 的桌面黑色画布、霓虹边框或大量 pill。

### 9.4.1 原生节点编辑器的最低功能

“参考 ComfyUI”落实为真实可编辑关系，而非一张静态流程图。V0-03 先做一份模板贯穿的纵向切片，之后在同一 feature 内补全：

- 简化模式可以选中节点、检查输入/输出、修改支持参数；高级模式可从版本化 registry 添加/删除节点，连接/断开 typed 端口、拖动位置、缩放/平移和 fit-to-graph。
- 连线前后均验证类型、缺失端口和环，错误锚定具体节点/端口；禁止自动删 unsupported 节点或无提示补边。删除被引用节点先预览影响，不静默改执行语义。
- 参数编辑、节点/边操作有有界 undo/redo；`SavedStateHandle` 只存轻量选择/视口键，完整图草稿存 repository。位置/折叠独立于执行 digest，编辑自动保存草稿，显式保存产生 immutable revision。
- Canvas 的节点命中区与可见图形分开；提供可访问的节点/连接列表和按钮式连接替代，不能要求精确拖线或只用颜色识别端口。拖动/缩放不抢系统返回边缘手势，sheet 开启时画布停止接收背景手势。
- 只渲染可见节点/边，不在每次 pointer move 或重组做 JSON 解析、拓扑排序、文件 IO 或 native 调用；测试真实目标图规模，超出产品上限给明确错误。
- 点击运行冻结本轮 revision/参数/输入；运行中允许编辑下个草稿，必须清楚显示“当前运行版本”和“草稿有改动”，不能悄悄让 native 读取新值。

首个 UI 切片以注入式 fake port 覆盖状态，仅用于开发/测试，生产按钮必须调用真实 owner 或明确显示未接线状态。截图/占位视频不能作为原生生成结果。

### 9.5 视觉验收

每次影响 UI 的切片都要：

1. 固定空态、编辑态、运行态、成功播放器态和错误态。
2. 在紧凑手机、大手机、键盘打开、浅色/深色下真实渲染。
3. 第一轮按构图、层级、间距、字体、状态列出最多五个最高影响问题并修复，不凑数量。
4. 第二轮复查已有问题和可访问性，记录仍存在的问题及范围。
5. 没有真实渲染就写 NOT_RUN，不写“视觉通过”。

---

## 10. ComfyUI API 工作流导入（V2）

### 10.1 只支持 API-format prompt graph

ComfyUI 常见的 UI workflow JSON 与 API-format prompt JSON 不同。V2 首先支持 API-format：

    {
      "node_id": {
        "class_type": "…",
        "inputs": { "…": "…" }
      }
    }

导入 UI JSON 时若无法确定其 API 语义，应明确提示“请导出 API format”，不能静默猜测。版本、class_type、inputs、依赖引用和输出节点都必须进入 parser fixture。

### 10.2 导入流程

1. SAF/FileProvider 读取，限制大小、编码、深度、对象数量和字符串长度。
2. 保存原始文件到受控 source ref；parser 生成 canonical graph 和 digest。
3. 校验 duplicate/missing refs、cycles、port/type mismatch、整数/浮点边界、未知 class_type、路径和模型字段。
4. 根据固定 ComfyUI server revision 建立 allowlist：节点 class_type、输入参数、默认值、输出类型、映射版本和已知语义差异。
5. 能映射到 MCA 的节点 lower 成 MCA graph；不能映射的节点保留在诊断中，并给出 node id/path/原因。
6. 只有所有必需执行节点支持且模型角色显式绑定时，才允许“运行”；部分导入可以保存为不可运行 revision。
7. 预览页显示源格式、兼容性、模型映射、参数变化和风险，用户确认后保存 revision。

初始 allowlist 不要凭印象写节点名。先从目标 ComfyUI revision 导出最小 text/image-to-video fixture，再将每个 class_type 记录到 compatibility matrix。概念上需要覆盖的角色是 LoadImage、TextEncode、Wan model/conditioning、sampler、decode、video output；实际 class_type、参数和版本必须来自 fixture。

每份映射 fixture 同时记录输入默认值、输出槽位索引、scheduler/sigma、latent 缩放、帧规则、decoder、seed 和 save-video 行为。Comfy 的 KSampler/自定义 Wan sampler 不自动等于 sdcpp sampler；不能只按节点名字相似就 lower。不能证明等价的子图保留为“已导入、当前本地不可执行”，由 V3 显式选远端处理。

解析采用严格 JSON：拒绝重复对象键、非有限数、超深结构、超大字符串/数组与整数溢出。导入限额先固定一组可测试的产品值（建议初始 4 MiB、128 节点、512 边、深度64，实施时测量确认），不向任意 JSON parser 无界灌入文件。Comfy `inputs` 的 `[nodeId, outputIndex]` 与普通数组值按已固定的节点字段 schema 区分，不能把每个二元素数组都当引用。执行以用户明确选择的输出根及其依赖闭包为单位；未参与执行的节点保留在文档与诊断中，影响所选输出的未知节点必须阻止该次编译，不偷偷剔除。

### 10.3 模型绑定

Comfy 的 filename 不能自动绑定 MCA 中同名但 checksum 不同的模型。模型映射必须包含 role、family、revision、checksum、precision 和用户选择；缺少映射时导入成功但运行不可用，并显示修复入口。

### 10.4 图执行

V1 已须实现 MCA 原生受限 DAG→VideoRequest 的生产 compiler，落在 `app/.../video/workflow/` 的纯 Kotlin 逻辑，由 process owner 注入调用；类型和诊断协议在 `core:video-contract`。它校验输出根、拓扑、typed 输入、模型、参数与 immutable snapshot，不从 Compose 动态读状态。V1-02a 完成该编译链，UI 模板和手工图共用，不能让节点编辑只是装饰。

V2 的新增 parser/Comfy lowerer 同样放在纯实现位置，通过契约接入，专做 Comfy→MCA 原生 graph 映射，不把完整 JSON parser/编译实现塞成万能 contract。支持的子图可以合并到一个 native pipeline；逻辑节点不等于独立 native context。进度报告真实阶段，V1 不承诺节点级 checkpoint 或断点续跑。

---

## 11. 远程 ComfyUI 后端（V3）

远程后端必须是用户明确选择的 backend，和本地 MCA native 分开显示。断网、权限、服务端版本或本地执行失败都不能自动切换。

### 11.1 endpoint 和安全

- endpoint 由用户在设置或视频页显式添加，保存的密钥进入凭据存储，不进 workflow JSON、Room run JSON、日志或分享文件。
- TLS、认证、redirect 同源、请求大小、响应大小、下载限额和 MIME/路径校验按真实信任边界实现。
- 不用“默认禁止 LAN 的 web search reader”替代 Comfy endpoint 的明确网络权限，也不能为了方便关闭全局 SSRF 防护。
- endpoint 记录 server version/capability，导入前做 object_info/能力探测；不支持 targeted cancel 的旧 server 不能伪装成安全取消。

用户显式配置的 LAN endpoint 可以连接；工作流/远端输出不能再任意指定第二个下载主机。限定 scheme、host、port 和允许的同源服务路径，重定向重验目标并禁止跨源携带凭据。远端返回的 filename/subfolder/type 只作为 `/view` 的编码查询参数，不当本地文件路径或新 URL；验证目录语义、长度和下载后的文件。未知 Custom Node 的“输出 URL”不能直接触发通用 fetch。

### 11.2 任务协议

1. 提交前持久化 endpointId、clientId、唯一 submitNonce、graphHash、输入引用和状态 SUBMITTING；尽量先建立 `/ws?clientId=…` 再 `POST /prompt`，保存服务端返回的 prompt_id。clientId 是事件关联，不能自动当幂等键。
2. WebSocket 只作为阶段/事件通道，不是唯一终态来源；所有事件校验 endpoint 与 prompt_id。图生视频先用该版本支持的 `/upload/image` 上传已校验输入，持久化本地 digest→远端文件引用，不把 Android content URI 传给服务端。
3. 已知 prompt_id 时用 `/queue` 与 `/history/{prompt_id}` 对账；history 暂无记录不等于任务失败。若提交丢响应且没有 ID，只能依固定 server revision 的幂等扩展或可核对 nonce 的 queue/history 查找；clientId 本身不能保证唯一任务。无法关联则保持 UNKNOWN，禁止自动重发；显示“提交结果未知，需对账”与明确用户操作，不能假装已经找到 history。
4. 成功输出通过 `/view` 下载到本地 staging，校验大小、MIME、签名、hash、封装与真实解码，再按本地媒体协议发布；不要信任路径或仅按扩展名判断视频。
5. queued 任务可用 /queue 删除；running 任务只能在服务端明确支持 targeted /interrupt 时取消。空 body 的旧 /interrupt 可能是全局中断，禁止调用影响他人。
6. 远程 run 的 Room 记录保存 endpointId、server revision、prompt_id、remote status 和本地下载状态；WebSocket 丢事件不能改变历史事实。

### 11.3 远程与本地的边界

远程任务不必持有本机 native lease 全程，但下载、校验、发布仍经过 VideoMediaRepository。远程模型名不自动变成本地模型 manifest；远程输出不能绕过本地安全验证和 Room 状态机。

支持等级分开记录：提交/观察/下载可用、排队撤销可用、运行中 targeted cancel 可用。旧服务器缺 targeted cancel 不意味着可以调用全局 interrupt；UI 只能提供“停止本机等待”，并明确远端任务仍可能运行。V3 完整取消能力的验收只对实测支持该协议的固定 server revision 成立；不得把未支持的操作包装为成功。

### 11.4 对标能力与后续节点次序

“对标 nightmare-mobile”以用户行为和完整执行链为目标，不能把其所有模型/插件一次塞入 V1。实现时使用下表跟踪差距，每行单独验收：

| 能力 | MCA 阶段/节点 | 输入→输出与验收边界 |
|---|---|---|
| 原生工作流和模板 | V0/V1；§4.2 八类节点 | 真实增删/连线/参数/保存/恢复；模板和手工编辑走同一 compiler |
| 5B 文生与图生 | V1 | 基础 TI2V-5B 两种模式→无音轨 MP4；完整 VAE，真实取消/释放/重试 |
| 图/参数交换 | V2 | 导入 API-format 子集，导出 MCA 自有 versioned 文档；明确不承诺 UI-format 往返无损 |
| 完整 Comfy 远端生态 | V3-01 | 显式远端提交和媒体回收；不在 Android 执行 Custom Node/Python |
| 输入裁切/缩放、首/末帧提取 | V3-02a | 已验证 image/video ref→image ref；方向/色彩/裁切/帧选择进 snapshot，精确标明新资产与来源 |
| 视频裁切与连接 | V3-02b：Trim/Concat | video refs→video asset；支持时间基/关键帧边界/无损 remux 或显式 re-encode，禁止直接拼字节；不默认有音轨 |
| 用前段末帧续写 | V3-02c：LastFrame→TI2V→Concat | 上一段已提交资产→输入图→新 run→连接结果；原段保留；重复边界帧与时长按明确规则处理，没有“native 断点续采样” |
| 5B LoRA/加速配方 | V3-02d，独立 profile | 校验 family/角色、权重、强度、采样与许可；不能把14B LoRA套5B，也不能默认 Turbo 参数适配基础权重 |
| Motion transfer/Fun Control等 | 后续独立 backend 实验 | 先有真实模型/条件张量/资源证据再增节点；不能由基础 TI2V 的 init_image 推导已支持控制视频 |
| 14B、MiniMax H3 本地 | E-01 | 分别审资产、内存与真实 backend，不计 V1/参考能力已对齐 |

V3-02a→02b→02c→02d 是同一扩展任务的建议切分，每卡单独定义写集和 fixture，前置为已存在的媒体/图/owner 合同；有多个推理阶段的图由同一媒体执行 owner 编排并逐段结算，不增加第二套调度器。缓存如需引入，键包含完整执行 snapshot/实际 decoder，规定容量、引用和失效，不因命中文件名就返回过期结果。输出可辨识、媒体可播放、取消/恢复和参数一致性均纳入逐行验证。

---

## 12. 分阶段执行包

详细步骤、精确写集、验收、回退与可复制提示词统一放在 [TASK_PACK.md](TASK_PACK.md)，避免两份卡片漂移。实现时依次遵守：

1. V0-01 → V0-01b：当前基线、真实契约模块、构建/架构检查接线。
2. V0-02 → V0-03：DSH 下方“视频”入口、独立 Route、手机模板与可编辑原生节点图。
3. V1-00 与 V1-01：推广既有资源锁域、固定基础 TI2V-5B 资产；独立准备但共享契约由一人整合。
4. V1-02a → V1-02b → V1-03：持久 run/journal 与 process owner → 独立 worker/FGS/AIDL → 真实 JNI/native（P0）。
5. V1-04 → V1-05 → V1-06：编码/原子发布 → 播放与媒体库 → 当前候选版本整合验收。合成帧可提前验证编码，不能替代真实模型。
6. V2-01 → V2-02：API-format parser、模型绑定和支持子图编译。
7. V3-01 → V3-02：显式远程 ComfyUI、逐节点扩展。E-01 的 14B/H3 单独实验。

每卡形成检查点，前置已满足且仍在授权范围内则继续，不逐卡索要用户批准。某个原生/加速条件受阻，只暂停依赖该结果的分支；保留具体证据并推进独立工作，不降低门槛。真正开始改生产代码仍需对应的实施授权，本次文档交付不等于已开始实施。

---

## 13. 测试和验收矩阵

### 13.1 契约与纯逻辑

- graph canonicalization、digest、版本兼容、默认值、seed 和参数边界。
- duplicate/missing/cycle/type mismatch/unsupported node。
- model role/checksum/revision 映射。
- requestId/epoch/lease/stale completion。
- run state transition、终态幂等、cancel race。
- remote prompt/status 对账和 UNKNOWN/RECONCILING。

### 13.2 Room 和文件

- v29 → 新版本迁移保留旧 chat/image/file/Agent 行、索引和 foreign key。（2026-10-28：设备用例 `VideoRoomMigrationTest.migration29To35KeepsChatImageFileRowsAndAddsAgentTables` 已 seed 并以导出 `35.json` 校验 chat/image/file 旧行保留，f9d `OK (3 tests)`。）
- Room transaction 失败、journal 写失败、fsync/rename 失败、空间不足、进程死、DB 有行但文件不存在。
- symlink/path escape/unsafe leaf/duplicate destination/checksum mismatch。
- startup reconcile 幂等，第二次运行不会重复发布或删除有效文件。
- image_assets 回归测试必须保持通过，不能用视频测试替代图片测试。

### 13.3 worker/native/codec

- 单 active、重复提交、binder death、worker 重启、cancel before load、cancel during sample、cancel during encode。
- native load/condition/sample/decode/encode/shutdown 顺序和异常。
- CPU correctness 与 acceleration 分开记录；每种模式至少一份真实输出。
- MediaCodec colorspace/stride/PTS/EOS/Muxer finalize、坏帧、半文件和磁盘满。
- 测 Java heap、native PSS/RSS、峰值磁盘、耗时、温度和重复运行；明确设备、模型、precision、build 和样本数。

### 13.4 UI/设备

- 抽屉顺序、视频 tab、back、状态保持、晚到模型回调。
- 空态、模型缺失、导入诊断、排队、采样、取消、失败、成功播放器。
- compact phone、larger phone、键盘打开、长 prompt、大字体、浅/深色、旋转/后台（若支持）。
- 节点触达、语义 label、文本进度、对比度和系统返回。

### 13.5 验证命令模板

实际模块名以实现后的 settings.gradle 为准，命令不得对不存在的 task 报“通过”：

    python tools/architecture/check_architecture_rules.py

    .\scripts\verify-code.ps1 -Tests '*Video*'

上面的受控脚本已经取得构建锁，不要在它外面重复套同一非重入锁。它已接线 video（`-VideoTests` 覆盖 `:core:video-contract` / `:feature:video-workspace`；changed-file 映射亦包含两模块），但脚本仍不等于全量、设备或 native 验收。新增 Android library 的 JVM 任务通常是 `testDebugUnitTest`，Kotlin JVM 模块通常是 `test`，先用受控的 `tasks --all` 确认。不能用不存在或零匹配的 task 当通过。

需要直接运行设备/模块 Gradle 时，用已有 guard 包裹**该次直接调用**，并检查输入指纹；下面以拟新增的实际仪器测试类为示例，实施时替换成源码中已存在的类，当前不要执行：

```powershell
Import-Module .\tools\build-guards\McaBuildLock.psm1 -Force
. .\tools\build-guards\McaWorktreeFingerprint.ps1
$videoRepoRoot = (Resolve-Path .).Path
$videoBuildLease = Enter-McaBuildLock -Kind test -CommandLine 'video instrumentation'
try {
    $videoInputsBefore = Get-McaWorktreeFingerprint -RepoRoot $videoRepoRoot
    & .\gradlew.bat -PmcaDeviceTestVariant=true :app:connectedDeviceTestAndroidTest `
        '-Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.video.VideoWorkflowDeviceTest'
    $videoGradleExitCode = $LASTEXITCODE
    $videoInputsAfter = Get-McaWorktreeFingerprint -RepoRoot $videoRepoRoot
    if ($videoGradleExitCode -ne 0) { throw 'video instrumentation failed' }
    if ($videoInputsBefore.fingerprint -ne $videoInputsAfter.fingerprint) {
        throw 'input-changed-during-run'
    }
} finally {
    Exit-McaBuildLock -Lock $videoBuildLease
}
```

仪器测试不使用 JVM 的 `--tests` 参数，精确类筛选用 `android.testInstrumentationRunnerArguments.class`。上例优先使用仓库已有的独立 deviceTest 变体，避免 debug 与已装生产包签名冲突。该变体只证明相应测试/UI/迁移范围，生产签名的原位升级、已有数据与真实 MainActivity 视频入口仍须另验；不能卸载/清数据来消除安装冲突。JVM 类筛选才使用 `--tests`；也可把上例 Gradle 参数替换为确认存在的新模块任务。执行后核对本次 XML 的真实测试数量、失败、跳过、退出码与输入绑定；构建成功不等于设备测试非空。完整 APK 构建使用既有 `scripts/build.ps1`、JDK21 和真实 SDK，不并发运行 Gradle/native。聚合 final-gate 会改写报告，本方案阶段不运行它。

每张卡交付证据采用：

    scope:
    start_revision:
    existing_workspace_changes:
    changed_files:
    owner_and_state_writer:
    commands:
    result: PASS | FAIL | NOT_RUN | BLOCKED | NOT_APPLICABLE
    runtime_device_and_model:
    raw_log_or_artifact:
    known_limits:
    rollback_point:

不能把旧 revision 报告、stub、编译或“没有失败但没有执行”写成当前 PASS。

---

## 14. 回滚、兼容和运维

- UI/contract、native、Room、remote 都按独立切片交付；任意阶段失败时保留已验证的入口和媒体读取，按真实后端/资产错误显示诊断，不扩大成其他功能不可用。
- schema 只做向前兼容迁移；旧 APK 不一定能读新数据，发布前必须说明最低回退版本和数据前向修复策略。不要用清库证明回滚。
- native 模型、子模块 patch、CMake 开关和 ABI 是构建输入，必须锁 revision、diff、license 和 checksum。
- 删除或重试使用 run identity；不删除其他 run 的文件，不释放其他任务的 lease。
- 日志默认只写 requestId、runId、stage、error code、model fingerprint 前缀和统计值；提示词、凭据、完整路径和用户文件内容按隐私规则处理。
- 应用升级启动先做 schema migration，再做 media/journal reconcile；reconcile 失败不能阻止聊天和图片主线启动，但视频页要显示可操作的恢复状态。

---

## 15. 可直接交给弱模型的执行提示词

使用 [TASK_PACK.md §8](TASK_PACK.md#8-可复制开工提示词) 中的完整提示词，并附本次要执行的卡片。默认先做 V0-01，禁止上来一次改完所有模块。卡片要求、前置、写集、运行证据与退出条件为执行合同，不能只提交文件统计或自评完成度。

开始实现前先重新确认当前工作区；本方案中的类型/新文件是拟实施落点，不是当前已存在的 API。执行者不得依据文档名称自行假定某模块、测试 task、视频 native 能力或服务端 targeted cancel 已存在。

---

## 16. 最终完成定义

### V1 本地视频可称“完成”的最低证据

- 视频入口在 DSH 下方，MCA 主题和状态矩阵真实渲染通过。
- Wan2.2 TI2V-5B manifest、许可证、revision、checksum 和模型角色有记录。
- 文生和图生各有至少一个真实可辨识短片段；参数、帧数、fps 和尺寸与 snapshot 一致。
- native load、cancel、shutdown、重复运行和 worker death 有真实证据。
- 峰值 Java/native 内存、磁盘、耗时和设备信息已记录；未通过的加速或分辨率明确标出。
- 当前 execution profile 的最小输出规格与资源/耗时/停止/热/重复运行预算已冻结并满足；未满足时只可交付实验状态，不能宣称 V1 产品可用。
- MP4 经 MediaExtractor 校验，Room media row 与 published file 可对应；应用重启可恢复/重试。
- 播放、暂停、seek、导出、分享、删除和坏文件错误有设备测试。
- 现有聊天、图片、DSH、Room v29 旧行和导航回归没有被视频切片破坏。
- 现有能力可用性和错误语义保持；没有静默参数替换、假进度、假取消或本地失败自动远程。

### V2/V3 完成定义

Comfy API 导入只有在固定节点子集 fixture、模型显式绑定、诊断和 canonical digest 通过后才叫支持；远程 ComfyUI 的 submit、WS/queue/history 对账、上传/下载、断线、提交丢响应和取消支持等级分别验收。targeted cancel 只在相应服务端证据齐全后标可用。更多节点逐个证明，不以“ComfyUI 兼容”作为总开关。

---

## 17. 关键参考

- MCA 工程硬锁：docs/architecture/MCA_PROJECT_HARD_LOCK.md
- MCA 导航：app/src/main/java/com/muyuchat/mca/AppTab.kt、MainActivity.kt、MainChatScreenActions.kt
- MCA 侧栏：feature/chat/src/main/java/com/muyuchat/feature/chat/ChatHistoryDrawerScreens.kt
- MCA runtime：core/engine/.../RuntimeSupervisor.kt、BackendRuntimeOwner.kt、app/.../ImageGenerationOwner.kt
- MCA native bridge：core/sd-native/.../NativeStableDiffusionBridge.kt、core/sd-native/src/main/cpp
- MCA Room：app/src/main/java/com/muyuchat/mca/ChatSessionStoreRoomDatabase.kt、ChatSessionStoreEntities.kt
- MCA 文件/journal：app/src/main/java/com/muyuchat/mca/ImageAssetFiles.kt、ImageExecutionJournal.kt
- Wan2.2：https://github.com/Wan-Video/Wan2.2
- ComfyUI：https://github.com/comfyanonymous/ComfyUI
- Comfy API 示例：https://github.com/comfyanonymous/ComfyUI/blob/master/script_examples/websockets_api_example.py
- 参考移动项目固定 commit：https://github.com/AbrahamPaulJ/nightmare-mobile/tree/42163d86e91fc2717a9d3673417d970f2998000d

这份方案完成的是可执行的工程设计和验收边界，不代表代码、native 视频能力、模型下载、设备性能或 ComfyUI 后端已经实现。

## 18. 本次方案交付的检查范围

| 项目 | 本次结果与边界 |
|---|---|
| 原聊天交接 | 已恢复最后产品需求，按“视频原生工作流方案”继续；未把最初总体评级标题当作最终任务 |
| 源码/架构复核 | 已核查导航、进程根、资源锁、Room、sd-native ABI、Wan组件/取消/解码与构建入口，并完成两轮独立审查修正 |
| 参考来源 | 已核对正确 Git commit、公开实现与许可差异；未运行参考 APK，也未沿用作者性能数字 |
| 文档检查 | 已检查三份文档的本地链接、代码围栏、任务依赖和命令适用范围；新增/修改仅本方案目录的 Markdown |
| 构建、模型与真机 | NOT_RUN；本次不构建、不下载权重、不运行 native，不把方案写成产品能力通过 |
| 交付状态 | 设计与任务包已完成，待获得实现授权后从 V0-01 开始；无提交、推送或发布 |


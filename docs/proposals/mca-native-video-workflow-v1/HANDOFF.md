# MCA 原生视频工作流：交接文档（收尾轮）

交接日期：2026-10-26（13:45 收尾轮更新）。交接人：视频工作流实施/验收会话（下称“本会话”）。
适用对象：接手设备/模型验收、V1-06 产品验收与后续维护的 Agent / 维护者。

**接手第一动作**：按顺序读 `AGENTS.md`（本地指令）→ `docs/architecture/MCA_PROJECT_HARD_LOCK.md` → 本文档 → [EXECUTION_PLAN.md](EXECUTION_PLAN.md) → [TASK_PACK.md](TASK_PACK.md) → [IMPLEMENTATION_LOG.md](IMPLEMENTATION_LOG.md)（唯一逐卡原始证据）→ [MODEL_ASSET_SOURCES.md](MODEL_ASSET_SOURCES.md)。然后重新核对 HEAD/工作区/子模块（本仓库是共享工作区，数字随时在变）。

> **2026-10-26 夜 round-3 更新（优先于下方 §0 的 13:45 快照）**：本轮按当前树重建了产物并复跑真实设备/模型路径。完成：运行卡进度残留缺陷修复（真机无 825/1263 提前采样）、真机 UI 测试更新弹窗 flake 修复、发现并修复“终态任务无重试入口”的 V1 缺口；f9d 仪器化 run13/run14 均 OK(9)；真实 T2V 228–229s/7.96GB/MP4 sha `1209881e…`；I2V 247–249s/8.68GB/MP4 sha `c5defe01…`（imageRef 已绑定；最小规格帧近白，**语义质量 NOT_MET**）；取消（采样阶段 UI 观察 6s、DB 事件 1.57s；TEXT_ENCODE 149s）；进程死亡→INTERRUPTED/UNCONFIRMED（INTERRUPTED_PROCESS_DEATH）；播放/分享/另存（导出 sha 一致）/删除（私有 media 行/文件移除、导出保留，最终 4 行/4 文件）。仍：产品预算/V1-06 **NOT_MET**、聊天/DSH/图片仲裁本轮 **NOT_RUN**、app 视频过滤 JVM 被并发 lane 半成品测试阻断（并发全量跑 21:55 的 135/0 XML 仅作 incidental）、远端 **BLOCKED**、加速 FAIL/BLOCKED。逐项证据见 [IMPLEMENTATION_LOG.md](IMPLEMENTATION_LOG.md “接手补齐轮（2026-10-26 夜，round-3）”) 与 `build/evidence/v1-06/round3-*`。

> **2026-10-26 深夜 round-4 更新**：① 当前树 JVM 补齐：**app 视频+policy 140/0（16 suites）**、feature workspace **32/0**；app 跑用**仅本次生效**的 init script 排除并发 lane 不可编译的未跟踪 `VideoSchemaSqlTest.kt`（未编辑/删除，其 2 测试仍 NOT_RUN）。② 无障碍修复并上机：端口语义带节点名、节点列表按钮显式 desc、抽屉“图片”label；新增守卫测试；uiautomator 原始无标签 clickable 11→9（剩余为 Material 包装节点，TalkBack 实读未验证；端口仍 22dp）。产物 C `e337ba0e…`（568,213,041B，22:29）装 f9d（lastUpdate 22:31:06），run15 **OK(9)**。③ 真实图片引擎导入成功（Anima Preview，5.24GB，经生产 UI），但生产“生成图片”点击未产生任务（`image_assets=0`、无 worker）→ **image×video 真实仲裁 NOT_RUN/BLOCKED**；debug 直调路径绕过 gate，未采用。④ 98a 被外部 lane 换装 `4ef1a121…` 并占用 DSH/chat → 本会话聊天/DSH **NOT_RUN**（0 发送/0 交互，仅只读旁证）。⑤ 产品预算/V1-06 仍 **NOT_MET**，V1 未完成。证据见 IMPLEMENTATION_LOG “接手补齐轮 2（2026-10-26 深夜，round-4）”、`round4-*`。

> **2026-10-27 round-5 严格验收续作（最新，优先于以上所有快照）**：
> - **设备/APK 一致性**：f9d 上已装 `com.muyuchat.mca.devicetest` base/test APK 与产物 C 字节一致（base sha `e337ba0e…`，test `b66dfd77…`）；可直接 `am instrument`，无需 Gradle 重装。
> - **本轮新证据**：直接 adb 跑 4 个非 UI 类 → **OK (8 tests)**，3.079s（编码/发布/删除、29→32、30→32、JNI 边界）；证据 `build/evidence/v1-06/audit-nonui-instrument.txt`。
> - **当前设备锁屏**（`mDreamingLockscreen=true`，安全锁），UI/真实模型 E2E 复跑 **BLOCKED**；round-4 的 run15 OK(9) 与真实出片仍是历史证据，不能当作本轮当前树结果。
> - **gate 真实缺陷（本轮发现并修复）**：`LocalVideoWorkerService` 的 `withLease` 是空块，`VideoWorkerCore` 在 `acquireGate` 返回后才执行 pipeline → **round-3/4 的 T2V/I2V 出片实际未持有共享物理锁**。已改为 `withGate { runExecutor }` 并用真实 `NativeMediaExecutionGate.withLease { block() }` 包住执行体；新增回归测试“executor runs inside the shared gate lease”。修复后尚未在真实 64×64 链路复跑，故“gate 修复已验证”仍 **NOT_RUN**。
> - **产品路线结论**：移动端产品预算 **NOT_MET**；CPU 64×64×9×1 仅实验能力（243–345s，峰值 8.5–9.1GB，语义 FAIL）；480×832×33×1 22.95min/0 step 被 watchdog 终止，20 步停在 0/20；128×128×9×4 **NOT_RUN**（锁屏）。OpenCL SIGABRT、Vulkan SIGSEGV、QNN 缺 Wan ONNX/context，均 **FAIL/BLOCKED**。`measuredPresets` 仍为空，不得写入未测预算。
> - **UI 修复（代码已改，未编译/未复验）**：端口命中区 48×48dp（行高/连线/自动布局同步）、节点列表可滚动、摘要不再 18 字符硬截断（全文进 semantics、视觉 2 行省略）、节点 focusable/Enter/Space、端口 clickable+Role、selected/stateDescription/liveRegion、模式与播放器状态描述、页面新增“本机 CPU 为实验能力”说明。
> - **VideoSchemaSqlTest**：排除机制在 `build/round4-apptest-init.gradle(.kts)`（仅历史调用）；本轮**不再使用该 init**，定向 `:app:testDebugUnitTest --tests ... --rerun` 已真实执行 **2/2 PASS**（1.45s，`build/evidence/v1-06/round5-schema-result.xml`）。（**2026-10-28 更正**：该文件现为 3 个用例，当前树 3/3 PASS，XML 见 `build/evidence/v1-07/jvm-test-xml/`；旧“2 测试 / 未编译 / NOT_RUN”的 caveat 作废。）测试/helpers/schemas 28–32 仍为 untracked（fresh clone 风险）。
> - **round-5 已完成**：当前树 UI 强制编译 PASS（43s）；deviceTest 基座+测试 APK 编译/打包 PASS；`VideoSchemaSqlTest` 无 init 排除真实 **2/2 PASS**（1.45s，XML 已复制）；新基座安装后非 UI 4 类 **OK(8)**。当前产物：base `74505395…`（451,283,360B）、test `9d24d8bf…`（1,653,258B），f9d 安装于 01:49:36。
> - **新增 opt-in headless 探针**（`VideoNativeBudgetDeviceTest`，`-e videoBudgetProbe true`，明确 non-product probe）：首次 64×64 运行因探针 payload 漏写 native 必填 `ditPath/encoderPath/vaePath` 而 `MODEL_MISSING`（15.1s，非产品模型失败）；已修复，待重建 test APK 后重跑 64×64 与 128×128×9×4。
> - **待办**：解锁设备 → 安装修复后的 test APK → 重跑 6 个视频 instrument 类（含 UI/播放）→ 探针跑 64×64（gate/媒体）与 128×128×9×4（预算/语义）→ TalkBack/两轮视觉 → 图片×视频真实仲裁（依赖图片任务 RUNNING）。远程 ComfyUI 仍 **BLOCKED**。

> **2026-10-28 v1-07 round-6 更新（最新，优先于以上所有快照；证据目录 `build/evidence/v1-07/`，逐项见 IMPLEMENTATION_LOG 同名分节）**：
> - **当前树已重建并安装**：base `a2b3577d…`（565,736,181B @02:34:28）+ androidTest `ed1c16b3…`（1,698,308B @02:57:34）；f9d 02:38:08 / 02:57:44 安装，设备侧 `sha256sum` 与构建物字节一致。98a 仍是被外部 lane 于 01:23 安装的 `736bcc7d…`（非本轮产物，相关结论只对该 sha 成立）。
> - **JVM（当前树，真实执行）**：contract **45/0**、feature workspace **33/0**、app 视频+policy **161/0（18 suites）**、gate 补充 **73/0**（含 `NativeMediaExecutionGateConcurrencyTest` 4）、`HuggingFaceTransferUrlTest` **3/3**、迁移相关 **71/0（14 suites）**、架构 **19/19**。**旧 “app 视频+policy 140/0” 是 round-4 旧集合（当时排除 schema 测试、缺 10 个 owner 用例），已作废**；`VideoSchemaSqlTest` 现为 **3 个用例 3/3 PASS**，旧 “2/2、未编译、NOT_RUN” 作废。一次 app 运行用仅本次生效的 init 排除另一 lane 不可编译的 `DshPromptedToolProtocolTest.kt`（该文件用例 **NOT_RUN**，未编辑未删除）。
> - **Room**：DB 为 **version 35**（视频 29–32；Agent park_tokens 32–33、dispatch_outbox 33–34、workflow_checkpoints 34–35）。JVM 29→35 链 + 各边 + `35.json` 结构比对 0 失败；**设备 MigrationTestHelper 新增 v29→v35 用例，OK (3 tests, 0.69s)**，含 chat/image/file 旧行保留断言。
> - **gate 修复已在真机验证**：worker 运行中 `/proc/<pid>/fd` 持有 `native_image_execution.lock`，外部 `fcntl` 探针 `BUSY`→运行结束 `ACQUIRED`。位置纠正：adapter 只取聊天租约并 bind，物理锁在 `:local_video` 的 `VideoWorkerCore.withGate → NativeMediaExecutionGate.withLease` 内包住整个执行体（含 JNI/编码）；20 分钟无进度看门狗在 `LocalVideoWorkerAdapter`（`PROGRESS_TIMEOUT_MS`），不在 `VideoWorkerCore`。
> - **真实设备（f9d，生产 UI）**：T2V 64×64×9×1 208.2s / 峰值 7.24GB / MP4 sha `0094df57…`；I2V（真实图片 `content://…/mca-i2v-redball.png`）244.1s / 7.51GB / sha `34ff411d…`（首帧可见红球、后帧近白）；**锁等待中取消 131ms，且从未出现任何 native 阶段事件**；重试同 revision、进程死亡→INTERRUPTED/UNCONFIRMED、播放/分享/另存/删除全部 PASS。
> - **真实三件套下载 PASS**：完全由视频页驱动，19m25s / 10,481,555,776B；暂停→续传（从 1.15GB 处继续，未从 0 重来）、断网→`DOWNLOAD_FAILED`（保留 2.08GB `.part`）→恢复后继续；设备侧三文件 SHA-256 全等 pin；READY 后真实 T2V（c2）245.2s / 2,870B / sha `75cebb06…`，证明下载字节可被 native 载入并发布。
> - **资产来源与预检**：`resolve/main` → 固定 commit（`57437632…` / `b535255b…`，HF API 2026-10-28 复核，main 当时等于它们）；传输主机改用应用既有 HF 端点策略（设备实测无法直连 `huggingface.co`，可直连 `hf-mirror.com` ≈10MB/s）；磁盘预检改为只要求“缺失尾部 + 256MiB 保留余量”，续传不再被误拒。
> - **98a**：视频页 UI 与浅/深色 PASS；端口命中区 8/8 = 48dp、摘要不再 18 字符截断（round-5 修复成立）；**TalkBack 可听朗读 NOT_RUN**（厂商 TTS 协议+教程遮罩，未代用户同意）；聊天真实回复 PASS（“1+1 equals 2.”）；图片 NOT_RUN（无生图引擎，UI 真空态）；DSH 启动 PASS；远端 ComfyUI **BLOCKED**（8188/8189/8000 无监听，UI 如实 `REMOTE_TIMEOUT`）。
> - **仍未完成**：产品规格画质与预算 **NOT_MET**（64×64×9×1 无可用语义短片；480×832×33×1 在当前树 warm 实测 **1375.4s / 7.15GB 峰值后被 20 分钟无进度看门狗终止 → INTERRUPTED、无媒体**，冷却后复测按单步成本比 (480·832·33)/(64·64·9)=357.5×（单步 ≈5.8–7.5h）判定 NOT_RUN，详见 IMPLEMENTATION_LOG §7），**V1 未完成**，`measuredPresets` 仍为空；图片×视频真实仲裁 **BLOCKED**（两机均无生图引擎）。未提交/未推送/未发布。

> **2026-10-28 v1-08 并行验证轮（在 round-6 之后；证据 `build/evidence/v1-08/`，逐项见 IMPLEMENTATION_LOG 同名分节）**：
> - **无障碍（源码已改 + 98a 真机验证）**：视频页无标签 clickable **18 → 0**；端口命中区 **8/8 = 48.0×48.0dp**（DiT 输出端口 29.8×48→48×48），可见圆点/连线几何不变；节点列表 sheet 11 → 1（仅 Material3 拖拽条）。`feature:video-workspace` JVM **36/0/0/0**，守卫测试经两次变异证明非恒真；浅色同状态与 v1-07 截图 **0 内容像素差异**。产物 `24465f54…`（570,526,159B）04:56:55 装 98a；**但 05:40 外部 lane 已把它换成 `c2bdfec8…`，该证据只对 `24465f54…` 成立**。TalkBack **逐控件可听朗读仍 NOT_RUN**（adb 无法稳定驱动该 MIUI TalkBack 焦点；TTS 通路已证实发声）。
> - **图片任务“死按钮”根因（只读）**：98a 的“跳到模型管理”是设计内空态守卫；f9d 的具体闸门 **NOT_PROVEN**，但已证 6 个真实缺陷，核心是图片页状态出口 `GuidanceNotice` 对未识别文案 `?: return` 整块不渲染（`ImageWorkspaceScreens.kt:568` + `UserGuidance.kt:82`），使 `ImageGenerationOwner.submit()` 至少 5 类拒绝在页面上 **0 像素可见**；另有 false 无兜底、回调静默默认 `{_,_->false}`、14 处早退零日志、失败在 job 非 active 时丢弃、画廊不渲染 jobs。**F1/F2/F3 已修并验证**：图片页未识别状态文案改走 `ComposerStatusMessage`（全文进 semantics）、false 分支兜底、15 处准入早退加结构化日志；`feature:chat` **491/0/0/0**（65 suites，守卫测试变异证明非恒真）；APK `56dfee91…` 装 98a（11:42:29）；用“缺主文件的合成记录”在真机证明 readiness 拒绝现在**可见**（`本地图像生成模型主文件不存在…`），fixture 已完全回滚。F4 未做（他 lane 测试依赖）。**新发现**：`family=SD15` 半注册记录会让启动崩溃（`UltraFix UI dimensions must expose a bounded executable range`，`ChatModelChoice.<init>`），建议后续在 `ChatUiStateMapping/ChatModelChoice` 做有界校验降级。image×video 真实仲裁仍 **BLOCKED**（无真实生图任务）。
> - **候选规格矩阵**：04:12–10:06 f9d 安全锁屏导致 UI 驱动测量 **NOT_RUN (BLOCKED: secure keyguard)**（`spec-matrix.md`）；10:04 一次解锁被代理误重置 `stay_on_while_plugged_in` 又锁上（已记录，零 run 提交），10:22 用户再解锁、Lead 设 `screen_off_timeout=1800000`+`svc power stayon true` 后完成首批真实测量：**128×128×9×4 生产链 446.5s / 采样 342.0s（85.5s/step）/ 峰值 8.08GB / 39.6°C / MP4 sha `4b726449…`，抽帧无可辨识主体（NOT_MET）**；同几何 headless 探针 775.9s / 7.07GB / sha `ed670b70…` 亦 NOT_MET。**64×64×9×20（默认步数）生产链 799.6s / 32.2s/step / 7.98GB / 40.6°C / sha `bbb31588…`，抽帧仍无可辨识主体 → 提高像素与步数都不解决语义，主因是几何/分布（条件链缺陷未排除）**。成本口径更正：按 128² 稳态 36–40s × 89.375 倍 token，480×832×33 单步 ≈53–60min（早前 5.8–7.5h 系用首步 58s 外推，已更正），20 步 ≈18–20h，看门狗结论不变。**480×832×9×1 已实测（判定实验）**：单步采样 **553.2s（9.2min，看门狗内）**，随后 **VAE 解码 19.9min 零进度 → 被 20 分钟看门狗杀成 INTERRUPTED/UNCONFIRMED（`INTERRUPTED_PROCESS_DEATH`）、无媒体**（峰值 8.17GB、41.6°C、无 LMK）。结论改写：**产品几何是“解码+看门狗”双约束**——近原生采样 CPU 可跑，瓶颈在无进度上报的 CPU 解码（33 帧单步 ≈33.8min 采样、解码 ≈74min），因此 480×832×33 在当前看门狗下连一步都发布不出。候选窄修复：解码进度回调 / 分阶段预算 / 分块解码 / VAE 上加速器（需独立评审，不得顺手改预算）。**`256×256×9×1` 已完成**：总 1041.4s（采样 4.45min + 解码 ≈10min）、峰值 8.49GB、41.2°C、MP4 65,015B sha `dd6527f7…`，抽帧为均匀噪声（1 步，无主体）。因此解码+看门狗约束的触发点介于 256×256×9（可完成）与 480×832×9（解码 >19.9min 被杀）之间。**语义未解**：1 步在 64/128/256 均为噪声/色场，64×64×20 步仍无主体；**`256×256×9×4` 已完成（首个 >64² 多步成片）**：1425.7s（采样 4×114.1s=456.2s、解码 13.3min，均 <20min 看门狗）、峰值 8.61GB、41.7°C、MP4 61,014B sha `6412fc8d…`；抽帧为抽象迷彩/条纹纹理，**无红球、无桌子、无连贯物体 → 语义 NOT_MET**。即 4 步 + 256² 仍不恢复主体，最可疑的是**文本条件是否真正进入 DiT**；**A/B 已判定：条件链缺陷成立** —— 固定 seed 42、两个截然不同的 prompt（红球 / 夜间跑车）在 64×64×9×4 下输出**像素完全相同**（frame sha 相同、meanAbsDiff 0.000；MP4 仅 creation_time 12 字节不同）⇒ **文本条件没有进入 DiT**，这解释全部“无主体”结果。另发现 **UI 种子字段缺陷**：首次提交忽略种子（`VideoGenerationOwner.kt:177-178`，`seedOverride` 只在重试时提供），UI 显示 20261028 而实际 seed 随机 4111864769242043674。证据 `build/evidence/v1-08/device-ab-ANALYSIS.txt`。**在条件链修复并通过 A/B 前，不做预算冻结、不得据此宣称模型不可用**。**种子缺陷已修**（`VideoGenerationOwner.draftSeedOf`：override → draft seed≠0 固定 → 否则随机；OwnerTest 15/15 PASS）。**条件链根因分析完成**（`build/evidence/v1-09/conditioning-rootcause.md`）：prompt 确实到达 native、T5 确实装载（否则会崩而非静默）、UMT5 词表合法；阻塞定位的是两处盲区——**F-3 视频路径把 sd.cpp 日志全关**、**F-5 从不校验 prompt 哈希**。**FIX-A（sd.cpp 日志接回 logcat + 模型/prompt 指纹证据行，`:core:sd-native:assembleDebug` PASS）与 FIX-B（执行前 `promptMatchesSnapshot` 校验，`PromptIntegrityTest` 4/4 PASS）已落盘**；**2026-10-29 v1-13 VAE 分块解码实测**：wrapper 新增可选 payload aeTiling/tileSizeX/tileSizeY/tileOverlap（默认关闭，生产不发送），native 回显 ae_tiling=off|512x288@0.5|32x32@0.5 证明键贯通；64×64×9×1 对照显示 **decode 44.18s → 50.20s（+13.6%，tiling 在小几何更慢）**，但帧 0/4/8 **像素完全相同（PSNR=∞，无接缝无噪点）**；**480×832×9×1 的基线/tiled 均被另一条 lane 的 m force-stop（进程 30657/32642/8273/10204/11070 → signal 9）打断，无可信解码数据**。结论：tiling 保持默认关闭，待干净 480×832 窗口复测。**参考项目对标**（nightmare-mobile）：Wan 2.2 在 Hexagon NPU 上 832×480/5s <3 分钟，靠 lightx2v 4-step 蒸馏 + tile 解码 ⇒ 我们 CPU 20 步规格在数量级上不可能达标，产品预算必须走 NPU + 蒸馏权重。**仍在阻塞**：480×832 干净测量（需停外部 lane 或解锁 98a iOS 窗口）、4-step 蒸馏权重是否引入（需用户批准并更新 pin）、NPU 通道立项。**2026-10-29 换机交叉验证（98a, 25091RP04C / SM8750P / Android 17）**：经视频页重新下载三件套 9,982.85MB（三 sha 全部验证通过，保留为第二个受信资产位置）；5D 堆叠修复在 98a 真机生效（`stacked 5D shape (2,2,1,48,3072) -> (2,2,1,147456)`、识别 `Wan 2.2 TI2V`、无 `invalid number of dimensions`/`MODEL_LOAD_FAILED`、`memory-mapped 620 tensors / 9982.85MB`）；tokenizer `status=0 pieces=256300`。A/B（64×64×9×4 seed 42）与 f9d **逐位一致**（meanAbsDiff 24.291/28.642/40.373，PSNR 19.962dB）⇒ CPU 路径确定性。**语义门禁仍未通过**：两段视频都是蓝紫抽象条纹，无可辨识红球/跑车。`memory-mapped`(620 个量化权重) 与 `total params 10.62MB`(非 mmap 参数缓冲) 是不同口径，不矛盾。以下为旧记录：**条件链根因已修复并验证（2026-10-28 18:2x）**：Android 上 `load_umt5_tokenizer_json()` 曾返回空串导致 `status=5 INVLIAD_JSON`、prompt 0 token；修复后 `status=0 pieces=256300`、prompt 切成 5/9 token，同 seed 双 prompt 输出**像素实质不同**（meanAbsDiff 24.3/28.6/40.4，PSNR 19.96 dB）⇒ **条件链已生效，但 64×64×9×4 画面仍为抽象色带，不构成产品通过**；`patch_embedding 5D` 与 `diffusion_model 10.30MB` 权重装载异常仍待修。旧记录：：模型识别正确（Wan 2.2 TI2V、非 distilled）、prompt 到达 sd.cpp，但 **UMT5 tokenizer 把 prompt 切成 0 个 token**（`t5_unigram_tokenizer.cpp:336 - split prompt "red ball on blue table" to tokens []`），T5 仍用 65.7s 计算空序列条件图 ⇒ cond 与 prompt 无关 ⇒ 输出像素相同。**根因 = tokenizer 返回空 token，不是条件权重/交叉注意力/量化/分辨率**；同时排除 T5 未装载、cross-attn 权重缺失（GGUF 300 个 cross 张量齐全）、48 通道接线、词表 JSON 损坏、`enc.blk.*` 前缀不兼容（`name_conversion.cpp` 有映射）。下一步：在 tokenizer init 后加 `status_/pieces/trie_results_size_` 诊断（走 FIX-A 日志）或主机复现 `encode("▁red▁ball…")`，按 `INVLIAD_JSON/NO_PIECES_LOADED/BUILD_DOUBLE_ARRAY_FAILED/NO_ENTRY_FOUND` 具体状态修复。**这是当前唯一阻塞语义验收的缺陷，优先级高于性能/预算/NPU。****算术更正**：单步成本比 `(480·832·33)/(64·64·9)=357.5×`（旧文档 35.7× 差 10 倍），480×832×33 CPU 单步 ≈**5.8–7.5h**；看门狗结论不变。`measuredPresets` 仍为空，未冻结任何规格。
> - **输入指纹/可复现**：指纹 S1 `009ca166…`（117 文件）→ S2 `84122b71…`（118 文件，04:29，期间其他 lane 仍在写 UI，故**引用须带时间戳**）；fresh clone 需 **144 个未跟踪文件 / 2,321,988B** + 369 M + 3 D（只出清单，提交未授权）；`.dsh-tmp/`(52.6MB) 与 `tmp_bing.html` 未忽略，`git add -A` 会误带。证据索引 172 条路径全部实存。**保护审计 PASS**（两文件仍在 `.git/info/exclude`、未被跟踪、HEAD 未变、staged 0）。
> - **文档偏差（未伪造一致）**：“当前树 = a2b3577d APK”已不成立（MainActivity 04:10 / MainViewModel 04:03 / 11 个 video UI 04:24–04:27 在该构建之后改写；98a 又于 05:40 被换装 `c2bdfec8…`）；03:56 有一次未记录的 debug 构建；v1-07 漂移审计的 D08/D13 已被真机证据取代；未跟踪死副本 `app/main/java/.../Wan22Ti2v5bProfiles.kt` 仍在。

---

## 0. 一页速览（截至本次交接）

> 注：本节是 **2026-10-26 交接快照**；2026-10-28 的当前状态、产物与计数见文首 **round-6 / v1-08 更新**（优先）。

| 项 | 状态 |
| --- | --- |
| 卡片进度 | V0-01/V0-01b/V0-02/V0-03/V1-00/V1-01/V1-02a/V1-02b/V1-03/V1-04/V1-05/V2-01/V2-02/V3-01 **代码已实现**；**V1-06 未通过**（产品预算 NOT_MET；UI/视觉/无障碍/两轮 QA 子集 PASS） |
| 接手轮修复（2026-10-26） | 视频入口空回调、编辑器不重组、运行无取消、模型 pin 取自 xetHash 四个真实缺陷已修；视频页改为 ComfyUI 式节点画布；收尾轮再修端口行/点位/语义（节点宽 260dp、每端口一行、端口可被 TalkBack 激活）；详见 IMPLEMENTATION_LOG“验收收尾轮” |
| 最终构建 | **历史产物 C（round-4）**：`app-deviceTest.apk` 568,213,041B @22:29:29，sha256 `e337ba0ef33acff2c7e3be9ae0422a49907fb88d8cf9f6a906ec94af707ef1da`；f9d lastUpdateTime **22:31:06**，run15 **OK (9 tests)**。**round-5 已改 UI/gate 源码，产物 C 不再代表当前树；新产物待构建/安装/复跑**。产物 B/C 历史证据保留 |
| JVM 测试 | **round-4 快照（22:17）**：contract 45/0、workspace **32/0**（含 retry+a11y 守卫）、chat 486/0、架构 19/19；**app 视频+policy 140/0（16 suites）**——用仅本次生效的 init script 排除了 `VideoSchemaSqlTest.kt`（其 2 测试 NOT_RUN）。**round-5 已改 UI/gate 与测试，须重跑后才有当前树结论；不得引用 140/0 作为当前树证据** |
| 真机首轮（保留） | 25091RP04C：9 tests / **3 PASS / 6 FAIL**（真实缺陷，未改写；原始证据在 `%TEMP%\mca-video-device3.log`@03:08） |
| 真机修复后复跑 | **历史 PASS**：run12/run13/run14/run15 OK(9)。**round-5 新证据：非 UI 4 类 OK(8 tests)，3.079s**（`audit-nonui-instrument.txt`）；设备当前安全锁屏，**UI/真实模型复跑 BLOCKED** |
| 模型资产 | **PASS**：官方 LFS sha256 修正；VAE/DiT/UMT5 已下载校验并放入 f9d；真实 worker 通过 manifest 校验并出片 |
| 真实 native 出片 | **流程/媒体 PASS（最小规格），语义/产品 NOT_MET；且 round-3/4 出片未持 gate**：T2V 64×64×9×1 228–292s/峰值 7.96–8.51GB/9 帧；I2V 247–289s/8.57–8.68GB；取消/重试/死亡/播放/分享/导出/删除有真实证据。**round-5 发现 `LocalVideoWorkerService` 的 lease 为空块，实际 JNI 未持共享锁；已修复并加回归测试，但修复后真实出片尚未复跑（NOT_RUN）**。64×64 帧无可辨识“红球/木桌”，语义 FAIL；480×832×33 22.95min/0 step 被 watchdog 终止 |
| 加速尝试（OpenCL） | **FAIL（运行时 native abort）**：QNN 3.0 headers + 设备 public libOpenCL（不打包）构建成功、9/9 测试通过；64×64×9×1 首个 Wan 采样步在 `ggml_backend_opencl_graph_compute` 内 `ggml_abort`（APP CRASH NATIVE，signal 6，187s，峰值 5.37GB，4.2GB swap），无媒体。已回退 CPU；详见 IMPLEMENTATION_LOG“加速尝试轮” |
| 加速尝试（Vulkan） | **FAIL（运行时 SIGSEGV）**：NDK glslc + Khronos Vulkan-Hpp/SPIR-V + MSVC host shader-gen 构建成功、9/9 测试通过；64×64×9×1 首个 Wan matmul 在 Adreno 驱动 `vkCmdBindPipeline` 崩溃（`ggml_vk_mul_mat_q_f16`，SIGSEGV，pid 26752，16:29:49），无媒体。已回退 CPU；详见 IMPLEMENTATION_LOG“加速尝试轮：Vulkan” |
| QNN/NPU 加速 | **BLOCKED（资产/转换链缺口）** | QNN SDK/转换器/HTP V73/V79 在；但工作区只有 Wan GGUF，无原始 safetensors/ONNX、无 Wan QNN context/model 包；现有 QNN 仅 SD1.5 图像。需先完成 Wan→ONNX→QNN 转换、HTP op/context/内存 spike，详见 `QNN_FEASIBILITY_AUDIT.md` |
| 媒体动作 | **PASS（本轮产物 A 真机复测）**：播放器打开且进度推进；MIUI 分享面板；另存到 Download 后 sha 与源片一致 `1209881e…`；删除协议移除私有 media 行/文件（最终 DB 4 行/目录 4 文件，两个 round-3 最小规格副本均移除），已导出副本保留；计数刷新一次滞后已注明。历史导出 sha `d0807293…` 保留 |
| 取消/重试/重复/恢复 | **PASS（有阶段延迟）**：采样阶段取消 6s CANCEL_REQUESTED→CANCELLED/RELEASED；TEXT_ENCODE 阶段取消 149s 后确认（不可中断阶段）；**重试入口本轮发现缺失并已修复**，产物 B 真机复测：CANCELLED 卡→新 run/attempt 同 revision、原终态不变，复测 run 取消后 CANCELLED/RELEASED；进程死亡外部强杀→重进页面 INTERRUPTED/UNCONFIRMED（`INTERRUPTED_PROCESS_DEATH`）；连续多次运行均释放 |
| V1-06 | **部分 PASS / 产品预算 NOT_MET / 当前树未复验**：round-4 有浅/深色、长文本、键盘、无障碍语义与两轮视觉证据；**round-5 已修端口 48dp、节点列表滚动、摘要全文语义（原先 22dp/截断）**，但尚未编译/上机/TalkBack 复验。产品预算 NOT_MET（CPU 默认规格小时级、峰值 8.3–9.5GB；OpenCL/Vulkan 均崩溃；QNN 缺资产） |
| 真实远端 ComfyUI | **BLOCKED**：无固定 revision/endpoint（8188/8189/8000 无监听，无服务端） |
| 提交/推送/发布 | **无**（未授权；全部改动在工作区） |

> 一句话结论：**真实模型最小规格出片（T2V/I2V/重复/取消/播放/分享/导出/删除）与两机设备闭环已通过；OpenCL 与 Vulkan 两个 GPU 后端均在同一 Wan 首个采样 matmul 处崩溃（OpenCL `ggml_abort`、Vulkan Adreno 驱动 SIGSEGV），已回退 CPU；默认 480×832×33×20 仍受 CPU/20 分钟 watchdog 限制、产品预算/V1-06 未满足，因此 V1 仍未完成，不能宣称移动端产品可用。**

---

## 1. 基线与环境（接手时必须重新核对，不得照抄本表）

- **HEAD**：`68b6eef1e67870b02bfef6afda2e625d36663da9`，分支 `codex/enterprise-refactor`（本会话全程未提交）。
- **工作区**：交接时 `git status --porcelain` 约 **665 条**（round-3 复核时 **685**，M 369 / ?? 313 / D 3，staged 0），绝大多数是他人/此前的既有修改；本会话写集见 §3，全部可识别。
- **子模块**：llama.cpp `4ceb1719`（20 脏文件）、stable-diffusion.cpp `16b3846`（18 脏文件）、tokenizers-cpp `acbdc5a`（5 脏文件）；sd.cpp 的嵌套子模块（frontend/libwebm/libwebp）显示 `-` 未初始化。内部补丁是构建输入，改动前先完整记录 diff。
- **工具链**：JDK21（`JAVA_HOME=E:\model\toolchains\jdk-21\...`，PATH 默认 java 是 17）；Android SDK `D:\model\android-sdk`；QNN SDK `E:\tools\qairt-sdk\2.45.0.260326`（裸调 gradlew 必须自设 `MCA_QNN_SDK_ROOT`）。
- **Android sd-native CMake**：SD_WEBP/SD_WEBM/SD_VULKAN/GGML_VULKAN 全部 FORCE OFF——只有 CPU 路径；任何加速宣传都需 P0-D 证据。
- **本地专用文件**：`AGENTS.md` 与 `app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt` 仍在 `.git/info/exclude`（本会话已复核）；不得 stage/commit/push。

## 2. 本会话遇到的环境现实（接手者必读）

1. **共享工作区有并行 Agent**：构建锁可能被长期持有；测试报告目录会被对方运行清掉；本会话独占文件曾被外部删除后重写。应对：一切 Gradle 经 `McaBuildLock`（Enter 长重试窗口）；证据读完立刻摘录；关键文件被删先确认不在冲突写集再恢复。
2. **PowerShell 包装长 Gradle 可能挂起不吐 stdout**：编译结果读重定向日志（`$env:TEMP\mca-*.log`，UTF-16LE），后台跑 + 轮询 + 收尾检查退出码/日志文本。
3. **日志 UTF-16LE 且会折行**：提取 `e: file` 错误需按编码读取并合并折行。
4. **app/build 句柄残留**偶发 `Unable to delete kotlin-classes`：重试；必要时在**自己持锁期间** `gradlew --stop`。
5. **验收纪律**：不得用旧 XML、零执行测试、fake 结果或“代码已修”替代真机 PASS；首轮 FAIL 必须原样保留。
6. **修复可能被工作区覆盖**：终审时发现 4 处已记录修复从文件中消失（`MainViewModel` alias、lease 可空、错误 import、`lastStep` Long），重新修复后才编译通过。接手后若看到“文档说修过但编译报旧错”，先按当前文件重查，不要相信旧结论；终审证据见 `build/manual-final-compile2.log`、`manual-final-tests{1,2,3}.log`、`manual-final-native.log`。

## 3. 已交付内容（按卡；调用链与证据）

### 3.1 入口与工作区 UI（V0-02/V0-03/V1-05）

- `app/.../AppTab.kt`：`VIDEO_WORKSPACE("视频")`，位于 `DSH_WORKSPACE` 与 `CHAT` 之间；`tabAfterLateModelLoad` 保持 DSH 行为不变并让视频同权。
- `MainActivity.kt` / `MainChatScreenActions.kt` / `MainViewModel.kt` / `MainRuntimeAssembly.kt`：视频页 CrossfadeTabPage、port/importPort/remoteProbePort 窄访问器。
- `feature/video-workspace/`：
  - `VideoWorkspaceScreen/Route/Presentation`：简化链 + 高级节点画布（缩放/拖动/端口点选）+ 可访问节点列表；所有按钮只发 intent。
  - `editor/VideoGraphEditorState.kt`：新草稿、全量校验、连线前置拒绝、有界 undo；`VideoDraftCodec.kt` 保存 UI 位置、导入 raw API JSON（`R`）与 metadata（`M`）。
  - `VideoNodeSheet.kt`：typed 参数编辑、定点小数、删除影响预览、断开连线。
  - `VideoPlayerDialog.kt`：平台 `VideoView` 播放/暂停/seek/错误重试，`ON_STOP`/dispose 释放。
  - `VideoLibraryOverlay/Components/MediaActions`：作品库、缩略图、分享（content URI + `FLAG_GRANT_READ`）、SAF 另存（64KiB 流式）、删除。
  - `VideoImportDialog.kt`、`RemoteComfySection.kt`：导入 preview/save、显式 endpoint/探活/提交/定向取消说明。
- **注意（偏差）**：未引入 Media3，使用平台 VideoView；离线构建与固定平台 API 可审计，播放器不持有生成任务。

### 3.2 契约（`core/video-contract`，14 源文件）

- 6 个 value-class 身份、8 类节点注册表、文档/校验/canonical digest（UI/元数据不入 digest）。
- `VideoRunStates.kt` 冻结 §4.3.1 转换表；`VideoRunSnapshot` 强制 init 不变量；25 个错误码。
- `VideoWorkflowPort`：observe×2/submit/cancel/retry/saveRevision + media library；`VideoMediaContract`/`VideoImportContract`/`VideoRemoteContract`。
- `VideoWanBaselineRules.kt`：帧 4k+1、尺寸 32 倍（源自 native 实测推导）；euler/flow shift 3.0/guidance 6.0/33f/16fps 基线。

### 3.3 持久化与进程级 owner（V1-02a/V1-04）

- `app/.../video/data/VideoDatabaseSchema.kt`：run/event/media 表 + 条件转移 DAO（runId+attemptId+stateVersion）+ `MIGRATION_29_30/30_31/31_32`；`ChatSessionStoreRoomDatabase` version **35**（29–32 为视频表；32–35 为 Agent 领域 park_tokens / dispatch_outbox / workflow_checkpoints，回退视频时不得降版本）。
- `app/.../video/data/VideoRunStore.kt`：Store 接口 + Room 实现；快照含 `inputRefs`、remote 字段；`manifestFor` 按 backend 返回 pin。
- `app/.../video/execution/VideoGenerationOwner.kt`：**唯一业务状态写入者**。启动对账用构造时 `startupRows` 快照并复核 version，避免误伤新提交；Succeeded 经 publisher 后 `completeRunWithMedia` + finalize；`Interrupted`；未请求的 Stopped=FAILED；retry 用记录 seed。
- `app/.../composition/VideoHostAssembly.kt`：每进程一个 owner（AtomicBoolean）、一个 publisher、一个 worker adapter、一个 remote adapter、一个 import adapter；`installRuntimeLeaseProvider` 注入聊天租约；暴露 port/importPort/remoteProbePort。

### 3.4 worker、JNI 与 native（V1-02b/V1-03）

- AIDL `ILocalVideoWorker`/`ILocalVideoWorkerCallback`；`:local_video` 独立进程 Service（specialUse FGS，binder death→INTERRUPTED）。
- `VideoWorkerCore`：纯 JVM 单 active/身份取消/看门狗/`onIdle` 退出前台。
- `LocalVideoWorkerAdapter`：聊天 `EngineLifecycleLease` → `NativeMediaExecutionGate` 物理锁 → 绑定 worker；content URI 输入图 staging（≤32MiB，可选 sha）；执行 JSON 含 dit/text_encoder/vae 显式路径。
- `VideoWorkerPipeline`：manifest 六态校验（Ready 才执行）→ PREPARE/LOAD/TEXT_ENCODE → 轮询 native 进度 → `generateVideo` → **逐字段校验 native 有效参数回显**（宽高/帧/fps/steps/seed/guidance/flowShift/sampler）→ DECODE → 编码 → Succeeded；取消等 native 返回后才结算 Stopped。
- `core/sd-native/src/main/cpp/sd_video_generate.cpp`：真实 JNI 实现（非 stub）：请求/几何/路径/staging 包含校验、磁盘预算、`sd_vid_gen_params_t` 填充（euler、DISCRETE、flow shift、init image）、RAII 帧释放、逐帧 PPM、DSO 全局 cancel/progress 串行安装与清除。
- `NativeStableDiffusionBridge.kt`：`generateVideo/getVideoProgress/cancelVideo`。
- **真实模型执行 PASS（实验最小规格）**：T2V/I2V 64×64×9×1 均产出已校验 MP4；默认 480×832×33 产品规格仍受 CPU 与 20 分钟 watchdog 限制。98a 无模型时仍诚实报 `MODEL_MISSING`，不会产出假视频。

### 3.5 编码、发布与媒体库（V1-04）

- `VideoMp4Encoder`：H.264、`MediaCodecList` capability 选择（flexible YUV + size）、baseline/level 回退、YUV420 row/pixel stride、PTS `index*1e6/fps`、EOS drain、60s 看门狗。
- `VideoMp4Verifier`：MediaExtractor 校验 H.264/无音轨/尺寸/样本数/时长 + 首/中/末帧解码 smoke。
- `VideoMediaPublisher`：staging 校验 → verifier → sha256 → journal（verified/renamed/committed）→ 原子 move + fsync → owner DB 提交 → 清理；崩溃窗口 reconcile 保留已确认成功、隔离未确认字节；删除用 tombstone+unlink，`abandon` 有 journal 时拒绝。
- 媒体输出目录在 app 私有 `filesDir` + FileProvider `video/media`。

### 3.6 ComfyUI API-format 导入（V2-01/V2-02）

- `app/.../video/workflow/importer/`：严格 JSON（4MiB/64 深度/128 节点/512 边）、固定 12 类映射矩阵、未知/未用节点不静默丢弃。
- `runnable` 条件：euler + normal + denoise 1.0、4k+1 帧、32 倍尺寸、三角色同名 profile、无图输入、mp4/h264；不满足=只保存不可运行。
- `VideoImportAdapter`：lower → 保存不可变 revision；raw API JSON 存 `rawSourceRef`（不进 executable digest）。
- 仅支持 **API format**；UI format 明确拒绝。

### 3.7 远程 ComfyUI（V3-01）

- `app/.../video/remote/`：显式 endpoint（内存/Authorization header，不落盘）、`/prompt` 提交、WS 阶段 + history/queue 对账、`/view` 下载并校验 MP4 ftyp、排队删除 vs 显式 targeted interrupt、丢响应保持 `REMOTE_UNKNOWN` 不重发。
- 不自动本地→远端回退；不支持远端图片上传（含 LoadImage 的图显式拒绝）；无音频。
- **真实 server NOT_RUN**：无固定 revision/endpoint。

### 3.8 既有文件的追加式修改（可逐个回退）

`settings.gradle.kts`、`app/build.gradle.kts`、`AppTab.kt`、`MainActivity.kt`、`MainChatScreenActions.kt`、`LoadModelCompletionPublish.kt`、`ChatModelCancelAndSwitchOperations.kt`、`MainViewModel.kt`、`MainRuntimeAssembly.kt`、`ChatSessionStoreRoomDatabase.kt`、`AndroidManifest.xml`、`feature/chat` 三文件、`LocalImageExecutionGate.kt → NativeMediaExecutionGate.kt`（等价更名，锁文件名不变）、`scripts/verify-code.ps1`、`tools/architecture/check_architecture_rules.py`、FileProvider xml。

---

## 4. 架构不变量（接手者不得破坏）

1. **owner 唯一**：页面/API/worker 只发 intent；所有业务状态只经 `VideoGenerationOwner` → 条件 DAO 事务。离开页面不取消；重启后非终态→INTERRUPTED（绝不伪造继续采样）。
2. **诚实不可用**：无模型→明确 `MODEL_MISSING`；无后端→`BACKEND_UNAVAILABLE`。production 装配是真实 adapter，不使用 fake 执行器。
3. **转换表线性**：阶段 TEXT_ENCODE→SAMPLE→DECODE→ENCODE→PUBLISH 按序推进，不允许跳段结算。
4. **取消三层**：QUEUED 直达 CANCELLED；运行中→CANCEL_REQUESTED→adapter 确认 Stopped→CANCELLED+RELEASED；requestCancel 不杀执行协程，靠 `executor.cancel(requestId)` 协作。
5. **锁域与锁序**：视频与图片共用 `NativeMediaExecutionGate`（同一物理锁文件）；锁序=产品准入→聊天 `EngineLifecycleLease`→物理锁→native context，反向释放。
6. **编译器物化**：owner 解析的图片绑定先在文档中物化再校验，物化结果进 canonical digest；同 id 异内容=Conflict。
7. **manifest 纪律**：`measuredPresets` 在 P0-C 真机数据前强制为空；Turbo/TAEHV/wan2.1_vae/A14B/Hexagon 是显式排除项。
8. **契约纯净**：`core:video-contract` 禁 android/androidx/app/engine/org.json/okhttp（Rule 18）；feature 只依赖契约+designsystem（Rule 19）。
9. **发布协议**：publisher 只做文件协议不写业务态；owner 在 publisher 返回后才提交 DB；崩溃窗口由 journal + reconcile 收口。
10. **远端**：显式 backend、无自动回退、凭据只在内存/header、取消只在排队时删除或显式 targeted interrupt。

## 5. 生产调用链与资源释放边界

```
UI(feature) --intent--> VideoWorkflowPort
  -> VideoGenerationOwner(主进程, 唯一状态写入者)
     -> 队列/取消/重试状态机
     -> LocalVideoWorkerAdapter
        -> 聊天 EngineLifecycleLease
        -> NativeMediaExecutionGate(物理文件锁)
        -> bind :local_video -> VideoWorkerCore -> VideoWorkerPipeline
           -> NativeStableDiffusionBridge -> sd_video_generate.cpp -> generate_video
           -> PPM frames -> VideoMp4Encoder -> staging/output.mp4
     -> VideoMp4Verifier -> VideoMediaPublisher(journal/原子 move/fsync)
     -> Room completeRunWithMedia(条件事务) -> projection
  -> UI observeRuns/observeMedia -> 播放/作品库/分享/另存/删除
远端:
  owner -> RemoteComfyExecutionAdapter -> /prompt -> WS/history/queue -> /view
```

释放边界：native 帧/ctx/init image 在 JNI finally 释放；worker 结束/取消释放 lease 与物理锁；service 在 `onIdle` 后退出前台并停止；播放器在 `ON_STOP`/dispose 停止；publisher 成功/失败都清理 staging（保留 journal 供 reconcile）。

---

## 6. 验证命令与原始证据

```powershell
# 架构门禁（纯 Python，不占构建锁）
python tools/architecture/check_architecture_rules.py                 # 19/19 PASS

# 定向 JVM（经 McaBuildLock；需 MCA_QNN_SDK_ROOT）
:core:video-contract:testDebugUnitTest --rerun                          # 45/45
:app:testDebugUnitTest --tests 'com.muyuchat.mca.video.*' --rerun       # round-3 应为 135/0；本会话被并发坏测试阻断，21:55 并发全量跑 15 套件=135/0 仅 incidental
:feature:video-workspace:testDebugUnitTest --rerun                      # round-3：30/30（含 retry 守卫）
:feature:chat:testDebugUnitTest --rerun                                 # 486/486
:app:compileDebugKotlin                                                 # PASS
:core:sd-native:assembleDebug --rerun-tasks                             # PASS（仅编译/链接）

# 设备（需设备端允许 USB 安装/确认）
gradlew '-PmcaDeviceTestVariant=true' '-Pmca.abis=arm64-v8a' :app:connectedDeviceTestAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.video.worker.NativeVideoBridgeDeviceTest,...'
```

- XML：`app/build/test-results/testDebugUnitTest/*.xml`、`feature/video-workspace/build/test-results/testDebugUnitTest/*.xml`、`core/video-contract/build/test-results/testDebugUnitTest/*.xml`。
- 日志：`%TEMP%\mca-sdnative-refresh2.log`、`%TEMP%\mca-video-app-v10.log`、`%TEMP%\mca-video-device3.log`、`%TEMP%\mca-video-device5.log`。
- 裸 gradlew 三件事：`Import-Module tools\build-guards\McaBuildLock.psm1` + `Enter-McaBuildLock -RetrySeconds ≥900` + 设 `MCA_QNN_SDK_ROOT`。证据按 XML mtime 过滤。

## 7. 未验证项总账（全部带恢复条件）

| 项 | 状态 | 恢复条件 / 下一步 |
| --- | --- | --- |
| 真实 Wan2.2 TI2V-5B 文生/图生（P0-C） | **PASS（流程/媒体，最小规格）** | 本轮产物 A：T2V 228s/7.96GB/sha `1209881e…`；I2V 247s/8.68GB/sha `c5defe01…`，imageRef 绑定。**语义质量 NOT_MET**：最小规格帧不可辨识；产品规格 480×832×33 仍未完成 |
| 产品规格真实出片（480×832×33×20） | **FAIL/NOT_MET** | 1 步被 20 分钟 watchdog 终止；20 步小时级；需加速后端或重定默认规格 |
| 真实取消/释放/重试/进程死亡恢复 | **PASS（阶段延迟如实记录）** | 采样阶段取消 6s；TEXT_ENCODE 阶段取消 149s 后 CANCEL_REQUESTED→CANCELLED/RELEASED；重试入口本轮修复并在产物 B 真机复测（新 run/attempt 同 revision）；外部强杀→INTERRUPTED/UNCONFIRMED（`INTERRUPTED_PROCESS_DEATH`） |
| 设备 instrumentation（编码/发布/迁移/JNI/UI） | **PASS** | 产物 B run14 `OK (9 tests)`；产物 A run13 同；run11 更新弹窗 flake 1/9 保留并已修测试轮询；首轮 6 FAIL 保留为历史 |
| 真机 UI 视觉/无障碍/两轮 QA | **round-3 子集 PASS（历史产物 cff571c5；98a 现已被换装 4ef1a121）**；round-4 已补语义标签 | 浅/深色、节点列表、英文长文本、键盘、两轮 QA；round-3 缺陷：端口高 21.8–22.2dp<48dp、端口 desc 缺节点名、节点列表容器无自身标签、摘要截断、抽屉“图片/最近”无 desc；round-4 已修端口节点名/节点列表按钮 desc/抽屉“图片”label（产物 C），端口尺寸与摘要未修、TalkBack 实读未验证 |
| QNN/NPU Wan2.2 加速 | **BLOCKED** | 仅有 GGUF；缺原始 safetensors/ONNX、Wan QNN context/model 包与 HTP op/context 验证。恢复条件见 `QNN_FEASIBILITY_AUDIT.md`；提供转换资产前不得写 QNN 后端或宣称 NPU |
| V1-06 产品预算与整合验收 | **NOT_MET / 未通过** | 实测轮廓：64×64×9×1 约 4–5min/8.5–9.1GB；480×832×33×1 >20min；480×832×33×20 小时级；取消确认采样阶段 6s、加载阶段 149s。OpenCL/Vulkan 均崩溃，QNN 资产缺口 BLOCKED；预算不能按默认规格冻结 |
| OpenCL 加速 spike | **FAIL** | 构建/9 项测试通过；64×64×9×1 首个 Wan 采样步在 `ggml_backend_opencl_graph_compute` 内 `ggml_abort`（APP CRASH NATIVE，signal 6，187s，峰值 5.37GB，4.2GB swap），无媒体；vendor libOpenCL 未打包；详见 IMPLEMENTATION_LOG“加速尝试轮” |
| Vulkan 加速 spike | **FAIL** | NDK glslc + Khronos Vulkan-Hpp/SPIR-V + MSVC host shader-gen 构建成功、9/9 测试通过；64×64×9×1 首个 Wan matmul 在 Adreno 驱动 `vkCmdBindPipeline` SIGSEGV（`ggml_vk_mul_mat_q_f16`，16:29:49），无媒体；详见 IMPLEMENTATION_LOG“加速尝试轮：Vulkan” |
| 图片/聊天/DSH 真实冒烟回归 | **NOT_RUN/BLOCKED** | f9d 已真实导入图片引擎（Anima 5.24GB，生产 UI），但图片任务启动 no-op（`image_assets=0`、无 worker，根因未定）；98a 被外部 lane 换装 `4ef1a121…` 并占用 DSH/chat，本会话 0 发送/0 DSH 交互。恢复条件=独占设备 + 可加载聊天模型（聊天生图桥）或可确认提交的图片页路径 |
| 图片×视频 native 资源仲裁（真实任务） | **NOT_RUN/BLOCKED** | 同一 `NativeMediaExecutionGate`（锁文件 `native_image_execution.lock`）源码/JVM 测试在；本轮图片引擎导入 PASS，但生产图片任务未启动；debug `LocalImageStoreSmokeActivity` 直调 provider、绕过 gate，不能作证。恢复条件=先让真实图片任务 RUNNING，再并发提交视频并观察 gate 等待 |
| 98a 当前装机一致性 | **BLOCKED/风险** | 98a 现装 `4ef1a121…`（450,679,424B，lastUpdate 21:30:13），无法追溯到保留产物/revision；round-3 的 cff571c5 证据仅历史。任何 98a 结果在来源确认前不得作发布验收 |
| 128×128×9×4 预算探针 | **NOT_RUN** | f9d 锁屏/外部占用导致 UI 自动化无法进入；未产生该规格证据，预算结论仍以 480×832×33 与 64×64×9×1 实测为准 |
| 当前树全量 JVM | **round-4 旧快照（已作废，勿引用）** | 旧 140/0 是排除了 `VideoSchemaSqlTest` 且不含 10 个新 owner 用例的集合。**2026-10-28 当前树**：contract 45/0、workspace 33/0、app 视频+policy 161/0（18 suites）、gate 补充 73/0、迁移 71/0（14 suites）、架构 19/19；另一 lane 的 `DshPromptedToolProtocolTest` 本轮 NOT_RUN；全仓所有模块测试本轮 NOT_RUN |
| 运行卡进度/阶段残留 | **PASS（已修复）** | `VideoProgressProjection` 仅接受 phase=sampling 且 total=请求步数的回调；产物 A 真机首个 SAMPLE 为 0/1，无 825/1263、无提前 SAMPLING |
| 终态任务重试入口 | **PASS（已修复）** | 产物 A 无按钮（FAIL）；产物 B 加 `video.run_retry_action` + VM→owner retry，真机复测新 run/attempt 同 revision，原终态不变 |
| 真实远端 ComfyUI 提交/WS/history/queue/下载/取消 | **BLOCKED** | 提供固定 server revision 的 endpoint 与凭据（仅内存输入）；用真实 workflow 导出 fixture 固化 |
| Comfy 导入对真实 server revision 的兼容性 | **NOT_RUN** | 同上；当前兼容性声明只覆盖固定 fixture 与 12 类矩阵 |
| 3 个备选资产 sha256 + ComfyOrg 许可标签 | **PASS（2026-10-26 复核）** | HF API：Q4_K_M `95b19697…`、Q8_0 `57bece98…`、UMT5 Q4_K_M `17cf97a5…`；Comfy-Org/Wan-2.2 标签 Apache-2.0、仓库 revision `ee6f4a40…`、fp16 sha `456f9013…`；备选权重仍未下载运行=NOT_RUN |
| 12GB 级模型每次提交前流式 sha256 的耗时 | **NOT_RUN** | P0-C 实测；如超预算需在 V1-06 决策缓存/采样策略（不得静默跳过校验） |
| native decode 阶段细粒度进度 | **已知限制** | native 返回后才报 DECODE；不可中断阶段由 worker watchdog 收口 |
| 内存常量性 | **不得宣称** | sd.cpp 先持有整段 float tensor 再分配全部 RGB 帧；`W×H×F×3` 只是下界 |
| 音频 | **N/A（V1）** | 仅 H.264 无音轨；播放器/编码器/校验器均按无音轨实现 |
| Media3 | **偏差已记录** | 平台 VideoView；如后续切换 Media3 需重做播放器验收 |

**首轮设备原始结果（25091RP04C，2026-10-26 03:08）**：9 tests / 3 PASS / 6 FAIL。
PASS：native 缺失组件→`MODEL_MISSING`、29→32 全表创建、损坏帧→Failed（不产出半成品）。
FAIL：64×64 编码 `CodecException`、取消回调计数断言、publisher 因编码失败、30→32 断言写反、native 无效请求后进度断言、Compose/Espresso 在 Android 16 的 InputManager 兼容失败。
修复动作：encoder capability 选择 + 128×128 测试帧 + 明细错误；取消断言次序；迁移断言修正；UiAutomator 替换 Espresso；publisher 测试输入。**修复后最终 APK 已在 98a 与 f9d 各 `OK (9 tests)`；首轮 FAIL 保留为历史，不改为 PASS。**

## 8. 回退地图（逐卡独立，不触碰用户数据）

- 入口/UI：还原 §3.8 导航 diff + 删 `feature/video-workspace` → 旧入口全部仍在。
- V1-00：`NativeMediaExecutionGate` 还原名（锁文件名不变，无兼容面）。
- V1-01：删 `video/model/` 三文件 + 测试 + app 依赖行。
- V1-02a：删 owner/store/schema/assembly；DB version **保持 35** 且 32–35 注册保留（当前版本已被 Agent 域占用，不得整体降级；只删 29–32 视频表/DAO）。
- V1-02b/V1-03：删 AIDL、worker 四文件、`sd_video_generate.cpp`/CMake 行/bridge 方法，恢复 `executor=null`；不改图片 bridge 语义。
- V1-04：删 codec/media 文件与 media 表；DB version **保持 35**；已发布文件采用前向修复，不删除其他 run 产物。
- V1-05：撤回 feature 播放器/库/动作与 Route 参数，不影响 owner/store/已发布媒体。
- V2/V3：删 importer/remote 包与 UI 区块，恢复 `remoteExecutor=null`；已持久化 endpointId 无害。
- 文档/证据：`docs/proposals/mca-native-video-workflow-v1/` 整目录（IMPLEMENTATION_LOG 是唯一逐卡证据源，别误删）。

## 9. 用户要求逐条审计

| # | 要求 | 状态 | 证据/缺口 |
| --- | --- | --- | --- |
| 1 | DSH 下方“视频”入口 | **PASS（真机）** | 入口回调修复；抽屉→视频页→画布真机组合；浅/深色截图 |
| 2 | 沿用 MCA 设计语言 + ComfyUI 节点交互 | **PASS（两轮 QA）** | 点阵画布、贝塞尔线、7 列 DAG、缩放/平移、端口行、节点列表、TalkBack 端口动作；截图见 IMPLEMENTATION_LOG 收尾轮 |
| 3 | 基础 Wan2.2 TI2V-5B 文生/图生 | **PASS（最小规格）/ 产品 NOT_MET** | T2V/I2V 64×64×9×1 真实 MP4；480×832×33×20 被 watchdog/小时级阻断 |
| 4 | 编码/持久化/恢复/播放/分享/删除 | **PASS（真机）** | 9 项测试两机通过；播放/分享/另存/删除/取消设备实测；进程死亡→INTERRUPTED |
| 5 | ComfyUI API-format 导入 | PASS（fixture 范围） | 50 tests；真实 server revision 未验 |
| 6 | 远程 ComfyUI | 代码/JVM PASS；真实 BLOCKED | 39 tests；无固定 revision endpoint |
| 7 | 14B/MiniMax H3 不实施 | PASS | 无 profile；manifest/矩阵显式拒绝 |
| 8 | intent-only、唯一 owner、锁序、离页不取消、复进不建第二运行时 | PASS（JVM/架构） | owner 单例、租约、物理锁、状态机测试 |
| 9 | 不复制受限源码/二进制/品牌 | PASS | 仅契约/自写实现；未引入参考项目代码/资产 |
| 10 | 串行受控构建、真实行为测试、fake/真实分开 | **PASS（有界）** | 全程 McaBuildLock；最终 APK 真实构建；真实 native 与设备动作已执行；全量 JVM 与图片/聊天/DSH 冒烟本轮未重跑，已单列 |
| 11 | 每卡检查点 + BLOCKED/NOT_RUN 恢复条件 | PASS | IMPLEMENTATION_LOG + 本文 §7 |
| 12 | 不提交/推送/发布、不清数据 | PASS | 无 git 提交；未删除用户数据 |

## 10. 接手后的最短路径

1. **先解产品预算/加速**：默认 480×832×33×1 已超过 20 分钟 watchdog，480×832×33×20 为小时级。OpenCL 与 Vulkan 后端均已在真机 Wan 首个采样 matmul 原生崩溃（OpenCL `ggml_abort`、Vulkan Adreno `vkCmdBindPipeline` SIGSEGV），已回退 CPU；下一步需接入 NPU/QNN 或换硬件，或重新论证并冻结更小的默认几何/步数。不能把 64×64×9×1 实验规格写成产品可用。
2. **再做产品规格真实出片**：在冻结预算内重跑 T2V/I2V，记录帧数/尺寸/MP4/播放/分享/导出/删除/取消/重复/热/峰值内存；当前只有最小规格证据。
3. **补回归**：`ChatWorkspaceNavigationTest`、图片/聊天/DSH 真实冒烟、图片×视频真实仲裁、app 视频过滤 JVM（预期 135，需并发 lane 的坏测试文件修复）；本轮完成架构 19/19、contract 45/0、workspace 30/0、chat 486/0、9 项视频设备测试与真实视频路径。
4. **修运行卡残留**：native/worker 每次开始清零 progress，延迟 SAMPLING 上报；把端口点触控高度提升到 48dp 或明确以节点列表为唯一无障碍路径。
5. **最后 V3**：固定 ComfyUI server revision 与 endpoint，做真实提交/WS/queue/history/下载/取消验收，并把导出 fixture 纳入测试。

在上述 1–2 与 V1-06 产品预算未满足前，**不得**把 V1 标记为完成；最小规格出片只能称实验闭环，不能称移动端产品可用，也不得用远端结果替代本地验收。

---

## 11. round-3（2026-10-26 夜）交接补记

### 11.1 本轮实际完成（均可在 `build/evidence/v1-06/round3-*` 复核）

| 项 | 结果 | 产物/证据 |
| --- | --- | --- |
| 当前树重建（双 ABI，经 McaBuildLock） | PASS | 产物 A `c5d81d3b…`（20:11，565,556,773B）；产物 B `9e50648a…`（21:38，565,602,537B，含重试入口） |
| 设备仪器化（编码/发布/迁移/JNI/UI） | PASS | run13 产物 A `OK(9)`；run14 产物 B `OK(9)`；run11 1/9 FAIL（`chat did not become visible`，疑似弹窗晚到、截图损坏）保留并已改测试轮询 |
| 真实 T2V 64×64×9×1 | PASS（流程/媒体） | 228s、峰值 7,963,708KB、温度 37.0°C/zone0 46.98°C、MP4 9 帧 sha `1209881e…` |
| 真实 I2V 64×64×9×1 | PASS（流程/媒体）/ 语义 NOT_MET | 247s、8,677,963KB、38.0°C/47.24°C、imageRef 绑定、MP4 sha `c5defe01…`；帧近白不可辨识 |
| 取消（采样阶段 / 加载阶段） | PASS | 采样 6s 确认；TEXT_ENCODE 149s 确认；均 CANCELLED/RELEASED + `CANCELLED_AFTER_NATIVE_STOP` |
| 重试（产物 B） | PASS | 终态卡→新 run/attempt 同 revision，原终态不变；复测 run 取消后 CANCELLED/RELEASED |
| 进程死亡恢复 | PASS（外部强杀观察） | CONDITIONING 被杀→重进 INTERRUPTED/UNCONFIRMED + `INTERRUPTED_PROCESS_DEATH` |
| 播放/分享/另存/删除 | PASS | 播放器进度推进；分享面板；导出 sha 一致；删除协议移除私有 media 行/文件（最终 DB 4 行/目录 4 文件，两个 round-3 副本均移除、导出保留）；UI 计数刷新一次滞后已注明 |
| 模型资产 | PASS | f9d 三文件 sha 与 pin 完全一致；HF API 许可/版本/备选 oid 复核 0 不一致 |
| 轮次门禁 | PASS（本轮范围） | 架构 19/19；contract 45/0；workspace 30/0；chat 486/0；**app 视频+policy 140/0（22:17）**；并发 lane 坏文件未改动、其 2 测试 NOT_RUN |
| 视觉/无障碍（round-3 时 98a 装 cff571c5，现已被外部 lane 换装 4ef1a121） | 子集 PASS + 缺陷清单；round-4 已修部分语义 | 浅/深色、长文本、键盘、两轮 QA；端口 21.8–22.2dp<48dp 等 5 项（4 项 R2 仍在）；round-4 产物 C 修复端口节点名/节点列表 desc/抽屉“图片”label；聊天冒烟 NOT_RUN |

### 11.2 仍未完成（决定 V1 不能完成）

1. **产品预算/V1-06 NOT_MET**：唯一可完成规格是 64×64×9×1（约 4–5min、8.5–9.1GB、0.56s 片），语义不可辨识；默认 480×832×33×1 >20min、×20 小时级。OpenCL/Vulkan 已在 Wan 首个采样 matmul 崩溃，QNN 资产/转换链 BLOCKED。需加速后端（NPU/新驱动）或重新论证并冻结更小默认规格；在预算冻结并满足前只能称实验闭环。
2. **聊天/DSH/图片真实冒烟与 image×video 仲裁 NOT_RUN**：两机都没有可用图片模型；98a 聊天无已加载模型；f9d 被真实视频与外部 lane 占用。恢复条件=空闲设备 + 已注册图片 bundle + 已加载聊天模型。
3. **app 视频 JVM 已补齐本轮范围**：22:17 以仅本次生效的 init script 排除并发 lane 不可编译的 `VideoSchemaSqlTest.kt`（未编辑/删除），独立执行 135 视频 + 5 policy = **140/0**；该文件自身 2 测试 NOT_RUN，恢复条件=该 lane 修复其类型错误。
4. **可见质量**：最小规格 T2V/I2V 不能支撑“可辨识短片”；需要更大的几何/步数或加速后端才可做语义质量验收，且须重新测量内存/热/时间。
5. **无障碍缺陷**：端口 48dp、端口语义名、节点列表容器标签、摘要截断、抽屉“图片/最近”label 仍未修；本轮只记录未改（为避免在共享工作区混入视觉变更）。
6. **远端 ComfyUI BLOCKED**：无固定 revision endpoint/凭据；恢复条件见 `round3-remote-assets.md`。
7. **加速 spike**：Vulkan 回移上游 Adreno tile 修复（`d14d9db8c`，约 8 行）+ debug 构建是最小可验证下一步（约 0.5 人日），但即使越过首 matmul 也不能承诺产品规格；未执行。

### 11.3 回退与边界

- 设备：仅升级 debug 签名 `com.muyuchat.mca.devicetest`；production `com.muyuchat.mca` 未动；f9d 模型/历史在 app 私有目录；98a 已恢复 stayon=false/night no/输入法。
- 工作区：未提交/未推送/未发布；`AGENTS.md` 与 `UniversalDeviceAdmissionPolicyTest.kt` 仍在 `.git/info/exclude`（审核 PASS）。本轮新增代码可按 IMPLEMENTATION_LOG §12 逐项回退。
- 并发：f9d 曾出现外部 instrumentation/force-stop 与 `run-as cp mca-restore.db`（20:44 logcat），构建锁被其他 lane 长时间占用；证据中的 INTERRUPTED 即由此产生，已如实标注而非伪造。

---

## 12. round-4（2026-10-26 深夜）交接补记

### 12.1 实际新增（均有原始证据）

- 当前树 JVM：app 视频+policy **140/0**、feature workspace **32/0**、contract 45/0、chat 486/0、架构 19/19。app 跑通过仅本次生效的 init script 排除并发 lane 不可编译的未跟踪 `VideoSchemaSqlTest.kt`（未编辑/删除；其 2 测试 NOT_RUN）。
- 无障碍：端口语义带节点名、节点列表按钮显式描述、抽屉“图片”label；新增 `VideoAccessibilityLabelsContractTest`；f9d 产物 C 上语义树复核通过；原始无标签 clickable 11→9（剩余为 Material 包装节点，TalkBack 实读未验证）。
- 产物 C：`e337ba0e…` 568,213,041B @22:29:29，f9d lastUpdate 22:31:06，run15 OK(9)。
- 真实图片引擎：Anima Preview 5.24GB 经生产 UI 导入 f9d 成功（生图(1)、CPU）。
- 产物 C 真实 T2V：243s/峰值 8,495,324KB/zone0 46.46°C，MP4 9 帧 sha `78e0286c…`，流程与媒体 PASS；帧近白（1 步最小规格+随机种子），语义 NOT_MET。
- 证据管理说明：round-4 复用 T2V harness 时覆盖了 artifact-A 的 `round3-t2v-*` 文件，已改名 `round4-t2v-artifactC-*` 并写 `round4-t2v-README.txt`；A 的 MP4 由 `round3-exported-203000.mp4`（sha `1209881e…`）保留，A 的 DB 事件在 `round3b-db-summary.txt`。不得把 round4 文件归因给 A。

### 12.2 仍未完成/阻塞（V1 不能完成）

1. **产品预算/V1-06 NOT_MET**：64×64×9×1 仍约 4–5min/8.5–9.5GB 且语义不可辨识；480×832×33×1 >20min、×20 小时级；加速 OpenCL/Vulkan FAIL、QNN BLOCKED。
2. **image×video 真实仲裁 NOT_RUN**：图片引擎已导入，但生产图片任务未启动（FAB no-op，`image_assets=0`，根因未定）；debug 直调绕过 gate 不可用。需先打通真实图片任务，再并发视频观察 gate 等待。
3. **聊天/DSH 真实回归 NOT_RUN**：98a 被外部 lane 换装 `4ef1a121…` 并占用 DSH/chat；f9d 上已拉取并推送 `qwen-test.gguf`（428MB）尝试经生产 UI 导入，但 DocumentsUI 浏览/分类/搜索均未能选中 .gguf（文件夹行点击退出选择器），未产生导入/加载/发送 → 仍 NOT_RUN；恢复条件=独占窗口 + 可追溯产物 + 可自动注册的模型下载入口或修通 SAF。
4. **98a 产物来源风险**：`4ef1a121…` 无法追溯保留产物/revision；来源确认前其任何结果不得作发布验收。
5. **TalkBack 实读、端口 22dp<48dp、摘要截断**：本轮只修了语义标签；端口触控尺寸与 TalkBack 自动化仍未验收。
6. **app 断言的 2 个迁移测试**：并发 lane 的 `VideoSchemaSqlTest.kt` 仍 NOT_RUN（未编译），需其修复后全量重跑。
7. **远端 ComfyUI**：无固定 revision endpoint，BLOCKED 不变。

### 12.3 恢复条件与下一步（按条件）

- 图片仲裁：空闲 f9d + 图片任务确认 RUNNING（可加载聊天模型走聊天生图桥，或定位直接图片页 no-op 原因）；随后并发提交视频并记录 gate 等待/串行。
- 聊天/DSH：98a 或 f9d 独占窗口 + 已知产物 + 已加载聊天模型（qwen-test 428MB 最小）；命令与步骤见 `round4-98a-chat-dsh-report.md` 恢复条件。
- 产品预算：需加速后端（Vulkan Adreno tile 回移 spike 约 0.5 人日验证；QNN 单 block 2–4 人日、全模型 2–4 周）或重新论证并冻结更小默认规格；在预算冻结并满足前不得标记 V1 完成。
- 无障碍：端口 48dp 需重排/覆盖层方案 + 视觉复验；TalkBack 需人工或自动化朗读验证。


---

## 2026-10-29 v1-14 增补：98a 480×832 再试（LMKD 阻塞）+ 256² 语义补测

- **480×832×9×1 基线**（aeTiling=false）：get_learned_condition 89.76s、sampling completed 619.93s（含一次 Greeze 冻结），解码未完成 → 22 分钟上限中止，无媒体、无解码数值。
- **480×832×9×1 分块**（512×288@0.5）：get_learned_condition 93.28s、sampling completed 290.89s，解码跑到约 7m44s 被 **LMKD 杀**（lowmemorykiller: thrashing、eason=lmkd:259、signal 9、RSS≈5GB），无媒体。
- ⇒ **480×832 存在第二个硬阻塞：内存**，VAE 分块无法绕过（分块解码仍持全分辨率中间张量）；预算对照仍 **NOT_RUN**，不伪造。
- **256×256×9×4**（ed ball on blue table，完成）：get_learned 123.20s、sampling 301.58s、**decode_first_stage 602.34s**、总 **1056s（17.6min）**、峰值 PSS 6,748,616KB、media 58,935B sha 7883540bc2eff41086af7eab990aaa96b5b7fc3683129085043d60762f4488bf；**视觉为混沌彩色模糊，无可辨识红球/桌面**。
- 结论：CPU 路径上语义与预算仍 **NOT_MET**；下一步二选一或并行——①引入 lightx2v 4-step 蒸馏权重（需批准 + 更新 pin/sha）；②解码期内存策略（按时间片流式解码、不保留全分辨率中间量）。
- 备注：磁盘 base APK 已变为其它 lane 构建的 34C81E3D…（585,284,778B，含本会话两处修复：stacked 5D + Wan 2.2 TI2V 识别）。
## 2026-10-29 v1-15 增补：画质结论的步数口径修正（重要）

本会话此前的“语义 NOT_MET”判定大多基于 **4 步**（64×64×9×4、128×128×9×4、256×256×9×4），而产品默认是 **20 步**（VideoWorkflowTemplates.kt：480×832×33/16fps/20steps）。
Wan 2.2 5B base 不是 4-step 蒸馏模型，4 步远低于可用步数 ⇒ **4 步下出抽象色块属预期**，不能据此断言模型/条件链能力不足；唯一 20 步数据点（64×64×9×20，799.6s）受分辨率限制同样无法承载“红球/桌面”。

**正确的语义判定尚未做**：需在生产步数下、分辨率足够的规格跑一次（128×128×9×20 或 256×256×9×10），预计 40–60 分钟。
**因此 HANDOFF 中“画质 FAIL/NOT_MET”应读作**：*在该次实验的步数与分辨率下未出现可辨识主体*，而非“产品规格下不可能”。若引入 lightx2v 4-step 蒸馏权重，则 4 步即为其正规推理配置，届时 4 步判定才有效。
## 2026-10-29 v1-16 增补：20 步语义判定与预算定论

- 128×128×9×20（合规步数）完成：条件 132.16s、采样 **842.89s（20 步，42s/步）**、解码 184.76s、总 **1159.81s/19.3min**；抽帧目视**仍是抽象色带，无可辨识主体**（uild/evidence/v1-16/）。
- 由此定论：**分辨率（低于 Wan 2.2 的 480p 工作区间）与 CPU 算力**才是限制，非步数、非条件链；480×832×33×20 采样量约为本次的 36× ⇒ ≈8.5 小时，CPU 路径与产品预算差约两个数量级。
- **产品路径 = NPU(QNN) + 4-step 蒸馏 + tile/流式解码**；CPU 仅实验能力。V1 语义/预算仍 NOT_MET。
## 2026-10-29 v1-17 增补：Turbo 4-step LoRA 已接入（真机验证）与语义边界

- **资产**：Turbo LoRA Wan22_TI2V_5B_Turbo_lora_rank_64_fp16.safetensors（332,348,584B，sha256  ace5244…478967b8，apache-2.0，已 pin 入 MODEL_ASSET_SOURCES.md），主机与设备双校验一致。
- **接入证据**：请求行 lora=…@1.000000 + 同日志 stacked 5D shape … -> (2,2,1,147456) ⇒ 该 APK 同时具备 5D 堆叠修复与 LoRA 通道。
- **实测**（128×128×9，seed 42）：4 步+LoRA = 采样 236.39s / 解码 198.71s / 总 **625.92s（10.4min）**；对比 20 步无 LoRA（842.89s / 184.76s / 1159.81s）**整体快 1.85×**。
- **语义**：4 步+LoRA 帧与 20 步帧明显不同（LoRA 生效），但**仍无可辨识主体** ⇒ **128×128 低于 Wan 2.2 的 480p 工作区间**；语义判定必须在 480p 级，而 CPU 480p×4 步约 40 分钟以上 ⇒ **产品预算只能靠 NPU**。
## 2026-10-29 v1-18 增补：NPU 环境验证通过

QAIRT 2.45.0 的 Linux 工具链（in/x86_64-linux-clang）在 **WSL Ubuntu-22.04 + Python 3.10.12** 下可正常执行：PYTHONPATH=<sdk>/lib/python python3 qairt-converter --help 输出完整参数表 ⇒ **此前"QAIRT 在 Windows 上 ABI 不匹配"的阻塞已绕过**（无需装 Windows CPython 3.10）。
仍缺：Wan DiT 的 ONNX/原始 safetensors（当前只有 GGUF）、QNN context 生成、HTP 推理接线。NPU 从 **BLOCKED(环境)** 变为 **READY(待资产与转换)**，预计 2.5–4 周。
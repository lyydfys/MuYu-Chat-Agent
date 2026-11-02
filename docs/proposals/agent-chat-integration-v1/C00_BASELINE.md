# V1.1 C00 基线锁定与唯一状态入口（2026-10-19 建立；2026-10-20 01:18 事实更新）

> 本文件只记录**当前工作区事实**和本轮实施边界。它不是 PASS 证据；任何 runtime/device
> 结论仍需真实执行。

> **2026-10-20 01:05–01:18 快照。** 本文已从 23:46 的 preflight 快照更新：仓库 HEAD 从
> 98eb8e4/7475fd6 推进到 `af24899`；架构从 14/14 变为 **16/16**；单测是**分模块独立
> 运行**，不是一次性汇总；full gate 仍 **NOT_RUN/BLOCKED**。工作区在采样期间不稳定：
> 01:07 为 82 个 `??`（`-uall` 542），01:12 为 556，01:18 变为 45 ` M` + 37 `??`
> （`-uall` 558），HEAD 未变——这本身就是不能开 gate 的证据。23:46 的旧快照保留在
> [ONE_SHOT_VERIFICATION_PREFLIGHT.md](ONE_SHOT_VERIFICATION_PREFLIGHT.md) 与各 evidence
> 文档中，不再代表当前事实。

## 1. 锁定事实（2026-10-20 01:07–01:18）

- **交付/代码基线（用户指定 HEAD）：`7475fd6`**
  （`7475fd640e21154dd9543bcae2c83ab112e30361`，2026-10-19 23:35:31，最后一个触及
  UI/测量脚本的 polish 提交）。
- **抓取时仓库 HEAD：`af24899`**
  （`af2489909117edfa88721816a5fa40f5a4a5df9c`，2026-10-20 00:42:38，
  `docs(audit): frontend completion report`）。`7475fd6..af24899` 的 6 个提交只改 3 个
  tracked 文件：`docs/proposals/audit/v9/FINAL-MATRIX.md`、
  `docs/proposals/audit/v9/FRONTEND-COMPLETION-REPORT.md`、`scripts/measure-frames.ps1`，
  **不触及 app/core/feature 生产源码**。但工作区另有 **45 个 tracked 文件未提交修改**
  （Agent V1 共享写集、构建脚本、manifest 等），因此**不能把当前源码等价于 `af24899`
  或 `7475fd6`**；基线是 `7475fd6` + dirty tree。
- **工作区（多次采样，不稳定）**：
  - 01:07：`git status --porcelain` **82 行，全部 `??`**（0 modified / 0 staged）；
    `git status --porcelain=v1 -uall` **542 条**。
  - 01:12：`-uall` **556 条**（仍在增长）。
  - 01:18：82 行 = **45 个 ` M` + 37 个 `??`**；`-uall` **558 条**，0 staged / 0 deleted；
    `git diff --stat` 为 45 files / +2317 / −411。
  - HEAD 始终是 `af24899`。Agent V1（`core/agent-contract`、`core/agent-runtime`、
    `feature/agent-workspace`、`app/.../agent`）、DSH 四模块、ADR 0002/0003、evidence 与
    新增测试仍是 untracked，不能引用某个 commit 复现。
  - preflight 的“44 modified + 497 untracked / 541 条”与 01:18 的 45 modified 形态接近；
    01:07–01:14 读到的“0 modified”**不是稳定事实**（原因未查明，但与并行会话同时发生），
    不能当作干净基线。
- **子模块**：`third_party/llama.cpp 4ceb1719…`、`third_party/stable-diffusion.cpp
  16b3846a…`、`third_party/tokenizers-cpp acbdc5a2…`。tokenizers-cpp 工作区仍有 5 个文件
  未提交改动（`CMakeLists.txt`、`include/tokenizers_c.h`、`include/tokenizers_cpp.h`、
  `rust/src/lib.rs`、`src/huggingface_tokenizer.cc`），属于 gate 指纹（硬锁 D08）。
- **构建锁（01:18 仍占用）**：`build/.mca-exclusive-build.lock` 记录 pid 14092、kind
  `build`、startedUtc `2026-10-19T17:02:41Z`、commandLine
  `lead: settings transition experiment`；实际 Gradle wrapper pid 28900 正在运行
  `:app:assembleRelease`。**当前不是空闲树，不能作为一次性 gate 的起点。**
- **并行会话**：仍在写 `feature/chat`、Agent/DSH 测试与 evidence；本文写作期间
  `feature/agent-workspace/.../AgentHomeHeaderTest.kt` 于 **01:06:37** 新增，说明
  workspace 的 118 测试结果已不是最新套件。本轮不得回退、覆盖或 stage 其改动。
- **设备**：`98a37aa7`（MIUI/HyperOS）与 `f9d98aa3` 在线；新包安装仍
  `INSTALL_FAILED_USER_RESTRICTED`，只允许同签名更新，不允许清数据/卸载。
- **唯一构建入口**：`scripts/verify-code.ps1`（全量 gate）与 `scripts/agent-verify.ps1`
  （单 lane）；构建锁不得手工绕过。本 V1.1 批次仍定义为“全部代码完成后一次性验证”，
  但截至本快照**一次性 gate 从未执行**。

## 2. C00 时点风险（历史判断，不据此改判 PASS）

> 以下是 C00 建立时（`98eb8e4` 邻域）的风险清单。C01/C03/C08 的部分修复已在
> 23:4x–00:1x 落地（最终回答持久化、MCP 不再自动 mint、Agent home 去品牌化等），但
> **没有 fresh app 全量测试**，因此不把任一条改写成 PASS；当前状态以 §4 和
> [V1_1_STATUS.md](V1_1_STATUS.md) 为准。

1. **MCP ASK 审批链未闭环**：tool-call 路径未提交 `UpsertInvocationTransition`，审批 insert
   fail-closed；即使修好也无 `tool.approval_requested` 事件与 origin 绑定（P0-1）。
2. **MCP egress 自动授权**：`RunScopedAgentToolCatalogProvider` 从 `allowAgentNetwork`
   常驻开关直接 mint run grant，而非每 run 显式授权；release 不 revoke（P0-2）。
   本轮 C03 之前不得把 MCP execution 当作可用能力。
3. **Local Agent 现在能真正调用模型**：修了 `mnn_opencl` → `LocalChatRuntime.backendId`
   与 sampling override 两处；Qwen3.5 0.8B 可生成，但不能稳定返回严格 CONTROL JSON
   （`PROTOCOL_MISSING_FIELD` / `PROTOCOL_INVALID_JSON`）；4B QAIRT native worker 加载失败。
4. **Agent 最终回答未持久化**：`RUN_TERMINAL.summary = display.name`，UI 把
   `SUCCEEDED` 当回答；需要把 `Completed.finalText` 有界写入 terminal summary（C01 附带）。
5. **前端仍不是 Chat 同款**：DSH landing 的 brand/selectors 与 192dp composer 仍在；
   用户已改为要求“像聊天页一样”并参考 third-party；UI 重做在 C08 范围。

## 3. C00–C11 依赖与写集（摘要）

| 卡 | 依赖 | 主要写集 | 当前状态（01:12） |
| --- | --- | --- | --- |
| C00 | — | 本文档 | 完成（本文件已更新） |
| C01 | C00 | `core/engine`、`core/agent-runtime` run/stop proof | 核心已写；runtime 132/0/0 @00:17；app 适配无 fresh app 证据 |
| C02 | C00/C01 | app chat execution/cancel、`feature/chat` | 代码已写；app 全量未重跑 |
| C03 | C00/C01/C02 约束 | `agent/security`、`extensions/mcp`、run catalog、model router | 代码已写；生产 composition 仍 MCP-disabled；app 全量未重跑 |
| C04 | C01–C03 | `core/agent-contract`、runtime kernel 等值提取 | 未开始 |
| C05 | C04 | creation receipt、handoff/input tables、Room v29 | 未开始 |
| C06 | C04/C05 | chat tools 纵切、最终回答流式 | 未开始 |
| C07 | C05/C06 | 显式交接、聊天任务引用 | 未开始 |
| C08 | C01–C07 | 共享 UI、Agent 页 Chat 化/third-party 参考 | 设计/部分 UI 已改；真机截图有，instrumentation NOT_RUN |
| C09 | C08 | 集成、真机、升级、恢复、性能 | 未开始 |
| C10 | C09 | 最终证据、维护、回退交接 | 未开始 |
| C11 | 单独授权 | 后续 DSH/third-party 能力路线 | 文档 |

## 4. 唯一验证门禁（截至 01:18：NOT_RUN / BLOCKED）

### 4.1 已有真实结果（分项，不是一次性）

| 项 | 最新结果 | 证据 | 状态 |
| --- | --- | --- | --- |
| `:app:assembleRelease` | **BUILD SUCCESSFUL**，00:06:29，13m1s，945 tasks | `artifacts/build-final5.log:1179-1180` | PASS（该次构建；不绑定当前树） |
| release APK | 01:01:44 为 495,516,800 B；01:19:55 被进行中构建替换为 393,690,027 B | `app/build/outputs/apk/release/` | 产物在变，不能当稳定候选；两次都无保留成功日志 |
| compile | 00:17:14，`BUILD SUCCESSFUL in 13s`（341 tasks：5 executed / 336 up-to-date） | `%TEMP%\mca-verify-compile-9284-000757530.log:451` | PASS（编译调用成功；主要 up-to-date） |
| `:core:agent-contract` | 19 / 0 / 0 @00:17:21 | `core/agent-contract/build/test-results/` | PASS（独立运行） |
| `:core:agent-runtime` | 132 / 0 / 0 @00:17:33（15 XML） | `core/agent-runtime/build/test-results/` | PASS（独立运行） |
| `:feature:agent-workspace` | 118 / 0 / 0 @00:17:44（14 XML） | `feature/agent-workspace/build/test-results/` | PASS（独立运行；套件已变，见 4.2） |
| `:app:testDebugUnitTest`（过滤 DSH） | 26 tests / **1 failed** @00:30:37 | `app/build/test-results/testDebugUnitTest/TEST-com.muyuchat.mca.dsh.McaDshGatewayBackendTest.xml`；`%TEMP%\agent-verify-7044-002913.log:514-525` | **FAIL** |
| `:app:testDebugUnitTest`（全量，历史） | 3079 / 0 / 0 / 11 skipped @22:30:37 | preflight §5（原 XML 已被后续过滤运行覆盖） | 历史/过期 |
| 架构规则 | **ALL 16 CHECKED ARCHITECTURE RULES PASSED**，exit 0 @01:05 前后 | `python tools/architecture/check_architecture_rules.py` stdout | PASS（快照；脚本声明的有限范围） |
| 一次性 gate | — | `verify-code.ps1 -AgentTests -Rerun` | **NOT_RUN** |

架构 16/16 包含 Rules 15/16（DSH contract/workspace）。脚本自身声明“只检查列出的范围”，
**不是全仓或生产链路证明**；且 01:05 之后树仍在变，需要在冻结树上重跑。

### 4.2 单测证据边界（不是 3348 一次性汇总）

- **3348 是四个独立运行的和**：contract 19 + runtime 132 + workspace 118 + app 3079 =
  3348；对应 XML 时间分别为 00:17:21、00:17:33、00:17:44、22:30:37。从未有一次
  `verify-code -AgentTests -Rerun` 产生该汇总，也没有 `VERIFY OK` 记录。
- **app 全量不是 current**：最后一次全量 3079/0/0/11 skipped @22:30:37，之后 app/Agent/DSH
  源码与测试多次改动；当前 `app/build/test-results/testDebugUnitTest` 只剩 00:30 的过滤
  XML，旧 3079 XML 已被覆盖。不得引用 3079 或 3348 作为当前 app 结果。
- **DSH 失败与测试缺口**：00:30:37 的过滤运行为 `McaDshGatewayBackendTest` 26 tests /
  1 failed，失败用例 `gateway generation stop structurally cancels its call without a second
  terminal`（`java.util.NoSuchElementException: List is empty`，`single()` 栈，
  XML 报 `McaDshGatewayBackendTest.kt:474`、栈内 499）。
- 00:44 起 DSH 测试源码被持续改写（`DshTestFixtures.kt`、`DshCallRegistryTest.kt`、
  `McaDshGatewayBackendTest.kt`；后者观测到 **429→502→453 行**、`@Test` 数 22→24 变动，
  01:15:06 仍在写）。失败用例仍存在（01:15 版在 line 244），但 00:30 XML 中触发
  `single()` 的旧断言已被改写为“不产生第二终态”的断言。**没有 00:30 之后的 app 测试 XML
  或成功日志**，所以这是“未验证的修复改写”，不是 PASS。
- 00:31:36 的 `agent-verify-11632-003136.log` 停在 `> Task :app:testDebugUnitTest` 之后，
  没有结果行、没有新 XML，不能算一次运行。
- `AgentHomeHeaderTest.kt` 01:06:37 新增，晚于 00:17:44 的 workspace 118 结果；workspace
  套件也已变化，需要重跑。
- 结论：**app 全量、最新 workspace 套件、一次性 gate 均为 NOT_RUN**；不能用未重跑的源码
  改写、删测试或换断言代替真实运行（硬锁 §12.4）。

### 4.3 当前阻塞（全部解除后才能跑一次性 gate）

1. **活动构建锁 + writer**：01:02:41 起 `:app:assembleRelease` 持锁；DSH evidence 文档在
   01:02–01:03 仍在写；`AgentHomeHeaderTest.kt` 01:06:37 新增；`-uall` 542→558，01:18
   porcelain 由全 `??` 变为 45 ` M` + 37 `??`；本次文档编辑也会改变 worktree 指纹。gate
   在此状态下预期 `input-changed-during-run`。
2. **DSH 测试**：00:30 的失败后测试源码被多次改写（01:15:06 仍在写），但没有重跑证据；
   当前是“未验证修复”。必须由负责人完成修改并跑出 fresh XML，按硬锁 §12.4 审查，
   不能用源码改写代替运行。
3. **app 全量与一次性 gate 未运行**：3348/3245 都不是当前树的单次门禁结果。
4. **发布绑定**：00:06 的 release 构建成功没有与任何现存 APK 建立指纹绑定；当前 APK
   仍在被 01:02:41 的构建重建（01:01:44 495.5 MB → 01:19:55 393.7 MB）。
5. **设备/真实链路**：instrumentation、真机两轮渲染、模型、MCP 真实 server、FGS、SAF、
   TalkBack、性能全部 NOT_RUN/BLOCKED（MIUI 新包安装限制）。
6. **资源**：01:12 实测仅 **0.46 GiB** 空闲 RAM、commit 35.7/40.7 GiB，`C:` **1.3 GiB**
   / `E:` 16.8 GiB；比 23:46 preflight 更紧。full gate 的 OOM/daemon 消失风险更高，
   运行需外部日志与足够生命周期。

### 4.4 Gate 顺序（不变）

代码完成且树冻结后只执行一次，顺序固定：

1. `:app:compileDebugKotlin` / `:app:assembleDiagnostic`（快速诊断变体；release 另跑一次）。
2. `:core:agent-contract:testDebugUnitTest`、`:core:agent-runtime:testDebugUnitTest`、
   `:feature:agent-workspace:testDebugUnitTest`、`:app:testDebugUnitTest`（全量）。
3. `scripts/verify-code.ps1 -AgentTests -Rerun`（唯一 full gate）。
4. 架构 `check_architecture_rules.py` **16/16**。
5. 设备：用同签名 release 更新安装 `98a37aa7`；只跑已有 instrumentation 与手工 smoke，
   MIUI 新包安装/清数据仍 BLOCKED。
6. 所有未执行项写 NOT_RUN/BLOCKED，不允许把编译或单测写成 runtime/device PASS。

## 5. 最新 UI 截图（真机，不是 instrumentation）

| 文件 | mtime | 内容/边界 |
| --- | --- | --- |
| `docs/proposals/agent-v1/evidence/5e912ff9/agent-header-final-2026-10-20.png` | 2026-10-20 01:02:49 | **最新**：Agent 首页，单汉堡 + “Agent”标题、最近 9 条任务（含“已暂停/失败”）、空模型 CTA、贴底 composer；2136×3200 真机截图 |
| `agent-header-optimized-2026-10-19.png` | 2026-10-19 23:40:41 | 旧 header；早于 00:02:10 的 `AgentWorkspaceHomeScreen.kt` 改动 |
| `agent-drawer-optimized-2026-10-19.png` | 2026-10-19 23:41:18 | 旧 drawer；早于 00:02:49 的 `AgentHomeDrawer.kt` 改动 |
| `agent-home-optimized-2026-10-19.png` | 2026-10-19 23:00:45 | `agent-header-review-2026-10-19.md` 已判为 pre-change，不能当 current |
| `agent-home-release-2026-10-19.png` | 2026-10-19 22:50:21 | 更早 release 截图 |

23:40/23:41 两张图与 `agent-header-review-2026-10-19.md` 的几何结论绑定；它们**早于**
00:02 的源码改动和 01:02:49 的最终截图，不能作为当前 header 的渲染证据。以上全部是
手工截图，**不是** instrumentation、两轮视觉验收或可访问性证据。

## 6. 回退

- 本批次只允许回退新卡补丁，不得回退并行会话的 `feature/chat`/设置/DSH 改动。
- MCP 若无法在 C03 内闭环，回退到 base catalog（零 MCP socket），保留管理 UI 与配置。
- 数据库若进入 v29，只允许前向修复，不指导安装旧 APK 清库。
- 本轮只改文档，没有代码回退点；未回退任何并行改动。

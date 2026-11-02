# V1.1 实施状态（2026-10-20 01:18）

> 只记录本轮实际写入和已验证结果；不把编译/单测/runtime 写成 PASS。一次性 full gate
> 仍未运行；下方“已写”与“已验证”严格分开。

> 快照：仓库 HEAD `af24899`（交付/代码基线 `7475fd6`）；工作区为 dirty tree
> （01:18：82 行 = 45 ` M` + 37 `??`，`-uall` 558），详见 `C00_BASELINE.md` §1。

## 已完成（代码已写；证据边界见下）

| 卡 | 交付 | 证据边界 |
| --- | --- | --- |
| C00 | `C00_BASELINE.md`：交付基线 HEAD、工作区、门禁、回退 | 文档（2026-10-20 01:18 已更新） |
| C01 核心 | 引擎 `EngineStopSettlement` + runtime `AgentRunStopSettlement`；STOPPING 不提前释放；17 个测试文件 | runtime 132/0/0 @00:17:33；app 适配已写（23:45–23:53），**无 fresh app 测试** |
| C02 | 聊天 attempt 精确冻结/取消；搜索 CE 传播；29 个测试 | app 全量未重跑；设备 I04–I06 未跑 |
| C03 | MCP 不再自动 mint；仅已有 run grant；release revoke；15 测试 | app 全量未重跑；生产 composition 仍 MCP-disabled |
| 前端 | Agent home 去品牌落地页，改为 Chat 骨架（顶栏/空态/任务行/贴底 composer）；退出入口；详情 tab/顶栏/composer 修复 | 01:02:49 真机截图；**未做 instrumentation/两轮视觉验收** |
| 最终回答 | `RUN_TERMINAL.summary` 在 SUCCEEDED 时写入 `Completed.finalText`（≤1200 字符/4KiB 内） | 单测通过（历史）；当前 app 全量未复跑 |
| third-party | 4 份只读调研：能力图、工具/自动化、聊天 UI 映射、UI 对照 | 未采纳为代码 |
| UI | Agent 单一左上菜单（与 Chat 一致）、抽屉收窄 0.72/300dp、行高 48dp、抽屉底部“返回对话”入口 | release 构建成功；真机截图已取 |

> 表中“未编译/未渲染”类旧标签已不再精确：00:06 有一次 release 构建成功，00:17 有三组
> 模块单测通过。但这些都不是 app 全量或设备证据，因此**没有任何卡升级为 PASS**。

## 本轮已验证（真实执行，分项）

### 构建

- `:app:assembleRelease` **BUILD SUCCESSFUL**（2026-10-20 **00:06:29**，13m 1s，
  **945 tasks**；`artifacts/build-final5.log:1179-1180`）。这是最近一份保留成功日志的
  release 构建；它不绑定当前树。
- release APK 产物在变：01:01:44 曾为 495,516,800 B；01:19:55 被进行中的构建替换为
  393,690,027 B。两次都没有保留成功日志，不能作为稳定候选或指纹绑定证据。
- 01:02:41 起另一次 `:app:assembleRelease` 持锁运行中（pid 28900 / lock pid 14092，
  `lead: settings transition experiment`），截至本快照未结束。

### 单测（**分模块独立运行，不是一次性汇总**）

| 模块 | 最新 XML | 结果 | 说明 |
| --- | --- | --- | --- |
| `:core:agent-contract` | 00:17:21 | **19 / 0 / 0** | 独立运行 |
| `:core:agent-runtime` | 00:17:33 | **132 / 0 / 0**（15 XML） | 独立运行 |
| `:feature:agent-workspace` | 00:17:44 | **118 / 0 / 0**（14 XML） | 独立运行；01:06:37 新增 `AgentHomeHeaderTest.kt`，结果已非最新套件 |
| `:app:testDebugUnitTest`（过滤 DSH） | 00:30:37 | **26 tests / 1 failed** | 失败用例见下；不是全量 |
| `:app:testDebugUnitTest`（全量，历史） | 22:30:37 | 3079 / 0 / 0 / 11 skipped | **历史/过期**；原 XML 已被过滤运行覆盖 |

- **3348 不是一次运行**：19 + 132 + 118 + 3079 = 3348，来自四个不同时间的 XML；
  `verify-code -AgentTests -Rerun` 从未产生该汇总，也没有 `VERIFY OK` 记录。
- **DSH 失败**：`McaDshGatewayBackendTest > gateway generation stop structurally cancels its
  call without a second terminal`，`java.util.NoSuchElementException: List is empty`
  （`single()` 栈；XML 报 `McaDshGatewayBackendTest.kt:474`）。日志
  `%TEMP%\agent-verify-7044-002913.log:514-525`。
- 00:44 起 DSH 测试源码被持续改写（`DshTestFixtures.kt`、`DshCallRegistryTest.kt`、
  `McaDshGatewayBackendTest.kt`；后者观测到 **429→502→453 行**、`@Test` 数 22→24 变动，
  01:15:06 仍在写）。失败用例仍存在（01:15 版在 line 244），旧 `single()` 断言已被改写为
  “不产生第二终态”的断言；但 **00:30 之后没有新的 app 测试 XML**，所以是“未验证修复”，
  不是 PASS。00:31:36 的 `agent-verify-11632-003136.log` 停在
  `> Task :app:testDebugUnitTest`，无结果、无 XML，不能算运行。
- 结论：**app 全量、最新 workspace 套件、一次性 gate 均为 NOT_RUN**；不得用 3079/3348
  或未重跑的源码改写充当当前通过。

### 架构

- `python tools/architecture/check_architecture_rules.py` →
  **ALL 16 CHECKED ARCHITECTURE RULES PASSED（16/16）**，exit 0（2026-10-20 01:05 前后快照）。
- 16 条包含 Rules 15/16（DSH）；脚本只检查其声明的范围，不是全仓/生产链路证明。
  01:05 之后树仍在变（`-uall` 由 542 增至 558），冻结树需重跑。

### 真机

- 01:02:49 截图（`docs/proposals/agent-v1/evidence/5e912ff9/
  agent-header-final-2026-10-20.png`）：Agent 首页实际渲染为单汉堡 + “Agent”标题 +
  最近 9 条任务（含“已暂停/失败”）+ 空模型 CTA + 贴底 composer；无重复返回键。
- 这是**手工截图**，不是 instrumentation、两轮渲染、暗色/TalkBack/IME/性能验收。

## 最新 UI 截图

| 文件 | mtime | 边界 |
| --- | --- | --- |
| `docs/proposals/agent-v1/evidence/5e912ff9/agent-header-final-2026-10-20.png` | 01:02:49 | **当前最新** |
| `.../agent-header-optimized-2026-10-19.png` | 23:40:41 | 旧 header；早于 00:02 源码改动 |
| `.../agent-drawer-optimized-2026-10-19.png` | 23:41:18 | 旧 drawer；早于 00:02 源码改动 |
| `.../agent-home-optimized-2026-10-19.png` | 23:00:45 | 已被 header review 判为 pre-change |
| `.../agent-home-release-2026-10-19.png` | 22:50:21 | 更早 release 截图 |

23:40/23:41 的图与 `agent-header-review-2026-10-19.md` 的几何结论绑定，但**早于** 00:02
的源码改动；不要把它们当作当前 header 的证据。

## 当前阻塞（不是 PASS）

1. **full gate 未跑 / 当前被阻塞**：`verify-code.ps1 -AgentTests -Rerun` 从未执行；
   01:02:41 起有活动 `:app:assembleRelease` 持锁，DSH evidence 与测试仍在写
   （`AgentHomeHeaderTest.kt` 01:06:37；`-uall` 542→558；01:18 porcelain 由全 `??`
   变为 45 ` M` + 37 `??`），本次文档编辑也会改变指纹。
2. **DSH 测试缺口**：00:30 的失败后测试源码被多次改写（01:15:06 仍在写），但没有重跑证据；
   当前是“未验证修复”。必须先完成并跑出 fresh XML，按硬锁 §12.4 审查，不能用源码改写
   代替运行。
3. **app 全量**：最后一次 3079/11 skipped @22:30 已过期；当前 app 结果只有 00:30 的过滤
   DSH 失败。workspace 套件也已被 01:06 新增测试改变。
4. **本地模型**：运行时绑定与 sampling 修复后，Qwen3.5 0.8B 能生成；但严格 CONTROL JSON
   仍 `PROTOCOL_NOT_OBJECT`。已实现“一次 repair 后仍非 JSON 时，把可见文本作为 final
   answer（不执行任何工具/审批）”的兼容回退；release 已包含，但尚未在真机完成一次回退
   后的完整任务。
5. **4B QAIRT** 仍 native worker 加载退出；未解决。
6. **C01 应用接线**已写（`AgentStopProofSource`、`McaAgentLocalModelAdapter`、
   `MainRuntimeAssembly`、`AgentRuntimeAssembly`），但**没有 fresh app 测试**；不能写 PASS。
7. **C04–C11**：未开始；third-party 仅完成只读调研，未采纳为生产代码。
8. **设备**：MIUI 新包安装 `INSTALL_FAILED_USER_RESTRICTED`；instrumentation、真实模型/
   云、MCP 真实 server、FGS/SAF/TalkBack/性能均 NOT_RUN/BLOCKED。
9. **资源**：01:12 实测仅约 **0.46 GiB** 空闲 RAM（commit 35.7/40.7 GiB），`C:` 1.3 GiB；
   Kotlin 编译与 full gate 的 OOM 风险比 preflight 时更高。

## 一次性验证（待执行，当前 NOT_RUN）

代码冻结后按 `C00_BASELINE.md` §4 的唯一顺序执行。开始前必须满足：无活动锁/writer、
DSH 测试缺口有结论、app 全量可跑。运行后按模块 XML 写实际数字，**不要以 3348/3245 为
期望值**；指纹变化即 `input-changed-during-run`。

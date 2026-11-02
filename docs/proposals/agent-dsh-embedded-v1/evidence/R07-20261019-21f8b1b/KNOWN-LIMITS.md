# 修复轮已知限制（必须随报告保留）

- Run ID：`R07-20261019-21f8b1b`
- 状态：限制生效中；不因 C1–C7 或 E1–E9 通过而消失
- 来源：Lead 明确要求 + 修复交接文档 §6 + [BASELINE.md](../../BASELINE.md) / [ADR 0003](../../../../adr/0003-dsh-embedded-dual-agent-modes.md)

> **2026-10-20 02:38 更新**：官方主对话区白屏（`BLOCKER-WHITE-PANE`）与选择器裁剪（`BLOCKER-PICKER-CLIPPING`）均已修复，A3 独立验证官方 hero/composer、默认工作区、picker 90/360px 与真实 adb tap 切换；两项已关闭。**但 DSH 聊天仍不可用**：本地模型回合 `n_ctx=8192` **FAIL**，云端上游 503 **BLOCKED external**。本文件的 PTY/sandbox/隔离/模型刷新/原生 sharp/全离线限制继续有效，并新增本地上下文与云端阻断两项。详见 [R07-REPORT.md](R07-REPORT.md) §5/§7 与 [E1-E9-MATRIX.md](E1-E9-MATRIX.md) §2。

> 这些限制不是“待优化项”，而是当前证据边界。报告、UI 文案、发布说明都不得越过它们宣称能力。

## 1. 汇总

| ID | 限制 | 当前状态 | 一句话结论 |
| --- | --- | --- | --- |
| LIMIT-PTY | 真 PTY | **FAIL** | 当前 Android 宿主/payload 没有真 PTY；普通 pipe 不是 PTY，终端/PTY 不得写 PASS |
| LIMIT-SANDBOX | Android sandbox | **fail-closed / workspace-write** | 有效模式为 workspace-write；不宣称完整 sandbox 强制，审批保持 ask |
| LIMIT-ISOLATION | 真隔离 | **不宣称** | 单 APK 同 UID；插件是应用内受信任可执行代码，不是 OS 隔离 |
| LIMIT-MODEL-REFRESH | 模型启动后刷新 | **未验证** | Seeder 只在启动时生成 overlay；官方 UI 是否自动发现新模型未证明 |
| LIMIT-LOCAL-CTX | 本地模型回合 | **PASS WITH TUNABLE REQUIREMENT** | n_ctx=16384 PASS；12288 FAIL；要求 n_ctx ≥ 16384（tunable），不是 8192 可用 |
| LIMIT-CLOUD-503 | 云端模型回合 | **BLOCKED external** | 上游 503 无可用渠道；属外部阻断，但也不是成功回合 |
| LIMIT-LOCAL-QUALITY | 本地模型输出质量 | **QUALITY CAVEAT** | 0.8B 输出为冗长离题拒绝；MODEL_QUALITY_ONLY，不是 protocol/loop failure |
| LIMIT-SHARP | 原生 sharp | **不宣称** | 参考 payload 用纯 JS 替换；原生图片链路不得写 PASS |
| LIMIT-OFFLINE | 全离线 | **不宣称** | 云模型、远端 MCP、搜索、在线插件安装需要网络 |

### 1.1 本地模型上下文修复 memo（2026-10-20）

- 默认值：`MnnRuntimeParameterAdapter.runtimeDefaults` 的 `n_ctx=8192`（`core/engine/.../MnnRuntimeParameterAdapter.kt:22`）。
- DSH 本地 agent turn 需要约 9.8k tokens 的 system prompt + 31 tool schemas 才到用户输入，超 8192；结果是 `PI_AI_ERROR`、0 tokens（A3 evidence）。
- **A4 最终结果**：n_ctx=12288 仍 FAIL（`PI_AI_ERROR`，catalogRevision `97d816d721535529`）；n_ctx=16384 **PASS**（真实回合完成：1轮1步、1分54秒、5.8 tok/s、6.7K tok；engine `context=16384 backend=mnn_cpu`；catalogRevision `645ec67dd7def7a6`）。
- **要求**：对该 Qwen3.5 0.8B MNN + 完整标准 DSH prompt，**n_ctx ≥ 16384（tunable）**；12288 不足。KV-cache 成本约 0.79 GB（Lead memo），设备有余量。
- **质量 caveat**：0.8B 输出是长离题拒绝，不是 `DSH-OK`；分类 **MODEL_QUALITY_ONLY**，不得写成协议失败，也不得写成质量可接受。
- **状态**：**PASS WITH TUNABLE REQUIREMENT**，已由 FINAL-ONE-PASS 在 `0A731D84…` 上复验（local turn 1分19秒 / 6.6K tok；`context=16384 backend=mnn_cpu`）。
- **备选**：使用已记录的 reduced agent preset（更少工具/更短 system prompt），但会损失能力。
- **最终验证（PASS）**：n_ctx=12288 FAIL；n_ctx=16384 PASS；采用 **n_ctx ≥ 16384** 作为该模型完整标准 DSH prompt 的最低可用配置。不得用“提供了参数入口”替代成功回合。

## 2. 逐项说明

### LIMIT-PTY：真 PTY FAIL

- 当前事实：Lead 确认 **PTY FAIL**。参考 payload 以 `child_process` 替换 `node-pty`，因此该 payload 不提供真 PTY；普通管道、伪终端回显或 resize 空操作都不能算 PTY。
- 不得宣称：终端“已可用”“PTY 已通过”“resize/进程组正常”。
- 影响：C2/C3 的宿主测试、E3/E5 的页面恢复、以及完整 R07 的 V18（终端/PTY）都只能记录 `FAIL`/`NOT_RUN`；任何 PASS 需要真实 PTY 证据。
- 改变状态所需证据：Android Bionic 上真实 PTY 设备的创建、输入/输出、窗口尺寸变更、Ctrl-C、进程组清理与子进程树消失的原始日志/录屏；绑定设备与 APK。
- 当前证据：Lead 确认；具体探针日志待附。**未附前维持 FAIL/限制。**

### LIMIT-SANDBOX：Android sandbox fail-closed（workspace-write）

- 当前事实：Android sandbox 以 fail-closed 方式工作，有效权限模式为 **workspace-write**；越界路径应被拒绝或不受支持。审批策略保持 `ask`。
- 不得宣称：完整 sandbox 强制、任意路径可写、`danger-full-access` 默认、或“审批/sandbox 已实现 OS 隔离”。
- 影响：文件工具、插件安装、MCP/子进程、E2/E3/E8 的可用路径都受此边界约束；越界失败应记录为真实限制，不是“没测到”。
- 改变状态所需证据：具体失败码/拒绝日志、允许/拒绝路径矩阵、审批交互原始记录；若未来引入强隔离，需新 ADR。

### LIMIT-ISOLATION：真隔离不宣称

- 当前事实：单 APK + 同 UID 的 DSH 宿主只隔离崩溃和部分生命周期，不阻止插件使用 Node `fs/net/process` 访问同 UID 资源；官方 DSH `SAFETY.md` 明确其未安全审计。
- 不得宣称：恶意插件隔离、凭据/数据库不可达、OS 级 sandbox、企业级安全边界。
- 影响：所有“安全通过”结论只能是具体机制在具体产物/设备上的观察；C1–C7 通过不代表安全。
- 改变状态所需证据：额外 UID / 独立进程 / 独立签名 runtime 等机制的真实探针与威胁模型；在此之前保持“不宣称”。

### LIMIT-MODEL-REFRESH：模型启动后刷新未验证

- 当前事实：`DshGatewayConfigSeeder` 只在运行时启动时生成 provider overlay；`DshRuntimeOwner.restart()` 在宿主已 READY 且存活时可能直接返回当前代，不能据此推断重新播种。官方 provider 的配置合并/模型发现/刷新协议尚未核对。
- 不得宣称：“加载模型后 DSH 自动出现”“restart 即可刷新”“模型接入全部完成”。
- 影响：E9 必须记录真实观察；若官方 UI 未发现新模型，保留限制并给出后续方案（复用官方扩展点；或显式刷新命令；用户接受中断且 owner 停稳后才 stop→ensureStarted）。
- 改变状态所需证据：E9 前后截图、网关 `/models` 与官方 UI 对照、官方协议路径；必要时新 owner 契约与测试。

### LIMIT-SHARP：原生 sharp 不宣称

- 当前事实：参考 payload 把 sharp 替换为纯 JS 实现；没有原生 libvips 加载/解码证据。
- 不得宣称：原生图片处理、原生 sharp、图像链路完整对标。
- 改变状态所需证据：Android Bionic 上 libvips/sharp 真实加载与解码的日志/产物；在此之前保持 NOT_RUN/限制。

### LIMIT-OFFLINE：全离线不宣称

- 当前事实：本机运行不等于全离线；云模型、远端 MCP、搜索与在线插件安装需要网络。强离线开关必须覆盖整个 DSH/plugin 进程网络出口。
- 不得宣称：“完全离线”“无网络也可完整 DSH”。
- 改变状态所需证据：覆盖整个进程网络出口的机制与抓包/断网测试；仅限制模型网关不足。

## 3. 与 C1–C7 / E1–E9 的关系

- C1–C7 是修复回归门；即使全绿，也不能消除上述限制，更不能证明 E1–E9。
- E1–E9 是修复后的入口/恢复证据；E2 可以证明“空目录也能打开前端”，但不能证明“模型刷新/推理完整”。
- 报告必须在本文件基础上逐项引用限制；任何 PASS 结论不得与限制冲突。
- 若限制状态变化，必须附新证据并按 evidence/README 的不可覆盖规则新增文件，不能直接改历史结论。

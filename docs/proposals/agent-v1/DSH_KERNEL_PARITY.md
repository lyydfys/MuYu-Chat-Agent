# DSH 内核能力对标与复刻路线（DSH Kernel Parity）

- 文档类型：只读对标分析（不是实现计划、不是验收报告、不宣称 parity）。
- 分析日期：2026-10-19（Asia/Shanghai）。
- MCA 工作区：`E:\MAC`，HEAD `2368c72`，工作区含未提交改动（本次未触碰、未回滚、未 stash）。
- MCA Agent 设计/证据基线：`docs/proposals/agent-v1/MCA_AGENT_V1_ARCHITECTURE.md`、`docs/proposals/agent-v1/evidence/5e912ff9/*`（证据基线 `5e912ff9`，当前源码已推进到 Room v28 / K15）。
- DSH 参照：只读克隆 `C:\Users\example\AppData\Local\Temp\dsh-android`，HEAD `98162a2`（App v1.20.1，内核 `0.2.1-rc.2`；`README.md:11`、`CHANGES.md:38-44`）。未构建、未安装、未修改。
- 本次执行：仅静态阅读源码/文档；**未**运行 Gradle、`verify-code.ps1`、`adb`、任何测试或设备操作；**未**创建/修改除本文件外的任何文件。
- 阅读范围：MCA 的 `core/agent-contract`、`core/agent-runtime`、`app/.../agent/**`（约 22.7k 行）、`feature/agent-workspace/**`（约 10.1k 行）中与 owner/loop/context/tools/approval/egress/MCP/Skills/platform/composition/route/VM 相关的文件；**不是逐行 100% 覆盖**，结论以本文引用的行号为准。DSH 克隆只含 Android 壳、内核补丁 overlay、插件、配置与文档；内核主体以 `@deepseek-ai/dsh` npm 包随 payload 分发，不在克隆源码树内。

> **English summary.** This is a structural (source-level) comparison only; no behavioral parity is claimed. MCA already has stronger per-call approval/TOCTOU controls, egress grants, quotas, and durable audit facts than the DSH Android package shows. What MCA genuinely lacks is DSH's "durable session" layer: persisted conversation transcripts with crash resume, context compaction, broader tools + native tool calling, goal/plan/todo rounds with auto-continuation, and subagents. The top five gaps are ranked in §5; the 3-phase roadmap is in §8. No Gradle, device, model, or MCP server was run; everything runtime-level is `NOT_RUN`, device install is `BLOCKED`, and DSH MCP/Skills/Memory details are `UNKNOWN` where the read-only clone has no evidence.

> 结论先行：MCA 在“审批/冻结命令/一次性消费/egress/配额/导出”这些**安全与数据**维度上已经超出 DSH Android 包的可核验深度；真正缺失的是 **DSH 内核作为“可持续会话系统”的那一层**——持久会话+恢复、上下文压缩、目标/计划、子代理、插件化工具、跨会话记忆。这是架构缺口，不是 UI 缺口。第 5 节按用户可见影响与架构风险排序。

---

## 0. 证据等级与判定词

本文件不使用“整体 PASS/完成”这种说法。每条结论都绑定证据等级：

| 标记 | 含义 | 可信度 |
| --- | --- | --- |
| `[MCA-SRC]` | 当前 checkout 源码事实，给出 `path:line`。**本文未标 `[MCA-EVID]` 的 MCA 结论均属此类** | 高（静态） |
| `[MCA-EVID]` | 既有证据文档的声明；如与源码冲突，第 6 节单列 | 中（可能过期） |
| `[DSH-REPO]` | 只读克隆中的文件与行号 | 高（静态） |
| `[DSH-OBS]` | 当前运行的 DSH 会话中直接可见的能力面（工具目录、运行模式）；可复现，但非仓库源码证据 | 中 |
| `[UNKNOWN]` | 现有只读材料无法判定；不推测、不补全 | 低 |

判定词（仅用于“机制是否存在/是否同构”）：

- `EQUIVALENT`：机制结构、状态归属、边界基本同构，**行为未验证**。
- `PARTIAL`：有机制，但缺少决定语义的关键部分。
- `MISSING`：源码中没有该机制。
- `UNKNOWN`：至少一侧无足够证据。
- `DIFFERENT`：两侧都成立，但模型不同，不能互称等价。

**Parity 定义**：本文件的“对标/parity”只指 *内核机制与状态契约的结构等价*。真实模型、真实设备、真实 MCP server、真实网关的行为等价一律 `NOT_RUN`。

> 引用约定：为避免重复，正文中的 MCA 引用写成“文件名 + 行号”，完整模块路径前缀见 §10-A；DSH 引用写成“克隆内相对路径 + 行号”。

---

## 1. 执行摘要

### 1.1 三句话结论

1. MCA 的 Agent V1 是**任务制（一个任务一个目标）、单活跃 run、强审批、可审计**的受限 Agent；DSH 内核是一个**持久会话、可压缩、可续跑、可委派、可插件扩展**的通用 Agent 运行时。
2. 两者在“工具调用安全”上并非 MCA 落后：可核验范围内，MCA 的冻结命令 + 一次性审批 + policy/epoch 失效 + egress 数据分级，比 DSH Android 包中可见的 `approval/asked|decided|policy` 事件模型绑得更细。但在“Agent 能不能持续工作”上，MCA 落后一个量级。
3. 排名最高的五个真实缺口是：**① 持久会话与进程重启续跑；② 上下文压缩；③ 工具广度与本地原生 tool calling；④ 目标/计划（plan/todo/rounds）与自动续跑；⑤ 子代理/工作流**。插件体系（Cordis）是风险最高但应最后做的第 6 项，因为 `MCA_AGENT_V1_ARCHITECTURE.md` §12.3 的 Host 决策门尚未满足。

### 1.2 能力面计数（仅结构判定）

| 能力域 | DSH 侧证据强度 | MCA 侧 | 判定 |
| --- | --- | --- | --- |
| Sessions（持久会话/恢复/fork） | 强（`[DSH-REPO]`） | 任务/run 事实持久，会话 transcript 不持久 | `PARTIAL` |
| Agent loop | 强（事件分类） | 确定性 prompted loop | `PARTIAL` |
| Tool registry | 强（插件 defineTool） | 4 内置 + MCP，不可插件扩展 | `PARTIAL` |
| Tool approval | 中（事件+preset） | 冻结命令+一次性 CAS+TTL | `DIFFERENT`/MCA 更细 |
| Skills | 弱（仅运行面） | SKILL.md 子集+收窄 | `UNKNOWN`（DSH）/`PARTIAL`（MCA） |
| Plugins（Cordis） | 强 | 无（决策门未过） | `MISSING` |
| MCP | 无仓库证据（仅运行面命名） | Streamable HTTP 客户端+registry+egress | `UNKNOWN`（DSH）/`PARTIAL`（MCA） |
| Context compaction | 强（compaction 事件族） | 无；溢出即暂停 | `MISSING` |
| Goal / plan | 强（goal/plan/todo 事件） | 仅 task.goal 字符串 | `PARTIAL` |
| Subagents / teams | 强（subagent/team 事件+session origin） | 无（E05 计划中） | `MISSING` |
| Memory | 中（持久日志+跨会话检索运行面） | 无跨任务记忆 | `MISSING` |
| Permissions / sandbox | 强（preset+sandbox mode） | 固定策略+egress+路径守卫 | `DIFFERENT` |
| 平台（FGS/通知/恢复/配额/导出） | 中 | 有，且配额/导出更正式 | `EQUIVALENT`（源码层） |

> 这张表不是分数，也不是“完成度”。它只回答“机制是否存在、是否同构”。

---

## 2. DSH 内核能力清单（带证据）

DSH 的形态：Android 壳（MainActivity/权限引导/WebView）+ Node 内核（`dsh/lib/bin.js web`，监听 `127.0.0.1:3080`）+ Cordis 插件组合。`[DSH-REPO] docs/开发指南.md:89-102`、`dsh-patches/README.md:21-35`。前端通过 `POST /api/session.prompt` 发消息、`ws://127.0.0.1:3080/api/events.mux` 收流式回复（`dsh-patches/README.md:32-34`）。

### CL-01 Sessions（持久会话、恢复、fork/steer/queue）

- **持久化**：每个会话一个 append-only JSONL 日志，默认 zstd 校验帧；按 generation 保存 `session.vN.jsonl.zstd`，历史 generation 只读迁移。`[DSH-REPO] dsh-patches/overlay/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/README.md:12,56-68,80`
- **崩溃语义**：已提交前缀不重写；截断的末尾行丢弃；torn zstd 帧只取完整解码记录并在下一批前重写；恢复读取会补 synthetic closers。`[.../README.md:74]`
- **恢复后模型语义**：恢复的会话保留历史；「有 durable call 无 result」→ `TOOL_OUTCOME_UNKNOWN`，提示模型只重试只读/幂等动作并核对副作用；「有 assistant request 无 durable call」→ `TOOL_NOT_STARTED`。`[.../README.md:133-141]`
- **并发写者**：同一会话单写者（进程内 claim + kernel flock/semaphore），崩溃持有者的锁随进程消失；无删除 API，日志只能外部清理。`[.../README.md:157-160]`
- **会话操作面**：controller 暴露 create/list/fork/steer/queue，错误码含 `session/fork-unavailable`、`session/steer-unavailable`、`session/queue-item-not-found`、`session/conflict`、`session/writer-held`。`[DSH-REPO] dsh-patches/overlay-021/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js:246,874,1017,1036,1082`
- **fork 幂等**：上游 `origin` schema 只允许 `subagent`；分支连点/慢网重试由内核按 `(sessionId, atSeq)` 做 5 秒幂等，复用同一个子会话 id。`[DSH-REPO] CHANGES.md:54-58`
- **header**：session header 有 `origin?: 'subagent'`。`[DSH-REPO] .../lib/types/format.d.ts:50`

### CL-02 Agent loop（turn/step/attempt/tool/retry/hook）

DSH 持久化的会话事件分类覆盖 loop 与外围能力（`worker.cjs:2369-2421`）：

```
approval/asked, approval/decided, approval/policy,
assistant/attempt, assistant/message, assistant/chunk,
command/run, command/done,
compaction/start|summary|prune|end,
deliverables/presented, feedback/*,
goal/change, hook/invoked, hook/result,
llm/retry, llm/retry-started, model/selection,
permission/preset, plan/mode, request/context, request/header,
sandbox/mode, schedule/change, session/title, step/start|end,
subagent/catalog|descriptor|model-selection-policy, system/message,
team/*, todo/write, tool-workflow/*, tool/call, tool/ptc-dispatch, tool/result,
turn/start|end, user/message, web/deepseek-search-llm-request
```

`[DSH-REPO] .../dsh-session-persistence-jsonl/lib/worker.cjs:2369-2421`

- 事件必需字段给出了语义：`approval/asked` = `id,toolName`（可选 `callId,reason`）；`approval/decided` = `id,outcome`；`approval/policy` = `policy`。`[worker.cjs:6219-6221]`
- `model/selection` = `provider,model`（可选 `reasoningEffort`）；`permission/preset` = `preset`；`plan/mode` = `active`；`sandbox/mode` = `mode`。`[worker.cjs:6303-6308]`
- `turn/start|end`、`step/start|end`、`assistant/chunk` 说明 token 流与步骤边界是内核事件，不是前端临时状态。`[worker.cjs:6222-6231,6329-6330]`
- Programmatic Tool Calling：`tool/ptc-dispatch`、`tool/ptc-dispatch-start`，并由 `dsh-ptc-runtime-node` 提供 runtime；`workflow` 工具依赖 `workflow-ptc`。`[DSH-REPO] config/cordis.patch.yml:8-13`；事件 `[worker.cjs:2414-2416]`
- 启动插件表把 loop 作为内核入口：`requiredStartupEntryIds` 含 `agent-loop`、`webserver`、`web-runtime`、`modules`、`connection`、`headless-runner`、`acp`、`sdk-jsonrpc-server`。`[DSH-REPO] dsh-patches/overlay-021/.../dsh-app-boot/lib/index.js:4270-4279`

### CL-03 Tool registry（插件注册工具）

- 插件 = npm 包，核心是 `ctx.tools.register(defineTool({...}))`，在 `cordis.patch.yml` 的 `insert:` 注入。`[DSH-REPO] docs/开发指南.md:192-199`、`plugins/README.md:14-25`
- 组合配置里可见的工具体：`bash-local`、`tool-shizuku`、`tool-android`、`tool-accessibility`、`tool-vscreen`、`tool-browser`；`bash` 的 `cwd` 来自 `DSH_WORKSPACE`，`timeoutMs: 60000`。`[DSH-REPO] config/cordis.patch.yml:59-79`
- Bash 语义：每条命令 fresh non-login `bash -c`，无 rc；应用 cwd/timeout/output caps，超限输出落 spill file；**不提供隔离**，需要隔离时另配 `dsh-bash-sandbox`。`[DSH-REPO] .../dsh-bash-local/README.md:12`
- Android 工具走 Shizuku 特权通道，shell 权限上限是 uid=2000（不能读其他 App 私有数据、不能改 /system）。`[DSH-REPO] plugins/README.md:38-41`、`docs/开发指南.md:209-213`
- `fs-search` 工具包（read/glob/grep）存在。`[DSH-REPO] dsh-patches/overlay/.../dsh-tool-fs-search/README.md`

### CL-04 Tool approval + permissions

- 权限 preset：`read-only` / `workspace-write` / `danger-full-access`，每个 preset 同时给 `sandbox` 与 `approval`（`ask` 或 `never`）；Android 默认 `danger-full-access`。`[DSH-REPO] config/cordis.patch.yml:46-58`
- 沙箱能力：`dsh-sandbox-local` 通过 `node-addon-system` 的 `landlock-run` 探测，真正隔离只在 `confine()` 被调用时 fail-closed；默认 preset 不会走到。`[DSH-REPO] config/cordis.patch.yml:8-18`
- 危险 Android 操作可恢复逐次确认：`SHIZUKU_APPROVE=ask` 时 install/uninstall/clear/grant/revoke/force_stop/put/tap/swipe/text/keyevent 弹审批。`[DSH-REPO] plugins/README.md:27-30`
- 会话层审批事件与批量决策：`approval/asked`、`approval/decided`、`approval/policy`。`[DSH-REPO] worker.cjs:6219-6221`

### CL-05 Skills

- 仓库材料中没有 SKILL.md/技能解析器；仅见 app-boot 插件表里的 `@deepseek-ai/dsh-experimental-badge-skill-bundle`。`[DSH-REPO] dsh-patches/overlay-021/.../dsh-app-boot/lib/index.js:583`
- `[DSH-OBS]` 当前运行会话提供 `skill` 工具与 `available_skills` 目录（系统提示注入），即“技能 = 可加载的指令集”，按需加载而非一次性塞入上下文。
- 结论：DSH skills **存在（运行面）**，但本只读克隆无法证明其格式、权限、执行边界。`UNKNOWN`（细节）。

### CL-06 Plugins（Cordis 组合）

- 组合即插件表：`- id: ... disabled: true` 禁用、`- insert:` 注入；插件包可依赖原生模块，需像 4 个 Android 补丁一样适配。`[DSH-REPO] config/cordis.patch.yml:25-45,59-79`、`dsh-patches/README.md:84-95`
- 插件安装：`dsh plugin --profile web add <包名>`（内部转 pnpm）；插件挂载失败会写 `$DSH_HOME/logs/plugins.log`（CHANGES v1.15.9）。`[DSH-REPO] dsh-patches/README.md:84-95`、`CHANGES.md:938`
- `[DSH-OBS]` 运行面提供 `plugin_manager`（list/install/remove/版本豁免）、`plugin_market`、`cordis_inspect_list/query`，即“插件 = 可安装的能力包 + 可查询的宿主契约”。

### CL-07 MCP

- 本只读克隆中**没有** MCP client/协议源码或文档：检索 `modelcontextprotocol`/`tools/list` 为 0；`jsonrpc` 只命中内核启动项 `sdk-jsonrpc-server`（`dsh-app-boot/lib/index.js:4270-4279`），不构成 MCP 证据。`[UNKNOWN]`
- `[DSH-OBS]` 当前运行会话的系统提示引用 `mcp__github__*` 形式的 MCP 工具，并说明 GitHub 优先走 MCP；说明部署侧具备 MCP 工具命名空间与接入能力，但无法从本克隆证明 client 协议版本、transport 与审批模型。
- 结论：**不能宣称 DSH 的 MCP 与 MCA 等价或更强**；只能记录“DSH 部署存在 MCP 面”。

### CL-08 Context compaction

- 事件族：`compaction/start`、`compaction/summary`、`compaction/prune`、`compaction/end`。`[DSH-REPO] worker.cjs:2375-2378`
- `compaction/start` = `compactionId,turn`（可选 `sourceCommandId`）；`compaction/end` = `compactionId,turn`（可选 `sourceCommandId,error`）。`[worker.cjs:6238,6244]`
- `compaction/summary` = `compactionId,summary,shadowedRange,shadowedSeqs,shadowedTokenCount,provider,model`（可选 `maxTokens,usage,rawOutput,llmStreamCall,...`）；`compaction/prune` = `shadowedRange,shadowedSeqs,shadowedTokenCount`。`[worker.cjs:6239-6259]`
- 这证明 DSH 的压缩是**内核一等事件**：有被遮蔽的 seq 区间、token 计数、摘要模型与用量，而不是 UI 折叠。

### CL-09 Goal / plan / todo

- `goal/change` = `kind,version,operation`（可选 `goal,roundsStarted,createdAt,updatedAt,cleared,clearedAt`）。`[DSH-REPO] worker.cjs:6261-6272`
- `plan/mode` = `active`；`todo/write` = `todos`。`[DSH-REPO] worker.cjs:6305,6365`
- `[DSH-OBS]` 运行面有 `create_goal`/`update_goal`/`get_goal`（含 round 上限与自动续跑）、`todo_write`、`exit_plan_mode`，与事件族一一对应。

### CL-10 Subagents / teams

- Session header `origin?: 'subagent'`。`[DSH-REPO] .../format.d.ts:50`
- `subagent/descriptor` = `mode,version,provider`（可选 `label,agentProvider,agentModel,agentReasoningEffort,persona,toolFilter`）；`subagent/model-selection-policy` = `allowedModels`；`subagent/catalog`。`[worker.cjs:6331-6343,2401-2403]`
- `team/member`、`team/task`、`team/message/queued|delivered`。`[worker.cjs:2405-2408,6360-6364]`
- controller 错误码 `subagent/unauthorized`、`subagent/catalog-diagnostic`、`subagent/not-found`，说明子代理有独立授权与目录诊断。`[DSH-REPO] dsh-api-session-controller/lib/index.js:1699-1701`
- `tool-workflow/run-start|agent-start|agent-end|run-end` 是工作流执行的持久事实。`[worker.cjs:2410-2413]`
- `[DSH-OBS]` 运行面有 `subagent`、`subagent_fork`、`workflow`（脚本化多代理编排）、`list_agents`、`send_message`、`interrupt_agent`。

### CL-11 Memory

- 仓库层：只有会话持久日志（CL-01），**没有**独立 memory 子系统证据。`[UNKNOWN]`
- `[DSH-OBS]` 运行面有 `session_search`、`session_event_search`（跨会话检索历史事件）与 `session_trace`（会话谱系）。这属于“可检索历史”，不等于“人格/偏好记忆”。
- 结论：DSH 的“记忆”= 持久事件日志 + 跨会话检索；是否存在独立的长期记忆库 `UNKNOWN`。

### CL-12 Permissions / sandbox

- 见 CL-04。补充：权限 preset 的变更本身是会话事件 `permission/preset`，sandbox 变更也是 `sandbox/mode`；说明 DSH 把“权限模式”作为**会话状态**而非一次性弹窗。`[worker.cjs:6304,6308]`
- 本地服务鉴权：4 个绑 `127.0.0.1` 的服务一律要求令牌；引擎首页 URL 带一次性 token。`[DSH-REPO] README.md:28`、`android-app/src/com/deepseek/harness/MainActivity.java:78-81`

### CL-13 平台（FGS/通知/恢复/会话治理）

- 前台保活 + 任务完成通知。`[DSH-REPO] README.md:44-45`；FGS 启动与通知渠道见 `MainActivity.java:2504,2910,3122`
- 会话治理：删除进回收站、彻底删除单独确认、坏附件只降级一条消息、会话修复页。`[DSH-REPO] README.md:18,26`
- 救援：安全模式启动、导出全部数据/从备份导入、内核自检与自愈账本。`[DSH-REPO] README.md:27,42`
- 引擎手动启动；`settings.yaml`/`.credentials.yaml` 属用户数据不被覆盖。`[DSH-REPO] docs/开发指南.md:104-108`

### CL-14 模型协议选择

- `model/selection` 事件记录 provider/model/reasoningEffort；session controller 负责会话与模型选择。`[DSH-REPO] worker.cjs:6303`
- `[DSH-OBS]` 运行面按模型能力派发工具调用（原生 tool calling + PTC），并可在会话内切换模型/推理强度。

---

## 3. MCA 对标总表（机制 → 证据 → 判定）

| # | DSH 能力 | MCA 现状（源码） | 判定 |
| --- | --- | --- | --- |
| 1 | 持久会话 + 恢复 + fork/steer/queue | Task/Run 事实持久；transcript 仅内存；无恢复、无 fork/steer/queue | `PARTIAL`（缺关键语义） |
| 2 | turn/step/attempt + 流式 chunk + retry + hook + PTC | 确定性 prompted loop；step/attempt 事件持久；progress sink 生产端为空；无 retry/hook/PTC | `PARTIAL` |
| 3 | 插件注册工具（bash/fs/android/browser） | 4 个内置工具 + MCP 适配器；不可插件扩展 | `PARTIAL`（广度）/`MISSING`（扩展机制） |
| 4 | 审批事件 + preset（ask/never + sandbox） | 冻结命令 + 一次性审批 + TTL + policy/epoch 失效 + deny loop | `DIFFERENT`，MCA 单次更严格 |
| 5 | Skills（运行面可加载指令集） | SKILL.md 子集、ZIP/目录/SAF 导入、allowed-tools 收窄、快照、撤销失效审批 | `PARTIAL`；DSH 细节 `UNKNOWN` |
| 6 | Cordis 插件 | 无；§12.3 Host 决策门 5 条未满足 | `MISSING`（有意延后） |
| 7 | MCP | Streamable HTTP 客户端 + registry + run-scoped egress grant + schema drift 回调 | `PARTIAL`（仅 tools/HTTP） |
| 8 | Context compaction | 无；溢出转 `CONTEXT_LIMIT` 暂停；仅 16 KiB 工具结果截断 | `MISSING` |
| 9 | Goal/plan/todo/rounds | 仅 `AgentTask.goal` 不可变字符串；runOrdinal 续跑；无 plan/todo/rounds | `PARTIAL` |
| 10 | Subagents/teams/workflow | 无；E05 计划父子 run、深度 1、最多 2 child | `MISSING` |
| 11 | Memory | 无跨任务记忆/检索；仅单任务 workspace+artifacts | `MISSING` |
| 12 | Permissions/sandbox | 固定策略：读 grant、写/网/外呼 ASK、egress origin+data class、逻辑路径守卫 | `DIFFERENT` |
| 13 | FGS/通知/恢复/配额/导出 | lease、guard、通知停止、启动对账（不自动续跑）、256 MiB/1 GiB 配额、任务包导入导出 | `EQUIVALENT`（源码层） |
| 14 | 模型选择/原生 FC/PTC | 本地 prompted；云 Responses+supportsTools 才 NATIVE；无 PTC | `PARTIAL` |

---

## 4. 逐域对标与证据

### 4.1 Sessions

**DSH**：会话是持久日志 + 可恢复事件流；恢复时用 `TOOL_NOT_STARTED`/`TOOL_OUTCOME_UNKNOWN` 显式标注中断，而不是假装工具成功（`session-persistence/README.md:74,133-141`）。会话可 fork/steer/queue，写者互斥（`dsh-api-session-controller/lib/index.js:246,874,1017,1036,1082`）。

**MCA**：

- 任务、run、步骤、调用、审批、事件、产物都在 Room：`AgentExecutionEntities.kt:18-59`（attempts）、`:61-91`（steps）、`:100-146`（invocations）、`:155-201`（approvals）；事件类型见 `core/agent-contract/.../AgentEvents.kt:39-58`。
- **模型可见的 transcript 只存在于内存**：`AgentRunOwner.kt:76-78` 持有 `suspended`/`pendingApprovals`；回答与审批续跑都必须命中内存 transcript（`AgentOwnerInteractionCommands.kt:140-144`、`:216-220`），否则返回 `invalidState`。
- **进程重启不续跑**：`AgentStartupRecovery.kt:31` 明确 “never resumes a run”；它只把非终态记录结算成 `PAUSED`/`NEEDS_REVIEW`（`:101-124`）。审批中重启是“安全但不可继续”的语义：pending approval 被置 `EXPIRED`、invocation 置 `NOT_EXECUTED`、run 置 `PAUSED(APPROVAL_EXPIRED)`（`:150-206`），不会自动批准。
- **“继续”不复用历史**：`ContinueAgentTask` 创建新 run，代码中没有把上一 run transcript 注入（`AgentOwnerInteractionCommands.kt:83-98`，`AgentLoopResume` 只在回答/审批时构造，`:171-179`、`:247-257`）。
- 没有 fork/steer/queue/title；容量是 1 active + 5 queued（`AgentContract.kt:26-27`）。

**结论**：MCA 的持久化是“审计事实持久”，DSH 是“会话可续跑”。这是第 1 号缺口。

### 4.2 Agent loop

**DSH**：turn/step/attempt/chunk/tool/result/retry/hook 都是持久事件（`worker.cjs:2369-2421`），前端只是投影；PTC 与 workflow 把“工具编排”放进内核（`tool/ptc-dispatch`、`tool-workflow/*`）。

**MCA**：

- `AgentPromptedLoopCoordinator.kt:57` 是唯一执行体；主循环 `:166-430`；每次模型响应最多 1 个工具调用（`AgentBudget.callsPerModelResponse == 1`，`AgentRunTypes.kt:77`）；最多一次协议修复（`:318-330`）。
- 工具处理：`handleToolCall` 先 schema 校验，再冻结命令 + 审批（`:455-582`），执行前经 `executeFrozenCommand`（`:584-620`）。
- 没有 hook、没有 llm retry（只有 attempt 预算），没有 PTC，没有并行工具。
- **流式只有契约没有接线**：`AgentModelProgress` 定义 `TextDelta`（`AgentModelPort.kt:69-74`），但 owner 传的是空 sink：`AgentOwnerExecution.kt:119` `AgentModelProgressSink { }`。UI 只能看到提交后的 timeline 事件。
- 预算：12 attempts / 8 tools / 1 repair / 180 s 模型时间 / 900 s run / 120 s 资源等待（`AgentRunTypes.kt:59-84`）。

**结论**：loop 的确定性和预算控制是 MCA 的强项；缺的是长任务的“呼吸”（流式、重试、压缩、委派）。

### 4.3 Tool registry 与 approval

**DSH**：

- 工具来自插件 `defineTool`（`docs/开发指南.md:192-199`），组合注入（`config/cordis.patch.yml:59-79`）；审批是“preset（sandbox+approval）+ 会话事件”（`:46-58`、`worker.cjs:6219-6221`）。Android 危险操作可 `SHIZUKU_APPROVE=ask`（`plugins/README.md:27-30`）。

**MCA**：

- 内置工具只有 4 个：`workspace.list`、`workspace.read_text`、`workspace.search_text`、`artifact.write_text`（`AgentToolSchemas.kt:243-293`）；写操作是 `WORKSPACE_WRITE`（`:282-293`）。
- 每 run 组装的 catalog 不可变，Skill 只能收窄：`AgentToolCatalogAssembler.kt:112-160`；快照不可修改：`:230-249`。
- 审批：`AgentPermissionBroker.decide` 只允许“读 grant 内 ALLOW_FOR_TASK”，其余一律新建审批（`AgentPermissionBroker.kt:46-57`）；`authorize` 是一次性 CAS（`:187-242`）；`revalidate` 检查 schema hash、policyVersion、runEpoch、offline、grant 撤销、过期（`:244-272`）；连续拒绝 2 次触发 `PERMISSION_DENIED_LOOP`（`:305-321`）。
- 冻结命令绑定参数哈希、schema 哈希、资源指纹、epoch、policy、过期时间（`AgentInvocationTypes.kt:9-39`）。
- 审批存储是 Room（`RoomAgentApprovalStore.kt:29`）；用户决策写入 `ConsumeApprovalTransition` 并在同一事务里结算 run（`AgentOwnerInteractionCommands.kt:228-241`）。
- V1 读 grant 固定为 `inputs` 前缀（`AgentToolPermissionHook.kt:82-93`）。

**结论**：MCA 的“一次调用一次批准 + 冻结参数 + 过期/失效”在 TOCTOU 维度严格；DSH 的“权限模式是会话状态”更适合长任务与自动化。二者不能互称等价。

### 4.4 Skills 与 Plugins

**DSH**：插件是 npm 包 + Cordis 组合（`config/cordis.patch.yml:25-79`、`dsh-patches/README.md:84-95`）；skills 仅运行面可见（`[DSH-OBS]`），仓库 `app-boot` 仅见 skill bundle 名称（`dsh-app-boot/lib/index.js:583`）。

**MCA**：

- 导入：ZIP/目录/SAF，frontmatter 受限子集（`AgentSkillFrontmatter.kt:36`），包预览与限制（`AgentSkillImporter.kt:69`、`AgentSkillLimits.kt:11`）。
- allowed-tools 只能收窄，支持完整 key 与 `workspace.*`/`artifact.*`/`mcp:<serverId>:*`（`AgentSkillToolPattern.kt:101,129`；assembler `AgentToolCatalogAssembler.kt:150-159`）。
- 快照持久化（`AgentSkillStores.kt:61,201`）、文件 store（`FileAgentSkillStore.kt:18`）、管理器（`AgentSkillManager.kt:16`）。
- 撤销 → 非终态 run 的 PENDING 审批失效（acceptance `[SKILLUI]`/X04；`AgentApprovalStorePort.invalidatePendingForRun`）。
- 脚本只展示不执行（architecture §12.1；`AgentSkillImporter` 的 preview 只列文件）。
- **无插件扩展点**：没有 `defineTool` 等价物；工具集合在编译期固定。

**结论**：MCA 的 Skills 是“受限文档/资源包 + 权限收窄”，不是 DSH 运行面那种“按需加载的指令集 + 工具扩展”。插件为 `MISSING`，且这是**有意的**（§12.3 决策门）。

### 4.5 MCP

**DSH**：`[UNKNOWN]`（本克隆无 MCP 文本/源码；运行面只有 `mcp__github__*` 命名线索）。

**MCA**：

- 协议：JSON-RPC + SSE + Streamable HTTP（`McpClient.kt:32`、`McpSseParser.kt:13`、`McpHttpTransport.kt:61`），能力/分页/错误映射在 `McpProtocolTypes.kt:10-134`。
- Registry：`AgentMcpRegistry.kt:140`，schema 漂移回调 `AgentMcpCatalogChange`（`:33`），endpoint policy（`AgentMcpEndpointPolicy.kt:21`），egress guard seam（`AgentMcpEgressGuard.kt:10-22`）。
- 运行接线：`RunScopedAgentToolCatalogProvider` 在 `NETWORK_ENABLED` 且非 offline 时按 server 铸 run-scoped grant → connect → assemble，`release` 关闭 session；grant 被拒则该 server 不连（fail closed）。`RunScopedAgentToolCatalogProvider.kt:50-77,79-153`
- 组合：`MainRuntimeAssembly.kt:334-340` 已把 `mcpServerStore` + `egressGrantStore` 传入 provider；`mcpTools = emptyList()` 只作用于 base fallback（`:314-330`）。
- 缺口：无 stdio；不实现 OAuth（仅静态 bearer/API-key/无认证）；不实现 resources/prompts/sampling（按规范拒绝而不是映射）；远端 cancel/timeout 的真实时长未验证；共享 OkHttp client 不按 run close（card-status K13）。

**结论**：MCA 的 MCP 是一个“合规子集 + 审批/egress”实现；DSH 侧不可核验，不能宣称 parity。

### 4.6 Context compaction

**DSH**：一等事件 + 摘要模型 + 被遮蔽 seq/token 统计（`worker.cjs:6238-6259`）。

**MCA**：**没有压缩**。

- `AgentContextAssembler` 的类注释直接写明 “When … cannot fit … the result is `Overflow` and the caller must pause with CONTEXT_LIMIT; no compression happens here.”（`AgentContextAssembler.kt:56-68`）
- 溢出返回 `MODEL_CONTEXT_LIMIT`（`:110-121`），loop 映射为 `CONTEXT_LIMIT` 暂停（`AgentPromptedLoopCoordinator.kt:194-200`）。
- 唯一的“减负”是工具结果 > 16 KiB 时替换为摘要骨架（`AgentContextAssembler.kt:82-101,170-189`），不是历史压缩。
- 没有 `compaction` 事件、没有 summary、没有 prune。

**结论**：`MISSING`，且是第 2 号缺口。影响是长任务在上下文边界**必死**，用户只能“继续”并丢掉历史。

### 4.7 Goal / plan / todo

**DSH**：goal 有 kind/version/operation、roundsStarted、cleared；plan/mode 与 todo/write 是持久事件（`worker.cjs:6261-6272,6305,6365`）；运行面有 goal/todo/plan 工具（`[DSH-OBS]`）。

**MCA**：

- 只有 `AgentTask.goal: String`（`AgentTaskTypes.kt:31-52`），创建后不可变；续跑是 `ContinueAgentTask`（`:88-103`），由用户触发。
- 没有 plan mode、没有 todo 列表、没有 goal 版本/轮次、没有自动续跑。
- 有 `ask_user` 暂停等待用户回答（`AgentControlMessage.AskUser` → `AgentOwnerExecution.waitForUser`，`:271-315`），这是“问一句”，不是“计划”。

**结论**：goal 只到“任务目标”这一层；DSH 的 goal/plan/todo 是三层。`PARTIAL`（有目标，无计划与轮次）。

### 4.8 Subagents / teams / workflow

**DSH**：subagent 是持久会话的一种 origin（`format.d.ts:50`），有 descriptor（persona/toolFilter/模型/reasoning）、allowedModels、catalog、授权错误；team 有 member/task/message；workflow 有 run/agent 事件（`worker.cjs:2401-2413,6331-6343`）。fork 幂等（`CHANGES.md:54-58`）。

**MCA**：

- 全仓库 agent 源码检索 `subagent`/`workflow` 命中 0（本次只读检索）。
- 只有单 run 执行；最多 1 active + 5 queued（`AgentContract.kt:26-27`）。
- 架构 E05 计划“最多 2 child、深度 1、共用父预算、权限只能缩小、本地推理串行”（`MCA_AGENT_V1_ARCHITECTURE.md:935`）——**计划，不是实现**。

**结论**：`MISSING`，第 5 号缺口。

### 4.9 Memory

**DSH**：持久会话日志可被跨会话检索（`[DSH-OBS]` `session_search`）；是否有独立长期记忆库 `UNKNOWN`。

**MCA**：任务之间没有共享记忆；每个任务有私有 workspace/artifacts；没有跨任务检索；没有“用户偏好/项目记忆”。`AgentWorkspacePaths` 把 workspace 绑定到 task。

**结论**：`MISSING`（跨任务记忆）。注意：不要把“事件日志可查”说成“有记忆”。

### 4.10 Permissions / sandbox / egress

**DSH**：三种 preset（sandbox+approval）作为会话状态（`config/cordis.patch.yml:46-58`、`worker.cjs:6304,6308`）；沙箱是独立能力（landlock，fail-closed），Android 默认不开（`cordis.patch.yml:8-18`）；危险 Android 操作可按 env 恢复审批（`plugins/README.md:27-30`）。

**MCA**：

- 侧效应分级：`AgentToolSideEffect`（`AgentToolPort.kt:4-9`）；读 grant 内可 `ALLOW_FOR_TASK`，其余 ASK（`AgentPermissionBroker.kt:53-56`）。
- egress：origin 规范化 + 传输分类 + 数据分级 + 逐 source 哈希（`AgentNetworkEgressPolicy.kt:16-58,186,240`）；MCP 只允许 grant 里的精确 origin（`RunScopedAgentToolCatalogProvider.kt:155-166`）。
- 路径：逻辑路径、前缀、`..`、软链拒绝（`AgentLogicalPath.kt`、S 组测试）；SAF 只复制普通文件、不跟随软链/循环 document ID（architecture §12.1）。
- **没有** OS 级沙箱、shell、可切换 preset；也没有 `SHIZUKU_APPROVE` 之类“同会话模式”。

**结论**：`DIFFERENT`。MCA 的 egress/审批更细；DSH 的“权限模式 + 沙箱能力 + 工具广度”更适合通用 Agent。复刻时**不能**为了工具广度绕过 MCA 的审批/egress。

### 4.11 平台：FGS、通知、恢复、配额、导出

**DSH**：保活 + 完成通知（`README.md:44-45`；`MainActivity.java:2504,2910,3122`）；会话回收站/修复（`README.md:18,26`）；安全模式/备份导入/自检（`README.md:27,42`）。

**MCA**：

- FGS：`AgentForegroundLease`（`AgentForegroundLease.kt:77,151,173`）+ `AgentRunProcessGuard`（`AgentRunProcessGuard.kt:65`），由 `ObservingAgentJournal` 驱动，只在 RUNNING 持锁（`MainRuntimeAssembly.kt:270-288`）。
- 通知停止：`AgentRunNotificationController` + `AgentRunStopActionRouter`，带 `(runId, epoch)`（`MainRuntimeAssembly.kt:275-288,355-357`；`AgentRuntimeAssembly.kt:214-245`）。
- FGS 不可用 → typed pause（`AgentRuntimeAssembly.kt:260-274`；`MainRuntimeAssembly.kt:343-351`）。
- 启动恢复：只对账不自动续跑（`AgentStartupRecovery.kt:31`）。
- 配额/清理/导出：`AgentWorkspaceQuota`、`AgentTaskDeletionPolicy`、`AgentTaskPackageExporter/Importer`、`AgentWorkspaceGarbageCollector`（K14）。

**结论**：源码层 `EQUIVALENT`，且 MCA 的任务包校验/配额比 DSH 会话回收更正式。行为全部 `NOT_RUN`。

### 4.12 模型协议（补充）

- MCA 本地模型固定 `PROMPTED_V1`；云只在 `Responses + supportsTools` 时 `NATIVE`，Responses 未开工具则为 `PROMPTED_V1`，其他云协议为 `NONE`（`AgentModelChoiceMapper.kt:35-71`）。本地 adapter 走同一 engine/facade + lease + stop proof（`McaAgentLocalModelAdapter.kt:73,355,423`）；云 adapter 有严格终态与有界 SSE（`AgentCloudModelAdapter.kt:84,246`）。
- DSH 是模型无关的（`model/selection` + 工具调用由内核抽象），`[DSH-OBS]` 支持原生工具调用与推理强度切换。
- **结论**：MCA 的“本地原生 FC”缺失；prompted 协议是可靠性/延迟的长期上限。

### 4.13 流式与可观测性（补充）

- DSH 有 `assistant/chunk` 持久事件与 `llm/retry*`（`worker.cjs:6222-6231,2386-2387`）。
- MCA 有 `AgentModelProgress.TextDelta` 契约，但生产 owner 传空 sink（`AgentOwnerExecution.kt:119`）；UI 只能看到提交后的 timeline。审计事件是有的（`AgentEvents.kt:39-58`），但“边生成边看”没有接线。
- **结论**：`PARTIAL`，属于用户可见的“等待感”问题，但不应先于会话/压缩/工具做。

---

## 5. 真正缺失的能力（按用户可见影响 × 架构风险排序）

### 5.1 排序方法

- **用户可见影响**：不做这项，用户能否完成常见任务？是否导致任务中断/重来？
- **架构风险**：实现它是否必须改 owner/状态机/持久化/审批/egress？是否可能引入第二运行时或旁路？
- **不是 UI**：判断标准是“只改 Compose 能否获得该能力”。只改 UI 能解决的，不进本表。

评分 1–5，乘积只用于排序，不是精确度量。

| 排名 | 缺口 | 影响 | 风险 | 乘积 | 现状一句话 |
| --- | --- | ---: | ---: | ---: | --- |
| 1 | G1 持久会话 transcript + 进程重启续跑 | 5 | 5 | 25 | 审批/回答依赖内存 transcript；重启后 pending 审批无法续跑；继续丢历史 |
| 2 | G2 上下文压缩（compaction） | 5 | 3 | 15 | 溢出即 `CONTEXT_LIMIT` 暂停，长任务必死 |
| 3 | G3 工具广度 + 本地原生 tool calling | 5 | 4 | 20 | 只有 4 个文件工具；本地仅 prompted |
| 4 | G4 目标/计划/todo/轮次 + 自动续跑 | 4 | 3 | 12 | goal 是创建时字符串，无 plan/todo/rounds |
| 5 | G5 子代理/工作流/团队 | 3 | 5 | 15 | 无 child run、无委派、无并行 |
| 6 | G6 Cordis 插件宿主 | 3 | 5 | 15 | 决策门未过，且是最大安全风险 |
| 7 | G7 跨任务记忆/检索 | 3 | 3 | 9 | 无跨任务记忆，事件只能按 task 查 |
| 8 | G8 MCP 完整性（stdio/OAuth/resources） | 2 | 3 | 6 | 仅 HTTP tools 子集 |
| 9 | G9 会话 fork/steer/queue/title + 跨任务搜索 | 2 | 2 | 4 | 只有 1 active + 5 queued，无 fork |
| 10 | G10 流式输出/hook/retry/PTC | 2 | 2 | 4 | 契约有 TextDelta，生产未接线；无 hook/retry/PTC |

> 排序说明：G3 乘积高于 G2，但 G1/G2 是“其他能力的底座”（没有持久会话与压缩，工具再多也只能跑短任务），因此 G1、G2 排在 G3 前。G5 与 G6 乘积相同，G5 先做：它复用现有工具/审批就能产生用户价值，G6 需要先过安全门。
>
> 未单列的次要缺口：定时/调度（DSH `schedule/change` 与调度工具；MCA 无，E06 计划）、会话标题/回收站治理（DSH 有；MCA 只有任务归档与删除）、hooks 扩展点（DSH `hook/invoked|result`；MCA 无）。它们的乘积均 ≤ 6，不改变 Top 5。

### 5.2 Top 5（给整合负责人的版本）

#### G1 持久会话 transcript 与重启续跑（影响 5 / 风险 5）

- **今天会发生什么**：进程被杀/系统回收后，run 被结算为 `PAUSED`/`INTERRUPTED`；若当时在等审批，pending approval 会被置 `EXPIRED`、invocation 置 `NOT_EXECUTED`（安全），但用户无法再批准/拒绝，只能“继续”；而“继续”会开新 run 且没有旧 transcript。结果：中断前的模型回合、工具结果与用户问题全部从模型上下文中消失。
- **证据**：`AgentRunOwner.kt:76-78`、`AgentOwnerInteractionCommands.kt:140-144,216-220`、`AgentStartupRecovery.kt:31`、`ContinueAgentTask` 无 transcript 注入（`AgentOwnerInteractionCommands.kt:83-98`）。
- **为什么不是 UI**：UI 可以显示“已中断”，但无法让模型看见中断前的调用与结果；必须持久化 transcript、恢复配对、显式标注 `TOOL_NOT_STARTED`/`TOOL_OUTCOME_UNKNOWN`。
- **风险点**：与 tool pairing 不变式、artifact commit journal、invocation 幂等、Room 迁移相交；恢复只能续跑模型回合，不能自动重放工具副作用。

#### G2 上下文压缩（影响 5 / 风险 3）

- **今天会发生什么**：长任务在第 N 次模型调用后因 `estimatedTokens + reservedOutputTokens > window` 暂停为 `CONTEXT_LIMIT`；用户点“继续”后新 run 从零开始。
- **证据**：`AgentContextAssembler.kt:56-68,110-121`；loop `AgentPromptedLoopCoordinator.kt:194-200`。
- **为什么不是 UI**：压缩必须改写“模型看到的历史”，涉及摘要模型调用、被遮蔽 seq/token 记录、工具配对、审批上下文；UI 折叠不改变 token。
- **风险点**：压缩调用本身要遵守 offline/egress/预算；摘要不得吞掉未配对调用；压缩后旧 approval 的语义要明确（保留/失效）。

#### G3 工具广度与本地原生 tool calling（影响 5 / 风险 4）

- **今天能做什么**：列目录、读文本、搜索文本、写 artifact；不能编辑文件、不能 patch、不能跑命令、不能浏览网页、不能操作系统。
- **证据**：`AgentToolSchemas.kt:243-293`；`AgentModelChoiceMapper.kt:35-71`（本地固定 prompted）。
- **为什么不是 UI**：每新增一个工具都要 descriptor/schema hash/side-effect/参数规范化/结果边界；原生 FC 要改 decoder 与 adapter。
- **风险点**：写/执行工具必须继续走冻结命令 + 审批 + egress；shell 需要独立沙箱/进程隔离，不能拿“同 UID 子进程”冒充安全边界（architecture §12.3、E03）。

#### G4 目标/计划/todo/轮次与自动续跑（影响 4 / 风险 3）

- **今天会发生什么**：Agent 不能在任务内维护“待办清单”，也不能在预算内自动继续；用户必须手动“继续”，且继续后历史丢失（见 G1）。
- **证据**：`AgentTaskTypes.kt:31-52`；无 todo/plan 类型（本次检索命中 0）。
- **风险点**：自动续跑必须与预算、审批、FGS、上下文压缩、lease 协同；计划状态是新的业务状态，需要单写者。

#### G5 子代理/工作流（影响 3 / 风险 5）

- **今天会发生什么**：没有并行调研、没有“父任务派生子任务”、没有团队/消息。
- **证据**：agent 源码无 `subagent`/`workflow`；`AgentContract.kt:26-27` 只允许 1 active。
- **风险点**：本地推理必须串行共享同一 engine lease；父子预算/审批/取消要成为一棵树；子代理不能扩大父的 egress 或工具集；循环委派要有硬上限。

### 5.3 明确不是“缺失”的部分（避免把 UI 当内核）

- Agent 首页/详情/审批 sheet/timeline/artifacts/Skills/MCP 管理页、导入导出入口都已存在（`feature/agent-workspace/...`，`AgentWorkspaceRoute.kt:21-22`、`AgentWorkspaceViewModel.kt:504-622`）。
- 因此“DSH 有审批面板，MCA 没有”之类的说法不成立；MCA 缺的是**面板背后的可续跑会话/计划/子代理状态**。
- 同理，MCP/Skills 的“管理 UI 已有”不等于“运行时已经闭环”；本文把两者分开判定。

---

## 6. 证据冲突与文档漂移（必须先修正认知）

这些冲突不代表代码错，但会误导后续排期。结论以当前源码为准，同时保留“行为未验证”。

| # | 冲突 | 文档说法 | 源码事实 | 处置 |
| --- | --- | --- | --- | --- |
| C1 | MCP 是否接入生产 | `acceptance-status.md:42`、`verification-2026-10-19.md:100-101` 说 composition 未传 MCP adapters；`MainRuntimeAssembly.kt:311-313` 注释说“registry 还不存在” | `MainRuntimeAssembly.kt:334-340` 已构造 `RunScopedAgentToolCatalogProvider(serverStore=..., grantStore=...)`；`:392-394` 的 `AgentMcpAdminService(application, mcpServerStore)` 也复用同一 store | 以源码为准：**已接线，未做真实 server/设备 E2E**；注释是残留，建议后续清理 |
| C2 | FGS-unavailable 是否映射到 owner | `acceptance-status.md:45` 说未接线 | `AgentRuntimeAssembly.kt:260-274` + `MainRuntimeAssembly.kt:343-351` 已接线；`AgentPauseReasonFaultTest` 覆盖 | 以源码为准：**已接线**；设备 NOT_RUN |
| C3 | K14 是否实现 | `card-status.md:70` “仍未完成 #1：K14 未实现” | K14 行本身写“已交付”；代码有 exporter/importer/quota/deletion | 以源码+K14 行为准；`card-status` 的“仍未完成”段是旧文本 |
| C4 | 测试计数 | `card-status` 3245；`acceptance-status` 3168/3121；`verification-2026-10-19.md:53` 3245 | 无法在本任务复跑（未运行 Gradle） | 引用最新 `verification-2026-10-19.md:53` 的 3245/0/0/11，但标注“历史运行，未复跑” |
| C5 | 验收汇总 | `acceptance-status.md:36` 58/63/7；`:228` 56/62/10 | 文档内部不一致 | 不引用汇总数作为结论；只引用具体 ID 证据 |
| C6 | AgentRunOwner 规模 | `card-status.md:16` “最大 447 行” | 拆分后 5 个文件合计 1,428 行（165+277+264+470+252），单文件最大 470 | 作为“新增会话/压缩状态前先收口 owner”的风险项（硬锁 §8 要求主文件与 partial 合并观察） |

> 额外漂移：`capability-matrix.md` 的 DB v27 结论已过期（当前 `ChatSessionStoreRoomDatabase.kt:81` 是 v28）；该文档是基线 `5e912ff9` 的快照，不应直接引用为当前事实。

---

## 7. NOT_RUN / UNKNOWN / BLOCKED 清单

### 7.1 本任务未运行

- 未运行 Gradle、`scripts/verify-code.ps1`、`tools/architecture/check_architecture_rules.py`、`tools/final-gate/*`。
- 未运行 `adb`、未安装 APK、未做 UI/通知/SAF/TalkBack/IME 验证。
- 未运行真实 MNN/GGUF/云模型、未连接真实 MCP server。
- 未构建/运行 DSH 参照克隆（只读）。
- 未做性能/内存/耗电测量。

### 7.2 无法从现有材料判定（`UNKNOWN`）

1. DSH 的 MCP client 协议版本、transport、审批模型、与 MCA 的差异。
2. DSH skills 的格式、权限、脚本执行边界（仓库无 SKILL.md 证据）。
3. DSH 上下文压缩的触发策略（自动/手动/阈值）、失败回退、与模型窗口的关系——只知事件存在。
4. DSH goal 的自动续跑轮次上限、预算和停止条件。
5. DSH 是否有独立长期记忆库；`session_search` 的索引范围与保留期。
6. DSH 插件在 Android 上的实际隔离强度（Node 进程内代码，`§12.3` 已指出“同 UID 不是沙箱”）。
7. MCA 的 MCP 真实 server 端到端、远端 cancel/timeout、schema drift 后的审批失效 E2E。
8. MCA 的未来 Room 迁移（如 28→29，本文件 Phase 1 才提出）与真实设备升级链。
9. MCA 的 FGS/通知/stop proof 在真机上的行为。
10. MCA 本地模型的真实 tool-call 质量与 prompted 协议成功率。

### 7.3 BLOCKED（沿用既有证据）

- 设备 instrumentation：MIUI `INSTALL_FAILED_USER_RESTRICTED`（`evidence/5e912ff9/verification-2026-10-19.md:87-90`、`card-status.md:60`）。
- 因此：Room 迁移、Compose Route、SAF 往返、FGS/通知、两轮视觉/TalkBack 均 `BLOCKED`/`NOT_RUN`，不是 PASS。

---

## 8. 三阶段“完美复刻”路线（保留 MCA 约束）

### 8.0 不可破坏的约束（每阶段都适用）

| ID | 约束 | 来源 |
| --- | --- | --- |
| K1 | 每进程只有一个 `AgentRunOwner`；页面/API 只观察 | `MCA_PROJECT_HARD_LOCK.md` §5；`AgentRuntimeAssembly.kt:55-125` |
| K2 | 复用生产 engine/facade/admission/lease；不新建第二套运行时 | 硬锁 §5；`MainRuntimeAssembly.kt:294-305` |
| K3 | 所有副作用必须经冻结命令 + broker 审批；不得为 MCP/Skill/子代理开旁路 | architecture §8；`AgentPermissionBroker.kt:187-242` |
| K4 | 网络必须经 egress grant（origin/data class/policyVersion）；LOCAL_ONLY 不联网 | architecture §11；`RunScopedAgentToolCatalogProvider.kt:79-153` |
| K5 | 单一权威状态写入者；Room 事务 + exportSchema + 真实迁移；无 destructive fallback | 硬锁 §6/§3.2；`ChatSessionStoreRoomDatabase.kt:81-84` |
| K6 | FGS 只覆盖真实执行；等待审批/资源/用户时释放 | architecture §14；`MainRuntimeAssembly.kt:270-288` |
| K7 | 冻结视觉；新增表面沿用现有 Composable 语言，不整体换肤 | 硬锁 §9 |
| K8 | 不降低 targetSdk、不以“同 UID 子进程”冒充沙箱、不把 Skill 导入说成 Cordis 兼容 | architecture §12.3、E03 |

### 8.1 Phase 1 — 会话底座（G1 + G2 + 流式）

**目标**：让一个任务在进程重启后能继续；让长任务不被上下文边界杀死；不新增任何绕过审批的能力。

| 切片 | 交付 | 主要 owner | 关键约束 | 验证门（JVM/静态） |
| --- | --- | --- | --- | --- |
| P1.1 Schema v29 | `agent_transcript_entries`（runId/stepIndex/kind/payloadRef/hash/size）+ `agent_compactions`（compactionId/runId/fromSeq/toSeq/shadowedTokenCount/summaryRef/provider/model） | `app/agent/data` + `ChatSessionStoreRoomDatabase` | 27→28 已有；新增 28→29 必须 exportSchema + 迁移测试；只增表不改旧列 | 迁移测试、schema 契约测试、Room 读取测试 |
| P1.2 持久 transcript | 新 `AppendTranscriptTransition`（与 step/attempt 同事务）；只在 journal 写；`AgentPayloadStore` 存有界 payload | `AgentJournal` + `RoomAgentJournal` | 单写者；不存明文密钥；payload hash/size 校验 | 配对不变式、崩溃点重放、幂等测试 |
| P1.3 恢复语义 | 重启后重建 transcript；未落地的 tool call 标 `TOOL_NOT_STARTED` / `TOOL_OUTCOME_UNKNOWN`；**不自动执行工具**；`AgentStartupRecovery` 从“只对账”升级为“可续跑模型回合” | `AgentRunOwner` + `AgentStartupRecovery` | 恢复必须走同一 owner；恢复时重新校验 approval epoch/policy | 故障注入：杀进程、半写、审批中重启 |
| P1.4 会话回合 | `AppendUserTurn` 命令；任务内多轮消息；`Continue` 复用 transcript 而不是新空 run | `AgentRunOwner` + feature VM | 页面仍只发命令；不产生第二个 writer | 状态机测试、UI 契约测试 |
| P1.5 压缩 | `AgentContextCompactor`（runtime 纯组件）；溢出前摘要旧回合；写 compaction 事件（shadowed seq/token）；失败 → typed pause（如 `COMPACTION_FAILED`） | `core/agent-runtime` + 新 contract 类型 | 摘要调用走同一 model port + 预算 + offline/egress；不吞未配对调用；保留最近 N 轮与所有未闭合边 | 压缩后配对、token 估计、恢复后一致、审批上下文保持 |
| P1.6 流式 | 把 `AgentModelProgressSink` 接到 run-scoped UI 投影（临时状态，不写 DB）；不参与控制决策 | `AgentRunOwner` + feature VM | delta 不触发工具/审批；不改变已提交事件 | 进度不泄漏到 journal；停止后可丢弃 |

**Phase 1 完成判据**：JVM 下“杀进程→重启→继续同一 run”与“上下文溢出→压缩→继续”可重复通过；无新增权限旁路；设备验证仍 `NOT_RUN`（安装被阻断），不得写 PASS。

### 8.2 Phase 2 — 政策内的能力广度（G3 + G4 + 会话操作）

**目标**：把“能持续工作”变成“能做完更多类型的任务”，但每个新动作仍在审批/egress 之内。

| 切片 | 交付 | 约束 |
| --- | --- | --- |
| P2.1 文件工具补齐 | `workspace.edit`（hash 前置条件）、`workspace.glob`、`workspace.grep`；复用 `AgentWorkspaceFilePort` | 全部无网络；写操作 `WORKSPACE_WRITE`→ASK；descriptor/schema hash 冻结 |
| P2.2 计划/待办 | `AgentPlan`/`AgentTodoItem` 契约 + 持久化 + `plan.write`/`todo.write` 工具 + timeline 行 | 计划是业务状态，单写者；不改变权限 |
| P2.3 目标轮次 | goal 版本/操作/roundsStarted；自动续跑策略（最大轮次、预算、暂停条件）；用户可关 | 自动续跑必须先有 P1；每轮仍走预算与审批 |
| P2.4 原生 FC | 云 Responses NATIVE 已有；在本地后端暴露 tool-call 时增加 NATIVE；否则保持 prompted；协议错误可回退 prompted | decoder 变更不得绕过 schema/审批 |
| P2.5 会话操作 | fork（复制 ≤ seq 的 transcript）、queue/steer、title；跨任务事件检索（只读） | fork 需要新的 run/task 关系与配额；搜索不泄漏 secret |
| P2.6 工作流（可选） | 轻量 DAG/步骤模板（非通用脚本引擎） | 每个节点复用现有模型/工具合同；不建第二个 loop owner |

**Phase 2 完成判据**：新增工具全部有 schema/approval/egress 测试；计划/轮次状态可恢复；设备 E2E 仍 `NOT_RUN`。

### 8.3 Phase 3 — 多代理、记忆与扩展（G5 + G6 + G7 + G8）

**目标**：DSH 式“委派/团队/插件/记忆”，但每项都过安全门。

| 切片 | 交付 | 硬边界 |
| --- | --- | --- |
| P3.1 子代理 | child run（parentRunId/depth/origin）+ 独立 transcript；子 catalog ⊆ 父；父预算共享；取消树；最多 2 child/深度 1 | 本地推理串行共享 engine lease；子不能扩 egress；循环委派拒绝 |
| P3.2 团队/消息（可选） | team/task/message 事件与 UI | 消息不自动执行工具；需父级审批 |
| P3.3 记忆 | 任务/项目级“记忆文件”+ 只读检索；显式用户可见、可删除 | 不存密钥；不把记忆当权限；检索不联网 |
| P3.4 插件 Host | 仅 §12.3 五条全过后：tool-bridge 模式、manifest 声明工具/数据类/egress、独立进程或明确“可信代码” | 不运行第二 loop；插件工具仍经 broker；不能降低 targetSdk |
| P3.5 MCP 完整性 | stdio（隔离进程）、resources/prompts 只读映射、schema drift→审批失效 E2E；OAuth 明确不支持 | stdio 必须有进程/路径/超时预算；远端副作用 UNKNOWN 语义 |

**Phase 3 完成判据**：子代理/插件的越权与旁路测试通过；真实 server/设备证据补齐；否则保持 `NOT_RUN`。

### 8.4 阶段门与回退

- **阶段门**：每个 Phase 结束时只允许声明“源码层 + JVM 层”完成；设备/模型/真实 server 必须单列 `NOT_RUN`。禁止把 3245 个 JVM 测试绿写成“生产可用”。
- **数据回退**：新增表/事件是 additive；旧代码忽略新表；不做 destructive migration；回退到旧 APK 后新表留在库中不阻塞（需在迁移测试中证明）。
- **功能回退**：每个 Phase 的入口可由 composition root 关闭（不新增“全局 feature flag 框架”，用构造参数/导航入口即可）。
- **停止条件**：一旦某切片需要“第二个 owner/第二套 engine/绕过 broker/降低 targetSdk”，立即停止并改方案（硬锁 §5、§11；architecture §12.3）。

---

## 9. 风险登记（复刻特有）

| ID | 风险 | 触发条件 | 缓解 |
| --- | --- | --- | --- |
| R1 | 持久 transcript 让“旧 approval 复活” | 重启后恢复 run + 旧 pending approval | 恢复时强制 policyVersion/epoch/schema hash 复核；不匹配即失效 |
| R2 | 压缩吞掉未配对调用，模型继续执行 | 摘要边界切在 assistant envelope 与 result 之间 | 压缩按完整回合边界切；保留所有未闭合边；断言配对 |
| R3 | 自动续跑越过用户审批 | 模型在续跑轮次请求写/网 | 自动续跑只允许读/计划；写/网仍 ASK |
| R4 | 子代理并发导致 engine lease 争用 | 两个 child 同时本地推理 | 本地推理串行；child 共享父 lease；超额排队 |
| R5 | 插件/工具扩大 egress | 新工具声明 NETWORK | egress grant 逐 origin；LOCAL_ONLY 禁止；审计 payload source 哈希 |
| R6 | 会话文件/工作区无限增长 | 持久 transcript + 压缩摘要 + 多轮 | 配额（复用 AgentWorkspaceQuota）+ GC + 有界 payload |
| R7 | 文档再次漂移 | 开发只改代码不改证据 | 每个切片更新 evidence；本文第 6 节的冲突项在合并前修正 |

---

## 10. 附录

### A. 证据索引（本文件引用）

**MCA（当前源码）**

- `core/agent-contract/src/main/java/com/muyuchat/core/agent/contract/`：`AgentContract.kt`、`AgentRunTypes.kt`、`AgentTaskTypes.kt`、`AgentToolPort.kt`、`AgentInvocationTypes.kt`、`AgentEvents.kt`、`AgentJournal.kt`、`AgentModelPort.kt`
- `core/agent-runtime/src/main/java/com/muyuchat/core/agent/runtime/`：`run/AgentRunOwner.kt`、`run/AgentOwnerRunCommands.kt`、`run/AgentOwnerInteractionCommands.kt`、`run/AgentOwnerExecution.kt`、`run/AgentPromptedLoopCoordinator.kt`、`run/AgentBudgetPolicy.kt`、`run/AgentRunStateMachine.kt`、`context/AgentContextAssembler.kt`
- `app/src/main/java/com/muyuchat/mca/agent/`：`security/AgentPermissionBroker.kt`、`security/AgentFrozenCommandFactory.kt`、`security/AgentNetworkEgressPolicy.kt`、`security/AgentPermissionTypes.kt`、`composition/AgentRuntimeAssembly.kt`、`composition/AgentToolPermissionHook.kt`、`composition/RunScopedAgentToolCatalogProvider.kt`、`composition/RoomAgentApprovalStore.kt`、`tools/AgentToolCatalogAssembler.kt`、`tools/AgentToolSchemas.kt`、`extensions/mcp/*`、`extensions/skills/*`、`platform/AgentStartupRecovery.kt`、`platform/AgentForegroundLease.kt`、`platform/AgentRunProcessGuard.kt`、`data/*`、`inference/*`
- `app/src/main/java/com/muyuchat/mca/composition/MainRuntimeAssembly.kt`
- `feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/`：`AgentWorkspaceRoute.kt`、`AgentWorkspaceViewModel.kt`、`ui/*`
- `app/src/main/java/com/muyuchat/mca/ChatSessionStoreRoomDatabase.kt:81-84`

**DSH（只读克隆）**

- `README.md`、`CHANGES.md`、`docs/开发指南.md`
- `config/cordis.patch.yml`
- `plugins/README.md`、`plugins/*/package.json`、`plugins/*/lib/*`
- `dsh-patches/README.md`
- `dsh-patches/overlay/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/{README.md,lib/worker.cjs,lib/types/format.d.ts}`
- `dsh-patches/overlay/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-bash-local/README.md`
- `dsh-patches/overlay-021/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js`
- `dsh-patches/overlay-021/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js`
- `android-app/src/com/deepseek/harness/MainActivity.java`

### B. 复现方式（只读）

1. 读 DSH 事件分类：打开 `.../dsh-session-persistence-jsonl/lib/worker.cjs`，定位 `"approval/asked"` 到 `"web/deepseek-search-llm-request"` 的数组（约 2369–2421 行）。
2. 读事件字段语义：同文件 `disposition(...)` 表（约 6219–6370 行），重点 `compaction/*`、`goal/change`、`plan/mode`、`todo/write`、`subagent/*`、`approval/*`。
3. 读会话持久化语义：同包 `README.md:12,56-80,133-141`。
4. 读权限 preset：`config/cordis.patch.yml:46-58`；读插件注册：`plugins/README.md:14-30`。
5. 读 MCA 上下文与恢复：`AgentContextAssembler.kt:56-68,110-121`、`AgentRunOwner.kt:76-78`、`AgentStartupRecovery.kt:31`、`AgentOwnerInteractionCommands.kt:140-144`。
6. 读 MCA 审批：`AgentPermissionBroker.kt:46-57,187-272,305-321`。

### C. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-10-19 | 初版：只读对标、缺口排序、三阶段路线、证据冲突与 NOT_RUN 清单 |

---

## 11. 最终声明

- 本文件**没有**证明 MCA 与 DSH 的任何行为等价；只证明“机制存在/缺失”的结构差异。
- 本文件**没有**运行代码、测试、构建或设备；所有运行时结论均为 `NOT_RUN`，设备安装为 `BLOCKED`。
- 引用 DSH 的部分来自只读克隆；引用 MCA 的部分来自当前工作区源码。两者都绑定到本文记录的时间点，后续变更需重查。
- 如果只能做一件事：先做 **G1（持久 transcript + 恢复）**；如果只能做两件：加 **G2（压缩）**。工具广度与子代理在会话底座完成前不会带来可持续的收益。

# C1–C7 修复轮测试门（结果：PASS，含证据缺口说明）

- Run ID：`R07-20261019-21f8b1b`
- 状态：**PASS**（C1–C6 有 Gradle XML 计数；C7 为 Lead 报告 PASS，最终原始日志仍缺）
- 上级报告：[R07-REPORT.md](R07-REPORT.md)；机器清单：[run.json](run.json)
- 来源计划：[DSH_SIDEBAR_ENTRY_HANDOFF_AND_FIX_PLAN_20261019.md](../../../../repair/DSH_SIDEBAR_ENTRY_HANDOFF_AND_FIX_PLAN_20261019.md) §7 阶段 C
- 执行证据：`E:\tmp\dsh-final-gates\*.log`（modules/app-dsh/diag3/diag4）+ 各模块 Gradle `test-results/testDebugUnitTest/*.xml`

> 说明：C1–C6 的计数已用 Gradle XML 交叉核对（failures=0/errors=0/skipped=0）。C1/C6 的 chat XML 时间戳为 2026-10-19T15:32Z（23:32 +08），早于 A3 最终 gate；C7 的最终 raw log 未在 `E:\tmp\dsh-final-gates` 找到。发布证据需补齐这两点后再冻结。

## 0. 汇总

| ID | 任务 | 数量 | 失败 | 结果 | 证据 |
| --- | --- | --- | --- | --- | --- |
| C1 | `:feature:chat:testDebugUnitTest --tests …ChatWorkspaceNavigationTest` | **2/2** | 0 | **PASS** | `feature/chat/build/test-results/testDebugUnitTest/TEST-com.muyuchat.feature.chat.ChatWorkspaceNavigationTest.xml` |
| C2 | `:core:dsh-runtime:testDebugUnitTest` | **77/77** | 0 | **PASS** | `core/dsh-runtime/build/test-results/testDebugUnitTest`（16 suites，01:44:20）+ `modules.log` |
| C3 | `:feature:dsh-workspace:testDebugUnitTest` | **68/68** | 0 | **PASS** | `feature/dsh-workspace/build/test-results/testDebugUnitTest`（11 suites，01:44:29） |
| C4 | `:api:dsh-gateway:testDebugUnitTest` | **77/77** | 0 | **PASS** | `api/dsh-gateway/build/test-results/testDebugUnitTest`（12 suites，01:44:34） |
| C5 | `:app:testDebugUnitTest`（DSH suites） | **74/74** | 0 | **PASS** | `app/build/test-results/testDebugUnitTest` 6 个 DSH suite（01:44:50）+ `app-dsh.log` |
| C6 | `:feature:chat:testDebugUnitTest` + app DSH 集合 | **357/357 + 74/74** | 0 | **PASS** | chat 52 suites（23:32 XML）+ app DSH 74/74（01:44） |
| C7 | `python tools/architecture/check_architecture_rules.py` | — | 0 | **PASS（Lead 报告）** | final raw log 缺；旧 `E:\tmp\dsh-fix-verify\C7-architecture.log` 只是早前记录 |

## 1. 逐项细节

### C1 Chat 导航行为 — PASS 2/2

- 命令：`:feature:chat:testDebugUnitTest --tests com.muyuchat.feature.chat.ChatWorkspaceNavigationTest`
- XML：2 tests / 0 failures / 0 errors / 0 skipped，timestamp `2026-10-19T15:32:21Z`。
- 覆盖面：DSH 与 Agent 入口隔离；capabilities 更新后不捕获旧回调。
- 注意：XML 早于 A3 final gate；ChatViewModel 在 A1/A2/A3 期间未再变更（本次更新记录的 hash 未变），但仍需最终冻结时重跑。

### C2 DSH runtime — PASS 77/77

- 命令：`:core:dsh-runtime:testDebugUnitTest`
- XML：16 suites / 77 tests / 0 failures；日志 `modules.log` BUILD SUCCESSFUL（30s，01:44）。
- 覆盖：Probe 401/403/404/500/空 body、owner、认证、payload、readiness。

### C3 DSH workspace — PASS 68/68

- 命令：`:feature:dsh-workspace:testDebugUnitTest`
- XML：11 suites / 68 tests / 0 failures（01:44:29）。
- 覆盖：生命周期、代次、竞争、内联调度、layout shim 相关调用（测试层面）。

### C4 Gateway — PASS 77/77

- 命令：`:api:dsh-gateway:testDebugUnitTest`
- XML：12 suites / 77 tests / 0 failures（01:44:34）。
- 覆盖：认证、路由、流式、取消等。

### C5 App DSH — PASS 74/74

- 命令：`:app:testDebugUnitTest`（DSH suites）
- XML 6 个 suite：DshCallRegistry 9、DshGatewayAssembly 2、DshModelCatalog 9、DshPromptedToolProtocol 16、DshTranscriptMapper 14、McaDshGatewayBackend 24 = **74/74**，0 failures（01:44:50）；`app-dsh.log` BUILD SUCCESSFUL。
- 覆盖：空目录/认证/缺模型/目录变化、prompt-compat 工具协议、transcript 映射、gateway backend。

### C6 Chat + app DSH 回归 — PASS

- Chat：52 suites / **357/357**，0 failures（XML timestamp `2026-10-19T15:32:24Z`）。
- App DSH：**74/74**（01:44）。
- 注意：chat 结果早于 A3；发布冻结前应重跑 chat set 并保存新 XML。

### C7 架构规则 — PASS（Lead 报告；raw log 缺）

- 命令：`python tools/architecture/check_architecture_rules.py`
- Lead 报告 PASS、0 failures。
- **缺口**：`E:\tmp\dsh-final-gates` 中无 C7 最终日志；只有旧 `E:\tmp\dsh-fix-verify\C7-architecture.log`。发布证据必须补最终 raw log（或重新执行并保存）。

## 2. 判定

- C1–C6 计数与 XML 一致、0 失败；C7 按 Lead 报告 PASS。
- 但“测试通过”不等于 release E2E 通过；本地模型回合 FAIL、云端 503 BLOCKED 仍开放。
- 最终 release 前须：重跑 chat set（C1/C6）、补 C7 final log、绑定 `headFinal` 与 release APK。

# E1–E9 修复轮真机验收矩阵 — ABSOLUTE FINAL B610E1D0 待短回归

- Run ID：`R07-20261019-21f8b1b`
- Absolute final APK：`b610e1d0d29195e966e705a4a5113f46d8601a16349f45bedb98954ace73ae9a`，393,717,991 B，05:06:13，0.2.4 (8)，signer `2619ac4c…a9cf`；**short regression PENDING**
- Previous hardening：`2005df02…`（previous PASS_WITH_CAVEATS）；intermediate `B63149B8…`；stale `e0b97fb3…`
- 设备：98a37aa7（Xiaomi 25091RP04C / Android 17 SDK37 / arm64-v8a）
- Absolute final 预检：dex closure strings（stop verification failed/found survivors/survivingPids/label regexes）；runtime 93/93；P1-R1/R2 code-review closed；label P2 closed except Cafe2024 edge
- `headFinal`：未冻结（观察 HEAD `1965bed836de40610948d92e2e1bb62890635d93`）
- `headFinal`：未冻结（观察 HEAD `26dcd81d43c1800849247660bf85ce1d28acbb87`，03:41:20）
- 上级：[R07-REPORT.md](R07-REPORT.md)、[R07-DECISION-SHEET.md](R07-DECISION-SHEET.md)、[run.json](run.json)

> 下文 §1–§6 是 previous `0A731D84…` 的 FINAL 结果；**不得复制为 2005DF02 的 PASS**。2005DF02 的 final 列全部 PENDING，见决策单。

> 结果绑定 FINAL-ONE-PASS 最终候选 SHA。结论 **PASS WITH CAVEATS**，不是 clean all-green。

## 1. E1–E9 最终结果

| ID | 场景 | 结果 | 证据/说明 |
| --- | --- | --- | --- |
| E1 | 入口 | **PASS** | `fp-dsh-10s.png`；进入 DSH |
| E2 | 无模型/官方 UI + 默认工作区 | **PASS** | hero/composer/默认工作区 |
| E3 | 官方前端 + picker | **PASS** | `fp-picker-submenu.png`；human-readable 名称 + 真实 adb tap |
| E4 | token 审计 | **NOT_RUN** | 未执行 |
| E5 | 重复进入/恢复/单 Node | **PASS** | 同 `:dsh` PID 5669 / Node 5697；会话恢复 |
| E6 | 后台恢复 | **PASS** | HOME 6s → 恢复 |
| E7 | 运行时失败/新代 | **NOT_RUN** | 无受控注入 |
| E8 | 模式隔离 | **PASS** | Agent + chat |
| E9 | pre-start model visible | **PASS** | picker 两模型 |
| E9 | post-start catalog refresh | **NOT_RUN** | documented limitation |
| chat | DSH protocol/loop | **PASS（quality caveat）** | n_ctx=16384；1分19秒 / 6.6K tok；输出离题 |
| cloud | 云端回合 | **BLOCKED external** | 上游 503 |

## 2. 模型自适应最终结果

| 项 | 值 |
| --- | --- |
| n_ctx=12288 | **FAIL** — `PI_AI_ERROR`；仍超预算（A4 evidence，最终轮未重测） |
| n_ctx=16384 | **PASS** — final load `context=16384 backend=mnn_cpu`；local turn 完成 1分19秒 / 6.6K tok / 无 n_ctx error |
| 要求 | **n_ctx ≥ 16384（tunable）**；12288 不足；不是 8192 可用 |
| 质量 | **MODEL_QUALITY_ONLY** — 输出 `I am ready upon receiving feedback…` 离题，不是 DSH-OK；不是协议失败 |
| 设备最终状态 | MCA chat；model READY at n_ctx=16384；DSH alive；single Node PID 5697；data preserved |
| P2 UX | pending profile transaction 可跨 force-stop；下一次 load 报 recovery，apply/rollback 禁用，需第二次重启 |

## 3. C1–C7 与代码门

| ID | 数量 | 结果 |
| --- | --- | --- |
| C1 | 2/2 | **PASS** |
| C2 | 77/77 | **PASS** |
| C3 | 68/68 | **PASS** |
| C4 | 77/77 | **PASS** |
| C5 | 74/74 | **PASS** |
| C6 | chat 357/357 + app 74/74 | **PASS** |
| C7 | architecture RC=0 | **PASS（raw log 缺）** |

## 4. 已关闭 / 带 caveat

| 项 | 状态 | 说明 |
| --- | --- | --- |
| White-pane | **CLOSED_VERIFIED_FINAL** | FINAL hero/composer 渲染 |
| Picker clipping | **CLOSED_VERIFIED_FINAL** | full list 可见、adb tap 可用 |
| Local-model context | **PASS WITH TUNABLE REQUIREMENT** | n_ctx ≥ 16384 |
| Display-name human name | **PASS WITH CAVEAT** | 已显示 `Qwen3.5 0.8B mnn …`，但仍带 UUID 后缀 + `[PROMPTED_TOOL_V1]` |
| DSH chat | **PASS（protocol/loop）** | 质量 caveat |

## 5. 未通过 / 未运行 / 阻断 / 限制

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 本地 n_ctx=12288 | **FAIL** | 不足；要求 ≥16384 |
| E4 | **NOT_RUN** | token 审计缺 |
| E7 | **NOT_RUN** | 无安全注入 |
| E9 post-start refresh | **NOT_RUN** | documented limitation |
| Cloud | **BLOCKED external** | 503 |
| PTY | **FAIL** | 无真 PTY |
| Android sandbox | **fail-closed / workspace-write** | 不宣称完整 sandbox |
| 真隔离 / 全离线 / 原生 sharp | **不宣称** | 同 ADR/BASELINE |
| P1 stop-fence descendants | **OPEN** | 见决策单 |
| P2 plugins.log | OPEN | packaging 警告 |
| P2 pending-txn UX | OPEN | recovery UX |
| P3 stop_marker | OPEN observation | chat 仍可用 |
| C7 raw log / headFinal | OPEN | 未冻结/未落盘 |

## 6. 一屏

```text
R07 FINAL: PASS_WITH_CAVEATS — 0A731D84…FB62 / 98a37aa7
E1/E2/E3/E5/E6/E8/E9(pre-start) PASS; E4/E7/E9 post-start refresh NOT_RUN
DSH chat PASS (protocol/loop) at n_ctx=16384 — 1m19s/6.6K tok; MODEL_QUALITY_ONLY
C1-C7 PASS (C7 raw log missing); cloud BLOCKED external 503; 12288 FAIL
Open: P1 stop-fence descendants, P2 UUID suffix, P2 plugins.log, P2 pending-txn UX
Limits: PTY FAIL; sandbox fail-closed workspace-write; isolation/offline/native-sharp not claimed
headFinal/commit NOT FROZEN
```

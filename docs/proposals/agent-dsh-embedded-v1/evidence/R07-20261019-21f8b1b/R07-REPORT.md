# R07 修复轮最终证据报告 — ABSOLUTE FINAL B610E1D0 待短回归

- Run ID：**`R07-20261019-21f8b1b`**
- 状态：**AWAITING short regression on absolute final `B610E1D0…`；`2005DF02` previous PASS_WITH_CAVEATS；`B63149B8` intermediate**
- 更新时间：2026-10-20 05:12 +08:00，由 R00 dsh-baseline 更新；仅文档，未构建、未安装、未做设备操作
- **Absolute final APK**：`b610e1d0d29195e966e705a4a5113f46d8601a16349f45bedb98954ace73ae9a`，393,717,991 B，mtime 05:06:13，0.2.4 (8)，minSdk 26 / targetSdk 36，signer `2619ac4c…a9cf`，non-debuggable；payload + MANIFEST + 7 `libdsh*.so`（R00 预检）
- Previous hardening：`2005df02…`（previous PASS_WITH_CAVEATS）；intermediate：`B63149B8…`；stale：`e0b97fb3…`
- 设备：device-b（Xiaomi 25091RP04C / Android 17 SDK37 / arm64-v8a）
- Absolute final 预检：dex 含 `stop verification failed`/`found survivors`/`survivingPids`/label regexes；runtime 93/93；P1-R1/R2 code-review 关闭；label P2 closed except Cafe2024 edge；device survivor injection NOT_RUN
- `headFinal`：**未冻结**（观察 HEAD `1965bed836de40610948d92e2e1bb62890635d93`，03:56:07）
- 一屏裁决：[R07-DECISION-SHEET.md](R07-DECISION-SHEET.md)（final 列 PENDING）
- 说明：下文 §1–§7 记录的是 previous `2005DF02…` 的 FINAL 结果，仅作历史/对照；**不得复制为 B610E1D0 的 PASS**。
- 说明：下文 §1–§7 记录的是 previous `0A731D84…` 的 FINAL 结果，仅作历史/对照；**不得复制为 2005DF02 的 PASS**。
- 关联：[E1-E9-MATRIX.md](E1-E9-MATRIX.md)、[C1-C7-RESULTS.md](C1-C7-RESULTS.md)、[KNOWN-LIMITS.md](KNOWN-LIMITS.md)、[EVIDENCE-INDEX.md](EVIDENCE-INDEX.md)、[run.json](run.json)

> **最终裁决**：Release E2E **PASS WITH CAVEATS**。官方 UI/工作区/picker/入口/E5/E6/E8/单 Node/无 crash 与 C1–C7 全部 PASS；本地 DSH 回合在 **n_ctx=16384** 完成（1分19秒、6.6K tok、无 n_ctx error），但 0.8B 输出离题，属 **MODEL_QUALITY_ONLY**；12288 仍 **FAIL**；云端 503 **BLOCKED external**；E4/E7/E9 post-start refresh **NOT_RUN**。

## 1. FINAL-ONE-PASS 最终结果（绑定 0A731D84…）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| APK identity/signature/payload/7 libs | **PASS** | `033121/identity/*` |
| Install + firstInstallTime + data preserved | **PASS** | 03:32:04；`033121/install/*` |
| Cold start | **PASS** | 198 ms；`logs/cold-start-am.log` |
| Local model load n_ctx=16384 | **PASS** | `context=16384 backend=mnn_cpu`；`fp-model-ready.png` |
| DSH entry + READY epoch=1 + FIRST_FRAME | **PASS** | origin `:40347`，catalogRevision `dcc6ab27212992ff` |
| Official hero/composer/default workspace | **PASS** | `fp-dsh-10s.png` |
| Picker: human-readable local + cloud | **PASS（caveat）** | 本地 `Qwen3.5 0.8B mnn 47f681e8 … [PROMPTED_TOOL_V1]`；仍带 UUID 后缀 |
| Real adb tap chip switch | **PASS** | `fp-chip-cloud.png` / `fp-chip-local.png` |
| Local DSH turn at 16384 | **PASS（quality caveat）** | 1分19秒 / 6.6K tok；无 n_ctx error；输出离题 |
| E5 re-entry / same host | **PASS** | 同 `:dsh` PID 5669 / Node 5697；会话恢复 |
| E6 background return | **PASS** | HOME 6s → 恢复 |
| E8 Kotlin Agent + chat | **PASS** | `fp-e8-agent.png` / `fp-e8-back-chat.png` |
| Single Node / no crash/ANR | **PASS** | `ps-final.txt`、`crash-scan.txt` |
| C1–C7 code gates | **PASS** | C1 2/2、C2 77/77、C3 68/68、C4 77/77、C5 74/74、C6 chat 357/357 + app 74/74、C7 RC=0（raw log 缺） |

## 2. 带 caveat 的 PASS

| 项 | 结论 | Caveat |
| --- | --- | --- |
| White-pane / official UI | **PASS** | 已修复并 FINAL 复验；非新问题 |
| Picker clipping | **PASS** | 已修复；full list 可见 |
| Local model context | **PASS WITH TUNABLE REQUIREMENT** | **n_ctx ≥ 16384**；12288 FAIL；不是 8192 可用 |
| Display-name human name | **PASS WITH CAVEAT** | 已显示人类名称，但仍带 UUID 后缀；P2 |
| DSH chat | **PASS（protocol/loop）** | 质量 MODEL_QUALITY_ONLY；不代表 0.8B 输出可用 |
| C7 architecture | **PASS** | final raw log 未落盘 |
| C1/C6 chat gates | **PASS** | chat XML 早于 final gate（357/357） |

## 3. FAIL / NOT_RUN / BLOCKED

| 项 | 最终值 | 原因 |
| --- | --- | --- |
| 本地 n_ctx=12288 | **FAIL** | 仍超预算；要求 ≥16384（A4 evidence，最终轮未重测） |
| E4 token 审计 | **NOT_RUN** | 未执行；auth 行为有观察，但无专门审计 |
| E7 受控失败/新代 | **NOT_RUN** | 无安全故障注入 |
| E9 post-start catalog refresh | **NOT_RUN** | documented limitation |
| Cloud model turn | **BLOCKED（external）** | 上游 503；未重测；不是 MCA 代码回归 |
| PTY | **FAIL** | 无真 PTY；普通 pipe ≠ PTY |
| Android sandbox | **fail-closed / workspace-write** | 不宣称完整 sandbox；审批 ask |
| 真隔离 / 全离线 / 原生 sharp | **不宣称** | 同 ADR/BASELINE 限制 |

## 4. 剩余开放项

| ID | 状态 | 内容 |
| --- | --- | --- |
| P1-STOP-FENCE | **OPEN（descendants）** | stop-fence 后代/后继问题仍开放。 |
| P2-MODEL-LABEL | **OPEN（partial）** | human-readable 名称已出现，但仍含 UUID 后缀；不写“完全修复”。 |
| P2-PLUGINS-LOG | OPEN | `ERR_MODULE_NOT_FOUND restructure/src/EncodeStream.js` + spill-store unsafe-root。 |
| P2-PENDING-PROFILE-TXN | OPEN UX | pending profile transaction 可跨 force-stop；下次 load 报 recovery，apply/rollback 禁用，需第二次重启。 |
| P3-STOP-MARKER | OPEN observation | DSH 后 MCA chat `上下文 KV 未命中 · stop_marker`；chat 仍可用。 |
| C7-RAW-LOG | OPEN | architecture PASS 只有 Lead 报告；final raw log 缺。 |
| headFinal/commit | **NOT FROZEN** | HEAD/commit 未冻结；观察 `4a60709b…`。 |

## 5. 证据路径

| 类别 | 路径 |
| --- | --- |
| FINAL-ONE-PASS | `artifacts/dsh-verify/20261020-033121/`（`FINAL-ONE-PASS.md`、`verdict.json`、`identity/`、`install/`、`shots/fp-*`、`dumps/fp-*`、`logs/`） |
| A4 previous release | `artifacts/dsh-verify/20261020-024856/` |
| A4 model adaptation | `artifacts/dsh-verify/20261020-025707/` |
| 历史 | `20261020-001623`（A1）、`20261020-013442`（A2）、`20261020-022248`（A3） |
| Code gates | `E:\tmp\dsh-final-gates\` + Gradle XML |
| R07 docs | `docs/proposals/agent-dsh-embedded-v1/evidence/R07-20261019-21f8b1b/` |

## 6. 一屏最终裁决

```text
R07 FINAL VERDICT — 0A731D84…FB62 (393,714,839 B, 0.2.4/8, min 26/target 36)
Device: device-b / Android 17 SDK37 / arm64-v8a
Release E2E: PASS WITH CAVEATS (run 20261020-033121; install data preserved; 198 ms cold start)
C1–C7: PASS — 2/2, 77/77, 68/68, 77/77, 74/74, 357/357+74/74, architecture RC=0 (C7 raw log missing)
E1–E9: E1 PASS, E2 PASS, E3 PASS, E4 NOT_RUN, E5 PASS, E6 PASS, E7 NOT_RUN, E8 PASS,
        E9 pre-start PASS / post-start refresh NOT_RUN
DSH chat: PASS (protocol/loop) at n_ctx=16384 — 1m19s, 6.6K tok; quality=MODEL_QUALITY_ONLY
Local adaptation: 12288 FAIL, 16384 PASS; requirement n_ctx >= 16384 (tunable)
Cloud: BLOCKED external 503
Open: P1 stop-fence descendants; P2 display-name UUID suffix; P2 plugins.log; P2 pending-txn UX
Limits: PTY FAIL; Android sandbox fail-closed (workspace-write); isolation/offline/native-sharp not claimed
headFinal/commit: NOT FROZEN
Decision: PASS WITH CAVEATS — not clean all-green
```

## 7. 声明

最终候选 `0A731D84…` 已由 FINAL-ONE-PASS 验证并绑定；结论是 **PASS WITH CAVEATS**。不得改写成“全部通过”：12288 仍 FAIL、云端 BLOCKED、E4/E7/post-start refresh NOT_RUN、PTY FAIL、sandbox fail-closed、P1/P2 开放、headFinal 未冻结。UI/测试 PASS 不等于输出质量可接受；0.8B 质量 caveat 必须随结论保留。

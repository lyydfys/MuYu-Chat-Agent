# R07 修复轮证据索引 — ABSOLUTE FINAL B610E1D0 待短回归

- Run ID：`R07-20261019-21f8b1b`
- 状态：**Absolute final `B610E1D0…` 已固定、short regression PENDING；`2005DF02` previous；`B63149B8` intermediate；`E0B97FB3` stale**
- 上级：[R07-REPORT.md](R07-REPORT.md)、[R07-DECISION-SHEET.md](R07-DECISION-SHEET.md)；清单：[run.json](run.json)

> 外部证据在 `E:\MAC\artifacts\dsh-verify\`。`PRESENT` = 已收到并复核；`PENDING` = 尚未完成；`MISSING` = 无匹配证据。

## 0. Absolute final candidate（B610E1D0…，short regression pending）

| 证据 | 内容 | 状态 |
| --- | --- | --- |
| `app/build/outputs/apk/release/app-release.apk` | SHA-256 `b610e1d0d29195e966e705a4a5113f46d8601a16349f45bedb98954ace73ae9a`，393,717,991 B，0.2.4 (8)，05:06:13；签名 `2619ac4c…a9cf` | **PRESENT（R00 预检）** |
| APK 内容 | `assets/dsh/MANIFEST.json` + `payload.zip`；7 个 `lib/arm64-v8a/libdsh*.so` | **PRESENT（R00 预检）** |
| Dex closure proof | `stop verification failed` ×1；`found survivors` ×1；`survivingPids` ×1；label regexes 各 ×1；`refusing start orphans`/`Cafe2024` 未逐字命中 | **PRESENT（部分）** |
| Runtime tests | `core/dsh-runtime/build/test-results/testDebugUnitTest`：93/93，0 failures（17 suites） | **PRESENT** |
| Code review | P1-R1/R2 signed off closed（Lead） | **PRESENT（Lead）** |
| Short regression | label/Cafe2024 edge + local turn + E5 | **PENDING** |
| Runtime tests | `core/dsh-runtime/build/test-results/testDebugUnitTest`：80/80，0 failures（XML 早于 build） | **PRESENT** |
| FINAL-ONE-PASS | label without UUID + local turn + E5 | **PENDING** |

## 0.1 Previous final candidate（0A731D84…，PASS_WITH_CAVEATS）

| 证据 | 内容 | 状态 |
| --- | --- | --- |
| `app/build/outputs/apk/release/app-release.apk`（previous） | SHA-256 `0a731d84507813daf0f1dda64eaf8e98e2f934911cc71cbe839a4e5fbfcdfb62`，393,714,839 B，03:27:50 | **PRESENT（previous）** |
| FINAL-ONE-PASS | `artifacts/dsh-verify/20261020-033121/`：FINAL-ONE-PASS.md、verdict.json、identity/install/shots/dumps/logs | **PRESENT（previous）** |
| 本地回合 | n_ctx=16384；1分19秒 / 6.6K tok；quality MODEL_QUALITY_ONLY | **PRESENT（previous）** |

## 0.1 Previous A4 release（59A40724…，provisional）

| 证据 | 内容 | 状态 |
| --- | --- | --- |
| `app/build/outputs/apk/release/app-release.apk`（旧） | `59a40724…31DF`，393,702,651 B，02:44:37 | **PRESENT（previous）** |
| Release E2E | `artifacts/dsh-verify/20261020-024856/`（RELEASE-REPORT.md、verdict.json、identity/install/shots/logs） | **PRESENT（provisional）** |
| Model adaptation | `artifacts/dsh-verify/20261020-025707/`（12288 FAIL / 16384 PASS；context=16384；quality caveat） | **PRESENT（provisional）** |
| `E:\tmp\dsh-final-gates\release3.log`、`index.txt` | BUILD SUCCESSFUL 22m06s；`RELEASE3_RC=0` | **PRESENT** |

## 1. A3（diagnostic 294d9656，历史）

| 证据 | 内容 | 状态 |
| --- | --- | --- |
| `20261020-022248/FINAL-REPORT.md`、`verdict.json` | A3 判定、模型后端失败/阻断、picker PASS | **PRESENT** |
| `20261020-022248/logs/picker-evidence.json` | menu 90/360px（A2 0/0）；两模型；真实 adb tap | **PRESENT** |
| `20261020-022248/logs/cdp-menu-css.json` | vh/dvh probe；menu computed CSS | **PRESENT** |
| `20261020-022248/logs/cdp-local-error.json`、`cdp-cloud-error2.json` | local FAIL n_ctx 8192；cloud 503 | **PRESENT** |
| `20261020-022248/logs/ps-*.txt`、`logcat-e5-reentry.txt` | 单 Node / 同 PID / 无新 READY | **PRESENT** |
| `20261020-022248/shots/a3-*.png` | UI/picker/local error/cloud error/E5/chat | **PRESENT** |
| A3 APK identity/install | `294d9656…`、563,846,247 B、02:23:57 原位安装、数据保留 | **PRESENT** |

### 1.1 A4 本地模型适配验证（PENDING）

| 证据 | 内容 | 状态 |
| --- | --- | --- |
| tuning override 变更 | n_ctx 8192 → 12288/16384 + reload | **PENDING** |
| engine active n_ctx | reload 后实际上下文 | **PENDING** |
| gateway `/v1/models` contextWindow | overlay/catalog 是否以新 contextWindow 重播种 | **PENDING** |
| DSH host stop fence + new epoch | 必须 stop→ensureStarted，不能 READY 时 restart | **PENDING** |
| real DSH turn | 成功 / `PI_AI_ERROR` / tokens + 截图/日志 | **PENDING** |
| 结论规则 | 成功→“requires n_ctx ≥ ~12k (tunable)”；失败→保留 FAIL + 新证据；verifier 前不得 chat PASS | 规则已记录 |

## 2. 历史（A1/A2）

| 证据 | 内容 | 状态 |
| --- | --- | --- |
| `20261020-001623/*` | A1 release `4500a34e…`：白屏失败、E1/E8 等历史 | **PRESENT（历史）** |
| `20261020-013442/identity/*` | A2 diagnostic `0d48974f…` 身份 | **PRESENT（历史）** |
| A2 E2E | picker 裁剪失败等 | **SUPERSEDED by A3** |
| `E:\tmp\f9-u1.png`、`f9-u2.png` | 空模型 Lead 证据 | **PARTIAL（非独立 verifier）** |
| `E:\tmp\f9-vp3.png` | 白屏修复视觉（Lead） | **PRESENT（Lead，历史）** |

## 3. C1–C7

| 证据 | 内容 | 状态 |
| --- | --- | --- |
| Gradle XML：runtime / workspace / gateway / app / chat | C2 77/77、C3 68/68、C4 77/77、C5 74/74、C1 2/2、C6 chat 357/357 | **PRESENT** |
| `E:\tmp\dsh-final-gates\modules.log`、`app-dsh.log`、`diag3.log`、`diag4.log` | BUILD SUCCESSFUL | **PRESENT** |
| C7 final raw log | Lead 报告 PASS；最终日志未在 `dsh-final-gates` 找到 | **MISSING** |
| C1/C6 chat XML 时间戳 | 23:32（早于 A3 final gate） | **PARTIAL** |

## 4. 模型后端（独立失败面）

| 证据 | 结果 | 状态 |
| --- | --- | --- |
| `shots/a3-local-sent-error.png`、`logs/cdp-local-error.json` | 本地 agent turn **FAIL**：n_ctx=8192、PI_AI_ERROR、0 tokens | **PRESENT** |
| `shots/a3-cloud-sent-26s.png`、`logs/cdp-cloud-error2.json` | 云端 **BLOCKED external**：503 无可用渠道 | **PRESENT** |
| DSH chat | **NONE**（无成功回合） | **FAIL/BLOCKED** |

## 5. 限制与缺陷

| 项 | 状态 |
| --- | --- |
| PTY FAIL、sandbox fail-closed workspace-write、无真隔离、模型刷新未验证、原生 sharp/全离线不宣称 | 继续有效 |
| 白屏 / picker 裁剪 | **CLOSED_VERIFIED_A3** |
| 模型 UUID 标签、plugins.log 警告 | OPEN（P2） |
| `stop_marker` 观察 | OPEN（P3） |
| E4 token 审计、E7 故障注入、E9 post-start refresh | **MISSING** |

## 6. 采集规则

- 证据必须绑定同一 APK SHA、设备 fingerprint、采集时间；A1/A2/A3 分开，不互相覆盖。
- A4 release 已构建（`59A40724…31DF`，393,702,651 B，02:44:37），**release E2E 仍 PENDING**；diagnostic A3 通过不等于 release 通过。
- 不得把 UI PASS 写成 chat PASS；本地 FAIL、云端 BLOCKED 必须保留。
- C7 最终日志与 C1/C6 重跑结果需在 release 证据前补齐。

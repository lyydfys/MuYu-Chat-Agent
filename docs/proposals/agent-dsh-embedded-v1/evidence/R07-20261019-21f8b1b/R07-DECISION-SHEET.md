# R07 最终决策单 — ABSOLUTE FINAL CANDIDATE B610E1D0 PENDING SHORT REGRESSION

- Run ID：`R07-20261019-21f8b1b`
- 状态：**AWAITING short regression PASS on absolute final `B610E1D0…`；`2005DF02` previous；`B63149B8` intermediate**
- 代码冻结：是；R00 只写文档，未构建、未安装
- **Absolute final APK**：`b610e1d0d29195e966e705a4a5113f46d8601a16349f45bedb98954ace73ae9a`，393,717,991 B，mtime 2026-10-20 05:06:13，0.2.4 (8)，minSdk 26 / targetSdk 36，signer `2619ac4c…a9cf`，non-debuggable；payload + MANIFEST + 7 `libdsh*.so`（R00 预检）
- Previous hardening：`2005df024a66bc5c21123e73dafa71d7c1c80a319f5048003d64aed2ef7b94dc`（393,715,727 B）——previous PASS_WITH_CAVEATS
- Intermediate superseded：`B63149B8…`（identity 未记录）；stale `E0B97FB3…`
- Device：device-b（Xiaomi 25091RP04C / Android 17 SDK37 / arm64-v8a）
- **Verifier short regression：PENDING**
- 规则：final 列在短回归 PASS 前一律 `PENDING`；返回后绑定 `B610E1D0…`，再发一屏裁决。

## 1. Absolute final 预检（R00，只读）

| 项 | 值 | 状态 |
| --- | --- | --- |
| APK identity / signer / payload / 7 libs | SHA/大小/aapt/apksigner/zip | **PASS（R00）** |
| Dex closure strings | `stop verification failed` ×1、`found survivors` ×1、`survivingPids` ×1、label regexes 各 ×1 | **PRESENT（R00）** |
| Dex `refusing start orphans` | 逐字未命中（Lead 报存在；可能是不同措辞/其他 dex） | **NOT_FOUND_VERBATIM** |
| Dex `Cafe2024` | 逐字未命中 | **NOT_FOUND_VERBATIM** |
| Runtime tests | 93/93，0 failures（17 suites，latest 04:57:35） | **PASS（XML）** |
| P1-R1 / P1-R2 | **signed off closed by code review**（Lead） | **CLOSED（code review）** |
| Label P2 | **closed except Cafe2024 edge**（Lead）；行为待短回归 | **PENDING behavior** |
| Device survivor injection | — | **NOT_RUN** |

> Dex/mapping 存在只证明代码被打包；label/Cafe2024 行为以 verifier 短回归为准。

## 2. 全量 case（final 全部 PENDING，绑定 B610E1D0…）

| # | Case | Previous 2005DF02 | Absolute B610E1D0 final |
| --- | --- | --- | --- |
| 01 | APK identity/signature/payload/7 libs | PASS | **PENDING** |
| 02 | Install/data preserved | PASS | **PENDING** |
| 03 | Cold start | PASS 191 ms | **PENDING** |
| 04 | C1 Chat navigation | PASS 2/2 | **PENDING** |
| 05 | C2 DSH runtime | PASS 80/80 | **PENDING**（Lead 报 93/93） |
| 06 | C3 DSH workspace | PASS 68/68 | **PENDING** |
| 07 | C4 Gateway | PASS 77/77 | **PENDING** |
| 08 | C5 App DSH | PASS 74/74 | **PENDING** |
| 09 | C6 Chat + app DSH | PASS 357/357 + 74/74 | **PENDING** |
| 10 | C7 Architecture | PASS（raw log 缺） | **PENDING** |
| 11 | E1 入口 | PASS | **PENDING** |
| 12 | E2 官方 UI + workspace | PASS | **PENDING** |
| 13 | E3 官方前端 + picker | PASS | **PENDING**（label/Cafe2024 edge） |
| 14 | E4 token 审计 | NOT_RUN | **PENDING → 预计 NOT_RUN** |
| 15 | E5 重进/恢复/单 Node | PASS | **PENDING**（短回归） |
| 16 | E6 后台恢复 | PASS | **PENDING** |
| 17 | E7 失败注入 | NOT_RUN | **PENDING → 预计 NOT_RUN** |
| 18 | E8 模式隔离 | PASS | **PENDING** |
| 19 | E9 pre-start model | PASS | **PENDING** |
| 20 | E9 post-start refresh | NOT_RUN | **PENDING → 预计 NOT_RUN** |
| 21 | DSH chat protocol/loop | PASS at n_ctx=16384 | **PENDING**（短回归 local turn） |
| 22 | 本地 n_ctx=12288 | FAIL | **PENDING → 预计 FAIL** |
| 23 | 本地 n_ctx=16384 | PASS | **PENDING** |
| 24 | 输出质量 | MODEL_QUALITY_ONLY | **PENDING**（caveat 继续） |
| 25 | Cloud turn | BLOCKED external 503 | **PENDING → 预计 BLOCKED** |
| 26 | Single Node/no crash | PASS | **PENDING** |

## 3. 必须保留的 caveat

- **Local context**：n_ctx=12288 FAIL；**n_ctx=16384 PASS**；要求 **n_ctx ≥ 16384（tunable）**；质量 **MODEL_QUALITY_ONLY**。
- **Cloud**：上游 503 **BLOCKED external**。
- **PTY**：**FAIL**；**Android sandbox**：**fail-closed / workspace-write**；真隔离/全离线/原生 sharp 不宣称。
- **E4/E7/E9 post-start refresh**：**NOT_RUN**。
- **Device survivor injection**：**NOT_RUN**（即使 P1-R1/R2 code review 关闭）。
- **C7**：final raw log 缺；**headFinal/commit**：**NOT FROZEN**（观察 HEAD `1965bed836de40610948d92e2e1bb62890635d93`，03:56:07）。
- **plugins.log packaging**、**pending-profile UX** 仍开放。

## 4. 开放/关闭项

| ID | 状态 | 内容 |
| --- | --- | --- |
| P1-R1 crash/service-restart orphan scan | **CLOSED（code review）** | Lead 签核关闭；device 注入仍 NOT_RUN |
| P1-R2 client synthesized STOPPED | **CLOSED（code review）** | Lead 签核关闭 |
| P1-STOP-FENCE | code-closed；device survivor injection NOT_RUN | dex 含 stop verification failed/found survivors/survivingPids |
| P2-MODEL-LABEL | **CLOSED except Cafe2024 edge**（Lead） | 行为待短回归；Cafe2024 逐字未在 dex 命中 |
| P2-PLUGINS-LOG | OPEN | restructure/EncodeStream.js + spill-store |
| P2-PENDING-PROFILE-TXN | OPEN UX | pending profile transaction recovery |
| P3-STOP-MARKER | OPEN observation | `上下文 KV 未命中 · stop_marker`；chat 仍可用 |
| C7-RAW-LOG | OPEN | final raw log 缺 |
| headFinal/commit | **NOT FROZEN** | 未冻结 |

## 5. 精确 artifact 路径

| 类别 | 路径 |
| --- | --- |
| Absolute final APK | `E:\MAC\app\build\outputs\apk\release\app-release.apk`（`b610e1d0…ae9a`，393,717,991 B，05:06:13） |
| Short regression | **PENDING** — 收到后写 `artifacts/dsh-verify/<run>/` |
| Previous hardening | `artifacts/dsh-verify/20261020-041600/`（2005DF02） |
| Previous final | `artifacts/dsh-verify/20261020-033121/`（0A731D84） |
| A4 release/model adaptation | `artifacts/dsh-verify/20261020-024856/`、`20261020-025707/` |
| History | `20261020-001623`（A1）、`20261020-013442`（A2）、`20261020-022248`（A3） |
| Runtime tests | `core/dsh-runtime/build/test-results/testDebugUnitTest`（93/93） |
| R07 docs | `docs/proposals/agent-dsh-embedded-v1/evidence/R07-20261019-21f8b1b/` |

## 6. 一屏最终裁决模板（待短回归）

```text
R07 FINAL VERDICT — B610E1D0…AE9A (393,717,991 B, 0.2.4/8)
Device: device-b / Android 17 SDK37 / arm64-v8a
Short regression: <PASS/FAIL/NOT_RUN/BLOCKED> (run <verifierRun>)
C1–C7: <matrix> (C2 expected 93/93; C7 raw log missing)
E1–E9: <E1/E2/E3/E5/E6/E8/E9 pre-start; E4/E7/E9 post-refresh NOT_RUN>
DSH chat: <PASS/FAIL/NOT_RUN> at n_ctx=16384; quality=MODEL_QUALITY_ONLY
Local adaptation: 12288 FAIL, 16384 PASS; requirement n_ctx>=16384 (tunable)
Label: <P2 closed except Cafe2024 edge / unresolved>
Stop-fence: code-closed/P1-R1/R2 code-review closed; device survivor injection NOT_RUN
Cloud: BLOCKED external 503; PTY FAIL; sandbox fail-closed workspace-write
Open: plugins.log; pending-txn UX; C7 raw log; headFinal NOT FROZEN
Decision: <PASS_WITH_CAVEATS / HOLD>
```

## 7. 填充规则

1. 只接受绑定 **B610E1D0…AE9A**、设备 device-b、采集时间的短回归证据；2005DF02 是 previous，B63149B8 是 intermediate，E0B 是 stale。
2. final 列只能写 `PASS/FAIL/NOT_RUN/BLOCKED`；无证据保持 `PENDING` 或最终 `NOT_RUN`。
3. 不得把 2005DF02/0A 的 PASS 复制给 B610E1D0；不得把 12288 写成可用；不得把 MODEL_QUALITY_ONLY 写成协议失败。
4. 若 Café2024 或其他模型名仍出现 hex token，label P2 不能写 fully closed。
5. 收到 short regression PASS 后填满本表并发送一屏裁决；在此之前不发送最终 PASS。

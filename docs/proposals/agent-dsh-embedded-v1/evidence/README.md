# R07 证据目录规范（evidence/）

本目录保存 R07 最终验证的原始证据。R07 执行前不要提交伪造、占位或“示例通过”证据。

## 0. 当前状态：ABSOLUTE FINAL B610E1D0 待短回归（2026-10-20 05:12 更新）

- `R07-20261019-21f8b1b/` 是**修复轮证据目录**；当前 `run.json` 状态为 `AWAITING_FINAL_ONE_PASS_ABSOLUTE_CANDIDATE`。
- **Absolute final APK**：`b610e1d0d29195e966e705a4a5113f46d8601a16349f45bedb98954ace73ae9a`，393,717,991 B，0.2.4 (8)，05:06:13；R00 预检 identity/payload/7 libs 通过；dex 含 `stop verification failed`/`found survivors`/`survivingPids`/label regexes；runtime 93/93；P1-R1/R2 code-review closed；label P2 closed except Cafe2024 edge。**verifier short regression 未返回。**
- **Previous**：`2005DF02…` previous PASS_WITH_CAVEATS；`B63149B8…` intermediate；`E0B97FB3…` stale。
- **保留 caveat**：device survivor injection NOT_RUN；cloud 503 BLOCKED；0.8B quality MODEL_QUALITY_ONLY；E4/E7/E9 post-refresh NOT_RUN；PTY FAIL；sandbox fail-closed；C7 raw log；headFinal 未冻结；plugins.log/pending-txn UX。
- 最终裁决在 [R07-DECISION-SHEET.md](R07-20261019-21f8b1b/R07-DECISION-SHEET.md)：B610E1D0 final 列全部 PENDING，不得写 PASS。
- 一屏裁决：[R07-DECISION-SHEET.md](R07-20261019-21f8b1b/R07-DECISION-SHEET.md)。
- 最终裁决在 [R07-DECISION-SHEET.md](R07-20261019-21f8b1b/R07-DECISION-SHEET.md)：2005DF02 final 列全部 PENDING，不得写 PASS。
- 若 verifier 通过：label P2 closed；stop-fence P1 code-closed（device survivor injection 仍 NOT_RUN）；release PASS with same model/cloud/PTY caveats。
- 限制继续有效：PTY FAIL；Android sandbox fail-closed（workspace-write）；真隔离/全离线/原生 sharp 不宣称；headFinal/commit 未冻结。
- 最终发布仍须：A4 release E2E + local adaptation 结果、补 C7/C1/C6 证据、冻结 `headFinal`。**diagnostic 通过不等于 release 通过，UI PASS 不等于 chat PASS。**

## 1. 目录结构

```text
docs/proposals/agent-dsh-embedded-v1/evidence/
├── README.md                     # 本文件（R00）
└── <runId>/                      # 每次 R07 pass 一个目录
    ├── run.json                  # 该 pass 的机器可读清单（必须有）
    ├── R07-REPORT.md             # 该 pass 的最终报告
    ├── C1-C7-RESULTS.md          # 修复轮测试门（如适用）
    ├── E1-E9-MATRIX.md           # 修复轮真机矩阵（如适用）
    ├── KNOWN-LIMITS.md           # 该轮限制
    ├── EVIDENCE-INDEX.md         # 证据索引
    ├── V00__freeze-packet__1.json
    ├── V01__arch-rules__1.log
    ├── ...
    └── V22__r07-report__1.md
```

- `<runId>` 格式：`R07-<YYYYMMDD>-<candidate-short>`，例如 `R07-20261025-98eb8e4`。修复轮可用修复基准 commit 前 7 位（当前骨架即 `21f8b1b`，并在 `run.json` 另记最终 `headFinal`）。
  - `candidate-short` 取候选 HEAD 前 7 位；同一日多个 pass 加 `-2`、`-3`。
  - `runId` 一旦用于一次冻结输入，不得复用或覆盖。
- 每个用例一个子前缀（`V00`…`V22`；修复轮增量用 `C1`…`C7` / `E1`…`E9`），见 [VERIFICATION_PLAN.md](../VERIFICATION_PLAN.md)。
- 历史 run 保留原样；不可在原地改结果或覆盖文件。重跑新建 `runId`。

## 2. 文件命名

```text
<caseId>__<slug>__<n>.<ext>
```

- `caseId`：`V00`…`V22`，与验证计划一致。
- `slug`：小写 kebab-case，描述证据内容（如 `apk-signature`、`nav-image-agent-dsh`、`cancel-four-stage`）。
- `n`：同一用例同类型证据的序号，从 `1` 开始；不重复占用。
- `ext`：按证据类型：
  - `.log`：原始命令/进程 stdout+stderr（不手工删改；需要说明时另写 `.md`）
  - `.txt`：纯文本输出、属性、命令结果
  - `.json`：机器可读结果、manifest、逐行结果表
  - `.md`：人工说明、四段取消记录、检查矩阵
  - `.png`：截图原图
  - `.mp4`：屏幕录制原文件
  - `.zip`：受控的日志/数据快照归档（大文件优先放受控本地存储并在 `run.json` 写路径+hash，而不是塞进仓库）

## 3. run.json 必填字段

```json
{
  "runId": "R07-20261025-98eb8e4",
  "startedAt": "2026-10-25T09:00:00+08:00",
  "finishedAt": "2026-10-25T20:00:00+08:00",
  "executor": "name/role",
  "result": "PASS | FAIL | BLOCKED | NOT_RUN",
  "source": {
    "mca": {
      "head": "...",
      "branch": "codex/enterprise-refactor",
      "trackedDiffSha256": "...",
      "untrackedInventorySha256": "...",
      "submodules": [
        {"path": "third_party/llama.cpp", "revision": "...", "internalDiffSha256": "..."}
      ]
    },
    "officialDsh": {
      "commit": "687f282e8625c6b8d11ff58211a90524be2e4317",
      "packageVersion": "0.2.1-rc.2",
      "lockfileSha256": "..."
    },
    "payload": {
      "apkUrl": "...",
      "apkSha256": "...",
      "sizeBytes": 0,
      "packageName": "com.muyuchat.mca",
      "versionCode": 0,
      "versionName": "...",
      "targetSdk": 36,
      "payloadManifestSha256": "..."
    }
  },
  "device": {
    "model": "...", "androidRelease": "...", "apiLevel": 36,
    "abi": "arm64-v8a", "buildFingerprint": "...",
    "memoryBytes": 0, "storageFreeBytes": 0, "screen": "..."
  },
  "toolchain": {"jdk": "...", "gradle": "...", "agp": "...", "kotlin": "...", "sdk": "...", "ndk": "..."},
  "cases": [
    {
      "caseId": "V06",
      "referenceResult": "NOT_RUN",
      "phoneResult": "PASS",
      "evidence": ["V06__runtime-phases__1.log"],
      "startedAt": "...", "finishedAt": "...",
      "notes": "what was and was not proven"
    }
  ],
  "redactions": ["launch token / model key removed; field names preserved"],
  "notes": "free-form, must not hide failures"
}
```

- `referenceResult` 与 `phoneResult` 必须分开；没有官方控制组时 `referenceResult` 为 `NOT_RUN`，不能写“与官方一致”。
- 所有时间用带时区的 ISO 8601。
- `run.json` 的结果必须与 `V22__results__1.json` 和原始证据一致。

## 4. 出处与可复现

- 每个证据文件必须能追溯到：命令/操作、执行者、时间、产物/设备身份。日志开头建议保留命令与版本行。
- 截图/录屏不得裁剪、拼接、美图或修改结论相关区域；允许加边界说明，但原始文件必须保留。
- 日志不得手工删行；敏感信息脱敏时保留字段名与结构，另写 `redactions` 说明。
- 大文件（长录屏、完整 logcat、payload）如不进仓库，必须在 `run.json` 写受控路径、大小与 SHA-256；受控副本随候选保留，不得只留口头描述。
- 证据一旦写入，不改不覆盖；需要更正时新增文件并说明关系。

## 5. 结果与判定

- 结果词汇固定为 `PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`，判定规则见 [VERIFICATION_PLAN.md](../VERIFICATION_PLAN.md#3-结果定义与判定规则)。
- 不允许把 `FAIL` 改判为 `PASS`，也不允许把必需项失败改判为 `NOT_APPLICABLE` 逃避验收。
- “静态存在/编译成功/UI 相似/官方代码”不是 `PASS`。
- 真 PTY、原生 sharp、隔离、全离线等命题必须有真实机制证据；参考 payload 的替换实现不能升级这些结论。
- 历史证据可以引用，但必须注明原 `runId`/revision/产物/设备与适用理由，不能改日期冒充本次运行。

## 6. 敏感信息与隐私

- 禁止保存：launch token、模型/云密钥、用户真实会话内容、凭据文件、签名私钥。
- logcat/截图/录屏/报告都必须脱敏；token 只记录“出现/未出现”与位置类别，不记录值。
- 用户数据的导入/导出测试使用脱敏 fixture 或受控测试账号；证据中不得出现真实私人内容。
- 证据目录本身不进入对外分发，除非经用户明确授权并完成许可证/隐私复核。

## 7. 所有权

- R07 验证者写本目录下的 run 证据；R00 只写本 README。
- 整合者审阅报告与证据，不对原始证据做美化；发布决定基于证据，不基于摘要。
- 清理临时证据必须经验证者与整合者确认，且不能删除与发布结论相关的原始记录。

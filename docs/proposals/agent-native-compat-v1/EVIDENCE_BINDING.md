# CURRENT 验收证据绑定契约（EVIDENCE_BINDING）

日期：2026-10-26 · 状态：工具化契约（不新增任何验收 PASS）

本文件规定“历史状态（HISTORICAL）”与“当前证据（CURRENT）”如何分离、CURRENT 证据如何被工具强制绑定。
权威实现：`tools/agent/bind_case_evidence.py`；状态视图生成器：`tools/agent/parity_cases.py`。

## 1. 硬分离

- `parity-cases.json` 的 `status` / `attempts` / 历史 `evidenceRefs` 是 **HISTORICAL**：只记录旧 revision / 旧 APK 上
  做过什么。`bind_case_evidence.py` **从不写 `status`**。
- CURRENT 只写两个独立字段：
  - `currentStatus`：`PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`；
  - `currentEvidence`：指纹、HEAD、命令、executedTests、exitCode、层、记录路径等结构化摘要。
- 原始证据记录写到 `artifacts/agent-native-thirdparty/evidence/<caseId>/<timestamp>.json`；`currentEvidence.recordPath`
  指向它，`evidenceRefs` 只追加一条 `current:` 前缀的紧凑引用。
- `ACCEPTANCE_STATUS.md` 由生成器同时渲染两列：`历史状态` 与 `CURRENT`。CURRENT 只在证据指纹与当前快照
  指纹一致时才显示 `currentStatus`，否则一律 `NOT_RUN`；历史状态列永不因 CURRENT 绑定而变化。

## 2. 当前快照锚点（current-snapshot.json）

生成器读取 `artifacts/agent-native-thirdparty/current-snapshot.json`（可选；缺失时表头明确显示 CURRENT 未绑定）：

```json
{
  "schemaVersion": "mca.current-snapshot/1",
  "head": "<40-hex HEAD>",
  "fingerprint": "<64-lowercase-hex build-guard worktree fingerprint>",
  "generatedAt": "<UTC ISO-8601，来自 fingerprint JSON 的 generatedUtc>",
  "definition": "<CURRENT PASS 的文字定义>"
}
```

指纹来源（build-guard，唯一可信来源）：

```powershell
# 任选可用的 PowerShell 入口（本机外层 shell 为 Windows PowerShell 5.1 时也能跑）
powershell -NoProfile -File E:\MAC\tools\build-guards\McaWorktreeFingerprint.ps1 -RepoRoot E:\MAC -EmitJson |
  Out-File -Encoding utf8 $env:TEMP\mca-fingerprint.json

# 只取元数据（用 Python 读，避免 Windows PowerShell 5.1 把无 BOM 的中文 JSON 按 ANSI 读坏）
python -c "import json;d=json.load(open(r'$env:TEMP\mca-fingerprint.json',encoding='utf-8'));print({k:d[k] for k in ('head','fingerprint','generatedUtc','files')})"
```

脚本参数也可以直接 dot-source 后在 PowerShell 内调用 `Get-McaWorktreeFingerprint -RepoRoot E:\MAC`，只取
`schema / generatedUtc / head / files / fingerprint`；不要把完整 entries 写进分发文档。

该脚本按以下内容做 SHA-256：HEAD、每个 dirty/untracked 文件内容、子模块 HEAD 与内部 dirty 文件、两个本地保护文件
（只读哈希，不发布）。`artifacts/` 与 `build/` 等目录按目录名排除，因此**写证据、写状态视图、写 ROUND-LOG 不会改变指纹**；
写 `tools/`、`docs/`、`app/` 等源码/文档会改变指纹。

绑定前必须先用同一快照跑一次 build-guard，并在运行结束前再跑一次；两次不一致（`input-changed-during-run`）时结果无效。
快照本身不是“运行证明”，必须与命令、测试数、退出码一起进入证据记录。

## 3. CLI

```powershell
python tools\agent\bind_case_evidence.py --case WF-01 --status PASS `
  --fingerprint <64-hex> --head <40-hex> `
  --command "verify-code.ps1 -Tests '*WorkflowDefinition*Test' (exit 0)" `
  --executed-tests 12 --exit-code 0 --layers U/I/D `
  --evidence-ref artifacts\agent-native-thirdparty\verify-20261026\workflow-definitions
```

阻断/失败示例（不要求指纹，只要求 HEAD/命令/issue）：

```powershell
python tools\agent\bind_case_evidence.py --case TOOL-19 --status BLOCKED `
  --head <40-hex> --command "device stdio probe not attempted" `
  --issue artifacts/agent-native-thirdparty/ISSUES.md#device-01
```

参数与强制规则：

| 参数 / 规则 | 说明 |
| --- | --- |
| `--case` / `--status` | 必需；case 必须存在于 121 项登记，状态必须合法 |
| `--head` / `--command` | 必需；HEAD 必须 40-hex，命令必须非空（去敏） |
| `--fingerprint` | PASS 必需；64 个小写十六进制字符，且必须等于 `current-snapshot.json` 的 fingerprint |
| `--executed-tests` | PASS 必须 `> 0`；禁止“空绿灯” |
| `--exit-code` | PASS 必须 `= 0` |
| `--layers` | PASS 时必须是 case 必需层的**超集**（从 `parity-cases.json` 读取）；未知层直接拒绝 |
| `--issue` | `FAIL` / `BLOCKED` 必需 |
| `--evidence-ref` | 可重复；原始证据引用（日志/截图/XML 路径等） |
| 缺 `current-snapshot.json` | PASS 拒绝：没有当前锚点就没有 CURRENT PASS |

成功写出的证据记录（节选）：

```json
{
  "schemaVersion": "mca.case-evidence/1",
  "caseId": "WF-01",
  "status": "PASS",
  "recordedAt": "2026-10-26T20:35:00+08:00",
  "fingerprint": "<64-hex>",
  "head": "<40-hex>",
  "command": "<命令>",
  "executedTests": 12,
  "exitCode": 0,
  "suppliedLayers": ["U", "I", "D"],
  "requiredLayers": ["D", "I", "U"],
  "coversRequiredLayers": true,
  "evidenceRefs": ["<原始证据路径>"],
  "issue": null,
  "snapshot": {"head": "<40-hex>", "fingerprint": "<64-hex>", "generatedAt": "<UTC>"}
}
```

## 4. 重新生成不丢头

`tools/agent/parity_cases.py` 从 `current-snapshot.json` 生成 HISTORICAL vs CURRENT 表头与 CURRENT 列；
`merge_existing` 同时保留历史 `status` 与 CURRENT 字段。因此：

- `python tools/agent/parity_cases.py` 重生成后，HISTORICAL/CURRENT 表头仍在，121 项与 118 条历史 evidenceRefs 不丢；
- 手工改 `ACCEPTANCE_STATUS.md` 不再是必要的，也不应在工具生成后把它当成状态权威；
- 历史状态被 CURRENT 绑定污染、或没有指纹/命令/测试数/退出码的 PASS，都属于伪造绿灯，审查时直接判 FAIL。

## 5. 验证

```powershell
python -m unittest discover -s tools/agent -p "test_bind_case_evidence.py" -v
```

测试覆盖：PASS 缺指纹/指纹不匹配/无快照/0 tests/非 0 exit/层子集全部拒绝；BLOCKED 缺 issue 拒绝；
历史 `status` 不被改写；registry 仍 121 项；118 条历史 evidenceRefs 保留；生成器表头与 CURRENT 列存在。

## 6. 不做什么

- 不自动把 HISTORICAL PASS 复制成 CURRENT PASS；
- 不根据文件存在、页面存在或“测试名存在”自动升 PASS；
- 不在本工具内运行 Gradle/设备/模型；命令由 runner 执行并提供证据；
- 不记录密钥、Token、用户内容或完整敏感 payload。

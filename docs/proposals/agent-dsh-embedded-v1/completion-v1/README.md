# completion-v1 — C00 证据基线目录

本目录是 **DSH 补齐 V1（C00–C15）** 的证据基线，由 C00 只读建立；生产代码、旧方案文档和 `CAPABILITY_MANIFEST.json` 均不在写集内。

## 文件职责

| 文件 | 作用 | 状态 |
| --- | --- | --- |
| [`BASELINE.md`](BASELINE.md) | 当前 branch/HEAD、工作树计数、子模块 revision 与内部变更数、payload/APK/设备/JDK/SDK/targetSdk 的实测事实、C00 执行边界与未运行清单 | C00 已建立 |
| [`PATCH_LEDGER.json`](PATCH_LEDGER.json) | 已知 payload 语义弱化、平台补丁、依赖替换账本（9 条）；状态只用 `NOT_RUN/DIAGNOSED/BLOCKED` | C00 已建立，运行时探针均未跑 |
| [`PARITY_MATRIX.json`](PARITY_MATRIX.json) | C14 行级功能对标矩阵的**种子**：`schemaVersion` / `generatedAt` / `sourceRevisions` / `rows: []` | 种子，等待 C14 填行 |
| [`EVIDENCE_FORMAT.md`](EVIDENCE_FORMAT.md) | 未来运行目录、`run.json` 最小字段、`PASS/FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLE` 约定，以及“旧 PASS 不得当本次”的规则 | C00 已建立 |
| `README.md` | 本说明：row identity、填行规则、快照复现方式 | C00 已建立 |

## PARITY_MATRIX 的 row identity（C14 必读）

1. 行身份唯一来源是 [`../CAPABILITY_MANIFEST.json`](../CAPABILITY_MANIFEST.json)：
   - `schema = mca.dsh.capability.v1`，`schemaVersion = 1`；
   - `rows` 共 **422** 行，行 id 字段为 `id`（例：`host.tool-plugin-manager`、`host.session`）；
   - `generatedAt = 2026-10-19T19:38:31+08:00`，由 R00 只读静态扫描生成。
2. C14 新增每一行时必须带 `capabilityManifestId`，且必须能在 manifest 的 `rows[].id` 中**精确命中**；不得凭阅读印象自造 row id，不得把 manifest 的 422 行整段复制成矩阵行。
3. C00 不预填任何 row（`rows: []`）。这是有意为之：矩阵行要由 C14 在真实证据/探针完成后逐行派生，而不是在基线阶段先写结论。
4. **禁止复制、改写或覆盖** `CAPABILITY_MANIFEST.json`。C14 只读引用；若确需修订 manifest，另开任务并保留原始证据。

### 关于上一版未提交草稿的 10 条 provisional row

本目录在 2026-10-20 12:45 前后存在一版未跟踪草稿，其中 `PARITY_MATRIX.json` 预填了 10 条 provisional row。C00 重写时**没有沿用**，原因：

- 其 evidence 行号与 payload 实测不一致。例：PTY 行写 `dsh-subprocess-local/lib/index.js` 884–935 / 1035–1055 / 1155–1180，本次逐行读取实测为 **943–1176**（`openAndroidPty` 943–973、`resize` 空实现 1046、`spawnWithPipes` 1050–1161、`spawnPtyCompat` 1163–1176）。
- 部分 row 的 `status`（如 `FAIL`）是“生产路径缺失”的推断，却没有绑定 `CAPABILITY_MANIFEST` 的 row identity，也不符合“C00 只建种子、C14 填行”的分工。

因此按任务卡收缩为 `rows: []`；原有结论如仍有价值，由 C14 用可复核证据重新派生。此说明仅记录处置原因，不作为功能结论。

## 快照复现

`BASELINE.md` 的数字是时点快照；其他 C 任务仍在写工作树。复现时必须重新采集并记录时刻：

```powershell
cd E:\MAC
git rev-parse --abbrev-ref HEAD
git rev-parse HEAD
git log -1 --format='%H%n%ci%n%s'
git status --porcelain=v1 | Measure-Object -Line
git status --porcelain=v1 -uall
git diff --shortstat
git submodule status
foreach ($sm in 'third_party/llama.cpp','third_party/stable-diffusion.cpp','third_party/tokenizers-cpp') {
  git -C (Join-Path (Get-Location) $sm) rev-parse HEAD
  (git -C (Join-Path (Get-Location) $sm) status --porcelain=v1 | Measure-Object -Line).Lines
}
Get-FileHash -Algorithm SHA256 .\core\dsh-runtime\src\main\assets\dsh\payload.zip
Get-ChildItem .\core\dsh-runtime\src\main\jniLibs\arm64-v8a -File | Get-FileHash -Algorithm SHA256
Get-FileHash -Algorithm SHA256 .\app\build\outputs\apk\release\app-release.apk
(Get-Content .\scripts\dsh\payload-lock.json -Raw -Encoding UTF8 | ConvertFrom-Json) | Select-Object apkSha256,payloadSha256,kernelVersion,kernelRevision
$env:JAVA_HOME
& "$env:JAVA_HOME\bin\java.exe" -version
D:\model\android-sdk\platform-tools\adb.exe devices -l
```

**不要**在本目录运行 Gradle、安装 APK 或执行 `adb shell`；构建锁由 Lead 持有，设备操作需要单独授权。

## 状态词与边界

- 运行证据统一用 `PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`（定义见 [`EVIDENCE_FORMAT.md`](EVIDENCE_FORMAT.md)）。
- `PATCH_LEDGER.json` 里的 `DIAGNOSED` **不是**运行通过，只表示“源码/归档已读通并定位行为与调用点”；它不能升级为 PASS。
- 旧候选 `b610e1d0…` 的 PASS 只属于 05:12 那个 APK；当前 `app-release.apk`（`70746C4C…`，12:15:48）未验证，不得继承。
- C00 的只读边界、并发写入快照（C02c seeder 14:23:43）和全部未运行项见 [`BASELINE.md`](BASELINE.md) §6–§8。

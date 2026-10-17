# MCA RC 生产 Soak / 故障矩阵 Runner

本目录新增两个执行文件：

- `tools/device/rc-campaign.ps1`：PowerShell 7 主机包装器，负责 APK 绑定、参数校验、计划生成、设备证据拉取和严格 JSON 报告生成。
- `tools/device/rc-campaign.sh`：Android 设备端执行器，负责真实 Local API 请求、故障注入、连续采样和原始 NDJSON 落盘。

本 runner **不安装 APK、不构建 APK、不修改生产代码**。它要求目标 APK 已经安装，并且设备上已安装包的 `base.apk` 哈希、字节数与 `-ApkPath` 完全一致。

---

## 1. 正式 RC 判定

正式 final gate 证据必须同时满足：

- `-Hours >= 24`
- `-Requests >= 1000`
- `-Warmup >= 1`，正式建议 `20`
- 不是 `-FaultOnly`
- 不是 `-SoakOnly`
- 不是 `-SkipFaults`
- 整个 campaign 使用同一最终 RC APK，期间不换包
- `soak-report.json`、`fault-report.json`、`device-report.json` 同时生成并可由 final gate 校验

任何短时 pilot、少于 1000 次请求、只跑 soak、只跑 fault 或跳过故障矩阵的运行都会明确写成：

```json
{
  "campaign_kind": "PILOT",
  "pilot_included_in_formal": false,
  "formal_gate_eligible": false
}
```

**Pilot 不得计入正式 24h 证据。** 正式 campaign 必须重新从零开始运行，并且不得在期间安装其他 APK。

---

## 2. 前置条件

1. Windows PowerShell 7（`pwsh`）。
2. Android SDK `adb` 可用；可用 `-Adb` 显式指定。
3. 物理 ARM64 设备在线，`adb devices -l` 中状态为 `device`。
4. 最终 RC APK 已安装，且必须是单 APK 安装；`pm path` 同时返回 base/split 多 APK 时会 fail-closed。
5. 生产 `MainActivity` 能正常启动，Local API `/health` 返回 `{"status":"ok"}`。
6. `/v1/models` 中目标聊天模型存在 `"loaded":true`。
7. Local API 已启用并配置 API key。
8. 本地聊天模型和本地图像模型均已存在并且可以执行。
9. 屏幕已解锁；设备语言和当前 UI 需保留生产界面中的中文控件：
   - “选择模型”
   - “本地推理”
   - “加载”
   - “卸载”
10. 关闭会遮挡生产 UI 的应用更新弹窗，或确保更新弹窗不会在 24h 内出现。
11. 不要手动操作设备、切包、清理应用数据或重启 adb。

UI 自动化只使用每次重新抓取的 `uiautomator` bounds，不使用硬编码坐标；找不到真实控件时该场景 fail-closed，不会伪造成功。

---

## 3. 参数

```powershell
pwsh -NoProfile -File tools/device/rc-campaign.ps1 `
  -Serial f9d98aa3 `
  -ApkPath E:\MAC\app\build\outputs\apk\release\app-release.apk `
  -OutDir E:\MCA_RC_EVIDENCE\20261017-final `
  -Hours 24 `
  -Requests 1000 `
  -Warmup 20
```

必需参数：

| 参数 | 含义 |
| --- | --- |
| `-Serial` | 目标设备序列号 |
| `-ApkPath` | 最终 RC APK 路径；必须与设备 `base.apk` 哈希和字节数一致 |
| `-OutDir` | 输出目录；正式运行要求为空目录 |

主参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `-Hours` | `24` | 正式观测窗口，加载 warmup 后开始计时 |
| `-Requests` | `1000` | 正式逻辑请求总数，不含 warmup 和 recovery control |
| `-Warmup` | `20` | 正式窗口前的预热请求，明确不计入 1000 |
| `-SoakOnly` | off | 只执行 soak；fault report 标记 `SKIPPED`，不能过 final gate |
| `-FaultOnly` | off | 只执行约 40 个诊断用故障请求；soak report 标记 `NOT_RUN`/`FAILED`，不能过 final gate |
| `-SkipFaults` | off | 跳过故障矩阵与故障注入；不能过 final gate |
| `-DryRun` | off | 不接触设备，打印展开参数、计划计数、设备命令模板和 shell 语法结果 |
| `-SelfTest` | off | 无设备自测：1000 请求计划、唯一 ID、分段展开、设备脚本语法、JSON、LF/no-BOM |
| `-Adb` | 自动发现 | `adb.exe` 路径 |
| `-Package` | `com.muyuchat.mca` | 生产包名 |
| `-ChatModel` | `Huihui-Qwen3.5-2B-abliterated.Q4_K_M` | 审核时的聊天模型 ID/别名 |
| `-ImageModel` | `image:0fc35bfa-9cf3-4b18-955f-e05e8aee49b5` | 审核时的图像模型 ID |
| `-Port` | `11435` | Local API 端口 |
| `-SampleIntervalSeconds` | `30` | 设备端连续采样间隔 |
| `-ApiKeyEnvVar` | `MCA_API_KEY` | Release key 的环境变量名 |
| `-ModelFaultReport` | `tools/fault-injection/fault_matrix_report.json` | 只作为 `MODEL_TEST` 合同证据引用 |

A/B 构建的 key 处理：

- Debug 构建：设备端通过 `run-as com.muyuchat.mca` 读取私有 `mca_api.xml`；key 不打印、不落盘。
- Release 构建：

```powershell
$env:MCA_API_KEY = '<release-local-api-key>'
pwsh -NoProfile -File tools/device/rc-campaign.ps1 ...
Remove-Item Env:MCA_API_KEY
```

Release key 的传输链：

```text
调用者环境变量
  -> PowerShell 内存
  -> adb stdin
  -> 设备端 shell read
  -> curl -K - 的 stdin 配置
```

key 不会出现在：

- `adb shell` 命令行参数
- `curl` 命令行参数
- 持久日志
- JSON report
- 设备端临时文件

原始输出仍会再次按 key 和 `Bearer ...` 模式脱敏；拉取证据后还会扫描文本证据，若发现完整 release key 则 campaign fail-closed。`rc-campaign.sh` 当前只接受 bearer-token 安全字符集 `[A-Za-z0-9._~+/=:-]`；其他字符会 fail-closed，避免 curl config 注入。

---

## 4. 启动前 fail-closed 校验

正式窗口前，runner 必须全部通过：

1. `adb devices -l` 中存在指定 `-Serial`，状态为 `device`。
2. `pm list packages --user 0 com.muyuchat.mca` 精确包含生产包。
3. `pm path` 精确得到一个 `base.apk`。
4. 设备端 `sha256sum base.apk` 和 `wc -c base.apk` 与主机 APK 一致。
5. `/health` 返回 `status=ok`。
6. `/v1/models` 返回严格 JSON，目标聊天模型包含 `"loaded":true`。
7. `MainActivity` 为 resumed activity，`uiautomator` 能获取非空 UI 树。
8. 设备为物理 ARM64；模拟器、QEMU 或非 ARM64 直接失败。
9. Debug/Release key 通道可用。
10. warmup 的每一个请求都成功；warmup 不计入正式逻辑请求数。

周期性和结束时都会重新校验 APK 绑定。任何换包、adb 断连或哈希变化都会使 campaign 失败。

---

## 5. 1000 次逻辑请求

默认 `-Requests 1000` 时为确定性计划，不是运行后凑数：

| 类型 | 默认数量 | 说明 |
| --- | ---: | --- |
| chat | 950 | 普通、stream、non-stream、cancel、retry、disconnect、main kill、background/foreground |
| image | 50 | 真实 `/v1/images/generations`，同时形成 image runtime load/unload 证据 |
| stream | 634 | 计划自测值随请求数变化，但正式 1000 下固定 |
| non-stream | 366 | 与 stream 相加严格等于 1000 |
| cancel | 10 | 使用 `X-MCA-Request-Id` + `/v1/generate/stop` |
| retry | 12 | 每个 cancel/disconnect 都有后续正值恢复请求 |
| disconnect | 2 | 客户端强制断连 |
| worker death | 5 | `N = max(3, ceil(Requests/200))`，1000 时 N=5 |
| main process kill | 2 | `am force-stop` + 冷启动 + UI reload + authenticated recovery |
| background/foreground | 5 | in-flight stream 下 HOME/恢复 MainActivity |
| image load/unload | 50 | 每次 image 请求都计数；另有一次显式生产 UI unload/load |

计划特征：

- 每个逻辑请求都有唯一 `request_id`，格式为 `rc-<UTC>-<apk8>-000001`。
- 请求 ID 不重复；final report 的 `request_ids` 与 `attempted` 严格一致。
- warmup 使用独立 ID，并写 `counts_toward_workload=false`。
- worker-death 和 main-kill 的 positive control 也写 `counts_toward_workload=false`。
- 所有计划请求按 24h 窗口的约 90% 均匀铺开；剩余时间继续采样。
- 如果 24h 结束时未完成全部计划请求，报告中的 `attempted` 会小于 1000，并 fail-closed。

---

## 6. 设备脚本覆盖表

| 生产场景 | 触发方式 | 必须观察到的正证据 | 失败方式 |
| --- | --- | --- | --- |
| stream chat | 真实 `/v1/chat/completions` SSE | HTTP 2xx、SSE `data:`、终止 `[DONE]` | 计入未解释失败 |
| non-stream chat | 真实 chat completion | HTTP 2xx、非空 `choices` | 计入未解释失败 |
| image generation | 真实 `/v1/images/generations` | HTTP 2xx、>1000 bytes、`data` 字段 | 计入未解释失败 |
| cancel | unique request ID + `/v1/generate/stop` | stop HTTP 2xx、`stopped=true`、请求未正常完成 | scenario FAILED |
| retry | 新 request ID 重试 | 后续请求成功 | 恢复缺失即失败 |
| client disconnect | 本地 curl 进程 `kill -9` | 断连前请求确实在飞；后续 retry 成功 | scenario FAILED |
| worker death | Debug `run-as kill -9`；Release 先停 `LocalImageWorkerService` | worker PID 在飞行中消失；请求未完整 200；同模态 image recovery 200 | kill 未生效即 FAILED，不记 PASS |
| main process kill | `am force-stop` + `am start` | main 进程消失、请求被切断、`/health` 冷启动 200、UI reload、authenticated control 成功 | 任一缺失即 FAILED |
| load/unload | 生产 UI 卸载/加载 + `/v1/models` | 先观察到 loaded 消失，再观察到目标模型 `loaded=true` | UI 控件缺失或 API 状态不符即 FAILED |
| background/foreground | `KEYCODE_HOME` + `am start` | 背景时 MainActivity 不再 resumed；恢复后 resumed；stream 正常完成 | 任一未观察到即 FAILED |
| auth rejection | 无 token / 错 token / 真 token | 401/403、401/403、200 | 任一不符即 FAILED |
| APK binding | 开始、每约 15 分钟、结束 | 设备 base.apk sha/bytes 不变 | 立即失败 |

Worker death 在 Release 上如果系统既不允许 `run-as`，也不允许 shell 停止该 service，runner 会记录 `INVALID/FAILED`。它不会把“请求正常完成”冒充为注入成功。

---

## 7. 连续 24h 观测

设备端 monitor 独立于 `MainActivity` 和应用进程，因此在 main kill、worker death、Force Stop、后台/前台切换期间仍继续运行。

每轮样本写入：

```text
<OutDir>/raw/logs/samples.ndjson
```

每个有效样本包含：

- RFC3339 UTC `timestamp`
- `elapsed_seconds`
- `MemAvailable` 字节数
- 最高 thermal zone 温度
- main / `:local_chat` / `:local_image` 的：
  - PID
  - PSS
  - RSS
  - native heap
  - FD count
  - thread count
- `process_presence`
- sample quality

如果进程存在但任一指标不可读，设备端写 `sample_invalid`，Host 计入错误并 fail-closed；不会把不可读 FD 伪造成 `0` 后当成有效样本。

同时连续采集：

- `logcat -b crash`
- `logcat -b events` 的 `am_anr` / `am_crash`
- `logcat -b main` 的 `AndroidRuntime:E` / `ActivityManager:E`

最终 `device-report.json` 中 `crash_count` 和 `anr_count` 必须都为 0。

---

## 8. 原始证据与 SHA256

所有原始证据在设备端持续写入，结束后使用 `adb pull` 拉到：

```text
<OutDir>/raw/
```

报告中每一个 evidence descriptor 都包含：

```json
{
  "path": "raw/logs/samples.ndjson",
  "sha256": "<64 hex>",
  "bytes": 123456
}
```

路径严格相对 report 文件所在目录；不使用绝对路径、URL 或 `..`。

至少包含：

- `raw/logs/samples.ndjson`
- `raw/logs/requests.ndjson`
- `raw/logs/crash-buffer.log`
- `raw/logs/anr-events.log`
- `raw/logs/runtime-errors.log`
- `raw/logs/auth-check.ndjson`
- `raw/logs/device-stderr.log`
- segment console logs
- monitor console logs

所有样本和逻辑请求均是严格 NDJSON，每行独立 JSON；Host 逐行用 `System.Text.Json` 解析，任何坏行都会 fail-closed。

---

## 9. 三个报告

输出固定为：

```text
<OutDir>/soak-report.json
<OutDir>/fault-report.json
<OutDir>/device-report.json
```

Schema 与 `tools/final-gate/verify_runtime_evidence.py` 对齐。

### 9.1 `soak-report.json`

关键字段：

- `schema = "mca-runtime-evidence/1.0"`
- `report_type = "soak"`
- `campaign_kind`
- `pilot_included_in_formal = false`
- `formal_gate_eligible`
- `apk_sha256`
- `apk_size_bytes`
- `serial`
- `started_at`, `ended_at`
- `duration_hours`
- `warmup.completed`, `warmup.requests`
- `requests.attempted`, `success`, `failure`, `cancel`, `request_ids`, `unexplained_failures`
- `logical_counts` 七项：
  - `stream`
  - `nonstream`
  - `load_unload`
  - `cancel`
  - `retry`
  - `worker_death`
  - `restart`
- `coverage` 七项布尔值
- `memory_samples`
- `raw_logs`
- `apk_binding_checks`
- `errors`, `warnings`

约束：

- `attempted == success + failure + cancel`
- `logical_counts.stream + logical_counts.nonstream == attempted`
- `logical_counts.cancel >= requests.cancel`
- `request_ids` 唯一，数量等于 `attempted`
- `coverage` 七项为 true 且对应计数为正

### 9.2 `fault-report.json`

关键字段：

- `schema = "mca-runtime-evidence/1.0"`
- `report_type = "fault"`
- `apk_sha256`
- `dimensions` 恰好三个键：
  - `MODEL_TEST`
  - `PRODUCTION_INJECTION`
  - `DEVICE_EXECUTION`
- `mandatory_scenarios[]`
- `model_test_suite`
- `raw_logs`
- `errors`, `warnings`

每个 mandatory scenario 包含：

- 唯一 `id`
- `status = "VERIFIED"` 或 `"FAILED"`
- `dimensions = ["MODEL_TEST","PRODUCTION_INJECTION","DEVICE_EXECUTION"]`
- `device.serial`
- `device.physical = true`
- `device.abi = "arm64-v8a"`
- 独立的 scenario evidence JSON descriptor

scenario evidence JSON 中分别保存：

- `model_test`：只引用合同模型，明确 `CONTRACT_MODEL_ONLY`
- `production_injection`：真实生产路径注入及其观察值
- `device_execution`：物理设备、ABI、实际执行
- `request_evidence`
- `errors`

`MODEL_TEST` 来自现有 `tools/fault-injection/fault_matrix_report.json` 的 VERIFIED contract IDs，runner 会复制并哈希该报告。它只用于合同期望，**绝不代替** `PRODUCTION_INJECTION` 或 `DEVICE_EXECUTION`。

### 9.3 `device-report.json`

关键字段：

- `schema = "mca-runtime-evidence/1.0"`
- `report_type = "device"`
- `apk_sha256`
- `installed_sha256`
- `serial`
- `device.physical`
- `device.abi`
- `device.manufacturer`
- `device.model`
- `main_activity_ui.status = "PASSED"`
- `main_activity_ui.activity = "<package>/.MainActivity"`
- `main_activity_ui.evidence`
- `authenticated_local_api.status = "PASSED"`
- `authenticated_local_api.authenticated = true`
- `authenticated_local_api.http_status` 2xx
- `authenticated_local_api.request_id`
- `authenticated_local_api.evidence`
- `crash_count = 0`
- `anr_count = 0`

---

## 10. DryRun / SelfTest

### DryRun

```powershell
pwsh -NoProfile -File tools/device/rc-campaign.ps1 `
  -DryRun `
  -Serial TEST123 `
  -ApkPath .\app\build\outputs\apk\release\app-release.apk `
  -OutDir .\artifacts\rc-dryrun
```

DryRun 不创建 OutDir、不调用 adb、不接触设备。它打印：

- 展开后的参数
- APK SHA256
- 计划计数
- segment 数量
- shell 语法结果
- key 传输策略

### SelfTest

```powershell
pwsh -NoProfile -File tools/device/rc-campaign.ps1 -SelfTest
```

SelfTest 不需要设备，验证：

- PowerShell 7
- 1000 请求计划
- unique request IDs
- stream/non-stream
- cancel/retry
- worker death 次数
- restart 次数
- background/foreground 次数
- image load/unload
- warmup 不计入正式计数
- 分段不丢失/不重复请求
- `rc-campaign.sh` 的 `sh -n` 语法
- 严格 JSON round-trip
- 三个文本文件为 UTF-8 no-BOM + LF

SelfTest 不运行 Gradle、不生成 `__pycache__`、不接触设备。

---

## 11. Final gate 校验

```powershell
python tools/final-gate/verify_runtime_evidence.py `
  --apk E:\MAC\app\build\outputs\apk\release\app-release.apk `
  --soak-report E:\MCA_RC_EVIDENCE\20261017-final\soak-report.json `
  --fault-report E:\MCA_RC_EVIDENCE\20261017-final\fault-report.json `
  --device-report E:\MCA_RC_EVIDENCE\20261017-final\device-report.json `
  --min-soak-hours 24 `
  --min-requests 1000 `
  --out E:\MCA_RC_EVIDENCE\20261017-final\gate-verification.json
```

Exit code：

- `0`：全部 PASS
- `1`：至少一个 FAIL
- `2`：无 FAIL，但存在 UNVERIFIED

---

## 12. 需要人工/UI 配合的步骤

自动 runner 会尝试完成以下步骤，但它们依赖真实生产 UI；失败不会被掩盖：

1. 确保最终 RC 已安装，且安装包与 `-ApkPath` 完全一致。
2. 人工解锁设备，保持屏幕可用。
3. 首次运行前确认聊天模型已加载。
4. Release 构建在启动前设置 `MCA_API_KEY`。
5. 若生产 UI 文案、布局或语言变化导致“选择模型/本地推理/加载/卸载”定位失败，需要人工修复 UI 或调整 runner 的 locator 后重跑；不能把该场景标记为 PASS。
6. 若系统不允许 shell 停止 `LocalImageWorkerService` 且 release 不允许 `run-as kill`，worker-death 场景应保持 FAILED，需要构建/部署侧提供等价的可审计故障注入通道后重跑。
7. 正式 24h 运行期间不要换包、不要清数据、不要重启 adb、不要手动强停应用。
8. 运行结束后由人工复核三个 report 与 final gate 输出，确认报告不是 pilot、不是 partial、不是 model-only。

---

## 13. 明确限制

- 本 runner 不安装 APK。
- 本 runner 不构建 APK。
- 本 runner 不运行 Gradle。
- 本 runner 不修改生产 Kotlin/C++。
- 本 runner 不 stage/commit/push 任何文件。
- 本 runner 不把 model contract 结果冒充 production injection 或 device execution。
- 本 README 和脚本不声称已经执行过正式 campaign；正式证据只能在真实设备上完整运行 24h/1000 后产生。
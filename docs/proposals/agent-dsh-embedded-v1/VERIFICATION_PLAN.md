# R07 一次性最终验证计划与证据模板

状态：R00 交付文档，供 R07 独立验证者执行。日期：2026-10-19。
本文不表示任何用例已运行；R00 的结果全部是 `NOT_RUN`。
关联：[ADR 0003](../../adr/0003-dsh-embedded-dual-agent-modes.md)、[BASELINE](BASELINE.md)、[CAPABILITY_MANIFEST.json](CAPABILITY_MANIFEST.json)、[证据目录说明](evidence/README.md)、[执行包](EXECUTION_PACK.md#r07固定版本功能与插件对照验收)。

## 1. 目的与原则

- 目的：用**一次连续、受控的 R07 pass**，对固定候选做“官方 DSH 控制组 vs Android 集成版”的最终对照验收，输出逐行 `PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`、原始证据和产物身份。
- 一次 pass 的定义：一个冻结输入（源码 + 工作区补丁 + 子模块内部 diff + 官方 SHA + payload + APK + 设备状态）从上到下的完整执行；**中途不改代码、不重构建、不换 APK、不换设备**。若必须改代码，则旧 pass 作废，以新 `runId` 从 V00 重跑。
- 验证者独立性：R07 owner 是独立验证者，不得是正在修改被测代码的人；不得通过改测试、减断言、扩大 suppress、改 golden 或空执行来消除失败（硬锁 12.4）。
- 对照原则：文本输出不要求逐字相同；比较调用关联、工具效果、事件次序、数据恢复与终态。能用 mock 确定的协议部分做确定性对照；OS/native/模型/网络能力必须真实执行。
- 单次构建锁：整个 pass 只允许一个构建串行执行；不得与其他 Agent 并发构建同一构建目录。

## 2. 角色、前置条件与冻结包

### 2.1 角色

| 角色 | 责任 |
| --- | --- |
| R07 verifier | 执行本计划、写原始证据、判定结果、写报告；不改生产代码 |
| Integrator | 提供冻结候选与构建锁；确认 V00 输入；处理验证暴露的缺陷（在 pass 之外修复） |
| Runtime owner | 提供 DSH 宿主启动/停止/日志、gateway 与 payload 的可观察入口 |
| Device operator | 真机操作、截图/录屏、安装/升级与数据快照 |
| Lead | 审阅报告、决定是否发布；不把 R00/R01 静态结论升级为设备 PASS |

### 2.2 冻结包（V00 必须产出）

- MCA：`HEAD`、branch、tracked diff SHA-256、untracked 文件清单与总 hash、三个子模块 revision + 内部 diff SHA-256。
- 官方 DSH：commit `687f282e8625c6b8d11ff58211a90524be2e4317`、包版本、`pnpm-lock.yaml` SHA-256、`.mca-review-sha`。
- payload：APK 下载 URL、最终大小、SHA-256、包名/versionCode/versionName、解包文件 manifest（路径/大小/SHA-256）、许可证文本、所有替换/补丁。
- Android 产物：APK 路径、SHA-256、签名证书 SHA-256、applicationId、versionCode/versionName、minSdk/targetSdk、ABI 列表、依赖 closed set。
- 设备：厂商/型号、Android 版本/API、build fingerprint、ABI、内存/存储、屏幕、当前时间与时区、是否 root/Shizuku 等与结论相关的状态。
- 工具链：JDK、Gradle、AGP、Kotlin、SDK platforms/build-tools、NDK。
- 一个 `run.json`（见证据规范）保存以上全部身份；后续所有证据必须引用同一 `runId`。

**V00 未产出或身份不一致时，pass 直接 `BLOCKED`，不开始 V01。**

### 2.3 环境前置

- 设备已授权 adb、屏幕可交互、网络可用（云模型用例需要）；本地模型资产已就绪（本地用例需要），且模型来源/校验和已记录。
- 已有“上一版正式签名 APK”和真实历史数据快照，用于 V05 升级与回退。
- 官方桌面控制环境（Windows/macOS/Linux）已按固定 SHA 准备；若无法准备，控制组相关行记录 `NOT_RUN`，且不得声称“与官方一致”。
- 已确认 MCA 三个 DSH 模块（`core:dsh-runtime`、`api:dsh-gateway`、`feature:dsh-workspace`）与 `app/.../dsh/` 已落地；`./gradlew` 配置成功。
- 已确认 DSH 数据目录与 MCA 数据目录分离，且备份/设备转移规则已按真实敏感内容评审。

## 3. 结果定义与判定规则

| 结果 | 判定条件 |
| --- | --- |
| `PASS` | 在**本次 runId** 下真实执行，所有 PASS 判据满足，原始证据可复现，产物/设备身份绑定完整 |
| `FAIL` | 真实执行后观察到不符合预期行为；证据保留；不得用“已知问题”改成 PASS |
| `NOT_RUN` | 未执行（无设备、无模型、无网络、无控制组、时间不足等）；必须写清原因与缺失输入 |
| `BLOCKED` | 已尝试执行但被外部条件阻断（如 payload 无法启动、签名不兼容、权限无法获得）；必须附阻断证据与下一步 |
| `NOT_APPLICABLE` | 该用例在本次目标环境/范围确实不适用（如 Windows-only 行在 Android），必须在记录中写明理由；**不得把必需项失败改判成 N/A 逃避验收** |

判定硬规则：

1. 只完成 Node/WebView/文本对话，不能把整个 R07 判 `PASS`；只完成部分能力只能记录实验进度。
2. `-SkipTests`、空测试集合、0 个匹配测试、旧 XML、mock/stub 结果都不能算行为 PASS。
3. 真实 PTY 与原生 sharp 不得用普通 pipe 或纯 JS 替换冒充；参考 payload 的替换只能记录为 `NOT_RUN`/`FAIL`（对“真 PTY/原生 sharp”命题）。
4. 取消必须分四段记录：UI 反馈、取消送达、真实执行停止、lease/资源释放；缺任一段不能判“取消 PASS”。
5. 单 APK 同 UID 方案不得写成沙箱/隔离通过；“全离线”只有在覆盖整个 DSH/plugin 进程网络出口的机制被验证后才可写。
6. 任何结果必须绑定源码/产物/设备身份；历史证据要注明原 revision 与适用理由，不能改日期冒充本次运行。
7. `PASS` 不能由“用了官方代码”“静态存在”“编译成功”“UI 相似”推导。

## 4. 证据命名与目录

遵循 [evidence/README.md](evidence/README.md)。每个用例至少一份原始证据，命名：

```text
docs/proposals/agent-dsh-embedded-v1/evidence/<runId>/<caseId>__<slug>__<n>.<ext>
```

示例：

```text
evidence/R07-20261025-98eb8e4/V04__apk-identity__1.json
evidence/R07-20261025-98eb8e4/V04__apk-signature__1.txt
evidence/R07-20261025-98eb8e4/V07__nav-image-agent-dsh__1.png
evidence/R07-20261025-98eb8e4/V10__tool-loop-local__1.log
evidence/R07-20261025-98eb8e4/V12__cancel-four-stage__1.md
```

- 日志用原始 stdout/stderr 文件；截图/录屏保留原文件，不裁剪、不美图；关键 UI 状态可加 `.md` 说明但不得替代原始证据。
- 每个证据文件在 `run.json` 的 `cases[]` 中登记：`caseId`、`result`、`evidence[]`、`startedAt`、`finishedAt`、`executor`、`notes`。
- 凭据/launch token/模型密钥/用户隐私内容必须脱敏；脱敏不得改变结论相关字段，且要保留脱敏说明。

## 5. 验证矩阵

> 时间预算为计划值，不是实测值；实际耗时应写入报告。每项都必须记录 `referenceResult`（官方控制组）与 `phoneResult`（Android 集成版），二者分开判定。

### V00 冻结与身份

| 项 | 内容 |
| --- | --- |
| 目标 | 固定本次 pass 的全部输入身份 |
| 步骤 | 生成并复核 2.2 冻结包；写 `run.json`；记录设备/工具链；确认构建锁 |
| PASS | 所有身份字段齐全、相互引用一致、`runId` 唯一；后续证据都引用该 `runId` |
| FAIL | 任一身份缺失/冲突仍继续执行 |
| 证据 | `V00__freeze-packet__1.json`、`V00__run-manifest__1.json` |
| 预算 | 20 min |

### V01 静态门禁与依赖方向

| 项 | 内容 |
| --- | --- |
| 步骤 | 运行 `python tools/architecture/check_architecture_rules.py`；检查新增 DSH 模块依赖方向、无 feature→app/其他 feature 内部依赖、无新 `Any?` 注入、无第二模型 owner、无 MainUiState/MainCoordinatorHost 扩张；核对 `CAPABILITY_MANIFEST.json` 与真实装配树一致 |
| PASS | 脚本退出码 0 且实际执行范围覆盖本变更；manifest 行与装配树一致；无新增违规 |
| FAIL | 脚本失败、空执行、或静态规则被绕过 |
| 证据 | `V01__arch-rules__1.log`、`V01__dependency-check__1.md` |
| 预算 | 20 min |

### V02 候选构建

| 项 | 内容 |
| --- | --- |
| 步骤 | 使用受控入口（`.\scripts\build.ps1` 或整合者确认的等价命令）构建候选；记录命令、JDK/SDK、开始/结束时间、退出码、产物路径；确认没有并发构建 |
| PASS | 构建成功、产物是本候选源码/补丁/submodule 的产物、APK 可被后续步骤读取 |
| FAIL | 构建失败、产物来源不明、使用旧产物冒充 |
| 证据 | `V02__build__1.log`、`V02__build-env__1.txt`、`V02__artifact-path__1.txt` |
| 预算 | 30–60 min |

### V03 单元/集成测试

| 项 | 内容 |
| --- | --- |
| 步骤 | 运行本次变更适用的测试集合（至少架构检查、受影响模块单测、DSH 宿主/gateway 契约测试、既有聊天/Local API 回归）；记录实际匹配/执行/失败/跳过数量；核对是否覆盖本次 DSH 改动 |
| PASS | 所有必需测试真实执行且通过；数量与过滤器匹配；无跳过掩盖必需项 |
| FAIL | 失败、0 个匹配、空报告、旧报告冒充 |
| 证据 | `V03__unit-tests__1.log`、`V03__test-summary__1.json` |
| 预算 | 30–45 min |

### V04 APK / payload 身份与签名

| 项 | 内容 |
| --- | --- |
| 步骤 | `apksigner verify --print-certs`、`aapt2 dump badging`/`apkanalyzer`；记录 applicationId、versionCode/Name、minSdk/targetSdk、ABI、签名方案与证书 SHA-256、debug 标志；核对 payload 文件 manifest 与哈希；确认没有把 Linux glibc/musl 二进制冒充 Android Bionic |
| PASS | 身份与 V00 一致；签名有效；targetSdk 36；payload 哈希逐文件匹配；必需 native 依赖 ABI 有记录 |
| FAIL | 签名不符、targetSdk 被降级、payload 缺文件/哈希不符、ABI 造假 |
| 证据 | `V04__apk-identity__1.json`、`V04__apk-signature__1.txt`、`V04__payload-manifest__1.json` |
| 预算 | 15 min |

### V05 安装、升级与兼容签名

| 项 | 内容 |
| --- | --- |
| 步骤 | 先安装上一版正式签名 APK 并准备真实历史数据（聊天/图片/模型/Kotlin Agent 任务）；同签名覆盖安装候选；核对 applicationId 不变、数据仍在、可继续使用；分别在干净安装和升级安装各跑一次 DSH 启动 |
| PASS | 升级成功且旧聊天/图片/模型/Agent 数据连续；两个安装形态都能启动；DSH 数据目录独立 |
| FAIL | 必须清数据/改 applicationId/降级才能安装；旧数据丢失或不可用 |
| 证据 | `V05__install-upgrade__1.log`、`V05__upgrade-data-check__1.md`、`V05__pre-upgrade-snapshot__1.json` |
| 预算 | 30 min |

### V06 宿主启动、ready 与认证

| 项 | 内容 |
| --- | --- |
| 步骤 | 冷启动 app → 打开 DSH → 观察 Runtime Owner 的 `STARTING/READY`；确认 ready 是 Loader 稳定 + required-startup audit + 认证可用，而不是端口打开；记录 Node 版本、`process.platform`、`process.execPath`、`$DSH_HOME`；确认只监听 loopback；WebView 接收一次 launch token 入口并转为清洁 URL；重复进页不启动第二个宿主 |
| PASS | READY 语义符合官方；认证流程成功；无第二个进程/代次；URL/token 不落日志 |
| FAIL | 端口打开即判 ready；token 泄漏；重复启动；监听非 loopback |
| 证据 | `V06__runtime-phases__1.log`、`V06__auth-handoff__1.md`、`V06__process-tree__1.txt` |
| 预算 | 20 min |

### V07 设备导航：图片 → Agent → DSH

| 项 | 内容 |
| --- | --- |
| 步骤 | 冷启动进入侧栏；确认顺序为图片 → Agent → DSH；分别打开三个入口；验证 `AGENT` 仍是调参、`AGENT_WORKSPACE` 仍是 Kotlin Agent、DSH 是新入口；来回切换并重启，验证各自返回栈/草稿/滚动/最后选择；在 Kotlin Agent 有在途任务时切到 DSH，确认不被取消，反向亦然 |
| PASS | 顺序与语义正确；旧 ordinal 未移动；两种状态恢复互不覆盖；切模式不取消任务 |
| FAIL | DSH 占用 AGENT/AGENT_WORKSPACE；顺序错；切换导致任务取消或状态丢失 |
| 证据 | `V07__nav-image-agent-dsh__1.png`、`V07__nav-return-restore__1.mp4`、`V07__cross-mode-task__1.md` |
| 预算 | 30 min |

### V08 官方页面渲染与交互

| 项 | 内容 |
| --- | --- |
| 步骤 | 加载官方认证同源页面；确认不是 `file://`、不是只拷贝 index.html；检查官方 boot 注入、`/plugins/.../client.js` 动态资源、主题/布局/侧栏/输入框/审批/tool tree/设置页；检查 WebView console 错误；旋转/窄屏/大字体/深色/键盘；断线重连；上传/下载/SAF/分享/剪贴板/外链/返回层级 |
| PASS | 官方页面真实渲染且关键交互可用；无阻断性 console 错误；Android 交互有真实结果 |
| FAIL | 白屏/资源 404/自绘替代/关键交互不可用 |
| 证据 | `V08__official-page__1.png`、`V08__console__1.log`、`V08__file-interactions__1.md`、`V08__mobile-layout__1.mp4` |
| 预算 | 40 min |

### V09 模型目录与网关

| 项 | 内容 |
| --- | --- |
| 步骤 | 核对官方模型选择器读取的目录与 MCA `/v1/models` 字段（id、上下文/输出限制、supportsTools、nativeToolCalling、toolProtocolLabel）；核对网关只监听 loopback、认证生效、DSH 专用凭据可撤销；确认 DSH 得不到 MCA 云原始密钥；切换模型走既有准入；记录目录 revision |
| PASS | 目录与真实模型一致；认证/撤销有效；无密钥泄漏；切换受准入控制 |
| FAIL | 目录造假/空目录、无认证、密钥下发、绕开准入 |
| 证据 | `V09__model-catalog__1.json`、`V09__gateway-auth__1.md`、`V09__credential-check__1.txt` |
| 预算 | 25 min |

### V10 真实本地模型工具闭环

| 项 | 内容 |
| --- | --- |
| 步骤 | 选择真实本地模型（记录模型文件/校验和/后端）；用固定 fixture 让模型调用一个真实 DSH 工具（如 workspace read）；确认工具由 DSH 执行、结果回传、最终回答；记录 tools/tool_choice/tool_calls/tool_call_id/终态；重复一次多工具/并行（若模型支持）；覆盖畸形 JSON/上下文溢出/后端错误 |
| PASS | 真实模型 → 真实工具 → 真实结果 → 正常终态；字段/关联保真；错误不伪装成 stop |
| FAIL | 只回文本、工具未执行、字段丢失、把 length/EOF 当 stop、mock 代替真实模型 |
| 证据 | `V10__tool-loop-local__1.log`、`V10__request-response-fixture__1.json`、`V10__native-stop-proof__1.md` |
| 预算 | 45 min |

### V11 真实云端模型工具闭环

| 项 | 内容 |
| --- | --- |
| 步骤 | 用既有云端适配能力走同一条工具闭环；若字段/终态不满足，记录精确缺口；不得新建第二套云账号/密钥；条件不具备时记录 `NOT_RUN/BLOCKED` |
| PASS | 真实云端模型完成工具往返且终态严格；旧聊天/云链路回归通过 |
| FAIL | 用本地结果冒充云端；密钥泄漏；终态被改写 |
| 证据 | `V11__tool-loop-cloud__1.log`、`V11__cloud-route__1.json` |
| 预算 | 30 min |

### V12 取消/停止/竞争（四段证据）

| 项 | 内容 |
| --- | --- |
| 步骤 | 对 DSH 在途请求分别触发：UI cancel、AbortSignal、HTTP 断连、Service stop、Node 退出；记录四段（UI 反馈、取消送达、真实执行停止、lease/资源释放）；同时让 Kotlin Agent/聊天/图片/DSH 竞争本地模型，确认 DSH 子代理排队不启动第二套 engine，旧聊天 global stop 不误停 DSH/Kotlin Agent；超时保持 stop fence |
| PASS | 每条链四段齐全；不同入口互不串扰；资源释放有真实停稳证据 |
| FAIL | 仅 Flow 结束/布尔返回；全局 stop 误伤；超时后复用未知资源 |
| 证据 | `V12__cancel-four-stage__1.md`、`V12__competition__1.log`、`V12__stop-fence__1.txt` |
| 预算 | 40 min |

### V13 持久化、崩溃恢复与未知副作用

| 项 | 内容 |
| --- | --- |
| 步骤 | 创建会话/多轮/压缩/fork；杀进程/断电式中断（在受控时机）；重启后按官方日志恢复事实；检查 torn tail/锁/SQLite/附件；确认未知副作用不复做；旧会话 fixture 可读；清空/导出不影响其他模式 |
| PASS | 恢复事实正确；无重复副作用；旧数据兼容；单一 writer |
| FAIL | 重建日志、双写、自动重做未知写操作、丢历史 |
| 证据 | `V13__crash-recovery__1.md`、`V13__session-log__1.txt`、`V13__fixture__1.json` |
| 预算 | 40 min |

### V14 后台、旋转与进程生命周期

| 项 | 内容 |
| --- | --- |
| 步骤 | 旋转、退后台、锁屏、长时间后台；targetSdk 36 前台服务/通知行为；通知 action 按模式与代次；进程被杀后恢复；DSH 停止不关闭共享模型 owner/不停止 Kotlin Agent；进入/退出模式不预加载/误杀 |
| PASS | 旋转/后台不重建 runtime；在途任务按合同继续或可恢复；停止动作只影响本模式 |
| FAIL | 旋转重建/丢任务；通知误停另一模式；后台策略造假 |
| 证据 | `V14__rotation__1.mp4`、`V14__background-notification__1.log`、`V14__kill-restore__1.md` |
| 预算 | 30 min |

### V15 双模式数据隔离与清理

| 项 | 内容 |
| --- | --- |
| 步骤 | 分别清空 DSH 历史/插件/配置，确认 Kotlin Agent 与聊天/图片/模型数据不变；反向清理 Kotlin Agent 数据，确认 DSH 不变；ID 命名空间检查；备份/设备转移规则检查；导出/导入不误改另一模式；两模式各有在途任务时的 DSH 重启/重置边界 |
| PASS | 两个模式数据独立、清理互不影响、ID 不冲突、备份规则符合评审 |
| FAIL | 交叉清理、共享表双写、ID 混用 |
| 证据 | `V15__data-isolation__1.md`、`V15__cleanup-matrix__1.json` |
| 预算 | 30 min |

### V16 安全边界

| 项 | 内容 |
| --- | --- |
| 步骤 | 检查：仅 loopback 监听（外网接口不可达）；launch token 不出现在 logcat/文件/历史/Intent；Host/Origin 校验；审批默认 ask、危险路径需审批；路径穿越/符号链接/绝对路径拒绝；无通用危险 JS bridge；外链受控；模型密钥不下发；插件同 UID 风险如实记录；离线声明与实际机制一致；通知/日志不泄密 |
| PASS | 上述边界的实际机制被验证；无夸大的隔离/离线声明 |
| FAIL | 非 loopback 暴露、token 泄漏、审批被关、路径逃逸、万能桥、虚假隔离声明 |
| 证据 | `V16__security-matrix__1.md`、`V16__token-audit__1.txt`、`V16__listener__1.txt` |
| 预算 | 30 min |

### V17 文件/搜索/编辑工具与附件

| 项 | 内容 |
| --- | --- |
| 步骤 | 逐项执行真实读写/搜索/编辑；附件上传、图片读取、产物展示；验证路径边界、错误、并发写；原生 sharp 行记录真实结果（参考 payload 的纯 JS 替换不算原生） |
| PASS | 每个必需工具有真实效果与事件；错误可定位 |
| FAIL | 只加载不执行、路径逃逸、附件丢失、假 sharp |
| 证据 | `V17__tools-file__1.log`、`V17__attachment__1.md` |
| 预算 | 30 min |

### V18 终端/PTY 与 PTC/workflow

| 项 | 内容 |
| --- | --- |
| 步骤 | 真实 PTY：启动 shell、输出回显、输入、resize、Ctrl-C、进程组清理；若只有普通 pipe，记录为 `NOT_RUN/FAIL`，不得写 PTY PASS。PTC/workflow：真实执行模型编写的程序、超时、隔离声明、输出与恢复；subagent：创建/消息/等待/取消/谱系 |
| PASS | 真实 PTY 语义与 PTC 真执行均有证据；无 PTY 则明确失败 |
| FAIL | pipe 冒充 PTY、resize 空操作、PTC 只加载不执行 |
| 证据 | `V18__pty__1.mp4`、`V18__ptc-workflow__1.log`、`V18__subagent__1.json` |
| 预算 | 40 min |

### V19 插件、Skills 与 MCP

| 项 | 内容 |
| --- | --- |
| 步骤 | 安装/启用/禁用/卸载一个纯 JS 插件；验证 host+client 资源与 reload；验证失败回滚；加载 workspace Skill 并调用；配置一个 stdio 和一个 Streamable HTTP MCP server（如可用），验证工具注册、调用、断连清理；记录插件可信合同 |
| PASS | 每个必需扩展真实可用；失败可回滚；MCP 传输真实工作 |
| FAIL | 只加载不执行、安装破坏运行树、MCP 假连接 |
| 证据 | `V19__plugin__1.log`、`V19__skill__1.md`、`V19__mcp__1.json` |
| 预算 | 35 min |

### V20 长任务/调度/后台作业

| 项 | 内容 |
| --- | --- |
| 步骤 | 启动后台 job 与 schedule；验证输出流、取消、进程死亡、休眠/杀进程/重启后的语义；确认进程内 timer 不被承诺为休眠后可靠 |
| PASS | 长任务按官方语义执行/恢复；不夸大调度可靠性 |
| FAIL | 后台任务丢失且无记录、定时器假承诺 |
| 证据 | `V20__jobs-schedule__1.log`、`V20__sleep-restart__1.md` |
| 预算 | 25 min |

### V21 官方控制组对照（桌面）

| 项 | 内容 |
| --- | --- |
| 步骤 | 在固定 SHA 的官方桌面环境用同一 fixture 执行 V09–V13、V17–V20 的协议/行为对照；记录 referenceResult；不可用则 `NOT_RUN` |
| PASS | 控制组与手机结果的差异被解释且不违反验收 |
| FAIL | 用手机结果冒充控制组；无控制组却声称等价 |
| 证据 | `V21__desktop-control__1.log`、`V21__comparison__1.json` |
| 预算 | 45–90 min（若环境可用） |

### V22 报告汇总

| 项 | 内容 |
| --- | --- |
| 步骤 | 生成逐行结果表：manifest 必需行 × 本次结果；统计 PASS/FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLE；列出未验证条件、阻断项、风险与回退建议；绑定所有产物/设备身份 |
| PASS | 每行有结果与证据；失败项不被隐藏；结论不超出证据 |
| FAIL | 汇总与原始证据矛盾、必需项缺失、把 NOT_RUN 写成通过 |
| 证据 | `V22__r07-report__1.md`、`V22__results__1.json` |
| 预算 | 30 min |

## 6. 一次性执行顺序与时间预算

严格按顺序执行；V00 不通过则停止。

| 阶段 | 用例 | 计划预算 | 说明 |
| --- | --- | --- | --- |
| A 冻结 | V00 | 20 min | 先冻结，后构建；冻结后不修改输入 |
| B 静态/构建/测试 | V01–V03 | 80–125 min | 单构建锁；构建失败不进入设备阶段 |
| C 产物身份 | V04 | 15 min | 签名/payload/ABI |
| D 安装升级 | V05 | 30 min | 先快照，后升级；失败可回退 |
| E 设备启动/UI | V06–V08 | 90 min | 认证、导航、官方页面 |
| F 模型 | V09–V11 | 100 min | 目录、本地、云端 |
| G 取消/竞争 | V12 | 40 min | 四段证据 |
| H 持久化/生命周期 | V13–V14 | 70 min | 崩溃、旋转、后台 |
| I 数据/安全 | V15–V16 | 60 min | 隔离与 token/loopback |
| J 工具/扩展/长任务 | V17–V20 | 130 min | 真实 PTY/PTC/插件/MCP |
| K 控制组 | V21 | 45–90 min | 可选但影响“等价”结论 |
| L 汇总 | V22 | 30 min | 逐行报告 |
| **合计** | | **约 11–13 h**（含缓冲；不含设备准备/模型下载） | 计划值，不是实测 |

- 时间不足时：只允许把未执行项记为 `NOT_RUN` 并写清缺失输入；不得用“抽样通过”替代必需项。
- 任何 FAIL 都要先保存证据再继续后续独立项；不得在 pass 中改代码/测试/配置来“顺手修复”。

## 7. 回退与恢复

| 阶段 | 失败/异常时的回退 |
| --- | --- |
| V00 | 停止；不构建、不安装；保留冻结包尝试记录 |
| V01–V03 | 停止；不改代码；把失败交还 integrator，在 pass 外修复后以新 runId 重跑 |
| V04 | 不安装；保留产物与身份证据；把签名/payload 问题交还整合者 |
| V05 | 卸载候选；重新安装上一版正式签名 APK；从快照恢复真实数据；不得清用户数据 |
| V06–V08 | 停止 DSH runtime；force-stop app；必要时重启设备；保留日志；不得删除 DSH 数据目录 |
| V09–V12 | 停止在途请求/宿主；确认 lease 停稳；不得用全局 stop 清理现场 |
| V13–V14 | 停止宿主；从数据快照恢复；保留原始日志与 fixture；不重建历史 |
| V15 | 立即停止清理操作；按快照恢复；分别核对两模式数据；不得互相“修复” |
| V16 | 关闭监听/停止 runtime；保留证据；安全失败未解决前不得发布 |
| V17–V20 | 停止 job/schedule/PTY；确认进程树清理；保留日志 |
| V21 | 不影响手机结论；控制组失败记录 `NOT_RUN/BLOCKED` |
| 全局 | 候选 APK 不作发布；回退到上一版正式 APK；数据已发生不可逆写入时，写明前向修复/快照恢复方案 |

## 8. 不得声称的内容

- 不得因“用了官方源码/官方前端”就写“Android 与原版完全一致”。
- 不得把单 APK 同 UID 写成沙箱/隔离通过，或把“本地模型”写成“全离线”。
- 不得把普通 pipe 写成 PTY，把纯 JS 替换写成原生 sharp，把插件加载写成插件执行通过。
- 不得把 V00–V04 的静态/构建/身份结果写成功能通过。
- 不得把历史通过证据（旧 revision/旧 APK/旧设备）改日期当成本次结果。
- 不得用测试、mock、stub、旧 XML、UI 相似或“代码存在”替代真实模型/设备/native 证据。

## 9. R07 报告骨架（写入 V22 证据）

```markdown
# R07 最终验证报告 — <runId>
- 日期/执行者/角色：
- 冻结输入：HEAD / branch / tracked diff sha256 / untracked hash / submodules
- 官方 DSH：SHA / version / lockfile sha256
- payload：APK URL / size / sha256 / package / versionCode / targetSdk
- 设备：model / Android / API / ABI / build fingerprint / memory
- 工具链：JDK / Gradle / AGP / Kotlin / SDK / NDK
## 结果汇总
| case | referenceResult | phoneResult | 证据 | 备注 |
## 逐行能力结论
| manifest row id | 结果 | 证据 | 未验证条件 |
## FAIL / BLOCKED / NOT_RUN 明细
## 未验证项与风险
## 回退/发布建议
## 签名（验证者/日期）
```

## 10. 修复轮增量计划 R07-R1（2026-10-19 DSH 侧栏入口）

本节是**增量修复轮**，不替代第 5 节的完整 V00–V22 矩阵；它只覆盖“侧栏 DSH 入口打不开”的修复验证。完整发布仍需按 V00–V22 重跑/补齐，且不得把本轮的 C/E 结果外推为完整 DSH 对齐。

### 10.1 范围、来源与证据目录

- 修复交接文档：[DSH_SIDEBAR_ENTRY_HANDOFF_AND_FIX_PLAN_20261019.md](../../repair/DSH_SIDEBAR_ENTRY_HANDOFF_AND_FIX_PLAN_20261019.md)。该文是当前入口问题的**当前接手入口**。
- 根因：`ChatViewModel` 重建 `ChatShellActions` 时漏转 `onOpenDshWorkspace`；另有启动链空模型目录、401 readiness 误判、页面 Starting 恢复三类候选修复。
- 修复轮基准：`21f8b1b3f17a233bcfadf9c810465eb6e1c37de3`（Lead 指定/交接文档复核点）；骨架创建时实际 HEAD 已到 `f39e8fc9724523f8ae32d0c3ae540d14d62215cd`，最终 pass 必须填写 `headFinal` 并重算全部指纹。
- 证据骨架目录：`evidence/R07-20261019-21f8b1b/`，包含 `run.json`、`R07-REPORT.md`、`C1-C7-RESULTS.md`、`E1-E9-MATRIX.md`、`KNOWN-LIMITS.md`、`EVIDENCE-INDEX.md`。
- **结果保持规则**：骨架中所有结果单元初始为 `NOT_RUN`；Lead/验证者粘贴绑定源码、APK、设备的原始证据后才可改值。候选代码落盘、编译成功、宿主 READY、端口打开都不等于 PASS。

### 10.2 C1–C7 测试门（结果保持 NOT_RUN）

| ID | 任务 | 最低期望 | 结果 |
| --- | --- | --- | --- |
| C1 | `:feature:chat:testDebugUnitTest --tests com.muyuchat.feature.chat.ChatWorkspaceNavigationTest` | ≥2 项，禁止 tests=0 | **NOT_RUN** |
| C2 | `:core:dsh-runtime:testDebugUnitTest` | 全部通过，含新 Probe 的 401/403/404/500/空 body 回归 | **NOT_RUN** |
| C3 | `:feature:dsh-workspace:testDebugUnitTest` | 全部通过，含生命周期、竞争与内联调度补充 | **NOT_RUN** |
| C4 | `:api:dsh-gateway:testDebugUnitTest` | 全部通过（认证/路由/流式/取消） | **NOT_RUN** |
| C5 | `:app:testDebugUnitTest --tests com.muyuchat.mca.dsh.DshGatewayAssemblyTest` | ≥2 项，真实 HTTP 装配 | **NOT_RUN** |
| C6 | `:feature:chat:testDebugUnitTest` + app DSH 集合 | 过滤器实际匹配，旧契约不回归 | **NOT_RUN** |
| C7 | `python tools/architecture/check_architecture_rules.py` | 退出码 0，只声明脚本实际覆盖范围 | **NOT_RUN** |

执行要求沿用第 3 节：持构建锁、保存原始日志与 XML、核对实际执行数；`-SkipTests`、tests=0、空匹配、旧 XML、mock 都不能算 PASS。

### 10.3 E1–E9 真机矩阵（结果保持 NOT_RUN）

| ID | 场景 | 通过判据 | 结果 |
| --- | --- | --- | --- |
| E1 | 聊天侧栏点击 DSH 的复现闭环 | 离开聊天并进入 DSH 启动/前端，不只关闭侧栏 | **NOT_RUN** |
| E2 | 无模型冷启动 | 官方前端可见可操作，空模型状态真实，无伪造推理成功 | **NOT_RUN** |
| E3 | 官方资源就绪 | boot/index、bootstrap JS、WebSocket 等真实依赖正常，非仅宿主 READY | **NOT_RUN** |
| E4 | 认证边界 | 认证前 401、认证后可加载；token 不入日志/截图/持久导航 | **NOT_RUN** |
| E5 | 重复进入 | 复用宿主，不起第二 Node，页面恢复，聊天可用 | **NOT_RUN** |
| E6 | 后台恢复 | 页面恢复，未取消的 DSH 工作不因切页误杀 | **NOT_RUN** |
| E7 | 运行时失败/新代恢复 | 可重试失败，新 READY 恢复，旧代回调不覆盖；无安全注入则 NOT_RUN | **NOT_RUN** |
| E8 | 模式隔离 | 调参/Kotlin Agent/DSH 各自正确，Kotlin Agent 不被替换 | **NOT_RUN** |
| E9 | 模型目录变化 | 记录官方 UI 是否发现新加载模型；未验证则保留限制 | **NOT_RUN** |

- E7 不得以杀死整个 MCA 主进程的方式影响正在进行的聊天/图片任务；没有安全故障注入条件时记录 `NOT_RUN`。
- E9 不得把“网关 `/models` 已更新”写成“官方模型选择器已刷新”；Seeder 只在启动时生成 overlay，`restart()` 在 READY 时可能只附着当前代。

### 10.4 本轮已知限制（不得因 C/E 通过而消失）

| 限制 | 状态 | 约束 |
| --- | --- | --- |
| 真 PTY | **FAIL** | 普通 pipe 不是 PTY；参考 payload 以 child_process 替换 node-pty；终端/PTY 不得写 PASS |
| Android sandbox | **fail-closed / workspace-write** | 不宣称完整 sandbox 强制；审批保持 ask；不复制 danger-full-access 默认 |
| 真隔离 | **不宣称** | 单 APK 同 UID；插件是应用内受信任可执行代码 |
| 模型启动后刷新 | **未验证** | 见 E9；不承诺自动刷新 |
| 原生 sharp | **不宣称** | 参考 payload 为纯 JS 替换 |
| 全离线 | **不宣称** | 云模型/远端 MCP/搜索/在线插件需要网络 |

详见 `evidence/R07-20261019-21f8b1b/KNOWN-LIMITS.md`。

### 10.5 执行顺序、预算与回退

1. **C0 冻结**：`headFinal`、tracked/untracked/子模块内部 diff、构建锁持有者（约 15 min）。
2. **C1–C7**：持锁串行执行、保存日志/XML（约 45–75 min）；失败即记录，不在 pass 内改测试。
3. **候选 APK**：绑定源码与工作区补丁构建、记录 SHA-256/签名/版本，原位升级并保留数据（约 30–60 min）。
4. **E1–E9**：从真实 `MainActivity` 侧栏进入，按矩阵采集证据（约 60–90 min）。
5. **证据粘贴与复核**：填 `run.json`、报告与索引，单独列出 FAIL/NOT_RUN/BLOCKED 和限制（约 30 min）。
6. **合计**：约 3–4.5 h（计划值，非实测）。

回退按四个切片分别处理：导航（ChatViewModel 回调）、HTTP（Probe 错误流）、空目录（Gateway 装配/Seeder）、页面状态（Workspace ViewModel）。由于大量文件未跟踪或混有既有改动，禁止 `git restore` 整目录、`git reset --hard`、`git clean` 或删除整个新模块；不要为回退清除 DSH 用户数据。

### 10.6 与完整 R07 的关系

- 本轮通过只证明“侧栏入口 + 空目录启动 + 401 readiness + Starting 恢复”的候选修复在给定产物/设备上成立。
- 不证明：完整官方前端能力、模型/工具协议闭环、真实 PTY、原生 native 依赖、插件/MCP 执行、后台长任务、数据升级、安全隔离。
- 发布前仍需按第 5 节完整矩阵与 R01/R02/R03 证据补齐；未完成项保持 `NOT_RUN/BLOCKED`。

### 10.7 2026-10-20 验证状态更新（部分证据）

独立验证者 `dsh-verify` 在设备 `device-b` 上完成一轮检查（run `20261020-001623`，APK SHA-256 `4500a34e3cc9b5f8dede14b98354281c5a7dd8d03f51281fd6237ba13556ddc5`，393,690,015 B）：

| 项 | 结果 | 说明 |
| --- | --- | --- |
| APK 身份/签名/原位安装 | **PASS** | firstInstallTime 未变；既有聊天/模型可见；无卸载/清数据 |
| 冷启动 + 真实模型加载 | **PASS** | COLD 191 ms；Qwen3.5 0.8B MNN CPU `已就绪` |
| E1 入口 | **PASS（partial，entry only）** | 进入 DSH、官方 shell + `预览版说明` 弹窗渲染；主区随后空白 |
| E2 空模型启动 | **PASS（partial；Lead 提供）** | device-a READY + 官方 sidebar；主区空白；非独立验证 |
| E3 官方功能前端 | **FAIL（partial）** | READY/认证/单 Node/网关 PASS；官方主对话视图不渲染 |
| E5 单宿主/页面恢复 | **FAIL** | 单 `:dsh`/Node PID 不变 PASS；重进页面仍空白 FAIL |
| E6 后台恢复 | **FAIL** | 前后台切换后仍空白 |
| E8 模式隔离 | **PASS** | Kotlin Agent、聊天、模型保持可用 |
| C1–C7 | **NOT_RUN** | verifier 明确未判断（`NOT_MINE`） |
| E4/E7/E9 | **NOT_RUN** | token 审计、受控故障注入、post-start 模型刷新均未做 |
| DSH 聊天 | **NOT_RUN** | 无 composer/输入区 |

- **发布阻断 `BLOCKER-WHITE-PANE`**：官方 `预览版说明` 点 `继续` 后主对话区纯白，只剩 hamburger；`新会话/插件/更多` 无内容；工作区 import 灰色空遮罩；打字无 IME；旋转/后台/重进不恢复。运行时 READY、认证通过、单 Node、网关 models=2，无崩溃/ANR，失败点隔离在 onboarding 后的 WebView 客户端渲染。
- 该阻断意味着 E1/E2 只能记 partial PASS，**不得**宣布“侧栏修复完成/DSH 可用”；修复后必须重跑 E1/E2/E3/E5/E6 + DSH chat，并保留 C1–C7 与限制证据。
- 证据路径：`artifacts/dsh-verify/20261020-001623/`（SUMMARY.md、verdict.json、shots/、logs/、identity/）；空模型截图 `E:\tmp\f9-u1.png`、`E:\tmp\f9-u2.png`（Lead 提供）；细节见 `evidence/R07-20261019-21f8b1b/`。

### 10.8 2026-10-20 01:35 修复候选更新（白屏根因已证明并修复）

- **根因（live WebView CDP on device-a）**：官方页面已挂载、client plugins ACTIVE，但 Android WebView 的 initial containing block 为零高：`window.innerHeight`/`documentElement.clientHeight` = 857，`100vh` = 0，`html/body/#root` computed height = 0；fixed 层（onboarding/sidebar）能画，正常流 app frame 塌陷 → 主区白屏。排除 render error/error boundary/plugin failure（console errors 空、`[data-slot-error]` 不存在）。
- **FIX-1**：`DshPageLayoutShim.kt` 把测量到的 viewport 高度写成 html 的显式 px，page start/commit/finish + resize/orientation 同步；CDP 验证 html/root 857px、官方 hero+composer 渲染。
- **FIX-2**：seeder 写 `workspace-controller.documentsDirectory = <files/dsh/workspace>`，修复 Android 首用默认工作区创建失败。
- **修复候选 APK**：`app/build/outputs/apk/diagnostic/app-diagnostic.apk`，SHA-256 `0d48974f25ec681ef47d6c0798efa464e52b04c79f81f4074f5dc35db107027d`，563,846,247 B，versionName `0.2.4-diag`（debuggable，非 release 产物）。
- **视觉证明**：`E:\tmp\f9-vp3.png` 显示官方 `探索未至之境` / `默认工作区` / `标准模式` / composer / `请选择模型`（Lead 提供，非独立 verifier）。
- **当前状态**：`BLOCKER-WHITE-PANE = FIXED_PENDING_INDEPENDENT_E2E`；verifier run `20261020-013442`（device-b）正在重跑 E2/E3/E5/E6/E8/E9 + real chat。**在 A2 证据到达前，E3/chat 保持 `NOT_RUN`，不得写 PASS 或发布“DSH 可用”。**
- **Review P1s（Lead 报告）**：Node cwd=workspace、stop-fence re-scan、seeder name label 已处理（代码存在/Lead 报告，设备证据待补）；模型启动后刷新仍是 [KNOWN-LIMITS.md](evidence/R07-20261019-21f8b1b/KNOWN-LIMITS.md) 中的 documented limitation。
- **A1 历史 vs A2 当前**：A1 release `4500a34e…` 的 E3/E5/E6 FAIL、E1/E2 partial PASS、E8 PASS、chat NOT_RUN 只作历史；A2 除 E1 entry 外全部 `NOT_RUN`。diagnostic 通过后仍需 release-build E2E 与 C1–C7。

### 10.9 2026-10-20 02:38 A3 结果（UI/Picker PASS，模型后端 FAIL/BLOCKED）

- **A3 APK**：diagnostic `294d965613f676a7de877b80a4824ca8ab8893ca399584c4aaea0c0af29beeb6`，563,846,247 B，mtime 02:17:20，02:23:57 在 device-b 原位安装（数据保留）；verifier run `artifacts/dsh-verify/20261020-022248`。
- **已关闭**：白屏 `BLOCKER-WHITE-PANE`（A3 hero/composer/workspace 渲染）；picker 裁剪 `BLOCKER-PICKER-CLIPPING`（A2 menu 0/0 → A3 90/360px，两模型可见，真实 adb tap 切换 chip；根因是 html height shim 后 CSS `vh` 仍为 0，CSSOM vh/dvh rewrite 修复）。
- **PASS**：E1 入口、E2 官方 UI/默认工作区、E3 官方前端 + picker、E5 重复进入/会话恢复/单 Node（同 PID 11167/11197）、E6 后台恢复（A2 证据）、E8 模式隔离。
- **FAIL / BLOCKED（仍诚实保留）**：本地模型 agent turn **FAIL** — `n_ctx=8192` 预算（system 5,640 chars + 31 tool schemas 28,614 chars ≈9.8k tokens），`PI_AI_ERROR`、0 tokens；云端模型回合 **BLOCKED external** — 上游 503 无可用渠道（gemini-3.6-flash-high，5 次重试）；成功 DSH chat = NONE。
- **NOT_RUN**：E4 token 审计、E7 受控故障注入、E9 post-start catalog refresh（start-visible PASS）。模型启动后刷新仍为 documented limitation。
- **C1–C7 PASS**：C1 2/2、C2 77/77、C3 68/68、C4 77/77、C5 74/74、C6 chat 357/357 + app 74/74、C7 architecture PASS per Lead；计数已用 Gradle XML 交叉核对（0 failures）。**注意**：C1/C6 的 chat XML 时间戳早于 A3 final gate；C7 最终原始日志仍缺。
- **Release（A4）**：release `app-release.apk`，`59a407242feddcdb8bca4598231cb9cc831a6c461e9c92444909f77868e031df`，393,702,651 B，0.2.4，02:44:37；`:app:assembleRelease -Pmca.abis=arm64-v8a -Pmca.keepNativeSymbols=true`，BUILD SUCCESSFUL 22m06s，`RELEASE3_RC=0`；`assets/dsh/payload.zip` 160,464,248 B + 7 个 `libdsh*.so`。**release E2E 进行中，未 PASS**；`headFinal` 未冻结。之前 02:15:17 的 release `360F8632…460EE` 已被 A4 取代。
- **发布判断**：UI/测试门 PASS 不等于聊天可用；A4 release E2E + C7/C1/C6 证据 + 模型回合修复前，不发布“DSH 聊天可用”。
- **本地模型 memo**：默认 `n_ctx=8192`（`MnnRuntimeParameterAdapter`）；最小能力损失修复是经现有 tuning override 提到 **12288/16384**（KV 成本需测量），备选 reduced agent preset；在真正验证更大上下文前 chat 保持 FAIL。

### 10.10 2026-10-20 02:48 A4 release identity（E2E pending）

- A4 release：`59A40724…31DF`，393,702,651 B，mtime 02:44:37，`com.muyuchat.mca` 0.2.4（8），minSdk 26 / targetSdk 36，signer `2619ac4c…a9cf`；zip 内容含 `assets/dsh/MANIFEST.json` 7,664 B + `assets/dsh/payload.zip` 160,464,248 B + 7 个 `lib/arm64-v8a/libdsh*.so`；R00 独立重算 SHA-256/大小一致。
- 构建日志：`E:\tmp\dsh-final-gates\release3.log` BUILD SUCCESSFUL 22m06s；`index.txt` `RELEASE3_RC=0`。
- 状态：**BUILT / RELEASE E2E PENDING**。独立 verifier 尚未返回；此前 A3 diagnostic 结果不得复制为 A4 release 结果；`headFinal` 仍未冻结。
- Release E2E 必须覆盖：安装/数据保留、DSH 入口与官方 UI、workspace/picker、E5 重进、local/cloud 模型回合（保持 FAIL/BLOCKED 真相）、E4/E7/E9 缺口、以及 C1–C7 与 `headFinal` 重新绑定。

### 10.11 2026-10-20 02:55 A4 本地模型适配最终验证（PENDING）

- **目标**：在 A4 release `59A40724…31DF` 上把已加载 Qwen3.5 0.8B MNN 的 `n_ctx` 从 8192 提到 **12288/16384**，reload，重启 DSH 宿主让 gateway catalog/overlay 以新 `contextWindow` 重新播种，然后发送一条真实 DSH 回合。
- **必须的步骤**：记录 tuning override 变更 → reload 模型并确认 engine active n_ctx → **stop 并等待 stop fence**，再 `ensureStarted` 新 epoch（`DshRuntimeOwner` 对 READY 代直接返回当前代；仅 `restart()`/`ensureStarted` 不会重播种）→ 核对 gateway `/v1/models` 与 overlay 的 `contextWindow`（`DshMcaPorts.activeModel` 从 `resolvedLoadBoundValues["n_ctx"]` 映射）→ 真实 DSH turn + 截图/日志。
- **结果规则**：
  - 成功：`BLOCKER-LOCAL-MODEL-CONTEXT` 从 FAIL 改判为 **“requires n_ctx ≥ ~12k (tunable)”**；chat 只在 verifier 返回匹配 screenshot/log 后才可改 PASS。
  - 失败：保留 FAIL，附新的 contextWindow/error/tokens 证据。
  - 阻断：记录 BLOCKED 及原因；不得写 PASS。
- **禁止**：用 A3 diagnostic 结果冒充 A4 release；在 READY 时调用 `restart()` 并声称 overlay 已重播种；在 verifier 返回前写 chat PASS。
- 证据：tuning 前后值、engine active n_ctx、gateway contextWindow、host stop fence/new epoch、真实 turn 截图/日志、设备 fingerprint + A4 APK SHA。

### 10.12 2026-10-20 03:32 最终候选与 A4 结果

- **A4 previous artifact 结果（provisional）**：release `59A40724…` 的 E2E 为 PASS_PARTIAL（identity/install/entry/READY/UI/workspace/picker/E5/single-Node/no-crash）；本地回合 12288 FAIL、**16384 PASS**（1轮1步/1分54秒/5.8 tok/s/6.7K tok，engine `context=16384 backend=mnn_cpu`，catalogRevision `645ec67dd7def7a6`）；质量 **MODEL_QUALITY_ONLY**；云端 503 BLOCKED external；E4/E7/post-start refresh NOT_RUN。要求：**n_ctx ≥ 16384（tunable）**。
- **最终候选**：`0a731d84507813daf0f1dda64eaf8e98e2f934911cc71cbe839a4e5fbfcdfb62`，393,714,839 B，03:27:50，0.2.4 (8)，signer `2619ac4c…a9cf`；R00 预检 payload + 7 libs + `classes.dex` display-name strings 通过。**FINAL-ONE-PASS PENDING**。
- **规则**：FINAL-ONE-PASS 前 final 列全部 PENDING；返回后绑定到最终候选 SHA，填 [R07-DECISION-SHEET.md](evidence/R07-20261019-21f8b1b/R07-DECISION-SHEET.md)，再发一屏裁决。不得把 A4 provisional PASS 复制为最终 PASS；不得把 12288 写成可用；不得把 MODEL_QUALITY_ONLY 写成协议失败。
- **开放项**：P1 stop-fence；P2 display-name 行为（fix strings 已在最终候选，行为待验）；P2 plugins.log；P2 pending-profile-transaction UX；P3 stop_marker；C7 final raw log；`headFinal` 未冻结。

### 10.13 2026-10-20 03:41 FINAL-ONE-PASS 最终裁决

- **Run**：`artifacts/dsh-verify/20261020-033121/`（FINAL-ONE-PASS.md、verdict.json，03:31–03:39）。
- **APK**：`0a731d84507813daf0f1dda64eaf8e98e2f934911cc71cbe839a4e5fbfcdfb62`，393,714,839 B，0.2.4 (8)，min 26 / target 36，signer `2619ac4c…a9cf`，non-debuggable；payload 160,464,248 B + 7 libs。
- **PASS**：identity/install/data、198 ms cold start、n_ctx=16384 load、DSH READY+FIRST_FRAME、hero/composer/workspace、human-readable picker+adb tap、local turn（1分19秒/6.6K tok）、E5/E6/E8、single Node/no crash、C1–C7（C7 raw log 缺）。
- **FAIL**：12288 insufficient（A4 evidence）。
- **BLOCKED**：cloud 503 external。
- **NOT_RUN**：E4 token audit、E7 controlled failure injection、E9 post-start refresh。
- **Caveats**：0.8B quality MODEL_QUALITY_ONLY；display-name 仍含 UUID 后缀；P1 stop-fence descendants；P2 plugins.log；P2 pending-txn UX；PTY FAIL；sandbox fail-closed workspace-write；isolation/offline/native-sharp 不宣称；headFinal/commit 未冻结。
- **Decision**：**PASS WITH CAVEATS**（不是 clean all-green）。最终值见 [R07-DECISION-SHEET.md](evidence/R07-20261019-21f8b1b/R07-DECISION-SHEET.md)。

### 10.14 2026-10-20 03:52 HARDENING 候选 E0B97FB3（待 FINAL-ONE-PASS）

- **候选**：`e0b97fb35d1557c538de114ff1aa0e58a6194660fd7d5ba873c33ef34cff2e59`，393,715,027 B，03:49:47，0.2.4 (8)，同 signer；`0A731D84…` 降为 previous。
- **R00 预检**：identity/payload/7 libs 通过；R8 `mapping.txt` 含 `fencedPids` ×1、`DshLocalModelDisplayName` ×32；`survivingPids`/`trackedPids` 在 mapping 未命中（Lead 报存在，需 verifier/代码 review）；runtime tests 80/80。
- **Verifier 待测**：label without UUID、local turn、E5；未返回前 final 全 PENDING。
- **若通过**：label P2 closed；stop-fence P1 code-closed（device survivor injection 仍 NOT_RUN）；release PASS with same model/cloud/PTY caveats。
- **不得**：把 0A 的 FINAL PASS 复制给 E0B；把 mapping 存在等同于行为验证；把 device survivor injection 缺失写成 PASS。

### 10.15 2026-10-20 04:15 REBUILT HARDENING 2005DF02（待 FINAL-ONE-PASS）

- **候选**：`2005df024a66bc5c21123e73dafa71d7c1c80a319f5048003d64aed2ef7b94dc`，393,715,727 B，04:06:35，0.2.4 (8)，同 signer；`E0B97FB3` stale，`0A731D84` previous。
- **R00 预检**：identity/payload/7 libs 通过；dex 含 `dsh: stop fence blocked start; pids=`（×1）、`survivingPids`（×1）、label regexes `[0-9a-f]{8,}` 与 `(?i)(?<![A-Za-z0-9])[0-9a-f]{8,}`（各 ×1）；R8 mapping（04:04:21）含 `getTrackedPids` ×3、`getSurvivingPids` ×1、`fencedPids` ×1、`DshLocalModelDisplayName` ×36；`release-hardened.log` BUILD SUCCESSFUL 6m45s，`RELEASE_HARDENED_RC=0`；runtime XML 80/80（早于 build）。
- **Verifier 待测**：label without hex token、local turn、E5；未返回前 final 全 PENDING。
- **若通过**：label P2 expected closed if no hex token；stop-fence P1 code-closed（device survivor injection 仍 NOT_RUN）；release PASS with same model/cloud/PTY caveats。
- **不得**：把 0A/E0B 的 PASS 复制给 2005DF02；把 dex/mapping 存在等同于行为验证；把 device survivor injection 缺失写成 PASS。

### 10.16 2026-10-20 04:22 FINAL HARDENING PASS（绑定 2005DF02）

- **Run**：`artifacts/dsh-verify/20261020-041600/`（HARDENING-VERIFY.md、verdict.json，04:16–04:22）。
- **PASS**：identity/not-stale、install 04:17:19 data preserved、191 ms cold start、n_ctx=16384 load、DSH READY epoch=1 origin :44353 catalogRevision `0c4f6330c0c62c80`、picker `Qwen3.5 0.8B mnn [PROMPTED_TOOL_V1]`（hex8/uuid empty）+ cloud + adb tap、local turn 1m26s/7.6 tok/s/6.6K tok、E5 PID 29162/29191、single Node、no crash、C1–C7。
- **Closed**：white-pane、picker clipping、local-context tunable ≥16384、stop-fence code inclusion、label UUID strip。
- **Open/caveat**：P1-R1 crash/service-restart orphan scan；P1-R2 client synthesized STOPPED；device survivor injection NOT_RUN；cloud 503 BLOCKED external；0.8B quality MODEL_QUALITY_ONLY；E4/E7/E9 post-start refresh NOT_RUN；PTY FAIL；sandbox fail-closed workspace-write；plugins.log packaging；pending-profile UX；C7 raw log；headFinal/commit 未冻结。
- **Decision**：**PASS WITH CAVEATS**，绑定 `2005DF02…94DC`；不是 clean all-green。一屏见 [R07-DECISION-SHEET.md](evidence/R07-20261019-21f8b1b/R07-DECISION-SHEET.md)。

### 10.17 2026-10-20 05:12 ABSOLUTE FINAL B610E1D0（待短回归）

- **候选**：`b610e1d0d29195e966e705a4a5113f46d8601a16349f45bedb98954ace73ae9a`，393,717,991 B，05:06:13，0.2.4 (8)，同 signer；`2005DF02` previous，`B63149B8` intermediate，`E0B97FB3` stale。
- **R00 预检**：identity/payload/7 libs 通过；dex 含 `stop verification failed` ×1、`found survivors` ×1、`survivingPids` ×1、label regexes 各 ×1；`refusing start orphans`/`Cafe2024` 未逐字命中；runtime XML 93/93，0 failures；P1-R1/R2 Lead 报告 code review 关闭；label P2 closed except Cafe2024 edge。
- **Verifier**：short regression（label/Cafe2024 edge、local turn、E5）PENDING；final 全 PENDING。
- **保留 caveat**：device survivor injection NOT_RUN；cloud 503 BLOCKED external；0.8B quality MODEL_QUALITY_ONLY；E4/E7/E9 post-start refresh NOT_RUN；PTY FAIL；sandbox fail-closed workspace-write；C7 raw log；headFinal/commit 未冻结；plugins.log；pending-profile UX。
- **若通过**：bind R07 到 B610E1D0，P1-R1/R2 code-review closed，label P2 closed except Cafe2024 edge；仍为 PASS WITH CAVEATS。
- **不得**：把 2005DF02/0A 的 PASS 复制给 B610E1D0；把 code review 等同 device survivor injection 验证；把 Cafe2024 edge 写成完全关闭。



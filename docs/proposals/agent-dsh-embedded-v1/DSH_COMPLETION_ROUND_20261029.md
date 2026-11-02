# DSH 补齐轮次记录（2026-10-29）

范围：MCA 内嵌 DSH 的启动恢复、候选 payload 升级事务、MCP resources 工具链、workspace SAF tree 授权、
能力桥/命令边界证据。基线分支 `codex/enterprise-refactor`，工作区含大量并行车道未提交改动；本轮
未提交、未发布任何内容，也未触碰 `AGENTS.md` 与 `UniversalDeviceAdmissionPolicyTest.kt`。

## 1. 启动恢复返回类型化结果（已实现并测试）

原缺陷（改前源码）：`DshMaintenanceOwner.recoverOnStart()` 用 `runCatching{}.getOrNull()` 吞掉
safe-mode / upgrade resume 的异常，`DshModeAssembly.beforeStart` 是 `() -> Unit`，
`ensureStartupRecovery()` 吞异常后无条件 `startupRecoveryDone = true` 并继续启动 runtime。

行为变化：

- `DshMaintenanceOwner.recoverOnStart(): DshStartupOutcome`（`Proceed` / `Blocked`）。
  每个阶段独立包裹；抛异常记录为 `DshMaintenanceFailure(RECOVERY_REQUIRED, retryable=true)`，
  `detail` 只含阶段名与异常类名（不含消息/路径）。journal 扫描失败时**不再**继续执行会移动文件的
  safe-mode/upgrade resume，直接 Blocked。
- 阻断条件：阶段抛异常、trial 未确认释放、safe-mode Rejected、upgrade `RecoveryRequired`、
  journal 任一事务 `RequiresAttention`、safe-mode 判定为 `RECOVERY_REQUIRED`。
  允许启动：upgrade 为 `TrialFailed/RolledBack/Rejected`（selector 未移动）。
- `DshModeAssembly.beforeStart: (() -> DshStartupAdmission)?`；拒绝时返回
  `FAILED` 快照并带 `DshFailureCode.STARTUP_RECOVERY_REQUIRED`，**不调用** `client.ensureStarted/restart`。
- 拒绝不落永久闩锁：下一次启动重跑 sweep（重试路径）；成功 Proceed 才记 `startupRecoveryDone`。
- UI 投影：`DshFailureProjection` 新增标题/中文说明/可重试文案。

文件：`core/dsh-maintenance/.../DshMaintenanceOwner.kt`、`core/dsh-contract/.../DshRuntimeContract.kt`、
`app/.../DshMaintenanceAssembly.kt`、`app/.../DshModeAssembly.kt`、`app/.../DshHostAssembly.kt`、
`feature/dsh-workspace/.../DshFailureProjection.kt`。

测试：`StartupOutcomeTest`（Proceed / 抛异常 Blocked / RequiresAttention Blocked）。PASS。

## 2. 候选 payload 升级事务（事务已实机提交、生产已切到闭包；§8.2 完成**内容不同**的双版本提交，§8.6 在另一台设备复验试跑与生产切换；回滚的设备复测与官方新版本候选仍未完成）

原缺陷（改前源码自证）：`DshTrialGenerationHost.startTrial` 把 `config.payloadDir`（已安装 payload）
传给 trial，`kernelEntry = File(config.payloadDir, ...)`，candidate root 只当标签；
`CandidateStaging` 只写身份描述符，注释明说“Today's trial launches the already verified installed payload”。

行为变化：

- 候选必须是**可执行**的：`candidate.json` 新增 `payload{root,kernelEntry,archiveSha256,bytes,fileCount,
  criticalFiles[]}`。`DshClosureCandidateSource` 在**使用点**重算关键文件哈希、要求 kernel entry 存在，
  仅身份描述符的候选被 `UPGRADE_INCOMPATIBLE` 拒绝。`DshClosureUpgradeManager.verify()` 同样拒绝，
  因此不可能出现“用已安装 payload 跑候选 trial 还通过”。
- trial 实际从 `<candidate>/payload` 启动：新增
  `DshTrialCandidatePayload.resolve()`（拒绝缺 payload、缺 kernel、`..`/符号链接越界），
  `DshTrialGenerationHost` 用其 `payloadRoot` 构造 `DshRoots.payload`、`kernelEntry` 与环境变量；
  `verifyPayload=false` 仍成立（不安装第二份），但执行的是候选树。
- 中断恢复归因：`DshTrialRecovery` 用 journal 的 `candidateRoot/payload` 作为额外 needle，
  崩溃后仍能证明 journaled PID 属于该候选，否则保持 UNKNOWN。
- 身份持久化：`DshClosureSelector` 新增 `lastGoodFingerprint/lastGoodRoot`（回滚不清除）
  与 `installedPayloadVersion/installedArchiveSha256`（来自 APK 自身 `dsh/MANIFEST.json`，
  由 `DshMaintenanceAssembly` 注入）。提交与回滚都会写入，可核对安装版本/活动版本/上次可用版本。
- debug/test 级 stager 现在真实解压 payload 到 `payload/` 并校验 manifest 的 fileCount 与
  criticalFiles，再走生产候选源复核。

测试：`UpgradeTest`（原 14 + 新增 3：仅身份候选拒绝、staging 后被篡改拒绝、selector 身份持久化）、
`ClosureCandidateTest`（8，夹具补真实 payload）、`CandidateStagingTest`（19，夹具改为真实 zip + 真实
critical 哈希）、`DshTrialCandidatePayloadTest`（4）、`DshRuntimeOwnerTrialTest`（7，断言改为
“执行候选树且不等于已安装树”）。全部 PASS。

诚实边界：**未完成双版本候选实机升级**，不得称升级闭环。test-only stager 的成功不等于生产升级验证。

## 3. MCP resources 工具链

- 已验证（打包 payload 解包内容）：`@deepseek-ai/dsh-mcp-client` 提供 tools；
  `dsh-mcp-resources/lib/index.js` 注册 `resources/list`、`resources/templates/list`、`resources/read`；
  **不存在** `dsh-mcp-prompts`，无任何插件调用 `prompts/list|get` → prompts 明确 BLOCKED（需 payload 升级）。
- MCA 侧改动：声明面类型化（capabilities schema v2；`prompts` 一律拒绝并给稳定错误码；
  未知能力/重复能力/旧 schema 带 capabilities 均拒绝）；stdio 环境拒绝重引入 `DSH_*`；
  服务器数量与工具目录预算（契约常量 `DSH_MAX_MODEL_VISIBLE_TOOLS=128`，网关按常量执行）。
- 证据文档：`docs/proposals/agent-dsh-embedded-v1/DSH_MCP_RESOURCES_CLOSURE_20261029.md`。
- NOT_RUN：真实 `resources/list`/`read`、分页、session 过期、重连、取消、真实子进程环境，
  目前只有静态代码证据，需设备/节点实测。

## 4. DSH workspace 的 SAF tree 授权

- 新增单一 owner：`DshWorkspaceTreeGrantOwner` + 平台端口/实现 + SharedPreferences 持久化记录
  （URI、modes、grant 时间）+ 认证路由 `/workspace/tree/{status,request,release}` + 控制台行。
  状态：`GRANTED / NOT_GRANTED / REVOKED / INVALID / PROVIDER_UNAVAILABLE`，
  每次 status 都以 `persistedUriPermissions` 重新判定；release 先
  `releasePersistableUriPermission` 再报 REVOKED；provider 缺失/抛错为 PROVIDER_UNAVAILABLE。
- 明确区分来源：`DshWorkspaceAccess` 只允许 `PERSISTENT_TREE_GRANT` 携带
  `persistentTreeGrant=true`；WebView 文件选择与下载/导出投影为一次性访问，
  `DshWebFileChooserPolicy` 拒绝 `ACTION_OPEN_DOCUMENT_TREE`。
- 契约不变量修正：树授权来源在未授权时允许投影为 `kind=PERSISTENT_TREE_GRANT, persistentTreeGrant=false`
  （仍禁止一次性/保存路径声称授权）。
- 测试：platform 4 个测试类 + feature 1 个测试类，共 47 例。PASS。
- BLOCKED：真实系统选择器、进程重启后持久化、provider 卸载、Android 14/15 行为需设备 instrumentation；
  打包 payload 尚未注册调用这些路由的 Node 工具。

## 5. 按工具能力与维护门禁

审计结论（file:line 见本轮对话记录）：六类工具均有类型化错误返回与取消路径；
终端停止有真实证明（waitpid + 会话扫描），其余多为“请求停止 + 释放”，无 teardown 证明。

门禁改动（已实现并测试）：此前没有任何生产写入者持有 MUTATION/READ lease，契约注释与实现不符。
本轮把**同一个** `DshCapabilityMaintenanceAdmissionGate` 实例从 `DshHostAssembly` 同时注入
维护 owner 与能力桥，`DshCapabilityServerCore.handle` 在认证后为每个能力请求取一个 MUTATION lease，
在 `finally` 中按 `leaseId+generation` 释放：维护独占屏障与任何工具调用因此互斥；
屏障持有时工具调用返回 `503 CAPABILITY_UNAVAILABLE`（retryAfter=1s）且 handler 不被调用。
分类保持保守（所有 handler 都可能写用户数据；未来只读路由可降为 READ）。
`DshCapabilityMaintenanceLeaseTest` 三例：请求后屏障可立即获取、屏障期间请求被拒且 handler 未执行、
在飞的请求会阻止屏障。PASS。

仍未覆盖：`DshJobLedger.submit`、插件安装（Node 侧）仍以“停宿主”为屏障，尚未接入 lease。

### 5.1 终端会话级租约（已实现并测试）

PTY 会话比开启它的能力请求活得更久，只按“请求级”取租仍有窗口：请求返回后会话仍在写。本轮新增
契约端口 `DshTerminalSessionAdmission`（`core:dsh-contract`，`fun interface`，只暴露
`acquire(owner,session)→release`），由 app 侧 `DshCapabilityTerminalSessionAdmission`
（`core:dsh-platform`）映射共享 gate：
- 子进程创建**之前**取 MUTATION lease；屏障持有时 `acquire` 返回 null → `open` 返回
  `Refused(MAINTENANCE_ACTIVE)`，**不产生子进程**；
- 租约随 `Record` 存活，`closeRecord` 的 `finally` 用 `AtomicBoolean` 只释放一次；
  `closeAll`（代次停止）释放每个会话的租约；
- 路由把 `MAINTENANCE_ACTIVE` 映射为 HTTP 503 / `maintenance-active`。

文件：`core/dsh-contract/.../DshTerminalSessionAdmission.kt`、
`core/dsh-platform/.../DshCapabilityTerminalSessionAdmission.kt`、
`core/dsh-runtime/.../DshTerminalHost.kt`（取/放租约、Record 持租）、`DshTerminalToolService.kt`、
`DshTerminalAssembly.kt`、`DshTerminalToolRoutes.kt`（503）、`DshTerminalLane.kt`、
`DshCapabilityAssembly.kt`（注入）。
测试：`DshTerminalSessionAdmissionTest`（4：会话存活期持租、重复 close 只释放一次、屏障期拒绝且不 spawn、
closeAll 全释放）、`DshCapabilityTerminalSessionAdmissionTest`（2：一次性释放/屏障拒绝）；既有
`DshTerminalHostTest` 13 例回归通过。PASS。

## 6. 命令兼容边界（审计；JVM 可证的用例已存在，其余设备阻断）

`bash` 是 sh 兼容层：解析为 `/system/bin/sh`（mksh/toybox）或 `libdshcmd.so`，
`gnuBash=false`，仅保留 `-c/-i/-s/-l`，剥离 `--noprofile/--norc/--posix/--restricted/--login`。
源码已把 GNU 专属特性建模为可探针的 `DshGnuBashFeature`：
`PROCESS_SUBSTITUTION`、`NAMEREF`（`local -n`）、`SHOPT`（extglob/globstar）、`BASH_VERSION`，
并有测试 `gnuFeaturesCarryAProbeThatARealBashWouldPass` 证明每个探针在真 bash 下会通过。

已由 JVM 测试证明：argv 形状（node/npm/npx/pnpm/pip/python/rg/curl/git/sh/bash）、拒绝原因、
PATH/linker 前缀、PTY 会话语义（fake port）、seam 文本形状。

设备已实测的部分（`/toolchain/verify`）：§8.3 旧闭包 payload 上 9/10（node 26.4.0、npm 11.20.0、npx 11.20.0、
pnpm 10.18.3、pip 26.1.2、python 3.14.6、rg 15.2.0、curl 8.21.0、git 2.55.0 PASS；`bash` 为 `SPAWN_FAILED`，
`gnuBash=false`，缺失特性 arrays/brace-expansion/process-substitution/nameref/shopt/bash-version）；
**§8.6 的新构建 + 安装 payload 上是 10/10 PASS（含 BASH，out=`dsh-toolchain-ok`），且 PTY 内
`printf dsh-pty-ok; uname -m` 返回 `dsh-pty-ok`/`aarch64`。**设备实测同时暴露一个真实缺陷并已修：APK 打包了 `libdshcmd.so`，
但闭包 payload 没有 `bin/bash` 启动名时，解析器仍把 `DispatcherLink` 指向不存在的路径，
PTY 子进程 exec 失败以 `exitCode=127` 立即退出。现在 `DshCommandClosure` 只有在
`model.payloadFile("bin/bash")` 为真时才使用 dispatcher；否则退回平台 shell（同样剥离 bash 专属参数，
`gnuBash=false`），并新增用例 `aDispatcherWithoutThePayloadBashNameFallsBackToThePlatformShell`。

**仍未证明（设备阻断，不得推断 PASS）**：PTY 内真实命令执行（当前 payload 的 open 即 127，
修复后需重装并复测）、`python -c "import json"`、插件执行、真实 `libdshpty.so` resize/SIGWINCH/reap、
sh 对数组语法/`BASH_VERSION` 的实际拒绝。

## 7. 验证记录（本机，非设备）

- `python tools/architecture/check_architecture_rules.py`：19/19 PASS（在契约不变量修正后复跑）。
- 编译：`:core:dsh-{contract,runtime,platform,maintenance}`、`:api:dsh-gateway`、
  `:feature:dsh-workspace`、`:app` `compileDebugKotlin` 全部 BUILD SUCCESSFUL
  （`-x :core:native:verifyMcaQnnSdkHeaders`，该任务因本机缺 QNN SDK 头而失败，与本次改动无关）。
- 单测：6 个模块合计 **1285 例，0 失败**（runtime 4 例跳过为既有 `@Ignore`）：
  contract 34、runtime 499、platform 160、maintenance 128、api/dsh-gateway 236、feature/dsh-workspace 228。
- `:app:compileDebugKotlin`：**BUILD SUCCESSFUL**（全量 runtime 测试同一调用，6m12s；
  协作者抽取后再次单独编译通过 2m42s）。
  说明（撤回）：更早一次编译在 `agent/workflow/WorkflowExecutionService.kt` 失败，属并行车道
  03:07 的未完成编辑；该车道 03:19 补齐后重跑通过，因此 DSH 装配（启动闸门、共享维护门禁、
  终端会话租约、SAF 路由挂载）已获得模块级编译证据。

## 8. 设备与账号

- 设备在线：`98a37aa7`、`f9d98aa3`（arm64-v8a），`/data` 余量 6.4 GB。
- 测试前已安装 APK：`/data/app/~~UFHwcG0nfFikZc-MQA8kDw==/com.muyuchat.mca-IIF9UPkVkSfIKOCxMrW9cg==/base.apk`，
  SHA-256 `22578fdd2ce68cc52dd106ab89ddebdf291958552e8c158f4d7a8997b103ccc3`，
  版本 `0.2.4-diag` (versionCode 8)。该 APK 不含本轮改动，不作为本轮证据。
- 更正（2026-10-29）：先前“本机无 QAIRT/QNN SDK”的结论**撤回**。SDK 实际存在：
  `E:\model\qairt-sdk\qairt.45.0.260326`（8 个构建必需头文件齐全），失败原因只是未设置
  `MCA_QNN_SDK_ROOT`。已用 `-PmcaQnnSdkRoot=E:\model\qairt-sdk\qairt.45.0.260326` 重新构建
  `:app:assembleBenchmark`（diag：同 applicationId + release 签名，可覆盖安装）。首次尝试因与并行车道
  Gradle 并发（对方持 `build/.mca-exclusive-build.lock`，pid 10416）导致 daemon 原生 OOM
  （`hs_err_pid32996.log`：`Native memory allocation (malloc) failed ... Chunk::new`）；
  已改为排队等锁 + `--max-workers=2 -Xmx4g` 重试。构建结果与设备步骤见本轮最终交付。
- 构建阻塞链（设备步骤 NOT_RUN 的真实原因）：① lint 锁竞争 → daemon 原生 OOM（已改为排队）；
  ② 排队重试时 `feature/modelhub/RecommendedModelsSection.kt:616-647` 处于并行车道的半写状态
  （`Modifier 'enum' is not applicable to 'local class'`、`Syntax error: Expecting '}'`），
  该文件不属于本任务写集，按车道规则不改；构建已改为“等锁 + 该文件连续 8 分钟未变”后再执行。
- 回滚备份（安装前已拉取）：`D:\mca-archive\dsh-round-20261029\mca-0.2.4-diag-baseline.apk`，
  SHA-256 `22578fdd2ce68cc52dd106ab89ddebdf291958552e8c158f4d7a8997b103ccc3`（= 安装中 base.apk）。
  安装任何新 APK 前该文件即为回滚点；若签名/版本不符导致无法回装，保持现状不动。
- DeepSeek 账号：未使用（NOT_RUN）。

### 8.1 2026-10-29 设备实测（第一轮，候选已暂存，升级未提交）

- 安装：诊断构建 `app-diagnostic.apk`（含本轮 DSH 改动，release key），SHA-256
  `f3f4e93c5f92d2b494bcf0e8b698f5538c15c463d10ef615a2235dfad70fbeeb`；
  覆盖安装后设备实测 `base.apk` 同哈希
  （`/data/app/~~ks7TAM8NDIDBgGMc4xrLxw==/com.muyuchat.mca-sVzdrhnGAESYGV-jAWJ8qw==/base.apk`），
  versionName `0.2.4-diag`/vc8，仍是 DEBUGGABLE。
- 启动冒烟 **PASS（设备）**：打开 DSH 后 gateway `READY generation=1 models=7`（127.0.0.1:41351）、
  terminal lane `REAL_PTY` 9 routes、capability bridge `READY port=44269 routes=33 vscreen=true`；
  WebView 探针 `title="DeepSeek Harness" href=http://127.0.0.1:45551/ readyState=complete`；
  控制台 `运行阶段=就绪`、首帧=已渲染、模型绑定=已绑定。未见 `STARTUP_RECOVERY_REQUIRED` 拒绝。
- 候选生成（PC，生产 stager，非 descriptor）：从构建 APK 的 payload 派生 v2 候选
  `payloadVersion=...+mca-acceptance2`（生产 payload + 1 个 marker 文件），
  archive sha `bfb0ab0d…`、fileCount 31022；staged fingerprint
  `9f9e6bcca0e6eac980755d3d72293e4d78e1fa30a3b228acdcfb7f5e3c92d19d`、10 组件。
  此候选是**合成的 v2**（非官方新版本），设备验证只能证明事务可执行，不等于官方 v2 已验收。
- 发现并修复两个真实缺陷（`CandidateStaging.kt`，测试用例已补）：
  1. zip 目录项（`bin/`）把带尾斜杠的名字传给 `resolveWithin`，安全谓词判空段非法，
     **任何含目录项的 payload 都无法 staging**。改为 `trimEnd('/')` 后再解析。
  2. 早退 `return@use null` 落在外层 lambda，函数最后仍返回 `files=0`，把“中止”伪装成
     “0 个文件 ≠ manifest”；同时文件输出内的 `return@use` 只退出内层 `use`。
     改为具名 `zipScan@` 标签、`files` 作为扫描结果返回，并在 IO_FAILURE detail 带异常类名。
  新增回归：`directoryEntriesInTheArchiveDoNotAbortStaging`（含目录项的 zip 仍可 staging）。
- 候选推送（设备）：`adb push` tar → `cat tar | run-as tar -x`（512,757,760 B），
  落 `files/dsh/closure/incoming/v6/`；`candidate.json` 设备哈希
  `fc60b426…` 与本地一致，payload 31022 文件，6 个 critical 文件设备 sha256 全部匹配。
- 控制台识别候选：整闭包升级 `候选版本 v6`、阶段 `已回滚`；宿主停止时“运行整闭包升级”可用。
- 升级实跑（3 次）：均写入 `phase=ROLLED_BACK detail=HOST_STILL_RUNNING`，
  并已执行预升级备份（控制台 `pre-upgr…ef8 未校验·37 项·165 KB`）。对照 `ps -A`：
  `libdshnode.so` 已退出，仅剩 `com.muyuchat.mca:dsh` 服务进程，说明进程已静默但
  `stopAndConfirm` 仍返回非 STOPPED。
- 根因（生产装配，已修）：`app/.../DshMaintenanceAssembly.kt` 的 `productionHost.stopAndConfirm`
  在 `runtime.stop()` 后**立即**读 `runtime.snapshot.value`，读到停止前快照；
  升级事务的串行闸门因此永远看不到 STOPPED。修复为：等待快照进入 `STOPPED`（或 `FAILED`）
  且受 `timeoutMillis` 约束，并把“已退出（PROCESS_EXITED）”判定为静默 STOPPED（带 detail 证据）。
  结论：该修复需新 APK 实机复测；在复测完成前，**不得声称双版本候选升级闭环**。
- DeepSeek 账号：未使用（NOT_RUN）。
- 取证文件：`D:\mca-archive\dsh-round-20261029\`（安装前后 APK 哈希、`smoke-0*`、
  `console-0*`、`upgrade-0*` 截图、`device-before-*` 与升级状态快照）。

## 9. 回滚

- 代码：本轮全部为未提交改动，回滚 = 恢复上述文件到本轮基线；未触碰他人文件。
- 数据：selector 新增字段为向后兼容读（缺省空），旧 selector 仍可读；候选格式新增 `payload` 段，
  无该段的旧候选现在会被明确拒绝（不会静默用已安装 payload 试跑）。
- 设备：安装新 APK 前记录原 APK 路径与 SHA-256（见上），可用同 key 重新构建 0.2.4 回滚。

## 10. 例外与监控

- `app/.../DshCapabilityAssembly.kt` 曾达 813 行（>800 硬上限）。已把终端 lane 与 workspace-tree
  owner 的构造抽到 `app/.../DshCapabilityCollaborators.kt`（仅构造、不持状态、不藏策略），
  assembly 回到 781 行，无需例外。若该文件再次逼近 800，先抽下一个协作者，不登记长期例外。

### 8.2 2026-10-29 设备实测（第二轮：升级真实提交，生产切到已提交闭包）

- APK 链（每步都核对设备 `base.apk` 哈希等于构建产物）：
  - 修复前基线 `f3f4e93c…`（§8.1 的 f3f4e93c，仅作对照）；
  - `d9469214…`（stop 诊断构建）、`71a7a0ec…`（unbound-stop 修复）、`24515ccb…`（闭包生产切换第一版）、
    `5da36498…`（restart 携带闭包 + 每次启动重新准入，**当前安装**）。
  - 当前设备路径 `/data/app/~~cM7kMS3PMxebGmukwp1glQ==/com.muyuchat.mca-sUDk-40b4-eeaaFbKB9SLA==/base.apk`。
- 缺陷 3（真实根因，已修）：`DshRuntimeServiceClient.stop()` 在**未绑定**（上一次 stop 已 `unbind`）时跳过
  owner 往返，`DshStopSnapshotPolicy` 把“无回复”直接判为 `FAILED/STOP_TIMEOUT`。维护层把 FAILED 视为
  “未确认停止”，升级事务在 ~10 s 内以 `HOST_STILL_RUNNING` 回滚。设备日志（诊断构建）：
  `stop-and-confirm begin phase=STOPPED epoch=5 timeout=30000` → `end phase=FAILED failure=STOP_TIMEOUT elapsed=0ms`。
  修复：stop 在无绑定时先 `messengerOrBind()` 取得**新**的 owner 回复；拒绝把未绑定当 STOPPED 的安全规则不变。
- 缺陷 4（真实缺口，已补）：提交后 selector 只被 maintenance 读写，runtime 从不读取；
  `startProduction(activeRoot)` 的 root 参数被忽略 → 生产仍从安装包 payload 启动（描述符式提交）。实现：
  - 契约 `DshRuntimeClient.ensureStartedFrom(File?)`（null=安装 payload；默认实现返回类型化失败，禁止假装启动）；
  - IPC `KEY_APPLY_PAYLOAD_ROOT`（null 无指令 / `""` 回安装包 / 绝对路径=闭包）；
  - 服务侧 `DshClosurePayloadAdmission`（纯 JVM 类）：闭包存储包含关系、`candidate.json` 的 `payload.root`、
    关键文件 sha256、kernel entry；owner 在**每次 generation 启动**（含 restart）再准入一次；
  - `DshRuntimeOwner` 以 `activeClosurePayload` 覆盖生产 profile/environment 的 payload 根；
  - 维护 `startProduction` 传 `closure/incoming/<id>/payload`；工作区启动与 `restart()` 读取持久 selector 传闭包。
- 设备验证（当前安装 `5da36498…`）：
  - 提交事务 **COMMITTED**：`17:05:13` 第一轮（`d9469214…` 诊断 APK）与 `18:06:58` 第二轮（`24515ccb…`）；
    trial 实际加载候选：`dsh: trial: launching … /files/dsh/closure/incoming/v6/payload/dshroot/…/bin.js`，
    `trial: RUNNING pid=21301`，随后 `trial: released (SIGTERM)`；提交后生产同样从
    `…/closure/incoming/v6/payload/dshroot/…` 启动并 `READY epoch=11`。
  - 二次提交写入 `previousFingerprint=previousRoot=9f9e6bcc…/incoming/v6`（身份可核对）。
  - 应用重启后的工作区启动路径同样使用闭包：`18:05:12 launch …closure/incoming/v6/payload…`，`READY epoch=9`。
  - 篡改对照：在候选 `dshhome/profiles/web/package.json` 追加 8 字节后重启 → 未出现任何 `dsh: launching`
    （服务已创建但拒绝启动，网关随即 teardown）；从本地副本恢复后（设备 sha256 回到
    `b07f71cba5c341f6bd6ebf1316573588817e3a15629a86f11d34d4a01a82c94b`，与 candidate.json 记录一致）
    点“重新启动”即从闭包 `launching …` 并 `READY epoch=13`。诚实边界：篡改时 UI 展示的前置错误码是
    `PROCESS_EXITED`（启动恢复拦截），闭包准入拒绝在服务侧发生（无 node 启动），其 `PAYLOAD_INTEGRITY`
    尚未在界面上单独取证。
- JVM 测试：`DshClosurePayloadAdmissionTest` 5/5 PASS；聚合单测 `:core:dsh-contract:testDebugUnitTest` 34/0、`:core:dsh-maintenance:testDebugUnitTest` 130/0、`:core:dsh-runtime:testDebugUnitTest` 504/0（共 668 项，0 失败，`BUILD SUCCESSFUL`，日志 `/tmp/agg-tests-closure.log`）。
- 测试脚手架声明：为复跑提交路径，主动删除过 `files/dsh/maintenance/upgrade.json`（selector 保留）；
  篡改/恢复只作用于 `incoming/v6` 的 1 个关键文件，恢复后哈希与候选记录一致。
- 内容不同的双版本升级（2026-10-29 18:54，最终 APK `5da36498…`）：
  - 候选改为**内容不同**版本：`dshhome/profiles/web/package.json` 设备 sha256 由 `b07f71cb…` 变为
    `7f88cff8…`（新增 `_mcaClosureMarker`），`candidate.json` 同步记录该哈希；安装 payload 仍为
    `b07f71cb…`，即活动闭包内容 ≠ 安装 payload。
  - 升级事务 `18:54:45` 确认 → `18:55:01` **COMMITTED**：新指纹
    `077d91784530ce9aee7de681e97fcb84a444f2c6902e4f9c8cc5ad400f3f235a`，previous
    `9f9e6bcca0e6eac980755d3d72293e4d78e1fa30a3b228acdcfb7f5e3c92d19d`。
  - trial 与提交后生产均从 `…/closure/incoming/v6/payload/dshroot/…/bin.js` 启动（trial RUNNING pid=6507
    后 SIGTERM 释放；生产 `READY epoch=17`）。selector 记录新指纹/lastGood/installed 身份（archive `97514651…`）。
  - 诚实边界：这是**合成**内容差异（marker 版本，不是官方 DSH 新版本）；且为在已提交树上就地修改后重新提交，
    因此 previous 指纹描述的是修改前身份，回滚到该 previous 需配合真实归档。
- 缺陷 5（回滚未切回，已修）：健康检查失败时旧实现只回退 selector，生产可能继续运行失败闭包，且首次提交
  （无 previous）只能报 RECOVERY_REQUIRED。现在 `ClosureUpgrade` 的失败分支按顺序执行：停掉候选 generation
  （未确认 STOPPED → RECOVERY_REQUIRED）→ 回退 selector（无 previous 则删除 selector，回到安装 payload）→
  启动上一闭包/安装 payload 并做只读健康检查；任一步不满足即 RECOVERY_REQUIRED，绝不发布 ROLLED_BACK。
  resume 的 HEALTH_PENDING 失败路径同样处理。单测：`UpgradeTest` 新增/升级 4 个用例
  （候选不健康→上一闭包恢复健康并 ROLLED_BACK；首次提交失败→selector 清空并恢复安装 payload；上一闭包
  恢复后仍不健康→RECOVERY_REQUIRED；HEALTH_PENDING 崩溃后 resume→候选不健康时同样恢复上一闭包并 ROLLED_BACK）；
  `:core:dsh-maintenance:testDebugUnitTest` 133/0 PASS。
  诚实边界：本项为 JVM 覆盖（fake lifecycle port），**设备上未制造真实健康失败**（NOT_RUN）。
- 仍未闭环（不得声称升级能力闭环）：
  1) 尚无**官方** DSH 新版本候选（本轮内容差异为合成 marker）；
  3) 生产 home 模板/skills seeding 仍取安装 payload（数据半边未随闭包切换）；
  4) 服务内部 `MSG_RESTART`（非 App 包装客户端）不带 payload 指令；
  5) 篡改时的界面错误码未区分为 `PAYLOAD_INTEGRITY`。

### 8.3 2026-10-29 设备实测（第三轮：能力桥直连探针；随后设备掉线）

- 对象：仍在安装的 `5da36498…`（§8.2 末尾构建）。探针命令（`run-as` 读取 `/proc/<pid>/environ`
  中的 `APP_LOCAL_TOKEN`，以 `x-dsh-token`/`x-dsh-generation` 调 loopback 能力桥）：
  `adb shell "run-as com.muyuchat.mca sh /data/local/tmp/mca-shell-probe.sh 34799 2"`。
  端点 `127.0.0.1:34799` 取自 node 进程的 `APP_NOTIFY_PORT`；`x-dsh-generation: 17` 被拒
  `{"ok":false,"code":"STALE_GENERATION","status":409}`，能力代数实际为 `2`。
- `/terminal/list` → `{"ok":true,"sessions":[]}`（干净起点）。
- `/terminal/open {"type":"shell","cols":80,"rows":24}` → `sessionId=term-1`、`transport="REAL_PTY"`、
  `realPty=true`、`pid=24862`、且**首包即** `"status":"EXITED","exitCode":127`。
  `/terminal/write` + `/terminal/read` 只得到空文本与同一 EXITED 状态；
  `/terminal/close` → `{"ok":true,"settled":true,"escalated":false,"detail":"SIGTERM; terminal session empty"}`。
  结论：真实 PTY 通道可用（native open 成功、有 pid、可关闭），但**该 payload 下 shell 进程 exec 失败**，
  因此“可通过 PTY 执行命令”在本轮为 NOT_RUN，不是 PASS。
- `/toolchain/verify` → `probes=9/10 failed=BASH:SPAWN_FAILED`；逐项真实版本：
  NODE v26.4.0、NPM 11.20.0、NPX 11.20.0、PNPM 10.18.3、PIP 26.1.2（python 3.14）、PYTHON 3.14.6、
  RIPGREP 15.2.0、CURL 8.21.0、GIT 2.55.0（以上 9 项 PASS）；
  BASH `gnuBash=false`，`missingGnuFeatures=[arrays,brace-expansion,process-substitution,nameref,shopt,bash-version]`；
  `missingDependencies=[bash wrapper, git launcher, rg launcher, curl launcher]`（PATH 启动名缺失，
  与直接 exec nativeLibraryDir ELF 可用并不矛盾；报告按这两类事实分列）。
- 根因与修复（JVM 已测，设备复测 NOT_RUN）：`DshCommandClosure` 仅凭 `libdshcmd.so` 存在就断言
  `bin/bash` 是可执行的 dispatcher 链接；闭包 payload 实际没有该名字时，PTY 子进程 exec 失败即 127。
  现在要求 `payloadFile("bin/bash")` 为真才使用 dispatcher，否则退回 `/system/bin/sh`（剥离 bash 专属
  参数、`gnuBash=false`）。用例：`DshCommandClosureTest.aDispatcherWithoutThePayloadBashNameFallsBackToThePlatformShell`。
- 设备掉线：此后 `adb devices` 为空、Windows 侧无 Android USB 设备（20:38 前后），
  因此回滚构建（缺陷 5）与新闭包 shell 回退的**设备复测均标 NOT_RUN**；重连后需重装并复跑
  启动冒烟 + 一次闭包升级 + PTY 命令执行三件事。

### 8.4 2026-10-29 收尾：失败码不被断连覆盖（JVM 已测；设备 NOT_RUN）

- 缺陷 6（界面错误码，已修）：服务断连时 `DshRuntimeServiceClient.onServiceDisconnected/onBindingDied`
  无条件 `publishFailure(PROCESS_EXITED)`，会把 owner 刚发布的 `PAYLOAD_INTEGRITY` /
  `STARTUP_RECOVERY_REQUIRED` 覆盖成“运行时已退出”，用户看到的是通用崩溃 + 重试，而不是真实原因。
  新增纯策略 `DshDisconnectFailurePolicy.resolve(current, message)`：当前快照已是 FAILED 且不是
  `PROCESS_EXITED` 时保留原失败（同一实例，不产生新状态）；READY/STOPPED/已 PROCESS_EXITED 仍按断连
  写 `PROCESS_EXITED` 并保留 epoch。用例 `DshDisconnectFailurePolicyTest`（5 例：READY→退出、
  闭包完整性拒绝保留、启动恢复拒绝保留、STOPPED→退出、已有退出刷新消息）。
- 诚实边界：这是链路层覆盖规则（JVM 证明）；设备上“篡改→UI 显示运行组件校验失败”的端到端取证
  仍为 NOT_RUN（设备不在线）。
- 待构建：包含缺陷 5（回滚切回）与本轮 shell 回退 / 失败码保留的 APK 尚未产出（并行车道 Gradle 占用中，
  构建排队；产出后记录 SHA-256 并在设备重连时安装复测）。

**§8.4 定向测试（本机，2026-10-29 20:58）**：
`./gradlew :core:dsh-runtime:testDebugUnitTest --tests '…process.DshCommandClosureTest' --tests '…process.DshShellCompatibilityTest' --tests '…state.DshDisconnectFailurePolicyTest' --tests '…terminal.*' -I /tmp/aliyun.init.gradle.kts -PmcaQnnSdkRoot=E:/model/qairt-sdk/qairt/2.45.0.260326 -Pmca.abis=arm64-v8a --no-daemon --max-workers=1 --no-parallel --console=plain`
→ **BUILD SUCCESSFUL，54 例 / 0 失败 / 0 错误 / 0 跳过**（`DshCommandClosureTest` 17、`DshShellCompatibilityTest` 6、
`DshDisconnectFailurePolicyTest` 5、`DshPipeTerminalTest` 3、`DshTerminalFactoryTest` 11、`DshTerminalSessionTest` 12；
XML 位于 `core/dsh-runtime/build/test-results/testDebugUnitTest/`）。

### 8.5 2026-10-29 21:09 设备重新上线（基线已变）

- 设备重新连上：`f9d98aa3`，型号 `2304FPN6DC`（ishtar），ABI arm64-v8a。
- **安装基线不是 §8.2 的诊断构建**：当前 `app_path=/data/app/~~vFEKl3pyD-QnTLnIVvFwVg==/com.muyuchat.mca-WT9LfooC2iAUldCOgEhXKw==/base.apk`，
  SHA-256 `db542f6bc4c6f947199185fbf2563cad19eb235c0c736b63464d5c85bd3af5b1`，`versionName=0.2.4`、`versionCode=8`，
  **release 非 debuggable**（`run-as` 被拒，无法读取 `files/dsh/closure/`，无探针数据）。
  因此 §8.3 的闭包/探针现场不在本机；本轮的诊断 APK 安装前基线与回滚点 = 该 `db542f6b…`。
- 已修但尚未被本设备验证的三项：闭包健康失败回滚（缺陷 5）、闭包 payload 缺 `bin/bash` 时的平台 shell 回退（缺陷 6 前置）、
  断连不覆盖 `PAYLOAD_INTEGRITY`（缺陷 6）。诊断 APK 构建中（并行车道亦在构建，产物最终以安装前复核的 SHA 为准）。

### 8.6 2026-10-29 21:43–21:48 设备实测（第四轮：新 APK 安装 + PTY 命令真实执行 + 工具链 10/10）

- APK 身份链（全部 SHA-256 实测）：
  - 安装前基线：`db542f6b…`（versionName 0.2.4/vc8，`application-debuggable`，debug 证书 `ad116923…`）。
    已拉回 `D:\mca-archive\dsh-round-20261029\mca-0.2.4-release-baseline-20261029.apk`（570,526,852 B）。
  - 构建产物（并行车道同一工作树 21:38 的 `:app:assembleDiagnostic`，universal arm64+x86_64，release 证书
    `2619ac4c…`）：`2930664b…`，593,635,945 B，归档 `app-diag-r4-releasekey.apk`。签名链不匹配（基线是 debug 证书），
    直接 `adb install -r` 必失败；因此用本机 debug keystore（证书 `ad116923…` = 基线证书）**重签**为
    `app-diag-r4-debugkey.apk`（`c52b2f0a…`）后安装成功（`adb install -r -d`，Incremental Install）。
  - 安装后设备 `base.apk`：`/data/app/~~OIWmT9GSO-MU3fZMVl922g==/com.muyuchat.mca-tS_chlUPuDqd_TGS2NKZDA==/base.apk`
    = `c52b2f0a…`，versionName `0.2.4-diag`，vc8。**代码与 2930664b… 相同，仅签名不同**。
- DSH 启动（设备）：从历史抽屉“打开 DSH 工作区”→ 官方页面 `DeepSeek Harness`（预览版说明）加载；
  `:dsh` 服务 pid 23338、`libdshnode.so` pid 23432；node env：`DSH_PAYLOAD_DIR=…/files/dsh/payload`
  （本安装无闭包 selector，走安装 payload）、`MCA_DSH_GENERATION=1`、能力桥端口 42349。
- 能力桥探针（PC 侧 `adb forward tcp:42349 tcp:42349` + `x-dsh-token`/`x-dsh-generation: 1`）：
  - `/terminal/list` → `{"ok":true,"sessions":[]}`。
  - `/terminal/open {"type":"shell","cols":80,"rows":24}` → **`status:"RUNNING"`**、`realPty:true`、`transport:"REAL_PTY"`、
    pid 23697、motd `:/data/data/com.muyuchat.mca/files/dsh/workspace $ `。
    对照 §8.3 同一调用在旧闭包 payload 上首包即 `EXITED/127` —— 说明 shell 启动路径在本构建可用。
  - `/terminal/write {"text":"printf dsh-pty-ok; uname -m; echo\n"}` → `{"ok":true,"bytes":35}`；
    `/terminal/read` → **`dsh-pty-ok` + `aarch64`**，提示符回显、`status:"RUNNING"`。
    **PTY 内真实命令执行 = PASS（设备）**，§8.3 的 NOT_RUN 项在本构建关闭。
  - `/toolchain/verify` → **`ok:true`, `usable:true`, 10/10 PASSED, missingDependencies=[]**：
    NODE 26.4.0(DIRECT)、NPM 11.20.0(NODE_SCRIPT, PATH=dispatcher)、NPX 11.20.0、PNPM 10.18.3、
    PIP 26.1.2(PYTHON_MODULE)、PYTHON 3.14.6(DIRECT)、RIPGREP 15.2.0、CURL 8.21.0、GIT 2.55.0、
    BASH(DISPATCHER, `gnuBash=false`, out=`dsh-toolchain-ok`)。
- 诚实边界：
  1) 本安装 payload **含 `bin/bash` 启动名**，所以 BASH 走 dispatcher；§8.3 的 127 根因（闭包含 libdshcmd 但无
     `bin/bash`）所对应的**平台 shell 回退分支**只有 JVM 用例覆盖，设备未复现该分支本身；
     该断言基于“解析器不再把缺失链接当可执行”这一纯逻辑测试，不冒充设备 PASS。
  2) 本轮设备**没有闭包候选**（数据目录无 `closure/`），因此升级提交/健康失败回滚（缺陷 5）仍未在设备复测（NOT_RUN）。
  3) APK 由并行车道构建（我的队列为避免 RAM 换页被停止），身份以上述 SHA 为准；如需可复现构建，设备空闲时应独立重建。
- 新安装基线即 `c52b2f0a…`；回滚到旧版可用已归档的 `db542f6b…`（同 debug 证书，可覆盖安装）。

**聚合回归（2026-10-29 21:50，本机）**：`:core:dsh-runtime:testDebugUnitTest` 全模块
**68 个测试类 / 511 例 / 0 失败 / 0 错误 / 4 跳过（既有 @Ignore）**，`BUILD SUCCESSFUL in 1m 39s`
（日志 `/tmp/dsh-runtime-full.log`，XML `core/dsh-runtime/build/test-results/testDebugUnitTest/`）。
相对上一轮 504 例，新增 7 例：`DshDisconnectFailurePolicyTest` 5、`DshCommandClosureTest` 1（平台 shell 回退）、
`DshShellCompatibilityTest` 1（缺 bin/bash 时不宣称 dispatcher）。

### 8.7 仍未闭环（不得声称升级能力闭环）

1. **官方 DSH 新版本候选**：仍无（内容差异为合成 marker，§8.2 已在旧设备完成提交，§8.8 的 v8 候选已推入本设备待执行）。
2. **健康失败回滚（缺陷 5）**：JVM 133/0 覆盖（停候选→回退 selector→启上一闭包/安装 payload→健康检查），
   设备上未制造真实健康失败，NOT_RUN。
3. **数据半边**：生产 home 模板/skills seeding 仍取安装 payload。
4. **服务内部 `MSG_RESTART`**：不带 payload 指令；当前所有 App 侧 restart/ensure 路径都带闭包，
   `START_NOT_STICKY` 使其仅在活进程内生效（风险窄，未改）。
5. **MCP resources**：`resources/list`/`read`、分页、session 过期、重连、取消、真实子进程环境仍只有静态/契约证据
   （§3），需设备 Node 实测；`prompts` 明确 BLOCKED（payload 无该插件）。
6. **SAF tree**：系统选择器、重启后持久化、provider 卸载的设备 instrumentation NOT_RUN；官方 payload 尚未注册调用这些路由的 Node 工具。
7. **平台 shell 回退的设备分支**：本安装 payload 含 `bin/bash`，回退分支只有 JVM 证明。

### 8.8 2026-10-30 00:31–00:45 设备候选就位，升级被锁屏阻断（NOT_RUN）

- 候选来源：**生产 stager**（`DshCandidateStager` + `DshPayloadArchiveSpec`，非 descriptor、非测试桩），
  PC 侧 Java 直调同一份 release 编译产物，输入为当前安装 APK 自带的 `assets/dsh/payload.zip` 与
  `assets/dsh/MANIFEST.json`（`app-diag-r4-releasekey.apk`，SHA `2930664b…`，与安装包代码一致）。
  - 环境事实：staging 在 **D: 盘**上 `moveDurably` 无法完成（`IO_FAILURE: could not be promoted atomically`，
    同一代码在 **C: 临时目录**一次成功）——判为 Windows/该盘的环境问题，不做产品代码改动。
  - 产物：`candidateId=v8`，fingerprint `0bf6f2014a3fb96adfcec49c953d889719ec70457b63255fc3528f14fa157821`，
    archive sha `975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab`，10 组件、
    31,021 个文件；`candidate.json` 本地 sha256 `067fc29a07a19f725b9c612bd6b77b3a254e5546341ec40464a62fc65a1f3181`。
  - 内容不同于安装 payload：`payload/dshhome/profiles/web/package.json` 加入 `_mcaClosureMarker`
    （sha256 `b07f71cb…` → `bbe5dd3c57030a47dabfc174b790574c091be2be0ed3993e1ca97fc0b9b0d1ef`），
    `candidate.json` 的该关键文件哈希同步更新（不是只改描述符）。
- 设备落盘（run-as 可核对）：
  - `files/dsh/closure/incoming/v8/candidate.json` sha256 = `067fc29a…`（与本地一致）；
  - `files/dsh/closure/incoming/v8/payload/dshhome/profiles/web/package.json` = `bbe5dd3c…`；
  - 安装 payload 同名文件 = `b07f71cb…`（活动候选内容 ≠ 安装 payload，可证）；
  - `find …/v8/payload -type f | wc -l` = **31,021**（与 `candidate.json` 的 `fileCount` 一致）。
  - 传输：`tar -czf` 137,711,744 B → `adb push` → `run-as … gunzip -c | tar -xf -`（12.7 s）。
- **升级事务未执行（NOT_RUN）**：设备在 00:44 处于重启后的数字密码锁屏（“手机重启后，第一次需要输入密码”），
  控制台只能通过界面触发（源码中没有 Local API/能力桥/maintenance 路由可替代），
  拒绝猜密码或绕过锁屏，因此本轮不做“运行整闭包升级”。
  解锁后的执行顺序（候选已就位，无需再推）：打开 DSH 工作区 → 悬浮球 → 控制台（应显示“候选版本 v8”）
  → 运行整闭包升级 → 确认执行；观察 `trial: launching …/incoming/v8/payload/dshroot/…`、
  `COMMITTED`、selector 指纹 = `0bf6f201…`、生产 `launching …/incoming/v8/payload/…`。
- 同轮复核（代码层面，非新证据）：`startupAdmission()` 是工作区启动的唯一 `beforeStart` 闸门
  （`DshHostAssembly` 注入 `DshModeAssembly`），sweep 抛异常被转成类型化
  `STARTUP_RECOVERY_REQUIRED(retryable=true)`，Blocked 不会被四舍五入为“完成”；
  维护事务内部的生产启动走自身 `startProduction`（提交/回滚路径），不递归过闸。

**聚合回归（2026-10-30 00:47–00:56，本机，Gradle 串行）**：`:core:dsh-contract:testDebugUnitTest` **34/0**、
`:core:dsh-maintenance:testDebugUnitTest` **133/0（1 跳过）**、`:api:dsh-gateway:testDebugUnitTest` **236/0**、
`:feature:dsh-workspace:testDebugUnitTest` **228/0**，均 `EXIT_*=0`（日志 `/tmp/agg-tests-r5.log`）；
与同轮 `:core:dsh-runtime` **511/0（4 跳过）** 合计 **1,142 例 / 0 失败**。

### 8.9 2026-10-30 01:15 设备复跑暴露真实缺陷：启动失败被误标为 PAYLOAD_INTEGRITY（已修，JVM 测试）

- 环境事实：设备上有一个 **他人/用户的活跃视频任务** `sd-cli`（Wan2.2、`/data/local/tmp/hy1`、PSS 10.7 GB），
  设备 14.2/14.8 GB 内存占用、LMK 连续杀进程。01:15 复跑 DSH：
  - 00:52 那次：`:dsh` 正常验证 payload（`payload … reused archive=975146516216`）、拉起 node
    （`NODE_PROCESS_ALIVE epoch=2`），0.3 s 后 `libprocessgroup: kill: send 9 to pid -16498`（系统/MIUI 杀进程组）。
  - 01:15 重试：界面显示 **`DSH 运行组件校验失败` / `错误代码：PAYLOAD_INTEGRITY`**（“请更新或重新安装 MCA”）。
    但安装 payload 未被改动（同名文件哈希仍是 `b07f71cb…`，未升级、未篡改）——这是**误报**。
- 根因（我此前引入）：`DshRuntimeServiceClient.ensureStartedFrom()` 把任何非 APPLIED/UNCHANGED 的 apply 结果
  一律改写成 `PAYLOAD_INTEGRITY(retryable=false)`。宿主被系统杀掉/apply 超时的真实类型是
  `NODE_START_FAILED(retryable=true)`，结果变成“组件损坏、请重装”的不可重试错误，也丢掉了重试路径。
- 修复：新增纯策略 `DshStartFailureProjection.resolve(result, closureRequested)`——
  只有 **FAILED 且回复中没有 owner 失败** 且 **本次请求的是闭包**时才投影为 `PAYLOAD_INTEGRITY`；
  其余 FAILED 保留 owner/客户端已有的类型化失败（如 `NODE_START_FAILED`，可重试）；
  带 owner 失败的 FAILED 原样返回；DEFERRED/ROLLED_BACK 不伪装成失败。
  用例 `DshStartFailureProjectionTest`（5 例：被杀宿主保留 NODE_START_FAILED、安装 payload 的失败→INTERNAL 可重试、
  闭包拒绝→PAYLOAD_INTEGRITY 不可重试、APPLIED 原样、DEFERRED 原样）。
- 诚实边界：此缺陷的**设备证据**是旧构建（`c52b2f0a…`）上的误报；修复后的 APK 尚未重建，
  因此“设备上不再误报”仍为 NOT_RUN；升级事务仍被内存压力阻断（候选 v8 已就位，见 §8.8）。

**§8.9 修复的单测（本机 01:21）**：`:core:dsh-runtime:testDebugUnitTest --tests 'com.muyuchat.core.dsh.runtime.state.*'`
→ **BUILD SUCCESSFUL，8 类 / 49 例 / 0 失败**（含新 `DshStartFailureProjectionTest` 5 例；
日志 `/tmp/proj-tests2.log`）。修复未进入 APK：`c52b2f0a…` 仍是旧构建，设备复测 NOT_RUN。

### 3.1 pinned payload 的 MCP 能力范围（静态源码审计，2026-10-30；真实执行仍 NOT_RUN）

审计对象：候选 v8 的 payload（与安装 payload 同一 archive `97514651…`）下
`dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/`。

- `dsh-mcp-client/lib/index.js`：
  - `resources/list`（带 `cursor`）、`resources/templates/list`（带 `cursor`）、`resources/read`（按 `uri`）均转发到当前连接代际（L693–L695）；
  - 连接监督器持有**代际**并在断连时重启，重连有上限 `maxAttempts`，放弃时注销工具并停止；上一代未确认关闭时**拒绝并发重启**以避免两个子进程并存（L418–L580 注释与实现）；
  - 工具执行透传 `execution.signal`（L144），图片落盘前后都有取消检查（L300）；
  - stdio 子进程环境 = `buildChildEnv(config.env)`（L26–L45），其底层 `scrubbedParentEnv()`
    （`dsh-subprocess/lib/index.js` L50–L56）会剔除所有匹配 `SENSITIVE_ENV_PATTERN` 的键**以及所有 `DSH_` 前缀键**，
    再叠加插件声明的 env → 子进程拿不到宿主 DSH token。
- `dsh-mcp-resources/lib/index.js`：注册三个共享工具，分别调用 `resources/list`、`resources/templates/list`、
  `resources/read`（L61/L71/L88），二进制结果只以占位文本进入模型历史（`renderResourceResult`）。
- `prompts`：两个包里**不存在** `prompts/list|get` 的调用或注册（全文检索无命中）→ 与 §3「prompts BLOCKED（需 payload 升级）」一致。
- MCA 侧对应约束已有 JVM 证据：capabilities schema v2、`prompts` 一律拒绝、stdio 环境拒绝重引入 `DSH_*`、
  工具目录上限 `DSH_MAX_MODEL_VISIBLE_TOOLS=128`（§3，网关 236/0）。

**诚实边界**：以上是 pinned payload 的**静态能力范围审计**，不是设备/Node 实跑证据；
`resources/list`/`read`、分页、session 过期、重连、取消与真实子进程环境的端到端执行仍 NOT_RUN（设备锁屏中）。

### 8.10 2026-10-30 01:42–01:46 本设备完成 v8 双版本升级（COMMITTED）与跨进程重启验证

设备 `f9d98aa3`（安装包 `c52b2f0a…`，此前无闭包）。候选来自 §8.8 的生产 stager 产物 v8
（`candidate.json` 设备 sha256 `067fc29a…`，31,021 文件，marker `bbe5dd3c…` ≠ 安装 payload `b07f71cb…`）。

- 控制台识别 `候选版本 v8`；停止宿主（`运行阶段=已停止`，日志 `stopped (SIGTERM)` /
  `stop proved no host and no payload orphans`）后确认执行升级：
  - trial：`trial: launching …/closure/incoming/v8/payload/dshroot/…/bin.js` →
    `NODE_PROCESS_ALIVE epoch=4` → `PORT_LISTENING / HTTP_AUTHENTICATED / PLUGINS_LOADED` →
    `trial: RUNNING generation=1 pid=29102 origin=http://127.0.0.1:40623 (non-publishing)` →
    `trial: released (SIGTERM)`。
  - 生产切换到候选树：`launching …/closure/incoming/v8/payload/dshroot/…/bin.js` →
    `READY epoch=5 origin=http://127.0.0.1:37087 binding=b-fcaac0c75e182d52
    (gateway=http://127.0.0.1:38705/v1 catalog=mca-88512191c96d1d47 capabilityGeneration=2 hostBuild=mca-app)`
    → `FIRST_FRAME_RENDERED epoch=5`。
  - `upgrade.json`：`{"phase":"COMMITTED","candidateId":"v8",fingerprint "05e17f7f…", previousFingerprint "",
    previousDataRoot "", previousDataSchemaVersion 0}`。
  - `selector.json`：`root=…/closure/incoming/v8`、`lastGoodRoot` 同值、
    `installedArchiveSha256=97514651…`、`installedPayloadVersion=dsh-0.2.1-rc.2+android-ref-c324272bff00`、
    `previousFingerprint=""`（首次提交）。
  - 指纹口径说明：设备提交身份为 `05e17f7f…`；PC stager 生成时打印的 `0bf6f201…` 是**打 marker 之前**的身份，
    `candidate.json` 的 payload 段（关键文件哈希）参与身份计算，故以设备/selector 的值为准。
- 跨进程重启（selector 为权威）：`am force-stop com.muyuchat.mca` → 重新打开 → DSH 工作区，
  日志 `launching …/closure/incoming/v8/payload/…` → `READY epoch=6 origin=http://127.0.0.1:42461`。
- 新发现（真实缺口，已修，JVM 测试进行中）：提交后活动闭包仍留在 `closure/incoming/`，再放一个新候选时
  `DshClosureCandidateSource` 视作“多于一个候选”而拒绝（设备上构造 v9 后控制台不再给出可升级候选）。
  修复：`DshClosureCandidateSource`/`DshCandidateStager` 增加 `activeRoot` 提供者，把 selector 当前服务的树
  排除出“已暂存候选”集合；App 侧以 `owner.upgrade.selector()?.root` 注入；新增用例
  `theActiveClosureIsNotCountedAsASecondStagedCandidate`。设备验证需新 APK（NOT_RUN）。
- 设备现场：v9 实验目录已删除（`incoming/` 仅剩 v8），生产宿主当前为“已停止”（升级演练前的停止操作）；
  建议用户界面点“重新启动运行时”恢复。

**§8.10 配套单测（本机 01:53）**：`:core:dsh-maintenance:testDebugUnitTest` → **BUILD SUCCESSFUL，
15 类 / 136 例 / 0 失败 / 1 跳过**（较上轮 +3：`resumeWithAnInterruptedTrialRollsBackOnlyAfterTheTrialIsConfirmedStopped`、
`resumeWithATrialThatWillNotStopBecomesRecoveryRequired`、`theActiveClosureIsNotCountedAsASecondStagedCandidate`；
日志 `/tmp/maint-tests-r6.log`）。
代码改动：`ClosureUpgrade.resumeFlow` 的 TRIAL_RUNNING/TRIAL_PASSED/VERIFIED 分支现在先确认 trial 已 STOPPED，
未确认则 `RECOVERY_REQUIRED`（不再把仍在运行的 trial 报成 ROLLED_BACK）；`DshClosureCandidateSource`/`DshCandidateStager`
新增 `activeRoot` 提供者，把 selector 服务的闭包排除出“已暂存候选”。
设备状态：v8 闭包为活动版本，`重新启动运行时` 后 `READY epoch=7 origin=http://127.0.0.1:42343`；
本修复进入 APK 需重建（E: 1.3 GB 不足），设备复测 NOT_RUN。

### 8.11 2026-10-30 01:51–01:57 设备后台杀进程与 SAF 演练未执行

- 01:51 `重新启动运行时` 成功：从 v8 闭包 `launching …/incoming/v8/payload/…` → `READY epoch=7`。
- 之后 `:dsh` 服务与 `libdshnode.so` 被系统回收（仅剩 app 进程），再次点“重新启动运行时/重开工作区”在本轮未见
  `launching` 日志、`pidof libdshnode` 为空——设备处于后台限制状态，运行时常不可用。
- 计划中的 **SAF tree 设备演练未执行（NOT_RUN）**：`/workspace/tree/{status,request,release}` 已注册（`DshWorkspaceTreeGrantRoutes`，
  GET status / POST request(?requestId,write,reason) / POST release，请求返回 `pending=true` 后轮询 status），
  但要驱动系统选择器需要有响应的 DSH 运行时 + 可点击界面；本轮运行时被系统杀掉，未继续。
  该项仍只有 JVM 47 例与路由契约证据（§4），设备选目录/重启持久化/撤销未取证。
- 设备留存状态：`selector.json` → v8（已 COMMIT），`incoming/` 仅 v8；运行时进程当前不存在（系统回收），
  下次用户打开 DSH 工作区会从 v8 闭包启动。

### 8.12 2026-10-30 02:00 设备 SAF tree 授权演练（未取得 GRANTED，NOT_RUN）

运行时从 v8 闭包 `READY epoch=8`（capabilityGeneration=1，桥端口 42685）。通过 `adb forward` 直连能力桥：

1. `GET /workspace/tree/status` → `{"ok":true,"state":"NOT_GRANTED","pending":false,
   "access":"PERSISTENT_TREE_GRANT","persistentTreeGrant":false}`（基线正确）。
2. `POST /workspace/tree/status` 的鉴权：`x-dsh-generation: 8`（运行代次）→ `STALE_GENERATION`；
   必须用 **capabilityGeneration=1**（桥的代数与 runtime epoch 不同）——记录为运维事实。
3. `POST /workspace/tree/request?requestId=req-saf-acceptance-0001&write=1&reason=acceptance`
   - 首次 `requestId=req-saf-1` → `INVALID_REQUEST`（契约要求 16–64 个 URL-safe 字符）；
   - 合法 id → `{"ok":true,"state":"NOT_GRANTED","pending":true,...}`，**系统目录选择器确实弹出**
     （DocumentsUI 显示目录，含 `使用此文件夹` 与 MIUI 的“要允许MCA访问 … 中的文件吗？允许/取消”）。
4. 两次按选择器允许后，`status` 仍为 `NOT_GRANTED`，`detail="the workspace folder picker was dismissed"`，
   `pending=false`；未观察到 `persistedTreeGrant=true`。
- 结论：本轮**未取得 GRANTED**，因此撤销/失效/provider 不可用三项也无法继续验证 → 全部 NOT_RUN。
  尚不能区分“MIUI 文件选择器的返回未送达 MCA 的 ActivityResult 管线”与“自动化点按没有真正触发允许”
  （两次点按坐标都落在 `允许` 按钮 bounds 内，但对话框随后以 dismissed 结束）；需要人工在界面上完成一次授权再判定。
- 诚实边界：这**不是** PASS，也不把“选择器弹出”当作授权成功；§4 的 JVM 47 例与路由契约证据仍成立。

**§8.12 根因补充与修复（代码审查 02:03）**：`DshWorkspaceTreePickerContract.parseResult` 在用
`intent.flags` 判定 `FLAG_GRANT_READ_URI_PERMISSION`，缺失即映射为 `Cancelled`。而 MIUI/AOSP 的
`ACTION_OPEN_DOCUMENT_TREE` 结果**可能不回显**该 flag（真正的授权由 `takePersistableUriPermission`
发布），于是“用户已允许”被记录成 `the workspace folder picker was dismissed`——与设备现象一致。
修复：结果只要求 `RESULT_OK`+`data`，read/write 模式仍从 flags 读取且**绝不升级**写权限，交给
owner 的 `takePersistable`/`isUsableSelection` 复核；无真实授权时会得到 `NOT_PERSISTED`/`INVALID_SELECTION`，
不再伪装成“取消”。设备复测需重建 APK（E: 2.7 GB，本轮仅启动 `:app:compileDebugKotlin` 验证编译）。

**§8.12 复核（新构建，02:19–02:23）**：诊断 APK 重建并安装成功——`app-diagnostic.apk` 470,587,814 B
（arm64-only，`compileDiagnosticKotlin` BUILD SUCCESSFUL 4m26s、`assembleDiagnostic` EXIT=0），release 证书 `c38e8495…`，
debug 重签后 `cb50a92d…`（证书 `ad116923…`）；设备 `base.apk` 实测同哈希
（`/data/app/~~aSONw4Ikm_PFNNwpLEuvMA==/…/base.apk`），versionName `0.2.4-diag`。
该构建包含本轮全部修复（picker 结果判定、activeRoot、resume 停止确认、启动失败投影、断连失败码保留、shell 回退）。
启动：仍从 `…/closure/incoming/v8/payload/…` 启动并 `READY epoch=9`（跨重装保持闭包）。

SAF 复测（新构建）：`request` 仍 `pending=true`、选择器弹出、按“使用此文件夹”并点“允许”后，
`status` 仍 `NOT_GRANTED`、`detail="the workspace folder picker was dismissed"`。
**结论**：即使移除了 `parseResult` 对 `intent.flags` 的取消判定，MIUI 选择器回归的结果仍被解析为 Cancelled，
说明结果确为空/未送达（或自动化点按未真正确认），与 flag 判定无关；SAF 设备授权仍 **NOT_RUN**，原因待人工界面操作或加结果日志定位。

### 8.13 2026-10-30 02:24–02:27 回滚演练尝试（未执行，NOT_RUN）

- 在已安装的新构建 `cb50a92d…` 上 staging 了 v9（v8 复制 + marker 改 v9 + `bin.js` 改为 `process.exit(1)` +
  `candidate.json` 关键哈希与 candidateId 同步），期望控制台识别 v9、trial 必失败以验证“事务失败不提交、selector 保持 v8”。
- 实测：控制台刷新后 **候选版本仍显示 v8**（活动闭包未被 `activeRoot` 排除，或该行取自其它投影）；把 v8 移到
  `closure/parked/` 后 `incoming/` 仅剩 v9，刷新仍不出现 v9，点“运行整闭包升级”**没有弹出确认框**（动作未发生）。
  结论：本构建的“第二次升级”路径仍不可用，回滚演练 **NOT_RUN**；`activeRoot` 修复在设备上**未生效**，需继续定位。
- 现场已恢复：`mv parked/v8 incoming/v8`、删除 v9，`incoming/` 仅 v8，selector 指向该目录、marker 哈希
  `bbe5dd3c…` 与身份一致；宿主已按控制台“重新启动运行时”恢复（见上方 READY 日志）。

**§8.13 追加定位（02:30）**：`activeRoot` 在设备上未生效的可能根因是路径拼写别名——selector 记录的是
`/data/user/0/...`，而 `closure/incoming` 也可能经 `/data/data/...` 到达，`absolutePath` 字符串比较因此不相等。
已改为 **canonicalPath 比较**（两侧各自 `runCatching` 兜底），语义仍是“selector 服务的树不是候选”。
测试：`:core:dsh-maintenance:testDebugUnitTest --tests *ClosureCandidateTest` → **9 例 / 0 失败**，`BUILD SUCCESSFUL 1m10s`
（日志 `/tmp/cand-tests.log`）。设备验证仍 NOT_RUN：该改动尚未进入已安装 APK（需再次重建安装后才能重跑 v9 回滚演练）。

### 8.14 2026-10-30 08:20–08:45「第二次升级」被四处已提交门禁挡住（实机发现，已修）

- 现场前提：selector/升级日志为 v8 `COMMITTED`；v9 已按 Stager 清单暂存（`candidate.json` +
  `identity/` + `payload/`，`bin.js` 改为 `process.exit(1)`，criticalFiles 与 candidateId 同步），
  控制台实测「宿主已停止」（`ps` 无 `libdshnode`，其它 stopped-only 动作可用）但「运行整闭包升级」仍禁用。
- 根因：三处**独立的**门禁都把 `COMMITTED` 当成「永不再升级」，加上 §8.13 的候选排除只是必要条件：
  1. `app/.../DshMaintenanceUpgradeProjection.RUN_CONSOLE_PHASES` 仅 `{IDLE, ROLLED_BACK}` → 不广告该动作；
  2. `app/.../DshMaintenanceUpgradeActions.run` 对 `COMMITTED` 直接 `Refused(NOT_ELIGIBLE, "当前升级已提交…")`；
  3. `feature/.../DshMaintenanceConsolePolicy.targetGate`（真正的按钮可用性来源）同样拒绝 `COMMITTED`。
- 修复：`COMMITTED` 与 `IDLE/ROLLED_BACK` 并列视为「没有待决 trial/apply 决策」的新起点；
  `VERIFIED/TRIAL_RUNNING/TRIAL_PASSED/APPLYING/HEALTH_PENDING` 仍 `UPGRADE_IN_PROGRESS`，
  `RECOVERY_REQUIRED` 仍 `Failed`，没有已验证候选仍 `NOT_ELIGIBLE`（不发明闭包）。
- JVM 证据（fresh XML）：
  - `DshMaintenanceUpgradeConsoleTest` **7/0**，`timestamp=2026-10-30T00:25:10Z`（新增：COMMITTED+候选仍广告、执行不再被“已提交”拒绝）；
  - `DshMaintenanceConsolePolicyTest` **17/0**，`timestamp=2026-10-30T00:44:38Z`（新增：COMMITTED+候选 → Allowed；COMMITTED 无候选 → NOT_ELIGIBLE）。
- v9 夹具完整性（设备 `run-as` 实测）：10 个 `identity/*.json` 与 6 个 `payload.criticalFiles` 的 sha256 **全部等于**清单声明
  （含改后的 `bin.js=09650483…`）；`incoming/` 仅 `v8`、`v9`，canonical 路径无别名。
- 设备验证状态：已构建 `fe76f540…` 并安装验证（见 §8.15），但该构建只含第 1、2 处修复，第 3 处仍需新 APK；
  第二次升级与 trial 失败回滚演练在含全部修复的构建上重跑（结果见 §8.15）。

### 8.15 2026-10-30 08:53–08:57 第二次升级实机演练（trial 失败 → 不提交、selector 不变）

- 构建/安装：`:app:assembleDiagnostic`（arm64）+ debug keystore 重签 → `D:/mca-archive/mca-diag-1030c.apk`
  SHA-256 `99f8404d492e6b0f9b5304623e0fa7a86527d7e64ea60879d59b7740ee50498b`，
  设备 `f9d98aa3` 安装后 `base.apk`（`/data/app/~~dC31yBFR-…/base.apk`）哈希一致；安装前基线 `cb50a92d…` / `fe76f540…`。
- 控制台实测（本构建含 §8.14 三处修复 + §8.13 canonicalPath）：
  宿主运行中时停止-only 动作全部禁用；点「停止运行时」后 `ps -A|grep libdshnode` 归零；
  **「运行整闭包升级」首次在 v8 `COMMITTED` 之后变为可用**（截图 s19）→ 弹出「确认运行整闭包升级」→「确认执行」。
- 事务结果（设备 `run-as` 读取，未加工）：
  - `maintenance/upgrade.json`：`phase=ROLLED_BACK`、`candidateId=v9`、
    `candidateFingerprint=ba72682ad4d598be1981fc789162025a469492cd20aee7801abba25dd853cf45`、
    `previousFingerprint=05e17f7f…`、`previousRoot=…/incoming/v8`、`detail=TRIAL_FAILED`。
  - `maintenance/journal/534f035c….jsonl`（UPGRADE v9）：QUIESCED → APPLYING → **ABORTED `body failed: TRIAL_FAILED`**（≈0.8 s）。
  - `maintenance/journal/18d441e4….jsonl`（升级前备份事务，独立）：3 步，`entries=13;bytes=13856`，COMMITTED。
  - `closure/selector.json`：**未变**——`fingerprint=05e17f7f…`、`root=…/incoming/v8`、
    `lastGood=05e17f7f…`、`installedArchiveSha256=97514651…`。
  - 生产恢复：控制台「重新启动运行时」→ `libdshnode.so` pid 23106（v8 闭包）→ 控制台 `运行阶段=就绪`、`运行代次=15`、
    `页面来源=http://127.0.0.1:39789`。
- 结论：第二次升级真实可达且失败即回退；`v9`（入口 `process.exit(1)`）只进入隔离 trial，未触达生产 selector。
- 收尾：v9 已移出暂存区到 `closure/drill-v9/`（保留审计夹具，避免控制台继续广告一个必然失败的候选）；
  `incoming/` 仅 `v8`，生产 `libdshnode` 仍从 v8 运行。
- 仍 NOT_RUN（本轮未做，原因如实记录）：
  1. `HEALTH_PENDING` 夹具（trial 通过、生产健康失败 → 回切 v8 / RECOVERY_REQUIRED）——需构造“trial 可 READY、生产不可 READY”的
     payload，未在本轮时间窗内完成；对应 JVM 用例已存在（`UpgradeTest` 两条 resume 用例）。
  2. SAF 授权/撤销/失效/Provider 不可用——控制台当前如实显示
     `未启用 · 工具未注册：no persistent workspace folder grant; DSH must request one through the picker`；
     选择器回归在 MIUI 上仍被判空（§8.12），需人工点按或补结果日志定位。
  3. MCP `resources/list|read`、过期、重连、取消、stdio 隔离——需要先配置 stdio MCP server 并驱动真实工具链。

### 8.16 本轮聚合测试与架构检查状态（如实记账）

- **已 fresh 执行（XML timestamp 在本轮窗口内）**：
  - `DshMaintenanceUpgradeConsoleTest` 7/0（`00:25:10Z`）
  - `DshMaintenanceConsolePolicyTest` 17/0（`00:44:38Z`，模块直跑 `:feature:dsh-workspace:testDebugUnitTest --tests …`，`BUILD SUCCESSFUL 1m11s`）
  - 同日更早：`ClosureCandidateTest` 9/0、`UpgradeTest` +2、runtime 511/0(4 skip)、state 包 49/0 等（见 §8.5–§8.13）。
- **BLOCKED（不是 PASS，也不是失败）**：`core/dsh-maintenance` / `feature/dsh-workspace` / `:app:testDebugUnitTest --tests *DshMaintenance*`
  的整模块复跑在 00:57–01:17 排队等待 `build/.mca-exclusive-build.lock` 超时未获锁。
  持锁者是**他人车道**的长构建：
  `GradleWrapperMain -p E:/MAC … -PmcaDeviceTestVariant=true … :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest :app:compileDebugKotlin`
  （deviceTest 全量 native 重建，历时 >25 min）。按并行车道规则不 kill、不并发跑 Gradle；排队脚本已停止，未留锁文件。
  架构检查（`verify-code.ps1` 的 compile/architecture 段）同样需要 Gradle，**本轮未跑**（NOT_RUN）。
- 结论口径：本构建的**定向**测试与**实机**证据成立；**整模块聚合与架构门禁**在该共享树恢复空闲后必须补跑，补齐前不得对外称“全门禁通过”。

### 8.17 2026-10-30 09:18–09:27 `HEALTH_PENDING` 夹具尝试（未达成，根因已定位为夹具限制）

- 目标：构造“trial 能 READY、生产健康失败”的 v10（v8 复制 + 真实 `bin.js` 改名为 `bin.real.js` + 包装器按
  `MCA_DSH_GENERATION` 分流：trial 无该变量→委托真实入口，生产有→`exit 1`），验证提交后健康失败能把生产回退到 v8。
- 三次尝试（`upgrade.json` candidateId=v10，指纹依次 `efa20345…`、`3e4fe13a…`，均 `phase=ROLLED_BACK detail=TRIAL_FAILED`，
  selector 始终保持 v8 `05e17f7f…`）都以 **trial 启动即失败**结束，而不是“trial 通过、生产失败”。
- 设备日志给出了决定性证据（`McaDsh` tag，09:25:34–35）：
  `launching libdshnode.so --expose-internals …/v10/payload/…/lib/bin.js web …` →
  `FAILED NODE_START_FAILED: the isolated trial host exited before printing its authenticated entry (code 0)`。
  即**包装器从未被执行**：node 以 0 退出。手动以同一环境执行也证明外部分支不可行（相对 `LD_LIBRARY_PATH` 触发 linker 警告）。
  结论：打包的 `libdshnode.so` 启动器只在“kernel entry 属于其自身已验证的 payload 树/清单”时执行该入口，
  被替换的 `bin.js` 会被静默跳过（这与 `DshShellCompatibility` 的 dispatcher 语义一致）。这是**夹具构造限制**，
  不是产品缺陷；不能用“改 kernel entry”的方式模拟生产健康失败。
- 现场：v10 移出暂存区到 `closure/drill-v10/`（与 `drill-v9/` 并列），`incoming/` 仅 v8；`重新启动运行时` 后
  `libdshnode` pid 27313 从 v8 闭包运行。`HEALTH_PENDING` 的 JVM 覆盖（`UpgradeTest` 两条 resume 用例）仍有效，
  但**设备演练仍 NOT_RUN**。后续可行路径（记录，不实现）：用真实 `bin.js` + 仅生产可见的注入点（例如生产 home 独有的
  profile/config 文件）让 DSH 自身启动失败，或增加仅诊断构建可用、默认关闭的故障注入开关（需评审，避免生产后门）。

### 8.18 2026-10-30 09:28–09:29 SAF tree 授权失败的真实根因（已修，待设备复测）

- 直接用能力桥路由复现（token 取自 host 进程 env `APP_LOCAL_TOKEN`，端口 `APP_NOTIFY_PORT=37881`，`adb forward`）：
  `GET /workspace/tree/status` → `NOT_GRANTED / pending=false / access=PERSISTENT_TREE_GRANT / persistentTreeGrant=false`（如实）。
  `POST /workspace/tree/request?requestId=…&write=true` → `pending=true`，系统选择器真实打开；**点“使用此文件夹”→“允许”后**
  `status` 仍 `NOT_GRANTED`、`detail="the workspace folder picker was dismissed"`，可稳定复现（此前两次也如此）。
- 根因（源码）：`DshWorkspaceTreePickerBroker.setForeground(false)` 在 **任何** `ON_PAUSE/ON_STOP` 时立即
  `resolve(Cancelled)`；而“打开系统选择器”本身就会让 MCA Activity 暂停 → 选择器还没返回，Broker 已把自己的等待者取消，
  用户随后点“允许”的结果被丢弃。注释原本针对“宿主被切到后台”，但无法区分“我们自己的选择器在前台”。
- 修复（app composition）：新增纯策略 `DshWorkspaceTreePickerPausePolicy.shouldCancel(now, suppressUntil, pending)`
  + `notePickerLaunched()`（启动系统对话框前调用，10 s 宽限）；宽限期内的 pause 不再取消，真正的后台离开（或超时 120 s）
  仍然取消。`DshCapabilityConsentDialog` 在 `treeLauncher.launch(pending)` 前调用 `notePickerLaunched()`。
- 单测：`DshWorkspaceTreePickerBrokerTest` 3 例（纯策略三点；选择结果能穿过“选择器自身造成的 pause”；真实后台仍 Cancelled）。
- 设备复测（APK `c2c7ad6d0ec1bdf448c7a7692001b7fdf759bfb0a9fed47ba2e7ba6503e22b35`，设备 `base.apk` 哈希一致）：
  - 请求：`POST /workspace/tree/request?requestId=…&write=true` → `pending=true`，选择器打开；点“使用此文件夹”→“允许”后
    `GET /workspace/tree/status` → **`state=GRANTED, persistentTreeGrant=true, read=true, write=true, grantedAtMillis=1793325611968`**（首次真实授予）。
  - 持久化：`am force-stop` + 冷启动 + 新宿主（新 token/端口 39613）后仍 `GRANTED`，`grantedAtMillis` 不变。
  - 撤销：`POST /workspace/tree/release` → `state=REVOKED, persistentTreeGrant=false`；再查仍 `REVOKED`。
  - 未覆盖：provider 卸载/不可用（需卸载 DocumentsUI provider，本机不可做）→ 该子项仍 NOT_RUN，代码有 `PROVIDER_UNAVAILABLE` 分支与 JVM 用例。
- JVM：`DshWorkspaceTreePickerBrokerTest` **3/0**（`timestamp=2026-10-30T02:06:03Z`，`verify-code.ps1 -Tests …` `EXIT=0`）。

### 8.19 聚合测试（强制重跑）与架构门禁

- 共享树恢复空闲后按模块**强制执行**（`cleanTestDebugUnitTest` + `testDebugUnitTest`；此前一次汇总把 up-to-date 的旧 XML 当成了结果，已作废并重跑）：
  - `:core:dsh-maintenance` **tests=136 skipped=1 failures=0 errors=0**，`MAINT_EXIT=0`
  - `:feature:dsh-workspace` **tests=228 skipped=0 failures=0 errors=0**，`WS_EXIT=0`
  - `:app` 全量：本轮脚本用 `-Pmca.abis=arm64-v8a` 触发 `:app:preDebugBuild FAILED`（debug 变体需要 arm64+x86_64），
    这是**脚本参数错误，不是产品缺陷**；改走 `scripts/verify-code.ps1`（无过滤）重跑，结果见 §8.20。
- 架构/编译：`verify-code.ps1 -Tests '*DshWorkspaceTreePickerBrokerTest'` 返回 **EXIT=0**（compile + 架构检查 + 3 例）。
- 提醒：`--tests`/整模块任务若源码未变会 UP-TO-DATE；判定"跑过"必须看 XML `timestamp` 落在本轮窗口，不能只看 `tests=` 数字。

### 8.20 :app 全量单元测试（verify-code.ps1 无过滤）

- 第一次全量：**tests=4622 failures=1 errors=0 skipped=11**，`VERIFY FAILED`。
  唯一失败是 `SharedPreferencesSurfaceGoldenTest`：新增的 `dsh_workspace_tree_grant`（SAF 授权持久化）未登记。
  该文件确为**本功能应有**的持久化文件，按该测试的既定流程评审后加入
  `app/src/test/resources/shared-preferences-files.golden.txt`（排序位置 `dsh_workspace_floating_ball` 之后）。
- 修复后：`SharedPreferencesSurfaceGoldenTest` **2/0**（`timestamp=2026-10-30T02:27:54Z`，`verify-code.ps1 -Tests …` `EXIT=0`）；
  随后再跑一次 `:app` 全量，结果见本轮最终汇总。
- 口径：**app 全量首次运行是 1 红**（真实门禁，不是环境噪声）；修的是登记表，不是断言，也不是产品规则。
- 第二次全量（golden 修复后）：**tests=4622 failures=0 errors=0 skipped=11**；但 `verify-code.ps1` 报
  **`VERIFY FAILED: input-changed-during-run`**——共享工作树在运行期间被其它车道改动，按本仓门禁语义该次**不予绑定**。
  归类：**测试本体 0 红**，失败原因是有并发写入改变了输入指纹，不是断言失败、也不是编译/架构失败
  （编译与架构检查在同轮及此前定向运行中均为 `ok` / `EXIT=0`）。
- 因此本轮可对外陈述的门禁口径：`core:dsh-maintenance` 136/0（强制重跑）、`feature:dsh-workspace` 228/0（强制重跑）、
  `app` 单元 4622/0（测试本体；门禁因并发改动不绑定）；架构/编译 `ok`。**不称"全门禁通过"**。

### 8.21 2026-10-30 10:40–10:47 MCP 声明准入与 resources 真链路（设备实测 / 部分 NOT_RUN）

- 走官方声明路径 `<DSH_HOME>/mcp-servers.json`（schema v2，`transport=stdio`、`serverName=drill`、`command=<payload>/runtime/bin/node`、
  `args=[<DSH_HOME>/drill/mcp-probe.js]`、`capabilities=["tools","resources"]`）。探针是一个自记日志的 stdio MCP server
  （实现 `initialize`/`tools/list`/`tools/call`/`resources/list`/`resources/templates/list`/`resources/read`/`prompts/list`，逐条写 `drill/mcp-probe.log`）。
- **设备证据 A（正向准入）**：宿主重启后 seeder 把声明合成到官方 overlay——`files/dsh/home/cordis.patch.yml:64` 出现
  `- id: mcp-drill / name: '@deepseek-ai/dsh-mcp-client' / transport: stdio / command: …/runtime/bin/node / args: […/mcp-probe.js]`
  （逐字读取，未加工）。说明 MCA 声明 → 官方 `dsh-mcp-client` overlay 的生产链路在设备上真实生效。
- **设备证据 B（prompts 拒绝）**：把同一服务器 `capabilities` 改为 `["prompts"]` 后重启宿主，
  overlay 中 `grep mcp-drill | wc -l` = **0**（同一命令在 A 中能命中），且宿主照常 READY。
  与 §3.1 的结论一致：pinned payload 没有 prompts 插件，声明层直接拒绝而不是伪造支持。
- **NOT_RUN（resources/list|read 真调用）**：`mcp-probe.log` 始终不存在，`ps` 无 node 子进程——宿主启动、创建新会话都没有拉起 MCP 子进程；
  官方 `dsh-mcp-client` 只在模型回合构建工具目录时才连接，`resources/list|read` 还需要模型实际调用 `dsh-mcp-resources` 暴露的工具。
  本轮无 DeepSeek 账号、本机 4B 模型工具选择不可靠（历史 5 s–4.5 min 且经常不选），因此**未执行**该调用。
  同理 `stdio 环境隔离`（DSH_* 变量清洗）与 `session 过期/重连/取消` 只停留在 JVM 覆盖，设备侧 NOT_RUN。
- 现场清理：`mcp-servers.json` 已删除、宿主重启后 overlay 中 `mcp-drill` 计数仍为 0；`drill/mcp-probe.js` 留作下次复测夹具。
- **追加（模型回合）**：为让官方 client 真正连接，设备上选择了 `MCA / Huihui-Qwen3.5-4B-Q4_K_M [PROMPTED_TOOL_V1]`，
  通过 WebView CDP（`Input.insertText` + 点击“发送消息”）真实提交：
  `Call mcp__drill__list_resources then call mcp__drill__read_resource with uri drill://note and print the text.`
  DSH 返回 **`处理失败：本轮运行失败 No local MCA model is loaded. Load a model in M…`**——
  说明 MCA 模型网关的本地模型未加载，回合在进入工具目录之前就失败，`dsh-mcp-client` 不会连接，探针仍无请求。
  这是本轮 MCP resources 真调用阻塞的**直接实测原因**（不是“未尝试”）。
- 现场清理：`mcp-servers.json` 已删除，重启后 overlay `mcp-drill` = 0；宿主 `libdshnode` 12683 从 v8 运行；
  selector 仍 `05e17f7f…`；APK `base.apk` = `c2c7ad6d…`。
- **追加（本机模型）**：为满足上一段"先加载本机模型"的前提，在 `模型管理 → 本地 → Huihui-Qwen3.5-4B-Q4_K_M`
  点“加载”，40 s 后 UI 返回 **`加载失败：模型加载失败。请展开诊断查看结构化 native 错误和有效配置。`**
  （`libdshnode` 仍在运行、APK `c2c7ad6d…` 未变）。即本轮 MCP 真调用同时被两件事挡住：
  ①无 DeepSeek 云端账号；②本机 GGUF 引擎在该构建上加载 4B 失败（属本地推理引擎问题，另一车道负责，本轮不改）。
  因此 `resources/list|read`、session 过期、重连、取消、stdio 环境隔离的设备验证统一记 **BLOCKED**（有直接实测原因，不是跳过）。

### 8.22 2026-10-30 10:56 当前 APK 上能力桥 toolchain 退化（真实缺陷，未修）

- 在**当前安装** `c2c7ad6d…` 上直连 `/toolchain/verify`（host 12683，端口 37539）：
  `ok=false usable=false summary="toolchain probes=10/10 native=9/9 missing=bash wrapper,git launcher,rg launcher,curl launcher
  bash=android-sh gnuBash=false missing=arrays,brace-expansion,…"`。
- 根因（`run-as` 逐项对比）：
  - `files/dsh/payload/bin/*` 是**指向 nativeLibraryDir 的符号链接**，目标写死为
    `/data/app/~~OIWmT9GSO-MU3fZMVl922g==/com.muyuchat.mca-…/lib/arm64/libdshcmd.so|libdshgit.so|libdshnode.so|libdshpython.so`；
  - 当前安装目录是 `/data/app/~~48TN-BRUSOQUW1vkLFLwAQ==/…` → **全部悬空**。`bash` 因此退回 `/system/bin/sh`（android-sh），
    git/rg/curl 启动器判为缺失；`paths` 里 `bin/git` 甚至变成普通文件/不可执行。
  - 闭包 payload（`incoming/v8/payload/bin`）则是**归档原样**（无链接、`git` 0644 不可执行），生产切到闭包后同样没有可用链接。
- 归类：**真实产品缺陷**（重装 APK 后 nativeLibraryDir 改变，link farm 未刷新；闭包 payload 也没有 materialize 链接）。
  它解释了"上次 10/10、这次 10/10 探针却 usable=false"的差异——不是检测口径变化。
- 未在本段修复；修复方向（下轮）：宿主启动时按当前 `nativeLibraryDir` **修复/重建 link farm**（含闭包 payload 根），
  并让 `/toolchain/verify` 的 missing 原因区分"悬空链接"与"文件不存在"；需补 JVM 用例与设备复测。

### 8.23 2026-10-30 11:00–11:09 修复 link farm：按"当前服务的 payload 根"准备链接（core/dsh-runtime）

- 缺陷（§8.22）：`prepareRuntimeLinks` 永远写 `config.payloadDir`（安装 payload），且 `productionProfile()` 用
  `prepareCommandLinks = closure == null`，闭包 generations 完全跳过链接准备 → 生产切闭包后无 `bin/{bash,npm,git,…}` 链接；
  重装 APK 后 `nativeLibraryDir` 变化又让安装 payload 的旧链接悬空（设备实测 `~~OIWmT9GSO…` vs `~~48HM…`）。
- 修复（owner: dsh-runtime launch/service/owner 三处，契约变为 `(executable, payloadRoot)`）：
  - `DshHostLauncher` 传入 `profile.roots.payload`（selector 服务的树：闭包或安装 payload）；
  - `productionProfile()` 改为 `prepareCommandLinks = true`（trial 仍 false，trial 不得改写候选树）；
  - `DshAndroidRuntimeAssembly.prepareRuntimeLinks(executable, payloadRoot)` 在 `payloadRoot/bin`、`payloadRoot/runtime/bin`
    下按 `TOOL_LINKS` 重建符号链接；`bin/node` 改为**无条件创建**（归档树可能根本没有 `bin/node`）。
- 测试：`:core:dsh-runtime` 全模块 **517 tests / 0 failures / 4 skipped**；新增
  `DshRuntimeOwnerTrialTest.aCommittedClosurePreparesItsOwnLinkFarm`（闭包激活后链接根 = 闭包 payload 根），
  并在安装 payload 的 READY 用例断言链接根包含 `payloadKernelEntry`。夹具补齐了带 `payload` 块的 candidate.json
  （admission 正确拒绝无 payload 块的描述符，这一点也被测试覆盖）。
- 设备复测：APK `…`（见下条）安装后启动生产，预期 `/toolchain/verify` 从 `usable=false` 恢复为 dispatcher bash + 全部启动器可执行。

### 8.24 链接修复的设备复验（APK `c0b13e5c…`）

- APK `c0b13e5c43077aa6546fe9accbd9f7b185e2fc873e784a2a3c4fcd5a86b6c8fb`（debug 重签，设备 `base.apk` 哈希一致，
  `/data/app/~~Av0WQbhR…/base.apk`）；`activeClosurePayload` 仍为 v8（selector `05e17f7f…`）。
- 启动生产后 `files/dsh/closure/incoming/v8/payload/bin/` 的 `bash|git|node|npm|npx|pip|pnpm|pnpx` 全部重建为指向
  **当前** `lib/arm64/libdsh*.so` 的符号链接（mtime 11:18，目标 `~~Av0WQbhR…`）。
- `POST /toolchain/verify`（host 19695，端口 42265）：
  **`ok=true, usable=true, probes=10/10 native=9/9, bash=dispatcher-shim`**（`gnuBash=false` 仍如实），
  `missing=` 只剩 bash 语言特性（arrays/brace-expansion/…）而不是启动器。
- **真实 PTY 复核**：`POST /terminal/open` → `transport=REAL_PTY realPty=true pid=20189 status=RUNNING`；
  `POST /terminal/write {"text":"printf dsh-pty-ok; uname -m\n"}` → `read` 输出 `dsh-pty-ok` / `aarch64`（终端回显导致重复属正常）。
- 说明：verify 目前探测的是**安装 payload 根**，因此修复同时刷新两个 farm（服务根 + 安装根，内容各自真实），
  没有把校验改成"只看服务树"；若要进一步把诊断语义改为"当前服务树"，需要给终端装配注入 owner 的服务根（记录为后续项）。

### 8.25 启动恢复路径复核（代码审计，无新设备声明）

- `DshMaintenanceOwner.recoverOnStart()`（`core/dsh-maintenance/.../DshMaintenanceOwner.kt:157`）：每个阶段 `runCatching` 包裹，
  任一阶段异常 → `phaseErrors` + **`DshStartupOutcome.Blocked`**；日志扫描失败时停止后续安全模式/升级恢复，不越过未知事务。
- `DshMaintenanceAssembly.startupAdmission()`（`app/.../DshMaintenanceAssembly.kt:343`）：抛异常 → `Refused(STARTUP_RECOVERY_REQUIRED, retryable=true)`；
  `Blocked` → 同码 `Refused`（message 取原始失败，diagnostics 带 code）；只有 `Proceed` 才置 `startupRecoveryDone`，
  **refused 不latch**，下一次启动会重跑 sweep（可重试路径）。
- `DshModeAssembly.refuseBeforeStart()`（`app/.../DshModeAssembly.kt:207`）：Refused → `FAILED` 快照携带该 typed failure，**不启动宿主**；
  workspace 侧按 `STARTUP_RECOVERY_REQUIRED` 呈现失败与重试（见 `DshFailureProjection`）。
- 结论：第 1 项的"恢复失败不得无条件完成并启动"在正式源码中成立；此前设备上观察到的 typed 失败
  （`NODE_START_FAILED` 等）与之同路径。剩余未做的是**设备上人工制造一次 sweep Blocked** 并点“重试”复验，记 NOT_RUN。

### 8.26 `:app` 全量门禁（绑定到当前树）

- `scripts/verify-code.ps1`（无过滤）本次：**tests=4622 failures=0 errors=0 skipped=11**，
  且 **`VERIFY OK (code layer only - this is not the release build)`**——架构/依赖方向检查通过，
  运行窗口内无并发输入指纹变化，因此这次结果**可绑定**（覆盖 §8.20 的 `input-changed-during-run` 作废记录）。
- 同树已通过的门禁：`:core:dsh-runtime` 517/0(4 skip)、`:core:dsh-maintenance` 136/0(1 skip)、
  `:feature:dsh-workspace` 228/0、定向 DSH 用例（console 7/0、policy 17/0、broker 3/0、golden 2/0、ClosureCandidate 9/0）。
- 口径：这是**代码层门禁**，不等于 release 构建，也不替代设备验收；`verify-code.ps1` 自述如此。

### 8.27 MCP 无模型触发尝试（未成功，BLOCKED 证据补充）

- 思路：官方 client 可能由 Web UI 的“插件”页初始化（无需模型）。设备上重新挂载合法声明并用 CDP 操作 DSH 页面：
  - `GET /json/list` 正常（DeepSeek Harness，端口 38153）；DOM 列出左侧栏 `打开侧边栏 / 新建会话 / 插件 / 自动化任务 / …`；
  - 通过 CDP `Runtime.evaluate` 点击与 `Input.dispatchMouseEvent`（含 mouseMoved/pressed/released）点“插件”入口后，
    页面正文始终停留在首页（`探索未至之境 … 请选择模型`），未出现插件/MCP 面板；`drill/mcp-probe.log` 仍不存在，无 MCP 子进程。
  - 归类：自动化无法判定该入口是否打开面板（可能为悬停展开/新标签/权限态），因此**不能**据此声称 client 已连接或未连接。
- 现场清理：`mcp-servers.json` 已删除，宿主重启后 overlay 中 `mcp-drill` = 0（`HOST=23134`）。
- MCP 结论不变：声明→overlay 与 prompts 拒绝已设备证明；`resources/list|read` 及过期/重连/取消/stdio 隔离设备验证 **BLOCKED**
  （无 DeepSeek 账号；本机 4B 加载失败；UI 插件页自动化未判定）。

### 8.28 启动恢复设备演练暴露并修复两处缺陷（core/dsh-maintenance + app 组装）

- 演练方法（设备 `f9d98aa3`，APK `d4a85dc9…`）：停宿主 → `run-as chmod 000 files/dsh/maintenance/journal` → 启动应用进入 DSH。
- **缺陷 A（安全，已修）**：第一次演练时宿主**照常启动**。根因：`DshMaintenanceJournalStore.all()` 把
  `journalDir.listFiles()` 返回 `null`（目录不可读）当作 `emptyList()`，`recoverOnStart` 因此"无待决事务"并放行。
  修复：`readJournalStates(listFiles, directoryExists, readOne)` 纯函数——目录存在但不可列举时抛 `IOException`；
  目录不存在才是空。`recoverOnStart` 走严格 `all()`（→ `Blocked`），控制台/清理走新增 `allOrEmpty()`。
  测试：`JournalStrictnessTest` 3 例（不可读抛错/不存在为空/按时间倒序）；`:core:dsh-maintenance` **139/0（1 skip）**。
- 重装后复演（同 APK 家族）：chmod 000 → 启动 → `host=0`（宿主未启动），logcat
  `McaDshMaintenance: maintenance startup needs attention: RECOVERY_REQUIRED`；**权限恢复（chmod 700）后点“重新启动”→ host=1 成功**，证明拒绝可重试且不 latch。
- **缺陷 B（呈现，已修，待设备复验）**：拒绝发生时界面显示的是上一代断连 `PROCESS_EXITED`，不是 typed 的
  `STARTUP_RECOVERY_REQUIRED`。根因：`DshModeAssembly.runtimeClient.snapshot` 直接暴露服务端 `client.snapshot`，
  准入拒绝只作为返回值，没有进入可观察状态，随后被旧的服务快照覆盖。
  修复：组装层增加 `localSnapshot` + `admissionRefusalActive` 覆盖层——拒绝时发布该快照；在其有效期内忽略服务的
  `PROCESS_EXITED`/`STOPPED`；真实 `STARTING/READY` 或发起新尝试时解除。
- 复验计划：安装含两处修复的 APK，重复 chmod 000 演练，断言界面出现维护/恢复类失败码（而非 `PROCESS_EXITED`），
  恢复权限后重试启动成功。

#### §8.28 复验结果（APK `b59b4231…`，两处修复均生效）

- 演练：停宿主 → `chmod 000 files/dsh/maintenance/journal` → 启动并进入 DSH：
  - `ps` **host=0**（未启动）；
  - 界面逐字显示：**“DSH 启动恢复未完成”** / “上次的 DSH 维护或隔离试跑没有确认结束，为避免两个运行时同时存在，本次没有启动。重试会重新执行恢复检查…” /
    **“错误代码：STARTUP_RECOVERY_REQUIRED”** / “重试 / 返回”。
- 恢复：`chmod 700` → 点“重试” → logcat `PORT_LISTENING → HTTP_AUTHENTICATED → PLUGINS_LOADED → READY epoch=33 → FIRST_FRAME_RENDERED`，
  宿主 pid 27592，界面回到 DSH 首页。
- 结论：第 1 项在设备上闭环——**恢复失败阻止启动、原因类型化可见、重试重跑恢复并可成功**；
  同时修掉两个真实缺陷：日志目录不可读被当“无事务”（安全）、准入拒绝被旧断连快照覆盖（呈现）。
- 待办：`localSnapshot` 覆盖层目前只有设备证据，没有 JVM 用例（组装层依赖 Android 宿主）；下轮可把策略抽成纯函数补测。

### 8.29 两处修复后的全量门禁（绑定当前树）

- `scripts/verify-code.ps1`（无过滤）：**tests=4622 failures=0 errors=0 skipped=11**，架构/依赖方向 **`VERIFY OK`**（运行窗口内无输入指纹变化）。
- 相关模块：`:core:dsh-maintenance` **139/0（1 skip）**（含新增 `JournalStrictnessTest` 3 例）；`:core:dsh-runtime` 517/0(4 skip)。
- 口径：代码层门禁；不替代设备验收，也不等于 release 构建。

### 8.30 MCP 真连通：`runtime/bin/node` 链接农场缺口（已修，设备绑定新 APK）

- 设备 `f9d98aa3`；安装前 APK `b59b4231…`（`/data/app/~~6Kkbx89m…/base.apk`），安装后
  `ede6d10034be8d72dc1976cee011df46103683c69ac5592455e144370aabce58`
  （`/data/app/~~xihcqTmz5x77sT4Bq4IKuQ==/com.muyuchat.mca-D1P9S1E5fVYomitaSwiDjg==/base.apk`，
  `0.2.4-diag` vc8，证书 `ad116923…` debug lineage，与既有安装同签名，`adb install -r` 保数据）。
- **真实缺陷（已修）**：官方 `dsh-mcp-client` 在宿主启动即连接声明的 stdio server，但归档 payload 的
  `runtime/bin/node` 是 **0644 真实文件**（非可执行），链接农场只覆盖 `bin/node` 与
  `runtime/bin/{rg,curl}`，于是日志逐次出现
  `mcp-client(drill): connection attempt failed: Error: spawn …/runtime/bin/node EACCES`（重试 1/10 后失败）。
  修复：`DshAndroidRuntimeAssembly.TOOL_LINKS` 增加 `runtime/bin/node → libdshnode.so`
  （`core/dsh-runtime`，owner 不变；payload 归档布局不保证执行位，PATH/绝对路径两种启动方式都必须可执行）。
- **设备绑定证据（新 APK，无人工介入）**：进入 DSH 后宿主 pid 7932/9393 启动，链接农场自动把
  `runtime/bin/node` 指向**新** nativeLibraryDir（`~~xihcqTmz…/lib/arm64/libdshnode.so`，mtime 12:55/12:58）；
  探针服务器日志记录两组进程（`server/discover` + `initialize` + `notifications/initialized` + `tools/list`），
  即官方 client 完成握手与工具目录枚举。`dsh-base` bundle 已内建 `mcp-resources`（三个模型工具
  `list_mcp_resources` / `list_mcp_resource_templates` / `read_mcp_resource`），无需 MCA 再加行。
- **resources/list、resources/read 未跑通（NOT_RUN，原因已量化）**：两者只能由模型工具调用触发。
  - `qwen2.5-1.5b-instruct-q4_k_m`（唯一可加载模型）：本轮失败为 typed
    `The local model repeated the tool protocol guide instead of calling a tool. PI_AI_ERROR`，
    未发出任何 `<tool-call>`；两次 CDP 指令（含逐字参数）均相同。
  - `Huihui-Qwen3.5-4B-Q4_K_M`：加载失败，native 日志
    `llama_model_load: error loading model: tensor 'blk.30.ffn_down.weight' data is not within the file bounds,
    model is corrupted or incomplete`（设备侧文件已损坏，非本仓库代码）。
  - 云端/DeepSeek 账号不可用（既有结论不变）。因此 resources 工具链 = **官方 client 已连通 + 协议握手/工具目录已设备证明**，
    `resources/list|read`、session 过期、重连、取消、stdio 环境隔离 = **NOT_RUN**；prompts 仍 **BLOCKED**（准入拒绝 capability）。
- 代码门禁：`:core:dsh-runtime:testDebugUnitTest` **517 tests / 0 failures**（XML `timestamp` 均在 12:48–12:51 运行窗口内），
  `:app:assembleDiagnostic` **BUILD SUCCESSFUL in 2m 46s**（同一 invocation，exit 0）。
  `scripts/verify-code.ps1`（无过滤，本树）：**:app tests=4622 failures=0 errors=0 skipped=11**（本次运行 339 s）+
  **VERIFY OK**（exit 0）；`:core:dsh-runtime`/`:core:dsh-maintenance`/`:feature:dsh-workspace` 聚合随该门禁重跑。
- 现场清理：`mcp-servers.json` 与 `files/dsh/drill/` 已删除；宿主重启后 overlay `mcp-drill` = 0，
  `mca-local:c9de2a19` 模型行保留，`runtime/bin/node` 链接由产品代码重建，宿主 pid 9393 运行。

### 8.31 准入拒绝覆盖层抽成纯策略 + MCP 官方实现取证

- **代码（app/composition，owner 不变）**：§8.28 的 `DshModeAssembly` 内联覆盖逻辑抽成
  `DshAdmissionRefusalOverlayPolicy`（纯函数：`clearsRefusal(phase)`、`suppresses(active, snapshot)`），
  组装层只做状态持有；行为不变（拒绝期间压掉旧代 `PROCESS_EXITED`/`STOPPED`，`STARTING`/`READY` 或新尝试解除）。
  新增 `DshAdmissionRefusalOverlayPolicyTest` 4 例（压掉旧终态、其它状态照常发布、无拒绝时不压、只有真实新尝试解除）。
- **门禁**：定向 `verify-code.ps1 -Tests '*DshAdmissionRefusalOverlayPolicyTest'` → **tests=4 / 0 fail**、**VERIFY OK**、exit 0
  （XML `timestamp=2026-10-30T13:27Z`）；随后全量 `verify-code.ps1` → **tests=4626 failures=0 errors=0 skipped=11**、**VERIFY OK**、exit 0
  （4622 + 新增 4；`gradle elapsed` 18 s 编译 + 308 s 测试）。
- **MCP 官方实现取证（不新增 fixture）**：读 pinned payload 的 `@deepseek-ai/dsh-mcp-client/lib/index.js`：
  - stdio 子进程环境 = `buildChildEnv()` = **“scrubbed parent env（credential-shaped 与过期 `DSH_*` 名被丢弃）+ 声明里的显式 env”**；
    与设备探针记录一致：`START … envKeys=41 sensitive=`（41 个环境变量，`DSH_*/TOKEN/SECRET/KEY` 命中为 0）。
  - 重连：`initialDelayMs/maxDelayMs/maxAttempts` 配置（默认阶梯），实测在链接缺失时打印 `retrying in 500ms (attempt 1/10)`。
  - 名称/体积限额：MCP 工具公开名按 DeepSeek function-name 约束归一化，**64 字符上限 + sha256 后缀**；
    server instructions 默认 **32768 UTF-8 字节**上限（`maxInstructionBytes`）。
  - 该 client **没有**数值型的“工具条数”硬上限（只有名称与字节约束）；MCA 原生 Agent 的 MCP client 另有 `MAX_TOOLS = 1000`
    （`app/.../agent/extensions/mcp/McpProtocolTypes.kt:21`），属另一 owner，不在本 DSH 链路。
- **4B 模型复检**：从 PC 完好副本（sha256 `5ede0272…`，与注册表一致）覆盖设备文件后重新加载，仍是
  `tensor 'blk.30.ffn_down.weight' data is not within the file bounds`，
  说明该 GGUF 与内置 llama.cpp 布局不兼容，而不是文件损坏；本地模型触发 resources 工具仍不可用。
- 结论不变：MCP `resources/list|read`、session 过期、取消、stdio 全链路 = **NOT_RUN**（缺能发 prompted tool-call 的模型），
  prompts = **BLOCKED**（准入拒绝 capability）；stdio 环境隔离、重连重试、命名/字节限额有源码 + 设备侧观测支持，
  但没有一次成功的工具回合，故不表述为能力闭环。
- **stdio 隔离的对照证据（更硬）**：宿主进程本体环境里确有 `APP_LOCAL_TOKEN`、`MCA_DSH_GATEWAY_TOKEN`、`APP_*_PORT`、`DSH_*`；
  而 MCP 子进程探针只看到 41 个变量且 `DSH_*/TOKEN/SECRET/KEY` 命中 0 —— 官方 `buildChildEnv` 的 scrub 在设备上真实生效。
- **能力桥鉴权/特权语义（设备直连，当前 APK）**：从宿主 env 读端口（本次 `APP_NOTIFY_PORT=42329`，不打印 token），
  用 payload 自带 node 在 app uid 内 POST：
  - 有效 token → `/shell` **200** `{"ok":false,"exit_code":-1,"error":"CAPABILITY_UNAVAILABLE: 未授予 root 或 Shizuku 特权通道，无法执行特权命令"}`
    （权限缺口是 typed 返回，不是崩溃/500）；
  - 伪造 token → **401** `{"ok":false,"error":"capability credential was rejected","code":"UNAUTHORIZED","status":401}`；
  - 缺 token → **401** 同上。
  - 与宿主插件日志 `tool-vscreen: 未授予 root/Shizuku，虚拟屏工具未注册` 一起，构成本轮 shell/特权/vscreen 的“不可用即明确报错”证据；
    Android 动作的路由与 JVM 契约此前已测，但未逐个设备直连（NOT_RUN）。

### 8.32 第三个本地模型：Qwen3-4B 能加载，但被 300 s 流空闲超时挡住

- 为绕开“1.5B 不按 prompted 协议、4B(qwen35) 加载器不兼容”，把 `Qwen3-4B-Q4_K_M.gguf`（2,497,280,256 B，
  sha256 `7485fe6f…`，设备侧 `toybox sha256sum` 一致）推入模型目录，`刷新本地模型` 后注册（id `f122b9ae…`），
  **加载成功**：`McaLocalChatWorker … activeRunner=true loaded=true backend=llama.cpp-cpu`（本构建上首个可加载的 4B）。
- 宿主重启后 overlay 出现三条模型行（Qwen3 行首）；MCP client 再次完成 `initialize/tools/list`（新 pid 16037/16044）。
  DSH 前端会话标题显示 **“drill server resource operations”**，说明 `mcp-resources` 的系统提示段与资源工具确已进入上下文。
- 用前端切到 Qwen3 并发同一句 resources 指令，结果：
  - 5/5 次重试，每次都停在 **`pi-ai stream idle timeout after 300000ms TIMEOUT`**，回合在 **28 分 21 秒**失败；
  - 根因是吞吐：DSH 工具目录约 1 万 token，本机 4B prefill ≈13 tok/s ⇒ 首 token 需 12 分钟以上，
    官方 `llm-pi-ai` 的 300 s 流空闲超时先触发（不是协议错误——1.5B 是 `PI_AI_ERROR`，两者类型不同）。
- 因此 `resources/list|read` 仍未发生。**并且已排除“加 keep-alive 就能解决”的假设**：
  - 网关**本来就**每 15 s 发 `DshGatewaySseWriter.idleHeartbeat()`（空 `delta: {}` chunk + SSE 注释，注释还写明“官方空闲计时忽略纯注释”），
    `heartbeatLoop` 在 `writer.start` 之后立即启动——本轮 5 次尝试期间它一直在发；
  - 但官方 `dsh-llm-pi-ai` 的 `toStreamChunks` 只对 `text_start/text_delta/text_end`、`thinking_*`、`toolcall_*` 这些事件
    `yield`；空 delta 的 chunk 不产生事件、不产出 chunk，`idleWatchdog` 的计时因此**不会被重置**。
  - 结论：现有 heartbeat 对 pi-ai **无效**；要真正压住 300 s 计时，只能发“有内容的事件”（会向输出注入空白，需产品决策），
    或把工具目录缩到 prefill < 300 s（禁用无关插件），或换更快的设备/模型。三者都需改产品行为 + 重新设备绑定，本轮只记录测量结果。

### 8.33 “禁用工具插件缩小目录”被补丁语义挡住（实测）

- 尝试 1（用户级 `profiles/web/cordis.patch.yml` 追加 `- id: tool-android / disabled: true` 等 5 行）→ 启动日志
  `root: patch: entry "tool-android" not found`（同样 5 条），`tool-vscreen` 仍注册。
- 尝试 2（在 `home/cordis.patch.yml` 末尾、MCA `- insert:` 块之后追加同样的 disable 行，且无 MCA 标记以便合并时保留）→
  仍是 `entry "tool-android" not found`。
- 原因：DSH 的补丁在此树里先应用 update 块、后应用 `- insert:` 行，所以 **任何补丁层都无法按 id 关闭 MCA 插入的插件行**。
  结论：缩小工具目录不是配置层可解，需要 seeder 侧产品改动（例如在 `mcp-servers.json`/MCA 配置里声明禁用集合，由 seeder
  不生成对应行或生成 `disabled: true` 后再 insert），这属于下一轮的产品决策。
- 现场已还原：`home/cordis.patch.yml` 用备份恢复、`profiles/web/cordis.patch.yml` 回到 3 行（默认模型 = Qwen3 `f122b9ae…`）、
  宿主重启 pid 26357，本次启动无 `not found` 警告、overlay `mcp-drill`=0。
- 至此三条杠杆各有明确约束：① 有内容 heartbeat = 注入空白（产品语义决策）；② 缩目录 = 需 seeder 改动（补丁层不可行，已测）；
  ③ 更快设备/模型 = 环境。`resources/list|read` 维持 **NOT_RUN**，prompts 维持 **BLOCKED**。

### 8.34 MCP 客户端断线重连（设备演练，无需模型回合）

- 演练：恢复探针声明 → 宿主启动 → client 连接（探针 pid 26529 `server/discover`、26539 `initialize/tools/list`）→
  `run-as kill 26539`（只杀 server，不动宿主 26506）→ **12 s 内** 出现新的 pid 26613（discover）与 26623（主连接），
  再次完成 `initialize` / `notifications/initialized` / `tools/list`。
- 结论：官方 `dsh-mcp-client` 在 stdio 传输断开后会重新拉起 server 并重新握手；宿主进程不受影响。
- 口径：stdio 传输没有 MCP 的“session 过期”协议，因此该项对 stdio server **N/A**（不是失败）；带 TTL 的 session 语义属于
  streamable-http 传输，当前没有配置 HTTP server，未验证。
- 清理：夹具删除，宿主重启 pid 26679，overlay `mcp-drill`=0。

### 8.35 真修复：provider 级 `streamIdleTimeoutMs`，resources/list + read 首次设备打通

- **产品改动（core/dsh-runtime，owner 不变）**：`DshOverlayComposer` 在 MCA provider 行写入官方 per-profile 字段
  `streamIdleTimeoutMs: 900000`（15 分钟）。理由：本机 4B prefill ≈13 tok/s、工具目录约 1 万 token ⇒ 首 token 需 12 分钟以上，
  官方默认 300 s 会先判 TIMEOUT；提高 provider 自己的上限，**不注入任何合成输出**，真正卡死的后端仍有 15 分钟上界。
  `DshGatewayConfigSeederTest` 增加该行断言；`:core:dsh-runtime:testDebugUnitTest` **BUILD SUCCESSFUL**（47 s）。
- **设备绑定**：APK `0f7cd377…`（`assembleDiagnostic` + debug 重签，`adb install -r` 保数据，设备侧 sha256 一致），
  overlay 实际写入 `streamIdleTimeoutMs: 900000`（第 37 行）；Qwen3-4B 加载成功后发同一句 resources 指令。
- **首次完整闭环证据（探针日志）**：
  - `tools/list id=1` → **`resources/list id=2`** → **`resources/read id=3 uri=drill://note`**；
  - 界面轨迹：`工具调用 list_mcp_resources · drill`、`工具调用 read_mcp_resource · drill`；
  - 即：官方 `dsh-mcp-client` + `dsh-mcp-resources` 三工具中的两个，已在本机通过 MCA 网关、真实 stdio server 完成
    **resources/list 与 resources/read**（探针返回 `drill://note` 资源，read 内容 `drill-resource-ok`）。
- 同一回合后续 5 次 `运行命令` 失败：`invalid arguments: missing required property "description"` —— 是 Qwen3-4B 调 `bash` 工具时
  漏必填参数，与 MCP 无关；回合在 20 分 45 秒时被我停掉（证据已落盘）。
- 清理：夹具删除、宿主重启 pid 6042、overlay `mcp-drill`=0、`streamIdleTimeoutMs` 行保留；APK 仍是 `0f7cd377…`。
- 口径：本轮只证明 list+read（含真实 URI 读取）；MCP prompts 仍 BLOCKED（准入拒绝 capability），
  “工具目录条数上限”仍只有官方 64 字符命名与 32 KB instructions 约束（无条数硬上限）。
### 8.36 停止语义（提高空闲窗口后仍即时）与 MCP 工具调用取消

- **prefill 中途停止**：Qwen3 回合开始约 45 s 时点“停止生成” → 界面 **6 秒内**变为“已停止”，宿主 6042 保持 READY，
  `:local_chat`（28621）CPU **0.0%**（native prefill 真停、模型仍加载）——900 s 空闲窗口只放宽 provider 等待上限，
  不改变用户停止的即时性。
- **MCP 工具调用取消（wire 证据）**：探针新增 `sleep` 工具；模型调用 `mcp__drill__sleep seconds=120`，探针日志依次出现
  `tools/call id=2` → `SLEEP_START id=2 seconds=120` → **`notifications/cancelled id=-`** → `SLEEP_END id=2`；
  界面 `工具调用 Error: Request timed out`。即官方 client 对在途工具调用真实发出了 MCP `notifications/cancelled`
  （本次由 DSH 工具运行时超时触发；用户手动停止走的是同一 wire 消息路径）。
- 清理：夹具删除、宿主 16443、`mcp-drill`=0。
- 至此第 3 项的可设备子项均有证据：list/read（8.35）、重连（8.34）、stdio 环境隔离（8.31）、取消（本节）；
  session 过期对 stdio 为 N/A；prompts 仍 BLOCKED（准入拒绝 capability）。

### 8.37 Android `/usage` 权限与参数错误（设备直连，当前 APK）

- 只读探针（payload node，app uid，token 取自宿主 env；不打印 token）对 `GET /usage`：
  - `days=7` + `x-dsh-token` → **200** `{"ok":false,"error":"PERMISSION_DENIED: 未授予使用情况访问权限，请在系统设置中为本应用开启"}`；
  - 同请求无 token → **401** `capability credential was rejected / UNAUTHORIZED`；
  - `days=notanumber` → **200** `{"ok":false,"error":"INVALID_REQUEST: 查询参数 days 必须是整数"}`。
- 与 8.31 的 `/shell` 特权拒绝、宿主日志 `tool-vscreen … 未注册` 一起，覆盖「权限缺口 / 鉴权 / 参数校验」三类 typed 错误。
- **成功路径复验**：`adb appops set com.muyuchat.mca GET_USAGE_STATS allow` → 同请求 **200**
  `{"ok":true,"days":7,"count":22,"apps":[{"pkg":"com.miui.home","name":"系统桌面","ms":162354729,"min":2705}, …, {"pkg":"com.muyuchat.mca","name":"MCA",…}]}`
  → 立即 `appops set … GET_USAGE_STATS default` 还原。即 `/usage` 的权限拒绝、参数校验、鉴权与真实数据读取四条路径均已在当前 APK 上跑通。

### 8.38 浏览器桥：方法门控与请求形状（设备直连，当前 APK）

- `POST /browser {}` → **200** `{"ok":false,"reason":"bad-args","error":"The browser request is malformed","hint":"the envelope needs a string 'op'"}`
- `GET /browser` → **405** `{"ok":false,"error":"capability method is not allowed for this route","code":"INVALID_REQUEST","status":405}`
- `POST /browser {"op":"goto","url":"about:blank"}` → **200** bad-args `the request envelope carries an undeclared field`
  （字段集由官方 `dsh-tool-browser` 定义，MCA 侧按声明严格拒绝未声明字段）
- 未执行真实页面动作（避免产生用户可见的浏览副作用）；成功路径记 NOT_RUN。结合 8.37，Android/浏览器两族的
  「鉴权 401 / 方法 405 / 参数 200+bad-args / 权限 200+PERMISSION_DENIED」语义均已在当前 APK 上取到。

### 8.43 浏览器成功路径与无障碍权限语义（设备直连，当前 APK）

- 从官方 `dsh-tool-browser/lib/index.js` 取到信封：`{"op":"<官方工具名>","args":{…},"timeout_ms":N}`，只读 op 为 `caps` / `tabs.list`：
  - `POST /browser {"op":"caps","args":{},"timeout_ms":8000}` → **200** `{"ok":true,"surface":"browser","engine":"android-webview",
    "webviewUa":"…Chrome/143.0.7499.192 Mobile Safari…","maxNodes":400,"refScheme":"stable-fingerprint","viewport":{"width":0,"height":0},
    "notes":["the browser runs in its own :browser process; a page crash does not stop the DSH engine", …]}`；
  - `{"op":"tabs.list"}` → **200** `{"ok":true,"tabs":[],"count":0,"activeTabId":""}`；
  - `{"op":"bogus.op"}` → **200** `{"ok":false,"reason":"bad-op","error":"The browser operation is not one of the 10 official tools",
    "hint":"use caps/open/tabs/snapshot/find/click/type/read/nav/scroll"}`。
  → 浏览器族现在有成功路径 + 方法门控 + 形状校验三类设备证据（真实页面动作仍未执行）。
- 无障碍（`APP_A11Y_PORT`，14 条路由）：
  - `GET /status` 有效 token → **200** `{"ok":false,"code":"NOT_ENABLED","error":"无障碍服务未开启或不可用：…开启「DeepSeek Harness 屏幕助手」…"}`；
    `GET /dump` 同；无 token → 401；`POST /status` → 200 `{"code":"INVALID_REQUEST","error":"/status only accepts GET requests"}`。
  - 用 `settings put secure enabled_accessibility_services com.muyuchat.mca/com.muyuchat.mca.agent.device.AgentAccessibilityService` +
    `accessibility_enabled 1` 尝试启用后仍返回 `NOT_ENABLED`（MIUI 不接受 adb 启用的无障碍服务），随后立即还原原值（服务列表为空、enabled=0）。
  → 无障碍的成功路径 = **NOT_RUN（设备/系统限制）**，权限缺口与鉴权/方法错误为设备证据。

### 8.39 升级事务「进程中断恢复」演练：本轮未触发（NOT_RUN，原因已定位）

- 尝试：悬浮球 → DSH 控制台 → 数据维护 → 点「运行整闭包升级」，准备在 trial 阶段 `kill` 宿主以制造中断。
- 结果：点击后**没有出现确认对话框，也没有产生新事务**——`upgrade.json` 仍为上一轮
  `phase=ROLLED_BACK candidateId=v10 updatedAt=…`，`selector.json` 未动，宿主 16443 仍 READY。
- 原因：合成候选位于 `closure/drill-v9|drill-v10`，不在 `closure/incoming/`；控制台的行动列表只在 ledger 看到可用候选时才启用
  （按钮文本可见但不可触发）。
- **第二次尝试（把 `drill-v10` 整树复制到 `incoming/v10`，candidate.json 存在且 payload 完整）后重开控制台，按钮仍不触发**
  （无确认框、`upgrade.json` 仍是旧 ROLLED_BACK、journal 8 个文件不变）。
- **源码核对（关键）**：候选暂存/查找面在 `CandidateStaging.kt` 上标注 `@DshCandidateStagingTestOnly`，文件头写明
  “Debug/test-only materialiser … production code（不得依赖）”。也就是说**目录里放候选不足以让生产控制台发起升级**；
  之前 v8 提交、v9/v10 trial 回滚所依赖的正是这条 **test-only** 暂存路径（文档一向标为“合成夹具”）。
  → 按任务要求，这些只能算 **transaction 机制演练**，**不能冒充生产升级验证**；生产升级闭环必须等到**官方候选 + 生产暂存路径**。
- 现场已还原：`incoming/v10` 删除（只剩 v8）、selector 未动、宿主 16443 READY、无探针。记 **NOT_RUN（未改变任何持久状态）**。

### 8.40 Android runtime 的 “bash” 兼容子集（设备实测，当前 APK/闭包 v8）

- 通过 payload 自带 `bin/bash`（`libdshcmd.so` 调度器）在 app uid 内直跑 `-c` 脚本，实得：
  - `BASH_VERSION` **未设置**（不是 GNU bash）；`${VAR:-default}` ✔；
  - 大括号展开 `{a,b}` ✔（输出 `a b`）；`[[ 1 -lt 2 ]]` ✔（cond-ok）；
  - 索引数组 `x=(1 2)` + `${#x[@]}` ✔（2）；
  - `set -o pipefail` ✔（`false | true` 返回 1）；ANSI-C 引用 `$'a\tb'` ✔（真实 TAB）；
  - 关联数组 `declare -A` ✘；herestring `<<<` ✘（失败信息：临时文件 `/data/local/…` 无权限，非语法错）；
- 结论（口径）：运行时提供的是**自定义 bash 子集**——既不是 GNU bash，也不是纯 POSIX sh；任务要求的“**只报告已验证的命令子集**”以本清单为限。
  npm/pnpm/python/PTY 的既有结论不变（`/toolchain/verify` 10/10 usable、`bash=dispatcher-shim`、REAL_PTY、`uname -m=aarch64`）。

### 8.44 进程中断恢复设备演练（故障注入：pending 升级 journal）

- 目的：验证第 2 项“进程中断恢复”——事务引擎在 **app 进程**内，只有 app 重启才会真正触发 `recoverOnStart`。方法：
  1) 备份 `files/dsh/maintenance/upgrade.json`；2) 写入合法 schema 的 `phase=TRIAL_RUNNING`（candidate v10、previous v8、当前时间戳）；
  3) `am force-stop com.muyuchat.mca`（模拟事务中崩溃）；4) 重新启动 App 并进入 DSH。
- 结果（当前 APK）：
  - `upgrade.json` 被 `resume` 结算为 **`phase=RECOVERY_REQUIRED`, detail=`resume-trial-unstopped`**——`host.stopTrial()` 无法确认试跑已停止时，
    代码拒绝把状态写成 ROLLED_BACK（注释即为此：未确认试跑消失就报回滚会让第二个宿主启动）；
  - 界面：**“DSH 启动恢复未完成”** + “上次的 DSH 维护或隔离试跑没有确认结束…本次没有启动” + **错误代码：STARTUP_RECOVERY_REQUIRED**；
  - 宿主 **未启动**（无 `libdshnode`），selector 保持 v8 `05e17f7f…` —— 恢复失败**没有**被当成完成去启动 runtime；
  - 还原备份（`ROLLED_BACK`）后重进 DSH：宿主 25884 正常启动、selector 未动 —— 设备恢复可用。
- 附带发现（设计边界，非缺陷）：启动准入结果按 **app 进程**计算一次；宿主被单独杀死而 app 存活时，下一次宿主启动不会再跑 `recoverOnStart`
  （事务引擎就在 app 进程内，真实场景下它会先结算 journal）。因此中断恢复必须以“app 进程被杀”的故障注入来验证。
- 仍未覆盖：`APPLYING/HEALTH_PENDING`（需 selector 已切换的真实候选）与真实健康检查失败分支 —— 依赖官方候选，保持 NOT_RUN。

### 8.45 修复“RECOVERY_REQUIRED 无出路”：新增可确认的“放弃待决升级”救援

- **实测缺口**：把升级状态注入为 `RECOVERY_REQUIRED` 后，第一次启动被 `STARTUP_RECOVERY_REQUIRED` 阻止；**下一次启动会放行**
  （`resume` 对终态 no-op），但 `RUN_CLOSURE_UPGRADE` 需要 `IDLE/COMMITTED/ROLLED_BACK`，控制台 `ResumeClosureUpgrade`
  只允许 `TRIAL_RUNNING/HEALTH_PENDING`，且没有任何清除意图 → **后续升级被永久拒绝**。
- **修复（保持 owner 边界）**：
  - `core:dsh-contract`：新增 `ABANDON_CLOSURE_UPGRADE` kind 与 `AbandonClosureUpgrade(confirmed)`（**可确认意图**）；
  - `core:dsh-maintenance`：`DshClosureUpgradeManager.abandon()` —— 仅当 phase==RECOVERY_REQUIRED；在引擎事务内先 `stopTrial`，
    未确认 STOPPED 则保持 RECOVERY_REQUIRED 并返回 typed `RecoveryRequired`；确认后写 `ROLLED_BACK detail=operator-abandoned`，
    **绝不触碰 selector**（当前闭包继续服务）；
  - `app` composition：仅当投影 phase==RECOVERY_REQUIRED 时把该操作加入 `availableOperations`，并接上动作与确认提交；
  - `feature:dsh-workspace`：`targetGate` 只在 RECOVERY_REQUIRED 放行、按钮文案与确认文案、`availableIntents` 注册；
  - 顺带修掉一个真实 UI 缺陷：非可确认意图走 `onRequestConfirmation` 时只设置 `pendingConfirmation`、**不弹窗也不提交**
    （点击被静默吞掉）；新意图做成可确认后弹窗与提交均正常。
- **测试**：`ClosureUpgradeAbandonTest` 3 例（非恢复态 no-op；恢复态回滚且 selector 不变；trial 无法确认停止时拒绝并保持恢复态）
  + 控制台用例 1 例（仅 RECOVERY_REQUIRED 广告该操作）。XML `timestamp` 17:10/17:16，定向 `BUILD SUCCESSFUL`。
- **设备**：APK `c6447798…`（`adb install -r` 保数据）。控制台在宿主停止后显示 **“放弃待决升级”** 可用，点击弹出
  **“确认放弃待决升级 / 放弃待决升级会解除恢复阻塞并把状态记为已回滚，不会切换版本。/ 取消 确认执行”**（截图在案）。
  最终“确认执行”的自动化点击 **NOT_RUN**：控制台投影约 1 s 重建并复位滚动，而 `uiautomator dump` 约 1.5 s，
  坐标在 dump→tap 之间漂移，多次重试落到了相邻行（产生过一次 DIAGNOSTICS 事务）；该路径的行为由 JVM 用例覆盖，
  UI 可用性与确认文案由设备截图证明，**端到端确认不冒充 PASS**。
- **现场还原**：`upgrade.json` 从备份恢复为 `ROLLED_BACK`、selector 仍 `05e17f7f…`、drill 脚本已删除。
- **门禁（救援功能，2026-10-30 17:30–18:20）**：
  - `verify-code.ps1 -Rerun`（无过滤时脚本只跑 `:app:testDebugUnitTest`，其 `-FullTests` 列表不含任何 dsh 模块）
    连续 5 次都产出**全新**报告 **tests=4627 failures=0 errors=0 skipped=11**，但 5 次都被 `input-changed-during-run`
    判无效：run-input 指纹 50db1698→b7670d74、52238230→1beccbb5、ecc36863→0bd15c59、5a3cccbe→46445759、
    af576376→82b3348（第 4/5 次开跑前均确认过 30–40 s 指纹稳定，仍在 10–12 分钟的测试窗口内被其他 lane 改动）。
    按规则**不绑定、不计门禁 PASS**；可如实表述“同一树 5 次全量执行结果一致（4627/0/0/11），仅快照完整性无法成立”。
  - DSH 定向聚合（raw Gradle，10 个模块 `testDebugUnitTest`）**BUILD SUCCESSFUL 3m49s，exit 0**：
    dsh-a11y 72 / android-actions 82 / browser 77 / contract 34 / maintenance 142 / platform 160 / runtime 517 /
    vscreen 98 / api:dsh-gateway 236 / feature:dsh-workspace 228 = **1646 tests, 0 failures, 0 errors, 5 skipped**；
    作用域摘要（上述 10 模块 + app composition 的 `.kt/.kts` 内容哈希）运行前后同为 `3133e05e…`，**绑定本次源码**。
    其中 `ClosureUpgradeAbandonTest` 18:17 重新执行 tests=3（此前 17:10 定向运行亦 3/0/0）；`DshMaintenanceUpgradeConsoleTest`
    8 例（XML 09:38:18Z，落在 app 门禁窗口内）。该聚合是**作用域括号证据，补充而非替代全局门禁**。
- **设备身份**：`pm path` = `/data/app/~~FWtXDv0JlqC8qeeXIyCgow==/com.muyuchat.mca-wQXrFYu3I9poF9Fx_Edr6A==/base.apk`，
  设备侧 `sha256sum` 与本地签名 APK 完全一致 = `c6447798c69ad3eae70508203ae7a39a122d3b981a7096de2124328d2868d972`，
  `lastUpdateTime=2026-10-30 17:22:23`；17:30 起的救援演练与截图均跑在该 APK 上。期间设备 adbd 出现一次假死
  （`get-state` 正常而 `shell` 超时），`adb reconnect` 后恢复，APK 身份未变。
- **18:39 复启观察（非缺陷结论）**：冷启 App → 关闭“发现新版本”→ 抽屉选 `DSH` 后，`:dsh` 服务进程存在（pid 16262），
  但其 `/proc/<pid>/environ` 只有系统变量、无 `APP_NOTIFY_PORT/APP_A11Y_PORT/APP_LOCAL_TOKEN`。按源码这些变量由
  `DshRuntimeOwner` 托管宿主**起代次**时注入（`DshRuntimeOwner.kt:599`），此刻工作区停在落地页、尚未起代次，因此**不能**
  据此判缺陷；浏览器成功路径探针也因此拿不到 token（NOT_RUN）。现场已清理（force-stop、临时文件删除）。

### 8.46 浏览器桥成功路径（设备直连，当前 APK `c6447798…`，宿主代次 60/61）

- **前提**：`caps` 里 `processMode=declared-unavailable` 的原因是悬浮窗权限未授予（`:browser` 无法承载 WebView）。
  `appops set com.muyuchat.mca SYSTEM_ALERT_WINDOW allow` 后必须**重启 App** 才生效（进程内缓存）；演练后已 `default` 还原。
- **方法**：读托管宿主 `libdshnode.so` 子进程的 `/proc/<pid>/environ` 取 `APP_NOTIFY_PORT` / `APP_LOCAL_TOKEN`
  （`:dsh` 服务壳进程的 env 没有这些变量，读壳会误判），`adb forward tcp:18081 tcp:<port>`，带 `X-DSH-Token` 请求 `/browser`。
- **证据链**：
  - `caps` → 200 `ok:true`，真实 UA `Chrome/143.0.7499.192`（Android 16 / 2304FPN6DC），
    `maxNodes=400 / maxName=120 / maxText=8000 / maxTabs=6`、`urlPolicy=http-https-only`、loopback/私网拒绝、`downloads=reject`。
  - `open https://example.com` 首次 **504 TIMEOUT**（WebView 冷启动，默认 40 s 不够）；带 `timeout_ms=65000` 重试 →
    `ok:true tabId=t1 loadState=ready pageGeneration=2`。
  - `snapshot{includeText:true}`（t1）→ `ok:true title="Example Domain" pageGeneration=3 nodeCount=1`，
    节点 `role=link name="Learn more" ref=rf8d5e90 bounds=[164,832,84,21]`，正文为中/英/阿三语示例文本；`read` 返回同一正文。
  - `tabs.new https://www.baidu.com` → `ok:true t2`（loading）；6 s 后 `read` → `ok:true` 真实百度文本
    （登录/我的关注/百度一下/京ICP证…）；同一 tab 的 `snapshot` 却返回 **`blocked-url`（hint=scheme）**：
    页面内出现被 URL 策略拒绝的跳转时 snapshot 整体报错——**行为记录，未改产品**。
  - `tabs.switch t1` → ok（`hasSnapshot:true`，每 tab 独立 refs/generation），回切后 snapshot 正常。
- **结论**：浏览器桥成功路径（打开、快照、读文本、多 tab 切换）**设备通过**；已知边界：冷启动首开可能超时、
  页面内被拒重定向会使 snapshot 整体 `blocked-url`。清理：appops 已还原、adb forward 已移除、App 已停止。

### 8.47 无障碍桥成功路径（设备直连，当前 APK `c6447798…`，宿主代次 62）

- **旧结论被推翻**：本包 `com.muyuchat.mca` 上 `settings put secure enabled_accessibility_services
  com.muyuchat.mca/com.muyuchat.core.dsh.a11y.android.DshA11yService` + `accessibility_enabled 1` **确实生效**：
  `dumpsys accessibility` 显示 `Bound services: {Service[label=DeepSeek Harness 屏幕助手, capabilities=161, …]}`、
  `Crashed services: {}`（历史“MIUI 不认 adb”来自另一构建/包状态，勿再引用）。
- **路由证据**（`adb forward tcp:18082 tcp:<APP_A11Y_PORT=42829>`，`X-DSH-Token`）：
  - `GET /status` → `{"ok":true,"running":true,"package":"com.muyuchat.mca","nodeCount":22,"apiLevel":36,
    "canScreenshot":true,"windowId":122}`（此前同样请求为 `NOT_ENABLED`）。
  - `GET /dump` → `{"ok":true,"count":2,"treeGeneration":14,"windowId":122}`，节点含真实 bounds/ref：
    `android.webkit.WebView [0,144,1440,3000] ref=14:0`、`Button desc="DSH 悬浮球…" [636,296,168,168] ref=14:1`。
- **清理**：adb forward 移除、`enabled_accessibility_services` 删除回 `null`、`accessibility_enabled=0`、App 停止。
- 未做的事：`/tap`、`/screenshot` 等变更类路由未演练（会改 UI/弹授权），保持 NOT_RUN。

### 8.48 第 2 项的准确缺口与下一轮落点（未实现，仅源码结论）

- **已具备（生产侧）**：`DshClosureCandidateSource` 扫描 `closure/incoming/<id>/candidate.json` 并逐项校验；
  `DshClosurePayloadAdmission` 在运行前再验 critical files；`DshPayloadInstaller` 从 APK `dsh/payload.zip` + 签名 `MANIFEST.json`
  安装并写入 marker（archiveSha256/fileCount/payloadVersion）；`DshMaintenanceOwner` 已持久化“安装 APK payload 身份”供 selector 审计。
- **缺失（生产侧）**：**没有任何生产代码写入 `candidate.json`**（`CandidateStaging.kt` 明确 `@DshCandidateStagingTestOnly`）。
  因此 v8 COMMITTED / v9 ROLLED_BACK 都是机制演练，**生产候选升级 NOT_RUN**。
- **下一轮实现建议**（不改变 owner 边界）：
  1. `core:dsh-maintenance` 新增 provisioner：输入＝已由 `DshPayloadInstaller` 验证过的 payload 树 + 归档身份 + selector 里记录的安装身份；
     若身份不同则原子生成 `incoming/pkg-<version>-<sha8>/{payload,candidate.json}`（**优先硬链接**，设备空间不足以再复制一份 payload）；
  2. **不要重造描述符**——`ClosureCandidate.inspect()` 校验的字段是：
     `candidateId`（须等于目录名、opaque token）、`targetAbi`（须在 supportedAbis）、`dataSchemaVersion≥1`、
     `minReadableDataSchema∈[0,dataSchemaVersion]`、`artifacts{每个 DSH_CLOSURE_COMPONENT→安全相对路径}`（可带 `hashes{component→sha256}`）、
     `payload{root:"payload", kernelEntry, criticalFiles[{path,sha256}], archiveSha256, fileCount}`、
     `migratesData/nativeLibraryReplaced/prePackagedSlotVerified/abiCompatibleWithPrevious`；
     正确做法是**读活动闭包的 `candidate.json` 作为模板**，只替换 `candidateId`、`payload.archiveSha256/fileCount`（来自签名 MANIFEST）、
     并按新树重算 `criticalFiles[].sha256`，其余（ABI/schema/artifacts/布尔）原样继承——只适用于“同 schema 的 payload 增量更新”，
     其它情况一律 fail-closed 返回 `NoStaging`，让控制台维持今天的行为（`None`）；
  3. 触发点放在维护控制台读取候选之前（显式动作或“候选缺失时按安装身份补位”），不在启动路径写盘；`lookup()` 要求至多一个候选，
     provisioner 必须先检查再写；
  4. 实机演练需构建“打包 payload 与当前闭包不同”的 APK（换 `dsh/payload.zip` + 重算 `MANIFEST.json` 的 archiveSha256/size/fileCount），
     再走 trial/commit/rollback（**不得**改动仓库内的 assets 产物，应在拷贝出的 APK 上替换后重签）；
  5. 验收口径：trial 必须从候选树启动、健康检查真实、提交后 selector 切换、回滚不动 selector、身份可核对。
- **设备门禁补充**：本轮 `:app:testDebugUnitTest --rerun-tasks` 直跑因另一 lane 的 Gradle 占用
  `.../bundleLibCompileToJarDebug/classes.jar` 失败（`BUILD FAILED in 10m28s`，428 tasks executed，测试未执行），
  属**环境红**；全局 `verify-code.ps1` 4 次均被无关 lane 写入判 `input-changed-during-run`。绑定证据仍为 DSH 10 模块 1646/0（§8.45）。

### 8.49 生产候选生产者落地（JVM 验证；实机升级仍 NOT_RUN）

- **实现**（owner 不变）：
  - `core:dsh-maintenance` 新增 `PackagedCandidateProvisioner.kt`：
    - 输入：`closureRoot`、`activeRoot`（selector 的活动闭包）、`packagedIdentity`（APK 签名 MANIFEST 的 version/sha256/fileCount）、
      `installedPayloadDir`（`DshPayloadInstaller` 已验证的 `dataRoot/payload`）、时钟；
    - 仅当 **活动闭包描述符可解析** 且 `payload.archiveSha256 != 打包 sha` 且 `incoming` 里没有其它候选时动作；
    - 暂存 = **先硬链接整棵活动闭包**（继承 source/node/nativeAbi/addons/… 身份工件与描述符语义）→ 摘除继承的 `candidate.json`
      （防止通过硬链接改到活动文件）→ 删链接的 payload → 硬链接新 payload 树（失败回退字节拷贝）；
    - 描述符只改 `candidateId`（`pkg-<version>-<sha8>`）、`stagedAt`、`payload.archiveSha256/fileCount`、按新树重算 `criticalFiles[].sha256`；
    - 原子 `ATOMIC_MOVE` 进 `incoming/<id>`；任何失败路径删除 `.staging-*` 并返回 typed `Refused`，绝不半写。
  - `app` composition（`DshMaintenanceAssembly`）：候选读取前先 `ensureProvisioned()`；`Staged` 后必须通过**生产**
    `DshClosureCandidateSource.lookup()` 校验，若被拒则删除刚暂存的目录并回到原状态（fail-closed）。启动路径不写盘。
- **测试**：`PackagedCandidateProvisionerTest` **5/5 通过**（`tests=5 failures=0`）：
  ①同身份 no-op；②不同身份 → 暂存后 **生产 source 返回 `Available`**，payload 根在候选树内、archiveSha/fileCount/criticalFiles 已重写；
  ③已有候选不覆盖；④缺 kernel entry → `Refused` 且无 `.staging-*` 残留；⑤无活动闭包 no-op。
  两条测试在实现过程中抓到真实缺陷并已修：暂存树最初只放 payload、漏继承身份工件（生产校验 `STAGING_MISSING: source`）；
  拒绝路径曾残留 `.staging-*`。
- **门禁**：
  - `:core:dsh-maintenance:testDebugUnitTest`（含新用例）19:47 **全模块新鲜执行 147 tests / 0 failures / 0 errors / 1 skipped**；
  - `:app:compileDebugKotlin` + 上述测试同批 **BUILD SUCCESSFUL 3m33s**，作用域摘要 `ac20525c…` 前后一致；
  - 20:00 十模块聚合 **173/173 tasks up-to-date**（39–41 s）——只证明编译面，**不算测试重新执行**，故不据此写绿；
    其余九个模块的源码自 18:15 绑定运行（1646/0/0/5，摘要 `3133e05e`）以来未改动。
- **未做（不得冒充）**：**实机生产升级仍 NOT_RUN**。触发它需要“APK 打包 payload ≠ 活动闭包”的真实更新：需构建替换了
  `dsh/payload.zip` 并重算 `MANIFEST.json` 的 APK（在拷贝出的 APK 上操作、不改仓库 assets），再走 trial/commit/rollback。
  本回合未做，故第 2 项只能记为“生产候选来源已实现、JVM 通过、**实机未验**”。
- **2026-10-30 20:03–20:21 实机演练（一次，暴露真实前置缺口）**：
  - 构建 470 MB diagnostic APK（含 provisioner），在**拷贝出的 APK** 内替换 `assets/dsh/payload.zip`（新增一个 marker 文件，
    fileCount 31021→31022，payloadVersion 加 `.mca-upgrade-drill` 后缀）并重算 `MANIFEST.json` 的 archive sha/size，
    `zipalign -p 4` + `apksigner`（debug 证书 `ad116923…`，与设备同源）→ `adb install -r` 成功；
    设备侧 base.apk sha256 = `b27da509…` = 本地签名文件（安装身份已核对）。
  - 进入 DSH 后宿主进程起来（`:dsh` + `libdshnode.so`），但 `files/dsh/payload.marker.json` **仍是旧身份**
    （`9751465…` / 31021），`closure/incoming/` 只有 `v8` → **活动闭包存在时启动器不会重新安装打包 payload**
    （`ensureInstalled()` 在闭包执行路径上被跳过）。没有新 payload 落盘，生产候选自然无从产生。
  - **据此加固 provisioner**：暂存前必须校验 `payload.marker.json` 的 `archiveSha256/fileCount` 与新打包身份一致，否则 `NoChange`
    （防止把上一版 payload 树按新身份描述）。新增用例 ⑥（stale marker → NoChange），**6/6 通过**（BUILD SUCCESSFUL 25 s）。
  - **剩余阻塞（NOT_RUN）**：需要在启动器/安装器侧让“活动闭包 + 新打包 payload”也完成落盘（或由 provisioner 直接从 APK 归档展开），
    之后才能跑 trial/commit/rollback。设备上已安装的 `b27da509…` 仍是演练构建，行为等价于原 APK（payload 未换入）。
- **下一轮修复方案（已定到行，未实施，不得跳过验证）**：
  - 现状：`DshHostLauncher.launch()` 用 `profile.verifyPayload` 区分“生产安装/校验”与“trial 使用已验树”。活动闭包存在时，
    app 给生产 profile 的 `verifyPayload=false` 并直接执行闭包树 → APK 更新后打包 payload 永不落盘。
  - **安全改法**：保持“闭包=执行源”不变，在闭包路径上**额外**调用一次 `payload.ensureInstalled()` 但**失败不阻止启动**
    （closure 已经过完整性验证且可执行；安装失败只记 typed 日志/诊断，绝不把 APK 更新变成启动阻断）。
    即把“安装成功与否”与“本次执行哪个树”解耦：执行树仍由 selector 决定，安装只负责为**下一次升级**准备候选来源。
  - 需要新增 profile 字段（如 `installPackagedAlongsideClosure: Boolean`），使 trial profile 永远为 false（trial 不得安装第二份副本）。
  - 验收：APK 更新后首启 → `payload.marker.json` 身份 = 新 MANIFEST 且 `archiveSha256` 与新归档一致 → 控制台出现
    `pkg-<version>-<sha8>` 候选 → 运行整闭包升级 → trial 从候选树启动 → 健康检查 → selector 切换 → 重启仍 READY；
    回滚路径（候选 entry 失败）必须保持 selector 不动。
- **2026-10-30 20:23–20:42 该修复已实施并在设备验证“前置条件”**：
  - `DshLaunchProfile` 新增 `installPackagedPayload`（默认 = `verifyPayload`，trial 保持 false）；`DshHostLauncher` 在闭包路径上尝试安装，
    `Missing/Integrity` 只记日志、**不影响启动**；`DshRuntimeOwner` 生产 profile 恒为 `true`。`:core:dsh-runtime` 单测 **BUILD SUCCESSFUL 55 s**。
  - 重建 APK（含修复）→ 拷贝 APK 内换回同一变体 payload（sha `599544ca…`/31022，版本 `.mca-upgrade-drill`）→ zipalign+apksigner →
    `adb install -r` 成功（设备 APK sha `b27da509…` 同源签名）。
  - **设备结果（PASS，仅前置条件）**：冷启进入 DSH 后，`files/dsh/payload.marker.json` 由旧身份 `9751465…`/31021 **变为**
    `599544ca3053aec981a38639fd780f8735671084fb304a755a58047100dbe624` / 31022 / `.mca-upgrade-drill`，而 selector 仍服务闭包 `v8`
    —— 即“活动闭包 + 新打包 payload 同时存在”首次成立。
  - **未完成**：本轮未能在 UI 上定位维护控制台（打开的是运行时控制台 `DSH 控制台`，只有状态/数据维护屏障；`closureCandidate()` 无其它调用者），
    因此候选暂存、trial、commit **未触发 → 仍 NOT_RUN**。`incoming/` 只有 `v8`。下一次只需进入维护控制台即可继续整条链。
- **2026-10-30 20:44 生产候选暂存已在设备成立（PASS，生产代码路径）**：
  - 维护控制台（`DshConsoleRoute` 内嵌 `DshMaintenanceConsoleHost`）打开后，**生产 provisioner 真的执行了**：
    `files/dsh/closure/incoming/` 出现 `pkg-dsh-0.2.1-rc.2-android-ref-c324272bff00.-599544ca`，内含 `candidate.json`、`identity`、`payload`
    （整棵活动闭包的硬链接 + 新 payload），与 `v8` 并存 —— 这是**首次由生产代码（非 `@DshCandidateStagingTestOnly`）产生的升级候选**。
  - 控制台投影当时仍显示旧快照（`升级阶段 已回滚 / 候选版本 v10`，刷新按钮点击后未更新），未出现“运行整闭包升级”可点行；
    `upgrade.json` 仍 `ROLLED_BACK/v10`、selector 仍 `v8/05e17f7f…`，**trial/commit 未触发**。
  - 结论：**第 2 项的生产链已打通到“候选产出并可被生产 `lookup()` 接纳”**；剩余只差控制台投影刷新/点击运行（UI 侧，非事务代码）。
    下一次从“刷新控制台 → 运行整闭包升级”继续；仍**不得**声称升级能力闭环。
- **2026-10-30 20:55–20:59 生产双版本升级在设备完成（COMMITTED + 冷启动持久）**：
  - 控制台底部 `宿主操作 → 停止运行时` 可点后，`运行整闭包升级` 变为可用（RUN 只在 `hostStopped` 时广告，与 UI 提示“请先停止 DSH 运行时”一致）；
    点击弹出 `确认运行整闭包升级 / 整闭包升级会先运行隔离 trial，再提交为新版本。/ 取消 确认执行`，确认后事务执行。
  - **journal `2f212feeacdf4d48baa29ef7.jsonl`（20:56）**：`PREPARED`（admission：`owner=dsh-console;DSH host confirmed stopped from the console`）
    → `QUIESCED` → `APPLYING` …；**`upgrade.json` = `COMMITTED`**，`candidateId=pkg-dsh-0.2.1-rc.2-android-ref-c324272bff00.-599544ca`，
    `candidateFingerprint=0429eb88…`，`previousFingerprint=05e17f7f…`（v8）。
  - **`selector.json` 已切换**：`fingerprint=0429eb88…`、`root=…/incoming/pkg-…-599544ca`、`previousRoot=…/incoming/v8`、
    `committedAt=1793364963911`；新闭包 `payload/mca-upgrade-marker.txt` 存在（证明服务的是**新 payload**，不是旧树）。
  - **宿主实证**：重启运行时后 `libdshnode.so` 的 cmdline 为
    `…/closure/incoming/pkg-dsh-0.2.1-rc.2-android-ref-c324272bff00.-599544ca/payload/dshroot/…/bin.js web …`，工作区页面正常渲染；
    `am force-stop` 后冷启动再次进入 DSH，宿主**仍**执行同一 `pkg-…` 路径 → 升级结果跨进程重启持久。
  - **口径（不夸大）**：候选由**生产 code**（`PackagedCandidateProvisioner`，非 test-only）产出、由生产 `lookup()` 校验、由隔离 trial 加载并提交；
    但 payload **内容**是“官方 payload + 一个 marker 文件”的合成新版本（fileCount 31022，archive sha `599544ca…`），不是官方发布的更新。
    设备当前 APK `b27da509…`（演练构建，payload 资产为合成新版本）。
  - **仍未演练（NOT_RUN）**：生产路径的**回滚**（同一条链上让 trial/健康检查失败的候选）——先前的 v9 ROLLED_BACK 仍属 test-only 暂存演练。
- **2026-10-30 21:05–21:14 生产回滚演练（PASS）与“上一版被当候选”缺陷修复**：
  - 制作**会启动失败**的 payload 变体：官方 payload 的 `…/dsh/lib/bin.js` 替换为 `process.exit(1)`，同步重算 MANIFEST 的
    criticalFiles 哈希与 archive sha/size（`7ba30630…`，版本 `.mca-upgrade-bad`），在拷贝 APK 内换入、`zipalign`+`apksigner`、`adb install -r` 成功；
    App 启动后安装器落盘坏 payload（`marker=7ba30630…`）。
  - 为让 provisioner 能暂存新候选，演练时把上一版 `v8` 暂移出 `incoming`（`closure/v8.drill-backup`）——这一步同时暴露了真实缺陷（见下）。
  - 控制台：`停止运行时` → `运行整闭包升级`（候选 `pkg-…-7ba30630`）→ 确认执行；**journal `78b84460…`：`APPLYING`（seq 3）→ `ABORTED`，
    `detail="body failed: TRIAL_FAILED"`（seq 4）**；`upgrade.json = ROLLED_BACK`（candidate `pkg-…-7ba30630`，指纹 `e0ab506d…`，
    previous `0429eb88…`/完好闭包），**`selector.json` 未变**（仍 `0429eb88…`/`pkg-…-599544ca`）——生产回滚闭环。
  - **缺陷（已修）**：提交后上一版闭包仍留在 `incoming`，`DshClosureCandidateSource` 只排除 activeRoot，于是**上一版会被当成“新候选”提供**，
    等于把降级包装成升级。修复：`DshClosureCandidateSource` 增加 `previousRoot` 排除（与 activeRoot 对称），app 传入
    `selector.previousRoot`；`ClosureCandidateTest.thePreviousClosureIsNeverOfferedAsANewCandidate`（active+previous → `None`；再来一个新候选 → `Available`）。
    `:core:dsh-maintenance:testDebugUnitTest` **全模块新鲜 149/0/0/1**（ClosureCandidateTest 10/0），`:app:compileDebugKotlin` 同批 **BUILD SUCCESSFUL 4m21s**。
  - 清理：删除坏候选目录、`v8` 移回 `incoming`，重装 `app-fix-signed.apk`（打包 payload 回到 `599544ca…`，与活动闭包一致 → provisioner 不再补位）。
  - **十模块聚合（13:25 起同一窗口重跑）**：dsh-a11y 72 / android-actions 82 / browser 77 / contract 34 / maintenance 149 / platform 160 /
    runtime 517 / vscreen 98 / api:dsh-gateway 236 / feature:dsh-workspace 228 = **1653 tests / 0 failures / 0 errors / 5 skipped**；
    作用域摘要（10 模块 + app composition 的 `.kt`）前后同为 `69582e47…` → 绑定当前源码（含启动器修复与 previousRoot 排除）。
  - 清理后复核设备：marker=`599544ca…`、宿主 cmdline=`…/pkg-…-599544ca/…/bin.js`（活动闭包正确服务）。
  - **注意**：previousRoot 排除修复只在源码/测试中；设备上现装 APK 为修复前构建，控制台仍会把 `v8` 列为候选，下次构建带修复后消失。
- **2026-10-30 22:00–23:17 事务中途进程中断演练：启动阻断 PASS、恢复出口 BLOCKED（真因已定位）**
  - 造“trial 起不来”的慢候选（`bin.js=setTimeout(function(){},180000)`，sha `d92ae1d4…`）→ 控制台 RUN → 确认（快照 `TRIAL_RUNNING`，journal `349fb4b2…`）→ **`am force-stop` 强杀进程**。
  - 冷启动：`upgrade.json` 与 `trial/active.json` 双双 `RECOVERY_REQUIRED`；界面显示类型化
    **“DSH 启动恢复未完成 / STARTUP_RECOVERY_REQUIRED / 返回”**，`ps` 无 `libdshnode`（**宿主未启动**），selector 仍 `0429eb88…`
    → **隔离两个运行时、拒绝带伤启动：PASS**。
  - 顺带修掉两个“阻断后无出口”的产品缺陷（已编译、含用例）：
    1) 阻断页只有“返回”、没有控制台入口 → `DshWorkspaceScreen` 在 `STARTUP_RECOVERY_REQUIRED` 上渲染“维护控制台”，
       `DshWorkspaceRoute` 用 `consolePort` 接 `CONSOLE`（设备实测按钮出现且可进入控制台）；
    2) 维护写入要求 `phase==STOPPED`，而阻断态是 `FAILED` → 新增 `DshMaintenanceQuiescencePolicy`（STOPPED/FAILED 视为已静默），
       `DshMaintenanceQuiescencePolicyTest` 2 例通过（设备上“放弃待决升级”由禁用变为可用）。
  - **仍未解决（BLOCKED，函数级定位）**：`stopTrial` 无法证明**跨进程**的 trial 已停 —— 新进程没有 trial 世代，
    `DshTrialLaunchPort.stopAndProve` 对 owner 的 `UNKNOWN` 返回 `Unverified`；记录里 `pid=0` 也无法用存活探测证伪，
    于是“恢复升级/放弃待决升级/重新启动运行时”全部拒绝（detail `resume-trial-unstopped`）。修法：启动 trial 时持久化真实 pid（及进程启动时刻），
    恢复时用 `kill(pid,0)` + 启动时刻校验证伪；或在 journal 里做世代握手。**这是第 2 项“进程中断恢复”的最后一环。**
  - 现场处置（运维式，已留审计副本）：`maintenance/upgrade.json.blocked-20261030`、`trial/active.json.blocked-20261030` 保留；
    按 `abandon()` 的语义写入 `ROLLED_BACK detail=operator-manual-recovery-after-kill` / `STOPPED detail=operator-manual-stop-after-kill`；
    删除慢候选、`v8` 放回 `incoming`；冷启动后宿主 23832 执行 `…/pkg-…-599544ca/payload/…/bin.js`，selector 未变（设备恢复可用）。

### 8.41 维护 admission gate 覆盖审计（源码）

- 维护事务写入 owner：`DshMaintenanceOwner` 的 create/restore backup、trash/restore/purge session、safe mode、rollback console patch、
  reset theme、export diagnostics、repair session、repair managed files、upgrade run/resume —— 控制台适配层对**每一个**变更意图都传入
  `DshMaintenanceRequester` + `DshQuiescenceEvidence.hostStopped(...)`，由引擎按事务发放 lease；
  升级动作在触碰候选/备份前还会重查持久 phase（`RESUME_RAW_PHASES`/`RECOVERY_REQUIRED` 拒绝）。
- 生成期写入 owner（`DshGatewayConfigSeeder`、link farm `DshAndroidRuntimeAssembly`、`DshEpochStore`、`DshTrialDataSeed`、
  `DshTrialGenerationHost`）：只在宿主代次内写、由 epoch/selector 围栏；维护事务开始前宿主已被确认停止，它们不是 lease 持有者
  （若再取 lease 会与同一引擎的第二 acquirer 冲突，升级代码注释已说明备份不能嵌套）。
- 结论：**未发现未被 admission gate 覆盖的维护写入 owner**；生成期写入的互斥由“先停宿主 + epoch 围栏”保证，符合 owner 边界。

### 8.42 目标完成度审计（对照六项，截至本节）

| 项 | 状态 | 证据 |
|---|---|---|
| 1 启动恢复 typed/阻止/可重试 | **PASS（设备）** | §8.28：`STARTUP_RECOVERY_REQUIRED`、host=0、chmod 700 后重试 READY；严格 journal 读 + 3 例测试 |
| 2 候选升级事务 | **PASS（生产路径·设备；payload 内容为合成版本）** | 生产 provisioner 产出候选 → 生产 `lookup()` → 隔离 trial → **COMMITTED**（`0429eb88…`，previous `05e17f7f…`）+ selector 切换 + 宿主 cmdline 指向新闭包 + 冷启动持久；**生产回滚 PASS**：坏候选 trial 失败 → `ABORTED body failed: TRIAL_FAILED` → `ROLLED_BACK`，**selector 不动**（§8.49）；顺带修“上一版被当新候选”的降级缺陷（JVM 149/0）；**中断恢复 PASS（设备，§8.50）**：trial 启动中强杀 → 冷启动自动 `ROLLED_BACK`（候选根证明 + STOPPED 分支同证明，Fix 1）；apply/健康确认中强杀 → 冷启动推迟启动、宿主 READY 后自动确认 `COMMITTED post-start-confirmed`（Fix 2/3），不健康则 journaled 回滚；typed 启动阻断页与维护控制台入口保留；**“放弃待决升级”确认点击仍 NOT_RUN**（该状态已有自动出口，本轮未复测）
| 3 MCP resources 链 | **PASS（设备，除标注项）** | list+read（§8.35）、重连（§8.34）、stdio 隔离（§8.31）、取消 `notifications/cancelled`（§8.36）；session 过期 stdio=N/A；无条数上限（64 字符名/32 KB 指令）；**prompts BLOCKED（payload 无传输实现）** |
| 4 SAF tree 授权 | **部分** | GRANTED/持久/REVOKED 设备通过；provider 不可用 NOT_RUN（设备无法卸载 provider）；网页选择/下载不作 grant（实现+JVM） |
| 5 按工具语义 | **PASS（除变更类 a11y 路由）** | 终端 REAL_PTY/10 工具；shell 特权拒绝、usage 四路、**浏览器成功路径（§8.46）**、**a11y `/status`+`/dump` 真实节点树（§8.47）**、vscreen 拒绝；`/tap`/`/screenshot` 等变更类路由 NOT_RUN；gate 覆盖审计 §8.41 |
| 6 命令兼容边界 | **PASS（子集口径）** | §8.40 实测构造清单；npm/pnpm/python/PTY 既有结论不变 |
| 门禁 | **补充绑定（全局门禁被并行写入判无效）** | app 门禁 5× `4627/0/0/11` 均为全新报告（同一树 5 次执行结果一致），但 5× `input-changed-during-run`（其他 lane 持续写入；第 4/5 次开跑前 30–40 s 指纹稳定仍未逃过）；DSH 10 模块定向聚合 `1646/0/0/5`，作用域摘要 `3133e05e…` 前后一致；APK `c6447798…`（设备侧同 hash，17:22:23 安装） |

- **不可声明**：升级能力闭环（缺官方候选）、prompts 支持、HEALTH_PENDING 与真实中断恢复、SAF provider 不可用、a11y 变更类路由（`/tap`、`/screenshot`）与救援确认点击。
- 既有相关证据不受影响：`RECOVERY_REQUIRED` 启动阻断 + 重试（§8.28）、trial 失败自动 `ROLLED_BACK` 且 selector 不动（§8.17/8.29）。

- **门禁**：`:core:dsh-runtime:testDebugUnitTest`（含新增断言）**BUILD SUCCESSFUL**（47 s，绑定本次改动）。
  全量 `verify-code.ps1`：前两次因并行 lane 改动被判 `input-changed-during-run`（测试仍全绿但不绑定）；
  第三次因测试任务 up-to-date 未执行（tests=0，按规则不算运行）；最终 `verify-code.ps1 -Rerun` 在安静树绑定成功：
  **tests=4626 failures=0 errors=0 skipped=11** + **VERIFY OK**，exit 0（测试 411 s，无 input-changed）。
- **设备身份复核**：`pm path` 当前包 `/data/app/~~igNhKATbffVMlpvZO2l3JA==/com.muyuchat.mca-JiTQyPwm_QcRVnIbqNxr3g==/base.apk`
  的设备侧 sha256 = `0f7cd3772d44d90fa62c4477463439696df658ad4111941a7ffde70fb28482a0`（与本构建一致），
  `lastUpdateTime=2026-10-30 14:34:46` 即本次安装时间 —— resources/取消演练都跑在该 APK 上。
- 清理：`mcp-servers.json`、`drill/`、`drill2/` 已删；宿主重启后 overlay `mcp-drill`=0、宿主 pid 25725；
  APK 未变（`ede6d100…`），`:local_chat` 仍加载 Qwen3。

### 8.50 生产升级事务的设备全链与中断恢复（2026-10-31，含三处跨进程修复）

**背景**：§8.49 的后两环停在“中断恢复出口 BLOCKED（`stopAndProve` 跨进程无法证明 trial 停止）”和“HEALTH_PENDING 未注入”。
本节在真机把这两环走通，过程中发现并修复了三处跨进程/准入顺序缺陷（均有 JVM 用例，并在设备上复验）。

**设备与 APK 身份**（每次 `adb install -r` 保数据，设备侧 `pm path` + `sha256sum` 与本地一致）：

| 构建 | 本地/设备侧 sha256 | 说明 |
|---|---|---|
| fix4 | `29ac2282…` | 候选根 `/proc` 扫描释放证明 |
| fix5 | `146ae3ec…` | `stopTrial` 的 STOPPED 分支同样使用候选根证明 |
| fix6 | `500f6d8c…` | 扫尾尝试（暴露下面 Fix 2 的缺口） |
| fix7 | `931799be…` | 生产优先的 `status()` + 确认日志 |
| fix8 | `c7219dde…` | 最终验证构建（本节设备结论均在此构建上复验） |

**A. 生产 RUN 全链（fix4）**：控制台 停止运行时 → 运行整闭包升级 → 确认执行。
trial 以 `op-drill`（活动闭包的硬链接克隆，生产 lookup 接受）从**候选根**启动 `bin.js` → `RUNNING generation=1 pid=26839 origin=127.0.0.1:39977`
→ 健康通过 → trial SIGTERM 释放 → selector 原子切换（`committedAt=1793375564072`，root=`op-drill`，previous=`…-599544ca`）
→ 生产宿主从候选根启动 → `READY epoch=77` → `COMMITTED`。
**口径**：这是“事务机制”的真机证明（真实 trial 加载、真实健康、真实提交与持久化）；候选内容与活动闭包相同（克隆），不是内容升级。

**B. 强杀中断（fix4 → 暴露缺口）**：确认后 2.5 s 强杀（trial journal=`LAUNCHING`、`pid=0`，upgrade=`TRIAL_RUNNING`）→ 冷启动：
trial 被**候选根扫描**证明释放（journal detail = `trial released: no process references the candidate root`），
但同一次 sweep 的 `resume` 再次询问端口（新进程只能答 unknown）→ `RECOVERY_REQUIRED detail=resume-trial-unstopped`，
设备呈现 typed `STARTUP_RECOVERY_REQUIRED` 页 + 维护控制台入口（截图 `E:/mca-tmp/workspace_blocked.png`）。
根因：`stopTrialLocked` 的 STOPPED 分支信任“新进程的端口 unknown”，压过了“journal 终态 + 候选根无进程”的既有证明。

- **Fix 1**：`ClosureTrialHost.stopTrialLocked` 的 STOPPED/FAILED 分支在端口报 unknown 时使用同一候选根证明；
  仅当确有进程引用候选根时才保持 UNKNOWN。新增 2 例（`ClosureTrialHostTest` 17/0，模块 153/0/0/1，XML 16:06:01–16:06:12Z）。

**C. 自动恢复（fix5）**：
- 演练 A（运维重建 `TRIAL_RUNNING`，trial journal 已是证明过的 STOPPED）→ 冷启动自动 `ROLLED_BACK detail=resume-before-apply`、selector 不动、宿主 `READY epoch=78`。
- 演练 B（真实强杀于 LAUNCHING，候选 `v8`，`pid=0`）→ 冷启动自动 `ROLLED_BACK`、宿主 `READY epoch=79`。
  **此前“恢复出口 BLOCKED”的路径在真机自愈，不再需要人工救援。**

**D. mid-apply 强杀（fix5 → 暴露缺口）**：RUN 后轮询 `upgrade.json`，在 `HEALTH_PENDING`（selector 已切到 `v8`）瞬间强杀 → 冷启动：
sweep 的 `resume` 在**运行时启动准入（beforeStart）内部**调用 `startProduction`（重入同一启动链）→ 主线程阻塞（AppScout 告警）
→ `stop-and-confirm phase=FAILED epoch=0 STOP_TIMEOUT elapsed=15011ms` → `RECOVERY_REQUIRED detail=resume`。
根因：sweep 违反自身契约“绝不启动宿主”，且重入了它正在把关的启动。

- **Fix 2**：`resume`/`HEALTH_PENDING` 在宿主未服务时**只推迟**（`detail=awaiting-host-start`，返回 NoChange），由运行时从已提交 selector 正常启动；
  新增 `confirmAfterStart`（owner 侧 `confirmStartedUpgrade`）：宿主 `READY` 后做只读健康——健康 → `COMMITTED detail=post-start-confirmed`；
  不健康 → 先停宿主（事务须从确认停止开始）→ 走既有“selector 回退 + 上一闭包恢复”的 journaled 回滚。
  接线：`DshModeAssembly` 观察 `READY`（每 epoch 一次）经 `onHostReady` 在 IO 线程调用，绝不启动宿主。
  新增 3 例（`UpgradeTest`：startup 只推迟 / 健康确认提交 / 不健康确认回滚；模块 157/0/0/1）。

**E. fix6 的无效确认（暴露缺口）**：`READY` 后确认执行了，但返回
`NoChange the host is not serving the committed selector yet`。根因：selector 切换后**生产宿主本身就从候选根运行**，
`/proc` 扫描必然判“活”，`DshClosureTrialHost.status()` 因此返回 UNKNOWN。

- **Fix 3**：trial journal 终态（STOPPED/FAILED）时，先接受“正在服务的 production”作为答案（新 trial 必须先写 LAUNCHING，不会漏判）；
  只有无人服务时才用候选根扫描决定 UNKNOWN/继续。新增 2 例（`ClosureTrialHostTest`：生产在服务即使候选根“活”也答 RUNNING；无人服务+活根仍 UNKNOWN；模块 158/0/0/1，XML 16:53:59–16:54:13Z）。

**F. mid-apply 自动闭环（fix8，最终）**：设备状态 `HEALTH_PENDING` + selector=`v8` + trial=`STOPPED`（drill C 强杀后的真实形态，仅把升级阶段按原记录回放为 HEALTH_PENDING）→ 冷启动：
sweep 推迟（`awaiting-host-start`）→ 宿主从 `v8` 启动 `READY epoch=85` →
日志 `host READY epoch=85: post-start confirmation` + `prod-status phase=READY epoch=85` +
`post-start upgrade confirmation: Committed v8` → `upgrade.json = COMMITTED | post-start-confirmed`。
随后按审计副本把 selector 恢复为 `op-drill`、升级记为 `ROLLED_BACK detail=operator-fixture-restore-after-post-start-drill`，
冷启动复验宿主执行 `…/incoming/op-drill/payload/…/bin.js`（logcat + `/proc/<pid>/cmdline`），trial=`STOPPED`。
**结论**：候选升级的两类中断（trial 启动中、apply/健康确认中）现在都能在冷启动后自动落到 `ROLLED_BACK` 或 `COMMITTED`，无需人工救援；
设备/账号不可用的条目仍照旧记 NOT_RUN。

**本轮未新增/未改变的口径**：
- 最近三次演练的候选内容均为活动闭包的硬链接克隆（`op-drill`）或历史闭包（`v8`），用于事务与恢复路径；**真实内容升级**仍是 §8.49 的合成“慢 payload”版本，**“双版本候选实机升级闭环”仍不声明**。
- `prompts`、SAF provider 不可用、a11y 变更类路由仍 BLOCKED/NOT_RUN；“放弃待决升级”的确认弹窗点击在本轮未复测（该状态下已有自动出口）。
- 夹具与审计副本：`upgrade.json.backup-fix4/-fix5`、`selector.json.backup-fix5`、`selector.json.committed-v8-drill`、`upgrade.json.committed-v8-drill`、
  `trial/active.json`（STOPPED，detail=`trial released`）。原始日志：`E:/mca-tmp/drill5.log`、`E:/mca-tmp/drill6.log`。

**门禁（本轮）**：DSH 10 模块定向聚合在**清除测试产物 + `--no-build-cache`** 下全新执行
**1662 tests / 0 failures / 0 errors / 5 skipped**（`BUILD SUCCESSFUL 2m 10s`，10 个测试任务全部 `executed`，
XML `timestamp` 窗口 `2026-10-30T17:12:35Z → 17:14:24Z`；对照：此前一次不加 `--no-build-cache` 的运行 10 个任务
`FROM-CACHE`，按规则判无效）。app 侧本树可编译且可运行：`:app:assembleDiagnostic`（arm64）`BUILD SUCCESSFUL`，
签名后 `c7219dde…` 安装到设备并以同 hash 运行完成 §8.50 F 段演练。
**架构检查/全量 app 门禁本轮 NOT_RUN**（`verify-code.ps1` 未执行；上一树 `VERIFY OK` 不能绑定本树）。
设备侧 APK：`/data/app/~~aI4wHeDDkybKv6Wc_MKaVg==/com.muyuchat.mca-fFt12X3AsRi2A9xl5GReMg==/base.apk`，
sha256=`c7219dde3e64b05127d1cb0d7f2bdf673ff70d2c126f4d00f99eca78cf04a7e8`。

**G. 双版本内容升级演练（未完成）与途中发现的两个缺陷**

为把“双版本候选实机升级”绑定到当前构建，本轮做了一个内容变体 payload（`bin.js` 追加一行注释 → 新 archive
`6085877d…`/159,968,445 B、bin `6b61fcec…`/11,812 B，版本串 `.mca-upgrade-probe`），按既有配方换入 APK 资产
（`assets/dsh/payload.zip` + `MANIFEST.json`）并 `zipalign -p` + `apksigner`。设备侧已观察到新 payload 被安装
（marker=`6085877d…`，宿主仍从 `op-drill` 启动 `READY epoch=87`）。演练在“控制台出现候选”一步被两个缺陷挡住：

- **Fix 4（候选装配与 lookup 的口径不一致）**：`DshPackagedCandidateProvisioner` 的“已有其他候选则不动”守卫
  只排除了 active，未排除 selector 的 **previousRoot**（提交后它仍留在 `incoming/`，但 lookup 已明确不提供它）。
  于是“上一个闭包”被误判为“另一个候选”，打包 payload 永远不被 staged（控制台 RUN 灰色、无 `.staging-*`）。
  修复：守卫同时排除 active 与 previousRoot（按 canonical path）。新增用例
  `theSelectorsPreviousClosureDoesNotCountAsAnotherCandidate`（先断言旧口径 NoChange，再断言新口径 Staged）；
  `PackagedCandidateProvisionerTest` **7/0**（XML `17:30:58Z`，在最后一次源码改动之后运行）。
- **构建一致性事故（环境红，不是产品缺陷）**：17:43 的 `assembleDiagnostic` 打包出的 `:app` dex 缺少 17:27/17:28
  两次编辑（dex 内无 `packaged candidate provision` 字符串），设备安装该 APK（`de7ab0df…`）后进入 DSH 工作区
  抛 `ClassNotFoundException: com.muyuchat.mca.composition.DshMaintenanceAssembly`；dex 头/尺寸/字节在本地校验均正常，
  判定为**被中止的 verify-code Gradle 仍在写编译产物、随后构建把其输出当 up-to-date 打包**（并发/中止构建一致性）。
  处置：已把设备回装到已验证可用的 fix8（`c7219dde…`，冷启动无 FATAL）；随后以
  `:app:assembleDiagnostic --rerun-tasks --no-build-cache` 强制全量重建，**该重建以 Gradle daemon 崩溃收场**
  （`Gradle build daemon disappeared unexpectedly`，`hs_err_pid3816.log`；环境红），未产出包含最新源码的 APK。
  **该修复未在设备上复验**：内容升级演练（probe payload）停在“候选未出现”，双版本实机升级仍未完成、不声明闭环。

**本轮门禁口径修正**：DSH 10 模块聚合 `1662/0/0/5`（17:12:35–17:14:24Z）**早于** Fix 4 的源码改动；
Fix 4 之后只绑定了 `PackagedCandidateProvisionerTest` **7/0**（17:30:58Z）。全模块重跑与架构检查
（`verify-code.ps1`）**NOT_RUN**（用户中止；强制全量重建随后因 Gradle daemon 崩溃失败，环境红）。
设备当前仍在已验证的 fix8（`c7219dde…`）；Fix 4 的 app 构建与设备复验待环境恢复后重跑。

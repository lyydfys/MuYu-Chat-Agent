# DSHA 采纳差距矩阵（MCA 五能力对照）

- 审计日期：2026-10-19（Asia/Shanghai），只读。
- 审计对象：[DSHA 与多个 DSH Android 项目的实现研究及 MCA 借鉴建议](DSHA_MULTI_PROJECT_REVIEW_20261019.md)（下称“报告”）中的五个可吸收点。
- 审计基线：`E:\MAC` HEAD `7475fd640e21154dd9543bcae2c83ab112e30361`；工作区 44 个已跟踪修改 + 37 个未跟踪条目；全部 DSH 模块仍为未跟踪工作区内容；三个 native 子模块 `llama.cpp` / `stable-diffusion.cpp` / `tokenizers-cpp` 指针未变（见 §1）。
- 交付范围：**只创建本文件**。未修改生产代码、未运行 Gradle/测试、未构建 APK、未安装或运行任何 APK/外部项目、未做真机或性能验证。

> 本文把“源码事实”和“运行时结果”分开写。源码事实只证明当前代码/固定载荷里有什么，**不是** `PASS`；本文没有任何运行时 `PASS`。所有需要设备、构建或真实执行才能成立的项目统一记为 `NOT_RUN`，继承的限制单列于 §5。

## 0. 结论（先读）

1. **五点里只有两点已有真实骨架**：启动状态分离（`DshRuntimeOwner` / `DshReadinessGate` / `DshWorkspaceViewModel`）和运行时/用户数据分离（`DshRuntimeConfig` / `DshPayloadInstaller`）。另外三点——隔离候选试运行、官方插件管理器的 MCA 侧接入/验证、Android 能力桥——在 MCA 源码中没有实现（官方 plugin-manager 机制本身存在于固定载荷中，见 §2.4）。
2. **最严重的单点不是“缺功能”，而是工作目录错位**：DSH 进程以 `files/dsh/payload` 为 `cwd`，而 `files/dsh/workspace` 与 `DSH_WORKSPACE` 在固定内核里没有消费者。默认工作目录下的用户/工具写入会落进受管 payload 树，下一次 payload 替换会整树删除旧 payload（见 G1）。
3. **启动就绪链本身是强项，但证据模型不完整**：所有探针子失败都压成 `LOADER_NOT_READY`，`AUTH_ENTRY_MISSING` 在 gate 中不可达，`Outcome.Ready.cookie` 被丢弃，READY 之后没有持续健康证据；页面 401/403 还会升级为整代宿主重启（见 G3–G5）。
4. **“官方插件管理器”不是没接，而是被可执行性阻断**：固定内核的 `@deepseek-ai/dsh-plugin-manager` 通过 `execa("pnpm", …)` 安装；MCA 的 `TOOL_LINKS` 没有 pnpm/npm，`payload/bin/pnpm` 是脚本包装器，在 targetSdk 36 下不可执行（见 G9）。
5. **设备能力桥完全不存在**：`api/dsh-gateway` 是模型网关，WebView 的 SAF/下载/对话框是浏览器级能力；没有插件可调用的 token 桥、能力发现、授权、撤销或“结果未知不重放”语义（见 G10）。

| 报告五点 | MCA 现状（源码） | 最高优先级差距 | 优先级 |
| --- | --- | --- | --- |
| 启动状态分离 | 宿主 phase + 页面 state 已分开；五步 readiness gate 存在 | G3 证据压缩 / G4 无 READY 后健康态 / G5 401 触发整代重启 / G6 插件激活未被验证 | P0 |
| 运行时/用户数据分离 | `payload` / `home` / `workspace` / `tmp` 目录已分开；备份排除 `dsh` | G1 cwd 指向 payload、workspace 无效 / G2 静默重建 + 非原子 seed | P0 |
| 隔离候选试运行 | 只有 staging + 摘要 + rename；没有试运行 | G7 先提交后真跑、旧树即删、无健康提交/回退 | P1 |
| 官方插件管理器 | 官方 `plugin-manager` 行在 web profile 清单中；MCA 未接 | G9 pnpm 不可执行、无插件事实清单、无受管/用户边界 | P1 |
| Android 能力桥 | 无桥；只有 WebView SAF/下载和模型网关 | G10 无能力协议/授权/撤销；G11 同 UID 不是隔离边界 | P2 |

> 报告 §6 的 P0–P3 是“验收/研究阶段”；本文的 P0/P1/P2 是**代码落点顺序**，两者不是同一套编号。本文顺序的依据是数据安全与依赖关系，不是报告阶段的简单复述。

## 1. 审计基线与证据边界

### 1.1 起点与漂移

| 项 | 本次观察 | 证据 |
| --- | --- | --- |
| 仓库 HEAD | `7475fd640e21154dd9543bcae2c83ab112e30361`（2026-10-19 23:35 +08:00） | `git -C E:\MAC rev-parse HEAD` |
| 工作区 | 44 个 ` M`、37 个 `??`；DSH 各模块全部是未跟踪目录 | `git status --porcelain` |
| 子模块指针 | `llama.cpp 4ceb1719…`、`stable-diffusion.cpp 16b3846a…`、`tokenizers-cpp acbdc5a2…` | `git submodule status` |
| DSH 代码可回退性 | `core/dsh-contract`、`core/dsh-runtime`、`api/dsh-gateway`、`feature/dsh-workspace`、`app/.../dsh`、`scripts/dsh` 都是 `??`；**没有任何 DSH commit 可绑定** | `git status --porcelain` |
| 报告基线 | 报告记录的是 `f39e8fc9…`；当前 HEAD 已前进，且 DSH 代码仍未提交 | 报告 §2；本次 HEAD |
| 固定内核 | `payloadVersion=dsh-0.2.1-rc.2+android-ref-c324272bff00`；官方 `687f282e…`；参考包 `woaiys3` v1.20.1 | [MANIFEST.json](../../../core/dsh-runtime/src/main/assets/dsh/MANIFEST.json) |
| payload 摘要 | `payload.zip` 160,464,248 B / `97514651…`；`libdshnode.so` 49,715,528 B / `a0d21a15…` | [payload-lock.json](../../../scripts/dsh/payload-lock.json) |
| Android 目标 | `compileSdk 36` / `targetSdk 36` / `minSdk 26`；打包 ABI 仅 `arm64-v8a` | [libs.versions.toml](../../../gradle/libs.versions.toml#L5-L7)、[DshAndroidRuntimeAssembly.kt:89](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt#L89) |

**后果**：本次一切结论都是“工作区源码 + 固定载荷”的静态事实；不能把它们写成某个干净 revision 的构建/运行结果。报告 §2 的基线声明仍然成立，且比当前工作区更旧。

### 1.2 实际读取的源码

- `core/dsh-contract/**`：`DshRuntimeContract.kt`、`DshModelGatewayContract.kt`。
- `core/dsh-runtime/**`：`DshRuntimeOwner`、`DshRuntimeStateMachine`、`DshReadinessGate`、`DshHttpProbe`、`LoopbackHttpProbe`、`DshAndroidRuntimeAssembly`、`DshRuntimeService`、`DshRuntimeServiceClient`、`DshRuntimeNotifications`、`DshRuntimeConfig`、`DshGatewayConfigSeeder`、`DshPayloadInstaller`、`DshPayloadManifest`、`DshNodeCommand`、`AndroidDshNodeHost`、`DshAuthEntryParser`，以及对应测试文件。
- `api/dsh-gateway/**`：`DshGatewayServer`、`DshGatewaySecret`、`DshGatewayConfig`。
- `feature/dsh-workspace/**`：`DshWorkspaceViewModel`、`DshLoadPlanner`、`DshFailureProjection`、`DshOrigin`、`DshWebViewClient`、`DshWebViewFactory`、`DshWebChromeClient`、`DshWebViewHost`、`DshWorkspaceRoute`、`DshWebHostCallbacks`，以及相关测试文件。
- `app/.../dsh/**` 与装配：`DshMcaPorts`、`McaDshGatewayBackend`、`DshModelCatalog`、`DshModeAssembly`、`DshGatewayAssembly`。
- `scripts/dsh/**`：`README.md`、`payload-lock.json`、`stage.ps1`（结构检索）。
- 固定载荷（本地审计副本，git-ignored）：`dshhome/` 模板、`dsh-client-connection`、`dsh-app-boot`、`dsh-host-webserver`、`dsh-working-directory`、`dsh-plugin-manager`、`payload/bin/pnpm`。
- 工程合同与既有证据：[MCA_PROJECT_HARD_LOCK.md](../../architecture/MCA_PROJECT_HARD_LOCK.md)、[BASELINE.md](BASELINE.md)、[ADR 0003](../../adr/0003-dsh-embedded-dual-agent-modes.md)、[R07 KNOWN-LIMITS](evidence/R07-20261019-21f8b1b/KNOWN-LIMITS.md)、[C1–C7](evidence/R07-20261019-21f8b1b/C1-C7-RESULTS.md)、[E1–E9](evidence/R07-20261019-21f8b1b/E1-E9-MATRIX.md)。

### 1.3 本次未执行

- 未运行 Gradle、未编译、未跑任何单测/仪器测试；未读取测试 XML；未使用 `verify-code.ps1`、`build.ps1`、架构检查脚本。
- 未构建 APK、未安装、未启动、未杀进程、未抓 logcat、未截图/录屏。
- 未运行 DSHA 或其他外部项目；未核对其 APK 字节或签名。
- 未验证固定载荷在真机上的启动、认证、前端渲染、WebSocket、插件加载、工具执行、工作目录行为或数据写入结果。

### 1.4 结果用语

| 标记 | 含义 |
| --- | --- |
| **静态成立** | 当前源码/固定载荷可直接证明的事实；不是运行时 `PASS`。 |
| **静态不成立 / 未实现** | 代码中不存在该机制，或行为与声明不一致。 |
| **NOT_RUN** | 需要构建、设备或真实执行；本次没有做。 |
| **FAIL / 限制** | 只继承 R07 `KNOWN-LIMITS` 中已有的 `FAIL`/限制，不新增运行时结论。 |

## 2. 五个吸收点逐项对照

### 2.1 启动状态分离（网页就绪 vs 宿主存活）

**报告主张**（§3.3、§6 P0）：网页健康与宿主存活分别建模；`LISTENING`、`AUTH_EXPIRED`、`INVALID_RESPONSE`、`HTTP_READY` 是不同证据；页面/认证异常本身不授权重启 Node；借鉴点放回已有 `DshReadinessGate`、ViewModel 和诊断投影，不增加第二个 app 级 controller。

**MCA 已成立的部分（静态）**

- 宿主状态机只有宿主语义：`DshRuntimePhase {STOPPED, STARTING, READY, STOPPING, FAILED}`，`DshRuntimeSnapshot` 只带 `phase/epoch/origin/failure`；[DshRuntimeContract.kt:11-72](../../../core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/DshRuntimeContract.kt#L11-L72)。
- 页面状态单独建模：`DshWorkspaceUiState {Starting, Failure, Web}` 与 `DshPageUiState(loading/progress/canGoBack/error)`；[DshWorkspaceViewModel.kt:32-55](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceViewModel.kt#L32-L55)、[DshWebHostCallbacks.kt:52-57](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWebHostCallbacks.kt#L52-L57)。
- 一个权威宿主 owner，串行 start/restart/stop，epoch 单调递增；[DshRuntimeOwner.kt:114-143](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt#L114-L143)、[DshRuntimeStateMachine.kt:51-79](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/state/DshRuntimeStateMachine.kt#L51-L79)。
- readiness 是五步强门：303 + `HttpOnly` + `dsh-auth-*` cookie + 干净 `Location` → 带 `__DSH_BOOT_READY__` 的 HTML → bootstrap JS ≥512 B 且 MIME 为 JS → 第二次 index 仍稳定 → 未认证 401；[DshReadinessGate.kt:85-176](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/DshReadinessGate.kt#L85-L176)。
- 页面网络错误/HTTP 错误多数只进入可恢复 banner，不重启宿主；[DshWorkspaceViewModel.kt:379-433](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceViewModel.kt#L379-L433)。

**差距**

- **G3 证据被压成单一失败码。** `probeOnce` 对 token 交换失败、非 303、无 cookie、非 HttpOnly、非干净重定向、index 失败/非 200/非 HTML/缺 marker、bootstrap 缺失、第二次不稳定、未认证非 401，全部返回 `LOADER_NOT_READY`；`AUTH_ENTRY_MISSING` 在宿主侧只出现在 `DshRuntimeOwner` 的“启动行没打印”分支，gate 内的提前返回分支（[DshReadinessGate.kt:73](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/DshReadinessGate.kt#L73)）实际不可达；页面 401 时 ViewModel 又会手工构造同一个码（[DshFailureProjection.kt:53-60](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshFailureProjection.kt#L53-L60)），因此该码并不专指“认证入口缺失”。`Outcome.Ready.cookie` 已算出（[DshReadinessGate.kt:36](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/DshReadinessGate.kt#L36)）但 owner 只使用 `origin/indexBytes/pluginAsset`（[DshRuntimeOwner.kt:384-393](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt#L384-L393)），认证证据没有穿过 IPC。
- **G4 READY 之后没有健康态。** owner 只有退出监视线程（[DshRuntimeOwner.kt:477-510](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt#L477-L510)）；没有 `LISTENING`/`AUTH_EXPIRED`/`INVALID_RESPONSE`/`HTTP_READY` 的持续探针。端口仍监听但页面/认证已坏时，宿主快照仍是 READY。另一个状态真实性问题：`DshRuntimeServiceClient.stop()` 丢弃 `MSG_STOP` 的回复并无条件发布 `STOPPED`（[DshRuntimeServiceClient.kt:174-198](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeServiceClient.kt#L174-L198)），而 service 侧可能因 stop fence 返回 `FAILED/STOP_TIMEOUT`（[DshRuntimeStateMachine.kt:122-136](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/state/DshRuntimeStateMachine.kt#L122-L136)）。
- **G5 401/403 升级为整代宿主重启。** `DshWebViewClient` 把主框架 401/403 交给 `onHttpUnauthorized`（[DshWebViewClient.kt:99-111](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewClient.kt#L99-L111)），ViewModel 以 `AUTH_ENTRY_MISSING` + `requiresRestart=true` 失败（[DshWorkspaceViewModel.kt:419-423](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceViewModel.kt#L419-L423)）；`retry()` 随后走 `client.restart()`（[DshWorkspaceViewModel.kt:114-118](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceViewModel.kt#L114-L118)），`DshModeAssembly.restart()` 先停宿主与网关再起新代（[DshModeAssembly.kt:39-47](../../../app/src/main/java/com/muyuchat/mca/composition/DshModeAssembly.kt#L39-L47)）。这是“认证状态”没有独立模型的直接后果；报告要求的“页面/认证异常不授权重启 Node”尚未满足。
- **G6 “插件已激活”只是注释。** gate 的注释称 marker + bootstrap 代表“Loader tree settled and its client plugin resources are registered”（[DshReadinessGate.kt:10-20](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/DshReadinessGate.kt#L10-L20)），但固定内核的 `__DSH_BOOT_READY__` 只是 host webserver 注入的 boot deferred（`payload/.../dsh-host-webserver/lib/index.js:63-70`），客户端插件图（`__DSH_BOOT__` manifest / batches）在浏览器里随后才加载；在“插件证据”这一点上，gate 只验证了 bootstrap batch 资源，没有验证插件图或激活失败。报告的 E3（官方资源就绪）仍是 `NOT_RUN`。

**运行时限制**：真机前端渲染、WebSocket、client 插件加载、401/403 实际路径均为 `NOT_RUN`（R07 E1–E9 全部 `NOT_RUN`，见 §5）。本文不把五步 gate 的静态存在当成真机通过。

### 2.2 运行时/用户数据分离

**报告主张**（§3.6）：APK 拥有的引擎、内置插件和脚本与用户会话、项目、第三方插件分开；稳定数据根映射到 DSH home；数据不可读时拒绝静默创建空库；更新与用户数据分开管理。

**MCA 已成立的部分（静态）**

- 目录分层：`dataRoot` 下 `home/`（DSH_HOME）、`workspace/`、`payload/`、`tmp/`，另有 `payload.marker.json`；[DshRuntimeConfig.kt:5-17,60-65](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshRuntimeConfig.kt#L5-L17)。
- 环境隔离：`HOME`、`DSH_HOME`、`DSH_CONFIG_DIR`、`DSH_WORKSPACE`、`TMPDIR`、`LD_LIBRARY_PATH` 指向 DSH 树，`PATH` 以 payload 的 bin/runtime 目录优先、再附 `/system/bin` 与 `/system/xbin`；[DshNodeCommand.kt:48-92](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt#L48-L92)。
- payload 安装只操作 `payload.staging.*` / `payload.old.*` / `payload.archive.tmp` / marker，不递归删除 `dataRoot` 或 `home`；[DshPayloadInstaller.kt:85-177,239-256](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadInstaller.kt#L85-L177)。
- 备份/设备转移排除整个 `dsh` 目录，DSH 的凭据、插件状态和会话不进入 Android 备份；[backup_rules.xml:25-28](../../../app/src/main/res/xml/backup_rules.xml#L25-L28)、[data_extraction_rules.xml:23-25,57-59](../../../app/src/main/res/xml/data_extraction_rules.xml#L23-L59)。
- MCA overlay 写在 `$DSH_HOME/cordis.patch.yml`，这是固定内核确实会读取的 home-level user patch 层（`dsh-app-boot/lib/index.js:1118-1136`），不是随意路径。

**差距**

- **G1 工作目录错位（最严重）。** `DshRuntimeOwner` 把 `workingDir = config.payloadDir` 传给 `DshNodeHost.start`（[DshRuntimeOwner.kt:333-339](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt#L333-L339)、[AndroidDshNodeHost.kt:63-66](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/AndroidDshNodeHost.kt#L63-L66)），所以 `process.cwd()` 是 `files/dsh/payload`；固定内核 `dsh-working-directory` 的默认目录正是 `config.defaultDirectory ?? process.cwd()`（`payload/.../dsh-working-directory/lib/index.js:26`），而官方 base/preset 没有覆盖 `defaultDirectory`。`DSH_WORKSPACE` 在固定内核的 `@deepseek-ai` 包（`*.js`）中检索无命中（只有参考 app 的 `cordis.patch.yml` 用过 `process.env.DSH_WORKSPACE`）。因此 `files/dsh/workspace` 与 `DSH_WORKSPACE` 实际上没有把工具工作根切走；默认工作根是受管 payload 树。下一次 `DshPayloadInstaller` 换树时 `deleteRecursively(previous)` 会删除整个旧 payload（[DshPayloadInstaller.kt:143-157](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadInstaller.kt#L143-L157)），默认工作根下产生的用户/工具文件随之消失。
- **G2 数据不可读时静默重建 + seed 非原子。** `DshGatewayConfigSeeder.seedProfileTemplate()` 在 `profiles/web` 或 home 目录缺失时直接 `mkdirs` 并复制模板（[DshGatewayConfigSeeder.kt:50-87](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt#L50-L87)）；home 丢失/损坏与首次启动走同一路径，没有“拒绝静默创建空库”的判定。模板复制是直接 `target.outputStream()`，不是 temp+rename（[DshGatewayConfigSeeder.kt:89-98](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt#L89-L98)）；若进程在复制中死亡，已存在的半截文件因 `if (!target.isFile)` 永远不会被重写。`PROFILE_TEMPLATE_FILES` 只有 3 个文件（[DshGatewayConfigSeeder.kt:216](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt#L216)），没有模板版本号或迁移记录，payload 升级不会更新已有 home 的 profile 模板。
- **G2b 环境契约漂移（低影响）。** `DSH_CONFIG_DIR` 只被写入环境（[DshNodeCommand.kt:59](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt#L59)），固定内核的 `@deepseek-ai` 包（`*.js`/`*.mjs`）检索没有读取它；官方配置实际在 `$DSH_HOME` 与 `$DSH_HOME/profiles/web`。`DshRuntimeConfig` 注释把 `workspace/` 写成“tool working directory”（[DshRuntimeConfig.kt:11-17](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshRuntimeConfig.kt#L11-L17)）也与 G1 冲突。
- **受管/用户插件边界不存在。** 目录上只有 `payload` 与 `home` 之分；第三方插件若经官方 plugin manager 安装，会写进 `$DSH_HOME/profiles/web`（package.json/lock/node_modules），MCA 没有“受管插件 vs 用户插件”的记录或清理规则（见 G9）。

**运行时限制**：默认工作目录、用户文件实际落点、下次换树是否真的删除用户文件，均为 `NOT_RUN`；本文给的是代码路径，不是已复现的数据丢失事件。备份排除 `dsh` 是已实现事实，但随之没有导出/迁移路径（ADR D7 的“DSH 唯一 writer”选择，本文不建议加 DSHA 式宿主备份）。

### 2.3 隔离候选试运行

**报告主张**（§3.6、§6 P3）：`RuntimeTrial` 在隔离环境验证鉴权、随机 nonce/端口、存储与会话重开、浏览器 renderer；`ManagedRuntimeTransaction` 把健康证据绑定到内容与更新事务；流程为 candidate → 隔离真试运行 → 停止屏障 → 切换 → 健康确认 → 提交/回退。

**MCA 已成立的部分（静态）**

- `DshPayloadInstaller` 有 staging、大小/SHA-256 校验、路径穿越拒绝、关键文件校验、rename 换树、失败时回滚 `previous`、marker 写入；[DshPayloadInstaller.kt:37-177](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadInstaller.kt#L37-L177)。
- 换树前不启动 Node；启动前再次验证 payload（[DshRuntimeOwner.kt:213-239](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt#L213-L239)）。
- 只有一个 payload 来源：APK assets（[DshAndroidRuntimeAssembly.kt:43-55](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt#L43-L55)）；没有在线下载器/第二套更新器。

**差距**

- **G7 先提交、后真跑，且旧树立即删除。** 安装成功即 `staging.renameTo(payloadDir)`，随后 `deleteRecursively(previous)`（[DshPayloadInstaller.kt:143-157](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadInstaller.kt#L143-L157)）；第一次真实执行发生在换树之后，用的是真实 `home`、真实 gateway、真实端口（[DshRuntimeOwner.kt:333-395](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt#L333-L395)）。如果候选通过 hash 但 exec/loader/auth 失败，旧树已经不在，宿主只能停在 FAILED；`ensureInstalled()` 下次看到 marker 匹配会直接 `reused=true`（[DshPayloadInstaller.kt:72-80](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadInstaller.kt#L72-L80)），不会自愈。
- **G7b 试运行的“隔离”不存在。** 没有 `trial home`、随机 nonce、独立端口或“不派发业务”的屏障；现有 `nodeExecProbeTimeoutMillis` 只跑一次 `node --version`（[DshRuntimeOwner.kt:291-318](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt#L291-L318)、[DshNodeExecStrategy.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeExecStrategy.kt)），不能证明内核、loader、认证、存储或插件。
- **G7c 校验范围有限。** manifest 只固定 6 个关键文件（`bin.js`、`dsh/package.json`、`profiles/web/package.json`、3 个 runtime `.so`；[MANIFEST.json:17-48](../../../core/dsh-runtime/src/main/assets/dsh/MANIFEST.json#L17-L48)），`fileCount` 只校验条目数，不是逐文件 hash（31,021 个条目；[MANIFEST.json:16](../../../core/dsh-runtime/src/main/assets/dsh/MANIFEST.json#L16)）。非关键文件损坏/污染只能靠真跑暴露。
- **G8 没有候选/更新来源。** payload 只从 APK assets 读取，没有下载、版本协商或更新事务；“candidate”目前只能由新 APK 带进来。因此 G7 的修复范围是 **APK 升级时的候选**，或未来单独引入受管更新器；本文不把“加下载器”当作本矩阵的交付项。

**运行时限制**：`RuntimeTrial`、隔离 home、健康提交、回退均为 `NOT_RUN`（未实现）。现有 staging/rename 的静态事实不等于“更新已安全”；没有真机故障注入证据。

### 2.4 官方插件管理器

**报告主张**（§4.2、§6 P1）：插件市场复用官方 `pluginManager` 的检查、安装、审批与回滚；MCA 只做目录/入口增强，执行和数据事务交给官方机制，不在 Kotlin 再造安装器；按插件类型记录兼容事实，不按设备型号设准入。

**MCA 现状（静态）**

- 官方 `plugin-manager` 宿主行在 shipped web profile 的 R00 静态分类中是 `enabled-when-profileContext-present`（`CAPABILITY_MANIFEST.json` 的 `host.plugin-manager`，`requiredForV1=true`，`phoneResult=NOT_RUN`）；`ui-plugin-manager` / `client.ui-plugin-manager` 也在 web roster 中（`NOT_RUN`）。这些是清单事实，不是手机运行结果。
- 官方安装实现通过 `execa("pnpm", …)`（`payload/.../dsh-plugin-manager/lib/index.js:610`，默认 `pnpmCommand = "pnpm"` 在 `:1574`），向 profile 写 `package.json`、`pnpm-lock.yaml`、`pnpm-workspace.yaml`、`node_modules`，并可能运行依赖构建脚本。
- MCA 的 `DshGatewayConfigSeeder` 只写 `permission` 与 `llm-pi-ai` 两段 overlay（[DshGatewayConfigSeeder.kt:153-186](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt#L153-L186)），不启用/禁用插件行，也不提供插件目录或入口。
- MCA 没有 Kotlin 插件安装器；这一点与报告“不要在 Kotlin 再造一套”一致。

**差距**

- **G9 pnpm 不可执行。** `payload/bin/pnpm` 是 `#!/system/bin/sh` 包装器（`payload/bin/pnpm:1-18`），最终 `exec "$NODE" "$DIR/../pnpm/bin/pnpm.cjs"`。payload 解压用 `ZipInputStream` + `FileOutputStream`，不保留可执行位（[DshPayloadInstaller.kt:180-215](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadInstaller.kt#L180-L215)），targetSdk 36 也禁止从 app home 执行；`TOOL_LINKS` 只镜像 node/rg/curl/git/git-remote/python，没有 pnpm/npm（[DshAndroidRuntimeAssembly.kt:166-175](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt#L166-L175)），`prepareRuntimeLinks()` 也不处理 pnpm/npm（[DshAndroidRuntimeAssembly.kt:110-151](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt#L110-L151)）。`scripts/dsh/README.md` 已明确 npm/pnpm 包装脚本在 targetSdk 36 下不可执行（[README.md:86-89](../../../scripts/dsh/README.md#L86-L89)）。所以官方插件安装路径在当前宿主上没有可用 `pnpm`。
- **G9b 没有插件事实清单。** 报告 P1 要求逐插件记录 DSH API 版本、Node、npm/系统依赖、native ABI、所需 shell、安装需求、权限、锁/原子写、取消与产物。MCA 侧没有这份清单（`CAPABILITY_MANIFEST.json` 是 R00 的静态清单，不是运行事实；其 `phoneResult` 全为 `NOT_RUN`）。
- **G9c 没有受管/用户插件边界。** 官方 manager 直接写用户 profile；MCA 没有“受管插件目录/用户插件目录”之分，也没有安装前授权、安装后健康检查、失败回滚或撤销的宿主策略。DSH 的 approval 仍由官方负责，但“谁允许安装/何时允许联网/失败如何回退”没有 MCA 合同。
- **G9d 模型面 `tool-plugin-manager` 是另一件事。** 官方 web overlay 把面向模型的 `tool-plugin-manager` 静态禁用（`CAPABILITY_MANIFEST.json` 的 `host.tool-plugin-manager`，`disabled-by-web-overlay`）；UI 的 plugin manager 与模型工具不是同一条路径，不能因为 UI 存在就宣称模型可安装插件。

**运行时限制**：插件安装/启用/禁用/卸载、纯 JS 插件、带 native addon 插件、需要 Linux 包管理器的插件全部 `NOT_RUN`；G9 的 pnpm 结论是源码/载荷事实，不是“安装必失败”的真机证明（但没有可执行 pnpm 时，`execa("pnpm")` 没有成功路径）。

### 2.5 Android 能力桥

**报告主张**（§3.5、§6 P3）：设备能力通过独立桥提供；先解析受限请求并认证，再按路由提供设备操作；token 放请求头、拒绝 URL token、常量时间比较；能力发现、单独授权、撤销、结果未知不重放；不默认引入 ADB/root/全文件访问/虚拟屏。

**MCA 现状（静态）**

- **没有设备桥。** `api/dsh-gateway` 只有 `/healthz`、`/v1/models`、`/v1/chat/completions`、`/v1/mca/calls/cancel`（[DshGatewayServer.kt:228-249](../../../api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewayServer.kt#L228-L249)），是模型推理协议适配，不是设备操作。
- WebView 只有浏览器级能力：SAF 文件选择（[DshWebChromeClient.kt:53-57](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebChromeClient.kt#L53-L57)）、`data:`/http 下载与 SAF 保存（[DshWebViewHost.kt:116-179](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewHost.kt#L116-L179)）、JS 对话框/全屏/外链；camera/mic 明确拒绝（[DshWebChromeClient.kt:59-61](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebChromeClient.kt#L59-L61)），`allowFileAccess=false`、`allowContentAccess=false`（[DshWebViewFactory.kt:45-72](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewFactory.kt#L45-L72)）。
- 全仓 `*.kt` 没有 `WebMessageListener` / `postWebMessage` / `addJavascriptInterface`；没有插件可调用的 JS bridge。
- 固定载荷里存在参考 app 的 `dsh-tool-android`、`dsh-tool-shizuku`、`dsh-tool-accessibility`、`dsh-tool-vscreen` 包，但 MCA 的 seeder **故意不插入**这些行（[DshGatewayConfigSeeder.kt:21-24](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt#L21-L24)）。
- ADR 0003 D4 只写了要保留“窄平台契约”（SAF/分享/外链/通知/权限）与“不暴露通用 JS bridge”（[ADR 0003:50-53](../../../docs/adr/0003-dsh-embedded-dual-agent-modes.md#L50-L53)），没有实现。

**差距**

- **G10 没有能力桥。** 没有 token 认证的 loopback 设备路由、能力注册/发现、逐能力授权、撤销、调用身份、幂等/“结果未知不重放”或审计。现有 `DshGatewaySecret` 的 header bearer + 常量时间比较（[DshGatewaySecret.kt:18-34](../../../api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewaySecret.kt#L18-L34)）与 loopback 绑定（[DshGatewayServer.kt:92-98](../../../api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewayServer.kt#L92-L98)）是可复用先例，但不是设备桥。
- **G10b 没有宿主侧授权/回放合同。** 文件选择、下载、通知等目前是页面级交互，没有“哪个插件请求了哪个能力、用户授权了什么、如何撤销、重复/未知结果怎么处理”的模型；也没有和 DSH 审批/会话身份绑定。
- **G11 同 UID 不是隔离边界。** `:dsh` 进程只隔离崩溃和部分生命周期（[DshRuntimeService.kt:29-40](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeService.kt#L29-L40)）；桥或插件不是 OS 安全边界。若未来要求不可信插件隔离，需要额外 UID/独立 runtime APK 等新决策，不能靠“有桥”宣称隔离。

**运行时限制**：桥、能力发现、授权、撤销、回放语义全部 `NOT_RUN`（未实现）；SAF/下载的实际真机行为也是 `NOT_RUN`（R07 E1–E9 未执行）。

## 3. 差距明细汇总

| ID | 所属点 | 精确符号/位置 | 源码事实 | 差距 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| G1 | 运行时/用户数据 | `DshRuntimeOwner.startInternal` 的 `workingDir = config.payloadDir`；`DshNodeCommand.environment` 的 `DSH_WORKSPACE`；固定内核 `dsh-working-directory` | 进程 cwd = payload 树；官方默认工作目录 = `process.cwd()`；`DSH_WORKSPACE` 无消费者 | 用户/工具默认写入受管 payload 树，换树即删；`workspace/` 名存实亡 | **P0** |
| G2 | 运行时/用户数据 | `DshGatewayConfigSeeder.seedProfileTemplate/copyFile`；`DshRuntimeConfig` | home 缺失即静默重建；模板直接覆盖写、只写缺失文件、无版本/迁移 | 数据丢失被当成正常首启；半截模板永久保留；payload 升级不更新 profile | **P0** |
| G3 | 启动状态 | `DshReadinessGate.probeOnce`；`DshFailureCode`；`Outcome.Ready.cookie` | 12+ 种探针失败 → `LOADER_NOT_READY`；`AUTH_ENTRY_MISSING` 不可达；cookie 丢弃 | 无法区分认证/loader/服务/资源失败；认证证据不跨 IPC | **P0** |
| G4 | 启动状态 | `DshRuntimeOwner.startExitWatcher`；`DshRuntimeServiceClient.stop` | READY 后只有进程退出监视；stop 回复被丢弃并直接发布 STOPPED | 无持续健康态；stop fence 可能被 UI 误报为已停 | **P0** |
| G5 | 启动状态 | `DshWebViewClient.onReceivedHttpError` → `DshWorkspaceViewModel.onHttpUnauthorized/retry` | 401/403 → `requiresRestart=true` → 整代 restart | 认证/页面异常触发宿主重启，可能中断在途工作 | **P0** |
| G6 | 启动状态/插件 | `DshReadinessGate` 注释与 marker 检查；固定内核 `dsh-host-webserver` | marker 是 host boot deferred；gate 只查 bootstrap 资源 | “插件已激活”未被验证；插件激活失败没有状态 | P1 |
| G7 | 候选试运行 | `DshPayloadInstaller.install` 换树/删旧树；`DshRuntimeOwner` 启动顺序 | 先换树再真跑；旧树立即删除；无 trial home/端口/nonce；只有 6 个关键文件 hash | 候选失败无法回退；非关键文件只能靠真跑暴露 | **P1** |
| G8 | 候选试运行 | `AssetDshPayloadSource`；无下载/更新类 | 只有 APK assets 来源；无更新事务 | 无在线候选/回滚路径；“候选试运行”只适用于 APK 升级或未来受管更新器 | P1 |
| G9 | 官方插件管理器 | `TOOL_LINKS`；`payload/bin/pnpm`；官方 `dsh-plugin-manager` | pnpm 是脚本包装器、未镜像、不可执行；官方 `execa("pnpm")` | 插件安装路径没有可用 pnpm；无插件事实/边界/宿主策略 | **P1** |
| G10 | 设备能力桥 | `DshGatewayServer` 路由；`DshWebChromeClient`；全仓无 WebMessage bridge | 只有模型网关 + WebView 浏览器能力 | 无设备能力协议、授权、撤销、幂等/不重放 | **P2** |
| G11 | 安全边界 | `DshRuntimeService` 注释；ADR 0003 §4 | 同 UID 普通进程；不宣称隔离 | 桥/插件不是安全边界；不可信插件隔离需新 ADR | P2 |

## 4. P0 / P1 / P2 实施顺序

排序原则：先修“会丢数据/说假话”的现有行为，再修更新/插件生态的安全门，最后加新能力。每一项都必须能独立回退；不允许把多个 owner/数据格式/视觉变化混在一个切片里（硬锁 §13）。

| 顺序 | 差距 | 最小切片（建议） | 退出条件（可验证） | 验证层级 | 回退 |
| --- | --- | --- | --- | --- | --- |
| **P0-1** | G1 | 把 `DshNodeHost.start` 与 `DshNodeExecStrategy.resolve/probeFailureDetail` 的 `workingDir` 改为 `config.workspaceDir`；若官方工具需要 payload 内的相对路径，显式注入绝对路径而不是改 cwd；为 workspace 增加存在性/可写性检查。 | 新代 `process.cwd()` = `files/dsh/workspace`；默认工作目录下写入的文件不再进入 `payload/`；换 payload 不影响这些文件。 | JVM 单测（参数/路径）+ 真机一次文件落点检查（`NOT_RUN` 现在） | 把 `workingDir` 改回 `payloadDir` 即恢复；不影响 payload 安装格式。 |
| **P0-2** | G2 | ① home 缺失/不可读时区分“首次”与“曾存在但不可读”，后者 fail closed 并给可诊断失败码；② seed 改为 temp+fsync+rename；③ 记录 `profileTemplateVersion`，payload 升级时按版本迁移而不是只补缺失文件。 | 删除/损坏 home 不再静默生成空库；复制中断后下次能修复；模板版本可追踪。 | JVM 单测（缺失/半截/版本迁移）+ 真机故障注入（`NOT_RUN`） | 保留旧 seed 路径作为开关；数据不迁移时只读不写。 |
| **P0-3** | G3/G4/G5 | ① 给 readiness 子步骤增加可区分失败码/证据（auth-exchange、index、bootstrap、unauth、stability）；② 把 gate 已算出的 cookie/认证证据通过窄类型 IPC 暴露给页面侧（token 仍不落盘），快照增加 READY 后的健康态（至少 listening/auth/http 三态），用低频探针；③ 401/403 先尝试一次 cookie 刷新/页面重载，不直接重启整代；④ `stop()` 投影 service 的真实 stop 结果。 | 失败码能定位到具体证据；READY 后服务坏掉能被 UI 看到；401 不自动产生新 epoch；stop fence 不被误报 STOPPED。 | JVM 单测（gate/ViewModel/stop 投影）+ 真机 401/后台/停止（`NOT_RUN`） | 保留现有五步 gate 不删；新增证据为附加字段；401 策略可切回旧路径。 |
| **P1-1** | G7 | 在 `DshPayloadInstaller` 之外加 candidate 树 + trial home（独立 `dataRoot/trial`）+ 随机端口 + 真实 gateway endpoint；试运行只做：303→cookie、index marker、bootstrap JS、一次 storage 写入/重开；不派发业务工具；停止并确认 trial 树无残留后才 rename 提交。 | 坏候选在提交前失败，旧 `payload/` 不变；健康结果与 `archiveSha256` 绑定；trial 不碰用户 home。 | JVM 单测（安装事务/失败回滚）+ 真机（`NOT_RUN`） | 保留现有 `install()` 作为 fallback；trial 代码路径可删。 |
| **P1-2** | G8 | 不新增下载器。把“候选”明确为 APK 内 payload 版本；在升级流程中复用 P1-1；若要在线更新，另立 ADR。 | 文档/UI 不宣称在线更新；APK 升级时旧/新 payload 至少有一个可回退。 | 静态/脚本检查（`NOT_RUN`） | 仅文档与 gate；无数据格式变化。 |
| **P1-3** | G9 | 先解决 pnpm 执行：为 pnpm 提供 nativeLibraryDir 链接或经 `node pnpm.cjs` 的受控 shim（不能直接执行 app-home 脚本）；再记录插件事实清单；只验证纯 JS 插件；安装/审批/回滚仍走官方机制；建立受管/用户插件目录边界。 | `pnpm --version` 在宿主环境可运行；纯 JS 插件安装→启用→禁用→卸载成功；插件目录可区分受管/用户。 | 真机（`NOT_RUN`）+ 静态事实清单 | 不启用插件菜单；保留官方默认；删除 shim 即回退。 |
| **P1-4** | G6 | 在 gate 之后增加 client graph/插件激活证据（读 `__DSH_BOOT__` manifest、检查 batches/entry URL、记录失败插件），失败不升级为“页面就绪”。 | READY 快照不再声称插件已激活；插件失败有独立状态/诊断。 | JVM fixture + 真机（`NOT_RUN`） | 只加证据字段；不改变 READY 判据。 |
| **P2-1** | G10 | 用官方 Cordis 插件注册 DSH 侧能力；Android 侧提供窄 typed port + loopback 路由 + header bearer（复用 `DshGatewaySecret` 常量时间比较）；能力发现、逐项授权、撤销、调用 id、幂等/未知结果不重放；首批只做 SAF 文件选择/保存、分享、通知、外链。 | 插件能发现能力；未授权调用被拒；撤销生效；重复调用幂等；未知结果不自动重放。 | 协议单测 + 真机（`NOT_RUN`） | 关闭路由/插件行即回退；不触碰模型网关。 |
| **P2-2** | G11 | 先做威胁模型决策：若只支持受信任插件，明确写入文档；若要求不可信插件隔离，另立 ADR（额外 UID/独立 runtime APK），不在本切片实现。 | 文档/UI 不宣称隔离；桥和插件边界有明确结论。 | 文档评审（不做运行时） | 决策本身可回退；不改变现有权限默认。 |

**依赖说明**：P1-1 依赖 P0-1（独立的 trial/workspace 根）与 P0-3（可区分证据）；P1-3 依赖 P0-2（受管/用户边界）与 P1-1（安装前试运行）；P2-1 依赖 P1-3（插件注册路径）与 P2-2（安全结论）。

## 5. NOT_RUN 与运行时限制

### 5.1 本次审计

| 项目 | 结果 | 说明 |
| --- | --- | --- |
| 编译 / 单测 / 仪器测试 | **NOT_RUN** | 未运行 Gradle，未执行任何测试任务，未读测试 XML。 |
| APK 构建/安装/启动 | **NOT_RUN** | 未构建、未安装、未启动、未杀进程、未抓 logcat。 |
| 真机前端/认证/WebSocket/插件 | **NOT_RUN** | 无设备证据；R07 E1–E9 全部 `NOT_RUN`。 |
| 默认工作目录/数据落点 | **NOT_RUN** | G1 是静态代码路径，未在设备上复现。 |
| 候选试运行/回退 | **NOT_RUN** | 功能不存在。 |
| 插件安装/启用/卸载 | **NOT_RUN** | 功能路径被 pnpm 事实阻断；未在设备上验证。 |
| 能力桥/授权/撤销 | **NOT_RUN** | 功能不存在。 |
| 性能/包体/内存 | **NOT_RUN** | 未测量。 |
| 外部项目（DSHA 等）运行 | **NOT_RUN** | 未安装、未执行、未构建。 |

### 5.2 继承的运行时限制（R07 `KNOWN-LIMITS`，不因本文改变）

| ID | 限制 | 当前状态 | 对本文五点的影响 |
| --- | --- | --- | --- |
| LIMIT-PTY | 真 PTY | **FAIL** | G10 的终端类能力不能宣称可用；桥不解决 PTY。 |
| LIMIT-SANDBOX | Android sandbox | fail-closed / `workspace-write` | G1 的 workspace 不是 OS 沙箱；G11 不能宣称隔离。 |
| LIMIT-ISOLATION | 真隔离 | 不宣称 | G10/G11：桥/插件不是安全边界。 |
| LIMIT-MODEL-REFRESH | 模型启动后刷新 | 未验证 | 与 G3/G4 的状态模型无关；不要把“页面 READY”当模型刷新完成。 |
| LIMIT-SHARP | 原生 sharp | 不宣称 | 插件 native addon 兼容不能由 G9 的纯 JS 通过外推。 |
| LIMIT-OFFLINE | 全离线 | 不宣称 | G10 的设备桥/通知等不改变网络依赖事实。 |

### 5.3 源码级运行约束（本次核对）

- `targetSdk 36`、`minSdk 26`、仅 `arm64-v8a`（[libs.versions.toml](../../../gradle/libs.versions.toml#L5-L7)、[DshAndroidRuntimeAssembly.kt:89](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt#L89)）。
- 可执行 ELF 从 `nativeLibraryDir` 执行；可写目录 exec 被禁止；`bash` 被替换为 `/system/bin/sh` 符号链接，且明确未复现原 shim 的 rc/env（[DshAndroidRuntimeAssembly.kt:110-151](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt#L110-L151)）。
- 只有 node/rg/curl/git/git-remote-http(s)/python3.14 被镜像为 `lib*.so`；npm/pnpm 等包装脚本不可执行（[scripts/dsh/README.md:86-89](../../../scripts/dsh/README.md#L86-L89)）。
- 单 APK、同 UID；`:dsh` 是普通 Service 进程，不是 OS 安全沙箱（[DshRuntimeService.kt:29-40](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeService.kt#L29-L40)）。
- payload 来自第三方参考 APK（`woaiys3` v1.20.1），不是 MCA 自构建；供应链义务与字节来源见 [scripts/dsh/README.md:7-23](../../../scripts/dsh/README.md#L7-L23)。
- 没有在线更新/下载路径；payload 只来自 APK assets（[DshAndroidRuntimeAssembly.kt:43-55](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt#L43-L55)）。

## 6. 报告主张逐条核对（防重复陈旧文档）

| 报告主张 | 源码核对 | 结论 |
| --- | --- | --- |
| `core/dsh-runtime` 已有 owner、service、epoch、进程树、readiness（§5） | `DshRuntimeOwner`、`DshRuntimeStateMachine`、`AndroidDshNodeHost`、`DshReadinessGate` 均存在 | **静态成立**；但证据模型不完整（G3–G6）。 |
| payload 已有 staging、摘要核验、目录切换（§5） | `DshPayloadInstaller` 有 staging/hash/rename/marker | **静态成立**；缺试运行与提交后回退（G7/G8）。 |
| DSH home 与 payload 已分开（§5） | 目录分开成立，但 cwd 指向 payload，`workspace/` 未生效 | **部分成立**：作为目录布局成立，作为运行时数据边界不成立（G1）。 |
| 手机工具目前主要是宿主和模型接入（§5） | 无设备桥；只有 WebView SAF/下载与模型网关 | **静态成立**；桥未实现（G10）。 |
| 官方 pluginManager 可复用（§4.2） | 官方行存在且启用；`execa("pnpm")`；MCA 未接；pnpm 不可执行 | **方向成立，当前路径被阻断**（G9）。 |
| 数据不可读时拒绝静默创建空库（§3.6，DSHA 对照） | MCA 在 home 缺失时静默 `mkdirs` + 复制 | **不成立**（G2）。 |
| 隔离试运行/健康提交（§3.6、§6 P3） | 无 trial/健康记录/回退 | **未实现**（G7）。 |
| MCA gate 比 DSHA 更强，不应降级（§3.3） | 五步 gate 仍在；401 修复在 [LoopbackHttpProbe.kt:46-50](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/LoopbackHttpProbe.kt#L46-L50) 用 `errorStream` 保留真实状态 | **静态成立**；应保留，不采纳 DSHA 的“打印 URL 即 READY”。 |
| `bash` shim 只指向 `/system/bin/sh`，未重建 rc/env（§5） | 与 [DshAndroidRuntimeAssembly.kt:126-136](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt#L126-L136) 一致 | **静态成立**。 |
| 本地模型 `nativeToolCalling=false`（§5） | `DshModelCatalog` 对本地模型固定 false；`McaDshGatewayBackend` 注释一致 | **静态成立**（[DshModelCatalog.kt:57-58](../../../app/src/main/java/com/muyuchat/mca/dsh/DshModelCatalog.kt#L57-L58)）。 |
| 报告基线 `f39e8fc9…`（§2） | 当前 HEAD `7475fd6…`；DSH 全模块未跟踪 | **报告基线已旧**；所有 DSH 结论只能绑定工作区，不能绑定 commit。 |

## 7. 交付证据（按硬锁 §16）

- 任务 / 范围：只读审计报告五点 → 当前 MCA DSH 实现；产出本矩阵。
- 起点 revision + 既有工作区 / 子模块差异：HEAD `7475fd640e21154dd9543bcae2c83ab112e30361`；44 ` M` + 37 `??`；DSH 模块全部未跟踪；三个 native 子模块指针见 §1.1。
- 改动文件与职责：仅本文件。无源码、构建、资源、测试或脚本改动。
- 功能 / 数据 / 协议 / 视觉影响：无。本文不改变默认值、数据格式、HTTP/SSE、WebView 设置或视觉。
- owner、状态与资源边界：无变化；本文只记录 `DshRuntimeOwner` / `DshRuntimeService` / `DshGatewayServer` 的现状。
- 检查命令、结果、原始证据位置：未运行构建/测试/设备命令；只做了源码与固定载荷的只读核对。运行时结果全部 `NOT_RUN`（§5）。
- NOT_RUN / BLOCKED / 既有问题 / 新增问题：见 §5；新增问题是 G1–G11 的静态差距，不是新运行时故障。
- 临时路径与例外：无临时路径；无例外申请。
- 回退点 / 下一切片：删除本文件即可回退（无其他改动）。下一切片建议按 §4 的 P0-1（G1）开始；若用户只授权文档，则本文件即为终点。

## 附录 A：关键符号索引

| 主题 | 符号 | 位置 |
| --- | --- | --- |
| 宿主契约 | `DshRuntimePhase` / `DshRuntimeSnapshot` / `DshRuntimeClient` | [DshRuntimeContract.kt](../../../core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/DshRuntimeContract.kt) |
| 宿主 owner | `DshRuntimeOwner.startInternal/stopInternal/fail` | [DshRuntimeOwner.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt) |
| 状态机 | `DshRuntimeStateMachine` | [DshRuntimeStateMachine.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/state/DshRuntimeStateMachine.kt) |
| 就绪门 | `DshReadinessGate.awaitReady/probeOnce` | [DshReadinessGate.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/DshReadinessGate.kt) |
| HTTP 探针 | `DshHttpProbe` / `LoopbackHttpProbe` | [DshHttpProbe.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/DshHttpProbe.kt)、[LoopbackHttpProbe.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/LoopbackHttpProbe.kt) |
| Android 装配 | `DshAndroidRuntimeAssembly` / `TOOL_LINKS` / `prepareRuntimeLinks` | [DshAndroidRuntimeAssembly.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt) |
| 前台服务 | `DshRuntimeService` / `DshRuntimeNotifications` | [DshRuntimeService.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeService.kt)、[DshRuntimeNotifications.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeNotifications.kt) |
| 进程与命令 | `DshNodeCommand.environment` / `AndroidDshNodeHost.start` | [DshNodeCommand.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt)、[AndroidDshNodeHost.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/AndroidDshNodeHost.kt) |
| 配置与 seed | `DshRuntimeConfig` / `DshGatewayConfigSeeder` | [DshRuntimeConfig.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshRuntimeConfig.kt)、[DshGatewayConfigSeeder.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt) |
| 安装/校验 | `DshPayloadInstaller` / `DshPayloadManifest` | [DshPayloadInstaller.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadInstaller.kt)、[DshPayloadManifest.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadManifest.kt) |
| 页面状态 | `DshWorkspaceUiState` / `DshPageUiState` / `DshFailureProjectionMapper` | [DshWorkspaceViewModel.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceViewModel.kt)、[DshWebHostCallbacks.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWebHostCallbacks.kt)、[DshFailureProjection.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshFailureProjection.kt) |
| WebView 边界 | `DshOrigin` / `DshWebViewClient` / `DshWebViewFactory` / `DshWebChromeClient` | [DshOrigin.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshOrigin.kt)、[DshWebViewClient.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewClient.kt)、[DshWebViewFactory.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewFactory.kt)、[DshWebChromeClient.kt](../../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebChromeClient.kt) |
| 模型网关 | `DshGatewayServer` / `DshGatewaySecret` / `DshModeAssembly` | [DshGatewayServer.kt](../../../api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewayServer.kt)、[DshGatewaySecret.kt](../../../api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewaySecret.kt)、[DshModeAssembly.kt](../../../app/src/main/java/com/muyuchat/mca/composition/DshModeAssembly.kt) |
| 模型目录 | `DshModelCatalog` / `McaDshGatewayBackend.catalog` | [DshModelCatalog.kt](../../../app/src/main/java/com/muyuchat/mca/dsh/DshModelCatalog.kt)、[McaDshGatewayBackend.kt](../../../app/src/main/java/com/muyuchat/mca/dsh/McaDshGatewayBackend.kt) |
| 固定内核（本地审计副本，git-ignored） | `dsh-app-boot` / `dsh-host-webserver` / `dsh-working-directory` / `dsh-plugin-manager` / `payload/bin/pnpm` | `E:\tmp\dsh-runtime\payload\...`；字节身份见 `MANIFEST.json` / `payload-lock.json` |

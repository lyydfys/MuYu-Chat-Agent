# DSHA 与多个 DSH Android 项目的实现研究及 MCA 借鉴建议

研究日期：2026-10-19。方式：GitHub 检索、固定提交源码审查、与 MCA 当前源码对照。本次不修改生产代码，不构建、安装或运行外部项目。

## 1. 结论

**可以借鉴，DSH-APP/DSHA 尤其值得参考，但应按能力吸收，不整体移植其应用架构。**

最符合“DSHA”名称的候选是 [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA)，与此前的 `woaiys3/deepseek-harness-android-app` 是不同项目。搜索还发现多个同名或近似项目；用户尚未确认具体作者，因此本文同时比较五种实现，不把候选自动当作唯一指代。

对 MCA 的建议分为两步：

1. 保留现有“独立 DSH 页面 + 单一 DSH 宿主 owner + MCA 模型网关”，优先吸收启动就绪、代次隔离、诊断、插件扩展和更新事务。
2. 若目标包含大量依赖 GNU Bash、glibc 原生扩展、编译器和系统包的桌面插件，再单独验证 DSHA 式 Linux 用户空间；不要为修复一个页面入口立即替换现有 runtime。

运行环境本地化、模型推理本地化、工具依赖离线化是三件事。DSHA 解决了第一件及一部分第三件；MCA 的价值在于通过已有模型 owner 补齐第二件。任何项目的“手机本地运行”描述都不能直接证明主模型完全离线或全部桌面插件兼容。

## 2. 审查基线与证据限制

| 项目 | 固定提交 | 路线 | 项目许可初查 |
| --- | --- | --- | --- |
| [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) | `d5b40935f86f360f6634cfdbc4a2f780a9af9ae3` | Ubuntu/glibc + bxroot/proot + Node + 官方 Web 前端及移动插件 | 自有代码 MIT；依赖另核 |
| [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android) | `7ad2446883f28f7da25ff5b8a74aed065458c9c4` | Android bionic Node 独立进程 + 官方 Web profile | MIT；依赖另核 |
| [pht88888888/DSHAPP](https://github.com/pht88888888/DSHAPP) | `c5cd3ccbf942500312fc36e43e3de58308be6fc9` | 内置 Termux 用户空间 + Node + 官方 Web GUI | MIT；依赖另核 |
| [Chiyang001/DSHAN](https://github.com/Chiyang001/DSHAN) | `25def52f2d64c4746490b26817188c1d21e591f4` | nodejs-mobile/libnode + JNI + 手机工具适配 | 未找到项目级 LICENSE/COPYING，不默认可复制 |
| [SingalongDan/dsha](https://github.com/SingalongDan/dsha) | `5b8fee5968c1b4bb8a98be90affd0c96030d6d82` | 本地 Node 后端 + 原生 Jetpack Compose 前端 | AGPL-3.0，须单独评估复用义务 |

MCA 本轮只读检查起点 HEAD 为 `f39e8fc9724523f8ae32d0c3ae540d14d62215cd`，同时存在大量未提交和未跟踪的 DSH/Agent/前端改动；三个 native 子模块有既有内部差异。本文不代表某个干净提交的构建结果。

DSHA 主分支已同步 build173 源码，但其 README 的公开 APK 仍指向 build170。[build173 说明](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/docs/source-updates/build173.md#L14) 明确既有预编译资产不能代表当前源码的最终包。不能混用源码机制、旧 APK 和历史性能报告来证明同一版本已经通过。

## 3. DSHA 实际如何工作

### 3.1 它保留官方 DSH，在 Android 外围建立宿主

```text
Android 原生启动/配置/诊断页
  → 应用级生命周期 controller + 前台服务
  → 安装、校验 Ubuntu 与受管 DSH 资产
  → bxroot（默认）或符合条件的 proot 兼容路径
  → Ubuntu 用户空间内的 Node + 官方 DSH Web profile
  → 本机 HTTP/认证/WebSocket
  → Android WebView（标准版）或 GeckoView（兼容版）

DSH 官方插件 / DSHA 手机插件
  → 带 token 的本机设备桥
  → Android 文件选择、通知、无障碍、ADB 等已授权能力
```

这条路线使 DSH 的插件加载器、会话和 Agent 循环继续由官方实现负责。Android 层负责环境、进程、页面承载和手机能力，不需要把整个循环重写成 Java/Kotlin。

Ubuntu 在这里是 Linux 用户空间，不是启动另一个 Linux 内核，也不是获得 Android root。

### 3.2 当前已经不是旧 proroot 方案

[ContainerRuntime.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.java#L10) 定义当前 bxroot/proot 路线；旧 proroot 被明确退休。

[BxrootRuntime.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/runtime/BxrootRuntime.java#L10) 使用安装器管理的 nativeLibraryDir 中三个固定 native 组件，启动前逐件校验摘要。[上游锁](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/tools/bxroot/lock.json) 固定来源和补丁。

其 [app/build.gradle](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/build.gradle#L20) 当前 targetSdk 为 37，说明它的设计没有单纯依赖低 target 的可写目录执行路径。**这只是源码设计事实，不是我们已经独立验证它在 MCA target36 下可运行。**

借鉴重点是实际执行探针、资产闭包、明确失败后的有限回退；不是照抄一个 runtime 名字或“零开销”说法。

### 3.3 网页就绪和宿主存活分别建模

[WebHealthProbe.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/util/WebHealthProbe.java#L12) 区分 LISTENING、AUTH_EXPIRED、INVALID_RESPONSE、HTTP_READY 等状态；页面或认证异常本身不授权重启 Node。

[DshAuthSession.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/util/DshAuthSession.java#L113) 检查官方 303、干净重定向、认证 cookie，再进行第二次带 cookie 的首页请求，避免“拿到 cookie 就算页面成功”。

[WebPreviewActivity.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java#L687) 等待异步 `setCookie` 回调完成且页面代次仍有效后才导航；同时限制文件访问、第三方 cookie 和外部页面能力。

[WebLifecycleController.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java#L56) 将认证 URL、端口和回调绑定到当前 generation/instance，不允许旧启动输出写入新运行。

MCA 已有 epoch、readiness gate 和 WebView owner，应在这些既有位置补齐行为，不再复制一套 DSHA controller。

### 3.4 移动端适配主要通过插件扩展

其 [builtin-plugins.json](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/assets/builtin-plugins.json#L13) 将手机通知、状态展示、Computer Use、虚拟屏等注册为插件，保留官方基础 bundles。

移动界面使用 [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)。DSHA 固定的 [package.json](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/assets/builtin-plugins/dsh-web-mobile/package.json) 记录 3.1.0、上游 commit、构建摘要以及 `dsh.client` 注入声明。

这比长期修改上游生成后的前端 bundle 更适合维护。但该插件并非只有 CSS：宿主部分包含会话删除路由、响应压缩和推理选项默认值填充等逻辑。MCA 若引入，必须核对会话操作、认证和默认值影响，不能当作无行为风险的皮肤。

### 3.5 手机能力通过独立桥提供

[HttpShellService.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/HttpShellService.java#L671) 先解析受限请求并认证，再按路由提供设备操作。其 [BridgeCredentialAuth.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/util/BridgeCredentialAuth.java#L9) 将 token 放在请求头，拒绝 URL token 并进行常量时间比较。

这与 MCA 模型网关是两个职责：模型网关处理推理，设备桥处理手机操作。后续可通过官方 DSH 插件调用 MCA 的窄能力，避免插件直接依赖 Activity、MainViewModel 或全局 UI store。

可以吸收能力发现、单独授权、撤销和结果未知时不重放的规则，不需要默认引入 ADB、root、全文件访问或虚拟屏。

### 3.6 更新与用户数据分开管理

[ManagedRuntimeLayout.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/util/ManagedRuntimeLayout.java#L6) 列出 APK 拥有的引擎、内置插件和脚本，明确排除用户会话、项目和第三方插件。

[UserDataLayout.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/backup/UserDataLayout.java#L74) 将稳定数据根映射到 DSH home，数据不可读时拒绝静默创建一个空库。

[RuntimeTrial.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java#L234) 在隔离试运行中验证鉴权、随机 nonce/端口、存储与会话重开、浏览器 renderer。[ManagedRuntimeTransaction.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/backup/ManagedRuntimeTransaction.java#L125) 再将健康证据绑定到实际内容与更新事务。

这部分比“下载成功后直接覆盖 node_modules”更值得 MCA 吸收。二进制回退与用户数据格式回退仍需分别设计。

### 3.7 重启回退不重放未知业务

[WebRuntimeFallback.java](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/app/src/main/java/com/deepseekharness/app/util/WebRuntimeFallback.java#L17) 仅在明确引擎失败、旧树已退出、未派发业务、尚未重试、没有插件故障等条件全部满足时单次回退。

MCA 应保留 requestId/epoch/lease 与真实停止屏障，吸收这一规则。不能把模型超时、网页卡顿或“结果未知”统一处理成重启再执行，这可能重复文件写入或外部操作。

## 4. 其他实现提供的互补经验

### 4.1 Soodok：轻量原生 Node，但修改清单需要审查

其 [targetSdk=28](https://github.com/Soodok/Deepseek-Harness-Local-Android/blob/7ad2446883f28f7da25ff5b8a74aed065458c9c4/app/build.gradle.kts#L20) 用于支持私有目录执行 Node；MCA 不能照搬这个前提。

值得借鉴：用 [官方插件 CLI](https://github.com/Soodok/Deepseek-Harness-Local-Android/blob/7ad2446883f28f7da25ff5b8a74aed065458c9c4/app/src/main/java/app/dsh/mobile/engine/PluginCenter.kt#L169) 安装插件、本代日志提取认证入口、带 bootId 的插件健康标记和可选客户端插件。

不能直接搬用：其 [运行时收集脚本](https://github.com/Soodok/Deepseek-Harness-Local-Android/blob/7ad2446883f28f7da25ff5b8a74aed065458c9c4/scripts/collect-termux-runtime.sh#L197) 将文件独占锁改成直接成功；另有先检查再 rename 的发布路径，会削弱并发语义。需要按最终产物审查，不能因为最初出现 stub 就误判最终功能：PTY 占位在 [后续步骤](https://github.com/Soodok/Deepseek-Harness-Local-Android/blob/7ad2446883f28f7da25ff5b8a74aed065458c9c4/scripts/collect-termux-runtime.sh#L245) 会被 Android PTY 后端替换。本轮未运行该最终产物。

### 4.2 DSHAPP：内置 Termux 不等于要求用户另装 Termux

DSHAPP 将 Termux 用户空间、Node、官方 DSH 和 WebView 放在一个应用内，用户不需要自己敲安装命令。它与完整 Ubuntu 路线不同：主要面对 Android bionic 工具链。

其固定提交 [app/build.gradle.kts](https://github.com/pht88888888/DSHAPP/blob/c5cd3ccbf942500312fc36e43e3de58308be6fc9/app/build.gradle.kts#L29) 同样选择 targetSdk28。可参考运行环境准备、启动阶段、移动端插件 overlay 和工具兼容清单；不能通过降低 MCA target 来获得同样效果。

其中有两个具体做法很适合 MCA：

- [插件 ready 后继续等待真实首帧](https://github.com/pht88888888/DSHAPP/blob/c5cd3ccbf942500312fc36e43e3de58308be6fc9/app/src/main/java/com/dsh/android/MainActivity.kt#L2550)，而不是后端打印 URL 就收掉启动层。需要分别观察宿主启动、网页请求、插件激活和画面可见。
- [插件市场复用官方 pluginManager](https://github.com/pht88888888/DSHAPP/blob/c5cd3ccbf942500312fc36e43e3de58308be6fc9/market-plugin/dsh-android-market/lib/index.js#L20) 的检查、安装、审批与回滚。MCA 可以做目录与入口增强，执行和数据事务继续交给官方机制，避免在 Kotlin 再造一套插件安装器。

但其后端 Ready 主要来自 stdout 中的 URL，不能据此替换 MCA 更完整的 HTTP/认证/资源探针；其 engine 解包路径还存在先删除旧目录再 rename 的行为，也不能直接称为无损原子更新。

其大型 EngineManager/MainActivity 混合较多职责，不符合 MCA 按领域 owner、页面投影和装配分开的接入方式，不建议整文件移植。

### 4.3 DSHAN：JNI libnode 是另一条宿主路线，并非完整替代

其 [JNI 桥](https://github.com/Chiyang001/DSHAN/blob/25def52f2d64c4746490b26817188c1d21e591f4/app/src/main/cpp/dshbridge.cpp#L29) 调用 `node::Start()`，避免通过可写目录 exec Node，但同时修改进程全局环境、工作目录和标准输出。若在 MCA 使用这种方案，必须保持专属宿主进程，不能直接放入主进程。

其 [插件策略](https://github.com/Chiyang001/DSHAN/blob/25def52f2d64c4746490b26817188c1d21e591f4/runtime/android-plugin-policy.cjs#L2) 主动禁用 Bash/PowerShell、PTC/workflow、pnpm 插件管理器等部分能力，不能用于“DSH 全功能兼容”的承诺。

值得参考的是 [真实 flock 与原子不覆盖发布](https://github.com/Chiyang001/DSHAN/blob/25def52f2d64c4746490b26817188c1d21e591f4/app/src/main/cpp/flock.cpp#L27) 和内核分代激活/回退。尚未找到该 revision 项目级代码许可，因此当前只参考原理，不默认复制其自有代码。

### 4.4 SingalongDan/dsha：本地后端与原生界面可以分离

它的 [本地服务](https://github.com/SingalongDan/dsha/blob/5b8fee5968c1b4bb8a98be90affd0c96030d6d82/host-app/app/src/main/java/io/github/singalongdan/dsha/DshHostService.kt#L135) 启动 Node/DSH，前端使用 Compose，并不是必须依赖远端电脑的遥控器。[RPC 客户端](https://github.com/SingalongDan/dsha/blob/5b8fee5968c1b4bb8a98be90affd0c96030d6d82/host-app/app/src/main/java/io/github/singalongdan/dsha/DshApiClient.kt#L21) 和 WebSocket 流适配证明原生界面与官方后端可以通过协议解耦。

但是它自有代码采用 AGPL，且原生界面需持续跟进 DSH 的轨迹、审批和会话协议。对当前“使用官方前端”的 DSH 模式，参考价值低于 DSHA 的网页承载机制；可供后续独立 Kotlin Agent 的交互研究，不能顺手替换现有 DSH 前端。

它的 targetSdk 同为 28，NOTICE 记录的 DSH 为 `0.1.5-rc.2`；MCA 当前固定的 `0.2.1-rc.2` 必须重新核对 API/wire 协议，不能直接复制客户端后宣称兼容。

## 5. 与 MCA 当前实现的具体关系

| 能力 | MCA 当前源码事实 | 推荐借鉴动作 |
| --- | --- | --- |
| 独立入口 | 已有 DSH tab、Route 和侧栏动作；上一轮发现过 ChatViewModel 漏转回调 | 继续逐层验证动作包，不因更换 runtime 掩盖导航缺陷 |
| 宿主 owner | `core/dsh-runtime` 已有 owner、服务、epoch、进程树和 readiness | 吸收细分诊断/健康证据，保持一个权威 owner |
| 高 target 执行 | 现有脚本将 Node/部分工具放到 nativeLibraryDir，保持 target36 | 保留此约束；用真实产物验证而不是仅重命名文件 |
| 模型供应 | `api/dsh-gateway` 与 app adapter 对接本地/云端 | 继续复用 MCA 模型、凭据和推理 owner，不复制 DSHA 模型管理 |
| 更新安装 | payload 已有 staging、摘要核验和目录切换 | 增加隔离试运行与健康提交；不要再做一套下载器 |
| 数据归属 | DSH home 与 payload 已分开 | 明确受管与用户插件边界，验证更新与中断恢复 |
| 手机工具 | 现阶段主要是宿主和模型接入 | 后续用独立 DSH 插件 + 有类型设备能力桥接入，不耦合聊天内部 |
| 完整 Linux 工具 | 当前 Android 工具集合存在语义与包装限制 | 用具体插件需求评估可选 Linux runtime，不能声称已等价桌面 |

尤其需要注意 [DshAndroidRuntimeAssembly.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt)：当前源码将 bash shim 指向 `/system/bin/sh`，并明确未重建原 shim 的 rc/env 设置。这能解决路径执行问题，**不能保证 GNU Bash 语法、启动文件和桌面插件行为等价**。

[scripts/dsh/README.md](../../../scripts/dsh/README.md) 也明确记录 python/npm/pnpm 包装脚本在 target36 下的限制。DSHA Linux 路线对这些问题有参考价值，但不能用它的 README 代替 MCA 实测。

[McaDshGatewayBackend.kt](../../../app/src/main/java/com/muyuchat/mca/dsh/McaDshGatewayBackend.kt) 当前本地模型工具调用走显式 prompted 协议，并声明 `nativeToolCalling=false`。运行时换成 Ubuntu 不会自动提高模型的工具调用可靠性、上下文容量或结构化输出能力。

## 6. 建议分阶段吸收，保持可维护性

### P0：完成当前入口与启动链的验收

延续 [入口故障交接文档](../../repair/DSH_SIDEBAR_ENTRY_HANDOFF_AND_FIX_PLAN_20261019.md)，重新核对当前代码和已发生的并行修复，不沿用历史测试状态。验收覆盖：真实侧栏点击、空模型前端、官方认证、bootstrap 资源、页面与 WebSocket、失败提示、重复进入和返回。

DSHA 借鉴点放入已有 `DshReadinessGate`、ViewModel、诊断投影和测试；不增加第二个 app 级 controller。页面渲染故障不默认重启有活动工作的 Node。

### P1：插件与工具的兼容事实清单

每个代表插件至少记录：DSH API 版本、Node 版本、npm/系统依赖、原生模块 ABI、所需 shell、动态安装需求、权限、文件锁/原子写入、取消与产物结果。

分别验证纯 JS 插件、启动外部命令的插件、包含 native addon 的插件，以及实际需要 Linux 包管理器的插件。按依赖事实分类与运行测试，不按设备型号设置准入名单，也不因纯 JS 插件通过就声称任意插件通过。

移动端体验优先评估 `dsh-web-mobile` 的官方扩展方式；版本固定、默认关闭新增业务行为、对现有官方 DSH revision 做兼容回归后再采用。当前研究不授权改变 MCA 冻结视觉。

### P2：按真实需求验证可选 Linux 运行环境

若 P1 证明 Android 适配在 GNU 工具、glibc 或原生编译方面存在持续阻碍，单独做 Linux runtime 可行性切片：

1. 固定 bxroot/proot、Ubuntu、Node、DSH、基础工具和所有许可/摘要。
2. 在独立 DSH 数据根验证官方前端、认证、PTY、文件锁、原生插件和进程停止。
3. 比较相同模型与任务的包体、首次部署、总进程内存、功耗、启动和工具耗时，不使用历史宣传值。
4. 模型仍调用 MCA loopback 网关，不在 Linux 环境中再加载一套模型权重/引擎。
5. 运行时选择固定到任务/代次；活动任务中不无提示切换后端，不重放结果未知的操作。
6. 若没有实质兼容收益，保留现有实现，不强行引入第二环境。

这不是新增一个 DSH 业务内核。它只是同一个 DSH 宿主 owner 可选择的执行环境，前端入口、模型 API 和任务归属保持一致。

### P3：手机工具桥与受管更新

在现有模块职责上补齐手机能力的窄协议与任务身份，插件负责 DSH 一侧注册，Android 侧负责真实权限和执行终态。更新采用 candidate → 隔离真试运行 → 停止屏障 → 切换 → 健康确认 → 提交/回退，保留用户原件。

不整体复制其他项目的 MainActivity、EngineManager、自动保活体系、模型配置库或备份 owner；各项能力按最小写集、明确 owner 和独立验收分次交付。

## 7. 必须保留的限制说明

- **Ubuntu/proot 不是插件安全边界。** [DSHA 安全模型](https://github.com/DSH-APP/DSHA/blob/d5b40935f86f360f6634cfdbc4a2f780a9af9ae3/docs/security-model.md#L10) 明确同 UID 的插件可以访问该 UID 可访问的数据。MCA 的 `:dsh` 进程提供进程隔离，但不自动提供不同 UID 的文件隔离。如果需要对不可信任意插件提供强数据隔离，须另行设计不同 UID 等真实边界，不能靠路径约定或审批界面宣称做到。
- **完整插件生态需要执行依赖支持。** 官方插件加载器存在，不代表 Docker、桌面 GUI、特定 CPU 指令或所有 native addon 在手机可用。
- **项目许可不等于全部依赖许可。** MIT、AGPL、Ubuntu/Termux/proot/native runtime 和移植代码分别核对，不能整体按首页标记复制。
- **手机 Agent 本地运行不代表主模型离线。** 完全离线还需要主模型、标题/压缩等辅助模型、嵌入、检索和工具网络依赖逐项核对。只要仍调用云端模型，就不符合严格的全离线定义。
- **本次没有真机性能或兼容性结论。** 没有运行外部 APK、执行其构建或测量包体之外的运行资源，不能评定哪个项目“更成熟”或宣称 MCA 已超越它们。

## 8. 本次交付范围

已完成：检索同名与相关项目；固定五个源码 revision；审查 DSHA 启动、认证、WebView、Linux 运行时、数据和更新设计；对照其他路线及 MCA 当前实现提出分阶段建议。

未执行：生产代码修改、依赖升级、运行时替换、插件安装、Gradle/native 构建、APK 安装、真机运行或性能测试。只读外部源码保存在临时研究目录，未导入 MCA。

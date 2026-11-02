# DSH 左侧栏入口故障：交接文档与修复方案

记录日期：2026-10-19，Asia/Shanghai。范围：左侧栏点击 DSH 后无法进入官方前端。

**交付状态：诊断已形成，部分候选修复已写入工作区，尚未完成测试、构建和修复后的真机验收。用户最新要求仅交付本文；后续实现、构建和安装均未由本文自动启动。**

## 1. 接手者首先需要知道的结论

当前最直接的故障是 **`ChatViewModel` 重建 `ChatShellActions` 时漏转 `onOpenDshWorkspace`**。应用装配提供了正确的导航操作，侧栏也调用了这个操作，但真实渲染路径使用的是 ViewModel 重建后的动作包；遗漏字段落回默认空函数，因而点击只关闭侧栏，既不切页，也不启动 DSH。

补齐这个回调以后，启动链还存在三类需要验证的阻断：

1. 启动装配和配置种子逻辑原先拒绝空模型目录，导致未加载模型时无法先打开 DSH 界面。
2. HTTP 就绪探针对预期的 `401` 使用 `HttpURLConnection.inputStream`，会把正常的未认证响应转成异常和状态码 `-1`，阻止运行时进入 READY。
3. 已打开的 DSH 页面遇到外部运行时重启时，原页面状态逻辑可能停在 Starting，不能根据新 READY 恢复前端。

四项问题需要分别验证。导航修复不能证明 Node、官方插件资源和 WebView 已经能运行；网关测试通过也不能证明官方前端已经显示。

## 2. 产品与架构边界

本次修复沿用已经确定的产品关系：

- 左侧栏顺序保持“图片 → Agent → DSH”。
- Kotlin Agent 与 DSH 是两个独立页面和执行体系；保留现有 Kotlin Agent。
- DSH 使用官方前端与内核，MCA 提供 Android 宿主和模型网关。
- DSH 模型请求通过已有共享推理 owner 和资源仲裁执行；不增加第二个模型引擎。
- 未加载模型可以打开 DSH 界面。发起推理时仍必须检查真实模型能力，不能伪造模型或把无模型推理标成成功。

修复不需要新增产品页面、改变侧栏布局、重写聊天页、替换插件体系或修改现有会话格式。不得借此向 `MainUiState` 增加 DSH 业务状态，扩大 `MainCoordinatorHost`，或者让 feature 依赖 app/其他 feature 的内部实现。

接手前完整阅读 [MCA 项目硬锁](../architecture/MCA_PROJECT_HARD_LOCK.md) 和工作区适用的本地 Agent 指令。本文不覆盖这些约束。

## 3. 工作区基线与证据边界

### 3.1 源码并非一个干净提交

本轮诊断起点记录的 HEAD 为 `4436e520e0a42e718f311628d5db57cb300265e4`。22:07 左右重新检查时，其他并行任务已把 HEAD 更新到 `21f8b1b3f17a233bcfadf9c810465eb6e1c37de3`。本轮修复任务没有执行提交、推送或切换分支。

工作区包含大量既有 Agent、DSH、聊天、推理取消、Room 和前端改动；DSH 多个模块仍为 untracked。`git diff HEAD` 不能区分本轮修复与之前的开发，也不能覆盖未跟踪文件。接手时必须重新检查，不得整体回滚、清理或用旧分支覆盖。

子模块 revision 记录如下，revision 不能替代内部差异检查：

| 子模块 | revision |
| --- | --- |
| `third_party/llama.cpp` | `4ceb1719101f32637b841206c172f3f058ffc182` |
| `third_party/stable-diffusion.cpp` | `16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0` |
| `third_party/tokenizers-cpp` | `acbdc5a27ae01ba74cda756f94da698d40f11dfe` |

至少 tokenizers 存在未提交补丁；其他子模块也必须核对工作树与 index，不能仅根据父仓库状态判断干净。

### 3.2 已观察到的设备现象

诊断时有一台手机和一台平板通过 ADB 连接，两者均可打开现有 MCA 聊天页，尚未加载聊天模型。

手机上已实际复现：展开侧栏 → 点击有“打开 DSH 工作区”语义的 DSH 条目 → 侧栏关闭 → 仍停留在聊天页。随后未观察到对应 DSH 启动日志。这与遗漏导航回调的源码行为一致。

本轮修复前截图保存在工作区忽略产物目录：

- `artifacts/dsh-sidebar-fix/f9d98aa3-before.png`
- `artifacts/dsh-sidebar-fix/98a37aa7-before.png`

这些截图只证明修复前状态，不能充当修复后证据。平板尚未完成本轮 DSH 点击回归。

### 3.3 构建与 APK 状态

本轮没有运行候选修复的 Gradle 测试、APK 构建或安装。编写本文前观察到另一个任务持有构建锁，命令标记为 `lead: final release build`；该任务的产物、结束状态和是否纳入本轮全部改动需要另外核实，不能引用为本轮通过。

最后一次读取到的既有 APK：

| 路径 | 时间 | 用途限制 |
| --- | --- | --- |
| `app/build/outputs/apk/release/app-release.apk` | 2026-10-19 21:37:52 | 早于本轮候选修复，不能作为已修复安装包 |
| `app/build/outputs/apk/diagnostic/app-diagnostic.apk` | 2026-10-19 21:05:38 | 同上 |

时间只是快照；接手时应重新记录源码、APK SHA-256、版本、签名和设备安装时间。

## 4. 真实调用链与根因定位

### 4.1 导航链 N1：按钮操作被空函数替换

```text
MainChatScreenActions
  提供 onOpenDshWorkspace = { host.onTab(AppTab.DSH_WORKSPACE) }
        ↓ 作为 capabilities 注入
ChatRoute
  remember ChatViewModel → update → 使用 viewModel.screenActions
        ↓
ChatViewModel.buildScreenActions
  重建 ChatShellActions，原先遗漏 DSH 字段 ← 直接断点
        ↓
ChatScreen → ChatHistoryDrawerScreens → 用户点击 DSH
        ↓
应当切换 AppTab.DSH_WORKSPACE → MainActivity → DshWorkspaceRoute
```

源码入口：

- [MainChatScreenActions.kt](../../app/src/main/java/com/muyuchat/mca/MainChatScreenActions.kt)
- [ChatRoute.kt](../../feature/chat/src/main/java/com/muyuchat/feature/chat/ChatRoute.kt)
- [ChatViewModel.kt](../../feature/chat/src/main/java/com/muyuchat/feature/chat/ChatViewModel.kt)
- [ChatScreenActions.kt](../../feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreenActions.kt)
- [ChatScreen.kt](../../feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt)
- [MainActivity.kt](../../app/src/main/java/com/muyuchat/mca/MainActivity.kt)

`ChatShellActions.onOpenDshWorkspace` 的默认值是 `{}`。只检查侧栏与 `MainActivity` 两端会误以为已接通，必须检查中间重建动作包的路径。

候选修复：在现有 ViewModel 意图面增加 `openDshWorkspace()`，将 `onOpenDshWorkspace` 显式绑定到它，调用时读取当前 capabilities。不要再添加一层导航管理器或在 ViewModel 中创建 DSH runtime。

### 4.2 启动链 S1：空模型目录阻断前端

```text
DshWorkspaceRoute / ViewModel
  → DshRuntimeServiceClient.ensureStarted
  → DshGatewayAssembly.endpointProvider
  → authenticated /v1/models
  → DshGatewayConfigSeeder
  → DshRuntimeOwner → Node / 官方 Web profile
```

原有装配在 `catalog.isEmpty()` 时返回 null；Seeder 也将空模型列表视为失败。空目录与网关故障被混为一谈。

候选行为：

| 条件 | 正确处理 |
| --- | --- |
| 模型目录读取成功，列表为空 | 启动网关，`/v1/models` 返回 200 和空 data，继续启动 DSH |
| 空目录生成 provider overlay | 显式写 `models: []`，不能写成 YAML null |
| 模型目录无法读取、网关无法启动 | 返回真实失败；不能伪装成合法空列表 |
| 推理请求引用不存在的模型 | 保留 `404 / model_not_found`，不能进入 backend 推理 |
| 请求没有网关认证 | 保留 401 |

`DshGatewayServer` 已有空目录支持，候选修复主要去掉其上层错误门槛，不需要新建服务器。

空数组 overlay 仍需使用实际随包的官方 profile/schema 验证。仅凭 Kotlin 字符串正确，不能认定官方插件初始化成功。

### 4.3 就绪链 S2：401 被误判成网络失败

[LoopbackHttpProbe.kt](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/LoopbackHttpProbe.kt) 原先对 401 读取 `inputStream`，而 `HttpURLConnection` 会对 HTTP 错误状态抛异常。外层捕获后构造 code=-1，导致 [DshReadinessGate.kt](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/DshReadinessGate.kt) 最后的认证边界检查无法通过。

候选修复：所有 `code >= 400` 读取 `errorStream`；没有错误响应体时也保留真实状态码。继续保留超时、响应体大小上限、loopback 限制、不跟随重定向等行为。

完整 READY 条件必须继续保留：token 入口 303 → HttpOnly cookie → 带官方 boot marker 的 HTML → 可访问的 client bootstrap JS → 第二次稳定 HTML → 无认证访问 401。不能通过删掉最后一步、接受 -1 或只判断端口开放来修复。

### 4.4 页面恢复链 L1：外部重启后停在 Starting

[DshWorkspaceViewModel.kt](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceViewModel.kt) 原先在 STARTING 时清掉旧页面，但 READY 分支只处理仍是 Web 且 epoch 改变的情况。状态已经变成 Starting 后，无法恢复 Web。

候选修复由页面 ViewModel 继续负责宿主生命周期投影：

- 区分首次尚未请求启动的 STOPPED 与真实启动后退出。
- Starting 收到当前 READY 时加载当前代入口；收到 FAILED/STOPPED 时显示失败。
- 启动 job 与代次刷新 job 不竞争消费入口，同代只保留一个加载 owner。
- 新 epoch 取消旧代加载；IPC 返回后再次检查取消状态与当前 phase/epoch/origin。
- 旧代结果和异常不得覆盖新代页面。

退出页面继续只退出页面，不自动停止 Node 或取消已有 DSH 工作。

## 5. 已落盘改动台账

**本表表示文件中已有候选代码，不表示通过验收。接手者先审查现状，不要再次盲目套用补丁。**

| 文件 | 本轮候选变化 | 验证状态 |
| --- | --- | --- |
| `feature/chat/.../ChatViewModel.kt` | 补齐 DSH 意图与 screenActions 绑定 | NOT_RUN |
| `feature/chat/src/test/.../ChatWorkspaceNavigationTest.kt` | 2 项行为测试：DSH 与其他 Agent 入口隔离；更新 capabilities 后无旧回调 | NOT_RUN |
| `core/dsh-runtime/.../readiness/LoopbackHttpProbe.kt` | HTTP 错误统一读取 errorStream，保留 401 | NOT_RUN |
| `core/dsh-runtime/src/test/.../readiness/LoopbackHttpProbeTest.kt` | 3 项真实 loopback HTTP 回归，包含完整 readiness 序列 | NOT_RUN |
| `feature/dsh-workspace/.../DshWorkspaceViewModel.kt` | Starting 恢复、代次 owner、过期回调隔离 | NOT_RUN |
| `feature/dsh-workspace/src/test/.../DshWorkspaceViewModelTest.kt` | 9 项生命周期和竞争回归 | NOT_RUN |
| `feature/dsh-workspace/build.gradle.kts` | 复用版本目录中的 coroutines-test 依赖 | NOT_RUN |
| `app/.../composition/DshGatewayAssembly.kt` | 去掉空 catalog 拒绝；保留真实失败 | NOT_RUN |
| `app/src/test/.../dsh/DshGatewayAssemblyTest.kt` | 2 项真实 HTTP 装配测试：空目录/认证/不存在模型；同代模型目录变化 | NOT_RUN |
| `core/dsh-runtime/.../config/DshGatewayConfigSeeder.kt` | 允许合法空目录，生成 `models: []` | NOT_RUN |
| `core/dsh-runtime/.../service/DshRuntimeServiceClient.kt` | 网关不可用提示不再一律归因于未加载模型 | NOT_RUN |
| `core/dsh-contract/.../DshRuntimeContract.kt` | endpoint 契约说明允许空目录 | NOT_RUN |

上述新增测试合计 **16 项**，这是源码声明数，实际执行数量尚未核对。

`ChatViewModel` 与其他相关文件还包含此前已有改动；本轮不是其全部 git diff 的作者。`ChatScreen` 中先导航再关闭侧栏的调整在并行工作期间已经存在，不能未经核对归入本轮。

另外，忽略目录 `artifacts/dsh-sidebar-fix/run-locked.ps1` 中已有一个未执行的本地验证脚本。它不是新的规范构建入口，也不属于生产功能。接手者优先使用仓库已有构建锁和正式验证流程。

## 6. 明确尚未解决或验证的问题

### 6.1 官方前端实际运行仍未证实

本轮尚未在候选修复安装包上确认 Node 启动、官方 Loader 完成、WebSocket 接通、资源加载及 WebView 绘制。真正点击进入后，可能才会暴露 payload、Node 可执行文件、profile 路径、插件资源或平台兼容问题。仅针对实际日志中的故障处理，不预先更换整套 DSH 方案。

### 6.2 空目录后加载模型的刷新语义

网关 `/models` 每次读取当前 backend catalog，新增的装配测试拟验证其动态更新。但是 Seeder 只在运行时启动时生成 provider overlay，**不能据此推断官方 DSH 模型选择器会自动同步**。

需要核对官方 provider 的配置合并、模型发现和刷新协议。尤其注意：当前 `DshRuntimeOwner.restart()` 在宿主已经 READY 且仍存活时直接返回当前代。不能在方案中写“调用 restart 就会重新生成 overlay”。

后续处理顺序：

1. 优先验证官方已有模型发现/刷新能力，复用正式扩展点。
2. 若确实需要重新生成运行时配置，给出显式刷新命令与 owner 契约；不能静默杀掉有活动任务的宿主。
3. 用户明确接受中断且 owner 已确认停稳时，才可按 `stop → 确认终态 → ensureStarted` 重新播种；保留 DSH 会话和文件。

本轮入口故障的最低目标是空目录也能打开官方前端；模型变化后的可见性必须另外记录为已验证或待补，不得含糊宣称“模型接入全部完成”。

### 6.3 测试缺口

- ViewModel 现有 9 项测试使用 `StandardTestDispatcher`，尚未覆盖内联执行导致的同栈竞争。应补 `UnconfinedTestDispatcher` 同步发 READY 的测试；若测试框架具备真实 Main.immediate 环境，再补对应验证。
- Seeder 尚无本轮新增的空目录回归测试，需要覆盖最终 overlay 与真实配置解析，而非只匹配一个源码字符串。
- `DshRuntimeOwner.Deps.gatewayEndpoint()` 注释仍把 null 描述为“没有可用模型”，应在后续实现切片中与更新后的网关语义对齐；本次文档阶段不修改它。

## 7. 后续修复执行顺序

下列步骤仅供后续获得实施授权的接手者执行。当前任务停留在文档交付。

### 阶段 A：重建基线与审查已有候选代码

1. 完整阅读硬锁；读取当前 HEAD、tracked/untracked 差异、子模块 index/工作树差异及构建锁状态。
2. 记录本次要验证的文件内容哈希，确认没有其他任务正在编辑相同写集。
3. 对照第 5 节逐文件确认候选改动仍在，特别检查导航回调真实路径。
4. 保持已有本地模型、云端凭据、聊天/图片/Kotlin Agent/DSH 数据。不要清理数据或重建 DSH home。
5. 不从本轮旧 HEAD 重置当前工作区。发现并行任务已改同一处，先合并现有意图与最新接口。

阶段出口：能说明当前待测源码快照、各候选变化和与其他任务的边界。

### 阶段 B：收口候选修复并补关键测试

| 切片 | 负责层/写集 | 必须成立 | 禁止的捷径 |
| --- | --- | --- | --- |
| B1 导航 | ChatViewModel 与其行为测试 | 从 screenActions 调用能到 DSH，更新后不捕获旧 capability | 绕过 Route、点击中直接构造 runtime |
| B2 HTTP | Probe 与 HTTP 测试 | 401/403/404/500、空 body 均保留真实状态；READY 认证检查不缩水 | 关闭认证、只测 fake probe |
| B3 空目录 | 网关装配、Seeder、端点契约与相应测试 | 空目录前端可启动；损坏目录/认证失败仍失败 | 假模型、吞掉目录异常 |
| B4 生命周期 | DSH ViewModel 与测试 | Starting 有恢复/失败出口，旧代不能污染新代 | 新增应用级全局页面状态或第二 runtime |

Seeder 测试实现选择：优先在现有边界上用临时 profile 和真实小型认证 HTTP fixture 驱动 `seed`；若当前 JVM 环境无法执行 Android/JSON API，使用既有固定版本测试依赖或对应 instrumentation lane。只有确有隔离价值时才提取纯 overlay 构造器；不要为测试引入万能服务容器。

阶段出口：补丁范围仍限定于故障相关路径，无页面重设计、无执行 owner 迁移、无数据格式变化。

### 阶段 C：持构建锁执行验证

当前环境使用 JDK 21；SDK/QAIRT 路径从现有构建脚本与本地配置读取，不修改依赖版本和 ABI 范围来绕过失败。

先确认锁持有者是否仍存活。不得抢占、删除活锁或并行启动 Gradle/native 编译。下面是复用现有锁模块的单步执行模板，替换 `$gradleArguments` 后逐步执行：

```powershell
Set-Location E:\MAC
Import-Module .\tools\build-guards\McaBuildLock.psm1 -Force
Assert-McaBuildSlot | Out-Null
$gradleArguments = @(
    ':feature:chat:testDebugUnitTest',
    '--tests', 'com.muyuchat.feature.chat.ChatWorkspaceNavigationTest',
    '--console=plain'
)
$dshVerificationLock = Enter-McaBuildLock -Kind test -RetrySeconds 0 `
    -CommandLine ('DSH entry verification ' + ($gradleArguments -join ' '))
try {
    & .\gradlew.bat @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw 'DSH verification failed; inspect the actual Gradle output.' }
} finally {
    Exit-McaBuildLock -Lock $dshVerificationLock | Out-Null
}
```

每一步保存独立日志并核对 XML 中的实际执行数。不要把多个模块随意组合后使用一个不明确的 `--tests` 过滤器。

| 次序 | Gradle 任务/检查 | 验收内容 |
| --- | --- | --- |
| C1 | `:feature:chat:testDebugUnitTest --tests com.muyuchat.feature.chat.ChatWorkspaceNavigationTest` | 当前已有 2 项，不允许 tests=0 |
| C2 | `:core:dsh-runtime:testDebugUnitTest` | 包含新 Probe 测试及现有 Owner、认证、payload、readiness 回归 |
| C3 | `:feature:dsh-workspace:testDebugUnitTest` | 包含生命周期与新增内联调度测试，以及已有导航/页面策略回归 |
| C4 | `:api:dsh-gateway:testDebugUnitTest` | 原网关认证、路由、流式、取消等相关回归 |
| C5 | `:app:testDebugUnitTest --tests com.muyuchat.mca.dsh.DshGatewayAssemblyTest` | 当前已有 2 项，覆盖真实装配和 HTTP |
| C6 | `:feature:chat:testDebugUnitTest` 与 app 的 DSH 相关测试集合 | 检查补丁未损坏已有契约；过滤器须实际匹配 |
| C7 | `python tools/architecture/check_architecture_rules.py` | 只声明脚本实际覆盖的架构规则，不扩写为全仓证明 |

如果编译失败，先区分本轮改动与其他并行变化；修复最小相关范围，不删测试、降断言或放宽硬锁。测试通过后再构建安装包；源码发生新变化时重新选择必要检查。

### 阶段 D：构建和安装候选 APK

1. 与其他构建任务协调，使用同一个构建锁。可先使用现有 `:app:assembleDiagnostic` 做入口定位；需要正式包时执行受控 release 构建流程。
2. 当前 diagnostic 是 debuggable、非 R8 的诊断变体，配置为同 applicationId；不是正式发布产物。不能只凭名字认为它与正式包隔离。
3. 安装前确认候选 APK 的证书与设备既有包兼容，记录 APK SHA-256、版本和源码输入。
4. 使用原 applicationId 原位更新，保留用户数据；签名不匹配就停止该安装路径，不得卸载、清数据、改包名来制造升级通过。
5. 从真实 `MainActivity` 进入侧栏 DSH，不能以直开 loopback URL 替代导航验收。

### 阶段 E：真机入口与恢复验收

| 场景 | 操作 | 通过标准 |
| --- | --- | --- |
| E1 复现闭环 | 聊天页展开侧栏，点击 DSH | 离开聊天，进入 DSH 启动/前端；不再只关闭侧栏 |
| E2 无模型冷启动 | 不加载模型，首次进入 DSH | 官方前端可见且可操作；空模型状态真实；不出现伪造推理成功 |
| E3 官方资源就绪 | 等待首次加载 | boot/index、bootstrap JS、WebSocket 等实际依赖正常，不能只看宿主 READY |
| E4 认证 | 分别核对认证前和认证后访问 | 认证前 401，认证后可加载；入口 token 不进入日志、截图或持久化导航路径 |
| E5 重复进入 | 退出回聊天，再次进入，连续执行数次 | 复用现有宿主，不起第二 Node；页面可恢复，聊天仍可使用 |
| E6 后台恢复 | 前后台切换后返回 | 页面恢复；用户未取消的 DSH 工作不因切页被误杀 |
| E7 运行时失败/新代 | 使用受控故障注入或已有调试控制测试退出和新代 | 显示可重试失败；新 READY 恢复前端；旧代回调不覆盖 |
| E8 模式隔离 | 返回并分别打开 Kotlin Agent、DSH、智能调参 | 各自进入正确页面，已有 Kotlin Agent 不被替换 |
| E9 模型目录变化 | DSH 空目录启动后，在 MCA 加载模型再返回 | 记录官方 UI 是否能发现新模型；未验证则保留明确限制 |

E7 不得通过杀死整个 MCA 主进程影响正在进行的聊天/图片任务。若没有安全故障注入条件，应把该场景记为 NOT_RUN，保留单元测试与已有恢复契约，不伪造真机通过。

如果需要进一步定位，按以下顺序收集脱敏证据：

1. 点击是否到达 `AppTab.DSH_WORKSPACE`。
2. gateway 是否启动、catalog 是合法空列表还是读取失败。
3. `:dsh` 服务是否绑定，Node 是否实际存活。
4. payload/profile 播种是否成功，具体失败码是什么。
5. readiness 卡在哪个 HTTP 步骤。
6. READY 后 WebView 是否执行 token→cookie 跳转，主资源和 WebSocket 是否正常。

日志只记录阶段、状态码、epoch 和必要错误类型；不得输出 bearer token、完整认证 URL、云端密钥或用户会话正文。

## 8. 验收、回退与交付要求

### 8.1 本轮文档交付时的真实状态

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 现有版本手机侧栏故障复现 | PASS（诊断） | 只确认修复前故障，不代表功能正常 |
| 根因与调用链源码定位 | 已记录 | 导航漏转为直接根因，其他为后续启动/恢复缺陷 |
| 候选代码落盘 | 已记录 | 第 5 节，待审查与验证 |
| 新测试编译/执行 | NOT_RUN | 16 项仅为源码声明数 |
| 候选修复完整 APK | NOT_RUN | 其他任务构建不可自动归入本轮 |
| 修复后官方 DSH 前端真机验收 | NOT_RUN | 尚无成功截图与完整日志 |
| 本地/云端模型实际推理 | NOT_RUN | 本轮目标是入口修复，不能外推能力对标结论 |

### 8.2 完成条件

后续修复只有在以下证据齐备后才能标记完成：

- 当前合并源码的相关编译、行为测试和架构检查结果清楚，含执行数量与原始日志。
- 使用绑定源码快照的兼容签名 APK 原位更新后，从真实侧栏进入官方 DSH 前端。
- 未加载模型时前端可进入，认证保持有效，无重复宿主。
- 返回聊天与独立 Kotlin Agent 入口仍正常。
- 恢复、模型目录刷新等未覆盖场景逐项写明，不能用“全部修复”掩盖。

### 8.3 回退原则

回退只针对经过确认的本轮差异，按导航、HTTP probe、空目录、页面状态四个切片分别处理。由于大量文件未跟踪或混有既有改动，禁止 `git restore` 整目录、`git reset --hard`、`git clean` 或删除整个新模块。

本轮候选变化没有计划进行数据库或会话迁移。后续验证可能首次生成 DSH home、overlay 和官方会话文件，应由原 owner 管理；不要为了回退代码清除用户数据。二进制回退前仍需检查签名和后续任务是否已引入数据变化。

## 9. 可直接交给执行 Agent 的提示词

> 任务：修复 MCA 左侧栏 DSH 入口无法打开官方前端的问题。开始前完整阅读工作区适用 Agent 指令、`docs/architecture/MCA_PROJECT_HARD_LOCK.md` 和本交接文档。先核对当前 HEAD、全部相关 tracked/untracked 差异、子模块与构建锁，不覆盖其他任务。第 5 节候选修复已经落盘，测试尚未执行；逐文件审查后补关键缺口，不要重复造实现。
>
> 保持“图片 → Agent → DSH”以及 Kotlin Agent 的独立性。首先确认 ChatRoute→ChatViewModel.screenActions 的 DSH 回调完整，再验证空模型目录可启动前端、真实 401 能通过认证就绪检查、Starting→READY/FAILED/STOPPED 有正确出口及旧 epoch 隔离。保留官方前端、插件、认证、权限预设、会话数据、单一推理 owner 与取消合同；不重写聊天页，不扩大全局状态/Host，不新增引擎。
>
> 依照第 7 节分阶段执行。Gradle/native 统一持锁；核对真实测试数量。不要把旧 APK、其他任务的构建、fake 测试或端口监听当成官方前端成功。测试后构建兼容签名候选包，保留数据原位更新，从 MainActivity 实际点击 DSH 验证。记录 APK 哈希、源码输入、脱敏日志和修复后截图；不能安装或验证的场景明确写 NOT_RUN/BLOCKED。
>
> 空目录后的模型刷新需要单独核对官方协议。当前 READY 状态的 restart() 可能仅附着原代，不得声称调用它就能刷新 overlay，也不得静默中断活动任务。完成时交付精确改动、owner/状态边界、测试结果、设备证据、剩余限制与按切片回退方式。未获得实施授权时只继续诊断与文档。

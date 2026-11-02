# D12b 终端桥生产接线（PTY → 官方工具链）

日期：2026-10-21。范围：把已存在的 `core/dsh-runtime` 真 PTY（`DshTerminalHost`/`DshTerminalToolService`/
`DshTerminalToolRoutes`/`DshTerminalAssembly`）接进 App capability 桥，并实现官方
`dsh-subprocess-local.spawnTerminal` 的 home 层 JS seam。**未运行 Gradle/NDK，未上设备**（构建锁由整合者持有）；
本文件只记录实现事实、接线补丁与未验证项，不代表验收 PASS。

## 1. 本轮交付

1. **能力家族**：`DshCapability.WORKSPACE_TERMINAL`（contract enum）；不与 `PRIVILEGED_SHELL` 混用——PTY 子进程是
   App uid，不是 root/Shizuku。console 投影新增"工作区终端"行，只有"桥在跑 + 真 PTY"才 GRANTED，pipe 回退如实显示
   transport 与原因。
2. **App 装配**：`DshCapabilityAssembly` 每个 capability 生成代创建一个 `DshTerminalLane`（内含且仅含一个
   `DshTerminalAssembly`），把 9 条固定路由以 **POST + WORKSPACE_TERMINAL** 挂载；生成代停止时**先**跑
   `stopGeneration`（有界 SIGTERM→SIGKILL）再关 loopback server，未 settle 的 survivor 事实保留为
   `terminalFacts()`，不吞成功。
3. **官方 seam**：`dsh-subprocess-local.spawnTerminal` 转发到 App 桥（`APP_NOTIFY_PORT` + `X-DSH-Token`，
   open/close 带 `X-DSH-Request-Id`），返回 `dsh-terminal-bash` 期望的 handle
   （`output`/`done`/`write`/`resize`/`terminate`/`pid`/`running`/`waitForExit`）。
   `inspectForeground()` → `undefined`（未知，不伪造 pgid）；`signalForeground()` → 抛错（不报"已投递"）。
4. **bash 兼容**：`DshShellCompatibility` 把 closure 的 `bash` 解析结果变成**类型化事实**：解释器
   （`ANDROID_SH` / `DISPATCHER_SHIM` / …）、`gnuBash=false`、以及 6 项 GNU 专有特性的真实探针与期望输出。
   `/toolchain/verify` 响应新增 `bashCompatibility`，`DshToolchainReport.summary()` 追加 `bash=…`。

## 2. 文件

新增：

| 文件 | 职责 |
| --- | --- |
| `app/.../composition/DshTerminalLane.kt` | 每生成代的终端 owner 槽位 + 只读事实投影（Android-free） |
| `app/.../composition/DshCapabilityRouteMounting.kt` | 纯挂载函数（能力家族 + verb 集合显式传入） |
| `core/dsh-runtime/.../process/DshShellCompatibility.kt` | bash/sh 类型化兼容说明 + GNU 特性探针表 |
| `core/dsh-runtime/.../process/DshTerminalSeamInstaller.kt` | 把 seam 装进 home（插件文件 + 自有 overlay 块） |
| `core/dsh-runtime/src/main/assets/dsh-seam/terminal-bridge.mjs` | 官方 seam 插件（零依赖，仅 `node:` 内建） |
| `tools/dsh/terminal-seam-smoke.mjs` | 对假桥的 JS 冒烟（`node tools/dsh/terminal-seam-smoke.mjs`） |

修改：`core/dsh-contract/.../capability/DshCapabilityTypes.kt`（枚举）、
`feature/dsh-workspace/.../DshConsoleProjector.kt`（标签）、
`app/.../composition/DshCapabilityAssembly.kt`（lane、挂载、stop 顺序、console 行、CAPABILITIES）、
`core/dsh-runtime/.../process/DshToolchainVerification.kt`（report 增加 `shellCompatibility`）、
`core/dsh-runtime/.../process/DshTerminalToolRoutes.kt`（`bashCompatibility` 载荷）。

## 3. 未拥有文件的接线补丁（建议由 seeder owner 落地）

现状：seam 由 App 侧 `DshTerminalLane.open()` 安装，而它发生在 `:dsh` 首次 seed **之前**，所以**第一次**生成代
里 home 还不存在 → 类型化 `Pending(HOME_NOT_SEEDED)`，官方终端这一代仍走 payload 自带 JS PTY；从第二次生成代起
seam 生效。要让首启就生效，把安装点移到 seed 成功之后：

`core/dsh-runtime/.../service/DshAndroidRuntimeAssembly.kt`

```kotlin
// 构造函数增加一个 asset 读取器（该文件已持有 Context）
private val terminalSeamSource: () -> String? = {
    runCatching {
        context.assets.open("dsh-seam/terminal-bridge.mjs")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
}
```

`seedDshHome(endpoint)` / `seedDshHomeAccountOnly()` 在 `DshSeedOutcome.Seeded` 分支里、返回 true 之前：

```kotlin
// overlay 已由 seeder 写好；这里只装插件文件与本安装器自有的 insert 块。
terminalSeamSource()?.let { source ->
    when (val seam = DshTerminalSeamInstaller(config.homeDir).install(source)) {
        is DshTerminalSeamState.Active -> log("dsh: terminal seam ${seam.summary}")
        else -> log("dsh: terminal seam not active: ${seam.summary}")
    }
}
```

**不要**把 `- id: mca-terminal-bridge` 写进 seeder 的共享 `- insert:` 块：安装器检测到"别人的块里已含本 id"时会
保留该块不动并报 `RowOwner.SHARED_BLOCK`，但两个 writer 写同一行本身就是隐患。保持"seeder 拥有自己的行、安装器
拥有自己的一行"即可，seeder 的三向 merge 会把未知块原样保留。

## 4. 已执行的检查

- `python tools/architecture/check_architecture_rules.py`：Rule 15（DSH contract 纯度）、Rule 16（DSH workspace
  边界）通过；整体 FAIL 由 **别人的**未跟踪文件
  `core/agent-runtime/.../run/AgentToolBatchExecution.kt`（CRLF）触发，与本轮改动无关。
- `node --check core/dsh-runtime/src/main/assets/dsh-seam/terminal-bridge.mjs` → exit 0（ESM 可解析，Node 24.19.0）。
- `node tools/dsh/terminal-seam-smoke.mjs` → **PASS**：假桥校验了 token/request-id、open 的 cols/rows/requirePty、
  `motd` 回放与游标推进（5 → 12）、`write` 的 `submit:false`、resize、`done` 的 `{exitCode:0, signal:undefined}`、
  `terminate` 必须 settle（否则抛错）、`inspectForeground()===undefined`、`signalForeground` 抛错、非 shell argv 拒绝、
  `-c` argv 拒绝、以及未处理的 `done` 不会带崩宿主。
- PyYAML 解析"seeder 行 + 安装器块"的合成 overlay：3 个顶层 patch 条目，第二个 `insert` 只含
  `mca-terminal-bridge`（`./mca-terminal-bridge/index.mjs`）。

## 5. NOT_RUN（诚实边界）

- **NOT_RUN（构建/单测）**：未运行 Gradle，Kotlin 全部未编译；新增 5 个 JVM 测试文件与 1 个扩展用例
  （`DshShellCompatibilityTest`、`DshTerminalSeamInstallerTest`、`DshTerminalSeamSourceTest`、
  `DshTerminalLaneTest`、`DshCapabilityRouteMountingTest`、`DshConsoleProjectorTest` 新增终端投影用例）未执行。
- **NOT_RUN（设备）**：`:dsh` 内官方 `terminal_*` 实跑、isatty、resize→`stty size`、^C 命中前台组、断连重连、
  子树退出、PDEATHSIG。
- **NOT_RUN（loader）**：`./mca-terminal-bridge/index.mjs` 是否被官方 loader 的 `baseUrl` 解析成功（证据见第 6 节），
  以及 `service.spawnTerminal = …` 实例覆盖在真实 cordis 服务对象上是否生效。
- **NOT_RUN（测试钩子）**：仓库没有 JS 测试框架；JS 侧只有 `tools/dsh/terminal-seam-smoke.mjs` 这个独立冒烟脚本，
  不进 Gradle 测试图。

## 6. 风险

- **argv/env 保真**：桥的 `/terminal/open` 只接受 `type/cwd/cols/rows/requirePty`，固定跑 closure 解析出的 shell；
  seam 因此**拒绝**任何非 shell argv 或 `-c` 等会改变语义的参数（抛错），并在 handle 上挂
  `mcaCompatibility`（`sandboxArgvByteFaithful:false`、`gnuBash:false`、请求的 program/args/terminalType）。sandbox
  的 profile fence、自定义 shellPath、`TERM=dumb` 覆盖都未逐字节保真。
- **前台组不可用**：`inspectForeground` 恒为未知、`signalForeground` 恒抛错；`terminal_signal` 的 SIGTERM/SIGHUP
  不能经本桥转发。^C/^Z 只能走 tty 行规程（`/terminal/control`），而本 seam 不调用它。
- **两个 overlay writer**：`DshGatewayConfigSeeder` 与 `DshTerminalSeamInstaller` 都写 `cordis.patch.yml`。安装器
  只改自有单行 `insert` 块、拒绝创建文件、对共享块零改写（有测试），但顺序上仍是"App 先写、:dsh 后 merge"。
- **首启一代**：见第 3 节；未落地补丁前，第一次生成代的官方终端没有 seam。
- **文件预算**：`DshCapabilityAssembly.kt` 539 → 671 行（单一 composition 文件硬上限 800，目标 500），属既有债务
  D06 范围内的一次扩张；新职责已尽量下沉到 `DshTerminalLane`/`DshCapabilityRouteMounting`。
- **bash 不是 bash**：`bin/bash` 只是 `/system/bin/sh` shim；`DshShellCompatibility` 不再让任何探针结果升级成
  "bash 可用"，GNU 特性（数组、花括号展开、进程替换、`local -n`、`shopt`、`BASH_VERSION`）在设备上会失败，
  依赖它们的插件需要改用 POSIX 写法或等待真正的 bash 载荷。

## 7. 回退

- Kotlin：删除 `DshCapability.WORKSPACE_TERMINAL` 行与 `buildRoutes` 里的终端挂载即可回到"无终端家族"，
  其余家族不受影响；`DshTerminalLane` 独立文件，可直接删除。
- Home：删除 `<DSH_HOME>/profiles/web/mca-terminal-bridge/` 与 overlay 中 `- insert:` 的 `mca-terminal-bridge`
  块；下一次生成代不会重建（除非 seeder 补丁已落地）。
- 数据：本轮不写数据库、不改协议旧字段，只新增只读投影与一个可选 overlay 块。

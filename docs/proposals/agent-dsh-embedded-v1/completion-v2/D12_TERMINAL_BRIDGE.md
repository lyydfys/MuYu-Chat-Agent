# D12 终端桥：真 PTY 的生产接线（实施记录）

日期：2026-10-21。状态：代码已写入工作树；**未构建、未上设备**（构建锁由整合者持有）。本文件只记录本轮实现事实、接线补丁与未验证项，不代表验收 PASS。

## 1. 事实基线（源码读取）

- 官方终端工具链：`dsh-tool-terminal`（`terminal_open/send/read/signal/close/list`）→ `ctx.terminals`（`dsh-terminal`）→ `dsh-terminal-bash` 后端 → `ctx.subprocess.spawnTerminal`（`@deepseek-ai/dsh-subprocess-local`）。
- payload 的 `spawnTerminal` 是 Android 补丁版：`/dev/ptmx` + 枚举 `/dev/pts/N` 的纯 JS 实现，失败回退管道；`resize()` 在两条路径上都是 no-op，没有 controlling tty/前台进程组。
- MCA 已有真 PTY 原语：`src/main/cpp/dsh_pty.c`（`posix_openpt`+`grantpt`+`unlockpt`+`ptsname_r`；`setsid`+`TIOCSCTTY`+`TIOCSWINSZ`；SIGTERM→SIGKILL 进程组停止），JVM 侧 `DshTerminalFactory`/`DshTerminalSession` 已存在但**没有生产调用者**。
- 官方 capability 桥是 App 进程内的认证 loopback 服务；官方移动工具包用 `APP_NOTIFY_PORT` + `X-DSH-Token` 调用。终端子进程归属 App 进程（同一 UID，nativeLibraryDir 可执行）。

## 2. 本轮实现的所有权

```
DshTerminalAssembly (process/)
  └─ DshTerminalToolService (ownerKey="dsh", toolchain cache)
       ├─ DshTerminalHost: 每个会话一个 pump 线程 + 有界 scrollback + 停止
       │    ├─ DshTerminalFactory → DshNativePtyPort (REAL_PTY) / 显式 PIPE_FALLBACK
       │    └─ DshTerminalSession（resize 合并、phase、exit、幂等 close）
       ├─ DshToolchainVerifier: 真实执行 node/npm/npx/pnpm/pip/python/rg/curl/git/bash
       └─ DshTerminalToolRoutes: /terminal/* + /toolchain/verify（固定路径、JSON）
```

- 会话 id 单调、owner 固定为生成代；`list/read/write/control/resize/close` 都校验 owner。
- 交互输入：`write(text, submit)` 追加 `\r`；`control(CTRL_C/CTRL_D/CTRL_Z/CTRL_L)` 向 tty 写字节，由行规程把 `^C/^Z` 变成前台进程组信号；pipe 回退明确拒绝（`pty-unavailable`），不伪造。
- resize：走 `DshTerminalSession` 的合并窗口；真 PTY 返回 applied/coalesced，pipe 返回 `resize-unsupported`。
- 重连：输出带绝对字节游标（`firstRetainedOffset/nextOffset`），`/terminal/attach` 重放保留窗口；不会因重连重启子进程。
- 后台任务：无 attachment 的会话照常运行并保留 scrollback；`closeAll`/`stopGeneration` 对所有会话执行有界 SIGTERM→SIGKILL，返回 `settled/escalated`，未 reap 不报 stopped。
- 孤儿防护：native fork 子进程设置 `PR_SET_PDEATHSIG(SIGKILL)`（App 进程死时 shell 不逃逸），并在 exec 前关闭 3..255 的继承 fd；`open` 与 `closeAll` 竞态下，晚到的子进程会被立刻关闭而不是留在 fence 外。`DshPipeTerminalPort`/`DefaultDshProcessRunner` 的停止也改为先按进程树 signal 再 destroy。

## 3. 命令/安装链验证

`DshToolchainVerifier` 对闭包解析出的每个角色执行固定探针（真实 argv，非 shell 字符串）：

| 角色 | 探针 | 判定 |
| --- | --- | --- |
| node | `node --version` | `^vX.Y` |
| npm/npx | `node <payload>/npm-cli.js --version` | 版本行 |
| pnpm | `node <payload>/pnpm.cjs <copy-import> --version` | 版本行 |
| pip | `<python> -m pip --version` | 含 `pip` |
| python | `<python> --version` | 含 `Python` |
| rg/curl/git | `<nativeLibraryDir>/libdsh*.so --version` | 对应版本行 |
| bash | dispatcher 或 `/system/bin/sh` `-c "printf dsh-toolchain-ok"` | 精确 marker |

同时列出依赖事实：`libdshnode.so`、`libdshcmd.so`、`libdshpty.so`、`libdshpython.so`、`libdshrg/curl/git*.so`、npm/npx/pnpm 脚本、python stdlib、bash/git/rg/curl 入口。超时、spawn 失败、退出码非 0、输出不含角色标记都分别成状态；`--version` 成功不算安装链完整，这里只声明“探针通过 + 依赖存在”。

## 4. 接线补丁（需整合者/其他 owner 落地的部分）

### 4.1 App capability 桥挂载（app composition，本轮不可改）

`DshCapabilityAssembly.buildRoutes()` 中增加一个字段并在 `buildRoutes` 挂载：

```kotlin
private val terminals = DshTerminalAssembly(
    config = config,                       // 与 :dsh 同一 DshRuntimeConfig
    nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir),
    nodeNeedsLinker = /* 取自当前 exec 策略；未知时 false 并在 /toolchain/verify 里如实失败 */ false,
    connection = DshTerminalHostConnection.REQUIRED,
    log = { Log.i("McaDshTerminal", it) }
)

// buildRoutes():
mount(
    builder = builder,
    table = terminals.routes(),
    capabilityOf = { DshCapability.WORKSPACE_TERMINAL },  // 新家族，见 4.2
    methods = setOf(DshCapabilityHttpMethod.POST)
)
```

`DshCapabilityAssembly.stop()` 必须在关闭 loopback server 之前调用 `terminals.stop()`；未确认停止时保留 survivor 事实（不要把 stopGeneration 的异常吞成成功）。`onDestroy` 路径同样调用一次（幂等）。

生命周期：`DshTerminalAssembly` 绑定一个 capability 生成代；`stop()` 后 host 进入 closed 且不再接受新会话。下一次 `endpointOrNull()` 必须创建一个**新的** `DshTerminalAssembly`（不要复用已 closed 的实例），旧实例的 stop 结果保留为证据。

### 4.2 capability 家族（contract + composition）

`core:dsh-contract` 的 `DshCapability` 需要新增 `WORKSPACE_TERMINAL`（或所有者批准的等价家族）；`DshCapabilityAssembly.CAPABILITIES` 加同值；`consoleCapabilities()` 增加一行投影（GRANTED 仅当桥运行且 PTY 可用，`PipeFallback` 时如实显示 transport 与原因）。不在 `PRIVILEGED_SHELL` 下挂载：终端不是 root/Shizuku。

### 4.3 官方 subprocess seam（EXT）

官方 `spawnTerminal(spec)` 需要改为把 spec 转发到本桥（同一 `APP_NOTIFY_PORT`/`APP_LOCAL_TOKEN`），并实现 `dsh-terminal-bash` 期望的 handle 形状：`output`（Readable，Buffer 块）、`done`（`{exitCode, signal}`）、`write/resize`、`inspectForeground()`、`signalForeground()`、`terminate()`、`pid`。映射：

| seam | 本桥 |
| --- | --- |
| spawn | `POST /terminal/open` `{type:"shell", cwd, cols, rows, requirePty:true}` |
| output | 轮询 `POST /terminal/read` `{sessionId, offset}`，`nextOffset` 作为游标；EOF 时 `done` |
| write | `POST /terminal/write` `{sessionId, text, submit:true}` |
| resize | `POST /terminal/resize` |
| Ctrl-C | `POST /terminal/control` `{control:"CTRL_C"}`（或 `terminal_signal SIGINT` 映射） |
| terminate | `POST /terminal/close`，未 settled 时抛错，不得报成功 |
| inspectForeground/signalForeground | 尚未暴露（见 §6）：seam 必须返回“不可用/UNKNOWN”，不能伪造 pgid |

seam 对 `open/close` 应携带 `X-DSH-Request-Id`（16..64 URL-safe）：`open` 不是幂等操作，服务端账本只在有 request id 时回放同一结果，避免重试创建第二个终端。`write/read` 可以不带 id。`open` 响应同时给出 `motd`（启动输出）与 `session.nextOffset`（游标）：seam 要么把 `motd` 作为首批输出并把游标设为 `nextOffset`，要么忽略 `motd` 从 `offset=0` 读；两种都不会重复或丢字节。

候选实现位置（C08 写集，非本文件）：payload 源补丁（需重新生成 `payload.zip` 并更新 `MANIFEST.json`/`payload-lock.json`，由打包 owner 决定），或 home 层纯 JS 插件在 `ctx.subprocess` 上包装 `spawnTerminal`（不改 payload 字节，需 C02c 在 overlay 中 insert）。两种方式都必须保留 `PipeFallback` 的显式模式上报。

### 4.4 启动期验证（由 owner 或桥首次调用）

在 `DshRuntimeOwner` 完成 `prepareRuntimeLinks` 后（或 `DshTerminalAssembly` 创建后）调用一次 `terminals.verifyToolchain()`，把 `summary()` 与失败角色写入现有日志/console 投影；失败不阻止 Node 启动（官方前端仍可用），但能力投影必须显示具体缺失，不能显示“命令可用”。

## 5. 已写入文件

| 文件 | 职责 |
| --- | --- |
| `core/dsh-runtime/.../process/DshToolchainVerification.kt` | 环境快照 + 真实探针 + 依赖证据 |
| `core/dsh-runtime/.../process/DshTerminalHost.kt` | 会话 owner、pump、scrollback、输入/resize/控制、停止 |
| `core/dsh-runtime/.../process/DshTerminalTypes.kt` | 会话状态/拒绝原因/控制字节/结果与快照类型 |
| `core/dsh-runtime/.../process/DshTerminalOutputRing.kt` | 有界输出窗口与字节游标 |
| `core/dsh-runtime/.../process/DshTerminalToolService.kt` | 官方工具面 facade、工具链缓存、生成代 stop |
| `core/dsh-runtime/.../process/DshTerminalToolRoutes.kt` | 固定 JSON 路由（/terminal/*、/toolchain/verify） |
| `core/dsh-runtime/.../process/DshTerminalAssembly.kt` | 从 `DshRuntimeConfig` 派生的生产装配 |
| `core/dsh-runtime/.../terminal/DshTerminalSession.kt` | 增加 `processId`、`size()` |
| `core/dsh-runtime/src/main/cpp/dsh_pty.c` | `PR_SET_PDEATHSIG` 防 App 死亡后 shell 逃逸 |
| 测试 4 个 + fakes | JVM 构造/生命周期/路由/工具链验证 |

## 6. 未验证 / 风险

- **NOT_RUN（设备）**：`:dsh` 内官方 `terminal_*` 实跑、isatty、中文输入、resize→`stty size`、^C 命中前台组、后台 job、断连重连、子树退出、App 进程被杀后 PDEATHSIG 生效、`/dev/ptmx`/`/dev/pts` 在 App 命名空间可见性。以上全部只能上设备后判定。
- **NOT_RUN（构建）**：本轮未运行 Gradle/NDK；C 改动与 Kotlin 源均未编译。
- **未暴露**：`inspectForeground()`/`signalForeground()` 需要额外 native 查询（`/proc/<pid>/stat` 的 tpgid 或 `TIOCGPGRL`）与按 pgid 发信号；当前只有 `^C/^Z/^D/^L` 行规程。官方 `terminal_signal` 的 SIGTERM/SIGHUP/SIGKILL 不能通过本桥转发；seam 必须显式拒绝/降级，不能伪造 `delivered:true`。
- **argv/env 保真限制**：`/terminal/open` 目前不接收官方 seam 的 `argv`/`env`，而是用 `DshCommandClosure` 解析出的 shell（`/system/bin/sh -i` 或 dispatcher bash shim）加 `DshNodeCommand.environment`。因此 `dsh-terminal-bash` 的 sandbox-mode fence、SHELL/TERM 覆盖和自定义 shellPath 尚未逐字节保真；需要下一片扩展 open 协议（白名单 argv + 凭证形状 env 过滤）后再宣称等价。
- **仅依赖闭包 pre-link 事实**：PATH 上 `npm/npx/pnpm/pip` 的 dispatcher 符号链接没有单独 exec 验证（BASH 探针会执行 dispatcher；npm 探针走 `node <cli.js>`，绕过 PATH）。如要覆盖 EACCES 正负例，需增加“经 PATH 名执行”的探针（设备型）。
- **跨进程语义**：终端子进程属于 App 进程，而不是 `:dsh`；服务/主进程死亡时 PDEATHSIG 杀 shell，但 shell 的后台孙进程仍可能被 reparent。停止 fence 不能把“shell 已退出”当作“整个工具树已归零”，需要设备验证。
- **会话 owner**：本桥按生成代固定 owner；官方 Node 层负责 agent 级 owner 隔离。桥不解析 agent id，避免伪造身份。

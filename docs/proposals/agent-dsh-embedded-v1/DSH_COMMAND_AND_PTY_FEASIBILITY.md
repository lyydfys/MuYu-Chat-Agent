# C07/C08 可行性决议备忘：命令执行闭包与真 PTY

日期：2026-10-20（Asia/Shanghai）。性质：**只读设计评审**；无构建、无设备、无生产代码修改。

> **修订 1（2026-10-20，Lead 复核）**：§0 第 1 条、§1 F1/F2、§4 步骤 1 已更正。复核发现原文“home 级 `$DSH_HOME/cordis.patch.yml` 不被官方 loader 读取”不成立：payload 的 `dsh-app-boot` 与 `profile-boot` 明确把 home 级 patch 作为优先于 profile 层的用户层读取，05:12 真机的 `provider MCA` 与模型列表也只能来自该 overlay。真实缺陷是 payload 模板 `dshhome/cordis.patch.yml` 的平台 patch 行没有合并进 MCA 生成的 overlay；F3/F4/F5 与 PTY 结论经复核基本成立（F5 的 python 部分按现行源码另行更正：`python*` 已被重链到 `libdshpython.so`，不得与 npm 类 EACCES 混同），具体见正文。

上游输入：[完成方案 §5.3/§5.4](DSH_COMPLETION_PLAN_V1_20261020.md)、[DSHA 参考分析](../../repair/DSHA_REFERENCE_ANALYSIS_20261019.md)、[移动 WebView 附录](../../repair/DSHA_REFERENCE_ANALYSIS_20261019_ADDENDUM_MOBILE_WEBVIEW.md)。

**证据基线**：`E:\tmp\dsh-runtime\payload`（解包自 APK 的 `assets/dsh/payload.zip`，`payloadVersion = dsh-0.2.1-rc.2+android-ref-c324272bff00`，官方 commit `687f282e…`）。下文 `payload/...` 指该目录；行号为本次实测。
**结论都可在本机复现**；任何“今天能跑/不能跑”的判定都标注了证据强度，未在设备上执行。

---

## 0. 决议摘要

1. **命令闭包今天不成立，但缺的不是二进制**：node/rg/curl/git/python 的 ELF 入口与 npm/pnpm/pip 包装脚本都在，**卡在两层**——(i) MCA 生成的 home overlay 会被官方读取（已复核），但 seeder 没有把 payload 模板 `dshhome/cordis.patch.yml` 的平台 patch 行合并进来，`bash-sandbox` 仍启用、`bash-local` 未插入；(ii) MCA 默认 `workspace-write` 下 `bash-sandbox` 调 `confine()`，Android 无 landlock runner → `SANDBOX_UNAVAILABLE`（源码可确认，待设备抓一次）。
2. **包装脚本本身还有第二道门**：`bin/npm|npx|pnpm|pip` 是普通脚本文件，位于可写应用目录，targetSdk 36 下 execve 被拒；`bash` 之所以能用是因为 MCA 把它替换成指向 `/system/bin/sh` 的符号链接。`python*` 例外：现行源码会把它重链到 `libdshpython.so`，但该 4,448 B ELF 是否为可用解释器未验证，不能与 npm 类结论混同。
3. **真 PTY 已有一版纯 JS 实现**（`/dev/ptmx` + `/dev/pts/N` + slave 作 stdio），是**真 TTY**（提示符/回显/行规程），但 `resize()` 是空实现、没有 controlling tty/会话/前台进程组语义、失败静默降级管道。`/dev/pts` 在 app 命名空间是否可见是唯一未决事实。
4. **node-pty 不是 V1 主线**：payload 明确“红线不带原生模块”，包内也没有 node-pty；Bionic 自建要 node-gyp/NDK 且上游无 android 预编译。真需要 native PTY 时应走 **Termux terminal-emulator JNI**（DSHA 生产证据），而不是 node-pty。
5. **推荐 V1 三步**：P0 合并平台 patch 行（0.5–1 天）→ P0.5 受管窄 launcher（1–2 天）→ P1 PTY 收口（2–4 天）。**在 P1 探针出结果前，不得声称“已支持真 PTY”或“npm/pnpm 可用”。**

---

## 1. 事实基线（决定 (a) 的五条硬事实）

### F1 profile 的两层用户 patch 路径（已复核更正）

官方既有 profile 级用户层，也有 home 级用户层：

- profile 级：`loadProfileDirectory` 读取 `$DSH_HOME/profiles/<name>/cordis.patch.yml`（`PROFILE_PATCH_FILENAME`，`dsh-app-boot/lib/index.js` 的 `patchPath = join(dir, PROFILE_PATCH_FILENAME)`），`profiles/web/package.json` 的 `dsh.profile.patchReload: live` 也指向该文件。
- home 级：`dsh/lib/profile-boot-C14i-8IN.js` 的 `homePatchPath()` 返回 `join(resolveDshHome(), PROFILE_PATCH_FILENAME)`，注释明确它 “outranks the per-profile layer”；`dsh-app-boot/lib/index.js` 的 `readProfilePatches()` 在 profile 层之后追加 `loadOptionalPatches(binName, join(context.home, "cordis.patch.yml"))`，`runProfile()` 在 `boot()` 前调用它。
- 另有 CLI `--patch <path>` 覆盖层。

因此 `$DSH_HOME/cordis.patch.yml` 本身是合法且优先级高于 profile 层的用户层；**MCA 写在该路径的 overlay 会被读取**。05:12 真机 UI 出现 `provider MCA` 与模型列表也只能来自 MCA 的该 overlay（无其他生产者），构成经验佐证。

### F2 MCA 写在 home 根：路径正确，但生成内容不完整（已复核更正）

`DshGatewayConfigSeeder`：`overlayTarget = File(home, OVERLAY_NAME)`（[DshGatewayConfigSeeder.kt:383](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt#L383)），`OVERLAY_NAME = "cordis.patch.yml"`（`:614`），`PROFILE_DIR_RELATIVE = "profiles/web"`（`:616`）只用于读模板。启动命令无 `--patch`（[DshNodeCommand.kt:33-40](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt#L33-L40)）。
**⇒ 该路径会被官方 home 层读取，MCA 的 permission/workspace-controller/llm-pi-ai 覆盖进入生效树（与 05:12 模型列表证据一致）。真正的问题是 `buildOverlay()` 只生成这三类行，没有把 payload 模板 `dshhome/cordis.patch.yml` 的平台 patch 行（disable `bash-sandbox`、insert `bash-local`、参考 Android 工具行等）复制或合并进来。** 现有单测只断言“文件内容写对了”（`DshGatewayConfigSeederTest.kt:57`），不证明这些平台行已进入 composed tree；需要核对 composed tree 或设备行为。

### F3 平台 patch（模板）没有被复制（已按当前 payload 复核）

payload 模板里的平台 patch 位于 `payload/dshhome/cordis.patch.yml`（当前归档 79 行，`dsh-android-patch: v4`），内容包括：禁用 `bash-sandbox`、`pwsh-sandbox`、`session-log-download`、`session-telemetry-otel`；`permission` 预设（**`defaultPreset: danger-full-access`**；该档 `approval: never`，read-only/workspace-write 才是 `ask`）；`insert` `bash-local`（cwd `DSH_WORKSPACE`）、`tool-shizuku`、`tool-android`、`tool-accessibility`、`tool-vscreen`、`tool-browser`。MCA seeder 只复制 `cordis.yml/package.json/pnpm-workspace.yaml`（`PROFILE_TEMPLATE_FILES`，[同文件:636](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt#L636)）与 `settings.yaml`，**没有复制该 patch，也没有合并其平台行**。

### F4 平台行缺失时，`ctx.shell` 仍是 bash-sandbox（fail-closed）

`dsh-base/cordis.patch.yml` 注册 `bash-sandbox`（`disabled` 仅 `process.platform === 'win32'`，Android 上启用）；`sandbox-policy.mode = process.env.DSH_PERMISSION_MODE ?? 'workspace-write'`。MCA 不设 `DSH_PERMISSION_MODE`（[DshNodeCommand.kt:56-92](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt#L56-L92) 的环境列表无此项），且 MCA overlay 的 `permission.defaultPreset` 为 `workspace-write` ⇒ 默认 `workspace-write`。
`SandboxBashExecutor` 构造取 `ctx.sandboxPolicy.defaultMode`（`payload/.../dsh-bash-sandbox/lib/index.js:46-53`）；`execute()` 在非 `danger-full-access` 时调用 `this.confine(...)`（`:65-80`），runner 不可用/失败即抛 `SandboxUnavailableError`（`:104-105`）。landlock runner 来自 `@deepseek-ai/node-addon-system-<platform>-<arch>`；当前 payload 只有 darwin/linux 的 optional 平台包，且全树无 `.node` 文件 ⇒ Android 上 runner 不可用。
**⇒ 平台 patch 未合并时，bash 工具能挂载但每次执行会 fail closed，而不是“静默降级为不隔离执行”。**（源码可确认；待设备抓一次 `bash echo` 的真实报错。若 preset 为 `danger-full-access`，bash-sandbox 会走非隔离分支，但 MCA 明确拒绝继承该危险默认。）

---

## 2. (a) 命令入口映射：每个名字今天落到哪里

路径事实：PATH = `<payload>/bin : <payload>/runtime/bin : /system/bin : /system/xbin`；`SHELL=/system/bin/sh`；`TERM=xterm`；`LD_LIBRARY_PATH=<payload>/runtime/lib`；`PYTHONHOME=<payload>/python`；`GIT_EXEC_PATH=<payload>/git/libexec/git-core`（[DshNodeCommand.kt:56-92](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt#L56-L92)）。
运行时链接替换：`bin/node`、`runtime/bin/{rg,curl}`、`bin/git`、`git/libexec/git-core/git-remote-{http,https}`、`python/bin/{python3.14,python3}` → `nativeLibraryDir/libdsh*.so` 符号链接；`bin/bash` → `/system/bin/sh`（[DshAndroidRuntimeAssembly.kt:110-151,166-175](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt#L110-L151)）。

| 命令 | PATH 命中 | 载荷实体（原始字节） | 实际执行体 | 今天能否工作 | 阻塞点 |
|---|---|---|---|---|---|
| `node` | `<payload>/runtime/bin/node` | ELF 49,715,528 B（`libdshnode.so` 同字节，`MANIFEST.json:9-13`） | bionic Node 26（Termux build，`MANIFEST.json:62-65`） | **能**（READY/单 Node 已证） | — |
| `git` | `<payload>/bin/git` | ELF 3,562,128 B（= `libdshgit.so`） | 同一 ELF，运行时被换成指向 nativeLibraryDir 的链接 | 大概率能；`git-remote-*` 另有链接 | 未做真实 `git clone` 验证 |
| `curl` | `<payload>/runtime/bin/curl` | ELF 308,272 B（= `libdshcurl.so`） | 同上 | 大概率能 | 证书/`OPENSSL_CONF` 已有，未验证 https |
| `rg` | `<payload>/runtime/bin/rg` | ELF 4,868,920 B（= `libdshrg.so`） | 同上 | 大概率能 | 未验证 |
| `bash` | `<payload>/bin/bash` | **脚本** 1,251 B（`#!/system/bin/sh`，剥掉 `--noprofile/--norc/-l` 等后 `exec /system/bin/sh "$@"`） | 运行时被换为 **指向 `/system/bin/sh` 的符号链接** → mksh | **能，但不是 GNU Bash** | 无 bash 语义；模板 patch 的 `bash-local` 未生效 |
| `python` / `python3` | `<payload>/bin/python{,3}` | 归档为**脚本** 655 B → `exec ../python/bin/python3.14`；现行源码 `prepareRuntimeLinks()` 会把 `bin/python{,3}`、`bin/python3.14`、`python/bin/python3.14` 重链到 `libdshpython.so` | 归档脚本 → `python/bin/python3.14`（ELF 4,448 B，= `libdshpython.so`）；运行时为 nativeLibraryDir 链接 | **入口已重链，不再适用 EACCES 结论；解释器身份/实际运行未验证** | 4,448 B 的 ELF 究竟是什么未核对；`python/lib/python3.14/` 才有 stdlib |
| `pip` / `pip3` | `<payload>/bin/pip{,3}` | **脚本** 528 B → `exec ../python/bin/python3.14 -m pip` | 同上 | **卡在脚本 exec** | 依赖 python 先通 |
| `npm` | `<payload>/bin/npm` | **脚本** 942 B → `exec "$NODE" ../npm/bin/npm-cli.js`；设 `NPM_CONFIG_PREFIX=$HOME/.npm-global` | `npm-cli.js`（81 B，`require('../lib/cli.js')`；shebang 是**残留 Termux 路径** `#!/data/data/com.termux/files/usr/bin/env node`，因经 `node <file>` 加载而不生效） | **卡在脚本 exec** | 无 `.bin`（`payload/node_modules/.bin` 不存在） |
| `npx` | `<payload>/bin/npx` | **脚本** 488 B → `node ../npm/bin/npx-cli.js` | 同上 | **卡在脚本 exec** | 同上 |
| `pnpm` | `<payload>/bin/pnpm` | **脚本** 835 B → `node ../pnpm/bin/pnpm.cjs --config.package-import-method=copy`（注释说明 SELinux/FUSE 拒绝硬链接，故强制 copy 导入） | `pnpm.cjs`（1,102 B）→ `require('../dist/pnpm.cjs')` | **卡在脚本 exec** | 同上；`pnpm --version` 不能代表插件生态 |
| `sh` | `/system/bin/sh` | 系统 mksh | — | 能 | 方言非 bash |

### F5 “卡在脚本 exec” 的准确含义（已按现行源码更正 python）

包装脚本位于 `<payload>/bin`（= `files/dsh/payload`，**可写应用目录**）。targetSdk 29+ 起应用不能 execve 可写目录里的文件；`bash` 例外是因为 MCA 把它替换成符号链接、指向系统只读 ELF。`npm/npx/pnpm/pip` **没有**做等价替换，PATH 查找会 execve 这些脚本 → EACCES；`/system/bin/sh -c "npm …"` 里的 mksh 对 EACCES **不会**回退成“读脚本解释执行”（只对 ENOEXEC 有回退）。
`python`/`python3` 是例外：现行 `DshAndroidRuntimeAssembly.prepareRuntimeLinks()` 的 `TOOL_LINKS` 会把 `bin/python`、`bin/python3`、`bin/python3.14` 与 `python/bin/python3{,14}` 重链到 `nativeLibraryDir/libdshpython.so`，因此 **不能把 python 与 npm 一并断言为 EACCES**；但该 4,448 B ELF 是否为可用解释器（stdlib/site-packages 能否加载）仍需设备验证，`pip` 的 PATH 入口仍是包装脚本，先受 EACCES 约束。（本候选设备侧待复现；R01 历史探针见 `DshAndroidRuntimeAssembly.kt:129-134`。）
**⇒ 即使平台 patch 合并、bash 能跑，`npm/npx/pnpm/pip` 仍会失败；`python*` 需先验证重链后的解释器是否真可用。** 这是 C07 的第二道门，也是方案 §5.3 要求的“受管窄 launcher”真正要解决的问题。

---

## 3. (b) 真 PTY：现状与三条候选路径

### 3.1 现状：payload 里已有纯 JS PTY（不是 node-pty）

`payload/.../dsh-subprocess-local/lib/index.js:944-1176`（注释自述“v1.19.7 第 13 轮重写”）：

- `openAndroidPty()`（`:944`）：`openSync('/dev/ptmx','r+')`，再逐个 `openSync('/dev/pts/N')` 并用 `fstatSync().rdev !== 0` 认 slave（上限 64）。
- `spawnWithPty()`（`:976`）：`spawn(file, args, { stdio: [slave, slave, slave] })` → 子进程拿到**真 pts**。
- 读泵：64 KiB `readSync(master)`，EAGAIN→16 ms 重试，EIO/EBADF 结束；`write()` 写 master；`kill()` 只 `child.kill(signal)`；**`resize() {}` 空实现（`:1046`）**。
- 无 `setsid`/`TIOCSCTTY`：**没有 controlling terminal**，子进程仍属于 Node 的会话/进程组；前台进程组语义不存在。
- 失败或非 android → `spawnWithPipes()`（`:1064`）：普通管道 + 客户端行规程（本地回显、退格、Ctrl-U、Ctrl-C→`SIGINT`、Ctrl-D→`stdin.end`；方向键/ESC 等控制字符被丢弃），`resize()` 同样空实现（`:1159`）；交互 shell 额外设 `ENV=/dev/null`、`PS1`、`TERM` 以抑制 mksh 的“无 tty”告警。
- 选择器 `spawnPtyCompat()`（`:1164-1176`）：`process.platform === 'android'` 才尝试 ptmx，否则/失败则管道，并 `console.log('[pty] …')` 记录原因。
- 包依赖里虽声明 `node-pty 1.2.0-beta.15`（`dsh-subprocess-local/package.json`，含 `postinstall: ensure-spawn-helper.mjs`），但 **payload 内没有 node-pty 包**（全树检索只命中注释/import/lockfile）。平台 patch 第 3 行也写明“node-pty → child_process；koffi 惰性加载”（`payload/dshhome/cordis.patch.yml:3`）。

**结论**：C08 的起点不是“从零做 PTY”，而是“把已有的 JS PTY 收口到可验收”，缺的是 **resize**、**controlling tty/会话/前台组**、**模式诚实上报**，以及一个设备事实（`/dev/pts` 可见性）。

### 3.2 候选对比

| 维度 | A. 现有 JS PTY（ptmx/pts） | B. Termux terminal-emulator JNI（DSHA 路线） | C. node-pty Bionic 自建 | D. 管道回退（已有） |
|---|---|---|---|---|
| 依赖 | 无原生依赖，纯 JS | NDK 构建 `libtermux`/`libpty`；需 JNI 桥或原生 helper 进程 | node-gyp + NDK + `posix_spawn` helper | 无 |
| ABI/页大小 | 与 ABI 无关 | arm64-v8a，16 KB page 需对齐 | 同左，且要逐 API level 验证 | 无关 |
| 真 TTY | **是**（slave 作 stdio） | 是（openpty+fork/exec） | 是 | **否** |
| resize | **无**（需对 slave 做 `TIOCSWINSZ`，JS 无 ioctl） | 有（原生 ioctl） | 有 | 无 |
| 会话/前台组 | **无**（无 setsid/TIOCSCTTY） | 有（原生 fork/exec + 会话归属） | 有 | 无 |
| 信号语义 | 仅 `child.kill(pid)`；master 上的 ^C 走 slave 行规程，可能打到 Node 自己的进程组（**需实测，有风险**） | 原生会话隔离，可精确发给前台组 | 同左 | 自己翻译 Ctrl-C 为 `SIGINT` 给子进程 |
| 与 payload 红线 | 兼容（已在树内） | 需新增 native 构件与许可审查 | **冲突**（明确“不带原生模块”） | 兼容 |
| 与 Android 权限 | 仅需能开 `/dev/ptmx` 且 `/dev/pts` 可见 | 相同 + SELinux 对 native 二进制 | 相同 | 无 |
| DSHA 证据 | 无（DSHA 不用它） | **有生产证据**：Termux terminal-view/terminal-emulator JNI，`libtermux.so`，PTY 环境由 `TerminalRuntime` 产出（argv `/bin/bash -l`，同一容器 runtime 的 env），见 [TerminalRuntime.java:70-96](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/runtime/TerminalRuntime.java#L70-L96)、[build.gradle](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/build.gradle)（`com.termux.termux-app:terminal-view:0.118.0`，Apache-2.0） | 无（上游无 android 预编译） | 无 |
| 工作量（估） | 1–2 天（resize 探针 + 模式上报 + 会话语义文档化） | 4–7 天（NDK 构件 + JNI/N-API + 16 KB + 许可 + 停止/回收） | 5–10 天且高风险 | 0（已存在） |

> DSHA 的 PTY **不能直接搬**：它在 proot/Ubuntu rootfs 里跑 `/bin/bash -l`，rootfs 提供 glibc bash 与完整 `/dev/pts` 绑定；MCA 是 bionic 单进程，没有 rootfs，也没有 bash。可搬的是“Termux JNI 的 openpty+fork/exec+ioctl 形状”，不是它的 argv/env。

### 3.3 设备约束（必须先证的事实）

1. `/dev/ptmx` 在 app 进程（untrusted_app 域）是否可开；
2. `/dev/pts` 是否挂在 app 的 mount namespace（payload 注释称“在该 App 命名空间里根本不存在（ENOENT）”，但这是参考包在它自己设备上的观察）；
3. 打开 ptmx 后，slave 是否出现在 `/dev/pts/<n>`（JS 方案靠枚举 + `rdev` 识别）；
4. `TIOCSWINSZ` 是否允许（需要 native；JS 无 ioctl，故 A 方案无论如何做不到 resize）；
5. ^C 通过 slave 行规程会发给哪个进程组（无 setsid 时可能是 Node 所在组 → **可能把宿主 Node 一起中断**，这是 A 方案必须先排掉的风险）。

### 3.4 最小探针（不写生产代码，但必须跑在 app 进程内）

> 注意：`adb shell` 是 shell UID，**不能**代表 app 的 SELinux/命名空间；探针必须由 MCA 的 `:dsh` 进程内的 Node 执行（例如现有诊断工具路径，或一次性 diagnostic 变体的临时脚本）。

```
P1  node -e "const fs=require('fs');const m=fs.openSync('/dev/ptmx','r+');console.log('ptmx ok',m);
             let s=null;for(let i=0;i<64;i++){try{const f=fs.openSync('/dev/pts/'+i,'r+');
             const st=fs.fstatSync(f);if(st.rdev){s=[i,f];break}fs.closeSync(f)}catch(e){}}
             console.log('slave',s&&s[0],'rdev',s&&fs.fstatSync(s[1]).rdev)"
P2  用 P1 的 slave fd 作 stdio，spawn /system/bin/sh -i，打印子进程前 200 字节输出，确认 PS1/回显存在
P3  在 P2 的会话里执行 stty size（期望 0 0 或 80 24——证明 resize 未生效）
P4  P2 会话里写 ^C，同时用 ps -o pid,pgid,sid,stat -p <node>,<child> 观察信号命中谁
P5  mount | grep -E 'pts|devpts'（app 内 /proc/self/mountinfo）
```

判据：P1 出 slave 且 P2 有提示符 ⇒ 真 TTY 可用（C08 的“isatty/交互输入/中文”可期）；P3 非 0 ⇒ resize 缺口确认；P4 命中 Node ⇒ A 方案必须先加原生 setsid/前台组，否则用户 ^C 会打断宿主。

---

## 4. (c) 推荐 V1 路径（含风险/工作量）

### 步骤 1（P0，0.5–1 天）：把平台 patch 行合并进 MCA 生成层（不是改路径）

- **不要改 overlay 路径**：home 级 `$DSH_HOME/cordis.patch.yml` 已会被官方读取。需要修的是 `DshGatewayConfigSeeder` 的生成内容：在 `buildOverlay()` 中补入 payload 模板 `dshhome/cordis.patch.yml` 的**允许行**（可把允许行提为基座后追加 MCA 行，但不得整文件照搬；现有 `DshGatewayConfigSeederTest` 断言 overlay 不得出现 `approval: never` 与 `tool-android`，改变策略必须显式改测试并说明）。home overlay 每次 host generation seed 都会重写，设备手改不持久，修复必须落在 `buildOverlay()`/seeder。
- 必选行：`- id: bash-sandbox` + `disabled: true`（换成 `bash-local` 提供 `ctx.shell`，cwd 用 `DSH_WORKSPACE`）；保留 MCA 的 `llm-pi-ai` provider 与 `workspace-controller.documentsDirectory`。
- 取舍行：模板里的 `tool-shizuku/tool-android/tool-accessibility/tool-vscreen/tool-browser` 是参考 App 的实现，MCA 计划用自己的 capability 桥（C11）替代；未决定前不得把它们当作已完成能力，也不得因为不合并而让既有 DSH 入口缺工具——逐行记录保留/替换/退出条件。
- `permission` 决策：不得继承模板 `defaultPreset: danger-full-access`。但 `bash-local` 只声明 `sandboxMode = workspace-write`、并不调用 `ctx.sandbox`，因此在没有真实 OS enforcement 时，“workspace-write”只是声明；必须在 UI/能力清单如实说明，或选择经审查的等价 preset。此项需产品决策并留证据。
- 退出条件：composed tree（`--dump-config` 等价入口或注入点）里 `bash-sandbox` disabled、`bash-local` 存在；设备上 bash 工具执行 `echo ok` 不报 `SANDBOX_UNAVAILABLE`；模型列表仍读到 MCA 网关；单测覆盖“平台行已合并”而非仅文件内容。
- 风险：`bash-local` 改变 `ctx.shell` 提供者，需回归 `tool-bash`/`tool-jobs`/permission presets；仍需保留 `dsh-sandbox` 服务（参考注释说明撤掉会让 ptc-runtime 永久 pending）。

### 步骤 2（P0.5，1–2 天）：受管窄 launcher，解决脚本 EACCES

- 方案：**一个多调用 native 垫片**（NDK 构建，arm64，16 KB 对齐），以 `libdshwrap.so` 打包、在 payload/`bin` 下建成 `npm/npx/pnpm/pip` 的符号链接；垫片按 `argv[0]` basename 映射到固定脚本，`execve("/system/bin/sh", ["sh", "<payload>/bin/<name>", ...argv])`。`python*` 由现有 `libdshpython.so` 重链提供，不纳入本次 shim 映射；若设备验证解释器不可用，再单独立项。
- 保留包装脚本的 prefix/env 行为（`NPM_CONFIG_PREFIX`、`--config.package-import-method=copy`、`PYTHONHOME`、`LD_LIBRARY_PATH`）；**不接受**任意环境变量替换执行目标。
- 不选“把脚本改成 `.so`”这类 hack：脚本不是 ELF，改名不能获得 exec 权限。
- 退出条件：`npm --version`、`npx --version`、`pnpm --version`、`pip --version`、`python -c "print(1)"`（由现有 native 重链验证，不在 launcher 映射内）、`git --version`、`curl --version`、`rg --version` 全部通过，且 `.bin`/shebang/postinstall 单列验收。
- 风险：新增 native 构件 → 需要来源/ABI/Bionic/16 KB/SHA/许可记录。

### 步骤 3（P1，2–4 天）：PTY 收口

- 保留现有 JS PTY；新增**模式诚实上报**（`pty` 或 `pipe` + 失败原因，`spawnPtyCompat` 已有日志，扩到 seam 状态）与 **resize 能力位**；`resize` 明确返回“不支持”而不是静默 no-op。
- 跑 §3.4 探针；按结果二选一：
  - pts 可见、^C 不打 Node ⇒ 先接受“无 resize 的真 TTY”，把 resize/前台组作为已知限制写进能力清单；
  - pts 不可见或必须支持 TUI resize ⇒ 接 Termux JNI：最小实现是 native helper（openpty + setsid + TIOCSCTTY + TIOCSWINSZ + fork/exec `/system/bin/sh`），Node 侧通过 N-API 或 socket 交互；**不引入 node-pty**。
- 退出条件：isatty、交互输入、中文、Ctrl-C、resize→`stty size`、前后台组、孙进程、断连重连、terminate、host stop；输出缓冲有界。

### 不建议（V1）

- **node-pty Bionic 自建**：上游无 android 预编译、需 node-gyp/NDK，且与 payload“无原生模块”的红线正面冲突；收益（resize）可由 Termux JNI 以更低耦合拿到。
- **GNU Bash 构件**：默认保持 mksh 并在 UI/文档暴露“bash 实为 mksh”；要真 bash 需单独立项（构建、许可、16 KB、方言回归），不能与 C07 混做。

---

## 5. 必须明确“不得声称”的项

1. 不得声称“C08 已支持真 PTY”：resize 是 no-op、无 controlling tty/前台组、`/dev/pts` 未验证；**管道回退不得计为 PTY 通过**。
2. 不得声称“npm/npx/pnpm/pip 可用”：包装脚本在可写目录，exec 会被拒；`python*` 重链后的解释器身份与 `pnpm --version` 一样不能代表生态可用。
3. 不得声称“bash 是 GNU Bash”：目前是指向 `/system/bin/sh` 的符号链接（mksh）。
4. 不得声称“workspace-write 沙箱已生效”：`bash-local` 的 `sandboxMode` 是硬编码声明（`dsh-bash-local/lib/index.js:75-77`），没有 enforcement；真正 fail-closed 的是 `bash-sandbox`，而它在无 runner 时直接拒绝执行。
5. 不得用 `--version` 或“命令存在”代替真实执行证据。
6. 不得把本文的 F3–F5 推论当作已完成诊断：F4 的具体失败面（`SANDBOX_UNAVAILABLE`）需在设备上抓一次真实 bash 工具调用确认；F1/F2 的旧结论已于修订 1 更正。

---

## 6. 待证清单（交给执行者）

| 编号 | 要证什么 | 最小方法 | 判据 |
|---|---|---|---|
| V1 | 平台 patch 行是否进入生效树 | 在设备 DSH home 查 `cordis.patch.yml` 是否含 `bash-sandbox disabled`/`bash-local`；或用 composed tree 入口核对；再看 bash 工具首次调用是否 `SANDBOX_UNAVAILABLE` | 缺行/报错即证实 F3/F4；home overlay 存在不等于平台行已合并 |
| V2 | 包装脚本 exec 是否 EACCES | 在 `:dsh` 进程内 `sh -c 'npm --version'`，抓 stderr | `Permission denied` |
| V3 | `/dev/ptmx`、`/dev/pts` 在 app 内可用性 | §3.4 P1 | 有 slave/rdev |
| V4 | ^C 命中谁 | §3.4 P4 | 命中子进程而非 Node |
| V5 | resize 是否真的无效 | §3.4 P3 | `stty size` = 0 0 |
| V6 | python3.14（4,448 B ELF）到底是什么 | `file`/`readelf -d` + 跑 `-c "import sys;print(sys.version)"` | 能打印版本才叫 Python |

---

## 7. 证据索引

- payload 平台 patch：`payload/dshhome/cordis.patch.yml`（当前 79 行，`dsh-android-patch: v4`）——disable `bash-sandbox`/`pwsh-sandbox`/`session-log-download`/`session-telemetry-otel`；permission 危险默认 `danger-full-access`（该档 `approval: never`）；insert `bash-local` 与参考 Android 工具行。该文件不在 MCA seeder 的复制清单内。
- payload 包装脚本：`payload/bin/{bash,npm,npx,pnpm,pip,python,python3}`（字节与内容见 §2）。
- payload ELF：`payload/runtime/bin/{node,curl,rg}`、`payload/bin/git`、`payload/python/bin/python3.14`（均 `7f 45 4c 46`）。
- JS PTY：`payload/.../dsh-subprocess-local/lib/index.js:944-1176`（openAndroidPty 944 / spawnWithPty 976 / resize 1046、1159 / spawnWithPipes 1064 / spawnPtyCompat 1164-1176）。
- 终端后端（可选 bundle）：`payload/.../dsh-experimental-terminal-bundle/cordis.patch.yml`；`dsh-terminal-bash/lib/index.js:9-15,31-33`（默认 `/bin/bash --noprofile --norc -i`）。
- 默认 profile bundles：`payload/dshhome/profiles/web/package.json`（仅 `dsh-base` + `dsh-web-app`）。
- 官方 profile/home 层解析（payload 归档）：`dsh/lib/profile-boot-C14i-8IN.js`（`homePatchPath()`，home 层 outranks profile 层）；`dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js`（`loadProfileDirectory` 的 profile 级 patch、`readProfilePatches()` 的 home 级 join）；CLI `--patch` 同文件。
- sandbox 运行链（payload 归档）：`dsh-base/cordis.patch.yml`（bash-sandbox/sandbox-policy/permission 行）；`dsh-bash-sandbox/lib/index.js`（confine/fail-closed）；`dsh-bash-local/lib/index.js`（sandboxMode 声明、不调用 ctx.sandbox）；`dsh-sandbox-local/lib/index.js`（landlock-run 依赖）。
- native 构件：`node-addon-system` 的 optional 平台包只有 darwin/linux；payload 全树无 `.node` ⇒ Android landlock runner 不可用。
- MCA 侧：`DshGatewayConfigSeeder.kt:383,614-616`、`DshNodeCommand.kt:33-92`、`DshAndroidRuntimeAssembly.kt:110-175`。
- DSHA：`TerminalRuntime.java:70-96`（PTY argv/env）、`WebLaunchCommand.java:17`（`DSHA_WORKSPACE_DOCUMENTS`）、`build.gradle`（Termux terminal-view 依赖）。

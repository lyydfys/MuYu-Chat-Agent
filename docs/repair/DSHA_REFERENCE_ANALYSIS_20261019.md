# DSHA 参考分析：面向 MCA 独立 Android DSH 模式的对照、采纳与拒绝

记录日期：2026-10-19（Asia/Shanghai）。范围：开源项目 [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA)（把官方 `@deepseek-ai/dsh` 跑在 Android 上的第三方启动器）与 MCA 现有独立 DSH 模式实现之间的逐项对照。

**状态：只读研究与文档交付。本文不运行任何 DSHA 代码，不安装 APK，不修改 MCA 生产代码，不执行构建、测试或设备验证。本文不改变 MCA 现有任何 `PASS / FAIL / NOT_RUN / BLOCKED` 状态。**

关联文档：[项目工程合同](../architecture/MCA_PROJECT_HARD_LOCK.md)、[内嵌原版 DSH 方案 V1](../proposals/agent-dsh-embedded-v1/ARCHITECTURE.md)、[V1 基线](../proposals/agent-dsh-embedded-v1/BASELINE.md)、[侧栏入口故障交接](DSH_SIDEBAR_ENTRY_HANDOFF_AND_FIX_PLAN_20261019.md)、[移动 WebView 补充分析](DSHA_REFERENCE_ANALYSIS_20261019_ADDENDUM_MOBILE_WEBVIEW.md)。

---

## 0. 一页结论

1. **DSHA 是“容器派”路线**：APK 内置完整 Ubuntu 24.04 arm64 rootfs，用 bxroot（优先）/ proot（兼容）在应用私有目录里跑真 glibc 环境，再在里面跑 Node 24 + pnpm + `@deepseek-ai/dsh 0.2.0-rc.2`。MCA 是“bionic 原生载荷”路线：把 Termux bionic 版 Node 打包成 `libdshnode.so`，直接从 `nativeLibraryDir` 执行，跑在同一套 bionic 环境里。两者都遵守 Android 10+ 的可写目录 W^X 限制，但绕开的方式不同（见 §3.2、§4.2）。
2. **DSHA 最值得抄的不是 proot，而是“鉴权交接”和“失败分类”**：它在 Java 侧完成 dsh 的 launch-token → cookie 交换，把 HttpOnly cookie 直接注入 WebView 再加载干净 URL，token URL 从不进入 WebView；并且明确规定“页面/流/鉴权异常一律不授权重启 Node”。这两条对 MCA 是低成本、高收益、零架构变更。
3. **DSHA 的就绪判定比 MCA 弱**：DSHA 把“官方启动行打印出鉴权 URL”当成就绪，MCA 的是 5 步门（303 → HttpOnly cookie → `__DSH_BOOT_READY__` → client 插件资源 → 第二次稳定 → 未认证 401）。**这一条必须拒绝降级。**
4. **DSHA 的 PTY 是真 PTY**（Termux terminal-emulator JNI + 同一套 proot/rootfs 环境），MCA 侧完全没有任何 PTY 代码（全仓 `*.kt` 检索 `pty|termux|terminal-emulator` 零命中）。这是两条路线最实质的能力差，也是唯一能论证“未来需要 proot/bxroot 兜底”的硬需求（见 §4.2、R6）。
5. **DSHA 的看门狗语义严谨**（15 s 探活、连续 3 次、120 s 冷却、只有 `ConnectException` 才算重启候选、等待鉴权期间永不自动重启、慢启动只提示不杀），MCA 目前没有看门狗、没有冷却、没有分层探针，只有页面驱动的 `retry()`/`restart()`。建议只借鉴“分类 + 冷却”，不要照搬“后台常驻自动拉起”（见 R3、R4）。
6. **proot 在 targetSdk 37 上跑生产包（273 MiB / 345 MiB APK）是我们 H2 选择的外部旁证，但不是我们自己的证据**：DSHA 从 `nativeLibraryDir/libproot.so` 直接 exec，证明“nativeLibraryDir 里的 ELF 在 targetSdk 37 可执行”这个机制方向成立；它**不能**证明 49 MB 的 `libdshnode.so`（Node 自身有 loader/DT_NEEDED 依赖）在我们包里也能 exec —— 那个结论仍只由 MCA 自己的 R01 探针支撑（见 §1.4、§6）。
7. **不要抄 DSHA 的默认权限档**：DSHA 默认 `danger-full-access`（README「已知限制」明写），MCA 的 seeder 明确写死 `workspace-write` + `approval: ask`，这是必须保留的拒绝项。
8. **不要抄它的数据/备份引擎**：DSHA 有完整的宿主侧备份格式（v5 无密码 tar.gz + 内部加密自动副本 + 受管更新事务 + 回切证明）。MCA 的合同是“DSH 是它自己数据的唯一 writer”，引入第二套备份格式会同时违反硬锁 §3.2 与方案 §10。只值得借“凭据不进备份”和“代次绑定引用”两个原则。
9. **包体代价必须直说**：走 DSHA 路线等于 +273 MiB 起的 APK、双内核（WebView + Gecko）、rootfs 冷安装 42.4 s（其自身 README 记录，未达 30 s 目标）。MCA 现在是 160 MB payload + 62.5 MB jniLibs 的 bionic 方案，已经不小，但结构简单一档。
10. **许可证上 DSHA 整体 MIT，但它捆的 proot 是 GPL-2.0、bxroot 是独立项目、Termux 组件是 Apache-2.0、GeckoView 是 MPL-2.0**；“仓库 MIT”不能当作全部文件 MIT。MCA 自己的 payload 已经带 `git`（GPL-2.0）与 readline（GPL-3.0）的再分发义务（见 §7）。

---

## 1. 证据方法与边界

### 1.1 抓取方式与时间

- 抓取窗口：2026-10-19 23:2x – 23:5x（Asia/Shanghai）。
- 方法：对 `raw.githubusercontent.com` 逐文件 `web_fetch`（HTTP 200，返回真实文件正文）；用 `github.com/DSH-APP/DSHA/commits/main.atom` 取提交上下文；用 `data.jsdelivr.com` 的文件清单做**文件名与大小盘点**（仅用于确认文件存在与体积，不作为内容证据）。
- 失败记录：`api.github.com/repos/DSH-APP/DSHA` 返回 **429 rate limit exceeded**（出口 IP 174.136.204.56），因此本报告**没有**使用 GitHub REST API 的 `default_branch` / `stargazers_count` / tree 递归结果。凡涉及“星星数量”“最新 commit”的陈述都来自 atom feed 或仓库自身文件。
- 本报告**没有**克隆仓库、没有逐文件核对其全部源码、没有运行其测试或回执。读取清单见附录 B。

### 1.2 DSHA 版本身份与提交上下文

| 项 | 值 | 证据 |
|---|---|---|
| 仓库 | `DSH-APP/DSHA` | [github.com/DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) |
| `main` 最新提交 | `d5b40935f86f360f6634cfdbc4a2f780a9af9ae3`（2026-10-19T04:40:49Z，**仅 star-history 自动提交**） | [commits/main.atom](https://github.com/DSH-APP/DSHA/commits/main.atom) |
| 最近一次源码同步提交 | `810fb03b9575e1e816d086e6556ca1c1ae99b029`（2026-10-14T18:57:05Z，"同步 build173 源码并刷新 Star History"） | 同上 |
| 已发布正式包 | tag `v0.2.0-rc2` = **build170**（README 下载表）；当前源码是 **build173**，未重新发布 APK | [README.md](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/README.md) |
| 星标（最近一次自动提交） | 1629（2026-10-19T04:40Z） | atom feed 标题 |
| `applicationId` / Java 包 | `com.dsh.client` / `com.deepseekharness.app` | [app/build.gradle](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/build.gradle) |
| `versionCode` / `versionName` | `173` / `0.2.24-20261014.1308-dsh0.2.0-rc.2` | 同上 |
| compile / target / min SDK | `37` / `37` / Standard 30、Low 23 | 同上（`compileSdk { version = release(37) }`、`targetSdk 37`、flavor `minSdk`） |
| ABI | 仅 `arm64-v8a` | 同上（`abiFilters 'arm64-v8a'`） |
| 语言 / 构建 | Java 17、无 Kotlin；AGP 9.1.1、Gradle Wrapper 9.3.1、NDK 26.3.11579264、`platforms;android-37.0` | [BUILD.md](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/BUILD.md) |
| 内置运行时 | Ubuntu 24.04 noble arm64（base 版本号 10）、Node.js 24、pnpm 10.34.5、`@deepseek-ai/dsh 0.2.0-rc.2` | README、[runtime-descriptor.json](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/runtime-descriptor.json)（`dshVersion: 0.2.0-rc.2`、`baseVersion: 10`） |
| 发布包体 | Standard `273.10 MiB`、Low `345.27 MiB` | README 下载表 |

> **引用形式说明**：下文 DSHA 源码链接统一指向提交 `810fb03b…`（源码同步点）。我在 2026-10-19 23:2x 实际读取的是 `main` 分支的同名文件；`main` 在 `810fb03b` 之后只有 README 与 `docs/star-history.*` 两个系列的提交，因此这些源文件内容**应当**等于 `810fb03b` 快照，但我**没有逐字节比对**，不能把它当严格证明。链接文本用文件名，URL 中可核对完整路径。

### 1.3 MCA 侧快照

| 项 | 值 |
|---|---|
| 工作目录 | `E:\MAC` |
| 观察到的 HEAD | `f39e8fc9724523f8ae32d0c3ae540d14d62215cd`（本次阅读时；此前文档记录过 `4436e52`、`21f8b1b3`、`98eb8e47`，说明**并行任务仍在改动工作区**） |
| 工作区脏度 | `git status --porcelain` 83 条（同一时刻计数） |
| DSH 模块 | `core/dsh-contract`、`core/dsh-runtime`、`api/dsh-gateway`、`feature/dsh-workspace`、`app/.../dsh`、`app/.../composition/Dsh*` |
| 已落盘载荷 | `core/dsh-runtime/src/main/assets/dsh/payload.zip` 160,464,248 B；`jniLibs/arm64-v8a/libdshnode.so` 49,715,528 B，另有 `libdshrg/libdshcurl/libdshgit/libdshgitremotehttp/libdshgitremotehttps/libdshpython`（合计 jniLibs 62,540,944 B） |
| 载荷内核 | `dsh-0.2.1-rc.2+android-ref-c324272bff00`，官方基线 commit `687f282e…`，参考包 `woaiys3/deepseek-harness-android-app` v1.20.1 |

按硬锁 §2/§13，以上是“拍摄时刻”的记录，**不是可复现构建输入**；HEAD 与工作区在本报告写作期间仍在变化，任何人接手前必须重新核对。

### 1.4 本报告不做什么（读结论前先读这一节）

- 不声称 DSHA 的任何行为在 MCA 的设备、包名、targetSdk、签名或数据上成立。
- 不把 DSHA 的“仓库里存在某机制”写成“MCA 可以照搬”。每条建议都标注了落点、最小实现、验证方式与回退。
- 不把 DSHA 的 payload/loader 结论外推到 MCA 的 payload：DSHA exec 的是 `libproot.so`（2xx KB 级静态闭包入口），MCA exec 的是 49 MB 的 Node ELF。**两者对 linker/DT_NEEDED/`process.execPath` 的要求完全不同**。
- 不声称任何许可证结论是法律意见（§7）。
- 不修改 MCA 代码、不跑构建。所有 MCA 侧陈述都来自本次**只读**源码阅读，未执行。

---

## 2. DSHA 是什么（结构层面）

### 2.1 分层与职责

来源：[AGENTS.md](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/AGENTS.md) 的“分层与归属”表，以及本次实读的类文件。

| 层 | 关键类 | 职责（DSHA 自己的定义） | 实读证据 |
|---|---|---|---|
| `ui/` | `MainActivity`、`WebPreviewActivity`、`PreviewAuth`、`TerminalFragment` | 界面与启动门禁；WebView 宿主 | [WebPreviewActivity.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java) |
| `core/` | `HarnessController`、`WebLifecycleController`、`StartupDiagnostics` | 兼容门面 + 唯一 Web 编排 + 应用状态 | [WebLifecycleController.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java) |
| `runtime/` | `ProotBootstrap`、`RuntimeExecution`、`ContainerRuntime`、`RuntimeLauncher`、`WebProcessManager`、`TerminalRuntime` | 运行门面、执行租约、引擎选择、BINDS、argv/env 构造、停止、PTY | 见 §3.2/§3.5 |
| `backup/` | `HostDataTransaction`、`ManagedRuntimeTransaction`、`AutomaticBackups`、`ColdInstallTransaction` | 备份/恢复/受管更新事务 | 见 §3.8 |
| `data/` | `KeyVault` | Keystore AES/GCM 加密 API key | 见 §3.8 |
| `bridge/` | `HttpShellService`、`AppDeviceActions`、`DeviceFileCore` | 3090 设备能力桥（token 门控） | 见 §3.9 |
| `util/` | `WebLaunchCommand`、`DshAuthUrl`、`WebHealthProbe`、`WebProcSel`、`ShellQuote`、`Query`、`BackupScope` | 纯逻辑，**必须**配单测，禁 Android import | 见 §3.3/§3.7 |

### 2.2 相比 MCA 的关键结构选择

| 选择点 | DSHA | MCA |
|---|---|---|
| 运行时形态 | 完整 Ubuntu rootfs + bxroot/proot；真 glibc、真 PTY、`apt` 可用 | 单进程 bionic Node（`libdshnode.so`）+ 预打包工具 ELF；无 rootfs、无 PTY |
| 谁定义“就绪” | 官方启动行（`dsh web: <url>`）出现即视为可用 | 5 步 HTTP 门 + 认证围栏（§3.3、§4.2） |
| 谁发起 DSH | 用户点“启动”，前台服务常驻，看门狗托管 | 页面进入时 `ensureStarted()`（按需），无看门狗 |
| 进程隔离 | 同一 Android UID、独立 Node 进程、独立 rootfs 目录 | 同一 UID、独立 `:dsh` Service 进程 |
| 模型 | 用户填 `DEEPSEEK_API_KEY`，官方 provider 直连 | MCA 单一推理 owner + loopback OpenAI 兼容网关（`api/dsh-gateway`） |
| 操作系统耦合 | 高（rootfs、proot、SELinux `link(2)`、16 KB 页、Gecko） | 中（bionic ELF exec、`nativeLibraryDir`、原生工具链接） |
| 包体 | 273–345 MiB | 160 MB payload.zip + 62.5 MB jniLibs（另有重复存放，见 §4.1） |

---

## 3. DSHA 逐子系统实现（实读源码）

### 3.1 环境供给：rootfs 打包与冷安装

**事实：**

- 离线 rootfs **不入 Git**；构建时由生成器产出，资产标记为 `offline-rootfs.layout=split-runtime-v1`，Ubuntu 与 `dsh-runtime.bin` 分包：“冷安装两份都解压，局部更新只读 dsh 包”（AGENTS.md，`offline-rootfs.layout=split-runtime-v1` 条目）。
- `app/build.gradle` 里 `androidResources { noCompress += ['gz','xz','bin','ja','tar','whl'] }`，并注明 **为什么改名 `.bin`**：“`bin` 是离线 rootfs：aapt 会把 `.tar.gz` 静默解成 `.tar`，所以打包前改名 `.bin`，保持 STORED（不 deflate）”。这是一个**很容易踩的真实坑**。MCA 侧的对照事实是：本次 grep `app/build.gradle.kts` **没有找到** `androidResources { noCompress = ... }` 声明；160 MB 的 `dsh/payload.zip` 是否被 aapt2 二次压缩、是否与 jniLibs 重复，本报告**未核对**（列入 U5）。
- 资产由 `tools/prepare-standard-assets.py` 生成到 `app/build/generated/standardAssets`，构建校验 `tools/asset-deployment.json` + `verify-asset-deployment.py`；“不直接修改原始 rootfs”。
- 运行时描述符 `runtime-descriptor.json` 是**受管资产身份**：`runtimeId`（sha256）、`baseVersion`、`dshVersion`、`launcherContract: DSHA_ARM64_V2`、以及**逐个 launcher 输入文件与 native 库的 sha256**（含 `HarnessService.java`、`ProotBootstrap.java`、`libproot.so`、`libbxroot-*.so`、`libdsha-session.so`、`libtermux.so`）。
- 冷安装是**事务**：`ColdInstallTransaction` / `ColdBundleTransaction` / `ColdSplitExtraction`，装完才 `markOfflineExtracted()`；装失败不影响已有健康树。
- 受管更新要“真隔离试运行”（`RuntimeTrial`：静态资产/原生模块、实际端口/鉴权、存储和会话写后重开、guest 退出）通过才提交，失败自动回切。
- 实测冷安装耗时：README 记录历史完整冷安装 **42.412 s**，明确写“尚未达到 30 秒目标”。

**对 MCA 的含义：** MCA 的 payload 是“一次性解压 + 校验 + rename 原子替换”（§4.1），没有“试运行后提交”这一层；在只有一份 bionic payload 的前提下这够用，但**如果未来引入 proot rootfs，`RuntimeTrial` 这一层是必需品**（R9）。

### 3.2 容器运行时执行：bxroot/proot、`nativeLibraryDir`、BINDS、env

这是 DSHA 最“硬”的一段，也是最容易被误抄的一段。

**(a) 引擎抽象**（[ContainerRuntime.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.java)）

```java
public interface ContainerRuntime {
    String id(); String displayName();
    boolean available(); String unavailableReason();
    List<String> baseArgv(File rootfsDir, boolean hardlinkSupported);
    List<String> baseArgv(File rootfsDir, boolean hardlinkSupported, File dataDomain);
    void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir) throws IOException;
    void prepare() throws Exception;
}
```
生产实现两个：`BxrootRuntime`（默认，独立 Android ULX 闭包）与 `Proot`（Termux proot，兼容/显式选择）。`Proroot` 已退休（`available()` 恒 false、`PROROOT_RETIRED`）。**接口注释写明**：“两个引擎均由宿主 subreaper 证明 owned 树空及 launcher 退出；引擎退出码不能替代该屏障。”

**(b) proot 的 argv 构造**（同文件 `Proot.baseArgv`）

```java
argv.add(nativeLibProot.getAbsolutePath());          // ← nativeLibraryDir/libproot.so
if (!hardlinkSupported) {
    argv.add("--link2symlink");
    File l2s = new File(rootfsDir, ".l2s"); l2s.mkdirs();
    argv.add("-b"); argv.add(l2s.getAbsolutePath() + ":" + l2s.getAbsolutePath());
}
argv.add("-L"); argv.add("--kill-on-exit"); argv.add("-0");
argv.add("--rootfs=" + rootfsDir.getAbsolutePath());
argv.add("--cwd=/root");
// 然后逐个 Bind：-b host[:guest]，宿主机路径不存在的 bind 会被记录 BIND_SKIPPED 并跳过
```

配套的三条**真实踩坑记录**（代码注释原文，价值很高）：

1. `--link2symlink` **只在文件系统不支持硬链接时才加**：“Android app 私有目录（/data/…，ext4/f2fs）本来就支持硬链接，扩展纯属多余”；而且“会破坏 dsh write 工具”。
2. `.l2s` 目录**必须按宿主绝对路径双绑定**：“L2S 链保存宿主绝对路径，容器必须能按同一路径访问，否则 dpkg 的 chown/stat 报 ENOENT。”
3. **不映射 `/linkerconfig`**：“app 进程对它无权限（selinux），proot 每次启动都会打 `can't sanitize binding "/linkerconfig": Permission denied`，污染终端/日志；而且 bind 失败 = 从没绑上，映射它是纯负收益。”

**(c) BINDS 清单**（`ContainerRuntime.binds()`，两引擎共用一份）：

```java
/dev → /dev,  /dev/urandom → /dev/random,  /proc → /proc,  /sys → /sys,
/system → /system,  /apex → /apex,  /proc/self/fd → /dev/fd,
/storage/emulated/0 → /sdcard,  /storage/emulated/0 → /storage/emulated/0
```
注释解释 `/system`、`/apex` 的原因：“Bundled Termux Python 要用 Android 的 linker 和 APEX 库”。

**(d) 环境变量**（`Proot.applyEnv`）

```java
File directory = nativeLibProot.getParentFile();                       // nativeLibraryDir
String loader = name.equals("libproot_legacy.so") ? "libprootloader_legacy.so" : "libprootloader.so";
pb.environment().put("PROOT_LOADER", new File(directory, loader).getAbsolutePath());
pb.environment().put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());       // files/linux/tmp
if (settings.disableProotSeccomp) put("PROOT_NO_SECCOMP","1"); else remove(...)
pb.environment().put("LD_LIBRARY_PATH", libDir + ":" + directory);      // files/linux/lib : nativeLibraryDir
if (new File(directory,"libprootloader32.so").isFile()) put("PROOT_LOADER_32", ...)
prepare() 里还硬校验 files/linux/lib 下存在 libtalloc.so.2 与 libandroid-shmem.so，否则抛 PROOT_DEPENDENCIES_MISSING
```

**这里有一条对 MCA 极重要的结构结论**：DSHA 只有**被 exec 的 ELF**放在 `nativeLibraryDir`（PackageManager 管理、不受 W^X 限制）；**被 dlopen 的 .so** 放在 `files/linux/lib`，靠 `LD_LIBRARY_PATH`/`PROOT_LOADER` 引导。也就是说，“Android 10+ 限制”只约束 exec 的那一个文件，不约束共享库。MCA 的 `DshNodeCommand.environment` 采用完全一致的思路（`LD_LIBRARY_PATH` 指向 `payload/runtime/lib`），并且额外把工具 ELF 也搬进 `nativeLibraryDir` 再用符号链接指回 payload（§4.1）。

**(e) 统一 argv/env 出口**（[RuntimeLauncher.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java)）

- 所有执行都收敛到一个形状：`<runtime baseArgv> [-b …]* [-b preloads] /bin/bash -c "<command>"`，`redirectErrorStream(true)`，隔离/管道模式才保留 stdin，否则 `Compat.redirectStdinDevNull(builder)`。
- 固定环境：`HOME=/root`、`PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`、`TMPDIR=/tmp`、`DEBIAN_FRONTEND=noninteractive`，再由 `RuntimeTools.applyEnvironment` 注入随包 CA / npm / npx 等。
- `NODE_OPTIONS` 统一注入 `--require=<guest>/bridge-token-compat.cjs`（桥 token 兼容 shim）。
- bxroot 与 proot **环境互斥清理**：bxroot 分支 `removeIf(key → key.startsWith("PROROOT_") || key.startsWith("PROOT_"))` 并设 `DSHA_BXROOT_STRICT_EXEC=1`、`BXROOT_AUTO_HOST=1`、`BXROOT_HOST_PATH=""`、`BXROOT_REENTRY=0`；proot 分支反向清 `BXROOT_*` 并 `remove("LD_PRELOAD")`。最后写 `DSHA_RUNTIME_ENGINE=<id>`、`DSHA_CACHE_DIR=<scratch>`。
- bxroot 前置**只读资格探针**（`RuntimeEngineQualification`），按 `fingerprint + :bxroot` 缓存通过结果，**在一个独立数据域、不加载 profile/用户命令/个人数据绑定**的前提下探测；不通过则 `copyWithRuntime(spec, ports.capabilityFallback(...))` 掉到 proot。这是“bxroot 优先、proot 兼容”的可审查实现，而不是 try/catch 掩盖。
- 退出语义：`IsolatedInstallProcess.start(builder, tmp, context, spec.record)` + `ports.businessDispatched()` **在调用 start 之前**打点，注释写明“START 可能先到达 guest 再返回/抛异常”，因此“未派发”与“已派发但结果未知”必须区分 —— 这正是 MCA 侧 `DshRuntimeOwner` 用 epoch/stop fence 表达的同一件事。

### 3.3 DSH 启动、鉴权与“就绪”

**(a) 启动命令是一个字符串，且 pidfile 在 exec 之前**（[WebLaunchCommand.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/util/WebLaunchCommand.java)）

```java
"export DSH_HOME=/root/.dsh && " +
"export DSHA_WORKSPACE_DOCUMENTS=/root/Documents && " +
"export DSH_PERMISSION_MODE=" + ShellQuote.arg(permissionMode) + " && " +
"export DSH_CONFIRM=" + (confirmShell ? "1" : "0") + " && " +
"export BROWSER=true && " +
"export DSHA_PRELOAD_PREVIOUS=\"${NODE_OPTIONS-}\" && " +
"export NODE_OPTIONS=\"--import=/root/.dsh/startup-observer.cjs ${NODE_OPTIONS-}\" && " +
"export DSHA_UI_LANGUAGE=... && DSHA_STARTUP_PROFILE=... && DSHA_WEB_GENERATION=" + generation + " && " +
"cd /root && rm -f <identity file>; echo $$ > <pidfile> 2>/dev/null; " +
"[ ! -e <stop sentinel> ] || exit 0; " +
"exec dsh " + ("web".equals(profile) ? "web" : "--profile " + ShellQuote.arg(profile)) +
" --no-open --host 127.0.0.1 --port " + listenPort;
```

要点：
- `echo $$ > pidfile` **必须在 `exec` 之前**，因为 `exec` 不换 pid（AGENTS.md 明确列为“已知 trap”）；
- 先清 identity 文件、写 pid、再检查 stop sentinel，最后 `exec dsh`；
- `listenPort` 可以是 **0**（动态端口），由官方 web server 自己选；
- token **不在 argv**（注释：“no credentials in argv”），API key 通过 `launchEnvironment` 走环境变量。
- DSHA 走的是 `dsh`（容器 PATH 里的 `/usr/local/bin/dsh`）CLI，而不是直接 `node lib/bin.js`；MCA 走 `node --expose-internals <payload>/dshroot/.../lib/bin.js`。两者的 loader 行为差异见 §4.2。

**(b) 鉴权 URL 的严格解析**（[DshAuthUrl.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/util/DshAuthUrl.java)）

```java
private static final Pattern AUTH_URL =
    Pattern.compile("^(http://127\\.0\\.0\\.1:\\d+/)\\?token=([A-Za-z0-9_-]{43})$");
private static final Pattern STARTUP =
    Pattern.compile("^dsh web: (\\S+)(?:[ \\t][^\\r\\n]*)?$", Pattern.MULTILINE);
```
- 只认 `127.0.0.1`、只认 43 位 base64url token（注释：官方用 `Buffer.toString('base64url')`）；
- 同一输出里出现**两个不同** auth URL 直接判 null（防日志拼接/重放）；
- 还有 `findAny(output, port)`：**进程输出只能为本轮配置的端口提供凭据**；
- `redact()` 把 `token=...` 打码；`extractCookie()` 从任意 `Set-Cookie` 里挑 `dsh-auth-*`，不假设它是第一条。

**(c) “打印鉴权 URL” = 就绪**（[WebLifecycleController.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java)）

`WebOutputSession.drain(p, Events{…})` 逐行读 guest stdout；当 `DshAuthUrl.findAny(...)` 命中：

```java
public void authentication(String url) {
  synchronized (lifecycle) {
    if (!current() || authenticated()) return;
    if (!state.recovery.failed(generation)) {
      if (!publishAuthenticatedRun(lifecycle, run, url)) return;
      config.rememberFallbackWebPort(run.requestedPort, run.port);
      config.rememberWebOriginPort(run.port);
      webProc.recordIdentity();                    // ← 写 pid 的出生身份
      setWebStage(generation, "服务已就绪，等待进入网页");
      lifecycle.finishStart(generation);           // ← 这里才结束 STARTING
    }
  }
  notifyReadyWebPageChanged();
  new Thread(() -> afterAuthenticated(generation, onStatus, safeMode), "dsha-web-ready").start();
}
```
> 语义差异（重要）：DSHA 的 READY = “官方 loader 树已稳定到打印带 token 的 URL”。它**不**验证 index HTML、**不**验证 client 插件资源、**不**验证未认证 401。MCA 的 `DshReadinessGate` 都做（§4.2）。

**(d) 慢启动：不设强杀时限**

- `io.schedule(() -> reportSlowStart(generation, onStatus), 60, TimeUnit.SECONDS);` → 60 s 只发一条“启动时间较长，仍在等待鉴权；可查看日志或手动停止”，**不累计失败、不写 sentinel、不杀进程**（`reportSlowStart` 方法注释原文如此）。
- AGENTS.md 对应条目：“Web 启动等待鉴权不设强制终止时限，60 秒仅提示；保持 `isStarting`，避免看门狗把慢启动当故障”。

**(e) 端口策略**

`WebPortPolicy.choose(forceDynamic ? 0 : preferred, forceDynamic ? 0 : fallback, available)`，然后：
- 首选端口被占 → 状态提示“首选 Web 端口已占用：N”“正在自动选择可用 Web 端口，首选端口设置保留”，**不覆盖用户配置**；
- 成功后 `config.rememberFallbackWebPort(...)`、`config.rememberWebOriginPort(...)`（下次复用实际可用端口）；
- 仅当“单一明确的 bind 冲突 + 本轮 guest 已核验退出 + 未鉴权 + 只重试一次”时才 `retryAfterPortConflict` → `forceDynamicPort = true` → 用 `--port 0` 重来；
- 明确禁止“按端口猜 PID 杀进程”。

**(f) bxroot → proot 的一次性兼容重试**

`WebOutputSession.Events.exited(exitCode)` 里，只有同时满足 `WebRuntimeFallback.shouldRetry(runtimeName, compatible, hadAuth, namedFailure, isCurrent, exitCode, commandWasNeverExecuted, confirmedTreeExit, ...)` 才 `retryWithProot()`；且重试前必须 `processes.confirmStopped()`。AGENTS.md 的规则：“启动阶段明确引擎失败、无插件故障和用户业务提交，且本轮受管后代树与 launcher 都已确认退出，才允许一次自动 proot 重试…未知执行结果和已开始的写任务不能借此重放。”

### 3.4 WebView 宿主

DSHA 的 WebView 是**独立 Activity**（PiP 基类），不是 Compose 内嵌：

- 入口：`WebPreviewActivity extends PictureInPictureActivity implements WebFullscreenUi.Host`。
- **鉴权交接（关键差异）**：[PreviewAuth.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/PreviewAuth.java) 在后台线程调 `controller.exchangeDshAuthCookie()`（Java 侧完成 303 交换，拿 `dsh-auth-*` cookie），回主线程前校验 `generation` 与 `authUrl` 未变，然后 `WebPreviewActivity.createWebView()`：

```java
if (hasCookie) {
  cookies.setCookie(baseUrl, authCookie + "; Path=/; HttpOnly; SameSite=Strict", ok -> {
      ... continueInitialNavigation();     // 等 setCookie 异步完成再加载，避免首进偶发未认证
  });
} else continueInitialNavigation();
// continueInitialNavigation(): loadUrl(navigation.cookieAccepted() ? baseUrl : authUrl)
```
也就是说：**能用 cookie 就加载干净 URL（`http://127.0.0.1:<port>/`），token URL 只在降级路径才用**；`baseUrl` 来自 `WebPreviewPolicy.loopbackBaseUrl(authUrl)`。

- **401/403 只刷新 cookie，不重启 Node**：
```java
if ((code == 401 || code == 403) && !authRetried) { authRetried = true; authCookie = null; restoreState = null; loadSession(false); return; }
```
- **WebSettings 逐项显式**：`javaScriptEnabled`、`domStorageEnabled`、`allowFileAccess=false`、`allowContentAccess=true`（注释：网页只能获取用户选择后复制到专属 FileProvider 的 URI）、`mixedContentMode=NEVER_ALLOW`、`setSupportMultipleWindows(false)`、`setAcceptThirdPartyCookies(view,false)`；desktop UA 仅当用户显式开“桌面模式”。
- **WebMessageListener 精确 origin**：`DshaLanguage` / `DshaTheme` 用 `addWebMessageListener(view, name, Set.of(baseUrl 去掉尾斜杠), ...)`，并二次校验 `isCurrentPage(source) && mainFrame && sameService(baseUrl, source.getUrl())`。
- **document-start 兼容脚本**：`WebViewCompat.addDocumentStartJavaScript(view, WebPageScripts.compatibility(...), Set.of(origin))`，用于 `AbortSignal.any/timeout`、`Promise.withResolvers`、`Iterator.*`、ES 兼容层；页面完成后再跑一次**能力核验** `CAPABILITY_CHECK`，缺失就报“系统 WebView 需要更新”或（Low 版）切 Gecko。
- **文件选择**：`onShowFileChooser` → `WebUploads.parseChooserResult` → **只接受 `content` scheme**，复制到 `getCacheDir()` 会话目录后经 `FileProvider`（`<pkg>.updates`）交给网页；每次请求有独立 ticket，页面取消/换代后旧结果必须作废。
- **下载**：`setDownloadListener` → blob:/data: 走 `WebBlobDownload`（页面内取字节），http(s) 走 `WebDownloads.start(baseUrl, url, CookieManager.getCookie(url), name, length, null)` —— **带 cookie 下载**，且先校验 `sameService(baseUrl, view.getUrl())`。
- **返回**：`navigateBack()` 先 `evaluateJavascript(WebPageScripts.back(...))`，1200 ms 内无 `true` 结果就回退到 `current.canGoBack()/leavePreview()`；另有 `OnBackPressedCallback`。
- **PiP**：`pictureInPictureContentReady()` 要求 `webView != null && retained.ready && !pageFailed && isCurrentPage && sameService`；`onStop/onStart` 在非 PiP 时 `webView.onPause()/onResume()`；`onRenderProcessGone` 返回 true 防止进程被杀，然后重建或切 Gecko。
- **状态恢复**：`onSaveInstanceState` 保存 `webView.saveState()` + `(generation, instanceId, authUrl)`；恢复时若 identity 与当前代不符则丢弃状态。
- **页面事件回传**：console 里以 `[DSHA_PAGE] ` 前缀的 JSON（`documentId`/`nonce`/`page`/`sequence`/`fatal`）会被 `evaluateJavascript` 二次确认 documentId 后交给 `StartupDiagnostics.pageEvent`，`fatal` 且未 ready → `HarnessController.failedWebPage(...)` → 跳 `StartupRecoveryActivity`。

### 3.5 PTY 终端（真 PTY）

- 依赖：`implementation 'com.termux.termux-app:terminal-view:0.118.0'`（build.gradle 注释：“真 PTY 终端：Termux 拆出的 terminal-view/terminal-emulator（Apache 2.0），自带 JNI PTY 层（openpty+fork/exec）”）。
- JNI 源码在仓库内：`tools/termux-jni/termux.c`（7,218 B）与 `tools/termux-jni/build.ps1`；`jniLibs` 里是 `libtermux.so`；`app/build.gradle` 明确 `keepDebugSymbols += '**/libtermux.so'` 和 `pickFirsts += '**/libtermux.so'`（“使用 app 自带的同版本 16 KB JNI，覆盖 terminal-emulator AAR 中的旧库”）。
- PTY 的执行环境**复用同一套容器运行时**（[TerminalRuntime.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/runtime/TerminalRuntime.java)）：

```java
List<String> argv = spec.runtime.baseArgv(rootfs, spec.hardlinks, spec.dataDomain);
launcher.bindPreloads(argv);
if (guestCommand == null || guestCommand.length == 0) {
  argv.add("/bin/bash"); argv.add("-c");
  argv.add("stty sane 2>/dev/null || stty echo icanon 2>/dev/null || true; exec /bin/bash -l");
} else Collections.addAll(argv, guestCommand);
// 环境用 /system/bin/true 探一次 ProcessBuilder 再把 key=value 打进 String[]，另加 LANG/LC_ALL=C.UTF-8
```
- 出生身份：`PtySession`/`PtyGuestOwner`（`runtime/PtyGuestOwner.java` 在 descriptor 的 launcherInputs 里），AGENTS.md：“PTY 出生身份在原生 fork/exec 握手中登记：子进程自读 stat，父进程确认后才 exec。不得退回 Java 启动后单次读取，或停止时按裸 PID 临时认领。”
- 16 KB 页：AGENTS.md 要求 `max-page-size=16384 / common-page-size=4096`，并“核验 RELRO 在 4 KB 与 16 KB 页映射内”。
- 兜底：`core/SimpleTerminalBackend.java`（“简易终端兜底 … 会话保留在后台”）；多标签、会话编号永久递增、关闭要等该会话真的退出。

**对照 MCA**：仓库内 `*.kt` 对 `pty|node-pty|terminal-emulator|termux` **零命中**（本次检索结果），即 MCA 的 DSH 模式没有任何终端能力。

### 3.6 移动 UI 注入与页面桥

**(a) 官方移动插件 `dsh-web-mobile`**（内置，非改造官方前端）

- 事实：`app/src/main/assets/builtin-plugins/dsh-web-mobile/package.json` → `"version": "3.1.0"`、`"license": "MIT"`、上游 `github.com/mexiaosqwq/dsh-web-mobile`、`dshaUpstream.commit = ab3a85bead1c53b723e8627f5b44e3c306d85604`、`clientSha256 = 4e4be68a…`、`prebuilt: true`。
- 注入方式：**一条 Cordis 行**（`builtin-plugins/dsh-web-mobile/cordis.patch.yml`）：
```yaml
- insert:
    - id: dsh-web-mobile
      name: 'dsh-web-mobile'
```
  注释写明：“The browser half is discovered through the package.json dsh.client declaration; the host half is an empty apply so the row exists in the host Loader.”
- 客户端半边通过 `"dsh": { "client": { "platform": "web", "inject": ["@deepseek-ai/dsh-client-locale", …] } }` 声明依赖的官方 client 模块，由官方 client loader 组装 —— **不改官方前端 dist**。
- 上游锁定：`tools/mobile-plugin-upstream.lock.json`、`tools/apply-mobile-client-patches.mjs`（本地差异管理，descriptor 里两个文件都有 sha256）。

**(b) DSHA 自有页面桥 `app-integration`**（Cordis 插件，host + client 两半）

- `package.json`：`dsh.bundle.patch=./cordis.patch.yml`、`dsh.client.inject=[ui-conversation, ui-layout, ui-sidebar-right, ui-workspace]`，描述“DSHA 后台任务、网页返回和草稿恢复适配”。
- host 半边 [index.js](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/app-integration/index.js) 做两件事：
  1. 注册**受官方鉴权保护**的 HTTP 路由 `GET/HEAD /__dsha/browser-origin-restore`，先 `scoped.connection.authorizeIndex(req,res)`，再返回带 CSP 的极简 HTML；
  2. 每 2 s 原子写 `/root/.dsha-web-activity.json`：`{generation, at, idle, plugins}`（`mode 0o600`，tmp + rename）。
- 这个 activity 文件被原生侧 `HarnessService.knownWebIdle()` 读取：`generation == c.getWebGeneration() && age <= 30_000 && idle` → 才允许省电模式释放 WakeLock。**这是一个干净的“guest 主动上报空闲、原生只读消费”的跨边界契约**，比原生猜端口/猜页面状态可靠。

**(c) 网页文件桥**：AGENTS.md 记录 `window.DSHA.shareFile/saveFile` 接收网页已有的 `File/Blob`，“通过精确同源、顶层帧、当前运行实例的 WebMessageListener 转为受限流式传输，**不接收本机路径**”；分享缓存文件名与显示名一致，`FileProvider` 仅临时授权读。

### 3.7 看门狗 / 停止 / 重启

**(a) 前台服务**（[HarnessService.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/HarnessService.java)）

```java
private static final long KEEPALIVE_INTERVAL_MS = 15000L;
private static final long RESTART_COOLDOWN_MS = 120000L;
private static final int  KEEPALIVE_MAX_FAIL   = 3;
```
- `startForeground` 在 `onCreate` 与每次 `onStartCommand` 无条件先立（注释：“Android 8+ 硬性契约 … 5 秒内 startForeground，否则被强杀”）；返回 `START_STICKY`。
- `onStartCommand` 会**重新检查** “可见 Activity 恰好在用户停止前排队了 START”的情况，晚到的 Intent 不得复活服务。
- 通知正文是真正的 Activity PendingIntent（`open_launch=true`），停止按钮是独立 `PendingIntent.getService`（requestCode 不与正文共用）。
- 省电：`PARTIAL_WAKE_LOCK`（10 分钟续期）+ `WIFI_MODE_FULL_HIGH_PERF`（仅 LAN/任务/非省电时），由 `util/PowerPolicy.keepCpu(eco, active, starting, interactive, lan, work, idle, elapsed)` 统一决策；`releaseLocks()` 幂等。

**(b) 健康探针是分层的**（[WebHealthProbe.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/util/WebHealthProbe.java)）

```java
public enum State { UNCONFIGURED, UNAVAILABLE, UNKNOWN, LISTENING, AUTH_EXPIRED, INVALID_RESPONSE, HTTP_READY, STALE }
public boolean healthy()          { return state == State.HTTP_READY; }
public boolean restartCandidate() { return state == State.UNAVAILABLE; }   // 只有连接被拒才是重启候选
```
方法注释原文：“Current-port listening, authentication and HTTP readiness are separate evidence.” 以及 “A broken page/stream/auth response never authorizes a Node restart.”

**(c) 重启规则**（`HarnessService` 看门狗线程）

- `!c.canAutoRestart()` → 不重启（用户已停、sentinel 存在、恢复页 blocked、正在启动）；
- 探针 `STALE`（探测期间换代码/用户操作）→ `fail = 0`，丢弃本轮结果；
- 只有 `health.restartCandidate()` 才 `fail++`；`fail < 3` 继续等；
- 冷却：`lastRestartAt != 0 && now - lastRestartAt < 120_000` → 跳过；
- 重启入口 `c.restartWebAutomatically(generation, …)` **在 lifecycle 锁内**再校验代次，并 `recordWebFailure(...)`；
- 连续失败 → `state.recovery.blocked()` → 通知“连续失败，自动重启已暂停；点此查看恢复选项” + 进入恢复页。

**(d) 停止：sentinel + pidfile + 出生身份 + 双证明**（[WebProcessManager.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/runtime/WebProcessManager.java)）

1. `sentinel()`：确保 `root/` 归本应用所有（`lstat` + `st_uid` + `st_mode` 补 `0700`，链接/异 UID 直接拒绝），创建 `<root>/.dsha-stopped`；
2. 读 `<root>/.dsha-web.pid`（>32 B、符号链接、非本 UID 一律拒绝）；
3. `inspect(pid)`：`kill(pid,0)` → 读 `/proc/<pid>/stat` 出生身份 + `/proc/<pid>/cmdline` → `WebProcSel.looksLikeWeb`；`ESRCH`=GONE、`EPERM/EACCES`=DENIED/OTHER（用 `/proc/<pid>` 的 `st_uid` 区分“旧 PID 被别的 UID 复用”）；
4. `WebRecordedStop.run(...)`：SIGTERM → 轮询 `mayRetire(...)` → 成功则 `retire()`（pid 改名为 `.dsha-web.pid.stale`，删 identity）；
5. `confirmStopped(trackedProcessAlive)`：**先要求 launcher 已退出**，再 `hasOwnedWeb()` 全 `/proc` 扫描“可证实的同 UID Web”，两者都干净才算停稳；
6. 强制停止（`forceStop`）需要**原生 UI 显式确认** + 证据（pid/出生身份/uid/cmdline/generation 全部匹配），且“只给经身份核验的 PID 发信号，绝不给 launcher 或进程组发信号”；
7. `proot --kill-on-exit` 保证杀 launcher 时 guest 树一并退出。

注释里的两条关键纪律：“旧 Web PID 复用时 … 只隔离旧记录，不给该 PID 发信号”；“不能按裸 PID、端口或名称强杀”。

**(e) 与 MCA 的形态差异**：DSHA 的 pid 来自 guest 内 `echo $$`（因为 proot 多一层），MCA 的 pid 来自 `Process` 对象（反射 `ProcessImpl.pid`）。MCA 的 `DshProcessTree` + `DshStopOutcome.fenceRequired` 已经具备“父进程退出但子进程残留 → 升级 SIGKILL → 仍残留则立 fence”的语义（§4.1 行“停止”）。

### 3.8 数据、备份与受管更新

- 数据在 rootfs 内（`/root/.dsh` 等），宿主侧提供“数据与备份 → 存储指南”显示实际位置；旧 `Documents/dshdata` 继续兼容读。
- 导出：`DSHA-data-v5-UUID.tar.gz`，**无密码**、可完整性校验；内部自动副本仍用本机随机密钥加密；旧加密原件需原密码。
- 自动备份：默认开启，每天/1–168 小时间隔/停止后三种模式；运行中自动延后；保留最近 3 份已验证副本；手动/未知/部分记录不自动删除。
- 范围备份：全量/只对话/只插件/只设置；恢复只覆盖对应内容并先做摘要与预检。
- **凭据不进备份**：桥 token 整文件排除；`.credentials.yaml` 字段级剔除本机密钥、保留用户 API key。
- 受管更新：`ManagedRuntimeTransaction` + `RuntimeTrial`（真隔离启动、验证端口/鉴权/会话读写/插件加载后才提交）+ `retired-proof.json`（旧树 inode、树摘要、数据归档与映射核对后才清理）。
- 配置快照：6 文件检查点、3 份健康 + 3 份修复前、健康快照必须匹配启动前内容与启动 ID。
- API key：`KeyVault`（Keystore AES/GCM），失败不清旧密文。

**MCA 的合同与此冲突**：ARCHITECTURE §10 明确“DSH 是唯一 writer；MCA 不把 JSONL 双写进 Room”；BASELINE §9 把“数据目录/备份”列为 R06 未知项。因此**宿主侧备份引擎必须拒绝**（R8）。

### 3.9 安全边界（含 LAN 代理）

DSHA 自己的 [docs/security-model.md](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/docs/security-model.md) 写得比多数项目诚实：

| 边界 | 实际保证 | 不能推导的保证 |
|---|---|---|
| Android 应用 UID | 系统隔离其他普通应用的私有数据 | **proot 的 root 不是 Android root** |
| Ubuntu 与插件 | 包/路径/摘要/事务核验 | 同 UID 任意代码**不是**独立沙箱 |
| loopback 桥 | 当前 token、路由与能力约束 | 本机 TCP 不等于只有本应用能连 |
| 内嵌页面 | 当前来源、实际端口、页面、运行代次 | 不能批准任意网页、旧回调或摄像头 |
| LAN | 独立启用与鉴权、当前 Web 端口、有界连接 | **LAN HTTP 是明文**；不提供 TLS/受信配对/后台不受限承诺 |
| 用户归档 | 完整认证/摘要、范围与双向记录核验 | 归档自述的健康不成为本机证明 |

README「已知限制」还明写：**bash 的沙箱隔离不可用**（bubblewrap 需要 unprivileged user namespace，Android sepolicy 不给）；默认 `danger-full-access`，可改 `workspace-write` / `read-only`。

**LAN 代理细节**（[LanProxyService.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/LanProxyService.java)）：

- 只监听 `0.0.0.0:3081` → 转发到 `127.0.0.1:<实际 dsh 端口>`；`dsh` 本身永远只 loopback。
- 凭据：`getLanToken()` 生成 **32 字节随机 → base64url（256 bit）**，存私有 SharedPreferences；“revoked stored values are never reused”。
- 首次带 token 命中：`303 See Other` + `Location: /` + `Set-Cookie: <name>=<token>; Path=/; Max-Age=43200; HttpOnly; SameSite=Strict` + `Cache-Control: no-store` + `Referrer-Policy: no-referrer`；token 从 URL 剥离（`LanAuth.stripTokenFromRequestLine` / `stripTokenFromUrl`）。
- 转发前**重写**：`Host`/`Origin` → `127.0.0.1:<backend>`，`Sec-Fetch-Site: same-origin`，**删除** `Referer`/`Cookie`/`Authorization`/任何含 `dsha`+`token` 的头；注入本轮内部 `dsh-auth-*` cookie。
- 回程**删除** `Set-Cookie`/`Set-Cookie2`/`Authorization`/`Proxy-Authorization`/`X-Dsha-Token`/`X-Token`/`dsh-auth-*`，**删除** `Access-Control-Allow-*`（避免把代理当跨域 API），重写 `Location` 去掉后端 authority。
- 代次绑定：`AuthState(cookie, generation, backendPort)` 单引用发布 + `AuthSnapshot` 不透明租约；`writeCurrentRequest` 在锁内二次校验 `isCurrentDshAuth`，不满足就 **fail-closed 返回 null**（注释：“never a signal to forward without cookie”）。
- 后端 401/403 → `invalidateDshAuth(run, snapshot)` 立即失效；`stop(generation)` 只停本代 listener，`clearDshAuth(generation)` 清内存凭据（不落盘）。
- 容量与隧穿：`SocketDispatch("dsha-lan", 32, 8, 16, 8)` 有界并发；101 后才 `tunnel()` 双向 pump；SSE/流式走独立 lane，短请求不占长流线程。

### 3.10 启动诊断

**(a) Node 侧观察器**（[startup-observer.cjs](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/startup-observer.cjs)，通过 `NODE_OPTIONS=--import=...` 预加载）

- 只读：读 `$DSH_HOME/profiles/<profile>/package.json` 的 `dsh.profile.bundles`，按**官方 `dsh-app-boot.resolveBundleDir` 的两个 anchor + Node 解析顺序**定位插件（注释强调“不能只列几个固定目录”）；
- 校验每个 bundle 的 `dsh.bundle.patch` 是包内相对路径、realpath 不越界、文件存在；只从 YAML 里**正则提取** `id|name|module` 标量（不执行 `!!js` 表达式）；
- 用 Cordis 的**真实加载边界**观察：`require(<installation>/node_modules/@deepseek-ai/cordis-plugin-loader/lib/index.js').Entry.prototype.init` 打补丁，“同一模块缓存只加载一次，不拦截每一次 Node 依赖解析”；
- 输出 `[DSHA_STARTUP] {type, plugin, message, …}` 行；`required` 集合 `{agent-loop, webserver, modules, connection, headless-runner, acp, sdk-jsonrpc-server}` 的失败才标 `fatal: true`；
- 观察/翻译自身失败**不允许**被当作插件初始化失败（`emit()` 吞异常）；
- 保护：`DSHA_PRELOAD_PREVIOUS` 让**工具子进程**沿用启动前的 `NODE_OPTIONS`，观察器只用于当前 Web 进程。

**(b) 原生侧聚合**（[StartupDiagnostics.java](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/core/StartupDiagnostics.java)）

- `StartupTrace` 时间线 + `StartupIssueBudget` + 插件归属表 `owners`（plugin name / path / id → owner，上限 3000）；
- 进程输出里的 `[DSHA_STARTUP]` JSON 被解析成 stage/issue/catalog/loading/loaded；非结构化行按 `error|failed|cannot|did not activate|missing` 归因到“RELATED_PLUGIN_LOG”（注释：“关键词只是相关证据，不是已确认的加载失败”）；
- 浏览器侧 `[DSHA_PAGE]` 事件同样进入时间线，`fatal && !browserReady` 才返回 true 触发恢复；
- 历史：`StartupHistoryStore` 保存最近 5 次脱敏记录（中英双语快照）；`persistHistory` 1 秒节流 + 单线程写；
- `preserveFailure(runtimeLog, reason)`：**成功重试不得覆盖上次失败证据**；
- `lastFailure(context)` 读 `last-startup-failure.log` 并 `SensitiveData.redact` + 长度上限 16 KB。

### 3.11 构建与资产可复现

- 离线 rootfs 与大型生成物**不提交**；`BUILD.md` 给出“输入准备 → 证明生成 → 软件验证 → 正式打包”的完整顺序，并要求“生成的证明需与真实字节核对；普通 Gradle 构建使用 check，不能靠旧缓存跳过新输入”。
- 多个 `verify*` 任务挂在 `preBuild`：`verifyBundledIdentities`、`verifyBuiltinRegistry`、`verifyCredentialRegistry`、`verifyBxrootNative`、`verifyBxrootProjection`、`verifyBackupDependencies`（逐 coordinate + JAR sha256 + 许可证文件 sha256）。
- 发布签名：**用历史 debug keystore 签正式包**（`DSHA-ACTUAL-PUBLISH-KEY-debug.keystore`，证书 SHA-256 `E7:E3:A3:…:53:F5`），注释解释“Android 只允许同签名覆盖安装，换钥 = 逼用户卸载重装丢数据”；另有 `verifyPublishCertificate` 任务比对 `ci/release-identity.json`，不符则拒绝打包。
- `jniLibs { useLegacyPackaging = true; keepDebugSymbols += [...] }`：因为“运行时和应急启动器绑定签名 APK 中的完整字节，禁止打包阶段 strip 改写”。
- 双 flavor：`standard`（系统 WebView，minSdk 30）/ `low`（额外 GeckoView 143，minSdk 23），共用功能代码与 Ubuntu Python，**共用 `com.dsh.client`，不能并排安装**。

---

## 4. 与 MCA 当前实现的逐项对照

### 4.1 主对照表

MCA 侧行号来自本次只读阅读，可直接跳转核对。

| 维度 | DSHA | MCA 当前 | 差距 / 风险 | 处置 |
|---|---|---|---|---|
| 宿主机制 | Ubuntu rootfs + bxroot/proot，`libproot.so` 从 `nativeLibraryDir` exec | bionic `libdshnode.so` 从 `nativeLibraryDir` exec；备选 `/system/bin/linker64 <path>`（[DshNodeExecStrategy.kt:43](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeExecStrategy.kt#L43)） | 机制同源（都绕 W^X），载荷完全不同 | 保持；把 DSHA 当旁证（§6） |
| 载荷来源 | 自建 rootfs + 官方 npm 包 + pnpm 安装 | 从第三方 APK 抽出的 `payload.zip`（`woaiys3` v1.20.1） | MCA 依赖第三方二次打包字节，含已替换的 `node-pty`/`sharp` | 记入供应链债；见 §7 |
| 内核版本 | `0.2.0-rc.2` | `0.2.1-rc.2`（`687f282e…`） | 版本线不同，不能互抄补丁 | 升级走 MCA 自己的基线流程 |
| 启动命令 | `exec dsh web --no-open --host 127.0.0.1 --port <n>`（n 可为 0） | `node --expose-internals <bin.js> web --no-open --host 127.0.0.1 --port 0`（[DshNodeCommand.kt:26](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt#L26)） | MCA 需要 `--expose-internals`；DSHA 不需要 | 保持，不互换 |
| DSH_HOME | `/root/.dsh`（guest 内） | `files/dsh/home`（`HOME`/`DSH_HOME`/`DSH_CONFIG_DIR`/`TMPDIR` 全隔离，[DshNodeCommand.kt:48](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt#L48)） | 等价 | 保持 |
| 就绪判定 | 官方启动行打印 token URL 即 READY | 5 步门：303 + HttpOnly + `__DSH_BOOT_READY__` + client 插件 JS + 二次稳定 + 未认证 401（[DshReadinessGate.kt:85](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/DshReadinessGate.kt#L85)） | **MCA 更强** | **拒绝降级** |
| 鉴权交接 | Java 侧换 cookie → `CookieManager.setCookie(HttpOnly;SameSite=Strict)` → 加载干净 URL；token URL 不进 WebView | WebView 直接加载 token URL 一次，靠 303 落地；换代后 cookie 复活（[DshLoadPlanner.kt:60](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshLoadPlanner.kt#L60)）；401 走 `requiresRestart = true` | token URL 会进 WebView 历史/内存；一次 cookie 过期即重建整代 Node | **R1 采纳** |
| WebView 宿主 | 独立 Activity + PiP + Gecko 回退 + `WebMessageListener` 精确 origin + document-start 兼容脚本 | Compose `AndroidView` + 每次换代新 WebView；严格 origin 围栏（[DshWebViewClient.kt:40](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewClient.kt#L40)）；设置更保守（`allowContentAccess=false`，[DshWebViewFactory.kt:45](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewFactory.kt#L45)） | MCA 无 PiP、无 JS 消息桥、无 document-start 注入、无 Gecko 回退 | R5/R4 条件采纳 |
| 文件交互 | SAF 多选 + 复制进 FileProvider + blob 下载 + `window.DSHA.shareFile/saveFile` | SAF 打开/保存 + `data:` 保存 + 下载策略（[DshWebViewHost.kt:108](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewHost.kt#L108)） | 基本对齐；MCA 无网页侧桥 | 暂不采纳 |
| 停止 | sentinel + guest pidfile + 出生身份 + SIGTERM→复核→SIGKILL + 全局同 UID 扫描 | `Process` 对象 + 反射 pid + `DshProcessTree` 树杀 + SIGKILL 升级 + stop fence（[AndroidDshNodeHost.kt:127](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/AndroidDshNodeHost.kt#L127)） | MCA 无 guest 内证明（无 pidfile），但 pid 来源更直接 | 不照搬；见 R2 |
| 退出/重启 | 15 s 探活 + 3 次 + 120 s 冷却 + 只有连接拒绝才重启 + 慢启动不杀 | 退出监视线程 → `PROCESS_EXITED`（retryable）→ 页面 `retry()/restart()` 手动（[DshRuntimeOwner.kt:477](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt#L477)） | 无自动恢复、无冷却、无失败预算 | R3/R4 |
| 慢启动 | 等待鉴权**不设**强杀时限，60 s 仅提示 | `authLineTimeoutMillis = 150 s`，超时即 FAILED（[DshRuntimeConfig.kt:36](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshRuntimeConfig.kt#L36)） | 冷启动/插件多时可能误判 | R3 |
| 端口 | 首选→备用→0；实际端口回写配置；仅 bind 冲突重试一次 | 固定 `port = 0`，OS 分配，origin 从启动行解析 | MCA 更简单且不会冲突 | 保持（无需 R 项） |
| 环境隔离 | 逻辑目录分离（rootfs 内），同 UID | 逻辑目录分离（`files/dsh/**`），同 UID + 独立 `:dsh` 进程 | 等价；都**不是** OS 安全隔离 | 保持诚实表述 |
| 工具子进程 | 真 glibc：git/curl/rg/python/PTY 全可用 | 预打包 bionic ELF + 符号链接（[DshAndroidRuntimeAssembly.kt:110](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt#L110)）；bash shim 直指 `/system/bin/sh` | 无 PTY；bash 无 rc/env 复现 | 记录限制；R6 |
| 移动 UI | 内置 `dsh-web-mobile 3.1.0`（MIT）+ 自有 app-integration 插件 | 无注入；官方前端原样 | 手机窄屏体验未知 | R5 条件采纳 |
| 模型 | 用户 `DEEPSEEK_API_KEY` + 官方 provider | MCA 单一 owner + loopback 网关（[DshGatewayServer.kt:31](../../api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/DshGatewayServer.kt#L31)） + `llm-pi-ai` overlay（[DshGatewayConfigSeeder.kt:153](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt#L153)） | MCA 独有能力 | 保持 |
| 权限默认 | `danger-full-access` | `defaultPreset: workspace-write` + 全部 `approval: ask` | **必须保持差异** | 拒绝 |
| 数据/备份 | 宿主侧 v5 备份引擎 + 自动备份 + 受管更新事务 | DSH 唯一 writer；无宿主备份 | 与 MCA 合同冲突 | 拒绝（R8） |
| 诊断 | `--import` 观察器 + 插件归属 + 5 次历史 + 失败证据保留 | 400 行 ring + 脱敏 + `tail(20)` 进失败诊断（[AndroidDshNodeHost.kt:82](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/AndroidDshNodeHost.kt#L82)） | 无阶段时间线、无插件归属、无历史 | R4 |
| 包体 | 273 / 345 MiB | payload.zip 160 MB + jniLibs 62.5 MB（同内容双份存放） | APK 体积偏大且可能重复 | 见 §4.1 风险注 |
| targetSdk | 37 | 36（[libs.versions.toml:7](../../gradle/libs.versions.toml#L7)） | 都高于 29，机制同类 | 保持 |

> **包体风险注（MCA 侧）**：`payload.zip`（160,464,248 B）里已经包含 `runtime/bin/node`，而同一份 Node 又以 `jniLibs/arm64-v8a/libdshnode.so`（49,715,528 B）单独入包（[MANIFEST.json:9](../../core/dsh-runtime/src/main/assets/dsh/MANIFEST.json#L9) 声明的 `jniLib` 与 [payload-lock.json:14](../../scripts/dsh/payload-lock.json#L14) 声明的 `jniLibsPath` 指向同一字节）。另外，`app/build.gradle.kts` 中**没有** `androidResources.noCompress` 声明（本次 grep 结果），所以 160 MB 的 zip 资产是否会被 aapt2 再压缩一次也未知。两件事（重复存放 / 二次压缩）都**未核对**，列入 U5；DSHA 的 `noCompress['bin','tar','gz']` + split-runtime 分包是可直接借鉴的对照做法，但它解决的是“压缩与更新粒度”，不自动等于“去重”。

### 4.2 四个关键差异的展开

**(1) 执行位置：为什么 MCA 的做法更“窄”，DSHA 的更“宽”**

- DSHA 把“要 exec 的 ELF”与“要被 dlopen 的 .so”分开：只有前者进 `nativeLibraryDir`，后者留在 `files/linux/lib`（`LD_LIBRARY_PATH` + `PROOT_LOADER`）。所以它只需要把一个 ~200 KB 的 proot 入口放进 native 目录。
- MCA 把 Node（49.7 MB）+ rg + curl + git + git-remote-* + python（共 62.5 MB）**全部**作为 native library 入包，再从 payload 里的 `bin/*` 建符号链接指回 `nativeLibraryDir`（[DshAndroidRuntimeAssembly.kt:166](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt#L166) 的 `TOOL_LINKS`）。这是**更强的约束满足**：所有可执行工具都必须是 `lib*.so` 且真的能作为 ELF 运行。
- 后果：MCA 的 `libdshpython.so` 只有 **4,448 B**（[payload-lock.json:54](../../scripts/dsh/payload-lock.json#L54)），与 49 MB 的 Node 完全不是一个量级；`DshNodeCommand` 的注释说明 `python/bin/python3.14` 是这个 native library 的符号链接、因此需要 `PYTHONHOME`（[DshNodeCommand.kt:90](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeCommand.kt#L90)）。它到底是“极小的启动入口/包装”还是别的实现，本报告**未核对 ELF 内容**。DSHA 不需要这类技巧，因为它有真 glibc rootfs。**这是 bionic 路线的固有复杂度，不是 MCA 的实现缺陷。**

**(2) 就绪语义：MCA 更强，且必须保持**

- DSHA：`authentication(url)` 命中即 `finishStart`，页面可以立刻进入。
- MCA：`DshReadinessGate` 还要 `index.body.contains("__DSH_BOOT_READY__")`、bootstrap client 插件资产 ≥ 512 B 且 MIME 是 JS、二次稳定、未认证必须 401；任一不满足 → `LOADER_NOT_READY`。
- 有一条**已知的 MCA 缺陷**正好说明这个门的价值：交接文档 §4.3 记录 `LoopbackHttpProbe` 曾对 401 读 `inputStream` 导致 `-1`，整个 gate 永远不通过。如果当初只判“端口开放”或“打印了 URL”，这个 bug 就会被静默掩盖成“已就绪”。**DSHA 的就绪定义如果搬到 MCA，会失去这层保护。**

**(3) 鉴权交接：DSHA 的做法更安全，且能顺带修掉 MCA 的“401 就重启 Node”**

- DSHA：cookie 在 Java 侧换好，`setCookie` 异步回调成功后才 `loadUrl(baseUrl)`；401/403 只重试一次 cookie 刷新。
- MCA：`DshLoadPlanner` 优先 `TokenEntry`（token URL），否则 `CookieResume`；`DshWebViewClient.onReceivedHttpError(401/403)` → `onHttpUnauthorized()` → `DshWorkspaceViewModel` 以 `requiresRestart = true` 失败（[DshWorkspaceViewModel.kt:419](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceViewModel.kt#L419)、[DshWebViewClient.kt:99](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewClient.kt#L99)），即一次 cookie 过期就要重建整代 Node（官方 loader + 插件重新加载，耗时未测）。
- 采纳 DSHA 的做法可以让 token URL 完全不进 WebView（连一次都不进），从而消除“历史/内存/截屏/崩溃报告里出现 token”的面；并把“401 → 重启整代 Node”降级为“401 → 刷一次 cookie”。

**(4) PTY：唯一能论证 proot 兜底的硬需求**

- DSH 的终端工具、`vim`/`htop`/`tmux`、需要 `openpty` 的插件，在 MCA 的 bionic 载荷里**没有实现路径**：`node-pty` 在参考 payload 中已被替换为 `child_process`（BASELINE §4.2 记录，Lead 确认），MCA 侧也没有任何 Termux/JNI PTY 代码。
- DSHA 用 Termux JNI + proot + `/bin/bash -l` 拿到真 PTY，代价是 273 MiB 包体与整套 rootfs。
- 结论：**V1 不引入 proot**；把“真 PTY / 原生 addon”作为 V2 的需求门，先做最小探针（§8），再决定是否接受包体与 GPL 义务（R6）。

---

## 5. 建议：采纳 / 条件采纳 / 拒绝（按“价值 ÷ 风险”排序）

每条给出：落点、最小实现、验证、回退。**所有条目在本轮均为“建议”，未实施。**

### R1（最高价值 / 最低风险）WebView 侧确定性 cookie 注入，token URL 不加载

- **抄什么**：`PreviewAuth` + `continueInitialNavigation()` 的模式 —— 原生侧在后台完成 303 交换拿到 `dsh-auth-*`，`CookieManager.setCookie(origin, cookie + "; Path=/; HttpOnly; SameSite=Strict")`，**回调成功后再** `loadUrl(origin + "/")`；只有 cookie 交换失败才退回加载 token URL。
- **MCA 落点**：`feature/dsh-workspace/.../web/DshWebViewHost.kt:189`（现在的 `view.loadUrl(state.loadUrl)`）与 `DshLoadPlanner.kt:73`（`TokenEntry` 分支）；需要一个跨进程的“按代次换 cookie”能力 —— `DshRuntimeOwner` 已有 `webEntry()`，但**没有**暴露“用 token 换 cookie 并返回 cookie”的 IPC。最小实现是在 `core/dsh-runtime` 复用 `LoopbackHttpProbe`（它已经会做 303 + `Set-Cookie` 解析，见 [DshReadinessGate.kt:97](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/readiness/DshReadinessGate.kt#L97) 的 `DshSetCookie.firstCookie`），新增一个 `MSG_EXCHANGE_COOKIE`（请求携带 epoch，服务端校验 `webEntry()` 的代次），返回 `name=value`。
- **注意**：`DshReadinessGate` 的 `Outcome.Ready` 其实**已经**把 `cookie` 算出来了，但没有穿过 IPC 边界；这是最小改动点。
- **验证**：单元测试（假 HTTP fixture 返回 303 + Set-Cookie）＋ 真机检查 WebView 历史里不出现 `token=`、`about:blank` 后 `canGoBack` 行为不变、冷启动与热恢复各一次。
- **回退**：保留现有 `TokenEntry` 路径作为降级分支（cookie 交换失败即用），一行开关可切回。

### R2（高价值 / 低风险）把“失败码 → 是否自动重试”写成显式策略表

- **抄什么**：`WebHealthProbe.restartCandidate()` 的单一判据思想 —— “只有连接被拒才是重启候选”，页面/流/鉴权异常一律不授权重启。
- **MCA 落点**：`DshRuntimeOwner.fail(...)` 的 `retryable` 现在由调用点各自传布尔（[DshRuntimeOwner.kt:449](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt#L449)），`LOADER_NOT_READY` 还特判成 `!retryable`（[DshRuntimeOwner.kt:380](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt#L380)）。建议抽成纯函数 `DshRestartPolicy.shouldAutoRetry(code, phase, consecutiveFailures, nowMillis, lastAttemptMillis)`，映射：`PROCESS_EXITED`/`NODE_START_FAILED` = 可自动；`AUTH_ENTRY_MISSING`/`LOADER_NOT_READY`/`PAYLOAD_*`/`ABI_UNSUPPORTED`/`STOP_TIMEOUT` = 不可自动（需用户动作或换包）；`GATEWAY_UNAVAILABLE` = 可自动但受冷却。
- **验证**：纯 JVM 单测覆盖每个 code + 冷却边界；不需要设备。
- **回退**：纯函数无副作用，删掉即回到现状。

### R3（高价值 / 中风险）放宽“等鉴权”的判定，区分“慢”与“坏”

- **抄什么**：DSHA “等待鉴权不设强杀时限，60 s 仅提示”；以及“失败预算 + 冷却”（3 次 / 120 s）。
- **MCA 落点**：`DshRuntimeConfig.authLineTimeoutMillis = 150_000` 与 `readinessTimeoutMillis = 60_000`（[DshRuntimeConfig.kt:36](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshRuntimeConfig.kt#L36)）。建议改成**进度感知**：只要子进程还活着**且** ring buffer 在最近 N 秒内仍有新行，就把 deadline 顺延（有上限，例如 10 分钟），并在 60 s 时向 UI 发一条“仍在启动”的提示（不改变 phase）；只有“进程活着但完全无新输出超过 X 秒”或“进程退出”才 fail。
- **验证**：单测（假 host 持续吐行 vs 停止吐行）+ 真机首次冷启动计时。
- **回退**：保留固定超时值作为配置开关默认值。

### R4（中价值 / 低风险）采纳诊断的“契约”，不采纳 `--import` 观察器

- **抄什么**：①阶段时间线（`stage`/`issue`/`owner`）；②失败证据**不被成功重试覆盖**；③最近 N 次脱敏历史；④`fatal` 只由白名单入口决定。
- **不抄什么**：`startup-observer.cjs` 依赖 Cordis 内部 `Entry.prototype.init`、`fiber.state`、`.store`、`resolveBundleDir` 的两个 anchor —— 这些在官方内核小版本间会变，MCA 固定 `0.2.1-rc.2` 也不该把诊断绑死在内部结构上。**替代方案**：让 DSH 侧通过官方扩展点输出结构化阶段（若官方无 seam，则只解析 stderr 的稳定行），原生侧做归属映射。
- **MCA 落点**：`core/dsh-runtime/.../log/DshOutputRing.kt` + `DshRuntimeOwner.startInternal` 的阶段打点（现在只有 `deps.log(...)` 自由文本）；新增 `DshStartupTrace`（纯 Kotlin，可测）与 `files/dsh/last-failure.log` 写入（注意：**不能**把 token/URL 写进去，复用现有 `DshLogRedactor`）。
- **验证**：单测（脱敏、截断、保留）＋ 真机注入一次失败并确认上次失败仍在。
- **回退**：新增文件独立，不接线即无行为。

### R5（中价值 / 中风险）移动 UI：先评估 `dsh-web-mobile`，不要自己写样式

- **抄什么**：用**官方 Cordis client 注入**承载移动适配（一条 patch 行 + `package.json` 的 `dsh.client.inject`），而不是改官方 dist 或往 WebView 里塞 CSS。
- **MCA 前置条件**（缺一不可）：
  1. 先把官方前端在手机上跑通并截图（当前状态见交接文档 §8.1：真机前端验收 `NOT_RUN`）；
  2. 固定上游 commit + `clientSha256`（DSHA 的做法是 `dshaUpstream` 元数据 + `tools/*.lock.json`）；
  3. 遵守硬锁 §9 冻结合同：移动适配是独立视觉变更，必须单独截图验收（中文/英文、浅/深、窄屏、1.3 倍字体）；
  4. 许可证：MIT（`package.json` 已声明），但要**逐文件**核对上游 LICENSE 与本地差异补丁的署名。
- **风险**：上游 3.1.0 与官方 client 版本强耦合（peerDependencies 里写死了多个 `@deepseek-ai/dsh-client-*` 范围），MCA 内核版本一旦升级就可能整体失效。
- **回退**：作为可选插件行，删行即回到官方原样。

### R6（高价值 / 高风险，V2 门槛）proot/bxroot 只作为“真 PTY / 原生 addon”的兜底候选

- **触发条件**：产品明确要求“DSH 终端工具可用/第三方插件可编译 native addon”，且 R1–R5 无法满足。
- **必须接受的代价**（来自 DSHA 的实测事实，不是猜测）：
  - 包体 +273 MiB（Standard）/+345 MiB（Low）；
  - 冷安装 42.4 s（其自身未达 30 s 目标），需要事务与回切；
  - `link(2)` 被 SELinux 拦 → `--link2symlink` + `.l2s` 双绑定 + `PROOT_L2S_DIR`，且注释明说“会破坏 dsh write 工具”；
  - `/linkerconfig` 不可 bind；
  - `proot --kill-on-exit` + 出生身份 + 全局同 UID 扫描才敢说“停稳”；
  - bxroot 需要独立 ULX 闭包 + 只读资格探针 + 一次性 fallback；
  - 16 KB 页对齐（`max-page-size=16384`）；
  - **GPL-2.0 义务**（proot）与 bxroot 的独立许可证。
- **最小探针**（不写生产代码）：在 MCA 包内放一个**只读** `libproot.so` + 最小 rootfs（仅 busybox/bash），按 DSHA 的 argv/env 形状跑 `echo SMOKE_OK` + `openpty` 测试；同时验证 targetSdk 36 下 exec、`.l2s` 行为、停止后无残留进程。**在探针通过前，禁止在方案/发布文档里写“将采用 proot”。**
- **回退**：探针代码不进入生产路径；bionic 主路径不受影响。

### R7（拒绝，V1）LAN 代理

- 方案 §8 明确“不开放到任意网卡”；MCA 的 DSH 网关也只监听 loopback。
- 若未来真需要 LAN：**照抄的是它的安全形状**（token → 303 + `SameSite=Strict` HttpOnly cookie、`fail-closed`、代次绑定快照、剥除 `Set-Cookie`/`Authorization`/`X-*Token`/CORS 头、重写 `Host`/`Origin`/`Sec-Fetch-Site`），而不是“URL 里带 token”的弱模式。
- **不采纳的部分**：明文 HTTP（其自身文档承认）、`0.0.0.0` 绑定、12 小时 cookie 寿命。

### R8（拒绝）宿主侧数据/备份引擎

- 与方案 §10（DSH 是唯一 writer）和 BASELINE §9（R06 未知项）冲突。
- **只借两条原则**：①凭据不进任何导出；②任何跨代引用必须绑定 `runtimeEpoch + profile + session` 稳定身份，不绑定端口/URL。

### R9（拒绝，V1）冷安装事务 / 受管运行时试运行

- 在 bionic 单 payload 方案下，`DshPayloadInstaller` 的“hash 校验 + staging + rename 原子替换 + 保留旧树”已经覆盖同等风险（[DshPayloadInstaller.kt:85](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadInstaller.kt#L85)）。
- 仅当 R6 落地（引入 rootfs）时才需要 `RuntimeTrial` 那一层。

### R10（拒绝，V1；V2 再议）后台看门狗 + WakeLock/WifiLock

- MCA 目前的语义是“页面按需启动、页面驱动重试”，`DshRuntimeService` 已经用 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 在前台（[DshRuntimeService.kt:239](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeService.kt#L239)），但**没有**自动拉起。
- 自动拉起会引入“谁授权重启 / 用户停止意图如何不被撤销 / 与聊天和生图抢资源”的一整套问题；DSHA 用 `canAutoRestart()` + sentinel + recovery.blocked + 冷却来解决，MCA 若要做，必须同样成套引入，不能只加一个 `while(true) restart()`。
- **若采纳**：只抄 R2 的策略表 + 120 s 冷却 + “慢启动不触发看门狗”三条，且默认关闭（用户可见开关 + 通知常驻）。

---

## 6. 明确非声明（Non-claims）

1. 本报告**没有运行过 DSHA**：没装 APK、没在本机或设备上启动它的 rootfs/Node/WebView。
2. 本报告**没有逐文件核对** DSHA 全仓；只读了附录 B 列出的文件。其 README 中的设备验收、测试数量、APK 大小/SHA 我**没有独立验证**（例如 README 的 `c11abc41…`/288,099,627 B 是 build173 记录，属其自述）。
3. 本报告**没有**核对 DSHA 的 `v0.2.0-rc2` 发布 APK 字节与签名（`E7:E3:A3:…`）。README 与其 AGENTS.md 的签名描述属自述。
4. **`libproot.so` 可 exec ≠ `libdshnode.so` 可 exec。** DSHA 的 nativeLibraryDir exec 事实只覆盖它自己的小体积入口 ELF（以及 `libbxroot-*.so` 闭包）；MCA 的 49 MB Node ELF 有完全不同的 DT_NEEDED/loader 需求。MCA 的 exec 可行性仍**只**由 MCA 自己的 R01 探针支撑，本轮状态不变（`NOT_RUN` / 既有证据范围以内）。
5. **DSHA 在 targetSdk 37 上跑通 ≠ MCA 在 targetSdk 36 上等价**。两者包名、签名、manifest、ABI 过滤、首启流程都不同。
6. 本报告**没有**对 MCA 做任何构建、测试或设备验证；`core/dsh-runtime`、`api/dsh-gateway`、`feature/dsh-workspace` 的通过状态在本报告写作时仍是交接文档记录的 `NOT_RUN`（16 项新测试仅为源码声明数）。
7. 本报告**没有**证明官方前端在 MCA 中可见；R1/R4 的价值前提是“前端能跑起来”，该前提本身仍待真机证据。
8. 本报告**不是**许可证法律意见；§7 只是把可核对的字段摘出来，义务判断需法务/维护者复核。
9. 本报告**没有**核对 MCA `payload.zip` 与 `libdshnode.so` 是否在 APK 内被去重（§4.1 风险注），也没有核对最终 APK 的实际体积。
10. 本报告**没有**评估性能（DSHA 的 proot/bxroot 性能其自身文档也明确“本轮未进行 Android 性能验收”）。

---

## 7. 许可证与再分发

### 7.1 DSHA 侧

| 组件 | 许可证（可核对来源） | 核对状态 |
|---|---|---|
| DSHA 仓库本体 | MIT（`LICENSE`，README badge） | 已读 README/LICENSE 声明，**未逐字读 LICENSE 全文** |
| `@deepseek-ai/dsh` 内核 | MIT | 上游声明；DSHA README 致谢 |
| `dsh-web-mobile` | **MIT**（`package.json: "license": "MIT"`） | 已读 |
| Termux `terminal-view` / `terminal-emulator` | **Apache-2.0**（build.gradle 注释与 README 致谢） | 已读注释；**未读 AAR 内 LICENSE 全文** |
| proot（`termux/proot`） | 上游 GPL-2.0；仓库内 `app/src/low/assets/licenses/proot-COPYING.txt` 19,106 B | 文件存在已确认（文件清单）；**未逐字核对** |
| bxroot（`qiannianhuanxiang/bxroot`） | 独立项目，DSHA 以 `tools/bxroot/lock.json` 锁定 | **未核对许可证** |
| GeckoView（Low 版） | MPL-2.0（Maven 坐标 `org.mozilla.geckoview:geckoview-arm64-v8a`） | **未核对** |
| Shizuku | Apache-2.0（BASELINE 已记录；DSHA `standardImplementation dev.rikka.shizuku:*`） | 已读依赖声明 |
| 第三方汇总 | `THIRD_PARTY_NOTICES.md`（7,458 B，存在） | 文件存在已确认；**内容未读** |

**结论**：“DSHA 是 MIT 仓库”只在“仓库自有代码”这一层成立。proot 是 GPL-2.0，任何把 proot 二进制与其启动脚本一起再分发的方案都要单独评估义务；bxroot / GeckoView 的许可证需要逐项核对后才能进 MCA 的 `THIRD_PARTY_NOTICES`。

### 7.2 MCA 侧（更重要，因为这是我们要签字的部分）

MCA 现有 `payload.zip` 的 `MANIFEST.json` 已经声明了一串许可证，其中**包含 copyleft**：

| 组件 | 声明许可证 | 备注 |
|---|---|---|
| `@deepseek-ai/dsh` | MIT | `officialCommit 687f282e…` |
| Node.js v26（Termux bionic build） | MIT | 来源 `nodejs.org` |
| `woaiys3/deepseek-harness-android-app` payload assembly | MIT | **第三方二次打包**，其自身还含 LGPL-3.0（third-party 代码）与 Apache-2.0（Shizuku SDK）片段（BASELINE §4.3 记录） |
| ICU | Unicode-3.0 | |
| OpenSSL 3 | Apache-2.0 | |
| **readline** | **GPL-3.0**（原文标注 “review: redistribution obligations”） | 已在清单里显式标黄 |
| **git** | **GPL-2.0**（同样标注 review） | 同上 |
| Python 3.14 | PSF-2.0 | |
| ripgrep / curl / PCRE2 / libssh2 / zstd / bzip2 / ncurses / expat / libffi / nghttp2 / ngtcp2 / c-ares / SQLite / zlib | MIT / BSD-3 / Zlib / Public-Domain / X11 / bzip2-1.0.6 / curl | |

**两条必须写进交付的风险**：

1. **MCA 的 DSH payload 不是自己构建的**：它是从 `woaiys3/...` v1.20.1 的 APK 里抽出来的（`payload-lock.json` 记录 `apkUrl`/`apkSize`/`apkSha256`）。这带来三层义务：上游仓库许可、上游对 `node-pty`/`sharp` 的替换说明、以及上游自己捆的 LGPL/Apache 片段。**“仓库整体 MIT”同样不能覆盖它。**
2. **copyleft 已经存在**：`git`(GPL-2.0) 与 `readline`(GPL-3.0) 已经在随包分发。任何“为了加 PTY 而引入 proot(GPL-2.0)”的决定，都应在这个既存事实下评估，而不是当作全新问题；但**不能**因此认为“反正已经有 GPL，再多一个也无所谓” —— 义务是按组件逐个履行的。

---

## 8. 未验证项与最小探针建议

| 编号 | 未验证项 | 为什么重要 | 最小探针（成本） |
|---|---|---|---|
| U1 | DSHA 的 rootfs/proot 在**我们**的设备与 targetSdk 36 上的行为 | R6 的全部前提 | 不装 DSHA；只做 R6 的 `libproot.so + busybox rootfs` 只读烟测（1 天量级） |
| U2 | MCA 的 `libdshnode.so` 在真机上 exec + 官方 loader 完整启动 | 当前 `NOT_RUN` 的核心 | 已有 R01 探针结论需与最终 APK 绑定复核 |
| U3 | 官方前端在 MCA WebView 里能渲染（含 client 插件资源） | R1/R4 的价值前提 | 交接文档 §7 阶段 D/E 的真机入口验收 |
| U4 | MCA 的 token-URL 加载路径是否真的会让 token 进 WebView 历史 | R1 收益量化 | 真机上进入 DSH → 返回 → 查 `webView.copyBackForwardList()` 与日志（不回传 token 明文） |
| U5 | `payload.zip` 与 jniLibs 是否重复占体积、是否被 aapt2 二次压缩 | 包体决策 | 对最终 APK 做 `unzip -l` 与逐条目压缩比对 |
| U6 | `dsh-web-mobile 3.1.0` 与 MCA 的 `0.2.1-rc.2` 内核是否兼容 | R5 门槛 | 桌面/模拟器上用同一内核加载该插件并截图；先不打包 |
| U7 | 401 之后 MCA 是否真的重启整棵 Node | R1/R2 收益 | 单测 + 真机日志（看 epoch 是否变化） |

---

## 附录 A：证据 URL 索引（按主题）

**仓库与版本**
- 仓库主页：https://github.com/DSH-APP/DSHA
- 提交历史（atom）：https://github.com/DSH-APP/DSHA/commits/main.atom
- README：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/README.md
- AGENTS.md（项目结构、启动契约、踩坑）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/AGENTS.md
- BUILD.md：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/BUILD.md
- 构建脚本：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/build.gradle
- 资产/启动器身份：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/runtime-descriptor.json

**运行时**
- ContainerRuntime（bxroot/proot/BINDS/env）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.java
- RuntimeLauncher（统一 argv/env）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java
- RuntimeExecution（编排/探针/租约）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/runtime/RuntimeExecution.java
- ProotBootstrap（门面）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java
- TerminalRuntime（PTY 环境）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/runtime/TerminalRuntime.java
- WebProcessManager（停止/身份/屏障）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/runtime/WebProcessManager.java

**启动/鉴权/就绪**
- WebLaunchCommand（精确命令串）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/util/WebLaunchCommand.java
- DshAuthUrl（严格解析/脱敏/cookie 提取）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/util/DshAuthUrl.java
- WebLifecycleController（启动/端口/慢启动/重试）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java
- WebHealthProbe（分层健康）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/util/WebHealthProbe.java
- HarnessService（前台/看门狗/保活）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/HarnessService.java

**WebView / 页面**
- WebPreviewActivity（WebView 宿主全貌）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java
- PreviewAuth（异步 cookie 交换）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/PreviewAuth.java
- LanAuthBridge（代次绑定发布）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/core/LanAuthBridge.java

**插件 / 移动 UI / 诊断**
- dsh-web-mobile 包声明（3.1.0 / MIT / 上游 commit）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/package.json
- dsh-web-mobile 注入行：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/cordis.patch.yml
- app-integration 包声明：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/app-integration/package.json
- app-integration host 半边：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/app-integration/index.js
- startup-observer.cjs：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/startup-observer.cjs
- StartupDiagnostics：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/core/StartupDiagnostics.java
- GeckoView content-script 清单：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/web-integration/manifest.json

**安全**
- docs/security-model.md：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/docs/security-model.md
- LanProxyService（LAN 鉴权/转发）：https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/LanProxyService.java

**外部参考**
- 官方 DSH：https://github.com/deepseek-ai/deepseek-harness（MCA 固定 `687f282e…`）
- 参考 Android 包：https://github.com/woaiys3/deepseek-harness-android-app（v1.20.1）
- 移动 UI 上游：https://github.com/mexiaosqwq/dsh-web-mobile（commit `ab3a85be…`）
- bxroot：https://github.com/qiannianhuanxiang/bxroot
- proot：https://github.com/termux/proot
- Termux：https://github.com/termux/termux-app

## 附录 B：本轮 DSHA 文件读取清单

读取方式：`raw.githubusercontent.com`（HTTP 200，全部为真实正文）。文件清单盘点用 `data.jsdelivr.com`（仅确认存在与体积）。

| 文件 | 用途 |
|---|---|
| `README.md` | 产品面、包体、已知限制、默认权限档、LAN 说明 |
| `AGENTS.md` | 分层职责、启动契约、踩坑（pidfile/sentinel/link2symlink/PROOT_L2S/nativeLibraryDir） |
| `BUILD.md` | 工具链、flavor、资产生成、验证与签名 |
| `app/build.gradle` | targetSdk 37、arm64、jniLibs 打包、noCompress、签名、依赖（Termux/Gecko/Shizuku） |
| `app/src/main/assets/runtime-descriptor.json` | 运行时身份、launcher 输入 sha256、native 库 sha256 |
| `app/src/main/assets/startup-observer.cjs` | Node 侧启动观察器全貌 |
| `app/src/main/assets/web-integration/manifest.json` | GeckoView content-script 注入清单 |
| `app/src/main/assets/app-integration/package.json`、`index.js` | 自有插件声明与 host 半边（activity 心跳、受鉴权路由） |
| `app/src/main/assets/builtin-plugins/dsh-web-mobile/package.json`、`cordis.patch.yml` | 移动 UI 版本/许可/上游锁定/注入方式 |
| `app/src/main/java/.../HarnessService.java` | 前台服务、看门狗、保活、通知 |
| `app/src/main/java/.../core/WebLifecycleController.java` | 启动/鉴权/端口/慢启动/重试/停止编排 |
| `app/src/main/java/.../core/StartupDiagnostics.java` | 启动诊断聚合 |
| `app/src/main/java/.../core/LanAuthBridge.java` | 代次绑定的凭据发布 |
| `app/src/main/java/.../runtime/ContainerRuntime.java` | bxroot/proot、BINDS、env |
| `app/src/main/java/.../runtime/RuntimeExecution.java` | 运行时选择、探针、执行租约 |
| `app/src/main/java/.../runtime/RuntimeLauncher.java` | 统一 argv/env 构造 |
| `app/src/main/java/.../runtime/ProotBootstrap.java` | 运行门面（协作对象清单） |
| `app/src/main/java/.../runtime/WebProcessManager.java` | 停止：sentinel/pid/出生身份/屏障 |
| `app/src/main/java/.../runtime/TerminalRuntime.java` | PTY 的 argv/env |
| `app/src/main/java/.../util/WebLaunchCommand.java` | 精确启动命令串 |
| `app/src/main/java/.../util/DshAuthUrl.java` | 鉴权 URL 严格解析与脱敏 |
| `app/src/main/java/.../util/WebHealthProbe.java` | 分层健康与重启候选 |
| `app/src/main/java/.../ui/WebPreviewActivity.java` | WebView 宿主全貌 |
| `app/src/main/java/.../ui/PreviewAuth.java` | 异步 cookie 交换 |
| `app/src/main/java/.../LanProxyService.java` | LAN 代理与 token 鉴权 |
| `docs/security-model.md` | 安全边界自述 |

未读取但被引用（仅通过 AGENTS.md/README/构建脚本间接引用，**内容未核对**）：`BxrootRuntime.java`、`RuntimeTools.java`、`WebOutputSession.java`、`WebProcSel.java`、`WebStopCoordinator.java`、`PtySession.java`、`RecoveryController.java`、`THIRD_PARTY_NOTICES.md`、`docs/releases/*`。

---

## 交付说明（给接手者）

- 本文只写 `docs/repair/`，未修改任何源码、未改构建、未提交。
- 本文所有 DSHA 结论都可在附录 A 的 URL 上复现；所有 MCA 结论都可用表内行号复核。
- 本文**不构成**实施授权：R1–R10 是建议，落地需按硬锁 §13 切片、按 §15 记录例外、按 §12 选择验证层级。
- 若 R1 被采纳，最小切片是“`core/dsh-runtime` 新增 cookie exchange IPC + `DshWebViewHost` 优先加载干净 URL”，回退点是保留现有 `TokenEntry` 分支。

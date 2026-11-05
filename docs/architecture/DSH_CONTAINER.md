# DSH 免 root 容器（proot）落地说明

版本：2.0 · 状态：**内置离线资产 + 六步安装流水线**（v0.3.9 起）

本文说明 `feature:dsh-workspace` 的容器运行时契约（`container/DshContainerContract.kt`、
`DshContainerInstaller.kt`、`DshContainerTarGz.kt`、`DshContainerHost.kt`）与控制台区块
（`ui/DshContainerSection.kt`）做了什么、没做什么。它是工程合同的一部分，不替代
[MCA_PROJECT_HARD_LOCK.md](MCA_PROJECT_HARD_LOCK.md)。

## 1. 结论：1.0 的"不内置"已撤销

1.0 版把 proot 与 rootfs 留在"用户自备"，理由是这属于**资产与许可证决策**，必须单独评审。
该决策已完成，结论如下：

| 资产 | 来源 | 版本 | 许可证 | 进 Git？ |
| --- | --- | --- | --- | --- |
| `proot` | `packages.termux.dev`（termux-main/aarch64） | 5.1.107.102 | GPL-2.0-or-later | 否 |
| `libtalloc.so` | 同上（proot 的依赖） | 2.5.0 | LGPL-3.0-or-later | 否 |
| `libandroid-shmem.so` | 同上（proot 的依赖） | 0.7 | Apache-2.0 | 否 |
| Ubuntu base rootfs | `cdimage.ubuntu.com/ubuntu-base/releases/24.04` | 24.04.5 LTS (noble) arm64 | Ubuntu 基础镜像；各组件保留其在镜像内 `/usr/share/doc` 的许可证 | 否 |

- 二进制与镜像**永不进 Git**：`.gitignore` 排除 `/core/dsh-runtime/src/main/jniLibs/` 与
  `/app/src/main/assets/offline/`。由 `scripts/dsh/stage-container.ps1` 在构建前摆放，
  脚本内置上表每个 URL 的 SHA-256，下载字节哈希不符即硬失败。
- **随发布附带**：APK 增量约 30 MB（rootfs 归档 29,936,675 字节 + 三个二进制约 308 KB），
  解包后占用应用私有目录约 80 MB。1.0 文档写的"~200 MB 包体"是未实测的估计，实际小得多。
- **GPL-2.0 义务**：proot 以独立可执行文件形式随包分发。发布记录必须带 `container-manifest.json`
  里的 `sourceUrl`，以便提供对应源码；不得声明 MCA 对 proot 拥有著作权。
- 安全边界不变：容器只是**文件系统隔离**，不是安全沙箱；`-0` 只在 proot 的 user namespace 内
  伪装 root，真实 uid 仍是应用 uid，不构成提权。

## 2. 资产如何变成可执行

- `execve()` 成败由 SELinux 决定。targetSdk 36 的 `untrusted_app` 域不允许执行应用可写目录里的
  ELF（`app_data_file` 的 `exec_no_trans` 被拒绝），所以 proot 只能放在 **APK
  `nativeLibraryDir`**（本项目 `android:extractNativeLibs="true"` + `useLegacyPackaging = true`）。
- 因此 proot 以 `libproot.so` 的名字随包，由 PackageManager 解包到 nativeLibraryDir；
  `libtalloc.so` / `libandroid-shmem.so` 用**原始 SONAME**随包，动态加载器按名字即可找到。
- 这三个文件在 `app/build.gradle.kts` 的 `packaging.jniLibs.keepDebugSymbols` 中**无条件**保留：
  对一个可执行文件做 strip 没有意义，且可能破坏其入口。
- rootfs 是**数据**：解包到 `files/dsh/container/rootfs`，由 proot 以 `-r` 读取。

### 2.1 已实测的平台墙（v0.3.9 真机结论）

真机上 proot 本身能跑（`libproot.so --version` 输出 `5.1.107.102`），但**guest 程序起不来**：

```
proot error: execve("/usr/bin/sh"): Permission denied
proot warning: can't canonicalize /data/data/com.termux/files/usr/tmp/: Permission denied
```

原因不是配置错误，而是 Android 的 W^X 策略：`targetSdk 36` 的 `untrusted_app` 域**不允许
execve 应用可写存储里的 ELF**，而 rootfs 只能落在可写的应用私有目录里。允许执行的
`nativeLibraryDir` 是只读且随 APK 打包的，装不下一棵可变的 rootfs。

Termux / proot-distro 之所以能用，是因为 Termux 长期把 `targetSdk` 停在 28，从而保留了
exec 豁免。MCA 不会为了这个特性把 `targetSdk` 降回 28——那会丢掉整个平台的兼容性要求。

因此：**六步流水线可以完整跑完并把 rootfs 装好，但第 6 步探针会在 targetSdk 36 的设备上
失败**，失败原因会如实显示为"execve ... Permission denied"，不会被伪装成成功。

## 3. 六步安装流水线

`DshContainerInstaller.install(assets, layout, probe, onEvent)`，每一步失败都报点名原因：

| # | 步骤 | 做什么 | 失败意味着 |
| --- | --- | --- | --- |
| 1 | 校验离线资产 | 读 manifest（schema 必须是 `mca.dsh.container.v1`）；流式算 rootfs 归档的 SHA-256 与字节数并与清单比对；确认 nativeLibraryDir 里有 `libproot.so` | 这次构建没打资产，或资产与清单不一致（不会解包任何东西） |
| 2 | 准备安装目录 | 建 `files/dsh/container`，删掉上次崩溃留下的 `.staging` | 存储不可写 |
| 3 | 解包 rootfs | `tar.gz` → `.staging`，带真实字节进度 | 归档损坏 / 格式不受支持 / 越界条目 |
| 4 | 校验 rootfs 结构 | `.staging/bin/sh` 必须存在（Ubuntu 的 `bin/sh` 是到 `dash` 的符号链接，两种都算） | 归档不是完整 rootfs |
| 5 | 原子提交 | 旧 `rootfs` → `rootfs.previous`，`.staging` → `rootfs`，写 `.install-state.json`。提交失败会把旧树移回原位 | 重命名失败（跨设备/权限） |
| 6 | 自检探针 | 渲染唯一允许的 argv 并执行，要求退出码 0 且输出 `ok` | proot 不能执行 / 容器起不来（SELinux、缺库、rootfs 不完整） |

- **幂等**：`.install-state.json` 记录的归档 SHA-256 与本次清单一致且 `rootfs/bin/sh` 存在时，
  第 2–5 步直接复用，只跑第 6 步。
- **可回滚**：第 5 步保留 `rootfs.previous`；新 rootfs 起不来时旧树还在原地。
- **解包安全**：绝对路径、`..` 段、经符号链接父目录越界的条目一律拒绝；条目数上限 400k、
  解压总量上限 2 GiB。GNU 长文件名、pax `path`/`linkpath`、ustar prefix 都支持。
  不支持创建符号链接的宿主机（Windows 无 SeCreateSymbolicLinkPrivilege）会把**同目录相对**
  链接物化为副本，其他情况的链接直接失败而不是猜测。

## 4. 容器命令（唯一可执行形态）

`DshContainerCommand.argv()` 的固定形状（纯函数，有单测）：

```
proot -r <rootfsPath> -0 -w /root \
      -b /dev -b /proc -b /sys [-b <挂载>]… \
      -- /bin/sh -c <entryCommand>
```

- 每个参数都是独立 argv 元素，**不拼接 shell 字符串**。
- 挂载合并：`/dev`、`/proc`、`/sys` 永远在前，随后是 spec 的额外挂载，按 source 去重并**保留最后一次**。
- 路径校验：必须是绝对路径、不含 `..` 段、不是 `content://` URI；入口命令不能为空、不能含 NUL/换行。
- **唯一可执行的入口命令**是常量 `echo ok`（`PROBE_ENTRY_COMMAND`）。`DshContainerCommandPolicy`
  只放行由同一 spec 渲染出的探针 argv；任何带用户输入的 argv 都不放行。

## 5. 状态机

| 状态 | 条件 | 控制台行为 |
| --- | --- | --- |
| `SUPPORTED` | nativeLibraryDir 找到 proot **且** `<rootfs>/bin/sh` 存在 | 显示路径，允许"运行固定探针" |
| `MISSING_PROOT` | 两个候选位置都没有 proot | 说明本次构建没打资产、要跑哪个脚本；安装按钮仍可点（会如实报缺资产） |
| `NOT_PERMITTED` | proot 只在应用可写目录 | 说明 SELinux/exec 限制；探针按钮禁用 |
| `MISSING_ROOTFS` | proot 可用但 rootfs 缺失或缺 `bin/sh` | 提示点"安装内置 Ubuntu" |

判定顺序 "先 proot 后 rootfs"。探针结果（退出码/输出/超时）独立成行，失败不会被"刷新"掩盖。

## 6. 验证与未验证项

已验证（JVM，`DshContainerInstallerTest` / `DshContainerTarGzTest` / `DshContainerContractTest`）：

- 六步顺序、事件流、提交后的目录形状、`.install-state.json` 写入、`.staging` 清理；
- 缺资产 → `AssetsMissing` 且不落盘；哈希不符 → `Integrity` 且**不解包**；二次运行复用；探针失败保留树；
- tar：普通文件/目录/符号链接/模式位、绝对路径与 `..` 拒绝、经符号链接父目录越界拒绝、缺 `bin/sh` 拒绝；
- manifest：schema 不符、SHA 形状不符、`matchesArchive` 的字节数与哈希两条路径。

真机已验证（v0.3.9，`98a37aa7`）：proot 二进制可从 `nativeLibraryDir` 执行（`--version` 通过）；
rootfs 归档可从 APK 资产读出并按官方 SHA-256 校验；**第 6 步探针因上述 W^X 限制失败**。

未验证（NOT_RUN）：

- 容器内的**真实工作负载**（`apt update`、交互式 shell、长时运行）：本轮只证明"能起、能出 ok"；
- `rootfs.previous` 的回滚路径只在单测里覆盖，真机未演练；
- x86_64 ABI（本项目只发 arm64-v8a）；
- proot 在个别厂商 ROM 上可能被 SELinux 策略拒绝，届时状态会是 `MISSING_PROOT`/探针失败，
  不会伪装成可用。

## 7. 复现构建

```powershell
pwsh scripts/dsh/stage-container.ps1     # 下载 + 校验 + 摆放（约 30 MB，一次性）
.\scripts\release.ps1                    # 打包含容器资产的 APK
```

不跑第 1 步也能构建：APK 变小，容器区块显示"缺少 proot"，安装按钮报 `AssetsMissing`。
这条降级路径是有意设计的——构建永远不因为缺二进制资产而失败。

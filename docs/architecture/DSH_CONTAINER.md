# DSH 免 root 容器（proot）落地说明

版本：1.0 · 状态：评估落地（本版**不内置** rootfs 与 proot 二进制）

本文说明 `feature:dsh-workspace` 的容器运行时契约（`container/DshContainerContract.kt`、
`container/DshContainerHost.kt`）与控制台区块（`ui/DshContainerSection.kt`）做了什么、没做什么，
以及用户自备 proot/rootfs 时应当如何放置。它是工程合同的一部分，不替代 [MCA_PROJECT_HARD_LOCK.md](MCA_PROJECT_HARD_LOCK.md)。

## 1. 结论：本版为什么不内置

参考实现（DSHA）把 **proot 二进制 + 一份 Ubuntu rootfs** 随包分发。这两项在本仓库中都不存在，
本轮也不可能由代码生成：

| 资产 | 性质 | 本版处置 |
| --- | --- | --- |
| `proot` | 第三方 C 程序编译产物（上游 GPL-2.0；termux-packages 的分发受其各自许可证约束），需按 ABI 编译/挑选 | 不内置、不自动下载 |
| Ubuntu rootfs（含 `/bin/sh`） | 数百 MB 的第三方发行版文件树（含 GPL/LGPL 组件与发行版许可声明） | 不内置、不自动下载 |

理由不是"文件大"，而是：随包分发他人二进制/文件树属于**资产与许可证决策**，必须单独评审
（来源、版本、校验和、许可证文本、升级与替换策略、供应链可复现）。未完成该决策前，容器能力保持
"用户自备、我们只检测与执行固定探针"。

> 本版**没有**在 APK 中加入任何 rootfs、proot、下载器或安装向导；`feature:dsh-workspace` 也没有
> 新增依赖、没有改 build 文件。

## 2. 现有终端能力的边界（审计结论）

- **uid**：`core:dsh-runtime` 的 `libdshpty.so` 在**应用进程内** `fork`，`setsid` + `TIOCSCTTY` 后
  `execve(argv[0])`；没有 `setuid`/`setgid`/`chroot`，子进程仍是本应用 uid（`u0_aXXX`）。
  proot 的 `-0` 只是**用户命名空间内**的伪 root，不会改变真实 uid，也不等于系统权限。
- **可执行 ELF 的位置**：`execve()` 成功与否由 SELinux 决定。targetSdk 36 的
  `untrusted_app` 域不能执行应用可写目录（`app_data_file` 的 `exec_no_trans` 被拒绝），所以
  `DshCommandClosure` 的既有结论是：真实 ELF 只能放在**APK `nativeLibraryDir`**（本项目
  `android:extractNativeLibs="true"` + `useLegacyPackaging = true`，原生库会被解包到可执行目录），
  或经 `/system/bin/linker64` 方式启动；应用私有目录里的脚本/ELF 只能当**数据**。
- 因此：**把 proot 放到 `files/dsh/container/proot` 一定执行不了**。契约把这种情况映射为
  `NOT_PERMITTED`（"proot 存在但不能执行"），而不是谎称 `SUPPORTED`。
- **SAF 现状**：`WORKSPACE_TREE` 是既有的一条持久 SAF 目录授权（`OpenDocumentTree` +
  `takePersistableUriPermission`，见 `core:dsh-platform`）。它是 `content://` URI，**不是** proot
  `-r` 需要的真实文件系统路径；本版把它作为"需要先导入"的提示（`MISSING_ROOTFS` 的 detail），
  不当作 root 路径，也不实现导入（导入会复制数百 MB，属于独立切片）。
- **Termux**：本版只用 `PackageManager` 做一次**提示性**检测（API 30+ 受包可见性限制，通常查不到；
  未为此增加 `<queries>`）。Termux 永远不是依赖、不是门禁、不是拒绝理由——项目硬规则禁止用
  设备/应用匹配屏蔽功能；`DshContainerProbeTest` 断言带不带该标志状态完全相同。

## 3. 用户自备目录布局

```
files/dsh/container/
├── rootfs/                  # 一份含 bin/sh 的 Linux rootfs（Ubuntu base 即可）
│   ├── bin/sh               # 必需的根文件系统证明
│   └── ...
└── proot                   # 只作为数据；本版不会执行它（见 §2）
```

proot 的**可执行位置**（二选一）：

1. **推荐**：把 arm64-v8a 的 `proot` 二进制打包进 APK，命名 `libproot.so`，放在
   `nativeLibraryDir`（`core:dsh-runtime/src/main/jniLibs/arm64-v8a/libproot.so` 或等价位置）。
   由打包负责人评审许可证与来源后引入；引入前状态恒为 `MISSING_PROOT`。
2. 系统可执行目录（需要 root；本项目不以 root 为前置条件）。

放置 rootfs 时注意：解压后必须保留 `bin/sh`（Ubuntu base 的 `/bin/sh` 是 `dash` 的符号链接），
并保证目录可读；proot 会以只读方式 bind `/dev /proc /sys` 与应用 `files/` 目录。

## 4. 容器命令（唯一可执行形态）

`DshContainerCommand.argv()` 的固定形状（纯函数，有单测）：

```
proot -r <rootfsPath> -0 -w /root \
      -b /dev -b /proc -b /sys [-b <挂载>]… \
      -- /bin/sh -c <entryCommand>
```

- 每个参数都是独立 argv 元素，**不拼接 shell 字符串**；带空格、引号、中文的路径/命令保持单个 token。
- 挂载合并：`/dev`、`/proc`、`/sys` 永远在前，随后是 spec 的额外挂载，按 source 去重并**保留最后一次**
  （显式 `/dev:/dev` 覆盖默认值，与 proot "后者覆盖"一致）。
- 路径校验：必须是绝对路径、不含 `..` 段、不是 `content://` URI；入口命令不能为空、不能含 NUL/换行。
- **唯一可执行的入口命令**是常量 `echo ok`（`PROBE_ENTRY_COMMAND`）。`DshContainerCommandPolicy`
  只放行由同一 spec 渲染出的探针 argv；任何带用户输入的 argv 都不放行。设备诊断白名单
  `DshDeviceCommandPolicy` 按设计拒绝 `sh`/`proot`，容器通道不能借道，因此使用这条**单条常量白名单**，
  而不是第二套通用命令白名单。

## 5. 状态机

| 状态 | 条件 | 控制台行为 |
| --- | --- | --- |
| `SUPPORTED` | nativeLibraryDir 找到 proot **且** `<rootfs>/bin/sh` 存在 | 显示路径，允许"运行固定探针" |
| `MISSING_PROOT` | 两个候选位置都没有 proot | 说明放置方式；探针按钮禁用 |
| `NOT_PERMITTED` | proot 只在应用可写目录 | 说明 SELinux/exec 限制；探针按钮禁用 |
| `MISSING_ROOTFS` | proot 可用但 rootfs 缺失或缺 `bin/sh` | 说明布局与 SAF 导入限制；探针按钮禁用 |

判定顺序 "先 proot 后 rootfs"：两者都缺时用户下一步仍是先准备 proot。探针结果（退出码/输出/
超时）独立成行，失败不会被"刷新"掩盖。

探针执行边界：`DshContainerHost` 进程级单例，`ProcessBuilder`（无 shell、无 stdin、合并输出上限
8 KiB、20 s 超时后 `destroyForcibly`），只在 `SUPPORTED` 下运行常量 argv。长期归属应是既有终端/命令
runtime；本切片位于 feature 模块是因为本轮不允许改 `core/**`，且该处只暴露**一条固定命令**，不是第二套
通用执行面。

## 6. 验证与未验证项

已验证（JVM）：

- argv 形状、空格/引号/中文路径单 token、挂载合并顺序、路径越界拒绝、入口命令约束；
- 四种 probe 状态映射与缺失原因文案；SAF/Termux 只影响文案不影响状态；
- 契约测试 `DshContainerContractTest`（纯 JVM，不依赖设备、rootfs 或 proot 二进制）。

未验证（NOT_RUN，属预期）：

- **真机 proot 执行**：没有任何 proot 二进制/rootfs 进包，探针在真机上必然走
  `MISSING_PROOT`；因此"容器真的能跑"只在用户自备资产并由打包负责人在后续切片引入后才有意义。
- nativeLibraryDir 中 `libproot.so` 的可执行性（取决于设备 SELinux 策略与 AGP 打包行为）。
- 大 rootfs 的解压/导入性能、存储配额、`-b` 路径在真实设备上的可读性。

## 7. 若决定内置 Ubuntu（后续切片的前置决策）

1. 资产决策：proot 来源仓库与 commit、构建方式、GPL-2.0 与其余组件许可证随包合规、校验和；
2. rootfs 决策：发行版/版本、精简程度、许可证清单、更新与替换策略、包体增量与 ABI；
3. 引入方式：`jniLibs/arm64-v8a/libproot.so` + `assets/rootfs.tar.zst`（首次启动解压到
   `files/dsh/container/rootfs`，带校验和与失败回滚）；
4. 安全边界：容器只是"文件系统隔离"，不是安全沙箱；不得据此声称隔离了恶意代码；
5. 只有完成 1–4 并接入真实探针证据后，才可以把本文 §1 的"不内置"改为"内置"。

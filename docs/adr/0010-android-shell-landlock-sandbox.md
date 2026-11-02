# ADR 0010：Android shell workspace-write 的真实内核限制

- 状态：**Accepted；host 层已实现并通过 JVM/编译验证；Android 17 测试机内核未启用 Landlock，实际生效路径为 advisory（高信任执行）**
- 日期：2026-10-24
- 决策范围：DSH 模型发起的 shell 命令在 `workspace-write` 预设下的文件边界；不改变 DSH 权限预设、审批 UI、官方 Agent loop、插件体系或文件工具语义
- 关联：[工程硬锁](../architecture/MCA_PROJECT_HARD_LOCK.md)、[completion-v2 方案](../proposals/agent-dsh-embedded-v1/completion-v2/README.md)、[D01/D02](../proposals/agent-dsh-embedded-v1/completion-v2/EXECUTION_PACK.md)、[旧发现](../../artifacts/dsh-v2/verification/SANDBOX-WORKSPACE-WRITE.md)、[证据](../../artifacts/dsh-v2/verification/LANDLOCK-WORKSPACE-WRITE.md)

## 1. 背景

锁定 payload 的 `@deepseek-ai/dsh-sandbox-local` 是官方进程限制 provider：它接受 operator 提供的 `runnerCommand`，并把命令包装成 `runner <bwrap-compatible profile> -- <argv>`。官方 Linux 链使用 `bwrap` 或打包的 `landlock-run`，但 payload 没有 Android 可执行的 launcher，因此参考 overlay 禁用了官方 `bash-sandbox`，改用 `bash-local`。

`bash-local` 的 `sandboxMode = workspace-write` 只是让权限预设和工具接受该模式；它从不调用 `ctx.sandbox`。2026-10-23 的设备证据证明 `touch ../../mca-escape.txt` 可以在会话工作区之外创建文件，且没有审批/拒绝。这是“显示为工作区隔离、实际不受限”的不诚实状态，必须在功能上修复，而不是继续用路径字符串过滤冒充隔离。

## 2. 决策

### D1：保留官方 sandbox 栈，只补 Android 平台 runner

overlay 的文本渲染由纯函数 `DshOverlayComposer` 负责；seeder 只做探针、模式选择和事务性发布，避免把平台行规则继续堆进已经超预算的 seeder。

- 保持官方 `bash-sandbox`、`fs-sandbox`、`sandbox-local`、`sandbox-policy` 与审批流；不重写 Agent loop、不新建 Kotlin shell 隔离层。
- 新增自有 native PIE `libdshsandbox.so`（源码 `core/dsh-runtime/src/main/cpp/dsh_sandbox.c`，由 `scripts/dsh/build-sandbox-runner.ps1` 用项目 NDK 编译并 stage 到 jniLibs）。它解析 `dsh-sandbox-local` 实际发出的 bwrap profile 参数，并在 `exec` 前对本进程施加 Landlock ruleset：
  - 只处理写类访问（WRITE_FILE、REMOVE_*、MAKE_*；ABI≥2 加 REFER，ABI≥3 加 TRUNCATE）；
  - 允许写 `/dev/null`、会话工作区（`--bind`）与临时目录（`--tmpfs /tmp` 映射到 `$TMPDIR`，Android 无 `/tmp`）；
  - 读与 exec 不限制，与官方 bwrap profile 的 `--ro-bind / /` 等价；
  - `--die-with-parent` 映射为 `PR_SET_PDEATHSIG`；`--unshare-pid`/`--proc`/`--dev` 不做无权限的伪实现；
  - 启动器失败统一 exit 125 + `mca-dsh-sandbox: ` 前缀；目标 exec 失败用 127 且不带该前缀，避免被误判为 runner 故障。
- 不在 payload 内打补丁，因此不需要重新锁定 JS 闭包；runner 是 APK 的 native 产物，随 APK 哈希绑定。

### D2：能力探测决定组合，失败必须诚实降级

host seeder 在生成 overlay 前运行 `libdshsandbox.so --probe`。探针不是 `--version` 检查：它在可写 cwd 中创建一个仅该子目录可写的 Landlock ruleset，验证“授权目录写入成功 + 父目录写入被拒绝”，失败或超时都返回 typed unavailable。

- 探针通过：overlay 保留官方 `bash-sandbox`，给 `sandbox` 行写绝对 `runnerCommand` 与 `runnerFailureSignatures`，**不插入** `bash-local`；overlay 写 `# mca-sandbox: kernel (landlock abi=N)`。
- 探针失败/runner 缺失：禁用 `bash-sandbox`，插入 `bash-local`，overlay 写 `# mca-sandbox: advisory (REASON)`。这只保留兼容入口；控制台不得再把该组合描述为工作区隔离。
- 两种组合都经过 `DshComposedConfigInspector` 的诚实门：kernel 模式必须有绝对 runner 路径且不能有 `bash-local`；advisory 模式必须有 advisory 标记且不能宣称 runner。
- 控制台 `PRIVILEGED_SHELL` 只在真实 `id -u`/成功 shell 证明后显示 GRANTED；`VIRTUAL_SCREEN` 需要 capture consent；`BROWSER` 需要 overlay 权限；`ANDROID_SYSTEM` 的详情明确哪些子动作仍走 payload 自己的 root/Shizuku 路径。

### D3：临时目录与命令环境

`mca-shell-env` 现在同时注入 `DSH_PAYLOAD_DIR` 与 `TMPDIR=<app-private dsh/tmp>`。这样 `--tmpfs /tmp` 的 Android 等价授权与子进程实际看到的临时目录一致；没有可写 `/tmp` 的环境不会被伪装成可写。

## 3. 影响与边界

- **行为变化（有意的安全修复）**：在 kernel 模式下，`workspace-write` 内的 shell 命令不能再写会话工作区之外；原先依赖越界写入的脚本会得到 `Permission denied`，可通过官方审批升级到 `danger-full-access`。这是纠正先前的虚假隔离声明，不是删除功能或降低权限模式语义。
- **不是完整沙箱**：读、网络、设备 ioctl、进程可见性不受 Landlock 写规则限制；插件/工具若绕过 `ctx.shell` 直接调用 `ctx.subprocess`，不在本 runner 的保证范围内；文件工具的边界仍由官方 `fs-sandbox` 的 trusted path fence 提供（不是内核边界）。
- **无 payload/协议变更**：不修改官方 JS、不新增 shell 解析器、不替换 Agent loop；共享契约只增加 typed sandbox fact 与平台配置字段。
- **回退**：删掉 `sandboxRunnerFile` 或让探针失败即回到 advisory 组合；也可撤本切片源码与 jniLibs artifact。无数据迁移，无 session/workspace 格式变化。

## 4. 验证与未完成项

- 已执行：native arm64 PIE 编译/stage（`d7821e2a…`），`:core:dsh-runtime:testDebugUnitTest` 449/0（4 skipped），`:core:dsh-platform:testDebugUnitTest` 110/0，`:core:dsh-vscreen:testDebugUnitTest` 98/0，`:app:compileReleaseKotlin` BUILD SUCCESSFUL（exit 0）。
- 设备探针（2026-10-24，Android 17 / kernel 6.6.118-android15-8-gki / `user`）：
  `libdshsandbox.so --probe` 返回 `Landlock is unavailable on this kernel (Function not implemented)`，
  `zcat /proc/config.gz` 显示 `# CONFIG_SECURITY_LANDLOCK is not set`，`Seccomp: 0`。这不是 seccomp 或
  runner 的问题；该内核根本无法执行 Landlock。seeder 因此选择 `advisory(KERNEL_PROBE_FAILED)`，
  控制台显示“高信任执行（未隔离）”，这是本设备的正确结论，不是隔离 PASS。
- 仍未执行：支持 Landlock 的设备上的越界拒绝、授权目录写入、子进程继承、symlink 逃逸和
  `danger-full-access` 审批升级；当前测试机不支持这些能力，故内核强制路径在该设备为
  **NOT_APPLICABLE**，advisory 显示为设备待复测（需安装新 APK）。
- 设备验收步骤见 `artifacts/dsh-v2/verification/LANDLOCK-WORKSPACE-WRITE.md` §4；在支持 Landlock 的
  设备上通过前不得对外宣称“工作区隔离已生效”。

## 5. 例外

无。本 ADR 不涉及功能删减、冻结视觉变更、数据破坏或外部发布。

# MCA 内嵌原版 DSH V1 — 基线锁定（R00）

状态：R00 交付文档。日期：2026-10-19（Asia/Shanghai）。
本文只记录**只读核对到的事实**与**明确的未知项**；不表示 Android 可运行、功能等价或发布验收通过。
关联文档：[架构方案](ARCHITECTURE.md)、[执行包](EXECUTION_PACK.md)、[ADR 0003 双 Agent 模式](../../adr/0003-dsh-embedded-dual-agent-modes.md)、[能力清单](CAPABILITY_MANIFEST.json)、[验证计划](VERIFICATION_PLAN.md)、[证据目录说明](evidence/README.md)。

## 1. 基线的含义与易变警告

- 本次基线快照时间：**2026-10-19T19:27:35+08:00**（MCA 仓库扫描脚本输出），官方 DSH 与参考项目在同一时段核对。
- MCA 工作区**正在被多个并行 Agent 修改和构建**：19:22 观察到 53 条 porcelain 记录，19:27 快照为 63 条。基线是“拍摄时刻”的记录，不是冻结提交。
- **R00 不产生可构建结论**。R07 最终验收前必须重新冻结一次源码、工作区补丁、子模块内部 diff、官方 DSH SHA、payload 与 APK，并重新生成同格式快照；旧快照不能冒充最终候选。
- 本文所有结果使用 `PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE` 词汇。R00 没有任何设备或桌面运行，因此运行时结果一律 `NOT_RUN`。

## 2. MCA 仓库基线

### 2.1 仓库身份

| 项 | 值 |
| --- | --- |
| 路径 | `E:\MAC` |
| 分支 | `codex/enterprise-refactor` |
| HEAD | `98eb8e47a98bd90d2f071203c2a56dcd29b013d1` |
| HEAD 提交时间 | 2026-10-19 19:03:19 +0800 |
| HEAD 提交标题 | `polish: batch of a11y, large-font, motion and state fixes from the parallel audits` |
| 暂存区 | 空（scan 时无 staged 变更） |
| tracked 修改 | 42 个文件（scan 时） |
| untracked 条目 | 21 条（scan 时） |
| tracked diff 指纹 | 189,739 bytes；SHA-256 `9ee67e75bec54cc99d2a40318cce4f5d9d4ee1b5d9378b063f69f68a5b0e268c`（`git diff --binary` 输出字节） |
| 脏工作区说明 | 该指纹只覆盖 tracked diff；untracked 新文件不在其中，必须与子模块内部 diff 分别记录 |

**不得**把 HEAD SHA 当作可复现构建来源：工作区有大量未提交 Agent V1 与并行的前端/可访问性工作；`HEAD + 工作区补丁 + 子模块内部 diff` 才是实际构建输入。

### 2.2 tracked 修改清单（scan 时 42 个）

```text
app/build.gradle.kts
app/src/main/java/com/muyuchat/mca/ChatExecutionOperations.kt
app/src/main/java/com/muyuchat/mca/ChatExecutionPortAdapters.kt
app/src/main/java/com/muyuchat/mca/ChatRunOwnership.kt
app/src/main/java/com/muyuchat/mca/ChatSessionStoreRoomDatabase.kt
app/src/main/java/com/muyuchat/mca/ChatStopGenerationOperations.kt
app/src/main/java/com/muyuchat/mca/GenerationForegroundLeaseRegistry.kt
app/src/main/java/com/muyuchat/mca/MainActivity.kt
app/src/main/java/com/muyuchat/mca/MainChatScreenActions.kt
app/src/main/java/com/muyuchat/mca/MainViewModel.kt
app/src/main/java/com/muyuchat/mca/McaGenerationForegroundService.kt
app/src/main/java/com/muyuchat/mca/UiGenerationOwnership.kt
app/src/main/java/com/muyuchat/mca/WebSearchCollectionOperations.kt
app/src/main/java/com/muyuchat/mca/WebSearchTurnExecution.kt
app/src/main/java/com/muyuchat/mca/composition/MainRuntimeAssembly.kt
app/src/main/res/xml/backup_rules.xml
app/src/main/res/xml/data_extraction_rules.xml
app/src/test/java/com/muyuchat/mca/ChatStopGenerationOperationsTest.kt
app/src/test/java/com/muyuchat/mca/KnowledgeBaseRoomContractTest.kt
app/src/test/java/com/muyuchat/mca/MainShellMotionContractTest.kt
app/src/test/java/com/muyuchat/mca/MainViewModelCompletionFallbackTest.kt
app/src/test/java/com/muyuchat/mca/McaGenerationForegroundServiceContractTest.kt
app/src/test/java/com/muyuchat/mca/RoomSchemaContractTest.kt
architecture/architecture-manifest.json
core/engine/src/main/java/com/muyuchat/core/engine/GenieXChatRunner.kt
core/engine/src/main/java/com/muyuchat/core/engine/LiteRtLmChatRunner.kt
core/engine/src/main/java/com/muyuchat/core/engine/LocalRunnerShells.kt
core/engine/src/main/java/com/muyuchat/core/engine/McaInferenceService.kt
core/engine/src/main/java/com/muyuchat/core/engine/McaInferenceStopControl.kt
core/engine/src/main/java/com/muyuchat/core/engine/McaInferenceStreamChat.kt
core/engine/src/main/java/com/muyuchat/core/engine/McaInferenceStreamPrefill.kt
feature/chat/src/main/java/com/muyuchat/feature/chat/ChatHistoryDrawerScreens.kt
feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt
feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreenActions.kt
feature/chat/src/main/java/com/muyuchat/feature/chat/ChatViewModel.kt
gradle.properties
gradle/libs.versions.toml
scripts/verify-code.ps1
settings.gradle.kts
third_party/tokenizers-cpp
tools/architecture/check_architecture_rules.py
tools/architecture/generate_architecture_manifest.py
```

### 2.3 untracked 清单（scan 时 21 条）

| 条目 | 文件数 | 字节 |
| --- | --- | --- |
| `app/schemas/com.muyuchat.mca.McaRoomDatabase/28.json` | 1 | 96,849 |
| `app/src/androidTest/java/com/muyuchat/mca/agent/` | 4 | 24,563 |
| `app/src/main/java/com/muyuchat/mca/agent/` | 119 | 991,615 |
| `app/src/main/java/com/muyuchat/mca/presentation/AgentDocumentLauncherUi.kt` | 1 | 2,792 |
| `app/src/main/java/com/muyuchat/mca/presentation/AgentMcpAdminService.kt` | 1 | 5,781 |
| `app/src/main/java/com/muyuchat/mca/presentation/AgentModelCatalogAdapter.kt` | 1 | 4,277 |
| `app/src/main/java/com/muyuchat/mca/presentation/AgentPackageService.kt` | 1 | 5,230 |
| `app/src/main/java/com/muyuchat/mca/presentation/AgentSkillAdminService.kt` | 1 | 6,326 |
| `app/src/test/java/com/muyuchat/mca/ChatAttemptStopIdentityContractTest.kt` | 1 | 9,043 |
| `app/src/test/java/com/muyuchat/mca/ChatSearchCancellationContractTest.kt` | 1 | 6,243 |
| `app/src/test/java/com/muyuchat/mca/agent/` | 64 | 565,603 |
| `core/agent-contract/` | 14 | 54,868 |
| `core/agent-runtime/` | 46 | 502,507 |
| `core/dsh-contract/` | 3 | 8,047 |
| `core/engine/src/main/java/com/muyuchat/core/engine/EngineStopSettlement.kt` | 1 | 16,247 |
| `docs/adr/0002-agent-v1-runtime-and-delivery.md` | 1 | 22,184 |
| `docs/proposals/agent-chat-integration-v1/` | 4 | 110,765 |
| `docs/proposals/agent-dsh-embedded-v1/` | 2（scan 时，仅 ARCHITECTURE 与 EXECUTION_PACK） | 37,218 |
| `docs/proposals/agent-v1/` | 18 | 681,902 |
| `feature/agent-workspace/` | 56 | 522,403 |
| `scripts/agent-verify.ps1` | 1 | 5,316 |

- 以上 Agent V1 代码与文档是**既有并行工作**，不是 R00 新增；R00 只新增本目录四份文档与 `docs/adr/0003-*.md`。
- `core/dsh-contract/` 已在 scan 时存在（Lead-owned，冻结）：`DshRuntimeContract.kt`（运行时 phase/失败码/snapshot/webEntry/endpoint 契约）与 `DshModelGatewayContract.kt`（协议中立的消息、工具调用、流事件与 finish reason）。R00 不修改它。

### 2.4 进行中的模块接线（不是缺陷，但影响可构建性）

- `settings.gradle.kts` 增加了：
  - `include(":core:agent-contract")`、`include(":core:agent-runtime")`
  - `include(":core:dsh-contract")`
  - `include(":core:dsh-runtime")`
  - `include(":api:dsh-gateway")`
  - `include(":feature:dsh-workspace")`
  - `include(":feature:agent-workspace")`
- scan 时 `core/dsh-contract/` 存在，但 `core/dsh-runtime/`、`api/dsh-gateway/`、`feature/dsh-workspace/` 三个目录**尚不存在**。该接线由整合者（Lead）加入，三个模块由并行 teammate 正在创建。
- 结论：19:27 的树**不能完成 Gradle 配置**；这是 in-flight 状态，不作为缺陷记录。R01/R07 构建前必须确认目录已落地，且 `./gradlew` 配置成功。

### 2.5 子模块基线（revision 与内部未提交 diff 都是构建输入）

| 子模块 | revision | 内部状态 | 内部 diffstat | 内部 diff SHA-256（`git diff --binary` 字节） |
| --- | --- | --- | --- | --- |
| `third_party/llama.cpp` | `4ceb1719101f32637b841206c172f3f058ffc182`（v0.4.1-147-g4ceb17191） | 20 个已跟踪文件修改 | 20 files changed, +475 / -35 | `d84a4f4d348526cf492bd39c7a0e7537852730a5e2b83f1db7113a57c3559ab1` |
| `third_party/stable-diffusion.cpp` | `16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0`（master-663-be65ac7-1-g16b3846） | 16 个已跟踪文件修改；3 个删除（`examples/server/frontend`、`thirdparty/libwebm`、`thirdparty/libwebp`）；2 个 untracked（`src/qwen_image_2_1.hpp`、`src/qwen_image_2_1_vae.hpp`） | 16 files changed, +2466 / -147（未含 untracked 头文件） | `fc0ea62de6de75013f45315b2ab5afedb4fa4d90945f0f4e2dd330c98d12c2c6` |
| `third_party/tokenizers-cpp` | `acbdc5a27ae01ba74cda756f94da698d40f11dfe`（v0.1.1-9-gacbdc5a） | 5 个已跟踪文件修改（CMakeLists、两个 C 头、rust/src/lib.rs、huggingface_tokenizer.cc） | 5 files changed, +478 / -117 | `605466392a7769ed9e1625c7b03d298bbad4cb41c967df466084b2abb6eaefe6` |

- tokenizers-cpp 当前内部 diff 指纹与 ADR 0002 记录的 `8B6C0D1D…` **不一致**：Agent V1 工作期间该子模块补丁已经变化（rust 适配扩大到 483 行变更）。因此：
  - 不得用 ADR 0002 的旧指纹代表当前构建输入；
  - R07 必须以最终重新计算的内部 diff 指纹为准；
  - 该子模块属于**既有债务 D08**，R00 不修改、不清理、不覆盖。
- 三个子模块的内部 diff 都必须在最终 APK/候选证据中绑定；只记录父仓库 submodule 指针不足。
- 本机可能同时存在并行 Gradle/native 构建（scan 期间 `core/agent-runtime/build` 有文件在写入）。R00 不启动构建；后续构建必须遵守单一构建锁。

### 2.6 工具链与构建合同

| 项 | 值 | 来源 |
| --- | --- | --- |
| JDK | 21.0.12.1+1（Temurin 21.0.12.1+1-LTS） | `E:\model\toolchains\jdk-21\jdk-21.0.12.1+1`，`JAVA_HOME` 已指向 |
| Android SDK | `D:\model\android-sdk` | `local.properties: sdk.dir=D\:\\model\\android-sdk` |
| platforms | `android-35`、`android-36` | SDK 目录实测 |
| build-tools | `34.0.0`、`35.0.0`、`36.0.0` | SDK 目录实测 |
| Gradle | 8.14.3 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 8.13.2 | `gradle/libs.versions.toml` |
| Kotlin | 2.3.21 | 同上 |
| compileSdk / minSdk / targetSdk | **36 / 26 / 36** | 同上，行 5–7 |
| NDK | 29.0.13113456 | 同上，行 8 |
| coroutines / Room / Jackson | 1.9.0 / 2.8.4 / 2.22.4 | 同上 |
| Gradle JVM 参数 | `-Xmx4096m`，parallel=true，Kotlin daemon `-Xmx3g` | `gradle.properties` |

- `targetSdk 36` 是 MCA 的既有合同，不因第三方 runtime 启动器而降级。
- JDK 17 不代表可运行当前全部依赖测试；完整验证需要 JDK 21（见硬锁第 11 节）。

### 2.7 MCA 应用身份（当前）

| 项 | 值 | 来源 |
| --- | --- | --- |
| applicationId | `com.muyuchat.mca` | `app/build.gradle.kts` |
| versionCode | 8 | 同上 |
| versionName | `0.2.4` | 同上 |
| namespace | `com.muyuchat.mca` | 同上 |
| 变体 | 另有 benchmark（`.benchmark`）、diag（`-diag`）、devicetest（`.devicetest`）等测试变体 | 同上 |

升级验收必须使用与既有数据兼容的正式签名和真实历史数据；清数据、改 applicationId 或强制降级都不能作为升级成功证据。

## 3. 官方 DSH 基线（只读审计副本）

### 3.1 来源与身份

| 项 | 值 |
| --- | --- |
| 只读副本 | `C:\Users\example\AppData\Local\Temp\mca-dsh-audit-687f282e8625` |
| 仓库 | `deepseek-ai/deepseek-harness` |
| commit | **`687f282e8625c6b8d11ff58211a90524be2e4317`** |
| SHA 证据 | 副本根 `.mca-review-sha` 内容即该 SHA（**该副本是源码归档，不是 git checkout**，无法用 `git rev-parse` 重新推导；R01 应重新从固定 commit 取源码并核对 `.mca-review-sha`） |
| 包版本 | `0.2.1-rc.2`（根与 342 个 workspace 包均为该版本） |
| 包管理器 | `pnpm@11.28.5`（根 `package.json` 的 `packageManager`） |
| lockfile | `pnpm-lock.yaml`，`lockfileVersion: '9.0'`，27,667 行 / 1,090,222 bytes（scan 时） |
| Node engines | `^22.19.0 \|\| >=24.0.0`（根 `package.json`） |
| 根许可证 | MIT（`LICENSE`，Copyright (c) 2026 DeepSeek） |
| 安全状态 | `SAFETY.md`：experimental developer preview，未做安全审计；sandbox/审批不能保证隔离 |
| R00 操作 | 只读文件；未 `pnpm install`、未运行构建/脚本、未启动 DSH |

### 3.2 workspace 与许可证（静态扫描）

- 扫描索引到 **366 个 workspace `package.json`**（`packages/*/*` 342 个 + `vendor/*` + `native/system/**`）。
- 许可证分布（全部 366）：351 MIT、6 BSD-3-Clause、9 未声明 `license` 字段。
- Web profile 实际组合闭包 **192 个包**：其中已声明许可证的均为 MIT，其余为未声明字段；未发现组合闭包内的非 MIT 声明。
- `THIRD_PARTY_NOTICES.md` 由脚本生成，包含 9 个小节：vendored source、runtime npm dependencies、Chrome DevTools frontend、Claude Code platform payloads、LibreOffice conversion kit、development-only npm dependencies、Python SDK dependencies、bundled Python distributions、first-party native packages。
- 上述是**清单级**事实，不等于已完成逐依赖法律审计。分发前仍须按 `pnpm-lock.yaml` 和 `THIRD_PARTY_NOTICES.md` 核对实际打包闭包、上游 LICENSE 文本与 native/官方 payload 的再分发条款。

### 3.3 Web profile 的实际装配

- `PROFILE_TEMPLATES.web = ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app']`（`packages/boot/app-boot/src/profile.ts`）。
- 组合顺序：`dsh-base` 的 `cordis.patch.yml` → `dsh-web-app` 的 `cordis.patch.yml` → `dsh-web-app/presets/*.patch.yml` → 用户 profile patch。
- 默认 agent preset：`standard`（`agent-preset-registry` 行 `default: standard`）。
- 静态行统计：
  - host plane 行 **186**（其中无静态 `disabled` 表达式的 154 行；其余为 web overlay 禁用、按 preset 重挂、平台条件或 desktop-only）；
  - 浏览器 roster 客户端插件行 **70**；
  - `standard` preset 行 **30**；另有 `ptc` 31、`cordis` 31、`minimal` 10（alternate presets）；
  - 组合包 192 个。
- 行级、包级明细见 [CAPABILITY_MANIFEST.json](CAPABILITY_MANIFEST.json)（schema `mca.dsh.capability.v1`，422 行，全部 `NOT_RUN`）。

### 3.4 启动、认证与默认配置（源码事实）

| 事实 | 源码证据 | 工程含义 |
| --- | --- | --- |
| 启动命令族 | `packages/bundle/web-app/src/startup.ts`：`--host`、`--port`、`--no-open`、`--public-url`、`--trusted-host`、`--tls-cert/--tls-key` | 候选启动：`dsh --profile web --host 127.0.0.1 --port 0 --no-open` |
| wildcard 拒绝 | 同上：`isWildcardHost` 报 usage error | 不开放任意网卡；loopback 为默认 |
| 默认端口 | 3080（webserver 行的 `webStartup.port ?? 3080`） | `--port 0` 由 OS 选空闲端口，需解析打印 URL |
| 认证 | README：启动打印带进程 token 的 URL → 换取签名 HttpOnly/SameSite cookie → 清洁 URL；Host/Origin fence | token 不得入普通日志、崩溃报告、历史或外部 Intent |
| ready 语义 | README 与 `src/index.ts`：`dsh web:` URL 行与浏览器 handoff 只在 Loader 树稳定、required-startup audit 通过、Connection 认证可用后发出 | 端口打开 ≠ ready；宿主必须等 URL 行与认证入口 |
| 权限默认 | `dsh-base`：`DSH_PERMISSION_MODE ?? 'workspace-write'`；approval 为 `ask`，仅 `danger-full-access` 变 `never` | 保留 ask；不得复制参考 app 的 `danger-full-access + approval: never` |
| 工具呈现模式 | `DSH_TOOLS_MODE`（native / ptc），默认 native | 默认 native；PTC 另行验证 |
| 默认模型 | `agent-default-model`：`provider: deepseek-official`，`model: deepseek-flash` | MCA 网关目录如何替换/补充是 R02 的明确决策，不能静默改 |
| 会话检索 | `session-query-sqlite`：`path: ':memory:'`，`openAt: never` | 全文检索默认关闭；SQLite 延迟导入 |
| 会话日志 | `session-persistence-jsonl`：`root: dshHomePath('sessions')` | `$DSH_HOME` 未在 Android 定义 → 未知项 |
| KV 存储 | `storage-json`：`root: dshHomePath('storages')` | 同上 |
| telemetry | `session-telemetry-otel`：`FEEDBACK_ONLY`，官方 collector；`DSH_TELEMETRY_DISABLED` 可退出 | 普通活动不采集；需实测无意外外发 |
| 搜索 | `web.searchProvider: deepseek-official`，`fetchProvider: http`，`DEEPSEEK_API_KEY` | 网络与 key 处理需单独验证 |
| agent 平面 | base 中 process-wide 的工具行在 web 被禁用，改由 per-session preset 挂载 | 不能把“行存在”误读为“agent 可见” |
| 模型 provider | `llm-pi-ai` 为多 provider 双胞胎，settings 为空时休眠；`llm-deepseek` 为原生 adapter | MCA 计划走 openai-completions 自定义 provider；协议保真见 R02 |

## 4. 参考 Android payload（非官方支持，仅研究参考）

### 4.1 源码身份

| 项 | 值 |
| --- | --- |
| 仓库 | `woaiys3/deepseek-harness-android-app` |
| 本地只读副本 | `C:\Users\example\AppData\Local\Temp\dsh-android` |
| tag / commit | `v1.20.1` / `98162a23b39eeeb75b8d4608b3e773bbb13be43d` |
| 工作区 | clean（`git status --porcelain` 为空） |
| AndroidManifest | package `com.deepseek.harness`，versionName `1.20.0`，versionCode `57`，minSdk 24，**targetSdk 28** |
| 启动方式 | `MainActivity` 从可写的 `files/payload/runtime/bin/node` 用 `ProcessBuilder` 启动 Node |
| 官方支持 | **否**。仓库是第三方参考实现，不是 DeepSeek 官方 Android 支持，也不是功能通过证据 |

README 第 244 行明确：`targetSdk` 必须保持 **28**（≥29 会导致 node 二进制 EACCES 起不来）。MCA 保持 targetSdk 36，因此 R01 必须证明另一种宿主机制；参考项目的可写目录 exec 路径对 MCA **不可直接复用**。

### 4.2 APK 与 payload 身份（未最终）

| 项 | 值 |
| --- | --- |
| APK 路径（暂存） | `E:\tmp\dsh-runtime\DeepSeekHarness.apk` |
| 下载 URL | `https://github.com/woaiys3/deepseek-harness-android-app/releases/download/v1.20.1/DeepSeekHarness.apk` |
| 最终大小 | **162,006,797 bytes**（Lead 确认；R00 于 2026-10-19T19:41+08:00 独立复核一致；staging 期间曾观察到 41,402,368 / 62,042,112 bytes 的中间态） |
| SHA-256 | **`c324272bff00e8cce06e0baa2ebb9b88e524a5d246e06f8cb55d39df7c66dafa`**（Lead 确认；R00 独立 `Get-FileHash` 复核一致） |
| APK 状态 | **FINAL_DOWNLOAD_VERIFIED**（下载产物身份已固定） |
| payload 内部 manifest | **NOT_FINAL**（逐文件路径/大小/SHA-256 与解包清单由 dsh-runtime 提取，尚未收到；不得用整包 SHA-256 代替） |
| 包名/版本（源码 Manifest） | `com.deepseek.harness` / 1.20.0 (57) |
| minSdk / targetSdk（源码 Manifest） | 24 / 28 |

- APK 身份已固定只代表“下载到的参考产物”可复现；**不代表** payload 解包后的逐文件完整性已核对，也不代表该 APK 可在 MCA 的 targetSdk 36 下运行。

**重要（Lead 确认的 payload 事实）**：参考 payload 对依赖做了降级替换：

- `node-pty` → 被替换为 `child_process`：参考 payload **没有真实 PTY**；普通 pipe 不能算 PTY 通过。
- `sharp` → 被替换为纯 JS 实现：参考 payload **没有原生 sharp**；原生图片处理不能因此算通过。

因此真 PTY 与原生 sharp 在本基线中保持 `NOT_RUN`（参考 payload 只能作为“降级实现”证据，绝不能升级为原生能力 PASS）。后续只有在 MCA 自己的宿主与产物上取得真实证据，才能改变结论。

### 4.3 参考项目许可证摘要

- 仓库整体 MIT（`LICENSE`）。
- `THIRD_PARTY_NOTICES.md` 明确：vscreen 虚拟屏模块包含来自 **third-party** 的代码，该部分按 **LGPL-3.0** 分发（随附 GPL-3.0/LGPL-3.0 文本）；Shizuku SDK 为 **Apache-2.0**；Node.js 运行时为 MIT；DSH 内核 `@deepseek-ai/dsh` 为 MIT。
- MCA 若复用参考项目的任何文件，必须逐文件核对许可证与署名，不能把“仓库整体 MIT”当作全部文件 MIT。本基线只记录摘要，不做法律结论。

## 5. 宿主方案决策记录（R01 门，当前未决）

| 候选 | 描述 | 当前状态 | 关键证据要求 |
| --- | --- | --- | --- |
| H1：JNI / Node 共享库宿主 | APK 内按 ABI 打包 Node 共享库/JNI，在专属 Service 进程中运行官方 Web profile | **NOT_RUN / 未选择** | Node 版本、内部 loader、native addon、`process.execPath`、`child_process.spawn/fork`、官方 frontend/动态插件资源、认证、会话持久化/锁、工具子进程、PTC、PTY、停止/回收 |
| H2：APK 内可执行宿主 | APK 内合规打包 Android 可执行文件，验证真实安装路径、链接器依赖与 exec 行为（保持 targetSdk 36） | **NOT_RUN / 未选择** | Bionic/ABI、安装路径、exec 是否被 Android 10+ 限制、进程树与停止、与 H1 的最小对比 |
| 参考实现：可写目录 exec | 参考项目在 `files/payload/runtime/bin/node` 启动，targetSdk 28 | **REJECTED AS-IS（对 MCA）** | 仅作参考；targetSdk 36 下不可直接复用，且 Linux glibc/musl 二进制不能当 Android Bionic 包 |

- **决策：未做出**。R00 只记录候选与门槛；ADR 0003 的状态因此是“accepted for implementation, R01 feasibility gate pending”。
- R01 通过前，不得开始大规模 DSH 适配、不得把任一候选写入发布文档为“已选定”。
- 两个候选都必须遵守：不降低 targetSdk、不把二进制改名 `.so` 当完成、不以 Node hello 代替完整 DSH、不以 stub/mock 代替真实执行。

## 6. 更新与可复现策略

1. **官方 DSH 固定**：只使用 commit `687f282e8625c6b8d11ff58211a90524be2e4317`；**禁止跟随浮动 master/next**。升级必须单独评审，重新生成 BASELINE 与 CAPABILITY_MANIFEST，并重跑受影响回归。
2. **lockfile 固定**：以官方 `pnpm-lock.yaml`（lockfileVersion 9.0）为依赖真值；不得用 `latest`、动态版本或无来源二进制。任何 `patchedDependencies`、`pnpm-workspace.yaml` 的 `allowBuilds` 变更都必须记录理由、上游路径、版本、测试与退出条件。
3. **Node 版本固定**：官方只声明 `^22.19.0 || >=24.0.0`；MCA 尚未选择具体 Node 构建。R01 必须记录：Node 精确版本、构建来源、ABI/Bionic、编译选项、license/NOTICE、SHA-256。
4. **Android 侧固定**：targetSdk 36、minSdk 26、NDK 29.0.13113456、Gradle 8.14.3、AGP 8.13.2、Kotlin 2.3.21、JDK 21.0.12.1+1。
5. **工作区固定**：最终候选必须绑定 HEAD + tracked diff 指纹 + untracked 清单 + 三个子模块 revision 与内部 diff 指纹。R00 的快照不能直接用于最终发布。
6. **payload 固定**：APK 必须记录下载 URL、大小、SHA-256、包名/版本、解包文件 manifest（每文件路径/大小/SHA-256）、许可证文本，以及所有替换/补丁。当前 APK 下载产物已固定（`FINAL_DOWNLOAD_VERIFIED`），但 payload 逐文件 manifest 仍为 `NOT_FINAL`；在收到前不得用整包 SHA-256 代替逐文件核对。
7. **不得**在 `node_modules` 原地手改而无来源记录；不得把旧构建产物缓存当作当前基线；不得清缓存/删 build 掩盖失败。

## 7. 可复现核对步骤（只读）

以下命令只用于重建本基线，不会安装、构建或修改仓库：

```powershell
# MCA 身份与脏工作区
git -C E:\MAC rev-parse HEAD
git -C E:\MAC branch --show-current
git -C E:\MAC status --porcelain=v1
git -C E:\MAC diff --binary | <hash>          # 与本文 tracked diff 指纹比较
git -C E:\MAC submodule status
git -C E:\MAC\third_party\llama.cpp status --porcelain
git -C E:\MAC\third_party\stable-diffusion.cpp status --porcelain
git -C E:\MAC\third_party\tokenizers-cpp status --porcelain
git -C E:\MAC\third_party\tokenizers-cpp diff --binary | <hash>

# 官方 DSH（只读）
Get-Content C:\Users\example\AppData\Local\Temp\mca-dsh-audit-687f282e8625\.mca-review-sha
Get-Content C:\Users\example\AppData\Local\Temp\mca-dsh-audit-687f282e8625\package.json
Get-Content C:\Users\example\AppData\Local\Temp\mca-dsh-audit-687f282e8625\pnpm-lock.yaml -TotalCount 20

# 工具链
& E:\model\toolchains\jdk-21\jdk-21.0.12.1+1\bin\java.exe -version
Get-Content E:\MAC\gradle\libs.versions.toml -TotalCount 10
Get-Content E:\MAC\local.properties
```

- R01 建议从固定 commit 重新下载源码归档并核对 `.mca-review-sha`；本机审计副本不能证明 tarball 未被修改，只能证明其内部 marker 与本文一致。
- 不要用 `git clean`、`git reset`、删除未跟踪目录或重建子模块来“整理”基线。

## 8. 运行结果登记（R00）

| 项目 | 结果 | 说明 |
| --- | --- | --- |
| MCA 构建 | `NOT_RUN` | R00 只读；且 scan 时三个 DSH 模块目录尚未落地 |
| MCA 单元/集成测试 | `NOT_RUN` | 未运行 |
| 官方 DSH 桌面控制运行 | `NOT_RUN` | 未安装依赖、未启动 |
| 官方 DSH Android 运行 | `NOT_RUN` | 无宿主 |
| 模型网关工具闭环 | `NOT_RUN` | R02 |
| APK 安装/升级 | `NOT_RUN` | 无最终 APK |
| 双模式 UI 导航 | `NOT_RUN` | 代码未接线 |
| 真实本地/云端模型 | `NOT_RUN` | 无运行 |
| 取消/停止/资源释放 | `NOT_RUN` | 无运行 |
| 数据隔离/恢复 | `NOT_RUN` | 无运行 |
| 安全（token/loopback/权限） | `NOT_RUN` | 只有源码事实，无运行时验证 |

## 9. 已知未知项（R01/R02/R03/R07 必须解决）

1. **宿主机制未定**：H1（JNI/shared library）与 H2（packaged exec）都未运行；targetSdk 36 下 exec 可行性未知。
2. **Node 构建身份**：精确版本、来源、ABI、Bionic 适配、`process.platform`（`android` 还是 `linux`）、`process.execPath`、`process.getBuiltinModule`、`node:sqlite` 可用性。
3. **profileContext / profile 目录**：`dsh --profile web` 在 Android 上的实际 profile 创建、`node_modules` 链接与可写性未验证。
4. **`$DSH_HOME` / `$HOME`**：Android 上没有默认 HOME；会话、存储、凭据目录必须先定义并验证。
5. **启动/ready/认证**：`dsh web:` URL 行解析、launch token → WebView cookie、Host/Origin、重启后 cookie 恢复。
6. **模型网关协议保真**：tools/tool_choice/assistant tool_calls/tool_call_id/tool result、并行调用、流式终态、usage、取消映射（R02）。
7. **本地模型工具能力**：实际模型模板/解析器是否支持原生 tool calling；`nativeToolCalling=false` 的提示词兼容模式必须单独标注，不能冒称原生。
8. **云端链路**：既有云端适配是否满足字段/终态合同；是否在 V1 范围。
9. **native 依赖**：node-pty、koffi、sharp、node-addon-system（Landlock/flock）、node-addon-require-builtin、@vscode/ripgrep、LibreOffice 在 Android Bionic/arm64 的加载与执行。
10. **真实 PTY / 原生 sharp**：参考 payload 已替换为 child_process / 纯 JS，因此**没有**这两项的原生证据；保持 `NOT_RUN`。
11. **文件交互**：SAF、上传/下载、分享、剪贴板、外链、返回层级、键盘/IME、大字体、深色、窄屏、断线重连（R04/R07）。
12. **后台与进程死亡**：targetSdk 36 前台服务类型、通知、后台限制、进程被杀死后的恢复语义（R03/R06）。
13. **数据目录/备份**：DSH 数据目录与 MCA 数据分离、cloud backup/data transfer 排除、导入导出（R06）。
14. **APK/payload 身份**：APK 大小 162,006,797 bytes 与 SHA-256 `c324272b…66dafa` 已固定并独立复核；但解包 payload 的逐文件 manifest/哈希仍待 dsh-runtime 提供，payload 行保持 `NOT_FINAL`。此外，该参考 APK 与 MCA 自身候选 APK 的签名/包名/安装关系尚未验证（R01/R07）。
15. **设备与系统**：R01/R07 使用的具体机型、Android 版本、ABI、可用内存与存储未定。
16. **许可证再分发**：官方源码、Node 运行时、参考 payload 的各组件许可证与 NOTICE 打包方式未完成逐项核对。

## 10. R00 结论

- 基线身份已按“HEAD + 脏工作区 + 子模块内部 diff + 官方固定 commit + 官方 lockfile + 参考 APK（已固定，payload 内部 manifest 未最终）”锁定。
- 当前没有任可运行证据：宿主、模型协议、设备 UI、双模式、数据、安全全部 `NOT_RUN`。
- 宿主选择是 R01 的门，不是 R00 的结论；ADR 0003 只能标记为“accepted for implementation, R01 feasibility gate pending”。
- 任何后续文档或发布说明不得把本基线中的静态源码事实写成设备通过。

## 11. 修复轮入口（2026-10-19 更新）

侧栏 DSH 入口故障的当前接手入口是 [DSH_SIDEBAR_ENTRY_HANDOFF_AND_FIX_PLAN_20261019.md](../../repair/DSH_SIDEBAR_ENTRY_HANDOFF_AND_FIX_PLAN_20261019.md)：根因（`ChatViewModel` 重建 `ChatShellActions` 时漏转 `onOpenDshWorkspace`）以及空模型目录启动、401 readiness 误判、页面 Starting 恢复三类候选修复已落盘但全部未验证，“候选代码存在”不等于修复完成。本节不改变上面的 R00 基线事实；修复轮的计划与结果保持规则见 [VERIFICATION_PLAN.md](VERIFICATION_PLAN.md) 第 10 节，证据骨架在 [evidence/R07-20261019-21f8b1b/](evidence/R07-20261019-21f8b1b/)（当前 C1–C7 / E1–E9 全部 `NOT_RUN`），且以下限制继续有效：真 PTY `FAIL`、Android sandbox fail-closed（workspace-write）、不宣称真隔离、模型启动后刷新未验证、原生 sharp 与全离线不宣称。

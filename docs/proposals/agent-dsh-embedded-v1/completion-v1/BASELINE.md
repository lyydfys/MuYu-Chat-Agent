# DSH Completion V1 — C00 证据基线

- 任务卡：C00（基线、补丁账本、功能矩阵种子）；类型：只读分析 + 文档/清单。
- 执行者：`baseline-keeper`（C00）。
- 文档生成时间：**2026-10-20T14:30:15+08:00**；git 计数采集窗口 14:24–14:29 +08:00。
- 本轮写集：仅本目录 `docs/proposals/agent-dsh-embedded-v1/completion-v1/` 下的 5 个文件。
- 执行边界：**未运行 Gradle、未构建、未安装、未操作设备**（只读的 `adb devices -l` 除外）、未提交 git、未改生产代码/旧文档/`CAPABILITY_MANIFEST.json`。
- 状态词：`PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`。本文件的 `DIAGNOSED` 只用于补丁账本（源码已读通），**不是**运行状态；见 `EVIDENCE_FORMAT.md`。
- 本文件记录 **C00 时点的可验证事实**。旧候选的 PASS 不结转；源码存在不等于设备运行通过。

## 0. 结论摘要

| 项 | 事实 | 判定 |
| --- | --- | --- |
| 分支 / HEAD | `codex/enterprise-refactor` / `68b6eef1e67870b02bfef6afda2e625d36663da9` | 已实测 |
| HEAD 是否含嵌入式 DSH 源码 | **否**：`git ls-tree -r --name-only HEAD` 中 `dsh-runtime/dsh-contract/dsh-gateway/DshGatewayConfigSeeder` 命中 **0** | 已实测 |
| 工作树 | 95 个 porcelain 条目 = 44 `M` + 51 `??`；`-uall` 展开 714 行 / 670 个未跟踪文件；tracked diff 44 files, +2494/-411 | 已实测 |
| 三个子模块 | llama.cpp `4ceb1719…`（20 项内部变更）、stable-diffusion.cpp `16b3846a…`（18 项）、tokenizers-cpp `acbdc5a2…`（5 项） | 已实测 |
| Payload | `975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab`（160,464,248 B，独立复算一致） | 已实测 |
| 最近真机验证 APK | `b610e1d0…73ae9a`，393,717,991 B，2026-10-20 05:06:13 | 历史证据，**非当前产物** |
| 当前 `app-release.apk` | `70746C4C…5EFF9C`，393,718,763 B，12:15:48 | **未验证** |
| 当前设备 | `98a37aa7` 在线（product:piano, model:25091RP04C） | 仅 `adb devices -l` |
| 关键冲突 | C02c 正在改 `DshGatewayConfigSeeder.kt`（14:23:43），工作树 overlay 已含平台 patch 行；`DSH_COMPLETION_ACCEPTANCE_V1.md:7` 仍写“seeder 只生成三部分” | 文档漂移，见 §6 |

## 1. 采集命令与原始事实

以下命令均在 `E:\MAC` 或指定子目录**只读**执行；输出摘要见各节。所有数据来自命令，不引用旧报告数字。

```powershell
git -C E:\MAC rev-parse --abbrev-ref HEAD                  # codex/enterprise-refactor
git -C E:\MAC rev-parse HEAD                               # 68b6eef1e67870b02bfef6afda2e625d36663da9
git -C E:\MAC log -1 --format='%H%n%ci%n%s'                # 2026-10-20 12:25:10 +0800 / docs(audit): round-29 completion gaps and device captures
git -C E:\MAC status --porcelain=v1                        # 95 行
git -C E:\MAC status --porcelain=v1 -uall                  # 714 行（670 未跟踪文件）
git -C E:\MAC diff --shortstat                             # 44 files changed, 2494 insertions(+), 411 deletions(-)
git -C E:\MAC ls-tree -r --name-only HEAD | Select-String 'dsh-runtime|dsh-contract|dsh-gateway|DshGatewayConfigSeeder'  # 0 命中
git -C E:\MAC submodule status                             # 3 个子模块，见 §2.3
foreach ($sm in 'third_party/llama.cpp','third_party/stable-diffusion.cpp','third_party/tokenizers-cpp') { git -C (Join-Path E:\MAC $sm) status --porcelain=v1 }
Get-FileHash -Algorithm SHA256 E:\MAC\core\dsh-runtime\src\main\assets\dsh\payload.zip
Get-ChildItem E:\MAC\core\dsh-runtime\src\main\jniLibs\arm64-v8a -File | Get-FileHash -Algorithm SHA256
Get-FileHash -Algorithm SHA256 E:\MAC\app\build\outputs\apk\release\app-release.apk
Select-String -Path E:\MAC\gradle\libs.versions.toml -Pattern 'compileSdk|minSdk|targetSdk|agp|kotlin'
Select-String -Path E:\MAC\app\build.gradle.kts -Pattern 'compileSdk|minSdk|targetSdk|versionCode|versionName|sourceCompatibility'
Get-Content E:\MAC\local.properties
$env:JAVA_HOME; & "$env:JAVA_HOME\bin\java.exe" -version
D:\model\android-sdk\platform-tools\adb.exe devices -l
```

> 并发写入提示：采集期间其他 C 任务仍在写工作树（见 §6），因此 git 计数是**时点快照**，不是冻结基线。任何后续任务若要复现本文件，必须记录自己的采集时刻。

## 2. 源码身份

### 2.1 父仓库

| 项 | 值 |
| --- | --- |
| 仓库路径 | `E:\MAC` |
| 分支 | `codex/enterprise-refactor` |
| HEAD | `68b6eef1e67870b02bfef6afda2e625d36663da9` |
| HEAD 提交时间 | 2026-10-20 12:25:10 +0800 |
| HEAD 标题 | `docs(audit): round-29 completion gaps and device captures` |
| `headFinal` | **未冻结**（未收到冻结 commit；HEAD 单独不能当可复现声明） |

### 2.2 工作树（关键事实）

| 指标 | 实测值 | 命令 |
| --- | --- | --- |
| porcelain 条目（默认折叠） | **95** = 44 `M` + 51 `??` | `git status --porcelain=v1` |
| porcelain 条目（`-uall` 展开） | **714** = 44 `M` + 670 个未跟踪文件 | `git status --porcelain=v1 -uall` |
| tracked diff | 44 files changed, **+2494 / −411** | `git diff --shortstat` |
| 未跟踪顶层条目 | 51 个（含 `api/dsh-gateway/`、`core/dsh-contract/`、`core/dsh-runtime/`、`feature/dsh-workspace/`、`app/src/main/java/com/muyuchat/mca/dsh/`、`scripts/dsh/`、`docs/proposals/agent-dsh-embedded-v1/` 等） | `git status --porcelain=v1` |
| HEAD 树中的 DSH 源码 | **0 个文件**（`dsh-runtime` / `dsh-contract` / `dsh-gateway` / `DshGatewayConfigSeeder` 均无命中） | `git ls-tree -r --name-only HEAD` |

**含义（必须继承）**：嵌入式 DSH 的 Kotlin 模块、payload staging、方案文档目前**全部是未跟踪工作树内容**。任何“当前实现”的结论只能绑定 `HEAD + 本次工作树指纹`；只写 HEAD 会得到“根本不存在 DSH 模块”的错误复现。子模块内部未提交 diff 同样是构建输入（硬锁 §11）。

### 2.3 子模块

在各自目录内执行 `git -C <submodule> status --porcelain=v1`；计数为变更路径数（修改/删除/未跟踪合计）。

| 子模块 | revision | 内部 porcelain 变更数 | 内容摘要 |
| --- | --- | --- | --- |
| `third_party/llama.cpp` | `4ceb1719101f32637b841206c172f3f058ffc182` | **20** | 全部为 `M`：`ggml-*`、`llama-*`、`quants`、vulkan、quantize 工具等 |
| `third_party/stable-diffusion.cpp` | `16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0` | **18** | 14 `M` + 2 `D`（`examples/server/frontend`、`thirdparty/libwebm`、`thirdparty/libwebp`）+ 2 `??`（`src/qwen_image_2_1*.hpp`） |
| `third_party/tokenizers-cpp` | `acbdc5a27ae01ba74cda756f94da698d40f11dfe` | **5** | `CMakeLists.txt`、`tokenizers_c.h/.hpp`、`rust/src/lib.rs`、`src/huggingface_tokenizer.cc` |

`git submodule status` 输出（父仓视角无 `+`/`-` 前缀，但内部均有未提交变更）：

```text
 4ceb1719101f32637b841206c172f3f058ffc182 third_party/llama.cpp (v0.4.1-147-g4ceb17191)
 16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0 third_party/stable-diffusion.cpp (master-663-be65ac7-1-g16b3846)
 acbdc5a27ae01ba74cda756f94da698d40f11dfe third_party/tokenizers-cpp (v0.1.1-9-gacbdc5a)
```

## 3. Payload 与官方源身份

### 3.1 lock 与运行时 manifest

`scripts/dsh/payload-lock.json`（`generatedAtUtc = 2026-10-19T12:09:16.2574989Z`）实测字段：

| 字段 | 值 |
| --- | --- |
| `apkUrl` | `https://github.com/woaiys3/deepseek-harness-android-app/releases/download/v1.20.1/DeepSeekHarness.apk` |
| `apkSize` | 162,006,797 B |
| `apkSha256` | `c324272bff00e8cce06e0baa2ebb9b88e524a5d246e06f8cb55d39df7c66dafa` |
| `payloadSize` | 160,464,248 B |
| `payloadSha256` | `975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab` |
| `kernelVersion` | `0.2.1-rc.2` |
| `kernelRevision` | `20261018072502` |

`core/dsh-runtime/src/main/assets/dsh/MANIFEST.json`（运行时安装器使用，7,664 B）实测：

| 字段 | 值 |
| --- | --- |
| `manifestVersion` / `payloadVersion` | `1` / `dsh-0.2.1-rc.2+android-ref-c324272bff00` |
| `archive` | `dsh/payload.zip`，160,464,248 B，sha256 与 lock 一致 |
| `fileCount` | 31,021（与 `third_party/dsh/FILES.tsv` 的 31,021 行数据一致；zip 条目总数 36,541 = 31,021 文件 + 5,520 目录项） |
| `kernelEntry` | `dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js` |
| `source.officialCommit` | `687f282e8625c6b8d11ff58211a90524be2e4317`（官方包 `0.2.1-rc.2`） |
| `source.referenceVersion` | `v1.20.1`（`referenceCommit = null`） |

### 3.2 独立复算（本轮 Get-FileHash，不读 lock）

```text
payload.zip size=160464248 sha256=975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab   ✅ 与 lock/MANIFEST 一致
```

7 个宿主 ELF 的 `jniLibs` 副本同样逐字节复算，全部与 `payload-lock.json` 的 `executables[]` 一致：

| role | payload entry | jniLibs 副本 | size (B) | sha256 |
| --- | --- | --- | --- | --- |
| node | `runtime/bin/node` | `libdshnode.so` | 49,715,528 | `a0d21a1589312919114b24a5cc520c30ab7f26f9985b3f0c643c153172ebd1e6` |
| ripgrep | `runtime/bin/rg` | `libdshrg.so` | 4,868,920 | `5adfd20e4c350aecd718bbef048a57a3fbabe0402c9c39a25eb40a28f1b19543` |
| curl | `runtime/bin/curl` | `libdshcurl.so` | 308,272 | `e9cd08920d448bb6aa448c87c5b84dc62e55b5ed654664684d79348a1b06de61` |
| git | `bin/git` | `libdshgit.so` | 3,562,128 | `aba46d67c0c5752c27129d97136055bd8d7f3b3c4378edbf5c7c5a6f8dead5db` |
| git-remote-http | `git/libexec/git-core/git-remote-http` | `libdshgitremotehttp.so` | 2,010,824 | `909e5616f9cb422bc6a90517e34ac40c2a8ec0475949908a3596b006dd96db61` |
| git-remote-https | `git/libexec/git-core/git-remote-https` | `libdshgitremotehttps.so` | 2,010,824 | `909e5616f9cb422bc6a90517e34ac40c2a8ec0475949908a3596b006dd96db61` |
| python3.14 | `python/bin/python3.14` | `libdshpython.so` | 4,448 | `4f8d78ede7fc26e96e99342dd83ce42d32c9b4d0806bc9a99ff7151396269b25` |

### 3.3 “无原生模块”事实（本轮实测）

- payload zip 内 `.node` 条目数：**0**（36,541 条目扫描）。
- `node-addon-system` 的 `optionalDependencies` 只有 darwin-arm64/x64、linux-x64/arm64（`node-addon-system/package.json:36-40`）——**没有 Android 平台包**。
- `node-pty` 在 zip 内条目数：**0**；尽管 `dsh-subprocess-local/package.json:52` 仍声明 `node-pty: 1.2.0-beta.15`。
- `sharp` 在 zip 内无 `build/Release`、无 `.node`；`dsh-attachment-local/package.json:39` 仍声明 `sharp: 0.35.5`。
- 原生构建工具链存在（NDK `29.0.13113456`、`29.0.14206865`），但**本轮未构建**；存在 NDK ≠ 已交付 addon。

### 3.4 payload 里 Android 适配补丁的来源

`scripts/dsh/stage.ps1` 只做 5 件事：从 reference APK 解出 `assets/payload.zip` → 校验 lock → 复制 ELF 到 jniLibs → 写运行时 `MANIFEST.json` → 写 `third_party/dsh/MANIFEST.json` / `FILES.tsv`；**脚本内没有任何对 payload 内 JS 的 patch 步骤**（`Select-String -Pattern 'patch|Replace\(|Set-Content|WriteAllText'` 只命中 manifest/lock 写入）。

因此 `flock.js`、`dsh-bash-local`、`dsh-subprocess-local` 等处的“Android 适配”注释是 **pinned reference payload（woaiys3 v1.20.1）自带的补丁**，MCA 只是按 hash 原样入库。补丁账本的 `owner` 因此写“payload/reference 补丁 + 后续 C 卡收口”，不能写成 MCA 已实现。

## 4. Android / 宿主工具链（本轮实测）

| 项 | 值 | 来源 |
| --- | --- | --- |
| AGP | `8.13.2` | `gradle/libs.versions.toml:2` |
| Kotlin | `2.3.21` | `gradle/libs.versions.toml:3` |
| compileSdk | **36** | `gradle/libs.versions.toml:5` |
| minSdk | **26** | `gradle/libs.versions.toml:6` |
| targetSdk | **36** | `gradle/libs.versions.toml:7` |
| versionCode / versionName | `8` / `0.2.4` | `app/build.gradle.kts:213-214` |
| Java source/target | `JavaVersion.VERSION_17` | `app/build.gradle.kts:377-378` |
| Gradle wrapper | `8.14.3` | `gradle/wrapper/gradle-wrapper.properties` |
| `JAVA_HOME` | `E:\model\toolchains\jdk-21\jdk-21.0.12.1+1`（Temurin `21.0.12.1+1`，实测 `java -version` 21.0.12.1 LTS） | 环境变量 + `$env:JAVA_HOME\bin\java.exe -version` |
| PATH 上的 `java` | `openjdk version "17.0.10"`（Microsoft build） | `java -version` |
| SDK | `sdk.dir=D:\model\android-sdk` | `local.properties` |
| SDK platforms | `android-35`、`android-36` | `Get-ChildItem D:\model\android-sdk\platforms` |
| build-tools | `34.0.0`、`35.0.0`、`36.0.0` | `Get-ChildItem D:\model\android-sdk\build-tools` |
| NDK | `29.0.13113456`、`29.0.14206865` | `Get-ChildItem D:\model\android-sdk\ndk` |

**工具链注意（必须继承）**：

- 硬锁 §11 要求当前完整验证使用 **JDK 21**；`JAVA_HOME` 正确指向 21，但 PATH 上的 `java` 是 **17.0.10**。谁在 shell 里直接调用 `java`/`gradle` 而不使用 `JAVA_HOME`，就可能得到“源码 target 17 所以 JDK 17 也行”的错误结论。
- 本轮**没有运行 Gradle**：构建锁由 Lead 持有（任务明令）。因此 compileSdk/targetSdk 是**配置事实**，不是“本次构建成功”的证据。

## 5. 05:12 真机证据身份（历史 PASS，不结转）

证据根：`artifacts/dsh-verify/20261020-051217/`（`FINAL-RELEASE-VERIFY.md` + `verdict.json` + `identity/`、`install/`、`logs/`、`dumps/`、`shots/`）。

| 项 | 值 |
| --- | --- |
| 运行窗口 | 2026-10-20 05:12–05:18 +0800 |
| 设备 | **98a37aa7**（`product:piano`, `model:25091RP04C`） |
| APK 路径 | `app/build/outputs/apk/release/app-release.apk` |
| APK sha256 | **`b610e1d0d29195e966e705a4a5113f46d8601a16349f45bedb98954ace73ae9a`** |
| APK size / mtime | 393,717,991 B / 2026-10-20 05:06:13 |
| 包身份 | `com.muyuchat.mca`, versionName `0.2.4`, versionCode `8`, minSdk `26`, targetSdk `36` |
| signer sha256 | `2619ac4ce0ad8397b84c77df6ba165801fd4fab1460470f22f1eb7b3e4f9a9cf` |
| 载荷 | `assets/dsh/payload.zip`（160,464,248 B）+ `assets/dsh/MANIFEST.json` + 7 个 `lib/arm64-v8a/libdsh*.so` |

结论边界（来自原始 `verdict.json`，不得扩写）：

- **基础链路 PASS**：APK identity / 非陈旧（dex 含 stop-fence 字符串、R8 mapping 含 `DshStopOutcome.getTrackedPids()` / `getSurvivingPids()`）/ 安装且 `firstInstallTime` 不变、数据保留 / 冷启动 200 ms / 本地模型 n_ctx=16384 加载成功 / DSH READY、单 Node（PID 10566）/ picker label 干净且云行存在 / 真 adb 点击切换 chip / 本地回合完成（`1分18秒`、`6.6K tok`，**输出离题=MODEL_QUALITY_ONLY**）/ E5 离开→聊天→返回同 PID 且会话恢复 / 无 crash/ANR。
- **不覆盖**：真实工具调用、插件、记忆/压缩、workflow/PTC、skills、真 PTY、Android 能力桥、动态目录热更新、升级/回滚、故障注入、云回合（`NOT_RETESTED`，已知上游 503）。这些在 C00 一律 `NOT_RUN`。
- **历史结论**：`PASS … Supersedes 0A731D84 / E0B97FB3 / 2005DF02` —— 该 PASS 只对应 APK `b610e1d0…`。

### 5.1 当前 `adb devices` 实测（本轮唯一设备命令）

```text
List of devices attached
98a37aa7               device product:piano model:25091RP04C device:piano transport_id:2
```

- `adb` 不在 PATH；使用 `D:\model\android-sdk\platform-tools\adb.exe`。
- 本轮**未**执行 `adb shell` / getprop / install / logcat 等任何会改变或深入读取设备的操作，因此设备 OS 版本、Build、当前前台页面均**未在本轮验证**；不要引用草稿中的“Android 17 / SDK 37”作为本轮实测。

### 5.2 当前 `app-release.apk` 与已验证 APK 不同

| 项 | 05:12 已验证 | 当前产物 |
| --- | --- | --- |
| sha256 | `b610e1d0…73ae9a` | **`70746C4CA39AB40E5A500BB299DD4BB7590E7E7854AE33BD6BD2EDEFDD5EFF9C`** |
| size | 393,717,991 B | 393,718,763 B（+772 B） |
| mtime | 2026-10-20 05:06:13 | 2026-10-20 12:15:48 |

**判定：`NOT_RUN`。** 当前 APK 比已验证 APK 晚约 7 小时且 hash 不同；05:12 的 PASS 不能复制给当前产物。C00 不构建、不安装，故当前 APK 只能记为未验证候选。

## 6. 快照中正在变化的生产输入（并发工作）

采集时其他 C 任务正在写工作树；以下为直接影响本基线结论的实测快照：

| 文件 | size | mtime | sha256（快照） | 说明 |
| --- | --- | --- | --- | --- |
| `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt` | 31,374 | 2026-10-20 14:23:43 | `52f114cba16a3dbabdc4a4fd6261b677ec25cdf2afb258877863f071cc6d4897` | C02c 正在合并平台 patch 行 |
| `core/dsh-runtime/src/test/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeederTest.kt` | 28,729 | 2026-10-20 14:26:01 | `9191e3837efd2ca9f70b0b34bc4ddb98bc7bf5699f2885747890b5a49565beb2` | C02c 测试（含 `currentHomeReseedRewritesTheOverlayWithPlatformPatchRows`） |
| `dshhome/cordis.patch.yml`（payload 内） | 4,686 | — | `807fc6bb9e8da1675ac20502eac88fb7654eff8cacce6b9499fc465ef14bdb72` | 平台 patch 模板 v4 |

**已实测的 overlay 现状对比**（源码级，非设备）：

- payload 模板 `dshhome/cordis.patch.yml`：4 条 `disabled`（`bash-sandbox`、`pwsh-sandbox`、`session-log-download`、`session-telemetry-otel`，行 25–39）+ `permission`（行 46–58，**模板默认 `danger-full-access` + `approval: never`**）+ `insert` 块（`bash-local` + `tool-shizuku` + `tool-android` + `tool-accessibility` + `tool-vscreen` + `tool-browser`，行 59–79）。
- MCA seeder 当前工作树 `buildOverlay()`（行 567–631）：**同一组 4 条 disabled**（578–585）+ `permission`（586–598，`defaultPreset: workspace-write`、全部 `approval: ask`）+ `workspace-controller`（603–605）+ `llm-pi-ai`（606–621）+ `insert bash-local`（625–630，`cwd` 为 MCA 工作区字面路径、`timeoutMs: 60000`）；**刻意不复制**模板的 `danger-full-access` / `approval: never` / 5 个参考 Android 工具插件（由 C11 capability bridge 替代，注释 571–577）。
- 结论：**“平台 patch 未进入 MCA overlay（seeder 只生成三条）”是 12:36 之前的状态；当前工作树已补上必需平台行（C02c，未提交、未构建、未设备验证）。** `DSH_COMPLETION_ACCEPTANCE_V1.md:7` 与旧草稿仍写旧状态，属于**文档漂移**，C02c 合入/验证后必须更新；C00 不修改该文档。

## 7. 本目录上一版草稿与本次重写

采集时本目录已有 12:45 前后写入的 3 个未跟踪草稿（`BASELINE.md` / `PATCH_LEDGER.json` / `PARITY_MATRIX.json`）。本次按任务卡重写并补齐 `README.md`、`EVIDENCE_FORMAT.md`，原因：

- 旧草稿记 89 条 porcelain（44 `M` + 45 `??`）、640 个未跟踪文件；当前实测为 95 条（44 `M` + 51 `??`）、670 个未跟踪文件——期间其他任务新增了未跟踪产物。
- 旧草稿的 `PARITY_MATRIX.json` 预填了 10 条 provisional row，但其 evidence 行号与 payload 实测不符（例：PTY 行号写 884–935/1035–1055/1155–1180，实测为 943–1176），不满足“row identity 来自 `CAPABILITY_MANIFEST.json`、由 C14 填行”的要求，已按任务卡收缩为种子（`rows: []`），原因记入 `README.md`。
- 旧草稿未覆盖 `EVIDENCE_FORMAT.md`，且未记录 C02c 并发写入与当前 APK 未验证边界。

## 8. C00 边界与未运行项

**本轮只读分析，不代表生产验证。** 未运行（全部 `NOT_RUN`，不是失败）：

1. Gradle 编译、单元测试、Robolectric/设备测试（构建锁由 Lead 持有）。
2. APK 构建、签名、安装、升级、回滚。
3. 任何设备操作：`adb shell`、getprop、install、am start、input、logcat、截图、UI dump。
4. DSH 运行时启动、READY、gateway/catalog 行为、单 Node、停止/恢复探针。
5. 模型加载、真实回合、工具调用、插件、记忆/压缩、workflow/PTC、skills、PTY、Android 能力桥、动态目录、升级事务。
6. payload 内 JS 补丁的运行时探针（flock 竞争、fs-local 竞态、PTY resize、bash 沙箱 fail-closed、sharp 编解码、node-pty 缺失负例）。
7. `CAPABILITY_MANIFEST.json` 的 422 行未逐行复核；C00 只读取其 schema/row identity（见 `README.md`），不复制、不改写。

因此本文件的所有 `PASS` 类事实只有两类：(a) 命令可复算的静态身份（hash/size/git/SDK 配置）；(b) 明确标注来源与 APK 的历史 05:12 设备证据。除此之外不宣称任何生产通过。

## 9. 本文件引用的配套产物

- `PATCH_LEDGER.json`：9 条已知 payload 语义弱化/依赖替换，状态仅 `NOT_RUN/DIAGNOSED/BLOCKED`。
- `PARITY_MATRIX.json`：C14 用的行级矩阵**种子**（`rows: []`）。
- `README.md`：row identity 约定、C14 填行规则、快照复现命令。
- `EVIDENCE_FORMAT.md`：`run.json` 最小字段与五状态词约定。

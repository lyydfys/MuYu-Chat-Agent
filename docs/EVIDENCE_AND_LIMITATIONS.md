# 证据分层与已知限制

本文件回答三个维护者最容易误判的问题：**某条结论靠什么支撑**、**哪些变更不能回退**、
**已知的验证缺口有多大**。它只写可复核的内容，并明确标注每一条的依据类型。

依据类型一律用下列四档之一，不使用含义模糊的措辞：

| 标记 | 含义 |
| --- | --- |
| **源码** | 可由仓库内源码直接读出；给出符号名而非行号，行号会随切片移动 |
| **JVM 测试** | 由 `testDebugUnitTest` 覆盖，可用 `gradlew testDebugUnitTest` 复现 |
| **真机** | 在物理设备上观测到的结果；指名设备序列号与 APK 哈希 |
| **未验证** | 尚无上述任何一类证据；不得据此宣称通过 |

## 1. 数据库与降级限制

### 1.1 实际限制来源（不是笼统的"禁止降级"）

`McaRoomDatabase` 的迁移链是**严格单向**的：`ChatSessionStore.kt` 的 `companion object` 中
所有 `MIGRATION_x_y` 都满足 `y > x`，从 `MIGRATION_1_2` 一直串到 `MIGRATION_26_27`。
**依据：源码。**

版本不符时的行为由 `Room.databaseBuilder(...)` 的配置决定：该构建链**没有**调用
`fallbackToDestructiveMigration` 或 `fallbackToDestructiveMigrationOnDowngrade`。
**依据：源码**（`git grep 'fallbackToDestructiveMigration'` 无命中）。

由此得到的限制：

- **升级**：有完整迁移链，可用。**依据：源码**，并由
  `McaRoomMigrationExecutionTest` 在真实 SQLite 上执行其中五个迁移。**依据：JVM 测试。**
- **降级到期望更早 schema 版本的 APK**：Room 在打开数据库时校验版本，遇到比自身期望更高的
  版本且没有破坏性回退时不会继续。**依据：源码推论。**
  **尚未在真机上实测降级失败**，因此这条按"源码推论、未真机实测"对待，
  不要写成"旧 APK 一定无法打开数据库"这种确定事实。

### 1.2 为什么保留"不清库"的选择

不使用破坏性回退是刻意的：破坏性回退会在版本不符时**清空用户数据**，
而数据保护优先于"能启动"。代价就是上面那条降级限制。

### 1.3 恢复路径（按优先级）

1. **前向修复**：安装同版本或更高版本的 APK。这是唯一不依赖额外材料的方式。
2. **同签名重装**：`adb install -r` 保留 `data` 分区；**依据：真机**，
   在两台设备上共观测到四次同签名升级后 `firstInstallTime` 不变、偏好文件计数不变。
3. **备份恢复**：图像库备份走 `ImageLibraryBackupManifestTest` 覆盖的格式。
   **依据：JVM 测试。** 该路径**不覆盖**聊天数据库，聊天会话没有对应的导出恢复流程。

### 1.4 其他不可回退的变更

- **签名**：APK 必须保持同一签名密钥，否则只能卸载重装并丢失数据。**依据：真机**（四次升级均使用同一 release 密钥库）。
- **native 资源版本**：`LiteRtQualcommRuntimeStager` 按变体（V73/V75/V79/V81）分别固定
  SHA-256，且 `COMMON_FILES` 与 `HTP_FILES_BY_VARIANT` 共同决定所需文件集。
  **依据：源码。** 变体选择错误会导致真实 native 加载失败，因此不能只替换部分库文件。

## 2. QAIRT V68/V69：缺口**已关闭**（保留本节以说明如何复现完整的构建）

**⚠️ 状态变更：** 本节原先记录“本机 SDK 并集不含 HTP V68/V69”，并据此把“老代次验证覆盖不完整”
列为需要披露的残留风险。**该缺口此后已关闭**，因此下面保留的是**结论与复现方法**，而不是限制。

**关闭方式：** 完整运行时根**一直就在本机**，只是从未作为构建输入传入：

```
<仓库内既有构建产物>/qnn-runtime-sdk/qairt/2.45.0.260326/
  lib/hexagon-v66, v68, v69, v73, v75, v79, v81     # 全部七代 HTP
  lib/aarch64-android                                # 主机侧 Stub / CalculatorStub
```

**依据：源码/环境**。该根对 `scripts/build.ps1` 所要求的 17 个运行时名**齐全度 17/17**。

**复现完整的调试 APK：**

```powershell
$env:MCA_QNN_SDK_ROOT = "<上述运行时根的副本>"
pwsh -NoProfile -File scripts/build.ps1
```

**该配方会自行校验并拒绝不完整的 SDK**，因此“构建成功”本身就是完整性证据。最后一次运行的
`artifacts/apk-build-records/*.json` 记录 `status = passed`、`missingEntries = 0`、
`validationErrors = 0`、`expectedEntries = 71` 与 `presentEntries = 71`。

**仍成立的两条：**

- **不要为任何代次引入芯片、机型或认证白名单。** 未知或未列出的设备继续走通用兼容路径，
  由**真实的 native 加载与执行结果**决定成败。**依据：源码**，并由若干**可发布**测试固定
  （禁止门列表、API 输出不得含 `allowlist`/`whitelist`/`chipset`、未知设备内存必须 `canAttempt`）。
- **构建完整性属“必需二进制是否齐备”，与设备准入无关** —— 补齐 SDK 根既没有放宽也没有收紧
  任何设备的可用性。

**⚠️ 一处仍未验证的外推：** 上述完整性只在本机、本 revision、本 SDK 根上被证明。
不同代次的设备是否都能真实执行，仍要由该设备上的 native 加载与执行结果决定。

## 3. 证据分层：当前哪些结论靠什么支撑

| 结论 | 依据 | 备注 |
| --- | --- | --- |
| 结构拆分与基线等值 | **源码** | 目标文件正文行与基线行多重集比对：13,562 行逐字节相同，差异 70 行全部对应已声明的可见性放宽与 P7 精修 |
| CI 三条门禁（编译 / lint / 单测） | **JVM 测试** | 本机以工作流原命令复现；lint 为 0 错误 |
| 运行时所有权契约 | **源码 + JVM 测试** | `LocalImageGenerationOwnershipContractTest` 等九个具名测试 |
| 迁移与 schema | **JVM 测试** | 五个迁移在真实 SQLite 上执行；schema JSON 已提交 |
| 密钥失败不落明文 | **JVM 测试** | `CredentialStoragePolicyTest` 覆盖六种失败模式 |
| 模块无循环依赖 | **源码** | 依赖图 46 条边、零环路，并以注入反向边做过正控制 |
| runtime 资产完整性 | **源码** | 24/24 资产有 SHA-256 固定值，拷贝时校验 |
| **完整（94 / 26 库）生产构建** | **源码/环境** | `scripts/build.ps1` 以完整运行时根运行至 `status = passed`，`expectedEntries 71/71`、`missingEntries 0` |
| **同一输入下的产物可复现** | **源码/环境** | 同一 revision 与同一 SDK 根上两次独立运行产出**相同的 APK SHA-256**（`155A51AA…`） |
| 同签名升级保留数据 | **真机** | 两台设备共四次 |
| 真实 native 推理与认证 Local API | **真机** | `MCA_NATIVE_OK`，带密钥 200 / 去密钥 401 |
| 深色主题气泡精修 | **真机** | 四轮渲染 + 浅色回归 |
| 性能前后对照 | **未验证** | 无历史基线；仅有 after 数值。两份同签名、同库集合的 APK 已备好，等待真机安装 |
| 真机清单中的云端与视觉链路 | **未验证** | 尚未执行 |
| 仪器化 UI 测试的**执行** | **未验证** | 测试源可编译并产出测试 APK；设备侧安装需手动完成 |
| 降级到旧 APK 的实际行为 | **未验证** | 仅有源码推论 |
| **产物可复现性的外推** | **未验证** | 仅证明同一机器、同一 revision、同一 SDK 根；不同 JDK/NDK、文件系统、时区与并行调度未验证 |

## 4. 当前环境说明（含绝对路径）

面向长期维护的正文使用仓库相对路径；需要**精确复现**的环境事实集中在这里，
换机器后只需改这一节。

- 独立重构环境：`E:\MAC`（分支 `codex/enterprise-refactor`）
- 受保护的原版参照：`E:\model\MAC`（本任务全程未改动）
- JDK：`E:\model\toolchains\jdk-21\jdk-21.0.12.1+1`
- **完整的 QAIRT 运行时根（构建生产 APK 用这个）：`E:\tools\qairt-sdk\2.45.0.260326`**
  - 它是从原仓库既有的展开产物复制出来的**仓库外副本**，七代 HTP 与 `aarch64-android` 主机库齐全
  - 副本核对：文件数 9,658 = 9,658，总字节 3,856.8 MB = 3,856.8 MB，关键库 SHA-256 逐一致
  - **不要**把 SDK 根放在盘根下：构建配方会在盘的根上调用 `Split-Path -Parent`，浅层布局会失败
- 仅有头文件的 QAIRT 安装（**不足以**构建完整 APK）：`C:\Users\sy427\qairt-sdk\2.46.0.260424`
- MNN Android 构建根：`E:\MAC\third_party\MNN\project\android\build_64_mca_full`
- 执行记录与证据：`E:\MAC_REFACTOR_STATUS.md`、`E:\MAC_REFACTOR_EVIDENCE\`
- 手动安装用交付物：`E:\MCA_INSTALL\`（基线 / 候选 / 仪器化测试 APK）

## 5. 维护责任

| 职责 | 责任主体 |
| --- | --- |
| 主维护人 | 项目持有人 / 仓库 Owner |
| 备用维护人 | 待指定 |
| 发布签名与 APK 交付 | 项目持有人 |
| CI runner | 项目持有人 |
| QAIRT / MNN / native SDK | 项目持有人 |
| 真机测试与性能基线 | 项目持有人 |
| 安全问题和依赖升级 | 项目持有人 |

## 6. 本文件不覆盖的内容

本文件描述**限制与证据分层**，不重复功能说明。功能范围见 `IMPLEMENTATION_STATUS.md`
与 `README.md`；构建与发布流程见 `docs/BUILD.md` 与 `docs/RELEASE.md`；
真机验收清单见 `docs/MCA_REAL_DEVICE_REGRESSION.md`。

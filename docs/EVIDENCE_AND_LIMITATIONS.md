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

## 2. QAIRT V68/V69 缺口的影响范围

**事实：** 本机可用的 QAIRT SDK 2.46.0 与该仓库的 `vendor/litert/qualcomm/runtime-assets/`
**并集**仍不含 HTP **V68 与 V69** 的运行时二进制。
**依据：源码/环境**（直接检查 SDK 归档与 vendor 目录，见 `E:\MAC_REFACTOR_EVIDENCE\` 下的
`P8-06` 与 `P9-24`）。

**影响范围（不要外推）：**

- 受影响的只有 **HTP V68 / V69 代次的设备**。**依据：源码**
  （`HTP_FILES_BY_VARIANT` 按代次取文件，缺失代次即缺文件）。
- **不受影响的**：V73 / V75 / V79 / V81 代次在 vendor 树中**均有** `Skel` + `Stub`，
  共 24 个资产全部有 64 位十六进制 SHA-256 固定值。**依据：源码。**
- 本任务使用的两台设备分别落在 **V79（SM8550）** 与 **V81（SM8750P）**，
  均在被覆盖的代次内；真机上的 native 推理与认证 API 调用因此**未受该缺口影响**。
  **依据：真机。**

**明确不做的事：** 不为补齐老代次而引入芯片、机型或认证白名单。
未知或未列出的设备继续走通用兼容路径，由**真实的 native 加载与执行结果**决定成败。
**依据：源码**，并由若干**可发布**测试固定（禁止门列表、API 输出不得含
`allowlist`/`whitelist`/`chipset`、未知设备内存必须 `canAttempt`）。

**发布说明应当写：** “缺少对应 SDK 导致**老代次验证覆盖不完整**”，
而不是“老代次设备不可用”。

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
| 同签名升级保留数据 | **真机** | 两台设备共四次 |
| 真实 native 推理与认证 Local API | **真机** | `MCA_NATIVE_OK`，带密钥 200 / 去密钥 401 |
| 深色主题气泡精修 | **真机** | 四轮渲染 + 浅色回归 |
| 性能前后对照 | **未验证** | 无历史基线；仅有 after 数值 |
| 真机清单中的云端与视觉链路 | **未验证** | 尚未执行 |
| 仪器化 UI 测试的**执行** | **未验证** | 测试源可编译并产出测试 APK，但当前设备无法安装 |
| 降级到旧 APK 的实际行为 | **未验证** | 仅有源码推论 |

## 4. 当前环境说明（含绝对路径）

面向长期维护的正文使用仓库相对路径；需要**精确复现**的环境事实集中在这里，
换机器后只需改这一节。

- 独立重构环境：`E:\MAC`（分支 `codex/enterprise-refactor`）
- 受保护的原版参照：`E:\model\MAC`（本任务全程未改动）
- JDK：`E:\model\toolchains\jdk-21\jdk-21.0.12.1+1`
- QAIRT SDK 根：`C:\Users\sy427\qairt-sdk\2.46.0.260424`
- MNN Android 构建根：`E:\MAC\third_party\MNN\project\android\build_64_mca_full`
- 执行记录与证据：`E:\MAC_REFACTOR_STATUS.md`、`E:\MAC_REFACTOR_EVIDENCE\`

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

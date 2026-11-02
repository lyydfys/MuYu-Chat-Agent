# MCA Agent V1 — 交接与使用说明（2026-10-19）

> 本文是 K17 交接材料。它区分 **code-layer 已验证** 与 **设备/模型/发布尚未验证**；不把编译、
> JVM 测试或“实现存在”写成发布 PASS。

## 1. 当前证据层级

| 层 | 状态 | 证据 |
| --- | --- | --- |
| 确定性 JVM/架构 | **PASS** | `verify-code -AgentTests -Rerun` → 3245 tests / 0 failures / 0 errors / 11 skipped，`VERIFY OK`；`check_architecture_rules.py` 14/14 |
| 编译/组装 | **PASS** | `:app:compileDebugKotlin`、`:app:assembleDebug`、`:feature:agent-workspace:compileDebugAndroidTestKotlin` |
| Room 27→28 | 编译/KSP **PASS** | `28.json`（26 entities）、`RoomSchemaContractTest` 7/0；真实设备迁移 **NOT_RUN** |
| 设备 instrumentation | **BLOCKED** | MIUI `INSTALL_FAILED_USER_RESTRICTED`（`INSTALL_FAILED_USER_RESTRICTED`），详见 `verification-2026-10-19.md` |
| 真实 MNN/GGUF/云 | **NOT_RUN** | 无模型/云凭据/设备执行证据 |
| FGS/通知/TalkBack/视觉/性能 | **NOT_RUN** | 设备阻塞 |
| MCP 真实连接 | **代码接线完成，真实 server NOT_RUN** | `RunScopedAgentToolCatalogProvider` 在联网 run 内为 enabled+allowAgentNetwork server mint run-scoped grant、连接 registry、装配 catalog；真实 server/设备证据仍缺 |
| 验收矩阵 | 58 PASS / 63 NOT_RUN / 7 BLOCKED | `acceptance-status.md`，128 IDs 全覆盖 |

> **历史证据说明（2026-10-20 01:10 增补，不改写上表原始数字）**：上表第 1 行
> “3245 tests / 14/14”是 2026-10-19 K15 的历史门禁（`5e912ff9` 时代），不是当前树结果。
> 当前：仓库 HEAD `af24899`（交付基线 `7475fd6`）；架构 **16/16**（2026-10-20 01:05，含
> DSH Rules 15/16）；contract **19**、runtime **132**、workspace **118** 是 00:17 的**分模块独立运行**，不是一次性汇总；
> app 全量未重跑（历史 3079/11 skipped @22:30 已过期，最新 app 记录是 00:30 过滤 DSH
> 26/1 failed）；full gate **NOT_RUN/BLOCKED**（活动构建锁 + DSH 测试缺口）。详见
> `../agent-chat-integration-v1/V1_1_STATUS.md`。

## 2. 用户如何使用

1. 侧栏“图片”下方的 **Agent** 入口进入独立工作区；原“智能调参”页保持不变。首页按 DSH 模型复刻：品牌 mark + “探索未至之境” + “预览版”、工作区/网络选择器、单一输入卡（+ / 附件 / 权限 / 模型 / 发送）；任务历史、Skills/MCP、导入导出都在左侧菜单。
2. 首页输入目标，选模型（本地；云模型在 egress 客户端完成前不显示），点发送。
3. 详情页为“对话 / 轨迹”双 tab：目标气泡、assistant 状态/活动、已提交产物、内联审批/问题；底部浮动输入卡负责回答/继续/停止，审批仍用模态单（默认焦点不在“允许”）。
4. 运行中可停止；停止只取消该 run 的精确 generation，不会影响聊天/图片任务。
5. 首页/详情 overflow：
   - **导入任务包**：选择 `.mca-task.zip`，校验后导入为 `IMPORTED_ARCHIVED`（不自动运行）。
   - **导出任务包**：选择保存位置，导出确定性 ZIP（只含任务显示摘要和已提交产物，不含目标原文、授权、凭据）。
   - **删除任务**：先精确取消活动 run，再 TOMBSTONED 并清理文件；运行中的任务会提示先停止。
   - **管理 Skills**：ZIP/目录导入、启停（最多 4）、卸载；运行中的任务保留其快照。
   - **管理 MCP**：添加 HTTPS endpoint（本地服务器需显式 toggle）、启停、删除；**当前不建立连接**，配置只是注册表偏好。
6. 后台运行：Agent 运行中会复用现有 `McaGenerationForegroundService`（通用“Agent 任务在后台继续运行”），通知可停止该 run；FGS 启动失败会以 `FOREGROUND_SERVICE_UNAVAILABLE` 暂停，不再静默继续。

## 3. 开发者如何处理设备阻塞

MIUI 需要在 **开发者选项 → USB 安装** 允许 adb 安装。打开后：

```powershell
$adb = 'D:\model\android-sdk\platform-tools\adb.exe'
# 构建隔离变体（不覆盖已签名的正式包，不卸载用户数据）
.\scripts\agent-verify.ps1 -Tasks ':app:assembleDeviceTest',':app:assembleDeviceTestAndroidTest' `
  -GradleArgs '-PmcaDeviceTestVariant=true' -TimeoutMinutes 60
& $adb install -r -d app\build\outputs\apk\deviceTest\app-deviceTest.apk
& $adb install -r -d app\build\outputs\apk\androidTest\deviceTest\app-deviceTest-androidTest.apk
& $adb shell am instrument -w -e class `
  com.muyuchat.mca.agent.data.AgentRoomMigrationTest,com.muyuchat.mca.agent.data.AgentRoomJournalTest,com.muyuchat.mca.agent.data.export.AgentRoomPackagePersistenceTest `
  com.muyuchat.mca.devicetest.test/androidx.test.runner.AndroidJUnitRunner
```

执行后把 `adb logcat` 与 instrumentation XML 归档到 `evidence/`；真实模型/云需要另外的模型资产与凭据，不能由单元测试替代。

## 4. 已知限制 / 未接线

1. **MCP 真实连接**：`RunScopedAgentToolCatalogProvider` 已实现 run-scoped grant→connect→catalog→release；真实受控 server、设备网络与 UI 审批全链路仍 NOT_RUN。启用 server 并在 MCP 管理页打开“允许 Agent 联网任务访问”，再把任务网络模式设为“联网”，才会创建本次 run 的 grant；所有 MCP 工具调用仍是 EXTERNAL_SIDE_EFFECT/ASK，需逐次审批。
2. **MCP schema-drift**：registry 的 generation fence 尚未在工具 schema 变化时联动 broker 审批失效（X09）。
3. **云模型**：`AgentCloudModelAdapter` 有严格 terminal/SSE，但 egress client 未接，云选项隐藏。
4. **native stop proof**：K04 只能证明 coroutine 静止；engine/worker 的 native quiescence API 未补。
5. **真实数据库迁移/journal/SAF/FGS/Compose**：设备阻塞，全部 NOT_RUN。
6. **导入任务可见性**：已实现 Home“显示已导入归档”开关（Saveable，默认关）；归档任务只读，不能启动/继续/停止，但可导出/删除。
7. **egress grant 已接 run**：`RoomAgentEgressGrantStore` 复用 `agent_grants`；provider 对每个联网 run 自动创建 run-scoped grant（PRIVATE、runId/taskId/policyVersion），grant 拒绝/连接失败/装配冲突都回退 base catalog。默认 OkHttp client 为进程共享，release 关闭 MCP session 而非底层 client。
8. **导入产物无 `agent_artifact_commits` 行**：v28 `invocationId` NOT NULL，导入无 invocation；如审计强制要求，需 v29 迁移。
9. **升级/回退**：不要用旧 APK 清库。关闭 Agent 入口/停止新 run 是安全退路；Agent 表与 `files/agent` 可保留/导出。若需回退代码，只回退 Agent 入口与 runtime 组合，不要 downgrade Room 或删 `files/agent`。
10. **DSH 前端证据边界**：Home/Detail 已按 DSH 截图实现并有 JVM/Compose 编译证据，但尚无真机两轮渲染、暗色对比、TalkBack 与 IME 实测；视觉验收仍 NOT_RUN。

## 5. 验证命令

```powershell
# 全量 code-layer 门禁（串行 build lock）
.\scripts\verify-code.ps1 -AgentTests -Rerun

# 架构
python tools\architecture\check_architecture_rules.py

# 单模块
.\scripts\agent-verify.ps1 -Tasks ':core:agent-contract:testDebugUnitTest',':core:agent-runtime:testDebugUnitTest'
.\scripts\agent-verify.ps1 -Tasks ':feature:agent-workspace:testDebugUnitTest'
.\scripts\agent-verify.ps1 -Tasks ':app:testDebugUnitTest' -Tests 'com.muyuchat.mca.agent.*'
```

## 6. 结论

**V1 code-layer 已形成可维护的完整闭环（契约、runtime、Room、安全、工具、UI、Skills/MCP 管理、导入导出、FGS/恢复），但尚未 release-ready。** 设备、真实模型/云、MCP 真实连接、视觉/无障碍与性能证据仍是 G1–G3 的硬缺口；在这些证据补齐前，不得宣称 V1 完成或“全面超越”任何基线。

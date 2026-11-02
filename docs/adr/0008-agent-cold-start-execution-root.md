# ADR 0008：Agent 冷启动执行根迁移

- 状态：Proposed — 进程级 holder 已落地（2026-10-25）；页面/后台/冷启动设备证据仍 NOT_RUN
- 日期：2026-10-20（实现增补 2026-10-25）
- 关联：README §5、DESIGN §7.6、EXECUTION_PACK N21、PARITY_ACCEPTANCE WF-23/26、REG-05、OFF-06
- 兼容：聊天、图片、Local API、停止与模型资产行为必须连续

## 背景

当前 Agent Job 是传入 ViewModel scope 的子 Job，模型/共享资源根由既有 AppStartupViewModel/MainViewModel 创建链持有（B09）。页面退出、VM clear、FGS 重建或进程冷启动时，合法任务可能失去运行根；直接加 Worker 则会创建第二套 runtime/engine。要支持无人值守与冷启动触发，必须迁移实际资源 owner，而不是加一个 Application 单例字段。

## 决策

1. **进程级单一执行根**：把实际共享执行服务根移到 application-only 的进程 holder；初始化单飞、幂等，按 process role（主进程 / 既有 native 工作进程 / isolated 脚本进程）只装配所需服务。isolated 脚本进程不得初始化 MCA DB/模型/主执行根。
2. **页面 attach/detach 只观察**：UI 不再销毁仍有合法任务的资源根；退出/重建时任务按合同存续；idle 释放与未证明停止的 fence 不矛盾。
3. **入口统一取窄能力**：聊天/图片/Local API/Agent/Worker/Receiver 都通过 provider 取得同实例窄能力；Worker/Receiver 只提交 inbox，不 new engine、不等 Activity。
4. **一次切换**：同一候选内不允许新旧两套 owner 同时活跃；旧资源停止确认/账本核对后才把准入切给新 root。保存组件依赖不得含 VM/Activity 引用。
5. **冷启动合同**：启动先完成资源/恢复核账，再放行触发 dispatch；加载模型只用用户已授权的 workflow/模型绑定，不拿"当前聊天选中模型"代替固定绑定。
6. **停止与恢复**：保留 typed stop settlement、native 隔离与 idle 释放；进程死亡后按 executionKind/recoveryVersion 核账，未知停止保留 fence。

## 备选方案

- 继续 parent 到 VM scope，仅在前台运行：可作为过渡，但不得宣称后台/冷启动能力；文档与 UI 必须显示真实状态。
- 每个 Worker/Receiver 自建 runtime：拒绝。会产生第二 engine/owner 与重复 stop。
- Application 单例直接 new 全部服务：拒绝。会破坏 process role 隔离与测试边界。

## 后果

- 需要画实例创建/引用/关闭/进程死亡图，列聊天/图片/API/Agent 各入口取得的实例 ID，写独立迁移时序。
- 组合根、生命周期与共享模型写集由整合者独占；该切片避开大规模 UI/模型底层改动，禁止与其他组合根写集并发抢写。
- 设备冷启动、FGS 重建、进程死亡与 stop 证据在真机完成前保持 NOT_RUN/BLOCKED；不得用 fake 或杀 WorkRequest 冒充已停。

## 退出条件

冷启动不打开页面可处理合法事件；页面退出/重建任务存续；聊天/图片/API/Agent 交替与取消共用同一仲裁且无双 native 实例；旧 stop 通知只停目标 run；REG-05/WF-26 有真实证据。

## 实现增补（2026-10-25）

已落地的最小单一切换（无新旧双 owner）：

1. `McaApplication` 持有 application-only `McaAppContainer` 与唯一 `McaProcessRuntimeRoot`；两者都在同一
   锁下单飞创建。`closeProcessRuntimeRoot()` 同时清空并关闭两者，下一次启动重新创建。
2. `McaProcessRuntimeRoot` 以内核 `SupervisorJob + Dispatchers.Main.immediate` 构造 `MainRuntimeAssembly`
   （engine + AgentHostAssembly + DSH host），页面 VM 只消费 `runtimeRoot.assembly`。
3. `AppStartupViewModel` 从 Application 取 container/root，不再自建 container；`MainViewModel` 不再构造
   `MainRuntimeAssembly`、不再接受 `injectedEngine`，也不在 `onCleared` 关闭 Agent host / image worker
   client / isolated runners。`McaProcessRuntimeRoot.close()` 是这些资源的唯一结算点。
4. 资源根在“最后一个 Activity 明确 finishing”时结算（`ProcessActivityTracker` 排除配置变更与仍有前台
   Activity 的情况），`Application.onTerminate` 也结算；普通 home 后台不销毁 Activity，根继续存活。
5. `AgentRuntimeAssembly` 的恢复核账在 root 构造时执行，因此冷启动 Worker/后续入口拿到 root 时恢复已完成。

仍未完成（保持 NOT_RUN）：Worker/Receiver inbox 与冷启动触发 dispatch、Local API/DSH 的入口级复用、
真实设备的页面重建/后台/冷启动/进程死亡/取消/资源释放/聊天-图片-API-DSH 共存证据。


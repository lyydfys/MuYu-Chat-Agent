# ADR 0007：Workflow 图定义、同 root 执行与恢复

- 状态：Proposed（N00 草案；N17–N22 实现中）
- 日期：2026-10-20
- 关联：README §5、DESIGN §7、EXECUTION_PACK N17–N22、PARITY_ACCEPTANCE WF-01..30
- 兼容：旧 recipe 文件、手动创建普通任务与既有管理页保留

## 背景

V1 的 workflow 是文件 recipe + 预检 + 手动创建普通 Agent 任务，没有图定义、节点执行、触发或恢复。third-party 基线是可编辑 DAG（Trigger/Execute/Condition/Logic/Extract）、参数引用、环检测与拓扑执行、六种触发、WorkManager 调度。MCA 还需要持久节点恢复、park 与同 root 子任务，这些是增强目标而非上游已证实能力。

直接复用普通任务 FIFO（一个 active run）会让父 frame 等待子 AgentTask 时死锁；另建子 run 或第二套 loop 会复制状态机与资源 owner。

## 决策

1. **draft/published 分离**：definition repository 是唯一 writer；draft 用 CAS 修改，publish 固定 `definitionHash` + 依赖 lock。run 只读不可变版本，旧 run 不跟随 draft/latest。
2. **节点与绑定**：Trigger/Tool/AgentTask/Model/Script/Condition/Logic/Transform/Extract/Wait/Approval/Subworkflow/Output；typed inputs/outputs/bindings，受限 JSON 路径/表达式 AST，禁止任意 eval；secret 只用 credentialRef。
3. **静态验证**：唯一 ID、边端点、类型/required、可达/终结、拓扑无环、分支完整、依赖 hash、子流程递归/层数、大小/预算；错误定位到 node/field；不静默裁剪。
4. **单一执行 root**：`AgentRunOwner` 仍是 run 唯一 writer；图是它选用的执行策略，只产出节点事实。Tool 走 InvocationPipeline，Model 走统一 attempt gateway/lease，Script 走包 host；AgentTask 使用**同 root 目标执行 frame**，绝不 start 子 run 排到父后；Subworkflow 固定子版本。
5. **节点事务与恢复**：node output + 成功 checkpoint + 后继 ready 同事务；wait/approval/timer 用 durable token，确认计算停止后 CAS 从 ACTIVE→PARKED 释放 active slot；wake 以 `(runId,epoch,checkpointRevision,wakeId)` 去重后重新排队；启动先单飞分页核账全部 nonterminal，按 `executionKind/recoveryVersion` 分派，完成前不放行新准入。
6. **未知副作用**：无 receipt 的外部动作转 `UNKNOWN/NEEDS_REVIEW`，不自动重放；纯节点/稳定读取才可按输入 hash 重放；补偿是独立受审批动作，不宣称 DB 能保证远端 exactly-once。
7. **触发**：六基线 manual/schedule/tasker/intent/speech/app_open 都先写 durable inbox；slot key 唯一约束去重；WorkManager 只唤醒，不是精确定时器/模型 owner；时区/DST/missed-slot 如实；无人值守默认 LOCAL_ONLY 只读，扩大自动化必须绑定 definition/tool/package hash、scope/args、次数/期限、来源与撤销版本的窄授权，超界 WAITING_APPROVAL。

## 备选方案

- 子 run 排队 + 父等待：拒绝。父占 active slot 会造成死锁，且两次 run 状态难以合并。
- 任意回边 DAG：拒绝。采用显式 bounded loop/foreach 控制节点，保证可恢复与预算。
- 用 WorkManager 当执行引擎/模型 owner：拒绝。它只负责持久唤醒。

## 后果

- 新增 workflow definition/run/node attempt/checkpoint/trigger inbox/slot 表（迁移版本由整合者分配）；旧 recipe 保留只读/编辑，转换生成新 version 并记录 sourceHash。
- 图执行失败的终态由 definition failure policy 决定；补偿可失败，不把部分产物当成功。
- 编辑器/运行详情只做 draft 投影与 intent，仓储唯一保存；手机优先列表/表单，画布与列表同一 draft 事实源。

## 退出条件

WF-01..30 逐项有候选证据；无效图拒绝且零副作用、分支/fan-in 不死锁、同 root 子 frame 完成任务、节点事务各崩溃点可恢复、park 不饿死普通任务、六触发各留真实入口证据、无人值守越界不执行；旧 recipe/任务/历史连续。

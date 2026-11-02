# ADR 0004：Agent 工具协议与单一调用流水线

- 状态：Proposed（N00 草案；实现进行中，未形成运行验收）
- 日期：2026-10-20
- 关联：README §5、DESIGN §4、EXECUTION_PACK N03/N05/N06、PARITY_ACCEPTANCE TOOL-01..13/26/27
- 取代/补充：ADR 0002（V1 runtime）中的 prompted 单调用执行路径保持兼容，不被删除

## 背景

V1 已具备 prompted 单工具回合、冻结命令、一次性审批、调用账本、预算、取消与 typed stop settlement。但静态审查发现两个结构性问题：

1. 云端 mapper 可声明 `NATIVE`，实际最终请求的 `tools`/`toolExchanges` 仍为空，结果只回 `visibleText`（B03）。能力标签与真实 wire 不一致。
2. 审批消费、不可变决议、invocation 状态、dispatch intent 与 run/frame 关联分布在 broker 与 owner 的两次提交之间（DESIGN §4.2）。崩溃窗口可能产生"已消费但无可恢复动作"或"未消费先派发"。

同时，workflow 节点、脚本 host-call 需要提交工具提议，但不得各自持有可绕过审批的执行器。

## 决策

1. **版本化模型回合**：新增 `AgentModelTurn`（完整 text parts、结构化 calls、finishReason、usage、terminalProof）。`PROMPTED_V1` 严格单调用格式保留；`PROMPTED_V2` 是独立 parser；原生 provider 各自映射到同一 canonical transcript。完整终态与 schema 校验前不派发工具。
2. **单一 `AgentToolInvocationPipeline`**：模型 / workflow / script-host 只能提交带来源的 propose，拿到窄 `propose/await/cancel` 能力，不能拿到任意 executor。
3. **一个仓储事务**：审批 CAS 消费 → 不可变 decision event → invocation 状态 → dispatch outbox → run/frame/node 关联同事务提交。executor 在事务外，只能认领已提交 outbox。
4. **效果与恢复矩阵**：纯计算/稳定快照读取可按输入 hash 重放；原子文件写按目标 hash 核账；幂等远端用同一 logical id；未知外部副作用转 `NEEDS_REVIEW/UNKNOWN`，不自动重放。`CONSUMED` 既不证明已执行，也不允许重新签发无约束审批。
5. **批调用**：整批验证/落账后派发；仅可信 READ_ONLY、无 egress、无冲突资源、执行器可取消的调用最多 3 并行，其余按原序串行；结果按模型原 ordinal 回传；每项独立审批与终态。

## 备选方案

- 保留 V1 旁路作为"保险"：拒绝。两条可执行 pipeline 会产生第二套终态与账本，无法证明 exactly-once。
- 只修 mappers、不引入 outbox：拒绝。审批消费与派发仍在两个事务，崩溃窗口未关闭。
- 在 DB 事务内执行工具：拒结。违反"事务内禁止网络/native/跨进程/用户审批"。

## 后果

- `AgentRunOwner` 仍是 run 状态唯一 writer；新增流水线只产生调用事实。
- 旧 transcript/审批/命令需要 upcast fixture；旧 workspace scope 按已知规则升级，memory 伪路径审批失效并要求重新申请。
- 需要真实文件原子写与故障注入测试（consume/commit/dispatch/receipt/result 各边界）。
- 未通过真实 wire/设备/模型验证前，NATIVE 能力状态保持 UNKNOWN/NOT_RUN，不以标签宣称完成。

## 退出条件

旧 loop 的全部调用迁移到新流水线并删除旁路；审批消费、派发、回执、结果五处死亡均有恢复证据；`TOOL-01..13/26` 逐项有候选绑定结果；回归不改变聊天/图片既有行为。
